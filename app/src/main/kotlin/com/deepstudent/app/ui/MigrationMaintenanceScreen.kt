package com.deepstudent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.deepstudent.platform.database.MigrationStage
import com.deepstudent.platform.database.MigrationStatus

/** Renders only the database module's structured diagnostic; it never accesses a database. */
@Composable
internal fun MigrationMaintenanceScreen(status: MigrationStatus, retrying: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("migration-maintenance"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("数据库维护", style = MaterialTheme.typography.headlineSmall)
        Text("数据库升级未完成。已提交的迁移不会重复执行，请检查诊断后重试。")
        Text("数据库：${status.database.id}")
        Text("已提交版本：${status.committedVersion?.toString() ?: "未知"}")
        Text("目标版本：${status.targetVersion?.toString() ?: "未知"}")
        Text("失败阶段：${status.stage.label()} (${status.stage.name})")
        status.failure?.let { failure ->
            if (failure.database != status.database) Text("异常归属：${failure.database.id}")
            failure.version?.let { Text("失败迁移版本：$it") }
            Text("诊断：${failure.diagnostic.name} — ${failure.diagnostic.fixedMessage}")
            if (failure.objectType != null && failure.objectName != null) {
                Text("对象：${failure.objectType} / ${failure.objectName}")
            }
        }
        Text("重试只会继续验证并执行尚未提交的迁移，不会清除或跳过数据。")
        Button(onClick = onRetry, enabled = !retrying, modifier = Modifier.fillMaxWidth().testTag("migration-retry")) {
            Text(if (retrying) "正在验证并续作…" else "重试迁移")
        }
    }
}

private fun MigrationStage.label(): String = when (this) {
    MigrationStage.REGISTRY -> "连接注册"
    MigrationStage.SOURCE -> "迁移来源校验"
    MigrationStage.OPEN -> "打开数据库"
    MigrationStage.INSPECT -> "历史和结构校验"
    MigrationStage.MIGRATION -> "提交迁移"
    MigrationStage.GOVERNANCE -> "治理记录"
    MigrationStage.WAL -> "启用日志模式"
    MigrationStage.READY -> "已就绪"
}
