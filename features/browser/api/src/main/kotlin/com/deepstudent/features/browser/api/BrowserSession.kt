package com.deepstudent.features.browser.api

import kotlinx.coroutines.flow.StateFlow

data class BrowserState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val error: String? = null,
    val closed: Boolean = false,
)

interface BrowserSession : AutoCloseable {
    val state: StateFlow<BrowserState>
    fun navigate(url: String)
    fun goBack(): Boolean
    fun reload()
    override fun close()
}

fun interface BrowserNavigationPolicy {
    fun allows(url: String): Boolean
}
