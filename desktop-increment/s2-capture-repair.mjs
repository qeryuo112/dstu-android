import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdtempSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { desktopRoot, installedIncrementIds, registerIncrement } from './loader.mjs';
import { installPluginDataIncrement, preparePluginDataMigrations } from './plugin-data.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const originalPath = 'config/schema/final-lock.json';
const originalSha = '5fd1b3a4bd4288ce577c3f17677c9ca6accfab56c03a80130a8797baed5c08e8';
const supplementPath = 'config/schema/s2-04-repair.json';
const supplementSha = 'ec1ce53307dd93c45584c55b7fc38c6e52a3b28b3a7fa5f0514b56c78f0b283c';
const sqlPath = 'config/schema/coordinated/vfs/V20261120__fix_plugin_delete_trigger.sql';
const relativeSql = 'vfs/V20261120__fix_plugin_delete_trigger.sql';
const definitionPath = 'host/platform/storage/migration/definitions/vfs.ts';
const definitionSha = '714c6bce5313baf493f2705319d46765ae3de51a1e5ece9c1f89d38a68d62d5e';
const sha = data => createHash('sha256').update(data).digest('hex');
const read = path => readFileSync(resolve(root, path));

function verifiedInputs() {
  const originalBytes = read(originalPath);
  const supplementBytes = read(supplementPath);
  assert.equal(sha(originalBytes), originalSha, 'S2.04: final lock drift');
  assert.equal(sha(supplementBytes), supplementSha, 'S2.04: supplement lock drift');
  const original = JSON.parse(originalBytes.toString('utf8'));
  const repair = JSON.parse(supplementBytes.toString('utf8'));
  assert.equal(repair.anchor.file, originalPath);
  assert.equal(repair.anchor.sha256, originalSha);
  assert.equal(repair.anchor.sqlSetSha256, original.sql.setSha256);
  assert.equal(repair.migration.file, sqlPath);
  assert.equal(repair.migration.database, 'vfs');
  assert.equal(repair.migration.version, 20261120);
  assert.equal(repair.migration.name, 'fix_plugin_delete_trigger');
  assert.equal(original.sql.files.length, 138);
  const sql = read(sqlPath);
  assert.equal(sql.length, repair.migration.bytes, 'S2.04: repair length');
  assert.equal(sha(sql), repair.migration.sha256, 'S2.04: repair SQL drift');
  for (const row of original.sql.files) {
    const bytes = read(row.file);
    assert.equal(bytes.length, row.bytes, `S2.04: original length ${row.file}`);
    assert.equal(sha(bytes), row.sha256, `S2.04: original SQL drift ${row.file}`);
  }
  return { original, repair };
}

function replaceOnce(text, from, to, label) {
  const index = text.indexOf(from);
  assert.ok(index >= 0, `S2.04: missing source shape ${label}`);
  assert.equal(text.indexOf(from, index + 1), -1, `S2.04: ambiguous source shape ${label}`);
  return text.slice(0, index) + to + text.slice(index + from.length);
}

/** Composes after S0.24 without touching the frozen desktop checkout. */
export function installCaptureRepairIncrement() {
  verifiedInputs();
  const owned = [];
  if (!installedIncrementIds().includes('portable-plugin-skill-data')) owned.push(installPluginDataIncrement());
  try {
    const handle = registerIncrement({
      unit: 'S2.04', id: 'capture-plugin-delete-repair',
      files: [{
        path: definitionPath, sha256: definitionSha,
        transform(text) {
          let output = replaceOnce(text,
            'export const V20261111_STUDY_SPACE_PROFILES_AND_MOUNTS: MigrationDefSpec = {',
            `export const V20261120_FIX_PLUGIN_DELETE_TRIGGER: MigrationDefSpec = {
  refineryVersion: 20261120,
  name: 'fix_plugin_delete_trigger',
  file: '${relativeSql}',
  expectedTables: ['portable_plugin_records'],
};

export const V20261111_STUDY_SPACE_PROFILES_AND_MOUNTS: MigrationDefSpec = {`,
            'repair definition');
          output = replaceOnce(output,
            '  V20261117_PLUGIN_SKILL_DATA_CONTRACT,\n];\n\nexport const VFS_SCHEMA_VERSION',
            '  V20261117_PLUGIN_SKILL_DATA_CONTRACT,\n  V20261120_FIX_PLUGIN_DELETE_TRIGGER,\n];\n\nexport const VFS_SCHEMA_VERSION',
            'repair list');
          return output;
        },
      }],
    });
    return {
      ...handle,
      composed: ['S0.21', 'S0.22', 'S0.23', 'S0.24', 'S2.04'],
      unregister() { handle.unregister(); for (const prior of owned.reverse()) prior.unregister(); },
    };
  } catch (error) {
    for (const prior of owned.reverse()) prior.unregister();
    throw error;
  }
}

function sqlFiles(directory, base = directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return sqlFiles(path, base);
    assert.ok(entry.isFile(), `S2.04: unexpected non-file in migrations: ${path}`);
    return entry.name.endsWith('.sql') ? [relative(base, path).split(sep).join('/')] : [];
  });
}

// Resolve existing symlinked ancestors too: never write through an alias into dstu-node.
function realOutputPath(path) {
  let ancestor = path;
  const tail = [];
  while (!existsSync(ancestor)) {
    const parent = dirname(ancestor);
    assert.notEqual(parent, ancestor, 'S2.04: output has no existing ancestor');
    tail.unshift(ancestor.slice(parent.length + (parent === sep ? 0 : 1)));
    ancestor = parent;
  }
  return resolve(realpathSync(ancestor), ...tail);
}

/** Copy 135 original + three S0 coordinated files, then the one approved repair. */
export function prepareCaptureRepairMigrations(parent = undefined) {
  const { original, repair } = verifiedInputs();
  const directory = parent === undefined ? mkdtempSync(join(tmpdir(), 'dstu-s204-migrations-')) : resolve(parent);
  const withinDesktop = relative(realpathSync(desktopRoot), realOutputPath(directory));
  assert.ok(withinDesktop.startsWith('..' + sep) || withinDesktop === '..', 'S2.04: output cannot be in desktop checkout');
  assert.ok(!existsSync(directory) || readdirSync(directory).length === 0, 'S2.04: output must be empty');
  preparePluginDataMigrations(directory);
  const expected = new Map(original.sql.files.map(row => {
    const suffix = row.origin === 'coordinated'
      ? row.file.slice('config/schema/coordinated/'.length)
      : row.file.slice('config/schema/history/'.length);
    return [suffix, row];
  }));
  assert.equal(expected.size, 138);
  assert.deepEqual(sqlFiles(directory).sort(), [...expected.keys()].sort(), 'S2.04: prepared original SQL set');
  for (const [path, row] of expected) {
    const bytes = readFileSync(join(directory, path));
    assert.equal(bytes.length, row.bytes, `S2.04: prepared length ${path}`);
    assert.equal(sha(bytes), row.sha256, `S2.04: prepared SQL drift ${path}`);
  }
  cpSync(resolve(root, sqlPath), join(directory, relativeSql));
  const copied = readFileSync(join(directory, relativeSql));
  assert.equal(copied.length, repair.migration.bytes);
  assert.equal(sha(copied), repair.migration.sha256);
  assert.equal(sqlFiles(directory).length, 139);
  return directory;
}
