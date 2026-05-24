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
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
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
                Button(
                    onClick = onInstallZip,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(R.string.modules_install_zip))
                    }
                }
            }

            if (modules.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.modules_empty_title),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            items(modules, key = { it.id }) { module ->
                val isBusy = busyId == module.id
                val trusted = ModuleSettings.isModuleTrusted(module.id)
                var expanded by remember { mutableStateOf(false) }
                
                val containerColor = if (trusted) MaterialTheme.colorScheme.tertiaryContainer 
                                    else MaterialTheme.colorScheme.surfaceContainer
                val contentColor = if (trusted) MaterialTheme.colorScheme.onTertiaryContainer 
                                  else MaterialTheme.colorScheme.onSurface
                val iconTint = if (trusted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary

                TitleCard(
                    onClick = { expanded = !expanded },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = iconTint
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = module.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
                            CheckboxButton(
                                checked = module.enabled,
                                onCheckedChange = { onToggle(module) },
                                modifier = Modifier.size(32.dp),
                                label = { }
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    colors = androidx.wear.compose.material3.CardDefaults.cardColors(
                        containerColor = containerColor,
                        contentColor = contentColor
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "v${module.version ?: "1.0"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (trusted) contentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
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
                                    IconButton(
                                        onClick = { onOpenWebUi(module) },
                                        modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize)
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                                    }
                                }
                                if (module.hasAction) {
                                    IconButton(
                                        onClick = { onRunAction(module) },
                                        modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize)
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    }
                                }
                                if (module.hasService) {
                                    IconButton(
                                        onClick = { onRunService(module) },
                                        modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize)
                                    ) {
                                        Icon(Icons.Rounded.Terminal, contentDescription = null)
                                    }
                                }
                                
                                IconButton(
                                    onClick = { onTrustChange(module, !trusted) },
                                    modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                                    colors = if (trusted) IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) else IconButtonDefaults.filledTonalIconButtonColors()
                                ) {
                                    Icon(Icons.Rounded.Security, contentDescription = null)
                                }

                                IconButton(
                                    onClick = { onDelete(module) },
                                    modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = null)
                                }
                            }
                        }

                        if (isBusy) {
                            Text(
                                text = stringResource(R.string.modules_running),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
