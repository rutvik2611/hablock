package dev.hablock.app.domain.model

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fence derived from points walked around a place.
 *
 * Nothing here knows about Android: the user walks the boundary, the recorder collects fixes, and this
 * turns them into a centre and a radius wide enough to cover every point walked. That is the honest
 * answer to "draw the fence" on a phone with no map and no internet permission.
 */
data class RecordedFence(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val samples: Int,
)

object FenceRecorder {

    /** Below this the walking is too incomplete to describe a place. */
    const val MIN_SAMPLES = 4

    /** A little slack so a boundary walked once is not clipped on later visits. */
    const val MARGIN_METERS = 10.0

    const val MIN_RADIUS = 5.0
    const val MAX_RADIUS = 2_000.0

    /**
     * Centre as the arithmetic mean of the walked points, radius as the distance to the farthest one.
     *
     * Averaging latitude and longitude directly is acceptable at the scale of a building; the error grows
     * with latitude but a gym fence is metres, not kilometres.
     */
    fun record(points: List<Pair<Double, Double>>): RecordedFence? {
        val usable = points.filter { it.first in -90.0..90.0 && it.second in -180.0..180.0 }
        if (usable.size < MIN_SAMPLES) return null

        val centreLat = usable.sumOf { it.first } / usable.size
        val centreLon = usable.sumOf { it.second } / usable.size
        val farthest = usable.maxOf { DwellTracker.distanceMeters(centreLat, centreLon, it.first, it.second) }
        val radius = (farthest + MARGIN_METERS).coerceIn(MIN_RADIUS, MAX_RADIUS)
        return RecordedFence(centreLat, centreLon, radius, usable.size)
    }

    /** How far the walked path still has to close, shown while recording so the walk has a finish line. */
    fun gapMeters(points: List<Pair<Double, Double>>): Double? {
        if (points.size < 2) return null
        val first = points.first()
        val last = points.last()
        return DwellTracker.distanceMeters(first.first, first.second, last.first, last.second)
    }
}
