@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.home

import android.os.Build
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.lifecycle.Resource
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

internal data class LocalNetworkPermissionState(
    val permission: String?,
    val required: Boolean,
    val granted: Boolean
) {
    val label: String
        get() = permission?.substringAfterLast('.') ?: "none"
}

internal data class HomeButtonSpec(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
internal fun HomeScreen(
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

@Composable internal fun PhoneHomeScreen(
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
    var moreOpen by androidx.compose.runtime.mutableStateOf(false)
    val diagnostics = remember(status, grantedCount, localNetworkPermissionState) {
        buildDiagnostics(context, status, grantedCount, localNetworkPermissionState)
    }

    androidx.compose.material3.Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                actions = {
                    androidx.compose.material3.IconButton(onClick = onSettings) {
                        ShizukuIcon(
                            icon = R.drawable.ic_action_settings_24dp,
                            contentDescription = stringResource(R.string.settings_title)
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = onRefresh) {
                        ShizukuIcon(
                            icon = R.drawable.ic_server_restart,
                            contentDescription = stringResource(R.string.home_refresh)
                        )
                    }
                    Box {
                        androidx.compose.material3.IconButton(onClick = { moreOpen = true }) {
                            ShizukuIcon(
                                icon = R.drawable.ic_more_vert_24,
                                contentDescription = null
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = moreOpen,
                            onDismissRequest = { moreOpen = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_stop)) },
                                leadingIcon = {
                                    ShizukuIcon(R.drawable.ic_close_24, contentDescription = null)
                                },
                                onClick = {
                                    moreOpen = false
                                    onStop()
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
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
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
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
internal fun StatusCard(
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
            androidx.compose.material3.LoadingIndicator(Modifier.size(32.dp))
        }
    }
}

@Composable
internal fun ManageAppsCard(
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
internal fun RootCard(
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
internal fun WirelessAdbCard(
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
internal fun AdbCommandCard(
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
internal fun LocalNetworkPermissionCard(
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
internal fun DiagnosticsCard(
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
internal fun SimpleActionCard(
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
internal fun HomeCard(
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
internal fun HomeButtons(buttons: List<HomeButtonSpec>) {
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
internal fun ButtonIcon(@DrawableRes icon: Int) {
    ShizukuIcon(
        icon = icon,
        contentDescription = null,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(18.dp)
    )
}

@Composable
internal fun htmlStringResource(@StringRes id: Int, vararg formatArgs: Any): String {
    val raw = stringResource(id, *formatArgs)
    return remember(raw) { htmlToPlainText(raw) }
}

internal fun htmlToPlainText(value: String): String {
    return HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
}

internal fun buildServiceSummary(context: android.content.Context, status: ServiceStatus): String {
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

internal fun buildDiagnostics(
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
