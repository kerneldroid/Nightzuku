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
    const val MANAGER_PROVIDER_AUTHORITY = "kerneldroid.nightzuku.shizuku"

    /** Server-to-server spool paths (readable by UID 0 / UID 2000). */
    const val SPOOL_TMP_PATH = "/data/local/tmp/nightzuku_smart.log"
    const val SPOOL_SHELL_PATH = "/data/user_de/0/com.android.shell/nightzuku_smart.log"

    /**
     * App-owned external cache spool paths accessible to both UID 0 / UID 2000 AND the
     * `untrusted_app` Manager (`kerneldroid.nightzuku`) under Android 10+ SELinux / FUSE rules,
     * even when `shizuku_server` is dead.
     */
    const val SPOOL_EXT_EMULATED_PATH = "/storage/emulated/0/Android/data/kerneldroid.nightzuku/cache/nightzuku_smart.log"

    /**
     * App-owned internal DE/CE cache spool paths written by UID 0 (root) with matching app UID/GID
     * and inherited `app_data_file` SELinux label.
     */
    const val SPOOL_APP_DE_CACHE_PATH = "/data/user_de/0/kerneldroid.nightzuku/cache/nightzuku_smart.log"
    const val SPOOL_APP_CE_CACHE_PATH = "/data/data/kerneldroid.nightzuku/cache/nightzuku_smart.log"
    const val SPOOL_FILE_NAME = "nightzuku_smart.log"

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
        appendSnapshotToFile(File(SPOOL_EXT_EMULATED_PATH), snapshot)
        if (uid == 0) {
            appendSnapshotToFile(File(SPOOL_APP_DE_CACHE_PATH), snapshot, inheritParentOwnerAndSeContext = true)
            appendSnapshotToFile(File(SPOOL_APP_CE_CACHE_PATH), snapshot, inheritParentOwnerAndSeContext = true)
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
