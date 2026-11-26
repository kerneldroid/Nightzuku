package moe.shizuku.manager.settings

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.receiver.AdbBootStartScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        AdbBootStartScheduler.cancel(context)
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

    @Test
    fun `schedules non-persisted unmetered boot job and cancels cleanly`() {
        assertTrue(AdbBootStartScheduler.schedule(context, bootCount = 7))

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobInfo = scheduler.getPendingJob(AdbBootStartScheduler.JOB_ID_ADB_BOOT_START)
        assertNotNull(jobInfo)
        assertFalse(jobInfo!!.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, jobInfo.networkType)
        assertEquals(7, jobInfo.extras.getInt(AdbBootStartScheduler.EXTRA_BOOT_COUNT, -1))

        AdbBootStartScheduler.cancel(context)
        assertNull(scheduler.getPendingJob(AdbBootStartScheduler.JOB_ID_ADB_BOOT_START))
    }

    @Test
    fun `tracks pre-unlock start-sent boot count independently of handled boot count`() {
        assertEquals(-1, ShizukuSettings.getLastBootStartSentCount())
        ShizukuSettings.setLastBootStartSentCount(12)
        assertEquals(12, ShizukuSettings.getLastBootStartSentCount())
        assertEquals(-1, ShizukuSettings.getLastHandledBootCount())
    }

    @Test
    fun `BootCompleteReceiver starts DirectBootStartService on LOCKED_BOOT_COMPLETED for ADB mode`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context)
        shadowApp.clearStartedServices()
        ShizukuSettings.setStartOnBootEnabled(true)
        ShizukuSettings.setLastLaunchMode(LaunchMethod.ADB)
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 42)

        val receiver = moe.shizuku.manager.receiver.BootCompleteReceiver()
        receiver.onReceive(context, android.content.Intent(android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED))

        val started = shadowApp.nextStartedService
        assertNotNull(started)
        assertEquals(
            moe.shizuku.manager.service.DirectBootStartService::class.java.name,
            started.component?.className
        )
        assertEquals(42, started.getIntExtra("boot_count", -1))
    }

    @Test
    fun `BootCompleteReceiver confirms via DirectBootStartService on BOOT_COMPLETED if pre-unlock start was sent`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context)
        shadowApp.clearStartedServices()
        ShizukuSettings.setStartOnBootEnabled(true)
        ShizukuSettings.setLastLaunchMode(LaunchMethod.ADB)
        ShizukuSettings.setLastBootStartSentCount(43)
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 43)

        val receiver = moe.shizuku.manager.receiver.BootCompleteReceiver()
        receiver.onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))

        val started = shadowApp.nextStartedService
        assertNotNull(started)
        assertEquals(
            moe.shizuku.manager.service.DirectBootStartService::class.java.name,
            started.component?.className
        )
        val scheduler = context.getSystemService(JobScheduler::class.java)
        assertNull(scheduler.getPendingJob(AdbBootStartScheduler.JOB_ID_ADB_BOOT_START))
    }

    @Test
    fun `BootCompleteReceiver schedules JobScheduler on BOOT_COMPLETED when no pre-unlock start was sent`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context)
        shadowApp.clearStartedServices()
        ShizukuSettings.setStartOnBootEnabled(true)
        ShizukuSettings.setLastLaunchMode(LaunchMethod.ADB)
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 44)

        val receiver = moe.shizuku.manager.receiver.BootCompleteReceiver()
        receiver.onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowApp.nextStartedService)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        assertNotNull(scheduler.getPendingJob(AdbBootStartScheduler.JOB_ID_ADB_BOOT_START))
    }

    @Test
    fun `EnvironmentUtils isUserUnlocked reflects UserManager lock state`() {
        val um = context.getSystemService(android.os.UserManager::class.java)
        org.robolectric.Shadows.shadowOf(um).setUserUnlocked(false)
        assertFalse(moe.shizuku.manager.utils.EnvironmentUtils.isUserUnlocked(context))

        org.robolectric.Shadows.shadowOf(um).setUserUnlocked(true)
        assertTrue(moe.shizuku.manager.utils.EnvironmentUtils.isUserUnlocked(context))
    }
}
