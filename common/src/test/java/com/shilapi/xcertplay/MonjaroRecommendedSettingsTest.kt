package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.media.MicrophoneInput
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MonjaroRecommendedSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun pendingTitles() = MonjaroRecommendedSettings.pending(context).map { it.title }

    private fun plan(sessionRunning: Boolean = true, overlayPermitted: Boolean = true, locationPermitted: Boolean = true) =
        MonjaroRecommendedSettings.plan(context, sessionRunning, overlayPermitted, locationPermitted)

    private fun applyAll(locationPermitted: Boolean = true) =
        MonjaroRecommendedSettings.apply(context, plan(locationPermitted = locationPermitted))

    @Test fun applyingSavesEveryRecommendedValue() {
        QnxTripCredentials.save(context, "example-password")
        BydOutputSettings.setWheelSpeedToIphone(context, true)
        applyAll()

        assertTrue(AirPlayPersistence.loadRootHotspotDetails(context))
        assertTrue(CarHotspotSettings.rootStart(context))
        assertTrue(AirPlayPersistence.loadDisconnectPhoneBluetooth(context))
        assertTrue(AirPlayPersistence.loadHotspotAudioFirewall(context))
        assertTrue(AirPlayPersistence.loadRequestLostAudio(context))
        assertEquals(MicrophoneInput.AUTO, AirPlayPersistence.loadMicrophoneInput(context))
        assertTrue(AirPlayPersistence.loadPauseCarPlayForCarAudio(context))
        assertTrue(AirPlayPersistence.loadWheelSeek(context))
        assertTrue(AirPlayPersistence.loadDashboardMusic(context))
        assertTrue(AirPlayPersistence.loadDashboardCalls(context))
        assertTrue(AirPlayPersistence.loadCustomDashboard(context))
        assertEquals(600, AirPlayPersistence.loadDashboardMap(context).width)
        assertTrue(AirPlayPersistence.loadDashboardManeuver(context))
        assertEquals(DashboardBackground.NEON, AirPlayPersistence.loadDashboardBackground(context))
        assertEquals(20, AirPlayPersistence.loadDashboardDimmingPercent(context))
        assertTrue(AirPlayPersistence.loadDashboardBottomLine(context))
        assertTrue(AirPlayPersistence.loadDashboardWheelToggle(context))
        assertTrue(AirPlayPersistence.loadHudManeuvers(context))
        assertEquals(260, AirPlayPersistence.loadHudHeight(context))
        assertEquals(50, AirPlayPersistence.loadHudScalePercent(context))
        assertTrue(AirPlayPersistence.loadHudLanes(context))
        assertTrue(AirPlayPersistence.loadHudTrip(context))
        assertTrue(AirPlayPersistence.loadMainBufferedAudio(context))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertTrue(AirPlayPersistence.loadAudioFocusEnabled(context))
        assertTrue(AirPlayPersistence.loadNavigationPromptAudioFocus(context))
        assertTrue(AirPlayPersistence.loadAdvancedAudioChannelMapping(context))
        assertTrue(AirPlayPersistence.loadHevcEnabled(context))
        assertTrue(AirPlayPersistence.loadSmoothVideo(context))
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadSafeAreaDrawOutside(context))
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
        assertFalse(BydOutputSettings.wheelSpeedToIphone(context))
        assertTrue(BydOutputSettings.videoWhileParked(context))
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(context))
        assertTrue(NavigationWheelSettings.enabled(context))
    }

    @Test fun theBydWheelSpeedSwitchIsTurnedOffNotOn() {
        assertFalse(R.string.wheel_speed_for_tunnels in pendingTitles())

        BydOutputSettings.setWheelSpeedToIphone(context, true)
        assertTrue(R.string.wheel_speed_for_tunnels in pendingTitles())
        applyAll()
        assertFalse(BydOutputSettings.wheelSpeedToIphone(context))
    }

    @Test fun locationReportingWaitsForThePermission() {
        val plan = plan(locationPermitted = false)
        MonjaroRecommendedSettings.apply(context, plan)

        assertTrue(plan.askLocation)
        assertFalse(plan.reconnectNow)
        assertTrue(plan.markReconnect)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        assertEquals(listOf(R.string.report_location_to_iphone), pendingTitles())
    }

    @Test fun applyingAgainChangesNothing() {
        QnxTripCredentials.save(context, "example-password")
        applyAll()

        assertEquals(emptyList<Int>(), pendingTitles())
        val again = plan()
        assertEquals(emptyList<Int>(), again.settings.map { it.title })
        assertFalse(again.reconnectNow || again.markReconnect || again.askOverlay || again.askLocation)
    }

    @Test fun onlyTheDifferencesArePending() {
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveHudHeight(context, 260)

        val pending = pendingTitles()
        assertFalse(R.string.smooth_video in pending)
        assertFalse(R.string.settings_hud_height in pending)
        assertTrue(R.string.settings_hud_scale in pending)
    }

    @Test fun aPhotoBackgroundAndItsDimmingStay() {
        AirPlayPersistence.saveDashboardBackground(context, DashboardBackground.CUSTOM_PHOTO)
        AirPlayPersistence.saveDashboardDimmingPercent(context, 40)

        val pending = pendingTitles()
        applyAll()

        assertFalse(R.string.settings_custom_dashboard_background in pending)
        assertFalse(R.string.settings_custom_dashboard_dimming in pending)
        assertEquals(DashboardBackground.CUSTOM_PHOTO, AirPlayPersistence.loadDashboardBackground(context))
        assertEquals(40, AirPlayPersistence.loadDashboardDimmingPercent(context))
    }

    @Test fun theBottomLineStaysOffWithoutTheQnxPassword() {
        val pending = pendingTitles()
        applyAll()

        assertFalse(R.string.settings_custom_dashboard_bottom_line in pending)
        assertFalse(AirPlayPersistence.loadDashboardBottomLine(context))
    }

    @Test fun arrivalAndTheConnectionAreLeftAlone() {
        AirPlayPersistence.saveDashboardArrival(context, false)
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveAutoStartOnBoot(context, false)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(context, false)
        val fps = AirPlayPersistence.loadFps(context)
        val scale = AirPlayPersistence.loadDisplayScalePercent(context)
        val carHotspot = CarHotspotSettings.enabled(context)
        val wiredBuffer = AirPlayPersistence.loadMediaBufferMillis(context, wireless = false)

        applyAll()

        assertFalse(AirPlayPersistence.loadDashboardArrival(context))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(context))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(context))
        assertFalse(AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context))
        assertEquals(fps, AirPlayPersistence.loadFps(context))
        assertEquals(scale, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(carHotspot, CarHotspotSettings.enabled(context))
        assertEquals(wiredBuffer, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }

    @Test fun anImmediateChangeReconnectsARunningSessionAndKeepsTheBar() {
        applyAll()
        AirPlayPersistence.saveSmoothVideo(context, false)
        AirPlayPersistence.saveHevcEnabled(context, false)

        val running = plan()
        assertTrue(running.reconnectNow)
        assertTrue(running.markReconnect)
        val stopped = plan(sessionRunning = false)
        assertFalse(stopped.reconnectNow)
        assertTrue(stopped.markReconnect)
    }

    @Test fun aMissingOverlayPermissionComesBeforeAnyReconnect() {
        val plan = plan(overlayPermitted = false)

        assertTrue(plan.askOverlay)
        assertFalse(plan.reconnectNow)
        assertTrue(plan.markReconnect)
    }

    @Test fun theOverlayIsAskedForOnlyByTheDashboardAndTheHud() {
        applyAll()
        AirPlayPersistence.saveSmoothVideo(context, false)

        val plan = plan(overlayPermitted = false)
        assertFalse(plan.askOverlay)
        assertTrue(plan.reconnectNow)
    }

    @Test fun aNextConnectionChangeOnlyMarksTheReconnect() {
        applyAll()
        AirPlayPersistence.saveHevcEnabled(context, false)

        val plan = plan()
        assertFalse(plan.reconnectNow)
        assertTrue(plan.markReconnect)
    }

    @Test fun anAtOnceChangeNeedsNoReconnect() {
        applyAll()
        AirPlayPersistence.savePauseCarPlayForCarAudio(context, false)
        AirPlayPersistence.saveHudLanes(context, false)

        val plan = plan()
        assertFalse(plan.reconnectNow)
        assertFalse(plan.markReconnect)
    }

    @Test fun theWirelessBufferReconnectsOnlyAWirelessSession() {
        applyAll()
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, 300)

        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertFalse(plan().markReconnect)

        AirPlayPersistence.saveWirelessEnabled(context, true)
        assertTrue(plan().reconnectNow)
    }

    @Test fun hidingTheStatusBarAppliesTheSystemBars() {
        applyAll()
        AirPlayPersistence.saveHideTopBar(context, false)

        val plan = plan()
        assertTrue(plan.applySystemBars)
        assertFalse(plan.markReconnect)
    }
}
