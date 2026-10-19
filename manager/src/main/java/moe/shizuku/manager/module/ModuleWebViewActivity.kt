package moe.shizuku.manager.module

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuScaffold
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@ModuleWebViewActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    var webViewCrashError by remember { mutableStateOf<String?>(null) }

                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(WearMaterialTheme.colorScheme.background)
                    ) {
                        if (webViewCrashError == null) {
                            AndroidView(
                                factory = {
                                    val wrapper = android.widget.FrameLayout(this@ModuleWebViewActivity)
                                    val webView = try {
                                        WebView(this@ModuleWebViewActivity)
                                    } catch (e: Throwable) {
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            webViewCrashError = e.message ?: "Unknown error"
                                        }
                                        return@AndroidView wrapper
                                    }
                                    activeWebView = webView
                                    wrapper.addView(
                                        webView,
                                        android.widget.FrameLayout.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                    configureModuleWebView(
                                        webView = webView,
                                        module = module,
                                        index = index,
                                        trusted = trusted,
                                        webNetworkAllowed = webNetworkAllowed,
                                        exposeBridge = exposeBridge,
                                        isWatch = true,
                                        onPendingCommand = { req, cb ->
                                            pendingCommand = req
                                            pendingDecision = cb
                                        }
                                    )
                                    wrapper
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    webViewCrashError?.let { errorMsg ->
                        val okText = stringResource(android.R.string.ok)
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { finish() },
                            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            moe.shizuku.manager.ui.compose.WearShizukuTheme {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(WearMaterialTheme.colorScheme.background)
                                ) {
                                    moe.shizuku.manager.ui.compose.WearScreenScaffold { scrollState ->
                                        androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                                            state = scrollState,
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                                top = 32.dp,
                                                bottom = 32.dp,
                                                start = 8.dp,
                                                end = 8.dp
                                            ),
                                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(WearMaterialTheme.colorScheme.background)
                                        ) {
                                            item {
                                                WearText(
                                                    text = "WebView Error",
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = WearMaterialTheme.colorScheme.error,
                                                    style = WearMaterialTheme.typography.titleMedium
                                                )
                                            }
                                            item {
                                                WearText(
                                                    text = "WebView is not supported or crashed during initialization on this WearOS device.\n\n$errorMsg",
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                            item {
                                                WearButton(
                                                    onClick = { finish() },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    WearText(okText)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    pendingCommand?.let { request ->
                        val warningTitle = stringResource(R.string.unsafe_warning_title)
                        val okText = stringResource(android.R.string.ok)
                        val cancelText = stringResource(android.R.string.cancel)
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = {
                                pendingDecision?.invoke(false)
                                pendingCommand = null
                                pendingDecision = null
                            },
                            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            moe.shizuku.manager.ui.compose.WearScreenScaffold { scrollState ->
                                androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                                    state = scrollState,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        top = 32.dp,
                                        bottom = 32.dp,
                                        start = 8.dp,
                                        end = 8.dp
                                    ),
                                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(WearMaterialTheme.colorScheme.background)
                                ) {
                                    item {
                                        WearText(
                                            text = warningTitle,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                            color = WearMaterialTheme.colorScheme.primary,
                                            style = WearMaterialTheme.typography.titleMedium
                                        )
                                    }
                                    item {
                                        WearText(
                                            text = request.command,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    item {
                                        WearButton(
                                            onClick = {
                                                pendingDecision?.invoke(true)
                                                pendingCommand = null
                                                pendingDecision = null
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            WearText(okText)
                                        }
                                    }
                                    item {
                                        WearFilledTonalButton(
                                            onClick = {
                                                pendingDecision?.invoke(false)
                                                pendingCommand = null
                                                pendingDecision = null
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            WearText(cancelText)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
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
                                        isWatch = false,
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureModuleWebView(
        webView: WebView,
        module: AdbModule,
        index: File,
        trusted: Boolean,
        webNetworkAllowed: Boolean,
        exposeBridge: Boolean,
        isWatch: Boolean,
        onPendingCommand: (ModuleCommandRequest, (Boolean) -> Unit) -> Unit
    ) {
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = trusted
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = trusted
            settings.blockNetworkLoads = !webNetworkAllowed
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            if (isWatch) settings.textZoom = 85

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : LocalModuleWebViewClient(module, webNetworkAllowed) {
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
            loadUrl(index.toURI().toString())
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
        private val module: AdbModule,
        private val webNetworkAllowed: Boolean
    ) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "file" -> !isInsideWebRoot(uri.path.orEmpty())
                "https" -> !webNetworkAllowed
                "http" -> true
                else -> true
            }
        }

        private fun isInsideWebRoot(path: String): Boolean {
            val root = module.webRoot ?: return false
            return runCatching {
                val rootFile = root.canonicalFile.toPath()
                val target = File(path).canonicalFile.toPath()
                target.startsWith(rootFile)
            }.getOrDefault(false)
        }
    }

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
    }
}
