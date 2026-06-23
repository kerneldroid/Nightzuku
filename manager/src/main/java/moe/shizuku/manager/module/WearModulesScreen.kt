package moe.shizuku.manager.module

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.ButtonDefaults as WearButtonDefaults
import androidx.wear.compose.material3.CheckboxButton as WearCheckboxButton
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.IconButton as WearIconButton
import androidx.wear.compose.material3.IconButtonDefaults as WearIconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.TitleCard as WearTitleCard
import androidx.wear.compose.material3.CardDefaults as WearCardDefaults
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WearModulesScreen(
    modules: List<AdbModule>,
    busyId: String?,
    onToggle: (AdbModule) -> Unit,
    onRunAction: (AdbModule) -> Unit,
    onRunService: (AdbModule) -> Unit,
    onOpenWebUi: (AdbModule) -> Unit,
    onDelete: (AdbModule) -> Unit,
    onTrustChange: (AdbModule, Boolean) -> Unit,
    onInstallZip: () -> Unit
) {
    val transformationSpec = rememberTransformationSpec()

    WearScreenScaffold { state ->
        TransformingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 44.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                WearScreenTitle(icon = Icons.Rounded.Extension, title = stringResource(R.string.modules_title))
            }

            item {
                WearButton(
                    onClick = onInstallZip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = WearButtonDefaults.filledTonalButtonColors()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        WearIcon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        WearText(text = stringResource(R.string.modules_install_zip))
                    }
                }
            }

            if (modules.isEmpty()) {
                item {
                    WearText(
                        text = stringResource(R.string.modules_empty_title),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        textAlign = TextAlign.Center,
                        style = WearMaterialTheme.typography.bodyMedium
                    )
                }
            }

            items(modules, key = { it.id }) { module ->
                val isBusy = busyId == module.id
                val trusted = ModuleSettings.isModuleTrusted(module.id)
                var expanded by remember { mutableStateOf(false) }

                val containerColor = if (trusted) WearMaterialTheme.colorScheme.tertiaryContainer
                                    else WearMaterialTheme.colorScheme.surfaceContainer
                val contentColor = if (trusted) WearMaterialTheme.colorScheme.onTertiaryContainer
                                  else WearMaterialTheme.colorScheme.onSurface
                val iconTint = if (trusted) WearMaterialTheme.colorScheme.tertiary else WearMaterialTheme.colorScheme.primary

                WearTitleCard(
                    onClick = { expanded = !expanded },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            WearIcon(
                                imageVector = Icons.Rounded.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = iconTint
                            )
                            Spacer(Modifier.width(6.dp))
                            WearText(
                                text = module.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
                            WearCheckboxButton(
                                checked = module.enabled,
                                onCheckedChange = { onToggle(module) },
                                modifier = Modifier.size(32.dp),
                                label = { }
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .animateContentSize(),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = WearCardDefaults.cardColors(
                        containerColor = containerColor,
                        contentColor = contentColor
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        WearText(
                            text = "v${module.version ?: "1.0"}",
                            style = WearMaterialTheme.typography.labelSmall,
                            color = if (trusted) contentColor.copy(alpha = 0.8f) else WearMaterialTheme.colorScheme.onSurfaceVariant
                        )

                        AnimatedVisibility(visible = module.enabled && !isBusy && expanded) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (module.hasWebUi) {
                                    WearIconButton(
                                        onClick = { onOpenWebUi(module) },
                                        modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize)
                                    ) {
                                        WearIcon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                                    }
                                }
                                if (module.hasAction) {
                                    WearIconButton(
                                        onClick = { onRunAction(module) },
                                        modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize)
                                    ) {
                                        WearIcon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    }
                                }
                                if (module.hasService) {
                                    WearIconButton(
                                        onClick = { onRunService(module) },
                                        modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize)
                                    ) {
                                        WearIcon(Icons.Rounded.Terminal, contentDescription = null)
                                    }
                                }

                                WearIconButton(
                                    onClick = { onTrustChange(module, !trusted) },
                                    modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize),
                                    colors = if (trusted) WearIconButtonDefaults.iconButtonColors(
                                        containerColor = WearMaterialTheme.colorScheme.primary,
                                        contentColor = WearMaterialTheme.colorScheme.onPrimary
                                    ) else WearIconButtonDefaults.filledTonalIconButtonColors()
                                ) {
                                    WearIcon(Icons.Rounded.Security, contentDescription = null)
                                }

                                WearIconButton(
                                    onClick = { onDelete(module) },
                                    modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize),
                                    colors = WearIconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = WearMaterialTheme.colorScheme.errorContainer,
                                        contentColor = WearMaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    WearIcon(Icons.Rounded.Delete, contentDescription = null)
                                }
                            }
                        }

                        if (isBusy) {
                            WearText(
                                text = stringResource(R.string.modules_running),
                                style = WearMaterialTheme.typography.labelSmall,
                                color = WearMaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
