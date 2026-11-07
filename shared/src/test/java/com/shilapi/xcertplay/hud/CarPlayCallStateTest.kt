package com.shilapi.xcertplay.hud

import android.view.KeyEvent
import com.shilapi.xcertplay.hud.CarPlayCallKeyPolicy.Action
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayCallStateTest {
    private var now = 1_000L
    private val state = CarPlayCallState { now }

    private fun update(
        uuid: String,
        status: Int,
        name: String? = null,
        remote: String? = null,
        label: String? = null,
        reason: Int? = null,
    ) = Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
        remote?.let { string(0, it) }
        name?.let { string(1, it) }
        u8(2, status)
        u8(3, 1)
        string(4, uuid)
        label?.let { string(CarPlayCallState.LABEL, it) }
        reason?.let { u8(CarPlayCallState.DISCONNECT_REASON, it) }
    }

    private fun mute(muted: Boolean?) = Iap2Messages.buildRaw(CarPlayCallState.COMMUNICATIONS_UPDATE) {
        u8(0, 3)
        muted?.let { bool(CarPlayCallState.MUTE_STATUS, it) }
    }

    private fun outcome(kind: CarPlayCallOutcome.Kind, name: String, talkSeconds: Long, endedAt: Long, number: String = "") =
        CarPlayCallOutcome(kind, name, number, talkSeconds, endedAt)

    @Test
    fun followsAnIncomingCallFromRingingToEnded() {
        assertTrue(state.accept(update("a", status = 2, name = "Mum")))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Mum"), state.current())

        now = 5_000L
        assertTrue(state.accept(update("a", status = 4, name = "Mum")))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 5_000L), state.current())

        // A repeated update keeps the time the call connected and changes nothing on the card.
        now = 9_000L
        assertFalse(state.accept(update("a", status = 4, name = "Mum")))
        assertTrue(state.accept(update("a", status = 0)))
        assertNull(state.current())
    }

    @Test
    fun anOutgoingCallDialsThenConnects() {
        val number = "+971500000000"
        state.accept(update("b", status = 1, remote = number))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.DIALING, number, number = number), state.current())
        state.accept(update("b", status = 3, name = "Office"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "Office", number = number), state.current())
        state.accept(update("b", status = 4))
        // The name and number stick when a later update leaves them out.
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Office", 1_000L, number), state.current())
    }

    @Test
    fun theNumberIsTheRemoteIdAndEmptyWithoutOne() {
        state.accept(update("a", status = 2, name = "Mum", remote = "+971500000001"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Mum", number = "+971500000001"), state.current())
        state.accept(update("b", status = 2, name = "Hidden"))
        state.accept(update("a", status = 0))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Hidden"), state.current())
    }

    @Test
    fun aHeldCallStaysActiveWithTheTimeItConnected() {
        state.accept(update("a", status = 4, name = "Mum", remote = "+971500000001"))
        now = 7_000L
        assertFalse(state.accept(update("a", status = 5)))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 1_000L, "+971500000001"), state.current())
        // A held call that connects only now counts from the time it connected.
        state.clear()
        state.accept(update("b", status = 5, name = "Boss"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Boss", 7_000L), state.current())
    }

    @Test
    fun aWaitingCallRingsOverTheActiveOneAndTheActiveOneReturns() {
        state.accept(update("a", status = 4, name = "Mum"))
        state.accept(update("b", status = 2, name = "Boss", remote = "+971500000002"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Boss", number = "+971500000002"), state.current())

        state.accept(update("b", status = 6))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 1_000L), state.current())

        // Answering the waiting call holds the first one; the answered call is shown.
        state.accept(update("b", status = 2, name = "Boss", remote = "+971500000002"))
        now = 4_000L
        state.accept(update("a", status = 5))
        state.accept(update("b", status = 4))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Boss", 4_000L, "+971500000002"), state.current())
    }

    @Test
    fun theLabelFollowsTheShownCallWithoutChangingTheCard() {
        state.accept(update("a", status = 4, name = "Mum", label = "mobile"))
        assertFalse(state.accept(update("a", status = 4, label = "home")))
        assertEquals("home", state.snapshot().label)
        state.accept(update("b", status = 2, name = "Boss"))
        assertEquals("", state.snapshot().label)
        state.accept(update("b", status = 0))
        assertEquals("home", state.snapshot().label)
    }

    @Test
    fun anIncomingCallThatNeverConnectedIsDeclinedWithoutAReason() {
        state.accept(update("a", status = 2, name = "Mum", remote = "+971500000001"))
        now = 3_000L
        state.accept(update("a", status = 6))
        assertNull(state.current())
        assertEquals(outcome(CarPlayCallOutcome.Kind.DECLINED, "Mum", 0, 3_000L, "+971500000001"), state.snapshot().lastOutcome)
    }

    @Test
    fun aConnectedCallEndsWithItsTalkTime() {
        state.accept(update("a", status = 2, name = "Mum"))
        now = 5_000L
        state.accept(update("a", status = 4))
        now = 70_900L
        state.accept(update("a", status = 0))
        assertEquals(outcome(CarPlayCallOutcome.Kind.ENDED, "Mum", 65, 70_900L), state.snapshot().lastOutcome)
    }

    @Test
    fun theDisconnectReasonDecidesWhenTheIphoneSendsIt() {
        // Declined is declined even after the call connected.
        state.accept(update("a", status = 4, name = "Mum"))
        now = 4_000L
        state.accept(update("a", status = 0, reason = 1))
        assertEquals(CarPlayCallOutcome.Kind.DECLINED, state.snapshot().lastOutcome?.kind)

        // A ringing call the caller gave up on has ended, not been declined.
        state.accept(update("b", status = 2, name = "Boss"))
        state.accept(update("b", status = 0, reason = 0))
        assertEquals(outcome(CarPlayCallOutcome.Kind.ENDED, "Boss", 0, 4_000L), state.snapshot().lastOutcome)

        // The reason may come with a later disconnect update of the same call.
        state.accept(update("c", status = 4, name = "Office"))
        now = 10_000L
        state.accept(update("c", status = 6))
        assertEquals(outcome(CarPlayCallOutcome.Kind.ENDED, "Office", 6, 10_000L), state.snapshot().lastOutcome)
        now = 11_000L
        state.accept(update("c", status = 0, reason = 1))
        assertEquals(outcome(CarPlayCallOutcome.Kind.DECLINED, "Office", 0, 10_000L), state.snapshot().lastOutcome)
        state.accept(update("x", status = 0, reason = 0))
        assertEquals(CarPlayCallOutcome.Kind.DECLINED, state.snapshot().lastOutcome?.kind)
    }

    @Test
    fun anOutgoingCallThatNeverConnectedEndsWithNoTalkTime() {
        state.accept(update("a", status = 1, remote = "+971500000001"))
        state.accept(update("a", status = 0))
        assertEquals(outcome(CarPlayCallOutcome.Kind.ENDED, "+971500000001", 0, 1_000L, "+971500000001"),
            state.snapshot().lastOutcome)
    }

    @Test
    fun theMicrophoneIsUnknownUntilTheIphoneReportsIt() {
        state.accept(update("a", status = 4, name = "Mum"))
        assertNull(state.snapshot().muted)
        assertFalse(state.accept(mute(true)))
        assertEquals(true, state.snapshot().muted)
        // An update without the mute status keeps the last one.
        state.accept(mute(null))
        assertEquals(true, state.snapshot().muted)
    }

    @Test
    fun theMuteKeyShowsTheExpectedStateUntilTheIphoneReportsOrANewCallStarts() {
        state.accept(update("a", status = 4, name = "Mum"))
        assertTrue(state.muteToggled())
        assertEquals(true, state.snapshot().muted)
        assertFalse(state.muteToggled())
        assertEquals(false, state.snapshot().muted)
        assertTrue(state.muteToggled())

        // The iPhone's own report replaces the expectation.
        state.accept(mute(false))
        assertEquals(false, state.snapshot().muted)
        assertTrue(state.muteToggled())

        // A new call forgets an unconfirmed expectation; an update of the same call keeps it.
        state.accept(update("a", status = 4))
        assertEquals(true, state.snapshot().muted)
        state.accept(update("b", status = 2, name = "Boss"))
        assertEquals(false, state.snapshot().muted)
    }

    @Test
    fun theIphonesReportRightAfterTheMuteKeyIsNotTurnedOverByIt() {
        state.accept(update("a", status = 4, name = "Mum"))
        assertTrue(state.muteToggled())
        state.accept(mute(true))
        assertEquals(true, state.snapshot().muted)
        state.muteNotSent(true)
        assertEquals("a reported state stays", true, state.snapshot().muted)
    }

    @Test
    fun aMuteKeyThatWasNotSentLeavesTheStateItFound() {
        state.accept(update("a", status = 4, name = "Mum"))
        state.accept(mute(true))
        assertFalse(state.muteToggled())
        state.muteNotSent(false)
        assertEquals(true, state.snapshot().muted)
        assertFalse(state.muteToggled())
        assertTrue(state.muteToggled())
        state.muteNotSent(true)
        assertEquals("the earlier expectation is back", false, state.snapshot().muted)
    }

    @Test
    fun otherMessagesAndClearLeaveNoCall() {
        assertFalse(state.accept(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) { u8(2, 2) }))
        assertNull(state.current())
        state.accept(update("a", status = 2, name = "Mum"))
        state.accept(mute(true))
        state.accept(update("a", status = 0))
        state.accept(update("b", status = 2, name = "Boss"))
        state.clear()
        assertNull(state.current())
        assertEquals(CarPlayCallSnapshot(), state.snapshot())
    }

    @Test
    fun theNameFitsBydsCallCard() {
        assertArrayEquals("Mum".toByteArray(Charsets.UTF_16LE), CarPlayCallState.nameBytes("Mum"))
        val long = CarPlayCallState.nameBytes("A".repeat(40))
        assertEquals(CarPlayCallState.MAX_NAME_BYTES, long.size)
        // An emoji is never cut in half.
        val emoji = CarPlayCallState.nameBytes("A".repeat(29) + "😀")
        assertEquals("A".repeat(29), String(emoji, Charsets.UTF_16LE))
    }
}

class CarPlayCallKeyPolicyTest {
    private val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Mum")
    private val active = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 1L)

    private fun press(key: Int, call: CarPlayCallCard?, session: Boolean = true) =
        CarPlayCallKeyPolicy.onKey(key, true, call, session) to CarPlayCallKeyPolicy.onKey(key, false, call, session)

    @Test
    fun theCallKeyAnswersARingingCarPlayCall() {
        assertEquals(Action.CONSUME to Action.ANSWER, press(313, ringing))
        assertEquals(Action.CONSUME to Action.ANSWER, press(KeyEvent.KEYCODE_CALL, ringing))
        // During the call it must not open BYD's phone app over CarPlay, and does not hang up.
        assertEquals(Action.CONSUME to Action.CONSUME, press(313, active))
    }

    @Test
    fun theHangUpKeysEndOrDecline() {
        assertEquals(Action.CONSUME to Action.END, press(KeyEvent.KEYCODE_ENDCALL, ringing))
        assertEquals(Action.CONSUME to Action.END, press(314, active))
        assertEquals(Action.END, CarPlayCallKeyPolicy.onHangUpBroadcast(314, active, session = true))
        assertEquals(Action.END, CarPlayCallKeyPolicy.onHangUpBroadcast(309, ringing, session = true))
    }

    @Test
    fun trackKeysAnswerAndDeclineOnlyWhileACallRings() {
        // #272: the Song Pro DM-i's wheel has only previous/next for calls.
        assertEquals(Action.CONSUME to Action.ANSWER, press(KeyEvent.KEYCODE_MEDIA_NEXT, ringing))
        assertEquals(Action.CONSUME to Action.END, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ringing))
        assertEquals(Action.PASS to Action.PASS, press(KeyEvent.KEYCODE_MEDIA_NEXT, active))
        assertEquals(Action.PASS to Action.PASS, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS, null))
    }

    @Test
    fun withoutACarPlayCallTheCarKeepsItsKeys() {
        assertEquals(Action.PASS to Action.PASS, press(313, null))
        assertEquals(Action.PASS to Action.PASS, press(313, ringing, session = false))
        assertEquals(Action.PASS to Action.PASS, press(KeyEvent.KEYCODE_VOLUME_UP, ringing))
        assertEquals(Action.PASS, CarPlayCallKeyPolicy.onHangUpBroadcast(314, null, session = true))
        assertEquals(Action.PASS, CarPlayCallKeyPolicy.onHangUpBroadcast(305, active, session = true))
    }
}
