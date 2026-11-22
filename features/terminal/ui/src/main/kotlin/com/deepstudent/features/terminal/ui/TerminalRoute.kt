package com.deepstudent.features.terminal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.deepstudent.features.terminal.api.TerminalCommands
import com.deepstudent.features.terminal.api.TerminalQuery

@Composable
fun TerminalRoute(
    query: TerminalQuery,
    commands: TerminalCommands,
    onClosePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val presenter = remember(query, commands, scope) {
        TerminalPresenter(query, commands, scope)
    }
    DisposableEffect(presenter) {
        onDispose(presenter::stop)
    }
    val state by presenter.state.collectAsState()
    TerminalScreen(state, presenter.actions, onClosePage, modifier)
}
