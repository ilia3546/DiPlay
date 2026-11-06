package com.shilapi.xcertplay.hud

/** Next-turn instruction for the dashboard overlay. Icons use the AMap NEW_ICON vocabulary. */
data class ClusterTurnGuidance(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
    /** Route-level arrival/duration/distance for the info strip below the card. */
    val arrivalEpochSeconds: Long? = null,
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
) {
    companion object {
        internal fun from(frame: BydClusterFrame): ClusterTurnGuidance {
            return ClusterTurnGuidance(frame.icon, frame.roundaboutExit, frame.distanceMeters, frame.road)
        }

        /** The instruction for an Apple RouteGuidanceManeuverType, as [com.shilapi.xcertplay.glance.CarPlayGlance] gives it. */
        fun fromApple(type: Int, drivingSide: Int, distanceMeters: Int, road: String): ClusterTurnGuidance =
            from(BydClusterFrame.from(BydAppleManeuver(distanceMeters, type, drivingSide, road)))
    }
}
