package moe.shizuku.starter

import android.system.Os
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Binary length-prefixed codec for passing oversized `app_process` argument vectors out-of-band
 * past Linux `execve()`'s 128 KiB `MAX_ARG_STRLEN` and `ARG_MAX` limits.
 */
object ArgvPayloadCodec {

    private const val MAGIC = 0x4E5A4156 // "NZAV"
    private const val VERSION = 1
    private const val MODE_0600 = 0x180

    const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024 // 4 MiB
    const val MAX_ARG_COUNT = 65_536

    data class Payload(
        val targetClass: String,
        val args: Array<String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Payload) return false
            return targetClass == other.targetClass && args.contentEquals(other.args)
        }

        override fun hashCode(): Int {
            return 31 * targetClass.hashCode() + args.contentHashCode()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun write(file: File, targetClass: String, args: Array<String>) {
        if (args.size > MAX_ARG_COUNT) {
            throw IOException("Argument count ${args.size} exceeds limit $MAX_ARG_COUNT")
        }
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val targetBytes = targetClass.toByteArray(StandardCharsets.UTF_8)
        var totalBytes = 12 + targetBytes.size
        val encodedArgs = Array(args.size) { i ->
            val b = args[i].toByteArray(StandardCharsets.UTF_8)
            totalBytes += 4 + b.size
            if (totalBytes > MAX_PAYLOAD_BYTES) {
                throw IOException("Serialized argv payload exceeds $MAX_PAYLOAD_BYTES bytes")
            }
            b
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(targetBytes.size)
            out.write(targetBytes)
            out.writeInt(encodedArgs.size)
            for (argBytes in encodedArgs) {
                out.writeInt(argBytes.size)
                out.write(argBytes)
            }
            out.flush()
        }

        try {
            Os.chmod(file.absolutePath, MODE_0600)
        } catch (_: Throwable) {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readAndDelete(path: String): Payload {
        val file = File(path)
        try {
            if (!file.exists() || !file.isFile) {
                throw IOException("Argv payload file not found: $path")
            }
            val length = file.length()
            if (length <= 0L || length > MAX_PAYLOAD_BYTES) {
                throw IOException("Invalid argv payload size: $length")
            }
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC) {
                    throw IOException("Invalid argv payload magic: 0x${Integer.toHexString(magic)}")
                }
                val version = input.readInt()
                if (version != VERSION) {
                    throw IOException("Unsupported argv payload version: $version")
                }
                val classLen = input.readInt()
                if (classLen < 0 || classLen > MAX_PAYLOAD_BYTES) {
                    throw IOException("Invalid target class length: $classLen")
                }
                val classBytes = ByteArray(classLen)
                input.readFully(classBytes)
                val targetClass = String(classBytes, StandardCharsets.UTF_8)

                val argCount = input.readInt()
                if (argCount < 0 || argCount > MAX_ARG_COUNT) {
                    throw IOException("Invalid argument count: $argCount")
                }
                var remaining = MAX_PAYLOAD_BYTES - classLen
                val args = Array(argCount) {
                    val argLen = input.readInt()
                    if (argLen < 0 || argLen > remaining) {
                        throw IOException("Invalid argument length: $argLen")
                    }
                    remaining -= argLen
                    val bytes = ByteArray(argLen)
                    input.readFully(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                }
                return Payload(targetClass, args)
            }
        } finally {
            runCatching { file.delete() }
        }
    }
}
