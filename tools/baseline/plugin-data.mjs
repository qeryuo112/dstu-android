import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { artifact, desktop, log, main, root, sha256 } from './lib.mjs';

const UNIT = 'S0.24';
const CONTRACT = 'config/contracts/plugin-data.json';
const FIXTURE = 'fixtures/plugin-data.json';
const FIXED_TIME = '2026-11-15T16:00:00Z';
const LATER_TIME = '2026-11-15T16:01:00Z';
const SOURCE_A = 'a'.repeat(64);
const SOURCE_B = 'b'.repeat(64);
const PLUGIN_SQL = 'config/schema/coordinated/vfs/V20261117__plugin_skill_data_contract.sql';
const PREVIOUS_PLUGIN_SQL = 'vfs/V20261116__plugin_skill_data_contract.sql';
const MEDIA_SQL = 'vfs/V20261116__portable_media_progress.sql';
const TEMPLATE_SQL = 'mistakes/V20261006__portable_anki_templates.sql';
const SOURCE_FILES = [
  'host/plugins/sync/src/classification-registry.ts',
  'host/platform/storage/migration/definitions/vfs.ts',
  'host/plugins/sync/src/collect-sync-changes.ts',
  'host/plugins/sync/src/change-payload.ts',
  'host/plugins/sync/repository/apply-databases.ts',
  'host/plugins/sync/repository/apply-record.ts',
  'host/plugins/plugin-services/api.ts',
  'host/plugins/plugin-services/src/secrets.ts',
  'host/plugins/plugin-services/src/schedule.ts',
  'host/platform/secrets/sensitive-key.ts',
];
const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const plain = value => JSON.parse(JSON.stringify(value, (_key, item) =>
  typeof item === 'bigint' ? { $bigint: item.toString() } : item));

function errorOf(fn) {
  try { fn(); return null; } catch (error) { return error instanceof Error ? error.message : String(error); }
}

async function worker() {
  const api = await import('../../desktop-increment/plugin-data.mjs');
  const hook = api.installPluginDataIncrement();
  const { setLocalRuntime } = await load('host/plugins/sync/src/local-runtime.ts');
  const { nodeLocalRuntime } = await load('host/plugins/sync/src/local-runtime.node.ts');
  setLocalRuntime(nodeLocalRuntime);
  const { createStorageService } = await load('host/platform/storage/service.ts');
  const { collectSyncChanges } = await load('host/plugins/sync/src/collect-sync-changes.ts');
  const { serializeSyncChange, parseSyncChange } = await load('host/plugins/sync/src/change-payload.ts');
  const { applyDownloadedChangesToDatabases } = await load('host/plugins/sync/repository/apply-databases.ts');
  const { rowSyncTables, syncClassificationRegistry } = await load('host/plugins/sync/src/classification.ts');
  const { validateSyncRegistryDrift } = await load('host/plugins/sync/repository/registry-preflight.ts');
  const { Connection } = await load('host/platform/storage/connection.ts');
  const { loadSqlMigrations, Runner } = await load('host/platform/storage/migration/refinery.ts');
  const temp = mkdtempSync(join(tmpdir(), 'dstu-s024-plugin-data-'));
  const stores = [];
  const freshReports = {};
  const logger = { info() {}, debug() {}, warn() {}, error() {} };
  try {
    const migrationsDir = api.preparePluginDataMigrations(join(temp, 'migrations'));
    const preparedVfs = readdirSync(join(migrationsDir, 'vfs'))
      .filter(file => file.endsWith('.sql')).sort();
    assert.deepEqual(preparedVfs.slice(-3), [
      'V20261115__review_plans_fsrs.sql', MEDIA_SQL.split('/').at(-1),
      PLUGIN_SQL.split('/').at(-1),
    ], 'S0.24: prepared coordinated vfs SQL order');
    assert.equal(preparedVfs.includes(PREVIOUS_PLUGIN_SQL.split('/').at(-1)), false,
      'S0.24: stale same-version plugin SQL was copied');
    assert.equal(preparedVfs.includes('V20261114__portable_media_progress.sql'), false,
      'S0.24: stale media SQL was copied');
    assert.equal(readdirSync(join(migrationsDir, 'mistakes')).includes(TEMPLATE_SQL.split('/').at(-1)), true,
      'S0.24: template SQL missing from prepared directory');
    for (const input of api.PLUGIN_DATA_MIGRATION_INPUTS) {
      const prepared = resolve(migrationsDir, input.file);
      assert.equal(sha256(readFileSync(prepared)), input.sha256,
        `S0.24: prepared coordinated SQL drift ${input.file}`);
    }
    const preparedVfsMigrations = loadSqlMigrations(join(migrationsDir, 'vfs'));
    const open = async name => {
      const storage = createStorageService({ baseDir: join(temp, name),
        dataLocalDir: join(temp, `${name}-local`), migrationsDir });
      stores.push(storage);
      const report = await storage.migrations.runAll();
      assert.equal(report.success, true,
        `${name}: migration ${JSON.stringify(report.databases)}`);
      const freshVfs = report.databases.find(row => row.id === 'vfs');
      assert.deepEqual(plain({ fromVersion: freshVfs.fromVersion, toVersion: freshVfs.toVersion,
        appliedCount: freshVfs.appliedCount }),
      { fromVersion: 0, toVersion: 20261117, appliedCount: preparedVfsMigrations.length },
      `${name}: fresh vfs head`);
      const restart = await storage.migrations.runAll();
      assert.equal(restart.success, true, `${name}: restart migration`);
      const restartVfs = restart.databases.find(row => row.id === 'vfs');
      assert.deepEqual(plain({ fromVersion: restartVfs.fromVersion, toVersion: restartVfs.toVersion,
        appliedCount: restartVfs.appliedCount }),
      { fromVersion: 20261117, toVersion: 20261117, appliedCount: 0 },
      `${name}: restart zero migrations`);
      freshReports[name] = {
        freshVfs: plain({ fromVersion: freshVfs.fromVersion, toVersion: freshVfs.toVersion,
          appliedCount: freshVfs.appliedCount, success: freshVfs.success }),
        restartVfs: plain({ fromVersion: restartVfs.fromVersion, toVersion: restartVfs.toVersion,
          appliedCount: restartVfs.appliedCount, success: restartVfs.success }),
      };
      return storage;
    };
    const a = await open('device-a');
    const b = await open('device-b');
    const aDb = a.database('vfs');
    const bDb = b.database('vfs');
    const capabilityA = { owner_id: 'plugin.public', package_version: '1.0.0',
      source_hash: SOURCE_A, schema_version: 2, device_id: 'device-a' };
    const capabilityB = { ...capabilityA, package_version: '1.0.1',
      source_hash: SOURCE_B, device_id: 'device-b' };
    const aStore = api.createPluginDataStore(aDb, capabilityA, { now: () => FIXED_TIME });
    const bStore = api.createPluginDataStore(bDb, capabilityB, { now: () => FIXED_TIME });
    const skillA = api.createPluginDataStore(aDb, { ...capabilityA,
      owner_id: 'skill.public', schema_version: 1 }, { now: () => FIXED_TIME });
    const skillB = api.createPluginDataStore(bDb, { ...capabilityB,
      owner_id: 'skill.public', schema_version: 1 }, { now: () => FIXED_TIME });
    const otherA = api.createPluginDataStore(aDb, { ...capabilityA,
      owner_id: 'plugin.other' }, { now: () => FIXED_TIME });

    const upstreamVfs = loadSqlMigrations(resolve(desktop, 'resources/app/migrations/vfs'));
    const preparedMistakes = loadSqlMigrations(join(migrationsDir, 'mistakes'));
    const baselineVfs = new Connection(join(temp, 'upstream-20261115', 'databases', 'vfs.db'),
      { label: 's024-upstream-vfs', profile: { foreignKeys: true } });
    const baselineMistakes = new Connection(join(temp, 'upstream-20261115', 'databases', 'mistakes.db'),
      { label: 's024-upstream-mistakes', profile: { foreignKeys: true } });
    let upgradeReport;
    try {
      const baselineApplied = new Runner(upstreamVfs, { grouped: false, abortDivergent: false,
        abortMissing: false }).run(baselineVfs);
      assert.equal(baselineApplied.length, upstreamVfs.length);
      assert.equal(baselineVfs.pluck('SELECT MAX(version) FROM refinery_schema_history'), 20261115);
      const mediaUpgrade = new Runner(preparedVfsMigrations.filter(migration =>
        migration.version <= 20261116), { grouped: false, abortDivergent: false,
        abortMissing: false }).run(baselineVfs);
      assert.deepEqual(mediaUpgrade.map(migration => [migration.version, migration.name]),
        [[20261116, 'portable_media_progress']]);
      assert.equal(baselineVfs.pluck('SELECT MAX(version) FROM refinery_schema_history'), 20261116);
      const pluginUpgrade = new Runner(preparedVfsMigrations, { grouped: false,
        abortDivergent: false, abortMissing: false }).run(baselineVfs);
      assert.deepEqual(pluginUpgrade.map(migration => [migration.version, migration.name]),
        [[20261117, 'plugin_skill_data_contract']]);
      assert.deepEqual(plain(baselineVfs.get(`SELECT version, name FROM refinery_schema_history
        ORDER BY version DESC LIMIT 1`)),
      { version: 20261117, name: 'plugin_skill_data_contract' });
      assert.equal(new Runner(preparedVfsMigrations).run(baselineVfs).length, 0);

      const upstreamMistakes = loadSqlMigrations(resolve(desktop, 'resources/app/migrations/mistakes'));
      assert.equal(new Runner(upstreamMistakes, { grouped: false, abortDivergent: false,
        abortMissing: false }).run(baselineMistakes).length, upstreamMistakes.length);
      assert.equal(baselineMistakes.pluck('SELECT MAX(version) FROM refinery_schema_history'), 20261005);
      const templateUpgrade = new Runner(preparedMistakes, { grouped: false,
        abortDivergent: false, abortMissing: false }).run(baselineMistakes);
      assert.deepEqual(templateUpgrade.map(migration => [migration.version, migration.name]),
        [[20261006, 'portable_anki_templates']]);
      assert.equal(new Runner(preparedMistakes).run(baselineMistakes).length, 0);
      upgradeReport = {
        vfs: {
          fromVersion: 20261115,
          mediaVersion: 20261116,
          mediaName: 'portable_media_progress',
          pluginVersion: 20261117,
          pluginName: 'plugin_skill_data_contract',
          restartApplied: 0,
          migrationRow: plain(baselineVfs.get(`SELECT version, name FROM refinery_schema_history
            WHERE version = 20261117`)),
        },
        mistakes: {
          fromVersion: 20261005,
          templateVersion: 20261006,
          templateName: 'portable_anki_templates',
          restartApplied: 0,
        },
      };
    } finally {
      baselineVfs.close();
      baselineMistakes.close();
    }

    const classification = syncClassificationRegistry()
      .find(row => row.table_name === 'portable_plugin_records');
    assert.deepEqual(plain(classification), {
      database: 'vfs', table_name: 'portable_plugin_records', primary_key: 'id',
      category: 'RowSync', conflict_policy: 'Lww', business_unique_keys: '',
      has_json_blobs: true,
      merge_notes: 'S0.24: portable plugin/skill data by canonical [owner,namespace,recordId] id; row LWW; install and trust stay local',
    });
    assert.equal(rowSyncTables().filter(row => row.table_name === 'portable_plugin_records').length, 1);
    assert.equal(syncClassificationRegistry().find(row => row.table_name === 'custom_anki_templates').category, 'RowSync');
    assert.equal(syncClassificationRegistry().find(row => row.table_name === 'portable_media_events').category, 'RowSync');
    validateSyncRegistryDrift(a);
    validateSyncRegistryDrift(b);
    assert.equal(aDb.pluck('SELECT MAX(version) FROM refinery_schema_history'), 20261117);
    assert.equal(aDb.pluck("SELECT name FROM refinery_schema_history WHERE version=20261117"),
      'plugin_skill_data_contract');

    aStore.put('prefs', 'item', {
      future: { n: 9007199254740993n, nullable: null }, text: 'device-a', nested: ['中', true],
    });
    aStore.put('prefs', 'item', {
      future: { n: 9007199254740993n, nullable: null }, text: 'device-a', nested: ['中', true],
    }, { minWriterSchema: 2 });
    aStore.put('prefs', 'compat', { future: { n: 9007199254740993n }, text: 'schema-two' });
    skillA.put('defs', 'item', { command: 'not executed', text: 'skill data' });
    otherA.put('prefs', 'item', { text: 'other owner' });
    assert.deepEqual(aStore.get('prefs', 'item'), {
      future: { n: 9007199254740993n, nullable: null }, text: 'device-a', nested: ['中', true],
    });
    assert.equal(otherA.get('prefs', 'item').text, 'other owner');
    assert.equal(skillA.get('prefs', 'item'), null);
    const token = { ...capabilityA };
    const scoped = api.createPluginDataStore(aDb, token, { now: () => FIXED_TIME });
    token.owner_id = 'plugin.forged';
    scoped.put('prefs', 'bound', { owner_id: 'plugin.forged', text: 'bound capability' });
    assert.equal(scoped.get('prefs', 'bound').owner_id, 'plugin.forged');
    assert.equal(api.createPluginDataStore(aDb, { ...capabilityA, owner_id: 'plugin.forged' },
      { now: () => FIXED_TIME }).get('prefs', 'bound'), null);

    const collected = await collectSyncChanges(a, null);
    const pluginChanges = collected.filter(change => change.table_name === 'portable_plugin_records');
    assert.equal(pluginChanges.length, 6);
    const serialized = pluginChanges.map(serializeSyncChange);
    const parsed = serialized.map(parseSyncChange);
    const upload = applyDownloadedChangesToDatabases(b, parsed, 'UseCloud', 'device-b', undefined, logger);
    if (upload.total_failed !== 0) {
      const quarantine = bDb.all('SELECT * FROM __sync_quarantine');
      throw new Error(`plugin data apply failed: ${JSON.stringify({ errors: upload.db_errors,
        failures: upload, quarantine })}`);
    }
    assert.deepEqual(bStore.get('prefs', 'item'), aStore.get('prefs', 'item'));
    assert.equal(bStore.read('prefs', 'item').package_version, '1.0.0');
    assert.equal(bStore.read('prefs', 'item').source_hash, SOURCE_A);
    assert.equal(skillB.get('defs', 'item').text, 'skill data');
    assert.deepEqual(bStore.get('prefs', 'compat').future, { n: 9007199254740993n });
    assert.equal(api.createPluginDataStore(bDb, { ...capabilityB, owner_id: 'plugin.other' },
      { now: () => FIXED_TIME }).get('prefs', 'item').text, 'other owner');
    assert.equal(bDb.pluck(`SELECT COUNT(*) FROM __change_log
      WHERE table_name='portable_plugin_records' AND sync_version=0`), 0);

    bStore.put('prefs', 'item', { future: { n: 9007199254740993n, nullable: null },
      text: 'device-b edit', nested: ['中', true] });
    const bChanges = (await collectSyncChanges(b, null))
      .filter(change => change.table_name === 'portable_plugin_records');
    const aApply = applyDownloadedChangesToDatabases(a,
      bChanges.map(serializeSyncChange).map(parseSyncChange),
      'UseCloud', 'device-a', undefined, logger);
    assert.equal(aApply.total_failed, 0);
    assert.equal(aStore.get('prefs', 'item').text, 'device-b edit');
    assert.equal(aStore.read('prefs', 'item').package_version, '1.0.1');

    aStore.put('prefs', 'item', { text: 'schema-one write' });
    const down = errorOf(() => api.createPluginDataStore(aDb, { ...capabilityA, schema_version: 1 },
      { now: () => LATER_TIME }).put('prefs', 'item', { text: 'too old' }));
    assert.match(down, /MIN_WRITER_DOWNGRADE/);
    api.createPluginDataStore(aDb, { ...capabilityA, schema_version: 1 },
      { now: () => LATER_TIME }).put('prefs', 'compat', { text: 'schema-one compat' });
    assert.deepEqual(aStore.get('prefs', 'compat'), {
      future: { n: 9007199254740993n }, text: 'schema-one compat',
    });
    assert.ok(aStore.read('prefs', 'compat').unknown_json.includes('future'));

    const sourceChanged = errorOf(() => api.createPluginDataStore(aDb, { ...capabilityA,
      source_hash: SOURCE_B, schema_version: 2 }, { now: () => LATER_TIME,
      sourceAuthority: hash => hash === SOURCE_A }).put('prefs', 'new', { text: 'x' }));
    assert.match(sourceChanged, /source_hash/);

    const deep = [];
    let cursor = deep;
    for (let index = 0; index < 17; index++) { const next = []; cursor.push(next); cursor = next; }
    const beforeQuota = aStore.quota();
    const tooDeep = errorOf(() => aStore.put('prefs', 'deep', deep));
    assert.match(tooDeep, /DEPTH_EXCEEDED/);
    const tooLarge = errorOf(() => aStore.put('prefs', 'large', 'x'.repeat(65_535)));
    assert.match(tooLarge, /RECORD_TOO_LARGE/);
    assert.deepEqual(plain(aStore.quota()), plain(beforeQuota));

    const forgedId = JSON.stringify(['plugin.other', 'prefs', 'forged']);
    const forged = errorOf(() => aDb.run(`INSERT INTO portable_plugin_records
      (id, owner_id, owner_kind, namespace, record_id, package_version, source_hash,
       schema_version, min_writer_schema, data_json, unknown_json, deleted_at,
       device_id, local_version, updated_at)
      VALUES (?1, 'plugin.public', 'plugin', 'prefs', 'forged', '1.0.0', ?2,
       1, 1, '{}', '{}', NULL, 'device-a', 1, ?3)`, [forgedId, SOURCE_A, FIXED_TIME]));
    assert.match(forged, /CHECK|constraint/i);
    let deepVector = { leaf: true };
    for (let index = 0; index < 17; index++) deepVector = { nested: deepVector };
    const forgedDepth = errorOf(() => aDb.run(`INSERT INTO portable_plugin_records
      (id, owner_id, owner_kind, namespace, record_id, package_version, source_hash,
       schema_version, min_writer_schema, data_json, unknown_json, deleted_at,
       device_id, local_version, updated_at)
      VALUES (?1, 'plugin.public', 'plugin', 'prefs', 'depth', '1.0.0', ?2,
       1, 1, ?3, '{}', NULL, 'device-a', 1, ?4)`,
    [JSON.stringify(['plugin.public', 'prefs', 'depth']), SOURCE_A,
      JSON.stringify(deepVector),
      FIXED_TIME]));
    assert.match(forgedDepth, /depth exceeds/i);

    const policy = api.createPluginDataPolicy('plugin.public', { installed: true,
      enabled: true, trusted: true, trust: 'full' });
    const uninstall = api.uninstallPluginData(bStore, policy, { authorized: true });
    assert.equal(uninstall.installState.installed, false);
    assert.equal(uninstall.retainedSyncedData, true);
    assert.equal(bStore.get('prefs', 'item').text, 'device-b edit');
    const unauthorized = errorOf(() => api.uninstallPluginData(bStore, policy, {}));
    assert.match(unauthorized, /LOCAL_AUTH_REQUIRED/);
    const deleted = api.uninstallPluginData(bStore, policy, { authorized: true, deleteData: true });
    assert.equal(deleted.dataTombstones.deleted >= 1, true);
    assert.equal(bStore.get('prefs', 'item'), null);
    const tombstone = bDb.get("SELECT deleted_at, unknown_json FROM portable_plugin_records WHERE id=?",
      [JSON.stringify(['plugin.public', 'prefs', 'item'])]);
    assert.ok(tombstone.deleted_at);
    assert.equal(tombstone.unknown_json, '{}');

    const services = api.createLocalPluginServices('plugin.public', {
      installed: true, enabled: true, trusted: false, trust: 'untrusted',
    });
    assert.equal(services.getSecret('apiKey'), null);
    services.setSecret('apiKey', 'local-only-secret');
    services.writeSchedule('nightly', { since: Date.parse(FIXED_TIME), last: null });
    const localServices = services.inspect();
    assert.equal(localServices.local, true);
    assert.equal(localServices.synced, false);
    assert.equal(localServices.portableData, false);
    assert.deepEqual(localServices.secretKeys, ['apiKey']);
    assert.deepEqual(localServices.scheduleKeys, ['nightly']);
    assert.equal(localServices.fullTrust, false);
    assert.equal(services.localSnapshot().secretKeys.includes('apiKey'), true);
    assert.equal(JSON.stringify(services.localSnapshot()).includes('local-only-secret'), false);
    const portableBeforeServices = aDb.pluck('SELECT COUNT(*) FROM portable_plugin_records');
    const logBeforeServices = aDb.pluck('SELECT COUNT(*) FROM __change_log');
    const remoteTrust = api.inspectPluginDataContent({
      owner_id: 'plugin.public', package_version: '9.9.9', source_hash: SOURCE_B,
      schema_version: 2, installed: true, enabled: true, trusted: true,
      trust: 'full', fullTrust: true,
      secrets: { apiKey: 'remote-secret' },
      schedule: [{ id: 'nightly', since: 0, last: 0 }],
    }, services);
    assert.equal(remoteTrust.install.trusted, false);
    assert.equal(remoteTrust.install.trust, 'untrusted');
    assert.equal(remoteTrust.install.fullTrust, false);
    assert.equal(remoteTrust.install.localPolicy, true);
    assert.equal(services.getSecret('apiKey'), 'local-only-secret');
    assert.deepEqual(services.readSchedule('nightly'), {
      since: Date.parse(FIXED_TIME), last: null,
    });
    assert.equal(aDb.pluck('SELECT COUNT(*) FROM portable_plugin_records'), portableBeforeServices);
    assert.equal(aDb.pluck('SELECT COUNT(*) FROM __change_log'), logBeforeServices);
    assert.equal(aDb.pluck(`SELECT COUNT(*) FROM portable_plugin_records
      WHERE data_json LIKE '%local-only-secret%'`), 0);
    const localFull = api.createLocalPluginServices('plugin.public', {
      installed: true, enabled: true, trusted: true, trust: 'full',
    });
    assert.equal(localFull.inspect().fullTrust, true);
    assert.equal(api.inspectPluginDataContent(remoteTrust.content, localFull).install.fullTrust, true);

    const remoteContent = api.inspectPluginDataContent({
      owner_id: 'plugin.public', package_version: '9.9.9', source_hash: SOURCE_B,
      schema_version: 2, installed: true, enabled: true, trusted: true,
      execute: 'globalThis.__pluginExecuted = true',
    }, policy.inspect());
    assert.equal(remoteContent.install.installed, false);
    assert.equal(remoteContent.install.trusted, false);
    assert.equal(remoteContent.install.trust, 'untrusted');
    assert.equal(remoteContent.executes, false);

    const tombstoneChanges = (await collectSyncChanges(b, null))
      .filter(change => change.table_name === 'portable_plugin_records');
    const replay = applyDownloadedChangesToDatabases(a,
      tombstoneChanges.map(serializeSyncChange).map(parseSyncChange),
      'UseCloud', 'device-a', undefined, logger);
    assert.equal(replay.total_failed, 0);
    assert.equal(aStore.get('prefs', 'item'), null);
    const tombstoneA = aDb.get(`SELECT deleted_at, unknown_json FROM portable_plugin_records
      WHERE id=?`, [JSON.stringify(['plugin.public', 'prefs', 'item'])]);
    assert.ok(tombstoneA.deleted_at);

    const batchRows = aDb.all(`SELECT id, owner_id, owner_kind, namespace, record_id,
      package_version, source_hash, schema_version, min_writer_schema, data_json,
      unknown_json, deleted_at, device_id, local_version, updated_at
      FROM portable_plugin_records ORDER BY id`);
    assert.ok(batchRows.length >= 2);
    assert.doesNotThrow(() => api.applyPluginDataBatch(aDb, batchRows));
    assert.doesNotThrow(() => api.applyPluginDataBatch(aDb, [...batchRows].reverse()));
    assert.equal(aStore.get('prefs', 'item'), null);
    assert.equal(aStore.read('prefs', 'item'), null);
    assert.equal(aStore.get('prefs', 'bound'), null);
    assert.equal(aStore.get('prefs', 'compat'), null);
    const floorRow = batchRows.find(row => row.owner_id === 'plugin.public'
      && row.namespace === 'prefs' && row.record_id === 'item');
    assert.ok(floorRow);
    assert.equal(floorRow.min_writer_schema, 2);
    if (Number(floorRow.min_writer_schema) > 1) {
      const floor = errorOf(() => aDb.run(`UPDATE portable_plugin_records
        SET min_writer_schema=1 WHERE id=?`, [floorRow.id]));
      assert.match(floor, /min_writer_schema cannot decrease/);
    }

    const sourceHashes = Object.fromEntries(SOURCE_FILES.map(file =>
      [file, sha256(readFileSync(resolve(desktop, file)))]));
    const snapshot = aDb.all(`SELECT id, owner_id, owner_kind, namespace, record_id,
      package_version, source_hash, schema_version, min_writer_schema, data_json,
      unknown_json, deleted_at, device_id, local_version, updated_at
      FROM portable_plugin_records ORDER BY id`).map(row => {
        const owner = api.createPluginDataStore(aDb, { ...capabilityA,
          owner_id: row.owner_id, source_hash: row.source_hash,
          schema_version: row.schema_version, device_id: row.device_id },
        { now: () => FIXED_TIME });
        const live = row.deleted_at === null ? owner.get(row.namespace, row.record_id) : null;
        return { ...row, decoded_live: live };
      });
    const fixture = {
      formatVersion: 1,
      unit: UNIT,
      capability: capabilityA,
      records: snapshot,
      unknownRoundTrip: { retained: { n: 9007199254740993n, nullable: null } },
      trust: { install: 'local-only', trust: 'local-only', fullTrust: 'local-only',
        remoteTrustMarker: false, remoteFullTrustAccepted: false },
      pluginServices: {
        localOnly: true,
        portableData: false,
        secretKeyPattern: 'secret.plugin.<owner>.<key>',
        scheduleKeyPattern: 'plugin.schedule.<owner>.<job>',
        sample: { secretKeys: ['apiKey'], scheduleKeys: ['nightly'] },
        fullTrustSource: 'local-policy',
      },
      migration: {
        version: 20261117,
        file: 'vfs/V20261117__plugin_skill_data_contract.sql',
        baselineVersion: 20261115,
        mediaVersion: 20261116,
        previousReservation: { version: 20261116, path: PREVIOUS_PLUGIN_SQL },
        sha256: sha256(readFileSync(resolve(root, PLUGIN_SQL))),
        preparedInputs: api.PLUGIN_DATA_MIGRATION_INPUTS.map(input => ({ ...input })),
      },
      upstreamUpgrade: upgradeReport,
      freshAndRestart: freshReports,
      quota: { depth: 16, recordBytes: 65_536, ownerRecords: 2000, ownerBytes: 5 * 1024 * 1024 },
    };
    const contract = {
      formatVersion: 1,
      unit: UNIT,
      status: { sourceIncrement: 'installed on locked desktop checkout',
        packaging: 'not claimed', distribution: 'not claimed' },
      api: {
        install: 'installPluginDataIncrement()',
        store: 'createPluginDataStore(db, capability, options)',
        apply: 'production applyDownloadedChangesToDatabases with serialized sync changes',
        migrations: 'preparePluginDataMigrations(parent)',
      },
      schema: {
        table: 'portable_plugin_records',
        migration: 'V20261117__plugin_skill_data_contract.sql',
        logicalId: 'canonical JSON array [owner_id, namespace, record_id]',
        rowFields: ['owner_id', 'owner_kind', 'namespace', 'record_id', 'package_version',
          'source_hash', 'schema_version', 'min_writer_schema', 'data_json',
          'unknown_json', 'deleted_at', 'device_id', 'local_version', 'updated_at'],
        classification: plain(classification),
        constraints: ['id equals json_array(owner, namespace, record_id)',
          'identity and min_writer cannot decrease', 'record depth <= 16',
          'record bytes <= 65536', 'live owner quota 2000 rows / 5 MiB'],
      },
      migration: {
        coordinatedFile: PLUGIN_SQL,
        version: 20261117,
        name: 'plugin_skill_data_contract',
        baselineVersion: 20261115,
        mediaVersion: 20261116,
        previousReservation: { version: 20261116, path: PREVIOUS_PLUGIN_SQL },
        sha256: sha256(readFileSync(resolve(root, PLUGIN_SQL))),
        preparedInputs: api.PLUGIN_DATA_MIGRATION_INPUTS.map(input => ({ ...input })),
        preparedOrder: ['V20261006__portable_anki_templates.sql',
          'V20261116__portable_media_progress.sql',
          'V20261117__plugin_skill_data_contract.sql'],
      },
      ownership: { capability: 'trusted injected identity copied at store creation',
        arbitraryPayloadOwnerIgnored: true, ownerIsolation: true },
      separation: {
        data: ['portable_plugin_records'],
        localServices: {
          pluginSecrets: { storage: 'local secure store',
            keyPattern: 'secret.plugin.<owner>.<key>', portable: false, synced: false },
          pluginSchedule: { storage: 'local settings',
            keyPattern: 'plugin.schedule.<owner>.<job>', portable: false, synced: false },
          pluginNotify: { storage: 'local device notification policy', portable: false },
          studyData: { route: 'existing repositories and domain sync',
            portablePluginRecords: false },
        },
        installAndTrust: 'callable local policy adapter only; not synchronized and not a reserved table; app lifecycle not claimed',
        fullTrust: 'local-only; remote content cannot install, enable, trust, or grant full trust',
        remoteContent: { installs: false, enables: false, trusts: false,
          fullTrust: false, executes: false },
        uninstallDefault: 'remove local install state and retain synced data',
        explicitDelete: 'owner tombstones retained for replay',
      },
      sourceHashes,
      observed: {
        recordsCollected: pluginChanges.length,
        wireBytes: serialized.reduce((sum, text) => sum + Buffer.byteLength(text), 0),
        recordsApplied: upload.total_success,
        unknownRetained: true,
        tombstoneReplay: true,
        forgedTupleRejected: true,
        sourceHashNotOutputAuthority: true,
        pluginServicesLocal: true,
        remoteFullTrustRejected: true,
        freshAndRestart: true,
        upstreamMediaPluginUpgrade: true,
      },
    };
    const mode = process.argv[3];
    artifact(FIXTURE, plain(fixture), mode);
    artifact(CONTRACT, contract, mode);
    process.stdout.write(JSON.stringify({ formatVersion: 1, unit: UNIT,
      records: pluginChanges.length, applied: upload.total_success,
      migrationVersion: 20261117, upgrade: '20261115->20261116->20261117',
      freshAndRestart: true, tombstoneReplay: true, forgedTupleRejected: true,
      unknownRetained: true, pluginServicesLocal: true,
      remoteFullTrustRejected: true }));
  } finally {
    for (const storage of stores) storage.shutdown();
    setLocalRuntime(null);
    hook.unregister();
    rmSync(temp, { recursive: true, force: true });
  }
}

export async function run(mode) {
  const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--worker', mode],
    { cwd: root, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'inherit'] });
  const value = JSON.parse(output);
  log('plugin-data.verified', { unit: UNIT, mode, ...value });
}

if (process.argv[2] === '--worker') worker().catch(error => {
  log('plugin-data.failed', { message: error.message, stack: error.stack });
  process.exitCode = 1;
});
else main(import.meta.url, run);
