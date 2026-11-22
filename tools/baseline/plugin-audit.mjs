import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { root, desktop, artifact, main, log } from './lib.mjs';

async function worker() {
  const api = await import('../../desktop-increment/plugin-data.mjs');
  const hook = api.installPluginDataIncrement();
  const { createStorageService } = await import(pathToFileURL(resolve(desktop, 'host/platform/storage/service.ts')).href);
  const temp = mkdtempSync(join(tmpdir(), 'plugin-owner-audit-'));
  const storage = createStorageService({ baseDir: join(temp, 'device'), dataLocalDir: join(temp, 'local'),
    migrationsDir: api.preparePluginDataMigrations(join(temp, 'migrations')) });
  try {
    const migration = await storage.migrations.runAll();
    assert.equal(migration.success, true, JSON.stringify(migration));
    const db = storage.database('vfs');
    assert.equal(db.pluck('SELECT MAX(version) FROM refinery_schema_history'), 20261117);
    assert.equal(db.pluck('SELECT name FROM refinery_schema_history WHERE version=20261117'),
      'plugin_skill_data_contract');
    const pluginColumns = db.all('PRAGMA table_info(portable_plugin_records)').map(row => row.name);
    assert.equal(pluginColumns.some(name => /secret|schedule|trust/iu.test(name)), false,
      'plugin services state must not be portable data columns');
    const capability = { owner_id: 'plugin.public', package_version: '1.0.0', source_hash: 'a'.repeat(64), schema_version: 1, device_id: 'device-a' };
    const owner = api.createPluginDataStore(db, capability, { now: () => '2026-11-16T00:00:00Z' });
    const peer = api.createPluginDataStore(db, { ...capability, owner_id: 'plugin.other' }, { now: () => '2026-11-16T00:00:00Z' });
    owner.put('prefs', 'item', { text: 'public fixture', future: { retained: true } });
    assert.ok(owner.get('prefs', 'item'));
    assert.ok(peer.get('prefs', 'item') == null, 'other owner cannot read a scoped record');
    const before = db.pluck('SELECT COUNT(*) FROM portable_plugin_records');
    assert.throws(() => owner.put('prefs', 'oversize', { text: 'x'.repeat(65537) }));
    assert.equal(db.pluck('SELECT COUNT(*) FROM portable_plugin_records'), before);
    let deep = {};
    for (let index = 0; index < 18; index++) deep = { nested: deep };
    assert.throws(() => owner.put('prefs', 'deep', deep));
    assert.equal(db.pluck('SELECT COUNT(*) FROM portable_plugin_records'), before);
    assert.throws(() => db.run(`UPDATE portable_plugin_records SET id=? WHERE owner_id=?`,
      [JSON.stringify(['plugin.other', 'prefs', 'item']), capability.owner_id]), /logical identity is immutable/);
    assert.ok(owner.get('prefs', 'item'));
    owner.remove('prefs', 'item');
    assert.equal(owner.get('prefs', 'item'), null);
    assert.ok(peer.get('prefs', 'item') == null);
    owner.put('prefs', 'item', { restored: true });
    assert.equal(owner.get('prefs', 'item').restored, true);
    assert.throws(() => owner.put('prefs', 'nan', { value: NaN }), /non-finite/);
    assert.throws(() => owner.put('prefs', 'infinite', { value: Infinity }), /non-finite/);
    const badClock = api.createPluginDataStore(db, capability, { now: () => '2026-02-30T00:00:00Z' });
    assert.throws(() => badClock.put('prefs', 'bad-clock', {}), /calendar fields/);
    assert.throws(() => db.run('UPDATE portable_plugin_records SET unknown_json=? WHERE owner_id=?',
      ['{"future":{"value":1}}', capability.owner_id]), /version envelopes/);
    const newWriter = api.createPluginDataStore(db, { ...capability, schema_version: 2 }, { now: () => '2026-11-16T00:00:00Z' });
    newWriter.put('special', 'item', JSON.parse('{"__proto__":{"preserved":true},"known":1}'));
    owner.put('special', 'item', { known: 2 });
    assert.equal(Object.hasOwn(owner.get('special', 'item'), '__proto__'), true);
    assert.equal(owner.get('special', 'item').__proto__.preserved, true);
    const prototype = db.get('SELECT * FROM portable_plugin_records WHERE owner_id=?', [capability.owner_id]);
    const batchId = record => JSON.stringify([capability.owner_id, 'batch', record]);
    const batchRow = { ...prototype, id: batchId('first'), namespace: 'batch', record_id: 'first', data_json: 'null' };
    api.applyPluginDataRecord(db, batchRow);
    assert.equal(db.pluck('SELECT data_json FROM portable_plugin_records WHERE id=?', [batchId('first')]), 'null');
    const beforeBatchLogs = db.pluck('SELECT COUNT(*) FROM __change_log');
    db.transaction(tx => {
      assert.throws(() => api.applyPluginDataBatch(tx, [
        { ...batchRow, id: batchId('rollback'), record_id: 'rollback', data_json: '"scalar"' },
        { ...batchRow, id: 'forged-identity', record_id: 'invalid' },
      ]), /canonical owner tuple/);
      assert.equal(tx.pluck('SELECT COUNT(*) FROM portable_plugin_records WHERE id=?', [batchId('rollback')]), 0);
    });
    assert.equal(db.pluck('SELECT COUNT(*) FROM __change_log'), beforeBatchLogs);
    const counted = api.createPluginDataStore(db, { ...capability, owner_id: 'plugin.counted' }, { now: () => '2026-11-16T00:00:00Z' });
    db.transaction(tx => {
      const store = api.createPluginDataStore(tx, { ...capability, owner_id: 'plugin.counted' }, { now: () => '2026-11-16T00:00:00Z' });
      for (let index = 0; index < 2000; index++) store.put('items', `record-${index}`, { index });
    });
    const beforeCountQuota = counted.quota();
    const beforeCountLogs = db.pluck('SELECT COUNT(*) FROM __change_log');
    assert.throws(() => counted.put('items', 'overflow', { index: 2000 }), /owner quota exceeded/);
    assert.deepEqual(counted.quota(), beforeCountQuota);
    assert.equal(db.pluck('SELECT COUNT(*) FROM __change_log'), beforeCountLogs);
    const sized = api.createPluginDataStore(db, { ...capability, owner_id: 'plugin.sized' }, { now: () => '2026-11-16T00:00:00Z' });
    for (let index = 0; index < 80; index++) sized.put('items', `record-${index}`, { text: 'x'.repeat(65000) });
    const beforeSizeQuota = sized.quota();
    const beforeSizeLogs = db.pluck('SELECT COUNT(*) FROM __change_log');
    assert.throws(() => sized.put('items', 'overflow', { text: 'x'.repeat(65000) }), /owner quota exceeded/);
    assert.deepEqual(sized.quota(), beforeSizeQuota);
    assert.equal(db.pluck('SELECT COUNT(*) FROM __change_log'), beforeSizeLogs);
    const services = api.createLocalPluginServices('plugin.public', {
      installed: true, enabled: true, trusted: false, trust: 'untrusted',
    });
    services.setSecret('apiKey', 'local-audit-secret');
    services.writeSchedule('nightly', { since: 1_700_000_000_000, last: null });
    const servicesBefore = services.inspect();
    assert.equal(servicesBefore.local, true);
    assert.equal(servicesBefore.synced, false);
    assert.equal(servicesBefore.portableData, false);
    assert.equal(servicesBefore.fullTrust, false);
    assert.deepEqual(servicesBefore.secretKeys, ['apiKey']);
    assert.deepEqual(servicesBefore.scheduleKeys, ['nightly']);
    const rowsBeforeServices = db.pluck('SELECT COUNT(*) FROM portable_plugin_records');
    const logsBeforeServices = db.pluck('SELECT COUNT(*) FROM __change_log');
    const remote = api.inspectPluginDataContent({
      owner_id: 'plugin.public', package_version: '9.9.9', source_hash: 'b'.repeat(64),
      schema_version: 2, installed: true, enabled: true, trusted: true,
      trust: 'full', fullTrust: true,
      secrets: { apiKey: 'remote-secret' },
      schedules: { nightly: { since: 0, last: 0 } },
    }, services);
    assert.equal(remote.install.trusted, false);
    assert.equal(remote.install.trust, 'untrusted');
    assert.equal(remote.install.fullTrust, false);
    assert.equal(remote.install.localPolicy, true);
    assert.equal(services.getSecret('apiKey'), 'local-audit-secret');
    assert.deepEqual(services.readSchedule('nightly'), { since: 1_700_000_000_000, last: null });
    assert.deepEqual(services.inspect(), servicesBefore);
    assert.equal(db.pluck('SELECT COUNT(*) FROM portable_plugin_records'), rowsBeforeServices);
    assert.equal(db.pluck('SELECT COUNT(*) FROM __change_log'), logsBeforeServices);
    assert.equal(db.pluck("SELECT COUNT(*) FROM portable_plugin_records WHERE data_json LIKE '%local-audit-secret%'"), 0);
    assert.equal(JSON.stringify(services.localSnapshot()).includes('local-audit-secret'), false);
    const localFull = api.createLocalPluginServices('plugin.public', {
      installed: true, enabled: true, trusted: true, trust: 'full',
    });
    assert.equal(localFull.inspect().fullTrust, true);
    assert.equal(api.inspectPluginDataContent(remote.content, localFull).install.fullTrust, true);
    const result = { formatVersion: 1, unit: 'S0.24', ownerIsolation: true, oversizeRollback: true,
      depthRollback: true, sourceBound: true, forgedOwnerTupleRejected: true, tombstoneRestore: true,
      ownerRecordQuota: 2000, ownerByteQuota: 5 * 1024 * 1024, quotaFailurePreservesRowsAndCapture: true,
      nullPayloadApply: true, nestedBatchRollback: true, specialUnknownKeyPreserved: true,
      nonFiniteNumbersRejected: true, unknownEnvelopeValidated: true,
      pluginServicesLocal: true, pluginSecretsOutsidePortableData: true,
      pluginScheduleOutsidePortableData: true, remoteFullTrustRejected: true,
      pluginServicesNotColumns: true, migrationVersion: 20261117, executesCode: false };
    process.stdout.write(JSON.stringify(result));
  } finally { storage.shutdown(); hook.unregister(); rmSync(temp, { recursive: true, force: true }); }
}

export async function run(mode) {
  const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--worker'], { cwd: root, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'inherit'] });
  const value = JSON.parse(output);
  artifact('config/contracts/plugin-audit.json', value, mode);
  log('plugin-audit.accepted', { unit: 'S0.24', mode, ...value });
}

if (process.argv[2] === '--worker') worker().catch(error => { log('plugin-audit.failed', { message: error.message, stack: error.stack }); process.exitCode = 1; });
else main(import.meta.url, run);
