package com.shilapi.xcertplay.hicar

import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One UIBC event: a touch action (0 down, 1 up, 2 move) or a key press/release. */
internal class HiCarInputEvent private constructor(
    val action: Int,
    val x: Int,
    val y: Int,
    val key: Int,
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_KEY_DOWN = 3
        const val ACTION_KEY_UP = 4

        fun touch(action: Int, x: Int, y: Int): HiCarInputEvent {
            require(action in ACTION_DOWN..ACTION_MOVE) { "touch action" }
            return HiCarInputEvent(action, x, y, 0)
        }

        fun key(key: Int, down: Boolean): HiCarInputEvent =
            HiCarInputEvent(if (down) ACTION_KEY_DOWN else ACTION_KEY_UP, 0, 0, key)
    }
}

/**
 * Encodes UIBC events the way the phone expects them on the input socket.
 *
 * `0xFF`-prefixed marker, a big-endian body, then the whole thing AES-CTR encrypted and length
 * prefixed. Touch bodies are 14 bytes, key bodies 12. Coordinates are in the picture space the
 * decoder reports — sending window pixels would put the finger somewhere else.
 *
 * Ported from `UiInput` (Aki894/CarLink `hisight_input.py`, MIT).
 */
internal class HiCarUiInputEncoder(sessionKey: ByteArray) {
    private val cipher = HiCarRecordCipher(sessionKey, MAX_RECORD_BYTES)

    fun encode(event: HiCarInputEvent, elapsedMillis: Long): ByteArray {
        val body: ByteArray = when (event.action) {
            HiCarInputEvent.ACTION_DOWN, HiCarInputEvent.ACTION_UP, HiCarInputEvent.ACTION_MOVE -> {
                // Range is the router's business: it knows the decoded size, which is no longer a
                // constant. Re-checking against 800×480 here would reject every coordinate of a
                // 1080p picture.
                if (event.x < 0 || event.y < 0 || elapsedMillis < 0) {
                    throw HiCarWireException("touch coordinates")
                }
                ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
                    .put(event.action.toByte())
                    .putShort(11)
                    .put(1)
                    .put(0)
                    .putShort(event.x.toShort())
                    .putShort(event.y.toShort())
                    .putInt((elapsedMillis and 0xFFFF_FFFFL).toInt())
                    .put(0)
                    .array()
            }

            HiCarInputEvent.ACTION_KEY_DOWN, HiCarInputEvent.ACTION_KEY_UP -> {
                if (event.key != KEY_HOME && event.key != KEY_BACK) throw HiCarWireException("unsupported input key")
                ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                    .put(event.action.toByte())
                    .putShort(9)
                    .put(0)
                    .putShort(event.key.toShort())
                    .putShort(0)
                    .putInt(0)
                    .array()
            }

            else -> throw HiCarWireException("input action")
        }
        val plain = join(MARKER, body)
        val encrypted = cipher.encrypt(plain)
        plain.fill(0)
        val length = encrypted.size + 4
        return join(
            byteArrayOf(0, 0, ((length ushr 8) and 0xFF).toByte(), (length and 0xFF).toByte()),
            encrypted,
        )
    }

    fun close() = cipher.clear()

    private companion object {
        const val MAX_RECORD_BYTES = 1_500
        const val KEY_HOME = 3
        const val KEY_BACK = 4
        val MARKER = byteArrayOf(-1, -1, -1, -1, -1, -1)
    }
}

/**
 * Holds the one input channel that may receive touches.
 *
 * A new session replaces the old sender, and nothing is sent when no channel is attached. Down/up
 * pairing is enforced here rather than in the UI: a move without a down, or a second down, would
 * leave the phone believing a finger is still on the screen after the driver lifted it.
 */
internal class HiCarInputRouter {
    interface Sender {
        fun send(event: HiCarInputEvent): Boolean
    }

    private var sender: Sender? = null
    private var pressed = false
    private var x = 0
    private var y = 0

    /**
     * The coordinate space touches are expressed in.
     *
     * Not a constant: a phone may encode any shape it likes, and the decoder's output format is the
     * only authoritative answer. Coordinates outside it are refused, so a wrong value here means
     * touches land in the wrong place or are dropped - which is why it follows the picture rather
     * than the offer.
     */
    @Volatile
    var displayWidth: Int = HiCarDisplaySize.BASE.width
        private set

    @Volatile
    var displayHeight: Int = HiCarDisplaySize.BASE.height
        private set

    @Synchronized
    fun setDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        displayWidth = width
        displayHeight = height
    }

    @Synchronized
    fun attach(next: Sender) {
        sender = next
        pressed = false
    }

    @Synchronized
    fun detach(old: Sender) {
        if (sender === old) {
            sender = null
            pressed = false
        }
    }

    @Synchronized
    fun ready(): Boolean = sender != null

    @Synchronized
    fun touch(action: Int, nx: Int, ny: Int): Boolean {
        val target = sender ?: return false
        if (nx !in 0 until displayWidth || ny !in 0 until displayHeight) return false
        if (action !in HiCarInputEvent.ACTION_DOWN..HiCarInputEvent.ACTION_MOVE) return false
        if (action == HiCarInputEvent.ACTION_DOWN && pressed) return false
        if (action != HiCarInputEvent.ACTION_DOWN && !pressed) return false
        if (!target.send(HiCarInputEvent.touch(action, nx, ny))) return false
        x = nx
        y = ny
        pressed = action != HiCarInputEvent.ACTION_UP
        return true
    }

    /** Maps view pixels onto [displayWidth]×[displayHeight]; null when outside and not clamped. */
    fun map(x: Float, y: Float, width: Int, height: Int, clamp: Boolean): IntArray? =
        map(x, y, width, height, clamp, displayWidth, displayHeight)

    /** Releases a finger the phone still thinks is down; called when a session ends. */
    @Synchronized
    fun cancel() {
        if (pressed) sender?.send(HiCarInputEvent.touch(HiCarInputEvent.ACTION_UP, x, y))
        pressed = false
    }

    @Synchronized
    fun key(key: Int): Boolean {
        val target = sender ?: return false
        if (key != KEY_HOME && key != KEY_BACK) return false
        cancel()
        if (!target.send(HiCarInputEvent.key(key, true))) return false
        return target.send(HiCarInputEvent.key(key, false))
    }

    companion object {
        /** Defaults for a session whose picture size is not known yet; see [displayWidth]. */
        const val DISPLAY_WIDTH = 800
        const val DISPLAY_HEIGHT = 480
        const val KEY_HOME = 3
        const val KEY_BACK = 4

        /** Maps view pixels onto [displayWidth]×[displayHeight]; null when outside and not clamped. */
        fun map(
            x: Float,
            y: Float,
            width: Int,
            height: Int,
            clamp: Boolean,
            displayWidth: Int = DISPLAY_WIDTH,
            displayHeight: Int = DISPLAY_HEIGHT,
        ): IntArray? {
            if (width < 1 || height < 1 || x.isNaN() || x.isInfinite() || y.isNaN() || y.isInfinite()) return null
            if (!clamp && (x < 0f || x >= width || y < 0f || y >= height)) return null
            val px = (x * displayWidth / width).toInt().coerceIn(0, displayWidth - 1)
            val py = (y * displayHeight / height).toInt().coerceIn(0, displayHeight - 1)
            return intArrayOf(px, py)
        }
    }
}

/**
 * Writes encoded UIBC events to the phone's input socket, off the UI thread.
 *
 * The queue is small and bounded: moves may be dropped under load, but a down or an up is never
 * silently lost — a lost up leaves the phone with a stuck finger, which is worse than a lost move.
 */
internal class HiCarInputChannel(
    private val socket: Socket,
    sessionKey: ByteArray,
    private val router: HiCarInputRouter,
    private val active: () -> Boolean,
    private val onLog: (String) -> Unit,
) : HiCarInputRouter.Sender, AutoCloseable {
    private val encoder = HiCarUiInputEncoder(sessionKey)
    private val pending = ArrayBlockingQueue<HiCarInputEvent>(64)
    private val startedAt = System.nanoTime()

    @Volatile private var closed = false

    fun start() {
        router.attach(this)
        Thread({ write() }, "hicar-uibc").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun send(event: HiCarInputEvent): Boolean {
        if (closed || !active()) return false
        if (pending.offer(event)) return true
        if (event.action == HiCarInputEvent.ACTION_MOVE) return true
        close()
        onLog("UIBC: queue full, input channel closed so no finger stays pressed")
        return false
    }

    private fun write() {
        var count = 0L
        try {
            val out: OutputStream = socket.getOutputStream()
            while (!closed && active()) {
                val event = pending.poll(250, TimeUnit.MILLISECONDS) ?: continue
                val packet = encoder.encode(event, (System.nanoTime() - startedAt) / 1_000_000L)
                out.write(packet)
                out.flush()
                packet.fill(0)
                count++
                if (count <= 3 || count % 60 == 0L) onLog("UIBC: $count encrypted input event(s) sent")
            }
        } catch (failure: IOException) {
            if (!closed && active()) onLog("UIBC: sender stopped (${failure.javaClass.simpleName})")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        router.detach(this)
        pending.clear()
        runCatching { socket.close() }
        encoder.close()
    }
}
