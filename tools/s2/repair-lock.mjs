// Reproduce the S2.04 supplement from locked bytes and the frozen desktop SQLite implementation.
// Usage: node --experimental-strip-types tools/s2/repair-lock.mjs --write|--verify
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Connection } from '../../../dstu-node/host/platform/storage/connection.ts';
import { MigrationSet } from '../../../dstu-node/host/platform/storage/migration/definitions.ts';
import { computeSchemaFingerprint } from '../../../dstu-node/host/platform/storage/migration/fingerprint.ts';
import { migrationsFromSqlFiles, refineryChecksum, Runner } from '../../../dstu-node/host/platform/storage/migration/refinery.ts';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const desktop = resolve(root, '../dstu-node');
const finalPath = 'config/schema/final-lock.json';
const finalSha = '5fd1b3a4bd4288ce577c3f17677c9ca6accfab56c03a80130a8797baed5c08e8';
const setSha = 'a3c70a046b3d981c2ceba348ad9e7a14e3113d076925e6dbad50ccc67a6d2675';
const sqlPath = 'config/schema/coordinated/vfs/V20261120__fix_plugin_delete_trigger.sql';
const lockPath = resolve(root, 'config/schema/s2-04-repair.json');
const trigger = 'trg__change_log_portable_plugin_records_delete';
const sha = data => createHash('sha256').update(data).digest('hex');
const read = path => readFileSync(resolve(root, path));
const decode = bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes);
const git = (...args) => execFileSync('git', args, { cwd: desktop, encoding: 'utf8' }).trim();
assert.equal(process.argv.length, 3, 'expected --write or --verify');
assert.ok(['--write', '--verify'].includes(process.argv[2]));
assert.equal(git('rev-parse', 'HEAD'), 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87');
assert.equal(git('status', '--porcelain', '--untracked-files=no'), '', 'desktop tracked worktree changed');
const finalBytes = read(finalPath);
assert.equal(sha(finalBytes), finalSha, 'final lock bytes');
const final = JSON.parse(decode(finalBytes));
const rows = final.sql.files;
assert.equal(rows.length, 138);
assert.equal(final.sql.upstreamCount, 135);
assert.equal(final.sql.coordinatedCount, 3);
assert.equal(final.sql.setSha256, setSha);
assert.equal(sha(rows.map(row => `${row.database}\0${row.version}\0${row.name}\0${row.sha256}\0${row.refineryChecksum}\n`).join('')), setSha);
assert.equal(new Set(rows.map(row => `${row.database}\0${row.version}`)).size, 138);
for (const row of rows) {
  const bytes = read(row.file);
  assert.equal(bytes.length, row.bytes, row.file);
  assert.equal(sha(bytes), row.sha256, row.file);
  assert.equal(refineryChecksum(row.name, row.version, decode(bytes)).toString(), row.refineryChecksum, row.file);
}
const old = final.databases.find(row => row.id === 'vfs');
assert.equal(old.version, 20261117);
const sqlBytes = read(sqlPath);
const sql = decode(sqlBytes);
const migration = {
  database: 'vfs', version: 20261120, name: 'fix_plugin_delete_trigger', file: sqlPath,
  bytes: sqlBytes.length, sha256: sha(sqlBytes),
  refineryChecksum: refineryChecksum('fix_plugin_delete_trigger', 20261120, sql).toString(),
};
const vfsRows = rows.filter(row => row.database === 'vfs').sort((a, b) => a.version - b.version);
assert.equal(vfsRows.length, 75);
const oldMigrations = migrationsFromSqlFiles(vfsRows.map(row => ({ name: basename(row.file), sql: decode(read(row.file)) })));
const newMigrations = migrationsFromSqlFiles([{ name: basename(sqlPath), sql }]);
const allMigrations = [...oldMigrations, ...newMigrations];
const asSet = migrations => new MigrationSet('vfs', migrations.map(m => ({
  refineryVersion: m.version, name: m.name, sql: m.sql,
  expectedTables: [], expectedColumns: [], expectedIndexes: [], expectedQueries: [], idempotent: false,
})));
const conn = new Connection(':memory:', { label: 's2-04-repair-lock', profile: { foreignKeys: true } });
let previous, repaired;
try {
  assert.equal(new Runner(oldMigrations).run(conn).length, 75);
  previous = computeSchemaFingerprint(conn, asSet(oldMigrations), old.version);
  assert.equal(previous.hash, old.fingerprint, 'pre-repair SQLite fingerprint');
  for (const key of ['tables', 'indexes', 'triggers']) {
    assert.deepEqual([...previous.scope[key]], old.managedSchema.names[key], `old ${key} scope`);
  }
  const oldTrigger = conn.pluck("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?", [trigger]);
  assert.ok(oldTrigger.includes("VALUES ('portable_plugin_records', NEW.id, 'DELETE')"));
  assert.equal(new Runner(allMigrations).run(conn).length, 1);
  assert.equal(new Runner(allMigrations).run(conn).length, 0);
  repaired = computeSchemaFingerprint(conn, asSet(allMigrations), migration.version);
  for (const key of ['tables', 'indexes', 'triggers']) {
    assert.deepEqual([...repaired.scope[key]], old.managedSchema.names[key], `repaired ${key} scope`);
  }
  const newTrigger = conn.pluck("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?", [trigger]);
  assert.equal(newTrigger, oldTrigger.replace("VALUES ('portable_plugin_records', NEW.id, 'DELETE')", "VALUES ('portable_plugin_records', OLD.id, 'DELETE')"));
  assert.equal(repaired.canonicalSchema, previous.canonicalSchema.replace(oldTrigger, newTrigger), 'only trigger changes');
  assert.equal(conn.pluck('PRAGMA integrity_check'), 'ok');
  assert.equal(conn.all('PRAGMA foreign_key_check').length, 0);
} finally { conn.close(); }
const supplement = {
  formatVersion: 1,
  unit: 'S2.04',
  purpose: 'coordinated portable_plugin_records physical DELETE trigger repair',
  anchor: { file: finalPath, sha256: finalSha, sqlSetSha256: setSha },
  database: 'vfs',
  before: { version: old.version, fingerprint: previous.hash, migrationCount: vfsRows.length },
  after: { version: migration.version, fingerprint: repaired.hash, migrationCount: allMigrations.length },
  scope: 'unchanged from anchored final-lock managedSchema.names',
  migration,
};
const rendered = `${JSON.stringify(supplement, null, 2)}\n`;
if (process.argv[2] === '--write') writeFileSync(lockPath, rendered, { flag: 'wx' });
else assert.equal(readFileSync(lockPath, 'utf8'), rendered, 'supplement lock drift');
console.log(JSON.stringify({ ...supplement, supplementSha256: sha(Buffer.from(rendered)) }, null, 2));
