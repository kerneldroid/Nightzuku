@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package moe.shizuku.manager.module

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.Text as TvText
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.module.update.ModuleInstaller
import moe.shizuku.manager.module.update.UpdateResult
import moe.shizuku.manager.module.update.UpdateSettingsScreen
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.ui.compose.ExpressiveSwitch
import moe.shizuku.manager.ui.compose.MonospaceLog
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold

class ModulesActivity : AppActivity() {

    private var reloadTrigger by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        reloadTrigger++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var modules by remember { mutableStateOf<List<AdbModule>>(emptyList(), neverEqualPolicy()) }
            var output by remember { mutableStateOf<Pair<String, String>?>(null) }
            var deleteTarget by remember { mutableStateOf<AdbModule?>(null) }
            var updateDialogTarget by remember { mutableStateOf<Pair<AdbModule, UpdateResult>?>(null) }
            var pendingCommand by remember { mutableStateOf<ModuleCommandRequest?>(null) }
            var runningModuleId by remember { mutableStateOf<String?>(null) }
            var batchProgressText by remember { mutableStateOf<String?>(null) }
            var allowBackgroundActions by remember { mutableStateOf(ModuleSettings.allowBackgroundActions()) }
            var pinRefreshTick by remember { mutableIntStateOf(0) }
            var showUpdateSettings by remember { mutableStateOf(false) }

            val isCheckingModules by NightDogClient.isCheckingModules.collectAsState()
            val checkReport by NightDogClient.lastCheckReport.collectAsState()

            fun reload(refreshHealth: Boolean = true) {
                scope.launch {
                    allowBackgroundActions = ModuleSettings.allowBackgroundActions()
                    modules = AdbModuleManager.listModules(context)
                    pinRefreshTick++
                    if (refreshHealth) {
                        NightDogClient.runBackgroundCheck(
                            context = context,
                            forceUpdateCheck = false,
                            runServices = false
                        )
                    }
                }
            }

            fun runManualCheckNow() {
                if (isCheckingModules || batchProgressText != null) return
                scope.launch {
                    val report = NightDogClient.runManualCheck(
                        context = context,
                        checkUpdates = true,
                        forceRunServices = true
                    )
                    modules = AdbModuleManager.listModules(context)
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.modules_background_check_toast,
                            report.healthyModules,
                            report.totalModules,
                            report.updatesAvailableCount
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            fun updateModuleNow(
                module: AdbModule,
                updateResult: UpdateResult?,
                preferredMode: ModuleSettings.InstallMode? = null
            ) {
                scope.launch {
                    runningModuleId = module.id
                    ModuleInstaller.getInstance().updateModule(context, module, updateResult, preferredMode)
                        .onSuccess { updated ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.modules_update_success, updated.name, updated.version ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                            modules = AdbModuleManager.listModules(context)
                            NightDogClient.runManualCheck(context, checkUpdates = true, forceRunServices = false)
                        }
                        .onFailure { err ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.modules_install_failed, err.message ?: err.javaClass.simpleName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    runningModuleId = null
                }
            }

            fun updateAllModulesNow() {
                val report = checkReport ?: return
                val targets = modules.mapNotNull { mod ->
                    if (ModuleSettings.isModuleUpdateIgnored(mod.id)) return@mapNotNull null
                    val upd = report.updatesByModule[mod.id] ?: return@mapNotNull null
                    if (upd.hasUpdate) mod to upd else null
                }
                if (targets.isEmpty() || batchProgressText != null) return

                scope.launch {
                    batchProgressText = context.getString(R.string.modules_update_all_progress, 1, targets.size)
                    val summary = ModuleInstaller.getInstance().updateAllModules(
                        context = context,
                        targets = targets,
                        onProgress = { cur, total, name ->
                            batchProgressText = "${context.getString(R.string.modules_update_all_progress, cur, total)}: $name"
                        }
                    )
                    batchProgressText = null
                    modules = AdbModuleManager.listModules(context)
                    NightDogClient.runManualCheck(context, checkUpdates = true, forceRunServices = false)
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.modules_update_all_result,
                            summary.successCount,
                            summary.total
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            fun runModuleAction(module: AdbModule) {
                scope.launch {
                    runningModuleId = module.id
                    output = runCatching {
                        val result = AdbModuleManager.runAction(module)
                        context.getString(R.string.modules_action_result, result.exitCode) to result.combinedOutput
                    }.getOrElse {
                        context.getString(R.string.modules_action_failed) to (it.message ?: it.javaClass.simpleName)
                    }
                    runningModuleId = null
                    reload(refreshHealth = true)
                }
            }

            val zipLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                scope.launch {
                    runCatching {
                        AdbModuleManager.install(context, uri)
                    }.onSuccess { module ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.modules_install_success, module.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        reload(refreshHealth = true)
                    }.onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.modules_install_failed, it.message ?: it.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            LaunchedEffect(reloadTrigger) {
                allowBackgroundActions = ModuleSettings.allowBackgroundActions()
                modules = AdbModuleManager.listModules(context)
                NightDogClient.runBackgroundCheck(
                    context = context,
                    forceUpdateCheck = false,
                    runServices = false
                )
            }

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@ModulesActivity)
            val isTv = moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@ModulesActivity)
            var showCatalog by remember { mutableStateOf(false) }

            if (showUpdateSettings && !isWatch && !isTv) {
                BackHandler {
                    showUpdateSettings = false
                    reload(refreshHealth = false)
                }
                UpdateSettingsScreen(
                    onNavigateUp = {
                        showUpdateSettings = false
                        reload(refreshHealth = false)
                    }
                )
                return@setContent
            }

            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {

                    if (showCatalog) {
                        moe.shizuku.manager.module.catalog.WearCatalogScreen(
                            onBack = { showCatalog = false },
                            onModuleInstalled = { showCatalog = false; reload() }
                        )
                    } else {
                        WearModulesScreen(
                            modules = modules,
                            busyId = runningModuleId,
                            isCheckingModules = isCheckingModules || batchProgressText != null,
                            checkReport = checkReport,
                            onCheckModules = { runManualCheckNow() },
                            onUpdateAllModules = { updateAllModulesNow() },
                            onUpdateModule = { module, updateRes -> updateModuleNow(module, updateRes) },
                            onToggle = { module ->
                                scope.launch {
                                    AdbModuleManager.setEnabled(module, !module.enabled)
                                    reload()
                                }
                            },
                            onRunAction = { module -> runModuleAction(module) },
                            onRunService = { module ->
                                scope.launch {
                                    runningModuleId = module.id
                                    output = runCatching {
                                        val result = AdbModuleManager.runService(module)
                                        context.getString(R.string.modules_service_result, result.exitCode) to result.combinedOutput
                                    }.getOrElse {
                                        context.getString(R.string.modules_service_failed) to (it.message ?: it.javaClass.simpleName)
                                    }
                                    runningModuleId = null
                                    reload(refreshHealth = true)
                                }
                            },
                            onDelete = { module -> deleteTarget = module },
                            onTrustChange = { module, trusted ->
                                scope.launch {
                                    ModuleSettings.setModuleTrusted(module.id, trusted)
                                    if (trusted && !module.enabled) {
                                        AdbModuleManager.setEnabled(module, true)
                                    }
                                    reload()
                                }
                            },
                            onOpenWebUi = { module ->
                                startActivity(
                                    Intent(this@ModulesActivity, ModuleWebViewActivity::class.java)
                                        .putExtra(ModuleWebViewActivity.EXTRA_MODULE_ID, module.id)
                                )
                            },
                            onInstallZip = { zipLauncher.launch(MODULE_MIME_TYPES) },
                            onOpenCatalog = { showCatalog = true }
                        )
                    }

                    output?.let { (title, text) ->
                        WearAlertDialog(
                            visible = true,
                            onDismissRequest = { output = null },
                            title = { WearText(title) },
                            text = {
                                WearText(
                                    text = text,
                                    style = WearMaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp)
                                )
                            },
                            confirmButton = {
                                WearButton(onClick = { output = null }) {
                                    WearText(stringResource(android.R.string.ok))
                                }
                            }
                        )
                    }

                    deleteTarget?.let { module ->
                        WearAlertDialog(
                            visible = true,
                            onDismissRequest = { deleteTarget = null },
                            title = { WearText(stringResource(R.string.modules_delete_title)) },
                            text = { WearText(stringResource(R.string.modules_delete_message, module.name)) },
                            confirmButton = {
                                WearButton(
                                    onClick = {
                                        scope.launch {
                                            AdbModuleManager.delete(module)
                                            deleteTarget = null
                                            reload()
                                        }
                                    }
                                ) {
                                    WearText(stringResource(R.string.modules_delete))
                                }
                            },
                            dismissButton = {
                                WearFilledTonalButton(onClick = { deleteTarget = null }) {
                                    WearText(stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }

                }
            } else if (isTv) {
                var showCatalog by remember { mutableStateOf(false) }
                var showLabFeatures by remember { mutableStateOf(false) }
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showCatalog) {
                            moe.shizuku.manager.module.catalog.TvCatalogScreen(
                                onNavigateUp = { showCatalog = false; reload() }
                            )
                        } else if (showLabFeatures) {
                            moe.shizuku.manager.settings.TvLabMenuScreen(
                                onNavigateUp = { showLabFeatures = false; reload() }
                            )
                        } else {
                            TvModulesScreen(
                                modules = modules,
                                isCheckingModules = isCheckingModules || batchProgressText != null,
                                checkReport = checkReport,
                                onNavigateUp = { finish() },
                                onInstallZip = { zipLauncher.launch(MODULE_MIME_TYPES) },
                                onCheckModules = { runManualCheckNow() },
                                onUpdateAllModules = { updateAllModulesNow() },
                                onUpdateModule = { module, updateRes -> updateModuleNow(module, updateRes) },
                                onToggle = { module ->
                                    scope.launch {
                                        AdbModuleManager.setEnabled(module, !module.enabled)
                                        reload()
                                    }
                                },
                                onRunAction = { module ->
                                    if (!ModuleSettings.isModuleTrusted(module.id) && ModuleSettings.recommandForAction()) {
                                        pendingCommand = ModuleCommandRequest(
                                            module = module,
                                            source = ModuleCommandSource.ACTION,
                                            command = module.actionCommandPreview()
                                        )
                                    } else {
                                        runModuleAction(module)
                                    }
                                },
                                onRunService = { module ->
                                    scope.launch {
                                        runningModuleId = module.id
                                        output = runCatching {
                                            val result = AdbModuleManager.runService(module)
                                            context.getString(R.string.modules_service_result, result.exitCode) to result.combinedOutput
                                        }.getOrElse {
                                            context.getString(R.string.modules_service_failed) to (it.message ?: it.javaClass.simpleName)
                                        }
                                        runningModuleId = null
                                        reload(refreshHealth = true)
                                    }
                                },
                                onDelete = { deleteTarget = it },
                                onTrustChange = { module, trusted ->
                                    scope.launch {
                                        ModuleSettings.setModuleTrusted(module.id, trusted)
                                        if (trusted && !module.enabled) {
                                            AdbModuleManager.setEnabled(module, true)
                                        }
                                        reload()
                                    }
                                },
                                onOpenWebUi = { module ->
                                    startActivity(
                                        Intent(this@ModulesActivity, ModuleWebViewActivity::class.java)
                                            .putExtra(ModuleWebViewActivity.EXTRA_MODULE_ID, module.id)
                                    )
                                },
                                onOpenCatalog = { showCatalog = true },
                                onLabFeatures = { showLabFeatures = true }
                            )

                            output?.let { (title, text) ->
                                AlertDialog(
                                    onDismissRequest = { output = null },
                                    title = { TvText(title) },
                                    text = { MonospaceLog(text) },
                                    confirmButton = {
                                        TvButton(onClick = { output = null }) {
                                            TvText(stringResource(android.R.string.ok))
                                        }
                                    },
                                    containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                                    shape = TvMaterialTheme.shapes.extraLarge
                                )
                            }

                            deleteTarget?.let { module ->
                                AlertDialog(
                                    onDismissRequest = { deleteTarget = null },
                                    title = { TvText(stringResource(R.string.modules_delete_title)) },
                                    text = { TvText(stringResource(R.string.modules_delete_message, module.name)) },
                                    confirmButton = {
                                        TvButton(
                                            onClick = {
                                                scope.launch {
                                                    AdbModuleManager.delete(module)
                                                    deleteTarget = null
                                                    reload()
                                                }
                                            }
                                        ) {
                                            TvText(stringResource(R.string.modules_delete))
                                        }
                                    },
                                    dismissButton = {
                                        TvOutlinedButton(onClick = { deleteTarget = null }) {
                                            TvText(stringResource(android.R.string.cancel))
                                        }
                                    },
                                    containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                                    shape = TvMaterialTheme.shapes.extraLarge
                                )
                            }
                        }
                    }
                }
            } else {
                var showCatalog by remember { mutableStateOf(false) }
                AnimatedContent(
                    targetState = showCatalog,
                    transitionSpec = {
                        if (targetState) {
                            slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                        } else {
                            slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                        }
                    },
                    label = "catalog-transition"
                ) { catalogOpen ->
                    if (catalogOpen) {
                        moe.shizuku.manager.module.catalog.CatalogScreen(
                            onNavigateUp = { showCatalog = false; reload() }
                        )
                    } else {
                        ShizukuExpressiveTheme {
                            ShizukuLazyScaffold(
                                title = stringResource(R.string.modules_title),
                                onNavigateUp = { finish() },
                                actions = {
                                    Row(
                                        modifier = Modifier
                                            .height(36.dp)
                                            .padding(end = 10.dp)
                                            .clip(MaterialTheme.shapes.small),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        if (modules.isNotEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .height(36.dp)
                                                    .clip(MaterialTheme.shapes.small.copy(topEnd = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)))
                                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                                    .clickable { zipLauncher.launch(MODULE_MIME_TYPES) }
                                                    .padding(horizontal = 14.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    ShizukuIcon(
                                                        R.drawable.ic_outline_arrow_upward_24,
                                                        modifier = Modifier
                                                            .padding(end = 6.dp)
                                                            .size(16.dp)
                                                    )
                                                    Text(
                                                        stringResource(R.string.modules_install_zip),
                                                        style = MaterialTheme.typography.labelLarge
                                                    )
                                                }
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .height(36.dp)
                                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                                    .clickable(enabled = !isCheckingModules && batchProgressText == null) { runManualCheckNow() }
                                                    .padding(horizontal = 12.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                ShizukuIcon(R.drawable.ic_server_restart, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .height(36.dp)
                                                .clip(MaterialTheme.shapes.small.copy(topStart = CornerSize(0.dp), bottomStart = CornerSize(0.dp)))
                                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                                .clickable { showCatalog = true }
                                                .padding(horizontal = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ShizukuIcon(R.drawable.ic_outline_store_24, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            ) {
                                if (modules.isNotEmpty()) {
                                    item {
                                        NightDogModuleCheckBanner(
                                            isChecking = isCheckingModules,
                                            batchProgressText = batchProgressText,
                                            report = checkReport,
                                            allowBackgroundActions = allowBackgroundActions,
                                            onToggleBackgroundActions = { enabled ->
                                                allowBackgroundActions = enabled
                                                ModuleSettings.setAllowBackgroundActions(enabled)
                                            },
                                            onRunCheck = { runManualCheckNow() },
                                            onUpdateAll = { updateAllModulesNow() },
                                            onOpenUpdateSettings = { showUpdateSettings = true }
                                        )
                                    }
                                }
                                item {
                                    AnimatedContent(targetState = modules.isEmpty(), label = "module-empty-state") { empty ->
                                        if (empty) {
                                            EmptyModulesCard(onInstall = { zipLauncher.launch(MODULE_MIME_TYPES) })
                                        }
                                    }
                                }
                                items(modules, key = { it.id }) { module ->
                                    val health = checkReport?.healthByModule?.get(module.id)
                                    val updateRes = checkReport?.updatesByModule?.get(module.id)
                                    val isPinned = remember(module.id, pinRefreshTick) {
                                        ModuleSettings.isModuleUpdateIgnored(module.id)
                                    }
                                    ModuleCard(
                                        module = module,
                                        busy = runningModuleId == module.id,
                                        trusted = ModuleSettings.isModuleTrusted(module.id),
                                        pinned = isPinned,
                                        health = health,
                                        updateResult = updateRes,
                                        modifier = Modifier.animateItem(),
                                        onToggle = {
                                            scope.launch {
                                                AdbModuleManager.setEnabled(module, !module.enabled)
                                                reload()
                                            }
                                        },
                                        onRunAction = {
                                            if (!ModuleSettings.isModuleTrusted(module.id) && ModuleSettings.recommandForAction()) {
                                                pendingCommand = ModuleCommandRequest(
                                                    module = module,
                                                    source = ModuleCommandSource.ACTION,
                                                    command = module.actionCommandPreview()
                                                )
                                            } else {
                                                runModuleAction(module)
                                            }
                                        },
                                        onRunService = {
                                            scope.launch {
                                                runningModuleId = module.id
                                                output = runCatching {
                                                    val result = AdbModuleManager.runService(module)
                                                    context.getString(R.string.modules_service_result, result.exitCode) to result.combinedOutput
                                                }.getOrElse {
                                                    context.getString(R.string.modules_service_failed) to (it.message ?: it.javaClass.simpleName)
                                                }
                                                runningModuleId = null
                                                reload(refreshHealth = true)
                                            }
                                        },
                                        onOpenWebUi = {
                                            startActivity(
                                                Intent(this@ModulesActivity, ModuleWebViewActivity::class.java)
                                                    .putExtra(ModuleWebViewActivity.EXTRA_MODULE_ID, module.id)
                                            )
                                        },
                                        onUpdateClick = {
                                            if (updateRes != null && updateRes.hasUpdate) {
                                                updateDialogTarget = module to updateRes
                                            } else {
                                                updateModuleNow(module, updateRes)
                                            }
                                        },
                                        onPinChange = { pinned ->
                                            ModuleSettings.setModuleUpdateIgnored(module.id, pinned)
                                            pinRefreshTick++
                                        },
                                        onDelete = { deleteTarget = module },
                                        onTrustChange = { trusted ->
                                            scope.launch {
                                                ModuleSettings.setModuleTrusted(module.id, trusted)
                                                if (trusted && !module.enabled) {
                                                    AdbModuleManager.setEnabled(module, true)
                                                }
                                                reload()
                                            }
                                        }
                                    )
                                }
                            }

                            updateDialogTarget?.let { (module, updateRes) ->
                                ModuleUpdateDialog(
                                    module = module,
                                    updateResult = updateRes,
                                    onDismiss = { updateDialogTarget = null },
                                    onConfirmUpdate = { preferredMode ->
                                        updateDialogTarget = null
                                        updateModuleNow(module, updateRes, preferredMode)
                                    },
                                    onPinVersion = {
                                        updateDialogTarget = null
                                        ModuleSettings.setModuleUpdateIgnored(module.id, true)
                                        pinRefreshTick++
                                    }
                                )
                            }

                            output?.let { (title, text) ->
                                AlertDialog(
                                    onDismissRequest = { output = null },
                                    title = { Text(title) },
                                    text = { MonospaceLog(text) },
                                    confirmButton = {
                                        TextButton(onClick = { output = null }) {
                                            Text(stringResource(android.R.string.ok))
                                        }
                                    }
                                )
                            }

                            deleteTarget?.let { module ->
                                AlertDialog(
                                    onDismissRequest = { deleteTarget = null },
                                    title = { Text(stringResource(R.string.modules_delete_title)) },
                                    text = { Text(stringResource(R.string.modules_delete_message, module.name)) },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    AdbModuleManager.delete(module)
                                                    deleteTarget = null
                                                    reload()
                                                }
                                            }
                                        ) {
                                            Text(stringResource(R.string.modules_delete))
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { deleteTarget = null }) {
                                            Text(stringResource(android.R.string.cancel))
                                        }
                                    }
                                )
                            }

                            pendingCommand?.let { request ->
                                ReCommandDialog(
                                    request = request,
                                    busy = runningModuleId == request.module.id,
                                    onDismiss = { pendingCommand = null },
                                    onReject = { pendingCommand = null },
                                    onApprove = {
                                        pendingCommand = null
                                        runModuleAction(request.module)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val MODULE_MIME_TYPES = arrayOf(
            "application/zip",
            "application/octet-stream",
            "application/x-zip-compressed"
        )
    }
}

private fun AdbModule.actionCommandPreview(): String {
    val script = actionScript ?: return "sh action.sh"
    val content = runCatching { script.readText().trim() }.getOrDefault("")
    return buildString {
        append("sh ")
        append(script.absolutePath)
        if (content.isNotBlank()) {
            appendLine()
            appendLine()
            append(content)
        }
    }.take(MAX_RECOMMAND_COMMAND_CHARS)
}

private const val MAX_RECOMMAND_COMMAND_CHARS = 64 * 1024

@Composable
private fun NightDogModuleCheckBanner(
    isChecking: Boolean,
    batchProgressText: String?,
    report: NightDogClient.ModuleCheckReport?,
    allowBackgroundActions: Boolean,
    onToggleBackgroundActions: (Boolean) -> Unit,
    onRunCheck: () -> Unit,
    onUpdateAll: () -> Unit,
    onOpenUpdateSettings: () -> Unit
) {
    val updatesCount = report?.updatesAvailableCount ?: 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShizukuIcon(
                    R.drawable.ic_shield_24,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.modules_background_check_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            batchProgressText != null -> batchProgressText
                            isChecking -> stringResource(R.string.modules_background_check_running)
                            report != null -> stringResource(
                                R.string.modules_background_check_report_summary,
                                report.healthyModules,
                                report.totalModules,
                                report.updatesAvailableCount,
                                report.issuesCount
                            )
                            else -> stringResource(R.string.modules_background_check_never)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenUpdateSettings) {
                    ShizukuIcon(R.drawable.ic_settings_outline_24dp, modifier = Modifier.size(20.dp))
                }
            }

            if (batchProgressText != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onRunCheck,
                    enabled = !isChecking && batchProgressText == null
                ) {
                    if (isChecking) {
                        LoadingIndicator(Modifier.size(16.dp))
                    } else {
                        ShizukuIcon(
                            R.drawable.ic_server_restart,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(16.dp)
                        )
                        Text(stringResource(R.string.modules_background_check_short))
                    }
                }

                if (updatesCount > 0) {
                    Button(
                        onClick = onUpdateAll,
                        enabled = !isChecking && batchProgressText == null
                    ) {
                        ShizukuIcon(
                            R.drawable.ic_outline_arrow_upward_24,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(16.dp)
                        )
                        Text(stringResource(R.string.modules_update_all_button, updatesCount))
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.modules_background_actions),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.modules_background_actions_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ExpressiveSwitch(
                    checked = allowBackgroundActions,
                    onCheckedChange = onToggleBackgroundActions
                )
            }
        }
    }
}

@Composable
private fun ModuleUpdateDialog(
    module: AdbModule,
    updateResult: UpdateResult,
    onDismiss: () -> Unit,
    onConfirmUpdate: (ModuleSettings.InstallMode?) -> Unit,
    onPinVersion: () -> Unit
) {
    var selectedMode by remember { mutableStateOf<ModuleSettings.InstallMode?>(null) }
    val changelogText = updateResult.changelog?.trim()?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.modules_update_changelog_empty)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(module.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(
                        R.string.modules_update_version_transition,
                        module.version ?: "-",
                        updateResult.latestVersion ?: "new"
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.modules_update_changelog_title),
                    style = MaterialTheme.typography.labelLarge
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 200.dp)
                ) {
                    Text(
                        text = changelogText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.update_settings_install_mode_label),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedMode == null,
                        onClick = { selectedMode = null },
                        label = { Text(stringResource(R.string.modules_update_mode_default)) }
                    )
                    FilterChip(
                        selected = selectedMode == ModuleSettings.InstallMode.RELEASE,
                        onClick = { selectedMode = ModuleSettings.InstallMode.RELEASE },
                        label = { Text(stringResource(R.string.update_settings_install_mode_release)) }
                    )
                    FilterChip(
                        selected = selectedMode == ModuleSettings.InstallMode.SOURCES,
                        onClick = { selectedMode = ModuleSettings.InstallMode.SOURCES },
                        label = { Text(stringResource(R.string.update_settings_install_mode_sources)) }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirmUpdate(selectedMode) }) {
                Text(stringResource(R.string.modules_update_now))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPinVersion) {
                    Text(stringResource(R.string.modules_update_pin))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun EmptyModulesCard(onInstall: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.modules_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.modules_empty_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onInstall) {
                ShizukuIcon(
                    R.drawable.ic_outline_arrow_upward_24,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp)
                )
                Text(stringResource(R.string.modules_install_zip))
            }
        }
    }
}

@Composable
private fun ModuleCard(
    module: AdbModule,
    busy: Boolean,
    trusted: Boolean,
    pinned: Boolean,
    health: ModuleHealthStatus?,
    updateResult: UpdateResult?,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onRunAction: () -> Unit,
    onRunService: () -> Unit,
    onOpenWebUi: () -> Unit,
    onUpdateClick: () -> Unit,
    onPinChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTrustChange: (Boolean) -> Unit
) {
    var expanded by remember(module.id) { mutableStateOf(true) }
    var showExtraActions by remember(module.id, trusted, pinned) { mutableStateOf(false) }
    val containerColor by animateColorAsState(
        targetValue = if (trusted) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else if (module.enabled) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        animationSpec = tween(260),
        label = "module-container"
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    expanded = true
                    showExtraActions = !showExtraActions
                }
            )
            .animateContentSize(animationSpec = tween(280)),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Column {
            ModuleBanner(module)
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = module.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ModuleInfoLine(label = R.string.modules_version_label, value = module.version ?: "-")
                        ModuleInfoLine(label = R.string.modules_author_label, value = module.author ?: "-")
                    }
                    ExpressiveSwitch(
                        checked = module.enabled,
                        onCheckedChange = { onToggle() }
                    )
                }
                module.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ModuleChips(
                    module = module,
                    trusted = trusted,
                    pinned = pinned,
                    health = health,
                    updateResult = updateResult,
                    onUpdateChipClick = onUpdateClick,
                    onUnpinClick = { onPinChange(false) }
                )
                AnimatedVisibility(
                    visible = busy,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
                ) {
                    RunningRow()
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
                ) {
                    ModuleActions(
                        module = module,
                        busy = busy,
                        hasUpdate = updateResult?.hasUpdate == true && !pinned,
                        onRunAction = onRunAction,
                        onRunService = onRunService,
                        onOpenWebUi = onOpenWebUi,
                        onUpdate = onUpdateClick,
                        onDelete = onDelete,
                        trusted = trusted,
                        pinned = pinned,
                        showExtraActions = showExtraActions,
                        onTrustChange = {
                            showExtraActions = false
                            onTrustChange(it)
                        },
                        onPinChange = {
                            showExtraActions = false
                            onPinChange(it)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleChips(
    module: AdbModule,
    trusted: Boolean,
    pinned: Boolean,
    health: ModuleHealthStatus?,
    updateResult: UpdateResult?,
    onUpdateChipClick: () -> Unit,
    onUnpinClick: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(
            onClick = {},
            label = { Text(module.id) }
        )
        AssistChip(onClick = {}, label = { Text(module.formattedSize) })
        if (trusted) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.modules_full_trust)) })
        }
        if (pinned) {
            AssistChip(
                onClick = onUnpinClick,
                label = { Text(stringResource(R.string.modules_update_pinned_chip)) }
            )
        }
        if (module.hasService) {
            val bgAllowed = ModuleSettings.canRunBackground(module) && ModuleSettings.canRunService(module)
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        stringResource(
                            if (bgAllowed) R.string.modules_background_allowed
                            else R.string.modules_background_blocked
                        )
                    )
                }
            )
        }
        if (health != null) {
            if (health.healthy) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.modules_health_ok)) }
                )
            } else {
                AssistChip(
                    onClick = {},
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    label = {
                        Text(health.issues.firstOrNull() ?: stringResource(R.string.modules_health_issue))
                    }
                )
            }
        }
        if (updateResult?.hasUpdate == true && !pinned) {
            AssistChip(
                onClick = onUpdateChipClick,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                label = {
                    Text(
                        stringResource(
                            R.string.modules_update_available_chip,
                            updateResult.latestVersion ?: "new"
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun ModuleInfoLine(@StringRes label: Int, value: String) {
    Text(
        text = stringResource(label, value),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun RunningRow() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingIndicator(Modifier.size(28.dp))
        Text(
            text = stringResource(R.string.modules_running),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModuleActions(
    module: AdbModule,
    busy: Boolean,
    hasUpdate: Boolean,
    trusted: Boolean,
    pinned: Boolean,
    showExtraActions: Boolean,
    onRunAction: () -> Unit,
    onRunService: () -> Unit,
    onOpenWebUi: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    onTrustChange: (Boolean) -> Unit,
    onPinChange: (Boolean) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModuleButton(
            label = R.string.modules_run_action,
            icon = R.drawable.ic_outline_play_arrow_24,
            primary = true,
            enabled = !busy && module.enabled && module.hasAction && ModuleSettings.canRunAction(module),
            onClick = onRunAction
        )
        ModuleButton(
            label = R.string.modules_open_webui,
            icon = R.drawable.ic_outline_open_in_new_24,
            enabled = !busy && module.hasWebUi,
            onClick = onOpenWebUi
        )
        ModuleButton(
            label = R.string.modules_run_service,
            icon = R.drawable.ic_terminal_24,
            enabled = !busy &&
                module.enabled &&
                module.hasService &&
                ModuleSettings.canRunService(module) &&
                ModuleSettings.canRunBackground(module),
            onClick = onRunService
        )
        if (hasUpdate) {
            ModuleButton(
                label = R.string.modules_update_action,
                icon = R.drawable.ic_outline_arrow_upward_24,
                primary = true,
                enabled = !busy,
                onClick = onUpdate
            )
        }
        if (showExtraActions) {
            ModuleButton(
                label = if (trusted) R.string.modules_untrust else R.string.modules_trust,
                icon = R.drawable.ic_warning_24,
                enabled = !busy,
                onClick = { onTrustChange(!trusted) }
            )
            ModuleButton(
                label = if (pinned) R.string.modules_update_unpin else R.string.modules_update_pin,
                icon = R.drawable.ic_settings_outline_24dp,
                enabled = !busy,
                onClick = { onPinChange(!pinned) }
            )
        }
        ModuleButton(
            label = R.string.modules_delete,
            icon = R.drawable.ic_close_24,
            enabled = !busy,
            danger = true,
            onClick = onDelete
        )
    }
}

@Composable
private fun ModuleButton(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false
) {
    val modifier = Modifier.widthIn(min = 116.dp)
    val content: @Composable RowScope.() -> Unit = {
        ShizukuIcon(
            icon,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(18.dp)
        )
        Text(stringResource(label))
    }
    when {
        primary -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            content = content
        )
        danger -> OutlinedButton(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            content = content
        )
        else -> FilledTonalButton(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            content = content
        )
    }
}

@Composable
private fun ModuleBanner(module: AdbModule) {
    val banner = module.banner ?: return
    val bitmap = remember(banner.absolutePath, banner.lastModified()) {
        runCatching { BitmapFactory.decodeFile(banner.absolutePath)?.asImageBitmap() }.getOrNull()
    } ?: return

    val overlayAlpha by animateColorAsState(
        targetValue = if (module.enabled) {
            androidx.compose.ui.graphics.Color.Transparent
        } else {
            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.48f)
        },
        animationSpec = tween(260),
        label = "module-banner-disabled-overlay"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayAlpha)
        )
    }
    Spacer(Modifier.height(2.dp))
}
