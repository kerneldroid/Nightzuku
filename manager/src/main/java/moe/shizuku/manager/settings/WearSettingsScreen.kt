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
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.SwitchButton as WearSwitchButton
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.TitleCard as WearTitleCard
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

    val transformationSpec = rememberTransformationSpec()

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
                WearSwitchButton(
                    checked = startOnBoot,
                    onCheckedChange = onStartOnBootChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { WearText(startOnBootTitle) },
                    icon = { WearIcon(Icons.Rounded.RestartAlt, contentDescription = null) }
                )
            }

            item {
                WearTitleCard(
                    onClick = onNightModeClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    title = { WearText(nightModeTitle) },
                    subtitle = { WearText(nightModeSummary) }
                )
            }

            item {
                WearSwitchButton(
                    checked = blackNightTheme,
                    onCheckedChange = onBlackNightThemeChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { WearText(blackThemeTitle) },
                    icon = { WearIcon(Icons.Rounded.DarkMode, contentDescription = null) }
                )
            }

            item {
                WearSwitchButton(
                    checked = useSystemColor,
                    onCheckedChange = onUseSystemColorChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { WearText(systemColorTitle) },
                    icon = { WearIcon(Icons.Rounded.Palette, contentDescription = null) }
                )
            }

            item {
                WearTitleCard(
                    onClick = onModuleAccessModeClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    title = { WearText(accessModeTitle) },
                    subtitle = { WearText(accessModeSummary) }
                )
            }

            if (moduleAccessMode == moe.shizuku.manager.module.ModuleSettings.AccessMode.CUSTOM) {
                item {
                    WearTitleCard(
                        onClick = onCustomPermissionsClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        title = { WearText(customPermissionsTitle) }
                    )
                }
            }

            item {
                WearTitleCard(
                    onClick = onLabFeaturesClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    title = { WearText(labFeaturesTitle) }
                )
            }
        }
    }
}
