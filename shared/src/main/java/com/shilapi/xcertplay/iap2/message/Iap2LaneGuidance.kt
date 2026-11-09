package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList

/**
 * One lane from iAP2 `0x5204 LaneGuidanceInfoUpdate`.
 *
 * <p>Field ids come from the iOS `AccessoryNavigation.framework` and the RGI hook's TLV tables:
 * outer id 2 carries a lane, and inside it 0 = index, 1 = status, 2 = angles, 3 = angle-highlight.
 * Angles are signed degrees: 0 straight, positive right, negative left (measured on the car).
 * [angles] holds every direction the lane allows, in the order sent; Apple Maps may leave the
 * [highlightAngle] out of it while that direction is still allowed.
 */
data class LaneGuidanceLane(
    val index: Int,
    val status: Int,
    val angles: List<Int>,
    val highlightAngle: Int?,
) {
    /** The car marks the lane it recommends with status 2 (measured on the car). */
    val recommended: Boolean get() = status == 2
}

/** One lane event from `0x5204`: its lane guidance index, null when missing, and its lanes. */
data class LaneGuidanceEvent(val index: Int?, val lanes: List<LaneGuidanceLane>)

/**
 * The lane fields of iAP2 `0x5201 RouteGuidanceUpdate`: which `0x5204` event is current and
 * whether it is shown. The iPhone sends lane events ahead of time and only these fields say which
 * one to draw. Null fields were not in the update and keep their previous value.
 */
data class RouteLaneGuidance(
    val state: Int?,
    val laneGuidanceIndex: Int?,
    val laneGuidanceTotalCount: Int?,
    val laneGuidanceShowing: Boolean?,
)

/** Parser for the lane list in iAP2 `0x5204` and the lane fields of `0x5201`. */
object Iap2LaneGuidance {

    /** MaxLaneGuidanceStorageCapacity: how many lane events the accessory declares it keeps. */
    const val STORAGE_CAPACITY = 8

    private const val LANE_GUIDANCE_INDEX = 0x0001
    private const val LANE_INFORMATION = 0x0002
    private const val FIELD_INDEX = 0x0000
    private const val FIELD_STATUS = 0x0001
    private const val FIELD_ANGLES = 0x0002
    private const val FIELD_ANGLE_HIGHLIGHT = 0x0003
    /** LaneAngleHighlight value for a lane without a highlighted direction. */
    private const val NO_ANGLE = 1000

    private const val ROUTE_STATE = 0x0001
    private const val ROUTE_LANE_GUIDANCE_INDEX = 0x0010
    private const val ROUTE_LANE_GUIDANCE_TOTAL = 0x0011
    private const val ROUTE_LANE_GUIDANCE_SHOWING = 0x0012

    /**
     * Every lane carried by one `0x5204` payload. A malformed payload yields an empty list rather
     * than an exception: lane guidance is optional and must never break the session. A lane
     * information field that holds several lanes starts each one with its index.
     */
    fun parse(payload: ByteArray): List<LaneGuidanceLane> = parseEvent(payload).lanes

    /** The lane guidance index and lanes of one `0x5204` payload; a malformed payload has neither. */
    fun parseEvent(payload: ByteArray): LaneGuidanceEvent {
        val outer = runCatching { Iap2ParameterList.parse(payload) }.getOrNull()
            ?: return LaneGuidanceEvent(null, emptyList())
        val lanes = outer.all(LANE_INFORMATION).flatMap { parameter ->
            val fields = runCatching { Iap2ParameterList.parse(parameter.payload) }.getOrNull()
                ?: return@flatMap emptyList()
            lanes(fields.asList())
        }.sortedBy { it.index }
        val index = outer.first(LANE_GUIDANCE_INDEX)?.payload?.let(::unsigned)?.toInt()
        return LaneGuidanceEvent(index, lanes)
    }

    private fun lanes(fields: List<Iap2Parameter>): List<LaneGuidanceLane> {
        val lanes = ArrayList<LaneGuidanceLane>()
        var index: Int? = null
        var status = 0
        val angles = ArrayList<Int>()
        var highlight: Int? = null
        var started = false
        fun finish() {
            if (started) lanes += LaneGuidanceLane(index ?: 0, status, angles.toList(), highlight)
            index = null
            status = 0
            angles.clear()
            highlight = null
            started = false
        }
        for (field in fields) {
            if (field.id == FIELD_INDEX && started) finish()
            when (field.id) {
                FIELD_INDEX -> index = field.u16()
                FIELD_STATUS -> status = field.u8()
                FIELD_ANGLES -> angles += field.s16List()
                FIELD_ANGLE_HIGHLIGHT -> highlight = field.s16().takeUnless { it == NO_ANGLE }
                else -> continue
            }
            started = true
        }
        finish()
        return lanes
    }

    /** The route state and lane fields of a `0x5201` payload; null when it is malformed. */
    fun routeLanes(payload: ByteArray): RouteLaneGuidance? {
        val fields = runCatching { Iap2ParameterList.parse(payload) }.getOrNull() ?: return null
        return RouteLaneGuidance(
            state = fields.first(ROUTE_STATE)?.payload?.takeIf { it.size == 1 }?.let { it[0].toInt() and 0xff },
            laneGuidanceIndex = fields.first(ROUTE_LANE_GUIDANCE_INDEX)?.payload?.let(::unsigned)?.toInt(),
            laneGuidanceTotalCount = fields.first(ROUTE_LANE_GUIDANCE_TOTAL)?.payload?.let(::unsigned)?.toInt(),
            laneGuidanceShowing = fields.first(ROUTE_LANE_GUIDANCE_SHOWING)?.payload
                ?.takeIf { it.size == 1 }?.let { it[0].toInt() != 0 },
        )
    }

    private fun unsigned(bytes: ByteArray): Long? {
        if (bytes.isEmpty() || bytes.size > 4) return null
        return bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    }

    private fun Iap2Parameter.u16(): Int =
        if (payload.size >= 2) ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff) else 0

    private fun Iap2Parameter.u8(): Int = payload.firstOrNull()?.toInt()?.and(0xff) ?: 0

    private fun Iap2Parameter.s16(): Int = u16().let { if (it and 0x8000 != 0) it - 0x10000 else it }

    private fun Iap2Parameter.s16List(): List<Int> {
        val bytes = payload
        return (0 until bytes.size / 2).map { i ->
            (((bytes[2 * i].toInt() and 0xff) shl 8) or (bytes[2 * i + 1].toInt() and 0xff)).toShort().toInt()
        }
    }
}
