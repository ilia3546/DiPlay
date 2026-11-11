package com.shilapi.xcertplay.media

import java.util.AbstractQueue
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded queue of RTP packets in sequence order. Wi-Fi delivers some packets after later ones;
 * played in arrival order, each makes the music jump back and forth. A late packet goes back to its
 * place while a later packet is still queued. One whose place was already taken, or a duplicate, is
 * not queued. When the queue is full the arriving packet is not queued, whatever its place.
 */
internal class RtpReorderQueue<T : Any>(
    private val capacity: Int,
    private val sequenceOf: (T) -> Int,
    private val timestampOf: (T) -> Int,
    private val nanoTime: () -> Long = System::nanoTime,
) : AbstractQueue<T>() {
    enum class Placement { APPENDED, REORDERED, LATE, FULL }

    /** How long the packet after a gap stays queued while the missing packets can still take their places. */
    interface GapWait {
        /** The packet after a gap reached the head of the queue; false lets it go at once, without a wait. */
        fun start(): Boolean
        /** How much longer the wait may last; 0 or less ends it now. */
        fun remainingNanos(): Long
        fun ended(waitedNs: Long, end: GapEnd)
    }

    /**
     * FILLED: the packet after the gap went on with every missing packet that could still be placed. GAVE_UP: it
     * went on without them. ABANDONED: it left the queue another way (a restarted sequence, a stale backlog).
     */
    enum class GapEnd { FILLED, GAVE_UP, ABANDONED }

    private class Entry<T>(val item: T, val sequence: Int, val timestamp: Int, val run: Int)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val entries = LinkedList<Entry<T>>()
    // A run is the packets since the last restart or sequence discontinuity; only they are put in order.
    private var run = 0
    private var newest: Entry<T>? = null
    private var lastTaken: Entry<T>? = null
    // The packet after the gap being waited for; once the wait is given up, that gap is not waited for again.
    private var gapHead: Entry<T>? = null
    private var gapSinceNs = 0L
    private var gapGivenUp = false
    // Waits that ended outside a poll with a GapWait, reported to it at the next one.
    private val abandonedWaitsNs = mutableListOf<Long>()

    fun insert(item: T): Placement {
        val sequence = sequenceOf(item)
        val timestamp = timestampOf(item)
        lock.withLock {
            if (entries.size >= capacity) return Placement.FULL
            val latest = newest
            if (latest != null && (sequence < 0 || latest.sequence < 0 || order(sequence, timestamp, latest) <= 0)) {
                if (isLate(sequence, timestamp, latest)) return insertLate(item, sequence, timestamp)
                startRun()
            }
            val entry = Entry(item, sequence, timestamp, run)
            entries.addLast(entry)
            newest = entry
            notEmpty.signal()
            return Placement.APPENDED
        }
    }

    /** The next packet starts a new sequence: none is dropped as late against the packets before it. */
    fun restartSequence() {
        lock.withLock { startRun() }
    }

    fun poll(timeout: Long, unit: TimeUnit): T? = poll(timeout, unit, null)

    /**
     * With [gapWait], the packet after a gap in its run stays queued for as long as [gapWait] allows, and a
     * packet that fills the gap goes ahead of it. Only the packet right before the head is waited for: once it
     * is too far behind the newest to be put in place, so is the rest of the gap, and the head goes on.
     * One wait covers the whole gap, however many of its packets arrive.
     */
    fun poll(timeout: Long, unit: TimeUnit, gapWait: GapWait?): T? {
        var nanos = unit.toNanos(timeout)
        lock.withLock {
            if (gapWait != null && abandonedWaitsNs.isNotEmpty()) {
                abandonedWaitsNs.forEach { gapWait.ended(it, GapEnd.ABANDONED) }
                abandonedWaitsNs.clear()
            }
            while (true) {
                val head = entries.peekFirst()
                val wait = when {
                    head == null -> nanos
                    gapWait == null -> 0L
                    else -> gapWaitNanos(head, gapWait)
                }
                if (head != null && wait <= 0L) return takeFirst(gapWait)
                if (nanos <= 0L) return null
                val slice = minOf(wait, nanos)
                nanos -= slice - notEmpty.awaitNanos(slice)
            }
        }
    }

    fun remainingCapacity(): Int = lock.withLock { capacity - entries.size }

    override fun offer(e: T): Boolean = when (insert(e)) {
        Placement.APPENDED, Placement.REORDERED -> true
        Placement.LATE, Placement.FULL -> false
    }

    override fun poll(): T? = lock.withLock { takeFirst() }

    override fun peek(): T? = lock.withLock { entries.peekFirst()?.item }

    override val size: Int get() = lock.withLock { entries.size }

    /** A snapshot: the decoder may take packets while the caller iterates. */
    override fun iterator(): MutableIterator<T> =
        Collections.unmodifiableList(lock.withLock { entries.map { it.item } }).iterator()

    override fun clear() {
        lock.withLock {
            entries.clear()
            startRun()
        }
    }

    private fun startRun() {
        run++
        newest = null
        lastTaken = null
        abandonGap()
    }

    private fun takeFirst(gapWait: GapWait? = null): T? {
        val entry = entries.pollFirst() ?: return null
        if (entry.run == run) lastTaken = entry
        if (entry === gapHead) {
            if (gapWait == null) {
                abandonGap()
            } else {
                if (!gapGivenUp) gapWait.ended(nanoTime() - gapSinceNs, GapEnd.FILLED)
                gapHead = null
            }
        }
        return entry.item
    }

    private fun abandonGap() {
        if (gapHead != null && !gapGivenUp) abandonedWaitsNs += nanoTime() - gapSinceNs
        gapHead = null
    }

    private fun gapWaitNanos(head: Entry<T>, gapWait: GapWait): Long {
        if (!followsGap(head)) return 0L
        val placeable = newest?.let { inReorderWindow(head.sequence - 1, it) } == true
        val now = nanoTime()
        if (gapHead == null) {
            if (!placeable || !gapWait.start()) return 0L
            gapHead = head
            gapSinceNs = now
            gapGivenUp = false
        }
        if (gapGivenUp || (!placeable && head !== gapHead)) return 0L
        val remaining = if (placeable) gapWait.remainingNanos() else 0L
        if (remaining <= 0L) {
            gapGivenUp = true
            gapWait.ended(now - gapSinceNs, GapEnd.GAVE_UP)
        }
        return remaining
    }

    /** Packets of [head]'s run are missing between the last one taken and it. */
    private fun followsGap(head: Entry<T>): Boolean {
        val taken = lastTaken ?: return false
        if (head.run != run || head.sequence < 0 || taken.sequence < 0) return false
        return sequenceDelta(head.sequence, taken.sequence) > 1
    }

    /** Further behind the newest packet, [insert] takes a packet as a restarted sequence. */
    private fun inReorderWindow(sequence: Int, latest: Entry<T>): Boolean =
        latest.sequence >= 0 && -sequenceDelta(sequence, latest.sequence) in 0..MAX_REORDER_PACKETS

    /** Behind the newest packet by a reordering, not by a restarted sequence or timestamp. */
    private fun isLate(sequence: Int, timestamp: Int, latest: Entry<T>): Boolean {
        if (sequence < 0) return false
        return inReorderWindow(sequence, latest) && timestamp - latest.timestamp <= 0
    }

    private fun insertLate(item: T, sequence: Int, timestamp: Int): Placement {
        lastTaken?.let { if (order(sequence, timestamp, it) <= 0) return Placement.LATE }
        val position = entries.listIterator(entries.size)
        while (position.hasPrevious()) {
            val queued = position.previous()
            val after = if (queued.run == run) order(sequence, timestamp, queued) else 1
            if (after == 0) return Placement.LATE
            if (after > 0) {
                position.next()
                break
            }
        }
        position.add(Entry(item, sequence, timestamp, run))
        notEmpty.signal()
        return Placement.REORDERED
    }

    /** Positive when the packet comes after [other], negative before it, 0 for the same packet. */
    private fun order(sequence: Int, timestamp: Int, other: Entry<T>): Int {
        val bySequence = sequenceDelta(sequence, other.sequence)
        if (bySequence != 0) return bySequence
        val byTimestamp = timestamp - other.timestamp
        return byTimestamp.coerceIn(-1, 1)
    }

    private fun sequenceDelta(sequence: Int, other: Int): Int = ((sequence - other) shl 16) shr 16

    internal companion object {
        const val NO_SEQUENCE = -1
        /** Further behind, a packet is taken as a restarted sequence rather than a late one. */
        const val MAX_REORDER_PACKETS = 64

        /** The 16-bit sequence number of an RTP packet, or [NO_SEQUENCE] when the header is incomplete. */
        fun rtpSequence(rtp: ByteArray): Int =
            if (rtp.size < RTP_HEADER_BYTES) NO_SEQUENCE
            else ((rtp[2].toInt() and 0xff) shl 8) or (rtp[3].toInt() and 0xff)

        private const val RTP_HEADER_BYTES = 12
    }
}
