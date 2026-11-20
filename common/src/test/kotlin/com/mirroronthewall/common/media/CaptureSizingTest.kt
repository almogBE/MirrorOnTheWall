package com.mirroronthewall.common.media

import com.mirroronthewall.common.media.CaptureSizing.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CaptureSizingTest {
    private val smooth = VideoProfile.SMOOTH

    @Test
    fun landscapePhoneOn1080pTv_fitsTvWidth() {
        // 2400x1080 phone (20:9) in landscape on a 1920x1080 TV → 1920 wide.
        assertEquals(Size(1920, 864), CaptureSizing.compute(2400, 1080, 1920, 1080, smooth))
    }

    @Test
    fun portraitPhoneOn1080pTv_fitsTvHeight() {
        // Portrait 1080x2400 is shown 1080 tall on the TV: 486x1080 → aligned 480x1072.
        assertEquals(Size(480, 1072), CaptureSizing.compute(1080, 2400, 1920, 1080, smooth))
    }

    @Test
    fun portraitPhoneOn4kTv_isCappedByProfile() {
        // 4K TV would allow 972x2160, but the profile caps the long edge at 1920.
        assertEquals(Size(864, 1920), CaptureSizing.compute(1080, 2400, 3840, 2160, smooth))
    }

    @Test
    fun highResPhoneLandscapeOn4kTv_isCappedByProfile() {
        // 3200x1440 phone → long edge cap 1920 → 1920x864.
        assertEquals(Size(1920, 864), CaptureSizing.compute(3200, 1440, 3840, 2160, smooth))
    }

    @Test
    fun unknownTvSize_usesProfileCapsOnly() {
        assertEquals(Size(1920, 864), CaptureSizing.compute(2400, 1080, 0, 0, smooth))
    }

    @Test
    fun neverUpscales() {
        assertEquals(Size(1280, 720), CaptureSizing.compute(1280, 720, 3840, 2160, smooth))
    }

    @Test
    fun alwaysAlignedAndAspectRoughlyPreserved() {
        val screens = listOf(1080 to 2400, 2400 to 1080, 1440 to 3120, 1170 to 2532, 720 to 1600, 1600 to 2560)
        val tvs = listOf(1920 to 1080, 3840 to 2160, 1280 to 720, 0 to 0)
        for ((sw, sh) in screens) for ((tw, th) in tvs) {
            val s = CaptureSizing.compute(sw, sh, tw, th, smooth)
            assertEquals(0, s.width % CaptureSizing.ALIGNMENT)
            assertEquals(0, s.height % CaptureSizing.ALIGNMENT)
            assertTrue("$s within caps", maxOf(s.width, s.height) <= smooth.maxLongEdge && minOf(s.width, s.height) <= smooth.maxShortEdge)
            // Flooring each edge to the alignment loses < ALIGNMENT px per edge, so the
            // relative aspect error is bounded by ALIGNMENT / shorter edge.
            val err = abs(s.width.toDouble() / s.height - sw.toDouble() / sh) / (sw.toDouble() / sh)
            val bound = CaptureSizing.ALIGNMENT.toDouble() / minOf(s.width, s.height)
            assertTrue("aspect error $err > $bound for ${sw}x$sh on ${tw}x$th → $s", err < bound)
        }
    }
}
