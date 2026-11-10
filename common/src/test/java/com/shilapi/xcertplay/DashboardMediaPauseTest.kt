package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMediaPauseTest {
    private fun paused(source: DashboardMediaSource, signals: DashboardMediaSignals) =
        DashboardMediaFeed.paused(source, signals)

    @Test fun carPlayIsPausedWhileItDoesNotPlay() {
        val focused = DashboardMediaSignals(carPlayFocus = true, carPlayTrack = true)
        assertTrue(paused(DashboardMediaSource.CARPLAY, focused))
        assertFalse(paused(DashboardMediaSource.CARPLAY, focused.copy(carPlayPlaying = true)))
    }

    @Test fun aHeldRadioIsPausedWhileItDoesNotSound() {
        val playing = DashboardMediaSignals(radioPlaying = true, radioServicePlaying = true)
        assertFalse(paused(DashboardMediaSource.RADIO, playing))
        assertFalse("the radio service is not bound yet", paused(DashboardMediaSource.RADIO,
            playing.copy(radioServicePlaying = null)))
        assertTrue("the firmware's flag outlives the radio", paused(DashboardMediaSource.RADIO,
            playing.copy(radioServicePlaying = false)))
        assertTrue(paused(DashboardMediaSource.RADIO, DashboardMediaSignals()))
    }

    @Test fun nothingElseIsPaused() {
        for (source in listOf(DashboardMediaSource.OTHER, DashboardMediaSource.UNKNOWN)) {
            assertFalse(paused(source, DashboardMediaSignals()))
        }
    }

    @Test fun aPauseShowsAfterHalfASecond() {
        val delay = DashboardPauseDelay()
        assertFalse(delay.shown(paused = true, nowMillis = 1_000))
        assertEquals(500L, delay.remainingMillis(1_000))
        assertFalse(delay.shown(paused = true, nowMillis = 1_499))
        assertEquals(1L, delay.remainingMillis(1_499))
        assertTrue(delay.shown(paused = true, nowMillis = 1_500))
        assertNull(delay.remainingMillis(1_500))
        assertTrue(delay.shown(paused = true, nowMillis = 9_000))
    }

    @Test fun aShortPauseNeverShowsAndPlayingHidesAtOnce() {
        val delay = DashboardPauseDelay()
        assertFalse(delay.shown(paused = true, nowMillis = 1_000))
        assertFalse("the iPhone plays the next track", delay.shown(paused = false, nowMillis = 1_200))
        assertNull(delay.remainingMillis(1_200))
        assertFalse("a new pause waits again", delay.shown(paused = true, nowMillis = 1_300))
        assertFalse(delay.shown(paused = true, nowMillis = 1_700))
        assertTrue(delay.shown(paused = true, nowMillis = 1_800))
        assertFalse(delay.shown(paused = false, nowMillis = 1_801))
    }

    @Test fun aResetForgetsAPauseInProgress() {
        val delay = DashboardPauseDelay()
        delay.shown(paused = true, nowMillis = 1_000)
        delay.reset()
        assertNull(delay.remainingMillis(1_100))
        assertFalse(delay.shown(paused = true, nowMillis = 1_600))
    }
}
