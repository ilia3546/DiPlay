package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With CarPlay audio on the car's Bluetooth, the head unit's own AVRCP already drives the iPhone's
 * music. Registering the wheel's media keys over iAP2 as well makes play/pause and skip double-fire,
 * so only the microphone key (long press -> Siri) may be wired then.
 */
class GeelyWheelKeyCodesTest {

    @Test fun bluetoothAudioLeavesMediaKeysToTheHeadUnit() {
        assertEquals(
            listOf(GeelyWheelKeyCodes.KEY_VOICE_ASSIST),
            GeelyWheelKeyCodes.registeredCodes(bluetoothAudio = true, calls = false).toList(),
        )
    }

    @Test fun withoutBluetoothAudioAllWheelKeysAreWired() {
        assertEquals(
            listOf(
                GeelyWheelKeyCodes.KEY_PLAY_PAUSE,
                GeelyWheelKeyCodes.KEY_NEXT,
                GeelyWheelKeyCodes.KEY_PREVIOUS,
                GeelyWheelKeyCodes.KEY_VOICE_ASSIST,
            ),
            GeelyWheelKeyCodes.registeredCodes(bluetoothAudio = false, calls = false).toList(),
        )
    }

    @Test fun theSiriKeyIsAlwaysWired() {
        listOf(true, false).forEach { bluetooth ->
            assertTrue(
                "voice key must be registered when bluetoothAudio=$bluetooth",
                GeelyWheelKeyCodes.KEY_VOICE_ASSIST in
                    GeelyWheelKeyCodes.registeredCodes(bluetooth, calls = false).toList(),
            )
        }
    }

    @Test fun mediaKeysAreOnlyWiredWithoutBluetoothAudio() {
        val mediaKeys = listOf(
            GeelyWheelKeyCodes.KEY_PLAY_PAUSE,
            GeelyWheelKeyCodes.KEY_NEXT,
            GeelyWheelKeyCodes.KEY_PREVIOUS,
        )
        mediaKeys.forEach { key ->
            assertFalse(
                "media key $key must not double-fire over the car's Bluetooth",
                key in GeelyWheelKeyCodes.registeredCodes(bluetoothAudio = true, calls = false).toList(),
            )
            assertTrue(
                "media key $key must reach CarPlay when the car's Bluetooth is not used",
                key in GeelyWheelKeyCodes.registeredCodes(bluetoothAudio = false, calls = false).toList(),
            )
        }
    }

    @Test fun theCallsOptionWiresPreviousAndNextButNotPlayPauseOverTheCarsBluetooth() {
        assertEquals(
            listOf(GeelyWheelKeyCodes.KEY_NEXT, GeelyWheelKeyCodes.KEY_PREVIOUS, GeelyWheelKeyCodes.KEY_VOICE_ASSIST),
            GeelyWheelKeyCodes.registeredCodes(bluetoothAudio = true, calls = true).toList(),
        )
        assertEquals(
            GeelyWheelKeyCodes.ALL.toList(),
            GeelyWheelKeyCodes.registeredCodes(bluetoothAudio = false, calls = true).toList(),
        )
    }

    @Test fun theDashboardToggleAddsPlayPauseOnlyWhereItIsNotForwarded() {
        listOf(true, false).forEach { bluetooth ->
            listOf(true, false).forEach { calls ->
                val forwarded = GeelyWheelKeyCodes.forwardedCodes(bluetooth, calls).toList()
                assertEquals(forwarded, GeelyWheelKeyCodes.registeredCodes(bluetooth, calls).toList())
                val withToggle = GeelyWheelKeyCodes.registeredCodes(bluetooth, calls, dashboardToggle = true).toList()
                val expected = if (GeelyWheelKeyCodes.KEY_PLAY_PAUSE in forwarded) forwarded
                    else forwarded + GeelyWheelKeyCodes.KEY_PLAY_PAUSE
                assertEquals(expected, withToggle)
            }
        }
    }

    @Test fun playPauseIsNotForwardedOverTheCarsBluetoothEvenWhenRegisteredForTheToggle() {
        listOf(true, false).forEach { calls ->
            assertTrue(GeelyWheelKeyCodes.KEY_PLAY_PAUSE in
                GeelyWheelKeyCodes.registeredCodes(bluetoothAudio = true, calls = calls, dashboardToggle = true).toList())
            assertFalse(GeelyWheelKeyCodes.KEY_PLAY_PAUSE in
                GeelyWheelKeyCodes.forwardedCodes(bluetoothAudio = true, calls = calls).toList())
        }
    }
}
