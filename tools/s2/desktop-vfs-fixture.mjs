import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, openSync, readFileSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { Connection } from '../../../dstu-node/host/platform/storage/connection.ts';
import { computeSchemaFingerprint } from '../../../dstu-node/host/platform/storage/migration/fingerprint.ts';
import { loadSqlMigrations, Runner } from '../../../dstu-node/host/platform/storage/migration/refinery.ts';
import { MigrationSet } from '../../../dstu-node/host/platform/storage/migration/definitions.ts';
import { root } from '../s1/frozen-inputs.mjs';

const desktop = resolve(root, '../dstu-node');
const commit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: desktop, encoding: 'utf8' }).trim(), commit);
execFileSync('git', ['diff', '--quiet', commit, '--'], { cwd: desktop });
const json = name => JSON.parse(readFileSync(resolve(root, name), 'utf8'));
const golden = json('config/baseline/schema-upgrade.json');
const lock = json('config/schema/final-lock.json').databases.find(item => item.id === 'vfs');
const repair = json('config/schema/s2-04-repair.json');
assert.equal(lock.version, repair.before.version);
assert.equal(lock.fingerprint, repair.before.fingerprint);
const effectiveHistory = [...lock.history, { version: repair.migration.version, name: repair.migration.name,
  checksum: repair.migration.refineryChecksum }];
const version = golden.fixture.fromVersion;
const migrations = loadSqlMigrations(resolve(root, 'config/schema/history/vfs')).filter(item => item.version <= version);
const set = new MigrationSet('vfs', migrations.map(item => ({ refineryVersion: item.version, name: item.name, sql: item.sql,
  expectedTables: [], expectedColumns: [], expectedIndexes: [], expectedQueries: [], idempotent: false })));

assert.equal(process.argv.length, 3, 'usage: desktop-vfs-fixture.mjs <new disposable database file>');
const output = resolve(process.argv[2]);
closeSync(openSync(output, 'wx', 0o600));
const sha = data => createHash('sha256').update(data).digest('hex');
try {
  const db = new Connection(output, { label: 's2-desktop-upgrade-fixture', profile: { foreignKeys: true } });
  let sqlite;
  let historyHash;
  try {
    assert.equal(new Runner(migrations).run(db).length, golden.fixture.vfsMigrationCount.before);
    sqlite = db.pluck('SELECT sqlite_version()');
    const history = db.all('SELECT version,name,checksum FROM refinery_schema_history ORDER BY version');
    historyHash = sha(JSON.stringify(history));
    assert.deepEqual(history, lock.history.filter(item => item.version <= version));
    const schema = computeSchemaFingerprint(db, set, version);
    assert.equal(schema.hash, golden.fingerprints.previousVersion.fingerprint);

    const row = golden.values.before;
    db.run(`INSERT INTO exam_sheets(id,exam_name,status,temp_id,metadata_json,preview_json,created_at,updated_at)
      VALUES (?, 'Desktop upgrade fixture', 'completed', 's2-test', '{}', '{}', ?, ?)`,
    [row.exam_id, row.created_at, row.updated_at]);
    db.run(`INSERT INTO questions(id,exam_id,question_label,content,answer,created_at,updated_at)
      VALUES (?, ?, '1', 'Fixture question', 'A', ?, ?)`,
    [row.question_id, row.exam_id, row.created_at, row.updated_at]);
    const columns = ['id', 'question_id', 'exam_id', 'ease_factor', 'interval_days', 'repetitions', 'next_review_date',
      'last_review_date', 'status', 'total_reviews', 'total_correct', 'consecutive_failures', 'is_difficult', 'created_at',
      'updated_at', 'device_id', 'local_version', 'deleted_at'];
    db.run(`INSERT INTO review_plans(${columns.join(',')}) VALUES (${columns.map(() => '?').join(',')})`,
      columns.map(key => row[key]));
    assert.deepEqual(db.get(`SELECT ${columns.join(',')} FROM review_plans WHERE id=?`, [row.id]), row);
    assert.equal(db.pluck('PRAGMA integrity_check'), 'ok');
    assert.deepEqual(db.all('PRAGMA foreign_key_check'), []);
  } finally {
    db.close();
  }
  console.log(JSON.stringify({ event: 's2.desktop.old-fixture', commit, sqlite, fromVersion: version,
    oldHistorySha256: historyHash, fileSha256: sha(readFileSync(output)), finalHistorySha256: sha(JSON.stringify(effectiveHistory)),
    finalFingerprint: repair.after.fingerprint, targetVersion: repair.after.version,
    frozenFinalHistorySha256: lock.historySha256, frozenFinalFingerprint: lock.fingerprint }));
} catch (error) {
  rmSync(output, { force: true });
  throw error;
}
