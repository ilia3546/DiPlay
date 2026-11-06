package com.shilapi.xcertplay

/** Which surface decodes CarPlay's cluster stream (111); a CarPlay screen hands it only to that one. */
internal enum class ClusterStreamOwner {
    /** The custom dashboard decodes the stream through [DashboardMapStream], whichever screen hosts the session. */
    CUSTOM_DASHBOARD,
    CLUSTER_DISPLAY,
    MAIN_SURFACE,

    /** The cluster map is on, but its display has no surface yet. */
    NONE;

    companion object {
        fun of(customDashboard: Boolean, clusterMapEnabled: Boolean, clusterSurfaceReady: Boolean): ClusterStreamOwner =
            when {
                customDashboard -> CUSTOM_DASHBOARD
                !clusterMapEnabled -> MAIN_SURFACE
                clusterSurfaceReady -> CLUSTER_DISPLAY
                else -> NONE
            }
    }
}
