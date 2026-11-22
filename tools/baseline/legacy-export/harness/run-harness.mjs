import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { desktop, desktopRequire, log, sha256 } from '../../lib.mjs';
import { sqlGlobPlugin } from './sql-glob.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const harnessRoot = here;

export const legacyExportRoot = resolve(here, '..');

const tools = () => ({ esbuild: desktopRequire('esbuild'), chromium: desktopRequire('playwright').chromium });

const SQLITE_WASM = resolve(desktop, 'mobile/node_modules/@sqlite.org/sqlite-wasm/dist/sqlite3.wasm');
const SQLITE_PROXY = resolve(desktop, 'mobile/node_modules/@sqlite.org/sqlite-wasm/dist/sqlite3-opfs-async-proxy.js');

const EDGE = '/opt/microsoft/msedge/msedge';

const COOP = {
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Embedder-Policy': 'require-corp',
  'Cross-Origin-Resource-Policy': 'same-origin',
};

export async function buildWorker() {
  return bundle(harnessRoot, 'worker.ts', 'legacy-export-worker.ts');
}

export async function buildOneShotWorker() {
  return bundle(resolve(harnessRoot, '..', 'one-shot'), 'worker.ts', 'legacy-export-one-shot-worker.ts');
}

async function bundle(sourceRoot, file, sourcefile) {
  const { esbuild } = tools();
  const built = await esbuild.build({
    stdin: {
      contents: readFileSync(resolve(sourceRoot, file), 'utf8'),
      resolveDir: desktop,
      sourcefile,
      loader: 'ts',
    },
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'browser',
    target: 'es2022',
    plugins: [sqlGlobPlugin(desktop)],
    logLevel: 'silent',
    absWorkingDir: desktop,

    alias: {
      '@dstu': desktop,
      '@harness': legacyExportRoot,
      'decode-named-character-reference': resolve(desktop, 'node_modules/decode-named-character-reference/index.js'),
    },
    metafile: true,
  });
  const text = built.outputFiles[0].text;
  return { text, bytes: Buffer.byteLength(text), inputs: Object.keys(built.metafile.inputs) };
}

export async function collect({ samples, delivery = 'http', timeoutMs = 600_000, onConsole = null, oneShot = false } = {}) {
  const { chromium } = tools();
  const worker = await buildWorker();
  const oneShotWorker = oneShot ? await buildOneShotWorker() : null;
  log('legacy-export.bundle', {
    bytes: worker.bytes, inputs: worker.inputs.length,
    ...(oneShotWorker === null ? {} : { oneShotBytes: oneShotWorker.bytes }),
  });
  const page = readFileSync(resolve(harnessRoot, 'page.html'), 'utf8');
  const zips = new Map();
  const uploads = [];

  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (url.pathname === '/result/zip/') { response.writeHead(404, COOP); response.end(); return; }
    if (url.pathname.startsWith('/result/zip/') && request.method === 'POST') {
      const chunks = [];
      request.on('data', chunk => chunks.push(chunk));
      request.on('end', () => {
        const bytes = Buffer.concat(chunks);
        const sample = url.pathname.slice('/result/zip/'.length);
        zips.set(sample, bytes);
        uploads.push({ sample, bytes: bytes.length, sha256: sha256(bytes) });
        response.writeHead(200, { ...COOP, 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ ok: true, bytes: bytes.length }));
      });
      return;
    }
    const file = (() => {
      if (url.pathname === '/' || url.pathname === '/index.html') return { body: page, type: 'text/html; charset=utf-8' };
      if (url.pathname === '/harness/worker.js') return { body: worker.text, type: 'text/javascript; charset=utf-8' };
      if (url.pathname === '/harness/one-shot.js' && oneShotWorker !== null) return { body: oneShotWorker.text, type: 'text/javascript; charset=utf-8' };
      if (url.pathname === '/harness/sqlite3.wasm') return { body: readFileSync(SQLITE_WASM), type: 'application/wasm' };
      if (url.pathname === '/harness/sqlite3-opfs-async-proxy.js') return { body: readFileSync(SQLITE_PROXY), type: 'text/javascript; charset=utf-8' };
      return null;
    })();
    if (file === null) {
      log('legacy-export.request', { status: 404, path: url.pathname });
      response.writeHead(404, COOP); response.end('not found'); return;
    }
    log('legacy-export.request', { status: 200, path: url.pathname, bytes: file.body.length });
    response.writeHead(200, { ...COOP, 'Content-Type': file.type, 'Cache-Control': 'no-store' });
    response.end(file.body);
  });

  await new Promise((resolveListen, rejectListen) => {
    server.once('error', rejectListen);
    server.listen(0, '127.0.0.1', resolveListen);
  });
  const { port } = server.address();
  const origin = `http://127.0.0.1:${port}`;
  log('legacy-export.origin', { origin, crossOriginIsolated: true });

  let browser;
  const consoleLines = [];
  try {
    browser = await chromium.launch({
      executablePath: EDGE,
      headless: true,
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
    });
    const context = await browser.newContext({ acceptDownloads: false });
    const tab = await context.newPage();
    tab.on('console', message => {
      const line = `${message.type()} ${message.text()}`;
      if (consoleLines.length < 400) consoleLines.push(line);
      onConsole?.(line);
    });
    tab.on('pageerror', error => { consoleLines.push(`pageerror ${error.message}`); onConsole?.(`pageerror ${error.message}`); });
    await tab.goto(`${origin}/`, { waitUntil: 'load' });
    await tab.waitForFunction(() => globalThis.__harnessReady === true, null, { timeout: 30_000 });
    // 保存通道能力探针：在真实执行环境里测 navigator.share / File System Access / <a download>，
    // 结果作为 payload 传给 Worker，由生产分支顺序推导 `mobile:save-backup` 会返回哪个 method。
    const saveCapabilities = await tab.evaluate(() => globalThis.__saveCapabilities());
    const result = await tab.evaluate(
      async ({ payload, timeout }) => {
        const running = globalThis.__runHarness(payload);
        const guard = new Promise((_, reject) => setTimeout(() => reject(new Error('harness timeout')), timeout));
        return Promise.race([running, guard]);
      },
      { payload: { samples, delivery, capabilities: saveCapabilities }, timeout: timeoutMs },
    );
    // 一次性过渡版本：在同一个真实 OPFS 上再跑一次“只导出、不种数据、不迁移”的路径。
    // 结果先写回应用内备份目录（交付路径），再由本页把同一份字节 POST 回来（校验路径）。
    let oneShotResult = null;
    if (oneShotWorker !== null) {
      await tab.waitForFunction(() => globalThis.__runOneShotReady === true, null, { timeout: 30_000 });
      oneShotResult = await tab.evaluate(
        async timeout => {
          const running = globalThis.__runOneShot({ samples: ['probe'], delivery: 'opfs', backupIdPrefix: 'one-shot-fixture' });
          const guard = new Promise((_, reject) => setTimeout(() => reject(new Error('one-shot timeout')), timeout));
          return Promise.race([running, guard]);
        },
        timeoutMs,
      );
      const path = oneShotResult.exported[0].delivered.path;
      const uploaded = await tab.evaluate(async target => {
        const root = await navigator.storage.getDirectory();
        const parts = target.split('/').filter(part => part !== '' && part !== '.');
        const name = parts.pop();
        let directory = root;
        for (const part of parts) directory = await directory.getDirectoryHandle(part);
        const file = await (await directory.getFileHandle(name)).getFile();
        const response = await fetch('/result/zip/one-shot', { method: 'POST', body: await file.arrayBuffer() });
        return { status: response.status, bytes: file.size };
      }, path);
      if (uploaded.status !== 200) throw new Error(`one-shot zip 回传失败: ${uploaded.status}`);
    }
    return {
      result, zips, uploads, oneShot: oneShotResult, origin, saveCapabilities,
      environment: { browser: await browser.version(), workerBytes: worker.bytes, oneShotBytes: oneShotWorker?.bytes ?? null },
    };
  } finally {
    if (browser) await browser.close();
    server.closeAllConnections();
    await new Promise(resolveClose => server.close(resolveClose));
  }
}
