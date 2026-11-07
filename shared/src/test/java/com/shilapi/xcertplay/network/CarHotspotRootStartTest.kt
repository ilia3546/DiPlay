package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.CarHotspotTethering.StartMode
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarHotspotRootStartTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("diplay_car_hotspot", 0)

    @Before fun reset() {
        prefs.edit().clear().commit()
    }

    @Test fun theFirstRootStartBuildsSavedChoiceIsClearedOnceOnAMonjaro() {
        monjaro()
        prefs.edit().putBoolean("root_start", true).putBoolean("auto_enable", true).commit()
        assertFalse(CarHotspotSettings.enabled(context))
        assertTrue(CarHotspotSettings.rootStart(context))
        assertEquals(StartMode.ROOT, CarHotspotTethering.startMode(context))

        prefs.edit().putBoolean("auto_enable", true).commit()
        assertTrue(CarHotspotSettings.enabled(context))
    }

    @Test fun withoutRootStartTheSavedChoiceIsKept() {
        monjaro()
        prefs.edit().putBoolean("auto_enable", true).commit()
        assertTrue(CarHotspotSettings.enabled(context))
    }

    @Test fun otherHeadUnitsAreNotMigrated() {
        prefs.edit().putBoolean("root_start", true).putBoolean("auto_enable", true).commit()
        assertTrue(CarHotspotSettings.enabled(context))
        assertFalse(prefs.contains("root_start_migrated"))
    }

    @Test fun rootStartIsItsOwnTrigger() {
        monjaro()
        CarHotspotSettings.setRootStart(context, true)
        assertFalse(CarHotspotSettings.enabled(context))
        assertTrue(CarHotspotSettings.shouldEnable(context, true, WirelessHotspotMode.MANUAL))
        assertFalse(CarHotspotSettings.shouldEnable(context, false, WirelessHotspotMode.MANUAL))
        assertFalse(CarHotspotSettings.shouldEnable(context, true, WirelessHotspotMode.WIFI_P2P))
        assertTrue(CarHotspotTethering.canStart(context))
    }

    @Test fun aRestoredChoiceThatNoStartPathCanServeLeavesConnectionsToTheDriver() {
        // Android 11 off BYD: the settings never show this choice, but a backup brings it back.
        monjaro()
        CarHotspotSettings.setEnabled(context, true)
        assertEquals(StartMode.NONE, CarHotspotTethering.startMode(context))
        assertFalse(CarHotspotSettings.shouldEnable(context, true, WirelessHotspotMode.MANUAL))
        assertFalse(CarHotspotTethering.canStart(context))
    }

    @Test @Config(sdk = [29]) fun olderAndroidKeepsTheSystemAccessPath() {
        CarHotspotSettings.setEnabled(context, true)
        assertEquals(StartMode.SYSTEM_ACCESS, CarHotspotTethering.startMode(context))
        assertTrue(CarHotspotSettings.shouldEnable(context, true, WirelessHotspotMode.MANUAL))
        assertEquals(CarHotspotTethering.permitted(context), CarHotspotTethering.canStart(context))
        CarHotspotSettings.setEnabled(context, false)
        assertEquals(StartMode.NONE, CarHotspotTethering.startMode(context))
    }

    private fun monjaro() {
        ShadowBuild.setProduct("geely_fx11")
        ShadowBuild.setModel("KX11-A3-L1")
        assertTrue(GeelyHeadUnit.monjaroA3)
    }
}
