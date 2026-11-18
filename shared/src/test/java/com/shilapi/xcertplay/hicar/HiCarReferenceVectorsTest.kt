package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The reference receiver ships byte-level protocol vectors, generated from the Python wire
 * implementation that its own working device sessions were built on. Those vectors are the least
 * ambiguous statement of "the flow that works", so they are used here as the acceptance criteria
 * for this port rather than as documentation.
 *
 * Copied verbatim from `huducat586/hicar-android-receiver/native-auth/` (MIT, from Aki894/CarLink
 * MIT). See `reference/README.md` next to the Java reference copies for provenance.
 *
 * Three groups:
 *  - `wire-vectors.txt`: `<kind> <payload hex> <framed hex>` — every nearby frame, including CRC.
 *  - `dmsdp-vectors.txt`: `<entry request hex> <entry reply hex>` — the 4006 handshake.
 *  - `network-vectors.txt`: `<kind> <payload hex|-> <reply hex|->` — the Wi-Fi negotiation, with
 *    kind `-1` meaning "the command the negotiator queued instead of answering".
 */
class HiCarReferenceVectorsTest {

    @Test
    fun `every nearby frame matches the reference bytes and survives fragmentation`() {
        val lines = read("hicar/wire-vectors.txt")
        assertTrue("the vector file must not be empty", lines.isNotEmpty())
        var decodes = 0
        for ((index, line) in lines.withIndex()) {
            val (kindHex, payloadHex, expectedHex) = line.split(" ")
            val kind = kindHex.toInt()
            val payload = hex(payloadHex)
            val expected = hex(expectedHex)

            assertArrayEquals(
                "frame $index (kind $kind) bytes",
                expected,
                HiCarWire.encode(HiCarFrame(kind, 0, payload)),
            )

            // The phone does not respect our read boundaries. Every split point of the same frame
            // has to produce exactly one frame with the same kind and payload.
            for (split in 0..expected.size) {
                val decoder = HiCarFrameDecoder()
                val frames = decoder.feed(expected.copyOfRange(0, split), split) +
                    decoder.feed(expected.copyOfRange(split, expected.size), expected.size - split)
                assertEquals("frame $index split $split", 1, frames.size)
                assertEquals("frame $index split $split kind", kind, frames.single().kind)
                assertArrayEquals("frame $index split $split payload", payload, frames.single().payload)
                decodes++
            }

            val corrupted = expected.clone()
            corrupted[corrupted.lastIndex] = (corrupted[corrupted.lastIndex].toInt() xor 1).toByte()
            try {
                HiCarFrameDecoder().feed(corrupted, corrupted.size)
                fail("frame $index must reject a bad CRC")
            } catch (rejected: HiCarWireException) {
                // expected
            }
        }
        assertTrue("the fragmentation sweep must actually run", decodes > lines.size * 4)
    }

    @Test
    fun `the DMSDP entry handshake matches the reference bytes`() {
        val (requestHex, replyHex) = read("hicar/dmsdp-vectors.txt").single().split(" ")
        val request = hex(requestHex)
        val expected = hex(replyHex)

        for (split in 0..request.size) {
            val decoder = HiCarDmsdpDecoder()
            val packets = decoder.feed(request.copyOfRange(0, split), split) +
                decoder.feed(request.copyOfRange(split, request.size), request.size - split)
            assertEquals("split $split", 1, packets.size)
            assertArrayEquals("entry reply at split $split", expected, hiCarDmsdpEntryReply(packets.single(), 4007))
        }

        val corrupted = request.clone()
        corrupted[corrupted.lastIndex] = (corrupted[corrupted.lastIndex].toInt() xor 1).toByte()
        try {
            HiCarDmsdpDecoder().feed(corrupted, corrupted.size)
            fail("a bad DMSDP CRC must be refused")
        } catch (rejected: HiCarWireException) {
            // expected
        }
    }

    @Test
    fun `the whole Wi-Fi negotiation produces the reference bytes`() {
        val offer = HiCarNetworkOffer(
            ssid = "LAB",
            bssid = "02:11:22:33:44:66",
            passphrase = "not-a-real-password",
            ownerAddress = "192.168.49.1",
            clientAddress = "192.168.49.2",
            frequencyMhz = 5745,
            controlPort = 4005,
        )
        val negotiator = HiCarNetworkNegotiator(offer)
        // The access point must not be handed to a peer that has not authenticated.
        assertNull(negotiator.respond(HiCarFrame(4, 0, ByteArray(0))))
        negotiator.authenticated = true

        for ((index, line) in read("hicar/network-vectors.txt").withIndex()) {
            val (kindHex, payloadHex, expectedHex) = line.split(" ")
            val expected = if (expectedHex == "-") null else hex(expectedHex)
            if (kindHex == "-1") {
                val queued = negotiator.takePending()
                assertEquals("line $index queued command count", 1, queued.size)
                assertArrayEquals("line $index queued command bytes", expected, queued.single())
            } else {
                val payload = if (payloadHex == "-") ByteArray(0) else hex(payloadHex)
                val reply = negotiator.respond(HiCarFrame(kindHex.toInt(), 0, payload))
                if (expected == null) {
                    assertNull("line $index must not answer", reply)
                } else {
                    assertNotNull("line $index must answer", reply)
                    assertArrayEquals("line $index reply bytes", expected, reply)
                }
            }
        }

        assertTrue("credentials must have been handed out", negotiator.credentialsSent)
        // An encrypted frame on this channel is never answered.
        assertNull(negotiator.respond(HiCarFrame(4, 1, ByteArray(0))))
    }

    private fun read(name: String): List<String> {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing $name" }
        return stream.bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
