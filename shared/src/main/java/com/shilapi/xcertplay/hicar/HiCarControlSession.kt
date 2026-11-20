package com.shilapi.xcertplay.hicar

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The DMSDP control channel that runs on the FE36 socket after authentication.
 *
 * Records are `marker | length(2, big endian) | payload`. The channel starts in plaintext with an
 * `OPTIONS * DMSDP/1.0` greeting and switches to AES-CTR only when the phone sends
 * `SET_PARAMETER` with `CryptoSwitch: 222`. After that switch a plaintext record is a protocol
 * violation, not something to be lenient about: accepting it would let a peer downgrade the
 * channel at will.
 *
 * Payloads beginning with `$` are binary business frames (module, type, body); everything else is
 * HTTP-like text. Only fixed categories are reported on violation, never payload bytes.
 */
internal class HiCarControlSession(
    private val cipher: HiCarRecordCipher,
    private val events: Events = Events,
    /** Handles `serviceid`-tagged text (speaker, microphone); null means "not offered". */
    private val textHandler: TextHandler? = null,
) {
    /** A service-specific text exchange (RTSP-shaped) that runs before the generic handlers. */
    interface TextHandler {
        /** Returns the replies, or null when the service id is not this handler's. */
        fun receive(startLine: String, headers: Map<String, String>, body: ByteArray): List<ByteArray>?
    }

    interface Events {
        fun onLog(message: String) {}

        fun onNegotiated() {}

        /** The phone's clock sample, forwarded to whoever answers its time server queries. */
        fun onClock(high: Long, low: Long) {}

        /** [body] is only valid for the duration of the call; the session clears it afterwards. */
        fun onApplicationService(module: Long, type: Long, body: ByteArray): List<ByteArray> = emptyList()

        companion object : Events
    }

    var negotiated: Boolean = false
        private set

    private var wire = ByteArray(0)
    private var text = ByteArray(0)
    private var failed = false
    private var sequence = 1

    /** The opening greeting; it must be the first record on a fresh channel. */
    fun initial(): ByteArray = pack(
        marker = HiCarCodes.RECORD_PLAINTEXT,
        payload = encode(
            "OPTIONS * DMSDP/1.0",
            "CryptoVer" to "1",
            "CryptoAbility" to "222",
            "CryptoAlg" to "aes128-ctr",
            "UdpKA" to "0",
            "LowDelay" to "0",
            "CSeq" to "1",
        ),
    )

    fun heartbeat(): ByteArray? {
        if (!negotiated || failed) return null
        val request = encode("GET_PARAMETER * DMSDP/1.0", "CSeq" to (++sequence).toString())
        return pack(HiCarCodes.RECORD_ENCRYPTED, cipher.encrypt(request))
    }

    /** Consumes raw socket bytes and returns the records that must be written back, in order. */
    fun feed(chunk: ByteArray, count: Int = chunk.size): List<ByteArray> {
        if (failed || count < 0 || count > chunk.size || wire.size + count > MAX_WIRE_BYTES) {
            throw HiCarWireException("control input limit")
        }
        val replies = ArrayList<ByteArray>()
        try {
            wire = join(wire, chunk.copyOf(count))
            while (wire.size >= RECORD_HEADER_BYTES) {
                val marker = wire[0].toInt() and 0xFF
                val size = ((wire[1].toInt() and 0xFF) shl 8) or (wire[2].toInt() and 0xFF)
                if ((marker != HiCarCodes.RECORD_PLAINTEXT && marker != HiCarCodes.RECORD_ENCRYPTED) ||
                    size < 1 || size >= MAX_RECORD_BYTES
                ) {
                    // The header is framing, not payload: printing it is what separates "the phone
                    // speaks another record format" from "we mis-parsed one byte". Without this the
                    // only evidence was the words "control header".
                    events.onLog(
                        "DMSDP: unexpected record header marker=0x" +
                            String.format(Locale.ROOT, "%02x", marker) + " size=$size, first bytes=" +
                            wire.take(24).joinToString(" ") { String.format(Locale.ROOT, "%02x", it) },
                    )
                    throw HiCarWireException("control header")
                }
                if (wire.size < size + RECORD_HEADER_BYTES) break
                var payload = wire.copyOfRange(RECORD_HEADER_BYTES, size + RECORD_HEADER_BYTES)
                wire = wire.copyOfRange(size + RECORD_HEADER_BYTES, wire.size)
                payload = when {
                    marker == HiCarCodes.RECORD_ENCRYPTED -> {
                        if (!negotiated) throw HiCarWireException("early encryption")
                        cipher.decrypt(payload)
                    }

                    negotiated -> throw HiCarWireException("plaintext after encryption")
                    else -> payload
                }
                if (payload.isNotEmpty() && payload[0] == BINARY_PREFIX) {
                    handleBinary(payload, replies)
                } else {
                    text = join(text, payload)
                    if (text.size > MAX_TEXT_BYTES) throw HiCarWireException("message limit")
                    parseText(replies)
                }
            }
            return replies
        } catch (failure: IOException) {
            failed = true
            events.onLog("DMSDP rejected: ${failure.message ?: failure.javaClass.simpleName}")
            throw failure
        } finally {
            // Payload buffers hold session plaintext; do not leave them for a later reader.
            if (failed) {
                wire.fill(0)
                text.fill(0)
                wire = ByteArray(0)
                text = ByteArray(0)
            }
        }
    }

    fun close() {
        failed = true
        wire.fill(0)
        text.fill(0)
        wire = ByteArray(0)
        text = ByteArray(0)
        cipher.clear()
    }

    private fun handleBinary(payload: ByteArray, replies: MutableList<ByteArray>) {
        if (!negotiated || text.isNotEmpty() || payload.size < BINARY_HEADER_BYTES) {
            throw HiCarWireException("binary order")
        }
        var module = 0L
        for (index in 1..4) module = (module shl 8) or (payload[index].toLong() and 0xFF)
        var type = 0L
        for (index in 5..8) type = (type shl 8) or (payload[index].toLong() and 0xFF)
        val length = ((payload[9].toInt() and 0xFF) shl 8) or (payload[10].toInt() and 0xFF)
        if (length != payload.size - BINARY_HEADER_BYTES) throw HiCarWireException("binary size")
        val body = payload.copyOfRange(BINARY_HEADER_BYTES, payload.size)
        events.onLog("DMSDP application frame: module $module, type $type, ${body.size} bytes")
        // Payloads are shown only when a test explicitly asks for them: the point is to find out
        // whether the phone signals an audio-purpose change at all, and that cannot be answered from
        // a byte count.
        if (HiCarDebug.enabled("debug.hicar.frames")) {
            events.onLog("DMSDP application frame body: module $module type $type ${HiCarDebug.hex(body)}")
        }
        val answers = try {
            events.onApplicationService(module, type, body)
        } finally {
            body.fill(0)
        }
        for (answer in answers) replies.add(pack(HiCarCodes.RECORD_ENCRYPTED, cipher.encrypt(answer)))
    }

    private fun parseText(replies: MutableList<ByteArray>) {
        while (text.isNotEmpty()) {
            val end = indexOfHeaderEnd(text)
            if (end < 0) {
                if (text.size > MAX_HEADER_BYTES) throw HiCarWireException("header limit")
                return
            }
            if (end > MAX_HEADER_BYTES) throw HiCarWireException("header limit")
            for (index in 0 until end) {
                val byte = text[index].toInt() and 0xFF
                if (byte > 127 || byte == 0) throw HiCarWireException("non-ascii header")
            }
            val rows = String(text, 0, end, StandardCharsets.US_ASCII).split("\r\n")
            val startLine = rows.firstOrNull() ?: throw HiCarWireException("start line")
            if (!START_LINE.matches(startLine)) throw HiCarWireException("start line")
            val headers = LinkedHashMap<String, String>()
            for (row in rows.drop(1)) {
                val colon = row.indexOf(':')
                if (colon < 1) throw HiCarWireException("header")
                val name = row.substring(0, colon)
                val value = row.substring(colon + 1).trim()
                if (!HEADER_NAME.matches(name)) throw HiCarWireException("header")
                if (headers.put(name.lowercase(Locale.ROOT), value) != null) throw HiCarWireException("duplicate header")
                if (headers.size > MAX_HEADERS) throw HiCarWireException("header limit")
            }
            val bodyLength = headers["content-length"] ?: "0"
            if (!BODY_LENGTH.matches(bodyLength)) throw HiCarWireException("body length")
            val size = bodyLength.toInt()
            if (size > MAX_BODY_BYTES) throw HiCarWireException("body limit")
            if (text.size < end + HEADER_TERMINATOR_BYTES + size) return
            val body = text.copyOfRange(end + HEADER_TERMINATOR_BYTES, end + HEADER_TERMINATOR_BYTES + size)
            text = text.copyOfRange(end + HEADER_TERMINATOR_BYTES + size, text.size)
            val method = startLine.split(" ", limit = 2).first()
            events.onLog("DMSDP control message: $method, ${body.size} bytes")
            val service = if (headers.containsKey("serviceid") && textHandler != null) {
                if (!negotiated) throw HiCarWireException("service before encryption")
                try {
                    textHandler.receive(startLine, headers, body)
                } finally {
                    body.fill(0)
                }
            } else {
                null
            }
            if (service != null) {
                for (answer in service) replies.add(pack(HiCarCodes.RECORD_ENCRYPTED, cipher.encrypt(answer)))
                continue
            }
            handleText(startLine, headers, body, replies)
            body.fill(0)
        }
    }

    private fun handleText(startLine: String, headers: Map<String, String>, body: ByteArray, replies: MutableList<ByteArray>) {
        val cseq = headers["cseq"]
        when {
            startLine == SET_PARAMETER && headers.containsKey("cryptoswitch") -> {
                if (negotiated ||
                    body.isNotEmpty() ||
                    headers["cryptover"] != "1" ||
                    headers["cryptoswitch"] != "222" ||
                    headers["cryptoalg"] != "aes128-ctr" ||
                    cseq == null || !CSEQ.matches(cseq)
                ) {
                    throw HiCarWireException("crypto negotiation")
                }
                replies.add(
                    pack(
                        HiCarCodes.RECORD_PLAINTEXT,
                        encode(
                            "DMSDP/1.0 200 OK",
                            "CSeq" to cseq,
                            "CryptoVer" to "1",
                            "CryptoSwitch" to "222",
                            "CryptoAlg" to "aes128-ctr",
                            "Platform" to "0",
                        ),
                    ),
                )
                negotiated = true
                events.onLog("DMSDP AES-CTR negotiated")
                events.onNegotiated()
            }

            startLine == GET_PARAMETER && negotiated && body.isEmpty() && headers.size == 1 &&
                cseq != null && CSEQ.matches(cseq) -> {
                events.onLog("DMSDP encrypted heartbeat")
            }

            startLine == SET_PARAMETER && negotiated && headers.containsKey("timeserverport") &&
                headers.containsKey("timeushigh") && headers.containsKey("timeuslow") -> {
                if (cseq == null || !CSEQ.matches(cseq)) throw HiCarWireException("clock sequence")
                val port = headers.getValue("timeserverport")
                if (!PORT.matches(port) || port.toInt() !in 1..65535) throw HiCarWireException("clock port")
                val high = headers.getValue("timeushigh")
                val low = headers.getValue("timeuslow")
                for (value in listOf(high, low)) {
                    if (!CLOCK_VALUE.matches(value) || value.toLong() !in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL) {
                        throw HiCarWireException("clock value")
                    }
                }
                replies.add(pack(HiCarCodes.RECORD_ENCRYPTED, cipher.encrypt(encode("DMSDP/1.0 200 OK", "CSeq" to cseq))))
                events.onClock(high.toLong(), low.toLong())
            }

            else -> Unit
        }
    }

    private fun pack(marker: Int, payload: ByteArray): ByteArray {
        if (payload.isEmpty() || payload.size >= MAX_RECORD_BYTES) throw HiCarWireException("control record")
        return join(
            byteArrayOf(marker.toByte(), ((payload.size ushr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte()),
            payload,
        )
    }

    private fun encode(startLine: String, vararg headers: Pair<String, String>): ByteArray =
        encodeMessage(startLine, headers.toList())

    private fun indexOfHeaderEnd(data: ByteArray): Int {
        for (index in 0..data.size - HEADER_TERMINATOR_BYTES) {
            if (data[index] == 13.toByte() &&
                data[index + 1] == 10.toByte() &&
                data[index + 2] == 13.toByte() &&
                data[index + 3] == 10.toByte()
            ) {
                return index
            }
        }
        return -1
    }

    companion object {
        /** `start line | header: value… | blank line`, ASCII only; also used by the service handlers. */
        fun encodeMessage(startLine: String, headers: List<Pair<String, String>>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(startLine.toByteArray(StandardCharsets.US_ASCII))
            out.write(CRLF)
            for ((name, value) in headers) {
                out.write("$name: $value".toByteArray(StandardCharsets.US_ASCII))
                out.write(CRLF)
            }
            out.write(CRLF)
            return out.toByteArray()
        }

        private const val RECORD_HEADER_BYTES = 3
        private const val MAX_RECORD_BYTES = 1498
        private const val MAX_WIRE_BYTES = 65_536
        private const val MAX_TEXT_BYTES = 73_732
        private const val MAX_HEADER_BYTES = 8_192
        private const val MAX_HEADERS = 64
        private const val MAX_BODY_BYTES = 65_536
        private const val HEADER_TERMINATOR_BYTES = 4
        private const val BINARY_HEADER_BYTES = 11
        private const val BINARY_PREFIX = '$'.code.toByte()
        private const val SET_PARAMETER = "SET_PARAMETER * DMSDP/1.0"
        private const val GET_PARAMETER = "GET_PARAMETER * DMSDP/1.0"
        val CRLF = byteArrayOf(13.toByte(), 10.toByte())
        val START_LINE = Regex("[A-Z_]+ \\S+ DMSDP/1\\.0|DMSDP/1\\.0 [1-5][0-9]{2}( [ -~]+)?")
        val HEADER_NAME = Regex("[A-Za-z][A-Za-z0-9-]{0,63}")
        val BODY_LENGTH = Regex("[0-9]{1,5}")
        val CSEQ = Regex("[0-9]{1,9}")
        val PORT = Regex("[0-9]{1,5}")
        val CLOCK_VALUE = Regex("-?[0-9]{1,10}")
    }
}
