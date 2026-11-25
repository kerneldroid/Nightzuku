@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.ui.compose.TvBackButton
import moe.shizuku.manager.ui.compose.TvSettingsClickRow
import moe.shizuku.manager.ui.compose.TvSettingsToggleRow
import moe.shizuku.manager.ui.compose.TvTwoPaneLayout

@Composable
fun TvSettingsScreen(
    onNavigateUp: () -> Unit,
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    isRooted: Boolean,
    bootLaunchMethod: Int,
    bootDiscoveryTimeoutSec: Int,
    bootMaxRetries: Int,
    bootFallbackNotification: Boolean,
    onBootMethodClick: () -> Unit,
    onBootTimeoutClick: () -> Unit,
    onBootRetriesClick: () -> Unit,
    onBootFallbackNotificationChange: (Boolean) -> Unit,
    languageSummary: String,
    onLanguageClick: () -> Unit,
    nightSummary: String,
    onNightModeClick: () -> Unit,
    blackNightTheme: Boolean,
    onBlackNightThemeChange: (Boolean) -> Unit,
    useSystemColor: Boolean,
    onUseSystemColorChange: (Boolean) -> Unit,
    onLabFeaturesClick: () -> Unit,
    isDialogOpen: Boolean = false
) {
    val initialFocusRequester = remember { FocusRequester() }
    val bootMethodFocusRequester = remember { FocusRequester() }
    val bootTimeoutFocusRequester = remember { FocusRequester() }
    val bootRetriesFocusRequester = remember { FocusRequester() }
    val languageFocusRequester = remember { FocusRequester() }
    val nightModeFocusRequester = remember { FocusRequester() }

    var lastDialogTrigger by remember { mutableStateOf<FocusRequester?>(null) }

    LaunchedEffect(Unit) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    LaunchedEffect(isDialogOpen) {
        if (!isDialogOpen) {
            val target = lastDialogTrigger
            if (target != null) {
                runCatching { target.requestFocus() }
                lastDialogTrigger = null
            }
        }
    }

    val bootMethodSummary = when {
        bootLaunchMethod == LaunchMethod.ROOT && isRooted ->
            stringResource(R.string.settings_boot_method_root)
        !isRooted ->
            stringResource(R.string.settings_boot_method_wireless_only_summary)
        else ->
            stringResource(R.string.settings_boot_method_wireless)
    }

    TvTwoPaneLayout(
        title = stringResource(R.string.settings_title),
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
                TvSettingsGroupTitle(stringResource(R.string.settings_startup))
            }
            item {
                TvSettingsToggleRow(
                    title = stringResource(R.string.settings_start_on_boot),
                    summary = stringResource(R.string.settings_start_on_boot_summary),
                    checked = startOnBoot,
                    onCheckedChange = onStartOnBootChange,
                    icon = R.drawable.ic_server_restart,
                    modifier = Modifier.focusRequester(initialFocusRequester)
                )
            }
            item {
                TvSettingsClickRow(
                    title = stringResource(R.string.settings_boot_method_title),
                    summary = bootMethodSummary,
                    enabled = startOnBoot,
                    icon = if (bootLaunchMethod == LaunchMethod.ROOT && isRooted) {
                        R.drawable.ic_root_24dp
                    } else {
                        R.drawable.ic_wadb_24
                    },
                    modifier = Modifier.focusRequester(bootMethodFocusRequester),
                    onClick = {
                        lastDialogTrigger = bootMethodFocusRequester
                        onBootMethodClick()
                    }
                )
            }

            if (startOnBoot && !(bootLaunchMethod == LaunchMethod.ROOT && isRooted)) {
                item {
                    TvSettingsGroupTitle(stringResource(R.string.settings_startup_wireless_options))
                }
                item {
                    TvSettingsClickRow(
                        title = stringResource(R.string.settings_boot_timeout_title),
                        summary = stringResource(R.string.settings_boot_timeout_summary, bootDiscoveryTimeoutSec),
                        icon = R.drawable.ic_wadb_24,
                        modifier = Modifier.focusRequester(bootTimeoutFocusRequester),
                        onClick = {
                            lastDialogTrigger = bootTimeoutFocusRequester
                            onBootTimeoutClick()
                        }
                    )
                }
                item {
                    TvSettingsClickRow(
                        title = stringResource(R.string.settings_boot_retries_title),
                        summary = stringResource(R.string.settings_boot_retries_summary, bootMaxRetries),
                        icon = R.drawable.ic_server_restart,
                        modifier = Modifier.focusRequester(bootRetriesFocusRequester),
                        onClick = {
                            lastDialogTrigger = bootRetriesFocusRequester
                            onBootRetriesClick()
                        }
                    )
                }
                item {
                    TvSettingsToggleRow(
                        title = stringResource(R.string.settings_boot_fallback_notification_title),
                        summary = stringResource(R.string.settings_boot_fallback_notification_summary),
                        checked = bootFallbackNotification,
                        onCheckedChange = onBootFallbackNotificationChange,
                        icon = R.drawable.ic_outline_notifications_active_24
                    )
                }
            }

            item {
                TvSettingsGroupTitle(stringResource(R.string.settings_language))
            }
            item {
                TvSettingsClickRow(
                    title = stringResource(R.string.settings_language),
                    summary = languageSummary,
                    icon = R.drawable.ic_outline_translate_24,
                    modifier = Modifier.focusRequester(languageFocusRequester),
                    onClick = {
                        lastDialogTrigger = languageFocusRequester
                        onLanguageClick()
                    }
                )
            }

            item {
                TvSettingsGroupTitle(stringResource(R.string.settings_user_interface))
            }
            item {
                TvSettingsClickRow(
                    title = stringResource(rikka.core.R.string.dark_theme),
                    summary = nightSummary,
                    icon = R.drawable.ic_outline_dark_mode_24,
                    modifier = Modifier.focusRequester(nightModeFocusRequester),
                    onClick = {
                        lastDialogTrigger = nightModeFocusRequester
                        onNightModeClick()
                    }
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
                TvSettingsGroupTitle(stringResource(R.string.lab_features_title))
            }
            item {
                TvSettingsClickRow(
                    title = stringResource(R.string.lab_features_title),
                    summary = stringResource(R.string.lab_features_summary),
                    icon = R.drawable.ic_code_24dp,
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
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        fontWeight = FontWeight.Bold
    )
}
