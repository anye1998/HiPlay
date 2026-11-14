package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Everything about the device that decides whether HiCar discovery can work, read on the device
 * itself so a log export carries it.
 *
 * This exists because the decisive question — "is the BLE advertising address the adapter's own
 * address?" — needs two numbers that live in different places: one on this device, one on the
 * phone's scanner. An app cannot run `dumpsys` and cannot hold `LOCAL_MAC_ADDRESS`, so every value
 * here comes from a public API or a readable file, and every value that is not obtainable says
 * *why* instead of disappearing. A missing line is a fact; a filled-in guess is a bug.
 *
 * [rootProbe] is the one exception, and it is a separate, explicitly triggered step: on a rooted
 * head unit `su` can read the two values that no app-level API exposes. It reads only.
 */
internal object HiCarDeviceAudit {

    private const val PLACEHOLDER = "02:00:00:00:00:00"

    private val NETWORK_FEATURES = listOf(
        "android.hardware.bluetooth",
        "android.hardware.bluetooth_le",
        "android.hardware.wifi",
        "android.hardware.wifi.direct",
        "android.hardware.usb.host",
    )

    /** Keys worth keeping out of `bt_config.conf`; the rest is noise or secrets we do not need. */
    private val CONFIG_KEYS = listOf(
        "Address",
        "Name",
        "LE_LOCAL_KEY_IRK",
        "LE_LOCAL_KEY_IR",
        "LE_LOCAL_KEY_DHK",
        "LE_LOCAL_KEY_ER",
        "ControllerMacAddr",
    )

    fun collect(context: Context): List<String> = buildList {
        add("--- device audit ---")
        add(buildLine())
        addAll(bluetoothLines(context))
        addAll(addressLines(context))
        addAll(permissionLines(context))
        addAll(wifiLines(context))
        addAll(featureLines(context))
        add(compareHint())
        add(rootLine())
        add("--- end of device audit ---")
    }

    private fun buildLine(): String =
        "build: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) / " +
            "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} / device=${Build.DEVICE} / " +
            "fingerprint=${Build.FINGERPRINT}"

    private fun bluetoothLines(context: Context): List<String> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
            ?: return listOf("bluetooth: no adapter on this device")
        return listOf(
            "bluetooth: enabled=${read { adapter.isEnabled }} name='${read { adapter.name }}' " +
                "scanMode=${read { scanMode(adapter) }}",
            "adapter: multipleAdvertisement=${read { adapter.isMultipleAdvertisementSupported }} " +
                "extendedAdvertising=${read { extendedAdvertising(adapter) }} " +
                "le2m=${read { le2m(adapter) }} leCoded=${read { leCoded(adapter) }} " +
                "offloadedFiltering=${read { adapter.isOffloadedFilteringSupported }}",
            "bonds: ${read { bonds(adapter) }}",
        )
    }

    /**
     * The three addresses that decide the whole discovery stage, each from a different source.
     *
     * The API value is what a scanner would see only if the stack advertises with it; the settings
     * value survives on some ROMs where the API masks it; the interface list is the kernel's view
     * and is the only one that cannot be redacted to a placeholder.
     */
    private fun addressLines(context: Context): List<String> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val api = try {
            adapter?.address
        } catch (failure: Exception) {
            null
        }
        val settings = try {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        } catch (failure: Exception) {
            null
        }
        return listOf(
            "bluetooth address (api): ${describeBluetoothAddress(api)}",
            "bluetooth address (settings): ${describeBluetoothAddress(settings)}",
            "interfaces: ${interfaces()}",
        )
    }

    private fun permissionLines(context: Context): List<String> {
        val wanted = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("android.permission.NEARBY_WIFI_DEVICES")
            }
        }
        val granted = wanted.filter {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val missing = wanted - granted.toSet()
        return listOf(
            "permissions granted: ${granted.shortNames()}",
            "permissions missing: ${missing.shortNames()}",
        )
    }

    private fun wifiLines(context: Context): List<String> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return listOf("wifi: no wifi service")
        return listOf(
            "wifi: enabled=${read { wifi.isWifiEnabled }} fiveGhz=${read { fiveGhz(wifi) }} " +
                "scanAlwaysAvailable=${read { wifi.isScanAlwaysAvailable }}",
        )
    }

    private fun featureLines(context: Context): List<String> {
        val manager = context.packageManager
        return listOf(
            "features: " + NETWORK_FEATURES.joinToString(", ") {
                "${it.substringAfterLast('.')}=${manager.hasSystemFeature(it)}"
            },
        )
    }

    /**
     * The comparison the driver has to make, spelled out in the file instead of in a chat message.
     *
     * The two numbers are printed above; this line says what the difference between them means.
     */
    private fun compareHint(): String =
        "compare: the phone's scanner must show the same MAC as 'bluetooth address (api)'. " +
            "An odd first octet (I/G bit set) cannot be a public address, so no phone can dial it; " +
            "an ambiguous one is settled by the address type the scanner prints next to the MAC — " +
            "see docs/HICAR_MIGRATION.md 7.10"

    private fun rootLine(): String {
        val markers = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/magisk",
        )
        val found = markers.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        return if (found.isEmpty()) {
            "root: no su binary visible; 'Root probe' will report the refusal and stop"
        } else {
            "root: ${found.joinToString()} — 'Root probe' can read bt_config.conf and dumpsys"
        }
    }

    /**
     * Reads the two values only root can see: the adapter's real BR address and whether the LE
     * stack holds an identity-resolving key.
     *
     * Read-only by construction — three commands, none of which writes, restarts or reconfigures
     * anything. Every failure names the command that failed, because "su denied" and "file missing"
     * call for different next steps. It never retries and never falls back to a guess.
     */
    fun rootProbe(): List<String> = buildList {
        add("--- root probe (read only) ---")
        val identity = shell("id")
        add("id: ${identity.ifEmpty { "(no output)" }}")
        if (!identity.contains("uid=0")) {
            add("su: not granted; nothing else was attempted")
            add("--- end of root probe ---")
            return@buildList
        }
        val config = shell("cat /data/misc/bluedroid/bt_config.conf")
        if (config.isBlank()) {
            add("bt_config.conf: empty or unreadable")
        } else {
            val wanted = config.lineSequence()
                .map { it.trim() }
                .filter { line -> CONFIG_KEYS.any { line.startsWith(it, ignoreCase = true) } }
                .toList()
            add("bt_config.conf: ${wanted.size} line(s) of interest")
            wanted.forEach { add("  $it") }
            add(
                "bt_config.conf: LE_LOCAL_KEY_IRK present=" +
                    config.contains("LE_LOCAL_KEY_IRK", ignoreCase = true),
            )
        }
        val dumpsys = shell(
            "dumpsys bluetooth_manager | grep -iE 'Address:|Identity address|LE address|Name:' | head -20",
        )
        if (dumpsys.isBlank()) {
            add("dumpsys bluetooth_manager: no matching line")
        } else {
            add("dumpsys:")
            dumpsys.lineSequence().filter { it.isNotBlank() }.take(20).forEach { add("  ${it.trim()}") }
        }
        add("--- end of root probe ---")
    }

    /**
     * Describes an address exactly as the platform returns it.
     *
     * Since Android 6 `BluetoothAdapter.getAddress()` returns 02:00:00:00:00:00 to any app without
     * LOCAL_MAC_ADDRESS. Printing that placeholder as if it were an address is worse than printing
     * nothing: it would be compared against the phone's scanner, "differ", and send the reader after
     * the wrong problem.
     */
    fun describeBluetoothAddress(raw: String?): String = when {
        raw.isNullOrBlank() -> "none"
        raw.equals(PLACEHOLDER, ignoreCase = true) -> "masked by the platform ($PLACEHOLDER)"
        else -> "$raw (${addressType(raw)})"
    }

    /**
     * Classifies the first octet, and refuses to over-claim.
     *
     * Two facts are certain. A public (BR/EDR) address is always unicast, so an odd first octet
     * (I/G bit set) cannot be one. And `10` in the top two bits is not a legal random-address form,
     * so such an address can only be public. Everything else is genuinely ambiguous: `4C:5E:0C…` is
     * both a real OUI and a shape a resolvable private address can take, and only the address type
     * in the advertising PDU — what a scanner reports next to the MAC — settles it.
     */
    fun addressType(raw: String): String {
        val first = raw.substringBefore(':').toIntOrNull(16) ?: return "unknown type"
        if (first and 0x01 == 1) return "random (I/G set): cannot be a public BR address"
        return when (first and 0xC0) {
            0xC0 -> "looks static-random (top two bits 11)"
            0x80 -> "public"
            else -> "ambiguous: a public OUI or a resolvable private address — the scanner's address type decides"
        }
    }

    private fun scanMode(adapter: BluetoothAdapter): String = when (adapter.scanMode) {
        BluetoothAdapter.SCAN_MODE_NONE -> "not connectable"
        BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "connectable, not discoverable"
        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "discoverable"
        else -> "unknown"
    }

    private fun extendedAdvertising(adapter: BluetoothAdapter): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            adapter.isLeExtendedAdvertisingSupported.toString()
        } else {
            "needs API 26"
        }

    private fun le2m(adapter: BluetoothAdapter): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            adapter.isLe2MPhySupported.toString()
        } else {
            "needs API 26"
        }

    private fun leCoded(adapter: BluetoothAdapter): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            adapter.isLeCodedPhySupported.toString()
        } else {
            "needs API 26"
        }

    private fun fiveGhz(wifi: WifiManager): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifi.is5GHzBandSupported.toString()
        } else {
            "needs API 30 (the Wi-Fi P2P preflight line reports it at runtime instead)"
        }

    private fun bonds(adapter: BluetoothAdapter): String {
        val bonded = adapter.bondedDevices ?: return "none"
        if (bonded.isEmpty()) return "none"
        return "${bonded.size} (" + bonded.joinToString { "${it.address} '${it.name}'" } + ")"
    }

    /** Kernel view of every interface: the only address source that cannot be a placeholder. */
    private fun interfaces(): String {
        val names = runCatching { File("/sys/class/net").list()?.toList() }.getOrNull()
        if (names.isNullOrEmpty()) return "unreadable (/sys/class/net)"
        return names.sorted().joinToString(", ") { "$it=${interfaceMac(it)}" }
    }

    private fun interfaceMac(name: String): String = runCatching {
        File("/sys/class/net/$name/address").readText().trim().ifEmpty { "empty" }
    }.getOrElse { it.javaClass.simpleName }

    private fun shell(command: String): String = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor(8, TimeUnit.SECONDS)) {
            text.trim()
        } else {
            process.destroy()
            "timed out after 8 s"
        }
    } catch (failure: Exception) {
        "${failure.javaClass.simpleName}: ${failure.message}"
    }

    private fun List<String>.shortNames(): String =
        map { it.substringAfterLast('.') }.joinToString().ifEmpty { "none" }

    /** Reads a permission-checked getter without letting a refusal abort the whole audit. */
    private inline fun read(block: () -> Any?): String = try {
        block()?.toString() ?: "none"
    } catch (failure: Exception) {
        failure.javaClass.simpleName
    }
}
