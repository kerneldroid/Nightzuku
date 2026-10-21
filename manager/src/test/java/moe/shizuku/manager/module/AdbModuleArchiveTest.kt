package moe.shizuku.manager.module

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
