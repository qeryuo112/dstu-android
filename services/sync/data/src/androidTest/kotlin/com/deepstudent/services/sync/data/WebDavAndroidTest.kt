package com.deepstudent.services.sync.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavAndroidTest {
    @get:Rule val s229Foreground = S229PublicationForegroundRule()

    @Test fun listingAndXxe() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider = WebDavProviderFactory(allowLoopbackHttp = true).open(server.url("/").toString(), "u", "r", charArrayOf())
            val body = "<d:multistatus xmlns:d='DAV:'><d:response><d:href>/r/</d:href>" +
                "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
                "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
            server.enqueue(MockResponse().setResponseCode(207).setBody(body))
            assertEquals(emptyList<String>(), provider.list(""))
            server.enqueue(MockResponse().setResponseCode(207).setBody("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>"))
            try {
                provider.list("")
                fail()
            } catch (failure: SyncFailure) {
                assertEquals(SyncError.INVALID_REPOSITORY, failure.error)
            }
        }
    }
}
