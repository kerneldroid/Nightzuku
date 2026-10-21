package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private var state: State = State.IDLE
    private var wantRunning: Boolean = false
    private var serviceName: String? = null
    private var activeListener: DiscoveryListener? = null

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
                        }
                    } else {
                        state = State.IDLE
                    }
                }
                State.STARTING -> {
                    // Discovery start is in flight; onDiscoveryStart will immediately stop it
                    state = State.STOPPING
                }
                else -> {}
            }
        }
    }

    private fun beginDiscoveryLocked() {
        val listener = DiscoveryListener(this)
        activeListener = listener
        state = State.STARTING
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.w(TAG, "discoverServices failed", t)
            activeListener = null
            state = State.IDLE
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
        }
    }

    private fun onDiscoveryStop(listener: DiscoveryListener) {
        runOnMain {
            if (listener !== activeListener) return@runOnMain
            activeListener = null
            state = State.IDLE
            if (wantRunning) {
                beginDiscoveryLocked()
            }
        }
    }

    private fun onDiscoveryStopFailed(listener: DiscoveryListener) {
        runOnMain {
            if (listener !== activeListener) return@runOnMain
            activeListener = null
            state = State.IDLE
        }
    }

    private fun onServiceFound(listener: DiscoveryListener, info: NsdServiceInfo) {
        runOnMain {
            if (listener !== activeListener || !wantRunning) return@runOnMain
            try {
                nsdManager.resolveService(info, ResolveListener(this, listener))
            } catch (t: Throwable) {
                Log.w(TAG, "resolveService failed", t)
            }
        }
    }

    private fun onServiceLost(listener: DiscoveryListener, info: NsdServiceInfo) {
        runOnMain {
            if (listener !== activeListener || !wantRunning) return@runOnMain
            if (info.serviceName == serviceName) {
                observer.onChanged("" to -1)
            }
        }
    }

    private fun onServiceResolved(listener: DiscoveryListener, resolvedService: NsdServiceInfo) {
        val host = resolvedService.host?.hostAddress ?: return
        val port = resolvedService.port
        if (port <= 0) return

        val isLocal = isLocalAddress(host) && isPortListening(host, port)
        if (!isLocal) return

        runOnMain {
            if (listener !== activeListener || !wantRunning) return@runOnMain
            serviceName = resolvedService.serviceName
            observer.onChanged(host to port)
        }
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
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onDiscoveryStartFailed(this)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
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

    internal class ResolveListener(
        private val adbMdns: AdbMdns,
        private val owner: DiscoveryListener
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {}

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(owner, nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
