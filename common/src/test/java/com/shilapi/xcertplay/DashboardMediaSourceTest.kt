package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardMediaSourceTest {
    private val carPlayPlays = DashboardMediaSignals(carPlayFocus = true, carPlayPlaying = true, carPlayTrack = true)

    private fun source(signals: DashboardMediaSignals) = DashboardMediaSource.of(signals)

    @Test fun carPlayPlayingWithTheFocusIsShown() {
        assertEquals(DashboardMediaSource.CARPLAY, source(carPlayPlays))
        assertEquals("a track without metadata still plays", DashboardMediaSource.CARPLAY,
            source(carPlayPlays.copy(carPlayTrack = false)))
    }

    @Test fun aPausedCarPlayTrackStaysWhileDiPlayKeepsTheFocus() {
        assertEquals(DashboardMediaSource.CARPLAY, source(carPlayPlays.copy(carPlayPlaying = false)))
        assertEquals("nothing to show", DashboardMediaSource.UNKNOWN,
            source(DashboardMediaSignals(carPlayFocus = true)))
    }

    @Test fun losingTheFocusTakesCarPlayAway() {
        assertEquals(DashboardMediaSource.UNKNOWN, source(carPlayPlays.copy(carPlayFocus = false)))
        assertEquals(DashboardMediaSource.RADIO, source(carPlayPlays.copy(carPlayFocus = false, radioPlaying = true)))
    }

    @Test fun bluetoothAndUsbAreOtherSources() {
        assertEquals(DashboardMediaSource.OTHER, source(DashboardMediaSignals(bluetoothPlaying = true)))
        assertEquals(DashboardMediaSource.OTHER, source(DashboardMediaSignals(usbPlaying = true)))
        assertEquals("a paused CarPlay track gives way", DashboardMediaSource.OTHER,
            source(carPlayPlays.copy(carPlayPlaying = false, usbPlaying = true)))
    }

    @Test fun theRadioPlaysWhileDiPlayIsSilent() {
        val radio = DashboardMediaSignals(radioPlaying = true)
        assertEquals(DashboardMediaSource.RADIO, source(radio))
        assertEquals(DashboardMediaSource.RADIO, source(radio.copy(radioServicePlaying = true)))
        assertEquals("CarPlay paused with the focus", DashboardMediaSource.RADIO,
            source(carPlayPlays.copy(carPlayPlaying = false, radioPlaying = true)))
    }

    @Test fun aRadioFlagLeftSetAfterTheRadioPausedDoesNotHideCarPlay() {
        assertEquals(DashboardMediaSource.CARPLAY,
            source(carPlayPlays.copy(radioPlaying = true, radioServicePlaying = false)))
        assertEquals("the service is not bound yet", DashboardMediaSource.UNKNOWN,
            source(carPlayPlays.copy(radioPlaying = true, radioServicePlaying = null)))
    }

    @Test fun disagreeingSignalsShowNothing() {
        assertEquals(DashboardMediaSource.UNKNOWN, source(carPlayPlays.copy(radioPlaying = true)))
        assertEquals(DashboardMediaSource.UNKNOWN, source(carPlayPlays.copy(bluetoothPlaying = true)))
        assertEquals(DashboardMediaSource.UNKNOWN, source(DashboardMediaSignals(radioPlaying = true, usbPlaying = true)))
        assertEquals(DashboardMediaSource.UNKNOWN, source(DashboardMediaSignals(bluetoothPlaying = true, usbPlaying = true)))
        assertEquals("the radio service says it is paused", DashboardMediaSource.UNKNOWN,
            source(DashboardMediaSignals(radioPlaying = true, radioServicePlaying = false)))
        assertEquals(DashboardMediaSource.UNKNOWN, source(DashboardMediaSignals()))
    }

    @Test fun withoutTheFocusCarPlayIsShownWhileItPlaysAndNothingElseDoes() {
        val playing = DashboardMediaSignals(carPlayFocusDisabled = true, carPlayPlaying = true, carPlayTrack = true)
        assertEquals(DashboardMediaSource.CARPLAY, source(playing))
        assertEquals("paused without the focus", DashboardMediaSource.UNKNOWN, source(playing.copy(carPlayPlaying = false)))
        assertEquals(DashboardMediaSource.UNKNOWN, source(playing.copy(radioPlaying = true)))
        assertEquals(DashboardMediaSource.UNKNOWN, source(playing.copy(bluetoothPlaying = true)))
        assertEquals(DashboardMediaSource.UNKNOWN, source(playing.copy(usbPlaying = true)))
        assertEquals(DashboardMediaSource.RADIO, source(playing.copy(carPlayPlaying = false, radioPlaying = true)))
    }

    @Test fun progressRunsOnFromTheLastPositionWhilePlaying() {
        assertEquals(0.5f, DashboardProgress.of(60_000, 1_000, 200_000, true, 41_000)!!, 1e-6f)
        assertEquals("paused", 0.3f, DashboardProgress.of(60_000, 1_000, 200_000, false, 41_000)!!, 1e-6f)
        assertEquals("past the end", 1f, DashboardProgress.of(190_000, 0, 200_000, true, 60_000)!!, 1e-6f)
        assertEquals("a clock before the position", 0.3f, DashboardProgress.of(60_000, 5_000, 200_000, true, 1_000)!!, 1e-6f)
        assertNull("unknown length", DashboardProgress.of(60_000, 0, null, true, 0))
        assertNull(DashboardProgress.of(60_000, 0, 0, true, 0))
        assertNull("unknown position", DashboardProgress.of(null, 0, 200_000, true, 0))
    }
}
