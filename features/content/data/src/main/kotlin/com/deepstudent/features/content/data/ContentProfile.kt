package com.deepstudent.features.content.data

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** WebKit cannot delete an in-memory profile, so reuse one bounded, content-only profile. */
internal object ContentProfile {
    private const val NAME = "dstu-content-host-v1"
    private var initialized = false

    fun attach(view: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return false
        if (!initialized) {
            val store = ProfileStore.getInstance()
            store.allProfileNames.filter { it.startsWith("dstu-content-") }.forEach { name ->
                runCatching { store.deleteProfile(name) }
            }
            initialized = true
        }
        WebViewCompat.setProfile(view, NAME)
        WebViewCompat.getProfile(view).cookieManager.setAcceptCookie(false)
        return true
    }
}
