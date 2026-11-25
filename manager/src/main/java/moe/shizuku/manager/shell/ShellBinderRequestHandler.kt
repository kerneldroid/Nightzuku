package moe.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import moe.shizuku.manager.settings.SecurityPolicyClient
import moe.shizuku.manager.settings.SecurityPolicyState
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

object ShellBinderRequestHandler {

    const val ACTION_REQUEST_BINDER_NIGHT = "kerneldroid.nightzuku.intent.action.REQUEST_BINDER"
    const val ACTION_REQUEST_BINDER_LEGACY = "rikka.shizuku.intent.action.REQUEST_BINDER"

    fun isSupportedAction(action: String?): Boolean {
        return action == ACTION_REQUEST_BINDER_NIGHT || action == ACTION_REQUEST_BINDER_LEGACY
    }

    fun handleRequest(context: Context, intent: Intent): Boolean {
        if (!isSupportedAction(intent.action)) {
            return false
        }

        val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false

        val shizukuBinder = Shizuku.getBinder()
        if (shizukuBinder == null) {
            LOGGER.w("Binder not received or Nightzuku service not running")
        }

        val policy = if (shizukuBinder != null) SecurityPolicyClient.getSecurityPolicy() else null
        val blockedByPolicy = policy != null && (
            policy.accessPaused ||
                !policy.allowLegacyWire ||
                policy.binderDeliveryMode == SecurityPolicyState.BINDER_DELIVERY_AUTHORIZED_ONLY
            )
        val binderToSend = if (blockedByPolicy) {
            LOGGER.w(
                "Blocked shell binder request by security policy (paused=%s, legacyWire=%s, deliveryMode=%d)",
                policy!!.accessPaused,
                policy.allowLegacyWire,
                policy.binderDeliveryMode
            )
            null
        } else {
            shizukuBinder
        }

        val data = Parcel.obtain()
        return try {
            data.writeStrongBinder(binderToSend)
            data.writeString(context.applicationInfo.sourceDir)
            binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
            !blockedByPolicy
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        } finally {
            data.recycle()
        }
    }
}
