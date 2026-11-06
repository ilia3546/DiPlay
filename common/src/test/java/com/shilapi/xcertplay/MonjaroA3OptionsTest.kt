package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.MicrophoneInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MonjaroA3OptionsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun turnEverythingOn() {
        AirPlayPersistence.saveRootHotspotDetails(context, true)
        AirPlayPersistence.saveDisconnectPhoneBluetooth(context, true)
        AirPlayPersistence.saveHotspotAudioFirewall(context, true)
        AirPlayPersistence.saveMicrophoneInput(context, MicrophoneInput.ALL_CABIN_MICROPHONES)
        AirPlayPersistence.saveDashboardMusic(context, true)
        AirPlayPersistence.saveCustomDashboard(context, true)
        AirPlayPersistence.saveDashboardBackground(context, DashboardBackground.NEON)
        AirPlayPersistence.saveDashboardDimmingPercent(context, 40)
    }

    @Test fun everyOptionIsOffByDefault() {
        assertEquals(MonjaroA3Options(), MonjaroA3Options.load(context, monjaroA3 = true))
    }

    @Test fun savedOptionsStayOffOnAnyOtherHeadUnit() {
        turnEverythingOn()
        assertEquals(MonjaroA3Options(), MonjaroA3Options.load(context, monjaroA3 = false))
        assertEquals(MonjaroA3Options(), MonjaroA3Options.load(context))
    }

    @Test fun theMonjaroA3UsesWhatTheDriverSaved() {
        turnEverythingOn()
        ShadowBuild.setProduct("geely_fx11")
        ShadowBuild.setModel("KX11-A3-L1")
        assertEquals(MonjaroA3Options(rootHotspotDetails = true, disconnectPhoneBluetooth = true, hotspotAudioFirewall = true,
            microphoneInput = MicrophoneInput.ALL_CABIN_MICROPHONES, dashboardMusic = true, customDashboard = true,
            dashboardBackground = DashboardBackground.NEON, dashboardDimmingPercent = 40), MonjaroA3Options.load(context))
    }

    @Test fun theCustomDashboardIsOffOnTheOceanPresetWithoutDimmingByDefault() {
        val options = MonjaroA3Options.load(context, monjaroA3 = true)
        assertFalse(options.customDashboard)
        assertEquals(DashboardBackground.OCEAN, options.dashboardBackground)
        assertEquals(0, options.dashboardDimmingPercent)
    }

    @Test fun aPhotoIsDimmedUntilTheDriverChoosesADimming() {
        AirPlayPersistence.saveDashboardBackground(context, DashboardBackground.CUSTOM_PHOTO)
        assertEquals(20, MonjaroA3Options.load(context, monjaroA3 = true).dashboardDimmingPercent)
        AirPlayPersistence.saveDashboardDimmingPercent(context, 0)
        assertEquals(0, MonjaroA3Options.load(context, monjaroA3 = true).dashboardDimmingPercent)
    }

    @Test fun anUnknownSavedDimmingFallsBackToTheDefault() {
        AirPlayPersistence.saveDashboardDimmingPercent(context, 35)
        assertEquals(0, MonjaroA3Options.load(context, monjaroA3 = true).dashboardDimmingPercent)
    }
}
