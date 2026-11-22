package com.deepstudent.app.workspace

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun browserText(root: View): String {
    val value = AtomicReference("")
    val done = CountDownLatch(1)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val browser = findBrowser(root)
        if (browser == null) done.countDown()
        else browser.evaluateJavascript("document.body.innerText") { value.set(it); done.countDown() }
    }
    check(done.await(5, TimeUnit.SECONDS))
    return value.get()
}

private fun findBrowser(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) findBrowser(view.getChildAt(index))?.let { return it }
    return null
}
