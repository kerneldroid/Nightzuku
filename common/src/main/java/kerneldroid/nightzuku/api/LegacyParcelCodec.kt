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
    const val DESCRIPTOR_ISHIZUKU_APPLICATION = "moe.shizuku.server.IShizukuApplication"

    private const val BUNDLE_MAGIC_NATIVE = 0x4C444E42 // 'B' 'N' 'D' 'L'
    private const val VAL_NULL = -1
    private const val VAL_STRING = 0
    private const val VAL_INTEGER = 1
    private const val VAL_PARCELABLE = 4
    private const val VAL_LONG = 6
    private const val VAL_BOOLEAN = 9
    private const val VAL_IBINDER = 15

    const val TRANSACTION_BIND_APPLICATION = 2
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
                body.writeInt(VAL_STRING)
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
     *
     * Inspects the raw Parcel representation BEFORE calling `Bundle.getBinder()` so that
     * `BaseBundle.unparcel()` is never triggered on a Bundle containing `BinderContainer`
     * in a process that lacks `moe.shizuku.api.BinderContainer` on its classpath.
     */
    @JvmStatic
    fun extractBinderFromBundle(bundle: Bundle?): IBinder? {
        if (bundle == null) return null

        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val length = parcel.readInt()
            if (length > 0) {
                val magic = parcel.readInt()
                if (magic == BUNDLE_MAGIC_NATIVE) {
                    val bodyStart = parcel.dataPosition()
                    val bodyEnd = (bodyStart + length).coerceAtMost(parcel.dataSize())
                    val count = parcel.readInt()
                    for (i in 0 until count) {
                        if (parcel.dataPosition() >= bodyEnd) break
                        val key = parcel.readString()
                        val type = parcel.readInt()
                        if (key == NightProtocol.EXTRA_BINDER && type == VAL_IBINDER) {
                            return parcel.readStrongBinder()
                        }
                        if (key == LEGACY_BINDER_EXTRA_KEY && type == VAL_PARCELABLE) {
                            val posBeforeClass = parcel.dataPosition()
                            val directClassName = parcel.readString()
                            if (directClassName == LEGACY_BINDER_CONTAINER_CLASS) {
                                return parcel.readStrongBinder()
                            }
                            // Android 13+ (API 33+) prefixes lazy VAL_PARCELABLE values with an int byte size
                            parcel.setDataPosition(posBeforeClass)
                            val lazySize = parcel.readInt()
                            if (lazySize > 0 && parcel.dataPosition() + lazySize <= bodyEnd) {
                                val lazyClassName = parcel.readString()
                                if (lazyClassName == LEGACY_BINDER_CONTAINER_CLASS) {
                                    return parcel.readStrongBinder()
                                }
                            }
                            break
                        }
                        when (type) {
                            VAL_NULL -> Unit
                            VAL_STRING -> parcel.readString()
                            VAL_INTEGER, VAL_BOOLEAN -> parcel.readInt()
                            VAL_LONG -> parcel.readLong()
                            VAL_IBINDER -> parcel.readStrongBinder()
                            else -> break
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            parcel.recycle()
        }

        return try {
            bundle.getBinder(NightProtocol.EXTRA_BINDER)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Dispatches `IShizukuApplication.bindApplication(Bundle)` over raw Parcel.
     */
    @JvmStatic
    fun sendLegacyBindApplication(
        applicationBinder: IBinder,
        serverUid: Int,
        serverVersion: Int,
        serverPatchVersion: Int,
        seContext: String?,
        permissionGranted: Boolean,
        shouldShowRationale: Boolean
    ): Boolean {
        val data = Parcel.obtain()
        try {
            val reply = Bundle().apply {
                putInt("shizuku:attach-reply-uid", serverUid)
                putInt("shizuku:attach-reply-version", serverVersion)
                putInt("shizuku:attach-reply-patch-version", serverPatchVersion)
                putString("shizuku:attach-reply-secontext", seContext)
                putBoolean("shizuku:attach-reply-permission-granted", permissionGranted)
                putBoolean("shizuku:attach-reply-should-show-request-permission-rationale", shouldShowRationale)
            }
            data.writeInterfaceToken(DESCRIPTOR_ISHIZUKU_APPLICATION)
            data.writeInt(1)
            reply.writeToParcel(data, 0)
            return applicationBinder.transact(TRANSACTION_BIND_APPLICATION, data, null, IBinder.FLAG_ONEWAY)
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
            data.writeInterfaceToken(DESCRIPTOR_ISHIZUKU_APPLICATION)
            data.writeInt(requestCode)
            data.writeInt(1)
            reply.writeToParcel(data, 0)
            clientBinder.transact(TRANSACTION_DISPATCH_PERMISSION_RESULT, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }
}
