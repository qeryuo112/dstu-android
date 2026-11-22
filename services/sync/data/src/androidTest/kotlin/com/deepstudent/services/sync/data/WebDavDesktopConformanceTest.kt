package com.deepstudent.services.sync.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavDesktopConformanceTest {
    @Test fun actualDesktopAndAndroidShareProviderObjectsAndPairing() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = requireNotNull(arguments.getString("webdavEndpoint"))
        require(endpoint.startsWith("http://127.0.0.1:"))
        val token = UUID.randomUUID().toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir.canonicalFile, "sync-conformance-$token")
        val alias = "com.deepstudent.sync.conformance.$token"
        val password = "public-fixture-password".toCharArray()
        val factory = WebDavProviderFactory(allowLoopbackHttp = true)
        val provider = factory.open(endpoint, "public-sync-user", "shared-provider", password)
        try {
            val desktopBytes = "desktop-public\r\n中文😀\u0000".toByteArray()
            assertArrayEquals(desktopBytes, provider.get("from-desktop/中文 %.bin"))
            val androidBytes = "android-public\r\n中文😀\u0000".toByteArray()
            provider.put("from-android/中文 %.bin", androidBytes)
            assertArrayEquals(androidBytes, provider.get("from-android/中文 %.bin"))
            assertTrue(provider.list("from-").containsAll(listOf("from-desktop/中文 %.bin", "from-android/中文 %.bin")))
            provider.put("from-android/remove.bin", byteArrayOf(1))
            provider.delete("from-android/remove.bin")
            assertEquals(null, provider.get("from-android/remove.bin"))
            val paired = pair(directory, alias, endpoint, factory)
            SecurityFactory.create(File(directory, "security"), alias).use { security ->
                val reopened = LocalSyncStore(File(directory, "configuration"), security.credentials)
                assertEquals(paired, reopened.read())
                val secret = reopened.password()
                try {
                    secret.use { bytes -> assertEquals("public-fixture-password", bytes.toString(Charsets.UTF_8)) }
                } finally {
                    secret.close()
                }
                val joined = PairingService(reopened, reopened, factory).pair(PairingMode.JOIN,
                    PairingRequest(endpoint, "public-sync-user", "created-on-android", "public-fixture-password".toCharArray()))
                assertEquals(SyncResult.Success(paired), joined)
            }
            for (file in directory.walkTopDown().filter(File::isFile)) {
                assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("public-fixture-password"))
            }
        } finally {
            password.fill('\u0000')
            check(alias != AndroidKeystoreKeyWrapper.DEFAULT_ALIAS)
            AndroidKeystoreKeyWrapper(alias).deleteKey()
            directory.deleteRecursively()
        }
    }

    private suspend fun pair(directory: File, alias: String, endpoint: String, factory: WebDavProviderFactory): SyncConfiguration {
        SecurityFactory.create(File(directory, "security"), alias).use { security ->
            val store = LocalSyncStore(File(directory, "configuration"), security.credentials)
            val request = PairingRequest(endpoint, "public-sync-user", "created-on-android", "public-fixture-password".toCharArray())
            val result = PairingService(store, store, factory).pair(PairingMode.CREATE, request)
            assertTrue(result.toString(), result is SyncResult.Success)
            assertTrue(request.password.all { it == '\u0000' })
            return (result as SyncResult.Success).value
        }
    }
}
