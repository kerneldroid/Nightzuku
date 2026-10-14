package moe.shizuku.manager.module

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object AdbModuleManager {

    private const val MODULES_DIR = "adb_modules"
    private const val DISABLE_FILE = "disable"
    private const val MAX_ENTRY_COUNT = 2048
    private const val MAX_EXTRACTED_BYTES = 200L * 1024L * 1024L
    private const val MAX_SCRIPT_SECONDS = 120L
    private const val MAX_OUTPUT_CHARS = 64 * 1024
    private const val MAX_SCRIPT_BYTES = 256 * 1024
    @Volatile private var servicesStartedForBinder = false
    private val idRegex = Regex("[A-Za-z][A-Za-z0-9._-]{1,63}")
    private val installMutexes = ConcurrentHashMap<String, Mutex>()

    fun cleanupStagingDirs(context: Context) {
        modulesRoot(context).listFiles { file ->
            file.isDirectory && file.name.startsWith(".") && file.name.endsWith(".installing")
        }?.forEach { it.deleteRecursively() }
    }

    fun modulesRoot(context: Context): File {
        val filesDir = context.filesDir
        filesDir.parentFile?.apply {
            setReadable(true, false)
            setExecutable(true, false)
        }
        filesDir.apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
        return File(filesDir, MODULES_DIR).apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
    }

    suspend fun listModules(context: Context): List<AdbModule> = withContext(Dispatchers.IO) {
        modulesRoot(context)
            .listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.mapNotNull(::readModule)
            ?.sortedWith(compareBy<AdbModule> { !it.enabled }.thenBy { it.name.lowercase(Locale.ROOT) })
            .orEmpty()
    }

    suspend fun install(context: Context, uri: Uri): AdbModule = withContext(Dispatchers.IO) {
        cleanupStagingDirs(context)
        val temp = File.createTempFile("module-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open module ZIP." }
                temp.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(temp).use { zip ->
                val allEntries = zip.entries().asSequence().toList()
                val propEntry = allEntries.firstOrNull {
                    !it.isDirectory && it.name.replace('\\', '/').trim('/') == "module.prop"
                } ?: allEntries
                    .filter { !it.isDirectory && it.name.replace('\\', '/').trim('/').endsWith("/module.prop") }
                    .minByOrNull { it.name.count { ch -> ch == '/' || ch == '\\' } }
                    ?: error("module.prop is missing.")

                val propPath = propEntry.name.replace('\\', '/').trim('/')
                val rootPrefix = if (propPath == "module.prop") {
                    ""
                } else {
                    propPath.removeSuffix("module.prop")
                }

                val props = zip.getInputStream(propEntry).use { parseModuleProp(it.bufferedReader().readText()) }
                val id = props["id"]?.trim().orEmpty()
                require(idRegex.matches(id)) { "Invalid module id: $id" }

                installMutexes.getOrPut(id) { Mutex() }.withLock {
                    val modulesDir = modulesRoot(context)

                    val target = File(modulesDir, id)
                    val staging = File(modulesDir, ".$id.installing")
                    staging.deleteRecursively()
                    staging.mkdirs()

                    var entryCount = 0
                    var extractedBytes = 0L
                    allEntries.forEach { entry ->
                        val normalizedName = entry.name.replace('\\', '/').trim('/')
                        if (normalizedName.isBlank()) return@forEach
                        val relativeName = if (rootPrefix.isNotEmpty()) {
                            if (!normalizedName.startsWith(rootPrefix)) return@forEach
                            normalizedName.removePrefix(rootPrefix).trimStart('/')
                        } else {
                            normalizedName
                        }
                        if (relativeName.isBlank()) return@forEach

                        entryCount++
                        require(entryCount <= MAX_ENTRY_COUNT) { "Module ZIP has too many files." }
                        val cleanName = cleanZipName(relativeName)
                        val outFile = File(staging, cleanName)
                        ensureInside(staging, outFile)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedInputStream(zip.getInputStream(entry)).use { input ->
                                outFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            extractedBytes += outFile.length()
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) { "Module ZIP is too large." }
                        }
                    }

                    // Preserve existing .source.prop across updates if not included in new ZIP
                    val existingSourceProp = target.resolve(".source.prop")
                    val stagingSourceProp = staging.resolve(".source.prop")
                    if (existingSourceProp.isFile && !stagingSourceProp.exists()) {
                        runCatching { existingSourceProp.copyTo(stagingSourceProp, overwrite = false) }
                    }

                    markScriptsExecutable(staging)
                    target.deleteRecursively()
                    check(staging.renameTo(target)) { "Unable to move module into storage." }
                    markScriptsExecutable(target)
                    readModule(target) ?: error("Installed module is unreadable.")
                }
            }
        } finally {
            temp.delete()
        }
    }

    suspend fun setEnabled(module: AdbModule, enabled: Boolean) = withContext(Dispatchers.IO) {
        val marker = module.directory.resolve(DISABLE_FILE)
        if (enabled) {
            marker.delete()
        } else {
            marker.writeText("disabled\n")
        }
    }

    suspend fun delete(module: AdbModule) = withContext(Dispatchers.IO) {
        ModuleSettings.setModuleTrusted(module.id, false)
        module.directory.deleteRecursively()
    }

    suspend fun runAction(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunAction(module)) { "action.sh is blocked by module access policy." }
        val script = module.actionScript?.takeIf { it.isFile } ?: error("This module has no action.sh.")
        runModuleScript(module, script, module.lastActionLog)
    }

    suspend fun runService(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunService(module)) { "service.sh is blocked by module access policy." }
        check(ModuleSettings.canRunBackground(module)) { "Background actions are disabled." }
        val script = module.serviceScript?.takeIf { it.isFile } ?: error("This module has no service.sh.")
        runModuleScript(module, script, module.lastServiceLog)
    }

    suspend fun runEnabledServicesIfAllowed(context: Context): List<Pair<AdbModule, ModuleActionResult>> =
        withContext(Dispatchers.IO) {
            if (servicesStartedForBinder) return@withContext emptyList()
            if (!Shizuku.pingBinder()) return@withContext emptyList()

            servicesStartedForBinder = true
            listModules(context)
                .filter { it.enabled && it.hasService && ModuleSettings.canRunService(it) && ModuleSettings.canRunBackground(it) }
                .mapNotNull { module ->
                    runCatching { module to runService(module) }.getOrNull()
                }
        }

    fun resetServiceRunGuard() {
        servicesStartedForBinder = false
    }

    private fun runModuleScript(module: AdbModule, script: File, logFile: File): ModuleActionResult {
        check(module.enabled) { "Module is disabled." }
        module.logsDir.mkdirs()

        if (script.length() > MAX_SCRIPT_BYTES) {
            return ModuleActionResult(exitCode = -1, stdout = "", stderr = "Script too large: ${script.length()} bytes (max $MAX_SCRIPT_BYTES)")
        }

        val scriptContent = try {
            script.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return ModuleActionResult(exitCode = -1, stdout = "", stderr = "Cannot read script: ${e.message}")
        }

        val binder = Shizuku.getBinder() ?: error("Shizuku service is not running.")
        val service = IShizukuService.Stub.asInterface(binder)
        val env = arrayOf(
            "MODDIR=${module.directory.absolutePath}",
            "ASH_STANDALONE=1",
            "SHIZUKU_MODULE_ID=${module.id}",
            "SHIZUKU_MODULE_MODE=${ModuleSettings.getAccessMode().value}",
            "SHIZUKU_MODULE_TRUSTED=${if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"}",
            "SHIZUKU_MODULE_BACKGROUND=${if (ModuleSettings.canRunBackground(module)) "1" else "0"}"
        )
        val wrappedScript = "cd \"\$MODDIR\" 2>/dev/null || true\n$scriptContent"
        val remote = service.newProcess(
            arrayOf("sh", "-c", wrappedScript),
            env,
            "/data/local/tmp"
        )

        runCatching { ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close() }
        var stdout = ""
        var stderr = ""
        val stdoutThread = Thread {
            try {
                stdout = readStreamTail(remote.getInputStream())
            } catch (_: Exception) {
            }
        }
        val stderrThread = Thread {
            try {
                stderr = readStreamTail(remote.getErrorStream())
            } catch (_: Exception) {
            }
        }
        stdoutThread.start()
        stderrThread.start()
        val finished = remote.waitForTimeout(MAX_SCRIPT_SECONDS, TimeUnit.SECONDS.name)
        val exitCode = if (finished) {
            remote.exitValue()
        } else {
            remote.destroy()
            124
        }
        stdoutThread.join(1000)
        stderrThread.join(1000)
        val result = ModuleActionResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr
        )
        writeLastLog(logFile, module, script, result, finished)
        return result
    }

    private fun readStreamTail(fd: ParcelFileDescriptor): String {
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            java.io.BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val tail = StringBuilder()
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    tail.append(buffer, 0, read)
                    if (tail.length > MAX_OUTPUT_CHARS * 2) {
                        tail.delete(0, tail.length - MAX_OUTPUT_CHARS)
                    }
                }
                if (tail.length > MAX_OUTPUT_CHARS) {
                    tail.substring(tail.length - MAX_OUTPUT_CHARS)
                } else {
                    tail.toString()
                }
            }
        }
    }

    fun readModule(directory: File): AdbModule? {
        val propsFile = directory.resolve("module.prop")
        if (!propsFile.isFile) return null
        val props = parseModuleProp(propsFile.readText())
        val id = props["id"]?.takeIf { idRegex.matches(it) } ?: return null
        val name = props["name"]?.takeIf { it.isNotBlank() } ?: id
        return AdbModule(
            id = id,
            name = name,
            version = props["version"]?.takeIf { it.isNotBlank() },
            versionCode = props["versionCode"]?.toLongOrNull(),
            author = props["author"]?.takeIf { it.isNotBlank() },
            description = props["description"]?.takeIf { it.isNotBlank() },
            directory = directory,
            banner = findFirstExistingFile(
                directory,
                props["banner"],
                "banner.png",
                "banner.jpg",
                "banner.jpeg",
                "banner.webp"
            ),
            webRoot = findFirstExistingDir(
                directory,
                props["webui"],
                "webroot",
                "webui",
                "web"
            ),
            declaresShellBridge = props["usesShellBridge"]?.toBooleanStrictOrNull()
                ?: props["shellBridge"]?.toBooleanStrictOrNull()
                ?: false,
            actionScript = findFirstExistingFile(directory, props["action"], "action.sh", "common/action.sh"),
            serviceScript = findFirstExistingFile(directory, props["service"], "service.sh", "common/service.sh"),
            logsDir = directory.resolve("logs"),
            sizeBytes = directory.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() },
            enabled = !directory.resolve(DISABLE_FILE).exists()
        )
    }

    private fun parseModuleProp(raw: String): Map<String, String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .associate {
                val index = it.indexOf('=')
                it.substring(0, index).trim() to it.substring(index + 1).trim()
            }
    }

    private fun cleanZipName(name: String): String {
        val clean = name.replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "Invalid ZIP entry." }
        val parts = clean.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe ZIP entry: $name"
        }
        return clean
    }

    private fun ensureInside(root: File, child: File) {
        val rootPath = root.canonicalPath
        val childPath = child.canonicalPath
        require(childPath == rootPath || childPath.startsWith("$rootPath/")) {
            "Unsafe module path: ${child.name}"
        }
    }

    private fun markScriptsExecutable(directory: File) {
        directory.walkTopDown().forEach { file ->
            file.setReadable(true, false)
            if (file.isDirectory || file.extension.equals("sh", ignoreCase = true)) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun writeLastLog(
        logFile: File,
        module: AdbModule,
        script: File,
        result: ModuleActionResult,
        finished: Boolean
    ) {
        logFile.parentFile?.mkdirs()
        logFile.writeText(
            buildString {
                appendLine("module=${module.id}")
                appendLine("script=${script.name}")
                appendLine("finished=$finished")
                appendLine("exitCode=${result.exitCode}")
                appendLine("mode=${ModuleSettings.getAccessMode().value}")
                appendLine("trusted=${ModuleSettings.isModuleTrusted(module.id)}")
                appendLine()
                appendLine("[stdout]")
                appendLine(result.stdout.trim())
                appendLine()
                appendLine("[stderr]")
                appendLine(result.stderr.trim())
            }
        )
    }

    private fun findFirstExistingFile(directory: File, vararg paths: String?): File? {
        return paths.asSequence()
            .filterNotNull()
            .map { it.replace('\\', '/').trim().trim('/') }
            .filter { it.isNotBlank() }
            .mapNotNull { rel ->
                val candidate = directory.resolve(rel)
                runCatching {
                    ensureInside(directory, candidate)
                    candidate.takeIf { it.isFile }
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun findFirstExistingDir(directory: File, vararg paths: String?): File? {
        return paths.asSequence()
            .filterNotNull()
            .map { it.replace('\\', '/').trim().trim('/') }
            .filter { it.isNotBlank() }
            .mapNotNull { rel ->
                val candidate = directory.resolve(rel)
                runCatching {
                    ensureInside(directory, candidate)
                    candidate.takeIf { it.isDirectory }
                }.getOrNull()
            }
            .firstOrNull()
    }
}
