import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { test } from 'node:test';
import { FROZEN_REF, FROZEN_PLAN, MANIFEST_FILE, root, readFrozenManifest,
  readFrozenPlanningFile, hasFrozenHistory, verifyManifestInputs, verifyPrivateEvidenceIndex,
  privateHistoryRepository } from './frozen-inputs.mjs';
function fixture(t) {
  const dir = mkdtempSync(resolve(tmpdir(), 'dstu-public-frozen-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  mkdirSync(resolve(dir, 'config/publication'), { recursive: true });
  for (const file of [MANIFEST_FILE, 'config/publication/s0-functional-plan.md']) cpSync(resolve(root, file), resolve(dir, file));
  return dir;
}
test('public planning source works with no historical Git objects', t => {
  const dir = fixture(t);
  assert.equal(hasFrozenHistory(dir), false);
  assert.deepEqual(readFrozenPlanningFile(FROZEN_REF, FROZEN_PLAN, dir), readFrozenPlanningFile(FROZEN_REF, FROZEN_PLAN, root));
  assert.throws(() => readFrozenPlanningFile(FROZEN_REF, 'logs/private.log', dir), /only the frozen planning source/u);
  assert.throws(() => readFrozenPlanningFile('HEAD', FROZEN_PLAN, dir), /unsupported historical revision/u);
  assert.throws(() => privateHistoryRepository(dir, ''), /needs private history/u);
});
test('changed frozen plan and changed hash manifest fail closed', t => {
  const dir = fixture(t);
  const file = resolve(dir, 'config/publication/s0-functional-plan.md');
  const bytes = readFileSync(file); bytes[0] ^= 1; writeFileSync(file, bytes);
  assert.throws(() => readFrozenPlanningFile(FROZEN_REF, FROZEN_PLAN, dir), /bytes changed/u);
  writeFileSync(resolve(dir, MANIFEST_FILE), '{}');
  assert.throws(() => readFrozenManifest(dir), /manifest changed/u);
});
test('manifest-only verification preserves source and private-index hash checks', () => {
  const bytes = Buffer.from('source');
  const entry = { path: 'config/source.json', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
  const manifest = { sourceInputs: [entry, { path: FROZEN_PLAN }] };
  let revisions = 0;
  const options = { readLive: () => bytes, readPlan: () => bytes, revisionVerifier: () => revisions++ };
  assert.deepEqual(verifyManifestInputs(manifest, options), { total: 2, historical: 1, authorized: 1 });
  assert.equal(revisions, 1);
  assert.throws(() => verifyManifestInputs(manifest, { ...options, readLive: () => Buffer.from('changed') }), /frozen bytes changed/u);
  assert.equal(verifyPrivateEvidenceIndex([entry], [entry]), 1);
  assert.throws(() => verifyPrivateEvidenceIndex([entry], []), /missing archived/u);
  assert.throws(() => verifyPrivateEvidenceIndex([entry], [{ ...entry, sha256: '0'.repeat(64) }]), /bytes changed/u);
});
