package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.message.Iap2LaneGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudFrameTest {
    private val route = CarPlayGlance.Snapshot(
        connected = true, maneuverType = 2, maneuverIndex = 4, distanceMeters = 350, road = "Садовое кольцо",
    )

    @Test fun noRouteIsNoLine() {
        assertNull(HudFrame.from(CarPlayGlance.Snapshot()))
        assertNull(HudFrame.from(CarPlayGlance.Snapshot(connected = true, distanceMeters = 350, road = "Садовое кольцо")))
    }

    @Test fun aRightTurnIn350MetresOntoTheStreet() {
        val frame = HudFrame.from(route)!!
        assertEquals(HudManeuver.RIGHT, frame.kind)
        assertEquals("350", frame.number)
        assertEquals("м", frame.unit)
        assertEquals("Садовое кольцо", frame.instructionLabel)
        assertEquals(0, frame.exitNumber)
        assertEquals("", frame.tripSummary)
    }

    @Test fun everyManeuverTypeHasItsSign() {
        val expected = mapOf(
            0 to HudManeuver.NO_TURN,
            1 to HudManeuver.LEFT, 20 to HudManeuver.LEFT,
            2 to HudManeuver.RIGHT, 21 to HudManeuver.RIGHT,
            3 to HudManeuver.STRAIGHT, 5 to HudManeuver.STRAIGHT, 11 to HudManeuver.STRAIGHT,
            4 to HudManeuver.UTURN, 18 to HudManeuver.UTURN, 19 to HudManeuver.UTURN, 26 to HudManeuver.UTURN,
            6 to HudManeuver.ROUNDABOUT_ENTER, 7 to HudManeuver.ROUNDABOUT_EXIT,
            13 to HudManeuver.KEEP_LEFT, 52 to HudManeuver.KEEP_LEFT,
            14 to HudManeuver.KEEP_RIGHT, 53 to HudManeuver.KEEP_RIGHT,
            22 to HudManeuver.EXIT_LEFT, 23 to HudManeuver.EXIT_RIGHT,
            47 to HudManeuver.SHARP_LEFT, 48 to HudManeuver.SHARP_RIGHT,
            49 to HudManeuver.SLIGHT_LEFT, 50 to HudManeuver.SLIGHT_RIGHT,
            15 to HudManeuver.FERRY_ENTER, 16 to HudManeuver.FERRY_EXIT, 17 to HudManeuver.FERRY_ENTER,
            10 to HudManeuver.ARRIVE, 12 to HudManeuver.ARRIVE, 24 to HudManeuver.ARRIVE, 25 to HudManeuver.ARRIVE,
            27 to HudManeuver.ARRIVE,
            8 to HudManeuver.UNKNOWN, 9 to HudManeuver.UNKNOWN, 51 to HudManeuver.UNKNOWN,
            54 to HudManeuver.UNKNOWN, 255 to HudManeuver.UNKNOWN, -1 to HudManeuver.UNKNOWN,
        ) + (28..46).associateWith { HudManeuver.ROUNDABOUT_EXIT }
        for ((type, sign) in expected) assertEquals("type $type", sign, HudFrame.sign(type))
    }

    @Test fun aRoundaboutExitCarriesItsNumberAndOnlyThere() {
        assertEquals(3, HudFrame.from(route.copy(maneuverType = 30))!!.exitNumber)
        assertEquals(1, HudFrame.from(route.copy(maneuverType = 28))!!.exitNumber)
        assertEquals(19, HudFrame.from(route.copy(maneuverType = 46))!!.exitNumber)
        assertEquals(0, HudFrame.from(route.copy(maneuverType = 7))!!.exitNumber)
        assertEquals(0, HudFrame.from(route.copy(maneuverType = 6))!!.exitNumber)
        assertEquals(0, HudFrame.from(route.copy(maneuverType = 47))!!.exitNumber)
    }

    @Test fun theSignDoesNotDependOnTheDrivingSide() {
        for (type in listOf(4, 6, 7, 8, 30)) {
            assertEquals(HudFrame.sign(type), HudFrame.from(route.copy(maneuverType = type, drivingSide = 1))!!.kind)
        }
    }

    @Test fun distancesUseACommaAndANarrowSpaceFromFiveDigits() {
        assertEquals("0" to "м", HudFrame.distance(0))
        assertEquals("999" to "м", HudFrame.distance(999))
        assertEquals("1" to "км", HudFrame.distance(1000))
        assertEquals("1,5" to "км", HudFrame.distance(1500))
        assertEquals("9,9" to "км", HudFrame.distance(9900))
        assertEquals("10" to "км", HudFrame.distance(9999))
        assertEquals("12" to "км", HudFrame.distance(12_345))
        assertEquals("9999" to "км", HudFrame.distance(9_999_000))
        assertEquals("10\u202f000" to "км", HudFrame.distance(10_000_000))
        assertEquals("100\u202f000" to "км", HudFrame.distance(100_000_000))
    }

    @Test fun theTripShowsMinutesAndDistanceEachWhenKnown() {
        val both = HudFrame.from(route.copy(remainingSeconds = 720, remainingMeters = 4200))!!
        assertEquals("12 мин · 4,2 км", both.tripSummary)
        assertEquals("<1 мин", HudFrame.from(route.copy(remainingSeconds = 59))!!.tripSummary)
        assertEquals("1 мин", HudFrame.from(route.copy(remainingSeconds = 60))!!.tripSummary)
        assertEquals("850 м", HudFrame.from(route.copy(remainingMeters = 850))!!.tripSummary)
    }

    @Test fun anglesSnapToTheNearestOfNineArrowsOnBothSides() {
        val expected = mapOf(
            0 to HudLaneDirection.STRAIGHT, 22 to HudLaneDirection.STRAIGHT, -22 to HudLaneDirection.STRAIGHT,
            23 to HudLaneDirection.SLIGHT_RIGHT, 45 to HudLaneDirection.SLIGHT_RIGHT, 67 to HudLaneDirection.SLIGHT_RIGHT,
            -23 to HudLaneDirection.SLIGHT_LEFT, -45 to HudLaneDirection.SLIGHT_LEFT, -67 to HudLaneDirection.SLIGHT_LEFT,
            68 to HudLaneDirection.RIGHT, 90 to HudLaneDirection.RIGHT, 112 to HudLaneDirection.RIGHT,
            -68 to HudLaneDirection.LEFT, -90 to HudLaneDirection.LEFT, -112 to HudLaneDirection.LEFT,
            113 to HudLaneDirection.SHARP_RIGHT, 135 to HudLaneDirection.SHARP_RIGHT, 157 to HudLaneDirection.SHARP_RIGHT,
            -113 to HudLaneDirection.SHARP_LEFT, -135 to HudLaneDirection.SHARP_LEFT, -157 to HudLaneDirection.SHARP_LEFT,
            158 to HudLaneDirection.UTURN_RIGHT, 180 to HudLaneDirection.UTURN_RIGHT,
            -158 to HudLaneDirection.UTURN_LEFT, -180 to HudLaneDirection.UTURN_LEFT,
        )
        for ((angle, direction) in expected) assertEquals("angle $angle", direction, HudFrame.direction(angle))
        for (angle in listOf(181, -181, 360, Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())) {
            assertNull("angle $angle", HudFrame.direction(angle))
        }
    }

    @Test fun aLaneJoinsItsAngleAndHighlightAndHighlightsOnlyWhenRecommended() {
        assertEquals(HudLane(listOf(HudLaneDirection.STRAIGHT, HudLaneDirection.RIGHT), HudLaneDirection.RIGHT, true),
            HudFrame.lane(LaneGuidanceLane(0, 2, 0, 90)))
        assertEquals(HudLane(listOf(HudLaneDirection.STRAIGHT, HudLaneDirection.RIGHT), null, false),
            HudFrame.lane(LaneGuidanceLane(0, 0, 0, 90)))
        assertEquals(HudLane(listOf(HudLaneDirection.LEFT), HudLaneDirection.LEFT, true),
            HudFrame.lane(LaneGuidanceLane(0, 2, -90, null)))
        assertEquals("no duplicate", HudLane(listOf(HudLaneDirection.LEFT), HudLaneDirection.LEFT, true),
            HudFrame.lane(LaneGuidanceLane(0, 2, -90, -95)))
        assertEquals("unknown angle has no arrow", HudLane(emptyList(), null, false),
            HudFrame.lane(LaneGuidanceLane(0, 0, 300, null)))
        assertEquals(HudLane(listOf(HudLaneDirection.STRAIGHT), HudLaneDirection.STRAIGHT, true),
            HudFrame.lane(LaneGuidanceLane(0, 2, 0, 400)))
    }

    @Test fun theLanesCapturedOnTheCarDrawTheThirdAsRecommendedToTheRight() {
        // Iap2LaneGuidanceTest's payload: Apple Maps, three lanes, the third recommended.
        val sample = ("00 06 00 00 00 2a" +
            " 00 06 00 01 00 00" +
            " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
            " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
            " 00 1b 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 02 00 00" +
            " 00 06 00 03 00 5a").split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val frame = HudFrame.from(route.copy(lanes = CarPlayGlance.LaneStrip(4, Iap2LaneGuidance.parse(sample))))!!
        assertEquals(listOf(
            HudLane(listOf(HudLaneDirection.LEFT), null, false),
            HudLane(listOf(HudLaneDirection.STRAIGHT), null, false),
            HudLane(listOf(HudLaneDirection.STRAIGHT, HudLaneDirection.RIGHT), HudLaneDirection.RIGHT, true),
        ), frame.lanes)
    }

    @Test fun lanesOfAnotherManeuverAreNotShown() {
        val lanes = listOf(LaneGuidanceLane(0, 2, 0, null))
        assertEquals(1, HudFrame.from(route.copy(lanes = CarPlayGlance.LaneStrip(4, lanes)))!!.lanes.size)
        assertEquals(emptyList<HudLane>(), HudFrame.from(route.copy(lanes = CarPlayGlance.LaneStrip(3, lanes)))!!.lanes)
        assertEquals(emptyList<HudLane>(),
            HudFrame.from(route.copy(maneuverIndex = null, lanes = CarPlayGlance.LaneStrip(4, lanes)))!!.lanes)
    }
}
