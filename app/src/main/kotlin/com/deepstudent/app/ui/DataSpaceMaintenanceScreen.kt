package com.deepstudent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.deepstudent.platform.storage.DataSpaceError

@Composable
internal fun DataSpaceMaintenanceScreen(error: DataSpaceError, retry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("dataspace-maintenance"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("存储空间暂不可用", style = MaterialTheme.typography.headlineSmall)
        Text(if (error == DataSpaceError.BUSY) "另一项操作正在使用存储空间，请稍后重试。"
            else "应用无法确认应打开哪份数据，未清除原数据。请保留应用数据以便诊断。")
        Text("诊断代码：${error.name}")
        Button(onClick = retry, modifier = Modifier.testTag("dataspace-recheck")) { Text("重新检查") }
    }
}
