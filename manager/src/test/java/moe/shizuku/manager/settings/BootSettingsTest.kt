package moe.shizuku.manager.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class BootSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        ShizukuSettings.initialize(context)
        ShizukuSettings.getPreferences().edit().clear().commit()
    }

    @Test
    fun `forces wireless ADB when device is not rooted even if root was stored`() {
        ShizukuSettings.setLastLaunchMode(LaunchMethod.ROOT)

        assertEquals(LaunchMethod.ADB, ShizukuSettings.resolveBootLaunchMethod(isRooted = false))
        assertEquals(LaunchMethod.ROOT, ShizukuSettings.resolveBootLaunchMethod(isRooted = true))
    }

    @Test
    fun `defaults to wireless ADB when last launch mode is unknown`() {
        assertEquals(LaunchMethod.ADB, ShizukuSettings.resolveBootLaunchMethod(isRooted = true))
        assertEquals(LaunchMethod.ADB, ShizukuSettings.resolveBootLaunchMethod(isRooted = false))
    }

    @Test
    fun `clamps autostart timeout and retries and persists fallback toggle`() {
        ShizukuSettings.setBootDiscoveryTimeoutSeconds(5)
        assertEquals(10, ShizukuSettings.getBootDiscoveryTimeoutSeconds())

        ShizukuSettings.setBootDiscoveryTimeoutSeconds(45)
        assertEquals(45, ShizukuSettings.getBootDiscoveryTimeoutSeconds())

        ShizukuSettings.setBootMaxRetries(0)
        assertEquals(1, ShizukuSettings.getBootMaxRetries())

        ShizukuSettings.setBootMaxRetries(6)
        assertEquals(6, ShizukuSettings.getBootMaxRetries())

        assertTrue(ShizukuSettings.isBootFallbackNotificationEnabled())
        ShizukuSettings.setBootFallbackNotificationEnabled(false)
        assertFalse(ShizukuSettings.isBootFallbackNotificationEnabled())
    }
}
