import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import {
  assertSafeEvidencePath,
  createEvidenceArchive,
  pruneWorktreeEvidence,
  readIndex,
  restoreEntries,
  verifyArchive,
} from './evidence-store.mjs';

const checkScript = fileURLToPath(new URL('./check-logs.mjs', import.meta.url));

function git(root, ...args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
}

function fixture() {
  const base = mkdtempSync(join(tmpdir(), 'dstu-evidence-test-'));
  const repo = join(base, 'repo');
  mkdirSync(join(repo, 'logs'), { recursive: true });
  writeFileSync(join(repo, '.gitignore'), '/logs/\n');
  writeFileSync(join(repo, 'logs', 'a.log'), 'alpha\n');
  writeFileSync(join(repo, 'logs', 'deleted.png'), 'deleted png bytes\n');
  writeFileSync(join(repo, 'logs', 'c.json'), '{"ok":true}\n');
  git(repo, 'init', '-q');
  git(repo, 'config', 'user.email', 'evidence@example.invalid');
  git(repo, 'config', 'user.name', 'Evidence Test');
  git(repo, 'add', '.');
  git(repo, 'add', '-f', 'logs');
  git(repo, 'commit', '-q', '-m', 'fixture');
  return { base, repo, artifactRoot: join(base, 'artifacts') };
}

function archive(f) {
  return createEvidenceArchive({
    root: f.repo,
    artifactRoot: f.artifactRoot,
    archiveId: 'logs-test',
    generatedAt: '2026-11-23T00:00:00.000Z',
    indexFile: 'docs/evidence/logs-index.json',
  });
}

test('archives HEAD blobs including deleted worktree files and restores idempotently', t => {
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  rmSync(join(f.repo, 'logs', 'deleted.png'));
  const result = archive(f);
  assert.equal(result.trackedCount, 3);
  assert.deepEqual(result.missingFromWorktree, ['logs/deleted.png']);
  const index = readIndex(result.indexFile);
  assert.equal(index.entries.length, 3);
  assert.equal(index.entries.find(entry => entry.path === 'logs/deleted.png').worktree, 'missing');
  assert.equal(verifyArchive({
    archivePath: result.archivePath,
    archiveSha256: index.archive.checksum.value,
    archiveSize: index.archive.size,
    entries: index.entries,
  }).entries, 3);
  const outputRoot = join(f.base, 'restore');
  const first = restoreEntries({
    root: f.repo,
    index,
    archivePath: result.archivePath,
    paths: ['logs/deleted.png'],
    outputRoot,
  });
  assert.deepEqual(first.restored, ['logs/deleted.png']);
  assert.equal(readFileSync(join(outputRoot, 'logs', 'deleted.png'), 'utf8'), 'deleted png bytes\n');
  const second = restoreEntries({
    root: f.repo,
    index,
    archivePath: result.archivePath,
    paths: ['logs/deleted.png'],
    outputRoot,
  });
  assert.deepEqual(second.restored, []);
  assert.deepEqual(second.skipped, ['logs/deleted.png']);
});

test('archives ignored worktree-only evidence after historical paths leave HEAD', t => {
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  git(f.repo, 'rm', '--cached', '-r', 'logs');
  writeFileSync(join(f.repo, 'logs', 'new-local.log'), 'new local evidence\n');
  const result = createEvidenceArchive({
    root: f.repo,
    artifactRoot: f.artifactRoot,
    archiveId: 'logs-worktree',
    generatedAt: '2026-11-23T00:00:00.000Z',
    indexFile: 'docs/evidence/logs-index-worktree.json',
    source: 'worktree',
  });
  const index = readIndex(result.indexFile);
  assert.equal(result.entryCount, 4);
  assert.equal(result.trackedCount, 0);
  assert.ok(index.entries.every(entry => entry.tracked === false));
  assert.equal(index.entries.find(entry => entry.path === 'logs/new-local.log').size, 19);
  assert.equal(verifyArchive({
    archivePath: result.archivePath,
    archiveSha256: index.archive.checksum.value,
    archiveSize: index.archive.size,
    entries: index.entries,
  }).entries, 4);
});

test('does not overwrite an existing default index', t => {
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  const first = createEvidenceArchive({
    root: f.repo,
    artifactRoot: f.artifactRoot,
    archiveId: 'logs-first',
    generatedAt: '2026-11-23T00:00:00.000Z',
    source: 'worktree',
  });
  const second = createEvidenceArchive({
    root: f.repo,
    artifactRoot: f.artifactRoot,
    archiveId: 'logs-second',
    generatedAt: '2026-11-23T00:00:01.000Z',
    source: 'worktree',
  });
  assert.equal(first.indexFile, join(f.repo, 'docs/evidence/logs-index.json'));
  assert.equal(second.indexFile, join(f.repo, 'docs/evidence/logs-index-logs-second.json'));
  assert.notEqual(first.indexFile, second.indexFile);
  git(f.repo, 'rm', '--cached', '-r', 'logs');
  const check = spawnSync(process.execPath, [checkScript, '--root', f.repo], { encoding: 'utf8' });
  assert.equal(check.status, 0, check.stderr);
  const summary = JSON.parse(check.stdout);
  assert.equal(summary.indexFiles.length, 2);
  assert.equal(summary.indexedEntries, 6);
});

test('rejects archive tampering and indexed hash tampering', t => {
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  const result = archive(f);
  const index = readIndex(result.indexFile);
  const tampered = structuredClone(index);
  tampered.entries[0].sha256 = '0'.repeat(64);
  assert.throws(() => verifyArchive({
    archivePath: result.archivePath,
    archiveSha256: index.archive.checksum.value,
    archiveSize: index.archive.size,
    entries: tampered.entries,
  }), /SHA-256 mismatch/u);
  const original = readFileSync(result.archivePath);
  writeFileSync(result.archivePath, Buffer.concat([original, Buffer.from('tamper')]));
  assert.throws(() => verifyArchive({
    archivePath: result.archivePath,
    archiveSha256: index.archive.checksum.value,
    archiveSize: index.archive.size,
    entries: index.entries,
  }), /size mismatch/u);
});

test('rejects missing archive and unindexed restore paths', t => {
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  const result = archive(f);
  const index = readIndex(result.indexFile);
  assert.throws(() => verifyArchive({
    archivePath: join(f.base, 'missing.tar.gz'),
    archiveSha256: index.archive.checksum.value,
    archiveSize: index.archive.size,
    entries: index.entries,
  }), /archive is missing/u);
  assert.throws(() => restoreEntries({
    root: f.repo,
    index,
    archivePath: result.archivePath,
    paths: ['logs/not-indexed.log'],
    outputRoot: join(f.base, 'restore'),
  }), /not present in index/u);
});

test('rejects traversal, absolute paths and symlinked output directories', t => {
  assert.throws(() => assertSafeEvidencePath('../escape.log'), /traversal|logs\//u);
  assert.throws(() => assertSafeEvidencePath('/absolute.log'), /absolute|logs\//u);
  assert.throws(() => assertSafeEvidencePath('logs\\..\\escape.log'), /backslash/u);
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  const result = archive(f);
  const index = readIndex(result.indexFile);
  const outputRoot = join(f.base, 'output');
  const outside = join(f.base, 'outside');
  mkdirSync(outputRoot);
  mkdirSync(outside);
  symlinkSync(outside, join(outputRoot, 'logs'), 'dir');
  assert.throws(() => restoreEntries({
    root: f.repo,
    index,
    archivePath: result.archivePath,
    paths: ['logs/a.log'],
    outputRoot,
  }), /symlink/u);
  assert.throws(() => createEvidenceArchive({
    root: f.repo,
    artifactRoot: join(f.repo, 'artifacts'),
    archiveId: 'inside',
  }), /outside the repository/u);
});

test('prunes only verified worktree files and is idempotent', t => {
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  const result = createEvidenceArchive({
    root: f.repo,
    artifactRoot: f.artifactRoot,
    archiveId: 'logs-prune',
    generatedAt: '2026-11-23T00:00:00.000Z',
    indexFile: 'docs/evidence/logs-index.json',
    pruneWorktree: true,
  });
  assert.deepEqual(result.prune, { removed: 3, skippedMissing: 0 });
  assert.equal(existsSync(join(f.repo, 'logs')), false);
  const second = pruneWorktreeEvidence(f.repo, readIndex(result.indexFile).entries);
  assert.deepEqual(second, { removed: 0, skippedMissing: 3 });
});

test('CI check rejects tracked logs and accepts an index after untracking', t => {
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  const result = archive(f);
  const before = spawnSync(process.execPath, [checkScript, '--root', f.repo, '--index', result.indexFile], {
    encoding: 'utf8',
  });
  assert.equal(before.status, 1, before.stderr);
  assert.match(before.stderr, /raw evidence logs are tracked/u);
  git(f.repo, 'rm', '--cached', '-r', 'logs');
  const after = spawnSync(process.execPath, [checkScript, '--root', f.repo, '--index', result.indexFile], {
    encoding: 'utf8',
  });
  assert.equal(after.status, 0, after.stderr);
  assert.match(after.stdout, /"trackedLogs":0/u);
});

test('historical replay restores on demand, cleans on failure and preserves new evidence', async t => {
  const { replayWithEvidence } = await import('./replay.mjs');
  const f = fixture();
  t.after(() => rmSync(f.base, { recursive: true, force: true }));
  const result = archive(f);
  const index = readIndex(result.indexFile);
  pruneWorktreeEvidence(f.repo, index.entries);
  const options = { root: f.repo, index, archivePath: result.archivePath };
  assert.throws(() => replayWithEvidence(() => {
    assert.equal(readFileSync(join(f.repo, 'logs/a.log'), 'utf8'), 'alpha\n');
    throw new Error('validator failed');
  }, options), /validator failed/);
  assert.equal(existsSync(join(f.repo, 'logs/a.log')), false);
  replayWithEvidence(() => writeFileSync(join(f.repo, 'logs/a.log'), 'new local evidence\n'), options);
  assert.equal(readFileSync(join(f.repo, 'logs/a.log'), 'utf8'), 'new local evidence\n');
  replayWithEvidence(() => assert.equal(readFileSync(join(f.repo, 'logs/a.log'), 'utf8'), 'new local evidence\n'), options);
  assert.equal(readFileSync(join(f.repo, 'logs/a.log'), 'utf8'), 'new local evidence\n');
  assert.equal(existsSync(join(f.repo, 'logs/c.json')), false);
});
