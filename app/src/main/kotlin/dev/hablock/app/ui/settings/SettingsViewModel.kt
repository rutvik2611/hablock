package dev.hablock.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hablock.app.domain.enforcement.DeviceOwnerController
import dev.hablock.app.domain.model.HcAvailability
import dev.hablock.app.domain.model.RelinquishState
import dev.hablock.app.domain.repository.HealthRepository
import dev.hablock.app.domain.repository.PermissionChecker
import dev.hablock.app.domain.service.RelinquishTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val accessibility: Boolean = false,
    val usageAccess: Boolean = false,
    val exactAlarms: Boolean = true,
    val deviceOwner: Boolean = false,
    val relinquishFailed: Boolean = false,
    val healthSupported: Boolean = true,
    val health: Boolean = false,
    val overrideUntil: java.time.Instant? = null,
    val locationEnabled: Boolean = false,
    val locationRequiredMinutes: Int = 30,
    val locationPlaces: List<dev.hablock.app.domain.model.TrackedPlace> = emptyList(),
    val locationDefaultPlaceId: String? = null,
    val checkInPlaceName: String? = null,
    val checkInMinutes: Long = 0L,
    val checkInNotice: String? = null,
    val locationMinutesToday: Long = 0,
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
)

class SettingsViewModel(
    private val permissionChecker: PermissionChecker,
    private val deviceOwnerController: DeviceOwnerController,
    private val relinquishTimer: RelinquishTimer,
    private val healthRepository: HealthRepository,
    private val overrideStore: dev.hablock.app.domain.service.OverrideStore,
    private val gateEngine: dev.hablock.app.domain.service.GateEngine,
    private val locationStore: dev.hablock.app.domain.service.LocationStore,
    private val locationSampler: dev.hablock.app.system.LocationSampler,
    private val dayClock: dev.hablock.app.domain.service.DayClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val relinquish: StateFlow<RelinquishState> =
        relinquishTimer.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelinquishState.Idle)

    val healthPermissions: Set<String> get() = healthRepository.requiredPermissions()

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            accessibility = permissionChecker.isAccessibilityServiceEnabled(),
            fineLocation = permissionChecker.hasFineLocation(),
            backgroundLocation = permissionChecker.hasBackgroundLocation(),
            usageAccess = permissionChecker.hasUsageAccess(),
            exactAlarms = permissionChecker.canScheduleExactAlarms(),
            deviceOwner = deviceOwnerController.isDeviceOwner(),
        )
        viewModelScope.launch {
            val until = runCatching { overrideStore.until() }.getOrNull()
            val active = until?.takeIf { it.isAfter(java.time.Instant.now()) }
            _uiState.value = _uiState.value.copy(overrideUntil = active)
            val rule = runCatching { locationStore.config() }.getOrDefault(dev.hablock.app.domain.model.LocationRule())
            val day = runCatching { locationStore.day() }.getOrNull()
                ?.takeIf { it.dayKey == dayClock.dayKey() }
            _uiState.value = _uiState.value.copy(
                locationEnabled = rule.enabled,
                locationRequiredMinutes = rule.requiredMinutes,
                locationPlaces = rule.places,
                locationDefaultPlaceId = rule.defaultPlaceId,
                checkInPlaceName = day?.checkInPlaceId?.let { id -> rule.places.firstOrNull { it.id == id }?.name },
                checkInMinutes = day?.checkInSinceMillis?.takeIf { it > 0 }?.let {
                    ((System.currentTimeMillis() - it) / 60_000L).coerceAtLeast(0L)
                } ?: 0L,
                locationMinutesToday = (day?.accumulatedMillis ?: 0L) / 60_000L,
            )
            val availability = runCatching { healthRepository.availability() }.getOrDefault(HcAvailability.UNAVAILABLE)
            val granted = runCatching { healthRepository.hasAllPermissions() }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(
                healthSupported = availability == HcAvailability.FULL || availability == HcAvailability.NO_MINDFULNESS,
                health = granted,
            )
        }
    }

    fun setLocationEnabled(enabled: Boolean) = viewModelScope.launch {
        val rule = locationStore.config()
        locationStore.saveConfig(rule.copy(enabled = enabled))
        gateEngine.refreshAll()
        refresh()
    }

    fun setLocationRequiredMinutes(minutes: Int) = viewModelScope.launch {
        val rule = locationStore.config()
        locationStore.saveConfig(rule.copy(requiredMinutes = minutes.coerceIn(5, 240)))
        gateEngine.refreshAll()
        refresh()
    }

    /** Saves the phone's current position as a tracked place with the radius the user chose. */
    fun addPlaceHere(name: String, radiusMeters: Double) = viewModelScope.launch {
        val fix = runCatching { locationSampler.currentLocation() }.getOrNull() ?: return@launch
        val rule = locationStore.config()
        val place = dev.hablock.app.domain.model.TrackedPlace(
            id = java.util.UUID.randomUUID().toString(),
            name = name.ifBlank { "Place ${rule.places.size + 1}" },
            latitude = fix.latitude,
            longitude = fix.longitude,
            radiusMeters = radiusMeters.coerceIn(5.0, 2000.0),
        )
        locationStore.saveConfig(rule.copy(enabled = true, places = rule.places + place))
        gateEngine.refreshAll()
        refresh()
    }

    fun removePlace(id: String) = viewModelScope.launch {
        val rule = locationStore.config()
        locationStore.saveConfig(
            rule.copy(
                places = rule.places.filterNot { it.id == id },
                defaultPlaceId = rule.defaultPlaceId?.takeIf { it != id },
            ),
        )
        gateEngine.refreshAll()
        refresh()
    }

    /**
     * "I'm here": the person tells the app they are at a place, and the minutes start counting.
     *
     * Validated against the current fix, so tapping it from the sofa does not open the gate: the check-in
     * is refused unless the phone is inside that place, and the refusal says how far away it is.
     */
    fun checkIn(placeId: String) = viewModelScope.launch {
        val place = locationStore.config().places.firstOrNull { it.id == placeId }
            ?: return@launch
        // GPS indoors is routinely 10-50 m off, so the check is only refused when the fix *confidently*
        // says the phone is somewhere else. No fix at all is uncertainty, not a refusal: the check-in is
        // accepted provisionally and minutes only start once a later sample confirms presence.
        val fix = runCatching { locationSampler.currentLocation() }.getOrNull()
        if (fix != null) {
            val distance = dev.hablock.app.domain.model.DwellTracker.distanceMeters(
                fix.latitude,
                fix.longitude,
                place.latitude,
                place.longitude,
            )
            val accuracy = fix.accuracy.coerceAtLeast(0f).toDouble()
            if (distance > place.radiusMeters + accuracy) {
                _uiState.update {
                    it.copy(
                        checkInNotice = "You are ${distance.toInt()} m from ${place.name} " +
                            "(±${accuracy.toInt()} m). Check-in needs you inside it.",
                    )
                }
                return@launch
            }
        }
        val now = dayClock.now()
        val dayKey = dayClock.dayKey(now)
        val day = locationStore.day()?.takeIf { it.dayKey == dayKey } ?: dev.hablock.app.domain.model.DwellDayState(dayKey = dayKey)
        locationStore.saveDay(
            day.copy(checkInPlaceId = placeId, checkInSinceMillis = now.toEpochMilli()),
        )
        _uiState.update { it.copy(checkInNotice = null) }
        // One reading now, so the next tick credits the gap from this moment on.
        runCatching { locationSampler.sample() }
        gateEngine.refreshAll()
        refresh()
    }

    fun checkOut() = viewModelScope.launch {
        val day = locationStore.day() ?: return@launch
        locationStore.saveDay(day.copy(checkInPlaceId = null, checkInSinceMillis = 0L))
        runCatching { locationSampler.sample() }
        gateEngine.refreshAll()
        refresh()
    }

    /** The radio selection: the gym new place conditions start on. Null means "any tracked place". */
    fun setDefaultPlace(id: String?) = viewModelScope.launch {
        val rule = locationStore.config()
        locationStore.saveConfig(rule.copy(defaultPlaceId = id))
        refresh()
    }

    /** Adds a gym from typed coordinates, so it can be set up away from the building. */
    fun addPlace(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        excluded: Boolean = false,
    ) =
        viewModelScope.launch {
            val rule = locationStore.config()
            val place = dev.hablock.app.domain.model.TrackedPlace(
                id = java.util.UUID.randomUUID().toString(),
                name = name.ifBlank { "Gym ${rule.places.size + 1}" },
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters.coerceIn(5.0, 2000.0),
                excluded = excluded,
            )
            locationStore.saveConfig(
                rule.copy(
                    enabled = true,
                    places = rule.places + place,
                    defaultPlaceId = rule.defaultPlaceId ?: place.id.takeUnless { excluded },
                ),
            )
            gateEngine.refreshAll()
            refresh()
        }

    /** End a temporary unlock now instead of waiting for it to run out. */
    fun lockNow() = viewModelScope.launch {
        gateEngine.clearOverride()
        refresh()
    }

    fun startRelinquish() = viewModelScope.launch { relinquishTimer.start() }

    fun cancelRelinquish() = viewModelScope.launch { relinquishTimer.cancel() }

    fun confirmRelinquish() = viewModelScope.launch {
        val succeeded = relinquishTimer.confirmRelinquish()
        _uiState.value = _uiState.value.copy(relinquishFailed = !succeeded)
        refresh()
    }
}
