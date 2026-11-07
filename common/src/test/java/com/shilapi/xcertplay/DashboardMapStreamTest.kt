package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardMapStreamTest {
    @Test fun aPickedMapSizeIsStreamedExactlyAndCentredOnTheCar() {
        val wide = MonjaroDashboardLayout.MapGeometry(width = 800, height = 400)
        val config = DashboardMapStream.config(wide)
        assertEquals(800 to 400, config.widthPixels to config.heightPixels)
        assertEquals(560, wide.map.left)
        assertEquals(240, wide.map.top)
        assertEquals(wide.map.left + 800 - 15, wide.arrivalCard.left + wide.arrivalCard.width)
        assertEquals(MonjaroDashboardLayout.mapWidths.all { it % 8 == 0 } && MonjaroDashboardLayout.mapHeights.all { it % 8 == 0 }, true)
    }

    @Test fun theStreamIsTheMapCardRoundedForTheIphone() {
        val config = DashboardMapStream.config(MonjaroDashboardLayout.defaultMap)
        assertEquals(456 to 304, config.widthPixels to config.heightPixels)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
        assertEquals("under the cards, clear of the sides", AirPlayInsets(top = 91, bottom = 0, left = 36, right = 36),
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
