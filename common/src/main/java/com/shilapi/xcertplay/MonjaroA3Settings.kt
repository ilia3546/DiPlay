package com.shilapi.xcertplay

import android.content.Context

/** Saves a Geely Monjaro A3 card choice and tells the running dashboard, HUD and wheel keys about it. */
internal object MonjaroA3Settings {
    fun setDashboardMusic(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveDashboardMusic(context, enabled)
        MonjaroDashboardMusic.settingChanged(context)
    }

    fun setDashboardCalls(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveDashboardCalls(context, enabled)
        MonjaroDashboardCall.settingChanged(context)
    }

    fun setCustomDashboard(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveCustomDashboard(context, enabled)
        MonjaroCustomDashboard.settingChanged()
        DesayWheelKeys.refresh()
    }

    fun setDashboardMapWidth(context: Context, width: Int) {
        AirPlayPersistence.saveDashboardMapWidth(context, width)
        MonjaroCustomDashboard.settingChanged()
    }

    fun setDashboardManeuver(context: Context, shown: Boolean) {
        AirPlayPersistence.saveDashboardManeuver(context, shown)
        MonjaroCustomDashboard.settingChanged()
    }

    fun setDashboardArrival(context: Context, shown: Boolean) {
        AirPlayPersistence.saveDashboardArrival(context, shown)
        MonjaroCustomDashboard.settingChanged()
    }

    fun setDashboardBackground(context: Context, background: DashboardBackground) {
        AirPlayPersistence.saveDashboardBackground(context, background)
        MonjaroCustomDashboard.settingChanged()
    }

    fun setDashboardDimmingPercent(context: Context, percent: Int) {
        AirPlayPersistence.saveDashboardDimmingPercent(context, percent)
        MonjaroCustomDashboard.settingChanged()
    }

    fun setDashboardWheelToggle(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveDashboardWheelToggle(context, enabled)
        DesayWheelKeys.refresh()
        if (!enabled) MonjaroCustomDashboard.wheelToggleTurnedOff()
    }

    fun setHudManeuvers(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveHudManeuvers(context, enabled)
        MonjaroHud.settingChanged()
    }

    fun setHudHeight(context: Context, height: Int) {
        AirPlayPersistence.saveHudHeight(context, height)
        MonjaroHud.settingChanged()
    }

    fun setHudScalePercent(context: Context, percent: Int) {
        AirPlayPersistence.saveHudScalePercent(context, percent)
        MonjaroHud.settingChanged()
    }

    fun setHudLanes(context: Context, shown: Boolean) {
        AirPlayPersistence.saveHudLanes(context, shown)
        MonjaroHud.settingChanged()
    }

    fun setHudTrip(context: Context, shown: Boolean) {
        AirPlayPersistence.saveHudTrip(context, shown)
        MonjaroHud.settingChanged()
    }
}
