package moe.shizuku.manager.nightdog

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbSettings
import moe.shizuku.manager.application
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleHealthStatus
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.update.ModuleInstaller
import moe.shizuku.manager.module.update.ModuleUpdateScheduler
import moe.shizuku.manager.module.update.UpdateResult
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Privileged lifecycle and module watchdog coordinator in the Nightzuku Manager.
 *
 * Responsibilities:
 * 1. Synchronizes with the `NightDog` watchdog daemon inside `shizuku_server` over Binder IPC.
 * 2. Executes and supervises privileged background `service.sh` hooks when `shizuku_server` starts or recovers.
 * 3. Inspects local ADB module integrity (`module.prop`, executable bits, WebUI assets, script exit codes).
 * 4. Applies pending module updates and restarts privileged `service.sh` hooks when requested by
 *    [ModuleUpdateScheduler] or manual user triggers.
 *
 * Note: Unprivileged periodic GitHub update polling is handled separately by [ModuleUpdateScheduler]
 * via Android's `JobScheduler` so network checks respect OS standby/unmetered constraints without
 * bloating the privileged watchdog loop.
 */
object NightDogClient {

    private const val KEY_NIGHTDOG_ENABLED = "nightdog_enabled"
    private const val KEY_NIGHTDOG_PENDING_SYNC = "nightdog_pending_sync"
    private const val PERIODIC_CHECK_INTERVAL_MS = 15L * 60L * 1000L

    data class ServerWatchdogStatus(
        val enabled: Boolean,
        val started: Boolean,
        val boundServicesCount: Int,
        val totalRebinds: Int,
        val beatCount: Long,
        val uptimeMs: Long,
        val boundServices: List<String>
    )

    data class ModuleCheckReport(
        val timestampMs: Long,
        val serverAlive: Boolean,
        val watchdogStatus: ServerWatchdogStatus?,
        val totalModules: Int,
        val enabledModules: Int,
        val healthyModules: Int,
        val servicesExecuted: Int,
        val servicesFailed: Int,
        val healthByModule: Map<String, ModuleHealthStatus>,
        val updatesByModule: Map<String, UpdateResult>,
        val autoUpdatedModules: List<String> = emptyList()
    ) {
        val updatesAvailableCount: Int
            get() = updatesByModule.count { (id, res) ->
                res.hasUpdate && !ModuleSettings.isModuleUpdateIgnored(id)
            }

        val issuesCount: Int
            get() = healthByModule.values.count { !it.healthy }
    }

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val updateApplyMutex = Mutex()
    private val pendingPrivilegeUpdates = ConcurrentHashMap<String, UpdateResult>()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var periodicJob: Job? = null

    private val _isCheckingModules = MutableStateFlow(false)
    val isCheckingModules: StateFlow<Boolean> = _isCheckingModules.asStateFlow()

    private val _lastCheckReport = MutableStateFlow<ModuleCheckReport?>(null)
    val lastCheckReport: StateFlow<ModuleCheckReport?> = _lastCheckReport.asStateFlow()

    private val _watchdogStatus = MutableStateFlow<ServerWatchdogStatus?>(null)
    val watchdogStatus: StateFlow<ServerWatchdogStatus?> = _watchdogStatus.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        syncWithServer()
        refreshWatchdogStatus()
        val ctx = resolveContext() ?: return@OnBinderReceivedListener
        scope.launch {
            runCatching { AdbSettings.preProvisionForAutostart(ctx) }
            flushPendingPrivilegeUpdates(ctx)
            if (ModuleSettings.isBackgroundCheckEnabled() || isEnabled()) {
                runBackgroundCheck(
                    context = ctx,
                    forceUpdateCheck = false,
                    runServices = true,
                    forceRunServices = false,
                    onServerStart = true
                )
            }
            if (ModuleSettings.getUpdateFrequency() == ModuleSettings.UpdateFrequency.ON_START) {
                ModuleUpdateScheduler.checkUpdates(
                    context = ctx,
                    force = false,
                    onServerStart = true
                )
            }
        }
        ensurePeriodicLoop()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AdbModuleManager.resetServiceRunGuard()
        _watchdogStatus.value = null
    }

    @JvmOverloads
    fun init(context: Context? = null) {
        if (context != null) {
            appContext = context.applicationContext
            ModuleUpdateScheduler.syncSchedule(context.applicationContext)
        }
        if (!initialized.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        ensurePeriodicLoop()
    }

    private fun resolveContext(): Context? {
        return appContext ?: runCatching { application.applicationContext }.getOrNull()
    }

    fun ensurePeriodicLoop() {
        val current = periodicJob
        if (current != null && current.isActive) return
        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_CHECK_INTERVAL_MS)
                val ctx = resolveContext() ?: continue
                if (ModuleSettings.isBackgroundCheckEnabled() || isEnabled()) {
                    runBackgroundCheck(
                        context = ctx,
                        forceUpdateCheck = false,
                        runServices = true,
                        forceRunServices = false,
                        onServerStart = false
                    )
                }
            }
        }
    }

    fun isServerAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun isEnabled(): Boolean {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            null
        }
        val cached = prefs?.getBoolean(KEY_NIGHTDOG_ENABLED, false) ?: false
        val pendingSync = prefs?.getBoolean(KEY_NIGHTDOG_PENDING_SYNC, false) ?: false

        val binder = Shizuku.getBinder()
        if (binder == null || !binder.pingBinder()) {
            return cached
        }

        if (pendingSync && sendSetEnabledTransaction(binder, cached)) {
            prefs?.edit()?.putBoolean(KEY_NIGHTDOG_PENDING_SYNC, false)?.apply()
            refreshWatchdogStatus()
            return cached
        }

        val remoteEnabled = sendGetEnabledTransaction(binder) ?: return cached
        prefs?.edit()
            ?.putBoolean(KEY_NIGHTDOG_ENABLED, remoteEnabled)
            ?.putBoolean(KEY_NIGHTDOG_PENDING_SYNC, false)
            ?.apply()
        return remoteEnabled
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            null
        }

        val binder = Shizuku.getBinder()
        val appliedToServer = if (binder != null && binder.pingBinder()) {
            sendSetEnabledTransaction(binder, enabled)
        } else {
            false
        }

        prefs?.edit()
            ?.putBoolean(KEY_NIGHTDOG_ENABLED, enabled)
            ?.putBoolean(KEY_NIGHTDOG_PENDING_SYNC, !appliedToServer)
            ?.apply()

        refreshWatchdogStatus()
        if (enabled) {
            ensurePeriodicLoop()
            resolveContext()?.let { ctx ->
                scope.launch {
                    runBackgroundCheck(
                        context = ctx,
                        forceUpdateCheck = false,
                        runServices = true,
                        forceRunServices = false,
                        onServerStart = false
                    )
                }
            }
        }
        return appliedToServer
    }

    fun syncWithServer() {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            return
        }
        val binder = Shizuku.getBinder()
        if (binder == null || !binder.pingBinder()) return

        val pendingSync = prefs.getBoolean(KEY_NIGHTDOG_PENDING_SYNC, false)
        if (pendingSync) {
            val desired = prefs.getBoolean(KEY_NIGHTDOG_ENABLED, false)
            if (sendSetEnabledTransaction(binder, desired)) {
                prefs.edit().putBoolean(KEY_NIGHTDOG_PENDING_SYNC, false).apply()
            }
        } else {
            val remoteEnabled = sendGetEnabledTransaction(binder) ?: return
            prefs.edit().putBoolean(KEY_NIGHTDOG_ENABLED, remoteEnabled).apply()
        }
    }

    fun refreshWatchdogStatus(): ServerWatchdogStatus? {
        val binder = Shizuku.getBinder()
        if (binder == null || !binder.pingBinder()) {
            _watchdogStatus.value = null
            return null
        }
        val status = sendGetStatusTransaction(binder)
        _watchdogStatus.value = status
        return status
    }

    /**
     * Installs discovered updates and restarts privileged `service.sh` scripts when the server is alive.
     * If a module requires a privileged `service.sh` restart and the server is currently offline,
     * the package files are updated immediately and the service restart is queued for `OnBinderReceived`.
     */
    suspend fun applyUpdates(
        context: Context,
        targets: List<Pair<AdbModule, UpdateResult>>
    ): List<AdbModule> = withContext(Dispatchers.IO) {
        updateApplyMutex.withLock {
            val installer = ModuleInstaller.getInstance()
            val updatedModules = mutableListOf<AdbModule>()
            val serverAlive = isServerAvailable()

            for ((module, upd) in targets) {
                if (ModuleSettings.isModuleUpdateIgnored(module.id) || !upd.hasUpdate) continue
                installer.updateModule(context, module, upd).onSuccess { updatedMod ->
                    updatedModules += updatedMod
                    if (updatedMod.enabled && updatedMod.hasService &&
                        ModuleSettings.canRunService(updatedMod) && ModuleSettings.canRunBackground(updatedMod)
                    ) {
                        if (serverAlive) {
                            runCatching { AdbModuleManager.runService(updatedMod) }
                        } else {
                            pendingPrivilegeUpdates[updatedMod.id] = upd
                        }
                    }
                }
            }
            updatedModules
        }
    }

    private suspend fun flushPendingPrivilegeUpdates(context: Context) {
        if (pendingPrivilegeUpdates.isEmpty() || !isServerAvailable()) return
        updateApplyMutex.withLock {
            val modulesById = AdbModuleManager.listModules(context).associateBy { it.id }
            val pendingIds = pendingPrivilegeUpdates.keys.toList()
            for (id in pendingIds) {
                pendingPrivilegeUpdates.remove(id)
                val mod = modulesById[id] ?: continue
                if (mod.enabled && mod.hasService &&
                    ModuleSettings.canRunService(mod) && ModuleSettings.canRunBackground(mod)
                ) {
                    runCatching { AdbModuleManager.runService(mod) }
                }
            }
        }
    }

    internal fun notifyUpdatesChanged(
        updatesMap: Map<String, UpdateResult>,
        autoUpdatedNames: List<String>
    ) {
        val current = _lastCheckReport.value ?: return
        _lastCheckReport.value = current.copy(
            updatesByModule = updatesMap,
            autoUpdatedModules = if (autoUpdatedNames.isNotEmpty()) autoUpdatedNames else current.autoUpdatedModules
        )
    }

    suspend fun runManualCheck(
        context: Context,
        checkUpdates: Boolean = true,
        forceRunServices: Boolean = false
    ): ModuleCheckReport {
        return runBackgroundCheck(
            context = context.applicationContext,
            forceUpdateCheck = checkUpdates,
            runServices = true,
            forceRunServices = forceRunServices,
            onServerStart = false
        )
    }

    suspend fun runBackgroundCheck(
        context: Context,
        forceUpdateCheck: Boolean = false,
        runServices: Boolean = true,
        forceRunServices: Boolean = false,
        onServerStart: Boolean = false
    ): ModuleCheckReport = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            _isCheckingModules.value = true
            try {
                val now = System.currentTimeMillis()
                AdbModuleManager.cleanupStagingDirs(context)

                val serverAlive = isServerAvailable()
                val wdStatus = if (serverAlive) refreshWatchdogStatus() else null

                var servicesExecuted = 0
                var servicesFailed = 0
                if (serverAlive && runServices && (ModuleSettings.allowBackgroundActions() || forceRunServices)) {
                    val results = runCatching {
                        AdbModuleManager.runEnabledServicesIfAllowed(context, force = forceRunServices)
                    }.getOrDefault(emptyList())
                    servicesExecuted = results.size
                    servicesFailed = results.count { it.second.exitCode != 0 }
                }

                // Delegate update checks to ModuleUpdateScheduler when requested (manual or server start)
                val updatesMap = if (forceUpdateCheck || onServerStart) {
                    ModuleUpdateScheduler.checkUpdates(
                        context = context,
                        force = forceUpdateCheck,
                        onServerStart = onServerStart
                    )
                } else {
                    ModuleUpdateScheduler.updatesByModule.value
                }

                val modules = AdbModuleManager.listModules(context)
                val healthMap = LinkedHashMap<String, ModuleHealthStatus>(modules.size)
                for (module in modules) {
                    healthMap[module.id] = AdbModuleManager.inspectModuleHealth(module)
                }

                ModuleSettings.setLastBackgroundCheckTime(now)

                val report = ModuleCheckReport(
                    timestampMs = now,
                    serverAlive = serverAlive,
                    watchdogStatus = wdStatus,
                    totalModules = modules.size,
                    enabledModules = modules.count { it.enabled },
                    healthyModules = healthMap.values.count { it.healthy },
                    servicesExecuted = servicesExecuted,
                    servicesFailed = servicesFailed,
                    healthByModule = healthMap,
                    updatesByModule = updatesMap,
                    autoUpdatedModules = _lastCheckReport.value?.autoUpdatedModules.orEmpty()
                )
                _lastCheckReport.value = report
                report
            } finally {
                _isCheckingModules.value = false
            }
        }
    }

    private fun sendGetStatusTransaction(binder: IBinder): ServerWatchdogStatus? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_getNightDogStatus, data, reply, 0)
            if (!ok) return null
            reply.readException()
            val bundle: Bundle = reply.readBundle(NightDogClient::class.java.classLoader) ?: return null
            ServerWatchdogStatus(
                enabled = bundle.getBoolean("enabled", false),
                started = bundle.getBoolean("started", false),
                boundServicesCount = bundle.getInt("boundServicesCount", 0),
                totalRebinds = bundle.getInt("totalRebinds", 0),
                beatCount = bundle.getLong("beatCount", 0L),
                uptimeMs = bundle.getLong("uptimeMs", 0L),
                boundServices = bundle.getStringArrayList("boundServices")?.toList().orEmpty()
            )
        } catch (t: Throwable) {
            LOGGER.w(t, "NightDogClient: getNightDogStatus failed")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun sendGetEnabledTransaction(binder: IBinder): Boolean? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_getNightDogEnabled, data, reply, 0)
            if (!ok) return null
            reply.readException()
            reply.readInt() != 0
        } catch (t: Throwable) {
            LOGGER.w(t, "NightDogClient: getNightDogEnabled failed")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun sendSetEnabledTransaction(binder: IBinder, enabled: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(if (enabled) 1 else 0)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_setNightDogEnabled, data, reply, 0)
            if (!ok) return false
            reply.readException()
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "NightDogClient: setNightDogEnabled(%s) failed", enabled.toString())
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
