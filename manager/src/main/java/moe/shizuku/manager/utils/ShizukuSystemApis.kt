package moe.shizuku.manager.utils

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.hidden.compat.util.SystemServiceBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.common.util.InstalledPackagesCompat
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

@SuppressLint("BlockedPrivateApi", "PrivateApi", "DiscouragedPrivateApi")
object ShizukuSystemApis {

    private const val PERSISTENT_DEVICE_ID = "default:0"
    private const val DEVICE_ID_DEFAULT = 0
    private const val REVOKE_REASON = "shizuku"

    @Volatile
    private var sPackageManager: Any? = null
    @Volatile
    private var getInstalledPackagesMethod: Method? = null
    @Volatile
    private var getPackageInfoMethod: Method? = null
    @Volatile
    private var getApplicationInfoMethod: Method? = null
    @Volatile
    private var pmCheckPermissionMethod: Method? = null

    @Volatile
    private var sPermissionManager: Any? = null
    @Volatile
    private var grantRuntimePermissionMethod: Method? = null
    @Volatile
    private var revokeRuntimePermissionMethod: Method? = null
    @Volatile
    private var checkPermissionMethod: Method? = null

    init {
        SystemServiceBinder.setOnGetBinderListener {
            return@setOnGetBinderListener ShizukuBinderWrapper(it)
        }
    }

    @Synchronized
    private fun getPackageManager(): Any? {
        if (sPackageManager == null) {
            try {
                val packageManagerStub = Class.forName("android.content.pm.IPackageManager\$Stub")
                val rawBinder = ServiceManager.getService("package") ?: return null
                val pmBinder = ShizukuBinderWrapper(rawBinder)
                sPackageManager = packageManagerStub.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, pmBinder)
                sPackageManager?.let { pm ->
                    for (method in pm.javaClass.methods) {
                        when (method.name) {
                            "getInstalledPackages" -> {
                                if (method.parameterTypes.isNotEmpty() &&
                                    (method.parameterTypes[0] == Long::class.javaPrimitiveType ||
                                        method.parameterTypes[0] == Int::class.javaPrimitiveType)
                                ) {
                                    val current = getInstalledPackagesMethod
                                    if (current == null ||
                                        (method.parameterTypes[0] == Long::class.javaPrimitiveType &&
                                            current.parameterTypes[0] != Long::class.javaPrimitiveType) ||
                                        method.parameterTypes.size > current.parameterTypes.size
                                    ) {
                                        getInstalledPackagesMethod = method
                                    }
                                }
                            }
                            "getPackageInfo" -> {
                                if (method.parameterTypes.size >= 2 &&
                                    method.parameterTypes[0] == String::class.java &&
                                    (method.parameterTypes[1] == Long::class.javaPrimitiveType ||
                                        method.parameterTypes[1] == Int::class.javaPrimitiveType)
                                ) {
                                    val current = getPackageInfoMethod
                                    if (current == null ||
                                        (method.parameterTypes[1] == Long::class.javaPrimitiveType &&
                                            current.parameterTypes[1] != Long::class.javaPrimitiveType) ||
                                        method.parameterTypes.size > current.parameterTypes.size
                                    ) {
                                        getPackageInfoMethod = method
                                    }
                                }
                            }
                            "getApplicationInfo" -> {
                                if (method.parameterTypes.size >= 2 &&
                                    method.parameterTypes[0] == String::class.java &&
                                    (method.parameterTypes[1] == Long::class.javaPrimitiveType ||
                                        method.parameterTypes[1] == Int::class.javaPrimitiveType)
                                ) {
                                    val current = getApplicationInfoMethod
                                    if (current == null ||
                                        (method.parameterTypes[1] == Long::class.javaPrimitiveType &&
                                            current.parameterTypes[1] != Long::class.javaPrimitiveType) ||
                                        method.parameterTypes.size > current.parameterTypes.size
                                    ) {
                                        getApplicationInfoMethod = method
                                    }
                                }
                            }
                            "checkPermission" -> {
                                if (method.parameterTypes.size == 3 &&
                                    method.parameterTypes[0] == String::class.java &&
                                    method.parameterTypes[1] == String::class.java &&
                                    method.parameterTypes[2] == Int::class.javaPrimitiveType
                                ) {
                                    pmCheckPermissionMethod = method
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
            }
        }
        return sPackageManager
    }

    @Synchronized
    private fun getPermissionManager(): Any? {
        if (sPermissionManager == null) {
            try {
                val permManagerStub = Class.forName("android.permission.IPermissionManager\$Stub")
                val rawBinder = ServiceManager.getService("permissionmgr") ?: return null
                val permBinder = ShizukuBinderWrapper(rawBinder)
                sPermissionManager = permManagerStub.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, permBinder)
                sPermissionManager?.let { pm ->
                    for (method in pm.javaClass.methods) {
                        when (method.name) {
                            "grantRuntimePermission" -> {
                                if (method.parameterTypes.size >= 3 && method.parameterTypes[0] == String::class.java) {
                                    val current = grantRuntimePermissionMethod
                                    if (current == null || method.parameterTypes.size > current.parameterTypes.size) {
                                        grantRuntimePermissionMethod = method
                                    }
                                }
                            }
                            "revokeRuntimePermission" -> {
                                if (method.parameterTypes.size >= 3 && method.parameterTypes[0] == String::class.java) {
                                    val current = revokeRuntimePermissionMethod
                                    if (current == null || method.parameterTypes.size > current.parameterTypes.size) {
                                        revokeRuntimePermissionMethod = method
                                    }
                                }
                            }
                            "checkPermission" -> {
                                if (method.parameterTypes.size >= 3 && method.parameterTypes[0] == String::class.java) {
                                    val current = checkPermissionMethod
                                    if (current == null || method.parameterTypes.size > current.parameterTypes.size) {
                                        checkPermissionMethod = method
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
            }
        }
        return sPermissionManager
    }

    internal fun invokeCompat(method: Method?, target: Any?, vararg args: Any?): Any? {
        if (method == null || target == null) return null
        val paramTypes = method.parameterTypes
        val finalArgs = arrayOfNulls<Any>(paramTypes.size)

        if (paramTypes.size == args.size + 1) {
            val lastIdx = args.size - 1
            for (i in 0 until lastIdx) {
                finalArgs[i] = coerceArg(args[i], paramTypes[i])
            }
            finalArgs[lastIdx] = when (paramTypes[lastIdx]) {
                String::class.java -> PERSISTENT_DEVICE_ID
                Int::class.javaPrimitiveType, Int::class.javaObjectType -> DEVICE_ID_DEFAULT
                else -> null
            }
            finalArgs[lastIdx + 1] = coerceArg(args[lastIdx], paramTypes[lastIdx + 1])
        } else {
            val copyCount = minOf(args.size, paramTypes.size)
            for (i in 0 until copyCount) {
                finalArgs[i] = coerceArg(args[i], paramTypes[i])
            }
            for (i in copyCount until paramTypes.size) {
                finalArgs[i] = when (paramTypes[i]) {
                    Int::class.javaPrimitiveType, Int::class.javaObjectType ->
                        (args.lastOrNull() as? Int) ?: DEVICE_ID_DEFAULT
                    String::class.java -> PERSISTENT_DEVICE_ID
                    else -> null
                }
            }
        }

        return try {
            method.invoke(target, *finalArgs)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is SecurityException) throw cause
            if (cause is RemoteException) throw RuntimeException(cause.message, cause)
            if (cause is RuntimeException) throw cause
            throw RuntimeException(cause.message, cause)
        }
    }

    private fun coerceArg(value: Any?, targetType: Class<*>): Any? {
        if (value is Long && (targetType == Int::class.javaPrimitiveType || targetType == Int::class.javaObjectType)) {
            return value.toInt()
        }
        if (value is Int && (targetType == Long::class.javaPrimitiveType || targetType == Long::class.javaObjectType)) {
            return value.toLong()
        }
        return value
    }

    private val users = arrayListOf<UserInfoCompat>()

    private fun getUsers(): List<UserInfoCompat> {
        return if (!Shizuku.pingBinder()) {
            arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner"))
        } else try {
            val list = UserManagerApis.getUsers(true, true, true)
            val users: MutableList<UserInfoCompat> = ArrayList<UserInfoCompat>()
            for (ui in list) {
                users.add(UserInfoCompat(ui.id, ui.name))
            }
            return users
        } catch (tr: Throwable) {
            arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner"))
        }
    }

    fun getUsers(useCache: Boolean = true): List<UserInfoCompat> {
        synchronized(users) {
            if (!useCache || users.isEmpty()) {
                users.clear()
                users.addAll(getUsers())
            }
            return users
        }
    }

    fun getUserInfo(userId: Int): UserInfoCompat {
        return getUsers(useCache = true).firstOrNull { it.id == userId } ?: UserInfoCompat(
            UserHandleCompat.myUserId(),
            "Unknown"
        )
    }

    fun getInstalledPackages(flags: Long, userId: Int): List<PackageInfo> {
        if (!Shizuku.pingBinder()) {
            return ArrayList()
        }
        try {
            return InstalledPackagesCompat.getInstalledPackages(flags, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        } catch (tr: ReflectiveOperationException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun getPackageInfo(packageName: String, flags: Long, userId: Int): PackageInfo? {
        if (!Shizuku.pingBinder()) return null
        return try {
            PackageManagerApis.getPackageInfo(packageName, flags, userId)
        } catch (e: Throwable) {
            try {
                val pm = getPackageManager()
                invokeCompat(getPackageInfoMethod, pm, packageName, flags, userId) as? PackageInfo
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun getApplicationInfo(packageName: String, flags: Long, userId: Int): ApplicationInfo? {
        if (!Shizuku.pingBinder()) return null
        return try {
            PackageManagerApis.getApplicationInfo(packageName, flags, userId)
        } catch (e: Throwable) {
            try {
                val pm = getPackageManager()
                invokeCompat(getApplicationInfoMethod, pm, packageName, flags, userId) as? ApplicationInfo
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun checkPermission(permName: String, pkgName: String, userId: Int): Int {
        if (!Shizuku.pingBinder()) {
            return PackageManager.PERMISSION_DENIED
        }
        try {
            return PermissionManagerApis.checkPermission(permName, pkgName, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        } catch (_: Throwable) {
            // Fall through to reflective IPermissionManager / IPackageManager resolution for Android 15-17
        }

        try {
            val permMgr = getPermissionManager()
            val method = checkPermissionMethod
            if (permMgr != null && method != null) {
                val paramTypes = method.parameterTypes
                val res = if (paramTypes.size >= 4) {
                    // IPermissionManager.checkPermission(String packageName, String permissionName, String/int deviceId, int userId)
                    invokeCompat(method, permMgr, pkgName, permName, userId)
                } else {
                    // Legacy 3-arg checkPermission(String permName, String pkgName, int userId)
                    invokeCompat(method, permMgr, permName, pkgName, userId)
                }
                if (res is Int) return res
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Throwable) {
        }

        try {
            val pkgMgr = getPackageManager()
            val method = pmCheckPermissionMethod
            if (pkgMgr != null && method != null) {
                val res = invokeCompat(method, pkgMgr, permName, pkgName, userId)
                if (res is Int) return res
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Throwable) {
        }

        return PackageManager.PERMISSION_DENIED
    }

    fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        if (!Shizuku.pingBinder()) {
            return
        }
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId)
            return
        } catch (e: SecurityException) {
            throw e
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        } catch (_: Throwable) {
            // Fall through to reflective IPermissionManager invocation for Android 15-17
        }

        val pm = getPermissionManager() ?: return
        val method = grantRuntimePermissionMethod ?: return
        invokeCompat(method, pm, packageName, permissionName, userId)
    }

    fun revokeRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        if (!Shizuku.pingBinder()) {
            return
        }
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId)
            return
        } catch (e: SecurityException) {
            throw e
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        } catch (_: Throwable) {
            // Fall through to reflective IPermissionManager invocation for Android 15-17
        }

        val pm = getPermissionManager() ?: return
        val method = revokeRuntimePermissionMethod ?: return
        val paramTypes = method.parameterTypes
        try {
            when {
                paramTypes.size == 5 && paramTypes[2] == String::class.java && paramTypes[4] == String::class.java -> {
                    method.invoke(pm, packageName, permissionName, PERSISTENT_DEVICE_ID, userId, REVOKE_REASON)
                }
                paramTypes.size == 5 && paramTypes[2] == Int::class.javaPrimitiveType && paramTypes[4] == String::class.java -> {
                    method.invoke(pm, packageName, permissionName, DEVICE_ID_DEFAULT, userId, REVOKE_REASON)
                }
                paramTypes.size == 4 && paramTypes[2] == Int::class.javaPrimitiveType && paramTypes[3] == String::class.java -> {
                    method.invoke(pm, packageName, permissionName, userId, REVOKE_REASON)
                }
                else -> {
                    invokeCompat(method, pm, packageName, permissionName, userId)
                }
            }
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is SecurityException) throw cause
            if (cause is RemoteException) throw RuntimeException(cause.message, cause)
            if (cause is RuntimeException) throw cause
            throw RuntimeException(cause.message, cause)
        }
    }
}
