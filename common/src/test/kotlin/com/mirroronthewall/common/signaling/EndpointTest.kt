package com.mirroronthewall.common.signaling

import com.mirroronthewall.common.MirrorConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointTest {
    private val d = MirrorConstants.DEFAULT_SIGNALING_PORT

    @Test
    fun parsesCommonForms() {
        assertEquals(Endpoint("192.168.1.20", d), Endpoint.parse("192.168.1.20"))
        assertEquals(Endpoint("192.168.1.20", 5000), Endpoint.parse(" 192.168.1.20:5000 "))
        assertEquals(Endpoint("tv.local", d), Endpoint.parse("tv.local"))
        assertEquals(Endpoint("fe80::1", 5000), Endpoint.parse("[fe80::1]:5000"))
        assertEquals(Endpoint("fe80::1", d), Endpoint.parse("[fe80::1]"))
        assertEquals(Endpoint("fe80::1", d), Endpoint.parse("fe80::1"))
        assertEquals(Endpoint("10.0.0.2", 47913), Endpoint.parse("ws://10.0.0.2:47913/signal"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Endpoint.parse(""))
        assertNull(Endpoint.parse("   "))
        assertNull(Endpoint.parse("1.2.3.4:"))
        assertNull(Endpoint.parse("1.2.3.4:99999"))
        assertNull(Endpoint.parse("1.2.3.4:abc"))
        assertNull(Endpoint.parse("[fe80::1"))
        assertNull(Endpoint.parse("a b"))
    }

    @Test
    fun buildsWebSocketUri() {
        assertEquals("ws://192.168.1.20:47913/signal", Endpoint("192.168.1.20", 47913).toUri().toString())
        assertEquals("ws://[fe80::1]:47913/signal", Endpoint("fe80::1", 47913).toUri().toString())
    }
}
