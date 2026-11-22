package com.shilapi.xcertplay.hicar

/**
 * Recognises the system's "allow this app to access the USB device" dialog.
 *
 * The dialog is drawn by the system, has no public API, and differs between ROMs: the title may say
 * "允许访问设备", "是否允许访问 USB 设备" or simply "USB"; the buttons are 允许 / 确定 / OK / Allow. What
 * is stable is that it mentions USB and has one confirming button.
 *
 * This is the whole decision of the accessibility service, kept out of it so it can be tested: an
 * accessibility service that clicks the wrong dialog is a serious thing to ship, and "which dialog is
 * this" is a rule, not a device behaviour.
 *
 * The rule is deliberately narrow — **both** a USB mention and a confirming label have to be present,
 * in the same window. A dialog that only has "允许" could be a permission prompt for the camera, and
 * one that only mentions USB could be a settings page.
 */
object HiCarUsbDialog {

    /** The labels a confirming button uses on the ROMs this receiver runs on. */
    private val CONFIRM_LABELS = setOf(
        "允许",
        "確定",
        "确定",
        "好",
        "OK",
        "Ok",
        "ok",
        "Allow",
        "ALLOW",
    )

    /**
     * Text that has to appear somewhere in the dialog for it to be the USB prompt.
     *
     * `使用 USB 配件` is the phrasing Android uses on the "accessory" variant; `充电` alone must not
     * match, because the charging notification also says USB and has no button worth pressing.
     */
    private val USB_HINTS = listOf("usb", "USB", "配件", "accessory", "Accessory")

    /**
     * The label of the button to press, or null when this window is not the dialog.
     *
     * Returns the exact text an accessibility node showed, so the caller can match nodes by equality
     * instead of guessing which of several buttons is the right one.
     */
    fun confirmLabel(texts: List<String>): String? {
        val cleaned = texts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        if (cleaned.isEmpty()) return null
        val mentionsUsb = cleaned.any { text -> USB_HINTS.any { hint -> text.contains(hint) } }
        if (!mentionsUsb) return null
        // Charging has its own notification, with a "打开" that opens settings; never treat it as this.
        if (cleaned.any { it.contains("充电") || it.contains("charging", ignoreCase = true) }) return null
        return cleaned.firstOrNull { it in CONFIRM_LABELS }
    }
}
