package com.shilapi.xcertplay

/**
 * Everything the exported log says about the device, collected while the activity is alive.
 *
 * It is a separate value object so the report text can be checked without an Activity, a phone or
 * a Compose runtime; the screen only fills in the fields.
 */
internal data class HiCarLogEnvironment(
    val generated: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val deviceModel: String,
    val advertisedModel: String,
    val identity: String,
    val connectionCode: String,
    val running: Boolean,
    val status: String,
)

/**
 * Renders the plain-text report the driver sends back after a failed connection attempt.
 *
 * Two deliberate choices:
 * - The six-digit connection code is included, because "the code was rejected" is one of the
 *   failures this report exists to diagnose. The file therefore says it is sensitive instead of
 *   hiding a secret in it.
 * - Lines dropped by the in-memory cap are reported, so a short log is never mistaken for a short
 *   session.
 */
internal fun hicarLogReport(
    environment: HiCarLogEnvironment,
    lines: List<String>,
    droppedLines: Int,
): String = buildString {
    appendLine("HiCar receiver bring-up log")
    appendLine("generated: ${environment.generated}")
    appendLine("app: ${environment.applicationId} ${environment.versionName} (${environment.versionCode})")
    appendLine("android: ${environment.androidRelease} (SDK ${environment.sdkInt}), ${environment.manufacturer} ${environment.deviceModel}")
    appendLine("advertised model: ${environment.advertisedModel}")
    appendLine("identity: ${environment.identity}")
    appendLine("connection code: ${environment.connectionCode.ifEmpty { "(none)" }}    <- six-digit code; treat this file as sensitive")
    appendLine("receiver: ${if (environment.running) "running" else "stopped"}")
    appendLine("status: ${environment.status}")
    appendLine("link: BLE 0xFDEE advertisement, insecure RFCOMM, channel negotiation, HiChain, DMSDP")
    if (droppedLines > 0) {
        appendLine("note: the oldest $droppedLines lines were dropped by the in-memory buffer")
    }
    appendLine()
    appendLine("--- log (${lines.size} lines, oldest first) ---")
    lines.forEach { appendLine(it) }
    appendLine("--- end of log ---")
}
