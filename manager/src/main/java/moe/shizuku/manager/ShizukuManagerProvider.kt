package moe.shizuku.manager

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.core.os.bundleOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightClient
import kerneldroid.nightzuku.api.NightProtocol
import kerneldroid.nightzuku.api.NightProvider
import moe.shizuku.api.BinderContainer
import moe.shizuku.manager.module.ModuleRuntimeUtils
import kerneldroid.nightzuku.util.SmartLogEngine
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.SmartLogManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.server.ktx.workerHandler

class ShizukuManagerProvider : NightProvider() {

    companion object {
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
        const val METHOD_VERIFY_HOST = "verifyNightzukuHost"
        private const val BINDER_TRANSACTION_REQUEST_BINDER_FOR_CLIENT = 10014

        internal fun handleClientBinderRequest(context: Context, method: String, extras: Bundle?): Bundle? {
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            if (!isEligibleBinderRequester(context, callingUid, extras?.getString("packageName"))) {
                return null
            }

            triggerServerBinderPush(callingUid, callingPid)

            val nightBinder = NightClient.getBinder()?.takeIf { it.pingBinder() }
            val legacyBinder = Shizuku.getBinder()?.takeIf { it.pingBinder() }
            if (nightBinder == null && legacyBinder == null) {
                return null
            }

            return Bundle().apply {
                if (method == NightProtocol.METHOD_REQUEST_BINDER) {
                    val primary = nightBinder ?: legacyBinder
                    if (primary != null) {
                        putBinder(NightProtocol.EXTRA_BINDER, primary)
                        putParcelable(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY, BinderContainer(primary))
                    }
                } else {
                    val primary = legacyBinder ?: nightBinder
                    if (primary != null) {
                        putParcelable(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY, BinderContainer(primary))
                        putBinder(NightProtocol.EXTRA_BINDER, primary)
                    }
                }
            }
        }

        private fun isEligibleBinderRequester(context: Context, callingUid: Int, requestedPkg: String?): Boolean {
            if (callingUid == Process.myUid() || callingUid == 0 || callingUid == 2000) {
                return true
            }
            val pm = context.packageManager
            val packages = runCatching { pm.getPackagesForUid(callingUid)?.toList().orEmpty() }.getOrDefault(emptyList())
            if (packages.isEmpty()) return false
            if (!requestedPkg.isNullOrEmpty() && !packages.contains(requestedPkg)) {
                return false
            }
            for (pkg in packages) {
                val pi = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                }.getOrNull() ?: continue
                val perms = pi.requestedPermissions ?: continue
                if (perms.contains(Manifest.permission.API) || perms.contains(Manifest.permission.API_V23)) {
                    return true
                }
            }
            return false
        }

        private fun triggerServerBinderPush(targetUid: Int, targetPid: Int) {
            val serverBinder = Shizuku.getBinder()?.takeIf { it.pingBinder() } ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
                data.writeInt(targetUid)
                data.writeInt(targetPid)
                serverBinder.transact(BINDER_TRANSACTION_REQUEST_BINDER_FOR_CLIENT, data, reply, 0)
            } catch (_: Throwable) {
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == SmartLogEngine.METHOD_PUSH_SMART_LOG) {
            val callingUid = Binder.getCallingUid()
            if (callingUid != 0 && callingUid != 2000 && callingUid != Process.myUid()) {
                return null
            }
            val ctx = context ?: return null
            val rawPayload = extras?.getString(SmartLogEngine.EXTRA_SMART_LOG_PAYLOAD) ?: arg
            val accepted = SmartLogManager.ingestPushedPayload(ctx, rawPayload)
            return Bundle().apply {
                putBoolean("accepted", accepted)
            }
        }

        if (method == METHOD_VERIFY_HOST) {
            val ctx = context ?: return null
            val signer = ModuleRuntimeUtils.getManagerSignerSha256(ctx)
            val challenge = arg?.take(256).orEmpty()
            val moduleId = extras?.getString("moduleId").orEmpty()
            val token = ModuleRuntimeUtils.computeAttestationToken(moduleId, challenge, signer)
            val digest = ModuleRuntimeUtils.computeAttestationDigest(moduleId, challenge, signer)
            return Bundle().apply {
                putBoolean("genuine", true)
                putString("hostPackage", ModuleRuntimeUtils.HOST_PACKAGE_ID)
                putString("hostVersion", BuildConfig.VERSION_NAME)
                putInt("hostVersionCode", BuildConfig.VERSION_CODE)
                putInt("modulesApiVersion", ModuleRuntimeUtils.HOST_API_VERSION)
                putString("binderDescriptor", NightProtocol.DESCRIPTOR)
                putString("moduleId", moduleId)
                putString("challenge", challenge)
                putString("signerSha256", signer)
                putString("attestationToken", token)
                putString("attestationSha256", digest)
            }
        }

        if (method == NightProtocol.METHOD_REQUEST_BINDER ||
            method == NightProtocol.LEGACY_METHOD_REQUEST_BINDER
        ) {
            val ctx = context ?: return null
            return handleClientBinderRequest(ctx, method, extras)
        }

        if (method == NightProtocol.METHOD_GET_BINDER) {
            val superReply = super.call(method, arg, extras ?: Bundle())
            val extracted = LegacyParcelCodec.extractBinderFromBundle(superReply)
            if (extracted != null && extracted.pingBinder()) {
                return superReply
            }
            val fallback: IBinder? = NightClient.getBinder()?.takeIf { it.pingBinder() }
                ?: Shizuku.getBinder()?.takeIf { it.pingBinder() }
            if (fallback != null) {
                return Bundle().apply {
                    putBinder(NightProtocol.EXTRA_BINDER, fallback)
                }
            }
            return superReply
        }

        if (extras == null) return null

        return when (method) {
            NightProtocol.METHOD_SEND_USER_SERVICE, METHOD_SEND_USER_SERVICE -> {
                handleSendUserService(extras)
            }
            else -> super.call(method, arg, extras)
        }
    }

    private fun handleSendUserService(extras: Bundle): Bundle? {
        return try {
            val token = extras.getString(NightProtocol.EXTRA_USER_SERVICE_TOKEN)
                ?: extras.getString(USER_SERVICE_ARG_TOKEN)
                ?: return null
            val binder = LegacyParcelCodec.extractBinderFromBundle(extras) ?: return null

            val countDownLatch = CountDownLatch(1)
            var reply: Bundle? = null

            val listener = object : Shizuku.OnBinderReceivedListener {

                override fun onBinderReceived() {
                    try {
                        Shizuku.attachUserService(
                            binder,
                            bundleOf(USER_SERVICE_ARG_TOKEN to token)
                        )
                        val serverBinder = Shizuku.getBinder()
                        val nightBinder = NightClient.getBinder()?.takeIf { it.pingBinder() } ?: serverBinder
                        if (serverBinder != null) {
                            reply = Bundle().apply {
                                putParcelable(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY, BinderContainer(serverBinder))
                                if (nightBinder != null) {
                                    putBinder(NightProtocol.EXTRA_BINDER, nightBinder)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        LOGGER.e(e, "attachUserService $token")
                        reply = null
                    } finally {
                        Shizuku.removeBinderReceivedListener(this)
                        countDownLatch.countDown()
                    }
                }
            }

            Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

            try {
                val received = countDownLatch.await(5, TimeUnit.SECONDS)
                if (!received) {
                    LOGGER.e("Binder not received in 5s for user service $token")
                    return null
                }
                reply
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                LOGGER.e(e, "Interrupted waiting for binder")
                null
            } finally {
                Shizuku.removeBinderReceivedListener(listener)
            }
        } catch (e: Throwable) {
            LOGGER.e(e, "sendUserService")
            null
        }
    }
}
