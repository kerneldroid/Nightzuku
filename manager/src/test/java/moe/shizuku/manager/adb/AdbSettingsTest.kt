package moe.shizuku.manager.adb

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class AdbSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val resolver get() = context.contentResolver

    @Before
    fun resetSettings() {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 0)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)
        Settings.Global.putLong(resolver, "adb_allowed_connection_time", 600_000L)
    }

    @Test
    fun `detects fully enabled wireless adb`() {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(resolver, "adb_allowed_connection_time", 0L)

        assertTrue(AdbSettings.isWirelessAdbEnabled(resolver))
    }

    @Test
    fun `reports disabled when the connection timer is still set`() {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)

        assertFalse(AdbSettings.isWirelessAdbEnabled(resolver))
    }

    @Test
    fun `writes missing keys when secure settings permission is granted`() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

        assertTrue(AdbSettings.enableWirelessAdb(context))
        assertEquals(1, Settings.Global.getInt(resolver, "adb_wifi_enabled", 0))
        assertEquals(1, Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0))
        assertEquals(0L, Settings.Global.getLong(resolver, "adb_allowed_connection_time", -1L))
    }

    @Test
    fun `already enabled state needs no permission`() {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(resolver, "adb_allowed_connection_time", 0L)

        assertTrue(AdbSettings.enableWirelessAdb(context))
    }
}
