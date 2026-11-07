package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.hud.DashboardCall
import com.shilapi.xcertplay.hud.GeelyDashboardCall
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MonjaroDashboardCallTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun record() {
        BydCarPlayCall.end()
        MonjaroDashboardCall.dashboard = object : DashboardCall {
            override fun start(context: Context) { calls += "start" }
            override fun show(context: Context, card: CarPlayCallCard?) {
                calls += "show " + (card?.let { "${it.phase} ${it.name}" } ?: "none")
            }
            override fun stop(context: Context) { calls += "stop" }
            override fun clearLeftBehind(context: Context) { calls += "clear left behind" }
            override fun whenIdle(done: () -> Unit) = done()
        }
    }

    @After fun clean() {
        MonjaroDashboardCall.sessionEnded()
        idle()
        BydCarPlayCall.end()
        idle()
        MonjaroDashboardCall.dashboard = GeelyDashboardCall
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun monjaroA3() {
        ShadowBuild.setProduct("geely_fx11")
        ShadowBuild.setModel("KX11-A3-L1")
    }

    private fun startSession() {
        MonjaroDashboardCall.sessionStarted(context)
        idle()
    }

    private fun ring() {
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 2); string(1, "Anna"); string(4, "call-one")
        })
        idle()
    }

    private fun answer() {
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(1, "Anna"); string(4, "call-one")
        })
        idle()
    }

    private fun hangUp() {
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 0); string(4, "call-one")
        })
        idle()
    }

    @Test fun anotherHeadUnitGetsNothingEvenWithTheOptionSaved() {
        AirPlayPersistence.saveDashboardCalls(context, true)
        MonjaroDashboardCall.install(context)
        ring()
        startSession()
        MonjaroDashboardCall.settingChanged(context)
        MonjaroDashboardCall.sessionEnded()
        idle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun aSessionFollowsTheCallUntilItEnds() {
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        startSession()
        ring()
        answer()
        hangUp()
        assertEquals(listOf("start", "show none", "show RINGING Anna", "show ACTIVE Anna", "show none"), calls)
    }

    @Test fun turningTheOptionOffDuringACallClosesTheWidget() {
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        startSession()
        ring()
        AirPlayPersistence.saveDashboardCalls(context, false)
        MonjaroDashboardCall.settingChanged(context)
        idle()
        answer()
        MonjaroDashboardCall.settingChanged(context)
        idle()
        assertEquals(listOf("start", "show none", "show RINGING Anna", "stop"), calls)
    }

    @Test fun theSessionEndingDuringACallClosesTheWidget() {
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        startSession()
        ring()
        MonjaroDashboardCall.sessionEnded()
        idle()
        answer()
        assertEquals(listOf("start", "show none", "show RINGING Anna", "stop"), calls)
    }

    @Test fun aNewSessionDuringACallShowsItAtOnce() {
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        ring()
        startSession()
        assertEquals(listOf("start", "show RINGING Anna"), calls)
    }

    @Test fun turningTheOptionOnDuringACallShowsIt() {
        monjaroA3()
        startSession()
        ring()
        AirPlayPersistence.saveDashboardCalls(context, true)
        MonjaroDashboardCall.settingChanged(context)
        idle()
        assertEquals(listOf("start", "show RINGING Anna"), calls)
    }

    @Test fun aWidgetLeftBehindIsClosedAtProcessStartOnlyOnTheMonjaroA3() {
        MonjaroDashboardCall.install(context)
        monjaroA3()
        MonjaroDashboardCall.install(context)
        assertEquals(listOf("clear left behind"), calls)
    }
}
