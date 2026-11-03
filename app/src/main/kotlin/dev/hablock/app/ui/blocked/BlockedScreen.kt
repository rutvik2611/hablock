package dev.hablock.app.ui.blocked

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hablock.app.R
import dev.hablock.app.domain.model.ConditionProgress
import dev.hablock.app.domain.model.GateState
import dev.hablock.app.ui.components.AppIconCookie
import dev.hablock.app.ui.components.ConditionMeter
import dev.hablock.app.ui.components.GroupedListItem
import dev.hablock.app.ui.components.ShapeBurst
import dev.hablock.app.ui.components.ShapeShiftingBadge
import dev.hablock.app.ui.components.groupPositionOf
import dev.hablock.app.ui.format.formatClock
import dev.hablock.app.ui.gateViewModel
import kotlinx.coroutines.launch

/** Upper bound on a single manual unlock, so a slip of the finger cannot mean "forever". */
private const val MAX_OVERRIDE_MINUTES = 720

/**
 * The screen that decides whether Hablock feels supportive or smug. Rule: specific and
 * actionable, matter-of-fact about the lock, never scolding.
 */
@Composable
fun BlockedScreen(
    blockId: String,
    packageName: String?,
    onClose: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val viewModel = gateViewModel(key = blockId) { container ->
        BlockedViewModel(
            container.blockRepository,
            container.gateEngine,
            container.healthRepository,
            blockId,
            container.sessionDuration.inWholeMinutes.toInt(),
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appLabel = packageName?.let { uiState.block?.blockedLabels?.get(it) ?: it.substringAfterLast('.') }
        ?: uiState.block?.name.orEmpty()
    val override = uiState.state as? GateState.Override
    val open = uiState.state is GateState.Open || override != null
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var wrongCode by remember { mutableStateOf(false) }
    var badMinutes by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshHcStatus()
        onPauseOrDispose {}
    }
    LaunchedEffect(uiState.state) {
        if (uiState.state is GateState.SessionActive || uiState.state is GateState.Override) onClose()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    scope.launch {
                        viewModel.refresh().join()
                        isRefreshing = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(28.dp))
                    if (packageName != null) {
                        AppIconCookie(packageName, appLabel, size = 56.dp, contentDescription = appLabel)
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(
                        stringResource(
                            if (open) R.string.blocked_headline_open else R.string.blocked_headline_locked,
                            appLabel,
                        ),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        textAlign = TextAlign.Center,
                    )
                    if (override != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.blocked_unlock_until, formatClock(override.until)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Box(contentAlignment = Alignment.Center) {
                        ShapeShiftingBadge(
                            modifier = Modifier.size(170.dp),
                            color = if (open) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        ) {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(88.dp),
                                tint = if (open) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                            )
                        }
                        ShapeBurst(
                            trigger = if (open) "open" else null,
                            modifier = Modifier.size(280.dp),
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    ConditionGroup(uiState.progress)
                    if (uiState.hcProblem) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.blocked_hc_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    if (!open) {
                        TextButton(onClick = { showUnlock = true }) {
                            Text(stringResource(R.string.blocked_unlock_button))
                        }
                    }
                }
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = { packageName?.let(onOpenApp) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.blocked_open_button,
                                viewModel.sessionMinutes,
                                appLabel,
                                viewModel.sessionMinutes,
                            ),
                        )
                    }
                }
            }
        }
    }
    if (showUnlock) {
        fun submit(minutes: Int) {
            when {
                !viewModel.codeAccepted(code) -> wrongCode = true
                minutes !in 1..MAX_OVERRIDE_MINUTES -> badMinutes = true
                else -> {
                    viewModel.grantOverride(minutes)
                    showUnlock = false
                    code = ""
                    minutesText = ""
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showUnlock = false; code = ""; minutesText = ""; wrongCode = false; badMinutes = false },
            title = { Text(stringResource(R.string.blocked_unlock_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.blocked_unlock_body))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; wrongCode = false },
                        label = { Text(stringResource(R.string.blocked_unlock_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                        ),
                        isError = wrongCode,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = if (wrongCode) {
                            { Text(stringResource(R.string.blocked_unlock_wrong)) }
                        } else {
                            null
                        },
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter(Char::isDigit); badMinutes = false },
                        label = { Text(stringResource(R.string.blocked_unlock_minutes_label)) },
                        singleLine = true,
                        isError = badMinutes,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = if (badMinutes) {
                            { Text(stringResource(R.string.blocked_unlock_bad_minutes)) }
                        } else {
                            null
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1, 2, 5, 15).forEach { preset ->
                            TextButton(onClick = { minutesText = preset.toString(); badMinutes = false }) {
                                Text(stringResource(R.string.blocked_unlock_preset_min, preset))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(30, 60, 120).forEach { preset ->
                            TextButton(onClick = { minutesText = preset.toString(); badMinutes = false }) {
                                Text(stringResource(R.string.blocked_unlock_preset_min, preset))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { submit(minutesText.toIntOrNull() ?: 0) }) {
                    Text(stringResource(R.string.blocked_unlock_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlock = false; code = ""; minutesText = ""; wrongCode = false; badMinutes = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    BackHandler { onClose() }
}

@Composable
private fun ConditionGroup(progress: List<ConditionProgress>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        progress.forEachIndexed { index, item ->
            GroupedListItem(position = groupPositionOf(index, progress.size)) {
                ConditionMeter(item.condition, item.current, item.required)
            }
        }
    }
}
