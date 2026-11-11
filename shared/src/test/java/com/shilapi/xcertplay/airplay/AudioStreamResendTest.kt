package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioStreamResendTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val loopback = InetAddress.getLoopbackAddress()
    private val delivered = CopyOnWriteArrayList<Int>()
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val listener = object : AudioStream.Listener {
        override fun onRtp(rtp: ByteArray, sample: Int) {
            delivered += ((rtp[2].toInt() and 0xff) shl 8) or (rtp[3].toInt() and 0xff)
        }
    }

    @Test fun aGapIsRequestedOnTheControlPortAndTheReplyReachesTheListener() {
        val phone = DatagramSocket(0, loopback).apply { soTimeout = 3_000 }
        val stream = AudioStream(key, 102, diagnostics::add, AudioStream.Resend(InetSocketAddress(loopback, phone.localPort)))
        try {
            val (dataPort, controlPort) = stream.listen(listener)
            phone.sendTo(seal(1), dataPort)
            // A packet that does not open must not move the sequence: 0x8001 would hide every real packet after it.
            phone.sendTo(ByteArray(40).also { it[0] = 0x80.toByte(); it[1] = 0x66; it[2] = 0x80.toByte(); it[3] = 1 }, dataPort)
            listOf(2, 5, 6).forEach { phone.sendTo(seal(it), dataPort) }
            val request = DatagramPacket(ByteArray(64), 64).also(phone::receive)
            assertEquals("80d5000100030001", request.data.copyOf(request.length).hex())
            waitFor { delivered.size == 4 }

            phone.sendTo(byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 1) + ByteArray(40), controlPort)
            phone.sendTo(byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 1) + seal(3), controlPort)
            waitFor { delivered.size == 5 }
            assertEquals(listOf(1, 2, 5, 6, 3), delivered.toList())
        } finally {
            stream.close()
            phone.close()
        }
        waitFor { diagnostics.any { it.startsWith("Resend: ") && it.contains("ended=true") } }
        val line = diagnostics.last { it.startsWith("Resend: ") }
        assertTrue(line, line.contains("resendRequests=1 resendPacketsRequested=1 "))
        assertTrue(line, line.contains("resendPeer=control controlRx=2 controlLastPt=0x56 controlPts=[0x56:2] "))
        assertTrue(line, line.contains("resendReplies=2 resendTaken=1 resendDuplicate=0 resendTooLate=0 resendNotRequested=0 resendReplyFailures=1 "))
    }

    @Test fun theFirstTwoRepliesThatDoNotOpenAreDescribedWithoutBeingPlayed() {
        val phone = DatagramSocket(0, loopback).apply { soTimeout = 3_000 }
        val stream = AudioStream(key, 102, diagnostics::add, AudioStream.Resend(InetSocketAddress(loopback, phone.localPort)))
        val prefix = byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 1)
        try {
            val (_, controlPort) = stream.listen(listener)
            phone.sendTo(prefix + byteArrayOf(9, 9, 9, 9) + seal(3), controlPort)
            phone.sendTo(prefix + ByteArray(8), controlPort)
            phone.sendTo(prefix + ByteArray(40), controlPort)
            waitFor { diagnostics.count { it.contains(" did not open ") } == 2 }
        } finally {
            stream.close()
            phone.close()
        }
        waitFor { diagnostics.any { it.startsWith("Resend: ") && it.contains("ended=true") } }
        assertTrue(diagnostics.last { it.startsWith("Resend: ") }.contains(" resendReplyFailures=3 "))
        assertEquals(listOf(
            "Resend: audio type=102 reply did not open bytes=60 hdr=80d60001 rtp=09090909 80660003 00000c00 " +
                "tail=03040506 0708090a opensAt=0:no,4:no,8:yes,12:no",
            "Resend: audio type=102 reply did not open bytes=12 hdr=80d60001 rtp=00000000 00000000 " +
                "tail=00000000 00000000 opensAt=0:no,4:no,8:no,12:no",
        ), diagnostics.filter { it.contains(" did not open ") })
        assertTrue(delivered.isEmpty())
    }

    @Test fun withoutResendNothingIsSentAndControlRepliesAreIgnored() {
        val phone = DatagramSocket(0, loopback).apply { soTimeout = 500 }
        val stream = AudioStream(key, 102, diagnostics::add)
        try {
            val (dataPort, controlPort) = stream.listen(listener)
            listOf(1, 2, 5, 6).forEach { phone.sendTo(seal(it), dataPort) }
            phone.sendTo(byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 1) + seal(3), controlPort)
            waitFor { delivered.size == 4 }
            assertThrows(SocketTimeoutException::class.java) { phone.receive(DatagramPacket(ByteArray(64), 64)) }
            assertEquals(listOf(1, 2, 5, 6), delivered.toList())
        } finally {
            stream.close()
            phone.close()
        }
        assertTrue(diagnostics.none { it.startsWith("Resend: ") })
    }

    private fun DatagramSocket.sendTo(bytes: ByteArray, port: Int) =
        send(DatagramPacket(bytes, bytes.size, InetSocketAddress(loopback, port)))

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun seal(sequence: Int): ByteArray {
        val timestamp = sequence * 1024L
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x66
        header[2] = (sequence shr 8).toByte(); header[3] = sequence.toByte()
        for (i in 0 until 4) header[4 + i] = (timestamp shr (24 - 8 * i)).toByte()
        val nonceTail = ByteArray(8) { (sequence + it).toByte() }
        val nonce = ByteArray(12).also { nonceTail.copyInto(it, 4) }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, ByteArray(16) { sequence.toByte() }, header.copyOfRange(4, 12))
        return header + sealed + nonceTail
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("condition not met in time", condition())
    }
}
