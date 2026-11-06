package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.network.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineeringModeLinkTest {
    private val enter = GeelyEngineeringModeMessages.switch(true)
    private val exit = GeelyEngineeringModeMessages.switch(false)
    private val queue = ArrayDeque<Runnable>()
    private val delayed = mutableListOf<Pair<Long, Runnable>>()
    private val events = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val rootTimeouts = mutableListOf<Long>()
    private var supported = true
    private var enterWorks = true
    private var exitWorks = true
    private var commitWorks = true
    private var onEnter: () -> Unit = {}

    private val claim = object : EngineeringModeClaim {
        override var held = false

        override fun set(held: Boolean): Boolean {
            if (!commitWorks) return false
            this.held = held
            events += "claim $held"
            return true
        }
    }

    private val link = EngineeringModeLink(
        execute = { queue.addLast(it) },
        schedule = { delayMillis, task -> delayed += delayMillis to task },
        sendEnter = { json ->
            events += "send $json"
            onEnter()
            enterWorks
        },
        sendExit = { json, rootTimeoutMillis ->
            events += "send $json"
            rootTimeouts += rootTimeoutMillis
            exitWorks
        },
        claim = claim,
        supported = { supported },
        newSession = { events += "new session" },
        log = { logs += it },
    )

    private fun runWorker() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    private fun runRetries() {
        while (delayed.isNotEmpty()) {
            delayed.removeAt(0).second.run()
            runWorker()
        }
    }

    @Test fun theJsonFollowsTheStockEngineeringModeScreen() {
        assertEquals("{\"EnterOrExitEngineeringMode\":1,\"EngineeringModePage\":0}", enter)
        assertEquals("{\"EnterOrExitEngineeringMode\":0,\"EngineeringModePage\":0}", exit)
        assertEquals(0x1005, GeelyCabinLan.ENGINEERING_MODE)
        assertEquals(0x10, GeelyCabinLan.ENGINEERING_MODE_SWITCH)
    }

    @Test fun theClaimIsOnDiskBeforeTheEnterAndClearedOnlyAfterTheExit() {
        link.enter { true }
        runWorker()
        link.exit()
        runWorker()
        assertEquals(listOf("claim true", "send $enter", "send $exit", "claim false"), events)
        assertFalse(claim.held)
    }

    @Test fun noEnterLeavesWhenTheClaimCannotBeSaved() {
        commitWorks = false
        link.enter { true }
        runWorker()
        link.exit()
        runWorker()
        assertEquals(emptyList<String>(), events)
        assertTrue(logs.contains("engineering mode claim could not be saved; not entering"))
    }

    @Test fun anOutOfDateEnterIsDropped() {
        link.enter { false }
        runWorker()
        assertEquals(emptyList<String>(), events)
    }

    @Test fun onlyTheLatestWishIsApplied() {
        link.enter { true }
        link.exit()
        runWorker()
        assertEquals("no exit without a claim, and no enter after the exit wish", emptyList<String>(), events)
    }

    @Test fun anExitAskedForDuringTheEnterFollowsIt() {
        onEnter = { link.exit() }
        link.enter { true }
        runWorker()
        assertEquals(listOf("claim true", "send $enter", "send $exit", "claim false"), events)
    }

    @Test fun aRepeatedEnterIsSentOnce() {
        link.enter { true }
        runWorker()
        link.enter { true }
        runWorker()
        assertEquals(listOf("claim true", "send $enter"), events)
    }

    @Test fun thereIsNoExitWithoutAClaim() {
        link.exit()
        link.releaseStale()
        runWorker()
        link.exitNow()
        assertEquals(emptyList<String>(), events)
    }

    @Test fun aFailedExitKeepsTheClaimAndIsRetriedThreeTimes() {
        link.enter { true }
        runWorker()
        exitWorks = false
        link.exit()
        runWorker()
        assertEquals(listOf(EngineeringModeLink.EXIT_RETRY_MILLIS), delayed.map { it.first })
        runRetries()
        assertEquals(1 + EngineeringModeLink.EXIT_RETRIES, events.count { it == "send $exit" })
        assertTrue(claim.held)

        exitWorks = true
        link.exit()
        runWorker()
        assertFalse("the next trigger tries again", claim.held)
    }

    @Test fun aNewEnterStopsTheRetriesOfAnOlderExit() {
        link.enter { true }
        runWorker()
        exitWorks = false
        link.exit()
        runWorker()
        link.enter { true }
        runWorker()
        runRetries()
        assertEquals(1, events.count { it == "send $exit" })
        assertEquals(2, events.count { it == "send $enter" })
    }

    @Test fun aClaimLeftByAKilledRunIsLeftAtStart() {
        claim.held = true
        link.releaseStale()
        runWorker()
        assertEquals(listOf("send $exit", "claim false"), events)
        assertTrue(logs.contains("engineering mode left on by a previous run; leaving it"))
    }

    @Test fun theProcessThatHoldsTheModeKeepsItWhenDiPlayOpens() {
        link.enter { true }
        runWorker()
        link.releaseStale()
        runWorker()
        assertEquals(listOf("claim true", "send $enter"), events)
    }

    @Test fun aCrashLeavesTheModeOnTheCallersThread() {
        link.enter { true }
        runWorker()
        link.exitNow()
        assertEquals(listOf("claim true", "send $enter", "send $exit", "claim false"), events)
    }

    @Test fun aCrashGivesTheRootHelperAShortTimeoutAndAnOrdinaryExitTheFullOne() {
        link.enter { true }
        runWorker()
        link.exit()
        runWorker()
        link.enter { true }
        runWorker()
        link.exitNow()
        assertEquals(listOf(RootShell.DEFAULT_TIMEOUT_MILLIS, EngineeringModeLink.CRASH_EXIT_ROOT_TIMEOUT_MILLIS), rootTimeouts)
        assertTrue(EngineeringModeLink.CRASH_EXIT_ROOT_TIMEOUT_MILLIS <= 3_000L)
    }

    @Test fun nothingIsSentOnAnyOtherHeadUnit() {
        supported = false
        claim.held = true
        link.enter { true }
        link.sessionStarted()
        link.exit()
        link.releaseStale()
        runWorker()
        link.exitNow()
        assertEquals(listOf("new session", "claim false"), events)
    }

    @Test fun staleDecisions() {
        assertEquals(StaleEngineeringModeAction.EXIT, staleEngineeringModeAction(heldInProcess = false, claimed = true, supported = true))
        assertEquals(StaleEngineeringModeAction.NONE, staleEngineeringModeAction(heldInProcess = false, claimed = false, supported = true))
        assertEquals(StaleEngineeringModeAction.NONE, staleEngineeringModeAction(heldInProcess = true, claimed = true, supported = true))
        assertEquals(StaleEngineeringModeAction.FORGET, staleEngineeringModeAction(heldInProcess = false, claimed = true, supported = false))
    }
}
