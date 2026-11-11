package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.RtpReorderQueue.GapEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioGapHoldTest {
    private var nowNs = 0L
    private val hold = AudioGapHold { nowNs }

    private fun remainingMs() = hold.remainingNanos() / 1_000_000

    @Test fun aPlayingTrackWaitsUntilItsSoundFallsToTheFloor() {
        // 1000 ms buffer: the track holds about a second when the gap reaches the head.
        hold.observeTrack(playing = true, unplayedNs = 1_000_000_000)
        assertTrue(hold.start())
        assertEquals(700, remainingMs())
        nowNs += 200_000_000
        hold.observeTrack(playing = true, unplayedNs = 800_000_000)
        assertEquals(500, remainingMs())
    }

    @Test fun aPlaybackHeadThatDoesNotMoveDoesNotPushTheDeadlineBack() {
        hold.observeTrack(playing = true, unplayedNs = 1_000_000_000)
        assertTrue(hold.start())
        for (step in 1..7) {
            nowNs += 100_000_000
            hold.observeTrack(playing = true, unplayedNs = 1_000_000_000)
            assertEquals(700L - step * 100, remainingMs())
        }
    }

    @Test fun soundDrainingFasterThanExpectedBringsTheDeadlineCloser() {
        hold.observeTrack(playing = true, unplayedNs = 1_000_000_000)
        assertTrue(hold.start())
        nowNs += 100_000_000
        hold.observeTrack(playing = true, unplayedNs = 500_000_000)
        assertEquals(200, remainingMs())
    }

    @Test fun aLongBufferWaitsNoLongerThanTheLimit() {
        hold.observeTrack(playing = true, unplayedNs = 3_000_000_000)
        assertTrue(hold.start())
        assertEquals(1_000, remainingMs())
        nowNs += AudioGapHold.MAX_HOLD_NANOS
        assertEquals(0, remainingMs())
    }

    @Test fun theLimitEndsBeforeTheLagGuardWouldDropTheBacklogTheWaitBuilds() {
        assertTrue(AudioGapHold.MAX_HOLD_NANOS < MediaPacketLagGuard.CONGESTION_GRACE_NS)
    }

    @Test fun aTrackAlreadyAtTheFloorDoesNotStartAWait() {
        hold.observeTrack(playing = true, unplayedNs = 250_000_000)
        assertFalse(hold.start())
        assertEquals("holds=0 holdFilled=0 holdGaveUp=0 holdAbandoned=0 holdNoRoom=1 holdMaxMs=0", hold.takeStats())
        assertEquals(0, hold.gaveUpTotal)
    }

    @Test fun aTrackThatDoesNotPlayWaitsOnlyBriefly() {
        hold.observeTrack(playing = false, unplayedNs = 2_000_000_000)
        assertTrue(hold.start())
        assertEquals(400, remainingMs())
        nowNs += 300_000_000
        hold.observeTrack(playing = false, unplayedNs = 2_000_000_000)
        assertEquals(100, remainingMs())
    }

    @Test fun theStatsCountEachWaitOnceAndStartAgainForTheNextWindow() {
        hold.observeTrack(playing = false, unplayedNs = 0)
        repeat(4) { hold.start() }
        hold.ended(120_000_000, GapEnd.FILLED)
        hold.ended(250_000_000, GapEnd.GAVE_UP)
        hold.ended(300_000_000, GapEnd.ABANDONED)
        assertEquals("holds=4 holdFilled=1 holdGaveUp=1 holdAbandoned=1 holdNoRoom=0 holdMaxMs=300", hold.takeStats())
        assertEquals("holds=0 holdFilled=0 holdGaveUp=0 holdAbandoned=0 holdNoRoom=0 holdMaxMs=0", hold.takeStats())
        hold.ended(0, GapEnd.GAVE_UP)
        assertEquals(2, hold.gaveUpTotal)
    }
}
