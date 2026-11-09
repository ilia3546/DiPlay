package com.shilapi.xcertplay

import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.HudCall
import com.shilapi.xcertplay.hud.HudFrame
import com.shilapi.xcertplay.hud.HudManeuver
import com.shilapi.xcertplay.hud.HudSettings
import com.shilapi.xcertplay.hud.HudUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonjaroHudDisplayTest {
    private val centre = DisplayDescription(0, "Built-in Screen", "local:129", 129, 1920, 720)
    private val cluster = DisplayDescription(1, "HDMI Screen", "local:130", 130, 1920, 720)
    private val hud = DisplayDescription(11, "HUD", "local:132", 132, 800, 480)

    @Test fun theHudIsThePhysicalDisplay132() {
        assertEquals(ClusterDisplayChoice(11, "display 11 at local:132"), MonjaroHudDisplay.choose(listOf(centre, cluster, hud)))
    }

    @Test fun eitherAddressIsEnough() {
        assertEquals(11, MonjaroHudDisplay.choose(listOf(centre, hud.copy(physicalId = null))).displayId)
        assertEquals(11, MonjaroHudDisplay.choose(listOf(centre, hud.copy(uniqueId = null))).displayId)
    }

    @Test fun noHudPicksNothingAndSaysWhy() {
        assertEquals(ClusterDisplayChoice(null, "no display at local:132"), MonjaroHudDisplay.choose(listOf(centre, cluster)))
        val sameSize = hud.copy(displayId = 3, uniqueId = "local:140", physicalId = 140)
        assertNull(MonjaroHudDisplay.choose(listOf(centre, sameSize)).displayId)
    }

    @Test fun twoCandidatesPickNothing() {
        assertEquals(ClusterDisplayChoice(null, "2 displays at local:132"),
            MonjaroHudDisplay.choose(listOf(hud, hud.copy(displayId = 12))))
        assertNull(MonjaroHudDisplay.choose(listOf(centre, hud.copy(physicalId = 131))).displayId)
    }

    @Test fun aHudOfAnotherSizePicksNothing() {
        assertEquals(ClusterDisplayChoice(null, "local:132 is 1280x480, not 800x480"),
            MonjaroHudDisplay.choose(listOf(centre, hud.copy(width = 1280))))
    }

    @Test fun aShownWindowStaysOnlyOnTheChosenDisplay() {
        assertTrue(MonjaroHudDisplay.usable(permitted = true, chosenDisplayId = 11, shownOnDisplayId = 11))
        assertFalse(MonjaroHudDisplay.usable(permitted = true, chosenDisplayId = 12, shownOnDisplayId = 11))
        assertFalse(MonjaroHudDisplay.usable(permitted = false, chosenDisplayId = 11, shownOnDisplayId = null))
    }

    @Test fun unreadableAddressesPickNothing() {
        assertNull(MonjaroHudDisplay.choose(listOf(centre, hud).map { it.copy(uniqueId = null, physicalId = null) }).displayId)
    }
}

class HudStateTest {
    private val events = mutableListOf<String>()
    private val drawn = mutableListOf<Pair<HudFrame?, HudSettings>>()
    private val drawnCalls = mutableListOf<HudCall?>()
    private val logs = mutableListOf<String>()
    private var call: HudCall? = null
    private var calling = false
    private val timers = mutableListOf<Runnable>()
    private var options = MonjaroA3Options(hudManeuvers = true)
    private var available = true
    private var hudDisplayId = 11
    private var shownOn: Int? = null
    private var showWorks = true
    private var watcher: (() -> Unit)? = null
    private var glance = CarPlayGlance.Snapshot()

    private val window = object : HudWindow {
        override fun available() = MonjaroHudDisplay.usable(available, hudDisplayId, shownOn)
        override fun startWatching(changed: () -> Unit) {
            watcher = changed
            events += "watch"
        }
        override fun stopWatching() {
            watcher = null
            events += "unwatch"
        }
        override fun show(): Boolean {
            events += "show"
            if (showWorks) shownOn = hudDisplayId
            return showWorks
        }
        override fun hide() {
            events += "hide"
            shownOn = null
        }
        override fun draw(frame: HudFrame?, call: HudCall?, settings: HudSettings) {
            drawn += frame to settings
            drawnCalls += call
        }
    }

    private val state = HudState(
        options = { options },
        window = window,
        glance = { glance },
        units = { HudUnits("м", "км", "мин", ',') },
        call = { call },
        calling = { calling },
        postDelayed = { delayMillis, task ->
            assertEquals(HudState.TICK_MILLIS, delayMillis)
            timers += task
        },
        cancel = { task -> timers.remove(task) },
        log = { logs += it },
    )

    private val connected = CarPlayGlance.Snapshot(connected = true, session = 1)
    private val turn = connected.copy(maneuverType = 2, maneuverIndex = 0, distanceMeters = 350, road = "Садовое кольцо")

    private fun glanceIs(next: CarPlayGlance.Snapshot) {
        glance = next
        state.glanceChanged()
    }

    private fun callIs(next: HudCall?) {
        call = next
        state.callChanged()
    }

    private fun tick() = timers.toList().forEach {
        timers.remove(it)
        it.run()
    }

    @Test fun offTheWindowNeverAppears() {
        options = MonjaroA3Options(hudManeuvers = false)
        glanceIs(turn)
        assertEquals(emptyList<String>(), events)
        assertTrue(drawn.isEmpty())
    }

    @Test fun theWindowLivesForTheSessionAndIsEmptyWithoutARoute() {
        glanceIs(connected)
        assertEquals(listOf("watch", "show"), events)
        assertEquals(listOf<HudFrame?>(null), drawn.map { it.first })

        glanceIs(turn)
        assertEquals(HudManeuver.RIGHT, drawn.last().first?.kind)
        glanceIs(connected)
        assertNull("the route ended", drawn.last().first)

        glanceIs(CarPlayGlance.Snapshot())
        assertEquals(listOf("watch", "show", "unwatch", "hide"), events)
        assertTrue(timers.isEmpty())
    }

    @Test fun turningTheOptionOnDuringASessionShowsTheLineAtOnce() {
        options = MonjaroA3Options(hudManeuvers = false)
        glanceIs(turn)
        options = MonjaroA3Options(hudManeuvers = true)
        state.settingChanged()
        assertEquals(listOf("watch", "show"), events)
        assertEquals(HudManeuver.RIGHT, drawn.single().first?.kind)

        options = MonjaroA3Options(hudManeuvers = false)
        state.settingChanged()
        assertEquals(listOf("watch", "show", "unwatch", "hide"), events)
    }

    @Test fun aNewHeightMovesTheShownLineWithoutAnotherWindow() {
        glanceIs(turn)
        options = options.copy(hudHeight = 310)
        state.settingChanged()
        assertEquals(listOf("watch", "show"), events)
        assertEquals(listOf(215, 310), drawn.map { it.second.centerY })
        assertEquals(drawn[0].first, drawn[1].first)
    }

    @Test fun losingThePermissionOrTheDisplayTakesTheWindowAwayAndItComesBack() {
        glanceIs(turn)
        available = false
        watcher!!()
        assertEquals(listOf("watch", "show", "hide"), events)
        available = true
        watcher!!()
        assertEquals(listOf("watch", "show", "hide", "show"), events)
        assertEquals(HudManeuver.RIGHT, drawn.last().first?.kind)
    }

    @Test fun aHeadUpDisplayBackUnderANewIdGetsTheWindowAtOnce() {
        glanceIs(turn)
        hudDisplayId = 12
        watcher!!()
        assertEquals(listOf("watch", "show", "hide", "show"), events)
        assertEquals(12, shownOn)
        assertEquals(HudManeuver.RIGHT, drawn.last().first?.kind)
        assertEquals(1, timers.size)
    }

    @Test fun aRefusedWindowIsTriedAgainOnTheNextChange() {
        showWorks = false
        glanceIs(turn)
        assertTrue(drawn.isEmpty())
        assertTrue(timers.isEmpty())
        showWorks = true
        watcher!!()
        assertEquals(listOf("watch", "show", "show"), events)
        assertEquals(1, drawn.size)
    }

    @Test fun everySecondTheLineFollowsGuidanceThatExpiredWithoutAFrame() {
        glanceIs(turn)
        tick()
        assertEquals("nothing new, nothing redrawn", 1, drawn.size)
        glance = connected
        tick()
        assertNull(drawn.last().first)
        assertEquals(1, timers.size)
    }

    @Test fun aChangeReadsTheGlanceAsItIsWhenItRunsNotAsItWasPosted() {
        glanceIs(turn)
        // The session ended while a change from it was still on its way to the main thread.
        glance = CarPlayGlance.Snapshot()
        state.glanceChanged()
        assertEquals(listOf("watch", "show", "unwatch", "hide"), events)
    }

    @Test fun anotherSessionIsAnotherWindowCheck() {
        glanceIs(turn)
        glanceIs(turn.copy(session = 2))
        assertEquals(listOf("watch", "show"), events)
        assertEquals(1, drawn.size)
    }

    @Test fun aCallBringsTheWindowWithTheLineOffAndTakesItAwayWhenItEnds() {
        options = MonjaroA3Options(hudManeuvers = false)
        glanceIs(turn)
        callIs(HudCall("Анна", ""))
        assertEquals(listOf("watch", "show"), events)
        assertNull("the line stays off", drawn.single().first)
        assertEquals(HudCall("Анна", ""), drawnCalls.single())

        callIs(null)
        assertEquals(listOf("watch", "show", "unwatch", "hide"), events)
        assertTrue(timers.isEmpty())
        assertEquals(listOf("HUD call shown", "HUD call hidden"), logs.filter { it.startsWith("HUD call") })
    }

    @Test fun theCallTimeRunsEverySecond() {
        options = MonjaroA3Options(hudManeuvers = false)
        callIs(HudCall("00:05", ""))
        call = HudCall("00:06", "")
        tick()
        assertEquals(listOf(HudCall("00:05", ""), HudCall("00:06", "")), drawnCalls)
        assertEquals(1, timers.size)
    }

    @Test fun aCallJoinsTheLineInTheSameWindow() {
        glanceIs(turn)
        callIs(HudCall("Анна", ""))
        assertEquals(listOf("watch", "show"), events)
        assertEquals(HudManeuver.RIGHT, drawn.last().first?.kind)
        assertEquals(HudCall("Анна", ""), drawnCalls.last())

        callIs(null)
        assertEquals(listOf("watch", "show"), events)
        assertEquals(HudManeuver.RIGHT, drawn.last().first?.kind)
        assertNull(drawnCalls.last())
    }

    @Test fun noCallLeavesTheLineAsItWas() {
        glanceIs(turn)
        callIs(null)
        assertEquals(listOf("watch", "show"), events)
        assertEquals(1, drawn.size)
        assertTrue(logs.none { it.startsWith("HUD call") })
    }

    @Test fun aCallTheHudMayShowOnlyLaterAppearsOnTheNextSecond() {
        options = MonjaroA3Options(hudManeuvers = false)
        glanceIs(connected)
        assertTrue("no call, no window, no timer", timers.isEmpty())

        calling = true
        state.callChanged()
        assertEquals("the session is not live yet", emptyList<String>(), events)
        assertEquals(1, timers.size)

        call = HudCall("00:42", "")
        tick()
        assertEquals(listOf("watch", "show"), events)
        assertEquals(HudCall("00:42", ""), drawnCalls.single())

        calling = false
        call = null
        state.callChanged()
        assertEquals(listOf("watch", "show", "unwatch", "hide"), events)
        assertTrue(timers.isEmpty())
    }

    @Test fun aWindowBroughtBackByTheDisplayDrawsTheCallAsItIsNow() {
        options = MonjaroA3Options(hudManeuvers = false)
        calling = true
        callIs(HudCall("00:05", ""))
        available = false
        watcher!!()
        call = HudCall("00:09", "")
        available = true
        watcher!!()
        assertEquals(listOf("watch", "show", "hide", "show"), events)
        assertEquals(HudCall("00:09", ""), drawnCalls.last())
    }
}
