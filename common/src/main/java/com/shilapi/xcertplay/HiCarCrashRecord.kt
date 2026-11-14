package com.shilapi.xcertplay

import android.content.Context

/**
 * Keeps the last uncaught exception so the next launch can show it.
 *
 * A bring-up screen on a head unit usually has no adb attached: a crash closes the app and takes the
 * on-screen log with it, leaving "点击 Start 就闪退" as the only evidence. The trace is written to
 * shared preferences the moment it happens and read back on the next start, which puts it in the same
 * exported report as everything else.
 */
internal object HiCarCrashRecord {
    private const val PREFERENCES = "hicar-receiver"
    private const val KEY_LAST_CRASH = "last_crash"

    /** Enough for a full stack plus a few causes; a runaway trace must not fill preferences. */
    private const val MAX_CHARS = 8_000

    fun record(context: Context, failure: Throwable, at: String) {
        val text = buildString {
            appendLine("crash at $at")
            appendLine("thread: ${Thread.currentThread().name}")
            val trace = runCatching { failure.stackTraceToString() }.getOrDefault(failure.toString())
            append(trace.take(MAX_CHARS))
        }
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CRASH, text)
                .apply()
        }
    }

    /** Returns the stored trace and clears it, so a crash is reported once, not on every launch. */
    fun take(context: Context): String? {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val stored = preferences.getString(KEY_LAST_CRASH, null) ?: return null
        runCatching { preferences.edit().remove(KEY_LAST_CRASH).apply() }
        return stored
    }
}
