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
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.discovery.DiscoveredModule
import moe.shizuku.manager.module.discovery.ModuleDiscoveryManager
import moe.shizuku.manager.module.update.ModuleInstaller
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle
import moe.shizuku.manager.ui.compose.WearShizukuTheme

@Composable
fun WearCatalogScreen(
    onBack: () -> Unit,
    onModuleInstalled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discoveryManager = remember { ModuleDiscoveryManager.getInstance(context) }
    val installer = remember { ModuleInstaller.getInstance() }

    val sortLabels = listOf(
        stringResource(R.string.wear_catalog_sort_newest),
        stringResource(R.string.modules_catalog_sort_oldest),
        stringResource(R.string.wear_catalog_sort_stars),
        stringResource(R.string.wear_catalog_sort_official)
    )

    var modules by remember { mutableStateOf<List<DiscoveredModule>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortIndex by remember { mutableIntStateOf(2) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var installResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf<DiscoveredModule?>(null) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showDangerDialog by remember { mutableStateOf(false) }
    var dangerReason by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf(TokenStore.getToken(context) ?: "") }

    LaunchedEffect(Unit) {
        if (token.isBlank()) {
            showTokenDialog = true
            isLoading = false
        } else {
            isLoading = true
            error = null
            try {
                modules = discoveryManager.getModules(forceRefresh = false)
                if (modules.isEmpty()) {
                    modules = discoveryManager.getModules(forceRefresh = true)
                }
            } catch (e: Exception) {
                error = e.message
            }
            isLoading = false
        }
    }

    val sortedModules = remember(modules, sortIndex) {
        when (sortIndex) {
            0 -> modules.sortedByDescending { it.lastChecked }
            1 -> modules.sortedBy { it.lastChecked }
            2 -> modules.sortedByDescending { it.stars }
            3 -> modules.sortedByDescending { it.isOfficial }
            else -> modules.sortedByDescending { it.stars }
        }
    }

    val transformationSpec = rememberTransformationSpec()

    fun startInstall(module: DiscoveredModule, mode: ModuleSettings.InstallMode) {
        showInstallDialog = null
        ModuleSettings.setInstallMode(mode)
        installingId = module.moduleId
        scope.launch {
            val owner = module.repoFullName.substringBefore('/')
            val repo = module.repoFullName.substringAfter('/')
            val result = installer.installModule(
                context = context,
                moduleId = module.moduleId,
                owner = owner,
                repo = repo,
                subPath = module.subPath
            )
            installingId = null
            result.onSuccess {
                installResult = Pair(module.moduleName, true)
                onModuleInstalled()
            }.onFailure {
                installResult = Pair(
                    context.getString(R.string.modules_catalog_install_failed, it.message ?: it.javaClass.simpleName),
                    false
                )
            }
        }
    }

    WearShizukuTheme {
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
                        WearScreenTitle(icon = Icons.Rounded.CloudDownload, title = stringResource(R.string.modules_catalog_title))
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WearIconButton(
                            onClick = { showFilterDialog = true },
                            modifier = Modifier.size(WearIconButtonDefaults.DefaultButtonSize)
                        ) {
                            WearIcon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.wear_catalog_filter))
                        }
                        Spacer(Modifier.width(6.dp))
                        WearText(
                            text = sortLabels[sortIndex],
                            style = WearMaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (isLoading) {
                    item {
                        WearText(
                            text = stringResource(R.string.modules_catalog_loading),
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            style = WearMaterialTheme.typography.bodyMedium,
                            color = WearMaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (error != null) {
                    item {
                        WearText(
                            text = error ?: "",
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            style = WearMaterialTheme.typography.bodySmall,
                            color = WearMaterialTheme.colorScheme.error
                        )
                    }
                }

                if (!isLoading && sortedModules.isEmpty()) {
                    item {
                        WearText(
                            text = stringResource(R.string.modules_catalog_empty),
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            style = WearMaterialTheme.typography.bodyMedium
                        )
                    }
                }

                items(sortedModules, key = { "${it.repoFullName}/${it.moduleId}" }) { module ->
                    val isInstalling = installingId == module.moduleId

                    WearTitleCard(
                        onClick = {},
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                WearText(
                                    text = module.moduleName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold
                                )
                                if (module.stars > 0) {
                                    WearText(
                                        text = "\u2605${module.stars}",
                                        style = WearMaterialTheme.typography.labelSmall,
                                        color = WearMaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        colors = WearCardDefaults.cardColors()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (module.version != null) {
                                WearText(
                                    text = "v${module.version}",
                                    style = WearMaterialTheme.typography.labelSmall,
                                    color = WearMaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (module.repoDescription != null) {
                                WearText(
                                    text = module.repoDescription,
                                    style = WearMaterialTheme.typography.bodySmall,
                                    color = WearMaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            if (isInstalling) {
                                WearText(
                                    text = stringResource(R.string.modules_catalog_installing),
                                    style = WearMaterialTheme.typography.labelSmall,
                                    color = WearMaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            } else {
                                WearButton(
                                    onClick = { showInstallDialog = module },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    colors = WearButtonDefaults.filledTonalButtonColors()
                                ) {
                                    WearText(text = stringResource(R.string.modules_catalog_install))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showFilterDialog) {
            WearAlertDialog(
                visible = true,
                onDismissRequest = { showFilterDialog = false },
                title = { WearText(stringResource(R.string.wear_catalog_sort_by)) },
                confirmButton = {},
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sortLabels.forEachIndexed { index, label ->
                            WearButton(
                                onClick = {
                                    sortIndex = index
                                    showFilterDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (sortIndex == index) WearButtonDefaults.buttonColors()
                                         else WearButtonDefaults.filledTonalButtonColors()
                            ) {
                                WearText(text = label)
                            }
                        }
                    }
                }
            )
        }

        showInstallDialog?.let { module ->
            WearAlertDialog(
                visible = true,
                onDismissRequest = { showInstallDialog = null },
                title = { WearText(module.moduleName) },
                confirmButton = {},
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WearButton(
                            onClick = { startInstall(module, ModuleSettings.InstallMode.SOURCES) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = WearButtonDefaults.filledTonalButtonColors()
                        ) {
                            WearText(text = stringResource(R.string.modules_catalog_install_sources))
                        }
                        WearButton(
                            onClick = { startInstall(module, ModuleSettings.InstallMode.RELEASE) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = WearButtonDefaults.filledTonalButtonColors()
                        ) {
                            WearText(text = stringResource(R.string.modules_catalog_install_release))
                        }
                        Spacer(Modifier.height(4.dp))
                        WearButton(
                            onClick = { showInstallDialog = null },
                            modifier = Modifier.fillMaxWidth(),
                            colors = WearButtonDefaults.buttonColors()
                        ) {
                            WearText(text = stringResource(android.R.string.cancel))
                        }
                    }
                }
            )
        }

        installResult?.let { (message, success) ->
            WearAlertDialog(
                visible = true,
                onDismissRequest = { installResult = null },
                title = { WearText(if (success) stringResource(R.string.modules_install_success, message) else message) },
                confirmButton = {
                    WearButton(onClick = { installResult = null }) {
                        WearText(stringResource(android.R.string.ok))
                    }
                }
            )
        }

        if (showTokenDialog) {
            var tokenInput by remember { mutableStateOf(token) }
            var showPat by remember { mutableStateOf(false) }
            val showWarning = tokenInput.isNotBlank() && !TokenStore.isValidTokenFormat(tokenInput)
            WearAlertDialog(
                visible = true,
                onDismissRequest = {
                    showTokenDialog = false
                    if (token.isBlank()) onBack()
                },
                title = { WearText(stringResource(R.string.modules_catalog_token_required)) },
                confirmButton = {
                    WearButton(
                        onClick = {
                            val trimmed = tokenInput.trim()
                            TokenStore.setToken(context, trimmed)
                            token = trimmed
                            showTokenDialog = false
                            scope.launch {
                                isLoading = true
                                try {
                                    modules = discoveryManager.getModules(forceRefresh = true)
                                } catch (e: Exception) {
                                    error = e.message
                                }
                                isLoading = false
                            }
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
                            text = stringResource(R.string.modules_catalog_token_description),
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
                                modifier = Modifier.padding(top = 4.dp)
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

        if (showDangerDialog) {
            WearAlertDialog(
                visible = true,
                onDismissRequest = { showDangerDialog = false },
                title = { WearText(stringResource(R.string.modules_catalog_danger_title)) },
                confirmButton = {},
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WearText(
                            text = dangerReason ?: stringResource(R.string.modules_catalog_danger_message),
                            style = WearMaterialTheme.typography.bodySmall,
                            color = WearMaterialTheme.colorScheme.error
                        )
                        WearButton(
                            onClick = { showDangerDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = WearButtonDefaults.buttonColors()
                        ) {
                            WearText(stringResource(R.string.modules_catalog_danger_continue))
                        }
                        WearButton(
                            onClick = { showDangerDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = WearButtonDefaults.filledTonalButtonColors()
                        ) {
                            WearText(stringResource(R.string.modules_catalog_danger_go_back))
                        }
                    }
                }
            )
        }
    }
}
