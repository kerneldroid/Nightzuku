package moe.shizuku.starter

import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightProtocol
import kerneldroid.nightzuku.util.PackageSignatureVerifier
import moe.shizuku.starter.util.IContentProviderCompat
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.UserService

object ServiceStarter {

    private const val TAG = "ShizukuServiceStarter"
    private const val MANAGER_PACKAGE = "kerneldroid.nightzuku"
    private const val LAUNCH_TOKEN_TIMEOUT_MS = 5_000L
    private const val LAUNCH_TOKEN_RETRY_MS = 250L

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
    @JvmOverloads
    fun commandForUserService(
        appProcess: String,
        managerApkPath: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        debug: Boolean,
        expectedSigners: Set<String> = emptySet()
    ): String {
        val processName = String.format("%s:%s", packageName, processNameSuffix)
        val signersArg = if (expectedSigners.isNotEmpty()) {
            " --signers=" + escapeShellArg(expectedSigners.joinToString(","))
        } else {
            ""
        }
        return String.format(
            Locale.ENGLISH,
            "(CLASSPATH=%s %s%s /system/bin " +
                "--nice-name=%s moe.shizuku.starter.ServiceStarter " +
                "--token=%s --package=%s --class=%s --uid=%d%s%s)&",
            escapeShellArg(managerApkPath),
            appProcess,
            if (debug) " $DEBUG_ARGS" else "",
            escapeShellArg(processName),
            escapeShellArg(token),
            escapeShellArg(packageName),
            escapeShellArg(classname),
            callingUid,
            signersArg,
            if (debug) " --debug-name=" + escapeShellArg(processName) else ""
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        var launchToken: String? = null
        var targetPackage: String? = null
        var targetUid: Int = -1
        var expectedSigners: Set<String> = emptySet()

        for (arg in args) {
            when {
                arg.startsWith("--token=") -> launchToken = arg.substring("--token=".length)
                arg.startsWith("--package=") -> targetPackage = arg.substring("--package=".length)
                arg.startsWith("--uid=") -> targetUid = arg.substring("--uid=".length).toIntOrNull() ?: -1
                arg.startsWith("--signers=") -> {
                    expectedSigners = arg.substring("--signers=".length)
                        .split(',')
                        .map { it.trim().lowercase(Locale.ROOT) }
                        .filter { it.isNotEmpty() }
                        .toSet()
                }
            }
        }

        // Verify that the launch token is still live on the server before loading client APK code
        if (!isLaunchTokenLive(launchToken)) {
            Log.w(TAG, "UserService launch token is not active, aborting")
            System.exit(1)
            return
        }

        // Verify the client APK's UID and cryptographic signing identity right before loading its code
        if (!verifyTargetPackageIdentity(targetPackage, targetUid, expectedSigners)) {
            Log.e(TAG, "UserService target package failed identity verification, aborting")
            System.exit(1)
            return
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

    private fun verifyTargetPackageIdentity(
        packageName: String?,
        callingUid: Int,
        expectedSigners: Set<String>
    ): Boolean {
        if (packageName.isNullOrEmpty() || callingUid <= 0) return false
        val userId = callingUid / 100000
        val expectedAppId = callingUid % 100000
        return try {
            val pi = PackageManagerApis.getPackageInfoNoThrow(
                packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            ) ?: return false
            val snapshot = PackageSignatureVerifier.extractSnapshot(pi, userId) ?: return false
            if (snapshot.appId != expectedAppId) {
                Log.e(TAG, "Target package $packageName appId=${snapshot.appId} does not match expected $expectedAppId")
                return false
            }
            if (expectedSigners.isNotEmpty()) {
                val baseline = PackageSignatureVerifier.Baseline(packageName, expectedAppId, expectedSigners)
                if (!baseline.matches(snapshot)) {
                    Log.e(TAG, "Target package $packageName signing certificate mismatch")
                    return false
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to verify target package identity for $packageName", t)
            false
        }
    }

    private fun isLaunchTokenLive(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val authority = MANAGER_PACKAGE + NightProtocol.PROVIDER_SUFFIX
        val started = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - started < LAUNCH_TOKEN_TIMEOUT_MS) {
            var provider: IContentProvider? = null
            try {
                provider = ActivityManagerApis.getContentProviderExternal(authority, 0, null, authority)
                if (provider != null) {
                    val reply = IContentProviderCompat.call(
                        provider,
                        null,
                        null,
                        authority,
                        NightProtocol.METHOD_GET_BINDER,
                        null,
                        Bundle()
                    )
                    val serverBinder = LegacyParcelCodec.extractBinderFromBundle(reply)
                    if (serverBinder != null && serverBinder.pingBinder()) {
                        return queryTokenLive(serverBinder, token)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to query launch token", t)
            } finally {
                if (provider != null) {
                    try {
                        ActivityManagerApis.removeContentProviderExternal(authority, null)
                    } catch (_: Throwable) {
                    }
                }
            }
            try {
                Thread.sleep(LAUNCH_TOKEN_RETRY_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        return false
    }

    private fun queryTokenLive(serverBinder: IBinder, token: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val descriptor = serverBinder.interfaceDescriptor ?: NightProtocol.DESCRIPTOR
            data.writeInterfaceToken(descriptor)
            data.writeString(token)
            val txCode = if (descriptor == NightProtocol.DESCRIPTOR) {
                NightProtocol.TRANSACTION_CLAIM_USER_SERVICE
            } else {
                10004 /* ServerConstants.BINDER_TRANSACTION_claimUserServiceLaunch */
            }
            if (!serverBinder.transact(txCode, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } catch (t: Throwable) {
            Log.w(TAG, "queryTokenLive failed", t)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun sendBinder(binder: IBinder, token: String, retry: Boolean = true): Boolean {
        val name = MANAGER_PACKAGE + NightProtocol.PROVIDER_SUFFIX
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
                    ActivityManagerApis.forceStopPackageNoThrow(MANAGER_PACKAGE, userId)
                    Log.e(TAG, String.format("kill %s in user %d and try again", MANAGER_PACKAGE, userId))
                    Thread.sleep(1000)
                    return sendBinder(binder, token, false)
                }
                return false
            }

            if (!retry) {
                Log.e(TAG, "retry works")
            }

            val extra = Bundle().apply {
                putBinder(NightProtocol.EXTRA_BINDER, binder)
                putString(NightProtocol.EXTRA_USER_SERVICE_TOKEN, token)
                putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token)
            }

            val reply = IContentProviderCompat.call(
                p,
                null,
                null,
                name,
                NightProtocol.METHOD_SEND_USER_SERVICE,
                null,
                extra
            )

            if (reply != null) {
                Log.i(TAG, String.format("send binder to %s in user %d", MANAGER_PACKAGE, userId))
                val serverBinder = LegacyParcelCodec.extractBinderFromBundle(reply)

                if (serverBinder != null && serverBinder.pingBinder()) {
                    shizukuBinder = serverBinder
                    serverBinder.linkToDeath({
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
            Log.e(TAG, String.format("failed send binder to %s in user %d", MANAGER_PACKAGE, userId), tr)
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
