package moe.shizuku.manager.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.Card as WearCard
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import moe.shizuku.manager.R
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.lifecycle.Resource

@Composable
internal fun WearHomeScreen(
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
    val status = serviceResource?.data ?: ServiceStatus()
    val running = status.isRunning
    val canUseWirelessAdb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0
    val transformationSpec = rememberTransformationSpec()

    WearScreenScaffold { state ->
        TransformingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                WearScreenTitle(icon = R.drawable.ic_system_icon, title = stringResource(R.string.app_name))
            }

            item {
                WearCard(
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Column {
                        WearText(
                            text = if (running) stringResource(R.string.home_status_service_is_running, stringResource(R.string.app_name))
                                   else stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name)),
                            style = WearMaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            if (running) {
                item {
                    WearButton(
                        onClick = onManageApps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WearIcon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            WearText(text = stringResource(R.string.home_app_management_title))
                        }
                    }
                }
                item {
                    WearButton(
                        onClick = onModules,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WearIcon(Icons.Rounded.Extension, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            WearText(text = stringResource(R.string.modules_title))
                        }
                    }
                }
            } else {
                if (isRooted) {
                    item {
                        WearButton(
                            onClick = onStartRoot,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WearIcon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                WearText(text = stringResource(R.string.home_root_button_start))
                            }
                        }
                    }
                }
                if (canUseWirelessAdb) {
                    item {
                        WearButton(
                            onClick = onStartWirelessAdb,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WearIcon(Icons.Rounded.Usb, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                WearText(text = stringResource(R.string.home_wireless_adb_title))
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        item {
                            WearButton(
                                onClick = onPairWirelessAdb,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    WearIcon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    WearText(text = stringResource(R.string.adb_pairing))
                                }
                            }
                        }
                    }
                }
            }

            item {
                WearButton(
                    onClick = onSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WearIcon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        WearText(text = stringResource(R.string.settings_title))
                    }
                }
            }

            item {
                WearButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WearIcon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        WearText(text = stringResource(R.string.home_refresh))
                    }
                }
            }

            item {
                WearButton(
                    onClick = onAbout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WearIcon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        WearText(text = stringResource(R.string.action_about))
                    }
                }
            }

            if (running) {
                item {
                    WearButton(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WearIcon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            WearText(text = stringResource(R.string.action_stop))
                        }
                    }
                }
            }
        }
    }
}
