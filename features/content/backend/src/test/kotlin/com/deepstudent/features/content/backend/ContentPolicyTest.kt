package com.deepstudent.features.content.backend

import com.deepstudent.features.content.api.ContentEvent
import com.deepstudent.features.content.api.ContentResource
import com.deepstudent.features.content.backend.domain.ContentMessagePolicy
import com.deepstudent.features.content.backend.domain.ContentResourcePolicy
import org.junit.Assert.*
import org.junit.Test

class ContentPolicyTest {
    @Test fun rejectsTraversalAndUnauthorizedResources() {
        val policy = ContentResourcePolicy(listOf(ContentResource("a/image.png", "image/png", byteArrayOf(1))))
        assertNotNull(policy.find("a/image.png"))
        listOf("../a/image.png", "%2e%2e/a", "a/../image.png", "/a/image.png", "a\\image.png", "a/image.png?x")
            .forEach { assertNull(it, policy.find(it)) }
        assertNull(policy.find("other.png"))
        assertThrows(IllegalArgumentException::class.java) {
            ContentResourcePolicy(listOf(ContentResource("x.html", "text/html", byteArrayOf())))
        }
    }

    @Test fun messageRequiresOriginFrameNonceSchemaAndBudget() {
        val origin = "https://abc.content.invalid"
        val valid = """["secret","answer","ok"]"""
        val policy = ContentMessagePolicy(origin, "secret", limit = 4)
        assertNull(policy.accept("https://other.invalid", true, valid))
        assertNull(policy.accept(origin, false, valid))
        assertNull(policy.accept(origin, true, valid.replace("secret", "wrong")))
        assertNull(policy.accept(origin, true, valid.replace("\"ok\"", "12")))
        assertNull(policy.accept(origin, true, valid.replace("]", ",true]")))
        assertEquals(ContentEvent.Answer("ok"), policy.accept(origin, true, valid))
        assertNull(policy.accept(origin, true, valid))
        policy.close()
        assertNull(policy.accept(origin, true, valid))
    }

    @Test fun linksCannotEscapeIntoOtherSchemesOrCredentials() {
        val policy = ContentMessagePolicy("https://a", "n")
        fun event(url: String) = policy.accept("https://a", true, """["n","openLink","$url"]""")
        assertNull(event("javascript:alert(1)"))
        assertNull(event("https://user:pass@example.com"))
        assertEquals(ContentEvent.OpenLink("https://example.com"), event("https://example.com"))
    }

    @Test fun rejectsDuplicateKeysAndOversizeInputs() {
        val policy = ContentMessagePolicy("https://a", "n")
        assertNull(policy.accept("https://a", true, """{"nonce":"wrong","nonce":"n","type":"answer","value":"ok"}"""))
        assertNull(policy.accept("https://a", true, """["n","answer","${"x".repeat(5000)}"]"""))
    }
}
