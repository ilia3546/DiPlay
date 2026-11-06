package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxCarDataTest {
    @Test fun theOutsideTemperatureIsReadOnlyWithoutATimeout() {
        assertEquals(6.6f, outsideTemperature(6.6f, 0)!!, 0f)
        assertNull(outsideTemperature(6.6f, 1))
        assertNull(outsideTemperature(6.6f, true))
        assertNull(outsideTemperature(6.6f, null))
        assertNull(outsideTemperature(null, 0))
        assertNull(outsideTemperature(Float.NaN, 0))
        assertNull(outsideTemperature(-273f, 0))
        assertEquals("—", DashboardFormat.temperature(outsideTemperature(6.6f, 1)))
    }

    @Test fun onlyParameterlessPublicAttributeGettersOfTripFieldsAreRead() {
        assertTrue(EcarxTripFields.candidate("TravelManager", "getPA_DstTrvldAct", 0))
        assertTrue(EcarxTripFields.candidate("TravelManager", "getPA_HealthOfEngOil", 0))
        assertTrue(EcarxTripFields.candidate("VfmiscManager", "getPA_TripAverageConsumption100", 0))
        assertTrue(EcarxTripFields.candidate("VfmiscManager", "getPA_Total_Traveled_Distance", 0))
        assertTrue(EcarxTripFields.candidate("SystemsettingManager", "getPA_SysSetFuCnsUnit", 0))
        assertFalse(EcarxTripFields.candidate("VfmiscManager", "getPA_FuelRemainPerc", 0))
        assertFalse(EcarxTripFields.candidate("TravelManager", "getPA_DstTrvldAct", 1))
        for (writer in listOf("setPA_DstTrvldAct", "resetTrip", "clearTripData", "resetPA_TripCom_RstCurrTrip",
                "setPA_TripCom_RstCurrTrip", "clearPA_Travel")) {
            assertFalse(writer, EcarxTripFields.candidate("TravelManager", writer, 0))
        }
        assertFalse(EcarxTripFields.candidate("TravelManager", "getTravelData", 0))
    }

    @Test fun onlyChangedTripValuesAreLoggedAgain() {
        val values = ChangedValues()
        val first = mapOf("TravelManager.DstTrvldAct" to "0 status=0", "VfmiscManager.TripAverageConsumption50" to "0 status=0")
        assertEquals(first, values.changes(first))
        assertEquals(emptyMap<String, String>(), values.changes(first))
        val next = first + ("TravelManager.DstTrvldAct" to "12 status=0")
        assertEquals(mapOf("TravelManager.DstTrvldAct" to "12 status=0"), values.changes(next))
        assertEquals(emptyMap<String, String>(), values.changes(next))
    }
}
