package dev.hablock.app.domain.model

import java.time.Instant

/**
 * A step target that must be met by [at]. Once the instant passes, the shortfall is binding: the gate
 * stays locked until the running total reaches [steps], then reopens on its own.
 */
data class StepCheckpoint(val at: Instant, val steps: Double)
