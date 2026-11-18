package com.mirroronthewall.common

import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorConstantsTest {

    @Test
    fun serviceTypeIsValidDnsSdTcpType() {
        // RFC 6763 §7: service name is 1-15 chars of letters, digits and hyphens,
        // prefixed with '_', followed by the transport label.
        val regex = Regex("^_[a-z0-9]([a-z0-9-]{0,13}[a-z0-9])?\\._tcp$")
        assertTrue(MirrorConstants.NSD_SERVICE_TYPE, regex.matches(MirrorConstants.NSD_SERVICE_TYPE))
    }

    @Test
    fun defaultPortIsUnprivilegedAndInRange() {
        assertTrue(MirrorConstants.DEFAULT_SIGNALING_PORT in 1024..65535)
    }

    @Test
    fun txtKeysAreShortAndUnique() {
        val keys = listOf(
            MirrorConstants.Txt.PROTOCOL_VERSION,
            MirrorConstants.Txt.RECEIVER_ID,
            MirrorConstants.Txt.DISPLAY_NAME,
        )
        assertTrue(keys.toSet().size == keys.size)
        // NsdServiceInfo.setAttribute limits key+value to 255 bytes; keep keys tiny.
        assertTrue(keys.all { it.isNotEmpty() && it.length <= 9 })
    }
}
