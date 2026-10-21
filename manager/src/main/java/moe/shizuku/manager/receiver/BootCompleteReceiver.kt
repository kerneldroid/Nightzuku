package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action
        ) {
            return
        }

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return
        if (!ShizukuSettings.isStartOnBootEnabled()) return

        val currentBootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)

        if (currentBootCount != -1 && ShizukuSettings.getLastHandledBootCount() == currentBootCount) {
            Log.d(AppConstants.TAG, "Boot start already succeeded for boot_count=$currentBootCount")
            return
        }

        if (!bootStartInFlight.compareAndSet(false, true)) {
            Log.d(AppConstants.TAG, "Boot start already in flight, skipping duplicate broadcast ${intent.action}")
            return
        }

        val launchMode = ShizukuSettings.getLastLaunchMode()
        if (launchMode == LaunchMethod.ROOT) {
            rootStart(currentBootCount)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            && launchMode == LaunchMethod.ADB
        ) {
            adbStart(context, currentBootCount)
        } else {
            bootStartInFlight.set(false)
            Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

    private fun rootStart(bootCount: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!Shell.getShell().isRoot) {
                    Shell.getCachedShell()?.close()
                    return@launch
                }

                val result = Shell.cmd(Starter.internalCommand).exec()
                if (result.isSuccess && bootCount != -1) {
                    ShizukuSettings.setLastHandledBootCount(bootCount)
                }
            } catch (t: Throwable) {
                Log.w(AppConstants.TAG, "rootStart on boot failed", t)
            } finally {
                bootStartInFlight.set(false)
                pending.finish()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun adbStart(context: Context, bootCount: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cr = context.contentResolver
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

                if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) {
                    return@launch
                }

                val latch = CountDownLatch(1)
                val handled = AtomicBoolean(false)
                val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { (host, port) ->
                    if (port <= 0 || !handled.compareAndSet(false, true)) return@AdbMdns
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
                            val key = AdbKey(keystore, "shizuku")
                            AdbClient(host, port, key, requireTls = true).use { client ->
                                client.connect()
                                client.shellCommand(Starter.internalCommand, null)
                            }
                            if (bootCount != -1) {
                                ShizukuSettings.setLastHandledBootCount(bootCount)
                            }
                        } catch (e: Exception) {
                            Log.w(AppConstants.TAG, "adbStart on boot failed", e)
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                adbMdns.start()
                try {
                    latch.await(ADB_BOOT_DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } finally {
                    adbMdns.stop()
                }
            } catch (t: Throwable) {
                Log.w(AppConstants.TAG, "adbStart setup failed", t)
            } finally {
                bootStartInFlight.set(false)
                pending.finish()
            }
        }
    }

    companion object {
        private const val ADB_BOOT_DISCOVERY_TIMEOUT_SECONDS = 10L
        private val bootStartInFlight = AtomicBoolean(false)
    }
}
