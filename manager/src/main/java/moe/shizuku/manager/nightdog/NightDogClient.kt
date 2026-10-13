package moe.shizuku.manager.nightdog

import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

object NightDogClient {

    fun isEnabled(): Boolean {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku binder is not available")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            binder.transact(ServerConstants.BINDER_TRANSACTION_getNightDogEnabled, data, reply, 0)
            reply.readException()
            return reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun setEnabled(enabled: Boolean) {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku binder is not available")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(if (enabled) 1 else 0)
            binder.transact(ServerConstants.BINDER_TRANSACTION_setNightDogEnabled, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
