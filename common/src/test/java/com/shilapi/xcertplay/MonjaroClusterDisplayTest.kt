package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonjaroClusterDisplayTest {
    private val centre = DisplayDescription(0, "Built-in Screen", "local:129", 129, 1920, 720)
    private val cluster = DisplayDescription(1, "HDMI Screen", "local:130", 130, 1920, 720)
    private val passenger = DisplayDescription(2, "HDMI Screen", "local:131", 131, 1920, 720)
    private val hud = DisplayDescription(11, "HUD", "local:132", 132, 800, 480)

    @Test fun theClusterIsThePhysicalDisplay130() {
        assertEquals(1, MonjaroClusterDisplay.choose(listOf(centre, cluster, passenger, hud)).displayId)
    }

    @Test fun eitherAddressIsEnough() {
        assertEquals(1, MonjaroClusterDisplay.choose(listOf(centre, cluster.copy(physicalId = null))).displayId)
        assertEquals(1, MonjaroClusterDisplay.choose(listOf(centre, cluster.copy(uniqueId = null))).displayId)
    }

    @Test fun theSizeAloneNeverPicksADisplay() {
        val choice = MonjaroClusterDisplay.choose(listOf(centre, passenger, hud))
        assertNull(choice.displayId)
        assertEquals("no display at local:130", choice.reason)
    }

    @Test fun unreadableAddressesPickNothing() {
        val hidden = listOf(centre, cluster, passenger).map { it.copy(uniqueId = null, physicalId = null) }
        assertEquals(ClusterDisplayChoice(null, "no display address readable"), MonjaroClusterDisplay.choose(hidden))
    }

    @Test fun anAmbiguousAnswerPicksNothing() {
        assertNull(MonjaroClusterDisplay.choose(listOf(cluster, cluster.copy(displayId = 5))).displayId)
        assertNull(MonjaroClusterDisplay.choose(listOf(centre, cluster.copy(physicalId = 131))).displayId)
    }

    @Test fun aClusterDisplayOfAnotherSizePicksNothing() {
        val choice = MonjaroClusterDisplay.choose(listOf(centre, cluster.copy(width = 1280)))
        assertNull(choice.displayId)
        assertEquals("local:130 is 1280x720, not 1920x720", choice.reason)
    }

    @Test fun aShownWindowStaysOnlyOnTheCurrentClusterDisplay() {
        assertTrue(MonjaroClusterDisplay.usable(permitted = true, chosenDisplayId = 1, shownOnDisplayId = null))
        assertTrue(MonjaroClusterDisplay.usable(permitted = true, chosenDisplayId = 1, shownOnDisplayId = 1))
        assertFalse("the display came back under a new id",
            MonjaroClusterDisplay.usable(permitted = true, chosenDisplayId = 3, shownOnDisplayId = 1))
        assertFalse(MonjaroClusterDisplay.usable(permitted = true, chosenDisplayId = null, shownOnDisplayId = 1))
        assertFalse(MonjaroClusterDisplay.usable(permitted = false, chosenDisplayId = 1, shownOnDisplayId = 1))
    }
}
