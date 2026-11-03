package dev.hablock.app.domain.service

import dev.hablock.app.domain.model.Block
import dev.hablock.app.domain.model.BlockDayState
import dev.hablock.app.domain.model.Condition
import dev.hablock.app.domain.model.ConditionProgress
import dev.hablock.app.domain.model.GateState
import dev.hablock.app.domain.model.MetricSnapshot
import dev.hablock.app.domain.model.StepCheckpoint
import java.time.Instant

/**
 * Pure checkpointed gate.
 *
 * With no checkpoints this is the classic N-of-M gate: an unmet goal locks immediately.
 *
 * With checkpoints (today: 4,000 by 12:00, 10,000 by 17:00) the day is open until the first one
 * comes due. Once a checkpoint has passed it is binding: falling short of *that* target locks the
 * gate until the target is reached, and the gate reopens by itself the moment the metric catches up.
 * Reaching an earlier checkpoint never earns a later one — 10,000 by 17:00 is still required.
 */
class GateEvaluator {

    fun evaluate(
        block: Block,
        dayState: BlockDayState?,
        snapshot: MetricSnapshot,
        now: Instant,
        checkpoints: List<StepCheckpoint> = emptyList(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): GateState {
        // A place rule has its own deadline hour, so the step checkpoints do not apply to it.
        if (block.conditions.any { it is Condition.PlaceVisit }) {
            return evaluatePlaces(block, dayState, snapshot, now, zone)
        }
        val threshold = effectiveThreshold(block)

        if (block.conditions.isEmpty()) {
            val session = dayState?.activeSession
            if (session != null && now.toEpochMilli() < session.endsAtMillis) {
                return GateState.SessionActive(emptyList(), Instant.ofEpochMilli(session.endsAtMillis))
            }
            return GateState.Locked(emptyList(), block.thresholdN)
        }

        // No checkpoints: classic behaviour, the base goal applies all day.
        if (checkpoints.isEmpty()) {
            val progress = progressOf(block, dayState, snapshot, null)
            sessionIfActive(dayState, now, progress)?.let { return it }
            return if (metCount(block, dayState, snapshot, null) >= threshold) {
                GateState.Open(progress, threshold)
            } else {
                GateState.Locked(progress, threshold)
            }
        }

        val due = checkpoints.lastOrNull { !now.isBefore(it.at) }
        val next = checkpoints.firstOrNull { now.isBefore(it.at) }
        val dueMet = due == null || metCount(block, dayState, snapshot, due.steps) >= threshold

        // While a due checkpoint is failing, show progress against it; otherwise look forward.
        val shownTarget = if (!dueMet) due?.steps else (next?.steps ?: due?.steps)
        val progress = progressOf(block, dayState, snapshot, shownTarget)

        sessionIfActive(dayState, now, progress)?.let { return it }

        return when {
            !dueMet -> GateState.Locked(progress, threshold)
            next == null -> GateState.Open(progress, threshold)
            else -> GateState.Warn(progress, threshold, next.at, next.steps)
        }
    }

    /**
     * Place-shaped blocks: open all day, then binding from each place's own [Condition.PlaceVisit.deadlineHour]
     * until the required minutes have accumulated. Meeting steps never satisfies a place rule, and vice versa.
     */
    private fun evaluatePlaces(
        block: Block,
        dayState: BlockDayState?,
        snapshot: MetricSnapshot,
        now: Instant,
        zone: java.time.ZoneId,
    ): GateState {
        val threshold = effectiveThreshold(block)
        val progress = block.conditions.map { condition ->
            ConditionProgress(
                condition = condition,
                current = snapshot.valueOf(condition.id),
                required = condition.goal,
            )
        }
        sessionIfActive(dayState, now, progress)?.let { return it }

        val localHour = now.atZone(zone).hour
        val missedPlace = block.conditions
            .filterIsInstance<Condition.PlaceVisit>()
            .firstOrNull { localHour >= it.deadlineHour && snapshot.valueOf(it.id) < it.goal }
        if (missedPlace != null) return GateState.Locked(progress, threshold)

        val others = block.conditions.filterNot { it is Condition.PlaceVisit }
        val met = others.count { snapshot.valueOf(it.id) >= it.goal }
        if (others.isNotEmpty() && met < threshold) return GateState.Locked(progress, threshold)

        return GateState.Open(progress, threshold)
    }

    fun effectiveThreshold(block: Block): Int =
        if (block.conditions.isEmpty()) block.thresholdN else block.thresholdN.coerceIn(1, block.conditions.size)

    private fun sessionIfActive(
        dayState: BlockDayState?,
        now: Instant,
        progress: List<ConditionProgress>,
    ): GateState? {
        val session = dayState?.activeSession ?: return null
        if (now.toEpochMilli() >= session.endsAtMillis) return null
        return GateState.SessionActive(progress, Instant.ofEpochMilli(session.endsAtMillis))
    }

    /** How many conditions meet [stepsTarget] (null = each condition's own goal). */
    private fun metCount(
        block: Block,
        dayState: BlockDayState?,
        snapshot: MetricSnapshot,
        stepsTarget: Double?,
    ): Int = block.conditions.count { condition ->
        val required = when {
            condition is Condition.Steps && stepsTarget != null -> stepsTarget
            else -> dayState?.requiredNow?.get(condition.id) ?: condition.goal
        }
        snapshot.valueOf(condition.id) >= required
    }

    private fun progressOf(
        block: Block,
        dayState: BlockDayState?,
        snapshot: MetricSnapshot,
        stepsTarget: Double?,
    ): List<ConditionProgress> = block.conditions.map { condition ->
        val required = when {
            condition is Condition.Steps && stepsTarget != null -> stepsTarget
            else -> dayState?.requiredNow?.get(condition.id) ?: condition.goal
        }
        ConditionProgress(
            condition = condition,
            current = snapshot.valueOf(condition.id),
            required = required,
        )
    }
}
