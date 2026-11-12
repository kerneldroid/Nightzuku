package moe.shizuku.manager.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kerneldroid.nightzuku.util.HiddenApiIntrospector
import kerneldroid.nightzuku.util.SmartLogEngine
import kerneldroid.nightzuku.util.SmartLogEngine.SmartLogSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.application
import moe.shizuku.manager.model.ServiceStatus
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

/**
 * Central Smart Log coordinator in the Nightzuku Manager.
 *
 * Captures, aggregates, and deduplicates diagnostic snapshots from:
 * 1. Manager JVM uncaught exceptions and runtime failures.
 * 2. Previous process native crashes (`SIGSEGV`/`SIGABRT` in JNI/Rust) and ANRs via Android 11+ `ApplicationExitInfo`.
 * 3. Rust `adb-pairing` (`libadb.so`) SPAKE2/HKDF/AES-GCM errors and Java wireless ADB pairing failures.
 * 4. Rust `starter` (`libshizuku.so`) supervisor crashes, SELinux denials, and abnormal server exits —
 *    even when `shizuku_server` dies fatally during external `adb shell` or boot script launch:
 *    - Tier 1: Direct IPC push into `ShizukuManagerProvider` (`content://kerneldroid.nightzuku.shizuku`, method `pushSmartLog`)
 *    - Tier 2: App-owned external cache spool (`/storage/emulated/0/Android/data/kerneldroid.nightzuku/cache/nightzuku_smart.log`)
 *    - Tier 3: Root-direct internal DE/CE cache spool with inherited UID/GID and `app_data_file` SELinux context
 *    - Tier 4: Root shell fallback for `/data/local/tmp/nightzuku_smart.log`
 * 5. `shizuku_server` QPR4+ hidden API reflection drift (`Android17Compat`, `UsersCompat`, `InstalledPackagesCompat`)
 *    and `NightDog` watchdog faults over Binder IPC.
 */
object SmartLogManager {

    private const val PREF_SEEN_SIGNATURES = "smart_log_seen_signatures"
    private const val PREF_LAST_EXIT_INFO_TS = "smart_log_last_exit_info_ts"
    private const val PREF_UNCLEAN_EXIT_COUNT = "smart_log_unclean_exit_count"
    private const val LOCAL_STORE_REL_PATH = "smart_logs/manager_snapshots.log"
    private const val MAX_PUSH_PAYLOAD_CHARS = 128 * 1024
    private const val MAX_LOGCAT_LINES = 240
    private const val MAX_TRACE_LINES = 200

    data class SmartLogUiState(
        val snapshots: List<SmartLogSnapshot> = emptyList(),
        val unseenCount: Int = 0,
        val totalErrorEvents: Int = 0,
        val isMultiError: Boolean = false,
        val qprCompatAudit: String = "",
        val shouldShowRecoveryDialog: Boolean = false
    )

    private val initialized = AtomicBoolean(false)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    private val snapshots = ArrayList<SmartLogSnapshot>()

    @Volatile
    private var serverQprAudit: String? = null

    private val _uiState = MutableStateFlow(SmartLogUiState())
    val uiState: StateFlow<SmartLogUiState> = _uiState.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scope.launch {
            syncWithServerAndDisk()
        }
    }

    @JvmStatic
    fun init(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        HiddenApiIntrospector.exemptHiddenApis()
        if (!initialized.compareAndSet(false, true)) {
            scope.launch { syncWithServerAndDisk() }
            return
        }

        ensureSpoolDirectories(ctx)
        installUncaughtExceptionHandler(ctx)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)

        scope.launch {
            loadLocalSnapshots(ctx)
            inspectHistoricalProcessExits(ctx)
            syncWithServerAndDisk()
        }
    }

    /**
     * Ensures the Manager's internal DE/CE cache directories and external app-specific cache
     * directory (`/storage/emulated/0/Android/data/kerneldroid.nightzuku/cache`) exist so
     * `rust/starter` and `shizuku_server` can write SELinux-accessible spool files even when
     * the server dies before Binder comes up.
     */
    private fun ensureSpoolDirectories(context: Context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext().cacheDir?.mkdirs()
            }
            context.cacheDir?.mkdirs()
            context.externalCacheDir?.mkdirs()
            context.externalCacheDirs?.forEach { it?.mkdirs() }
        }
    }

    private fun resolveStorageFile(context: Context): File {
        val storageCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return File(storageCtx.filesDir, LOCAL_STORE_REL_PATH)
    }

    private fun resolveAccessibleSpoolFiles(context: Context?): List<File> {
        val files = LinkedHashSet<File>()
        if (context != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val deCache = context.createDeviceProtectedStorageContext().cacheDir
                    if (deCache != null) {
                        files.add(File(deCache, SmartLogEngine.SPOOL_FILE_NAME))
                    }
                }
            }
            runCatching {
                context.cacheDir?.let { files.add(File(it, SmartLogEngine.SPOOL_FILE_NAME)) }
            }
            runCatching {
                context.externalCacheDir?.let { files.add(File(it, SmartLogEngine.SPOOL_FILE_NAME)) }
                context.externalCacheDirs?.filterNotNull()?.forEach { dir ->
                    files.add(File(dir, SmartLogEngine.SPOOL_FILE_NAME))
                }
            }
        }
        files.add(File(SmartLogEngine.SPOOL_APP_DE_CACHE_PATH))
        files.add(File(SmartLogEngine.SPOOL_APP_CE_CACHE_PATH))
        files.add(File(SmartLogEngine.SPOOL_EXT_EMULATED_PATH))
        // /data/local/tmp and the shell DE dir are deliberately absent: SELinux denies them to
        // untrusted_app. The server reads those and delivers them over Binder or pushSmartLog.
        return files.toList()
    }

    /**
     * Ingests a raw or Base64-encoded Smart Log snapshot payload pushed directly into
     * `ShizukuManagerProvider` (`content://kerneldroid.nightzuku.shizuku`, method `pushSmartLog`)
     * by `rust/starter` or `shizuku_server`.
     */
    @JvmStatic
    fun ingestPushedPayload(context: Context, rawPayload: String?): Boolean {
        if (rawPayload.isNullOrBlank() || rawPayload.length > MAX_PUSH_PAYLOAD_CHARS) {
            return false
        }
        val ctx = context.applicationContext ?: context
        appContext = ctx
        val parsed = SmartLogEngine.parseSnapshots(rawPayload)
        if (parsed.isEmpty()) {
            return false
        }
        synchronized(lock) {
            val merged = SmartLogEngine.mergeSnapshots(snapshots, parsed)
            snapshots.clear()
            snapshots.addAll(merged)
            persistLocalSnapshotsLocked(ctx)
        }
        refreshUiState()
        return true
    }

    private fun installUncaughtExceptionHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrashSynchronously(context, thread, throwable)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun recordCrashSynchronously(context: Context, thread: Thread, throwable: Throwable) {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val serverUid = if (binderAlive) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        val details = buildString {
            appendLine("Crash Thread: ${thread.name} (id=${thread.id}, state=${thread.state})")
            appendLine("Server State at Crash: ${if (binderAlive) "running/occupied (uid=$serverUid)" else "offline"}")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT} (Preview ${Build.VERSION.PREVIEW_SDK_INT}, Codename ${Build.VERSION.CODENAME})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE} / ${Build.PRODUCT})")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            appendLine()
            appendLine("Exception Stack Trace:")
            appendLine(SmartLogEngine.stackTraceToString(throwable))
            val logcat = captureProcessLogcatTail()
            if (logcat.isNotBlank()) {
                appendLine()
                appendLine("Recent Process Logcat:")
                appendLine(logcat)
            }
        }
        val snapshot = SmartLogSnapshot(
            source = "MANAGER",
            category = "APP_UNCAUGHT_CRASH",
            severity = "FATAL",
            summary = "Manager crashed with ${throwable.javaClass.simpleName}: ${throwable.message ?: "unexpected fault"}",
            details = details
        )
        synchronized(lock) {
            val merged = SmartLogEngine.mergeSnapshots(snapshots, listOf(snapshot))
            snapshots.clear()
            snapshots.addAll(merged)
            val file = resolveStorageFile(context)
            SmartLogEngine.appendSnapshotToFile(file, snapshot)
        }
        runCatching {
            val prefs = ShizukuSettings.getPreferences()
            val prevCount = prefs.getInt(PREF_UNCLEAN_EXIT_COUNT, 0)
            prefs.edit().putInt(PREF_UNCLEAN_EXIT_COUNT, prevCount + 1).commit()
        }
    }

    /**
     * Checks Android 11+ `ActivityManager.getHistoricalProcessExitReasons` to catch native JNI/Rust
     * crashes (`REASON_CRASH_NATIVE`), ANRs (`REASON_ANR`), or early initialization crashes that
     * bypassed the JVM `UncaughtExceptionHandler`.
     */
    private fun inspectHistoricalProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = ShizukuSettings.getPreferences()
            val lastProcessedTs = prefs.getLong(PREF_LAST_EXIT_INFO_TS, 0L)
            val exitList = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exitList.isEmpty()) return

            var newestTs = lastProcessedTs
            val newExitSnapshots = ArrayList<SmartLogSnapshot>()

            for (info in exitList.sortedBy { it.timestamp }) {
                if (info.timestamp <= lastProcessedTs) continue
                if (info.timestamp > newestTs) {
                    newestTs = info.timestamp
                }
                val isAbnormal = info.reason == ApplicationExitInfo.REASON_CRASH ||
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    info.reason == ApplicationExitInfo.REASON_ANR ||
                    info.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                if (!isAbnormal) continue

                val alreadyCapturedByJvm = synchronized(lock) {
                    snapshots.any {
                        it.category == "APP_UNCAUGHT_CRASH" &&
                            kotlin.math.abs(it.timestampMs - info.timestamp) < 5_000L
                    }
                }
                if (alreadyCapturedByJvm) continue

                val reasonLabel = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH (SIGSEGV/SIGABRT)"
                    ApplicationExitInfo.REASON_ANR -> "ANR (Application Not Responding)"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                    else -> "JVM_CRASH"
                }

                val traceSnippet = runCatching {
                    info.traceInputStream?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                            .lineSequence()
                            .take(MAX_TRACE_LINES)
                            .joinToString("\n")
                    }.orEmpty()
                }.getOrDefault("")

                val binderOccupied = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                val details = buildString {
                    appendLine("Exit Reason: $reasonLabel (code=${info.reason}, status=${info.status})")
                    appendLine("Process: ${info.processName} (pid=${info.pid}, pss=${info.pss}KB, rss=${info.rss}KB)")
                    appendLine("Description: ${info.description ?: "none"}")
                    appendLine("Service State on Re-entry: ${if (binderOccupied) "running / already occupied" else "stopped"}")
                    if (traceSnippet.isNotBlank()) {
                        appendLine()
                        appendLine("Exit Trace / Tombstone Excerpt:")
                        appendLine(traceSnippet)
                    }
                }

                newExitSnapshots.add(
                    SmartLogSnapshot(
                        timestampMs = info.timestamp,
                        source = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "NATIVE_CRASH" else "MANAGER",
                        category = "PROCESS_EXIT_${info.reason}",
                        severity = "FATAL",
                        summary = "Previous session terminated abnormally ($reasonLabel): ${info.description ?: "status=${info.status}"}",
                        details = details
                    )
                )
            }

            if (newestTs > lastProcessedTs) {
                prefs.edit().putLong(PREF_LAST_EXIT_INFO_TS, newestTs).apply()
            }

            if (newExitSnapshots.isNotEmpty()) {
                synchronized(lock) {
                    val merged = SmartLogEngine.mergeSnapshots(snapshots, newExitSnapshots)
                    snapshots.clear()
                    snapshots.addAll(merged)
                    persistLocalSnapshotsLocked(context)
                }
                refreshUiState()
            }
        } catch (_: Throwable) {
        }
    }

    private fun loadLocalSnapshots(context: Context) {
        synchronized(lock) {
            try {
                val file = resolveStorageFile(context)
                if (file.exists() && file.canRead()) {
                    val parsed = SmartLogEngine.parseSnapshots(file.readText(Charsets.UTF_8))
                    val merged = SmartLogEngine.mergeSnapshots(snapshots, parsed)
                    snapshots.clear()
                    snapshots.addAll(merged)
                }
            } catch (_: Throwable) {
            }
        }
        refreshUiState()
    }

    private fun persistLocalSnapshotsLocked(context: Context) {
        try {
            val file = resolveStorageFile(context)
            file.parentFile?.mkdirs()
            file.writeText(SmartLogEngine.serializeSnapshots(snapshots), Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun syncAll(context: Context? = null) {
        if (context != null) {
            appContext = context.applicationContext ?: context
            ensureSpoolDirectories(appContext!!)
        }
        scope.launch {
            val ctx = appContext ?: runCatching { application.applicationContext }.getOrNull()
            if (ctx != null) {
                loadLocalSnapshots(ctx)
                inspectHistoricalProcessExits(ctx)
            }
            syncWithServerAndDisk()
        }
    }

    private fun syncWithServerAndDisk() {
        val ctx = appContext ?: runCatching { application.applicationContext }.getOrNull()
        val incoming = ArrayList<SmartLogSnapshot>()

        // 1. Ingest the spool files the manager is allowed to read: its own DE/CE cache and the
        // app-specific external cache. Server-only paths (/data/local/tmp, shell DE) are read by
        // shizuku_server and delivered over Binder below.
        for (spoolFile in resolveAccessibleSpoolFiles(ctx)) {
            runCatching {
                if (spoolFile.exists() && spoolFile.canRead()) {
                    val parsed = SmartLogEngine.parseSnapshots(spoolFile.readText(Charsets.UTF_8))
                    if (parsed.isNotEmpty()) {
                        incoming.addAll(parsed)
                    }
                }
            }
        }

        // 2. Query shizuku_server over Binder if running
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (binder != null && binder.pingBinder()) {
            val serverBundle = fetchServerSmartLogsBundle(binder)
            if (serverBundle != null) {
                val serialized = serverBundle.getString("serializedSnapshots").orEmpty()
                if (serialized.isNotBlank()) {
                    incoming.addAll(SmartLogEngine.parseSnapshots(serialized))
                }
                val audit = serverBundle.getString("qprCompatAudit")
                if (!audit.isNullOrBlank()) {
                    serverQprAudit = audit
                }
            }
        } else {
            // 3. Fallback when server is dead and a cached root shell is open: read shell spool files via su
            runCatching {
                val cachedShell = Shell.getCachedShell()
                if (cachedShell != null && cachedShell.isRoot) {
                    val out = ArrayList<String>()
                    Shell.cmd(
                        "cat ${SmartLogEngine.SPOOL_TMP_PATH} ${SmartLogEngine.SPOOL_SHELL_PATH} 2>/dev/null"
                    ).to(out).exec()
                    if (out.isNotEmpty()) {
                        incoming.addAll(SmartLogEngine.parseSnapshots(out.joinToString("\n")))
                    }
                }
            }
        }

        if (incoming.isNotEmpty()) {
            synchronized(lock) {
                val merged = SmartLogEngine.mergeSnapshots(snapshots, incoming)
                if (merged != snapshots) {
                    snapshots.clear()
                    snapshots.addAll(merged)
                    if (ctx != null) {
                        persistLocalSnapshotsLocked(ctx)
                    }
                }
            }
        }
        refreshUiState()
    }

    private fun fetchServerSmartLogsBundle(binder: IBinder): Bundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_getSmartLogs, data, reply, 0)
            if (!ok) return null
            reply.readException()
            reply.readBundle(SmartLogManager::class.java.classLoader)
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun clearServerSmartLogs(binder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            if (binder.transact(ServerConstants.BINDER_TRANSACTION_clearSmartLogs, data, reply, 0)) {
                reply.readException()
            }
        } catch (_: Throwable) {
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Records an ADB wireless pairing failure, automatically attaching any native Rust SPAKE2 /
     * AES-GCM error log entries from `libadb.so`.
     */
    @JvmStatic
    fun recordPairingError(
        stage: String,
        host: String?,
        port: Int,
        throwable: Throwable?,
        extraDetails: String? = null
    ) {
        scope.launch {
            val rustNativeLog = runCatching { AdbPairingClient.consumeNativeErrorLog() }.getOrDefault("")
            val details = buildString {
                appendLine("Pairing Stage: $stage")
                appendLine("Target Endpoint: ${host ?: "unknown"}:$port")
                appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.CODENAME})")
                if (!extraDetails.isNullOrBlank()) {
                    appendLine("Context: $extraDetails")
                }
                if (rustNativeLog.isNotBlank()) {
                    appendLine()
                    appendLine("Rust Native Pairing Diagnostics (libadb.so):")
                    appendLine(rustNativeLog)
                }
                if (throwable != null) {
                    appendLine()
                    appendLine("Exception Stack Trace:")
                    appendLine(SmartLogEngine.stackTraceToString(throwable))
                }
                val logcat = captureProcessLogcatTail()
                if (logcat.isNotBlank()) {
                    appendLine()
                    appendLine("Recent Process Logcat:")
                    appendLine(logcat)
                }
            }
            val reason = throwable?.let { "${it.javaClass.simpleName}: ${it.message ?: "failed"}" }
                ?: rustNativeLog.lineSequence().firstOrNull()
                ?: "Pairing protocol rejected at $stage"
            appendAndNotify(
                SmartLogSnapshot(
                    source = if (rustNativeLog.isNotBlank()) "RUST_PAIRING" else "MANAGER",
                    category = "ADB_PAIRING_ERROR",
                    severity = "ERROR",
                    summary = "Wireless ADB pairing failed ($stage on port $port): $reason",
                    details = details
                )
            )
        }
    }

    /**
     * Records a root or wireless ADB starter failure from `StarterActivity`.
     */
    @JvmStatic
    fun recordStarterError(
        isRoot: Boolean,
        host: String?,
        port: Int,
        starterOutput: String,
        throwable: Throwable? = null
    ) {
        scope.launch {
            val mode = if (isRoot) "ROOT (su)" else "WADB (${host ?: "127.0.0.1"}:$port)"
            val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val details = buildString {
                appendLine("Launch Mode: $mode")
                appendLine("Binder Active: $binderAlive")
                appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.CODENAME})")
                if (starterOutput.isNotBlank()) {
                    appendLine()
                    appendLine("Starter Console Output:")
                    appendLine(starterOutput.trim())
                }
                if (throwable != null) {
                    appendLine()
                    appendLine("Exception Stack Trace:")
                    appendLine(SmartLogEngine.stackTraceToString(throwable))
                }
            }
            val summary = throwable?.let {
                "Starter failed in $mode: ${it.javaClass.simpleName}: ${it.message ?: ""}"
            } ?: "Starter exited with non-zero status in $mode"

            appendAndNotify(
                SmartLogSnapshot(
                    source = "RUST_STARTER",
                    category = "STARTER_EXECUTION_ERROR",
                    severity = "ERROR",
                    summary = summary.trim(),
                    details = details
                )
            )
            syncWithServerAndDisk()
        }
    }

    /**
     * Records a general Manager or IPC error (e.g. QPR reflection failure, Binder error, module fault).
     */
    @JvmStatic
    fun recordManagerError(
        category: String,
        summary: String,
        throwable: Throwable? = null,
        extraDetails: String? = null
    ) {
        scope.launch {
            val details = buildString {
                appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.CODENAME})")
                if (!extraDetails.isNullOrBlank()) {
                    appendLine(extraDetails.trim())
                }
                if (throwable != null) {
                    appendLine()
                    appendLine("Exception Stack Trace:")
                    appendLine(SmartLogEngine.stackTraceToString(throwable))
                }
            }
            appendAndNotify(
                SmartLogSnapshot(
                    source = "MANAGER",
                    category = category,
                    severity = "ERROR",
                    summary = summary,
                    details = details
                )
            )
        }
    }

    private fun appendAndNotify(snapshot: SmartLogSnapshot) {
        val ctx = appContext ?: runCatching { application.applicationContext }.getOrNull()
        synchronized(lock) {
            val merged = SmartLogEngine.mergeSnapshots(snapshots, listOf(snapshot))
            snapshots.clear()
            snapshots.addAll(merged)
            if (ctx != null) {
                persistLocalSnapshotsLocked(ctx)
            }
        }
        refreshUiState()
    }

    private fun buildSeenSignature(snap: SmartLogSnapshot): String {
        return "${snap.id}:${snap.occurrences}:${snap.timestampMs}"
    }

    private fun refreshUiState() {
        val currentList: List<SmartLogSnapshot>
        synchronized(lock) {
            currentList = snapshots.toList()
        }
        val prefs = runCatching { ShizukuSettings.getPreferences() }.getOrNull()
        val seenSet = prefs?.getStringSet(PREF_SEEN_SIGNATURES, emptySet()).orEmpty()
        val uncleanExits = prefs?.getInt(PREF_UNCLEAN_EXIT_COUNT, 0) ?: 0

        val unseen = currentList.filter { buildSeenSignature(it) !in seenSet }
        val totalEvents = currentList.sumOf { it.occurrences }
        val isMulti = currentList.size > 1 || totalEvents > 1 || uncleanExits > 1
        val qprAudit = serverQprAudit ?: runCatching { HiddenApiIntrospector.runLiveCompatProbe() }.getOrDefault("")

        _uiState.value = SmartLogUiState(
            snapshots = currentList,
            unseenCount = unseen.size,
            totalErrorEvents = totalEvents.coerceAtLeast(currentList.size),
            isMultiError = isMulti,
            qprCompatAudit = qprAudit,
            shouldShowRecoveryDialog = unseen.isNotEmpty() && currentList.isNotEmpty()
        )
    }

    /**
     * Marks all currently present snapshots as acknowledged so the automatic recovery dialog
     * closes cleanly and only reappears if a new error/crash occurs later.
     */
    @JvmStatic
    fun acknowledgeRecoveryDialog() {
        val currentList: List<SmartLogSnapshot>
        synchronized(lock) {
            currentList = snapshots.toList()
        }
        runCatching {
            val prefs = ShizukuSettings.getPreferences()
            val signatures = currentList.map { buildSeenSignature(it) }.toSet()
            prefs.edit()
                .putStringSet(PREF_SEEN_SIGNATURES, signatures)
                .putInt(PREF_UNCLEAN_EXIT_COUNT, 0)
                .apply()
        }
        refreshUiState()
    }

    /**
     * Clears all stored Smart Log snapshots in the Manager, starter spool files, and `shizuku_server`.
     */
    @JvmStatic
    fun clearAllLogs(context: Context? = null) {
        val ctx = context ?: appContext ?: runCatching { application.applicationContext }.getOrNull()
        synchronized(lock) {
            snapshots.clear()
            if (ctx != null) {
                runCatching { resolveStorageFile(ctx).delete() }
            }
            for (spoolFile in resolveAccessibleSpoolFiles(ctx)) {
                runCatching { if (spoolFile.exists()) spoolFile.delete() }
            }
        }
        runCatching {
            ShizukuSettings.getPreferences().edit()
                .remove(PREF_SEEN_SIGNATURES)
                .putInt(PREF_UNCLEAN_EXIT_COUNT, 0)
                .apply()
        }
        scope.launch {
            val binder = runCatching { Shizuku.getBinder() }.getOrNull()
            if (binder != null && binder.pingBinder()) {
                clearServerSmartLogs(binder)
            }
            refreshUiState()
        }
    }

    /**
     * Builds the comprehensive Smart Log report for display in the dialog or copying to clipboard.
     */
    @JvmStatic
    fun buildFullReport(
        context: Context,
        status: ServiceStatus,
        grantedCount: Int,
        localNetworkLabel: String
    ): String {
        val state = _uiState.value
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault(BuildConfig.VERSION_NAME)

        val abis = Build.SUPPORTED_ABIS?.joinToString(", ") ?: "unknown"
        val header = buildString {
            appendLine("App: ${context.getString(R.string.app_name)} $versionName (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.BOARD} / $abis)")
            appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT} (Preview ${Build.VERSION.PREVIEW_SDK_INT}) / ${Build.VERSION.CODENAME}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            appendLine("Service: ${if (status.isRunning) "running" else "stopped"}")
            appendLine("Server uid: ${status.uid}")
            appendLine("Server API: ${status.apiVersion}.${status.patchVersion}")
            appendLine("SELinux: ${status.seContext ?: "unknown"}")
            appendLine("ADB permission: ${if (status.permission) "full" else "limited"}")
            appendLine("Authorized apps: $grantedCount")
            appendLine("Local network: $localNetworkLabel")
            appendLine("Captured error snapshots: ${state.snapshots.size} (total events: ${state.totalErrorEvents})")
        }.trim()

        val qprAudit = state.qprCompatAudit.ifBlank {
            runCatching { HiddenApiIntrospector.runLiveCompatProbe() }.getOrDefault("")
        }
        val logcatTail = captureProcessLogcatTail()

        return SmartLogEngine.formatMergedReport(
            headerDiagnostics = header,
            snapshots = state.snapshots,
            qprCompatAudit = qprAudit,
            logcatTail = logcatTail
        )
    }

    private fun captureProcessLogcatTail(): String {
        return try {
            val pid = android.os.Process.myPid()
            val proc = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid", "-t", MAX_LOGCAT_LINES.toString())
                .redirectErrorStream(true)
                .start()
            val lines = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                reader.readLines()
            }
            runCatching { proc.destroy() }
            lines.takeLast(MAX_LOGCAT_LINES).joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }
}
