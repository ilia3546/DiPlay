package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallOutcome
import com.shilapi.xcertplay.hud.CarPlayCallSnapshot
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardCallBlockTest {
    private val ru = Locale.forLanguageTag("ru")
    private val now = 1_762_460_160_000L
    private val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Анна Смирнова", number = "+7 999 123-45-67")
    private val unknown = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "+7 999 123-45-67", number = "+7 999 123-45-67")

    private fun initials(name: String) = DashboardCallText.initials(name, ru)

    @Test fun initialsAreTheFirstLettersOfTwoWords() {
        assertEquals("АС", initials("Анна Смирнова"))
        assertEquals("АС", initials("  анна   смирнова  "))
        assertEquals("АМ", initials("Анна Мария Смирнова"))
        assertEquals("А", initials("Анна"))
        assertEquals("JD", initials("john doe"))
    }

    @Test fun aNumberOrNoNameHasNoInitials() {
        assertNull(initials(""))
        assertNull(initials("   "))
        assertNull(initials("+7 (999) 123-45-67"))
        assertNull(initials("900"))
        assertTrue(DashboardCallText.isNumber("+7 (999) 123-45-67"))
        assertFalse(DashboardCallText.isNumber("()-+"))
        assertFalse(DashboardCallText.isNumber("Офис 2"))
    }

    @Test fun anEmojiDoesNotBreakTheInitials() {
        assertEquals("А", initials("😀 Анна"))
        assertEquals("АС", initials("Анна 😀 Смирнова"))
        assertEquals("МБ", initials("Мама❤️ Бабушка"))
        assertNull(initials("😀🎉"))
    }

    @Test fun theRingingDetailIsTheLabelThenTheNumberOfANamedCaller() {
        assertEquals("Мобильный", DashboardCallText.ringingDetail(ringing, "Мобильный"))
        assertEquals("+7 999 123-45-67", DashboardCallText.ringingDetail(ringing, ""))
        assertNull("the name already is the number", DashboardCallText.ringingDetail(unknown, ""))
        assertEquals("Мобильный", DashboardCallText.ringingDetail(unknown, "Мобильный"))
        assertNull(DashboardCallText.ringingDetail(ringing.copy(number = ""), " "))
    }

    @Test fun theTalkTimeCountsWholeSecondsFromTheConnection() {
        val active = ringing.copy(phase = CarPlayCallCard.Phase.ACTIVE, activeSinceMillis = now - 61_900)
        assertEquals(61, DashboardCallText.talkSeconds(active, now))
        assertEquals(0, DashboardCallText.talkSeconds(active.copy(activeSinceMillis = now + 5_000), now))
        assertEquals(0, DashboardCallText.talkSeconds(ringing, now))
    }

    @Test fun aCallTakesTheMediaBlocksPlaceWhileTheOptionIsOn() {
        val calls = CarPlayCallSnapshot(card = ringing, label = "Мобильный")
        assertEquals(DashboardCallShown.Live(ringing, "Мобильный", muted = false), DashboardCallShown.of(true, calls, now))
        assertEquals(DashboardCallShown.Live(ringing, "Мобильный", muted = true),
            DashboardCallShown.of(true, calls.copy(muted = true), now))
        assertNull("option off", DashboardCallShown.of(false, calls, now))
        assertNull("no call", DashboardCallShown.of(true, CarPlayCallSnapshot(), now))
    }

    @Test fun theEndedCardStaysForThreeAndAHalfSeconds() {
        val outcome = CarPlayCallOutcome(CarPlayCallOutcome.Kind.ENDED, "Анна Смирнова", "+7", 134, endedAtMillis = now)
        val calls = CarPlayCallSnapshot(lastOutcome = outcome)
        assertEquals(DashboardCallShown.Ended(outcome), DashboardCallShown.of(true, calls, now))
        assertEquals(DashboardCallShown.Ended(outcome), DashboardCallShown.of(true, calls, now + 3_499))
        assertNull(DashboardCallShown.of(true, calls, now + 3_500))
        assertNull("option off", DashboardCallShown.of(false, calls, now + 1_000))
        assertNull("a clock set back does not keep it", DashboardCallShown.of(true, calls, now - 1_000))
    }

    @Test fun anOldOutcomeIsNotShownWhenTheFeedStarts() {
        val outcome = CarPlayCallOutcome(CarPlayCallOutcome.Kind.DECLINED, "Анна Смирнова", "+7", 0, endedAtMillis = now)
        assertNull(DashboardCallShown.of(true, CarPlayCallSnapshot(lastOutcome = outcome), now + 60_000))
    }

    @Test fun aNewCallReplacesTheEndedCardAtOnce() {
        val outcome = CarPlayCallOutcome(CarPlayCallOutcome.Kind.ENDED, "Анна Смирнова", "+7", 134, endedAtMillis = now)
        val calls = CarPlayCallSnapshot(card = unknown, lastOutcome = outcome)
        assertEquals(DashboardCallShown.Live(unknown, "", muted = false), DashboardCallShown.of(true, calls, now + 1_000))
    }
}
