package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayModesSummaryTest {
    @Test
    fun namesTheScreenAndMainAudioAndKeepsEveryValue() {
        val params = mapOf(
            "resources" to listOf(
                mapOf("resourceID" to 1L, "entity" to 1L),
                mapOf("resourceID" to 2L, "entity" to 2L),
            ),
            "appStates" to listOf(
                mapOf("appStateID" to 1L, "speechMode" to -1L, "entity" to 0L),
                mapOf("appStateID" to 2L, "state" to true),
                mapOf("appStateID" to 3L, "state" to false),
            ),
        )

        assertEquals(
            "resources=[screen{entity=1},mainAudio{entity=2}] " +
                "appStates=[1{entity=0,speechMode=-1},2{state=true},3{state=false}]",
            AirPlayModesSummary.describe(params),
        )
    }

    @Test
    fun writesNoFreeTextOrNestedValues() {
        val params = mapOf(
            "resources" to listOf(mapOf("resourceID" to 7L, "note" to "two words", "kind" to "take", "more" to listOf(1))),
        )

        assertEquals(
            "resources=[7{kind=take,more=?,note=?}] appStates=none",
            AirPlayModesSummary.describe(params),
        )
    }
}
