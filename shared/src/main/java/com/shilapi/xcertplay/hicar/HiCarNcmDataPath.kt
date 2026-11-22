package com.shilapi.xcertplay.hicar

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The USB side: one bulk transfer carries one whole NTB. */
internal interface NtbPort {
    /** The largest block this side will receive. */
    val receiveSize: Int

    /** Where the NDP16 and the datagram go in an outbound block. */
    val outLayout: NtbOutLayout

    /** The phone's own cap on one outbound block (dwNtbOutMaxSize). */
    val maxOutBlockLength: Int

    /** The send endpoint's maximum packet size; see [HiCarNtb16.blockLength]. */
    val zlpPacketSize: Int

    /** Reads one block into `buffer[0, receiveSize)`. >0 bytes, 0 nothing yet, <0 link error. */
    fun receive(buffer: ByteArray, timeoutMs: Int): Int

    /** Sends `buffer[0, length)`, returning true only when the whole block went out. */
    fun send(buffer: ByteArray, length: Int, timeoutMs: Int): Boolean
}

/** The tunnel side: one read or write is one IP packet. */
internal interface PacketTunnel {
    /** Reads one packet; >0 length, 0 nothing yet, <0 the tunnel is closed or was woken. */
    fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int

    fun write(buffer: ByteArray, offset: Int, length: Int): Boolean

    /** Makes the blocking [read] return a negative value soon. Safe from any thread, any number of times. */
    fun wakeup()
}

/**
 * The two threads that move packets between the USB device and the tunnel.
 *
 * - **receive**: bulk-IN block → NTB16 → Ethernet frames → [HiCarEthernetEndpoint] (ARP answered on
 *   the spot, IPv4 into the tunnel);
 * - **transmit**: IPv4 packet from the tunnel → Ethernet header → NTB16 → bulk-OUT.
 *
 * Two threads, not one, because a single one would let a slow tunnel stall the ARP replies the phone
 * needs to keep sending anything at all.
 *
 * This class owns neither [port] nor [tunnel]: [stop] waits for both threads to exit, and the caller
 * closes the resources afterwards. On older Android a USB connection is not safe to close while a
 * transfer is in flight, and a file descriptor closed early can be reused under the transfer.
 *
 * Ported from `NcmDataPath` in GGBondLab/OpenHC (GPL-3.0-only).
 */
internal class HiCarNcmDataPath(
    private val port: NtbPort,
    private val tunnel: PacketTunnel,
    private val endpoint: HiCarEthernetEndpoint,
    private val listener: Listener,
    private val timing: Timing = Timing(),
) {
    enum class LinkDown {
        /** The USB side kept failing: the device is gone or its permission was revoked. */
        USB_LINK_LOST,

        /** The tunnel closed (VPN revoked, another VPN took over). */
        TUNNEL_CLOSED,

        /** A transport thread hit an unexpected exception. */
        INTERNAL_ERROR,
    }

    /** Milestones, each reported once; they answer "how far did the cable get". */
    enum class Milestone {
        FIRST_BLOCK_FROM_PHONE,
        FIRST_ARP_REPLY,
        FIRST_PACKET_TO_TUNNEL,
        FIRST_PACKET_TO_PHONE,

        /**
         * The phone is asking for a different car address: it is usually using another car-info
         * block, e.g. the one the head unit's own projection app advertises.
         */
        ARP_FOR_OTHER_ADDRESS,
        FIRST_MALFORMED_BLOCK,
    }

    interface Listener {
        /** The data path itself found the link down (not [stop]). Called once, on a transport thread. */
        fun onLinkDown(reason: LinkDown)

        /** Called on a transport thread; must not block. */
        fun onMilestone(milestone: Milestone, detail: String) {}
    }

    /** Timings; tests shrink them. */
    class Timing(
        /** Per-read timeout; also how long [stop] can wait for the receive thread. */
        val receiveTimeoutMs: Int = 500,
        val sendTimeoutMs: Int = 1000,
        /** Pause after a read error, so a dead device does not spin. */
        val errorBackoffMs: Long = 20,
        val maxConsecutiveErrors: Int = 25,
        val joinTimeoutMs: Long = 3000,
    )

    data class Stats(
        val blocksFromPhone: Long,
        val bytesFromPhone: Long,
        val malformedBlocks: Long,
        val arpReplies: Long,
        val arpForOtherAddress: Long,
        val packetsToTunnel: Long,
        val packetsToPhone: Long,
        val bytesToPhone: Long,
        val droppedFromPhone: Long,
        val droppedToPhone: Long,
        val usbReceiveErrors: Long,
        val usbSendFailures: Long,
    )

    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)
    private val sendLock = Any()
    private val reported = AtomicInteger(0)

    private val blocksFromPhone = AtomicLong()
    private val bytesFromPhone = AtomicLong()
    private val malformedBlocks = AtomicLong()
    private val arpReplies = AtomicLong()
    private val arpForOtherAddress = AtomicLong()
    private val packetsToTunnel = AtomicLong()
    private val packetsToPhone = AtomicLong()
    private val bytesToPhone = AtomicLong()
    private val droppedFromPhone = AtomicLong()
    private val droppedToPhone = AtomicLong()
    private val usbReceiveErrors = AtomicLong()
    private val usbSendFailures = AtomicLong()

    @Volatile private var receiver: Thread? = null
    @Volatile private var transmitter: Thread? = null

    val isRunning: Boolean get() = running.get()

    /** Starts both threads. Once only. */
    fun start() {
        check(started.compareAndSet(false, true)) { "the data path starts once" }
        running.set(true)
        receiver = Thread({ receiveLoop() }, "hicar-ncm-rx").apply {
            isDaemon = true
            start()
        }
        transmitter = Thread({ transmitLoop() }, "hicar-ncm-tx").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stops and waits up to [Timing.joinTimeoutMs] for both threads. Safe to call from any thread and
     * more than once; called from a transport thread it does not wait for itself. True when both are
     * gone.
     */
    fun stop(): Boolean {
        running.set(false)
        tunnel.wakeup()
        val deadline = System.nanoTime() + timing.joinTimeoutMs * 1_000_000
        var allStopped = true
        for (thread in listOf(receiver, transmitter)) {
            if (thread == null || thread === Thread.currentThread()) continue
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs > 0) {
                try {
                    thread.join(remainingMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (thread.isAlive) allStopped = false
        }
        return allStopped
    }

    fun stats(): Stats = Stats(
        blocksFromPhone = blocksFromPhone.get(),
        bytesFromPhone = bytesFromPhone.get(),
        malformedBlocks = malformedBlocks.get(),
        arpReplies = arpReplies.get(),
        arpForOtherAddress = arpForOtherAddress.get(),
        packetsToTunnel = packetsToTunnel.get(),
        packetsToPhone = packetsToPhone.get(),
        bytesToPhone = bytesToPhone.get(),
        droppedFromPhone = droppedFromPhone.get(),
        droppedToPhone = droppedToPhone.get(),
        usbReceiveErrors = usbReceiveErrors.get(),
        usbSendFailures = usbSendFailures.get(),
    )

    // ---------------------------------------------------------------- phone → car

    private fun receiveLoop() {
        val buffer = ByteArray(port.receiveSize)
        val replyBuffer = ByteArray(HiCarNtb16.blockLength(port.outLayout, MAX_REPLY_FRAME, port.zlpPacketSize))
        val output = object : HiCarEthernetEndpoint.Output {
            override fun sendToPhone(frame: ByteArray, offset: Int, length: Int) {
                if (length > MAX_REPLY_FRAME) {
                    droppedToPhone.incrementAndGet()
                    return
                }
                System.arraycopy(frame, offset, replyBuffer, port.outLayout.datagramOffset, length)
                sendFrame(replyBuffer, length)
            }

            override fun deliverToTunnel(packet: ByteArray, offset: Int, length: Int) {
                if (tunnel.write(packet, offset, length)) {
                    if (packetsToTunnel.incrementAndGet() == 1L) {
                        milestone(Milestone.FIRST_PACKET_TO_TUNNEL, "$length bytes")
                    }
                } else {
                    droppedFromPhone.incrementAndGet()
                }
            }
        }
        val sink = HiCarNtb16.DatagramSink { frame, offset, length -> handleFrame(frame, offset, length, output) }
        var consecutiveErrors = 0
        try {
            while (running.get()) {
                val n = port.receive(buffer, timing.receiveTimeoutMs)
                if (n < 0) {
                    usbReceiveErrors.incrementAndGet()
                    if (++consecutiveErrors >= timing.maxConsecutiveErrors) {
                        linkDown(LinkDown.USB_LINK_LOST)
                        return
                    }
                    Thread.sleep(timing.errorBackoffMs)
                    continue
                }
                consecutiveErrors = 0
                if (n == 0) continue
                if (blocksFromPhone.incrementAndGet() == 1L) {
                    milestone(Milestone.FIRST_BLOCK_FROM_PHONE, "$n bytes")
                }
                bytesFromPhone.addAndGet(n.toLong())
                try {
                    HiCarNtb16.parse(buffer, 0, n, sink)
                } catch (failure: HiCarWireException) {
                    // One bad block is dropped; the thread has to stay alive, or the whole link goes
                    // silently dead.
                    if (malformedBlocks.incrementAndGet() == 1L) {
                        milestone(Milestone.FIRST_MALFORMED_BLOCK, failure.message.orEmpty())
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: RuntimeException) {
            // Never let an exception escape a transport thread: an uncaught one ends the process.
            linkDown(LinkDown.INTERNAL_ERROR)
        }
    }

    private fun handleFrame(frame: ByteArray, offset: Int, length: Int, output: HiCarEthernetEndpoint.Output) {
        when (endpoint.handleFromPhone(frame, offset, length, output)) {
            HiCarEthernetEndpoint.Inbound.ARP_REPLIED -> {
                if (arpReplies.incrementAndGet() == 1L) {
                    milestone(
                        Milestone.FIRST_ARP_REPLY,
                        endpoint.phoneAddress?.let { HiCarIpv4.format(it) }.orEmpty(),
                    )
                }
            }

            HiCarEthernetEndpoint.Inbound.ARP_FOR_OTHER_ADDRESS -> {
                if (arpForOtherAddress.incrementAndGet() == 1L) {
                    val target = frame.copyOfRange(
                        offset + HiCarEthernet.HEADER_LENGTH + 24,
                        offset + HiCarEthernet.HEADER_LENGTH + 28,
                    )
                    milestone(Milestone.ARP_FOR_OTHER_ADDRESS, HiCarIpv4.format(target))
                }
            }

            HiCarEthernetEndpoint.Inbound.DELIVERED, HiCarEthernetEndpoint.Inbound.ARP_IGNORED -> Unit

            HiCarEthernetEndpoint.Inbound.NOT_FOR_US,
            HiCarEthernetEndpoint.Inbound.UNSUPPORTED_TYPE,
            HiCarEthernetEndpoint.Inbound.MALFORMED,
            -> droppedFromPhone.incrementAndGet()
        }
    }

    // ---------------------------------------------------------------- car → phone

    private fun transmitLoop() {
        val layout = port.outLayout
        val packetOffset = layout.datagramOffset + HiCarEthernet.HEADER_LENGTH
        val buffer = ByteArray(
            HiCarNtb16.blockLength(layout, HiCarEthernet.HEADER_LENGTH + MAX_PACKET, port.zlpPacketSize),
        )
        try {
            while (running.get()) {
                val n = tunnel.read(buffer, packetOffset, MAX_PACKET)
                if (n < 0) {
                    linkDown(LinkDown.TUNNEL_CLOSED)
                    return
                }
                if (n == 0) continue
                when (endpoint.wrapForPhone(buffer, packetOffset, n)) {
                    HiCarEthernetEndpoint.Outbound.WRAPPED -> {
                        val frameLength = endpoint.frameLength(buffer, packetOffset)
                        if (sendFrame(buffer, frameLength)) {
                            bytesToPhone.addAndGet(frameLength.toLong())
                            if (packetsToPhone.incrementAndGet() == 1L) {
                                milestone(Milestone.FIRST_PACKET_TO_PHONE, "$frameLength bytes")
                            }
                        }
                    }

                    HiCarEthernetEndpoint.Outbound.NO_PHONE_YET,
                    HiCarEthernetEndpoint.Outbound.DROPPED,
                    -> droppedToPhone.incrementAndGet()
                }
            }
        } catch (failure: RuntimeException) {
            linkDown(LinkDown.INTERNAL_ERROR)
        }
    }

    /** The frame already sits at `buffer[layout.datagramOffset, …)`; frames it and sends the block. */
    private fun sendFrame(buffer: ByteArray, frameLength: Int): Boolean {
        val layout = port.outLayout
        val block = HiCarNtb16.blockLength(layout, frameLength, port.zlpPacketSize)
        if (block > port.maxOutBlockLength || block > buffer.size) {
            droppedToPhone.incrementAndGet()
            return false
        }
        // Sequence and send under one lock, so the sequence follows the actual send order.
        val sent = synchronized(sendLock) {
            HiCarNtb16.frameInPlace(buffer, layout, frameLength, sequence.getAndIncrement(), port.zlpPacketSize)
            port.send(buffer, block, timing.sendTimeoutMs)
        }
        if (!sent) usbSendFailures.incrementAndGet()
        return sent
    }

    private fun linkDown(reason: LinkDown) {
        if (running.compareAndSet(true, false)) {
            tunnel.wakeup()
            try {
                listener.onLinkDown(reason)
            } catch (failure: RuntimeException) {
                // An exception escaping a transport thread ends the process; the same rule as above.
            }
        }
    }

    private fun milestone(milestone: Milestone, detail: String) {
        val bit = 1 shl milestone.ordinal
        while (true) {
            val current = reported.get()
            if (current and bit != 0) return
            if (reported.compareAndSet(current, current or bit)) break
        }
        try {
            listener.onMilestone(milestone, detail)
        } catch (failure: RuntimeException) {
            // Logging must never break the data path.
        }
    }

    companion object {
        /** Largest packet read from the tunnel; the MTU is 1500 and the margin avoids truncation. */
        const val MAX_PACKET = 4096

        /** The receive side's own frames (ARP replies) are at most this long. */
        private const val MAX_REPLY_FRAME = HiCarEthernet.HEADER_LENGTH + HiCarArp.LENGTH
    }
}
