package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterStreamOwnerTest {
    @Test fun withoutTheCustomDashboardTheStreamGoesWhereItAlwaysWent() {
        for (dashboardOn in listOf(false, true)) {
            assertEquals(ClusterStreamOwner.MAIN_SURFACE, ClusterStreamOwner.of(false, dashboardOn, false, false))
            assertEquals(ClusterStreamOwner.MAIN_SURFACE, ClusterStreamOwner.of(false, dashboardOn, false, true))
            assertEquals(ClusterStreamOwner.CLUSTER_DISPLAY, ClusterStreamOwner.of(false, dashboardOn, true, true))
            assertEquals(ClusterStreamOwner.NONE, ClusterStreamOwner.of(false, dashboardOn, true, false))
        }
    }

    @Test fun aCustomDashboardSessionKeepsTheStreamFromTheMainSurfaceAndTheClusterPresentation() {
        for (clusterMap in listOf(false, true)) {
            for (presentation in listOf(false, true)) {
                assertEquals(ClusterStreamOwner.CUSTOM_DASHBOARD,
                    ClusterStreamOwner.of(true, true, clusterMap, presentation))
            }
        }
    }

    @Test fun aDashboardStreamTurnedOffMidSessionGoesNowhereUntilTheNextSession() {
        for (clusterMap in listOf(false, true)) {
            for (presentation in listOf(false, true)) {
                assertEquals(ClusterStreamOwner.NONE, ClusterStreamOwner.of(true, false, clusterMap, presentation))
            }
        }
    }
}
