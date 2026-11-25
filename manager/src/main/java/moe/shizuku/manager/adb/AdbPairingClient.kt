package moe.shizuku.manager.adb

import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairClient"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val PAIRING_SO_TIMEOUT_MS = 15_000

private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

private const val kExportedKeyLabel = "adb-label\u0000"
private const val kExportedKeySize = 64

private const val kPairingPacketHeaderSize = 6

private class PeerInfo(
    val type: Byte,
    dataBytes: ByteArray
) {

    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        dataBytes.copyInto(this.data, 0, 0, dataBytes.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),
        ADB_DEVICE_GUID(1.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }

        Log.d(TAG, "write PeerInfo ${toStringShort()}")
    }

    override fun toString(): String {
        return "PeerInfo(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "type=$type, dataSize=${data.size}"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
    val version: Byte,
    val type: Byte,
    val payload: Int
) {

    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte())
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }

        Log.d(TAG, "write PairingPacketHeader ${toStringShort()}")
    }

    override fun toString(): String {
        return "PairingPacketHeader(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=$kCurrentKeyHeaderVersion them=${version})")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacket type=${type}")
                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Log.e(TAG, "header payload not within a safe payload size (size=${payload})")
                return null
            }

            val header = PairingPacketHeader(version, type, payload)
            Log.d(TAG, "read PairingPacketHeader ${header.toStringShort()}")
            return header
        }
    }
}

private class PairingContext private constructor(private var nativePtr: Long) {

    private val nativeLock = Any()
    val msg: ByteArray

    init {
        msg = nativeMsg(nativePtr)
    }

    fun initCipher(theirMsg: ByteArray): Boolean = synchronized(nativeLock) {
        if (nativePtr == 0L) return false
        nativeInitCipher(nativePtr, theirMsg)
    }

    fun encrypt(`in`: ByteArray): ByteArray? = synchronized(nativeLock) {
        if (nativePtr == 0L) return null
        nativeEncrypt(nativePtr, `in`)
    }

    fun decrypt(`in`: ByteArray): ByteArray? = synchronized(nativeLock) {
        if (nativePtr == 0L) return null
        nativeDecrypt(nativePtr, `in`)
    }

    fun destroy() = synchronized(nativeLock) {
        if (nativePtr == 0L) return
        val ptr = nativePtr
        nativePtr = 0L
        nativeDestroy(ptr)
    }

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {

        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        fun drainErrorLog(): String {
            return try {
                nativeGetErrorLog().orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long

        @JvmStatic
        private external fun nativeGetErrorLog(): String?
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val key: AdbKey
) : Closeable {

    private enum class State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    private val ioLock = Any()
    private val cancelled = AtomicBoolean(false)
    private val contextDestroyed = AtomicBoolean(false)

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var sslSocket: SSLSocket? = null
    @Volatile
    private var inputStream: DataInputStream? = null
    @Volatile
    private var outputStream: DataOutputStream? = null

    private val peerInfo: PeerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    @Volatile
    private var pairingContext: PairingContext? = null
    @Volatile
    private var state: State = State.Ready

    fun start(): Boolean {
        if (cancelled.get()) {
            state = State.Stopped
            return false
        }

        setupTlsConnection()

        state = State.ExchangingMsgs

        if (!doExchangeMsgs()) {
            state = State.Stopped
            return false
        }

        state = State.ExchangingPeerInfo

        if (!doExchangePeerInfo()) {
            state = State.Stopped
            return false
        }

        state = State.Stopped
        return true
    }

    /**
     * Thread-safely interrupts any in-flight blocking socket I/O and closes active streams/sockets.
     * Safe to invoke from another thread (for example, inside coroutine cancellation handlers).
     */
    fun cancel() {
        val currentSocket: Socket?
        val currentSslSocket: SSLSocket?
        val currentInput: DataInputStream?
        val currentOutput: DataOutputStream?
        synchronized(ioLock) {
            cancelled.set(true)
            state = State.Stopped
            currentSocket = socket
            currentSslSocket = sslSocket
            currentInput = inputStream
            currentOutput = outputStream
        }

        val closeResources = {
            // Close sockets first so DataOutputStream.close() cannot block flushing to a live socket.
            runCatching { currentSslSocket?.close() }
            runCatching { currentSocket?.close() }
            runCatching { currentInput?.close() }
            runCatching { currentOutput?.close() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread(Runnable { closeResources() }, "AdbPairingCancel").start()
        } else {
            closeResources()
        }
    }

    private fun setupTlsConnection() {
        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        rawSocket.soTimeout = PAIRING_SO_TIMEOUT_MS
        synchronized(ioLock) {
            if (cancelled.get()) {
                runCatching { rawSocket.close() }
                throw IOException("Pairing cancelled")
            }
            socket = rawSocket
        }
        rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

        val sslContext = key.sslContext
        val createdSslSocket = sslContext.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
        createdSslSocket.soTimeout = PAIRING_SO_TIMEOUT_MS
        synchronized(ioLock) {
            if (cancelled.get()) {
                runCatching { createdSslSocket.close() }
                runCatching { rawSocket.close() }
                throw IOException("Pairing cancelled")
            }
            sslSocket = createdSslSocket
        }
        createdSslSocket.startHandshake()
        Log.d(TAG, "Handshake succeeded.")

        val createdInput = DataInputStream(createdSslSocket.inputStream)
        val createdOutput = DataOutputStream(createdSslSocket.outputStream)
        synchronized(ioLock) {
            if (cancelled.get()) {
                runCatching { createdInput.close() }
                runCatching { createdOutput.close() }
                runCatching { createdSslSocket.close() }
                runCatching { rawSocket.close() }
                throw IOException("Pairing cancelled")
            }
            inputStream = createdInput
            outputStream = createdOutput
        }

        val pairCodeBytes = pairCode.toByteArray(Charsets.UTF_8)
        val keyMaterial = Conscrypt.exportKeyingMaterial(createdSslSocket, kExportedKeyLabel, null, kExportedKeySize)
        val passwordBytes = ByteArray(pairCodeBytes.size + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        if (cancelled.get()) {
            throw IOException("Pairing cancelled")
        }
        val createdContext = PairingContext.create(passwordBytes)
        checkNotNull(createdContext) { "Unable to create PairingContext." }
        this.pairingContext = createdContext
    }

    private fun requireInputStream(): DataInputStream =
        inputStream ?: throw IOException("Pairing input stream is not open")

    private fun requireOutputStream(): DataOutputStream =
        outputStream ?: throw IOException("Pairing output stream is not open")

    private fun requirePairingContext(): PairingContext =
        pairingContext ?: throw IllegalStateException("PairingContext is not initialized")

    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        requireInputStream().readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        val out = requireOutputStream()
        out.write(buffer.array())
        out.write(payload)
        out.flush()
        Log.d(TAG, "write payload, size=${payload.size}")
    }

    private fun doExchangeMsgs(): Boolean {
        val ctx = requirePairingContext()
        val msg = ctx.msg
        val size = msg.size

        val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, size)
        writeHeader(ourHeader, msg)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        requireInputStream().readFully(theirMessage)

        if (!ctx.initCipher(theirMessage)) return false
        return true
    }

    private fun doExchangePeerInfo(): Boolean {
        val ctx = requirePairingContext()
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val outbuf = ctx.encrypt(buf.array()) ?: return false

        val ourHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, outbuf.size)
        writeHeader(ourHeader, outbuf)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        requireInputStream().readFully(theirMessage)

        val decrypted = ctx.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != kMaxPeerInfoSize) {
            Log.e(TAG, "Got size=${decrypted.size} PeerInfo.size=$kMaxPeerInfoSize")
            return false
        }
        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        Log.d(TAG, theirPeerInfo.toString())
        return true
    }

    override fun close() {
        cancel()

        val ctx = pairingContext
        if (ctx != null && contextDestroyed.compareAndSet(false, true)) {
            ctx.destroy()
        }
    }

    companion object {

        init {
            System.loadLibrary("adb")
        }

        @JvmStatic
        fun consumeNativeErrorLog(): String {
            return PairingContext.drainErrorLog()
        }
    }
}
