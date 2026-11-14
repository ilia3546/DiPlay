package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.AccuracySource
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Observed
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Quality
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Send
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Skip
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadUnitGnssFixPolicyTest {
    private val goodGga = NmeaSentenceParser.Gga(fixQuality = 1, satellitesUsed = 24, hdop = 0.7)
    private val gst = NmeaSentenceParser.Gst(3.0, 4.0)

    private fun <T> seen(sentence: T, ageMillis: Long = 300, fixTimeOffsetMillis: Long? = 0) =
        Observed(sentence, ageMillis, fixTimeOffsetMillis)

    private fun decide(
        fixAgeMillis: Long? = 300,
        locationAccuracyMeters: Double? = 0.8,
        locationSatellites: Int? = null,
        gga: Observed<NmeaSentenceParser.Gga>? = seen(goodGga),
        gst: Observed<NmeaSentenceParser.Gst>? = seen(this.gst),
    ) = HeadUnitGnssFixPolicy.decide(fixAgeMillis, locationAccuracyMeters, locationSatellites, gga, gst)

    @Test
    fun sendsAFreshFixWithTheGstErrorAndTheGgaSatellites() {
        assertEquals(Send(5.0, AccuracySource.GST, Quality(24, 0.7, 5.0)), decide())
    }

    @Test
    fun withoutGstTheAccuracyIsTheLargestOfTenMetresTheReportedOneAndFiveTimesHdop() {
        assertEquals(Send(10.0, AccuracySource.FALLBACK, Quality(24, 0.7, null)), decide(gst = null))
        assertEquals(Send(25.0, AccuracySource.FALLBACK, Quality(24, 0.7, null)), decide(locationAccuracyMeters = 25.0, gst = null))
        assertEquals(Send(10.0, AccuracySource.FALLBACK, Quality(24, null, null)),
            decide(locationAccuracyMeters = null, gga = seen(goodGga.copy(hdop = null)), gst = null))
        assertEquals(Send(15.0, AccuracySource.FALLBACK, Quality(24, 3.0, null)),
            decide(gga = seen(goodGga.copy(hdop = 3.0)), gst = null))
        assertEquals(SkipReason.ACCURACY, (decide(gga = seen(goodGga.copy(hdop = 11.0)), gst = null) as Skip).reason)
    }

    @Test
    fun anOldGstFallsBack() {
        val old = decide(gst = seen(gst, ageMillis = HeadUnitGnssFixPolicy.MAX_NMEA_AGE_MILLIS + 1))

        assertEquals(Send(10.0, AccuracySource.FALLBACK, Quality(24, 0.7, null)), old)
        assertEquals(AccuracySource.GST,
            (decide(gst = seen(gst, ageMillis = HeadUnitGnssFixPolicy.MAX_NMEA_AGE_MILLIS)) as Send).accuracySource)
    }

    @Test
    fun aGstOfAnotherEpochIsNotUsedForTheFix() {
        val limit = HeadUnitGnssFixPolicy.MAX_NMEA_FIX_TIME_OFFSET_MILLIS

        assertEquals(AccuracySource.FALLBACK, (decide(gst = seen(gst, fixTimeOffsetMillis = limit + 1)) as Send).accuracySource)
        assertEquals(AccuracySource.FALLBACK, (decide(gst = seen(gst, fixTimeOffsetMillis = -limit - 1)) as Send).accuracySource)
        assertEquals(AccuracySource.FALLBACK, (decide(gst = seen(gst, fixTimeOffsetMillis = null)) as Send).accuracySource)
        assertEquals(AccuracySource.GST, (decide(gst = seen(gst, fixTimeOffsetMillis = -limit)) as Send).accuracySource)
    }

    @Test
    fun sentenceTimeIsComparedWithTheFixTimeAcrossMidnight() {
        val dayStart = 1_784_160_000_000L

        assertEquals(-300L, HeadUnitGnssFixPolicy.timeOfDayOffsetMillis(86_399_800L, dayStart + 100))
        assertEquals(300L, HeadUnitGnssFixPolicy.timeOfDayOffsetMillis(100L, dayStart - 200))
        assertEquals(0L, HeadUnitGnssFixPolicy.timeOfDayOffsetMillis(45_319_000L, dayStart + 45_319_000L))
        assertEquals(-1_000L, HeadUnitGnssFixPolicy.timeOfDayOffsetMillis(45_318_000L, dayStart + 45_319_000L))
    }

    @Test
    fun aSentenceFromTheNextOrPreviousEpochIsRejected() {
        val previousEpoch = HeadUnitGnssFixPolicy.timeOfDayOffsetMillis(45_318_000L, 1_784_160_000_000L + 45_319_000L)

        assertEquals(AccuracySource.FALLBACK, (decide(gst = seen(gst, fixTimeOffsetMillis = previousEpoch)) as Send).accuracySource)
        assertEquals(AccuracySource.FALLBACK, (decide(gst = seen(gst, fixTimeOffsetMillis = 1_000)) as Send).accuracySource)
        assertEquals(AccuracySource.GST, (decide(gst = seen(gst, fixTimeOffsetMillis = 0)) as Send).accuracySource)
    }

    @Test
    fun anOldMissingOrFutureFixIsNotSent() {
        assertEquals(SkipReason.STALE, (decide(fixAgeMillis = HeadUnitGnssFixPolicy.MAX_FIX_AGE_MILLIS + 1) as Skip).reason)
        assertEquals(SkipReason.STALE, (decide(fixAgeMillis = null) as Skip).reason)
        assertEquals(SkipReason.STALE, (decide(fixAgeMillis = -1) as Skip).reason)
        assertEquals(AccuracySource.GST, (decide(fixAgeMillis = HeadUnitGnssFixPolicy.MAX_FIX_AGE_MILLIS) as Send).accuracySource)
    }

    @Test
    fun onlySatelliteFixQualitiesAreSent() {
        assertEquals(Skip(SkipReason.NO_FIX, Quality(0, null, 5.0)), decide(gga = seen(NmeaSentenceParser.Gga(0, 0, null))))
        for (quality in listOf(3, 6, 7, 8, 9)) {
            assertEquals("quality $quality", SkipReason.NOT_SATELLITE,
                (decide(gga = seen(goodGga.copy(fixQuality = quality))) as Skip).reason)
        }
        for (quality in listOf(1, 2, 4, 5)) {
            assertEquals("quality $quality", AccuracySource.GST,
                (decide(gga = seen(goodGga.copy(fixQuality = quality))) as Send).accuracySource)
        }
        assertEquals(AccuracySource.GST, (decide(gga = seen(goodGga.copy(fixQuality = null))) as Send).accuracySource)
    }

    @Test
    fun anOldOrUnmatchedGgaIsIgnoredAndTheLocationSatellitesAreUsed() {
        val noFix = NmeaSentenceParser.Gga(0, 0, 99.0)

        assertEquals(Send(5.0, AccuracySource.GST, Quality(9, null, 5.0)),
            decide(gga = seen(noFix, ageMillis = 5_000), locationSatellites = 9))
        assertEquals(Send(5.0, AccuracySource.GST, Quality(9, null, 5.0)),
            decide(gga = seen(noFix, fixTimeOffsetMillis = 3_000), locationSatellites = 9))
    }

    @Test
    fun tooFewSatellitesAreNotSentButAnUnknownCountIs() {
        val few = HeadUnitGnssFixPolicy.MIN_SATELLITES_USED - 1

        assertEquals(SkipReason.FEW_SATELLITES, (decide(gga = seen(goodGga.copy(satellitesUsed = few))) as Skip).reason)
        assertEquals(SkipReason.FEW_SATELLITES, (decide(gga = null, locationSatellites = few) as Skip).reason)
        assertEquals(Send(5.0, AccuracySource.GST, Quality(null, null, 5.0)), decide(gga = null))
        assertEquals(Send(5.0, AccuracySource.GST, Quality(5, 0.7, 5.0)), decide(gga = seen(goodGga.copy(satellitesUsed = 5))))
    }

    @Test
    fun anErrorOverFiftyMetresIsNotSent() {
        assertEquals(SkipReason.ACCURACY, (decide(gst = seen(NmeaSentenceParser.Gst(40.0, 40.0))) as Skip).reason)
        assertEquals(SkipReason.ACCURACY, (decide(locationAccuracyMeters = 60.0, gst = null) as Skip).reason)
        assertEquals(Send(50.0, AccuracySource.GST, Quality(24, 0.7, 50.0)), decide(gst = seen(NmeaSentenceParser.Gst(30.0, 40.0))))
    }
}
