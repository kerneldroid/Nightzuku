package moe.shizuku.manager.admin

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class DeviceOwnerBridgeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)

    @Test
    fun `returns false cleanly when app is not device owner`() {
        assertFalse(DeviceOwnerBridge.isDeviceOwner(context))
        assertFalse(DeviceOwnerBridge.ensureUsbDebuggingEnabled(context))
        assertFalse(DeviceOwnerBridge.setApplicationHidden(context, "com.example.app", true))
    }

    @Test
    fun `reports device owner active when provisioned`() {
        val admin = NightzukuDeviceAdminReceiver.getComponentName(context)
        shadowOf(dpm).setDeviceOwner(admin)

        assertTrue(DeviceOwnerBridge.isDeviceOwner(context))
        assertTrue(DeviceOwnerBridge.ensureUsbDebuggingEnabled(context))
    }
}
