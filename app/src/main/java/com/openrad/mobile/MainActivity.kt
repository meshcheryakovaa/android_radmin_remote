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
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: WebBridge
    private var consent: CompletableDeferred<Boolean>? = null
    private var exportText: String? = null
    private var importResult: CompletableDeferred<Uri?>? = null
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importResult?.complete(uri)
        importResult = null
    }

    private suspend fun readImportFile(): String? {
        val uri = withContext(Dispatchers.Main) {
            check(importResult == null) { "Import already pending" }
            val pending = CompletableDeferred<Uri?>()
            importResult = pending
            importDocument.launch(arrayOf("*/*"))
            try { pending.await() } finally { if (importResult === pending) importResult = null }
        } ?: return null
        return withContext(Dispatchers.IO) {
            val max = com.openrad.mobile.xray.XrayImport.MAX_BYTES
            contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > max) throw com.openrad.mobile.xray.ProfileImportException("JSON превышает 512 КБ")
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            } ?: throw com.openrad.mobile.xray.ProfileImportException("Не удалось прочитать файл")
        }
    }
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
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.url.scheme == "https" && request.url.authority == "appassets.androidplatform.net" &&
                    (request.url.path?.startsWith("/remote/frame/") == true || request.url.path?.startsWith("/remote/thumb/") == true) && ::bridge.isInitialized) {
                    val id = request.url.lastPathSegment.orEmpty()
                    val width = request.url.getQueryParameter("width")?.toIntOrNull() ?: 400
                    val bytes = runCatching {
                        if (request.url.path?.startsWith("/remote/thumb/") == true) {
                            val token = request.url.getQueryParameter("epoch")?.toLongOrNull() ?: return@runCatching null
                            bridge.remote.thumbnail(id.toLongOrNull() ?: return@runCatching null, token)
                        } else bridge.remote.image(id, width)
                    }.getOrNull()
                    return WebResourceResponse(if (bytes != null) "image/jpeg" else "text/plain", null,
                        if (bytes != null) 200 else 503, if (bytes != null) "OK" else "Unavailable",
                        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
                        ByteArrayInputStream(bytes ?: ByteArray(0)))
                }
                return assets.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", 403,
                    "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.toString().substringBefore('#') != PAGE
        }
        bridge = WebBridge(this, lifecycleScope, ::requestVpn, { text ->
            withContext(Dispatchers.Main) {
                check(exportText == null) { "Export already pending" }
                exportText = text
                export.launch("radmin-diagnostics.txt")
            }
        }, ::readImportFile)
        WebViewCompat.addWebMessageListener(web, "Native", setOf(ORIGIN)) { _, message, origin, mainFrame, reply ->
            if (mainFrame && origin.toString() == ORIGIN) {
                bridge.receive(message.data.orEmpty()) { response -> reply.postMessage(response) }
            }
        }
        web.loadUrl(PAGE)
    }

    override fun onStart() {
        super.onStart()
        if (::bridge.isInitialized) bridge.remote.resume()
    }

    override fun onStop() {
        if (::bridge.isInitialized) bridge.remote.pause()
        super.onStop()
    }

    override fun onDestroy() {
        if (::bridge.isInitialized) bridge.remote.pause()
        consent?.cancel()
        importResult?.cancel()
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
