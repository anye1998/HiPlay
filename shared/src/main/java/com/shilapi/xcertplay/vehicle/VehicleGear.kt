package com.shilapi.xcertplay.vehicle

/**
 * The selector position the car reports.
 *
 * It used to sit in the iAP2 transport next to `$PASCD`, where the gear was only needed to describe a
 * wheel-speed sample to a phone. The head unit's own `autoservice` read reports the same four states,
 * and the speed monitor still needs them to tell "standing still" from "in reverse".
 */
enum class VehicleGear(val letter: Char) { PARK('P'), REVERSE('R'), NEUTRAL('N'), DRIVE('D') }
