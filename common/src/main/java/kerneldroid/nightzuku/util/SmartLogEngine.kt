package kerneldroid.nightzuku.util

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

    const val SPOOL_TMP_PATH = "/data/local/tmp/nightzuku_smart.log"
    const val SPOOL_SHELL_PATH = "/data/user_de/0/com.android.shell/nightzuku_smart.log"

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
        if (raw.isBlank()) return emptyList()
        val results = ArrayList<SmartLogSnapshot>()
        var searchFrom = 0

        while (true) {
            val startIdx = raw.indexOf(BEGIN_MARKER, searchFrom)
            if (startIdx < 0) break
            val bodyStart = startIdx + BEGIN_MARKER.length
            val endIdx = raw.indexOf(END_MARKER, bodyStart)
            if (endIdx < 0) break

            val block = raw.substring(bodyStart, endIdx).trim()
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
     */
    @JvmStatic
    fun appendSnapshotToFile(file: File, snapshot: SmartLogSnapshot) {
        try {
            file.parentFile?.mkdirs()
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
            file.setReadable(true, false)
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

        sb.appendLine("╔══════════════════════════════════════════════════════════════════╗")
        if (snapshots.size > 1) {
            sb.appendLine("║ NIGHTZUKU SMART LOG • MERGED MULTI-ERROR REPORT (${snapshots.size} SNAPSHOTS)")
        } else if (snapshots.size == 1) {
            sb.appendLine("║ NIGHTZUKU SMART LOG • ERROR SNAPSHOT REPORT")
        } else {
            sb.appendLine("║ NIGHTZUKU SMART LOG • SYSTEM & COMPATIBILITY SNAPSHOT")
        }
        sb.appendLine("║ Generated: $nowFormatted")
        sb.appendLine("╚══════════════════════════════════════════════════════════════════╝")
        sb.appendLine()
        sb.appendLine("─── [1. ENVIRONMENT & SERVICE TELEMETRY] ──────────────────────────")
        sb.appendLine(headerDiagnostics.trim())

        if (!qprCompatAudit.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("─── [2. QPR / HIDDEN API COMPATIBILITY SNAPSHOT] ──────────────────")
            sb.appendLine(qprCompatAudit.trim())
        }

        if (snapshots.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("─── [3. CAPTURED ERROR SNAPSHOTS (${snapshots.size})] ───────────────────────────")
            snapshots.forEachIndexed { index, snap ->
                if (index > 0) {
                    sb.appendLine()
                    sb.appendLine("====================================================================")
                    sb.appendLine("          ▼ NEXT SNAPSHOT (${index + 1} of ${snapshots.size}) • ${snap.formattedTimestamp()} ▼")
                    sb.appendLine("====================================================================")
                } else {
                    sb.appendLine("====================================================================")
                }
                val repeatBadge = if (snap.occurrences > 1) " (x${snap.occurrences})" else ""
                sb.appendLine("[SNAPSHOT #${index + 1}/${snapshots.size}] ⏱ ${snap.formattedTimestamp()}$repeatBadge")
                sb.appendLine("Source: ${snap.source} | Category: ${snap.category} | Severity: ${snap.severity} | ID: ${snap.id}")
                sb.appendLine("Summary: ${snap.summary}")
                sb.appendLine("--------------------------------------------------------------------")
                sb.appendLine(snap.details.ifBlank { "(no additional stack/details)" })
                sb.appendLine("====================================================================")
            }
        }

        if (!logcatTail.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("─── [4. RECENT PROCESS LOGCAT TAIL] ───────────────────────────────")
            sb.appendLine(logcatTail.trim())
        }

        return sb.toString().trim()
    }
}
