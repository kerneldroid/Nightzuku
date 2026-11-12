package kerneldroid.nightzuku.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartLogEngineTest {

    @Test
    fun serializeAndParseRoundTripPreservesSnapshotsAndDeduplicates() {
        val snap1 = SmartLogEngine.SmartLogSnapshot(
            id = "a1b2c3d4",
            timestampMs = 1700000001000L,
            source = "QPR_COMPAT",
            category = "HIDDEN_API_DRIFT_GRANT_RUNTIME_PERMISSION",
            severity = "ERROR",
            summary = "Hidden API resolution failed for GRANT_RUNTIME_PERMISSION",
            details = "Matched Methods:\n  - void grantRuntimePermission(String, String, int, int)"
        )
        val snap2 = SmartLogEngine.SmartLogSnapshot(
            id = "e5f6g7h8",
            timestampMs = 1700000005000L,
            source = "RUST_PAIRING",
            category = "ADB_PAIRING_ERROR",
            severity = "ERROR",
            summary = "Wireless ADB pairing failed (nativeDecrypt)",
            details = "[RUST-ADB-PAIRING] AES-GCM peer info decryption failed"
        )

        val raw = SmartLogEngine.serializeSnapshots(listOf(snap1, snap2))
        val parsed = SmartLogEngine.parseSnapshots(raw)

        assertEquals(2, parsed.size)
        assertEquals("QPR_COMPAT", parsed[0].source)
        assertEquals("RUST_PAIRING", parsed[1].source)
        assertTrue(parsed[0].details.contains("grantRuntimePermission"))

        // Duplicate fingerprint increments occurrences
        val snap2Repeat = snap2.copy(id = "different", timestampMs = 1700000009000L)
        val merged = SmartLogEngine.mergeSnapshots(parsed, listOf(snap2Repeat))
        assertEquals(2, merged.size)
        assertEquals(2, merged[1].occurrences)
        assertEquals(1700000009000L, merged[1].timestampMs)
    }

    @Test
    fun formatMergedReportKeepsSnapshotsReadableWithoutBoxArt() {
        val s1 = SmartLogEngine.SmartLogSnapshot(
            id = "snap1",
            timestampMs = 1700000001000L,
            source = "RUST_STARTER",
            category = "SERVER_PROCESS_CRASH",
            severity = "ERROR",
            summary = "shizuku_server terminated with signal 11",
            details = "server_pid=4242\nsignal=11"
        )
        val s2 = SmartLogEngine.SmartLogSnapshot(
            id = "snap2",
            timestampMs = 1700000008000L,
            source = "MANAGER",
            category = "APP_UNCAUGHT_CRASH",
            severity = "FATAL",
            summary = "Manager crashed with IllegalStateException",
            details = "java.lang.IllegalStateException: Binder busy"
        )

        val report = SmartLogEngine.formatMergedReport(
            headerDiagnostics = "App: Nightzuku 13.7.1\nService: running",
            snapshots = listOf(s1, s2),
            qprCompatAudit = "[QPR / Hidden API Live Compatibility Probe]\n  • IPackageManager: OK"
        )

        assertTrue(report.contains("ENVIRONMENT"))
        assertTrue(report.contains("HIDDEN API COMPAT"))
        assertTrue(report.contains("SNAPSHOTS (2)"))
        assertTrue(report.contains("[1/2]"))
        assertTrue(report.contains("[2/2]"))
        assertTrue(report.contains("shizuku_server terminated with signal 11"))
        assertTrue(report.contains("Manager crashed with IllegalStateException"))
        assertTrue(!report.contains("╔"))
        assertTrue(!report.contains("NIGHTZUKU SMART LOG"))
    }

    @Test
    fun base64EncodedPushPayloadDecodesAndParsesCleanly() {
        val snap = SmartLogEngine.SmartLogSnapshot(
            id = "dead0001",
            timestampMs = 1700000012345L,
            source = "RUST_STARTER",
            category = "STARTER_SELINUX_BLOCKED",
            severity = "FATAL",
            summary = "SELinux policy blocks binder IPC",
            details = "su_context=u:r:su:s0"
        )
        val raw = SmartLogEngine.serializeSnapshot(snap)
        val b64Payload = "base64:" + java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        val parsed = SmartLogEngine.parseSnapshots(b64Payload)
        assertEquals(1, parsed.size)
        assertEquals("dead0001", parsed[0].id)
        assertEquals("STARTER_SELINUX_BLOCKED", parsed[0].category)

        // Verify HiddenApiIntrospector.exemptHiddenApis() is safe in unit test JVM
        HiddenApiIntrospector.exemptHiddenApis()
        val methods = HiddenApiIntrospector.getAllMethodsUnfiltered(String::class.java)
        assertTrue(methods.isNotEmpty())
    }
}
