package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

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

    @Test fun aPictureIsDecodedAtTheSmallestSizeThatStillFillsTheDashboard() {
        assertEquals(2, DashboardPhoto.sampleSize(4000, 3000, 1920, 720))
        assertEquals(1, DashboardPhoto.sampleSize(1920, 720, 1920, 720))
        assertEquals(1, DashboardPhoto.sampleSize(800, 600, 1920, 720))
        assertEquals(4, DashboardPhoto.sampleSize(8000, 6000, 1920, 720))
        assertEquals(1, DashboardPhoto.sampleSize(3000, 1000, 1920, 720))
    }

    @Test fun onlyTheMiddleOfAPictureIsDecodedAndSubsampledAlongItsCrop() {
        assertEquals(DashboardPhotoCrop(Rect(0, 750, 4000, 2250), 2),
            DashboardPhoto.crop(4000, 3000, ExifInterface.ORIENTATION_NORMAL))
        assertEquals("a long panorama", DashboardPhotoCrop(Rect(8667, 0, 11333, 1000), 1),
            DashboardPhoto.crop(20000, 1000, ExifInterface.ORIENTATION_NORMAL))
        assertEquals("a tall strip", DashboardPhotoCrop(Rect(0, 9812, 1000, 10187), 1),
            DashboardPhoto.crop(1000, 20000, ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals("a wide panorama keeps its full height", DashboardPhotoCrop(Rect(4667, 0, 15333, 4000), 4),
            DashboardPhoto.crop(20000, 4000, ExifInterface.ORIENTATION_NORMAL))
    }

    @Test fun aTurnedPictureIsCroppedUprightFirst() {
        for (turned in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)) {
            assertEquals("a landscape photo taken upright, stored portrait", DashboardPhotoCrop(Rect(750, 0, 2250, 4000), 2),
                DashboardPhoto.crop(3000, 4000, turned))
        }
        for (flat in listOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL)) {
            assertEquals(DashboardPhotoCrop(Rect(0, 1437, 3000, 2562), 1), DashboardPhoto.crop(3000, 4000, flat))
        }
    }

    @Test fun theUprightTurnFollowsTheExifOrientation() {
        fun corner(orientation: Int): List<Int> =
            floatArrayOf(1f, 0f).also(DashboardPhoto.upright(orientation)::mapPoints).map { it.roundToInt() }
        assertEquals(listOf(1, 0), corner(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(listOf(0, 1), corner(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(listOf(0, -1), corner(ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(listOf(-1, 0), corner(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(listOf(-1, 0), corner(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(listOf(1, 0), corner(ExifInterface.ORIENTATION_FLIP_VERTICAL))
        assertEquals(listOf(0, 1), corner(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(listOf(0, -1), corner(ExifInterface.ORIENTATION_TRANSVERSE))
    }
}
