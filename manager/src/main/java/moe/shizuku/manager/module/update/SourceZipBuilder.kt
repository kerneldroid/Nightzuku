package moe.shizuku.manager.module.update

import android.content.Context
import android.util.Log
import moe.shizuku.manager.module.discovery.ContentItem
import moe.shizuku.manager.module.discovery.RateLimitTracker
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SourceZipBuilder private constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val rateLimit = RateLimitTracker()

    suspend fun buildZip(
        context: Context,
        moduleId: String,
        files: List<ContentItem>,
        githubPat: String? = null,
        subPath: String? = null
    ): File? {
        val cacheDir = File(context.cacheDir, "module_zips").apply { mkdirs() }
        val zipFile = File(cacheDir, "$moduleId-source.zip")
        val prefix = subPath?.trim('/')?.let { "$it/" } ?: ""

        return try {
            val addedEntries = HashSet<String>()
            ZipOutputStream(zipFile.outputStream()).use { zip ->
                for (file in files) {
                    val fullPath = file.path.replace('\\', '/').trim('/')
                    val relativePath = if (prefix.isNotEmpty()) {
                        if (!fullPath.startsWith(prefix)) continue
                        fullPath.removePrefix(prefix).trimStart('/')
                    } else {
                        fullPath
                    }
                    if (relativePath.isBlank() || !isModuleFile(relativePath)) continue
                    if (!addedEntries.add(relativePath)) continue
                    val downloadUrl = file.downloadUrl ?: continue

                    val content = downloadFile(downloadUrl, githubPat) ?: continue
                    val entry = ZipEntry(relativePath)
                    entry.size = content.size.toLong()
                    zip.putNextEntry(entry)
                    zip.write(content)
                    zip.closeEntry()
                }
            }

            if (addedEntries.isNotEmpty() && zipFile.length() > 0) {
                zipFile
            } else {
                zipFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "ZIP build failed for $moduleId", e)
            zipFile.delete()
            null
        }
    }

    private fun downloadFile(url: String, githubPat: String?): ByteArray? {
        return try {
            val builder = Request.Builder()
                .url(url)

            val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
            if (host == "api.github.com" || host.endsWith(".api.github.com")) {
                builder.header("Accept", "application/vnd.github.v3.raw")
                if (!githubPat.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer ${githubPat.trim()}")
                }
            } else if (!githubPat.isNullOrBlank() && host == "raw.githubusercontent.com") {
                builder.header("Authorization", "Bearer ${githubPat.trim()}")
            }

            val request = builder.build()
            val response = client.newCall(request).execute()

            response.use { resp ->
                rateLimit.update(resp.headers)

                if (!resp.isSuccessful) {
                    Log.w(TAG, "Download failed: ${resp.code} for $url")
                    return null
                }

                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download error for $url", e)
            null
        }
    }

    private fun isModuleFile(relativePath: String): Boolean {
        val cleanName = relativePath.replace('\\', '/').trim('/').lowercase()
        if (cleanName.isBlank()) return false
        if (cleanName.startsWith(".git/") || cleanName.startsWith(".github/")) return false
        val fileName = cleanName.substringAfterLast('/')
        if (fileName in EXCLUDED_ROOT_FILES && !cleanName.contains('/')) return false
        return true
    }

    fun getRateLimit(): RateLimitTracker = rateLimit

    companion object {
        private const val TAG = "SourceZipBuilder"
        private val EXCLUDED_ROOT_FILES = setOf(
            ".gitignore", ".gitattributes", ".editorconfig", "license", "license.md", "license.txt"
        )

        @Volatile
        private var instance: SourceZipBuilder? = null

        fun getInstance(): SourceZipBuilder {
            return instance ?: synchronized(this) {
                instance ?: SourceZipBuilder().also {
                    instance = it
                }
            }
        }
    }
}
