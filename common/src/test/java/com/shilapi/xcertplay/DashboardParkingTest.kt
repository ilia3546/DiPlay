package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardParkingTest {
    private val parking = DashboardParking()
    private var now = 100_000L

    private fun at(kmh: Double?, gear: VehicleGear? = VehicleGear.DRIVE, ageMillis: Long = 0): Boolean =
        parking.next(EcarxWheelSpeed.Motion(now - ageMillis, kmh?.div(3.6), gear), now)

    private fun after(millis: Long, kmh: Double?, gear: VehicleGear? = VehicleGear.DRIVE): Boolean {
        now += millis
        return at(kmh, gear)
    }

    @Test fun itStartsParkedAndDrivesFromThreeKilometresAnHour() {
        assertTrue(parking.parked)
        assertTrue(at(2.9))
        assertFalse(after(250, 3.0))
    }

    @Test fun parkAndNeutralParkAtOnceAtAnySpeed() {
        assertFalse(at(40.0))
        assertTrue(after(250, 40.0, VehicleGear.PARK))
        assertFalse(after(250, 40.0))
        assertTrue(after(250, 5.0, VehicleGear.NEUTRAL))
    }

    @Test fun reverseAndAnUnknownGearFollowTheSpeed() {
        assertFalse(at(5.0, VehicleGear.REVERSE))
        assertFalse(after(250, 5.0, gear = null))
    }

    @Test fun standingParksOnlyAfterTwoSeconds() {
        assertFalse(at(30.0))
        assertFalse(after(250, 0.5))
        assertFalse(after(DashboardParking.STANDING_HOLD_MILLIS - 1, 0.0))
        assertTrue(after(1, 0.0))
    }

    @Test fun aSpeedBetweenTheThresholdsKeepsTheLastAnswer() {
        assertFalse(at(30.0))
        assertFalse(after(250, 2.0))
        assertFalse(after(5_000, 1.0))
        assertFalse(after(250, 0.9))
        assertFalse("crawling restarts the two seconds", after(1_900, 1.5))
        assertFalse(after(250, 0.5))
        assertTrue(after(DashboardParking.STANDING_HOLD_MILLIS, 0.5))
        assertTrue(after(250, 2.9))
        assertTrue(after(5_000, 1.0))
        assertFalse(after(250, 3.5))
    }

    @Test fun noSpeedOrAnOldOneParks() {
        assertFalse(at(30.0))
        assertTrue("no reading", parking.next(null, now))
        assertFalse(at(30.0))
        assertTrue("unread speed", at(null))
        assertFalse(at(30.0))
        assertTrue("stale", at(30.0, ageMillis = DashboardParking.FRESH_MILLIS + 1))
        assertFalse(at(30.0, ageMillis = DashboardParking.FRESH_MILLIS))
    }
}
