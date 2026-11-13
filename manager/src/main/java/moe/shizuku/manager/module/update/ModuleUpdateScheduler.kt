package moe.shizuku.manager.module.update

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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.ModulesActivity
import moe.shizuku.manager.module.catalog.TokenStore
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.utils.Logger.LOGGER

/**
 * OS-scheduled background update checker for ADB Modules (`JobScheduler` + `JobService`).
 *
 * Architectural separation of concerns:
 * 1. **Checking (`ModuleUpdateScheduler`)**: Runs unprivileged HTTP checks against GitHub Releases /
 *    `updateJson` / `raw.githubusercontent.com` on an OS-managed schedule (`JobScheduler`) with native
 *    network constraints (`NETWORK_TYPE_UNMETERED` or `NETWORK_TYPE_ANY`), surviving app backgrounding
 *    without requiring an active `shizuku_server` binder or pulling in `androidx.work` / `Room` SQLite bloat.
 * 2. **Applying Updates & Privileged Lifecycle (`NightDogClient`)**: When updates are found and
 *    auto-update is enabled, `ModuleUpdateScheduler` hands off the target modules to `NightDogClient.applyUpdates()`,
 *    which installs the updated packages and restarts privileged `service.sh` hooks over the active
 *    `shizuku_server` binder (or queues them until `OnBinderReceived` fires).
 */
object ModuleUpdateScheduler {

    const val JOB_ID_MODULE_UPDATE_CHECK = 4200
    private const val UPDATE_NOTIFICATION_CHANNEL_ID = "nightzuku_module_updates"
    private const val UPDATE_NOTIFICATION_ID = 4201
    private const val PREF_LAST_UPDATES = "adb_modules_last_updates"

    private const val SIX_HOURS_MS = 6L * 60L * 60L * 1000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val WEEK_MS = 7L * DAY_MS

    private val checkMutex = Mutex()

    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    private val _updatesByModule = MutableStateFlow<Map<String, UpdateResult>>(emptyMap())
    val updatesByModule: StateFlow<Map<String, UpdateResult>> = _updatesByModule.asStateFlow()

    /**
     * Synchronizes the OS `JobScheduler` periodic task with the user's current [ModuleSettings].
     */
    fun syncSchedule(context: Context) {
        val appCtx = context.applicationContext
        restoreCachedUpdates(appCtx)
        val scheduler = appCtx.getSystemService(JobScheduler::class.java) ?: return

        val freq = ModuleSettings.getUpdateFrequency()
        val intervalMs = when (freq) {
            ModuleSettings.UpdateFrequency.MANUAL,
            ModuleSettings.UpdateFrequency.ON_START -> {
                scheduler.cancel(JOB_ID_MODULE_UPDATE_CHECK)
                return
            }
            ModuleSettings.UpdateFrequency.SIX_HOURS -> SIX_HOURS_MS
            ModuleSettings.UpdateFrequency.DAILY -> DAY_MS
            ModuleSettings.UpdateFrequency.WEEKLY -> WEEK_MS
        }

        val networkType = if (ModuleSettings.isUpdateWifiOnly()) {
            JobInfo.NETWORK_TYPE_UNMETERED
        } else {
            JobInfo.NETWORK_TYPE_ANY
        }

        // Avoid rescheduling if an identical job is already pending
        val existing = scheduler.getPendingJob(JOB_ID_MODULE_UPDATE_CHECK)
        if (existing != null &&
            existing.isPeriodic &&
            existing.intervalMillis == intervalMs &&
            existing.networkType == networkType
        ) {
            return
        }

        val component = ComponentName(appCtx, ModuleUpdateJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID_MODULE_UPDATE_CHECK, component)
            .setPeriodic(intervalMs)
            .setRequiredNetworkType(networkType)
            .setPersisted(true)
            .build()

        runCatching {
            scheduler.schedule(jobInfo)
        }.onFailure { t ->
            LOGGER.w(t, "ModuleUpdateScheduler: failed to schedule periodic update job")
        }
    }

    fun isNetworkAllowedForBackgroundUpdate(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        if (ModuleSettings.isUpdateWifiOnly()) {
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        return true
    }

    /**
     * Checks installed modules for available updates and optionally triggers privileged
     * auto-update installation via [NightDogClient.applyUpdates].
     */
    suspend fun checkUpdates(
        context: Context,
        force: Boolean = false,
        onServerStart: Boolean = false
    ): Map<String, UpdateResult> = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            _isCheckingUpdates.value = true
            try {
                val appCtx = context.applicationContext
                val now = System.currentTimeMillis()
                val modules = AdbModuleManager.listModules(appCtx)
                if (modules.isEmpty()) {
                    _updatesByModule.value = emptyMap()
                    return@withLock emptyMap()
                }

                val isDue = force || (
                    ModuleSettings.isUpdateCheckDue(now, onServerStart = onServerStart) &&
                        isNetworkAllowedForBackgroundUpdate(appCtx)
                    )

                if (!isDue) {
                    val cached = _updatesByModule.value
                    val filtered = LinkedHashMap<String, UpdateResult>()
                    for (module in modules) {
                        cached[module.id]?.let { filtered[module.id] = it }
                    }
                    _updatesByModule.value = filtered
                    return@withLock filtered
                }

                val token = TokenStore.getToken(appCtx)?.trim()?.takeIf { it.isNotEmpty() }
                val checker = UpdateChecker.getInstance()
                val updatesMap = LinkedHashMap<String, UpdateResult>()

                for (module in modules) {
                    if (!force && ModuleSettings.isModuleUpdateIgnored(module.id)) {
                        continue
                    }
                    val updateRes = runCatching {
                        checker.checkUpdate(module, token)
                    }.getOrNull()
                    if (updateRes != null) {
                        updatesMap[module.id] = updateRes
                    }
                }
                ModuleSettings.setLastUpdateCheckTime(now)

                var autoUpdatedNames: List<String> = emptyList()
                if (!force && ModuleSettings.isAutoUpdateEnabled()) {
                    val targets = modules.mapNotNull { mod ->
                        if (ModuleSettings.isModuleUpdateIgnored(mod.id)) return@mapNotNull null
                        val upd = updatesMap[mod.id] ?: return@mapNotNull null
                        if (upd.hasUpdate) mod to upd else null
                    }
                    if (targets.isNotEmpty()) {
                        val applied = NightDogClient.applyUpdates(appCtx, targets)
                        autoUpdatedNames = applied.map { "${it.name} (${it.version ?: "new"})" }
                        for (updatedMod in applied) {
                            val prev = updatesMap[updatedMod.id] ?: continue
                            updatesMap[updatedMod.id] = prev.copy(
                                hasUpdate = false,
                                currentVersion = updatedMod.version,
                                currentVersionCode = updatedMod.versionCode
                            )
                        }
                    }
                }

                _updatesByModule.value = updatesMap
                persistUpdates(appCtx, updatesMap)
                NightDogClient.notifyUpdatesChanged(updatesMap, autoUpdatedNames)

                if (ModuleSettings.isUpdateNotificationsEnabled()) {
                    val pendingUpdates = updatesMap.filter { (id, res) ->
                        res.hasUpdate && !ModuleSettings.isModuleUpdateIgnored(id)
                    }
                    if (autoUpdatedNames.isNotEmpty() || (!force && pendingUpdates.isNotEmpty())) {
                        postUpdateNotification(
                            context = appCtx,
                            autoUpdated = autoUpdatedNames,
                            pendingCount = pendingUpdates.size
                        )
                    }
                }

                updatesMap
            } finally {
                _isCheckingUpdates.value = false
            }
        }
    }

    fun markModuleUpdated(module: AdbModule) {
        val current = _updatesByModule.value.toMutableMap()
        val existing = current[module.id]
        if (existing != null) {
            current[module.id] = existing.copy(
                hasUpdate = false,
                currentVersion = module.version,
                currentVersionCode = module.versionCode
            )
            _updatesByModule.value = current
            persistUpdates(appContext(), current)
            NightDogClient.notifyUpdatesChanged(current, emptyList())
        }
    }

    private fun appContext(): Context? =
        runCatching { moe.shizuku.manager.application.applicationContext }.getOrNull()

    private fun restoreCachedUpdates(context: Context) {
        if (_updatesByModule.value.isNotEmpty()) return
        val raw = runCatching {
            ShizukuSettings.getPreferences().getString(PREF_LAST_UPDATES, null)
        }.getOrNull() ?: return
        val restored = runCatching {
            val array = org.json.JSONArray(raw)
            val map = LinkedHashMap<String, UpdateResult>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("moduleId")
                map[id] = UpdateResult(
                    moduleId = id,
                    hasUpdate = obj.optBoolean("hasUpdate"),
                    currentVersion = obj.optString("currentVersion").takeIf { it.isNotEmpty() && it != "null" },
                    currentVersionCode = if (obj.has("currentVersionCode")) obj.getLong("currentVersionCode") else null,
                    latestVersion = obj.optString("latestVersion").takeIf { it.isNotEmpty() && it != "null" },
                    latestVersionCode = if (obj.has("latestVersionCode")) obj.getLong("latestVersionCode") else null,
                    downloadUrl = obj.optString("downloadUrl").takeIf { it.isNotEmpty() && it != "null" },
                    changelog = obj.optString("changelog").takeIf { it.isNotEmpty() && it != "null" }
                )
            }
            map
        }.getOrNull() ?: return
        _updatesByModule.value = restored
    }

    private fun persistUpdates(context: Context?, updates: Map<String, UpdateResult>) {
        if (context == null) return
        runCatching {
            val array = org.json.JSONArray()
            for (result in updates.values) {
                array.put(org.json.JSONObject().apply {
                    put("moduleId", result.moduleId)
                    put("hasUpdate", result.hasUpdate)
                    put("currentVersion", result.currentVersion)
                    put("currentVersionCode", result.currentVersionCode)
                    put("latestVersion", result.latestVersion)
                    put("latestVersionCode", result.latestVersionCode)
                    put("downloadUrl", result.downloadUrl)
                    put("changelog", result.changelog)
                })
            }
            ShizukuSettings.getPreferences().edit()
                .putString(PREF_LAST_UPDATES, array.toString())
                .apply()
        }
    }

    private fun postUpdateNotification(
        context: Context,
        autoUpdated: List<String>,
        pendingCount: Int
    ) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    UPDATE_NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.update_settings_notifications_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(context, ModulesActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

            val title: String
            val body: String
            if (autoUpdated.isNotEmpty()) {
                title = context.getString(R.string.modules_auto_updated_notification_title, autoUpdated.size)
                body = autoUpdated.joinToString(", ")
            } else {
                title = context.getString(R.string.modules_updates_available_notification_title, pendingCount)
                body = context.getString(R.string.modules_updates_available_notification_body)
            }

            val notification = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
            .build()

            nm.notify(UPDATE_NOTIFICATION_ID, notification)
        }
    }
}

/**
 * Android [JobService] invoked by the OS [JobScheduler] to check ADB module updates
 * with native network constraint enforcement.
 */
class ModuleUpdateJobService : JobService() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        activeJob = jobScope.launch {
            try {
                ModuleUpdateScheduler.checkUpdates(
                    context = applicationContext,
                    force = false,
                    onServerStart = false
                )
            } catch (t: Throwable) {
                LOGGER.w(t, "ModuleUpdateJobService: background update check failed")
            } finally {
                if (params != null) {
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        activeJob = null
        return true
    }
}
