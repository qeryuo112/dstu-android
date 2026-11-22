package com.deepstudent.app.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object SyncInvalidation {
    private val mutableGeneration = MutableStateFlow(0L)
    val generation = mutableGeneration.asStateFlow()
    fun notifyChanged() { mutableGeneration.update { it + 1 } }
}
