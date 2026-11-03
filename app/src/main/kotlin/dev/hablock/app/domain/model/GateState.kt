package dev.hablock.app.domain.model

import java.time.Instant

data class ConditionProgress(
    val condition: Condition,
    val current: Double,
    val required: Double,
) {
    val met: Boolean get() = current >= required
}

sealed interface GateState {
    val progress: List<ConditionProgress>
    val metCount: Int get() = progress.count { it.met }

    data class Open(
        override val progress: List<ConditionProgress>,
        val thresholdN: Int,
    ) : GateState

    data class Locked(
        override val progress: List<ConditionProgress>,
        val thresholdN: Int,
    ) : GateState

    /**
     * Before the next checkpoint with a goal still reachable: apps stay open and the card warns what
     * is still owed. Enforcement must treat this as "not blocked".
     */
    data class Warn(
        override val progress: List<ConditionProgress>,
        val thresholdN: Int,
        val deadline: Instant,
        val nextSteps: Double,
    ) : GateState

    data class SessionActive(
        override val progress: List<ConditionProgress>,
        val endsAt: Instant,
    ) : GateState
}
