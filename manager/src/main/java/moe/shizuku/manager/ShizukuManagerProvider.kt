package moe.shizuku.manager

import android.os.Bundle
import androidx.core.os.bundleOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightProvider
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.api.BinderContainer
import moe.shizuku.manager.module.ModuleRuntimeUtils
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.server.ktx.workerHandler

class ShizukuManagerProvider : NightProvider() {

    companion object {
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
        const val METHOD_VERIFY_HOST = "verifyNightzukuHost"
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_VERIFY_HOST) {
            val ctx = context ?: return null
            val signer = ModuleRuntimeUtils.getManagerSignerSha256(ctx)
            val challenge = arg?.take(256).orEmpty()
            val moduleId = extras?.getString("moduleId").orEmpty()
            val token = ModuleRuntimeUtils.computeAttestationToken(moduleId, challenge, signer)
            return Bundle().apply {
                putBoolean("genuine", true)
                putString("hostPackage", ModuleRuntimeUtils.HOST_PACKAGE_ID)
                putString("hostVersion", BuildConfig.VERSION_NAME)
                putInt("hostVersionCode", BuildConfig.VERSION_CODE)
                putInt("modulesApiVersion", ModuleRuntimeUtils.HOST_API_VERSION)
                putString("binderDescriptor", NightProtocol.DESCRIPTOR)
                putString("signerSha256", signer)
                putString("attestationToken", token)
            }
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
                        if (serverBinder != null) {
                            reply = Bundle().apply {
                                putParcelable(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY, BinderContainer(serverBinder))
                                putBinder(NightProtocol.EXTRA_BINDER, serverBinder)
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
