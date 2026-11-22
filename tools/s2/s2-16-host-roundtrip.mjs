import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startWebDavFixture } from '../s1/sync-webdav-fixture.mjs';
import { fixtureHash, openDesktopFixture } from '../s1/sync-notes-desktop.mjs';

const root = await mkdtemp(join(tmpdir(), 's2-16-host-roundtrip-'));
const webdav = await startWebDavFixture();
let desktop;
try {
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
  const env = {
    ...process.env,
    DSTU_S216_HOST_FIXTURE: root,
    DSTU_S216_HOST_ENDPOINT: webdav.endpoint,
    DSTU_S216_HOST_USERNAME: webdav.username,
    DSTU_S216_HOST_WEBDAV: webdav.password,
    DSTU_S216_HOST_ORIGINAL: original.noteId,
    DSTU_S216_HOST_INCOMING: incoming,
    DSTU_S216_HOST_INSTANCE: instance,
    DSTU_S216_HOST_BLOB: path.split('/').slice(-2).join('/'),
  };
  const child = spawn(process.execPath, ['tools/s1/gradle.mjs', ':app:testDebugUnitTest',
    '--tests', '*OrdinaryRestoreBaselineTest', '--no-daemon', '--offline'], { stdio: 'inherit', env });
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  assert.equal(code, 0, 'Android JVM restore/sync failed');
  const groups = await desktop.inspectChanges(webdav.snapshot());
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
  console.log(JSON.stringify({ event: 's2.16.desktop.roundtrip', scope: 'host-only', newWriterSequences: [1, 2], originalInstance: true }));
} finally {
  desktop?.close();
  await webdav.close();
  await rm(root, { recursive: true, force: true });
}
