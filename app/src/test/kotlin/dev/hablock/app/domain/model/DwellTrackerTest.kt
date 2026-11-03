package dev.hablock.app.domain.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DwellTrackerTest {

    private val day = "2026-08-23"
    private val start: Instant = Instant.parse("2026-08-23T09:00:00Z")
    private val cap = 6 * 60_000L
    private val rule = LocationRule(enabled = true, requiredMinutes = 30, places = listOf(place()))
    private fun place() = TrackedPlace("p1", "Place", 40.0, -73.0, 150.0)

    @Test
    fun `samples inside a place accumulate the gap between them`() {
        var state = DwellTracker.apply(DwellDayState(day), day, start, inside = false, maxStepMillis = cap)
        state = DwellTracker.apply(state, day, start.plusSeconds(300), inside = true, maxStepMillis = cap)
        state = DwellTracker.apply(state, day, start.plusSeconds(600), inside = true, maxStepMillis = cap)

        // First inside sample credits the 5 minutes since the previous sample.
        assertEquals(10 * 60_000L, state.accumulatedMillis)
        assertTrue(state.lastInside)
    }

    @Test
    fun `samples outside add nothing`() {
        var state = DwellTracker.apply(DwellDayState(day), day, start, inside = false, maxStepMillis = cap)
        state = DwellTracker.apply(state, day, start.plusSeconds(300), inside = true, maxStepMillis = cap)
        state = DwellTracker.apply(state, day, start.plusSeconds(600), inside = false, maxStepMillis = cap)

        // Only the middle gap was spent inside; leaving earns nothing.
        assertEquals(5 * 60_000L, state.accumulatedMillis)
        assertFalse(state.lastInside)
    }

    @Test
    fun `a long sleep cannot fake a workout`() {
        var state = DwellTracker.apply(DwellDayState(day), day, start, inside = true, maxStepMillis = cap)
        state = DwellTracker.apply(state, day, start.plusSeconds(8 * 3600), inside = true, maxStepMillis = cap)

        assertEquals(cap, state.accumulatedMillis, "credit is capped at one sampling window")
    }

    @Test
    fun `a new day starts from zero`() {
        var state = DwellTracker.apply(DwellDayState(day), day, start, inside = true, maxStepMillis = cap)
        state = DwellTracker.apply(state, day, start.plusSeconds(300), inside = true, maxStepMillis = cap)
        assertEquals(5 * 60_000L, state.accumulatedMillis)

        val next = DwellTracker.apply(state, "2026-08-24", start.plusSeconds(86_400), inside = true, maxStepMillis = cap)
        assertEquals(0L, next.accumulatedMillis)
        assertEquals("2026-08-24", next.dayKey)
    }

    @Test
    fun `fifteen minutes twice is thirty minutes for the rule`() {
        // A 15-minute sampling window, so each gap is credited in full.
        val wide = 20 * 60_000L
        var state = DwellTracker.apply(DwellDayState(day), day, start, inside = true, maxStepMillis = wide)
        state = DwellTracker.apply(state, day, start.plusSeconds(900), inside = true, maxStepMillis = wide)
        assertFalse(DwellTracker.isSatisfied(state, rule))
        assertEquals(15, DwellTracker.remainingMinutes(state, rule))

        state = DwellTracker.apply(state, day, start.plusSeconds(1800), inside = true, maxStepMillis = wide)
        assertTrue(DwellTracker.isSatisfied(state, rule))
        assertEquals(0, DwellTracker.remainingMinutes(state, rule))
    }

    @Test
    fun `radius decides whether a fix counts as inside`() {
        val p = TrackedPlace("p1", "Place", 40.0000, -73.0000, radiusMeters = 150.0)
        // ~0.001 degrees of latitude is ~111 m.
        assertTrue(DwellTracker.insideAny(listOf(p), 40.0010, -73.0000))
        assertFalse(DwellTracker.insideAny(listOf(p), 40.0030, -73.0000))
        assertTrue(DwellTracker.distanceMeters(40.0, -73.0, 41.0, -73.0) in 110_000.0..112_000.0)
    }

    @Test
    fun `a home place cancels a gym it overlaps`() {
        val home = TrackedPlace("home", "Home", 25.18928, 55.26410, radiusMeters = 80.0, excluded = true)
        val gym = TrackedPlace("gym", "Gym", 25.189255, 55.264181, radiusMeters = 150.0)
        val places = listOf(home, gym)

        // Standing at home: inside both, and home wins.
        assertNull(DwellTracker.countedPlace(places, 25.18928, 55.26410))
        // Far away from both: nothing counted.
        assertNull(DwellTracker.countedPlace(places, 25.18157, 55.27151))
    }

    @Test
    fun `a counted place still earns when it is away from home`() {
        val home = TrackedPlace("home", "Home", 25.18928, 55.26410, radiusMeters = 60.0, excluded = true)
        val gym = TrackedPlace("gym", "Fitness First", 25.18157, 55.27151, radiusMeters = 120.0)
        val matched = DwellTracker.countedPlace(listOf(home, gym), 25.18157, 55.27151)
        assertNotNull(matched)
        assertEquals("gym", matched!!.id)
    }
}
