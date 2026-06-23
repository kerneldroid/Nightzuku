@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModulesActivity
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.shell.ShellTutorialActivity
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.core.util.ClipboardUtils
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.html.text.HtmlCompat as RikkaHtmlCompat

abstract class HomeActivity : AppActivity() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkServerStatus()
        appsModel.load()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AdbModuleManager.resetServiceRunGuard()
        checkServerStatus()
    }

    private val homeModel by viewModels { HomeViewModel() }
    private val appsModel by appsViewModel()
    private val permissionRefreshTick = mutableIntStateOf(0)

    private var pendingLocalNetworkAction: (() -> Unit)? = null

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefreshTick.intValue++
        val action = pendingLocalNetworkAction
        pendingLocalNetworkAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(this, R.string.home_local_network_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val serviceResource by homeModel.serviceStatus.observeAsState()
            val grantedResource by appsModel.grantedCount.observeAsState()
            val localNetworkPermissionState = remember(permissionRefreshTick.intValue) {
                buildLocalNetworkPermissionState()
            }

            LaunchedEffect(serviceResource?.status, serviceResource?.data?.uid) {
                val status = serviceResource?.data ?: return@LaunchedEffect
                if (serviceResource?.status == Status.SUCCESS && status.isRunning) {
                    ShizukuSettings.setLastLaunchMode(
                        if (status.uid == 0) {
                            ShizukuSettings.LaunchMethod.ROOT
                        } else {
                            ShizukuSettings.LaunchMethod.ADB
                        }
                    )
                    try {
                        AdbModuleManager.runEnabledServicesIfAllowed(applicationContext)
                    } catch (_: Throwable) {
                    }
                }
            }

            var showAboutDialog by remember { mutableStateOf(false) }
            var showStopDialog by remember { mutableStateOf(false) }
            var showAdbCommandDialog by remember { mutableStateOf(false) }
            var showAdbDiscoveryDialog by remember { mutableStateOf(false) }
            var showWadbNotEnabledDialog by remember { mutableStateOf(false) }
            var showAdbPairDialog by remember { mutableStateOf(false) }

            ShizukuExpressiveTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        serviceResource = serviceResource,
                        grantedResource = grantedResource,
                        localNetworkPermissionState = localNetworkPermissionState,
                        isPrimaryUser = UserHandleCompat.myUserId() == 0,
                        isRooted = EnvironmentUtils.isRooted(),
                        onRefresh = {
                            checkServerStatus()
                            appsModel.load()
                        },
                        onSettings = { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) },
                        onAbout = { showAboutDialog = true },
                        onStop = { showStopDialog = true },
                        onModules = { startActivity(Intent(this@HomeActivity, ModulesActivity::class.java)) },
                        onManageApps = { startActivity(Intent(this@HomeActivity, ApplicationManagementActivity::class.java)) },
                        onTerminal = { startActivity(Intent(this@HomeActivity, ShellTutorialActivity::class.java)) },
                        onStartRoot = ::startRoot,
                        onStartWirelessAdb = { 
                            runWithLocalNetworkAccess { 
                                startWirelessAdb(
                                    onShowDiscovery = { showAdbDiscoveryDialog = true },
                                    onShowNotEnabled = { showWadbNotEnabledDialog = true }
                                ) 
                            } 
                        },
                        onPairWirelessAdb = { 
                            runWithLocalNetworkAccess { 
                                pairWirelessAdb(onShowPair = { showAdbPairDialog = true }) 
                            } 
                        },
                        onOpenWirelessGuide = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_ANDROID11.get()) },
                        onShowAdbCommand = { showAdbCommandDialog = true },
                        onOpenAdbHelp = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB.get()) },
                        onOpenAdbPermissionHelp = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_PERMISSION.get()) },
                        onLearnMore = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.HOME.get()) },
                        onCopyDiagnostics = { copyDiagnostics(it) },
                        onRequestLocalNetworkPermission = {
                            requestLocalNetworkPermission { permissionRefreshTick.intValue++ }
                        }
                    )

                    if (showAboutDialog) {
                        HomeAboutDialog(
                            onDismiss = { showAboutDialog = false },
                            onSourceCode = {
                                CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, "https://github.com/RikkaApps/Shizuku")
                            }
                        )
                    }

                    if (showStopDialog) {
                        HomeStopDialog(
                            onDismiss = { showStopDialog = false },
                            onConfirm = {
                                try {
                                    Shizuku.exit()
                                } catch (_: Throwable) {
                                }
                            }
                        )
                    }

                    if (showAdbCommandDialog) {
                        HomeAdbCommandDialog(
                            command = Starter.adbCommand,
                            onDismiss = { showAdbCommandDialog = false },
                            onCopy = {
                                if (ClipboardUtils.put(this@HomeActivity, Starter.adbCommand)) {
                                    Toast.makeText(
                                        this@HomeActivity,
                                        getString(R.string.toast_copied_to_clipboard, Starter.adbCommand),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onSend = {
                                var intent = Intent(Intent.ACTION_SEND)
                                intent.type = "text/plain"
                                intent.putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                                intent = Intent.createChooser(
                                    intent,
                                    getString(R.string.home_adb_dialog_view_command_button_send)
                                )
                                startActivity(intent)
                            }
                        )
                    }

                    if (showAdbDiscoveryDialog) {
                        HomeAdbDiscoveryDialog(
                            onDismiss = { showAdbDiscoveryDialog = false },
                            onStart = { host, port ->
                                startAndDismiss(host, port)
                                showAdbDiscoveryDialog = false
                            }
                        )
                    }

                    if (showWadbNotEnabledDialog) {
                        HomeWadbNotEnabledDialog(
                            onDismiss = { showWadbNotEnabledDialog = false }
                        )
                    }

                    if (showAdbPairDialog) {
                        HomeAdbPairDialog(
                            onDismiss = { showAdbPairDialog = false }
                        )
                    }
                }
            }
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    private fun startAndDismiss(host: String, port: Int) {
        val intent = Intent(this, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
            putExtra(StarterActivity.EXTRA_HOST, host)
            putExtra(StarterActivity.EXTRA_PORT, port)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        checkServerStatus()
        permissionRefreshTick.intValue++
    }

    private fun checkServerStatus() {
        homeModel.reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        return false
    }

    private fun showAboutDialog() {
    }

    private fun showStopDialog() {
    }

    private fun startRoot() {
        startActivity(
            Intent(this, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, true)
            }
        )
    }

    private fun startWirelessAdb(onShowDiscovery: () -> Unit, onShowNotEnabled: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            onShowDiscovery()
            return
        }

        val port = EnvironmentUtils.getAdbTcpPort()
        if (port > 0) {
            startActivity(
                Intent(this, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                    putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                    putExtra(StarterActivity.EXTRA_PORT, port)
                }
            )
        } else {
            onShowNotEnabled()
        }
    }

    private fun pairWirelessAdb(onShowPair: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val isWatch = EnvironmentUtils.isWatch(this)
        if (isWatch || (display?.displayId ?: -1) > 0 || isInMultiWindowMode) {
            onShowPair()
        } else {
            startActivity(Intent(this, moe.shizuku.manager.adb.AdbPairingTutorialActivity::class.java))
        }
    }

    private fun showAdbCommandDialog() {
    }

    private fun runWithLocalNetworkAccess(action: () -> Unit) {
        val state = buildLocalNetworkPermissionState()
        if (!state.required || state.granted) {
            action()
            return
        }

        pendingLocalNetworkAction = action
        localNetworkPermissionLauncher.launch(state.permission!!)
    }

    private fun requestLocalNetworkPermission(onGranted: () -> Unit) {
        val state = buildLocalNetworkPermissionState()
        if (!state.required || state.granted) {
            onGranted()
            return
        }

        pendingLocalNetworkAction = onGranted
        localNetworkPermissionLauncher.launch(state.permission!!)
    }

    private fun buildLocalNetworkPermissionState(): LocalNetworkPermissionState {
        val permission = when {
            Build.VERSION.SDK_INT >= SDK_ANDROID_17 -> PERMISSION_ACCESS_LOCAL_NETWORK
            Build.VERSION.SDK_INT >= SDK_ANDROID_16 -> Manifest.permission.NEARBY_WIFI_DEVICES
            else -> null
        }

        return LocalNetworkPermissionState(
            permission = permission,
            required = permission != null,
            granted = permission == null ||
                    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    private fun copyDiagnostics(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.home_diagnostics_title), text))
        Toast.makeText(this, R.string.home_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val SDK_ANDROID_16 = 36
        private const val SDK_ANDROID_17 = 37
        private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

internal data class LocalNetworkPermissionState(
    val permission: String?,
    val required: Boolean,
    val granted: Boolean
) {
    val label: String
        get() = permission?.substringAfterLast('.') ?: "none"
}

private data class HomeButtonSpec(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable

private fun HomeScreen(
    serviceResource: Resource<ServiceStatus>?,
    grantedResource: Resource<Int>?,
    localNetworkPermissionState: LocalNetworkPermissionState,
    isPrimaryUser: Boolean,
    isRooted: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onStop: () -> Unit,
    onModules: () -> Unit,
    onManageApps: () -> Unit,
    onTerminal: () -> Unit,
    onStartRoot: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onOpenWirelessGuide: () -> Unit,
    onShowAdbCommand: () -> Unit,
    onOpenAdbHelp: () -> Unit,
    onOpenAdbPermissionHelp: () -> Unit,
    onLearnMore: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onRequestLocalNetworkPermission: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isWatch = androidx.compose.runtime.remember(context) { moe.shizuku.manager.utils.EnvironmentUtils.isWatch(context) }
    val isTv = androidx.compose.runtime.remember(context) { moe.shizuku.manager.utils.EnvironmentUtils.isTV(context) }
    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearHomeScreen(
                serviceResource = serviceResource,
                grantedResource = grantedResource,
                localNetworkPermissionState = localNetworkPermissionState,
                isPrimaryUser = isPrimaryUser,
                isRooted = isRooted,
                onRefresh = onRefresh,
                onSettings = onSettings,
                onAbout = onAbout,
                onStop = onStop,
                onModules = onModules,
                onManageApps = onManageApps,
                onTerminal = onTerminal,
                onStartRoot = onStartRoot,
                onStartWirelessAdb = onStartWirelessAdb,
                onPairWirelessAdb = onPairWirelessAdb,
                onOpenWirelessGuide = onOpenWirelessGuide,
                onShowAdbCommand = onShowAdbCommand,
                onOpenAdbHelp = onOpenAdbHelp,
                onOpenAdbPermissionHelp = onOpenAdbPermissionHelp,
                onLearnMore = onLearnMore,
                onCopyDiagnostics = onCopyDiagnostics,
                onRequestLocalNetworkPermission = onRequestLocalNetworkPermission
            )
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            TVHomeScreen(
                serviceResource = serviceResource,
                grantedResource = grantedResource,
                localNetworkPermissionState = localNetworkPermissionState,
                isPrimaryUser = isPrimaryUser,
                isRooted = isRooted,
                onRefresh = onRefresh,
                onSettings = onSettings,
                onAbout = onAbout,
                onStop = onStop,
                onModules = onModules,
                onManageApps = onManageApps,
                onTerminal = onTerminal,
                onStartRoot = onStartRoot,
                onStartWirelessAdb = onStartWirelessAdb,
                onPairWirelessAdb = onPairWirelessAdb,
                onOpenWirelessGuide = onOpenWirelessGuide,
                onShowAdbCommand = onShowAdbCommand,
                onOpenAdbHelp = onOpenAdbHelp,
                onOpenAdbPermissionHelp = onOpenAdbPermissionHelp,
                onLearnMore = onLearnMore,
                onCopyDiagnostics = onCopyDiagnostics,
                onRequestLocalNetworkPermission = onRequestLocalNetworkPermission
            )
        }
    } else {
        PhoneHomeScreen(
            serviceResource = serviceResource,
            grantedResource = grantedResource,
            localNetworkPermissionState = localNetworkPermissionState,
            isPrimaryUser = isPrimaryUser,
            isRooted = isRooted,
            onRefresh = onRefresh,
            onSettings = onSettings,
            onAbout = onAbout,
            onStop = onStop,
            onModules = onModules,
            onManageApps = onManageApps,
            onTerminal = onTerminal,
            onStartRoot = onStartRoot,
            onStartWirelessAdb = onStartWirelessAdb,
            onPairWirelessAdb = onPairWirelessAdb,
            onOpenWirelessGuide = onOpenWirelessGuide,
            onShowAdbCommand = onShowAdbCommand,
            onOpenAdbHelp = onOpenAdbHelp,
            onOpenAdbPermissionHelp = onOpenAdbPermissionHelp,
            onLearnMore = onLearnMore,
            onCopyDiagnostics = onCopyDiagnostics,
            onRequestLocalNetworkPermission = onRequestLocalNetworkPermission
        )
    }
}

@Composable private fun PhoneHomeScreen(
    serviceResource: Resource<ServiceStatus>?,
    grantedResource: Resource<Int>?,
    localNetworkPermissionState: LocalNetworkPermissionState,
    isPrimaryUser: Boolean,
    isRooted: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onStop: () -> Unit,
    onModules: () -> Unit,
    onManageApps: () -> Unit,
    onTerminal: () -> Unit,
    onStartRoot: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onOpenWirelessGuide: () -> Unit,
    onShowAdbCommand: () -> Unit,
    onOpenAdbHelp: () -> Unit,
    onOpenAdbPermissionHelp: () -> Unit,
    onLearnMore: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onRequestLocalNetworkPermission: () -> Unit
) {
    val context = LocalContext.current
    val status = serviceResource?.data ?: ServiceStatus()
    val grantedCount = grantedResource?.data ?: 0
    val running = status.isRunning
    val adbPermission = status.permission
    val canUseWirelessAdb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0
    var moreOpen by remember { mutableStateOf(false) }
    val diagnostics = remember(status, grantedCount, localNetworkPermissionState) {
        buildDiagnostics(context, status, grantedCount, localNetworkPermissionState)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        ShizukuIcon(
                            icon = R.drawable.ic_action_settings_24dp,
                            contentDescription = stringResource(R.string.settings_title)
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        ShizukuIcon(
                            icon = R.drawable.ic_server_restart,
                            contentDescription = stringResource(R.string.home_refresh)
                        )
                    }
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            ShizukuIcon(
                                icon = R.drawable.ic_more_vert_24,
                                contentDescription = null
                            )
                        }
                        DropdownMenu(
                            expanded = moreOpen,
                            onDismissRequest = { moreOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_stop)) },
                                leadingIcon = {
                                    ShizukuIcon(R.drawable.ic_close_24, contentDescription = null)
                                },
                                onClick = {
                                    moreOpen = false
                                    onStop()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_about)) },
                                leadingIcon = {
                                    ShizukuIcon(R.drawable.ic_outline_info_24, contentDescription = null)
                                },
                                onClick = {
                                    moreOpen = false
                                    onAbout()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                StatusCard(
                    serviceResource = serviceResource,
                    status = status
                )
            }

            if (adbPermission) {
                item {
                    ManageAppsCard(
                        status = status,
                        grantedCount = grantedCount,
                        onClick = onManageApps
                    )
                }
                item {
                    SimpleActionCard(
                        icon = R.drawable.ic_adb_24dp,
                        title = stringResource(R.string.modules_title),
                        body = if (running) {
                            stringResource(R.string.home_modules_description)
                        } else {
                            stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
                        },
                        enabled = running,
                        onClick = onModules
                    )
                }
                item {
                    SimpleActionCard(
                        icon = R.drawable.ic_terminal_24,
                        title = stringResource(R.string.home_terminal_title),
                        body = if (running) {
                            stringResource(R.string.home_terminal_description)
                        } else {
                            stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
                        },
                        enabled = running,
                        onClick = onTerminal
                    )
                }
            }

            if (running && !adbPermission) {
                item {
                    HomeCard(
                        icon = R.drawable.ic_warning_24,
                        title = stringResource(R.string.home_adb_is_limited_title),
                        body = stringResource(R.string.home_adb_is_limited_description)
                    ) {
                        HomeButtons(
                            listOf(
                                HomeButtonSpec(
                                    label = R.string.home_adb_button_view_help,
                                    icon = R.drawable.ic_help_outline_24dp,
                                    primary = true,
                                    onClick = onOpenAdbPermissionHelp
                                )
                            )
                        )
                    }
                }
            }

            if (isPrimaryUser) {
                val rootRestart = running && status.uid == 0
                if (isRooted) {
                    item {
                        RootCard(rootRestart, onStartRoot)
                    }
                }
                if (canUseWirelessAdb) {
                    item {
                        WirelessAdbCard(
                            localNetworkPermissionState = localNetworkPermissionState,
                            onStartWirelessAdb = onStartWirelessAdb,
                            onPairWirelessAdb = onPairWirelessAdb,
                            onOpenWirelessGuide = onOpenWirelessGuide
                        )
                    }
                }
                item {
                    AdbCommandCard(
                        onShowAdbCommand = onShowAdbCommand,
                        onOpenAdbHelp = onOpenAdbHelp
                    )
                }
                if (!isRooted) {
                    item {
                        RootCard(rootRestart, onStartRoot)
                    }
                }
            }

            if (localNetworkPermissionState.required && !localNetworkPermissionState.granted) {
                item {
                    LocalNetworkPermissionCard(
                        localNetworkPermissionState = localNetworkPermissionState,
                        onRequestLocalNetworkPermission = onRequestLocalNetworkPermission
                    )
                }
            }

            item {
                DiagnosticsCard(
                    diagnostics = diagnostics,
                    onCopyDiagnostics = onCopyDiagnostics
                )
            }

            item {
                SimpleActionCard(
                    icon = R.drawable.ic_learn_more_24dp,
                    title = stringResource(R.string.home_learn_more_title),
                    body = stringResource(R.string.home_learn_more_description),
                    onClick = onLearnMore
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    serviceResource: Resource<ServiceStatus>?,
    status: ServiceStatus
) {
    val context = LocalContext.current
    val running = status.isRunning
    val title = if (running) {
        stringResource(R.string.home_status_service_is_running, stringResource(R.string.app_name))
    } else {
        stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
    }
    val summary = remember(status, running) {
        buildServiceSummary(context, status)
    }

    HomeCard(
        icon = if (running) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp,
        title = title,
        body = summary
    ) {
        if (serviceResource == null) {
            Spacer(Modifier.height(12.dp))
            LoadingIndicator(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun ManageAppsCard(
    status: ServiceStatus,
    grantedCount: Int,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val running = status.isRunning
    val title = if (running) {
        context.resources.getQuantityString(
            R.plurals.home_app_management_authorized_apps_count,
            grantedCount,
            grantedCount
        )
    } else {
        stringResource(R.string.home_app_management_title)
    }
    val body = if (running) {
        stringResource(R.string.home_app_management_view_authorized_apps)
    } else {
        stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
    }

    SimpleActionCard(
        icon = R.drawable.ic_system_icon,
        title = title,
        body = body,
        enabled = running,
        onClick = onClick
    )
}

@Composable
private fun RootCard(
    restart: Boolean,
    onStartRoot: () -> Unit
) {
    val buttonLabel = if (restart) R.string.home_root_button_restart else R.string.home_root_button_start
    val buttonIcon = if (restart) R.drawable.ic_server_restart else R.drawable.ic_server_start_24dp

    HomeCard(
        icon = R.drawable.ic_root_24dp,
        title = htmlStringResource(R.string.home_root_title),
        body = htmlStringResource(
            R.string.home_root_description,
            "Don't kill my app!"
        )
    ) {
        HomeButtons(
            listOf(
                HomeButtonSpec(
                    label = buttonLabel,
                    icon = buttonIcon,
                    primary = true,
                    onClick = onStartRoot
                )
            )
        )
    }
}

@Composable
private fun WirelessAdbCard(
    localNetworkPermissionState: LocalNetworkPermissionState,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onOpenWirelessGuide: () -> Unit
) {
    val body = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        htmlStringResource(R.string.home_wireless_adb_description)
    } else {
        htmlStringResource(R.string.home_wireless_adb_description_pre_11)
    }
    val permissionLine = if (localNetworkPermissionState.required) {
        stringResource(
            if (localNetworkPermissionState.granted) {
                R.string.home_local_network_granted
            } else {
                R.string.home_local_network_missing
            },
            localNetworkPermissionState.label
        )
    } else {
        null
    }

    HomeCard(
        icon = R.drawable.ic_wadb_24,
        title = htmlStringResource(R.string.home_wireless_adb_title),
        body = listOfNotNull(body, permissionLine).joinToString("\n\n")
    ) {
        val buttons = mutableListOf(
            HomeButtonSpec(
                label = R.string.home_root_button_start,
                icon = R.drawable.ic_server_start_24dp,
                primary = true,
                onClick = onStartWirelessAdb
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            buttons += HomeButtonSpec(
                label = R.string.adb_pairing,
                icon = R.drawable.ic_numeric_1_circle_outline_24,
                onClick = onPairWirelessAdb
            )
            buttons += HomeButtonSpec(
                label = R.string.home_wireless_adb_view_guide_button,
                icon = R.drawable.ic_help_outline_24dp,
                onClick = onOpenWirelessGuide
            )
        }
        HomeButtons(buttons)
    }
}

@Composable
private fun AdbCommandCard(
    onShowAdbCommand: () -> Unit,
    onOpenAdbHelp: () -> Unit
) {
    HomeCard(
        icon = R.drawable.ic_adb_24dp,
        title = htmlStringResource(R.string.home_adb_title),
        body = htmlStringResource(R.string.home_adb_description, Helps.ADB.get())
    ) {
        HomeButtons(
            listOf(
                HomeButtonSpec(
                    label = R.string.home_adb_button_view_command,
                    icon = R.drawable.ic_code_24dp,
                    primary = true,
                    onClick = onShowAdbCommand
                ),
                HomeButtonSpec(
                    label = R.string.home_adb_button_view_help,
                    icon = R.drawable.ic_help_outline_24dp,
                    onClick = onOpenAdbHelp
                )
            )
        )
    }
}

@Composable
private fun LocalNetworkPermissionCard(
    localNetworkPermissionState: LocalNetworkPermissionState,
    onRequestLocalNetworkPermission: () -> Unit
) {
    HomeCard(
        icon = R.drawable.ic_warning_24,
        title = stringResource(R.string.home_local_network_title),
        body = stringResource(
            R.string.home_local_network_description,
            localNetworkPermissionState.label
        )
    ) {
        HomeButtons(
            listOf(
                HomeButtonSpec(
                    label = R.string.home_local_network_grant,
                    icon = R.drawable.ic_settings_outline_24dp,
                    primary = true,
                    onClick = onRequestLocalNetworkPermission
                )
            )
        )
    }
}

@Composable
private fun DiagnosticsCard(
    diagnostics: String,
    onCopyDiagnostics: (String) -> Unit
) {
    HomeCard(
        icon = R.drawable.ic_outline_info_24,
        title = stringResource(R.string.home_diagnostics_title),
        body = diagnostics
    ) {
        HomeButtons(
            listOf(
                HomeButtonSpec(
                    label = R.string.home_diagnostics_copy,
                    icon = R.drawable.ic_content_copy_24,
                    primary = true,
                    onClick = { onCopyDiagnostics(diagnostics) }
                )
            )
        )
    }
}

@Composable
private fun SimpleActionCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    HomeCard(
        icon = icon,
        title = title,
        body = body,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun HomeCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {}
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .alpha(if (enabled) 1f else 0.56f),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ShizukuIcon(
                        icon = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (body.isNotBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun HomeButtons(buttons: List<HomeButtonSpec>) {
    if (buttons.isEmpty()) return

    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        buttons.forEach { button ->
            if (button.primary) {
                Button(
                    enabled = button.enabled,
                    onClick = button.onClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    ButtonIcon(button.icon)
                    Text(stringResource(button.label))
                }
            } else if (button.enabled) {
                FilledTonalButton(
                    onClick = button.onClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    ButtonIcon(button.icon)
                    Text(stringResource(button.label))
                }
            } else {
                OutlinedButton(
                    enabled = false,
                    onClick = button.onClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    ButtonIcon(button.icon)
                    Text(stringResource(button.label))
                }
            }
        }
    }
}

@Composable
private fun ButtonIcon(@DrawableRes icon: Int) {
    ShizukuIcon(
        icon = icon,
        contentDescription = null,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(18.dp)
    )
}

@Composable
private fun htmlStringResource(@StringRes id: Int, vararg formatArgs: Any): String {
    val raw = stringResource(id, *formatArgs)
    return remember(raw) { htmlToPlainText(raw) }
}

private fun htmlToPlainText(value: String): String {
    return HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
}

private fun buildServiceSummary(context: android.content.Context, status: ServiceStatus): String {
    if (!status.isRunning) return ""

    val user = if (status.uid == 0) "root" else "adb"
    val version = "${status.apiVersion}.${status.patchVersion}"
    val latestVersion = "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
    val raw = if (
        status.apiVersion != Shizuku.getLatestServiceVersion() ||
        status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION
    ) {
        context.getString(R.string.home_status_service_version_update, user, version, latestVersion)
    } else {
        context.getString(R.string.home_status_service_version, user, version)
    }
    return htmlToPlainText(raw)
}

private fun buildDiagnostics(
    context: android.content.Context,
    status: ServiceStatus,
    grantedCount: Int,
    localNetworkPermissionState: LocalNetworkPermissionState
): String {
    val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    val localNetwork = if (localNetworkPermissionState.required) {
        "${localNetworkPermissionState.label}: " +
                if (localNetworkPermissionState.granted) "granted" else "missing"
    } else {
        "not required"
    }

    return buildString {
        appendLine("App: ${context.getString(R.string.app_name)} $versionName (${BuildConfig.VERSION_CODE})")
        appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT} / ${Build.VERSION.CODENAME}")
        appendLine("Service: ${if (status.isRunning) "running" else "stopped"}")
        appendLine("Server uid: ${status.uid}")
        appendLine("Server API: ${status.apiVersion}.${status.patchVersion}")
        appendLine("SELinux: ${status.seContext ?: "unknown"}")
        appendLine("ADB permission: ${if (status.permission) "full" else "limited"}")
        appendLine("Authorized apps: $grantedCount")
        appendLine("Local network: $localNetwork")
    }.trim()
}
