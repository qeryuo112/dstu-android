import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { corpusNotes, corpusSha256, NOTE_COUNT } from './benchmark-corpus.mjs';
import { evaluate, evidenceIssue, hash, nearestRank, validateContract } from './benchmark-lib.mjs';
import { classifyAdbFailure, notesSamples, parseColdStart, parseInstrumentation, phaseIndex, requireFreshBenchmarkPackages, terminalSamples, validateApkIdentity, validateSyncAdbArgs } from './benchmark-device-lib.mjs';
import { includesSourcePath } from './benchmark-provenance.mjs';
import { readMacroStartup, STARTUP_CLASS, STARTUP_TEST, startupPayload } from './benchmark-startup.mjs';

const root = resolve(import.meta.dirname, '../..');
const original = JSON.parse(readFileSync(join(root, 'config/benchmark-s1-27.json')));
const copy = value => structuredClone(value);
const sha = value => hash(Buffer.from(String(value)));
const rows = () => Array.from({ length: 33 }, (_, index) => ({ ...phaseIndex(index), status: 'ok', value: 10 }));
const macroReport = (run, ttid = Array(30).fill(10), ttfd = Array(30).fill(15)) => ({ context: { payload: startupPayload(run) }, benchmarks: [{
  className: STARTUP_CLASS, name: STARTUP_TEST, warmupIterations: 3, repeatIterations: 30,
  metrics: { timeToInitialDisplayMs: { runs: ttid }, timeToFullDisplayMs: { runs: ttfd } },
  profilerOutputs: Array.from({ length: 30 }, (_, index) => ({ type: 'PerfettoTrace', label: `Trace Iteration ${index}`, filename: `startup-${index}.perfetto-trace` })),
}] });
function makeFixture() {
  const tmp = mkdtempSync(join(tmpdir(), 's127-unit-'));
  const contract = copy(original);
  contract.status = 'frozen';
  contract.syncCorpus.sha256 = sha('public-sync-corpus');
  function write(dir, name, obj) {
    const bytes = Buffer.from(JSON.stringify(obj));
    writeFileSync(join(dir, name), bytes);
    return { path: name, sha256: hash(bytes) };
  }
  const inputs = contract.devices.map((profile, ordinal) => {
    const dir = join(tmp, String(ordinal)); mkdirSync(dir);
    const device = { profileId: profile.id, model: `physical-${ordinal}`, buildFingerprint: `vendor/device-${ordinal}/release`, hardware: 'hardware', product: 'physical',
      api: [26, 33, 36][ordinal], abi: 'arm64-v8a', ramMiB: [2048, 4096, 16384][ordinal], memTotalMiB: [1850, 3800, 15000][ordinal],
      smallestWidthDp: [360, 400, 825][ordinal], pageSize: 4096, serialSha256: sha(`serial${ordinal}`), androidIdSha256: sha(`android${ordinal}`),
      isEmulator: false, qemu: '0', physicalAttested: true };
    device.identityEvidence = write(dir, 'identity.json', device);
    device.attestationEvidence = write(dir, 'attestation.json', { physicalAttested: true, operatorAlias: 'test', profileId: device.profileId, serialSha256: device.serialSha256 });
    profile.lock = { serialSha256: device.serialSha256, androidIdSha256: device.androidIdSha256, buildFingerprint: device.buildFingerprint };
    const record = { formatVersion: 1, unit: 'S1.27', kind: 'physical-device', collector: 's1-27-device-v1', device,
      build: { appId: 'com.deepstudent.app.benchmark', testId: 'com.deepstudent.app.benchmark.test', debuggable: false, profileableShell: true, commit: 'a'.repeat(40), sourceFingerprintSha256: sha('source'), sourceUnchanged: true, apkSha256: sha('apk'), installedApkSha256: sha('apk'),
        variant: 'debug', versionCode: 1, schemaFingerprint: 'schema-v1', wireVersion: 'wire-v2', apkEvidence: write(dir, 'apk.json', { appId: 'com.deepstudent.app.benchmark', testId: 'com.deepstudent.app.benchmark.test', debuggable: false, profileableShell: true, apkSha256: sha('apk'), installedApkSha256: sha('apk'), testApkSha256: sha('testapk'), versionCode: 1 }) },
      corpus: { id: contract.corpus.id, sha256: contract.corpus.sha256, verified: true, evidence: write(dir, 'corpus.json', { type: 'corpus', id: contract.corpus.id, sha256: contract.corpus.sha256, count: contract.corpus.noteCount }) },
      environment: { thermalState: 'normal', batteryState: 'charging', startedAt: '2026-11-18T08:00:00Z', finishedAt: '2026-11-18T09:00:00Z' }, metrics: {} };
    const official = write(dir, 'official-startup.json', macroReport(record));
    const traces = Array.from({ length: 30 }, (_, index) => write(dir, `startup-${index}.perfetto-trace`, { testOnlyTrace: index }));
    for (const definition of contract.metrics) {
      const samples = definition.method ? rows().slice(3) : rows();
      if (definition.method) samples.forEach(s => {
        s.traceEvidence = traces[s.index - 1];
        if (definition.id === 'cold-start-ttfd-ms') { s.value = 15; s.fullDisplayObserved = true; }
      });
      if (definition.id === 'notes-scroll-frame-p95-ms') samples.forEach(s => { s.frameDurationsMs = [8, 9, 10, 6, 7]; s.droppedReports = 0; });
      if (definition.id === 'notes-scroll-peak-pss-mib') samples.forEach(s => { s.pssSamples = [{ elapsedMs: 0, pssMiB: 9 }, { elapsedMs: 100, pssMiB: 10 }]; });
      if (definition.id === 'terminal-roundtrip-ms') samples.forEach(s => { s.outputBytes = 2176; s.markerObserved = true; });
      if (definition.id === 'sync-apply-ms') samples.forEach(s => { const proof = { fixtureId: contract.syncCorpus.id, corpusSha256: contract.syncCorpus.sha256,
        androidVerified: true, desktopVerified: true, desktopMode: 'production', phase: s.phase, index: s.index };
        s.verification = { ...proof, evidence: write(dir, `proof-${s.phase}-${s.index}.json`, proof) }; });
      const metric = { unit: definition.unit, samples };
      if (definition.method) { metric.method = definition.method; metric.sourceEvidence = official; }
      metric.evidence = write(dir, `${definition.id}.json`, { metric: definition.id, unit: metric.unit, samples, ...(metric.method ? { method: metric.method } : {}) });
      record.metrics[definition.id] = metric;
    }
    return { record, baseDir: dir };
  });
  const costDir = join(tmp, 'cost'); mkdirSync(costDir);
  const costs = { formatVersion: 1, unit: 'S1.27', sourceFingerprintSha256: sha('source'), reports: {}, commands: {} };
  for (const name of contract.costEvidence.requiredReports) costs.reports[name] = { generatedAt: '2026-11-18T08:00:00Z', sourceFingerprintSha256: sha('source'), evidence: write(costDir, `${name}.json`, {}) };
  for (const name of contract.costEvidence.requiredTimedCommands) costs.commands[name] = { command: `["${name}"]`, exitCode: 0, elapsedMs: 200, sourceFingerprintSha256: sha('source'), evidence: write(costDir, `${name}.log`, {}) };
  return { tmp, contract, inputs, cost: { record: costs, baseDir: costDir }, write, close: () => rmSync(tmp, { recursive: true, force: true }) };
}
function updateMetric(fixture, index, id) {
  const { record, baseDir } = fixture.inputs[index], metric = record.metrics[id];
  metric.evidence = fixture.write(baseDir, `${id}.json`, { metric: id, unit: metric.unit, samples: metric.samples, ...(metric.method ? { method: metric.method } : {}) });
}
function withFixture(fn) { const fixture = makeFixture(); try { fn(fixture); } finally { fixture.close(); } }

test('fixed corpus is stable and matches the independent contract', () => {
  assert.equal(NOTE_COUNT, 64); assert.equal(corpusNotes().length, 64); assert.equal(corpusSha256(), original.corpus.sha256); validateContract(original);
  const altered = copy(original); altered.corpus.sha256 = sha('wrong'); assert.throws(() => validateContract(altered), /Corpus/u);
});
test('nearest rank excludes interpolation and does not mutate data', () => {
  const values = Array.from({ length: 30 }, (_, i) => 30 - i);
  assert.equal(nearestRank(values, 0.5), 15); assert.equal(nearestRank(values, 0.95), 29); assert.equal(values[0], 30);
  assert.throws(() => nearestRank([], 0.95)); assert.throws(() => nearestRank([Infinity], 0.95)); assert.throws(() => nearestRank([1], 0));
});
test('complete in-memory physical-shaped records can exercise the full pass branch, but synthetic records cannot pass', () => withFixture(f => {
  assert.equal(evaluate(f.contract, f.inputs, f.cost).status, 'pass');
  f.inputs[0].record.kind = 'synthetic';
  assert.equal(evaluate(f.contract, f.inputs, f.cost).status, 'missing');
}));
test('one tablet yields preliminary numeric results while matrix, locks and frozen budget stay missing', () => withFixture(f => {
  const contract = copy(original), tablet = f.inputs[2];
  delete tablet.record.metrics['sync-apply-ms'];
  const result = evaluate(contract, [tablet]);
  assert.equal(result.status, 'missing'); assert.equal(result.devices['low-memory-phone'].status, 'missing');
  assert.equal(result.devices['tablet-foldable'].metrics['cold-start-ttid-ms'].summary.p95, 10);
  assert.equal(result.devices['tablet-foldable'].metrics['sync-apply-ms'].status, 'missing');
}));
test('failure, missing samples and explicit scenario failure are different outcomes', () => withFixture(f => {
  const metric = f.inputs[0].record.metrics['cold-start-ttid-ms'];
  metric.samples = metric.samples.slice(0, -1); updateMetric(f, 0, 'cold-start-ttid-ms');
  assert.equal(evaluate(f.contract, f.inputs, f.cost).devices['low-memory-phone'].metrics['cold-start-ttid-ms'].status, 'missing');
  metric.samples[1] = { phase: 'measurement', index: 2, status: 'failed' }; updateMetric(f, 0, 'cold-start-ttid-ms');
  assert.equal(evaluate(f.contract, f.inputs, f.cost).devices['low-memory-phone'].metrics['cold-start-ttid-ms'].status, 'fail');
}));
test('P50 and P95 absolute budgets both gate, warmups are excluded', () => withFixture(f => {
  const metric = f.inputs[0].record.metrics['terminal-roundtrip-ms'];
  metric.samples[0].value = 1e9;
  for (const sample of metric.samples.filter(s => s.phase === 'measurement').slice(14)) sample.value = 3500;
  updateMetric(f, 0, 'terminal-roundtrip-ms');
  const result = evaluate(f.contract, f.inputs, f.cost).devices['low-memory-phone'].metrics['terminal-roundtrip-ms'];
  assert.equal(result.summary.p50, 3500); assert.equal(result.status, 'fail');
}));
test('reused identity, emulator, mismatched APK and inconsistent builds fail', () => withFixture(f => {
  f.inputs[1].record.device.androidIdSha256 = f.inputs[0].record.device.androidIdSha256;
  f.inputs[2].record.device.isEmulator = true;
  f.inputs[0].record.build.installedApkSha256 = sha('other-apk');
  f.inputs[2].record.build.apkSha256 = sha('other-build');
  const result = evaluate(f.contract, f.inputs, f.cost);
  assert.equal(result.status, 'fail'); assert.ok(result.globalIssues.some(i => /Same physical identifier/u.test(i.message)));
  assert.ok(result.globalIssues.some(i => /Build identity differs/u.test(i.message)));
}));
test('frame traces, sampled PSS, sync proof, and cost provenance are not optional', () => withFixture(f => {
  const frame = f.inputs[0].record.metrics['notes-scroll-frame-p95-ms'];
  frame.samples[3].droppedReports = 1; updateMetric(f, 0, 'notes-scroll-frame-p95-ms');
  const pss = f.inputs[1].record.metrics['notes-scroll-peak-pss-mib'];
  pss.samples[3].pssSamples[1].pssMiB = 11; updateMetric(f, 1, 'notes-scroll-peak-pss-mib');
  const sync = f.inputs[2].record.metrics['sync-apply-ms'];
  sync.samples[3].verification.desktopVerified = false; updateMetric(f, 2, 'sync-apply-ms');
  f.cost.record.reports['complexity-psi'].sourceFingerprintSha256 = null;
  const result = evaluate(f.contract, f.inputs, f.cost);
  assert.equal(result.devices['low-memory-phone'].metrics['notes-scroll-frame-p95-ms'].status, 'fail');
  assert.equal(result.devices['midrange-phone'].metrics['notes-scroll-peak-pss-mib'].status, 'fail');
  assert.equal(result.devices['tablet-foldable'].metrics['sync-apply-ms'].status, 'fail');
  assert.equal(result.costs.status, 'missing');
}));
test('tampered and out-of-directory evidence is rejected', () => withFixture(f => {
  const metric = f.inputs[0].record.metrics['cold-start-ttid-ms'];
  writeFileSync(join(f.inputs[0].baseDir, metric.evidence.path), '{}');
  assert.equal(evaluate(f.contract, f.inputs, f.cost).devices['low-memory-phone'].metrics['cold-start-ttid-ms'].status, 'fail');
  assert.equal(evidenceIssue({ path: '../escape', sha256: sha('x') }, f.inputs[0].baseDir, 'test').status, 'fail');
  symlinkSync(join(f.tmp, 'cost'), join(f.inputs[0].baseDir, 'link'));
  assert.equal(evidenceIssue({ path: 'link/build.log', sha256: sha('x') }, f.inputs[0].baseDir, 'test').status, 'fail');
}));
test('instrumentation and cold-start parsers reject skipped, malformed or unsuccessful evidence', () => {
  const text = 'INSTRUMENTATION_STATUS: s127event={"type":"corpus"}\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n';
  assert.deepEqual(parseInstrumentation(text), [{ type: 'corpus' }]);
  assert.throws(() => parseInstrumentation(text + 'INSTRUMENTATION_STATUS_CODE: -4\n'));
  assert.throws(() => parseInstrumentation('OK (0 tests)\nINSTRUMENTATION_CODE: -1'));
  assert.equal(parseColdStart('Status: ok\nTotalTime: 1234\n'), 1234);
  assert.throws(() => parseColdStart('Status: error\nTotalTime: 1\n'));
  const events = [{ type: 'notes-trial', ...phaseIndex(0), framesMs: [5, 6, 7, 8, 9], droppedReports: 0, pssSamples: [{ pssMiB: 3 }, { pssMiB: 4 }] }];
  assert.equal(notesSamples(events).frames[0].value, 9); assert.equal(notesSamples(events).memory[0].value, 4);
  assert.throws(() => terminalSamples([{ type: 'terminal-trial', markerObserved: false, outputBytes: 2176 }]));
});
test('collector without both safety flags is inert', () => {
  const tmp = join(tmpdir(), `s127-must-not-exist-${process.pid}`);
  const result = spawnSync(process.execPath, ['tools/s1/benchmark-device.mjs', 'collect', '--spec', '/nonexistent', '--out', tmp], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 2); assert.match(result.stderr, /Refusing to contact adb/u);
});
test('CLI reports empty matrix as missing, not pass or failure', () => {
  const result = spawnSync(process.execPath, ['tools/s1/benchmark.mjs', 'report'], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 2); assert.equal(JSON.parse(result.stdout).status, 'missing');
  assert.doesNotThrow(() => execFileSync(process.execPath, ['tools/s1/benchmark.mjs', 'check-contract'], { cwd: root }));
});


test('APK gate requires isolated, non-debuggable and shell-profileable application', () => {
  const app = "package: name='com.deepstudent.app.benchmark' versionCode='12'\n";
  const declaration = '  E: profileable\n    A: android:shell(0x0101059e)=(type 0x12)0xffffffff\n';
  const source = sha('fixture-source');
  const base = 'E: application\n  A: android:debuggable(0x0101000f)=(type 0x12)0x0\n' + declaration +
    `  E: meta-data\n    A: android:name(0x01010003)="com.deepstudent.benchmark.sourceSha256"\n    A: android:value(0x01010024)="${source}"\n` +
    '  E: activity\n    A: android:name(0x01010003)="com.deepstudent.app.MainActivity"\n';
  const testApk = "package: name='com.deepstudent.app.benchmark.test' versionCode=''\n";
  const testManifest = 'E: instrumentation\n  A: android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"\n  A: android:targetPackage(0x01010021)="com.deepstudent.app.benchmark"\n';
  const validate = manifest => validateApkIdentity(app, manifest, testApk, testManifest, source);
  assert.deepEqual(validate(base), { appId: 'com.deepstudent.app.benchmark', testId: 'com.deepstudent.app.benchmark.test', versionCode: 12, debuggable: false, profileableShell: true });
  assert.equal(validate(base.replace('  A: android:debuggable(0x0101000f)=(type 0x12)0x0\n', '')).debuggable, false);
  assert.throws(() => validateApkIdentity(app.replace('.benchmark\'', "'"), base, testApk, testManifest), /non-benchmark/u);
  assert.throws(() => validateApkIdentity(app, base, testApk, testManifest.replace('app.benchmark"', 'app"')), /target mismatch/u);
  assert.throws(() => validate(base.replace('android:debuggable(0x0101000f)=(type 0x12)0x0', 'android:debuggable(0x0101000f)=(type 0x12)0xffffffff')), /must not be debuggable/u);
  assert.throws(() => validate(base.replace(declaration, '')), /profileable/u);
  assert.throws(() => validate(base.replace(declaration, '  E: activity\n  '+declaration.replaceAll('\n', '\n  '))), /profileable/u);
  assert.throws(() => validate(base.replace('android:shell(0x0101059e)=(type 0x12)0xffffffff', 'android:shell(0x0101059e)=(type 0x12)0x0')), /profileable/u);
  assert.throws(() => validate(base.replace('android:shell(0x0101059e)=(type 0x12)0xffffffff', 'android:shell(0x0101059e)=(type 0x12)0x1')), /profileable/u);
  assert.throws(() => validate(base.replace(source, sha('other-source'))), /source attribution/u);
});
test('global package lookup fails closed without enumerating hidden Android users', () => {
  const seen = [];
  const absent = pkg => { seen.push(pkg); return `Unable to find package: ${pkg}\r\n`; };
  assert.equal(requireFreshBenchmarkPackages('0', absent, true), '0');
  assert.deepEqual(seen, ['com.deepstudent.app.benchmark', 'com.deepstudent.app.benchmark.test', 'com.deepstudent.benchmark.startup']);
  for (const error of ['', 'Permission Denial', 'Package [com.deepstudent.app.benchmark]\n  User 666: installed=true', 'Unable to find package: other']) {
    assert.throws(() => requireFreshBenchmarkPackages('0', () => error), /refusing to touch/u);
  }
  assert.throws(() => requireFreshBenchmarkPackages('current', () => { throw new Error('must not query'); }), /current Android user/u);
});
test('sync callback allows only reverse and isolated app clear/force-stop', () => {
  assert.doesNotThrow(() => validateSyncAdbArgs(['reverse', '--no-rebind', 'tcp:50000', 'tcp:1234']));
  assert.throws(() => validateSyncAdbArgs(['reverse', '--no-rebind', 'tcp:0', 'tcp:1234']));
  assert.throws(() => validateSyncAdbArgs(['reverse', '--no-rebind', 'tcp:50000', 'tcp:50000']));
  assert.throws(() => validateSyncAdbArgs(['reverse', '--no-rebind', 'tcp:1234', 'tcp:1234']));
  assert.throws(() => validateSyncAdbArgs(['reverse', '--no-rebind', 'tcp:50000', 'tcp:0']));
  assert.throws(() => validateSyncAdbArgs(['reverse', '--no-rebind', 'tcp:50000', 'tcp:65536']));
  assert.doesNotThrow(() => validateSyncAdbArgs(['shell', 'pm', 'clear', 'com.deepstudent.app.benchmark']));
  assert.throws(() => validateSyncAdbArgs(['shell', 'pm', 'clear', 'com.deepstudent.app']));
  assert.throws(() => validateSyncAdbArgs(['shell', 'sh', '-c', 'pm clear com.deepstudent.app']));
  assert.throws(() => validateSyncAdbArgs(['uninstall', 'com.deepstudent.app']));
  assert.throws(() => validateSyncAdbArgs(['reverse', '--remove-all']));
  assert.throws(() => validateSyncAdbArgs(['reverse', '--no-rebind', 'tcp:1234', 'tcp:5678']));
  assert.doesNotThrow(() => validateSyncAdbArgs(['reverse', '--remove', 'tcp:1234']));
});
test('ADB child failures use a fixed vocabulary, never device IDs or output', () => {
  assert.equal(classifyAdbFailure({ code: 'ETIMEDOUT', signal: 'SIGTERM' }), 'timeout');
  assert.equal(classifyAdbFailure({ code: 'ENOBUFS' }), 'output-limit');
  assert.equal(classifyAdbFailure({ code: 'ENOENT' }), 'spawn-failed');
  assert.equal(classifyAdbFailure({ signal: 'SIGKILL' }), 'signaled');
  const occupied = { status: 1, stderr: 'adb: error: cannot bind listener: Address already in use\r\n' };
  assert.equal(classifyAdbFailure(occupied, { reverse: true }), 'address-in-use');
  assert.equal(classifyAdbFailure(occupied), 'nonzero-exit');
  assert.equal(classifyAdbFailure({ status: 1, stderr: 'adb: error: cannot rebind existing socket\n' }, { reverse: true }), 'address-in-use');
  assert.equal(classifyAdbFailure({ status: 1, stderr: "adb: device 'private-serial' not found\n" }), 'device-unavailable');
  assert.equal(classifyAdbFailure({ status: 1, stderr: 'arbitrary child log with credentials' }), 'nonzero-exit');
});

test('source provenance includes benchmark and production files but excludes docs, logs and Git metadata', () => {
  for (const path of ['docs/handoff-s1.md', 'logs/run.txt', '.git/config', 'tools/s1/benchmark-readme.md']) assert.equal(includesSourcePath(path), false);
  for (const path of ['app/src/main/App.kt', 'tools/s1/benchmark-sync.mjs', 'config/benchmark-s1-27.json', 'documentation/file.txt']) assert.equal(includesSourcePath(path), true);
});
test('the installed normal APK is rejected locally before any adb invocation', t => {
  const app = join(root, 'app/build/outputs/apk/debug/app-debug.apk');
  const testApk = join(root, 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');
  const aapt = '/home/xutao/Android/Sdk/build-tools/36.0.0/aapt';
  if (![app, testApk, aapt].every(existsSync)) return t.skip('cached APK or aapt not present');
  const tmp = mkdtempSync(join(tmpdir(), 's127-reject-normal-'));
  try {
    const marker = join(tmp, 'adb-invoked'), fake = join(tmp, 'not-adb');
    writeFileSync(fake, `#!/bin/sh\ntouch '${marker}'\nexit 1\n`, { mode: 0o700 });
    const spec = { adbPath: fake, aaptPath: aapt, device: { serial: 'private', profileId: 'tablet-foldable', operatorAlias: 'test', physicalAttested: true, ramMiB: 16384 },
      build: { apk: app, testApk, variant: 'debug', versionCode: 2100000, schemaFingerprint: 'schema', wireVersion: 'wire', sourceFingerprintSha256: '0'.repeat(64) } };
    const path = join(tmp, 'spec.json'); writeFileSync(path, JSON.stringify(spec));
    const result = spawnSync(process.execPath, ['tools/s1/benchmark-device.mjs', 'collect', '--spec', path, '--out', join(tmp, 'result'), '--execute', '--ack-disposable-data'], { cwd: root, encoding: 'utf8' });
    assert.equal(result.status, 2); assert.match(result.stderr, /non-benchmark APK/u); assert.equal(existsSync(marker), false); assert.equal(existsSync(join(tmp, 'result')), false);
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});


test('smoke timing cannot fill official startup TTID or TTFD, and official traces are required', () => withFixture(f => {
  const run = f.inputs[2].record;
  run.smoke = { kind: 'activity-launch-smoke', status: 'ok', totalTimeMs: 123, acceptanceEvidence: false };
  delete run.metrics['cold-start-ttid-ms'];
  delete run.metrics['cold-start-ttfd-ms'];
  let result = evaluate(f.contract, f.inputs, f.cost).devices['tablet-foldable'].metrics;
  assert.equal(result['cold-start-ttid-ms'].status, 'missing');
  assert.equal(result['cold-start-ttfd-ms'].status, 'missing');
  const metric = f.inputs[0].record.metrics['cold-start-ttid-ms'];
  metric.method = 'am start -W'; updateMetric(f, 0, 'cold-start-ttid-ms');
  result = evaluate(f.contract, f.inputs, f.cost).devices['low-memory-phone'].metrics;
  assert.equal(result['cold-start-ttid-ms'].status, 'fail');
  const full = f.inputs[1].record.metrics['cold-start-ttfd-ms'];
  full.samples[3].fullDisplayObserved = false; delete full.samples[4].traceEvidence; updateMetric(f, 1, 'cold-start-ttfd-ms');
  result = evaluate(f.contract, f.inputs, f.cost).devices['midrange-phone'].metrics;
  assert.equal(result['cold-start-ttfd-ms'].status, 'missing');
}));

test('official startup parses the warmup count and raw runs, not smoke or inferred timing', () => withFixture(f => {
  const run = f.inputs[0].record, policy = f.contract.policy, expected = startupPayload(run);
  const raw = macroReport(run);
  assert.equal(readMacroStartup(raw, expected, policy).traceFiles.length, 30);
  const noFull = macroReport(run, Array(30).fill(10), []);
  assert.equal(readMacroStartup(noFull, expected, policy).metrics.has('cold-start-ttfd-ms'), false);
  const reused = macroReport(run);
  reused.benchmarks[0].profilerOutputs[0].filename = reused.benchmarks[0].profilerOutputs[1].filename;
  assert.throws(() => readMacroStartup(reused, expected, policy), /reused Perfetto trace/u);
  raw.benchmarks[0].warmupIterations = 2;
  assert.throws(() => readMacroStartup(raw, expected, policy), /iteration count/u);
  run.metrics['cold-start-ttid-ms'].samples[2].value++;
  updateMetric(f, 0, 'cold-start-ttid-ms');
  assert.equal(evaluate(f.contract, f.inputs, f.cost).devices['low-memory-phone'].metrics['cold-start-ttid-ms'].status, 'fail');
}));
