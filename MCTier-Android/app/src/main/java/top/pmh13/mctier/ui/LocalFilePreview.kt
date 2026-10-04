package top.pmh13.mctier.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LocalFilePreview(file: File, kind: String) {
    val dark = PageBg.luminance() < .5f
    val english = L("中", "en") == "en"
    key(file.absolutePath, kind) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context -> WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { syncPreviewTheme(view) }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == "preview.mctier.invalid" && request.method == "GET") {
                        if (uri.path == "/index.html") return WebResourceResponse("text/html", "UTF-8", context.assets.open("file-preview/index.html"))
                        if (uri.path == "/attachment" && file.length() <= 64L * 1024 * 1024) return WebResourceResponse("application/octet-stream", null, file.inputStream())
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
            }
            // No JavaScript bridge, file access, cookies or remote document service.
            loadUrl("https://preview.mctier.invalid/index.html?android=1&kind=$kind&name=${URLEncoder.encode(file.name, "UTF-8")}&dark=${if (dark) 1 else 0}&en=${if (english) 1 else 0}")
        } },
        update = { view -> view.tag = dark; syncPreviewTheme(view) },
        onRelease = { it.stopLoading(); it.destroy() },
    )
    }
}

private fun syncPreviewTheme(view: WebView) {
    val dark = view.tag as? Boolean ?: return
    view.evaluateJavascript("window.postMessage({type:'mctier-preview-theme',dark:$dark}, '*')", null)
}
