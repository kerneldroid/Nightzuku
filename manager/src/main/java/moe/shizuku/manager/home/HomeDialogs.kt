@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package moe.shizuku.manager.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.MutableLiveData
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.Text as TvText
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbSettings
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import moe.shizuku.manager.ui.compose.MonospaceLog
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SmartLogManager
import java.net.ConnectException

@Composable
fun HomeAboutDialog(
    onDismiss: () -> Unit,
    onSourceCode: () -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.app_name)) },
                text = { WearText(versionName ?: "") }
            ) {
                item {
                    WearButton(onClick = onSourceCode, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(R.string.about_source_code_button))
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.app_name)) },
                text = { TvText(versionName ?: "") },
                confirmButton = {
                    TvButton(onClick = onSourceCode) {
                        TvText(stringResource(R.string.about_source_code_button))
                    }
                },
                dismissButton = {
                    TvOutlinedButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.ok))
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(versionName ?: "") },
            confirmButton = {
                TextButton(onClick = onSourceCode) {
                    Text(stringResource(R.string.about_source_code_button))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
fun HomeStopDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.action_stop)) },
                text = { WearText(stringResource(R.string.dialog_stop_message)) }
            ) {
                item {
                    WearButton(onClick = { onConfirm(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.ok))
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.action_stop)) },
                text = { TvText(stringResource(R.string.dialog_stop_message)) },
                confirmButton = {
                    TvButton(onClick = { onConfirm(); onDismiss() }) {
                        TvText(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TvOutlinedButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.cancel))
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.action_stop)) },
            text = { Text(stringResource(R.string.dialog_stop_message)) },
            confirmButton = {
                TextButton(onClick = { onConfirm(); onDismiss() }) {
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
}

@Composable
fun HomeStartMethodDialog(
    onDismiss: () -> Unit,
    onRoot: () -> Unit,
    onAdb: () -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)
    val title = stringResource(R.string.home_start_method_title)
    val message = stringResource(R.string.home_start_method_message)
    val rootLabel = stringResource(R.string.home_start_method_root)
    val adbLabel = stringResource(R.string.home_start_method_adb)

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(title) }
            ) {
                item {
                    WearButton(onClick = onRoot, modifier = Modifier.fillMaxWidth()) {
                        WearText(rootLabel)
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onAdb, modifier = Modifier.fillMaxWidth()) {
                        WearText(adbLabel)
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(title) },
                text = { TvText(message) },
                confirmButton = {
                    TvButton(onClick = onRoot) { TvText(rootLabel) }
                },
                dismissButton = {
                    TvOutlinedButton(onClick = onAdb) { TvText(adbLabel) }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onRoot, modifier = Modifier.fillMaxWidth()) {
                        Text(rootLabel)
                    }
                    FilledTonalButton(onClick = onAdb, modifier = Modifier.fillMaxWidth()) {
                        Text(adbLabel)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun HomeAdbCommandDialog(
    command: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.home_adb_button_view_command)) },
                text = {
                    WearText(command, style = WearMaterialTheme.typography.bodySmall)
                }
            ) {
                item {
                    WearButton(onClick = { onCopy(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(R.string.home_adb_dialog_view_command_copy_button))
                    }
                }
                item {
                    WearButton(onClick = { onSend(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(R.string.home_adb_dialog_view_command_button_send))
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.home_adb_button_view_command)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        MonospaceLog(command)
                    }
                },
                confirmButton = {
                    TvButton(onClick = { onCopy(); onDismiss() }) {
                        TvText(stringResource(R.string.home_adb_dialog_view_command_copy_button))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton(onClick = { onSend(); onDismiss() }) {
                            TvText(stringResource(R.string.home_adb_dialog_view_command_button_send))
                        }
                        TvOutlinedButton(onClick = onDismiss) {
                            TvText(stringResource(android.R.string.cancel))
                        }
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.home_adb_button_view_command)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(command)
                }
            },
            confirmButton = {
                TextButton(onClick = { onCopy(); onDismiss() }) {
                    Text(stringResource(R.string.home_adb_dialog_view_command_copy_button))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onSend(); onDismiss() }) {
                        Text(stringResource(R.string.home_adb_dialog_view_command_button_send))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        )
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun HomeAdbDiscoveryDialog(
    onDismiss: () -> Unit,
    onStart: (String, Int) -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val serviceState = remember { mutableStateOf<Pair<String, Int>?>(null) }

    val openDevSettings = {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
    }

    DisposableEffect(Unit) {
        val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) {
            serviceState.value = it
        }
        adbMdns.start()

        AdbSettings.enableWirelessAdb(context)

        onDispose {
            adbMdns.stop()
        }
    }

    val currentPort = serviceState.value?.second ?: -1
    val currentHost = serviceState.value?.first ?: ""
    val manualPort = remember { EnvironmentUtils.getAdbTcpPort() }
    val isTv = EnvironmentUtils.isTV(context)

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.dialog_adb_discovery)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WearText(stringResource(R.string.dialog_adb_discovery_message))
                        if (currentPort in 1..65535) {
                            WearText("Discovered port: $currentPort", color = WearMaterialTheme.colorScheme.primary)
                        }
                    }
                }
            ) {
                if (currentPort in 1..65535) {
                    item {
                        WearButton(onClick = { onStart(currentHost, currentPort) }, modifier = Modifier.fillMaxWidth()) {
                            WearText("Start ($currentPort)")
                        }
                    }
                } else if (manualPort != -1) {
                    item {
                        WearButton(onClick = { onStart("127.0.0.1", manualPort) }, modifier = Modifier.fillMaxWidth()) {
                            WearText("Start ($manualPort)")
                        }
                    }
                }
                item {
                    WearButton(onClick = openDevSettings, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(R.string.development_settings))
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.dialog_adb_discovery)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvText(stringResource(R.string.dialog_adb_discovery_message))
                        TvText(
                            text = stringResource(R.string.dialog_adb_discovery_message_toggle_wireless_debugging),
                            color = TvMaterialTheme.colorScheme.primary
                        )
                        if (currentPort in 1..65535) {
                            TvText("Discovered port: $currentPort", color = TvMaterialTheme.colorScheme.primary)
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (currentPort in 1..65535) {
                            TvButton(onClick = { onStart(currentHost, currentPort) }) {
                                TvText("Start ($currentPort)")
                            }
                        } else if (manualPort != -1) {
                            TvButton(onClick = { onStart("127.0.0.1", manualPort) }) {
                                TvText("Start ($manualPort)")
                            }
                        }
                        TvButton(onClick = openDevSettings) {
                            TvText(stringResource(R.string.development_settings))
                        }
                    }
                },
                dismissButton = {
                    TvOutlinedButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.cancel))
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_adb_discovery)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.dialog_adb_discovery_message))
                    Text(
                        text = stringResource(R.string.dialog_adb_discovery_message_toggle_wireless_debugging),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Row {
                    if (manualPort != -1) {
                        TextButton(onClick = { onStart("127.0.0.1", manualPort) }) {
                            Text(manualPort.toString())
                        }
                    }
                    TextButton(onClick = openDevSettings) {
                        Text(stringResource(R.string.development_settings))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    LaunchedEffect(currentPort) {
        if (currentPort in 1..65535) {
            onStart(currentHost, currentPort)
        }
    }
}

@Composable
fun HomeWadbNotEnabledDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.dialog_wireless_adb_not_enabled)) },
                text = { }
            ) {
                item {
                    WearButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.dialog_wireless_adb_not_enabled)) },
                text = { },
                confirmButton = {
                    TvButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.ok))
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_wireless_adb_not_enabled)) },
            text = { },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

private sealed class PairingStatus {
    object Idle : PairingStatus()
    object Success : PairingStatus()
    data class Error(val throwable: Throwable) : PairingStatus()
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun HomeAdbPairDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)

    val result = remember { MutableLiveData<PairingStatus>(PairingStatus.Idle) }
    val serviceInfo = remember { MutableLiveData<Pair<String, Int>>() }

    DisposableEffect(Unit) {
        val adbMdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { serviceInfo.postValue(it) }
        adbMdns.start()
        onDispose { adbMdns.stop() }
    }

    val discoveredService by serviceInfo.observeAsState()
    val discoveredPort = discoveredService?.second ?: -1
    val discoveredHost = discoveredService?.first ?: ""
    val pairStatus by result.observeAsState(PairingStatus.Idle)

    var portText by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf<String?>(null) }
    var pairingCodeError by remember { mutableStateOf<String?>(null) }
    var isPairing by remember { mutableStateOf(false) }

    LaunchedEffect(discoveredPort) {
        if (discoveredPort in 1..65535) portText = discoveredPort.toString()
    }

    LaunchedEffect(pairStatus) {
        when (pairStatus) {
            is PairingStatus.Success -> {
                onDismiss()
            }
            is PairingStatus.Error -> {
                isPairing = false
                val t = (pairStatus as PairingStatus.Error).throwable
                when (t) {
                    is ConnectException -> portError = context.getString(R.string.cannot_connect_port)
                    is AdbInvalidPairingCodeException -> pairingCodeError = context.getString(R.string.paring_code_is_wrong)
                    else -> pairingCodeError = t.message
                }
            }
            PairingStatus.Idle -> {}
        }
    }

    val onPair = {
        val p = portText.toIntOrNull() ?: -1
        if (p in 1..65535 && pairingCode.length == 6) {
            isPairing = true
            val prefs = ShizukuSettings.getPreferences()
            scope.launch(Dispatchers.IO) {
                try {
                    val key = AdbKey(PreferenceAdbKeyStore(prefs), "shizuku")
                    val host = if (p == discoveredPort && discoveredHost.isNotEmpty()) discoveredHost else "127.0.0.1"
                    runCatching {
                        AdbPairingClient(host, p, pairingCode, key).use { client ->
                            client.start()
                        }
                    }.onSuccess { ok ->
                        if (ok) {
                            result.postValue(PairingStatus.Success)
                        } else {
                            val err = Exception("Pairing handshake failed")
                            SmartLogManager.recordPairingError("HomeAdbPairDialog.handshake", host, p, err)
                            result.postValue(PairingStatus.Error(err))
                        }
                    }.onFailure {
                        SmartLogManager.recordPairingError("HomeAdbPairDialog.exchange", host, p, it)
                        result.postValue(PairingStatus.Error(it))
                    }
                } catch (e: Throwable) {
                    val keyErr = AdbKeyException(e)
                    SmartLogManager.recordPairingError("HomeAdbPairDialog.keyInit", "127.0.0.1", p, keyErr)
                    result.postValue(PairingStatus.Error(keyErr))
                }
            }
        }
    }

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.dialog_adb_pairing_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(R.string.dialog_adb_pairing_paring_code)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = pairingCodeError != null,
                            supportingText = pairingCodeError?.let { { Text(it) } },
                            enabled = !isPairing
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.dialog_adb_port)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = portError != null,
                            supportingText = portError?.let { { Text(it) } },
                            enabled = !isPairing
                        )
                    }
                }
            ) {
                item {
                    WearButton(
                        onClick = onPair,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isPairing
                    ) {
                        WearText(stringResource(android.R.string.ok))
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.dialog_adb_pairing_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(R.string.dialog_adb_pairing_paring_code)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = pairingCodeError != null,
                            supportingText = pairingCodeError?.let { { Text(it) } },
                            enabled = !isPairing
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.dialog_adb_port)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = portError != null,
                            supportingText = portError?.let { { Text(it) } },
                            enabled = !isPairing
                        )
                    }
                },
                confirmButton = {
                    TvButton(onClick = onPair, enabled = !isPairing) {
                        TvText(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TvOutlinedButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.cancel))
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_adb_pairing_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.dialog_adb_pairing_paring_code)) },
                        modifier = Modifier.fillMaxWidth(),
                        isError = pairingCodeError != null,
                        supportingText = pairingCodeError?.let { { Text(it) } },
                        enabled = !isPairing
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.dialog_adb_port)) },
                        modifier = Modifier.fillMaxWidth(),
                        isError = portError != null,
                        supportingText = portError?.let { { Text(it) } },
                        enabled = !isPairing
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onPair, enabled = !isPairing) {
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
}

@Composable
fun HomeAdbLimitedDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)
    val message = stringResource(R.string.app_management_dialog_adb_is_limited_message, moe.shizuku.manager.Helps.ADB.get())

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.app_management_dialog_adb_is_limited_title)) },
                text = { WearText(message) }
            ) {
                item {
                    WearButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.app_management_dialog_adb_is_limited_title)) },
                text = { TvText(message) },
                confirmButton = {
                    TvButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.ok))
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.app_management_dialog_adb_is_limited_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
fun HomeErrorDialog(message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.starter)) },
                text = { WearText(message) }
            ) {
                item {
                    WearButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.starter)) },
                text = { TvText(message) },
                confirmButton = {
                    TvButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.ok))
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.starter)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}


@Composable
fun SmartLogRecoveryDialog(
    logText: String,
    snapshotCount: Int,
    totalErrorEvents: Int,
    isMultiError: Boolean,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onOpenIssues: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val isTv = EnvironmentUtils.isTV(context)
    val headerMessage = if (isMultiError) {
        stringResource(R.string.smart_log_dialog_message_multiple, totalErrorEvents.coerceAtLeast(2))
    } else {
        stringResource(R.string.smart_log_dialog_message_single)
    }
    val logScrollState = rememberScrollState()

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = { WearText(stringResource(R.string.smart_log_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WearText(
                            text = headerMessage,
                            style = WearMaterialTheme.typography.bodySmall
                        )
                        WearText(
                            text = logText.take(600),
                            style = WearMaterialTheme.typography.bodySmall
                        )
                    }
                }
            ) {
                item {
                    WearButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(R.string.smart_log_button_copy))
                    }
                }
                item {
                    WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(R.string.smart_log_button_close))
                    }
                }
                item {
                    WearButton(onClick = onOpenIssues, modifier = Modifier.fillMaxWidth()) {
                        WearText(stringResource(R.string.smart_log_button_open_issues))
                    }
                }
            }
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.smart_log_dialog_title)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TvText(headerMessage)
                        MonospaceLog(logText)
                        TvButton(
                            onClick = onOpenIssues,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TvText(stringResource(R.string.smart_log_button_open_issues))
                        }
                    }
                },
                confirmButton = {
                    TvButton(onClick = onCopy) {
                        TvText(stringResource(R.string.smart_log_button_copy))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (snapshotCount > 0) {
                            TvOutlinedButton(onClick = onClear) {
                                TvText(stringResource(R.string.smart_log_button_clear))
                            }
                        }
                        TvOutlinedButton(onClick = onDismiss) {
                            TvText(stringResource(R.string.smart_log_button_close))
                        }
                    }
                },
                containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                shape = TvMaterialTheme.shapes.extraLarge
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                ShizukuIcon(
                    icon = R.drawable.ic_warning_24,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.smart_log_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = headerMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.smart_log_window_header,
                                        snapshotCount.coerceAtLeast(1)
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(onClick = onCopy) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.smart_log_button_copy),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            SelectionContainer {
                                Text(
                                    text = logText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp, max = 240.dp)
                                        .verticalScroll(logScrollState)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onCopy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.smart_log_button_copy))
                        }
                        if (snapshotCount > 0) {
                            OutlinedButton(onClick = onClear) {
                                Text(stringResource(R.string.smart_log_button_clear))
                            }
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.smart_log_button_close))
                        }
                    }

                    Button(
                        onClick = onOpenIssues,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        ShizukuIcon(
                            icon = R.drawable.ic_outline_open_in_new_24,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.smart_log_button_open_issues),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = null
        )
    }
}
