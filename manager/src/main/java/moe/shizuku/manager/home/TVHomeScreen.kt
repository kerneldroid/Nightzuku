@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.home

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.SurfaceDefaults as TvSurfaceDefaults
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.TvActionButton
import moe.shizuku.manager.ui.compose.TvActionCard
import moe.shizuku.manager.ui.compose.TvMenuButton
import moe.shizuku.manager.ui.compose.TvSecondaryButton
import moe.shizuku.manager.ui.compose.TvStaticCard
import moe.shizuku.manager.ui.compose.TvTwoPaneLayout
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.lifecycle.Resource

@Composable
internal fun TVHomeScreen(
    serviceResource: Resource<ServiceStatus>?,
    grantedResource: Resource<Int>?,
    localNetworkPermissionState: LocalNetworkPermissionState,
    isPrimaryUser: Boolean,
    isRooted: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onStop: () -> Unit,
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

    val diagnostics = remember(status, grantedCount, localNetworkPermissionState) {
        buildDiagnostics(context, status, grantedCount, localNetworkPermissionState)
    }

    val lazyListState = rememberLazyListState()
    val initialFocusRequester = remember { FocusRequester() }
    val refreshFocusRequester = remember { FocusRequester() }
    val primaryTargetIsManageApps = adbPermission && running
    val primaryTargetIsRoot = !primaryTargetIsManageApps && isPrimaryUser && isRooted
    val primaryTargetIsWireless = !primaryTargetIsManageApps && !primaryTargetIsRoot && isPrimaryUser && canUseWirelessAdb
    val primaryTargetIsAdbCommand = !primaryTargetIsManageApps && !primaryTargetIsRoot && !primaryTargetIsWireless && isPrimaryUser

    val targetItemIndex: Int? = remember(
        adbPermission,
        running,
        isPrimaryUser,
        isRooted,
        canUseWirelessAdb
    ) {
        var idx = 1 // 0 is TvStatusCard
        if (adbPermission) {
            if (primaryTargetIsManageApps) return@remember idx
            idx += 2 // Manage Apps + Terminal
        }
        if (running && !adbPermission) {
            idx += 1 // ADB limited warning card
        }
        if (isPrimaryUser) {
            if (isRooted) {
                if (primaryTargetIsRoot) return@remember idx
                idx += 1
            }
            if (canUseWirelessAdb) {
                if (primaryTargetIsWireless) return@remember idx
                idx += 1
            }
            if (primaryTargetIsAdbCommand) return@remember idx
        }
        null
    }

    LaunchedEffect(Unit) {
        if (targetItemIndex != null) {
            lazyListState.scrollToItem(targetItemIndex)
            withFrameNanos { }
            if (runCatching { initialFocusRequester.requestFocus() }.isFailure) {
                runCatching { refreshFocusRequester.requestFocus() }
            }
        } else {
            runCatching { refreshFocusRequester.requestFocus() }
        }
    }

    TvTwoPaneLayout(
        title = stringResource(R.string.app_name),
        sidebarContent = {
            TvMenuButton(
                icon = R.drawable.ic_server_restart,
                label = R.string.home_refresh,
                onClick = onRefresh,
                modifier = Modifier.focusRequester(refreshFocusRequester)
            )
            TvMenuButton(
                icon = R.drawable.ic_action_settings_24dp,
                label = R.string.settings_title,
                onClick = onSettings
            )
            TvMenuButton(
                icon = R.drawable.ic_outline_info_24,
                label = R.string.action_about,
                onClick = onAbout
            )
            if (running) {
                TvMenuButton(
                    icon = R.drawable.ic_close_24,
                    label = R.string.action_stop,
                    onClick = onStop
                )
            }
        }
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 32.dp, end = 32.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                TvStatusCard(status = status)
            }

            if (adbPermission) {
                item {
                    TvSimpleActionCard(
                        icon = R.drawable.ic_system_icon,
                        title = if (running) {
                            context.resources.getQuantityString(
                                R.plurals.home_app_management_authorized_apps_count,
                                grantedCount,
                                grantedCount
                            )
                        } else {
                            stringResource(R.string.home_app_management_title)
                        },
                        body = if (running) {
                            stringResource(R.string.home_app_management_view_authorized_apps)
                        } else {
                            stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
                        },
                        enabled = running,
                        modifier = if (primaryTargetIsManageApps) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                        onClick = onManageApps
                    )
                }
                item {
                    TvSimpleActionCard(
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
                    TvStaticInfoCard(
                        icon = R.drawable.ic_warning_24,
                        title = stringResource(R.string.home_adb_is_limited_title),
                        body = stringResource(R.string.home_adb_is_limited_description),
                        danger = true
                    ) {
                        TvActionButton(onClick = onOpenAdbPermissionHelp) {
                            TvText(stringResource(R.string.home_adb_button_view_help))
                        }
                    }
                }
            }

            if (isPrimaryUser) {
                val rootRestart = running && status.uid == 0
                if (isRooted) {
                    item {
                        TvRootCard(
                            restart = rootRestart,
                            onStartRoot = onStartRoot,
                            primaryButtonModifier = if (primaryTargetIsRoot) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
                if (canUseWirelessAdb) {
                    item {
                        TvWirelessAdbCard(
                            localNetworkPermissionState = localNetworkPermissionState,
                            onStartWirelessAdb = onStartWirelessAdb,
                            onPairWirelessAdb = onPairWirelessAdb,
                            onOpenWirelessGuide = onOpenWirelessGuide,
                            primaryButtonModifier = if (primaryTargetIsWireless) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
                item {
                    TvAdbCommandCard(
                        onShowAdbCommand = onShowAdbCommand,
                        onOpenAdbHelp = onOpenAdbHelp,
                        primaryButtonModifier = if (primaryTargetIsAdbCommand) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
                if (!isRooted) {
                    item {
                        TvRootCard(
                            restart = rootRestart,
                            onStartRoot = onStartRoot
                        )
                    }
                }
            }

            if (localNetworkPermissionState.required && !localNetworkPermissionState.granted) {
                item {
                    TvStaticInfoCard(
                        icon = R.drawable.ic_warning_24,
                        title = stringResource(R.string.home_local_network_title),
                        body = stringResource(
                            R.string.home_local_network_description,
                            localNetworkPermissionState.label
                        ),
                        danger = true
                    ) {
                        TvActionButton(onClick = onRequestLocalNetworkPermission) {
                            TvText(stringResource(R.string.home_local_network_grant))
                        }
                    }
                }
            }

            item {
                TvDiagnosticsCard(
                    diagnostics = diagnostics,
                    onCopyDiagnostics = { onCopyDiagnostics(diagnostics) }
                )
            }

            item {
                TvSimpleActionCard(
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
private fun TvStatusCard(status: ServiceStatus) {
    val context = LocalContext.current
    val running = status.isRunning
    val title = if (running) {
        stringResource(R.string.home_status_service_is_running, stringResource(R.string.app_name))
    } else {
        stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
    }
    val summary = remember(status, running) { buildServiceSummary(context, status) }

    TvStaticInfoCard(
        icon = if (running) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp,
        title = title,
        body = summary
    )
}

@Composable
private fun TvRootCard(
    restart: Boolean,
    onStartRoot: () -> Unit,
    primaryButtonModifier: Modifier = Modifier
) {
    val buttonLabel = if (restart) R.string.home_root_button_restart else R.string.home_root_button_start
    TvStaticInfoCard(
        icon = R.drawable.ic_root_24dp,
        title = htmlToPlainText(stringResource(R.string.home_root_title)),
        body = htmlToPlainText(stringResource(R.string.home_root_description, "Don't kill my app!"))
    ) {
        TvActionButton(
            onClick = onStartRoot,
            modifier = primaryButtonModifier
        ) {
            TvText(stringResource(buttonLabel))
        }
    }
}

@Composable
private fun TvWirelessAdbCard(
    localNetworkPermissionState: LocalNetworkPermissionState,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onOpenWirelessGuide: () -> Unit,
    primaryButtonModifier: Modifier = Modifier
) {
    val body = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        htmlToPlainText(stringResource(R.string.home_wireless_adb_description))
    } else {
        htmlToPlainText(stringResource(R.string.home_wireless_adb_description_pre_11))
    }
    TvStaticInfoCard(
        icon = R.drawable.ic_wadb_24,
        title = htmlToPlainText(stringResource(R.string.home_wireless_adb_title)),
        body = body
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TvActionButton(
                onClick = onStartWirelessAdb,
                modifier = primaryButtonModifier
            ) {
                TvText(stringResource(R.string.home_root_button_start))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                TvSecondaryButton(onClick = onPairWirelessAdb) {
                    TvText(stringResource(R.string.adb_pairing))
                }
                TvSecondaryButton(onClick = onOpenWirelessGuide) {
                    TvText(stringResource(R.string.home_wireless_adb_view_guide_button))
                }
            }
        }
    }
}

@Composable
private fun TvAdbCommandCard(
    onShowAdbCommand: () -> Unit,
    onOpenAdbHelp: () -> Unit,
    primaryButtonModifier: Modifier = Modifier
) {
    TvStaticInfoCard(
        icon = R.drawable.ic_adb_24dp,
        title = htmlToPlainText(stringResource(R.string.home_adb_title)),
        body = htmlToPlainText(stringResource(R.string.home_adb_description, Helps.ADB.get()))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TvActionButton(
                onClick = onShowAdbCommand,
                modifier = primaryButtonModifier
            ) {
                TvText(stringResource(R.string.home_adb_button_view_command))
            }
            TvSecondaryButton(onClick = onOpenAdbHelp) {
                TvText(stringResource(R.string.home_adb_button_view_help))
            }
        }
    }
}

@Composable
private fun TvDiagnosticsCard(
    diagnostics: String,
    onCopyDiagnostics: () -> Unit
) {
    TvStaticInfoCard(
        icon = R.drawable.ic_outline_info_24,
        title = stringResource(R.string.home_diagnostics_title),
        body = diagnostics
    ) {
        TvSecondaryButton(onClick = onCopyDiagnostics) {
            TvText(stringResource(R.string.home_diagnostics_copy))
        }
    }
}

@Composable
private fun TvSimpleActionCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TvActionCard(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    ) {
        TvHomeCardBody(
            icon = icon,
            title = title,
            body = body,
            danger = false
        )
    }
}

@Composable
private fun TvStaticInfoCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    danger: Boolean = false,
    actions: (@Composable () -> Unit)? = null
) {
    TvStaticCard(danger = danger) {
        TvHomeCardBody(
            icon = icon,
            title = title,
            body = body,
            danger = danger,
            actions = actions
        )
    }
}

@Composable
private fun TvHomeCardBody(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    danger: Boolean,
    actions: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvSurface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            colors = TvSurfaceDefaults.colors(
                containerColor = if (danger) {
                    TvMaterialTheme.colorScheme.error
                } else {
                    TvMaterialTheme.colorScheme.primaryContainer
                }
            )
        ) {
            Box(contentAlignment = Alignment.Center) {
                ShizukuIcon(
                    icon = icon,
                    tint = if (danger) {
                        TvMaterialTheme.colorScheme.onError
                    } else {
                        TvMaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TvText(
                text = title,
                style = TvMaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (body.isNotBlank()) {
                TvText(
                    text = body,
                    style = TvMaterialTheme.typography.bodyMedium,
                    color = if (danger) {
                        TvMaterialTheme.colorScheme.onErrorContainer
                    } else {
                        TvMaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (actions != null) {
                Spacer(Modifier.height(4.dp))
                actions()
            }
        }
    }
}
