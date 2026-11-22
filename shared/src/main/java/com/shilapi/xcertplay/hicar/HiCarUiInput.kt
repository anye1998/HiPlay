package com.shilapi.xcertplay.hicar

import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One contact in a touch snapshot.
 *
 * [id] is the pointer id Android assigned, kept as-is because the phone compares snapshots by it: a
 * number that disappears is read as "this finger lifted", so renumbering would turn a two-finger
 * pinch into two separate taps on whatever the phone had under them.
 */
class HiCarTouchPoint(val id: Int, val x: Int, val y: Int) {
    override fun toString(): String = "($id:$x,$y)"
}

/**
 * One UIBC event: a touch snapshot (0 down, 1 up, 2 move) or a key press/release.
 *
 * A touch carries **all** contacts that are down, not just the one that moved. That is the whole
 * difference between one finger and ten: the phone treats each packet as the current set, works out
 * which ids appeared or vanished, and turns the rest into moves.
 */
internal class HiCarInputEvent private constructor(
    val action: Int,
    val points: List<HiCarTouchPoint>,
    val key: Int,
) {
    /** The first contact, for the callers that only ever send one. */
    val x: Int get() = points.firstOrNull()?.x ?: 0
    val y: Int get() = points.firstOrNull()?.y ?: 0

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_KEY_DOWN = 3
        const val ACTION_KEY_UP = 4

        /** The phone decodes at most ten contacts; a longer packet is one packet it will refuse. */
        const val MAX_POINTS = 10

        fun touch(action: Int, x: Int, y: Int): HiCarInputEvent =
            touch(action, listOf(HiCarTouchPoint(0, x, y)))

        fun touch(action: Int, points: List<HiCarTouchPoint>): HiCarInputEvent {
            require(action in ACTION_DOWN..ACTION_MOVE) { "touch action" }
            require(points.isNotEmpty() && points.size <= MAX_POINTS) { "touch point count" }
            // Two contacts with the same id would be read as one finger that jumped; the phone has no
            // way to say which of the two it should keep.
            require(points.map { it.id }.toSet().size == points.size) { "touch pointer ids" }
            return HiCarInputEvent(action, points.sortedBy { it.id }, 0)
        }

        fun key(key: Int, down: Boolean): HiCarInputEvent =
            HiCarInputEvent(if (down) ACTION_KEY_DOWN else ACTION_KEY_UP, emptyList(), key)
    }
}

/**
 * Encodes UIBC events the way the phone expects them on the input socket.
 *
 * `0xFF`-prefixed marker, a big-endian body, then the whole thing AES-CTR encrypted and length
 * prefixed. A touch body is `4 + 5N + padding` bytes for N contacts (10 at most); a key body is 12.
 * Coordinates are in the picture space the decoder reports — sending window pixels would put the
 * finger somewhere else.
 *
 * The touch form is the one GGBondLab/OpenHC runs on a real car (`core/hisight`,
 * `UibcEncoder.touch`), which is also the only form that can express a pinch: one packet per
 * snapshot of every finger down.
 */
internal class HiCarUiInputEncoder(sessionKey: ByteArray) {
    private val cipher = HiCarRecordCipher(sessionKey, MAX_RECORD_BYTES)

    fun encode(event: HiCarInputEvent, @Suppress("UNUSED_PARAMETER") elapsedMillis: Long): ByteArray {
        val body: ByteArray = when (event.action) {
            HiCarInputEvent.ACTION_DOWN, HiCarInputEvent.ACTION_UP, HiCarInputEvent.ACTION_MOVE -> {
                // Range is the router's business: it knows the decoded size, which is no longer a
                // constant. Re-checking against 800×480 here would reject every coordinate of a
                // 1080p picture.
                val points = event.points
                if (points.isEmpty() || points.size > HiCarInputEvent.MAX_POINTS) {
                    throw HiCarWireException("touch point count")
                }
                if (points.any { it.id !in 0..31 || it.x !in 0..0xFFFF || it.y !in 0..0xFFFF }) {
                    throw HiCarWireException("touch coordinates")
                }
                // `action | BE16 (1 + 5N + pad) | N | N×(id, BE16 x, BE16 y) | pad`.
                //
                // The 14-byte single-touch body this replaced carried a millisecond timestamp after
                // the pair. The reference implementation that runs on a real car sends no timestamp
                // and can carry ten contacts in the same packet, so the snapshot form wins: a pinch
                // is impossible in the other body, and its length field means "1 + 5N", not
                // "1 + 5N + 5".
                val count = points.size
                val padding = (4 + 5 * count) % 2
                val declared = 1 + 5 * count + padding
                val body = ByteArray(4 + 5 * count + padding)
                body[0] = event.action.toByte()
                body[1] = ((declared ushr 8) and 0xFF).toByte()
                body[2] = (declared and 0xFF).toByte()
                body[3] = count.toByte()
                var at = 4
                for (point in points) {
                    body[at] = point.id.toByte()
                    body[at + 1] = ((point.x ushr 8) and 0xFF).toByte()
                    body[at + 2] = (point.x and 0xFF).toByte()
                    body[at + 3] = ((point.y ushr 8) and 0xFF).toByte()
                    body[at + 4] = (point.y and 0xFF).toByte()
                    at += 5
                }
                body
            }

            HiCarInputEvent.ACTION_KEY_DOWN, HiCarInputEvent.ACTION_KEY_UP -> {
                if (event.key !in SUPPORTED_KEYS) throw HiCarWireException("unsupported input key")
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

    companion object {
        private const val MAX_RECORD_BYTES = 1_500
        const val KEY_HOME = 3
        const val KEY_BACK = 4

        /**
         * The key codes this encoder will put on the wire.
         *
         * Home and back were the only two for a long time, because they were the only ones the driver
         * could ask for. The media keys are added for the steering wheel: they are exactly the ones a
         * phone accepts *without* being told `source_device_type 7`, so they need no extra
         * negotiation, and their body bytes are identical either way (modifier bits zero).
         */
        val SUPPORTED_KEYS: Set<Int> = setOf(KEY_HOME, KEY_BACK) + HiCarMediaKeyForwarder.KEY_CODES

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

    /**
     * The contacts the phone currently believes are down, by pointer id.
     *
     * A linked map so the "which one do I lift" answer is stable: [cancel] lifts the first id of the
     * last snapshot, which is the smallest, and the phone raises every other finger with it.
     */
    private val activePoints = LinkedHashMap<Int, HiCarTouchPoint>()
    private var lastSnapshot: List<HiCarTouchPoint> = emptyList()

    /** Media keys the phone believes are down; a session that ends must lift them. */
    private val pressedKeys = LinkedHashSet<Int>()

    /**
     * Called when the input channel appears or goes away.
     *
     * Key forwarding has to know this: a phone that selected key events still cannot receive one
     * until the socket exists, and the session that receives background buttons is only worth
     * creating once it can actually send.
     */
    @Volatile
    var onReadyChanged: ((Boolean) -> Unit)? = null

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
        activePoints.clear()
        lastSnapshot = emptyList()
        pressedKeys.clear()
        onReadyChanged?.invoke(true)
    }

    @Synchronized
    fun detach(old: Sender) {
        if (sender === old) {
            sender = null
            activePoints.clear()
            lastSnapshot = emptyList()
            pressedKeys.clear()
            onReadyChanged?.invoke(false)
        }
    }

    @Synchronized
    fun ready(): Boolean = sender != null

    /** One finger, for the buttons and probes that only ever touch a single point. */
    @Synchronized
    fun touch(action: Int, nx: Int, ny: Int): Boolean = touches(action, listOf(HiCarTouchPoint(0, nx, ny)))

    /**
     * A snapshot of every contact that is down.
     *
     * The three actions do not mean what they do in `MotionEvent`. The phone reads each packet as the
     * current set and diffs it against what it remembers, so:
     *
     * - [HiCarInputEvent.ACTION_DOWN] may only be sent when nothing is down — it starts a gesture;
     * - [HiCarInputEvent.ACTION_MOVE] carries the set as it is now, fingers may have been added or
     *   removed since the last snapshot (this is how a pinch works);
     * - [HiCarInputEvent.ACTION_UP] carries exactly one contact, ends the whole gesture, and the
     *   phone lifts the rest by itself.
     *
     * Getting that wrong is not cosmetic: a second DOWN leaves the phone with a gesture it will
     * never finish, and a multi-contact UP is treated as a move, so the picture stays "pressed".
     */
    @Synchronized
    fun touches(action: Int, points: List<HiCarTouchPoint>): Boolean {
        val target = sender ?: return false
        if (action !in HiCarInputEvent.ACTION_DOWN..HiCarInputEvent.ACTION_MOVE) return false
        if (points.isEmpty() || points.size > HiCarInputEvent.MAX_POINTS) return false
        if (points.map { it.id }.toSet().size != points.size) return false
        for (point in points) {
            if (point.id !in 0..31) return false
            if (point.x !in 0 until displayWidth || point.y !in 0 until displayHeight) return false
        }
        when (action) {
            HiCarInputEvent.ACTION_DOWN -> if (activePoints.isNotEmpty()) return false
            HiCarInputEvent.ACTION_MOVE -> if (activePoints.isEmpty()) return false
            HiCarInputEvent.ACTION_UP -> {
                if (activePoints.isEmpty() || points.size != 1) return false
                if (points[0].id !in activePoints) return false
            }
        }
        val snapshot = points.sortedBy { it.id }
        if (!target.send(HiCarInputEvent.touch(action, snapshot))) return false
        if (action == HiCarInputEvent.ACTION_UP) {
            activePoints.clear()
            lastSnapshot = emptyList()
        } else {
            activePoints.clear()
            for (point in snapshot) activePoints[point.id] = point
            lastSnapshot = snapshot
        }
        return true
    }

    /** Maps view pixels onto [displayWidth]×[displayHeight]; null when outside and not clamped. */
    fun map(x: Float, y: Float, width: Int, height: Int, clamp: Boolean): IntArray? =
        map(x, y, width, height, clamp, displayWidth, displayHeight)

    /** Releases a finger and any key the phone still thinks are down; called when a session ends. */
    @Synchronized
    fun cancel() {
        cancelTouch()
        for (code in pressedKeys.toList().asReversed()) {
            sender?.send(HiCarInputEvent.key(code, false))
        }
        pressedKeys.clear()
    }

    /**
     * Lifts whatever fingers the phone still believes are down, without touching the keys.
     *
     * A gesture that the window cancelled (a system swipe took over, the view was detached) has to
     * end, or every later DOWN is refused for the rest of the session. The media keys are a separate
     * state and a separate reason: cancelling a touch must not release a steering-wheel button the
     * driver is still holding.
     */
    @Synchronized
    fun cancelTouch(): Boolean {
        // One contact ends the whole gesture on the phone's side, so the anchor is any contact that
        // is still down; the first of the last snapshot is the smallest id, which every phone keeps.
        val anchor = lastSnapshot.firstOrNull() ?: return false
        activePoints.clear()
        lastSnapshot = emptyList()
        return sender?.send(HiCarInputEvent.touch(HiCarInputEvent.ACTION_UP, listOf(anchor))) == true
    }

    /** The two navigation keys, sent as one press: nothing on this side holds them down. */
    @Synchronized
    fun key(key: Int): Boolean {
        val target = sender ?: return false
        if (key != KEY_HOME && key != KEY_BACK) return false
        cancel()
        if (!target.send(HiCarInputEvent.key(key, true))) return false
        return target.send(HiCarInputEvent.key(key, false))
    }

    /**
     * One half of a media key press.
     *
     * Separate from [key] on purpose: a steering-wheel button is held as long as the driver holds it,
     * and the phone is what repeats it. Pairing is enforced here so a lost down cannot leave the phone
     * with a key stuck down — the failure that turns "next track" into "skip the whole playlist".
     */
    @Synchronized
    fun mediaKey(code: Int, down: Boolean): Boolean {
        val target = sender ?: return false
        if (code !in HiCarMediaKeyForwarder.KEY_CODES) return false
        if (down) {
            if (code in pressedKeys) return false
            if (!target.send(HiCarInputEvent.key(code, true))) return false
            pressedKeys += code
            return true
        }
        if (!pressedKeys.remove(code)) return false
        return target.send(HiCarInputEvent.key(code, false))
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
