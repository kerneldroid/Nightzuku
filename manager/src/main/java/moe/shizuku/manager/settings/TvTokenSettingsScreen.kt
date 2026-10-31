@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R
import moe.shizuku.manager.module.catalog.TokenStore
import moe.shizuku.manager.ui.compose.ShizukuIcon

@Composable
fun TvTokenSettingsScreen(
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    var githubToken by remember { mutableStateOf(TokenStore.getToken(context) ?: "") }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .size(width = 300.dp, height = 500.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.update_settings_github_pat_title),
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

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvSettingsGroupTitle(stringResource(R.string.update_settings_github))

            TvSettingsClickRow(
                title = stringResource(R.string.update_settings_github_pat),
                summary = if (githubToken.isNotBlank()) {
                    stringResource(R.string.update_settings_github_pat_set)
                } else {
                    stringResource(R.string.update_settings_github_pat_unset)
                },
                onClick = { showTokenDialog = true }
            )

            if (githubToken.isNotBlank()) {
                TvSettingsClickRow(
                    title = stringResource(R.string.update_settings_github_pat_delete),
                    summary = stringResource(R.string.update_settings_github_pat_delete_summary),
                    onClick = { showDeleteDialog = true }
                )
            }

            Spacer(Modifier.weight(1f))

            TvSurface(
                onClick = onNavigateUp,
                modifier = Modifier.fillMaxWidth(),
                shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.medium),
                colors = TvClickableSurfaceDefaults.colors(
                    containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedContainerColor = TvMaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ShizukuIcon(icon = R.drawable.ic_arrow_back_24, modifier = Modifier.size(24.dp))
                    TvText(text = stringResource(R.string.modules_catalog_open), style = TvMaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (showTokenDialog) {
        PatInputDialog(
            initialPat = githubToken,
            onDismiss = { showTokenDialog = false },
            onConfirm = { pat ->
                githubToken = pat
                if (pat.isBlank()) {
                    TokenStore.clearToken(context)
                } else {
                    TokenStore.setToken(context, pat)
                }
                showTokenDialog = false
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { TvText(stringResource(R.string.update_settings_github_pat_delete)) },
            text = {
                TvText(
                    text = stringResource(R.string.update_settings_github_pat_delete_summary),
                    style = TvMaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TvSurface(
                    onClick = {
                        githubToken = ""
                        TokenStore.clearToken(context)
                        showDeleteDialog = false
                    },
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.ok),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            dismissButton = {
                TvSurface(
                    onClick = { showDeleteDialog = false },
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
            shape = TvMaterialTheme.shapes.extraLarge
        )
    }
}

@Composable
private fun PatInputDialog(
    initialPat: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pat by remember { mutableStateOf(initialPat) }
    var showPat by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TvText(stringResource(R.string.update_settings_github_pat_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvText(
                    text = stringResource(R.string.update_settings_github_pat_description),
                    style = TvMaterialTheme.typography.bodyMedium,
                    color = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pat,
                    onValueChange = { pat = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { TvText(stringResource(R.string.update_settings_github_pat_label)) },
                    visualTransformation = if (showPat) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvSurface(
                        onClick = { showPat = !showPat },
                        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                        colors = TvClickableSurfaceDefaults.colors(
                            containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = if (showPat) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = stringResource(
                                if (showPat) R.string.update_settings_github_pat_hide
                                else R.string.update_settings_github_pat_show
                            ),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).size(20.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TvSurface(
                        onClick = { pat = "" },
                        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                        colors = TvClickableSurfaceDefaults.colors(
                            containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.update_settings_github_pat_clear),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).size(20.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TvSurface(
                onClick = { onConfirm(pat) },
                enabled = pat.isNotBlank(),
                shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                colors = TvClickableSurfaceDefaults.colors(
                    containerColor = TvMaterialTheme.colorScheme.primaryContainer
                )
            ) {
                TvText(
                    text = stringResource(android.R.string.ok),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        },
        dismissButton = {
            TvSurface(
                onClick = onDismiss,
                shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                colors = TvClickableSurfaceDefaults.colors(
                    containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                TvText(
                    text = stringResource(android.R.string.cancel),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        },
        containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
        shape = TvMaterialTheme.shapes.extraLarge
    )
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
private fun Spacer(modifier: Modifier) {
    androidx.compose.foundation.layout.Spacer(modifier)
}
