package rikka.shizuku.server

import android.app.ActivityManagerHidden
import android.app.ActivityManagerHidden.UID_OBSERVER_ACTIVE
import android.app.ActivityManagerHidden.UID_OBSERVER_CACHED
import android.app.ActivityManagerHidden.UID_OBSERVER_GONE
import android.content.pm.PackageManager
import android.os.Build
import android.os.RemoteException
import android.text.TextUtils
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.adapter.ProcessObserverAdapter
import rikka.hidden.compat.adapter.UidObserverAdapter
import rikka.shizuku.server.util.Android17Compat
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.UserHandleCompat

object BinderSender {

    private val logger = Logger("BinderSender")

    @Volatile
    private var shizukuService: ShizukuService? = null

    @Volatile
    private var nightEndpoint: NightServiceEndpoint? = null

    private val dispatchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "night-binder-sender").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }

    private class ProcessObserver : ProcessObserverAdapter() {

        @Throws(RemoteException::class)
        override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foregroundActivities: Boolean) {
            logger.d(
                "onForegroundActivitiesChanged: pid=%d, uid=%d, foregroundActivities=%s",
                pid,
                uid,
                foregroundActivities.toString()
            )

            synchronized(startedPids) {
                if (!foregroundActivities || !startedPids.add(pid)) {
                    return
                }
            }

            dispatchAsync(uid, pid)
        }

        override fun onProcessDied(pid: Int, uid: Int) {
            logger.d("onProcessDied: pid=%d, uid=%d", pid, uid)

            synchronized(startedPids) {
                if (startedPids.remove(pid)) {
                    logger.v("Pid %d dead", pid)
                }
            }
        }

        @Throws(RemoteException::class)
        override fun onProcessStateChanged(pid: Int, uid: Int, procState: Int) {
            logger.d("onProcessStateChanged: pid=%d, uid=%d, procState=%d", pid, uid, procState)

            synchronized(startedPids) {
                if (!startedPids.add(pid)) {
                    return
                }
            }

            dispatchAsync(uid, pid)
        }

        companion object {
            private val startedPids = HashSet<Int>()

            fun clear() {
                synchronized(startedPids) {
                    startedPids.clear()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private class UidObserver : UidObserverAdapter() {

        @Throws(RemoteException::class)
        override fun onUidActive(uid: Int) {
            logger.d("onUidActive: uid=%d", uid)
            uidStarts(uid)
        }

        @Throws(RemoteException::class)
        override fun onUidCachedChanged(uid: Int, cached: Boolean) {
            logger.d("onUidCachedChanged: uid=%d, cached=%s", uid, cached.toString())

            if (!cached) {
                uidStarts(uid)
            }
        }

        override fun onUidGone(uid: Int, disabled: Boolean) {
            logger.d("onUidGone: uid=%d, disabled=%s", uid, disabled.toString())
            uidGone(uid)
        }

        @Throws(RemoteException::class)
        private fun uidStarts(uid: Int) {
            synchronized(startedUids) {
                if (!startedUids.add(uid)) {
                    logger.v("Uid %d already starts", uid)
                    return
                }
                logger.v("Uid %d starts", uid)
            }

            dispatchAsync(uid, -1)
        }

        private fun uidGone(uid: Int) {
            synchronized(startedUids) {
                if (startedUids.remove(uid)) {
                    logger.v("Uid %d dead", uid)
                }
            }
        }

        companion object {
            private val startedUids = HashSet<Int>()

            fun clear() {
                synchronized(startedUids) {
                    startedUids.clear()
                }
            }
        }
    }

    private fun dispatchAsync(uid: Int, pid: Int) {
        dispatchExecutor.execute {
            try {
                sendBinder(uid, pid)
            } catch (t: Throwable) {
                logger.w(t, "Failed to dispatch binder to uid=%d pid=%d", uid, pid)
            }
        }
    }

    @Throws(RemoteException::class)
    private fun sendBinder(uid: Int, pid: Int) {
        val service = shizukuService ?: return
        val night = nightEndpoint ?: return
        val packages = PackageManagerApis.getPackagesForUidNoThrow(uid)
        if (packages.isEmpty()) {
            return
        }

        logger.d("sendBinder to uid %d: packages=%s", uid, TextUtils.join(", ", packages))

        val userId = UserHandleCompat.getUserId(uid)
        var sentManager = false
        for (packageName in packages) {
            val packageInfo = Android17Compat.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS.toLong(),
                userId
            ) ?: continue
            val requestedPermissions = packageInfo.requestedPermissions ?: continue

            if (requestedPermissions.contains(ServerConstants.MANAGER_PERMISSION)) {
                val granted = if (pid == -1) {
                    Android17Compat.checkPermission(ServerConstants.MANAGER_PERMISSION, uid) == PackageManager.PERMISSION_GRANTED
                } else {
                    ActivityManagerApis.checkPermission(ServerConstants.MANAGER_PERMISSION, pid, uid) == PackageManager.PERMISSION_GRANTED
                }

                if (granted && userId == 0 && !sentManager) {
                    ShizukuService.sendBinderToManager(service, night, userId)
                    sentManager = true
                }
            }

            val wire = ClientRouting.route(packageInfo)
            if (wire != null) {
                ShizukuService.sendBinderToClientPackage(packageInfo, wire, service, night, userId)
            }
        }
    }

    @JvmStatic
    fun register(shizukuService: ShizukuService, nightEndpoint: NightServiceEndpoint = shizukuService.nightEndpoint) {
        this.shizukuService = shizukuService
        this.nightEndpoint = nightEndpoint
        ProcessObserver.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            UidObserver.clear()
        }

        try {
            ActivityManagerApis.registerProcessObserver(ProcessObserver())
        } catch (tr: Throwable) {
            logger.e(tr, "registerProcessObserver")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var flags = UID_OBSERVER_GONE or UID_OBSERVER_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = flags or UID_OBSERVER_CACHED
            }
            try {
                ActivityManagerApis.registerUidObserver(
                    UidObserver(),
                    flags,
                    ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
                    null
                )
            } catch (tr: Throwable) {
                logger.e(tr, "registerUidObserver")
            }
        }
    }
}
