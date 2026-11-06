package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CustomDashboardStateTest {
    private val events = mutableListOf<String>()
    private var now = 0L
    private val timers = mutableListOf<Pair<Long, Runnable>>()
    private var flag = true
    private var available = true
    private var showWorks = true
    private var watcher: (() -> Unit)? = null
    private var pendingFrame: (() -> Unit)? = null
    private var current: (() -> Boolean)? = null
    private var enabled: () -> Boolean = { flag }

    private val window = object : DashboardWindow {
        override fun available() = available
        override fun startWatching(changed: () -> Unit) {
            watcher = changed
            events += "watch"
        }
        override fun stopWatching() {
            watcher = null
            events += "unwatch"
        }
        override fun show(firstFrame: () -> Unit): Boolean {
            events += "show"
            if (!showWorks) return false
            pendingFrame = firstFrame
            return true
        }
        override fun hide() {
            events += "hide"
        }
    }

    private val state by lazy {
        CustomDashboardState(
            enabled = { enabled() },
            window = window,
            enter = {
                current = it
                events += "enter"
            },
            exit = { events += "exit" },
            sessionStarted = { events += "session" },
            postDelayed = { delayMillis, task -> timers += now + delayMillis to task },
            cancel = { task -> timers.removeAll { it.second === task } },
        )
    }

    private fun advance(millis: Long) {
        now += millis
        timers.filter { it.first <= now }.forEach {
            timers.remove(it)
            it.second.run()
        }
    }

    private fun drawFirstFrame() {
        pendingFrame?.invoke()
        pendingFrame = null
    }

    private fun startAndDraw() {
        state.connectedChanged(true)
        drawFirstFrame()
    }

    @Test fun theModeIsEnteredOnlyAfterTheWindowsFirstFrame() {
        state.connectedChanged(true)
        assertEquals(listOf("session", "watch", "show"), events)
        drawFirstFrame()
        assertEquals(listOf("session", "watch", "show", "enter"), events)
        assertTrue(current!!())
    }

    @Test fun aSessionThatEndsBeforeTheFirstFrameNeverEnters() {
        state.connectedChanged(true)
        val frame = pendingFrame!!
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        frame()
        assertFalse(events.contains("enter"))
        assertEquals(listOf("session", "watch", "show", "unwatch", "exit", "hide"), events)
    }

    @Test fun aSessionEndLeavesTheModeAfterTheGrace() {
        startAndDraw()
        events.clear()
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS - 1)
        assertEquals(emptyList<String>(), events)
        advance(1)
        assertEquals(listOf("unwatch", "exit", "hide"), events)
        assertFalse("an enter still queued is out of date", current!!())
    }

    @Test fun aReconnectWithinTheGraceKeepsTheWindowAndTheMode() {
        startAndDraw()
        events.clear()
        state.connectedChanged(false)
        advance(1_000)
        state.connectedChanged(true)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        assertFalse(events.contains("exit"))
        assertFalse(events.contains("hide"))
        assertFalse(events.contains("show"))
        assertTrue(current!!())
    }

    @Test fun aLaterSessionShowsANewWindowAndIgnoresTheOldFrame() {
        state.connectedChanged(true)
        val oldFrame = pendingFrame!!
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        state.connectedChanged(true)
        oldFrame()
        assertFalse(events.contains("enter"))
        drawFirstFrame()
        assertEquals(1, events.count { it == "enter" })
        assertEquals(2, events.count { it == "show" })
    }

    @Test fun stopDuringTheEnterMakesItOutOfDate() {
        startAndDraw()
        val wish = current!!
        flag = false
        state.settingChanged()
        assertFalse(wish())
        assertEquals(listOf("unwatch", "exit", "hide"), events.takeLast(3))
    }

    @Test fun turningTheOptionOffLeavesAtOnceAndTurningItOnWaitsForTheNextSession() {
        flag = false
        state.connectedChanged(true)
        assertEquals(emptyList<String>(), events)
        flag = true
        state.settingChanged()
        assertEquals(emptyList<String>(), events)
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        state.connectedChanged(true)
        drawFirstFrame()
        assertEquals(listOf("session", "watch", "show", "enter"), events)
    }

    @Test fun losingTheDisplayOrThePermissionLeavesAtOnce() {
        startAndDraw()
        events.clear()
        available = false
        watcher!!()
        assertEquals(listOf("exit", "hide"), events)
        available = true
        watcher!!()
        drawFirstFrame()
        assertEquals(listOf("exit", "hide", "show", "enter"), events)
    }

    @Test fun aWindowThatCannotBeShownNeverEnters() {
        showWorks = false
        state.connectedChanged(true)
        assertEquals(listOf("session", "watch", "show"), events)
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        assertFalse(events.contains("enter"))
        assertFalse(events.contains("exit"))
    }

    @Test fun terminatingLeavesAtOnce() {
        startAndDraw()
        events.clear()
        state.connectedChanged(false)
        state.terminate()
        assertEquals(listOf("unwatch", "exit", "hide"), events)
        assertTrue(timers.isEmpty())
    }

    @Test fun anotherHeadUnitGetsNoWindowEvenWithTheOptionSaved() {
        val context: Context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveCustomDashboard(context, true)
        try {
            enabled = { MonjaroA3Options.load(context).customDashboard }
            state.connectedChanged(true)
            drawFirstFrame()
            assertEquals(emptyList<String>(), events)
            ShadowBuild.setProduct("geely_fx11")
            ShadowBuild.setModel("KX11-A3-L1")
            state.connectedChanged(false)
            advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
            state.connectedChanged(true)
            assertEquals(listOf("session", "watch", "show"), events)
        } finally {
            context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }
}
