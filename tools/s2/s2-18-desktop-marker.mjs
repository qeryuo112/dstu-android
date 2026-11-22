import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile, unlink, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { DESKTOP, DESKTOP_COMMIT } from './s2-14-desktop-interop.mjs';

const [mode, directory] = process.argv.slice(2);
assert.ok(directory && ['fixtures', 'verify'].includes(mode) && process.argv.length === 4);
const root = resolve(directory);
const frozen = args => execFileSync('git', args, { cwd: DESKTOP, encoding: 'utf8' }).trim();
assert.equal(frozen(['rev-parse', 'HEAD']), DESKTOP_COMMIT);
assert.equal(frozen(['diff', '--name-only', DESKTOP_COMMIT, '--']), '');
const load = path => import(pathToFileURL(join(DESKTOP, path)).href);
const [crypto, native, marker, claim] = await Promise.all([
  load('host/platform/secrets/backup-crypto.ts'), load('host/platform/native/argon2/index.ts'),
  load('host/plugins/sync-providers/src/marker.ts'), load('host/plugins/sync-providers/src/e2ee_claim.ts'),
]);
const backup = crypto.createBackupCrypto({ nativeArgon2: native.argon2 });
const password = 'pw-生产-🔐';
const identity = { createdByDevice: 'frozen-desktop-writer', createdAt: '2026-01-01T12:00:00Z' };
const legacy = marker.encodeEncryptionMarker({ version: 1, ...identity });
const markerKey = '.encryption-marker';
const encrypted = join(root, 'backup-v2.dsbk');

if (mode === 'fixtures') {
  const source = join(root, 'synthetic-backup-content');
  await writeFile(source, Buffer.alloc(1024 * 1024 + 41, 0x53), { flag: 'wx' });
  try { await backup.encryptBackupFile(source, encrypted, password); }
  finally { await unlink(source); }
  await writeFile(join(root, 'desktop-marker-v1.json'), legacy, { flag: 'wx' });
  const bytes = await readFile(encrypted);
  const plan = backup.planFirstChunkTrial(bytes, bytes.length);
  assert.equal(plan.kind, 'StreamV2');
  assert.ok(plan.prefixLen < bytes.length);
  await backup.trialDecryptFirstChunk(bytes.subarray(0, plan.prefixLen), bytes.length, password);
} else {
  const updated = await readFile(join(root, 'android-marker-v2.json'));
  const value = marker.decodeEncryptionMarker(updated);
  assert.equal(value.version, 2);
  assert.equal(value.createdByDevice, identity.createdByDevice);
  assert.equal(value.createdAt, identity.createdAt);
  assert.equal(await backup.checkPasswordVerifier(password, value.keyVerifier), true);
  assert.equal(await backup.checkPasswordVerifier('incorrect', value.keyVerifier), false);
  const files = new Map([[markerKey, updated]]);
  let puts = 0;
  const storage = {
    stat: async key => files.has(key) ? { size: files.get(key).length } : null,
    getBounded: async key => files.get(key) ?? null,
    put: async (key, bytes) => { files.set(key, bytes); if (key === markerKey) puts++; },
    delete: async key => { files.delete(key); },
  };
  await assert.rejects(claim.claimEncryptionMarker(storage, { markerKey, deviceIdShort: 'desktop-retry',
    ttlMs: 60_000, expectation: 'LegacyV1', buildMarker: () => { throw new Error('must not build'); } }), /E_SYNC_E2EE_CLAIM_CONFLICT/u);
  assert.equal(puts, 0);
  assert.equal(files.size, 1);
  assert.deepEqual(files.get(markerKey), updated);
}
console.log(JSON.stringify({ event: 's2.18.desktop.marker', mode, desktopCommit: DESKTOP_COMMIT }));
