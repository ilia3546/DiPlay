package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.HudSettings
import com.shilapi.xcertplay.media.MicrophoneInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        AirPlayPersistence.saveRequestLostAudio(context, true)
        AirPlayPersistence.saveMicrophoneInput(context, MicrophoneInput.ALL_CABIN_MICROPHONES)
        AirPlayPersistence.saveDashboardMusic(context, true)
        AirPlayPersistence.saveDashboardCalls(context, true)
        AirPlayPersistence.saveWheelSeek(context, true)
        AirPlayPersistence.saveCustomDashboard(context, true)
        AirPlayPersistence.saveDashboardWheelToggle(context, true)
        AirPlayPersistence.saveDashboardBackground(context, DashboardBackground.NEON)
        AirPlayPersistence.saveDashboardDimmingPercent(context, 40)
        AirPlayPersistence.saveDashboardIconBackdrop(context, true)
        AirPlayPersistence.saveHudManeuvers(context, true)
        AirPlayPersistence.saveHudDensity(context, HudSettings.Variant.BOLD)
        AirPlayPersistence.saveHudHeight(context, 260)
        AirPlayPersistence.saveHudScalePercent(context, 80)
        AirPlayPersistence.saveHudLanes(context, false)
        AirPlayPersistence.saveHudTrip(context, false)
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
            requestLostAudio = true, microphoneInput = MicrophoneInput.ALL_CABIN_MICROPHONES, dashboardMusic = true, dashboardCalls = true, wheelSeek = true, customDashboard = true,
            dashboardWheelToggle = true, dashboardBackground = DashboardBackground.NEON, dashboardDimmingPercent = 40,
            dashboardIconBackdrop = true, hudManeuvers = true,
            hudDensity = HudSettings.Variant.BOLD, hudHeight = 260, hudScalePercent = 80, hudLanes = false, hudTrip = false),
            MonjaroA3Options.load(context))
    }

    @Test fun dashboardCallsAreOffByDefaultAndOffOnAnyOtherHeadUnit() {
        assertFalse(MonjaroA3Options.load(context, monjaroA3 = true).dashboardCalls)
        AirPlayPersistence.saveDashboardCalls(context, true)
        assertTrue(MonjaroA3Options.load(context, monjaroA3 = true).dashboardCalls)
        assertFalse(MonjaroA3Options.load(context, monjaroA3 = false).dashboardCalls)
    }

    @Test fun requestingLostAudioIsOffByDefaultAndOffOnAnyOtherHeadUnit() {
        assertFalse(MonjaroA3Options.load(context, monjaroA3 = true).requestLostAudio)
        AirPlayPersistence.saveRequestLostAudio(context, true)
        assertTrue(MonjaroA3Options.load(context, monjaroA3 = true).requestLostAudio)
        assertFalse(MonjaroA3Options.load(context, monjaroA3 = false).requestLostAudio)
    }

    @Test fun theIconBackdropIsOffByDefaultAndOffOnAnyOtherHeadUnit() {
        assertFalse(MonjaroA3Options.load(context, monjaroA3 = true).dashboardIconBackdrop)
        AirPlayPersistence.saveDashboardIconBackdrop(context, true)
        assertTrue(MonjaroA3Options.load(context, monjaroA3 = true).dashboardIconBackdrop)
        assertFalse(MonjaroA3Options.load(context, monjaroA3 = false).dashboardIconBackdrop)
    }

    @Test fun theHudIsOffAndDrawsTheBalancedLineAt215And65PercentWithLanesAndTripByDefault() {
        val options = MonjaroA3Options.load(context, monjaroA3 = true)
        assertFalse(options.hudManeuvers)
        val settings = options.hudSettings
        assertEquals(HudSettings.Variant.BALANCED, settings.variant)
        assertEquals(215, settings.centerY)
        assertEquals(65, settings.scalePercent)
        assertTrue(settings.showLanes)
        assertTrue(settings.showTrip)
    }

    @Test fun everyHudPresetIsInsideTheDrawingRange() {
        assertEquals(listOf(120, 170, 215, 260, 310), MonjaroA3Options.hudHeights)
        assertEquals(listOf(50, 65, 80, 100), MonjaroA3Options.hudScalePercents)
        for (height in MonjaroA3Options.hudHeights) for (scale in MonjaroA3Options.hudScalePercents) {
            HudSettings(HudSettings.Variant.BOLD, height, scale, true, true)
        }
    }

    @Test fun anUnknownSavedHudPresetFallsBackToTheDefault() {
        AirPlayPersistence.saveHudHeight(context, 200)
        AirPlayPersistence.saveHudScalePercent(context, 70)
        val options = MonjaroA3Options.load(context, monjaroA3 = true)
        assertEquals(215, options.hudHeight)
        assertEquals(65, options.hudScalePercent)
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
