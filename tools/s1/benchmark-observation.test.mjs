import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { evaluate, hash, observe, validateObservationContract } from './benchmark-lib.mjs';
import { STARTUP_CLASS, STARTUP_TEST, startupPayload } from './benchmark-startup.mjs';

const root = resolve(import.meta.dirname, '../..');
const original = JSON.parse(readFileSync(join(root, 'config/benchmark-s1-27.json')));
const sha = value => hash(Buffer.from(String(value)));
const clone = value => structuredClone(value);
const CLI = join(root, 'tools/s1/benchmark.mjs');

function fixture({ differentBuild = false } = {}) {
  const tmp = mkdtempSync(join(tmpdir(), 's127-observation-'));
  const contract = clone(original);
  function write(dir, name, data) {
    const bytes = Buffer.from(JSON.stringify(data));
    writeFileSync(join(dir, name), bytes);
    return { path: name, sha256: hash(bytes) };
  }
  const inputs = [0, 1].map(index => {
    const baseDir = join(tmp, String(index)); mkdirSync(baseDir);
    const old = index === 0;
    const serial = sha(`secret-serial-${index}`), androidId = sha(`secret-android-${index}`);
    const device = { profileId: old ? 'tablet-foldable' : null, ramMiB: old ? 16384 : null,
      model: old ? 'iPA2475' : 'V2232A', buildFingerprint: 'vendor/test/release', hardware: 'hardware', product: 'physical',
      serialSha256: serial, androidIdSha256: androidId, isEmulator: false, qemu: '0', physicalAttested: true,
      api: 36, abi: 'arm64-v8a', memTotalMiB: 5000, pageSize: 16384, smallestWidthDp: 375 };
    device.identityEvidence = write(baseDir, 'identity.json', device);
    device.attestationEvidence = write(baseDir, 'attestation.json', { physicalAttested: true, operatorAlias: 'tester', profileId: device.profileId, serialSha256: serial });
    const apkSha256 = sha(`apk-${differentBuild && index ? 1 : 0}`);
    const sourceFingerprintSha256 = sha(`source-${differentBuild && index ? 1 : 0}`);
    const apk = { appId: 'com.deepstudent.app.benchmark', testId: 'com.deepstudent.app.benchmark.test',
      debuggable: false, profileableShell: true, apkSha256, installedApkSha256: apkSha256,
      testApkSha256: sha('test-apk'), versionCode: 12 };
    const record = { formatVersion: 1, unit: 'S1.27', kind: old ? 'physical-device' : 'physical-device-observation', collector: 's1-27-device-v1', device,
      build: { ...apk, commit: 'a'.repeat(40), sourceFingerprintSha256, sourceUnchanged: true,
        variant: 'debug', schemaFingerprint: 'schema', wireVersion: 'wire', apkEvidence: write(baseDir, 'apk.json', apk) },
      corpus: { id: contract.corpus.id, sha256: contract.corpus.sha256, verified: true,
        evidence: write(baseDir, 'corpus.json', { type: 'corpus', id: contract.corpus.id, sha256: contract.corpus.sha256, count: contract.corpus.noteCount }) },
      environment: { thermalState: 'normal', batteryState: 'charging', startedAt: '2026-11-18T08:00:00Z', finishedAt: '2026-11-18T09:00:00Z' }, metrics: {} };
    const ttid = Array.from({ length: 30 }, (_, i) => 10000 + (30 - i) * 100);
    const ttfd = ttid.map(n => n + 500);
    const traces = Array.from({ length: 30 }, (_, i) => write(baseDir, `trace-${i}.perfetto-trace`, { trace: i }));
    const report = { context: { payload: startupPayload(record) }, benchmarks: [{ className: STARTUP_CLASS, name: STARTUP_TEST,
      warmupIterations: 3, repeatIterations: 30, metrics: { timeToInitialDisplayMs: { runs: ttid }, timeToFullDisplayMs: { runs: ttfd } },
      profilerOutputs: traces.map((t, i) => ({ type: 'PerfettoTrace', label: `Trace Iteration ${i}`, filename: t.path })) }] };
    const official = write(baseDir, 'startup.json', report);
    for (const def of contract.metrics) {
      const samples = Array.from({ length: def.method ? 30 : 33 }, (_, i) => {
        const warmup = !def.method && i < 3;
        const n = def.method ? i : i - 3;
        const sample = { phase: warmup ? 'warmup' : 'measurement', index: warmup ? i + 1 : n + 1, status: 'ok', value: warmup ? 100000 : 10000 + n };
        if (def.method) {
          sample.value = def.id === 'cold-start-ttid-ms' ? ttid[i] : ttfd[i];
          sample.traceEvidence = traces[i];
          if (def.id === 'cold-start-ttfd-ms') sample.fullDisplayObserved = true;
        }
        if (def.id === 'notes-scroll-frame-p95-ms') { sample.frameDurationsMs = [2, 3, 4, 5, 6]; sample.value = 6; sample.droppedReports = 0; }
        if (def.id === 'notes-scroll-peak-pss-mib') { sample.pssSamples = [{ elapsedMs: 0, pssMiB: 5 }, { elapsedMs: 100, pssMiB: 6 }]; sample.value = 6; }
        if (def.id === 'terminal-roundtrip-ms') { sample.outputBytes = contract.terminalCorpus.expectedOutputBytes; sample.markerObserved = true; }
        if (def.id === 'sync-apply-ms') {
          const proof = { fixtureId: contract.syncCorpus.id, corpusSha256: contract.syncCorpus.sha256, androidVerified: true,
            desktopVerified: true, desktopMode: 'production', phase: sample.phase, index: sample.index };
          sample.verification = { ...proof, evidence: write(baseDir, `sync-${sample.phase}-${sample.index}.json`, proof) };
        }
        return sample;
      });
      const metric = { unit: def.unit, samples };
      if (def.method) { metric.method = def.method; metric.sourceEvidence = official; }
      metric.evidence = write(baseDir, `metric-${def.id}.json`, { metric: def.id, unit: metric.unit, samples, ...(metric.method ? { method: metric.method } : {}) });
      record.metrics[def.id] = metric;
    }
    return { record, baseDir };
  });
  function saveRun(index) { const input = inputs[index]; const file = join(input.baseDir, 'run.json'); writeFileSync(file, JSON.stringify(input.record)); return file; }
  function updateMetric(index, id) {
    const { record, baseDir } = inputs[index], metric = record.metrics[id];
    metric.evidence = write(baseDir, `metric-${id}.json`, { metric: id, unit: metric.unit, samples: metric.samples, ...(metric.method ? { method: metric.method } : {}) });
  }
  return { tmp, contract, inputs, write, saveRun, updateMetric, close: () => rmSync(tmp, { recursive: true, force: true }) };
}
function withFixture(options, fn) { const f = fixture(options); try { fn(f); } finally { f.close(); } }
function cli(...args) { return spawnSync(process.execPath, [CLI, 'observe', ...args], { cwd: root, encoding: 'utf8' }); }

test('both legacy and unclassified records yield evidence-complete nearest-rank results without budgets', () => withFixture({}, f => {
  for (const input of f.inputs) {
    const result = observe(f.contract, [input]);
    assert.equal(result.status, 'complete'); assert.equal(result.acceptanceEvidence, false);
    const metric = result.devices[0].metrics['cold-start-ttid-ms'];
    assert.deepEqual(metric.summary, { count: 30, p50: 11500, p95: 12900, maximum: 13000, unit: 'ms' });
    assert.equal('budget' in metric, false);
    assert.equal(result.devices[0].metrics['terminal-roundtrip-ms'].summary.p50, 10014);
    assert.equal(result.devices[0].metrics['terminal-roundtrip-ms'].summary.p95, 10028);
    assert.equal(JSON.stringify(result).includes(input.record.device.serialSha256), false);
    assert.equal(JSON.stringify(result).includes(input.record.device.androidIdSha256), false);
  }
  // Observation does not depend on profile definitions, locks, contract status or budgets.
  delete f.contract.devices; f.contract.status = 'provisional';
  for (const metric of f.contract.metrics) delete metric.budgets;
  assert.doesNotThrow(() => validateObservationContract(f.contract));
  assert.equal(observe(f.contract, [f.inputs[0]]).status, 'complete');
}));

test('two distinct devices and matching application identity complete regardless of APK/source hash', () => {
  for (const differentBuild of [false, true]) withFixture({ differentBuild }, f => {
    const result = observe(f.contract, f.inputs, { requireTwo: true });
    assert.equal(result.status, 'complete'); assert.equal(result.distinctDevices, true);
    assert.equal(result.sameApk, !differentBuild); assert.equal(result.sameSource, !differentBuild);
    assert.equal(result.hostOnlyChangesVerified, false); assert.equal(result.acceptanceEvidence, false);
    assert.equal(result.devices.length, 2);
    const output = JSON.stringify(result);
    for (const input of f.inputs) {
      assert.ok(!output.includes(input.record.device.serialSha256));
      assert.ok(!output.includes(input.record.device.androidIdSha256));
      assert.ok(!output.includes(input.record.build.apkSha256));
    }
  });
});

test('missing metrics, raw evidence, full display, and a missing second run are not complete', () => withFixture({}, f => {
  delete f.inputs[1].record.metrics['sync-apply-ms'];
  let result = observe(f.contract, f.inputs, { requireTwo: true });
  assert.equal(result.status, 'missing'); assert.equal(result.devices[1].metrics['sync-apply-ms'].status, 'missing');
  const startup = f.inputs[0].record.metrics['cold-start-ttfd-ms'];
  delete startup.samples[0].traceEvidence; startup.samples[1].fullDisplayObserved = false;
  f.updateMetric(0, 'cold-start-ttfd-ms');
  result = observe(f.contract, f.inputs, { requireTwo: true });
  assert.equal(result.devices[0].metrics['cold-start-ttfd-ms'].status, 'missing');
  assert.equal(observe(f.contract, [f.inputs[1]], { requireTwo: true }).status, 'missing');
}));

test('tampered raw capture, startup value or sync proof fails', () => {
  withFixture({}, f => {
    const { record, baseDir } = f.inputs[0];
    writeFileSync(join(baseDir, record.metrics['terminal-roundtrip-ms'].evidence.path), '{}');
    const result = observe(f.contract, [f.inputs[0]]);
    assert.equal(result.status, 'fail'); assert.equal(result.devices[0].metrics['terminal-roundtrip-ms'].status, 'fail');
  });
  withFixture({}, f => {
    f.inputs[0].record.metrics['cold-start-ttid-ms'].samples[2].value++;
    f.updateMetric(0, 'cold-start-ttid-ms');
    assert.equal(observe(f.contract, [f.inputs[0]]).devices[0].metrics['cold-start-ttid-ms'].status, 'fail');
  });
  withFixture({}, f => {
    f.inputs[1].record.metrics['sync-apply-ms'].samples[0].verification.desktopVerified = false;
    f.updateMetric(1, 'sync-apply-ms');
    assert.equal(observe(f.contract, [f.inputs[1]]).devices[0].metrics['sync-apply-ms'].status, 'fail');
  });
});

test('identity collision, more than two runs, application mismatches, and emulators fail', () => {
  for (const field of ['serialSha256', 'androidIdSha256']) withFixture({}, f => {
    f.inputs[1].record.device[field] = f.inputs[0].record.device[field];
    const result = observe(f.contract, f.inputs, { requireTwo: true });
    assert.equal(result.status, 'fail'); assert.equal(result.distinctDevices, false);
  });
  withFixture({}, f => {
    assert.equal(observe(f.contract, [...f.inputs, f.inputs[0]], { requireTwo: true }).status, 'fail');
    f.inputs[1].record.build.wireVersion = 'different';
    assert.equal(observe(f.contract, f.inputs, { requireTwo: true }).status, 'fail');
    f.inputs[1].record.device.isEmulator = true;
    assert.equal(observe(f.contract, [f.inputs[1]]).status, 'fail');
  });
});

test('observation cannot be promoted into historical matrix even with a forged profile', () => withFixture({}, f => {
  f.inputs[1].record.device.profileId = 'tablet-foldable';
  f.inputs[1].record.device.ramMiB = 16384;
  const result = evaluate(f.contract, [f.inputs[1]]);
  assert.equal(result.status, 'fail');
  assert.equal(result.devices['tablet-foldable'].status, 'missing');
  assert.ok(result.globalIssues.some(issue => /excluded/u.test(issue.message)));
  assert.equal(observe(f.contract, [f.inputs[1]]).status, 'fail');
}));

test('CLI exits correctly, writes exclusive output, and never echoes private input or paths', () => withFixture({ differentBuild: true }, f => {
  const first = f.saveRun(0), second = f.saveRun(1), out = join(f.tmp, 'out.json');
  let result = cli('--run', first, '--run', second, '--require-two');
  assert.equal(result.status, 0, result.stderr); assert.equal(JSON.parse(result.stdout).sameApk, false);
  for (const input of f.inputs) assert.ok(!result.stdout.includes(input.record.device.serialSha256) && !result.stdout.includes(input.record.device.androidIdSha256));
  result = cli('--run', first, '--require-two', '--out', out);
  assert.equal(result.status, 2); assert.equal(JSON.parse(readFileSync(out, 'utf8')).status, 'missing');
  assert.equal(cli('--run', first, '--out', out).status, 3);
  result = cli('--run', first, '--run', second, '--run', first, '--require-two');
  assert.equal(result.status, 1); assert.equal(JSON.parse(result.stdout).status, 'fail');
  result = cli('--run', join(f.tmp, 'secret-serial-0'));
  assert.equal(result.status, 3); assert.ok(!result.stderr.includes('secret-serial-0'));
  assert.equal(cli('--run', first, '--run', second).status, 3);
}));

function recordSmoke(f, index) {
  const { record, baseDir } = f.inputs[index];
  const smoke = { kind: 'activity-launch-smoke', status: 'ok', totalTimeMs: 321, acceptanceEvidence: false };
  record.smoke = { ...smoke, evidence: f.write(baseDir, 'smoke.json', smoke) };
}

test('functional comparison accepts verified routes without turning incomplete timings into a pass', () => withFixture({}, f => {
  recordSmoke(f, 0); recordSmoke(f, 1);
  const phone = f.inputs[1].record;
  delete phone.metrics['cold-start-ttid-ms'];
  delete phone.metrics['cold-start-ttfd-ms'];
  phone.metrics['sync-apply-ms'].samples[32] = { phase: 'measurement', index: 30, status: 'failed' };
  f.updateMetric(1, 'sync-apply-ms');
  assert.equal(observe(f.contract, f.inputs, { requireTwo: true }).status, 'fail');
  const result = observe(f.contract, f.inputs, { requireTwo: true, functional: true });
  assert.equal(result.scope, 'functional');
  assert.equal(result.status, 'complete');
  assert.equal(result.devices[1].functions.startup.totalTimeMs, 321);
  assert.equal(result.devices[1].functions['sync-apply-ms'].trialsVerified, 29);
  assert.equal(result.devices[1].metrics['cold-start-ttid-ms'].status, 'missing');
  assert.equal(result.devices[1].metrics['sync-apply-ms'].status, 'fail');
  const command = cli('--run', f.saveRun(0), '--run', f.saveRun(1), '--require-two', '--functional');
  assert.equal(command.status, 0);
  assert.equal(JSON.parse(command.stdout).scope, 'functional');
}));

test('functional comparison still requires startup evidence and a verified measured roundtrip, not a warmup or an assertion', () => withFixture({}, f => {
  recordSmoke(f, 0);
  assert.equal(observe(f.contract, f.inputs, { requireTwo: true, functional: true }).status, 'missing');
  recordSmoke(f, 1);
  const phone = f.inputs[1].record;
  const sync = phone.metrics['sync-apply-ms'];
  sync.samples = sync.samples.filter(s => s.phase === 'warmup');
  f.updateMetric(1, 'sync-apply-ms');
  assert.equal(observe(f.contract, f.inputs, { requireTwo: true, functional: true }).status, 'missing');
  phone.smoke.totalTimeMs++;
  assert.equal(observe(f.contract, f.inputs, { requireTwo: true, functional: true }).status, 'fail');
}));
