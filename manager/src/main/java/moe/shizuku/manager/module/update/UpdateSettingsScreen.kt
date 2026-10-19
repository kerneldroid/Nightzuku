@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.module.update

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.catalog.TokenStore
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.ui.compose.SettingsItem
import moe.shizuku.manager.ui.compose.SettingsSection
import moe.shizuku.manager.ui.compose.SettingsSwitchItem
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold

@Composable
fun UpdateSettingsScreen(
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backgroundCheckEnabled by remember { mutableStateOf(ModuleSettings.isBackgroundCheckEnabled()) }
    var allowBackgroundActions by remember { mutableStateOf(ModuleSettings.allowBackgroundActions()) }
    var catalogEnabled by remember { mutableStateOf(ModuleSettings.isCatalogEnabled()) }
    var updateFrequency by remember { mutableStateOf(ModuleSettings.getUpdateFrequency()) }
    var autoUpdateEnabled by remember { mutableStateOf(ModuleSettings.isAutoUpdateEnabled()) }
    var wifiOnly by remember { mutableStateOf(ModuleSettings.isUpdateWifiOnly()) }
    var notificationsEnabled by remember { mutableStateOf(ModuleSettings.isUpdateNotificationsEnabled()) }
    var installMode by remember { mutableStateOf(ModuleSettings.getInstallMode()) }
    var githubPat by remember { mutableStateOf(TokenStore.getToken(context) ?: "") }
    var showPatDialog by remember { mutableStateOf(false) }

    val isChecking by NightDogClient.isCheckingModules.collectAsState()
    val lastReport by NightDogClient.lastCheckReport.collectAsState()

    val checkSummary = when {
        isChecking -> stringResource(R.string.modules_background_check_running)
        lastReport != null -> {
            val report = lastReport!!
            stringResource(
                R.string.modules_background_check_report_summary,
                report.healthyModules,
                report.totalModules,
                report.updatesAvailableCount,
                report.issuesCount
            )
        }
        else -> stringResource(R.string.modules_background_check_never)
    }

    ShizukuExpressiveTheme {
        ShizukuLazyScaffold(
            title = stringResource(R.string.update_settings_title),
            onNavigateUp = onNavigateUp
        ) {
            item {
                SettingsSection(title = stringResource(R.string.nightdog_section_title)) {
                    SettingsSwitchItem(
                        index = 0,
                        count = 3,
                        icon = R.drawable.ic_shield_24,
                        title = stringResource(R.string.modules_background_check_title),
                        summary = stringResource(R.string.modules_background_check_summary),
                        checked = backgroundCheckEnabled,
                        onCheckedChange = {
                            backgroundCheckEnabled = it
                            ModuleSettings.setBackgroundCheckEnabled(it)
                            if (it) {
                                NightDogClient.ensurePeriodicLoop()
                            }
                        }
                    )
                    SettingsSwitchItem(
                        index = 1,
                        count = 3,
                        icon = R.drawable.ic_terminal_24,
                        title = stringResource(R.string.modules_background_actions),
                        summary = stringResource(R.string.modules_background_actions_summary),
                        checked = allowBackgroundActions,
                        onCheckedChange = {
                            allowBackgroundActions = it
                            ModuleSettings.setAllowBackgroundActions(it)
                        }
                    )
                    SettingsItem(
                        index = 2,
                        count = 3,
                        icon = R.drawable.ic_server_restart,
                        title = stringResource(R.string.modules_background_check_run_now),
                        summary = checkSummary,
                        showChevron = false,
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
            }

            item {
                Spacer(Modifier.height(8.dp))
            }

            item {
                SettingsSection(title = stringResource(R.string.update_settings_frequency)) {
                    UpdateFrequencyDropdown(
                        index = 0,
                        count = 4,
                        selected = updateFrequency,
                        onSelect = {
                            updateFrequency = it
                            ModuleSettings.setUpdateFrequency(it)
                        }
                    )
                    SettingsSwitchItem(
                        index = 1,
                        count = 4,
                        icon = R.drawable.ic_outline_arrow_upward_24,
                        title = stringResource(R.string.update_settings_auto_update),
                        summary = stringResource(R.string.update_settings_auto_update_summary),
                        checked = autoUpdateEnabled,
                        onCheckedChange = {
                            autoUpdateEnabled = it
                            ModuleSettings.setAutoUpdateEnabled(it)
                        }
                    )
                    SettingsSwitchItem(
                        index = 2,
                        count = 4,
                        icon = R.drawable.ic_settings_outline_24dp,
                        title = stringResource(R.string.update_settings_wifi_only),
                        summary = stringResource(R.string.update_settings_wifi_only_summary),
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            ModuleSettings.setUpdateWifiOnly(it)
                        }
                    )
                    SettingsSwitchItem(
                        index = 3,
                        count = 4,
                        icon = R.drawable.ic_outline_notifications_active_24,
                        title = stringResource(R.string.update_settings_notifications),
                        summary = stringResource(R.string.update_settings_notifications_summary),
                        checked = notificationsEnabled,
                        onCheckedChange = {
                            notificationsEnabled = it
                            ModuleSettings.setUpdateNotificationsEnabled(it)
                        }
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
            }

            item {
                SettingsSection(title = stringResource(R.string.update_settings_install_mode)) {
                    InstallModeDropdown(
                        selected = installMode,
                        onSelect = {
                            installMode = it
                            ModuleSettings.setInstallMode(it)
                        }
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
            }

            item {
                SettingsSection(title = stringResource(R.string.update_settings_catalog)) {
                    SettingsSwitchItem(
                        index = 0,
                        count = 1,
                        icon = R.drawable.ic_outline_store_24,
                        title = stringResource(R.string.update_settings_catalog_enabled),
                        summary = stringResource(R.string.update_settings_catalog_enabled_summary),
                        checked = catalogEnabled,
                        onCheckedChange = {
                            catalogEnabled = it
                            ModuleSettings.setCatalogEnabled(it)
                        }
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
            }

            item {
                val githubItemCount = 1 + (if (githubPat.isNotBlank()) 1 else 0)
                SettingsSection(title = stringResource(R.string.update_settings_github)) {
                    SettingsItem(
                        index = 0,
                        count = githubItemCount,
                        icon = R.drawable.ic_baseline_link_24,
                        title = stringResource(R.string.update_settings_github_pat),
                        summary = if (githubPat.isNotBlank()) {
                            stringResource(R.string.update_settings_github_pat_set)
                        } else {
                            stringResource(R.string.update_settings_github_pat_unset)
                        },
                        onClick = { showPatDialog = true }
                    )
                    if (githubPat.isNotBlank()) {
                        SettingsItem(
                            index = 1,
                            count = githubItemCount,
                            icon = R.drawable.ic_close_24,
                            title = stringResource(R.string.update_settings_github_pat_delete),
                            summary = stringResource(R.string.update_settings_github_pat_delete_summary),
                            showChevron = false,
                            onClick = {
                                githubPat = ""
                                TokenStore.clearToken(context)
                            }
                        )
                    }
                }
            }
        }

        if (showPatDialog) {
            PatInputDialog(
                initialPat = githubPat,
                onDismiss = { showPatDialog = false },
                onConfirm = { pat ->
                    githubPat = pat
                    if (pat.isBlank()) {
                        TokenStore.clearToken(context)
                    } else {
                        TokenStore.setToken(context, pat)
                    }
                    showPatDialog = false
                }
            )
        }
    }
}

@Composable
private fun UpdateFrequencyDropdown(
    index: Int,
    count: Int,
    selected: ModuleSettings.UpdateFrequency,
    onSelect: (ModuleSettings.UpdateFrequency) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        SettingsItem(
            index = index,
            count = count,
            icon = R.drawable.ic_outline_notifications_active_24,
            title = stringResource(R.string.update_settings_frequency_label),
            summary = stringResource(
                when (selected) {
                    ModuleSettings.UpdateFrequency.MANUAL -> R.string.update_settings_frequency_manual
                    ModuleSettings.UpdateFrequency.ON_START -> R.string.update_settings_frequency_on_start
                    ModuleSettings.UpdateFrequency.SIX_HOURS -> R.string.update_settings_frequency_6h
                    ModuleSettings.UpdateFrequency.DAILY -> R.string.update_settings_frequency_daily
                    ModuleSettings.UpdateFrequency.WEEKLY -> R.string.update_settings_frequency_weekly
                }
            ),
            showChevron = false,
            onClick = { expanded = true },
            trailing = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_settings_frequency_manual)) },
                onClick = {
                    onSelect(ModuleSettings.UpdateFrequency.MANUAL)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_settings_frequency_on_start)) },
                onClick = {
                    onSelect(ModuleSettings.UpdateFrequency.ON_START)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_settings_frequency_6h)) },
                onClick = {
                    onSelect(ModuleSettings.UpdateFrequency.SIX_HOURS)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_settings_frequency_daily)) },
                onClick = {
                    onSelect(ModuleSettings.UpdateFrequency.DAILY)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_settings_frequency_weekly)) },
                onClick = {
                    onSelect(ModuleSettings.UpdateFrequency.WEEKLY)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun InstallModeDropdown(
    selected: ModuleSettings.InstallMode,
    onSelect: (ModuleSettings.InstallMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        SettingsItem(
            index = 0,
            count = 1,
            icon = R.drawable.ic_outline_arrow_upward_24,
            title = stringResource(R.string.update_settings_install_mode_label),
            summary = stringResource(
                when (selected) {
                    ModuleSettings.InstallMode.SOURCES -> R.string.update_settings_install_mode_sources
                    ModuleSettings.InstallMode.RELEASE -> R.string.update_settings_install_mode_release
                }
            ),
            showChevron = false,
            onClick = { expanded = true },
            trailing = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_settings_install_mode_sources)) },
                onClick = {
                    onSelect(ModuleSettings.InstallMode.SOURCES)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_settings_install_mode_release)) },
                onClick = {
                    onSelect(ModuleSettings.InstallMode.RELEASE)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun PatInputDialog(
    initialPat: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pat by remember { mutableStateOf(initialPat) }
    var showPat by remember { mutableStateOf(false) }
    val showWarning = pat.isNotBlank() && !TokenStore.isValidTokenFormat(pat)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_settings_github_pat_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.update_settings_github_pat_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pat,
                    onValueChange = { pat = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.update_settings_github_pat_label)) },
                    visualTransformation = if (showPat) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true
                )
                if (showWarning) {
                    Text(
                        text = stringResource(R.string.modules_catalog_token_format_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showPat = !showPat }) {
                        Text(
                            text = stringResource(
                                if (showPat) R.string.update_settings_github_pat_hide
                                else R.string.update_settings_github_pat_show
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pat = "" }) {
                        Text(stringResource(R.string.update_settings_github_pat_clear))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pat) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
