import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { artifact, main, desktop, git, readDesktop, root, sha256, log } from './lib.mjs';
import { manifestOf } from './legacy-export/package.mjs';

const files = ['host/plugins/sync-support/src/tombstone-model.ts', 'host/plugins/sync/src/file-manifest.ts', 'host/plugins/backup/src/manifest-model.ts', 'host/plugins/sync/src/asset-filenames.ts'];
const FIXTURE = 'fixtures/legacy-mobile/legacy-mobile-standard-standard-v1.zip';
const DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const plain = value => value instanceof Map ? Object.fromEntries([...value].map(([key, item]) => [key, plain(item)]))
  : typeof value === 'bigint' ? { decimal: String(value) }
    : Array.isArray(value) ? value.map(plain)
      : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, plain(item)])) : value;

export async function run(mode) {
  const [tombstone, manifests, backup, paths] = await Promise.all(files.map(load));
  const vectors = [];
  const vector = (id, input, invoke, validate = () => {}) => {
    const actual = invoke(input);
    validate(actual);
    const output = plain(actual);
    vectors.push({ id, input: plain(input), expected: output,
      expectedSha256: sha256(JSON.stringify(output)), comparison: 'semantic output exact; decimal objects represent lossless integers' });
  };
  const rejects = (id, input, invoke, pattern) => {
    let caught;
    try { invoke(input); } catch (error) { caught = error; }
    assert.ok(caught, `${id}: expected rejection`);
    if (pattern) assert.match(caught.message, pattern);
    vectors.push({ id, input: plain(input), expected: { rejected: true, message: caught.message } });
  };
  const date = '2026-11-15T00:00:00Z';
  const hash = sha256('file-golden fixture');
  const assetKey = 'active/notes_assets/public-fixture.txt';
  const event = { format_version: 4, device_id: 'golden-device-01', seq: 18446744073709551615n,
    operation_id: tombstone.eventOperationId('asset', 'golden-device-01', assetKey, date),
    kind: 'asset', object_id: assetKey, deleted_at: date, size: 9007199254740993n, relative_path: null, payload_hash: '' };
  event.payload_hash = tombstone.eventPayloadHash(event);
  vector('tombstone-event-payload-hash', event, value => ({ hash: tombstone.eventPayloadHash(value), key: tombstone.eventKey(value),
    legacyKey: tombstone.eventLegacyKey(value), keyMatches: tombstone.eventKeyMatches(value, tombstone.eventKey(value)),
    seqFromKey: tombstone.eventSeqFromKey(tombstone.eventKey(value)) }), result => {
    assert.equal(result.keyMatches, true); assert.equal(result.seqFromKey, event.seq);
  });
  vector('tombstone-hash-binds-size', event, value => ({ before: value.payload_hash, after: tombstone.eventPayloadHash({ ...value, size: value.size + 1n }) }),
    result => assert.notEqual(result.before, result.after));
  vector('tombstone-event-parse-max-u64', event, tombstone.parseTombstoneEvent, result => assert.equal(result.seq, event.seq));
  for (const seq of [-1n, 18446744073709551616n, null, '1']) rejects(`tombstone-seq-${String(seq)}`, { ...event, seq }, tombstone.parseTombstoneEvent);
  for (const [id, entry, normalize, stringify] of [
    ['blob', { deleted_at: date, device_id: event.device_id, size: 9007199254740993n, relative_path: `${hash.slice(0, 2)}/${hash}` }, tombstone.normalizeBlobManifest, tombstone.stringifyBlobTombstones],
    ['asset', { deleted_at: date, device_id: event.device_id, size: 18446744073709551615n }, tombstone.normalizeAssetManifest, tombstone.stringifyAssetTombstones],
    ['workspace', { deleted_at: date, device_id: event.device_id }, tombstone.normalizeWorkspaceManifest, tombstone.stringifyWorkspaceTombstones],
  ]) {
    vector(`${id}-tombstone-bytes`, { entries: { [hash]: entry }, updated_at: date, future_outer: 'discarded' }, value => {
      const parsed = normalize(value);
      const text = stringify(parsed);
      return { parsed, text, utf8Hex: Buffer.from(text).toString('hex'), sha256: sha256(text) };
    });
    vector(`${id}-empty-defaults`, {}, normalize, result => assert.equal(result.updated_at, ''));
    rejects(`${id}-null-map`, { entries: null }, normalize);
    rejects(`${id}-missing-device`, { entries: { x: { deleted_at: date } } }, normalize);
  }
  for (const [id, parser, entry] of [
    ['blob', manifests.parseBlobManifest, { relative_path: `${hash.slice(0, 2)}/${hash}`, size: 18446744073709551615n }],
    ['asset', manifests.parseAssetManifest, { sha256: hash, size: 9007199254740993n }],
    ['workspace', manifests.parseWorkspaceManifest, { sha256: hash, size: 0, updated_at: date }],
  ]) {
    const stringify = (await load('host/platform/util/lossless-json.ts')).stringifyJsonLossless;
    const encode = value => Buffer.from(stringify(value));
    vector(`${id}-file-manifest`, { entries: { [hash]: entry }, unknown_outer: 1 }, value => parser(encode(value)),
      result => assert.equal(result.entries.size, 1));
    for (const size of [-1, 1.5, '1', null, 18446744073709551616n]) rejects(`${id}-invalid-size-${String(size)}`,
      { entries: { x: { ...entry, size } } }, value => parser(encode(value)));
    rejects(`${id}-invalid-utf8`, 'ff', value => parser(Buffer.from(value, 'hex')));
  }
  vector('blob-relative-path-valid', { hash, path: `${hash.slice(0, 2)}/${hash}.txt` },
    value => manifests.blobRelativePathProblem(value.hash, value.path), result => assert.equal(result, null));
  for (const [index, path] of ['../secret', '/etc/passwd', 'aa/wrong', `${hash.slice(0, 2)}/${hash}.a.b`].entries()) vector(`blob-path-guard-${index}`, { hash, path },
    value => manifests.blobRelativePathProblem(value.hash, value.path), result => assert.equal(typeof result, 'string'));
  rejects('tombstone-mixed-writers', { key: tombstone.assetDeviceTombstoneKey(event.device_id), devices: [event.device_id, 'peer'] },
    value => tombstone.sourceDeviceFromTombstoneEntries(value.key, tombstone.ASSET_TOMBSTONE_PREFIX, value.devices), /多台设备/);
  const fixtureBytes = readFileSync(resolve(root, FIXTURE));
  const actualManifest = await manifestOf(FIXTURE);
  const validateBackup = value => {
    const parsed = backup.parseManifest(value);
    backup.validateForSlotRestore(parsed);
    return parsed;
  };
  vector('backup-manifest-v3-production-export', actualManifest, validateBackup,
    result => assert.equal(result.snapshot_kind, 'full'));
  for (const [id, mutate, pattern] of [
    ['future-major', value => { value.version = '4.0.0'; }, /版本/],
    ['coverage-missing', value => { delete value.coverage; }, /coverage/],
    ['coverage-file-count', value => { value.coverage.domains['database:vfs'].file_count++; }, /计数/],
    ['coverage-trust', value => { value.coverage.domains['database:vfs'].restore_trust = 'explicit'; }, /安全元数据/],
    ['traversal', value => { value.files[0].path = '../secret'; }, /不安全路径/],
    ['invalid-hash', value => { value.files[0].sha256 = 'bad'; }, /SHA-256/],
    ['partial-slot', value => { value.snapshot_kind = 'partial_overlay'; }, /完整快照/],
    ['encrypted-key-policy', value => { value.key_policy = 'included_encrypted'; }, /加密封存/],
    ['empty-snapshot-epoch', value => { value.snapshot_epoch = ''; }, /snapshot_epoch/],
  ]) {
    const changed = structuredClone(actualManifest);
    mutate(changed);
    rejects(`backup-${id}`, changed, validateBackup, pattern);
  }
  for (const [index, path] of ['active/notes_assets/中文🙂.md', 'active/notes_assets/e\u0301.txt', 'active/documents/CON.txt',
    'active/documents/a:b?.pdf', 'app_data/skills/name/trailing. ', 'active/images/quote‛.png'].entries()) {
    vector(`portable-path-${index}`, path, value => {
      const encoded = paths.sanitizeAssetKey(value);
      assert.notEqual(encoded, null);
      const decoded = paths.decodeAssetKey(encoded);
      assert.equal(decoded, value);
      return { encoded, decoded, sha256: sha256(Buffer.from(encoded)) };
    });
  }
  for (const [index, path] of ['active/images/../secret', 'active/images//file', '/active/images/file', 'active/images/'].entries()) {
    vector(`portable-invalid-${index}`, path, paths.sanitizeAssetKey, value => assert.equal(value, null));
  }
  assert.equal(new Set(vectors.map(vector => vector.id)).size, vectors.length);
  const data = { formatVersion: 2, format: 'dstu-s0.18-file-golden/v2', unit: 'S0.18',
    sourceHashes: files.map(file => ({ file, sha256: sha256(readDesktop(file)) })), vectors,
    sourceBinding: {
      commit: DESKTOP_COMMIT,
      head: git(desktop, 'rev-parse', 'HEAD'),
      fixture: { path: FIXTURE, sha256: sha256(fixtureBytes), bytes: fixtureBytes.length },
      parentSample: 'S0.16 public legacy export; reused byte-for-byte, not regenerated against the frozen desktop source',
      parentManifest: { version: actualManifest.version, snapshotKind: actualManifest.snapshot_kind,
        keyPolicy: actualManifest.key_policy, files: actualManifest.files.length },
    },
    checks: { vectors: vectors.length, rejectionCases: vectors.filter(row => row.expected?.rejected).length,
      losslessSizeAndSeq: true, tombstoneHashBindsFields: true },
    rules: { fileManifestUnknowns: 'outer/entry unknown members discarded by current parser',
      tombstone: 'full device identity for bookkeeping; current hash keys and legacy cleaned-device keys accepted',
      blobPath: 'manifest parsing retains untrusted relative_path; filesystem consumer must call blobRelativePathProblem' } };
  assert.equal(data.sourceBinding.fixture.sha256, sha256(readFileSync(resolve(root, FIXTURE))),
    'S0.16 parent sample bytes changed');
  assert.equal(data.sourceBinding.fixture.sha256, '3bcecfdd5ec02b0d3b8ee110f2649b6edc136bc9bb6d41b2a5673061ec932eba');
  for (const source of data.sourceHashes) {
    assert.equal(sha256(readFileSync(resolve(desktop, source.file))), source.sha256, `${source.file}: source hash drift`);
  }
  artifact('config/baseline/file-golden.json', data, mode);
  log('file-golden.accepted', { unit: 'S0.18', mode, ...data.checks });
}

main(import.meta.url, run);
