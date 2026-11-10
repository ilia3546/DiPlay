package com.shilapi.xcertplay

import com.shilapi.xcertplay.MonjaroDashboardLayout as L

/**
 * The custom dashboard's start-up of design v4, as a function of the time since its window appeared: the Geely
 * logo, then Geely beside CarPlay, then the wallpaper, the map and the side blocks. The clock and the temperature
 * stay out of it. Each step is the design's CSS transition, with its duration, delay and easing.
 */
internal object DashboardBoot {
    /** QNX switches the cluster to the dashboard window a moment after it appears, so the logo waits this long. */
    const val START_DELAY_MILLIS = 350L

    /** [geelyLeft] and [carPlayLeft] are the logos' left edges, in pixels right of the logo centre. */
    data class Frame(
        val wallpaperAlpha: Float,
        val geelyAlpha: Float,
        val geelyLeft: Float,
        val dividerAlpha: Float,
        val dividerScale: Float,
        val carPlayAlpha: Float,
        val carPlayLeft: Float,
        val mapAlpha: Float,
        val mapScale: Float,
        val leftAlpha: Float,
        val leftShift: Float,
        val rightAlpha: Float,
        val rightShift: Float,
    ) {
        val logoShown: Boolean get() = geelyAlpha > 0f || dividerAlpha > 0f || carPlayAlpha > 0f
    }

    private class Transition(val start: Long, val duration: Long, val easing: CubicBezier) {
        val end: Long get() = start + duration

        fun at(millis: Long): Float = easing.at(((millis - start).toFloat() / duration).coerceIn(0f, 1f))
    }

    private val EASE = CubicBezier.EASE
    private val SLIDE = CubicBezier(0.65f, 0f, 0.35f, 1f)
    private val SETTLE = CubicBezier.SETTLE

    private fun step(after: Long, duration: Long, easing: CubicBezier) =
        Transition(START_DELAY_MILLIS + after, duration, easing)

    private const val LOGO = 0L
    private const val LOCKUP = 1_000L
    private const val WALLPAPER = 2_550L
    private const val MAP = 2_950L
    private const val SIDES = 3_350L
    private const val RIGHT_DELAY = 150L

    private val geelyIn = step(LOGO, 700, EASE)
    private val geelySlide = step(LOCKUP, 900, SLIDE)
    private val geelyOut = step(WALLPAPER, 700, EASE)
    private val dividerIn = step(LOCKUP, 500, EASE)
    private val dividerGrow = step(LOCKUP + 150, 700, SLIDE)
    private val dividerOut = step(WALLPAPER, 500, EASE)
    private val carPlaySlide = step(LOCKUP + 200, 800, SETTLE)
    private val carPlayIn = step(LOCKUP + 250, 600, EASE)
    private val carPlayOut = step(WALLPAPER + 250, 600, EASE)
    private val wallpaper = step(WALLPAPER, 1_200, EASE)
    private val mapIn = step(MAP, 700, EASE)
    private val mapGrow = step(MAP, 1_000, SETTLE)
    private val leftIn = step(SIDES, 800, EASE)
    private val leftSlide = step(SIDES, 1_000, SETTLE)
    private val rightIn = step(SIDES + RIGHT_DELAY, 800, EASE)
    private val rightSlide = step(SIDES + RIGHT_DELAY, 1_000, SETTLE)

    val DURATION_MILLIS: Long = listOf(geelyIn, geelySlide, geelyOut, dividerIn, dividerGrow, dividerOut, carPlaySlide,
        carPlayIn, carPlayOut, wallpaper, mapIn, mapGrow, leftIn, leftSlide, rightIn, rightSlide).maxOf { it.end }

    private const val GEELY_CENTRED = -L.BOOT_GEELY_WIDTH / 2f
    private const val GEELY_BESIDE = -(L.BOOT_GEELY_WIDTH + L.BOOT_GEELY_GAP).toFloat()

    fun at(millis: Long): Frame = Frame(
        wallpaperAlpha = wallpaper.at(millis),
        geelyAlpha = geelyIn.at(millis) * (1 - geelyOut.at(millis)),
        geelyLeft = between(GEELY_CENTRED, GEELY_BESIDE, geelySlide.at(millis)),
        dividerAlpha = dividerIn.at(millis) * (1 - dividerOut.at(millis)),
        dividerScale = dividerGrow.at(millis),
        carPlayAlpha = carPlayIn.at(millis) * (1 - carPlayOut.at(millis)),
        carPlayLeft = between(L.BOOT_CARPLAY_START.toFloat(), L.BOOT_CARPLAY_GAP.toFloat(), carPlaySlide.at(millis)),
        mapAlpha = mapIn.at(millis),
        mapScale = between(L.BOOT_MAP_SCALE, 1f, mapGrow.at(millis)),
        leftAlpha = leftIn.at(millis),
        leftShift = between(-L.BOOT_SIDE_SHIFT, 0f, leftSlide.at(millis)),
        rightAlpha = rightIn.at(millis),
        rightShift = between(L.BOOT_SIDE_SHIFT, 0f, rightSlide.at(millis)),
    )

    val finished: Frame = at(DURATION_MILLIS)

    private fun between(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
}
