package dev.hablock.app.domain.service

import dev.hablock.app.domain.GateConstants
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import dev.hablock.app.domain.model.StepCheckpoint
import java.time.ZonedDateTime

/** The Gate day runs from DAY_RESET_HOUR to DAY_RESET_HOUR in local time. */
class DayClock(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneOverride: ZoneId? = null,
) {
    // Resolved per call so a timezone change on the device takes effect without a new instance.
    private val zone: ZoneId get() = zoneOverride ?: ZoneId.systemDefault()

    /** The zone deadlines are expressed in, so callers can judge a local hour without guessing. */
    val zoneId: ZoneId get() = zone

    fun now(): Instant = clock.instant()

    fun dayStart(now: Instant = now()): Instant {
        val zoned = now.atZone(zone)
        val date = if (zoned.hour < GateConstants.DAY_RESET_HOUR) zoned.toLocalDate().minusDays(1) else zoned.toLocalDate()
        return resetAt(date)
    }

    fun dayKey(now: Instant = now()): String =
        dayStart(now).atZone(zone).toLocalDate().toString()

    fun dayWindow(now: Instant = now()): Pair<Instant, Instant> = dayStart(now) to now

    fun nextReset(now: Instant = now()): Instant =
        resetAt(dayStart(now).atZone(zone).toLocalDate().plusDays(1))

    /** Today at [hour]:00 local; used by clock-based rules such as the location deadline. */
    fun todayAt(now: Instant = now(), hour: Int): Instant =
        ZonedDateTime.of(dayStart(now).atZone(zone).toLocalDate(), java.time.LocalTime.of(hour, 0), zone).toInstant()

    /** Today's checkpoints in local time, earliest first. */
    fun checkpoints(now: Instant = now()): List<StepCheckpoint> {
        val date = dayStart(now).atZone(zone).toLocalDate()
        return GateConstants.CHECKPOINTS.map { StepCheckpoint(at(date, it.hour, it.minute), it.steps) }
    }

    fun passedCheckpoints(now: Instant = now()): List<StepCheckpoint> =
        checkpoints(now).filter { !now.isBefore(it.at) }

    fun nextCheckpoint(now: Instant = now()): StepCheckpoint? =
        checkpoints(now).firstOrNull { now.isBefore(it.at) }

    /** Step target already due today, or 0.0 before the first checkpoint. */
    fun requiredSteps(now: Instant = now()): Double = passedCheckpoints(now).lastOrNull()?.steps ?: 0.0

    private fun at(date: LocalDate, hour: Int, minute: Int): Instant =
        ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), zone).toInstant()

    private fun resetAt(date: LocalDate): Instant =
        ZonedDateTime.of(date, java.time.LocalTime.of(GateConstants.DAY_RESET_HOUR, 0), zone).toInstant()
}
