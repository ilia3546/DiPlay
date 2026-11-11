package com.shilapi.xcertplay.media

/**
 * Music waits for packets the iPhone is asked to send again. Their replies come 100 ms or more after the loss,
 * when the packets after the gap are already queued; decoded at once, those leave the reply no place to go.
 * The packet after a gap waits while the track still has more than [FLOOR_NANOS] of sound to play, so the
 * track does not run dry when the reply never comes, and for [MAX_HOLD_NANOS] at most. While the track does
 * not play (before the start or during a rebuffer) it waits only [IDLE_HOLD_NANOS], so music is not held up.
 * The deadline is set when the wait starts and only moves closer: a playback head the platform updates in
 * large steps reads as sound that does not drain.
 */
internal class AudioGapHold(private val nanoTime: () -> Long = System::nanoTime) : RtpReorderQueue.GapWait {
    private var playing = false
    private var floorReachedNs = 0L
    private var deadlineNs = 0L
    private var holds = 0
    private var filled = 0
    private var gaveUp = 0
    private var abandoned = 0
    private var noRoom = 0
    private var maxWaitNs = 0L

    /** Waits given up without their packets; each leaves a hole in the queued level. */
    var gaveUpTotal = 0
        private set

    /** Call before each take from the queue, with the sound written to the track and not played yet. */
    fun observeTrack(playing: Boolean, unplayedNs: Long) {
        this.playing = playing
        floorReachedNs = nanoTime() + unplayedNs - FLOOR_NANOS
    }

    override fun start(): Boolean {
        val now = nanoTime()
        deadlineNs = if (playing) minOf(now + MAX_HOLD_NANOS, floorReachedNs) else now + IDLE_HOLD_NANOS
        if (deadlineNs <= now) {
            noRoom++
            return false
        }
        holds++
        return true
    }

    override fun remainingNanos(): Long {
        if (playing) deadlineNs = minOf(deadlineNs, floorReachedNs)
        return deadlineNs - nanoTime()
    }

    override fun ended(waitedNs: Long, end: RtpReorderQueue.GapEnd) {
        when (end) {
            RtpReorderQueue.GapEnd.FILLED -> filled++
            RtpReorderQueue.GapEnd.GAVE_UP -> {
                gaveUp++
                gaveUpTotal++
            }
            RtpReorderQueue.GapEnd.ABANDONED -> abandoned++
        }
        maxWaitNs = maxOf(maxWaitNs, waitedNs)
    }

    /** The counts since the last call, for the renderer's stats line. */
    fun takeStats(): String {
        val stats = "holds=$holds holdFilled=$filled holdGaveUp=$gaveUp holdAbandoned=$abandoned " +
            "holdNoRoom=$noRoom holdMaxMs=${maxWaitNs / 1_000_000L}"
        holds = 0
        filled = 0
        gaveUp = 0
        abandoned = 0
        noRoom = 0
        maxWaitNs = 0L
        return stats
    }

    internal companion object {
        /** Covers a decoder input wait (100 ms) and the head unit's mixer period after the wait ends, with margin. */
        const val FLOOR_NANOS = 300_000_000L
        /** Ends before [MediaPacketLagGuard.CONGESTION_GRACE_NS]: a wait alone never makes the guard drop its backlog. */
        const val MAX_HOLD_NANOS = 1_000_000_000L
        /** A reply comes about 150 ms after the packet after the gap: the request waits for three more packets. */
        const val IDLE_HOLD_NANOS = 400_000_000L
    }
}
