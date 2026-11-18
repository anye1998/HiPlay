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

    @Volatile private var closed = false
    @Volatile private var focused = true
    @Volatile private var paused = false

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
        val info = MediaCodec.BufferInfo()
        try {
            while (!closed) {
                if (paused) {
                    pending?.wipe()
                    pending = null
                    Thread.sleep(20)
                    continue
                }
                if (pending == null) pending = queue.poll()
                val frame = pending
                if (frame != null) {
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
                        buildTrack(sampleRate, channelCount, encoding)
                    }

                    output >= 0 -> {
                        try {
                            if (info.size > 0) write(decoder?.getOutputBuffer(output))
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

    private fun buildTrack(sampleRate: Int, channelCount: Int, encoding: Int) {
        track?.let { old -> runCatching { old.release() } }
        val mask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
        if (minimum < 1) throw IOException("audio buffer unavailable")
        val audio = AudioTrack.Builder()
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
                    .setChannelMask(mask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate * channelCount * 2 / 10))
            .build()
        if (audio.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { audio.release() }
            throw IOException("AudioTrack state")
        }
        audio.play()
        track = audio
        onLog("audio: AAC $sampleRate Hz, $channelCount channel(s); playback started")
    }

    private fun write(buffer: ByteBuffer?) {
        val audio = track ?: return
        val pcm = buffer ?: return
        if (!focused || paused) return
        var written = 0
        while (pcm.hasRemaining() && !closed && focused && !paused) {
            val count = audio.write(pcm, pcm.remaining(), AudioTrack.WRITE_BLOCKING)
            if (count < 0) throw IOException("audio write failed")
            if (count == 0) break
            written += count
        }
        if (written > 0) {
            decoded++
            if (decoded <= 3 || decoded % 400 == 0L) onLog("audio: $decoded PCM block(s) written to the output device")
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
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!focused) onLog("audio: focus unavailable; the speaker service will stay silent until it returns")
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
