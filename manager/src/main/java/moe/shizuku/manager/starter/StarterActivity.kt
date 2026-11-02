@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.starter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbPairingRequiredException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.HtmlText
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku

private class NotRootedException : Exception()

class StarterActivity : AppActivity() {

    private var waitingForService = false
    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null

    private val viewModel by viewModels {
        ViewModel(
            this,
            intent.getBooleanExtra(EXTRA_IS_ROOT, true),
            intent.getStringExtra(EXTRA_HOST),
            intent.getIntExtra(EXTRA_PORT, 0)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        binderReceivedListener?.let {
            Shizuku.removeBinderReceivedListener(it)
            binderReceivedListener = null
        }
    }

    private fun copyLogToClipboard(text: String) {
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.starter_output_title), text))
        Toast.makeText(this, R.string.home_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startedWithRoot = intent.getBooleanExtra(EXTRA_IS_ROOT, true)

        if (Shizuku.pingBinder()) {
            finish()
            return
        }

        viewModel.output.observe(this) {
            val output = it.data.orEmpty().trim()
            if (!waitingForService && output.endsWith("info: shizuku_starter exit with 0")) {
                waitingForService = true
                viewModel.appendOutput("")
                viewModel.appendOutput("Waiting for service...")
            }
        }

        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                binderReceivedListener = null
                runOnUiThread {
                    viewModel.appendOutput("Service started")
                    if (!isFinishing) {
                        finish()
                    }
                }
            }
        }
        binderReceivedListener = listener
        Shizuku.addBinderReceivedListenerSticky(listener)

        setContent {
            BackHandler {
                finish()
            }

            val outputResource by viewModel.output.observeAsState()
            val output = outputResource?.data.orEmpty()
            val failed = outputResource?.status == Status.ERROR
            var errorToShow by remember(outputResource) {
                mutableIntStateOf(
                    if (outputResource?.status == Status.ERROR) {
                        when (outputResource?.error) {
                            is AdbKeyException -> R.string.adb_error_key_store
                            is NotRootedException -> R.string.start_with_root_failed
                            is ConnectException -> R.string.cannot_connect_port
                            is AdbPairingRequiredException, is SSLProtocolException -> R.string.adb_pair_required
                            else -> 0
                        }
                    } else 0
                )
            }

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@StarterActivity)
            val isTv = moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@StarterActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    Box {
                        WearStarterScreen(
                            output = output,
                            failed = failed,
                            startedWithRoot = startedWithRoot
                        )

                        if (errorToShow != 0) {
                            moe.shizuku.manager.home.HomeErrorDialog(
                                message = stringResource(errorToShow),
                                onDismiss = { errorToShow = 0 }
                            )
                        }
                    }
                }
            } else if (isTv) {
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    Box {
                        TvStarterScreen(
                            onNavigateUp = { finish() },
                            output = output,
                            failed = failed,
                            startedWithRoot = startedWithRoot
                        )

                        if (errorToShow != 0) {
                            moe.shizuku.manager.home.HomeErrorDialog(
                                message = stringResource(errorToShow),
                                onDismiss = { errorToShow = 0 }
                            )
                        }
                    }
                }
            } else {
                val logText = output.ifBlank { stringResource(R.string.starting_root_shell) }
                ShizukuExpressiveTheme {
                    Box {
                        ShizukuLazyScaffold(
                            title = stringResource(R.string.starter),
                            onNavigateUp = { finish() },
                            navigationIcon = R.drawable.ic_close_24,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            actions = {
                                IconButton(onClick = { copyLogToClipboard(logText) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.starter_copy_log)
                                    )
                                }
                            }
                        ) {
                            item {
                                StarterStatusBlock(
                                    failed = failed,
                                    startedWithRoot = startedWithRoot
                                )
                            }

                            if (!failed) {
                                item {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
                                    )
                                }
                            }

                            item {
                                StarterLogSection(
                                    logText = logText,
                                    onCopy = { copyLogToClipboard(logText) }
                                )
                            }

                            if (failed) {
                                item {
                                    FilledTonalButton(
                                        onClick = { finish() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp),
                                        shape = MaterialTheme.shapes.largeIncreased
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(android.R.string.cancel))
                                    }
                                }
                            }
                        }

                        if (errorToShow != 0) {
                            moe.shizuku.manager.home.HomeErrorDialog(
                                message = stringResource(errorToShow),
                                onDismiss = { errorToShow = 0 }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {

        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_HOST = "$EXTRA.HOST"
        const val EXTRA_PORT = "$EXTRA.PORT"
    }
}

private class ViewModel(context: Context, root: Boolean, host: String?, port: Int) : androidx.lifecycle.ViewModel() {

    private val sb = StringBuilder()
    private val outputLock = Any()
    private val _output = MutableLiveData<Resource<String>>()

    val output = _output as LiveData<Resource<String>>

    init {
        try {
            if (root) {
                startRoot()
            } else {
                startAdb(host!!, port)
            }
        } catch (e: Throwable) {
            postResult(e)
        }
    }

    fun appendOutput(line: String) {
        synchronized(outputLock) {
            sb.appendLine(line)
        }
        postResult()
    }

    private fun appendRaw(value: String?) {
        synchronized(outputLock) {
            sb.append(value.orEmpty()).append('\n')
        }
        postResult()
    }

    private fun appendLine(value: String) {
        synchronized(outputLock) {
            sb.append(value).append('\n')
        }
        postResult()
    }

    private fun postResult(throwable: Throwable? = null) {
        val snapshot = synchronized(outputLock) {
            sb.toString()
        }
        if (throwable == null) {
            _output.postValue(Resource.success(snapshot))
        } else {
            _output.postValue(Resource.error(throwable, snapshot))
        }
    }

    private fun startRoot() {
        synchronized(outputLock) {
            sb.append("Starting with root...").append('\n').append('\n')
        }
        postResult()

        viewModelScope.launch(Dispatchers.IO) {
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                appendLine("\nCan't open root shell, try again...")
                if (!Shell.getShell().isRoot) {
                    appendLine("\nStill not :(")
                    postResult(NotRootedException())
                    return@launch
                }
            }

            Shell.cmd(Starter.internalCommand).to(object : CallbackList<String?>() {
                override fun onAddElement(s: String?) {
                    appendRaw(s)
                }
            }).submit {
                if (it.code != 0) {
                    appendLine("\nSend this to developer may help solve the problem.")
                }
            }
        }
    }

    private fun startAdb(host: String, port: Int) {
        synchronized(outputLock) {
            sb.append("Starting with wireless adb in port $port...").append('\n').append('\n')
        }
        postResult()

        viewModelScope.launch(Dispatchers.IO) {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                appendLine("\n${Log.getStackTraceString(e)}")

                postResult(AdbKeyException(e))
                return@launch
            }

            runCatching {
                AdbClient(host, port, key).use { client ->
                    client.connect()
                    client.shellCommand(Starter.internalCommand) {
                        synchronized(outputLock) {
                            sb.append(String(it))
                        }
                        postResult()
                    }
                }
            }.onFailure {
                it.printStackTrace()

                appendLine("\n${Log.getStackTraceString(it)}")
                postResult(it)
            }
        }
    }
}

@Composable
private fun StarterStatusBlock(
    failed: Boolean,
    startedWithRoot: Boolean
) {
    val accent by animateColorAsState(
        targetValue = if (failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        label = "starterAccent"
    )
    val onAccent = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = accent
        ) {
            Box(contentAlignment = Alignment.Center) {
                ShizukuIcon(
                    icon = when {
                        failed -> R.drawable.ic_warning_24
                        startedWithRoot -> R.drawable.ic_root_24dp
                        else -> R.drawable.ic_adb_24dp
                    },
                    contentDescription = null,
                    tint = onAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (startedWithRoot) {
                    HtmlText(R.string.home_root_title)
                } else {
                    HtmlText(R.string.home_wireless_adb_title)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    if (failed) R.string.notification_service_start_failed
                    else R.string.notification_service_starting
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    if (startedWithRoot) R.string.starter_mode_root_badge
                    else R.string.starter_mode_adb_badge
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StarterLogSection(
    logText: String,
    onCopy: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.starter_output_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.starter_copy_log)
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            SelectionContainer {
                Text(
                    text = logText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(scroll)
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
