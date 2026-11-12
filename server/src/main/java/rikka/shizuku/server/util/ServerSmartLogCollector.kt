package rikka.shizuku.server.util

import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import java.io.File
import kerneldroid.nightzuku.util.HiddenApiIntrospector
import kerneldroid.nightzuku.util.SmartLogEngine
import kerneldroid.nightzuku.util.SmartLogEngine.SmartLogSnapshot
import rikka.hidden.compat.ActivityManagerApis

/**
 * Server-side Smart Log aggregator and QPR compatibility diagnostic recorder inside `shizuku_server`.
 *
 * Responsibilities:
 * 1. Immediately lifts ART hidden API restrictions (`HiddenApiIntrospector.exemptHiddenApis()`)
 *    inside `shizuku_server` (`app_process`) so blacklisted framework methods are visible to reflection.
 * 2. Captures uncaught server exceptions before process termination, persists them to all SELinux-compliant
 *    spools, and pushes them directly to `ShizukuManagerProvider` (`pushSmartLog`) so the Manager
 *    receives the crash snapshot even if `shizuku_server` dies fatally during external ADB/boot launch.
 * 3. Captures Android 16/17 QPR4+ hidden API reflection failures from [Android17Compat],
 *    [UsersCompat], and `InstalledPackagesCompat` along with live AIDL method dumps.
 * 4. Ingests native `rust/starter` crash/error snapshots from spool files.
 * 5. Serves accumulated snapshots and a live QPR compatibility probe to the Nightzuku Manager
 *    over Binder (`BINDER_TRANSACTION_getSmartLogs`).
 */
object ServerSmartLogCollector {

    private val LOGGER = Logger("ServerSmartLog")
    private val lock = Any()
    private val snapshots = ArrayList<SmartLogSnapshot>()

    private val spoolFiles = listOf(
        File(SmartLogEngine.SPOOL_TMP_PATH),
        File(SmartLogEngine.SPOOL_SHELL_PATH),
        File(SmartLogEngine.SPOOL_EXT_EMULATED_PATH),
        File(SmartLogEngine.SPOOL_APP_DE_CACHE_PATH),
        File(SmartLogEngine.SPOOL_APP_CE_CACHE_PATH)
    )

    @Volatile
    private var cachedQprAudit: String? = null

    @JvmStatic
    fun init() {
        HiddenApiIntrospector.exemptHiddenApis()
        installUncaughtExceptionHandler()
        ingestSpoolFiles()
    }

    @JvmStatic
    fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordServerCrash(thread, throwable)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    @JvmStatic
    fun ingestSpoolFiles() {
        synchronized(lock) {
            for (file in spoolFiles) {
                try {
                    if (file.exists() && file.canRead()) {
                        val raw = file.readText(Charsets.UTF_8)
                        val parsed = SmartLogEngine.parseSnapshots(raw)
                        if (parsed.isNotEmpty()) {
                            val merged = SmartLogEngine.mergeSnapshots(snapshots, parsed)
                            snapshots.clear()
                            snapshots.addAll(merged)
                        }
                    }
                } catch (t: Throwable) {
                    LOGGER.w(t, "Failed to ingest spool file %s", file.absolutePath)
                }
            }
        }
    }

    @JvmStatic
    fun recordServerCrash(thread: Thread, throwable: Throwable) {
        val details = buildString {
            appendLine("Thread: ${thread.name} (id=${thread.id}, state=${thread.state})")
            appendLine("Server UID: ${OsUtils.getUid()} | PID: ${OsUtils.getPid()} | SELinux: ${OsUtils.getSELinuxContext()}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, Preview ${Build.VERSION.PREVIEW_SDK_INT}, Codename ${Build.VERSION.CODENAME})")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            appendLine()
            appendLine("Stack Trace:")
            appendLine(SmartLogEngine.stackTraceToString(throwable))
            appendLine()
            appendLine(getOrRunQprCompatAudit())
        }
        recordSnapshot(
            source = "SERVER",
            category = "SERVER_UNCAUGHT_CRASH",
            severity = "FATAL",
            summary = "Uncaught ${throwable.javaClass.simpleName} in shizuku_server thread '${thread.name}': ${throwable.message ?: "no message"}",
            details = details
        )
    }

    @JvmStatic
    fun recordQprCompatFailure(
        operationName: String,
        serviceName: String,
        interfaceName: String,
        targetProxy: Any?,
        triedCandidates: String,
        methodKeywords: List<String>,
        failure: Throwable?
    ) {
        HiddenApiIntrospector.exemptHiddenApis()
        val reflectionDump = HiddenApiIntrospector.inspectInterfaceMethods(
            serviceName = serviceName,
            interfaceName = interfaceName,
            targetProxy = targetProxy,
            methodKeywords = methodKeywords
        )
        val details = buildString {
            appendLine("Operation: $operationName")
            appendLine("Tried Candidate Shapes: $triedCandidates")
            appendLine("Server UID: ${OsUtils.getUid()} | SELinux: ${OsUtils.getSELinuxContext()}")
            appendLine()
            appendLine(reflectionDump)
            if (failure != null) {
                appendLine()
                appendLine("Underlying Exception:")
                appendLine(SmartLogEngine.stackTraceToString(failure))
            }
        }
        recordSnapshot(
            source = "QPR_COMPAT",
            category = "HIDDEN_API_DRIFT_$operationName",
            severity = "ERROR",
            summary = "Hidden API resolution failed for $operationName on $interfaceName ($serviceName)",
            details = details
        )
    }

    @JvmStatic
    fun recordServerError(
        category: String,
        summary: String,
        throwable: Throwable? = null,
        extraDetails: String? = null
    ) {
        val details = buildString {
            appendLine("Server UID: ${OsUtils.getUid()} | PID: ${OsUtils.getPid()} | SELinux: ${OsUtils.getSELinuxContext()}")
            if (!extraDetails.isNullOrBlank()) {
                appendLine(extraDetails.trim())
            }
            if (throwable != null) {
                appendLine()
                appendLine("Stack Trace:")
                appendLine(SmartLogEngine.stackTraceToString(throwable))
            }
        }
        recordSnapshot(
            source = "SERVER",
            category = category,
            severity = "ERROR",
            summary = summary,
            details = details
        )
    }

    @JvmStatic
    fun recordSnapshot(
        source: String,
        category: String,
        severity: String,
        summary: String,
        details: String
    ) {
        val snap = SmartLogSnapshot(
            source = source,
            category = category,
            severity = severity,
            summary = summary,
            details = details
        )
        synchronized(lock) {
            val updated = SmartLogEngine.mergeSnapshots(snapshots, listOf(snap))
            snapshots.clear()
            snapshots.addAll(updated)
            SmartLogEngine.appendSnapshotToAllSpools(snap)
        }
        pushSnapshotToManagerProvider(snap)
    }

    /**
     * Pushes [snapshot] directly to `ShizukuManagerProvider` (`kerneldroid.nightzuku.shizuku`)
     * via `ActivityManagerApis.getContentProviderExternal`. Because `shell` (`u:r:shell:s0`) and
     * `su` (`u:r:su:s0`) are permitted by SELinux to call exported ContentProviders of `untrusted_app`,
     * the Manager persists the snapshot inside its own internal `app_data_file` storage even if
     * `shizuku_server` exits fatally right after.
     */
    private fun pushSnapshotToManagerProvider(snapshot: SmartLogSnapshot) {
        val authority = SmartLogEngine.MANAGER_PROVIDER_AUTHORITY
        val token: IBinder? = null
        var provider: IContentProvider? = null
        try {
            provider = ActivityManagerApis.getContentProviderExternal(authority, 0, token, authority)
            if (provider == null || !provider.asBinder().pingBinder()) {
                return
            }
            val serialized = SmartLogEngine.serializeSnapshot(snapshot)
            val extras = Bundle().apply {
                putString(SmartLogEngine.EXTRA_SMART_LOG_PAYLOAD, serialized)
            }
            IContentProviderUtils.callCompat(
                provider,
                null,
                authority,
                SmartLogEngine.METHOD_PUSH_SMART_LOG,
                serialized,
                extras
            )
        } catch (_: Throwable) {
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(authority, token)
                } catch (_: Throwable) {
                }
            }
        }
    }

    @JvmStatic
    fun getOrRunQprCompatAudit(): String {
        cachedQprAudit?.let { return it }
        HiddenApiIntrospector.exemptHiddenApis()
        val audit = HiddenApiIntrospector.runLiveCompatProbe()
        cachedQprAudit = audit
        return audit
    }

    @JvmStatic
    fun buildSmartLogsBundle(): Bundle {
        ingestSpoolFiles()
        val serializedSnapshots: String
        val count: Int
        synchronized(lock) {
            count = snapshots.size
            serializedSnapshots = SmartLogEngine.serializeSnapshots(snapshots)
        }
        return Bundle().apply {
            putInt("snapshotCount", count)
            putString("serializedSnapshots", serializedSnapshots)
            putString("qprCompatAudit", getOrRunQprCompatAudit())
            putInt("serverUid", OsUtils.getUid())
            putInt("serverPid", OsUtils.getPid())
            putString("seContext", OsUtils.getSELinuxContext())
        }
    }

    @JvmStatic
    fun clearSmartLogs() {
        synchronized(lock) {
            snapshots.clear()
            for (file in spoolFiles) {
                runCatching { if (file.exists()) file.delete() }
            }
        }
    }
}
