package com.shilapi.xcertplay.hicar

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Reads the head unit's own BR/EDR address, which no ordinary app is allowed to ask the platform for.
 *
 * `BluetoothAdapter.getAddress()` answers `02:00:00:00:00:00` to any app without
 * `LOCAL_MAC_ADDRESS`, and the secure setting behind it needs `BLUETOOTH_PRIVILEGED`. So the address
 * is taken from the same two places a PC would look, over the local adb shell this app already holds
 * for the vehicle bus:
 *
 * 1. `settings get secure bluetooth_address` — the value the platform stores once the stack starts.
 * 2. `dumpsys bluetooth_manager` — the line the Bluetooth service prints for itself, used when the
 *    setting was never written or has been cleared.
 *
 * Neither needs root, but both need adbd to trust this app's key; the settings page offers that with
 * 「获取 ADB 授权」. A masked placeholder is refused rather than filled in: writing `02:00:…` into the
 * address field would derive an identity no phone would ever match, which is worse than an empty one.
 */
object HiCarBluetoothAddress {

    /** Who answered, so the settings page can say where the number came from. */
    enum class Source(val label: String) {
        SETTINGS("系统设置"),
        DUMPSYS("蓝牙服务"),
        NONE("没有来源"),
    }

    /**
     * One attempt at the address.
     *
     * @property address the usable address, or null when none was found.
     * @property detail what to print when [address] is null, in the driver's words.
     */
    data class Reading(
        val address: String?,
        val source: Source,
        val detail: String,
    )

    internal const val SETTINGS_COMMAND = "settings get secure bluetooth_address"
    internal const val DUMPSYS_COMMAND = "dumpsys bluetooth_manager"

    /**
     * How much of `dumpsys` is searched.
     *
     * The service prints its own block first; the bonded-device list follows and carries addresses of
     * a different kind. A bound keeps a later `address:` line from being mistaken for the car's own.
     */
    private const val DUMPSYS_HEAD_LINES = 60

    private val MAC = Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")

    /** Values the platform or a stub ROM returns when it will not tell. */
    private val MASKED = Regex("(?i)^(02:00:00:00:00:00|00:00:00:00:00:00)$")

    private val ADDRESS_LINE =
        Regex("(?i)^\\s*(?:local\\s+)?address\\s*:\\s*([0-9a-f]{2}(?::[0-9a-f]{2}){5})\\s*$")

    /** A real unicast address, not the placeholder. Case-insensitive, and never truncated. */
    fun usable(raw: String?): Boolean {
        val value = raw?.trim()?.uppercase() ?: return false
        return MAC.matches(value) && !MASKED.matches(value)
    }

    /** Parses `settings get secure bluetooth_address`; `null` and the placeholder both read as absent. */
    fun fromSettings(output: String?): String? {
        val value = output?.trim()?.uppercase() ?: return null
        return value.takeIf { usable(it) }
    }

    /** Parses the Bluetooth service's own `address:` line from the head of `dumpsys bluetooth_manager`. */
    fun fromDumpsys(output: String?): String? {
        if (output.isNullOrBlank()) return null
        for (line in output.lineSequence().take(DUMPSYS_HEAD_LINES)) {
            val value = ADDRESS_LINE.find(line)?.groupValues?.get(1)?.uppercase() ?: continue
            if (usable(value)) return value
        }
        return null
    }

    /**
     * Reads the address over the local adb channel.
     *
     * Never offers a key: `mayAsk = false`, so the car's "allow debugging?" dialog cannot appear from
     * a settings page the driver may be looking at while driving. Without an authorised channel the
     * answer is a reason, not an empty string.
     */
    fun read(context: Context, log: (String) -> Unit): Reading = runCatching {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            when (adb.connect(mayAsk = false)) {
                LocalAdb.Access.READY -> Unit
                LocalAdb.Access.NOT_APPROVED -> {
                    log("bluetooth address: adb is not approved yet; press 获取 ADB 授权 first")
                    return Reading(null, Source.NONE, "adb 还没有被允许：先点上面的「获取 ADB 授权」")
                }
                LocalAdb.Access.UNREACHABLE -> {
                    log("bluetooth address: adbd is not listening on 127.0.0.1:5555")
                    return Reading(null, Source.NONE, "连不上 127.0.0.1:5555：请确认车机的 adb 调试已开启")
                }
                LocalAdb.Access.UNSUPPORTED -> {
                    log("bluetooth address: this adbd only accepts TLS pairing")
                    return Reading(null, Source.NONE, "该 adbd 只接受无线配对（TLS），本应用走 5555 明文通道，用不了")
                }
            }

            val setting = adb.shell(SETTINGS_COMMAND)
            fromSettings(setting)?.let {
                log("bluetooth address: $it from secure settings")
                return Reading(it, Source.SETTINGS, "settings get secure bluetooth_address")
            }
            log(
                "bluetooth address: secure settings answered " +
                    (setting?.trim()?.takeIf { value -> value.isNotEmpty() } ?: "(nothing)") +
                    "; asking the Bluetooth service next",
            )

            val dumped = adb.shell(DUMPSYS_COMMAND, DUMPSYS_TIMEOUT_MILLIS)
            fromDumpsys(dumped)?.let {
                log("bluetooth address: $it from dumpsys bluetooth_manager")
                return Reading(it, Source.DUMPSYS, "dumpsys bluetooth_manager")
            }
            log("bluetooth address: neither source returned a usable address")
            Reading(
                null,
                Source.NONE,
                "两个来源都没有可用地址。可能是蓝牙没打开，或者这台 ROM 把地址藏起来了：" +
                    "先在车机设置里打开蓝牙再点一次，仍不行就手输（设置 → 关于/蓝牙 里能看到）",
            )
        }
    }.getOrElse { failure ->
        log("bluetooth address: the adb read failed (${failure.javaClass.simpleName})")
        Reading(null, Source.NONE, "读取失败：${failure.javaClass.simpleName}（详见日志）")
    }

    /** `dumpsys bluetooth_manager` prints a lot; one 8 s window is enough for a phone-sized dump. */
    private const val DUMPSYS_TIMEOUT_MILLIS = 8_000
}
