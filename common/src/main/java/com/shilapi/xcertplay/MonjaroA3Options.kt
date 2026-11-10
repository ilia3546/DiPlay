package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.HudSettings
import com.shilapi.xcertplay.media.MicrophoneInput
import com.shilapi.xcertplay.network.GeelyHeadUnit

/** The Geely Monjaro A3 card's choices as a connection uses them; all off or AUTO on any other head unit. */
internal data class MonjaroA3Options(
    val rootHotspotDetails: Boolean = false,
    val disconnectPhoneBluetooth: Boolean = false,
    val hotspotAudioFirewall: Boolean = false,
    val requestLostAudio: Boolean = false,
    val microphoneInput: MicrophoneInput = MicrophoneInput.AUTO,
    val dashboardMusic: Boolean = false,
    val dashboardCalls: Boolean = false,
    val pauseCarPlayForCarAudio: Boolean = false,
    val wheelSeek: Boolean = false,
    val customDashboard: Boolean = false,
    val dashboardWheelToggle: Boolean = false,
    val dashboardBackground: DashboardBackground = DashboardBackground.OCEAN,
    val dashboardDimmingPercent: Int = 0,
    val dashboardMap: MonjaroDashboardLayout.MapGeometry = MonjaroDashboardLayout.defaultMap,
    val dashboardIconBackdrop: Boolean = false,
    val dashboardManeuver: Boolean = true,
    val dashboardArrival: Boolean = true,
    val dashboardBottomLine: Boolean = false,
    val hudManeuvers: Boolean = false,
    val hudDensity: HudSettings.Variant = HudSettings.Variant.BALANCED,
    val hudHeight: Int = HudSettings.DEFAULT_Y,
    val hudScalePercent: Int = HudSettings.DEFAULT_SCALE,
    val hudLanes: Boolean = true,
    val hudTrip: Boolean = true,
) {
    /** The HUD line's drawing settings. */
    val hudSettings: HudSettings get() = HudSettings(hudDensity, hudHeight, hudScalePercent, hudLanes, hudTrip)

    companion object {
        /** The rows the HUD line may sit on, top to bottom, in the HUD's 800x480 pixels. */
        val hudHeights = listOf(120, 170, HudSettings.DEFAULT_Y, 260, 310)
        val hudScalePercents = listOf(HudSettings.MIN_SCALE, HudSettings.DEFAULT_SCALE, 80, HudSettings.MAX_SCALE)

        fun load(context: Context, monjaroA3: Boolean = GeelyHeadUnit.monjaroA3): MonjaroA3Options {
            if (!monjaroA3) return MonjaroA3Options()
            return MonjaroA3Options(
                rootHotspotDetails = AirPlayPersistence.loadRootHotspotDetails(context),
                disconnectPhoneBluetooth = AirPlayPersistence.loadDisconnectPhoneBluetooth(context),
                hotspotAudioFirewall = AirPlayPersistence.loadHotspotAudioFirewall(context),
                requestLostAudio = AirPlayPersistence.loadRequestLostAudio(context),
                microphoneInput = AirPlayPersistence.loadMicrophoneInput(context),
                dashboardMusic = AirPlayPersistence.loadDashboardMusic(context),
                dashboardCalls = AirPlayPersistence.loadDashboardCalls(context),
                pauseCarPlayForCarAudio = AirPlayPersistence.loadPauseCarPlayForCarAudio(context),
                wheelSeek = AirPlayPersistence.loadWheelSeek(context),
                customDashboard = AirPlayPersistence.loadCustomDashboard(context),
                dashboardWheelToggle = AirPlayPersistence.loadDashboardWheelToggle(context),
                dashboardBackground = AirPlayPersistence.loadDashboardBackground(context),
                dashboardDimmingPercent = AirPlayPersistence.loadDashboardDimmingPercent(context),
                dashboardMap = AirPlayPersistence.loadDashboardMap(context),
                dashboardIconBackdrop = AirPlayPersistence.loadDashboardIconBackdrop(context),
                dashboardManeuver = AirPlayPersistence.loadDashboardManeuver(context),
                dashboardArrival = AirPlayPersistence.loadDashboardArrival(context),
                dashboardBottomLine = AirPlayPersistence.loadDashboardBottomLine(context),
                hudManeuvers = AirPlayPersistence.loadHudManeuvers(context),
                hudDensity = AirPlayPersistence.loadHudDensity(context),
                hudHeight = AirPlayPersistence.loadHudHeight(context),
                hudScalePercent = AirPlayPersistence.loadHudScalePercent(context),
                hudLanes = AirPlayPersistence.loadHudLanes(context),
                hudTrip = AirPlayPersistence.loadHudTrip(context),
            )
        }
    }
}
