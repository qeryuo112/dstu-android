# S1 交接：承接已签收的 S0 基线

- 记录时间：2026-11-16T04:17:24+08:00（Asia/Shanghai）
- 当前状态：S0.G / M0 已 accepted；S1.01 尚未开工。本文件是 S1 的基线承接与准备文档，不是 S1 实现、验收或完成声明。
- 原完整交接：[docs/archive/handoff-s0.md](archive/handoff-s0.md)。归档为原 487 行、77,108 字节，SHA-256 `f22db4c71a074f3c81391c34a40033601826eae59d92202de55a2797c8063158`；按原字节移动，无追加标记、无内容改写。
- 本轮范围：仅交接文档分阶段归档。未进入任何 S1 实现，未修改 rules、计划、配置、tools、package，未提交 Git，未运行会写冻结日志的测试。

## 1. S0 已实现与签收基线

### 1.1 S0.G / M0 签收

- `config/s0-g-main-review.json` 为 `accepted`，签收人 `main-thread`，签收时间 `2026-11-16T03:59:35+08:00`；25 个 S0 单元证据齐全，`m0.complete=true`、`m0.signedOff=true`。回执 SHA-256 为 `5a9fc4d66b4d8ae4189cf40b67b16749e32cdd416160563b23aca3922bb2f73d`。
- `config/schema/final-lock.json` 为 `validated`；其自身 `signedOff=false`、`m0.signedOff=false` 是锁不自我签名的设计，最终 M0 依据是上述独立主审回执。final-lock SHA-256 为 `5fd1b3a4bd4288ce577c3f17677c9ca6accfab56c03a80130a8797baed5c08e8`。
- S0.G 真实组装 135 份上游历史迁移 + 3 份协调迁移 = 138 份 SQL；四库 fresh、既有上游库升级和 restart 均通过，`integrity_check=ok`、`foreign_key_check=0`，restart 全部 0 迁移。
- 最终签收运行记录为 `logs/s0-g-main-signed-verify.log`；全量入口为 25/25 + S0.G、302 计划单元、5717 关联、56329ms，记录见 `logs/s0-final-verification.log`。
- 最终 registry 为 126 项、61 个 RowSync；wire current 4、minimum reader 3、30 天兼容租约，v5 为 inactive 且未注册生产入口。

### 1.2 源 commit / tree

| 来源 | commit | tree | 状态 |
| --- | --- | --- | --- |
| 本仓 `dstu-android`（本轮文档改动前 HEAD） | `36511a1ba27ef2db7c45636162575a2b1c6564e8` | `af3f5448de359aa500c8007da3ab5ca092b1a510` | 本轮仅新增文档改动，不提交 |
| 冻结桌面 `dstu-node` | `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` | `3bee0f058851b9eb113bdc036d9f60433ed29944` | clean，固定不再跟进 |
| Operit | `7a39941bad80209973c535046a18195c5da9e647` | `1c74219f7b7fdebebd07e99830877559e2dcd3e6` | clean，冻结 |
| terminal | `e4646680663789002b43bbfffa6e69e65c35e614` | `bb5420c2fd21040878c25240607996aed6afbd77` | clean，冻结 |

- 来源锁为 `config/source-lock.json`，`lockedAt=2026-11-16T01:20:26+08:00`，SHA-256 为 `738677c72d82a4c50fa14c4711a2afe7c6130bfd52f6bb1d458c1a4ad084fc64`。
- 桌面基线只作为功能重写目标；其上游 mobile 0.20.1 仅为观察事实，`adopted=false`。不采用上游自带 Android/Capacitor/mobile 运行链，也不把 mobile 运行证据当作桌面功能已交付。

### 1.3 SQL 集合、四共享库与迁移

- SQL 集合：135 upstream + 3 coordinated = 138；集合 SHA-256 为 `a3c70a046b3d981c2ceba348ad9e7a14e3113d076925e6dbad50ccc67a6d2675`。
- 四共享库 fresh 分别应用 75/30/24/8 份迁移；升级路径 vfs `20261115→20261117` 应用 2 份、mistakes `20261005→20261006` 应用 1 份，chat_v2 与 llm_usage 无变化。四库 restart 均为 0 迁移。

| 数据库 | 版本 | 迁移数 | canonical fingerprint |
| --- | ---: | ---: | --- |
| vfs | 20261117 | 75 | `4370a412fa4d4dd7a23cf2308735f75c147ba30ab22329e7829a9bb1d56161d2` |
| chat_v2 | 20261111 | 30 | `2a29bf29771c7381db4cac8cdbad325cddad8d86bc06795a369176e4654e8b7a` |
| mistakes | 20261006 | 24 | `fdd31bdf68ccfa505b88e946a24eb4ae66dc43e13ccf3102f045a67af36b4e31` |
| llm_usage | 20260826 | 8 | `ed69ae106103c3b48460d898be47fdfe12124e26f392e4636d1fe7816beb6245` |

### 1.4 S0.21–S0.24 合同基线

| 单元 | 合同与协调迁移 | 已签收边界 |
| --- | --- | --- |
| S0.21 | `config/contracts/templates-assets.json`、`asset-transport.json`、`chat-attachment.json`；`mistakes/V20261006__portable_anki_templates.sql`（SHA-256 `205f7da3f916b65c56b20bdd1472213837dcf359cc25cfe2697a6a3bbf6646d9`） | `custom_anki_templates` 由 BackupOnly 改为 RowSync/Lww，增加 `device_id`、`local_version`、`deleted_at` 3 列、6 索引、3 触发器；未改动的内置模板不捕获。资产根为 `active/anki_media` 与 `active/chat_attachments`，明文/DSBK 传输、墓碑、恢复与坏对象拒绝已验。聊天附件适配器只做受控落盘、hash/大小校验与数据库元数据事务；实际发送流程接线属于 S3.13。 |
| S0.22 | `config/contracts/media-time.json`、`media-audit.json`；`vfs/V20261116__portable_media_progress.sql`（SHA-256 `1beeb57d996340270b2f8acfe3fe6608a7432fda5c4178aca6034abaad00d116`） | `portable_media_events` 按 `event_id` 去重，先按设备再全局汇总，逻辑日封顶 86400 秒；恢复点按实际观察时间/device/id 选择，允许后退；字幕按 resource/plan/version/index 选确定性 winner，落选事件仍保存；事件不可改写或删除。旧本机媒体/计时表保持非 RowSync。 |
| S0.23 | `config/contracts/portable-config.json`、`config-audit.json`；无新增 SQL | 573 个设置使用点均有分类证据：portable 20、local 503、secret 50；未知键默认 local。`theme` 与 `theme_palette` 作为受校验便携值；插件主题变体选择、启用、信任、权限预设、调度历史保持本机。插件秘密走 `secret.plugin.*` 安全存储，不作为偏好同步；远端配置不授予信任或执行权限。 |
| S0.24 | `config/contracts/plugin-data.json`、`plugin-audit.json`；`vfs/V20261117__plugin_skill_data_contract.sql`（SHA-256 `e0c9ba13527bf4ae0d1409dadc890b6af63a4cc86017b8b3b2a051289742ee38`） | `portable_plugin_records` 以规范 JSON `[owner, namespace, record_id]` 绑定身份，行级 Lww；未知 JSON 保留，源 hash 不是输出权限。约束为记录深度 ≤16、记录 ≤65,536 字节、owner 存活配额 2,000 行/5 MiB；显式删除保留 owner 墓碑。安装/启用/信任/全信任只在本机策略适配器，远端内容不得 install/enable/trust/fullTrust/execute；卸载默认删安装态、保留同步数据。 |

### 1.5 冻结边界、旧包与发布阻断

- 桌面冻结于 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87`，后续不自动推进或跟进上游；不采用其 mobile/Capacitor/Android 自带运行链。Operit 与 terminal 分别冻结于上表 commit/tree，工作树均 clean。
- 旧移动 ZIP 是固定兼容样本，不得重生成、重导出或替换。验证只回放已落盘字节：standard ZIP SHA-256 `3bcecfdd5ec02b0d3b8ee110f2649b6edc136bc9bb6d41b2a5673061ec932eba`（3,987,747 字节），continuity ZIP SHA-256 `774b1aea192eaec9f991c127aa275689b624b6679724e6ac6dea1316f5a13924`（3,987,749 字节），continuity sidecar `9c6b0fa1c3b93b8677514e6add0f0aecec4521e4ee4289cbe2d1f38a09bcadb6`，expected `030a4621633ec496c9442a4a0d8dea27b083c42a0b6162b7181bfcf402454269`。旧包来源事实按 `145bb3879164c58d1cd5ff39daa94a3daa9fe874` 的历史 blob 读取。
- 本轮实际未产出 APK/AAB：没有 Gradle 根工程、没有新 APK，未安装设备应用，未运行 Kotlin/Compose 或设备业务测试。`config/release-contract.json` 的 `releasePackage.observed=false`；其中历史 debug APK 只是旧链观察事实，不是 S0 产物或可发布候选。
- 发布阻断仍为 29 项：26 个许可组件待补证据 + 3 个 16 KiB 重建任务；0 个未分配 owner，`notS0Failure` 不等于豁免。42 个许可组件中 7 已解析、26 未解析、9 不分发；许可后续门禁为 S9.14，桌面 APK 处置为 S9.19。16 KiB 为 14 项检查中 11 通过、3 失败：两份 `aapt2-arm64-v8a`（owner S6.15）与 `operit_shell_exec`（owner S6.21），后续门禁为 S9.13；禁止直接改字节，必须从固定来源与 NDK 重建。

### 1.6 执行队列与下一执行项

- `config/execution-plan.json`：302 个主单元、10 个阶段出口，串行队列共 312 项；1,774 张卡、3,198 个场景。单元状态为 24 signed、1 in-progress、277 pending；卡状态为 24 signed、1 in-progress、1,749 pending。
- 下一执行项为 S1.01“建 Gradle、工具链与构建入口”：串行序号 26，依赖 S0.G，预计 2.5 工作日，`status=pending`、`actualWorkDays=null`、`actualCompletedDate=null`，实现卡 `S1.01/implementation` 仍为 pending；接受标准要求干净环境构建并安装 Compose APK、按 S0.15 核验版本/SDK/ABI，并保留来源与旧资产检查。它只是下一项计划，本轮未开工，不能写成已完成。
- 核对差异：`config/execution-plan.json` 是 S0.25 冻结快照，仍写 S0.G `pending-signoff` / `signoffStatus=pending-main-signoff`，并把 S0.25 标为 in-progress；这不撤销已存在的 accepted 主审回执。计划文件本轮按用户要求未改，留待主线程在后续计划审查/验证记录中处理。

## 2. 本轮文档动作与验证

- 原 `docs/handoff.md` 仅移动为 `docs/archive/handoff-s0.md`；移动后大小仍为 77,108 字节、487 行，SHA-256 与移动前及 `HEAD:docs/handoff.md` 均为 `f22db4c71a074f3c81391c34a40033601826eae59d92202de55a2797c8063158`，`git cat-file -p HEAD:docs/handoff.md | cmp -s - docs/archive/handoff-s0.md` 退出码 0。
- 新建本文件 `docs/handoff-s1.md`，只承接 S0 基线并标明 S1.01 未开工；它不是 S1 实现验收，也不代表 S1 阶段完成。
- 未修改任何 `docs/rules/rules.txt` 内容；rules 由用户独占维护。未修改计划、配置、tools、package，未提交 Git。
- 本轮没有运行 `npm run verify`、S0.G 采集或其他会写入冻结日志的测试；核对依据为真实 final-lock、主审回执、release-contract、execution-plan、S0.21–S0.24 合同、旧 ZIP 样本哈希及只读证据日志。

## 3. 待主线程追加

- 主线程随后补充本轮计划审查/验证记录；需特别确认冻结执行计划仍显示 S0.G `pending-main-signoff` 与 S0.25 in-progress 的展示差异，且不得因此回退 accepted M0 或不必要地改写已冻结历史。

## 2026-11-16T04:57:36+08:00 — 主线程计划审查与归档验收

- 本条承接并完成上文“待主线程追加”。前面“仅归档、未改计划/工具、未测试/提交”记录的是04:17归档子任务当时状态，不代表本轮主线程最终范围。S0.G/M0的最终状态仍以accepted主审回执为准，冻结队列的签收前展示不回写、不回退。
- 主线程审查结论：原计划已有UseCase/Repository和纵切理念，但大core/feature目录、RuntimeController必经调用链、AppGraph注入范围、工作台/全域apply/工坊复合职责以及缺少依赖/复杂度CI约束，不能充分保证前后端分离与模块化。新计划将前后端明确为本机表现层、纯Kotlin业务层、数据/系统适配，未引入远程服务器或改变原生路线。
- 有效计划改为[原生分层与模块化执行计划](plans/2026-11-16-native-modular-execution-plan.md)，入口为[docs/README.md](README.md)与`config/planning/active-plan.json`。原计划因S0.25整份SHA及S0.09行号绑定保留原路径原字节，不改旧生成器/锁以放宽校验。新计划完整继承原302单元/10出口/1774卡/3198场景，增加277个未实施单元和9出口的分层责任映射；无功能删减、无重编号、无工期重复计数。
- 新架构要求：UI只依赖类型化API、domain/application无Android/Compose/SQL/native、各域api/backend/data/ui边界、公共服务不反向依赖域实现、AppGraph只按进程装配、任务/授权/资源写入唯一权威、跨域公开端口、设置/工具/面板按owner注册；四面板、同步apply、Agent、工坊及逐包迁移有明确内部拆分。
- S1.01安排真实Gradle图/源码依赖检查和复杂度门禁，默认函数60有效行、类300、文件500、圈复杂度10、嵌套4层，并要求针对性例外/负例和独立前后端测试。当前文档工具不能证明这些Android检查已实现；尚无Gradle工程/新APK，S1.01未开工。旧S0平台“无工程”假设在S1通过独立快照回放与当前工程检查分开处理，不能跳过检查或重签旧锁。
- 文档校验工具为`tools/planning/verify-plan.mjs`与`verify-plan.test.mjs`。主审补充出口必须在本阶段末/下一阶段前的检查，并新增缺出口、改归档、未签回执负例。独立测试23/23通过，日志`logs/s1-planning-verifier-tests.log`；真实文档校验277单元/9出口/8冻结引用通过，日志`logs/s1-planning-integrity.log`。当前计划SHA-256为`05d4753c033c24809e1a2767905ac246c8e086f0e65bdf6af59aabcfd5153ad0`，9个文档链接均可解析。
- 主线程完整S0复验25/25+已签S0.G通过：302计划单元、5717关联、57900ms，日志`logs/s0-planning-preservation-verify.log`。独立队列验证与`S0_G_REQUIRE_SIGNED=1`门禁通过，日志`logs/s0-planning-queue-verify.log`、`logs/s0-planning-signed-gate.log`。所有记录使用新文件名，未覆盖冻结成功日志，未运行capture。
- `docs/archive/handoff-s0.md`与原`HEAD:docs/handoff.md`逐字节cmp相等，原487行/77108字节/SHA不变。`rules`、原计划、源锁、schema/合同、执行输入/队列、final-lock/主审回执及旧baseline工具均与本轮开始时一致；桌面/Operit工作树干净，未fetch/推进或采用mobile链。
- 使用三个本轮新代理分别负责归档、只读副审、文档验证工具，均未跨任务单元且已关闭。主线程独立复验后进行本轮本地提交，不推送。下一开发位置仍为S1.01，以本版有效计划与冻结功能合同共同作为输入。

## 2026-11-16T05:46:50+08:00 — S1.01 主线程验收

- 用户授权本轮完成S1.01–S1.04；本条仅签收S1.01。开发前已读rules，文件未修改；四个新专属代理分别准备一个单元，未跨单元，峰值4并发，主线程按依赖逐个复验与提交。
- 建立Gradle8.14.2/AGP8.13.0/Kotlin2.2.21、version catalog、三个convention插件、根架构插件、基础Android CI与最小Compose壳。身份为com.deepstudent.app、min26/compile36/target36、arm64-v8a、0.21.0/2100000；Gradle直接调用冻结版本函数，没有第二套公式。wrapper官方分发/JAR checksum与来源见config/planning/s1-toolchain.json、gradle/NOTICE。
- 主审拒绝了最初不完整的门禁，补齐纯common限制、合法公开API、domain/application方向、qualified引用、包声明误报、真实源码InputFiles、改源码后不得UP-TO-DATE的回归。verifyArchitecture检查真实Gradle配置图及Kotlin PSI结构，不宣称完整类型解析。verifyComplexity包含detekt1.23.8、PSI2.2.21、build-logic Kotlin自检与Checkstyle10.20.1 Java AST；修复4处Java高圈方法，不放宽阈值，无例外。
- 主线程工具测试12个TestKit+4个Java AST正反例全部通过，含Android/JVM convention编译、6类违规/clean夹具和增量失效；日志logs/s1-01-tooling-final-review.log。Node版本/文档测试31/31通过，logs/s1-01-node-tests.log。最终仅S1.01索引候选在临时干净目录无build cache构建，56/56任务执行通过，logs/s1-01-verified-clean-final.log；没有混入已准备S1.02图或未来模块。
- 真实解析58个AAR manifest，minSdk全部≤26，记录logs/s1-01-resolved-aars.json；保留min26且不回写冻结发布合同。实际APK SHA-256 84fe7a7b1a935a63bc4a79d2a98c2ea7579abc9791981c8cee6939438bc33172，debug公开证书018004…42，身份/SDK/ABI/ZIP16KiB及graphics.path ELF LOAD全部≥16384通过，旧APK/Capacitor/密钥资产不打包，logs/s1-01-apk-verified.log。安装/冷启动成功，481ms；iPA2475/API36前台Compose截图logs/s1-01-bootstrap-device.png与logs/s1-01-device-smoke-pass.log。设备页4096，不能声称16KiB运行或正式签名/升级通过。
- 新阶段入口npm run verify在36511a1冻结快照用bubblewrap保持原路径只读回放；npm run verify:s1验证当前工程，设备测试另跑。旧比较器、锁、fixture及绑定日志不改；快照映射/浏览器临时目录问题已修复，25/25+signed S0.G全量通过，logs/s1-01-s0-snapshot-isolated.log。当前536冻结输入和来源HEAD/tree/clean复核通过，logs/s1-01-frozen-inputs-main.log；所有快照临时目录已清理。失败尝试日志保留诊断，误含整机活动状态的两份临时日志已删除。
- S1.01 accepted，准备本地提交，不推送。当前不是S1.G，不关闭26许可/3项16KiB发布阻断；S1.02/03/04仅准备未签收，下一步注册DstuApplication与真实engine/isolated服务并执行设备测试。完整实施说明docs/implementation/s1-01-build.md。

## 2026-11-16T06:04:10+08:00 — S1.02 主线程验收

- S1.01已提交40c407d，本条签收S1.02，未进入S1.03/04集成。rules复读且未修改。专属代理只完成本单元，主线程接Manifest/MainActivity并独立构建、测试。
- DstuApplication每进程持有唯一Main/Engine/Isolated/Auxiliary图；API28+公共进程API，26/27读/proc；isolated公共判断与同user UID回退，未知角色fail-closed。主资源工厂仅Main可接收，lazy/open-once/close-once；当前尚无业务库，未注册工厂时明确拒绝，子图构造无法打开主库。Activity只在装配入口取graphId，不传全图给业务UI。
- 注册非导出:engine及isolatedProcess报告探针，只接受host UID的MSG_REPORT，不暴露通用RPC。Graph日志异常只输出类名、不输出原message/cause/stack；补sentinel隐私测试。主线程最终JVM33/33、两个测试APK构建及架构/复杂度通过，logs/s1-02-lifecycle-harness-build.log。
- 真机iPA2475/API36最终4/4通过，2.262s：主角色、真实engine、isolated UID与零业务资源、MainActivity真实重建。logs/s1-02-device-lifecycle-final.log含旧/新Activity实例187591356→186361690及生命周期阶段；两实例同Application图，子进程独立pid/UID。此前ActivityScenario经app START返回102、Instrumentation.startActivitySync等待，shell普通启动返回0；改为仅instrumentation的uiAutomation shell启动并用Application回调观察/主线程recreate，有20秒分步预算、60秒整体预算和清理，不加生产后台启动权限、不回避重建验收。
- 测试依赖固定升级runner/core1.7.0、ext-junit1.3.0支持API36；未改变min26或运行依赖。Gradle connected入口初次拉UTP依赖TLS握手停滞后终止，SDK adb AndroidJUnitRunner运行同一实际构建并检查OK(4 tests)/退出码；可重放工具tools/s1/instrument.mjs。用户提醒本地代理可用于下载，S1独立cache已用127.0.0.1:7897及Maven Central直连例外。
- 收口S1.01工具后续发现的5个Gradle任务cache意愿注解，CI/current入口改完整build-logic check；主线程validatePlugins+16工具测试+Checkstyle全部通过，logs/s1-02-full-tooling-check.log。不是豁免校验或缓存源码；内容InputFiles与增量负例保持。
- 本轮APK SHA e54ec67c7c17e08eb87fcdce634209ef9a7153edb11f2890c40eb6ce50f98c28，身份/SDK/ABI/debug证书/16KiB静态检查再过，logs/s1-02-apk-final.log。S1.02 accepted，本地提交不推送；下一项S1.03，S1.G仍未签收。

## 2026-11-16T06:20:19+08:00 — S1.03 主线程验收

- S1.02已提交d089198；本条签收S1.03。仅本单元专属代理实现core/common、runtime/resources/engine三个服务api、cards/media/settings/plugins四域api和media/backend/domain；共9个纯Kotlin模块，不建立大core/model，不实现数据库、网络、页面或同步。媒体summary由api移到backend，公开DTO与字段校验留api，后端不反向污染前端合同。
- ID、SHA/桌面Rust trim、注入随机/墙钟/单调钟、int64/JSON安全边界、typed error/Outcome及窄日志/Repository/Task/Engine端口落地；取消不吞，fatal Error不包装，未知异常不泄漏原message/路径/用户内容。单调钟跨signed rollover/大间隔fail-closed、墙钟回拨与epoch溢出均有测试。随机端口不发明产品算法，固定字节只在测试注入。
- Kotlin精确映射冻结S0.21–24：模板24列及unknown/显式null，资产根/hash/大小/附件路径和bigint墓碑，媒体13列/事件互斥/UTC与恢复后退/字幕winner，573设置点scope20/503/50与秘密/执行本机边界，插件14字段+canonical tuple/unknown/记录65536字节/深16/owner2000及5MiB。读取原落盘fixture，不改原SQL/wire/trust或旧ZIP。
- 主审修复JSON builder/Map/List及附件ByteArray可变别名、未知字段Map快照、plugin墓碑不释放行额度及配额整数溢出、非有限JSON数、URL缺host/凭据、错误日志泄密。实际门禁抓到10处圈复杂度，已按转义、timestamp、kind字段和config投影职责拆分，不降阈值，不加例外。URI纯值解析允许，Socket/URL连接/DNS/HTTP仍禁止，新增TestKit证明两者区别。
- 主线程独立--rerun-tasks：84测试全部通过，core23/runtime6/resources10/engine2/cards7/media-api5/media-backend2/settings16/plugins13，53任务全部执行；10模块、56生产Kotlin文件、架构与复杂度零违规/零例外，logs/s1-03-independent-rerun.log。完整build-logic check包含13 TestKit+4 Java正反例，通过logs/s1-03-tooling-uri-regression.log。阶段回执校验7/7正反例，logs/s1-03-progress-verifier-tests.log；它只核对依赖顺序/卡号/证据，不代替业务测试。
- 当前536冻结输入/来源再次通过，rules未改，无fetch/推进桌面。S1.03 accepted，本地提交不推送；S1.04准备代码尚未纳入settings或签收。下一步真实捆绑SQLite根工程/设备探针，不做S1.05迁移runner。

## 2026-11-16T06:28:10+08:00 — S1.04 主线程验收及本轮收口

- S1.03已提交03573cc；本条签收S1.04。本单元专属代理仅实现platform/database，主线程纳入settings并从app装配依赖，driver/connection/statement均只在internal适配层。公共端口只返回能力报告，不向UI/业务暴露SQL。未进入S1.05迁移runner、笔记仓储或网络开发。
- 固定androidx.sqlite:sqlite-bundled:2.6.2，实际Android/JVM均为SQLite3.50.1、source id b77dc5e0…914c95、THREADSAFE=2/ENABLE_FTS5；JSON1无旧编译标志但真实JSON1/JSONB可用。19类探针通过trigram中英/CJK、contentless_delete、JSON、WAL双连接快照/busy250ms、保存点/事务/FK、Long.MIN/MAX/2^53+1、NUL blob/null、重开、坏库/非法SQL诊断及资源关闭；错误和日志只输出固定诊断，secret哨兵负例通过。
- 主审修复错误分类函数圈复杂度及probe耗时在block之前计算的问题；WAL锁持有/测量/映射分职责，finally释放锁，新增capture耗时和WAL≥100ms回归。最终JVM19/19、仪器6/6通过，logs/s1-04-timing-regression.log、logs/s1-04-device-timed-final.log；最终计时审计correlation d5541311-32cb-4955-840a-9119e726e46c的WAL为259ms，logs/s1-04-device-timing-audit.log（同时保留修复前历史段，按correlation区别）。
- 根工程最终11模块/78生产Kotlin文件、架构/复杂度零违规零例外，141任务强制执行通过。标准解析实际60个外部AAR均min≤26；artifactView仅选择外部module，修复project多变体歧义，不跳过任何外部AAR，logs/s1-04-resolved-aars.json。最终app APK SHA e2dd9c91479eecb4e05fc3611448bb1feac3ce6c202add487a4c5ce917e07707，身份0.21.0/2100000、min26/target36/arm64与debug证书保持；graphics.path与sqliteJni全部LOAD16384/ZIP对齐通过，logs/s1-04-native-timed-final.log。设备仍4KiB，未宣称16KiB运行或正式发布签名通过。
- 同一最终APK的S1.02进程/重建回归4/4再过，logs/s1-04-process-final-regression.log；engine/isolated仍零业务初始化，APK含库不等于子进程打开库。当前工程总入口通过logs/s1-04-current-regression.log；固定S0快照25/25+signed S0.G再次通过logs/s1-04-s0-frozen-regression.log，536冻结输入/来源/旧锁及rules未变，不fetch、不采用上游mobile链。
- 本次S1.01–S1.04四单元均accepted，分别本地提交，不推送；S1.G仍未签收，next=S1.05。结束前主线程再跑最终current/progress/冻结检查，将构建目录/APK/缓存中间产物移出并清理，只保留源码/日志/文档；设备保留已安装的debug最小壳。26许可+3项16KiB重建仍为发布阻断。

## 2026-11-16T06:30:13+08:00 — S1.01–S1.04 最终清理核对

- 最后`npm run verify:s1`通过，含38 Node测试、17工具测试、当前debug/release JVM测试、11模块门禁、APK与60 AAR核验；日志logs/s1-04-final-current-accepted.log。当前stage回执accepted=4、next=S1.05、s1GateAccepted=false。
- `logs/s1-04-workspace-cleanup.log`记录删除本轮产生的19个build/.gradle/.kotlin目录与wrapper bootstrap临时目录，源码/fixture/日志/文档保留。最终APK已安装设备，按规则工作区不保留APK/中间构建产物，可用正式Gradle入口重建。清理后536冻结输入与来源再次通过logs/s1-04-after-cleanup-freeze.log，rules/旧计划/锁/SQL/ZIP/成功证据未改。
- 四个本轮专属代理均已关闭；移除两个本轮instrumentation测试包，保留com.deepstudent.app debug应用并冷启动成功559ms，logs/s1-04-device-test-cleanup.log。S1.04最后本地提交由主线程执行，不推送，不进入S1.05。

## 2026-11-16T07:12:00+08:00 - S1.05 主线程验收

- 本轮用户授权S1.05–S1.10，开发前复读rules且不改文件；六个新专属代理各负责一个单元，峰值6并发，遇429后降低同时请求数并按单元恢复，已落盘代码保留。主线程只按原依赖顺序集成、验收与提交；本条只签收S1.05，不提前签收后续单元或S1.G。
- platform/database增加真实bundled迁移runner，构建从冻结config复制135+3 SQL与final-lock为assets，不重生成基线。加载核对锁SHA、138份字节/SHA、SipHash-1-3 refinery与集合SHA；四共享库fresh指纹与桌面完全一致，restart均0迁移。browser历史文件仅参与来源校验，不作为共享库打开。
- 逐迁移SQL与history同事务；重建外键检查、旧版本prefix scratch指纹、治理canonical记录校验、SQL/DDL漂移定位与失败版本均有证据。主审要求补齐事务receiver/SqlRow跨回调线程逃逸拒绝、禁止外部事务控制/可写PRAGMA、WAL/busy配置、64位history版本不截断；新增2^32+version伪造回归。端口不暴露AndroidX connection/statement，data用绑定SqlValue/SqlRow和显式同库transaction，不伪装跨库事务。
- 主线程最终强制重跑15新迁移测试+19驱动回归=34/34；真机9/9（新迁移3+原探针6），四库integrity=ok、FK=0；日志logs/s1-05-main-final-rerun.log、logs/s1-05-main-device-final.log。仅本单元索引候选在干净临时目录禁用build cache，72/72任务执行通过、11模块/88生产Kotlin文件零违规零例外，logs/s1-05-main-clean-index.log；工作区检查额外扫描到未来未提交api，不作为本单元证据计数。
- 536冻结输入、38 Node测试、17 build-logic正反例和S0只读回放25/25+signed S0.G通过，logs/s1-05-10-preflight-freeze.log、s1-05-10-preflight-node.log、s1-05-10-tooling-regression.log、s1-05-10-preflight-s0-replay.log。rules/SQL/锁/桌面HEAD/tree均不变，不fetch，不采用上游mobile链。
- 实施说明docs/implementation/s1-05-migrations.md，末尾待复验描述为代理准备时点；本条给出最终15+19/9结果。最小runner对桌面特殊旧scope恢复仍fail-closed，完整策略在S2.02/03，未宣称正式发布、16KiB真机或阶段出口通过。S1.05 accepted，本地提交不推送；S1.06–10准备代码不进入本提交，下一集成S1.06。

## 2026-11-16T07:38:44+08:00 - S1.06 主线程验收

- 前置S1.05本地提交0f17d57；本条签收S1.06，不进入S1.G。专属代理负责notes api/backend/data及resources backend/data，主线程装配MainStorageResources和设备harness；代理因429间歇中断后同单元恢复，现已关闭。rules及冻结SQL/registry/wire不改。
- 笔记正文、资源ID、目录成员、历史、标签与typed附件关系使用迁移后的真实VFS库；正文资源owner接受相同SqlOperations事务，不另开连接。notes公开端口不持SQLite/Android，app惰性装配主进程存储，Activity不接数据库；engine/isolated仍不打开主库。附件内嵌/外置、SHA/长度、内容去重、软删恢复、引用失效与unknown props保留均有测试。
- 主审纠正过度拒绝普通链接的初稿，来源绑定移植冻结桌面revisions.ts资产提取规则，严格percent/UTF8和加号原义；高级未知编辑格式仍拒绝破坏性写入。Android ICU不支持JVM的(?U)，改用明确ECMAScript空白字符集合，并增加真机回归。此为真实平台缺陷，不能由JVM通过替代。
- 真机外置附件最初失败，固定stage/causeClass诊断定位PUBLISH/AccessDeniedException；设备不允许hardlink发布。主线程改同私有目录进程间FileChannel锁及进程内锁保护的ATOMIC_MOVE，已有目标在锁内校验hash/size，不覆盖损坏对象；无直接写半文件降级。失败与诊断日志保留。平台TOCTOU/断电目录fsync/孤儿回收硬化仍属S2存储单元，不宣称本轮完整覆盖。
- 最终干净索引候选18新JVM（notes1+5+5/resources3+4）及app既有33通过，114/114任务执行，16模块/113候选生产文件零架构/复杂度违规、零例外，logs/s1-06-main-clean-index.log。最终修复构建logs/s1-06-main-atomic-publish-build.log；此前强制178任务结果早于Android修复，不能当最终真机依据。
- 真机notes data2/2、resources data1/1、app真实组合2/2通过，logs/s1-06-main-notes-device-fixed.log、s1-06-main-assets-device-fixed.log、s1-06-main-device-publish-fixed.log；包含1MiB外置附件及typed关系。额外显式写fixture→adb force-stop→新PID读回正文/附件/hash/关系2步成功，logs/s1-06-main-process-write.log、s1-06-main-force-stop.log、s1-06-main-process-read.log。fixture只在隔离cache目录且读回后删除，不触及用户数据。
- 公开日志只固定事件/owner/阶段/耗时/关联ID，不含正文/路径/凭据；应用已接真实日志sink。编译中间缓存损坏后清理对应generated并in-process重建通过，未删除源码/日志。实施说明docs/implementation/s1-06-notes-resources.md为准备阶段记录，最终结论以本条为准。S1.06 accepted，准备本地提交不推送；下一S1.07，业务完成outbox接线随S1.08实现。

## 2026-11-16T07:46:30+08:00 - S1.07 主线程验收

- S1.06已提交64de7cd；主线程将platform/network正式纳入根图和catalog，OkHttp4.12.0/coroutines1.10.2，公共端口不泄漏OkHttp/Okio类型。HTTP生命周期、SSE framing、字节预算和错误日志分离；不含provider/chat业务、不自动重试重定向、不发明业务完成语义。主进程资源owner持有NetworkTransport并close，不由UI观察持有。
- 主线程JVM23/23（HTTP12/SSE6/取消5），真实本机HTTP/socket涵盖等待header、阻塞读、挂起回调取消和20次竞态；取消原样传播、服务端观察断连、响应与活动call释放。UTF8跨chunk/多行data/BOM/CRLF/不完整末帧、非法UTF8、预算与敏感哨兵脱敏均过，logs/s1-07-main-final-build.log。最终同模块APK真机2/2，logs/s1-07-main-device-final.log；不把代理移包前测试当最终证据。
- 仅本单元干净索引候选32/32任务执行，17模块/122生产Kotlin文件，真实Gradle图及PSI/detekt零违规零例外，logs/s1-07-main-clean-index.log。工作区额外未来api不计本单元文件数。最终app装配和60 AAR minSdk≤26通过，logs/s1-07-main-composed-final.log；APK SHA5e34e91ac40185063731ee7ed7597c15b709fd259e53ff8975e74826041aa184，身份SDK/ABI/debug签名/native LOAD16384/ZIP16KiB及旧链排除通过，logs/s1-07-main-apk-evidence.log。设备4KiB，非正式签名/16KiB运行签收。
- 明文许可仅androidTest manifest，生产仅INTERNET、target36默认安全策略；网络错误和日志不含URL/header/body/原cause，未知回调异常固定诊断。干净EOF只代表frame完整，TRUNCATED与传输失败由后续用例处理；无自动游标恢复或provider协议。实施说明中的8文件为代理早期扫描，最终独立网络实现9文件，根候选122文件。
- 专属S1.07代理已关闭，其他代理各交付本单元后均关闭；主线程继续S1.08–10串行集成。本条S1.07 accepted，本地提交不推送，S1.G未签收，冻结桌面/rules不变。

## 2026-11-16T08:01:59+08:00 - S1.08 主线程验收

- 前置S1.07提交ffad8b4；本条只签收S1.08。runtime api/backend/data分别拥有本机合同、纯状态机/lease/supervisor和SQLite投影，不反向导入features，不改原wire TaskStatus。应用scope每任务独立Job，观察flow结束不取消任务；周期续租、取消请求先落账、单终态/游标持久化、幂等键冲突拒绝。
- 主审补事务内expected ownership比较、旧owner已释放时Reconcile token仍拒绝、到期Fence核对实际expiry、generation条件取消、confirmed标记阻止自动续跑；默认条件取消fail-closed。修复原圈复杂度按职责拆分，未降低门禁。runtime本机schema v2含confirmation_required，v1升级保留数据/事件；共享wire未增字段。
- 主线程实现notes同库SqlNoteTaskStore：笔记/资源/历史/捕获与业务fact在同一VFS事务，失败注入fact INSERT回滚全部；同task/generation+request hash重试返回原fact，不重复建笔记。运行时仅由app映射公开BusinessCompletionSource投影/ack，不伪造完成事实。新增__android_notes_task_*及runtime表均登记config/stages/s1-local-governance.json为LocalRuntime、非RowSync、非共享fingerprint，不改冻结138 SQL/registry126/61。
- MainRuntimeResources惰性application-owned装配真实runtime.db和note handler，UI不持全graph/SQL；TaskCompletion独立负责业务事实核对和终态，普通诊断sink异常不改变执行。真实设备note任务重复提交同ID、业务内容/fact先存在再Completed、单完成事件通过，logs/s1-08-main-device-note-task.log；SQLite账本checkpoint/receipt/cursor关闭重开1/1，logs/s1-08-main-device-ledger.log。
- 仅S1.08索引候选禁缓存干净构建213/213任务执行，9纯状态机+9真实SQLite+2 notes原子事实=20新增核心测试，以及notes既有5回归全部通过；19模块141生产文件架构/复杂度零违规零例外，logs/s1-08-main-clean-index-final.log。工作区联合恢复测试14+3也已过但属于未签收S1.10，不计本单元。首次索引漏TaskCompletion被干净编译抓出，补入后重跑通过，失败日志保留。
- S1.08 accepted，本地提交不推送；S1.09/10代码仍未签收。业务写入在取消/失租后不得提交外部副作用，后续能力与长跑矩阵仍需S2硬化；本轮不声称8小时/OEM/全部权限或S1.G通过。rules/冻结桌面不变，所有专属代理已关闭。

## 2026-11-16T08:14:23+08:00 - S1.09 主线程验收

- S1.08已提交b815597；本条签收S1.09。platform/background按冻结Operit职责引用模型重写，未整体复制巨型service；DutyRegistry、通知factory、停止receiver、资格判断、超时checkpoint和服务生命周期分责。只声明dataSync及必要FGS/通知权限、组件不导出、PendingIntent显式immutable并含task/generation。无specialUse、透明overlay或无用途常驻。
- 主线程接真实notes stream import纯Kotlin用例与HTTP/SQLite适配，实际网络传输才授dataSync用途，本地notes.create拒绝冒充。逐chunk部分正文checkpoint持久，完成笔记同库fact，取消写本机独立回执与ack，新增表登记LocalRuntime，不改变冻结共享schema/wire。debug仅loopback明文许可，release无此配置。
- 新增执行owner requestCheckpoint与自身checkpoint写入串行，当前lease/generation校验后持久确认，FGS超时先请求（2秒预算）再条件取消，不接管业务循环。停止动作改原子generation取消，旧通知不取消新一代；测试fake也须实现条件端口，不能依赖不安全默认。null intent只查catalog对账，不凭账本重启不具资格任务；START_NOT_STICKY。
- 主线程11/11职责/timeout顺序/日志测试、runtime既有9+9回归、APK/架构/复杂度通过。仅本单元索引候选禁缓存233/233任务执行，20模块156生产Kotlin文件零违规零例外，logs/s1-09-main-clean-index.log。完整工作区包含未来恢复代码，未计入本单元候选。
- 同一集成APK真机双真实loopback SSE任务：启动FGS→销毁MainActivity→另一任务cursor仍推进→通知停止指定task得到Cancelled→另一task继续并Completed→最后职责/通知/服务均退出；真实owner checkpoint持久确认通过，logs/s1-09-main-fgs-device-final.log。模块通知/Manifest3/3再验，logs/s1-09-main-notification-device.log。不是假handler或仅切后台，最终测试显式finishAndRemoveTask；此前首轮仅Home作为历史诊断保留。
- 设备仅API36/4KiB；未模拟系统6小时真实timeout、未完成OEM/8h常驻、Worker或持久职责，后续S2/S9仍保留。实施文档早期缺checkpoint/条件取消的待办已由本条接线解决，其余后续范围不豁免。S1.09 accepted，本地提交不推送；下一S1.10启动对账，rules和冻结桌面不改。

## 2026-11-16T08:38:03+08:00 - S1.10 主线程验收与本轮收口

- S1.09本地提交5fdd9de；本条签收S1.10，本轮S1.05–S1.10六单元全部完成，不开工S1.11、不签S1.G。六个新专属代理各只负责一个单元、峰值6并发，429后逐单元恢复；均已关闭，最终审查/测试/签收由主线程完成。未推送。
- RecoveryCoordinator按回收旧owner→业务fact/outbox→投影/持久事件→中断/待确认/安全续跑→重读快照发布顺序运行，同实例互斥；来源读取失败不等同无事实，pending异常阻断该来源恢复，不ack失败投影。恢复使用既有TaskLedger/TaskSupervisor，不复制执行器、不反向依赖域。未知外部副作用持久PAUSED+confirmationRequired，部分正文checkpoint保留；确认/暂停/审批不自动重跑。
- 主审修复认领与投影竞态：Claim在事务内比较expected ownership，Fence到期模式核对当前expiry，旧token即使owner已null仍不能投影；租约活跃且无死亡证明不接管。RuntimeProcessLock在独立runtime.owner.lock持有跨进程独占锁，取得后才证明旧main owner不能继续本目录执行；lock只属应用root。App在IO线程开启恢复，完成后才暴露runtime；engine/isolated不打开业务库。ForegroundRuntimePorts异步等待同一恢复屏障，避免冷启动Service读取未初始化runtime；前台资格只接受当前owner正在运行的传输任务。
- 恢复测试14纯Kotlin+3真实SQLite通过，另增加runtime独占锁回归；业务源异常/ack失败/重复启动/取消/旧owner/expired/live lease/待确认重开与异常传播均覆盖。Coroutine调试恢复会复制suppressed Error，测试校验其类型与信息保留而非对象identity。主线程精简复杂分支，门禁无豁免。
- 最终全工程unique JVM为243/243：app34、core23、cards7、media5+2、settings16、plugins13、notes1+6+7、resources10+3+5、engine2、runtime6+23+12、network23、background11、database34。仅统计JVM test与Android debugUnitTest，release重复不累加，logs/s1-10-main-test-counts.log。更正上文S1.06最终18计数：后补空白字符回归及FileDiagnostics回归已在最后构建入库，S1.06自身应为20（notes1+6+5/resources3+5），S1.08再增notes2；该更正不撤销已通过的行为证据。
- 最终干净索引候选216/216任务执行，runtime9状态+14恢复/9账本+3SQLite恢复、app34与APK测试包全部通过；20模块168生产Kotlin文件真实Gradle/PSI/detekt零违规零例外，logs/s1-10-main-clean-index-final.log。全工程test及最终总入口npm run verify:s1通过，含39 Node测试、17 build-logic正反例、60实际AAR minSdk≤26，logs/s1-10-main-final-engineering.log、s1-10-main-final-current.log。
- 同一最终APK设备普通套件8项通过、4项显式宿主重启专用方法按设计跳过，logs/s1-10-main-final-device-suite.log；instrument工具现扣除skip，不把runner OK(12)误算12通过。额外宿主写入真实notes同库fact但ledger仍Running及部分生成checkpoint→force-stop→新PID启动自动对账两步各1/1、无跳过；完成事实补账、正文保留并Interrupted、重复对账事件数不变，logs/s1-10-main-final-recovery-write.log、s1-10-main-final-recovery-force-stop.log、s1-10-main-final-recovery-read.log。强化FGS回归断言一个真实MainActivity存在且isDestroyed后仍推进、通知定向取消/最后service退出1/1，logs/s1-10-main-final-fgs-proof.log。隔离子进程与重建图回归仍过。
- 最终app APK SHA e58898c3315f6b4f3019f3f96fc788a30e9096921fcf42f8934b024522afd146；身份0.21.0/2100000、min26/target36/arm64-v8a保持，debug证书固定，graphics.path/sqliteJni LOAD16384与ZIP16KiB通过，logs/s1-10-main-apk-final.log。设备为API36/4KiB，不宣称API26真机、16KiB运行、正式签名或升级门禁通过；26许可+3项native重建仍阻断发布。
- 开发过程中用户自行更新rules第5条，要求只留最终调试日志并清理子代理中间文档。主线程复读并保留用户字节，不改、不暂存该文件；旧S1验证器误把live rules列为S0产品冻结输入，已修正为535个真实产品基线文件，新增测试防止再次pin rules。SQL/锁/fixture/旧计划/来源仍逐字节严格校验，S0只读快照25/25+signed S0.G再次通过，logs/s1-10-main-s0-final-preserved.log；无fetch/上游推进。
- 按新规则清理本轮重复失败/准备日志与S1.01–S1.10代理实施准备文档，最终事实和限制已收敛到本handoff，plan/README入口同步。上文旧时间点引用的已删除文件可从其单元本地commit追溯，不重新解释为当前文件；config/stages/s1-progress.json所绑定的最终证据保留，S0冻结日志及归档绝不删改。清单见logs/s1-10-main-document-cleanup.log、s1-10-main-document-cleanup-final.log。随后清除构建目录/APK/.gradle/.kotlin及本轮临时harness，卸载仪器包，仅保留源码、最终日志和阶段文档；用户rules修改保持未提交。

### 下一执行位置与命令

- accepted=10、next=S1.11、s1GateAccepted=false。S1.11为设计资源生成器/冻结token对照/core/design，须新建该单元专属代理，先读当前rules、有效计划和本handoff；不继续使用已关闭的S1.05–10代理。
- 构建：`DSTU_PROXY_HOST=127.0.0.1 node tools/s1/gradle.mjs <tasks> --no-daemon`，独立cache为`../build-tools/s1-gradle-home`。总入口`npm run verify:s1`，历史回放`npm run verify`；设备`node tools/s1/instrument.mjs <module> <testPackage> <minimumPassed> [testClass#method]`。日志用`tools/s1/logged.mjs`唯一新文件，不覆盖固定成功记录。Gradle8.14.2/AGP8.13.0/Kotlin2.2.21/Java17及代理127.0.0.1:7897保持。
- 原138 SQL、四库版本/指纹、registry126/61、wire v4/minReader3/v5 inactive、来源commit/tree及M0回执仍以上文冻结基线为准。完整S1阶段设备/视觉/九纵切与S2–S9后续测试不因本轮完成而豁免。

## 2026-11-16T08:41:44+08:00 - 最终清理与基线复核

- Gradle守护进程停止3个，所有本轮exec构建/设备会话已结束。清理28个build/.gradle/.kotlin目录、SQL临时库/APK及本轮S1.05/07/08/09/10临时harness，logs/s1-10-main-workspace-cleanup.log；依赖cache保留在仓库外的s1-gradle-home，未碰冻结S0 cache。设备卸载7个本轮仪器包，保留debug应用并冷启动成功508ms，logs/s1-10-main-device-cleanup.log。
- 清理后535冻结产品输入及来源commit/tree/clean全部通过，logs/s1-10-main-after-cleanup-freeze.log；progress accepted10/nextS1.11及计划277单元/9出口/8冻结引用通过，logs/s1-10-main-accepted-progress.log、s1-10-main-accepted-planning.log。rules的用户修改保留在工作树、不进入开发提交；其CRLF空白警告不由代理改写，其他暂存文件diff检查通过。
- 代理准备文档已清理，保留必要来源摘要：SQLite分句状态机依据public-domain sqlite3_complete/src/complete.c及冻结桌面sql-text.ts；refinery checksum为SipHash-1-3(0,0)，输入UTF8(name)+FF+i32LE(version)+原SQL字节+FF，unsigned64十进制；schema治理canonical按冻结scope和原DDL。资产引用规则依据桌面revisions.ts:21–84。FGS采用冻结Operit AIForegroundService.kt职责模型，源SHA83e216e22b4361b7cfd64c7915a617d655370d51f7894bdc6f7ba9224b61aefb，refreshBackgroundKeepAlive449/stopSelfIfIdle906/promotion936；不复制服务整体。来源的许可/后续发布审查依旧受原S0/S9门禁约束。
- 公开SQL端口仅data使用DatabaseFactory.openShared/openLocal、SqlValue绑定、SqlRow映射、同库transaction；驱动/statement保持internal。笔记API与资源API供S1.12后续UI，runtime注册handler和BusinessCompletionSource由app组合。S1.10恢复依赖公开RecoveryRegistration/Decision/Publisher，不改frozen wire；本轮无新业务页面，最小Compose壳不冒充完整笔记/聊天UI。

## 2026-11-16T09:37:33+08:00 - S1.11 主线程验收

- 用户授权本轮S1.11至S1.16；本条只签收S1.11，S1.G未签收。开工复读rules，当前用户已将并发改为最多3，主线程遵守并同步有效计划，规则字节不改、不暂存。各新代理仅负责一个单元；S1.11代理完成后关闭，未接其他单元。桌面冻结e6b2657与535产品输入保持，S0只读回放25/25+signed S0.G通过，logs/s1-11-16-main-preflight-s0.log。
- core/design提供无状态DstuTheme、20个亮暗/调色板场景、329颜色/场景、195数值/模式、Material3全部48颜色槽与Typography/Shapes/Sizes；Theme不读设置存储。Logo/mark及9个导航图标沿冻结几何，许可来源与随包文字见config/stages/s1-design-source.json；不回写S0 NOTICE或豁免许可阻断。Custom仅冻结静态场景，任意自选色算法与控制器留S5.01。
- 生成器使用已解析冻结输入，verifyDesignTokens调用真实浏览器采集器重算20x812根token并比较完整语义快照；只剔除运行环境engine身份，实际版本照常记录，数值/声明/来源hash不放宽。真实隔离CSS修改负例定位token/文件/差值并失败。主线程修复被accent覆盖的负例，以及--z-debug最大整数被Float舍入问题：数值表保留Double，仅Compose比例消费转换。最终生成器/设备参数8/8测试通过，logs/s1-11-main-generator-final.log。
- 生成源码精确路径纳入架构/复杂度扫描；修复已登记但配置时尚不存在目录被漏掉的问题，任务先生成后扫描，新增干净目录模型回归。build-logic18/18通过，logs/s1-11-main-tooling-generated-roots.log。最终干净索引候选禁缓存248/248任务实际执行，21模块225生产Kotlin文件零违规零例外，app34回归通过，logs/s1-11-main-clean-verified.log。候选工具用bubblewrap原路径映射，冻结来源只读，仅临时debug签名/daemon目录与独立cache可写，结束删除临时目录；不触及adb密钥。
- API36首次createComposeRule启动阻塞，改仅测试包shell宿主+createEmptyComposeRule，生产不增测试入口/权限。最终同源码设备6/6无跳过，颜色/数值逐值、20场景像素及真实点击、模式切换通过，logs/s1-11-main-design-device-precise.log。亮暗board为logs/s1-11-main-board-light.png、s1-11-main-board-dark.png，主线程视觉检查无重叠/空白；字体为用户当前设备系统字体，未改用户设置。
- app启动壳真实消费DstuTheme。APK身份0.21.0/2100000、min26/target36/arm64与debug证书保持，SHA5b2ee33716d9b53330622cd37b7807a4194ff9bf8c9e11081ec320ed955453cc，native LOAD16384/ZIP16KiB静态通过，logs/s1-11-main-final-apk.log；60实际AAR minSdk<=26。许可随包仅在随后干净候选加入，设备行为源码相同；正式签名/API26真机/16KiB运行未验。CI已接固定桌面输入与设计校验，但未宣称远端CI运行通过。
- S1.11 accepted，主线程本地提交，不推送。S1.12/13/14/16准备未签收，下一集成S1.12；本轮结束再按rules清理中间日志/构建产物，只保留最终证据。

## 2026-11-16T09:59:59+08:00 - S1.12 主线程验收

- 前置S1.11本地提交26cd5fb；本条只签收S1.12。专属代理完成notes/ui及core/ui后关闭，不跨单元。主线程装配MainActivity/MainScreen，将真实Notes端口从app root注入Route，UI无数据库/后端实现依赖；核心back合同仅处理返回消费，不增加全域导航管理器。
- 笔记列表、手机编辑栈、平板双栏、创建/编辑/保存/软删/恢复、未保存返回确认、loading/empty/error/cancel/saving/OCC冲突均实现。Route/ViewModel/Screen/局部组件分责，编辑提交携带原updatedAt，冲突保留草稿。写命令一旦派发不在commit与观察回执之间取消，不自动重试不确定创建；日志只固定UI事件，不含标题/正文。
- 主线程fake API JVM8/8通过，真机fake UI最终5/5无跳过，含实际旋转/重建后的dirty草稿与返回栈，logs/s1-12-main-fake-ui-dark-final.log。API36平板默认忽略方向请求，测试Activity显式compat属性后真实重建；未改用户系统旋转设置。主审修复全文断言误用及暗色LocalContentColor依赖外Surface问题，Screen自行提供主题前景，新增无外Surface暗色回归。
- 主线程新增隔离cache真实SQLite UI harness：中文/组合emoji创建保存→显式force-stop→新进程重新打开内容一致→删除恢复成功，两个宿主步骤各1/1，logs/s1-12-main-final-ui-write.log、s1-12-main-final-force-stop.log、s1-12-main-final-ui-read.log。该harness不写用户活动数据，读回后删除隔离目录；普通套件中重启步骤须显式选中，不能把skip计通过。
- 最终仅S1.12暂存候选禁缓存300/300任务执行，23模块239生产Kotlin文件、真实Gradle图/PSI/detekt零违规零例外，63实际AAR minSdk<=26，logs/s1-12-main-clean-final.log。工具支持导出该独立候选APK供设备验收，不将工作区S1.13/14/15/16准备文件混入。APK SHA15628b1c6828e5c3d2477af6c6c030f62c7329cb98205752e6c93ff9d253dc6b，身份/SDK/debug签名/ABI/native16KiB静态检查通过，logs/s1-12-main-apk-final.log。
- 最终phone/tablet亮暗图为logs/s1-12-main-phone-light-final.png、s1-12-main-phone-dark-fixed.png、s1-12-main-tablet-light-final.png、s1-12-main-tablet-dark-fixed.png；与冻结桌面notes图并排证据s1-12-main-desktop-phone-comparison.png、s1-12-main-desktop-tablet-comparison.png，主线程检查无重叠/空白/暗色黑字。设备用户字体保留，不宣称像素同形；当前正文是原文编辑，富文本语义/完整组件族在S5，未保存草稿进程死亡持久化属S1.17。实际MainActivity入口冷启动506ms可达，logs/s1-12-main-entry-smoke.log。
- S1.12 accepted，本地提交不推送，下一S1.13。设备API36/4KiB，不宣称API26真机、16KiB运行或正式发布；rules、冻结桌面/SQL/wire及S0许可/native阻断不变。

## 2026-11-16T10:04:21+08:00 - S1.13 主线程验收

- 前置S1.12提交c75d48f。本条仅S1.13；专属代理交付纯Kotlin文档合同与parser后关闭，S1.14编辑/IME新增包不进入本提交。MarkdownCodec/NoteDocument/BlockNode/InlineNode与UTF16半开SourceRange公开，未知节点与范围只属于原始源快照，不暴露JetBrains AST实现。
- 采用org.jetbrains:markdown-jvm:0.7.3成熟GFM parser，固定JAR SHA893abb31f15b6216f74dc704c0c6bf1e567dcf2b62cca29cf42e4f53153637ac；来源及Apache-2.0 POM记录于config/stages/s1-editor-source.json，发布仍需随包许可文本，不冒充清关。换行仅解析视图规范为LF，偏移映射回原始CRLF/CR文本；UTF8严格解码，不规范化Unicode，非法字节/孤立代理字符显式拒绝。
- serialize直接保留原始源字节；根块替换验证expected原文与完整根范围，重解析后确认旁侧源片段及结构未变，拒绝不透明扩展/跨块结构吸收。方言/未知容器整体不透明保全，不用不完整语法器猜测解释；全方言编辑与完整桌面AST对拍仍属S5，当前只对标准块及部分方言范围对照，不以字节回环宣称完整语义等价。
- 主线程独立暂存候选禁缓存26/26任务实际执行，新增14/14 JVM测试通过（api2、backend12），9冻结输入+150桌面语料原字节往返、范围嵌套/Unicode/CRLF、stale/多块/未知扩展拒绝、邻块结构不变覆盖。25模块249生产文件Gradle图/PSI/detekt零违规零例外，logs/s1-13-main-clean-parser.log。此单元无页面/平台适配，纯后端端口不启动Activity/Compose，无虚构设备验收。
- S1.13 accepted，本地提交不推送；下一S1.14，S1.G未签收。冻结来源/SQL/wire及rules用户改动不变。

## 2026-11-16T11:18:01+08:00 - S1.14 主线程验收

- 前置S1.13提交7932af3。本条签收输入适配与撤销单元，不签完整输入法/OEM矩阵。专属实现代理及后续同单元诊断代理均已关闭，未跨单元。EditorSession/Selection/TextEdit/EditCommand纯合同，backend拥有字素边界/合成事务/正逆操作/历史，editor/ui只持注入的session；app把其接入NotesEditorSlot，不使用反射或UI到backend运行时暗依赖。
- 固定ICU4J76.1以保持JVM/Android相同Unicode字素规则，UTF16原始偏移不拆组合emoji/组合附标/CRLF；输入快照按原文本替换，不经Markdown序列化改写。合成提交作为一个undo事务，分支编辑清redo，跨块选区方向保留，stale/非法文本/越界拒绝。Notes editorKey在输入与保存后稳定，明确打开/冲突重载生成新key，避免每次输入重建或远端重载继承旧撤销栈。
- 主线程修复跨模块smart cast编译错误，拆分raw差异范围/字素扩展/快捷键映射使真实detekt通过，不放宽阈值。新增7纯输入JVM（含固定种子500次编辑逐步undo/redo）+1 notes会话身份回归，连原editor14/notes8全部通过。仅S1.14暂存候选禁缓存329/329任务实际执行，26模块257生产Kotlin文件零架构/复杂度违规零例外，63AAR minSdk<=26，logs/s1-14-main-clean-final.log。
- 最终候选真机InputConnection3/3无跳过：setComposingText(n/ni)→中文commit、选区、跨块含CRLF/组合emoji粘贴、键盘undo合成事务/redo；fake session UI1/1真实交互通过，logs/s1-14-main-input-final.log、s1-14-main-fake-ui-final.log。APK SHA5b2d54243e20c66149e017b6cb5659cb599a93c75f41fa95f1eafb13482d321e，身份/SDK/ABI/debug签名/16KiB静态检查通过，logs/s1-14-main-apk-final.log。
- 专用InputMethodService和搜狗候选测试尝试未通过：instrumentation会终止同包服务，改时序后vivo StylusInputMethod又强制接管并改变所选IME。主线程未将其计通过，也未以假键盘替代；失败脚手架及debug IME服务已从最终候选删除，release无新增IME能力。系统中文候选/多输入法/手写/OEM实际矩阵留S5.11，S1.14证据范围是已签原要求中的IME合成区适配与真实Android输入合同，不宣称该OEM候选UI通过。测试期间临时设置/默认输入法/子类型/启用列表/无障碍服务与手写/硬键盘值已恢复核对，logs/s1-14-main-settings-restored.log；修复adb shell分号转义后输入法历史也恢复。误截系统桌面和中间失败材料本轮收口清理，不保留用户界面资料。
- ICU JAR14,621,879字节，SHA732cdf18121b1642899da1f5e37e52cc7f48e3ec07fa737105d4603976781b33，来源/POM Unicode-3.0见config/stages/s1-editor-input-source.json；当前debug APK约32MiB，固定跨平台算法有体积成本。许可全文随包仍为发布前要求，不更改冻结26许可阻断。
- S1.14 accepted，本地提交不推送，下一S1.15；不签S1.G/API26真机/16KiB运行/完整OEM兼容。rules及冻结产品来源不改。

## 2026-11-16T11:44:16+08:00 - S1.15 主线程验收

- 前置S1.14本地提交0e2a5b6。本条签收内容/浏览器最小宿主；实现与app装配分别由本单元专属新代理完成并关闭，未跨单元。content/browser各自api/backend/data/ui，纯资源/消息/导航规则与WebView owner/UI分开，app以AndroidView内容slot装配；官方笔记仍原生Compose，不用WebView承载业务页。
- 内容随机origin/nonce，主frame/schema/字节/次数预算白名单消息；资源显式授权集合、路径规范/穿越/未知资源拒绝，CSP禁止网络连接/外frame/表单/对象，WebMessageListener不暴露通用JS接口。HTML自定义CSS/脚本保留，消息仅answer/openLink/replay，不授原生业务执行。主题CSS变量/night_mode注入，KaTeX/Mermaid使用冻结桌面成熟源码资产。浏览器独立owner无内容消息桥，生产HTTPS、测试仅loopback HTTP，登录cookie留浏览器，关闭释放WebView但不删用户登录态。
- 124资产校验复制含60字体、脚本/CSS及内嵌组件许可，冻结package-lock/SRI与源码字节、59个Mermaid内嵌组件集均校验；tools/s1/content-fetch.mjs只获取固定包，不推进桌面。主线程Node资产测试1/1通过，logs/s1-15-main-asset-verification.log，来源config/stages/s1-content-sources.json；CI/current入口纳入校验，未宣称远端CI已执行。
- 主审修复主文档/资源分派复杂度、宿主debug网络策略冲突、浏览器历史提交回调后UI状态同步、内容切换AndroidView沿用已销毁owner的问题。内容profile受WebKit“进程已加载不能删除”限制，改一个固定内容专用profile有界复用，不为每张卡生成永久目录；cookie禁用/随机origin/DOM存储关闭/网络禁用，冷启动清理未加载旧profile，close即时撤销资源/消息并销毁WebView。重复5次profile数量保持1设备断言通过，不宣称每次close立即删除磁盘profile。
- 纯后端5/5通过，真实内容设备3/3（公式/图表、卡面脚本/消息伪造/资源授权/主题、重复打开关闭与资源撤销）、浏览器模拟登录→下页→历史返回→关闭重开cookie读回1/1，fake UI各1/1，logs/s1-15-main-content-bounded-proof.log、s1-15-main-browser-history-proof.log、s1-15-main-content-fake-ui.log、s1-15-main-browser-ui-device.log。脚本无手势导航受Chromium历史干预，验收下页通过公开navigate命令，不人为改变WebView历史算法。
- app默认笔记、内容/浏览器最小入口，隐藏笔记保留草稿但disabled BackHandler，清焦点/隐藏键盘。主线程真实组合测试1/1：笔记dirty→公式→图表→HTML翻面/回答→暗色→浏览器关闭返回原草稿，logs/s1-15-main-final-entries-visual.log。等待WebView visual-state后再截图，避免theme DOM已变但旧帧仍亮；logs/s1-15-main-formula.png、s1-15-main-diagram.png、s1-15-main-card-dark-painted.png已主审查看，无重叠/空白，用户系统字体保留。
- 仅本单元干净暂存候选禁缓存498/498任务实际执行，34模块277生产Kotlin文件零架构/复杂度违规零例外，64AAR minSdk<=26，logs/s1-15-main-clean-final.log；随后仅测试截图等待更新，独立候选复编并设备再过，logs/s1-15-main-final-visual-build.log。APK SHA5211e90d8a7e3fd79cf8086fda92dd4fa171b27ec2ea136b23296f85308b9661，身份/ABI/debug签名/native16KiB静态通过，logs/s1-15-main-apk-final.log。S1.15 accepted，本地提交不推送；下一S1.16，不开S1.17、不签S1.G。

## 2026-11-16T11:56:14+08:00 - S1.16 主线程验收与本轮回归

- 前置S1.15提交ddccd26，本条签收S1.16，用户授权S1.11至S1.16六单元均已完成；不开S1.17、不签S1.G。源码构建与纯服务合同各由本单元新代理实现，均已关闭；主线程独立审查/编译/设备复验，不把代理自报结果当签收。
- PDFium固定73a041a5c1aaffa09ebb64f1981ff501cb91a41c/tree c66f95aa1d5f92bf5d0b79535823e42bfd6496af，对应冻结桌面@embedpdf/pdfium2.15.1源子模块。depot/DEPS及完整嵌套来源、GN/Ninja/编译器/NDK/样本哈希锁在config/stages/s1-pdf-source.json，构建拒绝源码别名、隐藏tracked flags、修改或未登记checkout；不使用预编译PDF引擎。主线程974目标编译命令全部arm64/API26，7个预期JNI导出、ELF Android note26、LOAD>=16384，库SHA90b66a00f5ee34d6444470c6685dac1f45dcea519fb1fa8b966987d7d8e2b3ae，logs/s1-16-main-source-build.log。实际NDK29.0.13846066-beta3如实登记；V8/XFA/Skia关闭。
- JNI复制调用方可seek常规PFD，native owner统一串行化文档/页/text/bitmap操作，RAII释放并限制1GiB文档/8句柄/10万字形/原始16M像素；复制fd以pread读取不改调用方偏移。密码UTF8临时字节两侧擦除，调用方String不可擦除不伪称已清。纯services/engine/api/pdf提供PdfSession及页/字形/坐标/ARGB快照，UI无需PFD/Bitmap/native；adapter在4M像素预算内防御性拷贝、typed错误无原cause、关闭幂等。同步方法须调用方派发IO，后续面板通过纯合同接入，不新增虚构PDF页面。
- 主线程服务合同3新JVM+原2回归通过；最终同候选设备18/18（JNI10+适配8）无跳过，中文字符/box与冻结WASM oracle对拍0.001pt、旋转/扫描/密码/坏页/损坏文件、同ARGB像素、PFD借用、重复close/失败/句柄上限均验，logs/s1-16-main-pdf-final-device.log。native probe50循环RSS增量476KiB/fd0，失败60次fd0，logs/s1-16-main-native-probe.log；JNI测50循环native heap增量4688字节/fd0、失败60fd0，logs/s1-16-main-native-budget-device.log。桌面oracle日志s1-16-main-desktop-oracle.log；Poppler源样本视觉检查只辅助，不能代替PDFium中文文本/坐标oracle。
- 仅本单元干净暂存候选禁缓存434/434任务实际执行，35模块280生产Kotlin文件、Gradle/PSI/detekt零违规零例外，64实际AAR minSdk<=26，logs/s1-16-main-clean-final.log。最终含PDF APK SHAe84c967fff89305a34663ff46620185451b69ccd2b5e9da0f6bdd928abd99815，身份0.21.0/2100000、min26/target36/arm64/debug证书不变，三native库LOAD/ZIP16KiB静态通过，logs/s1-16-main-apk-final.log；设备API36/4KiB，不签API26真机或16KiB运行/正式签名/升级门禁。
- 最终npm run verify:s1通过：281/281唯一JVM（release重复不累加）、48/48 Node、18/18 build-logic、全模块构建/架构/实际AAR/APK检查通过，logs/s1-11-16-main-final-current.log、s1-11-16-main-test-counts.log。app普通套件12通过+6显式重启专用skip，logs/s1-11-16-main-final-app-device.log，skip未计通过；本轮真实notes force-stop步骤此前已各1通过。完整S0只读回放25/25+signed S0.G通过，logs/s1-11-16-main-final-s0.log，535冻结输入/四库指纹/SQL138/registry126/61/wire4/minReader3/v5 inactive不变。
- 六单元开发中最高3代理并发，每代理只处理其单元，全部关闭；rules用户改动不修改不暂存，桌面不fetch/推进、无mobile链。26许可补证+3旧native重建发布阻断仍有效，新依赖许可证据也不自动清关。随后清理中间日志/截图、代理临时编译、APK/build/.gradle/.kotlin与PDF native输出，保留固定源码来源和最终日志/阶段交接，清理回执另追加；本地提交不推送。

## 2026-11-16T12:06:44+08:00 - 最终清理与交接位置

- 内容入口设备harness收口改为隔离cache存储，不再向活动库写验收笔记；新测试包复编与真实入口再验1/1通过，logs/s1-11-16-main-final-test-isolation-build.log、s1-11-16-main-final-isolated-entry-device.log。此前测试创建的软删墓碑保留，不破坏活动数据/捕获记录。
- 清理100个未绑定中间日志/截图（含误截系统桌面）、43个build/.gradle/.kotlin目录、APK及PDF native out-android编译输出；来源checkout与仓库外依赖cache保留。logs/s1-11-16-main-log-cleanup.log、s1-11-16-main-workspace-cleanup.log记录精确范围，未删S0冻结日志或阶段回执引用。无新增代理中间文档，当前交接仍仅handoff-s1，历史S0归档不改。
- 卸载9个仪器包，只保留com.deepstudent.app debug；清理后冷启动530ms，首帧为加载态，随后主线程确认进入笔记/内容/浏览器三入口及笔记双栏，最终就绪图logs/s1-11-16-main-final-ready.png，启动回执s1-11-16-main-final-device-launch.log。Gradle/Kotlin daemon与adb instrumentation进程全部结束，共享adb server保留。
- 清理后535冻结产品输入再次通过，logs/s1-11-16-main-cleanup-freeze.log；accepted16/nextS1.17/s1GateAccepted=false及计划有序覆盖再验通过。rules用户修改不暂存/提交；其他本轮源码/最终证据/计划与交接本地提交，不推送。
- 下一单元S1.17必须另建专属代理，范围仍为PDF/笔记/聊天/浏览器四面板、草稿、CloseGuard和快照；本轮不实施。重建用DSTU_PROXY_HOST=127.0.0.1 node tools/s1/gradle.mjs；PDF固定源码在../build-tools/s1-pdf-work，native输出已删会重新编译，来源缺失时按tools/s1/pdf-fetch.mjs取得固定版本，不推进桌面。总入口npm run verify:s1，历史回放npm run verify，设备测试按单元工具；API26/16KiB真实运行、OEM输入/后台矩阵、许可与正式签名发布要求继续保留。

## 2026-11-16T22:38:21+08:00 - 用户要求整体回退到S1.16基线

- 用户明确要求将提交、工作区、设备测试包及本轮相关日志与截图回退到`17890bb`。主线程执行本地`git reset --hard 17890bb`，移除其后的S1.17、S1.18、S1.19、S1.20四个已签收提交以及S1.21–S1.25全部未提交实现，不推送、不重写远端。回退前HEAD `0d8eafb`完整保存在本地分支`backup/pre-17890bb-reset-20261116`，仅用于核对；当前主线HEAD为`17890bba7f724808fa8e088dca78b618794d3da7`。阶段回执恢复为accepted16、next=S1.17、s1GateAccepted=false。
- 用户维护的`docs/rules/rules.txt`未随回退丢弃；恢复后SHA256仍为`994889fccd12c00edcd87dfb3da9de6ac77194b5d4481105b22d9aa15c32634a`，该文件保持工作树修改，未暂存/未提交。`git clean -fdx`移除本轮未跟踪源码、临时来源/工具、构建目录、诊断日志和未来截图；`17890bb`自带的442份历史证据保留，S1.21–S1.25事故日志按本次用户回退要求删除，不能再用其作为当前证据。
- 从回退后的源码重建`app-debug.apk`及测试APK成功，构建日志`s1-reset-17890bb-build.log`；应用APK SHA256为`e84c967fff89305a34663ff46620185451b69ccd2b5e9da0f6bdd928abd99815`，与S1.16签收记录完全一致，身份0.21.0/2100000/min26/target36/arm64-v8a/debug证书及16KiB静态对齐复验通过，`s1-reset-17890bb-apk-evidence.log`。测试APK SHA256为`c2d405957813f112003b829b5bb3d0ae6d7bb9c4e4d730d85e7e8deef920946e`。
- 设备iPA2475/API36已卸载本轮新增的`com.deepstudent.integrations.operit.shizuku.test`、`com.deepstudent.engines.compression.test`、`com.deepstudent.platform.security.test`、`com.deepstudent.features.sync.ui.test`，并重装基线`com.deepstudent.app`与`com.deepstudent.app.test`；最终包列表仅这两项。卸载测试包时有短暂`DELETE_FAILED_INTERNAL_ERROR`，随后包列表确认已消失并成功重装；本条目不以该瞬时错误宣称卸载全程干净。
- 最终基线应用冷启动`Status: ok`、`COLD`、`TotalTime: 546ms`，笔记/内容/浏览器三入口及笔记双栏可见、无文字遮挡；主线程查看回退后截图`/tmp/dstu-17890bb-final.png`，该临时图不在仓库保留。
- 同一回退源码执行`connectedDebugAndroidTest`，18项中12通过、6个显式重启专用方法skip、1项失败：`EditorInputConnectionTest.undoWhileComposingCreatesOneReversibleTransaction`在`EditorInputConnectionTest.kt:111`断言合成后的`session.state.composition`非空失败；其余两项编辑输入及内容/图/运行时普通套件通过。日志`s1-reset-17890bb-device-suite.log`，XML报告为构建中间产物。该失败与S1.14当时记录的InputConnection3/3通过不一致，当前未修改基线、未把skip计通过，也未用此结果推翻历史签收；它是对回退基线的现状复验限制。
- 当前四个`s1-reset-17890bb-*.log`为本轮回退回执，工作区源码无改动；396个build/.gradle/.kotlin目录及APK中间产物已清理，`s1-reset-17890bb-cleanup.log`记录清理动作。Gradle/Kotlin daemon与instrumentation均已结束，临时回退截图及rules备份已移出/tmp，adb server保留。后续新开发从S1.17重新开始；不再沿用已删除的S1.21–S1.25实现或事故上下文。

## 2026-11-17T00:43:35+08:00 - S1.17 重新开发与主线程验收

- 承接用户回退后的17890bb，从源码重新实现S1.17，不复用回退分支；本轮授权S1.17–S1.20，本条仅签收S1.17。专属代理实现workspace api/backend/data后关闭，主线程审查并完成故障保护、JVM测试、独立UI与chat/PDF/notes/browser装配。其他专属代理仅准备各自单元，最多3并发；rules用户修改不暂存、不提交。
- WorkspaceSession、PanelHostRegistry、CloseGuard、LayoutSnapshotCodec与opaque DraftStore职责分开；资源身份规范JSON避免分隔符碰撞。同资源聚焦不移动；phone stack/tablet四格同组稳定组件，隐藏浏览器/笔记不接返回；每面板独立ViewModelStore。app注册四域与原有content，不在workspace实现领域逻辑。
- 原子临时文件/fsync/rename保存布局与草稿，损坏读取fail-closed。保存前写入未确认标记，已派发提交不在取消窗口丢回执；新输入版本受保护，关闭保存后再次检查dirty，丢弃失败保留草稿，未确认提交明确核对后才能再次保存。Notes原始OCC修订号、Unicode/CRLF草稿保全，关闭前从同一编辑VM串行刷新，不重复领域写入。布局与草稿是独立原子文件，不宣称跨文件事务或最终S5全部协议已完成。
- 最小chat调用已签网络SSE与应用级runtime任务，UI观察不拥有执行；部分文本及终态保留在本机runtime checkpoint，非完整S3共享chat仓储/模型配置。生产HTTPS、无裸凭据/查询参数，loopback只测试注入；PDF通过正式纯引擎端口+PDFium，SAF引用恢复；browser独立owner保留Cookie，不使用WebView承载官方业务。
- 主线程workspace16 JVM（api4/backend9/data3）通过，含保存并发输入/取消/不确定提交跨重启/损坏草稿/丢弃与写盘失败；chat6、notes草稿codec2及notes恢复VM回归在全工程测试通过。仅S1.17索引候选禁缓存1769/1769任务实际执行，46模块325生产文件、架构/复杂度零违规零例外、64AAR minSdk<=26，logs/s1-17-main-clean-candidate.log。后端故障单独复验logs/s1-17-main-backend-final-tests.log，48/48工具通过logs/s1-17-main-tool-tests.log。
- 同一干净候选真机7/7（chat真实SSE/SQLite重新开库、双任务定向取消、fake手机/平板2、原InputConnection3）+显式四面板写1/force-stop/读1全部通过，无skip计通过；logs/s1-17-main-final-device-suite.log、final-restart-write/final-force-stop/final-restart-read。真实PDF页面、笔记dirty关闭取消、chat中文emoji完成、browser loopback DOM、跨尺寸及重启后保存关闭均验证。phone-light/tablet-dark图已主审查看无空白/文字重叠；保留用户设备字体，不宣称像素同形。回退曾失败的合成撤销本轮两次3/3通过，未改内核或断言，历史失败记录仍保留。
- APK SHA256 42d87cc466abc2bbd47d09377e47784f0b8c254fca36ebd6ba01161c3dfd80ab，0.21.0/2100000/min26/target36/arm64/debug证书及三native16KiB静态检查通过，logs/s1-17-main-final-apk.log。PDF固定974目标源码重编哈希仍90b66a00…3ae。535冻结输入与S0只读25/25+signed S0.G通过，logs/s1-17-20-main-s0-readonly.log；不推进桌面，不改变SQL/wire/旧包及26许可/3旧native发布阻断。设备仍API36/4KiB，不签API26真机、16KiB运行或正式发布。
- S1.17 accepted，本地提交不推送；下一S1.18，S1.G未签收。构建中间产物待本轮四单元全部收口统一清理，保留当前源码与最终证据。

## 2026-11-17T01:17:46+08:00 - S1.18 主线程验收

- 前置S1.17提交5545508。本单元专属代理实现后交回尚未稳定的测试接口，主线程接管审查、修补及独立验收，不以代理自报完成签收；该代理已关闭，没有跨单元。纯services/engine/api/fsrs数值合同、engines/fsrs JNI、review api/backend调度/字段/训练各自独立，无页面原因是本单元验证算法与适配，不凭空加产品页面。
- 固定fsrs crate6.6.2与jni0.21.1，通过真实Rust compute_parameters/evaluate，不手写优化器或合成loss。Cargo.lock固定90个registry包；fsrs-sources-check.py逐个校验.crate checksum并将5007份解包源文件与归档逐字节对比，拒绝源缓存篡改。NDK arm64/API26、9 JNI导出及LOAD>=16384通过，取消token由opaque registry管理，先join观察线程再销毁，输入offset/rating越界拒绝；原始桥与工具哈希见s1-fsrs-source.json。许可文本随assets保留，不代表所有Rust传递许可已清关。
- 闪卡与题库共用同一FSRS数值引擎和调度规则，SM-2旧列惰性转换、分钟/逻辑日、确定fuzz、字段映射与训练分离。主线程将过长DTO按调度/计数/策略/时间分组，不改冻结表列；参数提交CAS且优化返回后再次检查取消。取消不写参数，并发外部参数变化返回CONFLICT。
- 冻结桌面oracle6调度+3题库×四档、128训练items重算通过，logs/s1-18-main-oracle-verified.log。调度与训练集结构严格比较，21参数重算完全一致；current RMSE出现约1e-8尾差，oracle明示1e-6数值限，不重生成expected；设备内存/interval数值1e-5、训练参数1e-4、loss1e-5容差。固定训练前loss0.4994423，优化后0.4696471，真实参数及RMSE均对拍。fixture hash登记manifest，仍以冻结桌面源为oracle。
- 主线程20 JVM通过：调度/题库/字段/训练集、fuzz语料、取消/无提升零写/CAS。仅本单元索引候选禁缓存542/542任务实际执行，49模块342生产Kotlin文件、架构/复杂度零违规零例外、64AAR minSdk<=26，logs/s1-18-main-final-candidate.log。设备最终5/5无skip：闪卡四档与分钟/日、旧题库SM2、固定训练参数/loss、真实大训练取消、取消零写原参数。首次取消样本先于30ms完成未被冒充通过，扩大有界样本并补返回前取消检查后复验通过，logs/s1-18-main-final-fsrs-device.log。
- 最终APK SHA a1abe54d3dc8d21f7ed57cc73d4a460f07822698cf3fbeff73d9d2ed3fccafb6，身份/SDK/ABI/debug证书不变，包含新libdstu_fsrs.so共4库16KiB静态检查通过，logs/s1-18-main-final-apk.log。测试装配在app，不让engine反向依赖feature。旧S0合同/桌面冻结不变，仍不签API26真机/16KiB运行或发布；S1.18 accepted，本地提交不推送，下一S1.19，S1.G未签收。

## 2026-11-17T01:32:00+08:00 - S1.19 主线程验收

- 前置S1.18提交64cfad3。专属实现与同单元runtime装配代理均已关闭，主线程独立修复Gradle/复杂度、JNI字符串与实际设备端口合同并验收。本单元无页面，是最小受限脚本调用与落账能力，不提前实现完整S3或S6 isolated执行服务。
- 使用来源锁固定QuickJS e585640ffa2cf88ce7c2d587583c6fe15928ec2f/tree5b914b9a114100ef1935d689c7009a40794b26f0，版本2026-06-04；源码构建arm64/API26、5 JNI导出、16KiB LOAD并随包MIT文本。source工具校验commit/tree/关键字节，未推进桌面/Operit。PTC最小合同采用JavaScript call(tool,args)/最后表达式/envelope，不宣称与冻结桌面Starlark语法等价；完整方言兼容仍需后续引擎路线明确，不把本单元窄执行合同当跨语言等价。
- pure services/script api/backend分离引擎预算、白名单逐调用授权、trace和终态事实；data在独立本机script.db同事务保存结果/回执，唯一runtime监督器投影BusinessCompletion并启动恢复。两注册工具仅echo/add无外部副作用，不提供任意系统能力；未知工具回error且不耗工具预算，递归ptc/越权为不可捕获拒绝。运行时授权、时间/工具/内存/栈/VM轮询/源码/结果预算均执行，工具线程池最多4，超时Future取消不伪称可强杀不合作外部代码。S1未开放外部副作用工具、未建立第二权限权威。
- 主审发现JNI Utf8ToJava把ASCII误当非法编码，首次7设备全失败；修复ASCII/截断/非法标量后，发现引擎测试工具名未作真实broker规范化，修正测试端口而非放宽授权。最终7/7真实JNI设备通过，中文emoji、版本、两工具、失败/unknown/不可捕获策略、wall/memory/stack/result/source/cancel均验，logs/s1-19-main-final-engine-device.log；独立原生宿主含toJSON/stringify超时通过logs/s1-19-main-native-host-probe.log。
- 真实runtime设备5/5通过：成功两工具trace/result/fact和重开库一致、policy与tool预算单终态、取消保留事实且另一任务完成、已提交事实恢复/不可续任务interrupted/重复恢复无重复事件，logs/s1-19-main-final-runtime-device.log。JVM17/17（api3/backend11/data3）通过。仅本单元索引禁缓存625/625实际执行、53模块356生产Kotlin文件、架构/复杂度零违规零例外、64AAR minSdk<=26，logs/s1-19-main-clean-candidate.log。
- APK SHA b709231f31f0293cdbb080568a0d04c5cde3ce903bd457b491b5cd001e5696fd，身份0.21.0/2100000/min26/target36/arm64/debug不变，新QuickJS库加入后5native静态16KiB通过，logs/s1-19-main-final-apk.log。当前内存统计明确为currentMemoryBytes，不冒充峰值。设备API36/4KiB、S0冻结及许可/native发布阻断不变；S1.19 accepted，本地提交不推送，下一S1.20，S1.G未签收。

## 2026-11-17T02:09:55+08:00 - S1.20 主线程验收与四单元回归

- 前置S1.19提交22c122d。本条签收S1.20，用户本轮授权S1.17–S1.20均已完成，不进入S1.21、不签S1.G。实现、同单元静态修补与app装配均用新专属代理，主线程独立审查、修复、构建/设备验收；所有代理已关闭，最多3并发且未跨单元。
- 固定Operit terminal e4646680663789002b43bbfffa6e69e65c35e614/treebb5420c2fd21040878c25240607996aed6afbd77；最小7份原字节PTY/ANSI来源留third_party并逐字节核对上游。编译派生源码固定在engines/terminal/src/operit/kotlin，只去掉10条可能含用户输出的Log；每次构建核对派生字节和精确5文件集合。PTY C原字节编译，经本机owner补丁处理JNI失败/fd/child/reap，NDK29.0.13846066-beta3、arm64/API26、9 JNI导出、LOAD>=16384，libpty SHA2a726d93…320。LGPL/GPL文本及原/补丁源码随notices保留，许可义务留S9.14正式审查，不自称清关。
- API/backend/data/ui与engine分离，ANSI解释复用Operit scanner/emulator，transport只分帧；UTF8/代理对/宽字形按固定ICU规则适配，背景先绘制避免擦掉宽字符。会话registry/I/O pump/screen/进程owner分工，UI不持fd/JNI/kill。Application主进程唯一懒owner，工作台PanelHost仅装配typed ports；关闭面板仅停止观察，显式关闭会话才终止/回收；/system/bin/sh APP沙箱，不提前引入PRoot/rootfs/安装。
- 主审新增ID冲突负例，发现并修复双重process.close以及会话编号从0开始的问题；JVM13/13（api2/backend5/ANSI6）通过。58真实模块架构零违规，复杂度383自有/适配生产文件零违规，5个精确vendor派生路径例外（PtyIo/AnsiColorUtils/Scanner/Sequence/Emulator）有来源、实测值、owner、测试及S6.02/03复审，不豁免目录或自有代码。TestKit因新例外文件未复制曾失败，夹具现复制真实例外来源而不放宽“路径必须存在”，18/18工具复验通过。
- 首次无前台Activity的instrumentation被OEM冻结，所有线程do_freezer_trap，未计通过；测试专属前台宿主后稳定，不改用户电池/OEM/系统设置。首次全fd计数包含Activity renderer/JIT初始加载而失败，增加测试宿主与I/O预热，并每轮精确检查/dev/pts fd回0后，最终before172/after172/pty0→0通过。真实双会话stty80×24/70×20、resize91×31、中文emoji、Ctrl+C后继续、退出37/9均验证；logs/s1-20-main-final-pty-device.log。API26真机/完整后台OEM矩阵仍未通过，不以测试前台宿主冒充后台保活。
- 同一干净候选设备7/7：真实PTY2、fake UI3+宽字形canvas像素1、隔离cache入口/关闭面板不杀session/Activity重建同session/exit0/显式close1。最终logs/s1-20-main-final-ui-device.log、final-entry-device.log；主线程查看terminal-entry.png，中文emoji、光标/输入控件无重叠空白，用户系统字体保留。入口harness不清空活动会话或写活动库。
- 仅本单元索引候选禁缓存722/722任务实际执行，58模块、64实际AAR minSdk<=26，logs/s1-20-main-clean-candidate.log。完整npm run verify:s1通过：356唯一JVM、48 Node、18 build-logic、设计20×812 token、全模块build/架构/复杂度/APK通过，logs/s1-20-main-current-final-pass.log、test-counts.log；最终候选再跑本轮核心17/17设备无skip，logs/s1-17-20-main-final-business-device.log。535冻结输入+S0只读25/25/signed S0.G再过，logs/s1-17-20-main-final-s0.log。
- 最终APK SHA7dd9898215dc53152f590ba8f712a7c16f68c330389305404e73a38fea22abc4，com.deepstudent.app、0.21.0/2100000、min26/target36/arm64/debug证书不变，6库LOAD/ZIP16KiB静态通过，logs/s1-20-main-final-apk.log。设备仍iPA2475/API36/4KiB；API26真实运行、16KiB运行、OEM后台、正式签名/升级/26许可及3旧native发布阻断仍保留。当前accepted20/nextS1.21/s1GateAccepted=false；后续清理与最终提交回执另追加，不推送。

## 2026-11-17T02:18:34+08:00 - S1.17–S1.20 最终清理与交接

- 最终候选再执行四面板写入1/force-stop/读回并保存关闭1均通过，logs/s1-17-20-main-final-workspace-write.log、final-workspace-stop.log、final-workspace-read.log；与17项核心回归同为最终APK，不将重启专用skip计通过。本轮四单元完成位置为S1.20，下一S1.21须用户另行授权并新建专属代理，不提前实现。
- 清理63份本轮中间日志及29个子代理临时编译目录，保留最终证据与原S0历史材料，logs/s1-17-20-main-intermediate-cleanup.log。所有代理已关闭，无代理中间文档。68个仓库build/.gradle/.kotlin及Rust target、PDF out-android、QuickJS android-obj/host-probe输出已删除，源码与外部固定来源/依赖cache保留，logs/s1-17-20-main-build-cleanup.log、native-cleanup.log；检查无build/target目录。Gradle/Kotlin daemon均已退出，adb server保留。
- 卸载4个本轮仪器测试包，设备仅保留com.deepstudent.app，logs/s1-17-20-main-device-cleanup.log。最终冷启动531ms，首张final-ready.png仅加载帧，不作为业务就绪；随后实际工作台就绪图final-workspace.png已主线程查看，六入口与笔记双栏可见、无重叠/空白，logs/s1-17-20-main-final-launch.log、final-visible.log。
- 清理后535份冻结输入再次通过，logs/s1-17-20-main-final-frozen.log。计划覆盖/进度验证accepted20、nextS1.21、s1GateAccepted=false通过；rules用户文件修改不暂存不提交，桌面/Operit来源不变，无push。前三个本地提交5545508、64cfad3、22c122d分别对应S1.17/18/19；当前S1.20源码/最终证据/计划与本交接随后本地提交。仓库最终只预期保留rules用户修改，发布限制不变。


## 2026-11-18T00:07:51+08:00 - S1.21 主线程验收

- 前置 S1.20 `8c6ca62`；本轮授权继续 S1.21–S1.23，按序先签本单元，不签 S1.G、不进入 S1.24。暂停/恢复后保留已有未提交实现；专属代理分别完成状态/UI与下载适配，主线程独立审查、修复、构建与真机验收，未把代理静态结果当设备通过。
- `environments/api/backend/data/ui` 与 `engines/proot` 分层：manifest/Recipe、journal/checkpoint、校验/有界解包/健康/原子激活、包安装与版本回执分别归属；UI只提交source/命令并观察任务。主进程懒owner、SAF与固定HTTPS下载两项显式动作、关闭面板不取消安装，Activity重建重新观察同owner。下载只访问OperitTerminalCore固定commit `e4646680663789002b43bbfffa6e69e65c35e614` 的镜像，64,133,552字节/SHA `91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa`；不跟随重定向、不切备用地址。HTTPS适配9项fake网络/清理/预算负例通过；当前网络不能直连该raw地址，未宣称真实远程下载成功，真实完整安装采用同SHA本地source。
- 清除未明来源预编译PRoot及test绕过开关。固定termux/proot `52d65a4dc140ca554d241a1438ddd4f6b00c9813` / tree `e844b7edf36e1d7e63d34c0eed301d2815a327ba`，143份上游C/头/构建输入逐字节核对；149 vendor锁定文件及2个Bionic构建头受hash检查。talloc2.4.2原字节源码，NDK29.0.13846066-beta3、arm64/API26，构建生成APK nativeLibraryDir可执行文件及loader，不运行下载到可写目录的未知二进制。PRoot SHA `7bd5f7e3bee34caba2c51d47ac218582494005dc5969fa07f485ef712032e30d`、loader SHA `6ec168f952f3f8dd95da467f0757e863cfda2c0b08ca20b3dba45a2a4dae96b6`，LOAD>=16KiB；GPL/LGPL原文、对应源码、构建头/脚本/锁随APK notices提供，许可证正式发布义务仍留S9审查，不自称清关。参考Operit运行环境/loader/清空LD_LIBRARY_PATH职责，没有采用其自动探测回退。
- 主审修复真实阻塞：旧binary的link2symlink路径伴随dpkg状态损坏，改固定源码构建后冷安装通过；该固定PRoot明确忽略SIGTERM，Android Process.destroy/Forcibly不能当作终止证明，使用固定host-shell先exec PID握手及PRoot明确支持的SIGQUIT清理tracees，主线程设备取消后活进程/pipe worker回0。失败阈值使用已核验PID信号，不反射Java私有API、不从guest任意输出猜PID。修复旧包回执在重装期间仍显示INSTALLED、READY先于mutation slot释放的竞态；补archive逐chunk取消、提前解包预算、generation路径限制、DNS按当前网络。venv在不可重命名的稳定generation创建，原子切换symlink，避免原.next路径残留在pip shebang。
- 本单元JVM60/60无skip（engine6/api4/backend25/data18/ui7），app懒owner另2项通过。低空间、checkpoint中断重入、激活已提交/journal失败对账、损坏归档/路径/链接/字节预算/重复提交/取消均覆盖。独立索引候选 `78119df3729f3d3cf22afb22c851940ca4b4d032` 禁缓存重跑2554/2554任务、63模块/137边无环、600架构文件/428复杂度文件零违规（仅继承5个精确terminal vendor例外）；logs/s1-21-main-clean-candidate.log、unit-summary.log、source-review.log。
- 真机iPA2475/API36/4KiB，native在APP UID10403下运行，不使用root或Shizuku安装Linux包。完整冷链1/1：原镜像校验、解包、Ubuntu24.04启动/激活、apt Git、项目venv；Git `1:2.43.0-1ubuntu7.4`、python3-venv `3.12.3-0ubuntu2.1`、python3-pip `24.0+dfsg-1ubuntu1.3`、Python `3.12.3`、requests `2.32.3`。logs/s1-21-main-cold-install-device.log。运行包与干净候选ZIP SHA不同，但11份dex、2份native和artifact resource逐字节一致，logs/s1-21-main-running-candidate-equivalence.log，不冒充APK hash相同；再安装干净候选同SHA后原生重入/稳定venv/真实取消/策略4/4，fakeUI4/4、真实app懒入口/关闭/Activity重建2/2无skip，logs/s1-21-main-staged-native-device.log、staged-ui-device.log、staged-entry-device.log。入口PNG主线程目视无重叠或空白，设备用户原字体未改；未清用户业务库。
- 候选app SHA `da8dd09a65b9b5857fd4eeb147f25dd265d562964c2453d8895c72dda6f106ae`，com.deepstudent.app/0.21.0/2100000/min26/target36/arm64/debug证书不变，8个native LOAD及ZIP16KiB静态检查通过，logs/s1-21-main-final-apk.log。535冻结输入+S0只读25/25/signed S0.G、48 Node、build-logic检查通过，logs/s1-21-23-main-frozen-s0.log、tooling.log、build-logic.log；API26真实运行、16KiB运行、OEM后台、正式签名/升级与既有发布阻断未被本单元解除。
- 当前签收accepted21/nextS1.22/s1GateAccepted=false。S1.22/S1.23的准备实现保留工作树，未混入本单元索引候选或签收；下一步继续同轮真实Shizuku协议/授权与security app装配。最终清理待三单元收口后统一执行。


## 2026-11-18T00:38:31+08:00 - S1.22 主线程验收

- 前置S1.21 `0a64884`，本条仅签S1.22，不把尚未签收的security准备代码混入独立候选。S1.21条目误写的前置短hash已按真实Git历史纠正为 `8c6ca62`，未改其验收事实。专属代理分离platform/API/UI与app接线，主线程审查、协议定位、资源/设备复验和提交；全部代理均已关闭，无跨单元沿用。
- `services/execution/api`、`platform/execution`、`features/execution/ui`分工：只读typed `id`/`pm list packages`，Binder连接/授权/SDK握手UID与真实观察UID、并发双流有界捕获、进程owner及PFD关闭分别归属。主进程懒owner和工作台Shizuku入口复用同一ExecutionSystem，Panel ViewModel隔离；关闭页面不关闭共享owner，显式shutdown才关闭。没有任意shell入口、root切换或Shizuku UserService扩张。
- 参考冻结Operit `7a39941bad80209973c535046a18195c5da9e647` 的ShizukuAuthorizer/DebuggerShellExecutor职责，不复制其全局工具体系。SDK固定13.1.5，实际核对jar公开API/AIDL：授权前不能直接读取受保护的raw getUid/getVersion，否则Sui把SecurityException误报成Binder死亡。改用SDK bindApplication已给出的、绑定特定Binder的握手身份启用正规请求；授权检查与授权后的UID核对仍走fresh typed Binder，不依赖SDK权限缓存，不猜UID。明确区分权限不足、协议故障与真正死亡；SDK替换、迟到refresh/授权回调不能恢复旧binding，epoch失效同步取消旧owner。
- 创建进程之前注册owner；晚返回句柄在撤权/死亡/关闭/超时/取消后仍销毁/关闭。双流并发排空，超限继续drain但不无限保存；stdin立即EOF，stdout/stderr各只取一次AutoClose PFD。日志仅固定阶段、异常类型、UID、命令ID、hash correlation与耗时，不记输出/callerId/原异常正文。49 JVM（service4/platform38/UI7）加app懒owner5项通过。
- 首次全量D8遇3GiB heap OOM，未计通过；本次明确构建参数6GiB/2 workers，不更改应用资源预算或弱化门禁。66模块/637架构文件、445生产复杂度文件零违规（继承5个terminal vendor例外），完整禁缓存2728任务通过；最终仅修测试挂载等待/防休眠后，独立索引 `443ecd990222e72d863e074cb74546a3cc256bf9` 再执行835任务、同一生产源码和本单元host套件/全部目标APK通过，logs/s1-22-main-clean-candidate.log、final-candidate.log、host-unit-summary.log。无fake接口冒充生产连接。
- 真设备已运行Sui与root Shizuku，SDK正确选择Sui。用户自行在正规测试页完成授权，主线程没有grant权限/代点允许或停掉用户服务；真实Binder mandatory运行（requireShizuku=true）通过1项：实际`id` UID=0与后端0一致，4轮并发`id`/只读`pm` exit0、实际UID回执与FD基线通过。真实PFD适配6项、Binder SDK握手/替换/fresh撤权边界4项，共platform设备11/11；logs/s1-22-main-staged-execution-device.log。PFD每轮精确检查三描述符关闭，预热后kernel pipe基线2→2，避免把renderer/JIT首次FD初始化误算泄漏。撤权/死亡负例用受控Binder/PFD夹具与JVM端口覆盖，未停止真实用户Sui服务，不冒充实服强杀或真实用户撤权测试。
- 初始UI复验时设备自动睡眠/锁屏，Compose无root；未计通过。只唤醒屏幕并请用户解锁，没有dismiss锁屏或更改休眠/OEM设置；测试宿主临时KEEP_SCREEN_ON，显式等待首个Compose root。修复无返回类型的runBlocking测试误推断Int、ActivityScenario启动冻结，统一前台shell测试宿主。最终同索引导出APK：fakeUI6/6（连接/撤权/死亡/身份/取消/只读动作），app入口懒加载、关闭不停止、持久布局重读/Activity重建及错误重试2/2，无skip；logs/s1-22-main-staged-ui-device.log、staged-entry-device.log。共19项设备通过。主App真实入口亦已加载，主线程看PNG无重叠/空白，用户进一步确认界面已实际加载；主App权限与test包分开，真实主App保持等待用户单独授权，不将其显示为已验证root。
- app候选SHA `8c452ee0ce840a2e1d2d2f018a8317c2938bca3cc93c457393dabe72c222b791`，身份/版本/min26/target36/arm64/debug证书保持，8 native LOAD/ZIP16KiB静态通过；logs/s1-22-main-final-apk.log、device-events.log、entry.png。API26/16KiB真实运行、OEM后台与旧发布阻断依然保留。本轮未执行系统写命令，未改变已有运行服务。
- 当前accepted22/nextS1.23/s1GateAccepted=false；下一步只收口已授权的S1.23，不进入S1.24。清理及全轮最终回归另追加。


## 2026-11-18T00:45:23+08:00 - S1.23 主线程验收及三单元收口

- 前置S1.22 `8262f05`。本轮授权S1.21–S1.23至此均已完成，下一S1.24，不进入同步provider或签S1.G。两名本单元专属代理分别负责security实现与app窄工厂，主线程审查、修补、实际构建/真机/桌面向量复验；所有子代理已关闭。旧准备代码保留后重审，不使用回退前已删除实现的历史测试冒充本轮证据。
- `platform/security`职责分离：AndroidKeystore包裹随机32字节master、加密credential store、Argon2id KDF/DSBK-v1/v2 codec、私有暂存及原子激活、固定审计/诊断。app仅主进程懒持有SecurityPorts，复用LazyProcessOwner；普通startup和engine/isolated不访问Keystore，后续服务只注入各窄端口，本单元不添页面或业务同步。Context/path失败压成无敏感cause的固定错误；记录仅受校验的operation/outcome/digest/byte-count/error-code。
- 生产Argon2id参数保持冻结桌面v0x13、64MiB/t3/p4/32字节，BC1.87实现固定版本；同进程串行KDF避免并发倍增，协议参数上限与本机资源准入分开，不能用瞬时未GC free bytes错误拒绝后一次合法KDF。生产写v2分块1MiB，读取按冻结wire处理final-bit nonce、块计数及版本；v1内存预算与v2输入/输出/chunk/KDF预算明确。错误密码、数据/标签篡改、乱序/重复/截断、错误末帧和输出超限均不激活目标，失败/取消清暂存；活跃暂存的跨实例清理和总量竞争受管控。
- 不缓存解包后的master；每次操作观察Keystore状态，失效/包裹密文篡改绝不自动创建替代alias。凭据写入与显式master重建共享进程及OS文件事务锁，避免并发生成不同master或错误覆盖。密文记录与暂存限制符号链接，拥有的key/密码UTF8/明文缓冲清零；重建是显式破坏性动作，不作为错误回退。App测试使用cache隔离目录与UUID alias，清理不触及生产alias或用户凭据。
- 权威fixture仍取冻结桌面 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 的backup-crypto.ts/dsbk-stream.ts及S0 golden，源码SHA在生成器逐项核对，不更新wire或冻结字节。新非冻结production fixture覆盖Unicode/UTF16替代/NUL/不规范化密码与4个流边界；`--check`通过；Android JVM生产KDF产出v1/v2两份1,048,577字节明文容器均由冻结桌面反向解密/逐字节核对通过，testRevision=s1.23-security-hardening-v2，logs/s1-23-main-fixture-check.log、desktop-roundtrip.log。JVM软件wrapper不冒充AndroidKeystore；真实device另验。
- platform56 JVM+app工厂/owner5 JVM全部通过，含真实生产KDF而非廉价测试KDF；logs/s1-23-main-unit-summary.log。最终全工程533唯一JVM（debug与纯Kotlin，排除release重复）全过；67真实模块/674架构文件与468复杂度文件零违规（5个已登记精确terminal vendor例外保持），全索引 `7052108b841b0a1fa83dd91c1b3e5d0964b72c47` 禁缓存2812/2812任务全部实际执行，logs/s1-23-main-clean-candidate.log、s1-21-23-main-current-host.log、test-counts.log。构建仍明确6GiB/2 workers；不把更大构建heap当作安卓运行内存预算或降级路径。
- 最终干净候选APK重装后平台真机8/8无skip：真实Keystore主密钥/credential加密读回、错密/篡改拒绝、真实DSBK生产KDF与fixture、活实例失效观察/不自动重建alias/显式重建/并发wrapper；app隔离工厂2/2无skip：真实alias/ciphertext/重开与typed审计、错误消息/cause无秘密。logs/s1-23-main-staged-security-device.log。最终app同时再验环境入口2、Shizuku入口2、三个真实进程图3，共app9/9；logs/s1-23-main-staged-app-device.log。审计选定日志sentinel扫描通过，凭据密文与诊断明文扫描由fixture在删除前断言，logs/s1-23-main-audit-scan.log；不宣称扫描设备其他应用的私有日志或数据。
- 最终app SHA `9ab9689980dbd6e2eff95b53303d4ae375a9a505ad8f4b8a4090ed5f574e286e`，com.deepstudent.app/0.21.0/2100000/min26/target36/arm64/debug证书不变，8 native LOAD/ZIP16KiB静态通过，logs/s1-23-main-final-apk.log。设备仍iPA2475/API36/4KiB。535冻结输入/S0只读25/25/signed S0.G、48 Node与build-logic门禁均保留成功回执；最后冻结输入再核，logs/s1-21-23-main-final-frozen.log。API26/16KiB真实运行、OEM后台、正式签名/升级/许可及既有native发布阻断仍未解除。
- 真实HTTPS远程镜像下载仍只具备adapter负例/来源与hash证据（当前raw直连不可用），完整Linux真安装已走同hash本地source验证；正式主App的Shizuku权限与测试包分开，主App实际界面已加载、等待用户单独授权，不伪报已授权。未新增自动回退、未动系统锁屏/休眠/权限或停止用户Sui服务。
- 当前accepted23/nextS1.24/s1GateAccepted=false。最后仅进行本轮证据/工作区/测试包/构建中间产物清理与本地提交；不推送、不进入下一单元，清理回执另追加。


## 2026-11-18T00:51:24+08:00 - S1.21–S1.23 最终清理与交接位置

- 三单元实现与验收提交分别为 `0a64884`、`8262f05`、`99bd3a4`；无推送。当前accepted23/nextS1.24/s1GateAccepted=false，未开展S1.24或阶段出口。最终进度/计划一致性及535冻结输入再验证通过，logs/s1-21-23-main-final-check.log；docs/README当前指向S1.24，原冻结原计划/SQL/wire/fixture未改。
- 卸载本轮6个测试包（app.test、environments.data/ui.test、execution.ui/platform.test、security.test）及其隔离rootfs/测试凭据数据，仅保留com.deepstudent.app。真实Sui/root Shizuku原有服务未停止；生产alias未删除，用户业务库未清空。清理后设备app SHA仍为 `9ab9689980dbd6e2eff95b53303d4ae375a9a505ad8f4b8a4090ed5f574e286e`，MainActivity正常启动；logs/s1-21-23-main-device-cleanup.log、final-check.log。
- 删除75个仓库build/.gradle/.kotlin编译目录及Rust target、外部PDF输出、QuickJS/proot/talloc编译输出；保留固定外部源码、来源归档与依赖cache。外部Operit、PRoot与冻结桌面checkout均无新增tracked差异；所有Gradle/Kotlin daemon已结束，adb server保留。清理后仓库无build/.gradle/.kotlin/target目录；logs/s1-21-23-main-build-cleanup.log、native-cleanup.log、daemons-stop.log。
- 清理4份未跟踪的中间设备日志、旧硬编码proot诊断脚本，以及38个本轮/tmp scratch项和本轮设备/data/local/tmp文件；不删除历史签收证据、用户其他scratch或来源cache。最终候选/设备/桌面对拍/审计证据与handoff保留，子代理中间材料随build删除，没有新增中间文档；logs/s1-21-23-main-intermediate-cleanup.log。
- 工作区仅保留用户原有未提交 `docs/rules/rules.txt` 修改（SHA仍为 `994889fccd12c00edcd87dfb3da9de6ac77194b5d4481105b22d9aa15c32634a`）；没有暂存或提交用户规则。后续S1.24若获授权，从本轮Keystore/credential窄端口和已冻结桌面合同接同步provider；真实raw镜像下载/API26/16KiB运行/正式签名等未通过项目仍按上述边界记录，不以本轮工程验收代替发布验收。


## 2026-11-18T07:41:19+08:00 - S1.24 主线程验收

- 前置S1.23 `99bd3a4`，上轮交接HEAD `afaae7f`。本轮授权S1.24–S1.25并明确允许最多3专属代理协助、逐单元本地提交、不推送；本条只签收S1.24，S1.25准备代码未混入独立候选，不签S1.G或进入S1.26。开发中曾短暂启动第4代理，发现后暂停该会话，后续保持最多3；不将其作为合规并发证据。
- `services/sync/api/backend/data` 与 `features/sync/ui` 分层，app主进程懒owner只注入credential窄口，不在engine/isolated初始化。WebDAV配置/显式create与join、独立UUID v4设备身份、manifest/format/change/batch无损codec以及同步最小入口交付；本单元没有记录同步按钮，不用假同步冒充S1.25。clear须用户明确确认，只删除连接配置和关联凭据，保留设备ID和本地业务数据。
- 凭据沿用S1.23真实Keystore/密文存储，配置仅保存版本化credentialRef；先写新凭据再原子交换配置、进程及OS锁、fsync、有界NOFOLLOW读、祖先符号链接及本机身份校验。失败不替换旧可用密码；缺失/失效与认证篡改为固定typed错误，无URL/用户名/秘密/原cause输出。密码CharArray在成功、失败、取消和忙拒绝时清零；Compose临时输入不进入SavedState或普通偏好，不声称JVM String可物理擦除。
- WebDAV固定HTTPS，只有显式测试factory允许loopback HTTP，拒绝重定向和不安全路径；目录逐层创建、Depth1有界递归列举，DAV根/response/propstat/status及href origin/范围/重复/截断嫌疑均fail-closed。Android SAX拒绝DOCTYPE/ENTITY/非法UTF8，网络回调内消费并关闭response使headers后取消调用Call.cancel。取消测试证明及时结束，但没有额外服务端socket EOF测量，不扩张为全量网络故障矩阵。单对象16MiB、目录749/条目10000预算明确。
- 主审重写并与冻结 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 的S0黄金样本精确字节/SHA对比：change可选null、i64/u64边界、重复键last-wins、manifest、batch、format及deviceHash/key。核查真实桌面后确认format序列化字段是 `min_client`、读取兼容 `min_reader_version`；先前审查认为min_client错误的判断已纠正，未按误判改wire。create v4/min reader3，v5拒绝；marker/DSBK/未来格式/回读不符/取消不保存配置。真实UUID与桌面身份相互独立。
- 主线程仅S1.24索引候选 `b1a7d320707828bcc34ac7c3734e2d379491bec7` 禁缓存902/902任务实际执行通过；71模块/156边无环、709架构文件、485复杂度文件零违规，仅继承5个terminal精确vendor例外，68实际AAR minSdk<=26，日志 `logs/s1-24-main-clean-candidate.log`。36项Node/计划/来源测试与535冻结输入通过，`s1-24-main-frozen-tools.log`、`frozen-inputs.log`；未修改冻结SQL/schema/wire/来源或旧S0回执。
- 同一导出候选真机iPA2475/API36/4KiB：app7/7（工厂密文重开、HTTP join、跨Activity配对、workspace懒加载/关闭重开、真实三进程图），fakeUI4/4（亮暗loading/表单/取消/错误/重试/设备ID/清除确认），platform2/2（SAX/XXE、Keystore/密码扫描），真实冻结桌面与Android HTTP provider往返1/1，共14项无skip。日志 `s1-24-main-staged-{app,ui,data,provider}-device.log`。桌面put→Android get/put/list/delete/create/join→桌面get/list/delete和format读取均实测，并非内存provider替代。
- 首次真机失败未计通过：test缺INTERNET修复；Android `/data/data` 祖先符号链接导致安全路径拒绝，app工厂与隔离测试使用Context规范路径，不在存储层放宽symlink策略；fakeUI ActivityScenario启动冻结/约96秒Process crashed，日志未确认根因，换已接受显式shell前台测试宿主后4/4通过，不宣称解决OEM后台保活。只测试Activity短暂KEEP_SCREEN_ON，不修改系统锁屏/休眠/权限或停止用户Sui。
- 最终app SHA `baa1a7b66f93cdab016d6cb2d80548bbdd44b4151b74808165ac40cca9fa2618`，com.deepstudent.app/0.21.0/2100000/min26/target36/arm64/debug证书不变，8native LOAD及ZIP16KiB静态检查通过，`s1-24-main-staged-apk.log`。API26/16KiB真实运行、OEM后台、正式签名/升级/许可等既有发布阻断不变。旧PNG删除和rules修改为开工已有用户工作树差异，不暂存或提交，不擅自恢复。accepted24/nextS1.25/s1GateAccepted=false；继续完成本轮S1.25，最终中间产物统一清理。


## 2026-11-18T13:03:06+08:00 - S1.25 主线程验收及本轮完成位置

- S1.24最终提交因本机Git身份缺失延后，用户已明确授权本轮仅命令级沿用最近提交身份 `Codex <codex@localhost>`，没有修改全局配置或推送。主线程从保存的S1.24完整索引树独立本地提交 `b383fa0`（父`afaae7f`），保留S1.25工作树和索引不回退。本条只签收本轮另一单元S1.25，不进入S1.26、不签S1.G；下一项须另行授权。
- 生产链为应用域懒SyncController→ConfiguredSyncTransfer→远端实例/格式及schema门禁→桌面同线协议租约→blob传输→change下载/领域apply→change发布/回读确认→本设备manifest。服务只依赖注册的DomainSyncAdapter；notes/data拥有笔记/历史/资源引用与同库事务，cards/data只提供混合批次附属 `mistakes.anki_decks` 入站窄适配，没有把完整牌组业务或全域apply提前签收。UI不读库、不解析wire、不拼远端对象键。
- 笔记捕获使用真实 `__change_log.changed_at`，保留所有原列及无损大整数/显式null；按需补资源、文件、blob、文件夹、格式、版本和typed relation，忽略既定本机派生列。游标与删除版本按instance/device隔离，领域apply、回声抑制和游标更新同一vfs事务；主线程将协程context取消回调注入notes和附属deck，在首行写后/echo标记前/提交前取消均回滚。附属mistakes先幂等提交、再提交vfs游标，跨库非假原子事务，失败保留游标等待重放。未知字段值和已有HLC/dev envelope落vault并回传，不宣称本单元已实现所有未来域的合并策略。
- 最小PublicationJournal预留精确key/seq/压缩字节/hash/原change_log id；网络写入后必须GET逐字节回验才确认，失败/退出重开复用同一预留对象，待续作先于新下载/分配。领域mark与journal确认分开但均幂等，测试覆盖标记失败后再开无重复key/seq。独立本机文件锁与应用互斥防重复执行。云端锁沿冻结桌面独立contender、pending/committed选举、心跳、所有权检查和定向释放，取消释放有界；不把无条件WebDAV协议声称为强一致CAS。
- 真正资源双向验证不是只回传笔记：冻结桌面用正式NoteRepo/AttachmentRepo创建1,048,593字节外置附件与typed reference，Android正式NotesService编辑、AttachmentService新建1,048,607字节外置附件并link；经真实WebDAV与JNI zstd发布，冻结桌面生产download/apply/NoteHistory hooks和AttachmentRepo `getContentBounded`读取完整字节。两个hash分别 `c26032d5154f96bd29c799447d715ab681d8d0aa308ecc6f321a35d98f0672da`、`f0ca53819151140ed2af6f3eeb899337f2cf8d45eaf51177d17cd80835fff23b`。edit/delete/restore/repeat四阶段及cleanup均过，typed引用/删除状态/正文一致，repeat远端新变更0；`logs/s1-25-main-signed-candidate-notes.log`，没有过滤桌面迁移自然产生的anki_decks或手写SQL模拟业务。
- 固定zstd源码commit `f8745da6ff1ad1e7bab384bd1f9d742439278e99`、归档SHA `4b0bd1f0cfb25e61b9103c35f27395530ff5b4c0d2513a00fd745849e85ea52c`；每次从归档新解包并逐源hash核对后编译，不从可能插入额外头文件的缓存直接编译。JNI encode/decode、输入/输出16MiB和窗口上限、arm64/API26/16KiB静态对齐，许可/来源/构建脚本/JNI源随assets；Android库SHA `dc5eccc7ce4610fb8b52ca95e7cca27de1e6f3dc484a90fcfcc20d5d3240e017`。设备黄金帧解压/往返/无效/边界通过；宿主JNI压缩产物由Node桌面zstd反解207字节和SHA精确匹配，不要求不同zstd版本压缩字节相同。`s1-25-main-zstd-desktop-decode.log`。
- 同步页交付真实同步/阶段/取消/重试，配置删除与配对/同步互斥；页面移除和Activity重建只停止观察，不取消应用任务；显式取消/owner关闭才结束工作。同步完成/失败后查询失效刷新原笔记列表和干净当前文档，dirty草稿不覆盖，显示冲突供既有OCC处理。fake UI5/5、app工厂/页面/工作台/真实进程图8/8、zstd1/1、四阶段真实业务4/4，共18项业务设备通过（cleanup另1，不把它冒充功能测试）；provider及Keystore原2+1设备在同生产链再次回归。亮暗态由fake设备覆盖，未改变用户字号/系统权限。
- 首次完整标准 `npm run verify:s1` 被开工前用户已删旧PNG证据阻断，未修改verifier跳过。扩展既有 `verify-staged --verify-current` 在真实Git索引导出的独立目录跑原标准入口，Git历史只读挂载，缺失历史截图只存在该候选目录，不恢复用户工作树删除。标准51 Node、build-logic18、设计token、全部JVM/构建/实际AAR/APK门禁通过，`s1-25-main-deliverable-standard.log`；73模块、763架构文件、525生产复杂度文件零违规，仅继承5terminal vendor精确例外。后续只增加deck取消回调及3测试，最终候选树 `a5ed2c76b64cd60c4212b4fc50b3aeb10c6d7327` 再过3164任务（1410执行/1754缓存）、全套621唯一JVM全部通过，`s1-25-main-final-owner-candidate.log`、`final-test-counts.log`。构建默认heap6GiB/2workers与此前OOM实测一致，不改变安卓运行预算。
- 冻结S0快照25/25、signed S0.G、138SQL/126registry/61RowSync重新通过，`logs/s1-24-25-main-s0-regression.log`；桌面checkout无tracked差异、不fetch、不采用旧安卓链。最终候选app SHA `b18dcd01ce3e77f3911f01765aec3cae62eaf6481d1569dbdfec27b76c15d8cf`，com.deepstudent.app/0.21.0/2100000/min26/target36/arm64/debug证书不变，9native LOAD与ZIP16KiB静态检查通过。真机iPA2475/API36/4KiB，API26/16KiB运行、OEM后台、正式签名/升级/许可等发布阻断仍保留。
- 本单元范围明确：明文笔记、其资源和依赖链双向闭环。未支持域/旧legacy change对象/追加blob tombstone-event遇到时明确拒绝并保留安全点；单对象16MiB本机预算，不截断为“成功”；没有宣称已完成S2.27/S8全域apply、全部provider能力或复杂冲突治理。DSBK/加密仓库本单元拒绝，S1.26尚未开展。当前accepted25/nextS1.26/s1GateAccepted=false；最后清理构建产物、测试包和中间证据，再本地提交S1.25，不推送。


## 2026-11-18T13:11:04+08:00 - S1.24–S1.25 清理及本地提交收口

- S1.24已提交 `b383fa0`。S1.25最终源码候选与设备复验后，全工程JVM重计621/621无skip，牌组取消事务新增3例包含在11项牌组数据测试中；两个单元只创建本地提交，没有push，用户授权的命令级身份不写入全局或本仓库配置。
- 删除38份本轮未跟踪中间日志，保留S1.24正式验收、S1.25标准入口/最终源码候选/最终设备/双向附件/621测试/S0回归及清理证据；`logs/s1-24-25-main-intermediate-cleanup.log`。本轮没有新建中间交接文档，不删除开工前历史签收材料。
- 删除81个仓库build/.gradle/.kotlin目录及本轮原生编译输出、PDF out-android、zstd host/android objects、QuickJS android-obj；固定外部来源归档/源码和共享依赖cache保留。清理后仓库无build/.gradle/.kotlin/target目录；`s1-24-25-main-build-cleanup.log`、`native-cleanup.log`。项目隔离Gradle home的3个daemon停止；另有用户默认Gradle home的既有daemon未擅自终止，不声称全系统Java进程清零。
- 卸载本轮4个instrumentation包（sync.ui.test/app.test/zstd.test/sync.data.test），设备仅保留com.deepstudent.app，ADB reverse映射0；测试UUID alias/隔离数据库由每次finally清理，未删生产alias/用户库，原Sui/Shizuku服务未停止。最终正式入口冷启动586ms；`s1-24-25-main-device-cleanup.log`、`final-launch.log`。本单元未新增截图交付要求，真实界面交互由设备测试覆盖，不补造不存在的目视截图结论。
- 清理后计划覆盖及535冻结输入再次通过，`s1-24-25-main-final-document-check.log`、`final-frozen.log`。工作区预期仅保留用户原有docs/rules/rules.txt修改和21个历史PNG删除；它们不暂存、不提交、不恢复。直接工作树 `npm run verify:s1` 仍会报告这些历史图片缺失，原标准入口已在保留原字节图片的独立索引候选运行通过；此限制在交接和最终回复保留，不放宽验证器。当前accepted25/nextS1.26/s1GateAccepted=false，S1.26未实施。


## 2026-11-18T13:13:12+08:00 - 最终提交回执

- 本轮两个本地提交：S1.24 `b383fa0`、S1.25 `e979ee1`，均采用已授权命令级身份，无push、无Git持久配置变更。最新源码、最终日志和阶段交接均已提交；accepted25/nextS1.26/s1GateAccepted=false。
- 提交后计划与535冻结输入再过；设备仅保留com.deepstudent.app且reverse0；Git暂存区空、无新未跟踪文件。剩余工作树差异精确为开工已有的rules文件修改和21个历史PNG删除，未混入本轮提交。此条仅追加提交回执，不改变已验证源码或重新签任何阶段出口。


## 2026-11-18T18:14:57+08:00 - S1.26 主线程验收

- 前置 S1.25 `e979ee1`／交接 `ccccf60`。S1.26 两名专属子智能体分别补 JVM 故障回归与冻结桌面 WebDAV 夹具；主线程审查/修补、独立构建、真机复验与按单元提交。另有独立 S1.27 子智能体，本提交不混入 benchmark 工作，也不签 S1.27 或 S1.G。
- 明文与加密复用 `SyncPipeline`、相同工作台入口、同一 domain apply、publication journal 和游标。生产 Keystore 分别保存 WebDAV 和 E2EE 密码；配置只存凭据引用和模式。冻结桌面 v2 marker/Argon2id 先验证后开远端 lease 和本机 journal，写对象前复验 marker；新仓库两个模式争同一条件创建位置，创建未完成时不能以 legacy 明文加入。加密 metadata/change 用 DSBK v1（change 先实际 zstd），附件用 DSBK v2 并核验密文 size/hash、认证及明文内容 hash；immutable 资源不重写，重试不重封已持久化的批次。错误有固定 typed code；仅更正本机密钥并重试，不重配、清库或改变设备身份。
- 独立暂存树 `d6c83e1cb7974fe3edd07ff5850d29010c4042f8` 执行标准 `verify:s1` 并导出 APK：55 Node、18 build-logic、634/634 唯一 JVM（无 skip，新增 13 项）、3165 工程任务通过；73 模块／162 边无环，770 架构文件、528 复杂度生产文件零违规，保留既有 5 个精确 terminal vendor 例外。原样验证 535 个冻结输入。标准入口在独立候选看到历史 PNG 原字节；没有恢复或提交用户工作树的 21 项删除、没有修改 rules.txt。证据 `logs/s1-26-main-candidate.log`。
- iPA2475 / API36 / 4 KiB 上以候选 `b6e21e6f25b4b00560ad883d38a4fda04f722139e8dabdbe3938d01e6cf81d90` 独立复验。冻结桌面 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 正式仓储、同步发布/下载/apply、WebDAV、zstd 及 DSBK 对拍：明文 4 个业务阶段，加密 8 个业务阶段（edit/delete/restore/repeat、预留批次后断网、错密、正确重试、Android 建加密仓库供桌面验 verifier），两次 cleanup 另计。真机 UI 6/6（含更正密码同入口、保留更改提示、凭据清零）、app 生命周期入口 2/2。两模式附件明文 SHA-256 分别 `c26032d5154f96bd29c799447d715ab681d8d0aa308ecc6f321a35d98f0672da` / `f0ca53819151140ed2af6f3eeb899337f2cf8d45eaf51177d17cd80835fff23b`；加密双方 final seq 为桌面 2/Android 4，明文为 1/3；repeat 不新增批次。错密阶段远端无 PUT/DELETE/MKCOL，全部对象逐字节不变，Android pending/change_log/游标及序列不变；更正后同一 key、seq、密文字节完成并应用新桌面批次。见同名 staged 系列日志。
- APK 仍为 `com.deepstudent.app` / 0.21.0 / min26 / target36 / arm64 / debug 开发签名，9 个 native 静态 ELF/ZIP 16 KiB 校验通过，不冒充真 16 KiB 运行或发布验收。未升级无 verifier 的 legacy v1 marker，未提供换密/明密仓自动迁移；仍受 S1 笔记纵切、16 MiB/对象及已签边界限制，不宣称 S8 全域与大文件同步。accepted26，next S1.27，S1.G 未签收。


## 2026-11-18T18:56:24+08:00 - S1.27 性能工具与平板实测（未签收三档）

- S1.26 已本地提交 `5306bec`，无 push。S1.27 由两名专属子智能体分别承担采样/报告与官方冷启动模块；主线程接入构建、同步夹具、核验安全隔离、独立跑构建及真机。普通 App ID、签名范围与网络策略不变；仅 `-Pdstu.benchmarkApp=true` 增加 non-debuggable/profileable 隔离包 `com.deepstudent.app.benchmark`、其页面 instrumentation 以及 `:benchmark:startup` self-instrumentation。采集器在 ADB 前核对包名/测试目标/档位源指纹，在设备端核对安装字节；已有同名隔离包直接拒绝；重置、安装、清理不触碰普通包的数据。
- 固定 64 条 UTF-8 笔记与独立加密同步语料，后者每轮由冻结桌面正式 WebDAV/DSBK 发布一条笔记与 1,048,593 字节附件，Android 另备一条待上传笔记；只有工作台同步点击至 COMPLETE 被计时，配对和双向验证在窗外。真实 PTY 用确定的 2,176 字节输出、用不可能由 echo 原样出现的 viewport 终止标记。帧使用原生窗口 FrameMetrics，PSS 是主进程约 100 ms 采样峰值，非全设备内存。官方 AndroidX Macrobenchmark `StartupTimingMetric/COLD` 以实际工作台恢复后报告 fully drawn，做 3 次 Partial PGO 预热及 30 次正式采样，官方 JSON 有 30 个配对的原始 TTID/TTFD 及 30 个不同 Perfetto 轨迹；`am start -W` 只作 smoke，未替代正式数据。AndroidX 只导出 PGO 预热次数，未捏造预热耗时。各正式样本按 nearest-rank 聚合，不混入预热；所有其他场景也执行 3 次预热及 30 次正式测量。
- iPA2475 / API 36 / 16,384 MiB / 4 KiB / 825.6dp 的平板档，单一冻结源码候选六项完整采样，无伪造/补样，六项都在**暂定**预算内（不是三档通过）。测量 JSON 与透明范围见 `logs/s1-27-main-tablet-summary.json`。

| 平板指标 | P50 | P95 |
| --- | ---: | ---: |
| 冷启动 TTID (ms) | 243.67 | 279.19 |
| 冷启动 TTFD (ms) | 316.10 | 350.85 |
| 笔记滚动每轮帧时间 P95 (ms，再跨轮聚合) | 13.25 | 14.26 |
| 笔记滚动主进程采样峰值 PSS (MiB) | 140.36 | 177.08 |
| 真 PTY 命令至渲染完成 (ms) | 994.09 | 1045.04 |
| 加密 WebDAV 同步应用 (ms) | 2335.75 | 2384.79 |

- 架构/复杂度与真实主机耗时同候选记录：含启动模块计 74 项目／162 允许依赖边／无环，530 生产复杂度文件零违规，继承 5 个 terminal vendor 精确例外；含刷新架构/PSI/Detekt 报告与打包的增量 build 命令 28.70 s、全工程增量 JVM test 命令 35.98 s。报告哈希及原命令在验收证据中，不把增量用时当全量编译用时。普通包的独立暂存候选 `2b5782619150a29b98829dbd4fa4a692b088514c` 完整标准入口 634/634 JVM、18 build-logic、3165 构建任务，冻结输入 535，普通 APK 身份及 9 native 静态 16 KiB 检查通过。19 项 benchmark Node：工作区 19/19；隔离完整构建中 APK 尚未生成时，硬拒绝普通 APK 的实包负例合理跳过，其他 18/18，通过后的证据未冒称隔离环境也运行了该实包案例。
- 30 个原始 Perfetto、AndroidX JSON、各轮原始样本、双向校验记录和完整成本报告留于本机 0700 目录 `/home/xutao/.local/state/dstu-android/s1-27/2026-11-18-tablet-97a4ebf7`（不提交设备识别材料或系统轨迹）；从保留目录重放报告字节一致，退出码 2，整体 `missing`。
- 后续复跑入口：
  1. `node tools/s1/gradle.mjs -Pdstu.benchmarkApp=true :app:assembleBenchmark :app:assembleBenchmarkAndroidTest :benchmark:startup:assembleBenchmark`，按需同时执行 `verifyArchitecture verifyComplexity`。`node tools/s1/benchmark-provenance.mjs --sha256` 得到源码指纹，和 APK 内编入值相符。
  2. 在仓库外 0600 spec 填 `device` 的 `serial`（勿提交）、`profileId`、`operatorAlias`、`physicalAttested=true`、实际 `ramMiB`；`build` 的 `apk`、`testApk`、`startupApk` 为上述三个 APK 的绝对路径，另填 `variant=benchmark`、`versionCode`、`schemaFingerprint`（当前 `config/schema/final-lock.json` 的 VFS fingerprint）、`wireVersion` 和 `sourceFingerprintSha256`。解锁设备并独占后跑 `npm run benchmark:s1:collect -- --spec <private.json> --out <new-private-dir> --execute --ack-disposable-data --sync`。未加 `--sync` 或无 `startupApk` 必然留缺测，不能以 smoke 或模拟器代替。
  3. `node tools/s1/benchmark-cost.mjs run --out <private-dir> --label build|test -- <actual-command>` 分别记实际 Gradle 命令；用 `benchmark-cost.mjs collect` 传入两份测时记录和同一源码生成的 graph/PSI/Detekt 文件。`npm run benchmark:s1:report -- --run <run.json> ... --cost <cost.json>` 传入各档同一构建的证据。0/1/2 分别是完整通过/验证或预算失败/未完备，不用整体未验收去否定平板六项事实。
- 剩余硬条件：独立低内存手机（API 26–28、arm64、2–3 GiB）和中档手机（API 33–36、arm64、4–8 GiB）未接入；待真机实测后锁定设备身份、校准并冻结绝对预算。低版本 AndroidX 编译 reset 可能重装隔离包，已在冷启动后复查语料而不是算作成功；如发生须实测解决低版持久语料及官方 TTFD 兼容，不能沿用平板数据。折叠/16 KiB 真实行为未验证。`accepted26 / next=S1.27 / s1GateAccepted=false`，不进入 S2。
- 清理：隔离采集包及本轮另两项测试包卸载，reverse 0，普通 App 与用户业务数据保留；删除 81 个生成目录和本轮 native 输出，保留冻结来源与共享依赖缓存。子智能体均已关闭，临时说明/日志未进仓，`logs/s1-27-main-cleanup.log` 记录最终清理。工作区只保留用户原先的 rules 改动及 21 个历史 PNG 删除；不恢复、不暂存、不提交这些差异。


## 2026-11-18T19:28:23+08:00 - S1.27 双真机功能验收

- 用户确认本单元只要求此前 iPA2475 与当前 V2232A 两台实际 ARM64 设备跑通，不进行三档验收、不限制设备档位、不按性能数值判定。此条取代上一条的 S1.27 剩余档位/预算硬条件。修改生效计划的 S1.27 行，不改绑定 S0.G 的冻结原计划、冻结语料、设备档位历史合同、序列或后续阶段。子智能体负责未分档证据和测试，主线程整合、实机运行、独立复审及提交，未更改 Android 业务或设备权限。
- iPA2475 沿用并重新验证上一轮原始平板证据；V2232A 为独立真机，API 36 / arm64-v8a / 4 KiB。两台的隔离 App ID、版本 0.21.0（versionCode 2100000）、VFS schema 和 wire 一致；APK 字节哈希不同（重建后嵌入的源码指纹不同）。核对版本间 `app`、`benchmark`、`core`、`engines`、`features`、`platform`、`services` 源码无变化，但不声称复跑了同一字节 APK，也不用两机数字判性能退化。
- 功能证据：两台均能通过实际启动、固定笔记滚动、主进程内存采样、真实 PTY 和与冻结桌面加密 WebDAV 双向验证。新手机 30 次笔记帧/内存及 30 次终端正式样本；同步 3 次预热加 29 次正式双向验证完成，最后一次采样失败（未定位，不把它算成通过）。真实启动由隔离 App 的 activity launch smoke 及进入真实页面验证。官方 AndroidX COLD 在手机上两次未收到 ProfileInstaller `DROP_SHADER_CACHE` 广播，TTID/TTFD 未采集，不用 `am start -W` 数值填补，不绕过权限或忽略 AndroidX 检查。首轮完整量测报告继续为失败；用户指定的功能范围通过，见 `logs/s1-27-main-phone-measurement.log` 和 `logs/s1-27-main-two-device-functional.json`。
- 主机：仅工具暂存树 `24999776790c6b5438baaee08d785e897e6aa391` 完整标准候选通过（634/634 JVM、18 build-logic、535 冻结输入、架构/复杂度零违规，普通包 APK 门禁通过）。最终另加功能模式与负例后，独立跑 28/28 Node，含普通 APK 拒装实包回归、缺测/篡改、重复身份和启动 smoke 不冒充正式量测。手机 OriginOS 有 shell 不可枚举的辅助用户，改为逐目标全局包存在性检查；必须明确 absent，安装显式 `-R` 禁止替换，且只清理由本次安装、字节匹配的隔离包。
- `npm run benchmark:s1:collect -- --observe --sync --execute --ack-disposable-data --spec <private.json> --out <new-private-dir>` 可采集未分档设备；私有 spec 保留 serial、operatorAlias、physicalAttested 及构建元数据，不填 profileId、ramMiB。`npm run benchmark:s1:observe -- --functional --require-two --run <tablet-run.json> --run <phone-run.json>` 核对两台真实身份不同、应用版本/schema/wire 一致、启动 evidence 及各实机业务；不用预算通过，保留严格量测的失败/缺样。去掉 `--functional` 可查看整轮量测完备性；旧 `report` 仍可重放历史三档证据，但不是本轮签收条件。
- 手机原样试验、诊断重试、应用校验与双模式报告留在本机私有目录 `/home/xutao/.local/state/dstu-android/s1-27/2026-11-18-phone-df2105c5`；已从保留目录复验双真机报告一致。设备测试包全卸载、reverse 为 0，未触普通应用；编译中间目录与本轮 native 输出清理，固定来源缓存保留。
- 当前 `accepted27 / next=S1.G / s1GateAccepted=false`；S1.G 仍须单独审查，不进入 S2。


## 2026-11-18T20:14:35+08:00 - S1.27 手机单次同步缺样定位及复跑

- 查回 V2232A 原始样本和保留的设备事件：第 29 次 Android 用例于 19:21:32.369 正常结束，19:21:35.976 写入桌面也验证通过的证据；约 3.55 秒后写入第 30 次失败。期间没有该隔离包新的数据清理、进程启动或测试记录，也无同期 ADB USB 断开记录。定位在下一轮测试的准备/启动窗口，未见 Android 同步业务执行。旧采集器把运行时错误统一归为 `fixture verification failed`，未保留当时的具体步骤/errno，不能反推唯一历史根因。
- 原设计把电脑临时监听端口直接用作手机侧反向监听端口。V2232A 上用自建临时 loopback 监听器复现 `Address already in use`，新隧道未建成、不会触发 Android 用例；未人为占用端口的旧准备序列另跑 34 次均通过。端口冲突是已证实的旧设计缺陷，但不是第 30 次事故的直接证据。中间 `tcp:0` 尝试又发现本机 ADB 37.0.1 的反向目标记录与实际分配端口不一致：日志为 `disallowed connect to tcp` 后 daemon 退出；此为另一次未通过复验，非原始事故。恢复正式主机 ADB，核对字节后仅卸载本轮两隔离 APK，reverse 为空。
- 采集器改用独立显式设备端口及 `--no-rebind`，只有建隧道前确认地址占用才在至多四个尝试内换端口；不会重跑同步样本或放宽通过条件。日志保留失败步骤、固定类别与独立清理失败，不输出命令、端点、口令或业务数据。子智能体补实现和负例，主审收紧实际 loopback 目标、复查真实 ADB 版本行为、完成独立索引构建和复跑；benchmark 工具回归 45/45。
- 最终 V2232A 同一新轮加密 WebDAV 真实双向验证完成 3 次预热、**30/30 正式采样**，另实跑启动、笔记/内存、PTY，未采集新的 TTID/TTFD。沿用原平板原始记录核验双真机功能报告 complete；原手机 29/30、旧 AndroidX 启动缺测和中间 adb 故障都不被覆盖或计为通过。两机并非同一字节 APK（版本、schema、同步 wire 仍一致；此补丁不改业务源码），按用户约定只签功能，不补档位或性能标准。复跑隔离包全卸载、reverse 为 0。脱敏因果和指纹在 `logs/s1-27-main-phone-sync-diagnosis.log`；派生报告 `logs/s1-27-main-two-device-functional-rerun.json`；私有最终原始证据留档在 `/home/xutao/.local/state/dstu-android/s1-27/2026-11-18-phone-sync-diagnosis-PNxhCo/device-final`。


## 2026-11-18T20:54:27+08:00 - S1.G 独立主线程验收（M1）

- 按冻结队列检查 27 个连续已签单元、S0.G 回执及历史证据，用户删除的历史 PNG 从提交对象审查，不恢复工作区。现场普通 APK `com.deepstudent.app` / 0.21.0 / arm64-v8a / SHA-256 `6b44c0f2d0c5a46f37ccf99f7a648f679602872e62fbf53e8b17e591057d2154`，两台设备读回安装包逐字节同哈希。该 APK 的生产源码与 `374e2a4` 一致，后续只改 androidTest、工具及证据。冻结桌面 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 未改。禁缓存复建的另一份 debug APK 部分 DEX 字节不同，只作源码级工程复验，不说它是现场同字节候选。
- 九类纵切：数据（真实 SQLite 笔记、附件与 force-stop 读回）；编辑器（真实 InputConnection、组合字符、笔记编辑恢复）；内容（公式/图表/卡面、WebView 浏览器及真实 PDF）；任务（两实际传输、Activity 退出、定向停止、checkpoint 与恢复）；工作台（手机逐个面板、实际 1238dp 平板横屏四格、dirty 取消、新 PID 读回）；算法（FSRS 调度训练、QuickJS/任务预算及账本）；终端/Shizuku（两台真实 PTY，平板普通 App 用既有授权跑只读 `id`、`pm list packages`，均 exit 0，后端和实际 UID 同为 0）；产品壳（两机实际 MainActivity 与亮暗证据）；同步（普通候选实跑双向明文和加密笔记及附件）。S1.21 Git/venv 冷装沿用已签原证据，本轮复测 PRoot 原生启动和入口，未再次冷装/下载。
- V2232A 同一普通候选 42 个非跳过 app 设备测试通过；另有 18 项 PDF 及 1 项 PRoot 模块设备测试、两个真实桌面往返夹具。初测 FGS 提交后尚未 `RUNNING`、手机误等宽屏、面板还未可见的失败不计通过。专属代理修复测试夹具，主线程独立重编 androidTest 并复测真实页签和外部 force-stop；未改生产代码。iPA2475 经用户授权原位更新，不卸载、不清数据，9 个 app 设备测试通过。未改 Sui/Shizuku 授权或服务设置，不以夹具冒充实服撤权。手机本轮包核对后卸载，平板保留新版普通 App，仅卸载测试包；双机 reverse 为 0。
- 同步使用生产 SQLite/Keystore/压缩/DSBK 和冻结桌面 WebDAV。明文 edit/delete/restore/repeat；加密另验错密不推进安全游标、无远端写、保留原密文并用正确密码按同序列重试及 Android 建仓。fake UI 仅用于页面逻辑，不替换真实业务证据。浏览器/SSE 使用公开本机语料。视觉复核 S1.12 桌面基线及本轮实际手机产品页、平板四格和实际执行页；不声称像素一致或完整 OEM/IME 矩阵，历史 `390dp` 图不计入本轮手机实机证据。
- S0/M0 只读回放、535 冻结输入、Gradle 禁缓存完整工程、18 构建逻辑、634 唯一 JVM（含纯 Kotlin 用例）、73 模块 / 162 边无环、架构源码和 528 个复杂度文件无违规（原 5 条 terminal vendor 例外）、22 阶段回执单测及相邻合计 90 个 Node 通过。实机断言 main/engine/isolated 不同 PID、isolated UID、非主进程不装配业务存储/同步/安全，Activity 重建复用主 graph。证据见 `logs/s1-g-main-host.log`、`logs/s1-g-main-architecture.json`、`logs/s1-g-main-complexity.json`、`logs/s1-g-main-phone.log`、`logs/s1-g-main-tablet.log`、APK 验证清单和视觉图。
- 排期：S1.01–S1.27 回执跨度 2 天 13:41:33 为墙钟而非人日。扣除 S1 单元与出口后冻结参考量为 1290.5 工作日，不按并发数或墙钟速率相除。只滚动校准 S2.01–S2.03 为 8–10 工程人日、设备可用性额外预留 0.5–1 工作日，S2.03 后再估；见 `logs/s1-g-main-schedule.json`。不改冻结发布合同或填补缺测 TTID/TTFD。
- 结果：**S1.G / M1 accepted**；`accepted27 / next=S2.01 / s1GateAccepted=true`。这是 S1 功能/工程出口，不是正式发布签收，未开始 S2；进入下一阶段时再切换交接。本轮专属子智能体已关闭，主线程负责测试、签收和本地提交，不推送。

- 收尾：隔离暂存树最终 114/114 Node（含真实 APK 拒装负例）零跳过，签收 CLI 返回 27/S2.01/true；测试包和 reverse 均已核对清理，平板保留经授权的普通候选。源码/最终证据以外的本轮编译产物已清理，见 `logs/s1-g-main-cleanup.log`。
