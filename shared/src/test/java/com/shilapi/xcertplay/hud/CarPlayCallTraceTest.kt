package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayCallTraceTest {
    private val trace = CarPlayCallTrace()

    private fun call(status: Int) = Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
        string(0, "+971500000000")
        string(1, "Mum")
        u8(2, status)
        u8(3, 1)
        string(4, "a")
        string(7, "mobile")
    }

    @Test
    fun aCallUpdateLogsIdsAndSizesButNoNamesOrNumbers() {
        val line = trace.line(call(status = 4))!!
        assertEquals("CallStateUpdate 0x4155 0:14b 1:4b 2=4 3=1 4:2b 7:7b", line)
        listOf("Mum", "971", "mobile").forEach { assertFalse(it, line.contains(it)) }
    }

    @Test
    fun aCommunicationsUpdateLogsItsOneByteValues() {
        val frame = Iap2Messages.buildRaw(CarPlayCallState.COMMUNICATIONS_UPDATE) {
            u8(0, 3)
            string(3, "Carrier")
            bool(8, true)
            void(16)
        }
        assertEquals("CommunicationsUpdate 0x4158 0=3 3:8b 8=1 16:void", trace.line(frame))
    }

    @Test
    fun aRepeatedUpdateIsLoggedOnceAndOtherMessagesNever() {
        assertTrue(trace.line(call(status = 2)) != null)
        assertNull(trace.line(call(status = 2)))
        assertEquals("CallStateUpdate 0x4155 0:14b 1:4b 2=4 3=1 4:2b 7:7b", trace.line(call(status = 4)))
        assertNull(trace.line(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) { u8(2, 2) }))
    }

    @Test
    fun theSessionAsksForTheCallLabelDisconnectReasonAndMuteStatus() {
        fun requested(messageId: Int) = Iap2ParameterList.parse(
            Iap2ControlMessages.subscriptions().single { it.messageId == messageId }.payload,
        ).asList().map { it.id }

        assertEquals(listOf(0, 1, 2, 3, 4, 7, 11), requested(0x4154))
        assertEquals(listOf(0, 4, 5, 8), requested(0x4157))
    }
}
