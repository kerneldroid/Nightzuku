@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.ui.compose.ShizukuIcon

@Composable
fun TvSettingsScreen(
    onNavigateUp: () -> Unit,
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    languageSummary: String,
    onLanguageClick: () -> Unit,
    nightSummary: String,
    onNightModeClick: () -> Unit,
    blackNightTheme: Boolean,
    onBlackNightThemeChange: (Boolean) -> Unit,
    useSystemColor: Boolean,
    onUseSystemColorChange: (Boolean) -> Unit,
    moduleAccessMode: ModuleSettings.AccessMode,
    onModuleAccessModeClick: () -> Unit,
    recommandWebUi: Boolean,
    onRecommandWebUiChange: (Boolean) -> Unit,
    recommandAction: Boolean,
    onRecommandActionChange: (Boolean) -> Unit,
    onLabFeaturesClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .padding(32.dp)
                .size(width = 300.dp, height = 500.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.settings_title),
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            TvMenuButton(
                icon = R.drawable.ic_arrow_back_24,
                label = android.R.string.cancel,
                onClick = onNavigateUp
            )
        }


        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TvSettingsGroupTitle(stringResource(R.string.settings_startup))
            }
            item {
                TvSettingsToggleRow(
                    title = stringResource(R.string.settings_start_on_boot),
                    summary = stringResource(R.string.settings_start_on_boot_summary),
                    checked = startOnBoot,
                    onCheckedChange = onStartOnBootChange
                )
            }

            item {
                TvSettingsGroupTitle(stringResource(R.string.settings_language))
            }
            item {
                TvSettingsClickRow(
                    title = stringResource(R.string.settings_language),
                    summary = languageSummary,
                    onClick = onLanguageClick
                )
            }

            item {
                TvSettingsGroupTitle(stringResource(R.string.settings_user_interface))
            }
            item {
                TvSettingsClickRow(
                    title = stringResource(rikka.core.R.string.dark_theme),
                    summary = nightSummary,
                    onClick = onNightModeClick
                )
            }
            item {
                TvSettingsToggleRow(
                    title = stringResource(R.string.settings_black_night_theme),
                    summary = stringResource(R.string.settings_black_night_theme_summary),
                    checked = blackNightTheme,
                    onCheckedChange = onBlackNightThemeChange
                )
            }
            item {
                TvSettingsToggleRow(
                    title = stringResource(R.string.settings_use_system_color),
                    checked = useSystemColor,
                    onCheckedChange = onUseSystemColorChange
                )
            }

            item {
                TvSettingsGroupTitle(stringResource(R.string.modules_settings_title))
            }
            item {
                TvSettingsClickRow(
                    title = stringResource(R.string.modules_access_mode),
                    summary = stringResource(moduleAccessMode.labelRes),
                    onClick = onModuleAccessModeClick
                )
            }
            item {
                TvSettingsToggleRow(
                    title = stringResource(R.string.modules_recommand_webui),
                    summary = stringResource(R.string.modules_recommand_webui_summary),
                    checked = recommandWebUi,
                    onCheckedChange = onRecommandWebUiChange
                )
            }
            item {
                TvSettingsToggleRow(
                    title = stringResource(R.string.modules_recommand_action),
                    summary = stringResource(R.string.modules_recommand_action_summary),
                    checked = recommandAction,
                    onCheckedChange = onRecommandActionChange
                )
            }

            item {
                TvSettingsGroupTitle(stringResource(R.string.lab_features_title))
            }
            item {
                TvSettingsClickRow(
                    title = stringResource(R.string.lab_features_title),
                    summary = stringResource(R.string.lab_features_summary),
                    onClick = onLabFeaturesClick
                )
            }
        }
    }
}

@Composable
private fun TvSettingsGroupTitle(title: String) {
    TvText(
        text = title,
        style = TvMaterialTheme.typography.titleMedium,
        color = TvMaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun TvSettingsClickRow(
    title: String,
    summary: String? = null,
    onClick: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            focusedContainerColor = TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            TvText(text = title, style = TvMaterialTheme.typography.titleMedium)
            if (summary != null) {
                TvText(
                    text = summary,
                    style = TvMaterialTheme.typography.bodySmall,
                    color = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TvSettingsToggleRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (checked) {
                TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)
            } else {
                TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
            },
            focusedContainerColor = if (checked) {
                TvMaterialTheme.colorScheme.primaryContainer
            } else {
                TvMaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            TvText(
                text = if (checked) stringResource(android.R.string.ok) else stringResource(android.R.string.cancel),
                style = TvMaterialTheme.typography.labelLarge,
                color = if (checked) TvMaterialTheme.colorScheme.primary else TvMaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TvMenuButton(
    icon: Int,
    label: Int,
    onClick: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.5f else 0.2f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
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
