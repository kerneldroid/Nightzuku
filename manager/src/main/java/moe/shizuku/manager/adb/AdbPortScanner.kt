package moe.shizuku.manager.adb

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Discovers the local wireless-debugging (`adbd` TLS) port over loopback (`127.0.0.1`) in parallel
 * with [AdbMdns].
 *
 * `adbd` binds its ephemeral TLS listener within milliseconds of `adb_wifi_enabled = 1`, while
 * mDNS registration can lag by ~1-2 s or fail during early boot (`kDNSServiceErr_ServiceNotRunning`).
 * Because `service.adb.tls.port` is SELinux-restricted to apps (`adbd_prop`), probing loopback
 * directly with an ADB `A_CNXN` packet and checking for a valid `A_STLS` header provides fast,
 * permission-free discovery.
 *
 * Scan order prioritizes Linux's `inet_csk_find_open_port` behavior (odd ports in the lower half
 * of `ip_local_port_range` when `SO_REUSEADDR` is used) plus ports observed on prior boots.
 *
 * Port-scanning approach credited to Dimlos-jp/Dimzuku and BombPerry/Shizuku.
 */
class AdbPortScanner(
    private val log: ((String) -> Unit)? = null,
    private val order: IntArray = buildOrder(),
    private val host: String = LOOPBACK_IP,
    private val requireTlsOnly: Boolean = true,
    private val threads: Int = DEFAULT_THREADS,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val probeTimeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS,
    private val onPort: (Int) -> Unit,
) {

    private val generation = AtomicInteger(0)
    private val hit = AtomicBoolean(false)

    @Volatile
    private var activeThread: Thread? = null

    @Volatile
    private var activePool: ExecutorService? = null

    fun start() {
        val gen = generation.incrementAndGet()
        hit.set(false)
        val thread = Thread({ scanLoop(gen) }, "adb-port-scan").apply {
            isDaemon = true
        }
        activeThread = thread
        thread.start()
    }

    fun stop() {
        generation.incrementAndGet()
        runCatching { activePool?.shutdownNow() }
        runCatching { activeThread?.interrupt() }
        activePool = null
        activeThread = null
    }

    private fun scanLoop(gen: Int) {
        val t0 = SystemClock.elapsedRealtime()
        val targetAddr = runCatching { InetAddress.getByName(host) }.getOrElse {
            log?.invoke("scan: failed to resolve host=$host: $it")
            return
        }
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1)) { r ->
            Thread(r, "adb-scan-w").apply { isDaemon = true }
        }
        activePool = pool
        val cnxnPacket = buildCnxnPacket()

        try {
            var pass = 0
            while (gen == generation.get() && !hit.get()) {
                pass++
                val tp = SystemClock.elapsedRealtime()
                val next = AtomicInteger(0)
                val openCount = AtomicInteger(0)
                val workerCount = threads.coerceIn(1, order.size.coerceAtLeast(1))
                val tasks = (1..workerCount).map {
                    Callable {
                        while (gen == generation.get() && !hit.get()) {
                            val idx = next.getAndIncrement()
                            if (idx >= order.size) break
                            val port = order[idx]
                            val ok = probePort(targetAddr, port, cnxnPacket, openCount)
                            if (ok && gen == generation.get() && hit.compareAndSet(false, true)) {
                                val elapsed = SystemClock.elapsedRealtime() - t0
                                log?.invoke(
                                    "scan: matched adbd on $host:$port (+${elapsed}ms, pass=$pass, pos=$idx/${order.size})"
                                )
                                onPort(port)
                                break
                            }
                        }
                    }
                }
                pool.invokeAll(tasks)
                if (pass == 1 || hit.get()) {
                    val reached = minOf(next.get(), order.size)
                    log?.invoke(
                        "scan: pass=$pass took ${SystemClock.elapsedRealtime() - tp}ms, " +
                            "open=$openCount, reached=$reached/${order.size}"
                    )
                }
                if (!hit.get() && gen == generation.get()) {
                    Thread.sleep(PASS_GAP_MS)
                }
            }
        } catch (_: InterruptedException) {
            // Expected on stop()
        } catch (t: Throwable) {
            log?.invoke("scan: stopped/failed: $t")
        } finally {
            pool.shutdownNow()
            if (activePool === pool) {
                activePool = null
            }
        }
    }

    internal fun probePort(
        addr: InetAddress,
        port: Int,
        cnxnPacket: ByteArray = buildCnxnPacket(),
        openCounter: AtomicInteger? = null,
    ): Boolean {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(addr, port), connectTimeoutMs)
                openCounter?.incrementAndGet()
                s.soTimeout = probeTimeoutMs
                val out = s.getOutputStream()
                out.write(cnxnPacket)
                out.flush()

                val header = ByteArray(AdbMessage.HEADER_LENGTH)
                val ins = s.getInputStream()
                var read = 0
                while (read < header.size) {
                    val n = ins.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read < header.size) {
                    return false
                }
                isValidAdbProbeHeader(header, requireTlsOnly)
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "AdbPortScanner"
        const val LOOPBACK_IP = "127.0.0.1"
        const val PORT_MIN = 32768
        const val PORT_MAX = 60999
        private const val MIN_VALID_PORT = 1024
        private const val MAX_VALID_PORT = 65535
        private const val LEARNED_MARGIN = 500
        private const val PROC_PORT_RANGE = "/proc/sys/net/ipv4/ip_local_port_range"

        private const val DEFAULT_THREADS = 64
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 300
        private const val DEFAULT_PROBE_TIMEOUT_MS = 600
        private const val PASS_GAP_MS = 50L

        internal fun buildCnxnPacket(): ByteArray =
            AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "host::").toByteArray()

        internal fun isValidAdbProbeHeader(header: ByteArray, requireTlsOnly: Boolean): Boolean {
            if (header.size < AdbMessage.HEADER_LENGTH) return false
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            buf.int // arg0
            buf.int // arg1
            val dataLength = buf.int
            buf.int // data_crc32
            val magic = buf.int
            if ((command xor -0x1) != magic || dataLength < 0) {
                return false
            }
            return if (requireTlsOnly) {
                command == AdbProtocol.A_STLS
            } else {
                command == AdbProtocol.A_STLS ||
                    command == AdbProtocol.A_AUTH ||
                    command == AdbProtocol.A_CNXN
            }
        }

        /**
         * Parses `ip_local_port_range` content (e.g. `"32768\t60999"`), returning null if invalid.
         */
        internal fun parsePortRange(text: String): IntRange? {
            val numbers = Regex("\\d+").findAll(text.substringAfter('=')).mapNotNull {
                it.value.toIntOrNull()
            }.toList()
            if (numbers.size < 2) return null
            val low = numbers[0]
            val high = numbers[1]
            return if (low in MIN_VALID_PORT..high && high <= MAX_VALID_PORT) low..high else null
        }

        internal fun readProcIpLocalPortRange(): IntRange? = runCatching {
            val file = File(PROC_PORT_RANGE)
            if (file.canRead()) parsePortRange(file.readText()) else null
        }.getOrNull()

        /**
         * Linux `inet_csk_find_open_port` lower-half range for `SO_REUSEADDR` port 0 binds:
         * `high++; half = low + (((high - low) >> 2) << 1); ports low..half-1`.
         */
        internal fun kernelLowerHalf(low: Int, high: Int): IntRange {
            val end = high + 1
            val half = low + (((end - low) shr 2) shl 1)
            return low until half
        }

        /**
         * Builds a prioritized deduplicated scan order across ephemeral loopback ports:
         * 1. Odd ports in the lower half of readable `ip_local_port_range` (if accessible)
         * 2. Odd ports within ±500 of previously discovered ports ([history])
         * 3. Remaining odd ports of readable `ip_local_port_range`
         * 4. Odd ports in the default Linux lower half (32769..46883), then remaining odd (46885..60999)
         * 5. Even ports in 32768..60999 (and readable range if wider)
         */
        fun buildOrder(
            history: List<Int> = emptyList(),
            ipLocalPortRange: IntRange? = readProcIpLocalPortRange(),
        ): IntArray {
            val seen = BooleanArray(MAX_VALID_PORT + 1)
            val out = IntArray(MAX_VALID_PORT + 1)
            var n = 0

            fun add(from: Int, toInclusive: Int, step: Int) {
                var p = from.coerceAtLeast(MIN_VALID_PORT)
                val end = toInclusive.coerceAtMost(MAX_VALID_PORT)
                while (p <= end) {
                    if (!seen[p]) {
                        seen[p] = true
                        out[n++] = p
                    }
                    p += step
                }
            }

            fun addOdd(from: Int, toInclusive: Int) {
                val start = from.coerceAtLeast(MIN_VALID_PORT)
                add(if (start % 2 == 0) start + 1 else start, toInclusive, 2)
            }

            if (ipLocalPortRange != null) {
                val band = kernelLowerHalf(ipLocalPortRange.first, ipLocalPortRange.last)
                if (!band.isEmpty()) {
                    addOdd(band.first, band.last)
                }
            }

            val known = history.filter { it in MIN_VALID_PORT..MAX_VALID_PORT }
            if (known.isNotEmpty()) {
                addOdd(known.min() - LEARNED_MARGIN, known.max() + LEARNED_MARGIN)
            }

            if (ipLocalPortRange != null) {
                addOdd(ipLocalPortRange.first, ipLocalPortRange.last)
            }

            val defaultBand = kernelLowerHalf(PORT_MIN, PORT_MAX)
            addOdd(defaultBand.first, defaultBand.last)
            addOdd(defaultBand.last + 1, PORT_MAX)

            add(PORT_MIN, PORT_MAX, 1)
            if (ipLocalPortRange != null) {
                add(ipLocalPortRange.first, ipLocalPortRange.last, 1)
            }

            return out.copyOf(n)
        }
    }
}

/**
 * Remembers recent wireless-debugging ports in device-encrypted storage so pre-unlock and
 * post-unlock scans prioritize the device's observed ephemeral port band on future boots.
 */
object AdbPortHistory {
    private const val TAG = "AdbPortHistory"
    private const val PREFS = "adb_port_history"
    private const val KEY = "ports"
    private const val MAX_ENTRIES = 10

    private fun prefs(context: Context) =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun load(context: Context): List<Int> = runCatching {
        prefs(context).getString(KEY, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1024..65535 }
    }.getOrDefault(emptyList())

    @JvmStatic
    fun record(context: Context, port: Int) {
        if (port !in 1024..65535) return
        runCatching {
            val updated = (load(context).filter { it != port } + port).takeLast(MAX_ENTRIES)
            prefs(context).edit().putString(KEY, updated.joinToString(",")).commit()
        }.onFailure { t ->
            Log.w(TAG, "Failed to record ADB port history", t)
        }
    }
}
