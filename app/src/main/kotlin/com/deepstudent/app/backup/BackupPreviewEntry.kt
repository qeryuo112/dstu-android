package com.deepstudent.app.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepstudent.app.DstuApplication
import com.deepstudent.services.backup.api.CoverageStatus
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.api.DomainPreview
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.api.SnapshotKind

@Composable
internal fun BackupPreviewEntry() {
    val context = LocalContext.current.applicationContext
    val app = DstuApplication.from(context)
    val restore = app.backupRestore
    val encrypted: EncryptedBackupModel = viewModel(key = "backup-encrypted") { EncryptedBackupModel(app.encryptedBackups) }
    val status by restore.status.collectAsState()
    val continuity: ContinuityRestoreModel = viewModel(key = "backup-continuity") { ContinuityRestoreModel(app.continuityBackups) }
    var selected by rememberSaveable { mutableStateOf(BackupTab.PLAIN) }
    Column {
        Row {
            BackupTab.entries.forEach { tab ->
                TextButton(onClick = { selected = tab }, enabled = tab != selected) { Text(tab.label) }
            }
        }
        when (selected) {
            BackupTab.PLAIN -> PlainBackupEntry(restore)
            BackupTab.ENCRYPTED -> EncryptedBackupEntry(encrypted, status)
            BackupTab.CONTINUITY -> ContinuityRestoreScreen(continuity, status)
        }
    }
}

private enum class BackupTab(val label: String) { PLAIN("普通备份"), ENCRYPTED("加密备份"), CONTINUITY("连续迁移") }

@Composable
private fun PlainBackupEntry(restore: BackupRestoreCoordinator) {
    val context = LocalContext.current.applicationContext
    val model: BackupPreviewModel = viewModel(key = "backup-preview") { BackupPreviewModel(SafBackupPreviewReader(context), restore) }
    val state by model.state.collectAsState()
    val status by restore.status.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::open) }
    val preview = (state as? BackupPreviewState.Report)?.plan
    Column {
        BackupRestoreStatusPanel(status, preview?.canStage == true, preview?.legacyImport != null, model::requestRestore)
        BackupPreviewScreen(state, { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, model::cancel)
    }
}

@Composable
internal fun BackupPreviewScreen(state: BackupPreviewState, choose: () -> Unit, cancel: () -> Unit, showPicker: Boolean = true) {
    LazyColumn(Modifier.padding(12.dp).testTag("backup-preview"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("备份检查", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            Text("预览备份覆盖范围。检查不会更改现有数据。")
            if (showPicker) {
                Button(onClick = choose, enabled = state != BackupPreviewState.Reading, modifier = Modifier.testTag("backup-pick")) {
                    Text("选择备份 ZIP")
                }
            }
        }
        when (state) {
            BackupPreviewState.Initial -> Unit
            BackupPreviewState.Reading -> item {
                Text("正在读取清单并校验文件…")
                LinearProgressIndicator()
                Button(onClick = cancel) { Text("取消检查") }
            }
            BackupPreviewState.Unreadable -> item { Text("无法读取这个备份。请检查文件权限和剩余空间。") }
            is BackupPreviewState.Report -> {
                item { ReportSummary(state.plan) }
                if (state.plan.legacyImport != null) item { LegacyPreviewSummary(state.plan) }
                items(state.plan.domains, key = { it.id }) { DomainRow(it) }
            }
        }
    }
}

@Composable
private fun ReportSummary(plan: RestorePlan) {
    val title = when (plan.snapshotKind) {
        SnapshotKind.FULL -> "完整快照"
        SnapshotKind.PARTIAL_OVERLAY -> "部分覆盖快照（不可用于替换数据槽）"
        SnapshotKind.LEGACY_CANDIDATE, null -> "此备份不能用于替换数据槽"
    }
    Column(Modifier.testTag("backup-result")) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(if (plan.canStage) "校验通过，尚未恢复" else "当前不能进入恢复")
        plan.requiredBytes?.let { Text("预计需要空间：$it 字节") }
        val diagnostics = plan.problems.map { it.code.name }.distinct().joinToString("、")
        if (diagnostics.isNotBlank()) Text("诊断：$diagnostics")
    }
}

@Composable
private fun LegacyPreviewSummary(plan: RestorePlan) {
    val legacy = checkNotNull(plan.legacyImport)
    val dbCount = plan.domains.count { it.id.startsWith("database:") && it.action == DomainAction.STAGE_IN_SLOT }
    val blobs = plan.domains.firstOrNull { it.id == "asset-root:vfs_blobs" }?.fileCount ?: 0L
    val archiveAudit = plan.domains.any { it.id == "audit" && it.action == DomainAction.PRESERVE_INACTIVE }
    Column(Modifier.testTag("legacy-import-report")) {
        Text("旧移动备份导入报告", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text("来源：旧移动端（备份声明）")
        Text("应用 ${legacy.appVersion} · 清单 ${legacy.manifestVersion}")
        legacy.schemaVersions.toSortedMap().forEach { (id, version) -> Text("$id：$version") }
        Text("备份包含 $dbCount 个数据库、$blobs 个附件内容文件。")
        if (archiveAudit) Text("旧审计只存档，不替换本机审计。")
        Text("下次冷启动时才核对数据库和附件引用；通过后才切换数据。此次不接管同步身份。")
    }
}

@Composable
private fun DomainRow(domain: DomainPreview) {
    val action = when (domain.action) {
        DomainAction.STAGE_IN_SLOT -> "可在准备恢复时写入暂存槽"
        DomainAction.PRESERVE_INACTIVE -> "仅随备份存档，不启用"
        DomainAction.RETAIN_IN_SOURCE -> "保留在原备份；不导入或启用"
        DomainAction.EXPLICIT_TRUST_REQUIRED -> "需要单独确认信任"
        DomainAction.NO_ANDROID_TARGET -> "当前 Android 无恢复目标"
        DomainAction.NO_CONTENT -> "无备份内容"
        DomainAction.EXCLUDED -> "未覆盖"
        DomainAction.FAILED -> "备份失败"
    }
    Column(Modifier.testTag("backup-domain-${domain.id}")) {
        Text(domain.id, style = MaterialTheme.typography.titleSmall)
        Text("${domain.status.label()} · $action · ${domain.fileCount} 个文件 · ${domain.totalSize} 字节")
    }
}

private fun CoverageStatus.label(): String = when (this) {
    CoverageStatus.COMPLETE -> "完整"
    CoverageStatus.EMPTY -> "空域"
    CoverageStatus.ABSENT -> "未包含"
    CoverageStatus.EXCLUDED -> "已排除"
    CoverageStatus.FAILED -> "失败"
}
