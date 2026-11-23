import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { startWebDavFixture } from '../s1/sync-webdav-fixture.mjs';
import { fixtureHash, openDesktopFixture } from '../s1/sync-notes-desktop.mjs';

export async function verifyRestoreRoundtrip(runAndroid, scope) {
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
    const incoming = await desktop.secondNote();
    const parameters = {
      ENDPOINT: webdav.endpoint, USERNAME: webdav.username, WEBDAV: webdav.password,
      ORIGINAL: original.noteId, INCOMING: incoming, INSTANCE: instance, BLOB: path.split('/').slice(-2).join('/'),
    };
    const baseline = webdav.snapshot();
    await runAndroid({ root, parameters, port: webdav.port });
    const result = webdav.snapshot();
    for (const [key, bytes] of baseline) if (key.includes('/vfs_blobs/') || key.endsWith('/instance.json')) {
      assert.deepEqual(result.get(key), bytes, 'restored installation changed an original repository object');
    }
    const groups = await desktop.inspectChanges(result);
    const [, batches] = [...groups].find(([id]) => id !== desktop.deviceId) ?? [];
    assert.deepEqual(batches?.map(batch => Number(batch.sequence)), [1, 2]);
    assert.ok(batches[0].changes.some(change => change.table_name === 'notes' && change.record_id === original.noteId));
    assert.ok(!batches[0].changes.some(change => change.record_id === 'pre-restore-delete'));
    assert.ok(batches[1].changes.some(change => change.table_name === 'notes' && change.record_id === incoming));
    await desktop.receive();
    assert.equal(desktop.notes.getNoteContent(original.noteId, desktop.db), 'Edited after normal restore');
    assert.ok(desktop.notes.getNoteIncludingDeleted(incoming, desktop.db).deletedAt);
    const created = desktop.notes.listNotes('New on restored installation', 10, 0, desktop.db);
    assert.equal(created.length, 1);
    assert.equal(desktop.notes.getNoteContent(created[0].id, desktop.db), 'Created after normal restore');
    return { event: 's2.16.desktop.roundtrip', scope, newWriterSequences: [1, 2], originalInstance: true };
  } finally {
    desktop?.close();
    await webdav?.close();
    await rm(root, { recursive: true, force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const result = await verifyRestoreRoundtrip(async ({ root, parameters }) => {
    const env = { ...process.env, DSTU_S216_HOST_FIXTURE: root };
    for (const [key, value] of Object.entries(parameters)) env[`DSTU_S216_HOST_${key}`] = value;
    const child = spawn(process.execPath, ['tools/s1/gradle.mjs', ':app:testDebugUnitTest',
      '--tests', '*OrdinaryRestoreBaselineTest', '--no-daemon', '--offline'], { stdio: 'inherit', env });
    const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
    assert.equal(code, 0, 'Android JVM restore/sync failed');
  }, 'host-only');
  console.log(JSON.stringify(result));
}
