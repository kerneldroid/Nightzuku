@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.adb

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.TvActionButton
import moe.shizuku.manager.ui.compose.TvActionCard
import moe.shizuku.manager.ui.compose.TvBackButton
import moe.shizuku.manager.ui.compose.TvStaticCard
import moe.shizuku.manager.ui.compose.TvTwoPaneLayout
import rikka.compatibility.DeviceCompatibility

@Composable
fun TvAdbPairingTutorialScreen(
    onNavigateUp: () -> Unit,
    notificationEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onFinish: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(notificationEnabled) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    TvTwoPaneLayout(
        title = stringResource(R.string.adb_pairing_tutorial_title),
        sidebarContent = {
            TvBackButton(onClick = onNavigateUp)
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 32.dp, end = 32.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (notificationEnabled) {
                item {
                    TvTutorialStepCard(
                        icon = R.drawable.ic_outline_notifications_active_24,
                        title = stringResource(R.string.notification_channel_adb_pairing),
                        body = stringResource(R.string.adb_pairing_tutorial_content_notification),
                        modifier = Modifier.focusRequester(initialFocusRequester)
                    )
                }
                item {
                    TvTutorialStepCard(
                        icon = R.drawable.ic_help_outline_24dp,
                        title = stringResource(R.string.home_local_network_title),
                        body = stringResource(R.string.adb_pairing_tutorial_content_network) +
                            "\n\n" +
                            stringResource(R.string.adb_pairing_tutorial_content_network_limation_not_foreground)
                    )
                }
            } else {
                item {
                    TvTutorialStepCard(
                        icon = R.drawable.ic_outline_info_24,
                        title = stringResource(R.string.notification_settings),
                        body = stringResource(R.string.adb_pairing_tutorial_content_notification_blocked),
                        danger = true
                    ) {
                        TvActionButton(
                            onClick = onOpenNotificationSettings,
                            modifier = Modifier.focusRequester(initialFocusRequester)
                        ) {
                            TvText(stringResource(R.string.notification_settings))
                        }
                    }
                }
            }

            if (DeviceCompatibility.isMiui()) {
                item {
                    TvTutorialStepCard(
                        icon = R.drawable.ic_warning_24,
                        title = "MIUI",
                        body = stringResource(R.string.adb_pairing_tutorial_content_miui) +
                            "\n\n" +
                            stringResource(R.string.adb_pairing_tutorial_content_miui_2),
                        danger = true
                    )
                }
            }

            if (notificationEnabled) {
                item {
                    TvTutorialStepCard(
                        number = 1,
                        title = stringResource(R.string.adb_pairing_tutorial_content_steps),
                        body = stringResource(R.string.adb_pairing_tutorial_content_left_is_clickable)
                    ) {
                        TvActionButton(
                            onClick = onOpenDeveloperOptions
                        ) {
                            TvText(stringResource(R.string.development_settings))
                        }
                    }
                }
                item {
                    TvTutorialStepCard(
                        number = 2,
                        title = stringResource(R.string.adb_pairing_tutorial_content_enter_pairing_code),
                        body = stringResource(R.string.adb_pairing_tutorial_content_notification)
                    )
                }
                item {
                    TvTutorialStepCard(
                        number = 3,
                        title = stringResource(R.string.adb_pairing_tutorial_content_finish)
                    ) {
                        TvActionButton(onClick = onFinish) {
                            TvText(stringResource(android.R.string.ok))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTutorialStepCard(
    number: Int? = null,
    @DrawableRes icon: Int? = null,
    title: String,
    body: String? = null,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null
) {
    val cardContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (number != null) {
                TvText(
                    text = number.toString(),
                    style = TvMaterialTheme.typography.headlineLarge,
                    color = TvMaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            } else if (icon != null) {
                ShizukuIcon(
                    icon = icon,
                    modifier = Modifier.size(48.dp),
                    tint = if (danger) TvMaterialTheme.colorScheme.error else TvMaterialTheme.colorScheme.primary
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (body != null) {
                    TvText(
                        text = body,
                        style = TvMaterialTheme.typography.bodyMedium,
                        color = if (danger) {
                            TvMaterialTheme.colorScheme.onErrorContainer
                        } else {
                            TvMaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(4.dp))
                    content()
                }
            }
        }
    }

    if (content == null) {
        TvActionCard(
            onClick = {},
            modifier = modifier,
            danger = danger
        ) {
            cardContent()
        }
    } else {
        TvStaticCard(
            modifier = modifier,
            danger = danger
        ) {
            cardContent()
        }
    }
}
