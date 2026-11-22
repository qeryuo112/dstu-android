import { copyFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { desktop, desktopRequire } from '../../lib.mjs';
import { sqlGlobPlugin } from '../harness/sql-glob.mjs';

const { build } = desktopRequire('esbuild');
const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, 'build');
const runtimeRoot = resolve(desktop, 'mobile/node_modules/@sqlite.org/sqlite-wasm/dist');

export async function buildOneShot() {
  mkdirSync(OUT, { recursive: true });
  await build({
    bundle: true,
    format: 'esm',
    platform: 'browser',
    target: 'es2022',
    absWorkingDir: desktop,
    alias: { '@dstu': desktop, 'decode-named-character-reference': resolve(desktop, 'node_modules/decode-named-character-reference/index.js') },
    plugins: [sqlGlobPlugin(desktop)],
    logLevel: 'info',
    entryPoints: [resolve(HERE, 'worker.ts')],
    outfile: resolve(OUT, 'worker.js'),
  });
  copyFileSync(resolve(HERE, 'page.html'), resolve(OUT, 'index.html'));
  for (const name of ['delivery.mjs', 'LegacyExportPlugin.java']) copyFileSync(resolve(HERE, name), resolve(OUT, name));
  for (const name of ['sqlite3.wasm', 'sqlite3-opfs-async-proxy.js']) copyFileSync(resolve(runtimeRoot, name), resolve(OUT, name));
  writeFileSync(resolve(OUT, 'README.txt'), [
    'Deep Student 旧数据一次性导出',
    '',
    '1. 使用独立过渡源码目录，保留旧包 applicationId、Capacitor hostname/scheme 与原签名；提高 versionCode。不卸载旧包。',
    '2. 在过渡包 assets/public 放入 index.html、worker.js、delivery.mjs、sqlite3.wasm 与 sqlite3-opfs-async-proxy.js，入口只加载此页面，不启动原 Host Worker。',
    '3. 将 LegacyExportPlugin.java 放入 app/src/main/java/com/deepstudent/app/；MainActivity.onCreate 在 super.onCreate(savedInstanceState) 前调用 registerPlugin(LegacyExportPlugin.class)。',
    '4. 通过原 Capacitor 构建通道编译并用同一证书签名，覆盖安装；本脚本仅生成源码资源，不执行 APK 构建、签名或安装。',
    '5. 点击导出按钮。Worker 经生产 VACUUM INTO/exportFile 导出 OPFS 四库/资源，且不调用 runAll()、不种数据。',
    '6. 系统 ACTION_CREATE_DOCUMENT 选择 ZIP 位置。页面逐 64 KiB 传输，原生插件校验总大小与 SHA-256 并关闭流后返回 URI；取消或失败删除系统端未完成文件，OPFS 原归档保留。',
    '7. 用系统文件应用复制 ZIP；使用 tools/baseline/legacy-export/package.mjs 的 inspectPackage 校验，S2.12 Kotlin 导入器按同一合同消费。',
    '8. 同设备 continuity 使用独立 sidecar 与停用旧写者的确认流程；本一次性页面只导出标准 ZIP，不生成或推测旧设备同步身份。',
    '',
    'OPFS 的 /deep-student/... 是 WebView 逻辑路径，不是可直接 adb pull 的 Android 物理文件名。debug run-as 不作为 release 数据交付方式。',
    '主机验收覆盖 Worker 真实 OPFS 导出、页面分块传输与回执逻辑；原生系统选择器/过渡包覆盖安装在实际 Android 设备验收。',
  ].join('\n') + '\n');
  return OUT;
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  buildOneShot().then(dir => process.stdout.write(`one-shot assets: ${dir}\n`)).catch(error => {
    process.stderr.write(`${error.stack ?? String(error)}\n`);
    process.exitCode = 1;
  });
}
