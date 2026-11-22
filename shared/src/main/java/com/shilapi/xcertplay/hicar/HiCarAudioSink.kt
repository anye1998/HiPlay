package com.shilapi.xcertplay.hicar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

/**
 * Plays the phone's AAC stream.
 *
 * The decoder and the `AudioTrack` are built lazily from the first frame, because the sample rate and
 * channel count only exist once audio actually arrives; a stream that changes format mid-session
 * rebuilds both rather than writing wrongly-shaped PCM into the old track.
 *
 * Audio focus is requested once and honoured: when another app takes it, playback pauses and the
 * queue is drained, which on a head unit is the difference between "navigation prompt" and "both
 * talking at once".
 *
 * Ported from `AudioSink` in huducat586/hicar-android-receiver (MIT).
 */
internal class HiCarAudioSink(context: Context, private val onLog: (String) -> Unit) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(AudioManager::class.java)
    private val queue = ArrayBlockingQueue<HiCarAudioFrame>(QUEUE_FRAMES)
    private var focusRequest: Any? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var rate = 0
    private var channels = 0
    private var decoded = 0L
    private var mask = AudioFormat.CHANNEL_OUT_STEREO
    private var encoding = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Which output route the sound takes, and which one it was built for.
     *
     * A head unit gives navigation its own stream type so a guidance prompt is not attenuated with
     * the music it is playing over. The phone mixes its audio into one stream, so the only thing this
     * side can do is move the track when the phone says guidance is talking - which is what
     * [setStreamType] is called for. Zero means "no stream type", the usage-based route that this
     * receiver has always used.
     */
    @Volatile private var wantedStreamType = 0
    private var builtStreamType = 0

    /**
     * Frames per RTP stream, keyed by the SSRC-derived id.
     *
     * The phone may carry navigation guidance and music as two streams on one UDP port. They are
     * currently decoded into the same track, which is exactly the complaint this map exists to
     * answer: it shows whether a second stream is even arriving before anything is routed.
     */
    private val streamFrames = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    @Volatile private var closed = false
    @Volatile private var focused = true

    /**
     * Whether this sink currently holds audio focus.
     *
     * The screen needs it for one decision: the media-button session exists only while we hold focus,
     * because that is what makes the whole unit's media keys ours to hand to the phone. Reported from
     * the focus listener, which Android calls on the main thread.
     */
    @Volatile var onFocusChanged: ((Boolean) -> Unit)? = null
    @Volatile private var paused = false

    /**
     * Speed-linked gain for music, in dB. Only frames the phone labels as media are multiplied:
     * navigation prompts and calls must not get louder just because the car is on a motorway.
     */
    @Volatile private var speedGainDb = 0.0

    /** True when the platform has an AAC decoder; without one, the speaker stays unannounced. */
    val ready: Boolean

    init {
        val probe = MediaFormat.createAudioFormat(MIME, 48_000, 2)
        ready = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) != null
        if (!ready) onLog("audio: no AAC decoder in this image; the speaker service will not be announced")
    }

    fun start() {
        if (!ready || closed) return
        requestFocus()
        worker = Thread({ run() }, "hicar-audio").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun offer(frame: HiCarAudioFrame) {
        streamFrames.merge(frame.stream, 1L, Long::plus)
        if (closed || !focused) {
            frame.wipe()
            return
        }
        if (!queue.offer(frame)) {
            // Dropping the oldest keeps latency bounded: audio that is a second late is worse than a
            // click, and a queue that grows without limit ends up minutes behind.
            queue.poll()?.wipe()
            if (!queue.offer(frame)) frame.wipe()
        }
    }

    fun reset() {
        while (true) {
            val frame = queue.poll() ?: break
            frame.wipe()
        }
        val audio = track
        if (audio != null) runCatching { audio.pause(); audio.flush() }
    }

    /**
     * Asks for a different output route, e.g. the navigation channel while guidance is talking.
     *
     * The rebuild happens on the playback thread at the next buffer, so the change cannot race with
     * a write. Passing the same value twice is free, which matters because the phone repeats its
     * navigation focus on every guidance event.
     */
    fun setStreamType(type: Int) {
        if (type != wantedStreamType) wantedStreamType = type
    }

    /**
     * Sets the gain applied to music, in dB; 0 leaves the samples untouched.
     *
     * The value is applied at write time on the playback thread, so a speed update never races a
     * buffer. Non-media routes ignore it entirely.
     */
    fun setSpeedGain(db: Double) {
        speedGainDb = if (db.isFinite()) db.coerceIn(0.0, HiCarSpeedGainCurve.MAX_ALLOWED_DB) else 0.0
    }

    /** The gain currently applied to music, for the settings screen. */
    val currentSpeedGainDb: Double get() = speedGainDb

    override fun close() {
        if (closed) return
        closed = true
        worker?.let { thread ->
            runCatching { thread.join(300) }
            worker = null
        }
        queue.forEach { it.wipe() }
        queue.clear()
        track?.let { audio ->
            runCatching { audio.pause() }
            runCatching { audio.flush() }
            runCatching { audio.release() }
        }
        track = null
        codec?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        codec = null
        abandonFocus()
    }

    private fun run() {
        var decoder: MediaCodec? = null
        var pending: HiCarAudioFrame? = null
        // The route travels with the frame that produced the PCM being written. A decoder can hold a
        // frame for one buffer, so the value is updated on input rather than guessed at output.
        var currentRoute = HiCarAudioRoute.MEDIA
        val info = MediaCodec.BufferInfo()
        try {
            while (!closed) {
                if (paused) {
                    pending?.wipe()
                    pending = null
                    Thread.sleep(20)
                    continue
                }
                // A route change is applied here rather than from the caller so that it cannot land
                // in the middle of a write; it costs one buffer of silence.
                if (track != null && rate > 0 && wantedStreamType != builtStreamType) {
                    buildTrack(rate, channels, encoding, wantedStreamType)
                }
                if (pending == null) pending = queue.poll()
                val frame = pending
                if (frame != null) {
                    currentRoute = frame.route
                    if (decoder == null || frame.sampleRate != rate || frame.channels != channels) {
                        decoder?.let { old -> runCatching { old.stop() }; runCatching { old.release() } }
                        decoder = createDecoder(frame.sampleRate, frame.rateIndex, frame.channels)
                        codec = decoder
                        rate = frame.sampleRate
                        channels = frame.channels
                    }
                    val input = decoder.dequeueInputBuffer(5_000)
                    if (input >= 0) {
                        val buffer = decoder.getInputBuffer(input) ?: throw IOException("AAC input buffer")
                        if (buffer.capacity() < frame.data.size) throw IOException("AAC input capacity")
                        buffer.clear()
                        buffer.put(frame.data)
                        decoder.queueInputBuffer(input, 0, frame.data.size, 0, 0)
                        frame.wipe()
                        pending = null
                    }
                }
                val output = decoder?.dequeueOutputBuffer(info, 5_000) ?: MediaCodec.INFO_TRY_AGAIN_LATER
                when {
                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = decoder?.outputFormat ?: continue
                        val sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channelCount = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        if (encoding != AudioFormat.ENCODING_PCM_16BIT || channelCount !in 1..2) {
                            throw IOException("AAC output PCM format")
                        }
                        buildTrack(sampleRate, channelCount, encoding, wantedStreamType)
                    }

                    output >= 0 -> {
                        try {
                            if (info.size > 0) write(decoder?.getOutputBuffer(output), currentRoute)
                        } finally {
                            decoder?.releaseOutputBuffer(output, false)
                        }
                    }
                }
            }
        } catch (failure: IOException) {
            if (!closed) onLog("audio: playback stopped (${failure.message})")
        } catch (failure: RuntimeException) {
            if (!closed) onLog("audio: playback stopped (${failure.javaClass.simpleName})")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pending?.wipe()
        }
    }

    private fun createDecoder(sampleRate: Int, rateIndex: Int, channelCount: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        // AAC-LC, the rate table index, and the channel configuration — the three fields an Android
        // AAC decoder needs when it is fed raw access units without in-band configuration.
        val asc = (2 shl 11) or (rateIndex shl 7) or (channelCount shl 3)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc ushr 8).toByte(), asc.toByte())))
        val decoder = MediaCodec.createDecoderByType(MIME)
        decoder.configure(format, null, null, 0)
        decoder.start()
        return decoder
    }

    /**
     * Builds the output track for one geometry, on one route.
     *
     * A stream type of zero keeps the usage-based track this receiver has always used. Anything else
     * goes through the legacy constructor, which is the only way to name a stream type the platform
     * does not know - and this head unit's navigation channel is one of those. A ROM that refuses
     * the number gets the media route back with a line in the log, because silently losing audio
     * would look like the phone's fault.
     */
    private fun buildTrack(sampleRate: Int, channelCount: Int, encoding: Int, streamType: Int) {
        track?.let { old ->
            runCatching { old.pause() }
            runCatching { old.flush() }
            runCatching { old.release() }
        }
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minimum < 1) throw IOException("audio buffer unavailable")
        val bytes = maxOf(minimum * 2, sampleRate * channelCount * 2 / 10)
        mask = channelMask
        this.encoding = encoding
        var effective = 0
        var audio: AudioTrack? = null
        if (streamType != 0) {
            audio = runCatching {
                @Suppress("DEPRECATION")
                AudioTrack(streamType, sampleRate, channelMask, encoding, bytes, AudioTrack.MODE_STREAM)
            }.getOrNull()
            if (audio != null && audio.state == AudioTrack.STATE_INITIALIZED) {
                effective = streamType
            } else {
                audio?.let { refused -> runCatching { refused.release() } }
                audio = null
                onLog("audio: stream type $streamType is not usable on this ROM; the media route stays")
            }
        }
        if (audio == null) {
            audio = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bytes)
                .build()
        }
        val built: AudioTrack = audio ?: throw IOException("AudioTrack unavailable")
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { built.release() }
            throw IOException("AudioTrack state")
        }
        built.play()
        track = built
        builtStreamType = effective
        val route = if (effective == 0) "the media route" else "stream type $effective"
        onLog("audio: AAC $sampleRate Hz, $channelCount channel(s); playback started on $route")
    }

    private fun write(buffer: ByteBuffer?, route: HiCarAudioRoute) {
        val audio = track ?: return
        val pcm = buffer ?: return
        if (!focused || paused) return
        val gain = if (route == HiCarAudioRoute.MEDIA) speedGainDb else 0.0
        var written = 0
        if (gain != 0.0 && pcm.hasRemaining()) {
            // AudioTrack can only multiply through a byte array: the buffer has to be copied out,
            // changed, and written back. This is the one allocation on the audio path that the gain
            // feature adds, and it only happens while the car is moving.
            val samples = ByteArray(pcm.remaining())
            pcm.get(samples)
            val stats = HiCarPcmGain.applyPcm16(samples, 0, samples.size, gain)
            if (stats.limited > 0 && (decoded <= 3 || decoded % 400 == 0L)) {
                onLog(
                    "audio: speed gain +%.1f dB, limiter engaged on %d of %d samples".format(
                        gain, stats.limited, stats.samples,
                    ),
                )
            }
            var offset = 0
            while (offset < samples.size && !closed && focused && !paused) {
                val count = audio.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                if (count < 0) throw IOException("audio write failed")
                if (count == 0) break
                offset += count
                written += count
            }
        } else {
            while (pcm.hasRemaining() && !closed && focused && !paused) {
                val count = audio.write(pcm, pcm.remaining(), AudioTrack.WRITE_BLOCKING)
                if (count < 0) throw IOException("audio write failed")
                if (count == 0) break
                written += count
            }
        }
        if (written > 0) {
            decoded++
            if (decoded <= 3 || decoded % 400 == 0L) {
                val lanes = streamFrames.entries.sortedBy { it.key }
                    .joinToString(", ") { "${it.key}=${it.value}" }
                onLog(
                    "audio: $decoded PCM block(s) written on ${route.name.lowercase()}" +
                        (if (gain != 0.0) " (+%.1f dB)".format(gain) else "") +
                        "; frames by stream: $lanes",
                )
            }
        }
    }

    private fun requestFocus() {
        val audioManager = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change ->
                    focused = change == AudioManager.AUDIOFOCUS_GAIN
                    paused = !focused
                    if (!focused) reset()
                    onFocusChanged?.invoke(focused)
                }
                .build()
            focusRequest = request
            focused = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            focused = audioManager.requestAudioFocus(
                { change ->
                    focused = change == AudioManager.AUDIOFOCUS_GAIN
                    paused = !focused
                    if (!focused) reset()
                    onFocusChanged?.invoke(focused)
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!focused) onLog("audio: focus unavailable; the speaker service will stay silent until it returns")
        onFocusChanged?.invoke(focused)
    }

    private fun abandonFocus() {
        val audioManager = manager ?: return
        val request = focusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request is android.media.AudioFocusRequest) {
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(null) }
        }
        focusRequest = null
    }

    private companion object {
        const val MIME = "audio/mp4a-latm"
        const val QUEUE_FRAMES = 48
    }
}
