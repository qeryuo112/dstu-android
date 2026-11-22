// Targeted, disposable desktop/SQLite S2.04 verification; never writes the frozen checkout.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { installCaptureRepairIncrement, prepareCaptureRepairMigrations } from '../../desktop-increment/s2-capture-repair.mjs';
import { installedIncrementIds } from '../../desktop-increment/loader.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const desktop = resolve(root, '../dstu-node');
const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const repair = JSON.parse(readFileSync(resolve(root, 'config/schema/s2-04-repair.json'), 'utf8'));
const temp = mkdtempSync(join(tmpdir(), 'dstu-s204-host-'));
const hook = installCaptureRepairIncrement();
try {
  const dir = prepareCaptureRepairMigrations(join(temp, 'migrations'));
  assert.throws(() => prepareCaptureRepairMigrations(dir), /output must be empty/u);
  symlinkSync(desktop, join(temp, 'desktop-alias'));
  assert.throws(() => prepareCaptureRepairMigrations(join(temp, 'desktop-alias', 'must-not-create')), /cannot be in desktop checkout/u);
  const definitions = await load('host/platform/storage/migration/definitions/vfs.ts');
  const { buildMigrationSet } = await load('host/platform/storage/migration/definitions.ts');
  const { Connection } = await load('host/platform/storage/connection.ts');
  const { computeSchemaFingerprint } = await load('host/platform/storage/migration/fingerprint.ts');
  const { loadSqlMigrations, Runner } = await load('host/platform/storage/migration/refinery.ts');
  assert.equal(definitions.VFS_SCHEMA_VERSION, 20261120);
  assert.equal(definitions.VFS_MIGRATIONS.at(-1).file, 'vfs/V20261120__fix_plugin_delete_trigger.sql');
  const migrations = loadSqlMigrations(join(dir, 'vfs'));
  assert.equal(migrations.length, 76);
  const set = buildMigrationSet('vfs', definitions.VFS_MIGRATIONS, dir);
  assert.equal(set.latestVersion(), 20261120);
  const snapshot = db => ({
    rows: db.all('SELECT * FROM portable_plugin_records ORDER BY id'),
    log: db.all('SELECT * FROM __change_log ORDER BY id'),
    ledger: db.all('SELECT * FROM refinery_schema_history ORDER BY version'),
    sequence: db.all('SELECT * FROM sqlite_sequence ORDER BY name'),
  });
  const insert = record => `INSERT INTO portable_plugin_records VALUES (
    json_array('plugin.owner','prefs','${record}'),'plugin.owner','plugin','prefs','${record}',
    '1.2.3',printf('%064d',0),2,1,json_object('text','值'),json_object('future',json_object('value',null,'hlc','2026-01-01T00:00:00Z','dev','device-1','seq',1)),
    NULL,'device-1',9,'2026-11-20T01:02:03Z')`;
  for (const fresh of [true, false]) {
    const db = new Connection(join(temp, fresh ? 'fresh.db' : 'old.db'), { label: 's2-04-host', profile: { foreignKeys: true } });
    try {
      if (fresh) {
        assert.equal(new Runner(migrations).run(db).length, 76);
      } else {
        assert.equal(new Runner(migrations.slice(0, -1)).run(db).length, 75);
        assert.equal(computeSchemaFingerprint(db, set, 20261117).hash, repair.before.fingerprint);
        db.exec(insert('target'));
        db.exec(insert('kept-中文'));
        db.exec("UPDATE portable_plugin_records SET deleted_at='2026-11-20T02:03:04Z',local_version=10 WHERE record_id='kept-中文'");
        db.exec("UPDATE __change_log SET changed_at='2026-01-01',sync_version=42,field_deltas_json='{\"future\":null}'");
        const before = snapshot(db);
        assert.throws(() => db.exec("DELETE FROM portable_plugin_records WHERE record_id='target'"));
        assert.deepEqual(snapshot(db), before);
        assert.equal(new Runner(migrations).run(db).length, 1);
        const after = snapshot(db);
        assert.deepEqual(after.rows, before.rows);
        assert.deepEqual(after.log, before.log);
        assert.deepEqual(after.sequence, before.sequence);
        assert.deepEqual(after.ledger.slice(0, -1), before.ledger);
      }
      assert.equal(computeSchemaFingerprint(db, set, 20261120).hash, repair.after.fingerprint);
      const beforeRestart = snapshot(db);
      assert.equal(new Runner(migrations).run(db).length, 0);
      assert.deepEqual(snapshot(db), beforeRestart);
      if (fresh) db.exec(insert('target'));
      const id = db.pluck("SELECT id FROM portable_plugin_records WHERE record_id='target'");
      const logBeforeDelete = db.all('SELECT * FROM __change_log ORDER BY id');
      db.exec("DELETE FROM portable_plugin_records WHERE record_id='target'");
      const changes = db.all('SELECT * FROM __change_log ORDER BY id');
      assert.deepEqual(changes.slice(0, -1), logBeforeDelete);
      assert.equal(changes.length, logBeforeDelete.length + 1);
      assert.deepEqual([changes.at(-1).table_name, changes.at(-1).record_id, changes.at(-1).operation],
        ['portable_plugin_records', id, 'DELETE']);
      assert.equal(db.pluck('PRAGMA integrity_check'), 'ok');
      assert.equal(db.all('PRAGMA foreign_key_check').length, 0);
    } finally { db.close(); }
  }
  console.log('S2.04 desktop: frozen 138 + repair, symlink guard, fresh, old-head upgrade, restart, row/log preservation and DELETE passed');
} finally {
  hook.unregister();
  assert.deepEqual(installedIncrementIds(), []);
  rmSync(temp, { recursive: true, force: true });
}
