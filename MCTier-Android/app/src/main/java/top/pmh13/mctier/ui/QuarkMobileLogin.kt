package top.pmh13.mctier.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.pmh13.mctier.network.QuarkMobileLogin
import java.io.ByteArrayInputStream

private const val LOGIN_ORIGIN = "https://quark-login.mctier.invalid"
private const val LOGIN_PAGE = "$LOGIN_ORIGIN/index.html"

private class MobileLoginBridgeState {
    var ports: Array<WebMessagePort>? = null
    var released = false
    var submitted = false
}

/** Keep official CAPTCHA/SMS UI in its own origin; expose no JavaScript interface. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun QuarkMobileLoginForm(loginId: String, onTicket: (String) -> Unit, onError: (String) -> Unit) {
    val receive by rememberUpdatedState(onTicket)
    val failure by rememberUpdatedState(onError)
    key(loginId) {
        val connection = remember { MobileLoginBridgeState() }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            factory = { context -> WebView(context).apply {
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                // Match the official desktop login form and its client_id=532.
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https"

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.toString() == LOGIN_PAGE && request.method == "GET") {
                            return WebResourceResponse("text/html", "UTF-8", context.assets.open("quark-mobile-login.html"))
                        }
                        // The official form is hosted in an iframe. Do not reject its
                        // document as a "main frame" request: some WebView versions
                        // report iframe navigations inconsistently, which otherwise
                        // leaves the phone/SMS form as a blank white panel.
                        if (request.url.scheme != "https" || request.url.host == "quark-login.mctier.invalid") {
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (connection.released || url != LOGIN_PAGE || view.url != LOGIN_PAGE || connection.ports != null) return
                        val channel = view.createWebMessageChannel()
                        connection.ports = channel
                        channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                            override fun onMessage(port: WebMessagePort, message: WebMessage?) {
                                val ticket = message?.data
                                if (connection.released || connection.submitted || view.url != LOGIN_PAGE || !QuarkMobileLogin.validTicket(ticket)) return
                                connection.submitted = true
                                receive(requireNotNull(ticket))
                            }
                        })
                        view.postWebMessage(WebMessage("quark-login-channel", arrayOf(channel[1])), Uri.parse(LOGIN_ORIGIN))
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (!connection.released && (request.isForMainFrame || request.url.toString() == QuarkMobileLogin.URL)) {
                            failure(L("手机号登录页面加载失败，请检查网络后重新打开。", "Phone sign-in could not load. Check your connection and reopen it."))
                        }
                    }
                }
                loadUrl(LOGIN_PAGE)
            } },
            onRelease = {
                connection.released = true
                connection.ports?.forEach { port -> runCatching { port.close() } }
                connection.ports = null
                it.stopLoading()
                it.destroy()
            },
        )
    }
}
