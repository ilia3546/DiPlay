package com.shilapi.xcertplay.hud

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CarPlayCallsTest {
    private val ringing = CarPlayCallSnapshot(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Mum", number = "+971500000001"))
    private val active = CarPlayCallSnapshot(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 1_000L, "+971500000001"))

    @Before @After fun reset() = CarPlayCalls.publish { CarPlayCallSnapshot() }

    @Test
    fun everyObserverGetsTheCurrentCallAndEachChangeUntilItStops() {
        val first = mutableListOf<CarPlayCallSnapshot>()
        val second = mutableListOf<CarPlayCallSnapshot>()
        val stopFirst = CarPlayCalls.observe { first += it }
        CarPlayCalls.publish { ringing }
        val stopSecond = CarPlayCalls.observe { second += it }
        CarPlayCalls.publish { active }
        stopFirst()
        CarPlayCalls.publish { CarPlayCallSnapshot() }
        stopSecond()
        CarPlayCalls.publish { ringing }

        assertEquals(listOf(CarPlayCallSnapshot(), ringing, active), first)
        assertEquals(listOf(ringing, active, CarPlayCallSnapshot()), second)
        assertEquals(ringing, CarPlayCalls.current())
    }

    @Test
    fun anUnchangedCallIsNotSentAgain() {
        val seen = mutableListOf<CarPlayCallSnapshot>()
        val stop = CarPlayCalls.observe { seen += it }
        CarPlayCalls.publish { ringing }
        CarPlayCalls.publish { ringing }
        stop()

        assertEquals(listOf(CarPlayCallSnapshot(), ringing), seen)
    }
}
