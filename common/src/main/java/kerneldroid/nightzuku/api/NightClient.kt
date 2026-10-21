package kerneldroid.nightzuku.api

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-speed, zero-stub client connector for the native Nightzuku API (`INightService`).
 *
 * Communicates with `NightServiceEndpoint` using flat Parcel serialization,
 * supports capability inspection, guarded `transactRemote`, one-shot `execFast`,
 * global access pause control, and server diagnostics queries.
 */
object NightClient {

    private const val TAG = "NightClient"

    data class ExecResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val stdout: ByteArray,
        val stderr: ByteArray
    ) {
        val outputText: String
            get() = String(stdout, StandardCharsets.UTF_8)

        val errorText: String
            get() = String(stderr, StandardCharsets.UTF_8)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExecResult) return false
            return exitCode == other.exitCode &&
                timedOut == other.timedOut &&
                stdout.contentEquals(other.stdout) &&
                stderr.contentEquals(other.stderr)
        }

        override fun hashCode(): Int {
            var result = exitCode
            result = 31 * result + timedOut.hashCode()
            result = 31 * result + stdout.contentHashCode()
            result = 31 * result + stderr.contentHashCode()
            return result
        }
    }

    fun interface OnBinderReceivedListener {
        fun onBinderReceived()
    }

    fun interface OnBinderDeadListener {
        fun onBinderDead()
    }

    fun interface OnPermissionResultListener {
        fun onPermissionResult(requestCode: Int, grantResult: Int, capabilities: Int)
    }

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var serverUid: Int = -1

    @Volatile
    private var serverVersion: Int = -1

    @Volatile
    private var serverContext: String? = null

    @Volatile
    private var permissionGranted: Boolean = false

    @Volatile
    private var shouldShowRationale: Boolean = false

    @Volatile
    private var capabilitiesMask: Int = 0

    @Volatile
    private var binderReady: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val receivedListeners = CopyOnWriteArrayList<OnBinderReceivedListener>()
    private val deadListeners = CopyOnWriteArrayList<OnBinderDeadListener>()
    private val permissionListeners = CopyOnWriteArrayList<OnPermissionResultListener>()

    private val callbackBinder: Binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(NightProtocol.CALLBACK_DESCRIPTOR)
                    return true
                }
                NightProtocol.CALLBACK_ON_PERMISSION_CHANGED -> {
                    data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                    permissionGranted = data.readInt() != 0
                    shouldShowRationale = data.readInt() != 0
                    capabilitiesMask = data.readInt()
                    return true
                }
                NightProtocol.CALLBACK_ON_REQUEST_PERMISSION_RESULT -> {
                    data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                    val requestCode = data.readInt()
                    val allowed = data.readInt() != 0
                    val caps = data.readInt()
                    permissionGranted = allowed
                    capabilitiesMask = caps
                    val grantResult = if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                    dispatchPermissionResult(requestCode, grantResult, caps)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        binderReady = false
        onBinderReceived(null, null)
    }

    @JvmStatic
    fun onBinderReceived(newBinder: IBinder?, packageName: String?) {
        if (binder === newBinder) return

        binder?.let { old ->
            try {
                old.unlinkToDeath(deathRecipient, 0)
            } catch (_: Throwable) {
            }
        }

        if (newBinder == null) {
            binder = null
            serverUid = -1
            serverVersion = -1
            serverContext = null
            permissionGranted = false
            shouldShowRationale = false
            capabilitiesMask = 0
            binderReady = false
            dispatchBinderDead()
            return
        }

        binder = newBinder
        try {
            newBinder.linkToDeath(deathRecipient, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "linkToDeath failed", t)
            binder = null
            binderReady = false
            return
        }

        if (packageName != null) {
            try {
                attachFlat(newBinder, packageName)
                binderReady = true
                dispatchBinderReceived()
            } catch (t: Throwable) {
                Log.w(TAG, "Flat attach handshake failed", t)
                try {
                    newBinder.unlinkToDeath(deathRecipient, 0)
                } catch (_: Throwable) {
                }
                binder = null
                binderReady = false
            }
        }
    }

    @Throws(RemoteException::class)
    private fun attachFlat(target: IBinder, packageName: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            data.writeInt(NightProtocol.PROTOCOL_VERSION)
            data.writeString(packageName)
            target.transact(NightProtocol.TRANSACTION_ATTACH, data, reply, 0)
            reply.readException()
            serverUid = reply.readInt()
            serverVersion = reply.readInt()
            val grantState = reply.readInt()
            capabilitiesMask = reply.readInt()
            serverContext = reply.readString()
            permissionGranted = grantState == NightProtocol.STATE_GRANTED
            shouldShowRationale = grantState == NightProtocol.STATE_DENIED_PERMANENT
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun getBinder(): IBinder? = binder

    @JvmStatic
    fun pingBinder(): Boolean = binder?.pingBinder() == true

    @JvmStatic
    fun getUid(): Int = serverUid

    @JvmStatic
    fun getVersion(): Int = serverVersion

    @JvmStatic
    fun getSELinuxContext(): String? = serverContext

    @JvmStatic
    fun getCapabilities(): Int = capabilitiesMask

    @JvmStatic
    fun hasCapability(capabilityFlag: Int): Boolean = (capabilitiesMask and capabilityFlag) != 0

    @JvmStatic
    fun checkSelfPermission(): Int {
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED
        val current = binder ?: return PackageManager.PERMISSION_DENIED
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            current.transact(NightProtocol.TRANSACTION_CHECK_PERMISSION, data, reply, 0)
            reply.readException()
            permissionGranted = reply.readInt() != 0
            shouldShowRationale = reply.readInt() != 0
            capabilitiesMask = reply.readInt()
            if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun shouldShowRequestPermissionRationale(): Boolean {
        if (permissionGranted) return false
        return shouldShowRationale
    }

    @JvmStatic
    fun requestPermission(requestCode: Int) {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeInt(requestCode)
            current.transact(NightProtocol.TRANSACTION_REQUEST_PERMISSION, data, reply, 0)
            reply.readException()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun execFast(
        cmd: Array<String>,
        env: Array<String>? = null,
        dir: String? = null,
        timeoutMs: Long = NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS
    ): ExecResult {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeStringArray(cmd)
            data.writeStringArray(env)
            data.writeString(dir)
            data.writeLong(timeoutMs)
            current.transact(NightProtocol.TRANSACTION_EXEC_FAST, data, reply, 0)
            reply.readException()
            val exitCode = reply.readInt()
            val timedOut = reply.readInt() != 0
            val stdout = reply.createByteArray() ?: ByteArray(0)
            val stderr = reply.createByteArray() ?: ByteArray(0)
            ExecResult(exitCode, timedOut, stdout, stderr)
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun transactRemote(targetBinder: IBinder, code: Int, data: Parcel, reply: Parcel?, flags: Int) {
        val current = requireBinder()
        val wrapped = Parcel.obtain()
        try {
            wrapped.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            wrapped.writeStrongBinder(targetBinder)
            wrapped.writeInt(code)
            wrapped.writeInt(flags)
            wrapped.appendFrom(data, 0, data.dataSize())
            current.transact(NightProtocol.TRANSACTION_TRANSACT_REMOTE, wrapped, reply, 0)
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            wrapped.recycle()
        }
    }

    @JvmStatic
    fun getSystemProperty(name: String, defaultValue: String? = null): String? {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeString(name)
            data.writeString(defaultValue)
            current.transact(NightProtocol.TRANSACTION_GET_SYSTEM_PROPERTY, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun setSystemProperty(name: String, value: String) {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeString(name)
            data.writeString(value)
            current.transact(NightProtocol.TRANSACTION_SET_SYSTEM_PROPERTY, data, reply, 0)
            reply.readException()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun isAccessPaused(): Boolean {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            current.transact(NightProtocol.TRANSACTION_GET_ACCESS_PAUSED, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun setAccessPaused(paused: Boolean) {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeInt(if (paused) 1 else 0)
            current.transact(NightProtocol.TRANSACTION_SET_ACCESS_PAUSED, data, reply, 0)
            reply.readException()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun getServerDiagnostics(): Bundle? {
        val current = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            if (!current.transact(NightProtocol.TRANSACTION_GET_SERVER_DIAGNOSTICS, data, reply, 0)) {
                return null
            }
            reply.readException()
            reply.readBundle(NightClient::class.java.classLoader)
        } catch (_: Throwable) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener) {
        receivedListeners.add(listener)
        if (binderReady) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onBinderReceived()
            } else {
                mainHandler.post { listener.onBinderReceived() }
            }
        }
    }

    @JvmStatic
    fun removeBinderReceivedListener(listener: OnBinderReceivedListener) {
        receivedListeners.remove(listener)
    }

    @JvmStatic
    fun addBinderDeadListener(listener: OnBinderDeadListener) {
        deadListeners.add(listener)
    }

    @JvmStatic
    fun removeBinderDeadListener(listener: OnBinderDeadListener) {
        deadListeners.remove(listener)
    }

    @JvmStatic
    fun addPermissionResultListener(listener: OnPermissionResultListener) {
        permissionListeners.add(listener)
    }

    @JvmStatic
    fun removePermissionResultListener(listener: OnPermissionResultListener) {
        permissionListeners.remove(listener)
    }

    private fun requireBinder(): IBinder {
        return binder ?: throw IllegalStateException("NightClient binder has not been received")
    }

    private fun dispatchBinderReceived() {
        for (listener in receivedListeners) {
            mainHandler.post { listener.onBinderReceived() }
        }
    }

    private fun dispatchBinderDead() {
        for (listener in deadListeners) {
            mainHandler.post { listener.onBinderDead() }
        }
    }

    private fun dispatchPermissionResult(requestCode: Int, grantResult: Int, capabilities: Int) {
        for (listener in permissionListeners) {
            mainHandler.post { listener.onPermissionResult(requestCode, grantResult, capabilities) }
        }
    }
}
