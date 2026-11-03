package rikka.shizuku.server

import java.io.StringReader
import kerneldroid.nightzuku.api.NightProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShizukuConfigTest {

    @Test
    fun `parseReader loads valid v3 config with signers, capabilities, nightDogEnabled, and accessPaused`() {
        val flags = ConfigManager.FLAG_ALLOWED or NightProtocol.CAP_BINDER_PROXY
        val json = """
            {
              "version": 3,
              "nightDogEnabled": true,
              "accessPaused": true,
              "packages": [
                {
                  "uid": 10234,
                  "flags": $flags,
                  "packages": ["com.example.app"],
                  "signers": ["aabbccddeeff0011"],
                  "lastConnectedAt": 1700000000000
                }
              ]
            }
        """.trimIndent()

        val config = ShizukuConfigManager.parseReader(StringReader(json))
        assertEquals(ShizukuConfig.LATEST_VERSION, config.version)
        assertTrue(config.nightDogEnabled)
        assertTrue(config.accessPaused)
        assertEquals(1, config.packages!!.size)

        val entry = config.packages!![0]
        assertEquals(10234, entry.uid)
        assertTrue(entry.isAllowed())
        assertFalse(entry.isDenied())
        assertEquals(NightProtocol.CAP_BINDER_PROXY, entry.flags and NightProtocol.MASK_CAPABILITIES)
        assertEquals(listOf("com.example.app"), entry.packages)
        assertEquals(listOf("aabbccddeeff0011"), entry.signers)
        assertEquals(1700000000000L, entry.lastConnectedAt)
    }

    @Test
    fun `parseReader rejects outdated, future, or malformed config versions by starting clean`() {
        val payloads = listOf(
            """{"version":1,"packages":[{"uid":10234,"flags":1,"packages":["a"]}]}""",
            """{"version":2,"accessPaused":true,"packages":[{"uid":10234,"flags":1,"packages":["a"]}]}""",
            """{"version":4,"accessPaused":true,"packages":[{"uid":10234,"flags":1,"packages":["a"]}]}""",
            """{"version":"3","accessPaused":true,"packages":[{"uid":10234,"flags":1,"packages":["a"]}]}""",
            """{"accessPaused":true,"packages":[{"uid":10234,"flags":1,"packages":["a"]}]}""",
            """[]"""
        )

        for (raw in payloads) {
            val config = ShizukuConfigManager.parseReader(StringReader(raw))
            assertEquals(raw, ShizukuConfig.LATEST_VERSION, config.version)
            assertFalse(raw, config.accessPaused)
            assertTrue(raw, config.packages!!.isEmpty())
        }
    }

    @Test
    fun `toJson round-trips ShizukuConfig fields cleanly`() {
        val original = ShizukuConfig(
            mutableListOf(
                ShizukuConfig.PackageEntry(10555, ConfigManager.FLAG_ALLOWED).apply {
                    packages = mutableListOf("com.example.client")
                    signers = mutableListOf("1122334455667788")
                    lastConnectedAt = 999_888_777L
                    expiresAt = 1_800_000_000_000L
                }
            )
        ).apply {
            nightDogEnabled = false
            accessPaused = true
        }

        val serialized = ShizukuConfigManager.toJson(original)
        val restored = ShizukuConfigManager.parseReader(StringReader(serialized))

        assertEquals(ShizukuConfig.LATEST_VERSION, restored.version)
        assertFalse(restored.nightDogEnabled)
        assertTrue(restored.accessPaused)
        assertEquals(1, restored.packages!!.size)
        assertEquals(10555, restored.packages!![0].uid)
        assertEquals(listOf("1122334455667788"), restored.packages!![0].signers)
        assertEquals(999_888_777L, restored.packages!![0].lastConnectedAt)
        assertEquals(1_800_000_000_000L, restored.packages!![0].expiresAt)
    }

    @Test
    fun `PackageEntry expiresAt denies access once expiration timestamp is reached`() {
        val entry = ShizukuConfig.PackageEntry(10777, ConfigManager.FLAG_ALLOWED).apply {
            expiresAt = 5_000L
        }
        assertFalse(entry.isExpired(nowMs = 4_999L))
        assertTrue(entry.isExpired(nowMs = 5_000L))
        assertTrue(entry.isExpired(nowMs = 6_000L))

        entry.expiresAt = System.currentTimeMillis() - 1_000L
        assertFalse("Expired entry must not report isAllowed() == true", entry.isAllowed())

        entry.expiresAt = System.currentTimeMillis() + 60_000L
        assertTrue("Active non-expired entry must report isAllowed() == true", entry.isAllowed())
    }
}
