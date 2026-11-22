package com.deepstudent.features.environments.ui

import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage

private val phaseLabels = mapOf(
    EnvironmentPhase.IDLE to "等待安装",
    EnvironmentPhase.VERIFYING to "校验镜像",
    EnvironmentPhase.EXTRACTING to "解包系统",
    EnvironmentPhase.HEALTH_CHECKING to "检查系统",
    EnvironmentPhase.ACTIVATING to "激活环境",
    EnvironmentPhase.READY to "环境可用",
    EnvironmentPhase.INSTALLING_PACKAGE to "安装项目依赖",
    EnvironmentPhase.FAILED to "安装失败",
    EnvironmentPhase.CANCELLED to "已取消",
)

internal fun phaseLabel(phase: EnvironmentPhase): String = phaseLabels.getValue(phase)

private val progressLabels = mapOf(
    EnvironmentStage.ARCHIVE_VERIFY to "校验冻结镜像哈希",
    EnvironmentStage.ARCHIVE_COPY to "下载或复制镜像到私有目录",
    EnvironmentStage.HEALTH_CHECK to "启动 PRoot 并检查 Ubuntu",
    EnvironmentStage.ACTIVATE to "原子激活 rootfs",
    EnvironmentStage.APT to "通过 apt 安装依赖",
    EnvironmentStage.VENV to "创建项目 Python venv",
    EnvironmentStage.PROBE to "探测已安装版本",
    EnvironmentStage.COMPLETE to "安装完成",
    EnvironmentStage.IDLE to "",
)

internal fun progressLabel(progress: EnvironmentProgress): String =
    if (progress.stage == EnvironmentStage.EXTRACT) {
        "安全解包 ${progress.completedEntries}/${progress.totalEntries}"
    } else {
        progressLabels.getValue(progress.stage)
    }
