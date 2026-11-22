package com.deepstudent.features.content.data

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.deepstudent.features.content.api.*
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

class AndroidContentOwner(
    context: Context,
    document: ContentDocument,
    resources: ContentResources,
    validatorFactory: ContentMessageValidatorFactory,
    initialTheme: ContentTheme,
    private val onEvent: (ContentEvent) -> Unit,
) : ContentSession {
    init {
        mainThread()
        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
    }
    private val nonce = token()
    private val origin = "https://${token()}.invalid"
    private val validator = validatorFactory.create(origin, nonce)
    private val responses = ContentResponses(context.assets, origin, nonce, ContentDocumentPage.render(document, initialTheme), resources)
    private val webView = WebView(context)
    private val mutableStatus = MutableStateFlow(ContentStatus.Loading)
    private var theme: ContentTheme = initialTheme
    override val status: StateFlow<ContentStatus> = mutableStatus
    val view: View get() = webView

    init {
        if (!ContentProfile.attach(webView)) Log.w(TAG, "stage=profile-unavailable origin=ephemeral")
        configure(webView)
        webView.webViewClient = client(responses)
        WebViewCompat.addWebMessageListener(webView, "dstuContent", setOf(origin)) { _, message, source, main, _ ->
            validator.accept(source.toString(), main, message.data ?: "")?.let(onEvent)
        }
        webView.loadUrl("$origin/index.html?nonce=$nonce")
        Log.i(TAG, "stage=open owner=content")
    }

    override fun setTheme(theme: ContentTheme) {
        mainThread()
        if (mutableStatus.value == ContentStatus.Closed) return
        require(listOf(theme.background, theme.foreground, theme.accent).all { COLOR.matches(it) })
        this.theme = theme
        if (mutableStatus.value == ContentStatus.Ready) sendTheme(theme)
    }

    override fun close() {
        mainThread()
        if (mutableStatus.value == ContentStatus.Closed) return
        mutableStatus.value = ContentStatus.Closed
        validator.close()
        responses.close()
        WebViewCompat.removeWebMessageListener(webView, "dstuContent")
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.clearHistory()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
        Log.i(TAG, "stage=closed owner=content")
    }

    private fun client(responses: ContentResponses) = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = responses.resolve(request.url, request.isForMainFrame)
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        override fun onPageFinished(view: WebView, url: String) {
            if (mutableStatus.value == ContentStatus.Closed || !url.startsWith("$origin/index.html?")) return
            mutableStatus.value = ContentStatus.Ready
            sendTheme(theme)
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame && mutableStatus.value != ContentStatus.Closed) mutableStatus.value = ContentStatus.Failed
        }
    }

    private fun sendTheme(theme: ContentTheme) {
        val data = JSONObject().put("kind", "theme").put("nonce", nonce).put("dark", theme.dark)
            .put("background", theme.background).put("foreground", theme.foreground).put("accent", theme.accent)
        webView.evaluateJavascript("window.postMessage($data, ${JSONObject.quote(origin)});", null)
    }

    private fun configure(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = false
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        view.settings.setSupportMultipleWindows(false)
        view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.settings.setGeolocationEnabled(false)
        view.settings.blockNetworkLoads = true
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun token(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private companion object { const val TAG = "DstuS115Content"; val COLOR = Regex("#[0-9a-fA-F]{6}") }
}
