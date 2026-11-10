package com.shilapi.xcertplay

import kotlin.math.abs

/** CSS `cubic-bezier(x1, y1, x2, y2)`: the curve's y where its x is the time fraction. */
internal class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    fun at(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        var low = 0f
        var high = 1f
        var s = t
        repeat(24) {
            val x = curve(s, x1, x2)
            if (abs(x - t) < 1e-5f) return curve(s, y1, y2)
            if (x < t) low = s else high = s
            s = (low + high) / 2
        }
        return curve(s, y1, y2)
    }

    private fun curve(s: Float, p1: Float, p2: Float): Float {
        val r = 1 - s
        return 3 * r * r * s * p1 + 3 * r * s * s * p2 + s * s * s
    }

    companion object {
        /** CSS `ease`. */
        val EASE = CubicBezier(0.25f, 0.1f, 0.25f, 1f)

        /** The designs' `cubic-bezier(.2, .8, .2, 1)`: fast at first, settling softly. */
        val SETTLE = CubicBezier(0.2f, 0.8f, 0.2f, 1f)
    }
}
