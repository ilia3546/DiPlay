package com.shilapi.xcertplay

import com.shilapi.xcertplay.HoldSeek.Reason
import com.shilapi.xcertplay.HoldSeek.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldSeekTest {
    private fun held(forward: Boolean, position: Long? = 60_000, duration: Long? = 200_000, now: Long = 0) =
        HoldSeek().also { assertNull(it.start(forward, position, duration, now)) }

    @Test fun forwardStepsTenSecondsOnEachStep() {
        val seek = held(forward = true)
        assertEquals(Step.Seek(70_000, 10_000, 1), seek.next())
        assertEquals(Step.Seek(80_000, 10_000, 2), seek.next())
        assertEquals(Step.Seek(90_000, 10_000, 3), seek.next())
        assertEquals(3, seek.steps)
    }

    @Test fun backwardStepsTenSecondsBack() {
        val seek = held(forward = false)
        assertEquals(Step.Seek(50_000, -10_000, 1), seek.next())
        assertEquals(Step.Seek(40_000, -10_000, 2), seek.next())
    }

    @Test fun forwardStopsOneSecondBeforeTheEnd() {
        val seek = held(forward = true, position = 195_000, duration = 200_000)
        assertEquals(Step.Seek(199_000, 4_000, 1), seek.next())
        assertEquals(Step.Skip(Reason.AT_LIMIT), seek.next())
        assertTrue(seek.active)
    }

    @Test fun backwardStopsAtTheStart() {
        val seek = held(forward = false, position = 4_000)
        assertEquals(Step.Seek(0, -4_000, 1), seek.next())
        assertEquals(Step.Skip(Reason.AT_LIMIT), seek.next())
    }

    @Test fun aPositionPastTheEndIsClampedFirst() {
        val seek = held(forward = false, position = 204_500)
        assertEquals(Step.Seek(189_000, -10_000, 1), seek.next())
    }

    @Test fun noPositionStartsNoHold() {
        val seek = HoldSeek()
        assertEquals(Reason.NO_ELAPSED, seek.start(true, null, 200_000, 0))
        assertFalse(seek.active)
        assertEquals(Step.Skip(Reason.NOT_HELD), seek.next())
    }

    @Test fun noDurationStartsNoHold() {
        val seek = HoldSeek()
        assertEquals(Reason.NO_DURATION, seek.start(true, 60_000, null, 0))
        assertEquals(Reason.NO_DURATION, seek.start(true, 60_000, 0, 0))
        assertFalse(seek.active)
    }

    @Test fun stopEndsTheHold() {
        val seek = held(forward = true)
        seek.next()
        seek.stop()
        assertFalse(seek.active)
        assertEquals(0, seek.steps)
        assertEquals(Step.Skip(Reason.NOT_HELD), seek.next())
    }

    @Test fun aNewHoldStartsFromItsOwnPosition() {
        val seek = held(forward = true)
        seek.next()
        assertNull(seek.start(true, 10_000, 200_000, 0))
        assertEquals(Step.Seek(20_000, 10_000, 1), seek.next())
    }

    @Test fun aHoldExpiresAfterAMinute() {
        val seek = held(forward = true, now = 1_000)
        assertFalse(seek.expired(60_999))
        assertTrue(seek.expired(61_000))
        seek.stop()
        assertFalse(seek.expired(120_000))
    }

    @Test fun thePlayingPositionMovesOnFromTheLastChange() {
        val clock = HoldSeek.PlaybackClock()
        clock.observe(60_000, playing = true, nowMillis = 1_000)
        assertEquals(63_500L, clock.positionAt(4_500))
    }

    @Test fun aPausedPositionStaysPut() {
        val clock = HoldSeek.PlaybackClock()
        clock.observe(60_000, playing = false, nowMillis = 1_000)
        assertEquals(60_000L, clock.positionAt(4_500))
    }

    @Test fun aResumeAtTheSamePositionDoesNotCountThePause() {
        val clock = HoldSeek.PlaybackClock()
        clock.observe(60_000, playing = true, nowMillis = 0)
        clock.observe(60_000, playing = false, nowMillis = 1_000)
        clock.observe(60_000, playing = true, nowMillis = 61_000)
        assertEquals(60_000L, clock.positionAt(61_000))
        assertEquals(62_000L, clock.positionAt(63_000))
    }

    @Test fun aRepeatedUpdateDoesNotRestartTheClock() {
        val clock = HoldSeek.PlaybackClock()
        clock.observe(60_000, playing = true, nowMillis = 0)
        clock.observe(60_000, playing = true, nowMillis = 5_000)
        assertEquals(66_000L, clock.positionAt(6_000))
    }

    @Test fun noElapsedTimeHasNoPosition() {
        val clock = HoldSeek.PlaybackClock()
        clock.observe(null, playing = true, nowMillis = 0)
        assertNull(clock.positionAt(1_000))
        clock.observe(60_000, playing = false, nowMillis = 0)
        clock.reset()
        assertNull(clock.positionAt(1_000))
    }
}
