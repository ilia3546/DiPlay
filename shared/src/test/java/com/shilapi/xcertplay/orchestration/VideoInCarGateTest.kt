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
    @Test
    fun forcedVideoSkipsGearReadsAndRemainsAllowedForEveryGear() {
        val changes = mutableListOf<Boolean>()
        val gate = VideoInCarGate(
            readParked = { error("Forced video must not read BYD gear") },
            onChanged = { changes += it },
            onObserved = { error("Forced video must not report an invented gear") },
            forceVideoInCar = true,
        )
        try {
            gate.start()
            assertTrue(VideoInCar.allowed)
            gate.update(false)
            gate.update(null)
            gate.update(true)
            assertEquals(listOf(true), changes)
            assertTrue(VideoInCar.allowed)
        } finally {
            gate.close()
        }
        gate.update(true)
        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun normalGateRestoresGearChecksAfterAForcedConnection() {
        val forced = VideoInCarGate({ null }, {}, forceVideoInCar = true)
        forced.start()
        forced.close()
        val normal = VideoInCarGate({ null }, {})
        normal.update(false)
        assertFalse(VideoInCar.allowed)
        normal.update(true)
        assertTrue(VideoInCar.allowed)
        normal.close()
    }

    @After
    fun reset() {
        VideoInCar.allowed = false
        VideoInCar.screens = VideoInCar.VideoScreens()
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

    @Test
    fun aScreenAwayFromTheDriverAllowsVideoInAnyGearAndThePlayerLearnsOfEveryReading() {
        var passenger: Int? = 2
        val changes = mutableListOf<Boolean>()
        val screens = mutableListOf<VideoInCar.VideoScreens>()
        var ticks = 0
        val gate = VideoInCarGate({ null }, onChanged = { changes += it }, awayFromDriver = { passenger },
            onScreens = { screens += it }, onTick = { ticks++ })

        gate.update(true)
        assertEquals(VideoInCar.VideoScreens(driverScreen = true, passengerDisplayId = 2), VideoInCar.screens)
        gate.update(false)
        assertEquals(VideoInCar.VideoScreens(driverScreen = false, passengerDisplayId = 2), VideoInCar.screens)
        assertTrue(VideoInCar.allowed)
        gate.update(false)
        passenger = null
        gate.update(false)
        assertFalse(VideoInCar.allowed)

        assertEquals(listOf(true, false), changes)
        assertEquals(listOf(
            VideoInCar.VideoScreens(true, 2),
            VideoInCar.VideoScreens(false, 2),
            VideoInCar.VideoScreens(false, null),
        ), screens)
        assertEquals(4, ticks)
        gate.close()
    }

    @Test
    fun aNewGateComparesWithThePublishedScreensAndClosingClearsThem() {
        val first = VideoInCarGate({ null }, {}, awayFromDriver = { 2 })
        first.update(true)
        first.close()
        assertEquals(VideoInCar.VideoScreens(), VideoInCar.screens)
        assertFalse(VideoInCar.allowed)

        val published = mutableListOf<VideoInCar.VideoScreens>()
        val changes = mutableListOf<Boolean>()
        val next = VideoInCarGate({ null }, onChanged = { changes += it }, awayFromDriver = { 2 }, onScreens = { published += it })
        next.update(false)
        assertEquals(listOf(VideoInCar.VideoScreens(false, 2)), published)
        assertEquals(listOf(true), changes)
        next.close()
        next.update(true)
        assertEquals(VideoInCar.VideoScreens(), VideoInCar.screens)
        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun forcedVideoReadsNeitherTheGearNorTheScreenAndOnlyTheDriverScreenIsAllowed() {
        var ticks = 0
        val gate = VideoInCarGate(
            readParked = { error("Forced video must not read the gear") },
            onChanged = {},
            forceVideoInCar = true,
            awayFromDriver = { error("Forced video must not read the passenger screen") },
            onTick = { ticks++ },
        )
        gate.start()
        gate.update(false)
        assertEquals(VideoInCar.VideoScreens(driverScreen = true, passengerDisplayId = null), VideoInCar.screens)
        assertEquals(2, ticks)
        gate.close()
    }

    @Test
    fun withoutAScreenAwayFromTheDriverOnlyParkAllowsVideo() {
        val changes = mutableListOf<Boolean>()
        val published = mutableListOf<VideoInCar.VideoScreens>()
        val gate = VideoInCarGate({ null }, onChanged = { changes += it }, onScreens = { published += it })
        gate.update(null)
        gate.update(false)
        gate.update(true)
        gate.update(false)
        assertEquals(listOf(true, false), changes)
        assertEquals(listOf(VideoInCar.VideoScreens(true, null), VideoInCar.VideoScreens(false, null)), published)
        gate.close()
    }

    @Test
    fun aReadingThatOutlastsCloseAndTheNextGateIsNotPublished() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var oldTicks = 0
        var oldChanges = 0
        val old = VideoInCarGate({ true }, onChanged = { oldChanges++ }, onTick = { oldTicks++ }, awayFromDriver = {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            2
        })
        val reading = Thread { old.update(true) }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        old.close()
        val next = VideoInCarGate({ false }, {}, awayFromDriver = { 2 })
        next.update(false)
        release.countDown()
        reading.join(2_000)

        assertEquals(VideoInCar.VideoScreens(driverScreen = false, passengerDisplayId = 2), VideoInCar.screens)
        assertTrue(VideoInCar.allowed)
        assertEquals(0, oldTicks)
        assertEquals(0, oldChanges)
        next.close()
    }

    @Test
    fun closingAReplacedGateLeavesTheNewGatesScreens() {
        val old = VideoInCarGate({ true }, {})
        val next = VideoInCarGate({ true }, {}, awayFromDriver = { 2 })
        next.update(false)
        old.close()
        old.update(true)
        assertEquals(VideoInCar.VideoScreens(driverScreen = false, passengerDisplayId = 2), VideoInCar.screens)
        assertTrue(VideoInCar.allowed)
        next.close()
    }
}
