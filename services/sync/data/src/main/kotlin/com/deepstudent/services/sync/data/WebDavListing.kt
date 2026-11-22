package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import okhttp3.HttpUrl
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal data class DavEntry(val key: String, val directory: Boolean)

internal object WebDavListing {
    fun parse(bytes: ByteArray, base: HttpUrl, requested: HttpUrl): List<DavEntry> {
        try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            if (text.contains('\u0000') || text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true)) invalid()
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            val reader = factory.newSAXParser().xmlReader
            val handler = Handler(base, requested)
            reader.contentHandler = handler
            reader.entityResolver = handler
            reader.errorHandler = handler
            reader.parse(InputSource(StringReader(text)))
            return handler.result()
        } catch (failure: SyncFailure) { throw failure } catch (_: Exception) { invalid() }
    }

    private fun invalid(): Nothing = throw SyncFailure(SyncError.INVALID_REPOSITORY)

    private class Handler(private val base: HttpUrl, private val requested: HttpUrl) : DefaultHandler() {
        private val entries = mutableListOf<DavEntry>()
        private val seen = mutableSetOf<String>()
        private val stack = mutableListOf<String>()
        private val text = StringBuilder()
        private var responses = 0
        private var href: String? = null
        private var statuses = 0
        private var types = 0
        private var collection = false
        private var self = false

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = if (uri == "DAV:") localName.orEmpty() else ""
            val parent = stack.lastOrNull()
            if (stack.isEmpty() && name != "multistatus") invalid()
            if (stack.isNotEmpty() && name == "multistatus") invalid()
            validateParent(name, parent)
            when (name) {
                "response" -> beginResponse()
                "resourcetype" -> types++
                "collection" -> collection = true
            }
            stack.add(name)
            text.setLength(0)
        }

        private fun validateParent(name: String, parent: String?) {
            val expected = when (name) {
                "response" -> "multistatus"
                "href", "propstat" -> "response"
                "prop", "status" -> "propstat"
                else -> propertyParent(name) ?: return
            }
            if (parent != expected) invalid()
            if (name == "propstat" && statuses != 0) invalid()
        }

        private fun propertyParent(name: String): String? = when (name) {
            "resourcetype" -> "prop"
            "collection" -> "resourcetype"
            else -> null
        }

        private fun beginResponse() {
            responses++
            if (responses >= 750) invalid()
            href = null
            statuses = 0
            types = 0
            collection = false
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = stack.removeAt(stack.lastIndex)
            when (name) {
                "href" -> { if (href != null) invalid(); href = text.toString().trim() }
                "status" -> {
                    if (!text.toString().trim().matches(Regex("HTTP/\\S+ 200(?: .*)?"))) invalid()
                    statuses++
                }
                "propstat" -> if (statuses != 1) invalid()
                "response" -> finish()
            }
            text.setLength(0)
        }

        private fun finish() {
            if (statuses == 0 || types != 1) invalid()
            val url = checkedUrl(href ?: invalid())
            val parent = requested.encodedPath.trimEnd('/')
            val child = url.encodedPath.trimEnd('/')
            if (child == parent) { acceptSelf(); return }
            validateChild(parent, child)
            val key = relativeKey(url)
            if (!seen.add(key)) invalid()
            entries.add(DavEntry(key, collection))
        }

        private fun acceptSelf() {
            if (self || !collection) invalid()
            self = true
        }

        private fun validateChild(parent: String, child: String) {
            if (!child.startsWith("$parent/") || child.removePrefix("$parent/").contains('/')) invalid()
        }

        private fun checkedUrl(value: String): HttpUrl {
            if (value.any(Char::isISOControl)) invalid()
            val url = requested.resolve(value) ?: invalid()
            validateOrigin(url)
            if (url.query != null || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()) invalid()
            if (url.encodedPath.endsWith('/') != collection) invalid()
            return url
        }

        private fun validateOrigin(url: HttpUrl) {
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) invalid()
        }

        private fun relativeKey(url: HttpUrl): String {
            val baseParts = base.pathSegments.dropLastWhile { it.isEmpty() }
            val parts = url.pathSegments.dropLastWhile { it.isEmpty() }
            if (parts.take(baseParts.size) != baseParts) invalid()
            val relative = parts.drop(baseParts.size)
            if (relative.isEmpty() || relative.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\\' in it || it.any(Char::isISOControl) }) invalid()
            return relative.joinToString("/")
        }

        fun result(): List<DavEntry> {
            if (!self || responses == 0 || stack.isNotEmpty()) invalid()
            return entries
        }
    }
}
