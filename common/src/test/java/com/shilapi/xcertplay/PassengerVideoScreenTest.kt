package com.shilapi.xcertplay

import android.content.Context
import android.view.Display
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PassengerVideoScreenTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val centre = DisplayDescription(0, "Built-in Screen", "local:129", 129, 1920, 720)
    private val cluster = DisplayDescription(1, "HDMI Screen", "local:130", 130, 1920, 720)
    private val passenger = DisplayDescription(2, "HDMI Screen", "local:131", 131, 1920, 720)
    private val hud = DisplayDescription(11, "HUD", "local:132", 132, 800, 480)

    @After fun clean() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun thePassengerScreenIsThePhysicalDisplay131() {
        assertEquals(2, MonjaroPassengerDisplay.choose(listOf(centre, cluster, passenger, hud)).displayId)
        assertEquals(2, MonjaroPassengerDisplay.choose(listOf(centre, passenger.copy(physicalId = null))).displayId)
    }

    @Test fun theSizeOrAnAmbiguousAddressNeverPicksThePassengerScreen() {
        assertNull(MonjaroPassengerDisplay.choose(listOf(centre, cluster, hud)).displayId)
        assertNull(MonjaroPassengerDisplay.choose(listOf(passenger, passenger.copy(displayId = 5))).displayId)
        assertNull(MonjaroPassengerDisplay.choose(listOf(centre, passenger.copy(width = 1280))).displayId)
        assertNull(MonjaroPassengerDisplay.choose(listOf(centre.copy(uniqueId = null, physicalId = null))).displayId)
    }

    @Test fun thePassengerScreenIsAvailableOnlyWhileItIsOn() {
        assertEquals(2, PassengerVideoScreen.available(2, Display.STATE_ON))
        for (state in listOf(Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_UNKNOWN, null)) {
            assertNull(PassengerVideoScreen.available(2, state))
        }
    }

    @Test fun theOptionIsOffByDefaultAndOffOnAnyOtherHeadUnit() {
        assertFalse(PassengerVideoScreen.enabled(context))
        AirPlayPersistence.savePassengerVideo(context, true)
        assertFalse(PassengerVideoScreen.enabled(context))
        assertNull(PassengerVideoScreen.identity(context))
        assertNull(PassengerVideoScreen.available(context))
        ShadowBuild.setProduct("geely_fx11")
        ShadowBuild.setModel("KX11-A3-L1")
        assertTrue(PassengerVideoScreen.enabled(context))
        // Robolectric's displays have no readable address, so none is the passenger screen.
        assertNull(PassengerVideoScreen.identity(context))
        assertNull(PassengerVideoScreen.available(context))
    }
}
