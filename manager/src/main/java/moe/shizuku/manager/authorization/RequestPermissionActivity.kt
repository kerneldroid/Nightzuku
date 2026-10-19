package moe.shizuku.manager.authorization

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.Text as WearText
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.htmlToPlainText
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RequestPermissionActivity : AppActivity() {

    private fun setResult(requestUid: Int, requestPid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        val data = Bundle()
        data.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        data.putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        try {
            Shizuku.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        } catch (e: Throwable) {
            LOGGER.e(e, "dispatchPermissionConfirmationResult")
        }
    }

    private fun waitForBinder(): Boolean {
        val countDownLatch = CountDownLatch(1)

        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                countDownLatch.countDown()
            }
        }

        Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

        return try {
            val received = countDownLatch.await(5, TimeUnit.SECONDS)
            if (!received) {
                LOGGER.e("Binder not received in 5s")
            }
            received
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOGGER.e(e, "Interrupted waiting for binder")
            false
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!waitForBinder()) {
            finish()
            return
        }

        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("applicationInfo")
        }
        if (uid == -1 || pid == -1 || ai == null) {
            finish()
            return
        }

        val label = try {
            ai.loadLabel(packageManager)
        } catch (e: Exception) {
            ai.packageName
        }

        setContent {
            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@RequestPermissionActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    WearAlertDialog(
                        visible = true,
                        onDismissRequest = {
                            setResult(uid, pid, requestCode, allowed = false, onetime = true)
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
                                        label,
                                        getString(R.string.permission_group_description)
                                    )
                                )
                            )
                        },
                        confirmButton = {
                            WearButton(
                                onClick = {
                                    setResult(uid, pid, requestCode, allowed = true, onetime = false)
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                WearText(stringResource(R.string.grant_dialog_button_allow_always))
                            }
                        },
                        dismissButton = {
                            WearFilledTonalButton(
                                onClick = {
                                    setResult(uid, pid, requestCode, allowed = false, onetime = false)
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
                            setResult(uid, pid, requestCode, allowed = false, onetime = true)
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
                                            label,
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
                                        setResult(uid, pid, requestCode, allowed = true, onetime = true)
                                        finish()
                                    }
                                ) {
                                    Text(stringResource(R.string.grant_dialog_button_allow_onetime))
                                }
                                Button(
                                    onClick = {
                                        setResult(uid, pid, requestCode, allowed = true, onetime = false)
                                        finish()
                                    }
                                ) {
                                    Text(stringResource(R.string.grant_dialog_button_allow_always))
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    setResult(uid, pid, requestCode, allowed = false, onetime = false)
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
}
