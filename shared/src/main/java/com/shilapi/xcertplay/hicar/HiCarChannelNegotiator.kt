package com.shilapi.xcertplay.hicar

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Answers the parameter negotiation and the RFCOMM business-channel request on the 0xFE35 channel.
 *
 * The phone first asks for the cipher version, a device summary and the framing style (kind 0);
 * only after all three answers does it ask where the business channel lives (kind 2). The car may
 * not answer with a guessed port: the reply carries the port the system actually assigned to the
 * 0xFE36 server socket, so a missing port is a hard failure instead of a fixed channel number.
 */
internal class HiCarChannelNegotiator(
    private val summary: ByteArray,
    private val businessChannel: () -> Int,
) {
    var seenMask: Int = 0
        private set

    var businessAnnounced: Boolean = false
        private set

    val complete: Boolean get() = seenMask == ALL_SEEN

    fun respond(frame: HiCarFrame): ByteArray? {
        if (frame.encrypted) throw HiCarWireException("encrypted before authentication")
        val replies = ByteArrayOutputStream()
        for (field in HiCarTlv.fields(frame.payload)) {
            val reply = when {
                frame.kind == KIND_PARAMETERS && field.tag == 1 -> {
                    val version = field.value.singleOrNull()?.toInt()?.and(0xFF)
                        ?: throw HiCarWireException("CCMP version")
                    if (version < MIN_CCMP_VERSION) throw HiCarWireException("CCMP version")
                    seenMask = seenMask or 1
                    HiCarTlv.encode(2, byteArrayOf(SUPPORTED_CCMP_VERSION.toByte()))
                }

                frame.kind == KIND_PARAMETERS && field.tag == 3 -> {
                    if (field.value.isEmpty() || field.value.size > 256) throw HiCarWireException("summary")
                    seenMask = seenMask or 2
                    HiCarTlv.encode(4, summary)
                }

                frame.kind == KIND_PARAMETERS && field.tag == 5 -> {
                    val framing = field.value.singleOrNull()?.toInt()?.and(0xFF)
                        ?: throw HiCarWireException("framing")
                    if (framing > MAX_FRAMING) throw HiCarWireException("framing")
                    seenMask = seenMask or 4
                    HiCarTlv.encode(6, byteArrayOf(FRAMING_NONE.toByte()))
                }

                frame.kind == KIND_BUSINESS && field.tag == 3 -> {
                    if (!complete) throw HiCarWireException("business channel unavailable")
                    val channel = businessChannel()
                    if (channel !in MIN_RFCOMM_CHANNEL..MAX_RFCOMM_CHANNEL) {
                        throw HiCarWireException("business channel unavailable")
                    }
                    val request = HiCarReader(field.value)
                    val transport = request.readVarint() ?: throw HiCarWireException("business tag")
                    val business = request.readVarint() ?: throw HiCarWireException("business tag")
                    val tagLength = request.readVarint() ?: throw HiCarWireException("business tag")
                    if (tagLength > MAX_BUSINESS_TAG) throw HiCarWireException("business tag")
                    request.readBytes(tagLength) ?: throw HiCarWireException("business tag")
                    val protocol = request.readVarint() ?: throw HiCarWireException("business tag")
                    if (transport != HiCarCodes.BUSINESS_TRANSPORT_RFCOMM ||
                        business != HiCarCodes.BUSINESS_KIND_CONTROL ||
                        protocol != 0 ||
                        request.remaining != 0
                    ) {
                        throw HiCarWireException("unsupported business")
                    }
                    businessAnnounced = true
                    HiCarTlv.encode(
                        4,
                        join(
                            HiCarVarint.encode(0),
                            HiCarVarint.encode(channel),
                            field.value,
                            HiCarVarint.encode(16),
                            uuidBytes(HiCarCodes.SERVICE_BUSINESS),
                        ),
                    )
                }

                else -> null
            }
            if (reply != null) replies.write(reply, 0, reply.size)
        }
        return if (replies.size() == 0) null else HiCarWire.encode(HiCarFrame(frame.kind, 0, replies.toByteArray()))
    }

    private companion object {
        const val KIND_PARAMETERS = 0
        const val KIND_BUSINESS = 2
        const val MIN_CCMP_VERSION = 2
        const val SUPPORTED_CCMP_VERSION = 2
        const val MAX_FRAMING = 2
        const val FRAMING_NONE = 0
        const val MIN_RFCOMM_CHANNEL = 1
        const val MAX_RFCOMM_CHANNEL = 30
        const val MAX_BUSINESS_TAG = 128
        const val ALL_SEEN = 7
    }
}

/** 16-byte big-endian form of a Bluetooth base UUID, as the business-channel reply carries it. */
internal fun uuidBytes(uuid: UUID): ByteArray {
    val buffer = ByteBuffer.allocate(16)
    buffer.putLong(uuid.mostSignificantBits)
    buffer.putLong(uuid.leastSignificantBits)
    return buffer.array()
}
