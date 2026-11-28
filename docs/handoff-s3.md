# S3 交接：对话、模型与 Agent

## 2026-11-25T16:44:36+08:00 - S3 开工基线（尚未开工）

- **当前状态**：S0.G / M0、S1.G / M1 与 S2.G / M2 已签收；S2.01–S2.31 均为 `accepted`。S2.G 的签收时间为 `2026-11-25T16:24:59+08:00`，以 [S2 回执](../config/stages/s2-progress.json)和 [阶段证据索引](evidence/logs-index-s2-g-20261125.json)为准。本轮只治理文档和整理工作区，未实施、测试或签收 S3 单元。
- **下一单元**：`S3.01` 定义聊天模型、仓储与 `TurnSnapshot`。按 [活动计划登记](../config/planning/active-plan.json)查询当前卡：`node tools/planning/verify-plan.mjs --query S3.01`。原计划明示前置为 `S1.03`、`S2.02`、`S2.30`；当前串行队列另继承 `S2.31` 和 `S2.G`。
- **S3.01 交付与验收入口**：映射 Session / Message / Block / Variant，复用 S1 的最小聊天实现，交付 Kotlin 仓储与不可变回合快照。用桌面样本逐字段核对持久化和重启读回、保留未知字段、同输入快照稳定，正式流管线使用同一仓储。以[功能计划 S3](plans/2026-11-15-mobile-full-feature-refactor.md#s3-对话模型与agent29个单元)和[模块化执行计划](plans/2026-11-16-native-modular-execution-plan.md)的单元定义为准，不在本轮预填开发或通过记录。

## S3 开工检查

1. 按 [开工阅读清单](../AGENTS.md)执行唯一首读及前置核验；集成和签收仍遵守 [S2 的核验教训](archive/handoff-s2.md#s2-verification-lessons)。
2. 核对 [来源锁](../config/source-lock.json)和 [共享 schema 最终锁](../config/schema/final-lock.json)。正式开工时，主线程与 S3.01 专属子智能体各自阅读两侧冻结源码的相关实现、调用链和测试，再在本页追加具体路径、合同、复用方式与平台差异；本页不替代源码学习。
3. 把 S2 的[后续验收项与环境状态](archive/handoff-s2.md#s2-follow-up-acceptance)留在适用阶段，不视为已经验证，也不另加 S3.01 前置。S3 新发现的覆盖缺口记入[测试局限](test-limitations.md)，正式结果按准确时间追加到本页，原始证据归档到仓库外。

## 2026-11-25T21:41:05+08:00 — S3.01 开工核对

- 有效卡片为 `S3.01/implementation`，历史映射同名。`S1.03`、`S2.02`、`S2.30`、`S2.31`、`S2.G` 均已签收，当前本地 main 基线为 `1741409a7143`。用户授权本轮仅本机实测。
- 主线程核对 dstu-node、Operit 和 terminal 的 HEAD 与 tree 均匹配来源锁。阅读桌面 `contracts/ipc/generated/L04-chat-session.ts`、`resources/app/migrations/chat_v2/`、`host/plugins/chat/repository/{sessions,messages,blocks,load-session}.ts`、`host/plugins/chat/tests/message-persistence.test.ts` 及 `host/plugins/chat-pipeline/{src/freeze-execution.ts,src/execution-snapshot.ts,src/final-persistence.ts,tests/execution-snapshot.test.ts}`。Session、Message、Block 直接对应冻结三张表，Variant 由消息的 `variants_json` 承载，块的索引和归属独立持久化。执行输入在流前冻结，生成结束经同一聊天仓储在事务中保存。Kotlin 沿用共享库和字段，保留 JSON 扩展键与数据库治理列。`TurnSnapshot` 是持有独立值的回合读视图，桌面已存的 execution/context 数据仍留在原 JSON 位置。
- 阅读 Operit `data/{model/ChatEntity,model/MessageEntity,model/MessageVariantEntity,dao/MessageVariantDao,repository/ChatHistoryManager}.kt`、`services/core/{ChatHistoryDelegate,MessageProcessingDelegate}.kt` 和 `data/model/MessageVariantEntityTest.kt`。参考把持久化记录、运行读取和流状态交给各自所有者的做法。Operit 用独立 Room 表和消息时间定位变体，桌面的 ID 与内联 JSON 格式由 dstu 自己的合同定义。
- 写入范围为 chat API/backend/data、app 的存储与 S1 流式装配，以及单元测试、交接和证据。公开 Kotlin 端口加载一个一致的会话读视图并原子写入会话、消息与块，数据库适配负责事务与调度，任务运行时继续拥有取消和进度。计划验证桌面样本逐字段往返、重新打开和快照隔离、未知 JSON 字段保留、共享库写入与旧流入口。此切片归属聊天领域合同，页面保持现有入口。

## 2026-11-25T22:09:42+08:00 — S3.01 实现与本机核验

- S3.01 专属实现者独立核对三处来源锁，于 `2026-11-25T21:59:43+08:00` 再核对桌面 `host/plugins/chat/repository/{sessions,messages,blocks,load-session}.ts`、`host/plugins/chat-pipeline/src/{execute,freeze-execution,final-persistence,execution-snapshot}.ts` 与持久化用例，以及 Operit 的 `ChatHistoryManager.kt`、`ChatHistoryDelegate.kt`、`MessageVariantEntity.kt`、`MessageVariantDao.kt` 和变体测试。以桌面消息时间与 rowid、首块时间与块索引的排序为合同，借鉴 Operit 的运行只读入口和存储所有权。
- Kotlin 公开合同映射冻结三张表的 17 个会话列、17 个消息列和 23 个内容块列。Session 属性、Message 关联及载荷、Block 内容及时序分组。Variant 使用原位 JSON 和类型化读取，透明保留扩展键、数字精度与显式 JSON null。同步、回放与压缩列可读且继续由各自所有者维护。写合同以 `clear` 标记 SQL NULL，以 `JsonValue.Null` 写入 JSON null。SQLite 单快照读取、事务写入、内容修改使旧回放文本失效，相同写入保持已有变更日志。
- `TurnSnapshot` 对会话输入持有独立的不可变值。同一份输入可重复构造相同快照，`LoadTurnSnapshot` 直接读取聊天仓储。主线 `MainStorageResources` 用活动数据槽的 `chat_v2` 共享租约提供该仓储，现有 S1 流入相同端口，每个正常结束的单次请求保存一组会话、用户消息和助手消息。任务运行时继续记录进度与取消状态。仓储日志记录阶段、耗时、状态和操作关联号。
- 本机 `qualityGate`、聊天 JVM 14 项、App JVM 140 项、仓库 105 项、冻结输入 286 项，以及冻结桌面 Vitest 14 项通过。App Kotlin 与仪器测试代码编译通过。App JVM 套件的 3 项旧桌面备份夹具用例按原条件跳过，详情见测试摘要。
- API 36 x86_64 模拟器的聊天数据仪器套件 6 项通过。另执行流写入、`force-stop` 后新进程读回、冻结桌面库导入修改 3 次独立验证。桌面生产仓储写出的 SQLite 样本经 Android 修改标题后可由冻结桌面仓储读回，保持 9 位时间小数、Unicode、变体关联、未知值、精确 int64 和 SQL／JSON null 表达。首轮夹具查询引用错误表名，改用 `__change_log` 后单独复验。首次复杂度门禁发现三个模型参数超限，字段分组后完整门禁和仪器套件复跑通过。

## 2026-11-25T22:11:47+08:00 — S3.01 本机签收

- 主审签收 `S3.01/implementation`。实施提交为 `190daa2b6a5591edda5e39b0664b267887e23640`。[S3 回执](../config/stages/s3-progress.json)登记本机验收结果，下一单元为 `S3.02`，S3.G 状态为待验收。
- [本机证据索引](evidence/logs-index-s3-01-local-20261125.json)收录并逐项复验 22 条证据，归档 SHA-256 为 `71dc7f8bacc7a633315d9b352d51e6a3aa747eceb97ac6a708e89cbfb7031b91`。索引包含本机实测命令、桌面互操作脚本、模拟器测试包摘要与结果。[测试范围](test-limitations.md)登记整包与跨设备验收入口。

## 2026-11-25T23:46:00+08:00 — S3.02 开工

- 按现行卡核对 S3.01、S1.07 与 S2.G 的签收。冻结桌面阅读 `host/plugins/llm/src/{protocol,config_resolution,adapters/registry,data/provider_protocol_registry}.ts`、`tests/{llm_manager_protocol,config_decode}.test.ts` 及 `host/plugins/llm-providers/src/providers/openai-chat.ts`。供应商允许协议、默认协议与模型例外分开处理，vendor/profile 合成运行配置，私密信息独立取用。以冻结队列 S3.04–S3.08 的供应商成员作为逐项核验输入。
- Operit 阅读 `api/chat/{EnhancedAIService,llmprovider/AIService,llmprovider/AIServiceFactory,llmprovider/ModelConfigConnectionTester}.kt`、`data/model/ModelConfigData.kt`、`OpenAIProviderContentFieldTest.kt`。参考按配置构造协议调用及显式上报用量的方法。terminal 的 `src/main` 搜索命中的是终端文件系统和 SSH provider。三个只读来源提交与锁一致。
- 本卡归属 providers API 与配置解析。合同包括多协议注册表、vendor/profile 模型和参数、凭据引用、预检和流式调用端口。解析失败在定位适配器和发请求前以固定错误返回。使用本机测试逐项校验协议和非法配置，回合入库由 S3.03 接入。

## 2026-11-25T23:49:29+08:00 — S3.03 隔离准备

- S3.03 的正式接入等待 S3.02 回执。主审阅读桌面 `host/plugins/chat-pipeline/src/{execute,incremental-persistence,final-persistence,persistence-port,token-usage}.ts` 和持久化测试、`host/plugins/llm/src/{model2_streaming_service,model2_stream_events,usage}.ts`。用户消息在流前持久化，助手块分段保存，完成后的副作用以已提交事实为准。`completion_outbox` 由桌面子代理信封专用，单轮聊天的运行时回执采用独立的 Android 私有账本，参照现有笔记任务的 `__android_*` 表约定。共享块和用量写入冻结表，以真实会话、流作用域和 run ID 分列关联。
- Operit 阅读 `services/core/MessageProcessingDelegate.kt` 的取消、流结束和部分回复保留分支、`data/repository/ChatHistoryManager.kt` 与 `MessageProcessingDelegateTest.kt`。参考应用任务拥有流和取消，以及把部分回复和已完成回复分别落库。terminal 的 `src/main` 未见模型流、聊天保存或用量账本，最接近的是负责终端会话生命周期的 `SessionManager.kt`。
- S3.03 写入范围为 chat API/backend/data、单轮网络适配、App 运行时装配、本机验证和交接。复用同一个聊天仓储，块状态和完成事实同事务提交，运行时据 outbox 对账，用量按稳定身份幂等登记。沿用现有发送入口，由应用级任务统一负责取消和启动恢复。

## 2026-11-26T00:51:48+08:00 — S3.02 本机签收

- `providers` 按冻结表登记 23 个供应商的协议。供应商配置、模型档案、能力、参数和凭据引用通过纯 Kotlin 端口解析。预检固定返回错误码，认证材料在选出合法适配器后读取。测试覆盖官方与中转 OpenAI、DeepSeek 型号门控、OpenCode Go 路由、Codex OAuth 及非法配置下零访问。
- 主审独立复验 API 5 项、后端 36 项、桌面 33 项、冻结输入 286 项。架构及 provider 覆盖率门禁通过。独立脚本对照 S0 队列与冻结桌面注册表，23 项默认协议、许可协议及 Responses 声明一致。整库质量门禁随 S3.03 装配复验。
- 实施提交 `58244e515dca760cd0049c274147f1a4d57d7b7c`。本机[证据索引](evidence/logs-index-s3-02-local-20261126.json)已复验，归档 SHA-256 为 `471993c9288723a471779939b6f87a93983540a1fbb15cca4e2640bd15fd001e`。回执进入 S3.03。

## 2026-11-26T01:43:46+08:00 — S3.03 本机签收

- S3.02 签收后接入 `chat.turn_stream`。两个专属实现者独立核对冻结版本及桌面 `chat-pipeline` 的增量与最终保存、`llm-providers/src/providers/openai-chat.ts`、相应测试和共享迁移；Operit 重点对照 `MessageProcessingDelegate.kt`、`OpenAIProvider.kt` 及用量、取消测试。保留 S1 直连 SSE 的端点和请求格式；现有发送面板可选填模型，走经过 S3.02 配置预检的最小 Chat Completions 文本流。Provider 不接触数据库，也不持有任务所有权。
- `SqlChatRepository` 以同一活动数据槽租约提供会话读取与单轮增量写入。发送时先保存用户消息，回复分块提交后再更新运行时进度，最终块状态与 Android 私有完成回执同事务落盘。取消、断流和进程退出后均可读回已保存文本。共享表和桌面子代理 `completion_outbox` 不变；已报告的计数按任务身份幂等写入 `llm_usage_logs`，缺失且不能精确推出的值只保存在私有回执，不填估算或零。用量库暂时失败时保留可重放记录。
- 最终候选通过 `qualityGate`、仓库 105 项、冻结输入 286 项及冻结桌面 19 项。JVM：聊天 37 项、provider 传输 11 项、运行时 82 项、网络 23 项；App 141 项通过，3 项既有桌面备份夹具按运行条件跳过。API 36 x86_64 模拟器同一隔离候选通过 6 项：真实本机 HTTP 正常完成与用量查询、断流保留部分回复、取消并确认对端断开、缺用量字段仍正常完成、旧 SSE 格式、流中 `force-stop` 后由新进程读回。另有 2 项既有聊天仪器回归通过。首次取消测试的限速夹具未及时送出首帧，换成明确刷出首帧并保持连接的本机 HTTP 夹具后复跑；首次失败不计通过。
- 实施提交 `2dcc93546d4426312ab1f27740c0ee40b0efb2a0`。本机[证据索引](evidence/logs-index-s3-03-local-20261126.json)的 21 条证据逐项复验，归档 SHA-256 为 `c806a6cbaeb916d2518484384b3654211eb2a232878f0c1633fed2abfe21a977`。下一单元 `S3.04`，S3.G 尚未签收。未使用真机、未发布、未推送。后续开发在当前对话和专属子智能体内进行，见项目规则第 17 条。

## 2026-11-26T02:47:13+08:00 - S3.01–S3.03 平板补验签收

- **范围和候选**：用户授权本轮只验证 USB 平板 iPA2475（Android 16 / API 36、ARM64）。主线程复核三处来源锁和 286 个冻结输入，并确认相对 `c358c7b` 的候选代码差异仅在仪器测试。S3.01 独立 APK 以 `8fa7567` 构建；S3.02 和 S3.03 使用 `3f5a98f` 的同一组 0.21.0 隔离 App 与测试包。三份 APK 安装后逐一校验内容摘要，S3.02/S3.03 的测试随普通应用 UID 执行，不申请 Root 权限。
- **S3.01：6 个常规用例及 3 个独立阶段通过。** 真实 SQLite 验证记录及扩展字段、事务回滚、取消和一致读视图。本机回环 SSE 结束后，另一 PID 重新打开数据库读回中文与 emoji。用冻结桌面仓储生成 Session/Message/Block/Variant 样本，在平板验证同一输入的 `TurnSnapshot` 相等、写新标题前的旧快照不变，再由冻结桌面仓储读回；未知 JSON 键、`null`、纳秒时间和 64 位整数精度保留。首轮与最终包重叠的通过项不重复计数。
- **S3.02：4 个用例通过。** 真实解析器在平板逐一验证冻结目录的 23 个供应商默认协议、路由和认证模式，并复查 OpenAI、Qwen、Minimax 切换、OpenCode Go 三类根路径、DeepSeek 型号限制及代理显式协议。8 类非法配置以固定错误拒绝，适配器查找次数、凭据读取次数和平板回环测试端点请求数均为 0。专属子智能体核对桌面 `scripts/provider-protocol-registry.json`、`host/plugins/llm/src/protocol.ts`、`host/plugins/llm-providers/src/providers/openai-chat.ts` 和相关测试，以及 Operit 的 `OpenAIProvider.kt`、`OpenCodeProvider.kt`、`ChatConfigReadiness.kt`、`EndpointCompleter.kt`；供应商登记以冻结桌面为准，Operit 仅用于对照 Android 接入边界。
- **S3.03：6 个专项用例及 2 个旧聊天回归通过。** 隔离 App 对平板回环 HTTP 真实发请求，核对完成与幂等用量、断流、取消且对端断开、缺用量不估算、旧 SSE 入口。助手首段持久化且任务仍为运行态时，核实正在运行的 PID，再实际 `force-stop`；新 PID 读回已保存的首段，任务为中断、块为错误、没有业务完成回执。被强制停止的写入侧返回进程终止是测试注入，不单独计为通过；通过回执来自恢复侧。按隔离 UID 和时间窗录得 59 条结构化事件，完成、断流失败和用户取消均与同一任务的运行时收尾对应。
- **结果与证据**：平板共 21 个通过回执，无跳过。`qualityGate`、仓库 105 项和聊天/供应商定向 JVM 套件复验通过。[平板证据索引](evidence/logs-index-s3-01-03-ipa2475-20261126.json) 45 项已逐项复算，归档 SHA-256 为 `7b323b76a95620259e093f14eabc1b0bcbaefda9a6725e2176a8bbba7c46cbbf`。隔离包已卸载；未安装正式 App、未改正式 App 状态或 ADB 转发。保留原本机签收，[S3 回执](../config/stages/s3-progress.json)补记平板结果。未验证手机、未推送、未开启 S3.04；下一单元仍为 S3.04，S3.G 不变。

## 2026-11-26T03:05:39+08:00 - S3.01–S3.03 手机复验及双真机最终签收

- **同一候选**：V2232A 手机（Android 16 / API 36、ARM64，ADB shell UID `2000`）安装平板原封存的三份 APK，不重新构建。S3.01 候选 `8fa7567`、S3.02/S3.03 候选 `3f5a98f`；手机逐包安装读回与平板相同。三次 `继续安装` 均在校验 APK 身份及系统安装窗口后自动确认，未改变安全设置，不需要 Root 或 Shizuku 授权。
- **S3.01：手机 9/9。** 6 项常规用例及 3 个独立阶段完整通过。用平板已使用的同一冻结桌面 SQLite 样本，核对重复快照一致、保存后旧快照不变、未知 JSON 字段及 `null` 保留。另有真实回环 SSE 完成、停止后由不同进程读回，以及桌面读回手机改写的会话和精确 64 位整数。桌面读回的结构化结果与平板一致。
- **S3.02：手机 4/4。** 23 个冻结供应商的默认协议、路由及认证模式逐项通过，8 类非法配置在适配器查找、凭据读取和网络请求前返回对应错误；协议切换、代理和模型门控与平板用例一致。
- **S3.03：手机 6/6 专项及 2/2 旧聊天回归。** 实际 HTTP/SSE 跑通完成、断流、取消并断开对端、缺失用量、用量幂等和旧入口。在首段写入后强制停止，独立新进程读回部分回复，任务是中断、块状态为错误，没有生成业务完成回执。59 条隔离 UID 的结构化事件关联完成、失败、取消及中断恢复；主动强制停止的写入侧不计单独通过。
- **最终签收**：iPA2475 与 V2232A 各 21 项，共 42 项通过，0 失败、0 跳过。主线程逐个核对两台的用例、阶段和安装摘要；用例矩阵 SHA-256 `88cb908f967590c6263da7d45f8a703961e2baa3005487937c8f46dc55b95fc8`。手机[证据索引](evidence/logs-index-s3-01-03-v2232a-20261126.json) 44 项与平板[证据索引](evidence/logs-index-s3-01-03-ipa2475-20261126.json) 45 项已复算，手机归档 SHA-256 `53ae63103ec8ad02df63bbdcf1e69f40a072fba5260a56e34330dde7e8e13f90`。本轮复验 105 项仓库测试及 286 个冻结输入；沿用同封存候选的 `qualityGate` 和 JVM 证据。三份手机隔离包已卸载，正式 App 及 ADB 转发状态不变。未修改生产代码、发布或推送。仅签收 S3.01–S3.03，下一单元仍为 S3.04，S3.G 未签收。

## 2026-11-27T13:48:39+08:00 — S3.04 开工核查

- **前置与范围**：`S3.01`–`S3.03` 和 `S2.G` 均为已签收；`s3-progress.json` 的 `next` 为 `S3.04`。`verify-plan.mjs --query S3.04` 展开 10 个 provider（siliconflow、qwen、zhipu、doubao、minimax、moonshot、nvidia、mimo、ernie、mistral），每家保留 `request`、`stream`、`error-cancel` 三张卡，共用 setup 之外不合并逐家验收。
- **冻结源校验**：`dstu-node` HEAD/tree 为 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` / `3bee0f058851b9eb113bdc036d9f60433ed29944`；`Operit` 为 `7a39941bad80209973c535046a18195c5da9e647` / `1c74219f7b7fdebebd07e99830877559e2dcd3e6`；`Operit/terminal` 为 `e4646680663789002b43bbfffa6e69e65c35e614` / `bb5420c2fd21040878c25240607996aed6afbd77`。三仓工作区均无未提交更改。
- **冻结桌面路径与合同**：`dstu-node/scripts/provider-protocol-registry.json` 是 provider/协议目录；`host/plugins/llm-providers/src/providers/openai-chat.ts` 与 `tests/openai-chat.test.ts` 覆盖 Chat Completions 路由、Bearer 认证、工具声明清理、文本/思考/工具增量、usage 和 `[DONE]` 结束。`finish_reason` 不代替传输结束；官方 OpenAI 主机的 usage 参数按精确主机判断，不能泛化到所有兼容端点。
- **Operit 对照路径与差异**：读取 `OpenAIProvider.kt`、`AIServiceFactory.kt`、`ChatConfigReadiness.kt`、`EndpointCompleter.kt`、`ThinkingQualityMapping.kt`，以及 `QwenAIProvider.kt`、`DoubaoAIProvider.kt`、`KimiProvider.kt`、`NvidiaAIProvider.kt`、`MimoProvider.kt`、`MistralProvider.kt`。其中 OpenAIProvider 负责通用请求/工具/usage/流处理；Qwen、Doubao、Kimi、NVIDIA 的覆盖实现展示了各自思考参数差异；部分其他提供方由工厂路由到通用实现。它是行为参考，不能照搬其 Android Context/单体 Provider 架构。
- **本仓库接入面与实现边界**：`services/providers/api` 已有四类协议、运行时生成参数与思考配置、工具定义和安全凭据引用；当前 `OpenAiChatRequest` 与端口只实现 S3.03 最小文本单轮。S3.04 需按 providerId 明确请求映射与响应事件，并通过隔离网络记录逐家比对；不得把所有协议塞进一个大分支，也不得改变 S3.03 的取消、固定错误和敏感字段脱敏合同。S3.04 完成并签收前不接入 S3.05；其后按依赖顺序推进 S3.05–S3.08。
- **本轮验证边界**：只做本机 JVM、冻结夹具和本机回环网络验证；不做手机/平板真机测试，不使用线上供应商凭据，不推送或发布。验收将覆盖每家录制请求/响应、4xx、限流、取消及工具/思考/usage映射。

## 2026-11-27T15:42:21+08:00 — S3.04 续作范围与对拍口径

- 用户将本轮限定为 S3.04：仅做这十家供应商的 Chat Completions，不接入 S3.05–S3.08、不打包或安装到设备、不推送。保留本地 main 上既有 S3.01–S3.03 双机回执，不另起 Git 分支、worktree 或开发对话。
- 复核冻结桌面 `host/plugins/llm/src/{model2_request,model2_messages,protocol,question_authoring,generation,provider_quirks,reasoning_policy}.ts`、`src/adapters/` 内各家请求策略，以及 `tests/{adapters,qwen,doubao,moonshot,zhipu,minimax,ernie,mistral,provider_quirks,model2_pipeline_generation}.test.ts`。请求选路与各家参数先由桌面 LLM 层确定，再由 `llm-providers/src/providers/openai-chat.ts` 清理请求和解码响应。仅对已经编译好的消息进行协议对拍，系统提示构造及上下文策略仍归 S3.12；协议内工具调用的声明、流片段和历史不是外部工具的执行授权。
- Operit 对照其 `api/chat/llmprovider/{OpenAIProvider,AIServiceFactory,ThinkingQualityMapping,QwenAIProvider,DoubaoAIProvider,KimiProvider,NvidiaAIProvider,MimoProvider,MistralProvider}.kt` 的请求入口、usage 读取、工具流和 HTTP 取消。没有独立类的 SiliconFlow、智谱、MiniMax、百度走工厂中的共用实现；不照搬 Operit 对 NVIDIA 的额外推理白名单、MiMo 的额外认证头或聊天标记格式；供应商参数以本项目冻结桌面链为准。
- 主线程从冻结实现生成各家按原样比较的合成请求和 SSE 事件序列；安卓测试先校验实际解析出的协议、适配器 ID 和目标 URL，再仅把传输目标替换为本机回环，验证实际 HTTP body/headers、流事件顺序与关闭连接。没有线上供应商凭据或真实用户内容。

## 2026-11-27T17:22:32+08:00 — S3.04 Chat Completions 本机签收

- **实现**：`cc7b10254a15932b6a276a1ffc0be8c7f8a863cb`。应用组合层注册 SiliconFlow、Qwen、智谱、Doubao、MiniMax、Moonshot、NVIDIA、MiMo、ERNIE、Mistral 的 Chat Completions 路由。按冻结桌面将认证与路由、各家请求参数、HTTP 帧和流事件分开处理；工具只编码声明、回放历史及输出增量，不执行。未改共享数据库结构或同步协议。
- **供应商差异**：保留各家思考/采样与 token 上限写法，含 Qwen 工具结果续轮去掉 `tool_choice`、GLM 5.3 思考与档位归一、MiniMax 思考历史 `reasoning_details`、Kimi 的 MFJS 工具 schema 转换及 K3/K2.7 差异、MiMo 的采样与历史、Mistral 的文本/思考分段；SiliconFlow 的 DeepSeek V4 走 effort、V3.2 走预算，并按桌面的请求准备步骤使用 `max_tokens`。NVIDIA 使用冻结桌面的通用规则，不套用 Operit 专用白名单。额外参数的 JSON 字符串、布尔和 `null` 保留原类型。
- **逐家主审**：调用冻结桌面的请求参数、历史和最终请求准备函数生成预期，用冻结 wire adapter 生成流事件预期。走应用的实际配置解码、路由、凭据端口、协议端口和 OkHttp，只在连接前将已核对的目标改往本机回环。22 组完整请求、10 组流事件、10 家错误/取消回执均逐项比对。每家覆盖 HTTP 400/401/429、流内错误、预先取消和收首段后取消；服务端确认流内错误、正常 `[DONE]`、真实取消均主动断开，取消不发完成事件。请求诊断不含原始输入或凭据，凭据可擦除字节实际归零。
- **测试结果**：S3.04 应用回环矩阵 48 项通过、0 失败、0 跳过（22 请求、10 流、10 错误/取消、4 结束边界、2 旧入口回归）。全 App JVM 192 项无跳过；provider API/backend 5/36、provider data debug/release 各 29、network 23、chat API/backend 合计 20；仪器测试源码编译通过。`qualityGate`、仓库 105 项、冻结桌面 198 项、冻结输入 286 项及历史证据索引 249 条校验通过。覆盖 S3.04 的 30 张供应商操作卡和 1 张公共 setup 卡。
- **证据与边界**：[本机证据索引](evidence/logs-index-s3-04-local-20261127.json)含 58 项，归档 SHA-256 为 `762df0cef868c89a4eccc833bae760c902bca4108041099582e495b920eabaad`，已逐项复算。配套 [测试局限](test-limitations.md) 与 [S3 回执](../config/stages/s3-progress.json)已更新。本轮只签收 S3.04 的本机范围；未访问线上供应商、未安装/测试真机、未推送或发布。下一单元为 S3.05（尚未开工），S3.G 保持未签收。

## 2026-11-27T18:34:44+08:00 — S3.05 开工核查

- 前置 `S3.03`、`S3.04` 与 `S2.G` 的回执均已签收；本地 main 基线 `0a367cd`。现行卡为 `S3.05/common-setup` 和 OpenAI、OpenAI Codex、DeepSeek、Grok、xAI 各自的 `request`、`stream`、`error-cancel`，历史映射同名。仅做 S3.05，不启动 S3.06，不改现有工作区规则文件的用户改动。
- 主线程核对 dstu-node `e6b2657fd59014a73f5bbb1d14c2db732cf23e87`、Operit `7a39941bad80209973c535046a18195c5da9e647` 与 terminal `e4646680663789002b43bbfffa6e69e65c35e614` 的 commit/tree 与来源锁一致。桌面以 `host/plugins/llm-providers/src/providers/openai-responses.ts`、`host/plugins/llm/src/{provider_adapter,model2_messages,provider_quirks,codex_transport}.ts`、`llm-providers/src/openai-codex/{prepare,protocol,manager,store}.ts` 与 `llm-providers/tests/{openai-responses,codex-protocol,codex-manager}.test.ts` 为映射及状态依据：角色与输入 parts 编译为有序 items，回放原思考/搜索 item、函数调用和结果，使用显式完成边界；DeepSeek 审核路线在终态前隔离文本与工具，Codex 使用固定端点、账户头、代际感知的一次 401 刷新，并将取消和需重登保持为不同状态。
- Operit 对照 `app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/{OpenAIResponsesProvider,CodexProvider}.kt`、`data/api/{CodexAuthManager,CodexOAuthClient}.kt`、`ui/features/codex/{CodexOAuthCoordinator,CodexOAuthLoopbackCallbackServer}.kt` 和 `app/src/test/java/com/ai/assistance/operit/api/chat/llmprovider/{OpenAIResponsesPayloadAdapterTest,DeepseekResponsesPayloadAdapterTest,OpenAiToolCallHistoryTest}.kt`。复用 Android 原生传输、回环回调与受保护存储的分层方式；不复用 Operit 的 prompt cache 哈希、搜索展示标记或账户存储格式。
- 写入范围为 providers API/backend/data、主进程运行时/凭据装配、S3.05 合成对拍/回归及回执。协议端口持有 HTTP 取消，账户服务持有认证生命周期与凭据代际，既有聊天回合持有文本和用量持久化；协议事件不是工具执行授权。若无新页面，交付可调用的认证状态/操作端口，正式模型设置 UI 仍归后续单元。本轮只在本机运行冻结样本、合成 OAuth 和回环网络，不登录真实账户、不打包、不装机、不推送。

## 2026-11-27T18:53:50+08:00 — S3.05 源码复核与适配边界

- 认证实现者独立核对冻结树，追到桌面 `openai-codex/{manager,manager-values,protocol,prepare,store,types,error,callback-server}.ts`、`codex-{auth-service,commands,integer-width,manager,protocol}.test.ts`、集成 `llm-codex-connection.test.ts` 及设置 `OpenAICodexAccountSection.tsx` / `openaiCodexAuthClient.ts`。Operit 阅读 `CodexOAuthClient`、`CodexAuthManager`、`CodexAuthPreferences` 与 `CodexOAuth{Coordinator,LoopbackCallbackServer}`、`CodexLoginDialog`、`CodexOAuthTokenResponseTest`。Android 使用现有加密凭据库和本机回调的独立所有权，凭据存取、PKCE/设备码、一次刷新和流传输分离。
- 流实现者独立核对同一冻结树；重点阅读桌面 `openai-responses.ts`、`openai-responses.test.ts`、`openai-codex/protocol.ts`、`codex-{protocol,manager}.test.ts` 与 `llm/src/stream_validation.ts`，对照 Operit 的 `OpenAIResponsesProvider.kt`、`CodexProvider.kt` 及 `OpenAIResponsesPayloadAdapterTest`、`DeepseekResponsesPayloadAdapterTest`、`OpenAiToolCallHistoryTest`。语义以桌面为准：保留有序输出、区分 tools 增量/最终快照、DeepSeek 审核中仅保留非正文的进度；截断或失败不能作为聊天完成。检索 terminal 的 `src/main` 无对应协议/认证实现，不采用其命令或进程层。
- 主线程增加桌面 `reasoning_state_scope.ts` 和 `replay.ts` 对照：来自正式流的私有回放项绑定 provider / 端点摘要 / 模型 / 协议；改变目标后不发送该私有项，仍保留普通聊天正文及可回放的非私有历史。尚无页面依赖该底层协议，未接工具执行。

## 2026-11-28T01:44:27+08:00 — S3.05 Responses 本机验证（未提交工作区）

- **请求与流**：应用组合层接入 OpenAI、OpenAI Codex、DeepSeek、Grok、xAI 的 Responses 路由。保留输入角色/parts、工具调用和结果的顺序，以及同目标的思考与搜索历史；纯文本请求不带工具选择。流区分工具参数增量和最终快照，使用显式完成事件收尾，并保留宽整型用量；断流、错误和取消不发成功完成。DeepSeek 受审核路线在批准的终态前不交付正文或工具调用，截断也不例外。
- **账号与凭据**：Codex 账号凭据只发往固定端点；浏览器 PKCE/回环回调、设备码、取消及过期、代际感知的一次 401 刷新和失效后重新登录都经合成用例检查。用现有加密凭据库验证存取、重开和清除，以可调用的账号状态/操作端口交付，不增加设置页面或工具执行入口。
- **主线程复验**：冻结工作树 oracle 的 11 组完整请求、5 家流序列和 5 家错误/取消回执均通过逐项比较；应用接线、路由与门禁一并覆盖该单元的 16 张卡。App JVM 230 项（含 S3.05 38 项、S3.04 48 项）、provider API/backend 5/38 项、provider data debug/release 各 63 项、network 24 项、chat API/backend/data 6/14/17 项，均为 0 失败、0 跳过。App 仪器测试源码编译通过；`qualityGate`、仓库 105 项、冻结桌面 213 项、286 项冻结输入及 249 条历史证据校验通过。
- **证据及推进**：[本机证据索引](evidence/logs-index-s3-05-worktree-20261128.json)的 33 项说明、摘要和回环录件已校验，归档 SHA-256 `b95872d82bf55aadb43840d72c9507bf8003f21dd086fd69984369629306fb09`。基线为本地 `main` 的 `0a367cde96a54baae24af9e40d04113289d2aa0`；归档的 `source-files.sha256` 记录实际被测工作区的 67 个源码与夹具，不把该基线当作 S3.05 实现提交。见[测试边界](test-limitations.md)。本轮未打包、装机、使用真实账号或访问线上供应商，也未提交、推送或推进正式回执；下一待签收单元仍为 S3.05，未启动 S3.06，S3.G 不变。

## 2026-11-28T03:00:50+08:00 — S3.06–S3.08 开工边界与 Google 源码核查

- 按现行计划展开 S3.06（4 张）、S3.07（7 张）、S3.08（16 张）共 27 张卡。S3.08 的 `common-setup` 另关联 `navigation.views:llm-playground`。主审复算 S3.05 的 33 项归档、67 个源码/夹具哈希并重跑 16 张本机回放卡；结果一致，但 [正式回执](../config/stages/s3-progress.json) 仍指向 S3.05。按 S3.05 → S3.06 → S3.07 → S3.08 的顺序接入，本轮只在本地 main 开发独立、未注册的候选和测试，不修改既有正式路由、共享数据 schema 或推进签收。
- 三个只读参考树经 `config/source-lock.json` 校验。主审与 Google 专属核查者读取桌面 `host/plugins/llm/src/adapters/gemini.ts`、`host/plugins/llm-providers/src/{providers/gemini,adapters/gemini-openai-converter,adapters/gemini-openai-response}.ts` 与 `tests/{gemini-adapter,gemini-converter}.test.ts`，确认 `google` 和 `gemini` 共用协议，版本前缀/思考档位控制请求版本，工具参数为完整对象，签名只在相同目标回放，非 STOP 的失败终态不能标为完成。对照 Operit 的 `GeminiProvider.kt` 和 `GeminiThinkingConfigTest.kt`，借鉴 Kotlin 思考配置及取消的独立测试，不采用其固定版本 URL、内联 XML 或自动接入搜索。候选单独持有协议签名载体与固定脱敏错误，使用冻结样本和本机回环验证。没有候选接入主应用、打包、装机或使用真实凭据。

## 2026-11-28T03:01:41+08:00 — S3.08 冻结端点核查

- 主审与自定义端点专属核查者复核桌面 `host/plugins/llm/src/{protocol,prepared_request,vendor_models,opencode_go,model2_transport,connection_test}.ts`、`adapters/generic_openai.ts`、`tests/{vendor_models,opencode_go}.test.ts`、平台 `http/{proxy,system-proxy}.ts`，对照 Operit `OpenCodeProvider.kt`、`ModelListFetcher.kt`、`EndpointCompleter.kt`、`OpenRouterProvider.kt` 与 `OllamaProvider.kt`。以冻结目录的模型协议为准，不按模型名前缀猜路由。OpenCode Go 目录直接读取内置 13 项，身份头使用净化后的会话标识；OpenRouter 的 `reasoning` 与 `reasoning_details` 保持其方言；Ollama 走 OpenAI 兼容 SSE，不另造原生 NDJSON 协议。
- 用户配置的 API 中转由端点及显式协议决定，系统网络代理继续由传输层持有。候选提供受校验的自定义头、模型列表分页、取消与固定脱敏诊断，复用已有解析器和流传输，不改共享路由或放宽现有明文校验。Playground 保留 `debug/models`、调试构建、正式 Shell 宿主的导航验收；本轮没有在主应用挂入口或运行真机导航。

## 2026-11-28T03:02:23+08:00 — S3.06 冻结 Anthropic 核查

- 主审与专属核查者读取桌面 `host/plugins/llm-providers/src/providers/anthropic.ts`、`tests/anthropic.test.ts`、`host/plugins/llm/src/adapters/anthropic.ts` 与 `reasoning_state_scope.ts`，并对照 Operit `ClaudeProvider.kt`、`ClaudeProviderCancellationTest.kt` 和端点补全。请求保留角色顺序、`tool_result`、同一目标内原样思考块/签名及最多四个缓存断点；Claude 代际决定预算或 adaptive effort。验证补齐裸根 URL 的 `/v1/messages`、缓存用量累计、明确完成信号及截断工具参数。Operit 的 Kotlin 连接生命周期可参考，不复用其内联 XML 工具标记或兼容回退。候选不发可用的截断工具参数、不持有工具执行端口。
- 更正 01:44 交接中的基线长 SHA 录入笔误：`git rev-parse HEAD` 为 `0a367cde96a54baae24af9ae40d04113289d2aa0`。该值只表示基线，不是 S3.05 或后续单元的实施提交。

## 2026-11-28T04:41:21+08:00 — S3.06–S3.08 隔离候选本机复验

- **S3.06 Anthropic**：独立工厂、请求转换及 SSE 解码保留 `tool_result`、四个缓存断点、同目标的思考签名、缓存用量和停止原因。10 组冻结请求、7 组带成功或失败终态的录制流逐项比对；HTTP 错误、首段后取消、思考回放与换模型剥离有实际回环检查。畸形或未闭合的工具块没有完整调用事件，`message_stop` 和 `[DONE]` 不会把未闭合工具块变为完成。App 专项 21 项通过。
- **S3.07 Google / Gemini**：两个成员使用同一适配实现，处理版本选择、图文 parts、函数请求/回传、思考签名、安全拦截、空响应和用量。17 组实际回环请求和 9 组成功流事件录件已与冻结预期复算；另验证 3 组不成功终态、跨目标签名清洗，以及两成员的 HTTP 错误和真实 socket 取消。App 专项 36 项通过。
- **S3.08 自定义 / 本地 / 中转**：`custom`、`general`、`opencode_go`、`openrouter`、`ollama` 的请求、SSE、错误/取消 15 张供应商操作卡经 27 份录件复算；另验证显式协议不降级、一个隔离 Responses 路径、模型列表真实回环分页、OpenCode Go 内置 13 模型零网络。提供自定义头和固定错误诊断，代理 407 不回显远端正文，列表等待中取消会结束传输。App 专项 31 项通过。原始 `common-setup` 关联的 `debug/models` Playground 页面与正式导航重建验收不在本候选证据内。
- **主审复验**：`qualityGate`、架构与复杂度 0 违规、App 仪器测试源码编译通过。App JVM 318 项，其中本轮 88 项；provider API/backend 5/38，data debug/release 各 209，network 24，chat API/backend/data 6/14/17，全部无失败、无跳过。仓库 105 项、冻结桌面 196 项、冻结输入 286 项及历史索引 249 条核验通过；S3.05 16 张旧回放卡重跑通过，67 个受测文件哈希不变。初轮的测试夹具与复杂度失败均修正后重跑；一次生成 zstd 临时源码时的 `cpu.h` 缺失未改冻结源，串行执行最终验证通过。
- **证据与顺序**：[候选证据索引](evidence/logs-index-s3-06-08-candidates-20261128.json)归档 83 项，SHA-256 `7979f1289330276591438bc6471c51a6f4203ee11a67aa350deaf9b9d84abf2f`，含 63 个候选源码/夹具的校验和。只在独立测试定位器接入，现有应用正式路由、回执与用户规则文件不变；未提交、推送、打包、装机或访问线上供应商。正式回执仍指向 S3.05。该单元签收后，依次接入 S3.06、S3.07、S3.08，补做正式调用链、私有历史接线及适用的 Playground 验收，不能将候选通过替代为单元签收。未启动 S3.09，S3.G 不变。

## 2026-11-28T17:41:53+08:00 — S3.04–S3.08 iPA2475 平板实测

- **运行环境**：iPA2475、Android 16 / API 36、`arm64-v8a`。同一套封存隔离包 `com.deepstudent.app.s2device` 及其仪器 APK 在平板运行 28 个测试类，**174 项通过、0 失败、0 跳过**。五套冻结桌面样本独立复算通过，并逐个与 APK 中的资产核对字节。所有模拟上游只监听平板的 `127.0.0.1`；先核对原目标端点，再将连接改向回环。安装后读回 APK 校验和，验后两包均卸载，正式包状态及 ADB 反向端口映射未变。
- **已有应用接线**：S3.04 Chat Completions **48 项**，42 份录件覆盖 10 家及旧直连回归；S3.05 Responses **38 项**，21 份录件覆盖 5 家。S3.05 另以合成 OAuth 回复检查 Codex 回环回调和 401 刷新，使用真实 Android Keystore 验证加密存储、重开和清除，无线上授权或真实账号。
- **隔离候选**：S3.06 Anthropic **21 项**，在仪器断言中核对 10 组冻结请求、7 组录制流以及签名回传、截断和取消。S3.07 Google / Gemini **36 项**，17 组请求和 9 组成功流录件复算一致，另验收安全阻断、断流及取消。S3.08 自定义 / 本地 / 中转 **31 项**，33 份录件复算一致，覆盖 15 张成员操作卡、显式协议路由、隔离 Responses、模型列表两页与 OpenCode Go 内置 13 模型零网络。各候选使用测试专用定位器，未注册进主应用。
- **复跑与取证**：测试副本调整 DEX 不支持的带空格方法名，采用与凭据工厂一致的规范化私有目录，并让回环对端以 FIN 或远端 TCP reset 为有效断开，不把本地错误或超时当作断开。最终同一封存包完整重跑；4 组共 122 份设备录件全部独立比对，Anthropic 结果保留原始仪器日志。[平板证据索引](evidence/logs-index-s3-04-08-ipa2475-20261128.json)收录 155 项，归档 SHA-256 `6adce23fbefabd02f250720e67e5bddd1cceabae523f4905afab1059041eb95d`，APK 组合 SHA-256 `68d09419809bc7b3a341d0971ac55b0086071cab6a6e3854523205b86a890789`。`0a367cde96a54baae24af9ae40d04113289d2aa0` 仅为工作区基线，实际被测输入以归档哈希清单为准。
- **后续边界**：`debug/models` 尚无主应用入口，未执行 Playground 页面、可见性或 Activity 重建验收；候选通过不等同正式接入。未改业务源码、主应用路由、用户规则或正式回执，下一待签收仍是 S3.05；未提交、推送、启动 S3.09 或推进 S3.G。

## 2026-11-28T18:05:28+08:00 — S3.04–S3.08 V2232A 手机接入前基线

- V2232A（Android 16 / API 36、`arm64-v8a`、ADB UID 2000）使用平板封存的同一套隔离 APK，读回组合 SHA-256 `68d09419809bc7b3a341d0971ac55b0086071cab6a6e3854523205b86a890789`。首轮 S3.04/05/08 超过主机整组等待时间，不算完成；按测试类分批补跑，仅改变主机等待与分组，保留同一 APK、断言和请求超时。最终手机 **174 项通过、0 失败、0 跳过**，逐个用例与平板矩阵一致，122 份录件复算并与平板逐项相同。隔离包验后卸载，正式包和 ADB 映射状态不变。
- [手机基线证据](evidence/logs-index-s3-04-08-v2232a-baseline-20261128.json)收录 163 项，归档 SHA-256 `b0e36ad6b984de60feb4d34f181b93d95edb18ec5869d443093d9c75dba09286`。本条仅证明接入前双机基线；现有 S3.04/S3.05 链路与 S3.06–S3.08 隔离候选仍分开记录，正式回执不在本条推进。

## 2026-11-28T20:15:10+08:00 — S3.04–S3.08 正式应用接线与双真机签收

- **按顺序接入**：S3.04 保留 10 家既有 Chat Completions 和旧直连路径；S3.05 Responses 用真应用进程验证 SQLite 读取、同目标推理项回传，Codex 使用合成授权与真实 Android Keystore。随后接入 S3.06 Anthropic（同目标签名回传、换目标去私有状态、内联图片、拒绝截断工具块），再接入 S3.07 Google/Gemini（图文、函数调用、安全阻断、私有签名及成对历史）。继续使用原聊天 schema 和 JSON 负载，不把未执行的工具声明写成完成结果。
- **S3.08 与调试入口**：将 custom/general/OpenCode Go/OpenRouter/Ollama 的隔离实现接入正式选择器，显式协议不降级，保留旧直连优先级；OpenCode Go 内置 13 模型不发网络。验证本地端点、分页、自定义头、取消及 407 固定脱敏诊断。官方工作区调试构建提供 `debug/models`（LLM Playground），在手机页面栈、平板工作台均验证进入、返回、Activity 重建保持路由，且实际从回环服务读取列表并显示流式回复。非调试构建不挂入口。
- **双机证据**：V2232A、iPA2475 均为 Android 16 / API 36 / `arm64-v8a`。两机逐项跑同一份签发的隔离 APK，各 **186 项通过**（S3.04–S3.08 依次 49 / 42 / 22 / 38 / 35），合计 **372 项，0 失败、0 跳过**。122 对设备录件逐项相同且与冻结桌面预期对照；Anthropic 随原始仪器日志。APK 组 SHA-256 `882a0dd9f5664f1b1de60fde394a6f74d01d67a0e741739b45f8901da581bb52`，用例清单 `519a7ffab91d14ef66b019211cca8aaacba0ed17de224eff1c01043593d08203`。安装读回及卸载核验通过，不改正式应用与 ADB 反向映射。
- **主机复跑**：离线质量门禁、release Kotlin 编译、五套冻结桌面样本复算均通过。App JVM 319 项通过，3 项既有桌面备份夹具未配置而跳过；供应商 API 5、backend 38、data debug/release 各 209，聊天 API/backend/data 6/14/17；网络补测后 26 项（含 2 项 407 HTTP/SSE 脱敏）。前两轮启动框架超时未计入通过；复用系统启动的真实 MainActivity，改验实际 HTTP Host，并补齐传输层的 407 分类，最终重跑所有双机用例。
- **最终回执**：[证据索引](evidence/logs-index-s3-04-08-dual-device-20261128.json)含 375 项，归档 SHA-256 `daeee86f99dce1c2e8b784e8af0bb9616f96dac11e20426151eb34df47f5394f`；源码基线 `0a367cde96a54baae24af9ae40d04113289d2aa0` 仅为构建时 HEAD，被测工作树以归档的输入哈希 `cc2ed44ae38ae6be7c0193b66ff2d5a7d6279387888af7665926c7cf3e5ee5a6` 为准。[正式回执](../config/stages/s3-progress.json)已补 S3.04 双机证据并签收 S3.05–S3.08；下一单元标记为 S3.09，未开工，S3.G 仍未签收。

## 2026-11-28T20:17:34+08:00 — S3.08 代理主机补验确认

- 代理补测文件在双机封存之后增加了两条网络日志首阶段断言；使用最终测试源码重新运行网络 JVM 全套，26 项通过、0 失败或跳过。[主机补验索引](evidence/logs-index-s3-08-proxy-host-20261128.json)收录最终源码校验和与用例摘要。生产代码、正式双机 APK 和已有证据不变。
