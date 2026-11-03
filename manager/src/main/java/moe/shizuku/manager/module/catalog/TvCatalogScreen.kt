@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package moe.shizuku.manager.module.catalog

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.SurfaceDefaults as TvSurfaceDefaults
import androidx.tv.material3.Text as TvText
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.discovery.DiscoveredModule
import moe.shizuku.manager.module.discovery.ModuleDiscoveryManager
import moe.shizuku.manager.module.update.ModuleInstaller
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.TvMenuButton
import moe.shizuku.manager.ui.compose.TvShizukuTheme

private enum class TvCatalogSort(val labelRes: Int) {
    NEWEST(R.string.modules_catalog_sort_newest),
    OLDEST(R.string.modules_catalog_sort_oldest),
    STARS(R.string.modules_catalog_sort_stars),
    OFFICIAL(R.string.modules_catalog_sort_official)
}

@Composable
fun TvCatalogScreen(
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discoveryManager = remember { ModuleDiscoveryManager.getInstance(context) }
    val installer = remember { ModuleInstaller.getInstance() }

    var modules by remember { mutableStateOf<List<DiscoveredModule>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortBy by remember { mutableStateOf(TvCatalogSort.STARS) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    var installingId by remember { mutableStateOf<String?>(null) }
    var installResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showInstallDialog by remember { mutableStateOf<DiscoveredModule?>(null) }
    var showDangerDialog by remember { mutableStateOf(false) }
    var dangerReason by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val savedToken = TokenStore.getToken(context)
        if (savedToken.isNullOrBlank()) {
            showTokenDialog = true
            isLoading = false
        } else {
            tokenInput = savedToken
            isLoading = true
            try {
                val cached = discoveryManager.getModules(forceRefresh = false)
                if (cached.isNotEmpty()) {
                    modules = cached
                } else {
                    modules = discoveryManager.getModules(forceRefresh = true)
                }
            } catch (e: Exception) {
                error = e.message
            }
            isLoading = false
        }
    }

    val sortedModules = remember(modules, sortBy) {
        when (sortBy) {
            TvCatalogSort.NEWEST -> modules.sortedByDescending { it.lastChecked }
            TvCatalogSort.OLDEST -> modules.sortedBy { it.lastChecked }
            TvCatalogSort.STARS -> modules.sortedByDescending { it.stars }
            TvCatalogSort.OFFICIAL -> modules.sortedByDescending { it.isOfficial }
        }
    }

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
            }.onFailure {
                installResult = Pair(
                    context.getString(R.string.modules_catalog_install_failed, it.message ?: it.javaClass.simpleName),
                    false
                )
            }
        }
    }

    TvShizukuTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .size(width = 300.dp, height = 500.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TvText(
                    text = stringResource(R.string.modules_catalog),
                    style = TvMaterialTheme.typography.headlineMedium,
                    color = TvMaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                TvMenuButton(
                    icon = R.drawable.ic_arrow_back_24,
                    label = android.R.string.cancel,
                    onClick = onNavigateUp
                )

                TvCatalogSort.entries.forEach { sort ->
                    TvMenuButton(
                        icon = when (sort) {
                            TvCatalogSort.NEWEST -> R.drawable.ic_outline_info_24
                            TvCatalogSort.OLDEST -> R.drawable.ic_outline_info_24
                            TvCatalogSort.STARS -> R.drawable.ic_server_restart
                            TvCatalogSort.OFFICIAL -> R.drawable.ic_outline_open_in_new_24
                        },
                        label = sort.labelRes,
                        onClick = { sortBy = sort },
                        isSelected = sortBy == sort
                    )
                }
            }

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        TvText(
                            text = stringResource(R.string.modules_catalog_loading),
                            style = TvMaterialTheme.typography.headlineSmall,
                            color = TvMaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                error != null && sortedModules.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        TvText(
                            text = error ?: stringResource(R.string.modules_catalog_error),
                            style = TvMaterialTheme.typography.headlineSmall,
                            color = TvMaterialTheme.colorScheme.error
                        )
                    }
                }

                sortedModules.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        TvText(
                            text = stringResource(R.string.modules_catalog_empty),
                            style = TvMaterialTheme.typography.headlineSmall,
                            color = TvMaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(32.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        items(sortedModules, key = { "${it.repoFullName}/${it.moduleId}" }) { module ->
                            val isInstalling = installingId == module.moduleId

                            TvCatalogModuleCard(
                                module = module,
                                isInstalling = isInstalling,
                                onInstall = { showInstallDialog = module },
                                onViewOnGitHub = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(module.repoUrl))
                                    context.startActivity(intent)
                                },
                                onClick = { showInstallDialog = module }
                            )
                        }
                    }
                }
            }
        }

        if (showTokenDialog) {
            val showWarning = tokenInput.isNotBlank() && !TokenStore.isValidTokenFormat(tokenInput)
            AlertDialog(
                onDismissRequest = {
                    showTokenDialog = false
                    if (TokenStore.getToken(context).isNullOrBlank()) onNavigateUp()
                },
                title = { TvText(stringResource(R.string.modules_catalog_token_required)) },
                text = {
                    Column {
                        TvText(
                            text = stringResource(R.string.modules_catalog_token_description),
                            style = TvMaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { TvText(stringResource(R.string.modules_catalog_token_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (showWarning) {
                            TvText(
                                text = stringResource(R.string.modules_catalog_token_format_warning),
                                style = TvMaterialTheme.typography.bodySmall,
                                color = TvMaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TvSurface(
                        onClick = {
                            TokenStore.setToken(context, tokenInput.trim())
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
                        onClick = {
                            showTokenDialog = false
                            if (TokenStore.getToken(context).isNullOrBlank()) onNavigateUp()
                        },
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

        showInstallDialog?.let { module ->
            AlertDialog(
                onDismissRequest = { showInstallDialog = null },
                title = { TvText(module.moduleName) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvText(
                            text = stringResource(R.string.modules_catalog_install_description),
                            style = TvMaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvSurface(
                            onClick = { startInstall(module, ModuleSettings.InstallMode.SOURCES) },
                            shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                            colors = TvClickableSurfaceDefaults.colors(
                                containerColor = TvMaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            TvText(
                                text = stringResource(R.string.modules_catalog_install_sources),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }
                        TvSurface(
                            onClick = { startInstall(module, ModuleSettings.InstallMode.RELEASE) },
                            shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                            colors = TvClickableSurfaceDefaults.colors(
                                containerColor = TvMaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            TvText(
                                text = stringResource(R.string.modules_catalog_install_release),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }
                    }
                },
                dismissButton = {
                    TvSurface(
                        onClick = { showInstallDialog = null },
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

        installResult?.let { (message, success) ->
            AlertDialog(
                onDismissRequest = { installResult = null },
                title = { TvText(if (success) stringResource(R.string.modules_install_success, message) else message) },
                confirmButton = {
                    TvSurface(
                        onClick = { installResult = null },
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
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }

        if (showDangerDialog) {
            AlertDialog(
                onDismissRequest = { showDangerDialog = false },
                title = { TvText(stringResource(R.string.modules_catalog_danger_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvText(stringResource(R.string.modules_catalog_danger_message), style = TvMaterialTheme.typography.bodyMedium)
                        dangerReason?.let { TvText(it, style = TvMaterialTheme.typography.bodySmall, color = TvMaterialTheme.colorScheme.error) }
                        TvText(stringResource(R.string.modules_catalog_danger_prompt), style = TvMaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TvSurface(
                        onClick = { showDangerDialog = false },
                        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                        colors = TvClickableSurfaceDefaults.colors(containerColor = TvMaterialTheme.colorScheme.primaryContainer)
                    ) {
                        TvText(stringResource(R.string.modules_catalog_danger_continue), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    }
                },
                dismissButton = {
                    TvSurface(
                        onClick = { showDangerDialog = false },
                        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                        colors = TvClickableSurfaceDefaults.colors(containerColor = TvMaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        TvText(stringResource(R.string.modules_catalog_danger_go_back), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    }
}

@Composable
private fun TvCatalogModuleCard(
    module: DiscoveredModule,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onViewOnGitHub: () -> Unit,
    onClick: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.large),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TvText(
                        text = module.moduleName,
                        style = TvMaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = buildString {
                        module.author?.let { append(it) }
                        if (module.version != null) {
                            if (isNotEmpty()) append(" • ")
                            append(module.version)
                        }
                    }
                    if (meta.isNotEmpty()) {
                        TvText(
                            text = meta,
                            style = TvMaterialTheme.typography.bodySmall,
                            color = TvMaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (module.stars > 0) {
                    TvSurface(
                        shape = TvMaterialTheme.shapes.small,
                        colors = TvSurfaceDefaults.colors(
                            containerColor = TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TvText(
                                text = "\u2605",
                                fontSize = 14.sp,
                                color = TvMaterialTheme.colorScheme.primary
                            )
                            TvText(
                                text = "${module.stars}",
                                style = TvMaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            val description = module.description ?: module.repoDescription
            description?.let {
                TvText(
                    text = it,
                    style = TvMaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvSurface(
                    onClick = onInstall,
                    enabled = !isInstalling,
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer,
                        disabledContainerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    TvText(
                        text = stringResource(
                            if (isInstalling) R.string.modules_catalog_installing
                            else R.string.modules_catalog_install
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = TvMaterialTheme.typography.labelMedium
                    )
                }

                TvSurface(
                    onClick = onViewOnGitHub,
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = TvMaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ShizukuIcon(icon = R.drawable.ic_outline_open_in_new_24, modifier = Modifier.size(16.dp))
                        TvText(
                            text = stringResource(R.string.modules_catalog_view_github),
                            style = TvMaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
