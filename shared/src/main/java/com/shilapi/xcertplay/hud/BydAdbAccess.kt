package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Whether the local ADB channel a BYD-specific read needs is usable.
 *
 * The vehicle probe is one of the few paths that talks to the head unit's own `autoservice` binder, and
 * on a BYD that requires ADB over the loopback interface. This is the handshake only: it says whether
 * the link is there, not what the car answered.
 *
 * The dashboard/battery half of this class belonged to CarPlay's status card and is gone with it.
 */
object BydAdbAccess {
    enum class State { READY, NOT_APPROVED, ADB_OFF, PAIRING_ONLY }

    /** ADB handshake only; does not read any BYD service or offer feature conclusions. */
    fun checkState(context: Context, mayAsk: Boolean): State =
        LocalAdb(AdbKeys.load(context)).use { state(it.connect(mayAsk)) }

    internal fun state(access: LocalAdb.Access): State = when (access) {
        LocalAdb.Access.READY -> State.READY
        LocalAdb.Access.NOT_APPROVED -> State.NOT_APPROVED
        LocalAdb.Access.UNREACHABLE -> State.ADB_OFF
        LocalAdb.Access.UNSUPPORTED -> State.PAIRING_ONLY
    }
}
