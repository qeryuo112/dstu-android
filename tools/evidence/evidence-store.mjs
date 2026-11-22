import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

export const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const INDEX_FORMAT = 'dstu-evidence-index/v1';
export const MANIFEST_FORMAT = 'dstu-evidence-manifest/v1';
export const DEFAULT_INDEX = 'docs/evidence/logs-index.json';
export const ARCHIVE_FILE = 'evidence.tar.gz';
export const STORAGE_NAME = ARCHIVE_FILE;
const SHA256 = /^[a-f0-9]{64}$/u;
const MAX_GIT_BUFFER = 256 * 1024 * 1024;
const MAX_TAR_BUFFER = 64 * 1024 * 1024;

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

export function sha256File(path) {
  return sha256(readFileSync(path));
}

export function defaultArtifactRoot(root = repoRoot) {
  return resolve(root, '..', 'artifacts', 'android-evidence');
}

export function assertSafeRelativePath(value, label = 'path') {
  assert.equal(typeof value, 'string', `${label}: string required`);
  assert.ok(value.length > 0, `${label}: non-empty path required`);
  assert.ok(!value.includes('\0'), `${label}: NUL is not allowed`);
  assert.ok(!/[\u0000-\u001f\u007f]/u.test(value), `${label}: control characters are not allowed`);
  assert.ok(!value.includes('\\'), `${label}: backslash is not allowed`);
  assert.ok(!isAbsolute(value) && !/^[A-Za-z]:/u.test(value), `${label}: absolute path is not allowed`);
  const segments = value.split('/');
  assert.ok(segments.every(segment => segment !== '' && segment !== '.' && segment !== '..'),
    `${label}: traversal or empty segment is not allowed`);
  return value;
}

export function assertSafeEvidencePath(value, label = 'evidence path') {
  assertSafeRelativePath(value, label);
  assert.ok(value.startsWith('logs/'), `${label}: must start with logs/`);
  return value;
}

export function assertOutsideRepo(root, target) {
  const rel = relative(resolve(root), resolve(target));
  assert.ok(rel !== '' && (rel === '..' || rel.startsWith(`..${sep}`)),
    `artifact path must be outside the repository: ${target}`);
  return resolve(target);
}

export function assertSafeOutputPath(root, value) {
  assertSafeRelativePath(value, 'output path');
  const rootAbs = resolve(root);
  const target = resolve(rootAbs, value);
  const rel = relative(rootAbs, target);
  assert.ok(rel !== '' && rel !== '..' && !rel.startsWith(`..${sep}`) && !isAbsolute(rel),
    `output path escapes output root: ${value}`);
  if (existsSync(rootAbs)) {
    const rootStat = lstatSync(rootAbs);
    assert.ok(rootStat.isDirectory() && !rootStat.isSymbolicLink(), `output root is not a real directory: ${rootAbs}`);
  }
  let cursor = rootAbs;
  for (const segment of value.split('/')) {
    cursor = join(cursor, segment);
    if (!existsSync(cursor)) break;
    const stat = lstatSync(cursor);
    assert.ok(!stat.isSymbolicLink(), `output path contains a symlink: ${relative(rootAbs, cursor)}`);
    if (cursor !== target) assert.ok(stat.isDirectory(), `output path parent is not a directory: ${relative(rootAbs, cursor)}`);
  }
  if (existsSync(target)) assert.ok(lstatSync(target).isFile(), `output path is not a regular file: ${value}`);
  return target;
}

function git(root, args, options = {}) {
  return execFileSync('git', args, { cwd: root, maxBuffer: MAX_GIT_BUFFER, ...options });
}

function gitText(root, args) {
  return git(root, args, { encoding: 'utf8' }).trim();
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function writeJsonAtomic(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}-${Date.now()}`;
  writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o644 });
  renameSync(temporary, path);
}

function walkFiles(root, base = root) {
  const files = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const full = join(root, entry.name);
    if (entry.isDirectory()) files.push(...walkFiles(full, base));
    else if (entry.isFile()) files.push(relative(base, full).split(sep).join('/'));
    else throw new Error(`unsupported extracted entry type: ${relative(base, full)}`);
  }
  return files.sort();
}

function validateArchiveId(value) {
  assert.match(value, /^[A-Za-z0-9][A-Za-z0-9._-]*$/u, 'archive id must be a safe directory name');
  assert.ok(value !== '.' && value !== '..', 'archive id must not be . or ..');
  return value;
}

function listTrackedLogs(root, ref) {
  const raw = git(root, ['ls-tree', '-r', '-l', '-z', ref, '--', 'logs'], { encoding: 'utf8' });
  const records = raw.split('\0').filter(Boolean);
  const entries = records.map(record => {
    const match = /^(\d{6}) blob ([0-9a-f]+) +(\d+)\t(.+)$/su.exec(record);
    assert.ok(match, `unexpected git ls-tree record: ${record}`);
    const [, mode, gitBlob, sizeText, path] = match;
    assert.equal(mode, '100644', `unsupported evidence mode ${mode}: ${path}`);
    assertSafeEvidencePath(path);
    return { path, mode, gitBlob, size: Number(sizeText) };
  });
  return entries.sort((left, right) => left.path.localeCompare(right.path));
}

function listWorktreeLogs(root) {
  const logs = resolve(root, 'logs');
  if (!existsSync(logs)) return [];
  const stat = lstatSync(logs);
  assert.ok(stat.isDirectory() && !stat.isSymbolicLink(), 'logs/ must be a real directory');
  const files = [];
  const visit = directory => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const full = join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(full);
        continue;
      }
      assert.ok(entry.isFile(), `unsupported worktree evidence type: ${relative(root, full)}`);
      const path = relative(root, full).split(sep).join('/');
      assertSafeEvidencePath(path);
      const bytes = readFileSync(full);
      files.push({ path, size: bytes.length, sha256: sha256(bytes), bytes });
    }
  };
  visit(logs);
  return files.sort((left, right) => left.path.localeCompare(right.path));
}

function readGitBlobs(root, entries) {
  if (entries.length === 0) return new Map();
  const output = git(root, ['cat-file', '--batch'], {
    input: `${entries.map(entry => entry.gitBlob).join('\n')}\n`,
  });
  let offset = 0;
  const blobs = new Map();
  for (const entry of entries) {
    const newline = output.indexOf(0x0a, offset);
    assert.ok(newline >= 0, `truncated git cat-file header for ${entry.path}`);
    const header = output.subarray(offset, newline).toString('utf8');
    const match = /^([0-9a-f]+) blob (\d+)$/u.exec(header);
    assert.ok(match, `unexpected git cat-file header for ${entry.path}: ${header}`);
    const size = Number(match[2]);
    const start = newline + 1;
    const end = start + size;
    assert.ok(end <= output.length, `truncated git blob for ${entry.path}`);
    const bytes = output.subarray(start, end);
    assert.equal(bytes.length, entry.size, `git blob size mismatch for ${entry.path}`);
    blobs.set(entry.path, { bytes, sha256: sha256(bytes) });
    offset = end + 1;
  }
  return blobs;
}

function inspectWorktree(root, entry, expectedSha256) {
  const full = resolve(root, entry.path);
  if (!existsSync(full)) return 'missing';
  const stat = lstatSync(full);
  assert.ok(stat.isFile(), `worktree evidence is not a regular file: ${entry.path}`);
  const bytes = readFileSync(full);
  assert.equal(bytes.length, entry.size, `worktree evidence size differs from HEAD: ${entry.path}`);
  assert.equal(sha256(bytes), expectedSha256, `worktree evidence differs from HEAD: ${entry.path}`);
  return 'present';
}

function collectEntries(root, ref, source) {
  const includeHead = source === 'head' || source === 'both';
  const includeWorktree = source === 'worktree' || source === 'both';
  const tracked = includeHead ? listTrackedLogs(root, ref) : [];
  const blobs = readGitBlobs(root, tracked);
  const worktree = includeWorktree ? listWorktreeLogs(root) : [];
  const worktreeByPath = new Map(worktree.map(entry => [entry.path, entry]));
  const entries = [];
  const files = new Map();
  const trackedPaths = new Set();
  for (const trackedEntry of tracked) {
    const blob = blobs.get(trackedEntry.path);
    const local = worktreeByPath.get(trackedEntry.path);
    let worktreeState;
    let bytes;
    if (local) {
      assert.equal(local.size, trackedEntry.size, `worktree evidence size differs from HEAD: ${trackedEntry.path}`);
      assert.equal(local.sha256, blob.sha256, `worktree evidence differs from HEAD: ${trackedEntry.path}`);
      worktreeState = 'present';
      bytes = local.bytes;
    } else if (includeWorktree) {
      worktreeState = 'missing';
      bytes = blob.bytes;
    } else {
      worktreeState = inspectWorktree(root, trackedEntry, blob.sha256);
      bytes = blob.bytes;
    }
    entries.push({
      path: trackedEntry.path,
      size: trackedEntry.size,
      sha256: blob.sha256,
      gitBlob: trackedEntry.gitBlob,
      storage: STORAGE_NAME,
      worktree: worktreeState,
      tracked: true,
    });
    files.set(trackedEntry.path, bytes);
    trackedPaths.add(trackedEntry.path);
  }
  for (const local of worktree) {
    if (trackedPaths.has(local.path)) continue;
    entries.push({
      path: local.path,
      size: local.size,
      sha256: local.sha256,
      storage: STORAGE_NAME,
      worktree: 'present',
      tracked: false,
    });
    files.set(local.path, local.bytes);
  }
  entries.sort((left, right) => left.path.localeCompare(right.path));
  assert.ok(entries.length > 0, `no evidence found under logs/ (source=${source})`);
  return {
    entries,
    files,
    missingFromWorktree: entries.filter(entry => entry.worktree === 'missing').map(entry => entry.path),
  };
}

function tarNames(archivePath) {
  const raw = execFileSync('tar', ['-tzf', archivePath], { encoding: 'utf8', maxBuffer: MAX_TAR_BUFFER });
  return raw.split('\n').filter(Boolean);
}

function validateTarTypes(archivePath) {
  const raw = execFileSync('tar', ['-tvzf', archivePath], { encoding: 'utf8', maxBuffer: MAX_TAR_BUFFER });
  for (const line of raw.split('\n').filter(Boolean)) {
    assert.ok(line[0] === '-' || line[0] === 'd', `unsupported tar entry type: ${line}`);
  }
}

function validateTarEntries(archivePath, entries) {
  validateTarTypes(archivePath);
  const expected = new Set(entries.map(entry => entry.path));
  const seen = new Set();
  for (const name of tarNames(archivePath)) {
    const directory = name.endsWith('/');
    const path = directory ? name.slice(0, -1) : name;
    if (directory) {
      assertSafeRelativePath(path, 'archive directory');
      assert.equal(path, 'logs', `unexpected archive directory: ${name}`);
      continue;
    }
    assertSafeEvidencePath(path, 'archive entry');
    assert.ok(!seen.has(path), `duplicate archive entry: ${path}`);
    seen.add(path);
    assert.ok(expected.has(path), `archive contains an unindexed file: ${path}`);
  }
  for (const path of expected) assert.ok(seen.has(path), `archive is missing indexed file: ${path}`);
  assert.equal(seen.size, expected.size, 'archive entry count mismatch');
}

function extractVerifiedArchive({ archivePath, archiveSha256, archiveSize, entries }) {
  assert.ok(existsSync(archivePath), `archive is missing: ${archivePath}`);
  const stat = statSync(archivePath);
  assert.equal(stat.size, archiveSize, `archive size mismatch: ${archivePath}`);
  assert.equal(sha256File(archivePath), archiveSha256, `archive SHA-256 mismatch: ${archivePath}`);
  validateTarEntries(archivePath, entries);
  const temporary = mkdtempSync(join(tmpdir(), 'dstu-evidence-verify-'));
  try {
    execFileSync('tar', ['-xzf', archivePath, '-C', temporary, '--no-same-owner', '--no-same-permissions'], {
      stdio: 'pipe',
      maxBuffer: MAX_TAR_BUFFER,
    });
    const extracted = new Set(walkFiles(temporary));
    const expected = new Set(entries.map(entry => entry.path));
    for (const path of expected) assert.ok(extracted.has(path), `archive extraction is missing: ${path}`);
    for (const path of extracted) assert.ok(expected.has(path), `archive extraction has extra file: ${path}`);
    for (const entry of entries) {
      const full = resolve(temporary, entry.path);
      const fileStat = lstatSync(full);
      assert.ok(fileStat.isFile(), `archive entry is not a regular file: ${entry.path}`);
      assert.equal(fileStat.size, entry.size, `archive entry size mismatch: ${entry.path}`);
      assert.equal(sha256File(full), entry.sha256, `archive entry SHA-256 mismatch: ${entry.path}`);
    }
    return {
      temporary,
      report: {
        archivePath,
        archiveSha256,
        archiveSize,
        entries: entries.length,
        verifiedAt: new Date().toISOString(),
      },
    };
  } catch (error) {
    rmSync(temporary, { recursive: true, force: true });
    throw error;
  }
}

export function verifyArchive({ archivePath, archiveSha256, archiveSize, entries }) {
  const extracted = extractVerifiedArchive({ archivePath, archiveSha256, archiveSize, entries });
  try {
    return extracted.report;
  } finally {
    rmSync(extracted.temporary, { recursive: true, force: true });
  }
}

export function validateIndex(index) {
  assert.ok(index !== null && typeof index === 'object' && !Array.isArray(index), 'index: object required');
  assert.equal(index.format, INDEX_FORMAT, 'index: unsupported format');
  assert.ok(index.archive !== null && typeof index.archive === 'object', 'index: archive required');
  assertSafeRelativePath(index.archive.relativeLocation, 'index archive relativeLocation');
  assert.ok(typeof index.archive.location === 'string' && index.archive.location.length > 0, 'index archive location required');
  assert.ok(Number.isSafeInteger(index.archive.size) && index.archive.size >= 0, 'index archive size invalid');
  assert.match(index.archive.checksum?.value ?? '', SHA256, 'index archive checksum invalid');
  assert.equal(index.archive.checksum?.algorithm, 'sha256', 'index archive checksum algorithm must be sha256');
  assertSafeRelativePath(index.archive.manifest?.relativeLocation, 'index manifest relativeLocation');
  assert.match(index.archive.manifest?.checksum?.value ?? '', SHA256, 'index manifest checksum invalid');
  assert.ok(Array.isArray(index.entries) && index.entries.length > 0, 'index entries required');
  const seen = new Set();
  for (const entry of index.entries) {
    assertSafeEvidencePath(entry.path, 'index entry path');
    assert.ok(!seen.has(entry.path), `index duplicate path: ${entry.path}`);
    seen.add(entry.path);
    assert.ok(Number.isSafeInteger(entry.size) && entry.size >= 0, `index entry size invalid: ${entry.path}`);
    assert.match(entry.sha256 ?? '', SHA256, `index entry SHA-256 invalid: ${entry.path}`);
    assert.equal(entry.storage, STORAGE_NAME, `index entry storage invalid: ${entry.path}`);
    assert.ok(entry.worktree === 'present' || entry.worktree === 'missing', `index entry worktree state invalid: ${entry.path}`);
    if (entry.tracked !== undefined) assert.equal(typeof entry.tracked, 'boolean', `index entry tracked flag invalid: ${entry.path}`);
    if (entry.gitBlob !== undefined) assert.match(entry.gitBlob, /^[0-9a-f]+$/u, `index entry git blob invalid: ${entry.path}`);
    if (entry.tracked === true) assert.match(entry.gitBlob ?? '', /^[0-9a-f]+$/u, `tracked index entry missing git blob: ${entry.path}`);
  }
  return index;
}

export function readIndex(indexFile) {
  return validateIndex(readJson(indexFile));
}

export function resolveIndexArchive(index, options = {}) {
  if (options.archivePath) return resolve(options.archivePath);
  const artifactRoot = options.artifactRoot ?? process.env.DSTU_EVIDENCE_ROOT;
  if (artifactRoot) return resolve(artifactRoot, index.archive.relativeLocation);
  return resolve(index.archive.location);
}

export function pruneWorktreeEvidence(root, entries) {
  const present = entries.filter(entry => entry.worktree === 'present');
  const targets = [];
  let skippedMissing = 0;
  for (const entry of present) {
    const target = assertSafeOutputPath(root, entry.path);
    if (!existsSync(target)) {
      skippedMissing++;
      continue;
    }
    const stat = lstatSync(target);
    assert.ok(stat.isFile(), `worktree evidence is not a regular file: ${entry.path}`);
    const bytes = readFileSync(target);
    assert.equal(bytes.length, entry.size, `worktree evidence size changed before prune: ${entry.path}`);
    assert.equal(sha256(bytes), entry.sha256, `worktree evidence changed before prune: ${entry.path}`);
    targets.push(target);
  }
  for (const target of targets) rmSync(target);
  const logs = resolve(root, 'logs');
  if (existsSync(logs) && readdirSync(logs).length === 0) rmdirSync(logs);
  return { removed: targets.length, skippedMissing };
}

export function restoreEntries({
  root = repoRoot,
  index,
  archivePath,
  paths,
  outputRoot = root,
  force = false,
}) {
  const validated = validateIndex(index);
  const selected = paths?.length ? paths : validated.entries.map(entry => entry.path);
  const byPath = new Map(validated.entries.map(entry => [entry.path, entry]));
  const entries = selected.map(path => {
    assertSafeEvidencePath(path, 'restore path');
    const entry = byPath.get(path);
    assert.ok(entry, `requested evidence is not present in index: ${path}`);
    return entry;
  });
  const extracted = extractVerifiedArchive({
    archivePath,
    archiveSha256: validated.archive.checksum.value,
    archiveSize: validated.archive.size,
    entries: validated.entries,
  });
  const restored = [];
  const skipped = [];
  try {
    const output = resolve(outputRoot);
    for (const entry of entries) {
      const target = assertSafeOutputPath(output, entry.path);
      if (existsSync(target)) {
        const stat = lstatSync(target);
        assert.ok(stat.isFile(), `restore target is not a regular file: ${entry.path}`);
        const bytes = readFileSync(target);
        if (bytes.length === entry.size && sha256(bytes) === entry.sha256) {
          skipped.push(entry.path);
          continue;
        }
        assert.ok(force, `restore target differs; use --force to replace: ${entry.path}`);
      }
      const source = resolve(extracted.temporary, entry.path);
      const bytes = readFileSync(source);
      assert.equal(bytes.length, entry.size, `extracted evidence size mismatch: ${entry.path}`);
      assert.equal(sha256(bytes), entry.sha256, `extracted evidence SHA-256 mismatch: ${entry.path}`);
      mkdirSync(dirname(target), { recursive: true });
      const temporary = `${target}.restore-${process.pid}-${Date.now()}`;
      writeFileSync(temporary, bytes, { mode: 0o644 });
      renameSync(temporary, target);
      restored.push(entry.path);
    }
    return { restored, skipped, outputRoot: output };
  } finally {
    rmSync(extracted.temporary, { recursive: true, force: true });
  }
}

export function createEvidenceArchive(options = {}) {
  const root = resolve(options.root ?? repoRoot);
  const ref = options.ref ?? 'HEAD';
  const source = options.source ?? 'both';
  assert.ok(['head', 'worktree', 'both'].includes(source), 'source must be head, worktree or both');
  const artifactRoot = resolve(options.artifactRoot ?? defaultArtifactRoot(root));
  assertOutsideRepo(root, artifactRoot);
  const generatedAt = options.generatedAt ?? new Date().toISOString();
  const archiveId = validateArchiveId(options.archiveId ??
    `logs-${generatedAt.replace(/[-:]/g, '').replace(/\.\d{3}Z$/u, 'Z')}`);
  const outputDir = resolve(artifactRoot, archiveId);
  assertOutsideRepo(root, outputDir);
  assert.ok(!existsSync(outputDir), `archive directory already exists: ${outputDir}`);
  const defaultIndex = resolve(root, DEFAULT_INDEX);
  const indexFile = options.indexFile
    ? resolve(root, options.indexFile)
    : existsSync(defaultIndex)
      ? resolve(root, `docs/evidence/logs-index-${archiveId}.json`)
      : defaultIndex;
  const revision = gitText(root, ['rev-parse', ref]);
  const { entries, files, missingFromWorktree } = collectEntries(root, ref, source);
  const trackedCount = entries.filter(entry => entry.tracked === true).length;
  const presentCount = entries.filter(entry => entry.worktree === 'present').length;
  mkdirSync(artifactRoot, { recursive: true });
  const staging = mkdtempSync(join(artifactRoot, `.${archiveId}.tmp-`));
  const finalArchivePath = join(outputDir, ARCHIVE_FILE);
  const finalManifestPath = join(outputDir, 'manifest.json');
  const finalChecksumPath = join(outputDir, 'SHA256SUMS');
  const finalVerificationPath = join(outputDir, 'verification.json');
  const archiveRelative = relative(artifactRoot, finalArchivePath).split(sep).join('/');
  const manifestRelative = relative(artifactRoot, finalManifestPath).split(sep).join('/');
  try {
    const tree = join(staging, 'tree');
    for (const entry of entries) {
      const target = resolve(tree, entry.path);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, files.get(entry.path), { mode: 0o644 });
    }
    const tar = execFileSync('tar', ['-cf', '-', '-C', tree, 'logs'], { maxBuffer: MAX_GIT_BUFFER });
    const archiveBytes = gzipSync(tar, { level: 9 });
    const archiveSha256 = sha256(archiveBytes);
    const archiveSize = archiveBytes.length;
    const stagingArchive = join(staging, ARCHIVE_FILE);
    writeFileSync(stagingArchive, archiveBytes, { mode: 0o600 });
    const manifest = {
      format: MANIFEST_FORMAT,
      generatedAt,
      source: {
        repository: basename(root),
        ref,
        revision,
        selection: source,
        entryCount: entries.length,
        trackedCount,
        presentCount,
        missingFromWorktree,
      },
      archive: {
        format: 'tar.gz',
        fileName: ARCHIVE_FILE,
        relativeLocation: archiveRelative,
        location: finalArchivePath,
        size: archiveSize,
        checksum: { algorithm: 'sha256', value: archiveSha256 },
      },
      entries,
    };
    const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`);
    writeFileSync(join(staging, 'manifest.json'), manifestBytes, { mode: 0o600 });
    const manifestSha256 = sha256(manifestBytes);
    writeFileSync(join(staging, 'SHA256SUMS'),
      `${archiveSha256}  ${ARCHIVE_FILE}\n${manifestSha256}  manifest.json\n`, { mode: 0o600 });
    const verification = verifyArchive({
      archivePath: stagingArchive,
      archiveSha256,
      archiveSize,
      entries,
    });
    renameSync(staging, outputDir);
    writeFileSync(finalVerificationPath, `${JSON.stringify({
      ...verification,
      archivePath: finalArchivePath,
      manifestPath: finalManifestPath,
    }, null, 2)}\n`, { mode: 0o600 });
    const index = {
      format: INDEX_FORMAT,
      generatedAt,
      source: manifest.source,
      archive: {
        format: 'tar.gz',
        root: artifactRoot,
        relativeLocation: archiveRelative,
        location: finalArchivePath,
        size: archiveSize,
        checksum: { algorithm: 'sha256', value: archiveSha256 },
        manifest: {
          relativeLocation: manifestRelative,
          location: finalManifestPath,
          checksum: { algorithm: 'sha256', value: manifestSha256 },
        },
      },
      entries,
      compatibility: {
        legacyPathPolicy: 'Entries retain original logs/... paths; historical docs/config references are lookup keys, not tracked files.',
        localWrites: 'logs/ is gitignored; existing tools may continue writing local evidence.',
        restoreCommand: 'node tools/evidence/restore.mjs <logs/path>',
      },
    };
    validateIndex(index);
    writeJsonAtomic(indexFile, index);
    const result = {
      archivePath: finalArchivePath,
      manifestPath: finalManifestPath,
      checksumPath: finalChecksumPath,
      verificationPath: finalVerificationPath,
      indexFile,
      archiveSha256,
      archiveSize,
      entryCount: entries.length,
      trackedCount,
      presentCount,
      missingFromWorktree,
    };
    if (options.pruneWorktree) result.prune = pruneWorktreeEvidence(root, entries);
    return result;
  } catch (error) {
    if (existsSync(staging)) rmSync(staging, { recursive: true, force: true });
    throw error;
  }
}
