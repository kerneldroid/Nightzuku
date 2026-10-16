package rikka.shizuku.server

import android.content.pm.PackageInfo
import android.os.Process
import android.util.ArrayMap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import moe.shizuku.starter.ServiceStarter
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.util.Android17Compat

class ShizukuUserServiceManager : UserServiceManager() {

    private val apkChangedListeners: MutableMap<UserServiceRecord, ApkChangedListener> = ArrayMap()
    private val activeTokens = ConcurrentHashMap<String, UserServiceRecord>()
    private val claimedHostPids = ConcurrentHashMap<String, Int>()

    override fun getUserServiceStartCmd(
        record: UserServiceRecord, key: String, token: String, packageName: String,
        classname: String, processNameSuffix: String, callingUid: Int, use32Bits: Boolean, debug: Boolean
    ): String {
        activeTokens[token] = record
        var appProcess = "/system/bin/app_process"
        if (use32Bits && File("/system/bin/app_process32").exists()) {
            appProcess = "/system/bin/app_process32"
        }
        return ServiceStarter.commandForUserService(
            appProcess,
            ShizukuService.getManagerApplicationInfo()!!.sourceDir,
            token, packageName, classname, processNameSuffix, callingUid, debug
        )
    }

    /**
     * Validates that [token] belongs to a currently starting or active [UserServiceRecord]
     * before the privileged starter loads the target package's code.
     */
    fun claimUserServiceLaunch(token: String, hostPid: Int): Boolean {
        val record = activeTokens[token] ?: return false
        if (!record.starting && (record.service == null || !record.service.pingBinder())) {
            activeTokens.remove(token)
            return false
        }
        if (hostPid > 0) {
            claimedHostPids[token] = hostPid
        }
        return true
    }

    override fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
        super.onUserServiceRecordCreated(record, packageInfo)
        activeTokens[record.token] = record

        val packageName = packageInfo.packageName
        val listener = object : ApkChangedListener {

            override fun onApkChanged() {
                var newSourceDir: String? = null

                for (userId in UserManagerApis.getUserIdsNoThrow()) {
                    val pi = Android17Compat.getPackageInfo(packageName, 0L, userId)
                    val applicationInfo = pi?.applicationInfo
                    if (applicationInfo != null && applicationInfo.sourceDir != null) {
                        newSourceDir = applicationInfo.sourceDir
                        break
                    }
                }

                if (newSourceDir == null) {
                    UserServiceManager.LOGGER.v("remove record %s because package %s has been removed", record.token, packageName)
                    record.removeSelf()
                } else {
                    UserServiceManager.LOGGER.v("update apk listener for record %s since package %s is upgrading", record.token, packageName)
                    ApkChangedObservers.stop(this)
                    ApkChangedObservers.start(newSourceDir, this)
                }
            }
        }

        ApkChangedObservers.start(packageInfo.applicationInfo!!.sourceDir, listener)
        apkChangedListeners[record] = listener
    }

    override fun onUserServiceRecordRemoved(record: UserServiceRecord) {
        super.onUserServiceRecordRemoved(record)
        activeTokens.remove(record.token)
        val pid = claimedHostPids.remove(record.token)
        if (pid != null && pid > 1 && pid != Process.myPid()) {
            try {
                Process.killProcess(pid)
            } catch (_: Throwable) {
            }
        }
        val listener = apkChangedListeners[record]
        if (listener != null) {
            ApkChangedObservers.stop(listener)
            apkChangedListeners.remove(record)
        }
    }
}
