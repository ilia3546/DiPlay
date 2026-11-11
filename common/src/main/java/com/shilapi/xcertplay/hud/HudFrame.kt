package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import java.util.Locale

/** The sign [ManeuverIcon] draws for a maneuver. */
enum class HudManeuver {
    UNKNOWN, NO_TURN, STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    UTURN, KEEP_LEFT, KEEP_RIGHT, EXIT_LEFT, EXIT_RIGHT, ROUNDABOUT_ENTER, ROUNDABOUT_EXIT, FERRY_ENTER, FERRY_EXIT, ARRIVE,
}

enum class HudLaneDirection { UTURN_LEFT, SHARP_LEFT, LEFT, SLIGHT_LEFT, STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN_RIGHT }

/** One lane of the strip; a lane with no [directions] is drawn without an arrow. */
data class HudLane(
    @JvmField val directions: List<HudLaneDirection>,
    @JvmField val highlighted: HudLaneDirection?,
    @JvmField val preferred: Boolean,
)

/** One line on the head-up display, as [HudDrawing] draws it. */
data class HudFrame(
    @JvmField val kind: HudManeuver,
    /** The roundabout exit, 1 to 19, or 0 for none. */
    @JvmField val exitNumber: Int,
    @JvmField val number: String,
    @JvmField val unit: String,
    @JvmField val instructionLabel: String,
    @JvmField val lanes: List<HudLane>,
    @JvmField val tripMinutes: String,
    @JvmField val minutesUnit: String,
    @JvmField val tripNumber: String,
    @JvmField val tripUnit: String,
) {
    @JvmField val hasTrip: Boolean = tripMinutes.isNotEmpty() || tripNumber.isNotEmpty()

    companion object {
        /** The line for the next maneuver in [glance] with [units], or null without a route. */
        fun from(glance: CarPlayGlance.Snapshot, units: HudUnits): HudFrame? {
            val type = glance.maneuverType ?: return null
            val (number, unit) = distance(glance.distanceMeters.coerceAtLeast(0).toLong(), units)
            val trip = glance.remainingMeters?.let { distance(it, units) }
            return HudFrame(
                kind = sign(type),
                exitNumber = exitNumber(type),
                number = number,
                unit = unit,
                instructionLabel = glance.road,
                lanes = glance.lanes?.lanes.orEmpty().map(::lane),
                tripMinutes = glance.remainingSeconds?.let { if (it < 60) "<1" else (it / 60).toString() }.orEmpty(),
                minutesUnit = units.minutes,
                tripNumber = trip?.first.orEmpty(),
                tripUnit = trip?.second.orEmpty(),
            )
        }

        /** The roundabout exit Apple's [type] names, 1 to 19, or 0 for none. */
        fun exitNumber(type: Int): Int = if (type in 28..46) type - 27 else 0

        /**
         * Apple's iAP2 RouteGuidanceManeuverType, as the BYD cluster table and the MIB3 CarPlay RGI notes read it.
         * Types without a known side stay a neutral sign rather than a guess from the driving side.
         */
        fun sign(type: Int): HudManeuver = when (type) {
            0 -> HudManeuver.NO_TURN
            1, 20 -> HudManeuver.LEFT
            2, 21 -> HudManeuver.RIGHT
            3, 5, 11 -> HudManeuver.STRAIGHT
            4, 18, 19, 26 -> HudManeuver.UTURN
            6 -> HudManeuver.ROUNDABOUT_ENTER
            7, in 28..46 -> HudManeuver.ROUNDABOUT_EXIT
            13, 52 -> HudManeuver.KEEP_LEFT
            14, 53 -> HudManeuver.KEEP_RIGHT
            22 -> HudManeuver.EXIT_LEFT
            23 -> HudManeuver.EXIT_RIGHT
            47 -> HudManeuver.SHARP_LEFT
            48 -> HudManeuver.SHARP_RIGHT
            49 -> HudManeuver.SLIGHT_LEFT
            50 -> HudManeuver.SLIGHT_RIGHT
            // 17 changes ferries: drawn as boarding the next one.
            15, 17 -> HudManeuver.FERRY_ENTER
            16 -> HudManeuver.FERRY_EXIT
            10, 12, 24, 25, 27 -> HudManeuver.ARRIVE
            else -> HudManeuver.UNKNOWN
        }

        /** A lane without angles points to its highlight, or straight without one. */
        fun lane(lane: LaneGuidanceLane): HudLane {
            val angles = lane.angles.ifEmpty { listOf(lane.highlightAngle ?: 0) }
            val highlight = lane.highlightAngle?.let(::direction)
            val directions = (angles.mapNotNull(::direction) + listOfNotNull(highlight)).distinct()
            return HudLane(
                directions = directions,
                highlighted = if (lane.recommended) highlight ?: directions.singleOrNull() else null,
                preferred = lane.recommended,
            )
        }

        /** The nearest of the nine lane arrows to a signed angle in degrees, positive to the right; null outside ±180. */
        fun direction(angle: Int): HudLaneDirection? = when (angle) {
            in -22..22 -> HudLaneDirection.STRAIGHT
            in 23..67 -> HudLaneDirection.SLIGHT_RIGHT
            in 68..112 -> HudLaneDirection.RIGHT
            in 113..157 -> HudLaneDirection.SHARP_RIGHT
            in 158..180 -> HudLaneDirection.UTURN_RIGHT
            in -67..-23 -> HudLaneDirection.SLIGHT_LEFT
            in -112..-68 -> HudLaneDirection.LEFT
            in -157..-113 -> HudLaneDirection.SHARP_LEFT
            in -180..-158 -> HudLaneDirection.UTURN_LEFT
            else -> null
        }

        /**
         * Metres to the nearest 10 below 1 km, kilometres with one decimal below 10 km, the decimal separator of
         * [units], and a narrow no-break space between thousands from five digits on.
         */
        fun distance(meters: Long, units: HudUnits): Pair<String, String> {
            val rounded = if (meters < 1000) (meters + 5) / 10 * 10 else meters
            val kilometres = rounded >= 1000
            val value = if (kilometres) rounded / 1000.0 else rounded.toDouble()
            var text = String.format(Locale.ROOT, if (kilometres && value < 10) "%.1f" else "%.0f", value)
            if (text.endsWith(".0")) text = text.dropLast(2)
            val whole = text.indexOf('.').let { if (it < 0) text.length else it }
            if (whole >= 5) {
                val grouped = StringBuilder(text)
                var i = whole - 3
                while (i > 0) {
                    grouped.insert(i, '\u202F')
                    i -= 3
                }
                text = grouped.toString()
            }
            return text.replace('.', units.decimalSeparator) to if (kilometres) units.kilometres else units.meters
        }
    }
}
