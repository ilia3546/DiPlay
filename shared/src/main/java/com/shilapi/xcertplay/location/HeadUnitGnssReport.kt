package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.AccuracySource
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Decision
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Send
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Skip
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.SkipReason
import java.util.EnumMap
import java.util.Locale

internal class HeadUnitGnssReport(private val windowMillis: Long = SUMMARY_MILLIS) {
    private var windowStart = -1L
    private var sent = 0
    private val skipped = EnumMap<SkipReason, Int>(SkipReason::class.java)
    private val accuracySources = EnumMap<AccuracySource, Int>(AccuracySource::class.java)
    private val gstErrors = ArrayList<Double>()
    private val satellites = ArrayList<Double>()
    private val hdops = ArrayList<Double>()
    private val locationAccuracies = ArrayList<Double>()
    private val speedsKmh = ArrayList<Double>()
    private val gstFixTimeOffsets = ArrayList<Double>()

    fun record(
        nowMillis: Long,
        decision: Decision,
        locationAccuracyMeters: Double?,
        speedMetersPerSecond: Double?,
        gstFixTimeOffsetMillis: Long?,
    ) {
        if (windowStart < 0) windowStart = nowMillis
        when (decision) {
            is Send -> {
                sent++
                accuracySources.merge(decision.accuracySource, 1, Int::plus)
            }
            is Skip -> skipped.merge(decision.reason, 1, Int::plus)
        }
        decision.quality.gstErrorMeters?.let(gstErrors::add)
        decision.quality.hdop?.let(hdops::add)
        decision.quality.satellitesUsed?.let { satellites.add(it.toDouble()) }
        gstFixTimeOffsetMillis?.let { gstFixTimeOffsets.add(it.toDouble()) }
        if ((decision as? Skip)?.reason != SkipReason.STALE) {
            locationAccuracyMeters?.takeIf { it.isFinite() }?.let(locationAccuracies::add)
            speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 }?.let { speedsKmh.add(it * KMH_PER_METER_PER_SECOND) }
        }
    }

    fun summaryDue(nowMillis: Long): Boolean = windowStart >= 0 && nowMillis - windowStart >= windowMillis

    fun summary(nowMillis: Long, fixes: Int, nmeaMessages: Int): String {
        val line = "Head unit GPS: last ${(nowMillis - windowStart) / 1000} s fixes=$fixes sent=$sent " +
            "skipped=[stale=${skipped[SkipReason.STALE] ?: 0} noFix=${skipped[SkipReason.NO_FIX] ?: 0} " +
            "notSatellite=${skipped[SkipReason.NOT_SATELLITE] ?: 0} fewSatellites=${skipped[SkipReason.FEW_SATELLITES] ?: 0} accuracy=${skipped[SkipReason.ACCURACY] ?: 0}] " +
            "accuracyFrom=[gst=${accuracySources[AccuracySource.GST] ?: 0} " +
            "fallback=${accuracySources[AccuracySource.FALLBACK] ?: 0}] " +
            "gstError=${stats(gstErrors, " m", withMax = true)} " +
            "satellites=${stats(satellites, "", withMax = false, decimals = 0)} " +
            "hdop=${median(hdops)} locationAccuracy=${median(locationAccuracies, " m")} " +
            "gnssSpeed=${median(speedsKmh, " km/h")} gstFixTimeOffset=${median(gstFixTimeOffsets, " ms", decimals = 0)} " +
            "nmeaMessages=$nmeaMessages"
        windowStart = nowMillis
        sent = 0
        skipped.clear()
        accuracySources.clear()
        gstErrors.clear()
        satellites.clear()
        hdops.clear()
        locationAccuracies.clear()
        speedsKmh.clear()
        gstFixTimeOffsets.clear()
        return line
    }

    private fun stats(values: List<Double>, unit: String, withMax: Boolean, decimals: Int = 1): String {
        if (values.isEmpty()) return "none"
        val sorted = values.sorted()
        val max = if (withMax) " max=${format(sorted.last(), decimals)}" else ""
        return "[min=${format(sorted.first(), decimals)} median=${format(middle(sorted), decimals)}$max$unit]"
    }

    private fun median(values: List<Double>, unit: String = "", decimals: Int = 1): String =
        if (values.isEmpty()) "none" else "[median=${format(middle(values.sorted()), decimals)}$unit]"

    private fun middle(sorted: List<Double>): Double {
        val half = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[half] else (sorted[half - 1] + sorted[half]) / 2
    }

    private fun format(value: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)

    internal companion object {
        const val SUMMARY_MILLIS = 60_000L
        private const val KMH_PER_METER_PER_SECOND = 3.6
    }
}
