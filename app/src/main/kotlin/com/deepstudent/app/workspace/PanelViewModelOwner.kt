package com.deepstudent.app.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.deepstudent.features.workspace.api.PanelId

private class PanelViewModelOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** A closed/reopened panel cannot inherit an old note VM from the Activity. */
@Composable
internal fun PanelViewModels(panelId: PanelId, content: @Composable () -> Unit) {
    val owner = remember(panelId) { PanelViewModelOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
