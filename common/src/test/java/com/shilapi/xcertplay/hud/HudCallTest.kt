package com.shilapi.xcertplay.hud

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudCallTest {
    private val words = HudCallWords("Входящий вызов", "Вызов…", "Микрофон выкл.")
    private val now = 1_000_000L

    private fun call(
        calls: CarPlayCallSnapshot,
        callsActive: Boolean = true,
        engineeringMode: Boolean = true,
    ) = HudCall.of(calls, engineeringMode, { callsActive }, now, { words }, Locale.ROOT)

    private fun ringing(name: String = "", number: String = "") =
        CarPlayCallSnapshot(card = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, name, number = number))

    private fun talking(seconds: Long, muted: Boolean? = null) = CarPlayCallSnapshot(
        card = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Анна Петрова", now - seconds * 1_000, "+7 900 123-45-67"),
        muted = muted,
    )

    @Test fun aRingingCallShowsTheCallerByNameThenNumberThenAsIncoming() {
        assertEquals(HudCall("Анна Петрова", ""), call(ringing("Анна Петрова", "+7 900 123-45-67")))
        assertEquals(HudCall("+7 900 123-45-67", ""), call(ringing(number = "+7 900 123-45-67")))
        assertEquals(HudCall("Входящий вызов", ""), call(ringing()))
    }

    @Test fun aDialedCallShowsTheCallee() {
        val dialing = CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "", number = "+7 900 123-45-67")
        assertEquals(HudCall("+7 900 123-45-67", ""), call(CarPlayCallSnapshot(card = dialing)))
        assertEquals(HudCall("Мама", ""), call(CarPlayCallSnapshot(card = dialing.copy(name = "Мама"))))
        assertEquals(HudCall("Вызов…", ""), call(CarPlayCallSnapshot(card = dialing.copy(number = ""))))
    }

    @Test fun aConnectedCallShowsItsTime() {
        assertEquals(HudCall("00:05", ""), call(talking(5)))
        assertEquals(HudCall("01:05", ""), call(talking(65)))
        assertEquals(HudCall("1:00:01", ""), call(talking(3_601)))
    }

    @Test fun theMicrophoneShowsOffOnlyOnceTheIphoneSaidSo() {
        assertEquals(HudCall("00:05", "Микрофон выкл."), call(talking(5, muted = true)))
        assertEquals(HudCall("00:05", ""), call(talking(5, muted = false)))
        assertEquals(HudCall("00:05", ""), call(talking(5, muted = null)))
        assertEquals(HudCall("Анна", ""), call(ringing("Анна").copy(muted = true)))
    }

    @Test fun anEndedCallLeavesAtOnce() {
        val ended = CarPlayCallSnapshot(
            lastOutcome = CarPlayCallOutcome(CarPlayCallOutcome.Kind.ENDED, "Анна Петрова", "", 5, now - 100),
        )
        assertNull(call(ended))
        assertNull(call(CarPlayCallSnapshot()))
    }

    @Test fun outsideEngineeringModeFirmwareShowsTheCallItself() {
        assertNull(call(talking(5), engineeringMode = false))
        assertNull(call(ringing("Анна"), engineeringMode = false))
    }

    @Test fun withoutACallNeitherTheOptionNorTheWordsAreRead() {
        val read = mutableListOf<String>()
        assertNull(HudCall.of(CarPlayCallSnapshot(), true, { read += "option"; true }, now, { read += "words"; words }))
        assertEquals(emptyList<String>(), read)
    }

    @Test fun theCallsOptionOffShowsNothing() {
        assertNull(call(talking(5), callsActive = false))
        assertNull(call(ringing("Анна"), callsActive = false))
    }
}
