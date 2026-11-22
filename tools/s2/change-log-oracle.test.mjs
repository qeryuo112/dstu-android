// Host-only verification of the oracle document; this does not stand in for Android instrumentation.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const oracle = resolve(root, 'tools/s2/change-log-oracle.mjs');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const run = (...args) => spawnSync(process.execPath,
  ['--disable-warning=ExperimentalWarning', '--experimental-strip-types', oracle, ...args],
  { cwd: root, encoding: 'utf8' });

test('repaired desktop oracle is deterministic and preserves all original changes', () => {
  const temp = mkdtempSync(join(tmpdir(), 'dstu-s205-oracle-test-'));
  try {
    const first = join(temp, 'first.json');
    const second = join(temp, 'second.json');
    const a = run(first);
    const b = run(second);
    assert.equal(a.status, 0, a.stderr);
    assert.equal(b.status, 0, b.stderr);
    assert.deepEqual(readFileSync(first), readFileSync(second));
    const doc = JSON.parse(readFileSync(first, 'utf8'));
    const summary = JSON.parse(a.stdout);
    assert.equal(summary.fixtureSha256, sha(readFileSync(first)));
    assert.equal(doc.migration.version, 20261120);
    assert.equal(doc.migration.supplementLockSha256, 'ec1ce53307dd93c45584c55b7fc38c6e52a3b28b3a7fa5f0514b56c78f0b283c');
    assert.equal(doc.migration.checksum, '17799307631992323381');
    assert.deepEqual(doc.scenarios.map(s => [s.database, s.table, s.expectedChanges.length]), [
      ['vfs', 'resources', 7], ['chat_v2', 'chat_v2_session_mistakes', 4], ['vfs', 'portable_plugin_records', 7],
    ]);
    assert.deepEqual(doc.scenarios.slice(0, 2).map(s => sha(s.expectedChanges.join('\n'))), [
      '29b2437839d7257ecd8ee664dcc77c2c13d9a651c6fe35d7a2f5defe5fddbec9',
      'fa295a95025d2f9c9a5bfe79b00858560e466e935c9b9757913b58a99c60954e',
    ]);
    const raw = doc.scenarios[2].expectedChanges;
    const changes = raw.map(row => JSON.parse(row));
    const id = '["plugin.s2","state","reinsert"]';
    const removed = '["plugin.s2","state","removed"]';
    assert.deepEqual(changes.map(change => change.operation), ['Insert', 'Update', 'Delete', 'Insert', 'Update', 'Insert', 'Delete']);
    assert.deepEqual(changes.map(change => change.record_id), [id, id, id, id, id, removed, removed]);
    // JSON.parse cannot preserve these integers, so inspect their original decimal tokens.
    assert.deepEqual(raw.map(row => row.match(/"change_log_id":(\d+)/u)[1]),
      Array.from({ length: 7 }, (_, i) => (9007199254740993n + BigInt(i)).toString()));
    assert.ok([0, 1, 3, 4].every(i => raw[i].includes('"local_version":9007199254740993')));
    assert.ok([2, 5, 6].every(i => changes[i].data === null));
    assert.ok(doc.scenarios[2].statements.some(sql => /^DELETE FROM portable_plugin_records\b/u.test(sql)));
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});

test('oracle refuses to overwrite a pre-existing output', () => {
  const temp = mkdtempSync(join(tmpdir(), 'dstu-s205-oracle-protected-'));
  try {
    const existing = join(temp, 'existing.json');
    writeFileSync(existing, 'untouched');
    const result = run(existing);
    assert.notEqual(result.status, 0);
    assert.equal(readFileSync(existing, 'utf8'), 'untouched');
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
