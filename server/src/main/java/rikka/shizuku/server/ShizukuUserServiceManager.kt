package rikka.shizuku.server

import android.content.pm.PackageInfo
import android.util.ArrayMap
import java.io.File
import moe.shizuku.starter.ServiceStarter
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.util.Android17Compat

class ShizukuUserServiceManager : UserServiceManager() {

    private val apkChangedListeners: MutableMap<UserServiceRecord, ApkChangedListener> = ArrayMap()

    override fun getUserServiceStartCmd(
        record: UserServiceRecord, key: String, token: String, packageName: String,
        classname: String, processNameSuffix: String, callingUid: Int, use32Bits: Boolean, debug: Boolean
    ): String {
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

    override fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
        super.onUserServiceRecordCreated(record, packageInfo)

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
        val listener = apkChangedListeners[record]
        if (listener != null) {
            ApkChangedObservers.stop(listener)
            apkChangedListeners.remove(record)
        }
    }
}
