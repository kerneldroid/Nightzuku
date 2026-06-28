package rikka.shizuku.nightdog

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ServiceManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * NightDog — ultra-lightweight watchdog for Shizuku server.
 *
 * Architecture (AOSP Watchdog.java pattern):
 * - Server pushes heartbeat via beat() every 5s
 * - NightDog checks if beat arrived within timeout
 * - linkToDeath on critical binders (zero-polling death detection)
 * - Rare pingBinder fallback (60s) for edge cases
 *
 * Compatible with Android 7.1+ (API 25+) via app_process hidden APIs.
 */
object NightDog {

    private const val TAG = "NightDog"

    private val started = AtomicBoolean(false)
    private var watchdogThread: HandlerThread? = null
    private var handler: Handler? = null
    private val healthCheckRunnable = Runnable { checkHealth() }

    // Heartbeat state (AOSP Watchdog.java pattern)
    private val lastBeatTime = AtomicLong(0L)
    private var timeoutMs = 60_000L

    // linkToDeath state
    private val bindings = ConcurrentHashMap<String, IBinder>()
    private val deathRecipients = ConcurrentHashMap<String, IBinder.DeathRecipient>()
    private val rebindAttempts = ConcurrentHashMap<String, Int>()

    fun start(timeoutMs: Long = 60_000L, pollIntervalMs: Long = 60_000L) {
        if (!started.compareAndSet(false, true)) return

        this.timeoutMs = timeoutMs

        watchdogThread = HandlerThread("nightdog").apply { start() }
        handler = Handler(watchdogThread!!.looper)

        // Setup linkToDeath on critical services
        linkToServices("package", "activity", "user", "appops")

        // Start periodic liveness check (rare, 60s fallback)
        handler?.postDelayed(healthCheckRunnable, pollIntervalMs)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return

        handler?.removeCallbacks(healthCheckRunnable)
        deathRecipients.forEach { (name, recipient) ->
            bindings[name]?.let { binder ->
                try { binder.unlinkToDeath(recipient, 0) } catch (_: Exception) {}
            }
        }
        bindings.clear()
        deathRecipients.clear()
        rebindAttempts.clear()
        lastBeatTime.set(0L)

        watchdogThread?.quitSafely()
        watchdogThread = null
        handler = null
    }

    /**
     * Server calls this every 5s from its main thread.
     * If main thread is stuck, beat() won't be called → NightDog detects timeout.
     */
    fun beat() {
        lastBeatTime.set(SystemClock.uptimeMillis())
    }

    fun isStarted(): Boolean = started.get()

    // --- linkToDeath monitoring (zero-polling) ---

    private fun linkToServices(vararg names: String) {
        for (name in names) {
            val binder = ServiceManager.getService(name) ?: continue
            linkTo(name, binder)
        }
    }

    private fun linkTo(name: String, binder: IBinder) {
        val recipient = IBinder.DeathRecipient {
            Log.e(TAG, "Service died: $name")
            bindings.remove(name)
            deathRecipients.remove(name)
            rebindAttempts.remove(name)
            // Rebind after delay
            handler?.postDelayed({ rebind(name) }, 2000)
        }
        try {
            binder.linkToDeath(recipient, 0)
            bindings[name] = binder
            deathRecipients[name] = recipient
            rebindAttempts[name] = 0
        } catch (e: Exception) {
            Log.e(TAG, "linkToDeath($name) failed", e)
        }
    }

    private fun rebind(name: String) {
        if (!started.get()) return

        val attempts = rebindAttempts.getOrDefault(name, 0)
        if (attempts >= 10) {
            Log.e(TAG, "Service $name: max rebind attempts reached, giving up")
            rebindAttempts.remove(name)
            return
        }
        rebindAttempts[name] = attempts + 1

        val binder = ServiceManager.getService(name)
        if (binder != null && binder != bindings[name]) {
            Log.i(TAG, "Service recovered: $name")
            linkTo(name, binder)
        } else if (binder == null) {
            // Exponential backoff: 5s, 10s, 20s, ... up to 60s
            val delay = minOf(5000L * (1L shl attempts), 60_000L)
            handler?.postDelayed({ rebind(name) }, delay)
        }
    }

    // --- Health check (rare fallback) ---

    private fun checkHealth() {
        if (!started.get()) return

        // Check heartbeat (AOSP Watchdog.java pattern)
        val beat = lastBeatTime.get()
        if (beat > 0) {
            val elapsed = SystemClock.uptimeMillis() - beat
            if (elapsed > timeoutMs) {
                Log.e(TAG, "Heartbeat overdue by ${elapsed}ms, server may be stuck")
                // Recovery: exit server, let starter restart it
                stop()
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
        }

        // Verify linkToDeath bindings still valid (rare edge cases)
        for ((name, binder) in bindings) {
            try {
                if (!binder.pingBinder()) {
                    Log.w(TAG, "Service $name not responding (fallback check)")
                    bindings.remove(name)
                    deathRecipients.remove(name)
                    handler?.postDelayed({ rebind(name) }, 2000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking $name", e)
            }
        }

        // Next check in 60s
        handler?.postDelayed(healthCheckRunnable, 60_000L)
    }
}
