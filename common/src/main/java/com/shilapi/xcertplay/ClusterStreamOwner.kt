package com.shilapi.xcertplay

/** Which surface decodes CarPlay's cluster stream (111); a CarPlay screen hands it only to that one. */
internal enum class ClusterStreamOwner {
    /** The custom dashboard decodes the stream through [DashboardMapStream], whichever screen hosts the session. */
    CUSTOM_DASHBOARD,
    CLUSTER_DISPLAY,
    MAIN_SURFACE,

    /**
     * The cluster map is on, but its display has no surface yet; or the stream was sized for the custom dashboard,
     * which was turned off, and no other surface takes it until the next session.
     */
    NONE;

    companion object {
        fun of(
            customDashboardStream: Boolean,
            customDashboard: Boolean,
            clusterMapEnabled: Boolean,
            clusterSurfaceReady: Boolean,
        ): ClusterStreamOwner =
            when {
                customDashboardStream -> if (customDashboard) CUSTOM_DASHBOARD else NONE
                !clusterMapEnabled -> MAIN_SURFACE
                clusterSurfaceReady -> CLUSTER_DISPLAY
                else -> NONE
            }
    }
}
