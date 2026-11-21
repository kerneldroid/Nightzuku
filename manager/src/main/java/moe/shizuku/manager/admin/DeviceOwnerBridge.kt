package moe.shizuku.manager.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import moe.shizuku.manager.AppConstants

/**
 * Hybrid offline privilege bridge backed by Android's `DevicePolicyManager`.
 *
 * When Nightzuku is provisioned as Device Owner, this layer survives cold reboots without Wi-Fi
 * or an active `adbd` session and covers package management, runtime permissions, scope
 * delegation, and USB ADB persistence (`Settings.Global.ADB_ENABLED`). Full UID 2000 shell
 * operations still rely on the Nightzuku server once wireless or USB ADB becomes available.
 */
object DeviceOwnerBridge {

    @JvmStatic
    fun isDeviceOwner(context: Context): Boolean {
        val appCtx = context.applicationContext ?: context
        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching { dpm.isDeviceOwnerApp(appCtx.packageName) }.getOrDefault(false)
    }

    @JvmStatic
    fun isAdminActive(context: Context): Boolean {
        val appCtx = context.applicationContext ?: context
        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching {
            dpm.isAdminActive(NightzukuDeviceAdminReceiver.getComponentName(appCtx))
        }.getOrDefault(false)
    }

    /**
     * Keeps USB debugging (`Settings.Global.ADB_ENABLED`) enabled via `DevicePolicyManagerService`
     * (UID 1000). Useful for offline USB-C ADB Host dongles (e.g., ESP32-S3) on Android 17+.
     * Note: AOSP does not whitelist `adb_wifi_enabled` in `setGlobalSetting()`.
     */
    @Suppress("DEPRECATION")
    @JvmStatic
    fun ensureUsbDebuggingEnabled(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val appCtx = context.applicationContext ?: context
        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = NightzukuDeviceAdminReceiver.getComponentName(appCtx)
        return runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1")
            true
        }.onFailure { t ->
            Log.w(AppConstants.TAG, "DeviceOwnerBridge.ensureUsbDebuggingEnabled failed", t)
        }.getOrDefault(false)
    }

    @JvmStatic
    fun setApplicationHidden(context: Context, packageName: String, hidden: Boolean): Boolean {
        if (!isDeviceOwner(context)) return false
        val appCtx = context.applicationContext ?: context
        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = NightzukuDeviceAdminReceiver.getComponentName(appCtx)
        return runCatching {
            dpm.setApplicationHidden(admin, packageName, hidden)
        }.onFailure { t ->
            Log.w(AppConstants.TAG, "setApplicationHidden($packageName, $hidden) failed", t)
        }.getOrDefault(false)
    }

    @JvmStatic
    fun setPackagesSuspended(
        context: Context,
        packageNames: Array<String>,
        suspended: Boolean
    ): Array<String> {
        if (!isDeviceOwner(context)) return packageNames
        val appCtx = context.applicationContext ?: context
        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java) ?: return packageNames
        val admin = NightzukuDeviceAdminReceiver.getComponentName(appCtx)
        return runCatching {
            dpm.setPackagesSuspended(admin, packageNames, suspended)
        }.onFailure { t ->
            Log.w(AppConstants.TAG, "setPackagesSuspended(suspended=$suspended) failed", t)
        }.getOrDefault(packageNames)
    }

    @JvmStatic
    fun setPermissionGrantState(
        context: Context,
        packageName: String,
        permission: String,
        grantState: Int
    ): Boolean {
        if (!isDeviceOwner(context)) return false
        val appCtx = context.applicationContext ?: context
        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = NightzukuDeviceAdminReceiver.getComponentName(appCtx)
        return runCatching {
            dpm.setPermissionGrantState(admin, packageName, permission, grantState)
        }.onFailure { t ->
            Log.w(AppConstants.TAG, "setPermissionGrantState($packageName, $permission) failed", t)
        }.getOrDefault(false)
    }

    @JvmStatic
    fun setDelegatedScopes(
        context: Context,
        delegatePackage: String,
        scopes: List<String>
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isDeviceOwner(context)) {
            return false
        }
        val appCtx = context.applicationContext ?: context
        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = NightzukuDeviceAdminReceiver.getComponentName(appCtx)
        return runCatching {
            dpm.setDelegatedScopes(admin, delegatePackage, scopes)
            true
        }.onFailure { t ->
            Log.w(AppConstants.TAG, "setDelegatedScopes($delegatePackage) failed", t)
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @JvmStatic
    fun clearApplicationUserData(
        context: Context,
        packageName: String,
        executor: Executor,
        listener: DevicePolicyManager.OnClearApplicationUserDataListener
    ): Boolean {
        if (!isDeviceOwner(context)) return false
        val appCtx = context.applicationContext ?: context
        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = NightzukuDeviceAdminReceiver.getComponentName(appCtx)
        return runCatching {
            dpm.clearApplicationUserData(admin, packageName, executor, listener)
            true
        }.onFailure { t ->
            Log.w(AppConstants.TAG, "clearApplicationUserData($packageName) failed", t)
        }.getOrDefault(false)
    }
}
