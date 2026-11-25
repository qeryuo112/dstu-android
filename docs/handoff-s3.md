# S3 交接：对话、模型与 Agent

## 2026-11-25T16:44:36+08:00 - S3 开工基线（尚未开工）

- **当前状态**：S0.G / M0、S1.G / M1 与 S2.G / M2 已签收；S2.01–S2.31 均为 `accepted`。S2.G 的签收时间为 `2026-11-25T16:24:59+08:00`，以 [S2 回执](../config/stages/s2-progress.json)和 [阶段证据索引](evidence/logs-index-s2-g-20261125.json)为准。本轮只治理文档和整理工作区，未实施、测试或签收 S3 单元。
- **下一单元**：`S3.01` 定义聊天模型、仓储与 `TurnSnapshot`。按 [活动计划登记](../config/planning/active-plan.json)查询当前卡：`node tools/planning/verify-plan.mjs --query S3.01`。原计划明示前置为 `S1.03`、`S2.02`、`S2.30`；当前串行队列另继承 `S2.31` 和 `S2.G`。
- **S3.01 交付与验收入口**：映射 Session / Message / Block / Variant，复用 S1 的最小聊天实现，交付 Kotlin 仓储与不可变回合快照。用桌面样本逐字段核对持久化和重启读回、保留未知字段、同输入快照稳定，正式流管线使用同一仓储。以[功能计划 S3](plans/2026-11-15-mobile-full-feature-refactor.md#s3-对话模型与agent29个单元)和[模块化执行计划](plans/2026-11-16-native-modular-execution-plan.md)的单元定义为准，不在本轮预填开发或通过记录。

## S3 开工检查

1. 按 [开工阅读清单](../AGENTS.md)执行唯一首读及前置核验；集成和签收仍遵守 [S2 的核验教训](archive/handoff-s2.md#s2-verification-lessons)。
2. 核对 [来源锁](../config/source-lock.json)和 [共享 schema 最终锁](../config/schema/final-lock.json)。正式开工时，主线程与 S3.01 专属子智能体各自阅读两侧冻结源码的相关实现、调用链和测试，再在本页追加具体路径、合同、复用方式与平台差异；本页不替代源码学习。
3. 把 S2 的[后续验收项与环境状态](archive/handoff-s2.md#s2-follow-up-acceptance)留在适用阶段，不视为已经验证，也不另加 S3.01 前置。S3 新发现的覆盖缺口记入[测试局限](test-limitations.md)，正式结果按准确时间追加到本页，原始证据归档到仓库外。
