package com.shilapi.xcertplay.hicarauth

/**
 * Reads a network interface's hardware address straight from the kernel (`libnetiface.so`).
 *
 * `NetworkInterface.getHardwareAddress()` is masked by the Android framework for ordinary apps: on a
 * HUAWEI MRX-AL09 (Android 12) it reports `02:00:00:00:00:00` for the Wi-Fi Direct group interface,
 * which is not the address the access point broadcasts. The native side calls `getifaddrs()`, which
 * returns the same value `ip link` shows — including the per-group randomised address Android uses
 * for Wi-Fi Direct.
 *
 * Failures degrade to null rather than throwing: a device where the lookup is impossible should still
 * reach the next candidate source, not crash the receiver.
 */
object NetInterfaceMac {
    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("netiface") }.isSuccess }

    /** True when this APK carries the helper; false for a tree built without the CMake project. */
    val available: Boolean get() = loaded

    /** The six-byte hardware address as `aa:bb:cc:dd:ee:ff`, or null when the kernel has no entry. */
    fun read(interfaceName: String?): String? {
        if (!loaded || interfaceName.isNullOrBlank()) return null
        return runCatching { nativeRead(interfaceName) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Why [read] returned nothing.
     *
     * "unavailable" was not good enough on the device: a library that failed to load and an interface
     * the kernel does not expose need different fixes, and the log could not tell them apart.
     */
    fun describe(interfaceName: String?): String {
        if (!loaded) return "libnetiface not loaded"
        if (interfaceName.isNullOrBlank()) return "no interface name"
        val named = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val present = named.any { it.name == interfaceName }
        if (!present) {
            val visible = named.joinToString(",") { it.name }
            return "no interface $interfaceName (visible: $visible)"
        }
        return "$interfaceName has no AF_PACKET entry"
    }

    @JvmStatic
    private external fun nativeRead(interfaceName: String): String?
}
