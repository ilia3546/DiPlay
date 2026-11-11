package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyHoldToggleTest {
    private var now = 0L
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    private var toggles = 0
    private val hold = KeyHoldToggle(1_500, { delay, task -> pending += now + delay to task }) { toggles++ }

    private fun advance(millis: Long) {
        now += millis
        val due = pending.filter { it.first <= now }
        pending.removeAll(due)
        due.forEach { it.second() }
    }

    @Test fun aShortPressDoesNothing() {
        hold.pressed()
        advance(1_000)
        hold.released()
        advance(1_000)
        assertEquals(0, toggles)
    }

    @Test fun aHoldOfOneAndAHalfSecondsTogglesOnce() {
        hold.pressed()
        advance(1_499)
        assertEquals(0, toggles)
        advance(1)
        assertEquals(1, toggles)
        advance(5_000)
        hold.released()
        assertEquals(1, toggles)
    }

    @Test fun repeatedPressesWhileHeldNeitherRearmNorDelay() {
        hold.pressed()
        advance(1_000)
        hold.pressed()
        advance(500)
        assertEquals(1, toggles)
        hold.pressed()
        advance(5_000)
        assertEquals(1, toggles)
    }

    @Test fun aNewHoldAfterAReleaseTogglesAgain() {
        hold.pressed()
        advance(1_500)
        hold.released()
        hold.pressed()
        advance(1_500)
        assertEquals(2, toggles)
    }

    @Test fun aPendingToggleDoesNothingAfterCancel() {
        hold.pressed()
        advance(1_000)
        hold.cancel()
        advance(1_000)
        assertEquals(0, toggles)
    }

    @Test fun aPendingToggleOfAnEarlierHoldDoesNothing() {
        hold.pressed()
        advance(1_000)
        hold.released()
        hold.pressed()
        advance(500)
        assertEquals(0, toggles)
        advance(1_000)
        assertEquals(1, toggles)
    }
}
