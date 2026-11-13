package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class MonjaroRecommendedSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @Before fun geelyMonjaroA3() {
        ShadowBuild.setProduct("geely_fx11")
        ShadowBuild.setModel("KX11-A3-L1")
    }

    @After fun tearDown() {
        activity?.finish()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
    }

    @Test fun theButtonListsOnlyTheDifferencesAndApplies() {
        applyAll()
        AirPlayPersistence.saveHudLanes(context, false)
        AirPlayPersistence.saveHevcEnabled(context, false)
        val screen = openAdvancedSettings()

        recommendedButton(screen).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val message = shadowOf(dialog).message.toString()
        assertTrue(message, message.contains(screen.getString(R.string.settings_hud_lanes)))
        assertTrue(message, message.contains(screen.getString(R.string.efficient_video)))
        assertFalse(message, message.contains(screen.getString(R.string.settings_hud_trip)))
        assertEquals(screen.getString(R.string.settings_recommended_settings_apply), positiveButton(dialog))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(AirPlayPersistence.loadHudLanes(context))
        assertTrue(AirPlayPersistence.loadHevcEnabled(context))
        recommendedButton(screen).performClick()
        assertEquals(screen.getString(R.string.settings_recommended_settings_already_applied), ShadowToast.getTextOfLatestToast())
    }

    @Test fun locationAndSmoothVideoReconnectARunningSessionOnce() {
        grantLocation()
        applyAll(location = false)
        AirPlayPersistence.saveSmoothVideo(context, false)
        val screen = openAdvancedSettings()
        ReflectionHelpers.setField(screen, "setupError", null)
        val session = runSession()

        recommendedButton(screen).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(screen.getString(R.string.apply_and_reconnect), positiveButton(dialog))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
        assertTrue(AirPlayPersistence.loadSmoothVideo(context))
        assertEquals(1, session.stops)
    }

    @Test fun aSessionStartedAfterTheDialogOpenedIsKeptWithTheReconnectBar() {
        applyAll()
        AirPlayPersistence.saveSmoothVideo(context, false)
        val screen = openAdvancedSettings()
        ReflectionHelpers.setField(screen, "setupError", null)

        recommendedButton(screen).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(screen.getString(R.string.settings_recommended_settings_apply), positiveButton(dialog))
        val session = runSession()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(AirPlayPersistence.loadSmoothVideo(context))
        assertEquals(0, session.stops)
        assertTrue(PendingReconnect.isPending(session.controller))
    }

    @Test fun aMissingOverlayPermissionIsAskedForWithoutReconnecting() {
        applyAll()
        ShadowSettings.setCanDrawOverlays(false)
        AirPlayPersistence.saveCustomDashboard(context, false)
        AirPlayPersistence.saveSmoothVideo(context, false)
        val screen = openAdvancedSettings()
        ReflectionHelpers.setField(screen, "setupError", null)
        val session = runSession()

        recommendedButton(screen).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(screen.getString(R.string.settings_recommended_settings_apply), positiveButton(dialog))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(AirPlayPersistence.loadCustomDashboard(context))
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(screen).nextStartedActivity?.action)
        assertEquals(0, session.stops)
        assertTrue(PendingReconnect.isPending(session.controller))
    }

    @Test fun aReconnectThatStopsEarlyLeavesTheReconnectBar() {
        applyAll()
        AirPlayPersistence.saveSmoothVideo(context, false)
        val screen = openAdvancedSettings()
        ReflectionHelpers.setField(screen, "setupError", "no MFi identity")
        val session = runSession()

        recommendedButton(screen).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(screen.getString(R.string.apply_and_reconnect), positiveButton(dialog))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, session.stops)
        assertTrue(PendingReconnect.isPending(session.controller))
    }

    private class RunningSession(val controller: CarPlayController) { var stops = 0 }

    private fun runSession(): RunningSession {
        val session = RunningSession(mock(CarPlayController::class.java))
        CarPlayBackgroundSession.store(session.controller, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { done -> session.stops++; done() }
        CarPlayBackgroundSession.active = true
        return session
    }

    private fun grantLocation() =
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Everything recommended saved, wired, with the overlay allowed; [location] also turns location reporting on. */
    private fun applyAll(location: Boolean = true) {
        ShadowSettings.setCanDrawOverlays(true)
        MonjaroRecommendedSettings.apply(context, MonjaroRecommendedSettings.plan(context,
            sessionRunning = false, overlayPermitted = true, locationPermitted = true))
        AirPlayPersistence.saveLocationReportingEnabled(context, location)
        AirPlayPersistence.saveWirelessEnabled(context, false)
    }

    private fun positiveButton(dialog: AlertDialog) = dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString()

    private fun openAdvancedSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { screen ->
        activity = screen
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
    }

    private fun recommendedButton(screen: DiPlayActivity) =
        texts(screen).single { it.text == screen.getString(R.string.settings_recommended_settings) }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
