package dev.hablock.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hablock.app.R
import dev.hablock.app.domain.model.Block
import dev.hablock.app.ui.components.BreathingGateBadge
import dev.hablock.app.ui.components.GroupPosition
import dev.hablock.app.ui.components.GroupedListItem
import dev.hablock.app.ui.components.HablockDialog
import dev.hablock.app.ui.components.HablockIcons
import dev.hablock.app.ui.format.checkpointSummary
import dev.hablock.app.ui.format.formatWeekday
import dev.hablock.app.ui.gateViewModel
import dev.hablock.app.ui.wizard.BlockWizardSheet
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(addRequest: Int = 0, onAddHandled: () -> Unit = {}) {
    val viewModel = gateViewModel { container ->
        HomeViewModel(
            container.blockRepository,
            container.gateEngine,
            container.healthRepository,
            container.dayClock,
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var wizardTarget by remember { mutableStateOf<WizardTarget?>(null) }
    var actionsFor by remember { mutableStateOf<Block?>(null) }
    var pendingDelete by remember { mutableStateOf<Block?>(null) }
    var lockedAction by remember { mutableStateOf<LockedAction?>(null) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            wizardTarget = WizardTarget.New
            onAddHandled()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                subtitle = { Text(stringResource(R.string.home_subtitle, formatWeekday(uiState.today), checkpointSummary())) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    viewModel.refresh().join()
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()),
        ) {
            if (uiState.blocks.isEmpty() && !uiState.loading) {
                EmptyState(onAdd = { wizardTarget = WizardTarget.New })
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (uiState.hcProblem) {
                        item(key = "hc-problem") {
                            HealthConnectBanner(
                                permissions = viewModel.healthPermissions,
                                onGranted = { viewModel.refresh() },
                            )
                        }
                    }
                    val single = uiState.blocks.size == 1
                    items(uiState.blocks, key = { it.block.id }) { item ->
                        BlockCard(
                            block = item.block,
                            state = item.state,
                            expanded = single || item.block.id == expandedId,
                            onClick = {
                                if (single) {
                                    lockedAction = LockedAction.Edit(item.block)
                                } else {
                                    expandedId = if (expandedId == item.block.id) null else item.block.id
                                }
                            },
                            onToggle = { enabled ->
                                if (enabled) {
                                    viewModel.setEnabled(item.block, true)
                                } else {
                                    lockedAction = LockedAction.ToggleOff(item.block)
                                }
                            },
                            onLongPress = { actionsFor = item.block },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    wizardTarget?.let { target ->
        BlockWizardSheet(
            blockId = (target as? WizardTarget.Edit)?.blockId,
            onDismiss = { wizardTarget = null },
        )
    }

    actionsFor?.let { block ->
        BlockActionsSheet(
            block = block,
            onEdit = {
                actionsFor = null
                lockedAction = LockedAction.Edit(block)
            },
            onTogglePause = {
                actionsFor = null
                if (block.enabled) lockedAction = LockedAction.Pause(block) else viewModel.setEnabled(block, true)
            },
            onDelete = {
                actionsFor = null
                lockedAction = LockedAction.Delete(block)
            },
            onDismiss = { actionsFor = null },
        )
    }

    lockedAction?.let { action ->
        PasswordDialog(
            title = stringResource(R.string.home_password_title, action.block.name),
            message = stringResource(
                when (action) {
                    is LockedAction.Delete -> R.string.home_password_delete_body
                    is LockedAction.Edit -> R.string.home_password_edit_body
                    else -> R.string.home_password_body
                },
            ),
            confirmLabel = stringResource(R.string.home_password_confirm),
            verify = viewModel::codeAccepted,
            onConfirmed = {
                when (action) {
                    is LockedAction.ToggleOff -> viewModel.setEnabled(action.block, false)
                    is LockedAction.Pause -> viewModel.setEnabled(action.block, false)
                    is LockedAction.Delete -> viewModel.delete(action.block.id)
                    is LockedAction.Edit -> wizardTarget = WizardTarget.Edit(action.block.id)
                }
                lockedAction = null
            },
            onDismiss = { lockedAction = null },
        )
    }

    pendingDelete?.let { block ->
        HablockDialog(
            title = stringResource(R.string.home_delete_title, block.name),
            message = stringResource(R.string.home_delete_message),
            confirmLabel = stringResource(R.string.home_delete_confirm),
            destructive = true,
            onConfirm = {
                viewModel.delete(block.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

sealed interface WizardTarget {
    data object New : WizardTarget
    data class Edit(val blockId: String) : WizardTarget
}

@Composable
private fun HealthConnectBanner(permissions: Set<String>, onGranted: () -> Unit) {
    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { onGranted() }
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.home_hc_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = { healthLauncher.launch(permissions) }) { Text(stringResource(R.string.home_hc_fix)) }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BreathingGateBadge(Modifier.size(180.dp), color = MaterialTheme.colorScheme.primaryContainer)
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.headlineLargeEmphasized,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.home_empty_cta)) }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun BlockActionsSheet(
    block: Block,
    onEdit: () -> Unit,
    onTogglePause: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                block.name,
                Modifier.padding(start = 18.dp, bottom = 14.dp),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            GroupedListItem(
                position = GroupPosition.First,
                onClick = onEdit,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                title = stringResource(R.string.home_action_edit),
                leading = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            )
            GroupedListItem(
                position = GroupPosition.Middle,
                onClick = onTogglePause,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                title = stringResource(if (block.enabled) R.string.home_action_pause else R.string.home_action_resume),
                leading = { Icon(HablockIcons.Pause, contentDescription = null) },
            )
            GroupedListItem(
                position = GroupPosition.Last,
                onClick = onDelete,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.home_action_delete),
                leading = {
                    Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
            )
        }
    }

}

/** Rule-changing actions on a block. All of them require the household password. */
sealed interface LockedAction {
    val block: Block

    data class ToggleOff(override val block: Block) : LockedAction
    data class Pause(override val block: Block) : LockedAction
    data class Delete(override val block: Block) : LockedAction

    /** Opening the editor is destructive: the wizard can empty a block and save it. */
    data class Edit(override val block: Block) : LockedAction
}

@Composable
private fun PasswordDialog(
    title: String,
    message: String,
    confirmLabel: String,
    verify: (String) -> Boolean,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; wrong = false },
                    label = { Text(stringResource(R.string.home_password_label)) },
                    singleLine = true,
                    isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (wrong) {
                        { Text(stringResource(R.string.home_password_wrong)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (verify(code)) onConfirmed() else wrong = true }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
