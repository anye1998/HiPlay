package com.shilapi.xcertplay.hicar

/**
 * The service data the car publishes on the 0xFDEE advertisement.
 *
 * The phone recognises a HiCar-capable head unit from this payload, so the four model bytes are the
 * only thing the driver can get wrong: they must be the eight hexadecimal digits of the advertised
 * model, not a free-form name. The sequence byte lets the car rotate the payload while it keeps
 * advertising, so a phone that cached a previous value still sees an advertisement changing.
 *
 * The surrounding bytes are advertisement structures the phone parses positionally. They are kept
 * as one documented literal instead of being rebuilt from a builder, because a reordering would
 * make the payload syntactically valid but unrecognisable.
 */
internal object HiCarBeacon {
    const val SERVICE_DATA_BYTES = 11

    fun serviceData(model: String, sequence: Int): ByteArray {
        require(MODEL.matches(model)) { "HiCar model must be eight hexadecimal digits" }
        require(sequence in 0..255) { "HiCar advertisement sequence" }
        val modelBytes = ByteArray(4) { index ->
            model.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return byteArrayOf(
            1, 1,
            1, 3,
            modelBytes[0], modelBytes[1], modelBytes[2],
            4, modelBytes[3],
            16,
            sequence.toByte(),
        )
    }

    /** The sequence rotates 0..255 and is persisted so a restart does not repeat a value. */
    fun nextSequence(current: Int): Int = (current + 1) and 0xFF

    private val MODEL = Regex("[0-9a-fA-F]{8}")
}
