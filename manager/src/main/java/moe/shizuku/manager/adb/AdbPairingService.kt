package moe.shizuku.manager.adb

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Observer
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import rikka.core.ktx.unsafeLazy

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val notificationChannel = "adb_pairing"

        private const val tag = "AdbPairingService"

        private const val foregroundNotificationId = 1
        private const val resultNotificationId = 2
        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"
        private const val hostKey = "paring_host"
        private const val portKey = "paring_port"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(startAction)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(stopAction)
        }

        private fun replyIntent(context: Context, host: String, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java)
                .setAction(replyAction)
                .putExtra(hostKey, host)
                .putExtra(portKey, port)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pairingInFlight = AtomicBoolean(false)
    private var adbMdns: AdbMdns? = null

    private val observer = Observer<Pair<String, Int>> { pair ->
        val (host, port) = pair
        Log.i(tag, "Pairing service host: $host, port: $port")
        if (port <= 0 || pairingInFlight.get()) return@Observer

        val notification = createInputNotification(host, port)
        getSystemService(NotificationManager::class.java).notify(foregroundNotificationId, notification)
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> {
                onStart()
            }
            replyAction -> {
                val code = (RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: "")
                    .toString()
                    .trim()
                val host = intent.getStringExtra(hostKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1 && code.isNotEmpty() && host.isNotEmpty()) {
                    onInput(code, host, port)
                } else {
                    onStart()
                }
            }
            stopAction -> {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                startForeground(
                    foregroundNotificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } catch (e: Throwable) {
                Log.e(tag, "startForeground failed", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException
                ) {
                    getSystemService(NotificationManager::class.java).notify(foregroundNotificationId, notification)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
        adbMdns = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
        serviceScope.cancel()
    }

    private fun onStart(): Notification {
        getSystemService(NotificationManager::class.java).cancel(resultNotificationId)
        startSearch()
        return searchingNotification
    }

    private fun onInput(code: String, host: String, port: Int): Notification {
        if (!pairingInFlight.compareAndSet(false, true)) {
            return workingNotification
        }
        stopSearch()

        serviceScope.launch {
            try {
                val key = try {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                } catch (e: Throwable) {
                    e.printStackTrace()
                    handleResult(false, AdbKeyException(e))
                    return@launch
                }

                runCatching {
                    AdbPairingClient(host, port, code, key).use { client ->
                        client.start()
                    }
                }.onFailure {
                    handleResult(false, it)
                }.onSuccess {
                    handleResult(it, null)
                }
            } finally {
                pairingInFlight.set(false)
            }
        }

        return workingNotification
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val title: String
        val text: String?

        if (success) {
            Log.i(tag, "Pair succeed")

            title = getString(R.string.notification_adb_pairing_succeed_title)
            text = getString(R.string.notification_adb_pairing_succeed_text)

            stopSearch()
        } else {
            title = getString(R.string.notification_adb_pairing_failed_title)

            text = when (exception) {
                is ConnectException -> {
                    getString(R.string.cannot_connect_port)
                }
                is AdbInvalidPairingCodeException -> {
                    getString(R.string.paring_code_is_wrong)
                }
                is AdbKeyException -> {
                    getString(R.string.adb_error_key_store)
                }
                else -> {
                    exception?.let { Log.getStackTraceString(it) }
                }
            }

            if (exception != null) {
                Log.w(tag, "Pair failed", exception)
            } else {
                Log.w(tag, "Pair failed")
            }
        }

        // Post the terminal outcome on a separate notification ID so stopForeground(STOP_FOREGROUND_REMOVE)
        // cannot race with NotificationManager and swallow the result banner.
        getSystemService(NotificationManager::class.java).notify(
            resultNotificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(R.color.notification))
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(title)
                .setContentText(text)
                .build()
        )
        stopSelf()
    }

    private val stopNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_stop_searching),
            pendingIntent
        )
            .build()
    }

    private fun replyNotificationAction(host: String, port: Int): Notification.Action {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel(getString(R.string.dialog_adb_pairing_paring_code))
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, host, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_input_paring_code),
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private val searchingNotification by unsafeLazy {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(host: String, port: Int): Notification {
        return Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .addAction(replyNotificationAction(host, port))
            .build()
    }

    private val workingNotification by unsafeLazy {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
