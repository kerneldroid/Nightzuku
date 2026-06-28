@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.CheckboxButton as WearCheckboxButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.RadioButton as WearRadioButton
import androidx.wear.compose.material3.Text as WearText
import moe.shizuku.manager.R
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.ui.compose.GroupDivider
import moe.shizuku.manager.ui.compose.SettingsRow
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.SwitchSettingsRow

internal data class ChoiceOption(
    val title: String,
    val summary: String? = null,
    @param:androidx.annotation.DrawableRes val icon: Int? = null
)

@Composable
internal fun WearChoiceDialog(
    title: String,
    choices: List<ChoiceOption>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    moe.shizuku.manager.ui.compose.WearShizukuTheme {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(WearMaterialTheme.colorScheme.background)) {
            moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                    state = state,
                    contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                item {
                    WearText(
                        text = title,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        color = WearMaterialTheme.colorScheme.primary,
                        style = WearMaterialTheme.typography.titleMedium
                    )
                }
                for (index in choices.indices) {
                    val choiceTitle = choices[index].title
                    val isSelected = index == selectedIndex
                    item {
                        WearRadioButton(
                            selected = isSelected,
                            onSelect = { onSelect(index) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { WearText(choiceTitle) }
                        )
                    }
                }
                item {
                    WearFilledTonalButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        WearText(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun WearCustomPermissionsDialog(
    value: ModuleSettings.CustomPermissions,
    onDismiss: () -> Unit,
    onSave: (ModuleSettings.CustomPermissions) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value) }
    val title = stringResource(R.string.modules_custom_permissions)
    val actionText = stringResource(R.string.modules_permission_action)
    val serviceText = stringResource(R.string.modules_permission_service)
    val webBridgeText = stringResource(R.string.modules_permission_web_bridge)
    val webNetworkText = stringResource(R.string.modules_permission_web_network)
    val webDownloadText = stringResource(R.string.modules_permission_web_download)
    val okText = stringResource(android.R.string.ok)
    val cancelText = stringResource(android.R.string.cancel)

    moe.shizuku.manager.ui.compose.WearShizukuTheme {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(WearMaterialTheme.colorScheme.background)) {
            moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                    state = state,
                    contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                item {
                    WearText(
                        text = title,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        color = WearMaterialTheme.colorScheme.primary,
                        style = WearMaterialTheme.typography.titleMedium
                    )
                }
                item {
                    WearCheckboxButton(
                        checked = draft.action,
                        onCheckedChange = { draft = draft.copy(action = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { WearText(actionText) }
                    )
                }
                item {
                    WearCheckboxButton(
                        checked = draft.service,
                        onCheckedChange = { draft = draft.copy(service = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { WearText(serviceText) }
                    )
                }
                item {
                    WearCheckboxButton(
                        checked = draft.webBridge,
                        onCheckedChange = { enabled -> draft = if (enabled) draft.copy(webBridge = true, webNetwork = false) else draft.copy(webBridge = false) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { WearText(webBridgeText) }
                    )
                }
                item {
                    WearCheckboxButton(
                        checked = draft.webNetwork,
                        onCheckedChange = { enabled -> draft = if (enabled) draft.copy(webNetwork = true, webBridge = false) else draft.copy(webNetwork = false) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { WearText(webNetworkText) }
                    )
                }
                item {
                    WearCheckboxButton(
                        checked = draft.webDownload,
                        onCheckedChange = { draft = draft.copy(webDownload = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { WearText(webDownloadText) }
                    )
                }
                item {
                    WearButton(onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) {
                        WearText(okText)
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(cancelText)
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun CustomPermissionsDialog(
    value: ModuleSettings.CustomPermissions,
    onDismiss: () -> Unit,
    onSave: (ModuleSettings.CustomPermissions) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modules_custom_permissions)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SwitchSettingsRow(
                    icon = R.drawable.ic_outline_play_arrow_24,
                    title = stringResource(R.string.modules_permission_action),
                    summary = stringResource(R.string.modules_permission_action_summary),
                    checked = draft.action,
                    onCheckedChange = { draft = draft.copy(action = it) }
                )
                GroupDivider()
                SwitchSettingsRow(
                    icon = R.drawable.ic_terminal_24,
                    title = stringResource(R.string.modules_permission_service),
                    summary = stringResource(R.string.modules_permission_service_summary),
                    checked = draft.service,
                    onCheckedChange = { draft = draft.copy(service = it) }
                )
                GroupDivider()
                SwitchSettingsRow(
                    icon = R.drawable.ic_code_24dp,
                    title = stringResource(R.string.modules_permission_web_bridge),
                    summary = stringResource(R.string.modules_permission_web_bridge_summary),
                    checked = draft.webBridge,
                    onCheckedChange = { enabled ->
                        draft = if (enabled) {
                            draft.copy(webBridge = true, webNetwork = false)
                        } else {
                            draft.copy(webBridge = false)
                        }
                    }
                )
                GroupDivider()
                SwitchSettingsRow(
                    icon = R.drawable.ic_baseline_link_24,
                    title = stringResource(R.string.modules_permission_web_network),
                    summary = stringResource(R.string.modules_permission_web_network_summary),
                    checked = draft.webNetwork,
                    onCheckedChange = { enabled ->
                        draft = if (enabled) {
                            draft.copy(webNetwork = true, webBridge = false)
                        } else {
                            draft.copy(webNetwork = false)
                        }
                    }
                )
                GroupDivider()
                SwitchSettingsRow(
                    icon = R.drawable.ic_outline_arrow_upward_24,
                    title = stringResource(R.string.modules_permission_web_download),
                    summary = stringResource(R.string.modules_permission_web_download_summary),
                    checked = draft.webDownload,
                    onCheckedChange = { draft = draft.copy(webDownload = it) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
internal fun ChoiceDialog(
    title: String,
    choices: List<ChoiceOption>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                for ((index, choice) in choices.withIndex()) {
                    SettingsRow(
                        icon = choice.icon,
                        title = choice.title,
                        summary = choice.summary,
                        onClick = { onSelect(index) },
                        trailing = {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = { onSelect(index) }
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
internal fun TvChoiceDialog(
    title: String,
    choices: List<ChoiceOption>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TvText(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                for ((index, choice) in choices.withIndex()) {
                    TvChoiceRow(
                        icon = choice.icon,
                        title = choice.title,
                        summary = choice.summary,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) }
                    )
                }
            }
        },
        confirmButton = {
            TvOutlinedButton(onClick = onDismiss) {
                TvText(stringResource(android.R.string.cancel))
            }
        },
        containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
        shape = TvMaterialTheme.shapes.extraLarge
    )
}

@Composable
internal fun TvChoiceRow(
    icon: Int?,
    title: String,
    summary: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (selected) TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha) else Color.Transparent,
            focusedContainerColor = if (selected) TvMaterialTheme.colorScheme.primaryContainer else TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (icon != null) {
                ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                TvText(text = title, style = TvMaterialTheme.typography.titleMedium)
                if (summary != null) {
                    TvText(
                        text = summary,
                        style = TvMaterialTheme.typography.bodySmall,
                        color = TvMaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (selected) {
                ShizukuIcon(imageVector = Icons.Rounded.Check, modifier = Modifier.size(24.dp), tint = TvMaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun TvCustomPermissionsDialog(
    value: ModuleSettings.CustomPermissions,
    onDismiss: () -> Unit,
    onSave: (ModuleSettings.CustomPermissions) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TvText(stringResource(R.string.modules_custom_permissions)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                TvSwitchSettingsRow(
                    icon = R.drawable.ic_outline_play_arrow_24,
                    title = stringResource(R.string.modules_permission_action),
                    summary = stringResource(R.string.modules_permission_action_summary),
                    checked = draft.action,
                    onCheckedChange = { draft = draft.copy(action = it) }
                )
                TvSwitchSettingsRow(
                    icon = R.drawable.ic_terminal_24,
                    title = stringResource(R.string.modules_permission_service),
                    summary = stringResource(R.string.modules_permission_service_summary),
                    checked = draft.service,
                    onCheckedChange = { draft = draft.copy(service = it) }
                )
                TvSwitchSettingsRow(
                    icon = R.drawable.ic_code_24dp,
                    title = stringResource(R.string.modules_permission_web_bridge),
                    summary = stringResource(R.string.modules_permission_web_bridge_summary),
                    checked = draft.webBridge,
                    onCheckedChange = { enabled ->
                        draft = if (enabled) {
                            draft.copy(webBridge = true, webNetwork = false)
                        } else {
                            draft.copy(webBridge = false)
                        }
                    }
                )
                TvSwitchSettingsRow(
                    icon = R.drawable.ic_baseline_link_24,
                    title = stringResource(R.string.modules_permission_web_network),
                    summary = stringResource(R.string.modules_permission_web_network_summary),
                    checked = draft.webNetwork,
                    onCheckedChange = { enabled ->
                        draft = if (enabled) {
                            draft.copy(webNetwork = true, webBridge = false)
                        } else {
                            draft.copy(webNetwork = false)
                        }
                    }
                )
                TvSwitchSettingsRow(
                    icon = R.drawable.ic_outline_arrow_upward_24,
                    title = stringResource(R.string.modules_permission_web_download),
                    summary = stringResource(R.string.modules_permission_web_download_summary),
                    checked = draft.webDownload,
                    onCheckedChange = { draft = draft.copy(webDownload = it) }
                )
            }
        },
        confirmButton = {
            TvButton(onClick = { onSave(draft) }) {
                TvText(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TvOutlinedButton(onClick = onDismiss) {
                TvText(stringResource(android.R.string.cancel))
            }
        },
        containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
        shape = TvMaterialTheme.shapes.extraLarge
    )
}

@Composable
internal fun TvSwitchSettingsRow(
    icon: Int,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    TvSurface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (checked) TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (checked) TvMaterialTheme.colorScheme.primaryContainer else TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                TvText(text = title, style = TvMaterialTheme.typography.titleMedium)
                TvText(
                    text = summary,
                    style = TvMaterialTheme.typography.bodySmall,
                    color = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            moe.shizuku.manager.ui.compose.ExpressiveSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
