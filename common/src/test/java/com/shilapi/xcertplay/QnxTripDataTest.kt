package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QnxTripDataTest {
    private val a001 = listOf(122, 55, 0, 0, 167, 3, 0, 0, 97, 0, 0, 0, 255, 255, 0, 0, 12, 0, 44, 0, 130, 0, 8, 0,
        167, 3, 0, 0, 4, 0, 16, 0, 159, 0, 3, 0, 97, 0, 0, 0, 0, 0, 0, 0, 139, 1, 0, 0, 0, 127, 0, 0, 0, 0)

    private fun pps(data: List<Int>) = "cat /tmp/pps/spi_service/a001; echo __DIPLAY_\"\"DONE__\r\n[n]@a001\r\n" +
        "data:json:{\"len\":${data.size},\"data\":[${data.joinToString(",")}]}\r\n"

    @Test fun theStockClustersTripIsRead() {
        assertEquals(DashboardTrip(distanceKm = 9.7, drivingMinutes = 4 * 60 + 16, litresPer100Km = 15.9),
            QnxTripData.parse(pps(a001)))
    }

    @Test fun notAvailableFieldsAreUnknown() {
        val unavailable = a001.toMutableList().apply {
            for (i in 36..39) this[i] = 255
            this[28] = 255; this[29] = 255
            this[32] = 255; this[33] = 255
        }
        assertEquals(DashboardTrip(), QnxTripData.parse(pps(unavailable)))
        val noMinutes = a001.toMutableList().apply { this[30] = 255 }
        assertNull(QnxTripData.parse(pps(noMinutes))?.drivingMinutes)
    }

    @Test fun anAnswerWithoutTheObjectIsNoReading() {
        assertNull(QnxTripData.parse("cat: /tmp/pps/spi_service/a001: No such file or directory\r\n"))
        assertNull(QnxTripData.parse(pps(a001.take(20))))
    }
}
