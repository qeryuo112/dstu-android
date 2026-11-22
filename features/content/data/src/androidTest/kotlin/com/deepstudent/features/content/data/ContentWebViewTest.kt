package com.deepstudent.features.content.data

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.features.content.api.*
import com.deepstudent.features.content.backend.domain.ContentMessagePolicy
import com.deepstudent.features.content.backend.domain.ContentResourcePolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import android.net.Uri
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentWebViewTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val events = CopyOnWriteArrayList<ContentEvent>()
    private val host = ContentWebTestHost()
    private var owner: AndroidContentOwner? = null
    private val light = ContentTheme(false, "#ffffff", "#111111", "#116644")
    private val dark = ContentTheme(true, "#101010", "#eeeeee", "#33aa77")

    @Before fun startHost() = host.start()
    @After fun close() { instrument.runOnMainSync { owner?.close() }; host.close() }

    @Test fun formulaAndDiagramUseRealEngines() {
        start(ContentDocument.Formula("x^2 + y^2"))
        waitFor("document.querySelectorAll('.katex').length > 0")
        reset()
        start(ContentDocument.Diagram("graph TD; A-->B"))
        waitFor("document.querySelectorAll('[data-dstu-diagram] svg').length > 0")
    }

    @Test fun cardInteractionThemeAndForgedMessagesAreConfined() {
        val html = """<style>.custom{color:rgb(1, 2, 3)}</style><div id="custom" class="custom">card</div>
            <button id="answer" data-dstu-answer="yes">Answer</button>
            <img id="allowed" src="/resource/ok.png"><img id="denied" src="/resource/missing.png">
            <script>window.cardRuns = true;</script>"""
        start(ContentDocument.HtmlCard(html), listOf(ContentResource("ok.png", "image/png", PNG)))
        waitFor("window.cardRuns === true && document.querySelector('#allowed').complete && document.querySelector('#allowed').naturalWidth > 0")
        waitFor("document.querySelector('#denied').complete && document.querySelector('#denied').naturalWidth === 0")
        assertEquals("\"rgb(1, 2, 3)\"", evaluate("getComputedStyle(document.querySelector('#custom')).color"))
        evaluate("document.querySelector('#answer').click()")
        waitUntil { events.contains(ContentEvent.Answer("yes")) }
        evaluate("window.dstuContent.postMessage(JSON.stringify(['wrong','answer','forged']))")
        evaluate("window.dstuContent.postMessage(JSON.stringify([new URLSearchParams(location.search).get('nonce'),'unknown','']))")
        evaluate("""(() => { const f = document.createElement('iframe'); f.src = 'https://outside.invalid/'; document.body.appendChild(f); return true; })()""")
        evaluate("""(() => { const f = document.createElement('iframe'); f.srcdoc = '<script>window.dstuContent.postMessage(JSON.stringify([new URLSearchParams(parent.location.search).get(\"nonce\"),\"answer\",\"subframe\"]))<\/script>'; document.body.appendChild(f); return true; })()""")
        instrument.runOnMainSync { owner!!.setTheme(dark) }
        waitFor("document.documentElement.classList.contains('night_mode') && getComputedStyle(document.documentElement).getPropertyValue('--background').trim() === '#101010'")
        assertFalse(events.contains(ContentEvent.Answer("forged")))
        assertFalse(events.contains(ContentEvent.Answer("subframe")))
        assertEquals("\"rgb(1, 2, 3)\"", evaluate("getComputedStyle(document.querySelector('#custom')).color"))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val responses = ContentResponses(context.assets, "https://test.invalid", "n", "", ContentResourcePolicy(emptyList()))
        assertEquals(403, responses.resolve(Uri.parse("https://test.invalid/resource/%252e%252e/ok.png"), false).statusCode)
        assertEquals(403, responses.resolve(Uri.parse("https://test.invalid/index.html"), false).statusCode)
        assertEquals(403, responses.resolve(Uri.parse("https://test.invalid/resource/ok.png"), true).statusCode)
        val closed = owner!!
        reset()
        assertEquals(ContentStatus.Closed, closed.status.value)
    }

    @Test fun closingContentRevokesResourcesAndReusesOneBoundedProfile() {
        var profiles: ProfileStore? = null
        instrument.runOnMainSync {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) profiles = ProfileStore.getInstance()
        }
        repeat(5) {
            start(ContentDocument.HtmlCard("<p>profile</p>"))
            val closed = owner!!
            reset()
            assertEquals(ContentStatus.Closed, closed.status.value)
            if (profiles != null) {
                var names = emptySet<String>()
                instrument.runOnMainSync { names = profiles!!.allProfileNames.filter { it.startsWith("dstu-content-") }.toSet() }
                assertEquals(setOf("dstu-content-host-v1"), names)
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val responses = ContentResponses(context.assets, "https://test.invalid", "n", "<p>x</p>", ContentResourcePolicy(emptyList()))
        responses.close()
        assertEquals(403, responses.resolve(Uri.parse("https://test.invalid/index.html?nonce=n"), true).statusCode)
    }

    private fun start(document: ContentDocument, resources: List<ContentResource> = emptyList()) {
        instrument.runOnMainSync {
            owner = AndroidContentOwner(
                host.activity, document, ContentResourcePolicy(resources),
                ContentMessageValidatorFactory { origin, nonce -> ContentMessagePolicy(origin, nonce) }, light, events::add,
            )
            host.attach(owner!!.view)
        }
        waitUntil { owner?.status?.value == ContentStatus.Ready }
        waitFor("document.documentElement.dataset.dstuReady === 'true'")
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrument.runOnMainSync { (owner!!.view as WebView).evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun waitFor(script: String) = waitUntil { evaluate(script) == "true" }
    private fun waitUntil(test: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < end) { if (test()) return; Thread.sleep(100) }
        fail("Timed out waiting for WebView condition")
    }
    private fun reset() { instrument.runOnMainSync { owner?.close() }; owner = null }

    private companion object {
        val PNG = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/aN8AAAAASUVORK5CYII=", 0)
    }
}
