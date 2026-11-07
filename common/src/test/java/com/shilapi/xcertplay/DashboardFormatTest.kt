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

    @Test fun unknownValuesAreOneDashNotZeros() {
        assertEquals("—", DashboardFormat.temperature(null))
        assertEquals("—", DashboardFormat.decimal(null))
        assertEquals("—", DashboardFormat.hours(null))
        assertEquals("—", DashboardFormat.minutesOfHour(null))
        assertEquals("—", DashboardFormat.arrival(null, moscow, is24Hour = true))
        assertEquals("—", DashboardFormat.remainingMinutes(null))
        assertEquals("—", DashboardFormat.remainingKm(null))
    }

    @Test fun theArrivalIsATimeByTheClockAndTheRestIsMinutesAndKilometres() {
        val arrival = evening / 1000 + 18 * 60
        assertEquals("22:34", DashboardFormat.arrival(arrival, moscow, is24Hour = true, Locale.US))
        assertEquals("10:34", DashboardFormat.arrival(arrival, moscow, is24Hour = false, Locale.US))
        assertEquals("18", DashboardFormat.remainingMinutes(18 * 60 + 29, Locale.US))
        assertEquals("19", DashboardFormat.remainingMinutes(18 * 60 + 30, Locale.US))
        assertEquals("0", DashboardFormat.remainingMinutes(20, Locale.US))
        assertEquals("125", DashboardFormat.remainingMinutes(125 * 60, Locale.US))
        assertEquals("9,4", DashboardFormat.remainingKm(9_400, Locale.forLanguageTag("ru-RU")))
        assertEquals("0.3", DashboardFormat.remainingKm(320, Locale.US))
    }

    @Test fun tripNumbersFollowTheDesign() {
        assertEquals("9.7", DashboardFormat.decimal(9.72, Locale.US))
        assertEquals("15,9", DashboardFormat.decimal(15.9, Locale.forLanguageTag("ru-RU")))
        assertEquals("5", DashboardFormat.hours(302, Locale.US))
        assertEquals("02", DashboardFormat.minutesOfHour(302, Locale.US))
        assertEquals("0", DashboardFormat.hours(45, Locale.US))
    }

    @Test fun aCallLastsInMinutesAndSecondsThenInHours() {
        assertEquals("00:00", DashboardFormat.callDuration(0, Locale.US))
        assertEquals("00:42", DashboardFormat.callDuration(42, Locale.US))
        assertEquals("02:14", DashboardFormat.callDuration(134, Locale.US))
        assertEquals("59:59", DashboardFormat.callDuration(3_599, Locale.US))
        assertEquals("1:00:00", DashboardFormat.callDuration(3_600, Locale.US))
        assertEquals("1:02:05", DashboardFormat.callDuration(3_725, Locale.US))
        assertEquals("12:00:01", DashboardFormat.callDuration(43_201, Locale.US))
        assertEquals("00:00", DashboardFormat.callDuration(-5, Locale.US))
    }
}
