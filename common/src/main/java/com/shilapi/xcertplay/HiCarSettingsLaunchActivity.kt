package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The action [HiCarSettingsLaunchActivity] hands to the host.
 *
 * A top-level constant rather than one on the activity's companion, which is private: the host only
 * needs to recognise the action, not the door that carried it.
 */
internal const val ACTION_SETTINGS = "com.anye1998.hiplay.action.HICAR_SETTINGS"

/**
 * The `HiCar 设置` launcher icon: a doorway, not a screen.
 *
 * The settings page itself is drawn by [HiCarHostActivity] as an overlay, because a second real
 * activity in front of a running session pauses the host and the mirrored picture goes black. This
 * class therefore does nothing but hand the launch on and finish, which is over in the first frame.
 *
 * It exists because an `activity-alias` is not enough: the APK declared two launcher aliases and the
 * head unit's launcher listed only the first — both aliases resolve to the same target activity, and
 * a launcher that keys its icon list by package (or by target) shows one. A real activity is an entry
 * of its own, whatever the launcher does with aliases. See docs/HICAR_UI.md §7.
 *
 * `Theme.NoDisplay` is the point: the window never draws, so no black frame and no visible flicker on
 * the way to the host. Android requires such an activity to finish before it resumes, which is
 * exactly what happens here.
 */
class HiCarSettingsLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = Intent(this, HiCarHostActivity::class.java)
            .setAction(ACTION_SETTINGS)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        // A launcher on a ROM that refuses the hand-off should not leave the driver on a blank
        // screen: finishing either way returns them to the launcher, which is recoverable.
        runCatching { startActivity(host) }
        finish()
    }
}
