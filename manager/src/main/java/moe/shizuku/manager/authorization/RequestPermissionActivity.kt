@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.authorization

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
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
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.TvActionButton
import moe.shizuku.manager.ui.compose.TvSecondaryButton
import moe.shizuku.manager.ui.compose.TvShizukuTheme
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
        val packageSummary: String
    )

    private class ObscuredTouchFilteringWindowCallback(
        val delegate: Window.Callback,
        private val isObscured: (MotionEvent) -> Boolean
    ) : Window.Callback by delegate {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null && isObscured(event)) {
                LOGGER.w("Rejected obscured dialog touch event (flags=0x%x)", event.flags)
                return true
            }
            return delegate.dispatchTouchEvent(event)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRequest by mutableStateOf<PromptRequest?>(null)
    private var requestGeneration by mutableStateOf(0L)
    private var buttonsShownAtElapsedMs: Long = 0L
    private var resultDispatched = false
    private var pendingBinderListener: Shizuku.OnBinderReceivedListener? = null
    private var binderTimeoutRunnable: Runnable? = null

    private fun isObscuredTouch(ev: MotionEvent): Boolean {
        val partialFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        } else {
            FLAG_WINDOW_IS_PARTIALLY_OBSCURED_COMPAT
        }
        return (ev.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or partialFlag)) != 0
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && isObscuredTouch(ev)) {
            LOGGER.w("Rejected obscured touch event (flags=0x%x)", ev.flags)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    @Composable
    private fun HardenDialogWindow(current: PromptRequest, generation: Long) {
        val dialogView = LocalView.current
        val dialogWindow = generateSequence(dialogView.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()
            ?.window
        SideEffect {
            dialogView.rootView.filterTouchesWhenObscured = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { dialogWindow?.setHideOverlayWindows(true) }
            }
        }
        DisposableEffect(dialogWindow) {
            if (dialogWindow != null) {
                dialogWindow.decorView.filterTouchesWhenObscured = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { dialogWindow.setHideOverlayWindows(true) }
                }
                val currentCallback = dialogWindow.callback
                if (currentCallback != null && currentCallback !is ObscuredTouchFilteringWindowCallback) {
                    val wrapped = ObscuredTouchFilteringWindowCallback(currentCallback, ::isObscuredTouch)
                    dialogWindow.callback = wrapped
                    onDispose {
                        if (dialogWindow.callback === wrapped) {
                            dialogWindow.callback = wrapped.delegate
                        }
                    }
                } else {
                    onDispose { }
                }
            } else {
                onDispose { }
            }
        }
        LaunchedEffect(current, generation) {
            withFrameNanos { }
            buttonsShownAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun dispatchDecision(
        request: PromptRequest,
        allowed: Boolean,
        onetime: Boolean,
        ignoreTapGrace: Boolean = false
    ): Boolean {
        if (!ignoreTapGrace && allowed) {
            val shownAt = buttonsShownAtElapsedMs
            if (shownAt <= 0L) {
                LOGGER.w("Ignored premature grant tap before action buttons were rendered")
                return false
            }
            val elapsed = SystemClock.elapsedRealtime() - shownAt
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
            LOGGER.w("Invalid permission request extras: uid=%d pid=%d ai=%s", uid, pid, ai?.toString() ?: "null")
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
            packageSummary = packageSummary
        )
    }

    private fun restoreRequest(savedInstanceState: Bundle?): PromptRequest? {
        if (savedInstanceState == null) return null
        val uid = savedInstanceState.getInt(STATE_UID, -1)
        val pid = savedInstanceState.getInt(STATE_PID, -1)
        val requestCode = savedInstanceState.getInt(STATE_REQUEST_CODE, -1)
        val displayLabel = savedInstanceState.getString(STATE_DISPLAY_LABEL)
        val packageSummary = savedInstanceState.getString(STATE_PACKAGE_SUMMARY)
        if (uid <= 0 || pid <= 0 || displayLabel.isNullOrEmpty() || packageSummary.isNullOrEmpty()) {
            return null
        }
        return PromptRequest(
            uid = uid,
            pid = pid,
            requestCode = requestCode,
            displayLabel = displayLabel,
            packageSummary = packageSummary
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(true) }
        }
        window.decorView.filterTouchesWhenObscured = true

        val parsed = restoreRequest(savedInstanceState) ?: parseAndValidateRequest(intent)
        if (parsed == null) {
            finish()
            return
        }
        resultDispatched = savedInstanceState?.getBoolean(STATE_RESULT_DISPATCHED, false) == true
        if (resultDispatched) {
            finish()
            return
        }
        buttonsShownAtElapsedMs = 0L
        activeRequest = parsed
        requestGeneration += 1L

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
            val generation = requestGeneration
            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@RequestPermissionActivity)
            val isTv = moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@RequestPermissionActivity)
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
                            HardenDialogWindow(current, generation)
                            WearText(stringResource(R.string.app_name))
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                WearText(
                                    text = htmlToPlainText(
                                        getString(
                                            R.string.permission_warning_template,
                                            current.displayLabel,
                                            getString(R.string.permission_group_description)
                                        )
                                    )
                                )
                                WearFilledTonalButton(
                                    onClick = {
                                        if (dispatchDecision(current, allowed = true, onetime = true)) {
                                            finish()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    WearText(stringResource(R.string.grant_dialog_button_allow_onetime))
                                }
                            }
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
            } else if (isTv) {
                val denyFocusRequester = remember(current, generation) { FocusRequester() }
                TvShizukuTheme(wrapSurface = false) {
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
                            HardenDialogWindow(current, generation)
                            LaunchedEffect(current, generation) {
                                runCatching { denyFocusRequester.requestFocus() }
                            }
                            TvText(stringResource(R.string.app_name))
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                TvText(
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
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TvSecondaryButton(
                                    onClick = {
                                        if (dispatchDecision(current, allowed = true, onetime = true)) {
                                            finish()
                                        }
                                    }
                                ) {
                                    TvText(stringResource(R.string.grant_dialog_button_allow_onetime))
                                }
                                TvActionButton(
                                    onClick = {
                                        if (dispatchDecision(current, allowed = true, onetime = false)) {
                                            finish()
                                        }
                                    }
                                ) {
                                    TvText(stringResource(R.string.grant_dialog_button_allow_always))
                                }
                            }
                        },
                        dismissButton = {
                            TvSecondaryButton(
                                onClick = {
                                    dispatchDecision(current, allowed = false, onetime = false, ignoreTapGrace = true)
                                    finish()
                                },
                                modifier = Modifier.focusRequester(denyFocusRequester)
                            ) {
                                TvText(stringResource(R.string.grant_dialog_button_deny))
                            }
                        },
                        containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                        shape = TvMaterialTheme.shapes.extraLarge
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
                            HardenDialogWindow(current, generation)
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
        buttonsShownAtElapsedMs = 0L
        activeRequest = next
        requestGeneration += 1L
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RESULT_DISPATCHED, resultDispatched)
        activeRequest?.let { req ->
            outState.putInt(STATE_UID, req.uid)
            outState.putInt(STATE_PID, req.pid)
            outState.putInt(STATE_REQUEST_CODE, req.requestCode)
            outState.putString(STATE_DISPLAY_LABEL, req.displayLabel)
            outState.putString(STATE_PACKAGE_SUMMARY, req.packageSummary)
        }
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
        private const val FLAG_WINDOW_IS_PARTIALLY_OBSCURED_COMPAT = 0x2
        private const val STATE_UID = "state_uid"
        private const val STATE_PID = "state_pid"
        private const val STATE_REQUEST_CODE = "state_request_code"
        private const val STATE_DISPLAY_LABEL = "state_display_label"
        private const val STATE_PACKAGE_SUMMARY = "state_package_summary"
        private const val STATE_RESULT_DISPATCHED = "state_result_dispatched"
    }
}
