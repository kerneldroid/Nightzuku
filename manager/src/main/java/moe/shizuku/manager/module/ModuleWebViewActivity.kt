package moe.shizuku.manager.module

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuScaffold

class ModuleWebViewActivity : AppActivity() {

    private var activeWebView: WebView? = null

    override fun onDestroy() {
        activeWebView?.let { webView ->
            try {
                webView.stopLoading()
                webView.removeJavascriptInterface("Shizuku")
                webView.removeJavascriptInterface("Nightzuku")
                webView.destroy()
            } catch (_: Throwable) {
            }
        }
        activeWebView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        val module = AdbModuleManager.readModule(AdbModuleManager.modulesRoot(this).resolve(moduleId))
        val index = module?.webRoot?.let { root ->
            root.resolve("index.html").takeIf { it.isFile }
                ?: root.resolve("index.htm").takeIf { it.isFile }
        }
        if (module == null || index == null) {
            finish()
            return
        }

        setContent {
            BackHandler {
                val wv = activeWebView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
            var pendingCommand by remember { mutableStateOf<ModuleCommandRequest?>(null) }
            var pendingDecision by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
            val trusted = ModuleSettings.isModuleTrusted(module.id)
            val webNetworkAllowed = ModuleSettings.canUseWebNetwork(module)
            val exposeBridge = module.enabled &&
                ModuleSettings.canExposeWebBridge(module) &&
                (module.declaresShellBridge || trusted) &&
                (!webNetworkAllowed || trusted)

            DisposableEffect(Unit) {
                onDispose {
                    pendingDecision?.invoke(false)
                    pendingDecision = null
                }
            }

                ShizukuExpressiveTheme {
                    ShizukuScaffold(
                        title = module.name,
                        onNavigateUp = { finish() }
                    ) { padding ->
                        AndroidView(
                            factory = { context ->
                                WebView(context).also { webView ->
                                    activeWebView = webView
                                    configureModuleWebView(
                                        webView = webView,
                                        module = module,
                                        index = index,
                                        trusted = trusted,
                                        webNetworkAllowed = webNetworkAllowed,
                                        exposeBridge = exposeBridge,
                                        onPendingCommand = { req, cb ->
                                            pendingCommand = req
                                            pendingDecision = cb
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.padding(padding)
                        )
                    }

                    pendingCommand?.let { request ->
                        ReCommandDialog(
                            request = request,
                            onDismiss = {
                                pendingDecision?.invoke(false)
                                pendingCommand = null
                                pendingDecision = null
                            },
                            onReject = {
                                pendingDecision?.invoke(false)
                                pendingCommand = null
                                pendingDecision = null
                            },
                            onApprove = {
                                pendingDecision?.invoke(true)
                                pendingCommand = null
                                pendingDecision = null
                            }
                        )
                    }
                }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureModuleWebView(
        webView: WebView,
        module: AdbModule,
        index: File,
        @Suppress("UNUSED_PARAMETER") trusted: Boolean,
        webNetworkAllowed: Boolean,
        exposeBridge: Boolean,
        onPendingCommand: (ModuleCommandRequest, (Boolean) -> Unit) -> Unit
    ) {
        val assetServer = ModuleWebAssetServer(
            module = module,
            webNetworkAllowed = webNetworkAllowed
        )

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // All local module assets (including ES6 modules, JSON, WASM, CSS, and images) are served
            // over the per-module isolated virtual HTTPS origin via ModuleWebAssetServer. Raw file://
            // and content:// access are disabled for all modules to prevent local file inclusion outside webRoot.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : LocalModuleWebViewClient(assetServer) {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.loadUrl(
                        "javascript:(function() { " +
                            "if (!document.querySelector('meta[name=\"viewport\"]')) { " +
                            "var meta = document.createElement('meta'); " +
                            "meta.name = 'viewport'; " +
                            "meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'; " +
                            "document.getElementsByTagName('head')[0].appendChild(meta); " +
                            "} })()"
                    )
                }
            }
            if (exposeBridge) {
                val bridge = ModuleJsBridge(
                    module,
                    commandReviewer = { request ->
                        confirmCommandOnUiThread(request, onPendingCommand)
                    }
                )
                addJavascriptInterface(bridge, "Shizuku")
                addJavascriptInterface(bridge, "Nightzuku")
            }
            loadUrl(assetServer.entryUrl(index))
        }
    }

    private fun confirmCommandOnUiThread(
        request: ModuleCommandRequest,
        showDialog: (ModuleCommandRequest, (Boolean) -> Unit) -> Unit
    ): Boolean {
        val latch = CountDownLatch(1)
        val approved = AtomicBoolean(false)
        runOnUiThread {
            showDialog(request) { allowed ->
                approved.set(allowed)
                latch.countDown()
            }
        }
        return latch.await(5, TimeUnit.MINUTES) && approved.get()
    }

    private open class LocalModuleWebViewClient(
        private val assetServer: ModuleWebAssetServer
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return assetServer.intercept(request)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldInterceptRequest(
            view: WebView,
            url: String
        ): WebResourceResponse? {
            return assetServer.interceptUri(runCatching { Uri.parse(url) }.getOrNull(), "GET")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return assetServer.shouldOverrideNavigation(request)
        }
    }

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
    }
}
