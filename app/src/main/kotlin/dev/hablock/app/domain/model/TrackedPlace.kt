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
    /**
     * True for a place that must never earn dwell credit — home, above all. A gym inside the same
     * building as home cannot be told apart by radius, so sitting at home would otherwise count as a visit.
     */
    val excluded: Boolean = false,
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
    /** Preselected gym for new place conditions; the radio selection in settings. */
    val defaultPlaceId: String? = null,
)

/** One day's accumulated presence, plus the sample bookkeeping needed to add to it. */
@Serializable
data class DwellDayState(
    val dayKey: String,
    val accumulatedMillis: Long = 0L,
    val lastSampleMillis: Long = 0L,
    val lastInside: Boolean = false,
    /** Presence per tracked place id, so one gym can be required without another satisfying it. */
    val perPlaceMillis: Map<String, Long> = emptyMap(),
    /** Set while the user has told the app they are at a place ("I'm here"). */
    val checkInPlaceId: String? = null,
    val checkInSinceMillis: Long = 0L,
    /** Consecutive readings that said "not here", so one bad indoor fix cannot end a visit. */
    val outsideStreak: Int = 0,
) {
    val accumulatedMinutes: Long get() = accumulatedMillis / 60_000L

    fun minutesAt(placeIds: Collection<String>): Long =
        if (placeIds.isEmpty()) accumulatedMinutes else placeIds.sumOf { perPlaceMillis[it] ?: 0L } / 60_000L
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
        placeId: String? = null,
    ): DwellDayState {
        val carried = if (state.dayKey == dayKey) state else DwellDayState(dayKey = dayKey)
        val nowMs = now.toEpochMilli()
        val gap = if (carried.lastSampleMillis == 0L) 0L else (nowMs - carried.lastSampleMillis).coerceAtLeast(0L)
        val credit = if (inside) gap.coerceAtMost(maxStepMillis) else 0L
        // A reading matched to one place also counts toward the "any place" total; an unmatched-but-inside
        // reading (older callers that only pass a boolean) still earns the total without a place breakdown.
        val perPlace = if (credit > 0 && placeId != null) {
            carried.perPlaceMillis + (placeId to (carried.perPlaceMillis[placeId] ?: 0L) + credit)
        } else {
            carried.perPlaceMillis
        }
        return carried.copy(
            accumulatedMillis = carried.accumulatedMillis + credit,
            lastSampleMillis = nowMs,
            lastInside = inside,
            perPlaceMillis = perPlace,
        )
    }

    /**
     * Whether a window looks like a real visit rather than a phone resting at home.
     *
     * Steps or a recorded workout both count; a still phone counts for nothing, however well it sits
     * inside the fence.
     */
    fun corroborated(steps: Double, exerciseMinutes: Double, minSteps: Double): Boolean =
        steps >= minSteps || exerciseMinutes > 0.0

    /** The first tracked place containing the reading, or null when outside all of them. */
    fun matchedPlace(places: List<TrackedPlace>, lat: Double, lon: Double): TrackedPlace? =
        places.firstOrNull { distanceMeters(lat, lon, it.latitude, it.longitude) <= it.radiusMeters }

    /**
     * The place that earns credit for this reading, or null when nothing should.
     *
     * An excluded place wins over any counted one it overlaps: if the phone is at home, the gym next door
     * inside the same building earns nothing. This is what stops a lazy day at home from passing the rule.
     */
    fun countedPlace(
        places: List<TrackedPlace>,
        lat: Double,
        lon: Double,
        accuracyMeters: Double = 0.0,
    ): TrackedPlace? {
        // The fix's own accuracy widens the fence: indoors, a 30 m error must not eject a member mid-set.
        val inside = places.filter {
            distanceMeters(lat, lon, it.latitude, it.longitude) <= it.radiusMeters + accuracyMeters
        }
        if (inside.any { it.excluded }) return null
        return inside.firstOrNull()
    }

    fun requiredMillis(config: LocationRule): Long = config.requiredMinutes * 60_000L

    fun isSatisfied(state: DwellDayState, config: LocationRule): Boolean =
        state.accumulatedMillis >= requiredMillis(config)

    /** Satisfaction for one condition's chosen places (empty = any tracked place). */
    fun isSatisfied(state: DwellDayState, condition: Condition.PlaceVisit): Boolean =
        state.minutesAt(condition.placeIds).toDouble() >= condition.goal

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
