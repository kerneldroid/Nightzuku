package moe.shizuku.manager.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.runtime.Composable
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
    languageSummary: String,
    onLanguageClick: () -> Unit,
    nightModeSummary: String,
    onNightModeClick: () -> Unit,
    blackNightTheme: Boolean,
    onBlackNightThemeChange: (Boolean) -> Unit,
    useSystemColor: Boolean,
    onUseSystemColorChange: (Boolean) -> Unit,
    onLabFeaturesClick: () -> Unit
) {
    val startOnBootTitle = stringResource(R.string.settings_start_on_boot)
    val languageTitle = stringResource(R.string.settings_language)
    val nightModeTitle = stringResource(rikka.core.R.string.dark_theme)
    val blackThemeTitle = stringResource(R.string.settings_black_night_theme)
    val systemColorTitle = stringResource(R.string.settings_use_system_color)
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
                    onClick = onLanguageClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    title = { WearText(languageTitle) },
                    subtitle = { WearText(languageSummary) }
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
