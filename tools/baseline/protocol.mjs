/**
 * S0.10 — 冻结实际同步协议与版本窗口。
 *
 * 基线来自桌面实际生产调用链 `host/plugins/sync/src/run-sync.ts:131`（runSync），不是接口全集：
 * 每个 wire/manifest/key/provider 字段都由 AST 从声明类型提取，并绑定到该链上的调用点；
 * 版本窗口和 key/格式字节由桌面真实函数产出（内存 CloudStorage + 生产 codec/zstd）。
 * 两个场景驱动真实 runSync：同 schema 双向往返；异 schema（本端旧）拒绝业务发布、pending 保留，再原地迁移升级发布。
 */
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { artifact, main, root, desktop, git, readDesktop, readJson, sha256, log, parse, visit, ts, sourceFiles, location } from './lib.mjs';

const unit = 'S0.10';
const planFile = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const previousVfsVersion = 20261111;
const desktopCommit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const hashes = files => Object.fromEntries(files.map(file => [file, sha256(readDesktop(file))]));
const lineOf = (file, needle, occurrence = 1) => {
  const lines = readDesktop(file).split('\n');
  let seen = 0;
  for (const [index, text] of lines.entries()) if (text.includes(needle) && ++seen === occurrence) return index + 1;
  throw new Error(`${file}: missing ${needle}`);
};
const at = (file, needle, symbol, extra = {}) => ({ source: 'desktop', file, line: lineOf(file, needle), symbol, ...extra });

/* ------------------------------------------------------------------ AST 字段提取 */

const typeSources = {
  SyncFormatDescriptor: ['host/plugins/sync/src/remote-format.ts', 'SyncFormatDescriptor'],
  SyncShardCheckpoint: ['host/plugins/sync/src/remote-format.ts', 'SyncShardCheckpoint'],
  SyncCompatibilityLease: ['host/plugins/sync/src/remote-format.ts', 'SyncCompatibilityLease'],
  SyncManifest: ['host/plugins/sync/src/merge.ts', 'SyncManifest'],
  DatabaseSyncState: ['host/plugins/sync/src/merge.ts', 'DatabaseSyncState'],
  EngineChange: ['host/plugins/sync/src/change-payload.ts', 'EngineChange'],
  ChangesPayload: ['host/plugins/sync/src/download-payload.ts', 'ChangesPayload'],
  LeaseRecord: ['host/plugins/sync-providers/src/lease.ts', 'LeaseRecord'],
  CloudStorageConfig: ['contracts/ipc/generated/_shared.ts', 'CloudStorageConfig'],
  WebDavConfig: ['contracts/ipc/generated/_shared.ts', 'WebDavConfig'],
  S3Config: ['contracts/ipc/generated/_shared.ts', 'S3Config'],
  FtpConfig: ['contracts/ipc/generated/_shared.ts', 'FtpConfig'],
  SyncExecutionResponse: ['contracts/ipc/generated/L20-sync.ts', 'SyncExecutionResponse'],
};
const aliases = { SyncChangeWithData: 'EngineChange' };

/** type/interface 每个公开字段：名称、可选性、原始类型文本、行号。 */
function extractType(name) {
  const seen = alias => aliases[alias] ?? alias;
  const [file, symbol] = typeSources[seen(name)];
  const sf = parse(file);
  let node;
  visit(sf, candidate => {
    if ((ts.isInterfaceDeclaration(candidate) || ts.isTypeAliasDeclaration(candidate)) && candidate.name.text === symbol) node = candidate;
  });
  assert.ok(node, `${file}: ${symbol}`);
  const members = ts.isInterfaceDeclaration(node) ? node.members : node.type.members;
  assert.ok(members, `${file}: ${symbol} must declare members`);
  const properties = members.filter(ts.isPropertySignature);
  if (properties.length === 0) return { symbol, file, line: location(file, sf, node).line, union: node.type?.getText() ?? null, fields: [] };
  return {
    symbol, file, line: location(file, sf, node).line,
    fields: properties.map(property => ({
      name: property.name.getText().replace(/^['"]|['"]$/g, ''),
      optional: property.questionToken !== undefined,
      type: property.type.getText(),
      line: location(file, sf, property).line,
    })),
  };
}

function runSyncChain() {
  const file = 'host/plugins/sync/src/run-sync.ts';
  const sf = parse(file);
  const fn = sf.statements.find(statement => ts.isFunctionDeclaration(statement) && statement.name?.text === 'runSync');
  assert.ok(fn, `${file}: runSync declaration`);
  const body = fn.body;
  const calls = [];
  visit(body, node => {
    if (!ts.isCallExpression(node)) return;
    const callee = node.expression.getText();
    const line = location(file, sf, node).line;
    calls.push({ callee, line, statement: ts.isExpressionStatement(node.parent) ? node.parent.getText().replace(/\s+/gu, ' ').slice(0, 220) : null });
  });
  const bodyStart = sf.getLineAndCharacterOfPosition(fn.body.getStart(sf)).line + 1;
  const bodyEnd = sf.getLineAndCharacterOfPosition(fn.body.getEnd()).line + 1;
  return { entry: { file, line: location(file, sf, fn).line, symbol: 'runSync' }, body: { start: bodyStart, end: bodyEnd }, calls };
}

function declarationLine(file, symbol) {
  const sf = parse(file);
  let found = null;
  visit(sf, node => {
    if (found !== null || !(ts.isFunctionDeclaration(node) || ts.isVariableDeclaration(node))) return;
    if (node.name?.getText() === symbol) found = node;
  });
  assert.ok(found, `${file}: ${symbol}`);
  const line = location(file, sf, found).line;
  return { source: 'desktop', file, line, symbol, text: readDesktop(file).split('\n')[line - 1].trim() };
}

/* ------------------------------------------------------------------ 真实生产函数装配 */

const protocolSources = [
  'host/plugins/sync/src/run-sync.ts', 'host/plugins/sync/src/remote-format.ts', 'host/plugins/sync/src/manifest-upload.ts',
  'host/plugins/sync/src/remote-manifest.ts', 'host/plugins/sync/src/change-keys.ts', 'host/plugins/sync/src/change-payload.ts',
  'host/plugins/sync/src/download-payload.ts', 'host/plugins/sync/src/upload-changes.ts', 'host/plugins/sync/src/download-changes.ts',
  'host/plugins/sync/src/payload.ts', 'host/plugins/sync/src/merge.ts', 'host/plugins/sync/src/record-plain.ts',
  'host/plugins/sync/src/collect-sync-changes.ts', 'host/plugins/sync/src/transfer-commands.ts', 'host/plugins/sync/src/plugin.ts',
  'host/plugins/sync/repository/state.ts', 'host/plugins/sync/repository/registry-preflight.ts', 'host/plugins/sync/repository/apply-databases.ts',
  'host/plugins/sync-providers/src/sync_lease.ts', 'host/plugins/sync-providers/src/lease.ts', 'host/plugins/sync-providers/src/config.ts',
  'host/plugins/sync-providers/src/entry.ts', 'host/plugins/sync-providers/src/desktop-platform.ts', 'host/plugins/sync-providers/mobile.ts',
  'host/plugins/sync-support/src/tombstone-model.ts',
];
const moduleExports = `
export {
  CURRENT_FORMAT_VERSION, MIN_COMPATIBLE_FORMAT_VERSION, COMPATIBILITY_RELEASE_DAYS,
  FORMAT_KEY, MANIFESTS_PREFIX, LEGACY_MANIFEST_KEY, SYNC_TARGET_LEASE_PREFIX,
  ASSET_OBJECT_PREFIX, ASSET_MANIFEST_PREFIX, BLOB_TOMBSTONE_PREFIX, ASSET_TOMBSTONE_PREFIX,
  WS_TOMBSTONE_PREFIX, TOMBSTONE_EVENTS_PREFIX, DEFAULT_SYNC_LEASE_TTL_MS,
  SYNC_TARGET_LEASE_FORMAT_VERSION, validateRemoteFormat,
} from './host/plugins/sync/src/remote-format.ts';
export * from './host/plugins/sync/src/manifest-upload.ts';
export { parseSyncManifest, deviceManifestKey, deviceManifestLegacyKey } from './host/plugins/sync/src/remote-manifest.ts';
export { buildChangeKeyV3, buildChangeKeyV4, parseChangeKey } from './host/plugins/sync/src/change-keys.ts';
export * from './host/plugins/sync/src/change-payload.ts';
export * from './host/plugins/sync/src/download-payload.ts';
export * from './host/plugins/sync/src/merge.ts';
export * from './host/plugins/sync/src/payload.ts';
export * from './host/plugins/sync-providers/src/config.ts';
export * from './host/plugins/sync-providers/src/sync_lease.ts';
export * from './host/plugins/sync-support/src/tombstone-model.ts';
export { deviceIdShortHash } from './host/plugins/sync-support/src/tombstone-model.ts';
export { MemoryStorage } from './host/plugins/sync-providers/tests/memory_storage.ts';
`;

async function loadDesktop(contents) {
  const esbuild = (await import('./desktop-module.mjs')).loadDesktopModule;
  return esbuild(contents);
}

/** 编译证据同时覆盖实现模块与透明 wrapper，避免只 hash 重新导出的入口。 */
async function moduleBinding(contents, implementation, wrappers) {
  const loaded = await loadDesktop(contents);
  const inputs = new Set(loaded.inputs.map(source => source.file));
  for (const file of [...implementation, ...wrappers]) assert.ok(inputs.has(file), `${file}: consumed by protocol module`);
  return {
    implementation: loaded.inputs.filter(source => implementation.includes(source.file)),
    wrappers: loaded.inputs.filter(source => wrappers.includes(source.file)),
  };
}

const logger = () => ({ info() {}, warn() {}, debug() {}, error() {} });
const silent = { info() {}, warn() {}, debug() {}, error() {} };

/** 生产 codec/zstd/CloudStorage：字节与容器都来自桌面实现，测试替身只替换传输。 */
async function codecs() {
  const [payload, crypto, zstd, memory] = await Promise.all([
    load('host/plugins/sync/src/payload.ts'), load('host/platform/secrets/backup-crypto.ts'),
    load('host/platform/native/compression/index.ts'), load('host/plugins/sync-providers/tests/memory_storage.ts'),
  ]);
  return {
    create: () => payload.createPayloadCodec(crypto.createBackupCrypto(), null),
    encrypted: () => payload.createPayloadCodec(crypto.createBackupCrypto(), 's010-passphrase'),
    zstd: zstd.compression,
    MemoryStorage: memory.MemoryStorage,
  };
}

const hex = bytes => Buffer.from(bytes).toString('hex');
/** 对象键里的 UUID 与 epoch 秒每次都变，golden 只保留形状。 */
const strip = key => key.replace(/-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/u, '-<uuid>')
  .replace(/-\d{10}(?=-)/u, '-<epochSeconds>');

const planeOf = key => key === 'data_governance/format.json' ? 'control:formatDescriptor'
  : key.startsWith('data_governance/locks/sync-target/') ? 'control:targetLease'
  : key === 'data_governance/instance.json' ? 'control:instanceMetadata'
  : key === '.encryption-marker' ? 'control:encryptionMarker'
  : 'publication';

function traceCloud(storage) {
  const puts = [], deletes = [];
  const put = storage.put.bind(storage), remove = storage.delete.bind(storage);
  storage.put = async (key, data) => { puts.push({ key, plane: planeOf(key) }); return put(key, data); };
  storage.delete = async key => { deletes.push({ key, plane: planeOf(key) }); return remove(key); };
  const snapshot = () => new Map(storage.keys().map(key => [key, sha(storage.bytes(key) ?? new Uint8Array())]));
  const changedKeys = (before, after) => [...new Set([...before.keys(), ...after.keys()])]
    .filter(key => before.get(key) !== after.get(key)).map(strip).sort();
  const summary = list => {
    const counts = new Map();
    for (const item of list) {
      const id = `${item.plane} ${item.key}`.replace(/(sync-target\/)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.json/u, '$1<operationId>.json');
      counts.set(id, (counts.get(id) ?? 0) + 1);
    }
    return [...counts].sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0)).map(([key, count]) => ({ key, count }));
  };
  return {
    storage, snapshot, changedKeys,
    reset() { puts.length = 0; deletes.length = 0; },
    report: () => ({ puts: summary(puts), deletes: summary(deletes),
      publication: [...puts, ...deletes].filter(item => item.plane === 'publication').length }),
  };
}

/** runSync 结果里只有 duration_ms 随机器变化；其余字段都是协议事实，进 golden。 */
const outcome = result => Object.fromEntries(Object.entries(result).filter(([key]) => key !== 'duration_ms').map(([key, value]) => [key, value]));

const sha = bytes => sha256(Buffer.from(bytes));

/* ------------------------------------------------------------------ 场景：真实 runSync */

async function runSyncHarness() {
  const { createStorageService } = await load('host/platform/storage/service.ts');
  const { Connection } = await load('host/platform/storage/connection.ts');
  const refinery = await load('host/platform/storage/migration/refinery.ts');
  const localRuntime = await load('host/plugins/sync/src/local-runtime.ts');
  const nodeRuntime = await load('host/plugins/sync/src/local-runtime.node.ts');
  localRuntime.setLocalRuntime(nodeRuntime.nodeLocalRuntime);
  const { runSync } = await load('host/plugins/sync/src/run-sync.ts');
  const { createSyncSupport } = await load('host/plugins/sync-support/src/service.ts');
  const { SqliteSyncStateStore } = await load('host/plugins/sync-support/src/state.ts');
  const { applyDownloadedChangesToDatabases } = await load('host/plugins/sync/repository/apply-databases.ts');
  const migrationsDir = resolve(root, 'config/schema/history');
  const migrations = refinery.loadSqlMigrations(resolve(migrationsDir, 'vfs'));
  const latest = migrations.at(-1).version;
  const previous = migrations.find(item => item.version === previousVfsVersion)?.version;
  assert.ok(previous !== undefined, `vfs migration V${previousVfsVersion} anchors the old-reader scenario`);
  const crypto = await load('host/platform/secrets/backup-crypto.ts');
  const zstd = await load('host/platform/native/compression/index.ts');

  const store = () => {
    const values = new Map();
    return {
      get: key => values.get(key) ?? null, save: (key, value) => values.set(key, value), delete: key => values.delete(key),
      deleteByPrefix: () => 0, listByPrefix: prefix => [...values].filter(([key]) => key.startsWith(prefix))
        .map(([key, value]) => ({ key, value, updatedAt: '2026-11-15T00:00:00Z' })),
      getSecret: async () => null, saveSecret: async () => {}, deleteSecret: async () => false,
    };
  };

  /** 设备 = 真实 storage（历史 SQL 迁移）+ 真实 sync_state/tombstones；reopen 复用同一目录。 */
  async function device(base, label, deviceId, schemaVersion) {
    const dir = join(base, label);
    const audits = [];
    const cloudRef = { current: null };
    let current = null;
    const open = () => createStorageService({ baseDir: join(dir, 'base'), dataLocalDir: join(dir, 'local'), migrationsDir });
    async function mount(migrate) {
      const storage = open();
      if (migrate) await storage.migrations.runAll();
      const state = new SqliteSyncStateStore(() => storage.database('sync_state'));
      const support = createSyncSupport({ state, notes: () => null, logger: silent });
      const cloudConfig = { provider: 'webdav', root: 's0-10', webdav: { endpoint: 'https://sync.invalid/dav', username: 'u', password: 'p' } };
      const ctx = {
        logger: () => logger(), vfsDatabase: { isInMaintenanceMode: () => false },
        backup: {
          hydrateCloudConfig: async () => {}, syncCommandPorts: () => ({
            limiter: { tryAcquire: () => ({ release() {} }) }, loadHydratedCloudConfig: async () => null,
            saveSyncAudit: audit => audits.push(audit),
          }),
        },
        paths: { deviceId: async () => deviceId },
        syncProvider: {
          createStorage: async () => cloudRef.current, setCancelToken() {}, clearCancelToken() {}, isSyncCancelled: () => false,
          setStatusHook() {}, clearStatusHook() {},
        },
        secrets: { backup: crypto.createBackupCrypto() }, storage, syncSupport: support, app: { dataDir: join(dir, 'base') },
        reflect: { get: () => store() }, nativeCompression: zstd.compression,
        sync: { applyDownloadedChangesToDatabases: (changes, strategy) => applyDownloadedChangesToDatabases(storage, changes, strategy, deviceId) },
      };
      const ports = { tombstones: support.tombstones, workspaceCoordinator: { enterMaintenanceMode: async () => {}, exitMaintenanceMode: () => {} } };
      current = { storage, state, support, ctx, ports, cloudConfig };
      return current;
    }
    const mounted = await mount(true);
    if (schemaVersion !== latest) {
      const path = mounted.storage.pathOf('vfs');
      mounted.storage.shutdown();
      for (const suffix of ['', '-wal', '-shm']) rmSync(`${path}${suffix}`, { force: true });
      const connection = new Connection(path, { label, profile: { foreignKeys: true } });
      try { new refinery.Runner(migrations, { target: { kind: 'Version', version: schemaVersion } }).run(connection); }
      finally { connection.close(); }
      await mount(false);
    }
    const api = {
      label, deviceId, schemaVersion, audits, cloudConfig: mounted.cloudConfig,
      use(candidate) { cloudRef.current = candidate; return api; },
      sync: (direction, strategy = null) => runSync(current.ctx, current.ports, direction, current.cloudConfig, strategy, null),
      vfs: () => current.storage.database('vfs'),
      pendingRows: () => current.storage.database('vfs').all('SELECT id, table_name, record_id, operation, sync_version FROM __change_log WHERE sync_version = 0 ORDER BY id'),
      pending: () => current.storage.database('vfs').pluck('SELECT COUNT(*) FROM __change_log WHERE sync_version = 0'),
      pendingByDatabase: () => Object.fromEntries(['vfs', 'llm_usage', 'chat_v2', 'mistakes'].map(name => [name,
        current.storage.database(name).pluck('SELECT COUNT(*) FROM __change_log WHERE sync_version = 0')])),
      schemaVersionNow: () => current.storage.database('vfs').pluck('SELECT MAX(version) FROM refinery_schema_history'),
      cursors: () => current.state.cursors(JSON.parse(new TextDecoder().decode(instanceBytes())).instance_id),
      /** 关闭同路径 storage，再用真实 MigrationService 跑完剩余历史迁移。 */
      async upgrade() {
        current.storage.shutdown();
        await mount(true);
        return api.schemaVersionNow();
      },
      close: () => current.storage.shutdown(),
    };
    const instanceBytes = () => cloudRef.current.bytes('data_governance/instance.json');
    return api;
  }

  return { device, latest, previous, runSync };
}

/** 同 schema 两个设备（应用版本只是标签，不模拟两套应用二进制）：A 写 → B 拉 → B 改 → A 拉。 */
async function sameSchemaScenario(harness, codecsFactory) {
  const temp = mkdtempSync(join(tmpdir(), 's0-10-same-'));
  const cloud = new (codecsFactory.MemoryStorage)('memory-webdav');
  try {
    const a = await harness.device(temp, 'a', 'device-s010-a', harness.latest);
    const b = await harness.device(temp, 'b', 'device-s010-b', harness.latest);
    a.use(cloud); b.use(cloud);
    for (const device of [a, b]) {
      const insert = device.vfs();
      insert.exec(`INSERT INTO resources(id, hash, type, created_at, updated_at) VALUES('res_s010_${device.label}','hash_s010_${device.label}','note','2026-11-15T00:00:00Z','2026-11-15T00:00:00Z')`);
      insert.exec(`INSERT INTO notes(id, resource_id, title, created_at, updated_at) VALUES('note_s010_${device.label}','res_s010_${device.label}','title from ${device.label}','2026-11-15T00:00:00Z','2026-11-15T00:00:00Z')`);
    }
    const aUpload = await a.sync('upload');
    const bDownload = await b.sync('download');
    const bRowAfterDownload = b.vfs().get("SELECT id, title FROM notes WHERE id = 'note_s010_a'");
    b.vfs().exec("UPDATE notes SET title = 'edited on b', updated_at = '2026-11-15T00:00:10Z' WHERE id = 'note_s010_a'");
    const bUpload = await b.sync('upload');
    const aDownload = await a.sync('download');
    const aRowAfterDownload = a.vfs().get("SELECT id, title FROM notes WHERE id = 'note_s010_a'");
    const summary = {
      scenario: 'same-schema-two-devices', schemaVersion: harness.latest,
      applicationVersionLabels: { 'device-s010-a': '0.20.0', 'device-s010-b': '0.21.0' },
      applicationVersionScope: '文档标签：两设备使用同一生产 sync 代码；wire/manifest 中没有应用版本字段，因此本场景不证明跨应用二进制兼容',
      results: { aUpload: outcome(aUpload), bDownload: outcome(bDownload), bUpload: outcome(bUpload), aDownload: outcome(aDownload) },
      observations: {
        bSawAData: bRowAfterDownload, aSawBEdit: aRowAfterDownload,
        aPendingAfterUpload: a.pending(), bPendingAfterUpload: b.pending(),
        cursors: [...a.cursors()].map(([device, seq]) => [device, `${seq}`]),
      },
      cloudKeys: cloud.keys().filter(key => !key.includes('/locks/')).map(strip),
      passed: aUpload.success && bDownload.success && bUpload.success && aDownload.success
        && bRowAfterDownload?.title === 'title from a' && aRowAfterDownload?.title === 'edited on b',
    };
    for (const device of [a, b]) device.close();
    return summary;
  } finally { rmSync(temp, { recursive: true, force: true }); }
}

/**
 * 异 schema：A 已是基线 schema；C 停在上一迁移版本，先在旧 schema 写入 note_c/res_c。
 * C 上传被 schema 门槛拒绝时，业务发布写入必须为 0，pending 与本地记录不变（format/lease 属控制面）。
 * 之后在同一目录/同一 deviceId 上跑真实迁移升级，不新增替代数据，再上传并让 A 下载读到 note_c。
 */
async function schemaMismatchScenario(harness, codecsFactory) {
  const temp = mkdtempSync(join(tmpdir(), 's0-10-schema-'));
  const cloud = new (codecsFactory.MemoryStorage)('memory-webdav');
  const trace = traceCloud(cloud);
  try {
    const a = await harness.device(temp, 'a', 'device-s010-a', harness.latest);
    a.use(cloud);
    a.vfs().exec(`INSERT INTO resources(id, hash, type, created_at, updated_at) VALUES('res_base','hash_base','note','2026-11-15T00:00:00Z','2026-11-15T00:00:00Z')`);
    a.vfs().exec(`INSERT INTO notes(id, resource_id, title, created_at, updated_at) VALUES('note_base','res_base','baseline','2026-11-15T00:00:00Z','2026-11-15T00:00:00Z')`);
    const aUpload = await a.sync('upload');

    const c = await harness.device(temp, 'c', 'device-s010-c', harness.previous);
    c.use(cloud);
    const oldColumns = c.vfs().all("PRAGMA table_info('notes')").map(row => row.name);
    const addedColumn = a.vfs().all("PRAGMA table_info('notes')").map(row => row.name).find(column => !oldColumns.includes(column)) ?? null;
    c.vfs().exec(`INSERT INTO resources(id, hash, type, created_at, updated_at) VALUES('res_c','hash_c','note','2026-11-15T00:00:20Z','2026-11-15T00:00:20Z')`);
    c.vfs().exec(`INSERT INTO notes(id, resource_id, title, created_at, updated_at) VALUES('note_c','res_c','pending from c','2026-11-15T00:00:20Z','2026-11-15T00:00:20Z')`);

    const pendingBefore = c.pendingRows();
    const pendingByDatabaseBefore = c.pendingByDatabase();
    const schemaBefore = c.schemaVersionNow();
    const snapshotBefore = trace.snapshot();
    trace.reset();
    let uploadResult = null, rejection = null;
    try { uploadResult = await c.sync('upload'); } catch (error) { rejection = { name: error.constructor.name, message: error.message }; }
    const writeReport = trace.report();
    const snapshotAfter = trace.snapshot();
    const changed = trace.changedKeys(snapshotBefore, snapshotAfter);
    const stateAfterRejection = {
      pending: c.pending(), pendingRows: c.pendingRows(), schema: c.schemaVersionNow(),
      noteRow: c.vfs().get("SELECT id, resource_id, title FROM notes WHERE id = 'note_c'"),
      resourceRow: c.vfs().get("SELECT id, hash FROM resources WHERE id = 'res_c'"),
      audits: c.audits.map(audit => ({ direction: audit.direction, outcome: audit.outcome.kind, recordsAffected: audit.recordsAffected })),
    };
    const rejectionRecord = {
      scenario: 'mismatched-schema-local-older', cloudSchemaVersion: harness.latest, localSchemaVersion: schemaBefore,
      downgradedDatabase: 'vfs', pendingOrigin: 'note_c/res_c inserted on the old vfs schema by this scenario; the mistakes row is the migration-time default deck seed, not reinserted here',
      addedColumnOnCloudOnly: addedColumn, uploadReturnedNull: uploadResult === null, rejection,
      controlPlaneWrites: writeReport.puts.filter(item => item.key.startsWith('control:')),
      publicationWrites: writeReport.puts.filter(item => item.key.startsWith('publication:')),
      controlPlaneDeletes: writeReport.deletes.filter(item => item.key.startsWith('control:')),
      publicationDeletes: writeReport.deletes.filter(item => item.key.startsWith('publication:')),
      publicationWriteCount: writeReport.publication,
      cloudKeysChanged: changed,
      pendingUnchanged: JSON.stringify(pendingBefore) === JSON.stringify(stateAfterRejection.pendingRows),
      localRowsUnchanged: stateAfterRejection.noteRow?.title === 'pending from c'
        && stateAfterRejection.resourceRow?.hash === 'hash_c' && stateAfterRejection.schema === schemaBefore,
      audit: stateAfterRejection.audits,
      passed: uploadResult === null && rejection !== null
        && writeReport.publication === 0
        && !changed.some(key => key.includes('/manifests/') || key.includes('/changes/') || key.includes('v4/shards/')
          || key.includes('/preferences/') || key.includes('/file_manifests/') || key.includes('/blobs') || key.includes('/assets') || key.includes('/workspaces'))
        && JSON.stringify(pendingBefore) === JSON.stringify(stateAfterRejection.pendingRows)
        && stateAfterRejection.noteRow?.title === 'pending from c'
        && stateAfterRejection.audits.filter(audit => audit.outcome === 'complete').length === 0,
    };

    const schemaAfterUpgrade = await c.upgrade();
    // 迁移可能自己写入新的 pending 行（例如 mistakes 的默认牌组）；原两行必须仍在，新增行也要如实登记。
    const pendingAfterUpgrade = c.pendingRows();
    const pendingByDatabaseAfterUpgrade = c.pendingByDatabase();
    const publishableAfterUpgrade = Object.values(pendingByDatabaseAfterUpgrade).reduce((total, count) => total + Number(count), 0);
    const carriedKeys = new Set(pendingAfterUpgrade.map(row => `${row.table_name}/${row.record_id}/${row.operation}`));
    const originalCarried = pendingBefore.every(row => carriedKeys.has(`${row.table_name}/${row.record_id}/${row.operation}`));
    const addedDuringUpgrade = pendingAfterUpgrade.filter(row => !pendingBefore.some(before => before.id === row.id));
    const publicationBeforeUpgradeUpload = trace.report().publication;
    const upgradeUpload = await c.sync('upload');
    const writeAfterUpgrade = trace.report();
    const aDownload = await a.sync('download');
    const aSawNoteC = a.vfs().get("SELECT id, resource_id, title FROM notes WHERE id = 'note_c'");
    const upgradeRecord = {
      schemaFrom: schemaBefore, schemaTo: schemaAfterUpgrade,
      sameDirectorySameDeviceId: c.deviceId === 'device-s010-c',
      originalPendingCarried: originalCarried,
      pendingAtUpgrade: pendingAfterUpgrade,
      pendingByDatabaseBefore, pendingByDatabaseAfterUpgrade, publishableAfterUpgrade,
      pendingAddedByMigration: addedDuringUpgrade,
      dataReinserted: false,
      upload: outcome(upgradeUpload),
      publicationWritesDuringUpgradeUpload: writeAfterUpgrade.publication - publicationBeforeUpgradeUpload,
      pendingAfterUpload: c.pending(),
      aDownload: outcome(aDownload), aSawNoteC,
      passed: schemaAfterUpgrade === harness.latest && originalCarried && upgradeUpload.success
        && upgradeUpload.changes_uploaded === publishableAfterUpgrade && c.pending() === 0
        && Object.values(c.pendingByDatabase()).every(count => Number(count) === 0)
        && aDownload.success && aSawNoteC?.title === 'pending from c' && aSawNoteC?.resource_id === 'res_c',
    };

    for (const device of [a, c]) device.close();
    return { aUpload: outcome(aUpload), versions: { cloud: harness.latest, localBeforeUpgrade: schemaBefore, previousMigration: harness.previous },
      rejection: rejectionRecord, upgrade: upgradeRecord };
  } finally { rmSync(temp, { recursive: true, force: true }); }
}

/* ------------------------------------------------------------------ 采集 */

export async function collect() {
  const plan = readFileSync(resolve(root, planFile), 'utf8').split('\n');
  const planLine = plan.findIndex(text => text.startsWith('| S0.10 |')) + 1;
  const planSection = plan.findIndex(text => text.startsWith('### 8.2 同步')) + 1;
  assert.ok(planLine > 0 && planSection > 0, 'plan anchors');

  const chain = runSyncChain();
  const runSyncFile = 'host/plugins/sync/src/run-sync.ts';
  const inBody = needle => {
    const lines = readDesktop(runSyncFile).split('\n');
    for (let index = chain.body.start - 1; index < chain.body.end; index += 1) if (lines[index]?.includes(needle)) return index + 1;
    throw new Error(`${runSyncFile}: ${needle} inside runSync body ${chain.body.start}-${chain.body.end}`);
  };
  const stage = (needle, contract) => {
    const line = inBody(needle);
    const call = chain.calls.find(item => item.line === line);
    assert.ok(call, `${runSyncFile}:${line} must be a call inside runSync`);
    return { source: 'desktop', file: runSyncFile, line, callee: call.callee, contract };
  };

  const stageOrder = needle => inBody(needle);
  assert.ok(stageOrder('await validateRemoteFormat(storage, parsed !== \'Download\', options)') < stageOrder('lease = await acquireSyncTargetLease(storage, deviceId)'));
  assert.ok(stageOrder('lease = await acquireSyncTargetLease(storage, deviceId)') < stageOrder('await validateUploadSchemaCompatibility(storage, local, options)'));
  assert.ok(stageOrder('await validateUploadSchemaCompatibility(storage, local, options)') < stageOrder('const [result, skipped] = await executePlainSync(storage, enriched, local, parsed, merge'));

  const format = await (await loadDesktop(moduleExports)).exports;
  assert.equal(format.CURRENT_FORMAT_VERSION, 4, 'wire current version');
  assert.equal(format.MIN_COMPATIBLE_FORMAT_VERSION, 3, 'wire minimum reader');
  const codecSet = await codecs();
  const descriptorCodec = await codecSet.create();
  const emptyCloud = new codecSet.MemoryStorage('memory-format');
  const freshDescriptor = await format.validateRemoteFormat(emptyCloud, true, { deviceId: 'device-s010-a', codec: descriptorCodec });

  const descriptorBytes = emptyCloud.bytes(format.FORMAT_KEY);
  // 描述符里的 operation_id/expires_at 每次协商都会重生成；golden 只哈希去随机化后的字节。
  const descriptorText = new TextDecoder().decode(descriptorBytes)
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/u, '<uuid>')
    .replace(/\d{4}-\d\d-\d\dT[0-9:.]+\+00:00/u, '<rfc3339>');
  const window = [];
  for (const [label, text, willWrite] of [
    ['missing-write', null, true], ['missing-read', null, false],
    ['format-3', '{"format_version":3}', true], ['format-4-skeleton', '{"format_version":4}', true],
    ['format-4-min-4', '{"format_version":4,"min_client":4}', true],
    ['format-5', '{"format_version":5}', true], ['format-2', '{"format_version":2}', true],
    ['duplicate-min-client', '{"min_client":4,"min_reader_version":4}', true],
    ['corrupt', '{', true],
  ]) {
    const storage = new codecSet.MemoryStorage('memory-format');
    if (text !== null) await storage.put(format.FORMAT_KEY, new TextEncoder().encode(text));
    let outcome;
    try {
      const descriptor = await format.validateRemoteFormat(storage, willWrite, { deviceId: 'device-s010-a', codec: await codecSet.create() });
      const stored = storage.bytes(format.FORMAT_KEY);
      const input = text === null ? null : new TextEncoder().encode(text);
      const rewrote = stored !== null && (input === null || !Buffer.from(stored).equals(Buffer.from(input)));
      outcome = { accepted: true, format_version: descriptor.format_version, min_client: descriptor.min_reader_version,
        features: descriptor.features, compatibility_lease: descriptor.compatibility_lease === null ? null : {
          holder_device_id: descriptor.compatibility_lease.holder_device_id,
          min_writer_version: descriptor.compatibility_lease.min_writer_version,
          expires_at_format: /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?\+00:00$/u.test(descriptor.compatibility_lease.expires_at) ? 'RFC3339 UTC, optional 1-9 fractional digits' : descriptor.compatibility_lease.expires_at,
        }, rewroteFormatObject: rewrote };
    } catch (error) { outcome = { accepted: false, message: error.message }; }
    window.push({ label, input: text, willWrite, outcome });
  }
  assert.ok(window.find(item => item.label === 'format-5').outcome.accepted === false);
  assert.ok(window.find(item => item.label === 'format-2').outcome.accepted === false);
  assert.ok(window.find(item => item.label === 'format-4-min-4').outcome.rewroteFormatObject === false, 'v4 with min_client=4 is not rewritten');
  assert.ok(window.find(item => item.label === 'format-3').outcome.rewroteFormatObject === true, 'v3 document is upgraded in place with a lease');

  const keySamples = [];
  const fixed = JSON.parse('{"device":"device-s010-a","seq":"7","version":"1700000000"}');
  const v3 = format.buildChangeKeyV3(fixed.device, BigInt(fixed.seq), BigInt(fixed.version));
  const v4 = format.buildChangeKeyV4(fixed.device, BigInt(fixed.seq), BigInt(fixed.version));
  for (const [kind, key] of [['v3', v3], ['v4', v4]]) {
    const parsed = format.parseChangeKey(key);
    keySamples.push({ kind, key: strip(key), parsed: { kind: parsed.kind, deviceId: `${parsed.deviceId.length}hex`, seq: `${parsed.seq}`, version: `${parsed.version}` } });
  }
  const legacyKeys = [
    'data_governance/changes/<pathId>/1700000000.json.zst',
    'data_governance/changes/<pathId>/1700000000-abc.json',
  ];
  for (const key of legacyKeys) {
    const parsed = format.parseChangeKey(key);
    keySamples.push({ kind: 'legacy', key, parsed: parsed === null ? null : { kind: parsed.kind, version: `${parsed.version}` } });
  }
  assert.ok(keySamples.every(sample => sample.parsed !== null));

  const manifestKey = format.deviceManifestKey(fixed.device);
  const manifestShapes = Object.fromEntries(['v2-default', 'v3', 'v4-with-cursor'].map(label => {
    const base = {
      sync_transaction_id: 'tx', status: 'Complete', created_at: '2026-11-15T00:00:00+00:00', device_id: fixed.device,
      databases: { vfs: { schema_version: 20261111, data_version: 5, checksum: 'c', last_updated_at: '2026-11-15 00:00:00' } },
    };
    const payload = label === 'v2-default' ? base
      : label === 'v3' ? { ...base, format_version: 3, published_max_seq: 4 }
        : { ...base, format_version: 4, published_max_seq: 4, cursors: { [format.deviceIdShortHash(fixed.device)]: 9 }, snapshot_seen: { vfs: 'seen' }, superseded_by: 'device-s010-z' };
    return [label, format.parseSyncManifest(JSON.stringify(payload))];
  }));
  assert.equal(manifestShapes['v2-default'].format_version, 2);
  assert.equal(manifestShapes['v4-with-cursor'].superseded_by, 'device-s010-z');

  const plaintext = new TextEncoder().encode('{"changes":[],"total_count":0,"device_id":"device-s010-a","format_version":3,"source_seq":1,"source_device_id":"device-s010-a","operation_id":null}');
  const compressed = await codecSet.zstd.zstdEncode(plaintext, 0);
  const encryptedCodec = await codecSet.encrypted();
  const encrypted = await encryptedCodec.encode(compressed);
  const plainCodec = await codecSet.create();
  let plaintextRejection = null;
  try { await plainCodec.decode(encrypted); } catch (error) { plaintextRejection = error.message; }
  assert.match(plaintextRejection, /E_SYNC_E2EE_PASSWORD_REQUIRED/u, 'no-password client must reject an encrypted payload');
  const containers = {
    zstdLevel: 0,
    plaintext: { bytes: plaintext.byteLength, sha256: sha(plaintext) },
    compressed: { bytes: compressed.byteLength, sha256: sha(compressed), magic: hex(compressed.subarray(0, 4)) },
    encrypted: { bytes: encrypted.byteLength, sha256: sha(encrypted), magic: hex(encrypted.subarray(0, 4)), format: 'DSBK header (4-byte magic DSBK, 1-byte version)' },
    roundtripSha256: sha(await codecSet.zstd.zstdDecode(await encryptedCodec.decode(encrypted))),
    plaintextClientRejection: plaintextRejection,
  };
  encryptedCodec.dispose();
  assert.equal(containers.roundtripSha256, containers.plaintext.sha256);
  assert.equal(containers.compressed.magic, '28b52ffd');

  const providers = { desktop: await load('host/plugins/sync-providers/index.ts'), mobile: await load('host/plugins/sync-providers/mobile.ts'), entry: await load('host/plugins/sync-providers/src/entry.ts'), config: format };
  const capabilityOutcome = async (platform, config) => {
    try {
      const storage = await providers.entry.createPlatformStorage(platform, config);
      return { accepted: true, providerName: storage.providerName(), bindingHint: storage.instanceBindingHint().replace(/user=[^|]+/u, 'user=<redacted>') };
    } catch (error) { return { accepted: false, code: error.value?.details?.code ?? error.code ?? null, type: error.value?.type ?? error.name, message: error.message }; }
  };
  const webdav = { provider: 'webdav', webdav: { endpoint: 'https://sync.invalid/dav', username: 'u', password: 'p' } };
  const providerMatrix = [
    { provider: 'webdav', desktop: await capabilityOutcome(providers.desktop.DESKTOP_PLATFORM, webdav), mobile: await capabilityOutcome(providers.mobile.MOBILE_PLATFORM, webdav),
      port: 'HTTP(S) per endpoint; no separate port field', transport: 'host/plugins/sync-providers/src/webdav.ts', capabilities: ['put', 'get', 'listOutcome', 'stat', 'delete', 'putFile', 'getFile'] },
    { provider: 's3', desktop: await capabilityOutcome(providers.desktop.DESKTOP_PLATFORM, { provider: 's3', s3: { endpoint: 'https://s3.invalid', bucket: 'b', accessKeyId: 'a', secretAccessKey: 's' } }),
      mobile: await capabilityOutcome(providers.mobile.MOBILE_PLATFORM, { provider: 's3', s3: { endpoint: 'https://s3.invalid', bucket: 'b', accessKeyId: 'a', secretAccessKey: 's' } }),
      port: 'HTTP(S) per endpoint; endpoint 中的 bucket 前缀会按已知云厂商归一化', transport: 'host/plugins/sync-providers/src/s3.ts', capabilities: ['put', 'get', 'listOutcome', 'stat', 'delete', 'putFile'] },
    { provider: 'ftp', desktop: await capabilityOutcome(providers.desktop.DESKTOP_PLATFORM, { provider: 'ftp', ftp: { host: 'ftp.invalid', username: 'u', password: 'p', useTls: true } }),
      mobile: await capabilityOutcome(providers.mobile.MOBILE_PLATFORM, { provider: 'ftp', ftp: { host: 'ftp.invalid', username: 'u', password: 'p', useTls: true } }),
      port: 'config.port ?? 21 (ftps via useTls=true, same port)', transport: 'host/plugins/sync-providers/src/ftp.ts', capabilities: ['put', 'get', 'listOutcome', 'stat', 'delete'] },
  ];
  assert.equal(providerMatrix[1].mobile.accepted, false);
  assert.equal(providerMatrix[2].mobile.accepted, false);
  assert.equal(providerMatrix[2].desktop.accepted, true);
  const repositoryBinding = await moduleBinding(`
export { readUnknownFields, readUnknownFieldsForRoundTrip } from './host/plugins/sync/src/unknown-fields.ts';
export { getRecordData } from './host/plugins/sync/repository/record-data.ts';
`, ['host/plugins/sync/repository/unknown-fields.ts', 'host/plugins/sync/repository/record-data.ts'],
  ['host/plugins/sync/src/unknown-fields.ts']);
  assert.deepEqual(repositoryBinding.implementation.map(source => source.file), [
    'host/plugins/sync/repository/record-data.ts', 'host/plugins/sync/repository/unknown-fields.ts',
  ]);
  assert.deepEqual(repositoryBinding.wrappers.map(source => source.file), ['host/plugins/sync/src/unknown-fields.ts']);

  // schema 矩阵：共享库版本直接取自 S0.08 基线与 runSync 契约里的四库集合。
  const schema = readJson('config/baseline/schema.json');
  const classification = readJson('config/baseline/classification.json');
  const iterationSources = ['host/plugins/sync/src/collect-sync-changes.ts', 'host/plugins/sync/src/run-sync.ts', 'host/plugins/sync/src/transfer-commands.ts', 'host/plugins/sync/repository/apply-databases.ts', 'host/plugins/sync/repository/registry-preflight.ts'];
  const schemaMatrix = (() => {
    const names = classification.syncDatabases.names;
    const orders = iterationSources.map(file => ({ file, line: lineOf(file, "['vfs', 'llm_usage', 'chat_v2', 'mistakes']"), order: ['vfs', 'llm_usage', 'chat_v2', 'mistakes'] }));
    // 生产遍历顺序与 S0.09 的 syncDatabases.names 顺序不一致：集合相同，顺序不同，逐项记录不修正任一侧。
    assert.deepEqual([...names].sort(), ['chat_v2', 'llm_usage', 'mistakes', 'vfs'], 'classification sync database set');
    log('protocol.ordering', { classification: names, production: orders[0].order, sources: orders.map(item => `${item.file}:${item.line}`) });
    return names.map(name => {
      const database = schema.databases.find(item => item.id === name);
      const rows = classification.registry.filter(row => row.database === name);
      return {
        database: name, schemaVersion: database.version, migrationCount: database.migrationCount, fingerprint: database.fingerprint,
        tables: rows.length, rowSync: rows.filter(row => row.category === 'RowSync').length,
        wireRole: ['manifest databases[]', 'change payload database_name', 'change key not per-database'],
        productionIterationIndex: orders[0].order.indexOf(name),
      };
    });
  })();
  const iterations = { classificationOrder: classification.syncDatabases.names, productionOrder: ['vfs', 'llm_usage', 'chat_v2', 'mistakes'],
    discrepancy: 'same set, different order: S0.09 lists vfs/chat_v2/mistakes/llm_usage, production loops vfs/llm_usage/chat_v2/mistakes',
    sources: iterationSources.map(file => ({ source: 'desktop', file, line: lineOf(file, "['vfs', 'llm_usage', 'chat_v2', 'mistakes']") })) };

  const harness = await runSyncHarness();
  log('protocol.scenario.start', { scenario: 'same-schema-two-devices' });
  const sameSchema = await sameSchemaScenario(harness, codecSet);
  log('protocol.scenario.done', { scenario: sameSchema.scenario, passed: sameSchema.passed });
  log('protocol.scenario.start', { scenario: 'mismatched-schema-local-older' });
  const mismatch = await schemaMismatchScenario(harness, codecSet);
  log('protocol.scenario.done', {
    scenario: mismatch.rejection.scenario, passed: mismatch.rejection.passed, upgradePassed: mismatch.upgrade.passed,
    controlPlaneWrites: mismatch.rejection.controlPlaneWrites, publicationWriteCount: mismatch.rejection.publicationWriteCount,
    cloudKeysChanged: mismatch.rejection.cloudKeysChanged,
  });
  assert.ok(sameSchema.passed, 'same-schema roundtrip must pass through production runSync');
  assert.ok(mismatch.rejection.passed, 'schema mismatch must reject with zero publication writes and untouched pending');
  assert.ok(mismatch.upgrade.passed, 'in-place migration must publish the same pending rows and reach the peer');

  const v5 = readDesktop('host/plugins/sync/src/plugin.ts').includes('runSyncV5') ? 'wired' : 'not-wired';
  assert.equal(git(desktop, 'rev-parse', 'HEAD'), desktopCommit, 'desktop checkout is the frozen upstream commit');
  const protocol = {
    formatVersion: 1, unit, planContract: { file: planFile, line: planLine, section: { line: planSection, title: '8.2 同步' } },
    upstream: {
      repository: '../dstu-node', commit: desktopCommit, head: git(desktop, 'rev-parse', 'HEAD'),
      role: 'frozen desktop baseline; legacy mobile provider observations are historical comparison evidence, not a new capability claim',
    },
    sourceHashes: hashes(protocolSources),
    sourceBinding: {
      repository: repositoryBinding,
      rule: 'sourceHashes bind the runSync chain; repositoryBinding proves the implementation and src wrapper are both consumed by the compiled protocol module',
    },
    callChain: {
      entry: chain.entry,
      stages: [
        { stage: 'create-storage', ...stage('storage = await ctx.syncProvider.createStorage(config);', 'CloudStorage') },
        { stage: 'payload-codec', ...stage('codec = await createPayloadCodec(ctx.secrets.backup, config.encryptionPassword);', 'PayloadCodec') },
        { stage: 'format-negotiation', ...stage("await validateRemoteFormat(storage, parsed !== 'Download', options)", 'SyncFormatDescriptor') },
        { stage: 'target-lease', ...stage('lease = await acquireSyncTargetLease(storage, deviceId)', 'LeaseRecord') },
        { stage: 'upload-encryption-policy', ...stage('await enforceRecordUploadEncryptionPolicyForConfig(ctx.syncProvider, config, deviceId', 'encryption-marker') },
        { stage: 'registry-drift', ...stage('validateSyncRegistryDrift(ctx.storage)', 'S0.09 registry') },
        { stage: 'local-manifest', ...stage('const local = collectLocalManifest(ctx.storage, deviceId, false).manifest', 'SyncManifest') },
        { stage: 'schema-gate', ...stage('await validateUploadSchemaCompatibility(storage, local, options)', 'SchemaMismatchError') },
        { stage: 'collect-changes', ...stage('const enriched = await collectSyncChanges(ctx.storage, null)', 'EngineChange') },
        { stage: 'execute', ...stage('const [result, skipped] = await executePlainSync(storage, enriched, local, parsed, merge', 'SyncExecutionResponse') },
      ],
      separateEntries: [
        { symbol: 'runSyncWithProgress', ...chain.calls.find(call => call.callee === 'runSyncWithProgress') ?? at('host/plugins/sync/src/run-sync.ts', 'export async function runSyncWithProgress', 'runSyncWithProgress'), note: '独立命令 data_governance_run_sync_with_progress；同一前置序列，日志/事件更多' },
        { symbol: 'runSyncV5', ...at('host/plugins/sync/src/run-sync.ts', 'export async function runSyncV5', 'runSyncV5'), note: `v5 入口只从 api.ts 导出，插件 service/command 未注册（${v5}）；v5 不经过 format.json/validateRemoteFormat/schema gate` },
      ],
    },
    wire: {
      types: Object.fromEntries(Object.keys(typeSources).map(name => [name, extractType(name)])),
      changePayloadFieldOrder: ['table_name', 'record_id', 'operation', 'data', 'changed_at', 'change_log_id', 'database_name', 'suppress_change_log', 'source_device_id', 'source_seq'],
      changePayloadEnvelope: ['changes', 'total_count', 'device_id', 'format_version', 'source_seq', 'source_device_id', 'operation_id'],
      changePayloadSource: at('host/plugins/sync/src/upload-changes.ts', 'json = encodeUtf8(`{"changes":', 'uploadEnrichedChanges'),
      serialization: declarationLine('host/plugins/sync/src/change-payload.ts', 'serializeSyncChange'),
    },
    manifest: {
      types: { SyncManifest: extractType('SyncManifest'), DatabaseSyncState: extractType('DatabaseSyncState') },
      keys: { current: manifestKey.replace(/[0-9a-f]{16}/u, '<deviceIdShortHash16>'), legacy: `${format.MANIFESTS_PREFIX}/<deviceId>.json`, readers: { current: at('host/plugins/sync/src/remote-manifest.ts', 'export function deviceManifestKey', 'deviceManifestKey'), legacy: at('host/plugins/sync/src/remote-manifest.ts', 'export function deviceManifestLegacyKey', 'deviceManifestLegacyKey') } },
      writePath: at('host/plugins/sync/src/manifest-upload.ts', 'export async function uploadManifest', 'uploadManifest'),
      readPath: at('host/plugins/sync/src/remote-manifest.ts', 'export async function downloadManifest', 'downloadManifest'),
      parseDefaults: { format_version: 2, published_max_seq: 0, cursors: {}, superseded_by: null },
      shapes: Object.fromEntries(Object.entries(manifestShapes).map(([label, value]) => [label, { format_version: value.format_version, published_max_seq: `${value.published_max_seq}`, cursors: Object.keys(value.cursors), superseded_by: value.superseded_by }])),
    },
    objectKeys: {
      format: { key: format.FORMAT_KEY, source: at('host/plugins/sync/src/remote-format.ts', 'export const FORMAT_KEY', 'FORMAT_KEY') },
      instance: { key: 'data_governance/instance.json', source: at('host/plugins/sync/src/change-availability.ts', 'export const INSTANCE_KEY', 'INSTANCE_KEY') },
      manifests: { prefix: format.MANIFESTS_PREFIX, legacy: format.LEGACY_MANIFEST_KEY },
      changes: { v3: 'data_governance/changes/{deviceIdShortHash}/{seq:012}-{version}-{uuid}.json.zst', v4: 'data_governance/v4/shards/{deviceIdShortHash}/{seq:012}-{version}-{uuid}.json.zst',
        builders: { v3: at('host/plugins/sync/src/change-keys.ts', 'export function buildChangeKeyV3', 'buildChangeKeyV3'), v4: at('host/plugins/sync/src/change-keys.ts', 'export function buildChangeKeyV4', 'buildChangeKeyV4') },
        parser: at('host/plugins/sync/src/change-keys.ts', 'export function parseChangeKey', 'parseChangeKey') },
      lease: { prefix: format.SYNC_TARGET_LEASE_PREFIX, key: `${format.SYNC_TARGET_LEASE_PREFIX}/{operationId}.json`, ttlMs: format.DEFAULT_SYNC_LEASE_TTL_MS, formatVersion: format.SYNC_TARGET_LEASE_FORMAT_VERSION },
      encryptionMarker: { key: '.encryption-marker', version: 2, source: at('host/plugins/sync-providers/src/sync_manager.ts', 'export const ENCRYPTION_MARKER_FILE', 'ENCRYPTION_MARKER_FILE') },
      files: {
        preferences: 'data_governance/preferences/{encodeURIComponent(deviceId)}.json', blobs: 'data_governance/blobs/{relativePath}', blobsManifest: 'data_governance/blobs_manifest.json',
        blobManifests: 'data_governance/file_manifests/blobs', assets: `${format.ASSET_OBJECT_PREFIX}/{key}`, assetManifests: format.ASSET_MANIFEST_PREFIX,
        assetLegacyManifest: 'data_governance/assets_manifest.json', workspaces: 'data_governance/workspaces/{id}.db', workspaceManifests: 'data_governance/file_manifests/workspaces',
        tombstones: { blobs: format.BLOB_TOMBSTONE_PREFIX, assets: format.ASSET_TOMBSTONE_PREFIX, workspaces: format.WS_TOMBSTONE_PREFIX, events: format.TOMBSTONE_EVENTS_PREFIX },
      },
      samples: keySamples,
    },
    compression: { algorithm: 'zstd', level: 0, frameMagic: containers.compressed.magic, appliedTo: 'change payload JSON；format.json/manifest/instance 为明文 JSON（manifest 仅 E2EE 加密）', source: at('host/plugins/sync/src/upload-changes.ts', 'compression.zstdEncode(json, 0)', 'zstdEncode') },
    encryption: {
      kind: 'DSBK session (backup_crypto createFileCipherSession)', plaintextPolicy: 'password empty → plaintext allowed only when no .encryption-marker and no local encrypted-root memory',
      scope: ['device manifest', 'change payload after zstd', 'preferences/blobs/assets/workspaces objects'], notEncrypted: ['format.json', 'instance.json', 'lease objects', 'encryption-marker'],
      codes: { passwordRequired: 'E_SYNC_E2EE_PASSWORD_REQUIRED', wrongPassword: 'E_SYNC_E2EE_WRONG_PASSWORD', plaintextRejected: 'E_SYNC_E2EE_PLAINTEXT_LEGACY_REJECTED' },
      source: at('host/plugins/sync/src/payload.ts', 'export async function createPayloadCodec', 'createPayloadCodec'),
    },
    versionWindow: {
      current: format.CURRENT_FORMAT_VERSION, minCompatible: format.MIN_COMPATIBLE_FORMAT_VERSION, compatibilityReleaseDays: format.COMPATIBILITY_RELEASE_DAYS,
      features: freshDescriptor.features, freshDescriptorMinClient: freshDescriptor.min_reader_version,
      formatBytes: { normalizedBytes: Buffer.byteLength(descriptorText, 'utf8'), normalizedText: descriptorText, sha256: sha256(Buffer.from(descriptorText, 'utf8')),
        note: 'sha256 covers the normalized descriptor text; operation_id/expires_at are regenerated on every write' },
      v3V4: { v3AcceptedAsReader: format.MIN_COMPATIBLE_FORMAT_VERSION === 3, v4ShardsRequireMinReader4: 'uploadEnrichedChanges uses negotiated.min_reader_version >= 4', leaseDowngradeRule: 'min_reader stays 3 until lease expiry AND all active manifests format_version >= 4' },
      v5: { symbol: 'runSyncV5', separatelyExported: readDesktop('host/plugins/sync/api.ts').includes('runSyncV5'), registered: v5 === 'wired', wire: 'sync-v2/ (keyring/packs/segments), not format.json', activation: 'requires cloud_sync.v5_root secret; no plugin command or service method calls it' },
      samples: window,
    },
    providers: providerMatrix,
    platformCapabilities: {
      desktop: { status: 'frozen upstream baseline', source: 'host/plugins/sync-providers/index.ts' },
      legacyMobile: {
        status: 'historical-comparison-only', source: 'host/plugins/sync-providers/mobile.ts',
        note: 'retained as evidence; this unit does not adopt the upstream mobile provider chain',
        observed: providerMatrix.map(item => ({ provider: item.provider, result: item.mobile })),
      },
      nativeAndroid: { status: 'not-built', note: 'Kotlin/Compose provider implementations arrive in later units; none is claimed here' },
    },
    schemaMatrix,
    schemaIterations: iterations,
    scenarios: { sameSchema, mismatch },
    rules: {
      wire: '整数按无损 JSON number/bigint 序列化；manifest/change 外层未知字段按解析器处理，记录 data 的未知列经 unknown-fields vault 保留与回传；null 与省略按各字段的 serde Option 规则比较',
      keys: '当前写者使用 deviceIdShortHash16/至少12位seq/epoch秒version/UUID；读取器兼容明文pathId与legacy key，按生产parseChangeKey的路径和数字分支判定，S0.18保存宽松解析边界',
      pending: 'pending 发布以 __change_log.sync_version=0 为准；schema gate 只阻止业务发布（change/manifest/文件对象），控制面 format/lease 已先写入，pending 与云端游标都不推进',
      versions: '应用版本可独立编号，互通只取决于共享 schema 版本 + wire 窗口（format v3–v4）',
      v5: 'v5 是独立入口与独立对象命名空间；S0.18 golden 与 S0.20 发布窗口不得把 v5 当作已启用',
    },
    checks: {
      chainStages: chain.calls.length,
      types: Object.keys(typeSources).length,
      fields: Object.values(typeSources).reduce((total, [file, symbol]) => total + extractType(symbol).fields.length, 0),
      providers: providerMatrix.length,
      nativeProvidersBuilt: 0,
      schemaDatabases: schemaMatrix.length,
      windowSamples: window.length,
      sameSchemaPassed: sameSchema.passed,
      applicationVersionLabelsOnly: true,
      mismatchRejectedBeforePublication: mismatch.rejection.passed,
      mismatchUpgradePublishedPending: mismatch.upgrade.passed,
    },
  };
  return protocol;
}

export async function run(mode) {
  const started = performance.now();
  const value = await collect();
  artifact('config/baseline/protocol.json', value, mode);
  assert.ok(value.checks.chainStages >= 10);
  assert.ok(value.checks.fields >= 50);
  assert.equal(value.checks.providers, 3);
  log('protocol.complete', { unit, mode, elapsedMs: Math.round(performance.now() - started), ...value.checks });
}

main(import.meta.url, run);
