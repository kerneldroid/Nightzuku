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
    fun `detects enabled wireless adb even when connection timer is still set`() {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)

        assertTrue(AdbSettings.isWirelessAdbEnabled(resolver))
    }

    @Test
    fun `detects enabled wireless adb on Android TV 14 when usb debugging adb_enabled is off`() {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)

        assertTrue(AdbSettings.isWirelessAdbEnabled(resolver))
        assertTrue(AdbSettings.enableWirelessAdb(context))
        assertEquals(
            AdbSettings.BootPrepareResult.ENABLED,
            AdbSettings.prepareWirelessAdbForBoot(context, sdkInt = 34)
        )
    }

    @Test
    fun `reports disabled when adb_wifi_enabled is off`() {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 0)
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
    fun `already enabled state succeeds without permission even when connection timer is non-zero`() {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)

        assertTrue(AdbSettings.enableWirelessAdb(context))
        assertEquals(
            AdbSettings.BootPrepareResult.ENABLED,
            AdbSettings.prepareWirelessAdbForBoot(context, sdkInt = 34)
        )
    }

    @Test
    fun `already enabled state best-effort clears connection timer when permission is granted`() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)

        assertTrue(AdbSettings.enableWirelessAdb(context))
        assertEquals(1, Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0))
        assertEquals(0L, Settings.Global.getLong(resolver, "adb_allowed_connection_time", -1L))
    }

    @Test
    fun `android 17 boot preparation defers to mDNS despite redacted zero settings`() {
        assertTrue(AdbSettings.isReadRedactedPlatform(37))
        assertFalse(AdbSettings.isReadRedactedPlatform(36))
        assertEquals(
            AdbSettings.BootPrepareResult.REDACTION_DEFER_TO_MDNS,
            AdbSettings.prepareWirelessAdbForBoot(context, sdkInt = 37)
        )
    }

    @Test
    fun `android 14 boot preparation enables keys when WRITE_SECURE_SETTINGS is held`() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertEquals(
            AdbSettings.BootPrepareResult.ENABLED,
            AdbSettings.prepareWirelessAdbForBoot(context, sdkInt = 34)
        )
    }
}
