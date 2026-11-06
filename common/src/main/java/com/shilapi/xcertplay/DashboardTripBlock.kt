package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import com.shilapi.xcertplay.host.R

/** The current trip as the dashboard shows it; a null value is shown as [DashboardFormat.UNKNOWN]. */
internal data class DashboardTrip(
    val distanceKm: Double? = null,
    val drivingMinutes: Int? = null,
    val litresPer100Km: Double? = null,
)

/** The left block of design v2 A: the current trip's distance, driving time and average consumption. */
internal class DashboardTripBlock(context: Context) : LinearLayout(context) {
    /** A number and its unit, with the unit's gaps before and after it. */
    private class Part(val number: TextView, val unit: Int, val before: Int, val after: Int = 0)

    private val distance = number()
    private val hours = number()
    private val minutes = number()
    private val consumption = number()

    init {
        orientation = VERTICAL
        addView(dashboardText(context, L.TRIP_TITLE_TEXT, 600, L.TITLE_MUTED).apply {
            isAllCaps = true
            letterSpacing = L.TRIP_TITLE_LETTER_SPACING / L.TRIP_TITLE_TEXT
            setText(R.string.custom_dashboard_trip)
        })
        row(R.string.custom_dashboard_trip_distance, Part(distance, R.string.custom_dashboard_unit_km, L.TRIP_UNIT_GAP))
        row(R.string.custom_dashboard_trip_time,
            Part(hours, R.string.custom_dashboard_unit_hours, L.TRIP_HOURS_GAP_BEFORE, L.TRIP_HOURS_GAP_AFTER),
            Part(minutes, R.string.custom_dashboard_unit_minutes, L.TRIP_MINUTES_GAP))
        row(R.string.custom_dashboard_trip_consumption,
            Part(consumption, R.string.custom_dashboard_unit_litres_per_100_km, L.TRIP_UNIT_GAP))
    }

    fun show(trip: DashboardTrip) {
        distance.text = DashboardFormat.decimal(trip.distanceKm)
        hours.text = DashboardFormat.hours(trip.drivingMinutes)
        minutes.text = DashboardFormat.minutesOfHour(trip.drivingMinutes)
        consumption.text = DashboardFormat.decimal(trip.litresPer100Km)
    }

    private fun number(): TextView = dashboardText(context, L.TRIP_VALUE_TEXT, 600)

    private fun row(label: Int, vararg parts: Part) {
        val item = LinearLayout(context).apply { orientation = VERTICAL }
        item.addView(dashboardText(context, L.TRIP_LABEL_TEXT, 400, L.LABEL).apply { setText(label) })
        val value = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (part in parts) {
            value.addView(part.number)
            value.addView(dashboardText(context, L.TRIP_UNIT_TEXT, 500, L.LABEL).apply { setText(part.unit) },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = part.before
                    marginEnd = part.after
                })
        }
        item.addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = L.TRIP_LABEL_GAP
        })
        addView(item, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = L.TRIP_ROW_GAP
        })
    }
}
