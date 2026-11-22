import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, utimesSync, existsSync, symlinkSync } from 'node:fs';
import { lstat, stat, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { root, desktop, artifact, main, sha256, log } from './lib.mjs';

const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const DATABASES = ['vfs', 'llm_usage', 'chat_v2', 'mistakes'];

function databaseHeads(storage) {
  return Object.fromEntries(DATABASES.map(database => {
    const history = storage.database(database).all('SELECT version FROM refinery_schema_history ORDER BY version');
    return [database, { version: history.at(-1)?.version ?? 0, count: history.length }];
  }));
}

async function worker() {
  const {
    installTemplateAssetIncrement,
    prepareTemplateMigrations,
    TEMPLATE_ASSET_MIGRATION_INPUTS,
  } = await import('../../desktop-increment/templates-assets.mjs');
  const hook = installTemplateAssetIncrement();
  const { setLocalRuntime } = await load('host/plugins/sync/src/local-runtime.ts');
  const { nodeLocalRuntime } = await load('host/plugins/sync/src/local-runtime.node.ts');
  setLocalRuntime(nodeLocalRuntime);
  const { setLocalFs } = await load('host/plugins/sync-providers/src/local-fs.ts');
  const { nodeLocalFs } = await load('host/plugins/sync-providers/src/local-fs.node.ts');
  setLocalFs(nodeLocalFs);
  const { createStorageService } = await load('host/platform/storage/service.ts');
  const { SqliteSyncStateStore } = await load('host/plugins/sync-support/src/state.ts');
  const { createSyncSupport } = await load('host/plugins/sync-support/src/service.ts');
  const { adaptTombstoneStorage } = await load('host/plugins/sync-support/api.ts');
  const { MemoryStorage } = await load('host/plugins/sync-providers/tests/memory_storage.ts');
  const { createBackupCrypto } = await load('host/platform/secrets/backup-crypto.ts');
  const { createPayloadCodec } = await load('host/plugins/sync/src/payload.ts');
  const { syncAssetsWithTombstones } = await load('host/plugins/sync/src/file-tombstone-sync.ts');
  const { downloadAssetsManifest } = await load('host/plugins/sync/src/asset-manifest.ts');
  const { scanAssets, assetLocalPath } = await load('host/plugins/sync/src/asset-paths.ts');
  const directory = mkdtempSync(join(tmpdir(), 'dstu-assets-roundtrip-'));
  const migrationDirectory = prepareTemplateMigrations(join(directory, 'migrations'));
  for (const item of [TEMPLATE_ASSET_MIGRATION_INPUTS.upstream, TEMPLATE_ASSET_MIGRATION_INPUTS.template]) {
    assert.equal(sha256(readFileSync(resolve(migrationDirectory, item.file))), item.sha256,
      `${item.file}: prepared migration bytes`);
  }
  const stores = [];
  const codecs = [];
  let migrationHeads = null;
  const localFiles = {
    mtimeMs: target => stat(target).then(value => value.mtimeMs).catch(() => null),
    lstat: target => lstat(target).then(value => ({ symlink: value.isSymbolicLink(), directory: value.isDirectory() })).catch(() => null),
    readDir: readdir, exists: target => stat(target).then(() => true).catch(() => false), remove: target => rm(target),
  };
  const warnings = [];
  const logger = { info() {}, debug() {}, warn: message => warnings.push(message), error: message => warnings.push(message) };
  const real = createBackupCrypto();
  const backup = { ...real, createFileCipherSession: password => real.createFileCipherSession(password, { mCost: 8, tCost: 1, pCost: 1 }) };
  const payload = Buffer.from('public persistent asset fixture\r\n');
  const keys = ['active/anki_media/deck/media.txt', 'active/chat_attachments/session/document.txt'];
  const results = [];
  try {
    for (const encrypted of [false, true]) {
      const cloud = new MemoryStorage('asset-fixture');
      const devices = [];
      for (const id of ['a', 'b']) {
        const storage = createStorageService({ baseDir: join(directory, `${encrypted}-${id}`), dataLocalDir: join(directory, `local-${encrypted}-${id}`), migrationsDir: migrationDirectory });
        stores.push(storage);
        await storage.migrations.runAll();
        migrationHeads ??= databaseHeads(storage);
        const state = new SqliteSyncStateStore(() => storage.database('sync_state'));
        const codec = await createPayloadCodec(backup, encrypted ? 'public-fixture-password' : null);
        codecs.push(codec);
        const tombstones = createSyncSupport({ state, notes: () => undefined, logger, localFiles }).tombstones;
        const active = dirname(storage.pathOf('mistakes'));
        devices.push({ storage, active, options: { deviceId: `asset-${id}`, codec, logger, tombstones, openState: () => state, isCancelled: () => false } });
      }
      const [a, b] = devices;
      const localPath = (device, key) => join(device.active, key.slice('active/'.length));
      const save = (path, timestamp) => {
        mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, payload);
        utimesSync(path, new Date(timestamp), new Date(timestamp));
      };
      for (const key of keys) save(localPath(a, key), '2026-01-01T00:00:00Z');
      const outside = join(directory, `outside-${encrypted}`);
      save(join(outside, 'private-fixture.txt'), '2026-01-01T00:00:00Z');
      symlinkSync(outside, join(a.active, 'anki_media', 'linked-directory'));
      symlinkSync(join(outside, 'private-fixture.txt'), join(a.active, 'anki_media', 'linked-file'));
      const [scanned] = await scanAssets(a.active, a.active);
      assert.equal(scanned.size, keys.length, 'asset scan must not follow symlinks');
      assert.equal(assetLocalPath(a.active, a.active, 'active/anki_media/linked-file'), null);
      const upload = await syncAssetsWithTombstones(cloud, a.active, a.active, 'Upload', a.options);
      assert.deepEqual(upload.upload_failures, []); assert.equal(upload.uploaded, 2);
      const download = await syncAssetsWithTombstones(cloud, b.active, b.active, 'Download', b.options);
      assert.deepEqual(download.download_failures, []); assert.equal(download.downloaded, 2);
      for (const key of keys) {
        assert.deepEqual(readFileSync(localPath(b, key)), payload);
        utimesSync(localPath(b, key), new Date('2026-01-01T00:00:00Z'), new Date('2026-01-01T00:00:00Z'));
      }
      const manifest = await downloadAssetsManifest(cloud, a.options);
      const entries = keys.map(key => manifest.entries.get(key));
      assert.ok(entries.every(entry => entry.sha256 === sha256(payload)));
      const objects = new Set(entries.map(entry => entry.object_key));
      assert.equal(objects.size, encrypted ? 2 : 1);
      for (const entry of entries) {
        const object = cloud.bytes(entry.object_key);
        assert.equal(real.isEncryptedBackup(object), encrypted);
        if (encrypted) assert.equal(sha256(object), entry.cipher_sha256);
      }
      await a.options.tombstones.uploadAssetTombstones(adaptTombstoneStorage(cloud), a.options.codec, a.options.deviceId,
        { entries: { [keys[0]]: { deleted_at: '2026-01-02T00:00:00Z', device_id: a.options.deviceId, size: payload.length } }, updated_at: '2026-01-02T00:00:00Z' });
      await syncAssetsWithTombstones(cloud, a.active, a.active, 'Upload', a.options);
      assert.equal(existsSync(localPath(a, keys[0])), false);
      assert.ok(cloud.bytes(entries[1].object_key), 'other asset reference must keep its cloud bytes');
      assert.equal(cloud.bytes(entries[0].object_key) !== null, !encrypted, 'shared plain object kept; unshared cipher object removed');
      const deleted = await syncAssetsWithTombstones(cloud, b.active, b.active, 'Download', b.options);
      assert.equal(existsSync(localPath(b, keys[0])), false);
      assert.deepEqual(readFileSync(localPath(b, keys[1])), payload);
      save(localPath(a, keys[0]), '2026-01-03T00:00:00Z');
      const restoreUpload = await syncAssetsWithTombstones(cloud, a.active, a.active, 'Upload', a.options);
      assert.deepEqual(restoreUpload.upload_failures, []);
      const restored = await syncAssetsWithTombstones(cloud, b.active, b.active, 'Download', b.options);
      assert.deepEqual(readFileSync(localPath(b, keys[0])), payload);
      const latest = await downloadAssetsManifest(cloud, a.options);
      const objectKey = latest.entries.get(keys[0]).object_key;
      const originalObject = cloud.bytes(objectKey);
      const corrupted = Buffer.from(originalObject);
      corrupted[corrupted.length - 1] ^= 1;
      await cloud.put(objectKey, corrupted);
      const corruptDestination = join(directory, `corrupt-${encrypted}`);
      mkdirSync(corruptDestination, { recursive: true });
      const rejected = await syncAssetsWithTombstones(cloud, corruptDestination, corruptDestination, 'Download', b.options);
      assert.ok(rejected.download_failures.includes(keys[0]), 'tampered asset must not report successful download');
      assert.equal(existsSync(join(corruptDestination, keys[0].slice('active/'.length))), false);
      await cloud.put(objectKey, originalObject);
      results.push({ encrypted, upload: { uploaded: upload.uploaded, failed: upload.upload_failures.length },
        download: { downloaded: download.downloaded, failed: download.download_failures.length },
        plaintextSha256: sha256(payload), objectCount: objects.size, deletedWithTombstone: true,
        retainedOtherReference: true, restoredByteHash: sha256(readFileSync(localPath(b, keys[0]))),
        restoreDownloadCount: restored.downloaded, linkedFileAndDirectoryExcluded: true, tamperedObjectRejectedWithoutLanding: true,
        keyPolicy: encrypted ? 'ciphertext-addressed' : 'plaintext-content-addressed' });
      log('asset-transport.roundtrip', results.at(-1));
    }
    assert.equal(migrationHeads.vfs.version, 20261115, 'upstream vfs baseline');
    assert.equal(migrationHeads.mistakes.version, 20261006, 'S0.21 coordinated template migration');
    return { formatVersion: 1, unit: 'S0.21', results, checks: { transportCases: results.length,
      encryptionModes: ['plain', 'DSBK'], tombstoneDeleteRestore: true, sharedPlaintextObjectKept: true,
      upstreamVfsApplied: true, templateMigrationApplied: true },
      migrations: {
        heads: migrationHeads,
        prepared: TEMPLATE_ASSET_MIGRATION_INPUTS,
        rule: 'prepareTemplateMigrations copies the locked upstream migrations plus the S0.21 template SQL only',
      },
      testKdf: { mCost: 8, tCost: 1, pCost: 1, scope: 'fixture-only; production parameters unchanged' },
      implementation: 'production syncAssetsWithTombstones/syncAssetDirectories/codec/SqliteSyncStateStore plus source increment asset allowlist' };
  } finally {
    for (const codec of codecs) codec.dispose();
    for (const store of stores) store.shutdown();
    setLocalRuntime(null); setLocalFs(null); hook?.unregister?.();
    rmSync(directory, { recursive: true, force: true });
  }
}

export async function run(mode) {
  const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--worker'], { cwd: root, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'inherit'] });
  const data = JSON.parse(output);
  artifact('config/contracts/asset-transport.json', data, mode);
  log('asset-transport.accepted', { unit: 'S0.21', mode, ...data.checks });
}

if (process.argv[2] === '--worker') worker().then(value => process.stdout.write(JSON.stringify(value))).catch(error => {
  log('asset-transport.failed', { message: error.message, stack: error.stack }); process.exitCode = 1;
});
else main(import.meta.url, run);
