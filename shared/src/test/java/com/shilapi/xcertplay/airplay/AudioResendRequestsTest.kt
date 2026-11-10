package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.AudioResendRequests.Reply
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import org.junit.Assert.*
import org.junit.Test

class AudioResendRequestsTest {
    private val setupPeer = InetSocketAddress(InetAddress.getLoopbackAddress(), 7001)
    private val controlPeer = InetSocketAddress(InetAddress.getLoopbackAddress(), 7002)
    private val strangerPeer = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 9)), 7002)
    private var clock = 1_000_000_000L
    private val sent = mutableListOf<Pair<ByteArray, SocketAddress>>()
    private val reports = mutableListOf<String>()

    private fun requests(
        peer: InetSocketAddress? = setupPeer,
        send: (ByteArray, SocketAddress) -> Unit = { bytes, to -> sent += bytes to to },
    ) = AudioResendRequests(peer, send, "audio type=102", reports::add) { clock }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun AudioResendRequests.receive(vararg sequences: Int) = sequences.forEach(::received)

    @Test fun aRequestNamesTheFirstMissingPacketAndTheCount() {
        assertEquals("80d51234fffe0040", AudioResendRequests.request(0x1234, 0xfffe, 64).hex())
    }

    @Test fun aPacketIsRequestedOnceWhenItIsStillMissingThreePacketsBehindTheNewest() {
        val requests = requests()
        requests.receive(10, 12, 11, 13, 14, 15)
        assertTrue("a reordering by one or two asks for nothing", sent.isEmpty())
        requests.received(18)
        assertTrue(sent.isEmpty())
        requests.receive(19, 20, 21, 22)
        assertEquals(listOf("80d5000100100001", "80d5000200110001"), sent.map { it.first.hex() })
        assertEquals(setupPeer, sent.first().second)
    }

    @Test fun aLongGapAsksForItsNewestPacketsWithinTheReorderWindow() {
        val requests = requests()
        requests.receive(10, 100)
        assertEquals(listOf("80d500010024003e"), sent.map { it.first.hex() })
        requests.receive(101, 102, 103)
        assertEquals(listOf("80d500010024003e", "80d5000200620001", "80d5000300630001"), sent.map { it.first.hex() })
        requests.flush(ended = true)
        assertTrue(reports.last(), reports.last().contains("resendRequests=3 resendPacketsRequested=64 "))
    }

    @Test fun aRequestFollowsTheSequenceWrap() {
        requests().receive(65_533, 2)
        assertEquals(listOf("80d50001fffe0002"), sent.map { it.first.hex() })
    }

    @Test fun aPacketFarBehindTheNewestStartsANewSequence() {
        val requests = requests()
        requests.receive(1_000, 1_002, 10)
        assertTrue(sent.isEmpty())
        assertEquals(Reply.NOT_REQUESTED, requests.placeReply(1_001, controlPeer))
        requests.receive(12, 13, 14)
        assertEquals(listOf("80d50001000b0001"), sent.map { it.first.hex() })
        requests.flush(ended = true)
        assertTrue(reports.last(), reports.last().contains("resendRestarts=1 "))
    }

    @Test fun onlyARequestedPacketThatHasNotArrivedIsTaken() {
        val requests = requests()
        requests.receive(10, 20)
        assertEquals(listOf("80d50001000b0007"), sent.map { it.first.hex() })
        requests.received(12)
        assertEquals(Reply.TAKEN, requests.placeReply(11, controlPeer))
        assertEquals(Reply.DUPLICATE, requests.placeReply(11, controlPeer))
        assertEquals(Reply.DUPLICATE, requests.placeReply(12, controlPeer))
        assertEquals(Reply.DUPLICATE, requests.placeReply(20, controlPeer))
        assertEquals(Reply.NOT_REQUESTED, requests.placeReply(18, controlPeer))
        assertEquals(Reply.NOT_REQUESTED, requests.placeReply(25, controlPeer))
        requests.received(90)
        assertEquals(Reply.TOO_LATE, requests.placeReply(13, controlPeer))
        requests.flush(ended = true)
        assertTrue(reports.last(), reports.last().contains(
            "resendTaken=1 resendDuplicate=3 resendTooLate=1 resendNotRequested=2 resendReplyFailures=0 "))
        assertTrue(reports.last(), reports.last().contains("resendTakenTotal=1 "))
    }

    @Test fun atMost20RequestsGoOutEachSecond() {
        val requests = requests()
        var sequence = 0
        requests.received(sequence)
        repeat(26) {
            sequence += 2
            requests.received(sequence)
        }
        assertEquals(AudioResendRequests.MAX_REQUESTS_PER_SECOND, sent.size)
        clock += 1_000_000_000L
        requests.received(sequence + 2)
        assertEquals(AudioResendRequests.MAX_REQUESTS_PER_SECOND + 1, sent.size)
        requests.flush(ended = true)
        assertTrue(reports.last(), reports.last().contains("resendRequests=21 resendPacketsRequested=21 resendRateLimited=5 "))
    }

    @Test fun onlyAControlSenderAtTheIphoneAddressReplacesTheSetupControlPort() {
        val requests = requests()
        requests.receive(1, 5)
        requests.controlPacket(strangerPeer, 0x54)
        requests.received(6)
        requests.controlPacket(controlPeer, 0x54)
        requests.received(7)
        assertEquals(listOf<SocketAddress>(setupPeer, setupPeer, controlPeer), sent.map { it.second })
        assertTrue(reports.first(), reports.first().startsWith("Resend: audio type=102 first request peer=setup "))
        requests.flush(ended = true)
        assertTrue(reports.last(), reports.last().contains("resendPeer=control controlRx=2 controlLastPt=0x54 controlPts=[0x54:2] "))
    }

    @Test fun withoutASetupPeerOnlyAnOpenedReplyGivesTheAddress() {
        val requests = requests(peer = null)
        requests.receive(1, 5)
        requests.controlPacket(controlPeer, 0x54)
        requests.received(6)
        assertTrue(sent.isEmpty())
        requests.flush(ended = true)
        assertTrue(reports.last(), reports.last().contains("resendRequests=0 resendPacketsRequested=0 resendRateLimited=0 resendNoPeer=2 "))
        assertTrue(reports.last(), reports.last().contains("resendPeer=none "))
        requests.placeReply(3, controlPeer)
        requests.received(7)
        assertEquals(controlPeer, sent.single().second)
        assertTrue(reports.any { it.startsWith("Resend: audio type=102 first request peer=control ") })
    }

    @Test fun aFailedSendIsCountedAndDoesNotStopTheReceiver() {
        val requests = requests(send = { _, _ -> throw java.io.IOException("no route") })
        requests.receive(1, 5)
        requests.flush(ended = true)
        assertTrue(reports.last(), reports.last().contains("resendRequests=0 resendPacketsRequested=0 resendRateLimited=0 resendNoPeer=0 resendSendFailures=1 "))
    }

    @Test fun onlyAPayloadType56ReplyCarriesAResentDatagram() {
        val wire = ByteArray(40) { it.toByte() }
        val reply = byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 1) + wire
        assertArrayEquals(wire, AudioResendRequests.resentDatagram(reply, reply.size))
        assertEquals(0x56, AudioResendRequests.payloadType(reply, reply.size))
        val sync = byteArrayOf(0x90.toByte(), 0xd4.toByte(), 0, 7) + wire
        assertNull(AudioResendRequests.resentDatagram(sync, sync.size))
        assertEquals(0x54, AudioResendRequests.payloadType(sync, sync.size))
        assertNull(AudioResendRequests.resentDatagram(reply, 4))
        assertEquals(-1, AudioResendRequests.payloadType(reply, 1))
    }

    @Test fun windowCountersResetButTotalsCarryOn() {
        val requests = requests()
        requests.receive(1, 5)
        clock += 5_000_000_000L
        requests.received(6)
        requests.flush()
        requests.flush(ended = true)
        assertEquals(3, reports.size)
        assertTrue(reports[1], reports[1].contains("resendRequests=2 ") && reports[1].contains("resendRequestsTotal=2 "))
        assertTrue(reports[2], reports[2].contains("resendRequests=0 ") && reports[2].contains("resendRequestsTotal=2 "))
    }
}
