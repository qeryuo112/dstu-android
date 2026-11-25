import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { access, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { startWebDavFixture } from '../s1/sync-webdav-fixture.mjs';
import { androidFixtureHash, desktopRestoreHash, fixtureHash, openDesktopFixture } from '../s1/sync-notes-desktop.mjs';

export async function verifyRestoreRoundtrip(runAndroid, scope, { attachments = false } = {}) {
  const root = await mkdtemp(join(tmpdir(), 's2-16-roundtrip-'));
  let webdav;
  let desktop;
  try {
    webdav = await startWebDavFixture();
    desktop = await openDesktopFixture(root, webdav);
    const original = await desktop.seed();
    const bound = await desktop.cloud.get('data_governance/instance.json');
    const { instance_id: instance } = JSON.parse(Buffer.from(bound).toString());
    assert.ok(instance);
    const snapshot = join(root, 'frozen-desktop-vfs.db');
    const attachment = join(root, 'fixture-blob');
    desktop.snapshotVfs(snapshot);
    const objects = webdav.snapshot();
    const path = [...objects.keys()].find(key => key.includes(`/${fixtureHash}.`));
    assert.ok(path);
    await writeFile(attachment, objects.get(path), { flag: 'wx' });
    const inbound = attachments ? await desktop.publishRestoreAttachmentScenario(original.fileId) : null;
    const incoming = inbound?.noteId ?? await desktop.secondNote();
    const parameters = {
      ENDPOINT: webdav.endpoint, USERNAME: webdav.username, WEBDAV: webdav.password,
      ORIGINAL: original.noteId, INCOMING: incoming, INSTANCE: instance, BLOB: path.split('/').slice(-2).join('/'),
      ...(inbound ? { ORIGINAL_FILE: original.fileId, INCOMING_FILE: inbound.fileId, INCOMING_HASH: desktopRestoreHash, REMOVED_NOTE: inbound.removedNoteId } : {}),
    };
    const baseline = webdav.snapshot();
    let verifiedAttachment;
    await runAndroid({ root, parameters, port: webdav.port, verifyAndroidEdit: async () => {
      assert.ok(attachments && !verifiedAttachment, 'unexpected Android attachment checkpoint');
      const objects = webdav.snapshot();
      const groups = await desktop.inspectChanges(objects);
      const [, batches] = [...groups].find(([id]) => id !== desktop.deviceId) ?? [];
      assert.deepEqual(batches?.map(batch => Number(batch.sequence)), [1]);
      await desktop.receive();
      verifiedAttachment = desktop.verifyAndroidAttachment(original.noteId, original.resourceId, 'restored-android.txt');
      assert.ok((await desktop.inspectBlobHashes(objects)).has(androidFixtureHash));
    } });
    const result = webdav.snapshot();
    for (const [key, bytes] of baseline) if (key.includes('/vfs_blobs/') || key.endsWith('/instance.json')) {
      assert.deepEqual(result.get(key), bytes, 'restored installation changed an original repository object');
    }
    const groups = await desktop.inspectChanges(result);
    const [, batches] = [...groups].find(([id]) => id !== desktop.deviceId) ?? [];
    const sequences = attachments ? [1, 2, 3] : [1, 2];
    assert.deepEqual(batches?.map(batch => Number(batch.sequence)), sequences);
    assert.ok(batches[0].changes.some(change => change.table_name === 'notes' && change.record_id === original.noteId));
    assert.ok(!batches[0].changes.some(change => change.record_id === 'pre-restore-delete'));
    assert.ok(batches.at(-1).changes.some(change => change.table_name === 'notes' && change.record_id === incoming));
    await desktop.receive();
    assert.equal(desktop.notes.getNoteContent(original.noteId, desktop.db), 'Edited after normal restore');
    assert.ok(desktop.notes.getNoteIncludingDeleted(incoming, desktop.db).deletedAt);
    const created = desktop.notes.listNotes('New on restored installation', 10, 0, desktop.db);
    assert.equal(created.length, 1);
    assert.equal(desktop.notes.getNoteContent(created[0].id, desktop.db), 'Created after normal restore');
    if (attachments) {
      assert.ok(verifiedAttachment, 'desktop did not read the live Android attachment');
      const renamed = batches[1].changes.find(change => change.table_name === 'files' && change.record_id === verifiedAttachment.id);
      assert.equal(renamed?.data?.name, 'restored-android-renamed.txt');
      const deleted = batches[2].changes.find(change => change.table_name === 'files' && change.record_id === verifiedAttachment.id);
      assert.ok(deleted?.data?.deleted_at);
      desktop.verifyAndroidAttachmentDeletion(verifiedAttachment.id, verifiedAttachment.resourceId);
      assert.ok((await desktop.inspectBlobHashes(result)).has(desktopRestoreHash));
    }
    return { event: attachments ? 's2.30.desktop.restore-roundtrip' : 's2.16.desktop.roundtrip',
      scope, newWriterSequences: sequences, originalInstance: true, ...(attachments ? { attachments: 'bidirectional' } : {}) };
  } finally {
    desktop?.close();
    await webdav?.close();
    await rm(root, { recursive: true, force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const flags = process.argv.slice(2);
  assert.ok(flags.length === 0 || (flags.length === 1 && flags[0] === '--with-attachments'));
  const attachments = flags.includes('--with-attachments');
  const result = await verifyRestoreRoundtrip(async ({ root, parameters, verifyAndroidEdit }) => {
    const env = { ...process.env, DSTU_S216_HOST_FIXTURE: root, DSTU_S230_HOST_ATTACHMENTS: String(attachments) };
    for (const [key, value] of Object.entries(parameters)) env[`DSTU_S216_HOST_${key}`] = value;
    const child = spawn(process.execPath, ['tools/s1/gradle.mjs', ':app:testDebugUnitTest',
      '--tests', '*OrdinaryRestoreBaselineTest', '--offline'], { stdio: 'inherit', env });
    let finished = false;
    const completion = new Promise((done, reject) => { child.once('error', reject); child.once('close', done); })
      .finally(() => { finished = true; });
    try {
      if (attachments) {
        const ready = join(root, 's230-android-attachment-ready');
        const deadline = Date.now() + 150_000;
        while (!await access(ready).then(() => true, () => false)) {
          assert.ok(!finished && Date.now() < deadline, 'JVM did not reach the attachment checkpoint');
          await Promise.race([completion, delay(150)]);
        }
        await verifyAndroidEdit();
        await writeFile(join(root, 's230-desktop-readback'), '', { flag: 'wx' });
      }
      assert.equal(await completion, 0, 'Android JVM restore/sync failed');
    } catch (error) {
      await completion.catch(() => {});
      throw error;
    }
  }, 'host-only', { attachments });
  console.log(JSON.stringify(result));
}
