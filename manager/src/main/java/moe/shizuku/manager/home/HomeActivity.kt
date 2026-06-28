package moe.shizuku.manager.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModulesActivity
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.shell.ShellTutorialActivity
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.core.util.ClipboardUtils
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku

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
