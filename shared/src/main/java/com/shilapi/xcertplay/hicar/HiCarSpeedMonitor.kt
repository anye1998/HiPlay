package com.shilapi.xcertplay.hicar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.hud.BydClusterSpeedReader

/**
 * Turns a speed into a music gain and keeps doing it while a session runs.
 *
 * Two sources, in the order that matches their reliability on a head unit:
 *
 * 1. **the car bus** — wheel speed over the manufacturer's interface (BYD `autoservice` here). It
 *    keeps working in a tunnel and does not need a satellite, but it needs the unit's own channel
 *    (adb in BYD's case) and is therefore the source most likely to be unavailable;
 * 2. **location** — universal, but blind in tunnels, and a network fix often has no speed field at
 *    all. [HiCarLocationSpeed] derives one from consecutive positions when that happens.
 *
 * [HiCarSpeedEstimator] decides what to do when neither has news: hold for a tunnel, then fade.
 * The curve ([HiCarSpeedGainCurve]) and the smoother ([HiCarGainSmoother]) run once a second, and
 * only the resulting dB value reaches the audio sink — the feature never touches a system volume.
 */
internal class HiCarSpeedMonitor(
    context: Context,
    private val settings: HiCarSpeedVolumeSettings,
    private val onLog: (String) -> Unit,
    /** Called about once a second with the gain to apply, in dB. */
    private val onGainDb: (Double) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val estimator = HiCarSpeedEstimator()
    private val curve = settings.curve()
    private val smoother = HiCarGainSmoother()
    private val handler = Handler(Looper.getMainLooper())

    private var stopVehicle: (() -> Unit)? = null
    private var location: HiCarLocationSpeed? = null

    @Volatile private var vehicleKmh: Double? = null
    @Volatile private var vehicleAtMillis = 0L
    @Volatile private var locationKmh: Double? = null
    @Volatile private var locationAtMillis = 0L

    private var started = false
    private var lastSource = ""
    private var lastLoggedDb = Double.NaN
    private var lastLoggedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val vehicleFresh = vehicleKmh != null && now - vehicleAtMillis <= VEHICLE_FRESH_MILLIS
            val locationFresh = locationKmh != null && now - locationAtMillis <= LOCATION_FRESH_MILLIS
            val useVehicle = settings.source != HiCarSpeedVolumeSettings.Source.LOCATION && vehicleFresh
            val useLocation = settings.source != HiCarSpeedVolumeSettings.Source.VEHICLE && locationFresh
            val kmh: Double? = when {
                useVehicle -> vehicleKmh
                useLocation -> locationKmh
                else -> null
            }
            estimator.onFix(kmh, now)
            val source = when {
                useVehicle -> "vehicle"
                useLocation -> "location"
                estimator.isHeld(now) -> "held"
                else -> "none"
            }
            val speed = estimator.speedKmh(now)
            val applied = smoother.next(curve.gainDb(speed), TICK_MILLIS)
            onGainDb(applied)
            logIfChanged(source, speed, applied, now)
            if (started) handler.postDelayed(this, TICK_MILLIS)
        }
    }

    fun start() {
        if (started) return
        if (!settings.enabled) {
            onLog("speed volume: off; music is played at its own level")
            return
        }
        started = true
        if (settings.source != HiCarSpeedVolumeSettings.Source.LOCATION) startVehicle()
        if (settings.source != HiCarSpeedVolumeSettings.Source.VEHICLE) startLocation()
        handler.post(tick)
        onLog(
            "speed volume: start %.0f km/h → 0 dB, %.0f km/h → +%.1f dB, source ${settings.source.name.lowercase()}".format(
                curve.startKmh, curve.fullKmh, curve.maxGainDb,
            ),
        )
    }

    private fun startVehicle() {
        stopVehicle = runCatching {
            BydClusterSpeedReader.observe(appContext) { kmh ->
                vehicleKmh = kmh?.toDouble()
                vehicleAtMillis = SystemClock.elapsedRealtime()
            }
        }.onFailure {
            onLog("speed volume: the car's wheel-speed channel is unavailable (${it.javaClass.simpleName}); location will be used")
        }.getOrNull()
    }

    private fun startLocation() {
        val created = HiCarLocationSpeed(appContext) { kmh ->
            if (kmh != null) {
                locationKmh = kmh
                locationAtMillis = SystemClock.elapsedRealtime()
            }
        }
        if (created.start()) {
            location = created
        } else {
            created.close()
            onLog("speed volume: no location provider could be started; the gain stays at 0 dB")
        }
    }

    private fun logIfChanged(source: String, speed: Double, gain: Double, now: Long) {
        val changedSource = source != lastSource
        val changedGain = lastLoggedDb.isNaN() || kotlin.math.abs(gain - lastLoggedDb) >= 1.0
        if ((changedSource || changedGain) && now - lastLoggedAt >= 2_000) {
            lastSource = source
            lastLoggedDb = gain
            lastLoggedAt = now
            onLog(
                "speed volume: %.0f km/h from %s → %+.1f dB".format(speed, source, gain),
            )
        }
    }

    override fun close() {
        started = false
        handler.removeCallbacks(tick)
        stopVehicle?.invoke()
        stopVehicle = null
        location?.close()
        location = null
        estimator.reset()
        smoother.reset()
    }

    private companion object {
        const val TICK_MILLIS = 1_000L

        /** A wheel-speed read every 250 ms; three missed reads mean the channel is gone. */
        const val VEHICLE_FRESH_MILLIS = 4_000L

        /** Location updates arrive about once a second; five missed fixes are a tunnel, not a stop. */
        const val LOCATION_FRESH_MILLIS = 6_000L
    }
}
