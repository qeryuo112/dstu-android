import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { after, before, test } from 'node:test';
import { DESKTOP, DESKTOP_COMMIT, LEGACY_SHA256, convertV1, inspectZip, makeFixtures, verifyAndroid } from './s2-14-desktop-interop.mjs';

const PASSWORD = 's214-test-password';
const expectedCanonical = '472ef87a16776e7901ed2cbb327e871088bc4434009831f934879795100e506f';
const file = name => join(out, name);
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const oraclePath = fileURLToPath(new URL('./s2-14-desktop-interop.mjs', import.meta.url));
let dir, out, report, desktopZip, desktopCrypto;

before(async () => {
  dir = await mkdtemp(join(tmpdir(), 's214-oracle-test-'));
  out = join(dir, 'fixtures');
  report = await makeFixtures(out, PASSWORD);
  desktopZip = await import(pathToFileURL(join(DESKTOP, 'host/platform/zip/index.ts')).href);
  const crypto = await import(pathToFileURL(join(DESKTOP, 'host/platform/secrets/backup-crypto.ts')).href);
  const native = await import(pathToFileURL(join(DESKTOP, 'host/platform/native/argon2/index.ts')).href);
  desktopCrypto = crypto.createBackupCrypto({ nativeArgon2: native.argon2 });
});
after(async () => { if (dir) await rm(dir, { recursive: true, force: true }); });

async function writeZip(path, entries) {
  const writer = await desktopZip.ZipFileWriter.create(path);
  try {
    for (const [name, value, compression = 'stored'] of entries) {
      await writer.addBuffer(name, value, { compression });
    }
    await writer.finish();
  } catch (error) { await writer.abort(); throw error; }
}
function options(input) { return { input: file(input), password: PASSWORD, expectedZip: file('controlled-full.zip') }; }

test('frozen source provenance, actual desktop import, real database/blob hashes and no audit material', async () => {
  assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: DESKTOP, encoding: 'utf8' }).trim(), DESKTOP_COMMIT);
  assert.equal(report.desktopCommit, DESKTOP_COMMIT);
  assert.equal(report.legacySourceSha256, LEGACY_SHA256);
  assert.match(report.desktopPortableV2, /production exportBackupToZip.*importBackupFromZip succeeded/);
  assert.match(report.desktopPortableV1, /conversion.*importBackupFromZip succeeded/);
  assert.match(report.desktopPortableV2, /slot restore not executed/);
  assert.match(report.wholeZipDsbk, /NOT desktop normal/);
  assert.match(report.desktopAssetPort, /restoreAssetsWithProgress.*vfs_blobs.*not a slot restore/);
  assert.equal(report.fullZipCanonicalSha256, expectedCanonical);
  assert.deepEqual(report.fullZipEntries.map(e => e.path), [
    'assets/vfs_blobs/25/254349debf613fe19e0aae72db11d40cf52f86a0269b6cca09675ab1c2fc340a.txt',
    'chat_v2.db', 'llm_usage.db', 'manifest.json', 'mistakes.db', 'vfs.db',
  ]);
  assert.equal(report.fullZipEntries[0].sha256, '254349debf613fe19e0aae72db11d40cf52f86a0269b6cca09675ab1c2fc340a');
  assert.equal(report.fullZipEntries.at(-1).sha256, '73706ef8747809aef34e3f37a90b379693766150de795b49c19cad3be5fa6e26');
  for (const [name, record] of Object.entries(report.outputs)) {
    const bytes = await readFile(file(name));
    assert.equal(bytes.length, record.size); assert.equal(sha(bytes), record.sha256);
  }
  const persisted = JSON.parse(await readFile(file('oracle.json'), 'utf8'));
  assert.deepEqual(persisted, report);
  assert.ok(!JSON.stringify(persisted).includes(PASSWORD));
});

test('both whole-ZIP v1/v2 are real production-parameter desktop crypto, not invented frames', async () => {
  for (const version of [1, 2]) {
    const input = file(`controlled-full-v${version}.dsbk`), data = await readFile(input);
    assert.equal(data.subarray(0, 4).toString(), 'DSBK');
    assert.equal(data[4], version);
    assert.deepEqual([data.readUInt32LE(5), data.readUInt32LE(9), data.readUInt32LE(13)], [65536, 3, 4]);
    const decrypted = join(dir, `decrypted-v${version}.zip`);
    await desktopCrypto.decryptBackupFile(input, decrypted, PASSWORD);
    assert.equal(sha(await readFile(decrypted)), sha(await readFile(file('controlled-full.zip'))));
    assert.equal((await inspectZip(decrypted)).canonicalSha256, expectedCanonical);
  }
});

test('portable payload exports use v2 and realistically converted v1, not whole-archive encryption', async () => {
  for (const version of [1, 2]) {
    const bytes = await readFile(file(`desktop-portable-payload-v${version}.dsbk`));
    assert.equal(bytes.subarray(0, 4).toString(), 'DSBK'); assert.equal(bytes[4], version);
    const outFile = join(dir, `inner-${version}.zip`);
    await desktopCrypto.decryptBackupFile(file(`desktop-portable-payload-v${version}.dsbk`), outFile, PASSWORD);
    const inner = await inspectZip(outFile);
    assert.deepEqual(inner.entries.map(e => e.path), ['manifest.json']);
    assert.notEqual(inner.canonicalSha256, expectedCanonical);
  }
});

test('raw v2 canonical match is not misreported as desktop import or slot restore', async () => {
  const result = await verifyAndroid(options('controlled-full-v2.dsbk'));
  assert.equal(result.canonicalSha256, expectedCanonical);
  assert.match(result.verification, /frozen desktop crypto file port/);
  assert.equal(result.desktopImport.result, 'rejected');
  assert.match(result.desktopImport.reason, /未加密 ZIP 必须声明 key_policy=excluded_portable/);
  assert.match(result.desktopImport.scope, /no slot restore/);
  assert.equal(result.desktopSlotRestoreVerified, false);
  assert.match(result.inputOrigin, /not attested/);
});

test('desktop-shaped ZIP uses actual frozen import and verifies independent imported bytes', async () => {
  const result = await verifyAndroid(options('desktop-portable-v2.zip'));
  assert.equal(result.canonicalSha256, expectedCanonical);
  assert.equal(result.desktopImport.result, 'succeeded');
  assert.match(result.desktopImport.scope, /slot restore not executed/);
  assert.equal(result.desktopSlotRestoreVerified, false);
  assert.match(result.verification, /authenticated and imported/);
  // This is a self-test with a frozen DESKTOP fixture, not an Android device receipt.
});

test('wrong password, changed ciphertext and truncation fail authentication', async () => {
  const orig = await readFile(file('controlled-full-v2.dsbk'));
  const tampered = Buffer.from(orig); tampered[tampered.length - 1] ^= 1;
  const broken = join(dir, 'tampered.dsbk'), cut = join(dir, 'cut.dsbk');
  await writeFile(broken, tampered); await writeFile(cut, orig.subarray(0, orig.length - 12));
  await assert.rejects(() => verifyAndroid({ ...options('controlled-full-v2.dsbk'), password: 'wrong-password' }));
  await assert.rejects(() => verifyAndroid({ ...options('controlled-full-v2.dsbk'), input: broken }));
  await assert.rejects(() => verifyAndroid({ ...options('controlled-full-v2.dsbk'), input: cut }));
  await assert.rejects(() => verifyAndroid({ ...options('desktop-portable-v2.zip'), password: 'wrong-password' }));
  const zipped = Buffer.from(await readFile(file('desktop-portable-v2.zip')));
  const marker = zipped.indexOf(Buffer.from('DSBK\x02'));
  assert.ok(marker > 0); zipped[marker + 50] ^= 1;
  const damagedZip = join(dir, 'portable-tampered.zip'); await writeFile(damagedZip, zipped);
  await assert.rejects(() => verifyAndroid({ ...options('desktop-portable-v2.zip'), input: damagedZip }));
});

test('reference is required and wrong expected hash or unsupported v1 is rejected', async () => {
  await assert.rejects(() => verifyAndroid({ input: file('controlled-full-v2.dsbk'), password: PASSWORD }), /exactly one independent/);
  await assert.rejects(() => verifyAndroid({ ...options('controlled-full-v2.dsbk'), expectedCanonicalSha256: '0'.repeat(64) }), /exactly one independent/);
  await assert.rejects(() => verifyAndroid({ input: file('controlled-full-v2.dsbk'), password: PASSWORD, expectedCanonicalSha256: '0'.repeat(64) }), /canonical ZIP content mismatch/);
  await assert.rejects(() => verifyAndroid({ ...options('controlled-full-v2.dsbk'), input: file('controlled-full-v1.dsbk') }), /must be version 2/);
  await assert.rejects(() => verifyAndroid({ ...options('desktop-portable-v1-converted.zip') }), /must contain a DSBK v2/);
});

test('valid DSBK encrypting non-ZIP bytes cannot pass as backup verification', async () => {
  const plain = join(dir, 'not-zip.bin'), encrypted = join(dir, 'not-zip.dsbk');
  await writeFile(plain, 'this is not a ZIP');
  await desktopCrypto.encryptBackupFile(plain, encrypted, PASSWORD);
  await assert.rejects(() => verifyAndroid({ input: encrypted, password: PASSWORD, expectedCanonicalSha256: expectedCanonical }));
});

test('canonical hashes compare actual bytes independent of ZIP order and compression', async () => {
  const a = join(dir, 'a.zip'), b = join(dir, 'b.zip'), c = join(dir, 'c.zip');
  await writeZip(a, [['alpha.txt', 'hello world'], ['zeta.txt', 'test body']]);
  await writeZip(b, [['zeta.txt', 'test body', 'deflated'], ['alpha.txt', 'hello world', 'deflated']]);
  await writeZip(c, [['zeta.txt', 'changed body'], ['alpha.txt', 'hello world']]);
  assert.equal((await inspectZip(a)).canonicalSha256, (await inspectZip(b)).canonicalSha256);
  assert.notEqual((await inspectZip(a)).canonicalSha256, (await inspectZip(c)).canonicalSha256);
  const duplicate = join(dir, 'duplicate.zip');
  await writeZip(duplicate, [['same', 'one'], ['same', 'two']]);
  await assert.rejects(() => inspectZip(duplicate), /重复路径|duplicate/i);
});

test('CLI requires a password environment variable and reports honest import boundary', () => {
  const env = { ...process.env, S214_ORACLE_TEST_PASSWORD: PASSWORD };
  const ok = spawnSync(process.execPath, [oraclePath, 'verify-android', '--input', file('controlled-full-v2.dsbk'),
    '--password-env', 'S214_ORACLE_TEST_PASSWORD', '--expect-canonical-sha256', expectedCanonical], { encoding: 'utf8', env });
  assert.equal(ok.status, 0, ok.stderr);
  assert.equal(JSON.parse(ok.stdout).desktopImport.result, 'rejected');
  assert.ok(!ok.stdout.includes(PASSWORD)); assert.ok(!ok.stderr.includes(PASSWORD));
  const missing = spawnSync(process.execPath, [oraclePath, 'verify-android', '--input', file('controlled-full-v2.dsbk'),
    '--password-env', 'S214_MISSING_ENV', '--expect-canonical-sha256', expectedCanonical], { encoding: 'utf8', env });
  assert.notEqual(missing.status, 0);
  assert.match(missing.stderr, /password environment variable is missing/);
});

test('fixture creation does not overwrite existing directories or leave artifacts on a failed new output', async () => {
  await assert.rejects(() => makeFixtures(out, PASSWORD), /EEXIST/);
  assert.equal((await inspectZip(file('controlled-full.zip'))).canonicalSha256, expectedCanonical);
  await assert.rejects(() => makeFixtures(join(dir, 'short'), 'short'), /at least 8/);
});


test('bounded v1 conversion verifies v2 before atomic publication and preserves old output on failure', async () => {
  const input = file('controlled-full-v1.dsbk'), output = join(dir, 'converted.dsbk');
  const config = { input, output, password: PASSWORD, maxInputBytes: 5_000_000, maxPlaintextBytes: 5_000_000 };
  const result = await convertV1(config);
  assert.equal(result.plaintextSha256, sha(await readFile(file('controlled-full.zip'))));
  assert.equal((await verifyAndroid({ input: output, password: PASSWORD, expectedZip: file('controlled-full.zip') })).canonicalSha256, expectedCanonical);
  const published = await readFile(output);
  await assert.rejects(() => convertV1({ ...config, password: 'wrong-password' }));
  await assert.rejects(() => convertV1({ ...config, maxInputBytes: 5 }));
  await assert.rejects(() => convertV1({ ...config, maxPlaintextBytes: 5 }));
  await assert.rejects(() => convertV1({ ...config, maxPlaintextBytes: 256 * 1024 * 1024 + 1 }));
  await assert.rejects(() => convertV1({ ...config, input: file('controlled-full-v2.dsbk') }), /must be DSBK v1/);
  const oversizedKdf = Buffer.from(await readFile(input));
  oversizedKdf.writeUInt32LE(262145, 5);
  const kdfInput = join(dir, 'oversized-kdf.dsbk'); await writeFile(kdfInput, oversizedKdf);
  await assert.rejects(() => convertV1({ ...config, input: kdfInput }), /KDF parameters exceed/);
  await assert.rejects(() => convertV1({ ...config, output: input }), /must differ/);
  const linked = join(dir, 'linked.dsbk'); await symlink(input, linked);
  await assert.rejects(() => convertV1({ ...config, input: linked }));
  assert.deepEqual(await readFile(output), published);
  assert.ok((await readdir(dir)).every(name => !name.startsWith('.s214-v1-convert-')));
});

test('explicit conversion accepts an authenticated v1 larger than Android in-memory limit', async () => {
  const size = 65 * 1024 * 1024 + 7;
  const plain = Buffer.alloc(size, 0x52);
  const plainHash = sha(plain);
  const input = join(dir, 'large-v1.dsbk'), output = join(dir, 'large-v2.dsbk');
  const ciphertext = await desktopCrypto.encryptBackup(plain, PASSWORD);
  plain.fill(0);
  await writeFile(input, ciphertext); ciphertext.fill(0);
  const result = await convertV1({ input, output, password: PASSWORD,
    maxInputBytes: size + 61, maxPlaintextBytes: size });
  assert.equal(result.plaintextBytes, size);
  assert.equal(result.plaintextSha256, plainHash);
  assert.equal((await readFile(output)).subarray(0, 5).toString('binary'), 'DSBK\x02');
  assert.match(result.conversion, /no ZIP\/slot restore claim/);
  assert.ok((await readdir(dir)).every(name => !name.startsWith('.s214-v1-convert-')));
});

async function readZipEntries(path) {
  const reader = await desktopZip.ZipFileReader.open(path);
  try {
    const result = [];
    for (const entry of reader.entries) {
      assert.equal(entry.isDirectory, false);
      result.push([entry.name, await reader.readEntry(entry)]);
    }
    return result;
  } finally { await reader.close(); }
}

function checksumLedger(entries) {
  return Buffer.from(entries.filter(([name]) => name !== 'checksums.sha256')
    .map(([name, bytes]) => `${sha(bytes)}  ${name}`).join('\n'));
}

let variantNumber = 0;
async function portableVariant(entries, change, withChecksums = true) {
  const data = entries.map(([name, bytes]) => [name, Buffer.from(bytes)]);
  await change(data);
  const withoutChecksums = data.filter(([name]) => name !== 'checksums.sha256');
  if (withChecksums) withoutChecksums.push(['checksums.sha256', checksumLedger(withoutChecksums)]);
  const path = join(dir, `portable-variant-${variantNumber++}.zip`);
  await writeZip(path, withoutChecksums);
  return path;
}

function replaceEntry(entries, name, bytes) {
  const item = entries.find(([path]) => path === name);
  assert.ok(item, `missing test entry ${name}`);
  item[1] = Buffer.from(bytes);
}
function updatePayloadDeclaration(entries) {
  const payload = entries.find(([name]) => name === 'portable_secrets.dsbk')[1];
  const outer = JSON.parse(entries.find(([name]) => name === 'manifest.json')[1]);
  Object.assign(outer.files.find(file => file.path === 'portable_secrets.dsbk'), { size: payload.length, sha256: sha(payload) });
  replaceEntry(entries, 'manifest.json', JSON.stringify(outer, null, 2));
}

async function richPortableFixture(size) {
  const skillPath = 'persistent/user_skills/fixture-skill.bin';
  const skill = Buffer.alloc(size, 0x5a);
  const original = JSON.parse((await readZipEntries(file('controlled-full.zip')))
    .find(([name]) => name === 'manifest.json')[1]);
  const seedPath = 'crypto/.secure/.key_seed', credentialPath = 'crypto/.secure/test.enc';
  const seed = Buffer.from('ab'.repeat(32));
  const secure = await import(pathToFileURL(join(DESKTOP, 'host/platform/secrets/secure-store.ts')).href);
  const key = secure.deriveKey(seed.toString(), secure.SECURE_SALT_V3);
  const credential = secure.encryptWithKey(key, 'test-only-secret'); key.fill(0);
  original.key_policy = 'included_local';
  original.files.push({ path: skillPath, size, sha256: sha(skill) },
    { path: seedPath, size: seed.length, sha256: sha(seed) },
    { path: credentialPath, size: credential.length, sha256: sha(credential) });
  Object.assign(original.coverage.domains.crypto, { status: 'complete', paths: [seedPath, credentialPath].sort(),
    file_count: 2, total_size: seed.length + credential.length });
  Object.assign(original.coverage.domains['user-skills'], {
    status: 'complete', paths: [skillPath], file_count: 1, total_size: size,
  });
  const originalBytes = Buffer.from(JSON.stringify(original));
  const inner = join(dir, `rich-inner-${variantNumber}.zip`);
  await writeZip(inner, [['manifest.json', originalBytes], [skillPath, skill], [seedPath, seed], [credentialPath, credential]]);
  const sealed = await desktopCrypto.encryptBackup(await readFile(inner), PASSWORD);
  const base = await readZipEntries(file('desktop-portable-v1-converted.zip'));
  const input = await portableVariant(base, entries => {
    replaceEntry(entries, 'portable_secrets.dsbk', sealed);
    updatePayloadDeclaration(entries);
  });
  const records = report.fullZipEntries.filter(entry => entry.path !== 'manifest.json').concat([
    { path: 'manifest.json', size: originalBytes.length, sha256: sha(originalBytes) },
    { path: skillPath, size, sha256: sha(skill) },
    { path: seedPath, size: seed.length, sha256: sha(seed) },
    { path: credentialPath, size: credential.length, sha256: sha(credential) },
  ]).sort((a, b) => Buffer.compare(Buffer.from(a.path), Buffer.from(b.path)));
  const canonical = sha(Buffer.from(records.map(entry => `${entry.path}\t${entry.size}\t${entry.sha256}\n`).join('')));
  return { input, canonical, skillPath, seedPath, credentialPath, originalBytes, skillHash: sha(skill),
    seedHash: sha(seed), credentialHash: sha(credential) };
}

async function expectPortableRejected(input, mutateOptions = {}, reason) {
  const output = join(dir, `rejected-${variantNumber++}.zip`);
  const before = sha(await readFile(input));
  await assert.rejects(() => convertV1({ input, output, password: PASSWORD,
    maxInputBytes: 100 * 1024 * 1024, maxPlaintextBytes: 80 * 1024 * 1024, ...mutateOptions }), reason);
  await assert.rejects(() => readFile(output), { code: 'ENOENT' });
  assert.equal(sha(await readFile(input)), before, 'conversion changed the original backup');
}

test('portable v1 conversion authenticates, preserves sensitive domains and passes frozen desktop readback', async () => {
  const rich = await richPortableFixture(1234);
  const sourceHash = sha(await readFile(rich.input));
  const output = join(dir, 'converted-portable-rich.zip');
  const result = await convertV1({ input: rich.input, output, password: PASSWORD,
    maxInputBytes: 5_000_000, maxPlaintextBytes: 5_000_000 });
  assert.equal(result.format, 'dstu.s2-14.portable-v1-to-v2-conversion.v1');
  assert.equal(result.outerChecksumsVerified, true);
  assert.equal(result.importedCanonicalSha256, rich.canonical);
  assert.equal(result.importedFileCount, 9);
  assert.match(result.desktopImport, /before and after.*no slot restore/);
  assert.equal(sha(await readFile(rich.input)), sourceHash);
  const checked = await verifyAndroid({ input: output, password: PASSWORD, expectedCanonicalSha256: rich.canonical });
  assert.equal(checked.desktopImport.result, 'succeeded');
  assert.equal(checked.entries.find(entry => entry.path === rich.skillPath).sha256, rich.skillHash);
  assert.equal(checked.entries.find(entry => entry.path === rich.seedPath).sha256, rich.seedHash);
  assert.equal(checked.entries.find(entry => entry.path === rich.credentialPath).sha256, rich.credentialHash);
  assert.equal(checked.entries.find(entry => entry.path === 'manifest.json').sha256, sha(rich.originalBytes));
  const originalEntries = await readZipEntries(rich.input), convertedEntries = await readZipEntries(output);
  assert.deepEqual(originalEntries.map(([name]) => name), convertedEntries.map(([name]) => name));
  for (const [name, bytes] of originalEntries) {
    if (['manifest.json', 'portable_secrets.dsbk', 'checksums.sha256'].includes(name)) continue;
    assert.deepEqual(convertedEntries.find(([path]) => path === name)[1], bytes);
  }
  const getManifest = entries => JSON.parse(entries.find(([name]) => name === 'manifest.json')[1]);
  const oldManifest = getManifest(originalEntries), newManifest = getManifest(convertedEntries);
  const oldPayload = oldManifest.files.find(file => file.path === 'portable_secrets.dsbk');
  const newPayload = newManifest.files.find(file => file.path === 'portable_secrets.dsbk');
  Object.assign(oldPayload, { size: newPayload.size, sha256: newPayload.sha256 });
  assert.deepEqual(oldManifest, newManifest);
  assert.equal(convertedEntries.find(([name]) => name === 'portable_secrets.dsbk')[1][4], 2);
});

test('portable conversion rejects authentication, tampering, unverified metadata and scope violations without publishing', async () => {
  const rich = await richPortableFixture(1234);
  const base = await readZipEntries(rich.input);
  await expectPortableRejected(rich.input, { password: 'wrong-password' }, /auth|decrypt|password|解密|认证/i);
  const corruptedPayload = await portableVariant(base, entries => {
    const payload = entries.find(([name]) => name === 'portable_secrets.dsbk')[1];
    payload[payload.length - 1] ^= 1;
    updatePayloadDeclaration(entries); // attacker also updates public metadata and checksums
  });
  await expectPortableRejected(corruptedPayload, {}, /auth|decrypt|password|解密|认证/i);
  const changedDatabase = await portableVariant(base, entries => {
    const db = entries.find(([name]) => name === 'vfs.db')[1];
    db[db.length - 1] ^= 1;
    const manifest = JSON.parse(entries.find(([name]) => name === 'manifest.json')[1]);
    manifest.files.find(file => file.path === 'vfs.db').sha256 = sha(db);
    replaceEntry(entries, 'manifest.json', JSON.stringify(manifest));
  });
  await expectPortableRejected(changedDatabase, {}, /outer manifest differs/);
  const scope = await portableVariant(base, entries => entries.push(['crypto/.master_key', Buffer.from('forbidden')]));
  await expectPortableRejected(scope, {}, /archive policy rejected/);
  const extra = await portableVariant(base, entries => entries.push(['undeclared.bin', Buffer.from('forbidden')]));
  await expectPortableRejected(extra, {}, /outside the original manifest/);
  // Build a deliberately wrong checksum ledger rather than omit it.
  const brokenEntries = base.filter(([name]) => name !== 'checksums.sha256');
  brokenEntries.push(['checksums.sha256', Buffer.from('0'.repeat(64) + '  manifest.json')]);
  const brokenChecksum = join(dir, `broken-checksum-${variantNumber++}.zip`);
  await writeZip(brokenChecksum, brokenEntries);
  await expectPortableRejected(brokenChecksum, {}, /outer checksum mismatch/);
  const duplicate = await portableVariant(base, entries => entries.push(['manifest.json', Buffer.from('{}')]), false);
  await expectPortableRejected(duplicate, {}, /duplicate ZIP entry|archive policy rejected/);
  const duplicateKey = await portableVariant(base, entries => {
    const manifest = entries.find(([name]) => name === 'manifest.json')[1].toString();
    replaceEntry(entries, 'manifest.json', manifest.replace('{', '{"key_policy":"included_encrypted",'));
  });
  await expectPortableRejected(duplicateKey, {}, /duplicate manifest key/);
});

test('portable conversion is no-overwrite and enforces input, plaintext, path and symlink limits', async () => {
  const rich = await richPortableFixture(1234);
  const output = join(dir, 'existing-output.zip');
  const marker = Buffer.from('existing output must survive');
  await writeFile(output, marker);
  const inputHash = sha(await readFile(rich.input));
  const options = { input: rich.input, output, password: PASSWORD,
    maxInputBytes: 5_000_000, maxPlaintextBytes: 5_000_000 };
  await assert.rejects(() => convertV1(options), { code: 'EEXIST' });
  assert.deepEqual(await readFile(output), marker);
  assert.equal(sha(await readFile(rich.input)), inputHash);
  await expectPortableRejected(rich.input, { maxInputBytes: 100 });
  await expectPortableRejected(rich.input, { maxPlaintextBytes: 100 });
  await expectPortableRejected(rich.input, { maxInputBytes: 1024 * 1024 * 1024 + 1 });
  await assert.rejects(() => convertV1({ ...options, output: rich.input }), /must differ/);
  const alias = join(dir, 'portable-input-link.zip');
  await symlink(rich.input, alias);
  await assert.rejects(() => convertV1({ ...options, input: alias }));
  assert.deepEqual(await readFile(output), marker);
  assert.ok((await readdir(dir)).every(name => !name.startsWith('.s214-v1-convert-')));
});

test('portable v1 sealed payload larger than Android limit converts and desktop imports unchanged', async () => {
  const size = 65 * 1024 * 1024 + 7;
  const rich = await richPortableFixture(size);
  const output = join(dir, 'converted-large-portable.zip');
  const result = await convertV1({ input: rich.input, output, password: PASSWORD,
    maxInputBytes: 80 * 1024 * 1024, maxPlaintextBytes: 70 * 1024 * 1024 });
  assert.ok(result.plaintextBytes > 64 * 1024 * 1024);
  assert.equal(result.importedCanonicalSha256, rich.canonical);
  assert.equal(result.outerChecksumsVerified, true);
  const checked = await verifyAndroid({ input: output, password: PASSWORD, expectedCanonicalSha256: rich.canonical });
  assert.equal(checked.entries.find(entry => entry.path === rich.skillPath).sha256, rich.skillHash);
  assert.equal(checked.desktopImport.result, 'succeeded');
});

test('portable original manifest integrity is checked even when the attacker re-encrypts and updates outer metadata', async () => {
  const innerPlain = join(dir, 'tampered-original-inner.zip');
  const original = JSON.parse((await readZipEntries(file('controlled-full.zip')))
    .find(([name]) => name === 'manifest.json')[1]);
  original.files.find(file => file.path === 'vfs.db').sha256 = '0'.repeat(64);
  await writeZip(innerPlain, [['manifest.json', JSON.stringify(original)]]);
  const payload = await desktopCrypto.encryptBackup(await readFile(innerPlain), PASSWORD);
  const base = await readZipEntries(file('desktop-portable-v1-converted.zip'));
  const input = await portableVariant(base, entries => {
    replaceEntry(entries, 'portable_secrets.dsbk', payload);
    updatePayloadDeclaration(entries);
    const outer = JSON.parse(entries.find(([name]) => name === 'manifest.json')[1]);
    outer.files.find(file => file.path === 'vfs.db').sha256 = '0'.repeat(64);
    replaceEntry(entries, 'manifest.json', JSON.stringify(outer));
  });
  await expectPortableRejected(input, {}, /declared file integrity mismatch/);
});

test('CLI converts a compressed-manifest portable ZIP and does not print the password', async () => {
  const base = await readZipEntries(file('desktop-portable-v1-converted.zip'));
  const input = join(dir, 'compressed-outer-v1.zip');
  await writeZip(input, base.map(([name, bytes]) => [name, bytes, name === 'manifest.json' ? 'deflated' : 'stored']));
  const output = join(dir, 'converted-cli.zip');
  const env = { ...process.env, S214_ORACLE_TEST_PASSWORD: PASSWORD };
  const result = spawnSync(process.execPath, [oraclePath, 'convert-v1', '--input', input, '--output', output,
    '--password-env', 'S214_ORACLE_TEST_PASSWORD', '--max-input-bytes', '5000000', '--max-plaintext-bytes', '5000000'],
    { encoding: 'utf8', env });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).importedCanonicalSha256, expectedCanonical);
  assert.ok(!result.stdout.includes(PASSWORD)); assert.ok(!result.stderr.includes(PASSWORD));
  assert.equal((await verifyAndroid({ input: output, password: PASSWORD, expectedCanonicalSha256: expectedCanonical })).desktopImport.result, 'succeeded');
});

test('raw v1 conversion also refuses to overwrite an existing destination', async () => {
  const output = join(dir, 'raw-existing-output.dsbk');
  const marker = Buffer.from('preserve previously existing output');
  await writeFile(output, marker);
  await assert.rejects(() => convertV1({ input: file('controlled-full-v1.dsbk'), output, password: PASSWORD,
    maxInputBytes: 5_000_000, maxPlaintextBytes: 5_000_000 }), { code: 'EEXIST' });
  assert.deepEqual(await readFile(output), marker);
});
