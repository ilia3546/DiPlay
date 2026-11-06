package com.shilapi.xcertplay

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardFormatTest {
    private val moscow = TimeZone.getTimeZone("Europe/Moscow")
    private val evening = 1_762_456_560_000L // 2025-11-06 19:16 UTC, 22:16 in Moscow

    @Test fun theClockFollowsTheSystemHourFormat() {
        assertEquals("22:16", DashboardFormat.clock(evening, moscow, is24Hour = true, Locale.US))
        assertEquals("10:16", DashboardFormat.clock(evening, moscow, is24Hour = false, Locale.US))
        assertEquals("19:16", DashboardFormat.clock(evening, TimeZone.getTimeZone("UTC"), is24Hour = true, Locale.US))
    }

    @Test fun theTemperatureIsRoundedToWholeDegrees() {
        assertEquals("6°C", DashboardFormat.temperature(6.4f, Locale.US))
        assertEquals("7°C", DashboardFormat.temperature(6.6f, Locale.US))
        assertEquals("0°C", DashboardFormat.temperature(-0.4f, Locale.US))
        assertEquals("-12°C", DashboardFormat.temperature(-11.6f, Locale.US))
    }

    @Test fun unknownValuesAreDashesNotZeros() {
        assertEquals("--", DashboardFormat.temperature(null))
        assertEquals("--", DashboardFormat.decimal(null))
        assertEquals("--", DashboardFormat.hours(null))
        assertEquals("--", DashboardFormat.minutesOfHour(null))
    }

    @Test fun tripNumbersFollowTheDesign() {
        assertEquals("9.7", DashboardFormat.decimal(9.72, Locale.US))
        assertEquals("15,9", DashboardFormat.decimal(15.9, Locale.forLanguageTag("ru-RU")))
        assertEquals("5", DashboardFormat.hours(302, Locale.US))
        assertEquals("02", DashboardFormat.minutesOfHour(302, Locale.US))
        assertEquals("0", DashboardFormat.hours(45, Locale.US))
    }
}
