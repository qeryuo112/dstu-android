# dstu-android 开发交接

## 2026-11-15T07:07:36+08:00 — S0.01

- 本轮范围：按唯一串行队列完成 S0.01–S0.08；本记录按单元追加，所有阶段清单保持来源路径、哈希和可执行验收入口。
- 规则：每轮读取 `docs/rules/rules.txt`；本轮保持 `dstu-node`、`Operit` 与 `terminal` 只读。新工程初始化本地 Git，提交作者标识为自动化执行者 `Kimi Code <kimi-code@localhost>`。
- 交付：`config/source-inputs.json`、`config/source-lock.json`、`config/sources/*.commit.json`、`tools/baseline/sources.mjs` 与公共工具。开发工具复用相邻桌面工程已安装的 TypeScript；应用 Gradle/Kotlin 工程在 S1.01 创建。
- 来源：桌面、Operit、terminal 三仓 HEAD/tree；11 个 Git 引擎的固定提交/tree、原始 commit API 证据及 SHA-256；35 个桌面对照包的精确版本、下载 URL 和 SRI。引擎固定提交通过本机 HTTP 代理取得。
- 验收：`npm run baseline:sources && npm run verify:sources` 通过；逐来源检查 HEAD、tree、已跟踪文件状态、锚点哈希、terminal gitlink 和锁文件完整性。校验输出为带时间、事件名、来源与差异位置的 JSON 日志。
- 下一执行项：S0.02，17 个官方视图、主导航及 Compose 路由映射。

## 2026-11-15T07:09:21+08:00 — S0.02

- 交付：`config/baseline/navigation.json` 与 `tools/baseline/navigation.mjs`。从官方 shell AST 提取全部 17 个视图和 9 个主导航，保留原始顺序、标题 key、实际中英文标题、图标、可见表达式、Host 条件和 shell 参数；每项映射 Kotlin 模块、Compose 路由、手机/平板宿主、返回路径、交付单元与验收 ID。
- 验收：`npm run baseline:navigation && npm run verify:navigation` 通过；视图/导航总数、ID 唯一性、标题解析与所有导航目标关联通过。
- 下一执行项：S0.03，菜单、命令、外部文件与低频/开发入口。

## 2026-11-15T07:11:46+08:00 — S0.03

- 交付：`config/baseline/entries.json` 与 `tools/baseline/entries.mjs`。清点 96 个命令定义、57 个菜单行/行模板、45 个开发诊断面板、5 类外部入口；逐项记录启动参数、执行表达式、条件、返回路径、Android 宿主和验收场景。菜单中的动态窗口/壁纸条目以参数化行模板保留。
- 深链核对：扫描 `src`、`electron`、`mobile` 的 3283 个非测试源码，基线未注册公开应用深链；Electron 第二实例入口为唤回窗口，内部 asset/pdfstream 等协议作为资源传输登记。Android 接收文件、分享和 MCP OAuth 回调分别映射至后续正式实现单元。
- 验收：`npm run baseline:entries && npm run verify:entries` 通过；命令唯一性、清单覆盖阈值、源码哈希及可重算快照通过。
- 下一执行项：S0.04，工作台应用和资源生命周期合同。

## 2026-11-15T07:15:20+08:00 — S0.04

- 交付：`config/baseline/workbench.json` 与 `tools/baseline/workbench.mjs`。全部 22 个实际注册应用对应资源类型、single/multi 身份、激活入口、草稿、关闭、挂起、快照、Kotlin 模块及 44 条生命周期合同场景；保留 6 个桌面接口原文和源码哈希。mindmap 定义作为 notes 资源视角、desktop 作为 Agent 虚拟目标单独登记。
- 验收：`npm run baseline:workbench && npm run verify:workbench` 通过；AST 注册结果与桌面 apps golden 的 22 个 ID 集合一致。提取器支持 `as const` 常量解析。
- 下一执行项：S0.05，领域服务、IPC 命令、工具及后台任务全量清单。

## 2026-11-15T07:18:44+08:00 — S0.05

- 交付：`config/baseline/domains.json` 与 `tools/baseline/domains.mjs`。覆盖 29 个插件目录的 1752 个生产源码文件、417 个服务接口/发布点、1061 个 IPC 合同命令、915 个静态或参数化命令注册点、128 个工具执行器/注册点、222 个计时器/调度/worker 接缝。
- 每个条目关联 Kotlin 模块、后续交付单元、行为用例 ID 和源码证据。IPC 参数/result/error schema 与共享定义完整保存；执行器记录路由、敏感度和并发规则；大函数保留源码位置与 SHA-256，减少重复源码副本。既有桌面测试按领域关联，Kotlin 对拍用例沿合同落地。
- 验收：`npm run baseline:domains && npm run verify:domains` 通过；host/plugins 目录全集、生产文件索引全集、IPC lane 所有权、命令唯一性和快照重算一致。
- 下一执行项：S0.06，设置、UI slot、Dock、状态栏与选区工具条。

## 2026-11-15T07:23:54+08:00 — S0.06

- 交付：`config/baseline/contributions.json` 与 `tools/baseline/contributions.mjs`。登记 17 个官方设置区与额外 plugin-manager 区、572 处设置读写接缝、158 个设置行/分组、21 个槽声明、21 个槽值类型和 24 个直接贡献/公告接缝；Dock、状态栏、共享选区工具条另有动作/状态映射。
- 每项记录作用域、持久位置、交互状态和 Android 路由/宿主；secret 分类按桌面 `sensitive-key.ts` 的实际规则计算，portable 按既有同步白名单计算，额外跨端配置扩展由 S0.23 定稿。桌面隐藏的工作台/快捷键等设置仍进入原生同功能路线。
- 验收：`npm run baseline:contributions && npm run verify:contributions` 通过；全量重算、18 区映射及贡献面覆盖检查通过。校验日志记录实际清点数量和差异路径。
- 下一执行项：S0.07，内容方言、卡面/生成式块和无损数值/编码样本。

## 2026-11-15T07:29:47+08:00 — S0.07

- 交付：`config/content-inputs.json`、`config/baseline/content.json`、`tools/baseline/content.mjs` 与内存桌面模块装配器。记录 CommonMark/GFM、callout、toggle、columns、wiki/mention/引用、公式/图表、内联制卡、未知语法及未编辑片段保真规则。
- 可回放样本：9 份 Markdown AST/字节/哈希、9 个 Anki HTML/issue 输出、4 个生成式意图输入、18 类生成式组件各一组合法与非法 props、49 个聊天块/编辑扩展注册点；另含 14 个整数/浮点向量、4 个越界拒绝、null/省略/空串、精确 JSON token、Unicode/CRLF/UTF-8 前缀与 SHA-256 样本。
- 验收：`npm run baseline:content && npm run verify:content` 通过；真实调用桌面 parser、Anki 引擎、Zod schema、serde/int/float、UTF-8 和哈希实现。桌面代码经已安装 esbuild 在内存装配，未生成 dist、测试脚手架或临时产物。
- 下一执行项：S0.08，逐字节保存历史 SQL、refinery checksum 与 schema fingerprint。

## 2026-11-15T07:41:59+08:00 — S0.08

- 交付：`config/schema/history/` 保存全部 134 份历史 SQL 原始字节；`config/baseline/schema.json`、`tools/baseline/schema.mjs` 登记逐文件 SHA-256/refinery checksum、完整 history、表/列 cid/外键、索引/触发器原始 DDL、scope、canonical schema 与指纹。
- 数据库：vfs 72 份迁移，chat_v2 30，mistakes 23，llm_usage 8，browser 1；另登记 audit、sync_state、动态 workspace、嵌入治理表及 42 处内联 DDL/模板。7 个注册库的路径和连接 profile 与桌面实际布局对应；workspace 记录 fresh v2 与历史 v1 ALTER 后不同 cid 顺序。
- 验收：`npm run baseline:schema && npm run verify:schema` 通过。逐字节核对 134 份 SQL 与桌面 migration-lock；129 条 Rust checksum 与 4 组 Rust fingerprint/canonical 原文对拍通过；5 库空库迁移、二次运行零迁移通过；生产 MigrationCoordinator 使用新项目内保存的 SQL 完成四库新建、重启、integrity_check、foreign_key_check 和 managed fingerprint 对拍；workspace v1 真实 task_content 经 v2 迁至 initial_task；单字节 SQL 改动同时改变 SHA-256 与 refinery checksum。
- 统一入口：`npm run verify` 按 S0.01→S0.08 串行重算全部基线并检查所有 deliveryUnit 与计划编号的关联。开发工具复用相邻桌面已有依赖，未新增依赖或安装包。
- 下一执行项：S0.09，同步分类与待补齐域；S0.G 在 S0.25 后签收。Kotlin/Compose 根工程按计划于 S1.01 建立。

## 2026-11-15T07:52:46+08:00 — S0.01–S0.08 本轮最终交接

### 状态与交付

| 单元 | 状态 | 核心交付 |
| --- | --- | --- |
| S0.01 | 已完成 | 三仓、11 个 Git 引擎、35 个对照依赖包来源锁与离线校验 |
| S0.02 | 已完成 | 17 个视图、9 个导航与 Compose 路由/标题/可见条件 |
| S0.03 | 已完成 | 96 个命令、57 个菜单行模板、45 个诊断面板、5 类外部入口 |
| S0.04 | 已完成 | 22 个工作台应用、资源身份/草稿/关闭/挂起/快照合同 |
| S0.05 | 已完成 | 29 个插件目录、1061 个 IPC 合同、915 注册点、417 服务、128 工具接缝、222 调度接缝 |
| S0.06 | 已完成 | 18 个设置区、572 设置读写点、158 行/分组、21 个槽声明和类型、Dock/状态栏/选区动作 |
| S0.07 | 已完成 | 方言/卡面/生成式组件/数值/UTF-8/hash 的输入与实际桌面输出 |
| S0.08 | 已完成 | 134 份原始 SQL、五库迁移与 schema/DDL/fingerprint、七个注册库和本机库合同 |

### 最终核对

- S0.05 的混合 IPC lane 按实际命令注册 owner 分配 Kotlin 模块，浏览器、MCP 与通用设置分别归属各自模块；参数化注册保留源码/hash。
- S0.06 的设置项按组件导入图记录 ownerSections；常量按文件作用域及具名 import 解析，保留动态参数化入口。
- S0.07 已实际执行 toggle/wiki 的桌面 remark transform、math parser 与 inline-card scanner；18 类生成式组件保存 schema 源符号/定义及正常/非法 props 输出，15 个富内容语言别名全部登记。
- `config/schema/history/**` 使用 `.gitattributes` 保留 SQL 原字节，历史文件已有尾空白和 EOF 空行原样保存；源码白字节归入 hash/checksum，不做格式化。
- 最终 `npm run verify` 通过 8/8 单元，检查计划全部 302 个单元和 4956 处交付单元关联，耗时 4785 ms。完整结构化日志：`logs/s0-01-08-verification.log`。运行时新建/升级数据库位于一次性目录，结束后已删除；esbuild 只在内存生成对照模块。
- 操作入口：在项目根运行 `npm run verify`；分项入口为 `npm run verify:sources`、`verify:navigation`、`verify:entries`、`verify:workbench`、`verify:domains`、`verify:contributions`、`verify:content`、`verify:schema`。`baseline:*` 仅用于有意刷新快照。
- 本轮未创建 APK/Kotlin 业务模块；工程建立属于 S1.01。本轮的 Node 工具仅在开发机生成和校验基线，不进入安卓应用。
- 交接文档统一保留为本文件，规则与总体计划继续保留；桌面和 Operit/terminal 源仓均保持无改动。
- 已逐单元建立本地提交，最后的覆盖核对与验证日志另建本地提交；未推送远端。
- 下一步从 **S0.09** 开始。开放决策维持原计划：minSdk/ABI/版本映射在 S0.15，旧移动端真实数据出口在 S0.16，同步补齐合同在 S0.18–S0.24，S0.25 后签收 S0.G。

## 2026-11-15T09:19:54+08:00 — S0.09

- 本轮范围：按唯一串行队列执行 S0.09–S0.18。规则新增子智能体协作；主智能体审查、验收、交接与提交，每个子智能体对话不超过三轮。工作区预先存在的 `docs/rules/rules.txt` 修改保持原样，单元提交不夹带该修改。
- 交付：`tools/baseline/classification.mjs`、`config/baseline/classification.json`，以及 `baseline:classification` / `verify:classification`。统一验收按 S0.01→S0.09 执行。清点 124 表：58 RowSync、16 DerivedRebuild、31 LocalRuntime、18 BackupOnly、1 Deprecated；FileSync 注册表为 0，文件实际经过 blob/asset/workspace 通道。
- 覆盖：55 个未注册表逐项归入同步元数据、FTS 影子、迁移元数据、浏览器或本机库；共享库未注册业务表为 0。56 个运行期 DDL 表、12 个可同步设置、2 根/11 个资产目录及实际 portable 门控均有来源和重算证据。
- 双端补齐：模板和附件/Anki 媒体指向 S0.21，进度/字幕/计时 S0.22，主题/配置 S0.23，插件/技能 data S0.24；各项包含桌面捕获与后续 Kotlin 单元、资产/身份/授权边界和验收用例。
- 事实记录：note_document_revisions 的声明主键 version_id 与 SQLite seq/UNIQUE(version_id) 区别、automation_todo_deliveries 声明业务键无唯一索引、pomodoro.settings 白名单无当前生产者、插件 data helper 无当前生产调用方，均保存在基线，供后续合同单元处理。
- 验收：主智能体审查采集器与统一入口，独立执行 `npm run verify` 通过 9/9 单元；实建内存 schema、生产分类预检、触发器删除负例、设置上传过滤和资产门控全部通过。完整日志 `logs/s0-09-verification.log`。无新依赖、构建产物或临时数据库。
- 下一执行项：S0.10，实际同步协议与版本窗口。

## 2026-11-15T10:01:41+08:00 — S0.10

- 交付：`tools/baseline/protocol.mjs`、`config/baseline/protocol.json`、`baseline:protocol` / `verify:protocol`；统一入口扩展至 10 单元。AST 提取 13 个 type/interface、79 字段和 runSync 内 53 个调用点；登记 3 个 provider、4 个共享库及 9 个格式窗口样本。
- 生产链：runSync 中 format 协商→租约→schema 预检→变更采集→executePlainSync。当前 format 4/min 3，30 天兼容租约；v4 分片以 min_reader_version>=4 为开关。v5 仅独立导出，尚未注册生产命令，不纳入当前启用协议。
- 实测：两独立设备通过真实 runSync 完成 A 上传→B 下载→B 编辑上传→A 下载；本端旧 vfs schema 上传被拒绝，业务发布写入 0，pending 与原始记录保持；同一目录、同一 deviceId 执行真实迁移后发布原 pending，A 下载读到原 note_c/res_c。场景应用版本只是元数据标签，wire/manifest 不包含应用版本字段；版本矩阵按 schema/wire/能力表达。
- 审查修正：schema 门槛之前存在 format/lease 控制面写入，采集器分别记录控制面与业务发布，不再以键数量冒充零 PUT；升级用例使用原设备目录和原数据，记录迁移自带默认牌组 pending。
- 现状矩阵：旧移动平台可用 WebDAV，S3/FTP 当前拒绝；原生版本按后续 provider 单元补齐桌面能力。分类展示顺序与生产遍历顺序集合相同、顺序不同，两者按原顺序登记。
- 验收：主智能体复核修正，独立执行 `npm run verify` 通过 10/10 单元；日志 `logs/s0-10-verification.log`。临时 SQLite 目录自动删除，桌面及 Operit 源仓保持只读。
- 下一执行项：S0.11，设计令牌快照。

## 2026-11-15T10:09:24+08:00 — S0.10 读取边界复核

- 对照 `change-keys.ts` 与 `unknown-fields.ts` 修正协议说明：当前写键使用短哈希与至少 12 位 seq，读取器还接受 legacy/明文 pathId 及其现有宽松解析分支；外层未知字段和记录 data 的未知列分开，后者经 unknown-fields vault 保留回传。S0.18 以真实解析边界向量锁定这些行为。
- `npm run baseline:protocol && npm run verify:protocol` 通过，更新 `logs/s0-10-protocol-verify.log`。此修订仅更正合同说明，不改变生产调用或已验收场景。

## 2026-11-15T10:12:44+08:00 — S0.10 控制面删除记录

- 协议拒绝场景把租约清理 DELETE 明确归入 `controlPlaneDeletes`，`publicationDeletes` 仅记录业务对象删除。实际业务发布及业务删除均为 0，租约创建/提交/清理完整保留。
- 刷新并通过 `baseline:protocol` / `verify:protocol`，单元日志同步更新。

## 2026-11-15T10:16:48+08:00 — S0.09 偏好方向与引用复核

- 对照生产 `preferences-sync.ts:25–28` 更正方向说明：Bidirectional 应用并发布 winner，Upload 不应用本地 winner，Download 不发布远端对象；此前说明的“各方向均重发”有误。分类调用链中的 registry-preflight 引用同步更正为实际声明行 73。
- `baseline:classification` / `verify:classification` 通过，日志 `logs/s0-09-verification.log` 更新为本次采集与验收证据；注册数量与五类补齐任务不变。

## 2026-11-15T10:23:17+08:00 — S0.09–S0.10 复核总验收

- 新增偏好三方向真实调用：较新远端 winner 在 Upload 时仅发布、不改本地，Download 时仅改本地、不发布，Bidirectional 同时应用并发布；源 peer 对象字节保持，三个场景全部断言通过。
- `npm run verify` 通过 10/10 单元，包含本轮所有协议/分类说明修订；统一日志 `logs/s0-09-10-review.log`。跨单元核对分类四库集合、v5 未接线、原 pending 升级后发布结果一致。

## 2026-11-15T11:21:41+08:00 — S0.11

- 交付：`tools/baseline/design.mjs`、`config/design/tokens.baseline.json`、`baseline:design` / `verify:design`；统一入口改为显式单元/产物路径映射，以同一文件承载设计快照。
- 实际加载 30 个桌面 CSS 文件，使用已安装 Playwright 与独立临时 Chromium 引擎会话解析 computed style；812 个唯一令牌包含颜色通道/ARGB、长度 dp/sp、形状、字阶、动效、阴影、材质和布局上下文表达式，源码/行号/媒体条件/继承来源逐项保留。9 预设及 custom 的亮暗共 20 场景；3 个自选色×亮暗的 6 组真实算法输出一致，中性色不变；16 个领域作用域和 17 个变体覆盖笔记小字/窄栏、工作台材质、降动效/降透明/高对比等。
- 审查修正：删除 scoped 覆盖的恒真截断断言，逐项记录未捕获条件（移动 sidebar-width 与两项 Tailwind 工具变量）；删除重复 group 键，补齐领域归属。未将 var/calc 原文冒充已解析数值。
- 桌面事实：notes-hairline / notes-surface-subtle 在亮暗各依赖未定义的 surface-ink，共 4 条 unresolved；已登记声明位置和缺失依赖。媒体条件与工具内部变量单独分类。
- 验收：单元两次采集字节一致，篡改值/媒体条件可定位漂移；主智能体独立执行 `npm run verify` 通过 11/11 单元。日志 `logs/s0-11-verification.log`，详细单元日志 `logs/s0-11-design-capture.log`、`logs/s0-11-design-verify.log`。临时浏览器/探测目录已清理，未改桌面及 Operit。
- 下一执行项：S0.12，品牌、图标、术语与关键页面索引。

## 2026-11-15T12:09:48+08:00 — S0.12

- 交付：`tools/baseline/brand.mjs`、`config/design/brand.baseline.json` 与 `baseline:brand` / `verify:brand`。真实 ReactDOMServer 渲染 Mark 的 5 条 path 与 Logo 的 31 条 path，Logo 与公开 SVG 几何逐字一致；9 导航图标的 regular/bold 路径和 Compose 资源映射已登记。
- 文案：48 对语言文件、25016 叶子键、96 文件哈希，键缺失 0；17 视图、9 导航、18 设置区、39 主要动作、9 聊天术语成对且非空。92 命令使用 command_palette，另 4 自动化命令沿真实 getter 的 todo locale key 解析；7 空译文按分类词/星期前缀/joiner 记录原文与 Kotlin 资源规则。
- 页面：索引 92 张既有真实 PNG（Linux 44 / Darwin 48），逐图校验签名、尺寸、场景、亮暗与哈希；Linux notes 的 4 张缺失，保留为截图矩阵事实。13 个 Operit 移植页面映射 dstu 中英文名、路由、交付单元与 93 个亮暗 token 引用。
- 验收：来源 hash、SVG 几何、PNG 及文案解析、自检 8/8 通过；主智能体独立执行 `npm run verify` 通过 12/12 单元。完整日志 `logs/s0-12-verification.log`。本单元仅索引已有截图，未新拍或编译应用。
- 下一执行项：S0.13，Operit 采用单元与宿主接缝。

## 2026-11-15T13:06:19+08:00 — S0.10 时间精度稳定性修正

- 新的总验收发现 format.json 的租约时间可采用不同 RFC3339 小数精度，原始容器长度随之变化。比较器改为对归一化文本计算 `normalizedBytes` 与哈希，时间另按 RFC3339 UTC、1–9 位可选小数校验，不将随机值长度写成固定协议事实。
- `baseline:protocol` / `verify:protocol` 通过；本次 `npm run verify` 通过 13/13 单元，日志 `logs/s0-13-verification.log`。

## 2026-11-15T13:06:19+08:00 — S0.13

- 交付：`tools/baseline/adoption.mjs`、`config/operit-adoption.json`、`baseline:adoption` / `verify:adoption`。21 项采用单元覆盖计划 §3.1 的 12 项及 §3.2 的 9 项；13 直接移植、7 宿主适配、1 导航重写。
- 证据：122 个实际调用链步骤及 40 个补充符号逐条验证文件/行/token/hash。125 个包（app 107、terminal 18）记录采用/部分采用/替换/不采用，23 项范围外模块及 4 个单文件决策另列；13 个品牌移植页面、29 个桌面领域全部关联。
- 关键适配：真实终端内核位于 terminal 子模块；Shizuku newProcess/PFD 串行输出改并发排空；target 显式选择替代权限自动降级；pnpm 安装与探测配方统一；前台职责、通知与任务账本接入，透明 1×1 overlay 保持内测对照项。rootfs 原字节读取发行信息确认为 noble/24.04，与内置源和安装脚本一致。
- 验收：伪符号/错行/未知包/未映射包/错误链 token 五项负例通过，主智能体审查并独立执行 `npm run verify` 通过 13/13；单元日志 `logs/s0-13-adoption-capture.log`、`logs/s0-13-adoption-verify.log`。本单元登记采用接缝，未复制运行代码或编译APK。
- 下一执行项：S0.14，原生资源与许可来源。

## 2026-11-15T14:57:16+08:00 — S0.14

- 交付：`tools/baseline/assets.mjs`、`config/runtime-assets.json`、`config/runtime-notices.json`、生成的 `NOTICE` 及采集/校验入口。41 资产、85 文件、102 个受跟踪二进制逐项分类，未分类为 0；包含 PTY、执行资源、rootfs、QuickJS/WAMR/模型引擎、Shower、字体、模型 manifest、构建支持和外部下载声明。
- 原生事实：14 ELF 中 11 项 16 KiB 对齐、3 项 4 KiB（shell-exec 与两份 aapt2）；6 个归档内嵌 arm64 库及 6 个 rootfs ELF 样本通过；CMake 声明与实际二进制分开记录。rootfs 185 包、181 copyright、14 common-licenses、1 悬空版权链接，许可族已识别 174 包、10 包未映射/散文、1 包未解析。
- 来源/许可：15 个原始来源缺口、7 项未知许可、40 条后续任务如实登记；42 个 NOTICE 组件为 7 已解析、26 待补证据、9 无需分发，21 份原文许可路径/hash 独立保留。未取得的构建输出、模型与CI归档列外部项；NOTICE 当前提供原文来源引用。
- 主审修正：私钥容器 `.jks/.keystore/.p12` 等在任何读取前排除，仅登记路径/stat大小，不读取字节、不计算hash；两个受跟踪容器采用 sensitive-excluded，公开 KeyStoreHelper 源码正常采集。负例与文件系统调用跟踪确认容器读取数为 0。rootfs 只选择性读取元数据、版权及样本，临时目录结束即删。
- 验收：主智能体独立执行 `npm run verify` 通过 14/14 单元；资源缺失/hash/manifest/页对齐/版权数量/伪解析/敏感路径及 readelf 对拍均通过。总日志 `logs/s0-14-verification.log`，单元日志 `logs/s0-14-assets-capture.log`、`logs/s0-14-assets-verify.log`。桌面/Operit/terminal 无改动。
- 下一执行项：S0.15，平台与发布身份、版本单源、实包及证书对照。

## 2026-11-15T16:34:38+08:00 — S0.15

- 交付：`config/release-contract.json`、`config/baseline/platform.json`、`tools/baseline/platform.mjs`、`tools/baseline/version.mjs` 及单元入口。合同冻结 applicationId=com.deepstudent.app、minSdk=26、compile/targetSdk=36、arm64-v8a；x86_64 未纳入交付。缓存缺少的 4 类 Compose/core/activity 依赖按未观察登记，S1.01 取得实际 AAR 后对照统一 minSdk。
- 版本单源：稳定 semver 的 `major*10000000+minor*100000+patch`，minor<=99、patch<=99999、码上限2100000000；重写首版 0.21.0→2100000，高于已观察旧包1114586及旧生成器同版本1214586。0.20.x保留旧链，新链拒绝发布；版本号与schema/wire完全分开。31项用例覆盖映射、单调、非法/越界/同版退码、合同与实包。
- 实包：工作区 dstu debug APK 为0.20.0/1114586、min24/target36/compile36，证书018004…bb42，v2；旧正式证书公开指纹为3acac941…0e769，正式签名实包在工作区未观察。Operit三个内置APK的公开签名/版本/ABI另列，未执行安装或签名。
- 16KiB：保留3项重建任务；把主机静态检查、debug目录核对、release应用诊断、设备运行四个通道分开。主审修正无效的 readelf stdin 命令为临时解包后读文件，release通过ExecutionProfile记录真实nativeLibraryDir/PROOT_LOADER/LD_LIBRARY_PATH/cwd/uid，run-as仅用于debug目录，不冒充终端环境证据。真机步骤明确等待所属执行单元。
- 排除：Operit desktop.apk不采用、example jars不分发、subpack keystore不复用；不读取私钥或凭据。桌面/Operit/terminal保持只读。
- 验收：修正后主智能体独立 `npm run verify` 通过15/15单元，31/31平台用例通过；总日志 `logs/s0-15-verification.log`，单元日志 `logs/s0-15-platform-capture.log`、`logs/s0-15-platform-verify.log`。无新Gradle工程或构建中间产物。
- 下一执行项：S0.16，旧数据出口、真实导出包与普通/连续身份导入样本。

## 2026-11-15T18:45:56+08:00 — S0.12 品牌几何复核

- 主审发现通用图标映射的全局 tint 与 24×24dp 不适用于双色长条 Logo；品牌专用映射改为逐 path 消费 DstuTheme foreground/background、`Color.Unspecified` 全局 tint 与原始纵横比，Mark 保持正方形，Logo 保持409:147。导航单色图标仍用LocalContentColor。
- 新增双色 path 与纵横比断言，`baseline:brand` / `verify:brand` 通过，单元日志刷新；源码SVG几何未改变。

## 2026-11-15T21:00:41+08:00 — S0.16 与本轮范围调整

- 用户将本轮收尾点调整至 S0.16：原任务列表已删除本轮 S0.17/S0.18 执行项，二者保持未启动；后续从 S0.17 继续，不推进 S0.19 或 S1。子智能体已返回最终结果，本轮不再启动/轮询子任务。
- 交付：`tools/baseline/legacy-export.mjs`、`tools/baseline/legacy-export/`、`config/baseline/legacy-export.json`、`config/legacy-import-contract.json` 与 `fixtures/legacy-mobile/`。`baseline:legacy-export` 采集真实 OPFS 导出，`verify:legacy-export` 对已保存样本回放，不重建随机 ID/真实时钟。平台无关合同由 S2.12/S2.15 消费。
- 真实链路：独立新浏览器上下文、临时 localhost origin、专用 Worker、SQLite WASM 3.53.4 的 opfs-sahpool；生产 MobileStorageService 执行四库迁移，生产仓储写入公开示例数据，MobileBackupService 经 VACUUM INTO/exportFile/ZipWriter 生成归档。用户 profile 与私有业务数据库未打开。一次性只导出 Worker 在同一真实 OPFS 上运行，不调用 runAll、不种数据。
- 最终样本：`legacy-mobile-standard-standard-v1.zip` 为 3,987,747 B，SHA-256 `3bcecfdd5ec02b0d3b8ee110f2649b6edc136bc9bb6d41b2a5673061ec932eba`；`legacy-mobile-continuity-continuity-v1.zip` 为 3,987,749 B，SHA-256 `774b1aea192eaec9f991c127aa275689b624b6679724e6ac6dea1316f5a13924`。两包各 7 条目：四库、audit、1 个 blob、manifest；业务数据是公开 fixture，不是用户旧设备数据。
- 数据验收：vfs 72 / chat_v2 30 / mistakes 23 / llm_usage 8 条 refinery history、checksum、schema version 和四库 fingerprint 与 S0.08 对拍一致；integrity_check=ok、foreign_key_check=0；manifest v3/full/not_present、6 域 complete/15 域 absent 与 coverage 自洽。2 笔记/格式/修订、3 资源、1 blob/文件、1 会话/2 消息/1 块、错题/卡片/设置/用量/审计及资源引用逐项回读，真实 OPFS 活动槽与包内数据一致。
- 主审修正 identity：使用实际生产 SqliteSyncStateStore（sync-support/src/state.ts）；普通恢复真实轮换并重建消费基线。continuity 新合同增加 installationId、可信包身份/manifest/instance/device 绑定及停用旧写者前置；绑定、状态恢复和唯一接管回执位于同一 BEGIN IMMEDIATE，保留 tombstone_event_publish。11 项负例覆盖 hash/epoch/来源/实例/写者/整数范围/旧写者未停/已有端点/中途写失败；全部拒绝且前后状态不变。磁盘 sync_state 重建/关闭/重开回读通过。
- 主审修正系统保存：harness 的能力推导只记 predictedMethod，observedReturn=null，不再记作真实系统保存。Capacitor 旧保存出口的问题按源码推断登记；新增本项目内 LegacyExportPlugin.java，使用 ACTION_CREATE_DOCUMENT 单文件 URI 授权、64 KiB 分块写入及原生大小/SHA-256 回执。页面从 OPFS 逻辑路径读取 Blob，不把 OPFS 路径当成 Android 物理路径，也不以 debug run-as 充当 release 导出方式。一次性构建步骤保留包名、origin、原签名及递增版本，先停止原 Worker；不卸载旧包。
- 系统交付验证：页面传输/取消/错误清理/错误回执及现有 Capacitor nativePromise 消息往返 5 项通过；Java 插件用本机 Android API36、Capacitor8.6.0 与现有 AndroidX 实际依赖执行 javac --release17 通过。生成静态资源的标准入口执行通过。Android 系统选择器、正式过渡包覆盖安装和系统文件真实落盘尚未执行，交付的是已校验源码方案及公开样本，不是已签名过渡 APK。
- 总验收：`npm run verify` 通过 S0.01→S0.16 的 16/16 单元、302 个计划单元及 5394 处交付关联，耗时 39661 ms；完整日志 `logs/s0-09-16-verification.log`。单元采集/验证、静态资源构建和 Java 编译日志分别为 `logs/s0-16-legacy-capture.log`、`logs/s0-16-legacy-verify.log`、`logs/s0-16-one-shot-build.log`、`logs/s0-16-native-source-check.log`。总验收同步修正品牌路径映射数组的旧跨单元断言，Logo 双色与纵横比检查通过。
- 清理与仓库：保留源码、必要样本、日志与本交接；生成的 one-shot/build、临时 SQLite/Java 类文件与探测目录清理。dstu-node、Operit、terminal 工作树无改动；dstu-android 仅保留用户预先修改的 `docs/rules/rules.txt`，本轮提交不夹带该文件，未推送远端。

### 本轮 S0.09–S0.16 汇总

| 单元 | 结果 | 核心交付 |
| --- | --- | --- |
| S0.09 | 完成 | 124 表分类、58 RowSync、12 设置白名单及五类双端补齐任务 |
| S0.10 | 完成 | 13 合同/79 字段、实际启用 v3/v4、双设备真实 runSync 与原 pending 升级发布 |
| S0.11 | 完成 | 812 设计令牌、20 调色板场景、17 变体及自选色对拍 |
| S0.12 | 完成 | 双色品牌/9 图标、25016 双语键、92 截图索引与13移植页面映射 |
| S0.13 | 完成 | 21 采用单元、122 调用链环节、125 包范围决策 |
| S0.14 | 完成 | 41 资产、85 文件、许可/NOTICE、原生页对齐与来源补证任务 |
| S0.15 | 完成 | 包名/SDK/ABI、0.21.0→2100000版本单源、实包/公开证书与16KiB发布合同 |
| S0.16 | 完成 | 2 真实生产导出样本、10步导入合同、两种身份模式、原子接管及一次性导出源码 |

- 本轮未做 S0.17/S0.18；下一执行项为 S0.17。S0.G 仍在 S0.25 后签收，Kotlin/Compose 根工程在 S1.01 建立。

## 2026-11-15T21:30:55+08:00 — S0.17

- 本轮按用户要求继续 S0.17–S0.25，逐单元实现、审查、测试、追加交接与本地提交；既有规则文件修改不夹带提交。子智能体编写语料和公开模型录件工具，主智能体负责设备探针、测量方案、统一入口、审查修正与独立回归。
- 交付：`tools/baseline/corpus.mjs`、`model-recordings.mjs`、`measurement.mjs`、`probe-device.mjs`、`test-foundation.mjs`、`run-logged.mjs`，对应 `config/baseline/` 三份子产物及组合索引、`config/measurement-inputs.json`、`fixtures/model-streams/`。入口 `baseline:test-foundation`、`verify:test-foundation`、`probe:device`。
- 编辑器：143 个受跟踪测试文件内 13210 个字符串中筛出 429 个 Markdown 候选、363 个不同输入（重复66），选取150份；生产 mdast/GFM/math 与 callout/toggle/wikilink/columns 变换重复回放，记录原文、UTF-8字节、来源行/hash、规范化AST/hash。7种坏分栏、2种未知语法、1种未知callout另列；实际校验器拒绝7种篡改，不以对象不相等冒充验收。
- 语料索引：134历史迁移、725桌面对照输入、3 PDF和2历史ZIP、DSBK及明文/加密仓库场景。16份脚本SSE与20份客户端对本地脚本服务的录件明确标注，未称为真实上游响应。
- 真实模型流：从 Apache-2.0 的 openai-python v1.109.1（提交 `a1493f92a7cd4399d57046aadc943aeadda5b8e7`）取得3份官方声明来自live API的原始SSE，文本/结构化JSON/工具调用共17344字节；许可、上游测试原文、来源及hash保存在样本目录。桌面 SseEventBuffer/OpenAIAdapter 按whole/1字节/7字节共9次回放，事件一致，12种字节篡改经完整性检查拒绝。EOF可补Done的解析事实另列，不冒充完整性拒绝。未读取用户密钥、未发起付费模型请求。
- 设备：用户连接后只读探测 `lab-tablet-01`，iPA2475/API36/arm64-v8a，MemTotal16021468KiB、4096页、2064×3096/400dpi/825.6dp；ADB uid0、Shizuku包存在、dstu包未安装。物理序列号不入库；ADB root不等于应用ROOT或SHIZUKU目标已验收。低内存、中端、16KiB和折叠动作仍为后续设备需求，本轮不编造测量数据。
- 测量：启动、编辑/滚动、流更新、搜索/导入/同步、终端I/O、锁屏耗电6协议，固定warmup/次数、P50/P95 nearest-rank及原始证据字段；绝对预算按S1.27取得三档实测后冻结，当前应用性能运行0次。
- 验收：主智能体独立 `verify:test-foundation` 与全量 `npm run verify` 通过17/17，302计划单元/5394关联，39999ms；日志 `logs/s0-17-capture.log`、`s0-17-verify.log`、`s0-17-verification.log`、`s0-17-device-probe.log`。源仓保持只读，下载/解析临时目录已清理，无APK或Gradle中间产物。下一执行项S0.18。

## 2026-11-15T21:49:01+08:00 — S0.18

- 交付：`tools/baseline/sync-golden.mjs`、`crypto-golden.mjs`、`file-golden.mjs`、`sync-formats.mjs` 及对应四份JSON，入口 `baseline:sync-formats` / `verify:sync-formats`，统一验收扩展至18单元。子智能体分别实现协议与加密，主智能体审查、编写文件/墓碑/备份向量并独立回归。
- 协议：生产change/envelope/legacy pending/format/sync manifest解析与序列化、i64/u64/float/未知/null/省略、v3/v4/legacy及宽松键边界、7组LWW和8组合并、HLC时钟、UTF-8与实际zstd字节/hash。13项非法输入拒绝；121协议与2原生源码hash入产物。UUID与时钟先校验形状再仅归一随机字段；键原件单独留证。显式null的suppress_change_log会解析为false，不能当作None原样回传。
- 未知列：真实桌面Connection执行生产vault/getRecordData/rehydrate；未知Unicode、null、嵌套对象和9007199254740993整数保留，新增列后回填SQLite INTEGER且剩余未知字段保留。仅归一vault更新时钟，不舍弃字段值或版本。
- 加密：生产Argon2id/AES-256-GCM与DSBK-v1/v2空、Unicode、1048576、1048577字节共8往返；固定salt/nonce仅用于公开向量，不改生产随机源。11项拒绝含密码、标签/头salt篡改、截断、版本、0/超64MiB chunk和KDF上限；注入backend探针证明超限前调用0次。多块失败先写出1048576明文，再因末块认证失败abort，暂存归零、未close/commit；不声称已测活动槽切换。
- 文件格式：66向量（38拒绝）覆盖blob/asset/workspace manifest、墓碑设备/seq/operation/key/payload hash、u64边界、混合写者/路径；使用S0.16真实导出manifest做v3/full/coverage/hash/路径/信任/版本拒绝。另核对可逆NFC/NFD/中文/保留名/控制路径编码，未将清单解析成功等同于路径可落盘。
- 主审修正：删除恒真解析对比，按serialize稳定性和独立可选字段事实断言；修正bigint测试表示、raw UUID回放归一及source hash遗漏；去除按耗时推导KDF未分配的结论。S0.17子项最终采集后的组合索引hash同步刷新，语料仅更新时间/耗时，语义self-hash不变。
- 验收：独立 `verify:sync-formats` 与 `npm run verify` 通过18/18、302单元/5394关联，38569ms；日志 `logs/s0-18-capture.log`、`s0-18-verify.log`、`s0-18-verification.log`，分项日志同前缀。无新增依赖或构建产物；下一执行项S0.19。

## 2026-11-15T21:58:06+08:00 — 子智能体执行限制追加

- 用户追加：每个子智能体最多三轮会话，只完成当前单元；进入下一单元必须新建子智能体，不得把S0.18智能体继续用于S0.19。
- 两条限制已追加到 `docs/rules/rules.txt` 第7、8条，保留用户原有规则内容。此前复用的两个子智能体已关闭；S0.19改由全新子智能体接手未提交草稿，草稿不视为已签收。主智能体负责最终审查、独立验收、修复与提交。
- 后续初始委派计第1轮，最多再追加2轮反馈；完成后关闭，不再追加任务或采集。各单元串行顺序不变。

## 2026-11-15T22:11:45+08:00 — 同单元会话限制调整

- 用户放宽同一单元内子智能体会话数量，不再设三轮上限；已追加规则第9条，明确覆盖旧轮数限制。禁止跨单元复用、下一单元新建子智能体的限制继续执行。当前S0.19新智能体可以继续本单元审查修正，不能承担S0.20。

## 2026-11-15T22:47:35+08:00 — S0.19 中断后签收

- 从模型请求中断处恢复，已暂存代码及19/19验收日志完整；保留用户当前规则和交接文件的调整，重新按实际时间记录签收。
- 交付 `config/sync-registry-contract.json`、`tools/baseline/registry-contract.mjs`、`registry-audit.mjs` 与统一入口：124分类、58 RowSync、逻辑/SQLite主键、唯一索引、列/生成列、原始触发器hash与17个FTS影子表。专属S0.19智能体已完成并关闭。
- 实际生产迁移及仓储执行6步笔记/资源CRUD、FTS清空重建、3项独立预检故障与恢复、未注册表隔离/未知库拒绝、12组生产主键解析。table_info与table_xinfo分别记录，采用相同API对照S0.08后schema漂移0；两处既有逻辑键/唯一键异常如实保留。
- 主审独立验证、4种合同篡改拒绝、本机/备份/派生写入捕获0、全量19/19通过；302计划单元、5518关联、40125ms。证据 `logs/s0-19-capture.log`、`s0-19-verify.log`、`s0-19-independent-verify.log`、`s0-19-audit.log`、`s0-19-verification.log`。临时库已清理，桌面/Operit源仓无改动。下一执行项S0.20。

## 2026-11-15T23:00:52+08:00 — S0.20

- 交付 `config/sync-release-window.json`、`tools/baseline/release-window.mjs`、`release-audit.mjs`；四库schema/fingerprint/迁移head、v3/v4窗口、未启用v5、应用版本独立、桌面/旧移动/未构建原生端能力、协调迁移条件和后续消费单元分别登记。
- 生产函数执行6格式场景、6上传schema场景及租约获取/释放；v2/v5写前拒绝，v3升级v4；上传gate只拒绝cloud>local，后续conflict detector对双向不等均报告迁移，两层结论分别保留。各schema gate无对象修改，拒绝格式无写入。复用并hash绑定S0.10真实双设备往返及原pending原位升级证据，不声称执行了不同应用二进制。
- 五项后续迁移为有条件预留：模板mistakes/20261006、附件根vfs/20261113、媒体vfs/20261114、配置vfs/20261115、插件vfs/20261116；待所属单元证明需要后交付原始SQL，当前不更改历史schema或lock。Kotlin及正式安装包能力留在计划所属单元。
- 专属S0.20智能体完成后关闭；主审修正旧移动能力误标原生端与未采集字段，独立单元verify及全量20/20通过，302单元/5518关联，52662ms。日志 `logs/s0-20-verify.log`、`s0-20-verification.log`。下一执行项S0.21，桌面增量源码与冻结基线分开登记。

## 2026-11-15T23:30:18+08:00 — S0.21

- 交付桌面增量 `desktop-increment/loader.mjs`、`templates-assets.mjs`、`chat-attachment.mjs`，模板协调迁移 `config/schema/coordinated/mistakes/V20261006__portable_anki_templates.sql`、模板/字节/聊天持久化3合同和 `fixtures/templates-assets.json`。原桌面源码只读；隔离子进程安装hash绑定源增量并调用真实生产仓储/采集/apply，后续Kotlin按平台无关合同实现，不声称APK已构建或分发。
- 模板由BackupOnly转RowSync/Lww；新增3同步列、6索引、3触发器。未修改内置模板不捕获；用户模板及携带user_modified/user_deleted的内置覆盖捕获。真实insert/update→wire→第二设备apply与完整行回读通过；重复名称/引用/坏hash拒绝、未知列vault保留、模板脚本未执行。基线mistakes20261005、新版20261006及新增history/DDL/hash分别记录。
- 资产根补充active/anki_media与active/chat_attachments；增量加载器按注册顺序组合并校验原始源码hash，2描述符合成自测通过。扫描先拒绝软链文件/目录，落盘也拒绝软链目标，不触及数据库目录。
- 主审独立运行生产syncAssetsWithTombstones：明文/DSBK两模式各2文件上传下载、云端墓碑删除、共享明文对象保留、独立密文对象删除、新版本恢复、hash回读；篡改云对象拒绝且无目标落盘。低成本KDF只用于公开fixture，生产参数未改。
- 聊天持久化适配器先校验大小/hash→受控路径写/fsync/rename→数据库事务更新storage_path/content_hash；复用、坏hash、大小错配、路径越界、目录软链与元数据事务失败清理均通过。使用既有chat_v2_attachments列，无额外共享迁移；实际发送流程接线归S3.13，不把临时stage根称作持久出口。
- 专属智能体完成关闭。主审独立分项及全量21/21通过，302单元/5518关联、54730ms；日志 `logs/s0-21-verification.log` 与同前缀分项日志。临时库/文件清理，历史134 SQL未改，未安装设备应用。下一执行项S0.22。

## 2026-11-15T23:53:22+08:00 — S0.22

- 交付 `desktop-increment/media-time.mjs`、V20261114__portable_media_progress.sql、媒体合同/fixture与独立media-audit。新portable_media_events按event_id去重、来源device、study/resume/subtitle分型，实际registry/迁移增量叠加S0.21，历史媒体/计时表保留本机。
- 持久事件证据不可变，数据库拒绝改写和删除；required/null/整数/安全毫秒范围、合法日期/时区、字幕字段在适配器和DB约束双重检查。计时按设备事件累加再汇总，逻辑日封顶86400；恢复点按实际时间/device/id选取，允许后退；字幕按plan/version/index选确定性winner，落选事件仍保存。
- 真实collect→wire→apply六事件，乱序/重复回放结果固定：90+30=120秒，新恢复点12000ms胜过旧90000ms，字幕b胜出；旧本机播放/字幕/计时表不变。复用event_id异载荷、非法输入、远端约束违反和故障apply均拒绝/隔离，原汇总保全。显式旧数据迁移按来源设备稳定ID一次导入，不把旧累计值反复相加。
- 主审修正JS整数精度、SQL CHECK空值、时区误拒绝、table_info基线装配和字幕winner/回声日志断言；独立三排列/重复事件、坏日期/偏移/越界测试通过。专属智能体关闭，不复用于S0.23。
- 全量22/22、302单元/5518关联、46844ms；日志 `logs/s0-22-verification.log` 及media/audit分项。仅源码和公开fixture，临时库清理；下一执行项S0.23。

## 2026-11-16T00:14:13+08:00 — S0.23

- 交付portable-config桌面增量、合同/fixture、采集器与独立config-audit。全部572个S0.06设置使用点按portable/local/secret登记并保留证据；未知键默认local，信任/启用覆盖与默认权限预设从旧白名单移除，凭据继续走安全存储/显式配对。
- 复用生产createSettingsStore和syncPreferences，不新增库/迁移/KDF。捕获useTheme的localStorage主题mode及AppearanceTab的palette/custom-color SQLite副本，原键回放验证。模型/工具只允许明确便携字段，认证headers/apiKey/env/password及嵌套敏感字段拒绝；命令/cwd/路径/启用与信任不随配置获授权。
- 生产preferences入口经hash绑定源增量接入安全包装：整包预检、净化后发布与落库、已验证远端字节缓存避免二次读取变化；批量回放先验证全部项，坏包不产生半包写入。Upload/Download/Bidirectional结果按真实winner算法测试，恶意远端字段拒绝且本地/云发布不变。
- 主审独立5项嵌套凭据拒绝、未知/信任分类与无部分写入通过；修正env凭据遗漏、规范化值未进入发布、入口绕过预检、原始白名单事实误标。新智能体完成后关闭。
- 分项及全量23/23通过，302单元/5518关联、50206ms；日志 `logs/s0-23-verification.log` 与同前缀分项。源仓只读、临时SQLite清理、无新安装包。下一执行项S0.24。

## 2026-11-16T00:50:31+08:00 — S0.24 与本轮收尾

- 用户调整收尾点为S0.24：本轮不启动S0.25、不签收S0.G、不进入S1。S0.17–S0.23均已逐单元本地提交；S0.24完成后停止。后续恢复位置为S0.25。
- 交付 `desktop-increment/plugin-data.mjs`及JSON/作用域仓储/apply/本机策略模块、`config/schema/coordinated/vfs/V20261116__plugin_skill_data_contract.sql`、plugin-data合同/fixture和独立plugin-audit。新portable_plugin_records按[owner,namespace,record_id]规范JSON元组绑定身份，记录插件/技能owner_kind、包版本/source_hash/schema/min_writer、已知/未知JSON、设备版本与墓碑；源hash是来源，不是输出权限。
- 实际生产分类/迁移/collect→wire→apply：6变更、5实际应用，跨设备编辑、schema最低写者拒绝降级、未知字段/9007199254740993/null保留、卸载默认留数据、显式删除墓碑回放通过。安装/启用/信任只在明确的本机策略适配器中；远端内容不安装、不启用、不执行，本轮未声称正式插件生命周期/UI或APK已完成。
- 主审独立验证owner隔离、伪造身份不可变、64KiB/16层限制、2000条/5MiB owner配额，失败不改变记录或捕获日志；未知字段信封形状也由DB校验。补齐null/标量JSON、外层事务内批次保存点回滚、非法时钟/非有限数字、特殊__proto__自有字段保留与保护。
- 主审修复单元间迁移准备函数：S0.21仅复制模板SQL，S0.22仅复制模板/媒体SQL，资源传输使用对应准备函数。恢复S0.22原来的精确head断言与独立hook，未让早期测试依赖S0.24；既有模板/媒体fixture不需重写，独立回归通过。子智能体曾越范围改动媒体测试并启动总验收，已停止关闭，最终集成由主智能体重新修正和独立验收。
- 最终全量 `npm run verify` 通过24/24、302计划单元/5518关联，54115ms。日志 `logs/s0-24-verification.log`，分项 `s0-24-verify.log`、`s0-24-audit-verify.log`，模板/媒体回归与成功采集日志均同前缀。历史134 SQL未改、临时SQLite与文件已清理、桌面/Operit/terminal源仓无改动；用户预先整理的规则文件保持未夹带提交。

### S0.17–S0.24 本轮汇总

| 单元 | 结果 | 核心交付 |
| --- | --- | --- |
| S0.17 | 完成 | 150编辑器样本、真实公开模型录件、设备探针与测量方案 |
| S0.18 | 完成 | 同步/文件/墓碑/备份/压缩/DSBK与整数/未知字段golden |
| S0.19 | 完成 | 124表身份/分类/触发器、生产CRUD/FTS与独立审计 |
| S0.20 | 完成 | schema/wire/能力/应用版本与协调窗口 |
| S0.21 | 完成 | 模板迁移/仓储往返、持久资产根/字节传输和聊天落盘适配器 |
| S0.22 | 完成 | 媒体/字幕/计时不可变设备事件及幂等汇总 |
| S0.23 | 完成 | 572设置使用点分类、主题/配置安全捕获与生产偏好接线 |
| S0.24 | 完成 | 插件/技能owner数据、未知字段/墓碑/配额与信任隔离 |

- S0.25未启动，S0.G未签收，Kotlin/Compose工程仍按S1.01建立；本轮不提供新APK。

## 2026-11-16T01:26:06+08:00 — 上游差异审计与 S0.01 补充

- 用户授权本轮评估桌面更新、补齐已完成单元并完成S0.25/S0.G；计划文档可直接修改相关行。此次同步后冻结桌面基线，不再追踪上游；上游自带Android/Capacitor/mobile运行链不纳入采用或新增需求，已有旧数据出口与导入兼容合同保留。
- 使用现有GitHub CLI认证取得提交，在独立检出完成审计后，将干净的dstu-node工作树快进到e6b2657fd59014a73f5bbb1d14c2db732cf23e87（tree 3bee0f058851b9eb113bdc036d9f60433ed29944，0.20.1）；无手工修改源码，无推送。Operit/terminal基线不变。
- 专属S0.01智能体交付upstream-audit工具、分离的固定输入与完整差异产物。1245文件（修改903/新增336/删除6）、旧新blob/mode/路径/单元映射、未覆盖0；测试/demo/文档/上游移动运行证据不冒充已交付桌面功能。原基线索引从dstu-android@0222ccc读取，不随后续采集漂移。11项缺项/篡改负例拒绝，主智能体独立verify通过。
- 更新source-inputs/source-lock，固定新桌面commit/tree/锚点；已有正确引擎commit对象复用，禁止自动推进冻结输入。主智能体独立verify:sources通过，日志logs/s0-01-upstream-source-verify.log；原基线全量24/24通过留证logs/s0-upstream-before-verification.log。
- 计划直接修订来源/冻结范围、题库FSRS与SM-2惰性转换、插件专属服务/贡献槽/工坊流水线、外部结果taint、内容绑定审批和Android能力边界。仍为302主单元；桌面full trust不得变成APP/LINUX/SHIZUKU/ROOT授权。新增上游V20261115复习迁移与原未使用主题预留号须在S0.20对账；实际升级顺序在S0.G独立验证。
- 本检查点只签收S0.01补充及影响计划，其他已完成单元尚待新基线复验；S0.25/S0.G未签收，不启动S1。

## 2026-11-16T01:56:55+08:00 — S0.02 冻结基线补验

- 新建S0.02专属智能体，核对冻结桌面官方注册点与槽位快照：17视图/9导航，ID与目标路由唯一，全部导航目标有效，无新增遗漏；不涉及上游移动运行链。
- 单元verify与主智能体独立verify均通过，无语义/文案漂移，不改写既有基线。证据logs/s0-02-upstream-verify.log。智能体完成关闭，下一补验项S0.03。

## 2026-11-16T02:02:00+08:00 — S0.03 冻结基线补充

- 专属智能体完成命令/菜单/诊断/外部入口审计；仍为96/57/45/5，忽略来源行号后行为定义无变化。设置命令改用统一openSettingsTab助手，更新7行证据位置及对应源码hash；深链接扫描3283→3389，仍未注册公开应用scheme。
- 刷新entries.json，单元capture/verify及主智能体独立verify通过。logs/s0-03-upstream-audit.log保留分类对比，其他同前缀日志保留初验漂移和成功结果。未改源仓，智能体关闭；下一补充项S0.04。

## 2026-11-16T02:13:49+08:00 — S0.04 动态工作台补充

- 新建专属智能体，保留22官方静态app与golden严格对齐；另登记plugin-view.<viewKey>社区动态族、owner/single实例/资源/注册替换撤销/重入、草稿/快照/返回和S6.34/S6.43/S7.37归属。真实mirrorPluginViewApps/SlotStore/appRegistry回放8阶段，渲染依赖stub边界显式记录。
- LaunchRequest新增placement/LaunchPlacement与besidePlacement来源、真实workbenchBus/windowStore回放确认新窗并排、已有窗只聚焦不移动、无锚点回退。窗口随机ID先核验再仅归一证据。provider缺失时WindowBody unknown-app与已挂载视图撤销后missing-view分开登记，不冒充同一路径。
- 单元重复capture字节稳定，verify与主智能体独立verify通过；主线程桌面插件视图/并排布局生产测试23/23。日志logs/s0-04-upstream-production-tests.log和同前缀采集/独立验证。计划相关条目直接修订，源码仓clean；智能体关闭，下一项S0.05。

## 2026-11-16T02:17:28+08:00 — 同阶段并发规则调整

- 用户授权同一阶段不同单元使用独立智能体，最多6并发，每个智能体仍只能完成其单元，同单元会话不限。规则追加记录此调整及计划可直接替换/桌面冻结约束；保留用户此前整理后的规则内容，不恢复已删除的旧三轮限制。
- 计划改为唯一集成验收队列/阶段门禁，同阶段独立写范围允许并发实现，最终证据等待前置稳定后按序签收、交接和提交。S0.05/.06/.07/.08/.11/.18各由独立智能体处理，主线程承担生产测试和集成审查；S0.25/S0.G仍等待补充验收完成，不进入S1。

## 2026-11-16T02:32:38+08:00 — S0.05 领域与插件服务补充

- 新增plugin-services领域与12项桌面采用合同：owner密钥/通知/定时/studyData、四种study事件、插件工具/外部数据taint、工坊生命周期/能力披露/数据界面、题库FSRS预演与媒体伴读IPC。精确解析12条新增IPC字段/定义/命令/事件，不用递归展开全部schema或错误归属覆盖原合同。
- 30领域、436服务、1064命令、963注册、129工具、229后台任务、1795源码文件；按桌面profile及静态引用区分1750桌面生产、31仅移动证据、14未接线桌面模块，不按portable文件名排除桌面共用代码，不把未接线模块误叫移动功能。
- 专属智能体补充后主审修正引用范围/接口匹配/合同精确解析，独立verify通过。主线程插件服务/studyData/题库FSRS生产测试27/27，证据logs/s0-upstream-domain-production-tests.log，采集/范围审计和独立验证为logs/s0-05-upstream-*.log。智能体关闭，下一集成验收S0.06。

## 2026-11-16T02:34:04+08:00 — S0.06 贡献面补充

- 18设置分区、573设置使用点、156桌面控件；4个上游移动壳专用控件从产品采用数组移入非采用证据。139动态键保留expression/来源，不用推断值代替真实键。基础分类为portable15/local508/secret50，S0.23继续按安全合同细分。
- 28槽类型、26具名声明、24直接贡献、11关键扩展面含新增7 surfaces槽；owner/声明/类型/消费者/上下文/持久化/撤销和Android归属逐项登记。主审修正聊天动作/复习动作/编辑扩展单元映射，主题变体保持本机提供者/选择，不同步信任。
- 专属智能体完成关闭，capture/verify与主线程独立verify通过，日志logs/s0-06-upstream-{capture,verify,independent}.log。下一集成S0.07，阶段出口尚未签收。

## 2026-11-16T02:35:16+08:00 — S0.07 内容基线补充

- 接续专属智能体完成冻结桌面内容审计：9份Markdown/9份Anki、18类生成式组件、49注册点、15富内容语言的原期望输出不变。更新注册行证据并绑定171内容源码hash、注册语句和bundle源码/依赖摘要，未把UI交互变化当作新格式。
- 相关上游5文件64测试通过；capture/verify及主线程独立verify通过，日志logs/s0-07-upstream-tests.log与同前缀采集/最终/独立验证。智能体关闭，原模板/Markdown语义与UTF-8/整数/null/hash比较规则保留。下一集成S0.08。

## 2026-11-16T02:35:16+08:00 — S0.08 FSRS schema 补充

- 上游V20261115__review_plans_fsrs.sql按原字节追加，135份历史SQL=原134+1；七列默认分别为5个NULL/2个0。主审增加采集前旧提交0222ccc的逐文件不可变hash检查，不允许capture覆盖原SQL漂移。
- vfs73迁移/head20261115，指纹c3da4956b07fa60548a8c848a4e7f60fd7f05d10ac936d2396f0fa1920b98d23。独立fresh 0→20261115、旧库20261111→20261115升级1条、restart零迁移；SM-2旧行/列/日到期保持，新默认/历史checksum前缀/指纹/governance/integrity/外键验证通过。
- 新增schema-upgrade工具和产物，组合进verify:schema；mobile-only内联DDL作为非采用证据排除，桌面全部schema仍实建。专属智能体完成关闭，主线程修正证据标签并独立验证通过，logs/s0-08-upstream-{capture,verify,independent}.log。下一集成S0.09，旧导出样本仍需所属S0.16兼容回放。

## 2026-11-16T02:42:00+08:00 — S0.18 冻结基线收敛

- 只接手S0.18单元，未推进S0.19或后续单元，未提交、未全量验收。冻结桌面为`dstu-node@e6b2657fd59014a73f5bbb1d14c2db732cf23e87`，工作树干净；黄金仅使用桌面源码与既有S0.16公开样本，不采用上游Android/Capacitor运行链。
- 原草稿在`sync-golden.mjs`中重复导出`parseFormatDescriptor`，并调用未定义`captureV5DeferredReplay`/`capturePluginSandbox`/`captureFormatNegotiation`/`captureProductionSurface`，esbuild无法构建。已删除未完成的v5扩展采集和无用内存provider，不改生产实现，不把未注册v5当作已交付功能。
- 协议golden v2保留原S0.18的change/envelope/manifest/key/lossless/HLC-LWW/merge/UTF-8/zstd/unknown-vault向量；补齐格式描述符实测边界：缺失/ v3/ v4(min_client=3)读取通过，v5 reader/format、v2、损坏JSON在写前拒绝。生产注册面固定24命令、没有v5命令、`runSyncV5`仅源码导出而未注册；摘要为`registered=false`。
- 源码绑定分层记录冻结commit/head、实际实现`repository/unknown-fields.ts`+`record-data.ts`、透明wrapper`src/unknown-fields.ts`、17个协议文件、122个esbuild输入及2个原生输入；不再用单个旧121输入计数替代新源码树，所有绑定hash在verify时重读核对。
- unknown vault使用真实`Connection`：890...整数、显式null、嵌套对象及原字段HLC/device回放保留；第二次只新增`late_obj`列，确认仅该列回填、`late_null`/`late_big`仍留vault，value和fieldDeltas均保留。生产vault更新时钟仅校验格式，不纳入确定性比较。
- 加密沿用生产Argon2id/AES-256-GCM与DSBK v1/v2：4组边界8次往返、11项拒绝（含密码/标签/头/截断/0块/超64MiB/三类KDF预算）及多块末块失败abort清空；固定salt/nonce只用于公开向量，不声称活动槽切换。文件golden仍是66向量/38拒绝，复用S0.16旧导出包作为唯一文件样本，标准包hash`3bcecfdd...932eba`未重建。
- `sync-formats`升级v2聚合三份产物hash、冻结来源、旧样本引用及v5-inactive规则；独立`verify:sync-formats`两次通过。证据：`logs/s0-18-upstream-capture.log`、`s0-18-upstream-verify.log`、`s0-18-upstream-independent.log`；`s0-18-upstream-verify-before.log`保留接手前失败。未改schema/legacy/corpus/其他单元；桌面、Operit源仓无改动，临时SQLite清理。

## 2026-11-16T02:45:37+08:00 — S0.09 冻结分类签收

- 124注册/58RowSync及六类计数不变，55未注册、56runtime、178实体表全部有分类；未知业务表/缺触发器0，原主键/唯一键两处事实继续保留。源仓储实现移动不改变分类。
- review_plans 25列包含七FSRS列；题库review_plans/review_stats/review_history的Android归属校正为S7.24。源码/历史SQL/字段集合/运行期DDL归属交叉核对，2个mobile-only DDL文件不纳入采用。
- 专属智能体完成后主线程独立verify通过，logs/s0-09-upstream-{capture,verify,independent}.log；S0.08/S0.06输入已稳定，智能体关闭。

## 2026-11-16T02:45:37+08:00 — S0.10 冻结协议签收

- 同schema真实runSync双设备A上传3→B下载3→B上传4→A下载4，两端pending0。旧端20261111在preflight拒绝业务发布，publicationWriteCount0、云对象变化0、原行/pending保持；同目录/deviceId原位迁移20261115后原pending3条发布并由A回读note_c/res_c。
- wire4/min reader3与未注册v5不变，25调用链文件及repository实际unknown实现/透明wrapper绑定；旧mobile provider仅对照，原生Android未构建。capture/verify及主线程独立verify通过，logs/s0-10-upstream-{capture,verify,independent}.log。专属智能体关闭。

## 2026-11-16T02:45:37+08:00 — S0.11 冻结设计签收

- 812token、20palette、17变体/30样式表重算稳定，新增primary实色、danger和8个ghost变体的实际来源/配方/解析值合同，每视图最多一个推进动作主按钮。light/dark prominent底色均等于冻结primary，旧未解析surface-ink四处事实保留。
- theme.variants按AST记录本机插件贡献/选择/移除恢复边界，不混入静态预设或同步信任。修复草稿初始化顺序/重复恒真断言，浏览器try/finally关闭；两次capture字节一致，篡改primary和按钮配方拒绝，源码已恢复。
- 专属智能体完成关闭，主线程独立verify通过，logs/s0-11-upstream-independent.log及同前缀采集/重复/负例/最终日志。S0.12品牌文案使用本快照，不跟进上游。

## 2026-11-16T03:04:32+08:00 — 停止后恢复

- 用户授权继续此前任务，使用新的子代理模型与新会话，最新并发上限3。停止前会话不复用；所有子智能体仍限定单元，主线程审查、独立验证和本地提交。
- 恢复时无baseline/vitest残留进程；dstu-node仍为冻结e6b2657f且工作树干净。新基线补充已提交到S0.11，S0.12及之后的未提交补充保留为草稿，不视为已签收；S0.25/S0.G未完成，不进入S1。

## 2026-11-16T03:07:42+08:00 — 恢复配置更新

- 用户切换子代理模型并将最新并发上限调整为6。上一批三个被中断会话全部关闭，后续新建会话继承当前配置，不硬编码旧模型；每代理一个单元和阶段门禁不变。没有残留baseline/vitest进程，所有草稿保留。

## 2026-11-16T03:24:23+08:00 — S0.12 冻结文案签收

- 用户最新要求rules文件仅由用户维护，后续不再修改、回滚或夹带该文件；限制以用户消息及现有文件读取为准。新S0.12专属代理完成并关闭，主线程独立verify通过。
- 48对语言文件保留21物理键差异（en缺7个base、zh缺14个one/other），对应7个真实复数族；实际i18next count=1/2解析通过，Android按zh other/en one+other映射，不合成英文base或隐去差异。旧7空值与5调用点、4命令fallback继续记录。
- 品牌5/31path、9导航图标、13Operit页/93token引用、17/9/18核心术语、92既有截图保持，Linux notes缺4不变。工坊术语及primary规则按冻结桌面更新；不存在的desktopOnly草稿键标未采用，不限制计划S6.36原生工坊。
- 单元连续verify与主线程独立verify通过，logs/s0-12-final-verify-3.log、-4.log及logs/s0-12-resume-independent.log；未改冻结源仓。S0.25/S0.G尚待其他补充与最终独立验证。

## 2026-11-16T03:28:38+08:00 — S0.13 冻结采用签收

- 保留Operit21采用项、125包/122调用链步骤，绑定冻结桌面30领域和动态plugin-view合同；新增插件专属服务的未来原生适配归属及UI动态族生命周期责任，不声称S0已有Kotlin实现。
- 目录仅记录有直接Operit关联的19领域，其他11领域依原桌面重写计划交付，不用恒真检查冒充全域移植覆盖。13品牌页面与S0.12最终术语/路由核对通过。
- 主线程独立verify通过，logs/s0-13-resume-independent.log；原采集/单元日志为logs/s0-13-upstream-*.log。来源Operit/terminal不变，桌面运行链不写入源仓。

## 2026-11-16T03:28:38+08:00 — S0.14 冻结资源签收

- 新桌面ACKNOWLEDGMENTS/THIRD_PARTY_NOTICES/Vue开发依赖作为来源证据，不直接合并成Android清许可结论；demo PDF不采用、Node/Electron/React/Vue非APK运行实现。导出字体原生成提交与字节一致，后续Android嵌入或替换仍在S4.05核验。
- 41资产/85文件/102二进制分类无遗漏；16KiB保留11通过/3失败，42个NOTICE组件为7已解析/26待证据/9不分发，原来源/许可补证任务不被关闭。私钥容器继续不读字节不hash。
- 主审按最终S0.13 hash重采集并独立verify通过，logs/s0-14-resume-{capture,independent}.log。旧代理曾越界增加主verify断言并运行失败的全量尝试，主线程已接管并保留日志，不据此签收其他单元。

## 2026-11-16T03:28:38+08:00 — S0.15 冻结身份签收

- 旧发布身份保留0.20.0/1114586，源码证据按145bb387原blob字节读取；新冻结提交携带的上游mobile0.20.1仅观察、不作为重写版本。新应用身份仍com.deepstudent.app/min26/compile与target36/arm64-v8a、0.21.0→2100000。
- 公开证书/现有debug实包/三份Operit APK对拍与版本单调约束不变，34/34平台用例通过；无签名包新构建、无私钥读取、无设备安装。历史证据与新应用版本/schema/wire分别记录。
- 主线程使用最终资源hash重采集并独立verify通过，logs/s0-15-resume-{capture,independent}.log；原单元日志保留。后续16KiB重建和正式证书实包核验仍在原计划门禁。

## 2026-11-16T03:28:38+08:00 — S0.16 旧包兼容签收

- 两份公开旧ZIP/sidecar/expected字节不变，先以0222ccc历史schema验证原包四库/行/资源/manifest，再临时复制库由冻结桌面生产协调器从vfs20261111升级20261115并重启零迁移。原包事实单列sourceDatabases/sourceFingerprints，databases/fingerprints明确为迁移后目标，不重导出或替换旧样本。
- 临时副本中公开SM-2探针行保留旧列/日到期，七FSRS列默认正确；所有旧业务表/资产/audit逐项回读保持。旧出口源按145bb387 Git blob原字节取证，缺失即拒绝，未采用最新mobile链；原一次性浏览器采集证据明确历史复用。
- standard身份轮换、continuity原子接管/11负例/重复接管/磁盘回读与五种保存消息回归保留。单元capture/连续verify及主线程独立verify通过，logs/s0-16-final-*.log与s0-16-resume-independent.log。专属代理关闭，未声称真机系统保存或正式过渡包已验收。

## 2026-11-16T03:28:38+08:00 — S0.17 语料补验签收

- 冻结桌面源码更新后刷新原143测试文件来源hash/行证据，150份Markdown输入、UTF-8与AST期望和原样本逐项一致；schema索引135份（vfs73），新迁移原字节收录。设备探针/预算方法不编造新测量。
- 本地重采集与verify通过，3份公开真实模型录件9种切片回放/12篡改拒绝保持；paidApiCalls0，不发模型API请求、不读密钥、不重建旧ZIP。证据logs/s0-17-resume-{before,capture,verify}.log，原失败明确是已更新桌面测试源hash。

## 2026-11-16T03:28:38+08:00 — S0.18 新基线最终签收

- 主线程审查中断前golden v2，保留原协议/整数/null/LWW/压缩/DSBK与文件向量，unknown-field实现及透明wrapper同时绑定；新增真实局部rehydrate仅回填已存在列，其他未知列及原版本保留。v5诊断解析不等于生产启用，格式v4/min3及注册面明确拒绝未来版本。
- 主线程独立verify:sync-formats通过，logs/s0-18-resume-independent.log。8加密往返/11拒绝、66文件向量/38拒绝保持，历史标准ZIP hash不变；未增加生产协议版本或Android运行声明。本条为新基线补充最终签收，先前02:42收敛记录不作为全阶段验收。

## 2026-11-16T03:40:07+08:00 — S0.09 计划绑定修正与 S0.19 最终签收

- 首轮全量发现分类产物整份plan字节hash与实际消费范围不符：文字修订触发与分类无关漂移。改为有序302单元ID集合hash，仍保留同步矩阵/所属验收原文和来源检查；不忽略真正单元或矩阵变化。主线程重采集并独立分类verify通过，logs/s0-09-plan-binding-*.log。
- S0.19保留124/58、CRUD6步/FTS/主键12向量/原结构篡改；新增真实ReviewPlan服务评分→采集/enrich→wire序列化/解析→独立目标apply(parsed)→仓储回读七FSRS列。同步前写vault的未知9007199254740993/null在目标vault及getRecordData中实证一致，不硬编码保留结论。
- 漂移probe按实际生产语义记录：未知列接收进vault、主键变体按业务键别名跳过、远未来updated_at检疫；不强造拒绝规则。测试时钟限定同步调用并核验还原，源仓只读；连续verify/主线程独立verify通过。
- classification语义绑定变化后重采注册合同与发布窗口的依赖hash。第二轮全量24/24通过，302计划单元/5647关联，55978ms；日志logs/s0-resume-second-full.log。这不是S0.25/S0.G签收，二者仍在独立审查。

## 2026-11-16T03:40:07+08:00 — S0.20 协调窗口最终签收

- native基线vfs20261115/mistakes20261005与项目协调目标vfs20261117/mistakes20261006分别冻结；模板20261006、媒体20261116、插件20261117实际三SQL原字节hash一致，附件/主题复用既有持久化无需SQL，旧预留明确废止，无stale/待重排文件。
- FSRS parsed source/target及未知字段目标证据已纳入validated-source-contract；保留wire4/min3、v5未启用、格式/schema门槛/租约、零业务发布拒绝与原pending保全。合同就绪不等于桌面已发行或Kotlin宿主已安装，相关声明均false。
- 最终capture/verify/audit及主线程独立audit通过，logs/s0-20-final-*.log、s0-20-plan-binding-capture.log、s0-20-resume-independent.log。freezeEligible仅表示可进入独立S0.G组装核验，S0.G结果由其自身产生。

## 2026-11-16T03:40:07+08:00 — S0.21 冻结资产签收

- 模板协调SQL20261006原字节hash不变，prepare仅复制冻结上游135迁移与本模板；新基线vfs20261115来源/迁移锁/源码锚点逐项绑定，未依赖后续媒体/插件迁移。
- 生产模板仓储→采集/wire→第二设备apply完整行回读、未知字段/重复名/引用/坏hash/脚本不执行通过；资产明文/DSBK两模式各两文件传输、墓碑/其他引用保全/恢复/坏对象拒绝通过；聊天落盘复用/大小/hash/路径/软链/事务失败清理通过。
- 专属代理完成关闭，主线程模板、资产、聊天三分项独立verify通过，logs/s0-21-resume-independent.log、s0-21-assets-resume-independent.log、s0-21-chat-resume-independent.log与单元final日志。没有新APK或源仓直接改写。

## 2026-11-16T03:40:07+08:00 — S0.22 协调媒体签收

- 未发布媒体SQL由20261114重排为20261116，原SQL字节完全一致；新冻结definitions hash/源形状绑定，按20261115→20261116顺序追加。prepare只上游+模板+媒体。
- 真实已迁移20261115的库追加1条到20261116，新建库74条迁移、重启0条；旧FSRS行/列保持，120秒设备去重汇总、12000ms允许后退恢复点、字幕winner/不可变trigger/坏事件/rollback原用例通过。
- 专属代理完成关闭，主线程media-time与独立media-audit均verify通过，logs/s0-22-resume-independent.log、s0-22-audit-resume-independent.log与单元final日志。插件20261117由其所属单元独立交付，不混入本单元准备函数。

## 2026-11-16T03:40:07+08:00 — S0.23 配置边界签收

- 573设置使用点逐项有来源，安全分类为portable20/local503/secret50；插件主题变体依本机owner贡献，plugin.schedule执行史、本机启用/信任/权限预设不便携；secret.plugin.*按安全存储secret前缀归秘密通道。
- 冻结Appearance/useTheme来源变化经重读后绑定16文件hash，原主题镜像行为保留；生产preferences整包预检、多文件恶意后项不半写、净化发布与三方向真实SQLite回放均通过，无新SQL或KDF。
- 专属代理完成关闭，主线程portable-config与config-audit独立verify通过，logs/s0-23-resume-independent.log、s0-23-audit-resume-independent.log与final日志。迁移预留20261115不生成主题SQL，上游FSRS身份不变。

## 2026-11-16T03:45:46+08:00 — S0.24 协调插件数据签收

- 未发布插件SQL从20261116顺延20261117，原字节hash不变；媒体20261116后顺序追加，准备目录精确包含模板/媒体/插件三份协调SQL，不残留旧号。fresh vfs75迁移、上游20261115→媒体20261116→插件20261117升级及restart0通过。
- 保留owner身份隔离/配额/未知bigint与null/墓碑/回放/外层事务回滚/卸载数据保全，6变更5应用；本机服务适配器明确secret/schedule不进入portable表，inspect不泄漏secret值，remote installed/trust/fullTrust不授予执行权限。该适配器是平台无关边界证据，不声称已接入正式插件生命周期或安全存储。
- 专属代理完成关闭，主线程plugin-data与plugin-audit独立verify通过，logs/s0-24-resume-independent.log、s0-24-audit-resume-independent.log及final日志。S0.01–S0.24新基线补充均已通过独立验收；S0.25/G仍待参数化完整性修正与最终签收。

## 2026-11-16T03:55:50+08:00 — S0.25 执行矩阵签收

- 冻结config/execution-inputs.json与execution-plan.json，302主单元/10出口组成312位置唯一集成队列；55参数化父、640有来源成员、1774卡（55公共setup/1472操作子卡/247普通卡）、3198场景。每卡有执行角色/顺序/输入与证据/测试类型，父子工期只算一次，所有依赖（含范围）指向更早项。
- 主审修正范围解析、卡ID去重与生成时钟归一；S7.37覆盖30host+19officialUI+4example逐包子卡，browser_scroll按已有press_key/evaluate明确别名并建验收卡，不留未决成员。24前置均已签，S0.25工具本身不自行签M0。
- S0已完成不重排未来，实际工作日unknown/null；未来S1+从2026-11-17按五天周粗估剩余1354.5工作日（全模型1402.5含S0模型48），不计节假日且非交付承诺。均匀逐卡估算存在较大偏差，按计划S1.G实测后重新校准，不能把估算当实际耗时。
- 哈希以持久化frozen输入控制，日常verify无需环境变量；重复/漏成员/向后依赖/虚构actual/累计错误/缺场景/周末里程碑等14负例均拒绝。主线程独立verify通过logs/s0-25-resume-independent.log；主验收入口已接入本单元。S0.G最终签收由独立组装和主审回执完成，下一执行仍不得越过未签收G。

## 2026-11-16T04:01:36+08:00 — S0.G / M0 最终签收

- S0.01–S0.25新冻结基线补充全部独立验证后，由S0.G隔离进程真实组装135历史+3协调=138份SQL。template/media/plugin准备目录分别136/137/138份，逐字节与最终集合一致；原134历史及3协调SQL内容未改，只有未发布协调版本顺延。SQL集合SHA-256为a3c70a046b3d981c2ceba348ad9e7a14e3113d076925e6dbad50ccc67a6d2675。
- 四共享库fresh/既有上游库升级/restart指纹一致、integrity_check=ok、foreign_key_check=0，restart全部0迁移。vfs20261117/75迁移指纹4370a412fa4d4dd7a23cf2308735f75c147ba30ab22329e7829a9bb1d56161d2；mistakes20261006/24迁移指纹fdd31bdf68ccfa505b88e946a24eb4ae66dc43e13ccf3102f045a67af36b4e31；chat_v220261111/30与llm_usage20260826/8保持原指纹。旧FSRS行/关联记录/change log和模板旧列/默认值升级前后保持。
- 最终registry126/RowSync61，61张捕获表trigger检查通过，主键检查保留已记录的逻辑键异常，不掩盖历史事实。缺迁移/坏hash/旧号/向后依赖四负例均拒绝；25单元证据采用固定成功JSON事件日志，不以任意非空或失败日志签收。
- 主线程独立capture/verify后于03:59:35+08:00写入config/s0-g-main-review.json accepted回执，绑定final-lock、policy、SQL集合、四库指纹和25单元成功证据。工具生成的锁保持validated且不自行签名；回执为最终M0签收依据，避免相互hash循环。
- 强制签收验证通过：S0_G_REQUIRE_SIGNED=1对应logs/s0-g-main-signed-verify.log。最终全量入口通过25/25+S0.G、302计划单元/5717关联、56329ms，日志logs/s0-final-verification.log。config/execution-plan.json已冻结，npm run verify:execution-plan与npm run verify:stage-gate均为独立入口，npm run verify要求合法主审回执。
- M0完成不等于发布：26组件许可待补证据、3项16KiB重建等29条发布阻断项保留明确owner/后续门禁；无新APK、未运行Kotlin/Compose或设备业务测试。桌面冻结e6b2657f后不跟进上游，自带安卓运行链不采用。最新用户要求后rules仅只读，未再修改。
- 所有代理已关闭，临时数据库/类文件/测试中间产物清理；dstu-node、Operit、terminal工作树干净。交付逐单元本地提交，未推送远端。下一执行位置S1.01，本轮不启动。

