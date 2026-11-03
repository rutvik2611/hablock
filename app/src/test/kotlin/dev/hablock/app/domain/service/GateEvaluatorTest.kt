package dev.hablock.app.domain.service

import dev.hablock.app.domain.model.BlockDayState
import dev.hablock.app.domain.model.Condition
import dev.hablock.app.domain.model.GateState
import dev.hablock.app.domain.model.Session
import dev.hablock.app.domain.model.StepCheckpoint
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val EPS = 1e-3

class GateEvaluatorTest {

    private val evaluator = GateEvaluator()
    private val now: Instant = Instant.parse("2026-08-23T10:00:00Z")

    private val conditions = listOf(
        Condition.Steps("steps", 10_000.0),
        Condition.Meditation("meditation", 10.0),
        Condition.Exercise("workout", 60.0),
    )

    @Test
    fun `n of m matrix over every threshold and met count`() {
        val metValues = listOf(
            snapshotOf("steps" to 0.0, "meditation" to 0.0, "workout" to 0.0),
            snapshotOf("steps" to 10_000.0, "meditation" to 0.0, "workout" to 0.0),
            snapshotOf("steps" to 10_000.0, "meditation" to 10.0, "workout" to 0.0),
            snapshotOf("steps" to 10_000.0, "meditation" to 10.0, "workout" to 60.0),
        )
        for (threshold in 1..conditions.size) {
            val block = testBlock(conditions = conditions, thresholdN = threshold)
            for (metCount in metValues.indices) {
                val state = evaluator.evaluate(block, null, metValues[metCount], now)
                if (metCount >= threshold) {
                    assertIs<GateState.Open>(state, "threshold=$threshold met=$metCount")
                } else {
                    assertIs<GateState.Locked>(state, "threshold=$threshold met=$metCount")
                }
                assertEquals(metCount, state.metCount)
            }
        }
    }

    @Test
    fun `value exactly equal to requirement counts as met`() {
        val block = testBlock(conditions = conditions, thresholdN = 3)
        val snapshot = snapshotOf("steps" to 10_000.0, "meditation" to 10.0, "workout" to 60.0)
        val state = evaluator.evaluate(block, null, snapshot, now)
        assertIs<GateState.Open>(state)
        assertTrue(state.progress.all { it.met })
    }

    @Test
    fun `one unit short of requirement is not met`() {
        val block = testBlock(conditions = conditions, thresholdN = 1)
        val snapshot = snapshotOf("steps" to 9_999.0, "meditation" to 9.9, "workout" to 59.9)
        assertIs<GateState.Locked>(evaluator.evaluate(block, null, snapshot, now))
    }

    @Test
    fun `block without conditions is permanently locked`() {
        val block = testBlock(conditions = emptyList(), thresholdN = 0)
        val state = evaluator.evaluate(block, null, snapshotOf(), now)
        assertIs<GateState.Locked>(state)
        assertTrue(state.progress.isEmpty())
    }

    @Test
    fun `threshold above condition count is coerced to condition count`() {
        val two = conditions.take(2)
        val block = testBlock(conditions = two, thresholdN = 5)
        assertEquals(2, evaluator.effectiveThreshold(block))
        val allMet = snapshotOf("steps" to 10_000.0, "meditation" to 10.0)
        assertIs<GateState.Open>(evaluator.evaluate(block, null, allMet, now))
        val oneMet = snapshotOf("steps" to 10_000.0, "meditation" to 0.0)
        assertIs<GateState.Locked>(evaluator.evaluate(block, null, oneMet, now))
    }

    @Test
    fun `threshold below one is coerced to one`() {
        val block = testBlock(conditions = conditions, thresholdN = 0)
        assertEquals(1, evaluator.effectiveThreshold(block))
        assertIs<GateState.Locked>(evaluator.evaluate(block, null, snapshotOf(), now))
    }

    @Test
    fun `ratcheted requirement overrides the base goal`() {
        val block = testBlock(conditions = conditions, thresholdN = 1)
        val dayState = dayState(requiredNow = mapOf("steps" to 11_300.0, "meditation" to 10.0, "workout" to 60.0))
        val snapshot = snapshotOf("steps" to 10_500.0)
        val state = evaluator.evaluate(block, dayState, snapshot, now)
        assertIs<GateState.Locked>(state)
        assertEquals(11_300.0, state.progress.first().required)
    }

    @Test
    fun `active session wins over an otherwise open gate`() {
        val block = testBlock(conditions = conditions, thresholdN = 3)
        val endsAt = now.plusSeconds(600)
        val dayState = dayState(session = Session("b1", now.toEpochMilli(), endsAt.toEpochMilli()))
        val snapshot = snapshotOf("steps" to 10_000.0, "meditation" to 10.0, "workout" to 60.0)
        val state = evaluator.evaluate(block, dayState, snapshot, now)
        assertIs<GateState.SessionActive>(state)
        assertEquals(endsAt, state.endsAt)
    }

    @Test
    fun `active session wins over an otherwise locked gate`() {
        val block = testBlock(conditions = conditions, thresholdN = 3)
        val dayState = dayState(session = Session("b1", now.toEpochMilli(), now.plusSeconds(1).toEpochMilli()))
        assertIs<GateState.SessionActive>(evaluator.evaluate(block, dayState, snapshotOf(), now))
    }

    @Test
    fun `expired session is not session active`() {
        val block = testBlock(conditions = conditions, thresholdN = 1)
        val dayState = dayState(
            requiredNow = mapOf("steps" to 10_000.0, "meditation" to 10.0, "workout" to 60.0),
            session = Session("b1", now.minusSeconds(1800).toEpochMilli(), now.toEpochMilli()),
        )
        val snapshot = snapshotOf("steps" to 10_000.0)
        assertIs<GateState.Open>(evaluator.evaluate(block, dayState, snapshot, now))
    }

    // --- step checkpoints ----------------------------------------------------

    private val noon = Instant.parse("2026-08-25T12:00:00Z")
    private val evening = Instant.parse("2026-08-25T17:00:00Z")
    private val checkpoints = listOf(
        StepCheckpoint(noon, 4_000.0),
        StepCheckpoint(evening, 10_000.0),
    )

    private fun stepsBlock() = testBlock(conditions = listOf(Condition.Steps("steps", 10_000.0)), thresholdN = 1)

    @Test
    fun `before the first checkpoint the day is open with a warning`() {
        val state = evaluator.evaluate(
            stepsBlock(), null, snapshotOf("steps" to 200.0), noon.minusSeconds(3600), checkpoints,
        )
        assertIs<GateState.Warn>(state)
        assertEquals(4_000.0, state.nextSteps, EPS)
        assertEquals(noon, state.deadline)
    }

    @Test
    fun `one step short at noon locks the gate against the noon target`() {
        val state = evaluator.evaluate(
            stepsBlock(), null, snapshotOf("steps" to 3_999.0), noon, checkpoints,
        )
        assertIs<GateState.Locked>(state)
        assertEquals(4_000.0, state.progress.single().required, EPS)
    }

    @Test
    fun `meeting noon keeps the day open and points at five`() {
        val state = evaluator.evaluate(
            stepsBlock(), null, snapshotOf("steps" to 4_000.0), noon, checkpoints,
        )
        assertIs<GateState.Warn>(state)
        assertEquals(10_000.0, state.nextSteps, EPS)
        assertEquals(evening, state.deadline)
    }

    @Test
    fun `walking past the noon target reopens the gate by itself`() {
        val state = evaluator.evaluate(
            stepsBlock(), null, snapshotOf("steps" to 4_050.0), noon.plusSeconds(300), checkpoints,
        )
        assertIs<GateState.Warn>(state)
        // Noon is behind us, so progress now points at the five o'clock target.
        assertEquals(10_000.0, state.progress.single().required, EPS)
        assertEquals(10_000.0, state.nextSteps, EPS)
    }

    @Test
    fun `missing five o'clock locks even when noon was met`() {
        val state = evaluator.evaluate(
            stepsBlock(), null, snapshotOf("steps" to 9_500.0), evening, checkpoints,
        )
        assertIs<GateState.Locked>(state)
        assertEquals(10_000.0, state.progress.single().required, EPS)
    }

    @Test
    fun `ten thousand by five is open for the rest of the day`() {
        val state = evaluator.evaluate(
            stepsBlock(), null, snapshotOf("steps" to 10_000.0), evening.plusSeconds(60), checkpoints,
        )
        assertIs<GateState.Open>(state)
    }

    @Test
    fun `without checkpoints the classic lock still applies`() {
        assertIs<GateState.Locked>(evaluator.evaluate(stepsBlock(), null, snapshotOf("steps" to 0.0), noon))
    }

    private fun dayState(
        requiredNow: Map<String, Double> = conditions.associate { it.id to it.goal },
        session: Session? = null,
    ) = BlockDayState(blockId = "b1", dayKey = "2026-08-23", requiredNow = requiredNow, activeSession = session)

    // ---- place conditions: the gym rule ----

    private val nyc = java.time.ZoneId.of("America/New_York")
    private val beforeGymDeadline: Instant = java.time.ZonedDateTime.of(2026, 8, 23, 19, 0, 0, 0, nyc).toInstant()
    private val afterGymDeadline: Instant = java.time.ZonedDateTime.of(2026, 8, 23, 20, 1, 0, 0, nyc).toInstant()

    private fun gymBlock(places: List<String> = emptyList(), minutes: Double = 30.0) = testBlock(
        conditions = listOf(Condition.PlaceVisit("gym", minutes, deadlineHour = 20, placeIds = places)),
        thresholdN = 1,
    )

    @Test
    fun `a place block is open before its own deadline`() {
        val state = evaluator.evaluate(
            gymBlock(), null, snapshotOf("gym" to 0.0), beforeGymDeadline, zone = nyc,
        )
        assertIs<GateState.Open>(state)
    }

    @Test
    fun `a place block locks once its deadline passes unmet`() {
        val state = evaluator.evaluate(
            gymBlock(), null, snapshotOf("gym" to 0.0), afterGymDeadline, zone = nyc,
        )
        assertIs<GateState.Locked>(state)
        assertEquals(30.0, state.progress.single().required, EPS)
    }

    @Test
    fun `a place block reopens the moment the minutes are reached`() {
        val state = evaluator.evaluate(
            gymBlock(), null, snapshotOf("gym" to 30.0), afterGymDeadline, zone = nyc,
        )
        assertIs<GateState.Open>(state)
    }

    @Test
    fun `step checkpoints never satisfy a place block`() {
        // 12:00 and 17:00 checkpoints are both past; 20,000 steps must still not open the gym gate.
        val checkpoints = listOf(StepCheckpoint(noon, 4_000.0), StepCheckpoint(evening, 10_000.0))
        val state = evaluator.evaluate(
            gymBlock(), null, snapshotOf("gym" to 0.0), afterGymDeadline, checkpoints, nyc,
        )
        assertIs<GateState.Locked>(state)
    }

    @Test
    fun `meeting steps does not excuse the place, and meeting the place does not excuse steps`() {
        val mixed = testBlock(
            conditions = listOf(
                Condition.Steps("steps", 10_000.0),
                Condition.PlaceVisit("gym", 30.0, deadlineHour = 20),
            ),
            thresholdN = 2,
        )
        val stepsOnly = evaluator.evaluate(
            mixed, null, snapshotOf("steps" to 12_000.0, "gym" to 0.0), afterGymDeadline, emptyList(), nyc,
        )
        assertIs<GateState.Locked>(stepsOnly)
        val placeOnly = evaluator.evaluate(
            mixed, null, snapshotOf("steps" to 0.0, "gym" to 45.0), afterGymDeadline, emptyList(), nyc,
        )
        assertIs<GateState.Locked>(placeOnly)
    }
}
