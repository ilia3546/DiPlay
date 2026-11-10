package com.shilapi.xcertplay

import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import kotlin.math.min

/**
 * The media block's pause and sound-off badges of design v5 over time, as the design's CSS transitions move them:
 * each badge's max-width, opacity and scale, and the gap between the badges. A new target starts each property again
 * from where it is, as CSS Transitions do: over its full duration, or a shorter one when it turns back.
 */
internal class DashboardMediaBadgeMotion {
    /** [scale] is about the badge's centre. */
    data class Badge(val maxWidth: Float, val opacity: Float, val scale: Float) {
        /** How much of a badge [natural] pixels wide shows: its box cuts it on the right at [maxWidth]. */
        fun width(natural: Float): Float = min(natural, maxWidth)
    }

    data class Frame(val paused: Badge, val muted: Badge, val gap: Float) {
        fun width(pausedNatural: Float, mutedNatural: Float): Float =
            paused.width(pausedNatural) + gap + muted.width(mutedNatural)
    }

    /**
     * One CSS transition at a time. A transition that turns back to its reversing-adjusted start value runs for its
     * duration times the reversing shortening factor of CSS Transitions.
     */
    private class Property(private val duration: Long, private val easing: CubicBezier, value: Float) {
        private var from = value
        private var to = value
        private var start = 0L
        private var length = 0f
        private var reversingStart = value
        private var shortening = 1f

        private fun progress(nowMillis: Long): Float =
            if (length <= 0f) 1f else easing.at((nowMillis - start) / length)

        fun at(nowMillis: Long): Float = from + (to - from) * progress(nowMillis)

        fun moving(nowMillis: Long): Boolean = from != to && nowMillis - start < length

        /** With [nowMillis] null the property is at [target] at once. Returns whether anything changed. */
        fun set(target: Float, nowMillis: Long?): Boolean {
            if (nowMillis == null) {
                if (from == target && to == target) return false
                from = target
                to = target
                length = 0f
                reversingStart = target
                shortening = 1f
                return true
            }
            if (target == to) return false
            if (moving(nowMillis) && target == reversingStart) {
                shortening = (progress(nowMillis) * shortening + (1 - shortening)).coerceIn(0f, 1f)
                reversingStart = to
            } else {
                shortening = 1f
                reversingStart = at(nowMillis)
            }
            from = at(nowMillis)
            to = target
            start = nowMillis
            length = duration * shortening
            return true
        }
    }

    private class BadgeMotion {
        private val maxWidth = Property(L.MEDIA_BADGE_RESIZE_MILLIS, CubicBezier.SETTLE, 0f)
        private val opacity = Property(L.MEDIA_BADGE_FADE_MILLIS, CubicBezier.EASE, 0f)
        private val scale = Property(L.MEDIA_BADGE_RESIZE_MILLIS, CubicBezier.SETTLE, L.MEDIA_BADGE_HIDDEN_SCALE)

        fun set(shown: Boolean, nowMillis: Long?): Boolean =
            listOf(
                maxWidth.set(if (shown) L.MEDIA_BADGE_MAX_WIDTH else 0f, nowMillis),
                opacity.set(if (shown) 1f else 0f, nowMillis),
                scale.set(if (shown) 1f else L.MEDIA_BADGE_HIDDEN_SCALE, nowMillis),
            ).any { it }

        fun at(nowMillis: Long) = Badge(maxWidth.at(nowMillis), opacity.at(nowMillis), scale.at(nowMillis))

        fun moving(nowMillis: Long): Boolean =
            maxWidth.moving(nowMillis) || opacity.moving(nowMillis) || scale.moving(nowMillis)
    }

    private val paused = BadgeMotion()
    private val muted = BadgeMotion()
    private val gap = Property(L.MEDIA_BADGE_RESIZE_MILLIS, CubicBezier.SETTLE, 0f)

    /** Starts moving toward the badges that [paused] and [muted] show, at [nowMillis]; false if it already does. */
    fun moveTo(paused: Boolean, muted: Boolean, nowMillis: Long): Boolean = set(paused, muted, nowMillis)

    /** Shows the badges that [paused] and [muted] show at once; false if they are already there. */
    fun jumpTo(paused: Boolean, muted: Boolean): Boolean = set(paused, muted, null)

    fun at(nowMillis: Long) = Frame(paused.at(nowMillis), muted.at(nowMillis), gap.at(nowMillis))

    fun moving(nowMillis: Long): Boolean = paused.moving(nowMillis) || muted.moving(nowMillis) || gap.moving(nowMillis)

    private fun set(paused: Boolean, muted: Boolean, nowMillis: Long?): Boolean =
        listOf(
            this.paused.set(paused, nowMillis),
            this.muted.set(muted, nowMillis),
            gap.set(if (paused && muted) L.MEDIA_BADGE_GAP else 0f, nowMillis),
        ).any { it }
}
