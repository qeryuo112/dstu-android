import { randomInt } from 'node:crypto';

import { nearestRank } from './benchmark-lib.mjs';
import { STARTUP_PACKAGE, STARTUP_RUNNER } from './benchmark-startup.mjs';

export function parseInstrumentation(output) {
  if (/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -4/u.test(output) ||
      !/OK \(1 test\)/u.test(output) || !/INSTRUMENTATION_CODE: -1\b/u.test(output)) throw new Error('Instrumentation did not pass exactly one non-skipped test');
  const events = [];
  for (const match of output.matchAll(/^INSTRUMENTATION_STATUS: s127event=(.+)$/gmu)) events.push(JSON.parse(match[1]));
  return events;
}
export function parseColdStart(output) {
  if (!/^Status:\s*ok\s*$/mu.test(output)) throw new Error('Cold start did not succeed');
  const match = output.match(/^TotalTime:\s*(\d+)\s*$/mu);
  if (!match) throw new Error('Cold-start TotalTime missing');
  return Number(match[1]);
}
export function notesSamples(events) {
  const notes = events.filter(e => e.type === 'notes-trial');
  return {
    frames: notes.map(e => ({ phase: e.phase, index: e.index, status: 'ok', value: nearestRank(e.framesMs, 0.95), frameDurationsMs: e.framesMs, droppedReports: e.droppedReports })),
    memory: notes.map(e => ({ phase: e.phase, index: e.index, status: 'ok', value: Math.max(...e.pssSamples.map(p => p.pssMiB)), pssSamples: e.pssSamples })),
  };
}
export function terminalSamples(events) {
  return events.filter(e => e.type === 'terminal-trial').map(e => {
    if (e.markerObserved !== true || e.outputBytes !== 2176) throw new Error('Terminal fixture or marker mismatch');
    return { phase: e.phase, index: e.index, status: 'ok', value: e.durationMs, outputBytes: e.outputBytes, markerObserved: true };
  });
}
export function phaseIndex(index) { return { phase: index < 3 ? 'warmup' : 'measurement', index: index < 3 ? index + 1 : index - 2 }; }

export const BENCHMARK_APP_ID = 'com.deepstudent.app.benchmark';
export const BENCHMARK_TEST_ID = 'com.deepstudent.app.benchmark.test';
export const BENCHMARK_ACTIVITY = 'com.deepstudent.app.MainActivity';
export const PAGE_TEST_CLASS = 'com.deepstudent.app.benchmark.BenchmarkPageDeviceTest';
export const SYNC_TEST_CLASS = 'com.deepstudent.app.benchmark.BenchmarkSyncDeviceTest';

function elements(xml, name) {
  const result = [], stack = [], lines = xml.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const match = lines[i].match(/^(\s*)E: ([A-Za-z0-9_.-]+)\b/u);
    if (!match) continue;
    const indent = match[1].length;
    while (stack.length && stack.at(-1).indent >= indent) stack.pop();
    const parent = stack.at(-1)?.name ?? null;
    if (match[2] === name) {
      const fields = [];
      for (let j = i + 1; j < lines.length && !/^\s*E:/u.test(lines[j]); j++) fields.push(lines[j]);
      result.push({ parent, fields: fields.join('\n') });
    }
    stack.push({ indent, name: match[2] });
  }
  return result;
}
function attributesForElement(xml, name) { return elements(xml, name).map(element => element.fields); }
function hasAttribute(element, name, value) {
  return element.split('\n').some(line => line.includes(`android:${name}(`) && line.includes(`="${value}"`));
}
export function validateApkIdentity(appBadging, appManifest, testBadging, testManifest, expectedSource) {
  const packageId = text => text.match(/^package: name='([^']+)'/mu)?.[1];
  if (packageId(appBadging) !== BENCHMARK_APP_ID || packageId(testBadging) !== BENCHMARK_TEST_ID) throw new Error('Refusing non-benchmark APK package identity');
  if (!attributesForElement(appManifest, 'activity').some(block => hasAttribute(block, 'name', BENCHMARK_ACTIVITY))) throw new Error('Benchmark APK lacks the expected fully-qualified MainActivity');
  const instrumentation = attributesForElement(testManifest, 'instrumentation');
  if (instrumentation.length !== 1 || !hasAttribute(instrumentation[0], 'name', 'androidx.test.runner.AndroidJUnitRunner') ||
      !hasAttribute(instrumentation[0], 'targetPackage', BENCHMARK_APP_ID)) throw new Error('Benchmark test APK instrumentation target mismatch');
  const application = attributesForElement(appManifest, 'application');
  if (application.length !== 1) throw new Error('Benchmark APK application declaration is missing or ambiguous');
  const debugAttribute = application[0].split('\n').filter(line => /android:debuggable\(/u.test(line));
  if (debugAttribute.length > 1 || (debugAttribute.length === 1 && !/android:debuggable\([^)]*\)=\(type 0x12\)0x0(?:\s|$)/u.test(debugAttribute[0]))) throw new Error('Benchmark APK must not be debuggable');
  const profileable = elements(appManifest, 'profileable');
  if (profileable.length !== 1 || profileable[0].parent !== 'application' || !/android:shell\([^)]*\)=\(type 0x12\)0xffffffff(?:\s|$)/u.test(profileable[0].fields)) throw new Error('Benchmark APK must declare profileable shell=true');
  const source = elements(appManifest, 'meta-data').filter(element => element.parent === 'application' &&
    hasAttribute(element.fields, 'name', 'com.deepstudent.benchmark.sourceSha256'));
  if (!/^[a-f0-9]{64}$/u.test(expectedSource ?? '') || source.length !== 1 || !hasAttribute(source[0].fields, 'value', expectedSource)) {
    throw new Error('Benchmark APK contains different or missing source attribution');
  }
  const version = appBadging.match(/^package: name='[^']+' versionCode='(\d+)'/mu)?.[1];
  if (!version) throw new Error('Benchmark APK versionCode unavailable');
  return { appId: BENCHMARK_APP_ID, testId: BENCHMARK_TEST_ID, versionCode: Number(version), debuggable: false, profileableShell: true };
}
export function validateStartupApkIdentity(badging, manifest) {
  if (badging.match(/^package: name='([^']+)'/mu)?.[1] !== STARTUP_PACKAGE) throw new Error('Wrong startup benchmark APK package');
  const instrumentation = attributesForElement(manifest, 'instrumentation');
  if (instrumentation.length !== 1 || !hasAttribute(instrumentation[0], 'name', STARTUP_RUNNER) ||
      !hasAttribute(instrumentation[0], 'targetPackage', STARTUP_PACKAGE)) throw new Error('Startup benchmark is not self-instrumenting');
}
// Inspect child diagnostics in memory, emit only a bounded enum (never the command or its output).
export function classifyAdbFailure(error, { reverse = false } = {}) {
  if (error?.code === 'ETIMEDOUT') return 'timeout';
  if (error?.code === 'ENOBUFS' || error?.code === 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER') return 'output-limit';
  if (error?.code === 'ENOENT' || error?.code === 'EACCES') return 'spawn-failed';
  if (error?.signal != null) return 'signaled';
  const stderr = String(error?.stderr ?? '');
  if (reverse && /^(?:adb: error: )?(?:error: )?(?:cannot bind listener: Address already in use|cannot rebind existing socket)\r?$/mu.test(stderr)) return 'address-in-use';
  if (/^(?:adb: (?:error: )?)?(?:error: )?(?:device offline|no devices\/emulators found|device '[^'\r\n]+' not found)\r?$/mu.test(stderr)) return 'device-unavailable';
  return Number.isInteger(error?.status) && error.status !== 0 ? 'nonzero-exit' : 'unknown';
}

export function validateSyncAdbArgs(args) {
  if (!Array.isArray(args) || args.some(arg => typeof arg !== 'string')) throw new Error('Invalid sync adb arguments');
  const port = value => /^tcp:([1-9][0-9]{0,4})$/u.test(value ?? '') && Number(value.slice(4)) <= 65535;
  if (args.length === 4 && args[0] === 'reverse' && args[1] === '--no-rebind' && port(args[2]) && port(args[3]) &&
      Number(args[2].slice(4)) >= 49152 && args[2] !== args[3]) return;
  if (args.length === 3 && args[0] === 'reverse' && args[1] === '--remove' && port(args[2])) return;
  if (args.length === 4 && args[0] === 'shell' && args[1] === 'pm' && args[2] === 'clear' && args[3] === BENCHMARK_APP_ID) return;
  if (args.length === 4 && args[0] === 'shell' && args[1] === 'am' && args[2] === 'force-stop' && args[3] === BENCHMARK_APP_ID) return;
  throw new Error('Sync adb call is not allowlisted; only reverse and benchmark-only clear/force-stop are allowed');
}

// Use an explicit independent remote port. Some host ADB revisions track the
// literal tcp:0 instead of the allocated reverse name when enforcing allowed targets.
// Try another port only for a rejected bind, before any Android app operation.
export async function bindDisposableReverse(adb, hostPort, start = randomInt(49152, 65536)) {
  if (!Number.isInteger(hostPort) || hostPort < 1 || hostPort > 65535 ||
      !Number.isInteger(start) || start < 49152 || start > 65535) throw new Error('Invalid fixture port');
  let busy = 0;
  for (let offset = 0; offset <= 4; offset++) {
    const devicePort = 49152 + (start - 49152 + offset) % 16384;
    if (devicePort === hostPort) continue;
    const tunnel = `tcp:${devicePort}`;
    try {
      await adb(['reverse', '--no-rebind', tunnel, `tcp:${hostPort}`]);
      return { devicePort, tunnel, busy };
    } catch (error) {
      if (error?.s127ChildFailure !== 'address-in-use' || ++busy === 4) throw error;
    }
  }
  throw new Error('Fixture reverse attempt limit reached');
}

export function requireFreshBenchmarkPackages(current, dumpPackage, includeStartup = false) {
  if (!/^\d+$/u.test(current)) throw new Error('Cannot determine the current Android user safely');
  const targets = [BENCHMARK_APP_ID, BENCHMARK_TEST_ID, ...(includeStartup ? [STARTUP_PACKAGE] : [])];
  for (const pkg of targets) {
    // Global package record, including installations for hidden OEM secondary users.
    // An unsupported, empty, or permission-denied response never proves absence.
    if (dumpPackage(pkg).trim() !== `Unable to find package: ${pkg}`) {
      throw new Error('A benchmark package may already exist; refusing to touch existing data');
    }
  }
  return current;
}
