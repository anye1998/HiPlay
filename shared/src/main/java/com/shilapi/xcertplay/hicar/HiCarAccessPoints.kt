package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager

/**
 * Picks the hotspot backend HiCar gets, in a fixed order, and closes every backend it rejects.
 *
 * Kept free of Android types so the two rules that matter can be tested without a device:
 * - a backend that settles outside the accepted band is **closed** before the next one is tried,
 *   otherwise a 2.4 GHz group keeps the radio busy and the retry fails for an unrelated reason;
 * - every rejection carries a reason, because "no access point" alone has already cost one device
 *   round trip.
 */
internal object HiCarAccessPoints {
    class Backend(val name: String, val create: () -> WirelessHotspotManager)

    class Choice(val backend: Backend, val manager: WirelessHotspotManager, val info: WirelessHotspotInfo)

    /**
     * [choice] is the first backend that started inside the accepted band; when null, [reasons]
     * explains each rejection and [bandRefused] separates "started, wrong band" from "never started".
     */
    class Outcome(
        val choice: Choice?,
        val reasons: List<String>,
        val bandRefused: Boolean,
    )

    fun open(
        backends: List<Backend>,
        timeoutMillis: Long,
        accepted: (Int?) -> Boolean,
        log: (String) -> Unit = {},
    ): Outcome {
        val reasons = ArrayList<String>()
        var bandRefused = false
        for (backend in backends) {
            val manager = try {
                backend.create()
            } catch (failure: Exception) {
                reasons += "${backend.name} unavailable (${failure.javaClass.simpleName})"
                log("HiCar access point: ${backend.name} unavailable (${failure.javaClass.simpleName})")
                continue
            }
            val info = try {
                manager.start(timeoutMillis)
            } catch (failure: Exception) {
                runCatching { manager.close() }
                val detail = failure.message?.takeIf { it.isNotBlank() } ?: "no detail"
                reasons += "${backend.name} failed (${failure.javaClass.simpleName}: $detail)"
                log("HiCar access point: ${backend.name} failed (${failure.javaClass.simpleName})")
                continue
            }
            if (!accepted(info.frequencyMHz)) {
                // Close it before trying the next backend: a group left up would hold the radio.
                runCatching { manager.close() }
                bandRefused = true
                val frequency = info.frequencyMHz?.toString() ?: "unknown"
                reasons += "${backend.name} settled on $frequency MHz, not an accepted 5 GHz channel"
                log("HiCar access point: ${backend.name} settled on $frequency MHz; closed it")
                continue
            }
            return Outcome(Choice(backend, manager, info), reasons, bandRefused)
        }
        return Outcome(null, reasons, bandRefused)
    }
}
