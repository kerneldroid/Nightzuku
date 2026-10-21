package rikka.shizuku.server

import android.os.Binder
import android.os.IBinder
import kerneldroid.nightzuku.api.NightProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuApiConstants

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TransactGuardTest {

    @Test
    fun `verifyTargetBinder rejects null, self-referential, and privileged descriptor binders`() {
        val legacyEndpoint = Binder()
        val nightEndpoint = Binder()

        assertThrows(IllegalArgumentException::class.java) {
            TransactGuard.verifyTargetBinder(null, legacyEndpoint, nightEndpoint)
        }
        assertThrows(SecurityException::class.java) {
            TransactGuard.verifyTargetBinder(legacyEndpoint, legacyEndpoint, nightEndpoint)
        }
        assertThrows(SecurityException::class.java) {
            TransactGuard.verifyTargetBinder(nightEndpoint, legacyEndpoint, nightEndpoint)
        }

        val spoofedLegacyProxy = mock(IBinder::class.java)
        `when`(spoofedLegacyProxy.interfaceDescriptor).thenReturn(ShizukuApiConstants.BINDER_DESCRIPTOR)
        assertThrows(SecurityException::class.java) {
            TransactGuard.verifyTargetBinder(spoofedLegacyProxy, legacyEndpoint, nightEndpoint)
        }

        val spoofedNightProxy = mock(IBinder::class.java)
        `when`(spoofedNightProxy.interfaceDescriptor).thenReturn(NightProtocol.DESCRIPTOR)
        assertThrows(SecurityException::class.java) {
            TransactGuard.verifyTargetBinder(spoofedNightProxy, legacyEndpoint, nightEndpoint)
        }

        val systemServiceProxy = mock(IBinder::class.java)
        `when`(systemServiceProxy.interfaceDescriptor).thenReturn("android.content.pm.IPackageManager")
        TransactGuard.verifyTargetBinder(systemServiceProxy, legacyEndpoint, nightEndpoint)
    }

    @Test
    fun `effectiveCapabilities respects accessPaused, grant status, and explicit capability masks`() {
        val allowedDefault = ShizukuConfig.PackageEntry(10100, ConfigManager.FLAG_ALLOWED)
        assertEquals(
            NightProtocol.DEFAULT_CAPABILITIES,
            TransactGuard.effectiveCapabilities(allowedDefault, accessPaused = false)
        )
        assertEquals(
            0,
            TransactGuard.effectiveCapabilities(allowedDefault, accessPaused = true)
        )

        val denied = ShizukuConfig.PackageEntry(10100, ConfigManager.FLAG_DENIED)
        assertEquals(0, TransactGuard.effectiveCapabilities(denied, accessPaused = false))
        assertEquals(0, TransactGuard.effectiveCapabilities(null, accessPaused = false))

        val shellOnly = ShizukuConfig.PackageEntry(
            10100,
            ConfigManager.FLAG_ALLOWED or NightProtocol.CAP_SHELL_EXEC
        )
        assertEquals(
            NightProtocol.CAP_SHELL_EXEC,
            TransactGuard.effectiveCapabilities(shellOnly, accessPaused = false)
        )
    }

    @Test
    fun `enforceCapability blocks paused clients and missing capabilities while exempting manager`() {
        val configManager = mock(ShizukuConfigManager::class.java)
        val shellOnlyEntry = ShizukuConfig.PackageEntry(
            10200,
            ConfigManager.FLAG_ALLOWED or NightProtocol.CAP_SHELL_EXEC
        )
        `when`(configManager.find(10200)).thenReturn(shellOnlyEntry)
        `when`(configManager.isAccessPaused()).thenReturn(false)

        // Allowed capability succeeds
        TransactGuard.enforceCapability(
            configManager,
            callingUid = 10200,
            isManager = false,
            requiredCapability = NightProtocol.CAP_SHELL_EXEC,
            operationName = "execFast"
        )

        // Missing capability (CAP_BINDER_PROXY) is rejected
        assertThrows(SecurityException::class.java) {
            TransactGuard.enforceCapability(
                configManager,
                callingUid = 10200,
                isManager = false,
                requiredCapability = NightProtocol.CAP_BINDER_PROXY,
                operationName = "transactRemote"
            )
        }

        // Global pause blocks even granted capabilities for ordinary clients
        `when`(configManager.isAccessPaused()).thenReturn(true)
        assertThrows(SecurityException::class.java) {
            TransactGuard.enforceCapability(
                configManager,
                callingUid = 10200,
                isManager = false,
                requiredCapability = NightProtocol.CAP_SHELL_EXEC,
                operationName = "execFast"
            )
        }

        // Manager is always exempt even when accessPaused is true
        TransactGuard.enforceCapability(
            configManager,
            callingUid = 10001,
            isManager = true,
            requiredCapability = NightProtocol.CAP_BINDER_PROXY,
            operationName = "transactRemote"
        )
    }
}
