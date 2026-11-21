package moe.shizuku.manager.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.AppConstants

/**
 * Optional opt-in Device Owner receiver for the hybrid offline privilege layer.
 *
 * When provisioned once via:
 *   `adb shell dpm set-device-owner kerneldroid.nightzuku/moe.shizuku.manager.admin.NightzukuDeviceAdminReceiver`
 * Device Owner privileges survive reboot offline with zero Wi-Fi or ADB dependency.
 */
class NightzukuDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(AppConstants.TAG, "NightzukuDeviceAdminReceiver enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(AppConstants.TAG, "NightzukuDeviceAdminReceiver disabled")
    }

    companion object {
        @JvmStatic
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext ?: context, NightzukuDeviceAdminReceiver::class.java)
    }
}
