package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** The custom dashboard's numbers; a value the head unit does not give is [UNKNOWN], never a zero. */
internal object DashboardFormat {
    const val UNKNOWN = "--"

    /** Without an AM/PM marker in the 12-hour form, as the cluster has no room for one. */
    fun clock(millis: Long, zone: TimeZone, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm", locale).apply { timeZone = zone }.format(Date(millis))

    fun temperature(celsius: Float?, locale: Locale = Locale.getDefault()): String {
        if (celsius == null) return UNKNOWN
        return String.format(locale, "%d°C", celsius.roundToInt())
    }

    fun decimal(value: Double?, locale: Locale = Locale.getDefault()): String =
        if (value == null) UNKNOWN else String.format(locale, "%.1f", value)

    fun hours(minutes: Int?, locale: Locale = Locale.getDefault()): String =
        if (minutes == null) UNKNOWN else String.format(locale, "%d", minutes / 60)

    fun minutesOfHour(minutes: Int?, locale: Locale = Locale.getDefault()): String =
        if (minutes == null) UNKNOWN else String.format(locale, "%02d", minutes % 60)

    fun arrival(epochSeconds: Long?, zone: TimeZone, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        if (epochSeconds == null) UNKNOWN else clock(epochSeconds * 1000, zone, is24Hour, locale)

    fun remainingMinutes(seconds: Long?, locale: Locale = Locale.getDefault()): String =
        if (seconds == null) UNKNOWN else String.format(locale, "%d", (seconds + 30) / 60)

    fun remainingKm(meters: Long?, locale: Locale = Locale.getDefault()): String = decimal(meters?.div(1000.0), locale)
}
