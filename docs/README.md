# 文档入口

## 当前执行

- [原生分层与模块化执行计划](plans/2026-11-16-native-modular-execution-plan.md)：S1 起的有效工程组织、模块依赖、分层交付和架构验收计划；覆盖当前计划 S1 至 S9 的 280 个单元及 9 个出口；新增 S2.16–S2.18 兼容补修，原 S2.16–S2.28 顺延为 S2.19–S2.31。
- [S2 交接](handoff-s2.md)：承接 S0 与 S1 已签收基线，最新进度见末尾追加记录及 [S2 回执](../config/stages/s2-progress.json)。
- [测试局限与后续建议](test-limitations.md)：区分已验证、未实测和环境阻塞，记录补测建议与证据入口。
- [项目规则](rules/rules.txt)：开发前必读，仅由用户修改或按用户明确要求修改；总目标为完整冻结桌面功能的 Android 原生端，新单元须先读桌面与 Operit 冻结相关源码。

## 历史交接

- [S0 交接归档](archive/handoff-s0.md)：S0 冻结基线与 [S0.G / M0 签收](../config/s0-g-main-review.json)。
- [S1 交接归档](archive/handoff-s1.md)：S1.01–S1.27 的实现与验收记录，以及独立的 [S1.G / M1 签收](../config/stages/s1-progress.json)。

两份归档按原字节保留。S1 正文中的旧相对链接仍按原位置记录，现行导航以本页和 S2 交接为准。

## 冻结依据

- [原计划（当前功能/依赖正文）](plans/2026-11-15-mobile-full-feature-refactor.md)：按用户 2026-11-23 授权在原路径直接修订，当前为 305 主单元、10 出口、315 队列位置。S0.G 绑定的旧字节与行号从 Git `36511a1ba27ef2db7c45636162575a2b1c6564e8` 读取并验证，不重新签收旧基线。
- `config/execution-plan.json`：保留 302 主单元、10 出口、1774 卡、3198 场景的历史队列原字节；当前编号通过活动计划修订映射查询旧卡，不把旧 S2.16 的 runtime 内容误当成新 S2.16。M0 最终状态仍以 `config/s0-g-main-review.json` 的 accepted 回执为准。
- [活动计划登记](../config/planning/active-plan.json)：登记有效计划、当前原计划修订与编号映射、冻结引用、历史归档和当前交接；当前 next 见 S2 回执，不修改旧 S0 合同或签收文件。

不跟进桌面上游，不采用其安卓运行链，也不改变已冻结schema/wire/fixture。文档一致性检查为`node tools/planning/verify-plan.mjs`；当前编号/前置及历史卡映射用`node tools/planning/verify-plan.mjs --query S2.16`查询；Android真实架构/复杂度门禁已在S1.01实现，入口为`node tools/s1/gradle.mjs verifyArchitecture verifyComplexity`。`npm run verify`回放只读S0快照，`npm run verify:s1`检查当前工程，设备测试单独执行。各单元最终实现、命令及边界记录在其阶段交接中，子代理准备文档按项目规则清理。

## 工程治理（2026-11-23）

- [CI、质量门禁和签名发布](ci.md)：检查范围、模块校验、覆盖率与 GitHub 环境配置。
- [证据归档与索引](evidence/README.md)：原始日志退出 Git 后的校验、按需重放与长期存储。

- [公开源码与私有历史](publication.md)：独立公开目录、日志/截图排除及冻结输入兼容校验。
