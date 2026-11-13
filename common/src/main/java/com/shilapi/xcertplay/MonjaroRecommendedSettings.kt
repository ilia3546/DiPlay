package com.shilapi.xcertplay

import android.content.Context
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.media.MicrophoneInput
import com.shilapi.xcertplay.network.CarHotspotSettings

/**
 * The settings tested on the Geely Monjaro A3, applied in one step from its card. Each one is saved with the same
 * calls as its own control in Settings; what only the Settings screen can do is in its [Plan].
 */
internal object MonjaroRecommendedSettings {
    /** When a changed setting reaches CarPlay, as its own control in Settings applies it. */
    enum class Reconnect { NONE, NEXT_CONNECTION, NOW }

    enum class Effect { NONE, OVERLAY, SYSTEM_BARS, LOCATION_REPORTING }

    class Setting internal constructor(
        @StringRes val title: Int,
        val reconnect: Reconnect,
        val effect: Effect,
        internal val applied: (Context) -> Boolean,
        internal val save: (Context) -> Unit,
    )

    /** The pending [settings] and what the Settings screen does around saving them. */
    data class Plan(
        val settings: List<Setting>,
        val askOverlay: Boolean,
        val askLocation: Boolean,
        val applySystemBars: Boolean,
        val markReconnect: Boolean,
        val reconnectNow: Boolean,
    )

    const val MAP_WIDTH = 600
    const val DIMMING_PERCENT = 20
    const val HUD_HEIGHT = 260
    const val HUD_SCALE_PERCENT = 50
    const val WIRELESS_MUSIC_BUFFER_MILLIS = 1000
    val BACKGROUND = DashboardBackground.NEON

    /** The settings that differ from the recommended values. */
    fun pending(context: Context): List<Setting> = settings(context).filterNot { it.applied(context) }

    fun plan(context: Context, sessionRunning: Boolean, overlayPermitted: Boolean, locationPermitted: Boolean): Plan {
        val settings = pending(context)
        val askOverlay = !overlayPermitted && settings.any { it.effect == Effect.OVERLAY }
        val askLocation = !locationPermitted && settings.any { it.effect == Effect.LOCATION_REPORTING }
        val reconnect = settings.maxOfOrNull { it.reconnect } ?: Reconnect.NONE
        return Plan(
            settings = settings,
            askOverlay = askOverlay,
            askLocation = askLocation,
            applySystemBars = settings.any { it.effect == Effect.SYSTEM_BARS },
            // Stays shown when the reconnect stops early; a new session is no longer pending by itself.
            markReconnect = reconnect != Reconnect.NONE,
            // A permission screen comes first; the driver reconnects from the bar after it.
            reconnectNow = sessionRunning && reconnect == Reconnect.NOW && !askOverlay && !askLocation,
        )
    }

    /** Saves the settings of [plan]; location reporting waits for the permission when [Plan.askLocation] is set. */
    fun apply(context: Context, plan: Plan) {
        plan.settings.filterNot { plan.askLocation && it.effect == Effect.LOCATION_REPORTING }.forEach { it.save(context) }
    }

    private fun setting(
        @StringRes title: Int,
        reconnect: Reconnect = Reconnect.NONE,
        effect: Effect = Effect.NONE,
        applied: (Context) -> Boolean,
        save: (Context) -> Unit,
    ) = Setting(title, reconnect, effect, applied, save)

    private fun settings(context: Context): List<Setting> = buildList {
        add(setting(R.string.settings_root_hotspot_details, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadRootHotspotDetails) { AirPlayPersistence.saveRootHotspotDetails(it, true) })
        add(setting(R.string.settings_root_hotspot_start, applied = CarHotspotSettings::rootStart) {
            CarHotspotSettings.setRootStart(it, true)
        })
        add(setting(R.string.settings_disconnect_phone_bluetooth, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadDisconnectPhoneBluetooth) { AirPlayPersistence.saveDisconnectPhoneBluetooth(it, true) })
        add(setting(R.string.settings_hotspot_audio_firewall, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadHotspotAudioFirewall) { AirPlayPersistence.saveHotspotAudioFirewall(it, true) })
        add(setting(R.string.settings_request_lost_audio, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadRequestLostAudio) { AirPlayPersistence.saveRequestLostAudio(it, true) })
        add(setting(R.string.settings_microphone_input,
            applied = { AirPlayPersistence.loadMicrophoneInput(it) == MicrophoneInput.AUTO }) {
            AirPlayPersistence.saveMicrophoneInput(it, MicrophoneInput.AUTO)
        })
        add(setting(R.string.settings_pause_carplay_for_car_audio,
            applied = AirPlayPersistence::loadPauseCarPlayForCarAudio) { AirPlayPersistence.savePauseCarPlayForCarAudio(it, true) })
        add(setting(R.string.settings_wheel_seek, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadWheelSeek) { AirPlayPersistence.saveWheelSeek(it, true) })
        add(setting(R.string.settings_dashboard_music, applied = AirPlayPersistence::loadDashboardMusic) {
            MonjaroA3Settings.setDashboardMusic(it, true)
        })
        add(setting(R.string.settings_dashboard_calls, applied = AirPlayPersistence::loadDashboardCalls) {
            MonjaroA3Settings.setDashboardCalls(it, true)
        })
        add(setting(R.string.settings_custom_dashboard, Reconnect.NEXT_CONNECTION, Effect.OVERLAY,
            applied = AirPlayPersistence::loadCustomDashboard) {
            MonjaroA3Settings.setCustomDashboard(it, true)
        })
        add(setting(R.string.settings_custom_dashboard_map_width, Reconnect.NEXT_CONNECTION,
            applied = { AirPlayPersistence.loadDashboardMap(it).width == MAP_WIDTH }) {
            MonjaroA3Settings.setDashboardMapWidth(it, MAP_WIDTH)
        })
        add(setting(R.string.settings_custom_dashboard_maneuver, applied = AirPlayPersistence::loadDashboardManeuver) {
            MonjaroA3Settings.setDashboardManeuver(it, true)
        })
        // A photo is the driver's own choice, and its dimming goes with it.
        if (AirPlayPersistence.loadDashboardBackground(context) != DashboardBackground.CUSTOM_PHOTO) {
            add(setting(R.string.settings_custom_dashboard_background,
                applied = { AirPlayPersistence.loadDashboardBackground(it) == BACKGROUND }) {
                MonjaroA3Settings.setDashboardBackground(it, BACKGROUND)
            })
            add(setting(R.string.settings_custom_dashboard_dimming,
                applied = { AirPlayPersistence.loadDashboardDimmingPercent(it) == DIMMING_PERCENT }) {
                MonjaroA3Settings.setDashboardDimmingPercent(it, DIMMING_PERCENT)
            })
        }
        // Settings offers the bottom line only once the QNX password is saved.
        if (QnxTripCredentials.load(context) != null) {
            add(setting(R.string.settings_custom_dashboard_bottom_line, applied = AirPlayPersistence::loadDashboardBottomLine) {
                AirPlayPersistence.saveDashboardBottomLine(it, true)
            })
        }
        add(setting(R.string.settings_custom_dashboard_wheel_toggle, applied = AirPlayPersistence::loadDashboardWheelToggle) {
            MonjaroA3Settings.setDashboardWheelToggle(it, true)
        })
        add(setting(R.string.settings_hud_maneuvers, effect = Effect.OVERLAY, applied = AirPlayPersistence::loadHudManeuvers) {
            MonjaroA3Settings.setHudManeuvers(it, true)
        })
        add(setting(R.string.settings_hud_height, applied = { AirPlayPersistence.loadHudHeight(it) == HUD_HEIGHT }) {
            MonjaroA3Settings.setHudHeight(it, HUD_HEIGHT)
        })
        add(setting(R.string.settings_hud_scale, applied = { AirPlayPersistence.loadHudScalePercent(it) == HUD_SCALE_PERCENT }) {
            MonjaroA3Settings.setHudScalePercent(it, HUD_SCALE_PERCENT)
        })
        add(setting(R.string.settings_hud_lanes, applied = AirPlayPersistence::loadHudLanes) {
            MonjaroA3Settings.setHudLanes(it, true)
        })
        add(setting(R.string.settings_hud_trip, applied = AirPlayPersistence::loadHudTrip) {
            MonjaroA3Settings.setHudTrip(it, true)
        })
        add(setting(R.string.efficient_video, Reconnect.NEXT_CONNECTION, applied = AirPlayPersistence::loadHevcEnabled) {
            AirPlayPersistence.saveHevcEnabled(it, true)
        })
        add(setting(R.string.smooth_video, Reconnect.NOW, applied = AirPlayPersistence::loadSmoothVideo) {
            AirPlayPersistence.saveSmoothVideo(it, true)
        })
        add(setting(R.string.hide_the_status_bar, effect = Effect.SYSTEM_BARS, applied = AirPlayPersistence::loadHideTopBar) {
            AirPlayPersistence.saveHideTopBar(it, true)
        })
        add(setting(R.string.draw_outside_safe_area, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadSafeAreaDrawOutside) { AirPlayPersistence.saveSafeAreaDrawOutside(it, true) })
        add(setting(R.string.contrib_audio_home_toggle_audio_focus, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadAudioFocusEnabled) { AirPlayPersistence.saveAudioFocusEnabled(it, true) })
        add(setting(R.string.settings_navigation_prompt_audio_focus, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadNavigationPromptAudioFocus) { AirPlayPersistence.saveNavigationPromptAudioFocus(it, true) })
        add(setting(R.string.advanced_audio_channel_mapping, Reconnect.NEXT_CONNECTION,
            applied = AirPlayPersistence::loadAdvancedAudioChannelMapping) { AirPlayPersistence.saveAdvancedAudioChannelMapping(it, true) })
        add(setting(R.string.main_buffered_audio, Reconnect.NOW, applied = AirPlayPersistence::loadMainBufferedAudio) {
            AirPlayPersistence.saveMainBufferedAudio(it, true)
        })
        add(setting(R.string.settings_music_buffer_wireless,
            // As in Settings: only a wireless session plays the wireless buffer.
            if (AirPlayPersistence.loadWirelessEnabled(context)) Reconnect.NOW else Reconnect.NONE,
            applied = { AirPlayPersistence.loadMediaBufferMillis(it, wireless = true) == WIRELESS_MUSIC_BUFFER_MILLIS }) {
            AirPlayPersistence.saveMediaBufferMillis(it, wireless = true, WIRELESS_MUSIC_BUFFER_MILLIS)
        })
        add(setting(R.string.report_location_to_iphone, Reconnect.NOW, Effect.LOCATION_REPORTING,
            applied = AirPlayPersistence::loadLocationReportingEnabled) { AirPlayPersistence.saveLocationReportingEnabled(it, true) })
        // Saved without the BYD ADB check of their Settings switches. With ECARX the gear and the wheel speed come from
        // the car's own service; the BYD switch on would send BYD's wheel speed instead.
        add(setting(R.string.wheel_speed_for_tunnels, Reconnect.NOW, applied = { !BydOutputSettings.wheelSpeedToIphone(it) }) {
            BydOutputSettings.setWheelSpeedToIphone(it, false)
        })
        add(setting(R.string.video_while_parked, Reconnect.NOW, applied = BydOutputSettings::videoWhileParked) {
            BydOutputSettings.setVideoWhileParked(it, true)
        })
        add(setting(R.string.carplay_map_on_instrument_cluster_experimental, Reconnect.NOW,
            applied = AirPlayPersistence::loadClusterMapEnabled) { AirPlayPersistence.saveClusterMapEnabled(it, true) })
        add(setting(R.string.settings_navigation_wheel_volume, applied = NavigationWheelSettings::enabled) {
            NavigationWheelSettings.setEnabled(it, true)
        })
    }
}
