# Android 证据日志归档与索引

本目录是 `dstu-android` 原始证据日志的唯一 Git 入口。原始文件不再进入 Git 树；Git 只保留可携带的索引和本说明。

## 归档结果

- 生成时间：`2026-11-23T03:09:17+08:00`（UTC `2026-11-22T19:09:17.224Z`）
- 源提交：`dbf8b6c04686e7727ac0ac7e9ca18bd8840729ac`
- 归档文件：`/home/xutao/deepstudent/artifacts/android-evidence/logs-20261122T190917Z/evidence.tar.gz`
- 归档格式：`tar.gz`
- 归档大小：`4683914` 字节
- 归档 SHA-256：`e456a9389d5d24503429e2ceb8fe233de162fac9a05363f510724d3cdf889071`
- 外部清单：`/home/xutao/deepstudent/artifacts/android-evidence/logs-20261122T190917Z/manifest.json`
- 外部清单 SHA-256：`859571a578e3ae88ed8c4dd1d57d2391bd5db3a120387e2386e70b0d529025bd`
- Git 索引：`docs/evidence/logs-index.json`

审计口径：HEAD 中 `logs/` 共 652 个文件（619 个 `.log`、24 个 `.png`、9 个 `.json`），总原始大小 19,791,692 字节。归档时工作区现存 631 个；其余 21 个 PNG 从 Git blob 导入归档，没有恢复工作区。索引中每条记录保留原路径、SHA-256、大小、存储归档名和归档时的 `present`/`missing` 状态。

## 为什么这样做

`docs/rules/rules.txt` 第 5 条要求工作区无中间产物，最终调试日志和文档可保留。原始日志作为证据有价值，但不适合作为源码长期进入 Git：它们包含大量重复设备输出，会持续膨胀仓库。现在原始证据放在仓库外的 artifact 存储，Git 只提交索引。

历史 Git 对象不会因为本次移出工作区或解除跟踪而自动消失。当前历史大小仍需保留；要缩小 `.git` 必须重写历史并强推，本单元没有做，也不会做。归档前后 `git count-objects -vH` 均为：`count: 5658`、`size: 28.27 MiB`、`in-pack: 5096`、`size-pack: 9.26 MiB`。

## 校验

```bash
node tools/evidence/verify.mjs
```

该命令会先核对归档大小和 SHA-256，再检查 tar 条目集合、文件类型、每个条目的路径安全、大小和 SHA-256。外部目录也可直接校验：

```bash
cd /home/xutao/deepstudent/artifacts/android-evidence/logs-20261122T190917Z
sha256sum -c SHA256SUMS
```

## 按需还原

还原单个文件到工作区原路径：

```bash
node tools/evidence/restore.mjs logs/s1-01-bootstrap-device.png
```

还原全部 652 个文件到工作区的 `logs/`（该目录已被忽略；这也会重新生成 21 个当前缺失的 PNG，若只想取单个证据请使用上面的单文件命令）：

```bash
node tools/evidence/restore.mjs --all
```

还原到临时目录，不污染工作区：

```bash
node tools/evidence/restore.mjs --output /tmp/dstu-evidence logs/s1-g-main-phone.log
```

还原是幂等的：目标文件大小和 SHA-256 与索引一致时跳过；不一致时默认拒绝，只有显式加 `--force` 才替换。归档路径默认取索引中的绝对位置，也可用 `--archive` 或 `--artifact-root` 指向迁移后的存储。

## 归档新证据

```bash
node tools/evidence/archive.mjs
```

默认合并 `HEAD` 中的 `logs/` 与工作区 `logs/`：HEAD 中已删除的文件从 Git blob 导入，工作区中新产生的本地文件也会纳入。迁移完成后如果只想归档新本地日志，使用 `--source worktree`；只想复核历史 HEAD 时用 `--source head`。归档写入 `/home/xutao/deepstudent/artifacts/android-evidence/logs-<UTC>/`，并生成 `manifest.json`、`SHA256SUMS`、`verification.json` 和 Git 索引。默认索引是 `docs/evidence/logs-index.json`；如果它已存在，新归档会写到 `docs/evidence/logs-index-<UTC>.json`，避免覆盖历史索引。归档先写入临时目录，校验通过后才原子改名。只有显式加 `--prune-worktree` 才会在归档和校验成功后删除工作区中与归档一致的文件；它不会删除未归档的本地日志，也不会执行 `git rm --cached`。

## CI 门禁

主线程应把以下命令加入 CI（建议与 Node 测试同一个 job）：

```bash
node --test tools/evidence/evidence-store.test.mjs && node tools/evidence/check-logs.mjs
```

`check-logs.mjs` 会拒绝任何仍被 Git 跟踪的 `logs/**` 文件，要求 `logs/` 已被忽略，并校验 `docs/evidence/logs-index*.json` 的格式、路径安全、SHA-256 和大小字段。CI 不需要外部归档即可通过；外部归档只在本地/release 验证或按需还原时使用。

## 旧引用与工具兼容

历史文档和配置仍保留原 `logs/...` 路径，这是有意的：索引条目就是这些路径的查找键。涉及文件包括 `docs/archive/handoff-s0.md`、`docs/archive/handoff-s1.md`、`docs/handoff-s2.md`、`config/stages/s1-progress.json`、`config/stages/s2-progress.json`、`config/s0-gate.json`、`config/schema/final-lock.json`。不要为了“让链接看起来存在”重新提交原始日志；需要查看时按索引还原。

已确认的真实读取依赖及影响：

| 文件 | 行为 | 迁移后影响与建议 |
| --- | --- | --- |
| `tools/s1/progress.mjs` | 通过 `hasStageEvidenceFile`/`readCandidateManifest` 读取 `logs/s1-*` | `npm run verify:s1` 在未还原时会失败。主线程可先 `restore --all`，或改为索引感知读取。 |
| `tools/baseline/stage-gate.mjs` | 读取 `config/s0-gate.json` 的 `unitEvidence` 日志并校验事件/哈希 | `npm run verify:stage-gate`、`npm run verify` 需要先还原，或改为从归档临时目录读取。 |
| `tools/s1/frozen-inputs.mjs` | `frozenInputScopes` 含 `logs`，`EXPECTED_FROZEN_INPUTS = 535`，读取 live logs 与 S0_REF 对比 | 删除 logs 后 `npm run verify` 会失败。S0_REF 中 logs 有 249 个；更合理的修补是从 scopes 移除 `logs` 并把期望数改为 286，同时把 `frozen-inputs.test.mjs` 的 scope 数从 7 改为 6。 |
| `tools/s1/benchmark-provenance.mjs` | 明确排除 `logs` 参与源码指纹 | 无需修改。 |
| `tools/baseline/run-logged.mjs`、`tools/baseline/upstream-audit.mjs`、`tools/s1/logged.mjs`、`tools/s1/device-smoke.mjs` | 只写本地 logs，且会自行创建目录 | `.gitignore` 已保证继续写不会进入 Git，无需改调用。 |
| `tools/s1/pull-visual.mjs`、`tools/s1/visual-compare.mjs` | 直接写 `logs/`，没有 `mkdirSync` | 当前工作区保留空 `logs/` 目录；新 clone 需主线程补 `mkdir -p logs` 或给这两个工具加目录创建。 |
| `tools/s1/progress.test.mjs` | 在临时目录创建 fixture logs | 无需修改。 |
| `tools/s2/verify-*-device.sh` | 输出/打印设备日志，不读取仓库 logs | 无需修改。 |

主线程负责迁移这些调用和 CI 配置；本单元没有修改既有 tools、package、`.github` 或规则文档。

## 冻结版本核查

- `dstu-node` 冻结于 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87`。其 `.gitignore` 已忽略 `logs/`、`*.log`、`**/logs/`；检索 `tools`、`scripts`、`package.json` 和 `.gitignore` 未发现证据归档/索引实现。最接近的只有“忽略日志”。
- `Operit` 冻结于 `7a39941bad80209973c535046a18195c5da9e647`。检索 `.gitignore`、`tools`、`.github` 未发现对等的证据归档/索引组件。
- 因此本单元没有可复用的冻结参考实现，归档格式、索引 schema、校验和还原流程为本仓库新增。

## 历史脚本兼容

`npm run verify:s1`、`npm run verify:progress`、`npm run verify:stage-gate` 通过
`tools/evidence/replay.mjs` 临时还原缺失证据，验证结束后只清除本次还原且内容未变化的文件；
已有本地日志或验证期间产生的新内容不被覆盖或删除。直接运行历史底层脚本时可先用同一 wrapper。
`npm run verify` 的 S0 隔离快照本身仍从历史 Git 提交读取证据；当前冻结校验改为 286 个源码/合同输入，
并另对 249 个历史 S0 日志的索引 SHA-256/大小逐项与冻结 Git blob 比较，未撤掉原有字节约束。

复制归档到另一台机器后设置 `DSTU_EVIDENCE_ROOT` 为 artifact 根目录；其下目录结构保持索引的
`relativeLocation` 不变。日常 PR CI 不下载历史真机证据，只校验索引并产生该次 CI 的新 artifact。

## 本轮审计验收输出

`logs-index-engineering-audit-20261123.json` 索引本轮21份最终测试/构建证据，包括API26/36真实模拟器结果、
单测分片、质量门禁、失败路径回归和一次性密钥签名验证。可通过同一verify/restore CLI传入`--index`读取。
该归档是工程审计证据，不推进既有业务阶段验收；公开CI报告与正式签名发布仍需GitHub远端配置。

## 公开范围

按用户2026-11-23要求，旧日志、截图和归档均不公开；公开仓库只保留索引。
公开Git对象库为独立的源码快照，不继承这些文件曾经所在的私有提交。
公开索引不包含可公开下载归档的承诺；`PRIVATE_ARTIFACT_ROOT`由证据持有者通过`DSTU_EVIDENCE_ROOT`自行指定。
