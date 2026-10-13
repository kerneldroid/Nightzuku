package moe.shizuku.starter

import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.Locale
import moe.shizuku.api.BinderContainer
import moe.shizuku.starter.util.IContentProviderCompat
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.UserService

object ServiceStarter {

    private const val TAG = "ShizukuServiceStarter"

    private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"

    @JvmField
    val DEBUG_ARGS: String = when {
        Build.VERSION.SDK_INT >= 30 -> "-Xcompiler-option" + " --debuggable" +
            " -XjdwpProvider:adbconnection" +
            " -XjdwpOptions:suspend=n,server=y"
        Build.VERSION.SDK_INT >= 28 -> "-Xcompiler-option" + " --debuggable" +
            " -XjdwpProvider:internal" +
            " -XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"
        else -> "-Xcompiler-option" + " --debuggable" +
            "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"
    }

    private var shizukuBinder: IBinder? = null

    private fun escapeShellArg(arg: String?): String {
        if (arg == null) return "''"
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    @JvmStatic
    fun commandForUserService(appProcess: String, managerApkPath: String, token: String, packageName: String, classname: String, processNameSuffix: String, callingUid: Int, debug: Boolean): String {
        val processName = String.format("%s:%s", packageName, processNameSuffix)
        return String.format(Locale.ENGLISH, "(CLASSPATH=%s %s%s /system/bin " +
            "--nice-name=%s moe.shizuku.starter.ServiceStarter " +
            "--token=%s --package=%s --class=%s --uid=%d%s)&",
            escapeShellArg(managerApkPath), appProcess, if (debug) " $DEBUG_ARGS" else "",
            escapeShellArg(processName),
            escapeShellArg(token), escapeShellArg(packageName), escapeShellArg(classname), callingUid,
            if (debug) " --debug-name=" + escapeShellArg(processName) else "")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        UserService.setTag(TAG)
        val result = UserService.create(args)

        if (result == null) {
            System.exit(1)
            return
        }

        val service = result.first
        val token = result.second

        if (!sendBinder(service, token)) {
            System.exit(1)
        }

        Looper.loop()
        System.exit(0)

        Log.i(TAG, "service exited")
    }

    private fun sendBinder(binder: IBinder, token: String): Boolean {
        return sendBinder(binder, token, true)
    }

    private fun sendBinder(binder: IBinder, token: String, retry: Boolean): Boolean {
        val packageName = "kerneldroid.nightzuku"
        val name = packageName + ".shizuku"
        val userId = 0
        var provider: IContentProvider? = null

        try {
            val p = ActivityManagerApis.getContentProviderExternal(name, userId, null, name)
            provider = p
            if (p == null) {
                Log.e(TAG, String.format("provider is null %s %d", name, userId))
                return false
            }
            if (!p.asBinder().pingBinder()) {
                Log.e(TAG, String.format("provider is dead %s %d", name, userId))

                if (retry) {
                    ActivityManagerApis.forceStopPackageNoThrow(packageName, userId)
                    Log.e(TAG, String.format("kill %s in user %d and try again", packageName, userId))
                    Thread.sleep(1000)
                    return sendBinder(binder, token, false)
                }
                return false
            }

            if (!retry) {
                Log.e(TAG, "retry works")
            }

            val extra = Bundle()
            extra.putParcelable(EXTRA_BINDER, BinderContainer(binder))
            extra.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token)

            val reply = IContentProviderCompat.call(p, null, null, name, "sendUserService", null, extra)

            if (reply != null) {
                reply.setClassLoader(BinderContainer::class.java.classLoader)

                Log.i(TAG, String.format("send binder to %s in user %d", packageName, userId))
                val container: BinderContainer? = reply.getParcelable(EXTRA_BINDER)

                val containerBinder = container?.binder
                if (containerBinder != null && containerBinder.pingBinder()) {
                    shizukuBinder = containerBinder
                    containerBinder.linkToDeath({
                        Log.i(TAG, "exiting...")
                        System.exit(0)
                    }, 0)
                    return true
                } else {
                    Log.w(TAG, "server binder not received")
                }
            }

            return false
        } catch (tr: Throwable) {
            Log.e(TAG, String.format("failed send binder to %s in user %d", packageName, userId), tr)
            return false
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, null)
                } catch (tr: Throwable) {
                    Log.w(TAG, "removeContentProviderExternal", tr)
                }
            }
        }
    }
}
