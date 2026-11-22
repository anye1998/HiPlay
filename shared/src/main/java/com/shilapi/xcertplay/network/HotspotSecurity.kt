package com.shilapi.xcertplay.network

import java.io.IOException

/**
 * How an access point is protected.
 *
 * This used to live in the AirPlay transport, which is where the access point was first needed and
 * where the Wi-Fi Direct control client named it. HiCar needs the same four values and nothing else
 * from that stack, so the enum lives next to the hotspot code that reads it and the protocol layer
 * could be deleted.
 */
enum class HotspotSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/** Why an access point could not be brought up, in the terms the receiver logs and retries in. */
enum class HotspotFailureCause {
    WIFI_DIRECT_CHANNEL_UNAVAILABLE,
    HOTSPOT_INTERFACE_NOT_FOUND,
    CAR_HOTSPOT_OFF,
    CAR_HOTSPOT_DETAILS_DIFFER,
    CAR_HOTSPOT_START_TIMED_OUT,
    CAR_HOTSPOT_START_FAILED,
}

/**
 * A failure the screen can act on, unlike a bare [IOException].
 *
 * It was `HotspotFailureException` in the CarPlay orchestration package; the difference it draws
 * — "the radio would not give us this channel" versus "this device cannot do it at all" — is what
 * decides whether the receiver tries the next backend or stops.
 */
open class HotspotFailureException(
    val reason: HotspotFailureCause,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
