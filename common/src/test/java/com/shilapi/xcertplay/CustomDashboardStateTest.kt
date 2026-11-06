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
    private var pendingLeft: (() -> Unit)? = null
    private val availableAnswers = ArrayDeque<Boolean>()
    private var enabled: () -> Boolean = { flag }

    private val window = object : DashboardWindow {
        override fun available() = availableAnswers.removeFirstOrNull() ?: available
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
            exit = {
                pendingLeft = it
                events += "exit"
            },
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

    /** The mode's worker sent the exit and cleared the claim. */
    private fun confirmExit() {
        pendingLeft?.invoke()
        pendingLeft = null
    }

    /** As a CarPlay screen does: the option is read while the AirPlay configuration is built, then iOS connects. */
    private fun connect() {
        state.sessionStarting()
        state.connectedChanged(true)
    }

    private fun startAndDraw() {
        connect()
        drawFirstFrame()
    }

    @Test fun theModeIsEnteredOnlyAfterTheWindowsFirstFrame() {
        connect()
        assertEquals(listOf("session", "watch", "show"), events)
        drawFirstFrame()
        assertEquals(listOf("session", "watch", "show", "enter"), events)
        assertTrue(current!!())
    }

    @Test fun aSessionThatEndsBeforeTheFirstFrameNeverEnters() {
        connect()
        val frame = pendingFrame!!
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        frame()
        assertFalse(events.contains("enter"))
        confirmExit()
        assertEquals(listOf("session", "watch", "show", "exit", "hide", "unwatch"), events)
    }

    @Test fun aSessionEndLeavesTheModeAfterTheGrace() {
        startAndDraw()
        events.clear()
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS - 1)
        assertEquals(emptyList<String>(), events)
        advance(1)
        assertEquals("the window stays until the mode is off", listOf("exit"), events)
        assertFalse("an enter still queued is out of date", current!!())
        confirmExit()
        assertEquals(listOf("exit", "hide", "unwatch"), events)
    }

    @Test fun aWindowWhoseExitIsNotConfirmedStays() {
        startAndDraw()
        events.clear()
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        advance(60_000)
        watcher!!()
        assertEquals(listOf("exit"), events)
    }

    @Test fun aSessionBackBeforeTheExitIsConfirmedKeepsTheWindowAndEntersAgain() {
        startAndDraw()
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        events.clear()
        connect()
        assertEquals(listOf("session", "enter"), events)
        assertTrue(current!!())
        confirmExit()
        assertFalse("the late confirmation of the old exit", events.contains("hide"))
    }

    @Test fun losingTheDisplayWhileLeavingRemovesTheWindowAtOnce() {
        startAndDraw()
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        events.clear()
        available = false
        watcher!!()
        assertEquals("no second exit", listOf("hide", "unwatch"), events)
        confirmExit()
        assertEquals(listOf("hide", "unwatch"), events)
    }

    @Test fun aClusterDisplayBackUnderANewIdGetsANewWindow() {
        startAndDraw()
        events.clear()
        availableAnswers += false
        watcher!!()
        assertEquals(listOf("exit", "hide", "show"), events)
        drawFirstFrame()
        assertEquals(listOf("exit", "hide", "show", "enter"), events)
    }

    @Test fun aReconnectWithinTheGraceKeepsTheWindowAndTheMode() {
        startAndDraw()
        events.clear()
        state.connectedChanged(false)
        advance(1_000)
        connect()
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        assertFalse(events.contains("exit"))
        assertFalse(events.contains("hide"))
        assertFalse(events.contains("show"))
        assertTrue(current!!())
    }

    @Test fun aLaterSessionShowsANewWindowAndIgnoresTheOldFrame() {
        connect()
        val oldFrame = pendingFrame!!
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        confirmExit()
        connect()
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
        assertEquals(listOf("enter", "exit"), events.takeLast(2))
        confirmExit()
        assertEquals(listOf("exit", "hide", "unwatch"), events.takeLast(3))
    }

    @Test fun turningTheOptionOffLeavesAtOnceAndTurningItOnWaitsForTheNextSession() {
        flag = false
        connect()
        assertEquals(emptyList<String>(), events)
        flag = true
        state.settingChanged()
        assertEquals(emptyList<String>(), events)
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        connect()
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
        connect()
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
            connect()
            drawFirstFrame()
            assertEquals(emptyList<String>(), events)
            ShadowBuild.setProduct("geely_fx11")
            ShadowBuild.setModel("KX11-A3-L1")
            state.connectedChanged(false)
            advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
            connect()
            assertEquals(listOf("session", "watch", "show"), events)
        } finally {
            context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }

    @Test fun theStreamAndTheWindowShareOneReadingOfTheOption() {
        assertTrue(state.sessionStarting())
        flag = false
        state.connectedChanged(true)
        assertTrue("the stream was configured for the dashboard", state.sessionEnabled)
        assertEquals(listOf("session", "watch", "show"), events)
        state.settingChanged()
        assertFalse(state.sessionEnabled)
        assertTrue("the stream stays sized for the dashboard", state.sessionStream)
        confirmExit()
        assertEquals(listOf("session", "watch", "show", "exit", "hide", "unwatch"), events)
        state.connectedChanged(false)
        advance(CustomDashboardState.SESSION_END_GRACE_MILLIS)
        assertTrue(state.sessionStream)
        assertFalse("the next session reads the option again", state.sessionStarting())
        assertFalse(state.sessionStream)
    }

    @Test fun turningTheOptionOnAfterTheConfigurationWaitsForTheNextSession() {
        flag = false
        assertFalse(state.sessionStarting())
        flag = true
        state.connectedChanged(true)
        assertFalse(state.sessionEnabled)
        assertFalse(state.sessionStream)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun onlyAMonjaroA3WithTheOptionConfiguresTheDashboardStream() {
        val context: Context = RuntimeEnvironment.getApplication()
        try {
            enabled = { MonjaroA3Options.load(context).customDashboard }
            AirPlayPersistence.saveCustomDashboard(context, true)
            assertFalse("another head unit", state.sessionStarting())
            ShadowBuild.setProduct("geely_fx11")
            ShadowBuild.setModel("KX11-A3-L1")
            assertTrue(state.sessionStarting())
            AirPlayPersistence.saveCustomDashboard(context, false)
            assertFalse(state.sessionStarting())
            assertFalse(state.sessionEnabled)
        } finally {
            context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }
}
