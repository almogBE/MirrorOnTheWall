package com.mirroronthewall.common.media

/**
 * Sender video settings (docs/ARCHITECTURE.md §6.3).
 *
 * Phase 2 ships only [SMOOTH]. The "Sharp" and "Low latency" profiles and the UI to
 * choose between them are added in Phase 3, after measurements.
 */
data class VideoProfile(
    val id: String,
    /** Upper bound for the longer capture edge, in pixels. */
    val maxLongEdge: Int,
    /** Upper bound for the shorter capture edge, in pixels. */
    val maxShortEdge: Int,
    val maxFps: Int,
    val minBitrateBps: Int,
    val startBitrateBps: Int,
    val maxBitrateBps: Int,
    val degradation: Degradation,
) {
    /** Maps to org.webrtc.RtpParameters.DegradationPreference. */
    enum class Degradation { MAINTAIN_FRAMERATE, BALANCED, MAINTAIN_RESOLUTION }

    init {
        require(minBitrateBps in 1..startBitrateBps && startBitrateBps <= maxBitrateBps) { "bitrates must be min <= start <= max" }
        require(maxShortEdge <= maxLongEdge) { "short edge cap exceeds long edge cap" }
        require(maxFps in 1..120)
    }

    companion object {
        /** Default profile: stable 60 fps. Under pressure, resolution and quality drop first. */
        val SMOOTH = VideoProfile(
            id = "smooth",
            maxLongEdge = 1920,
            maxShortEdge = 1080,
            maxFps = 60,
            minBitrateBps = 2_000_000,
            startBitrateBps = 8_000_000,
            maxBitrateBps = 14_000_000,
            degradation = Degradation.MAINTAIN_FRAMERATE,
        )
    }
}
