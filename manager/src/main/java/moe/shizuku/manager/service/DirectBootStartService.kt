package moe.shizuku.manager.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPortHistory
import moe.shizuku.manager.adb.AdbPortScanner
import moe.shizuku.manager.adb.AdbSettings
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.receiver.AdbBootStartScheduler
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku

/**
 * Direct-boot-aware foreground service that starts the Nightzuku server on boot, including
 * before the first unlock (`ACTION_LOCKED_BOOT_COMPLETED`).
 *
 * `JobScheduler` / `WorkManager` jobs do not execute before user unlock in standard user 0
 * flows. Running a `directBootAware` `connectedDevice` foreground service allows:
 * 1. Immediate loopback startup when a fixed `adb tcpip` port is active.
 * 2. Pre-unlock wireless ADB startup once Wi-Fi associates with a trusted ("Always allow")
 *    network: pre-warming `AdbKey`, toggling `adb_wifi_enabled` 0 -> 1 on API 30-36 (watching
 *    for immediate framework resets via [ContentObserver]), and running [AdbPortScanner] in
 *    parallel with [AdbMdns] so whichever discovers the TLS port first wins.
 * 3. Seamless handoff on `ACTION_BOOT_COMPLETED`: skips restarting if the pre-unlock run
 *    already brought the server up, or hands off to [AdbBootStartScheduler] if unlocked retry
 *    is still needed.
 *
 * Pre-unlock wireless ADB boot architecture credited to Dimlos-jp/Dimzuku and BombPerry/Shizuku.
 */
class DirectBootStartService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null

    private sealed class WirelessAttempt {
        data class Found(val host: String, val port: Int, val source: String) : WirelessAttempt()
        data object Refused : WirelessAttempt()
        data object NoPort : WirelessAttempt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (runJob?.isActive == true) {
            Log.d(TAG, "DirectBootStartService already running; letting active attempt finish")
            return START_NOT_STICKY
        }

        val requestedBootCount = intent?.getIntExtra(EXTRA_BOOT_COUNT, -1) ?: -1
        val bootCount = if (requestedBootCount != -1) requestedBootCount else currentBootCount()
        running = true
        runJob = scope.launch {
            try {
                runStart(bootCount)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "DirectBootStartService run failed", t)
                handOffAfterFailure(bootCount)
            } finally {
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        ensureNotificationChannel()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
    }

    private suspend fun runStart(bootCount: Int) {
        if (!ShizukuSettings.isStartOnBootEnabled()) {
            return
        }

        if (bootCount != -1 && ShizukuSettings.getLastHandledBootCount() == bootCount) {
            if (EnvironmentUtils.isUserUnlocked(this)) {
                NightDogClient.onUserUnlocked(applicationContext)
            }
            return
        }

        val unlocked = EnvironmentUtils.isUserUnlocked(this)
        if (unlocked) {
            val sentThisBoot = bootCount != -1 && ShizukuSettings.getLastBootStartSentCount() == bootCount
            val waitMs = if (sentThisBoot) ALREADY_RUNNING_WAIT_MS else QUICK_RUNNING_CHECK_MS
            if (waitForBinder(waitMs)) {
                Log.i(TAG, "Server already running on unlock (bootCount=$bootCount)")
                markBootHandled(bootCount)
                NightDogClient.onUserUnlocked(applicationContext)
                return
            }
        } else if (Shizuku.pingBinder()) {
            Log.i(TAG, "Server binder already alive while locked (bootCount=$bootCount)")
            if (bootCount != -1) {
                ShizukuSettings.setLastBootStartSentCount(bootCount)
                ShizukuSettings.setLastHandledBootCount(bootCount)
            }
            return
        }

        val launchMode = ShizukuSettings.resolveBootLaunchMethod(EnvironmentUtils.isRooted())
        val sent = when (launchMode) {
            LaunchMethod.ROOT -> startWithRoot()
            LaunchMethod.ADB -> startWithAdb()
            else -> false
        }
        if (!sent) {
            if (EnvironmentUtils.isUserUnlocked(this) && !Shizuku.pingBinder()) {
                AdbBootStartScheduler.schedule(applicationContext, bootCount)
            }
            return
        }

        if (bootCount != -1) {
            ShizukuSettings.setLastBootStartSentCount(bootCount)
        }

        if (!EnvironmentUtils.isUserUnlocked(this)) {
            if (waitForBinder(LOCKED_CONFIRM_MS)) {
                Log.i(TAG, "Server binder confirmed before unlock (bootCount=$bootCount)")
                if (bootCount != -1) {
                    ShizukuSettings.setLastHandledBootCount(bootCount)
                }
                if (EnvironmentUtils.isUserUnlocked(this)) {
                    NightDogClient.onUserUnlocked(applicationContext)
                }
                return
            }
            if (!EnvironmentUtils.isUserUnlocked(this)) {
                Log.i(TAG, "Start command sent before unlock; BOOT_COMPLETED will confirm (bootCount=$bootCount)")
                return
            }
            Log.i(TAG, "User unlocked while awaiting binder; extending confirmation window (bootCount=$bootCount)")
        }

        if (waitForBinder(UNLOCKED_CONFIRM_MS)) {
            Log.i(TAG, "Server binder confirmed after start (bootCount=$bootCount)")
            markBootHandled(bootCount)
            NightDogClient.onUserUnlocked(applicationContext)
        } else {
            throw IllegalStateException("Server binder did not arrive within ${UNLOCKED_CONFIRM_MS}ms")
        }
    }

    private fun startWithRoot(): Boolean {
        if (!Shell.getShell().isRoot) {
            Shell.getCachedShell()?.close()
            throw IllegalStateException("Launch mode is ROOT but no root shell is available")
        }
        val result = Shell.cmd(Starter.internalCommand).exec()
        return result.isSuccess
    }

    private suspend fun startWithAdb(): Boolean {
        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        val hasTcpPort = tcpPort in 1..65535
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || hasTcpPort
        if (!supported) {
            return false
        }

        if (hasTcpPort) {
            Log.i(TAG, "Starting Nightzuku via direct ADB TCP port $tcpPort (locked=${!EnvironmentUtils.isUserUnlocked(this)})")
            val key = buildAdbKey()
            connectAndRunStarter(AdbPortScanner.LOOPBACK_IP, tcpPort, key, requireTls = false)
            return true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }

        val isRedactedPlatform = AdbSettings.isReadRedactedPlatform()
        if (!isRedactedPlatform &&
            checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not granted; deferring to unlocked scheduler")
            return false
        }

        val keyDeferred: Deferred<AdbKey?> = scope.async {
            runCatching { buildAdbKey() }.getOrNull()
        }

        try {
            val cr = contentResolver
            if (!isRedactedPlatform) {
                runCatching { Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1) }
                runCatching { Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L) }
                // Pre-reset a stale 1 while waiting for Wi-Fi so the later write of 1 triggers AdbDebuggingManager.
                if (runCatching { Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) }.getOrDefault(0) == 1) {
                    runCatching { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0) }
                }
            }

            val wifiUp = awaitWifi(WIFI_WAIT_MS)
            Log.d(TAG, "DirectBoot awaitWifi=$wifiUp (locked=${!EnvironmentUtils.isUserUnlocked(this)})")

            val (host, port) = if (isRedactedPlatform) {
                discoverRedactedPlatformEndpoint()
            } else {
                enableWifiDebuggingAndDiscoverPort(cr)
            }

            val key = keyDeferred.await() ?: buildAdbKey()
            connectAndRunStarter(host, port, key, requireTls = true)
            AdbPortHistory.record(this, port)
            return true
        } finally {
            keyDeferred.cancel()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun discoverRedactedPlatformEndpoint(): Pair<String, Int> {
        val timeoutMs = ShizukuSettings.getBootDiscoveryTimeoutSeconds() * 1000L
        val found = Channel<Pair<String, Int>>(Channel.CONFLATED)
        val scanOrder = AdbPortScanner.buildOrder(AdbPortHistory.load(this))
        val scanner = AdbPortScanner(
            log = { Log.d(TAG, it) },
            order = scanOrder,
            requireTlsOnly = true
        ) { port ->
            found.trySend(AdbPortScanner.LOOPBACK_IP to port)
        }
        val mdns = if (AdbMdns.hasLocalNetworkPermission(this)) {
            AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { (host, port) ->
                if (port in 1..65535 && host.isNotEmpty()) {
                    found.trySend(host to port)
                }
            }
        } else {
            null
        }

        scanner.start()
        mdns?.start()
        return try {
            withTimeoutOrNull(timeoutMs) { found.receive() }
                ?: throw IllegalStateException("Wireless ADB endpoint not found on Android 17+ within ${timeoutMs}ms")
        } finally {
            scanner.stop()
            mdns?.stop()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun enableWifiDebuggingAndDiscoverPort(
        cr: ContentResolver,
    ): Pair<String, Int> {
        val found = Channel<WirelessAttempt.Found>(Channel.CONFLATED)
        val mdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { (host, port) ->
            if (port in 1..65535 && host.isNotEmpty()) {
                found.trySend(WirelessAttempt.Found(host, port, "mDNS"))
            }
        }
        val scanOrder = AdbPortScanner.buildOrder(AdbPortHistory.load(this))
        val scanner = AdbPortScanner(
            log = { Log.d(TAG, it) },
            order = scanOrder,
            requireTlsOnly = true
        ) { port ->
            found.trySend(WirelessAttempt.Found(AdbPortScanner.LOOPBACK_IP, port, "scan"))
        }

        mdns.start()
        try {
            val deadline = SystemClock.elapsedRealtime() + WIRELESS_BUDGET_MS
            var stageIdx = 0
            var refusals = 0
            while (SystemClock.elapsedRealtime() < deadline && stageIdx < STAGED_WINDOWS_MS.size) {
                if (Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1) {
                    Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0)
                    delay(RETOGGLE_GAP_MS)
                }

                // Drain any stale emission from before this toggle.
                found.tryReceive()

                val windowMs = STAGED_WINDOWS_MS[stageIdx]
                when (val result = tryEnableAndAwaitPort(cr, windowMs, found, scanner)) {
                    is WirelessAttempt.Found -> {
                        Log.i(TAG, "Discovered ADB port ${result.port} via ${result.source} (stage=${stageIdx + 1}, refusals=$refusals)")
                        return result.host to result.port
                    }
                    WirelessAttempt.Refused -> {
                        refusals++
                        if (refusals > MAX_REFUSALS) {
                            throw IllegalStateException("Wireless debugging refused $refusals times")
                        }
                        val pauseMs = if (refusals <= REFUSED_SHORT_RETRIES) {
                            REFUSED_RETRY_SHORT_MS
                        } else {
                            REFUSED_RETRY_LONG_MS
                        }
                        Log.d(TAG, "adb_wifi_enabled reset by framework (refusal #$refusals); retrying in ${pauseMs}ms")
                        delay(pauseMs)
                        if (!isOnWifi()) {
                            awaitWifi(REFUSED_RETRY_LONG_MS)
                        }
                    }
                    WirelessAttempt.NoPort -> {
                        stageIdx++
                        Log.d(TAG, "No port within ${windowMs}ms; advancing to stage ${stageIdx + 1}")
                    }
                }
            }
            throw IllegalStateException("Wireless ADB port not discovered before timeout")
        } finally {
            scanner.stop()
            mdns.stop()
        }
    }

    private suspend fun tryEnableAndAwaitPort(
        cr: ContentResolver,
        windowMs: Long,
        found: Channel<WirelessAttempt.Found>,
        scanner: AdbPortScanner,
    ): WirelessAttempt {
        return withTimeoutOrNull(windowMs) {
            callbackFlow {
                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        if (Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 0) {
                            trySend(WirelessAttempt.Refused)
                        }
                    }
                }
                cr.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI_ENABLED), false, observer)

                val forwardJob = launch {
                    trySend(found.receive())
                }

                Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1)
                scanner.start()

                awaitClose {
                    forwardJob.cancel()
                    scanner.stop()
                    cr.unregisterContentObserver(observer)
                }
            }.first()
        } ?: WirelessAttempt.NoPort
    }

    private fun buildAdbKey(): AdbKey =
        AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku").also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                it.sslContext
            }
            it.adbPublicKey
        }

    private fun connectAndRunStarter(host: String, port: Int, key: AdbKey, requireTls: Boolean) {
        AdbClient(host, port, key, requireTls = requireTls).use { client ->
            client.connect()
            client.shellCommand(Starter.internalCommand, null)
        }
    }

    private fun isOnWifi(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    private fun hasNonLocalAddress(lp: LinkProperties?): Boolean {
        return lp?.linkAddresses?.any {
            !it.address.isLinkLocalAddress && !it.address.isLoopbackAddress
        } == true
    }

    private suspend fun awaitWifi(timeoutMs: Long): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return false
            val active = cm.activeNetwork
            val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                hasNonLocalAddress(cm.getLinkProperties(active))
            ) {
                return true
            }

            withTimeoutOrNull(timeoutMs) {
                callbackFlow {
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (hasNonLocalAddress(cm.getLinkProperties(network))) {
                                trySend(true)
                            } else {
                                launch {
                                    delay(WIFI_IP_FALLBACK_MS)
                                    trySend(true)
                                }
                            }
                        }

                        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                            if (hasNonLocalAddress(lp)) {
                                trySend(true)
                            }
                        }
                    }
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()
                    cm.registerNetworkCallback(request, callback)
                    awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
                }.first()
            } == true
        } catch (_: SecurityException) {
            delay(timeoutMs.coerceAtMost(15_000L))
            false
        } catch (t: Throwable) {
            Log.w(TAG, "awaitWifi failed", t)
            false
        }
    }

    private suspend fun waitForBinder(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Shizuku.pingBinder()) return true
            delay(200L)
        }
        return Shizuku.pingBinder()
    }

    private suspend fun handOffAfterFailure(bootCount: Int) {
        if (!EnvironmentUtils.isUserUnlocked(this)) {
            Log.d(TAG, "Pre-unlock boot attempt failed; BOOT_COMPLETED will handle unlocked retry")
            return
        }
        if (waitForBinder(QUICK_RUNNING_CHECK_MS)) {
            markBootHandled(bootCount)
            NightDogClient.onUserUnlocked(applicationContext)
            return
        }
        AdbBootStartScheduler.schedule(applicationContext, bootCount)
    }

    private fun markBootHandled(bootCount: Int) {
        if (bootCount != -1) {
            ShizukuSettings.setLastHandledBootCount(bootCount)
        }
    }

    private fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_service_status),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_service_starting))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = AppConstants.TAG
        private const val CHANNEL_ID = "direct_boot_start"
        private const val NOTIFICATION_ID = 4212
        private const val EXTRA_BOOT_COUNT = "boot_count"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

        private const val WIFI_WAIT_MS = 90_000L
        private const val WIFI_IP_FALLBACK_MS = 2_500L
        private const val WIRELESS_BUDGET_MS = 90_000L
        private const val RETOGGLE_GAP_MS = 150L
        private const val REFUSED_RETRY_SHORT_MS = 1_000L
        private const val REFUSED_RETRY_LONG_MS = 3_000L
        private const val REFUSED_SHORT_RETRIES = 5
        private const val MAX_REFUSALS = 15
        private val STAGED_WINDOWS_MS = longArrayOf(1_500L, 1_500L, 2_500L, 4_500L, 10_000L)

        private const val ALREADY_RUNNING_WAIT_MS = 6_000L
        private const val QUICK_RUNNING_CHECK_MS = 500L
        private const val LOCKED_CONFIRM_MS = 5_000L
        private const val UNLOCKED_CONFIRM_MS = 15_000L

        @Volatile
        private var running = false

        @JvmStatic
        fun isRunning(): Boolean = running

        @JvmStatic
        fun start(context: Context, bootCount: Int = -1): Boolean {
            val appCtx = context.applicationContext ?: context
            val intent = Intent(appCtx, DirectBootStartService::class.java).apply {
                putExtra(EXTRA_BOOT_COUNT, bootCount)
            }
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
                true
            }.onFailure { t ->
                Log.w(TAG, "Failed to start DirectBootStartService (bootCount=$bootCount)", t)
            }.getOrDefault(false)
        }
    }
}
