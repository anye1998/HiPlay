package com.shilapi.xcertplay.hicar

/**
 * The deep link a phone reads from the pairing QR code.
 *
 * ```
 * hicar://car/connect?devicename=<name>&mac=<head unit BT>&moduleid=<first 6>&submoduleid=<last 2>
 *                    &pin=<6 digits>&ability=<4 or 6>
 * ```
 *
 * Everything in it is something this side already has:
 *
 * - `mac` is the **head unit's** classic Bluetooth address, the same one the advertisement carries
 *   and the same one the identity is derived from. A placeholder here produces a code that scans,
 *   connects to nothing, and looks like the QR feature is broken.
 * - `moduleid` / `submoduleid` are the advertised model id split 6 + 2, so the phone computes the
 *   same device id as the handshake will.
 * - `pin` is the code on screen, leading zeros kept: the phone fills it in, so a driver who cannot
 *   type while driving does not have to.
 * - `ability` is 4 for wireless only and 6 for wireless plus USB (the bit meanings beyond that are
 *   not documented).
 *
 * The parameters are **not** URL-encoded, so anything that would break the parsing is replaced instead
 * of escaped; the name is only ever shown on the phone.
 *
 * Ported from `PairingQrCode` in GGBondLab/OpenHC (GPL-3.0-only).
 */
object HiCarPairingCode {
    const val ABILITY_WIRELESS = "4"
    const val ABILITY_WIRELESS_AND_USB = "6"

    private const val PREFIX = "hicar://car/connect?"
    private val UNSAFE = "&=#%+?".toSet()
    private val ADDRESS = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val MODEL = Regex("^[0-9A-F]{8}$")

    /**
     * Addresses a phone cannot use: the unset address, the one Android reports before Bluetooth is
     * up, and the broadcast address.
     */
    private val PLACEHOLDERS = setOf(
        "00:00:00:00:00:00",
        "02:00:00:00:00:00",
        "FF:FF:FF:FF:FF:FF",
    )

    /**
     * The link, or null when one of the inputs cannot produce a code a phone would act on.
     *
     * Null rather than an exception because the caller is a screen: "no QR yet" is a normal state
     * before Bluetooth has reported its address, and [problem] explains it in words the driver can
     * act on.
     */
    fun buildOrNull(
        model: String,
        bluetoothAddress: String,
        pin: String,
        deviceName: String,
        supportsUsb: Boolean = true,
    ): String? {
        if (problem(model, bluetoothAddress, pin, deviceName) != null) return null
        val address = bluetoothAddress.trim().uppercase().replace('-', ':')
        val name = displayName(deviceName)
        val ability = if (supportsUsb) ABILITY_WIRELESS_AND_USB else ABILITY_WIRELESS
        return PREFIX + "devicename=" + name +
            "&mac=" + address +
            "&moduleid=" + model.substring(0, 6) +
            "&submoduleid=" + model.substring(6) +
            "&pin=" + pin +
            "&ability=" + ability
    }

    /**
     * Why a code cannot be built right now, or null when it can.
     *
     * Each sentence names the field and what to do about it: "the QR code is unavailable" sends the
     * driver looking for a bug that is usually an empty Bluetooth address field on the same page.
     */
    fun problem(
        model: String,
        bluetoothAddress: String,
        pin: String,
        deviceName: String,
    ): String? {
        val address = bluetoothAddress.trim().uppercase().replace('-', ':')
        return when {
            !MODEL.matches(model.trim().uppercase()) ->
                "机型码必须是 8 位十六进制，二维码里要拆成前后两半"
            !ADDRESS.matches(address) ->
                "本机蓝牙地址格式不对，二维码里的 mac 必须是车机自己的地址"
            address in PLACEHOLDERS ->
                "本机蓝牙地址还是占位值，手机扫了也连不上；先在上面填真实地址"
            pin.length != 6 || !pin.all { it in '0'..'9' } ->
                "连接码必须是 6 位数字"
            displayName(deviceName).isEmpty() ->
                "车机名不能为空，手机上要靠它分辨这是哪台车"
            else -> null
        }
    }

    /** The name as it goes into the unencoded query string. */
    fun displayName(name: String): String {
        val out = StringBuilder(name.trim().length)
        for (character in name.trim()) {
            out.append(
                if (character in UNSAFE || character.isWhitespace() || character.isISOControl()) '_' else character,
            )
        }
        return out.toString()
    }
}
