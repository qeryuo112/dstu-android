# Android 工程质量与 CI

## 检查入口

在仓库根执行：

```bash
npm run verify:repository       # 79 项显式登记、禁止原始 logs 入 Git、计划引用
npm run test:repository         # 仓库、归档、CI 合同与工具回归
npm run quality                # 格式、模块、架构/复杂度、build-logic 测试、覆盖率
node tools/ci/unit-tests.mjs jvm
node tools/ci/unit-tests.mjs android
bash tools/ci/device-tests.sh   # 已启动的专用测试设备；不要选择日常使用设备
```

本机 Gradle 缓存/SDK 由 `node tools/s1/gradle.mjs` 配置；直接运行分片或设备脚本时
显式设置 `ANDROID_HOME`、`GRADLE_USER_HOME`，多设备时设置 `ANDROID_SERIAL`。
设备脚本只运行 DataSpaceAndroidTest、NetworkAndroidTest、AndroidKeystoreDeviceTest，
不执行进程死亡、SAF picker、真实 Shizuku Binder 或用户生物识别操作。

## Pull request 与发布检查

`android.yml` 对 `main`、`release/**` 的 push/PR 运行，也支持手动调用与 release 复用。
可信push/手动运行分开显示仓库合同、格式/架构/build-logic、JVM/Android单测分片、API26/36仪器测试。
私有桌面基线不向PR开放；PR只自动执行不依赖私有源码的仓库合同，维护者审阅后在可信分支手动运行完整检查。
`Android required checks` 聚合所有必需 job；任意失败、取消或跳过都会失败。
仓库管理员应把该名称设为分支保护必需检查（本次只修改仓库代码，不修改 GitHub 账号设置）。
PR 不使用 `pull_request_target`，不获取私有基线或签名密钥；默认 token 仅 `contents: read`。
`helixnow/dstu-node`实际为私有仓库，完整构建需repository secret `FROZEN_DESKTOP_TOKEN`，
只授权该仓库的Contents: read；不要使用拥有全部repo写权限的日常登录token。缺凭据或PR输入受限时完整job跳过，
聚合必需检查会明确失败，不能把合同检查通过伪称全CI通过。
所有 job 有超时；PR 新提交取消旧 CI，主线和发布不自动取消。

JUnit4 保留；同步流水线的新回归测试使用 Truth 1.4.5，避免整仓测试框架迁移。
单测分片从显式登记模块生成，新插件类型若无法分类会报错，不能默默漏测。
当前有 32 个 JVM 测试模块和 23 个 Android 测试模块。
独立桌面备份 oracle 依赖 `S214_DESKTOP_FIXTURES`/Node 24，现有 3 个可选互操作用例
缺少该输入时仍会按原合同跳过；CI 报告会显示 skipped，不代表桌面整体验收。

## 门禁范围

- **格式**：Spotless 8.12.0 / ktlint 1.8.0，规则见 `.editorconfig`。
  固定 ratchet 基线在 `config/quality/format-baseline.txt`。从该提交之后变更的文件全部受检，
  包括已经合并到 main 的变更；不使用当前 HEAD/origin/main 作为默认比较目标，防止空 diff 绕过。
  第一次接入不重写历史全仓；`spotlessApply` 可修复当前变更。
- **覆盖率**：JaCoCo 0.8.13，范围明确列于 `config/quality/coverage.properties`：
  `:core:common`、`:services:backup:backend` 各自至少 60% 行覆盖率。
  报告包含模块全部 main class，不以只统计被测 class 的方式制造高覆盖率。
  空测试/缺少 exec 数据或零阈值会失败；新增范围应补测，不无理由降低门槛。
  XML/HTML 位于各模块 `build/reports/jacoco/test/`。Android/instrumentation 尚未纳入覆盖率数字。
- **模块登记**：保留 reviewable 显式 `include`；双向检查扫描 app/core/services/features/platform/engines/benchmark，
  拒绝漏登记、无构建文件的登记以及重复登记。可选 `:benchmark:startup` 仍只由
  `-Pdstu.benchmarkApp=true` 启用；校验并不擅自启用它。
- **仪器测试**：API 26 和 36，x86_64 default 镜像，自动执行平台测试并上传 JUnit/XML、设备属性、logcat。
  通过执行条目计数检查拒绝空套件或全部 skipped。整包原生引擎只支持 ARM64，
  不把平台模拟器结果冒充整包真机、性能、死亡恢复或 S1/S2 验收签收。

## 原生构建输入与证据

CI 复用 `.github/actions/android-environment/action.yml`，安装 JDK17/21、SDK36、build-tools36，
检查出冻结的 desktop/Operit（terminal 使用其锁定 submodule），不追新上游。
Android 单测与 release 另准备固定 NDK 29.0.13846066 rc3、Rust 1.99.0、FSRS Cargo.lock 下载，
固定 QuickJS/Zstd/PDFium 源码；既有构建脚本继续校验摘要并离线编译。重型 PDFium 冷构建会更慢。
CI 报告保留 14 天，release artifact 保留 30 天；长期验收需归档到持久 artifact 存储并提交索引。
原始 `logs/` 永不提交，历史日志查找和校验见 `docs/evidence/README.md`。

## 签名与发布

管理员创建 GitHub Environment **android-release**，设置审批规则，并添加：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`vMAJOR.MINOR.PATCH` 标签触发 `android-release.yml`；手动运行也必须选择现有版本标签。
版本仍走 `tools/baseline/version.mjs` 唯一映射，拒绝早于 0.21.0 的发布。
先调用完整 Android CI，再进入受保护的签名 job。缺任一签名输入直接失败，不回退 debug key。
密码只从环境变量传入 Gradle，临时 keystore 在退出时删除；不写入源码、报告或构建参数。
签名后校验 APK 包名/版本/SDK/ABI/16KiB 对齐和 release 非 debuggable、APK v2 签名，
以及 AAB 的 JAR 签名；上传 APK/AAB/SHA256SUMS，创建 **草稿** GitHub Release。
人工审阅后才公开，流水线不自动发布到商店。

本地可不提供签名环境直接构建 unsigned release；若要试签名，请使用专用测试 keystore。
本次签名流程实测使用已删除的一次性密钥，产生的测试签名包不是可分发正式版本。

## 2026-11-23 公开仓库兼容

公开源码使用独立历史和固定的纯源码格式基线，不上传旧logs/截图提交。
计划与冻结输入校验改用摘要锁定的源码副本/清单，仍可在不持有私有历史时运行当前CI；
完整S0历史重放要求显式`DSTU_PRIVATE_HISTORY`，详情见`docs/publication.md`。
