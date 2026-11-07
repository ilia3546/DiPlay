package com.shilapi.xcertplay

import android.content.Context
import android.graphics.BitmapFactory
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarButtonDefaultsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun onMonjaroA3() {
        ShadowBuild.setProduct("geely_fx11")
        ShadowBuild.setModel("KX11-A3-L1")
    }

    @Test fun otherHeadUnitsKeepTheBydButton() {
        assertEquals(R.raw.ic_car_home, AirPlayPersistence.defaultCarButtonIcon)
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun theMonjaroA3ShowsGeelysButton() {
        onMonjaroA3()
        assertEquals(R.raw.ic_car_home_geely, AirPlayPersistence.defaultCarButtonIcon)
        assertEquals("Geely", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun aNameTheDriverSavedWinsOverTheBrand() {
        onMonjaroA3()
        AirPlayPersistence.saveOemLabel(context, "My car")
        assertEquals("My car", AirPlayPersistence.loadOemLabel(context))
        AirPlayPersistence.saveOemLabel(context, "")
        assertEquals("Geely", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun theGeelyIconIsSentWithoutResizing() {
        val bytes = context.resources.openRawResource(R.raw.ic_car_home_geely).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(AirPlayIconSizing.MAX_PIXELS, bounds.outWidth)
        assertEquals(AirPlayIconSizing.MAX_PIXELS, bounds.outHeight)
    }
}
