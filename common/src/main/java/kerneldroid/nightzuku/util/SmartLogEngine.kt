package kerneldroid.nightzuku.util

import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Base64
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Cross-layer Smart Log snapshot engine shared between `shizuku_server`, `rust/starter` spool files,
 * and the Nightzuku Manager application.
 *
 * Captures, deduplicates, serializes, and merges diagnostic snapshots across Manager crashes,
 * Rust starter/pairing errors, NightDog recovery events, and Android QPR hidden API changes.
 */
object SmartLogEngine {

    const val BEGIN_MARKER = "===SMART_SNAPSHOT_BEGIN==="
    const val END_MARKER = "===SMART_SNAPSHOT_END==="
    const val BASE64_PREFIX = "base64:"

    const val METHOD_PUSH_SMART_LOG = "pushSmartLog"
    const val EXTRA_SMART_LOG_PAYLOAD = "smartLogPayload"
    const val DEFAULT_MANAGER_PACKAGE = "kerneldroid.nightzuku"
    const val MANAGER_PROVIDER_AUTHORITY = "kerneldroid.nightzuku.shizuku"

    /** Server-to-server spool paths (readable by UID 0 / UID 2000). */
    const val SPOOL_TMP_PATH = "/data/local/tmp/nightzuku_smart.log"
    const val SPOOL_SHELL_PATH = "/data/user_de/0/com.android.shell/nightzuku_smart.log"
    const val SPOOL_FILE_NAME = "nightzuku_smart.log"

    @Volatile
    private var activeManagerPackage: String = DEFAULT_MANAGER_PACKAGE

    /**
     * Configures the active manager package name used to derive app-owned spool paths in stealth
     * builds. Defaults to [DEFAULT_MANAGER_PACKAGE].
     */
    @JvmStatic
    fun setManagerPackage(packageName: String?) {
        val trimmed = packageName?.trim()
        if (!trimmed.isNullOrEmpty()) {
            activeManagerPackage = trimmed
        }
    }

    @JvmStatic
    fun getManagerPackage(): String = activeManagerPackage

    /**
     * App-owned external cache spool path accessible to both UID 0 / UID 2000 AND the
     * `untrusted_app` Manager under Android 10+ SELinux / FUSE rules, even when `shizuku_server`
     * is dead.
     */
    @JvmStatic
    @JvmOverloads
    fun spoolExtEmulatedPath(managerPackage: String = activeManagerPackage): String =
        "/storage/emulated/0/Android/data/$managerPackage/cache/$SPOOL_FILE_NAME"

    /**
     * App-owned internal DE cache spool path written by UID 0 (root) with matching app UID/GID
     * and inherited `app_data_file` SELinux label.
     */
    @JvmStatic
    @JvmOverloads
    fun spoolAppDeCachePath(managerPackage: String = activeManagerPackage): String =
        "/data/user_de/0/$managerPackage/cache/$SPOOL_FILE_NAME"

    /**
     * App-owned internal CE cache spool path written by UID 0 (root) with matching app UID/GID
     * and inherited `app_data_file` SELinux label.
     */
    @JvmStatic
    @JvmOverloads
    fun spoolAppCeCachePath(managerPackage: String = activeManagerPackage): String =
        "/data/data/$managerPackage/cache/$SPOOL_FILE_NAME"

    @JvmStatic
    val SPOOL_EXT_EMULATED_PATH: String
        get() = spoolExtEmulatedPath()

    @JvmStatic
    val SPOOL_APP_DE_CACHE_PATH: String
        get() = spoolAppDeCachePath()

    @JvmStatic
    val SPOOL_APP_CE_CACHE_PATH: String
        get() = spoolAppCeCachePath()

    const val MAX_SNAPSHOTS_RETAINED = 25
    private const val MAX_SPOOL_FILE_BYTES = 128 * 1024L

    data class SmartLogSnapshot(
        val id: String = UUID.randomUUID().toString().take(8),
        val timestampMs: Long = System.currentTimeMillis(),
        val source: String,
        val category: String,
        val severity: String,
        val summary: String,
        val details: String,
        val occurrences: Int = 1
    ) {
        val fingerprint: String
            get() = "$source|$category|${summary.take(120)}"

        fun formattedTimestamp(): String {
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
            } catch (_: Throwable) {
                timestampMs.toString()
            }
        }
    }

    @JvmStatic
    fun stackTraceToString(throwable: Throwable?): String {
        if (throwable == null) return ""
        return try {
            val sw = StringWriter(512)
            PrintWriter(sw).use { pw ->
                throwable.printStackTrace(pw)
            }
            sw.toString().trim()
        } catch (_: Throwable) {
            "${throwable.javaClass.name}: ${throwable.message}"
        }
    }

    @JvmStatic
    fun encodeBase64Payload(raw: String): String {
        return try {
            val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "$BASE64_PREFIX$encoded"
        } catch (_: Throwable) {
            raw
        }
    }

    @JvmStatic
    fun decodePayloadIfNeeded(payload: String?): String {
        if (payload.isNullOrBlank()) return ""
        val trimmed = payload.trim()
        if (!trimmed.startsWith(BASE64_PREFIX)) {
            return trimmed
        }
        val b64 = trimmed.removePrefix(BASE64_PREFIX).trim()
        return try {
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Throwable) {
            // java.util.Base64 is API 26+; never touch it on older devices.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ""
            try {
                String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        }
    }

    @JvmStatic
    fun serializeSnapshot(snapshot: SmartLogSnapshot): String {
        val cleanSummary = snapshot.summary.replace('\n', ' ').trim()
        val cleanDetails = snapshot.details
            .replace(BEGIN_MARKER, "[SNAPSHOT_BEGIN]")
            .replace(END_MARKER, "[SNAPSHOT_END]")
            .trim()
        return buildString(cleanDetails.length + 256) {
            appendLine(BEGIN_MARKER)
            appendLine("ID: ${snapshot.id}")
            appendLine("TIMESTAMP_MS: ${snapshot.timestampMs}")
            appendLine("SOURCE: ${snapshot.source}")
            appendLine("CATEGORY: ${snapshot.category}")
            appendLine("SEVERITY: ${snapshot.severity}")
            appendLine("OCCURRENCES: ${snapshot.occurrences}")
            appendLine("SUMMARY: $cleanSummary")
            appendLine("DETAILS:")
            appendLine(cleanDetails)
            appendLine(END_MARKER)
        }
    }

    @JvmStatic
    fun serializeSnapshots(snapshots: List<SmartLogSnapshot>): String {
        if (snapshots.isEmpty()) return ""
        val sb = StringBuilder(snapshots.size * 512)
        for (snap in snapshots) {
            sb.append(serializeSnapshot(snap))
        }
        return sb.toString()
    }

    @JvmStatic
    fun parseSnapshots(raw: String): List<SmartLogSnapshot> {
        val decoded = decodePayloadIfNeeded(raw)
        if (decoded.isBlank()) return emptyList()
        val results = ArrayList<SmartLogSnapshot>()
        var searchFrom = 0

        while (true) {
            val startIdx = decoded.indexOf(BEGIN_MARKER, searchFrom)
            if (startIdx < 0) break
            val bodyStart = startIdx + BEGIN_MARKER.length
            val endIdx = decoded.indexOf(END_MARKER, bodyStart)
            if (endIdx < 0) break

            val block = decoded.substring(bodyStart, endIdx).trim()
            parseSingleBlock(block)?.let { parsed ->
                mergeIntoList(results, parsed)
            }
            searchFrom = endIdx + END_MARKER.length
        }

        return results.takeLast(MAX_SNAPSHOTS_RETAINED)
    }

    private fun parseSingleBlock(block: String): SmartLogSnapshot? {
        val lines = block.lines()
        var id = ""
        var timestampMs = System.currentTimeMillis()
        var source = "UNKNOWN"
        var category = "GENERAL"
        var severity = "ERROR"
        var occurrences = 1
        var summary = ""
        val detailsBuilder = StringBuilder()
        var inDetails = false

        for (line in lines) {
            if (inDetails) {
                detailsBuilder.appendLine(line)
                continue
            }
            when {
                line.startsWith("ID:") -> id = line.substringAfter("ID:").trim()
                line.startsWith("TIMESTAMP_MS:") -> {
                    timestampMs = line.substringAfter("TIMESTAMP_MS:").trim().toLongOrNull()
                        ?: System.currentTimeMillis()
                }
                line.startsWith("SOURCE:") -> source = line.substringAfter("SOURCE:").trim()
                line.startsWith("CATEGORY:") -> category = line.substringAfter("CATEGORY:").trim()
                line.startsWith("SEVERITY:") -> severity = line.substringAfter("SEVERITY:").trim()
                line.startsWith("OCCURRENCES:") -> {
                    occurrences = line.substringAfter("OCCURRENCES:").trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
                }
                line.startsWith("SUMMARY:") -> summary = line.substringAfter("SUMMARY:").trim()
                line.startsWith("DETAILS:") -> inDetails = true
            }
        }

        if (summary.isBlank() && detailsBuilder.isEmpty()) return null
        if (id.isBlank()) {
            id = Integer.toHexString((timestampMs xor summary.hashCode().toLong()).toInt())
        }
        return SmartLogSnapshot(
            id = id,
            timestampMs = timestampMs,
            source = source,
            category = category,
            severity = severity,
            summary = summary.ifBlank { "$source:$category" },
            details = detailsBuilder.toString().trim(),
            occurrences = occurrences
        )
    }

    @JvmStatic
    fun mergeSnapshots(
        existing: List<SmartLogSnapshot>,
        incoming: List<SmartLogSnapshot>
    ): List<SmartLogSnapshot> {
        val merged = ArrayList<SmartLogSnapshot>(existing.size + incoming.size)
        for (item in existing) {
            mergeIntoList(merged, item)
        }
        for (item in incoming) {
            mergeIntoList(merged, item)
        }
        merged.sortBy { it.timestampMs }
        return if (merged.size > MAX_SNAPSHOTS_RETAINED) {
            merged.subList(merged.size - MAX_SNAPSHOTS_RETAINED, merged.size).toList()
        } else {
            merged
        }
    }

    private fun mergeIntoList(target: MutableList<SmartLogSnapshot>, candidate: SmartLogSnapshot) {
        val idx = target.indexOfFirst { it.id == candidate.id || it.fingerprint == candidate.fingerprint }
        if (idx >= 0) {
            val prev = target[idx]
            val combinedOccurrences = if (prev.id == candidate.id) {
                maxOf(prev.occurrences, candidate.occurrences)
            } else {
                prev.occurrences + candidate.occurrences
            }
            val latestTs = maxOf(prev.timestampMs, candidate.timestampMs)
            val richerDetails = if (candidate.details.length >= prev.details.length) candidate.details else prev.details
            target[idx] = prev.copy(
                timestampMs = latestTs,
                details = richerDetails,
                occurrences = combinedOccurrences
            )
        } else {
            target.add(candidate)
        }
    }

    /**
     * Appends a snapshot to a spool file on disk, pruning older entries if the file exceeds [MAX_SPOOL_FILE_BYTES].
     * When running as root (`UID 0`) and writing into the Manager's internal cache directory,
     * automatically copies the parent directory's UID/GID and SELinux security context (`app_data_file`)
     * so `untrusted_app` can read the file without SELinux denials even when `shizuku_server` is dead.
     */
    @JvmStatic
    fun appendSnapshotToFile(file: File, snapshot: SmartLogSnapshot, inheritParentOwnerAndSeContext: Boolean = false) {
        try {
            val parent = file.parentFile ?: return
            if (!parent.exists()) {
                if (inheritParentOwnerAndSeContext) {
                    // Do not create `/data/user_de/0/kerneldroid.nightzuku/cache` if the package dir doesn't exist
                    return
                }
                parent.mkdirs()
            }
            val existing = if (file.exists() && file.canRead()) {
                parseSnapshots(file.readText(Charsets.UTF_8))
            } else {
                emptyList()
            }
            val updated = mergeSnapshots(existing, listOf(snapshot))
            val serialized = serializeSnapshots(updated)
            val bytes = serialized.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_SPOOL_FILE_BYTES) {
                val trimmed = updated.takeLast(MAX_SNAPSHOTS_RETAINED / 2)
                file.writeText(serializeSnapshots(trimmed), Charsets.UTF_8)
            } else {
                file.writeText(serialized, Charsets.UTF_8)
            }
            if (inheritParentOwnerAndSeContext && runCatching { Process.myUid() == 0 }.getOrDefault(false)) {
                applyParentOwnerAndSeLinuxContext(parent, file)
            } else {
                file.setReadable(true, false)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Writes [snapshot] to all privileged and SELinux-accessible cross-process spool locations.
     */
    @JvmStatic
    fun appendSnapshotToAllSpools(snapshot: SmartLogSnapshot) {
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        // /data/local/tmp and the shell DE dir are only writable by the server (uid 2000/0);
        // writing them from untrusted_app would only produce SELinux denials.
        if (uid == 0 || uid == 2000) {
            appendSnapshotToFile(File(SPOOL_TMP_PATH), snapshot)
            appendSnapshotToFile(File(SPOOL_SHELL_PATH), snapshot)
        }
        // /sdcard is a symlink to /storage/emulated/0; writing both would duplicate the entry.
        appendSnapshotToFile(File(spoolExtEmulatedPath()), snapshot)
        if (uid == 0) {
            appendSnapshotToFile(File(spoolAppDeCachePath()), snapshot, inheritParentOwnerAndSeContext = true)
            appendSnapshotToFile(File(spoolAppCeCachePath()), snapshot, inheritParentOwnerAndSeContext = true)
        }
    }

    private fun applyParentOwnerAndSeLinuxContext(parentDir: File, targetFile: File) {
        try {
            val st = Os.stat(parentDir.absolutePath)
            Os.chown(targetFile.absolutePath, st.st_uid, st.st_gid)
            Os.chmod(targetFile.absolutePath, 384) // 0600
        } catch (_: Throwable) {
        }
        try {
            val selinuxClass = Class.forName("android.os.SELinux")
            val getFileContext = selinuxClass.getDeclaredMethod("getFileContext", String::class.java)
            val setFileContext = selinuxClass.getDeclaredMethod("setFileContext", String::class.java, String::class.java)
            val parentCtx = getFileContext.invoke(null, parentDir.absolutePath) as? String
            if (!parentCtx.isNullOrBlank()) {
                setFileContext.invoke(null, targetFile.absolutePath, parentCtx)
            }
        } catch (_: Throwable) {
        }
    }


    const val DEFAULT_FILTERED_LOGCAT_LINES = 80

    private val LOGCAT_TIME_REGEX = Regex(
        """^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+([VDIWEFA])/([^(:]+?)\s*\(\s*\d+\):\s?(.*)$"""
    )
    private val LOGCAT_THREADTIME_REGEX = Regex(
        """^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s+([^:]+?):\s?(.*)$"""
    )

    private val ALLOWED_TAG_PREFIXES = listOf(
        "Shizuku",
        "Nightzuku",
        "NightDog",
        "BinderSender",
        "SmartLog",
        "ServerSmartLog",
        "AdbClient",
        "AdbPairing",
        "AdbMdns",
        "AdbKey",
        "AdbModule",
        "Starter",
        "starter",
        "rish",
        "libadb",
        "libshizuku",
        "HiddenApi",
        "Android17Compat",
        "UsersCompat",
        "TransactGuard",
        "ClientRouting",
        "NightService",
        "UserService",
        "ConfigManager",
        "AuthorizationManager",
        "UserAccessPolicy",
        "Module",
        "AndroidRuntime",
        "DEBUG",
        "tombstoned",
        "System.err"
    )

    private val DENIED_TAG_PREFIXES = listOf(
        "OpenGLRenderer",
        "Adreno",
        "Mali",
        "Vulkan",
        "HWUI",
        "ThreadedRenderer",
        "RenderThread",
        "BLASTBufferQueue",
        "BufferQueue",
        "SurfaceComposerClient",
        "SurfaceControl",
        "SurfaceSyncGroup",
        "VRI[",
        "ViewRootImpl",
        "Choreographer",
        "InsetsController",
        "InsetsSourceConsumer",
        "ImeTracker",
        "InputMethodManager",
        "InputTransport",
        "InputEventReceiver",
        "WindowOnBackDispatcher",
        "WindowManager",
        "DisplayManager",
        "FrameTracker",
        "InteractionJankMonitor",
        "GraphicBuffer",
        "gralloc",
        "libEGL",
        "libGLES",
        "libvulkan",
        "Skia",
        "skia",
        "CompatChangeReporter",
        "ActivityThread",
        "LoadedApk",
        "ResourcesManager",
        "AssetManager",
        "ApplicationLoaders",
        "nativeloader",
        "ziparchive",
        "ProfileInstaller",
        "AutofillManager",
        "ContentCapture",
        "TextView",
        "AppCompatDelegate",
        "TrafficStats",
        "ConnectivityManager",
        "NetworkSecurityConfig",
        "StrictMode",
        "SystemSensorManager",
        "AudioManager",
        "MediaRouter",
        "Phenotype",
        "DynamiteModule",
        "GmsClient",
        "Firebase",
        "PlayCore",
        "SemDesktopMode",
        "SemDvfs",
        "SemWifi",
        "SecContentProvider",
        "Samsung",
        "DecorView",
        "ScrollIdentify",
        "MultiWindow",
        "Miui",
        "MIUI",
        "HyperOS",
        "Oplus",
        "OnePlus",
        "ColorOS",
        "Flyme",
        "Emui",
        "HwFramework",
        "Vivo",
        "Transsion",
        "MTK",
        "MediaTek",
        "Qti",
        "Qualcomm",
        "vendor.",
        "chatty",
        "libc-netbsd",
        "libc++",
        "Parcel",
        "Binder"
    )

    private val RELEVANT_MESSAGE_KEYWORDS = listOf(
        "kerneldroid.nightzuku",
        "moe.shizuku",
        "rikka.shizuku",
        "shizuku_server",
        "nightdog",
        "libadb.so",
        "libshizuku.so",
        "SecurityException",
        "NoSuchMethodError",
        "NoSuchFieldError",
        "UnsatisfiedLinkError",
        "DeadObjectException",
        "RemoteException",
        "SIGSEGV",
        "SIGABRT",
        "SIGBUS",
        "Fatal signal",
        "FATAL EXCEPTION",
        "ANR in",
        "avc: denied"
    )

    private val ART_NOISE_PATTERNS = listOf(
        "Background concurrent",
        "Background young concurrent",
        "Explicit concurrent",
        "Compiler allocated",
        "Clamp target GC heap",
        "WaitForGcToComplete",
        "Accessing hidden method",
        "Accessing hidden field",
        "Relocating",
        "Starting profile saver"
    )

    private fun isStackContinuation(message: String): Boolean {
        val trimmed = message.trimStart()
        return trimmed.startsWith("at ") ||
            trimmed.startsWith("Caused by:") ||
            trimmed.startsWith("Suppressed:") ||
            (trimmed.startsWith("... ") && trimmed.endsWith(" more"))
    }

    private fun containsRelevantKeyword(text: String): Boolean {
        val pkg = activeManagerPackage
        if (pkg != DEFAULT_MANAGER_PACKAGE && text.contains(pkg, ignoreCase = true)) {
            return true
        }
        return RELEVANT_MESSAGE_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }

    private fun isAllowedTag(tag: String): Boolean {
        return ALLOWED_TAG_PREFIXES.any { prefix ->
            tag.equals(prefix, ignoreCase = true) || tag.startsWith(prefix, ignoreCase = true)
        }
    }

    private fun isDeniedTag(tag: String): Boolean {
        return DENIED_TAG_PREFIXES.any { prefix ->
            tag.equals(prefix, ignoreCase = true) || tag.startsWith(prefix, ignoreCase = true)
        }
    }

    /**
     * Filters process logcat lines to retain Nightzuku/Shizuku, IPC, Rust native, and crash
     * diagnostics while stripping Android OS, GPU/HWUI, input, ART GC, and OEM framework noise.
     */
    @JvmStatic
    fun filterProcessLogcat(
        rawLines: List<String>,
        maxLines: Int = DEFAULT_FILTERED_LOGCAT_LINES
    ): String {
        if (rawLines.isEmpty() || maxLines <= 0) return ""
        val kept = ArrayList<Pair<String, String>>() // (dedupKey, fullLine)
        var lastKeptWasCrashOrException = false

        for (raw in rawLines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("--------- beginning of")) {
                continue
            }

            val match = LOGCAT_TIME_REGEX.matchEntire(line) ?: LOGCAT_THREADTIME_REGEX.matchEntire(line)
            if (match == null) {
                if (lastKeptWasCrashOrException && isStackContinuation(line)) {
                    kept.add(line to line)
                } else if (containsRelevantKeyword(line)) {
                    kept.add(line to line)
                    lastKeptWasCrashOrException = true
                } else {
                    lastKeptWasCrashOrException = false
                }
                continue
            }

            val level = match.groupValues[1][0]
            val tag = match.groupValues[2].trim()
            val message = match.groupValues[3]
            val hasKeyword = containsRelevantKeyword(message)

            if (lastKeptWasCrashOrException && isStackContinuation(message)) {
                kept.add("$level/$tag:$message" to line)
                continue
            }

            if (tag.equals("art", ignoreCase = true) || tag.equals("zygote", ignoreCase = true) || tag.equals("zygote64", ignoreCase = true)) {
                val isArtNoise = ART_NOISE_PATTERNS.any { message.contains(it, ignoreCase = true) }
                if (isArtNoise && !hasKeyword) {
                    lastKeptWasCrashOrException = false
                    continue
                }
                if (level != 'E' && level != 'F' && level != 'A' && !hasKeyword) {
                    lastKeptWasCrashOrException = false
                    continue
                }
            }

            val isFatal = level == 'F' || level == 'A'
            val isError = level == 'E' || isFatal

            val keep = when {
                isAllowedTag(tag) -> {
                    // For Nightzuku/Shizuku/crash tags, keep W/E/F or any line with relevant info; drop noisy V/D unless relevant
                    level != 'V' && (level != 'D' || hasKeyword || tag.contains("Shizuku", true) || tag.contains("Night", true) || tag.contains("SmartLog", true))
                }
                isDeniedTag(tag) -> {
                    // Strictly drop OS/OEM/HWUI noise. Info/warning lines from denied tags are
                    // discarded even when they mention our package name (ImeTracker, ActivityThread,
                    // Choreographer, etc. routinely log `kerneldroid.nightzuku:...`), otherwise the
                    // deny list would never suppress anything. Only real errors/fatals survive.
                    isFatal || (isError && hasKeyword)
                }
                else -> {
                    // Unknown third-party or OS subsystem tag: keep only errors/fatals mentioning Nightzuku/Shizuku/crash signals
                    isError && hasKeyword
                }
            }

            if (keep) {
                kept.add("$level/$tag:$message" to line)
                lastKeptWasCrashOrException = isError ||
                    tag.equals("AndroidRuntime", ignoreCase = true) ||
                    tag.equals("System.err", ignoreCase = true) ||
                    tag.equals("DEBUG", ignoreCase = true)
            } else {
                lastKeptWasCrashOrException = false
            }
        }

        if (kept.isEmpty()) return ""

        // Collapse consecutive identical messages to avoid flooding GitHub issues
        val collapsed = ArrayList<String>(kept.size)
        var i = 0
        while (i < kept.size) {
            val (key, line) = kept[i]
            var count = 1
            while (i + count < kept.size && kept[i + count].first == key) {
                count++
            }
            if (count > 1) {
                collapsed.add("$line [x$count]")
            } else {
                collapsed.add(line)
            }
            i += count
        }

        return collapsed.takeLast(maxLines).joinToString("\n")
    }

    /**
     * Formats one or multiple [SmartLogSnapshot]s into a unified human- and machine-readable
     * report separated by visual symbols and snapshot timestamps for GitHub Issues / PRs.
     */
    @JvmStatic
    fun formatMergedReport(
        headerDiagnostics: String,
        snapshots: List<SmartLogSnapshot>,
        qprCompatAudit: String? = null,
        logcatTail: String? = null
    ): String {
        val sb = StringBuilder(2048)
        val nowFormatted = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        } catch (_: Throwable) {
            System.currentTimeMillis().toString()
        }

        sb.appendLine("Generated: $nowFormatted")
        sb.appendLine()
        sb.appendLine("ENVIRONMENT")
        sb.appendLine(headerDiagnostics.trim())

        if (!qprCompatAudit.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("HIDDEN API COMPAT")
            sb.appendLine(qprCompatAudit.trim())
        }

        if (snapshots.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("SNAPSHOTS (${snapshots.size})")
            snapshots.forEachIndexed { index, snap ->
                if (index > 0) sb.appendLine()
                val repeatBadge = if (snap.occurrences > 1) " (x${snap.occurrences})" else ""
                sb.appendLine("[${index + 1}/${snapshots.size}] ${snap.formattedTimestamp()}$repeatBadge")
                sb.appendLine("${snap.source} | ${snap.category} | ${snap.severity} | ${snap.id}")
                sb.appendLine("Summary: ${snap.summary}")
                sb.appendLine(snap.details.ifBlank { "(no additional stack/details)" })
            }
        }

        if (!logcatTail.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("LOGCAT TAIL")
            sb.appendLine(logcatTail.trim())
        }

        return sb.toString().trim()
    }
}
