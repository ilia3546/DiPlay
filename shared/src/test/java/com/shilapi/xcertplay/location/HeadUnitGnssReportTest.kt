package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.AccuracySource
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Quality
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Send
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Skip
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitGnssReportTest {
    @Test
    fun summarisesAMinuteOfDecisions() {
        val report = HeadUnitGnssReport()

        assertFalse(report.summaryDue(1_000))
        report.record(1_000, Send(5.0, AccuracySource.GST, Quality(20, 0.6, 5.0)), 0.7, 10.0, 120)
        report.record(2_000, Send(10.0, AccuracySource.FALLBACK, Quality(24, 0.8, null)), 0.9, 12.0, null)
        report.record(3_000, Skip(SkipReason.STALE, Quality(23, 1.0, 9.0)), 50.0, 99.0, 2_400)
        report.record(4_000, Skip(SkipReason.NOT_SATELLITE, Quality(null, null, null)), null, null, -80)
        assertFalse(report.summaryDue(60_999))
        report.record(61_000, Skip(SkipReason.FEW_SATELLITES, Quality(4, 2.0, 3.0)), 0.8, 11.0, 140)
        assertTrue(report.summaryDue(61_000))

        assertEquals(
            "Head unit GPS: last 60 s fixes=58 sent=2 skipped=[stale=1 noFix=0 notSatellite=1 fewSatellites=1 accuracy=0] " +
                "accuracyFrom=[gst=1 fallback=1] gstError=[min=3.0 median=5.0 max=9.0 m] satellites=[min=4 median=22] " +
                "hdop=[median=0.9] locationAccuracy=[median=0.8 m] gnssSpeed=[median=39.6 km/h] " +
                "gstFixTimeOffset=[median=130 ms] nmeaMessages=420",
            report.summary(61_000, fixes = 58, nmeaMessages = 420),
        )
    }

    @Test
    fun startsANewMinuteAfterTheSummary() {
        val report = HeadUnitGnssReport()
        report.record(0, Skip(SkipReason.STALE, Quality(null, null, null)), null, null, 10)
        report.record(60_000, Skip(SkipReason.STALE, Quality(null, null, null)), null, null, null)
        report.summary(60_000, fixes = 0, nmeaMessages = 0)

        report.record(119_999, Skip(SkipReason.NO_FIX, Quality(null, null, null)), null, null, null)
        assertFalse(report.summaryDue(119_999))
        assertEquals(
            "Head unit GPS: last 59 s fixes=0 sent=0 skipped=[stale=0 noFix=1 notSatellite=0 fewSatellites=0 accuracy=0] " +
                "accuracyFrom=[gst=0 fallback=0] gstError=none satellites=none hdop=none locationAccuracy=none " +
                "gnssSpeed=none gstFixTimeOffset=none nmeaMessages=0",
            report.summary(119_999, fixes = 0, nmeaMessages = 0),
        )
    }
}
