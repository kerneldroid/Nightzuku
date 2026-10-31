package moe.shizuku.manager.module.catalog

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.discovery.DiscoveredModule
import moe.shizuku.manager.module.discovery.ModuleDiscoveryManager
import moe.shizuku.manager.module.update.ModuleInstaller
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class SortMode { NEWEST, OLDEST, STARS, OFFICIAL }

private const val MAX_BANNER_BYTES = 512 * 1024
private const val MAX_README_CHARS = 16000

private val sharedCatalogHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CatalogScreen(onNavigateUp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discoveryManager = remember { ModuleDiscoveryManager.getInstance(context) }
    val installer = remember { ModuleInstaller.getInstance() }

    var modules by remember { mutableStateOf<List<DiscoveredModule>>(emptyList()) }
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(SortMode.STARS) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf<DiscoveredModule?>(null) }
    var detailModule by remember { mutableStateOf<DiscoveredModule?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var installedModuleName by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf<String?>(TokenStore.getToken(context)) }
    var pendingDangerModule by remember { mutableStateOf<DiscoveredModule?>(null) }
    var showDangerDialog by remember { mutableStateOf(false) }
    var dangerReason by remember { mutableStateOf<String?>(null) }
    var showPropertiesSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = detailModule != null) {
        detailModule = null
    }

    LaunchedEffect(token) {
        if (token.isNullOrBlank()) {
            showTokenDialog = true
            isLoading = false
        } else {
            val cached = discoveryManager.getModules(forceRefresh = false)
            if (cached.isNotEmpty()) {
                modules = cached
                isLoading = false
            } else {
                isLoading = true
                error = null
                try {
                    modules = discoveryManager.getModules(forceRefresh = true)
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(modules) {
        installedIds = AdbModuleManager.listModules(context).map { it.id }.toSet()
    }

    val sortedModules = remember(modules, sortMode) {
        when (sortMode) {
            SortMode.NEWEST -> modules.sortedByDescending { it.lastChecked }
            SortMode.OLDEST -> modules.sortedBy { it.lastChecked }
            SortMode.STARS -> modules.sortedByDescending { it.stars }
            SortMode.OFFICIAL -> modules.sortedByDescending { it.isOfficial }
        }
    }

    ShizukuExpressiveTheme {
        run {
            val activeModule = detailModule
            if (activeModule != null) {
                ModuleDetailScreen(
                    module = activeModule,
                    installed = activeModule.moduleId in installedIds,
                    installing = installing == activeModule.moduleId,
                    onBack = { detailModule = null },
                    onInstall = { showInstallDialog = activeModule },
                    onViewOnGitHub = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(activeModule.repoUrl)))
                    }
                )
            } else {
                CatalogListScreen(
                    modules = sortedModules,
                    installedIds = installedIds,
                    isLoading = isLoading,
                    error = error,
                    sortMode = sortMode,
                    installing = installing,
                    onSortChange = { sortMode = it },
                    onRefresh = {
                        scope.launch {
                            isLoading = true
                            error = null
                            try {
                                modules = discoveryManager.getModules(forceRefresh = true)
                                installedIds = AdbModuleManager.listModules(context).map { it.id }.toSet()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    onCardClick = {
                        scope.launch {
                            val isDanger = withContext(Dispatchers.IO) { checkModuleDanger(sharedCatalogHttpClient, it) }
                            if (isDanger != null) {
                                pendingDangerModule = it
                                dangerReason = isDanger
                                showDangerDialog = true
                            } else {
                                detailModule = it
                            }
                        }
                    },
                    onInstall = { showInstallDialog = it },
                    onViewOnGitHub = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.repoUrl)))
                    },
                    onOpenProperties = { showPropertiesSheet = true },
                    onOpenTokenDialog = { showTokenDialog = true },
                    onNavigateUp = onNavigateUp
                )
            }
        }

        if (showPropertiesSheet) {
            CatalogPropertiesSheet(
                modules = modules,
                installedIds = installedIds,
                sortMode = sortMode,
                hasToken = !token.isNullOrBlank(),
                onDismiss = { showPropertiesSheet = false }
            )
        }

        if (showTokenDialog) {
            TokenInputDialog(
                initialToken = token.orEmpty(),
                onDismiss = {
                    showTokenDialog = false
                    if (TokenStore.getToken(context).isNullOrBlank()) onNavigateUp()
                },
                onTokenSet = { pat ->
                    if (pat.isBlank()) {
                        TokenStore.clearToken(context)
                        token = null
                    } else {
                        TokenStore.setToken(context, pat)
                        token = pat
                    }
                    showTokenDialog = false
                }
            )
        }

        showInstallDialog?.let { module ->
            InstallModeDialog(
                module = module,
                onDismiss = { showInstallDialog = null },
                onInstall = { mode ->
                    showInstallDialog = null
                    ModuleSettings.setInstallMode(mode)
                    installing = module.moduleId
                    scope.launch {
                        val owner = module.repoFullName.substringBefore('/')
                        val repo = module.repoFullName.substringAfter('/')
                        val result = installer.installModule(context, module.moduleId, owner, repo, module.subPath)
                        installing = null
                        result.fold(
                            onSuccess = {
                                installedIds = AdbModuleManager.listModules(context).map { it.id }.toSet()
                                installedModuleName = module.moduleName
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.modules_install_success, module.moduleName),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onFailure = {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.modules_catalog_install_failed,
                                        it.message ?: it.javaClass.simpleName
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
            )
        }

        installedModuleName?.let { name ->
            AlertDialog(
                onDismissRequest = { installedModuleName = null },
                title = { Text(stringResource(R.string.modules_install_success, name)) },
                confirmButton = {
                    TextButton(onClick = { installedModuleName = null; onNavigateUp() }) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            )
        }

        if (showDangerDialog) {
            AlertDialog(
                onDismissRequest = { showDangerDialog = false; pendingDangerModule = null },
                title = { Text("Potentially unsafe module") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "This module has unusually large content that may cause issues:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        dangerReason?.let {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    it,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Text("Do you want to continue anyway?", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    FilledTonalButton(onClick = {
                        showDangerDialog = false
                        detailModule = pendingDangerModule
                        pendingDangerModule = null
                    }) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = { showDangerDialog = false; pendingDangerModule = null }) { Text("Go back") }
                }
            )
        }
    }
}

private suspend fun checkModuleDanger(client: OkHttpClient, module: DiscoveredModule): String? {
    return withContext(Dispatchers.IO) {
        val owner = module.repoFullName.substringBefore('/')
        val repo = module.repoFullName.substringAfter('/')
        val subPath = module.subPath?.trim('/')?.let { "$it/" } ?: ""
        val candidates = listOf(
            "https://raw.githubusercontent.com/$owner/$repo/main/${subPath}banner.png",
            "https://raw.githubusercontent.com/$owner/$repo/master/${subPath}banner.png",
            "https://raw.githubusercontent.com/$owner/$repo/main/${subPath}banner.jpg",
            "https://raw.githubusercontent.com/$owner/$repo/master/${subPath}banner.jpg"
        )
        for (url in candidates) {
            try {
                val req = Request.Builder().url(url).head().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val size = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                        if (size > MAX_BANNER_BYTES) {
                            return@withContext "Banner too large: ${size / 1024}KB (max ${MAX_BANNER_BYTES / 1024}KB)"
                        }
                        return@withContext null
                    }
                }
            } catch (_: Exception) {
            }
        }
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CatalogListScreen(
    modules: List<DiscoveredModule>,
    installedIds: Set<String>,
    isLoading: Boolean,
    error: String?,
    sortMode: SortMode,
    installing: String?,
    onSortChange: (SortMode) -> Unit,
    onRefresh: () -> Unit,
    onCardClick: (DiscoveredModule) -> Unit,
    onInstall: (DiscoveredModule) -> Unit,
    onViewOnGitHub: (DiscoveredModule) -> Unit,
    onOpenProperties: () -> Unit,
    onOpenTokenDialog: () -> Unit,
    onNavigateUp: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ShizukuLazyScaffold(
        title = stringResource(R.string.modules_catalog_title),
        onNavigateUp = onNavigateUp,
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        actions = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    ShizukuIcon(
                        icon = R.drawable.ic_more_vert_24,
                        contentDescription = null
                    )
                }
                CatalogOverflowMenu(
                    expanded = menuExpanded,
                    sortMode = sortMode,
                    onDismiss = { menuExpanded = false },
                    onSortChange = { mode ->
                        menuExpanded = false
                        onSortChange(mode)
                    },
                    onProperties = {
                        menuExpanded = false
                        onOpenProperties()
                    },
                    onTokenSettings = {
                        menuExpanded = false
                        onOpenTokenDialog()
                    }
                )
            }
        }
    ) {
        if (isLoading && modules.isEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LoadingIndicator(Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.modules_catalog_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!isLoading && error != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        if (!isLoading && modules.isEmpty() && error == null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            stringResource(R.string.modules_catalog_empty),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(modules, key = { "${it.repoFullName}/${it.moduleId}" }) { module ->
            CatalogModuleCard(
                module = module,
                installed = module.moduleId in installedIds,
                installing = installing == module.moduleId,
                onCardClick = { onCardClick(module) },
                onInstall = { onInstall(module) },
                onViewOnGitHub = { onViewOnGitHub(module) },
                modifier = Modifier.animateItem()
            )
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun ModuleDetailScreen(
    module: DiscoveredModule,
    installed: Boolean,
    installing: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onViewOnGitHub: () -> Unit
) {
    var readmeContent by remember { mutableStateOf<String?>(null) }
    var isLoadingReadme by remember { mutableStateOf(true) }
    var bannerUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(module) {
        isLoadingReadme = true
        val owner = module.repoFullName.substringBefore('/')
        val repo = module.repoFullName.substringAfter('/')
        val subPath = module.subPath?.trim('/')?.let { "$it/" } ?: ""

        withContext(Dispatchers.IO) {
            coroutineScope {
                val bannerDeferred = async {
                    val candidates = listOf(
                        "https://raw.githubusercontent.com/$owner/$repo/main/${subPath}banner.png",
                        "https://raw.githubusercontent.com/$owner/$repo/master/${subPath}banner.png",
                        "https://raw.githubusercontent.com/$owner/$repo/main/${subPath}banner.jpg",
                        "https://raw.githubusercontent.com/$owner/$repo/master/${subPath}banner.jpg"
                    )
                    for (url in candidates) {
                        try {
                            val req = Request.Builder().url(url).head().build()
                            sharedCatalogHttpClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) return@async url
                            }
                        } catch (_: Exception) {
                        }
                    }
                    null
                }

                val readmeDeferred = async {
                    val candidates = listOf(
                        "https://raw.githubusercontent.com/$owner/$repo/main/README.md",
                        "https://raw.githubusercontent.com/$owner/$repo/master/README.md",
                        "https://raw.githubusercontent.com/$owner/$repo/main/${subPath}README.md",
                        "https://raw.githubusercontent.com/$owner/$repo/master/${subPath}README.md"
                    )
                    for (url in candidates) {
                        try {
                            val req = Request.Builder().url(url).build()
                            sharedCatalogHttpClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val body = resp.body?.string()?.take(MAX_README_CHARS)
                                    if (!body.isNullOrBlank()) return@async body
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    null
                }

                bannerUrl = bannerDeferred.await()
                readmeContent = readmeDeferred.await()
            }
        }
        isLoadingReadme = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(module.moduleName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onViewOnGitHub) {
                        ShizukuIcon(R.drawable.ic_outline_open_in_new_24, modifier = Modifier.size(20.dp))
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (installed) {
                        FilledTonalButton(
                            modifier = Modifier.weight(1f).height(44.dp),
                            onClick = { onInstall() }
                        ) {
                            Icon(Icons.Rounded.Update, null, modifier = Modifier.size(18.dp).padding(end = 6.dp))
                            Text(stringResource(R.string.modules_update_action), style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Button(
                            modifier = Modifier.weight(1f).height(44.dp),
                            enabled = !installing,
                            onClick = onInstall
                        ) {
                            ShizukuIcon(R.drawable.ic_outline_arrow_upward_24, modifier = Modifier.size(18.dp).padding(end = 6.dp))
                            Text(stringResource(R.string.modules_catalog_install), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.height(44.dp),
                        onClick = onViewOnGitHub
                    ) {
                        ShizukuIcon(R.drawable.ic_outline_open_in_new_24, modifier = Modifier.size(18.dp).padding(end = 6.dp))
                        Text("GitHub", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                if (bannerUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(bannerUrl).crossfade(true).build(),
                        contentDescription = module.moduleName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            module.moduleName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(module.moduleName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        module.author ?: module.repoFullName.substringBefore('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(onClick = {}, label = { Text(module.moduleId) })
                        if (module.hasNightzukuGuard) {
                            AssistChip(
                                onClick = {},
                                leadingIcon = {
                                    ShizukuIcon(
                                        R.drawable.ic_shield_24,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                label = { Text(stringResource(R.string.modules_nightzuku_guard_chip)) }
                            )
                        }
                        module.version?.let {
                            AssistChip(onClick = {}, label = { Text("v${it.removePrefix("v")}") })
                        }
                        AssistChip(
                            onClick = {},
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Star,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            label = { Text("${module.stars}") }
                        )
                        AssistChip(
                            onClick = {},
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.CloudDownload,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            label = { Text(formatCount(module.downloads)) }
                        )
                        if (module.isOfficial) {
                            AssistChip(
                                onClick = {},
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                ),
                                label = { Text(stringResource(R.string.modules_catalog_sort_official)) }
                            )
                        }
                        if (isModuleOutdated(module)) {
                            AssistChip(
                                onClick = {},
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                label = { Text(stringResource(R.string.modules_outdated)) }
                            )
                        }
                        if (installed) {
                            AssistChip(
                                onClick = {},
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                label = { Text(stringResource(R.string.modules_enabled)) }
                            )
                        }
                    }

                    (module.description ?: module.repoDescription)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (module.repoUrl.isNotBlank()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("README", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                        if (isLoadingReadme) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                LoadingIndicator(Modifier.size(28.dp))
                            }
                        } else if (!readmeContent.isNullOrBlank()) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ReadmeText(content = readmeContent!!, modifier = Modifier.padding(16.dp))
                            }
                        } else {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "README not available",
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

private const val OUTDATED_THRESHOLD_MS = 90L * 24 * 60 * 60 * 1000

private fun isModuleOutdated(module: DiscoveredModule): Boolean {
    return module.lastCommitAt > 0L &&
        System.currentTimeMillis() - module.lastCommitAt > OUTDATED_THRESHOLD_MS
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CatalogOverflowMenu(
    expanded: Boolean,
    sortMode: SortMode,
    onDismiss: () -> Unit,
    onSortChange: (SortMode) -> Unit,
    onProperties: () -> Unit,
    onTokenSettings: () -> Unit
) {
    val sortOptions = listOf(
        Triple(SortMode.STARS, R.string.modules_catalog_sort_stars, Icons.Rounded.Star),
        Triple(SortMode.NEWEST, R.string.modules_catalog_sort_newest, Icons.Rounded.Schedule),
        Triple(SortMode.OLDEST, R.string.modules_catalog_sort_oldest, Icons.Rounded.History),
        Triple(SortMode.OFFICIAL, R.string.modules_catalog_sort_official, Icons.Rounded.Verified)
    )

    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(index = 0, count = 2)
        ) {
            sortOptions.forEach { (mode, labelRes, icon) ->
                val selected = sortMode == mode
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(labelRes),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    onClick = { onSortChange(mode) }
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(index = 1, count = 2)
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.modules_catalog_properties)) },
                trailingIcon = { Icon(Icons.Rounded.Info, null, Modifier.size(20.dp)) },
                onClick = onProperties
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_settings_github_pat)) },
                trailingIcon = { Icon(Icons.Rounded.VpnKey, null, Modifier.size(20.dp)) },
                onClick = onTokenSettings
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogPropertiesSheet(
    modules: List<DiscoveredModule>,
    installedIds: Set<String>,
    sortMode: SortMode,
    hasToken: Boolean,
    onDismiss: () -> Unit
) {
    val officialCount = remember(modules) { modules.count { it.isOfficial } }
    val guardedCount = remember(modules) { modules.count { it.hasNightzukuGuard } }
    val installedCount = remember(modules, installedIds) { modules.count { it.moduleId in installedIds } }
    val sortLabel = stringResource(
        when (sortMode) {
            SortMode.STARS -> R.string.modules_catalog_sort_stars
            SortMode.NEWEST -> R.string.modules_catalog_sort_newest
            SortMode.OLDEST -> R.string.modules_catalog_sort_oldest
            SortMode.OFFICIAL -> R.string.modules_catalog_sort_official
        }
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.modules_catalog_properties_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            CatalogPropertyRow(
                label = stringResource(R.string.modules_catalog_prop_total),
                value = modules.size.toString()
            )
            CatalogPropertyRow(
                label = stringResource(R.string.modules_catalog_prop_official),
                value = officialCount.toString()
            )
            CatalogPropertyRow(
                label = stringResource(R.string.modules_catalog_prop_guarded),
                value = guardedCount.toString()
            )
            CatalogPropertyRow(
                label = stringResource(R.string.modules_catalog_prop_installed),
                value = installedCount.toString()
            )
            CatalogPropertyRow(
                label = stringResource(R.string.modules_catalog_prop_sort),
                value = sortLabel
            )
            CatalogPropertyRow(
                label = stringResource(R.string.modules_catalog_prop_token),
                value = stringResource(
                    if (hasToken) R.string.update_settings_github_pat_set
                    else R.string.update_settings_github_pat_unset
                )
            )
        }
    }
}

@Composable
private fun CatalogPropertyRow(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CatalogModuleCard(
    module: DiscoveredModule,
    installed: Boolean,
    installing: Boolean,
    onCardClick: () -> Unit,
    onInstall: () -> Unit,
    onViewOnGitHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (installed) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(260),
        label = "catalog-card-container"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onCardClick)
            .animateContentSize(animationSpec = tween(280)),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CatalogAvatar(
                    ownerAvatar = module.ownerAvatar,
                    moduleName = module.moduleName,
                    modifier = Modifier.size(48.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        module.moduleName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.modules_version_label, module.version ?: "-"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(
                            R.string.modules_author_label,
                            module.author ?: module.repoFullName.substringBefore('/')
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            (module.description ?: module.repoDescription)?.let { desc ->
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = onCardClick,
                    label = { Text(module.moduleId) }
                )
                if (module.hasNightzukuGuard) {
                    AssistChip(
                        onClick = onCardClick,
                        leadingIcon = {
                            ShizukuIcon(
                                R.drawable.ic_shield_24,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        label = { Text(stringResource(R.string.modules_nightzuku_guard_chip)) }
                    )
                }
                AssistChip(
                    onClick = onCardClick,
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Star,
                            null,
                            Modifier.size(16.dp)
                        )
                    },
                    label = { Text("${module.stars}") }
                )
                AssistChip(
                    onClick = onCardClick,
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.CloudDownload,
                            null,
                            Modifier.size(16.dp)
                        )
                    },
                    label = { Text(formatCount(module.downloads)) }
                )
                if (module.isOfficial) {
                    AssistChip(
                        onClick = onCardClick,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        label = { Text(stringResource(R.string.modules_catalog_sort_official)) }
                    )
                }
                if (isModuleOutdated(module)) {
                    AssistChip(
                        onClick = onCardClick,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        label = { Text(stringResource(R.string.modules_outdated)) }
                    )
                }
                if (installed) {
                    AssistChip(
                        onClick = onCardClick,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        label = { Text(stringResource(R.string.modules_enabled)) }
                    )
                }
            }

            if (installing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (installed) {
                    FilledTonalButton(
                        modifier = Modifier.widthIn(min = 116.dp),
                        enabled = !installing,
                        onClick = onInstall
                    ) {
                        ShizukuIcon(
                            R.drawable.ic_outline_arrow_upward_24,
                            modifier = Modifier.padding(end = 8.dp).size(18.dp)
                        )
                        Text(stringResource(R.string.modules_update_action))
                    }
                } else {
                    Button(
                        modifier = Modifier.widthIn(min = 116.dp),
                        enabled = !installing,
                        onClick = onInstall
                    ) {
                        ShizukuIcon(
                            R.drawable.ic_outline_arrow_upward_24,
                            modifier = Modifier.padding(end = 8.dp).size(18.dp)
                        )
                        Text(stringResource(R.string.modules_catalog_install))
                    }
                }
                OutlinedButton(
                    modifier = Modifier.widthIn(min = 116.dp),
                    onClick = onViewOnGitHub
                ) {
                    ShizukuIcon(
                        R.drawable.ic_outline_open_in_new_24,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp)
                    )
                    Text("GitHub")
                }
            }
        }
    }
}

@Composable
private fun CatalogAvatar(ownerAvatar: String, moduleName: String, modifier: Modifier = Modifier) {
    val initials = remember(moduleName) { moduleName.take(2).uppercase(Locale.getDefault()) }
    if (ownerAvatar.isNotBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(ownerAvatar).crossfade(true).build(),
            contentDescription = moduleName,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initials,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TokenInputDialog(
    initialToken: String = "",
    onDismiss: () -> Unit,
    onTokenSet: (String) -> Unit
) {
    var tokenInput by remember { mutableStateOf(initialToken) }
    var showToken by remember { mutableStateOf(false) }
    val showWarning = tokenInput.isNotBlank() && !TokenStore.isValidTokenFormat(tokenInput)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modules_catalog_token_required)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.modules_catalog_token_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text(stringResource(R.string.modules_catalog_token_hint)) },
                    visualTransformation = if (showToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(
                                        if (showToken) R.string.update_settings_github_pat_hide
                                        else R.string.update_settings_github_pat_show
                                    )
                                )
                            }
                            if (tokenInput.isNotEmpty()) {
                                IconButton(onClick = { tokenInput = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.update_settings_github_pat_clear)
                                    )
                                }
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showWarning) {
                    Text(
                        stringResource(R.string.modules_catalog_token_format_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTokenSet(tokenInput) }, enabled = tokenInput.isNotBlank()) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallModeDialog(
    module: DiscoveredModule,
    onDismiss: () -> Unit,
    onInstall: (ModuleSettings.InstallMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(module.moduleName) },
        text = {
            Text(
                text = stringResource(R.string.modules_catalog_install_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    onClick = { onInstall(ModuleSettings.InstallMode.SOURCES) }
                ) {
                    ShizukuIcon(R.drawable.ic_code_24dp, modifier = Modifier.size(18.dp).padding(end = 8.dp))
                    Text(stringResource(R.string.modules_catalog_install_sources), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    onClick = { onInstall(ModuleSettings.InstallMode.RELEASE) }
                ) {
                    ShizukuIcon(R.drawable.ic_outline_arrow_upward_24, modifier = Modifier.size(18.dp).padding(end = 8.dp))
                    Text(stringResource(R.string.modules_catalog_install_release), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun ReadmeText(content: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val blockQuoteShape = remember { RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var inCodeBlock = false
        val codeBlockLines = mutableListOf<String>()

        for (line in content.lines()) {
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(
                            text = codeBlockLines.joinToString("\n"),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    codeBlockLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }
            if (inCodeBlock) {
                codeBlockLines.add(line)
                continue
            }

            val t = trimmed.trim()
            if (t.isBlank()) continue

            val linkColor = MaterialTheme.colorScheme.primary
            when {
                t.startsWith("#### ") -> StyledText(parseInline(t.removePrefix("#### "), linkColor), MaterialTheme.typography.bodySmall, FontWeight.Medium)
                t.startsWith("### ") -> StyledText(parseInline(t.removePrefix("### "), linkColor), MaterialTheme.typography.titleSmall, FontWeight.Medium)
                t.startsWith("## ") -> StyledText(parseInline(t.removePrefix("## "), linkColor), MaterialTheme.typography.titleMedium, FontWeight.SemiBold)
                t.startsWith("# ") -> StyledText(parseInline(t.removePrefix("# "), linkColor), MaterialTheme.typography.titleLarge, FontWeight.Bold)
                t == "---" || t == "***" || t == "___" -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                t.startsWith("> ") -> {
                    Surface(
                        shape = blockQuoteShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.border(BorderStroke(3.dp, linkColor), blockQuoteShape)
                    ) {
                        StyledText(
                            parseInline(t.removePrefix("> "), linkColor),
                            MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
                        )
                    }
                }
                t.startsWith("![") -> {
                    val match = Regex("!\\[([^]]*)]\\(([^)]+)\\)").find(t)
                    if (match != null) {
                        val (_, alt, url) = match.groupValues
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                            contentDescription = alt.ifBlank { null },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            contentScale = ContentScale.FillWidth
                        )
                    } else {
                        StyledText(parseInline(t, linkColor), MaterialTheme.typography.bodySmall)
                    }
                }
                Regex("^\\d+\\.\\s").containsMatchIn(t) -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    val indentDp = (indent * 16).dp
                    val match = Regex("^(\\d+)\\.\\s(.+)").find(t)!!
                    Row(modifier = Modifier.padding(start = indentDp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${match.groupValues[1]}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StyledText(parseInline(match.groupValues[2], linkColor), MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                t.startsWith("- ") || t.startsWith("* ") -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    val indentDp = (indent * 16).dp
                    val itemText = t.removePrefix("- ").removePrefix("* ")
                    Row(modifier = Modifier.padding(start = indentDp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StyledText(parseInline(itemText, linkColor), MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                t.startsWith("|") -> {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(t, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> StyledText(parseInline(t, linkColor), MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    text = codeBlockLines.joinToString("\n"),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StyledText(
    annotated: androidx.compose.ui.text.AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    fontWeight: FontWeight? = null,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val styleWithWeight = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    val colorSpecified = color != androidx.compose.ui.graphics.Color.Unspecified
    val hasLinks = annotated.getStringAnnotations("URL", 0, annotated.length).isNotEmpty()

    if (hasLinks) {
        Text(
            text = annotated,
            style = if (colorSpecified) styleWithWeight.copy(color = color) else styleWithWeight,
            modifier = modifier.clickable {
                annotated.getStringAnnotations("URL", 0, annotated.length).firstOrNull()?.let {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.item)))
                }
            }
        )
    } else {
        Text(
            text = annotated,
            style = if (colorSpecified) styleWithWeight.copy(color = color) else styleWithWeight,
            modifier = modifier
        )
    }
}

private fun parseInline(text: String, linkColor: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2).let { it > i + 2 } -> {
                    val end = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }
                text[i] == '*' && (i + 1 < text.length) && text[i + 1] != '*' && text.indexOf('*', i + 1).let { it > i + 1 } -> {
                    val end = text.indexOf('*', i + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1).let { if (it == -1) text.length else it }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = (14 * 0.9f).sp)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                text.startsWith("[", i) -> {
                    val match = Regex("^\\[([^]]+)]\\(([^)]+)\\)").find(text, i)
                    if (match != null) {
                        val label = match.groupValues[1]
                        val url = match.groupValues[2]
                        pushStringAnnotation(tag = "URL", annotation = url)
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                        pop()
                        i = match.range.last + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
