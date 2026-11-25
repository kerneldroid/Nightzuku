package moe.shizuku.manager.adb

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val observer: Observer<Pair<String, Int>>
) {

    private enum class State {
        IDLE,
        STARTING,
        STARTED,
        STOPPING
    }

    private val appContext: Context = context.applicationContext ?: context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdManager: NsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue()
    ) { runnable ->
        Thread(runnable, "AdbMdns-Worker").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    private var state: State = State.IDLE
    private var wantRunning: Boolean = false
    private var serviceName: String? = null
    private var activeListener: DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // API 30-33 serialized resolveService() queue to prevent FAILURE_ALREADY_ACTIVE
    private val pendingResolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolveInFlight: Boolean = false

    // API 34+ ServiceInfoCallback registry keyed by serviceName
    private val activeServiceCallbacks = LinkedHashMap<String, Any>()

    fun start() {
        runOnMain {
            wantRunning = true
            if (state == State.IDLE) {
                beginDiscoveryLocked()
            }
        }
    }

    fun stop() {
        runOnMain {
            wantRunning = false
            clearResolutionStateLocked()
            when (state) {
                State.STARTED -> {
                    val listener = activeListener
                    if (listener != null) {
                        state = State.STOPPING
                        try {
                            nsdManager.stopServiceDiscovery(listener)
                        } catch (t: Throwable) {
                            Log.w(TAG, "stopServiceDiscovery failed", t)
                            activeListener = null
                            state = State.IDLE
                            releaseMulticastLockLocked()
                        }
                    } else {
                        state = State.IDLE
                        releaseMulticastLockLocked()
                    }
                }
                State.STARTING -> {
                    // Discovery start is in flight; onDiscoveryStart will immediately stop it
                    state = State.STOPPING
                }
                else -> {
                    releaseMulticastLockLocked()
                }
            }
        }
    }

    private fun beginDiscoveryLocked() {
        acquireMulticastLockLocked()
        val listener = DiscoveryListener(this)
        activeListener = listener
        state = State.STARTING
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.w(TAG, "discoverServices failed", t)
            activeListener = null
            state = State.IDLE
            releaseMulticastLockLocked()
        }
    }

    private fun acquireMulticastLockLocked() {
        if (multicastLock?.isHeld == true) return
        val lock = runCatching {
            wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { t ->
            Log.w(TAG, "Failed to acquire WifiManager.MulticastLock", t)
        }.getOrNull()
        if (lock != null) {
            multicastLock = lock
        }
    }

    private fun releaseMulticastLockLocked() {
        val lock = multicastLock ?: return
        multicastLock = null
        runCatching {
            if (lock.isHeld) {
                lock.release()
            }
        }.onFailure { t ->
            Log.w(TAG, "Failed to release WifiManager.MulticastLock", t)
        }
    }

    private fun clearResolutionStateLocked() {
        pendingResolveQueue.clear()
        resolveInFlight = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && activeServiceCallbacks.isNotEmpty()) {
            val callbacks = activeServiceCallbacks.values.toList()
            activeServiceCallbacks.clear()
            for (cb in callbacks) {
                unregisterServiceInfoCallback34(cb)
            }
        } else {
            activeServiceCallbacks.clear()
        }
    }

    private fun onDiscoveryStart(listener: DiscoveryListener) {
        runOnMain {
            if (listener !== activeListener) return@runOnMain
            if (!wantRunning || state == State.STOPPING) {
                state = State.STOPPING
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (t: Throwable) {
                    Log.w(TAG, "stopServiceDiscovery after deferred stop failed", t)
                    activeListener = null
                    state = State.IDLE
                    releaseMulticastLockLocked()
                }
            } else {
                state = State.STARTED
            }
        }
    }

    private fun onDiscoveryStartFailed(listener: DiscoveryListener) {
        runOnMain {
            if (listener !== activeListener) return@runOnMain
            activeListener = null
            state = State.IDLE
            clearResolutionStateLocked()
            releaseMulticastLockLocked()
        }
    }

    private fun onDiscoveryStop(listener: DiscoveryListener) {
        runOnMain {
            if (listener !== activeListener) return@runOnMain
            activeListener = null
            state = State.IDLE
            clearResolutionStateLocked()
            if (wantRunning) {
                beginDiscoveryLocked()
            } else {
                releaseMulticastLockLocked()
            }
        }
    }

    private fun onDiscoveryStopFailed(listener: DiscoveryListener) {
        runOnMain {
            if (listener !== activeListener) return@runOnMain
            activeListener = null
            state = State.IDLE
            clearResolutionStateLocked()
            releaseMulticastLockLocked()
        }
    }

    private fun onServiceFound(listener: DiscoveryListener, info: NsdServiceInfo) {
        runOnMain {
            if (listener !== activeListener || !wantRunning) return@runOnMain
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerServiceInfoCallback34(listener, info)
            } else {
                enqueueLegacyResolveLocked(listener, info)
            }
        }
    }

    private fun onServiceLost(listener: DiscoveryListener, info: NsdServiceInfo) {
        runOnMain {
            if (listener !== activeListener || !wantRunning) return@runOnMain
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val removed = info.serviceName?.let { activeServiceCallbacks.remove(it) }
                if (removed != null) {
                    unregisterServiceInfoCallback34(removed)
                }
            }
            if (info.serviceName == serviceName) {
                observer.onChanged("" to -1)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerServiceInfoCallback34(listener: DiscoveryListener, info: NsdServiceInfo) {
        val name = info.serviceName ?: return
        if (activeServiceCallbacks.containsKey(name)) return

        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "ServiceInfoCallback registration failed for $name: error=$errorCode")
                runOnMain {
                    if (activeServiceCallbacks[name] === this) {
                        activeServiceCallbacks.remove(name)
                    }
                }
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                onServiceResolved(listener, serviceInfo)
            }

            override fun onServiceLost() {
                runOnMain {
                    if (listener !== activeListener || !wantRunning) return@runOnMain
                    if (name == serviceName) {
                        observer.onChanged("" to -1)
                    }
                }
            }

            override fun onServiceInfoCallbackUnregistered() {
                Log.v(TAG, "ServiceInfoCallback unregistered for $name")
            }
        }

        try {
            activeServiceCallbacks[name] = callback
            nsdManager.registerServiceInfoCallback(info, callbackExecutor, callback)
        } catch (t: Throwable) {
            activeServiceCallbacks.remove(name)
            Log.w(TAG, "registerServiceInfoCallback failed for $name, falling back to legacy resolve", t)
            enqueueLegacyResolveLocked(listener, info)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterServiceInfoCallback34(callback: Any) {
        val typed = callback as? NsdManager.ServiceInfoCallback ?: return
        runCatching {
            nsdManager.unregisterServiceInfoCallback(typed)
        }.onFailure { t ->
            Log.w(TAG, "unregisterServiceInfoCallback failed", t)
        }
    }

    private fun enqueueLegacyResolveLocked(listener: DiscoveryListener, info: NsdServiceInfo) {
        pendingResolveQueue.addLast(info)
        drainLegacyResolveQueueLocked(listener)
    }

    @Suppress("DEPRECATION")
    private fun drainLegacyResolveQueueLocked(listener: DiscoveryListener) {
        if (resolveInFlight || listener !== activeListener || !wantRunning) return
        val next = pendingResolveQueue.removeFirstOrNull() ?: return
        resolveInFlight = true
        try {
            nsdManager.resolveService(next, ResolveListener(this, listener))
        } catch (t: Throwable) {
            Log.w(TAG, "resolveService failed for ${next.serviceName}", t)
            resolveInFlight = false
            drainLegacyResolveQueueLocked(listener)
        }
    }

    private fun onLegacyResolveFinished(listener: DiscoveryListener) {
        runOnMain {
            resolveInFlight = false
            drainLegacyResolveQueueLocked(listener)
        }
    }

    private fun onServiceResolved(listener: DiscoveryListener, resolvedService: NsdServiceInfo) {
        val port = resolvedService.port
        if (port <= 0) return

        val candidateHosts = extractCandidateHosts(resolvedService)
        if (candidateHosts.isEmpty()) return

        callbackExecutor.execute {
            val localHost = candidateHosts.firstOrNull { host ->
                isLocalAddress(host) && isPortListening(host, port)
            } ?: return@execute

            runOnMain {
                if (listener !== activeListener || !wantRunning) return@runOnMain
                serviceName = resolvedService.serviceName
                observer.onChanged(localHost to port)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractCandidateHosts(info: NsdServiceInfo): List<String> {
        val hosts = ArrayList<String>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (addr in info.hostAddresses) {
                addr.hostAddress?.takeIf { it.isNotEmpty() }?.let(hosts::add)
            }
        }
        if (hosts.isEmpty()) {
            info.host?.hostAddress?.takeIf { it.isNotEmpty() }?.let(hosts::add)
        }
        return hosts
    }

    private fun isLocalAddress(host: String): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { host == it.hostAddress }
                } == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPortListening(host: String, port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress(host, port), 1)
            false
        }
    } catch (_: IOException) {
        true
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.onDiscoveryStart(this)
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onDiscoveryStartFailed(this)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onDiscoveryStopFailed(this)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            adbMdns.onServiceFound(this, serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.onServiceLost(this, serviceInfo)
        }
    }

    @Suppress("DEPRECATION")
    internal class ResolveListener(
        private val adbMdns: AdbMdns,
        private val owner: DiscoveryListener
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, error=$errorCode")
            adbMdns.onLegacyResolveFinished(owner)
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(owner, nsdServiceInfo)
            adbMdns.onLegacyResolveFinished(owner)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
        const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val MULTICAST_LOCK_TAG = "nightzuku:adb_mdns"
        private const val API_ANDROID_17 = 37

        /**
         * On Android 17+ (API 37) with targetSdk >= 37, NsdManager and LAN sockets require
         * the runtime ACCESS_LOCAL_NETWORK permission; otherwise discovery fails with EPERM.
         */
        @JvmStatic
        fun hasLocalNetworkPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < API_ANDROID_17) {
                return true
            }
            return context.checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
