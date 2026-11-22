import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';

// Mock both fixture modules before loading the collector: these tests never load the real
// fixture implementations, use adb, contact a device, or access private benchmark data.
const collectorUrl = new URL('./benchmark-sync.mjs', import.meta.url).href;
const fixtureKey = 's127-benchmark-sync-test-fixtures';
const modules = {
  './sync-notes-desktop.mjs': `
    export const desktopCommit = 'fake-desktop-commit';
    export const fixtureContent = 'fake-fixture-content';
    export const fixtureHash = 'fake-fixture-hash';
    export const openDesktopFixture = (...args) => globalThis[Symbol.for('${fixtureKey}')].open(...args);
  `,
  './sync-webdav-fixture.mjs': `
    export const startWebDavFixture = (...args) => globalThis[Symbol.for('${fixtureKey}')].start(...args);
  `,
};
const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (context.parentURL === collectorUrl && Object.hasOwn(modules, specifier)) {
      return { url: `data:text/javascript,${encodeURIComponent(modules[specifier])}`, shortCircuit: true };
    }
    return nextResolve(specifier, context);
  },
});
const { collectBenchmarkSync, syncCorpusSha256 } = await import(collectorUrl);
hooks.deregister();

const fakeSecrets = [
  'FAKE_PASSWORD_SENTINEL', 'FAKE_AUTH_SENTINEL', 'FAKE_NONCE_SENTINEL',
  'FAKE_ENDPOINT_SENTINEL', 'FAKE_NOTE_SENTINEL', 'FAKE_STDERR_SENTINEL', 'FAKE_ARGV_SENTINEL',
];

function rig(overrides = {}, policy = { warmup: 3, iterations: 2 }) {
  const counts = {}, metrics = [], evidence = [], calls = [], adbCalls = [], instrumentCalls = [], openCalls = [];
  function hit(step, fallback, ...args) {
    counts[step] = (counts[step] ?? 0) + 1;
    calls.push(step);
    const result = overrides[step]?.(counts[step], ...args);
    return result === undefined ? fallback : result;
  }
  const fixture = {
    port: 12345, endpoint: 'http://127.0.0.1:12345/dav/',
    close: () => hit('fixture-close'),
  };
  const desktop = {
    seed: () => hit('desktop-seed', { noteId: fakeSecrets[4], fileId: fakeSecrets[2] }),
    receive: () => hit('desktop-receive', { downloaded: { changes: [{ table_name: 'notes', record_id: 'fake-android-note' }] } }),
    notes: { getNoteWithContent: () => hit('desktop-content-verification', [null, 'Public Android benchmark note: 中文 😀\n']) },
    db: {}, close: () => hit('desktop-close'),
  };
  globalThis[Symbol.for(fixtureKey)] = {
    start: () => hit('fixture-start', fixture),
    open: (...args) => { openCalls.push(args); return hit('desktop-open', desktop, ...args); },
  };
  const adb = args => {
    adbCalls.push(args);
    if (args[0] === 'reverse' && args[1] === '--no-rebind') return hit('adb-reverse', '', args);
    if (args[0] === 'reverse' && args[1] === '--remove') return hit('adb-reverse-remove', '', args);
    if (args[0] === 'shell') return hit('device-reset', 'Success', args);
    throw new Error('Unexpected test adb operation');
  };
  const instrument = (...args) => {
    instrumentCalls.push(args);
    return hit('android-instrumentation', [{ type: 'sync-trial', androidVerified: true,
      durationMs: 42.75, androidNoteId: 'fake-android-note' }], ...args);
  };
  const putMetric = (...args) => metrics.push(args);
  const writeEvidence = (out, name, proof) => {
    hit('evidence-write', undefined, out, name, proof);
    evidence.push({ name, proof });
    return { path: name, sha256: 'fake-sha256' };
  };
  return { counts, metrics, evidence, calls, adbCalls, instrumentCalls, openCalls, fixture, options: { contract: { syncCorpus: {
    id: 's1-27-desktop-roundtrip-v1', sha256: syncCorpusSha256 }, policy },
  adb, instrument, putMetric, reverseStart: 52345, out: 'fake-output-directory', writeEvidence } };
}

async function run(r) {
  const messages = [], saved = console.error;
  console.error = (...args) => messages.push(args.join(' '));
  try { await collectBenchmarkSync(r.options); }
  finally { console.error = saved; }
  for (const [root] of r.openCalls) assert.equal(existsSync(root), false, 'collector left its temporary fixture directory behind');
  const log = messages.join('\n');
  for (const secret of fakeSecrets) assert.ok(!log.includes(secret), `diagnostics leaked a synthetic secret: ${secret}`);
  return messages.map(message => JSON.parse(message));
}

function samples(r) {
  assert.equal(r.metrics.length, 1);
  const [id, unit, values] = r.metrics[0];
  assert.equal(id, 'sync-apply-ms');
  assert.equal(unit, 'ms');
  return values;
}

test('successful samples and verification proofs retain their original semantics', async () => {
  const r = rig();
  assert.deepEqual(await run(r), []);
  const values = samples(r);
  assert.equal(values.length, 5);
  assert.deepEqual(values.map(({ phase, index, status, value }) => ({ phase, index, status, value })), [
    { phase: 'warmup', index: 1, status: 'ok', value: 42.75 },
    { phase: 'warmup', index: 2, status: 'ok', value: 42.75 },
    { phase: 'warmup', index: 3, status: 'ok', value: 42.75 },
    { phase: 'measurement', index: 1, status: 'ok', value: 42.75 },
    { phase: 'measurement', index: 2, status: 'ok', value: 42.75 },
  ]);
  assert.equal(r.evidence.length, 5);
  assert.ok(values.every(sample => sample.verification.androidVerified && sample.verification.desktopVerified &&
    sample.verification.desktopMode === 'production' && sample.verification.evidence.path.startsWith('sync-')));
  assert.equal(r.counts['adb-reverse-remove'], 5);
  assert.equal(r.counts['fixture-close'], 5);
});

test('a verification failure after 29 measured samples keeps its step and is not retried', async () => {
  const r = rig({ 'desktop-seed': n => { if (n === 33) assert.fail(fakeSecrets.join(' ')); } },
    { warmup: 3, iterations: 31 });
  const [failure] = await run(r);
  assert.deepEqual(failure, { event: 's1.27.sync-trial.failed', phase: 'measurement', index: 30,
    step: 'desktop-seed', type: 'assertion', reason: 'fixture verification failed' });
  assert.equal(samples(r).length, 33);
  assert.deepEqual(samples(r).at(-1), { phase: 'measurement', index: 30, status: 'failed' });
  assert.equal(r.counts['desktop-seed'], 33);
  assert.equal(r.counts['android-instrumentation'], 32);
  assert.equal(r.evidence.length, 32);
});

test('a structured launcher child failure keeps the adb-reverse step without exposing fields', async () => {
  const error = new Error(fakeSecrets.join(' '));
  Object.assign(error, { s127ChildFailure: 'nonzero-exit', stdout: fakeSecrets[0], stderr: fakeSecrets[5],
    cmd: fakeSecrets[6], status: 17, signal: fakeSecrets[2], cause: new Error(fakeSecrets[1]) });
  const r = rig({ 'adb-reverse': () => { throw error; } });
  const [failure] = await run(r);
  assert.deepEqual(failure, { event: 's1.27.sync-trial.failed', phase: 'warmup', index: 1,
    step: 'adb-reverse', type: 'child-process', reason: 'child process failed', childFailure: 'nonzero-exit' });
  assert.deepEqual(samples(r), [{ phase: 'warmup', index: 1, status: 'failed' }]);
  assert.equal(r.counts['adb-reverse'], 1);
  assert.equal(r.counts['adb-reverse-remove'], undefined);
  assert.equal(r.counts['android-instrumentation'], undefined);
});

test('a real failing child in desktop seed is classified without stderr or argv', async () => {
  const r = rig({ 'desktop-seed': () => execFileSync(process.execPath,
    ['-e', `process.stderr.write(${JSON.stringify(fakeSecrets.join(' '))}); process.exit(7)`], { stdio: 'pipe' }) });
  const [failure] = await run(r);
  assert.deepEqual(failure, { event: 's1.27.sync-trial.failed', phase: 'warmup', index: 1,
    step: 'desktop-seed', type: 'child-process', reason: 'child process failed', childFailure: 'nonzero-exit' });
  assert.equal(r.counts['desktop-seed'], 1);
  assert.equal(r.counts['adb-reverse'], undefined);
});

test('a missing child executable is classified as spawn failure without its path', async () => {
  const r = rig({ 'desktop-seed': () => execFileSync(`/not-a-real-test-directory/${fakeSecrets[0]}`, [], { stdio: 'pipe' }) });
  const [failure] = await run(r);
  assert.equal(failure.step, 'desktop-seed');
  assert.equal(failure.type, 'child-process');
  assert.equal(failure.childFailure, 'spawn-failed');
});

test('the original failure remains visible when multiple cleanup operations also fail', async () => {
  const r = rig({
    'desktop-seed': () => assert.fail(fakeSecrets.join(' ')),
    'desktop-close': () => { throw new TypeError(fakeSecrets.join(' ')); },
    'fixture-close': () => { throw new Error(fakeSecrets.join(' ')); },
  });
  const [failure] = await run(r);
  assert.equal(failure.step, 'desktop-seed');
  assert.equal(failure.type, 'assertion');
  assert.deepEqual(failure.additionalFailures, [
    { step: 'desktop-close', type: 'runtime', reason: 'fixture verification failed', errorClass: 'TypeError' },
    { step: 'fixture-close', type: 'runtime', reason: 'fixture verification failed', errorClass: 'Error' },
  ]);
  assert.deepEqual(samples(r), [{ phase: 'warmup', index: 1, status: 'failed' }]);
  assert.equal(r.counts['desktop-close'], 1);
  assert.equal(r.counts['fixture-close'], 1);
});

test('failure to remove the adb tunnel is reported as a failed trial', async () => {
  const error = Object.assign(new Error(fakeSecrets.join(' ')), { s127ChildFailure: 'timeout' });
  const r = rig({ 'adb-reverse-remove': () => { throw error; } });
  const [failure] = await run(r);
  assert.equal(failure.step, 'adb-reverse-remove');
  assert.equal(failure.childFailure, 'timeout');
  assert.deepEqual(samples(r), [{ phase: 'warmup', index: 1, status: 'failed' }]);
  assert.equal(r.counts['desktop-close'], 1);
  assert.equal(r.counts['fixture-close'], 1);
  assert.equal(r.evidence.length, 0);
});

test('evidence write failure remains a failed sample rather than an unverified pass', async () => {
  const r = rig({ 'evidence-write': () => { throw new Error(fakeSecrets.join(' ')); } });
  const [failure] = await run(r);
  assert.equal(failure.step, 'evidence-write');
  assert.equal(failure.type, 'runtime');
  assert.deepEqual(samples(r), [{ phase: 'warmup', index: 1, status: 'failed' }]);
  assert.equal(r.counts['android-instrumentation'], 1);
});

test('legacy launcher failures retain their stage but do not invent a child outcome', async () => {
  const r = rig({ 'android-instrumentation': () => { throw new Error('Instrumentation command failed'); } });
  const [failure] = await run(r);
  assert.equal(failure.step, 'android-instrumentation');
  assert.equal(failure.type, 'child-process');
  assert.equal(failure.childFailure, 'unknown');
});

test('invalid structured labels and hostile error properties are never copied into diagnostics', async () => {
  const error = new Error(fakeSecrets.join(' '));
  error.s127ChildFailure = fakeSecrets[2];
  let reads = 0;
  Object.defineProperty(error, 'name', { get: () => ++reads < 3 ? 'TypeError' : fakeSecrets[0] });
  const r = rig({ 'desktop-seed': () => { throw error; } });
  const [failure] = await run(r);
  assert.equal(failure.step, 'desktop-seed');
  assert.equal(failure.type, 'runtime');
  assert.ok(failure.errorClass === undefined || failure.errorClass === 'TypeError');
});

test('reverse allocates a distinct device port, preserving the desktop endpoint and removing only that tunnel', async () => {
  const r = rig({}, { warmup: 1, iterations: 0 });
  const original = r.fixture.endpoint;
  assert.deepEqual(await run(r), []);
  assert.deepEqual(r.adbCalls, [
    ['reverse', '--no-rebind', 'tcp:52345', 'tcp:12345'],
    ['shell', 'pm', 'clear', 'com.deepstudent.app.benchmark'],
    ['reverse', '--remove', 'tcp:52345'],
  ]);
  assert.strictEqual(r.openCalls[0][1], r.fixture);
  assert.equal(r.fixture.endpoint, original);
  const extra = r.instrumentCalls[0][1];
  assert.equal(extra[extra.indexOf('syncEndpoint') + 1], original.replace(':12345/', ':52345/'));
  assert.equal(samples(r)[0].status, 'ok');
});

test('occupied reverse port is skipped before any reset or instrumentation, not retried as a sync', async () => {
  const busy = Object.assign(new Error(fakeSecrets.join(' ')), { s127ChildFailure: 'address-in-use' });
  const r = rig({ 'adb-reverse': count => { if (count === 1) throw busy; } }, { warmup: 1, iterations: 0 });
  r.options.reverseStart = 65535;
  assert.deepEqual(await run(r), []);
  assert.deepEqual(r.adbCalls, [
    ['reverse', '--no-rebind', 'tcp:65535', 'tcp:12345'],
    ['reverse', '--no-rebind', 'tcp:49152', 'tcp:12345'],
    ['shell', 'pm', 'clear', 'com.deepstudent.app.benchmark'],
    ['reverse', '--remove', 'tcp:49152'],
  ]);
  assert.equal(r.counts['android-instrumentation'], 1);
  assert.equal(samples(r)[0].status, 'ok');
});

test('no device operations or unsafe cleanup after every bounded bind attempt is rejected', async () => {
  const busy = Object.assign(new Error(fakeSecrets.join(' ')), { s127ChildFailure: 'address-in-use' });
  const r = rig({ 'adb-reverse': () => { throw busy; } });
  const [failure] = await run(r);
  assert.equal(failure.step, 'adb-reverse');
  assert.equal(failure.childFailure, 'address-in-use');
  assert.equal(r.counts['adb-reverse'], 4);
  assert.equal(r.counts['device-reset'], undefined);
  assert.equal(r.counts['android-instrumentation'], undefined);
  assert.equal(r.counts['adb-reverse-remove'], undefined);
  assert.equal(r.counts['desktop-close'], 1);
  assert.equal(r.counts['fixture-close'], 1);
  assert.deepEqual(samples(r), [{ phase: 'warmup', index: 1, status: 'failed' }]);
});

test('after allocation, invalid endpoint still cleans up the individually assigned tunnel', async () => {
  const r = rig({}, { warmup: 1, iterations: 0 });
  r.fixture.endpoint = `invalid:${fakeSecrets.join(' ')}`;
  const [failure] = await run(r);
  assert.equal(failure.step, 'android-endpoint');
  assert.equal(failure.type, 'assertion');
  assert.equal(failure.reason, 'invalid fixture endpoint');
  assert.deepEqual(r.adbCalls, [
    ['reverse', '--no-rebind', 'tcp:52345', 'tcp:12345'],
    ['reverse', '--remove', 'tcp:52345'],
  ]);
  assert.deepEqual(samples(r), [{ phase: 'warmup', index: 1, status: 'failed' }]);
});

test('the two new structured adb failure categories are accepted without exposing other fields', async () => {
  for (const category of ['address-in-use', 'device-unavailable']) {
    const error = Object.assign(new Error(fakeSecrets.join(' ')), { s127ChildFailure: category,
      stdout: fakeSecrets[0], stderr: fakeSecrets[5], cmd: fakeSecrets[6] });
    const r = rig({ 'adb-reverse': () => { throw error; } });
    const [failure] = await run(r);
    assert.equal(failure.step, 'adb-reverse');
    assert.equal(failure.type, 'child-process');
    assert.equal(failure.childFailure, category);
    assert.equal(r.counts['adb-reverse'], category === 'address-in-use' ? 4 : 1);
  }
});

test('each verification or setup failure reports the responsible stage', async () => {
  const cases = [
    { overrides: { 'fixture-start': () => { throw new TypeError(fakeSecrets[0]); } },
      step: 'fixture-start', type: 'runtime' },
    { overrides: { 'desktop-open': () => { throw new Error(fakeSecrets[0]); } },
      step: 'desktop-open', type: 'runtime' },
    { overrides: { 'device-reset': () => 'unexpected' }, step: 'device-reset', type: 'assertion',
      reason: 'disposable benchmark reset failed' },
    { overrides: { 'android-instrumentation': () => [] }, step: 'android-verification', type: 'assertion',
      reason: 'missing Android verification' },
    { overrides: { 'desktop-receive': () => { throw new SyntaxError(fakeSecrets[2]); } },
      step: 'desktop-receive', type: 'runtime' },
    { overrides: { 'desktop-receive': () => ({ downloaded: { changes: [] } }) },
      step: 'desktop-row-verification', type: 'assertion', reason: 'desktop did not import the outbound row' },
    { overrides: { 'desktop-content-verification': () => [null, fakeSecrets[4]] },
      step: 'desktop-content-verification', type: 'assertion', reason: 'desktop did not retain the outbound content' },
  ];
  for (const { overrides, step, type, reason } of cases) {
    const r = rig(overrides, { warmup: 1, iterations: 0 });
    const [failure] = await run(r);
    assert.equal(failure.step, step);
    assert.equal(failure.type, type);
    if (reason) assert.equal(failure.reason, reason);
    assert.deepEqual(samples(r), [{ phase: 'warmup', index: 1, status: 'failed' }]);
    assert.equal(r.counts['fixture-start'], 1);
  }
});
