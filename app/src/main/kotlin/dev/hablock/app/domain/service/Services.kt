package dev.hablock.app.domain.service

import dev.hablock.app.domain.model.Condition
import dev.hablock.app.domain.model.GateState
import dev.hablock.app.domain.model.MetricSnapshot
import dev.hablock.app.domain.model.RelinquishState
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Single orchestrator for gate evaluation, sessions and enforcement; keyed by block id. */
interface GateEngine {
    val states: StateFlow<Map<String, GateState>>
    suspend fun refreshAll()
    suspend fun onAppForegrounded(packageName: String)
    suspend fun onSessionExpired(blockId: String)
    suspend fun onDayReset()

    /** Fired by a checkpoint alarm: re-evaluate, close the gate if short, cover what is on screen. */
    suspend fun onCheckpointReached()

    /** Fired half an hour before a checkpoint: remind with the exact shortfall. */
    suspend fun onCheckpointWarning()

    /** Polled while the gate is closed so reaching the target reopens it without user action. */
    suspend fun onRecheck()
    suspend fun deleteBlock(blockId: String)
}

interface MetricProvider {
    suspend fun snapshot(conditions: List<Condition>, from: Instant, to: Instant): MetricSnapshot
}

interface AlarmScheduler {
    fun scheduleSessionEnd(blockId: String, at: Instant)
    fun cancelSessionEnd(blockId: String)
    fun scheduleDayReset(at: Instant)
    fun scheduleCheckpoint(at: Instant, steps: Double, warning: Boolean, leadMinutes: Int = 0)
    fun scheduleRecheck(at: Instant)
    fun scheduleRelinquishReady(at: Instant)
    fun cancelRelinquishReady()
}

interface Notifier {
    fun sessionEnded(blockId: String, blockName: String)
    fun relinquishReady()

    /** "3,200 steps left by 17:00" — sent before a checkpoint that is still outstanding. */
    fun checkpointWarning(stepsRemaining: Double, stepsTarget: Double, at: Instant)

    /** The gate just closed: the user is short and the apps are locked. */
    fun gateClosed(stepsRemaining: Double, stepsTarget: Double)

    /** The target was reached and the apps are usable again. */
    fun gateOpened()
}

interface RelinquishTimer {
    val state: Flow<RelinquishState>
    suspend fun start()
    suspend fun cancel()

    /** True when device ownership was actually dropped; false leaves the countdown state untouched. */
    suspend fun confirmRelinquish(): Boolean
}
