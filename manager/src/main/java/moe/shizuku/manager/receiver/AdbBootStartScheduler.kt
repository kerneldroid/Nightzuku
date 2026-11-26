package moe.shizuku.manager.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPortHistory
import moe.shizuku.manager.adb.AdbPortScanner
import moe.shizuku.manager.adb.AdbSettings
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku

/**
 * Schedules wireless ADB boot startup via Android's `JobScheduler` instead of blocking inside
 * `BootCompleteReceiver.goAsync()`.
 *
 * Advantages over the legacy BroadcastReceiver flow:
 * - Waits for an unmetered network (Wi-Fi or Ethernet) via OS network constraints instead of
 *   racing DHCP / network readiness.
 * - Eliminates the 10-second BroadcastReceiver ANR ceiling with a configurable discovery window.
 * - Uses OS-managed exponential backoff retries without persisting stale boot-count extras across
 *   subsequent reboots.
 * - Supports direct `adb tcpip` startup when `service.adb.tcp.port` / `persist.adb.tcp.port` is set.
 * - Waits for `ACTION_USER_PRESENT` on Android 11–16 when the keyguard is locked so the system
 *   does not reset `adb_wifi_enabled` to `0` mid-discovery.
 * - Supports Android 17+ native ADB Wi-Fi 2.0 zero-touch startup without gating on redacted
 *   `Settings.Global.getInt("adb_wifi_enabled")` reads.
 */
object AdbBootStartScheduler {

    const val JOB_ID_ADB_BOOT_START = 4210
    internal const val EXTRA_BOOT_COUNT = "boot_count"
    private const val BACKOFF_INITIAL_MS = 15_000L

    @JvmStatic
    fun schedule(context: Context, bootCount: Int): Boolean {
        val hasTcpPort = EnvironmentUtils.getAdbTcpPort() in 1..65535
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !hasTcpPort) {
            return false
        }
        val appCtx = context.applicationContext ?: context
        val scheduler = appCtx.getSystemService(JobScheduler::class.java) ?: return false

        val extras = PersistableBundle().apply {
            putInt(EXTRA_BOOT_COUNT, bootCount)
        }

        val builder = JobInfo.Builder(
            JOB_ID_ADB_BOOT_START,
            ComponentName(appCtx, AdbBootStartJobService::class.java)
        )
            .setExtras(extras)
            .setBackoffCriteria(BACKOFF_INITIAL_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setRequiredNetworkType(
                if (hasTcpPort) {
                    // Direct loopback adb tcpip startup (127.0.0.1:$tcpPort) does not require a network.
                    JobInfo.NETWORK_TYPE_NONE
                } else {
                    // Unmetered covers both Wi-Fi and Ethernet (Android TV) and ensures IP/DHCP readiness.
                    JobInfo.NETWORK_TYPE_UNMETERED
                }
            )

        val result = runCatching {
            scheduler.schedule(builder.build())
        }.onFailure { t ->
            Log.e(AppConstants.TAG, "Failed to schedule AdbBootStartJobService (bootCount=$bootCount)", t)
        }.getOrDefault(JobScheduler.RESULT_FAILURE)

        Log.d(AppConstants.TAG, "Scheduled AdbBootStartJobService (bootCount=$bootCount, result=$result)")
        return result == JobScheduler.RESULT_SUCCESS
    }

    @JvmStatic
    fun cancel(context: Context) {
        val appCtx = context.applicationContext ?: context
        appCtx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID_ADB_BOOT_START)
    }
}

class AdbBootStartJobService : JobService() {

    private enum class Outcome {
        DONE,
        RETRY
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && EnvironmentUtils.getAdbTcpPort() <= 0) {
            return false
        }

        val bootCount = params.extras.getInt(AdbBootStartScheduler.EXTRA_BOOT_COUNT, -1)
        activeJob?.cancel()
        activeJob = scope.launch {
            val outcome = try {
                executeAdbStart(bootCount)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(AppConstants.TAG, "AdbBootStartJobService failed unexpectedly", t)
                Outcome.RETRY
            }
            jobFinished(params, outcome == Outcome.RETRY)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob?.cancel()
        activeJob = null
        // Reschedule if the network dropped or the system preempted the job before completion.
        return !Shizuku.pingBinder()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJob?.cancel()
        activeJob = null
        scope.cancel()
    }

    private suspend fun executeAdbStart(bootCount: Int): Outcome {
        if (Shizuku.pingBinder()) {
            markBootHandled(bootCount)
            clearFallbackNotification()
            return Outcome.DONE
        }
        if (bootCount != -1 && ShizukuSettings.getLastHandledBootCount() == bootCount) {
            return Outcome.DONE
        }
        if (!ShizukuSettings.isStartOnBootEnabled()) {
            return Outcome.DONE
        }

        // Prefer direct TCP connection when `adb tcpip` is active (no TLS or mDNS required).
        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        if (tcpPort in 1..65535) {
            if (connectAndStartServer("127.0.0.1", tcpPort, requireTls = false)) {
                markBootHandled(bootCount)
                clearFallbackNotification()
                Log.i(AppConstants.TAG, "Nightzuku server started via ADB TCP on boot (127.0.0.1:$tcpPort)")
                return Outcome.DONE
            }
            Log.w(AppConstants.TAG, "Direct ADB TCP boot start on port $tcpPort failed")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return handleAttemptFailure(bootCount, "ADB TCP boot start on port $tcpPort failed")
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Outcome.DONE
        }

        val hasLocalNetPerm = AdbMdns.hasLocalNetworkPermission(this)
        val discoveryTimeoutMs = ShizukuSettings.getBootDiscoveryTimeoutSeconds() * 1000L
        val prepare = AdbSettings.prepareWirelessAdbForBoot(this)
        if (prepare == AdbSettings.BootPrepareResult.UNAVAILABLE) {
            Log.w(
                AppConstants.TAG,
                "Wireless ADB settings could not be enabled automatically; running bounded discovery probe"
            )
            val probeTimeoutMs = minOf(discoveryTimeoutMs, UNAVAILABLE_MDNS_PROBE_TIMEOUT_MS)
            val probedEndpoint = discoverLocalAdbEndpoint(probeTimeoutMs, enableMdns = hasLocalNetPerm)
            if (probedEndpoint == null) {
                if (!hasLocalNetPerm) {
                    showLocalNetworkPermissionNotification()
                } else if (ShizukuSettings.isBootFallbackNotificationEnabled()) {
                    showManualWirelessToggleNotification()
                }
                return Outcome.DONE
            }
            val (host, port) = probedEndpoint
            AdbPortHistory.record(this, port)
            val started = connectAndStartServer(host, port, requireTls = true)
            return if (started) {
                markBootHandled(bootCount)
                clearFallbackNotification()
                Log.i(AppConstants.TAG, "Nightzuku server started via wireless ADB on boot ($host:$port)")
                Outcome.DONE
            } else {
                handleAttemptFailure(bootCount, "ADB starter command did not bring up binder")
            }
        }

        val endpoint = discoverLocalAdbEndpoint(discoveryTimeoutMs, enableMdns = hasLocalNetPerm)
        if (endpoint == null) {
            if (!hasLocalNetPerm) {
                Log.w(AppConstants.TAG, "ACCESS_LOCAL_NETWORK not granted and loopback scan found no ADB port")
                showLocalNetworkPermissionNotification()
                return Outcome.DONE
            }
            if (prepare == AdbSettings.BootPrepareResult.ENABLED &&
                !AdbSettings.isWirelessAdbEnabled(contentResolver)
            ) {
                return handleAttemptFailure(
                    bootCount,
                    "AdbService reset adb_wifi_enabled during discovery"
                )
            }
            return handleAttemptFailure(bootCount, "Wireless ADB discovery timed out")
        }

        val (host, port) = endpoint
        AdbPortHistory.record(this, port)
        val started = connectAndStartServer(host, port, requireTls = true)

        return if (started) {
            markBootHandled(bootCount)
            clearFallbackNotification()
            Log.i(AppConstants.TAG, "Nightzuku server started via wireless ADB on boot ($host:$port)")
            Outcome.DONE
        } else {
            handleAttemptFailure(bootCount, "ADB starter command did not bring up binder")
        }
    }

    private suspend fun connectAndStartServer(
        host: String,
        port: Int,
        requireTls: Boolean
    ): Boolean {
        return try {
            val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
            val key = AdbKey(keystore, "shizuku")
            AdbClient(host, port, key, requireTls = requireTls).use { client ->
                val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                    if (cause != null) {
                        runCatching { client.close() }
                    }
                }
                try {
                    client.connect()
                    client.shellCommand(Starter.internalCommand, null)
                } finally {
                    cancelHandle.dispose()
                }
            }
            waitForServerBinder(BINDER_WAIT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(AppConstants.TAG, "ADB boot start connection to $host:$port failed", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun discoverLocalAdbEndpoint(
        timeoutMs: Long,
        enableMdns: Boolean = true,
    ): Pair<String, Int>? {
        val discovered = CompletableDeferred<Pair<String, Int>>()
        val scanner = AdbPortScanner(
            log = { Log.d(AppConstants.TAG, it) },
            order = AdbPortScanner.buildOrder(AdbPortHistory.load(this)),
            requireTlsOnly = true
        ) { port ->
            discovered.complete(AdbPortScanner.LOOPBACK_IP to port)
        }
        val adbMdns = if (enableMdns) {
            AdbMdns(this, AdbMdns.TLS_CONNECT) { (host, port) ->
                if (port in 1..65535 && host.isNotEmpty()) {
                    discovered.complete(host to port)
                }
            }
        } else {
            null
        }
        scanner.start()
        adbMdns?.start()
        return try {
            withTimeoutOrNull(timeoutMs) { discovered.await() }
        } finally {
            scanner.stop()
            adbMdns?.stop()
        }
    }

    private suspend fun waitForServerBinder(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Shizuku.pingBinder()) {
                return true
            }
            delay(250L)
        }
        return Shizuku.pingBinder()
    }

    private fun handleAttemptFailure(bootCount: Int, reason: String): Outcome {
        val maxRetries = ShizukuSettings.getBootMaxRetries()
        val attempts = incrementAttemptCount(bootCount)
        if (attempts < maxRetries) {
            Log.w(AppConstants.TAG, "ADB boot start attempt $attempts/$maxRetries failed: $reason")
            return Outcome.RETRY
        }
        Log.w(AppConstants.TAG, "ADB boot start exhausted $maxRetries retries: $reason")
        if (ShizukuSettings.isBootFallbackNotificationEnabled()) {
            showManualWirelessToggleNotification()
        }
        return Outcome.DONE
    }

    private fun incrementAttemptCount(bootCount: Int): Int {
        val prefs = ShizukuSettings.getPreferences()
        val storedBoot = prefs.getInt(PREF_RETRY_BOOT_COUNT, Int.MIN_VALUE)
        val next = if (storedBoot == bootCount) {
            prefs.getInt(PREF_RETRY_ATTEMPTS, 0) + 1
        } else {
            1
        }
        prefs.edit()
            .putInt(PREF_RETRY_BOOT_COUNT, bootCount)
            .putInt(PREF_RETRY_ATTEMPTS, next)
            .apply()
        return next
    }

    private fun markBootHandled(bootCount: Int) {
        if (bootCount != -1) {
            ShizukuSettings.setLastHandledBootCount(bootCount)
        }
        ShizukuSettings.getPreferences().edit()
            .remove(PREF_RETRY_BOOT_COUNT)
            .remove(PREF_RETRY_ATTEMPTS)
            .apply()
    }

    private fun ensureNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_service_status),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun showLocalNetworkPermissionNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureNotificationChannel(nm)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_BOOT_FALLBACK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.boot_adb_local_network_title))
            .setContentText(getString(R.string.boot_adb_local_network_message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.boot_adb_local_network_message)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID_BOOT_FALLBACK, notification) }
    }

    private fun showManualWirelessToggleNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureNotificationChannel(nm)
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        }
        val pi = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_BOOT_FALLBACK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.boot_adb_manual_toggle_title))
            .setContentText(getString(R.string.boot_adb_manual_toggle_message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.boot_adb_manual_toggle_message)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID_BOOT_FALLBACK, notification) }
    }

    private fun clearFallbackNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID_BOOT_FALLBACK)
    }

    companion object {
        private const val BINDER_WAIT_TIMEOUT_MS = 5_000L
        private const val UNAVAILABLE_MDNS_PROBE_TIMEOUT_MS = 8_000L
        private const val CHANNEL_ID = "adb_boot_start_status"
        private const val NOTIFICATION_ID_BOOT_FALLBACK = 4211
        private const val PREF_RETRY_BOOT_COUNT = "adb_boot_retry_boot_count"
        private const val PREF_RETRY_ATTEMPTS = "adb_boot_retry_attempts"
    }
}
