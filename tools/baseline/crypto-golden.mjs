import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createCipheriv, createDecipheriv } from 'node:crypto';
import { artifact, desktop, git, log, main, readJson, root, sha256 } from './lib.mjs';

const UNIT = 'S0.18';
const ARTIFACT = 'config/baseline/crypto-golden.json';
const BACKUP_CRYPTO = 'host/platform/secrets/backup-crypto.ts';
const DSBK_STREAM = 'host/platform/secrets/dsbk-stream.ts';
const DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const KDF = { m: 8, t: 1, p: 1 };
const SALT = Buffer.alloc(16, 7);
const V1_NONCE = Buffer.from('000102030405060708090a0b', 'hex');
const V2_PREFIX = Buffer.alloc(7, 0xaa);
const SMALL_LIMIT = 4096;
const CASES = [
  { id: 'empty', bytes: 0, description: 'empty plaintext' },
  { id: 'unicode', text: '备份 🔐 DSBK 中文', description: 'multibyte UTF-8 plaintext' },
  { id: 'exact-1MiB', bytes: 1024 * 1024, description: 'exactly one 1 MiB stream chunk' },
  { id: 'plus-1', bytes: 1024 * 1024 + 1, description: 'one byte beyond the 1 MiB chunk boundary' },
];
const NEGATIVE_CASES = [
  'wrong-password',
  'tag-tamper',
  'truncation',
  'header-magic',
  'header-version',
  'header-kdf-tamper',
  'chunk-zero',
  'over-64MiB',
  'kdf-budget-m',
  'kdf-budget-t',
  'kdf-budget-p',
];

function pattern(length) {
  const bytes = Buffer.alloc(length);
  for (let index = 0; index < length; index += 1) bytes[index] = (index * 31 + 7) & 0xff;
  return bytes;
}

function plaintextFor(spec) {
  return spec.bytes === undefined ? Buffer.from(spec.text, 'utf8') : pattern(spec.bytes);
}

function prefix(bytes, length = 64) {
  return bytes.subarray(0, Math.min(length, bytes.length)).toString('hex');
}

function bytesRecord(bytes) {
  return {
    bytes: bytes.length,
    sha256: sha256(bytes),
    headerHex: prefix(bytes),
    fullHex: bytes.length <= SMALL_LIMIT ? bytes.toString('hex') : null,
  };
}

function source(bytes, sizes = [1, 7, 65_536]) {
  let offset = 0;
  let pull = 0;
  const state = { pulls: 0, cancelled: null };
  const stream = new ReadableStream({
    pull(controller) {
      state.pulls += 1;
      if (offset >= bytes.length) {
        controller.close();
        return;
      }
      const size = sizes[pull++ % sizes.length];
      controller.enqueue(bytes.subarray(offset, offset + size));
      offset += size;
    },
    cancel(reason) {
      state.cancelled = reason ?? true;
    },
  }, { highWaterMark: 0 });
  return { stream, state };
}

function sink() {
  const chunks = [];
  const state = { writes: 0, closed: false, aborted: null };
  const stream = new WritableStream({
    write(chunk) {
      state.writes += 1;
      chunks.push(Buffer.from(chunk));
    },
    close() {
      state.closed = true;
    },
    abort(reason) {
      state.aborted = reason ?? true;
    },
  });
  return { stream, state, bytes: () => Buffer.concat(chunks) };
}

function decryptSink() {
  const chunks = [];
  const state = {
    writes: 0, bytesWritten: 0, closed: false, aborted: null, cleared: false,
    writesBeforeAbort: 0, bytesBeforeAbort: 0,
  };
  const stream = new WritableStream({
    write(chunk) {
      state.writes += 1;
      state.bytesWritten += chunk.length;
      chunks.push(Buffer.from(chunk));
    },
    close() {
      state.closed = true;
    },
    abort(reason) {
      state.aborted = reason ?? true;
      state.writesBeforeAbort = state.writes;
      state.bytesBeforeAbort = state.bytesWritten;
      for (const chunk of chunks) chunk.fill(0);
      chunks.length = 0;
      state.cleared = true;
    },
  });
  return {
    stream,
    state,
    bytes: () => Buffer.concat(chunks),
    stagedBytes: () => chunks.reduce((total, chunk) => total + chunk.length, 0),
  };
}

function realAead() {
  return {
    seal(key, nonce, plaintext) {
      const cipher = createCipheriv('aes-256-gcm', key, nonce);
      return Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
    },
    open(key, nonce, ciphertext) {
      if (ciphertext.length < 16) return null;
      try {
        const decipher = createDecipheriv('aes-256-gcm', key, nonce);
        decipher.setAuthTag(ciphertext.subarray(ciphertext.length - 16));
        return Buffer.concat([decipher.update(ciphertext.subarray(0, ciphertext.length - 16)), decipher.final()]);
      } catch {
        return null;
      }
    },
  };
}

function describeError(error) {
  return error instanceof Error ? error.message : String(error);
}

async function loadCrypto() {
  const backupCryptoPath = resolve(desktop, BACKUP_CRYPTO);
  const dsbkStreamPath = resolve(desktop, DSBK_STREAM);
  const backup = await import(backupCryptoPath);
  const stream = await import(dsbkStreamPath);
  const sourceHashes = [
    { file: `dstu-node/${BACKUP_CRYPTO}`, sha256: sha256(readFileSync(backupCryptoPath)) },
    { file: `dstu-node/${DSBK_STREAM}`, sha256: sha256(readFileSync(dsbkStreamPath)) },
  ];
  log('crypto-golden.direct-import', { modules: sourceHashes });
  return {
    ...backup,
    ...stream,
    sourceHashes,
    sourceBinding: {
      commit: DESKTOP_COMMIT,
      head: git(desktop, 'rev-parse', 'HEAD'),
      files: sourceHashes,
    },
  };
}

function deriveWith(crypto, salt = SALT) {
  return crypto.deriveKey('pw-vector', salt, KDF.m, KDF.t, KDF.p);
}

async function encryptV1(crypto, plaintext, nonce = V1_NONCE) {
  const key = await deriveWith(crypto);
  try {
    return crypto.encryptBackupWithKey(plaintext, SALT, KDF.m, KDF.t, KDF.p, key, nonce);
  } finally {
    key.fill(0);
  }
}

async function encryptV2(crypto, plaintext) {
  const output = sink();
  const calls = [];
  await crypto.encryptDsbkStream(
    source(plaintext).stream,
    output.stream,
    { salt: SALT, m: KDF.m, t: KDF.t, p: KDF.p },
    async (salt, m, t, p) => {
      const key = await crypto.deriveKey('pw-vector', salt, m, t, p);
      calls.push({ salt: Buffer.from(salt).toString('hex'), m, t, p, key });
      return key;
    },
    { aead: realAead(), randomBytes: length => Buffer.alloc(length, 0xaa), describeError },
  ).finally(() => { for (const { key } of calls) key.fill(0); });
  return { bytes: output.bytes(), keyCalls: calls.map(({ key, ...call }) => call) };
}

async function decryptV1(crypto, bytes, password = 'pw-vector') {
  const output = decryptSink();
  const input = source(bytes);
  const keys = [];
  await crypto.decryptDsbkStream(
    input.stream,
    output.stream,
    async (salt, m, t, p) => {
      const key = await crypto.deriveKey(password, salt, m, t, p);
      keys.push({ salt: Buffer.from(salt).toString('hex'), m, t, p, key });
      return key;
    },
    { aead: realAead(), randomBytes: length => Buffer.alloc(length, 0xaa), describeError },
  ).finally(() => { for (const { key } of keys) key.fill(0); });
  return {
    bytes: output.bytes(), output: output.state, input: input.state,
    keyCalls: keys.map(({ key, ...call }) => call),
  };
}

async function decryptV2(crypto, bytes, password = 'pw-vector') {
  const output = decryptSink();
  const input = source(bytes);
  const keys = [];
  await crypto.decryptDsbkStream(
    input.stream,
    output.stream,
    async (salt, m, t, p) => {
      const key = await crypto.deriveKey(password, salt, m, t, p);
      keys.push({ salt: Buffer.from(salt).toString('hex'), m, t, p, key });
      return key;
    },
    { aead: realAead(), randomBytes: length => Buffer.alloc(length, 0xaa), describeError },
  ).finally(() => { for (const { key } of keys) key.fill(0); });
  return {
    bytes: output.bytes(), output: output.state, input: input.state,
    keyCalls: keys.map(({ key, ...call }) => call),
  };
}

async function reject(label, run) {
  let error = null;
  try {
    await run();
  } catch (caught) {
    error = caught;
  }
  assert.ok(error, `${label}: expected rejection`);
  return error instanceof Error ? error.message : String(error);
}

function readV2Header(bytes) {
  assert.equal(bytes.subarray(0, 4).toString('ascii'), 'DSBK');
  assert.equal(bytes[4], 2);
  return {
    salt: Buffer.from(bytes.subarray(17, 33)).toString('hex'),
    prefix: Buffer.from(bytes.subarray(33, 40)).toString('hex'),
    m: bytes.readUInt32LE(5),
    t: bytes.readUInt32LE(9),
    p: bytes.readUInt32LE(13),
  };
}

function readV1Header(bytes) {
  assert.equal(bytes.subarray(0, 4).toString('ascii'), 'DSBK');
  assert.equal(bytes[4], 1);
  return {
    salt: Buffer.from(bytes.subarray(17, 33)).toString('hex'),
    nonce: Buffer.from(bytes.subarray(33, 45)).toString('hex'),
    m: bytes.readUInt32LE(5),
    t: bytes.readUInt32LE(9),
    p: bytes.readUInt32LE(13),
  };
}

async function failedDecrypt(crypto, bytes, password = 'pw-vector') {
  const output = decryptSink();
  const input = source(bytes);
  const calls = [];
  let message = null;
  try {
    await crypto.decryptDsbkStream(
      input.stream,
      output.stream,
      async (salt, m, t, p) => {
        calls.push({ salt: Buffer.from(salt).toString('hex'), m, t, p });
        return crypto.deriveKey(password, salt, m, t, p);
      },
      { aead: realAead(), randomBytes: length => Buffer.alloc(length, 0xaa), describeError },
    );
  } catch (error) {
    message = error instanceof Error ? error.message : String(error);
  }
  assert.ok(message, 'expected decrypt rejection');
  return { message, output: output.state, stagedBytes: output.stagedBytes(), input: input.state, keyCalls: calls };
}

async function negativeCases(crypto, v2) {
  const results = [];
  const wrongPassword = await failedDecrypt(crypto, v2, 'wrong');
  results.push({
    id: 'wrong-password', rejected: true, message: wrongPassword.message, kind: 'AEAD',
    keyCalls: wrongPassword.keyCalls, aborted: wrongPassword.output.aborted !== null,
    closed: wrongPassword.output.closed, stagedBytes: wrongPassword.stagedBytes,
  });

  const tag = Buffer.from(v2);
  tag[tag.length - 1] ^= 0x01;
  const tagError = await failedDecrypt(crypto, tag);
  results.push({
    id: 'tag-tamper', rejected: true, message: tagError.message, kind: 'AEAD',
    keyCalls: tagError.keyCalls, aborted: tagError.output.aborted !== null,
    closed: tagError.output.closed, stagedBytes: tagError.stagedBytes,
  });

  const truncated = v2.subarray(0, v2.length - 1);
  const truncatedError = await failedDecrypt(crypto, truncated);
  results.push({
    id: 'truncation', rejected: true, message: truncatedError.message, kind: 'AEAD',
    keyCalls: truncatedError.keyCalls, aborted: truncatedError.output.aborted !== null,
    closed: truncatedError.output.closed, stagedBytes: truncatedError.stagedBytes,
  });

  const magic = Buffer.from(v2);
  magic[0] ^= 0x01;
  const magicError = await failedDecrypt(crypto, magic);
  results.push({
    id: 'header-magic', rejected: true, message: magicError.message, kind: 'header',
    keyCalls: magicError.keyCalls, aborted: magicError.output.aborted !== null,
    closed: magicError.output.closed, stagedBytes: magicError.stagedBytes,
  });

  const version = Buffer.from(v2);
  version[4] = 3;
  const versionError = await failedDecrypt(crypto, version);
  results.push({
    id: 'header-version', rejected: true, message: versionError.message, kind: 'header',
    keyCalls: versionError.keyCalls, aborted: versionError.output.aborted !== null,
    closed: versionError.output.closed, stagedBytes: versionError.stagedBytes,
  });

  const headerTamper = Buffer.from(v2);
  headerTamper.fill(0x08, 17, 33);
  headerTamper.writeUInt32LE(9, 5);
  headerTamper.writeUInt32LE(1, 9);
  headerTamper.writeUInt32LE(1, 13);
  const expectedTamperedHeader = { salt: '08'.repeat(16), m: 9, t: 1, p: 1 };
  const headerKdfError = await failedDecrypt(crypto, headerTamper);
  assert.deepEqual(headerKdfError.keyCalls, [expectedTamperedHeader]);
  results.push({
    id: 'header-kdf-tamper', rejected: true, message: headerKdfError.message, kind: 'kdf-header',
    header: expectedTamperedHeader,
    keyCalls: headerKdfError.keyCalls,
    keyMatchesHeader: true,
    aborted: headerKdfError.output.aborted !== null,
    closed: headerKdfError.output.closed, stagedBytes: headerKdfError.stagedBytes,
  });

  const zeroChunk = Buffer.from(v2.subarray(0, crypto.DSBK_V2_HEADER_LEN));
  zeroChunk.writeUInt32LE(0, crypto.DSBK_V2_CHUNK_OFFSET);
  const chunkError = await failedDecrypt(crypto, zeroChunk);
  results.push({
    id: 'chunk-zero', rejected: true, message: chunkError.message, kind: 'chunk',
    keyCalls: chunkError.keyCalls, aborted: chunkError.output.aborted !== null,
    closed: chunkError.output.closed, stagedBytes: chunkError.stagedBytes,
  });

  const over64MiB = Buffer.from(v2.subarray(0, crypto.DSBK_V2_HEADER_LEN));
  over64MiB.writeUInt32LE(crypto.DSBK_MAX_PLAINTEXT_CHUNK + 1, crypto.DSBK_V2_CHUNK_OFFSET);
  const over64Error = await failedDecrypt(crypto, over64MiB);
  results.push({
    id: 'over-64MiB', rejected: true, message: over64Error.message, kind: 'chunk',
    keyCalls: over64Error.keyCalls, aborted: over64Error.output.aborted !== null,
    closed: over64Error.output.closed, stagedBytes: over64Error.stagedBytes,
  });

  const nativeCalls = [];
  const nativeArgon2 = {
    async deriveKey(password, salt, memoryKiB, iterations, parallelism, length = 32) {
      nativeCalls.push({
        passwordBytes: password.length,
        saltHex: Buffer.from(salt).toString('hex'),
        memoryKiB, iterations, parallelism, length,
      });
      return new Uint8Array(length).fill(0x11);
    },
  };
  const budgetCrypto = crypto.createBackupCrypto({ nativeArgon2 });
  const controlCallsBefore = nativeCalls.length;
  const control = await budgetCrypto.createFileCipherSession('pw-vector', { mCost: 8, tCost: 1, pCost: 1 });
  control.dispose();
  assert.equal(nativeCalls.length, controlCallsBefore + 1, 'valid session control did not call native derivation once');
  for (const [id, m, t, p] of [
    ['kdf-budget-m', crypto.KDF_MAX_M_COST_KIB + 1, 1, 1],
    ['kdf-budget-t', 8, crypto.KDF_MAX_T_COST + 1, 1],
    ['kdf-budget-p', 8, 1, crypto.KDF_MAX_P_COST + 1],
  ]) {
    const callsBefore = nativeCalls.length;
    const message = await reject(id, () => budgetCrypto.createFileCipherSession('pw-vector', { mCost: m, tCost: t, pCost: p }));
    const callsAfter = nativeCalls.length;
    assert.equal(callsAfter, callsBefore, `${id}: KDF budget rejection reached native allocation`);
    assert.ok(message.includes('加密参数异常'), `${id}: unexpected message ${message}`);
    results.push({
      id, rejected: true, message, kind: 'kdf-budget',
      nativeDeriveCallsBefore: callsBefore,
      nativeDeriveCallsAfter: callsAfter,
      allocationReached: false,
    });
  }

  assert.deepEqual(results.map(result => result.id), NEGATIVE_CASES);
  return results;
}

async function stagingAbort(crypto, v2) {
  const tampered = Buffer.from(v2);
  tampered[tampered.length - 1] ^= 0x01;
  const result = await failedDecrypt(crypto, tampered);
  assert.notEqual(result.output.aborted, null, 'staging sink was not aborted');
  assert.equal(result.output.closed, false, 'staging sink closed after abort');
  assert.equal(result.output.cleared, true, 'staged bytes were not cleared on abort');
  assert.ok(result.output.writesBeforeAbort >= 1, 'no authenticated prefix was staged before abort');
  assert.ok(result.output.bytesBeforeAbort > 0, 'staged prefix had no plaintext bytes before abort');
  assert.equal(result.stagedBytes, 0, 'staged bytes remain after abort');
  return {
    aborted: true,
    closed: false,
    committed: false,
    writesBeforeAbort: result.output.writesBeforeAbort,
    bytesBeforeAbort: result.output.bytesBeforeAbort,
    stagedBytesAfterAbort: result.stagedBytes,
    stagingFlag: 'cleared',
    activeSlotClaimed: false,
    scope: 'actual decrypt stream sink abort state; no active-slot switch claim',
  };
}

function verifyFrozen(classes, record) {
  const v1 = Buffer.from(record.v1.fullHex, 'hex');
  const v2 = Buffer.from(record.v2.fullHex, 'hex');
  assert.equal(sha256(v1), record.v1.sha256);
  assert.equal(sha256(v2), record.v2.sha256);
  assert.equal(v1.length, record.v1.bytes);
  assert.equal(v2.length, record.v2.bytes);
  assert.equal(classes.isEncryptedBackup(v1), true);
  assert.equal(classes.isEncryptedBackup(v2), true);
}

async function build(classes) {
  const cases = [];
  const v2BytesById = new Map();
  for (const spec of CASES) {
    const plaintext = plaintextFor(spec);
    const v1 = await encryptV1(classes, plaintext);
    const encryptedV2 = await encryptV2(classes, plaintext);
    const v2 = encryptedV2.bytes;
    v2BytesById.set(spec.id, v2);
    const decV1 = await decryptV1(classes, v1);
    const decV2 = await decryptV2(classes, v2);
    const v1Header = readV1Header(v1);
    const v2Header = readV2Header(v2);
    assert.equal(Buffer.compare(decV1.bytes, plaintext), 0, `${spec.id}: v1 round-trip`);
    assert.equal(Buffer.compare(decV2.bytes, plaintext), 0, `${spec.id}: v2 round-trip`);
    assert.equal(decV1.output.closed, true);
    assert.equal(decV2.output.closed, true);
    assert.equal(decV1.output.writes, 1);
    assert.equal(decV2.output.writes, spec.bytes === 1024 * 1024 + 1 ? 2 : 1);
    assert.deepEqual(decV1.keyCalls, [{ salt: SALT.toString('hex'), m: KDF.m, t: KDF.t, p: KDF.p }]);
    assert.deepEqual(decV2.keyCalls, [{ salt: SALT.toString('hex'), m: KDF.m, t: KDF.t, p: KDF.p }]);
    assert.deepEqual(encryptedV2.keyCalls, [{ salt: SALT.toString('hex'), m: KDF.m, t: KDF.t, p: KDF.p }]);
    assert.equal(v1Header.salt, SALT.toString('hex'));
    assert.equal(v1Header.nonce, V1_NONCE.toString('hex'));
    assert.equal(v2Header.salt, SALT.toString('hex'));
    assert.equal(v2Header.prefix, V2_PREFIX.toString('hex'));
    assert.deepEqual([v1Header.m, v1Header.t, v1Header.p], [KDF.m, KDF.t, KDF.p]);
    assert.deepEqual([v2Header.m, v2Header.t, v2Header.p], [KDF.m, KDF.t, KDF.p]);
    const record = {
      id: spec.id,
      description: spec.description,
      plaintext: {
        bytes: plaintext.length,
        sha256: sha256(plaintext),
        fullHex: plaintext.length <= SMALL_LIMIT ? plaintext.toString('hex') : null,
        pattern: spec.bytes === undefined ? 'literal-utf8' : 'byte(i)=(i*31+7)&255',
      },
      v1: bytesRecord(v1),
      v2: bytesRecord(v2),
      headers: {
        v1: v1Header,
        v2: v2Header,
      },
      encryptionKeyCalls: {
        v1: [{ salt: SALT.toString('hex'), m: KDF.m, t: KDF.t, p: KDF.p }],
        v2: encryptedV2.keyCalls,
      },
      roundtrip: {
        v1: {
          bytes: decV1.bytes.length, sha256: sha256(decV1.bytes), closed: decV1.output.closed,
          writes: decV1.output.writes, keyCalls: decV1.keyCalls,
        },
        v2: {
          bytes: decV2.bytes.length, sha256: sha256(decV2.bytes), closed: decV2.output.closed,
          writes: decV2.output.writes, keyCalls: decV2.keyCalls,
        },
      },
    };
    cases.push(record);
    log('crypto-golden.case', {
      id: spec.id,
      plaintextBytes: plaintext.length,
      plaintextSha256: sha256(plaintext),
      v1: record.v1,
      v2: record.v2,
      v1Closed: decV1.output.closed,
      v2Closed: decV2.output.closed,
    });
  }

  const unicode = cases.find(record => record.id === 'unicode');
  const exact = cases.find(record => record.id === 'exact-1MiB');
  const plus = cases.find(record => record.id === 'plus-1');
  assert.ok(unicode.v1.fullHex && unicode.v2.fullHex);
  assert.equal(exact.v1.fullHex, null);
  assert.equal(exact.v2.fullHex, null);
  assert.equal(plus.v1.fullHex, null);
  assert.equal(plus.v2.fullHex, null);
  assert.equal(plus.roundtrip.v2.writes, 2);

  const small = cases.find(record => record.id === 'unicode');
  const v2Bytes = Buffer.from(small.v2.fullHex, 'hex');
  const negatives = await negativeCases(classes, v2Bytes);
  const staging = await stagingAbort(classes, v2BytesById.get('plus-1'));
  log('crypto-golden.negatives', { count: negatives.length, ids: negatives.map(result => result.id) });
  log('crypto-golden.staging', staging);

  return {
    formatVersion: 1,
    unit: UNIT,
    format: 'dstu.crypto-golden.v1',
    source: {
      backupCrypto: 'dstu-node/host/platform/secrets/backup-crypto.ts',
      dsbkStream: 'dstu-node/host/platform/secrets/dsbk-stream.ts',
      sourceHashes: classes.sourceHashes,
      binding: classes.sourceBinding,
      modules: ['deriveKey', 'encryptBackupWithKey', 'encryptDsbkStream', 'decryptDsbkStream'],
      testOnlyKdf: KDF,
      saltHex: SALT.toString('hex'),
      v1NonceHex: V1_NONCE.toString('hex'),
      v2PrefixHex: V2_PREFIX.toString('hex'),
      v2PrefixInjection: 'injected through production encryptDsbkStream deps.randomBytes; fixture only, never production randomness',
      aead: 'node:crypto AES-256-GCM',
    },
    cases,
    negatives,
    staging,
    checks: {
      cases: cases.length,
      plaintextBoundaries: cases.map(record => record.plaintext.bytes),
      v1RoundTrips: cases.filter(record => record.roundtrip.v1.sha256 === record.plaintext.sha256).length,
      v2RoundTrips: cases.filter(record => record.roundtrip.v2.sha256 === record.plaintext.sha256).length,
      negativeCases: negatives.length,
      allNegativesRejected: negatives.every(result => result.rejected),
      over64MiBRejectedBeforeKey: negatives.find(result => result.id === 'over-64MiB')?.kind === 'chunk',
      over64MiBKeyCalls: negatives.find(result => result.id === 'over-64MiB')?.keyCalls.length ?? null,
      kdfBudgetRejectedBeforeDerivation: negatives.filter(result => result.kind === 'kdf-budget').every(result => result.allocationReached === false),
      headerBoundKeyDerivation: cases.every(record => record.roundtrip.v1.keyCalls.length === 1 && record.roundtrip.v2.keyCalls.length === 1),
      stagingAborted: staging.aborted && !staging.closed && staging.stagingFlag === 'cleared',
      activeSlotClaimed: false,
    },
    limitations: [
      'Argon2id parameters m=8 KiB, t=1, p=1 are cheap test-only parameters; they are not production KDF settings.',
      'The v2 nonce prefix is deterministic only through the injectable fixture dependency; production uses randomBytes.',
      'The staging check exercises the actual decrypt stream sink abort and clearing collected staged bytes; it does not claim a DataSpace active-slot switch or application activation.',
      'Large 1 MiB vectors are represented by deterministic input recipe, length, header, and SHA-256; only small vectors retain full hex.',
    ],
  };
}

function verifyArtifact(saved) {
  assert.equal(saved.formatVersion, 1);
  assert.equal(saved.unit, UNIT);
  assert.equal(saved.format, 'dstu.crypto-golden.v1');
  assert.equal(saved.source.binding.commit, DESKTOP_COMMIT);
  assert.equal(saved.source.binding.head, DESKTOP_COMMIT);
  assert.deepEqual(saved.source.binding.files, saved.source.sourceHashes);
  assert.equal(saved.source.sourceHashes.length, 2);
  assert.equal(saved.cases.length, CASES.length);
  assert.equal(saved.negatives.length, NEGATIVE_CASES.length);
  assert.equal(saved.checks.allNegativesRejected, true);
  assert.equal(saved.checks.over64MiBRejectedBeforeKey, true);
  assert.equal(saved.checks.over64MiBKeyCalls, 0);
  assert.equal(saved.checks.kdfBudgetRejectedBeforeDerivation, true);
  assert.equal(saved.checks.headerBoundKeyDerivation, true);
  assert.equal(saved.checks.stagingAborted, true);
  assert.equal(saved.checks.activeSlotClaimed, false);
  assert.equal(saved.staging.aborted, true);
  assert.equal(saved.staging.closed, false);
  assert.equal(saved.staging.committed, false);
  assert.ok(saved.staging.writesBeforeAbort >= 1);
  assert.ok(saved.staging.bytesBeforeAbort > 0);
  assert.equal(saved.staging.stagedBytesAfterAbort, 0);
  for (const record of saved.cases) {
    if (record.v1.fullHex !== null) assert.equal(sha256(Buffer.from(record.v1.fullHex, 'hex')), record.v1.sha256);
    if (record.v2.fullHex !== null) assert.equal(sha256(Buffer.from(record.v2.fullHex, 'hex')), record.v2.sha256);
    assert.equal(record.roundtrip.v1.sha256, record.plaintext.sha256);
    assert.equal(record.roundtrip.v2.sha256, record.plaintext.sha256);
    assert.deepEqual(record.roundtrip.v1.keyCalls.map(call => [call.salt, call.m, call.t, call.p]), [[record.headers.v1.salt, record.headers.v1.m, record.headers.v1.t, record.headers.v1.p]]);
    assert.deepEqual(record.roundtrip.v2.keyCalls.map(call => [call.salt, call.m, call.t, call.p]), [[record.headers.v2.salt, record.headers.v2.m, record.headers.v2.t, record.headers.v2.p]]);
  }
  assert.deepEqual(saved.negatives.map(result => result.id), NEGATIVE_CASES);
}

export async function run(mode) {
  const classes = await loadCrypto();
  const built = await build(classes);
  if (mode === 'capture') {
    artifact(ARTIFACT, built, mode);
  } else {
    const saved = readJson(ARTIFACT);
    verifyArtifact(saved);
    assert.deepEqual(saved.source.sourceHashes, classes.sourceHashes, 'crypto source hash drift');
    for (const record of saved.cases) {
      if (record.v1.fullHex === null || record.v2.fullHex === null) continue;
      verifyFrozen(classes, record);
    }
    artifact(ARTIFACT, built, mode);
  }
  log('crypto-golden.accepted', {
    unit: UNIT,
    mode,
    cases: built.checks.cases,
    v1RoundTrips: built.checks.v1RoundTrips,
    v2RoundTrips: built.checks.v2RoundTrips,
    negatives: built.checks.negativeCases,
    allNegativesRejected: built.checks.allNegativesRejected,
    stagingAborted: built.checks.stagingAborted,
  });
  return built;
}

main(import.meta.url, run);
