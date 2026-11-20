package com.mirroronthewall.common.signaling

import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.common.pairing.InMemoryTrustStore
import com.mirroronthewall.common.pairing.PairingAuthority
import com.mirroronthewall.common.protocol.Answer
import com.mirroronthewall.common.protocol.DisplayInfo
import com.mirroronthewall.common.protocol.Hello
import com.mirroronthewall.common.protocol.Offer
import com.mirroronthewall.common.protocol.Ping
import com.mirroronthewall.common.protocol.Pong
import com.mirroronthewall.common.protocol.RejectReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests of [SignalingServer] and [SignalingClient] over real loopback
 * sockets. This is the same code the TV and phone apps run, minus NSD and the UI.
 */
class SignalingLoopbackTest {

    private val pairing = PairingAuthority(InMemoryTrustStore())
    private val identity = SignalingServer.ReceiverIdentity("rx-1", "Test TV", DisplayInfo(1920, 1080, 60f))
    private lateinit var server: SignalingServer
    private val clients = mutableListOf<SignalingClient>()

    @Before
    fun setUp() {
        server = SignalingServer(preferredPort = 0, pairing = pairing, identity = { identity }, helloTimeoutMs = 500)
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
    }

    private fun startAndGetPort(s: SignalingServer = server): Int = runBlocking {
        s.start()
        val st = withTimeout(5_000) { s.state.first { it is SignalingServer.State.Listening || it is SignalingServer.State.Failed } }
        (st as? SignalingServer.State.Listening)?.port ?: error("server failed: $st")
    }

    private fun hello(id: String = "phone-1", code: String? = null, token: String? = null, version: Int = MirrorConstants.PROTOCOL_VERSION) =
        Hello(version, id, "Phone $id", code, token)

    private fun client() = SignalingClient().also { clients += it }

    private suspend fun <T> Flow<T>.next(): T = withTimeout(5_000) { first() }

    private suspend fun serverEvent(): SignalingServer.Event = server.events.next()

    @Test
    fun pairingThenMessagesBothWaysThenBye() = runBlocking {
        val port = startAndGetPort()
        val ep = Endpoint("127.0.0.1", port)
        val code = pairing.pairingCode.value
        val wrong = if (code == "0000") "1111" else "0000"

        val bad = client().connect(ep, hello(code = wrong))
        assertEquals(SignalingClient.ConnectResult.Rejected(RejectReason.BAD_CODE, MirrorConstants.PROTOCOL_VERSION), bad)
        val rej = serverEvent() as SignalingServer.Event.SenderRejected
        assertEquals(RejectReason.BAD_CODE, rej.reason)

        val noCode = client().connect(ep, hello())
        assertEquals(RejectReason.PAIRING_REQUIRED, (noCode as SignalingClient.ConnectResult.Rejected).reason)
        serverEvent()

        val c = client()
        val ok = c.connect(ep, hello(code = code)) as SignalingClient.ConnectResult.Connected
        assertEquals("rx-1", ok.welcome.receiverId)
        assertEquals("Test TV", ok.welcome.receiverName)
        assertEquals(1920, ok.welcome.display.widthPx)
        val started = serverEvent() as SignalingServer.Event.SessionStarted
        assertEquals("phone-1", started.peer.senderId)
        assertTrue(started.peer.newlyPaired)
        assertEquals(started.peer, server.activePeer.value)

        // Ping is answered by the server itself, not surfaced as an event.
        assertTrue(c.send(Ping(1, 42)))
        assertEquals(Pong(1, 42), (c.events.next() as SignalingClient.Event.MessageReceived).message)

        // Sender -> receiver.
        assertTrue(c.send(Offer("sdp-offer")))
        val got = serverEvent() as SignalingServer.Event.MessageReceived
        assertEquals(Offer("sdp-offer"), got.message)

        // Receiver -> sender.
        assertTrue(server.send(Answer("sdp-answer")))
        assertEquals(Answer("sdp-answer"), (c.events.next() as SignalingClient.Event.MessageReceived).message)

        // Sender ends the session.
        c.close("test done")
        val ended = serverEvent() as SignalingServer.Event.SessionEnded
        assertTrue(ended.reason, ended.reason.contains("test done"))
        withTimeout(5_000) { server.activePeer.first { it == null } }
        Unit
    }

    @Test
    fun trustTokenSkipsCode() = runBlocking {
        val ep = Endpoint("127.0.0.1", startAndGetPort())
        val c1 = client()
        val token = (c1.connect(ep, hello(code = pairing.pairingCode.value)) as SignalingClient.ConnectResult.Connected).welcome.trustToken
        serverEvent()
        c1.close()
        serverEvent()

        val c2 = client()
        val r = c2.connect(ep, hello(token = token))
        assertTrue(r is SignalingClient.ConnectResult.Connected)
        val started = serverEvent() as SignalingServer.Event.SessionStarted
        assertEquals(false, started.peer.newlyPaired)
    }

    @Test
    fun secondPhoneIsBusy_samePhoneReplacesItsOldSession() = runBlocking {
        val ep = Endpoint("127.0.0.1", startAndGetPort())
        val a = client()
        val token = (a.connect(ep, hello("A", code = pairing.pairingCode.value)) as SignalingClient.ConnectResult.Connected).welcome.trustToken
        val first = (serverEvent() as SignalingServer.Event.SessionStarted).peer

        val b = client().connect(ep, hello("B", code = pairing.pairingCode.value))
        assertEquals(RejectReason.BUSY, (b as SignalingClient.ConnectResult.Rejected).reason)
        serverEvent() // SenderRejected
        assertEquals("A", server.activePeer.value?.senderId)

        // Phone A reconnects (e.g. after a Wi-Fi blip) before the old socket timed out.
        val a2 = client()
        assertTrue(a2.connect(ep, hello("A", token = token)) is SignalingClient.ConnectResult.Connected)
        val ended = serverEvent() as SignalingServer.Event.SessionEnded
        assertEquals(first, ended.peer)
        val restarted = serverEvent() as SignalingServer.Event.SessionStarted
        assertNotEquals(first.sessionId, restarted.peer.sessionId)
        // The old client sees its socket closed.
        assertTrue(a.events.next() is SignalingClient.Event.Closed)
        // Messages from the new session still flow.
        assertTrue(a2.send(Offer("x")))
        assertEquals(Offer("x"), (serverEvent() as SignalingServer.Event.MessageReceived).message)
    }

    @Test
    fun versionMismatchIsRejected() = runBlocking {
        val ep = Endpoint("127.0.0.1", startAndGetPort())
        val r = client().connect(ep, hello(code = pairing.pairingCode.value, version = 999))
        assertEquals(RejectReason.VERSION_MISMATCH, (r as SignalingClient.ConnectResult.Rejected).reason)
    }

    @Test
    fun takenPreferredPortFallsBackToAFreePort() {
        ServerSocket(0).use { occupied ->
            val s = SignalingServer(occupied.localPort, pairing, { identity })
            try {
                val port = startAndGetPort(s)
                assertNotEquals(occupied.localPort, port)
                assertTrue(port > 0)
            } finally {
                s.close()
            }
        }
    }

    @Test
    fun silentConnectionIsClosedAfterHelloTimeout() {
        val port = startAndGetPort()
        val closed = CompletableFuture<Int>()
        val raw = object : WebSocketClient(URI("ws://127.0.0.1:$port${MirrorConstants.SIGNALING_PATH}")) {
            override fun onOpen(handshakedata: ServerHandshake) = Unit // never sends hello
            override fun onMessage(message: String) = Unit
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                closed.complete(code)
            }
            override fun onError(ex: Exception) = Unit
        }
        raw.connectBlocking(5, TimeUnit.SECONDS)
        assertEquals(SignalingServer.CLOSE_HELLO_TIMEOUT, closed.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun receiverStopSendsByeToPhone() = runBlocking {
        val ep = Endpoint("127.0.0.1", startAndGetPort())
        val c = client()
        assertTrue(c.connect(ep, hello(code = pairing.pairingCode.value)) is SignalingClient.ConnectResult.Connected)
        serverEvent()
        server.stop()
        // The Bye arrives as a message, then the close.
        val events = mutableListOf<SignalingClient.Event>()
        withTimeout(5_000) {
            c.events.collect { events += it }
        }
        val closed = events.last() as? SignalingClient.Event.Closed ?: fail("no Closed event: $events")
        assertEquals("receiver stopped", (closed as SignalingClient.Event.Closed).byeReason)
        assertEquals(SignalingServer.State.Stopped, server.state.value)
    }

    @Test
    fun connectToNothingFailsFast() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        val r = client().connect(Endpoint("127.0.0.1", freePort), hello(), timeoutMs = 3_000)
        assertTrue(r.toString(), r is SignalingClient.ConnectResult.Failed)
    }
}
