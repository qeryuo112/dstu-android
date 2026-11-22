package com.deepstudent.features.terminal.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction

@Composable
internal fun TerminalInput(
    enabled: Boolean,
    onInput: (String) -> Unit,
    onInterrupt: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val history = remember { mutableListOf<String>() }
    var position by remember { mutableStateOf(0) }
    val send = {
        onInput("$input\n")
        if (input.isNotBlank()) {
            history.add(input)
            if (history.size > 100) history.removeAt(0)
        }
        position = history.size
        input = ""
    }
    Row(Modifier.fillMaxWidth()) {
        TerminalIconButton("中断", onClick = onInterrupt, enabled = enabled) {
            Icon(Icons.Default.PanTool, contentDescription = null)
        }
        TerminalIconButton(
            "上一条",
            onClick = {
                if (position > 0) {
                    position--
                    input = history[position]
                }
            },
            enabled = enabled && position > 0,
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
        }
        TerminalIconButton(
            "下一条",
            onClick = {
                position = (position + 1).coerceAtMost(history.size)
                input = history.getOrElse(position) { "" }
            },
            enabled = enabled && position < history.size,
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f).testTag("terminal-input"),
            enabled = enabled,
            singleLine = true,
            label = { Text("输入") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        TerminalIconButton("发送", onClick = send, enabled = enabled) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
        }
    }
}
