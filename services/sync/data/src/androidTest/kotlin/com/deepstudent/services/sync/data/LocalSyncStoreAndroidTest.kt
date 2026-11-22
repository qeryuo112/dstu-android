package com.deepstudent.services.sync.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.sync.api.SyncConfiguration
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSyncStoreAndroidTest {
    @Test fun realKeystoreAndConfigurationReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val token = UUID.randomUUID().toString()
        val alias = "sync-test-$token"
        val root = File(context.cacheDir.canonicalFile, "sync-test-$token")
        val secret = "sync-secret-$token"
        try {
            val security = SecurityFactory.create(File(root, "security"), alias)
            val first = LocalSyncStore(File(root, "config"), security.credentials)
            val config = SyncConfiguration("https://example.test", "user", "root", first.getOrCreate(), "instance")
            first.save(config, secret.toCharArray())
            security.close()
            val reopened = SecurityFactory.create(File(root, "security"), alias)
            try {
                val store = LocalSyncStore(File(root, "config"), reopened.credentials)
                assertEquals(config, store.read())
                val password = store.password()
                try { password.use { bytes -> assertEquals(secret, bytes.toString(Charsets.UTF_8)) } } finally { password.close() }
                for (file in root.walkTopDown().filter { it.isFile }) {
                    assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
                }
            } finally { reopened.close() }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            root.deleteRecursively()
        }
    }
}
