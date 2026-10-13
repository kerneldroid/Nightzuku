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
import rikka.hidden.compat.PackageManagerApis
import stub.dalvik.system.VMRuntimeHidden

object ShizukuShellLoader {

    private var args: Array<String>? = null
    private var callingPackage: String? = null
    private var handler: Handler? = null

    private val receiverBinder: Binder = object : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                val binder = data.readStrongBinder()

                val sourceDir = data.readString()
                if (binder != null) {
                    handler!!.post { onBinderReceived(binder, sourceDir!!) }
                } else {
                    System.err.println("Server is not running")
                    System.err.flush()
                    System.exit(1)
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun requestForBinder() {
        val data = Bundle()
        data.putBinder("binder", receiverBinder)

        val intent = Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
            .setPackage(BuildConfig.MANAGER_APPLICATION_ID)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("data", data)

        val amBinder = ServiceManager.getService("activity")
        val am: IActivityManager
        if (Build.VERSION.SDK_INT >= 26) {
            am = IActivityManager.Stub.asInterface(amBinder)
        } else {
            am = ActivityManagerNative.asInterface(amBinder)
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val method = findBroadcastMethod(am)
                    ?: throw RuntimeException("Cannot find broadcastIntentWithFeature on " + am.javaClass)
                val paramTypes = method.parameterTypes
                val args = arrayOfNulls<Any>(paramTypes.size)
                args[0] = null
                args[1] = null
                args[2] = intent
                var intIndex = 0
                var booleanIndex = 0
                for (i in 3 until paramTypes.size) {
                    val t = paramTypes[i]
                    if (t == Boolean::class.java) {
                        args[i] = booleanIndex++ == 0
                    } else if (t == Int::class.java) {
                        args[i] = if (isAppOpParameter(paramTypes, i, intIndex)) -1 else 0
                        intIndex++
                    } else if (t == Long::class.java) {
                        args[i] = 0L
                    } else {
                        args[i] = null
                    }
                }
                try {
                    method.invoke(am, *args)
                } catch (e: ReflectiveOperationException) {
                    throw RuntimeException("broadcastIntentWithFeature invocation failed", e)
                }
            } else {
                am.broadcastIntent(null, intent, null, null, 0, null, null,
                    null, -1, null, true, false, 0)
            }
        } catch (e: Throwable) {
            if ((Build.VERSION.SDK_INT != Build.VERSION_CODES.O && Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1)
                || e.message != "Calling application did not provide package name") {
                throw e
            }

            System.err.println("broadcastIntent fails on Android 8.0 or 8.1, fallback to startActivity")
            System.err.flush()

            val activityIntent = Intent.createChooser(
                Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .putExtra("data", data),
                "Request binder from Shizuku"
            )

            am.startActivityAsUser(null, callingPackage, activityIntent, null, null, null, 0, 0, null, null, Os.getuid() / 100000)
        }
    }

    private fun onBinderReceived(binder: IBinder, sourceDir: String) {
        val base = sourceDir.substring(0, sourceDir.lastIndexOf('/'))
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
            System.err.println("Make sure you have Shizuku v12.0.0 or above installed")
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
        val pkg = PackageManagerApis.getPackagesForUidNoThrow(Os.getuid())
        if (pkg.size == 1) {
            packageName = pkg[0]
        } else {
            packageName = System.getenv("RISH_APPLICATION_ID")
            if (TextUtils.isEmpty(packageName) || "PKG" == packageName) {
                abort("RISH_APPLICATION_ID is not set, set this environment variable to the id of current application (package name)")
                System.exit(1)
            }
        }

        ShizukuShellLoader.callingPackage = packageName

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        handler = Handler(Looper.getMainLooper())

        try {
            requestForBinder()
        } catch (tr: Throwable) {
            tr.printStackTrace(System.err)
            System.err.flush()
            System.exit(1)
        }

        handler!!.postDelayed({
            abort(
                String.format(
                    "Request timeout. The connection between the current app (%1\$s) and Shizuku app may be blocked by your system. " +
                        "Please disable all battery optimization features for both current app (%1\$s) and Shizuku app.",
                    packageName
                )
            )
        }, 5000)

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
        return intIndex == 1 && index + 1 < paramTypes.size && index + 1 < paramTypes.size && paramTypes[index + 1] != String::class.java
    }
}
