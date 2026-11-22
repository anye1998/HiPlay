package com.shilapi.xcertplay.hicar

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How sharp the picture is, in the terms a driver can choose between.
 *
 * The three steps are not "resolution presets" alone: a phone is free to ignore the geometry we
 * offer, so the only lever that is always honoured is the bitrate. They are therefore paired —
 * a lower resolution comes with a lower bitrate, so choosing 流畅 really does reduce what the link
 * has to carry, and 清晰 spends more on detail at the same size.
 */
enum class HiCarVideoQuality(
    /** The value written to preferences; never the enum name, so a rename cannot lose a setting. */
    val stored: String,
    val title: String,
    val detail: String,
    /** Ceiling for the offered picture; the screen can still make it smaller. */
    val maxWidth: Int,
    val maxHeight: Int,
    /** Bitrate at 30 fps, in bits per second. */
    val baseBitrate: Int,
) {
    SMOOTH(
        stored = "smooth",
        title = "流畅",
        detail = "最多 720p，码率低：老车机或热点信号弱时更稳",
        maxWidth = 1280,
        maxHeight = 720,
        baseBitrate = 1_200_000,
    ),
    BALANCED(
        stored = "balanced",
        title = "标准",
        detail = "按屏幕能显示的最大档，最高 1080p",
        maxWidth = HiCarDisplaySize.FULL_HD_WIDTH,
        maxHeight = HiCarDisplaySize.FULL_HD_HEIGHT,
        baseBitrate = 2_000_000,
    ),
    SHARP(
        stored = "sharp",
        title = "清晰",
        detail = "同样分辨率下提高码率，文字和小图标更锐利",
        maxWidth = HiCarDisplaySize.FULL_HD_WIDTH,
        maxHeight = HiCarDisplaySize.FULL_HD_HEIGHT,
        baseBitrate = 3_200_000,
    ),
    ;

    companion object {
        fun of(stored: String?): HiCarVideoQuality =
            values().firstOrNull { it.stored == stored } ?: BALANCED
    }
}

/** Whether the phone is asked to encode at a fixed rate or at whatever the screen refreshes. */
enum class HiCarFrameRateMode(val stored: String, val title: String, val detail: String) {
    FOLLOW_DISPLAY(
        stored = "display",
        title = "跟随屏幕刷新率",
        detail = "60 Hz 屏幕要 60 帧，90/120 Hz 依次提高；帧率越高越顺，也更耗电",
    ),
    FPS_30(
        stored = "30",
        title = "固定 30 帧",
        detail = "最省带宽，滚动和动画会顿一点",
    ),
    FPS_60(
        stored = "60",
        title = "固定 60 帧",
        detail = "屏幕不到 60 Hz 时也用 60 帧，画面更顺",
    ),
    ;

    companion object {
        fun of(stored: String?): HiCarFrameRateMode =
            values().firstOrNull { it.stored == stored } ?: FOLLOW_DISPLAY
    }
}

/**
 * The `his_video_formats` numbers for one session.
 *
 * Kept as a value so the whole negotiation can be asserted in a unit test; the wire class only
 * formats it.
 */
data class HiCarVideoOffer(
    val width: Int,
    val height: Int,
    val fps: Int,
    val gop: Int,
    val bitrate: Int,
    val minBitrate: Int,
    val maxBitrate: Int,
) {
    init {
        require(width > 0 && height > 0) { "offer size" }
        require(fps in 1..240) { "offer fps" }
        require(bitrate > 0) { "offer bitrate" }
    }

    /** The line the phone reads in `GET_PARAMETER`. */
    fun formats(density: Int, screenWidth: Int, screenHeight: Int): String =
        "codecs 1;fps $fps;gop $gop;bitrate $bitrate;" +
            "vbr-min $minBitrate;vbr-max $maxBitrate;" +
            "dpi $density;scr-w $screenWidth;scr-h $screenHeight;" +
            "width $width;height $height"
}

/**
 * The video half of the settings page: sharpness and frame rate.
 *
 * Both only take effect on the next connection, and the page says so: `his_video_formats` is
 * answered once, during the RTSP handshake, and a running encoder cannot be renegotiated on this
 * protocol without dropping the stream.
 */
data class HiCarVideoSettings(
    val quality: HiCarVideoQuality = HiCarVideoQuality.BALANCED,
    val frameRate: HiCarFrameRateMode = HiCarFrameRateMode.FOLLOW_DISPLAY,
) {
    /**
     * The picture geometry to offer, given the area it will be shown in.
     *
     * The area is the panel minus whichever system bars the display mode leaves visible, so it is
     * not always 16:9. Offering its own shape is what lets the phone encode a picture that fills the
     * window: a fixed 16:9 step in a 1920×1032 area is letterboxed back to 1834×1032, and those side
     * bars are exactly the waste this avoids. [quality] still caps the size, so a 720p screen or the
     * 流畅 step is never offered more than it should encode.
     */
    fun offerSize(screen: HiCarDisplaySize): HiCarDisplaySize = HiCarDisplaySize.fitTo(
        width = screen.width,
        height = screen.height,
        maxWidth = minOf(screen.width, quality.maxWidth),
        maxHeight = minOf(screen.height, quality.maxHeight),
    )

    /**
     * The frame rate to ask for.
     *
     * Following the display means quantising to the four rates phone encoders actually produce. A
     * panel that reports 59.94 Hz must not become "59", and one that reports an implausible value
     * (some HDMI bridges report 0) falls back to 60 rather than to 1.
     */
    fun fpsFor(refreshHz: Float): Int = when (frameRate) {
        HiCarFrameRateMode.FPS_30 -> 30
        HiCarFrameRateMode.FPS_60 -> 60
        HiCarFrameRateMode.FOLLOW_DISPLAY -> {
            val hz = if (refreshHz.isFinite() && refreshHz >= 24f) refreshHz else 60f
            when {
                hz >= 115f -> 120
                hz >= 85f -> 90
                hz >= 50f -> 60
                else -> 30
            }
        }
    }

    /**
     * The whole offer.
     *
     * GOP stays one second, which is what the fixed 30/30 pair was: a keyframe every second is what
     * makes a dropped link recover without dragging a frozen picture for minutes.
     */
    fun offer(screen: HiCarDisplaySize, refreshHz: Float): HiCarVideoOffer {
        val shown = offerSize(screen)
        val fps = fpsFor(refreshHz)
        // 30 fps is the reference the quality's bitrate is quoted at; higher frame rates need
        // proportionally more, but not linearly — the extra frames repeat far more than they move.
        val rate = quality.baseBitrate + ((quality.baseBitrate * (fps - 30)) / 60.0).roundToInt()
        return HiCarVideoOffer(
            width = shown.width,
            height = shown.height,
            fps = fps,
            gop = fps,
            bitrate = rate,
            minBitrate = rate / 2,
            maxBitrate = (rate * 3) / 2,
        )
    }

    companion object {
        /**
         * The panel rate, with the implausible values some HDMI bridges report pushed to 60.
         *
         * Takes the number rather than a `Display` so the rule is testable on the JVM: the units that
         * report 0 Hz, 59.94 Hz and nothing at all are exactly the ones worth asserting.
         */
        fun refreshOf(refreshHz: Float?): Float {
            val hz = refreshHz ?: 60f
            return if (!hz.isFinite() || abs(hz) < 24f) 60f else hz
        }

        fun refreshOf(display: android.view.Display?): Float = refreshOf(display?.refreshRate)
    }
}

/** Persists [HiCarVideoSettings]; the same pattern as the display and key settings. */
object HiCarVideoSettingsStore {
    const val PREFERENCES = "hicar-video"
    private const val KEY_QUALITY = "quality"
    private const val KEY_FRAME_RATE = "frame_rate"

    fun load(context: Context): HiCarVideoSettings {
        val preferences = preferences(context)
        return HiCarVideoSettings(
            quality = HiCarVideoQuality.of(preferences.getString(KEY_QUALITY, null)),
            frameRate = HiCarFrameRateMode.of(preferences.getString(KEY_FRAME_RATE, null)),
        )
    }

    fun save(context: Context, settings: HiCarVideoSettings) {
        preferences(context).edit()
            .putString(KEY_QUALITY, settings.quality.stored)
            .putString(KEY_FRAME_RATE, settings.frameRate.stored)
            .apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
