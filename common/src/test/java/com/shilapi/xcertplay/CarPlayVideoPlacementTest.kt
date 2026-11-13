package com.shilapi.xcertplay

import com.shilapi.xcertplay.CarPlayVideoPlacement.Move
import com.shilapi.xcertplay.CarPlayVideoPlacement.Verdict
import com.shilapi.xcertplay.airplay.VideoInCar.VideoScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayVideoPlacementTest {
    private val driver = 0
    private val passenger = 2
    private val parked = VideoScreens(driverScreen = true, passengerDisplayId = passenger)
    private val driving = VideoScreens(driverScreen = false, passengerDisplayId = passenger)

    private fun verdict(actual: Int, screens: VideoScreens, identity: Int? = passenger, available: Boolean = true,
        landedOffTarget: Boolean = false) = CarPlayVideoPlacement.verdict(actual, driver, screens, identity, available, landedOffTarget)

    @Test fun theDriversDisplayNeverPlaysOutsideP() {
        val outsideP = listOf(driving, VideoScreens(false, null), VideoScreens(false, 7))
        for (screens in outsideP) for (identity in listOf(passenger, null)) for (available in listOf(true, false)) {
            for (landed in listOf(true, false)) {
                assertNotEquals("$screens $identity $available $landed", Verdict.PLAY,
                    verdict(driver, screens, identity, available, landed))
            }
        }
    }

    @Test fun outsidePThePlayerOnTheDriversDisplayIsBlockedWhileThePassengerScreenMayTakeIt() {
        assertEquals(Verdict.BLOCK, verdict(driver, driving))
        assertEquals(Verdict.CLOSE, verdict(driver, VideoScreens(false, null)))
        assertEquals(Verdict.CLOSE, verdict(driver, driving, landedOffTarget = true))
        assertEquals("passenger_launch_refused",
            CarPlayVideoPlacement.closeReason(driver, driver, passenger, landedOffTarget = true))
    }

    @Test fun inPBothScreensPlay() {
        assertEquals(Verdict.PLAY, verdict(driver, parked))
        assertEquals(Verdict.PLAY, verdict(passenger, parked))
        assertEquals(Verdict.PLAY, verdict(driver, VideoScreens(true, null), identity = null))
    }

    @Test fun thePassengerScreenPlaysInAnyGearWhileItIsOn() {
        assertEquals(Verdict.PLAY, verdict(passenger, driving))
        assertEquals(Verdict.PLAY, verdict(passenger, VideoScreens(false, null)))
    }

    @Test fun aPassengerScreenTurnedOffClosesThePlayerEvenInP() {
        assertEquals(Verdict.CLOSE, verdict(passenger, parked, available = false))
        assertEquals("passenger_screen_gone", CarPlayVideoPlacement.closeReason(passenger, driver, passenger, false))
    }

    @Test fun anyOtherDisplayClosesThePlayer() {
        for (screens in listOf(parked, driving, VideoScreens(true, null))) {
            assertEquals(Verdict.CLOSE, verdict(1, screens))
            assertEquals(Verdict.CLOSE, verdict(11, screens, identity = null))
        }
        assertEquals("other_display", CarPlayVideoPlacement.closeReason(1, driver, passenger, false))
    }

    @Test fun aNewPlayerOpensOnTheDriversDisplayInPAndOnThePassengerScreenOutsideIt() {
        assertEquals(driver, CarPlayVideoPlacement.newPlayerTarget(driver, parked))
        assertEquals(driver, CarPlayVideoPlacement.newPlayerTarget(driver, VideoScreens(true, null)))
        assertEquals(passenger, CarPlayVideoPlacement.newPlayerTarget(driver, driving))
        assertNull(CarPlayVideoPlacement.newPlayerTarget(driver, VideoScreens()))
    }

    @Test fun theMoveButtonOffersOnlyAScreenThatMayPlay() {
        assertEquals(Move.TO_PASSENGER, CarPlayVideoPlacement.move(driver, driver, parked, passenger))
        assertEquals(Move.TO_PASSENGER, CarPlayVideoPlacement.move(driver, driver, driving, passenger))
        assertEquals(Move.TO_DRIVER, CarPlayVideoPlacement.move(passenger, driver, parked, passenger))
        assertNull(CarPlayVideoPlacement.move(passenger, driver, driving, passenger))
        assertNull(CarPlayVideoPlacement.move(driver, driver, VideoScreens(true, null), null))
        assertNull(CarPlayVideoPlacement.move(1, driver, parked, passenger))
    }
}
