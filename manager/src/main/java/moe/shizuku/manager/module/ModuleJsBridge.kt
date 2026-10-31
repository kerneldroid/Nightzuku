package moe.shizuku.manager.module

import android.os.ParcelFileDescriptor
import android.webkit.JavascriptInterface
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.application
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class ModuleJsBridge(
    private val module: AdbModule,
    private val commandReviewer: ModuleCommandReviewer? = null
) {

    @JavascriptInterface
    fun getModuleInfo(): String {
        val ctx = runCatching { application.applicationContext }.getOrNull()
        val signer = ModuleRuntimeUtils.getManagerSignerSha256(ctx)
        return JSONObject().apply {
            put("ok", true)
            put("id", module.id)
            put("name", module.name)
            put("version", module.version ?: "")
            put("versionCode", module.versionCode ?: 0)
            put("author", module.author ?: "")
            put("enabled", module.enabled)
            put("webRoot", module.webRoot?.absolutePath ?: "")
            put("webOrigin", ModuleWebAssetServer.originForModule(module.id))
            put("virtualBridgeUrl", "${ModuleWebAssetServer.originForModule(module.id)}${ModuleWebAssetServer.VIRTUAL_BRIDGE_PATH}")
            put("moduleDir", module.directory.absolutePath)
            put("accessMode", ModuleSettings.getAccessMode().value)
            put("trusted", ModuleSettings.isModuleTrusted(module.id))
            put("background", ModuleSettings.canRunBackground(module))
            put("host", JSONObject().apply {
                put("genuine", true)
                put("packageName", ModuleRuntimeUtils.HOST_PACKAGE_ID)
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("modulesApiVersion", ModuleRuntimeUtils.HOST_API_VERSION)
                put("binderDescriptor", NightProtocol.DESCRIPTOR)
                put("providerAuthority", "${ModuleRuntimeUtils.HOST_PACKAGE_ID}${NightProtocol.PROVIDER_SUFFIX}")
                put("signerSha256", signer)
            })
            put("permissions", JSONObject().apply {
                put("action", ModuleSettings.canRunAction(module))
                put("service", ModuleSettings.canRunService(module))
                put(
                    "webBridge",
                    ModuleSettings.canExposeWebBridge(module) &&
                        (module.declaresShellBridge || ModuleSettings.isModuleTrusted(module.id))
                )
                put("webNetwork", ModuleSettings.canUseWebNetwork(module))
                put("webDownload", ModuleSettings.canDownloadWebFiles(module))
                put("declaresShellBridge", module.declaresShellBridge)
            })
        }.toString()
    }

    /**
     * Cryptographic host attestation for WebUI modules to verify they are running inside
     * genuine Nightzuku (`kerneldroid.nightzuku`) rather than an incompatible fork.
     */
    @JavascriptInterface
    fun verifyHost(challenge: String?): String {
        val cleanChallenge = challenge?.take(256).orEmpty()
        val ctx = runCatching { application.applicationContext }.getOrNull()
        val signer = ModuleRuntimeUtils.getManagerSignerSha256(ctx)
        val token = ModuleRuntimeUtils.computeAttestationToken(module.id, cleanChallenge, signer)
        return JSONObject().apply {
            put("ok", true)
            put("genuineNightzuku", true)
            put("hostPackage", ModuleRuntimeUtils.HOST_PACKAGE_ID)
            put("hostVersion", BuildConfig.VERSION_NAME)
            put("hostVersionCode", BuildConfig.VERSION_CODE)
            put("modulesApiVersion", ModuleRuntimeUtils.HOST_API_VERSION)
            put("binderDescriptor", NightProtocol.DESCRIPTOR)
            put("providerAuthority", "${ModuleRuntimeUtils.HOST_PACKAGE_ID}${NightProtocol.PROVIDER_SUFFIX}")
            put("moduleId", module.id)
            put("challenge", cleanChallenge)
            put("signerSha256", signer)
            put("attestationToken", token)
        }.toString()
    }

    @JavascriptInterface
    fun exec(command: String): String {
        return execInternal(command, ExecOptions())
    }

    @JavascriptInterface
    fun execWithOptions(command: String, optionsJson: String): String {
        return try {
            execInternal(command, parseExecOptions(optionsJson))
        } catch (e: Exception) {
            shellError(e.message ?: "Invalid exec options.")
        }
    }

    @JavascriptInterface
    fun download(url: String, relativeWebPath: String): String {
        val result = JSONObject()
        return try {
            ensureModuleUsableForWebFiles()
            val outFile = resolveWebFile(relativeWebPath)
            val bytes = downloadHttpsToFile(url, outFile)
            result.apply {
                put("ok", true)
                put("url", url)
                put("path", relativeWebPath)
                put("bytes", bytes)
            }.toString()
        } catch (e: Exception) {
            result.apply {
                put("ok", false)
                put("url", url)
                put("path", relativeWebPath)
                put("error", e.message ?: "Download failed.")
            }.toString()
        }
    }

    private fun execInternal(command: String, options: ExecOptions): String {
        if (command.isBlank()) {
            return shellError("Command is blank.")
        }

        if (!module.enabled) {
            return shellError("Module is disabled.")
        }

        val trusted = ModuleSettings.isModuleTrusted(module.id)
        if (!trusted && !module.declaresShellBridge) {
            return shellError("Permission denied: module.prop must declare usesShellBridge=true.")
        }
        if (!ModuleSettings.canExposeWebBridge(module)) {
            return shellError("Permission denied: WebUI shell bridge is blocked by module access policy.")
        }
        if (!trusted && ModuleSettings.recommandForWebUi()) {
            val approved = commandReviewer?.confirmCommand(
                ModuleCommandRequest(
                    module = module,
                    source = ModuleCommandSource.WEB_UI,
                    command = command
                )
            ) ?: false
            if (!approved) {
                return shellError("Command rejected by ReCommand.")
            }
        }

        val binder = Shizuku.getBinder() ?: return shellError("Shizuku service is not running.")

        val result = JSONObject()
        return try {
            val targetDir = resolveModuleDirectory(options.cwd)
            val service = IShizukuService.Stub.asInterface(binder)
            val ctx = runCatching { application.applicationContext }.getOrNull()
            val env = ModuleRuntimeUtils.buildModuleEnvironment(ctx, module, targetDir, options.extraEnv)
            // Pass the target working directory via environment variable ($SHIZUKU_MODULE_CWD)
            // rather than interpolating it into double-quoted shell syntax.
            val wrappedCommand = "cd \"\$SHIZUKU_MODULE_CWD\" 2>/dev/null || true\n$command"
            val remote = service.newProcess(
                arrayOf("sh", "-c", wrappedCommand),
                env,
                "/data/local/tmp"
            )

            val stdoutPfd = remote.getInputStream()
            val stderrPfd = remote.getErrorStream()
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread {
                try {
                    stdout = ModuleRuntimeUtils.readStreamTail(stdoutPfd, MAX_OUTPUT_CHARS)
                } catch (_: Exception) {
                }
            }
            val stderrThread = Thread {
                try {
                    stderr = ModuleRuntimeUtils.readStreamTail(stderrPfd, MAX_OUTPUT_CHARS)
                } catch (_: Exception) {
                }
            }
            stdoutThread.start()
            stderrThread.start()

            ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).use { output ->
                if (options.stdin.isNotEmpty()) {
                    output.write(options.stdin.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }

            val finished = remote.waitForTimeout(options.timeoutSeconds, TimeUnit.SECONDS.name)
            val exitCode = if (finished) {
                remote.exitValue()
            } else {
                remote.destroy()
                runCatching { stdoutPfd.close() }
                runCatching { stderrPfd.close() }
                EXIT_TIMEOUT
            }
            stdoutThread.join(500)
            if (stdoutThread.isAlive) {
                runCatching { stdoutPfd.close() }
                stdoutThread.join(250)
            }
            stderrThread.join(500)
            if (stderrThread.isAlive) {
                runCatching { stderrPfd.close() }
                stderrThread.join(250)
            }

            result.apply {
                put("ok", finished && exitCode == 0)
                put("exitCode", exitCode)
                put("stdout", stdout)
                put("stderr", stderr)
                put("timedOut", !finished)
            }.toString()
        } catch (e: Exception) {
            shellError(e.message ?: "Unknown error")
        }
    }

    private fun parseExecOptions(raw: String): ExecOptions {
        if (raw.isBlank()) return ExecOptions()

        val json = JSONObject(raw)
        val timeout = json.optLong("timeoutSeconds", DEFAULT_SCRIPT_SECONDS)
            .coerceIn(MIN_SCRIPT_SECONDS, MAX_SCRIPT_SECONDS)
        val stdin = json.optString("stdin", "")
        require(stdin.length <= MAX_STDIN_CHARS) { "stdin is too large." }

        val cwd = if (json.has("cwd")) json.optString("cwd", "") else ""
        val envJson = json.optJSONObject("env")
        val extraEnv = linkedMapOf<String, String>()
        if (envJson != null) {
            val keys = envJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(ENV_KEY_REGEX.matches(key)) { "Invalid env key: $key" }
                require(extraEnv.size < MAX_EXTRA_ENV_COUNT) { "Too many env variables." }
                val value = envJson.optString(key, "")
                require(value.length <= MAX_ENV_VALUE_CHARS) { "Env value is too large: $key" }
                extraEnv[key] = value
            }
        }

        return ExecOptions(
            timeoutSeconds = timeout,
            stdin = stdin,
            cwd = cwd,
            extraEnv = extraEnv
        )
    }

    private fun resolveModuleDirectory(relativePath: String): File {
        if (relativePath.isBlank() || relativePath == ".") return module.directory
        val clean = ModuleRuntimeUtils.cleanRelativePath(relativePath)
        val directory = File(module.directory, clean)
        ModuleRuntimeUtils.ensureInside(module.directory, directory)
        require(directory.isDirectory) { "cwd is not a directory: $relativePath" }
        return directory
    }

    private fun ensureModuleUsableForWebFiles() {
        require(module.enabled) { "Module is disabled." }
        require(module.webRoot?.isDirectory == true) { "Module has no WebUI root." }
        require(module.declaresShellBridge || ModuleSettings.isModuleTrusted(module.id)) {
            "module.prop must declare usesShellBridge=true."
        }
        require(ModuleSettings.canDownloadWebFiles(module)) { "WebUI download is blocked by module access policy." }
    }

    private fun resolveWebFile(relativeWebPath: String): File {
        val root = module.webRoot ?: error("Module has no WebUI root.")
        val clean = ModuleRuntimeUtils.cleanRelativePath(relativeWebPath)
        if (!ModuleSettings.isModuleTrusted(module.id)) {
            require(!clean.equals("index.html", ignoreCase = true) && !clean.endsWith("/index.html", ignoreCase = true)) {
                "download() cannot overwrite WebUI entry files."
            }
        }
        val file = File(root, clean)
        ModuleRuntimeUtils.ensureInside(root, file)
        require(!file.isDirectory) { "Destination is a directory." }
        return file
    }

    private fun downloadHttpsToFile(rawUrl: String, outFile: File): Long {
        var current = parseHttpsUrl(rawUrl)
        var redirects = 0

        while (true) {
            val connection = (current.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Nightzuku-Module-WebUI/2.0")
            }

            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    redirects++
                    require(redirects <= MAX_REDIRECTS) { "Too many redirects." }
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location header.")
                    current = parseHttpsUrl(URL(current, location).toString())
                    continue
                }

                require(code in 200..299) { "HTTP $code while downloading $current" }
                val parent = outFile.parentFile ?: error("Destination has no parent directory.")
                parent.mkdirs()
                val tmp = File.createTempFile("download-", ".tmp", parent)
                var total = 0L
                try {
                    BufferedInputStream(connection.inputStream).use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read.toLong()
                                require(total <= MAX_DOWNLOAD_BYTES) { "Downloaded file is too large." }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (outFile.exists()) outFile.delete()
                    check(tmp.renameTo(outFile)) { "Unable to save downloaded file." }
                    return total
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseHttpsUrl(raw: String): URL {
        val url = URL(raw.trim())
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS URLs are allowed." }
        require(!url.host.isNullOrBlank()) { "URL host is blank." }
        return url
    }

    private fun shellError(message: String): String {
        return JSONObject().apply {
            put("ok", false)
            put("exitCode", EXIT_ERROR)
            put("stdout", "")
            put("stderr", message)
            put("timedOut", false)
        }.toString()
    }

    private data class ExecOptions(
        val timeoutSeconds: Long = DEFAULT_SCRIPT_SECONDS,
        val stdin: String = "",
        val cwd: String = "",
        val extraEnv: Map<String, String> = emptyMap()
    )

    companion object {
        private const val EXIT_ERROR = -1
        private const val EXIT_TIMEOUT = 124
        private const val DEFAULT_SCRIPT_SECONDS = 120L
        private const val MIN_SCRIPT_SECONDS = 1L
        private const val MAX_SCRIPT_SECONDS = 600L
        private const val MAX_OUTPUT_CHARS = 64 * 1024
        private const val MAX_STDIN_CHARS = 64 * 1024
        private const val MAX_EXTRA_ENV_COUNT = 32
        private const val MAX_ENV_VALUE_CHARS = 4096
        private const val MAX_DOWNLOAD_BYTES = 20L * 1024L * 1024L
        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308
        )
    }
}
