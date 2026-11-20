package rikka.shizuku.server

import android.system.Os
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import moe.shizuku.starter.ArgvPayloadCodec
import moe.shizuku.starter.ArgvTrampoline
import rikka.shizuku.server.util.Logger

/**
 * Transparently lifts Linux/Android `execve()` command-line limits (`MAX_ARG_STRLEN` = 128 KiB,
 * conservative `ARG_MAX`) for `app_process` and `sh -c` invocations up to [MAX_EXTENDED_CMD_BYTES].
 *
 * - Normal commands below [SAFE_SINGLE_ARG_BYTES] / [SAFE_TOTAL_ARGV_BYTES] pass through untouched
 *   with zero I/O overhead.
 * - Oversized `app_process` / `app_process32` / `app_process64` invocations are transparently
 *   rewritten to boot [ArgvTrampoline] with a private `0600` binary payload file that is unlinked
 *   immediately upon hydration inside the child JVM.
 * - Oversized `sh -c <script>` invocations are materialized to a private `0600` script file and
 *   cleaned up on completion.
 */
object LargeCommandTransport {

    private val LOGGER = Logger("LargeCmdTransport")

    const val TRANSPORT_ID = "argv-trampoline-v1"

    /** Safely below Linux's 128 KiB (32 * PAGE_SIZE) MAX_ARG_STRLEN per-argument kernel limit. */
    const val SAFE_SINGLE_ARG_BYTES = 96 * 1024

    /** Safely below conservative Android ARG_MAX budgets (128 KiB). */
    const val SAFE_TOTAL_ARGV_BYTES = 112 * 1024

    /** Advertised maximum command-line payload supported by the server's out-of-band transport. */
    const val MAX_EXTENDED_CMD_BYTES = ArgvPayloadCodec.MAX_PAYLOAD_BYTES // 4 MiB

    private const val MODE_0700 = 0x1C0
    private const val MODE_0600 = 0x180
    private const val STALE_FILE_AGE_MS = 10 * 60 * 1000L

    private val APP_PROCESS_NAMES = setOf("app_process", "app_process32", "app_process64")
    private val SHELL_NAMES = setOf("sh", "mksh", "ash", "bash")

    data class PreparedCommand(
        val cmd: Array<String>,
        val env: Array<String>?,
        val cleanupFile: File? = null
    ) {
        fun cleanup() {
            cleanupFile?.let { file ->
                runCatching { if (file.exists()) file.delete() }
            }
        }
    }

    internal data class ParsedAppProcess(
        val binary: String,
        val vmOptions: List<String>,
        val parentDir: String,
        val appProcessOptions: List<String>,
        val mainClass: String,
        val mainArgs: Array<String>
    )

    @JvmStatic
    fun totalArgvBytes(cmd: Array<String>): Int {
        var total = 0
        for (arg in cmd) {
            total += arg.toByteArray(StandardCharsets.UTF_8).size + 1
        }
        return total
    }

    @JvmStatic
    fun needsTransport(cmd: Array<String>): Boolean {
        var total = 0
        for (arg in cmd) {
            val len = arg.toByteArray(StandardCharsets.UTF_8).size
            if (len > SAFE_SINGLE_ARG_BYTES) return true
            total += len + 1
            if (total > SAFE_TOTAL_ARGV_BYTES) return true
        }
        return false
    }

    @JvmStatic
    fun prepare(
        cmd: Array<String>,
        env: Array<String>?,
        managerApkPath: String?,
        spoolDirOverride: File? = null
    ): PreparedCommand {
        if (cmd.isEmpty() || !needsTransport(cmd)) {
            return PreparedCommand(cmd, env, null)
        }

        val totalBytes = totalArgvBytes(cmd)
        if (totalBytes > MAX_EXTENDED_CMD_BYTES) {
            throw IllegalArgumentException(
                "Command payload ($totalBytes bytes) exceeds server limit ($MAX_EXTENDED_CMD_BYTES bytes)"
            )
        }

        val binaryName = File(cmd[0]).name
        if (binaryName in APP_PROCESS_NAMES && !managerApkPath.isNullOrBlank()) {
            val parsed = parseAppProcessCommand(cmd)
            if (parsed != null && parsed.mainClass != ArgvTrampoline::class.java.name) {
                return prepareAppProcessTrampoline(parsed, env, managerApkPath, spoolDirOverride)
            }
        }

        if (binaryName in SHELL_NAMES && cmd.size >= 3 && cmd[1] == "-c") {
            return prepareShellScript(cmd, env, spoolDirOverride)
        }

        return PreparedCommand(cmd, env, null)
    }

    internal fun parseAppProcessCommand(cmd: Array<String>): ParsedAppProcess? {
        if (cmd.size < 3) return null
        val binary = cmd[0]
        val vmOptions = ArrayList<String>()
        var i = 1
        while (i < cmd.size) {
            val arg = cmd[i]
            if (!arg.startsWith("-")) break
            vmOptions.add(arg)
            if ((arg == "-cp" || arg == "-classpath") && i + 1 < cmd.size) {
                i++
                vmOptions.add(cmd[i])
            }
            i++
        }
        if (i >= cmd.size) return null
        val parentDir = cmd[i++]

        val appOptions = ArrayList<String>()
        while (i < cmd.size && cmd[i].startsWith("--")) {
            appOptions.add(cmd[i])
            i++
        }
        if (i >= cmd.size) return null

        val mainClass = cmd[i++]
        val mainArgs = if (i < cmd.size) cmd.copyOfRange(i, cmd.size) else emptyArray()
        return ParsedAppProcess(
            binary = binary,
            vmOptions = vmOptions,
            parentDir = parentDir,
            appProcessOptions = appOptions,
            mainClass = mainClass,
            mainArgs = mainArgs
        )
    }

    private fun prepareAppProcessTrampoline(
        parsed: ParsedAppProcess,
        env: Array<String>?,
        managerApkPath: String,
        spoolDirOverride: File?
    ): PreparedCommand {
        val dir = resolveSpoolDir(spoolDirOverride)
        val payloadFile = File(dir, "argv_${UUID.randomUUID()}.bin")
        ArgvPayloadCodec.write(payloadFile, parsed.mainClass, parsed.mainArgs)

        val updatedVmOptions = ArrayList<String>(parsed.vmOptions.size + 1)
        var patchedClasspathFlag = false
        var idx = 0
        while (idx < parsed.vmOptions.size) {
            val opt = parsed.vmOptions[idx]
            when {
                opt.startsWith("-Djava.class.path=") -> {
                    val existing = opt.substring("-Djava.class.path=".length)
                    updatedVmOptions.add("-Djava.class.path=${appendClasspath(existing, managerApkPath)}")
                    patchedClasspathFlag = true
                }
                (opt == "-cp" || opt == "-classpath") && idx + 1 < parsed.vmOptions.size -> {
                    updatedVmOptions.add(opt)
                    updatedVmOptions.add(appendClasspath(parsed.vmOptions[idx + 1], managerApkPath))
                    patchedClasspathFlag = true
                    idx++
                }
                else -> updatedVmOptions.add(opt)
            }
            idx++
        }

        val updatedEnv = if (patchedClasspathFlag) {
            env
        } else {
            patchClasspathEnv(env, managerApkPath)
        }

        val rewritten = ArrayList<String>(updatedVmOptions.size + parsed.appProcessOptions.size + 4)
        rewritten.add(parsed.binary)
        rewritten.addAll(updatedVmOptions)
        rewritten.add(parsed.parentDir)
        rewritten.addAll(parsed.appProcessOptions)
        rewritten.add(ArgvTrampoline::class.java.name)
        rewritten.add("${ArgvTrampoline.ARG_FILE_PREFIX}${payloadFile.absolutePath}")

        LOGGER.d(
            "Rewrote oversized app_process command (%s, %d args) via %s",
            parsed.mainClass,
            parsed.mainArgs.size,
            payloadFile.name
        )
        return PreparedCommand(rewritten.toTypedArray(), updatedEnv, payloadFile)
    }

    private fun prepareShellScript(
        cmd: Array<String>,
        env: Array<String>?,
        spoolDirOverride: File?
    ): PreparedCommand {
        val dir = resolveSpoolDir(spoolDirOverride)
        val scriptFile = File(dir, "sh_${UUID.randomUUID()}.sh")
        scriptFile.writeText(cmd[2], StandardCharsets.UTF_8)
        try {
            Os.chmod(scriptFile.absolutePath, MODE_0600)
        } catch (_: Throwable) {
            scriptFile.setReadable(false, false)
            scriptFile.setReadable(true, true)
            scriptFile.setWritable(false, false)
            scriptFile.setWritable(true, true)
        }

        val rewritten = ArrayList<String>(cmd.size)
        rewritten.add(cmd[0])
        rewritten.add(scriptFile.absolutePath)
        for (i in 3 until cmd.size) {
            rewritten.add(cmd[i])
        }
        return PreparedCommand(rewritten.toTypedArray(), env, scriptFile)
    }

    private fun appendClasspath(existing: String, managerApkPath: String): String {
        if (existing.isBlank()) return managerApkPath
        val parts = existing.split(':')
        if (parts.contains(managerApkPath)) return existing
        return "$existing:$managerApkPath"
    }

    private fun patchClasspathEnv(env: Array<String>?, managerApkPath: String): Array<String> {
        val base = env?.toMutableList() ?: System.getenv().entries.map { "${it.key}=${it.value}" }.toMutableList()
        val cpIdx = base.indexOfFirst { it.startsWith("CLASSPATH=") }
        if (cpIdx >= 0) {
            val existing = base[cpIdx].substring("CLASSPATH=".length)
            base[cpIdx] = "CLASSPATH=${appendClasspath(existing, managerApkPath)}"
        } else {
            base.add("CLASSPATH=$managerApkPath")
        }
        return base.toTypedArray()
    }

    private fun resolveSpoolDir(override: File?): File {
        val candidates = if (override != null) {
            listOf(override)
        } else {
            listOf(
                File("/data/user_de/0/com.android.shell/.nightzuku_argv"),
                File("/data/local/tmp/.nightzuku_argv"),
                File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "nightzuku_argv")
            )
        }
        for (dir in candidates) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (dir.isDirectory && dir.canWrite()) {
                    runCatching { Os.chmod(dir.absolutePath, MODE_0700) }
                    sweepStaleFiles(dir)
                    return dir
                }
            } catch (_: Throwable) {
            }
        }
        throw IllegalStateException("No writable spool directory available for oversized command transport")
    }

    private fun sweepStaleFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }
}
