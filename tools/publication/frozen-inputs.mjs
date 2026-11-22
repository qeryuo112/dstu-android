import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const root = resolve(import.meta.dirname, '../..');
export const FROZEN_REF = '36511a1ba27ef2db7c45636162575a2b1c6564e8';
export const FROZEN_PLAN = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
export const MANIFEST_FILE = 'config/publication/s0-inputs.json';
export const MANIFEST_SHA256 = '4c48b4ee0471a4547dbebedbb77f4f4c6d4c30f058ad7e37fa72644247f95f55';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

export function readFrozenManifest(base = root) {
  const bytes = readFileSync(resolve(base, MANIFEST_FILE));
  assert.equal(hash(bytes), MANIFEST_SHA256, 'public frozen-input manifest changed');
  const manifest = JSON.parse(bytes);
  assert.equal(manifest.sourceRevision, FROZEN_REF);
  assert.equal(manifest.sourceInputs.length, 286);
  assert.equal(manifest.privateEvidence.length, 249);
  return manifest;
}

export function hasFrozenHistory(base = root) {
  return spawnSync('git', ['cat-file', '-e', `${FROZEN_REF}^{commit}`], { cwd: base, stdio: 'ignore' }).status === 0;
}

// Only the original planning SOURCE document is distributable. Never fall back to raw evidence.
export function readFrozenPlanningFile(ref, file, base = root) {
  assert.equal(ref, FROZEN_REF, 'unsupported historical revision');
  assert.equal(file, FROZEN_PLAN, 'only the frozen planning source is public');
  const manifest = readFrozenManifest(base);
  const expected = manifest.sourceInputs.find(entry => entry.path === file);
  const bytes = hasFrozenHistory(base)
    ? execFileSync('git', ['show', `${ref}:${file}`], { cwd: base, maxBuffer: 64 * 1024 * 1024 })
    : readFileSync(resolve(base, manifest.planningSnapshot.file));
  assert.equal(bytes.length, expected.size, 'frozen planning source size changed');
  assert.equal(hash(bytes), expected.sha256, 'frozen planning source bytes changed');
  return bytes;
}

export function verifyManifestInputs(manifest, { readLive, revisionVerifier, readPlan }) {
  let authorized = 0;
  for (const entry of manifest.sourceInputs) {
    const bytes = readLive(entry.path);
    if (entry.path === FROZEN_PLAN) {
      revisionVerifier({ oldBytes: readPlan(), liveBytes: bytes });
      authorized++;
    } else {
      assert.equal(bytes.length, entry.size, `frozen bytes changed: ${entry.path}`);
      assert.equal(hash(bytes), entry.sha256, `frozen bytes changed: ${entry.path}`);
    }
  }
  assert.equal(authorized, 1, 'authorized revision path missing');
  return { total: manifest.sourceInputs.length, historical: manifest.sourceInputs.length - 1, authorized };
}

export function verifyPrivateEvidenceIndex(expectedEntries, entries) {
  const indexed = new Map(entries.map(entry => [entry.path, entry]));
  for (const expected of expectedEntries) {
    const actual = indexed.get(expected.path);
    assert.ok(actual, `missing archived frozen evidence: ${expected.path}`);
    assert.equal(actual.sha256, expected.sha256, `archived frozen bytes changed: ${expected.path}`);
    assert.equal(actual.size, expected.size, `archived frozen size changed: ${expected.path}`);
  }
  return expectedEntries.length;
}

export function privateHistoryRepository(base = root, explicit = process.env.DSTU_PRIVATE_HISTORY) {
  const repository = explicit ? resolve(explicit) : base;
  assert.ok(hasFrozenHistory(repository),
    'Full S0 replay needs private history. Set DSTU_PRIVATE_HISTORY to your retained local repository; public source checks use npm run verify:repository / quality instead.');
  return repository;
}
