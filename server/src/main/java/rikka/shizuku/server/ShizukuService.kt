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
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kerneldroid.nightzuku.api.LegacyParcelCodec
import moe.shizuku.api.BinderContainer
import kerneldroid.nightzuku.api.NightProtocol
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
    val nightEndpoint: NightServiceEndpoint
    private var heartbeatRunnable: Runnable? = null

    /** Tracks active permission prompts by (uid, pid, requestCode) -> elapsedRealtimeMs to block forged/expired replies. */
    private val openPrompts = ConcurrentHashMap<Triple<Int, Int, Int>, Long>()

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

        managerAppId = UserHandleCompat.getAppId(ai.uid)

        configManager = getConfigManager()
        clientManager = getClientManager()
        nightEndpoint = NightServiceEndpoint(this)

        ApkChangedObservers.start(ai.sourceDir, object : ApkChangedListener {

            override fun onApkChanged() {
                if (getManagerApplicationInfo() == null) {
                    Service.LOGGER.w("manager app is uninstalled in user 0, exiting...")
                    System.exit(ServerConstants.MANAGER_APP_NOT_FOUND)
                }
            }
        })

        BinderSender.register(this, nightEndpoint)

        if (configManager.getNightDogEnabled()) {
            applyNightDogState(true)
        }

        mainHandler.post {
            sendBinderToManager()
            sendBinderToClient()
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
        return UserHandleCompat.getAppId(callingUid) == managerAppId &&
            UserHandleCompat.getUserId(callingUid) == 0
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
        if (checkCallerManagerPermission(func, callingUid, callingPid)) {
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
        enforceManagerPermission("attachUserService")
        super.attachUserService(binder, options)
    }

    internal fun attachNightClient(
        callingUid: Int,
        callingPid: Int,
        bridgeApp: IShizukuApplication,
        packageName: String,
        protocolVersion: Int
    ): ClientRecord? {
        val isManager = ServerConstants.MANAGER_APPLICATION_ID == packageName &&
            UserHandleCompat.getUserId(callingUid) == 0

        var record = clientManager.findClient(callingUid, callingPid)
        if (record == null) {
            synchronized(this) {
                record = clientManager.addClient(
                    callingUid,
                    callingPid,
                    bridgeApp,
                    packageName,
                    ShizukuApiConstants.SERVER_VERSION
                )
            }
        }
        if (isManager) {
            try {
                Android17Compat.grantRuntimePermission(
                    ServerConstants.MANAGER_APPLICATION_ID,
                    Manifest.permission.WRITE_SECURE_SETTINGS,
                    UserHandleCompat.getUserId(callingUid)
                )
            } catch (e: RemoteException) {
                Service.LOGGER.w(e, "grant WRITE_SECURE_SETTINGS")
            }
        }
        return record
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
            Service.LOGGER.w("Request package $requestPackageName does not belong to uid $callingUid")
            throw SecurityException("Request package $requestPackageName does not belong to uid $callingUid")
        }

        val isManager = ServerConstants.MANAGER_APPLICATION_ID == requestPackageName &&
            UserHandleCompat.getUserId(callingUid) == 0

        if (clientManager.findClient(callingUid, callingPid) == null) {
            synchronized(this) {
                clientRecord = clientManager.addClient(callingUid, callingPid, application, requestPackageName, apiVersion)
            }
            if (clientRecord == null) {
                Service.LOGGER.w("Add client failed")
                return
            }
        } else {
            clientRecord = clientManager.findClient(callingUid, callingPid)
        }

        Service.LOGGER.d("attachApplication: %s %d %d", requestPackageName, callingUid, callingPid)

        var replyServerVersion = ShizukuApiConstants.SERVER_VERSION
        if (apiVersion == -1) {
            replyServerVersion = 12
        }

        if (!isManager) {
            val denied = configManager.find(callingUid)?.isDenied() == true
            try {
                LegacyParcelCodec.sendLegacyBindApplication(
                    application.asBinder(),
                    OsUtils.getUid(),
                    replyServerVersion,
                    ShizukuApiConstants.SERVER_PATCH_VERSION,
                    OsUtils.getSELinuxContext(),
                    clientRecord!!.allowed,
                    denied
                )
            } catch (e: Throwable) {
                Service.LOGGER.w(e, "attachApplication")
            }
        } else {
            try {
                Android17Compat.grantRuntimePermission(
                    ServerConstants.MANAGER_APPLICATION_ID,
                    Manifest.permission.WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(callingUid)
                )
            } catch (e: RemoteException) {
                Service.LOGGER.w(e, "grant WRITE_SECURE_SETTINGS")
            }
            val reply = Bundle()
            reply.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID, OsUtils.getUid())
            reply.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION, replyServerVersion)
            reply.putString(ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext())
            reply.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION)
            try {
                application.bindApplication(reply)
            } catch (e: Throwable) {
                Service.LOGGER.w(e, "attachApplication manager")
            }
        }
    }

    internal fun requestPermissionFromNightWire(callingUid: Int, callingPid: Int, requestCode: Int) {
        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return
        }
        val clientRecord = clientManager.requireClient(callingUid, callingPid)
        if (clientRecord.allowed) {
            val caps = TransactGuard.effectiveCapabilities(configManager.find(callingUid))
            if (!nightEndpoint.notifyRequestPermissionResult(callingUid, callingPid, requestCode, true, caps)) {
                clientRecord.dispatchRequestPermissionResult(requestCode, true)
            }
            return
        }
        val entry = configManager.find(callingUid)
        if (entry != null && entry.isDenied()) {
            if (!nightEndpoint.notifyRequestPermissionResult(callingUid, callingPid, requestCode, false, 0)) {
                clientRecord.dispatchRequestPermissionResult(requestCode, false)
            }
            return
        }
        showPermissionConfirmation(requestCode, clientRecord, callingUid, callingPid, UserHandleCompat.getUserId(callingUid))
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

        val now = SystemClock.elapsedRealtime()
        openPrompts.entries.removeIf { now - it.value > PROMPT_ANSWER_WINDOW_MS }
        openPrompts[Triple(callingUid, callingPid, requestCode)] = now

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
        enforceManagerPermission("dispatchPermissionConfirmationResult")

        if (data == null) {
            return
        }

        val openedAt = openPrompts.remove(Triple(requestUid, requestPid, requestCode))
        if (openedAt != null && SystemClock.elapsedRealtime() - openedAt > PROMPT_ANSWER_WINDOW_MS) {
            Service.LOGGER.w("Prompt expired for uid=%d pid=%d code=%d", requestUid, requestPid, requestCode)
            clientManager.findClient(requestUid, requestPid)?.dispatchRequestPermissionResult(requestCode, false)
            return
        }

        val allowed = data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED)
        val onetime = data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME)

        Service.LOGGER.i(
            "dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%s, onetime=%s",
            requestUid, requestPid, requestCode, allowed.toString(), onetime.toString()
        )

        val uidPackages = PackageManagerApis.getPackagesForUidNoThrow(requestUid).toMutableList()
        if (!onetime) {
            configManager.update(
                requestUid, uidPackages, ConfigManager.MASK_PERMISSION,
                if (allowed) ConfigManager.FLAG_ALLOWED else ConfigManager.FLAG_DENIED
            )
        }
        val entry = configManager.find(requestUid)
        val denied = entry?.isDenied() == true
        val caps = if (allowed) TransactGuard.effectiveCapabilities(entry) else 0

        val records = clientManager.findClients(requestUid)
        if (records.isEmpty()) {
            Service.LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid)
        } else {
            for (record in records) {
                record.allowed = allowed
                pushPermissionState(record, denied, caps)
                if (record.pid == requestPid) {
                    if (!nightEndpoint.notifyRequestPermissionResult(requestUid, requestPid, requestCode, allowed, caps)) {
                        record.dispatchRequestPermissionResult(requestCode, allowed)
                    }
                }
            }
        }

        if (!allowed) {
            for (pkg in uidPackages) {
                onPermissionRevoked(pkg)
            }
        }

        if (!onetime) {
            syncRuntimePermissionsForUid(requestUid, allowed)
        }
    }

    internal fun notifyCapabilitiesUpdated(uid: Int) {
        val entry = configManager.find(uid)
        val allowed = entry?.isAllowed() == true
        val denied = entry?.isDenied() == true
        val caps = TransactGuard.effectiveCapabilities(entry)
        for (record in clientManager.findClients(uid)) {
            record.allowed = allowed
            pushPermissionState(record, denied, caps)
        }
    }

    private fun pushPermissionState(record: ClientRecord, shouldShowRationale: Boolean, capabilities: Int) {
        if (nightEndpoint.notifyPermissionChanged(record.uid, record.pid, record.allowed, shouldShowRationale, capabilities)) {
            return
        }
        val client = record.client ?: return
        try {
            LegacyParcelCodec.sendLegacyBindApplication(
                client.asBinder(),
                OsUtils.getUid(),
                if (record.apiVersion == -1) 12 else ShizukuApiConstants.SERVER_VERSION,
                ShizukuApiConstants.SERVER_PATCH_VERSION,
                OsUtils.getSELinuxContext(),
                record.allowed,
                shouldShowRationale
            )
        } catch (t: Throwable) {
            Service.LOGGER.w(t, "Failed to push permission state to uid=%d pid=%d", record.uid, record.pid)
        }
    }

    private fun syncRuntimePermissionsForUid(uid: Int, allowed: Boolean) {
        val userId = UserHandleCompat.getUserId(uid)
        for (packageName in PackageManagerApis.getPackagesForUidNoThrow(uid)) {
            val pi = Android17Compat.getPackageInfo(
                packageName, PackageManager.GET_PERMISSIONS.toLong(), userId
            ) ?: continue
            val requestedPermissions = pi.requestedPermissions ?: continue

            if (requestedPermissions.contains(ServerConstants.PERMISSION)) {
                try {
                    if (allowed) {
                        Android17Compat.grantRuntimePermission(packageName, ServerConstants.PERMISSION, userId)
                    } else {
                        Android17Compat.revokeRuntimePermission(packageName, ServerConstants.PERMISSION, userId)
                    }
                } catch (t: Throwable) {
                    Service.LOGGER.w(t, "Failed to sync %s for %s", ServerConstants.PERMISSION, packageName)
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

    override fun getFlagsForUid(uid: Int, mask: Int): Int {
        enforceManagerPermission("getFlagsForUid")
        return getFlagsForUidInternal(uid, mask, true)
    }

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        enforceManagerPermission("updateFlagsForUid")

        val uidPackages = PackageManagerApis.getPackagesForUidNoThrow(uid).toMutableList()
        configManager.update(uid, uidPackages, mask, value)

        if ((mask and ConfigManager.MASK_PERMISSION) != 0) {
            val allowed = (value and ConfigManager.FLAG_ALLOWED) != 0
            val denied = (value and ConfigManager.FLAG_DENIED) != 0
            val caps = TransactGuard.effectiveCapabilities(configManager.find(uid))

            val records = clientManager.findClients(uid)
            for (record in records) {
                record.allowed = allowed
                pushPermissionState(record, denied, caps)
                if (!allowed) {
                    ActivityManagerApis.forceStopPackageNoThrow(record.packageName, UserHandleCompat.getUserId(record.uid))
                    onPermissionRevoked(record.packageName)
                }
            }

            if (!allowed && records.isEmpty()) {
                for (packageName in uidPackages) {
                    onPermissionRevoked(packageName)
                }
            }

            syncRuntimePermissionsForUid(uid, allowed)
        }
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
                } else if (ClientRouting.route(pi) != null) {
                    val metaData = applicationInfo.metaData
                    val wire = ClientRouting.route(pi)
                    if (wire == ClientRouting.Wire.NIGHTZUKU ||
                        (metaData != null && metaData.getBoolean("moe.shizuku.client.V3_SUPPORT", false))
                    ) {
                        list.add(pi)
                    }
                }
            }
        }
        return ParcelableListSlice(list)
    }

    public override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            enforceCallingPermission("transactRemote")
            TransactGuard.enforceCapability(
                configManager,
                callingUid,
                checkCallerManagerPermission("transactRemote", callingUid, callingPid),
                NightProtocol.CAP_BINDER_PROXY,
                "transactRemote"
            )
            val startPos = data.dataPosition()
            val targetBinder = data.readStrongBinder()
            TransactGuard.verifyTargetBinder(targetBinder, this, nightEndpoint)
            data.setDataPosition(startPos)
            transactRemote(data, reply, flags)
            return true
        } else if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            val replyParcel = reply!!
            if (!checkCallerManagerPermission("getApplications", Binder.getCallingUid(), Binder.getCallingPid())
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
            if (!checkCallerManagerPermission("setNightDogEnabled", Binder.getCallingUid(), Binder.getCallingPid())) {
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
            if (!checkCallerManagerPermission("getNightDogEnabled", Binder.getCallingUid(), Binder.getCallingPid())) {
                replyParcel.writeException(SecurityException("Permission denied"))
                return true
            }
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            replyParcel.writeNoException()
            replyParcel.writeInt(if (configManager.getNightDogEnabled()) 1 else 0)
            return true
        } else if (code == ServerConstants.BINDER_TRANSACTION_getNightDogStatus) {
            val replyParcel = reply!!
            if (!checkCallerManagerPermission("getNightDogStatus", Binder.getCallingUid(), Binder.getCallingPid())) {
                replyParcel.writeException(SecurityException("Permission denied"))
                return true
            }
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val statusBundle = Bundle().apply {
                putBoolean("enabled", configManager.getNightDogEnabled())
                putBoolean("started", NightDog.isStarted())
                putInt("boundServicesCount", NightDog.getBoundServiceCount())
                putInt("totalRebinds", NightDog.getTotalRebinds())
                putLong("beatCount", NightDog.getBeatCount())
                putLong("uptimeMs", NightDog.getUptimeMs())
                putStringArrayList("boundServices", ArrayList(NightDog.getBoundServiceNames()))
            }
            replyParcel.writeNoException()
            replyParcel.writeBundle(statusBundle)
            return true
        } else if (code == ServerConstants.BINDER_TRANSACTION_claimUserServiceLaunch) {
            val replyParcel = reply!!
            if (Binder.getCallingUid() != OsUtils.getUid()) {
                replyParcel.writeException(SecurityException("Permission denied"))
                return true
            }
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val token = data.readString()
            val claimed = token != null && getUserServiceManager().claimUserServiceLaunch(token, Binder.getCallingPid())
            replyParcel.writeNoException()
            replyParcel.writeInt(if (claimed) 1 else 0)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    fun sendBinderToClient() {
        for (userId in UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToClient(this, nightEndpoint, userId)
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
                                    BinderSender.register(this@ShizukuService, nightEndpoint)
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
        sendBinderToManager(this, nightEndpoint)
    }

    override fun dispatchPackageChanged(intent: Intent) {
    }

    override fun isHidden(uid: Int): Boolean {
        return false
    }

    companion object {

        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val PROMPT_ANSWER_WINDOW_MS = 5 * 60 * 1000L

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

        private fun sendBinderToClient(legacyBinder: Binder, nightBinder: Binder, userId: Int) {
            try {
                for (pi in InstalledPackagesCompat.getInstalledPackagesNoThrow(
                    PackageManager.GET_PERMISSIONS.toLong(), userId
                )) {
                    val wire = ClientRouting.route(pi) ?: continue
                    sendBinderToClientPackage(pi, wire, legacyBinder, nightBinder, userId)
                }
            } catch (tr: Throwable) {
                Service.LOGGER.e("exception when call getInstalledPackages", tr)
            }
        }

        @JvmStatic
        internal fun sendBinderToClientPackage(
            pi: PackageInfo,
            primaryWire: ClientRouting.Wire,
            legacyBinder: Binder,
            nightBinder: Binder,
            userId: Int
        ) {
            if (primaryWire == ClientRouting.Wire.NIGHTZUKU) {
                sendBinderToUserApp(ClientRouting.Wire.NIGHTZUKU, nightBinder, pi.packageName, userId, true)
                if (ClientRouting.supportsLegacyWire(pi)) {
                    sendBinderToUserApp(ClientRouting.Wire.SHIZUKU, legacyBinder, pi.packageName, userId, false)
                }
            } else {
                sendBinderToUserApp(ClientRouting.Wire.SHIZUKU, legacyBinder, pi.packageName, userId, true)
            }
        }

        private fun sendBinderToManager(legacyBinder: Binder, nightBinder: Binder) {
            for (userId in UserManagerApis.getUserIdsNoThrow()) {
                sendBinderToManager(legacyBinder, nightBinder, userId)
            }
        }

        @JvmStatic
        fun sendBinderToManager(legacyBinder: Binder, nightBinder: Binder, userId: Int) {
            sendBinderToUserApp(ClientRouting.Wire.NIGHTZUKU, nightBinder, ServerConstants.MANAGER_APPLICATION_ID, userId, true)
            sendBinderToUserApp(ClientRouting.Wire.SHIZUKU, legacyBinder, ServerConstants.MANAGER_APPLICATION_ID, userId, false)
        }

        @JvmStatic
        fun sendBinderToManager(binder: Binder, userId: Int) {
            sendBinderToUserApp(ClientRouting.Wire.SHIZUKU, binder, ServerConstants.MANAGER_APPLICATION_ID, userId, true)
        }

        @JvmStatic
        fun sendBinderToUserApp(binder: Binder, packageName: String, userId: Int) {
            sendBinderToUserApp(ClientRouting.Wire.SHIZUKU, binder, packageName, userId, true)
        }

        @JvmStatic
        fun sendBinderToUserApp(
            wire: ClientRouting.Wire,
            binder: Binder,
            packageName: String,
            userId: Int,
            retry: Boolean
        ) {
            try {
                DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000L, userId, 316, "shell")
                Service.LOGGER.v("Add %d:%s to power save temp whitelist for 30s", userId, packageName)
            } catch (tr: Throwable) {
                Service.LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName)
            }

            val name = packageName + ClientRouting.providerSuffix(wire)
            var provider: IContentProvider? = null
            val token: IBinder? = null

            try {
                val p = ActivityManagerApis.getContentProviderExternal(name, userId, token, name)
                provider = p
                if (p == null) {
                    Service.LOGGER.v("provider is null %s %d", name, userId)
                    return
                }
                if (!p.asBinder().pingBinder()) {
                    Service.LOGGER.e("provider is dead %s %d", name, userId)

                    if (retry) {
                        ActivityManagerApis.forceStopPackageNoThrow(packageName, userId)
                        Service.LOGGER.e("kill %s in user %d and try again", packageName, userId)
                        Thread.sleep(1000)
                        sendBinderToUserApp(wire, binder, packageName, userId, false)
                    }
                    return
                }

                if (!retry) {
                    Service.LOGGER.e("retry works")
                }

                val extra: Bundle
                val method: String
                if (wire == ClientRouting.Wire.NIGHTZUKU) {
                    extra = Bundle().apply {
                        putBinder(NightProtocol.EXTRA_BINDER, binder)
                    }
                    method = NightProtocol.METHOD_SEND_BINDER
                } else {
                    extra = Bundle().apply {
                        putParcelable(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY, BinderContainer(binder))
                    }
                    method = "sendBinder"
                }

                val reply = IContentProviderUtils.callCompat(p, null, name, method, null, extra)
                if (reply != null) {
                    Service.LOGGER.i("send binder (%s) to user app %s in user %d", wire.name, packageName, userId)
                } else {
                    Service.LOGGER.w("failed to send binder (%s) to user app %s in user %d", wire.name, packageName, userId)
                }
            } catch (tr: Throwable) {
                Service.LOGGER.e(tr, "failed send binder (%s) to user app %s in user %d", wire.name, packageName, userId)
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
