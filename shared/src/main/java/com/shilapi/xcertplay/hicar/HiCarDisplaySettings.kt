package com.shilapi.xcertplay.hicar

import android.content.Context

/**
 * Which system bars the receiver keeps on screen.
 *
 * The three values the settings page offers are not cosmetic. A head unit that only ever shows this
 * receiver wants every pixel and no bar; one that shares the panel with the car's own strip (air
 * conditioning, volume) must hand the bars their space back, or the picture is drawn underneath them
 * and the bottom row of the phone's UI cannot be touched at all.
 *
 * The receiver never letterboxes differently per mode: the picture is fitted into whatever rectangle
 * the bars leave ([HiCarVideoFit]), so switching modes changes the size of the picture, never its
 * shape.
 */
enum class HiCarWindowMode(
    /** The value written to preferences; never the enum name, so a rename cannot lose a setting. */
    val stored: String,
    val title: String,
    val detail: String,
) {
    FULLSCREEN(
        stored = "fullscreen",
        title = "全屏显示",
        detail = "隐藏状态栏和底部导航栏，画面占满整块屏幕",
    ),
    STATUS_BAR(
        stored = "status_bar",
        title = "显示状态栏",
        detail = "保留顶部状态栏，底部导航栏隐藏",
    ),
    STATUS_BAR_AND_NAVIGATION(
        stored = "status_bar_navigation",
        title = "显示状态栏和底部导航栏",
        detail = "两条系统栏都保留，画面区域相应缩小",
    ),
    ;

    val showsStatusBar: Boolean get() = this != FULLSCREEN
    val showsNavigationBar: Boolean get() = this == STATUS_BAR_AND_NAVIGATION

    companion object {
        /** Unknown values fall back to [FULLSCREEN]: it is the mode that cannot hide anything. */
        fun of(stored: String?): HiCarWindowMode = values().firstOrNull { it.stored == stored } ?: FULLSCREEN
    }
}

/**
 * Persists [HiCarWindowMode] for the receiver window.
 *
 * The mode is read in `onCreate` and again whenever the settings page changes it, so a mode chosen
 * during a connection is applied to that same window instead of waiting for the next launch.
 */
object HiCarDisplaySettings {
    const val PREFERENCES = "hicar-display"
    private const val KEY_MODE = "window_mode"

    fun load(context: Context): HiCarWindowMode =
        HiCarWindowMode.of(preferences(context).getString(KEY_MODE, null))

    fun save(context: Context, mode: HiCarWindowMode) {
        preferences(context).edit().putString(KEY_MODE, mode.stored).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
