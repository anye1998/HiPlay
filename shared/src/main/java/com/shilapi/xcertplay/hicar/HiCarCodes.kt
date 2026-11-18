package com.shilapi.xcertplay.hicar

import java.util.UUID

/**
 * Identifiers used by the HiCar receiver (car side).
 *
 * HiCar discovery does not use classic Bluetooth bonding: the phone learns about the car from a
 * BLE advertisement on [SERVICE_DISCOVERY] and then opens two insecure RFCOMM channels. No
 * `createBond` call is involved on either side of this flow.
 */
internal object HiCarCodes {
    /** 0xFDEE: service data that carries the car model and a rotating sequence number. */
    val SERVICE_DISCOVERY: UUID = UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb")

    /** 0xFE35: nearby/control channel. Framed with [HiCarFrame]; carries the Wi-Fi negotiation. */
    val SERVICE_CONTROL: UUID = UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb")

    /** 0xFE36: business channel. Carries the JSON authentication exchange. */
    val SERVICE_BUSINESS: UUID = UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb")

    const val CONTROL_SERVICE_NAME = "NearbyServerSocket"
    const val BUSINESS_SERVICE_NAME = "HiCar Business"

    /** Frame marker of the nearby wire format. */
    const val FRAME_MARKER = 0x7F

    /** DMSDP control record markers: plaintext until the crypto switch, AES-CTR afterwards. */
    const val RECORD_PLAINTEXT = 0x00
    const val RECORD_ENCRYPTED = 0xAB

    /** Negotiation constants observed on the control channel. */
    const val BUSINESS_TRANSPORT_RFCOMM = 2
    const val BUSINESS_KIND_CONTROL = 19
    const val NETWORK_CHANNEL = 3
    const val DATA_BUSINESS = 20
    const val DATA_PROTOCOL_TCP = 1

    /** The control port the phone dials on the car's access point; +1 is the data port. */
    const val DEFAULT_CONTROL_PORT = 4005

    /**
     * DMSDP module numbers.
     *
     * The phone dials [DEFAULT_CONTROL_PORT] (planning/entry), `+1` (the DMSDP entry handshake) and
     * `+2` (the control channel). Inside the control channel a `$` payload names its module in four
     * big-endian bytes; these are the two that appear before any media is negotiated.
     */
    const val DMSDP_MODULE_ENTRY = 1
    const val DMSDP_MODULE_DEVICE = 3
    const val DMSDP_MODULE_MEDIA = 5

    /** The device module always addresses the wildcard type. */
    const val DMSDP_TYPE_WILDCARD = 0xFFFF_FFFFL

    /** Device-module commands, as the phone numbers them. */
    const val DMSDP_COMMAND_DEVICE_INFO = 101
    const val DMSDP_COMMAND_SERVICE_ANNOUNCE = 102
    const val DMSDP_COMMAND_SERVICE_REQUEST = 104
}
