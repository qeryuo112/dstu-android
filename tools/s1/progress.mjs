import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, existsSync, realpathSync, statSync } from 'node:fs';
import { resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { root } from './frozen-inputs.mjs';

const stageUnits = Array.from({ length: 27 }, (_, index) => `S1.${String(index + 1).padStart(2, '0')}`);
const slices = ['data', 'editor', 'content', 'tasks', 'workspace', 'algorithms', 'terminalShizuku', 'productShell', 'sync'];
const gateChecks = ['acyclicModules', 'complexity', 'pureKotlin', 'fakeUi', 'processIsolation', 'scheduleCalibration'];
const deviceModels = ['iPA2475', 'V2232A'];
const evidencePattern = /^logs\/s1-[a-z0-9-]+\.(?:log|json|png)$/u;
const sha256Pattern = /^[a-f0-9]{64}$/u;
const localTimePattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00$/u;

function record(value, label) {
  assert.ok(value !== null && typeof value === 'object' && !Array.isArray(value), `${label}: object required`);
  return value;
}

function localTime(value, label) {
  assert.match(value, localTimePattern, `${label}: Asia/Shanghai timestamp required`);
  const milliseconds = Date.parse(value);
  assert.ok(Number.isFinite(milliseconds), `${label}: invalid timestamp`);
  // Date.parse normalizes dates such as February 30; do not accept those as receipts.
  assert.equal(new Date(milliseconds + 8 * 60 * 60 * 1000).toISOString().slice(0, 19), value.slice(0, 19), `${label}: invalid calendar date`);
  return milliseconds;
}

function requireEvidence(paths, label, hasEvidence) {
  assert.ok(Array.isArray(paths) && paths.length > 0, `${label}: evidence required`);
  assert.equal(new Set(paths).size, paths.length, `${label}: duplicate evidence`);
  for (const path of paths) {
    assert.match(path, evidencePattern, `${label}: invalid evidence path`);
    assert.ok(hasEvidence(path), `${label}: missing evidence ${path}`);
  }
}

function passed(value, label, hasEvidence, apkSha256) {
  record(value, label);
  assert.equal(value.status, 'passed', `${label}: explicit positive result required`);
  requireEvidence(value.evidence, label, hasEvidence);
  if (apkSha256 !== undefined) assert.equal(value.apkSha256, apkSha256, `${label}: candidate APK fingerprint mismatch`);
  return value;
}

function safeExistingFile(path, base) {
  const actualRoot = realpathSync(base);
  const actual = realpathSync(resolve(actualRoot, path));
  assert.ok(actual.startsWith(`${actualRoot}${sep}`), `file escapes repository: ${path}`);
  assert.ok(statSync(actual).isFile(), `not a regular file: ${path}`);
  return actual;
}

function validateCandidateManifestPath(path) {
  assert.match(path, /^logs\/s1-[a-z0-9-]+\.json$/u, 'candidate APK requires repository JSON manifest evidence');
}

// Durable build evidence is produced by verify-apk.mjs after checking the actual
// APK. Replay remains possible after the verified binary/build tree is cleaned.
export function readCandidateManifest(path, base = root) {
  validateCandidateManifestPath(path);
  const bytes = readFileSync(safeExistingFile(path, base));
  const result = record(JSON.parse(bytes), 'candidate APK manifest');
  assert.equal(result.event, 's1.apk.verified', 'candidate requires verified APK manifest');
  assert.equal(result.identity?.name, 'com.deepstudent.app', 'candidate must be the regular Android app');
  assert.match(result.sha256, sha256Pattern, 'APK manifest must include the APK digest');
  return { sha256: result.sha256, manifestSha256: createHash('sha256').update(bytes).digest('hex') };
}

export function hasStageEvidenceFile(path, base = root) {
  if (typeof path !== 'string' || !evidencePattern.test(path)) return false;
  try {
    const file = safeExistingFile(path, base);
    if (statSync(file).size === 0) return false;
    if (path.endsWith('.png')) {
      // A nonempty log renamed to .png is not visual evidence. This checks the
      // PNG signature and IHDR dimensions, not the content of the image.
      const bytes = readFileSync(file);
      const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
      if (bytes.length < 33 || !bytes.subarray(0, 8).equals(signature) || bytes.readUInt32BE(8) !== 13 ||
        bytes.toString('ascii', 12, 16) !== 'IHDR' || bytes.readUInt32BE(16) === 0 || bytes.readUInt32BE(20) === 0) return false;
    }
    return true;
  } catch {
    return false;
  }
}

function verifyGateQueue(queue, expected, signed) {
  assert.deepEqual(expected.map(unit => unit.id), stageUnits, 'frozen queue must retain all 27 ordered S1 units');
  for (const [index, unit] of expected.entries()) {
    assert.equal(queue.units.filter(candidate => candidate.id === unit.id).length, 1, `${unit.id}: duplicate frozen unit`);
    assert.ok(Array.isArray(unit.dependencies), `${unit.id}: frozen dependencies required`);
    assert.equal(new Set(unit.dependencies).size, unit.dependencies.length, `${unit.id}: duplicate frozen dependency`);
    for (const prerequisite of ['S0.G', ...stageUnits.slice(0, index)]) {
      assert.ok(unit.dependencies.includes(prerequisite), `${unit.id}: missing frozen prerequisite ${prerequisite}`);
    }
    assert.ok(signed.has(unit.id), `${unit.id}: unit not accepted`);
  }
  assert.ok(Array.isArray(queue.stageExits), 'frozen stage exits required');
  const exits = queue.stageExits.filter(exit => exit.id === 'S1.G');
  assert.equal(exits.length, 1, 'exactly one frozen S1.G exit required');
  const exit = exits[0];
  assert.equal(exit.stage, 'S1', 'invalid frozen S1.G stage');
  assert.ok(Array.isArray(exit.dependencies), 'frozen S1.G dependencies required');
  assert.deepEqual([...exit.dependencies].sort(), ['S0.G', ...stageUnits].sort(), 'frozen S1.G must depend on all 27 units and S0.G');
  for (const dependency of exit.dependencies) assert.ok(signed.has(dependency), `S1.G: prerequisite not accepted: ${dependency}`);

  assert.ok(Array.isArray(queue.queue), 'frozen serial queue required');
  const rows = queue.queue;
  assert.deepEqual(rows.filter(row => row.stage === 'S1' && row.kind === 'unit').map(row => row.id), stageUnits,
    'serial queue must retain all 27 ordered S1 units');
  for (const id of [...stageUnits, 'S1.G', 'S2.01']) {
    assert.equal(rows.filter(row => row.id === id).length, 1, `${id}: missing or duplicate serial queue entry`);
  }
  const start = rows.findIndex(row => row.id === stageUnits[0]);
  assert.ok(start >= 0, 'S1 serial queue start missing');
  stageUnits.forEach((id, index) => {
    const row = rows[start + index];
    assert.ok(row && row.id === id && row.kind === 'unit' && row.stage === 'S1', `${id}: invalid serial queue position`);
  });
  const gate = rows[start + stageUnits.length];
  assert.ok(gate && gate.id === 'S1.G' && gate.kind === 'exit' && gate.stage === 'S1', 'S1.G must immediately follow the S1 units');
  const next = rows[start + stageUnits.length + 1];
  assert.ok(next && next.id === 'S2.01' && next.kind === 'unit' && next.stage === 'S2', 'the next frozen queue item must be S2.01');
  const nextUnits = queue.units.filter(unit => unit.id === next.id);
  assert.equal(nextUnits.length, 1, 'next frozen unit must exist exactly once');
  assert.equal(nextUnits[0].stage, next.stage, 'next frozen unit stage mismatch');
  assert.ok(Array.isArray(nextUnits[0].dependencies) && nextUnits[0].dependencies.includes('S1.G'), 'next frozen unit must depend on S1.G');
  return next.id;
}

// The passed statuses are explicit main-thread attestations. File existence,
// basic visual format and APK identity are machine-checked; the referenced
// log content and physical-device observations still require human review.
function verifyStageGate(progress, queue, expected, signed, readCandidate, hasEvidence) {
  assert.equal(progress.units.length, stageUnits.length, 'S1.G requires all 27 unit receipts');
  const next = verifyGateQueue(queue, expected, signed);
  const gate = record(progress.stageGate, 'S1.G');
  assert.equal(gate.id, 'S1.G', 'independent S1.G receipt required');
  assert.equal(gate.status, 'accepted', 'S1.G must be explicitly accepted');
  assert.equal(gate.reviewer, 'main-thread', 'S1.G requires main-thread review');
  const acceptedAt = localTime(gate.acceptedAt, 'S1.G acceptedAt');
  assert.ok(acceptedAt <= Date.now(), 'S1.G acceptance cannot be in the future');
  progress.units.forEach(unit => {
    assert.ok(localTime(unit.acceptedAt, `${unit.id} acceptedAt`) <= acceptedAt, `S1.G predates ${unit.id}`);
  });

  const candidate = record(gate.candidateApk, 'S1.G candidate APK');
  assert.match(candidate.sha256, sha256Pattern, 'candidate APK requires a lowercase SHA-256');
  validateCandidateManifestPath(candidate.manifest);
  assert.match(candidate.manifestSha256, sha256Pattern, 'candidate APK manifest requires a lowercase SHA-256');
  requireEvidence(candidate.evidence, 'candidate APK', hasEvidence);
  assert.ok(candidate.evidence.includes(candidate.manifest), 'candidate APK is missing manifest evidence');
  const observed = readCandidate(candidate.manifest);
  assert.equal(observed.sha256, candidate.sha256, 'candidate APK fingerprint mismatch');
  assert.equal(observed.manifestSha256, candidate.manifestSha256, 'candidate APK manifest fingerprint mismatch');

  const verticalSlices = record(gate.verticalSlices, 'S1.G vertical slices');
  for (const name of slices) passed(verticalSlices[name], `vertical slice ${name}`, hasEvidence, candidate.sha256);
  const roundTrips = record(gate.roundTrips, 'S1.G desktop round trips');
  for (const name of ['plaintext', 'encrypted']) {
    const result = passed(roundTrips[name], `round trip ${name}`, hasEvidence, candidate.sha256);
    assert.ok(Array.isArray(result.directions), `${name}: both desktop/Android directions required`);
    assert.deepEqual([...result.directions].sort(), ['android-to-desktop', 'desktop-to-android'], `${name}: both desktop/Android directions required`);
  }

  const devices = record(gate.devices, 'S1.G devices');
  const identities = new Set();
  for (const model of deviceModels) {
    const device = passed(devices[model], `device ${model}`, hasEvidence, candidate.sha256);
    assert.equal(device.physical, true, `${model}: physical device required`);
    assert.ok(typeof device.deviceId === 'string' && device.deviceId.trim() !== '' && device.deviceId === device.deviceId.trim(), `${model}: device identifier required`);
    const identity = device.deviceId.toLowerCase();
    assert.ok(!identities.has(identity), 'two independent physical devices required');
    identities.add(identity);
    const visual = passed(device.visual, `device ${model} visual`, hasEvidence, candidate.sha256);
    assert.ok(visual.evidence.some(path => path.endsWith('.png')), `${model}: visual PNG evidence required`);
  }

  const checks = record(gate.checks, 'S1.G independent checks');
  for (const name of gateChecks) passed(checks[name], `check ${name}`, hasEvidence);
  return next;
}

// The CLI reads the archived APK manifest and stage evidence files. The
// historical pending path is unchanged; IO can be injected for isolated tests.
export function verifyProgress(progress, queue, hasEvidence, readCandidate = readCandidateManifest,
  hasStageEvidence = path => hasEvidence(path) && hasStageEvidenceFile(path)) {
  assert.equal(progress.stage, 'S1');
  assert.equal(progress.reviewer, 'main-thread');
  assert.equal(progress.frozenQueue, 'config/execution-plan.json');
  assert.ok(progress.s1GateAccepted === false || progress.s1GateAccepted === true, 'S1.G acceptance must be boolean');
  const signed = new Set(['S0.G', ...queue.units.filter(unit => unit.stage === 'S0').map(unit => unit.id)]);
  const expected = queue.units.filter(unit => unit.stage === 'S1');
  assert.ok(progress.units.length <= expected.length);
  progress.units.forEach((receipt, index) => {
    const unit = expected[index];
    assert.equal(receipt.id, unit.id, 'accepted units must follow the frozen queue without gaps');
    assert.equal(receipt.status, 'accepted');
    assert.equal(receipt.card, `${unit.id}/implementation`);
    assert.match(receipt.acceptedAt, localTimePattern);
    assert.ok(Number.isFinite(Date.parse(receipt.acceptedAt)));
    assert.equal(receipt.review, 'docs/archive/handoff-s1.md', 'review must point to S1 archived handoff');
    assert.deepEqual([...receipt.dependsOn].sort(), [...unit.dependencies].sort(), 'receipt dependency set differs from frozen queue');
    if (index > 0) assert.ok(Date.parse(receipt.acceptedAt) >= Date.parse(progress.units[index - 1].acceptedAt), 'acceptance time moved backwards');
    for (const dependency of unit.dependencies) assert.ok(signed.has(dependency), `${unit.id}: prerequisite not accepted: ${dependency}`);
    assert.ok(Array.isArray(receipt.evidence) && receipt.evidence.length >= 2, `${unit.id}: evidence required`);
    assert.equal(new Set(receipt.evidence).size, receipt.evidence.length, 'duplicate evidence');
    for (const path of receipt.evidence) {
      assert.match(path, evidencePattern);
      assert.ok(hasEvidence(path), `${unit.id}: missing evidence ${path}`);
    }
    signed.add(unit.id);
  });
  if (!progress.s1GateAccepted) {
    return { accepted: progress.units.length, next: expected[progress.units.length]?.id ?? 'S1.G', s1GateAccepted: false };
  }
  const next = verifyStageGate(progress, queue, expected, signed, readCandidate, hasStageEvidence);
  return { accepted: progress.units.length, next, s1GateAccepted: true };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const read = path => JSON.parse(readFileSync(resolve(root, path), 'utf8'));
  const result = verifyProgress(read('config/stages/s1-progress.json'), read('config/execution-plan.json'),
    path => existsSync(resolve(root, path)), readCandidateManifest, hasStageEvidenceFile);
  console.log(JSON.stringify({ time: new Date().toISOString(), event: 's1.progress.verified', ...result }));
}
