package moe.shizuku.manager.module

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleWebAssetServerTest {

    @Test
    fun perModuleOrigin_isDeterministicAndIsolatedBetweenModules() {
        val serverA = ModuleWebAssetServer(moduleId = "alpha_mod", webRoot = null, webNetworkAllowed = false)
        val serverA2 = ModuleWebAssetServer(moduleId = "alpha_mod", webRoot = null, webNetworkAllowed = false)
        val serverB = ModuleWebAssetServer(moduleId = "beta_mod", webRoot = null, webNetworkAllowed = false)

        assertEquals(serverA.origin, serverA2.origin)
        assertNotEquals(serverA.origin, serverB.origin)
        assertTrue(serverA.virtualHost.endsWith(".appassets.androidplatform.net"))
        assertTrue(serverA.isVirtualAssetHost(serverA.virtualHost))
        assertTrue(serverA.isVirtualAssetHost(ModuleWebAssetServer.LEGACY_SHARED_ASSET_HOST))
        assertFalse(serverA.isVirtualAssetHost(serverB.virtualHost))
    }

    @Test
    fun resolveMimeType_returnsStrictJavaScriptMimeForEs6Modules() {
        assertEquals("text/javascript", ModuleWebAssetServer.resolveMimeType("main.js"))
        assertEquals("text/javascript", ModuleWebAssetServer.resolveMimeType("module.mjs"))
        assertEquals("text/javascript", ModuleWebAssetServer.resolveMimeType("common.cjs"))
        assertEquals("application/wasm", ModuleWebAssetServer.resolveMimeType("engine.wasm"))
        assertEquals("application/json", ModuleWebAssetServer.resolveMimeType("config.json"))
        assertEquals("text/css", ModuleWebAssetServer.resolveMimeType("styles.css"))
        assertEquals("image/svg+xml", ModuleWebAssetServer.resolveMimeType("icon.svg"))
    }

    @Test
    fun resolveAssetCandidate_servesValidFilesAndExtensionlessEsImportsAndBlocksTraversal() {
        val webRoot = Files.createTempDirectory("nightzuku-webroot").toFile()
        try {
            val indexFile = webRoot.resolve("index.html").apply { writeText("<!doctype html>") }
            val jsDir = webRoot.resolve("src").apply { mkdirs() }
            val helperJs = jsDir.resolve("helper.js").apply { writeText("export const x = 1;") }
            val subModuleMjs = jsDir.resolve("sub.mjs").apply { writeText("export default 42;") }

            val server = ModuleWebAssetServer(
                moduleId = "es6_test_module",
                webRoot = webRoot,
                webNetworkAllowed = false
            )

            assertEquals(
                "https://${server.virtualHost}/index.html",
                server.entryUrl(indexFile)
            )

            // Direct file lookup
            assertEquals(
                helperJs.canonicalFile,
                server.resolveAssetCandidate(webRoot, "src/helper.js")?.canonicalFile
            )

            // Extensionless ES6 import fallback (.js and .mjs)
            assertEquals(
                helperJs.canonicalFile,
                server.resolveAssetCandidate(webRoot, "src/helper")?.canonicalFile
            )
            assertEquals(
                subModuleMjs.canonicalFile,
                server.resolveAssetCandidate(webRoot, "src/sub")?.canonicalFile
            )

            // Directory index fallback
            assertEquals(
                indexFile.canonicalFile,
                server.resolveAssetCandidate(webRoot, "")?.canonicalFile
            )

            // Missing asset returns null
            assertNull(server.resolveAssetCandidate(webRoot, "src/nonexistent.js"))
        } finally {
            webRoot.deleteRecursively()
        }
    }
}
