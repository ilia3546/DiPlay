package com.shilapi.xcertplay.hud

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
    private var repeating: Runnable? = null
    private var period = 0L
    private var cancels = 0
    private var sessions = 0
    private val sent = mutableListOf<Pair<Int, String>>()
    private val resent = mutableListOf<Pair<Int, String>>()
    private val blank = byteArrayOf(9)
    private val output = GeelyDashboardMusicOutput(send = { subtype, json -> sent += subtype to json; true },
        blankCover = { blank }, resend = { subtype, json -> resent += subtype to json; true })
    private val queue = DashboardMusicQueue(
        execute = { tasks.addLast(it) },
        repeat = { millis, task ->
            period = millis
            repeating = task
            ({ cancels += 1; repeating = null })
        },
        encode = { byteArrayOf(it.width.toByte()) },
        output = { output },
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
        queue.show("A", "Artist", "", first)
        queue.stop()
        queue.start()
        queue.show("B", "Artist", "", cover(2))
        runQueued()
        assertTrue(first.isRecycled)
        assertEquals(listOf(card("B", 2)), sent.filter { it.first == GeelyDashboardMessages.TRACK }.map { it.second })
        assertTrue(repeating != null)
    }

    @Test fun onlyTheLatestWishIsApplied() {
        val dropped = cover(1)
        val applied = cover(2)
        queue.show("A", "Artist", "", dropped)
        queue.show("B", "Artist", "", applied)
        assertTrue(dropped.isRecycled)
        assertFalse(applied.isRecycled)
        runQueued()
        assertTrue(applied.isRecycled)
        assertEquals(listOf(GeelyDashboardMessages.TRACK to card("B", 2)), sent)
    }

    @Test fun theShownCardIsRepeatedEveryThirtySecondsUntilStop() {
        queue.start()
        queue.show("A", "Artist", "", cover(1))
        queue.playing(true)
        runQueued()
        assertEquals(1, sessions)
        assertEquals(30_000L, period)
        repeating!!.run()
        assertEquals(listOf(GeelyDashboardMessages.TRACK to card("A", 1),
            GeelyDashboardMessages.PLAY_STATE to GeelyDashboardMessages.playState(true)), resent)
        queue.stop()
        runQueued()
        assertEquals(1, cancels)
        assertEquals(null, repeating)
        assertEquals(GeelyDashboardMessages.track("", "", "", blank), sent.last().second)
    }

    @Test fun aNewSessionRestartsTheRepeatOnce() {
        queue.start()
        queue.start()
        runQueued()
        assertEquals(2, sessions)
        assertEquals(1, cancels)
    }

    @Test fun whenIdleRunsAfterTheWorkAskedForBeforeIt() {
        var cleared = false
        queue.show("A", "Artist", "", null)
        runQueued()
        queue.stop()
        queue.whenIdle { cleared = sent.last().second == GeelyDashboardMessages.track("", "", "", blank) }
        runQueued()
        assertTrue(cleared)
    }
}
