package com.shilapi.xcertplay

import com.shilapi.xcertplay.EcarxWheelSpeed.Source
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxWheelSpeedTest {
    @Test fun ecarxGearEventsMapToTheSelectorPositions() {
        assertEquals(VehicleGear.PARK, EcarxWheelSpeed.gear(2097712))
        assertEquals(VehicleGear.REVERSE, EcarxWheelSpeed.gear(2097728))
        assertEquals(VehicleGear.NEUTRAL, EcarxWheelSpeed.gear(2097680))
        assertEquals(VehicleGear.DRIVE, EcarxWheelSpeed.gear(2097696))
        assertEquals(VehicleGear.DRIVE, EcarxWheelSpeed.gear(2097665)) // GEAR_FIRST, manual mode
        assertEquals(VehicleGear.DRIVE, EcarxWheelSpeed.gear(2097674)) // GEAR_TENTH
        assertNull(EcarxWheelSpeed.gear(-1)) // GEAR_UNKNOWN
        assertNull(EcarxWheelSpeed.gear(2097664))
    }

    @Test fun onlyARecentPollTellsWhetherTheCarIsInPark() {
        fun motion(gear: VehicleGear?) = EcarxWheelSpeed.Motion(elapsedMillis = 10_000, metersPerSecond = 0.0, gear = gear)

        assertEquals(true, EcarxWheelSpeed.parked(motion(VehicleGear.PARK), nowMillis = 11_000))
        assertEquals(false, EcarxWheelSpeed.parked(motion(VehicleGear.DRIVE), nowMillis = 11_000))
        assertEquals(false, EcarxWheelSpeed.parked(motion(VehicleGear.NEUTRAL), nowMillis = 11_000))
        assertNull(EcarxWheelSpeed.parked(motion(null), nowMillis = 11_000))
        assertNull(EcarxWheelSpeed.parked(motion(VehicleGear.PARK), nowMillis = 12_500)) // the polling stalled
        assertNull(EcarxWheelSpeed.parked(null, nowMillis = 11_000))
    }

    @Test fun theRawSpeedIsTakenAsMetersPerSecond() {
        assertEquals(0.0, EcarxWheelSpeed.metersPerSecond(0f)!!, 0.0)
        assertEquals(16.5, EcarxWheelSpeed.metersPerSecond(16.5f)!!, 1e-6)
        assertNull(EcarxWheelSpeed.metersPerSecond(-1f))
        assertNull(EcarxWheelSpeed.metersPerSecond(Float.NaN))
        assertNull(EcarxWheelSpeed.metersPerSecond(500f))
    }

    @Test fun carSignalLeverCodesMapToTheSelectorPositions() {
        assertEquals(VehicleGear.PARK, EcarxSignalReadings.leverGear(0))
        assertEquals(VehicleGear.REVERSE, EcarxSignalReadings.leverGear(1))
        assertEquals(VehicleGear.NEUTRAL, EcarxSignalReadings.leverGear(2))
        assertEquals(VehicleGear.DRIVE, EcarxSignalReadings.leverGear(3))
        assertEquals(VehicleGear.DRIVE, EcarxSignalReadings.leverGear(4)) // manual mode
        for (code in listOf(5, 6, 7, -1)) assertNull(EcarxSignalReadings.leverGear(code))
    }

    @Test fun aCarSignalSpeedIsReadForTwoAndAHalfSeconds() {
        val readings = EcarxSignalReadings()
        assertNull(readings.metersPerSecond(1_000))
        readings.speed(1.82f, 1_000)
        assertEquals(1.82, readings.metersPerSecond(1_000)!!, 1e-6)
        assertEquals(1.82, readings.metersPerSecond(3_500)!!, 1e-6)
        assertNull(readings.metersPerSecond(3_501))
        readings.speed(500f, 4_000)
        assertNull(readings.metersPerSecond(4_000))
    }

    @Test fun theLongitudinalSpeedIsComparedOnlyWithAFreshSpeedAboveTwentyKmh() {
        val readings = EcarxSignalReadings()
        readings.longitudinal(10f, 500)
        assertNull(readings.comparison())
        readings.speed(15 / 3.6f, 1_000)
        readings.longitudinal(4f, 1_100)
        assertNull(readings.comparison())
        readings.speed(60 / 3.6f, 2_000)
        readings.longitudinal(50 / 3.6f, 2_100)
        readings.longitudinal(52 / 3.6f, 2_200)
        readings.longitudinal(50 / 3.6f, 4_501)
        assertEquals("speed=60.0 VehSpdLgtA=51.0 km/h ratio=1.176 n=2", readings.comparison())
        assertNull(readings.comparison())
    }

    @Test fun theStatusSaysHowOldTheSpeedTheLastEventAndTheLastErrorAre() {
        val readings = EcarxSignalReadings()
        assertEquals("speedAgeMs=none gear=none lastEventAgoMs=none", readings.describe(1_000))
        readings.speed(0.74f, 1_200)
        readings.gear(1, EcarxSignalReadings.leverGear(1), 1_400)
        assertEquals("speedAgeMs=300 speed=0.74 gear=R lastEventAgoMs=100", readings.describe(1_500))
        readings.gear(7, EcarxSignalReadings.leverGear(7), 2_000)
        readings.error("onErrorEvent [291504647, 0]", 2_000)
        assertEquals("speedAgeMs=61800 speed=0.74 gear=raw 7 lastEventAgoMs=61000 lastError=onErrorEvent [291504647, 0] 61s ago",
            readings.describe(63_000))
    }

    @Test fun aLostCarSignalConnectionOpensANewCarAfterAPause() {
        val link = EcarxSignalLink()
        assertEquals("connecting", link.describe())
        assertTrue(link.shouldOpen(0))
        val first = link.opening()
        assertFalse(link.shouldOpen(0))
        assertFalse(link.shouldSubscribe())
        link.connected(first)
        assertEquals("connected", link.describe())
        assertTrue(link.shouldSubscribe())
        link.subscribed()
        assertEquals("subscribed", link.describe())
        assertFalse(link.shouldSubscribe())

        link.lost(first, "disconnected", 10_000)
        assertEquals("disconnected, reconnecting", link.describe())
        assertFalse(link.shouldOpen(14_999))
        assertTrue(link.shouldOpen(15_000))
        val second = link.opening()
        assertEquals("reconnecting", link.describe())

        link.lost(first, "disconnected", 15_100) // the old car, late
        link.connected(first)
        assertEquals("reconnecting", link.describe())
        link.connected(second)
        assertTrue(link.shouldSubscribe())
    }

    @Test fun aFailedSubscriptionWaitsForANewCar() {
        val link = EcarxSignalLink()
        val first = link.opening()
        link.connected(first)
        link.lost(first, "error CarNotConnectedException", 1_000)
        assertFalse(link.shouldSubscribe())
        assertEquals("error CarNotConnectedException, reconnecting", link.describe())
        assertFalse(link.shouldOpen(5_999))
        assertTrue(link.shouldOpen(6_000))
    }

    @Test fun aSignatureRefusalSwitchesToCarSignalAndOtherFailuresRetryAdaptApi() {
        val refused = InvocationTargetException(SecurityException("Permission denied"))
        assertTrue(EcarxWheelSpeed.deniedBySignature(refused))
        assertEquals(Source.CAR_SIGNAL, EcarxWheelSpeed.sourceAfter(refused, carSignal = true))
        assertEquals(Source.NONE, EcarxWheelSpeed.sourceAfter(refused, carSignal = false))
        assertEquals(Source.CAR_SIGNAL, EcarxWheelSpeed.sourceAfter(SecurityException("Permission denied"), carSignal = true))

        for (transient in listOf(IllegalStateException("speed sensor notavailable"), NullPointerException(),
                InvocationTargetException(IllegalStateException("not connected")))) {
            assertFalse(EcarxWheelSpeed.deniedBySignature(transient))
            assertEquals(Source.ADAPT_API, EcarxWheelSpeed.sourceAfter(transient, carSignal = true))
        }
    }

    @Test fun carSignalReverseIsDrainedWithAPositiveSpeed() {
        val readings = EcarxWheelSpeed.readings
        readings.clear()
        EcarxWheelSpeed.drain()
        readings.gear(1, EcarxSignalReadings.leverGear(1), 10_000)
        readings.speed(0.74f, 10_000)

        val (gearChanged, sample) = EcarxWheelSpeed.readSignal(10_250)
        assertTrue(gearChanged)
        EcarxWheelSpeed.keep(sample!!)
        assertEquals(EcarxWheelSpeed.Motion(10_250, 0.74f.toDouble(), VehicleGear.REVERSE), EcarxWheelSpeed.motion())
        assertEquals(VehicleSpeedReading(VehicleGear.REVERSE, listOf(VehicleSpeedSample(10_250, 0.74f.toDouble()))),
            EcarxWheelSpeed.drain())

        val (_, stale) = EcarxWheelSpeed.readSignal(13_000)
        assertNull(stale)
        assertNull(EcarxWheelSpeed.motion()!!.metersPerSecond)
        assertNull(EcarxWheelSpeed.drain())
        readings.clear()
        EcarxWheelSpeed.readSignal(13_000)
    }
}
