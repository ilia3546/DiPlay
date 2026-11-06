package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HudUnitsTest {
    private val route = CarPlayGlance.Snapshot(
        connected = true, maneuverType = 2, maneuverIndex = 0, distanceMeters = 1500, road = "Main Street",
        remainingSeconds = 720, remainingMeters = 4200,
    )

    private fun line(): List<String> {
        val frame = HudFrame.from(route, HudUnits.of(RuntimeEnvironment.getApplication()))!!
        return listOf(frame.number, frame.unit, frame.tripMinutes, frame.minutesUnit, frame.tripNumber, frame.tripUnit)
    }

    @Test @Config(qualifiers = "ru")
    fun russianUnitsAndADecimalComma() {
        assertEquals(HudUnits("м", "км", "мин", ','), HudUnits.of(RuntimeEnvironment.getApplication()))
        assertEquals(listOf("1,5", "км", "12", "мин", "4,2", "км"), line())
    }

    @Test @Config(qualifiers = "en")
    fun englishUnitsAndADecimalPoint() {
        assertEquals(HudUnits("m", "km", "min", '.'), HudUnits.of(RuntimeEnvironment.getApplication()))
        assertEquals(listOf("1.5", "km", "12", "min", "4.2", "km"), line())
    }
}
