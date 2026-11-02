package moe.shizuku.manager.adb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.ExpressiveCard
import moe.shizuku.manager.ui.compose.GuideHero
import moe.shizuku.manager.ui.compose.GuideQuickStart
import moe.shizuku.manager.ui.compose.GuideStep
import moe.shizuku.manager.ui.compose.GuideTimeline
import moe.shizuku.manager.ui.compose.PairingCodePreview
import moe.shizuku.manager.ui.compose.PairingGuideIllustration
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import rikka.compatibility.DeviceCompatibility

@Composable
fun PairingGuideScreen(
    notificationEnabled: Boolean,
    onNavigateUp: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onFinish: () -> Unit
) {
    ShizukuLazyScaffold(
        title = stringResource(R.string.adb_pairing_tutorial_title),
        onNavigateUp = onNavigateUp
    ) {
        item {
            GuideHero(
                icon = Icons.Rounded.Usb,
                eyebrow = "WIRELESS ADB",
                title = stringResource(R.string.adb_pairing_tutorial_title),
                subtitle = stringResource(R.string.guide_pairing_hero_subtitle)
            ) {
                PairingGuideIllustration(connectionReady = notificationEnabled)
            }
        }

        item {
            if (notificationEnabled) {
                GuideQuickStart(
                    title = stringResource(R.string.adb_pairing_status_ready),
                    body = stringResource(R.string.adb_pairing_tutorial_content_notification)
                ) {
                    Button(
                        onClick = onOpenDeveloperOptions,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.largeIncreased
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.development_settings))
                    }
                }
            } else {
                GuideQuickStart(
                    title = stringResource(R.string.adb_pairing_status_blocked),
                    body = stringResource(R.string.adb_pairing_tutorial_content_notification_blocked)
                ) {
                    Button(
                        onClick = onOpenNotificationSettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.largeIncreased,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(
                            Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.notification_settings))
                    }
                }
            }
        }

        item {
            GuideTimeline(
                steps = listOf(
                    GuideStep(
                        icon = Icons.Rounded.Settings,
                        title = stringResource(R.string.development_settings),
                        summary = stringResource(R.string.guide_pairing_step_developer_summary),
                        detail = stringResource(R.string.adb_pairing_tutorial_content_steps),
                        actionLabel = stringResource(R.string.development_settings),
                        onAction = onOpenDeveloperOptions
                    ),
                    GuideStep(
                        icon = Icons.Rounded.Pin,
                        title = stringResource(R.string.guide_pairing_step_code_title),
                        summary = stringResource(R.string.guide_pairing_step_code_summary),
                        detail = stringResource(R.string.adb_pairing_tutorial_content_notification),
                        visual = { PairingCodePreview() }
                    ),
                    GuideStep(
                        icon = Icons.Rounded.Check,
                        title = stringResource(R.string.guide_pairing_step_finish_title),
                        summary = stringResource(R.string.guide_pairing_step_done_summary),
                        actionLabel = stringResource(android.R.string.ok),
                        onAction = onFinish
                    )
                )
            )
        }

        if (DeviceCompatibility.isMiui()) {
            item {
                ExpressiveCard(
                    icon = R.drawable.ic_warning_24,
                    title = "MIUI",
                    body = stringResource(R.string.adb_pairing_tutorial_content_miui) +
                        "\n\n" +
                        stringResource(R.string.adb_pairing_tutorial_content_miui_2),
                    danger = true
                )
            }
        }

        item {
            ExpressiveCard(
                icon = R.drawable.ic_help_outline_24dp,
                title = stringResource(R.string.home_local_network_title),
                body = stringResource(R.string.adb_pairing_tutorial_content_network) +
                    "\n\n" +
                    stringResource(R.string.adb_pairing_tutorial_content_network_limation_not_foreground)
            )
        }
    }
}
