package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.network.WirelessHotspotInfo
import java.io.File

/**
 * Resolves the group owner BSSID that goes into the HiCar credential string.
 *
 * Android often hides a Wi-Fi Direct interface's real address from `NetworkInterface`: on a HUAWEI
 * MRX-AL09 tablet it returned the placeholder `02:00:00:00:00:00`, which HiCar's own validator
 * refuses (and rightly so — a phone that tries to join that BSSID joins nothing). The reference
 * receiver solves this by reading `/sys/class/net/<iface>/address` and requiring the two sources to
 * agree, and by refusing to send a placeholder at all rather than substituting one.
 *
 * The parsing is separated from the file read so every rule below is unit-tested.
 */
internal object HiCarAccessPointBssid {
    private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val INTERFACE_NAME = Regex("[A-Za-z0-9_.:-]{1,32}")

    /** Well-formed, unicast, and not one of Android's placeholder addresses. */
    fun usable(value: String?): Boolean {
        if (value == null || !MAC.matches(value)) return false
        if (value.equals("02:00:00:00:00:00", true) || value == "00:00:00:00:00:00") return false
        // A multicast address can never be a group owner.
        return (value.substring(0, 2).toInt(16) and 1) == 0
    }

    /**
     * Picks between the framework's view and sysfs.
     *
     * Two usable but different values mean one of them is stale or belongs to another interface, so
     * neither is trusted: sending the wrong BSSID would look like a phone-side failure later.
     */
    fun choose(interfaceAddress: String?, sysfsAddress: String?): Choice? {
        // sysfs content carries a trailing newline; trim before judging either source.
        val fromInterface = interfaceAddress?.trim()?.takeIf(::usable)
        val fromFile = sysfsAddress?.trim()?.takeIf(::usable)
        return when {
            fromInterface != null && fromFile != null ->
                if (fromInterface.equals(fromFile, true)) Choice(fromInterface, Source.BOTH) else null
            fromInterface != null -> Choice(fromInterface, Source.INTERFACE)
            fromFile != null -> Choice(fromFile, Source.SYSFS)
            else -> null
        }
    }

    enum class Source(val label: String) {
        BOTH("interface and sysfs agree"),
        INTERFACE("interface only; sysfs unavailable"),
        SYSFS("sysfs only; the framework reported a placeholder"),
    }

    class Choice(val bssid: String, val source: Source)

    /** Reads `info`'s interface address, falling back to sysfs, and never returns a placeholder. */
    fun resolve(info: WirelessHotspotInfo, readSysfs: (String) -> String? = ::readInterfaceAddress): Choice? {
        val name = info.interfaceName ?: return choose(info.bssid, null)
        val fromFile = if (INTERFACE_NAME.matches(name)) readSysfs(name) else null
        return choose(info.bssid, fromFile)
    }

    /**
     * `/sys/class/net/<iface>/address` is world-readable on Android and is the address the framework
     * itself uses; the name is validated before it reaches a path.
     */
    private fun readInterfaceAddress(name: String): String? = try {
        File("/sys/class/net/$name/address").readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
