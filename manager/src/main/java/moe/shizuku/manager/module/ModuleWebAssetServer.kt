package moe.shizuku.manager.module

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Serves a module's local `webRoot` directory over an isolated virtual HTTPS origin
 * (`https://mod-<sha256_prefix>.appassets.androidplatform.net/`) via [WebViewClient.shouldInterceptRequest].
 *
 * Compared to loading `file:///.../webroot/index.html` or using a single shared `WebViewAssetLoader` domain:
 * 1. Standard ES6 modules (`<script type="module" src="./main.js">`, static `import`, dynamic `import()`,
 *    and `fetch('./data.json')`) work out-of-the-box for all enabled modules without requiring Full Trust
 *    or enabling unsafe `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs`.
 * 2. Each module gets its own deterministic virtual HTTPS subdomain (`mod-<hash>.appassets.androidplatform.net`),
 *    strictly isolating `localStorage`, `sessionStorage`, `IndexedDB`, `CacheStorage`, and Web Locks
 *    between installed modules while remaining compatible with explicit `appassets.androidplatform.net` URLs.
 * 3. Raw `file://` and `content://` access can remain disabled on the `WebView`, eliminating local file
 *    inclusion (LFI) outside the module's canonical `webRoot`.
 * 4. Built-in virtual ES6 helper module (`/__nightzuku__/bridge.js` or `/nightzuku.mjs`) provides a clean
 *    Promise/async wrapper around the injected `window.Nightzuku` / `window.Shizuku` JavaScript bridge.
 */
class ModuleWebAssetServer(
    private val moduleId: String,
    private val webRoot: File?,
    private val webNetworkAllowed: Boolean
) {

    constructor(
        module: AdbModule,
        webNetworkAllowed: Boolean
    ) : this(
        moduleId = module.id,
        webRoot = module.webRoot,
        webNetworkAllowed = webNetworkAllowed
    )

    val virtualHost: String = computeModuleHost(moduleId)
    val origin: String = "$HTTPS_SCHEME://$virtualHost"

    fun entryUrl(indexFile: File? = null): String {
        val root = webRoot
        val relative = if (root != null && indexFile != null) {
            runCatching {
                ModuleRuntimeUtils.relativePathOrNull(root, indexFile)?.trimStart('/') ?: "index.html"
            }.getOrDefault("index.html")
        } else {
            "index.html"
        }
        val clean = relative.ifBlank { "index.html" }
        return "$origin/$clean"
    }

    fun isVirtualAssetHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase(Locale.US) ?: return false
        return normalized == virtualHost || normalized == LEGACY_SHARED_ASSET_HOST
    }

    fun shouldOverrideNavigation(request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return true
        if (scheme == "about" || scheme == "javascript") {
            return false
        }
        if ((scheme == HTTPS_SCHEME || scheme == HTTP_SCHEME) && isVirtualAssetHost(uri.host)) {
            return false
        }
        if (webNetworkAllowed && (scheme == HTTPS_SCHEME || scheme == HTTP_SCHEME)) {
            return false
        }
        return true
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        return interceptUri(request.url, request.method)
    }

    fun interceptUri(uri: Uri?, method: String? = "GET"): WebResourceResponse? {
        uri ?: return forbiddenResponse("Missing request URI")
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") {
            return null
        }

        val host = uri.host?.lowercase(Locale.US)
        if ((scheme == HTTPS_SCHEME || scheme == HTTP_SCHEME) && isVirtualAssetHost(host)) {
            return serveVirtualAsset(uri.encodedPath ?: uri.path.orEmpty(), method)
        }

        if (scheme == "file") {
            return serveLegacyFileUri(uri, method)
        }

        if (webNetworkAllowed && (scheme == HTTPS_SCHEME || scheme == HTTP_SCHEME)) {
            return null
        }

        return forbiddenResponse("External network access is disabled for this module")
    }

    internal fun serveVirtualAsset(rawPath: String, method: String? = "GET"): WebResourceResponse {
        val normalizedMethod = method?.uppercase(Locale.US) ?: "GET"
        if (normalizedMethod == "OPTIONS") {
            return emptyResponse(204, "No Content")
        }
        if (normalizedMethod != "GET" && normalizedMethod != "HEAD") {
            return errorResponse(405, "Method Not Allowed", "Only GET/HEAD requests are supported for local module assets")
        }

        val decodedPath = Uri.decode(rawPath).trim()
        val trimmedPath = decodedPath.trimStart('/')
        if (trimmedPath == BUILTIN_BRIDGE_PATH || trimmedPath == BUILTIN_BRIDGE_ALIAS_PATH) {
            return createTextResponse(
                mimeType = "text/javascript",
                body = BUILTIN_BRIDGE_ES_MODULE,
                includeHeadBody = normalizedMethod != "HEAD"
            )
        }

        val root = webRoot ?: return errorResponse(404, "Not Found", "Module webRoot is missing")
        val cleanRelative = runCatching {
            ModuleRuntimeUtils.cleanRelativePath(
                if (trimmedPath.isEmpty() || decodedPath.endsWith("/")) {
                    "${trimmedPath.trimEnd('/')}/index.html".trimStart('/')
                } else {
                    trimmedPath
                }
            )
        }.getOrNull() ?: return forbiddenResponse("Invalid or escaped module asset path")

        val candidate = resolveAssetCandidate(root, cleanRelative)
            ?: return errorResponse(404, "Not Found", "Asset not found: $cleanRelative")

        return serveFile(candidate, normalizedMethod != "HEAD")
    }

    private fun serveLegacyFileUri(uri: Uri, method: String?): WebResourceResponse {
        val root = webRoot ?: return forbiddenResponse("Module webRoot is missing")
        val path = uri.path ?: return forbiddenResponse("Invalid file URI")
        val target = File(path)
        if (!ModuleRuntimeUtils.isInside(root, target)) {
            return forbiddenResponse("Access outside module webRoot is forbidden")
        }
        val actualFile = when {
            target.isDirectory -> target.resolve("index.html").takeIf { it.isFile }
                ?: target.resolve("index.htm").takeIf { it.isFile }
            target.isFile -> target
            else -> null
        } ?: return errorResponse(404, "Not Found", "File not found")

        if (!ModuleRuntimeUtils.isInside(root, actualFile)) {
            return forbiddenResponse("Canonical path escaped module webRoot")
        }
        val normalizedMethod = method?.uppercase(Locale.US) ?: "GET"
        return serveFile(actualFile, normalizedMethod != "HEAD")
    }

    internal fun resolveAssetCandidate(root: File, cleanRelative: String): File? {
        val direct = if (cleanRelative.isEmpty()) root else root.resolve(cleanRelative)
        if (!ModuleRuntimeUtils.isInside(root, direct)) {
            return null
        }
        if (direct.isFile) {
            return direct
        }
        if (direct.isDirectory) {
            val indexHtml = direct.resolve("index.html")
            if (indexHtml.isFile && ModuleRuntimeUtils.isInside(root, indexHtml)) {
                return indexHtml
            }
            val indexHtm = direct.resolve("index.htm")
            if (indexHtm.isFile && ModuleRuntimeUtils.isInside(root, indexHtm)) {
                return indexHtm
            }
            return null
        }

        // Support extensionless ES6 module imports (e.g. `import { x } from './utils'`)
        val lastSegment = cleanRelative.substringAfterLast('/')
        if (!lastSegment.contains('.')) {
            for (ext in MODULE_FALLBACK_EXTENSIONS) {
                val withExt = root.resolve("$cleanRelative$ext")
                if (withExt.isFile && ModuleRuntimeUtils.isInside(root, withExt)) {
                    return withExt
                }
            }
            val subDirIndex = root.resolve("$cleanRelative/index.js")
            if (subDirIndex.isFile && ModuleRuntimeUtils.isInside(root, subDirIndex)) {
                return subDirIndex
            }
        }
        return null
    }

    private fun serveFile(file: File, includeBody: Boolean): WebResourceResponse {
        val mimeType = resolveMimeType(file.name)
        val encoding = if (isTextCharsetMime(mimeType)) "UTF-8" else null
        val stream = try {
            if (includeBody) {
                BufferedInputStream(FileInputStream(file))
            } else {
                ByteArrayInputStream(ByteArray(0))
            }
        } catch (_: Throwable) {
            return errorResponse(404, "Not Found", "Unable to open asset")
        }
        return WebResourceResponse(
            mimeType,
            encoding,
            200,
            "OK",
            buildHeaders(mimeType),
            stream
        )
    }

    private fun createTextResponse(
        mimeType: String,
        body: String,
        includeHeadBody: Boolean = true
    ): WebResourceResponse {
        val bytes = if (includeHeadBody) body.toByteArray(StandardCharsets.UTF_8) else ByteArray(0)
        return WebResourceResponse(
            mimeType,
            "UTF-8",
            200,
            "OK",
            buildHeaders(mimeType),
            ByteArrayInputStream(bytes)
        )
    }

    private fun emptyResponse(statusCode: Int, reasonPhrase: String): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reasonPhrase,
            buildHeaders("text/plain"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun forbiddenResponse(message: String): WebResourceResponse {
        return errorResponse(403, "Forbidden", message)
    }

    private fun errorResponse(statusCode: Int, reasonPhrase: String, message: String): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reasonPhrase,
            buildHeaders("text/plain"),
            ByteArrayInputStream(message.toByteArray(StandardCharsets.UTF_8))
        )
    }

    private fun buildHeaders(mimeType: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>(8)
        headers["Access-Control-Allow-Origin"] = origin
        headers["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
        headers["Access-Control-Allow-Headers"] = "Content-Type, Accept, X-Requested-With"
        headers["X-Content-Type-Options"] = "nosniff"
        headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
        headers["Cross-Origin-Resource-Policy"] = "same-origin"
        if (!webNetworkAllowed && mimeType == "text/html") {
            headers["Content-Security-Policy"] =
                "default-src 'self' https://$virtualHost https://$LEGACY_SHARED_ASSET_HOST data: blob:; " +
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://$virtualHost https://$LEGACY_SHARED_ASSET_HOST blob: data:; " +
                    "style-src 'self' 'unsafe-inline' https://$virtualHost https://$LEGACY_SHARED_ASSET_HOST data: blob:; " +
                    "img-src 'self' https://$virtualHost https://$LEGACY_SHARED_ASSET_HOST data: blob:; " +
                    "font-src 'self' https://$virtualHost https://$LEGACY_SHARED_ASSET_HOST data: blob:; " +
                    "connect-src 'self' https://$virtualHost https://$LEGACY_SHARED_ASSET_HOST data: blob:; " +
                    "media-src 'self' https://$virtualHost https://$LEGACY_SHARED_ASSET_HOST data: blob:; " +
                    "worker-src 'self' https://$virtualHost https://$LEGACY_SHARED_ASSET_HOST blob:;"
        }
        return headers
    }

    companion object {
        const val HTTPS_SCHEME = "https"
        const val HTTP_SCHEME = "http"
        const val LEGACY_SHARED_ASSET_HOST = "appassets.androidplatform.net"
        const val BUILTIN_BRIDGE_PATH = "__nightzuku__/bridge.js"
        const val BUILTIN_BRIDGE_ALIAS_PATH = "nightzuku.mjs"
        const val VIRTUAL_BRIDGE_PATH = "/$BUILTIN_BRIDGE_PATH"

        private val MODULE_FALLBACK_EXTENSIONS = listOf(".js", ".mjs", ".cjs", ".json")

        private val EXPLICIT_MIME_TYPES = mapOf(
            // Strict MIME types required by HTML5 / ES6 module specification
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "cjs" to "text/javascript",
            "json" to "application/json",
            "map" to "application/json",
            "webmanifest" to "application/manifest+json",
            "wasm" to "application/wasm",
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "ico" to "image/x-icon",
            "avif" to "image/avif",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "txt" to "text/plain",
            "md" to "text/markdown",
            "xml" to "application/xml",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "webm" to "video/webm"
        )

        fun computeModuleHost(moduleId: String): String {
            val normalized = moduleId.trim().lowercase(Locale.US).ifEmpty { "default" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            val hexPrefix = buildString(16) {
                for (i in 0 until 8) {
                    append(String.format(Locale.US, "%02x", digest[i].toInt() and 0xff))
                }
            }
            return "mod-$hexPrefix.$LEGACY_SHARED_ASSET_HOST"
        }

        fun originForModule(moduleId: String): String =
            "$HTTPS_SCHEME://${computeModuleHost(moduleId)}"

        fun resolveMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext.isEmpty()) {
                return "application/octet-stream"
            }
            EXPLICIT_MIME_TYPES[ext]?.let { return it }
            val systemMime = runCatching {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            }.getOrNull()
            return systemMime ?: "application/octet-stream"
        }

        private fun isTextCharsetMime(mimeType: String): Boolean {
            return mimeType.startsWith("text/") ||
                mimeType == "application/json" ||
                mimeType == "application/manifest+json" ||
                mimeType == "application/xml" ||
                mimeType == "image/svg+xml"
        }

        private val BUILTIN_BRIDGE_ES_MODULE = """
            // Nightzuku built-in ES6 module bridge wrapper
            const rawBridge = () => (typeof window !== 'undefined' && (window.Nightzuku || window.Shizuku)) || null;

            export function isBridgeAvailable() {
                return rawBridge() !== null;
            }

            export function getModuleInfo() {
                const bridge = rawBridge();
                if (!bridge || typeof bridge.getModuleInfo !== 'function') return null;
                try {
                    return JSON.parse(bridge.getModuleInfo());
                } catch (_) {
                    return null;
                }
            }

            export function getRuntimeInfo() {
                const bridge = rawBridge();
                if (!bridge) return null;
                const fn = bridge.getRuntimeInfo || bridge.getModuleInfo;
                if (typeof fn !== 'function') return null;
                try {
                    return JSON.parse(fn.call(bridge));
                } catch (_) {
                    return null;
                }
            }

            export function verifyHost(challenge = '') {
                const bridge = rawBridge();
                if (!bridge || typeof bridge.verifyHost !== 'function') {
                    throw new Error('Nightzuku host attestation bridge is not exposed for this module');
                }
                return JSON.parse(bridge.verifyHost(String(challenge ?? '')));
            }

            export async function exec(command) {
                const bridge = rawBridge();
                if (!bridge || typeof bridge.exec !== 'function') {
                    throw new Error('Nightzuku shell bridge is not exposed for this module');
                }
                const raw = bridge.exec(String(command ?? ''));
                const parsed = JSON.parse(raw);
                return {
                    ...parsed,
                    ok: parsed.exitCode === 0 && !parsed.blocked && !parsed.timedOut && !parsed.rateLimited
                };
            }

            export async function execWithOptions(command, options = {}) {
                const bridge = rawBridge();
                if (!bridge || typeof bridge.execWithOptions !== 'function') {
                    throw new Error('Nightzuku shell bridge is not exposed for this module');
                }
                const optionsJson = typeof options === 'string' ? options : JSON.stringify(options ?? {});
                const raw = bridge.execWithOptions(String(command ?? ''), optionsJson);
                const parsed = JSON.parse(raw);
                return {
                    ...parsed,
                    ok: parsed.exitCode === 0 && !parsed.blocked && !parsed.timedOut && !parsed.rateLimited
                };
            }

            export async function download(url, relativeWebPath) {
                const bridge = rawBridge();
                if (!bridge || typeof bridge.download !== 'function') {
                    throw new Error('Nightzuku download bridge is not exposed for this module');
                }
                const parsed = JSON.parse(bridge.download(String(url ?? ''), String(relativeWebPath ?? '')));
                if (!parsed.ok) {
                    throw new Error(parsed.error || 'Download failed');
                }
                return parsed;
            }

            export async function readText(relativePath) {
                const bridge = rawBridge();
                if (!bridge || typeof bridge.readText !== 'function') {
                    throw new Error('Nightzuku bridge is not exposed for this module');
                }
                const parsed = JSON.parse(bridge.readText(String(relativePath ?? '')));
                if (!parsed.ok) {
                    throw new Error(parsed.error || 'Failed to read file');
                }
                return parsed.content ?? '';
            }

            export async function writeText(relativePath, content) {
                const bridge = rawBridge();
                if (!bridge || typeof bridge.writeText !== 'function') {
                    throw new Error('Nightzuku bridge is not exposed for this module');
                }
                const parsed = JSON.parse(bridge.writeText(String(relativePath ?? ''), String(content ?? '')));
                if (!parsed.ok) {
                    throw new Error(parsed.error || 'Failed to write file');
                }
                return parsed.bytesWritten ?? 0;
            }

            export async function listFiles(relativePath = '') {
                const bridge = rawBridge();
                if (!bridge || typeof bridge.listFiles !== 'function') {
                    throw new Error('Nightzuku bridge is not exposed for this module');
                }
                const parsed = JSON.parse(bridge.listFiles(String(relativePath ?? '')));
                if (!parsed.ok) {
                    throw new Error(parsed.error || 'Failed to list files');
                }
                return parsed.entries ?? [];
            }

            export default {
                isBridgeAvailable,
                getModuleInfo,
                getRuntimeInfo,
                verifyHost,
                exec,
                execWithOptions,
                download,
                readText,
                writeText,
                listFiles
            };
        """.trimIndent()
    }
}
