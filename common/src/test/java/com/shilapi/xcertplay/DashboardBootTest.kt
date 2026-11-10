package com.shilapi.xcertplay

import com.shilapi.xcertplay.DashboardBoot.START_DELAY_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardBootTest {
    @Test fun itStartsOnABlackClusterWithEverythingButTheClockHidden() {
        val start = DashboardBoot.at(0)
        assertEquals(0f, start.wallpaperAlpha)
        assertFalse(start.logoShown)
        assertEquals(0f, start.mapAlpha)
        assertEquals(0.9f, start.mapScale)
        assertEquals(0f, start.leftAlpha)
        assertEquals(-50f, start.leftShift)
        assertEquals(0f, start.rightAlpha)
        assertEquals(50f, start.rightShift)
        assertFalse("the logo waits for QNX", DashboardBoot.at(START_DELAY_MILLIS).logoShown)
    }

    @Test fun theGeelyLogoFadesInAtTheCentreFirst() {
        val halfway = DashboardBoot.at(START_DELAY_MILLIS + 350)
        assertTrue(halfway.geelyAlpha in 0.1f..0.95f)
        val shown = DashboardBoot.at(START_DELAY_MILLIS + 700)
        assertEquals(1f, shown.geelyAlpha)
        assertEquals("centred on the logo point", -171f, shown.geelyLeft)
        assertEquals(0f, shown.dividerAlpha)
        assertEquals(0f, shown.carPlayAlpha)
        assertEquals(0f, shown.wallpaperAlpha)
    }

    @Test fun thenGeelyAndCarPlaySitEitherSideOfTheDivider() {
        val lockup = DashboardBoot.at(2_800)
        assertEquals(1f, lockup.geelyAlpha)
        assertEquals("46 px left of the centre", -388f, lockup.geelyLeft)
        assertEquals(1f, lockup.dividerAlpha)
        assertEquals(1f, lockup.dividerScale)
        assertEquals(1f, lockup.carPlayAlpha)
        assertEquals("46 px right of the centre", 46f, lockup.carPlayLeft)
        assertEquals(0f, lockup.wallpaperAlpha)
        assertEquals(0f, lockup.mapAlpha)
        val sliding = DashboardBoot.at(1_800)
        assertTrue(sliding.geelyLeft < -171f && sliding.geelyLeft > -388f)
        assertTrue("the divider grows after it appears", sliding.dividerScale < 1f)
    }

    @Test fun theLogosGiveWayToTheWallpaperThenTheMapThenTheSides() {
        val fading = DashboardBoot.at(3_300)
        assertTrue(fading.wallpaperAlpha > 0f && fading.wallpaperAlpha < 1f)
        assertTrue(fading.geelyAlpha < 1f)
        assertEquals(0f, fading.leftAlpha)
        val map = DashboardBoot.at(3_600)
        assertTrue(map.mapAlpha > 0f)
        assertTrue(map.mapScale > 0.9f && map.mapScale < 1f)
        assertEquals(0f, map.leftAlpha)
        val sides = DashboardBoot.at(3_800)
        assertTrue(sides.leftAlpha > 0f)
        assertEquals("the right side follows 150 ms later", 0f, sides.rightAlpha)
        assertFalse(DashboardBoot.at(3_750).logoShown)
        var last = 0f
        for (millis in 0L..DashboardBoot.DURATION_MILLIS step 50) {
            val alpha = DashboardBoot.at(millis).wallpaperAlpha
            assertTrue(alpha >= last)
            last = alpha
        }
    }

    @Test fun itEndsWithTheDashboardAsItStays() {
        assertEquals(4_850L, DashboardBoot.DURATION_MILLIS)
        val end = DashboardBoot.finished
        assertEquals(end, DashboardBoot.at(DashboardBoot.DURATION_MILLIS))
        assertEquals(end, DashboardBoot.at(DashboardBoot.DURATION_MILLIS + 10_000))
        assertEquals(1f, end.wallpaperAlpha)
        assertFalse(end.logoShown)
        assertEquals(1f, end.mapAlpha)
        assertEquals(1f, end.mapScale)
        assertEquals(1f, end.leftAlpha)
        assertEquals(0f, end.leftShift)
        assertEquals(1f, end.rightAlpha)
        assertEquals(0f, end.rightShift)
    }
}
