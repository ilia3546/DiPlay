package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayLostAudioRequestTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val startedFormats = CopyOnWriteArrayList<AudioFormat>()
    private val sink = object : MediaSink {
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
            startedFormats += format
        }
    }

    @Test fun musicWithASetupControlPortAsksTheIphoneForItsLostPackets() {
        assertTrue(requestSent(requestLostAudio = true, audioType = "media", sendsControlPort = true))
        assertTrue(startedFormats.single().requestsLostPackets)
    }

    @Test fun nothingIsRequestedWhileTheOptionIsOff() {
        assertFalse(requestSent(requestLostAudio = false, audioType = "media", sendsControlPort = true))
        assertFalse(startedFormats.single().requestsLostPackets)
    }

    @Test fun callsAndStreamsWithoutAControlPortAreLeftAlone() {
        assertFalse(requestSent(requestLostAudio = true, audioType = "telephony", sendsControlPort = true))
        assertFalse(requestSent(requestLostAudio = true, audioType = "media", sendsControlPort = false))
        assertEquals(2, startedFormats.size)
        assertTrue(startedFormats.none { it.requestsLostPackets })
    }

    private fun requestSent(requestLostAudio: Boolean, audioType: String, sendsControlPort: Boolean): Boolean {
        val phone = DatagramSocket(0, loopback).apply { soTimeout = 500 }
        val rtsp = ServerSocket(0, 1, loopback)
        val engine = CarPlayMediaEngine(sink, requestLostAudio = requestLostAudio)
        val session = session(Socket(loopback, rtsp.localPort))
        try {
            val setup = mutableMapOf<String, Any?>("audioType" to audioType, "audioFormat" to 0x800000L, "streamConnectionID" to 42L)
            if (sendsControlPort) setup["controlPort"] = phone.localPort
            val reply = engine.onAudio(session, 102, setup)!!
            val key = CarPlayMediaEngine::class.java
                .getDeclaredMethod("outputKey", AirPlaySession::class.java, Map::class.java)
                .apply { isAccessible = true }.invoke(engine, session, setup) as ByteArray
            listOf(1, 3, 4, 5).forEach { phone.sendTo(seal(key, it), reply["dataPort"] as Int) }
            val received = DatagramPacket(ByteArray(64), 64)
            return try {
                phone.receive(received)
                assertEquals("80d5000100020001", received.data.copyOf(received.length).joinToString("") { "%02x".format(it) })
                true
            } catch (_: SocketTimeoutException) {
                false
            }
        } finally {
            engine.onSessionClosed(session)
            session.close()
            rtsp.close()
            phone.close()
        }
    }

    private fun DatagramSocket.sendTo(bytes: ByteArray, port: Int) =
        send(DatagramPacket(bytes, bytes.size, InetSocketAddress(loopback, port)))

    private fun seal(key: ByteArray, sequence: Int): ByteArray {
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

    private fun session(socket: Socket): AirPlaySession {
        val session = AirPlaySession(
            socket = socket,
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
