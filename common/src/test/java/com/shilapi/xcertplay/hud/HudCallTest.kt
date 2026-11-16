package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.CarPlayCallCard.Phase.ACTIVE
import com.shilapi.xcertplay.hud.CarPlayCallCard.Phase.DIALING
import com.shilapi.xcertplay.hud.CarPlayCallCard.Phase.RINGING
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudCallTest {
    private val words = HudCallWords("Входящий вызов", "Вызов…")
    private val now = 1_000_000L

    private fun call(
        calls: CarPlayCallSnapshot,
        callsActive: Boolean = true,
        engineeringMode: Boolean = true,
    ) = HudCall.of(calls, engineeringMode, { callsActive }, now, { words }, Locale.ROOT)

    private fun ringing(name: String = "", number: String = "") =
        CarPlayCallSnapshot(card = CarPlayCallCard(RINGING, name, number = number))

    private fun talking(seconds: Long, muted: Boolean? = null) = CarPlayCallSnapshot(
        card = CarPlayCallCard(ACTIVE, "Анна Петрова", now - seconds * 1_000, "+7 900 123-45-67"),
        muted = muted,
    )

    @Test fun aRingingCallShowsTheCallerByNameThenNumberOverIncoming() {
        assertEquals(HudCall("Анна Петрова", "Входящий вызов", RINGING), call(ringing("Анна Петрова", "+7 900 123-45-67")))
        assertEquals(HudCall("+7 900 123-45-67", "Входящий вызов", RINGING), call(ringing(number = "+7 900 123-45-67")))
        assertEquals(HudCall("", "Входящий вызов", RINGING), call(ringing()))
        assertEquals(HudCall("", "Входящий вызов", RINGING), call(ringing(" ", " ")))
    }

    @Test fun aDialedCallShowsTheCalleeOverCalling() {
        val dialing = CarPlayCallCard(DIALING, "", number = "+7 900 123-45-67")
        assertEquals(HudCall("+7 900 123-45-67", "Вызов…", DIALING), call(CarPlayCallSnapshot(card = dialing)))
        assertEquals(HudCall("Мама", "Вызов…", DIALING), call(CarPlayCallSnapshot(card = dialing.copy(name = "Мама"))))
        assertEquals(HudCall("", "Вызов…", DIALING), call(CarPlayCallSnapshot(card = dialing.copy(number = ""))))
    }

    @Test fun aConnectedCallShowsTheOtherPartyOverItsTime() {
        assertEquals(HudCall("Анна Петрова", "00:05", ACTIVE), call(talking(5)))
        assertEquals(HudCall("Анна Петрова", "01:05", ACTIVE), call(talking(65)))
        assertEquals(HudCall("Анна Петрова", "1:00:01", ACTIVE), call(talking(3_601)))
    }

    @Test fun theMicrophoneShowsOffOnlyOnceConnectedAndTheIphoneSaidSo() {
        assertEquals(HudCall("Анна Петрова", "00:05", ACTIVE, muted = true), call(talking(5, muted = true)))
        assertEquals(HudCall("Анна Петрова", "00:05", ACTIVE), call(talking(5, muted = false)))
        assertEquals(HudCall("Анна Петрова", "00:05", ACTIVE), call(talking(5, muted = null)))
        assertEquals(HudCall("Анна", "Входящий вызов", RINGING), call(ringing("Анна").copy(muted = true)))
        val dialing = CarPlayCallSnapshot(card = CarPlayCallCard(DIALING, "Мама"), muted = true)
        assertEquals(HudCall("Мама", "Вызов…", DIALING), call(dialing))
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
