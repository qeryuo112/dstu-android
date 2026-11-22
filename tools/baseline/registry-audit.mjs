import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { readJson, difference, log, main, desktop, root } from './lib.mjs';

function validateContract(contract, baseline) {
  assert.equal(contract.unit, 'S0.19');
  assert.equal(contract.registry.length, baseline.registry.length);
  for (const [index, expected] of baseline.registry.entries()) {
    const actual = contract.registry[index];
    assert.equal(`${actual.database}.${actual.table}`, `${expected.database}.${expected.table}`, `registry order ${index}`);
    assert.equal(actual.category, expected.category, `${actual.table}: category`);
    assert.equal(actual.conflictPolicy, expected.conflictPolicy, `${actual.table}: conflict policy`);
    assert.equal(actual.logicalPk.declared, expected.primaryKey, `${actual.table}: logical identity`);
    if (actual.category === 'RowSync') assert.equal(actual.triggers.allRequiredPresent, true, actual.table);
  }
}

export async function run() {
  const baseline = readJson('config/baseline/classification.json');
  const schema = readJson('config/baseline/schema.json');
  const seen = new Set();
  for (const entry of baseline.registry) {
    const key = `${entry.database}.${entry.table}`;
    assert.equal(seen.has(key), false, key);
    seen.add(key);
    const table = schema.databases.find(database => database.id === entry.database).schema.tables.find(table => table.name === entry.table);
    if (entry.category === 'Deprecated') { assert.equal(table, undefined); continue; }
    assert.ok(table, key);
    const objects = schema.databases.find(database => database.id === entry.database).schema.objects;
    const triggers = objects.filter(object => object.type === 'trigger' && object.tbl_name === entry.table && object.sql.includes('__change_log'));
    if (entry.category !== 'RowSync') assert.equal(triggers.length, 0, `${key}: local/derived trigger leak`);
    else {
      for (const operation of entry.changeLog.required) assert.equal(operation.present, true, `${key}: ${operation.operation}`);
    }
    if (entry.schemaTable.identity.matches === false) assert.equal(key, 'vfs.note_document_revisions');
    if (entry.schemaTable.businessKeyBacked === false) assert.equal(key, 'vfs.automation_todo_deliveries');
  }
  assert.equal(seen.size, 124);
  assert.equal(baseline.registry.filter(entry => entry.category === 'RowSync').length, 58);
  const contract = readJson('config/sync-registry-contract.json');
  validateContract(contract, baseline);
  const mutations = [
    value => { value.registry[0].category = 'LocalRuntime'; },
    value => { [value.registry[0], value.registry[1]] = [value.registry[1], value.registry[0]]; },
    value => { value.registry[0].logicalPk.declared = 'incorrect'; },
    value => { value.registry.find(entry => entry.category === 'RowSync').triggers.allRequiredPresent = false; },
  ];
  for (const mutate of mutations) {
    const changed = structuredClone(contract);
    mutate(changed);
    assert.throws(() => validateContract(changed, baseline));
  }
  const load = file => import(pathToFileURL(resolve(desktop, file)).href);
  const { Connection } = await load('host/platform/storage/connection.ts');
  const { Runner, loadSqlMigrations } = await load('host/platform/storage/migration/refinery.ts');
  for (const database of ['vfs', 'mistakes']) {
    const conn = new Connection(':memory:', { label: `registry-local-${database}`, profile: { foreignKeys: true } });
    try {
      new Runner(loadSqlMigrations(resolve(root, 'config/schema/history', database))).run(conn);
      conn.exec('DELETE FROM __change_log');
      if (database === 'vfs') {
        conn.run('INSERT INTO media_progress(resource_id,last_position_ms,updated_at) VALUES(?,?,?)', ['public-media', 10, 0]);
        conn.run('UPDATE media_progress SET last_position_ms=20 WHERE resource_id=?', ['public-media']);
        conn.run('DELETE FROM media_progress WHERE resource_id=?', ['public-media']);
        conn.run('INSERT INTO path_cache VALUES(?,?,?,?,?)', ['note', 'public-note', '/public', '/', '2026-11-15T00:00:00Z']);
        conn.exec('DELETE FROM path_cache');
      } else {
        conn.run('INSERT INTO temp_sessions(temp_id,session_data,created_at,updated_at) VALUES(?,?,?,?)',
          ['public-session', '{}', '2026-11-15T00:00:00Z', '2026-11-15T00:00:00Z']);
        conn.run('UPDATE temp_sessions SET session_data=? WHERE temp_id=?', ['{"changed":true}', 'public-session']);
        conn.run('DELETE FROM temp_sessions WHERE temp_id=?', ['public-session']);
      }
      assert.equal(conn.pluck('SELECT COUNT(*) FROM __change_log'), 0, `${database}: local capture leaked`);
      log('registry-audit.local-writes', { unit: 'S0.19', database, captured: 0 });
    } finally { conn.close(); }
  }
  assert.equal(difference({ key: ['id'] }, { key: ['wrong'] }).path, '$.key.0');
  log('registry-audit.accepted', { unit: 'S0.19', registry: seen.size, rowSync: 58,
    logicalKeyExceptions: 1, unbackedUniqueKeys: 1, localDerivedCaptureLeaks: 0, independentTamperCases: mutations.length,
    runtimeWriteCategories: ['LocalRuntime', 'BackupOnly', 'DerivedRebuild'] });
}

main(import.meta.url, run);
