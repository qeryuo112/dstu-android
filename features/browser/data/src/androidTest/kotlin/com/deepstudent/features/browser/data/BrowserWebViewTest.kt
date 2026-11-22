package com.deepstudent.features.browser.data

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.features.browser.backend.domain.BrowserUrlPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserWebViewTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val host = BrowserWebTestHost()

    @Before fun startHost() = host.start()
    @After fun closeHost() = host.close()

    @Test fun loginHistoryCloseAndCookieSessionOnLoopback() {
        LoopbackLoginServer().use { server ->
            var owner = create()
            try {
                main { owner.navigate(server.origin) }
                waitUntil { owner.state.value.title == "Login" && !owner.state.value.loading }
                script(owner, "document.querySelector('#login').click()")
                waitUntil { owner.state.value.title == "Signed in" && !owner.state.value.loading }
                main { owner.navigate("${server.origin}/next") }
                waitUntil { owner.state.value.title == "Next" && !owner.state.value.loading && owner.state.value.canGoBack }
                assertTrue(owner.state.value.canGoBack)
                main { assertTrue(owner.goBack()) }
                waitUntil { owner.state.value.title == "Signed in" && !owner.state.value.loading }
                val closed = owner
                main { closed.close() }
                assertTrue(closed.state.value.closed)
                main { closed.navigate("${server.origin}/account") }
                assertTrue(closed.state.value.closed)
                owner = create()
                main { owner.navigate("${server.origin}/account") }
                waitUntil { owner.state.value.title == "Signed in" && !owner.state.value.loading }
            } finally {
                main { owner.close(); CookieManager.getInstance().setCookie(server.origin, "s115=; Max-Age=0; Path=/"); CookieManager.getInstance().flush() }
            }
        }
    }

    private fun create(): AndroidBrowserOwner {
        lateinit var owner: AndroidBrowserOwner
        main { owner = AndroidBrowserOwner(host.activity, BrowserUrlPolicy(true)); host.attach(owner.view) }
        return owner
    }
    private fun script(owner: AndroidBrowserOwner, source: String) {
        val done = CountDownLatch(1)
        main { (owner.view as WebView).evaluateJavascript(source) { done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun waitUntil(test: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < end) { if (test()) return; Thread.sleep(100) }
        fail("Timed out waiting for browser page")
    }
}
