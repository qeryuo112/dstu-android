import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { bindDisposableReverse, phaseIndex } from './benchmark-device-lib.mjs';
import { desktopCommit, fixtureContent, fixtureHash, openDesktopFixture } from './sync-notes-desktop.mjs';
import { startWebDavFixture } from './sync-webdav-fixture.mjs';

const app = 'com.deepstudent.app.benchmark';
const testClass = 'com.deepstudent.app.benchmark.BenchmarkSyncDeviceTest';
const fixtureId = 's1-27-desktop-roundtrip-v1';
const androidContent = 'Public Android benchmark note: 中文 😀\n';
// Semantic fixture bytes; generated note IDs, device IDs, salts and timestamps are not input parameters.
export const syncCorpusSha256 = createHash('sha256').update(JSON.stringify([
  fixtureId, desktopCommit, 'DSBK-v1+v2', 'Public S1.25', fixtureContent, fixtureHash, 1048593,
  'typed-source-link', 'Public S1.27 Android', androidContent, 'public',
])).digest('hex');

async function measure({ adb, instrument, diagnosis, reverseStart }) {
  const root = mkdtempSync(join(tmpdir(), 'dstu-benchmark-sync-'));
  let desktop, fixture, tunnel;
  try {
    diagnosis.step = 'fixture-start';
    fixture = await startWebDavFixture();
    diagnosis.step = 'desktop-open';
    desktop = await openDesktopFixture(root, fixture, true);
    diagnosis.step = 'desktop-seed';
    const seeded = await desktop.seed();
    diagnosis.step = 'adb-reverse';
    const binding = await bindDisposableReverse(adb, fixture.port, reverseStart);
    tunnel = binding.tunnel;
    if (binding.busy) console.log(JSON.stringify({ event: 's1.27.sync-reverse.busy-port-skipped', count: binding.busy }));
    diagnosis.step = 'android-endpoint';
    assert.ok(fixture.endpoint === `http://127.0.0.1:${fixture.port}/dav/`, 'invalid fixture endpoint');
    const endpoint = `http://127.0.0.1:${binding.devicePort}/dav/`;
    diagnosis.step = 'device-reset';
    assert.equal(adb(['shell', 'pm', 'clear', app]).trim(), 'Success', 'disposable benchmark reset failed');
    diagnosis.step = 'android-instrumentation';
    const events = await instrument('syncOnce', ['-e', 'syncEndpoint', endpoint,
      '-e', 'syncNote', seeded.noteId, '-e', 'syncFile', seeded.fileId], testClass);
    diagnosis.step = 'android-verification';
    const trial = events.find(event => event.type === 'sync-trial');
    assert.ok(trial?.androidVerified === true && Number.isFinite(trial.durationMs) && trial.durationMs > 0,
      'missing Android verification');
    diagnosis.step = 'desktop-receive';
    const received = await desktop.receive();
    diagnosis.step = 'desktop-row-verification';
    assert.ok(received.downloaded.changes.some(change => change.table_name === 'notes' && change.record_id === trial.androidNoteId),
      'desktop did not import the outbound row');
    diagnosis.step = 'desktop-content-verification';
    assert.ok(desktop.notes.getNoteWithContent(trial.androidNoteId, desktop.db)[1] === androidContent,
      'desktop did not retain the outbound content');
    return trial.durationMs;
  } catch (error) {
    // Capture the original failure before a later cleanup exception can replace it.
    diagnosis.failures.push(publicFailure(error, diagnosis.step));
    throw error;
  } finally {
    try { if (tunnel) cleanup(diagnosis, 'adb-reverse-remove', () => adb(['reverse', '--remove', tunnel])); }
    finally {
      try { if (desktop != null) cleanup(diagnosis, 'desktop-close', () => desktop.close()); }
      finally {
        try { if (fixture != null) await cleanupAsync(diagnosis, 'fixture-close', () => fixture.close()); }
        finally { cleanup(diagnosis, 'temporary-directory-remove', () => rmSync(root, { recursive: true, force: true })); }
      }
    }
  }
}

function cleanup(diagnosis, step, operation) {
  diagnosis.step = step;
  try { return operation(); }
  catch (error) { diagnosis.failures.push(publicFailure(error, step)); throw error; }
}

async function cleanupAsync(diagnosis, step, operation) {
  diagnosis.step = step;
  try { return await operation(); }
  catch (error) { diagnosis.failures.push(publicFailure(error, step)); throw error; }
}

export async function collectBenchmarkSync({ contract, adb, instrument, putMetric, out, writeEvidence, reverseStart }) {
  assert.equal(contract.syncCorpus.id, fixtureId);
  assert.equal(contract.syncCorpus.sha256, syncCorpusSha256, 'sync fixture changed');
  const samples = [];
  for (let index = 0; index < contract.policy.warmup + contract.policy.iterations; index++) {
    const iteration = phaseIndex(index);
    const diagnosis = { step: 'temporary-directory', failures: [] };
    try {
      const value = await measure({ adb, instrument, diagnosis, reverseStart });
      diagnosis.step = 'evidence-write';
      const proof = { fixtureId, corpusSha256: syncCorpusSha256, androidVerified: true, desktopVerified: true,
        desktopMode: 'production', ...iteration };
      const evidence = writeEvidence(out, `sync-${iteration.phase}-${iteration.index}.json`, proof);
      samples.push({ ...iteration, status: 'ok', value, verification: { ...proof, evidence } });
    } catch (error) {
      // Only fixed step/category/reason strings. Never print exceptions, commands, endpoints or fixture data.
      if (!diagnosis.failures.length) diagnosis.failures.push(publicFailure(error, diagnosis.step));
      const [failure, ...additionalFailures] = diagnosis.failures;
      console.error(JSON.stringify({ event: 's1.27.sync-trial.failed', ...iteration, ...failure,
        ...(additionalFailures.length ? { additionalFailures } : {}) }));
      samples.push({ ...iteration, status: 'failed' });
      break;
    }
  }
  putMetric('sync-apply-ms', 'ms', samples);
}

const childFailures = new Set(['spawn-failed', 'nonzero-exit', 'signaled', 'timeout', 'output-limit',
  'address-in-use', 'device-unavailable', 'unknown']);

function publicFailure(error, step) {
  // Treat every error field as untrusted: select fixed labels, never emit field values verbatim.
  let failure = { step, type: 'runtime', reason: 'fixture verification failed' };
  try {
    const child = error?.s127ChildFailure;
    if (childFailures.has(child)) return { step, type: 'child-process', reason: 'child process failed', childFailure: child };
    if (error?.name === 'AssertionError') {
      const firstLine = typeof error.message === 'string' ? error.message.split('\n', 1)[0] : '';
      return { step, type: 'assertion', reason: safeReason(firstLine) };
    }
    // execFileSync errors expose child metadata, but never log status, signal, argv, stdout or stderr.
    if (Array.isArray(error?.output) || Array.isArray(error?.spawnargs)) {
      const outcome = error.code === 'ETIMEDOUT' ? 'timeout' : error.code === 'ENOBUFS' ? 'output-limit' :
        error.signal ? 'signaled' : Number.isInteger(error.status) && error.status !== 0 ? 'nonzero-exit' :
          Array.isArray(error.spawnargs) ? 'spawn-failed' : 'unknown';
      return { step, type: 'child-process', reason: 'child process failed', childFailure: outcome };
    }
    // The current launcher has already discarded the child details for these exact, static messages.
    if (['Device command failed', 'Instrumentation transport failed', 'Instrumentation command failed'].includes(error?.message)) {
      return { step, type: 'child-process', reason: 'child process failed', childFailure: 'unknown' };
    }
    if (error?.message === 'Instrumentation did not pass exactly one non-skipped test') {
      return { step, type: 'instrumentation', reason: 'instrumentation test failed' };
    }
    const errorClass = error?.name;
    if (['Error', 'TypeError', 'ReferenceError', 'SyntaxError', 'RangeError'].includes(errorClass)) {
      failure = { ...failure, errorClass };
    }
  } catch { /* A hostile/unusual error object must not break failure reporting. */ }
  return failure;
}

function safeReason(value) {
  const allowed = ['invalid fixture endpoint',
    'disposable benchmark reset failed', 'missing Android verification',
    'desktop did not import the outbound row', 'desktop did not retain the outbound content'];
  return allowed.includes(value) ? value : 'fixture verification failed';
}
