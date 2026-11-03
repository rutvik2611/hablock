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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hablock.app.R
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
    var preset by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

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
                        "Gym" to false,
                        "Home" to true,
                        "Work" to false,
                    ).forEach { (label, isHome) ->
                        TextButton(onClick = { preset = label to isHome }) { Text(label) }
                    }
                    TextButton(onClick = { preset = "" to false }) { Text(stringResource(R.string.places_custom)) }
                }
            }
        }

        Spacer(Modifier.height(150.dp))
    }

    preset?.let { (label, isHome) ->
        AddPlaceDialog(
            initialName = label,
            initialHome = isHome,
            onDismiss = { preset = null },
            onSave = { name, lat, lon, radius, home ->
                viewModel.addPlace(name, lat, lon, radius, excluded = home)
                preset = null
            },
        )
    }
}

@Composable
private fun AddPlaceDialog(
    initialName: String,
    initialHome: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double, Double, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var radius by remember { mutableStateOf("150") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var home by remember { mutableStateOf(initialHome) }
    val latValue = lat.toDoubleOrNull()
    val lonValue = lon.toDoubleOrNull()
    val ready = latValue != null && lonValue != null &&
        latValue in -90.0..90.0 && lonValue in -180.0..180.0

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_location_add_gym)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_location_name)) },
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
                    listOf(5, 25, 100, 250, 500).forEach { preset ->
                        TextButton(onClick = { radius = preset.toString() }) { Text("$preset m") }
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
                onClick = { onSave(name, latValue ?: 0.0, lonValue ?: 0.0, radius.toDoubleOrNull() ?: 150.0, home) },
                enabled = ready,
            ) { Text(stringResource(R.string.settings_location_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
