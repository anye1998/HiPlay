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

    /** A numeric property, for a value that differs between head units rather than being a switch. */
    fun number(name: String, fallback: Int): Int = runCatching {
        val properties = Class.forName("android.os.SystemProperties")
        val get = properties.getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.trim()?.toIntOrNull() ?: fallback
    }.getOrDefault(fallback)

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

    /**
     * A frame body as text when it is printable JSON, as hex otherwise.
     *
     * The phone's device-info frame is a pretty-printed JSON document of several hundred bytes, and
     * what it says about itself - versions, capabilities - is exactly what a filtered or truncated
     * view cannot show. Keeping it to one line and to [JSON_LIMIT] bytes keeps the log buffer sane.
     */
    fun body(data: ByteArray, limit: Int = JSON_LIMIT): String {
        val start = data.indexOfFirst { it == '{'.code.toByte() || it == '['.code.toByte() }
        if (start < 0 || data.size - start > limit) return hex(data)
        for (index in start until data.size) {
            val byte = data[index].toInt() and 0xFF
            if (byte != 9 && byte != 10 && byte != 13 && (byte < 32 || byte > 126)) return hex(data)
        }
        val text = StringBuilder(data.size - start)
        for (index in start until data.size) {
            val byte = data[index].toInt() and 0xFF
            text.append(if (byte == 9 || byte == 10 || byte == 13) ' ' else byte.toChar())
        }
        return text.toString().replace(WHITESPACE, " ")
    }

    private val WHITESPACE = Regex("\\s+")
    private val HEX = "0123456789abcdef".toCharArray()
    private const val JSON_LIMIT = 2048
}
