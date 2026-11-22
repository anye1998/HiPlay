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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays the phone's AAC stream, one player per purpose.
 *
 * The phone separates its sound into streams and labels each with an Android usage: music, navigation
 * guidance, an assistant, a call, a ringtone. The previous version decoded all of them into one
 * `AudioTrack`, so a guidance prompt and the music it interrupts were the same sound on the same
 * channel and fought over the same focus.
 *
 * Now each [HiCarAudioRoute] owns a lane — its own queue, decoder and track, built lazily on the
 * first frame that belongs to it. Two consequences follow, and only one of them is about routing:
 *
 * - **Routing**: a lane's track can be pinned to a head unit channel number
 *   ([HiCarAudioSettings]), which is the only way to reach a vendor's dedicated navigation speaker.
 * - **Mixing**: when a non-media lane makes a sound, the media lane is attenuated locally
 *   ([DUCK_LEVEL]) for as long as that sound lasts. This is done here rather than with a second
 *   audio-focus request because the system sees one app, not five: two requests from the same app do
 *   not duck each other, they just fight over one focus. A local gain is what a car head unit does
 *   anyway.
 *
 * Focus is still requested once, for the media lane, and still honoured: when another app takes it
 * the media lane pauses and drains — on a head unit that is the difference between "navigation
 * prompt" and "both talking at once" when the car's own radio starts.
 *
 * Ported from `AudioSink` in huducat586/hicar-android-receiver (MIT); the lane split and the channel
 * choice follow GGBondLab/OpenHC (GPL-3.0-only), `platform/media/AudioPlayer` and
 * `AudioStreamChoice`.
 */
internal class HiCarAudioSink(
    context: Context,
    private val onLog: (String) -> Unit,
    initial: HiCarAudioSettings = HiCarAudioSettings(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(AudioManager::class.java)
    private var focusRequest: Any? = null

    @Volatile private var settings = initial
    private val lanes = LinkedHashMap<HiCarAudioRoute, Lane>()

    /**
     * Frames per RTP stream, keyed by the SSRC-derived id.
     *
     * The same diagnostic as before: it shows whether the phone is really sending a second stream
     * before anything is routed, which is the difference between "the phone mixed guidance into
     * music" and "our split is broken".
     */
    private val streamFrames = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    @Volatile private var closed = false
    @Volatile private var focused = true

    /**
     * Whether the media lane currently holds audio focus.
     *
     * The screen needs it for one decision: the media-button session exists only while we hold focus,
     * because that is what makes the whole unit's media keys ours to hand to the phone. Reported from
     * the focus listener, which Android calls on the main thread.
     */
    @Volatile var onFocusChanged: ((Boolean) -> Unit)? = null
    @Volatile private var paused = false

    /**
     * Speed-linked gain for music, in dB. Only the media lane is multiplied: navigation prompts and
     * calls must not get louder just because the car is on a motorway.
     */
    @Volatile private var speedGainDb = 0.0

    /** Until this instant (nanoTime) the media lane plays attenuated. */
    private val duckedUntil = AtomicLong(0)

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
    }

    fun offer(frame: HiCarAudioFrame) {
        streamFrames.merge(frame.stream, 1L, Long::plus)
        if (closed) {
            frame.wipe()
            return
        }
        if (frame.route == HiCarAudioRoute.MEDIA && (!focused || paused)) {
            frame.wipe()
            return
        }
        lane(frame.route).enqueue(frame)
    }

    fun reset() {
        for (lane in lanes.values) lane.reset()
    }

    /**
     * Takes new channel choices for the next track that is built.
     *
     * A track already playing keeps its channel: rebuilding it would cut the sound for a few hundred
     * milliseconds, which is worse than the setting taking effect on the next guidance prompt. The
     * settings page says "next connection" for the same reason.
     */
    fun updateSettings(next: HiCarAudioSettings) {
        if (next == settings) return
        settings = next
        onLog(
            "audio: channels media=${HiCarAudioSettings.label(next.mediaStream)}, " +
                "navigation=${HiCarAudioSettings.label(next.navigationStream)} (from the next track on)",
        )
    }

    /**
     * Music has to make room.
     *
     * The phone tells this side when its own guidance takes over the audio focus; on a session where
     * guidance is mixed into the music stream, that message is the only evidence that a prompt is
     * talking, and ducking here is what keeps the prompt audible over the music.
     */
    fun setNavigationFocus(active: Boolean) {
        if (active) holdDuck() else releaseDuck()
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

    /** Plays a short tone on the music channel; false when there is nothing to play it through. */
    fun previewMedia(): Boolean = preview(HiCarAudioRoute.MEDIA)

    /** Plays a short tone on the navigation channel; false when there is nothing to play it through. */
    fun previewNavigation(): Boolean = preview(HiCarAudioRoute.NAVIGATION)

    /** Which channels are in force, for the settings page to describe. */
    fun describe(): String =
        "music ${HiCarAudioSettings.label(settings.mediaStream)}, " +
            "navigation ${HiCarAudioSettings.label(settings.navigationStream)}"

    override fun close() {
        if (closed) return
        closed = true
        for (lane in lanes.values) lane.close()
        lanes.clear()
        abandonFocus()
    }

    // ------------------------------------------------------------------ lanes

    private fun lane(route: HiCarAudioRoute): Lane = synchronized(lanes) {
        lanes.getOrPut(route) { Lane(route) }
    }

    /**
     * One purpose's player.
     *
     * Every field here used to exist once for the whole sink. Copying them per lane is what makes a
     * navigation prompt able to be on a different channel, with a different decoder, without
     * touching the music's buffer.
     */
    private inner class Lane(val route: HiCarAudioRoute) {
        private val queue = ArrayBlockingQueue<HiCarAudioFrame>(QUEUE_FRAMES)
        private var worker: Thread? = null
        private var decoder: MediaCodec? = null
        private var track: AudioTrack? = null
        private var rate = 0
        private var channels = 0
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var decoded = 0L
        private var appliedVolume = -1f
        @Volatile private var closed = false

        fun enqueue(frame: HiCarAudioFrame) {
            synchronized(this) {
                if (closed) {
                    frame.wipe()
                    return
                }
                startWorker()
                if (!queue.offer(frame)) {
                    // Dropping the oldest keeps latency bounded: audio a second late is worse than a
                    // click, and a queue that grows without limit ends up minutes behind.
                    queue.poll()?.wipe()
                    if (!queue.offer(frame)) frame.wipe()
                }
            }
        }

        fun reset() {
            while (true) {
                val frame = queue.poll() ?: break
                frame.wipe()
            }
            val audio = track
            if (audio != null) runCatching { audio.pause(); audio.flush(); audio.play() }
        }

        fun close() {
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
            decoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            decoder = null
        }

        private fun startWorker() {
            if (worker != null) return
            worker = Thread({ run() }, "hicar-audio-${route.name.lowercase()}").also {
                it.isDaemon = true
                it.start()
            }
        }

        private fun run() {
            var codec: MediaCodec? = null
            var pending: HiCarAudioFrame? = null
            val info = MediaCodec.BufferInfo()
            try {
                while (!closed && !this@HiCarAudioSink.closed) {
                    if (route == HiCarAudioRoute.MEDIA && paused) {
                        pending?.wipe()
                        pending = null
                        applyVolume(0f)
                        Thread.sleep(20)
                        continue
                    }
                    // An idle lane still has to notice that the duck is over, or the music would stay
                    // quiet until the next track starts.
                    applyVolume(targetVolume())
                    if (pending == null) pending = queue.poll(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS)
                    val frame = pending
                    if (frame != null) {
                        if (codec == null || frame.sampleRate != rate || frame.channels != channels) {
                            codec?.let { old -> runCatching { old.stop() }; runCatching { old.release() } }
                            codec = createDecoder(frame.sampleRate, frame.rateIndex, frame.channels)
                            decoder = codec
                            rate = frame.sampleRate
                            channels = frame.channels
                        }
                        val input = codec.dequeueInputBuffer(5_000)
                        if (input >= 0) {
                            val buffer = codec.getInputBuffer(input) ?: throw IOException("AAC input buffer")
                            if (buffer.capacity() < frame.data.size) throw IOException("AAC input capacity")
                            buffer.clear()
                            buffer.put(frame.data)
                            codec.queueInputBuffer(input, 0, frame.data.size, 0, 0)
                            frame.wipe()
                            pending = null
                        }
                    }
                    val output = codec?.dequeueOutputBuffer(info, 5_000) ?: MediaCodec.INFO_TRY_AGAIN_LATER
                    when {
                        output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val actual = codec?.outputFormat ?: continue
                            val sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channelCount = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val pcmEncoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT || channelCount !in 1..2) {
                                throw IOException("AAC output PCM format")
                            }
                            buildTrack(sampleRate, channelCount, pcmEncoding)
                        }

                        output >= 0 -> {
                            try {
                                if (info.size > 0) write(codec?.getOutputBuffer(output))
                            } finally {
                                codec?.releaseOutputBuffer(output, false)
                            }
                        }
                    }
                }
            } catch (failure: IOException) {
                if (!closed) onLog("audio: ${route.name.lowercase()} playback stopped (${failure.message})")
            } catch (failure: RuntimeException) {
                if (!closed) onLog("audio: ${route.name.lowercase()} playback stopped (${failure.javaClass.simpleName})")
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
            // AAC-LC, the rate table index, and the channel configuration — the three fields an
            // Android AAC decoder needs when it is fed raw access units without in-band configuration.
            val asc = (2 shl 11) or (rateIndex shl 7) or (channelCount shl 3)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc ushr 8).toByte(), asc.toByte())))
            val created = MediaCodec.createDecoderByType(MIME)
            created.configure(format, null, null, 0)
            created.start()
            return created
        }

        private fun buildTrack(sampleRate: Int, channelCount: Int, pcmEncoding: Int) {
            track?.let { old ->
                runCatching { old.pause() }
                runCatching { old.flush() }
                runCatching { old.release() }
            }
            encoding = pcmEncoding
            appliedVolume = -1f
            val built = makeTrack(sampleRate, channelCount, pcmEncoding, settings.streamFor(route), route)
            track = built
            built.play()
            val channel = HiCarAudioSettings.label(settings.streamFor(route))
            onLog(
                "audio: ${route.name.lowercase()} lane started, AAC $sampleRate Hz, $channelCount channel(s), $channel",
            )
        }

        private fun targetVolume(): Float {
            if (route != HiCarAudioRoute.MEDIA) return 1f
            if (paused || !focused) return 0f
            return if (System.nanoTime() < duckedUntil.get()) DUCK_LEVEL else 1f
        }

        private fun applyVolume(volume: Float) {
            if (appliedVolume == volume) return
            appliedVolume = volume
            track?.let { runCatching { it.setVolume(volume) } }
        }

        private fun write(buffer: ByteBuffer?) {
            val audio = track ?: return
            val pcm = buffer ?: return
            if (route == HiCarAudioRoute.MEDIA && (paused || !focused)) return
            val gain = if (route == HiCarAudioRoute.MEDIA) speedGainDb else 0.0
            // A non-media lane making a sound is what the music has to make room for.
            if (route != HiCarAudioRoute.MEDIA) holdDuck()
            var written = 0
            if (gain != 0.0 && pcm.hasRemaining()) {
                // AudioTrack can only multiply through a byte array: the buffer has to be copied out,
                // changed, and written back. This is the one allocation on the audio path that the
                // gain feature adds, and it only happens while the car is moving.
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
                while (offset < samples.size && !closed && !this@HiCarAudioSink.closed) {
                    val count = audio.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (count < 0) throw IOException("audio write failed")
                    if (count == 0) break
                    offset += count
                    written += count
                }
            } else {
                while (pcm.hasRemaining() && !closed && !this@HiCarAudioSink.closed) {
                    val count = audio.write(pcm, pcm.remaining(), AudioTrack.WRITE_BLOCKING)
                    if (count < 0) throw IOException("audio write failed")
                    if (count == 0) break
                    written += count
                }
            }
            if (written > 0) {
                decoded++
                if (decoded <= 3 || decoded % 400 == 0L) {
                    val lanesText = streamFrames.entries.sortedBy { it.key }
                        .joinToString(", ") { "${it.key}=${it.value}" }
                    onLog(
                        "audio: $decoded PCM block(s) on ${route.name.lowercase()}" +
                            (if (gain != 0.0) " (+%.1f dB)".format(gain) else "") +
                            "; frames by stream: $lanesText",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ track building

    /**
     * Builds a track for one geometry on one channel.
     *
     * A channel of zero keeps the usage-based track this receiver has always used. Anything else goes
     * through the legacy constructor, which is the only way to name a channel the platform does not
     * know — and a head unit's navigation speaker is usually one of those. A ROM that refuses the
     * number gets the usage route back with a line in the log, because silently losing audio would
     * look like the phone's fault.
     */
    private fun makeTrack(
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
        streamType: Int,
        route: HiCarAudioRoute,
    ): AudioTrack {
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, pcmEncoding)
        if (minimum < 1) throw IOException("audio buffer unavailable")
        val bytes = maxOf(minimum * 2, sampleRate * channelCount * 2 / 10)
        if (streamType != 0) {
            val pinned = runCatching {
                @Suppress("DEPRECATION")
                AudioTrack(streamType, sampleRate, channelMask, pcmEncoding, bytes, AudioTrack.MODE_STREAM)
            }.getOrNull()
            if (pinned != null && pinned.state == AudioTrack.STATE_INITIALIZED) return pinned
            pinned?.let { refused -> runCatching { refused.release() } }
            onLog("audio: channel $streamType is not usable on this ROM; ${route.name.lowercase()} uses the usage route")
        }
        val built = AudioTrack.Builder()
            .setAudioAttributes(attributesFor(route))
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(pcmEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { built.release() }
            throw IOException("AudioTrack state")
        }
        return built
    }

    /**
     * The usage each lane declares.
     *
     * These are what the head unit uses when no channel number is chosen, and they are also what
     * decides whether the system can duck this sound on its own. Navigation deliberately asks for
     * guidance rather than music: a unit that has a "navigation" volume knob keys off this.
     */
    private fun attributesFor(route: HiCarAudioRoute): AudioAttributes {
        val usage = when (route) {
            HiCarAudioRoute.MEDIA -> AudioAttributes.USAGE_MEDIA
            HiCarAudioRoute.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            HiCarAudioRoute.VOICE -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            HiCarAudioRoute.CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            HiCarAudioRoute.RINGTONE -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }
        val contentType = when (route) {
            HiCarAudioRoute.MEDIA -> AudioAttributes.CONTENT_TYPE_MUSIC
            HiCarAudioRoute.NAVIGATION -> AudioAttributes.CONTENT_TYPE_SPEECH
            HiCarAudioRoute.CALL -> AudioAttributes.CONTENT_TYPE_SPEECH
            HiCarAudioRoute.RINGTONE -> AudioAttributes.CONTENT_TYPE_SONIFICATION
            HiCarAudioRoute.VOICE -> AudioAttributes.CONTENT_TYPE_SONIFICATION
        }
        return AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build()
    }

    // ------------------------------------------------------------------ ducking

    private fun holdDuck() {
        duckedUntil.set(System.nanoTime() + DUCK_HOLD_NANOS)
    }

    private fun releaseDuck() {
        duckedUntil.set(0)
    }

    // ------------------------------------------------------------------ preview

    private fun preview(route: HiCarAudioRoute): Boolean {
        if (!ready) {
            onLog("audio: no decoder in this image, so nothing can be previewed")
            return false
        }
        val pcm = runCatching { HiCarPreviewTone.pcm16() }.getOrNull() ?: return false
        val streamType = settings.streamFor(route)
        val name = route.name.lowercase()
        Thread({
            var audio: AudioTrack? = null
            try {
                audio = makeTrack(HiCarPreviewTone.SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT, streamType, route)
                audio.play()
                var offset = 0
                while (offset < pcm.size) {
                    val count = audio.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) break
                    offset += count
                }
                // The head unit reports "write returned" long before the amplifier has finished.
                Thread.sleep(PREVIEW_TAIL_MILLIS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                onLog("audio: preview on $name failed (${failure.javaClass.simpleName})")
            } finally {
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
            }
        }, "hicar-audio-preview-$name").also {
            it.isDaemon = true
            it.start()
        }
        onLog("audio: preview tone on $name (${HiCarAudioSettings.label(streamType)})")
        return true
    }

    // ------------------------------------------------------------------ focus

    private fun requestFocus() {
        val audioManager = manager ?: return
        val change: (Int) -> Unit = { value ->
            focused = value == AudioManager.AUDIOFOCUS_GAIN
            paused = !focused
            if (!focused) reset()
            onFocusChanged?.invoke(focused)
            Unit
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change)
                .build()
            focusRequest = request
            focused = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            focused = audioManager.requestAudioFocus(change, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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

        /** How long an idle lane waits before it re-checks its volume. */
        const val IDLE_POLL_MILLIS = 100L

        /** Music drops to about -10 dB while a prompt is talking. */
        const val DUCK_LEVEL = 0.32f

        /**
         * How long one prompt keeps the music down.
         *
         * The phone does not say when a prompt ends — there is no "done" message, only the next
         * stream's data. One and a half seconds covers a long turn instruction and is short enough
         * that music comes back before the driver notices.
         */
        const val DUCK_HOLD_NANOS = 1_500_000_000L

        /** Let the amplifier finish the preview tone; `write` returns when the buffer is accepted. */
        const val PREVIEW_TAIL_MILLIS = 150L
    }
}
