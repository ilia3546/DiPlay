package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
    /**
     * The rate of the stream's RTP timestamps. Opus is always decoded at 48 kHz, but audioFormat
     * 0x10000000 and 0x20000000 count at 16 and 24 kHz (see [MicrophoneConfig.opusClockRate]).
     */
    val rtpClockRate: Int = sampleRate,
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    private val resend: Resend? = null,
) : Closeable {
    /** Lost packets are requested again; [setupPeer] is the iPhone's SETUP control port, if known. */
    class Resend(val setupPeer: InetSocketAddress?)

    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false
    private val delivery = Any()
    private var resendRequests: AudioResendRequests? = null

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort()
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        onDiagnostic("Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}")
        val control = bindAnyPort()
        dataSocket = data
        controlSocket = control
        resendRequests = resend?.let {
            AudioResendRequests(it.setupPeer, send = { request, peer ->
                control.send(DatagramPacket(request, request.size, peer))
            }, label = "audio type=$streamType", report = onDiagnostic)
        }
        dataThread = Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ runControl(control, listener) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        // When the head unit opens its 360° camera, default-priority audio threads wait behind it (#737).
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val requests = resendRequests
        val buffer = ByteArray(DATAGRAM_BYTES)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                val sequence = if (packet.length >= RTP_HEADER_LEN) readU16Be(buffer, 2) else null
                stats.received(
                    size = packet.length,
                    sequence = sequence,
                    timestamp = if (packet.length >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                val wire = packet.data.copyOf(packet.length)
                val packetNumber = receivedPackets.incrementAndGet()
                if (wire.size < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType short packet bytes=${wire.size}",
                        )
                    }
                    synchronized(delivery) {
                        listener.onPacket(
                            wire,
                            null,
                            null,
                            IOException("audio packet shorter than RTP header plus tail"),
                        )
                    }
                    stats.processed()
                    requests?.flush()
                    continue
                }

                val sample = readU32Be(wire, 4)
                val payload = try {
                    open(wire)
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure " +
                                "wire=${wire.toHexString()}",
                            error,
                        )
                    }
                    synchronized(delivery) { listener.onPacket(wire, null, sample, error) }
                    stats.processed()
                    requests?.flush()
                    continue
                }
                val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=${wire.size} payloadBytes=${payload.size} " +
                            "payloadHead=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                synchronized(delivery) {
                    requests?.received(readU16Be(wire, 2))
                    deliverLocked(listener, wire, rtp, sample)
                }
                stats.processed()
                requests?.flush()
            }
        } finally {
            stats.flush(ended = true)
            requests?.flush(ended = true)
        }
    }

    private fun runControl(socket: DatagramSocket, listener: Listener) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        val requests = resendRequests
        // Replies reach the renderer under [delivery]; the data thread must not wait behind a lower priority (#737).
        if (requests != null) {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        }
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (closed.get()) return else continue
            }
            if (requests == null) continue
            requests.controlPacket(packet.socketAddress, AudioResendRequests.payloadType(buffer, packet.length))
            val wire = AudioResendRequests.resentDatagram(buffer, packet.length) ?: continue
            if (wire.size < RTP_HEADER_LEN + TAIL_LEN) {
                requests.replyFailed()
                continue
            }
            val payload = try {
                open(wire)
            } catch (_: Exception) {
                requests.replyFailed()
                continue
            }
            synchronized(delivery) {
                if (requests.placeReply(readU16Be(wire, 2), packet.socketAddress) == AudioResendRequests.Reply.TAKEN) {
                    deliverLocked(listener, wire, wire.copyOf(RTP_HEADER_LEN) + payload, readU32Be(wire, 4))
                }
            }
        }
    }

    /** Caller holds [delivery]: the data and control threads both hand packets to [listener]. */
    private fun deliverLocked(listener: Listener, wire: ByteArray, rtp: ByteArray, sample: Int) {
        listener.onPacket(wire, rtp, sample, null)
        if (!started) {
            started = true
            listener.onStarted(sample)
        }
        listener.onRtp(rtp, sample)
    }

    private fun open(wire: ByteArray): ByteArray {
        val aad = wire.copyOfRange(4, RTP_HEADER_LEN)
        val sealedEnd = wire.size - NONCE_LEN
        val sealed = wire.copyOfRange(RTP_HEADER_LEN, sealedEnd)
        val nonce = ByteArray(12).also { wire.copyInto(it, 4, sealedEnd, wire.size) }
        return AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(
                AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType,
                rtpClockRate = MicrophoneConfig.opusClockRate(bits),
            )
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}
