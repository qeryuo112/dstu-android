# 第三方版权与许可边界

根目录 `LICENSE` 适用于本项目第一方代码；保留第三方文件的版权、许可头与随附许可文本。
不得因为加入根 AGPL-3.0-only 许可证而把第三方代码宣称为本项目原创，或移除其原有义务。

主要来源与证据：

- 冻结桌面 `dstu-node@e6b2657fd59014a73f5bbb1d14c2db732cf23e87`：AGPL-3.0 许可证文本及业务/协议基线。
- `Operit@7a39941bad80209973c535046a18195c5da9e647` 与 terminal
  `e4646680663789002b43bbfffa6e69e65c35e614`：来源和采用边界见
  `config/source-lock.json`、`config/operit-adoption.json`和各 stage source lock。
- vendored terminal：保留 `third_party/operit/terminal/LICENSE` 与源文件许可头。
- PRoot：保留 `third_party/proot/COPYING`；talloc 等库保留源文件许可头。
- PDFium、QuickJS、Zstd、FSRS 等外部引擎：来源、固定版本与摘要见
  `config/stages/s1-*-source.json`；生成的许可证资产由既有构建流程收集。
- 资源、字体与运行时组件：见自动生成的 `NOTICE`、`config/runtime-notices.json`、
  `config/runtime-assets.json`；不要手工改动被冻结的 NOTICE 来掩盖 unresolved 项。

`NOTICE` 包含早期调查的历史运行时清单，**不是当前 APK 组件的完整许可证放行结论**。
其中标记 unresolved 的二进制/rootfs 材料未因本次源码开源而获准再分发。
正式二进制发布前，需要针对实际打包内容保留许可证、确认对应源码及其他分发义务；
本次不上传 APK/AAB、rootfs、旧验收截图或日志归档作为公开 Release 资产。
