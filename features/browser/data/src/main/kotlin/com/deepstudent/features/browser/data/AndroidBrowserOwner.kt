package com.deepstudent.features.browser.data

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.deepstudent.features.browser.api.BrowserNavigationPolicy
import com.deepstudent.features.browser.api.BrowserSession
import com.deepstudent.features.browser.api.BrowserState
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidBrowserOwner(context: Context, private val navigation: BrowserNavigationPolicy) : BrowserSession {
    init { mainThread() }
    private val webView = WebView(context)
    private val mutableState = MutableStateFlow(BrowserState())
    override val state: StateFlow<BrowserState> = mutableState
    val view: View get() = webView

    init {
        configure()
        webView.webViewClient = client()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { update(title = title.orEmpty()) }
        }
        Log.i(TAG, "stage=open owner=browser")
    }

    override fun navigate(url: String) {
        mainThread()
        if (state.value.closed) return
        if (!navigation.allows(url)) { mutableState.value = state.value.copy(error = "Unsupported address"); return }
        mutableState.value = state.value.copy(error = null, loading = true)
        webView.loadUrl(url)
    }

    override fun goBack(): Boolean {
        mainThread()
        if (state.value.closed || !webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    override fun reload() {
        mainThread()
        if (!state.value.closed && navigation.allows(webView.url.orEmpty())) webView.reload()
    }

    override fun close() {
        mainThread()
        if (state.value.closed) return
        mutableState.value = state.value.copy(closed = true, loading = false, canGoBack = false)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.clearHistory()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
        CookieManager.getInstance().flush()
        Log.i(TAG, "stage=closed owner=browser")
    }

    private fun configure() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.settings.setGeolocationEnabled(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    private fun client() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !navigation.allows(request.url.toString())

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (navigation.allows(request.url.toString())) return null
            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (!navigation.allows(url)) { view.stopLoading(); return }
            update(url = url, loading = true)
        }
        override fun onPageFinished(view: WebView, url: String) {
            update(url = url, loading = false)
            view.post { historyReady() }
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            update(url = url)
            view.post { historyReady() }
        }
        override fun onPageCommitVisible(view: WebView, url: String) { view.post { historyReady() } }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame && !state.value.closed) mutableState.value = state.value.copy(loading = false, error = "Page unavailable")
        }
    }

    private fun historyReady() {
        if (state.value.closed) return
        val history = webView.copyBackForwardList()
        mutableState.value = state.value.copy(canGoBack = webView.canGoBack())
        Log.i(TAG, "stage=history entries=${history.size} index=${history.currentIndex} back=${state.value.canGoBack}")
    }

    private fun update(url: String = state.value.url, title: String = state.value.title, loading: Boolean = state.value.loading) {
        if (!state.value.closed) mutableState.value = state.value.copy(url = url, title = title, loading = loading, canGoBack = webView.canGoBack())
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())
    private companion object { const val TAG = "DstuS115Browser" }
}
