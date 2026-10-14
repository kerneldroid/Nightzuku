package moe.shizuku.manager.nightdog

import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.atomic.AtomicBoolean
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

object NightDogClient {

    private const val KEY_NIGHTDOG_ENABLED = "nightdog_enabled"
    private const val KEY_NIGHTDOG_PENDING_SYNC = "nightdog_pending_sync"

    private val initialized = AtomicBoolean(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        syncWithServer()
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
    }

    fun isServerAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun isEnabled(): Boolean {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            null
        }
        val cached = prefs?.getBoolean(KEY_NIGHTDOG_ENABLED, false) ?: false
        val pendingSync = prefs?.getBoolean(KEY_NIGHTDOG_PENDING_SYNC, false) ?: false

        val binder = Shizuku.getBinder()
        if (binder == null || !binder.pingBinder()) {
            return cached
        }

        if (pendingSync && sendSetEnabledTransaction(binder, cached)) {
            prefs?.edit()?.putBoolean(KEY_NIGHTDOG_PENDING_SYNC, false)?.apply()
            return cached
        }

        val remoteEnabled = sendGetEnabledTransaction(binder) ?: return cached
        prefs?.edit()
            ?.putBoolean(KEY_NIGHTDOG_ENABLED, remoteEnabled)
            ?.putBoolean(KEY_NIGHTDOG_PENDING_SYNC, false)
            ?.apply()
        return remoteEnabled
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            null
        }

        val binder = Shizuku.getBinder()
        val appliedToServer = if (binder != null && binder.pingBinder()) {
            sendSetEnabledTransaction(binder, enabled)
        } else {
            false
        }

        prefs?.edit()
            ?.putBoolean(KEY_NIGHTDOG_ENABLED, enabled)
            ?.putBoolean(KEY_NIGHTDOG_PENDING_SYNC, !appliedToServer)
            ?.apply()

        return appliedToServer
    }

    fun syncWithServer() {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            return
        }
        val binder = Shizuku.getBinder()
        if (binder == null || !binder.pingBinder()) return

        val pendingSync = prefs.getBoolean(KEY_NIGHTDOG_PENDING_SYNC, false)
        if (pendingSync) {
            val desired = prefs.getBoolean(KEY_NIGHTDOG_ENABLED, false)
            if (sendSetEnabledTransaction(binder, desired)) {
                prefs.edit().putBoolean(KEY_NIGHTDOG_PENDING_SYNC, false).apply()
            }
        } else {
            val remoteEnabled = sendGetEnabledTransaction(binder) ?: return
            prefs.edit().putBoolean(KEY_NIGHTDOG_ENABLED, remoteEnabled).apply()
        }
    }

    private fun sendGetEnabledTransaction(binder: IBinder): Boolean? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_getNightDogEnabled, data, reply, 0)
            if (!ok) return null
            reply.readException()
            reply.readInt() != 0
        } catch (t: Throwable) {
            LOGGER.w(t, "NightDogClient: getNightDogEnabled failed")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun sendSetEnabledTransaction(binder: IBinder, enabled: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(if (enabled) 1 else 0)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_setNightDogEnabled, data, reply, 0)
            if (!ok) return false
            reply.readException()
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "NightDogClient: setNightDogEnabled(%s) failed", enabled.toString())
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
