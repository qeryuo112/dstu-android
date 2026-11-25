# dstu-android 开工阅读清单

这里集中登记开工要读的路径，不另立项目规则。主线程和每位专属子智能体均按以下顺序执行。开发、续作、排查、测试、打包、文档维护或清理工作区，都从第一批开始。

## 第一批：只读项目红线

- **唯一文件：[docs/rules/rules.txt](docs/rules/rules.txt)**。在外层工作区可直接读 `dstu-android/docs/rules/rules.txt`，本机绝对路径是 `/home/xutao/deepstudent/dstu-android/docs/rules/rules.txt`，不必先查路径。
- 第一项且唯一的操作是单独完整读取该文件。读完前禁止做任何其他事：不发进度说明，不列目录或搜索，不查 Git，不建待办或启动子智能体，不运行命令，不改文件，不与其他工具并行。分页时只续读同一文件；无法完整读取就停止并告知用户，不用摘要或记忆代替。
- `rules.txt` 是项目红线。不得违反其中规则

## 第二批：核对计划、交接和前置

按本轮范围读取以下资料。所有路径均相对 `dstu-android` 仓库根目录。

1. [config/planning/active-plan.json](config/planning/active-plan.json)：确认生效计划、现行编号与历史编号映射、当前交接路径。阶段切换后以登记为准，不继续使用旧一阶段的入口。
2. [docs/plans/2026-11-16-native-modular-execution-plan.md](docs/plans/2026-11-16-native-modular-execution-plan.md)：读 §4.1、§4.2 和本单元所在段；[docs/plans/2026-11-15-mobile-full-feature-refactor.md](docs/plans/2026-11-15-mobile-full-feature-refactor.md) 核对交付物、前置和原始验收条件；[config/execution-plan.json](config/execution-plan.json) 查冻结历史卡与场景，不用旧同名单元代替现行单元。
3. [S3 交接](docs/handoff-s3.md)：读开工检查、最新记录及本单元和前置的证据。S3 的入口 `S3.01` 由 [S2 回执](config/stages/s2-progress.json)的 `next` 和阶段出口确认；S2 的[核验教训](docs/archive/handoff-s2.md#s2-verification-lessons)与[后续验收](docs/archive/handoff-s2.md#s2-follow-up-acceptance)见归档。涉及早期前置时查 [S1 回执](config/stages/s1-progress.json)、[S0.G 主审回执](config/s0-g-main-review.json)，详见 [S1 归档](docs/archive/handoff-s1.md)和 [S0 归档](docs/archive/handoff-s0.md)。以回执和适用证据为准，不用「已实现」「待复验」代替签收。
4. [config/source-lock.json](config/source-lock.json)：确定只读参考仓库、冻结提交和 terminal 子模块。涉及共享 schema 时查 [config/schema/final-lock.json](config/schema/final-lock.json) 及适用的补充锁；涉及同步、资产、备份等合同，从计划定位对应的 `config/contracts/`、冻结输入与夹具。
5. 按任务补读：[docs/README.md](docs/README.md) 是资料导航；构建和打包查 [README.md](README.md) 及 [docs/ci.md](docs/ci.md)；核验证据、整理日志查 [docs/evidence/README.md](docs/evidence/README.md)；判断覆盖范围查 [docs/test-limitations.md](docs/test-limitations.md)；仅本轮允许真机时，再读 [docs/device-acceptance-prerequisites.md](docs/device-acceptance-prerequisites.md)。公开源码或发布另查 [docs/publication.md](docs/publication.md)，读到说明不等于获准发布。

读完规则后，才可用 `node tools/planning/verify-plan.mjs --query <现行单元 ID>` 查当前卡、前置和历史映射，并核对与本轮有关的验收证据。前置未签收时仅做规则允许的隔离准备，不接正式调用链、不推进签收。

## 第三批：新单元或新功能的冻结源码

- 主线程和该单元专属子智能体都须按 `config/source-lock.json` 核对 `/home/xutao/deepstudent/dstu-node`、`/home/xutao/deepstudent/Operit`；涉及终端时再核对 `/home/xutao/deepstudent/Operit/terminal`。只读冻结修订，不追新上游。进入具体目录时，按适用范围查本地 `AGENTS.md`（如有），不改变首读规则。
- 双方各自读与本单元对应的实现文件、调用链及测试。开工记录追加到当前交接，写明具体路径、关键合同、复用方式和差异；没有对应实现时记录实际搜索范围和最接近的组件。不以交接摘要代替读源码，不用 Operit 定义 dstu 多端同步协议。
- 主线程核对前置与记录后，向用户简要说明本轮范围和准备做的验证，再开始改动。未授权的真机、发布或其他额外操作不执行。
