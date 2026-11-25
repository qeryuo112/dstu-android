# dstu-node 安卓版：Kotlin 重写、桌面同步与 Operit 源码移植计划

- 日期：2026-11-15；上游差异修订：2026-11-16；当前计划修订：2026-11-23（S2.13–S2.15 桌面兼容审查补修与后续设计分流）
- 产品身份与总目标：dstu-android 本质上是 dstu-node 安卓部分的原生重构，最终交付具有完整冻结 dstu-node 桌面功能的 Android 原生端，不是精简版或伴侣端；必须与原冻结桌面保持业务语义、共享数据 schema、同步 wire、备份恢复、产品术语和视觉语言兼容。平台差异通过原生适配实现，不擅自删减桌面功能。
- 平台：Android 手机、平板、折叠屏。
- 技术路线：Kotlin 业务与系统服务、Jetpack Compose 官方界面、原生 SQLite 数据层。
- 源码策略：dstu-node 冻结桌面版是业务、数据、同步与视觉基线，Operit 冻结源码是原生实现和系统增强来源；每个新单元/功能开工前，主线程和专属子智能体先读两侧相关实现、调用链和测试，记录源码路径、关键合同、复用方式与差异再开发。某一侧没有对应实现时记录搜索范围与相关组件，不臆造对应功能；不把 Operit 本机备份当作 dstu 多端同步协议。移植模块统一接入 DstuTheme、导航、任务、权限和数据体系。
- dstu 冻结基线：`e6b2657fd59014a73f5bbb1d14c2db732cf23e87`（0.20.1）；本次同步后不再跟进上游，仅以该提交的桌面功能为重写目标。旧基线 `145bb3879164c58d1cd5ff39daa94a3daa9fe874` 保留作兼容样本来源。完整文件差异及受影响单元见 `config/upstream-impact.json`。
- Operit 基线：`7a39941bad80209973c535046a18195c5da9e647`；terminal 子模块：`e4646680663789002b43bbfffa6e69e65c35e614`。
- 项目目录：`dstu-android/`，与 `dstu-node/`、`Operit/` 同级。
- 开发方式：单验收队列与阶段门禁；同阶段独立单元可由专属子智能体并发，最多3个，按前置依赖集成、验证、交接和提交。
- 路径约定：桌面源码路径以相邻 `dstu-node/` 为根；`Operit/...` 指工作区内的 Operit 仓库；新应用目录以 `dstu-android/` 为根。

## 1. 交付范围与源码策略

### 1.1 最终交付

交付完整冻结桌面功能的 dstu-node Android 原生端：桌面与Android访问同一个同步仓库，双向同步业务记录和关联资源；两端保持相同的品牌、术语、信息架构和视觉风格。手机/平板使用适配尺寸与触控的原生布局，并增加以下正式能力：

1. 内置交互终端、多会话、后台命令与任务输出。
2. Ubuntu/PRoot 用户态环境和依赖安装中心。
3. Shizuku Android 系统执行层、Root 执行层和显式执行身份选择。
4. Agent 使用终端、安装依赖、调用本地 stdio MCP 的完整链路。
5. 前台服务自动保活、用户后台常驻、任务通知、浮窗、电池设置引导与重启恢复。
6. Operit 工具包、脚本、浏览器、语音、工作流等优秀实现的源码接入。

官方业务和官方页面统一由Kotlin/Compose承载。桌面端继续使用原技术栈，本计划以冻结提交的桌面功能、数据与设计为目标；不跟进此提交之后的上游功能。上游自带 Android/Capacitor/mobile 运行链不纳入采用或新增需求，只保留已有旧数据出口样本与导入兼容合同。项目自身桌面同步增量、schema及设计资源按冻结输入协调并回归。不采用上游旧 Android 运行链不等于缩减桌面功能；当前阶段的功能切片不构成最终交付删减。

三项发布合同：

1. **数据连续**：桌面创建的内容在Android可读、可编辑，Android修改同步回桌面，关联资源与删除/恢复状态一致。
2. **体验一致**：同名功能使用相同术语、图标语义、颜色/字阶/组件体系和业务流程；手机和平板按窗口尺寸重新编排。
3. **能力整合**：Operit终端、Shizuku、依赖安装、浮窗和脚本能力进入dstu工作台与设置体系，共用DstuTheme和权限/任务模型。

### 1.2 三类来源

| 来源 | 处理方式 | 交付位置 |
| --- | --- | --- |
| dstu 现有业务源码 | 提炼领域规则、数据格式和测试样本，编写 Kotlin 实现 | `core/`、`features/` |
| dstu 设计与文案资源 | 提取解析后的设计令牌、Logo/图标、导航和术语，生成Compose资源与组件规范 | `core/design/`、`config/design/`、资源生成工具 |
| Operit 优秀源码模块 | 固定提交直接引入，保留原结构，增加适配器和必要补丁 | `third_party/operit/`、`integrations/operit/` |
| 第三方引擎和运行资源 | 固定版本、来源、哈希、许可证和构建方式 | `engines/`、依赖清单、运行环境目录 |

构建排除清单：dstu 旧 React 页面、Milkdown/Crepe、旧 UI Kernel、TypeScript Host、Cordis、Tauri/Electron 桥、Capacitor、旧 Worker、SQLite WASM/OPFS 业务运行时、旧 dist/APK/AAR、旧 F7 WASM、桌面 `.node` 包。

源码移植以Operit模块为边界；新应用通过Kotlin接口连接模块。包名、资源、日志、文件根、任务归属和权限入口在适配层统一。移植页面、终端、安装中心、浮窗、Compose DSL均使用dstu配色、字体、图标、术语和组件外观；产品名称、关于页、导航与帮助入口使用dstu-node品牌与组织方式。

### 1.3 专用运行组件

| 组件 | 职责 | 与应用的接口 |
| --- | --- | --- |
| 内容 WebView | HTML 卡面、工件、公式/图表等专用内容 | 结构化内容、授权资源、有限交互消息 |
| 内置浏览器 | 网站、登录、下载、DOM/ref 操作 | BrowserSession、用户接管、下载与导航事件 |
| QuickJS/ToolPkg | 工具包、脚本、声明式 Compose 扩展 | CapabilityBroker、ToolDispatcher、UI 贡献接口 |
| CLI 环境 | Node/Python 等工具、包管理、本地 MCP | TerminalSession、ExecutionTarget、进程与文件授权 |

应用导航、编辑器主体、业务仓储和模型编排由 Kotlin 直接执行。Node/Python 作为终端内的用户工具安装，应用内核独立于 CLI 环境。

## 2. 完整功能矩阵

### 2.1 业务领域

| 领域 | 完整交付内容 |
| --- | --- |
| 聊天与模型 | 提供商、自定义端点、思考/文本/工具流、并行模型、变体、编辑/重试/分支、历史附件、用量与预算 |
| Agent | 工具循环、审批、计划、技能、PTC、工作区、子任务、目标、记忆、压缩与自动化 |
| 资源与笔记 | 文件/文件夹、标签、富文本、双链、历史、模板、画布/导图/图谱、导入导出 |
| 文档与检索 | PDF/Office/EPUB、OCR、图片、批注、原文回链、词法/向量/混合检索 |
| 制卡与复习 | 卡片/牌组/模板、遮挡、APKG、AnkiConnect、FSRS 调度与训练、复习计划 |
| 题库与洞察 | 解析/裁剪、组卷、练习/考试、批改/改判、错题、作文、掌握度、学习洞察 |
| 媒体与翻译 | 录音、语音输入、转写、字幕、播放进度、TTS、媒体源与翻译 |
| 生产力 | 待办/子任务/重复规则、提醒、番茄/专注、自动化、台账、统计 |
| 首页与今日 | 仪表盘卡片布局、今日学习、到期错题复习、周报、快捷入口与小组件数据源 |
| 终端与依赖 | PTY、多会话、后台执行、Ubuntu/PRoot、包管理、镜像源、开发工具链 |
| Android 系统能力 | Shizuku、Root、权限中心、系统命令、文件授权、设备操作 |
| 扩展生态 | MCP、本地 stdio、浏览器、ToolPkg、插件安装/更新/卸载、SDK、工坊 |
| 数据管理 | 同步、冲突、墓碑、资产、加密、备份、恢复、设备/实例身份 |
| 工作台 | 多文档/多任务、草稿、布局快照、手机面板栈、平板多栏/平铺 |
| 应用系统 | 设置、主题、语言、快捷操作、搜索、通知、后台常驻、诊断、更新与升级 |

### 2.2 清点依据

阶段 S0 交叉清点以下来源，生成场景矩阵：

- `src/ui-plugins/official/shell/index.ts:14` 的全部视图。
- `src/features/workbench/apps/` 的注册应用和资源打开/关闭/挂起协议。
- settings section、命令面板、深链接、菜单、Dock、状态栏和选区工具条。
- `host/plugins/*` 的业务、工具和后台任务，以及 `contracts/ipc` 的数据形状。
- 聊天块、生成式组件、Markdown 方言、HTML 卡面/工件、公式、mermaid、vega-lite、dot、abc、wavedrom、molecule-2d/3d、geojson。
- Operit 终端、权限、脚本、工作流、浏览器、语音、浮窗和本地推理能力。

每行记录：场景 ID、来源证据、Kotlin 模块、Compose 入口、数据/协议、执行目标、权限/引擎、正常/异常用例、负责人、交付阶段和验收结果。开发专用视图沿用相应发布条件。

## 3. Operit 源码采用清单

### 3.1 首批直接移植

| 能力 | 源码位置 | 移植对象 | 整合工作 |
| --- | --- | --- | --- |
| 终端内核 | `Operit/terminal/src/main/java/com/ai/assistance/operit/terminal/` | TerminalManager、SessionManager、SessionInputRouter、Pty/PtyIo/PtyChildProcess、TrackedCommandProtocol、LocalTerminalProvider | AppGraph 注入、独立文件根、TaskSupervisor 归属、统一日志和取消 |
| 终端界面 | 同模块 `view/`、`ui/`、`TerminalEnv.kt` | ANSI 模拟器、Canvas 终端、会话/输入/安装/设置界面 | 应用主题、路由、键盘/inset、工作台面板 |
| 依赖与镜像 | 同模块 `ui/SetupScreen.kt`、`utils/SourceManager.kt` | 分类安装界面、安装编排、APT/pip/npm/Rust 镜像配置 | PackageRecipe、安装任务状态、版本探测、依赖锁与安装回执 |
| Shizuku | `Operit/app/src/main/java/com/ai/assistance/operit/core/tools/system/ShizukuAuthorizer.kt`、`shell/DebuggerShellExecutor.kt` | 连接/授权/UID检查、状态监听、现有newProcess/PFD命令与进程操作 | ShizukuManager、移植执行器、显式target、并发I/O与审批；UserService作为后续增强适配 |
| Root/标准执行 | 同目录 `AndroidShellExecutor.kt`、`shell/RootShellExecutor.kt`、`shell/StandardShellExecutor.kt` | 执行器接口、libsu、标准进程和身份模型 | 显式路由、实际UID记录、并发流读取、进程组取消 |
| 前台服务 | `Operit/app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt:906`、`:936` | 职责集合、通知、后台常驻设置与生命周期处理 | RuntimeForegroundService、任务账本、通知操作、适用服务类型 |
| 悬浮窗 | `Operit/app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt`、`services/floating/` | 可见悬浮聊天、窗口管理、前台通知、有限WakeLock | 浮窗聊天/终端入口、taskId、权限与关闭动作 |
| QuickJS | `Operit/quickjs/` | Kotlin/JNI引擎、线程封装、中断与内存统计 | 固定上游提交、隔离服务、能力RPC |
| ToolPkg | `Operit/app/src/main/java/com/ai/assistance/operit/core/tools/packTool/` | Parser、Loader、API版本、依赖顺序、资源加载 | 包签名/权限账本、工具注册、资源与任务接入 |
| Compose 扩展 | `Operit/app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/` 及对应DSL parser | 声明式Compose树、组件注册与事件 | dstu UI contribution映射、主题、导航与文件选择端口 |
| MCP | `Operit/app/src/main/java/com/ai/assistance/operit/core/tools/mcp/McpRuntimeSession.kt`、`data/mcp/plugins/RemoteMcpRuntimeSession.kt` | 会话/描述模型、远程SSE与Streamable HTTP | 统一配置、认证、取消、工具注册；本地会话接终端进程 |
| 工作流 | `Operit/app/src/main/java/com/ai/assistance/operit/core/workflow/` | 图模型、拓扑/环检测、节点执行、调度 | dstu自动化模型、ToolDispatcher、任务持久化 |

### 3.2 按领域顺序接入的采用与增强目录

| 能力 | 源码来源 | 交付方向 |
| --- | --- | --- |
| 原生浏览器 | `core/tools/defaultTool/websession/browser/`、`standard/StandardBrowserSessionTools.kt` | 会话、ref快照、点击/输入、UA、下载、用户接管 |
| 文件 Provider | `provider/WorkspaceDocumentsProvider.kt`、`OperitDataDocumentsProvider.kt` | 工作区/附件在系统文件选择器中的授权访问 |
| 手机/平板导航 | `ui/main/layout/`、`navigation/`、`screens/ScreenRouteRegistry.kt` | 自适应导航、抽屉/侧栏、路由返回守卫、通用Compose组件 |
| 流式内容 | `ui/common/markdown/StreamMarkdownRenderer.kt`、`util/streamnative/` | 稳定块增量渲染、分段解析、公式/状态卡 |
| 语音 | `api/voice/VoiceServiceFactory.kt`、`api/speech/SpeechServiceFactory.kt` | TTS队列、取消、STT、VAD、本地/云端provider |
| 文档/OCR | `util/DocumentConversionUtil.kt`、OCR与图像工具 | 文本提取、页面栅格化、识别、格式转换 |
| 本地推理/嵌入 | `llm/llama/`、`llm/mnn/` 及实际provider | 离线模型与嵌入能力，模型下载、配置、资源预算 |
| 虚拟屏/设备Agent | `showerclient/`、`core/tools/agent/PhoneAgent.kt` | Shizuku shell 启动的虚拟显示、截图、触控、按键与设备操作 |
| ToolPkg外部集成 | `integrations/`、已接线工具与配置 | 与dstu自动化、系统分享、通知入口关联 |

S0 产出 Operit 全模块采用登记表：每项填写源码提交、实际调用链、产品价值、采用方式、适配点、依赖和交付版本。终端、Shizuku、依赖安装、前台常驻列为首批正式交付。

### 3.3 移植执行规则

1. 固定 Operit 与子模块提交，在 `third_party/operit/` 保存源码快照或受控fork。
2. `integrations/operit/` 连接 AppGraph、TaskSupervisor、CapabilityBroker、ResourceStore 和导航。
3. 业务状态、任务、权限与用量归新应用统一管理。
4. 为移植模块保留最小补丁集、上游来源、版权与许可证；后续更新按模块执行。
5. 原生库、rootfs、模型、字体和工具资源逐项记录来源、版本、hash、构建脚本和分发方式。
6. UI移植组件接入DstuTheme与组件库，替换Operit品牌、默认调色和示例文案，保留可复用的交互实现。
7. 业务持久化接入dstu的Repository与同步分类，Operit模块中的会话、偏好和缓存映射到对应的共享/本机数据范围。

## 4. 目标工程与执行架构

```text
dstu-android/
├── app/                         Application、AppGraph、Compose导航与装配
├── core/
│   ├── model/                   领域模型、状态、错误、时钟
│   ├── design/                  DstuTheme、设计令牌、原生组件、图标与术语
│   ├── data/                    SQLite、仓储、迁移、资源、数据槽
│   ├── runtime/                 任务、事件、恢复、审批、调度、常驻职责
│   ├── network/                 HTTP/SSE/WS、认证与网络策略
│   ├── security/                Keystore、加密、审计、授权
│   ├── execution/               ExecutionTarget、ShellRouter、进程与文件授予
│   └── interchange/             同步、备份、导入导出、格式编解码
├── features/
│   ├── chat/                    模型、对话、Agent与技能
│   ├── knowledge/               资源、笔记、文档、检索
│   ├── study/                   制卡、复习、题库与洞察
│   ├── productivity/            待办、番茄、自动化与统计
│   ├── workspace/              工作台、浏览器与多文档
│   ├── terminal/               终端、执行目标、依赖安装中心
│   └── system/                  设置、权限、后台、数据管理与升级
├── third_party/operit/          固定提交的源码模块
├── integrations/operit/         宿主适配器与最小补丁
├── engines/                     JNI、内容、脚本与模型引擎
├── config/                      依赖、运行资源、内容引擎与权限清单
├── testing/                     goldens、测试辅助与跨端用例
├── benchmark/
├── build-logic/
└── tools/
```

### 4.1 应用调用链

```text
Compose → ViewModel → UseCase → RuntimeController
                                ├─ DomainService → Repository → SQLite/ResourceStore
                                ├─ ToolDispatcher → CapabilityBroker → 工具
                                └─ ShellRouter → ExecutionTarget → 终端/系统进程

RuntimeForegroundService / WorkManager → 同一UseCase、任务账本与lease
```

默认单业务进程，集中数据库连接与权威资源写入。长任务提交返回taskId，Job归应用scope；ViewModel观察状态和事件。数据库事务负责本地提交，HTTP/审批/外部工具在事务外执行。

进程与初始化所有权：

| 进程 | 初始化与写入权限 | I/O与执行所有者 |
| --- | --- | --- |
| 主业务进程 | AppGraph、SQLite、RuntimeController、ResourceStore的唯一装配入口 | 本地任务与权威数据提交 |
| 重引擎进程 | 专用引擎依赖；输入PFD/URI、专属暂存结果 | 引擎任务与结果传输，主进程验证后提交 |
| isolated脚本服务 | 沙箱引擎与能力RPC客户端 | 授权消息/PFD，服务端逐调用检查 |
| Shizuku后端/UserService | Android系统执行身份与授权执行器 | 系统子进程、远程信号、PFD和退出码 |

Application按进程名选择装配入口。ProcessSupervisor分本地与远程实现：APP/LINUX进程由本地所有者终止；SHIZUKU/ROOT进程经对应执行器发信号和关闭句柄。

## 5. 内置终端、Shizuku 与依赖安装

### 5.1 四种执行目标

| ExecutionTarget | 环境与身份 | 主要用途 | 授权入口 |
| --- | --- | --- | --- |
| APP | Android应用UID与应用文件目录 | 应用内工具、私有工作区、基础命令 | 应用权限与资源授予 |
| LINUX | 应用UID承载的PRoot Ubuntu用户态 | apt、Python/venv/pip/uv、Node/npm/pnpm、Git、开发工具、本地MCP | 环境安装确认、目录挂载授权 |
| SHIZUKU | Shizuku/Sui提供的Android系统执行身份 | pm、settings、input、uiautomator、设备信息、Android系统操作 | Shizuku连接与用户授权，实际UID展示 |
| ROOT | su/libsu提供的root进程 | 需要root的设备操作和高级挂载 | root授权、命令级审批 |

创建会话时固定target、实际UID、cwd、环境变量和资源范围。终端顶部、Agent审批卡和命令回执显示这些字段；身份切换创建新会话或经用户确认重新绑定。

PRoot环境中的guest root与Android内核UID分开记录。APT/语言包安装在LINUX目标完成；Shizuku系统操作在SHIZUKU目标完成。Shizuku后端报告UID 2000或0时，按实际身份生成权限与审批策略。

### 5.2 终端模块移植

直接接入Operit的PTY、非阻塞I/O、ANSI模拟、Canvas渲染、多会话、历史、当前目录、OSC命令完成协议与隐藏执行。

适配交付：

- `TerminalServiceFacade`：会话/命令统一入口。
- `TerminalSessionStore`：target、cwd、history、输出位置、任务关联与退出状态。
- `ExecutionProfile`：环境、挂载、代理/镜像、PATH、预算、身份和审批策略。
- `TerminalOutputStore`：滚动缓冲、磁盘产物、分块输出、超长结果索引。
- `CommandTracker`：commandId、开始/结束、退出码、超时、取消、PID/PGID与回执。
- `ProcessSupervisor`：stdin/stdout/stderr、交互输入、SIGWINCH、进程组终止与句柄释放。

stdout/stderr并发排空；交互PTY保持终端字节序，非交互任务保留分流元数据。取消由进程所有者按进程组执行TERM、等待、KILL和资源释放。Shell脚本与结构化argv分开建模。

本地环境采用LocalTerminalProvider；SSH客户端、SSH/SFTP服务、FTP服务从TerminalManager抽出为显式连接/服务模块，接入各自配置与通知。远程连接使用单独的SSH target，APP/LINUX/SHIZUKU/ROOT保持本机执行目标。

### 5.3 Shizuku执行层

直接移植`ShizukuAuthorizer`和`DebuggerShellExecutor`的授权、Binder解析、UID校验、`IShizukuService.newProcess`及反射PFD交接路径，以`ShizukuManager`统一状态与会话。

1. 连接/授权/撤销/Binder死亡发布到StateFlow。
2. 首批执行器保留Operit现有启动路径，改为stdout/stderr并发读取，记录进程句柄、退出码和资源释放。
3. 每次请求绑定taskId、调用者、target、实际UID、argv或脚本、cwd和授权资源。
4. Binder死亡与权限撤销结束对应会话，写入可诊断结果；重新授权后创建新会话。
5. 应用私有文件通过显式PFD授予或受控暂存区交接；工作区修改由ResourceStore审核/提交。
6. 系统命令、安装/卸载、设置变更、跨应用控制统一进入CapabilityBroker和审计。
7. T3增强项新增固定API版本的`ShellUserService`，提供远程进程组信号、PTY和文件描述符能力；执行器适配测试分别覆盖直接移植路径与UserService路径。

源码依据：`DebuggerShellExecutor.kt:230`创建远程进程，`:243`取得PFD。现有执行路径和新增UserService在来源登记表分别标注。

### 5.4 Ubuntu与依赖安装中心

基于Operit的rootfs初始化、SetupScreen和SourceManager交付安装中心。

环境生命周期：下载/离线导入 → hash/来源检查 → 空间预检 → 临时解包 → 目录/挂载配置 → 原子激活 → 健康检查 → ready。

安装目录独立为`files/terminal/environments/<environmentId>`。应用工作区经授权挂载或暂存映射进入`/workspace`，资源变更回传业务层；环境重置、卸载与用户文档分别操作。

| 分类 | 首批依赖 | 管理能力 |
| --- | --- | --- |
| Linux基础 | bash、coreutils、Git、curl、证书、归档工具 | apt/dpkg安装、更新、卸载、源选择 |
| Python | Python3、venv、pip、pipx、uv | 项目虚拟环境、requirements/lock、版本与安装回执 |
| JavaScript | Node.js、npm、pnpm、npx | 项目依赖、全局CLI、锁文件、MCP启动配置 |
| JVM | OpenJDK、Gradle | 版本切换、JAVA_HOME、构建任务 |
| Rust/Go/C工具 | rustup/cargo、Go、编译工具 | 工具链配置、镜像、包/工具构建 |
| 文档/媒体工具 | 按工作流登记的转换器、音视频工具、检索CLI | 环境能力探测、安装、版本固定 |
| MCP依赖 | 每个server声明的runtime、包、版本、环境变量 | 安装→探测→启动→工具调用 |

`PackageRecipe`记录包名/来源、版本约束、安装方式、校验、环境目标、需要空间、授权、版本探测和卸载命令。包管理锁、安装进度、取消、日志与修复状态归任务账本；重入按包管理器状态恢复。

APT保留仓库签名验证；语言包使用项目锁文件和相应hash/来源约束；安装脚本经过来源登记与用户确认。镜像与代理支持用户配置。

移植修正清单：pnpm项映射到实际pnpm安装与探测；dpkg状态使用机器可读查询；rootfs代号进入环境manifest；安装结果由命令退出码、包状态和版本探测共同确认。

### 5.5 Agent与本地MCP接入

```text
工具/技能声明requires
 → DependencyResolver生成安装计划
 → 用户确认环境、依赖、空间、执行身份与网络动作
 → PackageInstaller执行并记录版本
 → 健康检查
 → ShellRouter启动命令或MCP server
 → 结果/产物回填当前会话与工作区
```

工具接口：`terminal_create_session`、`terminal_execute`、`terminal_input`、`terminal_cancel`、`terminal_resize`、`environment_status`、`dependency_plan`、`dependency_install`、`android_shell_execute`、`mcp_local_start`。

Agent和用户终端共享会话与输出；用户可接管交互、审阅安装计划、继续输入、取消进程和打开产物。

### 5.6 终端验收

- 无Shizuku/root设备完成Ubuntu初始化、交互PTY、`stty size`、中文/emoji输出与Ctrl+C。
- LINUX环境完成`apt-get update`、安装Python/venv/Git、项目venv内按测试锁文件安装包，并运行脚本。
- 安装Node/npm/pnpm并运行真实本地stdio MCP server，完成tools/list与tools/call。
- SHIZUKU会话执行`id`、只读`pm list packages`、设备信息查询，核对实际UID；系统写操作使用可逆测试项并恢复。
- ROOT会话在root测试设备验证身份、审批与取消。
- stdout/stderr同时大量输出、长命令取消、后台进程组退出均有自动化用例。
- 镜像更换、代理、下载中断、空间不足、包安装中断与环境重置覆盖完整状态机。
- Shizuku服务重启/撤权、Activity重建、锁屏与进程死亡分别核对任务和输出。

## 6. 前台服务与后台常驻

### 6.1 保活与执行职责

正式采用Operit的前台职责模型：`RuntimeForegroundService`在活跃任务或用户启用的持续职责期间维持前台优先级与常驻通知。

| 机制 | 实施任务 | 验收条件 |
| --- | --- | --- |
| 前台任务自动保活 | 用户发起长对话/Agent/终端/导入等任务时按适用类型启动前台职责 | 通知显示taskId、进度、返回入口与停止动作；Activity关闭后任务继续 |
| 后台常驻开关 | 采用Operit显式keep-alive偏好，绑定用户选择的后台终端、外部接入或持续Agent会话；按实际用途配置服务类型与持续窗口 | 功能空闲时保持已启用的有效持续职责，通知显示用途；开关跨启动保存，关闭后撤销持久职责 |
| 职责集合 | 按任务/服务记录引用；终态释放引用 | 最后一个职责结束后stopForeground与stopSelf；并行任务计数准确 |
| 条件START_STICKY | 对用户启用且具备恢复协议的持续职责请求系统重建，处理null intent；按目标SDK核对重建/前台类型/权限与执行窗口 | 重建先读账本并核对前台资格；系统允许的职责恢复执行，其余记录等待用户/系统允许入口；已提交动作按回执对账 |
| WakeLock | 需要CPU连续执行的任务按阶段持有带超时的PARTIAL_WAKE_LOCK | 任务结束/取消/服务停止释放；持锁时长进入电耗报告 |
| 电池/OEM设置 | 展示系统电池优化状态，提供相应设置和厂商后台/自启动指引 | 授权与拒绝两种配置均有状态与真机记录 |
| 可见浮窗 | 移植Operit浮窗聊天/窗口管理，加入终端/任务入口 | 显式授权、拖动/收起/关闭、返回主应用与资源释放 |
| 系统执行时限 | 按目标SDK建立用途/FGS类型/权限/时限表，实现timeout checkpoint | 系统要求停止时提交checkpoint、释放资源，后续入口按状态续作 |

通知“停止任务”取消指定task；“关闭后台”撤销持久职责。用户force-stop保持系统停止状态，重新打开应用后恢复账本。系统回收走持久恢复流程。

Operit透明overlay列入内测对照项目：对比开启/关闭时的生命周期、IME行为、电耗与设备差异，按结果登记采用方式。其现实现位于`AIForegroundService.kt:1490`，尺寸1×1、alpha=0。

### 6.2 系统调度

WorkManager在默认业务进程调用同一用例，争用同一lease。Worker在执行窗口内持有任务归属并等待提交/取消；长任务使用适用前台执行窗口或checkpoint拆批，移交有持久确认。

周期任务按系统调度窗口执行。提醒、番茄结束按用户用途选择系统机制；精确调度使用相应授权。BootReceiver重排已启用的工作流、周期任务与提醒，设备解锁后按配置恢复允许的职责。

### 6.3 任务与恢复

Application持有RuntimeController和SupervisorJob，每任务独立Job。StateFlow提供UI状态；必须送达的结果、审批、完成事件进入持久队列。

状态：created、running、waiting_approval、paused、completed、failed、cancelled、interrupted。

持久字段：taskId、generation、ownerEpoch/lease、checkpoint、取消请求、幂等键、业务回执和事件游标。

业务终态和完成outbox在所属业务库同事务提交，runtime.db通过幂等对账更新任务投影。恢复顺序：回收旧owner → 查业务完成事实 → 补账/事件 → 恢复可续任务 → 发布快照与游标事件。上游生成中断保留部分内容并标interrupted；外部副作用结果待确认时进入对应状态，由用户确认或协议回执解决。

### 6.4 后台验收

1. Activity finish、旋转、切工作台后任务持续推进，通知状态一致。
2. 锁屏30分钟跑真实任务，检查进度、输出、数据库提交与WakeLock释放。
3. 持续职责按适用服务类型与时限执行8小时测试，记录运行窗口、停止/恢复点与电耗。
4. Doze、App Standby、电池优化开/关、OEM后台配置逐项对照。
5. 系统回收、Shizuku死亡、用户通知停止、force-stop分别核对状态。
6. 测量任务恢复时延、漏投/重复事件、PSS、CPU与常驻模式耗电。

## 7. Kotlin业务与数据设计

### 7.0 桌面设计语言与原生组件

设计来源以锁定桌面commit的实际样式和组件为准：

| 来源 | 提取内容 | Compose实现 |
| --- | --- | --- |
| `src/styles/shadcn-variables.css:1` | 基础色、明暗模式、调色板、字号/字重/行高、圆角、尺寸 | DstuColorScheme、DstuTypography、DstuShapes、DstuSizes |
| `src/styles/theme-colors.css:1` | surface/text/border、侧栏/工作区、按钮、输入、弹窗、菜单、composer语义 | DstuTheme与CompositionLocal语义令牌 |
| `src/hooks/useTheme.ts:14` | light/dark/auto、9个预设与custom、自选色算法 | DstuThemeController、主题设置和解析值golden |
| `src/components/ui/buttonPrimitiveContract.ts:29`、DsDialog、SegmentedControl、unified-sidebar | 按钮/弹窗/分段/导航的外观和状态 | DstuButton、DstuDialog/Sheet、DstuSegmentedControl、DstuNavigation |
| chat样式、`notes-typography.css`、`workbench.tokens.css` | 消息与输入栏、思考/工具块、笔记版心、工作台材质 | 领域Compose组件与内容引擎样式 |
| DeepStudentLogo、StudySidebarIcons、locales、官方shell注册 | Logo、图标、术语、功能分类和导航顺序 | ImageVector/资源、Android文案资源、路由映射 |

构建工具从真实样式加载后导出已解析token快照，包含亮/暗模式、全部调色板和常用材质状态，生成版本化JSON与Kotlin资源。主题来源、算法和组件用例共用快照，桌面CSS/React运行代码留在开发对照工具中。

CI对冻结桌面设计源重算快照并逐值比较，变化时输出token、来源文件与差值并拒绝漂移，不自动刷新到上游。`verifyDesignTokens`与桌面/Android视觉测试共同执行；DstuButton遵循冻结版实色primary、danger及ghost规则，每个视图/对话框最多一个推进流程的实色主操作。

默认配色沿用dstu桌面调色板；Android主题、系统栏、弹窗、内容WebView和Operit移植组件全部消费DstuTheme。主色与中性色分别建模，自选色按桌面算法更新accent相关值。

尺寸映射：颜色/状态/字阶/圆角保留语义与比例，容器使用dp、文字使用sp；点击区域至少48dp。用户界面字号和系统字体缩放分别记录并验证组合效果。手机采用抽屉/面板栈/底部操作，平板采用侧栏/双栏/多面板，功能名、分组、图标和主操作与桌面对齐。

视觉验收产出token board、组件目录、关键页面桌面对照图与Android截图golden。配色与解析值按数值核对，排版/图标/层级/状态按组件和页面核对；手机/平板允许按窗口宽度重新排版。

### 7.1 对话与Agent

Kotlin类型：Session、Message、Block、Variant、TurnSnapshot、ProviderAdapter、ContextCompiler、PipelineRunner、ToolRegistry、ToolDispatcher、ApprovalCoordinator、MemoryService、SubagentCoordinator、GoalRuntime。

链路：发送用例 → 冻结模型/上下文/工具面/预算 → provider流 → 块增量持久化 → 统一工具审批执行 → 有序回填 → 压缩/记忆 → 完成回执与用量。

移植Operit provider接口、流解析、工具/包注册中的成熟实现，适配dstu的块、variant、分支、历史附件与领域数据。工作流、脚本、终端、MCP嵌套调用共用CapabilityBroker和TaskSupervisor。

审批检查调用者、会话、执行目标/实际UID、资源、敏感度、预算与取消。并发工具按资源键互斥，结果按原调用顺序回填。

### 7.2 笔记编辑器

Kotlin文档内核：NoteDocument、BlockNode、InlineNode、MarkdownCodec、SourceRange、EditCommand、UndoJournal、SelectionModel、ImeAdapter、DocumentSession、DraftStore、SaveCoordinator。

交付：WYSIWYG、IME合成、跨块选择/复制粘贴、撤销重做、移动块；表格/代码/公式/图片/双链/引用/callout/toggle/columns/卡片/图表；大文档增量解析、历史/OCC/租约、草稿恢复、Agent编辑隔离。

未编辑片段保留原表示，未知节点原样保存，编辑部分按方言序列化并检验语义往返。行为依据包括`src/components/crepe/normalizeMarkdown.ts:65`。

通用Compose组件、流式Markdown显示和内容处理直接采用Operit实现并应用DstuTheme；笔记字阶、版心、代码/公式/引用/双链样式与桌面对齐，编辑内核按dstu文档/草稿/版本语义落地。

### 7.3 富内容与工作台

采用Operit的稳定块增量渲染、公式/状态卡组件和适用内容工具。Kotlin block registry覆盖全部聊天块、生成式组件和交互结果。

专用内容宿主分别处理Anki HTML/CSS/JS卡面、cloze、FrontSide、音频、图表、公式、音乐/化学/分子和HTML工件。输入为结构化内容与授权资源，输出为限定的answer/openLink/replay等消息。每个引擎登记CSP、origin/nonce、消息schema与预算。

内容主题分宿主与用户内容两层：宿主构造文档时注入DstuTheme解析后的CSS变量、color-scheme与引擎预设样式；主题切换通过固定消息接口更新宿主样式区域。Anki卡面按night_mode等既定根节点约定切换，模板自有CSS/脚本保持原语义。U2用自定义卡面/图表夹具验证亮暗、主题切换与用户样式保留。

工作台模型：WorkspaceState、PanelInstance、ResourceKey、DraftState、LayoutSnapshot、ActivationPolicy、CloseGuard、AppRegistry。手机使用文档tab/面板栈/任务切换，平板使用多栏/平铺；关闭、保存、焦点、快照和任务归属统一。

四面板验收：PDF、笔记、聊天、浏览器并行，完成编辑、审批、任务运行、同资源重开、切尺寸和重启恢复。终端作为正式工作台应用加入面板与任务切换。

### 7.4 学习与生产力

- 卡片/牌组/模板/遮挡、媒体、批量操作、APKG与AnkiConnect。
- FSRS调度、学习/重学步、逻辑日、fuzz、bury、leech、评分/撤销、队列、统计与参数训练。
- 题库解析/裁剪、组卷、考试、作答/批改/改判、错题、掌握度与复习回流。
- 待办/重复任务/提醒、番茄/暂停/重启、台账与统计、模板与学习洞察。
- 主题/语言/字体/模型/网络/权限/存储/备份/恢复等全部设置项。

FSRS锁定参数版本、训练数据构造与数值比较规则；训练器采用Kotlin实现或同版本算法源码的JNI构建。训练参数写回、全量卡状态重算与loss评估分别验收。题库 `review_plans` 与闪卡复用同一 FSRS 调度器：保留旧 SM-2 列和日到期口径，旧行在预演/下次评分时惰性转换，四档预演与正式评分一致，quality 0–2/3/4/5 映射 Again/Hard/Good/Easy。规范来源：`host/plugins/review/src/fsrs-types.ts`、`review-plan-fsrs.ts`、生产训练代码与golden；不得另建第二套题库调度算法。

### 7.5 文档、检索、媒体与模型

采用Operit现有的文档提取、栅格化、OCR、TTS/STT/VAD、浏览器和文件Provider实现，封装为Kotlin领域端口。

PDF固定引擎版本，校验文本分页、坐标/旋转/DPI、密码错误与批注。Office/EPUB/APKG按内容与资源完整性对拍；OCR、训练、渲染和ANN按预设容差/召回评价。

检索采用SQLite FTS与ANN adapter，Kotlin负责分块、元数据、维度、距离、过滤、活跃性、删除/upsert与混合排序。物理索引按设备重建。

媒体任务包含录音、音频解码/重采样、VAD、转写、字幕、播放台账和TTS；长文件通过URI/PFD/流和有界缓存处理。Operit本地模型模块在S4.21接入离线能力，交付模型安装、版本/大小/内存检测与provider接入。

## 8. 数据、同步与迁移

### 8.1 SQLite与schema

采用固定版本捆绑SQLite、Kotlin仓储和MigrationRunner。普通偏好使用DataStore，跨端设置按其数据协议存储。

历史`V*.sql`逐字节保存；refinery checksum、列cid顺序、索引/触发器DDL和schema fingerprint与桌面golden对齐。FTS5、trigram、contentless_delete、JSON函数、WAL、事务/保存点、整数/blob/null均在真机捆绑驱动验证。

共享业务库为vfs、chat_v2、mistakes、llm_usage。新增共享迁移使用相同version/原始SQL并协调双端发布；Android任务、事件、设备布局等状态进runtime.db。业务完成outbox与终态同库同事务提交。

### 8.2 同步

同步是各业务阶段的公共主线。桌面版与Android使用同一同步仓库/云根和协议配置，保留各自独立device id、写者序列与本机执行状态。配对/凭据导入、手动/自动同步、方向、进度、冲突与错误入口使用dstu既有术语和设置结构。

Kotlin实现桌面实际启用的协议：时间戳解析/排序、字段合并、冲突、变更日志、墓碑/删除版本、回声抑制、device/instance绑定、序列、游标、租约和审计。协议基线从`host/plugins/sync/src/run-sync.ts:131`实际调用链锁定；每种协议版本、manifest、对象键、压缩/加密与merge规则有独立golden。写入沿用共享迁移触发器生成changed_at的规则，接收端按现有LWW规则解析HLC或常规时间戳；新的HLC写时钟随共同协议升级引入。

pending-publication是Android本机账本，记录变更集合、预留seq、对象键和hash；线上仍使用桌面既有wire/key/seq，published seq在远端回读确认后推进。预留未完成时复用同一key/seq完成发布，再分配后续序列；下载游标在本地持久提交后推进。

JSON保持整数精度、未知字段、null/省略语义；哈希/签名/内容地址载荷按规范固定编码。WebDAV/S3/FTP等基线provider逐项实现。共享迁移、分类、资产目录或协议变更协调双端发布。

版本矩阵按共享schema版本、wire协议版本和能力分类建立，应用版本可独立编号。现有协议的双向通过组合使用相同共享schema版本；不同schema组合验证升级提示、待同步数据保留与升级后继续。新增跨版本能力通过双端协议变更和golden测试发布，新增共享迁移在相关客户端安装包可用后进入协调升级窗口。

同步数据与状态矩阵：

| 数据域 | 桌面基线依据 | Android与桌面交付 |
| --- | --- | --- |
| 资源、笔记、文件夹/标签、文档格式/历史/关联 | classification中的RowSync/FileSync、资源与资产清单 | 双向新增/编辑/移动/删除/恢复，正文与关联资源可在另一端打开 |
| 聊天 | sessions/messages/blocks/attachments/groups/tags/compactions的RowSync | 完成内容、变体、引用与附件元数据互通，附件字节纳入资产闭环 |
| 题库、卡片/牌组、FSRS状态/日志、待办/番茄 | 各领域RowSync/FieldMerge/Lww和派生重算规则 | 双端修改/评分/撤销/作答/完成后收敛，统计按归属聚合或重建 |
| 卡片模板、Anki媒体、聊天附件持久字节 | `mistakes.ts:203`模板为BackupOnly；`asset-filenames.ts:137`资产目录白名单 | 建双端补齐工作包：分类/触发器、持久资产根、引用/hash/墓碑和双向测试，S0.21先完成合同与桌面捕获，随后按Kotlin领域单元依次接入和验证 |
| 媒体进度、字幕与学习计时 | `vfs-notes.ts:181`部分为BackupOnly或设备维度 | 定义可携带的恢复点/字幕与按设备事件或计数器合并；桌面和Android共同实现聚合与去重 |
| 主题/外观、模型/工具配置、插件与技能数据 | `settings.ts:2`可同步白名单、配对配置和插件数据入口 | 逐项定义portable/local/secret分类；补齐共享主题/配置/插件data的双端持久化与协议。API key与授权通过明确的安全配对/可选加密配置传递 |
| 派生索引和缓存 | DerivedRebuild | 同步源资源与必要配置，各端重建FTS/ANN与缓存 |
| 系统授权、绝对路径、运行环境与执行态 | LocalRuntime与设备资源 | Shizuku/root授权、Keystore密钥、rootfs/工具安装、本机路径、活动进程、任务租约与布局几何保留本机；可携带配置与路径使用逻辑ID映射 |

分类依据：`host/plugins/sync/src/classification.ts:10`、`classification-data/`、`preferences-sync.ts:7`和`asset-filenames.ts:137`。每个领域的Repository写入、变更捕获、资产提交、远端apply、引用校验与UI刷新在同一验收用例覆盖。内容同步与代码执行信任分开：插件/技能内容到达后展示安装状态，由本机授权执行。

跨端同步测试链：

1. S1完成一条真实双向笔记/资源流程：桌面写入→Android拉取显示→Android修改→桌面拉取显示。两端共用仓库与密钥，各用独立device id。
2. S2验证变更采集、触发器、序列/游标、字段冲突、资源hash/删除及启动分类检查。
3. S3–S7每新增业务写路径，在当前单元内依次完成Android写入、桌面往返和设备状态隔离用例，再进入下一单元。
4. S8完成全量数据域、provider、版本组合、弱网/冲突/重启与恢复硬化；备份互导另列独立测试。
5. S9在桌面正式安装包与Android正式候选APK中执行跨端工作流和数据校验。

### 8.3 备份与加密

实现v3 manifest、coverage ledger、full/partial_overlay、文件hash、恢复计划、staging与原子切槽。恢复前呈现覆盖域和资源变更。

新加密备份写DSBK v2，Argon2id和AES-GCM按桌面格式实现。v2大文件流式分块认证；v1兼容导入按文件/KDF预算预检，较大v1通过独立转换流程转v2后导入。认证完成后激活数据。

普通备份恢复按协议轮换设备身份并重建同步基线。连续身份迁移使用独立版本化migration sidecar，绑定源安装、同一快照、device/instance、upload/tombstone序列和游标，并记录hash与写者接管回执。

### 8.4 旧移动数据交付

S0保留已交付的两份真实 OPFS 导出包和一次性出口源码作为历史兼容输入，并在冻结桌面迁移上回放；不采用或追踪上游新 Android/Capacitor 恢复链。源码/桌面测试不代替计划既有的正式签名包覆盖安装、SAF 保存与真机回读证据。

交付一份可验证的SQLite/资源包；需要修通导出时，使用单独的一次性过渡版本完成。新应用Kotlin导入器消费导出包，执行大小/hash/schema/coverage/密钥与引用校验，再切换活动槽。

旧OPFS sahpool按其VFS导出API读取。旧数据源保留到导入回读、资源核验与用户确认完成。

## 9. 插件、ToolPkg、工作流与系统增强

### 9.1 ToolPkg与新SDK

直接接入Operit QuickJS、ToolPkg parser/API版本/依赖排序和Compose DSL。桥接函数统一映射CapabilityBroker，执行统一映射TaskSupervisor。

新插件SDK登记能力、资源范围、预算、版本、签名/来源和UI贡献。声明式UI映射到Compose组件；旧dstu插件逐包整理服务/工具/UI槽/Node/React依赖并迁移。补齐新基线 `dstu.plugin-services` 的 pluginSecrets、pluginNotify、pluginSchedule、studyData 与 study 事件：按实际调用插件归属隔离密钥/定时状态，调度接入任务账本和系统调度，领域写入走既有仓储及同步。动态插件视图、编辑器扩展、主题变体和通知动作均进入原生贡献注册及撤销生命周期。

工坊交付：意图分类/可行性与内置功能差异 → 提案卡/时间与用量 → 生成 → 校验/密钥扫描 → 隔离自检 → 内容绑定审批 → 安装 → 普通/密钥配置 → 跨会话绑定修改 → 更新 → 撤销/卸载 → `.dstuplugin` 导出。失败支持诊断、缩减重试和中断恢复；数据查看/清除与安装/信任分开。桌面工坊 full trust 只记录为上游行为，不授予 Android APP/LINUX/SHIZUKU/ROOT 执行权；Node/子进程依赖经依赖中心和 CapabilityBroker 显式授权。外部工具结果带 taint，随后高敏感调用再次审批，同 id/version 但内容变化重新审批。插件清单逐包有运行证据、迁移任务与负责人。

### 9.2 MCP与工作流

移植Operit会话/transport模型和远程实现；补齐桌面基线的认证、WebSocket、prompts/resources、结果清洗与工具命名。

本地stdio通过终端环境启动，stdin/stdout协议与stderr日志分开。依赖中心管理runtime、包、版本、环境变量和cwd；用户界面与Agent查看同一进程状态。

移植工作流图模型、拓扑/环检测与节点执行，连接dstu自动化、任务账本与系统调度。所有节点工具进入统一审批执行路径。

### 9.3 Shizuku系统增强与Shower

在系统工具目录加入包查询/用户批准的包操作、系统设置、输入、设备信息、界面层级等能力。Shower/PhoneAgent在S6.41–S6.42依次接入：shell身份启动服务、接收经过验证的Binder、创建虚拟屏、截图、触控和按键。

每项系统操作登记执行身份、授权资源、审批、取消和恢复方式；设备Agent的截图/触控与浏览器DOM工具分别提供明确接口。

## 10. 分阶段实施与执行单元

### 10.0 串行执行规则

- 阶段门禁按S0→S1→S2→S3→S4→S5→S6→S7→S8→S9推进，每个阶段出口签收后进入下一阶段。
- 阶段内编号为唯一集成验收顺序。同阶段独立写范围单元可由最多3个专属子智能体并发实现；每个智能体只负责一个单元，不跨单元复用。最终证据须等待前置签收输入稳定，主智能体逐项完成审查、独立测试、交接和本地提交。
- 表中“前置依赖”列出业务和技术输入，另外统一继承上一执行单元和上一阶段出口。`—`表示没有额外业务依赖，执行位置仍按串行顺序确定。
- 每个单元开工先按 rules 第13条阅读冻结桌面与 Operit 相关源码/调用链/测试，记录来源及差异；不能只读交接、摘要或 Android 既有代码。随后由同一执行通道完成Kotlin行为、Compose入口或可调用端口、桌面往返用例、视觉样本和证据归档。Android与桌面补齐工作在同一个单元内先后完成。
- 普通单元按1–3个工作日拆出可验收增量；多provider、引擎、块类型、安装recipe或插件的单元按S0清单展开为`Sx.nn/<entryId>/<operation>`。父单元汇总全部子卡，耗时按公共搭建与子卡实际工作量累计。
- S0.18–S0.24先交付同步golden、分类、版本和桌面捕获合同，再进入S1工程实现。S1纵切源码进入正式工程，后续沿同一代码和测试完善；数据、任务、授权、主题、引擎和协议各保留一个实现入口。
- S5交付主题、编辑器、已实现页面和通用工作台；S6交付终端/生态及其正式面板宿主；S7交付学习/生产力等完整业务页、逐包迁移并签收全应用UI矩阵。各阶段使用已经完成的实现作为输入。
- 所有业务页面消费S5.26的appId宿主合同；整页/面板、激活、草稿、关闭和快照随各业务单元交付，S7.38逐项总验收。设备本地状态按第8.2节分类。
- U0–U5、T1–T7表示阶段内的顺序分组。测试、性能测量、双端同步验证和文档更新纳入当前单元，阶段出口逐项核对签收。
- 新缺陷回到所属单元修复并重跑覆盖用例，或经用户授权插入独立补修单元，再继续队列；本次三项桌面兼容问题插入 S2.16–S2.18，旧签收保留历史证据，新增补修须独立签收。状态记录采用待启动、进行中、待验收、已完成；待验收仍占用当前执行位置。
- S0 历史队列冻结时为 302 主单元、312 队列位置；2026-11-23 用户授权新增三项补修后，当前为 305 主单元、10 阶段出口、315 队列位置。原 S2.16–S2.28 顺延为 S2.19–S2.31，其余编号不变；历史证据保留当时编号，新任务按当前编号与 `config/planning/active-plan.json` 映射记录，当前交接以 `config/planning/active-plan.json` 登记为准，S2 的进度与签收见 `config/stages/s2-progress.json`。

### 10.1 串行单元总览

| 阶段 | 执行单元数 | 编号范围 | 本阶段依次交付 |
| --- | --- | --- | --- |
| S0 | 25 | S0.01–S0.25 | 产品/来源清点→数据/视觉/发布基线→同步合同与桌面捕获→签收矩阵 |
| S1 | 27 | S1.01–S1.27 | 工程→数据/任务→主题/编辑/PDF/工作台→执行纵切→双向同步/基准 |
| S2 | 31 | S2.01–S2.31 | 数据/资源→恢复/安全→三项桌面兼容补修→任务/后台→权限/执行→同步底座/诊断 |
| S3 | 29 | S3.01–S3.29 | 模型协议→流与上下文→工具/Agent→聊天页面→跨端与性能 |
| S4 | 32 | S4.01–S4.32 | 引擎宿主→文档/OCR/APKG→音频/本地模型→检索→FSRS |
| S5 | 34 | S5.01–S5.34 | 主题/导航→编辑核心→内容块→多文档→工作台底座→输入/命令 |
| S6 | 44 | S6.01–S6.44 | 终端→环境/依赖→Shizuku/Root→MCP→ToolPkg→系统增强→宿主集成 |
| S7 | 38 | S7.01–S7.38 | 资料→制卡/复习→题库→生产力→媒体/首页/设置→逐包迁移→全应用UI签收 |
| S8 | 26 | S8.01–S8.26 | provider→同步事务/冲突/资产→版本/身份→备份/恢复→独立报告 |
| S9 | 19 | S9.01–S9.19 | 功能/视觉/设备→双端/后台/性能→来源/升级→发布/退役 |
| 合计 | **305** | **10个阶段出口** | **逐阶段、逐单元、逐子卡顺序交付** |

执行记录格式：`串行序号、单元/子卡编号、执行者、预计工作日、实际工作日、输入版本、前置签收、交付位置、验收用例、证据链接、状态`。交付物中的文件和模块在执行对应单元时创建。

### 10.2 参数化子卡

S0清单为provider、引擎、组件、块、格式、设置、recipe和插件分别确定有序成员集合。父单元内按成员顺序逐项完成，每个成员按表中操作从左到右执行；有条目间依赖时，S0将提供方排在消费方之前。同一成员全部子卡签收后处理下一成员，全部成员完成后签收父单元。

| 父单元 | 条目键与顺序子卡 | 每张子卡的交付与验收 |
| --- | --- | --- |
| S3.25工作流 | `graph-model`→`node-execution`→`schedule-agent`→`ui-roundtrip` | 图保存/环检测→既有工具节点/取消→调度与Agent调用→Compose编辑/执行与桌面读回 |
| S3.04–S3.08 provider族 | `<providerId>/request`→`/stream`→`/error-cancel` | 请求参数golden→流事件golden→真实连接/错误/取消记录 |
| S4.01引擎来源 | `<engineId>/source-lock`→`/build-origin` | 版本/许可/源码锁定→目标ABI构建和hash；本单元完成来源与构建交付 |
| S4.07–S4.22引擎适配族 | `<engineId或format>/adapter`→`/conformance`→`/budget` | 复用已完成的S4.01来源和S4.02宿主，依次签收Kotlin端口、正常/损坏对拍和设备预算 |
| S5.02–S5.03组件族 | `<componentId>/states`→`/input-a11y` | 主题状态截图→键盘/触控/读屏交互证据 |
| S5.14–S5.16编辑块族 | `<blockType>/render`→`/edit`→`/roundtrip` | 正常/损坏渲染→编辑/选择/撤销→序列化/主题/资源释放用例 |
| S5.18复杂内容 | 每个engineId的`/host`→`/interaction`→`/golden-budget` | mermaid、dot、vega-lite、wavedrom、abc、molecule-2d/3d、geojson、mindmap、canvas等逐类签收构建/宿主、编辑/交互、视觉/来源/预算 |
| S5.19聊天/生成式块 | `<blockType或componentId>/render`→`/action-stream` | 状态渲染→流式/审批/表单等类型化事件测试 |
| S5.30、S6.43及S7业务页面 | `<appId>/open-close`→`/draft-snapshot`→`/cross-device` | 整页/面板激活关闭→草稿/布局→数据双向与设备状态隔离 |
| S7.05、S7.19格式交付 | `<format>/import`→`/export`→`/roundtrip` | 导入报告→导出文件→桌面/Android回环与资源清单 |
| S7.35设置项 | `<settingId>/persist-effect`→`/scope-sync` | 重启与实际作用→portable/local/secret归属及对应通道 |
| S6.12–S6.15依赖安装 | `<recipeId>/install-probe`→`/failure-resume`→`/remove-reset` | 安装并运行→注入中断并重入→移除与用户文档保全；JDK、Gradle、Rust、Go、C工具各建子卡 |
| S6.22、S6.38、S6.42系统/浏览器动作 | `<operationId>/execute`→`/permission-cancel` | 实际效果→身份/授权/撤权/取消与审计 |
| S7.37插件迁移 | `<packageId>/api-map`→`/port-install`→`/data-ui-lifecycle` | 接口映射→移植安装→数据/UI/更新撤销与双端结果 |

示例：完成`S5.18/mermaid/host`及其验收，再完成`S5.18/mermaid/interaction`，最后完成`S5.18/mermaid/golden-budget`。mermaid的三张子卡全部签收后，按有序清单处理下一个引擎。

### 10.3 本次串行重排编号对照

| 原编号 | 本版编号 | 调整目的 |
| --- | --- | --- |
| 原S0.18 | S0.25 | 汇总新增同步合同后再冻结执行与签收矩阵 |
| 原S8.01–S8.07 | S0.18–S0.24 | 将各业务依赖的协议/分类/桌面补齐合同移到工程实施之前 |
| 原S1.18、S1.16、S1.17 | S1.16、S1.17、S1.18 | 先交付PDF引擎，再验证四面板，随后验证FSRS |
| 原S5.31、S5.32 | S6.43、S6.44 | 终端/浏览器/ToolPkg完成后接入正式工作台宿主 |
| 原S5.33–S5.36 | S5.31–S5.34 | 编辑与工作台底座按新顺序连续编号 |
| 原S5.37 | S7.38 | 全部业务页面与包迁移完成后统一签收UI性能与视觉矩阵 |
| 原S7.01–S7.36 | S6.01–S6.36 | 先完成终端、依赖、执行身份、MCP和包SDK |
| 原S7.38–S7.43 | S6.37–S6.42 | 在业务闭环前完成浏览器与系统增强 |
| 原S6.01–S6.36 | S7.01–S7.36 | 在主题、工作台、引擎和生态之后完成业务闭环 |
| 原S7.37 | S7.37 | 编号保留，执行位置移到完整业务和系统工具之后再逐包迁移 |
| 原S8.08–S8.33 | S8.01–S8.26 | S8集中做全业务同步与备份恢复硬化 |

上表是 S0 时的历史重排说明；原 302 项交付和 10 个阶段出口全部保留。2026-11-23 另新增 S2.16–S2.18 并将原 S2.16–S2.28 顺延三位，形成当前 305 项交付；其他编号不变，具体依赖以本文件当前各单元行为准。

### S0 范围冻结、源码清单与双端同步合同（25个单元）

目标：锁定产品、来源、数据、视觉与交付基线，再完成双端同步合同和桌面捕获，最后冻结串行执行清单。公共前置：—。

本阶段比较器、桌面捕获和fixture回放使用既有桌面工具链；新增共享迁移、最终schema指纹、协议和样本在S0.G一起冻结。Android侧先交付平台无关字段合同与输入/期望字节，Kotlin编解码和apply在S1/S2正式工程实现，领域页面随后按队列接入。

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S0.01 | 锁定桌面、Operit、terminal及引擎来源 | — | `config/source-lock.json`、来源校验规则 | 每个来源有提交/版本、路径、hash和获取方式；校验可定位版本差异 |
| S0.02 | 清点官方视图与主导航 | S0.01 | 视图/导航映射清单 | 基线17个视图与全部导航逐项对应，包含顺序、标题、可见条件与目标Compose路由 |
| S0.03 | 清点低频、开发及外部入口 | S0.02 | 菜单、深链接、命令面板、分享入口与发布条件表 | 每个入口有启动参数、返回路径、环境条件与验收场景 |
| S0.04 | 清点工作台应用与资源协议 | S0.01 | 静态应用与动态plugin-view族、ResourceKey、激活/并排打开/挂起/关闭/快照合同 | 每个应用/动态族有owner/实例/草稿/关闭语义，卸载撤销且重复打开只聚焦 |
| S0.05 | 清点领域服务与工具 | S0.01 | 领域/命令/工具/后台任务清单 | `host/plugins`与`contracts/ipc`条目均关联Kotlin模块和行为测试 |
| S0.06 | 清点设置与UI贡献面 | S0.02、S0.05 | 设置、slot、Dock、状态栏、选区工具条映射 | 每项有作用域、持久化位置、交互状态与Android入口 |
| S0.07 | 冻结内容方言与数值语义 | S0.05 | Markdown/卡面/生成式块/JSON编码规范及样本 | 方言、未知节点、int64/u64、null/省略、UTF-8与哈希编码各有输入和期望输出 |
| S0.08 | 冻结schema与迁移 | S0.01 | 历史SQL原文、逐文件hash、refinery/checksum/fingerprint样本 | 业务库、browser及相关本机库分别登记；源SQL与桌面基线逐字节核对 |
| S0.09 | 清点同步分类与待补齐域 | S0.05、S0.08 | portable/local/secret、RowSync/FileSync等分类及补齐任务表 | 模板、附件/Anki媒体、媒体进度/计时、主题配置、插件data各有双端任务 |
| S0.10 | 冻结实际同步协议与版本窗口 | S0.09 | wire/manifest/对象键/provider/schema版本矩阵 | 每个字段与生产调用链关联；同schema往返与异schema升级保全场景分别登记 |
| S0.11 | 提取桌面设计令牌快照 | S0.01 | `config/design/tokens.baseline.json`及生成规则 | 亮暗、全部调色板、形状、字阶、动效与领域语义值可重复生成 |
| S0.12 | 整理品牌、图标、术语与页面对照 | S0.02、S0.06、S0.11 | 图标/Logo来源、中英文案表、关键页面截图索引 | 导航/设置/主要操作术语成对，移植页面有dstu主题与文案映射 |
| S0.13 | 登记Operit采用单元与接缝 | S0.01、S0.05 | `config/operit-adoption.json` | 每项有源码符号、实际调用链、移植方式、适配器、负责人和阶段 |
| S0.14 | 整理原生资源与许可来源 | S0.13 | `config/runtime-assets.json`、许可/NOTICE清单 | PTY、proot/bash/busybox/loader、rootfs、QuickJS、Shower、字体/模型逐项登记 |
| S0.15 | 核实平台与发布身份 | S0.01、S0.14 | SDK/ABI/16KiB、applicationId、签名、版本映射和实包表 | 并列核对旧打包与版本生成脚本、实包versionCode和证书；确定单调递增的版本单源、统一minSdk和ABI，并写入发布合同 |
| S0.16 | 取得旧数据出口与导入合同 | S0.08、S0.10 | 真实导出包、hash/coverage/密钥说明、一次性导出交付步骤 | 用户可获得包；四库及资源逐项核验，同设备sidecar与普通恢复分别有样本 |
| S0.17 | 建立语料、设备与测量方案 | S0.07、S0.08、S0.11、S0.15 | schema/wire/SSE/文档/加密语料与设备矩阵 | 编辑器100份以上样本、真实模型流、明文/加密仓库、低内存/中端/平板及权限设备有明确来源 |
| S0.18 | 冻结同步协议/格式golden | S0.07–S0.10、S0.17 | 按wire/manifest/key/压缩/加密/merge场景的向量与比较器 | 真实桌面基线样本、未知字段/整数/null/时间与哈希比较规则齐全 |
| S0.19 | 建双端分类/主键/触发器注册合同 | S0.08、S0.09、S0.18 | 可机读注册表、主键/唯一键、捕获/影子表规则和桌面fixture | 基线集合逐项核对，增改删/本机状态/派生重建分类样本可运行 |
| S0.20 | 定义双端schema与发布窗口 | S0.10、S0.15、S0.18、S0.19 | 冻结桌面head与项目协调目标分别登记，实际SQL/未使用预留对账 | 上游vfs20261115后依次媒体20261116/插件20261117，模板mistakes20261006；附件/主题无新SQL；旧端拒绝保留pending |
| S0.21 | 补齐模板与持久附件合同/桌面捕获 | S0.18–S0.20 | 模板/Anki媒体/聊天附件分类、资产根、hash/引用/墓碑、桌面增量、共享迁移和平台无关字段/字节fixture | 桌面导出与回放fixture后分类、引用和模板一致；Android按合同完成S1/S2编解码/apply，再由S3/S4/S7接入实际场景 |
| S0.22 | 补齐媒体/字幕/计时合同与桌面捕获 | S0.18–S0.20 | 恢复点/字幕/设备不可变事件、桌面捕获/聚合向量、V20261116协调SQL | 从已到20261115的桌面库追加升级及fresh/restart通过，相同事件幂等，双设备汇总和可后退恢复点固定 |
| S0.23 | 补齐主题/可携带配置合同与桌面入口 | S0.11、S0.12、S0.18–S0.20 | settingId分类、主题/模型/工具配置映射、桌面持久化/捕获和跨端输入/期望fixture | 桌面写入/回放结果与跨端字段合同一致，local/secret通道与本机授权规则逐项登记 |
| S0.24 | 补齐插件/技能data合同与桌面入口 | S0.13、S0.18–S0.20 | owner/包版本/来源/键空间、V20261117协调SQL、data与安装/信任分离的fixture | 从上游20261115/媒体20261116真实升级，内容到达不执行；owner隔离/配额/墓碑/未知字段/新服务密钥调度边界通过 |
| S0.25 | 冻结串行队列、工期与签收矩阵 | S0.01–S0.24 | 场景→有序主单元/子卡→执行者→工作日→证据映射、累计里程碑和M0签收表 | 全部功能/采用/同步/视觉条目进入唯一队列，参数化成员和操作顺序齐全；依赖均指向更早单元，工期按单通道累计 |

**阶段出口 S0.G**：S0.01–S0.25全部签收，来源、范围、设计、协议、平台、数据出口和桌面捕获成为S1输入，M0完成。

### S1 原生骨架与源码移植纵切（27个单元）

目标：沿S0冻结合同建立正式源码线与可安装版本，依次验证数据、编辑器、内容、任务、工作台、算法、终端/Shizuku、产品壳和双向同步九类纵切。公共前置：S0.G。

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S1.01 | 建Gradle、工具链与构建入口 | — | 根工程、version catalog、build-logic、基础CI、版本映射 | 干净环境构建并安装Compose APK；版本/SDK/ABI按S0.15核验，来源和旧资产检查可执行 |
| S1.02 | 建进程感知AppGraph | S1.01 | 主进程/引擎/isolated装配入口、dispatcher、依赖注入 | 主业务库唯一装配，子进程按角色初始化；Activity重建复用应用依赖 |
| S1.03 | 定义ID、时钟、错误与核心端口 | S1.02 | 值类型、可注入墙钟/单调钟、Repository/Task/Engine接口与S0.21–S0.24平台无关合同的Kotlin类型 | 固定随机源/时钟样本与桌面规范一致；回拨、边界整数和错误传递可测 |
| S1.04 | 验证捆绑SQLite能力 | S1.01 | 版本/编译特性探针、FTS/JSON/WAL测试 | trigram/contentless_delete、保存点、整数/blob/null与目标设备均通过 |
| S1.05 | 实现迁移最小runner与golden | S1.04 | 历史SQL加载、refinery校验、schema指纹 | 空库迁移与桌面fingerprint一致；二次启动幂等，修改字节可定位漂移 |
| S1.06 | 实现笔记与资源真实读写 | S1.03、S1.05 | NoteRepository、正文/资源ID映射、文件hash | 建笔记和附件、编辑、删除/恢复后重启读回一致，引用与hash可核验 |
| S1.07 | 接入HTTP与SSE | S1.03 | 网络端口、流解码、取消与录制回放 | UTF-8跨chunk/多行data/断流样本正确；取消释放连接 |
| S1.08 | 建任务和持久事件最小账本 | S1.03、S1.06 | RuntimeController、taskId、lease、事件游标、业务outbox接缝 | 完成/失败/取消并行任务独立；重复提交幂等，提交后完成事实可查询 |
| S1.09 | 移植FGS职责与通知 | S1.08 | 前台职责引用、任务通知、停止动作 | Activity关闭后任务推进；取消目标任务、最后职责释放与通知一致 |
| S1.10 | 实现启动对账最小链 | S1.08、S1.09 | owner回收、业务回执查询、投影/事件补账 | 完成但账本滞后的任务补账；生成中断保留文本并标interrupted |
| S1.11 | 生成DstuTheme资源 | S1.01 | token快照生成、ColorScheme/Typography/Shapes/Sizes | 全调色板亮暗解析值对拍，修改源token产生差异报告 |
| S1.12 | 实现导航和笔记CRUD页面 | S1.06、S1.11 | 手机/平板导航、列表→打开→编辑→保存、品牌资源 | 中文/emoji保存重启读回，返回栈正确；页面和组件有桌面对照图 |
| S1.13 | 建Markdown AST与SourceRange | S1.03、S1.06 | 文档/块/行内模型、parser/serializer最小保真链 | 方言/未知节点往返，未改片段字节保留，单块编辑范围明确 |
| S1.14 | 验证IME、跨块选择与undo | S1.13 | 合成区适配、选区、EditCommand与逆操作 | 中文合成、组合emoji、跨块复制粘贴与固定随机undo序列通过 |
| S1.15 | 建内容与浏览器最小宿主 | S1.11、S1.12 | 授权资源/消息、主题注入、公式/图表/HTML卡面、测试页浏览器 | 内容交互与亮暗切换；消息origin/nonce校验；网页登录/返回/关闭释放 |
| S1.16 | 验证PDF源码构建与JNI | S1.01、S1.02 | 固定来源引擎、打开/页数/文本/渲染接口 | 中文/旋转/扫描/密码样本，坐标/文本规则、句柄释放与native预算有记录 |
| S1.17 | 验证四面板草稿与关闭协议 | S1.07、S1.08、S1.12、S1.15、S1.16 | Panel/ResourceKey、草稿、CloseGuard、快照与基于本阶段网络/任务端口的最小聊天会话/流视图 | PDF/笔记/聊天/浏览器纵切、未保存关闭取消、跨尺寸与重启恢复 |
| S1.18 | 验证FSRS数值与训练路线 | S1.03、S1.05 | 固定版本调度/训练适配、样本比较器，含题库计划状态适配 | 闪卡/题库四档、旧SM-2惰性转换、分钟/日到期样本一致；固定训练集产出参数/loss，取消保留原参数 |
| S1.19 | 验证PTC最小执行链 | S1.03、S1.08 | 固定引擎来源、脚本预算、工具白名单与回执 | 脚本调用两项工具；超预算/越权/取消正确结束，结果落账 |
| S1.20 | 直接移植PTY、ANSI与会话 | S1.01、S1.02 | Operit终端源码、JNI、最小终端页 | stty/resize、中文emoji、Ctrl+C、多会话输出与退出码正确 |
| S1.21 | 接入PRoot和安装纵切 | S1.20 | rootfs验证/解包/激活、PackageRecipe、apt与venv安装 | 普通设备启动Ubuntu，安装Git和项目venv包，记录版本；中断/低空间状态可重入 |
| S1.22 | 移植Shizuku授权与执行纵切 | S1.01、S1.02 | Authorizer/newProcess/PFD适配、并发双流、UID状态 | id与实际后端UID一致；pm只读命令、撤权/Binder死亡与资源释放通过 |
| S1.23 | 实现secret与Argon2id/DSBK-v2最小链 | S1.01、S1.03 | Keystore包裹主密钥、凭据密文存取端口、诊断脱敏、KDF/分块AEAD与暂存激活 | 双向解密字节一致；错误密码/篡改/截断拒绝激活；凭据文件/日志/诊断扫描无明文；暂存生命周期与v2内存有界 |
| S1.24 | 接入同步配置与最小provider | S0.18–S0.20、S1.07、S1.23 | WebDAV、独立device id、manifest/对象键/codec、配对入口 | put/get/list/delete与桌面格式样本一致，凭据存储和日志脱敏通过 |
| S1.25 | 跑明文笔记与资源双向同步 | S1.06、S1.24 | changed_at捕获、上传/apply、引用与资产链 | 桌面创建→Android编辑→桌面读回；删除/恢复一致，重复同步幂等 |
| S1.26 | 跑加密双向同步 | S1.23、S1.25 | 同链路加密配置、输入与桌面相同密码、格式处理 | 加密仓库完整往返；错密码保留未上传change_log与下载游标，改正确后同批完成，seq与资源hash一致 |
| S1.27 | 建设备与性能预算基线 | S1.12、S1.20、S1.26 | 固定设备/语料、启动/帧/内存/同步/终端测量任务 | 三档设备产出P50/P95及绝对预算，后续单元引用同一配置 |

**阶段出口 S1.G**：S1.01–S1.27全部签收，同一可安装版本完成九类纵切、明文/加密桌面往返与视觉基线，M1完成。

### S2 数据、任务、权限与后台底座（31个单元）

目标：沿S1代码依次完善统一数据、资源、恢复、安全、任务、后台、执行权限和同步基础，供后续领域调用。公共前置：S1.G。

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S2.01 | 完善连接profile与读写通道 | S1.04 | DatabaseRegistry、WAL读连接、写者队列、事务入口 | 并行读写快照正确，锁等待按预算返回，关闭后fd回基线 |
| S2.02 | 完善迁移与fingerprint | S1.05、S2.01 | 逐迁移事务、checksum、治理指纹与启动报告 | 新装/重复启动/旧版本升级对拍；字节/DDL漂移定位到对象 |
| S2.03 | 接入迁移维护与诊断 | S2.02 | 迁移状态、失败点、维护页和续作入口 | 每个迁移提交点终止后重开版本一致，旧数据保全且错误可定位 |
| S2.04 | 建同步分类和捕获校验 | S0.19–S0.24、S2.02 | 分类注册、主键、三类触发器校验 | RowSync增改删入日志，本机表保持设备隔离；缺项定位表/触发器 |
| S2.05 | 完善变更读取与JSON保真 | S2.04 | ChangeLogReader、稳定批次、字段序列化 | 大整数/null/空串/未知字段按wire规范往返，重插/删除顺序对拍 |
| S2.06 | 完善内容寻址与原子资源写 | S1.06、S2.01 | hash、引用、临时文件/激活、空间检查 | 同内容去重；写入故障无可见半文件，共享引用删除/恢复正确 |
| S2.07 | 接入URI/PFD/SAF导入导出 | S2.06 | 流式I/O、授权句柄、进度/取消 | 本机和云文件provider大文件hash一致，撤权/取消清理正确 |
| S2.08 | 建资产清单、墓碑和引用检查 | S2.05、S2.06 | 资产逻辑键、删除队列、引用报告 | 删除/恢复/多引用夹具保持可打开资源，清理按登记策略执行 |
| S2.09 | 建DataSpace暂存与切槽 | S2.02、S2.06 | active/staging状态、维护租约、原子切换 | 各切点终止后活动槽完整唯一；状态损坏有可诊断保全路径 |
| S2.10 | 接入manifest/coverage恢复计划 | S2.09 | v3计划、覆盖域/空间/hash检查 | full与partial按域展示；缺域/路径越界/hash错误在切槽前结束 |
| S2.11 | 实现恢复执行与故障重入 | S2.10 | 解包、资源/库校验、切槽与旧槽保留 | 解包/提交/切换故障点恢复为完整旧槽或新槽，操作幂等 |
| S2.12 | 实现旧移动包Kotlin导入 | S0.16、S2.11 | 来源/版本/数据/引用导入报告 | 真实旧包四库及资源回读一致，原数据保留至确认 |
| S2.13 | 完善Keystore与KDF/codec | S1.23、S2.06 | 主密钥包裹、DSBK版本分派、预算与失效处理 | 桌面向量对拍；交换块/截断/错密码拒绝激活；密钥失效有配对入口 |
| S2.14 | 完成加密导入导出纵切 | S2.11、S2.13 | v2双向恢复、v1兼容/转换路径 | 桌面与Android互导内容/hash一致，失败活动槽不变 |
| S2.15 | 实现恢复与迁移身份规则 | S2.11、S2.14 | 普通restore轮换、sidecar验证和唯一写者接管 | 普通恢复轮换本机写者并重建绑定；sidecar延续经核验的仓库instance/序列，重复接管拒绝 |
| S2.16 | 修复普通恢复四库同步基线 | S2.15、S2.05、S2.11 | 对齐冻结桌面 resetSyncBaselineAfterRestore，在候选四库清理旧 __change_log、冲突与 ID 别名并归一业务 sync_version/local_version；保留业务内容与资源，普通恢复基线和新仓库全量 seed 明确分开 | 原仓库恢复后以新 writer 重配，旧 pending 不再发布；四库业务内容/附件不变、新增/编辑/删除仍能与桌面往返；重启/失败重入不重复轮换或改活动槽，新仓库 seed 不借用残留日志 |
| S2.17 | 修复恢复资源引用兼容 | S2.16、S2.08、S2.14 | 复用 S2.08 引用合同，按真实文件/预览/压缩附件引用与 ref_count 验证恢复资源；无真实引用且零引用的缺失 blob 不再整包拒绝 | 同一冻结桌面导出/导入包在 Android 冷恢复可读；零引用缺失对象通过，ref_count=0 但仍有真实引用、正引用缺失或 hash/大小错误仍在切槽前失败；原活动数据保全 |
| S2.18 | 兼容旧版加密仓库 marker | S2.17、S2.13、S1.24、S1.26 | 对齐冻结桌面 .encryption-marker v1 密码验证和 claim 升级 v2 后加入原仓库的路径，复用现有配对/加密端口；区分仓库 marker 与 DSBK 文件版本 | 正确密码可加入旧标记仓库并与桌面明密文合同对拍；错密/标记损坏/升级竞争/中断不覆盖其他写者标记或混写明文，已有 v2 配对回归通过 |
| S2.19 | 完善runtime.db状态与投影 | S1.08、S2.01、S2.18 | tasks/events/approvals/audit/duties迁移 | generation/幂等键/游标不串任务，所有查询与状态重启读回 |
| S2.20 | 完善任务lease与取消 | S2.19 | acquire/renew/release、ownerEpoch、取消传播 | UI/Worker同时提交只有一执行者；过期接管、重复取消保持单终态 |
| S2.21 | 完善业务outbox与事件对账 | S2.19 | 同事务完成事实、消费者游标与投影ack | 业务已提交/账本未确认故障后只补账，重复投递消费幂等 |
| S2.22 | 完善启动恢复状态机 | S1.10、S2.20、S2.21 | owner回收、事实核对、interrupted/待确认恢复 | 半截内容保留；不明副作用进入待确认；重复重启保持一致 |
| S2.23 | 完善FGS职责和通知 | S1.09、S2.20 | 用途/类型、引用计数、task停止、条件Sticky | 多职责独立结束，null intent重建对账，最后职责释放无孤儿服务 |
| S2.24 | 接入常驻、WakeLock与电池页 | S2.23 | keep-alive设置、持锁记录、OEM指引 | 持锁分段有上限，取消/停止释放；常驻开关与真实职责一致 |
| S2.25 | 完善WorkManager与提醒调度 | S2.20、S2.23 | 同进程Worker、checkpoint、唯一工作名、重排 | UI/Worker不双执行，系统停止后按checkpoint续作；force-stop重开分别验证 |
| S2.26 | 实现CapabilityBroker审批审计 | S2.20、S2.21 | 调用者/target/UID/资源/预算、决定与撤销 | 拒绝执行数为零；旋转保留待审批；撤权结束调用且留审计 |
| S2.27 | 接入四目标ShellRouter | S1.20、S1.21、S1.22、S2.26 | 复用PTY/PRoot/Shizuku，移植Operit StandardShellExecutor/RootShellExecutor，统一四目标路由/回执/取消 | APP/LINUX/SHIZUKU/ROOT分别实跑并核对身份，取消由进程所有者执行；S6沿本实现完善交互与系统增强 |
| S2.28 | 接入Workspace/附件Provider | S2.06、S2.07 | Operit Provider适配、稳定documentId与授权 | 系统文件管理器读写经ResourceStore，产生一次变更，私有治理文件受边界保护 |
| S2.29 | 完善同步采集与发布基础 | S1.25、S1.26、S2.05、S2.08、S2.15、S2.16、S2.17、S2.18 | 承接 S1 上传路径，pending 预留/key/hash/回读确认/续作；本机发布回执使用 database+change_log_id；会话按 salt/KDF 参数复用派生密钥，对象 nonce 独立、结束清理；建立流式资源读写/暂存/取消合同及新仓库全量 seed 边界，wire 保持冻结桌面格式 | 预留或远端已写后终止复用同批 key/seq/ciphertext，跨库相同日志 ID 不错认；记录/资产可被桌面读取，回读后逐库幂等确认；多对象不重复无谓 KDF、会话退出清理；新仓库初始化不以旧 pending 片段冒充全量数据 |
| S2.30 | 完善下载apply与同步检查 | S2.29 | 提交后游标、基础 LWW/字段合并、流式资源落地与 UI 事件；全域按数据库分别幂等事务，全部完成才确认消费游标；S0.21–S0.24 全域 fixture 编解码/apply，冲突决定在 S8.08 完善 | 笔记/资源走真实用例；聊天/待办/卡片/模板/媒体事件/配置/插件 data 使用迁移后行 fixture 采集/apply/回读；跨库部分成功后重放不重复副作用、不提前推进游标；恢复重配后桌面↔Android真实笔记/附件新增编辑删除往返，引用/hash/整数/触发器与取消清理正确 |
| S2.31 | 统一错误、日志与诊断导出 | S2.03、S2.22、S2.30 | 错误目录、诊断页、脱敏分享包 | 每个错误有可复现场景，诊断可定位库/任务/阶段，凭据扫描通过 |

**阶段出口 S2.G**：S2.01–S2.31（包括新增 S2.16–S2.18 兼容补修）全部签收，数据、安全、资源、任务、权限、后台和同步基础端口冻结，M2完成。

### S3 对话、模型与Agent（29个单元）

目标：依次完成模型协议、流式回合、上下文、工具和Agent编排、聊天页面及跨端用例。复用S1/S2网络、任务、PTC与执行实现；S4补充正式引擎，S6补充完整生态。公共前置：S2.G。

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S3.01 | 定义聊天模型、仓储与TurnSnapshot | S1.03、S2.02、S2.30 | Session/Message/Block/Variant字段映射、Kotlin仓储与不可变快照，复用S1最小聊天实现 | 桌面样本逐字段持久化/重启读回；同输入快照稳定，未知字段保留，正式流管线直接使用同一仓储 |
| S3.02 | 建provider接口与配置解析 | S3.01、S1.07 | Operit接口适配、协议/参数/凭据/模型配置表 | 每个S0 provider解析到明确协议；非法配置在发请求前返回对应错误 |
| S3.03 | 完成单轮流式持久管线 | S3.02 | 发送→流→块→outbox→用量的用例 | 真实请求完成与断流/取消状态可查，已收内容重启保留 |
| S3.04 | 适配Chat Completions协议族 | S3.03 | 按providerId展开的参数、思考、工具、usage样本 | 每家录制请求/响应对拍；4xx/限流/取消各有场景 |
| S3.05 | 适配Responses协议族 | S3.03 | item/part/工具/思考/认证映射 | 文本/工具item顺序和历史回传正确，认证取消与失效可恢复 |
| S3.06 | 适配Anthropic协议族 | S3.03 | thinking签名、tool_result、缓存usage与stop原因 | 录制流和请求参数对拍，缺失/截断tool块不触发外部工具 |
| S3.07 | 适配Google协议族 | S3.03 | parts/function/思考/safety与usage映射 | 图文/函数样本正确，安全拦截/空响应/取消状态明确 |
| S3.08 | 接入自定义/本地/中转端点 | S3.02、S3.03 | 显式协议选择、模型列表、代理/自定义头 | 用户配置端点完成流式调用，列表拉取/代理错误与取消有诊断 |
| S3.09 | 硬化SSE与流边界 | S1.07、S3.03 | 共享帧解析、长度预算、未知事件处理 | 任意字节切片结果一致，UTF-8/多行data/结束/截断样本通过；无游标恢复的上游断流落interrupted |
| S3.10 | 硬化块增量与事件顺序 | S3.03、S3.09 | 稳定blockId、分片参数、生成序号与重复事件处理 | 各族delta回放得到同一内容，原文合法重复保留，游标重复投递幂等 |
| S3.11 | 实现usage归一与预算 | S3.10 | 调用日志、缓存用量、费用/轮数限制、日汇总 | 完成/取消/缓存调用按规则入账，缺usage标估算来源 |
| S3.12 | 实现上下文编译 | S3.01、S3.11 | 系统/历史/工具/附件/摘要/记忆排序与快照 | 固定上下文golden对拍，裁剪有来源和结果清单，编译可取消 |
| S3.13 | 接入附件与多模态 | S3.12、S1.06、S1.16、S0.21 | 持久资源引用、上传、文本/图像/音频输入适配 | 图片/PDF/音频按模型能力显式编码；损坏/超限/取消保全原资源与草稿 |
| S3.14 | 接入tokenizer与能力目录 | S3.02 | 模型能力、精确计数/估算规则与版本 | 固定词表计数对拍；估算如实标注，预算边界与未知模型由用户配置确认 |
| S3.15 | 实现变体、分支、编辑与重试 | S3.10 | 独立状态机、复制ID映射、active指针、编辑回卷规则 | 多变体独立取消；分支完整、旧内容保留策略对拍；重提幂等 |
| S3.16 | 建工具注册与schema目录 | S3.12 | 描述符、能力/target/资源、可用性与版本 | S0每项工具分配到明确交付单元；本阶段已实现工具的名称/schema/能力逐项回归，后续引擎/业务/系统工具随所属单元注册并实跑 |
| S3.17 | 接入ToolDispatcher与审批 | S3.16、S2.26 | 参数/身份/敏感度/预算/授权、插件工具与外部数据taint的统一执行入口 | 聊天/S1脚本/插件工具共用入口；外部结果后高敏调用再次审批，拒绝/超时/撤销执行数为零，审计完整 |
| S3.18 | 实现工具循环与有序并行 | S3.17 | 工具轮、并发限额、资源互斥、结果/完整产物 | 读并行/同资源写串行，回填顺序稳定；父取消结束全部子调用 |
| S3.19 | 接入工作区与Agent编辑 | S3.17 | 逻辑根/项目指令、OCC提案、文件暂存/提交 | 并发人工编辑保留双方内容，拒绝/取消保全原文与可查提案 |
| S3.20 | 实现技能加载与依赖声明 | S3.16 | manifest/frontmatter、正文分层加载、requires/trust | 每包元数据与路径校验；缺依赖显示安装计划，本机授权独立 |
| S3.21 | 实现PTC编排适配 | S3.18、S1.19 | PTC工具、预算/白名单、物化object与分页 | 同一最小引擎执行脚本→工具→物化；预算/越权/取消有回执 |
| S3.22 | 实现子代理生命周期 | S3.18、S3.11 | 父子任务、深度/并发/预算、结果回传 | 两子任务独立完成/失败，父取消传播，结果重投消费幂等 |
| S3.23 | 实现目标与续轮 | S3.22 | Goal状态、终止条件、预算、暂停/继续 | 多轮推进在条件达成/耗尽/取消处终止，另一设备只读取内容 |
| S3.24 | 实现记忆与压缩 | S3.12、S3.18、S2.02 | 记忆读写、压缩前flush、摘要lineage、恢复及使用已交付SQLite FTS的检索实现 | 真实记忆写入/召回、重启摘要/历史一致，失败保留active摘要；S4.27接回正式混合检索 |
| S3.25 | 移植工作流并接入自动化Agent轮 | S3.23、S2.25 | Operit工作流图/拓扑/节点执行、触发/迟到策略、headless授权与run账本 | 图编辑/保存/执行使用Kotlin/Compose和既有工具；计划触发/停用/补偿/取消对拍，审批/资源边界与普通Agent一致 |
| S3.26 | 接入终端与依赖工具 | S3.17、S1.20–S1.22、S2.27 | 会话/命令/安装计划/接管编排 | LINUX脚本与SHIZUKU命令实跑，UID/审批/产物可追溯，取消正确 |
| S3.27 | 交付聊天Compose纵切 | S3.10、S3.15、S3.17、S1.11、S1.12 | 列表/输入/模型/思考/工具/审批/变体页面 | 手机/平板发送→审批→完成，旋转/返回后状态与账本一致 |
| S3.28 | 签收聊天跨端同步 | S3.13、S3.15、S3.24、S0.18–S0.21 | 内容/变体/引用/附件与本机状态隔离用例 | 桌面含附件会话→Android续写→桌面读回，资源可打开；goal/lease保持本机 |
| S3.29 | 测量长对话与故障 | S3.27、S3.28、S1.27 | 长会话、流更新、限流/断流/重启预算报告 | 达到登记预算；断流部分保存、取消释放连接、重复事件无重复块 |

**阶段出口 S3.G**：S3.01–S3.29全部签收，provider逐项、完整工具轮、Agent状态和聊天同步证据齐全，作为S4引擎和后续生态的输入。

### S4 文档、媒体、检索与学习引擎（32个单元）

目标：依次完成引擎来源构建、宿主、文档/OCR/APKG、媒体与模型、检索、FSRS的Kotlin封装和行为/预算验收，输出S5/S7可直接使用的实现。公共前置：S3.G。

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S4.01 | 建引擎来源与构建矩阵 | S0.14、S1.01 | 按engineId展开的版本、源码/依赖、ABI、构建与hash规则 | 每个引擎可单独构建/验证来源，许可与运行资源齐全 |
| S4.02 | 建引擎宿主与取消协议 | S4.01、S1.02 | 同进程/隔离进程选择、PFD、暂存/结果、错误/取消 | 大输入有界内存；引擎崩溃限定影响，主进程验证结果再提交 |
| S4.03 | 建引擎语料与预算任务 | S4.02、S1.27 | 每类文本/坐标/浮点/图像/召回规则和设备用例 | 样本、容差、设备和测量点明确，超预算能定位引擎/操作 |
| S4.04 | 完善PDF分页文本与坐标 | S1.16、S4.01 | 页文本/字符框/旋转/密码与损坏错误 | 页序/分隔符对拍，旋转坐标在登记容差，错误不泄漏句柄 |
| S4.05 | 完善PDF渲染与缓存 | S4.04、S4.02 | DPI/裁剪/tile缓存、取消与回收 | 多次开关/渲染内存稳定，视觉与坐标按S4.03基准评价 |
| S4.06 | 实现批注与原文锚点引擎 | S4.04、S4.05 | 高亮/便签/quad、quote定位、摘要和导出 | 双端页码/坐标/引用一致，批注导出后桌面可读 |
| S4.07 | 接入Office文本抽取 | S4.01、S4.02 | 按格式展开的文本/表格/元数据解析 | DOCX/XLSX/PPTX等基线格式按规范保留结构，损坏/加密分类正确 |
| S4.08 | 接入Office生成与编辑 | S4.07 | 文档/讲义/表格/演示生成端口 | 应用导出后桌面打开、抽取回读与内容/资源清单一致 |
| S4.09 | 接入EPUB与文档入口校验 | S4.01、S4.07 | spine/章节/资源、编码、解包/XML预算 | 章节顺序和资源正确，恶意包/DRM/超限按类型返回 |
| S4.10 | 接入图像与HEIC处理 | S4.01、S4.02 | 解码、方向、裁剪/缩放、PNG/JPEG、大小预算 | 坐标和方向对拍；编码质量与大小达预算，损坏输入释放资源 |
| S4.11 | 移植系统/本地OCR | S4.10 | 语言/模型、段落/行/字符框、预处理 | 固定扫描集文字质量和框位置达S4.03指标，模型状态可查 |
| S4.12 | 接入云端OCR | S4.05、S4.10、S1.07 | 模型请求/结构化页输出、配额/usage | 回放对拍、限流/取消/单页失败有记录，按实际使用入账 |
| S4.13 | 建PDF OCR任务队列 | S4.11、S4.12 | 页任务、进度、缓存、暂停/继续/取消 | 多页中断后从checkpoint续作，已完成页保留，缓存与连接回收 |
| S4.14 | 接入压缩与protobuf | S4.01 | zstd/gzip/ZIP安全读取、协议模型 | 解压字节与字段向量对拍，未知字段保留，超限/损坏预算内结束 |
| S4.15 | 实现APKG读取端口 | S4.14、S1.04 | collection/models/decks/字段/调度解析与报告 | 现代anki21b、历史格式、媒体索引样本读取一致，坏包有明细 |
| S4.16 | 实现APKG媒体与写出端口 | S4.15、S0.21 | 文件名/hash映射、模板/媒体打包、导出器 | 桌面/Anki回读卡片/媒体/模板内容一致，目录越界与冲突可定位 |
| S4.17 | 接入音频探测/解码/重采样 | S4.01、S4.02 | 容器/编码识别、PCM流、进度/取消 | 各基线音频duration/采样结果在登记容差，长文件内存有界 |
| S4.18 | 移植VAD与切段 | S4.17 | 模型/静音参数、分段合并与时间轴 | 标注语料段边界、缺模型和取消通过，输出可复现 |
| S4.19 | 实现ASR与字幕引擎 | S4.18、S1.07、S0.22 | 分段转写、限流、清洗、SRT/VTT和事件 | 回放/真实录音质量、时间轴与重入去重，usage按段归属 |
| S4.20 | 移植TTS队列与播放事件 | S4.17、S0.22 | 分段/预取、播放/焦点/取消、进度/计时事件 | 播放不重叠，取消静音与焦点恢复正确；两端事件去重后计数一致 |
| S4.21 | 移植本地llama/MNN推理 | S4.01、S4.02、S3.02 | 模型安装/hash/ABI/内存检查、流式推理端口及本地provider正式接线 | 固定模型真实生成、取消释放、错误来源可查，目标设备预算记录 |
| S4.22 | 接入本地/云端embedding | S4.21、S1.07 | 批处理、模型/维度指纹、f32向量与usage | 各模型分别对拍，切模型终止旧任务并更新重建标记 |
| S4.23 | 实现分块与资源文本抽取 | S1.13 | 页/offset/类型/质量过滤的ChunkingEngine | 各类资源chunk与基线一致，UTF-8边界和引用位置正确 |
| S4.24 | 接入FTS与词法排序 | S2.02、S2.04、S4.23 | 基线FTS查询、短查询规则、增量/全量索引 | 删除/恢复/资源更新后可查，固定查询排序对拍 |
| S4.25 | 实现ANN adapter与generation | S4.22、S4.23 | profile/维度/距离、upsert/delete/filter、切换 | 精确检索基线下召回达标，删除不再命中，重建切换原子 |
| S4.26 | 实现混合检索与重排 | S4.24、S4.25 | 路由、过滤、RRF/MMR、rerank和引用 | 查询/过滤集结果与指标通过，超时和单路失败展示对应状态 |
| S4.27 | 完善索引恢复与重建 | S4.25、S4.26、S3.24 | checkpoint、孤儿清理、进度/恢复与聊天RAG/Agent记忆的正式检索接线 | 断点重建无重复向量，源库/索引活跃性一致；聊天与记忆实际召回、引用回链、取消和预算通过 |
| S4.28 | 完善FSRS记忆模型 | S1.18 | 参数版本、memoryState/nextStates、数值边界 | 固定与随机向量按版本的数值规则对拍，非法参数明确返回 |
| S4.29 | 完善FSRS调度器 | S4.28、S1.03 | 学习/重学、逻辑日、fuzz、bury/leech、retention与题库ReviewPlan适配 | 闪卡/题库复用同一算法，旧SM-2惰性转换保留未评分行/日到期；四档预演/评分、跨时区/逻辑日/撤销与桌面一致 |
| S4.30 | 完善FSRS训练与评估 | S4.28、S4.02 | 训练集、optimizer/evaluate、进度/取消 | loss/参数在预定容差，少样本返回状态，取消保留旧参数 |
| S4.31 | 接入参数写回与重算用例 | S4.29、S4.30、S2.02、S2.30 | 迁移后卡片/日志/牌组选项的Kotlin仓储用例、版本化提交、卡状态重算与旧参数记录 | 训练结果验证后真实写回，重算中断可续，评分/重算按版本对账；S7复用同一仓储接业务页面 |
| S4.32 | 实现Anki调度互操作映射 | S4.16、S4.29 | SM-2/FSRS导入种子、导出/回读映射 | 每卡到期/状态/参数按S0格式规则对拍，导出包可被目标Anki版本读取 |

**阶段出口 S4.G**：S4.01–S4.32全部签收，按引擎/操作交付来源、正式端口、golden、故障与预算记录，作为S5内容/编辑和S7领域输入。

### S5 Compose设计系统、编辑器与工作台底座（34个单元）

目标：沿S1代码按U0→U1→U2→U3→U4→U5依次完成主题、导航、编辑、内容、多文档、工作台底座和输入交互。笔记/PDF/聊天使用正式实现；终端/生态宿主在S6接入，完整业务页及全应用UI在S7签收。公共前置：S4.G。

#### U0 设计系统与导航

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S5.01 | 完善DstuTheme与主题控制器 | S1.11 | 亮暗/auto、9预设/custom、语义Color/Type/Shape/Size | token值与桌面快照一致；主题切换保留页面状态，系统栏同步 |
| S5.02 | 实现基础控件族 | S5.01 | 按componentId展开的按钮/输入/开关/分段/进度/徽标 | 交互状态、键盘/读屏、48dp命中与主题截图逐组件签收 |
| S5.03 | 实现容器和反馈控件族 | S5.02 | 弹窗/Sheet/菜单/侧栏/列表/空态/错误/骨架 | 返回/焦点/层级正确，手机平板状态与桌面语义一致 |
| S5.04 | 生成品牌图标与文案资源 | S0.12、S5.01 | ImageVector、Logo、strings、术语/错误资源生成 | 主要入口双语齐全，品牌展示检查通过，来源NOTICE保留 |
| S5.05 | 实现全导航与设置壳 | S1.12、S5.03、S5.04 | 全视图路由、分区、深链、命令入口与返回守卫 | S0全入口的路由/启动参数/发布条件和返回合同齐全；已交付笔记/聊天页面实际可达，后续领域在所属单元绑定正式页，旋转/重启返回栈恢复 |
| S5.06 | 完成Operit宿主主题适配层 | S5.02、S5.03、S1.20 | 导航/内容/安装外壳适配与UI主题合同；终端/浮窗成品在S6.03/S6.40交付 | 适配层实际示例完成主题/字号/图标/术语对照；S6移植页面逐页使用同一合同验收 |

#### U1 文档编辑核心

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S5.07 | 完善文档与SourceRange | S1.13 | 块/行内/属性/ID、源偏移映射、不变量检查 | 随机编辑映射可逆，移动块保持ID，未知内容原样保存 |
| S5.08 | 完善方言与增量parser | S5.07 | 按语法族展开的parser与局部失效 | 100份以上语料AST对拍，输入只触发受影响范围解析 |
| S5.09 | 完善serializer与保真校验 | S5.08 | 按方言序列化、未改字节复用、语义往返 | 编辑单块其余字节保持，表格/列表/围栏/属性往返 |
| S5.10 | 实现跨块选择与剪贴板 | S5.09 | 块/行内/表格选区、复制/剪切/粘贴/拖放 | 跨应用Markdown/纯文本粘贴保层级、组合字符和顺序 |
| S5.11 | 完善IME与光标输入 | S1.14、S5.10 | 合成区、候选、inset、光标跟随、外接键盘 | 多输入法中文/日文/emoji/RTL，旋转/切后台无重字丢字 |
| S5.12 | 完善命令、逆操作与undo | S5.10、S5.11 | 编辑事务、合并窗口、逆操作日志 | 固定随机编辑undo到初态、redo到终态，Agent修改独立于用户栈 |

#### U2 内容块与交互

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S5.13 | 建全量BlockSpec矩阵 | S0.07、S5.02、S5.09 | 按注册表导出的render/edit/serialize/主题/消息fixture矩阵 | 每个块/语法/生成式组件有归属单元与正常/损坏/交互用例 |
| S5.14 | 实现文本与结构块族 | S5.13 | 段落/标题/列表/任务/引用/分隔/代码逐块子卡 | 编辑、缩进、移动、复制、撤销与桌面方言一致 |
| S5.15 | 实现表格/公式/资源块族 | S5.13、S4.05、S4.10 | 行列编辑、行内公式、图像/媒体/PDF引用逐类子卡 | 表格粘贴/增删、公式编辑、资源加载与页坐标跳转正确 |
| S5.16 | 实现语义与容器块族 | S5.13、S5.05 | callout/toggle/columns/wiki/mention/card、块/斜杠菜单 | 每个容器编辑与序列化完整，未知节点保真，菜单动作可撤销 |
| S5.17 | 完善内容宿主与HTML卡面 | S1.15、S5.01、S0.21 | Anki/工件宿主、CSS变量、night_mode、限定消息 | 自定义模板/脚本亮暗与交互保持语义；越权消息拒绝，资源释放 |
| S5.18 | 接入图表/化学/音乐/地图/画布族 | S5.13、S5.17 | 每engineId单独的正常/损坏/主题/编辑子卡 | S0完整清单逐类渲染与交互；场景节点/边/视口/撤销与导出通过 |
| S5.19 | 接入聊天/生成式块族与增量渲染 | S3.10、S3.27、S5.13、S5.17 | 每blockType/componentId的渲染器、稳定节点流 | 各类流式/完成/中断/错误态正确，审批/重试/交互进统一用例 |

#### U3 多文档与保存

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S5.20 | 实现DocumentSession与tab | S2.G、S5.12 | 打开/激活/挂起/关闭与内存管理 | 至少8文档独立状态，同资源激活、重开滚动位置正确 |
| S5.21 | 实现DraftStore与保存协调 | S5.20 | 本机草稿、提交串行、dirty/失败状态 | 保存确认不覆盖后续输入，各提交故障点重启恢复草稿 |
| S5.22 | 实现OCC/租约与冲突呈现 | S5.21、S2.04 | baseRev/CAS、冲突双方内容与解决用例 | 陈旧写拒绝并保留草稿，双端同时修改有可读差异与决定 |
| S5.23 | 实现历史列表/diff/恢复 | S5.22 | 共享版本与本机保留策略接口、历史面板 | 恢复产生新版本，旧版本保留；桌面打开同一版本内容一致 |
| S5.24 | 实现双链/反链与引用面板 | S5.23、S4.06 | wiki解析、索引、重命名、失效与PDF锚点 | 删除/恢复/重命名双端一致，反链与原文坐标可定位 |
| S5.25 | 接入Agent提案与编辑通道 | S5.22、S3.19 | 选区引用、diff审批、应用/拒绝、独立操作记录 | 并发人工输入保留；拒绝原文不变，Agent撤回走自身回执 |

#### U4 原生工作台

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S5.26 | 完善面板模型与全应用注册 | S1.17、S2.G | Workspace/Panel/ResourceKey、静态appId与动态plugin-view族、手机/平板归属、并排打开/实例/草稿/关闭/快照合同 | 同资源不同视图共存，已有窗口只聚焦不移动；动态视图按owner撤销，关闭面板与取消任务动作明确 |
| S5.27 | 实现手机面板栈与任务切换 | S5.05、S5.26 | 返回/手势/底部切换/分屏布局 | 深栈返回、编辑守卫、旋转与分屏保持焦点/草稿 |
| S5.28 | 实现平板/折叠屏编排 | S5.26、S5.27 | 多栏/平铺/分隔条/姿态与断点 | 跨尺寸面板不丢失，触控/指针均可完成主操作 |
| S5.29 | 完善关闭守卫与布局快照 | S5.21、S5.28 | 保存预检、版本化快照、旧快照映射与恢复 | 取消关闭不改布局/草稿，重启恢复资源和焦点，损坏快照可诊断 |
| S5.30 | 接入笔记/PDF/聊天核心面板宿主 | S5.29、S3.27、S4.06 | 按appId展开的笔记/PDF/聊天正式宿主；资源库/学习页在S7.01–S7.16逐项绑定同一宿主 | 现有三类页面的引用打开、同资源激活、多任务和返回协议完整；资源库/学习的实例和关闭合同与S5.26一致 |

#### U5 输入、无障碍与性能

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S5.31 | 统一键盘/返回/inset/焦点 | S5.11、S5.27 | 预测返回、IME、分屏、焦点恢复与按键分发 | 各层一次返回关闭一层，光标可见，外接键盘顺序正确 |
| S5.32 | 完善TalkBack与可访问性 | S5.02、S5.31 | 已交付页面/块的语义、朗读、对比与减少动效组件规范 | 读屏完成现有编辑与切面板，字号放大无遮挡，48dp命中与语义色通过；后续页面按同规范随单元验收 |
| S5.33 | 完善语言、字体与缩放 | S5.04、S5.32、S0.23 | 文案资源管线和已交付页面的日期数字、CJK/emoji/RTL与缩放 | 系统/应用字号组合与语言切换保持内容、焦点和布局 |
| S5.34 | 实现全局命令与搜索入口 | S5.05、S5.33、S4.24 | 可触控命令面板、搜索/最近/收藏、快捷键 | S0全命令参数/路由/可用性映射齐全，已交付命令实际执行；搜索打开现有资源并保留编辑位置，后续领域动作在所属单元绑定并实跑 |

**阶段出口 S5.G**：S5.01–S5.34全部签收，设计/编辑/多文档/工作台底座及笔记/PDF/聊天面板可用；S6按已冻结主题与宿主接入终端、浏览器和贡献页面。

### S6 终端、依赖中心、Shizuku与扩展生态（44个单元）

目标：沿Operit移植源码按T1→T2→T3→T4→T5→T6→T7依次完成终端、依赖、执行身份、Agent/MCP、包生态、系统增强和正式工作台集成。任务与审批复用S2/S3，全部页面复用S5主题、导航和宿主。公共前置：S5.G。

#### T1 终端

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S6.01 | 完善PTY源码与执行资源打包 | S1.20、S0.14、S0.15 | CMake/JNI、实体nativeLibraryDir、软链接与环境变量检查 | 目标ABI/页大小安装后forkpty、proot/bash/busybox/loader启动通过 |
| S6.02 | 完善非阻塞I/O与resize | S6.01 | PtyIo/门面、输入/输出/关闭/窗口尺寸 | 大量双向字节正确，SIGWINCH生效，fd与读写协程回收 |
| S6.03 | 移植ANSI/Canvas终端界面 | S6.02、S1.11、S5.06 | 颜色/光标/滚动区/选区/输入键盘、Dstu调色 | ANSI序列夹具、CJK/emoji列宽、真彩与大输出滚动通过 |
| S6.04 | 完善多会话与状态存储 | S6.02 | target/cwd/history/输出游标/任务/退出信息 | 多会话不串输出；进程死亡后会话历史恢复并标明进程状态 |
| S6.05 | 完善命令跟踪协议 | S6.02 | OSC帧、commandId/退出码/PGID与回执 | 跨read边界重组、Ctrl+C和正常退出回执一致 |
| S6.06 | 完善隐藏执行与输出存档 | S6.05 | BEGIN/END/PID封装、长输出分块/产物索引 | Activity关闭仍可查输出；10MB级首尾回读正确，取消落单终态 |
| S6.07 | 完善进程组监督与远程取消 | S6.02、S6.06、S1.22、S2.27 | 本地PGID的TERM→等待→KILL；现有Shizuku/Root路径按进程句柄或已核验PID/PGID远程发信号与回执 | 现有四目标的父子进程取消、超时和重复取消完成回收；S6.23按同一端口新增UserService增强后单独复验 |
| S6.08 | 拆出SSH与文件服务连接 | S6.04 | SSH target、SSH/SFTP/FTP独立配置/凭据/服务开关 | 本地target不受远程配置影响，各连接/服务按清单认证、启停与取消 |

#### T2 环境与依赖安装

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S6.09 | 完善rootfs安装/更新/激活 | S1.21、S6.01 | manifest、下载/离线导入、校验、空间/解包/切换 | 各切点中断保持完整环境，失败可查，健康检查后激活 |
| S6.10 | 实现授权挂载与工作区映射 | S6.09、S2.06 | bind清单、逻辑路径/PFD或暂存映射与回传 | `/workspace`读写产物经ResourceStore提交，权限/撤销与路径越界可测 |
| S6.11 | 完善PackageRecipe和安装UI | S6.09、S1.11、S5.06 | 依赖计划/来源/版本/空间/授权、包锁/进度/回执 | 同包安装串行，取消/重启按包管理器状态重入，版本探测确认成功 |
| S6.12 | 实现APT基础recipes | S6.10、S6.11 | 按packageId展开的基础包与dpkg-query探测 | 更新源、安装/卸载Git等、签名错误和包中断状态正确 |
| S6.13 | 实现Python环境recipes | S6.12 | Python/venv/pip/pipx/uv、锁文件与项目隔离 | 测试锁安装后脚本真实运行，环境版本可查、删除venv不动文档 |
| S6.14 | 实现Node环境recipes | S6.12 | Node/npm/pnpm/npx、项目/全局CLI与锁 | 实际安装/探测pnpm，项目锁回读一致，本地CLI/MCP可启动 |
| S6.15 | 实现JVM/Rust/Go/C工具recipes | S6.12 | 按runtime/package拆的JDK/Gradle/cargo/Go/编译工具 | 各工具编译并运行小型真实工程，PATH/JAVA_HOME与版本回执一致 |
| S6.16 | 接入镜像/代理和环境锁 | S6.11 | SourceManager适配、源配置、代理、依赖lock | apt/pip/npm/rust实际访问目标源，切换/断网状态、安装版本可核验 |
| S6.17 | 实现修复/重置/卸载 | S6.12–S6.16 | 包状态检查、repair编排、环境与用户资源分离 | 中断安装修复到ready，重置/卸载保留用户工作区与文档 |

#### T3 Shizuku与Root

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S6.18 | 完善Shizuku连接/授权状态 | S1.22 | Authorizer移植、Sui检测、Binder/UID/权限StateFlow | 未安装/未启动/未授权/授权/撤权/死亡状态准确，实际UID记录 |
| S6.19 | 完善newProcess/PFD执行 | S6.18 | 固定API移植路径、并发双流、退出/句柄管理 | id/pm查询、stdout/stderr灌满、退出与fd基线通过 |
| S6.20 | 完善身份与资源审批接缝 | S6.19、S2.27 | target/UID/cwd/env/task/资源范围和审计 | 四目标按声明执行；授权/拒绝/身份切换和资源交接可查 |
| S6.21 | 完善Root执行器 | S6.20 | libsu/exec/交互模式、实际root身份与取消 | root测试设备执行/审批拒绝/进程组取消，结果与身份一致 |
| S6.22 | 移植首批Android系统工具 | S6.20 | 按commandId拆的包/设置/input/设备信息/层级工具 | 只读查询对照adb；可逆写项审批后验证并恢复 |
| S6.23 | 实现ShellUserService增强 | S6.19 | AIDL版本握手、远程PGID/信号、PTY/PFD | 与直接移植路径同命令结果一致，远程进程组/resize和版本错配可测 |
| S6.24 | 完成撤权/死亡/恢复矩阵 | S6.18、S6.21、S6.23 | 终态、重授权新会话、输出与恢复记录 | 杀后端/撤权结束相应任务，重新授权可用，无悬挂running |

#### T4 Agent与MCP

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S6.25 | 实现requires与依赖计划 | S6.11、S3.20、S3.26 | DependencyResolver、安装计划/审批卡 | 缺依赖产生明确目标/版本/空间计划，拒绝后无安装动作 |
| S6.26 | 实现安装→运行→产物编排 | S6.25、S6.13、S6.14 | 安装确认、健康检查、启动、结果回填 | 一条真实任务从缺包到脚本产物，版本与UID/授权回执一致 |
| S6.27 | 实现用户接管共享会话 | S6.26、S6.06、S3.G | 用户/Agent输入所有权、共享输出、取消 | 用户接管交互命令、交还或取消，Agent按回执更新状态 |
| S6.28 | 接入远端MCP协议/资源 | S1.07、S3.17 | SSE/Streamable HTTP/WebSocket、tools/prompts/resources | 各transport真实initialize/list/call/read、结果清洗、错误/关闭通过 |
| S6.29 | 实现MCP认证与凭据 | S6.28、S2.13 | PKCE/state、回调、刷新/撤销、secret存储 | 授权/刷新/过期/撤销与错误回调，凭据脱敏和本机授权正确 |
| S6.30 | 实现本地stdio会话 | S6.14、S6.26、S6.28 | 环境/runtime/env/cwd、协议stdout与stderr日志分离 | 真实Node/Python server工具调用、取消/崩溃/重开，用户与Agent同状态 |

#### T5 QuickJS、ToolPkg与工坊

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S6.31 | 固定构建脚本引擎与JNI | S0.14、S1.19 | Operit QuickJS和所选PTC引擎的独立构建/版本端口 | 各语言语料对拍、native来源/ABI/页大小核验，PTC保持其既定语义 |
| S6.32 | 实现isolated宿主与能力RPC | S6.31、S2.26 | 引擎预算/中断/消息、服务端逐调用授权 | 死循环/内存/越权/服务死亡用例，主业务及已提交数据保持一致 |
| S6.33 | 移植ToolPkg解析与依赖模型 | S6.32 | manifest/API/资源/子包、顺序、状态与可声明引擎 | 真实包解析/加载；缺依赖/错版本/坏包有明确结果 |
| S6.34 | 移植Compose DSL渲染端口 | S6.33、S1.11、S1.12、S5.03、S5.05、S5.26 | Dstu组件、事件、动态插件视图、编辑扩展/主题变体、导航/文件选择适配 | 真实贡献树可操作，主题/授权URI/工具回调进统一边界；卸载移除全部槽/视图/监听，主题变体仅消费本机已授权提供者 |
| S6.35 | 完成包安装/升级/卸载/导出 | S6.33、S0.24 | `.dstuplugin`、内容digest绑定审批、来源/签名/权限账本、插件专属服务与配置数据事务 | 同版换内容重新审批；owner隔离密钥/数据/调度，撤销停止工具/定时并释放通知/事件，跨端数据不带安装/信任 |
| S6.36 | 完成工坊生成流水线 | S6.34、S6.35、S3.G | 意图/可行性卡→生成/密钥扫描→隔离自检→审批→安装/配置/跨会话修改/更新/导出 | 终端/PDF/聊天真实包调用、普通/密钥配置、用量与时间、诊断/缩减重试/中断恢复逐步取证；桌面full trust不绕过Android能力与target审批 |

#### T6 浏览器与系统增强

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S6.37 | 移植浏览器宿主/会话 | S1.15、S1.11、S5.05 | WebView会话、历史/登录、UA、用户接管 | 网站身份保持、导航/关闭/接管准确，界面使用DstuTheme |
| S6.38 | 完善DOM/ref和上传下载 | S6.37、S3.17 | 按operation拆的snapshot/click/type/select/scroll、文件/下载 | 过期ref与跨origin策略可测，上传/下载产物经资源库读回hash |
| S6.39 | 完善Provider与终端资源桥 | S2.28、S6.10 | 复用Provider、逻辑目录和终端/工作区挂接 | 外部应用授权读写、资源审核/捕获、撤权与越界通过 |
| S6.40 | 移植浮窗聊天/终端入口 | S2.23、S6.04、S3.27、S5.06 | 窗口管理、任务绑定、通知/锁、换肤 | 拖动/收起/返回/关闭，任务与浮窗独立动作，资源最终释放 |
| S6.41 | 移植Shower与虚拟显示 | S6.22、S6.23 | server来源/Binder验证、显示/截图/视频/触控 | shell实际身份启动，截图/滑动/按键生效，后端死亡显式结束/重建 |
| S6.42 | 接入设备Agent工具与审计 | S6.41、S3.17 | 截图/层级/动作/接管、target/UID/审批 | 每个动作可追溯，用户接管/取消按协议执行，可逆测试恢复设备状态 |

#### T7 工作台与贡献宿主集成

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S6.43 | 接入浏览器/终端/媒体面板 | S5.29、S6.04、S6.06、S6.37、S6.38、S4.20 | 真会话/输出/下载/播放绑定 | 多面板30分钟运行，终端resize/接管、浏览器下载与播放状态正确 |
| S6.44 | 接入Compose DSL贡献宿主 | S5.03、S5.26、S5.29、S6.33、S6.34 | ToolPkg贡献的整页/工作台面板注册、Dstu组件、事件、文件选择、导航与关闭/快照 | 真实ToolPkg页面完成操作/审批/授权文件访问，同资源激活、关闭、重开和跨尺寸恢复正确 |

**阶段出口 S6.G**：S6.01–S6.44全部签收，T1–T7终端、依赖、身份、MCP、包SDK/安装/工坊、系统增强和正式面板集成完成，作为S7业务闭环输入。

### S7 学习、生产力与全应用UI闭环（38个单元）

目标：依次将资料、复习、题库、生产力、媒体、首页、设置和插件数据连接成完整用户工作流，随后逐包迁移旧插件，最后签收全应用UI。每行验收包含页面操作、面板/整页接入、持久化、变更捕获、双端读回和DstuTheme对照。公共前置：S6.G。

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S7.01 | 实现资源库查询与批量操作 | S0.19、S0.20 | 文件夹/标签/收藏/筛选/排序/分页与批量用例 | 深目录移动、多选删除/恢复、重启与桌面列表/计数一致 |
| S7.02 | 接入资源打开与链接面板 | S7.01、S5.24 | 领域路由、双链/反链/失效引用页面 | 笔记→PDF/图片/音频跳转，重命名/删除/恢复后两端引用可解释 |
| S7.03 | 接入笔记历史与恢复流程 | S7.01、S5.23 | 历史入口、版本diff/恢复与保留配置 | 恢复旧版生成新版本，正文/资源与桌面版本一致 |
| S7.04 | 接入资产上传与修复流程 | S7.01、S2.08 | 相机/SAF/分享入口、进度、缺失资源/孤儿报告 | 多引用资源删除/恢复正确，修复后桌面/Android均可打开 |
| S7.05 | 实现笔记/资料导入导出 | S7.02、S5.09、S4.08 | 按format展开的Markdown/HTML/PDF/DOCX与资源包向导 | 文件夹导入/冲突处理/导出回环，正文、双链、图片与桌面一致 |
| S7.06 | 接入文档阅读与批注 | S7.02、S4.06、S5.30 | 阅读/批注/生成引用/回跳的Compose页面 | 扫描/旋转文档批注持久化，两端同页同坐标，原文回链可用 |
| S7.07 | 实现牌组与选项管理 | S0.19、S0.20 | 牌组树、配置、预设与计数 | 移动子树/改上限/删除恢复，队列与桌面配置一致 |
| S7.08 | 实现卡片浏览与编辑 | S7.07 | 字段/标签/牌组/媒体/批量/查询 | 批量变更和单卡双端编辑结果一致，媒体/版本完整 |
| S7.09 | 实现模板管理与预览 | S7.08、S5.17、S0.21 | HTML/CSS/字段/cloze模板编辑、版本与应用 | 自定义模板亮暗交互正确，两端修改模板并重渲染同组卡 |
| S7.10 | 实现图片遮挡制卡 | S7.08、S4.10、S0.21 | 遮罩/组/撤销/多卡生成与重编辑 | 图片hash与遮罩归一坐标往返，重开/桌面卡面一致 |
| S7.11 | 接入APKG导入导出业务 | S7.09、S4.15、S4.16 | 导入批次、冲突/媒体报告、导出进度 | 多格式卡/模板/媒体/调度回环一致，中断重入无重复卡 |
| S7.12 | 实现复习会话与评分/撤销 | S7.08、S4.29 | 正反面/队列/四档/暂停/撤销/本机会话 | 评分与日志同事务；撤销恢复快照，两端调度数据一致、本机队列独立 |
| S7.13 | 接入调度配置与逻辑日 | S7.12、S4.29 | 学习/重学、fuzz、bury/leech、retention配置UI | 跨日/时区/同笔记规则、选项生效和桌面数据对拍 |
| S7.14 | 实现训练与重算界面 | S7.12、S4.30、S4.31 | 训练范围/进度/loss/参数版本/确认写回 | 训练/取消/重算重入可查，两端牌组选项和卡状态一致 |
| S7.15 | 实现复习统计与计划 | S7.12、S7.13 | 到期/留存/间隔/耗时、今日计划与筛选 | 固定卡日志聚合与桌面一致，队列/计划/统计互相可核对 |
| S7.16 | 接入AnkiConnect客户端 | S7.08、S1.07 | 服务地址/认证/探测、牌组/模板/媒体/卡片导出与回执 | 局域网Anki服务真实接收卡和媒体，重复操作按回执处理，取消保留本地卡 |
| S7.17 | 实现试卷/图片裁题导入 | S4.05、S4.10、S4.13、S7.01 | 裁题/校对/批次/原图页坐标 | 扫描资料产生题目，失败页可继续，两端题干/图像/来源可读 |
| S7.18 | 实现题目编辑与题库管理 | S7.17 | 各题型、选项/答案/解析/知识点/来源、去重 | 按题型子卡验收，双端字段、图片顺序和重复处理一致 |
| S7.19 | 实现题库格式导入导出 | S7.18 | 按format的字段映射/版本/图片包与回环 | 题干/答案/知识点/图片顺序回环，桌面可继续编辑 |
| S7.20 | 实现手动/规则组卷 | S7.18 | 分区/题型/分值、筛选抽题、排序与复制 | 固定随机源的题集/分值可对拍，桌面看到相同试卷 |
| S7.21 | 实现练习与错题回流 | S7.20 | 作答/判分/解析/标记/恢复与错题记录 | 中途退出保留答案，答错进入错题，桌面作答/判分一致 |
| S7.22 | 实现考试计时与交卷 | S7.21 | 持久时间锚、答题卡、手动/到期交卷与提交回执 | 锁屏/进程终止后重开按时间锚结算，交卷幂等，双端试卷/结果一致 |
| S7.23 | 实现主观题/作文批改改判 | S7.22、S3.G | rubric、模型/人工结果、原文引用和版本 | 按rubric输出可查；人工改判保留模型记录，双端读取同一决定 |
| S7.24 | 接入掌握度与复习回流 | S7.21、S7.23、S4.29 | 既定EMA/反刷/弱点/due偏置、FSRS题库复习计划与四档预演 | 固定作答/评分对拍，SM-2旧行惰性转换、分钟/日到期一致；改判/撤销修正回流，FSRS七列及错题关系双端可查 |
| S7.25 | 实现待办核心与资源关联 | S0.19、S0.20 | 列表/任务/子任务/标签/优先级/附件/批量 | 增改排序/完成/放弃重启保留，两端任务和关联一致 |
| S7.26 | 实现重复规则与实例 | S7.25 | 规则编辑/摘要、生成、单次/全组修改 | 跨月/时区/夏令时按golden生成，重复同步不多建实例 |
| S7.27 | 实现提醒与通知动作 | S7.26、S2.25 | 提醒配置、完成/稍后动作、时区/重启重排 | 授权配置与到期行为明确，动作入账幂等，另一端读取任务变化 |
| S7.28 | 实现番茄/专注会话 | S7.25、S2.23、S0.22 | 开始/暂停/继续/结束/休息/声音/任务关联 | 锁屏与重启按时间锚恢复，实际时长/完成轮次只入账一次 |
| S7.29 | 实现生产力台账与统计 | S7.27、S7.28 | 日/周/标签聚合、撤销和导出 | 双端事件去重与设备聚合正确，撤销后计数对拍 |
| S7.30 | 实现统一回收站流程 | S7.03、S7.08、S7.18、S7.25 | 类型/时间筛选、恢复/彻底删除与引用报告 | 各域删除/恢复/同步后关系正确，保留期按配置执行 |
| S7.31 | 接入媒体学习工作流 | S7.01、S4.19、S4.20、S0.22 | 播放/字幕/书签/继续学习/转写任务UI | 两端交替播放取约定恢复点，字幕/书签和计时去重正确 |
| S7.32 | 实现翻译与双语对照 | S3.G、S4.G、S7.02 | 选区/段落/全文、术语、译文和回链 | 同源段落关联保持，译文/引用双端读回，缓存命中与用量可查 |
| S7.33 | 实现学习洞察与周报 | S7.15、S7.24、S7.29 | 各域指标、趋势、记忆/洞察入口、周报 | 指标可追溯到明细，同数据桌面聚合一致，模型摘要与来源关联 |
| S7.34 | 实现首页与今日 | S7.33、S5.05 | 到期/错题/继续学习/待办/周报卡片、快捷入口 | 卡片操作刷新实际计数，跨尺寸保持布局规则，链接到正确领域 |
| S7.35 | 完成全设置分区 | S7.07、S7.28、S0.23、S5.05 | 按settingId的页面、即时作用、local/portable/secret标记 | 每个设置写入重启生效，便携项双端往返，本机项保持设备归属 |
| S7.36 | 接入插件/技能数据视图 | S7.35、S0.24、S6.35 | owner数据查看/显式清除、配额/墓碑/同步状态与冲突入口 | 实际安装包的数据范围与合同一致；清除产生可回放墓碑，普通配置/密钥/本机信任分别展示，无远端执行授权 |
| S7.37 | 完成旧插件逐包迁移 | S7.36、S6.G、S5.26 | 按packageId拆的服务/工具/UI槽/依赖适配与数据迁移；依赖业务端口已由S3–S7交付 | S0基线每包实际安装/运行/更新/撤销和双端数据对照，官方UI贡献逐项覆盖，宿主/信任/资源生命周期完整 |
| S7.38 | 签收UI性能与视觉矩阵 | S5.G、S6.G、S7.01–S7.37 | S3–S7全部业务/生态页面、三档设备/主题/长内容及全appId宿主报告 | S1.27预算、桌面风格、TalkBack/输入与U0–U5规范逐页通过；S8新增同步/恢复中心在S8.14/S8.25同规范验收，S9完成全APK签收 |

**阶段出口 S7.G**：S7.01–S7.38全部签收，资料、复习、题库、生产力、媒体、首页和双端继续使用七条闭环、旧插件逐包迁移及全应用UI矩阵通过，M3完成。

### S8 全量同步与备份恢复硬化（26个单元）

目标：沿S0合同和S1/S2实现，依次完成provider、同步事务/冲突/资产、版本/身份与备份恢复硬化；使用S3–S7已经完成的全业务页面，分别签收同步与备份恢复报告。公共前置：S7.G。

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S8.01 | 硬化wire与无损编码 | S1.24–S1.26、S0.18 | change/key/manifest/source归属、payload codec与畸形数据隔离 | 格式所需字节精确比较，普通JSON语义一致，int64/u64边界无截断 |
| S8.02 | 完善WebDAV provider | S8.01、S2.29、S2.30 | 列举/分页判定、创建/读取/写入/删除/移动、认证/错误/Range能力，接既有流式资源端口，不以整对象 ByteArray 扩大上限代替流式实现 | 真实端点大对象流式读写与桌面互见，内存有界；截断/配额/中断/证书按能力表处理，取消释放连接/暂存且未确认对象可重入 |
| S8.03 | 实现S3 provider | S8.01 | 签名、endpoint/region、分页、上传/下载与条件能力 | 真实兼容端点大对象/hash/分页/认证和限流用例通过 |
| S8.04 | 实现FTP provider | S8.01 | 显式能力探测、LIST/MLSD解析、REST/改名/删除、编码 | 中文/空格文件与大对象往返，网络中断后按服务器能力续作或重传 |
| S8.05 | 完成provider契约与仓库检查 | S8.02–S8.04、S0.20 | conformance suite、根/instance/加密标记、目标lease与验证发布 | 三provider同用例；双方争用按协议处理，读回不符保留pending |
| S8.06 | 完善pending发布事务 | S2.29、S8.01、S8.05 | 沿 S2.29 完善本机复合发布回执、预留→上传→回读→published确认与取消、新仓库显式全量 seed；wire 保持桌面格式 | 未发布预留复用 key/seq/ciphertext 后才分配后续序列；桌面无需 Android 本机表即可交错同步，远端已写先核验；跨库重复日志 ID 正确逐库确认；新仓库 seed 完整且不会因普通恢复残留日志只上传部分数据 |
| S8.07 | 完善下载apply与游标 | S2.30、S8.01、S8.05 | 序列/gap、FK依赖、隔离区、提交后游标与重放 | 乱序依赖按协议落地，故障重放幂等，缺口/损坏可诊断 |
| S8.08 | 完善字段合并/冲突/回声抑制 | S8.06、S8.07 | 分类策略、时间/LWW/集合/计数、冲突决定与审计 | 双端同字段/不同字段/删除编辑/同时间样本收敛，自上传不回声放大 |
| S8.09 | 完善墓碑与删除水位 | S8.06、S8.07 | 记录/blob/asset/workspace删除、保留/prune和恢复 | 删除/重建/离线旧端回归按协议收敛，引用中的对象清理安全 |
| S8.10 | 完善资产传输与文件名映射 | S0.21、S8.06、S8.07、S8.09 | 资产根、portable路径、大小写/Unicode、hash/引用及流式大对象传输，复用 S2.29–S2.30 资源端口和加密会话 | 中文/emoji/NFC/NFD/保留名对拍，越界拒绝；桌面大附件在 Android 可打开，上传/下载内存有界、流式 hash 一致、取消不发布半对象 |
| S8.11 | 完善去重/配额/低空间 | S8.10 | 本机内容去重、云对象引用/配额、流式暂存、低空间清理与 GC | 明文按内容 hash 去重；加密仓库复用桌面密文对象键，nonce/引用不覆盖；流式大对象不产生整包内存副本，提交未知对象保留、低空间/取消保全活动数据 |
| S8.12 | 完善弱网/限流/取消策略 | S8.02–S8.04、S8.06、S8.07 | provider预算、重试/backoff、网络切换、流式大对象断流/取消和 checkpoint | 固定网络故障下幂等完成，按预算结束并保留 pending；复用已确认字节/对象键与会话合同，取消释放流/句柄/连接，重入不重复发布 |
| S8.13 | 执行提交/序列故障点测试 | S8.06、S8.07、S8.09、S8.10 | 预留后PUT前、PUT后回读前、回读后本地确认前注入与owner记录 | 各点重复终止重开，seq/游标/资源保持一致且无重复副作用 |
| S8.14 | 实现同步中心UI | S5.G、S8.05、S8.08、S8.12 | 目标/方向/进度/领域delta、冲突/审计/重试 | 真实事件驱动计数、桌面术语与DstuTheme一致；冲突可解决，手机/平板宿主、返回、读屏、输入与预算通过 |
| S8.15 | 签收引用与数据域闭环 | S0.21–S0.24、S8.09、S8.10 | 按domainId的引用/资源/打开/删除恢复用例 | 源记录与字节两端可读，缺失可查/补取，元数据到达不触发跨端执行 |
| S8.16 | 执行版本与schema矩阵 | S0.20、S8.05 | schema指纹、触发器、wire组合的真实包报告 | 同schema完整往返；异schema提示/数据保留/升级后继续；新协议按协调版本用例 |
| S8.17 | 完善配对与设备/实例身份 | S2.15、S2.16、S2.18、S8.01、S8.06、S8.07 | 现有配对格式、secret通道、绑定、轮换与失效；普通换机默认恢复业务+新 writer+重配原 instance，延续 S2.16 基线和 S2.18 旧标记合同 | 同仓库独立 device id，过期/错误配对拒绝；普通换机无需管理员签名或 sidecar，恢复重配后双端持续同步；确需连续身份迁移的高级入口与普通流程分开 |
| S8.18 | 完善manifest与coverage | S2.10、S0.19、S0.20 | 域注册、full/partial、size/hash、消费计划 | 覆盖证据与包内容一致，未覆盖域保留，异常包切槽前拒绝 |
| S8.19 | 完善备份写出和v2流式加密 | S2.13、S8.18 | 一致性快照、资源流式打包、DSBK-v2、会话复用、进度/取消与暂存发布 | GB级包内存有界，桌面解密/hash一致，分阶段取消/失败包标识清晰并清理未提交暂存，不靠提高整包内存上限 |
| S8.20 | 完善恢复计划与原子激活 | S2.11、S2.16、S2.17、S8.18、S8.19 | 验证/预览/流式staging/切槽/完整性报告，复用普通恢复同步基线与真实资源引用规则 | GB级双向恢复内容/引用一致、内存有界，各切点保持完整活动槽；零引用残留不误拒，真实引用缺失可定位，恢复重配后与桌面继续同步 |
| S8.21 | 完善v1兼容/转换 | S2.14、S8.20 | 将 S2.14 已有 v1 开发转换工具产品化：用户可操作的导入/转换入口、格式/KDF预算、认证、进度/取消、原子v2输出 | 合法旧包转换后内容回读一致，用户无需仓库绝对路径或手写环境变量；错密经KDF/认证判定，超预算在分配前提示，取消不覆盖原包且临时文件清理 |
| S8.22 | 完善migration sidecar接管 | S2.15、S8.13、S8.17、S8.20 | 快照/源安装绑定、hash/敏感字段与唯一写者移交；只为确需保留原序列的高级托管迁移提供 sidecar，旧写者永久撤权与独立授权维持既定前提，claim 不冒充旧桌面停写机制 | 普通 restore 新 writer+重配仍是默认换机；高级迁移在旧写者已撤权后延续核验序列，错配/重复接管/未知结果可恢复；普通用户不被强制走管理员授权，不另造同步引擎 |
| S8.23 | 接入自动备份与保留策略 | S2.25、S8.19、S8.20 | 调度约束、唯一job、保留/prune和通知 | 条件满足运行、中断续作，最新版可恢复且保留数正确 |
| S8.24 | 执行备份恢复拒绝/故障矩阵 | S8.19–S8.23 | 密码/篡改/超版/缺域/损坏/低空间/中断用例 | 活动槽与原数据保全，错误码和诊断指向包/域/阶段 |
| S8.25 | 实现备份与恢复中心 | S5.G、S8.18–S8.24 | 列表、覆盖预览、恢复确认、进度、自动备份设置 | 手机/平板真实导出→查看→恢复，操作结果与库/资源一致；主题、宿主、返回、读屏、输入与预算逐项通过 |
| S8.26 | 分别签收同步与备份预算/隐私 | S3.G、S4.G、S5.G、S6.G、S7.G、S8.12、S8.14–S8.17、S8.24、S8.25 | A同步签收记录；B备份恢复签收记录，各附预算/secret扫描 | A核对全域往返、provider/版本与故障；B核对coverage/格式/大小/加密/身份与恢复。A/B独立记录结果，S8.G汇总二者 |

**阶段出口 S8.G**：S8.01–S8.26全部签收，交付相互独立的全业务同步报告与备份恢复报告，作为S9候选发布输入。

### S9 全量签收与替换发布（19个单元）

目标：以绑定提交/构建号的候选APK依次完成产品、数据、视觉、设备、运行与发布签收；新增修复回到所属单元并补充相同候选构建的覆盖证据。公共前置：S8.G。

| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |
| --- | --- | --- | --- | --- |
| S9.01 | 汇总全功能可追溯矩阵 | S0.25 | 场景/单元/提交/测试/截图/设备/负责人索引 | 每项有正常/异常证据，缺失项可自动列出 |
| S9.02 | 签收官方入口/工具/包生态 | S9.01 | 视图、应用、设置、菜单、命令、ToolPkg逐项清单 | 每项真实主流程与失败路径，官方贡献和基线包逐一关闭 |
| S9.03 | 签收桌面视觉与品牌 | S7.38 | token、组件状态、页面并排图与品牌报告 | 全主题/字阶/图标/术语与桌面基线，移植页面统一DstuTheme |
| S9.04 | 签收设备形态与布局 | S9.02 | 手机/平板/折叠/分屏/目标API矩阵 | 列表→打开→编辑→保存→重启，旋转/跨尺寸保留草稿/焦点 |
| S9.05 | 签收读屏与输入 | S9.04 | TalkBack、字号组合、RTL、键鼠/返回用例 | 一级任务与编辑器/终端可操作，焦点/命中/键盘遮挡检查通过 |
| S9.06 | 冻结发布golden与预算 | S9.01 | 设计/格式/领域/设备样本hash和候选报告 | 同一候选构建的golden和基准一致，有意变化有来源记录 |
| S9.07 | 执行双端连续使用 | S9.06 | 正式桌面包与候选APK的工作流/快照/hash | 笔记/聊天/学习/题库/待办/终端产物跨端继续、删除恢复与统计一致 |
| S9.08 | 执行provider/版本矩阵 | S8.16、S9.06 | WebDAV/S3/FTP与支持schema/wire组合报告 | 同schema完整往返，异schema升级保全，再升级继续同步 |
| S9.09 | 执行同步故障与身份恢复 | S8.13、S9.08 | 网络/时钟/终止/墓碑/缺资源与pending记录 | 提交点故障恢复seq/游标/引用正确，独立device与唯一写者保持 |
| S9.10 | 执行终端/Shizuku/依赖矩阵 | S6.G | 普通/Shizuku/root设备、包安装/接管/撤权报告 | 四target身份、PTY、依赖版本、取消/后端死亡与凭据保护逐项通过 |
| S9.11 | 执行后台/OEM/耗电矩阵 | S2.24、S6.40 | 30分钟锁屏、适用持续职责长跑、Doze/OEM/停止报告 | 任务/通知/checkpoint一致，WakeLock释放，电耗达登记预算 |
| S9.12 | 签收容量与性能 | S1.27、S9.04 | 启动/帧/长内容/搜索/引擎/同步/终端预算报告 | 三档设备P50/P95和峰值内存在预算内，差异有定位与解决记录 |
| S9.13 | 核验native与执行资源打包 | S6.01、S4.01 | ABI、ELF/资源分类、16KiB、实体/软链检查 | 目标页大小设备启动PTY/PRoot/PDF/OCR，全部native来源匹配 |
| S9.14 | 核验来源、许可与APK入口/配置 | S9.13 | SBOM/NOTICE/源码材料、四类verify、DEX/内容allowlist、字体/R8 mapping、网络/导出组件与权限报告 | 所有资源/入口来源与hash对应；网络/导出权限有用途、R8栈可还原；旧运行时排除、品牌/设计检查通过 |
| S9.15 | 执行签名/版本/原位升级 | S0.15、S8.22 | 实包证书/版本核对、旧数据/资源/身份接管记录 | 同签名且版本递增，导出导入/回读与继续同步通过；原数据源保留至用户确认 |
| S9.16 | 执行加密与恢复故障演练 | S8.24、S9.15 | v1/v2/密码/损坏/大包/切槽/sidecar记录 | 错误包保全活动槽，双向恢复后引用/身份正确，大包预算通过 |
| S9.17 | 建受控签名与放量流水线 | S9.02–S9.16 | 签名、产物hash/provenance、分档门槛与暂停/恢复、前向修复演练 | 触发崩溃/ANR/启动/同步门槛即暂停；修复以更高versionCode候选重跑golden、签名/升级与同步子集再继续，用户数据保留 |
| S9.18 | 交付帮助与迁移/维护材料 | S9.17 | 发布说明、支持矩阵、配对/导入、关于/许可与运维手册 | 文档/帮助与实际候选行为一致，用户可完成升级和同步配置 |
| S9.19 | 切换Android发布链并退役旧链 | S9.17、S9.18 | 新工程/Operit源码/适配补丁/构建规则入版本库与发布标签，模块更新手册，新Gradle CI、旧链退役、历史/格式/语料归档 | 新链正式发布、桌面链保持；在维护分支演练固定来源→补丁重放→候选构建→许可/回归/安装，原资料与接管记录可追溯 |

**阶段出口 S9.G**：S9.01–S9.19全部签收，M4发布包、完整证据矩阵、升级/数据接管和旧移动链退役完成。

## 11. 串行执行总队列与排期

```text
S0 范围/来源/设计/数据出口/同步合同与桌面捕获 → S0.G
 ↓
S1 新工程与九类纵切/真实双向同步 → S1.G
 ↓
S2 数据/资源/任务/后台/权限/同步底座 → S2.G
 ↓
S3 对话/模型/工具/Agent/聊天页面 → S3.G
 ↓
S4 文档/媒体/检索/模型/学习引擎 → S4.G
 ↓
S5 DstuTheme/编辑器/内容/工作台底座 → S5.G
 ↓
S6 终端/依赖/Shizuku/MCP/ToolPkg/系统增强/宿主 → S6.G
 ↓
S7 完整业务页面/学习与生产力闭环/逐包迁移/全应用UI → S7.G
 ↓
S8 全量同步硬化/备份恢复/独立签收 → S8.G
 ↓
S9 候选APK全量签收/升级/发布/旧链退役 → S9.G
```

### 11.1 唯一开工顺序

| 总队列位置 | 执行单元 | 开工输入 | 完成后执行 |
| --- | --- | --- | --- |
| 001–025 | S0.01→…→S0.25 | 锁定桌面与Operit基线 | S0.G→S1.01 |
| 026–052 | S1.01→…→S1.27 | S0.G | S1.G→S2.01 |
| 053–083 | S2.01→…→S2.31 | S1.G | S2.G→S3.01 |
| 084–112 | S3.01→…→S3.29 | S2.G | S3.G→S4.01 |
| 113–144 | S4.01→…→S4.32 | S3.G | S4.G→S5.01 |
| 145–178 | S5.01→…→S5.34 | S4.G | S5.G→S6.01 |
| 179–222 | S6.01→…→S6.44 | S5.G | S6.G→S7.01 |
| 223–260 | S7.01→…→S7.38 | S6.G | S7.G→S8.01 |
| 261–286 | S8.01→…→S8.26 | S7.G | S8.G→S9.01 |
| 287–305 | S9.01→…→S9.19 | S8.G | S9.G→完成交付 |

总队列位置统计主单元，阶段出口在本阶段末执行；子卡在父单元的位置内按第10.2节展开。应用的多任务、并行模型、工具并发和多面板是产品运行能力，继续按功能矩阵实现；开发工作、真机验收、双端补齐和发布准备按上述队列串行完成。

### 11.2 串行工期计算

以一个执行通道和每周5个工作日计算；每个单元的实现、移植适配、双端改动、测试、修复和证据归档计入该单元耗时，阶段出口核验单独计入对应阶段。

- 普通单元：1–3个工作日一份可验收增量，较大单元继续拆子卡。
- 参数化父单元：公共搭建耗时＋按顺序展开的全部子卡耗时，使用这一合计替换父单元粗估，父子工作量只计一次。
- 阶段工作日：本阶段所有普通单元和参数化父单元合计＋阶段出口核验工作日。
- 总工作日：S0到S9阶段工作日逐项相加；总工作周＝总工作日÷5，里程碑日期按队列累计。
- S0.25冻结第一版成员顺序、单元/子卡工作日与里程碑日期；S1.G按实际纵切耗时校正余下单元；后续每个阶段出口滚动更新余下排期。

### 11.3 里程碑

| 里程碑 | 完成位置 | 交付物与签收证据 |
| --- | --- | --- |
| M0 | S0.G | 全功能/采用矩阵、固定来源、设计/格式/同步合同、桌面捕获、旧数据出口和串行排期 |
| M1 | S1.G | 新APK、DstuTheme/导航及九类纵切，真机与明文/加密桌面笔记往返 |
| M2 | S2.G | 数据/任务/同步底座，迁移golden、常驻/恢复、授权与进程测试 |
| M3 | S7.G | 全业务、原生编辑/工作台、终端/生态、全应用UI、逐域同步/资产与后台闭环 |
| M4 | S9.G | 正式发布APK、双端连续使用、升级/数据接管、性能/安全/许可与旧链退役 |

## 12. 工程、来源与验证

### 12.1 模块接入与平台

使用固定提交的源码fork/vendor接入terminal、quickjs、showerclient及选定app源码；适配器集中保存宿主改动。上游QuickJS/WAMR等依赖固定到具体commit，原生资源按hash登记。

Operit终端移植基线为API26、arm64。S0登记完整版本minSdk与ABI决策；同一APK的库依赖按统一minSdk构建。API24/25支持需要完成对应源码适配与安装运行测试；较高minSdk模块采用独立安装交付时定义专门组件协议和安装矩阵。

主产品与存量设备升级安排写入发布配置，运行资源按需下载并提供离线安装包。rootfs、工具链、模型分别有版本、空间、hash、来源与卸载策略。

### 12.2 源码与许可

`config/operit-adoption.json`记录上游提交、模块、适配器、补丁和测试；`config/runtime-assets.json`记录原生库/rootfs/模型/工具资源。

逐文件/模块核对版权、SPDX及实际许可证声明，交付源码、修改记录、NOTICE与适用的重构建/重链接材料。Ubuntu包、bash、busybox、proot、QuickJS、Shower、模型与字体分别登记。

PTY的`pty.c`与Kotlin代码从源码构建；terminal既有proot/bash/busybox/loader等运行资源获取相应上游与构建来源，生成可核验交付。shower-server.jar记录对应源码和构建路径。

终端启动资源采用Operit实体nativeLibraryDir路径方案：配置`packaging.jniLibs.useLegacyPackaging = true`，安装后校验proot/bash/busybox/loader实体文件和`files/terminal/usr/bin`软链接，统一PROOT_LOADER与LD_LIBRARY_PATH。API/ABI、ELF/脚本分类、可执行权限与16KiB页兼容进入打包测试。

### 12.3 测试与性能

- JVM：领域状态机、格式/加密、HLC/冲突、FSRS、Markdown/undo、工具策略。
- Instrumentation：SQLite/迁移/FTS、Keystore/SAF、FGS/WorkManager、Shizuku、PTY、JNI与Provider。
- Compose：全部官方视图、编辑器IME、草稿/OCC、工作台、终端/安装中心、权限和常驻设置。
- 视觉：版本化桌面token/文案/图标与Android资源golden，亮暗/调色板/字号/手机平板组件与关键页面对照。
- 跨端同步：真实桌面客户端与APK连接同一仓库，各用独立device id；记录/资产/偏好/冲突/版本/重启与恢复矩阵。
- 协议：真实模型、SSE切片、MCP/OAuth、本地stdio、云provider、CLI版本探测。
- 故障：事务/导入/上传各切点、系统回收、force-stop重开、Binder死亡、撤权、断网、低空间、恶意输入。
- Benchmark：启动、输入/滚动帧、流更新、搜索、导入/索引/备份吞吐、终端I/O、PSS/CPU、电耗。

S1固定设备/语料及P50/P95预算。golden按桌面commit与格式版本管理；对照器在开发机/CI执行。

新工程定义Gradle任务并纳入必需CI：单元测试、connected instrumentation、Compose用例、benchmark、lint、release组装与产物来源检查。

### 12.4 产物检查

`verifyNoLegacyArtifacts`检查dstu旧运行时排除清单；`verifyArtifactOrigins`检查Operit与第三方源码/原生库/脚本/资源来源。

`verifyDstuBranding`核对应用label/Logo、启动页、导航、通知渠道、关于/帮助/协议入口和资源文案；源码版权、NOTICE、许可证和上游致谢按来源清单保留。`verifyDesignTokens`核对桌面快照与Kotlin资源版本，组件测试检查语义配色与字号出口。品牌展示、主题值、资源来源分别签收。

`config/content-engine-allowlist.json`逐项登记内容/浏览器/脚本入口、hash、用途和能力；CLI运行环境独立登记。检查APK资源、DEX依赖、native库、ABI/页对齐、字体/R8、网络配置、签名与版本。

## 13. 发布与旧链退役

1. `dstu-android/`及移植源码、适配器、Gradle工程进入版本控制。
2. 核实实际发布包名和签名，执行原位升级演练；证书依据`resources/app/android/release-signing-cert.sha256:8`及实包核对。
3. S0.15并列核对`mobile/scripts/build-apk.mjs:194`与`generate-version.mjs:72`两条版本计算路径和待覆盖实包，确定统一versionCode单源；Kotlin/Gradle据此实现，并逐包验证新版本递增。
4. 完成旧数据导出、Kotlin导入、资源回读和同步身份交接，保留原数据源至用户确认。
5. 正式版本使用新Gradle链，替换`.github/workflows/build-all-platforms.yml:227`的旧Android打包job。
6. 新版本通过M4后退役旧mobile构建、Capacitor配置和相关CI；保留Git历史、格式规范和验收语料。
7. 模块更新按固定来源、补丁重放、构建/测试、许可和安装验证流程执行。

## 14. 首批串行开工清单

| 顺序组 | 逐项执行编号 | 全部签收后提供 |
| --- | --- | --- |
| A 源码与产品清点 | S0.01→S0.02→S0.03→S0.04→S0.05→S0.06 | 固定来源、官方入口、工作台与业务/设置清单 |
| B 数据与视觉基线 | S0.07→S0.08→S0.09→S0.10→S0.11→S0.12 | 内容/schema/wire规范、同步补齐输入、token与术语 |
| C 资源与交付条件 | S0.13→S0.14→S0.15→S0.16→S0.17 | 采用/许可、平台/签名、旧数据出口、语料与设备 |
| D 双端同步合同 | S0.18→S0.19→S0.20→S0.21→S0.22→S0.23→S0.24 | 协议golden、分类/版本、模板/附件/媒体/配置/插件合同与桌面捕获 |
| E 冻结队列 | S0.25→S0.G | 有序子卡清单、首版工期、证据与M0 |
| F 工程与数据纵切 | S1.01→S1.02→S1.03→S1.04→S1.05→S1.06 | 正式工程、捆绑SQLite、迁移与笔记/资源读写 |
| G 网络与任务纵切 | S1.07→S1.08→S1.09→S1.10 | HTTP/SSE、任务账本、FGS和重启对账 |
| H 主题与编辑纵切 | S1.11→S1.12→S1.13→S1.14→S1.15 | DstuTheme、笔记页、AST、输入/undo、内容/浏览器宿主 |
| I PDF与多面板/算法纵切 | S1.16→S1.17→S1.18→S1.19 | PDF正式端口→四面板草稿/关闭→FSRS→PTC |
| J 终端与系统执行纵切 | S1.20→S1.21→S1.22 | PTY/ANSI、PRoot/依赖安装、Shizuku实际执行 |
| K 同步与基准签收 | S1.23→S1.24→S1.25→S1.26→S1.27→S1.G | secret/加密、明文/加密桌面往返、九类纵切与M1 |

每组完成后执行下一组，组内完成当前单元及全部子卡后执行右侧下一编号。S1.G后从S2.01开始，按第11.1节单通道推进直至S9.G。

**交付主线：dstu-node安卓版。Kotlin/Compose承载原生业务与桌面一致的视觉体验，同一套用户数据通过双向同步连接桌面；直接吸收Operit成熟源码，增加内置终端、Shizuku、依赖安装、前台常驻和完整Agent工具链。**

## 15. 当前计划修订、冻结执行输入与阶段签收

- S0.25 的 `config/execution-plan.json` 与 `config/execution-inputs.json` 保留历史原字节：302 主单元、10 出口、312 队列位置及原卡/场景。原计划在 Git `36511a1ba27ef2db7c45636162575a2b1c6564e8` 的旧字节继续用于 S0 哈希/行号回放；本文件工作区正文按用户授权原地更新，不重新捕获或签收历史基线。
- 当前执行由本文件 305 主单元/10 出口/315 队列位置与 `docs/plans/2026-11-16-native-modular-execution-plan.md` 的 280 项分层责任共同定义；`config/planning/active-plan.json` 登记本次三项新增及旧 S2.16–S2.28→新 S2.19–S2.31 映射。`node tools/planning/verify-plan.mjs` 验证当前编号/依赖/覆盖与历史哈希，`config/stages/s2-progress.json` 的 next=S2.16 指基线补修，不是旧 runtime 单元。
- 已完成 S0 不重新计入未来排期，实际工作日未知时保持 null；S1 及之后以 2026-11-17 为未来排期起点，按五天工作周累计，不计法定节假日，日期仅作保守估算而非承诺。参数化父单元只计公共搭建与全部子卡之和；S1.G 根据真实纵切耗时重新校准剩余排期。
- S0.G 的独立核验入口为 `npm run verify:stage-gate`。`config/schema/final-lock.json` 绑定135份冻结历史SQL与3份项目协调SQL的字节、checksum、实际四库指纹、分类/触发器及新建/存量升级/重启证据；最终目标为vfs20261117、mistakes20261006、chat_v220261111、llm_usage20260826。
- `config/s0-g-main-review.json` 为主智能体签收回执，必须绑定最终锁、SQL集合、四库指纹及25单元证据；独立工具不自行签收。全量 `npm run verify` 同时执行25单元与已签收的S0.G，不允许草案或未签状态冒充M0完成。
- S0交付是冻结来源、平台无关合同、桌面源增量、公开fixture与回放工具，不是新APK。Kotlin/Compose工程仍从S1.01开始；许可补证、三项16KiB重建及设备性能/正式升级实包等后续门禁保持，不因M0签收提前关闭。
