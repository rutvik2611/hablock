package dev.hablock.app.domain.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
