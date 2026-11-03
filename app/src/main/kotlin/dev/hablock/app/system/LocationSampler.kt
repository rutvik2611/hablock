package dev.hablock.app.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import dev.hablock.app.domain.GateConstants
import dev.hablock.app.domain.model.DwellDayState
import dev.hablock.app.domain.model.DwellTracker
import dev.hablock.app.domain.service.DayClock
import dev.hablock.app.domain.service.LocationStore
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One location reading per alarm tick, turned into dwell credit.
 *
 * Deliberately cheap: it prefers the last known fix and only asks for a fresh one when that fix is
 * stale, so a five-minute tick does not hold the GPS open. No Play Services dependency, which keeps
 * the app working on a phone without Google location services.
 */
class LocationSampler(
    private val context: Context,
    private val locationStore: LocationStore,
    private val dayClock: DayClock,
    private val healthRepository: dev.hablock.app.domain.repository.HealthRepository? = null,
) {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    suspend fun sample() {
        val config = locationStore.config()
        // Places exist because some block needs them; the global rule's own switch is not the only reason
        // to sample, or a per-block gym condition would never accumulate minutes.
        if (config.places.isEmpty()) return
        if (!hasLocationPermission()) return

        val location = freshestLocation() ?: return
        val now = Instant.now()
        val dayKey = dayClock.dayKey(now)
        val state = locationStore.day() ?: DwellDayState(dayKey = dayKey)
        val matched = DwellTracker.countedPlace(config.places, location.latitude, location.longitude)
        // Presence is only a candidate. A window also has to look like activity, so a phone left on the
        // sofa inside a tower gym's fence earns nothing.
        val moving = matched != null && runCatching {
            val windowFrom = if (state.lastSampleMillis > 0L) {
                Instant.ofEpochMilli(state.lastSampleMillis)
            } else {
                now.minusMillis(GateConstants.MOVEMENT_WINDOW.inWholeMilliseconds)
            }
            DwellTracker.corroborated(
                steps = healthRepository?.steps(windowFrom, now) ?: Double.MAX_VALUE,
                exerciseMinutes = healthRepository?.exerciseMinutes(windowFrom, now) ?: 0.0,
                minSteps = GateConstants.MOVEMENT_MIN_STEPS,
            )
        }.getOrDefault(true)
        // A check-in holds only while the phone is still at that place; leaving ends it.
        val stillThere = matched != null &&
            (state.checkInPlaceId == null || state.checkInPlaceId == matched.id)
        val next = DwellTracker.apply(
            state = state,
            dayKey = dayKey,
            now = now,
            inside = moving,
            maxStepMillis = GateConstants.LOCATION_MAX_CREDIT_MILLIS,
            placeId = matched?.id,
        )
        locationStore.saveDay(
            if (state.checkInPlaceId != null && !stillThere) {
                next.copy(checkInPlaceId = null, checkInSinceMillis = 0L)
            } else {
                next
            },
        )
    }

    /** One-shot fix for the "add this place" button; null when permission is missing. */
    suspend fun currentLocation(): android.location.Location? =
        if (hasLocationPermission()) freshestLocation() else null

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last known fix if it is younger than ten minutes, otherwise one bounded fresh request. */
    private suspend fun freshestLocation(): Location? {
        val manager = locationManager ?: return null
        val best = manager.allProviders
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best != null && System.currentTimeMillis() - best.time < FRESH_ENOUGH_MILLIS) return best
        return requestSingleFix(manager) ?: best
    }

    private suspend fun requestSingleFix(manager: LocationManager): Location? =
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                    @Deprecated("Required by the platform interface")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                }
                val provider = when {
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> null
                }
                if (provider == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                runCatching {
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure { continuation.resume(null) }
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }

    private companion object {
        const val FRESH_ENOUGH_MILLIS = 10 * 60 * 1000L
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
    }
}
