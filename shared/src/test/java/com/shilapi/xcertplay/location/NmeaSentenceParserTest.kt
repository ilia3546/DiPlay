package com.shilapi.xcertplay.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NmeaSentenceParserTest {
    @Test
    fun readsTheErrorEstimateFromGst() {
        val gst = NmeaSentenceParser.parse(sentence("GPGST,123519.00,7.1,9.2,6.5,32.0,3.0,4.0,5.5"))

        assertEquals(NmeaSentenceParser.Gst(3.0, 4.0, 45_319_000L), gst)
        assertEquals(5.0, (gst as NmeaSentenceParser.Gst).horizontalErrorMeters, 1e-9)
    }

    @Test
    fun readsQualitySatellitesAndHdopFromGgaOfAnyTalker() {
        val expected = NmeaSentenceParser.Gga(fixQuality = 1, satellitesUsed = 8, hdop = 0.9, utcTimeOfDayMillis = 45_319_000L)
        for (talker in listOf("GP", "GN", "GL", "GA")) {
            assertEquals(talker, expected,
                NmeaSentenceParser.parse(sentence("${talker}GGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")))
        }
        assertEquals(NmeaSentenceParser.Gst(1.5, 2.0, 45_319_000L), NmeaSentenceParser.parse(sentence("GNGST,123519,1,2,1,0,1.5,2.0,3")))
    }

    @Test
    fun acceptsALineEndAndALowerCaseChecksum() {
        val line = sentence("GPGGA,123519,4807.038,N,01131.000,E,1,12,0.6,545.4,M,46.9,M,,")

        assertEquals(NmeaSentenceParser.Gga(1, 12, 0.6, 45_319_000L), NmeaSentenceParser.parse("$line\r\n"))
        assertEquals(NmeaSentenceParser.Gga(1, 12, 0.6, 45_319_000L), NmeaSentenceParser.parse(line.lowercaseChecksum()))
    }

    @Test
    fun rejectsAWrongOrMissingChecksum() {
        val body = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        val good = sentence(body)
        val wrong = good.dropLast(2) + if (good.endsWith("00")) "01" else "00"

        assertNull(NmeaSentenceParser.parse(wrong))
        assertNull(NmeaSentenceParser.parse("\$$body"))
        assertNull(NmeaSentenceParser.parse("\$$body*"))
        assertNull(NmeaSentenceParser.parse("\$$body*G1"))
        assertNull(NmeaSentenceParser.parse(good.drop(1)))
    }

    @Test
    fun leavesEmptyOrBrokenGgaFieldsUnknown() {
        assertEquals(NmeaSentenceParser.Gga(0, null, null), NmeaSentenceParser.parse(sentence("GPGGA,,,,,,0,,,,,,,,")))
        assertEquals(NmeaSentenceParser.Gga(null, null, null, 45_319_000L),
            NmeaSentenceParser.parse(sentence("GPGGA,123519,4807.038,N,01131.000,E,x,-3,NaN,545.4,M,46.9,M,,")))
        assertNull(NmeaSentenceParser.parse(sentence("GPGGA,123519,4807.038,N")))
    }

    @Test
    fun ignoresAGstWithoutBothErrors() {
        assertNull(NmeaSentenceParser.parse(sentence("GPGST,123519.00,7.1,9.2,6.5,32.0,,4.0,5.5")))
        assertNull(NmeaSentenceParser.parse(sentence("GPGST,123519.00,7.1,9.2,6.5,32.0,3.0,abc,5.5")))
        assertNull(NmeaSentenceParser.parse(sentence("GPGST,123519.00,7.1,9.2,6.5,32.0,-3.0,4.0,5.5")))
        assertNull(NmeaSentenceParser.parse(sentence("GPGST,123519.00,7.1,9.2,6.5,32.0,0.0,4.0,5.5")))
        assertNull(NmeaSentenceParser.parse(sentence("GPGST,123519.00,7.1,9.2,6.5,32.0,3.0,0,5.5")))
        assertNull(NmeaSentenceParser.parse(sentence("GPGST,123519.00,7.1")))
    }

    @Test
    fun readsEverySentenceOfABatch() {
        val gga = sentence("GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        val rmc = sentence("GNRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")
        val gst = sentence("GNGST,123519.00,7.1,9.2,6.5,32.0,3.0,4.0,5.5")
        val broken = sentence("GPGST,123519.00,7.1,9.2,6.5,32.0,3.0,4.0,5.5").let { it.dropLast(1) + if (it.last() == '0') "1" else "0" }
        val expected = listOf(NmeaSentenceParser.Gga(1, 8, 0.9, 45_319_000L), NmeaSentenceParser.Gst(3.0, 4.0, 45_319_000L))

        assertEquals(expected, NmeaSentenceParser.parseAll("$gga\r\n$rmc\r\n$gst\r\n"))
        assertEquals(expected, NmeaSentenceParser.parseAll("$gga$rmc$gst"))
        assertEquals(expected, NmeaSentenceParser.parseAll("$gga\n$broken\n$gst"))
        assertEquals(emptyList<NmeaSentenceParser.Sentence>(), NmeaSentenceParser.parseAll("\r\n"))
    }

    @Test
    fun readsTheUtcTimeOfDayOfGgaAndGst() {
        fun ggaTime(time: String) =
            (NmeaSentenceParser.parse(sentence("GPGGA,$time,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")) as NmeaSentenceParser.Gga)
                .utcTimeOfDayMillis
        fun gstTime(time: String) =
            (NmeaSentenceParser.parse(sentence("GPGST,$time,7.1,9.2,6.5,32.0,3.0,4.0,5.5")) as NmeaSentenceParser.Gst).utcTimeOfDayMillis

        assertEquals(45_319_000L, ggaTime("123519"))
        assertEquals(45_319_250L, ggaTime("123519.25"))
        assertEquals(86_399_800L, gstTime("235959.800"))
        assertEquals(100L, gstTime("000000.1"))
        for (broken in listOf("", "1235", "12351x.00", "243519.00", "126019.00", "123560.00", "123519.", "123519.x")) {
            assertNull(broken, ggaTime(broken))
            assertNull(broken, gstTime(broken))
        }
    }

    @Test
    fun ignoresOtherSentences() {
        assertNull(NmeaSentenceParser.parse(sentence("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")))
        assertNull(NmeaSentenceParser.parse(sentence("PASCD,1.000,C,D,0,1,0.00,1.500")))
        assertNull(NmeaSentenceParser.parse(""))
    }

    private fun sentence(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return "\$$body*${"%02X".format(value)}"
    }

    private fun String.lowercaseChecksum(): String = substringBeforeLast('*') + "*" + substringAfterLast('*').lowercase()
}
