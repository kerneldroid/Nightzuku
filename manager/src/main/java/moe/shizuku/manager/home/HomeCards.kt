@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.home

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ui.compose.SettingsItem
import moe.shizuku.manager.ui.compose.SettingsSectionHeader
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

internal data class HeroAction(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
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
    var moreOpen by remember { mutableStateOf(false) }
    val diagnostics = remember(status, grantedCount, localNetworkPermissionState) {
        buildDiagnostics(context, status, grantedCount, localNetworkPermissionState)
    }
    val localNetworkMissing = localNetworkPermissionState.required && !localNetworkPermissionState.granted

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                StatusHero(
                    serviceResource = serviceResource,
                    status = status,
                    isPrimaryUser = isPrimaryUser,
                    isRooted = isRooted,
                    canUseWirelessAdb = canUseWirelessAdb,
                    onStop = onStop,
                    onStartRoot = onStartRoot,
                    onStartWirelessAdb = onStartWirelessAdb,
                    onShowAdbCommand = onShowAdbCommand
                )
            }

            if (adbPermission) {
                item {
                    val manageTitle = if (running) {
                        context.resources.getQuantityString(
                            R.plurals.home_app_management_authorized_apps_count,
                            grantedCount,
                            grantedCount
                        )
                    } else {
                        stringResource(R.string.home_app_management_title)
                    }
                    val manageBody = if (running) {
                        stringResource(R.string.home_app_management_view_authorized_apps)
                    } else {
                        stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
                    }
                    HomeGroup(title = stringResource(R.string.home_section_manage)) {
                        HomeRow(
                            index = 0,
                            count = 3,
                            icon = R.drawable.ic_system_icon,
                            title = manageTitle,
                            summary = manageBody,
                            enabled = running,
                            onClick = onManageApps
                        )
                        HomeRow(
                            index = 1,
                            count = 3,
                            icon = R.drawable.ic_adb_24dp,
                            title = stringResource(R.string.modules_title),
                            summary = if (running) {
                                stringResource(R.string.home_modules_description)
                            } else {
                                stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
                            },
                            enabled = running,
                            onClick = onModules
                        )
                        HomeRow(
                            index = 2,
                            count = 3,
                            icon = R.drawable.ic_terminal_24,
                            title = stringResource(R.string.home_terminal_title),
                            summary = if (running) {
                                stringResource(R.string.home_terminal_description)
                            } else {
                                stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
                            },
                            enabled = running,
                            onClick = onTerminal
                        )
                    }
                }
            }

            if (running && !adbPermission) {
                item {
                    AdbLimitedCard(onOpenAdbPermissionHelp = onOpenAdbPermissionHelp)
                }
            }

            if (isPrimaryUser) {
                val rootRestart = running && status.uid == 0
                val canPair = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val wirelessRows = if (canUseWirelessAdb) 1 + if (canPair) 2 else 0 else 0
                val count = 2 + wirelessRows
                val wirelessIndex = if (isRooted) 1 else 0
                val commandIndex = wirelessIndex + wirelessRows
                item {
                    HomeGroup(title = stringResource(R.string.home_section_start), compact = true) {
                        if (isRooted) {
                            HomeRootRow(
                                index = 0,
                                count = count,
                                restart = rootRestart,
                                onStartRoot = onStartRoot,
                                compact = true
                            )
                        }
                        if (canUseWirelessAdb) {
                            HomeWirelessRow(
                                index = wirelessIndex,
                                count = count,
                                localNetworkPermissionState = localNetworkPermissionState,
                                onStartWirelessAdb = onStartWirelessAdb,
                                compact = true
                            )
                            if (canPair) {
                                HomePairingRow(
                                    index = wirelessIndex + 1,
                                    count = count,
                                    onPairWirelessAdb = onPairWirelessAdb,
                                    compact = true
                                )
                                HomeGuideRow(
                                    index = wirelessIndex + 2,
                                    count = count,
                                    onOpenWirelessGuide = onOpenWirelessGuide,
                                    compact = true
                                )
                            }
                        }
                        HomeAdbCommandRow(
                            index = commandIndex,
                            count = count,
                            onShowAdbCommand = onShowAdbCommand,
                            onOpenAdbHelp = onOpenAdbHelp,
                            compact = true
                        )
                        if (!isRooted) {
                            HomeRootRow(
                                index = count - 1,
                                count = count,
                                restart = rootRestart,
                                onStartRoot = onStartRoot,
                                compact = true
                            )
                        }
                    }
                }
            }

            val infoCount = if (localNetworkMissing) 3 else 2
            item {
                HomeGroup(title = stringResource(R.string.home_section_info)) {
                    if (localNetworkMissing) {
                        HomeRow(
                            index = 0,
                            count = infoCount,
                            icon = R.drawable.ic_warning_24,
                            title = stringResource(R.string.home_local_network_title),
                            summary = stringResource(
                                R.string.home_local_network_description,
                                localNetworkPermissionState.label
                            ),
                            showChevron = false,
                            onClick = onRequestLocalNetworkPermission,
                            trailing = {
                                HomeRowButton(
                                    label = R.string.home_local_network_grant,
                                    icon = R.drawable.ic_settings_outline_24dp,
                                    onClick = onRequestLocalNetworkPermission
                                )
                            }
                        )
                    }
                    HomeRow(
                        index = if (localNetworkMissing) 1 else 0,
                        count = infoCount,
                        icon = R.drawable.ic_outline_info_24,
                        title = stringResource(R.string.home_diagnostics_title),
                        summary = diagnostics,
                        showChevron = false,
                        onClick = { onCopyDiagnostics(diagnostics) },
                        trailing = {
                            IconButton(onClick = { onCopyDiagnostics(diagnostics) }) {
                                ShizukuIcon(
                                    icon = R.drawable.ic_content_copy_24,
                                    contentDescription = stringResource(R.string.home_diagnostics_copy),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                    HomeRow(
                        index = infoCount - 1,
                        count = infoCount,
                        icon = R.drawable.ic_learn_more_24dp,
                        title = stringResource(R.string.home_learn_more_title),
                        summary = stringResource(R.string.home_learn_more_description),
                        onClick = onLearnMore
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusHero(
    serviceResource: Resource<ServiceStatus>?,
    status: ServiceStatus,
    isPrimaryUser: Boolean,
    isRooted: Boolean,
    canUseWirelessAdb: Boolean,
    onStop: () -> Unit,
    onStartRoot: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onShowAdbCommand: () -> Unit
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
    val container by animateColorAsState(
        targetValue = if (running) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "status_hero_container"
    )
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val cta = when {
        running -> HeroAction(R.string.action_stop, R.drawable.ic_close_24, onStop)
        !isPrimaryUser -> null
        isRooted -> HeroAction(R.string.home_root_button_start, R.drawable.ic_server_start_24dp, onStartRoot)
        canUseWirelessAdb -> HeroAction(R.string.home_root_button_start, R.drawable.ic_server_start_24dp, onStartWirelessAdb)
        else -> HeroAction(R.string.home_adb_button_view_command, R.drawable.ic_code_24dp, onShowAdbCommand)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraExtraLarge,
        color = container,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedContent(
                targetState = running,
                transitionSpec = {
                    (
                        scaleIn(initialScale = 0.9f, animationSpec = spatialSpec) +
                            fadeIn(animationSpec = effectsSpec)
                        ) togetherWith
                        (
                            scaleOut(targetScale = 0.9f, animationSpec = spatialSpec) +
                                fadeOut(animationSpec = effectsSpec)
                            )
                },
                label = "status_hero_header"
            ) { isRunning ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = if (isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            ShizukuIcon(
                                icon = if (isRunning) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp,
                                contentDescription = null,
                                tint = if (isRunning) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isRunning) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (summary.isNotBlank()) {
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isRunning) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            if (running) {
                HeroFactChip(
                    text = stringResource(
                        if (status.permission) R.string.home_hero_adb_full else R.string.home_hero_adb_limited
                    ),
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary
                )
            }

            if (serviceResource == null) {
                LoadingIndicator(Modifier.size(32.dp))
            } else if (cta != null) {
                Button(
                    onClick = cta.onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = MaterialTheme.shapes.extraLargeIncreased
                ) {
                    ShizukuIcon(
                        icon = cta.icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(cta.label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun HeroFactChip(
    text: String,
    container: Color,
    content: Color
) {
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
internal fun AdbLimitedCard(
    onOpenAdbPermissionHelp: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLargeIncreased,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShizukuIcon(
                    icon = R.drawable.ic_warning_24,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(24.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.home_adb_is_limited_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.home_adb_is_limited_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Button(
                onClick = onOpenAdbPermissionHelp,
                shape = MaterialTheme.shapes.largeIncreased,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                ShizukuIcon(
                    icon = R.drawable.ic_help_outline_24dp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_adb_button_view_help))
            }
        }
    }
}

@Composable
internal fun HomeGroup(
    title: String,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        SettingsSectionHeader(title)
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            content = content
        )
    }
}

@Composable
internal fun HomeRow(
    index: Int,
    count: Int,
    @DrawableRes icon: Int,
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    compact: Boolean = false
) {
    SettingsItem(
        index = index,
        count = count,
        title = title,
        modifier = modifier.then(if (enabled) Modifier else Modifier.alpha(0.56f)),
        icon = icon,
        summary = summary,
        showChevron = showChevron,
        onClick = if (enabled) onClick else null,
        trailing = trailing,
        compact = compact
    )
}

@Composable
internal fun HomeRowButton(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        ShizukuIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(18.dp)
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
internal fun HomeRootRow(
    index: Int,
    count: Int,
    restart: Boolean,
    onStartRoot: () -> Unit,
    compact: Boolean = false
) {
    HomeRow(
        index = index,
        count = count,
        compact = compact,
        icon = R.drawable.ic_root_24dp,
        title = htmlStringResource(R.string.home_root_title),
        summary = htmlStringResource(
            R.string.home_root_description,
            "Don't kill my app!"
        ),
        showChevron = false,
        onClick = onStartRoot,
        trailing = {
            HomeRowButton(
                label = if (restart) R.string.home_root_button_restart else R.string.home_root_button_start,
                icon = if (restart) R.drawable.ic_server_restart else R.drawable.ic_server_start_24dp,
                onClick = onStartRoot
            )
        }
    )
}

@Composable
internal fun HomeWirelessRow(
    index: Int,
    count: Int,
    localNetworkPermissionState: LocalNetworkPermissionState,
    onStartWirelessAdb: () -> Unit,
    compact: Boolean = false
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

    HomeRow(
        index = index,
        count = count,
        compact = compact,
        icon = R.drawable.ic_wadb_24,
        title = htmlStringResource(R.string.home_wireless_adb_title),
        summary = listOfNotNull(body, permissionLine).joinToString("\n\n"),
        showChevron = false,
        onClick = onStartWirelessAdb,
        trailing = {
            HomeRowButton(
                label = R.string.home_root_button_start,
                icon = R.drawable.ic_server_start_24dp,
                onClick = onStartWirelessAdb
            )
        }
    )
}

@Composable
internal fun HomePairingRow(
    index: Int,
    count: Int,
    onPairWirelessAdb: () -> Unit,
    compact: Boolean = false
) {
    HomeRow(
        index = index,
        count = count,
        compact = compact,
        icon = R.drawable.ic_numeric_1_circle_outline_24,
        title = stringResource(R.string.adb_pairing),
        summary = null,
        onClick = onPairWirelessAdb
    )
}

@Composable
internal fun HomeGuideRow(
    index: Int,
    count: Int,
    onOpenWirelessGuide: () -> Unit,
    compact: Boolean = false
) {
    HomeRow(
        index = index,
        count = count,
        compact = compact,
        icon = R.drawable.ic_help_outline_24dp,
        title = stringResource(R.string.home_wireless_adb_view_guide_button),
        summary = null,
        onClick = onOpenWirelessGuide
    )
}

@Composable
internal fun HomeAdbCommandRow(
    index: Int,
    count: Int,
    onShowAdbCommand: () -> Unit,
    onOpenAdbHelp: () -> Unit,
    compact: Boolean = false
) {
    HomeRow(
        index = index,
        count = count,
        compact = compact,
        icon = R.drawable.ic_adb_24dp,
        title = htmlStringResource(R.string.home_adb_title),
        summary = htmlStringResource(R.string.home_adb_description, Helps.ADB.get()),
        showChevron = false,
        onClick = onShowAdbCommand,
        trailing = {
            HomeRowButton(
                label = R.string.home_adb_button_view_help,
                icon = R.drawable.ic_help_outline_24dp,
                onClick = onOpenAdbHelp
            )
        }
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
    // Show the real release version of the installed app/server instead of the stale
    // API constants (which reported "13.6" while the app was already "13.7.1").
    val version = BuildConfig.VERSION_NAME.substringBefore(".r")
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
