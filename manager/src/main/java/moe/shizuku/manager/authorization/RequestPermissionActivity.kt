package moe.shizuku.manager.authorization

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.Text as WearText
import kerneldroid.nightzuku.util.LabelSanitizer
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.htmlToPlainText
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME

class RequestPermissionActivity : AppActivity() {

    private data class PromptRequest(
        val uid: Int,
        val pid: Int,
        val requestCode: Int,
        val displayLabel: String,
        val packageSummary: String,
        val shownAtElapsedMs: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRequest by mutableStateOf<PromptRequest?>(null)
    private var resultDispatched = false
    private var pendingBinderListener: Shizuku.OnBinderReceivedListener? = null
    private var binderTimeoutRunnable: Runnable? = null

    private fun dispatchDecision(
        request: PromptRequest,
        allowed: Boolean,
        onetime: Boolean,
        ignoreTapGrace: Boolean = false
    ): Boolean {
        if (!ignoreTapGrace && allowed) {
            val elapsed = SystemClock.elapsedRealtime() - request.shownAtElapsedMs
            if (elapsed < TAP_GRACE_WINDOW_MS) {
                LOGGER.w("Ignored premature grant tap (%dms < %dms)", elapsed, TAP_GRACE_WINDOW_MS)
                return false
            }
        }
        if (resultDispatched && activeRequest == request) {
            return true
        }
        resultDispatched = true
        sendReply(request.uid, request.pid, request.requestCode, allowed, onetime)
        return true
    }

    private fun sendReply(requestUid: Int, requestPid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        val data = Bundle().apply {
            putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
            putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        }
        try {
            Shizuku.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        } catch (e: Throwable) {
            LOGGER.e(e, "dispatchPermissionConfirmationResult")
        }
    }

    private fun parseAndValidateRequest(intent: Intent?): PromptRequest? {
        if (intent == null) return null
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("applicationInfo")
        }
        if (uid <= 0 || pid <= 0 || ai == null || ai.uid != uid || ai.packageName.isNullOrEmpty()) {
            LOGGER.w("Invalid permission request extras: uid=%d pid=%d ai=%s", uid, pid, ai)
            return null
        }

        val uidPackages = try {
            packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        if (uidPackages.isNotEmpty() && !uidPackages.contains(ai.packageName)) {
            LOGGER.w("Rejected permission prompt: package %s does not belong to uid %d", ai.packageName, uid)
            return null
        }

        val rawLabel = try {
            ai.loadLabel(packageManager)
        } catch (_: Exception) {
            ai.packageName
        }
        val cleanLabel = LabelSanitizer.sanitize(rawLabel, ai.packageName)
        val siblings = uidPackages.filter { it != ai.packageName }
        val packageSummary = if (siblings.isEmpty()) {
            ai.packageName
        } else {
            "${ai.packageName} (+${siblings.joinToString(", ")})"
        }
        val fullLabel = if (cleanLabel == ai.packageName) {
            packageSummary
        } else {
            "$cleanLabel ($packageSummary)"
        }

        return PromptRequest(
            uid = uid,
            pid = pid,
            requestCode = requestCode,
            displayLabel = fullLabel,
            packageSummary = packageSummary,
            shownAtElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(true) }
        }
        window.decorView.filterTouchesWhenObscured = true

        val parsed = parseAndValidateRequest(intent)
        if (parsed == null) {
            finish()
            return
        }
        activeRequest = parsed
        resultDispatched = false

        if (!Shizuku.pingBinder()) {
            val timeout = Runnable {
                pendingBinderListener?.let { Shizuku.removeBinderReceivedListener(it) }
                pendingBinderListener = null
                if (!Shizuku.pingBinder()) {
                    LOGGER.e("Binder not received within %dms, closing prompt", BINDER_WAIT_TIMEOUT_MS)
                    finish()
                }
            }
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    pendingBinderListener = null
                    mainHandler.removeCallbacks(timeout)
                }
            }
            pendingBinderListener = listener
            binderTimeoutRunnable = timeout
            Shizuku.addBinderReceivedListenerSticky(listener)
            mainHandler.postDelayed(timeout, BINDER_WAIT_TIMEOUT_MS)
        }

        setContent {
            val current = activeRequest ?: return@setContent
            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@RequestPermissionActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    WearAlertDialog(
                        visible = true,
                        onDismissRequest = {
                            dispatchDecision(current, allowed = false, onetime = true, ignoreTapGrace = true)
                            finish()
                        },
                        icon = {
                            WearIcon(
                                painter = painterResource(R.drawable.ic_system_icon),
                                contentDescription = null
                            )
                        },
                        title = {
                            WearText(stringResource(R.string.app_name))
                        },
                        text = {
                            WearText(
                                text = htmlToPlainText(
                                    getString(
                                        R.string.permission_warning_template,
                                        current.displayLabel,
                                        getString(R.string.permission_group_description)
                                    )
                                )
                            )
                        },
                        confirmButton = {
                            WearButton(
                                onClick = {
                                    if (dispatchDecision(current, allowed = true, onetime = false)) {
                                        finish()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                WearText(stringResource(R.string.grant_dialog_button_allow_always))
                            }
                        },
                        dismissButton = {
                            WearFilledTonalButton(
                                onClick = {
                                    dispatchDecision(current, allowed = false, onetime = false, ignoreTapGrace = true)
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                WearText(stringResource(R.string.grant_dialog_button_deny))
                            }
                        }
                    )
                }
            } else {
                ShizukuExpressiveTheme {
                    AlertDialog(
                        onDismissRequest = {
                            dispatchDecision(current, allowed = false, onetime = true, ignoreTapGrace = true)
                            finish()
                        },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_system_icon),
                                contentDescription = null
                            )
                        },
                        title = {
                            Text(stringResource(R.string.app_name))
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = htmlToPlainText(
                                        getString(
                                            R.string.permission_warning_template,
                                            current.displayLabel,
                                            getString(R.string.permission_group_description)
                                        )
                                    )
                                )
                            }
                        },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        if (dispatchDecision(current, allowed = true, onetime = true)) {
                                            finish()
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.grant_dialog_button_allow_onetime))
                                }
                                Button(
                                    onClick = {
                                        if (dispatchDecision(current, allowed = true, onetime = false)) {
                                            finish()
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.grant_dialog_button_allow_always))
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    dispatchDecision(current, allowed = false, onetime = false, ignoreTapGrace = true)
                                    finish()
                                }
                            ) {
                                Text(stringResource(R.string.grant_dialog_button_deny))
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.extraLarge
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val previous = activeRequest
        if (previous != null && !resultDispatched) {
            sendReply(previous.uid, previous.pid, previous.requestCode, allowed = false, onetime = true)
        }
        val next = parseAndValidateRequest(intent)
        if (next == null) {
            resultDispatched = true
            finish()
            return
        }
        resultDispatched = false
        activeRequest = next
    }

    override fun onDestroy() {
        binderTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        binderTimeoutRunnable = null
        pendingBinderListener?.let { Shizuku.removeBinderReceivedListener(it) }
        pendingBinderListener = null

        val current = activeRequest
        if (current != null && !resultDispatched && !isChangingConfigurations) {
            resultDispatched = true
            sendReply(current.uid, current.pid, current.requestCode, allowed = false, onetime = true)
        }
        super.onDestroy()
    }

    companion object {
        private const val BINDER_WAIT_TIMEOUT_MS = 3_000L
        private const val TAP_GRACE_WINDOW_MS = 400L
    }
}
