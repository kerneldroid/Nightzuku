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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
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
            if (moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@LabFeaturesActivity)) {
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    TvLabMenuScreen(onNavigateUp = { finish() })
                }
                return@setContent
            }

            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var connectorEnabled by remember { mutableStateOf(ModuleSettings.isConnectorEnabled()) }
            var nightDogEnabled by remember { mutableStateOf(NightDogClient.isEnabled()) }
            var showUnsafeDialog by remember { mutableStateOf(false) }
            var showNightDogDialog by remember { mutableStateOf(false) }

            val watchdogStatus by NightDogClient.watchdogStatus.collectAsState()

            LaunchedEffect(Unit) {
                NightDogClient.refreshWatchdogStatus()
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

            fun refreshTelemetry() {
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
                                WearFilledTonalButton(
                                    onClick = { refreshTelemetry() },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        WearText(text = stringResource(R.string.home_refresh))
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
                                        count = 2,
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
                                    SettingsItem(
                                        index = 1,
                                        count = 2,
                                        icon = R.drawable.ic_server_restart,
                                        title = stringResource(R.string.home_refresh),
                                        summary = statusSummary,
                                        showChevron = false,
                                        onClick = { refreshTelemetry() }
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
