package com.deepstudent.features.environments.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.PackageRecipeState
import com.deepstudent.features.environments.api.PackageRecipeStatus

@Composable
fun EnvironmentsScreen(
    state: EnvironmentUiState,
    actions: EnvironmentActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag("environments-screen"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EnvironmentHeader(state, actions)
            EnvironmentStatus(state, actions)
            EnvironmentProgressRow(state.snapshot.progress)
            HorizontalDivider()
            PackageSection(state, actions)
            state.failure?.let { FailureRow(it, if (state.actionFailure != null) actions.dismissFailure else null) }
        }
    }
}

@Composable
private fun EnvironmentHeader(state: EnvironmentUiState, actions: EnvironmentActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.SystemUpdateAlt,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("环境安装", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Ubuntu 24.04 / arm64",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = actions.close) {
            Icon(Icons.Default.Close, contentDescription = "关闭环境安装")
        }
    }
}

@Composable
private fun EnvironmentStatus(state: EnvironmentUiState, actions: EnvironmentActions) {
    val snapshot = state.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                phaseLabel(snapshot.phase),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (snapshot.activeGeneration != null) {
                AssistChip(
                    onClick = {},
                    label = { Text("已激活") },
                    leadingIcon = {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.size(16.dp))
                    },
                )
            }
        }
        Text(
            snapshot.rootfsSha256?.let { "SHA-256 ${it.take(16)}..." }
                ?: "尚未安装系统镜像",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnvironmentArchiveActions(state, actions)
    }
}

@Composable
private fun EnvironmentArchiveActions(state: EnvironmentUiState, actions: EnvironmentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = actions.downloadRootfs,
            enabled = !state.busy && state.downloadAvailable,
            modifier = Modifier.fillMaxWidth().testTag("environment-download"),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("下载 Ubuntu 镜像")
        }
        OutlinedButton(
            onClick = actions.installRootfs,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("environment-import"),
        ) {
            Text("从本地导入镜像")
        }
        if (state.snapshot.failure != null && state.snapshot.recoverable) {
            Text(
                "可继续当前任务，或自行选择下载或导入本地镜像。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.snapshot.recoverable || state.actionFailure?.recoverable == true) {
                OutlinedButton(onClick = actions.retry, enabled = !state.busy, modifier = Modifier.testTag("environment-retry")) {
                    Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("继续")
                }
            }
            if (state.busy) {
                OutlinedButton(onClick = actions.cancel, modifier = Modifier.testTag("environment-cancel")) {
                    Icon(Icons.Default.Cancel, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun EnvironmentProgressRow(progress: EnvironmentProgress) {
    if (progress.stage == com.deepstudent.features.environments.api.EnvironmentStage.IDLE) return
    val fraction = progressFraction(progress)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            progressLabel(progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PackageSection(state: EnvironmentUiState, actions: EnvironmentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("项目依赖", style = MaterialTheme.typography.titleMedium)
        Text(
            "安装完成后探测版本并记录回执。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.snapshot.recipes.forEach { recipe ->
            PackageRow(recipe, !state.busy && state.snapshot.phase == com.deepstudent.features.environments.api.EnvironmentPhase.READY, actions)
        }
    }
}

@Composable
private fun PackageRow(
    recipe: PackageRecipeState,
    enabled: Boolean,
    actions: EnvironmentActions,
) {
    Row(
        Modifier.fillMaxWidth().testTag("recipe-${recipe.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            packageIcon(recipe.status),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(recipe.title, style = MaterialTheme.typography.bodyLarge)
            val versions = recipe.versions.joinToString(" · ") { "${it.name} ${it.version}" }
            Text(
                versions.ifEmpty { statusLabel(recipe.status) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = { actions.installRecipe(recipe.id) },
            enabled = enabled && recipe.status != PackageRecipeStatus.INSTALLING,
            modifier = Modifier.testTag("install-${recipe.id}"),
        ) {
            Text(if (recipe.status == PackageRecipeStatus.INSTALLED) "重新安装并探测" else "安装")
        }
    }
}

@Composable
private fun FailureRow(failure: EnvironmentFailure, dismiss: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().testTag("environment-failure"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            failure.message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (dismiss != null) TextButton(onClick = dismiss) { Text("知道了") }
    }
}

private fun packageIcon(status: PackageRecipeStatus) = when (status) {
    PackageRecipeStatus.INSTALLED -> Icons.Default.CheckCircle
    PackageRecipeStatus.FAILED -> Icons.Default.Error
    PackageRecipeStatus.INSTALLING -> Icons.Default.Download
    else -> Icons.Default.Download
}

private fun statusLabel(status: PackageRecipeStatus): String = when (status) {
    PackageRecipeStatus.NOT_INSTALLED -> "未安装"
    PackageRecipeStatus.READY_TO_INSTALL -> "可安装"
    PackageRecipeStatus.INSTALLING -> "安装中"
    PackageRecipeStatus.INSTALLED -> "已安装"
    PackageRecipeStatus.FAILED -> "安装失败"
}

private fun progressFraction(progress: EnvironmentProgress): Float? {
    val total = if (progress.totalBytes > 0) progress.totalBytes else progress.totalEntries.toLong()
    val done = if (progress.totalBytes > 0) progress.processedBytes else progress.completedEntries.toLong()
    if (total <= 0) return null
    return (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

