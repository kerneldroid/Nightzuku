package moe.shizuku.manager.module

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbModuleArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `copyStreamBounded copies within budget and aborts mid-stream when limit is exceeded`() {
        val payload = ByteArray(16 * 1024) { (it and 0xFF).toByte() }
        val out = ByteArrayOutputStream()
        val copied = AdbModuleManager.copyStreamBounded(
            ByteArrayInputStream(payload),
            out,
            maxBytes = 16 * 1024L
        )
        assertEquals(16 * 1024L, copied)
        assertArrayEquals(payload, out.toByteArray())

        val oversizedOut = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            AdbModuleManager.copyStreamBounded(
                ByteArrayInputStream(payload),
                oversizedOut,
                maxBytes = 4096L
            )
        }
        assertTrue("Must abort before writing entire oversized stream", oversizedOut.size() <= 4096)
    }

    @Test
    fun `cleanZipName normalizes safe relative paths and rejects traversal segments`() {
        assertEquals("common/service.sh", AdbModuleManager.cleanZipName("/common\\service.sh/"))
        assertEquals("module.prop", AdbModuleManager.cleanZipName("module.prop"))

        for (unsafe in listOf("../evil.sh", "a/../../b", "a/./b", "/", "   ")) {
            assertThrows("Expected rejection for '$unsafe'", IllegalArgumentException::class.java) {
                AdbModuleManager.cleanZipName(unsafe)
            }
        }
    }

    @Test
    fun `ensureInside blocks canonical path escapes outside staging directory`() {
        val root = tempFolder.newFolder("staging")
        val inside = File(root, "webroot/index.html")
        AdbModuleManager.ensureInside(root, inside)

        val outside = File(root, "../escaped.sh")
        assertThrows(IllegalArgumentException::class.java) {
            AdbModuleManager.ensureInside(root, outside)
        }
    }

    @Test
    fun `parseModuleProp parses key-value pairs and ignores comments and blank lines`() {
        val raw = """
            # Module descriptor
            id = night_perf
            name=Night Performance Pack
            version = v1.4.2
            versionCode=142
            usesShellBridge = true
        """.trimIndent()

        val parsed = AdbModuleManager.parseModuleProp(raw)
        assertEquals("night_perf", parsed["id"])
        assertEquals("Night Performance Pack", parsed["name"])
        assertEquals("v1.4.2", parsed["version"])
        assertEquals("142", parsed["versionCode"])
        assertEquals("true", parsed["usesShellBridge"])
    }

    @Test
    fun `verifyModuleHostRequirements enforces Nightzuku runtime and API minimums`() {
        assertEquals(
            null,
            ModuleRuntimeUtils.verifyModuleHostRequirements(
                mapOf(
                    "id" to "night_perf",
                    "requireNightzuku" to "true",
                    "minNightzukuApi" to "1",
                    "minNightzukuVersion" to "13"
                )
            )
        )

        val apiError = ModuleRuntimeUtils.verifyModuleHostRequirements(
            mapOf(
                "id" to "future_mod",
                "minNightzukuApi" to "999"
            )
        )
        assertTrue(apiError?.contains("v999") == true)

        val versionError = ModuleRuntimeUtils.verifyModuleHostRequirements(
            mapOf(
                "id" to "future_mod",
                "minNightzukuVersion" to "999999999"
            )
        )
        assertTrue(versionError?.contains("versionCode") == true)
    }

    @Test
    fun `attestation tokens and digests are deterministic per signer, module, and challenge and written to env file`() {
        val modDir = tempFolder.newFolder("mod_test")
        val signer = "a1b2c3d4e5f60123456789abcdefa1b2c3d4e5f60123456789abcdef01234567"

        val token = ModuleRuntimeUtils.computeAttestationToken("mod_test", "nonce-123", signer)
        val digest = ModuleRuntimeUtils.computeAttestationDigest("mod_test", "nonce-123", signer)
        assertEquals(64, token.length)
        assertEquals(64, digest.length)
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(token, ModuleRuntimeUtils.computeAttestationToken("mod_test", "nonce-123", "A1:B2:${signer.substring(4)}"))
        assertFalse(token == ModuleRuntimeUtils.computeAttestationToken("mod_test", "nonce-456", signer))
        assertFalse(token == ModuleRuntimeUtils.computeAttestationToken("other_mod", "nonce-123", signer))

        val envFile = ModuleRuntimeUtils.writeModuleAttestationFile(modDir, "mod_test", signer, token, digest)
        assertTrue(envFile?.isFile == true)
        val envContent = envFile!!.readText()
        assertTrue(envContent.contains("NIGHTZUKU_HOST_PKG=kerneldroid.nightzuku"))
        assertTrue(envContent.contains("NIGHTZUKU_BINDER_DESCRIPTOR=kerneldroid.nightzuku.INightService"))
        assertTrue(envContent.contains("NIGHTZUKU_SESSION_TOKEN=$token"))
        assertTrue(envContent.contains("NIGHTZUKU_ATTESTATION_SHA256=$digest"))

        assertEquals(
            "/data/local/tmp/kerneldroid.nightzuku/adb_modules/mod_test",
            ModuleRuntimeUtils.shellModuleDirectory("mod_test").path.replace('\\', '/')
        )
    }

    @Test
    fun `relativePathOrNull enforces canonical containment without java nio`() {
        val root = tempFolder.newFolder("root")
        val nested = File(root, "a/b.txt").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        val sibling = tempFolder.newFolder("root-other")

        assertEquals("a/b.txt", ModuleRuntimeUtils.relativePathOrNull(root, nested))
        assertEquals("", ModuleRuntimeUtils.relativePathOrNull(root, root))
        assertNull(ModuleRuntimeUtils.relativePathOrNull(root, sibling))
        assertNull(ModuleRuntimeUtils.relativePathOrNull(root, File(root.parentFile, "root-other/outside.txt")))
    }

    @Test
    fun `tar sync round trip preserves files and executable bits`() {
        val source = tempFolder.newFolder("module-src")
        val target = tempFolder.newFolder("module-target")
        File(source, "module.prop").writeText("id=test\n")
        File(source, "script.sh").apply {
            writeText("#!/system/bin/sh\necho ok\n")
            setExecutable(true, true)
        }
        File(source, "logs").mkdirs()
        File(source, "logs/action-last.log").writeText("skip me")

        val archive = ByteArrayOutputStream()
        ModuleRuntimeUtils.writeDirectoryAsTar(source, archive)
        val result = ModuleRuntimeUtils.readTarIntoDirectory(ByteArrayInputStream(archive.toByteArray()), target)

        assertTrue(result.validEndOfArchive)
        assertFalse(result.limitReached)
        assertFalse(result.truncated)
        assertEquals("#!/system/bin/sh\necho ok\n", File(target, "script.sh").readText())
        assertTrue(File(target, "script.sh").canExecute())
        assertFalse(File(target, "logs/action-last.log").exists())
        assertTrue(result.seenPaths.contains("script.sh"))
        assertFalse(result.seenPaths.contains("module.prop"))
    }

    @Test
    fun `tar sync never extracts paths outside the target directory`() {
        val target = tempFolder.newFolder("sync-target")
        val outside = File(target.parentFile, "evil.txt")
        val archive = ByteArrayOutputStream()
        val tarOut = TarArchiveOutputStream(archive)
        val entry = TarArchiveEntry("../evil.txt")
        entry.size = 5L
        tarOut.putArchiveEntry(entry)
        tarOut.write("evil!".toByteArray())
        tarOut.closeArchiveEntry()
        tarOut.finish()

        val result = ModuleRuntimeUtils.readTarIntoDirectory(ByteArrayInputStream(archive.toByteArray()), target)

        assertFalse(outside.exists())
        assertFalse(result.truncated)
    }

}
