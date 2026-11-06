package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.GeelyCabinLan.Sender.Outcome
import com.shilapi.xcertplay.network.RootShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
    private var enterOutcome = Outcome.SENT
    private var now = 0L
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
            enterOutcome
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
        nowMillis = { now },
        sleep = { now += it },
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

    private fun runOneRetry(): Long {
        val (delayMillis, task) = delayed.removeAt(0)
        task.run()
        runWorker()
        return delayMillis
    }

    @Test fun aFailedExitKeepsTheClaimAndIsRetriedWithBackOffWhileTheClaimStays() {
        link.enter { true }
        runWorker()
        exitWorks = false
        link.exit()
        runWorker()
        val waits = List(6) { runOneRetry() }
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L), waits)
        assertEquals(7, events.count { it == "send $exit" })
        assertTrue(claim.held)

        exitWorks = true
        runOneRetry()
        assertFalse(claim.held)
        assertTrue("no retry once the exit was sent", delayed.isEmpty())
    }

    @Test fun theRetriesStopWhenTheClaimIsGone() {
        link.enter { true }
        runWorker()
        exitWorks = false
        link.exit()
        runWorker()
        claim.held = false
        runOneRetry()
        assertEquals(1, events.count { it == "send $exit" })
        assertTrue(delayed.isEmpty())
    }

    @Test fun theExitReportsOnlyOnceTheModeIsOffAndTheClaimGone() {
        val left = mutableListOf<String>()
        link.exit { left += "no claim" }
        runWorker()
        assertEquals("nothing to leave", listOf("no claim"), left)

        link.enter { true }
        runWorker()
        exitWorks = false
        link.exit { left += "sent" }
        runWorker()
        runOneRetry()
        assertEquals(listOf("no claim"), left)
        exitWorks = true
        runOneRetry()
        assertEquals(listOf("no claim", "sent"), left)
    }

    @Test fun anExitReplacedByANewerWishDoesNotReport() {
        var left = false
        link.enter { true }
        runWorker()
        exitWorks = false
        link.exit { left = true }
        runWorker()
        exitWorks = true
        link.enter { true }
        runWorker()
        runRetries()
        assertFalse(left)
    }

    @Test fun idleWaitsForTheExitRetries() {
        var idle = 0
        link.enter { true }
        runWorker()
        exitWorks = false
        link.exit()
        link.whenIdle { idle++ }
        runWorker()
        runOneRetry()
        assertEquals("an exit still waits for a retry", 0, idle)
        exitWorks = true
        runOneRetry()
        assertEquals(1, idle)
        link.whenIdle { idle++ }
        runWorker()
        assertEquals(2, idle)
    }

    @Test fun anEnterThatReplacesTheRetriesLeavesTheLinkIdle() {
        var idle = false
        link.enter { true }
        runWorker()
        exitWorks = false
        link.exit()
        link.whenIdle { idle = true }
        runWorker()
        assertFalse(idle)
        link.enter { true }
        runWorker()
        assertTrue(idle)
    }

    @Test fun anEnterThatSurelyDidNotLeaveDropsTheClaim() {
        for (outcome in listOf(Outcome.NOT_ATTEMPTED, Outcome.FAILED_KNOWN)) {
            events.clear()
            enterOutcome = outcome
            link.enter { true }
            runWorker()
            assertFalse(claim.held)
            link.exit()
            runWorker()
            assertEquals("no exit for a mode that was not entered", listOf("claim true", "send $enter", "claim false"), events)
        }
    }

    @Test fun anEnterThatMayHaveLeftKeepsTheClaimForTheExit() {
        enterOutcome = Outcome.UNKNOWN
        link.enter { true }
        runWorker()
        assertTrue(claim.held)
        link.exit()
        runWorker()
        assertEquals(listOf("claim true", "send $enter", "send $exit", "claim false"), events)
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

    @Test fun noEnterStartsAfterACrashExit() {
        link.exitNow()
        link.enter { true }
        runWorker()
        assertEquals(emptyList<String>(), events)
    }

    @Test fun aCrashDuringAnEnterThatDoesNotFinishLeavesButKeepsTheClaim() {
        onEnter = { link.exitNow() }
        link.enter { true }
        runWorker()
        assertEquals(listOf("claim true", "send $enter", "send $exit"), events)
        assertTrue("the enter may arrive after the exit", claim.held)
        assertTrue(now >= EngineeringModeLink.CRASH_ENTER_WAIT_MILLIS)
    }

    @Test fun aCrashWaitsForAnEnterUnderWayAndThenClearsTheClaim() {
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = mutableListOf<String>()
        val heldClaim = object : EngineeringModeClaim {
            @Volatile override var held = false
            override fun set(held: Boolean): Boolean {
                this.held = held
                return true
            }
        }
        val threaded = EngineeringModeLink(
            execute = { it.run() },
            schedule = { _, _ -> },
            sendEnter = { json ->
                synchronized(sent) { sent += json }
                sending.countDown()
                release.await(5, TimeUnit.SECONDS)
                Outcome.SENT
            },
            sendExit = { json, _ ->
                synchronized(sent) { sent += json }
                true
            },
            claim = heldClaim,
            supported = { true },
        )
        val worker = thread { threaded.enter { true } }
        assertTrue(sending.await(5, TimeUnit.SECONDS))
        val crash = thread { threaded.exitNow() }
        Thread.sleep(50)
        assertEquals("the exit waits for the enter", listOf(enter), synchronized(sent) { sent.toList() })
        release.countDown()
        crash.join(5_000)
        worker.join(5_000)
        assertEquals(listOf(enter, exit), sent)
        assertFalse(heldClaim.held)
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
