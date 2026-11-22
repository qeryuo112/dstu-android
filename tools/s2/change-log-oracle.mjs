// Verification-only oracle. No frozen input is regenerated or written by this tool.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { installCaptureRepairIncrement, prepareCaptureRepairMigrations } from '../../desktop-increment/s2-capture-repair.mjs';
import { root } from '../s1/frozen-inputs.mjs';

const desktop = resolve(root, '../dstu-node');
const commit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const frozenLockPath = 'config/schema/final-lock.json';
const frozenLockSha256 = '5fd1b3a4bd4288ce577c3f17677c9ca6accfab56c03a80130a8797baed5c08e8';
const supplementLockPath = 'config/schema/s2-04-repair.json';
const supplementLockSha256 = 'ec1ce53307dd93c45584c55b7fc38c6e52a3b28b3a7fa5f0514b56c78f0b283c';
const repairPath = 'config/schema/coordinated/vfs/V20261120__fix_plugin_delete_trigger.sql';
const repairName = 'fix_plugin_delete_trigger';
const repairVersion = 20261120;
const oldChangesSha256 = [
  '29b2437839d7257ecd8ee664dcc77c2c13d9a651c6fe35d7a2f5defe5fddbec9',
  'fa295a95025d2f9c9a5bfe79b00858560e466e935c9b9757913b58a99c60954e',
];
const git = (...args) => execFileSync('git', args, { cwd: desktop, encoding: 'utf8' }).trim();
assert.equal(git('rev-parse', 'HEAD'), commit);
assert.equal(git('status', '--porcelain', '--untracked-files=no'), '');
assert.equal(process.argv.length, 3, 'usage: change-log-oracle.mjs <new disposable fixture document>');
const output = resolve(process.argv[2]);
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const lockBytes = readFileSync(resolve(root, frozenLockPath));
assert.equal(sha(lockBytes), frozenLockSha256, 'frozen migration lock drift');
const lock = JSON.parse(lockBytes);
const supplementBytes = readFileSync(resolve(root, supplementLockPath));
assert.equal(sha(supplementBytes), supplementLockSha256, 'S2.04 supplement lock drift');
const supplement = JSON.parse(supplementBytes);
assert.equal(supplement.anchor.file, frozenLockPath);
assert.equal(supplement.anchor.sha256, frozenLockSha256);
assert.equal(supplement.anchor.sqlSetSha256, lock.sql.setSha256);
assert.equal(supplement.before.version, 20261117);
assert.equal(supplement.before.migrationCount, 75);
assert.equal(supplement.before.fingerprint, lock.databases.find(row => row.id === 'vfs').fingerprint);
assert.equal(supplement.after.version, repairVersion);
assert.equal(supplement.after.migrationCount, 76);
assert.equal(supplement.migration.file, repairPath);
assert.equal(supplement.migration.database, 'vfs');
assert.equal(supplement.migration.name, repairName);
assert.equal(supplement.migration.version, repairVersion);

// The desktop modules must be imported *after* installing the compositional source hook.
// In particular record-data imports the registry transitively, and an earlier import
// would silently cache the frozen registry without portable_plugin_records.
const increment = installCaptureRepairIncrement();
const temp = mkdtempSync(join(tmpdir(), 'dstu-s205-oracle-'));
try {
  const migrationsDir = prepareCaptureRepairMigrations(join(temp, 'migrations'));
  const { Connection } = await import('../../../dstu-node/host/platform/storage/connection.ts');
  const { loadSqlMigrations, Runner } = await import('../../../dstu-node/host/platform/storage/migration/refinery.ts');
  const { MigrationSet } = await import('../../../dstu-node/host/platform/storage/migration/definitions.ts');
  const { computeSchemaFingerprint } = await import('../../../dstu-node/host/platform/storage/migration/fingerprint.ts');
  const { VFS_MIGRATIONS, VFS_SCHEMA_VERSION } = await import('../../../dstu-node/host/platform/storage/migration/definitions/vfs.ts');
  const { rowSyncTables } = await import('../../../dstu-node/host/plugins/sync/src/classification.ts');
  const { getPendingChanges } = await import('../../../dstu-node/host/plugins/sync/repository/change-log.ts');
  const { enrichChangesWithData } = await import('../../../dstu-node/host/plugins/sync/repository/record-data.ts');
  const { serializeSyncChange } = await import('../../../dstu-node/host/plugins/sync/src/change-payload.ts');
  assert.equal(VFS_SCHEMA_VERSION, repairVersion, 'desktop increment did not install the new VFS head');
  const definition = VFS_MIGRATIONS.at(-1);
  assert.equal(definition.refineryVersion, repairVersion);
  assert.equal(definition.name, repairName);
  assert.equal(definition.file, `vfs/${basename(repairPath)}`);
  const pluginClassifications = rowSyncTables().filter(row => row.database === 'vfs' && row.table_name === 'portable_plugin_records');
  assert.equal(pluginClassifications.length, 1, 'plugin table is not a unique desktop RowSync entry');
  assert.equal(pluginClassifications[0].primary_key, 'id');

  const repairBytes = readFileSync(resolve(root, repairPath));
  const repairSha256 = sha(repairBytes);
  assert.equal(repairBytes.length, supplement.migration.bytes, 'repair byte count drift');
  assert.equal(repairSha256, supplement.migration.sha256, 'repair SQL differs from supplement lock');
  assert.equal(sha(readFileSync(join(migrationsDir, 'vfs', basename(repairPath)))), repairSha256,
    'prepared repair SQL differs from coordinated source');
  const prepared = new Map();
  for (const database of ['vfs', 'chat_v2']) {
    const rows = lock.sql.files.filter(row => row.database === database);
    const names = rows.map(row => basename(row.file));
    if (database === 'vfs') names.push(basename(repairPath));
    const found = readdirSync(join(migrationsDir, database)).filter(name => name.endsWith('.sql')).sort();
    assert.deepEqual(found, names.sort(), `${database}: prepared migration file set drift`);
    for (const row of rows) {
      assert.equal(sha(readFileSync(resolve(root, row.file))), row.sha256, `${row.file}: frozen source drift`);
      assert.equal(sha(readFileSync(join(migrationsDir, database, basename(row.file)))), row.sha256,
        `${row.file}: prepared SQL drift`);
    }
    prepared.set(database, loadSqlMigrations(join(migrationsDir, database)));
  }
  const repairMigration = prepared.get('vfs').at(-1);
  assert.equal(repairMigration.version, repairVersion);
  assert.equal(repairMigration.name, repairName);
  const repairChecksum = repairMigration.checksum.toString();
  assert.equal(repairChecksum, supplement.migration.refineryChecksum, 'repair refinery checksum drift');

const reset = [
  'DELETE FROM __change_log',
  "INSERT INTO sqlite_sequence(name,seq) SELECT '__change_log',0 WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='__change_log')",
  "UPDATE sqlite_sequence SET seq=9007199254740992 WHERE name='__change_log'",
];
const sameTime = "UPDATE __change_log SET changed_at='2026-01-01 00:00:00'";
const resources = [
  `INSERT INTO resources(id,hash,type,storage_mode,data,metadata_json,ref_count,created_at,updated_at) VALUES
      ('reinsert-r','r-old','note','inline',X'004142ff',NULL,0,1,2),
      ('removed-r','r-removed','note','inline',X'4142',NULL,0,1,2),
      ('published-r','r-published','note','inline',X'01',NULL,0,1,2)`,
  "UPDATE resources SET data=X'78' WHERE id='removed-r'",
  "DELETE FROM resources WHERE id IN ('reinsert-r','removed-r')",
  `INSERT INTO resources(id,hash,type,storage_mode,data,metadata_json,ref_count,created_at,updated_at) VALUES
      ('reinsert-r','r-new','note','inline',X'0001ff','',0,9007199254740993,9223372036854775807)`,
  "UPDATE resources SET index_error='device-only', mm_index_error='local-only' WHERE id='reinsert-r'",
  `CREATE TABLE __sync_unknown_fields (
      table_name TEXT NOT NULL, record_id TEXT NOT NULL, fields_json TEXT NOT NULL,
      updated_at TEXT NOT NULL DEFAULT (datetime('now')), PRIMARY KEY(table_name, record_id))`,
  `INSERT INTO __sync_unknown_fields(table_name,record_id,fields_json) VALUES('resources','reinsert-r',
      '{"future_integer":18446744073709551615,"future_min":-9223372036854775808,"future_null":null,"future_empty":"","future_clocked":{"value":{"list":[null,"",9007199254740993]},"hlc":"2026-01-01T00:00:00Z","dev":"fixture","seq":9007199254740994}}')`,
  "UPDATE __change_log SET sync_version=9007199254740993 WHERE table_name='resources' AND record_id='published-r'",
  `UPDATE __change_log SET field_deltas_json='{"future_clocked":{"hlc":"2026-01-02T00:00:00Z","dev":"fixture","seq":9007199254740995}}'
      WHERE table_name='resources' AND record_id='reinsert-r' AND operation IN ('INSERT','UPDATE')`,
];
const chat = [
  `INSERT INTO chat_v2_sessions(id,mode,title,created_at,updated_at) VALUES ('sess-s205','analysis',NULL,'2026-01-01','2026-01-01')`,
  `INSERT INTO chat_v2_session_mistakes(session_id,mistake_id,relation_type,created_at) VALUES ('sess-s205','例子','primary','2026-01-01')`,
  `DELETE FROM chat_v2_session_mistakes WHERE session_id='sess-s205' AND mistake_id='例子'`,
  `INSERT INTO chat_v2_session_mistakes(session_id,mistake_id,relation_type,created_at) VALUES ('sess-s205','例子','bridge','2026-01-01')`,
  `UPDATE chat_v2_session_mistakes SET updated_at='' WHERE session_id='sess-s205' AND mistake_id='例子'`,
  `UPDATE __change_log SET record_id='{"session_id":"sess-s205","mistake_id":"例子"}'
      WHERE table_name='chat_v2_session_mistakes' AND operation='UPDATE'`,
];


const plugin = [
  `INSERT INTO portable_plugin_records(id,owner_id,owner_kind,namespace,record_id,package_version,source_hash,
      schema_version,min_writer_schema,data_json,unknown_json,deleted_at,device_id,local_version,updated_at) VALUES
      (json_array('plugin.s2','state','reinsert'),'plugin.s2','plugin','state','reinsert','1',
       '${'a'.repeat(64)}',1,1,'{"value":"before"}','{}',NULL,'fixture',1,'2026-01-01')`,
  `UPDATE portable_plugin_records SET deleted_at='2026-01-02' WHERE id=json_array('plugin.s2','state','reinsert')`,
  `DELETE FROM portable_plugin_records WHERE id=json_array('plugin.s2','state','reinsert')`,
  `INSERT INTO portable_plugin_records(id,owner_id,owner_kind,namespace,record_id,package_version,source_hash,
      schema_version,min_writer_schema,data_json,unknown_json,deleted_at,device_id,local_version,updated_at) VALUES
      (json_array('plugin.s2','state','reinsert'),'plugin.s2','plugin','state','reinsert','1',
       '${'a'.repeat(64)}',1,1,'{"counter":9007199254740993,"empty":"","nothing":null}',
       '{"future":{"value":null,"hlc":"2026-01-01T00:00:00Z","dev":"fixture","seq":9007199254740993}}',
       NULL,'fixture',9007199254740993,'2026-01-03')`,
  `UPDATE portable_plugin_records SET data_json='{"counter":9007199254740993,"empty":"","nothing":null,"state":"current"}'
      WHERE id=json_array('plugin.s2','state','reinsert')`,
  `INSERT INTO portable_plugin_records(id,owner_id,owner_kind,namespace,record_id,package_version,source_hash,
      schema_version,min_writer_schema,data_json,unknown_json,deleted_at,device_id,local_version,updated_at) VALUES
      (json_array('plugin.s2','state','removed'),'plugin.s2','plugin','state','removed','1',
       '${'a'.repeat(64)}',1,1,'{"value":"gone"}','{}',NULL,'fixture',1,'2026-01-01')`,
  `DELETE FROM portable_plugin_records WHERE id=json_array('plugin.s2','state','removed')`,
];

function migrated(db, database) {
  const frozen = lock.databases.find(row => row.id === database);
  const expected = [...frozen.history];
  if (database === 'vfs') expected.push({ version: repairVersion, name: repairName, checksum: repairChecksum });
  const runner = new Runner(prepared.get(database));
  assert.equal(runner.run(db).length, expected.length);
  assert.deepEqual(db.all('SELECT version,name,checksum FROM refinery_schema_history ORDER BY version'), expected);
  assert.equal(runner.run(db).length, 0, `${database}: migration reran on restart`);
  if (database === 'vfs') {
    const set = new MigrationSet('vfs', prepared.get('vfs').map(m => ({
      refineryVersion: m.version, name: m.name, sql: m.sql, expectedTables: [], expectedColumns: [],
      expectedIndexes: [], expectedQueries: [], idempotent: false,
    })));
    const fingerprint = computeSchemaFingerprint(db, set, repairVersion);
    assert.equal(fingerprint.hash, supplement.after.fingerprint, 'repaired VFS schema fingerprint drift');
    for (const key of ['tables', 'indexes', 'triggers']) {
      assert.deepEqual([...fingerprint.scope[key]], frozen.managedSchema.names[key], `VFS ${key} scope drift`);
    }
    const trigger = db.all("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='trg__change_log_portable_plugin_records_delete'");
    assert.equal(trigger.length, 1);
    assert.match(trigger[0].sql, /AFTER\s+DELETE\s+ON\s+portable_plugin_records/iu);
    assert.match(trigger[0].sql, /OLD\.id/iu);
    assert.doesNotMatch(trigger[0].sql, /NEW\.id/iu);
  }
}

function scenario(database, table, inputs, assertions) {
  const statements = [...reset, ...inputs, sameTime].map(sql => sql.trim());
  const db = new Connection(':memory:', { label: 's2.05-desktop-changes-oracle', profile: { foreignKeys: true } });
  try {
    migrated(db, database);
    statements.forEach(sql => db.exec(sql));
    const pending = getPendingChanges(db, table, null).entries;
    const ids = pending.map(row => BigInt(row.id));
    assert.ok(ids.length >= 4 && ids[0] > 9007199254740992n);
    assert.ok(ids.every((id, index) => index === 0 || id > ids[index - 1]));
    const enriched = enrichChangesWithData(db, pending).map(change => ({ ...change, database_name: database }));
    assertions(enriched);
    return { database, table, statements, expectedChanges: enriched.map(serializeSyncChange) };
  } finally {
    db.close();
  }
}

const vfs = scenario('vfs', 'resources', resources, enriched => {
  const reinserted = enriched.filter(change => change.record_id === 'reinsert-r');
  assert.deepEqual(reinserted.map(change => change.operation), ['Insert', 'Delete', 'Insert', 'Update']);
  assert.equal(reinserted[1].data, null);
  assert.equal(reinserted[2].data.hash, 'r-new');
  assert.equal(reinserted[2].data.future_integer, (1n << 64n) - 1n);
  assert.equal(reinserted[2].data.index_error, undefined);
  assert.ok(enriched.filter(change => change.record_id === 'removed-r').every(change => change.data === null));
});
const composite = scenario('chat_v2', 'chat_v2_session_mistakes', chat, enriched => {
  assert.deepEqual(enriched.map(change => change.operation), ['Insert', 'Delete', 'Insert', 'Update']);
  assert.match(enriched[0].record_id, /^sess-s205:例子$/u);
  assert.match(enriched[3].record_id, /^\{/u);
  assert.equal(enriched[1].data, null);
  assert.equal(enriched[3].data.relation_type, 'bridge');
  assert.equal(enriched[3].data.updated_at, '');
});
const pluginChanges = scenario('vfs', 'portable_plugin_records', plugin, enriched => {
  const id = '["plugin.s2","state","reinsert"]';
  const removed = '["plugin.s2","state","removed"]';
  assert.deepEqual(enriched.map(change => change.operation), ['Insert', 'Update', 'Delete', 'Insert', 'Update', 'Insert', 'Delete']);
  assert.deepEqual(enriched.map(change => change.record_id), [id, id, id, id, id, removed, removed]);
  for (const index of [0, 1, 3, 4]) {
    assert.equal(enriched[index].data.id, id);
    assert.equal(enriched[index].data.data_json,
      '{"counter":9007199254740993,"empty":"","nothing":null,"state":"current"}');
    assert.equal(enriched[index].data.deleted_at, null);
    assert.equal(enriched[index].data.local_version, 9007199254740993n);
    assert.equal(enriched[index].data.unknown_json,
      '{"future":{"value":null,"hlc":"2026-01-01T00:00:00Z","dev":"fixture","seq":9007199254740993}}');
  }
  assert.ok([2, 5, 6].every(index => enriched[index].data === null));
});
const scenarios = [vfs, composite, pluginChanges];
for (let i = 0; i < oldChangesSha256.length; i++) {
  assert.equal(sha(scenarios[i].expectedChanges.join('\n')), oldChangesSha256[i],
    `original scenario ${i} changed after the capture repair`);
}
const migration = { frozenLockPath, frozenLockSha256, supplementLockPath, supplementLockSha256, repairPath, repairSha256,
  version: repairVersion, name: repairName, checksum: repairChecksum };
const fixture = JSON.stringify({ desktopCommit: commit, migration, scenarios });
writeFileSync(output, fixture, { flag: 'wx', mode: 0o600 });
console.log(JSON.stringify({ event: 's2.05.desktop-changes', desktopCommit: commit, migration,
  fixtureSha256: sha(fixture), scenarios: scenarios.map(({ database, table, expectedChanges }) =>
    ({ database, table, changes: expectedChanges.length, changesSha256: sha(expectedChanges.join('\n')) })) }));
} finally {
  rmSync(temp, { recursive: true, force: true });
  increment.unregister();
}
