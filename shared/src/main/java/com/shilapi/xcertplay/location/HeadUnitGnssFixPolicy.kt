package com.shilapi.xcertplay.location

import kotlin.math.abs

object HeadUnitGnssFixPolicy {
    const val MAX_FIX_AGE_MILLIS = 2_000L
    const val MAX_NMEA_AGE_MILLIS = 2_000L
    const val MAX_NMEA_FIX_TIME_OFFSET_MILLIS = 500L
    const val MIN_SATELLITES_USED = 5
    const val MAX_HORIZONTAL_ERROR_METERS = 50.0
    const val MIN_FALLBACK_ACCURACY_METERS = 10.0
    const val FALLBACK_METERS_PER_HDOP = 5.0
    val SATELLITE_FIX_QUALITIES = setOf(1, 2, 4, 5)

    enum class SkipReason { STALE, NO_FIX, NOT_SATELLITE, FEW_SATELLITES, ACCURACY }

    enum class AccuracySource { GST, FALLBACK }

    data class Observed<T>(val sentence: T, val ageMillis: Long, val fixTimeOffsetMillis: Long?)

    data class Quality(val satellitesUsed: Int?, val hdop: Double?, val gstErrorMeters: Double?)

    sealed interface Decision {
        val quality: Quality
    }

    data class Send(val accuracyMeters: Double, val accuracySource: AccuracySource, override val quality: Quality) : Decision

    data class Skip(val reason: SkipReason, override val quality: Quality) : Decision

    fun decide(
        fixAgeMillis: Long?,
        locationAccuracyMeters: Double?,
        locationSatellites: Int?,
        gga: Observed<NmeaSentenceParser.Gga>?,
        gst: Observed<NmeaSentenceParser.Gst>?,
    ): Decision {
        val matchedGga = gga?.takeIf(::matches)?.sentence
        val satellites = matchedGga?.satellitesUsed ?: locationSatellites
        val hdop = matchedGga?.hdop
        val gstError = gst?.takeIf(::matches)?.sentence?.horizontalErrorMeters
        val quality = Quality(satellites, hdop, gstError)
        val fixQuality = matchedGga?.fixQuality
        val skip = when {
            fixAgeMillis == null || fixAgeMillis < 0 || fixAgeMillis > MAX_FIX_AGE_MILLIS -> SkipReason.STALE
            fixQuality == 0 -> SkipReason.NO_FIX
            fixQuality != null && fixQuality !in SATELLITE_FIX_QUALITIES -> SkipReason.NOT_SATELLITE
            satellites != null && satellites < MIN_SATELLITES_USED -> SkipReason.FEW_SATELLITES
            else -> null
        }
        if (skip != null) return Skip(skip, quality)
        val send = if (gstError != null) {
            Send(gstError, AccuracySource.GST, quality)
        } else {
            val reported = locationAccuracyMeters?.takeIf { it.isFinite() } ?: 0.0
            val fromHdop = hdop?.let { it * FALLBACK_METERS_PER_HDOP } ?: 0.0
            Send(maxOf(MIN_FALLBACK_ACCURACY_METERS, reported, fromHdop), AccuracySource.FALLBACK, quality)
        }
        return if (send.accuracyMeters > MAX_HORIZONTAL_ERROR_METERS) Skip(SkipReason.ACCURACY, quality) else send
    }

    fun timeOfDayOffsetMillis(sentenceTimeOfDayMillis: Long, fixTimeMillis: Long): Long {
        val difference = Math.floorMod(sentenceTimeOfDayMillis - Math.floorMod(fixTimeMillis, DAY_MILLIS), DAY_MILLIS)
        return if (difference > DAY_MILLIS / 2) difference - DAY_MILLIS else difference
    }

    private const val DAY_MILLIS = 86_400_000L

    private fun matches(observed: Observed<*>): Boolean {
        val offset = observed.fixTimeOffsetMillis ?: return false
        return observed.ageMillis in 0..MAX_NMEA_AGE_MILLIS && abs(offset) <= MAX_NMEA_FIX_TIME_OFFSET_MILLIS
    }
}
