package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.service.DirectBootStartService
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != action
            && Intent.ACTION_BOOT_COMPLETED != action
        ) {
            return
        }

        if (UserHandleCompat.myUserId() > 0) return
        if (!ShizukuSettings.isStartOnBootEnabled()) return

        val currentBootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)

        if (Shizuku.pingBinder()) {
            if (currentBootCount != -1) {
                ShizukuSettings.setLastHandledBootCount(currentBootCount)
            }
            if (Intent.ACTION_BOOT_COMPLETED == action) {
                NightDogClient.onUserUnlocked(context)
            }
            return
        }

        if (currentBootCount != -1 && ShizukuSettings.getLastHandledBootCount() == currentBootCount) {
            Log.d(AppConstants.TAG, "Boot start already succeeded for boot_count=$currentBootCount")
            if (Intent.ACTION_BOOT_COMPLETED == action) {
                NightDogClient.onUserUnlocked(context)
            }
            return
        }

        val launchMode = ShizukuSettings.resolveBootLaunchMethod(EnvironmentUtils.isRooted())
        if (launchMode == LaunchMethod.ROOT) {
            if (!bootStartInFlight.compareAndSet(false, true)) {
                Log.d(AppConstants.TAG, "Root boot start already in flight, skipping $action")
                return
            }
            rootStart(currentBootCount)
        } else if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0)
            && launchMode == LaunchMethod.ADB
        ) {
            if (Intent.ACTION_LOCKED_BOOT_COMPLETED == action) {
                Log.d(AppConstants.TAG, "Starting DirectBootStartService on LOCKED_BOOT_COMPLETED")
                DirectBootStartService.start(context, currentBootCount)
                return
            }

            // On ACTION_BOOT_COMPLETED: if the pre-unlock service is still running or recently sent
            // the start command for this boot, avoid a parallel restart and let it confirm or hand off.
            if (DirectBootStartService.isRunning()) {
                Log.d(AppConstants.TAG, "DirectBootStartService already running on BOOT_COMPLETED")
                return
            }
            if (currentBootCount != -1 && ShizukuSettings.getLastBootStartSentCount() == currentBootCount) {
                Log.d(AppConstants.TAG, "Start command already sent pre-unlock; confirming via DirectBootStartService")
                if (DirectBootStartService.start(context, currentBootCount)) {
                    return
                }
            }

            AdbBootStartScheduler.schedule(context, currentBootCount)
        } else {
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

    companion object {
        private val bootStartInFlight = AtomicBoolean(false)
    }
}
