package com.shilapi.xcertplay

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import com.shilapi.xcertplay.CarMediaVolume.Sound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarMediaVolumeTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun mute(stream: Int, muted: Boolean?) = Intent(CarMediaVolume.MUTE_CHANGED).apply {
        putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", stream)
        if (muted != null) putExtra("android.media.EXTRA_STREAM_VOLUME_MUTED", muted)
    }

    private fun volume(stream: Int, value: Int?) = Intent(CarMediaVolume.VOLUME_CHANGED).apply {
        putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", stream)
        if (value != null) putExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", value)
        putExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", 7)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun setVolume(level: Int) {
        Settings.System.putInt(app.contentResolver, CarMediaVolume.VOLUME_SETTING, level)
        app.contentResolver.notifyChange(Settings.System.getUriFor(CarMediaVolume.VOLUME_SETTING), null)
        idle()
    }

    @Test fun theMuteBroadcastOfTheMediaStream() {
        val heard = Sound(volume = 4)
        assertEquals(Sound(muted = true, volume = 4), CarMediaVolume.after(heard, mute(AudioManager.STREAM_MUSIC, true)))
        assertEquals(Sound(muted = false, volume = 4), CarMediaVolume.after(heard, mute(AudioManager.STREAM_MUSIC, false)))
        assertEquals("no mute extra", heard, CarMediaVolume.after(heard, mute(AudioManager.STREAM_MUSIC, null)))
        assertEquals("another stream", heard, CarMediaVolume.after(heard, mute(AudioManager.STREAM_NOTIFICATION, true)))
    }

    @Test fun theVolumeBroadcastOfTheMediaStream() {
        val heard = Sound(muted = false, volume = 4)
        assertEquals(Sound(muted = false, volume = 0), CarMediaVolume.after(heard, volume(AudioManager.STREAM_MUSIC, 0)))
        assertEquals(Sound(muted = false, volume = 12), CarMediaVolume.after(heard, volume(AudioManager.STREAM_MUSIC, 12)))
        assertEquals(heard, CarMediaVolume.after(heard, volume(AudioManager.STREAM_MUSIC, null)))
        assertEquals(heard, CarMediaVolume.after(heard, volume(AudioManager.STREAM_RING, 0)))
        assertEquals("another action", heard, CarMediaVolume.after(heard, Intent("android.media.RINGER_MODE_CHANGED")
            .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)))
    }

    @Test fun theSoundIsOffWhenMutedOrAtZero() {
        assertFalse("nothing known is on", Sound().off)
        assertFalse(Sound(muted = false, volume = 5).off)
        assertTrue(Sound(muted = true, volume = 5).off)
        assertTrue(Sound(muted = true).off)
        assertTrue(Sound(volume = 0).off)
        assertTrue(Sound(muted = false, volume = 0).off)
    }

    @Test fun itStartsFromTheVolumeSettingAndFollowsTheLastEvent() {
        Settings.System.putInt(app.contentResolver, CarMediaVolume.VOLUME_SETTING, 4)
        var changes = 0
        val volume = CarMediaVolume(app) { changes++ }
        volume.start()
        assertFalse(volume.muted)
        app.sendBroadcast(volume(AudioManager.STREAM_MUSIC, 0))
        idle()
        assertTrue("the broadcast came after the setting", volume.muted)
        setVolume(3)
        assertFalse("the setting came after the broadcast", volume.muted)
        setVolume(0)
        assertTrue(volume.muted)
        app.sendBroadcast(volume(AudioManager.STREAM_MUSIC, 2))
        idle()
        assertFalse(volume.muted)
        assertEquals(4, changes)
        volume.stop()
    }

    @Test fun aSettingAtZeroIsOffFromTheStart() {
        Settings.System.putInt(app.contentResolver, CarMediaVolume.VOLUME_SETTING, 0)
        val volume = CarMediaVolume(app) {}
        volume.start()
        assertTrue(volume.muted)
        volume.stop()
    }

    @Test fun theMuteComesOnlyFromItsBroadcasts() {
        var changes = 0
        val volume = CarMediaVolume(app) { changes++ }
        volume.start()
        assertFalse("no setting, no broadcast: on", volume.muted)
        app.sendBroadcast(mute(AudioManager.STREAM_MUSIC, true))
        idle()
        assertTrue(volume.muted)
        setVolume(9)
        assertTrue("a volume does not unmute", volume.muted)
        app.sendBroadcast(mute(AudioManager.STREAM_MUSIC, false))
        idle()
        assertFalse(volume.muted)
        assertEquals(2, changes)
        app.sendBroadcast(mute(AudioManager.STREAM_MUSIC, true))
        idle()
        volume.stop()
        assertFalse(volume.muted)
        app.sendBroadcast(mute(AudioManager.STREAM_MUSIC, true))
        setVolume(0)
        assertFalse("stopped", volume.muted)
        assertEquals(3, changes)
    }
}
