#!/usr/bin/env node
/**
 * S2.14 isolated desktop interop oracle, NOT an Android restore executor.
 * Uses the frozen desktop implementation; outputs contain synthetic test data only.
 *
 * Node >= 24: fixtures --out NEW_DIR --password-env ENV_NAME
 *             orphan-fixture --out NEW_ZIP --password-env ENV_NAME
 *             verify-android --input RAW_DSBK_OR_PORTABLE_ZIP --password-env ENV_NAME
 *                            (--expect-zip ZIP | --expect-canonical-sha256 SHA256)
 *             inspect-zip --input ZIP
 *             convert-v1 --input DSBK_V1_OR_PORTABLE_ZIP --output NEW_FILE --password-env ENV_NAME
 *                        --max-input-bytes N --max-plaintext-bytes N
 * convert-v1 authenticates before writing plaintext to a private temporary
 * directory; desktop v1 crypto buffers whole containers in memory. Its hard
 * plaintext cap is 256 MiB, independently of the caller's tighter budgets.
 * KDF read cap: m <= 262144 KiB, t <= 8, p <= 4 (plus desktop validation).
 * It re-decrypts and compares v2 before atomic no-overwrite publication.
 * For a portable ZIP, max-input-bytes also bounds total expanded outer entries;
 * max-plaintext-bytes bounds both the sealed plaintext and expanded inner entries.
 * A forced process kill may leave private scratch: remove it before retrying.
 *
 * Canonical content = SHA-256 of sorted UTF-8 lines:
 *   path + TAB + decimal uncompressed length + TAB + SHA-256(actual bytes) + LF
 * Directory records do not contribute. Manifest bytes DO contribute. ZIP metadata,
 * entry order and compression do not. The expected digest MUST come from an
 * independently obtained plaintext reference, never from the encrypted export.
 *
 * Desktop's password ZIP only seals portable_secrets.dsbk, not the entire ZIP.
 * Its ZIP importer does not accept a naked DSBK nor an unsealed full ZIP. The
 * whole-ZIP DSBK files generated here are crypto-port integration fixtures,
 * not claimed to be the desktop application's normal export format.
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { constants as fsConstants, createReadStream } from 'node:fs';
import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, rm, statfs, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { convertPortableV1 } from './s2-14-portable-v1.mjs';

export const DESKTOP = '/home/xutao/deepstudent/dstu-node';
export const DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
export const LEGACY_SHA256 = '3bcecfdd5ec02b0d3b8ee110f2649b6edc136bc9bb6d41b2a5673061ec932eba';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const LEGACY = join(ROOT, 'fixtures/legacy-mobile/legacy-mobile-standard-standard-v1.zip');
const DB_IDS = ['vfs', 'llm_usage', 'chat_v2', 'mistakes'];
const PAYLOAD = 'portable_secrets.dsbk';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const desktopModule = p => import(pathToFileURL(join(DESKTOP, p)).href);
let desktopPromise;

/** Check tracked source AND the checked-out commit, not just a version label. */
async function desktop() {
  if (!desktopPromise) desktopPromise = (async () => {
    const git = args => execFileSync('git', args, { cwd: DESKTOP, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    assert.equal(git(['rev-parse', 'HEAD']), DESKTOP_COMMIT, 'desktop commit drift');
    assert.equal(git(['diff', '--name-only', DESKTOP_COMMIT, '--']), '', 'desktop tracked source drift');
    const [crypto, native, zip, policy, manifest, port, storage, assets, secureStore] = await Promise.all([
      desktopModule('host/platform/secrets/backup-crypto.ts'),
      desktopModule('host/platform/native/argon2/index.ts'),
      desktopModule('host/platform/zip/index.ts'),
      desktopModule('host/plugins/backup/src/zip-policy.ts'),
      desktopModule('host/plugins/backup/src/manifest.ts'),
      desktopModule('host/plugins/backup/src/zip.ts'),
      desktopModule('host/platform/storage/service.ts'),
      desktopModule('host/plugins/backup/src/assets.ts'),
      desktopModule('host/platform/secrets/secure-store.ts'),
    ]);
    return { crypto: crypto.createBackupCrypto({ nativeArgon2: native.argon2 }), zip, policy, manifest, port, storage, assets, secureStore };
  })();
  return desktopPromise;
}

function context(d, dir) {
  const logger = { info() {}, warn() {}, error() {}, debug() {}, trace() {} };
  // Imports validate seed/credentials in disposable directories; never use the
  // real user keystore, paths, or log potentially sensitive backup content.
  const os = { home: dir, data: dir, dataLocal: dir, config: dir, cache: dir, temp: dir, download: null };
  const internals = secureDir => ({ secureDir, enabled: true, keystore: null, platform: process.platform,
    env: { DSTU_KEY_SEED_FILE_ONLY: '1' }, os, log: logger });
  return {
    app: { dataDir: dir }, get: () => undefined, logger: () => logger,
    secrets: { backup: d.crypto,
      validateBackupSeedFile: path => d.secureStore.validateBackupSeedFile(path, null, process.platform),
      openStore: appDataDir => new d.secureStore.SecureStoreImpl(internals(join(appDataDir, '.secure'))) },
    storage: {
      openFile(path, opts) { return d.storage.openFileDatabase(path, { label: opts.label, mustExist: true, profile: { readonly: true } }); },
      close(db) { db.close(); },
    },
  };
}

async function withTemp(fn) {
  const dir = await mkdtemp(join(tmpdir(), 's2-14-desktop-oracle-'));
  try { return await fn(dir); } finally { await rm(dir, { recursive: true, force: true }); }
}

async function filesInZip(path, { trustedLegacy = false, includeBytes = false } = {}) {
  const d = await desktop();
  const reader = await d.zip.ZipFileReader.open(path);
  try {
    if (!trustedLegacy) d.policy.validateImportArchive(d.policy.archiveEntries(reader));
    const names = new Set(), files = [];
    for (const entry of reader.entries) {
      const name = entry.name;
      if (entry.isDirectory) continue;
      if (!name || name.includes('\\') || /[\t\r\n\0]/.test(name) || d.port.isUnsafeArchivePath(name)) {
        throw new Error('unsafe or noncanonical ZIP entry name');
      }
      if (names.has(name)) throw new Error(`duplicate ZIP entry: ${name}`);
      names.add(name);
      const chunks = [], digest = createHash('sha256');
      let size = 0;
      for await (const chunk of await reader.openEntryStream(entry)) {
        size += chunk.length;
        if (size > entry.size) throw new Error(`ZIP length exceeds declaration: ${name}`);
        digest.update(chunk);
        if (includeBytes) chunks.push(chunk);
      }
      if (size !== entry.size) throw new Error(`ZIP length mismatch: ${name}`);
      files.push({ path: name, size, sha256: digest.digest('hex'),
        ...(includeBytes ? { bytes: Buffer.concat(chunks, size) } : {}) });
    }
    files.sort((a, b) => Buffer.compare(Buffer.from(a.path), Buffer.from(b.path)));
    return files;
  } finally { await reader.close(); }
}
function canonical(files) {
  return hash(Buffer.from(files.map(f => `${f.path}\t${f.size}\t${f.sha256}\n`).join(''), 'utf8'));
}
function records(files) { return files.map(({ path, size, sha256 }) => ({ path, size, sha256 })); }
async function writeZip(path, files) {
  const { zip } = await desktop();
  const writer = await zip.ZipFileWriter.create(path);
  try {
    for (const file of files) await writer.addBuffer(file.path, file.bytes, { compression: 'stored' });
    await writer.finish();
  } catch (error) { await writer.abort(); throw error; }
}
function mustFind(files, name) {
  const result = files.find(file => file.path === name);
  if (!result) throw new Error(`missing ZIP entry: ${name}`);
  return result;
}
function fileRecord(path, bytes) { return { path, size: bytes.length, sha256: hash(bytes), bytes }; }

async function buildControlledSource(work, addOrphan = false) {
  const d = await desktop();
  if (hash(await readFile(LEGACY)) !== LEGACY_SHA256) throw new Error('frozen legacy ZIP hash mismatch');
  const old = await filesInZip(LEGACY, { trustedLegacy: true, includeBytes: true });
  const manifest = JSON.parse(mustFind(old, 'manifest.json').bytes.toString('utf8'));
  assert.equal(manifest.platform, 'mobile');
  assert.equal(manifest.key_policy, 'not_present');
  assert.equal(manifest.snapshot_kind, 'full');
  // This is deliberately a controlled desktop-profile fixture, not a claim
  // that legacy audit / identity material may cross trust domains.
  manifest.platform = 'linux';
  manifest.backup_id = 's214-controlled-desktop-profile';
  const blob = manifest.files.find(f => f.path.startsWith('assets/vfs_blobs/'));
  assert.ok(blob);
  // Desktop restoreNonDatabaseManifestFiles preserves an `assets/` prefix;
  // a genuine portable asset belongs in the asset ledger so the desktop
  // restoreAssetsWithProgress port writes it under vfs_blobs/ instead.
  manifest.files = manifest.files.filter(f => f.path !== 'databases/audit.db' && f.path !== blob.path);
  manifest.assets = {
    files: [{ asset_type: 'vfs_blobs', relative_path: blob.path, original_path: blob.path.slice('assets/'.length),
      size: blob.size, checksum: blob.sha256, is_directory: false }],
    total_files: 1, total_size: blob.size, skipped_files: 0, skip_reasons: {}, incomplete_asset_types: [],
    by_asset_type: { vfs_blobs: { file_count: 1, total_size: blob.size } },
    started_at: '2026-01-01T00:00:00Z', completed_at: '2026-01-01T00:00:00Z',
  };
  Object.assign(manifest.coverage.domains.audit, { status: 'absent', paths: [], file_count: 0, total_size: 0 });
  d.manifest.validateForSlotRestore(manifest);
  const source = join(work, 'source');
  await mkdir(source);
  const files = old.filter(f => f.path !== 'manifest.json' && f.path !== 'databases/audit.db');
  for (const file of files) {
    const path = join(source, file.path);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, file.bytes, { flag: 'wx', mode: 0o600 });
  }
  if (addOrphan) {
    const { DatabaseSync } = await import('node:sqlite');
    const file = join(source, 'vfs.db');
    const orphan = Buffer.from('unreferenced restore fixture');
    const sha256 = hash(orphan);
    const db = new DatabaseSync(file);
    try {
      db.prepare('INSERT INTO blobs(hash,relative_path,size,mime_type,ref_count,created_at) VALUES (?,?,?,?,0,0)')
        .run(sha256, `${sha256.slice(0, 2)}/${sha256}.bin`, orphan.length, 'application/x-dstu-orphan-fixture');
    } finally { db.close(); }
    const updated = fileRecord('vfs.db', await readFile(file));
    files[files.findIndex(f => f.path === 'vfs.db')] = updated;
    Object.assign(manifest.files.find(f => f.database_id === 'vfs'), { size: updated.size, sha256: updated.sha256 });
    manifest.coverage.domains['database:vfs'].total_size = updated.size;
  }
  d.manifest.validateForSlotRestore(manifest);
  const manifestEntry = fileRecord('manifest.json', Buffer.from(JSON.stringify(d.manifest.parseManifest(manifest))));
  files.push(manifestEntry);
  await writeFile(join(source, manifestEntry.path), manifestEntry.bytes, { flag: 'wx', mode: 0o600 });
  for (const id of DB_IDS) {
    const declared = manifest.files.find(f => f.database_id === id);
    const actual = mustFind(files, `${id}.db`);
    assert.equal(declared.sha256, actual.sha256);
    assert.equal(declared.size, actual.size);
  }
  return { source, files, manifest };
}

async function exportDesktopPort(ctx, source, path, password) {
  const d = await desktop();
  await d.port.exportBackupToZip(ctx, source, { outputPath: path, encryptionPassword: password,
    includeChecksums: true, compressionLevel: 0 }, () => {}, () => false);
}
async function importDesktopPort(ctx, input, target, password) {
  const d = await desktop();
  return d.port.importBackupFromZip(ctx, input, target, {
    password, skipExisting: false, onProgress() {}, isCancelled: () => false,
  });
}
async function assertImportedEquivalent(sourceFiles, dir) {
  // Native import serializes the recovered manifest afresh. Compare its parsed
  // content and every other source byte, not JSON formatting or ZIP metadata.
  const expectedManifest = JSON.parse(mustFind(sourceFiles, 'manifest.json').bytes.toString('utf8'));
  const actualManifest = JSON.parse((await readFile(join(dir, 'manifest.json'))).toString('utf8'));
  assert.deepEqual(actualManifest, expectedManifest);
  for (const file of sourceFiles.filter(f => f.path !== 'manifest.json')) {
    assert.equal(hash(await readFile(join(dir, file.path))), file.sha256, `desktop import content mismatch: ${file.path}`);
  }
}
async function portableV1(ctx, v2zip, output, password, work) {
  const d = await desktop();
  const entries = await filesInZip(v2zip, { includeBytes: true });
  const v2 = mustFind(entries, PAYLOAD);
  assert.equal(v2.bytes[4], 2);
  const inPath = join(work, 'portable-v2.dsbk'), inner = join(work, 'portable-inner.zip');
  await writeFile(inPath, v2.bytes, { flag: 'wx' });
  await d.crypto.decryptBackupFile(inPath, inner, password);
  const v1 = Buffer.from(await d.crypto.encryptBackup(await readFile(inner), password));
  assert.equal(v1[4], 1);
  const outer = JSON.parse(mustFind(entries, 'manifest.json').bytes.toString('utf8'));
  const payload = outer.files.find(file => file.path === PAYLOAD);
  assert.ok(payload);
  payload.size = v1.length; payload.sha256 = hash(v1);
  d.manifest.validateUntrusted(outer);
  const converted = entries.filter(f => !['manifest.json', PAYLOAD, 'checksums.sha256'].includes(f.path));
  converted.push(fileRecord('manifest.json', Buffer.from(JSON.stringify(outer, null, 2))));
  converted.push(fileRecord(PAYLOAD, v1));
  await writeZip(output, converted);
}

/** Write a NEW directory only. Portable v2 is a real desktop export; v1 is a conversion fixture. */
export async function makeFixtures(outDir, password) {
  assert.ok(typeof password === 'string' && Array.from(password.trim()).length >= 8, 'fixture password must contain at least 8 characters');
  const d = await desktop();
  const out = resolve(outDir);
  await mkdir(out); // never delete or overwrite a previous result
  try {
    return await withTemp(async work => {
      const { source, files } = await buildControlledSource(work);
      const ctx = context(d, work);
      const full = join(out, 'controlled-full.zip');
      await writeZip(full, files);
      const fullFiles = await filesInZip(full);
      const canonicalSha256 = canonical(fullFiles);
      const portable2 = join(out, 'desktop-portable-v2.zip');
      await exportDesktopPort(ctx, source, portable2, password);
      const p2 = await filesInZip(portable2, { includeBytes: true });
      assert.equal(mustFind(p2, PAYLOAD).bytes[4], 2);
      const outer = JSON.parse(mustFind(p2, 'manifest.json').bytes.toString('utf8'));
      assert.equal(outer.snapshot_kind, 'partial_overlay');
      assert.equal(outer.key_policy, 'included_encrypted');
      await importDesktopPort(ctx, portable2, join(work, 'imported-v2'), password);
      await assertImportedEquivalent(files, join(work, 'imported-v2'));
      const portable1 = join(out, 'desktop-portable-v1-converted.zip');
      await portableV1(ctx, portable2, portable1, password, work);
      await importDesktopPort(ctx, portable1, join(work, 'imported-v1'), password);
      await assertImportedEquivalent(files, join(work, 'imported-v1'));
      const importedManifest = d.manifest.loadManifest(join(work, 'imported-v2', 'manifest.json'));
      const restoredAssets = join(work, 'restored-assets');
      await mkdir(restoredAssets);
      assert.equal(await d.assets.restoreAssetsWithProgress(join(work, 'imported-v2'), restoredAssets,
        importedManifest.assets.files, () => true), 1);
      const blob = importedManifest.assets.files[0];
      assert.equal(hash(await readFile(join(restoredAssets, blob.original_path))), blob.checksum);
      const p1 = await filesInZip(portable1, { includeBytes: true });
      await writeFile(join(out, 'desktop-portable-payload-v2.dsbk'), mustFind(p2, PAYLOAD).bytes, { flag: 'wx' });
      await writeFile(join(out, 'desktop-portable-payload-v1.dsbk'), mustFind(p1, PAYLOAD).bytes, { flag: 'wx' });
      await d.crypto.encryptBackupFile(full, join(out, 'controlled-full-v2.dsbk'), password);
      await writeFile(join(out, 'controlled-full-v1.dsbk'), await d.crypto.encryptBackup(await readFile(full), password), { flag: 'wx' });
      const outputFiles = ['controlled-full.zip', 'desktop-portable-v2.zip', 'desktop-portable-v1-converted.zip',
        'desktop-portable-payload-v2.dsbk', 'desktop-portable-payload-v1.dsbk',
        'controlled-full-v2.dsbk', 'controlled-full-v1.dsbk'];
      const outputs = {};
      for (const name of outputFiles) {
        const bytes = await readFile(join(out, name));
        outputs[name] = { size: bytes.length, sha256: hash(bytes) };
      }
      const report = { format: 'dstu.s2-14.desktop-oracle.v1', desktopCommit: DESKTOP_COMMIT,
        legacySourceSha256: LEGACY_SHA256,
        source: 'controlled desktop-profile fixture derived from frozen legacy databases/blob; audit excluded',
        canonicalDefinition: 'SHA256(UTF8(path TAB decimal_size TAB sha256 LF) sorted by UTF8 path bytes; files only)',
        fullZipCanonicalSha256: canonicalSha256, fullZipEntries: records(fullFiles), outputs,
        desktopPortableV2: 'production exportBackupToZip; frozen importBackupFromZip succeeded; slot restore not executed',
        desktopPortableV1: 'test conversion of v2 sealed payload to desktop v1; frozen importBackupFromZip succeeded; slot restore not executed',
        desktopAssetPort: 'frozen restoreAssetsWithProgress wrote the referenced blob under vfs_blobs and its hash matched; not a slot restore',
        portablePayloads: 'standalone portable_secrets.dsbk extracted from each desktop ZIP; inner ZIP is NOT the complete backup',
        wholeZipDsbk: 'frozen crypto API wrapping controlled full ZIP; NOT desktop normal ZIP export',
      };
      await writeFile(join(out, 'oracle.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
      return report;
    });
  } catch (error) { await rm(out, { recursive: true, force: true }); throw error; }
}

export async function makeOrphanFixture(path, password) {
  return withTemp(async work => {
    const d = await desktop();
    const { source, files } = await buildControlledSource(work, true);
    const ctx = context(d, work);
    const output = resolve(path);
    assert.equal(await lstat(output).then(() => true, error => {
      if (error.code === 'ENOENT') return false;
      throw error;
    }), false, 'use a new fixture path');
    await exportDesktopPort(ctx, source, output, password);
    const imported = join(work, 'imported');
    await importDesktopPort(ctx, output, imported, password);
    await assertImportedEquivalent(files, imported);
    const bytes = await readFile(output);
    return { fixture: 'zero-reference-orphan', desktopCommit: DESKTOP_COMMIT, sha256: hash(bytes),
      exportedAndImported: true, fullSnapshotSha256: canonical(files) };
  });
}

export async function inspectZip(input) {
  const files = await filesInZip(resolve(input));
  return { canonicalSha256: canonical(files), entries: records(files) };
}

async function directoryRecords(root) {
  const result = [];
  async function visit(dir, prefix) {
    for (const name of await readdir(dir)) {
      const path = join(dir, name), relative = prefix ? `${prefix}/${name}` : name;
      const info = await lstat(path);
      if (info.isDirectory()) await visit(path, relative);
      else if (info.isFile()) {
        const digest = createHash('sha256');
        let size = 0;
        for await (const chunk of createReadStream(path)) { digest.update(chunk); size += chunk.length; }
        result.push({ path: relative, size, sha256: digest.digest('hex') });
      } else throw new Error('desktop import produced a non-regular file');
    }
  }
  await visit(root, '');
  result.sort((a, b) => Buffer.compare(Buffer.from(a.path), Buffer.from(b.path)));
  return result;
}
async function zipEntryHeader(path, name) {
  const d = await desktop();
  const reader = await d.zip.ZipFileReader.open(path);
  try {
    const entry = reader.find(name);
    if (!entry || entry.isDirectory) throw new Error(`missing ZIP entry: ${name}`);
    const stream = await reader.openEntryStream(entry);
    const chunks = [];
    let length = 0;
    for await (const chunk of stream) {
      chunks.push(chunk); length += chunk.length;
      if (length >= 5) break;
    }
    return Buffer.concat(chunks, length).subarray(0, 5);
  } finally { await reader.close(); }
}
async function fileHeader(path, length = 5) {
  const fd = await open(path, 'r');
  try {
    const buffer = Buffer.alloc(length);
    const { bytesRead } = await fd.read(buffer, 0, buffer.length, 0);
    return buffer.subarray(0, bytesRead);
  } finally { await fd.close(); }
}

/** Verify independently expected content; clearly distinguish crypto from actual desktop ZIP import. */
export async function verifyAndroid({ input, password, expectedZip, expectedCanonicalSha256 }) {
  if ((!expectedZip && !expectedCanonicalSha256) || (expectedZip && expectedCanonicalSha256)) {
    throw new Error('supply exactly one independent --expect-zip or --expect-canonical-sha256');
  }
  const d = await desktop();
  let expected = expectedCanonicalSha256;
  if (expectedZip) expected = (await inspectZip(expectedZip)).canonicalSha256;
  if (!/^[0-9a-f]{64}$/.test(expected ?? '')) throw new Error('invalid expected canonical SHA-256');
  return withTemp(async work => {
    const path = resolve(input), header = await fileHeader(path);
    const ctx = context(d, work);
    const nakedDsbk = header.subarray(0, 4).toString('ascii') === 'DSBK';
    let actual, desktopImport, scope;
    if (nakedDsbk) {
      if (header[4] !== 2) throw new Error('Android raw DSBK output must be version 2');
      const plain = join(work, 'android-plain.zip');
      await d.crypto.decryptBackupFile(path, plain, password);
      actual = await inspectZip(plain);
      assert.equal(actual.canonicalSha256, expected, 'canonical ZIP content mismatch');
      // The real ZIP port is separately probed. It may legitimately reject a
      // plaintext full ZIP; that rejection must NOT be called a restore.
      try {
        const sealed = actual.entries.some(f => f.path === PAYLOAD);
        await importDesktopPort(ctx, plain, join(work, 'desktop-import'), sealed ? password : null);
        desktopImport = { result: 'succeeded', scope: 'frozen ZIP import only; slot restore not executed' };
      } catch (error) {
        desktopImport = { result: 'rejected', reason: String(error.message), scope: 'frozen ZIP import only; no slot restore' };
      }
      scope = 'raw DSBK v2 decrypted by frozen desktop crypto file port; canonical plaintext ZIP verified';
    } else {
      // Desktop-compatible portable ZIP: verify the actual DSBK payload version,
      // then use the production ZIP port to authenticate, unseal and verify the
      // imported manifest, hashes and SQLite integrity in a disposable directory.
      const entries = await filesInZip(path);
      mustFind(entries, PAYLOAD);
      const payload = await zipEntryHeader(path, PAYLOAD);
      if (payload.subarray(0, 4).toString('ascii') !== 'DSBK' || payload[4] !== 2) {
        throw new Error('Android portable ZIP must contain a DSBK v2 sealed payload');
      }
      const dir = join(work, 'desktop-import');
      await importDesktopPort(ctx, path, dir, password);
      const imported = await directoryRecords(dir);
      actual = { entries: imported, canonicalSha256: canonical(imported) };
      assert.equal(actual.canonicalSha256, expected, 'canonical imported content mismatch');
      desktopImport = { result: 'succeeded', scope: 'frozen ZIP import only; slot restore not executed' };
      scope = 'desktop-compatible portable ZIP authenticated and imported by frozen desktop ZIP port';
    }
    return { format: 'dstu.s2-14.android-v2-verification.v1', desktopCommit: DESKTOP_COMMIT,
      verification: scope, canonicalSha256: actual.canonicalSha256, expectedCanonicalSha256: expected,
      entries: actual.entries, desktopImport, desktopSlotRestoreVerified: false,
      inputOrigin: 'caller-supplied; not attested by this oracle' };
  });
}

const MAX_CONVERSION_PLAINTEXT = 256 * 1024 * 1024;
const V1_OVERHEAD = 45 + 16;
const V2_HEADER = 44;
const V2_CHUNK = 1024 * 1024;
const V2_TAG = 16;
async function hashFile(path) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(path)) digest.update(chunk);
  return digest.digest('hex');
}
function safeBudget(value, max) {
  if (!Number.isSafeInteger(value) || value < 0 || value > max) throw new Error('invalid conversion budget');
  return value;
}

/** Desktop v1 is one-shot AES-GCM; explicitly bounded, not a streaming v1 decrypt. */
async function convertRawV1(source, output, password, maxPlaintextBytes, scratch, d) {
  const info = await lstat(source);
  if (!info.isFile() || info.size < V1_OVERHEAD) throw new Error('conversion input size is outside its budget');
  const plainBytes = info.size - V1_OVERHEAD;
  if (plainBytes > maxPlaintextBytes || plainBytes > MAX_CONVERSION_PLAINTEXT) {
    throw new Error('conversion plaintext size is outside its budget');
  }
  const header = await fileHeader(source, 17);
  if (header.length < 17 || header.subarray(0, 4).toString('ascii') !== 'DSBK' || header[4] !== 1) {
    throw new Error('conversion input must be DSBK v1');
  }
  const kdf = { memoryKiB: header.readUInt32LE(5), iterations: header.readUInt32LE(9), parallelism: header.readUInt32LE(13) };
  if (kdf.memoryKiB > 262144 || kdf.iterations > 8 || kdf.parallelism > 4) {
    throw new Error('conversion KDF parameters exceed desktop conversion budget');
  }
  const v2Size = V2_HEADER + plainBytes + V2_TAG * Math.max(1, Math.ceil(plainBytes / V2_CHUNK));
  const free = await statfs(scratch, { bigint: true });
  if (free.bavail * free.bsize < 2n * BigInt(plainBytes) + BigInt(v2Size) + 1048576n) {
    throw new Error('conversion scratch disk budget is unavailable');
  }
  const plain = join(scratch, 'authenticated.zip'), verified = join(scratch, 'verified.zip');
  // The frozen desktop port authenticates all of v1 before returning plaintext.
  await d.crypto.decryptBackupFile(source, plain, password);
  if ((await lstat(plain)).size !== plainBytes) throw new Error('conversion plaintext length mismatch');
  const plainSha256 = await hashFile(plain);
  await d.crypto.encryptBackupFile(plain, output, password);
  if ((await lstat(output)).size !== v2Size || (await fileHeader(output))[4] !== 2) {
    throw new Error('conversion v2 length mismatch');
  }
  await d.crypto.decryptBackupFile(output, verified, password);
  if ((await lstat(verified)).size !== plainBytes || (await hashFile(verified)) !== plainSha256) {
    throw new Error('conversion v2 verification failed');
  }
  return { plaintextPath: plain, plaintextBytes: plainBytes, plaintextSha256: plainSha256,
    containerBytes: v2Size, containerSha256: await hashFile(output), inputKdf: kdf };
}

const MAX_CONVERSION_INPUT = 1024 * 1024 * 1024;

async function hashHandle(handle) {
  const digest = createHash('sha256');
  for await (const chunk of handle.createReadStream({ autoClose: false, start: 0 })) digest.update(chunk);
  return digest.digest('hex');
}

export async function convertV1({ input, output, password, maxInputBytes, maxPlaintextBytes }) {
  const d = await desktop();
  safeBudget(maxInputBytes, MAX_CONVERSION_INPUT);
  safeBudget(maxPlaintextBytes, MAX_CONVERSION_PLAINTEXT);
  const source = resolve(input), destination = resolve(output);
  if (source === destination) throw new Error('conversion input and output must differ');
  if (!password) throw new Error('conversion password is empty');
  const parent = dirname(destination), parentInfo = await lstat(parent);
  if (!parentInfo.isDirectory() || parentInfo.isSymbolicLink()) throw new Error('conversion output directory is invalid');
  const sourceHandle = await open(source, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
  try {
    const info = await sourceHandle.stat();
    if (!info.isFile() || info.size < 4 || info.size > maxInputBytes) throw new Error('conversion input size is outside its budget');
    const free = await statfs(parent, { bigint: true });
    if (free.bavail * free.bsize < BigInt(info.size) + 1048576n) throw new Error('conversion scratch disk budget is unavailable');
    const scratch = await mkdtemp(join(parent, '.s214-v1-convert-'));
    try {
      const copied = join(scratch, 'source'), candidate = join(scratch, 'candidate');
      const dest = await open(copied, 'wx', 0o600), sourceDigest = createHash('sha256');
      try {
        let count = 0;
        for await (const chunk of sourceHandle.createReadStream({ autoClose: false, start: 0 })) {
          count += chunk.length;
          if (count > info.size || count > maxInputBytes) throw new Error('conversion input changed during copy');
          sourceDigest.update(chunk);
          await dest.writeFile(chunk);
        }
        if (count !== info.size) throw new Error('conversion input changed during copy');
        await dest.sync();
      } finally { await dest.close(); }
      const inputSha256 = sourceDigest.digest('hex');
      const header = await fileHeader(copied);
      let result;
      if (header.subarray(0, 4).toString('ascii') === 'DSBK') {
        const raw = await convertRawV1(copied, candidate, password, maxPlaintextBytes, scratch, d);
        const { plaintextPath: _privatePath, ...details } = raw;
        result = { format: 'dstu.s2-14.v1-to-v2-conversion.v1', desktopCommit: DESKTOP_COMMIT, ...details,
          limits: { hardPlaintextBytes: MAX_CONVERSION_PLAINTEXT, maxMemoryKiB: 262144, maxIterations: 8, maxParallelism: 4 },
          conversion: 'frozen desktop v1 authentication, v2 write and v2 readback; no ZIP/slot restore claim' };
      } else if (header.subarray(0, 4).equals(Buffer.from([0x50, 0x4b, 0x03, 0x04]))) {
        result = await convertPortableV1({ input: copied, output: candidate, scratch, password, maxInputBytes,
          maxPlaintextBytes, desktop: d, desktopCommit: DESKTOP_COMMIT, directoryRecords, canonical,
          importDesktop: (zip, dir) => importDesktopPort(context(d, scratch), zip, dir, password),
          convertRaw: (source, output) => convertRawV1(source, output, password, maxPlaintextBytes, scratch, d) });
      } else throw new Error('conversion input must be DSBK v1 or a desktop portable ZIP');
      const current = await lstat(source);
      if (!current.isFile() || current.isSymbolicLink() || current.dev !== info.dev || current.ino !== info.ino ||
          current.size !== info.size || await hashHandle(sourceHandle) !== inputSha256) {
        throw new Error('conversion input changed during processing');
      }
      const candidateHandle = await open(candidate, 'r+');
      try { await candidateHandle.sync(); } finally { await candidateHandle.close(); }
      // Same-filesystem hard-link creation is atomic and fails if *anything* exists at destination.
      await link(candidate, destination);
      return result;
    } finally { await rm(scratch, { recursive: true, force: true }); }
  } finally { await sourceHandle.close(); }
}

function parseOptions(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    const name = args[i], value = args[i + 1];
    if (!name?.startsWith('--') || !value || value.startsWith('--') || name in options) throw new Error(`invalid option: ${name}`);
    options[name] = value;
  }
  return options;
}
async function main(args) {
  const [command, ...rest] = args;
  const opts = parseOptions(rest);
  const accepted = command === 'fixtures' || command === 'orphan-fixture' ? ['--out', '--password-env']
    : command === 'verify-android' ? ['--input', '--password-env', '--expect-zip', '--expect-canonical-sha256']
    : command === 'inspect-zip' ? ['--input']
    : command === 'convert-v1' ? ['--input', '--output', '--password-env', '--max-input-bytes', '--max-plaintext-bytes'] : [];
  if (!accepted.length || Object.keys(opts).some(key => !accepted.includes(key))) throw new Error('invalid command or option; see usage in file header');
  if (command === 'inspect-zip') {
    if (!opts['--input']) throw new Error('--input is required');
    return inspectZip(opts['--input']);
  }
  const password = process.env[opts['--password-env']];
  if (!opts['--password-env'] || !password) throw new Error('password environment variable is missing or empty');
  if (command === 'fixtures' || command === 'orphan-fixture') {
    if (!opts['--out']) throw new Error('--out is required');
    return command === 'fixtures' ? makeFixtures(opts['--out'], password) : makeOrphanFixture(opts['--out'], password);
  }
  if (command === 'convert-v1') {
    if (!opts['--input'] || !opts['--output'] || !/^\d+$/.test(opts['--max-input-bytes'] ?? '') ||
      !/^\d+$/.test(opts['--max-plaintext-bytes'] ?? '')) throw new Error('conversion requires input, output and both byte budgets');
    return convertV1({ input: opts['--input'], output: opts['--output'], password,
      maxInputBytes: Number(opts['--max-input-bytes']), maxPlaintextBytes: Number(opts['--max-plaintext-bytes']) });
  }
  if (!opts['--input']) throw new Error('--input is required');
  return verifyAndroid({ input: opts['--input'], password, expectedZip: opts['--expect-zip'],
    expectedCanonicalSha256: opts['--expect-canonical-sha256'] });
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(result => console.log(JSON.stringify(result, null, 2))).catch(error => {
    console.error(`S2.14 desktop oracle failed: ${error.message}`); process.exitCode = 1;
  });
}
