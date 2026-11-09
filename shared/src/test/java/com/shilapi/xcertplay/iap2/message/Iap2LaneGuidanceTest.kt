package com.shilapi.xcertplay.iap2.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A real 0x5204 payload captured on the car (Apple Maps, three lanes, the third recommended):
 *
 * <pre>
 * id 0 component 42
 * id 1 lane guidance index 0
 * id 2 lane 0: index 0, status 0, angle -90
 * id 2 lane 1: index 1, status 0, angle 0
 * id 2 lane 2: index 2, status 2, angle 0, angle-highlight 90
 * </pre>
 */
class Iap2LaneGuidanceTest {

    private val sample = hex(
        "00 06 00 00 00 2a" +
            " 00 06 00 01 00 00" +
            " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
            " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
            " 00 1b 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 02 00 00" +
            " 00 06 00 03 00 5a",
    )

    @Test
    fun theCapturedPayloadHasThreeLanes() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(3, lanes.size)
        assertEquals(listOf(0, 1, 2), lanes.map { it.index })
    }

    @Test
    fun anglesAreSignedDegrees() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(listOf(-90), lanes[0].angles)
        assertEquals(listOf(0), lanes[1].angles)
        assertEquals(listOf(0), lanes[2].angles)
    }

    @Test
    fun theThirdLaneIsRecommendedAndHighlighted() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(0, lanes[0].status)
        assertEquals(0, lanes[1].status)
        assertEquals(2, lanes[2].status)
        assertEquals(true, lanes[2].recommended)
        assertEquals(90, lanes[2].highlightAngle)
        assertEquals(null, lanes[0].highlightAngle)
    }

    @Test
    fun theLaneGuidanceIndexIsReadAsSent() {
        assertEquals(0, Iap2LaneGuidance.parseEvent(sample).index)
        assertEquals(0x1234, Iap2LaneGuidance.parseEvent(hex("00 06 00 01 12 34")).index)
        assertEquals(null, Iap2LaneGuidance.parseEvent(hex("00 06 00 00 00 2a")).index)
        assertEquals(LaneGuidanceEvent(null, emptyList()), Iap2LaneGuidance.parseEvent(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun aLaneKeepsEveryAngleItAllows() {
        // Left and straight in one LaneAngles field, then a right turn in a repeated field.
        val lanes = Iap2LaneGuidance.parse(hex(
            "00 06 00 01 00 01" +
                " 00 23 00 02 00 06 00 00 00 00 00 05 00 01 02 00 08 00 02 ff a6 00 00 00 06 00 02 00 5a" +
                " 00 06 00 03 03 e8",
        ))
        assertEquals(1, lanes.size)
        assertEquals(listOf(-90, 0, 90), lanes[0].angles)
        assertEquals("1000 means no highlight", null, lanes[0].highlightAngle)
    }

    @Test
    fun oneLaneInformationFieldMayCarrySeveralLanes() {
        val lanes = Iap2LaneGuidance.parse(hex(
            "00 06 00 01 00 00" +
                " 00 2c 00 02" +
                " 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
                " 00 06 00 00 00 00 00 05 00 01 02 00 06 00 02 00 5a 00 06 00 03 00 00",
        ))
        assertEquals(listOf(0, 1), lanes.map { it.index })
        assertEquals(listOf(90), lanes[0].angles)
        assertEquals(0, lanes[0].highlightAngle)
        assertEquals(true, lanes[0].recommended)
        assertEquals(listOf(0), lanes[1].angles)
    }

    @Test
    fun fieldsBeforeTheFirstIndexAreALaneOfTheirOwn() {
        val lanes = Iap2LaneGuidance.parse(hex(
            "00 06 00 01 00 00" +
                " 00 20 00 02 00 05 00 01 02 00 06 00 02 ff a6" +
                " 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00",
        ))
        assertEquals(2, lanes.size)
        assertEquals(LaneGuidanceLane(0, 2, listOf(-90), null), lanes[0])
        assertEquals(LaneGuidanceLane(1, 0, listOf(0), null), lanes[1])
    }

    @Test
    fun aLaneWithoutAnglesKeepsAnEmptyList() {
        val lanes = Iap2LaneGuidance.parse(hex("00 06 00 01 00 00 00 0f 00 02 00 06 00 00 00 00 00 05 00 01 02"))
        assertEquals(emptyList<Int>(), lanes.single().angles)
    }

    @Test
    fun routeGuidanceNamesTheShownLaneEvent() {
        assertEquals(
            RouteLaneGuidance(state = 1, laneGuidanceIndex = 1, laneGuidanceTotalCount = 3, laneGuidanceShowing = true),
            Iap2LaneGuidance.routeLanes(hex("00 05 00 01 01 00 06 00 10 00 01 00 06 00 11 00 03 00 05 00 12 01")),
        )
        assertEquals(
            RouteLaneGuidance(state = null, laneGuidanceIndex = null, laneGuidanceTotalCount = null, laneGuidanceShowing = false),
            Iap2LaneGuidance.routeLanes(hex("00 05 00 12 00")),
        )
        assertEquals(null, Iap2LaneGuidance.routeLanes(byteArrayOf(0, 9, 0)))
    }

    @Test
    fun aMalformedPayloadYieldsNoLanes() {
        assertEquals(emptyList<LaneGuidanceLane>(), Iap2LaneGuidance.parse(byteArrayOf(0, 1, 2)))
    }

    private fun hex(text: String): ByteArray =
        text.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
