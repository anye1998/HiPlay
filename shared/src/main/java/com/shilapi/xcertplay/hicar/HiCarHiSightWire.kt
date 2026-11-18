package com.shilapi.xcertplay.hicar

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The HiSight session that runs on the video port the phone names (`DMSDP module 5, command 101`).
 *
 * This is RTSP-shaped, not RTSP: records are `length(4, big endian) | payload`, the payload is ASCII
 * until the phone's own `ANNOUNCE` selects `aes128ctr`, and after that every record is encrypted with
 * the same AES-CTR construction the DMSDP control channel uses. Nothing here is standard RTSP
 * library material, which is why it is written out rather than delegated.
 *
 * Ported from `HiSightWire` in huducat586/hicar-android-receiver (MIT), itself adapted from
 * Aki894/CarLink `hisight.py` (MIT). The order of the handshake matters and is preserved:
 *
 * 1. this side sends `ANNOUNCE` in the clear, offering `aes128ctr`;
 * 2. the phone answers `200`, and from then on both directions are encrypted;
 * 3. the phone asks `OPTIONS`, then `GET_PARAMETER` (capabilities), then `SET_PARAMETER` with
 *    `his_presentation_url` and `his_trigger_method: SETUP`;
 * 4. this side sends `SETUP`, and the phone's `200` carries `server_port` (video) and usually
 *    `remotectl_port` (input) in the `Transport` header;
 * 5. the client dials those ports, connects the media socket, and sends `PLAY`.
 *
 * Every message is validated before it is answered. The messages that are logged are fixed
 * categories and message names — never header values or payload bytes.
 */
internal class HiCarHiSightWire(
    sessionKey: ByteArray,
    private val onLog: (String) -> Unit,
    /** True only when a surface can actually receive touches; otherwise UIBC is not offered. */
    private val withInput: Boolean,
) {
    private val cipher = HiCarRecordCipher(sessionKey, MAX_RECORD_BYTES)
    private var buffer = ByteArray(0)
    private var encrypted = false
    private var optionsSent = false
    private var failed = false
    private var teardown = false
    private var target = "*"
    private var setupSequence: String? = null
    private var sequence = 2

    /** The port the phone serves video on; 0 until the `SETUP` answer names it. */
    var mediaPort: Int = 0
        private set

    /** The port the phone accepts encrypted UIBC input on; 0 when it did not offer one. */
    var inputPort: Int = 0
        private set

    val ended: Boolean get() = teardown

    /** The opening `ANNOUNCE`, in the clear: it is the message that selects the cipher. */
    fun initial(): ByteArray = pack(
        message(
            "ANNOUNCE * RTSP/1.0",
            "1",
            "encrypt_description: encrypt_list=aes128ctr;version=1\r\n",
        ),
        forcePlain = true,
    )

    /** Sent once the media socket is connected. */
    fun play(): ByteArray {
        if (mediaPort == 0) throw HiCarWireException("rtsp media state")
        return pack(message("PLAY $target RTSP/1.0", (++sequence).toString(), "", "Transport" to "RTP/AVP/UDP;unicast;client_port=0"))
    }

    /** Best-effort teardown; null when there is nothing worth telling the phone. */
    fun stop(): ByteArray? {
        if (!encrypted || mediaPort == 0 || teardown) return null
        return pack(message("TEARDOWN $target RTSP/1.0", (++sequence).toString(), ""))
    }

    /** Consumes socket bytes and returns the records to write back, in order. */
    fun feed(data: ByteArray, count: Int = data.size): List<ByteArray> {
        if (failed || count < 0 || count > data.size || buffer.size + count > MAX_WIRE_BYTES) {
            throw HiCarWireException("rtsp buffer")
        }
        val replies = ArrayList<ByteArray>()
        try {
            buffer = join(buffer, data.copyOf(count))
            while (buffer.size >= LENGTH_BYTES) {
                var size = 0L
                for (index in 0 until LENGTH_BYTES) size = (size shl 8) or (buffer[index].toLong() and 0xFF)
                if (size < 1 || size > MAX_RECORD_BYTES) throw HiCarWireException("rtsp size")
                if (buffer.size < size + LENGTH_BYTES) break
                var payload = buffer.copyOfRange(LENGTH_BYTES, (size + LENGTH_BYTES).toInt())
                buffer = buffer.copyOfRange((size + LENGTH_BYTES).toInt(), buffer.size)
                if (encrypted) payload = cipher.decrypt(payload)
                try {
                    parse(payload, replies)
                } finally {
                    payload.fill(0)
                }
            }
            return replies
        } catch (failure: IOException) {
            failed = true
            if (failure.message?.matches(ERROR_CATEGORY) == true) {
                onLog("HiSight rejected: ${failure.message} (fixed category, no message contents)")
            }
            throw failure
        } catch (failure: RuntimeException) {
            failed = true
            throw failure
        }
    }

    fun close() {
        cipher.clear()
        buffer.fill(0)
        buffer = ByteArray(0)
        failed = true
    }

    private fun parse(payload: ByteArray, replies: MutableList<ByteArray>) {
        for (byte in payload) {
            val value = byte.toInt() and 0xFF
            if (value > 127 || value == 0) throw HiCarWireException("rtsp ascii")
        }
        val raw = String(payload, StandardCharsets.US_ASCII)
        val split = raw.indexOf("\r\n\r\n")
        val head = if (split < 0) raw.trim() else raw.substring(0, split)
        val body = if (split < 0) "" else raw.substring(split + 4)
        if (head.length > MAX_HEADER_CHARS || body.length > MAX_BODY_CHARS) throw HiCarWireException("rtsp message limit")
        val rows = head.split("\r\n")
        val start = rows.firstOrNull() ?: throw HiCarWireException("rtsp start")
        if (!START_LINE.matches(start)) throw HiCarWireException("rtsp start")
        val headers = LinkedHashMap<String, String>()
        for (row in rows.drop(1)) {
            val colon = row.indexOf(':')
            if (colon < 1) throw HiCarWireException("rtsp header")
            val name = row.substring(0, colon).lowercase(Locale.ROOT)
            if (!HEADER_NAME.matches(name)) throw HiCarWireException("rtsp header")
            if (headers.put(name, row.substring(colon + 1).trim()) != null) throw HiCarWireException("rtsp duplicate header")
            if (headers.size > MAX_HEADERS) throw HiCarWireException("rtsp duplicate header")
        }
        val seq = headers["cseq"] ?: throw HiCarWireException("rtsp sequence")
        if (!CSEQ.matches(seq)) throw HiCarWireException("rtsp sequence")
        val method = start.substringBefore(' ')
        if (method == "RTSP/1.0") {
            onLog("HiSight: response ${start.split(" ").getOrNull(1) ?: "?"} for sequence $seq")
        }
        onLog("HiSight: $method, ${body.length}-byte body")
        when {
            method == "ANNOUNCE" -> {
                // The phone must select the cipher itself; accepting anything else would leave the
                // rest of the session unreadable on its side.
                if (encrypted || !body.contains("encrypt_list=aes128ctr")) throw HiCarWireException("rtsp cipher selection")
                replies.add(pack(message("RTSP/1.0 200 OK", seq, ""), forcePlain = true))
                encrypted = true
                onLog("HiSight: RTSP encryption negotiated (aes128ctr)")
            }

            !encrypted -> {
                // The very first answer is the phone confirming our ANNOUNCE; the cipher is not on
                // yet because its reply predates the switch.
                val first = method == "RTSP/1.0" && seq == "1" && start.startsWith("RTSP/1.0 200 ")
                if (!first) throw HiCarWireException("rtsp before encryption")
                onLog("HiSight: initial ANNOUNCE acknowledged; waiting for the phone's cipher offer")
            }

            method == "OPTIONS" -> {
                replies.add(
                    pack(
                        message(
                            "RTSP/1.0 200 OK",
                            seq,
                            "",
                            "Public" to "hisight1.0, GET_PARAMETER, SET_PARAMETER",
                        ),
                    ),
                )
                if (!optionsSent) {
                    replies.add(pack(message("OPTIONS * RTSP/1.0", "2", "", "Require" to "hisight1.0")))
                    optionsSent = true
                }
            }

            method == "GET_PARAMETER" -> {
                val requested = HashSet<String>()
                for (row in body.split("\r\n")) {
                    // `GET_PARAMETER` names one capability per line with no value; the length of the
                    // answer is decided entirely by which of these lines the phone asked for.
                    val name = row.trim().substringBefore(':')
                    if (name.isNotEmpty()) requested.add(name)
                }
                val caps = LinkedHashMap<String, String>()
                caps["his_version"] = "1.0"
                caps["his_video_formats"] =
                    "codecs 1;fps 30;gop 30;bitrate 2000000;vbr-min 1000000;vbr-max 3000000;" +
                        "dpi 160;scr-w $DISPLAY_WIDTH;scr-h $DISPLAY_HEIGHT;width $DISPLAY_WIDTH;height $DISPLAY_HEIGHT"
                caps["his_device_type"] = "device_type 4;subtype 0;"
                if (withInput) {
                    caps["his_uibc_capability"] =
                        "input_category_list=GENERIC;generic_cap_list=left_point_down, " +
                            "left_point_up, left_move, left_key_down, left_key_up;"
                }
                val answer = StringBuilder()
                for ((name, value) in caps) {
                    if (requested.contains(name)) answer.append(name).append(": ").append(value).append("\r\n")
                }
                replies.add(pack(message("RTSP/1.0 200 OK", seq, answer.toString())))
            }

            method == "SET_PARAMETER" -> {
                val params = HashMap<String, String>()
                for (row in body.split("\r\n")) {
                    val colon = row.indexOf(':')
                    if (colon <= 0) continue
                    val name = row.substring(0, colon).trim().lowercase(Locale.ROOT)
                    if (params.put(name, row.substring(colon + 1).trim()) != null) {
                        throw HiCarWireException("rtsp duplicate parameter")
                    }
                }
                val url = params["his_presentation_url"]
                if (url != null) {
                    val candidate = url.split(" ").firstOrNull() ?: ""
                    if (!PRESENTATION_URL.matches(candidate)) throw HiCarWireException("rtsp URL")
                    target = candidate
                }
                replies.add(pack(message("RTSP/1.0 200 OK", seq, "")))
                when (params["his_trigger_method"]) {
                    "SETUP" -> {
                        if (setupSequence != null) throw HiCarWireException("rtsp duplicate setup")
                        val next = (++sequence).toString()
                        setupSequence = next
                        replies.add(pack(message("SETUP $target RTSP/1.0", next, "", "Transport" to "RTP/AVP/UDP;unicast;client_port=0")))
                        onLog("HiSight: SETUP sent, waiting for the phone to name the video port")
                    }

                    "TEARDOWN" -> teardown = true
                }
            }

            method == "TEARDOWN" -> {
                replies.add(pack(message("RTSP/1.0 200 OK", seq, "")))
                teardown = true
            }

            method == "RTSP/1.0" && seq == setupSequence -> {
                if (!start.startsWith("RTSP/1.0 200 ")) throw HiCarWireException("rtsp setup refused")
                val transport = headers["transport"] ?: ""
                val port = TRANSPORT_PORT.find(transport)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: throw HiCarWireException("rtsp media port")
                if (port !in 1..65535) throw HiCarWireException("rtsp media port")
                val remote = TRANSPORT_INPUT_PORT.find(transport)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (remote != null && remote !in 1..65535) throw HiCarWireException("rtsp input port")
                mediaPort = port
                inputPort = remote ?: 0
                onLog(
                    "HiSight: video port negotiated; " +
                        if (inputPort != 0) "input port also offered" else "no input port offered",
                )
            }
        }
    }

    private fun message(start: String, seq: String, body: String, vararg headers: Pair<String, String>): ByteArray {
        if (!CSEQ.matches(seq)) throw HiCarWireException("rtsp sequence")
        val out = StringBuilder(start).append("\r\nCSeq: ").append(seq).append("\r\n")
        for ((name, value) in headers) out.append(name).append(": ").append(value).append("\r\n")
        if (body.isNotEmpty()) {
            out.append("Content-Type: text/parameters\r\n")
                .append("Content-Length: ").append(body.toByteArray(StandardCharsets.US_ASCII).size)
                .append("\r\n")
        }
        out.append("\r\n").append(body)
        return out.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun pack(plain: ByteArray, forcePlain: Boolean = false): ByteArray {
        val payload = if (encrypted && !forcePlain) cipher.encrypt(plain) else plain
        if (payload.isEmpty() || payload.size > MAX_RECORD_BYTES) throw HiCarWireException("rtsp record")
        return join(
            byteArrayOf(
                ((payload.size ushr 24) and 0xFF).toByte(),
                ((payload.size ushr 16) and 0xFF).toByte(),
                ((payload.size ushr 8) and 0xFF).toByte(),
                (payload.size and 0xFF).toByte(),
            ),
            payload,
        )
    }

    private companion object {
        const val LENGTH_BYTES = 4
        const val MAX_RECORD_BYTES = 65_000
        const val MAX_WIRE_BYTES = 70_000
        const val MAX_HEADER_CHARS = 8_192
        const val MAX_BODY_CHARS = 56_000
        const val MAX_HEADERS = 64
        const val DISPLAY_WIDTH = 800
        const val DISPLAY_HEIGHT = 480

        val START_LINE = Regex("[A-Z_]+ \\S+ RTSP/1\\.0|RTSP/1\\.0 [1-5][0-9]{2}( [ -~]+)?")
        val HEADER_NAME = Regex("[a-z][a-z0-9-]{0,63}")
        val CSEQ = Regex("[0-9]{1,9}")
        val PRESENTATION_URL = Regex("rtsp://[A-Za-z0-9.:/_?=&%=-]{1,256}")
        val TRANSPORT_PORT = Regex("(?:^|;)server_port=([0-9]+)")
        val TRANSPORT_INPUT_PORT = Regex("(?:^|;)remotectl_port=([0-9]+)")
        val ERROR_CATEGORY = Regex("rtsp [a-z ]{1,40}")
    }
}
