@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.GuideContent
import moe.shizuku.manager.ui.compose.GuideIntro
import moe.shizuku.manager.ui.compose.GuideStep
import moe.shizuku.manager.ui.compose.GuideStepList
import moe.shizuku.manager.ui.compose.SettingsSectionHeader
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold

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
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
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
            GuideContent {
                GuideIntro(stringResource(R.string.guide_rish_hero_subtitle))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onExportFiles,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.largeIncreased
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.terminal_export_files))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onCopySetup,
                            shape = MaterialTheme.shapes.largeIncreased
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.guide_rish_copy_setup))
                        }
                        FilledTonalButton(
                            onClick = onOpenTerminal,
                            shape = MaterialTheme.shapes.largeIncreased
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.guide_rish_open_terminal))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader(stringResource(R.string.guide_steps))
                    GuideStepList(
                        steps = listOf(
                            GuideStep(
                                title = stringResource(R.string.terminal_step_export_title),
                                summary = stringResource(R.string.guide_rish_step_export_summary),
                                detail = stringResource(R.string.guide_rish_step_export_detail),
                                actionLabel = stringResource(R.string.terminal_export_files),
                                onAction = onExportFiles
                            ),
                            GuideStep(
                                title = stringResource(R.string.terminal_step_configure_title),
                                summary = stringResource(R.string.guide_rish_step_configure_summary),
                                detail = stringResource(R.string.guide_rish_step_configure_detail),
                                code = configureCommand,
                                onCopyCode = { onCopyCommand(configureCommand) }
                            ),
                            GuideStep(
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

                TextButton(
                    onClick = onViewHelp,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
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
