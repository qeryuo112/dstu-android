import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readFrozenManifest, hasFrozenHistory, readFrozenPlanningFile, verifyManifestInputs, verifyPrivateEvidenceIndex } from '../publication/frozen-inputs.mjs';
import { readIndex } from '../evidence/evidence-store.mjs';
import {
  CONFIG_FILE,
  FUNCTIONAL_PLAN,
  verifyAuthorizedPlanRevision
} from '../planning/verify-plan.mjs';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const S0_REF = '36511a1ba27ef2db7c45636162575a2b1c6564e8';
export const EXPECTED_FROZEN_INPUTS = 286;
export const AUTHORIZED_REVISION_PATH = FUNCTIONAL_PLAN;
export const frozenInputScopes = ['config', 'fixtures', 'desktop-increment', 'tools/baseline',
  'NOTICE', FUNCTIONAL_PLAN];
const git = (...args) => execFileSync('git', args, { cwd: root, maxBuffer: 64 * 1024 * 1024 });
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

export function verifyHistoricalInputs(paths, options) {
  const expectedCount = options.expectedCount ?? EXPECTED_FROZEN_INPUTS;
  assert.equal(paths.length, expectedCount, `frozen input count ${paths.length} !== ${expectedCount}`);
  let historical = 0;
  let authorized = 0;
  for (const path of paths) {
    if (path === options.authorizedPath) {
      options.revisionVerifier({
        oldBytes: options.readOld(path),
        liveBytes: options.readLive(path)
      });
      authorized++;
      continue;
    }
    assert.equal(hash(options.readLive(path)), hash(options.readOld(path)), `frozen bytes changed: ${path}`);
    historical++;
  }
  assert.equal(authorized, 1, `authorized revision path missing: ${options.authorizedPath}`);
  return { total: paths.length, historical, authorized };
}

// Raw evidence is archived, but its frozen S0 byte hashes remain enforced in the index.
export function verifyArchivedFrozenEvidence(paths, entries, readOld, expectedCount = 249) {
  assert.equal(paths.length, expectedCount, 'frozen archived evidence count');
  const indexed = new Map(entries.map(entry => [entry.path, entry]));
  for (const path of paths) {
    const entry = indexed.get(path);
    assert.ok(entry, `missing archived frozen evidence: ${path}`);
    const bytes = readOld(path);
    assert.equal(entry.sha256, hash(bytes), `archived frozen bytes changed: ${path}`);
    assert.equal(entry.size, bytes.length, `archived frozen size changed: ${path}`);
  }
  return paths.length;
}

export function verifyFrozenInputs() {
  // User-owned development rules are live instructions, never an immutable product baseline.
  const manifest = readFrozenManifest(root);
  const privateHistory = hasFrozenHistory(root);
  const paths = privateHistory ? git('ls-tree', '-r', '--name-only', S0_REF, '--', ...frozenInputScopes)
    .toString('utf8').trim().split('\n') : manifest.sourceInputs.map(entry => entry.path);
  assert.deepEqual(paths, manifest.sourceInputs.map(entry => entry.path), 'frozen source file set changed');
  const metadata = JSON.parse(readFileSync(resolve(root, CONFIG_FILE), 'utf8'));
  const queue = JSON.parse(readFileSync(resolve(root, metadata.frozenQueue.file), 'utf8'));
  const progress = JSON.parse(readFileSync(resolve(root, metadata.s2Receipt.file), 'utf8'));
  const historicalOptions = {
    authorizedPath: AUTHORIZED_REVISION_PATH,
    expectedCount: EXPECTED_FROZEN_INPUTS,
    readOld: path => git('show', `${S0_REF}:${path}`),
    readLive: path => readFileSync(resolve(root, path)),
    revisionVerifier: input => verifyAuthorizedPlanRevision({ ...input, metadata, queue, progress })
  };
  const result = privateHistory ? verifyHistoricalInputs(paths, historicalOptions) : verifyManifestInputs(manifest, {
    ...historicalOptions, readPlan: () => readFrozenPlanningFile(S0_REF, FUNCTIONAL_PLAN),
  });
  const archivedPaths = privateHistory ? git('ls-tree', '-r', '--name-only', S0_REF, '--', 'logs')
    .toString('utf8').trim().split('\n') : manifest.privateEvidence.map(entry => entry.path);
  assert.deepEqual(archivedPaths, manifest.privateEvidence.map(entry => entry.path), 'frozen evidence file set changed');
  const index = readIndex(resolve(root, 'docs/evidence/logs-index.json'));
  const archivedEvidence = privateHistory ? verifyArchivedFrozenEvidence(archivedPaths,
    index.entries, path => git('show', `${S0_REF}:${path}`)) : verifyPrivateEvidenceIndex(manifest.privateEvidence, index.entries);
  const lock = JSON.parse(readFileSync(resolve(root, 'config/source-lock.json'), 'utf8'));
  for (const source of lock.repositories) {
    const cwd = resolve(root, '..', source.path);
    const run = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
    assert.equal(run('rev-parse', 'HEAD'), source.commit, `${source.id}: frozen HEAD`);
    assert.equal(run('rev-parse', 'HEAD^{tree}'), source.tree, `${source.id}: frozen tree`);
    assert.equal(run('status', '--porcelain', '--untracked-files=no'), '', `${source.id}: tracked source drift`);
  }
  console.log(JSON.stringify({
    time: new Date().toISOString(),
    event: 's1.frozen-inputs.verified',
    owner: 'S1.01',
    ref: S0_REF,
    files: result.total,
    verificationSource: privateHistory ? "private-git-history" : "hash-pinned-public-manifest",
    archivedEvidence,
    historicalBytes: result.historical,
    authorizedRevision: result.authorized
  }));
  return result.total;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) verifyFrozenInputs();
