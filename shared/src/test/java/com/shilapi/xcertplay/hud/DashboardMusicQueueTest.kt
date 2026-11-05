package com.shilapi.xcertplay.hud

import android.content.Intent
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DashboardMusicQueueTest {
    private val tasks = ArrayDeque<Runnable>()
    private val repeating = mutableMapOf<Long, Runnable>()
    private var cancels = 0
    private var sessions = 0
    private val sent = mutableListOf<Pair<Int, String>>()
    private val resent = mutableListOf<Pair<Int, String>>()
    private val blank = byteArrayOf(9)
    private val output = GeelyDashboardMusicOutput(send = { subtype, json -> sent += subtype to json; true },
        blankCover = { blank }, resend = { subtype, json -> resent += subtype to json; true })
    private val widgetSent = mutableListOf<Intent>()
    private var clock = 0L
    private val widget = GeelyMediaWidgetOutput(send = { widgetSent += it; true }, appName = "DiPlay",
        appPackage = "com.shihab.diplay", now = { clock })
    private val queue = DashboardMusicQueue(
        execute = { tasks.addLast(it) },
        repeat = { millis, task ->
            repeating[millis] = task
            ({ cancels += 1; repeating.remove(millis) })
        },
        encode = { byteArrayOf(it.width.toByte()) },
        output = { output },
        widget = { widget },
        newSession = { sessions += 1 },
    )

    private fun runQueued() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    private fun cover(width: Int) = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)

    private fun card(title: String, width: Int) =
        GeelyDashboardMessages.track(title, "Artist", "", byteArrayOf(width.toByte()))

    @Test fun aSyncQueuedBeforeAStopCannotLeaveTheNewerTrackCleared() {
        val first = cover(1)
        queue.show("A", "Artist", "", null, first)
        queue.stop()
        queue.start()
        queue.show("B", "Artist", "", null, cover(2))
        runQueued()
        assertTrue(first.isRecycled)
        assertEquals(listOf(card("B", 2)), sent.filter { it.first == GeelyDashboardMessages.TRACK }.map { it.second })
        assertEquals(setOf(DashboardMusicQueue.REASSERT_MILLIS, DashboardMusicQueue.PROGRESS_MILLIS), repeating.keys)
    }

    @Test fun onlyTheLatestWishIsApplied() {
        val dropped = cover(1)
        val applied = cover(2)
        queue.show("A", "Artist", "", null, dropped)
        queue.show("B", "Artist", "", null, applied)
        assertTrue(dropped.isRecycled)
        assertFalse(applied.isRecycled)
        runQueued()
        assertTrue(applied.isRecycled)
        assertEquals(listOf(GeelyDashboardMessages.TRACK to card("B", 2)), sent)
    }

    @Test fun theShownCardIsRepeatedEveryThirtySecondsUntilStop() {
        queue.start()
        queue.show("A", "Artist", "", null, cover(1))
        queue.playing(true)
        runQueued()
        assertEquals(1, sessions)
        assertEquals(30_000L, DashboardMusicQueue.REASSERT_MILLIS)
        repeating.getValue(DashboardMusicQueue.REASSERT_MILLIS).run()
        assertEquals(listOf(GeelyDashboardMessages.TRACK to card("A", 1),
            GeelyDashboardMessages.PLAY_STATE to GeelyDashboardMessages.playState(true)), resent)
        queue.stop()
        runQueued()
        assertEquals(2, cancels)
        assertTrue(repeating.isEmpty())
        assertEquals(GeelyDashboardMessages.track("", "", "", blank), sent.last().second)
    }

    @Test fun aNewSessionRestartsTheRepeatOnce() {
        queue.start()
        queue.start()
        runQueued()
        assertEquals(2, sessions)
        assertEquals(2, cancels)
    }

    @Test fun theWidgetsFollowEachTrackPlayStateAndRepeatAndAreGivenBackAtStop() {
        queue.start()
        queue.playing(false)
        queue.position(30_000L, clock)
        queue.show("A", "Artist", "Album", 200_000L, cover(300))
        runQueued()
        queue.playing(true)
        runQueued()
        clock = 5_000L
        repeating.getValue(DashboardMusicQueue.PROGRESS_MILLIS).run()
        repeating.getValue(DashboardMusicQueue.REASSERT_MILLIS).run()
        queue.stop()
        runQueued()
        assertEquals(5_000L, DashboardMusicQueue.PROGRESS_MILLIS)
        assertEquals(listOf(GeelyMediaWidgetMessages.UPDATE, GeelyMediaWidgetMessages.UPDATE,
            GeelyMediaWidgetMessages.PROGRESS, GeelyMediaWidgetMessages.UPDATE, GeelyMediaWidgetMessages.CLEAR),
            widgetSent.map { it.action })
        assertEquals("A", widgetSent[0].getStringExtra("music.title"))
        assertTrue(widgetSent[0].hasExtra("music.image"))
        assertFalse(widgetSent[0].getBooleanExtra("music.is_playing", true))
        assertTrue(widgetSent[1].getBooleanExtra("music.is_playing", false))
        assertEquals(35_000L, widgetSent[2].getLongExtra("music.progress", -1))
        assertEquals(35_000L, widgetSent[3].getLongExtra("music.progress", -1))
    }

    @Test fun whenIdleRunsAfterTheWorkAskedForBeforeIt() {
        var cleared = false
        queue.show("A", "Artist", "", null, null)
        runQueued()
        queue.stop()
        queue.whenIdle { cleared = sent.last().second == GeelyDashboardMessages.track("", "", "", blank) &&
            widgetSent.last().action == GeelyMediaWidgetMessages.CLEAR }
        runQueued()
        assertTrue(cleared)
    }
}
