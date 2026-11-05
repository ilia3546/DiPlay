package com.shilapi.xcertplay.hud

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GeelyMediaWidgetOutputTest {
    private val sent = mutableListOf<Intent>()
    private val ownership = mutableListOf<Boolean>()
    private var clock = 1_000L
    private val output = GeelyMediaWidgetOutput(send = { sent += it; true }, appName = "DiPlay",
        appPackage = "com.shihab.diplay", owned = { ownership += it }, now = { clock })

    private fun cover() = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)

    @Test fun anUpdateCarriesEveryFieldTheWidgetRequiresWithItsType() {
        output.position(60_000L, clock)
        output.playing(true)
        output.show("Song", "Artist", "Album", 200_000L, cover())
        val update = sent.single()
        assertEquals(GeelyMediaWidgetMessages.UPDATE, update.action)
        assertEquals("com.geely.mediawidget", update.`package`)
        assertEquals("Song", update.getStringExtra("music.title"))
        assertEquals("Artist", update.getStringExtra("music.subtitle"))
        assertEquals("DiPlay", update.getStringExtra("music.app"))
        assertEquals("com.shihab.diplay", update.getStringExtra("music.app.full"))
        assertTrue(update.getBooleanExtra("music.is_playing", false))
        assertEquals(200_000L, update.getLongExtra("music.duration", -1))
        assertEquals(60_000L, update.getLongExtra("music.progress", -1))
        @Suppress("DEPRECATION")
        val image = update.getParcelableExtra<Bitmap>("music.image")!!
        assertEquals(300, image.width)
        assertEquals(Bitmap.Config.RGB_565, image.config)
    }

    @Test fun aTrackWithoutCoverHasNoImageAndTheSubtitleIsNeverEmpty() {
        output.show("Song", "", "Album", null, null)
        output.show("Song", " ", "", null, null)
        assertFalse(sent[0].hasExtra("music.image"))
        assertEquals("Album", sent[0].getStringExtra("music.subtitle"))
        assertEquals(" ", sent[1].getStringExtra("music.subtitle"))
        assertEquals(0L, sent[1].getLongExtra("music.duration", -1))
        assertFalse(sent[1].getBooleanExtra("music.is_playing", true))
    }

    @Test fun theWidgetKeepsItsOwnCopyOfTheCover() {
        val cover = cover()
        output.show("Song", "Artist", "", null, cover)
        cover.recycle()
        output.reassert()
        @Suppress("DEPRECATION")
        assertFalse(sent.last().getParcelableExtra<Bitmap>("music.image")!!.isRecycled)
    }

    @Test fun aPlayStateChangeSendsTheCardAgainFromTheExtrapolatedPosition() {
        output.position(10_000L, clock)
        output.playing(true)
        output.show("Song", "Artist", "", 200_000L, null)
        clock += 4_000L
        output.playing(true)
        output.playing(false)
        clock += 9_000L
        output.reassert()
        assertEquals(3, sent.size)
        assertFalse(sent[1].getBooleanExtra("music.is_playing", true))
        assertEquals(14_000L, sent[1].getLongExtra("music.progress", -1))
        assertEquals(14_000L, sent[2].getLongExtra("music.progress", -1))
    }

    @Test fun progressAloneGoesOnlyWhilePlayingATrackWithADuration() {
        output.tick()
        output.show("Song", "Artist", "", 200_000L, null)
        output.tick()
        output.position(0L, clock)
        output.playing(true)
        clock += 5_000L
        output.tick()
        clock += 500_000L
        output.tick()
        output.show("Live", "Artist", "", null, null)
        output.tick()
        val progress = sent.filter { it.action == GeelyMediaWidgetMessages.PROGRESS }
        assertEquals(listOf(5_000L, 200_000L), progress.map { it.getLongExtra("music.progress", -1) })
        assertEquals(listOf(200_000L, 200_000L), progress.map { it.getLongExtra("music.duration", -1) })
        assertTrue(progress.all { it.`package` == "com.geely.mediawidget" })
    }

    @Test fun clearGivesTheWidgetsBackOnceAndOnlyAfterACardOfOurs() {
        output.clear()
        assertTrue(sent.isEmpty())
        output.show("Song", "Artist", "", null, null)
        output.show("", "", "Album", null, null)
        output.clear()
        output.reassert()
        output.tick()
        assertEquals(listOf(GeelyMediaWidgetMessages.UPDATE, GeelyMediaWidgetMessages.CLEAR), sent.map { it.action })
        assertEquals("com.geely.mediawidget", sent.last().`package`)
        assertEquals(listOf(true, false), ownership)
    }

    @Test fun ownershipStartsWithTheFirstCardOnly() {
        output.show("A", "Artist", "", null, null)
        output.show("B", "Artist", "", null, null)
        output.playing(true)
        output.reassert()
        assertEquals(listOf(true), ownership)
    }

    @Test fun widgetButtonsBecomeCarPlayPresses() {
        assertEquals(CarPlayMediaButton.PLAY, GeelyMediaWidgetMessages.mediaButton("gmc.audio_play"))
        assertEquals(CarPlayMediaButton.PAUSE, GeelyMediaWidgetMessages.mediaButton("gmc.audio_pause"))
        assertEquals(CarPlayMediaButton.PREVIOUS, GeelyMediaWidgetMessages.mediaButton("gmc.audio_pre"))
        assertEquals(CarPlayMediaButton.NEXT, GeelyMediaWidgetMessages.mediaButton("gmc.audio_next"))
        assertNull(GeelyMediaWidgetMessages.mediaButton("gmc.ecarx.xsf.mediacenter.audio_next"))
        assertNull(GeelyMediaWidgetMessages.mediaButton(null))
    }

    @Test fun theSameButtonWithin300MillisecondsIsIgnored() {
        val pressed = mutableListOf<Int>()
        var at = 0L
        val keys = GeelyMediaWidgetKeys(press = { pressed += it }, now = { at })
        keys.onKey("gmc.audio_next")
        at = 299L
        keys.onKey("gmc.audio_next")
        keys.onKey("gmc.audio_pre")
        at = 400L
        keys.onKey("gmc.audio_pre")
        at = 700L
        keys.onKey("gmc.audio_pre")
        keys.onKey("android.intent.action.SCREEN_ON")
        assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.PREVIOUS), pressed)
    }

    @Config(sdk = [30, 33])
    @Test fun theKeyReceiverIsRegisteredOnceAndRemoved() {
        val app = RuntimeEnvironment.getApplication()
        val pressed = mutableListOf<Int>()
        val keys = GeelyMediaWidgetKeys(press = { pressed += it })
        fun registered() = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction("gmc.audio_play") }
        keys.register(app)
        keys.register(app)
        val filter: IntentFilter = registered().single().intentFilter
        assertEquals(GeelyMediaWidgetMessages.KEYS, (0 until filter.countActions()).map(filter::getAction))
        app.sendBroadcast(Intent("gmc.audio_pause"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(CarPlayMediaButton.PAUSE), pressed)
        keys.unregister()
        keys.unregister()
        assertTrue(registered().isEmpty())
    }
}
