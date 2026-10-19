@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text as TvText
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.ui.compose.ShizukuIcon
import rikka.shizuku.Shizuku

@Composable
fun TvLabMenuScreen(
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connectorEnabled by remember { mutableStateOf(ModuleSettings.isConnectorEnabled()) }
    var nightDogEnabled by remember { mutableStateOf(NightDogClient.isEnabled()) }
    var backgroundCheckEnabled by remember { mutableStateOf(ModuleSettings.isBackgroundCheckEnabled()) }
    var allowBackgroundActions by remember { mutableStateOf(ModuleSettings.allowBackgroundActions()) }
    var showUnsafeDialog by remember { mutableStateOf(false) }
    var showNightDogDialog by remember { mutableStateOf(false) }

    val isChecking by NightDogClient.isCheckingModules.collectAsState()
    val lastReport by NightDogClient.lastCheckReport.collectAsState()
    val watchdogStatus by NightDogClient.watchdogStatus.collectAsState()

    LaunchedEffect(Unit) {
        NightDogClient.refreshWatchdogStatus()
        if (lastReport == null) {
            NightDogClient.runBackgroundCheck(
                context = context,
                forceUpdateCheck = false,
                runServices = false
            )
        }
    }

    DisposableEffect(Unit) {
        val receivedListener = Shizuku.OnBinderReceivedListener {
            nightDogEnabled = NightDogClient.isEnabled()
            NightDogClient.refreshWatchdogStatus()
        }
        Shizuku.addBinderReceivedListener(receivedListener)
        onDispose {
            Shizuku.removeBinderReceivedListener(receivedListener)
        }
    }

    val statusSummary = when {
        isChecking -> stringResource(R.string.modules_background_check_running)
        lastReport != null -> {
            val report = lastReport!!
            val wdActive = watchdogStatus?.started == true
            val wdBound = watchdogStatus?.boundServicesCount ?: 0
            val wdRebinds = watchdogStatus?.totalRebinds ?: 0
            val wdPart = if (wdActive) {
                stringResource(R.string.nightdog_status_active_summary, wdBound, wdRebinds)
            } else {
                stringResource(R.string.nightdog_status_inactive_summary)
            }
            val modPart = stringResource(
                R.string.modules_background_check_report_summary,
                report.healthyModules,
                report.totalModules,
                report.updatesAvailableCount,
                report.issuesCount
            )
            "$wdPart • $modPart"
        }
        else -> stringResource(R.string.modules_background_check_never)
    }

    Row(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Column(
            modifier = Modifier
                .size(width = 300.dp, height = 500.dp)
                .padding(end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.lab_features_title),
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            TvLabButton(
                icon = R.drawable.ic_arrow_back_24,
                label = android.R.string.cancel,
                onClick = onNavigateUp
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TvText(
                    text = stringResource(R.string.nightdog_section_title),
                    style = TvMaterialTheme.typography.titleMedium,
                    color = TvMaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item {
                TvLabToggleCard(
                    title = stringResource(R.string.nightdog_title),
                    summary = stringResource(R.string.nightdog_summary),
                    checked = nightDogEnabled,
                    onToggle = {
                        if (!nightDogEnabled) showNightDogDialog = true
                        else {
                            NightDogClient.setEnabled(false)
                            nightDogEnabled = false
                        }
                    }
                )
            }

            item {
                TvLabToggleCard(
                    title = stringResource(R.string.modules_background_check_title),
                    summary = stringResource(R.string.modules_background_check_summary),
                    checked = backgroundCheckEnabled,
                    onToggle = {
                        val next = !backgroundCheckEnabled
                        backgroundCheckEnabled = next
                        ModuleSettings.setBackgroundCheckEnabled(next)
                        if (next) {
                            NightDogClient.ensurePeriodicLoop()
                        }
                    }
                )
            }

            item {
                TvLabToggleCard(
                    title = stringResource(R.string.modules_background_actions),
                    summary = stringResource(R.string.modules_background_actions_summary),
                    checked = allowBackgroundActions,
                    onToggle = {
                        val next = !allowBackgroundActions
                        allowBackgroundActions = next
                        ModuleSettings.setAllowBackgroundActions(next)
                    }
                )
            }

            item {
                TvLabActionCard(
                    title = stringResource(R.string.modules_background_check_run_now),
                    summary = statusSummary,
                    onClick = {
                        if (!isChecking) {
                            scope.launch {
                                val report = NightDogClient.runManualCheck(
                                    context = context,
                                    checkUpdates = true,
                                    forceRunServices = true
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.modules_background_check_toast,
                                        report.healthyModules,
                                        report.totalModules,
                                        report.updatesAvailableCount
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }

            item {
                TvText(
                    text = stringResource(R.string.lab_features_summary),
                    style = TvMaterialTheme.typography.titleMedium,
                    color = TvMaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                TvLabToggleCard(
                    title = stringResource(R.string.shizuku_connectors_title),
                    summary = stringResource(R.string.shizuku_connectors_summary),
                    checked = connectorEnabled,
                    onToggle = {
                        if (!connectorEnabled) showUnsafeDialog = true
                        else {
                            connectorEnabled = false
                            ModuleSettings.setConnectorEnabled(false)
                        }
                    }
                )
            }
        }
    }

    if (showUnsafeDialog) {
        AlertDialog(
            onDismissRequest = { showUnsafeDialog = false },
            title = { TvText(stringResource(R.string.unsafe_warning_title)) },
            text = { TvText(stringResource(R.string.unsafe_warning_message)) },
            confirmButton = {
                TvSurface(
                    onClick = {
                        showUnsafeDialog = false
                        connectorEnabled = true
                        ModuleSettings.setConnectorEnabled(true)
                    },
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.ok),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            dismissButton = {
                TvSurface(
                    onClick = { showUnsafeDialog = false },
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
            shape = TvMaterialTheme.shapes.extraLarge
        )
    }

    if (showNightDogDialog) {
        AlertDialog(
            onDismissRequest = { showNightDogDialog = false },
            title = { TvText(stringResource(R.string.nightdog_title)) },
            text = { TvText(stringResource(R.string.nightdog_description)) },
            confirmButton = {
                TvSurface(
                    onClick = {
                        showNightDogDialog = false
                        nightDogEnabled = true
                        NightDogClient.setEnabled(true)
                    },
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.ok),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            dismissButton = {
                TvSurface(
                    onClick = { showNightDogDialog = false },
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
            shape = TvMaterialTheme.shapes.extraLarge
        )
    }
}

@Composable
private fun TvLabToggleCard(
    title: String,
    summary: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    TvSurface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.large),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (checked) TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedContainerColor = if (checked) TvMaterialTheme.colorScheme.primaryContainer
            else TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TvText(
                    text = summary,
                    style = TvMaterialTheme.typography.bodyMedium,
                    color = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TvText(
                text = if (checked) "ON" else "OFF",
                style = TvMaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (checked) TvMaterialTheme.colorScheme.primary else TvMaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TvLabActionCard(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.large),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            TvText(
                text = title,
                style = TvMaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            TvText(
                text = summary,
                style = TvMaterialTheme.typography.bodyMedium,
                color = TvMaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TvLabButton(
    icon: Int,
    label: Int,
    onClick: () -> Unit
) {
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            TvText(text = stringResource(label), style = TvMaterialTheme.typography.labelLarge)
        }
    }
}
