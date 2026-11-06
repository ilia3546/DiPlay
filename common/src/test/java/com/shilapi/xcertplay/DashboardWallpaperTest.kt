package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DashboardWallpaperTest {
    @Test fun aPresetIsDrawnAsSavedWithoutReadingThePhoto() {
        for (preset in DashboardBackground.presets) {
            var read = false
            assertEquals(preset to null, preset.drawn { read = true; null })
            assertFalse(read)
        }
    }

    @Test fun theDriversPhotoFallsBackToOceanWhileThereIsNone() {
        assertEquals(DashboardBackground.OCEAN to null, DashboardBackground.CUSTOM_PHOTO.drawn { null })
        val photo = Bitmap.createBitmap(16, 6, Bitmap.Config.ARGB_8888)
        val (wallpaper, picture) = DashboardBackground.CUSTOM_PHOTO.drawn { photo }
        assertEquals(DashboardBackground.CUSTOM_PHOTO, wallpaper)
        assertSame(photo, picture)
        assertNull(DashboardBackground.OCEAN.drawn { photo }.second)
    }

    @Test fun thirteenPresetsInTheDesignOrder() {
        assertEquals(listOf("OCEAN", "AURORA", "DUSK", "NEON", "GRAPHITE", "MIDNIGHT", "FOREST", "DUNES", "MAGMA", "ICE",
            "VIOLET", "CARBON", "BLACK"), DashboardBackground.presets.map { it.name })
    }

    @Test fun dimmingIsABlackLayerOfThatOpacity() {
        assertEquals(listOf(0, 51, 102, 153), DashboardBackground.dimmingPercents.map(::dashboardDimmingAlpha))
    }

    @Test fun aPictureIsCroppedAroundItsMiddleToTheDashboard() {
        assertEquals(Rect(0, 750, 4000, 2250), centerCrop(4000, 3000, 1920, 720))
        assertEquals(Rect(0, 0, 1920, 720), centerCrop(1920, 720, 1920, 720))
        assertEquals(Rect(367, 0, 633, 100), centerCrop(1000, 100, 1920, 720))
    }
}
