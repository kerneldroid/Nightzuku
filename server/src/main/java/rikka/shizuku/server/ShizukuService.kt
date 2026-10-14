package rikka.shizuku.server

import android.Manifest
import android.content.Context
import android.content.IContentProvider
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.ddm.DdmHandleAppName
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import android.os.ServiceManager
import moe.shizuku.api.BinderContainer
import moe.shizuku.common.util.BuildUtils
import moe.shizuku.common.util.OsUtils
import moe.shizuku.server.IShizukuApplication
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.DeviceIdleControllerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.parcelablelist.ParcelableListSlice
import rikka.rish.RishConfig
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.common.util.InstalledPackagesCompat
import rikka.shizuku.nightdog.NightDog
import rikka.shizuku.server.api.IContentProviderUtils
import rikka.shizuku.server.util.Android17Compat
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.UserHandleCompat

class ShizukuService : Service<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager>() {

    private val mainHandler: Handler = Handler(Looper.myLooper()!!)
    private val clientManager: ShizukuClientManager
    private val configManager: ShizukuConfigManager
    private val managerAppId: Int
    private var heartbeatRunnable: Runnable? = null

    init {
        HandlerUtil.setMainHandler(mainHandler)

        Service.LOGGER.i("starting server...")

        waitSystemService("package")
        waitSystemService(Context.ACTIVITY_SERVICE)
        waitSystemService(Context.USER_SERVICE)
        waitSystemService(Context.APP_OPS_SERVICE)

        val foundAi = getManagerApplicationInfo()
        if (foundAi == null) {
            System.exit(ServerConstants.MANAGER_APP_NOT_FOUND)
        }
        val ai = foundAi!!

        managerAppId = ai.uid

        configManager = getConfigManager()
        clientManager = getClientManager()

        ApkChangedObservers.start(ai.sourceDir, object : ApkChangedListener {

            override fun onApkChanged() {
                if (getManagerApplicationInfo() == null) {
                    Service.LOGGER.w("manager app is uninstalled in user 0, exiting...")
                    System.exit(ServerConstants.MANAGER_APP_NOT_FOUND)
                }
            }
        })

        BinderSender.register(this)

        if (configManager.getNightDogEnabled()) {
            applyNightDogState(true)
        }

        mainHandler.post {
            sendBinderToClient()
            sendBinderToManager()
        }
    }

    override fun onCreateUserServiceManager(): ShizukuUserServiceManager {
        return ShizukuUserServiceManager()
    }

    override fun onCreateClientManager(): ShizukuClientManager {
        return ShizukuClientManager(getConfigManager())
    }

    override fun onCreateConfigManager(): ShizukuConfigManager {
        return ShizukuConfigManager()
    }

    override fun checkCallerManagerPermission(func: String, callingUid: Int, callingPid: Int): Boolean {
        return UserHandleCompat.getAppId(callingUid) == managerAppId
    }

    private fun checkCallingPermission(): Int {
        return try {
            ActivityManagerApis.checkPermission(
                ServerConstants.PERMISSION,
                Binder.getCallingPid(),
                Binder.getCallingUid()
            )
        } catch (tr: Throwable) {
            Service.LOGGER.w(tr, "checkCallingPermission")
            PackageManager.PERMISSION_DENIED
        }
    }

    override fun checkCallerPermission(
        func: String,
        callingUid: Int,
        callingPid: Int,
        clientRecord: ClientRecord?
    ): Boolean {
        if (UserHandleCompat.getAppId(callingUid) == managerAppId) {
            return true
        }
        if (clientRecord == null && checkCallingPermission() == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return false
    }

    override fun exit() {
        enforceManagerPermission("exit")
        Service.LOGGER.i("exit")
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        NightDog.stop()
        System.exit(0)
    }

    override fun attachUserService(binder: IBinder, options: Bundle) {
        enforceManagerPermission("func")
        super.attachUserService(binder, options)
    }

    override fun attachApplication(application: IShizukuApplication?, args: Bundle?) {
        if (application == null || args == null) {
            return
        }

        val requestPackageName = args.getString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME) ?: return
        val apiVersion = args.getInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1)

        val callingPid = Binder.getCallingPid()
        val callingUid = Binder.getCallingUid()
        var clientRecord: ClientRecord? = null

        val packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid)
        if (!packages.contains(requestPackageName)) {
            Service.LOGGER.w("Request package " + requestPackageName + "does not belong to uid " + callingUid)
            throw SecurityException("Request package " + requestPackageName + "does not belong to uid " + callingUid)
        }

        val isManager = ServerConstants.MANAGER_APPLICATION_ID == requestPackageName

        if (clientManager.findClient(callingUid, callingPid) == null) {
            synchronized(this) {
                clientRecord = clientManager.addClient(callingUid, callingPid, application, requestPackageName, apiVersion)
            }
            if (clientRecord == null) {
                Service.LOGGER.w("Add client failed")
                return
            }
        }

        Service.LOGGER.d("attachApplication: %s %d %d", requestPackageName, callingUid, callingPid)

        var replyServerVersion = ShizukuApiConstants.SERVER_VERSION
        if (apiVersion == -1) {
            replyServerVersion = 12
        }

        val reply = Bundle()
        reply.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID, OsUtils.getUid())
        reply.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION, replyServerVersion)
        reply.putString(ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext())
        reply.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION)
        if (!isManager) {
            reply.putBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, clientRecord!!.allowed)
            reply.putBoolean(ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
        } else {
            try {
                Android17Compat.grantRuntimePermission(
                    ServerConstants.MANAGER_APPLICATION_ID,
                    Manifest.permission.WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(callingUid)
                )
            } catch (e: RemoteException) {
                Service.LOGGER.w(e, "grant WRITE_SECURE_SETTINGS")
            }
        }
        try {
            application.bindApplication(reply)
        } catch (e: Throwable) {
            Service.LOGGER.w(e, "attachApplication")
        }
    }

    override fun showPermissionConfirmation(
        requestCode: Int,
        clientRecord: ClientRecord,
        callingUid: Int,
        callingPid: Int,
        userId: Int
    ) {
        val ai = Android17Compat.getApplicationInfo(clientRecord.packageName, 0L, userId) ?: return

        val pi = Android17Compat.getPackageInfo(ServerConstants.MANAGER_APPLICATION_ID, 0L, userId)
        val userInfo = UserManagerApis.getUserInfo(userId)
        val isWorkProfileUser = if (BuildUtils.atLeast30()) {
            "android.os.usertype.profile.MANAGED" == userInfo.userType
        } else {
            (userInfo.flags and UserInfo.FLAG_MANAGED_PROFILE) != 0
        }
        if (pi == null && !isWorkProfileUser) {
            Service.LOGGER.w("Manager not found in non work profile user %d. Revoke permission", userId)
            clientRecord.dispatchRequestPermissionResult(requestCode, false)
            return
        }

        val intent = Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
            .setPackage(ServerConstants.MANAGER_APPLICATION_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            .putExtra("uid", callingUid)
            .putExtra("pid", callingPid)
            .putExtra("requestCode", requestCode)
            .putExtra("applicationInfo", ai)
        ActivityManagerApis.startActivityNoThrow(intent, null, if (isWorkProfileUser) 0 else userId)
    }

    override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle?) {
        if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
            Service.LOGGER.w("dispatchPermissionConfirmationResult called not from the manager package")
            return
        }

        if (data == null) {
            return
        }

        val allowed = data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED)
        val onetime = data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME)

        Service.LOGGER.i(
            "dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%s, onetime=%s",
            requestUid, requestPid, requestCode, allowed.toString(), onetime.toString()
        )

        val records = clientManager.findClients(requestUid)
        val packages = ArrayList<String>()
        if (records.isEmpty()) {
            Service.LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid)
        } else {
            for (record in records) {
                packages.add(record.packageName)
                record.allowed = allowed
                if (record.pid == requestPid) {
                    record.dispatchRequestPermissionResult(requestCode, allowed)
                }
            }
        }

        if (!onetime) {
            configManager.update(
                requestUid, packages, ConfigManager.MASK_PERMISSION,
                if (allowed) ConfigManager.FLAG_ALLOWED else ConfigManager.FLAG_DENIED
            )
        }

        if (!onetime && allowed) {
            val userId = UserHandleCompat.getUserId(requestUid)

            for (packageName in PackageManagerApis.getPackagesForUidNoThrow(requestUid)) {
                val pi = Android17Compat.getPackageInfo(
                    packageName, PackageManager.GET_PERMISSIONS.toLong(), userId
                ) ?: continue
                val requestedPermissions = pi.requestedPermissions ?: continue
                if (!requestedPermissions.contains(ServerConstants.PERMISSION)) {
                    continue
                }

                if (allowed) {
                    Android17Compat.grantRuntimePermission(packageName, ServerConstants.PERMISSION, userId)
                } else {
                    Android17Compat.revokeRuntimePermission(packageName, ServerConstants.PERMISSION, userId)
                }
            }
        }
    }

    private fun getFlagsForUidInternal(uid: Int, mask: Int, allowRuntimePermission: Boolean): Int {
        val entry = configManager.find(uid)
        if (entry != null) {
            return entry.flags and mask
        }

        if (allowRuntimePermission && (mask and ConfigManager.MASK_PERMISSION) != 0) {
            val userId = UserHandleCompat.getUserId(uid)
            for (packageName in PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                val pi = Android17Compat.getPackageInfo(
                    packageName, PackageManager.GET_PERMISSIONS.toLong(), userId
                ) ?: continue
                val requestedPermissions = pi.requestedPermissions ?: continue
                if (!requestedPermissions.contains(ServerConstants.PERMISSION)) {
                    continue
                }

                try {
                    if (Android17Compat.checkPermission(
                            ServerConstants.PERMISSION, packageName, userId
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        return ConfigManager.FLAG_ALLOWED
                    }
                } catch (e: Throwable) {
                    Service.LOGGER.w("getFlagsForUid")
                }
            }
        }
        return 0
    }

    private fun isManagerOrAuthorized(callingUid: Int): Boolean {
        val callingAppId = UserHandleCompat.getAppId(callingUid)
        if (callingAppId == managerAppId || callingUid == 2000 || callingUid == 0) {
            return true
        }
        val packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid)
        return packages.contains("com.termux")
    }

    override fun getFlagsForUid(uid: Int, mask: Int): Int {
        if (!isManagerOrAuthorized(Binder.getCallingUid())) {
            Service.LOGGER.w("getFlagsForUid is allowed to be called only from the manager or authorized clients")
            return 0
        }
        return getFlagsForUidInternal(uid, mask, true)
    }

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        if (!isManagerOrAuthorized(Binder.getCallingUid())) {
            Service.LOGGER.w("updateFlagsForUid is allowed to be called only from the manager or authorized clients")
            return
        }

        val userId = UserHandleCompat.getUserId(uid)

        if ((mask and ConfigManager.MASK_PERMISSION) != 0) {
            val allowed = (value and ConfigManager.FLAG_ALLOWED) != 0

            val records = clientManager.findClients(uid)
            for (record in records) {
                if (allowed) {
                    record.allowed = true
                } else {
                    record.allowed = false
                    ActivityManagerApis.forceStopPackageNoThrow(record.packageName, UserHandleCompat.getUserId(record.uid))
                    onPermissionRevoked(record.packageName)
                }
            }

            for (packageName in PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                val pi = Android17Compat.getPackageInfo(
                    packageName, PackageManager.GET_PERMISSIONS.toLong(), userId
                ) ?: continue
                val requestedPermissions = pi.requestedPermissions ?: continue
                if (!requestedPermissions.contains(ServerConstants.PERMISSION)) {
                    continue
                }

                if (allowed) {
                    Android17Compat.grantRuntimePermission(packageName, ServerConstants.PERMISSION, userId)
                } else {
                    Android17Compat.revokeRuntimePermission(packageName, ServerConstants.PERMISSION, userId)
                }
            }
        }

        configManager.update(uid, PackageManagerApis.getPackagesForUidNoThrow(uid), mask, value)
    }

    private fun onPermissionRevoked(packageName: String) {
        getUserServiceManager().removeUserServicesForPackage(packageName)
    }

    private fun getApplications(userId: Int): ParcelableListSlice<PackageInfo> {
        val list = ArrayList<PackageInfo>()
        val users = ArrayList<Int>()
        if (userId == -1) {
            users.addAll(UserManagerApis.getUserIdsNoThrow())
        } else {
            users.add(userId)
        }

        for (user in users) {
            for (pi in InstalledPackagesCompat.getInstalledPackagesNoThrow(
                (PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user
            )) {
                if (ServerConstants.MANAGER_APPLICATION_ID == pi.packageName) continue
                val applicationInfo = pi.applicationInfo ?: continue

                val uid = applicationInfo.uid
                var flags = 0
                val entry = configManager.find(uid)
                if (entry != null) {
                    val entryPackages = entry.packages
                    if (entryPackages != null && !entryPackages.contains(pi.packageName)) {
                        continue
                    }
                    flags = entry.flags and ConfigManager.MASK_PERMISSION
                }

                if (flags != 0) {
                    list.add(pi)
                } else {
                    val metaData = applicationInfo.metaData
                    val requestedPermissions = pi.requestedPermissions
                    if (metaData != null
                        && metaData.getBoolean("moe.shizuku.client.V3_SUPPORT", false)
                        && requestedPermissions != null
                        && requestedPermissions.contains(ServerConstants.PERMISSION)
                    ) {
                        list.add(pi)
                    }
                }
            }
        }
        return ParcelableListSlice(list)
    }

    public override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            val replyParcel = reply!!
            if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId
                && checkCallingPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                replyParcel.writeException(SecurityException("Permission denied"))
                return true
            }
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val userId = data.readInt()
            val result = getApplications(userId)
            replyParcel.writeNoException()
            result.writeToParcel(replyParcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            return true
        } else if (code == ServerConstants.BINDER_TRANSACTION_setNightDogEnabled) {
            val replyParcel = reply!!
            if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
                replyParcel.writeException(SecurityException("Permission denied"))
                return true
            }
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val enabled = data.readInt() != 0
            Service.LOGGER.i("setNightDogEnabled: %s", enabled)
            configManager.setNightDogEnabled(enabled)
            applyNightDogState(enabled)
            replyParcel.writeNoException()
            return true
        } else if (code == ServerConstants.BINDER_TRANSACTION_getNightDogEnabled) {
            val replyParcel = reply!!
            if (UserHandleCompat.getAppId(Binder.getCallingUid()) != managerAppId) {
                replyParcel.writeException(SecurityException("Permission denied"))
                return true
            }
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            replyParcel.writeNoException()
            replyParcel.writeInt(if (configManager.getNightDogEnabled()) 1 else 0)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    fun sendBinderToClient() {
        for (userId in UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToClient(this, userId)
        }
    }

    private fun applyNightDogState(enabled: Boolean) {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null

        if (enabled) {
            if (!NightDog.isStarted()) {
                Service.LOGGER.i("Starting NightDog watchdog...")
                NightDog.start(
                    timeoutMs = NightDog.DEFAULT_TIMEOUT_MS,
                    pollIntervalMs = NightDog.DEFAULT_POLL_INTERVAL_MS,
                    listener = object : NightDog.Listener {
                        override fun onServiceRecovered(name: String) {
                            Service.LOGGER.i("NightDog: system service '%s' recovered, refreshing bindings", name)
                            Android17Compat.invalidate(name)
                            mainHandler.post {
                                if (name == Context.ACTIVITY_SERVICE) {
                                    BinderSender.register(this@ShizukuService)
                                }
                                if (name == Context.ACTIVITY_SERVICE || name == "package" || name == Context.USER_SERVICE) {
                                    sendBinderToManager()
                                    sendBinderToClient()
                                }
                            }
                        }

                        override fun onFatalFault(reason: String) {
                            Service.LOGGER.e("NightDog detected fatal fault (%s), terminating server for recovery", reason)
                        }
                    }
                )
                Service.LOGGER.i("NightDog watchdog started")
            }

            val heartbeat = object : Runnable {
                override fun run() {
                    if (!NightDog.isStarted()) return
                    NightDog.beat()
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
            heartbeatRunnable = heartbeat
            NightDog.beat()
            mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
        } else {
            if (NightDog.isStarted()) {
                NightDog.stop()
                Service.LOGGER.i("NightDog watchdog stopped")
            }
        }
    }

    fun sendBinderToManager() {
        sendBinderToManager(this)
    }

    override fun dispatchPackageChanged(intent: Intent) {
    }

    override fun isHidden(uid: Int): Boolean {
        return false
    }

    companion object {

        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        @JvmStatic
        fun main(args: Array<String>) {
            DdmHandleAppName.setAppName("shizuku_server", 0)
            RishConfig.setLibraryPath(System.getProperty("shizuku.library.path"))

            Looper.prepareMainLooper()
            ShizukuService()
            Looper.loop()
        }

        private fun waitSystemService(name: String) {
            while (ServiceManager.getService(name) == null) {
                try {
                    Service.LOGGER.i("service " + name + " is not started, wait 1s.")
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Service.LOGGER.w(e.message, e)
                }
            }
        }

        @JvmStatic
        fun getManagerApplicationInfo(): ApplicationInfo? {
            return Android17Compat.getApplicationInfo(ServerConstants.MANAGER_APPLICATION_ID, 0L, 0)
        }

        private fun sendBinderToClient(binder: Binder, userId: Int) {
            try {
                for (pi in InstalledPackagesCompat.getInstalledPackagesNoThrow(
                    PackageManager.GET_PERMISSIONS.toLong(), userId
                )) {
                    val requestedPermissions = pi.requestedPermissions ?: continue

                    if (requestedPermissions.contains(ServerConstants.PERMISSION)) {
                        sendBinderToUserApp(binder, pi.packageName, userId)
                    }
                }
            } catch (tr: Throwable) {
                Service.LOGGER.e("exception when call getInstalledPackages", tr)
            }
        }

        private fun sendBinderToManager(binder: Binder) {
            for (userId in UserManagerApis.getUserIdsNoThrow()) {
                sendBinderToManager(binder, userId)
            }
        }

        fun sendBinderToManager(binder: Binder, userId: Int) {
            sendBinderToUserApp(binder, ServerConstants.MANAGER_APPLICATION_ID, userId)
        }

        @Deprecated("typo, use sendBinderToManager")
        fun sendBinderToManger(binder: Binder, userId: Int) {
            sendBinderToManager(binder, userId)
        }

        fun sendBinderToUserApp(binder: Binder, packageName: String, userId: Int) {
            sendBinderToUserApp(binder, packageName, userId, true)
        }

        fun sendBinderToUserApp(binder: Binder, packageName: String, userId: Int, retry: Boolean) {
            try {
                DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000L, userId, 316, "shell")
                Service.LOGGER.v("Add %d:%s to power save temp whitelist for 30s", userId, packageName)
            } catch (tr: Throwable) {
                Service.LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName)
            }

            val name = packageName + ".shizuku"
            var provider: IContentProvider? = null
            val token: IBinder? = null

            try {
                val p = ActivityManagerApis.getContentProviderExternal(name, userId, token, name)
                provider = p
                if (p == null) {
                    Service.LOGGER.e("provider is null %s %d", name, userId)
                    return
                }
                if (!p.asBinder().pingBinder()) {
                    Service.LOGGER.e("provider is dead %s %d", name, userId)

                    if (retry) {
                        ActivityManagerApis.forceStopPackageNoThrow(packageName, userId)
                        Service.LOGGER.e("kill %s in user %d and try again", packageName, userId)
                        Thread.sleep(1000)
                        sendBinderToUserApp(binder, packageName, userId, false)
                    }
                    return
                }

                if (!retry) {
                    Service.LOGGER.e("retry works")
                }

                val extra = Bundle()
                extra.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", BinderContainer(binder))

                val reply = IContentProviderUtils.callCompat(p, null, name, "sendBinder", null, extra)
                if (reply != null) {
                    Service.LOGGER.i("send binder to user app %s in user %d", packageName, userId)
                } else {
                    Service.LOGGER.w("failed to send binder to user app %s in user %d", packageName, userId)
                }
            } catch (tr: Throwable) {
                Service.LOGGER.e(tr, "failed send binder to user app %s in user %d", packageName, userId)
            } finally {
                if (provider != null) {
                    try {
                        ActivityManagerApis.removeContentProviderExternal(name, token)
                    } catch (tr: Throwable) {
                        Service.LOGGER.w(tr, "removeContentProviderExternal")
                    }
                }
            }
        }
    }
}
