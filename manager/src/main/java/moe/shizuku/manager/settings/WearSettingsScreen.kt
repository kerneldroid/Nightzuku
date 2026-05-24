package moe.shizuku.manager.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle

@Composable
fun WearSettingsScreen(
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    nightModeSummary: String,
    onNightModeClick: () -> Unit,
    blackNightTheme: Boolean,
    onBlackNightThemeChange: (Boolean) -> Unit,
    useSystemColor: Boolean,
    onUseSystemColorChange: (Boolean) -> Unit,
    onLabFeaturesClick: () -> Unit,
    moduleAccessMode: moe.shizuku.manager.module.ModuleSettings.AccessMode,
    onModuleAccessModeClick: () -> Unit,
    onCustomPermissionsClick: () -> Unit,
    // Dialog props
    showNightDialog: Boolean,
    nightLabels: List<String>,
    nightValues: List<Int>,
    currentNightMode: Int,
    onNightModeSelect: (Int) -> Unit,
    onNightDialogDismiss: () -> Unit
) {
    val startOnBootTitle = stringResource(R.string.settings_start_on_boot)
    val nightModeTitle = stringResource(rikka.core.R.string.dark_theme)
    val blackThemeTitle = stringResource(R.string.settings_black_night_theme)
    val systemColorTitle = stringResource(R.string.settings_use_system_color)
    val accessModeTitle = stringResource(R.string.modules_access_mode)
    val accessModeSummary = stringResource(moduleAccessMode.labelRes)
    val customPermissionsTitle = stringResource(R.string.modules_custom_permissions)
    val labFeaturesTitle = stringResource(R.string.lab_features_title)

    WearScreenScaffold { state ->
        TransformingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                WearScreenTitle(icon = Icons.Rounded.RestartAlt, title = stringResource(R.string.settings_title))
            }

            item {
                SwitchButton(
                    checked = startOnBoot,
                    onCheckedChange = onStartOnBootChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(startOnBootTitle) },
                    icon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) }
                )
            }

            item {
                androidx.wear.compose.material3.TitleCard(
                    onClick = onNightModeClick,
                    modifier = Modifier.fillMaxWidth(),
                    title = { Text(nightModeTitle) },
                    subtitle = { Text(nightModeSummary) }
                )
            }

            item {
                SwitchButton(
                    checked = blackNightTheme,
                    onCheckedChange = onBlackNightThemeChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(blackThemeTitle) },
                    icon = { Icon(Icons.Rounded.DarkMode, contentDescription = null) }
                )
            }

            item {
                SwitchButton(
                    checked = useSystemColor,
                    onCheckedChange = onUseSystemColorChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(systemColorTitle) },
                    icon = { Icon(Icons.Rounded.Palette, contentDescription = null) }
                )
            }

            item {
                androidx.wear.compose.material3.TitleCard(
                    onClick = onModuleAccessModeClick,
                    modifier = Modifier.fillMaxWidth(),
                    title = { Text(accessModeTitle) },
                    subtitle = { Text(accessModeSummary) }
                )
            }

            if (moduleAccessMode == moe.shizuku.manager.module.ModuleSettings.AccessMode.CUSTOM) {
                item {
                    androidx.wear.compose.material3.TitleCard(
                        onClick = onCustomPermissionsClick,
                        modifier = Modifier.fillMaxWidth(),
                        title = { Text(customPermissionsTitle) }
                    )
                }
            }

            item {
                androidx.wear.compose.material3.TitleCard(
                    onClick = onLabFeaturesClick,
                    modifier = Modifier.fillMaxWidth(),
                    title = { Text(labFeaturesTitle) }
                )
            }
        }
    }
}
