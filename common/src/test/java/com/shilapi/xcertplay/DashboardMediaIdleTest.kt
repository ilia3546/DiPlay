package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardMediaIdleTest {
    private fun shown(signals: DashboardMediaSignals) = DashboardMediaFeed.nothingShown(signals)

    @Test fun itSaysNothingPlaysOnlyWhenNothingSounds() {
        assertEquals(DashboardMedia.Idle, shown(DashboardMediaSignals()))
        assertEquals("the focus alone sounds nothing", DashboardMedia.Idle,
            shown(DashboardMediaSignals(carPlayFocus = true, radioServicePlaying = false)))
        assertEquals("the firmware's radio flag outlives the radio, the radio service says it is silent",
            DashboardMedia.Idle, shown(DashboardMediaSignals(carPlayFocus = true, radioPlaying = true,
                radioServicePlaying = false, carSource = false)))
    }

    @Test fun anySoundingSourceKeepsTheNeutralPlaceholder() {
        for (signals in listOf(
            DashboardMediaSignals(carPlayPlaying = true, radioPlaying = true),
            DashboardMediaSignals(carPlayMediaAudio = true, bluetoothPlaying = true),
            DashboardMediaSignals(carPlayPlaying = true, carPlayTrack = true),
            DashboardMediaSignals(radioPlaying = true),
            DashboardMediaSignals(bluetoothPlaying = true),
            DashboardMediaSignals(usbPlaying = true),
            DashboardMediaSignals(radioServicePlaying = true),
            DashboardMediaSignals(radioPlaying = true, bluetoothPlaying = true),
        )) {
            assertEquals("$signals", DashboardMedia.Empty, shown(signals))
        }
    }

    @Test fun carPlaySoundWithoutATrackSaysSo() {
        for (signals in listOf(
            DashboardMediaSignals(carPlayPlaying = true),
            DashboardMediaSignals(carPlayMediaAudio = true),
            DashboardMediaSignals(carPlayFocus = true, carPlayPlaying = true, carPlayMediaAudio = true),
            DashboardMediaSignals(carPlayMediaAudio = true, radioPlaying = true, radioServicePlaying = false),
        )) {
            assertEquals("$signals", DashboardMedia.Untitled, shown(signals))
        }
    }

    @Test fun aCarPlayTrackIsNeverIdle() {
        assertEquals(DashboardMedia.Empty, shown(DashboardMediaSignals(carPlayTrack = true)))
    }
}
