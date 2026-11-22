import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { startWebDavFixture } from './sync-webdav-fixture.mjs';
import { encryptionFixturePassword, fixtureBytes, fixtureContent, fixtureHash, openDesktopFixture } from './sync-notes-desktop.mjs';
import { assertNoCanaries, assertSameObjects, mutationCount } from './sync-notes-wire.mjs';

test('snapshot and log guard reject changes without printing canaries', () => {
  const before = new Map([['/first', Buffer.from('one')], ['/second', Buffer.from('two')]]);
  assertSameObjects(before, new Map([...before].reverse()));
  for (const after of [new Map([['/first', Buffer.from('one')]]),
    new Map([['/first', Buffer.from('one')], ['/second', Buffer.from('changed')]])]) {
    assert.throws(() => assertSameObjects(before, after), /remote object/u);
  }
  const canary = 'public-secret-canary';
  assert.doesNotThrow(() => assertNoCanaries('event=phase status=ok', [canary]));
  for (const text of [`bad ${canary}`, 'Authorization: Basic aGVsbG86d29ybGQ=']) {
    assert.throws(() => assertNoCanaries(text, [canary]), error => !error.message.includes(canary));
  }
  assert.equal(mutationCount({ 'GET:200': 10, 'PUT:201': 2, 'DELETE:204': 1, 'MKCOL:405': 1 }), 4);
});

test('desktop fixture refuses to direct credentials to a non-loopback endpoint', async () => {
  await assert.rejects(openDesktopFixture('/tmp', { endpoint: 'http://example.invalid/dav/', port: 80 }, true));
});

for (const encrypted of [false, true]) test(`frozen desktop ${encrypted ? 'encrypted' : 'plain'} local WebDAV wire is self-consistent`, async () => {
  const root = mkdtempSync(join(tmpdir(), 'sync-notes-selftest-'));
  const fixture = await startWebDavFixture();
  let desktop;
  try {
    desktop = await openDesktopFixture(root, fixture, encrypted);
    const seeded = await desktop.seed();
    assert.equal(seeded.hash, fixtureHash);
    const first = fixture.snapshot();
    const firstGroups = await desktop.inspectChanges(first);
    assert.equal(firstGroups.size, 1);
    const initial = firstGroups.get(desktop.deviceId);
    assert.equal(initial.length, 1);
    assert.equal(initial[0].sequence, 1n);
    assert.ok(initial[0].changes.some(change => change.record_id === seeded.noteId));
    const manifest = (await desktop.inspectManifests(first)).get(desktop.deviceId);
    assert.ok(manifest);
    assert.equal(BigInt(manifest.published_max_seq), 1n);
    assert.equal((await desktop.inspectBlobHashes(first)).get(fixtureHash), fixtureBytes.length);
    if (encrypted) {
      await desktop.verifyAndroidMarker('s1-26');
      for (const value of first.values()) assertNoCanaries(value, [fixture.username, fixture.password, encryptionFixturePassword, fixtureContent]);
      const tampered = new Map(first);
      const payload = Buffer.from(first.get(initial[0].path));
      payload[payload.length - 1] ^= 1;
      tampered.set(initial[0].path, payload);
      await assert.rejects(desktop.inspectChanges(tampered));
      const badBlob = new Map(first);
      const blobKey = [...first.keys()].find(key => key.includes(`/data_governance/blobs/${fixtureHash.slice(0, 2)}/`));
      assert.ok(blobKey);
      const blob = Buffer.from(first.get(blobKey));
      blob[blob.length - 1] ^= 1;
      badBlob.set(blobKey, blob);
      await assert.rejects(desktop.inspectBlobHashes(badBlob));
    }
    const secondNote = await desktop.secondNote();
    const second = fixture.snapshot();
    const groups = await desktop.inspectChanges(second);
    const batches = groups.get(desktop.deviceId);
    assert.deepEqual(batches.map(batch => batch.sequence), [1n, 2n]);
    assert.ok(batches[1].changes.some(change => change.record_id === secondNote));
    assert.equal(BigInt((await desktop.inspectManifests(second)).get(desktop.deviceId).published_max_seq), 2n);
    assertSameObjects(first, first);
    assert.throws(() => assertSameObjects(first, second), /remote object/u);
  } finally {
    desktop?.close();
    await fixture.close();
    rmSync(root, { recursive: true, force: true });
  }
});
