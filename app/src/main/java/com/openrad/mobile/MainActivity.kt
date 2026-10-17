package com.openrad.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.openrad.mobile.web.WebBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: WebBridge
    private var consent: CompletableDeferred<Boolean>? = null
    private var exportText: String? = null
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        consent?.complete(it.resultCode == Activity.RESULT_OK && VpnService.prepare(this) == null)
        consent = null
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = exportText
        exportText = null
        if (uri != null && text != null) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
        }
    }

    private suspend fun requestVpn(): Boolean = withContext(Dispatchers.Main) {
        val intent = VpnService.prepare(this@MainActivity) ?: return@withContext true
        check(consent == null) { "VPN permission request already pending" }
        val pending = CompletableDeferred<Boolean>()
        consent = pending
        vpnConsent.launch(intent)
        try { pending.await() } finally { if (consent === pending) consent = null }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            web.loadData("<p>Update Android System WebView to use Radmin Mobile.</p>", "text/html", "UTF-8")
            return
        }
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                assets.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", 403,
                    "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.toString().substringBefore('#') != PAGE
        }
        bridge = WebBridge(this, lifecycleScope, ::requestVpn) { text ->
            withContext(Dispatchers.Main) {
                check(exportText == null) { "Export already pending" }
                exportText = text
                export.launch("radmin-diagnostics.txt")
            }
        }
        WebViewCompat.addWebMessageListener(web, "Native", setOf(ORIGIN)) { _, message, origin, mainFrame, reply ->
            if (mainFrame && origin.toString() == ORIGIN) {
                bridge.receive(message.data.orEmpty()) { response -> reply.postMessage(response) }
            }
        }
        web.loadUrl(PAGE)
    }

    override fun onDestroy() {
        consent?.cancel()
        if (::web.isInitialized) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "Native")
            web.destroy()
        }
        super.onDestroy()
    }
    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val PAGE = "$ORIGIN/assets/www/index.html"
    }
}
