package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoInCarGateTest {
    @After
    fun reset() {
        VideoInCar.allowed = false
    }

    @Test
    fun videoIsAllowedOnlyWhileTheGearReadsPark() {
        val changes = mutableListOf<Boolean>()
        val gate = VideoInCarGate({ null }, onChanged = { changes += it })

        gate.update(null) // no ADB: stays off
        gate.update(false)
        gate.update(true)
        gate.update(true)
        gate.update(null) // unreadable while parked: off again
        gate.update(false)

        assertEquals(listOf(true, false), changes)
        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun closingTheGateTurnsVideoOff() {
        val gate = VideoInCarGate({ true }, onChanged = {})
        gate.update(true)
        gate.close()
        gate.update(true)

        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun observationsDistinguishUnknownParkAndNotPark() {
        val observations = mutableListOf<Boolean?>()
        val gate = VideoInCarGate({ null }, {}, observations::add)

        gate.update(null)
        gate.update(null)
        gate.update(false)
        gate.update(true)

        assertEquals(listOf(null, false, true), observations)
    }

    @Test
    fun theGateStartsItsReadingAndStopsItWhenClosed() {
        val readingThreads = mutableListOf<Thread>()
        val read = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val gate = VideoInCarGate(
            readParked = { read.countDown(); false },
            onChanged = {},
            startReading = {
                readingThreads += Thread.currentThread()
                ({ readingThreads += Thread.currentThread(); stopped.countDown() })
            },
        )

        gate.start()
        assertTrue(read.await(2, TimeUnit.SECONDS))
        gate.close()

        assertTrue(stopped.await(3, TimeUnit.SECONDS))
        assertEquals(2, readingThreads.size)
        assertTrue("started and stopped on the gate's own thread", readingThreads[0] === readingThreads[1])
    }
}
