package rikka.shizuku.server

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.RemoteException
import android.os.SystemProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuServiceConnection
import rikka.hidden.compat.PackageManagerApis
import rikka.rish.RishHost
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.api.RemoteProcessHolder
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils
import rikka.shizuku.server.util.ServerSmartLogCollector

private fun Process.destroyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
    } else {
        destroy()
    }
}

private fun Process.waitForCompat(timeoutMs: Long): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            Thread.sleep(50)
        }
    }
    return try {
        exitValue()
        true
    } catch (_: IllegalThreadStateException) {
        false
    }
}

class NightServiceEndpoint(
    private val service: ShizukuService
) : Binder() {

    private data class ExecCallKey(val uid: Int, val pid: Int, val callId: Long)

    private class ActiveExecHandle(
        val callId: Long,
        val callingUid: Int,
        val callingPid: Int
    ) {
        @Volatile
        var pid: Int = -1

        @Volatile
        var process: Process? = null

        val cancelled = AtomicBoolean(false)
        val clientDied = AtomicBoolean(false)
        val notified = AtomicBoolean(false)

        fun terminate(fromClientDeath: Boolean = false) {
            if (fromClientDeath) {
                clientDied.set(true)
            }
            if (!cancelled.compareAndSet(false, true)) return
            val targetPid = pid
            if (targetPid > 0) {
                RemoteProcessHolder.killProcessGroup(targetPid)
            }
            process?.destroyCompat()
        }
    }

    private val logger = Logger("NightEndpoint")
    private val EXEC_POOL_SIZE = 4
    private val EXEC_QUEUE_CAPACITY = 64
    private val nightCallbacks = ConcurrentHashMap<Int, IBinder>()
    private val nightCallbackDeaths = ConcurrentHashMap<Int, IBinder.DeathRecipient>()
    private val callbackUids = ConcurrentHashMap<Int, Int>()
    private val activeExecCalls = ConcurrentHashMap<ExecCallKey, ActiveExecHandle>()
    private val execPool = ThreadPoolExecutor(
        EXEC_POOL_SIZE,
        EXEC_POOL_SIZE,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(EXEC_QUEUE_CAPACITY),
        { r -> Thread(r, "night-exec").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

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

    fun notifyExecResult(
        pid: Int,
        callId: Long,
        exitCode: Int,
        timedOut: Boolean,
        stdout: ByteArray,
        stderr: ByteArray
    ): Boolean {
        val cb = nightCallbacks[pid] ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.CALLBACK_DESCRIPTOR)
            data.writeLong(callId)
            data.writeInt(exitCode)
            data.writeInt(if (timedOut) 1 else 0)
            data.writeByteArray(stdout)
            data.writeByteArray(stderr)
            cb.transact(NightProtocol.CALLBACK_ON_EXEC_RESULT, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            logger.w(t, "notifyExecResult failed for pid=%d callId=%d", pid, callId)
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
            NightProtocol.TRANSACTION_EXEC_ASYNC -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                handleExecAsync(data)
                return true
            }
            NightProtocol.TRANSACTION_CANCEL_EXEC -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                handleCancelExec(data)
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
                    "getSystemProperty",
                    service.isTemporaryGrantActive(callingUid)
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
                    "setSystemProperty",
                    service.isTemporaryGrantActive(callingUid)
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
                val caps = TransactGuard.effectiveCapabilities(
                    entry,
                    service.configManager.isAccessPaused(),
                    service.isTemporaryGrantActive(targetUid)
                )
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
            NightProtocol.TRANSACTION_GET_ACCESS_PAUSED -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                service.enforceManagerPermission("getAccessPaused")
                reply!!.writeNoException()
                reply.writeInt(if (service.configManager.isAccessPaused()) 1 else 0)
                return true
            }
            NightProtocol.TRANSACTION_SET_ACCESS_PAUSED -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                service.enforceManagerPermission("setAccessPaused")
                val paused = data.readInt() != 0
                service.setAccessPaused(paused)
                reply!!.writeNoException()
                return true
            }
            NightProtocol.TRANSACTION_GET_SERVER_DIAGNOSTICS -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                service.enforceCallingPermission("getServerDiagnostics")
                val isManager = service.checkCallerManagerPermission(
                    "getServerDiagnostics",
                    Binder.getCallingUid(),
                    Binder.getCallingPid()
                )
                val smartBundle = if (isManager) ServerSmartLogCollector.buildSmartLogsBundle() else null
                val diagnostics = Bundle().apply {
                    putInt("uid", OsUtils.getUid())
                    putInt("pid", OsUtils.getPid())
                    putInt("protocolVersion", NightProtocol.PROTOCOL_VERSION)
                    putInt("minProtocolVersion", NightProtocol.MIN_PROTOCOL_VERSION)
                    putString("seContext", OsUtils.getSELinuxContext())
                    putBoolean("accessPaused", service.configManager.isAccessPaused())
                    putBoolean("nightDogEnabled", service.configManager.getNightDogEnabled())
                    putInt("activeClientsCount", service.clientManager.getActiveClients().size)
                    if (smartBundle != null) {
                        putInt("smartSnapshotCount", smartBundle.getInt("snapshotCount", 0))
                        putString("serializedSmartSnapshots", smartBundle.getString("serializedSnapshots"))
                        putString("qprCompatAudit", smartBundle.getString("qprCompatAudit"))
                    }
                }
                reply!!.writeNoException()
                reply.writeBundle(diagnostics)
                return true
            }
            NightProtocol.TRANSACTION_ADD_USER_SERVICE -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                val connBinder = data.readStrongBinder()
                val conn = IShizukuServiceConnection.Stub.asInterface(connBinder)
                val options = data.readBundle(Bundle::class.java.classLoader) ?: Bundle()
                val res = service.addUserService(conn, options)
                reply!!.writeNoException()
                reply.writeInt(res)
                return true
            }
            NightProtocol.TRANSACTION_REMOVE_USER_SERVICE -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                val connBinder = data.readStrongBinder()
                val conn = IShizukuServiceConnection.Stub.asInterface(connBinder)
                val options = data.readBundle(Bundle::class.java.classLoader) ?: Bundle()
                val res = service.removeUserService(conn, options)
                reply!!.writeNoException()
                reply.writeInt(res)
                return true
            }
            NightProtocol.TRANSACTION_NEW_PROCESS -> {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                val cmd = data.createStringArray()
                if (cmd.isNullOrEmpty()) {
                    throw IllegalArgumentException("Command array must not be empty")
                }
                val env = data.createStringArray()
                val dir = data.readString()
                val proc = service.newProcess(cmd, env, dir)
                val exitFd = runCatching { proc.exitFd }.getOrNull()
                reply!!.writeNoException()
                reply.writeStrongBinder(proc.asBinder())
                if (exitFd != null) {
                    reply.writeInt(1)
                    exitFd.use {
                        it.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                    }
                } else {
                    reply.writeInt(0)
                }
                return true
            }
        }
        if (service.onTransactRish(code, data, reply, flags)) {
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun cancelActiveExecCallsForPid(pid: Int) {
        for ((key, handle) in ArrayList(activeExecCalls.entries)) {
            if (handle.callingPid == pid) {
                activeExecCalls.remove(key, handle)
                handle.terminate(fromClientDeath = true)
            }
        }
    }

    private fun unlinkCallback(pid: Int) {
        callbackUids.remove(pid)
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

        val handshakeStatus = when {
            clientProtocolVersion < NightProtocol.MIN_PROTOCOL_VERSION -> NightProtocol.ATTACH_STATUS_CLIENT_TOO_OLD
            clientProtocolVersion > NightProtocol.PROTOCOL_VERSION -> NightProtocol.ATTACH_STATUS_SERVER_TOO_OLD
            else -> NightProtocol.ATTACH_STATUS_OK
        }

        if (handshakeStatus != NightProtocol.ATTACH_STATUS_OK) {
            unlinkCallback(callingPid)
            reply.writeNoException()
            reply.writeInt(handshakeStatus)
            reply.writeInt(NightProtocol.PROTOCOL_VERSION)
            reply.writeInt(NightProtocol.MIN_PROTOCOL_VERSION)
            return
        }

        unlinkCallback(callingPid)
        if (callbackBinder != null) {
            val recipient = IBinder.DeathRecipient {
                if (nightCallbacks.remove(callingPid, callbackBinder)) {
                    nightCallbackDeaths.remove(callingPid)
                }
                callbackUids.remove(callingPid, callingUid)
                cancelActiveExecCallsForPid(callingPid)
                service.onClientProcessDied(callingUid, callingPid)
            }
            try {
                callbackBinder.linkToDeath(recipient, 0)
                nightCallbacks[callingPid] = callbackBinder
                nightCallbackDeaths[callingPid] = recipient
                callbackUids[callingPid] = callingUid
            } catch (_: RemoteException) {
                nightCallbacks.remove(callingPid, callbackBinder)
                nightCallbackDeaths.remove(callingPid, recipient)
                callbackUids.remove(callingPid, callingUid)
            }
        }

        val fallbackBinder = Binder()
        val bridgeApp = object : IShizukuApplication {
            override fun asBinder(): IBinder = callbackBinder ?: fallbackBinder

            override fun bindApplication(bundle: Bundle?) {
                if (bundle == null) return
                val allowed = bundle.getBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, false)
                val rationale = bundle.getBoolean(ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
                val caps = TransactGuard.effectiveCapabilities(
                    service.configManager.find(callingUid),
                    service.configManager.isAccessPaused(),
                    service.isTemporaryGrantActive(callingUid)
                )
                notifyPermissionChanged(callingUid, callingPid, allowed, rationale, caps)
            }

            override fun dispatchRequestPermissionResult(requestCode: Int, bundle: Bundle?) {
                val allowed = bundle?.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, false) ?: false
                val caps = TransactGuard.effectiveCapabilities(
                    service.configManager.find(callingUid),
                    service.configManager.isAccessPaused(),
                    service.isTemporaryGrantActive(callingUid)
                )
                notifyRequestPermissionResult(callingUid, callingPid, requestCode, allowed, caps)
            }
        }

        val record = service.attachNightClient(callingUid, callingPid, bridgeApp, packageName, clientProtocolVersion)
        val entry = service.configManager.find(callingUid)
        val isManager = service.checkCallerManagerPermission("attach", callingUid, callingPid)
        val paused = service.configManager.isAccessPaused()
        val grantState = when {
            isManager || (!paused && record != null && record.allowed) -> NightProtocol.STATE_GRANTED
            entry?.isDenied() == true -> NightProtocol.STATE_DENIED_PERMANENT
            else -> NightProtocol.STATE_UNDETERMINED
        }
        val capabilities = if (isManager) {
            NightProtocol.MASK_CAPABILITIES
        } else {
            TransactGuard.effectiveCapabilities(entry, paused, service.isTemporaryGrantActive(callingUid))
        }

        reply.writeNoException()
        reply.writeInt(NightProtocol.ATTACH_STATUS_OK)
        reply.writeInt(NightProtocol.PROTOCOL_VERSION)
        reply.writeInt(NightProtocol.MIN_PROTOCOL_VERSION)
        reply.writeInt(OsUtils.getUid())
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
            "transactRemote",
            service.isTemporaryGrantActive(callingUid)
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
        val paused = service.configManager.isAccessPaused()
        val allowed = isManager || (!paused && service.checkSelfPermission())
        val entry = service.configManager.find(callingUid)
        val rationale = !allowed && (entry?.isDenied() == true)
        val caps = if (isManager) {
            NightProtocol.MASK_CAPABILITIES
        } else {
            TransactGuard.effectiveCapabilities(entry, paused, service.isTemporaryGrantActive(callingUid))
        }

        reply.writeNoException()
        reply.writeInt(if (allowed) 1 else 0)
        reply.writeInt(if (rationale) 1 else 0)
        reply.writeInt(caps)
    }

    private data class CommandOutput(
        val exitCode: Int,
        val timedOut: Boolean,
        val stdout: ByteArray,
        val stderr: ByteArray
    )

    private fun executeCommandBounded(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        timeoutMs: Long,
        handle: ActiveExecHandle? = null
    ): CommandOutput {
        val stdoutBuffer = BoundedBuffer(NightProtocol.EXEC_FAST_MAX_OUTPUT_BYTES)
        val stderrBuffer = BoundedBuffer(NightProtocol.EXEC_FAST_MAX_OUTPUT_BYTES)

        val spawned = RishHost.spawnProcessGroupOrNull(cmd, env, dir)
        if (spawned != null) {
            val pid = spawned.pid
            handle?.pid = pid
            if (handle?.cancelled?.get() == true) {
                RemoteProcessHolder.killProcessGroup(pid)
                runCatching { spawned.stdinPfd.close() }
                runCatching { spawned.stdoutPfd.close() }
                runCatching { spawned.stderrPfd.close() }
                runCatching { RishHost.waitForPid(pid) }
                return CommandOutput(137, false, ByteArray(0), ByteArray(0))
            }

            runCatching { spawned.stdinPfd.close() }

            val stdoutStream = ParcelFileDescriptor.AutoCloseInputStream(spawned.stdoutPfd)
            val stderrStream = ParcelFileDescriptor.AutoCloseInputStream(spawned.stderrPfd)

            val stdoutFuture = execPool.submit {
                drainStreamIntoBuffer(stdoutStream, stdoutBuffer)
            }
            val stderrFuture = execPool.submit {
                drainStreamIntoBuffer(stderrStream, stderrBuffer)
            }
            val waitFuture = execPool.submit<Int> {
                RishHost.waitForPid(pid)
            }

            var timedOut = false
            val exitCode = try {
                waitFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                timedOut = true
                RemoteProcessHolder.killProcessGroup(pid)
                runCatching { stdoutStream.close() }
                runCatching { stderrStream.close() }
                runCatching { waitFuture.get(250, TimeUnit.MILLISECONDS) }
                124
            } catch (e: InterruptedException) {
                RemoteProcessHolder.killProcessGroup(pid)
                runCatching { stdoutStream.close() }
                runCatching { stderrStream.close() }
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting for process", e)
            } catch (e: Throwable) {
                RemoteProcessHolder.killProcessGroup(pid)
                runCatching { stdoutStream.close() }
                runCatching { stderrStream.close() }
                throw IllegalStateException("Process execution failed", e)
            }

            try {
                stdoutFuture.get(250, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                runCatching { stdoutStream.close() }
                stdoutFuture.cancel(true)
            }
            try {
                stderrFuture.get(250, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                runCatching { stderrStream.close() }
                stderrFuture.cancel(true)
            }

            return CommandOutput(
                exitCode = exitCode,
                timedOut = timedOut,
                stdout = stdoutBuffer.toByteArray(),
                stderr = stderrBuffer.toByteArray()
            )
        }

        val process = try {
            Runtime.getRuntime().exec(cmd, env, if (!dir.isNullOrEmpty()) File(dir) else null)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to start process: ${e.message}", e)
        }

        handle?.process = process
        if (handle?.cancelled?.get() == true) {
            process.destroyCompat()
            return CommandOutput(137, false, ByteArray(0), ByteArray(0))
        }

        try {
            process.outputStream.close()
        } catch (_: Throwable) {
        }

        val stdoutFuture = execPool.submit {
            drainStreamIntoBuffer(process.inputStream, stdoutBuffer)
        }
        val stderrFuture = execPool.submit {
            drainStreamIntoBuffer(process.errorStream, stderrBuffer)
        }

        var timedOut = false
        val exitCode = try {
            if (process.waitForCompat(timeoutMs)) {
                process.exitValue()
            } else {
                timedOut = true
                process.destroyCompat()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                124
            }
        } catch (e: InterruptedException) {
            process.destroyCompat()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            stdoutFuture.cancel(true)
            stderrFuture.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for process", e)
        }

        try {
            stdoutFuture.get(250, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            runCatching { process.inputStream.close() }
            stdoutFuture.cancel(true)
        }
        try {
            stderrFuture.get(250, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            runCatching { process.errorStream.close() }
            stderrFuture.cancel(true)
        }

        return CommandOutput(
            exitCode = exitCode,
            timedOut = timedOut,
            stdout = stdoutBuffer.toByteArray(),
            stderr = stderrBuffer.toByteArray()
        )
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
            "execFast",
            service.isTemporaryGrantActive(callingUid)
        )

        val cmd = data.createStringArray()
        if (cmd.isNullOrEmpty()) {
            throw IllegalArgumentException("Command array must not be empty")
        }
        val env = data.createStringArray()
        val dir = data.readString()
        val timeoutMs = data.readLong().coerceIn(100L, 300_000L)

        val out = executeCommandBounded(cmd, env, dir, timeoutMs)

        reply.writeNoException()
        reply.writeInt(out.exitCode)
        reply.writeInt(if (out.timedOut) 1 else 0)
        reply.writeByteArray(out.stdout)
        reply.writeByteArray(out.stderr)
    }


    /**
     * Oneway binder transactions report a sender PID of 0. Resolve the caller from the explicit
     * PID hint sent by newer clients, or fall back to the unique registered callback for the UID.
     * A PID hint is only honoured when it maps back to the calling UID.
     */
    private fun resolveOnewayCallingPid(reportedPid: Int, callingUid: Int): Int {
        val binderPid = Binder.getCallingPid()
        if (binderPid > 0) {
            return binderPid
        }
        if (reportedPid > 0 && callbackUids[reportedPid] == callingUid) {
            return reportedPid
        }
        var match = 0
        for ((pid, uid) in callbackUids) {
            if (uid == callingUid) {
                if (match != 0) {
                    return 0
                }
                match = pid
            }
        }
        return match
    }

    private fun handleExecAsync(data: Parcel) {
        val callingUid = Binder.getCallingUid()
        val callId = data.readLong()
        val cmd = data.createStringArray()
        val env = data.createStringArray()
        val dir = data.readString()
        val timeoutMs = data.readLong().coerceIn(100L, 300_000L)
        val reportedPid = if (data.dataAvail() >= 4) data.readInt() else 0
        val callingPid = resolveOnewayCallingPid(reportedPid, callingUid)

        try {
            service.enforceCallingPermission("execAsync", callingUid, callingPid)
            TransactGuard.enforceCapability(
                service.configManager,
                callingUid,
                service.checkCallerManagerPermission("execAsync", callingUid, callingPid),
                NightProtocol.CAP_SHELL_EXEC,
                "execAsync",
                service.isTemporaryGrantActive(callingUid)
            )
            if (cmd.isNullOrEmpty()) {
                throw IllegalArgumentException("Command array must not be empty")
            }
        } catch (t: Throwable) {
            val errBytes = (t.message ?: "execAsync rejected").toByteArray(StandardCharsets.UTF_8)
            notifyExecResult(callingPid, callId, -1, false, ByteArray(0), errBytes)
            return
        }

        val callKey = ExecCallKey(callingUid, callingPid, callId)
        val handle = ActiveExecHandle(callId, callingUid, callingPid)
        activeExecCalls[callKey] = handle

        execPool.execute {
            try {
                val out = executeCommandBounded(cmd, env, dir, timeoutMs, handle)
                if (!handle.clientDied.get() && handle.notified.compareAndSet(false, true)) {
                    val exitCode = if (handle.cancelled.get() && out.exitCode == 0) 137 else out.exitCode
                    notifyExecResult(callingPid, callId, exitCode, out.timedOut, out.stdout, out.stderr)
                }
            } catch (t: Throwable) {
                if (!handle.clientDied.get() && handle.notified.compareAndSet(false, true)) {
                    val exitCode = if (handle.cancelled.get()) 137 else -1
                    val errBytes = (t.message ?: "execAsync failed").toByteArray(StandardCharsets.UTF_8)
                    notifyExecResult(callingPid, callId, exitCode, false, ByteArray(0), errBytes)
                }
            } finally {
                activeExecCalls.remove(callKey, handle)
            }
        }
    }

    /**
     * Finds an in-flight exec call scoped to the calling client. Call IDs are generated per
     * client process, so the same callId may exist concurrently for different clients.
     */
    private fun findExecCall(
        callingUid: Int,
        callingPid: Int,
        callId: Long,
        isManager: Boolean
    ): Pair<ExecCallKey, ActiveExecHandle>? {
        if (callingPid > 0) {
            val key = ExecCallKey(callingUid, callingPid, callId)
            activeExecCalls[key]?.let { return key to it }
        }
        val matches = activeExecCalls.entries.filter { entry ->
            entry.key.callId == callId && (isManager || entry.key.uid == callingUid)
        }
        return matches.singleOrNull()?.let { it.key to it.value }
    }

    private fun handleCancelExec(data: Parcel) {
        val callingUid = Binder.getCallingUid()
        val callId = data.readLong()
        val reportedPid = if (data.dataAvail() >= 4) data.readInt() else 0
        val callingPid = resolveOnewayCallingPid(reportedPid, callingUid)
        val isManager = service.checkCallerManagerPermission("cancelExec", callingUid, callingPid)
        val entry = findExecCall(callingUid, callingPid, callId, isManager) ?: return
        val (key, handle) = entry
        if (handle.callingUid != callingUid && !isManager) {
            throw SecurityException("Cannot cancel execAsync callId=$callId owned by uid=${handle.callingUid}")
        }
        activeExecCalls.remove(key, handle)
        handle.terminate(fromClientDeath = false)
        if (handle.notified.compareAndSet(false, true)) {
            val errBytes = "Command execution cancelled".toByteArray(StandardCharsets.UTF_8)
            notifyExecResult(handle.callingPid, callId, 137, false, ByteArray(0), errBytes)
        }
    }

    private class BoundedBuffer(private val maxBytes: Int) {
        private val out = ByteArrayOutputStream()

        @Synchronized
        fun append(buf: ByteArray, length: Int) {
            val remaining = maxBytes - out.size()
            if (remaining > 0) {
                out.write(buf, 0, minOf(length, remaining))
            }
        }

        @Synchronized
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun drainStreamIntoBuffer(input: InputStream, target: BoundedBuffer) {
        try {
            input.use { stream ->
                val buf = ByteArray(4096)
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) break
                    if (read > 0) {
                        target.append(buf, read)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
