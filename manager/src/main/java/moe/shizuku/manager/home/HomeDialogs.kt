@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.wear.compose.material3.ExperimentalWearMaterial3Api::class)

package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.ExperimentalWearMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import java.net.ConnectException

@Composable
fun HomeAboutDialog(
    onDismiss: () -> Unit,
    onSourceCode: () -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                show = true,
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

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                show = true,
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
fun HomeAdbCommandDialog(
    command: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                show = true,
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

@Composable
fun HomeAdbDiscoveryDialog(
    onDismiss: () -> Unit,
    onStart: (Int) -> Unit
) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    val portState = remember { mutableIntStateOf(-1) }
    
    val openDevSettings = {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
    }

    DisposableEffect(Unit) {
        val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) {
            portState.intValue = it
        }
        adbMdns.start()
        
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        }
        
        onDispose {
            adbMdns.stop()
        }
    }

    val currentPort = portState.intValue
    val manualPort = remember { EnvironmentUtils.getAdbTcpPort() }

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                show = true,
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
                        WearButton(onClick = { onStart(currentPort) }, modifier = Modifier.fillMaxWidth()) {
                            WearText("Start ($currentPort)")
                        }
                    }
                } else if (manualPort != -1) {
                    item {
                        WearButton(onClick = { onStart(manualPort) }, modifier = Modifier.fillMaxWidth()) {
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
                        TextButton(onClick = { onStart(manualPort) }) {
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
            onStart(currentPort)
        }
    }
}

@Composable
fun HomeWadbNotEnabledDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                show = true,
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

@Composable
fun HomeAdbPairDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isWatch = EnvironmentUtils.isWatch(context)
    
    val result = remember { MutableLiveData<PairingStatus>(PairingStatus.Idle) }
    val port = remember { MutableLiveData<Int>() }
    
    DisposableEffect(Unit) {
        val adbMdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { port.postValue(it) }
        adbMdns.start()
        onDispose { adbMdns.stop() }
    }

    val discoveredPort by port.observeAsState(-1)
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
            val scope = kotlinx.coroutines.MainScope()
            scope.launch(Dispatchers.IO) {
                try {
                    val key = AdbKey(PreferenceAdbKeyStore(prefs), "shizuku")
                    AdbPairingClient("127.0.0.1", p, pairingCode, key).runCatching { start() }
                        .onSuccess {
                            if (it) result.postValue(PairingStatus.Success)
                            else result.postValue(PairingStatus.Error(Exception("Pairing failed")))
                        }
                        .onFailure { result.postValue(PairingStatus.Error(it)) }
                } catch (e: Throwable) {
                    result.postValue(PairingStatus.Error(AdbKeyException(e)))
                }
            }
        }
    }

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                show = true,
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
    val message = stringResource(R.string.app_management_dialog_adb_is_limited_message, moe.shizuku.manager.Helps.ADB.get())

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                show = true,
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

    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearAlertDialog(
                show = true,
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
