package com.mirroronthewall.common.media

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Chooses the size of the capture VirtualDisplay (= encode size).
 *
 * Capturing at the encode size, rather than at the phone's native resolution and
 * scaling later, is the cheapest way to save GPU and encoder work (ARCHITECTURE.md §6.1).
 *
 * The size is the phone's current (rotated) screen size, scaled down uniformly so
 * that:
 * 1. it fits inside the TV's display, since pixels the TV can't show are wasted. A
 *    portrait phone on a landscape 1080p TV is shown 1080 px tall, so capturing it
 *    2400 px tall would only burn bitrate.
 * 2. it stays within the profile's long and short edge caps.
 * 3. both edges are multiples of [ALIGNMENT]. Some older MediaCodec encoders
 *    misbehave otherwise. The result's aspect ratio can differ from the screen's by
 *    up to ALIGNMENT-1 px per edge.
 *
 * It never upscales.
 */
object CaptureSizing {

    const val ALIGNMENT = 16

    data class Size(val width: Int, val height: Int) {
        override fun toString() = "${width}x$height"
    }

    /**
     * @param screenWidth current screen width in pixels, already rotated (landscape → width > height)
     * @param screenHeight current screen height in pixels, already rotated
     * @param tvWidth TV display width, or 0 if unknown
     * @param tvHeight TV display height, or 0 if unknown
     */
    fun compute(
        screenWidth: Int,
        screenHeight: Int,
        tvWidth: Int,
        tvHeight: Int,
        profile: VideoProfile,
    ): Size {
        require(screenWidth > 0 && screenHeight > 0) { "invalid screen size ${screenWidth}x$screenHeight" }

        var scale = 1.0
        if (tvWidth > 0 && tvHeight > 0) {
            // TVs report their panel in landscape. Fit our frame into that box as-is
            // (a portrait phone becomes a pillarboxed column on the TV).
            val tvLong = max(tvWidth, tvHeight).toDouble()
            val tvShort = min(tvWidth, tvHeight).toDouble()
            scale = min(scale, min(tvLong / screenWidth, tvShort / screenHeight))
        }
        val long = max(screenWidth, screenHeight).toDouble()
        val short = min(screenWidth, screenHeight).toDouble()
        scale = min(scale, min(profile.maxLongEdge / long, profile.maxShortEdge / short))

        return Size(align(screenWidth * scale), align(screenHeight * scale))
    }

    private fun align(v: Double): Int = max(ALIGNMENT, floor(v / ALIGNMENT).toInt() * ALIGNMENT)
}
