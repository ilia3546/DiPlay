package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.media.RendererAudioFocus
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

/**
 * With the option to pause CarPlay for the car's audio, a session starts with CarPlay owning the wheel and the
 * media block, and as the media centre's source, even while the firmware flags its radio as playing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayWheelOwnerAtStartTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = mock(CarPlayController::class.java)
    private val mediaCentre = RecordingMediaCentre()
    private val renderer = Any()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun carAudioOwnsWheel(): Boolean = ReflectionHelpers.getField(CarPlayMediaKeys, "carAudioOwnsWheel")

    private fun published(): ClusterPlaybackState.Snapshot {
        var snapshot = ClusterPlaybackState.Snapshot()
        ClusterPlaybackState.observe { snapshot = it }()
        return snapshot
    }

    private fun mediaBlock(carSource: Boolean?) = DashboardMediaSource.after(null, DashboardMediaSignals(
        carPlayTrack = true, radioPlaying = true, radioServicePlaying = true, carSource = carSource))

    @Before fun monjaroA3WithTheRadioPlayingAndASilentIphone() {
        ShadowBuild.setProduct("geely_fx11")
        ShadowBuild.setModel("KX11-A3-L1")
        Settings.Global.putInt(app.contentResolver, "radio_is_playing", 1)
        // GeelyHeadUnit.detected is fixed by the first test class in this Robolectric sandbox that reads it, so the
        // wheel keys may bind their input service here; Robolectric would connect them with a null binder.
        val input = ComponentName("com.geely.inputservice", "com.geely.inputservice.InputService")
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(Intent().setComponent(input), input, AcceptingService())
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent("android.intent.action.GEELY_MEDIACENTER_SERVICE").setPackage("com.geely.mediacenterservice"),
            ComponentName("com.geely.mediacenterservice", "MediaCenterService"), mediaCentre)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(title = "Song", elapsedMillis = 0))
    }

    @After fun clean() {
        RendererAudioFocus.release(renderer)
        CarPlayMediaKeys.detach(controller)
        Settings.Global.putInt(app.contentResolver, "radio_is_playing", 0)
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        idle()
    }

    @Test fun aSilentIphoneGetsTheWheelTheMediaBlockAndTheMediaCentreWhileTheRadioPlays() {
        AirPlayPersistence.savePauseCarPlayForCarAudio(app, true)
        CarPlayMediaKeys.attach(app, controller)
        idle()
        assertFalse(carAudioOwnsWheel())
        assertEquals(false, published().carSource)
        assertEquals(DashboardMediaSource.CARPLAY, mediaBlock(published().carSource))
        assertTrue("CarPlay asked to be the media centre's source", mediaCentre.requested.await(5, TimeUnit.SECONDS))
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 2, mediaCentre.code)
    }

    @Test fun theCarTakingTheAudioAfterTheStartStillTakesTheWheelAndTheMediaBlock() {
        AirPlayPersistence.savePauseCarPlayForCarAudio(app, true)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        val request = ReflectionHelpers.getField<AudioFocusRequestCompat>(CarPlayMediaKeys, "focusRequest")
        ReflectionHelpers.getField<AudioManager.OnAudioFocusChangeListener>(request, "listener")
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idle()
        assertTrue(carAudioOwnsWheel())
        assertEquals(true, published().carSource)
        assertEquals(DashboardMediaSource.RADIO, mediaBlock(published().carSource))
    }

    @Test fun theCarTakingTheAudioPausesCarPlay() {
        loseMediaKeyFocusWhilePlaying()
        verify(controller).sendMediaButton(CarPlayMediaButton.PAUSE)
    }

    @Test fun diPlaysOwnPlayerTakingTheFocusLeavesCarPlayPlayingAndTakesTheFocusBack() {
        RendererAudioFocus.claim(renderer)
        loseMediaKeyFocusWhilePlaying()
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PAUSE)
        assertFalse(carAudioOwnsWheel())
        assertEquals(DashboardMediaSource.CARPLAY, mediaBlock(published().carSource))
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
    }

    @Test fun aLossAfterDiPlaysPlayerLetTheFocusGoStillPausesCarPlay() {
        RendererAudioFocus.claim(renderer)
        RendererAudioFocus.release(renderer)
        loseMediaKeyFocusWhilePlaying()
        verify(controller).sendMediaButton(CarPlayMediaButton.PAUSE)
    }

    private fun loseMediaKeyFocusWhilePlaying() {
        AirPlayPersistence.savePauseCarPlayForCarAudio(app, true)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        val request = ReflectionHelpers.getField<AudioFocusRequestCompat>(CarPlayMediaKeys, "focusRequest")
        ReflectionHelpers.getField<AudioManager.OnAudioFocusChangeListener>(request, "listener")
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idle()
    }

    @Test fun withoutThePauseOptionTheStartIsUnchanged() {
        CarPlayMediaKeys.attach(app, controller)
        idle()
        assertFalse(carAudioOwnsWheel())
        assertNull(published().carSource)
        assertFalse("the media centre is left alone", mediaCentre.requested.await(300, TimeUnit.MILLISECONDS))
    }

    private class AcceptingService : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply?.writeNoException()
            return true
        }
    }

    private class RecordingMediaCentre : Binder() {
        val requested = CountDownLatch(1)
        @Volatile var code = 0

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            this.code = code
            reply?.writeNoException()
            requested.countDown()
            return true
        }
    }
}
