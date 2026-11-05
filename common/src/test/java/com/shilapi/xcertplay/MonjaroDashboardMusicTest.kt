package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import com.shilapi.xcertplay.hud.DashboardMusic
import com.shilapi.xcertplay.hud.GeelyDashboardMusic
import com.shilapi.xcertplay.media.CarPlayNowPlaying
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
class MonjaroDashboardMusicTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()
    private var iphonePlaying = false

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun record() {
        MonjaroDashboardMusic.dashboard = object : DashboardMusic {
            override fun start(context: Context, mediaKey: (index: Int) -> Unit) { calls += "start" }
            override fun show(context: Context, title: String?, artist: String?, album: String?, durationMillis: Long?,
                artwork: Bitmap?) {
                calls += "show $title"
            }
            override fun playing(context: Context, playing: Boolean) { calls += "playing $playing" }
            override fun position(context: Context, elapsedMillis: Long?, atMillis: Long) {
                calls += "position $elapsedMillis@$atMillis"
            }
            override fun stop(context: Context) { calls += "stop" }
            override fun whenIdle(done: () -> Unit) = done()
        }
        ClusterMusicState.publish(CarPlayNowPlaying(title = "Song", artist = "Artist", playing = false), null)
        idle()
    }

    @After fun clean() {
        MonjaroDashboardMusic.sessionEnded()
        idle()
        MonjaroDashboardMusic.dashboard = GeelyDashboardMusic
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        idle()
    }

    private fun monjaroA3() {
        ShadowBuild.setProduct("geely_fx11")
        ShadowBuild.setModel("KX11-A3-L1")
    }

    private fun startSession() {
        MonjaroDashboardMusic.sessionStarted(context) { iphonePlaying }
        idle()
    }

    @Test fun anotherHeadUnitGetsNothingEvenWithTheOptionSaved() {
        AirPlayPersistence.saveDashboardMusic(context, true)
        startSession()
        MonjaroDashboardMusic.playingChanged(true)
        MonjaroDashboardMusic.settingChanged(context)
        MonjaroDashboardMusic.sessionEnded()
        idle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun anotherHeadUnitSendsNoWidgetBroadcastAndHoldsNoKeyReceiver() {
        MonjaroDashboardMusic.dashboard = GeelyDashboardMusic
        AirPlayPersistence.saveDashboardMusic(context, true)
        startSession()
        MonjaroDashboardMusic.playingChanged(true)
        MonjaroDashboardMusic.positionChanged(1_000L, 0L)
        MonjaroDashboardMusic.settingChanged(context)
        MonjaroDashboardMusic.sessionEnded()
        idle()
        val app = shadowOf(RuntimeEnvironment.getApplication())
        assertEquals(emptyList<Intent>(), app.broadcastIntents)
        assertEquals(0, app.registeredReceivers.count { it.intentFilter.hasAction("gmc.audio_play") })
    }

    @Test fun thePositionGoesToTheDashboardOnlyWhileItIsFollowedAndAtStart() {
        monjaroA3()
        AirPlayPersistence.saveDashboardMusic(context, false)
        startSession()
        MonjaroDashboardMusic.positionChanged(5_000L, 100L)
        idle()
        AirPlayPersistence.saveDashboardMusic(context, true)
        MonjaroDashboardMusic.settingChanged(context)
        idle()
        MonjaroDashboardMusic.positionChanged(9_000L, 200L)
        idle()
        MonjaroDashboardMusic.sessionEnded()
        idle()
        MonjaroDashboardMusic.positionChanged(1L, 300L)
        startSession()
        assertEquals(listOf("start", "playing false", "position 5000@100", "show Song", "position 9000@200", "stop",
            "start", "playing false", "position 1@300", "show Song"), calls)
    }

    @Test fun aSessionFollowsTheTrackAndEndsWithOneStop() {
        monjaroA3()
        AirPlayPersistence.saveDashboardMusic(context, true)
        iphonePlaying = true
        startSession()
        MonjaroDashboardMusic.playingChanged(false)
        idle()
        MonjaroDashboardMusic.sessionEnded()
        MonjaroDashboardMusic.sessionEnded()
        MonjaroDashboardMusic.playingChanged(true)
        idle()
        assertEquals(listOf("start", "playing true", "show Song", "playing false", "stop"), calls)
    }

    @Test fun aRepeatedAttachDoesNotStartAgain() {
        monjaroA3()
        AirPlayPersistence.saveDashboardMusic(context, true)
        startSession()
        startSession()
        MonjaroDashboardMusic.sessionEnded()
        idle()
        startSession()
        assertEquals(listOf("start", "playing false", "show Song", "stop", "start", "playing false", "show Song"), calls)
    }

    @Test fun turningTheOptionOffStopsOnce() {
        monjaroA3()
        AirPlayPersistence.saveDashboardMusic(context, true)
        startSession()
        AirPlayPersistence.saveDashboardMusic(context, false)
        MonjaroDashboardMusic.settingChanged(context)
        MonjaroDashboardMusic.settingChanged(context)
        MonjaroDashboardMusic.sessionEnded()
        idle()
        assertEquals(listOf("start", "playing false", "show Song", "stop"), calls)
    }

    @Test fun turningTheOptionOnMidSessionUsesThePlayStateOfNow() {
        monjaroA3()
        startSession()
        iphonePlaying = true
        AirPlayPersistence.saveDashboardMusic(context, true)
        MonjaroDashboardMusic.settingChanged(context)
        idle()
        assertEquals(listOf("start", "playing true", "show Song"), calls)
    }
}
