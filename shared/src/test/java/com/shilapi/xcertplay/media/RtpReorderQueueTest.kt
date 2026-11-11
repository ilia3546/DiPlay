package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.RtpReorderQueue.Placement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class RtpReorderQueueTest {
    private data class Packet(val sequence: Int, val sample: Int)

    private fun queue(capacity: Int = 192) = RtpReorderQueue<Packet>(capacity, Packet::sequence, Packet::sample)

    /** AAC-LC: 1024 samples per packet. */
    private fun packet(sequence: Int, base: Long = 0L) =
        Packet(sequence and 0xffff, (base + sequence.toLong() * 1024).toInt())

    private fun RtpReorderQueue<Packet>.drain(): List<Int> = generateSequence { poll() }.map { it.sequence }.toList()

    @Test fun packetsInOrderPassUnchanged() {
        val queue = queue()
        (100 until 120).forEach { assertEquals(Placement.APPENDED, queue.insert(packet(it))) }
        assertEquals((100 until 120).toList(), queue.drain())
    }

    @Test fun aPacketUpToSevenBehindGoesBackToItsPlace() {
        for (behind in 1..7) {
            val queue = queue()
            val arrival = (0..20).toMutableList().apply { add(5 + behind, removeAt(5)) }
            val placements = arrival.map { queue.insert(packet(it)) }
            assertEquals("behind=$behind", 1, placements.count { it == Placement.REORDERED })
            assertEquals("behind=$behind", (0..20).toList(), queue.drain())
        }
    }

    @Test fun severalLatePacketsInOneBurstAreAllRestored() {
        val queue = queue()
        listOf(0, 1, 4, 2, 6, 3, 5, 9, 7, 8, 10).forEach { queue.insert(packet(it)) }
        assertEquals((0..10).toList(), queue.drain())
    }

    @Test fun aLatePacketWhoseNextWasAlreadyTakenIsDroppedNotPlayed() {
        val queue = queue()
        listOf(1, 3, 4).forEach { queue.insert(packet(it)) }
        assertEquals(1, queue.poll()!!.sequence)
        assertEquals(3, queue.poll()!!.sequence)
        assertEquals(Placement.LATE, queue.insert(packet(2)))
        assertEquals(listOf(4), queue.drain())
    }

    @Test fun aLatePacketGoesFirstWhileItsNextIsStillQueued() {
        val queue = queue()
        listOf(1, 3).forEach { queue.insert(packet(it)) }
        assertEquals(1, queue.poll()!!.sequence)
        assertEquals(Placement.REORDERED, queue.insert(packet(2)))
        assertEquals(listOf(2, 3), queue.drain())
    }

    @Test fun packetsSkippedFromTheHeadCountAsTaken() {
        // MediaPacketLagGuard discards stale packets with poll(); their places are gone too.
        val queue = queue()
        listOf(1, 2, 4).forEach { queue.insert(packet(it)) }
        queue.poll()
        queue.poll()
        assertEquals(Placement.REORDERED, queue.insert(packet(3)))
        queue.poll()
        assertEquals(Placement.LATE, queue.insert(packet(2)))
    }

    @Test fun duplicatesAreDroppedQueuedOrTaken() {
        val queue = queue()
        listOf(5, 6, 7).forEach { queue.insert(packet(it)) }
        assertEquals(Placement.LATE, queue.insert(packet(7)))
        assertEquals(Placement.LATE, queue.insert(packet(6)))
        assertEquals(5, queue.poll()!!.sequence)
        assertEquals(Placement.LATE, queue.insert(packet(5)))
        assertEquals(listOf(6, 7), queue.drain())
    }

    @Test fun sequenceAndTimestampWrapKeepTheOrder() {
        val queue = queue()
        val base = 0xffff_f000L - 65_533L * 1024
        listOf(65_533, 65_534, 65_536, 65_535, 65_538, 65_537).forEach {
            assertNotEquals(Placement.LATE, queue.insert(packet(it, base)))
        }
        assertEquals(listOf(65_533, 65_534, 65_535, 0, 1, 2), queue.drain())
        assertEquals(Placement.LATE, queue.insert(packet(65_535, base)))
    }

    @Test fun theTimestampOrdersPacketsWithTheSameSequence() {
        val queue = queue()
        listOf(0, 2048, 1024, 3072).forEach { queue.insert(Packet(0, it)) }
        assertEquals(listOf(0, 1024, 2048, 3072), generateSequence { queue.poll() }.map { it.sample }.toList())
        assertEquals(Placement.LATE, queue.insert(Packet(0, 3072)))
    }

    @Test fun aRestartedSequenceIsNeverDroppedAsLate() {
        val queue = queue()
        (1000..1010).forEach { queue.insert(packet(it)) }
        queue.drain()
        assertEquals(Placement.LATE, queue.insert(packet(1005)))
        queue.restartSequence()
        (1001..1003).forEach { assertEquals(Placement.APPENDED, queue.insert(packet(it))) }
        assertEquals(listOf(1001, 1002, 1003), queue.drain())
    }

    @Test fun aJumpFarBackStartsANewSequence() {
        val queue = queue()
        (40_000..40_005).forEach { queue.insert(packet(it)) }
        queue.drain()
        (39_000..39_002).forEach { assertEquals(Placement.APPENDED, queue.insert(packet(it))) }
        assertEquals(Placement.REORDERED, queue.insert(packet(38_999)))
        assertEquals(listOf(38_999, 39_000, 39_001, 39_002), queue.drain())
    }

    @Test fun aTimestampAheadOfASequenceBehindIsANewSequence() {
        val queue = queue()
        (10..12).forEach { queue.insert(packet(it)) }
        queue.drain()
        assertEquals(Placement.APPENDED, queue.insert(Packet(11, 100_000)))
    }

    @Test fun aPacketBeforeANewSequenceIsNotOrderedAgainstTheOldOne() {
        val queue = queue()
        (500..502).forEach { queue.insert(packet(it)) }
        queue.restartSequence()
        listOf(11, 10).forEach { queue.insert(packet(it)) }
        assertEquals(listOf(500, 501, 502, 10, 11), queue.drain())
    }

    @Test fun aFullQueueRefusesEveryArrivingPacket() {
        val queue = queue(capacity = 3)
        listOf(1, 3, 4).forEach { queue.insert(packet(it)) }
        assertEquals(Placement.FULL, queue.insert(packet(5)))
        assertEquals(Placement.FULL, queue.insert(packet(2)))
        assertEquals(0, queue.remainingCapacity())
        assertEquals(listOf(1, 3, 4), queue.drain())
    }

    @Test fun packetsWithoutASequenceKeepTheirArrivalOrder() {
        val queue = queue()
        listOf(3, 1, 2).forEach { assertEquals(Placement.APPENDED, queue.insert(Packet(RtpReorderQueue.NO_SEQUENCE, it))) }
        assertEquals(listOf(3, 1, 2), generateSequence { queue.poll() }.map { it.sample }.toList())
    }

    @Test fun rtpSequenceReadsHeaderBytesTwoAndThree() {
        val rtp = ByteArray(12).apply { this[2] = 0xab.toByte(); this[3] = 0xcd.toByte() }
        assertEquals(0xabcd, RtpReorderQueue.rtpSequence(rtp))
        assertEquals(RtpReorderQueue.NO_SEQUENCE, RtpReorderQueue.rtpSequence(ByteArray(4)))
    }

    @Test fun pollWaitsForAPacketAndTimesOutWithoutOne() {
        val queue = queue()
        assertNull(queue.poll(1, TimeUnit.MILLISECONDS))
        val waiting = CountDownLatch(1)
        var taken: Packet? = null
        val consumer = Thread {
            waiting.countDown()
            taken = queue.poll(5, TimeUnit.SECONDS)
        }.apply { start() }
        waiting.await()
        queue.insert(packet(7))
        consumer.join(5_000)
        assertFalse(consumer.isAlive)
        assertEquals(7, taken?.sequence)
    }

    @Test fun aConcurrentDecoderNeverPlaysAPacketOutOfOrder() {
        val queue = queue()
        val total = 20_000
        // Every eighth packet arrives after the next one to five packets.
        val arrival = (0 until total).toMutableList()
        for (start in 0 until total - 8 step 8) arrival.add(start + 1 + start / 8 % 5, arrival.removeAt(start))
        val taken = mutableListOf<Int>()
        val produced = AtomicBoolean(false)
        val decoder = Thread {
            while (true) {
                val packet = queue.poll(1, TimeUnit.MILLISECONDS) ?: if (produced.get() && queue.isEmpty()) break else continue
                taken += packet.sample / 1024
            }
        }.apply { start() }
        var late = 0
        var reordered = 0
        for ((index, sequence) in arrival.withIndex()) {
            while (queue.remainingCapacity() == 0) Thread.yield()
            when (queue.insert(packet(sequence))) {
                Placement.LATE -> late++
                Placement.REORDERED -> reordered++
                else -> Unit
            }
            if (index % 64 == 0) Thread.yield()
        }
        produced.set(true)
        decoder.join(10_000)
        assertTrue("nothing was reordered", reordered > 0)
        assertFalse(decoder.isAlive)
        assertEquals(taken.sorted(), taken)
        assertEquals(taken.distinct().size, taken.size)
        assertEquals(total, taken.size + late)
    }

    private var nowNs = 0L

    private inner class RecordingWait(private val allowMs: Long, private val room: Boolean = true) : RtpReorderQueue.GapWait {
        val events = mutableListOf<String>()
        private var deadlineNs = 0L
        override fun start(): Boolean {
            events += "start"
            deadlineNs = nowNs + allowMs * 1_000_000
            return room
        }
        override fun remainingNanos() = deadlineNs - nowNs
        override fun ended(waitedNs: Long, end: RtpReorderQueue.GapEnd) {
            events += "${end.name.lowercase()} after ${waitedNs / 1_000_000} ms"
        }
    }

    private fun clockedQueue() = RtpReorderQueue<Packet>(192, Packet::sequence, Packet::sample) { nowNs }

    private fun RtpReorderQueue<Packet>.take(wait: RtpReorderQueue.GapWait?) = poll(0, TimeUnit.MILLISECONDS, wait)?.sequence

    private fun RtpReorderQueue<Packet>.drain(wait: RtpReorderQueue.GapWait) = generateSequence { take(wait) }.toList()

    @Test fun thePacketAfterAGapWaitsForTheMissingOneAndThenPlaysInOrder() {
        val queue = clockedQueue()
        val wait = RecordingWait(1_000)
        listOf(1, 2).forEach { queue.insert(packet(it)) }
        assertEquals(listOf(1, 2), queue.drain(wait))
        listOf(4, 5).forEach { queue.insert(packet(it)) }
        assertNull(queue.take(wait))
        nowNs += 120_000_000
        assertNull(queue.take(wait))
        assertEquals(Placement.REORDERED, queue.insert(packet(3)))
        assertEquals(listOf(3, 4, 5), queue.drain(wait))
        assertEquals(listOf("start", "filled after 120 ms"), wait.events)
    }

    @Test fun aGapThatNeverFillsIsGivenUpWithoutLosingThePacketsAfterIt() {
        val queue = clockedQueue()
        val wait = RecordingWait(100)
        queue.insert(packet(1))
        assertEquals(1, queue.take(wait))
        listOf(3, 4).forEach { queue.insert(packet(it)) }
        assertNull(queue.take(wait))
        nowNs += 100_000_000
        assertEquals(listOf(3, 4), queue.drain(wait))
        assertEquals(Placement.LATE, queue.insert(packet(2)))
        assertEquals(listOf("start", "gave_up after 100 ms"), wait.events)
    }

    @Test fun aPartlyFilledGapKeepsOneDeadline() {
        val queue = clockedQueue()
        val wait = RecordingWait(100)
        queue.insert(packet(1))
        assertEquals(1, queue.take(wait))
        queue.insert(packet(5))
        assertNull(queue.take(wait))
        nowNs += 60_000_000
        queue.insert(packet(4))
        assertNull(queue.take(wait))
        nowNs += 40_000_000
        assertEquals(listOf(4, 5), queue.drain(wait))
        assertEquals(listOf("start", "gave_up after 100 ms"), wait.events)
    }

    @Test fun aGivenUpGapIsNotWaitedForAgainAfterPartOfItArrived() {
        val queue = clockedQueue()
        val wait = RecordingWait(100)
        queue.insert(packet(1))
        assertEquals(1, queue.take(wait))
        queue.insert(packet(5))
        assertNull(queue.take(wait))
        queue.insert(packet(3))
        nowNs += 100_000_000
        assertEquals(3, queue.take(wait))
        assertEquals(5, queue.take(wait))
        assertEquals(Placement.LATE, queue.insert(packet(4)))
        assertEquals(listOf("start", "gave_up after 100 ms"), wait.events)
    }

    @Test fun withoutAGapWaitTheQueueGoesPastAGapAtOnce() {
        val queue = clockedQueue()
        listOf(1, 3).forEach { queue.insert(packet(it)) }
        assertEquals(listOf(1, 3), List(2) { queue.take(null) })
    }

    @Test fun theGapBetweenTwoRunsIsNotWaitedFor() {
        val queue = clockedQueue()
        val wait = RecordingWait(1_000)
        queue.insert(packet(1))
        assertEquals(1, queue.take(wait))
        queue.insert(packet(3))
        queue.restartSequence()
        queue.insert(packet(100))
        assertEquals(listOf(3, 100), queue.drain(wait))
        queue.restartSequence()
        queue.insert(packet(7))
        assertEquals(7, queue.take(wait))
        assertEquals(emptyList<String>(), wait.events)
    }

    @Test fun aLongGapWaitsForTheTailThatCanStillBePlacedAndSkipsItsStart() {
        // On the car a Wi-Fi drop lost 72 and 118 packets; the iPhone was asked for the last 64 of them.
        for (gap in listOf(72, 118)) {
            nowNs = 0
            val queue = clockedQueue()
            val wait = RecordingWait(1_000)
            queue.insert(packet(100))
            assertEquals(100, queue.take(wait))
            val afterGap = 101 + gap
            queue.insert(packet(afterGap))
            assertNull(queue.take(wait))
            val newest = afterGap + 7
            (afterGap + 1..newest).forEach { queue.insert(packet(it)) }
            assertNull(queue.take(wait))
            nowNs += 150_000_000
            val firstPlaceable = newest - RtpReorderQueue.MAX_REORDER_PACKETS
            (firstPlaceable until afterGap).forEach { assertEquals("gap=$gap $it", Placement.REORDERED, queue.insert(packet(it))) }
            assertEquals("gap=$gap", (firstPlaceable..newest).toList(), queue.drain(wait))
            assertEquals("gap=$gap", listOf("start", "filled after 150 ms"), wait.events)
        }
    }

    @Test fun aGapAlreadyTooFarBehindIsNotWaitedForAndOneThatFallsBehindIsGivenUp() {
        val queue = clockedQueue()
        val wait = RecordingWait(1_000)
        queue.insert(packet(1))
        assertEquals(1, queue.take(wait))
        (3..3 + RtpReorderQueue.MAX_REORDER_PACKETS).forEach { queue.insert(packet(it)) }
        assertEquals(3, queue.take(wait))
        assertEquals(emptyList<String>(), wait.events)

        val next = clockedQueue()
        next.insert(packet(200))
        assertEquals(200, next.take(wait))
        next.insert(packet(202))
        assertNull(next.take(wait))
        (203..201 + RtpReorderQueue.MAX_REORDER_PACKETS).forEach { next.insert(packet(it)) }
        assertNull(next.take(wait))
        next.insert(packet(202 + RtpReorderQueue.MAX_REORDER_PACKETS))
        assertEquals(202, next.take(wait))
        assertEquals(listOf("start", "gave_up after 0 ms"), wait.events)
    }

    @Test fun aGapWithoutRoomToWaitGoesOnAtOnceAndEndsNothing() {
        val queue = clockedQueue()
        val wait = RecordingWait(1_000, room = false)
        queue.insert(packet(1))
        assertEquals(1, queue.take(wait))
        listOf(3, 4).forEach { queue.insert(packet(it)) }
        assertEquals(listOf(3, 4), queue.drain(wait))
        assertEquals(listOf("start"), wait.events)
    }

    @Test fun aWaitEndedByARestartOrTheLagGuardIsReportedAsAbandoned() {
        val queue = clockedQueue()
        val wait = RecordingWait(1_000)
        queue.insert(packet(1))
        assertEquals(1, queue.take(wait))
        queue.insert(packet(3))
        assertNull(queue.take(wait))
        nowNs += 40_000_000
        queue.restartSequence()
        queue.insert(packet(100))
        assertEquals(listOf(3, 100), queue.drain(wait))
        assertEquals(listOf("start", "abandoned after 40 ms"), wait.events)

        queue.insert(packet(102))
        assertNull(queue.take(wait))
        nowNs += 30_000_000
        // MediaPacketLagGuard drops stale packets with poll().
        assertEquals(102, queue.poll()?.sequence)
        queue.insert(packet(103))
        assertEquals(103, queue.take(wait))
        assertEquals(listOf("start", "abandoned after 40 ms", "start", "abandoned after 30 ms"), wait.events)
    }

    @Test fun aPacketThatFillsTheGapWakesTheWait() {
        val queue = queue()
        val wait = RecordingWait(10_000)
        queue.insert(packet(1))
        assertEquals(1, queue.poll(0, TimeUnit.MILLISECONDS, wait)?.sequence)
        queue.insert(packet(3))
        val waiting = CountDownLatch(1)
        var taken: Int? = null
        var tookMs = 0L
        val consumer = Thread {
            waiting.countDown()
            val start = System.nanoTime()
            taken = queue.poll(5, TimeUnit.SECONDS, wait)?.sequence
            tookMs = (System.nanoTime() - start) / 1_000_000
        }.apply { start() }
        waiting.await()
        Thread.sleep(50)
        queue.insert(packet(2))
        consumer.join(5_000)
        assertFalse(consumer.isAlive)
        assertEquals(2, taken)
        assertTrue("took $tookMs ms", tookMs < 2_000)
    }
}
