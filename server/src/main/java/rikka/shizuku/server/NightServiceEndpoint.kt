package rikka.shizuku.server

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.common.util.OsUtils
import moe.shizuku.server.IShizukuApplication
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.util.Logger

class NightServiceEndpoint(
    private val service: ShizukuService
) : Binder() {

    private val logger = Logger("NightEndpoint")
    private val nightCallbacks = ConcurrentHashMap<Int, IBinder>()
    private val nightCallbackDeaths = ConcurrentHashMap<Int, IBinder.DeathRecipient>()
    private val execPool = Executors.newCachedThreadPool { r ->
        Thread(r, "night-exec").apply { isDaemon = true }
    }

    init {
        attachInterface(null, NightProtocol.DESCRIPTOR)
    }

    override fun getInterfaceDescriptor(): String = NightProtocol.DESCRIPTOR

    fun hasNightCallback(pid: Int): Boolean = nightCallbacks.containsKey(pid)

    fun notifyPermissionChanged(uid: Int, pid: Int, allowed: Boolean, rationale: Boolean, capabilities: Int): Boolean {
        val cb = nightCallbacks[pid] ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.CALLBACK_DESCRIPTOR)
            data.writeInt(if (allowed) 1 else 0)
            data.writeInt(if (rationale) 1 else 0)
            data.writeInt(capabilities)
            cb.transact(NightProtocol.CALLBACK_ON_PERMISSION_CHANGED, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            logger.w(t, "notifyPermissionChanged failed for uid=%d pid=%d", uid, pid)
            false
        } finally {
            data.recycle()
        }
    }

    fun notifyRequestPermissionResult(uid: Int, pid: Int, requestCode: Int, allowed: Boolean, capabilities: Int): Boolean {
        val cb = nightCallbacks[pid] ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.CALLBACK_DESCRIPTOR)
            data.writeInt(requestCode)
            data.writeInt(if (allowed) 1 else 0)
            data.writeInt(capabilities)
            cb.transact(NightProtocol.CALLBACK_ON_REQUEST_PERMISSION_RESULT, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            logger.w(t, "notifyRequestPermissionResult failed for uid=%d pid=%d", uid, pid)
            false
        } finally {
            data.recycle()
        }
    }

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(NightProtocol.DESCRIPTOR)
                return true
            }
            NightProtocol.TRANSACTION_ATTACH -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                handleAttach(data, reply!!)
                return true
            }
            NightProtocol.TRANSACTION_TRANSACT_REMOTE -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                handleTransactRemote(data, reply, flags)
                return true
            }
            NightProtocol.TRANSACTION_EXEC_FAST -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                handleExecFast(data, reply!!)
                return true
            }
            NightProtocol.TRANSACTION_CHECK_PERMISSION -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                handleCheckPermission(reply!!)
                return true
            }
            NightProtocol.TRANSACTION_REQUEST_PERMISSION -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                val requestCode = data.readInt()
                service.requestPermissionFromNightWire(Binder.getCallingUid(), Binder.getCallingPid(), requestCode)
                reply?.writeNoException()
                return true
            }
            NightProtocol.TRANSACTION_GET_SYSTEM_PROPERTY -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                val name = data.readString() ?: ""
                val defaultValue = data.readString()
                val callingUid = Binder.getCallingUid()
                val callingPid = Binder.getCallingPid()
                service.enforceCallingPermission("getSystemProperty")
                TransactGuard.enforceCapability(
                    service.configManager,
                    callingUid,
                    service.checkCallerManagerPermission("getSystemProperty", callingUid, callingPid),
                    NightProtocol.CAP_SYSTEM_PROPS,
                    "getSystemProperty"
                )
                val value = try {
                    SystemProperties.get(name, defaultValue)
                } catch (t: Throwable) {
                    throw IllegalStateException(t.message)
                }
                reply!!.writeNoException()
                reply.writeString(value)
                return true
            }
            NightProtocol.TRANSACTION_SET_SYSTEM_PROPERTY -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                val name = data.readString() ?: ""
                val value = data.readString() ?: ""
                val callingUid = Binder.getCallingUid()
                val callingPid = Binder.getCallingPid()
                service.enforceCallingPermission("setSystemProperty")
                TransactGuard.enforceCapability(
                    service.configManager,
                    callingUid,
                    service.checkCallerManagerPermission("setSystemProperty", callingUid, callingPid),
                    NightProtocol.CAP_SYSTEM_PROPS,
                    "setSystemProperty"
                )
                try {
                    SystemProperties.set(name, value)
                } catch (t: Throwable) {
                    throw IllegalStateException(t.message)
                }
                reply!!.writeNoException()
                return true
            }
            NightProtocol.TRANSACTION_CLAIM_USER_SERVICE -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                val token = data.readString()
                val callingUid = Binder.getCallingUid()
                val callingPid = Binder.getCallingPid()
                if (callingUid != OsUtils.getUid()) {
                    throw SecurityException("claimUserServiceLaunch may only be invoked by the privileged starter uid")
                }
                val claimed = token != null && service.userServiceManager.claimUserServiceLaunch(token, callingPid)
                reply!!.writeNoException()
                reply.writeInt(if (claimed) 1 else 0)
                return true
            }
            NightProtocol.TRANSACTION_GET_UID_CAPABILITIES -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                service.enforceManagerPermission("getUidCapabilities")
                val targetUid = data.readInt()
                val entry = service.configManager.find(targetUid)
                val caps = TransactGuard.effectiveCapabilities(entry)
                reply!!.writeNoException()
                reply.writeInt(caps)
                return true
            }
            NightProtocol.TRANSACTION_SET_UID_CAPABILITIES -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                service.enforceManagerPermission("setUidCapabilities")
                val targetUid = data.readInt()
                val caps = data.readInt() and NightProtocol.MASK_CAPABILITIES
                val packages = PackageManagerApis.getPackagesForUidNoThrow(targetUid).toMutableList()
                service.configManager.update(targetUid, packages, NightProtocol.MASK_CAPABILITIES, caps)
                service.notifyCapabilitiesUpdated(targetUid)
                reply!!.writeNoException()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun unlinkCallback(pid: Int) {
        val oldBinder = nightCallbacks.remove(pid)
        val oldDeath = nightCallbackDeaths.remove(pid)
        if (oldBinder != null && oldDeath != null) {
            try {
                oldBinder.unlinkToDeath(oldDeath, 0)
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleAttach(data: Parcel, reply: Parcel) {
        val callbackBinder = data.readStrongBinder()
        val clientProtocolVersion = data.readInt()
        val packageName = data.readString() ?: throw IllegalArgumentException("packageName is null")

        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        val packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid)
        if (!packages.contains(packageName)) {
            throw SecurityException("Package $packageName does not belong to calling uid $callingUid")
        }

        unlinkCallback(callingPid)
        if (callbackBinder != null) {
            val recipient = IBinder.DeathRecipient {
                if (nightCallbacks.remove(callingPid, callbackBinder)) {
                    nightCallbackDeaths.remove(callingPid)
                }
            }
            try {
                callbackBinder.linkToDeath(recipient, 0)
                nightCallbacks[callingPid] = callbackBinder
                nightCallbackDeaths[callingPid] = recipient
            } catch (_: RemoteException) {
                nightCallbacks.remove(callingPid, callbackBinder)
                nightCallbackDeaths.remove(callingPid, recipient)
            }
        }

        val bridgeApp = object : IShizukuApplication.Stub() {
            override fun bindApplication(bundle: Bundle?) {
                if (bundle == null) return
                val allowed = bundle.getBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, false)
                val rationale = bundle.getBoolean(ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
                val caps = TransactGuard.effectiveCapabilities(service.configManager.find(callingUid))
                notifyPermissionChanged(callingUid, callingPid, allowed, rationale, caps)
            }

            override fun dispatchRequestPermissionResult(requestCode: Int, bundle: Bundle?) {
                val allowed = bundle?.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, false) ?: false
                val caps = TransactGuard.effectiveCapabilities(service.configManager.find(callingUid))
                notifyRequestPermissionResult(callingUid, callingPid, requestCode, allowed, caps)
            }

            override fun showPermissionConfirmation(uid: Int, pid: Int, pkg: String?, code: Int) {}
        }

        val record = service.attachNightClient(callingUid, callingPid, bridgeApp, packageName, clientProtocolVersion)
        val entry = service.configManager.find(callingUid)
        val isManager = service.checkCallerManagerPermission("attach", callingUid, callingPid)
        val grantState = when {
            isManager || (record != null && record.allowed) -> NightProtocol.STATE_GRANTED
            entry?.isDenied() == true -> NightProtocol.STATE_DENIED_PERMANENT
            else -> NightProtocol.STATE_UNDETERMINED
        }
        val capabilities = if (isManager) {
            NightProtocol.MASK_CAPABILITIES
        } else {
            TransactGuard.effectiveCapabilities(entry)
        }

        reply.writeNoException()
        reply.writeInt(OsUtils.getUid())
        reply.writeInt(NightProtocol.PROTOCOL_VERSION)
        reply.writeInt(grantState)
        reply.writeInt(capabilities)
        reply.writeString(OsUtils.getSELinuxContext())
    }

    @Throws(RemoteException::class)
    private fun handleTransactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        service.enforceCallingPermission("transactRemote")
        TransactGuard.enforceCapability(
            service.configManager,
            callingUid,
            service.checkCallerManagerPermission("transactRemote", callingUid, callingPid),
            NightProtocol.CAP_BINDER_PROXY,
            "transactRemote"
        )

        val targetBinder = data.readStrongBinder()
        val targetCode = data.readInt()
        val targetFlags = data.readInt()

        TransactGuard.verifyTargetBinder(targetBinder, service, this)

        val newData = Parcel.obtain()
        try {
            try {
                newData.appendFrom(data, data.dataPosition(), data.dataAvail())
            } catch (t: Throwable) {
                throw IllegalArgumentException("Failed to copy transaction payload", t)
            }
            val identity = Binder.clearCallingIdentity()
            try {
                targetBinder.transact(targetCode, newData, reply, targetFlags)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        } finally {
            newData.recycle()
        }
    }

    private fun handleCheckPermission(reply: Parcel) {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        val isManager = service.checkCallerManagerPermission("checkPermission", callingUid, callingPid)
        val allowed = isManager || service.checkSelfPermission()
        val entry = service.configManager.find(callingUid)
        val rationale = !allowed && (entry?.isDenied() == true)
        val caps = if (isManager) NightProtocol.MASK_CAPABILITIES else TransactGuard.effectiveCapabilities(entry)

        reply.writeNoException()
        reply.writeInt(if (allowed) 1 else 0)
        reply.writeInt(if (rationale) 1 else 0)
        reply.writeInt(caps)
    }

    private fun handleExecFast(data: Parcel, reply: Parcel) {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        service.enforceCallingPermission("execFast")
        TransactGuard.enforceCapability(
            service.configManager,
            callingUid,
            service.checkCallerManagerPermission("execFast", callingUid, callingPid),
            NightProtocol.CAP_SHELL_EXEC,
            "execFast"
        )

        val cmd = data.createStringArray()
        if (cmd.isNullOrEmpty()) {
            throw IllegalArgumentException("Command array must not be empty")
        }
        val env = data.createStringArray()
        val dir = data.readString()
        val timeoutMs = data.readLong().coerceIn(100L, 300_000L)

        val process = try {
            Runtime.getRuntime().exec(cmd, env, if (!dir.isNullOrEmpty()) File(dir) else null)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to start process: ${e.message}", e)
        }

        try {
            process.outputStream.close()
        } catch (_: Throwable) {
        }

        val stdoutFuture = execPool.submit<ByteArray> {
            readBoundedStream(process.inputStream, NightProtocol.EXEC_FAST_MAX_OUTPUT_BYTES)
        }
        val stderrFuture = execPool.submit<ByteArray> {
            readBoundedStream(process.errorStream, NightProtocol.EXEC_FAST_MAX_OUTPUT_BYTES)
        }

        var timedOut = false
        val exitCode = try {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.exitValue()
            } else {
                timedOut = true
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                124
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            stdoutFuture.cancel(true)
            stderrFuture.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for process", e)
        }

        val stdoutBytes = try {
            stdoutFuture.get(1, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            stdoutFuture.cancel(true)
            ByteArray(0)
        }
        val stderrBytes = try {
            stderrFuture.get(1, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            stderrFuture.cancel(true)
            ByteArray(0)
        }

        reply.writeNoException()
        reply.writeInt(exitCode)
        reply.writeInt(if (timedOut) 1 else 0)
        reply.writeByteArray(stdoutBytes)
        reply.writeByteArray(stderrBytes)
    }

    private fun readBoundedStream(input: InputStream, maxBytes: Int): ByteArray {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val read = stream.read(buf)
                if (read < 0) break
                val remaining = maxBytes - out.size()
                if (remaining > 0) {
                    out.write(buf, 0, minOf(read, remaining))
                }
            }
            return out.toByteArray()
        }
    }
}
