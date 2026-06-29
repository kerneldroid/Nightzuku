package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.SwitchButton as WearSwitchButton
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.ui.compose.SettingsGroup
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.SwitchSettingsRow
import moe.shizuku.tapi.TapiAutoGrant
import moe.shizuku.tapi.TapiSettings
import rikka.shizuku.Shizuku

class LabFeaturesActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var connectorEnabled by remember { mutableStateOf(ModuleSettings.isConnectorEnabled()) }
            var nightDogEnabled by remember { mutableStateOf(try { Shizuku.getNightDogEnabled() } catch (_: Throwable) { false }) }
            var showUnsafeDialog by remember { mutableStateOf(false) }
            var showNightDogDialog by remember { mutableStateOf(false) }
            var tapiEnabled by remember { mutableStateOf(TapiSettings.isEnabled()) }
            var showTapiDialog by remember { mutableStateOf(false) }

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
                            item {
                                WearSwitchButton(
                                    checked = nightDogEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            showNightDogDialog = true
                                        } else {
                                            Shizuku.setNightDogEnabled(false)
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
                                            painter = painterResource(R.drawable.ic_baseline_link_24),
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            item {
                                WearSwitchButton(
                                    checked = tapiEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            showTapiDialog = true
                                        } else {
                                            TapiSettings.setEnabled(false)
                                            tapiEnabled = false
                                            TapiAutoGrant.onLabFeatureDisabled(this@LabFeaturesActivity)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        WearText(text = stringResource(R.string.tapi_title))
                                    },
                                    secondaryLabel = {
                                        WearText(text = stringResource(R.string.tapi_summary))
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
                                    nightDogEnabled = true
                                    Shizuku.setNightDogEnabled(true)
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

                    if (showTapiDialog) {
                        WearAlertDialog(
                            visible = true,
                            onDismissRequest = { showTapiDialog = false },
                            title = { WearText(stringResource(R.string.tapi_title)) },
                            text = { WearText(stringResource(R.string.tapi_description)) },
                            confirmButton = {
                                WearButton(onClick = {
                                    showTapiDialog = false
                                    TapiSettings.setEnabled(true)
                                    tapiEnabled = true
                                    TapiAutoGrant.onLabFeatureEnabled(this@LabFeaturesActivity)
                                }) {
                                    WearText(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                WearFilledTonalButton(onClick = { showTapiDialog = false }) {
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
                            SettingsGroup(title = stringResource(R.string.lab_features_summary)) {
                                SwitchSettingsRow(
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
                                SwitchSettingsRow(
                                    icon = R.drawable.ic_baseline_link_24,
                                    title = stringResource(R.string.nightdog_title),
                                    summary = stringResource(R.string.nightdog_summary),
                                    checked = nightDogEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            showNightDogDialog = true
                                        } else {
                                            Shizuku.setNightDogEnabled(false)
                                            nightDogEnabled = false
                                        }
                                    }
                                )
                                SwitchSettingsRow(
                                    icon = R.drawable.ic_baseline_link_24,
                                    title = stringResource(R.string.tapi_title),
                                    summary = stringResource(R.string.tapi_summary),
                                    checked = tapiEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            showTapiDialog = true
                                        } else {
                                            TapiSettings.setEnabled(false)
                                            tapiEnabled = false
                                            TapiAutoGrant.onLabFeatureDisabled(this@LabFeaturesActivity)
                                        }
                                    }
                                )
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
                                    nightDogEnabled = true
                                    Shizuku.setNightDogEnabled(true)
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

                    if (showTapiDialog) {
                        AlertDialog(
                            onDismissRequest = { showTapiDialog = false },
                            title = { Text(stringResource(R.string.tapi_title)) },
                            text = { Text(stringResource(R.string.tapi_description)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showTapiDialog = false
                                    TapiSettings.setEnabled(true)
                                    tapiEnabled = true
                                    TapiAutoGrant.onLabFeatureEnabled(this@LabFeaturesActivity)
                                }) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTapiDialog = false }) {
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
