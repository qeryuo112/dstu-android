import assert from 'node:assert/strict';
import { loadDesktop } from './package.mjs';

const { SqliteSyncStateStore } = await loadDesktop('host/plugins/sync-support/src/state.ts');

export const CONTINUITY_FORMAT = 'dstu.legacy.mobile.continuity';
export const CONTINUITY_VERSION = 1;
export const FIXTURE_DEVICES = { old: 'device-7f3a91c2', peer: 'device-91bc4de0', next: 'device-2c55a8f1' };
export const FIXTURE_INSTANCE = 'default';
export const FIXTURE_INSTALLATION = 'installation-legacy-mobile-demo';
const TIMESTAMP_FIELDS = new Set(['updated_at', 'rotated_at', 'bound_at', 'created_at', 'processed_at']);
const STATE_TABLES = ['instanceBinding', 'uploadSeq', 'consumeCursor', 'tombstoneWatermark', 'tombstoneEventPublish', 'legacyProcessedKey', 'deviceHistory'];
const I64_MAX = 9223372036854775807n;

export async function openStore(path = ':memory:') {
  const { SqliteDatabase } = await loadDesktop('host/platform/storage/database.ts');
  const db = new SqliteDatabase({ name: 'sync_state', path, profile: { synchronous: 'FULL', busyTimeoutMs: 5000 } });
  return { db, store: new SqliteSyncStateStore(db) };
}

function transactionalStore(tx) {
  return new SqliteSyncStateStore({
    get: (query, params) => tx.get(query, params),
    all: (query, params) => tx.all(query, params),
    run: (query, params) => tx.run(query, params),
    exec: sql => tx.exec(sql),
    transaction: fn => tx.savepoint(fn),
  });
}

function restoreState(store, state) {
  for (const row of state.instanceBinding) store.bindInstance(row.instance_id, row.provider, row.endpoint_hint);
  for (const row of state.uploadSeq) store.markPublishedSeq(row.instance_id, row.device_id, BigInt(row.last_seq));
  for (const row of state.consumeCursor) store.setCursor(row.instance_id, row.uploader_device_id, BigInt(row.last_seq));
  for (const row of state.tombstoneWatermark) store.setTombstoneWatermark(row.instance_id, row.source_device_id, row.kind, BigInt(row.last_applied_offset));
  for (const row of state.tombstoneEventPublish) {
    const seq = store.reserveTombstoneEventSeqWithExisting(row.instance_id, row.device_id, row.kind, row.operation_id, 0n, BigInt(row.seq));
    assert.equal(seq, BigInt(row.seq), 'tombstone event sequence differs from the snapshot');
  }
  for (const row of state.legacyProcessedKey) store.markLegacyProcessed(row.instance_id, row.object_key);
  for (const row of state.deviceHistory) store.recordDeviceRotation(row.old_device_id, row.new_device_id, row.reason);
}

export async function replaySidecarOnDisk(sidecar, directory) {
  assertSidecarShape(sidecar);
  const path = `${directory}/sync_state.db`;
  const first = await openStore(path);
  try {
    first.db.transaction(tx => restoreState(transactionalStore(tx), sidecar.state), { behavior: 'immediate' });
  } finally { first.db.close(); }
  const second = await openStore(path);
  try {
    const state = canonicalState(readState(second.db, second.store));
    return { path, state, matches: JSON.stringify(state) === JSON.stringify(sidecar.state) };
  } finally { second.db.close(); }
}

export function seedStore(store) {
  store.bindInstance(FIXTURE_INSTANCE, 'local', 'file:///deep-student/local/sync');
  store.markPublishedSeq(FIXTURE_INSTANCE, FIXTURE_DEVICES.old, 5n);
  store.setCursor(FIXTURE_INSTANCE, FIXTURE_DEVICES.peer, 12n);
  store.setTombstoneWatermark(FIXTURE_INSTANCE, FIXTURE_DEVICES.peer, 'row', 7n);
  store.setTombstoneWatermark(FIXTURE_INSTANCE, FIXTURE_DEVICES.peer, 'blob', 3n);
  store.reserveTombstoneEventSeq(FIXTURE_INSTANCE, FIXTURE_DEVICES.old, 'blob', 'op-legacy-1', 3n);
  store.markLegacyProcessed(FIXTURE_INSTANCE, 'legacy/object/one');
}

export function readState(db, store) {
  return {
    instanceBinding: db.all('SELECT instance_id, provider, endpoint_hint, bound_at FROM instance_binding ORDER BY instance_id'),
    uploadSeq: db.all('SELECT instance_id, device_id, last_seq, updated_at FROM upload_seq ORDER BY device_id'),
    consumeCursor: db.all('SELECT instance_id, uploader_device_id, last_seq, updated_at FROM consume_cursor ORDER BY uploader_device_id'),
    tombstoneWatermark: db.all('SELECT instance_id, source_device_id, kind, last_applied_offset, updated_at FROM tombstone_watermark ORDER BY source_device_id, kind'),
    tombstoneEventPublish: db.all('SELECT instance_id, device_id, kind, operation_id, seq, created_at FROM tombstone_event_publish ORDER BY device_id, kind, seq'),
    legacyProcessedKey: db.all('SELECT instance_id, object_key, processed_at FROM legacy_processed_key ORDER BY object_key'),
    deviceHistory: db.all('SELECT old_device_id, new_device_id, rotated_at, reason FROM device_history ORDER BY old_device_id, new_device_id'),
    api: {
      publishedMax: store.publishedMaxSeq(FIXTURE_INSTANCE, FIXTURE_DEVICES.old).toString(),
      cursors: Object.fromEntries([...store.cursors(FIXTURE_INSTANCE)].map(([device, seq]) => [device, seq.toString()])),
      watermarkRow: store.getTombstoneWatermark(FIXTURE_INSTANCE, FIXTURE_DEVICES.peer, 'row').toString(),
      watermarkBlob: store.getTombstoneWatermark(FIXTURE_INSTANCE, FIXTURE_DEVICES.peer, 'blob').toString(),
      legacyProcessed: store.isLegacyProcessed(FIXTURE_INSTANCE, 'legacy/object/one'),
      rotationsForNew: store.deviceRotationsForNew(FIXTURE_DEVICES.next).map(([oldId, reason]) => ({ oldId, reason })),
    },
  };
}

export function canonicalState(state) {
  return Object.fromEntries(STATE_TABLES.map(table => [table, state[table].map(row => Object.fromEntries(
    Object.entries(row).filter(([key]) => !TIMESTAMP_FIELDS.has(key)).map(([key, value]) => [key, typeof value === 'bigint' ? value.toString() : value]),
  ))]));
}

export function applyStandardRestore(db, store, { oldDeviceId, newDeviceId, reason = 'backup_restore_activation' }) {
  const before = readState(db, store);
  store.recordDeviceRotation(oldDeviceId, newDeviceId, reason);
  const after = readState(db, store);
  const canonicalBefore = canonicalState(before);
  const canonicalAfter = canonicalState(after);
  const differences = Object.fromEntries(['deviceHistory', 'consumeCursor', 'legacyProcessedKey', 'tombstoneWatermark', 'uploadSeq', 'tombstoneEventPublish'].map(key => [key, { before: canonicalBefore[key], after: canonicalAfter[key] }]));
  return { before, after, canonicalBefore, canonicalAfter, differences };
}

function nonempty(value, label) {
  assert.ok(typeof value === 'string' && value.trim().length > 0, `${label}: expected a nonempty string`);
}

function sequence(value, label) {
  assert.ok((typeof value === 'number' && Number.isSafeInteger(value)) || (typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value)), `${label}: expected an exact decimal integer`);
  assert.ok(BigInt(value) >= 0n && BigInt(value) <= I64_MAX, `${label}: outside sync_state i64 range`);
}

export function assertSidecarShape(sidecar) {
  assert.equal(sidecar.format, CONTINUITY_FORMAT);
  assert.equal(sidecar.formatVersion, CONTINUITY_VERSION);
  assert.equal(sidecar.status, 'new-contract-in-this-unit');
  assert.match(sidecar.snapshot.zip_sha256, /^[0-9a-f]{64}$/);
  assert.ok(Number.isSafeInteger(sidecar.snapshot.zip_bytes) && sidecar.snapshot.zip_bytes > 0);
  for (const key of ['snapshot_epoch', 'backup_id', 'manifest_created_at', 'manifest_version', 'app_version']) nonempty(sidecar.snapshot[key], `snapshot.${key}`);
  for (const key of ['applicationId', 'versionName', 'installationId']) nonempty(sidecar.source_installation[key], `source_installation.${key}`);
  assert.ok(Number.isSafeInteger(sidecar.source_installation.versionCode) && sidecar.source_installation.versionCode > 0);
  assert.equal(sidecar.identity.mode, 'continuity');
  nonempty(sidecar.identity.instance_id, 'identity.instance_id');
  nonempty(sidecar.identity.device_id, 'identity.device_id');
  assert.equal(sidecar.source_installation.versionName, sidecar.snapshot.app_version);
  for (const table of STATE_TABLES) assert.ok(Array.isArray(sidecar.state[table]), `state.${table}: expected an array`);
  assert.equal(sidecar.state.instanceBinding.length, 1, 'exactly one repository binding is required');
  assert.equal(sidecar.state.uploadSeq.length, 1, 'exactly one writer sequence is required');
  assert.deepEqual(sidecar.state.deviceHistory, [], 'historical rotations stay outside the continuity snapshot');
  for (const table of STATE_TABLES.filter(table => table !== 'deviceHistory')) {
    for (const row of sidecar.state[table]) assert.equal(row.instance_id, sidecar.identity.instance_id, `${table}: repository instance mismatch`);
  }
  const binding = sidecar.state.instanceBinding[0];
  nonempty(binding.provider, 'binding.provider');
  nonempty(binding.endpoint_hint, 'binding.endpoint_hint');
  for (const row of sidecar.state.uploadSeq) {
    assert.equal(row.device_id, sidecar.identity.device_id, 'upload writer mismatch');
    sequence(row.last_seq, 'uploadSeq.last_seq');
  }
  for (const row of sidecar.state.consumeCursor) {
    nonempty(row.uploader_device_id, 'consumeCursor.uploader_device_id');
    sequence(row.last_seq, 'consumeCursor.last_seq');
  }
  for (const row of sidecar.state.tombstoneWatermark) {
    nonempty(row.source_device_id, 'tombstoneWatermark.source_device_id');
    nonempty(row.kind, 'tombstoneWatermark.kind');
    sequence(row.last_applied_offset, 'tombstoneWatermark.last_applied_offset');
  }
  for (const row of sidecar.state.tombstoneEventPublish) {
    assert.equal(row.device_id, sidecar.identity.device_id, 'tombstone writer mismatch');
    nonempty(row.kind, 'tombstoneEventPublish.kind');
    nonempty(row.operation_id, 'tombstoneEventPublish.operation_id');
    sequence(row.seq, 'tombstoneEventPublish.seq');
    assert.ok(BigInt(row.seq) > 0n, 'published tombstone event sequence must be positive');
  }
  for (const row of sidecar.state.legacyProcessedKey) nonempty(row.object_key, 'legacyProcessedKey.object_key');
}

export function takeoverContinuity(db, store, sidecar, zipSha256, expected) {
  try {
    assertSidecarShape(sidecar);
    assert.equal(expected.sourceWriterStopped, true, 'source writer must be stopped before takeover');
    assert.equal(sidecar.snapshot.zip_sha256, zipSha256, 'snapshot SHA-256 mismatch');
    assert.equal(sidecar.snapshot.zip_bytes, expected.zipBytes, 'snapshot size mismatch');
    const manifest = expected.manifest;
    for (const [field, value] of Object.entries({ backup_id: manifest.backup_id, snapshot_epoch: manifest.snapshot_epoch, manifest_created_at: manifest.created_at, manifest_version: manifest.version, app_version: manifest.app_version, key_policy: manifest.key_policy })) assert.equal(sidecar.snapshot[field], value, `snapshot.${field}: manifest mismatch`);
    assert.deepEqual(sidecar.snapshot.schema_versions, manifest.schema_versions, 'snapshot schema versions mismatch');
    for (const key of ['applicationId', 'versionName', 'versionCode', 'installationId']) assert.equal(sidecar.source_installation[key], expected.sourceInstallation[key], `source_installation.${key}: trusted source mismatch`);
    assert.equal(sidecar.identity.instance_id, expected.instanceId, 'trusted repository instance mismatch');
    assert.equal(sidecar.identity.device_id, expected.deviceId, 'trusted writer mismatch');
    return db.transaction(tx => {
      const scoped = transactionalStore(tx);
      const key = takeoverKey(sidecar);
      assert.equal(scoped.isLegacyProcessed(expected.instanceId, key), false, '同一快照已被接管过（legacy_processed_key 命中）');
      const before = canonicalState(readState(db, store));
      const empty = STATE_TABLES.every(table => before[table].length === 0);
      assert.ok(empty || JSON.stringify(before) === JSON.stringify(sidecar.state), 'destination state differs from the stopped source snapshot');
      restoreState(scoped, sidecar.state);
      scoped.markLegacyProcessed(expected.instanceId, key);
      return {
        accepted: true,
        reason: null,
        receipt: { snapshot_epoch: sidecar.snapshot.snapshot_epoch, zip_sha256: zipSha256, instance_id: sidecar.identity.instance_id, writer_device_id: sidecar.identity.device_id, takeover_key: key, state: canonicalState(readState(db, store)) },
      };
    }, { behavior: 'immediate' });
  } catch (error) {
    return { accepted: false, reason: error.message, receipt: null };
  }
}

export function expectedStateAfterTakeover(state, zipSha256, snapshotEpoch) {
  const canonical = canonicalState(state);
  const key = `continuity-takeover/${snapshotEpoch}/${zipSha256}`;
  const keys = [...canonical.legacyProcessedKey.map(row => row.object_key), key].sort();
  return { ...canonical, legacyProcessedKey: keys.map(object_key => ({ instance_id: FIXTURE_INSTANCE, object_key })) };
}

export function takeoverKey(sidecar) {
  return `continuity-takeover/${sidecar.snapshot.snapshot_epoch}/${sidecar.snapshot.zip_sha256}`;
}

export function buildSidecar({ manifest, zipSha256, zipBytes, sourceInstallation, generator, state, createdAt }) {
  const sidecar = {
    format: CONTINUITY_FORMAT,
    formatVersion: CONTINUITY_VERSION,
    status: 'new-contract-in-this-unit',
    note: '本轮新定义的平台无关导入合同；旧版移动端只产出 manifest.json，没有这个 sidecar。',
    created_at: createdAt,
    generator,
    snapshot: { backup_id: manifest.backup_id, snapshot_epoch: manifest.snapshot_epoch, manifest_created_at: manifest.created_at, zip_sha256: zipSha256, zip_bytes: zipBytes, manifest_version: manifest.version, app_version: manifest.app_version, schema_versions: manifest.schema_versions, key_policy: manifest.key_policy },
    source_installation: { ...sourceInstallation, installationId: FIXTURE_INSTALLATION },
    identity: { mode: 'continuity', instance_id: FIXTURE_INSTANCE, device_id: FIXTURE_DEVICES.old, reason: 'same-device-continuity' },
    state: canonicalState(state),
    expected: { state: expectedStateAfterTakeover(state, zipSha256, manifest.snapshot_epoch), duplicate_rejected: true },
    key_policy: { material_in_archive: false, manifest: manifest.key_policy, explanation: '移动端备份不包含密钥材料；installationId/deviceId 为公开 fixture 标识。' },
  };
  assertSidecarShape(sidecar);
  return sidecar;
}
