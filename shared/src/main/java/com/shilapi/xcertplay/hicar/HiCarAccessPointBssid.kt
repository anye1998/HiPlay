package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.hicarauth.NetInterfaceMac
import com.shilapi.xcertplay.network.HotspotInterfaceBssid
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import java.io.File

/**
 * Resolves the group owner BSSID that goes into the HiCar credential string.
 *
 * Android frequently hides a Wi-Fi Direct interface's real address from `NetworkInterface`: on a
 * HUAWEI MRX-AL09 tablet (Android 12) it returned `02:00:00:00:00:00` for `p2p-p2p0-2`, and HiCar's
 * own validator refuses that value — correctly, because a phone told to join that BSSID joins
 * nothing, and the failure then looks like a phone-side problem.
 *
 * Four sources are tried, cheapest and most authoritative first:
 *
 * 1. the framework's interface address (`WifiP2pGroupManager` reads it via `NetworkInterface`);
 * 2. `/sys/class/net/<iface>/address`, which is what the reference receiver uses;
 * 3. the kernel's own interface table via `getifaddrs()` (`NetInterfaceMac`), which is not masked by
 *    the framework and therefore works on devices where 1 and 2 both refuse;
 * 4. the MAC encoded in the interface's IPv6 link-local address (RFC 4291 modified EUI-64), which
 *    needs no file access and survives a masked `NetworkInterface` view;
 * 5. the Wi-Fi Direct group owner's device address — a framework value, but the *persistent* P2P
 *    identity, which may differ from the randomised address the access point actually broadcasts.
 *
 * Every source's outcome is reported, including the ones that failed and why: a log line that only
 * says "cannot read the BSSID" costs another device round trip to explain.
 */
internal object HiCarAccessPointBssid {
    private val INTERFACE_NAME = Regex("[A-Za-z0-9_.:-]{1,32}")

    /** Well-formed, unicast, and not one of Android's placeholder addresses. */
    fun usable(value: String?): Boolean = HotspotInterfaceBssid.usable(value)

    enum class Source(val label: String) {
        BOTH("interface and sysfs agree"),
        INTERFACE("interface only; sysfs unavailable"),
        SYSFS("sysfs only; the framework reported a placeholder"),
        SYSFS_OVER_INTERFACE("sysfs; the framework reported a different address"),
        KERNEL("kernel interface table; the framework reported a placeholder"),
        LINK_LOCAL("MAC decoded from the interface's IPv6 link-local address"),
        OWNER("Wi-Fi Direct owner device address; may not be the broadcast address"),
    }

    class Resolution(val bssid: String, val source: Source)

    /**
     * The outcome plus one line per source, so a failure explains itself.
     *
     * [reasons] is populated in every case, not only on failure: knowing that sysfs was readable but
     * stale is as useful as knowing it was not readable at all.
     */
    class Attempt(val resolution: Resolution?, val reasons: List<String>)

    /**
     * Picks between the framework's view and sysfs.
     *
     * Agreement is reported as such. When both are usable but differ, sysfs wins: it is the kernel's
     * own value for that exact interface name, while the framework value can be a cached view of an
     * interface that was recreated with a new address. The disagreement is still surfaced through
     * [Source.SYSFS_OVER_INTERFACE] so it is visible in the log.
     */
    fun choose(interfaceAddress: String?, sysfsAddress: String?): Resolution? {
        // sysfs content carries a trailing newline; trim before judging either source.
        val fromInterface = interfaceAddress?.trim()?.takeIf(::usable)
        val fromFile = sysfsAddress?.trim()?.takeIf(::usable)
        return when {
            fromInterface != null && fromFile != null ->
                if (fromInterface.equals(fromFile, true)) Resolution(fromInterface, Source.BOTH)
                else Resolution(fromFile, Source.SYSFS_OVER_INTERFACE)
            fromInterface != null -> Resolution(fromInterface, Source.INTERFACE)
            fromFile != null -> Resolution(fromFile, Source.SYSFS)
            else -> null
        }
    }

    /**
     * Resolves the BSSID for [info] and records what each source said.
     *
     * The file and link-local readers are injected so the rules below stay testable without a
     * device; production passes the real ones.
     */
    fun inspect(
        info: WirelessHotspotInfo,
        readSysfs: (String) -> String? = ::readInterfaceAddress,
        readKernel: (String) -> String? = NetInterfaceMac::read,
        deriveFromLinkLocal: (String) -> String? = HotspotInterfaceBssid::read,
    ): Attempt {
        val name = info.interfaceName
        val reasons = ArrayList<String>(5)
        val framework = info.bssid?.trim()?.takeIf(::usable)
        if (framework == null) {
            reasons += "framework=${info.bssid ?: "none"} (${if (info.bssid == null) "absent" else "placeholder"})"
        }

        val sysfs = if (name != null && INTERFACE_NAME.matches(name)) {
            val raw = runCatching { readSysfs(name) }
            val value = raw.getOrNull()?.trim()?.takeIf(::usable)
            if (value == null) {
                reasons += "sysfs=${raw.exceptionOrNull()?.let { it.javaClass.simpleName } ?: "unusable"}"
            }
            value
        } else {
            reasons += "sysfs=no valid interface name (${name ?: "none"})"
            null
        }

        val kernel = if (name != null && INTERFACE_NAME.matches(name)) {
            val raw = runCatching { readKernel(name) }
            val value = raw.getOrNull()?.trim()?.takeIf(::usable)
            if (value == null) {
                reasons += "kernel=${raw.exceptionOrNull()?.let { it.javaClass.simpleName } ?: "unavailable"}"
            }
            value
        } else null

        val linkLocal = if (name != null && INTERFACE_NAME.matches(name)) {
            val value = runCatching { deriveFromLinkLocal(name) }.getOrNull()?.takeIf(::usable)
            if (value == null) reasons += "link-local=${HotspotInterfaceBssid.describe(name)}"
            value
        } else null

        val owner = info.groupOwnerDeviceAddress?.trim()?.takeIf(::usable)
        if (owner == null && info.groupOwnerDeviceAddress != null) {
            reasons += "owner=${info.groupOwnerDeviceAddress} (placeholder)"
        }

        choose(framework, sysfs)?.let { return Attempt(it, reasons) }
        if (kernel != null) return Attempt(Resolution(kernel, Source.KERNEL), reasons)
        if (linkLocal != null) return Attempt(Resolution(linkLocal, Source.LINK_LOCAL), reasons)
        if (owner != null) return Attempt(Resolution(owner, Source.OWNER), reasons)
        reasons += "owner=absent"
        return Attempt(null, reasons)
    }

    /** Convenience for callers that only need the address. */
    fun resolve(
        info: WirelessHotspotInfo,
        readSysfs: (String) -> String? = ::readInterfaceAddress,
    ): Resolution? = inspect(info, readSysfs).resolution

    /**
     * `/sys/class/net/<iface>/address` is world-readable on many Android builds and is the address
     * the framework itself uses; the name is validated before it reaches a path.
     */
    private fun readInterfaceAddress(name: String): String? =
        File("/sys/class/net/$name/address").readText().trim().takeIf { it.isNotEmpty() }
}
