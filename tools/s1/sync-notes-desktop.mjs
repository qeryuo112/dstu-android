import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { desktopRoot } from '../../desktop-increment/loader.mjs';
import { installTemplateAssetIncrement } from '../../desktop-increment/templates-assets.mjs';
import { installMediaTimeIncrement } from '../../desktop-increment/media-time.mjs';
import { installPortableConfigIncrement } from '../../desktop-increment/portable-config.mjs';
import { installPluginDataIncrement } from '../../desktop-increment/plugin-data.mjs';
import { installCaptureRepairIncrement, prepareCaptureRepairMigrations } from '../../desktop-increment/s2-capture-repair.mjs';

export const desktopCommit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
export const fixtureContent = 'Public desktop note: 中文 😀\n';
export const fixtureBytes = Buffer.alloc(1024 * 1024 + 17, 0x61);
export const fixtureHash = createHash('sha256').update(fixtureBytes).digest('hex');
export const androidFixtureBytes = Buffer.alloc(1024 * 1024 + 31, 0x62);
export const androidFixtureHash = createHash('sha256').update(androidFixtureBytes).digest('hex');
export const encryptionFixturePassword = 'public S1.26 仓库 🔐';

export async function openDesktopFixture(root, fixture, encrypted = false) {
  // Desktop services are loaded in-process and may only talk to the disposable loopback fixture.
  const endpoint = new URL(fixture.endpoint);
  assert.equal(endpoint.protocol, 'http:');
  assert.equal(endpoint.hostname, '127.0.0.1');
  assert.equal(endpoint.username + endpoint.password, '');
  assert.equal(Number(endpoint.port), fixture.port);
  assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: desktopRoot, encoding: 'utf8' }).trim(), desktopCommit);
  execFileSync('git', ['diff', '--quiet', desktopCommit, '--'], { cwd: desktopRoot });
  const increments = [installTemplateAssetIncrement(), installMediaTimeIncrement(), installPortableConfigIncrement(), installPluginDataIncrement(), installCaptureRepairIncrement()];
  // Increments cover pre-existing schema deltas, never the frozen sync codec, compression or DSBK implementation.
  const protectedPaths = ['host/plugins/sync/src/payload.ts', 'host/plugins/sync-support/src/codec.ts',
    'host/platform/native/compression/', 'host/platform/secrets/'];
  for (const increment of increments) for (const file of increment.files) {
    assert.ok(!protectedPaths.some(path => file.path === path || file.path.startsWith(path)));
  }
  const load = path => import(pathToFileURL(join(desktopRoot, path)).href);
  const paths = [
    'host/platform/storage/service.ts', 'host/plugins/sync/src/local-runtime.ts', 'host/plugins/sync/src/local-runtime.node.ts',
    'host/plugins/sync-providers/src/local-fs.ts', 'host/plugins/sync-providers/src/local-fs.node.ts',
    'host/plugins/vfs-repos/src/local-runtime.ts', 'host/plugins/vfs-repos/src/local-runtime.node.ts',
    'host/plugins/notes/repository/notes.ts', 'host/plugins/notes/repository/resource-deps.ts',
    'host/plugins/vfs-repos/repository/resources.ts', 'host/plugins/vfs-repos/repository/note-owner-writes.ts',
    'host/plugins/vfs-repos/repository/embedding-repo.ts', 'host/plugins/vfs-repos/repository/index-units.ts',
    'host/plugins/vfs-repos/repository/folders.ts', 'host/plugins/vfs-repos/repository/attachments.ts',
    'host/plugins/notes/repository/relations.ts', 'host/plugins/sync-providers/src/webdav.ts',
    'host/plugins/sync-support/src/state.ts', 'host/plugins/sync-support/src/codec.ts',
    'host/platform/native/compression/index.ts', 'host/plugins/sync/src/transfer-commands.ts',
    'host/plugins/sync/src/upload-changes.ts', 'host/plugins/sync/src/download-changes.ts',
    'host/plugins/sync/src/manifest-upload.ts', 'host/plugins/sync/src/blob-sync.ts',
    'host/plugins/sync/repository/apply-databases.ts', 'host/plugins/sync/repository/apply.ts',
    'host/plugins/sync/repository/note-history.ts', 'host/plugins/sync-support/src/note-history.ts',
    'host/plugins/notes/repository/format.ts', 'host/plugins/notes/repository/revisions.ts', 'host/plugins/notes/repository/sync.ts',
    'host/plugins/sync-support/src/tombstone-manifests.ts',
    'host/platform/secrets/backup-crypto.ts', 'host/plugins/sync/src/payload.ts',
    'host/plugins/sync-providers/src/sync_manager.ts', 'host/plugins/sync-providers/src/marker.ts',
    'host/plugins/sync/src/change-keys.ts', 'host/plugins/sync/src/download-payload.ts',
    'host/plugins/sync/src/remote-manifest.ts', 'host/platform/util/lossless-json.ts',
  ];
  const [storageModule, runtime, runtimeNode, fs, fsNode, vfs, vfsNode, notes, deps, resources, owners, embedding, indexes, folders, attachments, relations, webdav, state, codec, compression, transfer, upload, download, manifest, blobs, apply, applyHooks, history, historySupport, format, revisions, noteSync, tombstones, backup, payload, manager, marker, changeKeys, downloadPayload, remoteManifest, lossless] = await Promise.all(paths.map(load));
  const notesService = { repository: notes.noteRepository, format: format.noteFormatRepository, revisions: revisions.noteRevisionRepository, sync: noteSync.noteSyncRepository };
  applyHooks.setDefaultNoteHistory(history.createNoteHistoryHooks(notesService, () => historySupport.createNoteHistorySupport(notesService)));
  runtime.setLocalRuntime(runtimeNode.nodeLocalRuntime);
  fs.setLocalFs(fsNode.nodeLocalFs);
  vfs.setVfsReposLocalRuntime(vfsNode.nodeVfsReposLocalRuntime);
  deps.configureNoteResourceRepos({ noteOwnerWrites: new owners.NoteOwnerWritesRepo(), resources: new resources.ResourceRepo(), indexStates: new embedding.IndexStateRepo(), embeddingUnits: new indexes.IndexUnitRepo(), folders: new folders.FolderRepo() });
  const migrationsDir = prepareCaptureRepairMigrations(join(root, 'migrations'));
  const baseDir = join(root, 'desktop');
  const blobsDir = join(root, 'blobs');
  mkdirSync(blobsDir, { recursive: true });
  const storage = storageModule.createStorageService({ baseDir, dataLocalDir: baseDir, migrationsDir });
  const migration = await storage.migrations.runAll();
  assert.equal(migration.success, true, 'desktop fixture migrations failed');
  const repository = encrypted ? 's1-26' : 's1-25';
  const cloud = new webdav.WebDavStorage({ endpoint: fixture.endpoint, username: fixture.username, password: fixture.password }, repository, { rateLimitEnv: '0', requestWindowKey: `${repository}-${fixture.port}` });
  const deviceId = `${repository}-desktop`;
  // Do not forward desktop messages, exceptions, cloud keys, or payloads into fixture logs.
  const diagnosticCounts = { warnings: 0, errors: 0 };
  const logger = { debug() {}, info() {}, warn() { diagnosticCounts.warnings++; }, error() { diagnosticCounts.errors++; } };
  const desktopCrypto = encrypted ? backup.createBackupCrypto() : null;
  const policy = encrypted ? new manager.CloudSyncManager(cloud, deviceId, { backup: desktopCrypto }) : null;
  const encryptedCodec = encrypted ? await payload.createPayloadCodec(desktopCrypto, encryptionFixturePassword) : null;
  const options = { deviceId, codec: encryptedCodec ?? codec.plainCodec, logger };
  const blobOptions = { ...options, isCancelled: () => false, tombstones: {
    downloadBlobTombstones: (storage, payloadCodec) => tombstones.downloadBlobTombstones(storage, payloadCodec, logger),
  } };
  const attachmentRepo = new attachments.AttachmentRepo();
  const openState = () => new state.SqliteSyncStateStore(() => storage.database('sync_state'));
  const db = storage.database('vfs');
  assert.equal(Number(db.get('SELECT MAX(version) AS version FROM refinery_schema_history').version), 20261120,
    'roundtrip desktop must load the same coordinated repair as Android');
  async function publish() {
    if (policy) await policy.enforceEncryptionPolicyBeforeUploadWithPassword(encryptionFixturePassword);
    const snapshot = transfer.collectLocalManifest(storage, deviceId, true, logger);
    assert.ok(snapshot.changes.some(change => change.table_name === 'notes'));
    await upload.uploadEnrichedChanges(cloud, snapshot.changes, options, openState, compression.compression);
    await manifest.uploadManifest(cloud, snapshot.manifest, options, openState);
    const result = await blobs.syncVfsBlobs(cloud, blobsDir, 'Upload', blobOptions);
    assert.deepEqual(result.upload_failures, []);
    return result;
  }
  return {
    storage, db, cloud, notes: notes.noteRepository, options, openState, deviceId, diagnosticCounts,
    async seed() {
      const note = notes.noteRepository.createNoteInFolder({ title: 'Public S1.25', content: fixtureContent, tags: ['public'] }, null, db);
      const attachment = await new attachments.AttachmentRepo().uploadBytesWithFolderConn(db, blobsDir, { name: 'fixture.txt', mimeType: 'text/plain', type: 'file' }, fixtureBytes, null);
      assert.equal(attachment.resourceHash, fixtureHash);
      const relationId = randomUUID();
      const resourceId = db.get('SELECT resource_id FROM files WHERE id = ?1', [attachment.sourceId])?.resource_id;
      assert.ok(resourceId);
      relations.noteRelationRepository.put(db, null, { id: relationId, note_id: note.id, block_id: null, type: 'source', resource_id: resourceId, locator: { type: 'whole' } });
      const result = await publish();
      assert.ok(result.uploaded > 0);
      return { noteId: note.id, resourceId, fileId: attachment.sourceId, relationId, hash: fixtureHash };
    },
    async secondNote() {
      const note = notes.noteRepository.createNoteInFolder({ title: 'Public retry', content: 'Desktop second batch', tags: [] }, null, db);
      await publish();
      return note.id;
    },
    snapshotVfs(file) {
      db.run('VACUUM INTO ?1', [file]);
    },
    async verifyAndroidMarker(prefix) {
      const bytes = await new webdav.WebDavStorage({ endpoint: fixture.endpoint, username: fixture.username, password: fixture.password }, prefix,
        { rateLimitEnv: '0', requestWindowKey: `${prefix}-${fixture.port}` }).get('.encryption-marker');
      const value = marker.decodeEncryptionMarker(bytes);
      assert.equal(value.version, 2);
      assert.equal(await desktopCrypto.checkPasswordVerifier(encryptionFixturePassword, value.keyVerifier), true);
    },
    async receive() {
      const files = await blobs.syncVfsBlobs(cloud, blobsDir, 'Download', blobOptions);
      assert.deepEqual(files.download_failures, []);
      const downloaded = await download.downloadChanges(cloud, 0n, null, options, openState, compression.compression);
      const result = apply.applyDownloadedChangesToDatabases(storage, downloaded.changes, 'UseCloud', deviceId, undefined, logger);
      assert.equal(result.total_failed, 0, 'desktop apply failed');
      assert.equal(result.total_incomplete_skipped, 0);
      await download.commitDownloadProgress(cloud, downloaded, options, openState);
      const instance = await manifest.ensureRemoteInstanceId(cloud, options, openState);
      return { downloaded, result, cursors: openState().cursors(instance) };
    },
    /** Decrypt and decompress in this process using the *unmodified frozen desktop* implementation. */
    async inspectChanges(objects) {
      const prefix = `/dav/${repository}/`;
      const result = [];
      for (const [path, wire] of objects) {
        if (!path.startsWith(prefix)) continue;
        const key = path.slice(prefix.length);
        if (!key.startsWith('data_governance/changes/') && !key.startsWith('data_governance/v4/shards/')) continue;
        const parsed = changeKeys.parseChangeKey(key);
        assert.ok(parsed && parsed.kind !== 'legacy', 'invalid fixture change key');
        const compressed = await options.codec.decode(wire);
        assert.ok(Buffer.from(compressed).subarray(0, 4).equals(Buffer.from([0x28, 0xb5, 0x2f, 0xfd])), 'change is not zstd');
        const text = new TextDecoder('utf-8', { fatal: true }).decode(await compression.compression.zstdDecode(compressed));
        const raw = lossless.parseJsonLossless(text);
        const batch = downloadPayload.parseChangesPayload(raw);
        assert.equal(batch.source_seq, parsed.seq, 'wire key and payload sequence disagree');
        assert.equal(raw.device_id, batch.source_device_id, 'wire source identity disagrees');
        assert.ok(changeKeys.pathIdMatchesDevice(parsed.deviceId, batch.source_device_id), 'wire key and source identity disagree');
        assert.equal(BigInt(raw.total_count), BigInt(batch.changes.length));
        assert.equal(batch.format_version, parsed.kind === 'v4Shard' ? 4n : 3n);
        if (parsed.kind === 'v4Shard') assert.equal(batch.operation_id, parsed.operationId);
        result.push({ path, sequence: parsed.seq, device: batch.source_device_id, changes: batch.changes });
      }
      const groups = new Map();
      for (const batch of result) {
        if (!groups.has(batch.device)) groups.set(batch.device, []);
        groups.get(batch.device).push(batch);
      }
      for (const batches of groups.values()) {
        batches.sort((a, b) => a.sequence < b.sequence ? -1 : a.sequence > b.sequence ? 1 : 0);
        batches.forEach((batch, index) => assert.equal(batch.sequence, BigInt(index + 1), 'non-contiguous fixture sequence'));
      }
      return groups;
    },
    async inspectManifests(objects) {
      const prefix = `/dav/${repository}/data_governance/manifests/`;
      const result = new Map();
      for (const [path, wire] of objects) {
        if (!path.startsWith(prefix) || !path.endsWith('.json')) continue;
        const bytes = await options.codec.decode(wire);
        const value = remoteManifest.parseSyncManifest(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
        assert.ok(changeKeys.pathIdMatchesDevice(path.slice(prefix.length, -'.json'.length), value.device_id));
        assert.equal(result.has(value.device_id), false, 'duplicate fixture device manifest');
        result.set(value.device_id, value);
      }
      return result;
    },
    /** Verify the actual WebDAV object bytes, not only the local resource database. */
    async inspectBlobHashes(objects) {
      const prefix = `/dav/${repository}/data_governance/blobs/`;
      const result = new Map();
      const temp = mkdtempSync(join(root, 'sync-notes-blob-'));
      try {
        for (const [path, wire] of objects) {
          if (!path.startsWith(prefix)) continue;
          const match = /\/([0-9a-f]{64})\.[A-Za-z0-9]+$/u.exec(path);
          assert.ok(match, 'invalid fixture blob key');
          let bytes = wire;
          if (encrypted) {
            assert.equal(wire.subarray(0, 5).toString('hex'), '4453424b02');
            const input = join(temp, 'cipher');
            const output = join(temp, 'plain');
            writeFileSync(input, wire);
            // DSBK v2 is the production file/stream path; decryptBytes is deliberately v1-only.
            await desktopCrypto.decryptBackupFile(input, output, encryptionFixturePassword);
            bytes = readFileSync(output);
            rmSync(input); rmSync(output);
          }
          assert.equal(createHash('sha256').update(bytes).digest('hex'), match[1], 'remote plaintext blob hash mismatch');
          assert.equal(result.has(match[1]), false, 'duplicate fixture blob hash');
          result.set(match[1], bytes.length);
        }
        return result;
      } finally { rmSync(temp, { recursive: true, force: true }); }
    },
    verifyAndroidAttachment(noteId, originalResourceId) {
      const attachment = attachmentRepo.getByHash(db, androidFixtureHash);
      assert.ok(attachment, 'Android attachment found by content hash');
      assert.equal(attachment.name, 'android-fixture.txt');
      assert.equal(attachment.blobHash, androidFixtureHash);
      assert.equal(attachment.size, androidFixtureBytes.length);
      const content = attachmentRepo.getContentBounded(db, blobsDir, attachment.id, androidFixtureBytes.length);
      assert.ok(content, 'desktop attachment reader returned content');
      const bytes = Buffer.from(content, 'base64');
      assert.equal(bytes.length, androidFixtureBytes.length);
      assert.equal(createHash('sha256').update(bytes).digest('hex'), androidFixtureHash);
      assert.deepEqual(bytes, androidFixtureBytes);
      const links = relations.noteRelationRepository.list(db, null, noteId);
      assert.ok(links.some(link => link.resource_id === originalResourceId));
      assert.ok(links.some(link => link.resource_id === attachment.resourceId));
      return attachment;
    },
    close() { encryptedCodec?.dispose(); applyHooks.setDefaultNoteHistory(null); deps.configureNoteResourceRepos(null); storage.shutdown(); for (const increment of increments.reverse()) increment.unregister(); },
  };
}
