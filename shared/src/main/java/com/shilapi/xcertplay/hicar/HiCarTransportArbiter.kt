package com.shilapi.xcertplay.hicar

/**
 * Which of the two paths a session is allowed to use.
 *
 * HiCar carries the same protocol over Wi-Fi and over the cable; the difference is only the network
 * underneath. The cable wins whenever it is carrying packets, because it is the sturdier of the two
 * and because plugging it in is an explicit statement of intent. This class holds that rule and
 * nothing else, so the decision can be tested without a phone, a radio or a USB port.
 */
internal enum class HiCarTransport {
    /** Nothing is carrying packets: the advertisement and the access point decide what happens next. */
    WIRELESS,

    /** The cable is up; the access point stays closed and any wireless session gives way. */
    WIRED,
}

/** What the owner of the session has to do when the preferred path changes. */
internal sealed class HiCarTransportAction {
    /**
     * Tear down the wireless *data* path — access point, DMSDP listeners, HiSight, audio — while
     * keeping the Bluetooth round alive: the phone still authenticates over RFCOMM before it dials.
     */
    data class DropWireless(val reason: String) : HiCarTransportAction()

    /** Bind the DMSDP endpoints on the cable's address. */
    data class StartWired(val address: String) : HiCarTransportAction()

    /** Close the endpoints bound to the cable's address. */
    data object StopWired : HiCarTransportAction()

    /** The cable is gone: the advertisement and the access point are the way in again. */
    data object ResumeWireless : HiCarTransportAction()
}

/**
 * The wired-priority rule, as a small state machine.
 *
 * Transitions:
 *
 * | event                | state before | actions                                             |
 * |----------------------|--------------|-----------------------------------------------------|
 * | cable carries packets| wireless     | drop wireless, start wired                          |
 * | cable carries packets| wired        | start wired (a new address after a re-enumeration)  |
 * | cable unplugged      | wired        | stop wired, resume wireless                         |
 * | cable unplugged      | wireless     | nothing                                             |
 *
 * Nothing here is about the *radio*: the Bluetooth listeners and the 0xFDEE advertisement are run by
 * the round and are not touched by a cable coming or going. What gives way is the Wi-Fi access point
 * and everything that runs on top of it, which is what the driver means by "无线断开".
 */
internal class HiCarTransportArbiter {

    var current: HiCarTransport = HiCarTransport.WIRELESS
        private set

    fun isWired(): Boolean = current == HiCarTransport.WIRED

    fun wiredUp(address: String): List<HiCarTransportAction> {
        val actions = ArrayList<HiCarTransportAction>(2)
        if (current != HiCarTransport.WIRED) {
            actions.add(
                HiCarTransportAction.DropWireless("the cable is carrying packets at $address"),
            )
            current = HiCarTransport.WIRED
        }
        actions.add(HiCarTransportAction.StartWired(address))
        return actions
    }

    fun wiredDown(): List<HiCarTransportAction> {
        if (current != HiCarTransport.WIRED) return emptyList()
        current = HiCarTransport.WIRELESS
        return listOf(HiCarTransportAction.StopWired, HiCarTransportAction.ResumeWireless)
    }

    /** The receiver stopped: the next run decides again from scratch. */
    fun reset() {
        current = HiCarTransport.WIRELESS
    }
}
