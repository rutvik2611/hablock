package dev.hablock.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hablock.app.R
import dev.hablock.app.domain.model.FenceRecorder
import dev.hablock.app.domain.model.RecordedFence
import kotlinx.coroutines.delay
import dev.hablock.app.ui.components.GroupPosition
import dev.hablock.app.ui.components.GroupedListItem
import dev.hablock.app.ui.components.SectionHeader
import dev.hablock.app.ui.gateViewModel
import dev.hablock.app.ui.settings.SettingsViewModel

/**
 * Places get their own tab because a place is not a clock: the step gate is about time,
 * a gym rule is about geography, and mixing them into Settings hid the feature entirely.
 */
@Composable
fun PlacesScreen(modifier: Modifier = Modifier) {
    val viewModel = gateViewModel { container ->
        SettingsViewModel(
            container.permissionChecker,
            container.deviceOwnerController,
            container.relinquishTimer,
            container.healthRepository,
            container.overrideStore,
            container.gateEngine,
            container.locationStore,
            container.locationSampler,
            container.dayClock,
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // type -> (label, home?, minutes, deadline hour): the type decides the default behaviour
    data class Preset(val label: String, val home: Boolean, val minutes: Int, val hour: Int)
    var preset by remember { mutableStateOf<Preset?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.places_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.places_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        )

        SectionHeader(stringResource(R.string.places_section_rule))
        GroupedListItem(
            position = GroupPosition.First,
            title = stringResource(R.string.settings_location_require),
            supporting = stringResource(
                R.string.settings_location_summary,
                uiState.locationRequiredMinutes,
                uiState.locationMinutesToday,
            ),
            trailing = {
                Switch(checked = uiState.locationEnabled, onCheckedChange = { viewModel.setLocationEnabled(it) })
            },
        )

        val counted = uiState.locationPlaces.filterNot { it.excluded }
        if (counted.isNotEmpty()) {
            val name = uiState.checkInPlaceName
            GroupedListItem(
                position = GroupPosition.Middle,
                title = if (name != null) {
                    stringResource(R.string.settings_checkin_at, name)
                } else {
                    stringResource(R.string.settings_checkin_ask)
                },
                supporting = when {
                    uiState.checkInNotice != null -> uiState.checkInNotice
                    name != null -> stringResource(R.string.settings_checkin_counted, uiState.checkInMinutes)
                    else -> stringResource(R.string.settings_checkin_hint)
                },
                trailing = {
                    TextButton(
                        onClick = {
                            if (name != null) viewModel.checkOut() else viewModel.checkIn(counted.first().id)
                        },
                    ) {
                        Text(
                            stringResource(
                                if (name != null) R.string.settings_checkin_stop else R.string.settings_checkin_start,
                            ),
                        )
                    }
                },
            )
        }

        SectionHeader(stringResource(R.string.places_section_saved))
        if (uiState.locationPlaces.isEmpty()) {
            GroupedListItem(
                position = GroupPosition.First,
                title = stringResource(R.string.places_empty),
                supporting = stringResource(R.string.places_empty_hint),
            )
        } else {
            uiState.locationPlaces.forEach { place ->
                GroupedListItem(
                    position = GroupPosition.Middle,
                    onClick = if (place.excluded) null else ({ viewModel.setDefaultPlace(place.id) }),
                    title = place.name,
                    supporting = if (place.excluded) {
                        stringResource(R.string.settings_location_home_tag, place.radiusMeters.toInt())
                    } else {
                        stringResource(R.string.settings_location_radius, place.radiusMeters.toInt())
                    },
                    leading = if (place.excluded) {
                        null
                    } else {
                        {
                            RadioButton(
                                selected = uiState.locationDefaultPlaceId == place.id,
                                onClick = { viewModel.setDefaultPlace(place.id) },
                            )
                        }
                    },
                    trailing = {
                        TextButton(onClick = { viewModel.removePlace(place.id) }) {
                            Text(stringResource(R.string.settings_location_remove))
                        }
                    },
                )
            }
        }
        GroupedListItem(position = GroupPosition.Last) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.places_add_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Named types, because "add a place" makes a person invent a schema. Home is
                // offered too: it is a label that exists to be excluded, not a requirement.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Preset("Gym", home = false, minutes = 30, hour = 20),
                        Preset("Home", home = true, minutes = 30, hour = 20),
                        Preset("Work", home = false, minutes = 60, hour = 18),
                    ).forEach { option ->
                        TextButton(onClick = { preset = option }) { Text(option.label) }
                    }
                    TextButton(onClick = { preset = Preset("", false, 30, 20) }) {
                        Text(stringResource(R.string.places_custom))
                    }
                }
            }
        }

        Spacer(Modifier.height(150.dp))
    }

    preset?.let { option ->
        AddPlaceDialog(
            preset = option.label,
            initialMinutes = option.minutes,
            initialHour = option.hour,
            onDismiss = { preset = null },
            onUseCurrent = { fill -> viewModel.currentFix { lat, lon -> fill(lat, lon) } },
            onSave = { name, lat, lon, radius, home, minutes, hour ->
                viewModel.addPlace(
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = radius,
                    excluded = home,
                    kind = option.label.ifBlank { "custom" }.lowercase(),
                    requiredMinutes = minutes,
                    deadlineHour = hour,
                )
                preset = null
            },
        )
    }
}

@Composable
private fun AddPlaceDialog(
    preset: String,
    initialMinutes: Int,
    initialHour: Int,
    onDismiss: () -> Unit,
    onUseCurrent: (fill: (Double, Double) -> Unit) -> Unit,
    onSave: (String, Double, Double, Double, Boolean, Int, Int) -> Unit,
) {
    var name by remember { mutableStateOf(preset) }
    var radius by remember { mutableStateOf("150") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var home by remember { mutableStateOf(preset.equals("Home", ignoreCase = true)) }
    var recording by remember { mutableStateOf(false) }
    var mapping by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf(initialMinutes.toString()) }
    var hour by remember { mutableStateOf(initialHour.toString()) }
    val latValue = lat.toDoubleOrNull()
    val lonValue = lon.toDoubleOrNull()
    val ready = latValue != null && lonValue != null &&
        latValue in -90.0..90.0 && lonValue in -180.0..180.0

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.places_editor_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_location_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Two ways to place a pin; both are first class.
                TextButton(
                    onClick = {
                        onUseCurrent { fixLat, fixLon ->
                            lat = "%.6f".format(fixLat)
                            lon = "%.6f".format(fixLon)
                        }
                    },
                ) { Text(stringResource(R.string.places_use_current)) }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { mapping = true }) {
                        Text(stringResource(R.string.zone_map_open))
                    }
                    TextButton(onClick = { recording = true }) {
                        Text(stringResource(R.string.places_record_fence))
                    }
                }
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text(stringResource(R.string.settings_location_latitude)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text(stringResource(R.string.settings_location_longitude)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = radius,
                    onValueChange = { radius = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.settings_location_radius_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(5, 25, 100, 250, 500).forEach { option ->
                        TextButton(onClick = { radius = option.toString() }) { Text("$option m") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.places_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = hour,
                        onValueChange = { hour = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.places_by_hour)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clickable { home = !home },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = home, onCheckedChange = { home = it })
                    Text(stringResource(R.string.settings_location_home_toggle), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name,
                        latValue ?: 0.0,
                        lonValue ?: 0.0,
                        radius.toDoubleOrNull() ?: 150.0,
                        home,
                        minutes.toIntOrNull() ?: 30,
                        hour.toIntOrNull() ?: 20,
                    )
                },
                enabled = ready,
            ) { Text(stringResource(R.string.settings_location_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (mapping) {
        ZoneMapDialog(
            initialLat = lat.toDoubleOrNull(),
            initialLon = lon.toDoubleOrNull(),
            initialRadius = radius.toDoubleOrNull() ?: 150.0,
            onDismiss = { mapping = false },
            onPicked = { pickLat, pickLon, pickRadius ->
                lat = "%.6f".format(pickLat)
                lon = "%.6f".format(pickLon)
                radius = pickRadius.toInt().toString()
                mapping = false
            },
        )
    }
    if (recording) {
        FenceRecordDialog(
            onSample = onUseCurrent,
            onDismiss = { recording = false },
            onRecorded = { fence ->
                lat = "%.6f".format(fence.latitude)
                lon = "%.6f".format(fence.longitude)
                radius = fence.radiusMeters.toInt().toString()
                recording = false
            },
        )
    }
}

/** Walks the boundary: one fix every few seconds until the user says the loop is closed. */
@Composable
private fun FenceRecordDialog(
    onSample: (fill: (Double, Double) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onRecorded: (RecordedFence) -> Unit,
) {
    val points = remember { mutableStateListOf<Pair<Double, Double>>() }
    var lastFix by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val fence = remember(points.size) { FenceRecorder.record(points.toList()) }

    LaunchedEffect(Unit) {
        while (true) {
            onSample { fixLat, fixLon -> lastFix = fixLat to fixLon }
            delay(3_000)
        }
    }
    LaunchedEffect(lastFix) {
        val fix = lastFix ?: return@LaunchedEffect
        val previous = points.lastOrNull()
        // Only a real move is a new boundary point; a still phone must not add duplicates.
        if (previous == null || dev.hablock.app.domain.model.DwellTracker.distanceMeters(
                previous.first,
                previous.second,
                fix.first,
                fix.second,
            ) > 2.0
        ) {
            points.add(fix)
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.places_record_fence)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.places_record_hint), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.places_record_points, points.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (fence == null) {
                        stringResource(R.string.places_record_need_more)
                    } else {
                        stringResource(R.string.places_record_done_radius, fence.radiusMeters.toInt(), fence.samples)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { fence?.let(onRecorded) }, enabled = fence != null) {
                Text(stringResource(R.string.places_record_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
