package moe.shizuku.manager.module

import android.content.Context
import android.os.ParcelFileDescriptor
import kerneldroid.nightzuku.api.NightProtocol
import kerneldroid.nightzuku.util.PackageSignatureVerifier
import moe.shizuku.manager.BuildConfig
import moe.shizuku.server.IShizukuService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared runtime utilities, shell workspace staging, and cryptographic host attestation
 * for Nightzuku ADB Modules.
 *
 * Centralizes:
 * - `.prop` parsing (`module.prop`, `.source.prop`)
 * - Relative path sanitization and directory containment checks
 * - Bounded stream tail reading (`readStreamTail`)
 * - Deterministic, signer-bound cryptographic anti-fork host attestation (`NIGHTZUKU_HOST_*`,
 *   RFC 2104 HMAC-SHA256 tokens, SHA-256 attestation digests, and `.nightzuku_env` generation)
 * - SELinux-safe `/data/local/tmp/kerneldroid.nightzuku/adb_modules/<id>` staging and two-way sync
 *   for wireless ADB (`u:r:shell:s0`, UID 2000) without loosening private app data directory DAC permissions.
 */
object ModuleRuntimeUtils {

    const val HOST_PACKAGE_ID = "kerneldroid.nightzuku"
    const val HOST_API_VERSION = 2
    const val ATTESTATION_FILE_NAME = ".nightzuku_env"
    const val SHELL_RUNTIME_BASE = "/data/local/tmp/$HOST_PACKAGE_ID"
    const val SHELL_MODULES_ROOT = "$SHELL_RUNTIME_BASE/adb_modules"

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val ATTESTATION_KEY_PREFIX = "nightzuku-host-attestation-v2:"
    private const val MAX_OUTPUT_CHARS_DEFAULT = 64 * 1024
    private const val TAR_BLOCK_SIZE = 512
    private const val MAX_SYNC_FILES = 2048
    private const val MAX_SYNC_BYTES = 100L * 1024L * 1024L
    private const val SYNC_TIMEOUT_SECONDS = 30L

    val MODULE_ID_REGEX = Regex("[A-Za-z][A-Za-z0-9._-]{1,63}")

    private val runtimeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Serializes staging, script execution and write-back for one module. */
    fun runtimeLock(moduleId: String): Any = runtimeLocks.getOrPut(moduleId) { Any() }

    /** Stops scripts and child processes still running from the shell staging directory. */
    fun killShellModuleProcesses(service: IShizukuService?, moduleId: String) {
        if (service == null || !MODULE_ID_REGEX.matches(moduleId)) return
        val path = "$SHELL_MODULES_ROOT/$moduleId"
        runCatching {
            val remote = service.newProcess(
                arrayOf("sh", "-c", "pkill -f \"$path\" 2>/dev/null || true"),
                null,
                "/data/local/tmp"
            )
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close() }
            runCatching { remote.getInputStream()?.close() }
            runCatching { remote.getErrorStream()?.close() }
            remote.waitForTimeout(3L, TimeUnit.SECONDS.name)
        }
    }

    @Volatile
    private var cachedSignerSha256: String? = null

    /**
     * Parses a `.prop` text payload (`key=value` lines, ignoring blank lines and `#` comments).
     */
    fun parseProps(raw: String): Map<String, String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .associate {
                val index = it.indexOf('=')
                it.substring(0, index).trim() to it.substring(index + 1).trim()
            }
    }

    /**
     * Parses a `.prop` file if it exists and is a regular file, or returns an empty map.
     */
    fun readPropsFile(file: File): Map<String, String> {
        if (!file.isFile || !file.canRead()) return emptyMap()
        return runCatching { parseProps(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyMap())
    }

    /**
     * Normalizes a relative path and rejects blank segments, `.`, or `..` traversal attempts.
     */
    fun cleanRelativePath(path: String): String {
        val clean = path.replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "Invalid relative path." }
        val parts = clean.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe relative path: $path"
        }
        return clean
    }

    /**
     * Verifies that [child] resolves canonically inside [root].
     */
    fun ensureInside(root: File, child: File) {
        require(relativePathOrNull(root, child) != null) {
            "Unsafe module path: ${child.name}"
        }
    }

    /**
     * Returns [child]'s path relative to [root] using forward slashes, `""` when both point to
     * the same canonical file, or `null` when [child] is outside [root].
     *
     * Uses canonical [File] paths instead of `java.nio.file.Path` so it keeps working on API 25,
     * where `java.nio.file` is unavailable.
     */
    fun relativePathOrNull(root: File, child: File): String? {
        val rootDir = root.canonicalFile
        val childFile = child.canonicalFile
        val rootPath = rootDir.path
        val childPath = childFile.path
        if (childPath == rootPath) {
            return ""
        }
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        if (!childPath.startsWith(prefix)) {
            return null
        }
        return childPath.substring(prefix.length).replace(File.separatorChar, '/')
    }

    /**
     * Non-throwing variant of [ensureInside] for callers that probe candidate paths.
     */
    fun isInside(root: File, child: File): Boolean =
        runCatching { ensureInside(root, child) }.isSuccess

    /**
     * Reads UTF-8 output from a [ParcelFileDescriptor] while retaining at most the last [maxChars] characters.
     */
    fun readStreamTail(
        fd: ParcelFileDescriptor,
        maxChars: Int = MAX_OUTPUT_CHARS_DEFAULT
    ): String {
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            java.io.BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val tail = StringBuilder()
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    tail.append(buffer, 0, read)
                    if (tail.length > maxChars * 2) {
                        tail.delete(0, tail.length - maxChars)
                    }
                }
                if (tail.length > maxChars) {
                    tail.substring(tail.length - maxChars)
                } else {
                    tail.toString()
                }
            }
        }
    }

    fun normalizeSignerHex(raw: String?): String {
        return raw.orEmpty()
            .trim()
            .lowercase(Locale.US)
            .replace(":", "")
            .replace(" ", "")
    }

    /**
     * Resolves the SHA-256 certificate digest of the running Nightzuku Manager APK.
     */
    fun getManagerSignerSha256(context: Context?): String {
        cachedSignerSha256?.let { return it }
        if (context == null) return ""
        val resolved = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                android.content.pm.PackageManager.GET_SIGNATURES
            }
            val pi = pm.getPackageInfo(context.packageName, flags)
            normalizeSignerHex(PackageSignatureVerifier.extractSignerDigests(pi)?.firstOrNull())
        }.getOrDefault("")
        if (resolved.isNotEmpty()) {
            cachedSignerSha256 = resolved
        }
        return resolved
    }

    internal fun buildAttestationPayload(
        moduleId: String,
        challenge: String,
        signerSha256: String
    ): String {
        val normSigner = normalizeSignerHex(signerSha256)
        return "$HOST_PACKAGE_ID:$HOST_API_VERSION:${NightProtocol.DESCRIPTOR}:$normSigner:$moduleId:$challenge"
    }

    /**
     * Computes a deterministic, signer-bound RFC 2104 HMAC-SHA256 attestation token for [moduleId] and [challenge].
     *
     * Keyed by `"nightzuku-host-attestation-v2:<normalized_signer_sha256>"` so `nightzuku-guard.js` and
     * `nightzuku-guard.sh` can cryptographically verify the HMAC signature against the host's certificate digest
     * and challenge nonce.
     */
    fun computeAttestationToken(
        moduleId: String,
        challenge: String,
        signerSha256: String = cachedSignerSha256.orEmpty()
    ): String {
        return try {
            val normSigner = normalizeSignerHex(signerSha256)
            val keyBytes = "$ATTESTATION_KEY_PREFIX$normSigner".toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(keyBytes, HMAC_ALGORITHM))
            val payload = buildAttestationPayload(moduleId, challenge, normSigner)
            val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Computes a deterministic SHA-256 attestation digest over `"nightzuku-host-attestation-v2:<signer>:<payload>"`
     * so shell guards (`nightzuku-guard.sh`) can verify cryptographic authenticity using standard Android toybox
     * `sha256sum` even on minimal builds without `openssl`.
     */
    fun computeAttestationDigest(
        moduleId: String,
        challenge: String,
        signerSha256: String = cachedSignerSha256.orEmpty()
    ): String {
        return try {
            val normSigner = normalizeSignerHex(signerSha256)
            val payload = buildAttestationPayload(moduleId, challenge, normSigner)
            val input = "$ATTESTATION_KEY_PREFIX$normSigner:$payload".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Returns the SELinux-compatible shell staging directory (`/data/local/tmp/kerneldroid.nightzuku/adb_modules/<id>`)
     * accessible to `u:r:shell:s0` (UID 2000) in wireless ADB mode as well as root (`UID 0`).
     */
    fun shellModuleDirectory(moduleId: String): File {
        require(MODULE_ID_REGEX.matches(moduleId)) { "Invalid module ID: $moduleId" }
        return File(SHELL_MODULES_ROOT, moduleId)
    }

    /**
     * Maps a target working directory inside [module.directory] to its corresponding path inside
     * [shellModuleDirectory].
     */
    fun resolveShellCwd(module: AdbModule, appCwd: File): String {
        val shellRoot = shellModuleDirectory(module.id)
        val rel = relativePathOrNull(module.directory, appCwd) ?: return shellRoot.absolutePath
        return if (rel.isEmpty()) shellRoot.absolutePath else File(shellRoot, rel).absolutePath
    }

    /**
     * Verifies whether a module's `minNightzukuApi` / `minNightzukuVersion` constraints in `module.prop`
     * are satisfied by this genuine Nightzuku host.
     */
    fun usesNightzukuGuard(
        props: Map<String, String>,
        directory: File? = null,
        webRoot: File? = null,
        actionScript: File? = null,
        serviceScript: File? = null
    ): Boolean {
        if (props["requireNightzuku"]?.trim()?.equals("true", ignoreCase = true) == true ||
            props["nightzukuGuard"]?.trim()?.equals("true", ignoreCase = true) == true ||
            props["minNightzukuApi"]?.trim()?.toIntOrNull() != null ||
            props["minNightzukuVersion"]?.trim()?.toLongOrNull() != null
        ) {
            return true
        }
        if (directory != null) {
            if (directory.resolve("nightzuku-guard.sh").isFile ||
                directory.resolve("common/nightzuku-guard.sh").isFile
            ) {
                return true
            }
        }
        if (webRoot != null) {
            if (webRoot.resolve("nightzuku-guard.js").isFile) {
                return true
            }
            val indexFile = webRoot.resolve("index.html").takeIf { it.isFile }
                ?: webRoot.resolve("index.htm").takeIf { it.isFile }
            if (indexFile != null && indexFile.length() <= 64 * 1024) {
                val hasGuardJs = runCatching {
                    val content = indexFile.readText(Charsets.UTF_8)
                    content.contains("nightzuku-guard.js") || content.contains("verifyHostAttestation")
                }.getOrDefault(false)
                if (hasGuardJs) return true
            }
        }
        for (script in listOfNotNull(actionScript, serviceScript)) {
            if (script.isFile && script.length() <= 64 * 1024) {
                val hasGuardMarker = runCatching {
                    val content = script.readText(Charsets.UTF_8)
                    content.contains("nightzuku-guard.sh") ||
                        content.contains("NIGHTZUKU_HOST") ||
                        content.contains("NIGHTZUKU_SESSION_TOKEN") ||
                        content.contains(".nightzuku_env")
                }.getOrDefault(false)
                if (hasGuardMarker) return true
            }
        }
        return false
    }

    fun verifyModuleHostRequirements(props: Map<String, String>): String? {
        val minApi = props["minNightzukuApi"]?.trim()?.toIntOrNull()
        if (minApi != null && HOST_API_VERSION < minApi) {
            return "Module requires Nightzuku Modules API v$minApi (current: v$HOST_API_VERSION)"
        }
        val minVerCode = props["minNightzukuVersion"]?.trim()?.toLongOrNull()
        if (minVerCode != null && BuildConfig.VERSION_CODE.toLong() < minVerCode) {
            return "Module requires Nightzuku versionCode >= $minVerCode (current: ${BuildConfig.VERSION_CODE})"
        }
        return null
    }

    /**
     * Builds the canonical Nightzuku module environment variables and writes `.nightzuku_env`
     * into the module directory so module scripts can verify genuine Nightzuku execution.
     *
     * By default, `MODDIR` and `SHIZUKU_MODULE_CWD` point to the SELinux-accessible shell staging
     * directory (`/data/local/tmp/kerneldroid.nightzuku/adb_modules/<id>`) so scripts running under
     * `u:r:shell:s0` (wireless ADB) have full read/write/execute access without SELinux `app_data_file`
     * denials.
     */
    fun buildModuleEnvironment(
        context: Context?,
        module: AdbModule,
        cwd: File = module.directory,
        extraEnv: Map<String, String> = emptyMap(),
        useShellRuntimeDir: Boolean = true
    ): Array<String> {
        val signer = getManagerSignerSha256(context)
        val token = computeAttestationToken(module.id, "runtime-env", signer)
        val digest = computeAttestationDigest(module.id, "runtime-env", signer)
        val mode = ModuleSettings.getAccessMode().value
        val trusted = if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"
        val background = if (ModuleSettings.canRunBackground(module)) "1" else "0"

        writeModuleAttestationFile(module.directory, module.id, signer, token, digest)

        val runtimeModDir = if (useShellRuntimeDir) {
            shellModuleDirectory(module.id).absolutePath
        } else {
            module.directory.absolutePath
        }
        val runtimeCwd = if (useShellRuntimeDir) {
            resolveShellCwd(module, cwd)
        } else {
            cwd.absolutePath
        }

        val env = linkedMapOf<String, String>()
        extraEnv.forEach { (k, v) -> env[k] = v }

        // Reserved variables are set last so untrusted extraEnv cannot spoof them
        env["MODDIR"] = runtimeModDir
        env["SHIZUKU_MODULE_CWD"] = runtimeCwd
        env["NIGHTZUKU_APP_MODDIR"] = module.directory.absolutePath
        env["ASH_STANDALONE"] = "1"
        env["SHIZUKU_MODULE_ID"] = module.id
        env["SHIZUKU_MODULE_MODE"] = mode
        env["SHIZUKU_MODULE_TRUSTED"] = trusted
        env["SHIZUKU_MODULE_BACKGROUND"] = background

        // Genuine Nightzuku Host Attestation variables
        env["NIGHTZUKU_HOST"] = "1"
        env["NIGHTZUKU_HOST_PKG"] = HOST_PACKAGE_ID
        env["NIGHTZUKU_HOST_VERSION"] = BuildConfig.VERSION_NAME
        env["NIGHTZUKU_HOST_VERSION_CODE"] = BuildConfig.VERSION_CODE.toString()
        env["NIGHTZUKU_MODULES_API"] = HOST_API_VERSION.toString()
        env["NIGHTZUKU_BINDER_DESCRIPTOR"] = NightProtocol.DESCRIPTOR
        env["NIGHTZUKU_PROVIDER_AUTHORITY"] = "$HOST_PACKAGE_ID${NightProtocol.PROVIDER_SUFFIX}"
        env["NIGHTZUKU_SIGNER_SHA256"] = signer
        env["NIGHTZUKU_SESSION_TOKEN"] = token
        env["NIGHTZUKU_ATTESTATION_SHA256"] = digest

        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    internal fun writeModuleAttestationFile(
        moduleDir: File,
        moduleId: String,
        signerSha256: String,
        sessionToken: String,
        attestationSha256: String = computeAttestationDigest(moduleId, "runtime-env", signerSha256)
    ): File? {
        return runCatching {
            val envFile = moduleDir.resolve(ATTESTATION_FILE_NAME)
            envFile.writeText(
                buildString {
                    appendLine("# Auto-generated by Nightzuku ADB Module Runner — DO NOT EDIT")
                    appendLine("NIGHTZUKU_HOST=1")
                    appendLine("NIGHTZUKU_HOST_PKG=$HOST_PACKAGE_ID")
                    appendLine("NIGHTZUKU_HOST_VERSION=${BuildConfig.VERSION_NAME}")
                    appendLine("NIGHTZUKU_HOST_VERSION_CODE=${BuildConfig.VERSION_CODE}")
                    appendLine("NIGHTZUKU_MODULES_API=$HOST_API_VERSION")
                    appendLine("NIGHTZUKU_BINDER_DESCRIPTOR=${NightProtocol.DESCRIPTOR}")
                    appendLine("NIGHTZUKU_PROVIDER_AUTHORITY=$HOST_PACKAGE_ID${NightProtocol.PROVIDER_SUFFIX}")
                    appendLine("NIGHTZUKU_MODULE_ID=$moduleId")
                    appendLine("NIGHTZUKU_SIGNER_SHA256=$signerSha256")
                    appendLine("NIGHTZUKU_SESSION_TOKEN=$sessionToken")
                    appendLine("NIGHTZUKU_ATTESTATION_SHA256=$attestationSha256")
                },
                Charsets.UTF_8
            )
            envFile
        }.getOrNull()
    }

    /**
     * Stages the module directory into `/data/local/tmp/kerneldroid.nightzuku/adb_modules/<moduleId>`
     * over `shizuku_server` stdin using a POSIX ustar tar stream.
     *
     * This avoids SELinux `mlsconstrain` (`app_data_file`) denials when `shizuku_server` runs as
     * `u:r:shell:s0` (UID 2000) on Android 10+ while keeping the Manager's private `filesDir` `0700`.
     */
    fun syncModuleToShellRuntime(service: IShizukuService, module: AdbModule): File {
        val shellDir = shellModuleDirectory(module.id)
        val shellPath = shellDir.absolutePath
        val cmd = buildString {
            append("mkdir -p \"$shellPath\" && ")
            append("chmod 700 \"$SHELL_RUNTIME_BASE\" \"$SHELL_MODULES_ROOT\" \"$shellPath\" 2>/dev/null || true; ")
            append("rm -rf \"$shellPath\"/* \"$shellPath\"/.[!.]* \"$shellPath\"/..?* 2>/dev/null || true; ")
            append("cd \"$shellPath\" && tar -xf - && ")
            append("chmod -R u+rwX \"$shellPath\" 2>/dev/null || true")
        }
        val remote = service.newProcess(arrayOf("sh", "-c", cmd), null, "/data/local/tmp")
        val stdoutPfd = remote.getInputStream()
        val stderrPfd = remote.getErrorStream()
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).use { rawOut ->
                BufferedOutputStream(rawOut).use { out ->
                    writeDirectoryAsTar(module.directory, out)
                    out.flush()
                }
            }
            remote.waitForTimeout(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS.name)
        } finally {
            runCatching { stdoutPfd?.close() }
            runCatching { stderrPfd?.close() }
        }
        return shellDir
    }

    /**
     * Syncs any files created or modified by module scripts inside
     * `/data/local/tmp/kerneldroid.nightzuku/adb_modules/<moduleId>` back into the Manager's
     * private module directory (`module.directory`).
     */
    fun syncBackFromShellRuntime(service: IShizukuService, module: AdbModule) {
        val shellDir = shellModuleDirectory(module.id)
        val shellPath = shellDir.absolutePath
        val cmd = "cd \"$shellPath\" 2>/dev/null && tar -cf - ."
        val remote = runCatching {
            service.newProcess(arrayOf("sh", "-c", cmd), null, "/data/local/tmp")
        }.getOrNull() ?: return

        runCatching { ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close() }
        val stderrPfd = remote.getErrorStream()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream()).use { rawIn ->
                BufferedInputStream(rawIn).use { input ->
                    readTarIntoDirectory(input, module.directory)
                }
            }
            remote.waitForTimeout(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS.name)
        } catch (_: Throwable) {
        } finally {
            runCatching { stderrPfd?.close() }
        }
    }

    /**
     * Removes the staged shell directory `/data/local/tmp/kerneldroid.nightzuku/adb_modules/<moduleId>`
     * when a module is uninstalled.
     */
    fun deleteShellModuleRuntime(service: IShizukuService?, moduleId: String) {
        if (service == null || !MODULE_ID_REGEX.matches(moduleId)) return
        val shellPath = shellModuleDirectory(moduleId).absolutePath
        runCatching {
            val remote = service.newProcess(
                arrayOf("rm", "-rf", shellPath),
                null,
                "/data/local/tmp"
            )
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close() }
            runCatching { remote.getInputStream()?.close() }
            runCatching { remote.getErrorStream()?.close() }
            remote.waitForTimeout(5L, TimeUnit.SECONDS.name)
        }
    }

    private fun writeDirectoryAsTar(rootDir: File, out: OutputStream) {
        var fileCount = 0
        var totalBytes = 0L

        rootDir.walkTopDown().forEach { file ->
            val relPath = relativePathOrNull(rootDir, file) ?: return@forEach
            if (relPath.isEmpty()) return@forEach
            if (relPath == "logs/action-last.log" || relPath == "logs/service-last.log") return@forEach

            fileCount++
            if (fileCount > MAX_SYNC_FILES) return@forEach

            if (file.isDirectory) {
                val dirName = if (relPath.endsWith("/")) relPath else "$relPath/"
                writeTarHeader(out, dirName, 0L, 493, '5', file.lastModified() / 1000L)
            } else if (file.isFile) {
                val size = file.length()
                if (size < 0L || totalBytes + size > MAX_SYNC_BYTES) return@forEach
                totalBytes += size
                val isExec = file.canExecute() ||
                    file.extension.equals("sh", ignoreCase = true) ||
                    relPath.startsWith("bin/")
                val mode = if (isExec) 493 else 420 // 0755 or 0644
                writeTarHeader(out, relPath, size, mode, '0', file.lastModified() / 1000L)
                file.inputStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = size
                    while (remaining > 0L) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = input.read(buf, 0, toRead)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        remaining -= read.toLong()
                    }
                    while (remaining > 0L) {
                        out.write(0)
                        remaining--
                    }
                }
                val pad = ((TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE).toInt()
                if (pad > 0) {
                    out.write(ByteArray(pad))
                }
            }
        }
        // Two 512-byte zero blocks mark end-of-archive
        out.write(ByteArray(TAR_BLOCK_SIZE * 2))
    }

    private fun writeTarHeader(
        out: OutputStream,
        rawName: String,
        size: Long,
        mode: Int,
        typeFlag: Char,
        mtimeSeconds: Long
    ) {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val nameBytes = rawName.toByteArray(StandardCharsets.UTF_8)
        if (nameBytes.size <= 100) {
            System.arraycopy(nameBytes, 0, header, 0, nameBytes.size)
        } else {
            val slashIdx = rawName.lastIndexOf('/')
            val prefix = if (slashIdx > 0) rawName.substring(0, slashIdx) else ""
            val base = if (slashIdx > 0) rawName.substring(slashIdx + 1) else rawName
            val prefixBytes = prefix.toByteArray(StandardCharsets.UTF_8)
            val baseBytes = base.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(baseBytes, 0, header, 0, minOf(baseBytes.size, 100))
            System.arraycopy(prefixBytes, 0, header, 345, minOf(prefixBytes.size, 155))
        }

        writeOctal(header, 100, 8, mode.toLong())
        writeOctal(header, 108, 8, 0L)
        writeOctal(header, 116, 8, 0L)
        writeOctal(header, 124, 12, size)
        writeOctal(header, 136, 12, mtimeSeconds.coerceAtLeast(0L))

        // Fill checksum field with spaces (0x20) before computing sum
        for (i in 148 until 156) {
            header[i] = ' '.code.toByte()
        }
        header[156] = typeFlag.code.toByte()

        val magic = "ustar\u000000".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(magic, 0, header, 257, magic.size)

        var sum = 0L
        for (b in header) {
            sum += (b.toInt() and 0xFF).toLong()
        }
        val chk = String.format(Locale.US, "%06o", sum).toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(chk, 0, header, 148, minOf(chk.size, 6))
        header[154] = 0
        header[155] = ' '.code.toByte()

        out.write(header)
    }

    private fun writeOctal(buf: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = length - 1
        val formatted = String.format(Locale.US, "%0${digits}o", value)
        val bytes = formatted.toByteArray(StandardCharsets.US_ASCII)
        val copyLen = minOf(bytes.size, digits)
        System.arraycopy(bytes, bytes.size - copyLen, buf, offset, copyLen)
        buf[offset + digits] = 0
    }

    private fun readTarIntoDirectory(input: InputStream, targetDir: File) {
        val header = ByteArray(TAR_BLOCK_SIZE)
        var entryCount = 0
        var totalBytes = 0L
        val seenPaths = HashSet<String>()

        while (true) {
            if (!readFully(input, header, TAR_BLOCK_SIZE)) break
            if (header.all { it.toInt() == 0 }) break

            val name = extractTarString(header, 0, 100)
            val prefix = extractTarString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val size = parseTarOctal(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()

            val normalized = fullName.replace('\\', '/').trim().removePrefix("./").trim('/')
            val skipEntry = normalized.isEmpty() ||
                normalized == "." ||
                normalized == ATTESTATION_FILE_NAME ||
                normalized == ".source.prop" ||
                normalized == "disable" ||
                normalized == "module.prop" ||
                normalized.startsWith("logs/")

            entryCount++
            if (entryCount > MAX_SYNC_FILES || totalBytes + size > MAX_SYNC_BYTES) {
                skipTarPayload(input, size)
                continue
            }

            if (typeFlag == '5' || fullName.endsWith("/")) {
                if (!skipEntry) {
                    seenPaths.add(normalized)

                    runCatching {
                        val clean = cleanRelativePath(normalized)
                        val dir = File(targetDir, clean)
                        ensureInside(targetDir, dir)
                        dir.mkdirs()
                    }
                }
                skipTarPayload(input, size)
            } else if (typeFlag == '0' || typeFlag == '\u0000') {
                val outFile = if (!skipEntry) {
                    seenPaths.add(normalized)
                    runCatching {
                        val clean = cleanRelativePath(normalized)
                        val file = File(targetDir, clean)
                        ensureInside(targetDir, file)
                        file
                    }.getOrNull()
                } else {
                    null
                }

                if (outFile == null) {
                    skipTarPayload(input, size)
                } else {
                    outFile.parentFile?.mkdirs()
                    val tmpFile = File.createTempFile(".sync-", ".tmp", outFile.parentFile)
                    var eofReached = false
                    try {
                        tmpFile.outputStream().use { fileOut ->
                            var remaining = size
                            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (remaining > 0L) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val read = input.read(buf, 0, toRead)
                                if (read <= 0) {
                                    eofReached = true
                                    break
                                }
                                fileOut.write(buf, 0, read)
                                remaining -= read.toLong()
                                totalBytes += read.toLong()
                            }
                        }
                        if (!eofReached) {
                            if (outFile.exists()) outFile.delete()
                            tmpFile.renameTo(outFile)
                            if (outFile.extension.equals("sh", ignoreCase = true)) {
                                outFile.setExecutable(true, true)
                            }
                        }
                    } finally {
                        if (tmpFile.exists()) tmpFile.delete()
                    }
                    if (!eofReached) {
                        val pad = ((TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE)
                        if (pad > 0L) {
                            skipExact(input, pad)
                        }
                    }
                }
            } else {
                // Ignore symlinks ('2'), hardlinks ('1'), device nodes, or PAX headers for safety
                skipTarPayload(input, size)
            }
        }


        // Mirror deletions made by module scripts back into the manager sandbox.
        runCatching {
            val protected = setOf("module.prop", ".source.prop", ATTESTATION_FILE_NAME, "disable")
            targetDir.walkTopDown().forEach { file ->
                if (file == targetDir || !file.isFile) return@forEach
                val rel = file.relativeTo(targetDir).invariantSeparatorsPath
                if (rel.startsWith("logs/") || rel in protected || rel in seenPaths) return@forEach
                if (file.parentFile?.name?.startsWith("logs") == true) return@forEach
                file.delete()
            }
        }
    }

    private fun extractTarString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buf[end].toInt() != 0) {
            end++
        }
        return String(buf, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun parseTarOctal(buf: ByteArray, offset: Int, length: Int): Long {
        val raw = extractTarString(buf, offset, length).trim()
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull(8) ?: 0L
    }

    private fun skipTarPayload(input: InputStream, size: Long) {
        if (size <= 0L) return
        val pad = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
        skipExact(input, size + pad)
    }

    private fun skipExact(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(1024)
        while (remaining > 0L) {
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read.toLong()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var offset = 0
        while (offset < len) {
            val read = input.read(buf, offset, len - offset)
            if (read < 0) return offset > 0
            offset += read
        }
        return true
    }
}
