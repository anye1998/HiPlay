package com.shilapi.xcertplay.hicar

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.TextureView
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264 decoder writing to a [Surface], with a window that can come and go.
 *
 * The decoder owns a detached [SurfaceTexture] rather than one created by a view. Two reasons:
 * decoding can start before the activity has a surface (the phone may begin streaming while the
 * screen is still laying out), and a head unit that closes the window should not kill the session.
 * When a [TextureView] becomes available it is pointed at this texture; when it goes away the
 * decoder keeps running and simply stops presenting.
 *
 * Ported from `VideoSink` in huducat586/hicar-android-receiver (MIT): bounded queue, drop rather
 * than block, never record.
 */
class HiCarVideoSink(private val onLog: (String) -> Unit) : AutoCloseable {
    private val frames = ArrayBlockingQueue<Frame>(QUEUE_FRAMES)
    private var worker: Thread? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null

    @Volatile private var running = false

    @Volatile var ready: Boolean = false
        private set

    @Volatile var decoded: Boolean = false
        private set

    @Volatile private var visible = false
    @Volatile private var rendered = 0L
    @Volatile private var generation = 0

    private var received = 0L
    private var dropped = 0L
    private val owner = WeakReference<TextureView>(null)

    /** True once a picture has actually reached a visible window. */
    val presenting: Boolean get() = ready && visible && rendered > 0

    val stats: String
        get() = "decoder=${if (ready) "ready" else "off"}, received=$received, dropped=$dropped, rendered=$rendered"

    private class Frame(val data: ByteArray, val ptsUs: Long)

    /**
     * Starts decoding without a window. Safe to call repeatedly; only the first call does work.
     */
    @Synchronized
    fun prepare(): Boolean {
        if (ready) return true
        surface?.let { if (it.isValid) { startDecoder(); return ready } }
        closeDecoder()
        return try {
            val created = SurfaceTexture(false)
            created.setDefaultBufferSize(DISPLAY_WIDTH, DISPLAY_HEIGHT)
            texture = created
            surface = Surface(created)
            visible = false
            startDecoder()
            if (!ready) closeDecoder()
            ready
        } catch (failure: RuntimeException) {
            onDecoderFailure("surface", failure)
            false
        }
    }

    /** Points the current texture at a view whose surface just became available. */
    @Synchronized
    fun attach(view: TextureView, available: SurfaceTexture) {
        val existing = texture
        val current = surface
        if (existing != null && current != null && current.isValid) {
            if (view.surfaceTexture !== existing) {
                // Handing the view the decoder's texture is what makes frames visible without
                // restarting the decoder. If the platform refuses, say so: a black panel with no
                // explanation is the worst possible outcome of this call.
                val moved = runCatching { view.setSurfaceTexture(existing) }
                onLog(
                    if (moved.isSuccess) "video: window now shows the decoder's texture"
                    else "video: the window kept its own texture (${moved.exceptionOrNull()?.javaClass?.simpleName})",
                )
            }
            visible = true
            rendered = 0
            if (!ready) startDecoder()
            return
        }
        closeDecoder()
        texture = available
        available.setDefaultBufferSize(DISPLAY_WIDTH, DISPLAY_HEIGHT)
        surface = Surface(available)
        visible = true
        startDecoder()
    }

    /** The window is gone: keep decoding, stop presenting. Returns true when the texture is ours. */
    @Synchronized
    fun detach(value: SurfaceTexture): Boolean {
        if (value !== texture) return false
        visible = false
        rendered = 0
        return true
    }

    @Synchronized
    fun setVisible(value: Boolean) {
        visible = value
        if (!value) rendered = 0
    }

    /** Feeds one access unit; called on the media thread. */
    fun offer(kind: Int, timestamp: Long, data: ByteArray) {
        if (kind != 96) return
        if (!ready || data.isEmpty() || data.size > MAX_ACCESS_UNIT) throw IOException("video sink unavailable")
        val frame = Frame(data.copyOf(), timestamp * 1_000_000L / 90_000L)
        if (!frames.offer(frame)) {
            frame.data.fill(0)
            dropped++
        }
        received++
        if (received <= 3 || received % 120 == 0L) {
            onLog("HiSight: $received H.264 access unit(s), $dropped dropped, ${data.size} bytes last")
        }
    }

    override fun close() {
        visible = false
        closeDecoder()
        runCatching { surface?.release() }
        surface = null
        runCatching { texture?.release() }
        texture = null
    }

    private fun startDecoder() {
        releaseCodec()
        val target = surface ?: return
        try {
            val decoder = MediaCodec.createDecoderByType(MIME)
            val format = MediaFormat.createVideoFormat(MIME, DISPLAY_WIDTH, DISPLAY_HEIGHT)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT)
            format.setInteger("low-latency", 1)
            decoder.configure(format, target, null, 0)
            decoder.start()
            codec = decoder
            running = true
            ready = true
            received = 0
            rendered = 0
            dropped = 0
            val token = generation
            worker = Thread({ decode(decoder, token) }, "hicar-video-decoder").also {
                it.isDaemon = true
                it.start()
            }
            onLog("HiSight: H.264 decoder ready (${DISPLAY_WIDTH}×$DISPLAY_HEIGHT)")
        } catch (failure: IOException) {
            onDecoderFailure("start", failure)
        } catch (failure: RuntimeException) {
            onDecoderFailure("start", failure)
        }
    }

    private fun decode(decoder: MediaCodec, token: Int) {
        val info = MediaCodec.BufferInfo()
        var pending: Frame? = null
        try {
            while (running && generation == token) {
                if (pending == null) pending = frames.poll(5, TimeUnit.MILLISECONDS)
                val frame = pending
                if (frame != null) {
                    val input = decoder.dequeueInputBuffer(5_000)
                    if (input >= 0) {
                        val buffer = decoder.getInputBuffer(input)
                            ?: throw IOException("video input buffer")
                        if (buffer.capacity() < frame.data.size) throw IOException("video input capacity")
                        buffer.clear()
                        buffer.put(frame.data)
                        val flags = if (onlyConfig(frame.data)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                        decoder.queueInputBuffer(input, 0, frame.data.size, frame.ptsUs, flags)
                        frame.data.fill(0)
                        pending = null
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 5_000)
                if (output >= 0) {
                    val configuration = info.flags and
                        (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (!configuration) decoded = true
                    val show = visible && !configuration
                    decoder.releaseOutputBuffer(output, show)
                    if (show) rendered++
                } else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = decoder.outputFormat
                    onLog(
                        "HiSight: decoder output " + actual.getInteger(MediaFormat.KEY_WIDTH) + "×" +
                            actual.getInteger(MediaFormat.KEY_HEIGHT),
                    )
                }
            }
        } catch (failure: IOException) {
            if (generation == token) onDecoderFailure("decode", failure)
        } catch (failure: RuntimeException) {
            if (generation == token) onDecoderFailure("decode", failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pending?.let { it.data.fill(0) }
        }
    }

    private fun onDecoderFailure(stage: String, failure: Throwable) {
        ready = false
        running = false
        onLog("HiSight: video $stage failed (${failure.javaClass.simpleName})")
    }

    @Synchronized
    private fun closeDecoder() {
        releaseCodec()
        runCatching { surface?.release() }
        if (surface != null) surface = null
        runCatching { texture?.release() }
        texture = null
    }

    private fun releaseCodec() {
        generation++
        ready = false
        running = false
        decoded = false
        worker?.let { thread ->
            runCatching { thread.join(300) }
            worker = null
        }
        codec?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            codec = null
        }
        while (true) {
            val frame = frames.poll() ?: break
            frame.data.fill(0)
        }
    }

    private companion object {
        const val MIME = "video/avc"
        const val DISPLAY_WIDTH = 800
        const val DISPLAY_HEIGHT = 480
        const val MAX_ACCESS_UNIT = 2 * 1024 * 1024
        const val QUEUE_FRAMES = 8

        /** True when the access unit carries only SPS/PPS and should not be presented. */
        fun onlyConfig(data: ByteArray): Boolean {
            var config = false
            var index = 0
            while (index + 4 < data.size) {
                if (data[index].toInt() == 0 && data[index + 1].toInt() == 0) {
                    val long = data[index + 2].toInt() == 0
                    if (data[index + 2].toInt() == 1 || (long && data[index + 3].toInt() == 1)) {
                        val at = index + if (long) 4 else 3
                        if (at >= data.size) break
                        val type = data[at].toInt() and 31
                        if (type in 1..5) return false
                        if (type == 7 || type == 8) config = true
                    }
                }
                index++
            }
            return config
        }
    }
}
