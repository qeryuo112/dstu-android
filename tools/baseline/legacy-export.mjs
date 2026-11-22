import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { artifact, desktop, difference, git, log, main, root, sha256 } from './lib.mjs';
import { collect } from './legacy-export/harness/run-harness.mjs';
import {
  compareWithSchemaBaseline, historicalSchemaBaseline, inspectPackage, manifestOf, readArchive, schemaBaseline,
} from './legacy-export/package.mjs';
import { verifyLegacyUpgrade } from './legacy-export/upgrade.mjs';
import { verifyDelivery } from './legacy-export/one-shot/verify-delivery.mjs';
import {
  CONTINUITY_FORMAT, CONTINUITY_VERSION, FIXTURE_DEVICES, FIXTURE_INSTANCE, FIXTURE_INSTALLATION,
  applyStandardRestore, assertSidecarShape, buildSidecar, canonicalState, openStore, readState, replaySidecarOnDisk,
  seedStore, takeoverContinuity,
} from './legacy-export/identity.mjs';

const FIXTURE_DIR = 'fixtures/legacy-mobile';
const ARTIFACT = 'config/baseline/legacy-export.json';
const CONTRACT = 'config/legacy-import-contract.json';

const SAMPLE_IDS = { standard: 'standard-v1', continuity: 'continuity-v1' };
const SAMPLE_FILES = {
  standard: `${FIXTURE_DIR}/legacy-mobile-standard-${SAMPLE_IDS.standard}.zip`,
  continuity: `${FIXTURE_DIR}/legacy-mobile-continuity-${SAMPLE_IDS.continuity}.zip`,
};
const SIDECAR_FILE = `${FIXTURE_DIR}/legacy-mobile-continuity-${SAMPLE_IDS.continuity}.continuity.json`;
const EXPECTED_FILE = `${FIXTURE_DIR}/legacy-mobile-expected.json`;

const full = file => resolve(root, file);
const exists = file => existsSync(full(file));

const LEGACY_COMMIT = '145bb3879164c58d1cd5ff39daa94a3daa9fe874';
const HISTORY_BASELINE_COMMIT = '0222ccc';
const TARGET_DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const HISTORY_BASELINE_RESOLVED = git(root, 'rev-parse', HISTORY_BASELINE_COMMIT);

/** 按原字节读取 Git 对象；git helper 的 trim 会改变 hash，不能用。 */
function legacyBytes(file, commit = LEGACY_COMMIT) {
  return execFileSync('git', ['-C', desktop, 'show', `${commit}:${file}`], { maxBuffer: 64 * 1024 * 1024 });
}

function legacyText(file, commit = LEGACY_COMMIT) {
  return legacyBytes(file, commit).toString('utf8');
}

function historicalArtifact(file) {
  return JSON.parse(execFileSync('git', ['-C', root, 'show', `${HISTORY_BASELINE_COMMIT}:${file}`], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }));
}

/** 采集端环境事实：只读 145bb387 的旧出口源码，不跟随当前 mobile 源码。 */
function sourceFacts() {
  const fileEvidence = file => {
    const bytes = legacyBytes(file);
    return { path: file, commit: LEGACY_COMMIT, sha256: sha256(bytes), bytes: bytes.length };
  };
  const backup = legacyText('host/platform/storage-mobile/backup.ts');
  const index = legacyText('host/platform/storage-mobile/index.ts');
  const main = legacyText('mobile/src/main.ts');
  const dashboard = legacyText('src/features/settings/components/DataGovernanceDashboard.tsx');
  const backupEvidence = fileEvidence('host/platform/storage-mobile/backup.ts');
  const indexEvidence = fileEvidence('host/platform/storage-mobile/index.ts');
  const mainEvidence = fileEvidence('mobile/src/main.ts');
  const buildEvidence = fileEvidence('mobile/scripts/build-apk.mjs');
  const dashboardEvidence = fileEvidence('src/features/settings/components/DataGovernanceDashboard.tsx');
  const lineOf = (text, needle) => text.split('\n').findIndex(line => line.includes(needle)) + 1;
  const historical = historicalArtifact('config/baseline/legacy-export.json');
  return {
    provenance: {
      commit: LEGACY_COMMIT,
      kind: 'historical-public-package-generation',
      historicalArtifact: { commit: HISTORY_BASELINE_COMMIT, file: 'config/baseline/legacy-export.json' },
      files: {
        backup: backupEvidence,
        index: indexEvidence,
        shell: mainEvidence,
        build: buildEvidence,
        dashboard: dashboardEvidence,
      },
      note: 'All source facts are read from the requested 145bb387 commit with exact git blobs; no fallback to the current mobile source and no ZIP re-export.',
    },
    exportEntry: {
      service: 'host/platform/storage-mobile/backup.ts',
      symbol: 'MobileBackupService.exportBackup',
      line: lineOf(backup, 'async exportBackup('),
      command: 'data_governance_export_zip',
      commandLine: lineOf(index, "ctx.commands.register('data_governance_export_zip'"),
      backupAndExportLine: lineOf(index, "ctx.commands.register('data_governance_backup_and_export_zip'"),
    },
    saveChannel: {
      hostRequest: 'mobile:save-backup',
      hostLine: lineOf(main, "case 'mobile:save-backup'"),
      implementation: 'mobile/src/main.ts::saveBackupToSystem',
      implementationLine: lineOf(main, 'async function saveBackupToSystem'),
      methods: ['share', 'save-picker', 'download'],
      uiEntry: `src/features/settings/components/DataGovernanceDashboard.tsx:${lineOf(dashboard, 'BackupTab')} (BackupTab)`,
      uiSaveDialog: { file: 'src/features/settings/components/DataGovernanceDashboard.tsx', line: lineOf(dashboard, 'const savePath = await save({') },
    },
    captureTools: Object.fromEntries([
      'tools/baseline/legacy-export.mjs',
      'tools/baseline/legacy-export/fixtures.mjs',
      'tools/baseline/legacy-export/harness/worker.ts',
      'tools/baseline/legacy-export/harness/run-harness.mjs',
      'tools/baseline/legacy-export/harness/sql-glob.mjs',
      'tools/baseline/legacy-export/identity.mjs',
      'tools/baseline/legacy-export/package.mjs',
      'tools/baseline/legacy-export/upgrade.mjs',
      'tools/baseline/legacy-export/one-shot/build.mjs',
      'tools/baseline/legacy-export/one-shot/page.html',
      'tools/baseline/legacy-export/one-shot/worker.ts',
      'tools/baseline/legacy-export/one-shot/delivery.mjs',
      'tools/baseline/legacy-export/one-shot/verify-delivery.mjs',
      'tools/baseline/legacy-export/one-shot/LegacyExportPlugin.java',
    ].map(file => [file, sha256(readFileSync(full(file)))])),
    capabilityFiles: Object.fromEntries(Object.entries({
      backupService: 'host/platform/storage-mobile/backup.ts',
      storageService: 'host/platform/storage-mobile/service.ts',
      workerRuntime: 'mobile/src/worker/sqlite-runtime.ts',
      shell: 'mobile/src/main.ts',
      opfs: 'host/platform/files/opfs.ts',
      mobileLocalRuntime: 'host/platform/files/mobile-local-runtime.ts',
      syncState: 'host/plugins/sync/repository/state.ts',
      activeSyncState: 'host/plugins/sync-support/src/state.ts',
      restoreCommands: 'host/plugins/backup/src/restore-commands.ts',
      manifestModel: 'host/plugins/backup/src/manifest-model.ts',
    }).map(([id, file]) => [id, fileEvidence(file)])),
    installation: (() => {
      const script = legacyText('mobile/scripts/build-apk.mjs');
      const constant = (name, pattern) => {
        const match = new RegExp(`const ${name}\\s*=\\s*([^;]+);`).exec(script);
        const raw = match === null ? null : match[1].trim();
        const string = raw === null ? null : /^'([^']*)'$/.exec(raw);
        if (string !== null) return { value: string[1], expression: raw };
        const number = raw === null ? null : Number(raw);
        return { value: Number.isFinite(number) ? number : raw, expression: raw };
      };
      const lines = script.split('\n');
      const lineOfConstant = name => lines.findIndex(line => line.startsWith(`const ${name} `)) + 1;
      return {
        file: 'mobile/scripts/build-apk.mjs',
        sha256: buildEvidence.sha256,
        applicationId: { ...constant('APP_ID', null), line: lineOfConstant('APP_ID') },
        appName: { ...constant('APP_NAME', null), line: lineOfConstant('APP_NAME') },
        versionName: { ...constant('APP_VERSION_NAME', null), line: lineOfConstant('APP_VERSION_NAME') },
        versionCode: { ...constant('APP_VERSION_CODE', null), line: lineOfConstant('APP_VERSION_CODE') },
      };
    })(),
    capacitor: {
      ...historical.source.capacitor,
      provenance: { commit: HISTORY_BASELINE_COMMIT, artifact: 'config/baseline/legacy-export.json', note: 'frozen historical fact; the current node_modules tree is not read' },
    },
  };
}

/** 冻结回放：只读取已提交的公开 ZIP、sidecar 与 expected，不重跑 harness。 */
async function frozenFixtures() {
  const expected = JSON.parse(readFileSync(full(EXPECTED_FILE), 'utf8'));
  const written = {};
  for (const sample of ['standard', 'continuity']) {
    const bytes = readFileSync(full(SAMPLE_FILES[sample]));
    assert.equal(bytes.length, expected.files[sample].bytes, `${sample}: expected fixture size drift`);
    assert.equal(sha256(bytes), expected.files[sample].sha256, `${sample}: expected fixture SHA-256 drift`);
    written[sample] = { file: SAMPLE_FILES[sample], bytes: bytes.length, sha256: sha256(bytes) };
  }
  const sidecarBytes = readFileSync(full(SIDECAR_FILE));
  assert.equal(sha256(sidecarBytes), expected.sidecar.sha256, 'sidecar: expected fixture SHA-256 drift');
  return {
    expected,
    written,
    sidecar: JSON.parse(sidecarBytes.toString('utf8')),
  };
}

/** 一次性过渡包也要过同一套校验（它是从应用内备份目录读回的字节）。 */
async function inspectPackageFromBytes(bytes) {
  const dir = mkdtempSync(join(tmpdir(), 'dstu-legacy-oneshot-'));
  const file = join(dir, 'one-shot.zip');
  try {
    writeFileSync(file, bytes);
    const report = await inspectPackage(file, { baseline: schemaBaseline(), seeds: undefined });
    assert.deepEqual(report.failures, [], `一次性导出包校验失败\n${report.failures.join('\n')}`);
    return {
      entries: report.entries.length,
      backupId: report.manifest.backup_id,
      snapshotEpoch: report.manifest.snapshot_epoch,
      keyPolicy: report.manifest.key_policy,
      snapshotKind: report.manifest.snapshot_kind,
      slotRestore: report.slotRestore,
      failures: report.failures.length,
    };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** 写样本与 sidecar / expected fixture。 */
async function writeFixtures(captured, identity, installation) {
  mkdirSync(full(FIXTURE_DIR), { recursive: true });
  const written = {};
  for (const sample of ['standard', 'continuity']) {
    const bytes = captured.zips.get(sample);
    writeFileSync(full(SAMPLE_FILES[sample]), bytes);
    written[sample] = { file: SAMPLE_FILES[sample], bytes: bytes.length, sha256: sha256(bytes) };
  }

  const continuityBytes = captured.zips.get('continuity');
  const manifest = await manifestOf(full(SAMPLE_FILES.continuity));
  const sidecar = buildSidecar({
    manifest,
    zipSha256: sha256(continuityBytes),
    zipBytes: continuityBytes.length,
    sourceInstallation: {
      applicationId: installation.applicationId.value,
      appName: installation.appName.value,
      versionName: installation.versionName.value,
      versionCode: installation.versionCode.value,
      // 值来自旧包构建脚本里的常量（S0.15 已核对过实包），不是本轮新构建的 APK。
      identitySource: {
        file: installation.file,
        sha256: installation.sha256,
        lines: {
          applicationId: installation.applicationId.line,
          appName: installation.appName.line,
          versionName: installation.versionName.line,
          versionCode: installation.versionCode.line,
        },
      },
      artifact: 'deep-student-debug.apk（S0.15 observedPackage：debug 签名，不是 release 包）',
      packageNote: '兼容性断言：manifest.app_version 与 versionName 一致；本轮没有新增或重新打包 APK。',
    },
    generator: {
      tool: 'tools/baseline/legacy-export.mjs',
      unit: 'S0.16',
      runtime: `SQLite ${captured.result.environment.sqlite} / Edge headless / OPFS opfs-sahpool (${captured.result.environment.opfsPoolDirectory})`,
      seed: 'public fixtures written through production repositories (notes, vfs-repos, chat, anki, settings, llm_usage)',
    },
    state: identity.continuityState,
    createdAt: manifest.created_at,
  });
  writeFileSync(full(SIDECAR_FILE), `${JSON.stringify(sidecar, null, 2)}\n`);

  const expected = {
    formatVersion: 1,
    unit: 'S0.16',
    note: '公开 fixture 的期望值；verify 用它逐行比对导出包，不依赖采集时的随机 id 之外的事实。',
    source: 'tools/baseline/legacy-export/fixtures.mjs',
    seeds: captured.result.seeds,
    files: written,
    sidecar: { file: SIDECAR_FILE, sha256: sha256(readFileSync(full(SIDECAR_FILE))) },
  };
  writeFileSync(full(EXPECTED_FILE), `${JSON.stringify(expected, null, 2)}\n`);
  return { written, sidecar, expected };
}

/** 包核验：manifest / 条目 / 库 / 指纹 / 行 / 引用。 */
async function verifySample(sample, baseline, seeds) {
  const report = await inspectPackage(full(SAMPLE_FILES[sample]), { baseline, seeds });
  return { report };
}

/** 只把跨版本对比需要的字段写进 artifact；完整对象留在本次校验内。 */
function databaseSummary(described) {
  return Object.fromEntries(Object.entries(described).map(([id, value]) => [id, {
    version: value.version,
    history: value.history.length,
    last: value.history.at(-1) ?? null,
    tables: value.tables.length,
    indexes: value.indexes.length,
    triggers: value.triggers.length,
    integrity: value.integrity,
    foreignKeyViolations: value.foreignKeyViolations,
  }]));
}

/** schema.json 的可对拍摘要（历史包基线与目标桌面基线各存一份）。 */
function schemaSummary(schema) {
  return Object.fromEntries(schema.databases.filter(database => ['vfs', 'llm_usage', 'chat_v2', 'mistakes'].includes(database.id)).map(database => [database.id, {
    version: database.version,
    history: database.history.length,
    fingerprint: database.fingerprint,
  }]));
}

/** 迁移后的目标库必须等于当前 S0.08/冻结桌面 head，而不是旧包自身。 */
function assertTargetBaseline(sample, upgrade, baseline) {
  const failures = compareWithSchemaBaseline(upgrade.target.databases, upgrade.target.fingerprints, baseline);
  assert.deepEqual(failures, [], `${sample}: 目标库未达到冻结桌面 schema\n${failures.join('\n')}`);
  assert.equal(upgrade.targetVersions.vfs, 20261115, `${sample}: 目标 vfs 必须是 20261115`);
  assert.equal(upgrade.migration.success, true, `${sample}: 目标迁移失败`);
  assert.ok(upgrade.restart.databases.every(database => database.success && database.appliedCount === 0),
    `${sample}: 目标迁移重启不是幂等的`);
  assert.equal(upgrade.preserved.businessRows, true, `${sample}: 业务行未保持`);
  assert.ok(Object.values(upgrade.preserved.rows).every(value => value.unchanged),
    `${sample}: 迁移前后行数/内容不一致`);
  assert.ok(upgrade.preserved.assets.length > 0, `${sample}: 资产行未保留`);
  assert.equal(upgrade.fsrs.expectedColumns.length, 7, `${sample}: FSRS 列数不是 7`);
  assert.equal(upgrade.fsrs.allLegacyRowsDefaulted, true, `${sample}: 旧 review_plans 行未得到七列默认`);
  assert.equal(git(desktop, 'rev-parse', 'HEAD'), TARGET_DESKTOP_COMMIT,
    `冻结桌面提交漂移：期望 ${TARGET_DESKTOP_COMMIT}`);
}

/** 身份合同：普通恢复轮换 + continuity 接管（都用真实 SyncStateStore）。 */
function trustedTakeover(sample, source) {
  return {
    zipBytes: sample.bytes,
    manifest: { backup_id: sample.manifest.backup_id, snapshot_epoch: sample.manifest.snapshot_epoch, created_at: sample.manifest.created_at, version: '3.0.0', app_version: sample.manifest.app_version, key_policy: sample.manifest.key_policy, schema_versions: sample.manifest.schema_versions },
    sourceInstallation: { applicationId: source.installation.applicationId.value, versionName: source.installation.versionName.value, versionCode: source.installation.versionCode.value, installationId: FIXTURE_INSTALLATION },
    instanceId: FIXTURE_INSTANCE,
    deviceId: FIXTURE_DEVICES.old,
    sourceWriterStopped: true,
  };
}

async function identityNegatives(sidecar, zipSha256, expected) {
  const results = [];
  const cases = [
    ['snapshot-hash', value => { value.snapshot.zip_sha256 = 'f'.repeat(64); }],
    ['snapshot-epoch', value => { value.snapshot.snapshot_epoch = 'different-snapshot'; }],
    ['source-installation', value => { value.source_installation.installationId = 'different-installation'; }],
    ['source-package', value => { value.source_installation.applicationId = 'different.package'; }],
    ['identity-instance', value => { value.identity.instance_id = 'different-instance'; }],
    ['state-instance', value => { value.state.uploadSeq[0].instance_id = 'different-instance'; }],
    ['writer-id', value => { value.identity.device_id = 'different-writer'; }],
    ['integer-range', value => { value.state.uploadSeq[0].last_seq = '9223372036854775808'; }],
    ['active-source-writer', () => {}, value => { value.sourceWriterStopped = false; }],
    ['existing-endpoint', () => {}, null, 'endpoint'],
    ['mid-apply-rollback', () => {}, null, 'trigger'],
  ];
  for (const [id, mutate, mutateExpected, setup] of cases) {
    const current = structuredClone(sidecar);
    const trusted = structuredClone(expected);
    mutate(current);
    mutateExpected?.(trusted);
    const { db, store } = await openStore();
    try {
      if (setup === 'endpoint') store.bindInstance(FIXTURE_INSTANCE, 'local', 'file:///different/repository');
      if (setup === 'trigger') db.exec("CREATE TRIGGER reject_continuity_cursor BEFORE INSERT ON consume_cursor BEGIN SELECT RAISE(ABORT,'injected continuity write failure'); END");
      const before = canonicalState(readState(db, store));
      const result = takeoverContinuity(db, store, current, zipSha256, trusted);
      const after = canonicalState(readState(db, store));
      assert.equal(result.accepted, false, `${id}: must reject`);
      assert.deepEqual(after, before, `${id}: failed takeover must not change state`);
      results.push({ id, accepted: false, stateUnchanged: true });
    } finally { db.close(); }
  }
  log('legacy-export.identity.negatives', { cases: results.length, passed: results.length });
  return results;
}

async function identityReplay(sidecar, zipSha256, expected) {
  // 普通恢复：轮换设备身份。
  const standard = await openStore();
  let standardResult;
  try {
    seedStore(standard.store);
    const seeded = readState(standard.db, standard.store);
    standardResult = applyStandardRestore(standard.db, standard.store, {
      oldDeviceId: FIXTURE_DEVICES.old, newDeviceId: FIXTURE_DEVICES.next,
    });
    standardResult.seeded = canonicalState(seeded);
  } finally {
    standard.db.close();
  }

  // continuity：接管写者，重复接管必须被拒绝。
  const continuity = await openStore();
  let first;
  let second;
  try {
    first = takeoverContinuity(continuity.db, continuity.store, sidecar, zipSha256, expected);
    const committed = canonicalState(readState(continuity.db, continuity.store));
    second = takeoverContinuity(continuity.db, continuity.store, sidecar, zipSha256, expected);
    assert.deepEqual(canonicalState(readState(continuity.db, continuity.store)), committed, 'duplicate takeover must not change committed state');
  } finally {
    continuity.db.close();
  }

  // sidecar → 磁盘 sync_state.db 的真实回放（重建后读回，证明字段完备）。
  const diskDir = mkdtempSync(join(tmpdir(), 'dstu-legacy-syncstate-'));
  let disk;
  try {
    disk = await replaySidecarOnDisk(sidecar, diskDir);
  } finally {
    rmSync(diskDir, { recursive: true, force: true });
  }
  const negatives = await identityNegatives(sidecar, zipSha256, expected);
  return { standardResult, first, second, disk, negatives };
}

/** Host 侧 `deliver()` 实际返回的 method（全部样本一致时取该值）。 */
function captureSaveMethod(captured) {
  const methods = [...new Set(captured.result.exported.map(entry => entry.deliverMethod))];
  return methods.length === 1 ? methods[0] : methods.join(',');
}

/** 采集时的身份状态（sidecar 的 `state` 就从这里来）。 */
async function captureIdentityState() {
  const { db, store } = await openStore();
  try {
    seedStore(store);
    return canonicalState(readState(db, store));
  } finally {
    db.close();
  }
}

function sidecarStateEqual(left, right) {
  const strip = value => JSON.parse(JSON.stringify(value));
  return difference(strip(left), strip(right)) === null;
}

/** 平台无关导入合同（新定义；S2.12 消费）。 */
function importContract({ samples, environment, source, sidecar }) {
  return {
    formatVersion: 1,
    unit: 'S0.16',
    status: 'contract-defined-in-this-unit',
    purpose: '旧 Android 移动端（Capacitor + OPFS sahpool）数据出口的归档布局、校验顺序、两种身份模式与拒绝条件。Kotlin 导入器在 S2.12 实现。',
    delivery: {
      artifact: ARTIFACT,
      samples: Object.entries(samples).map(([mode, sample]) => ({ mode, ...sample })),
      consumer: 'S2.12 实现旧移动包 Kotlin 导入',
      importerUnit: 'S2.12',
      restoreUnit: 'S2.11',
      identityUnit: 'S2.15',
      deliveryUnit: 'S2.12',
      sampleSchemaNote: 'samples[].sourceDatabases/sourceFingerprints 是旧 ZIP 的 0222ccc 历史事实；samples[].databases/fingerprints 是临时复制迁移到 e6b2657f 后的目标事实。',
    },
    archive: {
      layout: {
        databases: { vfs: 'vfs.db', llm_usage: 'llm_usage.db', chat_v2: 'chat_v2.db', mistakes: 'mistakes.db' },
        audit: 'databases/audit.db',
        blobs: 'assets/vfs_blobs/<relative_path>',
        manifest: 'manifest.json',
      },
      manifestVersion: '3.0.0',
      keyPolicy: 'not_present',
      snapshotKind: 'full',
      notes: [
        'manifest 由生产 MobileBackupService.buildManifest 生成；coverage 由 persistentDomainRegistry + markFull 推导。',
        'vfs_blobs 的条目路径是 assets/vfs_blobs/<relative>，导入端同时接受 vfs_blobs/<relative>（生产 backup.ts blobRelative）。',
        '库条目是 VACUUM INTO 快照，因此 512 字节对齐、文件头为 "SQLite format 3\\0"。',
      ],
    },
    validation: {
      order: [
        'zip-entry-set',
        'database-header-and-size',
        'database-sha256',
        'manifest-parse-and-slot-restore',
        'coverage-and-key-policy',
        'schema-version-and-refinery-history',
        'schema-fingerprint',
        'business-rows',
        'resource-references',
        'identity-mode',
      ],
      rejectWhen: [
        '缺少 vfs/llm_usage/chat_v2/mistakes 任一核心库',
        'manifest.files 与 zip 条目不是一一对应',
        '库文件头不是 SQLite format 3、大小 < 512 或未按 512 对齐',
        '条目 sha256 与 manifest 声明不一致',
        'manifest.version 主版本 < 3 或缺 coverage ledger（旧包只能按 legacy candidate 检查）',
        'manifest.snapshot_kind != full',
        'key_policy=not_present 但 crypto 域不是 absent/empty',
        'manifest.schema_versions 高于当前应用支持的版本',
        'refinery_schema_history 与 S0.08 基线逐行不一致（version/name/checksum）',
        'resources.hash 不是 sha256(noteId:content)，或 files.sha256/blob_hash 指向不存在的 blob',
        'blobs.relative_path 在归档里缺失或文件 sha256 与 blobs.hash 不一致',
        'continuity sidecar 的 hash/大小/epoch/backup_id/schema 与已核验 ZIP manifest 不一致',
        '来源 installationId/包身份、仓库 instance、写者或 provider/endpoint 与可信输入不一致',
        '序列超出 sync_state 精确 i64 范围、目标同步状态与停用源快照不同、旧写者尚未停止',
        '同一 snapshot_epoch + zip_sha256 已被接管（legacy_processed_key 命中）',
      ],
      schemaBaseline: {
        packageValidation: {
          commit: HISTORY_BASELINE_COMMIT,
          resolvedCommit: HISTORY_BASELINE_RESOLVED,
          artifact: 'config/baseline/schema.json',
          versions: schemaSummary(historicalSchemaBaseline(HISTORY_BASELINE_COMMIT)),
          note: '旧 ZIP 的 manifest.schema_versions 与 refinery 行按 0222ccc 历史基线校验；旧包保持 vfs 20261111。',
        },
        target: {
          desktopCommit: TARGET_DESKTOP_COMMIT,
          artifact: 'config/baseline/schema.json',
          versions: schemaSummary(schemaBaseline()),
          note: '临时复制后由生产 MigrationCoordinator 迁移到冻结桌面 head，vfs 目标 20261115。',
        },
      },
      knownSchemaDeltas: {
        missingEverywhere: ['__governance_schema_fingerprints'],
        extraEverywhere: ['__governance_schema_fingerprints'],
        reason: '移动端迁移不做指纹回放（host/platform/storage-mobile/migrations.ts 头注释），桌面启动后才建这张表；历史包校验按 0222ccc，目标迁移按 e6b2657f。',
        extra: { mistakes: ['pending_memory_candidates'] },
        extraIndexPrefixes: { mistakes: ['idx_pending_mem_'] },
      },
    },
    identity: {
      modes: {
        standard: {
          description: '普通恢复：导入后轮换本机设备身份并重建同步基线（S2.15）。',
          desktopReference: 'host/plugins/backup/src/restore-commands.ts::finalizeRestoreActivation → syncSupport.state.recordDeviceRotation(old,new,"backup_restore_activation")',
          rotation: {
            method: 'SyncStateStore.recordDeviceRotation',
            source: 'host/plugins/sync-support/src/state.ts',
            line: 238,
            clears: ['consume_cursor.last_seq=0', 'legacy_processed_key', 'tombstone_watermark'],
            keeps: ['upload_seq', 'tombstone_event_publish', 'instance_binding'],
            writes: 'device_history(old_device_id,new_device_id,rotated_at,reason)',
          },
        },
        continuity: {
          description: '同设备 continuity：沿用旧设备的仓库 instance、upload_seq、tombstone 水位与游标，只接管写者；不轮换设备身份。',
          sidecar: {
            file: SIDECAR_FILE,
            format: CONTINUITY_FORMAT,
            version: CONTINUITY_VERSION,
            status: '本轮新定义的平台无关合同；旧版移动端没有这个产物',
            boundTo: ['snapshot.zip_sha256', 'snapshot.zip_bytes', 'snapshot.snapshot_epoch', 'snapshot.backup_id', 'snapshot.schema_versions', 'source_installation.installationId', 'source_installation.applicationId/versionName/versionCode', 'identity.instance_id', 'identity.device_id'],
            trustedInputs: '由已核验 ZIP manifest、旧安装身份确认及仓库配对取得，不能使用 sidecar 自身反填可信期望值。',
            atomicity: '停用旧写者后，以一个 BEGIN IMMEDIATE 事务恢复绑定/序列/游标/墓碑并记录接管回执；任一步失败回滚。',
            duplicateRule: 'legacy_processed_key 以 continuity-takeover/<snapshot_epoch>/<zip_sha256> 为键；第二次接管返回拒绝，不写任何状态。',
          },
          writerTakeover: {
            instanceBinding: '必须与 sidecar.identity.instance_id 一致；provider/endpoint_hint 不一致时拒绝，不静默改绑。',
            uploadSeq: '按 sidecar.state.uploadSeq 恢复；之后 nextUploadSeq 从 max(local, cloud)+1 继续。',
            cursors: '按 sidecar.state.consumeCursor 恢复。',
            tombstones: '按 sidecar.state.tombstoneWatermark 恢复（max 语义）。',
          },
        },
      },
      fixtureDevices: FIXTURE_DEVICES,
      fixtureInstance: FIXTURE_INSTANCE,
    },
    kotlinImporter: {
      unit: 'S2.12',
      steps: [
        '读取 ZIP 中央目录，拒绝条目数超限/路径越界（`..`、绝对路径、盘符）。',
        '按 validation.order 逐项校验；任一失败在切槽前结束并保留旧槽。',
        '把四库与 audit 写入暂存目录，vfs_blobs 解到 staging/vfs_blobs。',
        'standard 模式：提交后轮换 device id（等价 recordDeviceRotation），重建 consume_cursor/tombstone 基线。',
        'continuity 模式：校验 sidecar 绑定后恢复 instance/upload/cursor/tombstone；写 takeover 账本拒绝重复接管。',
        '原数据保留到导入回读、资源核验与用户确认完成（计划 §8.4）。',
      ],
      mustNot: ['不得把 manifest.key_policy=not_present 当作“有密钥可用”', '不得在 sidecar 校验失败时部分写入同步状态'],
    },
    keyPolicy: {
      manifest: 'not_present',
      material: false,
      explanation: '移动端备份不含密钥材料（backup.ts 注释与 key_policy 一致）。本轮两个样本没有设置任何备份密码，也没有导出任何密钥材料；fixture 中的设备号只是标识；contract 不定义任何加密格式。',
    },
    environment,
    sources: source,
    generatedFrom: {
      tool: 'tools/baseline/legacy-export.mjs',
      fixture: 'tools/baseline/legacy-export/fixtures.mjs',
      harness: 'tools/baseline/legacy-export/harness/worker.ts',
    },
    sidecarDigest: sidecar,
  };
}

/** capture 只回放冻结的公开包，不重跑 harness、不重写 ZIP/fixture。 */
async function capture() {
  log('legacy-export.capture.start', { unit: 'S0.16' });
  const source = sourceFacts();
  const previousArtifact = JSON.parse(readFileSync(full(ARTIFACT), 'utf8'));
  const frozen = await frozenFixtures();
  log('legacy-export.capture.frozen', {
    samples: Object.entries(frozen.written).map(([sample, entry]) => ({ sample, bytes: entry.bytes, sha256: entry.sha256 })),
    zipRewritten: false,
  });
  const written = {
    written: frozen.written,
    sidecar: frozen.sidecar,
    expected: frozen.expected,
  };
  const baseline = schemaBaseline();
  const historicalBaseline = historicalSchemaBaseline(HISTORY_BASELINE_COMMIT);
  const seeds = frozen.expected.seeds;

  const perSample = {};
  for (const sample of ['standard', 'continuity']) {
    const { report } = await verifySample(sample, historicalBaseline, seeds);
    assert.deepEqual(report.failures, [], `${sample}: 包校验失败\n${report.failures.join('\n')}`);
    const upgrade = await verifyLegacyUpgrade(await readArchive(full(SAMPLE_FILES[sample])));
    assertTargetBaseline(sample, upgrade, baseline);
    const { target, ...targetMigration } = upgrade;
    perSample[sample] = {
      file: written.written[sample].file,
      bytes: written.written[sample].bytes,
      sha256: written.written[sample].sha256,
      manifest: {
        backup_id: report.manifest.backup_id,
        snapshot_epoch: report.manifest.snapshot_epoch,
        created_at: report.manifest.created_at,
        app_version: report.manifest.app_version,
        schema_versions: report.manifest.schema_versions,
        snapshot_kind: report.manifest.snapshot_kind,
        key_policy: report.manifest.key_policy,
      },
      slotRestore: report.slotRestore,
      coverage: report.coverage,
      entries: report.entries,
      sourceDatabases: databaseSummary(report.databases),
      sourceFingerprints: report.fingerprints,
      databases: databaseSummary(target.databases),
      fingerprints: target.fingerprints,
      targetMigration,
      rows: report.rows,
      checks: { failures: report.failures.length },
    };
    log('legacy-export.sample.verified', {
      sample, bytes: written.written[sample].bytes, sha256: written.written[sample].sha256,
      entries: report.entries.length, failures: report.failures.length,
      sourceVersions: upgrade.sourceVersions, targetVersions: upgrade.targetVersions,
    });
  }

  // 两个样本的差异必须只在 manifest（snapshot_epoch / created_at / backup_id）。
  const standardEntries = new Map(perSample.standard.entries.map(entry => [entry.name, entry.sha256]));
  const continuityEntries = new Map(perSample.continuity.entries.map(entry => [entry.name, entry.sha256]));
  const differing = [...standardEntries.keys()].filter(name => standardEntries.get(name) !== continuityEntries.get(name));
  assert.deepEqual(differing, ['manifest.json'], `两个样本应只在 manifest 上不同，实际：${differing.join(',')}`);
  assert.notEqual(perSample.standard.manifest.snapshot_epoch, perSample.continuity.manifest.snapshot_epoch);

  assert.equal(perSample.continuity.manifest.app_version, source.installation.versionName.value,
    'manifest.app_version 必须等于旧包 versionName');
  const takeoverExpected = trustedTakeover(perSample.continuity, source);
  const identity = await identityReplay(written.sidecar, written.written.continuity.sha256, takeoverExpected);
  assert.equal(identity.standardResult.differences.deviceHistory.after.length, 1, '普通恢复必须写入 device_history');
  assert.equal(identity.standardResult.differences.consumeCursor.after[0].last_seq, 0, '普通恢复必须把 consume_cursor 清 0');
  assert.equal(identity.standardResult.differences.tombstoneWatermark.after.length, 0, '普通恢复必须删除 tombstone_watermark');
  assert.equal(identity.standardResult.differences.legacyProcessedKey.after.length, 0, '普通恢复必须删除 legacy_processed_key');
  assert.deepEqual(identity.standardResult.differences.uploadSeq.before, identity.standardResult.differences.uploadSeq.after, '普通恢复必须保留 upload_seq');
  assert.deepEqual(identity.standardResult.differences.tombstoneEventPublish.before, identity.standardResult.differences.tombstoneEventPublish.after, '普通恢复不得动 tombstone_event_publish');
  assert.equal(identity.first.accepted, true, `continuity 接管应成功：${identity.first.reason}`);
  assert.equal(identity.second.accepted, false, 'continuity 重复接管必须被拒绝');
  assert.ok(sidecarStateEqual(identity.first.receipt.state, written.sidecar.expected.state), 'continuity 接管后状态应等于 sidecar.expected.state');

  // sidecar 绑定校验：故意改一位 hash 必须被拒。
  const tampered = await openStore();
  let tamperedResult;
  try {
    seedStore(tampered.store);
    tamperedResult = takeoverContinuity(tampered.db, tampered.store, written.sidecar, 'f'.repeat(64), takeoverExpected);
  } finally {
    tampered.db.close();
  }
  assert.equal(tamperedResult.accepted, false, '快照 hash 不匹配时必须拒绝');
  assert.equal(identity.disk.matches, true, 'sidecar 状态在真实磁盘 sync_state.db 上重建后应与原状态一致');

  const samples = {};
  for (const sample of ['standard', 'continuity']) {
    samples[sample] = {
      mode: sample,
      file: perSample[sample].file,
      bytes: perSample[sample].bytes,
      sha256: perSample[sample].sha256,
      manifest: perSample[sample].manifest,
      slotRestore: perSample[sample].slotRestore,
      coverage: perSample[sample].coverage,
      entries: perSample[sample].entries,
      sourceDatabases: perSample[sample].sourceDatabases,
      sourceFingerprints: perSample[sample].sourceFingerprints,
      databases: perSample[sample].databases,
      fingerprints: perSample[sample].fingerprints,
      targetMigration: perSample[sample].targetMigration,
      rows: perSample[sample].rows,
      sidecar: sample === 'continuity' ? { file: SIDECAR_FILE, sha256: written.expected.sidecar.sha256, format: CONTINUITY_FORMAT, version: CONTINUITY_VERSION, status: 'new-contract-in-this-unit' } : null,
      checks: perSample[sample].checks,
    };
  }

  const value = {
    formatVersion: 1,
    unit: 'S0.16',
    delivery: {
      artifact: ARTIFACT,
      contract: CONTRACT,
      fixtures: [SAMPLE_FILES.standard, SAMPLE_FILES.continuity, SIDECAR_FILE, EXPECTED_FILE],
      consumer: 'S2.12 旧移动包 Kotlin 导入',
      deliveryUnit: 'S2.12',
      prerequisiteUnits: ['S0.08', 'S0.10'],
      previous: 'S0.08（schema/refinery 基线）、S0.10（同步协议/版本窗口）',
      sampleSchemaNote: 'samples[].sourceDatabases/sourceFingerprints 是旧 ZIP 的 0222ccc 历史事实；samples[].databases/fingerprints 是临时复制迁移到 e6b2657f 后的目标事实。',
    },
    environment: {
      ...previousArtifact.environment,
      captureMode: 'frozen-fixture-replay',
      zipRewritten: false,
    },
    schemaValidation: {
      historicalPackage: {
        commit: HISTORY_BASELINE_COMMIT,
        resolvedCommit: HISTORY_BASELINE_RESOLVED,
        artifact: 'config/baseline/schema.json',
        databases: schemaSummary(historicalBaseline),
        note: '旧 ZIP 由 145bb387 的移动端生成；其 refinery 行、指纹与 0222ccc 的 schema 基线逐行对拍，不要求等于当前桌面 head。',
      },
      target: {
        desktopCommit: TARGET_DESKTOP_COMMIT,
        artifact: 'config/baseline/schema.json',
        databases: schemaSummary(baseline),
        mode: '临时槽复制旧库 → 生产 MigrationCoordinator → 行/资产/审计保持 → review_plans 七列默认',
        note: '旧包本身不重导出；迁移只发生在临时复制上，目标 vfs 为 20261115。',
      },
    },
    source,
    fixtures: {
      file: EXPECTED_FILE,
      sha256: sha256(readFileSync(full(EXPECTED_FILE))),
      seeds,
      notes: '所有文本均为公开示例；设备号是 fixture 标识；无密钥材料。',
    },
    samples,
    identity: {
      standard: {
        rotationMethod: 'SyncStateStore.recordDeviceRotation',
        source: 'host/plugins/sync-support/src/state.ts:238',
        devices: FIXTURE_DEVICES,
        before: identity.standardResult.differences,
        after: identity.standardResult.canonicalAfter,
        assertions: {
          deviceHistoryWritten: true,
          consumeCursorReset: true,
          legacyProcessedKeyCleared: true,
          tombstoneWatermarkCleared: true,
          uploadSeqPreserved: true,
          tombstoneEventPublishPreserved: true,
        },
      },
      continuity: {
        format: CONTINUITY_FORMAT,
        version: CONTINUITY_VERSION,
        status: 'new-contract-in-this-unit',
        note: '旧版移动端没有 continuity sidecar；这是本轮为 S2.15 定义并验证的平台无关合同。',
        sidecar: { file: SIDECAR_FILE, sha256: written.expected.sidecar.sha256 },
        first: { accepted: identity.first.accepted, reason: identity.first.reason, receipt: identity.first.receipt },
        duplicate: { accepted: identity.second.accepted, reason: identity.second.reason },
        tamperedSnapshot: { accepted: tamperedResult.accepted, reason: tamperedResult.reason },
        expectedState: written.sidecar.expected.state,
        atomicity: 'BEGIN IMMEDIATE covers binding, state restoration and takeover receipt; any error rolls back the full state',
        negatives: identity.negatives,
        diskReplay: {
          database: 'sync_state.db（better-sqlite3 真实文件，生产 SyncStateStore 建表/写入/重开读回）',
          matches: identity.disk.matches,
          state: identity.disk.state,
        },
      },
    },
    channelFacts: {
      transferChecks: await verifyDelivery(),
      command: 'data_governance_export_zip / data_governance_backup_and_export_zip',
      hostSaveRequest: 'mobile:save-backup',
      implementation: 'mobile/src/main.ts::saveBackupToSystem',
      attempts: ['navigator.share (files)', 'showSaveFilePicker', '<a download>'],
      observedReturn: null,
      observedReturnNote: '未执行系统保存 API；harness 的 shell adapter 只记录能力推导值。桌面文件选择器、Android 系统保存与用户手势分别在实际交付设备验收。',
      predictedMethod: previousArtifact.channelFacts.predictedMethod,
      predictionOnly: true,
      saveCapabilities: previousArtifact.channelFacts.saveCapabilities,
      deliverCalls: previousArtifact.channelFacts.deliverCalls,
      androidReality: {
        capacitorVersion: source.capacitor.version,
        setsDownloadListener: source.capacitor.setsDownloadListener,
        implementsNavigatorShare: source.capacitor.implementsNavigatorShare,
        showsFileChooser: source.capacitor.showsFileChooser,
        overridesUrlLoading: source.capacitor.overridesUrlLoading,
        // WebView 里 `navigator.share` 与 `showSaveFilePicker` 都不由 Capacitor 提供；
        // `canShare` 在 Chromium WebView 上存在但通常返回 false（无系统分享实现），所以只剩 <a download>。
        branchOnAndroid: source.capacitor.downloadReachesSystem
          ? 'share → save-picker → download（DownloadListener 会接住）'
          : 'share（无）→ save-picker（无）→ download（WebView 无 DownloadListener，不落盘）',
        downloadReachesSystem: source.capacitor.downloadReachesSystem,
        conclusion: source.capacitor.setsDownloadListener
          ? 'Capacitor 注册了 DownloadListener，<a download> 可交给系统下载。'
          : 'Capacitor 8.6.0 的 Bridge 没有 setDownloadListener，也没有实现 navigator.share；`showSaveFilePicker` 在 Android WebView 中不存在。'
            + '因此 saveBackupToSystem 在 Android 上只剩 `<a download>`（href 是 blob: URL），WebView 没有下载监听时不会落盘到系统下载目录。'
            + '这是按 Capacitor 源码与 WebView 行为推断的结论，未在真机上执行；请在旧设备上用一次性导出源码核对。',
        inference: true,
        inferenceNote: '未在 Android 真机/模拟器执行；结论依据 Capacitor 8.6.0 Java 源码与 WebView 无 DownloadListener 时的既有行为。',
        evidence: [
          'mobile/node_modules/@capacitor/android/capacitor/src/main/java/com/getcapacitor/Bridge.java',
          'mobile/node_modules/@capacitor/android/capacitor/src/main/java/com/getcapacitor/BridgeWebChromeClient.java',
          'mobile/node_modules/@capacitor/android/capacitor/src/main/java/com/getcapacitor/BridgeWebViewClient.java',
        ],
      },
      uiEntry: source.saveChannel.uiEntry,
      uiSaveDialog: source.saveChannel.uiSaveDialog,
      missingOnMobile: [
        '移动端没有 plugin:dialog（host/platform/storage-mobile/index.ts:389 注释），UI 的 save() 对话框在 Android 上不可用；命令本身可用 outputPath=null 走应用内备份目录。',
      ],
      deliveryWorkaround: {
        kind: 'one-shot-source-in-this-repo',
        note: '本项目提供一次性导出源码，Worker 只导出、不种数据、不迁移；页面在同 origin 读取 OPFS ZIP 并通过LegacyExport 插件写入用户选择的系统文档 URI。原生桥真实落盘在旧设备验收，OPFS 导出与页面 Blob 交接在本单元验证。',
        files: [
          'tools/baseline/legacy-export/one-shot/build.mjs（生成静态资源）',
          'tools/baseline/legacy-export/one-shot/worker.ts（生产导出，不 runAll）',
          'tools/baseline/legacy-export/one-shot/page.html（进度页）',
          'tools/baseline/legacy-export/one-shot/delivery.mjs（OPFS Blob 与有界传输）',
          'tools/baseline/legacy-export/one-shot/LegacyExportPlugin.java（系统文档选择器与原生写入）',
        ],
        buildCommand: 'node tools/baseline/legacy-export/one-shot/build.mjs',
        output: 'tools/baseline/legacy-export/one-shot/build/（页面、worker、delivery、原生插件源码及SQLite资源；build/已忽略，验收后清理）',
        probe: {
          zip: previousArtifact.channelFacts.deliveryWorkaround.probe.zip,
          exported: previousArtifact.channelFacts.deliveryWorkaround.probe.exported.map(entry => ({
            sample: entry.sample, bytes: entry.bytes, sha256: entry.sha256, delivered: entry.delivered,
          })),
          environment: {
            ...previousArtifact.channelFacts.deliveryWorkaround.probe.environment,
            origin: previousArtifact.channelFacts.deliveryWorkaround.probe.environment.origin.replace(/:\d+$/, ':<ephemeral>'),
          },
          note: '沿用前一次真实 OPFS 采集记录：一次性 Worker 只调用 exportBackup，导出成功且没有调用 migrations.runAll()。',
          reusedFrom: 'previous capture; 本轮 frozen-fixture-replay 不重跑浏览器 harness，也不重导出旧 ZIP',
          verifiedBy: 'verify:legacy-export 只读样本 ZIP，不重跑浏览器；这条路径的记录保留在 capture 的 artifact 里。',
        },
        modes: ['http（仅新建测试环境向采集端回传）', 'opfs（应用内部归档逻辑路径）', 'LegacyExport 原生插件（用户选择的系统文档 URI，旧设备待验收）'],
        nativeDelivery: { plugin: 'LegacyExportPlugin.java（基于已有 Capacitor 8.6.0 Plugin API）', source: 'tools/baseline/legacy-export/one-shot/LegacyExportPlugin.java', target: '用户通过 ACTION_CREATE_DOCUMENT 选择的系统文档 URI', origin: '保持旧 Capacitor 应用的 origin 与 applicationId；旧 Worker 停止后独占 sahpool', permission: '系统文件选择器的单文件 URI 授权，不请求全盘存储权限', completion: '原生输出流校验大小/SHA-256 并关闭后返回 URI；取消或失败清理系统端半成品', executedOnAndroid: false },
      },
    },
    userSteps: {
      scope: '旧设备（dstu-node mobile 0.20.0 构建）上的操作步骤；本轮没有新增 APK，也没有在真机上执行。',
      steps: [
        '在旧设备打开 Deep Student → 设置 → 数据治理 → 备份（BackupTab）。',
        '选择“导出备份”：勾选需要的层级（默认核心层包含四库；vfs_blobs 需要 important 层 + 包含资产文件）。',
        '点击导出按钮（BackupTab.tsx:822 → DataGovernanceDashboard.backupAndExportZip）。',
        '桌面端会先弹出保存对话框（@tauri-apps/plugin-dialog save()）；移动端没有该插件，这一步在 Android 上失败或不可见。',
        'Host 侧真正执行 data_governance_backup_and_export_zip → MobileBackupService.exportBackup，产物在应用内 /deep-student/recovery/backups/<backup-id>.zip。',
        '保存通道调用 mobile:save-backup → saveBackupToSystem；Android 分支按源码推断为 <a download>，系统实际落盘仍须原生回执。',
        '执行 node tools/baseline/legacy-export/one-shot/build.mjs 生成一次性页面与 Worker；在保留 applicationId、WebView origin、原签名且 versionCode 递增的过渡包中接入LegacyExport 原生插件，不卸载旧应用。',
        '停止原 Host Worker 后打开一次性页面，点击“导出到系统文档”；Worker 经 VACUUM INTO/exportFile 生成 ZIP，页面从 OPFS 逻辑路径读取 Blob，经 LegacyExport 原生插件写入用户选择的系统文档 URI。',
        '确认页面给出的原生 URI、文件大小和 SHA-256；用系统文件应用复制该 ZIP。OPFS 路径不是 Android 物理文件路径，不按逻辑名使用 adb pull。',
        '将 ZIP 交给 S2.12 Kotlin 导入器；按 hash/coverage/schema/引用回读确认后再决定旧数据源退役。',
      ],
      expectedHostResult: '现有 harness 仅推导保存分支（predictedMethod），不产生系统保存回执；一次性页面以 LegacyExport 插件的原生 URI + 输出字节数/SHA-256 作为交付完成凭据。',
      androidDeliveryStatus: '一次性原生桥源码已提供；真实 Android 过渡包签名/安装/系统保存未执行。',
    },
    verification: {
      packageChecks: [
        'zip 条目集合与 manifest.files 一一对应',
        '四库与 audit 的 SQLite 文件头、512 对齐、大小、sha256',
        'manifest 解析 + validateForSlotRestore',
        'coverage ledger 与 key_policy',
        'refinery_schema_history 与 0222ccc 历史基线逐行对拍（旧包版本 20261111）',
        '桌面 computeSchemaFingerprint 对导出库文件按 manifest 声明版本重算并与 0222ccc 指纹对拍',
        '临时复制数据库由生产 MigrationCoordinator 迁移到 e6b2657f（vfs 20261115），逐表行/资产/审计保持',
        'review_plans 七个 FSRS 列存在且默认值为 NULL/0，legacy SM-2 列不变',
        '业务行（笔记/资源/blob/文件/会话/消息/块/错题/卡片/设置/用量/审计）',
        '资源引用：blobs.relative_path ↔ assets/vfs_blobs/<relative> 的 sha256 与大小，files.sha256/blob_hash ↔ blobs.hash，resources.hash = sha256(noteId:content)',
      ],
      identityChecks: [
        '普通恢复：真实 recordDeviceRotation 写入 device_history、清 consume_cursor/legacy_processed_key/tombstone_watermark、保留 upload_seq 与 tombstone_event_publish',
        'continuity：真实 SyncStateStore 接管后状态等于 sidecar.expected.state',
        'continuity 重复接管被 legacy_processed_key 拒绝',
        'continuity 快照 hash 不匹配被拒绝',
      ],
      notVerified: [
        '未在 Android 真机/模拟器上执行旧版 UI 导出流程（本单元不要求新增 APK，且不构建 Gradle）。',
        '未验证旧版加密（DSBK）备份：移动端导出固定 key_policy=not_present。',
        '未验证桌面 → 移动端反向导入；本单元只覆盖旧移动端导出包。',
      ],
    },
    checks: {
      samples: Object.keys(samples).length,
      sampleBytes: Object.values(samples).reduce((total, sample) => total + sample.bytes, 0),
      failures: Object.values(samples).reduce((total, sample) => total + sample.checks.failures, 0),
      schemaBaseline: 'config/baseline/schema.json',
      historicalSchemaBaselineCommit: HISTORY_BASELINE_COMMIT,
      targetDesktopCommit: TARGET_DESKTOP_COMMIT,
      fingerprintsMatchS008: true,
      sourceFingerprintsMatchHistorical: true,
      targetMigrationRowAssetIdentityPreserved: true,
      fsrsSevenColumnsDefaulted: true,
      slotRestorePassed: Object.values(samples).every(sample => sample.slotRestore.ok),
      entriesPerSample: Object.fromEntries(Object.entries(samples).map(([mode, sample]) => [mode, sample.entries.length])),
      identityModes: ['standard', 'continuity'],
      oneShotProbe: previousArtifact.checks.oneShotProbe === true
        && previousArtifact.channelFacts.deliveryWorkaround.probe.zip.archive.failures === 0,
      liveSlotMatchesArchive: true,
      sidecarBoundToSnapshot: true,
      duplicateTakeoverRejected: identity.second.accepted === false,
    },
  };

  artifact(ARTIFACT, value, 'capture');
  const contract = importContract({ samples, environment: value.environment, source, sidecar: { file: SIDECAR_FILE, sha256: written.expected.sidecar.sha256 } });
  writeFileSync(full(CONTRACT), `${JSON.stringify(contract, null, 2)}\n`);
  log('legacy-export.capture.complete', {
    artifact: ARTIFACT, contract: CONTRACT,
    samples: Object.entries(samples).map(([mode, sample]) => ({ mode, bytes: sample.bytes, sha256: sample.sha256 })),
  });
  return value;
}

/** 校验模式：只读已落盘文件。 */
async function verify() {
  log('legacy-export.verify.start', { unit: 'S0.16' });
  assert.ok(exists(ARTIFACT), `缺少 ${ARTIFACT}；先运行 npm run baseline:legacy-export`);
  const recorded = JSON.parse(readFileSync(full(ARTIFACT), 'utf8'));
  assert.equal(recorded.unit, 'S0.16');
  const expected = JSON.parse(readFileSync(full(EXPECTED_FILE), 'utf8'));
  assert.equal(sha256(readFileSync(full(EXPECTED_FILE))), recorded.fixtures.sha256, 'expected fixture 与 artifact 不一致');
  assert.deepEqual(expected.seeds, recorded.fixtures.seeds, 'expected fixture 的种子与 artifact 不一致');
  const baseline = schemaBaseline();
  const historicalBaseline = historicalSchemaBaseline(HISTORY_BASELINE_COMMIT);
  assert.deepEqual(recorded.schemaValidation.historicalPackage.databases, schemaSummary(historicalBaseline),
    'artifact 的历史 schema 基线摘要漂移');
  assert.deepEqual(recorded.schemaValidation.target.databases, schemaSummary(baseline),
    'artifact 的目标 schema 基线摘要漂移');
  assert.equal(recorded.checks.sourceFingerprintsMatchHistorical, true);
  assert.equal(recorded.checks.targetMigrationRowAssetIdentityPreserved, true);
  assert.equal(recorded.checks.fsrsSevenColumnsDefaulted, true);

  for (const sample of ['standard', 'continuity']) {
    const file = SAMPLE_FILES[sample];
    assert.ok(exists(file), `缺少样本 ${file}`);
    const bytes = readFileSync(full(file));
    const recordedSample = recorded.samples[sample];
    assert.ok(recordedSample !== undefined, `${sample}: artifact 缺少样本记录`);
    assert.equal(bytes.length, recordedSample.bytes, `${sample}: 文件大小与 artifact 不一致`);
    assert.equal(sha256(bytes), recordedSample.sha256, `${sample}: SHA-256 与 artifact 不一致`);
    assert.equal(expected.files[sample].sha256, recordedSample.sha256, `${sample}: expected fixture 与 artifact 不一致`);

    const report = await inspectPackage(full(file), { baseline: historicalBaseline, seeds: expected.seeds });
    assert.deepEqual(report.failures, [], `${sample}: 包校验失败\n${report.failures.join('\n')}`);
    assert.equal(report.manifest.backup_id, recordedSample.manifest.backup_id, `${sample}: backup_id 漂移`);
    assert.equal(report.manifest.snapshot_epoch, recordedSample.manifest.snapshot_epoch, `${sample}: snapshot_epoch 漂移`);
    assert.deepEqual(report.manifest.schema_versions, recordedSample.manifest.schema_versions, `${sample}: schema_versions 漂移`);
    assert.equal(report.manifest.key_policy, 'not_present', `${sample}: key_policy 必须是 not_present`);
    assert.equal(report.manifest.snapshot_kind, 'full', `${sample}: snapshot_kind 必须是 full`);
    assert.equal(report.slotRestore.ok, true, `${sample}: validateForSlotRestore 失败`);
    const sourceDatabases = databaseSummary(report.databases);
    for (const id of ['vfs', 'llm_usage', 'chat_v2', 'mistakes']) {
      const actual = report.databases[id];
      const frozen = recordedSample.sourceDatabases[id];
      assert.equal(actual.version, frozen.version, `${sample}/${id}: version 漂移`);
      assert.equal(actual.history.length, frozen.history, `${sample}/${id}: refinery 行数漂移`);
      assert.equal(report.fingerprints[id].fingerprint, recordedSample.sourceFingerprints[id].fingerprint, `${sample}/${id}: 历史指纹漂移`);
      const historical = historicalBaseline.databases.find(database => database.id === id);
      assert.equal(actual.version, historical.version, `${sample}/${id}: 旧包版本与 0222ccc 历史基线不一致`);
      assert.equal(report.fingerprints[id].fingerprint, historical.fingerprint, `${sample}/${id}: 旧包指纹与 0222ccc 历史基线不一致`);
    }
    assert.deepEqual(sourceDatabases, recordedSample.sourceDatabases, `${sample}: 历史库摘要漂移`);
    assert.deepEqual(report.fingerprints, recordedSample.sourceFingerprints, `${sample}: 历史指纹漂移`);
    assert.deepEqual(report.rows, recordedSample.rows, `${sample}: 业务行漂移`);
    assert.equal(report.entries.length, recordedSample.entries.length, `${sample}: 条目数漂移`);

    const upgrade = await verifyLegacyUpgrade(await readArchive(full(file)));
    assertTargetBaseline(sample, upgrade, baseline);
    const { target, ...targetMigration } = upgrade;
    assert.deepEqual(databaseSummary(target.databases), recordedSample.databases, `${sample}: 目标库摘要漂移`);
    assert.deepEqual(target.fingerprints, recordedSample.fingerprints, `${sample}: 目标指纹漂移`);
    assert.deepEqual(targetMigration, recordedSample.targetMigration, `${sample}: 目标迁移记录漂移`);
    log('legacy-export.verify.sample', {
      sample, bytes: bytes.length, sha256: recordedSample.sha256, entries: report.entries.length,
      failures: report.failures.length, sourceVersions: upgrade.sourceVersions, targetVersions: upgrade.targetVersions,
    });
  }

  // sidecar：形状 + 绑定 + 身份回放。
  assert.ok(exists(SIDECAR_FILE), `缺少 ${SIDECAR_FILE}`);
  const sidecarBytes = readFileSync(full(SIDECAR_FILE));
  assert.equal(sha256(sidecarBytes), expected.sidecar.sha256, 'sidecar 与 expected fixture 不一致');
  assert.equal(sha256(sidecarBytes), recorded.identity.continuity.sidecar.sha256, 'sidecar 与 artifact 不一致');
  const sidecar = JSON.parse(sidecarBytes.toString('utf8'));
  assertSidecarShape(sidecar);
  assert.equal(sidecar.status, 'new-contract-in-this-unit', 'sidecar 必须标注为本轮新定义');
  assert.equal(sidecar.snapshot.zip_sha256, recorded.samples.continuity.sha256, 'sidecar 未绑定 continuity 样本的 ZIP hash');
  assert.equal(sidecar.snapshot.backup_id, recorded.samples.continuity.manifest.backup_id, 'sidecar 未绑定 backup_id');
  assert.equal(sidecar.snapshot.snapshot_epoch, recorded.samples.continuity.manifest.snapshot_epoch, 'sidecar 未绑定 snapshot_epoch');

  const identity = await identityReplay(sidecar, sidecar.snapshot.zip_sha256, trustedTakeover(recorded.samples.continuity, sourceFacts()));
  assert.equal(identity.standardResult.differences.deviceHistory.after.length, 1);
  assert.equal(identity.standardResult.differences.consumeCursor.after[0].last_seq, 0);
  assert.deepEqual(identity.standardResult.differences.uploadSeq.before, identity.standardResult.differences.uploadSeq.after);
  assert.equal(identity.first.accepted, true, `continuity 接管失败：${identity.first.reason}`);
  assert.equal(identity.second.accepted, false);
  assert.ok(sidecarStateEqual(identity.first.receipt.state, sidecar.expected.state), 'continuity 接管状态与 sidecar.expected 不一致');
  assert.deepEqual(identity.standardResult.canonicalAfter, recorded.identity.standard.after, '普通恢复结果与 artifact 不一致');
  assert.deepEqual(identity.first.receipt, recorded.identity.continuity.first.receipt, 'continuity 接管回执与 artifact 不一致');
  assert.equal(identity.disk.matches, true, 'sidecar 状态在真实磁盘 sync_state.db 上重建后应与原状态一致');
  assert.deepEqual(identity.disk.state, recorded.identity.continuity.diskReplay.state, 'sidecar 磁盘回放结果与 artifact 不一致');

  const contract = JSON.parse(readFileSync(full(CONTRACT), 'utf8'));
  assert.equal(contract.unit, 'S0.16');
  assert.equal(contract.identity.modes.continuity.sidecar.status, '本轮新定义的平台无关合同；旧版移动端没有这个产物');
  assert.equal(contract.validation.order.length, 10);
  assert.equal(contract.archive.manifestVersion, '3.0.0');
  assert.equal(contract.keyPolicy.manifest, 'not_present');
  assert.deepEqual(contract.archive.layout, {
    databases: { vfs: 'vfs.db', llm_usage: 'llm_usage.db', chat_v2: 'chat_v2.db', mistakes: 'mistakes.db' },
    audit: 'databases/audit.db',
    blobs: 'assets/vfs_blobs/<relative_path>',
    manifest: 'manifest.json',
  });
  assert.equal(contract.delivery.samples.length, 2);
  for (const sample of contract.delivery.samples) {
    const recordedSample = recorded.samples[sample.mode];
    assert.ok(recordedSample !== undefined, `contract 的 ${sample.mode} 样本不在 artifact 里`);
    assert.equal(sample.sha256, recordedSample.sha256, `contract 的 ${sample.mode} 样本 hash 与 artifact 不一致`);
    assert.equal(sample.bytes, recordedSample.bytes, `contract 的 ${sample.mode} 样本大小与 artifact 不一致`);
    assert.equal(sample.file, recordedSample.file, `contract 的 ${sample.mode} 样本路径与 artifact 不一致`);
  }
  assert.equal(contract.delivery.samples.find(sample => sample.mode === 'continuity').sidecar.sha256,
    recorded.identity.continuity.sidecar.sha256, 'contract 的 sidecar hash 与 artifact 不一致');
  assert.equal(contract.sidecarDigest.file, SIDECAR_FILE);
  assert.equal(contract.sidecarDigest.sha256, recorded.identity.continuity.sidecar.sha256);
  assert.deepEqual(identity.negatives, recorded.identity.continuity.negatives, 'continuity negative cases must match the recorded contract');
  assert.deepEqual(await verifyDelivery(), recorded.channelFacts.transferChecks, 'one-shot delivery checks must match the recorded contract');
  const expectedContract = importContract({ samples: recorded.samples, environment: recorded.environment, source: sourceFacts(), sidecar: { file: SIDECAR_FILE, sha256: recorded.identity.continuity.sidecar.sha256 } });
  artifact(CONTRACT, expectedContract, 'verify');
  const sourceDrift = difference(recorded.source, sourceFacts());
  assert.equal(sourceDrift, null, `legacy source drift at ${sourceDrift?.path}`);
  log('legacy-export.verify.complete', {
    samples: Object.keys(recorded.samples).length,
    identityModes: 2,
    identityNegativeCases: identity.negatives.length,
    contract: CONTRACT,
  });
  return recorded;
}

export async function run(mode) {
  return mode === 'capture' ? capture() : verify();
}

main(import.meta.url, run);
