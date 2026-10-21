package moe.shizuku.manager.module.update

import android.util.Log
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.discovery.RateLimitTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UpdateChecker private constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val rateLimit = RateLimitTracker()

    suspend fun checkUpdate(module: AdbModule, githubPat: String? = null): UpdateResult =
        withContext(Dispatchers.IO) {
            try {
                val moduleProp = readModuleProp(module)
                val updateJsonUrl = moduleProp["updateJson"]

                if (!updateJsonUrl.isNullOrBlank()) {
                    checkViaUpdateJson(module, updateJsonUrl, githubPat)
                } else {
                    checkViaReleases(module, githubPat)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed for ${module.id}", e)
                UpdateResult(
                    moduleId = module.id,
                    hasUpdate = false,
                    currentVersion = module.version,
                    currentVersionCode = module.versionCode,
                    latestVersion = null,
                    latestVersionCode = null,
                    downloadUrl = null,
                    changelog = null
                )
            }
        }

    private fun readModuleProp(module: AdbModule): Map<String, String> {
        return readPropsFile(module.directory.resolve("module.prop"))
    }

    private fun readSourceProp(module: AdbModule): Map<String, String> {
        return readPropsFile(module.directory.resolve(".source.prop"))
    }

    private fun readPropsFile(propsFile: java.io.File): Map<String, String> {
        if (!propsFile.isFile) return emptyMap()
        return propsFile.readText().lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .associate {
                val index = it.indexOf('=')
                it.substring(0, index).trim() to it.substring(index + 1).trim()
            }
    }

    fun resolveOwnerAndRepo(module: AdbModule): Triple<String, String, String?>? {
        val sourceProps = readSourceProp(module)
        val sourceOwner = sourceProps["owner"]?.takeIf { it.isNotBlank() }
        val sourceRepo = sourceProps["repo"]?.takeIf { it.isNotBlank() }
        val sourceSubPath = sourceProps["subPath"]?.takeIf { it.isNotBlank() }
        if (sourceOwner != null && sourceRepo != null) {
            return Triple(sourceOwner, sourceRepo, sourceSubPath)
        }

        val moduleProps = readModuleProp(module)
        val repoProp = moduleProps["repository"] ?: moduleProps["repo"] ?: moduleProps["github"]
        if (!repoProp.isNullOrBlank()) {
            val cleaned = repoProp.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removeSuffix(".git")
                .trim('/')
            val parts = cleaned.split('/')
            if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return Triple(parts[0], parts[1], sourceSubPath)
            }
        }

        val updateJson = moduleProps["updateJson"]
        if (!updateJson.isNullOrBlank()) {
            val rawPrefix = "https://raw.githubusercontent.com/"
            if (updateJson.startsWith(rawPrefix, ignoreCase = true)) {
                val parts = updateJson.substring(rawPrefix.length).split('/')
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    return Triple(parts[0], parts[1], sourceSubPath)
                }
            }
        }

        val author = module.author?.trim()?.takeIf { it.isNotBlank() && !it.contains(' ') }
        if (author != null) {
            return Triple(author, module.id, sourceSubPath)
        }
        return null
    }

    private fun checkViaUpdateJson(
        module: AdbModule,
        updateJsonUrl: String,
        githubPat: String?
    ): UpdateResult {
        val request = buildRequest(updateJsonUrl, githubPat)
        val response = client.newCall(request).execute()

        val unsuccessful = UpdateResult(
            moduleId = module.id,
            hasUpdate = false,
            currentVersion = module.version,
            currentVersionCode = module.versionCode,
            latestVersion = null,
            latestVersionCode = null,
            downloadUrl = null,
            changelog = null
        )

        response.use { resp ->
            rateLimit.update(resp.headers)

            if (!resp.isSuccessful) return unsuccessful

            val body = resp.body?.string() ?: return unsuccessful

            val updateInfo = json.decodeFromString<UpdateJsonResponse>(body)
            val latestVersionCode = updateInfo.versionCode
            val currentVersionCode = module.versionCode

            val hasUpdate = if (latestVersionCode != null && currentVersionCode != null) {
                latestVersionCode > currentVersionCode
            } else if (updateInfo.version != null && module.version != null) {
                compareSemanticVersions(updateInfo.version, module.version) > 0
            } else {
                false
            }

            return UpdateResult(
                moduleId = module.id,
                hasUpdate = hasUpdate,
                currentVersion = module.version,
                currentVersionCode = module.versionCode,
                latestVersion = updateInfo.version,
                latestVersionCode = updateInfo.versionCode,
                downloadUrl = updateInfo.zipUrl,
                changelog = updateInfo.changelog
            )
        }
    }

    private fun checkViaReleases(
        module: AdbModule,
        githubPat: String?
    ): UpdateResult {
        val unsuccessful = UpdateResult(
            moduleId = module.id,
            hasUpdate = false,
            currentVersion = module.version,
            currentVersionCode = module.versionCode,
            latestVersion = null,
            latestVersionCode = null,
            downloadUrl = null,
            changelog = null
        )

        val (owner, repoName, subPath) = resolveOwnerAndRepo(module) ?: return unsuccessful
        val url = "https://api.github.com/repos/$owner/$repoName/releases/latest"

        val request = buildRequest(url, githubPat)
        val response = client.newCall(request).execute()

        response.use { resp ->
            rateLimit.update(resp.headers)

            if (!resp.isSuccessful) {
                return checkViaRemoteModuleProp(module, owner, repoName, subPath, githubPat) ?: unsuccessful
            }

            val body = resp.body?.string() ?: return unsuccessful

            val release = json.decodeFromString<GitHubRelease>(body)
            val tagVersion = normalizeVersionString(release.tagName)

            val zipAsset = release.assets.firstOrNull { asset ->
                asset.name.endsWith(".zip", ignoreCase = true) &&
                    (asset.name.contains(module.id, ignoreCase = true) ||
                        asset.name.contains(repoName, ignoreCase = true))
            } ?: release.assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }

            val downloadUrl = zipAsset?.browserDownloadUrl

            val currentVersionCode = module.versionCode
            val latestVersionCode = extractVersionCodeFromTag(release.tagName)

            val hasUpdate = when {
                module.version != null && tagVersion.isNotEmpty() && tagVersion.contains('.') -> {
                    compareSemanticVersions(tagVersion, module.version) > 0
                }
                latestVersionCode != null && currentVersionCode != null -> {
                    latestVersionCode > currentVersionCode
                }
                module.version != null && tagVersion.isNotEmpty() -> {
                    compareSemanticVersions(tagVersion, module.version) > 0
                }
                else -> false
            }

            return UpdateResult(
                moduleId = module.id,
                hasUpdate = hasUpdate,
                currentVersion = module.version,
                currentVersionCode = module.versionCode,
                latestVersion = tagVersion,
                latestVersionCode = latestVersionCode,
                downloadUrl = downloadUrl,
                changelog = release.body
            )
        }
    }

    private fun checkViaRemoteModuleProp(
        module: AdbModule,
        owner: String,
        repoName: String,
        subPath: String?,
        githubPat: String?
    ): UpdateResult? {
        val prefix = subPath?.trim('/')?.let { if (it.isNotEmpty()) "$it/" else "" } ?: ""
        val candidates = listOf(
            "https://raw.githubusercontent.com/$owner/$repoName/main/${prefix}module.prop",
            "https://raw.githubusercontent.com/$owner/$repoName/master/${prefix}module.prop"
        )
        for (rawUrl in candidates) {
            try {
                val req = buildRequest(rawUrl, githubPat)
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string() ?: return@use
                    val remoteProps = text.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
                        .associate {
                            val idx = it.indexOf('=')
                            it.substring(0, idx).trim() to it.substring(idx + 1).trim()
                        }
                    val remoteVersion = remoteProps["version"]?.takeIf { it.isNotBlank() }
                    val remoteVersionCode = remoteProps["versionCode"]?.toLongOrNull()
                    val currentVersionCode = module.versionCode
                    val hasUpdate = if (remoteVersionCode != null && currentVersionCode != null) {
                        remoteVersionCode > currentVersionCode
                    } else if (remoteVersion != null && module.version != null) {
                        compareSemanticVersions(remoteVersion, module.version) > 0
                    } else {
                        false
                    }
                    return UpdateResult(
                        moduleId = module.id,
                        hasUpdate = hasUpdate,
                        currentVersion = module.version,
                        currentVersionCode = module.versionCode,
                        latestVersion = remoteVersion,
                        latestVersionCode = remoteVersionCode,
                        downloadUrl = null,
                        changelog = null
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun buildRequest(url: String, githubPat: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

        if (!githubPat.isNullOrBlank() && isGitHubDomain(url)) {
            builder.header("Authorization", "Bearer $githubPat")
        }

        return builder.build()
    }

    private fun isGitHubDomain(url: String): Boolean {
        return try {
            val host = java.net.URL(url).host.lowercase()
            host == "api.github.com" || host.endsWith(".api.github.com")
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeVersionString(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+')
            .trim()
    }

    internal fun compareSemanticVersions(v1: String, v2: String): Int {
        val clean1 = normalizeVersionString(v1)
        val clean2 = normalizeVersionString(v2)
        val parts1 = clean1.split(".", "-")
        val parts2 = clean2.split(".", "-")

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val s1 = parts1.getOrNull(i)?.trim().orEmpty()
            val s2 = parts2.getOrNull(i)?.trim().orEmpty()
            val p1 = s1.toIntOrNull()
            val p2 = s2.toIntOrNull()
            if (p1 != null && p2 != null) {
                if (p1 != p2) return p1.compareTo(p2)
            } else if (s1 != s2) {
                return s1.compareTo(s2)
            }
        }
        return 0
    }

    /**
     * Extracts an integer `versionCode` only when [tag] is an integer release tag (e.g., `v12`, `r12`, `12`),
     * avoiding concatenating dotted semantic versions like `v1.2.0` into `120L`.
     */
    internal fun extractVersionCodeFromTag(tag: String): Long? {
        val trimmed = tag.trim()
        if (trimmed.contains('.')) {
            return null
        }
        val normalized = trimmed
            .removePrefix("v")
            .removePrefix("V")
            .removePrefix("r")
            .removePrefix("R")
            .removePrefix("build-")
            .trim()
        return normalized.toLongOrNull()
    }

    fun getRateLimit(): RateLimitTracker = rateLimit

    @Serializable
    private data class UpdateJsonResponse(
        val version: String? = null,
        val versionCode: Long? = null,
        val zipUrl: String? = null,
        val changelog: String? = null
    )

    companion object {
        private const val TAG = "UpdateChecker"

        @Volatile
        private var instance: UpdateChecker? = null

        fun getInstance(): UpdateChecker {
            return instance ?: synchronized(this) {
                instance ?: UpdateChecker().also {
                    instance = it
                }
            }
        }
    }
}
