package com.shilapi.xcertplay

import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.HudManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "ru")
class DashboardRouteTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun noManeuverIsNoRoute() {
        assertNull(DashboardRoute.of(CarPlayGlance.Snapshot(connected = true)))
    }

    @Test fun theTurnUsesTheHeadUpDisplaysSignAndTheTurnCardsDistances() {
        val route = DashboardRoute.of(CarPlayGlance.Snapshot(connected = true, maneuverType = 2, distanceMeters = 350,
            road = "Садовое кольцо", arrivalEpochSeconds = 1_762_461_240, remainingSeconds = 1_080,
            remainingMeters = 9_400))!!
        assertEquals(HudManeuver.RIGHT to 0, route.sign to route.exitNumber)
        assertEquals("350 м", ClusterTurnCardView.distanceLabel(context, route.turn.distanceMeters))
        assertEquals("Садовое кольцо", ClusterTurnCardView.roadLabel(context, route.turn))
        assertEquals(1_080L to 9_400L, route.remainingSeconds to route.remainingMeters)
    }

    @Test fun aRoundaboutNamesItsExitAndALongDistanceIsInKilometres() {
        val route = DashboardRoute.of(CarPlayGlance.Snapshot(connected = true, maneuverType = 30, distanceMeters = 1_250,
            road = "Ring"))!!
        assertEquals(HudManeuver.ROUNDABOUT_EXIT to 3, route.sign to route.exitNumber)
        assertEquals("Съезд 3", ClusterTurnCardView.roadLabel(context, route.turn))
        assertEquals("1,2 км", ClusterTurnCardView.distanceLabel(context, route.turn.distanceMeters))
        assertEquals("Сейчас", ClusterTurnCardView.distanceLabel(context, 15))
    }
}
