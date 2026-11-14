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

    @JvmStatic
    private external fun nativeRead(interfaceName: String): String?
}
