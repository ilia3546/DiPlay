package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterStreamOwnerTest {
    @Test fun withoutTheCustomDashboardTheStreamGoesWhereItAlwaysWent() {
        assertEquals(ClusterStreamOwner.MAIN_SURFACE, ClusterStreamOwner.of(false, false, false))
        assertEquals(ClusterStreamOwner.MAIN_SURFACE, ClusterStreamOwner.of(false, false, true))
        assertEquals(ClusterStreamOwner.CLUSTER_DISPLAY, ClusterStreamOwner.of(false, true, true))
        assertEquals(ClusterStreamOwner.NONE, ClusterStreamOwner.of(false, true, false))
    }

    @Test fun aCustomDashboardSessionKeepsTheStreamFromTheMainSurfaceAndTheClusterPresentation() {
        for (clusterMap in listOf(false, true)) {
            for (presentation in listOf(false, true)) {
                assertEquals(ClusterStreamOwner.CUSTOM_DASHBOARD, ClusterStreamOwner.of(true, clusterMap, presentation))
            }
        }
    }
}
