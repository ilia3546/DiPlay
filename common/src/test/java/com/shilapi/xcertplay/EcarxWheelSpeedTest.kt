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
        assertNull(EcarxSignalReadings.leverGear(4))
        assertNull(EcarxSignalReadings.leverGear(-1))
    }

    @Test fun aCarSignalSpeedIsReadForOneAndAHalfSeconds() {
        val readings = EcarxSignalReadings()
        assertNull(readings.metersPerSecond(1_000))
        readings.speed(1.82f, 1_000)
        assertEquals(1.82, readings.metersPerSecond(1_000)!!, 1e-6)
        assertEquals(1.82, readings.metersPerSecond(2_500)!!, 1e-6)
        assertNull(readings.metersPerSecond(2_501))
        readings.speed(500f, 3_000)
        assertNull(readings.metersPerSecond(3_000))
    }

    @Test fun theStatusSaysHowOldTheSpeedIsAndCountsEventsSinceTheLastStatus() {
        val readings = EcarxSignalReadings()
        assertEquals("speedAgeMs=none gear=none events=0", readings.describe(1_000))
        readings.gear(1, EcarxSignalReadings.leverGear(1))
        readings.speed(0.5f, 1_000)
        readings.speed(0.74f, 1_200)
        assertEquals("speedAgeMs=300 speed=0.74 gear=R events=3", readings.describe(1_500))
        assertEquals("speedAgeMs=800 speed=0.74 gear=R events=0", readings.describe(2_000))
        readings.gear(7, EcarxSignalReadings.leverGear(7))
        assertEquals("speedAgeMs=800 speed=0.74 gear=raw 7 events=1", readings.describe(2_000))
    }

    @Test fun adaptApiComesFirstAndCarSignalWhereAdaptApiIsMissing() {
        assertEquals(Source.ADAPT_API, EcarxWheelSpeed.firstSource(adaptApi = true, carSignal = true))
        assertEquals(Source.ADAPT_API, EcarxWheelSpeed.firstSource(adaptApi = true, carSignal = false))
        assertEquals(Source.CAR_SIGNAL, EcarxWheelSpeed.firstSource(adaptApi = false, carSignal = true))
        assertEquals(Source.NONE, EcarxWheelSpeed.firstSource(adaptApi = false, carSignal = false))
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
        readings.gear(1, EcarxSignalReadings.leverGear(1))
        readings.speed(0.74f, 10_000)

        val (gearChanged, sample) = EcarxWheelSpeed.readSignal(10_250)
        assertTrue(gearChanged)
        EcarxWheelSpeed.keep(sample!!)
        assertEquals(EcarxWheelSpeed.Motion(10_250, 0.74f.toDouble(), VehicleGear.REVERSE), EcarxWheelSpeed.motion())
        assertEquals(VehicleSpeedReading(VehicleGear.REVERSE, listOf(VehicleSpeedSample(10_250, 0.74f.toDouble()))),
            EcarxWheelSpeed.drain())

        val (_, stale) = EcarxWheelSpeed.readSignal(12_000)
        assertNull(stale)
        assertNull(EcarxWheelSpeed.motion()!!.metersPerSecond)
        assertNull(EcarxWheelSpeed.drain())
        readings.clear()
        EcarxWheelSpeed.readSignal(12_000)
    }
}
