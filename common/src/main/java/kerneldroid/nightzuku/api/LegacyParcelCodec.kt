package kerneldroid.nightzuku.api

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

/**
 * Zero-stub raw Parcel codec for legacy Shizuku compatibility.
 *
 * Constructs and parses legacy Shizuku Bundles and IShizukuApplication transactions
 * directly at the Parcel byte level so the server and starter never require
 * `moe.shizuku.api.BinderContainer` or legacy AIDL stubs on their runtime classpath.
 */
object LegacyParcelCodec {

    const val LEGACY_BINDER_EXTRA_KEY = "moe.shizuku.privileged.api.intent.extra.BINDER"
    private const val LEGACY_BINDER_CONTAINER_CLASS = "moe.shizuku.api.BinderContainer"
    private const val LEGACY_APPLICATION_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication"

    private const val BUNDLE_MAGIC_NATIVE = 0x4C444E42 // 'B' 'N' 'D' 'L'
    private const val VAL_PARCELABLE = 4

    private const val TRANSACTION_BIND_APPLICATION = 2
    private const val TRANSACTION_DISPATCH_PERMISSION_RESULT = 3

    /**
     * Builds a Bundle containing a serialized `moe.shizuku.api.BinderContainer` in raw Parcel
     * format without loading or referencing the `BinderContainer` class in the current process.
     * When sent across Binder IPC, the receiving client unparcels it using its own ClassLoader.
     */
    @JvmStatic
    fun createLegacyBinderBundle(binder: IBinder, token: String? = null): Bundle {
        val body = Parcel.obtain()
        val rawBundle = Parcel.obtain()
        return try {
            val mapCount = if (token != null) 2 else 1
            body.writeInt(mapCount)

            // Entry 1: moe.shizuku.privileged.api.intent.extra.BINDER -> BinderContainer(binder)
            body.writeString(LEGACY_BINDER_EXTRA_KEY)
            body.writeInt(VAL_PARCELABLE)
            body.writeString(LEGACY_BINDER_CONTAINER_CLASS)
            body.writeStrongBinder(binder)

            // Entry 2 (optional): shizuku:user-service-arg-token -> String
            if (token != null) {
                body.writeString("shizuku:user-service-arg-token")
                body.writeInt(0 /* VAL_STRING */)
                body.writeString(token)
            }

            val length = body.dataSize()
            rawBundle.writeInt(length)
            rawBundle.writeInt(BUNDLE_MAGIC_NATIVE)
            rawBundle.appendFrom(body, 0, length)
            rawBundle.setDataPosition(0)

            Bundle.CREATOR.createFromParcel(rawBundle)
        } finally {
            body.recycle()
            rawBundle.recycle()
        }
    }

    /**
     * Extracts an IBinder from a Bundle that may carry either a direct Nightzuku binder
     * (`NightProtocol.EXTRA_BINDER`) or a raw legacy `BinderContainer` parcelable.
     */
    @JvmStatic
    fun extractBinderFromBundle(bundle: Bundle?): IBinder? {
        if (bundle == null) return null

        // Fast path: direct binder extra from NightProtocol
        try {
            val direct = bundle.getBinder(NightProtocol.EXTRA_BINDER)
            if (direct != null) return direct
        } catch (_: Throwable) {
        }

        // Raw parcel extraction of BinderContainer without needing BinderContainer class
        val parcel = Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val length = parcel.readInt()
            if (length <= 0) return null
            val magic = parcel.readInt()
            if (magic != BUNDLE_MAGIC_NATIVE) return null
            val count = parcel.readInt()
            for (i in 0 until count) {
                val key = parcel.readString()
                val type = parcel.readInt()
                if (key == LEGACY_BINDER_EXTRA_KEY && type == VAL_PARCELABLE) {
                    val className = parcel.readString()
                    if (className == LEGACY_BINDER_CONTAINER_CLASS) {
                        return parcel.readStrongBinder()
                    }
                }
                // If not the first entry, fallback fails gracefully
                break
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Dispatches `IShizukuApplication.bindApplication(Bundle)` over raw Parcel.
     */
    @JvmStatic
    fun sendLegacyBindApplication(
        clientBinder: IBinder,
        serverUid: Int,
        serverVersion: Int,
        serverPatchVersion: Int,
        seContext: String?,
        allowed: Boolean,
        shouldShowRationale: Boolean
    ) {
        val data = Parcel.obtain()
        try {
            val reply = Bundle().apply {
                putInt("shizuku:attach-reply-uid", serverUid)
                putInt("shizuku:attach-reply-version", serverVersion)
                putInt("shizuku:attach-reply-patch-version", serverPatchVersion)
                putString("shizuku:attach-reply-secontext", seContext)
                putBoolean("shizuku:attach-reply-permission-granted", allowed)
                putBoolean("shizuku:attach-reply-should-show-request-permission-rationale", shouldShowRationale)
            }
            data.writeInterfaceToken(LEGACY_APPLICATION_DESCRIPTOR)
            data.writeInt(1)
            reply.writeToParcel(data, 0)
            clientBinder.transact(TRANSACTION_BIND_APPLICATION, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    /**
     * Dispatches `IShizukuApplication.dispatchRequestPermissionResult(int, Bundle)` over raw Parcel.
     */
    @JvmStatic
    fun sendLegacyPermissionResult(
        clientBinder: IBinder,
        requestCode: Int,
        allowed: Boolean
    ) {
        val data = Parcel.obtain()
        try {
            val reply = Bundle().apply {
                putBoolean("shizuku:request-permission-reply-allowed", allowed)
            }
            data.writeInterfaceToken(LEGACY_APPLICATION_DESCRIPTOR)
            data.writeInt(requestCode)
            data.writeInt(1)
            reply.writeToParcel(data, 0)
            clientBinder.transact(TRANSACTION_DISPATCH_PERMISSION_RESULT, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }
}
