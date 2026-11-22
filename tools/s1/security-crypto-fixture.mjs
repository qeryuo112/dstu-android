#!/usr/bin/env node
/**
 * S1.23 production Argon2id/DSBK fixture. Only --write changes the non-frozen
 * platform/security test resource; all frozen inputs are read-only and hashed.
 *
 * node --experimental-strip-types tools/s1/security-crypto-fixture.mjs --write
 * node --experimental-strip-types tools/s1/security-crypto-fixture.mjs --check
 * node --experimental-strip-types tools/s1/security-crypto-fixture.mjs --verify-android [directory]
 *
 * --verify-android requires freshly exported production JVM containers, not fake
 * KDF/provider output. It is desktop interoperability evidence, NOT Keystore or
 * device execution evidence. Run AndroidKeystoreDeviceTest separately on device.
 */
import { createCipheriv, createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const desktop = resolve(root, '../dstu-node');
const target = resolve(root, 'platform/security/src/test/resources/security/dsbk-production-golden.json');
const deviceTarget = resolve(root, 'platform/security/src/androidTest/assets/security/dsbk-production-golden.json');
const frozenCommit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const exportRevision = 's1.23-security-hardening-v2';
const sources = [
  ['host/platform/secrets/backup-crypto.ts', '0e6dc6397b387734221c00ad6f07d26553fa7d745e03112e8484780b85b42393'],
  ['host/platform/secrets/dsbk-stream.ts', '83b0f59d44808c92f2eaf0418966454ea92deeb3793453ef30781c82bdd6f710'],
];
const mode = process.argv[2];
if (!['--write', '--check', '--verify-android'].includes(mode)) {
  throw new Error('pass exactly one of --write, --check or --verify-android [directory]');
}
if (process.argv.length > (mode === '--verify-android' ? 4 : 3)) throw new Error('unexpected fixture arguments');
const commit = execFileSync('git', ['-C', desktop, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
if (commit !== frozenCommit) throw new Error(`desktop HEAD is not the frozen ${frozenCommit}`);
for (const [file, expected] of sources) {
  if (sha256(readFileSync(resolve(desktop, file))) !== expected) throw new Error(`${file} differs from frozen input`);
}
// Also reject a modified transitive desktop implementation or dependency lock.
execFileSync('git', ['-C', desktop, 'diff', '--quiet', 'HEAD', '--', 'host/platform', 'package.json', 'package-lock.json']);
const backup = await import(pathToFileURL(resolve(desktop, sources[0][0])).href);
const stream = await import(pathToFileURL(resolve(desktop, sources[1][0])).href);
const m = 65536, t = 3, p = 4;
const password = 'pw-生产-🔐'; // Public synthetic fixture only; never an actual credential.
const salt = Buffer.from('000102030405060708090a0b0c0d0e0f', 'hex');
const v1Nonce = Buffer.from('101112131415161718191a1b', 'hex');
const v2Prefix = Buffer.from('a1a2a3a4a5a6a7', 'hex');
const plaintext = Buffer.from('production-dsbk-兼容-🔐', 'utf8');
const desktopCrypto = backup.createBackupCrypto();
const key = await backup.deriveKey(password, salt, m, t, p);
try {
  if (mode === '--verify-android') {
    await verifyAndroid(resolve(process.argv[3] ?? resolve(root, 'platform/security/build/security-export')));
  } else {
    const fixture = await generateFixture();
    const text = `${JSON.stringify(fixture, null, 2)}\n`;
    if (mode === '--write') {
      for (const path of [target, deviceTarget]) {
        mkdirSync(dirname(path), { recursive: true });
        writeFileSync(path, text);
      }
      console.log(`wrote ${target}`);
    } else {
      if ([target, deviceTarget].some(path => !existsSync(path) || readFileSync(path, 'utf8') !== text)) {
        throw new Error(`${target} differs from frozen desktop output`);
      }
      console.log(`verified production KDF, Unicode and 4 streaming boundary cases: ${target}`);
    }
  }
} finally {
  key.fill(0);
}

function sha256(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function pattern(size) { return Buffer.from(Uint8Array.from({ length: size }, (_, i) => (i * 31 + 7) & 255)); }
function record(bytes, full = bytes.length <= 4096) {
  return {
    bytes: bytes.length,
    sha256: sha256(bytes),
    headerHex: bytes.subarray(0, 64).toString('hex'),
    tailHex: bytes.subarray(Math.max(0, bytes.length - 32)).toString('hex'),
    ...(full ? { fullHex: bytes.toString('hex') } : {}),
  };
}
function seal(k, nonce, pt) {
  const cipher = createCipheriv('aes-256-gcm', k, nonce);
  return Buffer.concat([cipher.update(pt), cipher.final(), cipher.getAuthTag()]);
}
async function encryptV2(plain) {
  const chunks = [];
  await stream.encryptDsbkStream(
    new ReadableStream({ start(c) { c.enqueue(plain); c.close(); } }),
    new WritableStream({ write(chunk) { chunks.push(Buffer.from(chunk)); } }),
    { salt, m, t, p },
    async (s, actualM, actualT, actualP) => {
      if (!Buffer.from(s).equals(salt) || actualM !== m || actualT !== t || actualP !== p) {
        throw new Error('desktop requested unexpected KDF parameters');
      }
      return key;
    },
    {
      aead: { seal, open() { throw new Error('encryption must not open'); } },
      randomBytes(length) {
        if (length !== v2Prefix.length) throw new Error('unexpected random request');
        return v2Prefix;
      },
    },
  );
  return Buffer.concat(chunks);
}
async function decryptDesktop(bytes) {
  if (bytes.length < 5 || bytes.subarray(0, 4).toString('ascii') !== 'DSBK') throw new Error('invalid DSBK header');
  if (bytes[4] === 1) return Buffer.from(await desktopCrypto.decryptBackup(bytes, password));
  if (bytes[4] !== 2) throw new Error('unsupported DSBK version');
  const chunks = [];
  try {
    await desktopCrypto.decryptBackupStream(
      new ReadableStream({ start(c) { c.enqueue(bytes); c.close(); } }),
      new WritableStream({ write(chunk) { chunks.push(Buffer.from(chunk)); } }),
      password,
    );
    return Buffer.concat(chunks);
  } finally {
    for (const chunk of chunks) chunk.fill(0);
  }
}
async function assertDesktopDecrypts(container, expected) {
  const decrypted = await decryptDesktop(container);
  try {
    if (!decrypted.equals(expected)) throw new Error('frozen desktop decrypt mismatch');
  } finally { decrypted.fill(0); }
}
async function generateFixture() {
  const v1 = backup.encryptBackupWithKey(plaintext, salt, m, t, p, key, v1Nonce);
  const v2 = await encryptV2(plaintext);
  await assertDesktopDecrypts(v1, plaintext);
  await assertDesktopDecrypts(v2, plaintext);
  const cases = [];
  for (const [id, size] of [['empty', 0], ['exact-1MiB', 1048576], ['plus-1', 1048577], ['three-chunk', 2097185]]) {
    const plain = pattern(size);
    const encrypted = await encryptV2(plain);
    await assertDesktopDecrypts(encrypted, plain);
    cases.push({ id, plaintext: { ...record(plain), pattern: 'byte(i)=(i*31+7)&255' }, v2: record(encrypted) });
  }
  const kdfVectors = [];
  for (const [id, text] of [
    ['unpaired-high-surrogate', 'a\ud800b'],
    ['unpaired-low-surrogate', 'a\udc00b'],
    ['embedded-nul', 'a\0b'],
    ['precomposed', 'é'],
    ['decomposed', 'e\u0301'],
  ]) {
    const derived = await backup.deriveKey(text, salt, m, t, p);
    try {
      kdfVectors.push({
        id,
        passwordUtf16: Array.from({ length: text.length }, (_, i) => text.charCodeAt(i)),
        passwordUtf8Hex: Buffer.from(text, 'utf8').toString('hex'),
        derivedKeySha256: sha256(derived),
      });
    } finally { derived.fill(0); }
  }
  return {
    formatVersion: 2, unit: 'S1.23', format: 'dstu.dsbk-production-golden.v2',
    source: { commit, files: sources.map(([file, hash]) => ({ file, sha256: hash })) },
    kdf: { algorithm: 'Argon2id', version: 0x13, memoryKiB: m, iterations: t, parallelism: p, outputBytes: 32 },
    password, saltHex: salt.toString('hex'), v1NonceHex: v1Nonce.toString('hex'), v2PrefixHex: v2Prefix.toString('hex'),
    plaintext: { ...record(plaintext), utf8: plaintext.toString('utf8') },
    derivedKeySha256: sha256(key), v1: record(v1), v2: record(v2), cases, kdfVectors,
  };
}
async function verifyAndroid(directory) {
  const manifestPath = resolve(directory, 'manifest.json');
  if (!existsSync(manifestPath) || statSync(manifestPath).size > 16384) throw new Error('missing or oversized Android export manifest');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  if (manifest.format !== 'dstu.security.android-export.v1' || manifest.testRevision !== exportRevision) {
    throw new Error('stale Android export; main thread must rerun ProductionDsbkFixtureTest after the S1.23 fixes');
  }
  if (!Array.isArray(manifest.containers) || manifest.containers.length !== 2) throw new Error('both v1 and v2 exports are required');
  const expected = pattern(1048577);
  for (const version of [1, 2]) {
    const entries = manifest.containers.filter(entry => entry.version === version);
    if (entries.length !== 1) throw new Error(`exactly one v${version} export is required`);
    const entry = entries[0];
    if (entry.file !== `android-v${version}.dsbk`) throw new Error('invalid export file name');
    const file = resolve(directory, entry.file);
    if (statSync(file).size > 2 * 1048576) throw new Error('export exceeds fixture input budget');
    const bytes = readFileSync(file);
    if (bytes.length !== entry.bytes || sha256(bytes) !== entry.sha256 || bytes[4] !== version) throw new Error('export metadata mismatch');
    if (bytes.readUInt32LE(5) !== m || bytes.readUInt32LE(9) !== t || bytes.readUInt32LE(13) !== p) {
      throw new Error('export did not use the production KDF');
    }
    if (entry.plaintextBytes !== expected.length || entry.plaintextSha256 !== sha256(expected)) throw new Error('unexpected fixture plaintext');
    await assertDesktopDecrypts(bytes, expected); // Never trust only self-reported plaintext hashes.
    console.log(`verified ${entry.file}: frozen desktop decrypt, production KDF, ${expected.length} bytes`);
  }
  console.log('verified both production JVM -> frozen desktop directions; device Keystore is a separate gate');
}
