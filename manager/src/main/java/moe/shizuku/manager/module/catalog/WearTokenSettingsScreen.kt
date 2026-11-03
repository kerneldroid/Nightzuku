package moe.shizuku.manager.module.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.ButtonDefaults as WearButtonDefaults
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.IconButton as WearIconButton
import androidx.wear.compose.material3.IconButtonDefaults as WearIconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.TitleCard as WearTitleCard
import androidx.wear.compose.material3.CardDefaults as WearCardDefaults
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.SurfaceTransformation
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle

@Composable
fun WearTokenSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var githubToken by remember { mutableStateOf(TokenStore.getToken(context) ?: "") }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val transformationSpec = rememberTransformationSpec()

    WearScreenScaffold { state ->
        TransformingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 44.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                ) {
                    WearIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize)
                    ) {
                        WearIcon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                    Spacer(Modifier.width(4.dp))
                    WearScreenTitle(icon = Icons.Rounded.VpnKey, title = stringResource(R.string.update_settings_github_pat_title))
                }
            }

            item {
                WearTitleCard(
                    onClick = { showTokenDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    title = { WearText(stringResource(R.string.update_settings_github_pat)) },
                    subtitle = {
                        WearText(
                            text = if (githubToken.isNotBlank()) {
                                stringResource(R.string.update_settings_github_pat_set)
                            } else {
                                stringResource(R.string.update_settings_github_pat_unset)
                            }
                        )
                    },
                    colors = WearCardDefaults.cardColors()
                )
            }

            if (githubToken.isNotBlank()) {
                item {
                    WearButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        colors = WearButtonDefaults.filledTonalButtonColors()
                    ) {
                        WearText(text = stringResource(R.string.update_settings_github_pat_delete))
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
            }

            item {
                WearButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    colors = WearButtonDefaults.buttonColors()
                ) {
                    WearText(text = stringResource(R.string.modules_catalog_open))
                }
            }
        }
    }

    if (showTokenDialog) {
        var tokenInput by remember { mutableStateOf(githubToken) }
        var showPat by remember { mutableStateOf(false) }
        val showWarning = tokenInput.isNotBlank() && !TokenStore.isValidTokenFormat(tokenInput)

        WearAlertDialog(
            visible = true,
            onDismissRequest = { showTokenDialog = false },
            title = { WearText(stringResource(R.string.update_settings_github_pat_title)) },
            confirmButton = {
                WearButton(
                    onClick = {
                        val trimmed = tokenInput.trim()
                        githubToken = trimmed
                        if (trimmed.isBlank()) {
                            TokenStore.clearToken(context)
                        } else {
                            TokenStore.setToken(context, trimmed)
                        }
                        showTokenDialog = false
                    },
                    enabled = tokenInput.isNotBlank(),
                    colors = WearButtonDefaults.filledTonalButtonColors()
                ) {
                    WearText(stringResource(android.R.string.ok))
                }
            },
            text = {
                Column {
                    WearText(
                        text = stringResource(R.string.update_settings_github_pat_description),
                        style = WearMaterialTheme.typography.bodySmall,
                        color = WearMaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text(stringResource(R.string.modules_catalog_token_hint)) },
                        singleLine = true,
                        visualTransformation = if (showPat) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = showWarning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    if (showWarning) {
                        WearText(
                            text = stringResource(R.string.modules_catalog_token_format_warning),
                            style = WearMaterialTheme.typography.labelSmall,
                            color = WearMaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        WearIconButton(
                            onClick = { showPat = !showPat },
                            modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize)
                        ) {
                            WearIcon(
                                imageVector = if (showPat) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(
                                    if (showPat) R.string.update_settings_github_pat_hide
                                    else R.string.update_settings_github_pat_show
                                )
                            )
                        }
                        WearIconButton(
                            onClick = { tokenInput = "" },
                            modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize)
                        ) {
                            WearIcon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.update_settings_github_pat_clear)
                            )
                        }
                    }
                }
            }
        )
    }

    if (showDeleteDialog) {
        WearAlertDialog(
            visible = true,
            onDismissRequest = { showDeleteDialog = false },
            title = { WearText(stringResource(R.string.update_settings_github_pat_delete)) },
            confirmButton = {
                WearButton(
                    onClick = {
                        githubToken = ""
                        TokenStore.clearToken(context)
                        showDeleteDialog = false
                    },
                    colors = WearButtonDefaults.filledTonalButtonColors()
                ) {
                    WearText(stringResource(android.R.string.ok))
                }
            },
            text = {
                WearText(
                    text = stringResource(R.string.update_settings_github_pat_delete_summary),
                    style = WearMaterialTheme.typography.bodySmall,
                    color = WearMaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}
