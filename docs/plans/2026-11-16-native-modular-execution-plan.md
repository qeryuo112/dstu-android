# dstu-node Android：原生分层与模块化执行计划

- 进度更新于 2026-11-25。S1.01–S1.27、S1.G、S2.01–S2.31 和 S2.G / M2 已签收。下一单元为 S3.01，进度见第 8 节。
- 本文件是后续开发的有效计划。dstu-android 本质上是 dstu-node 安卓部分的原生重构，最终交付具有完整冻结桌面功能且与冻结桌面兼容的 Android 原生端，不是精简版或伴侣端；本文件规定工程组织、前后端职责、单元实施与架构验收，不删减桌面功能、来源、兼容性、设备及发布要求。
- 当前功能与依赖依据：[原计划（已原地修订）](2026-11-15-mobile-full-feature-refactor.md)。2026-11-23 经用户授权插入 S2.16–S2.18，原 S2.16–S2.28 顺延为 S2.19–S2.31；现行编号由 `config/planning/active-plan.json` 的修订映射解释。`config/execution-plan.json` 及 S0 合同保留历史字节；原计划的 S0 版本从 Git `36511a1ba27ef2db7c45636162575a2b1c6564e8` 读取，不用旧编号直接查新单元。
- 当前交接：[S3 交接](../handoff-s3.md)；历史交接：[S0 归档](../archive/handoff-s0.md)、[S1 归档](../archive/handoff-s1.md)、[S2 归档](../archive/handoff-s2.md)。有效入口登记于 `config/planning/active-plan.json`。
- 开工路径及唯一首读要求见[开工阅读清单](../../AGENTS.md)；本计划不是首读文件。每个新单元须完成 §4.1 的源码学习，规则仅由用户修改或按用户明确要求修改。

## 1. 审查结论与修订边界

原计划具有原生业务、UseCase、Repository、任务账本和源模块移植的良好基础，但**不足以保证前后端分离和模块化**。纵切验收本身没有问题；缺少编译依赖约束、明确所有权和可检测的复杂度上限，才会使纵切成为巨型类或函数。

| 原计划证据 | 风险 | 本版处理 |
| --- | --- | --- |
| 第4节，第145至158行的大目录 | `core/model`、`core/data`、`core/runtime` 和 knowledge/study/system 会逐渐容纳无关业务 | 小型共享基础、独立业务域、服务端口与实现分离；模块按职责及变化原因划分 |
| 第4.1节第174行、6.3节第321行 | 所有 UseCase 经过 RuntimeController，控制器容易同时负责业务、审批、持久化和生命周期 | 短操作直接调用用例，长操作提交任务；任务、授权、恢复与前台适配分别拥有实现 |
| 第10.0节第507行 | “同单元交付”可能被误解为同一个 ViewModel 或源文件实现全部前后端逻辑 | 一个验收单元内先合同，再后端/适配，再前端，最后集成；每层独立证据 |
| 第633、679、896行 | 四面板、全域同步 apply、工坊流水线的复合职责缺少内部边界 | 明确状态机、注册表、协调器和页面的独立所有者及负例 |
| 第7.1节、第9节、第958行 | 工具、设置和插件逐项适配可能集中成巨型 switch/when | 按域贡献类型化描述符和处理器，不设通吃所有业务的管理器 |
| 第12.3节 | 行为测试丰富，但没有防越层/循环依赖/复杂度的 CI 门禁 | S1.01 实现静态依赖与复杂度检查，以真实负例验证检测能力；此后每单元必跑 |

### 1.1 不可变输入

以下仍是 S0.G / M0 已签收基线，不因本次架构修订重新捕获、重新签名或移动：

- 桌面 commit `e6b2657fd59014a73f5bbb1d14c2db732cf23e87`，tree `3bee0f058851b9eb113bdc036d9f60433ed29944`，仅使用冻结桌面功能；不 fetch/推进上游，不采用其 Android/Capacitor/mobile 运行链。
- Operit `7a39941bad80209973c535046a18195c5da9e647`，terminal `e4646680663789002b43bbfffa6e69e65c35e614`；保留来源、许可证和补丁证据。
- 135 历史 SQL + 3 协调 SQL，共 138；集合 SHA-256 `a3c70a046b3d981c2ceba348ad9e7a14e3113d076925e6dbad50ccc67a6d2675`。共享库目标 vfs20261117、mistakes20261006、chat_v220261111、llm_usage20260826；指纹以 `config/schema/final-lock.json` 为准。
- registry126 / RowSync61，wire v4 / minimum reader v3，v5 inactive；模板/附件、媒体/计时、便携配置、插件数据的 S0.21 至 S0.24 合同不变。只变 Kotlin 代码组织，不变 SQL、JSON、对象键、合并语义或执行信任。
- S0 历史快照保留 302 主单元、10 出口、312 队列位置、55 参数化父单元、640 成员、1774 卡、3198 场景。2026-11-23 当前计划新增 3 个兼容补修单元，合计 305 主单元、10 出口、315 队列位置；S1–S9 分层责任映射为 280 单元、9 出口。历史卡/场景保持原 ID，经明确映射继承到当前单元，不能把历史证据改名或据此签收新增单元。
- `config/s0-g-main-review.json` 的 accepted 回执是 M0 最终完成依据。冻结队列中 S0.25 in-progress / S0.G pending-main-signoff 是签收前快照，不能据此回退 M0，也不为修正展示而改写快照。
- 历史 ZIP、sidecar、expected、来源锁、合同、final-lock、已绑定成功日志保持原字节；原计划的历史字节留在上述 Git 快照，工作区正文按用户授权修订；历史 ZIP 不重新导出。`desktop-increment/` 仍通过受 SHA 约束的 loader 安装，不等同于已修改或发行桌面安装包。
- 包名 `com.deepstudent.app`，minSdk26，compile/targetSdk36，arm64-v8a，初始 0.21.0 / 2100000；26 许可补证与3项16KiB重建及原设备/签名门禁仍阻断发布。

原计划被 S0.25 整文件 SHA 及部分 S0 比较器的行号绑定；本次用户授权原地修订后，历史验证改从固定 Git 快照读旧原文并验原哈希，当前正文另按修订登记校验。功能要求以冻结原文为最低集合，工程组织以本版为准；遇到冲突不得通过删功能或改变基线消除，须记录具体冲突并由主线程审查。架构门禁按已签收实现持续验证；本轮只修订计划，新增兼容补修仍须独立开发和验收。

## 2. 前后端的准确含义

这是本地优先的原生 Android 应用，不新增远程业务服务器，不把官方页面改成 WebView，也不要求前后端独立安装。分离体现在源码依赖、状态所有权、测试和可替换接口：

| 层 | 负责 | 明确不负责 |
| --- | --- | --- |
| 表现层 `ui` | Compose 渲染、ViewModel、不可变 UiState、UiAction、导航与 UI 临时状态 | SQL、网络协议、进程启动、业务校验、评分/同步/授权决策 |
| 公开合同 `api` | 领域 DTO、命令/查询接口、错误、事件、资源引用、取消/任务句柄 | Compose、Android Context、SQLite 类型、具体网络/JNI/移植实现 |
| 业务层 `backend` | `domain` 纯规则及 `application` 用例/状态机；依赖所需端口 | Android 生命周期、页面状态、数据库连接、第三方实现类型 |
| 数据/平台适配 `data`、`platform` | Repository 实现、映射、事务、SQLite/HTTP/SAF/Keystore/FGS/JNI 交接 | 屏幕流程、跨域业务规则、重复的权限与任务权威 |
| 装配 `app` | 按进程装配对象、生命周期入口、注册域贡献 | 业务用例、通用 ServiceLocator、任意模块的运行时查找 |

业务层和 API 为纯 Kotlin/JVM，允许固定的 Kotlin 标准库、协程和纯 Kotlin 序列化库；不得依赖 Android、Compose、Lifecycle、WebView 或 native 类型。Android 必需类型在适配边界转换成 ID、流或明确定义的句柄端口。

前端测试以 fake 命令/查询端口驱动 loading/content/empty/error/approval/cancel 状态，不需启动真库、模型、PTY 或远端服务。后端测试无 Activity/Compose；平台测试用真实 SQLite/Keystore/服务；最后的跨端/设备 E2E 仍运行完整实现，不能由 fake 测试替代。

## 3. 工程边界与依赖方向

### 3.1 目录和编译边界

```text
app/                              进程入口与 composition roots
core/common/                      ID、时钟、错误、日志接口等小型共享原语
core/design/                      DstuTheme、无业务控件、图标与资源
core/ui/                          UI 生命周期/窗口/导航适配，不含业务
services/<capability>/api/        runtime/resources/sync/backup/capability/execution 的窄合同
services/<capability>/backend/    各公共服务状态机与编排
services/<capability>/data/       本服务本机账本及基础设施适配
features/<domain>/api/            域公开命令、查询和贡献合同
features/<domain>/backend/        domain/ 与 application/ 包，不放 UI
features/<domain>/data/           域表、wire/存储 DTO 映射、Repository 实现
features/<domain>/ui/             Route、ViewModel、Screen、可复用域组件
platform/database/               连接、迁移、事务；不拥有所有领域仓储
platform/network/                HTTP/SSE/WS 传输；不拥有 provider 业务协议
platform/storage/                URI/PFD/SAF、受控文件 I/O
platform/security/               Keystore、KDF、加密 primitive
platform/background/             FGS、WorkManager、通知/唤醒适配
integrations/operit/<module>/     窄宿主接口、映射与有来源的必要补丁
third_party/operit/<module>/      冻结来源，禁止反向引用应用
engines/<engine>/                各算法/native/内容引擎与独立 adapter
testing/                         分层 fixture、端口契约、跨端与设备 harness
build-logic/                     convention plugins、依赖与复杂度门禁
benchmark/                       固定语料与设备预算
```

这里的 `api/backend/data/ui` 是可检查的编译/包边界，不是要求每个微小能力立即创建四个空 Gradle 工程。S1.01 先建当前需要的骨架；各域首次实施时再建立模块。UI 与后端实现必须分 Gradle 模块；domain/application 可以同一纯 Kotlin backend 模块内分包，由架构测试限制 domain 不引用 application。只有真实独立边界才继续拆模块，不按每个类建模块。

领域命名细分为 notes/editor/documents/search、chat/providers/agent/workflow、cards/review/questions、tasks/focus、media/translation/insights/home、workspace/terminal/environments/mcp/plugins/workshop/browser/device/settings。原清单中的 `features/knowledge`、`features/study`、`features/system` 等仅保留为历史归属标签，不建立容纳全部子域的巨型实现。首次实施时为每个 S0 归属记录新模块映射，不能因为换目录丢掉场景。

### 3.2 允许与禁止的依赖

| 消费方 | 允许依赖 | 禁止依赖 |
| --- | --- | --- |
| 域 `api` | core/common、必要的公开基础合同 | 实现层、UI、Android、其他域的内部模型 |
| 域 `backend/domain` | 本域 api、core/common、同域纯规则 | application、data、ui、app、platform |
| 域 `backend/application` | 本域 domain/api、所需服务 api、已登记的其他域 api | data/平台具体实现、跨域内部包、UI |
| 域 `data` | 本域 api、backend 中的仓储/事务端口、platform、必要引擎 adapter | UI、另一个域的仓储实现、app |
| 域 `ui` | 本域 api、core/design、core/ui、声明式导航/面板合同 | backend/data 实现、SQLite/HTTP/JNI/Operit运行内核、其他域 UI 实现 |
| 公共服务 backend | 本服务合同、core/common、所需基础服务 api | features 实现；通过注册处理器接入领域，禁止逆向导入业务域 |
| platform/engines | 必需的低层 api、固定外部库 | app、features/ui、业务装配图 |
| integrations/operit | 冻结移植模块、窄宿主端口及必要平台适配 | AppGraph、其他域实现；vendor 源码不得反向引用宿主 |
| app 装配 | 本进程所需的公开接口与实现工厂 | 在装配函数内执行业务或维护第二份全局状态 |

所有 Gradle 依赖必须无环。跨域调用依赖公开命令/查询或域事件，双向流程通过拥有该用例的协调器和双方端口完成，不以 EventBus/全局容器绕过依赖图。跨域 UI 跳转发送类型化目的地，不互相 import Screen。默认 `implementation`，仅合同真正需要传递的依赖使用 `api`。

### 3.3 所有权和运行链

```text
短操作: Screen -> ViewModel -> Command/Query API -> UseCase -> Repository port
长操作: ViewModel/Worker/工具 -> SubmitTask API -> Ledger/Lease/Supervisor
                                       -> 注册的领域 TaskHandler -> 领域用例
后台观察: 查询快照 + 持久事件游标 -> UiState
数据实现: Repository adapter -> 同库事务/ResourceStore -> outbox
装配关系: app 注入 adapters/handlers；runtime 不导入 features
```

- `AppGraph` 分 MainGraph、EngineGraph、IsolatedGraph，子进程只构造其所需端口；不触发主业务库、主进程任务管理器或 UI 初始化。构造函数注入，不向 ViewModel/移植源码传整个 graph。
- RuntimeController 若保留名称，仅是窄门面。TaskLedger、LeaseManager、TaskSupervisor、RecoveryCoordinator、DutyRegistry 分开；它们不解析聊天、题库、工坊或同步的业务字段。状态迁移在纯 Kotlin 状态机中测试。
- 业务完成事实与 outbox 在所属业务库同事务提交，runtime.db 是幂等对账的任务投影，不覆盖业务事实。任务 Job 属应用/执行所有者，ViewModel 清理只结束 UI 观察，不隐式取消用户长任务。
- CapabilityBroker 是授权/撤权/审计的唯一裁决入口。ToolDispatcher 验参、定位 handler 和协调顺序，ApprovalCoordinator 只呈现/转交审批状态；不各建一套信任缓存。Worker、插件、CLI、脚本与 UI 调用必须走同一边界。
- FGS、Worker、BootReceiver、通知和浮窗是生命周期/系统适配器，调用同一任务端口/lease；不持有业务循环。通知生成、WakeLock、职责引用、窗口管理分工，不整体复制大型 service。
- DomainSyncAdapter 由各域注册，负责字段映射与既定 apply；sync service 只负责 wire、顺序、事务边界、游标及调度。S2.30 先用真实迁移后 fixture 完成各域合同与通用实现，业务写路径随后续单元接入，不提前创建所有页面。
- 同一数据库的跨表业务操作使用明确 UnitOfWork；不让 UI 传任意 SQL，不把四个库伪装成一个事务。跨库完成通过既有 outbox/幂等对账，网络、审批、脚本不得放进数据库事务。
- ResourceStore 唯一负责权威资源写入；PFD/临时产物由明确 owner 关闭并验证后提交。wire/持久化 DTO 与 UI DTO 分开映射，未知字段、大整数、null/省略等冻结语义必须保留。

### 3.4 UI 与扩展边界

每个页面为 Route（接线）/ViewModel（UI 状态协调）/Screen（纯渲染）/局部组件。ViewModel 仅调用类型化命令和查询接口，不直接持 Repository、RuntimeController 实现、Context、数据库或网络客户端。导航提示与可重放业务结果区分，重要结果不能依赖一次性 UI channel 才能送达。

设置、工具、工作台 appId、内容块和插件贡献按 owner 注册 `SettingSpec`、`ToolHandler`、`PanelHost`、`BlockSpec`、`ContributionDescriptor`。每个 owner 自带验证、持久化、作用域与释放接口；注册器只检查 ID/版本/冲突/注销，不集中实现全部 handler。禁止一个 `when(settingId/domain/appId/toolName)` 承载所有业务。

内容 WebView/浏览器/QuickJS/CLI 仍是专用运行组件，不承载官方业务页面。移植的 UI 只能依赖 UI facade；PTY、系统命令或包安装从适配端口调用。移植来源保留不代表大型上游管理器可以原样成为新的宿主中枢。

## 4. 单元实施、测试与复杂度门禁

### 4.1 每个原单元内的完成顺序

0. 源码学习：先核对 dstu-node `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 与 Operit `7a39941bad80209973c535046a18195c5da9e647`（涉及 terminal 时核对 `e4646680663789002b43bbfffa6e69e65c35e614`），阅读本单元相关实现、调用链及测试；在当前交接记录源码路径、关键数据/同步合同、复用方式与差异。不能以交接摘要代替读源码；一侧无对应实现则记录搜索范围和相关组件。
1. 合同：登记所属模块、公开 API、输入输出/错误、事务与取消 owner、当前单元与历史卡/场景映射和写范围；完整冻结桌面功能及跨端兼容是最终目标，阶段切片不能变成永久删减。
2. 后端：纯规则/状态机/用例和 fake 端口测试；跨域只调用已经签收的端口。
3. 适配：SQLite/网络/引擎/系统实现及契约测试；保持同一冻结数据格式和信任边界。
4. 前端：有页面的单元以 fake API 完成状态、交互、主题与可访问性，再接实际实现。纯后端单元不凭空增加页面，必须说明无页面原因并提供可调用端口及合同证据。
5. 集成：完成原验收及需要的真实设备/桌面往返、日志与故障测试；主线程独立复验、追加阶段 handoff、本地提交后才签收。

这些是同一单元/既有卡内部的工作步骤，不新增验收队列或允许只做某一层就报完成。按当前rules最多3个专属子代理，每个只负责一个单元，进入下一单元新建会话；同单元会话数量不限。同阶段可准备无共享写冲突的独立单元，但前置未签收时不得集成或以假接口宣称完成。集成严格遵守原队列及阶段出口，不能以并发绕过继承的前一单元依赖。

### 4.2 四类证据

| 证据类型 | 必验内容 | 不能替代 |
| --- | --- | --- |
| 纯 Kotlin/JVM | 状态迁移、业务不变量、序列化、取消、错误、fake 端口 | 真 SQLite/native/系统行为 |
| 数据与平台契约 | 真实驱动/迁移/事务、网络录件、JNI、进程和授权 | UI 与真实跨端使用 |
| UI + fake API | 全状态、用户动作、返回/旋转、主题/布局/读屏 | 后端正确性和持久化 |
| 集成/设备/跨端 | 同一构建真实端口、故障恢复、资源与数据往返 | 上述层间边界检测 |

每份证据关联 unit/card、提交、设备/语料、端口版本和命令；结构化日志含 taskId/correlationId、owner、状态/阶段与耗时，禁止 secrets、整段用户内容或裸凭据。无适用轨道时记录具体原因，不用空报告占位。

### 4.3 可执行架构门禁

S1.01 用 build-logic convention 和固定版本 detekt 建立 `verifyArchitecture`、`verifyComplexity`；Kotlin 源结构使用 Konsist 或 Kotlin PSI 做检查，不以文本 grep 冒充类型/依赖检测。S1.01 固定工具版本与选择理由，后续在同一入口扩展。每次新增模块/域必须更新独立的架构配置，不能修改 S0 冻结文件。

- 检查真实 Gradle 项目图、配置依赖和源码包边界：无环，UI 不依赖实现，domain/application 无 Android/Compose/SQL/HTTP/JNI，通用服务不反向导入 feature，装配只在 app。
- 核对真实下载AAR/SDK的minSdk、ABI和发布身份，不能仅在旧快照中复验平台合同。若发现冻结目标无法满足，阻断当前单元并登记具体依赖与可兼容版本，不静默抬高minSdk或改写S0合同；必要的平台变更另行经用户确认并记录新阶段决议。
- 项目自有生产代码默认上限：函数60有效行、类300有效行、文件500有效行、圈复杂度10、嵌套4层；构造注入超过8项必须拆分或证明单一职责。Compose 函数同样适用，不用大 lambda 或挪到 util 规避。
- 行数不是单一职责的充分条件。主审检查一个用例是否只有一个业务意图、错误/重试/取消所有权是否一致；禁止只有转发的碎片化 wrapper 和为通过计数而无意义拆函数。
- 生成源码、原字节 vendor、声明型 fixture 分别登记精确路径和来源，不能全局排除 `features/`、`integrations/` 或测试。自有适配和新增 vendor 补丁照常检查；合理例外须有符号、原因、实测指标、owner、测试和到期复审单元，不允许全文件 blanket suppress。
- 负例必须证明 UI 直引 data、domain 引 Android、跨域内部导入、循环依赖、全局 graph 获取以及超阈值函数会使 CI 失败；测试在独立 fixture 中构建，不污染真工程。
- S1.01 先验证当时存在的模块，不创建未来业务假实现来凑覆盖。S1.02 补进程装配测试，S1.03 补端口约束；每个新域自首次提交即纳入检查，所有阶段出口重跑全图。

`tools/planning/verify-plan.mjs` **只检查文档引用、覆盖与冻结哈希**，不是以上 Android 架构检查；S1.01已实现真实Gradle/PSI/detekt/Java AST门禁，入口及限制见 [S1 归档](../archive/handoff-s1.md) 的单元验收记录。

### 4.4 旧验证与未来工程共存

S0 比较器含“当前无 Gradle 工程”等阶段假设，不能在 S1 建工程后简单将失败跳过或重新签 S0。S1.01 增加独立阶段化验证入口：原 S0 工具/证据在绑定 `36511a1` 的临时只读基线快照中复验，当前 Kotlin/Gradle 工程执行新的阶段检查；两个结果分别记录。基线夹具、相邻冻结源码的路径需显式映射，完成后清理临时目录。

原 `npm run verify` 仍解释为 S0 快照验证；新阶段入口不得只检测旧快照来宣称 Android 正确。来源/SQL/wire 的只读不变检查同时作用于当前采用输入。实施该入口属于 S1.01 的构建/CI职责，不能为了它修改原锁、原成功日志或旧验收代码的哈希。

## 5. 分阶段责任与实施计划

以下每行**完整继承当前原计划对应单元的标题、前置、交付物和全部验收条件**；范围行只合并责任说明。S2.16–S2.18 是新增补修单元，S2.19–S2.31 映射原 S2.16–S2.28，其他编号不变；历史卡/场景通过修订映射查询冻结 `config/execution-plan.json`，历史证据保留旧 ID，新证据使用当前 ID 并登记来源。表内端口/模块是实现约束，不等于源码已完成。

### S1 正式工程与可安装纵切（27单元）

目标：从第一份源码保持 UI/后端可独立验证；最小实现进入正式所属模块，后续完善它，不另造第二套原型。公共前置仍为已签收 S0.G。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S1.01 | build-logic、平台/纯 Kotlin convention | app 最小 Compose 壳 | 建根工程、版本/来源校验、架构/复杂度负例及阶段验证入口；安装真实壳，禁止提前塞入业务 |
| S1.02 | app Main/Engine/IsolatedGraph | app 生命周期接线 | 分进程工厂与窄注入；子进程不打开业务库，Activity重建不重建应用服务 |
| S1.03 | core/common、各服务 api | 无页面（公开合同供后续 UI 使用） | ID/时钟/错误与S0.21至24 Kotlin字段合同；不建立聚合所有域模型的 core/model |
| S1.04 | platform/database | 无页面（驱动能力探针） | 真捆绑驱动测试，UI与业务不触及连接；错误从端口报告 |
| S1.05 | platform/database migration | 无页面（迁移 runner） | SQL加载/checksum/fingerprint 分离，逐字节golden和重启幂等 |
| S1.06 | notes backend/data、resources 服务 | 无页面（读写端口供S1.12消费） | 笔记用例、仓储与资源提交分工；真库CRUD/引用/重启测试 |
| S1.07 | platform/network | 无页面（传输协议端口） | 帧解码与HTTP生命周期分离，不含 provider/聊天业务；chunk/取消录件 |
| S1.08 | runtime api/backend/data | 无页面（任务/事件合同） | 任务状态机、lease、账本、事件/业务outbox分别实现；无 feature 反向依赖 |
| S1.09 | platform/background、DutyRegistry | 任务通知适配 | FGS只管理职责与系统生命周期；通知动作调用task端口，不直接执行业务 |
| S1.10 | runtime RecoveryCoordinator | 无页面（启动恢复用例） | 先业务事实再投影，恢复handler注册；重启故障矩阵不依赖 Activity |
| S1.11 | 设计资源生成器与冻结token比较 | core/design | 不把设置存储塞入Theme；纯资源生成与组件渲染分别验收 |
| S1.12 | notes命令/查询、导航合同 | notes/ui、app导航壳 | ViewModel只调用notes API；fake CRUD状态及真保存/重启双轨 |
| S1.13 | editor纯文档模型、parser/serializer | 无页面（文档内核） | SourceRange/保真/未知节点不依赖Compose；语料往返测试 |
| S1.14 | editor选择/命令/undo | editor输入适配 | IME是平台边界，选区/逆操作纯Kotlin；随机编辑与真实输入法分开测 |
| S1.15 | 内容消息/资源合同、browser会话API | 内容宿主、browser最小UI | HTML卡面与浏览器分宿主/权限；origin/nonce及关闭释放测试 |
| S1.16 | engines/pdf adapter | 无页面（PDF引擎供面板调用） | JNI/PFD/native句柄封装，文本/坐标/渲染契约和真实预算 |
| S1.17 | workspace状态机、草稿/关闭/快照，chat最小查询端口 | workspace四面板、薄chat流视图 | PanelHost注册，不在WorkspaceViewModel实现四域；JVM状态表、草稿重启、fake面板、真实四面板纵切 |
| S1.18 | engines/fsrs、review适配 | 无页面（数值与训练路线验证） | 闪卡/题库共用算法；调度、训练和字段映射分开，取消不写坏参数 |
| S1.19 | 脚本引擎adapter、受限工具端口 | 无页面（PTC最小执行合同） | 引擎/预算/工具授权/回执独立，不借脚本绕过运行时与授权 |
| S1.20 | terminal会话/PTY适配、Operit最小源码 | terminal/ui | ANSI渲染与进程I/O分离；UI不调用JNI/kill，真实PTY证据保持 |
| S1.21 | environments安装状态机/Recipe、PRoot适配 | 安装纵切UI | 下载、校验、解包、激活、包探测分工；UI观察任务，低空间/中断重入 |
| S1.22 | execution/Shizuku adapter | 授权与UID状态UI | Binder/授权/双流/进程owner分开；只读系统命令与撤权独立测试 |
| S1.23 | security端口、Keystore/KDF/DSBK adapter | 无页面（secret与加密合同） | 凭据、KDF、分块认证、暂存激活分开；错密/篡改和秘密扫描 |
| S1.24 | sync配置/codec、WebDAV adapter、identity | 配对/同步最小入口 | 配置表单不处理wire/密码；provider契约和secret错误传递 |
| S1.25 | sync发布/apply、notes DomainSyncAdapter | notes原页面与同步进度 | 真双向笔记/资产，用例提交后刷新查询；不是UI直接apply |
| S1.26 | 同一sync管线、加密adapter | 同一入口错误/重试态 | 明文/加密只替换编码端口；错密保留pending/游标，无第二套同步器 |
| S1.27 | benchmark和测量harness | 既有真实页面 | 按用户确认，以 iPA2475 与 V2232A 两台独立 arm64 真机跑通固定语料的启动、笔记帧与内存、终端和加密同步为准；不分档、不按性能数值或设备参数设签收门槛。保留 P50/P95、模块图、复杂度和编译/测试成本供诊断 |

**阶段出口 S1.G**：原27单元、九类纵切、真实明文/加密往返及设备/视觉证据全通过；另签收无环模块图、纯Kotlin业务测试、fake UI测试、进程初始化隔离和复杂度报告。S1.G校准剩余估算，不把本版拆分当作原2.5日粗估已经可靠。

### S2 数据、任务与权限底座（31单元）

目标：在已有服务中完善基础设施，不引入全能 DataManager/RuntimeManager。公共前置 S1.G。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S2.01–S2.02 | platform/database连接与迁移 | 无页面（数据库基础设施） | writer/读快照/事务与迁移各自所有者；真实锁等待/漂移/升级测试 |
| S2.03 | migration诊断查询/重试API | 维护页 | 页只展示报告/提交续作命令，不执行SQL或自行决定升级跳过 |
| S2.04–S2.05 | sync分类/捕获/无损codec | 无页面（采集合同） | registry校验、批次读取、wire映射分工；表域fixture统一契约 |
| S2.06–S2.08 | resources backend/data、storage适配 | SAF系统选择适配与进度端口 | 内容寻址、原子写、授权句柄、资产/墓碑/引用各自职责；不混入UI生命周期 |
| S2.09–S2.11 | backup DataSpace/Plan/Restore | 覆盖预览与恢复状态入口 | 规划为纯用例，staging/切槽为适配；每个故障点保证完整活动槽 |
| S2.12 | legacy导入adapter | 旧包导入报告 | 复用恢复服务和冻结旧包，不建立第二个切槽实现 |
| S2.13–S2.15 | security、backup、identity | 密钥失效/配对/接管入口 | KDF/codec、恢复、身份转移三份合同；普通restore与sidecar显式分流 |
| S2.16 | backup普通恢复、sync基线重建 | 复用恢复/重配入口 | 当前补修：参考桌面 resetSyncBaselineAfterRestore，对四个恢复库清理旧变更日志/冲突/别名并归一业务同步版本；只改候选槽、保留业务内容；重配原仓库不把旧 pending 当新修改重发，新仓库 seed 与普通恢复明确分开 |
| S2.17 | backup资源校验、resources引用合同 | 复用恢复预览/结果 | 当前补修：依据真实引用和 ref_count 校验附件，零引用且无真实引用的缺失 blob 不再整包拒绝；仍被文件/预览/压缩资源引用的缺失或 hash 错误在激活前失败；冻结桌面同包导出/导入与 Android 冷恢复对拍 |
| S2.18 | sync旧加密仓库配对/marker升级 | 配对页兼容/错误状态 | 当前补修：区别 DSBK v1 与 .encryption-marker v1，按冻结桌面验证密码后 claim 升级标记并加入原仓库；错密、损坏、竞争和中断不覆盖其他写者标记，不新建不兼容 wire |
| S2.19–S2.22 | runtime账本/lease/outbox/recovery | 无页面（任务基础端口） | 状态/所有权/取消/投影分别测试；业务完成仍由业务库权威记录 |
| S2.23–S2.25 | DutyRegistry、FGS/WorkManager/提醒适配 | 通知、常驻/电池页 | 系统停止/重建调用同一任务API，UI/Worker不能各自跑一份业务 |
| S2.26 | capability授权与审计服务 | 审批UI | 决策与展示分离，拒绝执行数0；旋转后从账本恢复待审批 |
| S2.27 | execution ShellRouter与四目标adapter | 身份选择/回执UI | 每目标独立executor，共同合同；实际UID/资源/进程owner核验 |
| S2.28 | storage DocumentsProvider、resources命令 | 系统文件入口 | Provider只转授权操作，不能获得任意治理文件/业务库访问权 |
| S2.29 | sync PublicationJournal/Publisher、加密会话与流式资源端口 | 无页面（发布基础） | 后续完善：本机确认键使用 database+change_log_id，pending 保留同批 key/seq/ciphertext，回读后确认；会话按 salt/KDF 参数复用派生密钥、对象 nonce 独立且结束清理；建立流式资源读取/暂存/取消合同，不改变桌面 wire |
| S2.30 | sync ApplyPipeline、DomainSyncAdapter/Asset/Cursor | 无页面（全域fixture与apply服务） | 后续完善：真实迁移行 fixture 覆盖全域，跨库各自事务幂等提交后才确认消费游标；按域注册，不建全域 when；通过流式资源端口落地、重放/取消/错误隔离及查询失效；增加恢复重配后的桌面↔Android真实笔记/附件往返 |
| S2.31 | core错误/日志合同、diagnostics导出 | 诊断页 | 各域贡献错误与脱敏报告，页面不读取各仓储内部；秘密扫描 |

**已知行为，不增设当前门槛**：S2.07 观察到的后台进程冻结本身不单列产品缺陷，不阻塞 S2.09。后台任务维持与恢复按 S2.22–S2.25 实施，设备和耗电矩阵按 S9.11 验证；实际功能失败仍按症状排查，[归类与复验边界](../archive/handoff-s2.md#s2-background-freezer-triage)。

**阶段出口 S2.G**：S2.01–S2.31（含新增三项兼容补修）及原数据/安全/任务/后台/权限/同步底座全签收；新增检查公共服务不导入领域实现、只有一个任务/授权权威、各adapter可被同一契约测试替换。基础端口冻结的是明确合同，不是一个暴露全部方法的“大接口”。

### S3 对话、模型与Agent（29单元）

目标：provider协议、聊天持久化、上下文、工具执行、Agent与工作流各有所有者。公共前置 S2.G。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S3.01 | chat api/backend/data | 无页面（聊天领域合同） | 复用最小聊天仓储；Session/Block/Variant映射与TurnSnapshot分离 |
| S3.02–S3.03 | providers API、chat回合状态机 | 无页面（流式用例） | provider不直接写chat库；TurnPipeline组合流、持久化和outbox |
| S3.04–S3.08 | 各provider协议adapter | 配置/错误所需状态端口 | 每provider原三张操作卡不合并，认证/参数/流映射独立，不共用巨大协议分支 |
| S3.09–S3.10 | SSE传输、chat块reduce/持久化 | 无页面（流正确性） | 帧边界与业务delta分离，随机切片/重复事件/合法重复原文测试 |
| S3.11 | usage与budget用例/data | 用量查询端口 | 用量账本/估算来源/预算策略分离，页面不计算权威费用 |
| S3.12–S3.14 | ContextCompiler、附件用例、tokenizer adapter | 附件/能力状态端口 | 上下文策略不依赖UI；资源上传/计数/裁剪均可独立取消与回放 |
| S3.15 | chat Variant/Branch/Edit状态机 | 变体操作合同 | 分支ID/OCC/active指针由用例控制，不由页面改库 |
| S3.16–S3.18 | tools注册/dispatcher、agent工具轮 | 审批/工具结果状态端口 | 注册描述符、执行、并行排序分别实现；Broker仍唯一裁决 |
| S3.19–S3.20 | workspace编辑提案、skills manifest/trust | 提案/依赖计划入口 | OCC与技能来源各自服务；Agent不绕过资源提交/本机信任 |
| S3.21–S3.23 | PTC adapter、Subagent/Goal状态机 | 目标/任务观察端口 | 分别负责物化、父子生命周期、续轮；不用AgentOrchestrator包办所有职责 |
| S3.24 | memory/compression用例、search API | 记忆/摘要查询入口 | 先FTS后正式检索adapter，调用端口不随S4重写；lineage与active提交一致 |
| S3.25 | workflow图/验证/节点/调度用例 | workflow编辑/执行UI | 保留原四子卡；纯图语义与UI树分开，headless与手动使用同一授权 |
| S3.26 | agent至terminal/environment API适配 | 用户接管状态 | 仅用窄工具端口，不import终端UI/实现，不复制安装器 |
| S3.27 | 既有chat命令/查询 | chat/ui | 列表/输入/模型/块/审批/变体分组件和状态域；fake UI后真实纵切 |
| S3.28–S3.29 | chat sync adapter、性能/故障harness | 既有聊天页 | 保留附件/变体/记忆跨端用例与预算；本机goal/lease不跨端执行 |

**阶段出口 S3.G**：原provider、工具轮、Agent、聊天与跨端证据全通过；检查chat/provider/agent/workflow无实现层互相引用，UI可在不启动模型/终端的情况下验证，所有真实工具仍受统一审批与取消。

### S4 文档、媒体、检索与学习引擎（32单元）

目标：引擎适配负责算力/句柄，领域用例负责业务，页面不调用native。公共前置 S3.G。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S4.01–S4.03 | engines独立构建/宿主/预算harness | 无页面（来源、端口与预算） | 每引擎来源/ABI/许可、独立句柄与取消；不共享全能EngineManager |
| S4.04–S4.06 | pdf adapter、documents批注用例/data | PDF阅读合同与既有面板 | 文本坐标/渲染缓存/批注分别所有者，UI不操控native地址 |
| S4.07–S4.09 | Office/EPUB adapters、documents用例 | 文档结果/错误端口 | 读取、生成、格式预算各格式测试；不把UI编辑状态放转换器 |
| S4.10–S4.13 | image/OCR adapters、OCR任务编排 | OCR进度/校验入口 | 解码、识别、页任务和恢复分离；真实损坏/取消/坐标预算 |
| S4.14–S4.16 | archive/protobuf、APKG adapter | 无页面（格式端口供S7向导） | 安全解包、集合解析、媒体写出分开，模板/资源合同完整保留 |
| S4.17–S4.20 | audio/VAD/ASR/TTS adapters、media用例 | 播放/转写状态端口 | PCM/分段/字幕/播放队列各自职责，计时事件调用冻结media合同 |
| S4.21–S4.22 | inference/embedding adapters、provider接线 | 模型安装/状态入口 | 安装、推理、向量计算独立；引擎不能决定UI或私自写领域库 |
| S4.23–S4.24 | search chunking/FTS | 无页面（索引/查询服务） | 抽取、分块、索引维护与排序分开，源资源API驱动变化 |
| S4.25–S4.27 | search ANN/generation、混合排序/恢复 | 搜索进度/结果查询 | adapter可替换；chat/memory只调用search API，重建不泄漏引擎类型 |
| S4.28–S4.30 | engines/fsrs模型/调度/训练 | 无页面（数值端口） | 闪卡/题库用同一调度器；训练不直接写卡库，固定向量/取消 |
| S4.31–S4.32 | review参数提交/重算、APKG调度映射 | 训练结果/重算查询合同 | 训练结果验证后由用例事务写回；互操作映射不成为第二套调度算法 |

**阶段出口 S4.G**：原每引擎/操作来源、golden、故障、预算齐全；附句柄/进程owner与关闭报告，确认 UI 无JNI依赖，算法可脱离Android验证，参数化成员无遗漏。

### S5 设计系统、编辑器与工作台（34单元）

目标：保持编辑器文档内核与UI独立；工作台管理面板，不接管各业务内部状态。公共前置 S4.G，原U0至U5分组顺序保留。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S5.01–S5.04 | token/文案/图标生成及主题配置API | core/design组件族 | ThemeController通过配置端口；控件不知业务/仓储，各状态与输入子卡保持 |
| S5.05–S5.06 | navigation/contribution合同 | 导航设置壳、Operit UI适配 | 目的地/可用性按owner注册，不造全页面when；无实现的路由不能伪装完成 |
| S5.07–S5.09 | editor文档/SourceRange、parser/serializer | 无页面（编辑内核） | 分语法规则与增量失效，不依赖Compose；保真/随机编辑/未知节点测试 |
| S5.10–S5.12 | editor选择/命令/undo | 剪贴板、IME、键盘适配 | 纯操作与平台输入转换分离；用户栈/Agent操作隔离，真实输入不替代随机内核测试 |
| S5.13–S5.16 | editor BlockSpec及各块编辑规则 | 按blockType的渲染/编辑组件 | 每块规范独立，不在单一EditorScreen中分发所有规则；完整原子卡 |
| S5.17–S5.19 | 内容消息/资源策略、各engine/block adapter | 内容宿主、聊天/生成式渲染器 | 原生动作回类型化用例，HTML用户样式保留；宿主/交互/预算分别测试 |
| S5.20–S5.21 | documents Session/Draft/Save用例与本机data | tab/dirty/保存状态UI | SaveCoordinator不管理布局；每文档独立会话，保存确认不覆盖新输入 |
| S5.22–S5.25 | notes OCC/History/Links、Agent提案用例 | 冲突/diff/历史/引用面板 | 页面不计算CAS或恢复策略；每条命令经原仓储/outbox，双端证据保持 |
| S5.26–S5.29 | workspace PanelRegistry/Activation/Close/Snapshot | 手机栈、平板编排、关闭提示 | 延续S1.17合同；状态机不持Composable/WebView，布局与业务草稿独立 |
| S5.30 | notes/documents/chat面板适配 | 各域公开PanelHost | workspace不import领域UI实现，由app组装注册；同资源激活/返回/关闭 |
| S5.31–S5.33 | 文案/输入策略纯辅助 | core/ui焦点/返回/无障碍/语言 | 不把业务快捷动作塞全局键盘处理器；字号/读屏/IME真实矩阵 |
| S5.34 | CommandRegistry、search API | 命令/搜索/最近入口 | 各域CommandSpec，UI只提交命令；后续域按owner绑定，不反向引用实现 |

**阶段出口 S5.G**：原设计、编辑、多文档、工作台和核心面板全部通过；另确认纯编辑内核可无UI回放、组件可fake渲染、面板注册无环、各域保存/关闭任务所有权清楚。大编辑器不通过拆文件后仍共享全局可变状态来规避检查。

### S6 终端、系统能力与扩展生态（44单元）

目标：移植成熟实现而非复刻上游巨型宿主；原T1至T7和所有真实设备/工具安装要求保留。公共前置 S5.G。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S6.01–S6.02 | terminal PTY/JNI构建、IoPump | 无页面（执行资源与字节I/O） | fork/读写/resize/close所有者明确，UI不持fd；16KiB来源检查 |
| S6.03 | ANSI模拟/输出模型 | terminal/ui Canvas与键盘 | 渲染消费稳定快照，输入走Session API；不在Composable里启动进程 |
| S6.04–S6.06 | terminal SessionRegistry/CommandTracker/OutputStore | 会话/历史/输出UI | 会话、OSC协议、输出存档各自实现，长输出有界，task归属统一 |
| S6.07–S6.08 | ProcessSupervisor、SSH/文件服务adapter | 远程连接/服务配置 | 本地/远程取消按owner，SSH不污染四本机target；不做全能TerminalManager |
| S6.09–S6.11 | environments Provisioner/Mount/Recipe/Install用例 | 依赖安装中心 | 下载/校验/激活、挂载、包事务分开；UI用进度API不拼shell |
| S6.12–S6.17 | 各包recipe、SourceManager适配、Repair用例 | 包列表/源/修复/重置UI | recipe数据化注册，依赖完整原子卡；每命令实际探测，用户文件保全 |
| S6.18–S6.20 | execution Shizuku连接/executor/审批适配 | 授权/身份/回执UI | 状态监听与进程执行分离，newProcess/PFD路径保留，共享Broker |
| S6.21–S6.24 | Root/SystemTools/UserService adapters | Root/系统操作及重授权入口 | 目标executor独立、固定握手、撤权/死亡统一合同；实际UID审计 |
| S6.25–S6.27 | DependencyResolver、安装运行协调、输入所有权 | 计划审批、共享会话接管 | 协调器调用已交付安装/执行API，不重写包管理或terminal状态 |
| S6.28–S6.30 | mcp Session/Transport/Auth/Stdio | MCP配置/认证/状态UI | transport/凭据/工具映射分开；stdout协议与stderr分离，UI不管子进程 |
| S6.31–S6.32 | script engines、isolated RPC服务 | 无页面（脚本与授权边界） | 预算/中断/服务端逐调用授权；主业务库不得在isolated初始化 |
| S6.33–S6.34 | plugins manifest/dependencies、贡献事件合同 | Compose DSL Renderer与槽适配 | 解析/验证不含UI；渲染只接受声明式树和受控事件，owner卸载全撤销 |
| S6.35 | plugins安装/更新/卸载/导出、secret/schedule端口 | 插件管理/配置UI | 内容digest重审，安装态/信任与portable data分开；生命周期逐项测试 |
| S6.36 | workshop状态机、提案/生成/验证/自检协调 | workshop提案/进度/诊断UI | 安装委托S6.35，模型委托provider，终端委托execution；重试/取消/回滚分步验收，禁止全流程巨函数 |
| S6.37–S6.38 | browser会话/DOM引用/文件桥 | browser/ui | 网站用户接管与自动化授权分离；UI不持共享任意WebView桥 |
| S6.39–S6.40 | resources Provider桥、浮窗生命周期适配 | 浮窗聊天/终端入口 | 复用同一会话/task；关闭窗口不隐式取消任务，职责/通知/锁独立释放 |
| S6.41–S6.42 | device Shower/PhoneAgent adapters | 接管/审批/状态入口 | 显示、截图、输入与服务连接分工；设备动作和browser DOM不混用 |
| S6.43–S6.44 | 各域PanelHost/Contribution生命周期 | browser/terminal/media/DSL面板 | app注册已有域宿主，workspace不新增生态业务分支；真实30分钟多面板 |

**阶段出口 S6.G**：原终端/依赖/四目标/MCP/包SDK/工坊/系统工具/面板证据全签收；额外检查vendor不反向依赖宿主、各移植adapter为窄接口、权限决策唯一、安装/执行/UI无混杂及所有服务/窗口/进程资源回收。

### S7 完整业务与全应用UI（38单元）

目标：页面调用已有引擎与新领域用例，不把仓储/算法粘入UI。公共前置 S6.G。以下每项仍需原页面操作、整页/面板、持久化、捕获、双端读回和主题证据。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S7.01–S7.04 | resources/notes查询、批量、历史/修复用例 | 资源库/链接/历史/资产页面 | 查询分页与写命令分离；页面不编SQL，所有写路径回原资源提交 |
| S7.05–S7.06 | documents转换/导入导出/批注用例 | 导入导出向导、阅读批注页 | 向导纯UI状态，转换和坐标调用S4 API；原格式/资源回环全部保留 |
| S7.07–S7.10 | cards牌组/卡片/模板/遮挡用例及data | 各自管理/编辑/预览组件 | 模板预览不获取原生执行权，遮罩坐标/多卡生成由用例维护 |
| S7.11 | cards导入批次/冲突、APKG adapter | APKG向导与报告 | 业务去重与格式解包分离，取消/重入保留卡与媒体 |
| S7.12–S7.15 | review会话/评分/配置/训练提交/统计 | 复习/选项/训练/计划页面 | 页面不实现FSRS；评分与日志同事务，本机会话不写portable执行态 |
| S7.16 | AnkiConnect client adapter、cards导出用例 | 连接/导出UI | 网络协议不进ViewModel；真实Anki端点与重复回执 |
| S7.17–S7.20 | questions导入/编辑/格式/组卷用例 | 裁题/题库/格式向导/组卷UI | OCR和图片坐标走S4端口，抽题固定随机源，题型/格式子卡不减少 |
| S7.21–S7.24 | questions作答/考试/批改/掌握度、review API | 练习/考试/批改/复习入口 | 计时锚、幂等交卷、rubric/EMA/FSRS分别所有者；无第二套题库调度 |
| S7.25–S7.27 | tasks任务/重复/提醒用例与data | 待办/规则/提醒UI | 规则生成纯Kotlin，调度用系统adapter；通知动作同一命令 |
| S7.28–S7.29 | focus计时会话、生产力事件聚合 | 专注/台账/统计UI | 时间锚/计数在后端，UI计时仅显示；两端事件去重一致 |
| S7.30 | 各域Trash API、回收站聚合用例 | 统一回收站 | 聚合器只分派域命令，不能直接改所有表；引用/保留由域负责 |
| S7.31–S7.32 | media学习事件、translation用例 | 播放/字幕/翻译对照页 | 源文/译文/资源回链有明确owner；复用provider/音频API |
| S7.33–S7.34 | insights/home只读聚合与命令路由 | 洞察/周报/今日卡片 | 指标可追溯，聚合不跨域直查表；卡片动作调用域命令 |
| S7.35 | 各域SettingSpec与配置API | 设置分区与字段组件 | 573使用点/portable20/local503/secret50语义继承，不做巨型SettingsViewModel/when |
| S7.36 | plugins数据查询/清除/配额用例 | 插件/技能数据页 | 不暴露裸DB/secret；清除走owner墓碑，远端数据不授予执行 |
| S7.37 | 每包PluginHostApi适配/数据迁移 | 各包UI贡献/宿主 | 53包160原卡完整保留；每包内部区分data、UI、更新撤销、跨端测试，common-setup仅公共harness |
| S7.38 | 集成/性能/来源harness | 所有已交付页面 | 全appId和三档设备矩阵；检查新增页面仍满足层级/复杂度/注册释放 |

**阶段出口 S7.G**：原七条业务闭环、逐包迁移、全应用UI矩阵及M3条件不减；检查聚合域只通过公开API读取，设置/工具/面板没有全域分支管理器，任何新业务写路径均有后端、UI和双端证据。

### S8 同步与备份恢复硬化（26单元）

目标：同步、备份、身份共享必要低层原语但保持独立用例/状态机，避免一个 InterchangeManager 控制全部数据生命周期。公共前置 S7.G。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S8.01–S8.05 | sync codecs、WebDAV/S3/FTP adapters、仓库检查 | 无页面（协议与provider合同） | 每协议独立实现及公共 conformance；S8.02 接 S2.29–S2.30 流式资源端口验证真实大对象、Range/断流/取消，不靠扩大 ByteArray 上限；不把认证/分页/文件协议塞上传循环 |
| S8.06–S8.07 | PublicationJournal/Publisher、ApplyPipeline/Cursor | 无页面（同步事务） | 预留/上传/回读/确认与下载/提交/游标各检查点，wire原样 |
| S8.08–S8.09 | MergePolicy/ConflictDecision、Tombstone水位 | 冲突查询/决定API | 决策是后端用例，不由UI时间戳排序；回声/删除/恢复原golden |
| S8.10–S8.13 | resources资产传输/配额、sync重试/故障harness | 无页面（资源与恢复硬化） | 流式大对象传输、去重/低空间/暂存清理、弱网/取消与故障重入；路径/hash、重试和提交 owner 分离，内存有界且复用加密会话；加密 nonce/对象键和冻结协议不变 |
| S8.14 | sync查询与命令API | sync-center/ui | 页面只观察进度/提交重试或冲突决定，fake全状态与真实provider双轨 |
| S8.15–S8.17 | 各域sync adapter、版本校验、identity/pairing | 域状态/升级/配对入口 | 逐域原操作卡与真实版本矩阵；S8.17 默认换机为恢复业务+新 writer+重配原 instance，延续 S2.16/S2.18 的基线与旧标记合同；secret/设备身份不进普通偏好，不把管理员授权作为普通换机门槛 |
| S8.18–S8.20 | backup Coverage/Writer/RestorePlan/Activator | 覆盖/恢复结果API | 一致性快照、打包、加密、staging/激活分别负责；S8.19–S8.20 完成 GB 级备份/恢复的内存有界、进度/取消/暂存与双端内容验证，低层 crypto 复用 |
| S8.21–S8.24 | legacy-v1 adapter、identity sidecar、backup调度/故障 | 转换/接管/自动备份配置 | S8.21 将已有 v1 开发转换工具产品化；S8.22 只为确需延续原序列的高级迁移提供 sidecar 托管移交，旧写者永久撤权是既定前置，claim 不冒充停止冻结桌面的机制；普通换机不依赖此流程。预算、移交、保留策略分别测，不另建切槽/任务体系 |
| S8.25 | backup命令/查询 | backup-center/ui | 手机/平板向导+fake错误/确认，真实导出恢复和原读屏/预算 |
| S8.26 | 同步报告与备份报告的独立harness | 原中心页面 | 两份报告分别结果/证据/隐私扫描；一种通过不能代替另一种 |

**阶段出口 S8.G**：原全域同步与备份恢复两份报告独立签收；检查sync和backup没有互相调用内部实现，二者复用的资源/加密/身份合同可单独测试，UI仍不拥有事务与恢复策略。

### S9 发布与替换（19单元）

目标：绑定同一候选提交/构建，验证功能、架构、数据、系统及发布。公共前置 S8.G，不新增产品需求或推进冻结上游。

| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |
| --- | --- | --- | --- |
| S9.01–S9.02 | 可追溯/功能/工具/包harness | 全部正式入口 | 原场景证据加模块/API/架构报告索引，未实现或豁免项不能计通过 |
| S9.03–S9.05 | 资源来源与状态fixture | 视觉、设备、输入/无障碍矩阵 | 同一候选验证全部主题/字号/尺寸，fake快照不能代替真机流程 |
| S9.06–S9.09 | golden/同步/provider/身份/故障harness | 真实跨端工作流 | 冻结候选证据；全域连续使用与故障独立检查，不改S0黄金输入求通过 |
| S9.10–S9.12 | execution/runtime/性能harness | 终端、后台、全应用性能 | 四target/长跑/OEM/容量全要求保留，按责任层定位修复后重验 |
| S9.13–S9.14 | native来源、许可/SBOM、架构/产物检查 | 品牌/权限/组件入口核验 | 关闭3项16KiB与26许可阻断；核验最终依赖图/例外清单/DEX/旧链排除 |
| S9.15–S9.16 | release身份、升级/恢复演练 | 导入/恢复/继续同步流程 | 真实签名版本/原位升级/旧数据保全；本版不降低旧包和sidecar要求 |
| S9.17–S9.19 | 发布流水线、源码/来源/维护归档 | 帮助/迁移/关于与正式发布 | 受控放量/更高版本前向修复、模块补丁重放、旧Android链退役；桌面冻结来源不推进 |

**阶段出口 S9.G**：原M4发布、功能/视觉/设备/升级/数据接管和旧链退役全部满足；候选构建附完整架构与复杂度报告、无过期例外、来源与许可证据。不得仅因模块图合规而豁免行为、设备或跨端失败。

## 6. 高风险复合单元的内部拆分

以下是既有单元内部责任，不是新单元或新代理跨单元授权。

| 场景 | 必须独立的职责 | 集成证明 |
| --- | --- | --- |
| S1.17 四面板 | PanelKey/ResourceKey、WorkspaceSession、DraftStore/Save、CloseGuard/Coordinator、LayoutSnapshotCodec、PanelHostRegistry；chat流视图仅调用最小chat端口 | 状态表/快照往返、草稿重启、fake面板、真实PDF/笔记/chat/browser；不提前实现完整S3 |
| S2.30 全域apply | SyncApplyPipeline、DomainSyncAdapter注册、MergePolicy、AssetMaterializer、CursorStore、查询失效事件 | 全域迁移后行fixture、重放、FK/整数/未知字段/失败切点；正式写用例随所属业务单元接入 |
| S3 回合与Agent | ChatTurnPipeline、ProviderAdapter、ContextCompiler、UsageRecorder、ToolRoundRunner、Subagent/Goal、Memory/Compression、WorkflowGraph/Executor | 每状态机独立取消/预算/错误；一条完整真实工具回合与聊天同步 |
| S6.36 工坊 | WorkshopStateMachine、Proposal/Generator、ArtifactValidator、SecretScanner、SandboxSelfTest、Installation API client、Config/Update/Export用例 | 全流程可checkpoint；内容变化重审，安装失败/自检失败/缩减重试/取消；full trust不授予设备执行权 |
| S7.35 设置 | 各域SettingSpec、校验/配置用例、便携范围映射、UI字段组件、壳注册 | 每setting原子卡persist-effect/scope-sync，secret与本机信任不泄漏 |
| S7.37 逐包迁移 | 每包manifest/API map、data迁移、UI贡献、更新撤销、跨端适配；公共harness只检查合同 | 30 host+19 official UI+4 examples逐包；无页面包说明原因，有UI包实际交互/截图 |

## 7. 排期、记录与验证入口

当前 305 主单元及 10 阶段出口为唯一执行顺序，本版提供 S1–S9 的 280 项分层责任映射；原 302 单元队列只作历史来源，不以同名旧 ID 覆盖新增补修。所有原参数化成员及操作经编号映射继承，跨阶段依赖同步顺延；S1 九类纵切与 M1 至 M4 条件继续执行，三项补修未签收前不进入新 S2.19。

原剩余1354.5工作日是单通道粗模型，不是承诺，不能据当前最多3并发简单除以3，也不能把新架构测试视为零成本。S1.G依据真实模块/纵切/设备耗时校准余下安排；新的估算与执行状态保存在阶段记录或后续版本化排期中，不覆写S0.25已签快照，不伪造S0实际耗时。

交接按阶段维护：S0 与 S1 按原字节归档；S2 在修正迁移后的链接、追加补测结论后封存。文档根仅保留当前阶段交接 [S3](../handoff-s3.md)，按准确 Asia/Shanghai 系统时间追加；活动计划入口见[文档目录](../README.md)。

本轮及后续计划编辑后的文档核验：

```bash
node --test tools/planning/verify-plan.test.mjs
node tools/planning/verify-plan.mjs
node tools/planning/verify-plan.mjs --query S2.16
```

它们验证当前 305 主单元/315 队列与 280 分层单元/9 出口的有序唯一覆盖、三项新增补修及旧编号映射、跨阶段依赖和阶段出口、非空分层责任；另验证历史 302/10/312/1774/3198 计数、固定 Git 原计划与其余冻结引用哈希、accepted M0 回执、S0/S1 归档及当前交接。文档校验不代替 Android 架构、Gradle 或设备验收。

历史 S0 比较器在固定快照中回放；当前工作区统一使用：

```bash
npm run verify
node tools/s1/frozen-inputs.mjs
```

使用新日志文件记录每轮结果，不覆盖S0.G绑定的固定成功日志；禁止运行capture刷新冻结输入。S1之后按第4.4节分别验证历史快照和当前工程；`npm run verify`已接只读S0快照，`npm run verify:s1`检查当前源码与产物，设备测试另跑。

## 8. 实施进度

本节是当前编号的进度展示，不是第二份验收队列。冻结 302 单元/312 队列与历史证据保持原字节；当前新增及顺延按修订映射执行。S1 证据索引保存在 [S1 交接归档](../archive/handoff-s1.md) 及 [S1 回执](../../config/stages/s1-progress.json)，S2 见 [S2 交接归档](../archive/handoff-s2.md) 和 [S2 回执](../../config/stages/s2-progress.json)。阶段签收以对应回执为准。S1.G（M1）与 S2.G（M2）均已由主线程独立签收。

| 单元 | 状态 | 主线程证据 |
| --- | --- | --- |
| S1.01 | accepted | 干净Compose APK、58 AAR、设备启动、16工具测试、真实架构/复杂度与只读S0回放；handoff-s1 2026-11-16T05:46:50+08:00 |
| S1.02 | accepted | 33 JVM、4真机角色/重建、全工具check、日志隐私；handoff-s1 2026-11-16T06:04:10+08:00 |
| S1.03 | accepted | 9纯Kotlin模块、84测试、S0.21–24精确fixture、10模块门禁零违规；handoff-s1 2026-11-16T06:20:19+08:00 |
| S1.04 | accepted | SQLite3.50.1/19类能力、19 JVM+6设备、最终APK native/身份、S0回放；handoff-s1 2026-11-16T06:28:10+08:00 |
| S1.05 | accepted | 138 SQL原字节/SipHash、四库fingerprint、重启0迁移、15新JVM+19驱动回归/9设备、72任务干净索引候选；handoff-s1 2026-11-16T07:12:00+08:00 |
| S1.06 | accepted | 笔记/附件/typed引用、18 JVM+33 app回归、真实组合/外置附件/force-stop重读、114任务干净候选；handoff-s1 2026-11-16T07:38:44+08:00 |
| S1.07 | accepted | HTTP/SSE23 JVM+2设备、socket关闭/20次取消竞争、17模块/122文件干净候选、APK/AAR检查；handoff-s1 2026-11-16T07:46:30+08:00 |
| S1.08 | accepted | 本机状态/lease/事件/同库业务事实、20新增核心测试+2设备、213任务干净候选/19模块141文件；handoff-s1 2026-11-16T08:01:59+08:00 |
| S1.09 | accepted | 11职责JVM、3通知设备+真实双传输/Activity销毁/定向停止/最后职责退出、233任务干净候选；handoff-s1 2026-11-16T08:14:23+08:00 |
| S1.10 | accepted | 恢复14+3故障测试/锁回归、真实fact与部分文本force-stop恢复、243全工程JVM/39工具/17门禁、20模块168文件；handoff-s1 2026-11-16T08:38:03+08:00 |
| S1.11 | accepted | 20x812浏览器token重算/329色与195数值设备对拍、6真机/8工具/18构建门禁、亮暗board、248任务干净候选/生成源码全扫描；handoff-s1 2026-11-16T09:37:33+08:00 |
| S1.12 | accepted | Notes API真CRUD导航、8 JVM/5 fake UI设备、force-stop真UI保存读回2步、手机平板亮暗与桌面对照、300任务干净候选；handoff-s1 2026-11-16T09:59:59+08:00 |
| S1.13 | accepted | GFM源码快照/UTF16范围/根块安全替换、9+150冻结语料字节往返、14 JVM、26任务干净候选/25模块零违规；handoff-s1 2026-11-16T10:04:21+08:00 |
| S1.14 | accepted | 合成事务/字素/跨块/undo、7输入+1会话JVM、3真实InputConnection+1 fake UI、329任务干净候选；OEM候选矩阵未过留S5.11；handoff-s1 2026-11-16T11:18:01+08:00 |
| S1.15 | accepted | 内容/浏览器独立8模块、5 JVM/1资产Node、3内容+1登录历史+2 fake UI+1 app组合设备、498任务干净候选；handoff-s1 2026-11-16T11:44:16+08:00 |
| S1.16 | accepted | 固定PDFium源码974目标/API26/JNI/纯服务合同、3 JVM/18真机、原生资源预算与桌面坐标对拍、434任务干净候选；handoff-s1 2026-11-16T11:56:14+08:00 |
| S1.17 | accepted | 纯workspace状态/原子草稿与快照、16 JVM、真实四面板/流式任务/定向取消、9设备及force-stop、1769任务干净候选；handoff-s1 2026-11-17T00:43:35+08:00 |
| S1.18 | accepted | 固定fsrs6.6.2 JNI、20 JVM/5设备、桌面四档与128训练样本对拍、取消/CAS、542任务干净候选；handoff-s1 2026-11-17T01:17:46+08:00 |
| S1.19 | accepted | 固定QuickJS源码、17 JVM/12设备、两工具/授权/预算/取消事实与恢复、625任务干净候选；handoff-s1 2026-11-17T01:32:00+08:00 |
| S1.20 | accepted | 固定Operit PTY/ANSI来源、13 JVM/7设备、双会话/resize/Ctrl+C/退出码/fd与入口重建、722任务干净候选；handoff-s1 2026-11-17T02:09:55+08:00 |

| S1.21 | accepted | 固定源码PRoot/talloc、rootfs/Git/venv真实冷安装、60 JVM/11设备、2554任务干净候选；HTTPS只有协议/fake证据不冒充真下载；handoff-s1 2026-11-18T00:07:51+08:00 |
| S1.22 | accepted | 固定SDK/Sui授权协议、真实UID0与只读id/pm、49+5 JVM/19设备、835任务最终干净候选；handoff-s1 2026-11-18T00:38:31+08:00 |
| S1.23 | accepted | 真实Keystore/生产Argon2id与DSBK双向对拍、61专属/533全工程JVM、平台8/app9设备、2812任务干净候选；handoff-s1 2026-11-18T00:45:23+08:00 |
| S1.24 | accepted | WebDAV真实桌面/Android互见、Keystore重开、无损黄金字节、14设备与902任务独立候选；handoff-s1 2026-11-18T07:41:19+08:00 |
| S1.25 | accepted | 真实明文笔记/双向外置附件、删除恢复与零回声、621全工程JVM、73模块零违规及最终设备候选；handoff-s1 2026-11-18T13:03:06+08:00 |
| S1.26 | accepted | 同一同步管线与真实 DSBK，错密零远端写／安全点不动／同密文重试，明文及加密 Android↔桌面双向验收，634 JVM 与 20 项真机验证；handoff-s1 2026-11-18T18:14:57+08:00 |
| S1.27 | accepted（双真机功能） | iPA2475 和 V2232A 实际启动、笔记、终端、桌面加密双向同步通过；不要求设备分档、性能预算或完整量测；原手机正式启动耗时缺测及一次同步采样失败公开保留，后续独立复跑加密同步 30/30；handoff-s1 2026-11-18T19:28:23+08:00 |
| S1.G | accepted（M1） | 同一普通 APK 两台 ARM64 真机、九类纵切、冻结桌面明文及加密往返、亮暗视觉、独立架构/复杂度/JVM/fake UI/进程隔离与排期回执；handoff-s1 2026-11-18T20:54:27+08:00 |
| S2.01 | accepted（双真机） | 单写者、WAL 快照、锁等待及关闭 FD 回基线；655 JVM 首审，S2.02 同包双真机回归；[S2 回执](../../config/stages/s2-progress.json) |
| S2.02 | accepted（双真机） | 每份迁移与账本原子提交，四库新装/重开、真实桌面旧 VFS 升级及漂移定位；661 JVM、两机各 28 项；[S2 回执](../../config/stages/s2-progress.json) |
| S2.03 | accepted（双真机） | 启动 VFS 迁移维护、失败定位与显式续作；668 JVM，两机每台 144 个硬终止恢复点和 30 项回归全过；[S2 回执](../../config/stages/s2-progress.json) |
| S2.04 | accepted（双真机） | 原冻结输入未改；新增共用 20261120 迁移改用 OLD.id；双机同候选 183/183 操作探针及各 146 个迁移硬终止恢复点通过；[S2 回执](../../config/stages/s2-progress.json) |
| S2.05 | accepted（双真机） | 同候选双机各通过 18 条桌面变更及 v3/v4 对拍、明文和加密 WebDAV 往返；对拍边界见 S2 交接；[S2 回执](../../config/stages/s2-progress.json) |
| S2.06 | accepted（双真机） | 729 JVM；两台各 18 项、明文/加密 WebDAV 同候选全过；验证真实写入与损坏拒绝日志；[S2 回执](../../config/stages/s2-progress.json) |
| S2.07 | accepted（双真机） | 764 JVM；V2232A 与 iPA2475 同候选各 36/36，含跨 UID 授权、真实 DocumentsUI、本机和 HTTP 往返；[S2 回执](../../config/stages/s2-progress.json) |
| S2.08 | accepted（双真机） | 788 JVM；V2232A 与 iPA2475 同候选各 7 项资产台账与 2 项 S2.07 回归通过；[S2 回执](../../config/stages/s2-progress.json) |
| S2.09 | accepted（双真机） | 802 JVM；iPA2475 与 V2232A 同候选各 22 项及 7 个硬终止重开通过，旧槽保留；[S2 回执](../../config/stages/s2-progress.json) |
| S2.10 | accepted（双真机） | 826 JVM；V2232A 与 iPA2475 同候选各 6 项只读 v3 域预检和既有活动槽回归；[S2 回执](../../config/stages/s2-progress.json) |
| S2.11 | accepted（双真机） | 836 JVM；iPA2475 与 V2232A 同候选各 9 项、3 个恢复硬终止重开，原活动数据与旧槽保全；[S2 回执](../../config/stages/s2-progress.json) |
| S2.12 | accepted（双真机） | 853 JVM；iPA2475 与 V2232A 同候选各 19 项及 3 个共享恢复硬终止重入通过；两份冻结旧包四库与附件回读，确认前和验证失败均保留原活动槽；[S2 回执](../../config/stages/s2-progress.json) |
| S2.13 | accepted（双真机） | 868 JVM；冻结桌面 DSBK v1/v2 对拍；V2232A 与 iPA2475 同候选各 23 项、零跳过，密钥丢失后确认重配，错密、换块、截断不提交；[S2 回执](../../config/stages/s2-progress.json) |
| S2.14 | accepted（双真机） | 899 JVM；iPA2475 与 V2232A 同补审候选各 28/28、零跳过；桌面 v1 ZIP 完整转换 v2、Android 冷恢复与跨端 ZIP 导入内容对拍、4 条认证至切槽关联、9 项加密页；未运行桌面整槽激活；[S2 回执](../../config/stages/s2-progress.json) |
| S2.15 | accepted（双真机） | 1013 JVM；iPA2475 与 V2232A 同补审候选各 45/45、零跳过；普通恢复重新配对、受控 WebDAV 续发 5→6、重复序列拒绝、claim 后过期或未知结果保留证据；不含用户生产仓库；[S2 回执](../../config/stages/s2-progress.json) |
| S2.16 | accepted（双真机） | 同候选双机各 3/3；四库候选基线、失败重入、同实例换写者及冻结桌面笔记往返；[S2 回执](../../config/stages/s2-progress.json) |
| S2.17 | accepted（双真机） | 同候选双机各 6/6；冻结桌面导入与真机恢复，零引用残留放行、引用缺失或损坏切槽前拒绝；[S2 回执](../../config/stages/s2-progress.json) |
| S2.18 | accepted（双真机） | 同候选双机各 10/10；旧标记升级、错密、损坏、竞争、取消、回读不确定及既有 v2；冻结桌面验回标记；[S2 回执](../../config/stages/s2-progress.json) |
| S2.19–S2.31 | accepted | 任务账本与后台、审批和执行、文件撤权、同步发布及应用、脱敏诊断的逐单元结果见 [S2 回执](../../config/stages/s2-progress.json)。 |
| S2.G | accepted（M2） | 31 个单元回执和 124 条证据引用通过复核，本机 1,231 项通过，同封存包两台设备各通过 6 项。主线程于 2026-11-25T16:24:59+08:00 签收，详见 [S2 签收记录](../archive/handoff-s2.md#s2-g-signoff)和[补测闭环](../archive/handoff-s2.md#s2-final-test-coverage)。 |
