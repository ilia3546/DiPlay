package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.hud.CarPlayCalls
import com.shilapi.xcertplay.hud.DashboardCall
import com.shilapi.xcertplay.hud.GeelyDashboardCall
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MonjaroDashboardCallTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()
    private var live = true
    private lateinit var realLiveSession: () -> Boolean

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun record() {
        BydCarPlayCall.end()
        BydCarPlayCall.subscribing()
        realLiveSession = MonjaroDashboardCall.liveSession
        MonjaroDashboardCall.liveSession = { live }
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
        BydCarPlayCall.subscribing()
        idle()
        MonjaroDashboardCall.dashboard = GeelyDashboardCall
        MonjaroDashboardCall.liveSession = realLiveSession
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

    @Test fun aCallReportedBeforeTheAirPlaySessionIsLiveShowsOnceItIs() {
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        live = false
        startSession()
        ring()
        live = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf("start", "show none", "show RINGING Anna"), calls)
    }

    @Test fun aCallAfterTheAirPlaySessionEndedIsNotShownWhileTheControllerStaysAttached() {
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        startSession()
        live = false
        ring()
        assertEquals(listOf("start", "show none"), calls)
    }

    @Test fun theAirPlaySessionEndingDuringACallClosesTheWidgetWhileTheControllerStaysAttached() {
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        startSession()
        ring()
        BydCarPlayCall.end()
        live = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf("start", "show none", "show RINGING Anna", "show none"), calls)
    }

    @Test fun theOptionActsOnlyOnTheMonjaroA3WithTheOptionOnAndALiveSession() {
        AirPlayPersistence.saveDashboardCalls(context, true)
        assertFalse("another head unit", MonjaroDashboardCall.active(context))
        monjaroA3()
        assertTrue(MonjaroDashboardCall.active(context))
        live = false
        assertFalse("no live session", MonjaroDashboardCall.active(context))
        assertTrue("the option itself stays on", MonjaroDashboardCall.enabled(context))
        live = true
        AirPlayPersistence.saveDashboardCalls(context, false)
        assertFalse("option off", MonjaroDashboardCall.active(context))
    }

    @Test fun theCustomDashboardShowsNoCallWithoutALiveSession() {
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        ring()
        live = false
        assertNull(DashboardCallShown.of(MonjaroDashboardCall.active(context), CarPlayCalls.current(),
            System.currentTimeMillis()))
    }

    @Test fun aSessionWithTheOptionOnWarnsWhileThePhonesBluetoothIsKept() {
        fun warnings() = ShadowLog.getLogsForTag("DiPlay-DashboardCall").count { it.type == android.util.Log.WARN }
        ShadowLog.clear()
        monjaroA3()
        AirPlayPersistence.saveDashboardCalls(context, true)
        startSession()
        assertEquals(1, warnings())
        MonjaroDashboardCall.sessionEnded()
        idle()
        AirPlayPersistence.saveDisconnectPhoneBluetooth(context, true)
        startSession()
        assertEquals(1, warnings())
    }
}
