package com.shilapi.xcertplay.hud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for a working org.json; the plain unit-test android.jar only has stubs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GeelyDashboardCallOutputTest {
    private val sent = mutableListOf<Triple<String, Int, String>>()
    private val marks = mutableListOf<Boolean>()
    private var accept = true
    private var acceptApp = true
    private var refused: String? = null
    private var clock = 1_000_000L

    private fun output(leftBehind: Boolean = false) = GeelyDashboardCallOutput(
        send = { subtype, json -> sent += Triple("root", subtype, json); accept && json != refused },
        resend = { subtype, json -> sent += Triple("app", subtype, json); acceptApp },
        leftBehind = leftBehind,
        widgetShown = { marks += it },
        now = { clock },
    )

    private fun call(name: String, number: String, state: Int) =
        Triple("root", GeelyCallMessages.CALL, GeelyCallMessages.call(name, number, state))

    private fun direct(name: String, number: String, state: Int) =
        Triple("app", GeelyCallMessages.CALL, GeelyCallMessages.call(name, number, state))

    private fun duration(seconds: Long) = Triple("app", GeelyCallMessages.DURATION, GeelyCallMessages.duration(seconds))

    private val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Anna", number = "+7 900 000-00-00")

    @Test fun buildsTheStockCallMessages() {
        assertEquals("{\"PhoneType\":1,\"Name\":\"Anna\",\"Number\":\"+7900\",\"CallState\":2,\"ContactPicture\":\"\"}",
            GeelyCallMessages.call("Anna", "+7900", GeelyCallMessages.DIALING))
        assertEquals("{\"CallDuration\":75}", GeelyCallMessages.duration(75))
    }

    @Test fun escapesCyrillicQuotesAndBackslashesInTheName() {
        val name = "Мама \"дом\" \\ 1\n"
        val json = JSONObject(GeelyCallMessages.call(name, "+7\"1", GeelyCallMessages.INCOMING))
        assertEquals(name, json.getString("Name"))
        assertEquals("+7\"1", json.getString("Number"))
        assertEquals(1, json.getInt("PhoneType"))
        assertEquals(1, json.getInt("CallState"))
        assertEquals("", json.getString("ContactPicture"))
    }

    @Test fun mapsEachPhaseToTheStockState() {
        assertEquals(GeelyCallMessages.INCOMING, GeelyCallMessages.state(CarPlayCallCard.Phase.RINGING))
        assertEquals(GeelyCallMessages.DIALING, GeelyCallMessages.state(CarPlayCallCard.Phase.DIALING))
        assertEquals(GeelyCallMessages.TALKING, GeelyCallMessages.state(CarPlayCallCard.Phase.ACTIVE))
    }

    @Test fun aCallGoesFromRingingThroughTheTalkTimeToEndThenIdle() {
        val output = output()
        output.show(ringing)
        output.show(ringing)
        clock += 2_000
        output.show(ringing.copy(phase = CarPlayCallCard.Phase.ACTIVE, activeSinceMillis = clock))
        output.tick()
        clock += 1_400
        output.tick()
        clock += 300
        output.tick()
        clock += 700
        output.tick()
        output.show(null)
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.TALKING),
            duration(0), duration(1), duration(2),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.ENDED),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.IDLE),
        ), sent)
        assertEquals(listOf(true, false), marks)
        assertFalse(output.ticking)
    }

    @Test fun theSameCallIsNotSentTwice() {
        val output = output()
        val active = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Anna", clock, "1")
        output.show(active)
        output.show(active)
        output.show(active.copy())
        assertEquals(listOf(call("Anna", "1", GeelyCallMessages.TALKING), duration(0)), sent)
    }

    @Test fun aNewNameOrNumberIsSentAgain() {
        val output = output()
        output.show(ringing)
        output.show(ringing.copy(name = "Anna K"))
        output.show(ringing.copy(name = "Anna K", number = "2"))
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna K", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna K", "2", GeelyCallMessages.INCOMING),
        ), sent)
    }

    @Test fun theTalkTimeCountsFromWhenTheCallConnected() {
        val output = output()
        val since = clock - 65_500
        output.show(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Anna", since))
        assertEquals(duration(65), sent.last())
        assertTrue(output.ticking)
    }

    @Test fun onlyACallInProgressHasATalkTime() {
        val output = output()
        output.show(ringing)
        output.tick()
        output.show(ringing.copy(phase = CarPlayCallCard.Phase.DIALING))
        output.tick()
        assertFalse(output.ticking)
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.DIALING),
        ), sent)
    }

    @Test fun aWaitingCallStopsTheTalkTimeUntilTheCallInProgressIsBack() {
        val output = output()
        val active = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Anna", clock - 10_000)
        output.show(active)
        output.show(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Boris"))
        assertFalse(output.ticking)
        output.tick()
        clock += 5_000
        output.show(active)
        assertEquals(listOf(
            call("Anna", "", GeelyCallMessages.TALKING), duration(10),
            call("Boris", "", GeelyCallMessages.INCOMING),
            call("Anna", "", GeelyCallMessages.TALKING), duration(15),
        ), sent)
    }

    @Test fun theTalkTimeNeverStartsSu() {
        val output = output()
        acceptApp = false
        output.show(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Anna", clock))
        output.tick()
        assertEquals(listOf(call("Anna", "", GeelyCallMessages.TALKING), duration(0), duration(0)), sent)
    }

    @Test fun nothingIsClosedWhenNothingWasShown() {
        val output = output()
        output.clear()
        output.show(null)
        assertEquals(emptyList<Triple<String, Int, String>>(), sent)
        assertEquals(emptyList<Boolean>(), marks)
    }

    @Test fun aWidgetLeftByAKilledProcessIsClosedOnTheFirstClear() {
        val output = output(leftBehind = true)
        output.clear()
        output.clear()
        assertEquals(listOf(call("", "", GeelyCallMessages.ENDED), call("", "", GeelyCallMessages.IDLE)), sent)
        assertEquals(listOf(false), marks)
    }

    @Test fun theWidgetIsMarkedShownBeforeItsFirstMessageEvenIfThatFails() {
        val output = output()
        accept = false
        output.show(ringing)
        assertEquals(listOf(true), marks)
        accept = true
        output.clear()
        assertEquals(listOf(true, false), marks)
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("", "", GeelyCallMessages.ENDED), call("", "", GeelyCallMessages.IDLE),
        ), sent)
    }

    @Test fun aFailedEndStaysShownAndIsSentAgain() {
        val output = output()
        output.show(ringing)
        accept = false
        output.show(null)
        accept = true
        output.clear()
        assertEquals(listOf(true, false), marks)
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.ENDED),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.ENDED),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.IDLE),
        ), sent)
    }

    @Test fun aRefusedAnswerIsSentAgainAndOnlyThenTheTalkTimeStarts() {
        val output = output()
        output.show(ringing)
        accept = false
        val active = ringing.copy(phase = CarPlayCallCard.Phase.ACTIVE, activeSinceMillis = clock)
        output.show(active)
        output.tick()
        assertFalse(output.ticking)
        assertTrue(output.undelivered)
        accept = true
        clock += 3_000
        output.retry()
        assertTrue(output.ticking)
        assertFalse(output.undelivered)
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.TALKING),
            direct("Anna", "+7 900 000-00-00", GeelyCallMessages.TALKING),
            duration(3),
        ), sent)
    }

    @Test fun aRefusedEndIsSentAgainWithoutANewCall() {
        val output = output()
        output.show(ringing)
        accept = false
        output.clear()
        assertTrue(output.undelivered)
        accept = true
        output.retry()
        output.retry()
        assertFalse(output.undelivered)
        assertEquals(listOf(true, false), marks)
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.ENDED),
            direct("Anna", "+7 900 000-00-00", GeelyCallMessages.ENDED),
            direct("Anna", "+7 900 000-00-00", GeelyCallMessages.IDLE),
        ), sent)
    }

    @Test fun aHalfSentEndSendsOnlyTheIdleStateAgainAndKeepsTheWidgetMarked() {
        val output = output()
        output.show(ringing)
        refused = GeelyCallMessages.call("Anna", "+7 900 000-00-00", GeelyCallMessages.IDLE)
        output.clear()
        assertEquals(listOf(true), marks)
        refused = null
        output.retry()
        assertEquals(listOf(true, false), marks)
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.ENDED),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.IDLE),
            direct("Anna", "+7 900 000-00-00", GeelyCallMessages.IDLE),
        ), sent)
    }

    @Test fun theSameCallAfterAHalfSentEndIsSentAgain() {
        val output = output()
        output.show(ringing)
        refused = GeelyCallMessages.call("Anna", "+7 900 000-00-00", GeelyCallMessages.IDLE)
        output.clear()
        output.show(ringing)
        assertFalse(output.undelivered)
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.ENDED),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.IDLE),
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
        ), sent)
    }

    @Test fun aNewWishReplacesTheUndeliveredOne() {
        val output = output()
        accept = false
        output.show(ringing)
        accept = true
        output.show(ringing.copy(name = "Boris"))
        output.retry()
        assertEquals(listOf(
            call("Anna", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
            call("Boris", "+7 900 000-00-00", GeelyCallMessages.INCOMING),
        ), sent)
    }

    @Test fun retriesWaitLongerUpToTenSeconds() {
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 10_000L, 10_000L),
            (0..5).map(GeelyDashboardCallOutput::retryDelayMillis))
    }

    @Test fun onlyARetryAfterTheLongestWaitMayStartSu() {
        val output = output()
        output.show(ringing)
        accept = false
        acceptApp = false
        output.clear()
        val waits = (1..6).map {
            val wait = output.retryDelayMillis
            sent.clear()
            output.retry()
            wait to sent.single().first
        }
        assertEquals(listOf(1_000L to "app", 2_000L to "app", 5_000L to "app", 10_000L to "root", 10_000L to "root",
            10_000L to "root"), waits)
    }

    @Test fun aCloseIsDeliveredOnceRootWorksAgainAfterItsFailuresStoppedItWithoutANewSession() {
        var roots = 0
        var rootWorks = true
        var clock = 0L
        val sender = GeelyCabinLan.Sender(
            direct = { _, _, _ -> throw SecurityException("policy") },
            root = { _, _, _ -> roots++; if (rootWorks) null else "FAILED Refused" },
            log = {},
            now = { clock },
        )
        val output = GeelyDashboardCallOutput(
            send = { subtype, json -> sender.send(GeelyCabinLan.PHONE, subtype, json) },
            resend = { subtype, json -> sender.sendWithoutRoot(GeelyCabinLan.PHONE, subtype, json) },
            widgetShown = { marks += it },
            renewTransport = sender::newSession,
        )
        output.show(ringing)
        rootWorks = false
        output.clear()
        while (roots < 1 + GeelyCabinLan.Sender.MAX_ROOT_FAILURES) {
            clock += output.retryDelayMillis
            output.retry()
        }
        assertTrue(output.undelivered)
        rootWorks = true
        clock += output.retryDelayMillis
        output.retry()
        assertFalse(output.undelivered)
        assertEquals(listOf(true, false), marks)
    }
}
