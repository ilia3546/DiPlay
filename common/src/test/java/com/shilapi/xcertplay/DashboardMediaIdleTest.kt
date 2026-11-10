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
            DashboardMediaSignals(carPlayPlaying = true),
            DashboardMediaSignals(radioPlaying = true),
            DashboardMediaSignals(bluetoothPlaying = true),
            DashboardMediaSignals(usbPlaying = true),
            DashboardMediaSignals(radioServicePlaying = true),
            DashboardMediaSignals(radioPlaying = true, bluetoothPlaying = true),
        )) {
            assertEquals("$signals", DashboardMedia.Empty, shown(signals))
        }
    }

    @Test fun aCarPlayTrackIsNeverIdle() {
        assertEquals(DashboardMedia.Empty, shown(DashboardMediaSignals(carPlayTrack = true)))
    }
}
