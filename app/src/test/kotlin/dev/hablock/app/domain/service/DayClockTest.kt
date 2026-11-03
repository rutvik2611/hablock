package dev.hablock.app.domain.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayClockTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun clockAt(local: String) = DayClock(
        clock = Clock.fixed(Instant.parse(local), zone),
        zoneOverride = zone,
    )

    @Test
    fun `just before local midnight belongs to the ending gate day`() {
        val clock = clockAt("2026-08-22T21:59:00Z")
        assertEquals("2026-08-22", clock.dayKey())
        assertEquals(Instant.parse("2026-08-21T22:00:00Z"), clock.dayStart())
    }

    @Test
    fun `local midnight starts the next gate day`() {
        val clock = clockAt("2026-08-22T22:00:00Z")
        assertEquals("2026-08-23", clock.dayKey())
        assertEquals(Instant.parse("2026-08-22T22:00:00Z"), clock.dayStart())
    }

    @Test
    fun `the small hours belong to the current gate day`() {
        val clock = clockAt("2026-08-23T01:00:00Z")
        assertEquals("2026-08-23", clock.dayKey())
    }

    @Test
    fun `day window runs from the day start to now`() {
        val now = Instant.parse("2026-08-23T09:30:00Z")
        val clock = clockAt("2026-08-23T09:30:00Z")
        val (from, to) = clock.dayWindow()
        assertEquals(Instant.parse("2026-08-22T22:00:00Z"), from)
        assertEquals(now, to)
    }

    @Test
    fun `checkpoints are noon and five in local time`() {
        // Berlin is UTC+2 in August, so 12:00 local is 10:00Z and 17:00 local is 15:00Z.
        val clock = clockAt("2026-08-23T09:30:00Z")
        val checkpoints = clock.checkpoints()
        assertEquals(2, checkpoints.size)
        assertEquals(Instant.parse("2026-08-23T10:00:00Z"), checkpoints[0].at)
        assertEquals(4_000.0, checkpoints[0].steps)
        assertEquals(Instant.parse("2026-08-23T15:00:00Z"), checkpoints[1].at)
        assertEquals(10_000.0, checkpoints[1].steps)
    }

    @Test
    fun `nothing is due before noon and four thousand is due after it`() {
        val morning = clockAt("2026-08-23T09:00:00Z")
        assertEquals(0.0, morning.requiredSteps(now = morning.now()))
        val afternoon = clockAt("2026-08-23T11:00:00Z") // 13:00 local
        assertEquals(4_000.0, afternoon.requiredSteps(now = afternoon.now()))
        val night = clockAt("2026-08-23T16:00:00Z") // 18:00 local
        assertEquals(10_000.0, night.requiredSteps(now = night.now()))
    }

    @Test
    fun `next checkpoint moves from noon to five during the day`() {
        val morning = clockAt("2026-08-23T09:00:00Z")
        assertEquals(4_000.0, morning.nextCheckpoint(now = morning.now())!!.steps)
        val afternoon = clockAt("2026-08-23T11:00:00Z")
        assertEquals(10_000.0, afternoon.nextCheckpoint(now = afternoon.now())!!.steps)
        val evening = clockAt("2026-08-23T16:00:00Z")
        assertEquals(null, evening.nextCheckpoint(now = evening.now()))
    }

    @Test
    fun `next reset is always strictly in the future`() {
        var instant = Instant.parse("2026-08-22T01:00:00Z")
        repeat(48) {
            val clock = DayClock(Clock.fixed(instant, zone), zone)
            assertTrue(clock.nextReset() > instant, "nextReset not in the future at $instant")
            instant = instant.plusSeconds(3600)
        }
    }

    @Test
    fun `next reset from the reset instant is the following day`() {
        val clock = clockAt("2026-08-22T22:00:00Z")
        assertEquals(Instant.parse("2026-08-23T22:00:00Z"), clock.nextReset())
    }
}
