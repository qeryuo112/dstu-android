import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { readCandidateManifest, hasStageEvidenceFile, verifyProgress } from './progress.mjs';
import { root } from './frozen-inputs.mjs';

const queue = JSON.parse(readFileSync(resolve(root, 'config/execution-plan.json'), 'utf8'));
const original = JSON.parse(readFileSync(resolve(root, 'config/stages/s1-progress.json'), 'utf8'));
const copy = () => {
  const receipt = structuredClone(original);
  receipt.s1GateAccepted = false;
  delete receipt.stageGate;
  return receipt;
};
const cloneQueue = () => ({ ...queue, units: structuredClone(queue.units), stageExits: structuredClone(queue.stageExits), queue: structuredClone(queue.queue) });
const sliceNames = ['data', 'editor', 'content', 'tasks', 'workspace', 'algorithms', 'terminalShizuku', 'productShell', 'sync'];
const checkNames = ['acyclicModules', 'complexity', 'pureKotlin', 'fakeUi', 'processIsolation', 'scheduleCalibration'];
const deviceNames = ['iPA2475', 'V2232A'];
const hash = 'a'.repeat(64);
const otherHash = 'b'.repeat(64);
const proofHash = 'c'.repeat(64);
const observed = { sha256: hash, manifestSha256: proofHash };
const apkProof = sha256 => JSON.stringify({ event: 's1.apk.verified', identity: { name: 'com.deepstudent.app' }, sha256 });
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/aV0AAAAASUVORK5CYII=', 'base64');
const positive = () => ({ status: 'passed', evidence: ['logs/s1-g-review.log'] });
const apkPositive = () => ({ ...positive(), apkSha256: hash });
const roundTrip = () => ({ ...apkPositive(), directions: ['desktop-to-android', 'android-to-desktop'] });
const device = (deviceId, model) => ({
  ...apkPositive(), deviceId, physical: true,
  visual: { ...apkPositive(), evidence: [`logs/s1-g-${model.toLowerCase()}.png`] },
});
const complete = () => ({
  stage: 'S1', reviewer: 'main-thread', frozenQueue: 'config/execution-plan.json', s1GateAccepted: true,
  units: queue.units.filter(unit => unit.stage === 'S1').map(unit => ({
    id: unit.id, card: `${unit.id}/implementation`, status: 'accepted', acceptedAt: '2026-11-17T08:00:00+08:00',
    review: 'docs/archive/handoff-s1.md', dependsOn: [...unit.dependencies], evidence: ['logs/s1-test-one.log', 'logs/s1-test-two.json'],
  })),
  stageGate: {
    id: 'S1.G', status: 'accepted', reviewer: 'main-thread', acceptedAt: '2026-11-17T08:00:01+08:00',
    candidateApk: { manifest: 'logs/s1-g-apk.json', manifestSha256: proofHash, sha256: hash, evidence: ['logs/s1-g-apk.json'] },
    verticalSlices: Object.fromEntries(sliceNames.map(name => [name, apkPositive()])),
    roundTrips: { plaintext: roundTrip(), encrypted: roundTrip() },
    devices: Object.fromEntries(deviceNames.map((name, index) => [name, device(`device-${index + 1}`, name)])),
    checks: Object.fromEntries(checkNames.map(name => [name, positive()])),
  },
});
const verifyFixture = (receipt, frozen = queue, stageEvidence = () => true, readCandidate = () => observed) =>
  verifyProgress(receipt, frozen, () => true, readCandidate, stageEvidence);
const claimPaths = [
  ...sliceNames.map(name => ['verticalSlices', name]),
  ...['plaintext', 'encrypted'].map(name => ['roundTrips', name]),
  ...checkNames.map(name => ['checks', name]),
  ...deviceNames.map(name => ['devices', name]),
  ...deviceNames.map(name => ['devices', name, 'visual']),
];
const claim = (receipt, path) => path.reduce((value, part) => value[part], receipt.stageGate);

// These existing pending-mode checks deliberately use the real progress snapshot, but
// do not bypass its frozen receipts by restoring or changing user-owned PNG files.
test('accepted stage evidence follows the frozen serial dependencies', () => {
  assert.equal(verifyProgress(copy(), queue, () => true).accepted, original.units.length);
});
test('S0.G completion includes declared S0 prerequisites for S1.24', () => {
  const result = verifyProgress(copy(), queue, () => true);
  assert.ok(result.accepted >= 24);
  const receipt = copy();
  receipt.units.find(unit => unit.id === 'S1.24').dependsOn.push('S0.99');
  assert.throws(() => verifyProgress(receipt, queue, () => true));
});
test('reject missing evidence', () => assert.throws(() => verifyProgress(copy(), queue, () => false)));
test('reject a gap in the queue', () => {
  const receipt = copy();
  receipt.units.shift();
  assert.throws(() => verifyProgress(receipt, queue, () => true));
});
test('reject unsigned receipt', () => {
  const receipt = copy();
  receipt.units[0].status = 'pending';
  assert.throws(() => verifyProgress(receipt, queue, () => true));
});
test('unit reviews must name the archived S1 handoff', () => {
  for (const wrongPath of ['docs/handoff-s1.md', 'docs/handoff-s2.md']) {
    const receipt = copy();
    receipt.units[0].review = wrongPath;
    assert.throws(() => verifyProgress(receipt, queue, () => true), /review must point to S1 archived handoff/u);
  }
});
test('reject stage signoff without full stage review', () => {
  const receipt = copy();
  receipt.s1GateAccepted = true;
  delete receipt.stageGate;
  assert.throws(() => verifyProgress(receipt, queue, () => true));
});
test('reject evidence traversal', () => {
  const receipt = copy();
  receipt.units[0].evidence[0] = '../outside.log';
  assert.throws(() => verifyProgress(receipt, queue, () => true));
});
test('reject altered dependency receipt', () => {
  const receipt = copy();
  receipt.units[0].dependsOn = [];
  assert.throws(() => verifyProgress(receipt, queue, () => true));
});

test('pending output is unchanged, even with 27 units and an unconsumed stageGate', () => {
  for (const count of [0, 1, 26, 27]) {
    const receipt = complete();
    receipt.s1GateAccepted = false;
    receipt.units = receipt.units.slice(0, count);
    assert.deepEqual(verifyFixture(receipt), {
      accepted: count,
      next: count === 27 ? 'S1.G' : `S1.${String(count + 1).padStart(2, '0')}`,
      s1GateAccepted: false,
    });
  }
});
test('accepted gate needs an independent full receipt, not just a boolean', () => {
  const receipt = complete();
  delete receipt.stageGate;
  assert.throws(() => verifyFixture(receipt), /S1.G: object required/u);
  for (const invalid of [undefined, null, 0, 1, 'true', 'false']) {
    const input = complete();
    input.s1GateAccepted = invalid;
    assert.throws(() => verifyFixture(input), /acceptance must be boolean/u);
  }
});
test('complete explicitly reviewed stage advances to the next item from the frozen queue', () => {
  let hashedPath;
  const receipt = complete();
  assert.deepEqual(verifyFixture(receipt, queue, () => true, path => { hashedPath = path; return observed; }),
    { accepted: 27, next: 'S2.01', s1GateAccepted: true });
  assert.equal(hashedPath, receipt.stageGate.candidateApk.manifest);
});
test('gate waits for every ordered signed receipt', () => {
  for (const mutate of [
    p => p.units.pop(),
    p => p.units.splice(3, 1),
    p => { [p.units[3], p.units[4]] = [p.units[4], p.units[3]]; },
    p => { p.units[26].status = 'pending'; },
    p => { p.units[26].dependsOn = []; },
    p => { p.units[26].evidence = []; },
  ]) {
    const receipt = complete();
    mutate(receipt);
    assert.throws(() => verifyFixture(receipt));
  }
});
test('main-thread stage receipt identity, positive acceptance, and actual local time are mandatory', () => {
  const mutations = [
    g => { delete g.id; }, g => { g.id = 'S0.G'; },
    g => { delete g.status; }, g => { g.status = 'pending'; }, g => { g.status = 'passed'; },
    g => { delete g.reviewer; }, g => { g.reviewer = 'subagent'; },
    g => { delete g.acceptedAt; }, g => { g.acceptedAt = '2026-11-17T00:00:01Z'; },
    g => { g.acceptedAt = '2026-02-30T08:00:01+08:00'; },
    g => { g.acceptedAt = '2026-11-17T07:59:59+08:00'; },
    g => { g.acceptedAt = '9999-11-17T08:00:01+08:00'; },
  ];
  for (const mutate of mutations) {
    const receipt = complete();
    mutate(receipt.stageGate);
    assert.throws(() => verifyFixture(receipt));
  }
  const receipt = complete();
  receipt.units[0].acceptedAt = '2026-02-30T08:00:00+08:00';
  assert.throws(() => verifyFixture(receipt), /invalid calendar date/u);
});

test('all nine slices, both round trips, both devices and visuals, and six checks independently require positive evidence', () => {
  for (const path of claimPaths) {
    for (const mutate of [
      value => { delete value.status; },
      value => { value.status = 'failed'; },
      value => { value.status = 'pending'; },
      value => { value.status = true; },
      value => { delete value.evidence; },
      value => { value.evidence = []; },
      value => { value.evidence = ['../outside.log']; },
      value => { value.evidence = ['logs/s1-g-review.log', 'logs/s1-g-review.log']; },
      value => { value.evidence = ['logs/s1-g-absent.log']; },
    ]) {
      const receipt = complete();
      mutate(claim(receipt, path));
      assert.throws(() => verifyFixture(receipt, queue, name => name !== 'logs/s1-g-absent.log'),
        undefined, `must reject ${path.join('.')} without positive existing evidence`);
    }
    const receipt = complete();
    const owner = path.slice(0, -1).reduce((value, part) => value[part], receipt.stageGate);
    delete owner[path.at(-1)];
    assert.throws(() => verifyFixture(receipt), undefined, `must reject absent ${path.join('.')}`);
  }
});
test('the final candidate APK must be linked to its own matching manifest', () => {
  for (const mutate of [
    a => { delete a.manifest; }, a => { a.manifest = '../outside.json'; }, a => { a.manifest = '/outside.json'; },
    a => { a.manifest = 'app/../candidate.json'; }, a => { a.manifest = 'logs/s1-g-image.png'; },
    a => { delete a.manifestSha256; }, a => { a.manifestSha256 = otherHash; },
    a => { delete a.sha256; }, a => { a.sha256 = hash.toUpperCase(); }, a => { a.sha256 = 'short'; },
    a => { a.sha256 = otherHash; }, a => { delete a.evidence; }, a => { a.evidence = []; },
    a => { a.evidence = ['logs/s1-g-image.png']; }, a => { a.evidence = ['logs/s1-g-absent.log']; },
  ]) {
    const receipt = complete();
    mutate(receipt.stageGate.candidateApk);
    assert.throws(() => verifyFixture(receipt, queue, name => name !== 'logs/s1-g-absent.log'));
  }
  const receipt = complete();
  assert.throws(() => verifyFixture(receipt, queue, () => true, () => ({ ...observed, sha256: otherHash })), /APK fingerprint mismatch/u);
});
test('every APK-bound claim must cite the same final candidate hash', () => {
  const paths = [
    ...sliceNames.map(name => ['verticalSlices', name]),
    ...['plaintext', 'encrypted'].map(name => ['roundTrips', name]),
    ...deviceNames.map(name => ['devices', name]),
    ...deviceNames.map(name => ['devices', name, 'visual']),
  ];
  for (const path of paths) {
    for (const value of [undefined, otherHash, hash.toUpperCase()]) {
      const receipt = complete();
      claim(receipt, path).apkSha256 = value;
      assert.throws(() => verifyFixture(receipt), /candidate APK fingerprint mismatch/u, path.join('.'));
    }
  }
});
test('plaintext and encrypted evidence must each cover both directions', () => {
  for (const name of ['plaintext', 'encrypted']) {
    for (const directions of [undefined, [], ['desktop-to-android'], ['android-to-desktop'],
      ['desktop-to-android', 'desktop-to-android'], ['desktop-to-android', 'android-to-desktop', 'extra']]) {
      const receipt = complete();
      receipt.stageGate.roundTrips[name].directions = directions;
      assert.throws(() => verifyFixture(receipt), /both desktop\/Android directions required/u);
    }
    const receipt = complete();
    receipt.stageGate.roundTrips[name].directions.reverse();
    assert.equal(verifyFixture(receipt).s1GateAccepted, true);
  }
});
test('the two specified physical devices require distinct identifiers and per-device visual PNGs', () => {
  for (const mutate of [
    d => { d.V2232A.deviceId = d.iPA2475.deviceId; },
    d => { d.V2232A.deviceId = d.iPA2475.deviceId.toUpperCase(); },
    d => { d.iPA2475.deviceId = ' '; }, d => { d.iPA2475.deviceId = ' padded '; },
    d => { delete d.iPA2475.physical; }, d => { d.V2232A.physical = false; },
    d => { d.V2232A.visual.evidence = ['logs/s1-g-review.log']; },
  ]) {
    const receipt = complete();
    mutate(receipt.stageGate.devices);
    assert.throws(() => verifyFixture(receipt));
  }
});

test('accepted mode rejects altered, truncated, reordered or non-serial frozen queue data', () => {
  const mutateQueue = [
    q => { q.units = q.units.filter(unit => unit.id !== 'S1.27'); },
    q => { q.units.find(unit => unit.id === 'S1.27').stage = 'S2'; },
    q => { q.units.push(structuredClone(q.units.find(unit => unit.id === 'S1.01'))); },
    q => { const index = q.units.findIndex(unit => unit.id === 'S1.03'); [q.units[index], q.units[index + 1]] = [q.units[index + 1], q.units[index]]; },
    q => { q.units.find(unit => unit.id === 'S1.27').dependencies = q.units.find(unit => unit.id === 'S1.27').dependencies.filter(id => id !== 'S1.01'); },
    q => { q.stageExits = q.stageExits.filter(exit => exit.id !== 'S1.G'); },
    q => { q.stageExits.find(exit => exit.id === 'S1.G').dependencies.pop(); },
    q => { q.stageExits.find(exit => exit.id === 'S1.G').dependencies.push('S1.01'); },
    q => { q.queue = q.queue.filter(row => row.id !== 'S1.05'); },
    q => { const index = q.queue.findIndex(row => row.id === 'S1.02'); [q.queue[index], q.queue[index + 1]] = [q.queue[index + 1], q.queue[index]]; },
    q => { q.queue.find(row => row.id === 'S1.G').kind = 'unit'; },
    q => { const index = q.queue.findIndex(row => row.id === 'S1.G'); q.queue.splice(index, 0, { id: 'S0.X', stage: 'S0', kind: 'unit' }); },
    q => { q.queue.find(row => row.id === 'S2.01').id = 'S2.02'; },
    q => { q.queue.find(row => row.id === 'S2.01').stage = 'S9'; },
    q => { q.queue.push(structuredClone(q.queue.find(row => row.id === 'S2.01'))); },
    q => { q.units.find(unit => unit.id === 'S2.01').dependencies = []; },
  ];
  for (const mutate of mutateQueue) {
    const frozen = cloneQueue();
    const receipt = complete();
    mutate(frozen);
    // Match altered receipt dependencies so the stage-queue check cannot be
    // bypassed merely by changing the two copies in concert.
    for (const unit of receipt.units) {
      const match = frozen.units.find(candidate => candidate.id === unit.id);
      if (match) unit.dependsOn = [...match.dependencies];
    }
    assert.throws(() => verifyFixture(receipt, frozen));
  }
});

test('candidate manifest and stage evidence reject missing files, directories and escaping symlinks', t => {
  const base = mkdtempSync(resolve(tmpdir(), 's1-g-progress-'));
  const outside = mkdtempSync(resolve(tmpdir(), 's1-g-outside-'));
  t.after(() => { rmSync(base, { recursive: true, force: true }); rmSync(outside, { recursive: true, force: true }); });
  mkdirSync(resolve(base, 'logs'));
  const bytes = Buffer.from(apkProof(hash));
  writeFileSync(resolve(base, 'logs/s1-g-apk.json'), bytes);
  writeFileSync(resolve(base, 'logs/s1-g-review.log'), 'test fixture');
  writeFileSync(resolve(outside, 'outside.json'), bytes);
  symlinkSync(resolve(outside, 'outside.json'), resolve(base, 'logs/s1-g-escape.json'));
  symlinkSync(resolve(outside, 'outside.json'), resolve(base, 'logs/s1-g-escape.log'));
  mkdirSync(resolve(base, 'logs/s1-g-directory.log'));
  writeFileSync(resolve(base, 'logs/s1-g-empty.log'), '');
  writeFileSync(resolve(base, 'logs/s1-g-invalid.png'), 'not a PNG');
  writeFileSync(resolve(base, 'logs/s1-g-valid.png'), png);
  const zeroWidth = Buffer.from(png);
  zeroWidth.writeUInt32BE(0, 16);
  writeFileSync(resolve(base, 'logs/s1-g-zero-width.png'), zeroWidth);
  assert.deepEqual(readCandidateManifest('logs/s1-g-apk.json', base), { sha256: hash, manifestSha256: createHash('sha256').update(bytes).digest('hex') });
  for (const path of ['../outside.json', '/outside.json', 'logs/../candidate.json', 'logs/s1-g-missing.json', 'logs/s1-g-escape.json', 'logs/s1-g-candidate.zip']) {
    assert.throws(() => readCandidateManifest(path, base), undefined, path);
  }
  assert.equal(hasStageEvidenceFile('logs/s1-g-review.log', base), true);
  assert.equal(hasStageEvidenceFile('logs/s1-g-valid.png', base), true);
  for (const path of ['logs/s1-g-missing.log', 'logs/s1-g-directory.log', 'logs/s1-g-escape.log',
    'logs/s1-g-empty.log', 'logs/s1-g-invalid.png', 'logs/s1-g-zero-width.png', '../outside.apk']) {
    assert.equal(hasStageEvidenceFile(path, base), false, path);
  }
});
test('accepted mode defaults to checking real stage evidence even when the legacy callback is permissive', () => {
  const receipt = complete();
  const missing = `logs/s1-g-nonexistent-${randomUUID()}.json`;
  receipt.stageGate.candidateApk.manifest = missing;
  receipt.stageGate.candidateApk.evidence = [missing];
  assert.throws(() => verifyProgress(receipt, queue, () => true, () => observed), /missing evidence/u);
});

test('end-to-end stage fixture reads the candidate and evidence files and rejects later drift', t => {
  const base = mkdtempSync(resolve(tmpdir(), 's1-g-full-'));
  t.after(() => rmSync(base, { recursive: true, force: true }));
  mkdirSync(resolve(base, 'logs'));
  const actualHash = otherHash;
  const manifest = apkProof(actualHash);
  const manifestPath = resolve(base, 'logs/s1-g-apk.json');
  writeFileSync(manifestPath, manifest);
  for (const path of ['s1-test-one.log', 's1-test-two.json', 's1-g-review.log']) {
    writeFileSync(resolve(base, 'logs', path), 'synthetic positive evidence');
  }
  for (const model of deviceNames) writeFileSync(resolve(base, `logs/s1-g-${model.toLowerCase()}.png`), png);
  const receipt = complete();
  receipt.stageGate.candidateApk.manifestSha256 = createHash('sha256').update(manifest).digest('hex');
  receipt.stageGate.candidateApk.sha256 = actualHash;
  for (const path of claimPaths) {
    const value = claim(receipt, path);
    if ('apkSha256' in value) value.apkSha256 = actualHash;
  }
  const verify = () => verifyProgress(receipt, queue, path => existsSync(resolve(base, path)),
    path => readCandidateManifest(path, base), path => hasStageEvidenceFile(path, base));
  assert.deepEqual(verify(), { accepted: 27, next: 'S2.01', s1GateAccepted: true });
  writeFileSync(manifestPath, apkProof(hash));
  assert.throws(verify, /APK fingerprint mismatch/u);
  writeFileSync(manifestPath, `${manifest}\n`);
  assert.throws(verify, /manifest fingerprint mismatch/u);
  writeFileSync(manifestPath, manifest);
  writeFileSync(resolve(base, 'logs/s1-g-review.log'), '');
  assert.throws(verify, /missing evidence/u);
  writeFileSync(resolve(base, 'logs/s1-g-review.log'), 'restored synthetic evidence');
  writeFileSync(resolve(base, 'logs/s1-g-v2232a.png'), 'not a screenshot');
  assert.throws(verify, /missing evidence/u);
});
