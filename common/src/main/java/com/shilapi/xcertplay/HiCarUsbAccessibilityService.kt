package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shilapi.xcertplay.hicar.HiCarUsbDialog

/**
 * Presses "允许" on the system's USB permission dialog.
 *
 * Wired HiCar cannot start until the phone is allowed to be opened as a USB device, and that dialog
 * is drawn by the system with no public API — on a head unit it appears over the receiver while the
 * driver is already reaching for the cable, and after the first unplug it comes back every time.
 *
 * This is opt-in and does nothing except that one press: it is enabled by the driver in the system's
 * accessibility settings, it only reacts to a window that mentions USB **and** has a confirming
 * button ([HiCarUsbDialog]), and it never reads or stores anything else from the screen.
 *
 * A service rather than a hack: `performAction(ACTION_CLICK)` on a node is the documented way, and it
 * leaves an audit trail in Settings › Accessibility, which is what makes it acceptable to ask a driver
 * to turn on.
 */
class HiCarUsbAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val root = rootInActiveWindow ?: return
        val nodes = collect(root, MAX_NODES)
        val label = HiCarUsbDialog.confirmLabel(nodes.map { it.text }) ?: return
        val button = nodes.firstOrNull { it.text == label } ?: return
        val pressed = runCatching {
            button.info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (pressed) {
            Log.i(TAG, "accepted the USB permission prompt (button \"$label\")")
        } else {
            Log.w(TAG, "the USB prompt was recognised but its button refused the click")
        }
    }

    override fun onInterrupt() = Unit

    /**
     * The window's nodes with their visible text.
     *
     * Bounded on purpose: `rootInActiveWindow` can describe a whole launcher, and an accessibility
     * service that walks thousands of nodes on every window change is a service that gets turned off.
     * A permission dialog is a dozen nodes.
     */
    private fun collect(root: AccessibilityNodeInfo, limit: Int): List<Node> {
        val out = ArrayList<Node>(16)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < limit) {
            val node = queue.removeFirst()
            val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) out.add(Node(text, node))
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return out
    }

    private class Node(val text: String, val info: AccessibilityNodeInfo)

    private companion object {
        const val TAG = "HiCarUsb"

        /** Enough for a dialog with a title, a message and two buttons, with room to spare. */
        const val MAX_NODES = 60
    }
}
