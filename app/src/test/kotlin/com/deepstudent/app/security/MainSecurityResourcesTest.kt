package com.deepstudent.app.security

import com.deepstudent.app.graph.GraphLogger
import com.deepstudent.app.graph.LazyProcessOwner
import com.deepstudent.platform.security.CredentialKey
import com.deepstudent.platform.security.CredentialStoreError
import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.platform.security.DsbkError
import com.deepstudent.platform.security.DsbkException
import com.deepstudent.platform.security.KeyWrapper
import com.deepstudent.platform.security.SecretBytes
import com.deepstudent.platform.security.SecurityAudit
import com.deepstudent.platform.security.SecurityAuditEvent
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.platform.security.SecurityOperation
import com.deepstudent.platform.security.SecurityOutcome
import com.deepstudent.platform.security.SecurityPorts
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MainSecurityResourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun auditRendersOnlyValidatedFields() {
        val event = SecurityAuditEvent(
            operation = SecurityOperation.CREDENTIAL_PUT,
            outcome = SecurityOutcome.FAILED,
            resourceDigest = "012345abcdef",
            bytes = 8,
            errorCode = CredentialStoreError.STORAGE_UNAVAILABLE.name,
        )
        assertEquals(
            "owner=platform.security operation=CREDENTIAL_PUT outcome=FAILED " +
                "resource=012345abcdef bytes=8 error=STORAGE_UNAVAILABLE",
            event.toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SecurityAuditEvent(SecurityOperation.CREDENTIAL_GET, SecurityOutcome.FAILED, errorCode = "SECRET_SENTINEL")
        }
    }

    @Test
    fun ownerIsLazySingleInstanceAndClosedPortsFail() {
        val opens = AtomicInteger()
        val owner = LazyProcessOwner("security-test", NoopGraphLogger) {
            opens.incrementAndGet()
            // Identity wrapping is exclusively for JVM lifecycle tests, never production.
            SecurityFactory.create(temporary.root, TestKeyWrapper)
        }
        assertFalse(owner.isInitialized)
        assertEquals(0, opens.get())

        val workers = 12
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map {
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    owner.get()
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val ports = futures.first().get(10, TimeUnit.SECONDS)
            futures.forEach { assertSame(ports, it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1, opens.get())
            assertTrue(owner.isInitialized)

            owner.close()
            owner.close()
            assertFalse(owner.isInitialized)
            assertThrows(IllegalStateException::class.java) { owner.get() }
            val credentialFailure = assertThrows(CredentialStoreException::class.java) {
                ports.credentials.get(CredentialKey.of("closed"))
            }
            assertEquals(CredentialStoreError.CLOSED, credentialFailure.error)
            val stagingFailure = assertThrows(DsbkException::class.java) {
                ports.staging.createSink(File(temporary.root, "target"))
            }
            assertEquals(DsbkError.STAGING_FAILED, stagingFailure.error)
        } finally {
            start.countDown()
            pool.shutdownNow()
            owner.close()
        }
    }

    @Test
    fun reusedOwnerCallsResourceCloseExactlyOnce() {
        val closes = AtomicInteger()
        val resource = AutoCloseable { closes.incrementAndGet() }
        val owner = LazyProcessOwner("security-test", NoopGraphLogger) { resource }
        assertSame(resource, owner.get())
        owner.close()
        owner.close()
        assertEquals(1, closes.get())
        assertThrows(IllegalStateException::class.java) { owner.get() }
    }

    @Test
    fun closingUnusedOwnerDoesNotCreateSecurityPorts() {
        val owner = LazyProcessOwner<SecurityPorts>("security-test", NoopGraphLogger) {
            error("unused owner must not open")
        }
        owner.close()
        owner.close()
        assertFalse(owner.isInitialized)
        assertThrows(IllegalStateException::class.java) { owner.get() }
    }

    @Test
    fun credentialFailureAuditsAndErrorsDoNotContainSentinel() {
        val sentinel = "SECRET_SENTINEL_2c6c"
        val messages = mutableListOf<String>()
        val wrapper = object : KeyWrapper {
            override fun wrap(plaintext: ByteArray): ByteArray = error(sentinel)
            override fun unwrap(wrapped: ByteArray): ByteArray = error(sentinel)
        }
        val ports = SecurityFactory.create(
            temporary.root,
            wrapper,
            audit = SecurityAudit { messages += it.toString() },
        )
        val secret = SecretBytes.fromUtf8(sentinel.toCharArray())
        try {
            val error = assertThrows(CredentialStoreException::class.java) {
                ports.credentials.put(CredentialKey.of(sentinel), secret)
            }
            assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, error.error)
            assertFalse(error.stackTraceToString().contains(sentinel))
            assertTrue(messages.any { it.contains("outcome=FAILED") })
            assertTrue(messages.none { it.contains(sentinel) })
        } finally {
            secret.close()
            ports.close()
        }
    }
}

private object TestKeyWrapper : KeyWrapper {
    override fun wrap(plaintext: ByteArray): ByteArray = plaintext.copyOf()
    override fun unwrap(wrapped: ByteArray): ByteArray = wrapped.copyOf()
}

private object NoopGraphLogger : GraphLogger {
    override fun info(event: String, fields: Map<String, Any?>) = Unit
    override fun warn(event: String, throwable: Throwable?, fields: Map<String, Any?>) = Unit
}
