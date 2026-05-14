package moe.shizuku.manager.utils

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ParceledListSlice
import android.os.RemoteException
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.hidden.compat.util.SystemServiceBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

object ShizukuSystemApis {

    init {
        SystemServiceBinder.setOnGetBinderListener {
            return@setOnGetBinderListener ShizukuBinderWrapper(it)
        }
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
            val listSlice: ParceledListSlice<PackageInfo>? =
                PackageManagerApis.getInstalledPackages(flags, userId)
            return if (listSlice != null) {
                listSlice.list
            } else ArrayList()
        } catch (e: NoSuchMethodError) {
            val binder = rikka.shizuku.ShizukuBinderWrapper(
                android.os.ServiceManager.getService("package")
            )
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            val pm = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            var foundList: ParceledListSlice<PackageInfo>? = null
            for (method in pm.javaClass.methods) {
                if (method.name == "getInstalledPackages") {
                    val paramTypes = method.parameterTypes
                    val args = Array<Any?>(paramTypes.size) { null }
                    for (i in paramTypes.indices) {
                        when (paramTypes[i]) {
                            Long::class.javaPrimitiveType -> args[i] = flags
                            Int::class.javaPrimitiveType -> args[i] = userId
                        }
                    }
                    val result = method.invoke(pm, *args)
                    if (result != null) {
                        foundList = result as ParceledListSlice<PackageInfo>
                        break
                    }
                }
            }
            return if (foundList != null) foundList.list else ArrayList()
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun checkPermission(permName: String, pkgName: String, userId: Int): Int {
        return if (!Shizuku.pingBinder()) {
            PackageManager.PERMISSION_DENIED
        } else try {
            PermissionManagerApis.checkPermission(permName, pkgName, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        if (!Shizuku.pingBinder()) {
            return
        }
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId)
        } catch (e: NoSuchMethodError) {
            try {
                val binder = rikka.shizuku.ShizukuBinderWrapper(
                    android.os.ServiceManager.getService("permissionmgr")
                )
                val stubClass = Class.forName("android.permission.IPermissionManager\$Stub")
                val pm = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                for (method in pm.javaClass.methods) {
                    if (method.name == "grantRuntimePermission") {
                        val paramTypes = method.parameterTypes
                        if (paramTypes.size >= 3 && paramTypes[0] == String::class.java && paramTypes[1] == String::class.java) {
                            val args = Array<Any?>(paramTypes.size) { null }
                            args[0] = packageName
                            args[1] = permissionName
                            if (paramTypes.size == 4 && paramTypes[2] == Int::class.javaPrimitiveType && paramTypes[3] == Int::class.javaPrimitiveType) {
                                args[2] = 0 // DEVICE_ID_DEFAULT
                                args[3] = userId
                            } else {
                                for (i in 2 until paramTypes.size) {
                                    if (paramTypes[i] == Int::class.javaPrimitiveType) args[i] = userId
                                }
                            }
                            method.invoke(pm, *args)
                            return
                        }
                    }
                }
            } catch (ex: Throwable) {
                // Ignore fallback failure
            }
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun revokeRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        if (!Shizuku.pingBinder()) {
            return
        }
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId)
        } catch (e: NoSuchMethodError) {
            try {
                val binder = rikka.shizuku.ShizukuBinderWrapper(
                    android.os.ServiceManager.getService("permissionmgr")
                )
                val stubClass = Class.forName("android.permission.IPermissionManager\$Stub")
                val pm = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                for (method in pm.javaClass.methods) {
                    if (method.name == "revokeRuntimePermission") {
                        val paramTypes = method.parameterTypes
                        if (paramTypes.size >= 3 && paramTypes[0] == String::class.java && paramTypes[1] == String::class.java) {
                            val args = Array<Any?>(paramTypes.size) { null }
                            args[0] = packageName
                            args[1] = permissionName
                            if (paramTypes.size == 5 && paramTypes[2] == Int::class.javaPrimitiveType && paramTypes[3] == Int::class.javaPrimitiveType && paramTypes[4] == String::class.java) {
                                args[2] = 0 // DEVICE_ID_DEFAULT
                                args[3] = userId
                                args[4] = "shizuku"
                            } else if (paramTypes.size == 4 && paramTypes[2] == Int::class.javaPrimitiveType && paramTypes[3] == Int::class.javaPrimitiveType) {
                                args[2] = 0 // DEVICE_ID_DEFAULT
                                args[3] = userId
                            } else {
                                for (i in 2 until paramTypes.size) {
                                    if (paramTypes[i] == Int::class.javaPrimitiveType) args[i] = userId
                                }
                            }
                            method.invoke(pm, *args)
                            return
                        }
                    }
                }
            } catch (ex: Throwable) {
                // Ignore fallback failure
            }
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }
}
