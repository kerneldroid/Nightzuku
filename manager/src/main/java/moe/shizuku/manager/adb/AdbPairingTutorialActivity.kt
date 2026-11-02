@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.adb

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.Card as WearCard
import androidx.wear.compose.material3.CardDefaults as WearCardDefaults
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.ExpressiveCard
import moe.shizuku.manager.ui.compose.SettingsSectionHeader
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import rikka.compatibility.DeviceCompatibility

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingTutorialActivity : AppActivity() {

    private var notificationEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationEnabled = isNotificationEnabled()
        if (notificationEnabled) {
            startPairingService()
        }

        setContent {
            BackHandler {
                finish()
            }

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@AdbPairingTutorialActivity)
            val isTv = moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@AdbPairingTutorialActivity)

            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                        androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                moe.shizuku.manager.ui.compose.WearScreenTitle(
                                    icon = Icons.Rounded.Usb,
                                    title = stringResource(R.string.adb_pairing_tutorial_title)
                                )
                            }
                            if (notificationEnabled) {
                                item {
                                    WearCard(
                                        onClick = {},
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        WearText(
                                            text = stringResource(R.string.adb_pairing_tutorial_content_notification),
                                            style = WearMaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            } else {
                                item {
                                    WearCard(
                                        onClick = ::openNotificationSettings,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = WearCardDefaults.cardColors(
                                            containerColor = WearMaterialTheme.colorScheme.errorContainer
                                        )
                                    ) {
                                        WearText(
                                            text = stringResource(R.string.adb_pairing_tutorial_content_notification_blocked),
                                            style = WearMaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                            if (notificationEnabled) {
                                item {
                                    WearCard(
                                        onClick = ::openDeveloperOptions,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column {
                                            WearText(
                                                text = "1. " + stringResource(R.string.adb_pairing_tutorial_content_steps),
                                                style = WearMaterialTheme.typography.labelMedium
                                            )
                                            WearText(
                                                text = stringResource(R.string.development_settings),
                                                style = WearMaterialTheme.typography.bodySmall,
                                                color = WearMaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                                item {
                                    WearCard(
                                        onClick = {},
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        WearText(
                                            text = "2. " + stringResource(R.string.adb_pairing_tutorial_content_enter_pairing_code),
                                            style = WearMaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                item {
                                    WearButton(
                                        onClick = { finish() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        WearText(stringResource(android.R.string.ok))
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (isTv) {
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    TvAdbPairingTutorialScreen(
                        onNavigateUp = { finish() },
                        notificationEnabled = notificationEnabled,
                        onOpenNotificationSettings = ::openNotificationSettings,
                        onOpenDeveloperOptions = ::openDeveloperOptions,
                        onFinish = { finish() }
                    )
                }
            } else {
                ShizukuExpressiveTheme {
                    PairingGuideScreen(
                        notificationEnabled = notificationEnabled,
                        onNavigateUp = { finish() },
                        onOpenNotificationSettings = ::openNotificationSettings,
                        onOpenDeveloperOptions = ::openDeveloperOptions,
                        onFinish = { finish() }
                    )
                }
            }
        }
    }

    private fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.notificationChannel)
        return nm.areNotificationsEnabled() &&
            (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    override fun onResume() {
        super.onResume()

        val newNotificationEnabled = isNotificationEnabled()
        if (newNotificationEnabled != notificationEnabled) {
            notificationEnabled = newNotificationEnabled

            if (newNotificationEnabled) {
                startPairingService()
            }
        }
    }

    private fun startPairingService() {
        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                val mode = getSystemService(AppOpsManager::class.java)
                    .noteOpNoThrow("android:start_foreground", android.os.Process.myUid(), packageName, null, null)
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(this, "OP_START_FOREGROUND is denied. What are you doing?", Toast.LENGTH_LONG).show()
                }
                startService(intent)
            }
        }
    }
}
