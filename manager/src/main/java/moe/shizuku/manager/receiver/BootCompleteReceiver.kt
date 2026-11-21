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

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return
        if (!ShizukuSettings.isStartOnBootEnabled()) return

        val currentBootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)

        if (currentBootCount != -1 && ShizukuSettings.getLastHandledBootCount() == currentBootCount) {
            Log.d(AppConstants.TAG, "Boot start already succeeded for boot_count=$currentBootCount")
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
            // Wireless ADB requires credential-encrypted keystores and an associated Wi-Fi
            // network; wait for ACTION_BOOT_COMPLETED and hand off immediately to JobScheduler
            // instead of blocking inside goAsync().
            if (Intent.ACTION_BOOT_COMPLETED != action) {
                Log.d(AppConstants.TAG, "Deferring ADB boot start until ACTION_BOOT_COMPLETED")
                return
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
