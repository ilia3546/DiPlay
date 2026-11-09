package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import com.shilapi.xcertplay.hud.BydHudRouteState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class) // Lane guidance logs through android.util.Log.
class CarPlayGlanceTest {
    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(id, block)

    @Before
    fun reset() {
        CarPlayGlance.listener = null
        CarPlayGlance.laneListener = null
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @After
    fun clear() = reset()

    @Test
    fun everyObserverAndTheListenerFollowTheSessionUntilTheyLeave() {
        val listened = mutableListOf<Boolean>()
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        CarPlayGlance.listener = { listened += it.connected }
        val stopFirst = CarPlayGlance.observe { first += it.connected }
        val stopSecond = CarPlayGlance.observe { second += it.connected }
        assertEquals("an observer hears the current state at once", listOf(false), first)

        CarPlayGlance.setConnected(true)
        stopFirst()
        CarPlayGlance.setConnected(false)
        stopSecond()
        CarPlayGlance.setConnected(true)

        assertEquals(listOf(false, true), first)
        assertEquals(listOf(false, true, false), second)
        assertEquals(listOf(true, false, true), listened)
    }

    @Test
    fun aConnectionObserverEndsOnTheCurrentLevelWhenChangesArriveOutOfOrder() {
        val posted = mutableListOf<Runnable>()
        val seen = mutableListOf<Boolean>()
        val stop = CarPlayGlance.observeConnection(posted::add) { seen += it }
        posted.removeAt(0).run()
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
        posted.reversed().forEach(Runnable::run)
        stop()
        assertEquals(listOf(false, false, false), seen)
        assertFalse(CarPlayGlance.isConnected)
    }

    @Test
    fun followsTheNextTurnAndTheSong() {
        val seen = mutableListOf<CarPlayGlance.Snapshot>()
        CarPlayGlance.listener = { seen += it }
        CarPlayGlance.setConnected(true)

        // Maneuver 7 is a right turn onto Main Street; the route update makes it the next one.
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Main Street") })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 350); u16List(0x0d, listOf(7)) })
        CarPlayGlance.onFrame(frame(0x5001) {
            group(0) { string(1, "Numb"); string(12, "Linkin Park") }
            group(1) { u8(0, 1) }
        })

        val now = CarPlayGlance.snapshot()
        assertTrue(now.connected)
        assertEquals(2, now.maneuverType)
        assertEquals(350, now.distanceMeters)
        assertEquals("Main Street", now.road)
        assertEquals("Numb — Linkin Park", now.song)
        assertTrue(now.playing)
        assertEquals(now, seen.last())
    }

    @Test
    fun routeEndAndSessionEnd() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 1) })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 90); u16List(0x0d, listOf(1)) })
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Podcast") } })

        // Arrived: no turn any more, the song stays.
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 2) })
        assertNull(CarPlayGlance.snapshot().maneuverType)
        assertEquals("Podcast", CarPlayGlance.snapshot().song)

        CarPlayGlance.setConnected(false)
        assertEquals(CarPlayGlance.Snapshot(), CarPlayGlance.snapshot())
    }

    @Test
    fun expiredGuidanceClearsWithoutAnotherFrameAndNotifiesTheWidget() {
        withRouteClock { advance ->
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 2) })
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(1)) })
            assertEquals(2, CarPlayGlance.snapshot().maneuverType)
            val seen = mutableListOf<CarPlayGlance.Snapshot>()
            CarPlayGlance.listener = { seen += it }
            advance(30_000_000_000L)
            assertNull(CarPlayGlance.snapshot().maneuverType)
            assertNull(seen.single().maneuverType)
            assertTrue(CarPlayGlance.snapshot().connected)
        }
    }

    @Test
    fun aPersistentlyEmptyManeuverListExpiresAfterTheGracePeriod() {
        withRouteClock { advance ->
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 2) })
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(1)) })
            CarPlayGlance.onFrame(frame(0x5201) { u16List(0x0d, emptyList()) })
            advance(2_000_000_000L)
            assertEquals(2, CarPlayGlance.snapshot().maneuverType)
            advance(1_000_000_000L)
            assertNull(CarPlayGlance.snapshot().maneuverType)
        }
    }

    @Test
    fun anEmptySongTitleClearsTheWidgetSong() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Previous song") } })
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "") } })
        assertNull(CarPlayGlance.snapshot().song)
    }

    // Inject the route parser's monotonic clock rather than waiting 30 seconds in each test.
    private fun withRouteClock(test: ((Long) -> Unit) -> Unit) {
        val route = CarPlayGlance.javaClass.getDeclaredField("route").apply { isAccessible = true }
            .get(CarPlayGlance) as BydHudRouteState
        val clock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
        val original = clock.get(route)
        var now = 1_000_000_000L
        try {
            clock.set(route, { now })
            test { now += it }
        } finally {
            clock.set(route, original)
        }
    }

    @Test
    fun otherMessagesChangeNothing() {
        CarPlayGlance.setConnected(true)
        val before = CarPlayGlance.snapshot()
        var calls = 0
        CarPlayGlance.listener = { calls++ }

        CarPlayGlance.onFrame(frame(0x4E09) { u8(1, 1) })

        assertEquals(before, CarPlayGlance.snapshot())
        assertEquals(0, calls)
    }

    @Test
    fun laneGuidanceReachesTheLaneListenerWithoutTouchingTheGlance() {
        CarPlayGlance.setConnected(true)
        val before = CarPlayGlance.snapshot()
        val strips = mutableListOf<List<LaneGuidanceLane>>()
        CarPlayGlance.laneListener = { strips += it.lanes }

        CarPlayGlance.onFrame(frame(0x5204) {
            group(2) { u16(0, 0); u8(1, 0); i16(2, -90) }
            group(2) { u16(0, 1); u8(1, 2); i16(2, 0); i16(3, 90) }
        })
        // The phone clears the strip with an empty payload when the overlap ends.
        CarPlayGlance.onFrame(frame(0x5204) { })

        assertEquals(2, strips.size)
        assertEquals(listOf(0, 1), strips[0].map { it.index })
        assertEquals(listOf(-90), strips[0][0].angles)
        assertEquals(true, strips[0][1].recommended)
        assertEquals(90, strips[0][1].highlightAngle)
        assertTrue(strips[1].isEmpty())
        assertEquals(before, CarPlayGlance.snapshot())
    }

    private fun routeTo(index: Int, type: Int = 2) {
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, index); u8(3, type) })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 300); u16List(0x0d, listOf(index)) })
    }

    private fun threeLanes(event: Int = 0) = CarPlayGlance.onFrame(frame(0x5204) {
        u16(1, event)
        group(2) { u16(0, 0); u8(1, 0); i16(2, -90) }
        group(2) { u16(0, 1); u8(1, 0); i16(2, 0) }
        group(2) { u16(0, 2); u8(1, 2); i16(2, 0); i16(3, 90) }
    })

    private fun oneLane(event: Int) = CarPlayGlance.onFrame(frame(0x5204) {
        u16(1, event)
        group(2) { u16(0, 0); u8(1, 2); i16(2, 0) }
    })

    private fun showLanes(index: Int, showing: Boolean, total: Int? = null) = CarPlayGlance.onFrame(frame(0x5201) {
        u16(0x10, index)
        optionalU16(0x11, total)
        u8(0x12, if (showing) 1 else 0)
    })

    @Test
    fun theSnapshotCarriesTheManeuverIndexAndTheSession() {
        CarPlayGlance.setConnected(true)
        val first = CarPlayGlance.snapshot().session
        routeTo(4)
        assertEquals(4, CarPlayGlance.snapshot().maneuverIndex)
        CarPlayGlance.setConnected(false)
        assertEquals(0L, CarPlayGlance.snapshot().session)
        assertNull(CarPlayGlance.snapshot().maneuverIndex)
        CarPlayGlance.setConnected(true)
        assertEquals(first + 1, CarPlayGlance.snapshot().session)
    }

    @Test
    fun aSessionThatReplacesTheActiveOneIsAnotherSessionWithoutTheOldRoute() {
        CarPlayGlance.sessionStarted()
        val first = CarPlayGlance.snapshot().session
        routeTo(3)
        threeLanes()
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Song") } })
        assertEquals("Song", CarPlayGlance.snapshot().song)

        CarPlayGlance.sessionStarted()
        val replaced = CarPlayGlance.snapshot()
        assertTrue(replaced.connected)
        assertEquals(first + 1, replaced.session)
        assertNull(replaced.maneuverType)
        assertNull(replaced.maneuverIndex)
        assertNull(replaced.lanes)
        assertNull(replaced.song)
    }

    @Test
    fun theFirstSessionKeepsGuidanceThatCameBeforeIt() {
        routeTo(3)
        CarPlayGlance.sessionStarted()
        assertEquals(3, CarPlayGlance.snapshot().maneuverIndex)
    }

    @Test
    fun lanesSentAheadStayHiddenUntilRouteGuidanceShowsTheirEvent() {
        // Apple Maps sends every lane event of the route at its start.
        CarPlayGlance.setConnected(true)
        routeTo(3)
        threeLanes(event = 0)
        oneLane(event = 1)
        assertNull("not showing yet", CarPlayGlance.snapshot().lanes)

        showLanes(index = 1, showing = false, total = 2)
        assertNull("index without showing", CarPlayGlance.snapshot().lanes)

        showLanes(index = 1, showing = true)
        val strip = CarPlayGlance.snapshot().lanes!!
        assertEquals(1, strip.laneGuidanceIndex)
        assertEquals(1, strip.lanes.size)

        routeTo(4)
        assertEquals("a new maneuver keeps the event 0x5201 names", strip, CarPlayGlance.snapshot().lanes)

        showLanes(index = 0, showing = true)
        assertEquals(listOf(0, 1, 2), CarPlayGlance.snapshot().lanes!!.lanes.map { it.index })

        val snapshots = mutableListOf<CarPlayGlance.Snapshot>()
        CarPlayGlance.observe { snapshots += it }()
        assertEquals(CarPlayGlance.snapshot().lanes, snapshots.single().lanes)
    }

    @Test
    fun anEmptyEventARerouteArrivalAndTheSessionEndClearTheLanes() {
        CarPlayGlance.setConnected(true)
        routeTo(3)
        threeLanes()
        showLanes(index = 0, showing = true)
        CarPlayGlance.onFrame(frame(0x5204) { u16(1, 0) })
        assertNull("an empty event", CarPlayGlance.snapshot().lanes)

        threeLanes()
        assertEquals(3, CarPlayGlance.snapshot().lanes!!.lanes.size)
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 5) })
        routeTo(3)
        showLanes(index = 0, showing = true)
        assertNull("rerouting drops the events of the old route", CarPlayGlance.snapshot().lanes)

        threeLanes()
        showLanes(index = 0, showing = true)
        showLanes(index = 0, showing = true, total = 0)
        assertNull("no lane events left", CarPlayGlance.snapshot().lanes)

        threeLanes()
        showLanes(index = 0, showing = true)
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 2); u16(0x10, 0); u8(0x12, 1) })
        routeTo(3)
        assertNull("arrival ignores the lane fields it carries", CarPlayGlance.snapshot().lanes)
        showLanes(index = 0, showing = true)
        assertNull("arrival drops the events", CarPlayGlance.snapshot().lanes)

        threeLanes()
        showLanes(index = 0, showing = true)
        CarPlayGlance.setConnected(false)
        assertNull("the session end", CarPlayGlance.snapshot().lanes)
        CarPlayGlance.setConnected(true)
        routeTo(3)
        threeLanes()
        assertNull("showing does not outlive the session", CarPlayGlance.snapshot().lanes)
    }

    @Test
    fun theLaneLogShowsEachEventAndTheShownIndex() {
        CarPlayGlance.setConnected(true)
        routeTo(3)
        CarPlayGlance.onFrame(frame(0x5204) { })
        ShadowLog.clear()
        CarPlayGlance.onFrame(frame(0x5204) {
            u16(1, 4)
            group(2) { u16(0, 0); u8(1, 2); i16(2, 0) }
        })
        showLanes(index = 4, showing = true, total = 5)
        showLanes(index = 4, showing = true, total = 5)
        val lines = ShadowLog.getLogsForTag("DiPlay-Lane").map { it.msg }
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("0x5204 lanes=1 index=4 shown=- "))
        assertEquals("0x5201 laneIndex=4 total=5 showing=true stored=[4]", lines[1])
    }

    @Test
    fun anEventWithoutAnIndexIsNeverShown() {
        CarPlayGlance.setConnected(true)
        routeTo(3)
        showLanes(index = 0, showing = true)
        CarPlayGlance.onFrame(frame(0x5204) { group(2) { u16(0, 0); u8(1, 2); i16(2, 0) } })
        assertNull(CarPlayGlance.snapshot().lanes)
    }

    @Test
    fun aFullStoreKeepsTheShownEvent() {
        CarPlayGlance.setConnected(true)
        routeTo(3)
        threeLanes(event = 0)
        showLanes(index = 0, showing = true)
        for (event in 1..20) oneLane(event)
        assertEquals(0, CarPlayGlance.snapshot().lanes!!.laneGuidanceIndex)
        showLanes(index = 20, showing = true)
        assertEquals(20, CarPlayGlance.snapshot().lanes!!.laneGuidanceIndex)
        showLanes(index = 1, showing = true)
        assertNull("the oldest events made room", CarPlayGlance.snapshot().lanes)
    }

    @Test
    fun aShownStripWithoutAManeuverIsNotPublished() {
        CarPlayGlance.setConnected(true)
        threeLanes()
        showLanes(index = 0, showing = true)
        assertNull(CarPlayGlance.snapshot().lanes)
        routeTo(3)
        assertEquals(3, CarPlayGlance.snapshot().lanes!!.lanes.size)
    }

    @Test
    fun expiredGuidanceTakesItsLanesAlong() {
        withRouteClock { advance ->
            CarPlayGlance.setConnected(true)
            routeTo(3)
            threeLanes()
            showLanes(index = 0, showing = true)
            advance(30_000_000_000L)
            assertNull(CarPlayGlance.snapshot().lanes)
        }
    }
}
