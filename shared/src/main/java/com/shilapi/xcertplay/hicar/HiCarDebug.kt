package com.shilapi.xcertplay.hicar

/**
 * Debug switches read from `setprop`, for evidence that would otherwise be a guess.
 *
 * These properties are temporary by design: they do not survive a reboot, so a diagnostic build
 * cannot silently keep logging payloads on a head unit that is driven every day. Reading them goes
 * through reflection because `android.os.SystemProperties` is not part of the SDK, and a missing
 * class must read as "off" rather than throw.
 */
internal object HiCarDebug {
    fun enabled(name: String): Boolean = runCatching {
        val properties = Class.forName("android.os.SystemProperties")
        val get = properties.getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.trim() == "1"
    }.getOrDefault(false)

    /**
     * The first [limit] bytes as hex, for payloads whose shape is the question.
     *
     * Bounded on purpose: a diagnostic line must not be able to fill the log buffer that the
     * failure report depends on.
     */
    fun hex(data: ByteArray, limit: Int = 48): String {
        val count = minOf(data.size, limit)
        val text = StringBuilder(count * 3)
        for (index in 0 until count) {
            if (index > 0) text.append(' ')
            text.append(HEX[(data[index].toInt() ushr 4) and 0x0F])
            text.append(HEX[data[index].toInt() and 0x0F])
        }
        if (data.size > count) text.append(" …(${data.size} bytes)")
        return text.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
