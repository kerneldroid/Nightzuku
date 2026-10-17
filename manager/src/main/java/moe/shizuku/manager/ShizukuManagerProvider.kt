package moe.shizuku.manager

import android.os.Bundle
import androidx.core.os.bundleOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightClient
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.api.BinderContainer
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler

class ShizukuManagerProvider : ShizukuProvider() {

    companion object {
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    }

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null

        return when (method) {
            NightProtocol.METHOD_SEND_BINDER -> {
                val binder = extras.getBinder(NightProtocol.EXTRA_BINDER)
                if (binder != null && context != null) {
                    NightClient.onBinderReceived(binder, context!!.packageName)
                }
                Bundle()
            }
            NightProtocol.METHOD_GET_BINDER -> {
                val activeBinder = NightClient.getBinder()?.takeIf { it.pingBinder() }
                    ?: Shizuku.getBinder()?.takeIf { it.pingBinder() }
                    ?: return null
                Bundle().apply {
                    putBinder(NightProtocol.EXTRA_BINDER, activeBinder)
                }
            }
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
            var reply: Bundle? = Bundle()

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
                        } else {
                            reply = null
                        }
                    } catch (e: Throwable) {
                        LOGGER.e(e, "attachUserService $token")
                        reply = null
                    }

                    Shizuku.removeBinderReceivedListener(this)
                    countDownLatch.countDown()
                }
            }

            Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

            try {
                countDownLatch.await(5, TimeUnit.SECONDS)
                reply
            } catch (e: TimeoutException) {
                LOGGER.e(e, "Binder not received in 5s")
                null
            }
        } catch (e: Throwable) {
            LOGGER.e(e, "sendUserService")
            null
        }
    }
}
