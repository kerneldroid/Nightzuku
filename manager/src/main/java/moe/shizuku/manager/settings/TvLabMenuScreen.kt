@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.ui.compose.TvActionButton
import moe.shizuku.manager.ui.compose.TvActionCard
import moe.shizuku.manager.ui.compose.TvBackButton
import moe.shizuku.manager.ui.compose.TvDialog
import moe.shizuku.manager.ui.compose.TvSecondaryButton
import moe.shizuku.manager.ui.compose.TvToggleCard
import moe.shizuku.manager.ui.compose.TvTwoPaneLayout
import rikka.shizuku.Shizuku

@Composable
fun TvLabMenuScreen(
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connectorEnabled by remember { mutableStateOf(ModuleSettings.isConnectorEnabled()) }
    var nightDogEnabled by remember { mutableStateOf(NightDogClient.isEnabled()) }
    var showUnsafeDialog by remember { mutableStateOf(false) }
    var showNightDogDialog by remember { mutableStateOf(false) }

    val nightDogFocusRequester = remember { FocusRequester() }
    val connectorFocusRequester = remember { FocusRequester() }
    var lastDialogTrigger by remember { mutableStateOf<FocusRequester?>(null) }

    val watchdogStatus by NightDogClient.watchdogStatus.collectAsState()

    LaunchedEffect(Unit) {
        NightDogClient.refreshWatchdogStatus()
        runCatching { nightDogFocusRequester.requestFocus() }
    }

    val anyDialogOpen = showUnsafeDialog || showNightDogDialog
    LaunchedEffect(anyDialogOpen) {
        if (!anyDialogOpen) {
            val target = lastDialogTrigger
            if (target != null) {
                runCatching { target.requestFocus() }
                lastDialogTrigger = null
            }
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

    val statusSummary = if (watchdogStatus?.started == true) {
        stringResource(
            R.string.nightdog_status_active_summary,
            watchdogStatus?.boundServicesCount ?: 0,
            watchdogStatus?.totalRebinds ?: 0
        )
    } else {
        stringResource(R.string.nightdog_status_inactive_summary)
    }

    TvTwoPaneLayout(
        title = stringResource(R.string.lab_features_title),
        sidebarContent = {
            TvBackButton(onClick = onNavigateUp)
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 32.dp, end = 32.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TvText(
                    text = stringResource(R.string.nightdog_section_title),
                    style = TvMaterialTheme.typography.titleMedium,
                    color = TvMaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item {
                TvToggleCard(
                    title = stringResource(R.string.nightdog_title),
                    summary = stringResource(R.string.nightdog_summary),
                    checked = nightDogEnabled,
                    icon = R.drawable.ic_shield_24,
                    shape = TvMaterialTheme.shapes.large,
                    modifier = Modifier.focusRequester(nightDogFocusRequester),
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            lastDialogTrigger = nightDogFocusRequester
                            showNightDogDialog = true
                        } else {
                            NightDogClient.setEnabled(false)
                            nightDogEnabled = false
                        }
                    }
                )
            }

            item {
                TvLabActionCard(
                    title = stringResource(R.string.home_refresh),
                    summary = statusSummary,
                    onClick = {
                        scope.launch {
                            val status = NightDogClient.refreshWatchdogStatus()
                            val msg = if (status?.started == true) {
                                context.getString(
                                    R.string.nightdog_status_active_summary,
                                    status.boundServicesCount,
                                    status.totalRebinds
                                )
                            } else {
                                context.getString(R.string.nightdog_status_inactive_summary)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            item {
                TvText(
                    text = stringResource(R.string.lab_features_summary),
                    style = TvMaterialTheme.typography.titleMedium,
                    color = TvMaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                TvToggleCard(
                    title = stringResource(R.string.shizuku_connectors_title),
                    summary = stringResource(R.string.shizuku_connectors_summary),
                    checked = connectorEnabled,
                    icon = R.drawable.ic_baseline_link_24,
                    shape = TvMaterialTheme.shapes.large,
                    modifier = Modifier.focusRequester(connectorFocusRequester),
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            lastDialogTrigger = connectorFocusRequester
                            showUnsafeDialog = true
                        } else {
                            connectorEnabled = false
                            ModuleSettings.setConnectorEnabled(false)
                        }
                    }
                )
            }
        }
    }

    if (showUnsafeDialog) {
        val cancelFocusRequester = remember { FocusRequester() }
        TvDialog(
            onDismissRequest = { showUnsafeDialog = false },
            title = { TvText(stringResource(R.string.unsafe_warning_title)) },
            text = { TvText(stringResource(R.string.unsafe_warning_message)) },
            initialFocusRequester = cancelFocusRequester,
            confirmButton = {
                TvActionButton(
                    onClick = {
                        showUnsafeDialog = false
                        connectorEnabled = true
                        ModuleSettings.setConnectorEnabled(true)
                    }
                ) {
                    TvText(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TvSecondaryButton(
                    onClick = { showUnsafeDialog = false },
                    modifier = Modifier.focusRequester(cancelFocusRequester)
                ) {
                    TvText(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showNightDogDialog) {
        val confirmFocusRequester = remember { FocusRequester() }
        TvDialog(
            onDismissRequest = { showNightDogDialog = false },
            title = { TvText(stringResource(R.string.nightdog_title)) },
            text = { TvText(stringResource(R.string.nightdog_description)) },
            initialFocusRequester = confirmFocusRequester,
            confirmButton = {
                TvActionButton(
                    onClick = {
                        showNightDogDialog = false
                        nightDogEnabled = true
                        NightDogClient.setEnabled(true)
                    },
                    modifier = Modifier.focusRequester(confirmFocusRequester)
                ) {
                    TvText(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TvSecondaryButton(onClick = { showNightDogDialog = false }) {
                    TvText(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TvLabActionCard(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    TvActionCard(
        onClick = onClick,
        shape = TvMaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
