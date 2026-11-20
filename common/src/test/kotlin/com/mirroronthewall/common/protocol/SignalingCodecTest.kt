package com.mirroronthewall.common.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingCodecTest {

    private val all: List<SignalingMessage> = listOf(
        Hello(1, "s-1", "Pixel", pairingCode = "0420", trustToken = null),
        Hello(1, "s-1", "Pixel", pairingCode = null, trustToken = "tok"),
        Welcome(1, "r-1", "Living room TV", "tok", DisplayInfo(3840, 2160, 60f)),
        Reject(RejectReason.BUSY, 1),
        Offer("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"),
        Answer("v=0\r\n"),
        IceCandidate("0", 0, "candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host"),
        IceCandidate(null, 1, "candidate:x"),
        IceRestartRequest,
        Bye("user"),
        Ping(7, 123_456_789L),
        Pong(7, 123_456_789L),
    )

    @Test
    fun everyMessageRoundTrips() {
        for (m in all) {
            val text = SignalingCodec.encode(m)
            assertEquals(text, m, SignalingCodec.decode(text))
        }
    }

    @Test
    fun usesTypeDiscriminatorAndOmitsNulls() {
        val text = SignalingCodec.encode(Hello(1, "s", "n", pairingCode = "1234"))
        assertTrue(text, text.contains("\"type\":\"hello\""))
        assertTrue(text, !text.contains("trustToken"))
        assertEquals("""{"type":"iceRestartRequest"}""", SignalingCodec.encode(IceRestartRequest))
    }

    @Test
    fun ignoresUnknownFieldsFromNewerPeers() {
        val text = """{"type":"bye","reason":"x","futureField":42}"""
        assertEquals(Bye("x"), SignalingCodec.decode(text))
    }

    @Test
    fun malformedOrUnknownInputDecodesToNull() {
        assertNull(SignalingCodec.decode("not json"))
        assertNull(SignalingCodec.decode("""{"type":"teleport"}"""))
        assertNull(SignalingCodec.decode("""{"reason":"no type"}"""))
        assertNull(SignalingCodec.decode("""{"type":"hello"}""")) // missing required fields
        assertNull(SignalingCodec.decode(""))
    }
}
