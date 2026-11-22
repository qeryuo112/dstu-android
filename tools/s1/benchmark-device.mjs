#!/usr/bin/env node
// Inert until both explicit execution and disposable-data flags are present.
import { execFileSync, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import { hash, readJson, validateContract, validateObservationContract } from './benchmark-lib.mjs';
import { ROOT, sourceFingerprint } from './benchmark-provenance.mjs';
import { collectBenchmarkStartup, STARTUP_PACKAGE, STARTUP_RUNNER } from './benchmark-startup.mjs';
import { BENCHMARK_ACTIVITY, BENCHMARK_APP_ID, BENCHMARK_TEST_ID, PAGE_TEST_CLASS, SYNC_TEST_CLASS,
  classifyAdbFailure, notesSamples, parseColdStart, parseInstrumentation, requireFreshBenchmarkPackages, terminalSamples, validateApkIdentity, validateStartupApkIdentity, validateSyncAdbArgs } from './benchmark-device-lib.mjs';

const usage = 'Usage: benchmark-device.mjs collect --spec PRIVATE.json --out NEW_DIRECTORY --execute --ack-disposable-data [--sync] [--observe]';
const contractPath = resolve(ROOT, 'config/benchmark-s1-27.json');
function parse(args) {
  if (args.shift() !== 'collect') throw new Error(usage);
  const result = {};
  while (args.length) {
    const flag = args.shift();
    if (['--execute', '--ack-disposable-data', '--sync', '--observe'].includes(flag)) result[flag.slice(2)] = true;
    else if (flag === '--spec' || flag === '--out') {
      const value = args.shift();
      if (!value || value.startsWith('--')) throw new Error(usage);
      result[flag.slice(2)] = value;
    } else throw new Error(usage);
  }
  if (!result.execute || !result['ack-disposable-data'] || !result.spec || !result.out) throw new Error(`Refusing to contact adb without explicit flags. ${usage}`);
  return result;
}
function ensurePrivateDestination(path) {
  const out = resolve(path), parent = realpathSync(resolve(out, '..'));
  const rel = relative(realpathSync(ROOT), parent);
  if (rel === '' || (!rel.startsWith(`..${sep}`) && rel !== '..' && !isAbsolute(rel))) throw new Error('Output directory must be outside the repository');
  mkdirSync(out, { mode: 0o700 });
  return realpathSync(out);
}
function writeEvidence(out, name, value) {
  const bytes = Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
  writeFileSync(resolve(out, name), bytes, { flag: 'wx', mode: 0o600 });
  return { path: name, sha256: hash(bytes) };
}
function required(value, name) { if (typeof value !== 'string' || !value.trim()) throw new Error(`Missing ${name}`); return value; }
function validateSpec(spec, contract, observation) {
  const d = spec.device ?? {}, b = spec.build ?? {};
  required(d.serial, 'device.serial'); required(d.operatorAlias, 'device.operatorAlias');
  if (d.physicalAttested !== true) throw new Error('Physical-device attestation required');
  if (observation) {
    if (d.profileId != null || d.ramMiB != null) throw new Error('An unclassified observation must not assign a tier or marketed RAM');
  } else {
    required(d.profileId, 'device.profileId');
    if (!Number.isInteger(d.ramMiB)) throw new Error('Marketed RAM MiB required for historical matrix mode');
    if (!contract.devices.some(profile => profile.id === d.profileId)) throw new Error('Unknown device profile');
  }
  for (const key of ['apk', 'testApk', 'variant', 'schemaFingerprint', 'wireVersion', 'sourceFingerprintSha256']) required(b[key], `build.${key}`);
  if (!Number.isInteger(b.versionCode) || b.versionCode < 1 || !/^[a-f0-9]{64}$/u.test(b.sourceFingerprintSha256)) throw new Error('Invalid version code or source fingerprint');
  for (const path of [b.apk, b.testApk]) if (!isAbsolute(path) || !existsSync(path)) throw new Error('APK paths must be existing absolute paths');
  if (b.startupApk != null && (!isAbsolute(b.startupApk) || !existsSync(b.startupApk))) throw new Error('Startup APK must be an existing absolute path');
  if (Object.hasOwn(spec, 'sync')) throw new Error('External sync adapters are not supported; use --sync');
}
function apkPreflight(spec) {
  const sdk = process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk');
  const aapt = spec.aaptPath ?? resolve(sdk, 'build-tools/36.0.0/aapt');
  const dump = (format, apk) => execFileSync(aapt, ['dump', format, apk, ...(format === 'xmltree' ? ['AndroidManifest.xml'] : [])], { encoding: 'utf8', timeout: 30_000, maxBuffer: 16 * 1024 * 1024 });
  const result = validateApkIdentity(dump('badging', spec.build.apk), dump('xmltree', spec.build.apk),
    dump('badging', spec.build.testApk), dump('xmltree', spec.build.testApk), spec.build.sourceFingerprintSha256);
  if (spec.build.startupApk) validateStartupApkIdentity(dump('badging', spec.build.startupApk), dump('xmltree', spec.build.startupApk));
  if (result.versionCode !== spec.build.versionCode) throw new Error('APK versionCode differs from specification');
  return result;
}
function parseDisplay(size, density, observation) {
  const overridden = /Override (?:size|density):/u.test(`${size}\n${density}`);
  if (overridden && !observation) throw new Error('Display size/density overrides are not permitted in historical matrix mode');
  const pixels = size.match(/Physical size:\s*(\d+)x(\d+)/u), dpi = density.match(/Physical density:\s*(\d+)/u);
  if (!pixels || !dpi || Number(dpi[1]) <= 0) throw new Error('Physical display metrics unavailable');
  return { pixels: [Number(pixels[1]), Number(pixels[2])], densityDpi: Number(dpi[1]), overridden,
    smallestWidthDp: Math.min(Number(pixels[1]), Number(pixels[2])) * 160 / Number(dpi[1]) };
}
const childFailure = kind => Object.assign(new Error('Benchmark ADB child failed'), { s127ChildFailure: kind });

function asyncAdb(executable, serial, args, timeout = 420_000) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(executable, ['-s', serial, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '', diagnostic = '', bytes = 0, failure;
    const timer = setTimeout(() => { failure ??= 'timeout'; child.kill('SIGKILL'); }, timeout);
    child.stdout.on('data', chunk => {
      bytes += chunk.length;
      if (bytes > 64 * 1024 * 1024) { failure ??= 'output-limit'; child.kill('SIGKILL'); }
      else output += chunk.toString('utf8');
    });
    child.stderr.on('data', chunk => { if (diagnostic.length < 4096) diagnostic = (diagnostic + chunk.toString('utf8')).slice(0, 4096); });
    child.once('error', () => { clearTimeout(timer); reject(childFailure('spawn-failed')); });
    child.once('close', (code, signal) => {
      clearTimeout(timer);
      if (code !== 0 || failure) reject(childFailure(failure ?? classifyAdbFailure({ status: code, signal, stderr: diagnostic })));
      else resolvePromise(output);
    });
  });
}
function preexistingPackages(shell, includeStartup) {
  const current = shell('am', 'get-current-user');
  return requireFreshBenchmarkPackages(current, pkg => shell('dumpsys', 'package', pkg), includeStartup);
}
function matchesProfile(device, profile) {
  const inside = (value, limits) => Number.isFinite(value) && value >= limits[0] && value <= limits[1];
  return inside(device.api, profile.api) && device.abi === profile.abi && inside(device.ramMiB, profile.ramMiB) &&
    inside(device.smallestWidthDp, profile.smallestWidthDp) && device.pageSize === profile.pageSize &&
    device.memTotalMiB <= device.ramMiB && device.memTotalMiB >= device.ramMiB * 0.65;
}

async function collect(options) {
  const observation = options.observe === true;
  const validate = observation ? validateObservationContract : validateContract;
  const spec = readJson(options.spec), contract = validate(readJson(contractPath));
  validateSpec(spec, contract, observation);
  const apkIdentity = apkPreflight(spec); // Hard reject production, debuggable or non-profileable APKs before adb.
  const syncModule = options.sync ? await import('./benchmark-sync.mjs') : null;
  if (syncModule && typeof syncModule.collectBenchmarkSync !== 'function') throw new Error('Built-in sync collector unavailable');
  const before = sourceFingerprint();
  if (before.sha256 !== spec.build.sourceFingerprintSha256) throw new Error('Current source fingerprint differs from the specified build source');
  const out = ensurePrivateDestination(options.out);
  const serial = spec.device.serial, adbPath = spec.adbPath ?? resolve(process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb');
  const adb = (args, encoding = 'utf8', timeout = 120_000) => {
    try { return execFileSync(adbPath, ['-s', serial, ...args], { encoding, timeout, maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }); }
    catch (error) {
      if (args[0] === 'install') {
        const code = /\bINSTALL_(?:FAILED|PARSE_FAILED)_[A-Z_0-9]{1,64}\b/u.exec(`${error.stdout ?? ''}\n${error.stderr ?? ''}`)?.[0];
        throw new Error(code ? `Benchmark package installation failed: ${code}` : 'Benchmark package installation failed');
      }
      throw childFailure(classifyAdbFailure(error, { reverse: args[0] === 'reverse' }));
    }
  };
  const shell = (...args) => adb(['shell', ...args]).trim();
  const startupApk = spec.build.startupApk;
  const currentUser = preexistingPackages(shell, Boolean(startupApk));
  const startedAt = new Date().toISOString();
  const getprop = name => shell('getprop', name);
  const model = getprop('ro.product.model'), fingerprint = getprop('ro.build.fingerprint'), hardware = getprop('ro.hardware'), product = getprop('ro.product.name');
  const qemu1 = getprop('ro.kernel.qemu'), qemu2 = getprop('ro.boot.qemu');
  const emulator = /^(?:emulator-|localhost:)/u.test(serial) || qemu1 === '1' || qemu2 === '1' || /(?:generic|emulator|sdk_gphone|goldfish|ranchu|vbox|virtualbox)/iu.test(`${model} ${fingerprint} ${hardware} ${product}`);
  if (emulator) throw new Error('Emulator detected: physical devices only');
  const androidId = shell('settings', 'get', 'secure', 'android_id');
  if (!/^[a-f0-9]{16,}$/iu.test(androidId)) throw new Error('Android ID unavailable; cannot establish distinct device identity');
  const mem = shell('cat', '/proc/meminfo').match(/^MemTotal:\s*(\d+)\s+kB/mu);
  const display = parseDisplay(shell('wm', 'size'), shell('wm', 'density'), observation);
  if (!mem) throw new Error('MemTotal unavailable');
  const device = { profileId: observation ? null : spec.device.profileId, model, buildFingerprint: fingerprint, hardware, product,
    api: Number(getprop('ro.build.version.sdk')), abi: getprop('ro.product.cpu.abi'), ramMiB: observation ? null : spec.device.ramMiB,
    memTotalMiB: Number(mem[1]) / 1024, pageSize: Number(shell('getconf', 'PAGE_SIZE')), smallestWidthDp: display.smallestWidthDp,
    serialSha256: hash(Buffer.from(serial)), androidIdSha256: hash(Buffer.from(androidId)), isEmulator: false,
    qemu: '0', qemuProperties: { kernel: qemu1, boot: qemu2 }, physicalAttested: true };
  if (observation) {
    if (device.abi !== 'arm64-v8a') throw new Error('The benchmark APK requires arm64-v8a');
  } else if (!matchesProfile(device, contract.devices.find(profile => profile.id === device.profileId))) {
    throw new Error('Device properties do not match requested profile');
  }
  device.identityEvidence = writeEvidence(out, 'device-identity.json', { ...device, display });
  device.attestationEvidence = writeEvidence(out, 'physical-attestation.json', { physicalAttested: true, operatorAlias: spec.device.operatorAlias,
    profileId: device.profileId, serialSha256: device.serialSha256, attestedAt: startedAt, note: 'Operator affirms one physical device, not an emulator.' });
  const installedHash = (packageName, file) => {
    if (![BENCHMARK_APP_ID, BENCHMARK_TEST_ID, STARTUP_PACKAGE].includes(packageName)) throw new Error('Unexpected package identity');
    const paths = shell('pm', 'path', '--user', currentUser, packageName).split('\n').filter(Boolean).map(line => line.replace(/^package:/u, ''));
    if (paths.length !== 1 || !/^\/data\/app\/[a-zA-Z0-9_+=.,~\/-]+\.apk$/u.test(paths[0])) throw new Error(`${packageName}: expected a single readable installed APK`);
    const remote = hash(adb(['exec-out', 'cat', paths[0]], null));
    const local = hash(readFileSync(file));
    if (remote !== local) throw new Error(`${packageName}: installed APK SHA-256 mismatch`);
    return local;
  };
  const installed = [];
  try {
    const packages = [[BENCHMARK_APP_ID, spec.build.apk], [BENCHMARK_TEST_ID, spec.build.testApk]];
    if (startupApk) packages.push([STARTUP_PACKAGE, startupApk]);
    for (const [packageName, apk] of packages) {
      const result = adb(['install', '-R', '-t', '--user', currentUser, apk]); // Explicitly disallow replacement, including on ADB versions defaulting to -r.
      if (!/^Success\s*$/mu.test(result)) throw new Error(`${packageName}: installation failed`);
      installed.push([packageName, apk]);
      installedHash(packageName, apk);
    }
    const apkSha256 = installedHash(BENCHMARK_APP_ID, spec.build.apk);
    const testApkSha256 = installedHash(BENCHMARK_TEST_ID, spec.build.testApk);
    const startupApkSha256 = startupApk ? installedHash(STARTUP_PACKAGE, startupApk) : null;
    const versionOutput = shell('dumpsys', 'package', BENCHMARK_APP_ID);
    const version = Number(versionOutput.match(/\bversionCode=(\d+)/u)?.[1]);
    if (version !== spec.build.versionCode) throw new Error('Installed versionCode differs from specification');
    const apkEvidence = writeEvidence(out, 'installed-apks.json', { ...apkIdentity, apkSha256, installedApkSha256: apkSha256, testApkSha256, startupApkSha256, versionCode: version });
    const battery = shell('dumpsys', 'battery'), thermal = shell('dumpsys', 'thermalservice');
    const level = battery.match(/^\s*level:\s*(\d+)/mu)?.[1], status = battery.match(/^\s*status:\s*(\d+)/mu)?.[1];
    const thermalStatus = thermal.match(/(?:Thermal Status:|mStatus=)\s*(-?\d+)/u)?.[1];
    const environment = { startedAt, batteryState: level && status ? `level=${level} status=${status}` : null,
      thermalState: thermalStatus == null ? null : `status=${thermalStatus}` };
    const run = { formatVersion: 1, unit: 'S1.27', kind: observation ? 'physical-device-observation' : 'physical-device',
      collector: 's1-27-device-v1', runId: randomUUID(), device,
      build: { appId: BENCHMARK_APP_ID, testId: BENCHMARK_TEST_ID, debuggable: false, profileableShell: true, commit: before.commit, sourceFingerprintSha256: before.sha256, sourceUnchanged: false, apkSha256, installedApkSha256: apkSha256,
        variant: spec.build.variant, versionCode: version, schemaFingerprint: spec.build.schemaFingerprint, wireVersion: spec.build.wireVersion, apkEvidence },
      environment, corpus: null, metrics: {} };
    const instrument = async (method, extra = [], testClass = PAGE_TEST_CLASS) => {
      if (![PAGE_TEST_CLASS, SYNC_TEST_CLASS].includes(testClass) || !/^[A-Za-z][A-Za-z0-9_]*$/u.test(method) || !Array.isArray(extra)) throw new Error('Unexpected benchmark instrumentation selection');
      const output = await asyncAdb(adbPath, serial, ['shell', 'am', 'instrument', '--user', currentUser, '-w', '-r', '-e', 'class', `${testClass}#${method}`,
        '-e', 'benchmarkConsent', 'disposable', ...extra, `${BENCHMARK_TEST_ID}/androidx.test.runner.AndroidJUnitRunner`]);
      return parseInstrumentation(output);
    };
    const putMetric = (id, unit, samples, metadata = {}) => {
      const evidence = writeEvidence(out, `metric-${id}.json`, { metric: id, unit, samples, ...(metadata.method ? { method: metadata.method } : {}) });
      run.metrics[id] = { ...metadata, unit, evidence, samples };
      console.log(JSON.stringify({ event: 's1.27.metric.captured', metric: id,
        measurements: samples.filter(s => s.phase === 'measurement' && s.status === 'ok').length,
        failed: samples.filter(s => s.status === 'failed').length }));
    };
    const seed = await instrument('seedDisposableCorpus');
    const corpusEvent = seed.find(e => e.type === 'corpus');
    if (!corpusEvent || corpusEvent.id !== contract.corpus.id || corpusEvent.sha256 !== contract.corpus.sha256 || corpusEvent.count !== contract.corpus.noteCount) throw new Error('Fixed corpus seed or readback mismatch');
    run.corpus = { id: corpusEvent.id, sha256: corpusEvent.sha256, verified: true, evidence: writeEvidence(out, 'corpus.json', corpusEvent) };
    // A single activity-launch smoke check is never a Macrobenchmark metric.
    let smoke;
    try {
      shell('am', 'force-stop', BENCHMARK_APP_ID);
      const totalTimeMs = parseColdStart(adb(['shell', 'am', 'start', '--user', currentUser, '-W', '-n', `${BENCHMARK_APP_ID}/${BENCHMARK_ACTIVITY}`]));
      smoke = { kind: 'activity-launch-smoke', status: 'ok', totalTimeMs, acceptanceEvidence: false };
    } catch { smoke = { kind: 'activity-launch-smoke', status: 'failed', acceptanceEvidence: false }; }
    run.smoke = { ...smoke, evidence: writeEvidence(out, 'smoke-activity-launch.json', smoke) };
    if (startupApk) {
      const runMacro = args => asyncAdb(adbPath, serial, ['shell', 'am', 'instrument', '--user', currentUser, '-w', '-r',
        ...args, `${STARTUP_PACKAGE}/${STARTUP_RUNNER}`], 720_000);
      await collectBenchmarkStartup({ contract, adb, runMacro, putMetric, out, run });
      // AndroidX may reset/reinstall the disposable package on older Android. No stale corpus attribution.
      const afterStartup = (await instrument('verifyDisposableCorpus')).find(e => e.type === 'corpus');
      if (afterStartup?.sha256 !== corpusEvent.sha256 || afterStartup?.count !== corpusEvent.count) throw new Error('Startup did not preserve the fixed corpus');
    }
    try {
      const values = notesSamples(await instrument('notesScrollAndMemory'));
      putMetric('notes-scroll-frame-p95-ms', 'ms', values.frames);
      putMetric('notes-scroll-peak-pss-mib', 'MiB', values.memory);
    } catch { putMetric('notes-scroll-frame-p95-ms', 'ms', [{ phase: 'measurement', index: 1, status: 'failed' }]); }
    try { putMetric('terminal-roundtrip-ms', 'ms', terminalSamples(await instrument('terminalRoundtrip'))); }
    catch { putMetric('terminal-roundtrip-ms', 'ms', [{ phase: 'measurement', index: 1, status: 'failed' }]); }
    if (syncModule) {
      const syncAdb = args => {
        validateSyncAdbArgs(args);
        if (args[0] === 'shell' && args[1] === 'pm' && args[2] === 'clear') return adb(['shell', 'pm', 'clear', '--user', currentUser, BENCHMARK_APP_ID]);
        return adb(args);
      };
      await syncModule.collectBenchmarkSync({ contract, adb: syncAdb, instrument, putMetric, out, writeEvidence });
    }
    run.environment.finishedAt = new Date().toISOString();
    run.build.sourceUnchanged = sourceFingerprint().sha256 === before.sha256;
    writeEvidence(out, 'run.json', run);
    console.log(JSON.stringify({ event: 's1.27.capture.finished', mode: observation ? 'observation' : 'matrix', profile: device.profileId, output: resolve(out, 'run.json'),
      sourceUnchanged: run.build.sourceUnchanged, capturedMetrics: Object.keys(run.metrics), syncRequested: Boolean(syncModule) }));
  } finally {
    for (const [packageName, apk] of installed.reverse()) {
      try {
        installedHash(packageName, apk); // Do not remove a package replaced by another actor.
        const result = adb(['uninstall', '--user', currentUser, packageName]);
        if (!/^Success\s*$/mu.test(result)) throw new Error('Uninstall did not report success');
      } catch {
        console.error(`s1.27 cleanup: did not confirm removal of ${packageName}; inspect manually before another run`);
        process.exitCode = 1;
      }
    }
  }
}

try { await collect(parse(process.argv.slice(2))); }
catch (error) { console.error(`s1.27 collection: ${error.message.replace(/(?:emulator-\d+|[a-f0-9]{16,})/giu, '[redacted]')}`); process.exitCode = 2; }
