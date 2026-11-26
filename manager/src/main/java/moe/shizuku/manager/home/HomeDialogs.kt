@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package moe.shizuku.manager.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingAutoDetect
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbPortHistory
import moe.shizuku.manager.adb.AdbPortScanner
import moe.shizuku.manager.adb.AdbSettings
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import moe.shizuku.manager.ui.compose.TvActionButton
import moe.shizuku.manager.ui.compose.TvDialog
import moe.shizuku.manager.ui.compose.TvSecondaryButton
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
        val primaryFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.app_name)) },
                text = { TvText(versionName ?: "") },
                initialFocusRequester = primaryFocusRequester,
                confirmButton = {
                    TvActionButton(
                        onClick = onSourceCode,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(stringResource(R.string.about_source_code_button))
                    }
                },
                dismissButton = {
                    TvSecondaryButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.ok))
                    }
                }
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
        val primaryFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.action_stop)) },
                text = { TvText(stringResource(R.string.dialog_stop_message)) },
                initialFocusRequester = primaryFocusRequester,
                confirmButton = {
                    TvActionButton(
                        onClick = { onConfirm(); onDismiss() },
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TvSecondaryButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.cancel))
                    }
                }
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
        val primaryFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(title) },
                text = { TvText(message) },
                initialFocusRequester = primaryFocusRequester,
                confirmButton = {
                    TvActionButton(
                        onClick = onRoot,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(rootLabel)
                    }
                },
                dismissButton = {
                    TvSecondaryButton(onClick = onAdb) {
                        TvText(adbLabel)
                    }
                }
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
        val primaryFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.home_adb_button_view_command)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        MonospaceLog(command)
                    }
                },
                initialFocusRequester = primaryFocusRequester,
                confirmButton = {
                    TvActionButton(
                        onClick = { onCopy(); onDismiss() },
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(stringResource(R.string.home_adb_dialog_view_command_copy_button))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvSecondaryButton(onClick = { onSend(); onDismiss() }) {
                            TvText(stringResource(R.string.home_adb_dialog_view_command_button_send))
                        }
                        TvSecondaryButton(onClick = onDismiss) {
                            TvText(stringResource(android.R.string.cancel))
                        }
                    }
                }
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
        val mainHandler = Handler(Looper.getMainLooper())
        val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) {
            if (it.second in 1..65535) {
                AdbPortHistory.record(context, it.second)
            }
            serviceState.value = it
        }
        val scanner = AdbPortScanner(
            order = AdbPortScanner.buildOrder(AdbPortHistory.load(context)),
            requireTlsOnly = true
        ) { port ->
            AdbPortHistory.record(context, port)
            mainHandler.post {
                if (serviceState.value?.second !in 1..65535) {
                    serviceState.value = AdbPortScanner.LOOPBACK_IP to port
                }
            }
        }
        adbMdns.start()
        scanner.start()

        AdbSettings.enableWirelessAdb(context)

        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            scanner.stop()
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
        val primaryFocusRequester = remember { FocusRequester() }
        val hasDirectStart = currentPort in 1..65535 || manualPort != -1
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
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
                initialFocusRequester = primaryFocusRequester,
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (currentPort in 1..65535) {
                            TvActionButton(
                                onClick = { onStart(currentHost, currentPort) },
                                modifier = Modifier.focusRequester(primaryFocusRequester)
                            ) {
                                TvText("Start ($currentPort)")
                            }
                        } else if (manualPort != -1) {
                            TvActionButton(
                                onClick = { onStart("127.0.0.1", manualPort) },
                                modifier = Modifier.focusRequester(primaryFocusRequester)
                            ) {
                                TvText("Start ($manualPort)")
                            }
                        }
                        TvActionButton(
                            onClick = openDevSettings,
                            modifier = if (!hasDirectStart) {
                                Modifier.focusRequester(primaryFocusRequester)
                            } else {
                                Modifier
                            }
                        ) {
                            TvText(stringResource(R.string.development_settings))
                        }
                    }
                },
                dismissButton = {
                    TvSecondaryButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.cancel))
                    }
                }
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
        val primaryFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.dialog_wireless_adb_not_enabled)) },
                text = { },
                initialFocusRequester = primaryFocusRequester,
                confirmButton = {
                    TvActionButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(stringResource(android.R.string.ok))
                    }
                }
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
    var autoArmed by remember { mutableStateOf(AdbPairingAutoDetect.isArmed(context)) }

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
                            val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                                if (cause != null) {
                                    client.cancel()
                                }
                            }
                            try {
                                client.start()
                            } finally {
                                cancelHandle.dispose()
                            }
                        }
                    }.onSuccess { ok ->
                        if (!coroutineContext.isActive) return@launch
                        if (ok) {
                            result.postValue(PairingStatus.Success)
                        } else {
                            val err = Exception("Pairing handshake failed")
                            SmartLogManager.recordPairingError("HomeAdbPairDialog.handshake", host, p, err)
                            result.postValue(PairingStatus.Error(err))
                        }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        if (!coroutineContext.isActive) return@launch
                        SmartLogManager.recordPairingError("HomeAdbPairDialog.exchange", host, p, it)
                        result.postValue(PairingStatus.Error(it))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!coroutineContext.isActive) return@launch
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
        val primaryFocusRequester = remember { FocusRequester() }
        val portFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.dialog_adb_pairing_title)) },
                initialFocusRequester = primaryFocusRequester,
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(R.string.dialog_adb_pairing_paring_code)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { runCatching { portFocusRequester.requestFocus() } }
                            ),
                            isError = pairingCodeError != null,
                            supportingText = pairingCodeError?.let { { Text(it) } },
                            enabled = !isPairing
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.dialog_adb_port)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(portFocusRequester),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { onPair() }
                            ),
                            isError = portError != null,
                            supportingText = portError?.let { { Text(it) } },
                            enabled = !isPairing
                        )

                        val autoEnabled = AdbPairingAutoDetect.isServiceEnabled(context)
                        TvSecondaryButton(
                            onClick = {
                                autoArmed = true
                                AdbPairingAutoDetect.arm(context)
                                if (autoEnabled) {
                                    AdbPairingAutoDetect.openDevelopmentSettings(context)
                                } else {
                                    AdbPairingAutoDetect.openAccessibilitySettings(context)
                                }
                            },
                            enabled = !isPairing
                        ) {
                            TvText(stringResource(R.string.adb_pairing_auto_code))
                        }
                        if (autoArmed) {
                            TvText(
                                text = stringResource(R.string.adb_pairing_auto_waiting),
                                style = TvMaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    TvActionButton(
                        onClick = onPair,
                        enabled = !isPairing,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TvSecondaryButton(onClick = onDismiss) {
                        TvText(stringResource(android.R.string.cancel))
                    }
                }
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
        val primaryFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.app_management_dialog_adb_is_limited_title)) },
                text = { TvText(message) },
                initialFocusRequester = primaryFocusRequester,
                confirmButton = {
                    TvActionButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(stringResource(android.R.string.ok))
                    }
                }
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
        val primaryFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.starter)) },
                text = { TvText(message) },
                initialFocusRequester = primaryFocusRequester,
                confirmButton = {
                    TvActionButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(stringResource(android.R.string.ok))
                    }
                }
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
        val primaryFocusRequester = remember { FocusRequester() }
        moe.shizuku.manager.ui.compose.TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = { TvText(stringResource(R.string.smart_log_dialog_title)) },
                initialFocusRequester = primaryFocusRequester,
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TvText(headerMessage)
                        MonospaceLog(logText)
                        TvSecondaryButton(
                            onClick = onOpenIssues,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TvText(stringResource(R.string.smart_log_button_open_issues))
                        }
                    }
                },
                confirmButton = {
                    TvActionButton(
                        onClick = onCopy,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        TvText(stringResource(R.string.smart_log_button_copy))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (snapshotCount > 0) {
                            TvSecondaryButton(onClick = onClear) {
                                TvText(stringResource(R.string.smart_log_button_clear))
                            }
                        }
                        TvSecondaryButton(onClick = onDismiss) {
                            TvText(stringResource(R.string.smart_log_button_close))
                        }
                    }
                }
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
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
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (snapshotCount > 0) {
                                    stringResource(R.string.smart_log_window_header, snapshotCount)
                                } else {
                                    stringResource(R.string.smart_log_window_header_clean)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )

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
                            Text(
                                text = stringResource(R.string.smart_log_button_copy),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.smart_log_button_close),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    if (snapshotCount > 0) {
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.smart_log_button_clear))
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
