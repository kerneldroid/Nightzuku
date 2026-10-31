package moe.shizuku.manager.authorization

import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Parcel
import kerneldroid.nightzuku.api.NightProtocol
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

data class AppControlSnapshot(
    val uid: Int,
    val granted: Boolean,
    val isOneTime: Boolean,
    val timerRemainingMs: Long,
    val capabilities: Int,
    val lastConnectedAt: Long,
    val activePids: List<Int>,
    val signerSha256: String
)

object AuthorizationManager {

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int): List<PackageInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(userId)
            try {
                Shizuku.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                throw RuntimeException(e)
            }
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list!!
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getPackages(): List<PackageInfo> {
        return getApplications(-1)
    }

    fun granted(packageName: String, uid: Int): Boolean {
        if ((Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED) {
            return true
        }
        return isOneTime(uid)
    }

    fun grant(packageName: String, uid: Int) {
        Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
    }

    fun revoke(packageName: String, uid: Int) {
        Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
    }

    fun isOneTime(uid: Int): Boolean {
        val binder = Shizuku.getBinder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(uid)
            binder.transact(ServerConstants.BINDER_TRANSACTION_isOneTimeGrant, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun setOneTime(uid: Int, enabled: Boolean) {
        val binder = Shizuku.getBinder() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(uid)
            data.writeInt(if (enabled) 1 else 0)
            binder.transact(ServerConstants.BINDER_TRANSACTION_setOneTimeGrant, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun setAccessTimer(uid: Int, durationMs: Long) {
        val binder = Shizuku.getBinder() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(uid)
            data.writeLong(durationMs)
            binder.transact(ServerConstants.BINDER_TRANSACTION_setAccessTimer, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getAccessTimerRemaining(uid: Int): Long {
        val binder = Shizuku.getBinder() ?: return 0L
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(uid)
            binder.transact(ServerConstants.BINDER_TRANSACTION_getAccessTimerRemaining, data, reply, 0)
            reply.readException()
            reply.readLong()
        } catch (_: Throwable) {
            0L
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getCapabilities(uid: Int): Int {
        val raw = try {
            Shizuku.getFlagsForUid(uid, NightProtocol.MASK_CAPABILITIES) and NightProtocol.MASK_CAPABILITIES
        } catch (_: Throwable) {
            0
        }
        return if (raw == 0) NightProtocol.DEFAULT_CAPABILITIES else raw
    }

    fun setCapabilities(uid: Int, capabilities: Int) {
        val sanitized = capabilities and NightProtocol.MASK_CAPABILITIES
        Shizuku.updateFlagsForUid(uid, NightProtocol.MASK_CAPABILITIES, sanitized)
    }

    fun forceStopApp(packageName: String, uid: Int) {
        val binder = Shizuku.getBinder() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeString(packageName)
            data.writeInt(uid)
            binder.transact(ServerConstants.BINDER_TRANSACTION_forceStopApp, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getAppControlInfo(packageName: String, uid: Int): AppControlSnapshot {
        val binder = Shizuku.getBinder()
        if (binder != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
                data.writeInt(uid)
                binder.transact(ServerConstants.BINDER_TRANSACTION_getAppControlInfo, data, reply, 0)
                reply.readException()
                val bundle: Bundle? = reply.readBundle(AppControlSnapshot::class.java.classLoader)
                if (bundle != null) {
                    val isOneTime = bundle.getBoolean("oneTime", false)
                    val allowedFlag = bundle.getBoolean("allowed", false)
                    return AppControlSnapshot(
                        uid = uid,
                        granted = allowedFlag || isOneTime,
                        isOneTime = isOneTime,
                        timerRemainingMs = bundle.getLong("timerRemainingMs", 0L),
                        capabilities = bundle.getInt("capabilities", NightProtocol.DEFAULT_CAPABILITIES),
                        lastConnectedAt = bundle.getLong("lastConnectedAt", 0L),
                        activePids = bundle.getIntArray("activePids")?.toList().orEmpty(),
                        signerSha256 = bundle.getString("signerSha256").orEmpty()
                    )
                }
            } catch (_: Throwable) {
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        val isGranted = try {
            granted(packageName, uid)
        } catch (_: Throwable) {
            false
        }
        return AppControlSnapshot(
            uid = uid,
            granted = isGranted,
            isOneTime = isOneTime(uid),
            timerRemainingMs = getAccessTimerRemaining(uid),
            capabilities = getCapabilities(uid),
            lastConnectedAt = 0L,
            activePids = emptyList(),
            signerSha256 = ""
        )
    }
}
