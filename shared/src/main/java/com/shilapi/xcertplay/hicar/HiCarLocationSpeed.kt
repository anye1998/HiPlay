package com.shilapi.xcertplay.hicar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Speed from the platform's location providers.
 *
 * This is the fallback source: it needs no manufacturer interface and works on any head unit with a
 * GPS receiver, but it has three weaknesses that have to be handled rather than ignored:
 *
 * - **no signal in tunnels and underground car parks**, which is why [HiCarSpeedEstimator] holds
 *   the last value instead of treating a missing fix as zero;
 * - **`Location.hasSpeed()` is often false on network fixes**, so the speed is derived from the
 *   distance between two fixes, which is noisy at low speed;
 * - **the phone's speed and the car's speed differ** by a few percent (GPS under-reads on hills).
 *
 * Only providers that can report a real position are subscribed. A fix without a usable speed
 * checks in as `null`, which the estimator understands as "no news", not as "stopped".
 */
internal class HiCarLocationSpeed(
    context: Context,
    private val onFix: (Double?) -> Unit,
) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(LocationManager::class.java)
    private val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { it in manager.allProviders }

    private var lastLocation: Location? = null
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(kmhOf(location))
            lastLocation = location
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in the platform API, still called on older head units")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    /** True when at least one provider accepted the subscription. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (started) return true
        if (!permitted()) return false
        var subscribed = 0
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, UPDATE_MILLIS, MIN_DISTANCE_METERS, listener, Looper.getMainLooper())
                subscribed++
            } catch (failure: Exception) {
                // A provider can be listed but unusable (no hardware, permission revoked mid-session).
            }
        }
        started = subscribed > 0
        return started
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager.removeUpdates(listener) }
        lastLocation = null
    }

    override fun close() = stop()

    private fun permitted(): Boolean =
        context().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun context(): Context = appContext

    private val appContext: Context = context.applicationContext

    private fun kmhOf(location: Location): Double? {
        if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f) {
            return (location.speed * 3.6).toDouble().takeIf { it <= MAX_KMH }
        }
        // No speed in the fix: derive it from the previous position. Below walking pace the noise of
        // this difference is larger than the speed itself, so a tiny value is reported as zero.
        val previous = lastLocation ?: return null
        val seconds = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
        if (seconds <= 0.4) return null
        val meters = distanceMeters(previous, location)
        val kmh = meters / seconds * 3.6
        return if (kmh < MIN_DERIVED_KMH) 0.0 else kmh.takeIf { it <= MAX_KMH }
    }

    private fun distanceMeters(a: Location, b: Location): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    companion object {
        private const val UPDATE_MILLIS = 1_000L
        private const val MIN_DISTANCE_METERS = 0f
        private const val MIN_DERIVED_KMH = 2.0
        private const val MAX_KMH = 300.0
    }
}
