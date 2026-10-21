package rikka.shizuku.server

import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import kerneldroid.nightzuku.api.NightProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.ClientRouting.Wire

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClientRoutingTest {

    private fun packageWith(
        pkg: String,
        requested: Array<String>? = null,
        declared: Array<String>? = null
    ): PackageInfo {
        return PackageInfo().apply {
            packageName = pkg
            requestedPermissions = requested
            permissions = declared?.map { name ->
                PermissionInfo().apply { this.name = name }
            }?.toTypedArray()
        }
    }

    @Test
    fun `native Nightzuku permission routes to NIGHTZUKU wire`() {
        val nativeOnly = packageWith("com.example.native", arrayOf(ServerConstants.PERMISSION))
        assertEquals(Wire.NIGHTZUKU, ClientRouting.route(nativeOnly))
        assertFalse(ClientRouting.supportsLegacyWire(nativeOnly))

        val dualWire = packageWith(
            "com.example.dual",
            arrayOf(ServerConstants.LEGACY_PERMISSION, ServerConstants.PERMISSION)
        )
        assertEquals(Wire.NIGHTZUKU, ClientRouting.route(dualWire))
        assertTrue(ClientRouting.supportsLegacyWire(dualWire))
    }

    @Test
    fun `legacy Shizuku permission routes to SHIZUKU wire`() {
        val legacyOnly = packageWith("com.example.legacy", arrayOf(ServerConstants.LEGACY_PERMISSION))
        assertEquals(Wire.SHIZUKU, ClientRouting.route(legacyOnly))
        assertTrue(ClientRouting.supportsLegacyWire(legacyOnly))
    }

    @Test
    fun `foreign manager packages and unrelated apps are excluded from client routing`() {
        val foreignManager = packageWith(
            "com.other.manager",
            requested = arrayOf(ServerConstants.PERMISSION),
            declared = arrayOf("com.other.manager.permission.MANAGER")
        )
        assertNull(ClientRouting.route(foreignManager))

        val unrelated = packageWith("com.example.plain", arrayOf("android.permission.INTERNET"))
        assertNull(ClientRouting.route(unrelated))
        assertNull(ClientRouting.route(null))
    }

    @Test
    fun `providerSuffix maps each wire to its dedicated ContentProvider authority suffix`() {
        assertEquals(NightProtocol.PROVIDER_SUFFIX, ClientRouting.providerSuffix(Wire.NIGHTZUKU))
        assertEquals(NightProtocol.LEGACY_PROVIDER_SUFFIX, ClientRouting.providerSuffix(Wire.SHIZUKU))
    }
}
