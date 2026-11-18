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

    /**
     * Consumes socket bytes and returns the records to write back, in order.
     *
     * A record whose contents this build does not understand is skipped, not fatal. The stream is
     * length-prefixed, so the next record is still addressable; killing the whole video session over
     * one unrecognised message is how a recoverable surprise turns into "the picture never appeared".
     * Only the three failures that destroy framing end the session: an impossible length, an
     * over-long buffer, and an ANNOUNCE that does not select the cipher (after which nothing can be
     * decrypted, so continuing would only produce garbage).
     */
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
                if (encrypted) {
                    val plain = try {
                        cipher.decrypt(payload)
                    } catch (failure: Exception) {
                        onLog("HiSight: record skipped, it could not be decrypted (${failure.javaClass.simpleName})")
                        payload.fill(0)
                        continue
                    }
                    payload.fill(0)
                    payload = plain
                }
                try {
                    parse(payload, replies)
                } catch (failure: IOException) {
                    val category = failure.message ?: ""
                    if (category in FATAL_CATEGORIES) {
                        onLog("HiSight: $category (framing lost, the session ends here)")
                        throw failure
                    }
                    // Bounded, printable protocol text only, and only for a record this side refused:
                    // without it a skipped message costs another car session to identify.
                    val text = String(payload, StandardCharsets.US_ASCII)
                    onLog(
                        "HiSight: record ignored as ${category.ifEmpty { "unreadable" }}; session continues >> " +
                            printable(text) + " <<",
                    )
                    // Skipping the record is not enough: the phone is waiting for a 200 and would end
                    // the session from its side. Anything that is recognisably a request and has a
                    // CSeq gets that answer, so a message this build merely does not understand costs
                    // one step instead of the whole picture.
                    acknowledgement(text)?.let { replies.add(it) }
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
            val line = row.trim()
            // A blank row inside the block is harmless; rejecting it only makes the parser fail on
            // formatting the phone is entitled to use.
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon < 1) {
                onLog("HiSight: header line without a name ignored (${printable(line)})")
                continue
            }
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            // Header names are lowercased and then used as keys, never validated against a strict
            // grammar: 11:11 lost a whole session because `his_heartbeat` (an underscore) did not
            // match `[a-z][a-z0-9-]*` and the parser threw instead of reading the value.
            if (!HEADER_NAME.matches(name)) {
                onLog("HiSight: header name '${printable(name)}' is outside the usual grammar; kept")
            }
            if (headers.put(name, line.substring(colon + 1).trim()) != null) {
                throw HiCarWireException("rtsp duplicate header")
            }
            if (headers.size > MAX_HEADERS) throw HiCarWireException("rtsp duplicate header")
        }
        val seq = headers["cseq"] ?: throw HiCarWireException("rtsp sequence")
        if (!CSEQ.matches(seq)) throw HiCarWireException("rtsp sequence")
        val method = start.substringBefore(' ')
        // Names only, never values: this is what tells the next run which headers the phone sends
        // without another dedicated trip.
        onLog("HiSight: $method headers=[${headers.keys.take(8).joinToString(",")}]")
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

    /**
     * A `200 OK` for a request whose contents this build could not read.
     *
     * Returns null for anything that is not a request or has no usable CSeq, in which case there is
     * nothing to acknowledge and the record is only skipped.
     */
    private fun acknowledgement(text: String): ByteArray? {
        val firstLine = text.substringBefore("\r\n").trim()
        val method = firstLine.substringBefore(' ')
        if (!REQUEST_METHOD.matches(method)) return null
        val cseq = CSEQ_LINE.find(text)?.groupValues?.getOrNull(1) ?: return null
        onLog("HiSight: answering that $method with 200 so the phone does not wait for it")
        return pack(message("RTSP/1.0 200 OK", cseq, ""))
    }

    /** Printable form of protocol text for the log: control characters escaped, length bounded. */
    private fun printable(text: String): String {
        val out = StringBuilder()
        for (character in text) {
            when {
                character == '\r' -> out.append("\\r")
                character == '\n' -> out.append("\\n")
                character.code in 32..126 -> out.append(character)
                else -> out.append('?')
            }
            if (out.length >= MAX_DUMP_CHARS) break
        }
        return out.toString()
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
        const val MAX_DUMP_CHARS = 400
        const val DISPLAY_WIDTH = 800
        const val DISPLAY_HEIGHT = 480

        val START_LINE = Regex("[A-Z_]+ \\S+ RTSP/1\\.0|RTSP/1\\.0 [1-5][0-9]{2}( [ -~]+)?")
        val HEADER_NAME = Regex("[a-z][a-z0-9._-]{0,63}")
        val CSEQ = Regex("[0-9]{1,9}")
        val REQUEST_METHOD = Regex("[A-Z]{3,20}")
        val CSEQ_LINE = Regex("\r\nCSeq:\\s*([0-9]{1,9})", RegexOption.IGNORE_CASE)

        /**
         * Any printable ASCII after the scheme.
         *
         * The reference restricts this to `[A-Za-z0-9.:/_?=&%-]`, and a URL carrying one character
         * outside that set would be refused — which costs the whole picture over a character in a
         * path. Spaces stay forbidden because this value is copied into a request line, and a space
         * there would change the request itself.
         */
        val PRESENTATION_URL = Regex("rtsp://[!-~]{1,256}")
        val TRANSPORT_PORT = Regex("(?:^|;)server_port=([0-9]+)")
        val TRANSPORT_INPUT_PORT = Regex("(?:^|;)remotectl_port=([0-9]+)")
        val ERROR_CATEGORY = Regex("rtsp [a-z ]{1,40}")

        /**
         * The only failures that are allowed to end a video session.
         *
         * Everything else is one bad record: the length prefix still frames the next one, so the
         * session continues and the record is reported instead.
         */
        val FATAL_CATEGORIES = setOf("rtsp size", "rtsp buffer", "rtsp cipher selection")
    }
}
