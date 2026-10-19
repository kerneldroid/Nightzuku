package moe.shizuku.manager.module.update

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.catalog.TokenStore
import moe.shizuku.manager.module.discovery.ContentItem
import moe.shizuku.manager.module.discovery.GitHubRepo
import moe.shizuku.manager.module.discovery.RateLimitTracker
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class BatchUpdateSummary(
    val total: Int,
    val updatedModules: List<AdbModule>,
    val failedModules: Map<String, String>
) {
    val successCount: Int get() = updatedModules.size
    val failureCount: Int get() = failedModules.size
}

class ModuleInstaller private constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val rateLimit = RateLimitTracker()
    private val sourceZipBuilder = SourceZipBuilder.getInstance()

    suspend fun updateModule(
        context: Context,
        module: AdbModule,
        updateResult: UpdateResult? = null,
        preferredMode: ModuleSettings.InstallMode? = null
    ): Result<AdbModule> = withContext(Dispatchers.IO) {
        try {
            cleanupOldZips(context)
            val effectiveMode = preferredMode ?: ModuleSettings.getInstallMode()

            if (effectiveMode == ModuleSettings.InstallMode.RELEASE || preferredMode == null) {
                val directUrl = updateResult?.downloadUrl?.takeIf { it.isNotBlank() }
                if (directUrl != null) {
                    val zipUri = downloadDirectZip(context, module.id, directUrl)
                    if (zipUri != null) {
                        val updated = AdbModuleManager.install(context, zipUri)
                        return@withContext Result.success(updated)
                    }
                }
            }

            val resolved = UpdateChecker.getInstance().resolveOwnerAndRepo(module)
                ?: return@withContext Result.failure(
                    IllegalStateException("Не удалось определить репозиторий GitHub для модуля ${module.id}")
                )
            val (owner, repo, subPath) = resolved
            installModule(
                context = context,
                moduleId = module.id,
                owner = owner,
                repo = repo,
                subPath = subPath,
                modeOverride = preferredMode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update failed for ${module.id}", e)
            Result.failure(e)
        }
    }

    suspend fun updateAllModules(
        context: Context,
        targets: List<Pair<AdbModule, UpdateResult>>,
        onProgress: ((current: Int, total: Int, moduleName: String) -> Unit)? = null
    ): BatchUpdateSummary = withContext(Dispatchers.IO) {
        val updated = mutableListOf<AdbModule>()
        val failed = LinkedHashMap<String, String>()
        val total = targets.size

        targets.forEachIndexed { index, (module, updateRes) ->
            onProgress?.invoke(index + 1, total, module.name)
            updateModule(context, module, updateRes)
                .onSuccess { updated += it }
                .onFailure { err ->
                    failed[module.id] = err.message ?: err.javaClass.simpleName
                }
        }

        BatchUpdateSummary(
            total = total,
            updatedModules = updated,
            failedModules = failed
        )
    }

    private fun downloadDirectZip(context: Context, moduleId: String, downloadUrl: String): Uri? {
        val cacheDir = File(context.cacheDir, "module_zips").apply { mkdirs() }
        val zipFile = File(cacheDir, "$moduleId-update.zip")
        return try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/octet-stream")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.byteStream()?.use { input ->
                    zipFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (zipFile.length() > 0) Uri.fromFile(zipFile) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct update ZIP download failed: $downloadUrl", e)
            zipFile.delete()
            null
        }
    }

    suspend fun installModule(
        context: Context,
        moduleId: String,
        owner: String,
        repo: String,
        subPath: String? = null,
        modeOverride: ModuleSettings.InstallMode? = null
    ): Result<AdbModule> = withContext(Dispatchers.IO) {
        try {
            cleanupOldZips(context)

            val installMode = modeOverride ?: ModuleSettings.getInstallMode()
            val githubPat = TokenStore.getToken(context)?.trim()?.takeIf { it.isNotEmpty() }
            Log.d(TAG, "Installing $moduleId mode=$installMode token=${if (githubPat == null) "null" else "set"}")

            val zipUri = when (installMode) {
                ModuleSettings.InstallMode.SOURCES -> {
                    buildFromSources(context, moduleId, owner, repo, subPath, githubPat)
                        ?: downloadRelease(context, moduleId, owner, repo, githubPat)
                }
                ModuleSettings.InstallMode.RELEASE -> {
                    downloadRelease(context, moduleId, owner, repo, githubPat)
                        ?: buildFromSources(context, moduleId, owner, repo, subPath, githubPat)
                }
            }

            if (zipUri == null) {
                return@withContext Result.failure(Exception("Failed to prepare module ZIP from $owner/$repo"))
            }

            val module = AdbModuleManager.install(context, zipUri)
            saveSourceMetadata(module.directory, owner, repo, subPath, installMode)
            Result.success(module)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed for $moduleId", e)
            Result.failure(e)
        }
    }

    private fun saveSourceMetadata(
        moduleDir: File,
        owner: String,
        repo: String,
        subPath: String?,
        installMode: ModuleSettings.InstallMode
    ) {
        runCatching {
            val sourceProp = moduleDir.resolve(".source.prop")
            sourceProp.writeText(
                buildString {
                    appendLine("owner=$owner")
                    appendLine("repo=$repo")
                    if (!subPath.isNullOrBlank()) {
                        appendLine("subPath=${subPath.trim('/')}")
                    }
                    appendLine("installMode=${installMode.value}")
                }
            )
            sourceProp.setReadable(true, false)
        }
    }

    private suspend fun buildFromSources(
        context: Context,
        moduleId: String,
        owner: String,
        repo: String,
        subPath: String?,
        githubPat: String?
    ): Uri? {
        val defaultBranch = fetchDefaultBranch(owner, repo, githubPat)

        // Primary fast & atomic path: download branch archive ZIP in a single HTTP call
        buildFromArchiveZip(context, moduleId, owner, repo, defaultBranch, subPath, githubPat)?.let {
            return it
        }

        // Fallback path: traverse GitHub Contents API with full relative paths
        val cleanSubPath = subPath?.trim('/')?.takeIf { it.isNotEmpty() }
        val rootPathSegment = cleanSubPath?.let { "/$it" } ?: ""
        val url = "https://api.github.com/repos/$owner/$repo/contents$rootPathSegment?ref=$defaultBranch"

        val request = buildApiRequest(url, githubPat)
        val response = client.newCall(request).execute()

        return response.use { resp ->
            rateLimit.update(resp.headers)

            if (!resp.isSuccessful) {
                Log.w(TAG, "Contents API failed: ${resp.code} for $url")
                return@use null
            }

            val body = resp.body?.string() ?: return@use null
            val items = json.decodeFromString<List<ContentItem>>(body)

            val allFiles = mutableListOf<ContentItem>()
            val dirsToFetch = ArrayDeque<String>()

            for (item in items) {
                if (item.type == "file") {
                    allFiles.add(item)
                } else if (item.type == "dir") {
                    val dirPath = item.path.trim('/')
                    val dirName = dirPath.substringAfterLast('/')
                    if (!dirName.startsWith(".git")) {
                        dirsToFetch.add(dirPath)
                    }
                }
            }

            while (dirsToFetch.isNotEmpty()) {
                val dirPath = dirsToFetch.removeFirst()
                val dirUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$defaultBranch"
                val dirRequest = buildApiRequest(dirUrl, githubPat)
                val dirResponse = client.newCall(dirRequest).execute()

                dirResponse.use { dirResp ->
                    rateLimit.update(dirResp.headers)
                    if (!dirResp.isSuccessful) {
                        Log.w(TAG, "Contents API failed for subdir: ${dirResp.code} for $dirUrl")
                        return@use
                    }
                    val dirBody = dirResp.body?.string() ?: return@use
                    val dirItems = json.decodeFromString<List<ContentItem>>(dirBody)
                    for (dirItem in dirItems) {
                        if (dirItem.type == "file") {
                            allFiles.add(dirItem)
                        } else if (dirItem.type == "dir") {
                            val nestedPath = dirItem.path.trim('/')
                            val nestedName = nestedPath.substringAfterLast('/')
                            if (!nestedName.startsWith(".git")) {
                                dirsToFetch.add(nestedPath)
                            }
                        }
                    }
                }
            }

            if (allFiles.isEmpty()) {
                Log.w(TAG, "No files found in $url")
                return@use null
            }

            val zipFile = sourceZipBuilder.buildZip(context, moduleId, allFiles, githubPat, cleanSubPath)
                ?: return@use null

            Uri.fromFile(zipFile)
        }
    }

    private fun fetchDefaultBranch(owner: String, repo: String, githubPat: String?): String {
        val repoUrl = "https://api.github.com/repos/$owner/$repo"
        return try {
            val repoRequest = buildApiRequest(repoUrl, githubPat)
            client.newCall(repoRequest).execute().use { resp ->
                rateLimit.update(resp.headers)
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Repo API failed: ${resp.code} for $repoUrl")
                    "main"
                } else {
                    val body = resp.body?.string() ?: return@use "main"
                    json.decodeFromString<GitHubRepo>(body).defaultBranch.ifBlank { "main" }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default branch lookup failed for $owner/$repo", e)
            "main"
        }
    }

    private fun buildFromArchiveZip(
        context: Context,
        moduleId: String,
        owner: String,
        repo: String,
        defaultBranch: String,
        subPath: String?,
        githubPat: String?
    ): Uri? {
        val cacheDir = File(context.cacheDir, "module_zips").apply { mkdirs() }
        val rawArchiveFile = File(cacheDir, "$moduleId-archive-raw.zip")
        val targetZipFile = File(cacheDir, "$moduleId-source.zip")
        val cleanSubPath = subPath?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotEmpty() }

        val candidateUrls = buildList {
            add("https://codeload.github.com/$owner/$repo/zip/refs/heads/$defaultBranch")
            if (defaultBranch != "main") add("https://codeload.github.com/$owner/$repo/zip/refs/heads/main")
            if (defaultBranch != "master") add("https://codeload.github.com/$owner/$repo/zip/refs/heads/master")
            if (!githubPat.isNullOrBlank()) {
                add("https://api.github.com/repos/$owner/$repo/zipball/$defaultBranch")
            }
        }

        try {
            var downloaded = false
            for (archiveUrl in candidateUrls) {
                try {
                    val req = if (archiveUrl.contains("api.github.com")) {
                        buildApiRequest(archiveUrl, githubPat)
                    } else {
                        Request.Builder().url(archiveUrl).build()
                    }
                    client.newCall(req).execute().use { resp ->
                        rateLimit.update(resp.headers)
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { input ->
                                rawArchiveFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (rawArchiveFile.length() > 0) {
                                downloaded = true
                            }
                        }
                    }
                    if (downloaded) break
                } catch (e: Exception) {
                    Log.w(TAG, "Archive download attempt failed: $archiveUrl", e)
                }
            }

            if (!downloaded) return null

            var hasModuleProp = false
            val addedEntries = HashSet<String>()

            ZipFile(rawArchiveFile).use { archiveZip ->
                ZipOutputStream(targetZipFile.outputStream()).use { outZip ->
                    val entries = archiveZip.entries().asSequence().toList()
                    for (entry in entries) {
                        if (entry.isDirectory) continue
                        val fullEntryPath = entry.name.replace('\\', '/').trim('/')
                        val slashIndex = fullEntryPath.indexOf('/')
                        if (slashIndex <= 0) continue

                        val repoRelativePath = fullEntryPath.substring(slashIndex + 1).trimStart('/')
                        if (repoRelativePath.isBlank()) continue

                        val moduleRelativePath = if (cleanSubPath != null) {
                            val prefix = "$cleanSubPath/"
                            if (!repoRelativePath.startsWith(prefix)) continue
                            repoRelativePath.removePrefix(prefix).trimStart('/')
                        } else {
                            repoRelativePath
                        }

                        if (moduleRelativePath.isBlank()) continue
                        val lower = moduleRelativePath.lowercase()
                        if (lower.startsWith(".git/") || lower.startsWith(".github/")) continue
                        if (!addedEntries.add(moduleRelativePath)) continue

                        if (moduleRelativePath == "module.prop") {
                            hasModuleProp = true
                        }

                        val newEntry = ZipEntry(moduleRelativePath)
                        outZip.putNextEntry(newEntry)
                        archiveZip.getInputStream(entry).use { input ->
                            input.copyTo(outZip)
                        }
                        outZip.closeEntry()
                    }
                }
            }

            if (hasModuleProp && targetZipFile.length() > 0) {
                return Uri.fromFile(targetZipFile)
            }
            targetZipFile.delete()
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Archive ZIP extraction failed for $moduleId", e)
            targetZipFile.delete()
            return null
        } finally {
            rawArchiveFile.delete()
        }
    }

    private suspend fun downloadRelease(
        context: Context,
        moduleId: String,
        owner: String,
        repo: String,
        githubPat: String?
    ): Uri? {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"

        val request = buildApiRequest(url, githubPat)
        val response = client.newCall(request).execute()

        val release = response.use { resp ->
            rateLimit.update(resp.headers)

            if (!resp.isSuccessful) {
                Log.w(TAG, "Releases API failed: ${resp.code}")
                return null
            }

            val body = resp.body?.string() ?: return null
            json.decodeFromString<GitHubRelease>(body)
        }

        val zipAsset = release.assets.firstOrNull { asset ->
            asset.name.endsWith(".zip", ignoreCase = true) &&
                (asset.name.contains(moduleId, ignoreCase = true) ||
                    asset.name.contains(repo, ignoreCase = true))
        } ?: release.assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }

        if (zipAsset == null) {
            Log.w(TAG, "No ZIP asset found in release")
            return null
        }

        val downloadUrl = zipAsset.browserDownloadUrl
        val cacheDir = File(context.cacheDir, "module_zips").apply { mkdirs() }
        val zipFile = File(cacheDir, "$moduleId-release.zip")

        return try {
            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/octet-stream")
                .build()
            val downloadResponse = client.newCall(downloadRequest).execute()

            downloadResponse.use { dlResp ->
                if (!dlResp.isSuccessful) {
                    Log.w(TAG, "ZIP download failed: ${dlResp.code}")
                    return null
                }

                dlResp.body?.byteStream()?.use { input ->
                    zipFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (zipFile.length() > 0) Uri.fromFile(zipFile) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "ZIP download error", e)
            zipFile.delete()
            null
        }
    }

    private fun buildApiRequest(url: String, githubPat: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

        if (!githubPat.isNullOrBlank()) {
            builder.header("Authorization", "Bearer ${githubPat.trim()}")
        }

        return builder.build()
    }

    private fun cleanupOldZips(context: Context) {
        val cacheDir = File(context.cacheDir, "module_zips")
        if (!cacheDir.isDirectory) return
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    fun getRateLimit(): RateLimitTracker = rateLimit

    companion object {
        private const val TAG = "ModuleInstaller"

        @Volatile
        private var instance: ModuleInstaller? = null

        fun getInstance(): ModuleInstaller {
            return instance ?: synchronized(this) {
                instance ?: ModuleInstaller().also {
                    instance = it
                }
            }
        }
    }
}
