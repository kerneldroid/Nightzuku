package moe.shizuku.manager.module

import android.content.Context
import android.os.ParcelFileDescriptor
import kerneldroid.nightzuku.api.NightProtocol
import kerneldroid.nightzuku.util.PackageSignatureVerifier
import moe.shizuku.manager.BuildConfig
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared runtime utilities and cryptographic host attestation for Nightzuku ADB Modules.
 *
 * Centralizes:
 * - `.prop` parsing (`module.prop`, `.source.prop`)
 * - Relative path sanitization and directory containment checks
 * - Bounded stream tail reading (`readStreamTail`)
 * - Cryptographic anti-fork host attestation (`NIGHTZUKU_HOST_*`, HMAC-SHA256 session tokens,
 *   and `.nightzuku_env` generation) so module developers can reliably reject broken third-party forks.
 */
object ModuleRuntimeUtils {

    const val HOST_PACKAGE_ID = "kerneldroid.nightzuku"
    const val HOST_API_VERSION = 2
    const val ATTESTATION_FILE_NAME = ".nightzuku_env"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val MAX_OUTPUT_CHARS_DEFAULT = 64 * 1024

    val MODULE_ID_REGEX = Regex("[A-Za-z][A-Za-z0-9._-]{1,63}")

    private val sessionSecret: ByteArray by lazy {
        ByteArray(32).also { SecureRandom().nextBytes(it) }
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
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        require(childPath.startsWith(rootPath)) {
            "Unsafe module path: ${child.name}"
        }
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
            PackageSignatureVerifier.extractSignerDigests(pi)?.firstOrNull().orEmpty()
        }.getOrDefault("")
        if (resolved.isNotEmpty()) {
            cachedSignerSha256 = resolved
        }
        return resolved
    }

    /**
     * Computes a deterministic HMAC-SHA256 attestation token for [moduleId] and [challenge].
     */
    fun computeAttestationToken(
        moduleId: String,
        challenge: String,
        signerSha256: String = cachedSignerSha256.orEmpty()
    ): String {
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(sessionSecret, HMAC_ALGORITHM))
            val payload = "$HOST_PACKAGE_ID:$HOST_API_VERSION:${NightProtocol.DESCRIPTOR}:$signerSha256:$moduleId:$challenge"
            val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
        } catch (_: Throwable) {
            ""
        }
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
     */
    fun buildModuleEnvironment(
        context: Context?,
        module: AdbModule,
        cwd: File = module.directory,
        extraEnv: Map<String, String> = emptyMap()
    ): Array<String> {
        val signer = getManagerSignerSha256(context)
        val token = computeAttestationToken(module.id, "runtime-env", signer)
        val mode = ModuleSettings.getAccessMode().value
        val trusted = if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"
        val background = if (ModuleSettings.canRunBackground(module)) "1" else "0"

        writeModuleAttestationFile(module.directory, module.id, signer, token)

        val env = linkedMapOf<String, String>()
        extraEnv.forEach { (k, v) -> env[k] = v }

        // Reserved variables are set last so untrusted extraEnv cannot spoof them
        env["MODDIR"] = module.directory.absolutePath
        env["SHIZUKU_MODULE_CWD"] = cwd.absolutePath
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

        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    internal fun writeModuleAttestationFile(
        moduleDir: File,
        moduleId: String,
        signerSha256: String,
        sessionToken: String
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
                },
                Charsets.UTF_8
            )
            envFile.setReadable(true, false)
            envFile
        }.getOrNull()
    }
}
