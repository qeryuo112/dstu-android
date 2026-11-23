# S2 交接：承接已签收的 S0 与 S1 基线

- 记录时间：2026-11-18T21:10:00+08:00（Asia/Shanghai）
- 当前状态：S0.G / M0、S1.G / M1 均已由主线程签收；S1.01–S1.27 全部 accepted，下一执行单元为 S2.01，S2 尚未开工。本文件是阶段基线梗概，不是 S2 实现、验收或正式发布声明。
- S0 完整交接：[archive/handoff-s0.md](archive/handoff-s0.md)。该文件为原字节归档，487 行、77,108 字节，SHA-256 `f22db4c71a074f3c81391c34a40033601826eae59d92202de55a2797c8063158`。
- S1 完整交接：[archive/handoff-s1.md](archive/handoff-s1.md)。原 `docs/handoff-s1.md` 已按**原字节归档**，与提交 `539ba2f` 的文件逐字节一致，479 行、134,297 字节，SHA-256 `2080fb13c58d862ab8427a371105276ed575d047337b6c5935aec9976f0b16e0`，没有追加或改写。
- 权威入口：[S0.G 主审回执](../config/s0-g-main-review.json)、[S0 最终锁](../config/schema/final-lock.json)、[S1 单元及 S1.G 回执](../config/stages/s1-progress.json)、[当前有效模块化执行计划](plans/2026-11-16-native-modular-execution-plan.md)、[冻结原计划](plans/2026-11-15-mobile-full-feature-refactor.md)。细节与历史失败以完整交接和原始证据为准。

## 0. 开工必读：S2.04 / S2.05 教训与核验清单

- 记录时间：2026-11-20T19:42:31+08:00（Asia/Shanghai）。每轮开工前，主线程先读 [rules](rules/rules.txt) 和本节，再查 [有效执行计划 §4.1／§4.2](plans/2026-11-16-native-modular-execution-plan.md)、[冻结执行队列](../config/execution-plan.json) 和 [S2 签收回执](../config/stages/s2-progress.json)，核实 `next`、全部前置签收及证据。向用户简要说明本轮单元、前置核查结果和待做验证；有实质进展时说明已完成什么，但不把中间结果报成签收。进入新阶段时，把本节或其入口带入新交接。

1. **前置签收后才能接入主线。** `25b11ff` 在 S2.04 尚未签收时就把 S2.05 的变更读取接入 App 同步；事后双机通过不能补正先后顺序。前置全部完成并正式签收前，后继单元只能做写范围隔离的准备，不接正式调用链、不合入主线。主线程集成前再查一次回执，不把「已实现」「待复验」当成 `accepted`。
2. **一个子智能体只负责一个单元。** 此前 S2.05 子智能体还接入了 S2.04 的迁移验收用例。分派前写清单元与文件范围，同阶段最多 3 个并发，换单元新建会话；跨单元共享脚本的合并和最终审查、独立测试、签收由主线程负责。
3. **签收前检查实际日志。** S2.04 捕获预检缺关联 ID 和耗时，S2.05 变更读取记录了耗时但缺关联 ID。检查关键路径的实际日志能用 `taskId` 或 `correlationId` 串起一次操作，并包含 `owner`、阶段、状态、耗时与脱敏故障线索；不得记录凭据或业务载荷。必需项缺失须补齐并复验，不用通过的功能测试替代。
4. **按证据范围写结论，逐单元收尾。** 编译探针不等于逐表实写；同时间戳的跨端对拍不等于倒序时间戳或原始 `sync_version=NULL` 也兼容。按该单元计划逐项核对正例、负例及所需真机证据；失败和未覆盖项如实标明。保留最终日志，清理过程产物，按实际时间追加交接并作本地提交，完成后再签收并推进下一单元。

5. **2026-11-23 新编号入口。** 用户授权原计划原地修订：新增 S2.16 普通恢复同步基线、S2.17 恢复资源引用兼容、S2.18 旧加密仓库标记兼容；原 S2.16–S2.28 顺延为 S2.19–S2.31。历史日志/回执的旧编号不改；新开工必须结合 `config/planning/active-plan.json` 的当前修订、原计划正文和 S2 回执查询，不能直接用冻结队列同名 ID。当前 next=S2.16（基线补修），不是 runtime.db。
6. **每个新单元先读源码。** 按 rules 第12/13条，完整冻结桌面功能及兼容性是总目标；主线程与专属子智能体开工前先阅读 dstu-node/Operit 冻结相关实现、调用链、测试，并记录路径/合同/采用方式/差异。没有对应实现的一侧记录搜索范围和相关组件，不把交接摘要当成源码学习。

## 1. S0 / M0：来源、合同与数据基线

- [来源锁](../config/source-lock.json) 将桌面 `dstu-node` 冻结于 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87`、Operit 冻结于 `7a39941bad80209973c535046a18195c5da9e647`、terminal 冻结于 `e4646680663789002b43bbfffa6e69e65c35e614`。桌面是功能及兼容合同的重写基线，后续不跟随上游，也不采用其自带 Android/Capacitor/mobile 运行链；旧移动 ZIP 是固定兼容样本，不重生成或替换。
- S0.G 主审于 `2026-11-16T03:59:35+08:00` 签收 25 个单元，M0 complete/signedOff。`final-lock` 为 validated 且不自我签名，最终签收依据是独立主审回执。S0.G 真实组装 135 份上游历史迁移与 3 份协调迁移；四共享库 fresh、既有库升级及 restart 均通过，完整性检查正常，restart 均不再执行迁移。

| 四共享库 | 签收版本 | fresh 迁移数 |
| --- | ---: | ---: |
| `vfs` | 20261117 | 75 |
| `chat_v2` | 20261111 | 30 |
| `mistakes` | 20261006 | 24 |
| `llm_usage` | 20260826 | 8 |

- 同步 registry 冻结为 126 项、其中 61 个 RowSync；wire 当前版本 4、最低读取版本 3、兼容租约 30 天，v5 未激活且未注册生产入口。S2 不得因完善底座而擅自改变冻结 wire、表分类或协调迁移；按合同保留大整数、`null`、未知字段及墓碑等语义。
- 重要合同边界：自定义 Anki 模板与资产按 [模板合同](../config/contracts/templates-assets.json) 和 [资产传输合同](../config/contracts/asset-transport.json) 同步；媒体进度使用可去重的便携事件，旧本机媒体/计时表不变为 RowSync，见 [媒体合同](../config/contracts/media-time.json)。[便携配置](../config/contracts/portable-config.json) 区分 portable、local 与 secret，未知键默认 local；[插件数据合同](../config/contracts/plugin-data.json) 保持 owner 隔离、配额与墓碑，远端数据不授予安装、启用、信任或执行权限。具体冲突处理和迁移细则以冻结合同及 S0 全文为准。
- M0 只签收来源、合同和夹具基线，不代表 APK 或设备发布。M0 留有许可与 16 KiB native 重建等发布阻断及后续 owner，须按各自门禁另行核验，不能因 M1 签收而视为豁免。

## 2. S1 / M1：原生纵切与双真机验收

- S1 建立原生模块化工程、Compose 产品壳及按进程隔离的装配；逐步接通 SQLite/资源、任务与工作台、编辑器和内容、FSRS/脚本、终端/PTY、环境与 Shizuku、安全及同步等纵切。S1 范围内的笔记与资产同步复用同一发布/apply 管线，明文与加密通过编码端口区分；凭据走 Keystore，冻结桌面 WebDAV、压缩和 DSBK 协议不被改写。这是 S1 纵切，不宣称 S8 全域同步已交付。
- 主线程于 `2026-11-18T20:54:27+08:00` 签收 S1.G / M1，九类纵切分别为数据、编辑器、内容、任务、工作台、算法、终端/Shizuku、产品壳和同步。iPA2475 平板与 V2232A 手机均为真实 ARM64 设备；S1.G 在两机安装读回的普通候选 `com.deepstudent.app` / `0.21.0` 为同一字节，APK SHA-256 `6b44c0f2d0c5a46f37ccf99f7a648f679602872e62fbf53e8b17e591057d2154`，见 [候选清单](../logs/s1-g-main-candidate.json)。禁缓存宿主复建的另一 debug APK 部分 DEX 不同，仅作为工程复验，不等同现场候选。
- 两机均有实际页面视觉证据，亮暗视觉按 S1 历史基线复核；平板横屏工作台及已授权 Shizuku 的只读 `id`、`pm list packages` 均有实测记录。S1.G 手机、平板分别通过 42 和 9 项非跳过 app 设备测试；另有 18 项 PDF 与 1 项 PRoot 模块设备测试。冻结桌面与 Android 真实明文、加密双向笔记及附件往返通过；加密错密不推进安全游标或写远端，更正密码后以原密文、同序列重试。历史测试及覆盖边界见 [手机证据](../logs/s1-g-main-phone.log)、[平板证据](../logs/s1-g-main-tablet.log) 和 S1 全文。
- 工程出口包括 634 项唯一 JVM、114 项 Node 和 18 项构建逻辑测试；73 模块、162 条边无环，528 个复杂度生产文件无违规，原 5 条 terminal vendor 例外保留。实机验证 main/engine/isolated 进程隔离及非主进程不装配业务存储、同步和安全；fake UI 测试不替代真实业务或设备证据。见 [宿主验证](../logs/s1-g-main-host.log)、[架构报告](../logs/s1-g-main-architecture.json) 和 [复杂度报告](../logs/s1-g-main-complexity.json)。

### 2.1 S1.27 历史缺样与复跑边界

- V2232A 旧轮第 29 个正式同步样本完成，第 30 次失败发生在下一轮采集的准备/启动窗口：当时未见该隔离包新的数据清理、进程或同步用例启动记录。旧采集器只保留通用失败、没有原始步骤或 errno，故无法确定该次历史故障的唯一根因。
- 旧式同号 ADB reverse 在真机设备端端口被占用时可复现绑定失败，是已证实的设计缺陷，**不是第 30 次失败必由端口占用造成的证据**。另一次 `tcp:0` 尝试触发 ADB 37.0.1 daemon 故障，亦不是原事故。采集器随后改用独立显式设备端口及 `--no-rebind`，仅对已确认的绑定冲突有限换端口并保留脱敏分步诊断，不自动重跑同步样本。
- 独立新轮手机加密双向同步完成 3 次预热、30/30 正式样本；原 29/30、TTID/TTFD 缺测和中间 ADB 故障仍如实保留，不以复跑覆盖历史。S1.27 两机证据不是同字节 APK；上述 S1.G 同包结论来自另一次普通候选验收。详见 [诊断日志](../logs/s1-27-main-phone-sync-diagnosis.log) 与 [双机复跑报告](../logs/s1-27-main-two-device-functional-rerun.json)。
- 按用户确认，S1.27 以既有平板和新增手机的实机功能跑通为准，**不恢复三档验收、设备档位限制或性能数值签收门槛**；保留已有测量用于诊断，不把缺测补写为通过。此调整不改写 S0 冻结队列，也不取消后续单元本身的功能正确性验收。

## 3. 下一执行位置与工作约束

- 下一单元 **S2.01：完善连接 profile 与读写通道**，前置 S1.04，S1.G 为 S2 公共前置，均已签收。按冻结原计划，交付 `DatabaseRegistry`、WAL 读连接、写者队列与事务入口；验收并行读写快照正确、锁等待按预算返回、关闭后文件描述符回基线。这里的锁等待预算是数据库功能验收，不是恢复设备分档或性能签收门槛。
- S2.02 迁移/fingerprint 等后续单元依赖 S2.01，不能提前签收。S2 应沿有效计划分别明确连接/事务与迁移所有者，不引入全能 `DataManager`/`RuntimeManager`；原 302 单元及阶段出口仍为唯一集成队列，[冻结执行计划](../config/execution-plan.json) 是 S0.25 快照，不因阶段进度回写，实际签收查各阶段回执。
- S1.G 仅滚动估算 S2.01–S2.03 为 8–10 工程人日，另预留设备可用性 0.5–1 工作日，S2.03 后重估；这不是实际耗时或交付承诺，见 [排期记录](../logs/s1-g-main-schedule.json)。
- 每轮先读 [项目规则](rules/rules.txt)；专属子智能体只负责分配的当前单元，同阶段最多 3 个并发，主线程负责审查、独立测试、交接及本地提交，不推送。交接按 Asia/Shanghai 准确时间追加；不把 mock、smoke 或模拟器冒充正式真机证据。

## 2026-11-18T21:16:17+08:00 - 主线程归档核验

- 专属子智能体撰写本页梗概，主线程审查并盘点 S1 过程文档。此前清理的实施草稿未遗留或重新引入；文档根只保留当前阶段交接，正式计划、回执和验收证据保留。两份历史交接与归档前版本逐字节一致；S1 正文的旧相对链接保持原样，按 [现行文档入口](README.md) 导航。
- 有效计划和活动计划登记已切换至 S2 交接；27 条 S1 单元回执只迁移交接路径，签收时间、状态、依赖与证据不变。49 项计划及签收校验测试通过；核对 42 个现行文档相对链接（含本条入口）、277 个计划单元、9 个阶段出口、9 项冻结引用和 535 个冻结输入。
- 在独立 Git 暂存树快照中复核，S1 签收校验仍返回 `accepted=27 / next=S2.01 / s1GateAccepted=true`。历史截图从 Git 字节读取，用户现有的规则修改和工作区 PNG 删除未恢复、未暂存。临时校验快照已清理；本轮未构建 APK、未操作真机、未启动 S2.01。

## 2026-11-19T04:42:08+08:00 - S2.01 连接与事务已实现，手机待复核

- S2.01 专属子智能体实施，主线程独立审查。生产 VFS、运行时、脚本和惰性卡组库使用 [DatabaseRegistry](../platform/database/src/main/kotlin/com/deepstudent/platform/database/DatabaseRegistry.kt)；兼容旧 DatabaseFactory 入口。规范路径共享单写者，拒绝活动文件的硬链接别名、替换和冲突配置。同步读默认走有界 WAL 只读连接，显式读快照在回调前固定。队列和 SQLite 锁争用共用有限等待预算；事务及行只在作用域可用，超时不补交，自动回滚后不可继续写。固定 SQLite 依赖与 S1 的持久性设置不变，未对既有库执行 VACUUM。
- [宿主复验](../logs/s2-01-main-host.log)：655 个唯一 JVM 测试，73 模块架构及复杂度零违规，普通 ARM64 APK 身份和对齐通过，535 个冻结输入未变。独立 JVM 子进程持锁验预算，Android 使用同一 bundled JNI 的另一连接持锁，不混称跨进程。正常关闭与在途回调超时后的清理均覆盖；不声明能强制终止调用方回调或 native 打开及关闭同时失败时的资源回收。
- [iPA2475 初轮及最终证据](../logs/s2-01-main-tablet.log)：最终 14 项数据库专项及 7 项 App 回归均通过、无跳过；200 ms 写队列/关闭预算实测 201/200 ms，350 ms SQLite 锁预算实测 356 ms，目标库 FD 0→3→0。主线程 App 用例验证笔记提交后旧快照仍见旧值且新连接读取新值。此前测试装置使用同进程不同 SQLite 库、以及按路径字符串匹配 FD 导致未通过；最终改为独立 bundled 连接和设备号/inode，保留前轮结论，不计通过。平板原位升级且未清数据，两个测试包已卸载。
- 用户一次只能接一台设备。S2.01 已完成宿主和首台实机前置验证，单独本地提交；接下来实施 S2.02。S2.01 和 S2.02 的双真机签收等两机顺序复跑最终候选后统一追加；此条不签收 S2.G 或发布。

## 2026-11-19T05:55:47+08:00 - S2.01、S2.02 双真机签收

- S2.01（实现提交 `1ee206c`）在 S2.02 最终候选上双机复验：生产笔记写提交不改变已固定 WAL 快照，新读取能看到更新；独立 bundled 连接锁、写队列、含在途事务的关闭均按预算返回；数据库 FD 关闭后回基线。平板最终实测队列/关闭 201/200 ms、SQLite 锁等待 353 ms、FD 0→3→0；手机专项同样通过相关断言，没有将缺失的手机 Logcat 测量补写为数值。
- S2.02 专属子智能体实施，主线程接入 VFS 和惰性卡组库的脱敏启动日志。每份冻结 SQL 与其 refinery 账本行同事务提交；外键重建在提交前校验；先验证源字节、账本前缀、受管结构和治理指纹，拒绝漂移，不在正常重开时改写账本或 `verified_at`。日志含库、版本、应用条数、指纹和固定诊断。列顺序/属性、索引与触发器差异定位遵循冻结作用域；不扩展为完整 table DDL 比较，不能据此保证检测纯 CHECK 定义等未被旧指纹覆盖的差异。
- 两台均用安装后字节哈希一致的普通 ARM64 `com.deepstudent.app` 0.21.0，APK SHA-256 `7b1a89f02b09ea1ac0e8e0ebb02aa580144c210ad2ff48caad1388ded333ab1a`；数据库和 App 仪表包哈希也匹配。iPA2475、V2232A 各通过 20 项数据库与 8 项 App 测试，零跳过。四共享库新装指纹匹配冻结桌面；真实 SQL/账本回滚及字节、表列、索引、触发器漂移能定位，拒绝时保全输入。独立冻结桌面 Runner 生成同一份 20261111 VFS 旧库，两机用普通 APK 升至 20261117，各只应用 3 个迁移；核对 75 条账本哈希、治理指纹、18 个旧字段和 7 个 FSRS 默认值，重开执行 0 个迁移且治理时间不变。未重采仓库夹具。
- [宿主](../logs/s2-02-main-host.log) 661 个唯一 JVM 通过、架构与复杂度零违规、535 个冻结输入不变；[平板](../logs/s2-02-main-tablet.log) 和 [手机](../logs/s2-02-main-phone.log) 保留最终成功回执及前轮失败说明。平板 OEM 冻结过合组仪表进程，分用例执行并把一次性旧夹具生成改为一次提交后通过，生产迁移仍逐份提交；手机前轮安装和合组仪表等待未计入通过。平板保留已有 App、未清数据；手机临时 App 和两机测试包已移除。没有新增设备档位限制。
- [单元签收及候选哈希](../config/stages/s2-progress.json)；复现入口为 [冻结桌面旧库生成](../tools/s2/desktop-vfs-fixture.mjs) 和 [单设备校验](../tools/s2/verify-device.sh)。下一个单元为 S2.03，维护页、主动续作和逐提交点进程终止验证尚未开始。未签收 S2.G / M2 或正式发布。

## 2026-11-19T19:19:35+08:00 - S2.03 实现与 iPA2475 验证完成，待同候选手机复验

- 专属子智能体实施、主线程独立审查。`MigrationMaintenance` 查询脱敏状态，报告库、已验证提交版本、目标版本、失败阶段、定位版本与冻结诊断；无法验证时显示未知。启动必需的 VFS 失败显示维护页，后台和前台看到同一失败，不会自行重试。用户可显式续作；仍经 `DatabaseRegistry.openShared` 重新校验并应用未提交迁移，无绕过、清数据或重放入口。进行中的重试由应用进程持有，页面重建不取消；并发点击合并，成功后按正常路径恢复工作区。非启动共享库本轮未新增产品维护页入口。
- [宿主](../logs/s2-03-main-host.log)：唯一 JVM 测试 668/668（包括失败诊断/真实锁争用、状态锁存与并发点击，另以真实子 JVM 在 8 个 LLM Usage SQL 提交及 1 个旧治理补记后直接 `halt`）；73 模块架构和复杂度门禁零违规、535 个冻结输入未变。初轮 `MainScreen` 圈复杂度 15 超过 10，拆分启动状态与展示后重跑成功，未加豁免。
- [iPA2475](../logs/s2-03-main-tablet.log)：独立测试进程在 SQLite `COMMIT` 后、关闭连接或开始下一迁移前收到实际 `SIGKILL`。新 PID 验证 137 个四共享库新建迁移（75/30/24/8）、3 个冻结桌面 20261111 旧 VFS 升级迁移及 4 个旧 head 治理补记，共 144/144；逐点核对冻结账本前缀与先前 `applied_on` 哈希、完整性及外键。旧库 18 个原字段、7 个新列默认值和考试/问题关联保全，最终 20261117；九次链尾重开无新迁移、治理时间不变。此测试只覆盖进程硬终止，不代表硬件掉电；不扩展 S2.02 冻结指纹的检测范围。
- 使用普通 ARM64 `com.deepstudent.app` 0.21.0 debug，App/两个仪表包安装后读回哈希均与[候选回执](../config/stages/s2-progress.json)一致。平板数据库 20 项、App 10 项全过、无跳过：含 SOURCE 漂移定位、失败保持、显式重试跨 Activity 重建、正常 `MainActivity` 进入工作区。平板保留原位 App 和数据，临时测试包已移除。
- 下一步只接入 V2232A，用同一候选与同一独立生成旧库运行 [硬终止矩阵](../tools/s2/verify-migration-death.sh) 和 [回归](../tools/s2/verify-device.sh)（`--s203`；可设 `DSTU_S2_APK_DIR` 指向保存的实际候选目录）。不重复平板、不启用设备档位或性能门槛。S2.03 尚未双机签收，`next` 仍为 S2.03，未开始 S2.04；S2.G / M2 及正式发布均未签收。

## 2026-11-19T20:33:18+08:00 - S2.03 双真机签收

- 主线程在仅连接 V2232A（ARM64、API 36）时复跑保存的 `2948000` 候选，不重新打包。普通 App `com.deepstudent.app` 0.21.0 debug、App 测试包、数据库测试包均安装后读回 SHA-256，与 iPA2475 实测候选及[签收回执](../config/stages/s2-progress.json)完全一致；独立冻结桌面旧 VFS 输入也为同一字节。手机上本轮临时安装的 App 和两个测试包均已卸载并核对，未清理其他应用或用户数据。
- [手机证据](../logs/s2-03-main-phone.log)：144/144 个真实 SQLite 写提交后硬终止恢复点（四共享库新建迁移 137、冻结桌面旧 VFS 升级 3、旧 head 治理补记 4），每个重开核对账本前缀和已提交时间戳摘要；九个链尾重开均执行 0 个迁移且治理记录不变。旧 VFS 从 20261111 升至 20261117，核对旧行字段、FSRS 默认值及相关行。另过 20 项数据库和 10 项 App 回归，无跳过，含启动 VFS 维护页诊断、失败保持、重试跨 Activity 重建、及正常工作区启动。两机均通过同一验证，不设设备档位或性能门槛。
- 单元关联的 `mistakes.migration_progress` 另对拍冻结分类 `LocalRuntime / NoConflict`。宿主以全部 24 份冻结 Mistakes SQL 建独立内存库，核对账本、`category` 主键，实测该表增改删的 `__change_log` 新增行数为 0；此证据是冻结 SQLite 捕获合同，不代替 S2.04 的 Android 全域采集验收。
- 范围不变：产品维护页只接启动必需的 VFS；旧数据样本是冻结桌面 20261111 VFS，不代表所有历史数据库；进程硬终止不是掉电实测；不扩张冻结 schema 指纹的 DDL 检测范围。S2.03 accepted，`next` 指向 S2.04，尚未开工；未签收 S2.G / M2 或正式发布。
- 排期复核：S2.01–S2.03 观察到真实逐提交点复验、先后换机及安装等待，日历耗时不换算为实际工程人日。S2.04–S2.05 暂按冻结实现基数 4.5 工程人日，加 1–2 人日用于合同向量、候选联调和主审复验，即滚动估算 5.5–6.5 工程人日；另列设备可用性 0.5–1 工作日，不计入工程人日。S2.05 后再校准；这不是承诺，冻结队列、阶段基数和里程碑不变。

## 2026-11-19T20:38:50+08:00 - S2.03 过程文件清理

- 移除本轮构建遗留的空 Kotlin 会话目录；复核临时 APK 包、旧 VFS 测试副本、构建产物与中间日志均已清理。保留 S2 签收回执引用的 8 份最终日志、当前交接、历史交接归档和正式计划；复验 535 个冻结输入未变、活动计划和全部 S2 证据路径有效。
- 未改动源码、签收状态、外部构建缓存或设备数据；用户已有的规则文件修改与 S1 截图删除保持原样。下一单元仍为 S2.04。

## 2026-11-20T14:38:55+08:00 - S2.04–S2.05 实现、宿主和 iPA2475 验证；冻结触发器阻塞

- S2.04（`0dca58c`）和 S2.05（`25b11ff`）分别由专属子智能体实施，主线程按依赖顺序审查、集成和本地提交。S2.04 唯一注册表从 S0.G 锁定输入装载 126 个分类（61 个 RowSync），现有 VFS manifest 主键取同一注册表；检查四库键、捕获触发器、日志表，以及隐私安全的实际 SQLite 操作编译。全库结构漂移阻断同步；已知故障表不在现有 Note/Anki 消费范围时，仅允许该已注册的现有范围使用，不等于全库预检通过。惰性卡组库仍按需开启。
- **签收阻塞**：冻结 `V20261117__plugin_skill_data_contract.sql` 中 `vfs.portable_plugin_records` 的 `trg__change_log_portable_plugin_records_delete` 在 `AFTER DELETE` 用 `NEW.id`（删除时只有 `OLD`）。四库共 183 个操作编译探针仅这一项失败。`note_document_revisions` 的物理 DELETE 可编译但不入日志，属于已冻结豁免；`portable_media_events` 物理删除受不可变约束。宿主和 Android 实库确证：插件的 `deleted_at` 墓碑 UPDATE 可入日志；物理 DELETE 被拒后行与日志保持不变。仅在宿主一次性内存库将标识值替换成 `OLD.id`，确认真实 DELETE 可记录同一逻辑主键；未修改原 SQL/冻结 lock，也未把该故障加为验收豁免。需明确双端协调追加新迁移等修复策略后再全量签收。
- S2.05 从同一固定 WAL 只读快照按变更日志 `id` 高水位分页，独立解析行图像、复合键、未知字段和日志 field deltas。保持原 INSERT/UPDATE/DELETE 顺序与缺像语义，删除不读取重插后的行；保留 SQLite 整数、JSON 大整数、null/缺省/空串及未知字段；本机派生字段不出站。已有笔记采用同一 reader 和领域依赖补全，保留旧笔记的 vault 版本元数据，显式日志值优先；不改 wire/ack/cursor。SQL 分页有界，旧发布 API 仍汇聚列表；Android 保留旧 `COALESCE(sync_version,0)=0` 选择，非桌面历史显式 NULL 的完全等价。未实现 S2.26/S2.27。
- [主线程宿主](../logs/s2-04-05-main-host.log)：694 个唯一 JVM 通过，73 模块架构/复杂度门禁零违规；535 个冻结输入不变。[iPA2475](../logs/s2-04-05-main-tablet.log)：同一普通 ARM64 0.21.0 debug App 与两仪表包读回哈希见[回执](../config/stages/s2-progress.json)；12 个诊断合同、5 项 App 回归通过，零跳过（断言成功定位冻结缺陷不代表缺陷通过）。VFS/CHAT_V2 两库冻结桌面序列 11 条、包括复合键和重插/删除，v3/v4 字节对拍；既有笔记与附件明文、加密 Android↔冻结桌面往返，错密无远端写、正确重试复用保留批次。平板 App 和数据保留，临时包、测试根与测试密钥清理；宿主构建产物、候选副本和过程日志已清理，仅留最终证据和源码。
- **S2.04、S2.05 暂未签收**，手机未复验，不启用设备分档或门槛，`next` 仍为 S2.04。S2.06 未开工，S2.G / M2 与正式发布未签收；工具入口为 [桌面 oracle](../tools/s2/change-log-oracle.mjs) 和 [单机验证](../tools/s2/verify-sync-device.sh)。

## 2026-11-20T18:10:14+08:00 - S2.04 协调修复完成，S2.04–S2.05 同候选平板复验通过；待 V2232A

- 用户明确批准保留原冻结迁移，新增桌面与 Android 共用的修复迁移。三名专属子智能体分工实施 S2.04 数据库/桌面增量、S2.04 捕获合同、S2.05 对拍，主线程审查、独立测试并本地提交 `aeb1940`。未修改原 `final-lock.json`、原 138 份 SQL、分类/wire 合同、冻结桌面工作树或基线提交 `e6b2657…`；不是重签 S0。新增 [20261120 协调 SQL](../config/schema/coordinated/vfs/V20261120__fix_plugin_delete_trigger.sql) 仅重建插件 DELETE 触发器为 `OLD.id`；[独立补充锁](../config/schema/s2-04-repair.json) 锚定原锁，锁定新增 SQL 字节、refinery 校验和及前后指纹。VFS 有效 head 从 20261117/75 条升为 **20261120/76 条**，指纹 `7dec0d2ff3cf1c40cb15e73396c05cbcab925487661b3e09a69a6b1f30f8345b`，其余共享库版本和受管 scope 不变。
- 桌面通过新增 [非侵入增量](../desktop-increment/s2-capture-repair.mjs) 和既有注册钩子加载同一 SQL，不写冻结桌面 checkout；Android 在原锁/原 SQL 校验后合并补充锁，源码读取或字节漂移均失败关闭。捕获 registry 替换同名触发器 hash，不重复注册；126 分类/61 RowSync 不变，严格全库预检的 **183/183** 操作可编译。原 `note_document_revisions` 物理 DELETE 豁免及媒体不可变约束保留。App 远端 schema 上限改从同一有效 registry 读取，不再写死旧 VFS head。
- [宿主最终证据](../logs/s2-04-05-repair-main-host.log)：**703 个唯一 JVM**、73 模块架构与复杂度零违规、535 个冻结输入原字节不变。桌面真实 SQLite 独立重算补充锁及指纹，fresh、旧 head 升级、重开、完整插件行/日志/账本保全和物理 DELETE 通过；三份运行 APK 实际 assets 中原锁/全部 SQL/新增锁及 SQL 与源码逐字节一致。初轮 3 个函数复杂度超限已拆 helper；新测试的非法 `unknown_json` 样本改为冻结 clocked-value envelope，未放宽生产约束。所有最终完整构建与测试通过后才保存候选。
- [平板最终证据](../logs/s2-04-05-repair-main-tablet.log)：只连接 iPA2475（ARM64/API 36），安装读回四 APK 均匹配回执；**27 项修复/同步专项**（4 迁移、16 捕获、4 App、3 oracle）及 **30 项既有回归**（20 数据库、10 App）全部通过、零跳过。覆盖 20261117 原位只加 1 条修复、20261111 桌面旧库经普通 App 加 4 条迁移、重开不改治理时间；插件插入/更新/墓碑/物理删除保持同一逻辑键，旧坏触发器注入后正确诊断且行与日志不丢失。
- 新 [桌面对拍](../tools/s2/change-log-oracle.mjs) 共 **18 条**，原 resources 7 条和 chat 复合键 4 条摘要不变，新增插件物理 DELETE/重插 7 条；平板按 2 条分页逐字节对拍并核对 v3/v4 往返、大整数、null、空串与未知字段。修复后的桌面和同一普通 APK 完成明文/加密真实 WebDAV 笔记及附件往返；加密错密不推进或改写，更正后按原密文同序列重试。
- 额外回归全部迁移提交后进程硬终止：按 vfs/chat_v2/mistakes/llm_usage/desktop-vfs 五个独立完整 scope 实测 **146 个恢复点**（138 新建迁移 + 4 旧 VFS 升级 + 4 治理补记），9 次链尾重开执行 0 个迁移且治理记录不变。首轮专项及随后 3 次长矩阵尝试受到 `com.vivo.sps:rms`（UID1000/PID18156）外部 force-stop；原始失败与系统证据均保留，陈旧 marker 被拒绝，均未算通过。[硬终止脚本](../tools/s2/verify-migration-death.sh) 新增显式 `--scenario`，默认仍为五库；最终五段都独立完成并由主线程核对集合/计数，不以部分 scope 冒充全矩阵。没有改变设备全局省电设置、增加设备档位限制或重建候选。
- 同候选续测资料保存在仓库外 **`/home/xutao/.cache/dstu-android/candidates/s2-04-05-repair-aeb1940`**（四 APK、同一 oracle 和同一旧 VFS 输入）；四包集合 SHA-256 `d928020cabf39691935455fc7e0e7ec8c665f2f98118d2a24d61c84048fb8f5f`，普通 App `7066420ee14e88b09cd1bf17ebc432749ca288886228c86135a09a1edffe3c79`，oracle `39ffa6cac796392544a051030980f6adc3981e5ddba2336babe583fddfa3400d`。完整哈希与首台结果见 [S2 回执](../config/stages/s2-progress.json)。平板保留普通 App 和原数据，测试包与临时夹具已清理；仓库 80 个忽略构建/会话目录及过程文件清理，未动共享构建缓存。规则文件修改和 21 张 S1 截图删除仍保留用户原状、未暂存提交。
- **下一步只接 V2232A，直接复用已保存候选，不重新打包**：设置 `DSTU_S2_APK_DIR` 为上述目录、`DSTU_S2_ORACLE_SHA256` 为上述 oracle hash、`DSTU_S2_CANDIDATE_SHA256` 为四包集合 hash；运行 `verify-sync-device.sh SERIAL V2232A "$DSTU_S2_APK_DIR/change-log-oracle.json" --capture-and-changes`、`verify-device.sh SERIAL V2232A "$DSTU_S2_APK_DIR/desktop-old-vfs.db" --s203`；迁移死亡矩阵可默认全跑或分别 `--scenario vfs|chat_v2|mistakes|llm_usage|desktop-vfs`，必须集齐五段 146 点/9 次链尾重开。再运行 `sync-notes-device.mjs --reuse-verified-app` 及加 `--encrypted` 的往返。Node 使用工作区 `build-tools/node24/bin`。只在手机同候选全过后签收 S2.04/S2.05；当前 `pendingCandidate.status=awaiting-phone`、`next=S2.04`、`s2GateAccepted=false`。S2.06 未开工，S2.G/M2 与正式发布未签收。

## 2026-11-20T18:48:45+08:00 - S2.04/S2.05 真机退出诊断探针（独立于验收候选）

- 主线程审查、集成两名子智能体的实现：[测试内探针](../testing/android-process-exit/src/androidTest/java/com/deepstudent/testing/exitprobe/ProcessExitProbe.java) 只编入数据库和同步独立 `androidTest` APK，未进入普通 App 或库的运行产物。Android 11/API 30+ 可用：通过 `-e listener com.deepstudent.testing.exitprobe.ProcessExitProbeListener` 自愿开启（同次运行可传 `-e dstuExitToken` 32 位小写 hex），记录 PID、UTC 毫秒与一次性标记。外部确认原 PID 已退出后，用同一 APK 的 `ProcessExitProbeAndroidTest#report`、原 `dstuExitPid` / `dstuExitToken` 和**启动报告前取的设备 Unix 毫秒** `dstuExitCutoffMs` 匹配历史退出信息；不匹配或不支持时不输出死因、不读原始 trace。默认不启用 listener；没有显式控制标记不会自杀或挂起。
- [宿主采集器](../tools/s2/collect-process-exit.mjs) 在 [同步验证](../tools/s2/verify-sync-device.sh) 和 [迁移恢复验证](../tools/s2/verify-migration-death.sh) 遇意外中断/校验失败时，先限时采集再卸载；只输出对应包、可信窗口、已知 PID 的退出元数据及 `am_kill` / `ActivityManager` 线索。区分 `start instr` / `finished inst` 生命周期与外部强停；计划内硬终止仍以原检查点和 PID 消失校验通过，不额外采集。OEM 日志若提供调用方 UID/PID 则保留为待核实线索；系统分类或 `Process crashed` 不等于厂家内部策略，采集失败不改变测试结果与清理。
- [最终证据](../logs/s2-exit-probe-main.log)：两个目标模块 **127 项 JVM**、**29 项宿主脚本回归**通过，架构/复杂度零违规。iPA2475（API 36）上分别单独装两份诊断 APK；[控制脚本](../tools/s2/verify-process-exit-probe.sh) 的自发 SIGKILL 实测 `SIGNALED / 9`，宿主 ADB 强停实测 `USER_REQUESTED / FORCE_STOP`，无 opt-in 不执行自杀；同 PID 错标记、时间窗外记录均拒绝归因。原 capture 用例附加 listener 后通过；另一次被动观察超时、预报告采集未见终止，随后 Android 启动报告时才 `due to start instr` 杀旧 PID，**不是 vivo 再次强停**。最终已加报告截止时间及旧 PID 消失检查；仍未得到 vivo RMS 命中的具体规则。
- 本轮诊断 APK 不计签收，已卸载并清理构建过程产物；普通 App 原安装路径和哈希不变，535 个冻结输入和仓库外原保存候选（四 APK、两输入）均未改。另以原候选回归 `llm_usage` 的 9 个计划内恢复点通过。`pendingCandidate` 不变，下一步仍只连接 V2232A，用原候选复验，届时宿主可自动采集异常；未开始 S2.06，未变更验收门槛。

## 2026-11-20T19:15:00+08:00 - S2.04 / S2.05 同候选双真机签收

- **RMS 诊断收档，暂不继续追查**：主线程于 `2026-11-20T18:53:37+08:00` 在 iPA2475 只读交叉核对 `dumpsys batterystats --history` 和系统日志。设备本地时间 `2026-11-20 13:58:09.036+08:00` 记录主屏 ON，之后首次记录主屏 OFF 为 `18:12:55.417+08:00`；四次 `com.vivo.sps:rms` 外部强停 (`17:52:35.170`、`17:57:35.192`、`18:00:05.203`、`18:02:35.237`) 均在该亮屏记录区间内，最后一次早于 OFF 约 10 分 20 秒。`17:50–18:04` 的电池历史有 1646 条记录，并非整段无样。现有证据不支持把这四次归为“合盖熄屏触发”；RMS 命中的具体规则未确定。不修改设备全局策略、不新增设备档位、不主动继续复现，后续实际遇到再用[诊断探针](../logs/s2-exit-probe-main.log)采集。
- 主线程仅连接 V2232A（ARM64/API 36），从仓库外保存目录复用原四 APK、同一桌面 oracle、同一旧 VFS 夹具，未重建。全部六文件哈希与 iPA2475 候选一致；手机安装读回的四包 SHA-256 再次核对。四包清单摘要 `d928020cabf39691935455fc7e0e7ec8c665f2f98118d2a24d61c84048fb8f5f`、oracle `39ffa6cac796392544a051030980f6adc3981e5ddba2336babe583fddfa3400d`，旧 VFS 摘要另见[签收回执](../config/stages/s2-progress.json)。初次手机脚本尚未安装 APK 即退出：该设备对全局缺包的 `pm path --user 0` 返回 1 和空 stdout；宿主修正为仅在成功的 `dumpsys package` 确认全局缺包时接受该返回，仍拒绝隐藏包及其他 ADB 查询错误。纯宿主护栏模拟回归覆盖相关负例；首轮不计测试通过，APK 无变化。
- [V2232A 最终证据](../logs/s2-04-05-repair-main-phone.log)：**27 项专项**（4 迁移修复、16 捕获、4 App、3 oracle）与 **30 项既有回归**（20 数据库、10 App）均通过、零跳过。四库严格预检断言 **183/183** 操作编译；同一插件键 INSERT/UPDATE/墓碑/物理 DELETE 与原坏触发器负例均有覆盖。冻结桌面 20261111 旧库经普通 App 应用 4 份迁移至 20261120，保留字段/默认值校验通过，重开执行 0 份迁移。五段迁移硬终止矩阵 **146/146**（138 新建、4 旧 VFS 升级、4 治理补记）及 **9 次**链尾零迁移重开全部通过、零跳过。本轮手机未观察到外部强停；测试包和本轮临时普通 App 均已卸载，临时 WebDAV/ADB reverse 已清理。
- S2.05：手机三个独立 oracle 用例均成功，**18 条** (`resources` 7、`chat_v2_session_mistakes` 4、`portable_plugin_records` 7) 按 2 条分页，v3/v4 字节对拍；宿主独立从冻结桌面加修复增量重算 15,951 字节 oracle，并与同候选逐字节相同。明文/加密 WebDAV 笔记、附件、删除恢复、幂等重复均通过；加密错密不推进，改正后沿原密文同序列重试，并校验 Android 创建仓库。**签收边界**：oracle 统一 `changed_at` 以固定时间值，Android 使用 WAL 快照中的 `id` 稳定分页；桌面 `changed_at ASC` 在时间倒序的构造输入上与 Android `id` 顺序不同。桌面 `sync_version=0` 和 Android `COALESCE(sync_version,0)=0` 对原始 `NULL` 的筛选亦不同。此处证明页内/页间重插、删除及值保真等已测合同，未宣称任意时间回拨或异常 raw `NULL` 数据序列完全同序，不把构造反例写成真机故障。
- 两名专属子智能体分别复核 S2.04 的冻结 138 SQL/增量锁、桌面迁移与缺包护栏，以及 S2.05 的独立 oracle、WebDAV 宿主回归及覆盖边界；主线程独立安装、跑 tests、审阅最终证据并签收。原候选宿主 703 JVM 与 73 模块门禁证据仍适用，本轮补充宿主脚本及计划检查 **56 项**通过、535 个冻结输入未变。保留已有规则文件编辑及 S1 截图删除，未暂存或提交。S2.04/S2.05 状态 `accepted`，不再有 `pendingCandidate`；**下一单元 S2.06（未开工）**。S2.G/M2 和正式发布仍未签收。


## S2.06：内容寻址资源写已实现与平板复验，待手机同包复验

- 记录时间：2026-11-21T00:09:29+08:00（Asia/Shanghai），实现基线 `f75944e4bbf1b67830ea85131d687f6a455cc9f0`。按置顶 §0 核对了 rules、有效计划 §4.1／§4.2 和冻结队列；S1.06、S2.01–S2.05、S1.G 全部已有签收与证据。本轮三名子智能体各自只负责一个单元；主线程独立审查、集成、打包、测试和提交。无新增页面：这是资源读写/恢复端口，现有笔记继续走正式 `AttachmentService`，系统选择器在 S2.07 接线。
- 资源库流式计算 SHA-256、验证大小和空间，以同文件系统隐藏暂存 + 原子重命名发布完整文件，再提交 SQLite 引用；重复内容复用已有附件/实体字节。写入、取消或数据库故障无可读半文件。恢复持有相同发布锁，再核对冻结表的持久引用和文件身份；冲突、身份记录缺失或不一致时保全完整文件，不猜测删除。已提交的附件不因后置清理故障报成失败，后续写入继续恢复。不改冻结 SQL、路径布局、桌面和 wire；测试覆盖注入切点、进程/句柄释放后的恢复、并发与替换保全，不宣称断电或任意不遵守发布锁的外部写入也已覆盖。
- 开发候选在 iPA2475 先后复现 `SPACE/SecurityException`（FileStore API）和 `PUBLISH/AccessDeniedException`（私有目录硬链接）；探针确认原子重命名及前后文件标识可用。已用 `File.usableSpace` 与原子移动替换，空间校验及恢复护栏保留。两次失败不计通过，退出证据是正常 instrumentation 生命周期，未归因于熄屏或 OEM 强停。
- 主线程宿主 [最终记录](../logs/s2-06-main-host.log)：729 项 JVM、18 项工具回归通过，73 模块架构/复杂度门禁零违规，535 份冻结输入未变；以锁定 `/usr/bin/node` 执行的 S0 快照 25 单元重放通过。平板 iPA2475（ARM64/API 36）同一冻结候选 [最终记录](../logs/s2-06-main-tablet.log)：13 项专项、1 项资源模块回归、4 项 App 用例，全过零跳过；含故障回滚、去重、两篇笔记共享附件的删除/恢复/重开和已有损坏同名文件不被覆盖、不入库。另验证桌面↔Android 明文与加密 DSBK WebDAV 笔记/附件往返。实际 logcat 39 条，16 个关联 ID，完整创建轨迹 3 个、损坏拒绝轨迹 1 个，owner/phase/status/耗时及固定原因可查，无夹具私有名或载荷。
- **续测：只接 V2232A，直接复用仓库外 `/home/xutao/.cache/dstu-android/candidates/s2-06-f75944e`，不要重打包**。三 APK 集合 SHA-256 `f61f600cb528d30d96cd747999d873a2c64cb4139823d25e5234f221794b96cf`；独立 APK、用例表摘要见 [S2 回执](../config/stages/s2-progress.json)。宿主先以 `sha256sum -c SHA256SUMS` 校验保存目录，使用项目 Node24。在仓库根运行 `bash tools/s2/verify-resource-device.sh SERIAL V2232A S2.06 "$CANDIDATE_DIR" "$CANDIDATE_SHA256" < "$CANDIDATE_DIR/cases.tsv"`；核对实际日志 `resources.operation` 串起成功发布及 `state=CORRUPT` 拒绝。再设置 `DSTU_S2_APK_DIR="$CANDIDATE_DIR"`，分别运行 `node --disable-warning=ExperimentalWarning --experimental-strip-types tools/s1/sync-notes-device.mjs` 及加 `--encrypted` 的往返。若手机原已安装普通 App，设备脚本保留原位更新后的同候选，此时两次往返均加 `--reuse-verified-app`；若设备脚本已移除临时普通 App，则不加该参数，令往返自行临时安装和清理。平板 App 保留原数据，测试包与 reverse 通道均已清理。手机通过后追加证据并签收 S2.06；当前 `pendingCandidate.status=awaiting-phone`、`next=S2.06`、`s2GateAccepted=false`。

## S2.07 / S2.08：隔离准备与真机探针，不是整体验收

- 记录时间：2026-11-21T00:09:29+08:00（Asia/Shanghai）。只做隔离工作，无 App 主线接线；仍按冻结队列先签收 S2.06，再接入 S2.07；S2.08 还继承 S2.07 的签收前置。分支及针对此边界的 [独立设备证据](../logs/s2-07-08-isolation-main.log) 已保留，临时 worktree、构建、旧候选及过程日志不留在仓库。
- **S2.07**：本地 `codex/s2-07-prep` `a7baa2611d448c170a61f27ced23485287e94ef9`。已备系统选择请求、URI 授权句柄、PFD 流、SHA-256 回读、进度、取消/撤权以及可明确报告「可能残留」的清理结果；46 项模块 JVM 通过。平板独立测试包 17 项全过，含本机 4 MiB、宿主 HTTP + 非可寻址管道 16 MiB 往返、大文件 hash、取消/撤权；不代表商业云盘已通过。夹具与仪表包同 UID；接线时用 `AttachmentStreams.createStream/copyTo` 替换探针测试存储，验证跨 UID 的系统 picker 授权与撤权、正式 App 导入导出与双真机。按分支内 `tools/s2/s2-07-cases.tsv` 跑适配轨道；测试 HTTP 服务为 `tools/s2/saf-object-fixture.mjs`，使用独占 ADB reverse，测试参数 `s207CloudPort`（默认 18765）。第三方 provider 的原子持久化不作为 SAF 回读验收结论。
- **S2.08**：本地 `codex/s2-08-prep` `f7f6069f1559276d826908b8c8126e759e34e594`。已备 Anki 媒体/聊天附件私有根安全扫描、文件清单、冻结 ready 队列的墓碑视图、活动/可恢复引用报告、专属临时文件清理；40 项模块 JVM、平板 3 项隔离用例全过。无引用不等于可回收：快照不能证明未观测 owner 的引用，按策略保留共享对象，只清理明确归属的 scratch；没有发布远端墓碑。前置通过后在 App 按生命周期装配 `AssetLedgerService`、`ActiveAssetScanner` 与冻结 VFS/chat SQLite 读适配，再做正式两机验收。冻结桌面独立明文/DSBK 资产样本也已复跑；不将其计作两端 S2.08 同步往返。


## S2.06 正式签收：V2232A 同候选复验

- 记录时间：2026-11-21T00:36:30+08:00（Asia/Shanghai）。主线程核对 rules、置顶 §0、计划与前置签收；只连接 V2232A（ARM64/API 36）。直接安装 iPA2475 已通过的同三 APK，未重打包，安装后各哈希与回执一致。集合 SHA-256 `f61f600cb528d30d96cd747999d873a2c64cb4139823d25e5234f221794b96cf`；选择表 SHA-256 `9765b3d1f460b133c4f120cf04766bac739777a1052881dcbc1dd52e2ee0fec4`。
- [手机证据](../logs/s2-06-main-phone.log)：13 项资源专项、1 项资源回归、4 项 App 实测，合计 **18/18，零跳过**；明文和加密 DSBK WebDAV 笔记/附件双向回归都通过（含错密后续作）。首轮手机 logcat 窗口缺资源标签，**不猜测其原因**；以同候选另跑 2 项 App 日志审计，不重复计数。审计前一次测试 APK 安装等待超时，后由系统完成；核实包字节后续跑，未观察为进程强停。补查实际日志 20 条、7 个关联操作，有完整写入及损坏拒绝轨迹，包含 owner、阶段、状态、耗时及固定原因，无名称/内容泄漏。测试包、临时 App、ADB reverse 和安装超时遗留的临时 APK 均已清理；临时测试日志标签恢复原值。无设备档位或内存门槛。
- 双机同候选、[宿主 729 JVM/18 工具以及冻结校验](../logs/s2-06-main-host.log)及约束验证完毕，`S2.06=accepted`。仅证实上述失败切点及恢复，不是断电持久性或外部任意修改的保证。`next=S2.07`；S2.G/M2、正式发布未签收。后继隔离验收证据不回填为 S2.06 证据。


## S2.07：正式资源库已接线，授权轨道尚未签收

- 记录时间：2026-11-21T01:13:59+08:00（Asia/Shanghai）。前置 S2.06 在 `9817499` 双真机签收后才接入主线；独立子智能体仅做各自单元，主线程复核、构建及单机测试。实现提交 `fd74d068bbc2aebefb72f189425271f784f7e392`。不加产品页面：`DstuApplication.storage().documentTransfers` 提供系统 OPEN/CREATE、授权句柄、私有持久读取/释放和可取消导入导出；产品页面消费留后续单元。App 与现有附件功能共用 `AttachmentService`，写入提交后不会因迟到取消假报未保存；导出只使用新建目标，按流式 SHA-256 回读确认，多出首字节立即拒绝。撤权后无法清理时如实报告 `MAY_REMAIN`，不保证已有描述符自动关闭或第三方远端原子持久化。
- [主线程宿主](../logs/s2-07-main-host.log)：764 JVM、7 工具测试通过；73 模块架构及复杂度零违规，冻结输入 535 份不变。S2.07 新增的资料位于正式端口、数据/平台适配与 App 装配，不改冻结 schema/wire。真实授权夹具仅在测试包，宿主 Activity 仅 debug、非 exported，核对 release manifest 不含它。
- [V2232A 证据](../logs/s2-07-main-phone.log)：固定候选的三个 APK 均安装成功，逐个读回的 SHA-256 与候选清单一致；17 项本机/HTTP 描述符等夹具用例与 9 项正式 App 存储装配通过。HTTP 副轨确认 16 MiB+37 B 真 HTTP PUT/HEAD/GET/DELETE 与 SHA-256，本机夹具 4 MiB+31 B；不能当成商业云盘或跨 UID 授权证据。较早同 App 与资源测试包的核心诊断候选取得 89 条实际脱敏 SAF 日志（17 条 App），已检查相关 ID、owner、阶段、状态、耗时、固定失败线索；它的 App 仪表包不含后加的宿主轨迹，两候选的通过数量不相加。
- **跨 UID 未通过验收**：同 UID 的撤权/清理不用于授权结论，已拆出独立 UID 的测试。手机第一条跨 UID 用例实际进入，最后回执是 `host.start / dispatched`，未观察到 `host.resume` 或 provider 授权回执，外层 135 秒超时；余 9 项未执行。不是未完成安装：三 APK 在用例之前已成功安装并校验，超时原因仍待定位，未观测到匹配进程退出或 `am_kill`；强停扫描超时也不证明原因。安全校验未放宽。最终 App、测试包、临时端口已清理。
- **续测先查置顶和回执**。冻结三 APK 在 `/home/xutao/.cache/dstu-android/candidates/s2-07-fd74d06`，集合 SHA-256 `14adcef893ce8f119e58d5f12bf4b2cdc6e1a247412e6f4f1619c876632c9504`、用例表 `dcd293f71c365d4e497518e2a41b2823a118842279ce211ee00e2450803a4311`。仅一台设备，保持屏幕解锁以观察系统选择器，用 Node24 在仓库运行 `node tools/s2/verify-saf-device.mjs SERIAL MODEL "$CANDIDATE_DIR" "$CANDIDATE_SHA256" "$CASES_SHA256"`；先定位手机测试宿主不恢复的问题，再对同一可签收候选在手机和平板各跑全 36 项。若修改了任一 APK，重新冻结并复跑同字节；不将旧隔离探针当作最终证据。`next=S2.07`、`pendingCandidate.status=awaiting-grant-and-tablet`，S2.G/M2 未签收。
- S2.08 额外完成隔离 App 装配（本地分支 `codex/s2-08-app-prep`、提交 `0e6c7f8c377791e3535b1b2165d23f53677d6bad`）：四项 JVM 装配通过，4 项 App 实机用例已编译未执行；Chat 租约懒开于 `active/databases/chat_v2.db`，台账借用 VFS，不发布远端墓碑。等 S2.07 正式签收后，再按该分支三份独占文件及冻结资产适配代码集成，接 `MainAssetLedgerResources` 到 `MainStorageResources` 生命周期；本轮未提前接入。


## 2026-11-21T01:41:58+08:00 - S2.07 跨应用授权超时查明，手机范围暂签

- 本轮按用户要求不使用子智能体，只验 V2232A（ARM64/API 36），一次只接一台；iPA2475 暂缓。用原候选复现：三 APK 安装成功且设备端 SHA-256 一致，进入首条跨 UID 测试后发出宿主启动请求。进程仍在、主屏亮且桌面仍在前台，该 PID 的 `cgroup.events` 为 `frozen 1`，因此宿主没有进入前台且测试内部超时也无法按时执行；不是没点安装、熄屏、进程先退出，或 provider 拒绝了本次授权请求。冻结的具体厂商规则未证实。[诊断记录](../logs/s2-07-freezer-main-phone.log)
- 修复只作用于测试链：shell 前台启动 `debug` 宿主，入口受 `android.permission.DUMP` 保护，release 合并 manifest 不含宿主或夹具。授权请求和 PFD I/O 仍由真实 App UID 发起。试验时发现厂商将只指定包的 OPEN 路由到自带文件管理器；仅仪表测试固定到已验证的系统 DocumentsUI 组件，生产 Intent 仍通用。DocumentsUI 前台时再次实测后台测试 PID 冻结，手机本轮由主机确认只选 `DSTU S207 Test Only` 的合成源文件、只保存预填的合成目标；真实 Activity result、系统 grant、流读写和哈希断言未替换。未改手机安全或冻结设置。此项不等同于验收厂商默认选择器或商业云盘。
- 撤权用例先销毁授予临时权限的宿主，随后针对独立 UID provider 的**持久授权**实做撤销，核对 provider 与 App 的持久授权状态及实际 PFD 读写被拒。不能仅以 `checkUriPermission` 的瞬时返回代替描述符打开，不把已经打开的描述符当成一定会被立刻关闭；取消后保留可能残留标记。
- **手机 36/36、零跳过**：17 项资源夹具、9 项 App 装配、8 项真实跨 UID、2 项真实系统 OPEN/CREATE；本机 4 MiB、主机 HTTP 16 MiB 往返哈希一致，HTTP 实测 PUT/HEAD/GET/DELETE。132 条 SAF 授权/传输日志经脱敏检查。主机 764 JVM、8 项工具回归、73 模块架构与复杂度零违规、535 项冻结输入未变。[主机](../logs/s2-07-phone-signoff-host.log) · [手机](../logs/s2-07-phone-signoff-device.log)
- 实现 `22b3caf7ed3fc06686feaa035ff813aae247f078`；候选 `/home/xutao/.cache/dstu-android/candidates/s2-07-22b3caf`，集合 SHA-256 `8acb779b4b54a939b661e6bae258bbd5d96e52ad34a762ebbd8930a3d71d84d7`，36 项用例表 SHA-256 `dcd293f71c365d4e497518e2a41b2823a118842279ce211ee00e2450803a4311`。将来只接平板，用 Node24 执行 `node tools/s2/verify-saf-device.mjs SERIAL iPA2475 "$D" "$(cat "$D/candidate.sha256")" "$(sha256sum "$D/cases.tsv" | cut -d' ' -f1)"`，`D` 为上述目录。默认自动交互；若重现选择器前台而测试进程冻结，同一候选可加 `DSTU_S207_MANUAL_PICKER=1` 由主机点合成文件和保存，须记录交互范围与结果。
- `phone-accepted` 只满足本轮用户指定的手机范围（据此再接入 S2.08 手机验证）；不是正式双机 `accepted`。`next=S2.07` 仍指正式队列中最先待补的平板门禁，S2.G/M2 和正式发布未签收。


## 2026-11-21T01:46:14+08:00 - S2.08 接入资产台账并暂签手机

- 在 S2.07 手机范围回执已提交 (`b650476`) 后，接入隔离准备的资产台账。`DstuApplication.storage()` 由 `MainStorageResources` 暴露 `AssetLedger`，与已有附件和 SAF 端口共用 VFS；首次跨库引用查询才持有 `active/databases/chat_v2.db` 租约，关闭台账后再关 VFS，不误关其它持有者的租约；未新增 UI、未改冻结迁移、未接后续远端发布。
- 已接入桌面合同兼容的逻辑资产键/清单、VFS 删除队列与日志读取、待发布墓碑快照、VFS/聊天活动与可恢复引用报告。不能完整观察的引用不作为回收依据；仅删除 S2.08 自己命名、加锁保护的临时常规文件，保留共享资产/blob、外来文件以及符号链接。扫描不跟随链接，文件读取失败和损坏聊天库不伪报空引用。
- **手机 9/9、零跳过**：3 项真实数据库/文件系统契约、4 项 App 装配及 2 项 S2.07 回归（附件往返、跨 UID 持久授权撤销）；覆盖多引用、删除/恢复后可打开、重开、只读观察冻结队列、清理边界、无权限与损坏库失败。实际 17 条台账日志含关联 ID、owner、阶段、状态、耗时、固定结果，无 URI、路径或业务载荷。主机 788 JVM、5 项工具回归、73 模块架构与复杂度零违规、535 份冻结输入不变，S0 只读重放 25 通过。[主机](../logs/s2-08-phone-signoff-host.log) · [手机](../logs/s2-08-phone-signoff-device.log)
- 实现 `908a4cb138a50a7f4a111e21080a013ad70fa117`；候选 `/home/xutao/.cache/dstu-android/candidates/s2-08-908a4cb`，集合 SHA-256 `445dea756d19964c5bf2ec6bb48908bd45f270822a0f4cfbcaf6a0e4989cd63e`；用例表 SHA-256 `92f4eab675c6aac6ddd53cd364a0be48214965dbc569cf55c472c86450de3f83`。日后补平板，同候选只接 iPA2475，以 Node24 在 PATH 中执行 `bash tools/s2/verify-resource-device.sh SERIAL iPA2475 S2.08 "$D" "$(cat "$D/candidate.sha256")" < "$D/cases.tsv"`，`D` 为上述目录。
- 本轮未用子智能体、未跑平板。两单元均为手机 `phone-accepted`，正式 `next=S2.07` 仍待平板，S2.G/M2 未签收。已卸载本轮临时 App 和测试包并确认无 ADB 转发残留；清理 78 个构建/会话目录及本轮过程记录，只保留交接、终验日志和仓库外冻结候选；保留用户原有 rules 修改及 S1 截图删除，不纳入本轮提交。


## 2026-11-21T02:08:35+08:00 - S2.07 平板同候选通过，双真机签收

- 只连接 iPA2475（ARM64/API 36），不启用子智能体。平板复用手机已通过的 `22b3caf` 三 APK 和 36 项用例表（集合 SHA-256 `8acb779b4b54a939b661e6bae258bbd5d96e52ad34a762ebbd8930a3d71d84d7`），不重新打包，实装读回哈希均一致。最终完整跑 **36/36、零跳过**：17 项资源、9 项 App、8 项真实跨 UID、2 项真实系统文件选择。4 MiB 本机与 16 MiB HTTP 读回哈希一致，服务端实测 PUT/HEAD/GET/DELETE；不是商业网盘实测。两个系统选择用例经主机确认只操作合成源和目标，Activity result、App UID grant 和 PFD 校验均由系统与实际 App 完成。159 条结构化授权/传输日志核查无 URI 或业务载荷。[平板终验](../logs/s2-07-main-tablet.log)
- **Root 取证复现旧候选，不计验收**：`01:57:06.531`（设备时钟）原宿主启动有 `BAL_ALLOW_PERMISSION`，但系统内部返回 `102`，没有创建/恢复到前台；约 4.7 秒后 `system_server`（UID 1000）的 `quick_frozen` 线程发起此 App PID 的 `cgroup_freeze`，内核确认 `val=1`，系统事件记 `from fast_freezer`。测试进程仍在、状态为 instrumentation 且 `cached=false`、主屏 Awake，无匹配退出/kill。这个顺序说明宿主未进入前台在先，冻结延长/阻断测试线程在后；不是安装未点、熄屏或之前平板 `com.vivo.sps:rms` 的 force-stop。未确认厂商内部拦截/选择规则，也不以平板线程身份代替手机取证。[冻结证据](../logs/s2-07-freezer-main-tablet.log)
- 终验首轮已过 34 项后，OPEN 主机点击未在 65 秒窗口内完成，该轮不计入终验。随后原字节完整复跑全过；通过轮 CREATE 等待期间也实际被冻结，主机点测试目标的保存后，系统按 `resume top activity` 解冻且收到真实结果。没有改省电、安全或冻结设置；只用过滤后的临时 tracefs 实例观察，完成后移除。后续遇 instrumentation 静默，先核对安装/启动/焦点、目标 PID 与 `cgroup.events`，再看退出记录；root 设备可用只针对目标的内核冻结/解冻事件区分发起者与结果，避免把 App 前台切换、冻结与进程死亡混写。
- 独立复跑 8 项工具测试；535 份冻结输入及计划校验通过。普通平板 App 保留原数据，两个测试包、ADB 临时通道和诊断实例均已清理。S2.07 `accepted`，正式队列 `next=S2.08`，随后在同一平板使用该单元自己的冻结候选；未签收 S2.G/M2。


## 2026-11-21T02:09:43+08:00 - S2.08 平板同候选通过，双真机签收

- S2.07 双机正式签收并提交 `aa4c98d` 后，仍只连接 iPA2475（ARM64/API 36），继续本轮不启用子智能体。复用手机验过的 `908a4cb` 候选三 APK（集合 SHA-256 `445dea756d19964c5bf2ec6bb48908bd45f270822a0f4cfbcaf6a0e4989cd63e`）；逐包实装读回哈希、固定 9 项用例表与手机一致，未重包。平板 **9/9、零跳过**，和手机证据合并签收。[平板终验](../logs/s2-08-main-tablet.log)
- 3 项资源层与 4 项 App 台账用例覆盖真库多引用、删除与恢复可读、重开、台账租约、只读墓碑/删除日志、拒绝不安全名字与不可读项、坏聊天库不伪报空引用、只清理自有临时常规文件；2 项 S2.07 回归验证附件导入/导出读回和跨 UID 持久授权撤销。审阅 17 条脱敏台账日志，包含正常与拒绝结果。继续保留共享资源与 blob，不以该结果宣称已发布远端墓碑。
- 本轮未改普通 App 代码、测试代码、冻结契约或设备的冻结/安全设置；普通平板 App 原位更新至已验收的 S2.08 候选，不清除应用数据，测试包、临时转发、tracefs 实例及主机的过程文件均清理。用户原有 rules 修改和 S1 截图删除不纳入提交。两单元已 `accepted`，正式 `next=S2.09`，尚未开工 S2.09；S2.G/M2 与正式发布仍未签收。


<a id="s2-background-freezer-triage"></a>

## 2026-11-21T02:17:26+08:00 - 后台冻结分类：已知系统行为，不阻塞当前单元

- 按用户确认，S2.07 在平板复现的 `fast_freezer` 是已知后台行为。仅见后台 App PID 冻结、回到前台正常解冻，或旧测试宿主因此超时，不单列产品缺陷，不阻塞下一单元 S2.09，也不重开 S2.07／S2.08。现有测试启动与选文件策略只保障验收路径，不代表生产 App 已具备全场景后台保活。[取证日志](../logs/s2-07-freezer-main-tablet.log)
- 按原计划在 **S2.19–S2.22** 完善任务恢复、适用的前台服务与通知、用户常驻和按检查点续作；届时复用本例，验证真实任务切后台后是否继续、系统停止后能否恢复或明确提示重试，并检查半成品和重复写入。**S9.11** 再做锁屏、厂商后台策略与耗电矩阵。Operit 的透明悬浮窗只作内测对照，不作为正式保活前置；不以关闭系统冻结或要求 root 作为通过条件。
- 归类边界：若回前台后实际功能仍失败、已有明确后台继续承诺的任务停滞且无法恢复，或发生数据丢失、重复写入、部分结果误报成功，应按具体的功能症状登记新缺陷并独立取证。本文不豁免 S2.09 的切槽故障及原验收条件，不改变冻结执行队列、签收回执或阶段出口。

## 2026-11-22T12:25:39+08:00 - S2.09 DataSpace 已接线，平板通过，手机待同候选复验

- 前置 S1.G、S2.01–S2.08 均已验签。S2.09 子智能体只实现 `:platform:storage` 和模块测试，主线程接 App、独立验收与提交。实现 `81961e9`。`FileDataSpace` 提供活动槽共享租约与跨进程排他的维护租约；准备、校验和同步新槽后原子替换唯一活动指针，原 `files/active` 与已激活旧槽保留。状态不明时不猜槽、不清空，通过脱敏代码提供保全诊断与重新检查；无本单元用户发起恢复的入口（覆盖预览及实际恢复分别属于 S2.10、S2.11）。App 的 VFS、附件、同步 blob、资产扫描、迁移入口和 runtime/script 都使用同一活动根；wire 逻辑资产键仍是 `active/...`。
- [宿主结果](../logs/s2-09-main-host.log)：802 JVM 零失败/跳过（独立计数，不重复计 release），含 14 个 DataSpace 测试、独立进程锁竞争、损坏/遗失指针保全、同操作重入；架构与复杂度通过（74 模块），535 冻结输入未改，APK 身份与对齐通过。 [平板结果](../logs/s2-09-main-tablet.log)：iPA2475 API 36 共 22/22（真实库/附件/资产 Compose、诊断界面和原功能回归）及 7/7 硬终止后重开，零跳过；指针替换前 4 个切点读完整旧槽，替换后 3 个读完整新槽且旧槽仍在。验证的是合成根上的进程终止，不是掉电或在用户数据上恢复。平板仪表出现的后台冻结按既定分类，从桌面拉回 App 前台即解冻并完成；未调整设备设置。两个测试包已清理，普通 App 原位保留。
- 仅连接 V2232A 后，用原候选目录 `/home/xutao/.cache/dstu-android/candidates/s2-09-81961e9` 复验，不重编候选：`bash tools/s2/verify-dataspace-device.sh SERIAL V2232A "$D" "$(cat "$D/candidate.sha256")"`。集合 SHA-256 `28a8ea15dd708549bca2287fe5a51b04fa28ff53666db9c813b93631b1ef888a`，逐 APK 与 6 组常规加 7 个硬终止用例表摘要在 [待签回执](../config/stages/s2-progress.json)。`next=S2.09`，不是双机正式签收，S2.10/S2.11 仍隔离。

## 2026-11-22T12:33:35+08:00 - S2.09 同候选双机签收

- V2232A（API 36/ARM64）用了同一 S2.09 候选集合 `28a8ea15dd708549bca2287fe5a51b04fa28ff53666db9c813b93631b1ef888a`，3 个 APK 实装读回与 13 行固定验收表均一致，**22/22 仪表、7/7 硬终止重开，零跳过**。指针替换前后只可见完整单槽，旧槽保留；日志核对关联 ID、阶段、耗时和维护竞争码。仪表后台冻结拉到前台后完成，不改系统策略、不另登记产品缺陷。[手机终验](../logs/s2-09-main-phone.log)
- 手机开始前无普通 App，已清除本轮临时安装的三个包；平板原有 App 保留。主线程签 S2.09，正式 `next=S2.10`；此前 S2.10/S2.11 未接入主线。S2.G / M2 未签收。

## 2026-11-22T12:42:36+08:00 - S2.10 v3 备份检查已接线，手机通过，待平板同候选

- S2.09 在 `783375b` 正式签收后接入 `55c0bdf`。独立 S2.10 子智能体实现纯规划和只读 ZIP；App 工作区的「备份检查」经系统文档选择器读取一次私有副本，显示 21 个域的状态、空间下界和固定诊断。Full 不等于已恢复；冻结桌面 `validateForSlotRestore` 拒绝 partial 替换整槽，Android 同样只预览 partial。未受信任／无 Android 目标的域不会自动写入。
- 规划用冻结桌面 `e6b2657` 生成的小型 full/partial 清单和两份 S0 冻结 ZIP；后者携带需要另行信任的审计域，不得自动暂存。校验真实字节而不是自报 hash；拒绝越界、重复、跟链、空间不足和缺域。ZIP 适配使用固定 Commons Compress 1.27.1；额外在加载第三方中央目录前检查实际记录条数与 16 MiB 目录上限（包括 ZIP64），manifest 读取上限 16 MiB。
- [宿主](../logs/s2-10-main-host.log) 826 JVM（该单元 24：15 planner、9 ZIP），零失败/跳过，架构和复杂度 77 模块、535 冻结输入与 APK 身份均通过。[V2232A](../logs/s2-10-main-phone.log) 6/6 零跳过，包括真 ContentResolver/FileProvider 的 full/partial、哈希/缺域、冻结 ZIP 预检、fake UI 和一次 S2.09 真 SQLite/附件切槽回归。不是 UI 点击系统选择器的全流程，也不是执行恢复。5 个预检操作共 15 条脱敏日志均可串联；本轮临时 APK 已卸载。
- 候选 `/home/xutao/.cache/dstu-android/candidates/s2-10-55c0bdf`、集合 SHA-256 `7bc781ed27c5072cd7b755f8ec06305c60dd1d94aa0fca5171d5ced1037a5b41`。下一步仅连接 iPA2475，用原候选 `bash tools/s2/verify-backup-preview-device.sh SERIAL iPA2475 "$D" "$(cat "$D/candidate.sha256")"`，不重包；正式 `next=S2.10`，S2.11 未接主线。

## 2026-11-22T12:45:40+08:00 - S2.10 同候选双机签收

- 改接 iPA2475（API 36/ARM64），复用 V2232A 验过的 S2.10 候选 `7bc781ed27c5072cd7b755f8ec06305c60dd1d94aa0fca5171d5ced1037a5b41`；两个实装 APK 摘要及用例表相同，**6/6、零跳过**。平板再次复核 5 个预检操作的 15 条关联日志，无源 URI、路径、文件内容；普通 App 原位保留，仪表包卸载。[平板终验](../logs/s2-10-main-tablet.log)
- S2.10 正式签收，`next=S2.11`。S2.10 仅显示备份内容、空间下界和检查结果；没有确认执行或切槽操作。S2.G/M2 与发布未签收。

## 2026-11-22T13:01:58+08:00 - S2.11 明文全量恢复接入，平板通过，待手机同候选

- S2.10 正式签收 `8e002be` 后才接入 S2.11。独立子智能体负责执行服务和模块测试；主线程接 App 冷启动屏障、确认/状态页和实际库、资源校验。用户先预检并显式安排，私有 ZIP 同步落盘后才发布预约；下次冷启动、业务槽租约获取前执行。复核清单与全部受计划约束的大小/哈希；排他租约内复制需保留的本地内容，逐文件写暂存，校验四库迁移历史、SQLite 完整性／外键和 VFS blob 路径/字节；失败保全活动槽，成功切槽并留旧槽。同一操作 ID 重入不重复切换。
- 范围：明文 v3 full、未绑定同步身份的安装。局部备份仍仅预览；已登记同步身份的安装不走本轮普通切槽流程，身份与同步基线按 S2.15；旧移动包适配 S2.12、加密纵切 S2.14。排队时不强停 App，也不调整系统冻结或安全设置。
- 首轮真机确认两处适配问题：App UID 的挂载点 `Files.getFileStore` 查询抛 `SecurityException`（独立探针，私有目录 `File.usableSpace` 可用），以及数据库公开读端口只接收既有无参只读 `PRAGMA`。修复并重冻候选 `0da36cf`，未改 SQLite 端口规则或冻结迁移。16 MiB 清单上限与 S2.10 一致；允许 S2.04 共用修复后的 vfs schema head，保留 S0 原字节。[宿主](../logs/s2-11-main-host.log)：836 JVM，全通过（恢复服务 10），78 模块架构/复杂度通过，535 冻结输入未变。
- [平板](../logs/s2-11-main-tablet.log) 9/9 常规、3/3 真实外部 SIGKILL 后不同 PID 重开，零跳过。真四库和真实 VFS 附件解包后可读，未送达 blob 的恢复被拒绝且原笔记仍在；WAL保全/读回、确认及状态 UI 和 S2.10 回归也通过。解包写入、暂存写稳、指针替换三个切点分别读旧、旧、新，复用原 opId 后最终新槽且旧槽留存，仅进程终止验证。App 复跑用 cache 临时用户数据，非在普通数据上恢复。两个测试包已卸载，平板普通 App 原位保留。
- 候选 `/home/xutao/.cache/dstu-android/candidates/s2-11-0da36cf`，集合 SHA-256 `ddd300d8813ef53e1e044a287eaa1714bd68e0378d6f129f279a41f9d8819d04`。只连接 V2232A 后，用 `bash tools/s2/verify-backup-restore-device.sh SERIAL V2232A "$D" "$(cat "$D/candidate.sha256")"` 复验，保留原字节。`next=S2.11`，该单元未正式签收，S2.12 未开工。

## 2026-11-22T13:13:20+08:00 - S2.11 同候选双机签收；S2.09–S2.11 收尾

- V2232A（API 36/ARM64）复用平板候选 `ddd300d8813ef53e1e044a287eaa1714bd68e0378d6f129f279a41f9d8819d04`，三个实装 APK 和用例表读回一致；**9/9 仪表、3/3 外部 SIGKILL 重开，零跳过**。两台均见切槽前旧槽完整、替换后新槽完整、同一操作只激活一次。手机固定窗口的 runner 输出未带恢复日志，补读同轮 logcat 得到 42 条成功与坏资源拒绝日志，关联 ID、阶段、状态、耗时、固定原因完整。普通用户数据未用作恢复夹具。系统安装提示确认后继续，测试包按所有权清理；测试中的已知后台冻结前台恢复，无系统配置改动。[手机终验](../logs/s2-11-main-phone.log)
- S2.09、S2.10、S2.11 三次按前置签收串行接入，已分别签收并本地提交；逐单元候选、日志为最终证据，未推送。清理临时工作树、隔离测试候选、构建中间物与过程日志；用户原有 rules 和 S1 截图删除保持。下一单元 `S2.12`（旧移动备份导入），本轮未开工；S2.G / M2 未签收。

## 2026-11-22T20:35:18+08:00 - S2.12 旧移动备份导入：实现、主机与平板复验

- 开工和接入均核对 rules、置顶清单、有效计划 §4.1／§4.2、冻结队列及回执；S0.16、S1.G、S2.01–S2.11 已签收。同单元两名专属子智能体分管 Kotlin 后端和真实 fixture 测试，主线程负责接入、审查、独立测试与提交。实现 `803df52`。
- 产品入口仍是「备份检查」。旧 OPFS 导出须明确声明 mobile / v3.0.0 / full / not_present，清单和 ZIP 文件一一核对；核验大小、SHA-256、coverage、四库版本和 SQLite 文件头。报告显示声明的来源/版本、四库和附件计数、旧审计处理。旧审计字节只随槽存入 `legacy-import/audit.db`，不启用、不替换本机审计；桌面及其他信任域门禁不改变。
- 确认只预约，不切槽。复核并固化私有 ZIP 后，下次冷启动复用 S2.11 `RestoreExecutor` 和唯一 DataSpace 指针；候选独立校验治理历史和源版本一致性、SQLite 完整性/外键、笔记哈希及文件/附件引用。ZIP 必须实际提供所引用的 blob，旧槽中碰巧存在的相同字节不算。成功也保留旧槽；无覆盖、局部、增量、加密或不可信域没有恢复资格，两份 ZIP 均仅作普通数据导入，sidecar 与同步身份迁移归 S2.15，已绑定安装仍不允许普通切槽。
- 主线程主机复验 853 JVM（旧包预检 15、备份规划模块合计 30、恢复模块 12），78 模块架构/复杂度零违规、计划测试 26、535 冻结输入校验。原 standard/continuity ZIP 与 sidecar 只读回放、测试 APK 内字节对拍；正式 App APK 不携带样本。平板 iPA2475 / API 36：同候选 19 项通过、0 跳过，含 8 项旧包用例，两份 ZIP 依真实 `ContentResolver` / `FileProvider` 经生产队列和恢复链回读 19 个业务投影以及 UTF-8 笔记、附件字节、S2.04 DELETE 修复与旧槽。源历史不符或 ZIP 缺 blob 均有同操作 ID 的拒绝日志，未激活。另通过 3 个 S2.11 共享执行器硬终止重入回归（桌面形态 fixture）。
- 首次平板尝试：屏幕 Awake、启动器前台，系统 fast freezer 冻结测试 PID；拉回前台同一 PID 继续跑，原来的超时不计入验收。最终仪表包在备份数据用例复用只存在于 debug 的 SAF 前台宿主；App APK 字节及系统设置不变，不列为产品缺陷。最终日志：[主机](../logs/s2-12-main-host.log)、[平板](../logs/s2-12-main-tablet.log)。
- 已冻结 `/home/xutao/.cache/dstu-android/candidates/s2-12-803df52`，集合 SHA-256 `f1488288a9f391a1266ff937a2bab791ae0eab03f9d640d3185b327b4568a707`。下步只连接 V2232A，用 `bash tools/s2/verify-backup-restore-device.sh SERIAL V2232A "$D" "$(cat "$D/candidate.sha256")" S2.12` 复验同字节；`next=S2.12`，正式签收与 S2.13 接入仍待手机。此回执不是旧过渡包正式覆盖安装、旧端系统保存或 S2.G / M2 签收。

## 2026-11-22T20:44:22+08:00 - S2.12 同候选双真机签收

- 当前只连接 V2232A / API 36 / arm64-v8a。主线程用已签平板的候选 `f1488288a9f391a1266ff937a2bab791ae0eab03f9d640d3185b327b4568a707` 复验，未重打包；确认三份安装 APK 及用例清单 SHA-256 与候选一致。手机 19/19 项、0 跳过（其中 8 项旧包用例）；原 standard 与 continuity ZIP 均以普通数据方式导入，四库、UTF-8 笔记和附件引用回读一致；确认前无切换，成功后旧槽仍在，旧审计不替换本机审计。真实版本不符和 ZIP 缺附件拒绝并保留原活动槽。
- 两机各通过 3 个 S2.11 共享执行器硬终止重开回归：`ENTRY_WRITTEN`／`STAGING_DURABLE` 读旧槽后同操作重入，`POINTER_REPLACED` 读新槽且不重复切换。两机实际日志均用同一 `correlationId` 串起四库、引用与激活，两个拒绝不发生激活；日志含 owner、阶段、状态、耗时与固定错误码。手机三份临时安装包已移除。证据：[手机](../logs/s2-12-main-phone.log)、[平板](../logs/s2-12-main-tablet.log)、[主机](../logs/s2-12-main-host.log)。
- 主线程签收 `S2.12`，`next=S2.13`，本轮未开工 S2.13。S2.G／M2、旧端正式过渡包覆盖安装及系统交付、同步身份接管均不在此回执内。用户已有 rules 修改与 S1 截图删除保持，本轮不暂存、不推送。

## 2026-11-22T21:41:07+08:00 - S2.13 手机候选回执；S2.13–S2.15 改为批次换机

- 前置 S1.23、S1.G、S2.01–S2.12 已签收。S2.13 专属子智能体负责 platform/security；主线程接同步配对入口、独立设备用例、审查与本地提交。实现 `d09f34f`。DSBK 路由 v1/v2，不改变桌面 wire；预检含文件长度、输出、Argon2 与分块预算，头校验不是认证。错密、换块、截断不提交私有暂存；大 v1 给单独转换条件。资源预算按操作，不设设备档位。
- Keystore 无副作用探测不会在缺失别名时新建密钥。永久丢失才显示「重置密钥并重新配对」；瞬时或无法确认的错误不授权删除。经用户确认后锁内复核，清旧凭据、建立新主密钥，再清本机同步配置；保留设备 ID、业务数据和待同步记录。重建失败仍拒绝连接，不承诺恢复已无法解密的旧凭据。实际配对后继续使用原设备 ID 和仓库实例。
- [主机](../logs/s2-13-main-host.log)：868 JVM、78 模块架构和复杂度零违规、26 项计划测试、535 冻结输入；真实 Argon2 + 冻结桌面 v1/v2 向量正反对拍。 [手机](../logs/s2-13-main-phone.log)：同一组装包的四份实装 APK 校验一致；V2232A 23/23（平台 10、Compose 7、App 6），零跳过。真实 Keystore 与失效→确认→本机重置→WebDAV 加入；交换块／截断／错密不写入原私有目标。日志核对关联 ID、owner、阶段、状态、耗时和固定错误码，无密钥或密码哨兵。临时安装包已清理。
- 冻结候选 `/home/xutao/.cache/dstu-android/candidates/s2-13-a42a4d3`，集合 SHA-256 `c069eecc09354e902ae94e33ce3e80777d026c0423d29679172aac1ec53ffcf8`。按用户最新安排，保持同一台手机，系列开发及手机验证后再换一次平板；先不换机。S2.13 尚未双机签收，`next=S2.13`。S2.14、S2.15 仅在隔离分支开发/候选验证，禁止提前接入正式主线；换为平板后按依赖顺序复验同一候选并签收。

## 2026-11-22T22:31:03+08:00 - S2.14 隔离候选：放宽整包拒绝规则，手机复验通过

- 用户调整：加密包有桌面密钥、脚本、桌面审计或没有 Android 恢复目标的域，不再拒绝整包。通过密码认证后，仍校验原始清单、覆盖账本、路径、大小和每个文件实际 SHA-256（包括不导入的文件）；只有 Android 已有恢复路由的数据进入暂存槽。其余标记「保留在原备份；不导入或启用」，不写进 Android 数据槽，也不修改用户选的原文件；局部／不完整快照不替换数据槽，普通明文和旧移动包原有入口不变。
- 「加密备份」可打开裸 DSBK 或冻结桌面密码 ZIP。整包加密输出 DSBK v2；另有「生成桌面兼容 ZIP」，提示仅敏感子包加密。预算内的 v1 经同一个恢复执行器，较大裸 v1 使用 `tools/s2/s2-14-desktop-interop.mjs convert-v1` 显式认证、转 v2、回读哈希后发布。用户确认只预约，下次冷启动仍由唯一的 DataSpace 执行器校验并切槽。
- 隔离分支 `staging/s2-14-20261122`，实现提交 `b5c8f39`；主线程复核后保存 [主机](../logs/s2-14-main-host.log) 和 [手机](../logs/s2-14-main-phone.log) 日志：891 JVM、78 模块架构/复杂度零违规、13项冻结桌面 oracle，手机 V2232A 18/18、零跳过。桌面 v2 及兼容 v1 测试包在 Android 冷启动回读 UTF-8 笔记和附件；混合包恢复数据且密钥/脚本不进入活动槽，错密不排队。Android 的实机 v2 兼容 ZIP 由冻结桌面的正式 ZIP 导入端口认证、检查四库，并与独立输入 canonical SHA-256 一致；未宣称执行桌面完整槽激活。
- 候选 `/home/xutao/.cache/dstu-android/candidates/s2-14-b5c8f39`，集合 SHA-256 `8e2d8741e1345c133346a2a1f0612f8b83e50f47650c0746d0f181860ab4d27a`。手机临时安装与语料已清理，待同一系列准备完成后一次换 iPA2475。平板命令：`bash tools/s2/verify-encrypted-backup-device.sh SERIAL iPA2475 "$D" "$(cat "$D/candidate.sha256")"`（在 S2.14 隔离分支或完成前置后已合入的源码执行；Node 24）。本阶段仍 `next=S2.13`；S2.14 不提前合入/签收，S2.G 未签收。

## 2026-11-22T22:37:48+08:00 - S2.15 隔离候选：普通恢复身份与 sidecar 合同手机过测，连续接管未开放

- 按批次换机安排，独立准备分支 `staging/s2-15-20261122` 的候选 `8dcc1f4` 基于已过手机的 S2.14 隔离提交组合，仅测试，不提前合入 main。三个专属子智能体分别完成 S2.15 的 sidecar 验证、SQLite 导入事务和排他 claim；主线程接普通恢复入口、审查测试及冻结候选。
- 普通恢复复用原执行器；在候选 VFS 上清理消费/墓碑/绑定基线，原活动槽不变。通过校验并提交指针后才解绑旧同步配置、以持久化操作 ID 轮换本机 writer；同一操作重进不换第二次，保留旧槽；先前配对的安装会看到重新配对提示。验证失败不改绑定、writer 或原槽；已提交但身份写入失败会提示核查，不用旧身份开始同步。
- 连续迁移是另一条路径。sidecar 与独立提供的归档哈希、清单、源安装、实例、writer 及状态绑定；SQLite 合同用真实驱动验证序列/游标/水位、原子回执与拒绝重复。隔离的远端 claim 测试验证条件创建和相同操作重入，不是生产接管。冻结 sidecar 不证明旧 writer 停止或未推进；真实仓库仍缺可信旧写者撤权/序列证明及原子排他能力证明，App 尚未开放原身份连续接管，不能以用户自填、客户端读回或模拟端口代替。
- [主机](../logs/s2-15-main-host.log)：949 JVM，全通过；78 模块架构和复杂度零违规，535 冻结输入未改。[手机](../logs/s2-15-main-phone.log)：V2232A 上 17 项数据合同（包括受控证明下的 SQLite 测试）和 2 项 App 普通恢复，零跳过。实际日志同一关联 ID 先记录候选身份事务，再记录切槽成功，最后记录 writer 轮换；坏包未改变原槽与配置。手机临时包已经清理。
- 隔离候选 `/home/xutao/.cache/dstu-android/candidates/s2-15-8dcc1f4`，集合 SHA-256 `33e5574a7a9a64fa6e2f8abf2469de6b5214c0f9635c84a0984dc31ef832e860`，只作为上述范围的验证证据。`next=S2.13` 不变；S2.13 和 S2.14 待同候选平板复验，S2.15 须补实仓库的可信交接路径后验收。按用户要求，系列未就绪前先保持手机连接、不分轮换设备；S2.G 未签收。

## 2026-11-22T23:32:42+08:00 - S2.15 托管 WebDAV 连续迁移已接通；同一手机完成 S2.13–S2.15 批次

- 规则与前置：主线程核对置顶、有效计划 §4.1/§4.2、冻结队列及回执；S2.13、S2.14 正式结果仍待平板，因此 S2.15 代码只提交在 `staging/s2-15-20261122`，不合入 main。三个本单元专属子智能体分管签名授权、SQLite 候选身份事务、本机同步绑定；主线程接入口、独立检查和运行设备。实现组合 `1c9719f`，最终候选提交 `bcaf26b`。
- 用户可在「备份检查 → 连续迁移」查看本机安装 ID 和持久化申请 ID。先用新凭据配对到原仓库，选择旧移动 ZIP、v1 sidecar、管理员授权正文/签名、公钥，并输入从独立可信渠道取得的公钥 SHA-256 指纹。验证绑定精确归档/sidecar 哈希、源安装、writer、仓库实例、请求与期限；完整 peer 清单用于核对设备别名。预览不占仓库、不切槽，确认才预约；普通恢复不需要这些授权材料，仍走轮换新 writer 的路径。
- **仓库前置：**管理员须实际、永久撤销旧写者访问（含共享凭据时废止旧凭据），核对源快照及远端序列，并保证 `dstu-management/continuity-claims/v1/` 支持持久原子条件创建且既有 claim 不可被普通客户端覆盖/删除。工具 `node tools/s2/prepare-continuity-grant.mjs --help` 根据独立管理员批准记录与实际包字节准备授权正文；管理员离线使用 `openssl dgst -sha256 -sign ADMIN.pem -out AUTH.sig AUTH.grant` 签发，再独立传递 DER 公钥及 SHA-256 指纹。工具和 App 不替任意 WebDAV 服务器修改权限；管理员签名是受信的运营证明，不是 sidecar 自证。支持既有 Android HTTPS WebDAV 配对的托管仓库，不对裸 sidecar 或其他 provider 自动接管。
- 冷启动复验授权与远端实例、旧 writer 最大已发布序列、加密标记及条件写读回；同一申请不确定时保留待处理证据，不删除远端占位。复用唯一 RestoreExecutor，候选 SQLite 同一事务写入序列、游标、墓碑、回执及本机随机见证；只有数据槽激活且该回执在实际活动槽读回，才发布本机旧 writer。重新配对只允许加入原 URL 与 instance，普通恢复仍可解除连续绑定。
- 主机 [结果](../logs/s2-15-managed-main-host.log)：1000/1000 JVM，78 模块架构与复杂度零违规，4 项签发工具、26 项计划测试、535 冻结输入。手机 [结果](../logs/s2-15-managed-main-phone.log)：V2232A 38/38、零跳过（29 真 SQLite、2 普通恢复、6 托管仓库、1 Compose）。通过受控 TLS WebDAV 的真实 HTTP 客户端验证旧账号被拒、归档/sidecar 匹配后真实冷切槽、恢复旧 writer 和序列 5、后续完整同步用同 writer 发布序列 6。对第二安装接管、过期签名、篡改签名、远端序列前进均拒绝，保留原槽；另注入切槽后、本机发布前故障，重新打开按持久回执续作，非外部 SIGKILL。受控仓库与模拟管理员并非用户生产服务器；本次未操作用户仓库或权限。实际日志的同一关联 ID 串起远端 claim、SQLite 提交、切槽、回执读回、writer 发布，无密码或地址。
- 最终冻结候选 `/home/xutao/.cache/dstu-android/candidates/s2-15-bcaf26b`，集合 SHA-256 `6884bd0d55a624c9379e711a38ba1930aa0195d95865c22c7bcd04d1c7a45f72`；设备三份临时包与测试 TLS 语料移除。S2.13（23）、S2.14（18）、S2.15（38）均已完成本批次手机验证，现在可以**只换一次平板 iPA2475**，按 13 → 14 → 15 顺序对三套各自同字节候选复验。S2.15 在隔离分支可执行 `bash tools/s2/verify-backup-identity-device.sh SERIAL iPA2475 "$D" "$(cat "$D/candidate.sha256")"`。主线 `next=S2.13`、S2.G 未签收，逐项验签后才按依赖顺序合入。没有重置设备安全或冻结设置。

## 2026-11-22T23:39:20+08:00 - S2.13 原封候选双真机签收

- 主线程核对 S1.23、S1.G、S2.01–S2.12 已签收；只连接 iPA2475（API 36、ARM64），未换回手机或重打包。平板安装后四包哈希及用例清单与手机候选一致，集合 SHA-256 `c069eecc09354e902ae94e33ce3e80777d026c0423d29679172aac1ec53ffcf8`，实现 `d09f34f`、测试候选 `a42a4d3`。签收证据：[主机](../logs/s2-13-main-host.log)、[V2232A](../logs/s2-13-main-phone.log)、[iPA2475](../logs/s2-13-main-tablet.log)。
- 两机各 **23/23、零跳过**（10 Keystore/KDF、7 Compose、6 App）。平板以真实 Keystore 验证密钥丢失、经确认重建并重新加入原仓库，保留原设备 ID 和本地数据；错密、换块、截断均不提交。实际日志中配对和 DSBK 各带关联 ID、阶段、状态、耗时，固定凭据哨兵未出现在日志。主机 868 JVM、冻结桌面 DSBK v1/v2 双向向量对拍；这些向量不替代下一单元的跨端全包恢复。
- 平板三份本轮测试包已移除；原有普通 App 仅覆盖安装候选，未卸载、未清除其数据。主线程签收 `S2.13`，`next=S2.14`，本次记录不签 S2.G／M2。

## 2026-11-22T23:44:57+08:00 - S2.14 加密备份互导双真机签收

- 主线程确认 S2.13 及全部队列前置已正式签收后，才从隔离提交 `b5c8f39` 复验。只连接 iPA2475（API 36、ARM64）；没有换回手机或重新打包。APK 安装读回、固定用例与夹具均匹配原手机候选，集合 SHA-256 `8e2d8741e1345c133346a2a1f0612f8b83e50f47650c0746d0f181860ab4d27a`。证据：[主机](../logs/s2-14-main-host.log)、[V2232A](../logs/s2-14-main-phone.log)、[iPA2475](../logs/s2-14-main-tablet.log)。
- 两机各 **18/18、零跳过**（4 项加密跨端，3 项冷恢复，8 项旧包，3 项 Compose）。桌面 v2 和兼容 v1 在 Android 冷启动恢复并回读笔记和附件；错密码不排队、不动原槽。混合包中的桌面密钥、脚本等经原包完整性校验后留在原包，不进入 Android 活动槽，也不被启用。
- 平板从实际内容解析器写出的 v2 桌面兼容 ZIP 经冻结桌面 `e6b2657` 的正式 ZIP 导入器认证并检查四库，数据库、清单、附件 canonical SHA-256 `472ef87a16776e7901ed2cbb327e871088bc4434009831f934879795100e506f` 与独立输入一致；手机也得到同一 canonical hash。此处证明桌面 ZIP 导入与内容，不声称桌面整槽激活。主机 891 JVM、13 项桌面 oracle；平板实际 `encryptedTrace` 核对关联 ID、阶段、状态、耗时和脱敏负例，临时测试包及语料已清理。
- 主线程按顺序合入准确候选提交，保留原有普通 App（未卸载或清除数据），签收 `S2.14`、`next=S2.15`；不签收 S2.G／M2。

## 2026-11-22T23:47:12+08:00 - S2.14 补审修正：撤回签收，保留原候选实测

- **本条更正上一条 S2.14 的签收状态。** 主线程独立得到相同候选的平板 18/18，以及冻结桌面 ZIP 导入和 canonical 内容一致。专属审查进一步发现：Android 对超预算桌面 portable v1 ZIP 提示转换，但工具只接受裸 DSBK v1；加密认证与预约、切槽采用互不关联的日志 ID，原脚本只验证全局计数；18 项没有新加密界面的专项 fake API 测试。保留原结果及 `b5c8f39` 源码与历史合入 `7bde6f9`，但不认作完整门禁签收。
- 主线程撤回 S2.14 状态，`next=S2.14`，S2.13 签收不变。S2.15 原隔离候选的平板运行只记实际复验结果，不作接入或签收；S2.14 整改后重新冻结，后续仍按顺序复验，完成这一批修正再换设备。

## 2026-11-22T23:49:51+08:00 - S2.15 原隔离候选平板复验通过，未签收

- 主线程在隔离提交 `bcaf26b` 以同一平板 iPA2475、同一手机候选集合 `6884bd0d55a624c9379e711a38ba1930aa0195d95865c22c7bcd04d1c7a45f72` 完成 **38/38、零跳过**（29 真 SQLite、2 普通恢复、6 托管仓库、1 Compose）。三份已装 APK 与受控 TLS 夹具哈希回验，`identityTrace`、`continuityTrace` 通过；受控 HTTPS WebDAV 上旧账号无法写入，真实切槽读回后用原写者从序列 **5 接续发布到 6**。普通恢复轮换、拒绝重复/过期/篡改/序列漂移、注入切槽后失败续作也通过，非生产仓库、非外部 SIGKILL。
- [平板证据](../logs/s2-15-managed-main-tablet.log) 独立于 [手机](../logs/s2-15-managed-main-phone.log)；测试包和 TLS 语料已移除，已存在普通 App 未卸载或清除数据。此处只保留隔离候选证据。S2.14 补审整改后须基于其正式候选重组，`next=S2.14`；不接入主线、不签 S2.15 或 S2.G／M2。

## 2026-11-23T00:13:30+08:00 - S2.14 补审候选平板复验通过，待手机

- 修正候选 `89c5a2c`，集合 SHA-256 `4bb6f55b5b37177b72907bb5c648da29602181c91b3cb1768103e12bb7cc7078`。仅连接 iPA2475，**28/28、零跳过**，主机 899 JVM，临时测试安装/语料已清理。证据：[主机](../logs/s2-14-audit-main-host.log)、[平板](../logs/s2-14-audit-main-tablet.log)；候选 `/home/xutao/.cache/dstu-android/candidates/s2-14-audit-89c5a2c`。
- 大号桌面 portable v1 ZIP 可用 `convert-v1` 重新封装为 v2；认证后验证原清单、覆盖及所有内容，转换前后均通过冻结桌面正式 ZIP 导入口并核对文件哈希。仅更新必要的外层清单大小/哈希和可选 checksum，不覆盖目的文件或改变输入，不导入桌面密钥/脚本。桌面端只验证隔离 ZIP 导入，不声称整槽激活。
- 复盘：主线程必须等指定独立审查归齐，再把设备结果、本单元新增门禁与源码一并核对后写最终签收。
- 同一 UUID 从认证经过持久预约延续至冷启动切槽；平板确认 4 条完整链，错密不入队。平板另验证转换包冷启动回读，以及 9 项新加密页 fake Compose（密码、重试、取消、预览、两种导出、亮暗、语义、重建）；8 项页面状态 JVM、20 项桌面工具（含超过 Android 64 MiB v1 直读上限）和 3 项日志门禁回归通过。
- 手机原候选结果仍保留，不能代替新字节候选的复验；`next=S2.14`，未签 S2.G／M2。

## 2026-11-23T00:15:09+08:00 - S2.15 补审候选平板复验通过，待手机

- 修正候选 `26420d9`，集合 SHA-256 `9f3b08cd08a043798f8bbc169aafa7ead3c0d6b623fc10101a23f84e0ee9ef52`。仅连接 iPA2475，**45/45、零跳过**，主机 1013 JVM，临时测试安装/语料已清理。证据：[主机](../logs/s2-15-audit-main-host.log)、[平板](../logs/s2-15-audit-main-tablet.log)；候选 `/home/xutao/.cache/dstu-android/candidates/s2-15-audit-26420d9`。
- 远端 claim 请求前先持久化意向；写入成功或结果不明后，激活前授权过期/校验失败保留本次待恢复材料，状态为待核查，不以已完成或有效授权处理。相同写者、相同序列的不同远端对象在接管前拒绝。普通恢复轮换后用受控 TLS WebDAV 实际重新配对，不复用旧身份或凭据。
- 受控 TLS 仓库放入有效的 v4 序列 5 测试批次；恢复后以原写者实际发布 6，进程内独立读者按 5、6 消费并回验。序列 5 不是由旧设备预先实发，读者也不是第二台设备；未访问用户生产 WebDAV。注入中断后的复验不是外部 SIGKILL；未开放自动更换已占用且过期申请的授权。本轮不接入主线，等 S2.14 手机复验签收后才按依赖顺序处理 S2.15。
- 手机原候选结果仍保留，不能代替新字节候选的复验；`next=S2.14`，未签 S2.G／M2。
- 这批平板复验已齐，下一步只换到 V2232A（保持只连一台）；不重新打包、不做设备分档。先用主线 `tools/s2/verify-encrypted-backup-device.sh` 与 `pendingCandidate.remediationCandidate` 里的目录和集合哈希复验 S2.14（28 项）。只有 S2.14 手机通过并正式签收后，才在 `staging/s2-15-audit-20261122` 的候选提交用 `tools/s2/verify-backup-identity-device.sh` 与 `isolatedPhoneCandidates["S2.15"].remediationCandidate` 的目录和集合哈希复验 S2.15（45 项）；后者双机通过且证据齐全后再合入签收。

## 2026-11-23T00:26:09+08:00 - S2.14 补审候选手机复验通过并签收

- 主线程核对 S2.13 和全部冻结前置回执，只连接 V2232A（API 36、ARM64）。未重打包或改用旧候选；准确提交 `89c5a2c`，集合 SHA-256 `4bb6f55b5b37177b72907bb5c648da29602181c91b3cb1768103e12bb7cc7078`。APK 实装读回、用例与语料哈希和 iPA2475 一致。[主机](../logs/s2-14-audit-main-host.log)、[平板](../logs/s2-14-audit-main-tablet.log)、[手机](../logs/s2-14-audit-main-phone.log)。
- 手机五组原/加密端口与页面回归加一组 9 项加密页，共 **28/28、零跳过**；备份转换后的 v2 ZIP 冷启动恢复并回读，错密不排队且保留活动槽，混合包中的桌面密钥与脚本仍留原包。真实日志同一 UUID 串起认证、持久预约和激活，4 条完整链，错密关联 ID 无入队或激活。9 项 fake Compose 覆盖亮暗、无障碍语义、取消/重试、预览与 Activity 重建。超过 Android v1 直读上限的完整 ZIP 转换为主机专项；真机恢复使用封存的小型转换样本并在隔离目录调用启动恢复入口。主机另通过 899 JVM、20 项冻结桌面工具检查和 3 项日志门禁回归。
- 手机导出由冻结桌面 `e6b2657` 正式 ZIP 导入口认证并导入，四库、清单、附件 canonical SHA-256 `472ef87a16776e7901ed2cbb327e871088bc4434009831f934879795100e506f` 与独立参考和平板一致；不声称桌面整槽激活。主线程开始时 USB 已枚举手机但 ADB 未列出；重启本机 ADB 服务后识别正常，属于宿主连线准备，非 Android 用例失败。
- 手机已清理本轮测试包与语料，本轮临时普通 App 也已卸载。主线程签收 `S2.14`，`next=S2.15`；未签 S2.G／M2。

## 2026-11-23T00:30:04+08:00 - S2.15 补审候选手机复验通过并合入签收

- 主线程核对 S2.14 和全部冻结前置回执，只连接 V2232A（API 36、ARM64）。未重打包或改用旧候选；准确提交 `26420d9`，集合 SHA-256 `9f3b08cd08a043798f8bbc169aafa7ead3c0d6b623fc10101a23f84e0ee9ef52`。APK 实装读回、用例与语料哈希和 iPA2475 一致。[主机](../logs/s2-15-audit-main-host.log)、[平板](../logs/s2-15-audit-main-tablet.log)、[手机](../logs/s2-15-audit-main-phone.log)。
- 手机 **45/45、零跳过**（29 真 SQLite、3 普通恢复、12 托管仓库、1 Compose），主机 1013 JVM。普通恢复换新写者后在受控 TLS WebDAV 重新加入原仓库，旧凭据被拒；连续迁移用原写者从有效测试批次 **5 发布到 6**，进程内独立读者对 5、6 消费回验。同写者同序列的重复远端对象被拒；可能已取得或已确认取得接管权时，响应丢失、候选校验失败或切槽前授权到期均保留待处理材料，不绕过授权或误标完成。
- `identityTrace`、`continuityTrace`、`ordinaryPairTrace` 均通过；日志串起操作、用时及脱敏故障码。序列 5 是有效 v4 受控语料，不是旧手机预发；进程内读者不是第二设备，未接触生产 WebDAV。故障用例为注入再重开，非外部 SIGKILL。主线程只在 S2.14 正式签收后运行本单元手机套件，并在两机同候选通过后将准确隔离提交并入，已核对索引中源码树与候选一致。
- 手机已清理本轮测试包与语料，本轮临时普通 App 也已卸载。主线程签收 `S2.15`，`next=S2.16`；未签 S2.G／M2。

## 2026-11-23T01:26:09+08:00 - 桌面兼容审查分流、S2 编号顺延与 rules 总目标更新

- 本轮按用户要求修订计划与规则，不开发或签收补修功能。原计划在原路径直接替换对应行，有效分层计划同步更新：新增 **S2.16 普通恢复四库同步基线**、**S2.17 恢复资源引用兼容**、**S2.18 旧加密仓库 marker 验证/升级**；原 S2.16–S2.28 顺延为 S2.19–S2.31，跨阶段前置及总队列位置一并更新。当前 305 主单元/10 出口/315 队列位置，分层责任 280 单元/9 出口，S2 共31单元。
- 当前需补修的证据：实际 SQLite 重置后旧 pending 仍被 ChangeLogReader 读取；冻结桌面正式导出/导入口接受的零引用缺失 blob 快照，在 Android 四库检查通过后被资源检查拒绝；旧加密 marker 在密码验证前直接报格式错误。三项分别回到新 S2.16–S2.18，不把已安排的未来功能或违反旧写者撤权前提的场景当成本轮缺陷。
- 后续单元直接加强原行：S2.29 多库 database+change_log_id 发布回执、同批 key/seq/ciphertext 续作、加密会话复用与流式资源/seed 合同；S2.30 跨库分事务幂等后再确认游标、恢复重配后的真实桌面往返。S8.02/10–12 大对象/弱网/取消，S8.17/22 普通换机默认新 writer 重配与高级 sidecar 分流，S8.19–21 GB级恢复/备份与 v1 转换产品化。原任务/恢复/后台设计继续按新 S2.19–S2.25 执行。
- rules 新增第12条：项目本质是 dstu-node 安卓部分原生重构，最终具有完整冻结桌面功能并与冻结桌面兼容；不采用旧 Android 运行链不等于删减桌面功能。第13条：每个新单元/功能开工，主线程及专属子智能体必须先读 dstu-node 和 Operit 冻结相关实现/调用链/测试，记录路径/合同/复用方式/差异；一侧无对应功能时记录检索范围，不臆造。原 rules 已有用户修改逐字节保留。
- 历史保护：不改 S0 队列、原卡/场景、来源/SQL/wire/夹具、S0/S1 归档或已有15条 S2签收记录。旧原计划从 Git `36511a1ba27ef2db7c45636162575a2b1c6564e8` 读取并验原哈希；当前原计划另有准确 SHA-256 和明确编号/条目修订。新补修用独立 `S2.16/remediation/implementation` 等卡，旧卡只作来源；`node tools/planning/verify-plan.mjs --query S2.16` 查询当前补修，`--query S2.19` 才查询原 runtime。进度随真正签收推进，不把修订起点固定成永远 next。
- 主线程审查、整合并独立运行规划/冻结输入验证，最终证据见 [planning-s2-desktop-compatibility-main.log](../logs/planning-s2-desktop-compatibility-main.log)。本轮只作本地提交、不推送；不动已有 S1 截图删除和 rules 旧用户改动，不生成额外过程文档。**当前 next=S2.16（修复普通恢复四库同步基线）；三项补修均 pending，S2.G/M2 未签收。**

## 2026-11-23T02:56:37+08:00 - S2.16–S2.18 本机补修验证，待真机验收

- 参考版本未推进：桌面 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87`，Operit `7a39941bad80209973c535046a18195c5da9e647`。桌面核对了 `host/plugins/backup/src/{restore-commands,backup-manager,zip}.ts`、`host/plugins/sync/repository/{change-log,apply-refcounts}.ts`、`host/plugins/vfs-repos/repository/maintenance.ts`，及相应的 `change-log.test.ts`、`regression_sync.test.ts`、`apply.test.ts`。旧标记核对 `host/plugins/sync-providers/src/{marker,password_proof,sync_manager,e2ee_claim}.ts` 及 `e2ee_claim_race.test.ts`、`host/plugins/integration/tests/sync-r12-locks.test.ts`。Operit 搜索 `app/src` 的备份/恢复与全库 Kotlin/Java/TS 的 `__change_log`、`ref_count`、WebDAV、`.encryption-marker`：最接近的是 `app/src/main/java/com/ai/assistance/operit/data/backup/{RoomDatabaseBackupManager,RawSnapshotBackupManager}.kt` 与 `RawSnapshotBackupManagerTest.kt`，只参考私有暂存、分步恢复，不采用其单设备快照协议。
- **S2.16** `sync/data/changelog/RestoreSyncBaseline.kt`：在普通恢复四个候选 SQLite 库中按表归一 `sync_version`，清除旧冲突、别名和日志（含归一触发的新日志）；不改活动槽，不清连续迁移的历史。`tools/s2/s2-16-host-roundtrip.mjs` 调用冻结桌面和 Android JVM 的正式同步代码，经本机 WebDAV 验证恢复、换新写者、继承原 instance、首次不重发旧日志、下行桌面笔记及上行新建/编辑/删除；新写者实发两批，桌面实际应用并读回。四库额外分别测实 SQLite 采集和失败重入；不把笔记端到端结果当成其他域已开通同步。新仓库全量 seed 仍与普通恢复分开，后续按 S2.29/S8.06 实施。
- **S2.17** `services/resources/data/.../assets/SqlVfsRestoreReferences.kt` 复用 S2.08 的文件与资源引用字段，并补外置资源、文件、预览原图/压缩图、整卷及题库文档中的引用；零计数、无引用、无载荷同时成立才放过缺失对象。引用存在或计数为正须由本 ZIP 提供匹配的载荷；原槽同哈希附件不能代替。同步上传清单跳过同类缺失残留，保留其元数据。同一冻结桌面正式加密导出已被正式桌面导入并由本机 Android 端解封、认证后完成 SQLite 切槽；未执行桌面整槽激活。错误 hash、长度、零计数但被引用、缺元数据均拒绝并保全原槽。
- **S2.18** 旧 `.encryption-marker` v1 与 DSBK 版本分别识别；用现有备份试密（最新/次新、DSBK v1 整件和 v2 首块），仅有同步记录时以已有认证密文证明，仓库确实无数据时才允许首次登记。沿用桌面 pending、租约、v2 字段，支持强 ETag 时加条件写；回读确认后才保存配对。错密、损坏、竞争、取消和写入响应丢失覆盖通过；未知结果保留认领材料，不回写旧标记。超出 DSBK v1 直读预算仍来源于既有加密端口限制；不会让无凭证的旧仓库进入明文模式。`tools/s2/s2-18-desktop-marker.mjs` 验证冻结桌面密文输入、Android 写出的 v2 字段和冻结桌面拒绝陈旧 claim；本机 WebDAV 测试已实际跑完，未访问生产仓库。
- [本机验证摘要](../logs/s2-16-18-main-host.log)：标准 `verify:s1` 在独立索引树 `0186dee735ca00017579c04c4ac92ba71eb1cda2` 通过 debug/仪表 APK **构建**、68 个 AAR、78 模块架构/复杂度门禁。全量 1033 个唯一 JVM 用例，默认 1030 通过、3 条需要桌面夹具；三条已随 `PortableBackupFilesTest`（14/14）补跑并通过。标准 Node 144 通过、1 条因干净候选无预置 APK 跳过；额外冻结桌面 ZIP 工具 20/20 通过。原始 S0 快照在隔离目录使用锁定 Edge/JDK，25/25 与已签 S0.G 均通过；535 份冻结输入校验通过。用户原有 rules 修改及 21 项旧截图删除不纳入候选、不回退。
- 本轮仅本机：不连接、安装或运行安卓真机用例。`plannedRemediations` 三项为 `awaiting-device`，正式签收仍停在 S2.15，`next=S2.16`，未推进 S2.19 或 S2.G/M2。下轮在同一候选上分别复跑真机冷恢复/重配与资源校验，再验旧标记有密文、错密、竞争和中断路径，按 S2.16→S2.18 依赖顺序签收。


## 2026-11-23T04:00:05+08:00 - Android 工程审计与治理修复（不推进业务阶段签收）

- **本轮边界与基线**：用户要求修复仓库证据、CI、AGP 反射、测试/覆盖率、格式、规则编号、模块登记问题。保留开工前用户 rules 修改（含最多3个子智能体、补充的顺序与说明要求）及21张截图的工作区删除。核对 `dstu-node@e6b2657fd59014a73f5bbb1d14c2db732cf23e87`、`Operit@7a39941bad80209973c535046a18195c5da9e647`、terminal `e4646680663789002b43bbfffa6e69e65c35e614`，未改这些冻结仓库。参考桌面 `.github/workflows/ci.yml`、`host/plugins/sync/src/run-sync.ts`/`errors.ts`/`file-transfer.ts`、provider errors及测试，以及 Operit 的 Gradle配置和 `.github/workflows/android-tests.yml`。Operit 无对应多端同步协议，不能作为协议来源。
- **证据治理**：实际 HEAD 有652个 logs 文件、工作区631个，而非初评630/651；共19,791,692原始字节。先归档并逐条SHA-256校验，21个已删PNG从HEAD blob导入外部归档、未恢复工作区，然后移出全部原始文件。历史归档 `/home/xutao/deepstudent/artifacts/android-evidence/logs-20261122T190917Z/evidence.tar.gz`，SHA-256 `e456a9389d5d24503429e2ceb8fe233de162fac9a05363f510724d3cdf889071`，Git仅存[索引](evidence/logs-index.json)。新增归档/校验/还原、路径安全/篡改/缺失/幂等测试、禁止追踪logs门禁；删除旧 `.gitattributes` 日志豁免。未重写Git历史，所以历史对象体积不会自动下降；远端持久存储上传尚未进行。
- **兼容历史验证**：S0冻结输入改为286份源码/合同，再独立核对249个已归档S0日志索引与冻结Git blob的大小/SHA-256，不撤销原有字节约束。`verify:s1`、`verify:progress`、`verify:stage-gate`使用临时证据重放wrapper，结束后只清除本轮还原且未变化的文件；保留既有/新产生日志，支持`DSTU_EVIDENCE_ROOT`。S1 progress与冻结输入校验实测通过；原始S0隔离快照逻辑未改，本轮未重跑整个S0验收。
- **CI与发布**：分开 repository/static/unit(jvm/android)/device(API26、36) job，聚合`Android required checks`拒绝失败/取消/跳过；push限定main与release/**，PR可取消旧run，最小权限、超时与报告上传。补冻结desktop/Operit及原生引擎准备，避免依赖本机缓存。tag release复用全部检查，再进入`android-release`受保护环境签名；缺密钥配置失败，不回退debug。验证APK包/版本/SDK/ABI/对齐/签名、AAB签名，输出APK/AAB/SHA256SUMS并创建草稿而非自动公开发布。当前仓库没有remote，未声称GitHub实际运行；管理员需配置分支保护、Environment与4项签名secret，见[CI指南](ci.md)。
- **类型安全/质量**：build-logic改为java-gradle-plugin + Kotlin JVM/SAM 2.2.21，与AGP8.13.0编译依赖对齐；AndroidConvention用公开typed DSL，无方法名反射配置。新增真实JaCoCo0.8.13报告与每模块60%行门禁，当前范围core/common 240/400=60.00%、services/backup/backend 1071/1118=95.80%；空测试、缺exec、零阈值和低覆盖率均拒绝，未声称覆盖全仓。JUnit4保留，新增Sync回归采用Truth1.4.5。Spotless8.12.0/ktlint1.8.0与.editorconfig接入固定历史commit ratchet，防止HEAD/origin/main空diff绕过；新未格式化Kotlin拒绝的负向测试实测通过，避免全仓历史格式重写。
- **同步异常**：SyncPipeline保留SyncFailure合同，明确IOException→STORAGE；CancellationException原样重抛并记CANCELLED；未知异常记录脱敏UNEXPECTED、correlationId与stage后原样重抛，不再在pipeline内伪装存储故障。6个新增回归覆盖预期失败、取消、程序错误、敏感信息与成功阶段序列。外层ConfiguredSyncTransfer/SyncController仍按既有UI合同兜底STORAGE，本轮不改公共错误枚举或桌面wire。
- **规则/模块**：重复6已修为连续1–14，修正用词/标点并明确原始证据不入Git；未丢用户已有新增条款。实际模块登记79项（78默认模块+1可选benchmark），不机械自动include；verifyModuleRegistry双向核查漏项/无构建文件/重复登记，单测分片无法识别新插件类型时失败。benchmark仍仅由原有property启用。
- **主线程实测**：repository Node **88/88**、原有design/content/progress工具 **30/30**；build-logic **23/23**；JVM分片32模块 **440/440**，Android单测23模块599条中 **596通过、3条既有桌面oracle测试因缺独立fixture跳过、0失败**。qualityGate、actionlint、git diff检查全部通过。Debug及androidTest APK、unsigned release APK/AAB构建通过；另用一次性非生产密钥实跑签名APK/AAB验证，密钥已删除，不可将该测试签名包当正式版本。
- **设备矩阵本机复验**：API36与API26专用x86_64模拟器均 **19/19、零跳过**（storage13/network2/Keystore4），共38条。API26使用用户指定本地代理127.0.0.1:7897分段下载官方revision1镜像，大小474178332字节、官方SHA1 `432f149c048bffce7f9de526ec65b336daf7a0a3`均匹配。未连接用户真机，未把平台套件当作ARM64整包/性能/死亡恢复/生物识别或S2签收；两个临时模拟器已关闭。
- **最终证据与队列**：本轮21份最终验证输出归档 `/home/xutao/deepstudent/artifacts/android-evidence/engineering-audit-20261123/evidence.tar.gz`，SHA-256 `573ddfb817b02f8fb2399de875937784cd27c4d4ab8195b303e7a451ab81bc51`，见[索引](evidence/logs-index-engineering-audit-20261123.json)。所有raw logs已退出Git且工作区无遗留raw logs；构建缓存按规则忽略。仅完成工程治理，不修改业务签收进度：`next=S2.16`，未签S2.G/M2。


## 2026-11-23T04:09:43+08:00 - AGPL 开源发布准备（公开历史范围待确认）

- 用户要求本轮完成后发布远程Git并开源，按其“apgl”含义准备AGPL-3.0-only。新增根`LICENSE`（GNU官方网站未修改原文）、`README.md`、`THIRD_PARTY.md`及package license字段；第三方保留各自许可和原NOTICE，不将unresolved二进制/rootfs材料宣称已放行，不上传测试签名包。
- GitHub CLI已验证现有keyring登录为`qeryuo112`；`qeryuo112/dstu-android`尚不存在，本地无remote。没有提取或写入任何token，也未创建/推送公开仓库。
- Gitleaks8.24.2经官方checksum校验后检查当前源码快照与全部历史；分别报告32/35条，审阅为固定源码摘要/提交ID、公开下载文件ID、测试日志脱敏marker、golden派生密钥摘要与时区字符串，不直接将扫描误报当成生产凭据。扫描报告脱敏存放在仓库外`/home/xutao/deepstudent/artifacts/android-publication-20261123/`，不提交secret报告。
- 需要用户确认的公开边界：当前Git树无raw logs，但历史提交仍含旧日志/截图；直接推送main会把这些历史一并公开。历史验收与格式门禁引用冻结提交，不能简单创建空历史快照后宣称所有校验照常。待确认是否允许公开旧历史，或另做保留本地历史的干净发布迁移，再执行公开推送。本条不是已发布声明。
- 工程审计已提交`0691818`；本次许可证准备后88项仓库回归仍通过。未修改业务验收队列。


## 2026-11-23T04:23:58+08:00 - 按用户要求建立不含旧证据的公开源码路径

- 用户明确“不把旧日志和截图一起公开”。保留私有仓库完整历史且不向它添加公开origin；建立独立公开目录`/home/xutao/deepstudent/dstu-android-public`。导出工具只用Git archive读取受审源码，不复制/fetch私有对象库，不继承旧commit父链。格式基线只含旧Kotlin源文件与许可证，不含旧docs/日志/截图；当前源码作为其后的新快照。
- `tools/publication/export.mjs`拒绝logs、artifact、缓存、截图、密钥、APK/AAB与未审阅ZIP；遍历公开所有可达提交检查，拒绝object alternates。仅保留两份既有synthetic legacy ZIP测试夹具（SQLite库、manifest和合成txt资产，无截图）。公开证据索引只留摘要，将私有绝对存储位置换成`PRIVATE_ARTIFACT_ROOT`；不上传证据归档。
- 为避免删历史后CI失效，新增SHA-256固定的S0输入清单（286源码/合同+249私有证据摘要）及唯一必要的旧功能计划源文档。planning/frozen-inputs支持无私有Git祖先的摘要校验，不包含旧日志正文。完整S0重放须显式设置`DSTU_PRIVATE_HISTORY`；缺失报错，不虚报历史验收通过。
- 私有目录`verify:repository`、**93/93** Node回归、冻结输入对原Git校验、`qualityGate`均通过；新增回归验证无历史计划校验、篡改拒绝、导出不带私有祖先、原私有证据仍在本地、更新保持固定公开基线、目标脏工作区拒绝覆盖。GitHub现有CLI和SSH登录均为`qeryuo112`，未创建新密钥或修改认证权限；公开推送将在实际公开目录验证后执行。
- 冻结desktop/Operit/terminal版本不变，业务验收仍`next=S2.16`；本次是公开边界治理，不签S2.G。


## 2026-11-23T04:28:46+08:00 - 公开目录验证与私有外部依赖隔离

- 独立公开目录实际执行`verify:repository`、93项Node回归、无原Git对象的286份冻结源码摘要校验、`qualityGate`和`:app:assembleDebug`全部通过；不是仅在私有目录验证。公开对象库检查原S0、dbf8b6c和私有源码提交均不存在，全部可达tree无logs/截图/未审阅归档。Gitleaks扫描公开源码历史32个提示均对应此前已审阅的固定摘要、提交ID、公开下载ID和synthetic测试marker，未把误报当生产凭据。
- GitHub API核实冻结桌面`helixnow/dstu-node`为private，Operit为public。为防止公开PR拿到私有源码，新增明确的`FROZEN_DESKTOP_TOKEN`读取入口，只用于可信push/手动运行，PR不取得token或私有依赖。不复制日常CLI token，不新增SSH key，不公开桌面仓库。缺少专用只读secret时repository合同仍执行，完整job跳过且聚合必需检查保持未通过，不伪报全CI绿灯。
- 私有依赖边界回归加入后共**94/94** Node用例通过，actionlint通过；发布CI和签名CI均显式使用该只读输入。管理员后续需单独配置只授权桌面仓库Contents: read的凭据。本次用户授权为Android源码公开，不包括私有桌面源码和旧验收材料。


## 2026-11-23T04:34:16+08:00 - GitHub公开推送完成与云端边界实测

- 已按用户授权创建并推送公开仓库`qeryuo112/dstu-android`，默认main、GitHub识别AGPL-3.0。首次公开head `b87614273af2a32d4cea6ab68948a865f05392a6`，仅3个独立源码提交；只推main，没有镜像推送、旧tags、旧日志、截图、归档或原Git祖先。私有开发目录仍无remote，完整历史保留。
- 从GitHub重新clone实际远端后检查所有可达对象，无logs/截图/二进制发布包；`verify:repository`及94/94脚本测试通过，公开索引无可下载私有归档。云端run 45066852276实际启动，私有基线availability明确报告缺少FROZEN_DESKTOP_TOKEN，不运行依赖私有源码的job，聚合检查保持失败；不是全CI通过。
- 首轮repository job脚本测试成功，actionlint在runner附带ShellCheck时把内嵌Node模板字符串的单引号误判为SC2016。改为带引号heredoc，保留字面量防shell展开；本机补装ShellCheck0.10.0与actionlint联合检查通过，并准备推送修复。完整AndroidCI剩余条件仍是管理员配置私有桌面仓库专用只读secret，未使用日常账户token作为CI凭据。


## 2026-11-23T16:46:40+08:00 - S2.16–S2.18 iPA2475 真机通过，保留同包供手机复验

- 本轮是真机验收，不推进桌面/Operit 版本。桌面 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 的 `host/plugins/backup/src/restore-commands.ts`、`host/plugins/sync/repository/change-log.ts` 和 `host/plugins/sync/tests/change-log.test.ts` 定义普通恢复重置；`backup-manager.ts`、`zip.ts`、VFS 资源引用及 `sync/repository/apply-refcounts.ts` 定义真实附件；`sync-providers/src/{marker,password_proof,e2ee_claim}.ts` 定义密码证明、租约认领与标记对拍。Operit `7a39941bad80209973c535046a18195c5da9e647` 检索 `app/src` 下变更日志、引用计数、WebDAV 和标记，核对 `data/backup/RawSnapshotBackupManager.kt`、`RoomDatabaseBackupManager.kt` 的私有暂存/恢复边界；其本机快照不是同步协议来源。
- **S2.16：3/3，零跳过。** 用 Android SQLite 和正式恢复执行器检验四库候选同步版本归一、冲突/别名/旧日志清除，原活动槽不变，失败可重新暂存，重开不清新改动。经专用 loopback WebDAV 普通排队、首次启动恢复入口、Keystore、换新 writer 加入同一实例：首轮无旧日志上传，收到桌面笔记；新 writer 真发序列 1/2，冻结桌面消费后读出新建、编辑、删除，附件与原仓库实例保持。四库采集不等于其他三个域已开通桌面往返。全量 seed 仍为 S2.29/S8.06 范围。
- **S2.17：6/6，零跳过。** 冻结桌面正式导出及重新导入的同一加密 ZIP，真机经 Android 文件提供器、认证、队列和首次访问恢复入口切槽。笔记与附件哈希匹配；无引用且零计数的缺失 blob 留元数据、不进入上传清单。正计数缺载荷、仍被文件/外置资源/预览/压缩图引用、缺元数据、错 hash 或大小均拒绝且原活动槽保全；未执行桌面整槽激活。
- **S2.18：10/10，零跳过。** 真机用 WebDAV 强 ETag 和真实 DSBK/Android Keystore：冻结桌面 marker v1 + DSBK v2 首块证明后升级原仓库并持久化配对，已有 v2 重配不重写标记；覆盖 DSBK v1、次新备份、只有认证清单、错误 Range/错密、损坏标记、竞争、持租约时取消及提交后回读丢失。回读不确定时无本机配对，保留已写标记及认领材料。冻结桌面验证真机 v2 校验字段、拒绝陈旧 claim。未访问生产仓库。
- 设备为 iQOO Pad2 Pro（`iPA2475`、API 36、ARM64），未用模拟器。预跑发现 vivo 后台 freezer 和私有路径链接问题；用已有前台测试宿主和规范私有路径修正后完整复跑。独立 debug ID `com.deepstudent.app.s2device` 安装后读回双 APK SHA-256，结束后卸载两份临时包和清专用反向端口；原 App 安装路径未变，未清数据。冷恢复测试显式调用新协调器的首次启动钩子并重开槽，不是硬断电或进程 kill 矩阵。
- `qualityGate`、APK 构建、仓库校验和 Node **96/96** 通过；主机对应四库/资源/marker 回归与冻结桌面向量通过。真机脱敏日志按 UID 采集，验证 12 个相关操作，确认四库重建 → 切槽 → writer 轮换，认证 → 排队 → 附件校验 → 切槽，以及 marker 的六类结果。证据索引 [s2-16-18-ipad2475-20261123](evidence/logs-index-s2-16-18-ipad2475-20261123.json)，归档 SHA-256 `87bdc760687e20d60f858a5d5e24d71f8ef109e81604a9cbfd7410e716c4bf31`；Git 没有原始日志、截图或 APK，未推送新的公开提交。
- 同包复验：候选源码 `2502c0eeaa0d041ed5e2e955343569b2b4d11bdf`，两 APK 清单 SHA-256 `113949de4b987da5b76b2cf412af2d31c769876e73930a1fcefc67fa7ddece6a`；本机候选目录 `/home/xutao/deepstudent/artifacts/android-s216-18-20261123/candidate-2502c0eeaa0d041ed5e2e955343569b2b4d11bdf`。仅连接且授权 `V2232A` 后，在项目根目录运行下方命令（需冻结桌面检出及可执行 Node 24）。不要重打包或改用普通包；手机通过后再按 S2.16→S2.18 更新签收。当前 `next=S2.16`，没有签 S2.G／M2。

```bash
PATH="/home/xutao/deepstudent/build-tools/node24/bin:$PATH" \
  node --disable-warning=ExperimentalWarning --experimental-strip-types \
  tools/s2/s2-16-18-device.mjs --serial "$PHONE_SERIAL" --model V2232A \
  --candidate "/home/xutao/deepstudent/artifacts/android-s216-18-20261123/candidate-2502c0eeaa0d041ed5e2e955343569b2b4d11bdf" \
  --output "/home/xutao/deepstudent/artifacts/android-s216-18-20261123/v2232a"
```


## 2026-11-23T16:54:51+08:00 - S2.16–S2.18 V2232A 同候选复验，双真机签收

- 主线程只连接 `V2232A`（API 36、ARM64），用 iPA2475 已验的 `2502c0eeaa0d041ed5e2e955343569b2b4d11bdf` 候选原包复跑。未重打包，双 APK 安装读回 SHA-256、集合 SHA-256 `113949de4b987da5b76b2cf412af2d31c769876e73930a1fcefc67fa7ddece6a`、19 个用例标识逐项一致；生成的桌面测试材料在各次运行独立校验摘要。桌面 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 和 Operit `7a39941bad80209973c535046a18195c5da9e647` 未变更。
- **S2.16 3/3、S2.17 6/6、S2.18 10/10，零跳过。** 手机实际用新写者在原同步实例发出序列 1、2，冻结桌面消费并回读笔记新建、编辑、删除；冻结桌面正式导入的加密备份在手机完成恢复，真实引用异常切槽前拒绝；手机写出的旧仓库升级 v2 标记由冻结桌面回验。复核 12 组关联日志，含错密、标记损坏、认领竞争、取消与提交后回读不确定。只用受控测试仓库，未访问生产同步数据。
- 测试于 `2026-11-23T16:53:20+08:00` 完成；专用反向端口和两份临时包已清理，未覆盖或清除用户 App、数据。手机证据归档 SHA-256 `f4b31959a9d71cae7b3bee3e1ee0ab05b3b14da72c191ce5a8f9ecaf3c10a1e4`，Git 只存[手机证据索引](evidence/logs-index-s2-16-18-v2232a-20261123.json)与[平板证据索引](evidence/logs-index-s2-16-18-ipad2475-20261123.json)，原始证据不公开。
- 主线程核对各前置签收后按 `S2.16 → S2.17 → S2.18` 登记 accepted。两台各 **19/19**，合计 **38/38**；`next=S2.19`（完善 runtime.db 状态与投影），未开始该单元，也未签 S2.G/M2。

## 2026-11-23T22:19:14+08:00 - S2.19 本机验证，待设备复验

- 当前卡 `S2.19/implementation` 源自冻结 `S2.16/implementation`；11 项历史插件命令场景保留来源映射，本轮不把运行时基础测试记作插件功能通过。冻结桌面 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 核对 `host/plugins/agent-workspace/src/workspace/subagent-task.ts`、`repository/subagent-task-repository.ts`、`tests/workspace-subagent-task.test.ts` 的原子状态转换及同状态幂等；核对同插件 `src/approval-manager.ts`、`tests/approval-manager.test.ts` 的会话/调用键隔离、晚到决定与拒绝。Operit `7a39941bad80209973c535046a18195c5da9e647` 核对 `app/src/{main,test}/java/com/ai/assistance/operit/integrations/a2a/` 的 `A2aTaskManager.kt` 与 `A2aTaskPublicationTest.kt`：借鉴快照和事件顺序，不采用其内存任务表作为持久草案，未找到同等的持久任务投影布局。
- `runtime.db` v3 复用 `DatabaseRegistry` 和 `SqlTaskLedger`：从 v1/v2 事务升级，新增审批、审计、职责投影；每次有效变更和审计、任务事件及游标同一事务，旧 generation、冲突与重复提交不产生额外事件。晚到投影只推进事件游标，不回拨任务租约时间。日志记录固定字段，不记录决定载荷；授权裁决和实际前台服务不在本单元。共享库 schema、同步 wire 和正式签收文件不变。
- 主线程在本机复跑 `runtime:api`、`runtime:backend`、`runtime:data` 共 **70/70**（含同分支的隔离 S2.20 回归），覆盖 fresh/v1/v2、DDL 失败回滚、重开、记录键与游标隔离、日志异常和脱敏。78 模块架构及复杂度检查无违规；`runtime:data` 测试 APK 只构建，未安装或运行。正式状态保留 `next=S2.19`，本机候选不作真机签收。

## 2026-11-23T22:19:14+08:00 - S2.20 隔离租约候选本机验证

- 当前卡 `S2.20/implementation` 映射冻结 `S2.17/implementation`，无独立历史场景。桌面原冻结提交核对 `host/plugins/agent-workspace/{src/workspace/completion-outbox,repository/completion-outbox-repository,tests/workspace-completion-outbox.test}.ts` 的 owner/expiry 匹配、原子回收和幂等确认；Operit 原冻结提交核对 `A2aTaskManager` 的取消与唯一终态、`A2aTaskPublicationTest` 的并发发布，以及 `core/workflow/WorkflowWorker.kt` 的 Worker 与业务入口分工。Android 沿用持久 `ownerEpoch`，不复制 Operit 的内存锁或引入 WorkManager。
- 在本地分支实现显式释放及精确匹配旧所有权的过期接管。接管许可由持久 owner 标识推导，只可核对事实或结算，不可交给执行器重跑；过期、续期、时钟回退、跨任务/代次旧 token 和重复取消都有负例。纯状态机 **11/11**、本机真实 SQLite **6/6**（已计入前述 70 项）；并发用例使用同进程两个 Registry 句柄，不是 Android Worker 或跨进程实测。未把新接管路径接入正式 App，也未签收 S2.20。

## 2026-11-23T22:36:59+08:00 - S2.21 隔离 outbox 和双库对账本机验证

- 当前 `S2.21/implementation` 对应冻结 `S2.18/implementation` 和 `sync.registry:chat_v2.completion_outbox`；现行查询还继承 S2.20 的执行顺序。核对冻结桌面 `host/plugins/agent-workspace/{src/workspace/completion-outbox,repository/completion-outbox-repository,tests/workspace-completion-outbox.test}.ts` 和 `host/plugins/chat/{src/completion-dispatcher,tests/completion-dispatcher.test}.ts`，取稳定事实键、补投查重及先投影后确认合同，不将聊天 outbox 改作 Android 私有任务队列。Operit 核对 `integrations/a2a/A2aTaskManager.kt`、相应 `A2aTaskPublicationTest.kt` 与 `core/workflow/WorkflowWorker.kt`，其内存任务/发布不提供业务库到运行时账本的持久投递。
- `script.db` 候选 outbox 只保存单调序列与唯一 receipt 引用，由触发器随脚本回执在同事务写入，安装时回填已存在事实；各消费者独立确认，游标不越未确认记录。复用 `BusinessCompletionSource` 和 `RecoveryCoordinator` 先对账再 ack。主线程本机复验新增用例 **13/13**，含同事务回滚及双库关闭重开：账本写入失败不提前确认；账本已写、确认失败时再次启动不新建投影事件、不重跑脚本。架构/复杂度无违规，日志不含事实内容或消费方原始标识。
- 候选仅覆盖脚本业务纵切，不把它记作聊天 `completion_outbox` 正式业务或同步完成；冻结聊天表、笔记路径及默认脚本装配均未变。旧脚本 ack 未迁移，未来显式切换候选时会对已存在回执做一次幂等补确认，接入前须复核升级流量。未真机测试，未签收。

## 2026-11-23T22:53:58+08:00 - S2.22 隔离启动恢复候选及四单元本机回归

- 当前 `S2.22/implementation` 对应冻结 `S2.19/implementation`，无独立历史场景。冻结桌面查看 `host/plugins/chat/src/workspace-run-agent.ts` 的 `workspaceRestoreExecutionsAction`，以及 `host/plugins/agent-workspace/src/workspace/subagent-task.ts` 的 `sweepOrphanedRunning` 和 `tests/workspace-subagent-task.test.ts`：活 owner 不动，已失去 owner 且终态不明的任务不盲重跑。Operit 查看 `api/chat/AIForegroundService.kt` 的空 intent 服务重建、`integrations/a2a/A2aTaskManager.kt` / `A2aTaskPublicationTest.kt`、`api/chat/enhance/SubtaskExecutionStateTest.kt` 的部分结果与取消语义；没有可移植的双库启动对账。两份冻结版本均保持原样。
- `AppRecoveryPlan` / `AppRecoveryPolicy` 复用 `RecoveryCoordinator`：先回收旧 owner、核对业务事实，再按任务处置。已有事实只补账；可证明幂等的 `notes.create` 可以续作；笔记导入与聊天流保留检查点并标为中断；无回执脚本进入待确认。等待审批或暂停不执行、不改终态，无 owner 的待审批或暂停任务再次扫描仍可能产生一对租约事件。租约过期不是进程死亡证据，不将过期当成可重放不明副作用的授权。正式启动仍走原入口；未把候选接入默认 `RuntimeStartupRecovery`。
- 主线程本机 SQLite 重开用例 **8/8**：事实先行、只续一次、保留部分内容、脚本待确认、活/过期所有权、审批/暂停、取消。业务事实在这组用例是受控夹具，笔记本身的同库去重复用既有 `NoteTaskStoreTest`，实际脚本双库掉点证据见 S2.21；不等同于安卓进程死亡实测。
- 最终在本地分支跑 `qualityGate`、Gradle `test`（含冻结桌面备份夹具）及 APK 构建：**1,089/1,089** 唯一 JVM/本机 SQLite 用例通过，零失败、零跳过；仓库回归 **96/96**。78 个模块架构与复杂度检查无违规；调试 App 与 App/runtime 测试 APK 只构建，App 调试 APK 包名、ARM64、调试签名和 16 KiB 对齐核验通过。286 份冻结源码/合同及 249 份历史证据校验通过。未使用真机、模拟器或设备脚本；未推送或合入主线。
- 工作保存在本地 `staging/s2-19-22-host`。S2.19 本机验证通过待设备复验；S2.20–S2.22 为隔离候选，须等前置正式签收再接入正式调用链。`next=S2.19`、`s2GateAccepted=false`，不把本机结果登记成真机签收。

## 2026-11-23T22:55:26+08:00 - 本机候选证据归档

- 以本地候选 `958af12` 为索引来源，将 5 份最终本机测试/门禁日志归档至仓库外；[证据索引](evidence/logs-index-s2-19-22-host-candidate-20261123.json) 与归档按 SHA-256 `6007db5df55ee5e6d88c1a424f36858458cb3390b161d0f595c127ac31bd77b9` 独立验证通过。仓库没有追踪原始日志；不是设备回执。

## 2026-11-24T04:32:19+08:00 - 合入本地 main、S2.19 双真机签收和后续候选复验

- 按用户指令，`staging/s2-19-22-host` 快进合入本地 `main`（`6583cab` → `9e34cceceade89b8787e7463d5d7311b5399f412`），[规则第 16 条](rules/rules.txt)登记后续禁止新建开发分支。未推送远端。S2.20–S2.22 的源码虽随分支合入，本轮仍未把候选接到正式 App 调用链。冻结桌面 `e6b2657fd59014a73f5bbb1d14c2db732cf23e87` 与 Operit `7a39941bad80209973c535046a18195c5da9e647` 未变。
- iPA2475 平板先跑运行时 APK **2/2**（与后续复跑重合，不重复计入）。两台 Android 16、ARM64 真机随后使用同样的四个隔离 APK，安装后逐个读回哈希一致；以测试源叠加方式复用该提交的用例，没有改动生产代码。每台 **42/42**：S2.19 账本 **15/15**，S2.20 租约 **6/6**，S2.21 脚本 outbox、适配与双库对账 **13/13**，S2.22 恢复策略 **8/8**，无失败、无跳过。
- **S2.19 正式签收。** 每台 15 项覆盖 fresh、v1/v2→v3、失败回滚、未知版本、重复键/冲突、任务和代次隔离、游标分页、各投影读回；另用同一跨进程测试 APK 在每台执行写入、读回两步（**2/2**）。提交后对隔离包执行 `force-stop`，主机确认旧 PID 不在，再启动新进程；重读任务终态、检查点、回执、审批、审计、职责及游标，两个设备的写入与读回游标均为 `9`。每台审阅 6 条实际投影日志，含任务关联、阶段、归属、结果、耗时，不含业务载荷；使用测试 sink，未声称普通 App 已订阅投影日志。此为提交后跨进程读回，不是掉电或事务中断实测，也不把历史插件命令场景登记为通过。
- **S2.20–S2.22 仅隔离候选验证。** S2.20 的竞争是同进程不同句柄，不是真实 UI／WorkManager 同时执行；S2.21 的对账是注入写入或确认失败后关库再开，没有接入默认脚本或聊天业务路径；S2.22 用受控事实与执行器验证显式恢复策略，不是正式 App 启动或系统杀进程后的恢复。按顺序仅登记 S2.19，`next=S2.20`，`s2GateAccepted=false`。
- 正式安装状态在两台设备前后未变（平板已安装，手机未安装）；四种隔离测试包均已卸载，未覆盖、清理或读写用户数据。双真机有效结果为每台 **44/44**（含跨进程的写入、读回两项），共 **88/88**。两台均未使用模拟器。手机最后结束于 `2026-11-24T04:30:59+08:00`。
- [首轮平板证据](evidence/logs-index-s2-19-ipad2475-first-device-20261124.json)与[双真机证据](evidence/logs-index-s2-19-22-dual-device-20261124.json)均已验证；双机最终日志与复现夹具归档 15 项，SHA-256 `4e30cd3a08ca7e52df7eb30ca3cd48dcbd123b565aca35237b2e21d1c8e5f13b`。同包复验原件保存在仓库外 `/home/xutao/deepstudent/artifacts/android-s219-22-device/9e34cce-20261124T040848-0FZwM2/`；四个补验 APK 的校验清单 SHA-256 `4f0e87cf20c41e248d07bdfa5263593d17bc6b85dc3600210af4800ce6862cc2`，跨进程 APK SHA-256 `aed906c1192a194c1fe8010ad9ab72bab7aa2f19b40b66c0d9ebc799cefecc42`。Git 仅保留证据索引与回执。

## 2026-11-24T05:00:25+08:00 - S2.20–S2.22 平板专项复验，等待手机同包验证

- 本轮按用户要求只对 iPA2475（Android 16、ARM64）运行。主线基线 `3afa07525bb7dd3b321305a54ef4924eeba120d5`，冻结桌面与 Operit 未变；未新建分支、未修改生产源码、未对手机执行操作。已签收的 S2.19 作为前置。已封存的 APK 先跑完 27 项基线，另用独立 `.s2device` APK 运行 5 项补验，合计 **32/32**，最终两轮均无失败、无跳过。3 次预期测试进程退出单独核验，不计入 32 个成功用例。
- **S2.20 7/7。** 六项 SQLite 租约回归覆盖续期、释放、旧 token 围栏、过期接管与重复取消。补验使测试 Activity 按钮与 Android `JobScheduler` 的 `JobService` 同时提交相同去重键；核对两个入口返回同一任务、仅一次业务执行，四次取消只写一次取消请求及一个终态，检查点保留。后台入口是受控系统任务夹具，不是生产 UI 或 WorkManager，未提前接入 S2.25。
- **S2.21 15/15。** 十三项基线外，分别在脚本回执和 outbox 已提交但未投影、以及投影已提交但未确认的边界终止隔离进程。主机确认旧 PID 已退出，随后新进程只补账或补确认：每个场景最终一份回执、一次投影、一次确认、零业务重执行；再次恢复不重复，独立消费者游标不受影响。实际日志核对先对账后确认。显式使用脚本候选 outbox，不涉及正式脚本装配或聊天同步。
- **S2.22 10/10。** 八项策略基线外，旧进程保留租约和检查点后退出，新进程对独立实际 VFS/运行时目录显式调用 `AppRecoveryPlan.forRuntime`：已有笔记事实只补账、另一条笔记只续作一次；聊天与导入保留部分内容并中断，未知脚本待确认，取消与审批/暂停状态符合预期。再次停掉隔离 App 后以不同 PID 运行，笔记数量和终态游标不变。不是安装到普通 App 的默认启动验证。早先一次自动复跑在 Activity 销毁等待超时；调整测试夹具收尾后用最终包完整复跑，未改产品代码。最终结束于 `2026-11-24T04:57:46+08:00`，全部临时包已移除，普通 App 的安装状态未变、未清理用户数据。
- [平板证据索引](evidence/logs-index-s2-20-22-ipad2475-20261124.json)指向 16 份已校验条目，归档 SHA-256 `c63a2f9a5edb817f784046543fd097ca8d5bdcc062563e215dab629a7f36c57d`。正式 `next=S2.20` 与 `s2GateAccepted=false` 未更改；待用户接入 V2232A 后按相同 APK 与选择器复验，不能将前一轮旧包结果与本轮补验混用。
- **手机同包复验入口**（用户接入并授权手机后才运行）：`ROOT=/home/xutao/deepstudent/artifacts/android-s220-22-20261124/tablet-043813-LxXkhl`；核对 `baseline/SHA256SUMS` 文件摘要 `4f0e87cf20c41e248d07bdfa5263593d17bc6b85dc3600210af4800ce6862cc2`、`phone-candidate/SHA256SUMS` 文件摘要 `840bf01c7ac024c39ab67950eed83fb116d549ea2950a4c4562932d6b86ab0fe`，不重打包。前一轮 S2.19 手机前置日志已按双机归档的哈希放入基线目录，只作为前置证据。

```bash
bash "$ROOT/baseline/run-on-device.sh" "$PHONE_SERIAL" V2232A candidates &&
  bash "$ROOT/phone-candidate/run-on-device.sh" "$PHONE_SERIAL" V2232A phone
```

## 2026-11-24T05:21:45+08:00 - S2.20–S2.22 手机同包复验与任务底座签收

- 主线程仅连接 V2232A（Android 16、ARM64），用上节封存原包复验，未重新打包。基线四个 APK 及补验两个 APK 的安装读回 SHA-256 与 iPA2475 逐个相同，两个设备的测试类和方法清单也逐项一致。手机 **32/32**、平板 **32/32**，合计 **64/64**，零失败或跳过；每台另有三次独立核验的预期退出，不重复计数。手机基线结束 `2026-11-24T05:07:17+08:00`，补验结束 `2026-11-24T05:08:17+08:00`。临时包均已卸载，手机上的普通 App 安装状态维持未安装；未操作平板、修改用户数据或使用模拟器。
- **S2.20（每台 7/7）：** SQLite 上领取/续期/释放、`ownerEpoch` 围栏及过期接管通过；同去重键从测试 Activity 与真实 Android `JobService` 同时提交时只执行一次，四次取消只留下一个取消事件与一个终态，检查点保留。受控后台入口以 `JobScheduler` 实测任务租约合同；不算 S2.25 正式 WorkManager、重排或系统停止续作验收。
- **S2.21（每台 15/15）：** 脚本回执与 outbox 同事务；业务已提交尚未记账、账已记尚未确认两处掉点均确认旧进程退出、新进程继续。每处最终一份回执、一次投影、一次确认、零脚本重执行，其他消费者游标不动。顺序由持久事件与真实 `RECONCILE` → `ACK` 脱敏日志共同验证。只签收脚本纵切的同事务、消费和对账合同；普通 App 仍未切换默认事实源。冻结历史场景 `sync.registry:chat_v2.completion_outbox` 保留 LocalRuntime 分类与 schema 校验，不把脚本结果登记为该聊天表的真实投递或同步，聊天流的正式业务链不在本回执。
- **S2.22（每台 10/10）：** 隔离 App 使用真实 VFS 和 runtime.db 显式调用 `AppRecoveryPlan.forRuntime`，回收旧 owner 后先核对事实。已完成笔记只补账、另一笔记仅续作一次，聊天/导入保留检查点并中断，无回执脚本进待确认，取消与审批/暂停保持正确。再次以不同 PID 重启时笔记数和终态游标不变。仅签收新恢复入口的合同，不代表它已连到普通 App 默认启动。
- 审阅手机采集的 81 条结构化恢复/outbox 日志，均带阶段、归属、结果、耗时，恢复侧可按合成任务 ID 关联，outbox 确认记录不含消费者 ID 或业务载荷。已核对 `S1.G`、`S1.10` 和 S2.19，按 **S2.20 → S2.21 → S2.22** 登记 accepted；`next=S2.23`（完善 FGS 职责和通知），不签 S2.G/M2，不改或启用正式 App 接线。
- [平板索引](evidence/logs-index-s2-20-22-ipad2475-20261124.json)与[手机索引](evidence/logs-index-s2-20-22-v2232a-20261124.json)均已校验；手机原始归档 SHA-256 `b4055506cbe249bd98325d7ad6a4e5211672697a7e519a19cba97093abdf7bf5`。另验 286 份冻结合同和 249 份历史证据；旧分类命令直接在当前树运行误用 302 项历史计数（现行 305 项），改走隔离 S0 快照后 S0.08 schema / S0.09 分类通过。额外全量历史回放在 S0.11 停止：本机 Edge `158.0.4370.33`，冻结基线要求 `157.0.4341.60`；不记全量 S0 回放通过，不改冻结文档或浏览器。这不是本次设备测试失败。

## 2026-11-24T06:20:14+08:00 - S2.23 前台职责与通知，平板候选验证

- 冻结桌面 `e6b2657f` 核对 `electron/shell/background-residency.ts`、`host/plugins/agent-orchestration/tests/background-residency.test.ts`，复用后台需有真实原因的判定；Operit `7a39941b` 核对 `api/chat/AIForegroundService.kt` 和 `core/application/ForegroundServiceCompat.kt`，借鉴用途和空 Intent 分流，未复制一律常驻、进程退出和透明浮窗。terminal `e464668` 保持冻结。现行 S2.23 对应历史卡 S2.20，不改共享 schema。
- 私有 main 实现 `f24dab8`：工作台流式笔记导入使用同一 `TaskSupervisor`；每个有效引用通过 `runtime_duties` 持久登记，通知按任务归并，停止按任务代次，取消/终态释放；旧进程已失去持有权的前台记录在首次成功打开 runtime.db 时归档为释放。同进程空 Intent 对账保留有效引用，无有效引用不启动任务。Sticky 仅给用户启用且有明确冷恢复协议的持续职责，现有导入及 PTY 不请求 Sticky。
- iPA2475 API 36 ARM64 **8/8**，无跳过，另一次受控进程退出核对旧 PID 确实退出；覆盖真实工作台、Activity 销毁后双任务继续、通知定向停止、服务同进程重建、冷打开后旧引用释放/检查点保留/未重放。早版前台强制启动在空重建触发系统 `ForegroundServiceDidNotStartInTimeException`；最终从可见入口发常规服务启动，只在有职责时提升，原路径完整复跑通过。空动作显式重建走与系统 null Intent 相同处理函数，不冒充系统自然 Sticky 重启；正式系统长时预算不在本轮实跑。
- 本机背景模块 27 项、账本投影 8 项、导入入参 2 项通过，架构/复杂度零违规，仓库合同和冻结输入通过。平板完成 `2026-11-24T06:18:47+08:00`，三个隔离包已按读回哈希清理，普通 App 安装状态不变。 [归档索引](evidence/logs-index-s2-23-ipad2475-20261124.json) SHA-256 `3b2a27f65f1b9d498549fe230ded469c3f07f64eb34a2636dcca2cc788fc3b80`，APK 清单 `cd78c92992d9549f3ed3a20de2eec4573fbb1454dadd23477fa6d04b9cd09ddd`。平板作为后续单元的集成检查点，不登记手机或双机正式签收，`next=S2.23`。

## 2026-11-24T06:49:01+08:00 - S2.24 常驻、电池页和分段锁，平板候选验证

- 冻结桌面 `e6b2657f` 核对 `electron/power.ts` 与 `electron/tests/power-entry.test.ts` 的启停及资源释放；Operit `7a39941b` 核对 `DisplayPreferencesManager`、`PermissionGuideViewModel`／`PermissionGuideScreen` 和 `FloatingChatService`，参考默认关闭、系统设置和带时限部分锁，不沿用全局空服务、透明浮窗或持续锁。terminal `e464668` 复用本机 PTY 会话生命周期，不重放 shell 命令。现行 S2.24 对应冻结 S2.21。
- 私有 main 生产接线 `c01d455`：工作台“后台与电池”、本机 DataStore 开关、可用通知与真实职责数量、系统电池入口和 OEM 指引；只为用户选择开启且实际 RUNNING 的终端会话增加 `specialUse` 职责。关闭后台撤销职责，不结束独立终端或导入。仅活动 I/O 段申请带系统超时、有限预算的 PARTIAL_WAKE_LOCK，空闲无锁；结束、关闭或服务销毁清理，日志记录阶段/原因/时长/上限。外部接入及持续 Agent 尚无可确认的正式持久在线入口，不伪造会话。
- iPA2475 API 36 ARM64 **4/4**，零跳过。两个真实 PTY 独立通知、关闭后台不杀会话、force-stop 重开只保留偏好、实读电池优化开/豁免两种状态并恢复；只临时修改同一隔离包的设备电池名单。另验证真实 Android WakeLock 句柄系统超时、按 owner 释放和全释放；不把控制器全释放测试当作人为杀 Service 的完整实测。早期用 `dumpsys power` 高频查询时系统 Watchdog 中断，系统服务恢复后按读回哈希清理临时包；最终改用真实锁句柄检测，完整复跑通过。普通 App 安装路径不变。
- 本机背景模块 **50/50**（包含 S2.23），真实 DataStore 测试 **1/1**，架构与复杂度无违规。平板完成 `2026-11-24T06:48:08+08:00`，[证据索引](evidence/logs-index-s2-24-ipad2475-20261124.json)，归档 SHA-256 `fd1e5b178558490f06cf9102f2daee4b0c0d0c5cc7b0d23388b8760230659367`，候选清单 SHA-256 `3bb9a6f72c4cb2ddcdf295715fb88158435d693adf84b7de91f73e0cee835f5e`。无分支、无推送，不提前登记双机签收。

## 2026-11-24T07:34:36+08:00 - S2.23–S2.25 同候选平板验证，等待手机复验

- 冻结桌面 `e6b2657f` 核对 `src/features/todo/reminderScheduler.ts`、`host/plugins/vfs-repos/repository/todos.ts` 与提醒目标测试，采用本机发生键、已投递不重复及仅重排启用项，不移植桌面轮询。Operit `7a39941b` 核对 `core/workflow/WorkflowWorker.kt`、`WorkflowScheduler.kt`、`integrations/tasker/WorkflowTaskerReceiver.kt`、`data/backup/RoomDatabaseBackupScheduler.kt`，复用 Worker 与业务职责分离、唯一名称和启动重排，不采用 `REPLACE` 重置周期。terminal `e464668` 无 WorkManager/闹钟重排对应实现。现行 S2.25 映射冻结 S2.22；共享 schema、同步 wire、冻结源码均不变。
- 生产源码 `4471fcf4e334e996109807d079e77eafa7c226b4`。工作台“计划与提醒”接入同进程 WorkManager 2.10.5、一次性 KEEP、周期 UPDATE 保持初始到期锚点、独立提醒渠道与 AlarmManager；能用精确闹钟才用，否则系统窗口，不授予精确权限。提醒投递成功才确认、按发生键防重，点击直接打开本应用 Activity（不走通知广播转跳）。`notes.create_scheduled` 是明确可续的纵切，UI 和 Worker 同任务 ID、同代次、同真实 VFS 事务和回执；系统停止与用户取消区分；正式启动只为该新用途接入可续任务的回收与事实对账，其它运行时恢复策略保持原状。
- **平板同候选：S2.23 8/8，S2.24 4/4，S2.25 13/13，共 25/25，零失败或跳过。** iPA2475 Android 16、ARM64；S2.23 另一次预期进程退出，主机核验 PID 消失；S2.24 电池优化开启与豁免两种实际状态、测试包豁免恢复；S2.25 实际 WorkManager 系统 Job 由 JobScheduler 超时命令停止，Worker 释放租约、保留 checkpoint 且不写用户取消，待 AndroidX 退避后同任务只产生一个 VFS 笔记回执。对照 `force-stop`：应用保持 stopped、旧 PID 与活动作业都消失，显式重开后新 PID 以同任务、同代次完成，不多写笔记。周期 UPDATE 连续重排只保留一个 WorkInfo 且首次时间不变。提醒复用开机重排函数，使用真实私有 Alarm Receiver/PendingIntent；对 inexact 回调采用受控发送，不把此用例记作整机重启、自然到点精度或已授精确权限。完整待办、工作流和番茄业务仍属后续单元。
- 结束于 `2026-11-24T07:32:48+08:00`，三份临时 APK 逐一核对设备读回哈希后卸载，普通 App 安装状态前后相同。相关 JVM/SQLite/DataStore 用例 **134/134**（62 背景 + 35 账本数据 + 34 运行后端 + 3 应用），仓库脚本 **96/96**，`qualityGate`、仓库合同、286 份冻结文件与 249 份历史证据校验均通过，隔离 App 签名 v2、ARM64、minSdk 26/targetSdk 36、16 KiB 对齐核验通过。
- [同包平板证据索引](evidence/logs-index-s2-23-25-ipad2475-20261124.json)，归档 SHA-256 `65eab4963b9e1a64471c4bb4be1c06c0f139c0693c38b7f4350299e4e915a31a`。Android 测试 APK 与所有原始日志在仓库外；不公开、不推送。下一步仅在用户接入并授权 V2232A 后用原件复跑，不重建。`next=S2.23`、`s2GateAccepted=false`；未签收双机或 S2.G。

```bash
ROOT=/home/xutao/deepstudent/artifacts/android-s223-25-20261124
CANDIDATE="$ROOT/tablet-phone-candidate"
(cd "$CANDIDATE" && sha256sum -c SHA256SUMS && sha256sum SHA256SUMS tablet-cases.txt)
bash tools/s2/verify-background-device.sh "$PHONE_SERIAL" V2232A "$CANDIDATE" "$ROOT/phone-final" all
rg '^PASS ' "$ROOT/phone-final/device.log" | cut -d ' ' -f 2- | sort | diff - "$CANDIDATE/tablet-cases.txt"
```

APK 清单摘要 `65415e23c2bdc55ad9d1895b18a2dd04c7daf1345e534d883c33bb52821c1c1a`；平板 25 项选择器清单摘要 `fc41fdf9415d89980949cc207512b8c8ac066445d0518a682e06bc21f6ff4fb8`。

## 2026-11-24T07:43:29+08:00 - S2.23–S2.25 手机同包复验与双真机签收

- 仅接入 `V2232A`（Android 16/API 36、ARM64），原样使用 iPA2475 平板候选的三个 APK，未重打包。候选源码 `4471fcf4e334e996109807d079e77eafa7c226b4`，APK 清单摘要 `65415e23c2bdc55ad9d1895b18a2dd04c7daf1345e534d883c33bb52821c1c1a`。两台设备的三个安装读回 SHA-256 逐一相同，25 个通过的类及方法标识逐项相同；用例清单摘要 `fc41fdf9415d89980949cc207512b8c8ac066445d0518a682e06bc21f6ff4fb8`。每台 **25/25**，合计 **50/50**，零失败或跳过。每台另分别核验一次受控进程退出并确认旧 PID 消失，不计入通过数。
- **S2.23（每台 8/8）**：从工作台正式入口发起笔记导入，Activity 销毁后双任务继续，通知停止只影响指定任务。服务同进程重建恢复有效职责，冷进程清理旧引用并保留检查点，不重跑导入。双机空动作走与 null Intent 共用的对账路径；Sticky 条件有本机回归，不把受控重建记为自然 Sticky 重启。
- **S2.24（每台 4/4）**：真实双 PTY 会话职责与常驻开关一致，关闭后台不终止独立终端，重开只保留偏好；系统实际电池优化和豁免均读回。真实 Android 锁句柄超时后释放，控制器按职责和全部释放通过；不签收外部接入、持续 Agent 或长时耗电矩阵。
- **S2.25（每台 13/13）**：以真 WorkManager 系统作业与工作台入口验证不双执行，系统停止不写用户取消；经过退避以原 taskId、原 generation 从检查点完成一次 VFS 提交。隔离包 `force-stop` 后没有旧进程或活动作业，显式重开换 PID 后仍只写一份业务回执。周期重排保留唯一 WorkInfo 和首次到期时间；真实私有闹钟接收器通过受控送达核验重复、撤销和开机重排入口，未授精确闹钟权限，不把它登记成整机重启或自然准点送达。
- 手机实测完成 `2026-11-24T07:41:14+08:00`。临时 APK 按读回哈希清理，测试包电池豁免已恢复，正式 App 安装状态无变化，未操作用户数据。手机[证据索引](evidence/logs-index-s2-23-25-v2232a-20261124.json)的 2 项归档已核验，SHA-256 `26a535a7c83bfac5e97f2d3c59bef0cdfad5e4e597d5c8f05c9a277420f3d225`；[平板索引](evidence/logs-index-s2-23-25-ipad2475-20261124.json)继续有效。原始日志与 APK 不入 Git、不公开。
- 主线程核对 `S1.09`、`S1.G`、`S2.20`、`S2.22` 等有效依赖，按 `S2.23 → S2.24 → S2.25` 登记 accepted。下一单元 `S2.26`（实现 CapabilityBroker 审批审计），未开始开发，`s2GateAccepted=false`，未签 S2.G/M2。此次仅私有本地 `main`，无新分支、无远端推送。
