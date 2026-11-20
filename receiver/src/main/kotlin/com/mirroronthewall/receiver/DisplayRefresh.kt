package com.mirroronthewall.receiver

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlin.math.abs

/**
 * Asks the TV to run its display at a refresh rate that matches the stream (§6.4).
 * 60 fps content on a panel running at 50 Hz judders, because every fifth frame is
 * dropped.
 *
 * - API 31+: `Surface.setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)`. The
 *   system may switch the HDMI mode. The resulting brief blank happens once at
 *   session start, which is better than continuous judder.
 * - API 30: `Surface.setFrameRate(fps, FIXED_SOURCE)` (seamless switches only).
 * - API 26–29: pick a `Display.Mode` with the same resolution and a matching refresh
 *   rate through `preferredDisplayModeId`.
 */
object DisplayRefresh {
    private const val TAG = "DisplayRefresh"

    fun request(activity: Activity, surface: Surface?, fps: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (surface == null || !surface.isValid) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
                } else {
                    surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                }
                Log.i(TAG, "Surface frame rate vote: $fps")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "setFrameRate rejected: ${e.message}")
            }
        } else {
            @Suppress("DEPRECATION") // Activity.display is API 30+. This branch runs below 30.
            val display = activity.windowManager.defaultDisplay
            val current = display.mode
            if (isMultipleOf(current.refreshRate, fps)) return
            val match = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .filter { isMultipleOf(it.refreshRate, fps) }
                .minByOrNull { it.refreshRate }
                ?: return
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = match.modeId }
            Log.i(TAG, "Preferred display mode ${match.modeId}: ${match.refreshRate} Hz")
        }
    }

    /** Withdraws the request so the TV can return to its normal mode. */
    fun clear(activity: Activity, surface: Surface?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (surface == null || !surface.isValid) return
            try {
                surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (e: IllegalArgumentException) {
                // Surface already gone.
            }
        } else if (activity.window.attributes.preferredDisplayModeId != 0) {
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }

    /** 60 fps plays smoothly at 60 or 120 Hz (within 1%), not at 50 Hz. */
    private fun isMultipleOf(refresh: Float, fps: Float): Boolean {
        val ratio = refresh / fps
        val n = Math.round(ratio)
        return n >= 1 && abs(ratio - n) < 0.01f
    }
}
