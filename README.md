# DeepStudent Android

DeepStudent 的 Kotlin / Jetpack Compose 原生 Android 重构工程（`dstu-android`）。
目标是对齐冻结桌面基线的完整业务语义、共享数据 schema、同步协议及备份恢复格式，
而不是精简版或伴侣应用；平台能力使用 Android 原生实现。

**开发状态：进行中。** 当前业务验收队列、已验收内容和未完成项见
[计划与文档入口](docs/README.md)及[最新交接](docs/handoff-s3.md)。
工程质量检查通过不等于整个产品已经交付。

## 构建与测试

固定工具链：Gradle 8.14.2、AGP 8.13.0、Kotlin 2.2.21、JDK 17/21、Node.js 22、
Android compile/target SDK 36、最低 API 26。原生引擎额外使用 NDK 29.0.13846066 rc3 和 Rust 1.99.0。

构建依赖冻结源码，工作目录布局为：

```text
workspace/
  dstu-android/   # 本仓库
  dstu-node/      # e6b2657fd59014a73f5bbb1d14c2db732cf23e87
  Operit/        # 7a39941bad80209973c535046a18195c5da9e647
  build-tools/   # 本地下载/构建缓存，不提交
  artifacts/     # 验收证据，不提交
```

完整的可执行环境准备过程在 `.github/actions/android-environment/action.yml`；
它安装并校验冻结的依赖，不自动升级源工程。准备完成后：

```bash
npm run verify:repository
npm run test:repository
node tools/s1/gradle.mjs qualityGate
node tools/s1/gradle.mjs :app:assembleDebug
```

详见 [CI、覆盖率、格式门禁、设备测试与签名发布](docs/ci.md)。
API 26/36 模拟器平台套件不替代 ARM64 整包真机、性能和人工系统授权验收。

## 证据与安全

原始日志、截图、构建输出和密钥不属于当前源码树。
Git 只保留[证据索引](docs/evidence/README.md)、校验和及还原说明。
`logs/` 和构建缓存被忽略，CI 防止原始证据重新进入当前文件树。
索引引用的 artifact 并不因源代码开源而自动公开可下载。
公开仓库采用独立的源码快照历史，不含旧日志/截图的Git对象；冻结计划与输入通过固定摘要的源码清单校验。
完整历史验收重放仍需私有材料，详见[公开源码与私有历史](docs/publication.md)。

不要在 issue、日志或提交中放入实际账户凭据、签名密钥或私人备份。
测试中的固定密码、密钥摘要和 synthetic marker 是独立测试语料，不是可复用的生产凭据。

## 许可证

本项目第一方代码采用 **GNU Affero General Public License v3.0 only（AGPL-3.0-only）**。
完整原文见 [LICENSE](LICENSE)。

第三方源码、资源和组件保留原版权声明和各自许可证；根许可证不改变它们的授权条件。
详见 [第三方许可说明](THIRD_PARTY.md)、[NOTICE](NOTICE) 及各组件随附文本。
开源源码不代表历史清单中未解决的二进制/rootfs 分发许可已经完成审查；
本次仅准备发布源码，不公开测试签名包、生产密钥或私人验收 artifact。
