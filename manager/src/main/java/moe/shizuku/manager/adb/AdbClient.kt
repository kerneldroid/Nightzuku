package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import rikka.core.util.BuildUtils
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val SO_TIMEOUT_MS = 30_000
private const val MAX_FRAME_DATA_BYTES = 4 * 1024 * 1024

class AdbClient @JvmOverloads constructor(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
    private val requireTls: Boolean = false
) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() {
        socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = SO_TIMEOUT_MS
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            if (!BuildUtils.atLeast29) {
                error("Connect to adb with TLS is not supported before Android 9")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.soTimeout = SO_TIMEOUT_MS
            try {
                tlsSocket.startHandshake()
                Log.d(TAG, "Handshake succeeded.")

                tlsInputStream = DataInputStream(tlsSocket.inputStream)
                tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
                useTls = true

                // In TLS 1.3, adbd sends its fatal certificate-rejection alert on the first read
                // after startHandshake() returns.
                message = read()
            } catch (e: SSLProtocolException) {
                throw AdbPairingRequiredException("Wireless ADB TLS client certificate rejected; pairing required", e)
            } catch (e: SSLException) {
                if (e.cause is SSLProtocolException || e.message?.contains(" alert ", ignoreCase = true) == true) {
                    throw AdbPairingRequiredException("Wireless ADB TLS handshake rejected; pairing required", e)
                }
                throw e
            }
        } else if (message.command == A_AUTH) {
            if (requireTls) {
                throw AdbException("Server requested legacy A_AUTH when TLS is required")
            }
            if (message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(A_OPEN, localId, 0, "shell:$command")

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("not A_WRTE or A_CLSE")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("not A_OKAY or A_CLSE")
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        require(dataLength in 0..MAX_FRAME_DATA_BYTES) { "ADB frame payload out of bounds: $dataLength" }
        val data: ByteArray?
        if (dataLength > 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = ByteArray(0)
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        if (useTls) {
            try {
                tlsInputStream.close()
            } catch (_: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (_: Throwable) {
            }
            try {
                tlsSocket.close()
            } catch (_: Throwable) {
            }
        }
        if (::plainInputStream.isInitialized) {
            try {
                plainInputStream.close()
            } catch (_: Throwable) {
            }
        }
        if (::plainOutputStream.isInitialized) {
            try {
                plainOutputStream.close()
            } catch (_: Throwable) {
            }
        }
        if (::socket.isInitialized) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }
}
