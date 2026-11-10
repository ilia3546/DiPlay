package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.RtpReorderQueue
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * Asks the iPhone again for realtime audio packets that never arrived, on the stream's control port.
 *
 * A request is `0x80 0xD5`, a request number, the first missing RTP sequence and a count, each a 16-bit
 * big-endian value. The answer comes back on the control port with payload type 0x56: a 4-byte header and
 * then the original sealed RTP datagram. Only packets that opened with the stream key may reach [received]
 * and [placeReply], and the caller must hand a packet to the renderer under the same lock as that call, so
 * the newest sequence here is the newest one in the renderer's reorder queue. Reports carry counts only.
 */
internal class AudioResendRequests(
    private val setupPeer: InetSocketAddress?,
    private val send: (ByteArray, SocketAddress) -> Unit,
    private val label: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    enum class Reply { TAKEN, DUPLICATE, TOO_LATE, NOT_REQUESTED }

    private val lock = Any()

    // Guarded by [lock]. Bit i of a mask is the packet i + 1 behind [newest].
    private var newest: Int? = null
    private var missing = 0L
    private var requested = 0L
    private var controlPeer: SocketAddress? = null
    private var requestNumber = 0
    private var rateWindowStart = nowNs()
    private var requestsInRateWindow = 0
    private var firstRequestLogged = false
    private var windowStart = nowNs()

    // Guarded by [lock]; a report resets all but the totals.
    private var requests = 0
    private var packetsRequested = 0
    private var rateLimited = 0
    private var noPeer = 0
    private var sendFailures = 0
    private var restarts = 0
    private var controlRx = 0
    private var controlLastPt = -1
    private val controlPayloadTypes = sortedMapOf<Int, Int>()
    private var replies = 0
    private var repliesTaken = 0
    private var repliesDuplicate = 0
    private var repliesTooLate = 0
    private var repliesNotRequested = 0
    private var replyFailures = 0
    private var requestsTotal = 0
    private var packetsRequestedTotal = 0
    private var repliesTotal = 0
    private var repliesTakenTotal = 0

    fun received(sequence: Int) {
        synchronized(lock) {
            val latest = newest
            if (latest == null) {
                startRun(sequence)
                return
            }
            val ahead = sequenceDelta(sequence, latest)
            when {
                ahead > 0 -> {
                    val gap = ahead - 1
                    val gapBits = if (gap >= WINDOW) WINDOW_BITS else (1L shl gap) - 1
                    missing = (if (ahead >= WINDOW) 0L else (missing shl ahead) and WINDOW_BITS) or gapBits
                    requested = if (ahead >= WINDOW) 0L else (requested shl ahead) and WINDOW_BITS
                    newest = sequence
                    requestMissing(sequence)
                }
                ahead == 0 -> Unit
                -ahead <= WINDOW -> {
                    val bit = 1L shl (-ahead - 1)
                    missing = missing and bit.inv()
                    requested = requested and bit.inv()
                }
                else -> {
                    restarts++
                    startRun(sequence)
                }
            }
        }
    }

    fun controlPacket(sender: SocketAddress?, payloadType: Int) {
        synchronized(lock) {
            val from = sender as? InetSocketAddress
            if (from != null && setupPeer != null && from.address == setupPeer.address) controlPeer = from
            controlRx++
            controlLastPt = payloadType
            controlPayloadTypes[payloadType] = (controlPayloadTypes[payloadType] ?: 0) + 1
            if (payloadType == RESPONSE_PAYLOAD_TYPE) {
                replies++
                repliesTotal++
            }
        }
    }

    /**
     * Whether a reply that opened with the stream key goes on to the renderer: only a packet that was
     * requested and has not arrived since. Within [RtpReorderQueue.MAX_REORDER_PACKETS] of the newest
     * packet the queue puts it in place; further back the queue would start a new sequence with it.
     */
    fun placeReply(sequence: Int, sender: SocketAddress?): Reply {
        synchronized(lock) {
            if (setupPeer == null && sender != null) controlPeer = sender
            val latest = newest
            val behind = if (latest == null) 0 else -sequenceDelta(sequence, latest)
            val result = when {
                latest == null || behind < 0 -> Reply.NOT_REQUESTED
                behind == 0 -> Reply.DUPLICATE
                behind > WINDOW -> Reply.TOO_LATE
                else -> {
                    val bit = 1L shl (behind - 1)
                    when {
                        requested and bit != 0L -> {
                            requested = requested and bit.inv()
                            Reply.TAKEN
                        }
                        missing and bit != 0L -> Reply.NOT_REQUESTED
                        else -> Reply.DUPLICATE
                    }
                }
            }
            when (result) {
                Reply.TAKEN -> { repliesTaken++; repliesTakenTotal++ }
                Reply.DUPLICATE -> repliesDuplicate++
                Reply.TOO_LATE -> repliesTooLate++
                Reply.NOT_REQUESTED -> repliesNotRequested++
            }
            return result
        }
    }

    fun replyFailed() {
        synchronized(lock) { replyFailures++ }
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        val line = synchronized(lock) {
            if (!ended && now - windowStart < REPORT_INTERVAL_NS) return
            val line = "Resend: $label resendRequests=$requests resendPacketsRequested=$packetsRequested " +
                "resendRateLimited=$rateLimited resendNoPeer=$noPeer resendSendFailures=$sendFailures " +
                "resendPeer=${peerSource()} controlRx=$controlRx " +
                "controlLastPt=${if (controlLastPt < 0) "none" else "0x%02x".format(controlLastPt)} " +
                "controlPts=[${controlPayloadTypes.entries.joinToString(",") { "0x%02x:%d".format(it.key, it.value) }}] " +
                "resendReplies=$replies resendTaken=$repliesTaken resendDuplicate=$repliesDuplicate " +
                "resendTooLate=$repliesTooLate resendNotRequested=$repliesNotRequested " +
                "resendReplyFailures=$replyFailures resendRestarts=$restarts " +
                "resendRequestsTotal=$requestsTotal resendPacketsRequestedTotal=$packetsRequestedTotal " +
                "resendRepliesTotal=$repliesTotal resendTakenTotal=$repliesTakenTotal ended=$ended " +
                "windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000}"
            windowStart = now
            resetWindow()
            line
        }
        runCatching { report(line) }
    }

    private fun startRun(sequence: Int) {
        newest = sequence
        missing = 0L
        requested = 0L
    }

    /** Asks once for each run of packets still missing [REQUEST_AFTER_PACKETS] behind [latest], oldest first. */
    private fun requestMissing(latest: Int) {
        var ready = missing and READY_BITS
        missing = missing and ready.inv()
        while (ready != 0L) {
            val oldest = 63 - java.lang.Long.numberOfLeadingZeros(ready)
            val count = java.lang.Long.numberOfLeadingZeros((ready shl (63 - oldest)).inv())
            val run = if (count >= 64) -1L else ((1L shl count) - 1) shl (oldest - count + 1)
            ready = ready and run.inv()
            if (request((latest - 1 - oldest) and 0xffff, count)) requested = requested or run
        }
    }

    private fun request(firstMissing: Int, count: Int): Boolean {
        val now = nowNs()
        if (now - rateWindowStart >= RATE_WINDOW_NS) {
            rateWindowStart = now
            requestsInRateWindow = 0
        }
        if (requestsInRateWindow >= MAX_REQUESTS_PER_SECOND) {
            rateLimited++
            return false
        }
        val fromControl = controlPeer
        val peer = fromControl ?: setupPeer
        if (peer == null) {
            noPeer++
            return false
        }
        requestsInRateWindow++
        requestNumber = (requestNumber + 1) and 0xffff
        if (!firstRequestLogged) {
            firstRequestLogged = true
            runCatching {
                report("Resend: $label first request peer=${if (fromControl != null) "control" else "setup"} " +
                    "first=$firstMissing count=$count")
            }
        }
        if (runCatching { send(request(requestNumber, firstMissing, count), peer) }.isFailure) {
            sendFailures++
            return false
        }
        requests++
        requestsTotal++
        packetsRequested += count
        packetsRequestedTotal += count
        return true
    }

    private fun peerSource(): String = when {
        controlPeer != null -> "control"
        setupPeer != null -> "setup"
        else -> "none"
    }

    private fun resetWindow() {
        requests = 0
        packetsRequested = 0
        rateLimited = 0
        noPeer = 0
        sendFailures = 0
        restarts = 0
        controlRx = 0
        controlLastPt = -1
        controlPayloadTypes.clear()
        replies = 0
        repliesTaken = 0
        repliesDuplicate = 0
        repliesTooLate = 0
        repliesNotRequested = 0
        replyFailures = 0
    }

    private fun sequenceDelta(sequence: Int, other: Int): Int = ((sequence - other) shl 16) shr 16

    companion object {
        const val REQUEST_PAYLOAD_TYPE = 0x55
        const val RESPONSE_PAYLOAD_TYPE = 0x56
        const val RESPONSE_HEADER_LEN = 4
        const val MAX_REQUESTS_PER_SECOND = 20
        /** A missing packet is requested once the newest is this many packets past it; Wi-Fi reorders by one or two. */
        const val REQUEST_AFTER_PACKETS = 3
        private val WINDOW = minOf(RtpReorderQueue.MAX_REORDER_PACKETS, Long.SIZE_BITS)
        private val WINDOW_BITS = if (WINDOW == Long.SIZE_BITS) -1L else (1L shl WINDOW) - 1
        private val READY_BITS = WINDOW_BITS and (-1L shl (REQUEST_AFTER_PACKETS - 1))
        private const val RATE_WINDOW_NS = 1_000_000_000L
        private const val REPORT_INTERVAL_NS = 5_000_000_000L

        fun request(number: Int, firstMissing: Int, count: Int): ByteArray = byteArrayOf(
            0x80.toByte(), (0x80 or REQUEST_PAYLOAD_TYPE).toByte(),
            (number ushr 8).toByte(), number.toByte(),
            (firstMissing ushr 8).toByte(), firstMissing.toByte(),
            (count ushr 8).toByte(), count.toByte(),
        )

        /** A control datagram's RTP payload type, without the marker bit; -1 when it is too short. */
        fun payloadType(datagram: ByteArray, length: Int): Int =
            if (length < 2) -1 else datagram[1].toInt() and 0x7f

        /** The sealed RTP datagram inside a 0x56 reply, or null for any other control datagram. */
        fun resentDatagram(datagram: ByteArray, length: Int): ByteArray? =
            if (payloadType(datagram, length) != RESPONSE_PAYLOAD_TYPE || length <= RESPONSE_HEADER_LEN) null
            else datagram.copyOfRange(RESPONSE_HEADER_LEN, length)
    }
}
