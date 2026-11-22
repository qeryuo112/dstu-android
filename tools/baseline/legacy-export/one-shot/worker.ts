import { createSqliteRuntime } from '@dstu/mobile/src/worker/sqlite-runtime.ts';
import { MobileStorageService } from '@dstu/host/platform/storage-mobile/service.ts';
import { MobileBackupService } from '@dstu/host/platform/storage-mobile/backup.ts';
import { createOpfsFiles, mobileRoots } from '@dstu/host/platform/files/opfs.ts';
import { createWebCrypto } from '@dstu/host/platform/crypto/web.ts';
import { sha256HexSync } from '@dstu/host/platform/crypto/sync.ts';

const APP_VERSION = '0.20.0';
const DATA = '/deep-student';

interface OneShotPayload {
  readonly samples?: readonly string[];
  readonly delivery?: 'opfs';
  readonly backupIdPrefix?: string;
}

const scope = globalThis as {
  postMessage?: (value: unknown) => void;
  onmessage?: ((event: MessageEvent) => void) | null;
};

const progress = (step: string): void => {
  scope.postMessage?.({ progress: `${step} +${Math.round(performance.now())}ms` });
};

scope.onmessage = (event: MessageEvent) => {
  void run(event.data as OneShotPayload)
    .then(result => scope.postMessage?.({ ok: true, result }))
    .catch(error => scope.postMessage?.({ ok: false, error: error instanceof Error ? `${error.stack ?? error.message}` : String(error) }));
};

async function run(payload: OneShotPayload): Promise<Record<string, unknown>> {
  progress('sqlite-runtime');
  const { runtime, timings } = await createSqliteRuntime();
  const root = await navigator.storage.getDirectory();
  const files = createOpfsFiles({ root, roots: mobileRoots(DATA) });
  const ctx = {
    app: { version: APP_VERSION, dataDir: DATA },
    logger: (target: string) => {
      const write = (level: string) => (message: string) => {
        progress(`${level} ${target} ${message}`);
      };
      return { error: write('error'), warn: write('warn'), info: write('info'), debug: () => undefined, trace: () => undefined };
    },
    reflect: { get: (name: string) => (name === 'files' ? files : undefined) },
    crypto: createWebCrypto(),
    // 一次性路径**不使用**旧保存通道：`mobile:save-backup` 在 Android 上不可靠（见 artifact 的 channelFacts）。
    // 这里显式报错，避免把“看起来成功”的假返回值写进任何结果。
    shell: { request: async (op: string): Promise<never> => { throw new Error(`one-shot 不调用 shell 通道（${op}）`); } },
    backendEvents: { emit: () => undefined },
  };
  const storage = new MobileStorageService({ runtime, baseDir: DATA, log: (level, message) => progress(`${level} storage ${message}`) });
  // 注意：不调用 runAll()。旧库里已有旧版应用迁移的结果；导出不应写库。
  progress('export');
  const backup = new MobileBackupService({ ctx: ctx as never, storage, runtime: runtime as never, files });
  const prefix = payload.backupIdPrefix ?? `one-shot-${Date.now()}`;
  const exported = [];
  for (const sample of payload.samples ?? ['legacy']) {
    const id = `${prefix}-${sample}`;
    const result = await backup.exportBackup(id, null);
    const bytes = await files.readBytes(result.zipPath);
    const target = `${storage.locations.baseDir}/recovery/backups/${id}.zip`;
    await files.writeBytes(target, bytes, { createParents: true });
    exported.push({
      sample, backupId: id, zipPath: result.zipPath, bytes: bytes.byteLength,
      sha256: sha256HexSync(bytes), fileCount: result.fileCount,
      delivered: { method: 'opfs', path: target },
    });
  }
  progress('done');
  return {
    environment: {
      sqlite: runtime.sqlite3.capi.sqlite3_libversion(),
      origin: location.origin,
      secureContext: isSecureContext,
      appVersion: APP_VERSION,
      userAgent: navigator.userAgent,
    },
    poolTimings: timings,
    exported,
  };
}
