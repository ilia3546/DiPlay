package com.shilapi.xcertplay

import com.shilapi.xcertplay.GeelyCallKeys.Action
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.CarPlayCallCard
import org.junit.Assert.assertEquals
import org.junit.Test

class GeelyCallKeysTest {
    private val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Anna")
    private val dialing = CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "Anna")
    private val active = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Anna", 1L)
    private val routed = mutableListOf<String>()
    // Stands for CarPlayMediaKeys.onExternalMediaKey while the car's own source owns the wheel.
    private val carSourceOwnsTheWheel: (Int) -> Unit = { routed += "left to the car $it" }

    @Test fun eachKeyDuringEachPhase() {
        val table = listOf(
            Triple(CarPlayMediaButton.PREVIOUS, ringing, Action.END),
            Triple(CarPlayMediaButton.NEXT, ringing, Action.ANSWER),
            Triple(CarPlayMediaButton.PLAY_PAUSE, ringing, Action.PASS),
            Triple(CarPlayMediaButton.PREVIOUS, dialing, Action.MUTE),
            Triple(CarPlayMediaButton.NEXT, dialing, Action.END),
            Triple(CarPlayMediaButton.PLAY_PAUSE, dialing, Action.PASS),
            Triple(CarPlayMediaButton.PREVIOUS, active, Action.MUTE),
            Triple(CarPlayMediaButton.NEXT, active, Action.END),
            Triple(CarPlayMediaButton.PLAY_PAUSE, active, Action.PASS),
            Triple(CarPlayMediaButton.PREVIOUS, null, Action.PASS),
            Triple(CarPlayMediaButton.NEXT, null, Action.PASS),
            Triple(CarPlayMediaButton.PLAY_PAUSE, null, Action.PASS),
        )
        table.forEach { (index, card, expected) ->
            assertEquals("$index ${card?.phase}", expected, GeelyCallKeys.action(index, card))
        }
    }

    private fun route(index: Int, active: Boolean = true, card: CarPlayCallCard? = ringing,
        bluetoothAudio: Boolean = false, media: (Int) -> Unit = { routed += "media $it" }) =
        GeelyCallKeys.route(index, active, bluetoothAudio, card, call = { routed += "call $it" }, media = media,
            carBluetooth = { routed += "car Bluetooth $it" })

    @Test fun duringACallTheKeyGoesToTheCallAndNotToTheIphoneAsMedia() {
        route(CarPlayMediaButton.NEXT)
        route(CarPlayMediaButton.PREVIOUS, card = active)
        assertEquals(listOf("call ANSWER", "call MUTE"), routed)
    }

    @Test fun withoutACallTheKeyGoesToTheMediaKeysAsBefore() {
        route(CarPlayMediaButton.NEXT, card = null)
        route(CarPlayMediaButton.PLAY_PAUSE, card = active)
        assertEquals(listOf("media ${CarPlayMediaButton.NEXT}", "media ${CarPlayMediaButton.PLAY_PAUSE}"), routed)
    }

    @Test fun whileTheOptionDoesNotActTheKeyGoesToTheMediaKeys() {
        route(CarPlayMediaButton.NEXT, active = false)
        route(CarPlayMediaButton.PREVIOUS, active = false, card = active)
        assertEquals(listOf("media ${CarPlayMediaButton.NEXT}", "media ${CarPlayMediaButton.PREVIOUS}"), routed)
    }

    @Test fun theCallIsHandledEvenWhileTheCarsSourceOwnsTheWheel() {
        route(CarPlayMediaButton.NEXT, card = active, media = carSourceOwnsTheWheel)
        route(CarPlayMediaButton.NEXT, card = null, media = carSourceOwnsTheWheel)
        assertEquals(listOf("call END", "left to the car ${CarPlayMediaButton.NEXT}"), routed)
    }

    @Test fun overTheCarsBluetoothOnlyACallTakesTheKeyAndNothingElseReachesTheIphone() {
        route(CarPlayMediaButton.NEXT, bluetoothAudio = true)
        route(CarPlayMediaButton.NEXT, card = null, bluetoothAudio = true)
        route(CarPlayMediaButton.PREVIOUS, active = false, bluetoothAudio = true)
        assertEquals(listOf("call ANSWER", "car Bluetooth ${CarPlayMediaButton.NEXT}",
            "car Bluetooth ${CarPlayMediaButton.PREVIOUS}"), routed)
    }
}
