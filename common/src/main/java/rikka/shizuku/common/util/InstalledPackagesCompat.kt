package rikka.shizuku.common.util

import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kerneldroid.nightzuku.util.HiddenApiIntrospector
import kerneldroid.nightzuku.util.SmartLogEngine

object InstalledPackagesCompat {

    private const val TAG = "InstalledPackagesCompat"
    private const val ANDROID_13 = 33
    private const val PARCELED_LIST_SLICE = "android.content.pm.ParceledListSlice"
    private val reportedFailureOnce = AtomicBoolean(false)

    @JvmStatic
    fun getInstalledPackagesNoThrow(flags: Long, userId: Int): List<PackageInfo> {
        return try {
            getInstalledPackages(flags, userId)
        } catch (e: Throwable) {
            Log.w(TAG, "getInstalledPackages failed", e)
            recordSmartFailure(flags, userId, e)
            emptyList()
        }
    }

    private fun recordSmartFailure(flags: Long, userId: Int, error: Throwable) {
        if (!reportedFailureOnce.compareAndSet(false, true)) return
        try {
            val reflectionDump = HiddenApiIntrospector.inspectInterfaceMethods(
                serviceName = "package",
                interfaceName = "android.content.pm.IPackageManager",
                targetProxy = runCatching { getPackageManager() }.getOrNull(),
                methodKeywords = listOf("getInstalledPackages", "getInstalledApplications", "getPackageInfo")
            )
            val snap = SmartLogEngine.SmartLogSnapshot(
                source = "QPR_COMPAT",
                category = "IPACKAGEMANAGER_INSTALLED_PACKAGES",
                severity = "ERROR",
                summary = "getInstalledPackages(flags=$flags, userId=$userId) failed: ${error.javaClass.simpleName}: ${error.message}",
                details = buildString {
                    appendLine(reflectionDump)
                    appendLine()
                    appendLine("Stack trace:")
                    appendLine(SmartLogEngine.stackTraceToString(error))
                }
            )
            SmartLogEngine.appendSnapshotToFile(File(SmartLogEngine.SPOOL_TMP_PATH), snap)
            SmartLogEngine.appendSnapshotToFile(File(SmartLogEngine.SPOOL_SHELL_PATH), snap)
        } catch (_: Throwable) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun getInstalledPackages(flags: Long, userId: Int): List<PackageInfo> {
        try {
            val packageManager = getContextPackageManager()
            val method = packageManager.javaClass.getMethod("getInstalledPackagesAsUser", Int::class.java, Int::class.java)
            val result = invoke(method, packageManager, flags.toInt(), userId)
            return if (result == null) emptyList() else result as List<PackageInfo>
        } catch (ignored: NoSuchMethodException) {
        } catch (e: Exception) {
            Log.d(TAG, "getInstalledPackagesAsUser failed, falling back to hidden API", e)
        }

        val packageManager = getPackageManager()
        val method: Method
        val result: Any?

        if (Build.VERSION.SDK_INT >= ANDROID_13) {
            method = packageManager.javaClass.getMethod("getInstalledPackages", Long::class.java, Int::class.java)
            result = invoke(method, packageManager, flags, userId)
        } else {
            method = packageManager.javaClass.getMethod("getInstalledPackages", Int::class.java, Int::class.java)
            result = invoke(method, packageManager, flags.toInt(), userId)
        }

        if (result == null) {
            return emptyList()
        }

        val resultClassName = result.javaClass.name
        if (resultClassName.startsWith(PARCELED_LIST_SLICE) || resultClassName.contains("PackageInfoList")) {
            val list = result.javaClass.getMethod("getList").invoke(result)
            return if (list == null) emptyList() else list as List<PackageInfo>
        }

        val fallbackGetList = runCatching { result.javaClass.getMethod("getList") }.getOrNull()
        if (fallbackGetList != null) {
            recordSmartFailure(
                flags,
                userId,
                IllegalStateException("QPR return type drift detected: $resultClassName (recovered via getList())")
            )
            val list = fallbackGetList.invoke(result)
            return if (list == null) emptyList() else list as List<PackageInfo>
        }

        throw IllegalStateException("Unsupported getInstalledPackages return type: $resultClassName")
    }

    private fun getPackageManager(): Any {
        val servicesClass = Class.forName("rikka.hidden.compat.Services")
        val field = servicesClass.getDeclaredField("packageManager")
        field.setAccessible(true)
        val service = field.get(null)
        return service.javaClass.getMethod("get").invoke(service)!!
    }

    private fun getContextPackageManager(): Any {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        var activityThread: Any? = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        if (activityThread != null) {
            val application = activityThreadClass.getMethod("getApplication").invoke(activityThread)
            if (application != null) {
                return application.javaClass.getMethod("getPackageManager").invoke(application)!!
            }
        }

        activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread)
        return systemContext.javaClass.getMethod("getPackageManager").invoke(systemContext)!!
    }

    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? {
        try {
            return method.invoke(receiver, *args)
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            if (cause is ReflectiveOperationException) {
                throw cause
            }
            if (cause is RuntimeException) {
                throw cause
            }
            if (cause is Error) {
                throw cause
            }
            throw e
        }
    }
}
