package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import moe.shizuku.manager.R

/**
 * One-shot, user-armed bridge between the TV Settings pairing dialog and [AdbPairingService].
 *
 * The accessibility service is never left enabled on its own: it disables itself when the arm
 * window expires, when a code is consumed, or when it is started without an active request.
 */
object AdbPairingAutoDetect {

    private const val PREFS = "adb_pairing_auto"
    private const val KEY_ARMED_AT = "armed_at_elapsed"
    private const val ARM_TIMEOUT_MS = 2 * 60 * 1000L

    fun isArmed(context: Context): Boolean {
        val armedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_ARMED_AT, 0L)
        return armedAt > 0L && SystemClock.elapsedRealtime() - armedAt in 0..ARM_TIMEOUT_MS
    }

    /**
     * @return true when the accessibility service is already enabled and can observe the dialog.
     */
    fun arm(context: Context): Boolean {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ARMED_AT, SystemClock.elapsedRealtime())
            .apply()
        return isServiceEnabled(context)
    }

    fun disarm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ARMED_AT)
            .apply()
    }

    fun isServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    fun openAccessibilitySettings(context: Context) {
        Toast.makeText(context, R.string.adb_pairing_auto_enable_accessibility, Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openDevelopmentSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        runCatching { context.startActivity(intent) }
    }

    fun submit(context: Context, code: String, host: String?, port: Int?) {
        disarm(context)
        runCatching {
            context.startForegroundService(
                AdbPairingService.autoPairIntent(
                    context,
                    host?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                    port ?: -1,
                    code
                )
            )
        }.onFailure {
            Toast.makeText(context, R.string.notification_adb_pairing_failed_title, Toast.LENGTH_LONG).show()
        }
    }
}
