package com.deepstudent.features.content.api

import kotlinx.coroutines.flow.StateFlow

data class ContentTheme(
    val dark: Boolean,
    val background: String,
    val foreground: String,
    val accent: String,
)

data class ContentResource(val path: String, val mimeType: String, val bytes: ByteArray)

sealed interface ContentDocument {
    data class Formula(val source: String, val display: Boolean = true) : ContentDocument
    data class Diagram(val source: String) : ContentDocument
    data class HtmlCard(val html: String) : ContentDocument
}

sealed interface ContentEvent {
    data class Answer(val value: String) : ContentEvent
    data class OpenLink(val url: String) : ContentEvent
    data object Replay : ContentEvent
}

enum class ContentStatus { Loading, Ready, Failed, Closed }

interface ContentSession : AutoCloseable {
    val status: StateFlow<ContentStatus>
    fun setTheme(theme: ContentTheme)
    override fun close()
}

interface ContentResources {
    fun find(path: String): ContentResource?
}

interface ContentMessageValidator : AutoCloseable {
    fun accept(sourceOrigin: String, mainFrame: Boolean, payload: String): ContentEvent?
    override fun close()
}

fun interface ContentMessageValidatorFactory {
    fun create(origin: String, nonce: String): ContentMessageValidator
}
