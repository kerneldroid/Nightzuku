package rikka.shizuku.shell

import android.app.ActivityManagerNative
import android.app.IActivityManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ServiceManager
import android.system.Os
import android.text.TextUtils
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import rikka.hidden.compat.PackageManagerApis
import stub.dalvik.system.VMRuntimeHidden

object ShizukuShellLoader {

    private const val ACTION_REQUEST_BINDER_NIGHT = "kerneldroid.nightzuku.intent.action.REQUEST_BINDER"
    private const val REQUEST_BINDER_TIMEOUT_MS = 5_000L

    private var args: Array<String>? = null
    private var callingPackage: String? = null
    private var handler: Handler? = null
    private var timeoutRunnable: Runnable? = null
    private val binderHandled = AtomicBoolean(false)

    private val receiverBinder: Binder = object : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                if (!binderHandled.compareAndSet(false, true)) {
                    return true
                }
                val binder = data.readStrongBinder()
                val sourceDir = data.readString()

                if (binder != null && !sourceDir.isNullOrEmpty()) {
                    handler?.post {
                        timeoutRunnable?.let { handler?.removeCallbacks(it) }
                        timeoutRunnable = null
                        onBinderReceived(binder, sourceDir)
                    }
                } else {
                    abort("Server is not running")
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun requestForBinder() {
        val data = Bundle()
        data.putBinder("binder", receiverBinder)

        val intent = Intent(ACTION_REQUEST_BINDER_NIGHT)
            .setPackage(BuildConfig.MANAGER_APPLICATION_ID)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("data", data)

        val amBinder = ServiceManager.getService("activity")
        val am: IActivityManager = if (Build.VERSION.SDK_INT >= 26) {
            IActivityManager.Stub.asInterface(amBinder)
        } else {
            ActivityManagerNative.asInterface(amBinder)
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val method = findBroadcastMethod(am)
                    ?: throw RuntimeException("Cannot find broadcastIntentWithFeature on " + am.javaClass)
                val paramTypes = method.parameterTypes
                val callArgs = arrayOfNulls<Any>(paramTypes.size)
                callArgs[0] = null
                callArgs[1] = null
                callArgs[2] = intent
                var intIndex = 0
                var booleanIndex = 0
                for (i in 3 until paramTypes.size) {
                    val t = paramTypes[i]
                    if (t == Boolean::class.java) {
                        callArgs[i] = booleanIndex++ == 0
                    } else if (t == Int::class.java) {
                        callArgs[i] = if (isAppOpParameter(paramTypes, i, intIndex)) -1 else 0
                        intIndex++
                    } else if (t == Long::class.java) {
                        callArgs[i] = 0L
                    } else {
                        callArgs[i] = null
                    }
                }
                try {
                    method.invoke(am, *callArgs)
                } catch (e: ReflectiveOperationException) {
                    throw RuntimeException("broadcastIntentWithFeature invocation failed", e)
                }
            } else {
                am.broadcastIntent(
                    null, intent, null, null, 0, null, null,
                    null, -1, null, true, false, 0
                )
            }
        } catch (e: Throwable) {
            if ((Build.VERSION.SDK_INT != Build.VERSION_CODES.O && Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1)
                || e.message != "Calling application did not provide package name"
            ) {
                throw e
            }

            System.err.println("broadcastIntent fails on Android 8.0 or 8.1, fallback to startActivity")
            System.err.flush()

            val activityIntent = Intent.createChooser(
                Intent(ACTION_REQUEST_BINDER_NIGHT)
                    .setPackage(BuildConfig.MANAGER_APPLICATION_ID)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .putExtra("data", data),
                "Request binder from Nightzuku"
            )

            am.startActivityAsUser(
                null, callingPackage, activityIntent, null, null, null, 0, 0, null, null, Os.getuid() / 100000
            )
        }
    }

    private fun resolveVerifiedManagerSourceDir(reportedSourceDir: String): String {
        val verified = try {
            PackageManagerApis.getApplicationInfoNoThrow(BuildConfig.MANAGER_APPLICATION_ID, 0L, 0)?.sourceDir
        } catch (_: Throwable) {
            null
        }
        if (!verified.isNullOrEmpty()) {
            return verified
        }
        return reportedSourceDir
    }

    private fun onBinderReceived(binder: IBinder, reportedSourceDir: String) {
        val sourceDir = resolveVerifiedManagerSourceDir(reportedSourceDir)
        val slashIndex = sourceDir.lastIndexOf('/')
        if (slashIndex <= 0) {
            abort("Invalid manager APK path: $sourceDir")
            return
        }
        val base = sourceDir.substring(0, slashIndex)
        var librarySearchPath = base + "/lib/" + VMRuntimeHidden.getRuntime().vmInstructionSet()
        val systemLibrarySearchPath = System.getProperty("java.library.path")
        if (!TextUtils.isEmpty(systemLibrarySearchPath)) {
            librarySearchPath = librarySearchPath + File.pathSeparatorChar + systemLibrarySearchPath
        }

        try {
            val classLoader = BaseDexClassLoader(sourceDir, null, librarySearchPath, ClassLoader.getSystemClassLoader())
            val cls = classLoader.loadClass("moe.shizuku.manager.shell.Shell")
            cls.getDeclaredMethod("main", Array<String>::class.java, String::class.java, IBinder::class.java, Handler::class.java)
                .invoke(null, args, callingPackage, binder, handler)
        } catch (tr: ClassNotFoundException) {
            System.err.println("Class not found")
            System.err.println("Make sure you have Nightzuku installed")
            System.err.flush()
            System.exit(1)
        } catch (tr: Throwable) {
            tr.printStackTrace(System.err)
            System.err.flush()
            System.exit(1)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        ShizukuShellLoader.args = args

        val packageName: String?
        val pkgs = PackageManagerApis.getPackagesForUidNoThrow(Os.getuid())
        if (pkgs.isEmpty()) {
            abort("Cannot resolve packages for uid ${Os.getuid()}")
            return
        } else if (pkgs.size == 1) {
            packageName = pkgs[0]
        } else {
            packageName = System.getenv("RISH_APPLICATION_ID")
            if (TextUtils.isEmpty(packageName) || "PKG" == packageName) {
                abort("RISH_APPLICATION_ID is not set, set this environment variable to the id of current application (package name)")
                return
            }
            if (!pkgs.contains(packageName)) {
                abort("RISH_APPLICATION_ID ($packageName) does not belong to current uid ${Os.getuid()}")
                return
            }
        }

        ShizukuShellLoader.callingPackage = packageName

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        val mainHandler = Handler(Looper.getMainLooper())
        handler = mainHandler

        try {
            requestForBinder()
        } catch (tr: Throwable) {
            tr.printStackTrace(System.err)
            System.err.flush()
            System.exit(1)
        }

        val timeout = Runnable {
            if (!binderHandled.get()) {
                abort(
                    String.format(
                        "Request timeout. The connection between the current app (%1\$s) and Nightzuku app may be blocked by your system. " +
                            "Please disable all battery optimization features for both current app (%1\$s) and Nightzuku app.",
                        packageName
                    )
                )
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, REQUEST_BINDER_TIMEOUT_MS)

        Looper.loop()
        System.exit(0)
    }

    private fun abort(message: String) {
        System.err.println(message)
        System.err.flush()
        System.exit(1)
    }

    private fun findBroadcastMethod(am: Any): Method? {
        var best: Method? = null
        for (m in am.javaClass.methods) {
            if ("broadcastIntentWithFeature" == m.name) {
                if (best == null || m.parameterTypes.size > best.parameterTypes.size) {
                    best = m
                }
            }
        }
        return best
    }

    private fun isAppOpParameter(paramTypes: Array<Class<*>>, index: Int, intIndex: Int): Boolean {
        if (index + 1 < paramTypes.size && paramTypes[index + 1] == Bundle::class.java) {
            return true
        }
        return intIndex == 1 && index + 1 < paramTypes.size && paramTypes[index + 1] != String::class.java
    }
}
