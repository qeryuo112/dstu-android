import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createSign, createVerify, generateKeyPairSync } from 'node:crypto';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { formatContinuityGrant, prepareContinuityGrant } from './prepare-continuity-grant.mjs';

const now = Math.floor(Date.now() / 1000);
const archive = Buffer.from('independently reviewed example ZIP bytes');
const sidecar = Buffer.from('independently reviewed example state');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const approved = {
  repositoryUrl: 'https://fixture.invalid/notes/', instanceId: 'repo', provider: 'WebDAV',
  endpointHint: 'webdav|endpoint=https://fixture.invalid|user=test|root=notes',
  applicationId: 'com.deepstudent.app', versionName: '0.20.0', versionCode: 20000,
  sourceInstallationId: 'old-installation', writerId: 'retired-writer', uploadSeq: 5,
  archiveSha256: hash(archive), sidecarSha256: hash(sidecar),
  targetInstallationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', operationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  issuedAtEpochSecond: now - 1, expiresAtEpochSecond: now + 100,
  attestations: ['writer-permanently-revoked', 'snapshot-sequence-verified', 'claim-store-atomic-immutable'],
  peerCanonicalIds: ['retired-writer', 'peer'],
};
const directories = [];
afterEach(async () => { await Promise.all(directories.splice(0).map(path => rm(path, { recursive: true, force: true }))); });
async function files() {
  const root = await mkdtemp(join(tmpdir(), 'grant-test-'));
  directories.push(root);
  const paths = { approval: join(root, 'approval.json'), archive: join(root, 'a.zip'), sidecar: join(root, 'a.json'), out: join(root, 'new.grant') };
  await Promise.all([writeFile(paths.approval, JSON.stringify(approved)), writeFile(paths.archive, archive), writeFile(paths.sidecar, sidecar)]);
  return paths;
}
test('precise canonical LF layout can be signed and verified independently', () => {
  const value = formatContinuityGrant(approved, now);
  const lines = value.toString().split('\n');
  assert.deepEqual(lines.slice(0, 5), ['dstu-continuity-grant-v1', approved.repositoryUrl, 'repo', 'WebDAV', approved.endpointHint]);
  assert.deepEqual(lines.slice(11, 15), [approved.archiveSha256, approved.sidecarSha256, approved.targetInstallationId, approved.operationId]);
  assert.deepEqual(lines.slice(17), [...approved.attestations, '2', 'peer', 'retired-writer', '']);
  const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const sign = createSign('RSA-SHA256').update(value).sign(keys.privateKey);
  assert.ok(createVerify('RSA-SHA256').update(value).verify(keys.publicKey, sign));
  assert.ok(!createVerify('RSA-SHA256').update(Buffer.concat([value, Buffer.from('\n')])).verify(keys.publicKey, sign));
});
test('independent authorizations, bounded time, targets and canonical repository are mandatory', () => {
  const digestAlias = hash(Buffer.from('peer')).slice(0, 16);
  for (const [field, value] of [
    ['attestations', []], ['expiresAtEpochSecond', now], ['expiresAtEpochSecond', now + 1_000_000],
    ['repositoryUrl', 'https://user:secret@fixture.invalid/'], ['endpointHint', 'password=demo'],
    ['operationId', 'not-this-device'], ['peerCanonicalIds', [digestAlias, 'peer', 'retired-writer']],
    ['uploadSeq', 999_999_999_999], ['peerCanonicalIds', ['retired-writer', 'retired-writer']],
  ]) assert.throws(() => formatContinuityGrant({ ...approved, [field]: value }, now));
  assert.throws(() => formatContinuityGrant({ ...approved, extra: true }, now), /INVALID_APPROVAL/u);
});
test('exact package bytes verified and output never overwrites', async () => {
  const paths = await files();
  await prepareContinuityGrant(paths);
  assert.deepEqual(await readFile(paths.out), formatContinuityGrant(approved, now));
  assert.equal((await stat(paths.out)).mode & 0o777, 0o600);
  await assert.rejects(prepareContinuityGrant(paths), { code: 'EEXIST' });
  assert.deepEqual(await readFile(paths.out), formatContinuityGrant(approved, now));
});
test('wrong archive or sidecar produces no output', async () => {
  const paths = await files();
  await writeFile(paths.archive, Buffer.from('other archive'));
  await assert.rejects(prepareContinuityGrant(paths), /ARCHIVE_HASH_MISMATCH/u);
  await assert.rejects(stat(paths.out), { code: 'ENOENT' });
  await writeFile(paths.archive, archive);
  await writeFile(paths.sidecar, Buffer.from('other state'));
  await assert.rejects(prepareContinuityGrant(paths), /SIDECAR_HASH_MISMATCH/u);
  await assert.rejects(stat(paths.out), { code: 'ENOENT' });
});
