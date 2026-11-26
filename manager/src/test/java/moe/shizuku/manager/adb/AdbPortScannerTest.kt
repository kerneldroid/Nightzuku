package moe.shizuku.manager.adb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class AdbPortScannerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `buildOrder prioritizes odd lower half before upper odd and even without duplicates`() {
        val order = AdbPortScanner.buildOrder(history = emptyList(), ipLocalPortRange = null)
        val expectedCount = AdbPortScanner.PORT_MAX - AdbPortScanner.PORT_MIN + 1
        assertEquals(expectedCount, order.size)
        assertEquals(expectedCount, order.toSet().size)

        val lowerOddEnd = 46883
        val idxLowerOddSample = order.indexOf(41111)
        val idxUpperOddSample = order.indexOf(51111)
        val idxEvenSample = order.indexOf(34000)

        assertTrue(idxLowerOddSample in 0..7057)
        assertTrue(idxUpperOddSample > idxLowerOddSample)
        assertTrue(idxEvenSample > idxUpperOddSample)
        assertTrue(order.indexOf(lowerOddEnd) < order.indexOf(46885))
    }

    @Test
    fun `buildOrder prioritizes readable ip_local_port_range and port history`() {
        val order = AdbPortScanner.buildOrder(
            history = listOf(39123),
            ipLocalPortRange = 37000..50000
        )
        // Lower half of 37000..50000 is 37000..43499; odd ports there come first.
        val band = AdbPortScanner.kernelLowerHalf(37000, 50000)
        assertEquals(37000..43499, band)
        assertEquals(37001, order[0])
        assertTrue(order.indexOf(39123) < order.indexOf(44501))
        assertTrue(order.indexOf(44501) < order.indexOf(37002))

        val historyOnlyOrder = AdbPortScanner.buildOrder(
            history = listOf(55111),
            ipLocalPortRange = null
        )
        assertTrue(historyOnlyOrder.indexOf(55111) < historyOnlyOrder.indexOf(32769))
    }

    @Test
    fun `parsePortRange parses proc and sysctl outputs and rejects invalid inputs`() {
        assertEquals(32768..60999, AdbPortScanner.parsePortRange("32768\t60999\n"))
        assertEquals(
            37000..50000,
            AdbPortScanner.parsePortRange("net.ipv4.ip_local_port_range = 37000\t50000")
        )
        assertNull(AdbPortScanner.parsePortRange("50000 37000"))
        assertNull(AdbPortScanner.parsePortRange("80 60000"))
        assertNull(AdbPortScanner.parsePortRange("not a range"))
    }

    @Test
    fun `isValidAdbProbeHeader enforces magic and TLS requirement`() {
        val stls = AdbMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null).toByteArray()
        val auth = AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, null).toByteArray()
        val http = "HTTP/1.1 400 Bad Request!".toByteArray()

        assertTrue(AdbPortScanner.isValidAdbProbeHeader(stls, requireTlsOnly = true))
        assertFalse(AdbPortScanner.isValidAdbProbeHeader(auth, requireTlsOnly = true))
        assertTrue(AdbPortScanner.isValidAdbProbeHeader(auth, requireTlsOnly = false))
        assertFalse(AdbPortScanner.isValidAdbProbeHeader(http, requireTlsOnly = false))

        // Corrupt magic
        val badMagic = stls.copyOf()
        badMagic[20] = (badMagic[20].toInt() xor 0xFF).toByte()
        assertFalse(AdbPortScanner.isValidAdbProbeHeader(badMagic, requireTlsOnly = true))
    }

    @Test
    fun `scanner probes live loopback socket and matches mock adbd STLS while ignoring non-ADB port`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val decoySocket = ServerSocket(0, 4, loopback)
        val adbSocket = ServerSocket(0, 4, loopback)
        val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }

        val decoyThread = thread(isDaemon = true) {
            while (!decoySocket.isClosed) {
                runCatching {
                    decoySocket.accept().use { client ->
                        client.getOutputStream().apply {
                            write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                            flush()
                        }
                    }
                }
            }
        }

        val sawValidCnxn = CountDownLatch(1)
        val adbThread = thread(isDaemon = true) {
            while (!adbSocket.isClosed) {
                runCatching {
                    adbSocket.accept().use { client ->
                        val ins = client.getInputStream()
                        val header = ByteArray(AdbMessage.HEADER_LENGTH)
                        var n = 0
                        while (n < header.size) {
                            val r = ins.read(header, n, header.size - n)
                            if (r < 0) break
                            n += r
                        }
                        if (n == header.size) {
                            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                            val cmd = buf.int
                            if (cmd == AdbProtocol.A_CNXN) {
                                sawValidCnxn.countDown()
                                val reply = AdbMessage(
                                    AdbProtocol.A_STLS,
                                    AdbProtocol.A_STLS_VERSION,
                                    0,
                                    null
                                ).toByteArray()
                                client.getOutputStream().apply {
                                    write(reply)
                                    flush()
                                }
                            }
                        }
                    }
                }
            }
        }

        val foundPort = AtomicInteger(-1)
        val foundLatch = CountDownLatch(1)
        val scanner = AdbPortScanner(
            order = intArrayOf(closedPort, decoySocket.localPort, adbSocket.localPort),
            requireTlsOnly = true,
            threads = 3,
            connectTimeoutMs = 400,
            probeTimeoutMs = 500
        ) { port ->
            foundPort.set(port)
            foundLatch.countDown()
        }

        try {
            scanner.start()
            assertTrue("Expected mock adbd to receive A_CNXN", sawValidCnxn.await(5, TimeUnit.SECONDS))
            assertTrue("Expected AdbPortScanner to discover mock adbd port", foundLatch.await(5, TimeUnit.SECONDS))
            assertEquals(adbSocket.localPort, foundPort.get())
        } finally {
            scanner.stop()
            runCatching { decoySocket.close() }
            runCatching { adbSocket.close() }
            decoyThread.join(500)
            adbThread.join(500)
        }
    }

    @Test
    fun `port history persists deduplicated ports capped at ten entries`() {
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("adb_port_history", Application.MODE_PRIVATE)
        prefs.edit().clear().commit()

        for (p in 33001..33012) {
            AdbPortHistory.record(context, p)
        }
        AdbPortHistory.record(context, 33005)

        val loaded = AdbPortHistory.load(context)
        assertEquals(10, loaded.size)
        assertEquals(33005, loaded.last())
        assertEquals(1, loaded.count { it == 33005 })
        assertNotNull(loaded.firstOrNull { it == 33004 })
    }
}
