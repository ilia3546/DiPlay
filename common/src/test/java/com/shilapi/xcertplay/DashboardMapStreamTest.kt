package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardMapStreamTest {
    @Test fun theStreamIsTheMapCardRoundedForTheIphone() {
        val config = DashboardMapStream.config
        assertEquals(704 to 408, config.widthPixels to config.heightPixels)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
        assertEquals("under the cards, clear of the sides", AirPlayInsets(top = 102, bottom = 0, left = 56, right = 56),
            config.safeArea)
    }

    @Test fun theCardCropsTheRoundingAtTheCentre() {
        val (scaleX, scaleY) = centreCropScale(700, 404, 704, 408)
        assertEquals(1f, scaleX, 0.0001f)
        assertEquals(700f / 704 * 408 / 404, scaleY, 0.0001f)
        assertEquals(1f to 1f, centreCropScale(700, 404, 700, 404))
        val (wideX, wideY) = centreCropScale(700, 404, 1280, 720)
        assertEquals(404f / 720 * 1280 / 700, wideX, 0.0001f)
        assertEquals(1f, wideY, 0.0001f)
    }
}
