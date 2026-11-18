package com.shilapi.xcertplay.hicar

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The device module of the DMSDP control channel: identity first, then the service catalogue.
 *
 * The phone asks who it is talking to ([COMMAND_DEVICE_INFO]), then which services this device can
 * offer ([COMMAND_SERVICE_REQUEST], a capability mask), and only after a service is announced does it
 * hand over an endpoint port for it. Each step refuses to run out of order, because answering step
 * three without step two is how a half-configured session looks to the phone.
 *
 * Ported from `DeviceBusiness` in huducat586/hicar-android-receiver (MIT), adapted from
 * Aki894/CarLink (MIT). The differences are deliberate and marked below: nothing is announced that
 * this build cannot actually serve.
 */
internal class HiCarDeviceBusiness(
    private val deviceId: String,
    private val model: String,
    private val ownerAddress: String,
    private val clientAddress: String,
    private val onLog: (String) -> Unit,
    /** True only when a decoder and a window are ready; until then the display service stays unannounced. */
    private val displayReady: () -> Boolean = { false },
    /** True when an AAC decoder and audio focus are available. */
    private val speakerReady: () -> Boolean = { false },
    /** True when the microphone may actually record (permission granted and the driver enabled it). */
    private val microphoneReady: () -> Boolean = { false },
    /** Called with the port the phone names for the video stream, before any RTSP happens. */
    private val onMediaEndpoint: (Int) -> Unit = {},
) {
    private var peer: String? = null
    private var exchanged = false
    private var displayAnnounced = false
    private var speakerAnnounced = false
    private var microphoneAnnounced = false

    /** True after the phone has confirmed our identity, which earlier steps require. */
    val ready: Boolean get() = exchanged

    fun handle(module: Long, type: Long, body: ByteArray): List<ByteArray> {
        if (module == HiCarCodes.DMSDP_MODULE_MEDIA.toLong() && type == HiCarCodes.DMSDP_TYPE_WILDCARD) {
            return mediaEndpoint(body)
        }
        if (module != HiCarCodes.DMSDP_MODULE_DEVICE.toLong() || type != HiCarCodes.DMSDP_TYPE_WILDCARD) {
            onLog("DMSDP device module: module $module type $type ignored")
            return emptyList()
        }
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) throw HiCarWireException("device business limit")
        val reader = HiCarReader(body)
        val command = reader.readVarint() ?: throw HiCarWireException("invalid device business")
        return when (command) {
            HiCarCodes.DMSDP_COMMAND_DEVICE_INFO -> deviceInfo(body.copyOfRange(reader.position, body.size))
            HiCarCodes.DMSDP_COMMAND_SERVICE_REQUEST -> serviceRequest(body.copyOfRange(reader.position, body.size))
            else -> {
                onLog("DMSDP device command $command, ${body.size - reader.position} bytes; ignored")
                emptyList()
            }
        }
    }

    /**
     * The identity exchange: the phone sends its device id and expects ours back.
     *
     * `localIp`/`remoteIp` are the addresses the phone has just been given, and `properties["2003"]`
     * is the model id — the same one the advertisement carries. `port` stays 0: this side never
     * accepts a media connection on an arbitrary port, the phone names one later.
     */
    private fun deviceInfo(json: ByteArray): List<ByteArray> {
        val incoming = try {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(json))
                .toString()
            JSONObject(text)
        } catch (failure: JSONException) {
            throw HiCarWireException("device identity field")
        } catch (failure: CharacterCodingException) {
            throw HiCarWireException("device identity field")
        }
        val identity = incoming.optString("deviceId", "")
        if (identity.isEmpty() || identity.toByteArray(StandardCharsets.UTF_8).size > 256) {
            throw HiCarWireException("device identity field")
        }
        if (peer != null && peer != identity) throw HiCarWireException("device identity changed")
        val businessId = if (incoming.has("businessId")) incoming.optInt("businessId", -1) else 1
        if (businessId < 0) throw HiCarWireException("device business id")
        if (exchanged) {
            onLog("DMSDP device info repeated; ignoring")
            return emptyList()
        }
        val own = JSONObject()
            .put("deviceId", deviceId)
            .put("deviceName", DEVICE_NAME)
            .put("deviceType", 4)
            .put("btName", DEVICE_NAME)
            .put("localIp", ownerAddress)
            .put("remoteIp", clientAddress)
            .put("port", 0)
            .put("properties", JSONObject().put("2003", model))
            .put("businessId", businessId)
        val reply = packet(HiCarCodes.DMSDP_COMMAND_DEVICE_INFO, own)
        peer = identity
        exchanged = true
        onLog("DMSDP device info: answered with $DEVICE_NAME, model $model, ${ownerAddress} ↔ $clientAddress")
        return listOf(reply)
    }

    /**
     * The capability mask: bit 3 (value 8) is display, 4 is speaker, 2 is microphone.
     *
     * Only the display bit is interpreted for now, and only while [displayReady] is true. Announcing
     * a service this build cannot serve is worse than staying silent: the phone would open a video
     * connection that never produces a frame and report the whole car as broken.
     */
    private fun serviceRequest(mask: ByteArray): List<ByteArray> {
        if (!exchanged) throw HiCarWireException("service request order")
        if (mask.size != 4) throw HiCarWireException("service request order")
        var bits = 0L
        for (byte in mask) bits = (bits shl 8) or (byte.toLong() and 0xFF)
        val wanted = buildString {
            if (bits and MASK_DISPLAY != 0L) append(" display")
            if (bits and MASK_SPEAKER != 0L) append(" speaker")
            if (bits and MASK_MICROPHONE != 0L) append(" microphone")
        }.trim().ifEmpty { "none" }
        onLog(
            "DMSDP service request: mask 0x${bits.toString(16)} wants $wanted; " +
                "display ready=${displayReady()} announced=$displayAnnounced, " +
                "speaker ready=${speakerReady()} announced=$speakerAnnounced, " +
                "microphone ready=${microphoneReady()} announced=$microphoneAnnounced",
        )
        val replies = ArrayList<ByteArray>()
        if (bits and MASK_DISPLAY != 0L && !displayAnnounced && displayReady()) {
            replies.add(
                packet(
                    HiCarCodes.DMSDP_COMMAND_SERVICE_ANNOUNCE,
                    service(DISPLAY_SERVICE_ID, 8, JSONObject().put("4001", 1.0).put("1009", DISPLAY_WIDTH).put("1010", DISPLAY_HEIGHT)),
                ),
            )
            displayAnnounced = true
            onLog("DMSDP display service announced: ${DISPLAY_WIDTH}×$DISPLAY_HEIGHT")
        }
        if (bits and MASK_SPEAKER != 0L && !speakerAnnounced && speakerReady()) {
            replies.add(
                packet(
                    HiCarCodes.DMSDP_COMMAND_SERVICE_ANNOUNCE,
                    service(SPEAKER_SERVICE_ID, 4, JSONObject().put("2003", "48000,44100").put("2004", "1").put("2005", "12")),
                ),
            )
            speakerAnnounced = true
            onLog("DMSDP speaker service announced: AAC 48 kHz")
        }
        if (bits and MASK_MICROPHONE != 0L && !microphoneAnnounced && microphoneReady()) {
            replies.add(
                packet(
                    HiCarCodes.DMSDP_COMMAND_SERVICE_ANNOUNCE,
                    service(MICROPHONE_SERVICE_ID, 2, JSONObject().put("2003", "48000").put("2004", "1").put("2005", "12")),
                ),
            )
            microphoneAnnounced = true
            onLog("DMSDP microphone service announced: AAC 48 kHz uplink")
        }
        return replies
    }

    /** `deviceId` and `deviceName` are repeated per service; the phone matches them to the session. */
    private fun service(serviceId: String, serviceType: Int, properties: JSONObject): JSONObject = JSONObject()
        .put("deviceId", deviceId)
        .put("deviceName", DEVICE_NAME)
        .put("serviceId", serviceId)
        .put("serviceType", serviceType)
        .put("properties", properties)
        .put("status", JSONObject())

    /**
     * The phone names the port it will serve video on, then waits for this side to dial it.
     *
     * Nothing here dials yet — the RTSP/HiSight client is the next stage — but the port is reported
     * so the log proves the negotiation reached this point.
     */
    private fun mediaEndpoint(body: ByteArray): List<ByteArray> {
        val reader = HiCarReader(body)
        val command = reader.readVarint() ?: throw HiCarWireException("truncated media endpoint")
        val port = reader.readVarint() ?: throw HiCarWireException("truncated media endpoint")
        if (command != HiCarCodes.DMSDP_COMMAND_DEVICE_INFO || reader.remaining != 0) {
            throw HiCarWireException("unexpected media endpoint")
        }
        if (port !in 1..65535) throw HiCarWireException("unexpected media endpoint")
        if (!displayAnnounced) throw HiCarWireException("unexpected media endpoint")
        onLog("DMSDP media endpoint: the phone serves video on $clientAddress:$port")
        onMediaEndpoint(port)
        return emptyList()
    }

    /**
     * `$ | module 3 | type 0xFFFFFFFF | length | command | body`.
     *
     * The `$` prefix is what the control session uses to tell a binary service packet from HTTP-like
     * text; the length counts the command and body but not the eleven-byte header.
     */
    private fun packet(command: Int, json: JSONObject): ByteArray {
        val body = join(HiCarVarint.encode(command), json.toString().toByteArray(StandardCharsets.UTF_8))
        if (body.size > MAX_BODY_BYTES) throw HiCarWireException("device reply limit")
        val out = ByteArrayOutputStream(11 + body.size)
        out.write(BINARY_PREFIX.toInt())
        out.write(byteArrayOf(0, 0, 0, HiCarCodes.DMSDP_MODULE_DEVICE.toByte()))
        out.write(byteArrayOf(-1, -1, -1, -1))
        out.write(byteArrayOf(((body.size ushr 8) and 0xFF).toByte(), (body.size and 0xFF).toByte()))
        out.write(body)
        return out.toByteArray()
    }

    private companion object {
        const val DEVICE_NAME = "HiCarLab"
        const val DISPLAY_SERVICE_ID = "display1"
        const val SPEAKER_SERVICE_ID = "speaker1"
        const val MICROPHONE_SERVICE_ID = "mic1"
        const val DISPLAY_WIDTH = 800
        const val DISPLAY_HEIGHT = 480
        const val MAX_BODY_BYTES = 4096
        const val BINARY_PREFIX = '$'.code.toByte()
        const val MASK_DISPLAY = 8L
        const val MASK_MICROPHONE = 2L
        const val MASK_SPEAKER = 4L
    }
}
