package com.shilapi.xcertplay.hicar

/**
 * One key to send to the phone: an Android key code and whether it went down.
 *
 * The wire uses the same two numbers, but keeping them apart from the encoder means the forwarding
 * rules below can be tested without a socket, a phone or Android.
 */
data class HiCarMediaKey(val code: Int, val down: Boolean)

/**
 * Car media keys (steering wheel, media buttons) → the keys the phone should see.
 *
 * Ported from `GGBondLab/OpenHC` (`platform/.../media/MediaKeyForwarder.kt`, GPL-3.0-only) and kept
 * as pure logic for the same reason: the rules are where the bugs are, and none of them need a car.
 *
 * Two entries feed it and both deliver the same `KeyEvent` shape:
 *
 * - the receiver's own window ([Source.WINDOW]) — while the picture is on screen;
 * - a media-button session ([Source.SESSION]) — while the app is in the background but still owns
 *   media focus, which is the only way a steering-wheel key reaches an app that is not visible.
 *
 * Rules, each of which came from a way a naive forwarder misbehaves:
 *
 * - only the media keys in [KEY_CODES] are touched; everything else is left to the head unit;
 * - nothing is forwarded while no session can send ([ready]), so the car's own radio keeps working;
 * - auto-repeat presses are swallowed, not forwarded: a held key would otherwise become dozens of
 *   "next track" presses;
 * - a second down without an up first delivers the missing up, so the phone never holds a key down;
 * - an up is only forwarded if its down was; an orphan up goes back to the system, which is what
 *   keeps a key the system handled from being left half-pressed;
 * - the same event arriving on both entries (the window *and* the session get it on some ROMs) is
 *   forwarded once, recognised by key code, action and event time;
 * - a media-button command (`onCommand`) has no up and down, so it is delivered as a pair — unless
 *   that key is being held, in which case the key events already carry it.
 *
 * Not thread safe: call from one thread (the main thread, as the activity and the media session do).
 */
class HiCarMediaKeyForwarder {
    enum class Source(val label: String) {
        WINDOW("前台窗口"),
        SESSION("媒体会话"),
    }

    /** A media-button session command and the Android key code it stands for. */
    enum class Command(val code: Int) {
        PLAY(KEYCODE_MEDIA_PLAY),
        PAUSE(KEYCODE_MEDIA_PAUSE),
        PLAY_PAUSE(KEYCODE_MEDIA_PLAY_PAUSE),
        NEXT(KEYCODE_MEDIA_NEXT),
        PREVIOUS(KEYCODE_MEDIA_PREVIOUS),
        STOP(KEYCODE_MEDIA_STOP),
    }

    /** [handled] true means the caller must consume the event; [keys] go to the phone in order. */
    class Result(val handled: Boolean, val keys: List<HiCarMediaKey>, val note: String)

    /** Whether a session can send keys right now. Turning it off forgets any key still held. */
    var ready: Boolean = false
        set(value) {
            field = value
            if (!value) {
                pressed.clear()
                recent.clear()
            }
        }

    private val pressed = LinkedHashSet<Int>()

    /** The last few events, so the same one arriving on both entries is forwarded once. */
    private val recent = ArrayDeque<Triple<Int, Boolean, Long>>()

    fun onKey(code: Int, down: Boolean, repeatCount: Int, eventTime: Long): Result {
        if (code !in KEY_CODES) return Result(false, emptyList(), "not a media key")
        if (!ready) return Result(false, emptyList(), "no key-capable connection; the key stays with the car")
        val id = Triple(code, down, eventTime)
        if (id in recent) return Result(true, emptyList(), "already forwarded from the other entry")
        recent.addLast(id)
        while (recent.size > RECENT_EVENTS) recent.removeFirst()
        if (down) {
            if (repeatCount > 0) return Result(true, emptyList(), "auto-repeat, swallowed")
            val keys = ArrayList<HiCarMediaKey>(2)
            if (code in pressed) keys += HiCarMediaKey(code, false)
            keys += HiCarMediaKey(code, true)
            pressed += code
            return Result(true, keys, if (keys.size == 2) "previous press never lifted; sending its up first" else "forwarded")
        }
        if (!pressed.remove(code)) return Result(false, emptyList(), "its down was not forwarded; the up stays with the system")
        return Result(true, listOf(HiCarMediaKey(code, false)), "forwarded")
    }

    /** A media-button command: delivered as a press, since the session reports no down and up. */
    fun onCommand(command: Command): Result {
        if (!ready) return Result(false, emptyList(), "no key-capable connection")
        val code = command.code
        if (code in pressed) return Result(true, emptyList(), "the key is held; the command is already carried by its events")
        return Result(
            true,
            listOf(HiCarMediaKey(code, true), HiCarMediaKey(code, false)),
            "synthesized as a press",
        )
    }

    /** The keys the phone may still believe are down, for releasing a session that ends mid-press. */
    fun pressedKeys(): List<Int> = pressed.toList()

    companion object {
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_STOP = 86
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_MEDIA_REWIND = 89
        const val KEYCODE_MEDIA_FAST_FORWARD = 90
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127

        /**
         * The keys this forwarder handles.
         *
         * These are exactly the media keys the phone accepts without being asked for
         * `source_device_type 7` (see [HiCarUiInputEncoder.SUPPORTED_KEYS]): forwarding volume or
         * d-pad keys would need that negotiation, and quietly dropping them is what makes a
         * steering wheel feel broken.
         */
        val KEY_CODES: Set<Int> = setOf(
            KEYCODE_MEDIA_PLAY_PAUSE,
            KEYCODE_MEDIA_STOP,
            KEYCODE_MEDIA_NEXT,
            KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_MEDIA_REWIND,
            KEYCODE_MEDIA_FAST_FORWARD,
            KEYCODE_MEDIA_PLAY,
            KEYCODE_MEDIA_PAUSE,
        )

        private const val RECENT_EVENTS = 8

        /** Name used in the log, e.g. "next(87)". */
        fun label(code: Int): String {
            val name = when (code) {
                KEYCODE_MEDIA_PLAY_PAUSE -> "play/pause"
                KEYCODE_MEDIA_STOP -> "stop"
                KEYCODE_MEDIA_NEXT -> "next"
                KEYCODE_MEDIA_PREVIOUS -> "previous"
                KEYCODE_MEDIA_REWIND -> "rewind"
                KEYCODE_MEDIA_FAST_FORWARD -> "fast-forward"
                KEYCODE_MEDIA_PLAY -> "play"
                KEYCODE_MEDIA_PAUSE -> "pause"
                else -> "key"
            }
            return "$name($code)"
        }
    }
}
