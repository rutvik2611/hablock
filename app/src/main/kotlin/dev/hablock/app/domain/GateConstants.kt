package dev.hablock.app.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

object GateConstants {
    const val DAY_RESET_HOUR = 0

    /** Local hour/minutes at which unmet goals close the gate for the rest of the day. */
    const val DEADLINE_HOUR = 17
    const val DEADLINE_MINUTE = 0

    /**
     * Deadline mode: the gate is open until [DEADLINE_HOUR] regardless of progress, closes when the
     * deadline passes with a goal unmet, and reopens for the rest of the day as soon as it is met.
     * Sessions and the ratchet do not apply in this mode, so an earned day is not spent in 30-minute
     * slices. When false, the classic session/ratchet gate runs unchanged.
     */
    const val DEADLINE_MODE = true
    val SESSION_DURATION: Duration = 30.minutes
    val RELINQUISH_COOLDOWN: Duration = 3.days
    const val INCREMENT_MIN = 0.10f
    const val INCREMENT_MAX = 0.50f
    const val INCREMENT_STEP = 0.05f

    const val ACTION_SESSION_EXPIRED = "dev.hablock.app.action.SESSION_EXPIRED"
    const val ACTION_DAY_RESET = "dev.hablock.app.action.DAY_RESET"
    const val ACTION_DEADLINE_REACHED = "dev.hablock.app.action.DEADLINE_REACHED"
    const val ACTION_RELINQUISH_READY = "dev.hablock.app.action.RELINQUISH_READY"
    const val EXTRA_BLOCK_ID = "block_id"
    const val EXTRA_PACKAGE_NAME = "package_name"
}
