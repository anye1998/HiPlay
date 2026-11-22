package com.shilapi.xcertplay.hicar

/**
 * What the speed-volume feature is doing right now, for the settings page to show.
 *
 * The setting itself only says which sources are *allowed*; it cannot say which one is answering.
 * On a head unit that is the difference between "the car bus works, the tunnel is covered" and
 * "there is no car bus on this ROM, only GPS, and the tunnel will fade" — and a driver deciding
 * whether to trust the feature needs to see which case they are in. So the monitor publishes this
 * once a second, and the page prints it verbatim.
 *
 * [source] is the source of the fix in force this tick, not the configured preference: a session
 * configured for `auto` shows `VEHICLE`, `LOCATION`, `HELD` or `NONE` depending on what arrived.
 */
data class HiCarSpeedStatus(
    /** False before the receiver starts, and again after it stops. */
    val running: Boolean = false,
    val source: Source = Source.OFF,
    /** The speed the curve is using, after holding a stale fix. */
    val speedKmh: Double = 0.0,
    /** The gain that speed produced, in dB. */
    val gainDb: Double = 0.0,
    /** True once the car bus has delivered a wheel-speed reading in this session. */
    val vehicleAvailable: Boolean = false,
    /** Why the car bus is not being used, when the ROM or the permission refused it. */
    val vehicleError: String? = null,
    /** True once a location provider was started for this session. */
    val locationAvailable: Boolean = false,
    /** Why location is not being used, when no provider could be started. */
    val locationError: String? = null,
) {
    enum class Source {
        /** The monitor is not running: the receiver is stopped, or the feature is off. */
        OFF,

        /** Wheel speed read from the car's own bus. */
        VEHICLE,

        /** Position-derived speed from the location provider. */
        LOCATION,

        /** No fresh fix; the last value is held through a tunnel and will then fade. */
        HELD,

        /** Running, but neither source has produced a usable speed. */
        NONE,
    }

    val sourceLabel: String
        get() = when (source) {
            Source.OFF -> "未启用"
            Source.VEHICLE -> "车辆总线（轮速）"
            Source.LOCATION -> "定位（GPS/北斗）"
            Source.HELD -> "保持最后速度（信号中断）"
            Source.NONE -> "暂无可用速度"
        }

    /** One line a driver can read without interpreting the fields. */
    fun describe(): String = when {
        !running -> "接收端未运行，或车速增益没有开启；连接手机并打开开关后，这里会显示实时来源。"
        source == Source.NONE -> "已开启，但还没有速度读数：" + listOfNotNull(vehicleError, locationError)
            .ifEmpty { listOf("两个来源都还没有数据") }
            .joinToString("；")
        else -> "%.0f km/h，来源 %s，当前增益 %+.1f dB".format(speedKmh, sourceLabel, gainDb)
    }
}
