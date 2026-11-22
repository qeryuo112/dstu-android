/**
 * S0.20: machine-readable schema/wire/capability release window.
 *
 * The expensive two-device round trip and in-place pending replay are frozen in
 * S0.10 and verified by S0.19's release audit. This tool re-hashes that evidence,
 * then calls the current production format, schema and lease functions directly.
 *
 * Migration targets are fixed contract inputs, not heads-plus-index guesses.
 * Working-tree delivery is deliberately excluded from this artifact and reported
 * by release-audit from the coordinated directory so later rebases cannot make
 * this unit's verify drift.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  artifact, desktop, desktopRequire, git, main, readDesktop, readJson, root, sha256, log,
} from './lib.mjs';
import { INITIAL_VERSION, releaseVersion, versionCode } from './version.mjs';

const UNIT = 'S0.20';
const ARTIFACT = 'config/sync-release-window.json';
const PLAN = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const SHARED = ['vfs', 'llm_usage', 'chat_v2', 'mistakes'];
const DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const DESKTOP_TREE = '3bee0f058851b9eb113bdc036d9f60433ed29944';
const DESKTOP_VERSION = '0.20.1';
const VFS_BASELINE = 20261115;
const VFS_INCREMENT_TARGET = 20261117;
const MISTAKES_BASELINE = 20261005;
const MISTAKES_INCREMENT_TARGET = 20261006;
const FSRS_COLUMNS = [
  'fsrs_stability', 'fsrs_difficulty', 'fsrs_phase', 'fsrs_learning_step',
  'fsrs_lapses', 'fsrs_due_at', 'fsrs_last_review_at',
];
const SOURCE_INPUTS = [
  'config/baseline/schema.json',
  'config/baseline/protocol.json',
  'config/baseline/sync-formats.json',
  'config/sync-registry-contract.json',
  'config/release-contract.json',
  'config/baseline/platform.json',
];

// These are target-contract inputs. Delivery is reported separately from the
// coordinated directory; do not infer it here from locks or heads.
const MIGRATION_ENTRIES = [
  {
    unit: 'S0.21', id: 'portable-anki-templates', database: 'mistakes',
    targetVersion: 20261006, targetPath: 'mistakes/V20261006__portable_anki_templates.sql',
    previousReservation: { version: 20261006, path: 'mistakes/V20261006__portable_anki_templates.sql' },
    sourceContract: 'ready', schemaContract: 'delivered',
    assembly: 'S0.G',
    reservationDisposition: 'delivered', schemaRequired: true,
    reason: 'Delivered by S0.21 as a coordinated mistakes migration.',
  },
  {
    unit: 'S0.21', id: 'portable-attachment-roots', database: 'vfs',
    targetVersion: null, targetPath: null,
    previousReservation: { version: 20261113, path: 'vfs/V20261113__portable_attachment_roots.sql' },
    sourceContract: 'ready', schemaContract: 'not-required',
    assembly: 'S0.G',
    reservationDisposition: 'not-required', schemaRequired: false,
    reason: 'S0.21 uses vfs_blobs, active/anki_media and active/chat_attachments with existing tables; no SQL was delivered.',
  },
  {
    unit: 'S0.22', id: 'portable-media-progress', database: 'vfs',
    targetVersion: 20261116, targetPath: 'vfs/V20261116__portable_media_progress.sql',
    previousReservation: { version: 20261114, path: 'vfs/V20261114__portable_media_progress.sql' },
    sourceContract: 'ready', schemaContract: 'target-fixed',
    assembly: 'S0.G',
    reservationDisposition: 'superseded', schemaRequired: true,
    reason: 'The old 20261114 reservation is below the upstream 20261115 vfs head and cannot be appended; the contract target is 20261116.',
  },
  {
    unit: 'S0.23', id: 'portable-theme-configuration', database: 'vfs',
    targetVersion: null, targetPath: null,
    previousReservation: { version: 20261115, path: 'vfs/V20261115__portable_theme_configuration.sql' },
    sourceContract: 'ready', schemaContract: 'not-required',
    assembly: 'S0.G',
    reservationDisposition: 'superseded', schemaRequired: false,
    reason: 'S0.23 reuses portable settings transport. Upstream already owns vfs 20261115 for review_plans_fsrs, and no theme SQL is required.',
  },
  {
    unit: 'S0.24', id: 'plugin-skill-data-contract', database: 'vfs',
    targetVersion: 20261117, targetPath: 'vfs/V20261117__plugin_skill_data_contract.sql',
    previousReservation: { version: 20261116, path: 'vfs/V20261116__plugin_skill_data_contract.sql' },
    sourceContract: 'ready', schemaContract: 'target-fixed',
    assembly: 'S0.G',
    reservationDisposition: 'superseded', schemaRequired: true,
    reason: 'The old 20261116 reservation is displaced by media; the contract target is 20261117.',
  },
];

const hashes = files => Object.fromEntries(files.map(file => [file, sha256(readDesktop(file))]));
const load = file => import(pathToFileURL(resolve(desktop, file)).href);

function sourceInputHashes() {
  return Object.fromEntries(SOURCE_INPUTS.map(file => [file, sha256(readFileSync(resolve(root, file)))]));
}

const migrationEntries = () => MIGRATION_ENTRIES.map(entry => ({ ...entry }));

async function desktopModule(entry) {
  const esbuild = desktopRequire('esbuild');
  const result = await esbuild.build({
    absWorkingDir: desktop,
    stdin: { contents: entry, resolveDir: desktop, sourcefile: 's020-entry.ts', loader: 'ts' },
    tsconfig: resolve(desktop, 'tsconfig.json'), bundle: true, write: false,
    format: 'esm', platform: 'node', target: 'node22', logLevel: 'silent',
  });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
}

function sourceEvidence(file, needle, symbol) {
  const lines = readDesktop(file).split('\n');
  const index = lines.findIndex(line => line.includes(needle));
  assert.ok(index >= 0, `${file}: missing ${needle}`);
  return { source: 'desktop', file, line: index + 1, symbol, text: lines[index].trim() };
}

function migrationHead(database, schema, lock) {
  const entries = lock.entries.filter(item => item.database === database).sort((left, right) => left.version - right.version);
  const latest = entries.at(-1);
  assert.ok(latest, `${database}: migration lock entry`);
  return {
    database, version: latest.version, name: latest.name, file: latest.path,
    sha256: latest.sha256, count: entries.length,
    baselineVersion: schema.databases.find(item => item.id === database).version,
  };
}

async function productionProbe() {
  const module = await desktopModule(`
    export { CURRENT_FORMAT_VERSION, MIN_COMPATIBLE_FORMAT_VERSION, FORMAT_KEY, COMPATIBILITY_RELEASE_DAYS } from './host/plugins/sync/src/remote-format.ts';
    export { validateRemoteFormat } from './host/plugins/sync/src/remote-format.ts';
    export { validateUploadSchemaCompatibility, SchemaMismatchError } from './host/plugins/sync/src/manifest-upload.ts';
    export { createManifest, detectConflicts } from './host/plugins/sync/src/merge.ts';
    export { schemaMismatchError } from './host/plugins/sync/src/manifest-upload.ts';
    export { MemoryStorage } from './host/plugins/sync-providers/tests/memory_storage.ts';
    export { acquireSyncTargetLeaseWithTtl, DEFAULT_SYNC_LEASE_TTL_MS } from './host/plugins/sync-providers/src/sync_lease.ts';
    export { createPayloadCodec } from './host/plugins/sync/src/payload.ts';
  `);
  const crypto = await load('host/platform/secrets/backup-crypto.ts');
  const codec = await module.createPayloadCodec(crypto.createBackupCrypto(), null);
  const storage = new module.MemoryStorage('s020-memory');
  const options = { deviceId: 'device-s020-local', codec };

  const state = (schemaVersion, dataVersion = 1, checksum = 's020') =>
    ({ schema_version: schemaVersion, data_version: dataVersion, checksum, last_updated_at: null });
  const manifest = (device, databases) => module.createManifest(device, databases);
  const call = async (body) => {
    try { return { accepted: true, error: null, value: await body() }; }
    catch (error) { return { accepted: false, error: { name: error.constructor.name, message: error.message, mismatches: error.mismatches ?? null } }; }
  };

  const upload = async (cloudDatabases, localDatabases) => {
    const cloud = new module.MemoryStorage('s020-schema');
    const peer = manifest('peer', cloudDatabases);
    const key = `data_governance/manifests/${(await import(pathToFileURL(resolve(desktop, 'host/plugins/sync-providers/api.ts')).href)).deviceIdShortHash('peer')}.json`;
    await cloud.put(key, await codec.encode(new TextEncoder().encode(JSON.stringify(peer))));
    const seedWrites = cloud.putLog.length;
    const before = cloud.keys().map(item => [item, sha256(cloud.bytes(item))]);
    const result = await call(() => module.validateUploadSchemaCompatibility(cloud, manifest('local', localDatabases), options));
    const after = cloud.keys().map(item => [item, sha256(cloud.bytes(item))]);
    return { accepted: result.accepted, error: result.error,
      writesUnchanged: JSON.stringify(before) === JSON.stringify(after),
      seedWrites, writesDuringGate: cloud.putLog.length - seedWrites };
  };

  const gates = [
    { scenario: 'cloud-newer-local-older', cloud: VFS_BASELINE, local: 20261027, expected: 'reject',
      run: await upload({ vfs: state(VFS_BASELINE) }, { vfs: state(20261027) }) },
    { scenario: 'cloud-older-local-newer', cloud: 20261027, local: VFS_BASELINE, expected: 'accept-at-upload-gate',
      run: await upload({ vfs: state(20261027) }, { vfs: state(VFS_BASELINE) }) },
    { scenario: 'equal-schema', cloud: VFS_BASELINE, local: VFS_BASELINE, expected: 'accept',
      run: await upload({ vfs: state(VFS_BASELINE) }, { vfs: state(VFS_BASELINE) }) },
    { scenario: 'missing-local-database', cloud: 20261005, local: 0, expected: 'reject-as-local-zero',
      run: await upload({ vfs: state(20261005) }, {}) },
    { scenario: 'per-database-cloud-newer', cloud: VFS_BASELINE, local: 20261027, expected: 'reject',
      run: await upload({ vfs: state(VFS_BASELINE), chat_v2: state(VFS_BASELINE) },
        { vfs: state(VFS_BASELINE), chat_v2: state(20261027) }) },
    { scenario: 'per-database-cloud-older', cloud: 20261027, local: VFS_BASELINE, expected: 'accept-at-upload-gate',
      run: await upload({ vfs: state(VFS_BASELINE), chat_v2: state(20261027) },
        { vfs: state(VFS_BASELINE), chat_v2: state(VFS_BASELINE) }) },
  ];
  for (const item of gates) {
    assert.equal(item.run.accepted, item.expected === 'accept' || item.expected === 'accept-at-upload-gate', item.scenario);
    assert.equal(item.run.writesUnchanged, true, `${item.scenario}: upload gate must not write`);
    assert.equal(item.run.writesDuringGate, 0, `${item.scenario}: upload gate write count`);
  }

  const detection = module.detectConflicts(
    manifest('local', { vfs: state(20261027) }),
    manifest('cloud', { vfs: state(VFS_BASELINE) }),
  );
  const detectionOlder = module.detectConflicts(
    manifest('local', { vfs: state(VFS_BASELINE) }),
    manifest('cloud', { vfs: state(20261027) }),
  );
  const schemaError = module.schemaMismatchError(detection);
  assert.equal(detection.needs_migration, true);
  assert.equal(detectionOlder.needs_migration, true);
  assert.ok(schemaError instanceof module.SchemaMismatchError);

  const formatCases = [];
  for (const [label, text, write, expected] of [
    ['v3-read', '{"format_version":3}', false, 'accept'],
    ['v3-write', '{"format_version":3}', true, 'accept-and-upgrade'],
    ['v4-min3-write', '{"format_version":4,"min_client":3}', true, 'accept'],
    ['v4-min4-write', '{"format_version":4,"min_client":4}', true, 'accept'],
    ['v5-write', '{"format_version":5}', true, 'reject'],
    ['v2-read', '{"format_version":2}', false, 'reject'],
  ]) {
    const cloud = new module.MemoryStorage(`s020-${label}`);
    await cloud.put(module.FORMAT_KEY, new TextEncoder().encode(text));
    const before = cloud.keys().map(key => [key, sha256(cloud.bytes(key))]);
    const result = await call(() => module.validateRemoteFormat(cloud, write, { deviceId: 'device-s020-local', codec }));
    const after = cloud.keys().map(key => [key, sha256(cloud.bytes(key))]);
    const accepted = result.accepted;
    const ok = expected === 'reject' ? !accepted : accepted;
    assert.ok(ok, `${label}: ${expected}`);
    if (!accepted) assert.equal(JSON.stringify(before), JSON.stringify(after), `${label}: rejected format must not write`);
    formatCases.push({ label, input: JSON.parse(text), willWrite: write, expected, accepted,
      descriptor: accepted ? { format_version: result.value.format_version, min_reader_version: result.value.min_reader_version } : null,
      error: result.error, writesUnchanged: JSON.stringify(before) === JSON.stringify(after) });
  }
  assert.equal(formatCases.find(item => item.label === 'v3-write').writesUnchanged, false);
  assert.equal(formatCases.find(item => item.label === 'v4-min4-write').writesUnchanged, true);

  const leaseStorage = new module.MemoryStorage('s020-lease');
  const lease = await module.acquireSyncTargetLeaseWithTtl(leaseStorage, 'device-s020-local', 30_000);
  const leaseShape = {
    format_version: lease.lease.format_version, target: lease.lease.target,
    holder_device_id: lease.lease.holder_device_id, activation_committed: lease.lease.activation_committed,
    keyPrefix: lease.key.slice(0, lease.key.lastIndexOf('/') + 1),
  };
  await lease.release();
  const leaseCleared = leaseStorage.keys().length === 0;
  assert.equal(leaseShape.format_version, 1);
  assert.equal(leaseShape.target, 'data_governance');
  assert.equal(leaseCleared, true);

  codec.dispose();
  return {
    constants: {
      currentFormat: module.CURRENT_FORMAT_VERSION,
      minCompatible: module.MIN_COMPATIBLE_FORMAT_VERSION,
      compatibilityReleaseDays: module.COMPATIBILITY_RELEASE_DAYS,
      formatKey: module.FORMAT_KEY,
      defaultLeaseTtlMs: module.DEFAULT_SYNC_LEASE_TTL_MS,
    },
    schemaGates: gates.map(({ run, ...item }) => ({ ...item, ...run })),
    laterDetector: {
      source: 'host/plugins/sync/src/detect-conflicts.ts and merge.ts',
      cloudNewer: { conflict_type: detection.database_conflicts[0]?.conflict_type, needs_migration: detection.needs_migration,
        schemaMismatch: schemaError.mismatches },
      cloudOlder: { conflict_type: detectionOlder.database_conflicts[0]?.conflict_type, needs_migration: detectionOlder.needs_migration,
        schemaMismatch: module.schemaMismatchError(detectionOlder).mismatches },
      distinction: 'validateUploadSchemaCompatibility rejects only cloud schema > local; detectConflicts/schemaMismatchError later reports any unequal schema in either direction',
    },
    formatCases,
    lease: { ...leaseShape, released: leaseCleared },
  };
}

export async function collect() {
  const schema = readJson('config/baseline/schema.json');
  const protocol = readJson('config/baseline/protocol.json');
  const registry = readJson('config/sync-registry-contract.json');
  const syncFormats = readJson('config/baseline/sync-formats.json');
  const release = readJson('config/release-contract.json');
  const platform = readJson('config/baseline/platform.json');
  const lock = JSON.parse(readDesktop('resources/app/migrations/migration-lock.json'));
  const plan = readFileSync(resolve(root, PLAN), 'utf8').split('\n');
  const planLine = plan.findIndex(line => line.startsWith('| S0.20 |')) + 1;
  assert.ok(planLine > 0, 'S0.20 plan row');
  assert.equal(git(desktop, 'rev-parse', 'HEAD'), DESKTOP_COMMIT, 'desktop freeze commit');
  assert.equal(git(desktop, 'rev-parse', 'HEAD^{tree}'), DESKTOP_TREE, 'desktop freeze tree');
  assert.equal(JSON.parse(readDesktop('package.json')).version, DESKTOP_VERSION, 'desktop package version');

  assert.deepEqual(protocol.schemaMatrix.map(item => item.database).sort(), [...SHARED].sort());
  assert.equal(registry.unit, 'S0.19');
  const reviewPlanRegistration = registry.registry.find(item => item.database === 'vfs' && item.table === 'review_plans');
  assert.ok(reviewPlanRegistration, 'S0.19 review_plans registration');
  const reviewPlanSync = registry.reviewPlans?.sync;
  assert.ok(reviewPlanSync, 'S0.19 top-level reviewPlans.sync');
  assert.deepEqual(registry.reviewPlans.columns, FSRS_COLUMNS, 'S0.19 full seven-column FSRS contract');
  assert.equal(reviewPlanSync.wire?.parsedEquals, true, 'S0.19 review_plans production wire roundtrip must be parsed');
  assert.ok(reviewPlanSync.wire.bytes > 0, 'S0.19 review_plans wire bytes');
  assert.equal(reviewPlanSync.changes, 2);
  assert.equal(reviewPlanSync.operation, 'Update');
  assert.deepEqual(reviewPlanSync.source, reviewPlanSync.target, 'S0.19 review_plans source/target roundtrip');
  assert.deepEqual(reviewPlanSync.source.fsrs, {
    stability: 2.30649995803833,
    difficulty: 2.1181039810180664,
    phase: 'learning',
    learning_step: 1,
    lapses: 0,
    due_at: 1794715800000,
    last_review_at: 1794715200000,
  }, 'S0.19 review_plans seven FSRS values');
  const unknownTarget = reviewPlanSync.unknownFields;
  assert.deepEqual(unknownTarget.targetRecord, unknownTarget.parsed, 'S0.19 unknown fields reach the parsed target record');
  assert.deepEqual(unknownTarget.targetVault, unknownTarget.seededBeforeSync, 'S0.19 unknown target vault');
  assert.equal(unknownTarget.valuesRetained, true);
  assert.equal(unknownTarget.sourceCanonicalSha256, unknownTarget.targetCanonicalSha256,
    'S0.19 unknown target canonical hash');
  const reviewPlanParity = {
    registered: true,
    category: reviewPlanRegistration.category,
    incrementalSync: reviewPlanRegistration.scope.incrementalSync,
    captureTriggers: reviewPlanRegistration.triggers.allRequiredPresent,
    sourceMatchesTarget: JSON.stringify(reviewPlanSync.source) === JSON.stringify(reviewPlanSync.target),
    syncParsed: reviewPlanSync.wire.parsedEquals === true,
    fsrsColumns: registry.reviewPlans.columns,
    wire: { bytes: reviewPlanSync.wire.bytes, changes: reviewPlanSync.changes, operation: reviewPlanSync.operation },
    unknownTarget: {
      seededBeforeSync: unknownTarget.seededBeforeSync,
      collected: unknownTarget.collected,
      parsed: unknownTarget.parsed,
      targetRecord: unknownTarget.targetRecord,
      targetVault: unknownTarget.targetVault,
      valuesRetained: unknownTarget.valuesRetained,
      canonicalSha256: unknownTarget.targetCanonicalSha256,
      sourceTargetCanonicalEqual:
        unknownTarget.sourceCanonicalSha256 === unknownTarget.targetCanonicalSha256,
    },
  };
  assert.equal(reviewPlanParity.sourceMatchesTarget, true, 'review_plans sync roundtrip source/target mismatch');
  assert.equal(reviewPlanParity.syncParsed, true, 'review_plans production sync roundtrip must be parsed before final capture');
  const captureState = 'validated-source-contract';
  assert.equal(syncFormats.unit, 'S0.18');
  assert.equal(syncFormats.formatVersion, 2);
  assert.match(syncFormats.format, /\/v2$/);
  for (const item of syncFormats.artifacts) {
    assert.equal(item.sha256, sha256(readFileSync(resolve(root, item.file))), `${item.file}: S0.18 hash`);
  }
  assert.equal(schema.migrations.length, 135, '135 frozen upstream SQL files');
  assert.equal(schema.migrations.filter(item => item.database === 'vfs').length, 73);
  assert.equal(schema.migrations.filter(item => item.database === 'mistakes').length, 23);
  const dbRows = SHARED.map(name => {
    const baseline = schema.databases.find(item => item.id === name);
    const head = migrationHead(name, schema, lock);
    const row = protocol.schemaMatrix.find(item => item.database === name);
    assert.equal(head.version, baseline.version, `${name}: migration head`);
    assert.equal(head.baselineVersion, baseline.version, `${name}: schema baseline`);
    assert.equal(row.schemaVersion, baseline.version, `${name}: protocol schema`);
    assert.equal(row.fingerprint, baseline.fingerprint, `${name}: fingerprint`);
    return {
      database: name, schemaVersion: baseline.version, migrationCount: head.count,
      fingerprint: baseline.fingerprint, path: baseline.path,
      migrationHead: { version: head.version, name: head.name, file: head.file, sha256: head.sha256 },
      scope: { tables: baseline.fingerprintScope.tables.length, indexes: baseline.fingerprintScope.indexes.length,
        triggers: baseline.fingerprintScope.triggers.length },
      rowSync: row.rowSync, productionIterationIndex: row.productionIterationIndex,
      wireRole: row.wireRole,
    };
  });
  const vfsBaseline = dbRows.find(row => row.database === 'vfs');
  const mistakesBaseline = dbRows.find(row => row.database === 'mistakes');
  assert.equal(vfsBaseline.schemaVersion, VFS_BASELINE);
  assert.equal(mistakesBaseline.schemaVersion, MISTAKES_BASELINE);

  const probe = await productionProbe();
  assert.equal(probe.constants.currentFormat, 4);
  assert.equal(probe.constants.minCompatible, 3);
  assert.equal(probe.formatCases.filter(item => !item.accepted).length, 2);
  assert.equal(probe.schemaGates.filter(item => !item.accepted).length, 3);
  assert.equal(probe.laterDetector.cloudOlder.needs_migration, true);
  assert.equal(probe.lease.released, true);

  const same = protocol.scenarios.sameSchema;
  const mismatch = protocol.scenarios.mismatch;
  for (const key of ['pendingUnchanged', 'localRowsUnchanged', 'publicationWriteCount', 'passed']) {
    assert.ok(['boolean', 'number'].includes(typeof mismatch.rejection[key]), `S0.10 rejection.${key}`);
  }
  for (const key of ['dataReinserted', 'sameDirectorySameDeviceId', 'originalPendingCarried', 'passed']) {
    assert.equal(typeof mismatch.upgrade[key], 'boolean', `S0.10 upgrade.${key}`);
  }
  assert.equal(same.passed, true);
  assert.equal(mismatch.rejection.passed, true);
  assert.equal(mismatch.upgrade.passed, true);
  const protocolArtifact = sha256(readFileSync(resolve(root, 'config/baseline/protocol.json')));
  const initial = releaseVersion(INITIAL_VERSION);
  assert.equal(versionCode(initial.version), initial.versionCode);
  assert.equal(initial.versionCode, release.version.initial.versionCode);
  const versionCases = [
    { version: '0.20.0', accepted: false, reason: 'legacy line is not publishable from the new source' },
    { version: '0.21.0', accepted: true, versionCode: initial.versionCode },
    { version: '0.21.00', accepted: false, reason: 'leading zero is not stable semver' },
  ].map(item => {
    try { return { ...item, result: releaseVersion(item.version) }; }
    catch (error) { return { ...item, result: { rejected: error.message } }; }
  });
  for (const item of versionCases) assert.equal(!('rejected' in item.result), item.accepted, item.version);

  const entries = migrationEntries();
  assert.equal(entries.length, 5);
  assert.deepEqual(entries.filter(item => item.schemaContract === 'delivered').map(item => item.targetVersion), [20261006]);
  assert.deepEqual(entries.filter(item => item.schemaContract === 'not-required').map(item => item.unit), ['S0.21', 'S0.23']);
  assert.deepEqual(entries.filter(item => item.schemaContract === 'target-fixed').map(item => [item.unit, item.targetVersion]),
    [['S0.22', 20261116], ['S0.24', 20261117]]);
  assert.equal(entries.filter(item => item.reservationDisposition === 'superseded').length, 3);
  assert.equal(entries.filter(item => item.sourceContract === 'ready').length, 5);
  assert.equal(entries.filter(item => item.assembly === 'S0.G').length, 5);
  assert.equal(entries.every(item => !('sha256' in item)), true, 'delivery hashes belong to release-audit');
  assert.equal(entries.find(item => item.unit === 'S0.22').previousReservation.version < VFS_BASELINE, true);
  assert.equal(entries.find(item => item.unit === 'S0.23').previousReservation.version, VFS_BASELINE);
  assert.equal(entries.find(item => item.unit === 'S0.24').previousReservation.version, 20261116);
  assert.equal(entries.find(item => item.unit === 'S0.22').targetVersion, 20261116);
  assert.equal(entries.find(item => item.unit === 'S0.24').targetVersion, 20261117);
  assert.equal(VFS_INCREMENT_TARGET, 20261117);
  assert.equal(MISTAKES_INCREMENT_TARGET, 20261006);

  const capabilityMatrix = [
    { platform: 'desktop', provider: 'webdav', desktop: true, legacyMobile: null, nativeAndroid: 'not-built',
      observed: protocol.providers.find(item => item.provider === 'webdav').desktop },
    { platform: 'desktop', provider: 's3', desktop: true, legacyMobile: null, nativeAndroid: 'not-built',
      observed: protocol.providers.find(item => item.provider === 's3').desktop },
    { platform: 'desktop', provider: 'ftp', desktop: true, legacyMobile: null, nativeAndroid: 'not-built',
      observed: protocol.providers.find(item => item.provider === 'ftp').desktop },
    { platform: 'legacy-mobile', provider: 'webdav', desktop: null, legacyMobile: true, nativeAndroid: 'not-built',
      observed: protocol.providers.find(item => item.provider === 'webdav').mobile },
    { platform: 'legacy-mobile', provider: 's3', desktop: null, legacyMobile: false, nativeAndroid: 'not-built',
      observed: protocol.providers.find(item => item.provider === 's3').mobile },
    { platform: 'legacy-mobile', provider: 'ftp', desktop: null, legacyMobile: false, nativeAndroid: 'not-built',
      observed: protocol.providers.find(item => item.provider === 'ftp').mobile },
  ];
  const consumedBy = [
    { unit: 'S1.05', task: 'Kotlin migration runner and fingerprint parity' },
    { unit: 'S1.24', task: 'WebDAV, independent device id, manifest/object keys/codec' },
    { unit: 'S2.02', task: 'migration and fingerprint upgrade reports' },
    { unit: 'S2.26', task: 'pending publication reservation and verified replay' },
    { unit: 'S2.27', task: 'download apply and post-commit cursor' },
    { unit: 'S8.05', task: 'provider lease and verified publish conformance' },
    { unit: 'S8.16', task: 'real-package schema/wire matrix' },
  ];

  const data = {
    formatVersion: 2, unit: UNIT,
    planContract: { file: PLAN, line: planLine },
    capture: {
      state: captureState,
      reviewPlansSyncParsed: reviewPlanParity.syncParsed,
      reviewPlanParity,
      sourceContractState: 'ready',
      note: 'This is the final S0.20 source-contract capture. Actual coordinated delivery is observed separately by release-audit.mjs.',
    },
    sources: {
      schema: 'config/baseline/schema.json', protocol: 'config/baseline/protocol.json',
      registry: 'config/sync-registry-contract.json', syncFormats: 'config/baseline/sync-formats.json',
      release: 'config/release-contract.json',
      platform: 'config/baseline/platform.json', migrationLock: 'dstu-node/resources/app/migrations/migration-lock.json',
      desktop: {
        commit: DESKTOP_COMMIT, tree: DESKTOP_TREE, packageVersion: DESKTOP_VERSION,
        frozen: true, releaseClaimed: false,
      },
      hashes: {
        ...hashes([
          'host/plugins/sync/src/remote-format.ts', 'host/plugins/sync/src/manifest-upload.ts',
          'host/plugins/sync/src/detect-conflicts.ts', 'host/plugins/sync/src/merge.ts',
          'host/plugins/sync/src/run-sync.ts', 'host/plugins/sync-providers/src/sync_lease.ts',
          'host/plugins/sync-providers/src/lease.ts', 'host/plugins/sync-providers/src/config.ts',
          'host/plugins/sync-providers/mobile.ts', 'host/plugins/sync-providers/src/desktop-platform.ts',
        ]),
        'config/baseline/schema.json': sha256(readFileSync(resolve(root, 'config/baseline/schema.json'))),
        'config/baseline/protocol.json': protocolArtifact,
        'config/baseline/sync-formats.json': sha256(readFileSync(resolve(root, 'config/baseline/sync-formats.json'))),
        'config/sync-registry-contract.json': sha256(readFileSync(resolve(root, 'config/sync-registry-contract.json'))),
        'config/release-contract.json': sha256(readFileSync(resolve(root, 'config/release-contract.json'))),
        'config/baseline/platform.json': sha256(readFileSync(resolve(root, 'config/baseline/platform.json'))),
        'dstu-node/resources/app/migrations/migration-lock.json': sha256(readDesktop('resources/app/migrations/migration-lock.json')),
      },
    },
    schema: {
      sharedDatabases: dbRows, deviceLocal: schema.registeredDatabases.filter(item => item.androidScope === 'device-local').map(item => item.id),
      nativeBaseline: {
        vfs: VFS_BASELINE, mistakes: MISTAKES_BASELINE,
        historicalSqlFiles: schema.migrations.length,
        vfsSqlFiles: schema.migrations.filter(item => item.database === 'vfs').length,
        upstreamVfsMigration: 'V20261115__review_plans_fsrs.sql',
      },
      incrementTarget: {
        vfs: VFS_INCREMENT_TARGET, mistakes: MISTAKES_INCREMENT_TARGET, separate: true,
        source: 'config/schema/coordinated; not the locked historical baseline and not the upstream migration lock',
      },
      fingerprintRule: schema.rules.fingerprint, sqliteRule: schema.rules.sqlite,
      coordinatedMigrationRule: 'Shared databases use identical version/raw SQL on both sides; append only after both installers are available, then enter the coordinated upgrade window.',
      finalSnapshot: 'The native baseline stays at vfs 20261115. Coordinated vfs 20261116/20261117 and mistakes 20261006 are fixed target contracts; S0.G independently assembles and freezes the actual delivered set.',
      attachmentMediaRoots: { existing: ['active/vfs_blobs'], candidateAssetRoots: ['active/anki_media', 'active/attachments'],
        schemaChange: 'not required by this unit; asset roots and reference policy are S0.21 contracts' },
    },
    wire: {
      current: probe.constants.currentFormat, minimumReader: probe.constants.minCompatible,
      compatibilityLeaseDays: probe.constants.compatibilityReleaseDays, formatKey: probe.constants.formatKey,
      v5: { state: 'inactive', registered: protocol.versionWindow.v5.registered,
        reason: protocol.versionWindow.v5.activation, wire: protocol.versionWindow.v5.wire },
      formatCases: probe.formatCases,
      distinction: probe.laterDetector.distinction,
    },
    application: {
      initialVersion: initial.version, versionCode: initial.versionCode,
      independent: true, desktopReleaseClaimed: false,
      scope: 'Application version labels are independent; they are not written into wire/manifest and do not prove different binary execution.',
      versionCases,
      installation: {
        applicationId: release.identity.applicationId, minSdk: release.platform.minSdk,
        targetSdk: release.platform.targetSdk, compileSdk: release.platform.compileSdk, abi: release.abi.shipped,
        sameInstallIdentity: release.identity.packageChange === 'never',
        releasePackageObserved: release.releasePackage.observed,
      },
    },
    capabilities: capabilityMatrix,
    lease: { ...probe.lease, ttlMs: probe.constants.defaultLeaseTtlMs, namespace: 'data_governance/locks/sync-target' },
    schemaGates: probe.schemaGates,
    laterDetector: probe.laterDetector,
    frozenEvidence: {
      protocolArtifactSha256: protocolArtifact,
      sameSchema: { passed: same.passed, schemaVersion: same.schemaVersion, applicationVersionLabels: same.applicationVersionLabels,
        applicationVersionScope: same.applicationVersionScope, cloudKeys: same.cloudKeys },
      mismatch: { rejectionPassed: mismatch.rejection.passed, publicationWriteCount: mismatch.rejection.publicationWriteCount,
        pendingUnchanged: mismatch.rejection.pendingUnchanged, localRowsUnchanged: mismatch.rejection.localRowsUnchanged,
        controlPlaneWrites: mismatch.rejection.controlPlaneWrites, upgradePassed: mismatch.upgrade.passed,
        sameDirectorySameDeviceId: mismatch.upgrade.sameDirectorySameDeviceId,
        originalPendingCarried: mismatch.upgrade.originalPendingCarried, dataReinserted: mismatch.upgrade.dataReinserted,
        schemaFrom: mismatch.upgrade.schemaFrom, schemaTo: mismatch.upgrade.schemaTo },
      note: 'Reused S0.10 real runSync evidence; S0.19 FSRS production roundtrip is parsed and validated in this final capture.',
    },
    migrationPlan: {
      format: 'dstu-s0.20-migration-contract/v2',
      status: 'target-contract-fixed',
      nativeBaselineHeads: Object.fromEntries(dbRows.map(row => [row.database, row.migrationHead])),
      increments: {
        vfs: { baseline: VFS_BASELINE, target: VFS_INCREMENT_TARGET },
        mistakes: { baseline: MISTAKES_BASELINE, target: MISTAKES_INCREMENT_TARGET },
      },
      entries,
      reconciliation: {
        template: '20261006 target contract; delivery observed by release-audit',
        attachment: '20261113 not-required; no SQL',
        media: '20261114 superseded and below the 20261115 upstream head; 20261116 target contract',
        theme: '20261115 superseded by upstream review_plans_fsrs; no theme SQL',
        plugin: '20261116 superseded; 20261117 target contract',
      },
      reservationBasis: 'The previous reservations were conditional heads-plus-index guesses and are removed. The fixed contract now records source-contract readiness, not file delivery.',
      rule: 'The native baseline and increment targets are separate. Do not append a coordinated migration at or below the upstream head, and do not invent SQL for a not-required contract.',
      deliveryReport: 'release-audit.mjs derives current target/stale file presence and hashes from config/schema/coordinated; delivery transitions belong to the S0.G report.',
      snapshotPolicy: 'S0.G independently assembles and freezes the delivered coordinated set. This artifact stores only fixed target-contract inputs so later rebases do not drift S0.20 verify.',
    },
    claims: {
      kotlinHostImplemented: false,
      hostInstallationComplete: false,
      desktopReleasePublished: false,
      s0gFinalAssemblyComplete: false,
    },
    consumers: { desktop: ['host/plugins/sync/src/remote-format.ts', 'host/plugins/sync/src/manifest-upload.ts', 'host/plugins/sync/src/detect-conflicts.ts'],
      android: consumedBy, installation: 'S1.01 Gradle identity; S1.05 migration runner; S1.24 provider; S8.16 real-package matrix' },
    checks: {
      sharedDatabases: dbRows.length, schemaFingerprintsMatchBaseline: true, applicationVersionsIndependent: true,
      versionRejectedLegacy: versionCases.find(item => item.version === '0.20.0').accepted === false,
      versionRejectedMalformed: versionCases.find(item => item.version === '0.21.00').accepted === false,
      formatCurrent: probe.constants.currentFormat, formatMinimum: probe.constants.minCompatible,
      v5Inactive: protocol.versionWindow.v5.registered === false,
      formatNegativeCases: probe.formatCases.filter(item => !item.accepted).length,
      uploadGateNegativeCases: probe.schemaGates.filter(item => !item.accepted).length,
      laterUnequalSchemaDetected: probe.laterDetector.cloudOlder.needs_migration && probe.laterDetector.cloudNewer.needs_migration,
      leaseAcquireRelease: probe.lease.released,
      legacyMobileWebdavOnly: capabilityMatrix.filter(item => item.platform === 'legacy-mobile' && item.legacyMobile).map(item => item.provider).join(',') === 'webdav',
      nativeAndroidProvidersClaimed: capabilityMatrix.filter(item => item.platform === 'nativeAndroid').length,
      historicalSqlFiles: schema.migrations.length,
      schemaBaselineVfs: VFS_BASELINE, schemaBaselineMistakes: MISTAKES_BASELINE,
      vfsIncrementTarget: VFS_INCREMENT_TARGET, mistakesIncrementTarget: MISTAKES_INCREMENT_TARGET,
      migrationEntriesReconciled: entries.length,
      sourceContractsReady: entries.filter(item => item.sourceContract === 'ready').length,
      deliveredSchemaContracts: entries.filter(item => item.schemaContract === 'delivered').length,
      notRequiredSchemaContracts: entries.filter(item => item.schemaContract === 'not-required').length,
      targetFixedSchemaContracts: entries.filter(item => item.schemaContract === 'target-fixed').length,
      supersededReservations: entries.filter(item => item.reservationDisposition === 'superseded').length,
      s021TemplateTarget: entries.some(item => item.schemaContract === 'delivered' && item.targetVersion === 20261006),
      s018GoldenV2Stable: syncFormats.formatVersion === 2
        && syncFormats.artifacts.every(item => item.sha256 === sha256(readFileSync(resolve(root, item.file)))),
      desktopFrozenCommit: DESKTOP_COMMIT,
      desktopReleaseClaimed: false,
      kotlinHostImplemented: false,
      hostInstallationComplete: false,
      frozenPendingUpgradeEvidence: mismatch.rejection.passed && mismatch.upgrade.passed,
      reviewPlansSyncParsed: reviewPlanParity.syncParsed,
      sourceContractReady: true,
    },
    evidence: {
      sourceHashes: sourceEvidence('host/plugins/sync/src/manifest-upload.ts', 'export async function validateUploadSchemaCompatibility', 'validateUploadSchemaCompatibility'),
      conflictSource: sourceEvidence('host/plugins/sync/src/merge.ts', 'export function detectConflicts', 'detectConflicts'),
      leaseSource: sourceEvidence('host/plugins/sync-providers/src/sync_lease.ts', 'export async function acquireSyncTargetLeaseWithTtl', 'acquireSyncTargetLeaseWithTtl'),
      schemaSource: sourceEvidence('host/plugins/sync/src/remote-format.ts', 'export const CURRENT_FORMAT_VERSION', 'CURRENT_FORMAT_VERSION'),
    },
  };
  assert.ok(platform.unit === 'S0.15');
  return data;
}

export async function run(mode) {
  const started = performance.now();
  const before = sourceInputHashes();
  const value = await collect();
  assert.deepEqual(sourceInputHashes(), before, 'source inputs changed during S0.20 collection; rerun after S0.19 settles');
  artifact(ARTIFACT, value, mode);
  log('release-window.accepted', { unit: UNIT, mode, elapsedMs: Math.round(performance.now() - started), ...value.checks });
}

main(import.meta.url, run);
