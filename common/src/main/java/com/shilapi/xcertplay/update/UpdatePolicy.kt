package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Decides whether this installation may be offered a published update at all.
 *
 * A test build cannot consume one, for two independent reasons:
 * - It is signed with the debug key, so installing a release APK over it fails with
 *   `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the download is wasted before the failure.
 * - Its version name carries a suffix (`0.2.22-hud-test`), which SemVer orders *below* the
 *   `0.2.22` release it was cut from. The comparison is correct and the offer is still wrong.
 *
 * So the check is not merely noisy on a test build: it can only end in a prompt that cannot work.
 */
internal object UpdatePolicy {
    /** Reason to skip update checks, or null when this build may check normally. */
    fun disabledReason(context: Context): String? = disabledReason(isDebuggable(context))

    /** Testable form: the caller supplies the debuggable flag instead of a Context. */
    fun disabledReason(debuggable: Boolean): String? =
        if (debuggable) "disabled in test builds" else null

    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
