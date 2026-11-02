@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.shizuku.manager.adb

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.GuideContent
import moe.shizuku.manager.ui.compose.GuideNote
import moe.shizuku.manager.ui.compose.GuideNoteTone
import moe.shizuku.manager.ui.compose.GuideStep
import moe.shizuku.manager.ui.compose.GuideStepList
import moe.shizuku.manager.ui.compose.PairingCodePreview
import moe.shizuku.manager.ui.compose.SettingsSectionHeader
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
        onNavigateUp = onNavigateUp,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp)
    ) {
        item {
            GuideContent {
                PairingStatusBanner(
                    ready = notificationEnabled,
                    title = stringResource(
                        if (notificationEnabled) R.string.adb_pairing_status_ready
                        else R.string.adb_pairing_status_blocked
                    ),
                    body = stringResource(
                        if (notificationEnabled) R.string.adb_pairing_tutorial_content_notification
                        else R.string.adb_pairing_tutorial_content_notification_blocked
                    ),
                    actionLabel = stringResource(
                        if (notificationEnabled) R.string.development_settings
                        else R.string.notification_settings
                    ),
                    actionIcon = if (notificationEnabled) Icons.Rounded.Settings
                    else Icons.Rounded.NotificationsActive,
                    onAction = if (notificationEnabled) onOpenDeveloperOptions
                    else onOpenNotificationSettings,
                    errorAction = !notificationEnabled
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader(stringResource(R.string.guide_steps))
                    GuideStepList(
                        steps = listOf(
                            GuideStep(
                                title = stringResource(R.string.development_settings),
                                summary = stringResource(R.string.guide_pairing_step_developer_summary),
                                detail = stringResource(R.string.adb_pairing_tutorial_content_steps),
                                actionLabel = stringResource(R.string.development_settings),
                                onAction = onOpenDeveloperOptions
                            ),
                            GuideStep(
                                title = stringResource(R.string.guide_pairing_step_code_title),
                                summary = stringResource(R.string.guide_pairing_step_code_summary),
                                detail = stringResource(R.string.adb_pairing_tutorial_content_enter_pairing_code),
                                visual = { PairingCodePreview() }
                            ),
                            GuideStep(
                                title = stringResource(R.string.guide_pairing_step_finish_title),
                                summary = stringResource(R.string.guide_pairing_step_done_summary),
                                actionLabel = stringResource(R.string.guide_pairing_step_finish_title),
                                onAction = onFinish
                            )
                        )
                    )
                }

                if (DeviceCompatibility.isMiui()) {
                    GuideNote(
                        icon = Icons.Rounded.Warning,
                        title = "MIUI",
                        text = stringResource(R.string.adb_pairing_tutorial_content_miui) + "\n\n" +
                            stringResource(R.string.adb_pairing_tutorial_content_miui_2),
                        tone = GuideNoteTone.Warning
                    )
                }

                GuideNote(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.home_local_network_title),
                    text = stringResource(R.string.adb_pairing_tutorial_content_network) + "\n\n" +
                        stringResource(R.string.adb_pairing_tutorial_content_network_limation_not_foreground)
                )
            }
        }
    }
}

@Composable
private fun PairingStatusBanner(
    ready: Boolean,
    title: String,
    body: String,
    actionLabel: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
    errorAction: Boolean
) {
    val container by animateColorAsState(
        targetValue = if (ready) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        label = "pairingBanner"
    )
    val content = if (ready) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.NotificationsActive,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = body, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = if (errorAction) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onError)
                    } else {
                        ButtonDefaults.textButtonColors(contentColor = content)
                    }
                ) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
