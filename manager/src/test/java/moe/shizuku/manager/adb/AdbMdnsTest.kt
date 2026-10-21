package moe.shizuku.manager.adb

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class AdbMdnsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nsdShadow = shadowOf(context.getSystemService(NsdManager::class.java))

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `stop called before onDiscoveryStarted unregisters listener as soon as start callback arrives`() {
        val observed = mutableListOf<Pair<String, Int>>()
        val mdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { observed += it }

        mdns.start()
        idleMainLooper()
        val firstListener = nsdShadow.getDiscoveryListeners(AdbMdns.TLS_PAIRING)!!.single()

        // Stop before NsdManager delivers onDiscoveryStarted
        mdns.stop()
        idleMainLooper()

        // When onDiscoveryStarted finally fires, AdbMdns must immediately stop it
        firstListener.onDiscoveryStarted(AdbMdns.TLS_PAIRING)
        idleMainLooper()
        assertNull(nsdShadow.getDiscoveryListenerServiceType(firstListener))

        firstListener.onServiceFound(NsdServiceInfo().apply { serviceName = "stale-adb" })
        idleMainLooper()
        assertTrue(observed.isEmpty())
    }

    @Test
    fun `start after onStartDiscoveryFailed registers a fresh DiscoveryListener`() {
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) {}
        mdns.start()
        idleMainLooper()

        val failedListener = nsdShadow.getDiscoveryListeners(AdbMdns.TLS_CONNECT)!!.single()
        failedListener.onStartDiscoveryFailed(AdbMdns.TLS_CONNECT, NsdManager.FAILURE_INTERNAL_ERROR)
        idleMainLooper()

        mdns.start()
        idleMainLooper()
        val retryListener = nsdShadow.getDiscoveryListeners(AdbMdns.TLS_CONNECT)!!.last()
        assertNotSame(failedListener, retryListener)
        assertEquals(AdbMdns.TLS_CONNECT, nsdShadow.getDiscoveryListenerServiceType(retryListener))

        mdns.stop()
        idleMainLooper()
    }
}
