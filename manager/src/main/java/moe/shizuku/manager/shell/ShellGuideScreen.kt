package moe.shizuku.manager.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.GuideHero
import moe.shizuku.manager.ui.compose.GuideQuickStart
import moe.shizuku.manager.ui.compose.GuideStep
import moe.shizuku.manager.ui.compose.GuideTimeline
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.TerminalGuideIllustration

@Composable
fun ShellGuideScreen(
    shName: String,
    dexName: String,
    onNavigateUp: () -> Unit,
    onExportFiles: () -> Unit,
    onCopyCommand: (String) -> Unit,
    onCopySetup: () -> Unit,
    onOpenTerminal: () -> Unit,
    onViewHelp: () -> Unit
) {
    val configureCommand = "RISH_APPLICATION_ID=\"com.termux\""
    val launchCommand = "sh $shName"

    ShizukuLazyScaffold(
        title = stringResource(R.string.guide_rish_topbar),
        onNavigateUp = onNavigateUp,
        actions = {
            IconButton(onClick = onViewHelp) {
                ShizukuIcon(
                    icon = R.drawable.ic_help_outline_24dp,
                    contentDescription = stringResource(R.string.home_adb_button_view_help)
                )
            }
        }
    ) {
        item {
            GuideHero(
                icon = Icons.Rounded.Terminal,
                eyebrow = "RISH",
                title = stringResource(R.string.home_terminal_title),
                subtitle = stringResource(R.string.guide_rish_hero_subtitle)
            ) {
                TerminalGuideIllustration(shName = shName, dexName = dexName)
            }
        }

        item {
            GuideQuickStart(
                title = stringResource(R.string.guide_quick_start),
                body = stringResource(R.string.guide_rish_quick_start_body)
            ) {
                Button(
                    onClick = onExportFiles,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.material3.MaterialTheme.shapes.largeIncreased
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.terminal_export_files))
                }
                FilledTonalButton(
                    onClick = onCopySetup,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.material3.MaterialTheme.shapes.largeIncreased,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.guide_rish_copy_setup))
                }
                FilledTonalButton(
                    onClick = onOpenTerminal,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.material3.MaterialTheme.shapes.largeIncreased,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.guide_rish_open_terminal))
                }
            }
        }

        item {
            GuideTimeline(
                steps = listOf(
                    GuideStep(
                        icon = Icons.Rounded.FolderOpen,
                        title = stringResource(R.string.terminal_step_export_title),
                        summary = stringResource(R.string.guide_rish_step_export_summary),
                        detail = stringResource(R.string.guide_rish_step_export_detail),
                        actionLabel = stringResource(R.string.terminal_export_files),
                        onAction = onExportFiles
                    ),
                    GuideStep(
                        icon = Icons.Rounded.Code,
                        title = stringResource(R.string.terminal_step_configure_title),
                        summary = stringResource(R.string.guide_rish_step_configure_summary),
                        detail = stringResource(R.string.guide_rish_step_configure_detail),
                        code = configureCommand,
                        onCopyCode = { onCopyCommand(configureCommand) }
                    ),
                    GuideStep(
                        icon = Icons.Rounded.Terminal,
                        title = stringResource(R.string.terminal_step_launch_title),
                        summary = stringResource(R.string.guide_rish_step_launch_summary),
                        detail = stringResource(R.string.guide_rish_step_launch_detail),
                        code = launchCommand,
                        onCopyCode = { onCopyCommand(launchCommand) },
                        actionLabel = stringResource(R.string.guide_rish_open_terminal),
                        onAction = onOpenTerminal
                    )
                )
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onViewHelp) {
                    Icon(
                        imageVector = Icons.Rounded.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_adb_button_view_help))
                }
            }
        }
    }
}
