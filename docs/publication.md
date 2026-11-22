# 公开源码与私有开发历史

用户于2026-11-23明确要求：**不把旧日志和截图一起公开**。

## 公开内容

公开仓库从独立的 Git 对象库建立，不 clone/fetch 私有仓库历史，不复制 `.git`，不推送旧 refs/tags。
首个提交只有格式比较所需的 Kotlin 源文件及许可证；下一提交导入当前已审阅源码。
它不是私有旧提交的后代。公开目录可正常 clone、开发和提交，新代码继续受固定格式基线约束。

排除 `logs/`、artifact、缓存、截图、签名密钥和发布二进制；只允许两份已审阅的 synthetic legacy
ZIP 测试夹具，不包含旧验收日志或截图。Git 中的证据索引仅提供路径/大小/摘要，不公开归档正文。
索引中的私有绝对存储路径在导出时替换为 `PRIVATE_ARTIFACT_ROOT`；需要原始证据时由持有者自行设置
`DSTU_EVIDENCE_ROOT`，不从公开仓库获取它们。

## 双目录工作流

- 私有开发目录：`/home/xutao/deepstudent/dstu-android`，保留完整旧历史，不设置公开远端。
- 公开推送目录：`/home/xutao/deepstudent/dstu-android-public`，只保存独立的源码历史。

提交私有源代码后，显式导出：

```bash
node tools/publication/export.mjs /home/xutao/deepstudent/dstu-android-public
```

导出器要求两个目录不包含彼此、工作区干净；既有目标必须是本工具生成的公开目录，
有本地改动时拒绝覆盖。后续导出生成新的源码快照提交，保留同一个公开格式基线。
**该命令不推送。** 在公开目录执行检查后再 `git push origin main`；不要在私有目录添加公开origin或使用mirror push。

`npm run verify:repository` 在公开目录还会扫描所有可达提交的文件树，拒绝raw logs、截图、未审阅归档等。
索引允许，归档正文不允许；公开目录不使用对象alternates来引用私有对象库。

## 没有旧历史时的校验

`config/publication/s0-inputs.json` 记录冻结S0的286份源码/合同及249份私有证据的大小/SHA-256，
清单整体SHA-256固定在`tools/publication/frozen-inputs.mjs`中。唯一附带的旧正文是**功能计划源文档**，
不是日志、截图或验收报告。其字节再受原S0摘要校验，用于现有计划修订合法性检查。

- `verify:repository`、`test:repository`、`qualityGate`和当前构建/测试不要求私有Git祖先。
- `tools/s1/frozen-inputs.mjs` 在私有仓库与原Git逐项比较，在公开仓库验证固定清单；输出明确区分来源。
- `npm run verify` 是完整历史S0重放。公开仓库必须显式提供`DSTU_PRIVATE_HISTORY`指向保留的私有仓库。
  缺少历史时退出失败并说明要求，不虚报通过，也不自动下载私有历史。
- `verify:progress` / `verify:s1` 等历史回执验证仍要求持有私有artifact。
  不把仅校验索引摘要称为已复跑当年的真机验收。

发布前在实际公开目录执行：

```bash
npm run verify:repository
npm run test:repository
node tools/s1/frozen-inputs.mjs
node tools/s1/gradle.mjs qualityGate
```

公开默认许可证为AGPL-3.0-only，第三方原许可保持不变。源码发布不附带APK/AAB、rootfs或私有证据资产。

## 私有外部依赖

冻结桌面`helixnow/dstu-node`目前为私有仓库，不因Android源码公开而公开。
GitHub完整构建需要维护者配置`FROZEN_DESKTOP_TOKEN`（仅该仓库Contents: read）。
不复制日常gh登录token，不自动扩大权限；PR不执行需要该依赖的job，不通过pull_request_target提权。
缺凭据时repository合同仍运行，完整检查保持未通过并提示配置要求。源码发布本身不需要公开桌面仓库。
