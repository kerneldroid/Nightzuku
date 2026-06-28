@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.home

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.SurfaceDefaults as TvSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.lifecycle.Resource
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

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

    val diagnostics = remember(status, grantedCount, localNetworkPermissionState) {
        buildDiagnostics(context, status, grantedCount, localNetworkPermissionState)
    }

    Row(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.app_name),
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            TvNavigationButton(
                icon = R.drawable.ic_server_restart,
                label = R.string.home_refresh,
                onClick = onRefresh
            )
            TvNavigationButton(
                icon = R.drawable.ic_action_settings_24dp,
                label = R.string.settings_title,
                onClick = onSettings
            )
            TvNavigationButton(
                icon = R.drawable.ic_outline_info_24,
                label = R.string.action_about,
                onClick = onAbout
            )
            if (running) {
                TvNavigationButton(
                    icon = R.drawable.ic_close_24,
                    label = R.string.action_stop,
                    onClick = onStop
                )
            }
        }


        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
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
                        onClick = onManageApps
                    )
                }
                item {
                    TvSimpleActionCard(
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
                    TvHomeCard(
                        icon = R.drawable.ic_warning_24,
                        title = stringResource(R.string.home_adb_is_limited_title),
                        body = stringResource(R.string.home_adb_is_limited_description)
                    ) {
                        TvButton(onClick = onOpenAdbPermissionHelp) {
                            TvText(stringResource(R.string.home_adb_button_view_help))
                        }
                    }
                }
            }

            if (isPrimaryUser) {
                val rootRestart = running && status.uid == 0
                if (isRooted) {
                    item {
                        TvRootCard(rootRestart, onStartRoot)
                    }
                }
                if (canUseWirelessAdb) {
                    item {
                        TvWirelessAdbCard(
                            localNetworkPermissionState = localNetworkPermissionState,
                            onStartWirelessAdb = onStartWirelessAdb,
                            onPairWirelessAdb = onPairWirelessAdb,
                            onOpenWirelessGuide = onOpenWirelessGuide
                        )
                    }
                }
                item {
                    TvAdbCommandCard(
                        onShowAdbCommand = onShowAdbCommand,
                        onOpenAdbHelp = onOpenAdbHelp
                    )
                }
                if (!isRooted) {
                    item {
                        TvRootCard(rootRestart, onStartRoot)
                    }
                }
            }

            if (localNetworkPermissionState.required && !localNetworkPermissionState.granted) {
                item {
                    TvHomeCard(
                        icon = R.drawable.ic_warning_24,
                        title = stringResource(R.string.home_local_network_title),
                        body = stringResource(
                            R.string.home_local_network_description,
                            localNetworkPermissionState.label
                        )
                    ) {
                        TvButton(onClick = onRequestLocalNetworkPermission) {
                            TvText(stringResource(R.string.home_local_network_grant))
                        }
                    }
                }
            }

            item {
                TvHomeCard(
                    icon = R.drawable.ic_outline_info_24,
                    title = stringResource(R.string.home_diagnostics_title),
                    body = diagnostics
                ) {
                    TvButton(onClick = { onCopyDiagnostics(diagnostics) }) {
                        TvText(stringResource(R.string.home_diagnostics_copy))
                    }
                }
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
private fun TvNavigationButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    onClick: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            TvText(text = stringResource(label), style = TvMaterialTheme.typography.labelLarge)
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

    TvHomeCard(
        icon = if (running) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp,
        title = title,
        body = summary
    )
}

@Composable
private fun TvRootCard(restart: Boolean, onStartRoot: () -> Unit) {
    val buttonLabel = if (restart) R.string.home_root_button_restart else R.string.home_root_button_start
    TvHomeCard(
        icon = R.drawable.ic_root_24dp,
        title = htmlToPlainText(stringResource(R.string.home_root_title)),
        body = htmlToPlainText(stringResource(R.string.home_root_description, "Don't kill my app!"))
    ) {
        TvButton(onClick = onStartRoot) {
            TvText(stringResource(buttonLabel))
        }
    }
}

@Composable
private fun TvWirelessAdbCard(
    localNetworkPermissionState: LocalNetworkPermissionState,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onOpenWirelessGuide: () -> Unit
) {
    val body = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        htmlToPlainText(stringResource(R.string.home_wireless_adb_description))
    } else {
        htmlToPlainText(stringResource(R.string.home_wireless_adb_description_pre_11))
    }
    TvHomeCard(
        icon = R.drawable.ic_wadb_24,
        title = htmlToPlainText(stringResource(R.string.home_wireless_adb_title)),
        body = body
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(onClick = onStartWirelessAdb) {
                TvText(stringResource(R.string.home_root_button_start))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                TvButton(onClick = onPairWirelessAdb) {
                    TvText(stringResource(R.string.adb_pairing))
                }
                TvButton(onClick = onOpenWirelessGuide) {
                    TvText(stringResource(R.string.home_wireless_adb_view_guide_button))
                }
            }
        }
    }
}

@Composable
private fun TvAdbCommandCard(onShowAdbCommand: () -> Unit, onOpenAdbHelp: () -> Unit) {
    TvHomeCard(
        icon = R.drawable.ic_adb_24dp,
        title = htmlToPlainText(stringResource(R.string.home_adb_title)),
        body = htmlToPlainText(stringResource(R.string.home_adb_description, Helps.ADB.get()))
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(onClick = onShowAdbCommand) {
                TvText(stringResource(R.string.home_adb_button_view_command))
            }
            TvButton(onClick = onOpenAdbHelp) {
                TvText(stringResource(R.string.home_adb_button_view_help))
            }
        }
    }
}

@Composable
private fun TvSimpleActionCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TvHomeCard(
        icon = icon,
        title = title,
        body = body,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun TvHomeCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {}
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = onClick ?: {},
        enabled = enabled && onClick != null,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.large),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            focusedContainerColor = TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvSurface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                colors = TvSurfaceDefaults.colors(containerColor = TvMaterialTheme.colorScheme.primaryContainer)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ShizukuIcon(
                        icon = icon,
                        tint = TvMaterialTheme.colorScheme.onPrimaryContainer,
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
                        color = TvMaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onClick == null) {
                    Spacer(Modifier.height(4.dp))
                    content()
                }
            }
        }
    }
}
