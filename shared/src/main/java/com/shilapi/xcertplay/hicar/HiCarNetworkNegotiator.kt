package com.shilapi.xcertplay.hicar

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * The access point the car offers to the phone: everything the phone needs to join and to reach
 * the two TCP endpoints (control and data) on the car.
 *
 * Every field is validated at construction. A wrong SSID length, a 2.4 GHz frequency or an address
 * outside the private ranges would be silently accepted by the phone and fail much later as a
 * connection timeout, so the car refuses to offer such a network at all.
 */
class HiCarNetworkOffer(
    val ssid: String,
    val bssid: String,
    val passphrase: String,
    val ownerAddress: String,
    val clientAddress: String,
    val frequencyMhz: Int,
    val controlPort: Int,
) {
    init {
        require(isText(ssid, 1, 32)) { "HiCar AP SSID" }
        require(passphrase.length in 8..63 && isText(passphrase, 8, 63)) { "HiCar AP passphrase" }
        require(bssid.isEmpty() || MAC_ADDRESS.matches(bssid)) { "HiCar AP BSSID" }
        require(!bssid.equals("02:00:00:00:00:00", true) && bssid != "00:00:00:00:00:00") { "HiCar AP BSSID" }
        require(frequencyMask(frequencyMhz) != 0) { "HiCar AP frequency" }
        require(controlPort in 1024..65535) { "HiCar AP control port" }
        val owner = ipv4(ownerAddress)
        val client = ipv4(clientAddress)
        require(owner.sliceArray(0..2).contentEquals(client.sliceArray(0..2))) { "HiCar AP subnet" }
        require(owner[3] != client[3]) { "HiCar AP host" }
        require(owner[3] in 1..254 && client[3] in 1..254) { "HiCar AP host" }
        require(owner[0] == 10 || (owner[0] == 192 && owner[1] == 168) || (owner[0] == 172 && owner[1] in 16..31)) {
            "HiCar AP must be a private network"
        }
    }

    val dataPort: Int get() = controlPort + 1

    /**
     * The credential string the phone parses after selecting this network:
     * `clientIp##ssid\nbssid\npassphrase\nfrequency\n1##version:3##controlPort##2##hicarApIp:ownerIp`.
     *
     * The carriage of a literal '#' or a control character would shift every later column, which is
     * why [isText] rejects both instead of escaping them.
     */
    fun credentials(): ByteArray =
        (clientAddress + "##" + ssid + "\n" + bssid + "\n" + passphrase + "\n" + frequencyMhz +
            "\n1##version:3##" + controlPort + "##2##hicarApIp:" + ownerAddress)
            .toByteArray(StandardCharsets.US_ASCII)

    companion object {
        private val MAC_ADDRESS = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")

        /** The 5 GHz channels the phone accepts in its frequency capability bitmap. */
        private val FREQUENCIES = intArrayOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)

        fun frequencyMask(frequencyMhz: Int): Int {
            val index = FREQUENCIES.indexOf(frequencyMhz)
            return if (index < 0) 0 else 1 shl index
        }

        private fun isText(value: String, min: Int, max: Int): Boolean {
            if (value.length !in min..max) return false
            return value.all { it.code in 32..126 && it != '#' }
        }

        private fun ipv4(value: String): IntArray {
            val parts = value.split(".")
            require(parts.size == 4) { "HiCar AP IPv4" }
            return IntArray(4) { index ->
                val part = parts[index]
                require(part == "0" || (part.first() != '0' && part.length <= 3)) { "HiCar AP IPv4" }
                part.toInt().also { require(it in 0..255) { "HiCar AP IPv4" } }
            }
        }
    }
}

/**
 * Answers the Wi-Fi negotiation the phone runs on the 0xFE35 channel after authentication.
 *
 * Order matters: the phone offers its capabilities (kind 4 tag 1), asks for the data socket
 * (tag 3), selects the network and its role (tag 7), then pulls the credentials (kind 33). Each
 * step is refused when the previous one has not completed, so a partial exchange can never leak
 * the passphrase.
 */
internal class HiCarNetworkNegotiator(
    private val offer: HiCarNetworkOffer,
    private val dataPort: () -> Int = { offer.dataPort },
) {
    var authenticated: Boolean = false

    var negotiated: Boolean = false
        private set

    var credentialsSent: Boolean = false
        private set

    private val pending = ArrayList<ByteArray>()

    fun takePending(): List<ByteArray> {
        val queued = ArrayList(pending)
        pending.clear()
        return queued
    }

    fun respond(frame: HiCarFrame): ByteArray? {
        if (!authenticated || frame.encrypted) return null
        val replies = ByteArrayOutputStream()
        for (field in HiCarTlv.fields(frame.payload)) {
            val reader = HiCarReader(field.value)
            val reply: ByteArray? = when {
                frame.kind == KIND_NETWORK && field.tag == 1 -> {
                    val channel = reader.readVarint() ?: throw HiCarWireException("network negotiation")
                    val details = HiCarTlv.fields(field.value.copyOfRange(reader.position, field.value.size))
                    if (channel != HiCarCodes.NETWORK_CHANNEL || details.size != 1 || details[0].tag != 3) {
                        throw HiCarWireException("network negotiation")
                    }
                    val capabilities = capabilities(details[0].value)
                    val accepted = capabilities[2] == 1 &&
                        capabilities[4] == 1 &&
                        ((capabilities[5] ?: 0) and HiCarNetworkOffer.frequencyMask(offer.frequencyMhz)) != 0
                    var result = join(
                        HiCarVarint.encode(HiCarCodes.NETWORK_CHANNEL),
                        HiCarVarint.encode(if (accepted) 1 else 255),
                    )
                    if (accepted) {
                        result = join(
                            result,
                            HiCarTlv.encode(3, join(byteArrayOf(1), HiCarVarint.encode(offer.frequencyMhz))),
                        )
                    }
                    negotiated = accepted
                    HiCarTlv.encode(2, result)
                }

                frame.kind == KIND_NETWORK && field.tag == 3 -> {
                    if (!negotiated) throw HiCarWireException("data endpoint not ready")
                    val port = dataPort()
                    if (port !in 1024..65535) throw HiCarWireException("data endpoint not ready")
                    val channel = reader.readVarint() ?: throw HiCarWireException("data socket request")
                    val business = reader.readVarint() ?: throw HiCarWireException("data socket request")
                    val size = reader.readVarint() ?: throw HiCarWireException("data socket request")
                    if (size > MAX_TAG_BYTES) throw HiCarWireException("data socket request")
                    reader.readBytes(size) ?: throw HiCarWireException("data socket request")
                    val protocol = reader.readVarint() ?: throw HiCarWireException("data socket request")
                    if (channel != HiCarCodes.NETWORK_CHANNEL ||
                        business != HiCarCodes.DATA_BUSINESS ||
                        protocol != HiCarCodes.DATA_PROTOCOL_TCP ||
                        reader.remaining != 0
                    ) {
                        throw HiCarWireException("data socket request")
                    }
                    HiCarTlv.encode(4, join(HiCarVarint.encode(0), HiCarVarint.encode(port), field.value))
                }

                frame.kind == KIND_NETWORK && field.tag == 7 -> {
                    val channel = reader.readVarint() ?: throw HiCarWireException("network selection")
                    val status = reader.readVarint() ?: throw HiCarWireException("network selection")
                    val selected = HiCarTlv.fields(field.value.copyOfRange(reader.position, field.value.size))
                    if (!negotiated ||
                        channel != HiCarCodes.NETWORK_CHANNEL ||
                        status != 1 ||
                        selected.size != 1 ||
                        selected[0].tag != 3
                    ) {
                        throw HiCarWireException("network selection")
                    }
                    val role = selected[0].value
                    if (role.size < 2 || role[0].toInt() != ROLE_ACCESS_POINT) throw HiCarWireException("network role")
                    val roleReader = HiCarReader(role, 1)
                    if (roleReader.readVarint() != offer.frequencyMhz || roleReader.remaining != 0) {
                        throw HiCarWireException("network frequency")
                    }
                    if (pending.size >= MAX_PENDING_COMMANDS) throw HiCarWireException("network command limit")
                    pending.add(HiCarWire.encode(HiCarFrame(KIND_NETWORK_COMMAND, 0, HiCarTlv.encode(1, ByteArray(0)))))
                    null
                }

                frame.kind == KIND_NETWORK_COMMAND && field.tag in 1..4 -> {
                    if (!negotiated) throw HiCarWireException("network command order")
                    when (field.tag) {
                        // Tag 1 carries no client address yet: the phone is told to wait.
                        1 -> HiCarTlv.encode(2, "busy".toByteArray(StandardCharsets.US_ASCII))
                        // Tags 2 and 4 carry the station MAC that has to join this access point.
                        else -> {
                            val mac = String(field.value, StandardCharsets.US_ASCII)
                            if (!CLIENT_MAC.matches(mac)) throw HiCarWireException("client address")
                            credentialsSent = true
                            HiCarTlv.encode(3, offer.credentials())
                        }
                    }
                }

                frame.kind == KIND_NETWORK_COMMAND && field.tag == 5 -> HiCarTlv.encode(6, ByteArray(0))

                else -> null
            }
            if (reply != null) replies.write(reply, 0, reply.size)
        }
        if (replies.size() == 0) return null
        return HiCarWire.encode(HiCarFrame(frame.kind, 0, replies.toByteArray()))
    }

    private fun capabilities(data: ByteArray): Map<Int, Int> {
        if (data.size > 256) throw HiCarWireException("capability limit")
        val reader = HiCarReader(data)
        val values = LinkedHashMap<Int, Int>()
        while (reader.remaining > 0) {
            val key = reader.readVarint() ?: throw HiCarWireException("capability truncated")
            val value = reader.readVarint() ?: throw HiCarWireException("capability truncated")
            if (values.put(key, value) != null || values.size > 32) throw HiCarWireException("capability fields")
        }
        return values
    }

    private companion object {
        const val KIND_NETWORK = 4
        const val KIND_NETWORK_COMMAND = 33
        const val ROLE_ACCESS_POINT = 2
        const val MAX_TAG_BYTES = 128
        const val MAX_PENDING_COMMANDS = 4
        val CLIENT_MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    }
}
