package dev.hablock.app.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

object GateConstants {
    const val DAY_RESET_HOUR = 0

    /** One local-time step checkpoint: at [hour]:[minute] the running total must have reached [steps]. */
    data class CheckpointRule(val hour: Int, val minute: Int, val steps: Double)

    /** Miss either checkpoint and the gate closes until that target is reached. */
    val CHECKPOINTS = listOf(
        CheckpointRule(12, 0, 4_000.0),
        CheckpointRule(17, 0, 10_000.0),
    )

    /**
     * Checkpoint mode: the gate is open until the first checkpoint, a missed checkpoint closes it,
     * and reaching that checkpoint's target reopens it automatically for the rest of the day.
     * Sessions and the ratchet do not apply, so an earned day is not spent in 30-minute slices.
     * When false, the classic session/ratchet gate runs unchanged.
     */
    const val CHECKPOINT_MODE = true

    /** How often a closed gate re-reads Health Connect to notice that the target was reached. */
    val RECHECK_INTERVAL: Duration = 5.minutes

    /** How often the tracked place sampler reads location while a location requirement is configured. */
    val LOCATION_SAMPLE_INTERVAL: Duration = 5.minutes

    /** A single sample can credit at most this much, so a sleeping phone cannot fake a workout. */
    const val LOCATION_MAX_CREDIT_MILLIS: Long = 6 * 60_000L

    /**
     * Presence alone never earns gym credit: a phone on the sofa sits inside a tower gym's fence just as
     * happily as one in the gym. A window only counts when it also shows movement (steps) or a workout.
     */
    const val MOVEMENT_MIN_STEPS: Double = 40.0
    val MOVEMENT_WINDOW: Duration = 10.minutes

    /** Reminder leads before each checkpoint: "X steps left by 12:00" at each of these. */
    val CHECKPOINT_WARNING_LEADS: List<Duration> = listOf(60.minutes, 30.minutes, 10.minutes)
    val SESSION_DURATION: Duration = 30.minutes
    val RELINQUISH_COOLDOWN: Duration = 3.days
    const val INCREMENT_MIN = 0.10f
    const val INCREMENT_MAX = 0.50f
    const val INCREMENT_STEP = 0.05f

    const val ACTION_SESSION_EXPIRED = "dev.hablock.app.action.SESSION_EXPIRED"
    const val ACTION_DAY_RESET = "dev.hablock.app.action.DAY_RESET"
    const val ACTION_CHECKPOINT_REACHED = "dev.hablock.app.action.CHECKPOINT_REACHED"
    const val ACTION_CHECKPOINT_WARNING = "dev.hablock.app.action.CHECKPOINT_WARNING"
    const val ACTION_LOCATION_SAMPLE = "dev.hablock.app.action.GYM_SAMPLE"
    const val ACTION_RECHECK = "dev.hablock.app.action.RECHECK"
    const val EXTRA_CHECKPOINT_STEPS = "checkpoint_steps"
    const val ACTION_RELINQUISH_READY = "dev.hablock.app.action.RELINQUISH_READY"
    const val EXTRA_BLOCK_ID = "block_id"
    const val EXTRA_PACKAGE_NAME = "package_name"
}
