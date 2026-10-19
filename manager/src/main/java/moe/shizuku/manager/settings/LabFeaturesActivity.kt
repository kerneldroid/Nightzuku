package moe.shizuku.manager.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.SwitchButton as WearSwitchButton
import androidx.wear.compose.material3.Text as WearText
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.ui.compose.SettingsItem
import moe.shizuku.manager.ui.compose.SettingsSection
import moe.shizuku.manager.ui.compose.SettingsSwitchItem
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import rikka.shizuku.Shizuku

class LabFeaturesActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
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

            fun triggerManualCheck() {
                if (isChecking) return
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

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@LabFeaturesActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                        androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                moe.shizuku.manager.ui.compose.WearScreenTitle(
                                    icon = Icons.Rounded.Code,
                                    title = stringResource(R.string.lab_features_title)
                                )
                            }
                            item {
                                WearSwitchButton(
                                    checked = nightDogEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            showNightDogDialog = true
                                        } else {
                                            NightDogClient.setEnabled(false)
                                            nightDogEnabled = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        WearText(text = stringResource(R.string.nightdog_title))
                                    },
                                    secondaryLabel = {
                                        WearText(text = stringResource(R.string.nightdog_summary))
                                    },
                                    icon = {
                                        WearIcon(
                                            imageVector = Icons.Rounded.Shield,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            item {
                                WearSwitchButton(
                                    checked = backgroundCheckEnabled,
                                    onCheckedChange = { enabled ->
                                        backgroundCheckEnabled = enabled
                                        ModuleSettings.setBackgroundCheckEnabled(enabled)
                                        if (enabled) {
                                            NightDogClient.ensurePeriodicLoop()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        WearText(text = stringResource(R.string.modules_background_check_title))
                                    },
                                    secondaryLabel = {
                                        WearText(text = stringResource(R.string.modules_background_check_summary))
                                    },
                                    icon = {
                                        WearIcon(
                                            imageVector = Icons.Rounded.TaskAlt,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            item {
                                WearSwitchButton(
                                    checked = allowBackgroundActions,
                                    onCheckedChange = { enabled ->
                                        allowBackgroundActions = enabled
                                        ModuleSettings.setAllowBackgroundActions(enabled)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        WearText(text = stringResource(R.string.modules_background_actions))
                                    },
                                    secondaryLabel = {
                                        WearText(text = stringResource(R.string.modules_background_actions_summary))
                                    },
                                    icon = {
                                        WearIcon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            item {
                                WearFilledTonalButton(
                                    onClick = { triggerManualCheck() },
                                    enabled = !isChecking,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        WearText(text = stringResource(R.string.modules_background_check_run_now))
                                    },
                                    secondaryLabel = {
                                        WearText(text = statusSummary)
                                    },
                                    icon = {
                                        WearIcon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                            item {
                                WearSwitchButton(
                                    checked = connectorEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            showUnsafeDialog = true
                                        } else {
                                            connectorEnabled = false
                                            ModuleSettings.setConnectorEnabled(false)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        WearText(text = stringResource(R.string.shizuku_connectors_title))
                                    },
                                    secondaryLabel = {
                                        WearText(text = stringResource(R.string.shizuku_connectors_summary))
                                    },
                                    icon = {
                                        WearIcon(
                                            painter = painterResource(R.drawable.ic_baseline_link_24),
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (showUnsafeDialog) {
                        WearAlertDialog(
                            visible = true,
                            onDismissRequest = { showUnsafeDialog = false },
                            title = { WearText(stringResource(R.string.unsafe_warning_title)) },
                            text = { WearText(stringResource(R.string.unsafe_warning_message)) },
                            confirmButton = {
                                WearButton(onClick = {
                                    showUnsafeDialog = false
                                    connectorEnabled = true
                                    ModuleSettings.setConnectorEnabled(true)
                                }) {
                                    WearText(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                WearFilledTonalButton(onClick = { showUnsafeDialog = false }) {
                                    WearText(stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }

                    if (showNightDogDialog) {
                        WearAlertDialog(
                            visible = true,
                            onDismissRequest = { showNightDogDialog = false },
                            title = { WearText(stringResource(R.string.nightdog_title)) },
                            text = { WearText(stringResource(R.string.nightdog_description)) },
                            confirmButton = {
                                WearButton(onClick = {
                                    showNightDogDialog = false
                                    NightDogClient.setEnabled(true)
                                    nightDogEnabled = true
                                }) {
                                    WearText(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                WearFilledTonalButton(onClick = { showNightDogDialog = false }) {
                                    WearText(stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }
                }
            } else {
                ShizukuExpressiveTheme {
                    ShizukuLazyScaffold(
                        title = stringResource(R.string.lab_features_title),
                        onNavigateUp = { finish() }
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SettingsSection(stringResource(R.string.nightdog_section_title)) {
                                    SettingsSwitchItem(
                                        index = 0,
                                        count = 4,
                                        icon = R.drawable.ic_shield_24,
                                        title = stringResource(R.string.nightdog_title),
                                        summary = stringResource(R.string.nightdog_summary),
                                        checked = nightDogEnabled,
                                        onCheckedChange = { enabled ->
                                            if (enabled) {
                                                showNightDogDialog = true
                                            } else {
                                                NightDogClient.setEnabled(false)
                                                nightDogEnabled = false
                                            }
                                        }
                                    )
                                    SettingsSwitchItem(
                                        index = 1,
                                        count = 4,
                                        icon = R.drawable.ic_server_ok_24dp,
                                        title = stringResource(R.string.modules_background_check_title),
                                        summary = stringResource(R.string.modules_background_check_summary),
                                        checked = backgroundCheckEnabled,
                                        onCheckedChange = { enabled ->
                                            backgroundCheckEnabled = enabled
                                            ModuleSettings.setBackgroundCheckEnabled(enabled)
                                            if (enabled) {
                                                NightDogClient.ensurePeriodicLoop()
                                            }
                                        }
                                    )
                                    SettingsSwitchItem(
                                        index = 2,
                                        count = 4,
                                        icon = R.drawable.ic_terminal_24,
                                        title = stringResource(R.string.modules_background_actions),
                                        summary = stringResource(R.string.modules_background_actions_summary),
                                        checked = allowBackgroundActions,
                                        onCheckedChange = { enabled ->
                                            allowBackgroundActions = enabled
                                            ModuleSettings.setAllowBackgroundActions(enabled)
                                        }
                                    )
                                    SettingsItem(
                                        index = 3,
                                        count = 4,
                                        icon = R.drawable.ic_server_restart,
                                        title = stringResource(R.string.modules_background_check_run_now),
                                        summary = statusSummary,
                                        showChevron = false,
                                        onClick = { triggerManualCheck() }
                                    )
                                }

                                Spacer(Modifier.height(4.dp))

                                SettingsSection(stringResource(R.string.lab_features_summary)) {
                                    SettingsSwitchItem(
                                        index = 0,
                                        count = 1,
                                        icon = R.drawable.ic_baseline_link_24,
                                        title = stringResource(R.string.shizuku_connectors_title),
                                        summary = stringResource(R.string.shizuku_connectors_summary),
                                        checked = connectorEnabled,
                                        onCheckedChange = { enabled ->
                                            if (enabled) {
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
                    }

                    if (showUnsafeDialog) {
                        AlertDialog(
                            onDismissRequest = { showUnsafeDialog = false },
                            title = { Text(stringResource(R.string.unsafe_warning_title)) },
                            text = { Text(stringResource(R.string.unsafe_warning_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showUnsafeDialog = false
                                    connectorEnabled = true
                                    ModuleSettings.setConnectorEnabled(true)
                                }) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showUnsafeDialog = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }

                    if (showNightDogDialog) {
                        AlertDialog(
                            onDismissRequest = { showNightDogDialog = false },
                            title = { Text(stringResource(R.string.nightdog_title)) },
                            text = { Text(stringResource(R.string.nightdog_description)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showNightDogDialog = false
                                    NightDogClient.setEnabled(true)
                                    nightDogEnabled = true
                                }) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNightDogDialog = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
