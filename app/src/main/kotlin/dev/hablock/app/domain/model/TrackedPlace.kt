package dev.hablock.app.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** A place whose visits count toward the location requirement. */
@Serializable
data class TrackedPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = 150.0,
)

/**
 * The location rule: by [deadlineHour] local, the day must contain at least [requiredMinutes] of presence
 * at any [TrackedPlace]. It is an extra gate on top of the step checkpoints, not a replacement.
 */
@Serializable
data class LocationRule(
    val enabled: Boolean = false,
    val requiredMinutes: Int = 30,
    val deadlineHour: Int = 20,
    val places: List<TrackedPlace> = emptyList(),
)

/** One day's accumulated presence, plus the sample bookkeeping needed to add to it. */
@Serializable
data class DwellDayState(
    val dayKey: String,
    val accumulatedMillis: Long = 0L,
    val lastSampleMillis: Long = 0L,
    val lastInside: Boolean = false,
) {
    val accumulatedMinutes: Long get() = accumulatedMillis / 60_000L
}

/** Outcome of one location sample, kept pure so the dwell maths is testable without Android. */
object DwellTracker {

    /**
     * Adds to the day's total when the sample says "inside".
     *
     * The credit for a sample is the time since the previous sample, capped at [maxStepMillis], so a
     * phone that was asleep for hours cannot claim an eight-hour location session from two readings. The
     * first sample of a day earns nothing: there is no interval before it.
     */
    fun apply(
        state: DwellDayState,
        dayKey: String,
        now: Instant,
        inside: Boolean,
        maxStepMillis: Long,
    ): DwellDayState {
        val carried = if (state.dayKey == dayKey) state else DwellDayState(dayKey = dayKey)
        val nowMs = now.toEpochMilli()
        val gap = if (carried.lastSampleMillis == 0L) 0L else (nowMs - carried.lastSampleMillis).coerceAtLeast(0L)
        val credit = if (inside) gap.coerceAtMost(maxStepMillis) else 0L
        return carried.copy(
            accumulatedMillis = carried.accumulatedMillis + credit,
            lastSampleMillis = nowMs,
            lastInside = inside,
        )
    }

    fun requiredMillis(config: LocationRule): Long = config.requiredMinutes * 60_000L

    fun isSatisfied(state: DwellDayState, config: LocationRule): Boolean =
        state.accumulatedMillis >= requiredMillis(config)

    fun remainingMinutes(state: DwellDayState, config: LocationRule): Int {
        val remaining = requiredMillis(config) - state.accumulatedMillis
        if (remaining <= 0) return 0
        return ((remaining + 59_999) / 60_000L).toInt()
    }

    /** Great-circle distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    fun insideAny(places: List<TrackedPlace>, lat: Double, lon: Double): Boolean =
        places.any { distanceMeters(lat, lon, it.latitude, it.longitude) <= it.radiusMeters }
}
