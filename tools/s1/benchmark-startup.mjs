import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const STARTUP_PACKAGE = 'com.deepstudent.benchmark.startup';
export const STARTUP_CLASS = `${STARTUP_PACKAGE}.ColdStartupBenchmark`;
export const STARTUP_TEST = 'coldStartup';
export const STARTUP_RUNNER = `${STARTUP_PACKAGE}.StartupBenchmarkRunner`;
export const STARTUP_METHOD = 'androidx.benchmark.macro.StartupTimingMetric/COLD';
export const startupMetrics = new Map([
  ['cold-start-ttid-ms', 'timeToInitialDisplayMs'], ['cold-start-ttfd-ms', 'timeToFullDisplayMs'],
]);

export function readMacroStartup(report, payload, policy) {
  assert.ok(report?.context?.payload && report.benchmarks?.length === 1, 'unexpected Macrobenchmark report');
  for (const [key, value] of Object.entries(payload)) assert.ok(report.context.payload[key] === value, 'Macrobenchmark attribution mismatch');
  const run = report.benchmarks[0];
  assert.ok(run.className === STARTUP_CLASS && run.name === STARTUP_TEST &&
    run.warmupIterations === policy.warmup && run.repeatIterations === policy.iterations, 'unexpected cold-start test or iteration count');
  const available = run.profilerOutputs?.filter(output => output.type === 'PerfettoTrace') ?? [];
  const traceFiles = Array.from({ length: policy.iterations }, (_, index) => {
    const matches = available.filter(trace => trace.label === `Trace Iteration ${index}`);
    assert.ok(matches.length === 1 && /^[A-Za-z0-9._=,\[\]-]{1,240}\.perfetto-trace$/u.test(matches[0].filename) &&
      !matches[0].filename.startsWith('.'), 'missing or invalid Perfetto trace');
    return matches[0].filename;
  });
  assert.ok(new Set(traceFiles).size === policy.iterations, 'reused Perfetto trace');
  const metrics = new Map();
  for (const [id, key] of startupMetrics) {
    const runs = run.metrics?.[key]?.runs;
    if (runs == null || runs.length < policy.iterations) continue; // no synthetic TTFD when reportFullyDrawn was absent
    assert.ok(Array.isArray(runs) && runs.length === policy.iterations && runs.every(value => Number.isFinite(value) && value > 0), 'invalid startup values');
    metrics.set(id, runs);
  }
  const ttid = metrics.get('cold-start-ttid-ms'), ttfd = metrics.get('cold-start-ttfd-ms');
  if (ttid && ttfd) assert.ok(ttfd.every((value, index) => value >= ttid[index]), 'TTFD earlier than TTID');
  return { metrics, traceFiles };
}

export function startupPayload(run) {
  return { appId: run.build.appId, apkSha256: run.build.apkSha256,
    sourceFingerprintSha256: run.build.sourceFingerprintSha256, corpusSha256: run.corpus.sha256 };
}

function saveEvidence(out, path, bytes) {
  assert.ok(Buffer.isBuffer(bytes) && bytes.length > 0, 'empty Macrobenchmark evidence');
  writeFileSync(resolve(out, path), bytes, { flag: 'wx', mode: 0o600 });
  return { path, sha256: createHash('sha256').update(bytes).digest('hex') };
}

export async function collectBenchmarkStartup({ contract, adb, runMacro, putMetric, out, run }) {
  const uuid = `s127-${randomUUID()}`;
  const base = run.device.api >= 29 ? `/sdcard/Android/media/${STARTUP_PACKAGE}` : `/sdcard/Android/data/${STARTUP_PACKAGE}/cache`;
  const remote = `${base}/${uuid}`;
  const payload = startupPayload(run);
  const args = Object.entries(payload).flatMap(([key, value]) => ['-e', `androidx.benchmark.output.payload.${key}`, value]);
  try {
    const output = await runMacro(['-e', 'benchmarkConsent', 'disposable', '-e', 'additionalTestOutputDir', remote,
      '-e', 'androidx.benchmark.output.enable', 'true', '-e', 'class', `${STARTUP_CLASS}#${STARTUP_TEST}`, ...args]);
    assert.ok(/OK \(1 test\)/u.test(output) && /INSTRUMENTATION_CODE: -1\b/u.test(output) &&
      !/FAILED|shortMsg=|STATUS_CODE: -[34]\b/u.test(output), 'startup instrumentation did not pass');
    const get = path => adb(['exec-out', 'cat', `${remote}/${path}`], null, 60_000);
    const source = get(`${STARTUP_PACKAGE}-benchmarkData.json`);
    const { metrics, traceFiles } = readMacroStartup(JSON.parse(source.toString('utf8')), payload, contract.policy);
    const sourceEvidence = saveEvidence(out, 'androidx-startup.json', source);
    const traces = traceFiles.map(path => saveEvidence(out, path, get(path)));
    for (const [id, values] of metrics) {
      const samples = values.map((value, index) => ({ phase: 'measurement', index: index + 1, status: 'ok', value,
        traceEvidence: traces[index], ...(id === 'cold-start-ttfd-ms' ? { fullDisplayObserved: true } : {}) }));
      putMetric(id, 'ms', samples, { method: STARTUP_METHOD, sourceEvidence, warmupIterations: contract.policy.warmup });
    }
  } catch (error) {
    const reason = error?.name === 'AssertionError' ? error.message.split('\n', 1)[0] : 'transport or official report unavailable';
    const known = ['startup instrumentation did not pass', 'unexpected Macrobenchmark report', 'Macrobenchmark attribution mismatch',
      'unexpected cold-start test or iteration count', 'missing or invalid Perfetto trace', 'reused Perfetto trace',
      'invalid startup values', 'TTFD earlier than TTID', 'empty Macrobenchmark evidence'];
    console.error(JSON.stringify({ event: 's1.27.startup.measurement.failed', reason: known.includes(reason) ? reason : 'official run evidence unavailable' }));
    for (const id of startupMetrics.keys()) putMetric(id, 'ms', [{ phase: 'measurement', index: 1, status: 'failed' }], { method: STARTUP_METHOD });
  } finally {
    // Only this run's fresh UUID directory of the dedicated test package.
    adb(['shell', 'rm', '-r', '-f', remote]);
  }
}
