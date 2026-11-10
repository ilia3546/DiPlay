package com.shilapi.xcertplay

import com.shilapi.xcertplay.DashboardMediaBadgeMotion.Badge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class DashboardMediaBadgeMotionTest {
    private val start = 10_000L
    private val hidden = Badge(maxWidth = 0f, opacity = 0f, scale = 0.8f)
    private val shown = Badge(maxWidth = 200f, opacity = 1f, scale = 1f)

    private fun motion(paused: Boolean, muted: Boolean) = DashboardMediaBadgeMotion().apply { jumpTo(paused, muted) }

    private fun settle(fraction: Float) = CubicBezier.SETTLE.at(fraction)

    private fun ease(fraction: Float) = CubicBezier.EASE.at(fraction)

    private fun assertBadge(expected: Badge, actual: Badge) {
        assertEquals("max-width", expected.maxWidth, actual.maxWidth, 0.01f)
        assertEquals("opacity", expected.opacity, actual.opacity, 0.001f)
        assertEquals("scale", expected.scale, actual.scale, 0.001f)
    }

    @Test fun itStartsWithNoBadgeAndJumpsWithoutMoving() {
        val motion = DashboardMediaBadgeMotion()
        assertEquals(DashboardMediaBadgeMotion.Frame(hidden, hidden, 0f), motion.at(start))
        motion.jumpTo(paused = true, muted = true)
        assertEquals(DashboardMediaBadgeMotion.Frame(shown, shown, 8f), motion.at(0))
        assertFalse(motion.moving(0))
    }

    @Test fun playToPaused() {
        val motion = motion(paused = false, muted = false)
        motion.moveTo(paused = true, muted = false, nowMillis = start)
        assertBadge(hidden, motion.at(start).paused)
        assertTrue(motion.moving(start))
        val half = motion.at(start + 225)
        assertBadge(Badge(200 * settle(0.5f), ease(0.75f), 0.8f + 0.2f * settle(0.5f)), half.paused)
        assertTrue("the fade ends before the size", motion.at(start + 300).paused.opacity == 1f && motion.moving(start + 300))
        assertBadge(shown, motion.at(start + 450).paused)
        assertFalse(motion.moving(start + 450))
        for (millis in listOf(0L, 225L, 450L)) {
            assertBadge(hidden, motion.at(start + millis).muted)
            assertEquals(0f, motion.at(start + millis).gap)
        }
    }

    @Test fun aShowingBadgeReachesItsFullWidthBeforeTheEnd() {
        val motion = motion(paused = false, muted = false)
        motion.moveTo(paused = true, muted = false, nowMillis = start)
        val natural = 95f
        val full = (0L..450L step 5).first { motion.at(start + it).paused.width(natural) == natural }
        assertTrue("full at $full ms", full in 5L..225L)
        assertEquals(natural, motion.at(start + full).width(natural, 120f))
        assertTrue(motion.at(start + full).paused.maxWidth < 200f)
    }

    @Test fun pausedToPausedAndMuted() {
        val motion = motion(paused = true, muted = false)
        motion.moveTo(paused = true, muted = true, nowMillis = start)
        assertBadge(hidden, motion.at(start).muted)
        assertEquals(0f, motion.at(start).gap)
        val half = motion.at(start + 225)
        assertBadge(shown, half.paused)
        assertBadge(Badge(200 * settle(0.5f), ease(0.75f), 0.8f + 0.2f * settle(0.5f)), half.muted)
        assertEquals(8 * settle(0.5f), half.gap, 0.001f)
        val end = motion.at(start + 450)
        assertBadge(shown, end.paused)
        assertBadge(shown, end.muted)
        assertEquals(8f, end.gap)
        assertEquals("the row centres both badges and the gap", 95f + 8f + 120f, end.width(95f, 120f))
    }

    @Test fun pausedAndMutedToMuted() {
        val motion = motion(paused = true, muted = true)
        motion.moveTo(paused = false, muted = true, nowMillis = start)
        assertBadge(shown, motion.at(start).paused)
        assertEquals(8f, motion.at(start).gap)
        val half = motion.at(start + 225)
        assertBadge(Badge(200 * (1 - settle(0.5f)), 1 - ease(0.75f), 1 - 0.2f * settle(0.5f)), half.paused)
        assertEquals(8 * (1 - settle(0.5f)), half.gap, 0.001f)
        assertBadge(shown, half.muted)
        val end = motion.at(start + 450)
        assertBadge(hidden, end.paused)
        assertEquals(0f, end.gap)
        assertBadge(shown, end.muted)
        assertEquals(120f, end.width(95f, 120f))
    }

    @Test fun aHidingBadgeKeepsItsWidthWhileItsMaxWidthIsWider() {
        val motion = motion(paused = true, muted = true)
        motion.moveTo(paused = false, muted = true, nowMillis = start)
        val natural = 95f
        val narrowing = (0L..450L step 1).first { motion.at(start + it).paused.width(natural) < natural }
        for (millis in 0 until narrowing) {
            val frame = motion.at(start + millis)
            assertEquals("$millis ms", natural, frame.paused.width(natural))
        }
        assertTrue("it fades while still full, $narrowing ms", narrowing > 0 && motion.at(start + narrowing - 1).paused.opacity < 1f)
        assertTrue("then it narrows to nothing", motion.at(start + 449).paused.width(natural) in 0f..1f)
        assertEquals(0f, motion.at(start + 450).paused.width(natural))
    }

    @Test fun mutedToPlay() {
        val motion = motion(paused = false, muted = true)
        motion.moveTo(paused = false, muted = false, nowMillis = start)
        assertBadge(shown, motion.at(start).muted)
        assertBadge(Badge(200 * (1 - settle(0.5f)), 1 - ease(0.75f), 1 - 0.2f * settle(0.5f)),
            motion.at(start + 225).muted)
        assertBadge(hidden, motion.at(start + 450).muted)
        for (millis in listOf(0L, 225L, 450L)) {
            assertBadge(hidden, motion.at(start + millis).paused)
            assertEquals(0f, motion.at(start + millis).gap)
        }
    }

    @Test fun playToPausedAndMutedAtOnce() {
        val motion = motion(paused = false, muted = false)
        motion.moveTo(paused = true, muted = true, nowMillis = start)
        assertEquals(DashboardMediaBadgeMotion.Frame(hidden, hidden, 0f), motion.at(start))
        val half = motion.at(start + 225)
        assertEquals("both badges move together", half.paused, half.muted)
        assertEquals(8 * settle(0.5f), half.gap, 0.001f)
        assertEquals(DashboardMediaBadgeMotion.Frame(shown, shown, 8f), motion.at(start + 450))
    }

    @Test fun pausedAndMutedToPlay() {
        val motion = motion(paused = true, muted = true)
        motion.moveTo(paused = false, muted = false, nowMillis = start)
        assertEquals(DashboardMediaBadgeMotion.Frame(shown, shown, 8f), motion.at(start))
        val half = motion.at(start + 225)
        assertEquals(half.paused, half.muted)
        assertBadge(Badge(200 * (1 - settle(0.5f)), 1 - ease(0.75f), 1 - 0.2f * settle(0.5f)), half.paused)
        assertEquals(DashboardMediaBadgeMotion.Frame(hidden, hidden, 0f), motion.at(start + 450))
    }

    @Test fun aNewTargetMidwayStartsFromWhereTheBadgeIs() {
        val motion = motion(paused = false, muted = false)
        motion.moveTo(paused = true, muted = false, nowMillis = start)
        val turn = start + 150
        val there = motion.at(turn)
        motion.moveTo(paused = false, muted = false, nowMillis = turn)
        assertEquals("no jump", there, motion.at(turn))
        val next = motion.at(turn + 1)
        assertEquals(there.paused.maxWidth, next.paused.maxWidth, 5f)
        assertEquals(there.paused.opacity, next.paused.opacity, 0.05f)
        // Turning back, each property runs for its duration times the eased progress it had made.
        val resize = 450 * settle(150f / 450)
        val fade = 300 * ease(150f / 300)
        val half = (resize / 2).toLong()
        assertBadge(Badge(there.paused.maxWidth * (1 - settle(half / resize)), there.paused.opacity * (1 - ease(half / fade)),
            there.paused.scale + (0.8f - there.paused.scale) * settle(half / resize)), motion.at(turn + half).paused)
        assertEquals(0f, motion.at(turn + ceil(fade).toLong()).paused.opacity)
        assertTrue(motion.moving(turn + resize.toLong() - 1))
        val back = turn + ceil(resize).toLong()
        assertBadge(hidden, motion.at(back).paused)
        assertFalse(motion.moving(back))
    }

    @Test fun aPauseCancelledAfter50MillisTurnsBackSooner() {
        val motion = motion(paused = false, muted = false)
        motion.moveTo(paused = true, muted = false, nowMillis = start)
        val turn = start + 50
        val width = motion.at(turn).paused.maxWidth
        motion.moveTo(paused = false, muted = false, nowMillis = turn)
        val factor = settle(50f / 450)
        assertEquals("the reversing shortening factor", 0.44f, factor, 0.04f)
        val resize = 450 * factor
        assertEquals(width, motion.at(turn).paused.maxWidth)
        val half = (resize / 2).toLong()
        assertEquals(width * (1 - settle(half / resize)), motion.at(turn + half).paused.maxWidth, 0.01f)
        assertTrue(motion.at(turn + resize.toLong() - 1).paused.maxWidth > 0f)
        assertTrue(motion.moving(turn + resize.toLong() - 1))
        val back = turn + ceil(resize).toLong()
        assertEquals("back in about ${resize.toLong()} ms rather than 450", hidden, motion.at(back).paused)
        assertFalse(motion.moving(back))
    }

    @Test fun turningBackTwiceShortensByTheLastTurn() {
        val motion = motion(paused = false, muted = false)
        motion.moveTo(paused = true, muted = false, nowMillis = start)
        motion.moveTo(paused = false, muted = false, nowMillis = start + 50)
        val first = settle(50f / 450)
        val again = start + 90
        val width = motion.at(again).paused.maxWidth
        motion.moveTo(paused = true, muted = false, nowMillis = again)
        val second = (settle(40f / (450 * first)) * first + (1 - first)).coerceIn(0f, 1f)
        assertTrue(second < 1f && second > first)
        assertEquals(width, motion.at(again).paused.maxWidth)
        assertTrue(motion.moving(again + (450 * second).toLong() - 1))
        val end = again + ceil(450 * second).toLong()
        assertBadge(shown, motion.at(end).paused)
        assertFalse(motion.moving(end))
    }

    @Test fun aChangeAfterTheMoveEndedTakesTheFullDuration() {
        val motion = motion(paused = false, muted = false)
        motion.moveTo(paused = true, muted = false, nowMillis = start)
        motion.moveTo(paused = false, muted = false, nowMillis = start + 450)
        assertTrue(motion.moving(start + 899))
        assertBadge(Badge(200 * (1 - settle(0.5f)), 1 - ease(0.75f), 1 - 0.2f * settle(0.5f)), motion.at(start + 675).paused)
        assertFalse(motion.moving(start + 900))
    }

    @Test fun theGapTurnsBackMidwayWithoutAJump() {
        val motion = motion(paused = true, muted = false)
        motion.moveTo(paused = true, muted = true, nowMillis = start)
        val turn = start + 100
        val gap = motion.at(turn).gap
        assertTrue(gap > 0f && gap < 8f)
        motion.moveTo(paused = true, muted = false, nowMillis = turn)
        assertEquals(gap, motion.at(turn).gap)
        val resize = 450 * settle(100f / 450)
        val half = (resize / 2).toLong()
        assertEquals(gap * (1 - settle(half / resize)), motion.at(turn + half).gap, 0.001f)
        assertEquals(0f, motion.at(turn + ceil(resize).toLong()).gap)
    }

    @Test fun theSameTargetAgainDoesNotStartOver() {
        val motion = motion(paused = false, muted = false)
        assertTrue(motion.moveTo(paused = true, muted = false, nowMillis = start))
        assertFalse("the feed's tick changes nothing", motion.moveTo(paused = true, muted = false, nowMillis = start + 300))
        assertBadge(shown, motion.at(start + 450).paused)
        assertFalse(motion.moveTo(paused = true, muted = false, nowMillis = start + 1_000))
        motion.jumpTo(paused = true, muted = false)
        assertFalse("a jump to where the badges are", motion.jumpTo(paused = true, muted = false))
        assertTrue(motion.jumpTo(paused = true, muted = true))
        assertFalse(motion.moving(start + 450))
    }

    @Test fun theGapIsThereOnlyWithBothBadges() {
        for (paused in listOf(false, true)) for (muted in listOf(false, true)) {
            assertEquals("paused=$paused muted=$muted", if (paused && muted) 8f else 0f, motion(paused, muted).at(start).gap)
        }
    }

    @Test fun aJumpEndsAMove() {
        val motion = motion(paused = false, muted = false)
        motion.moveTo(paused = true, muted = true, nowMillis = start)
        motion.jumpTo(paused = false, muted = true)
        assertFalse(motion.moving(start + 1))
        assertEquals(DashboardMediaBadgeMotion.Frame(hidden, shown, 0f), motion.at(start + 1))
    }
}
