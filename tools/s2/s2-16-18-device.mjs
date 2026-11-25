// Local physical-device acceptance; the suite never replaces the normal app or reads its data.
import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs, promisify } from 'node:util';
import { bindDisposableReverse, classifyAdbFailure } from '../s1/benchmark-device-lib.mjs';
import { DESKTOP_COMMIT, makeOrphanFixture } from './s2-14-desktop-interop.mjs';
import { verifyRestoreRoundtrip } from './s2-16-host-roundtrip.mjs';
import { inspectPublicationTrace, inspectRemediationTrace, parseInstrumentResults } from './s2-16-18-results.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const app = 'com.deepstudent.app.s2device';
const test = `${app}.test`;
const cache = 'cache/s2-remediation';
const sdk = process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk');
const adbPath = join(sdk, 'platform-tools/adb');
const aapt = join(sdk, 'build-tools/36.0.0/aapt');
const run = promisify(execFile);
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const options = Object.fromEntries(['serial', 'model', 'candidate', 'output'].map(key => [key, { type: 'string' }]));
const { values: args } = parseArgs({ options: { ...options, 'require-publication-trace': { type: 'boolean' } } });
assert.ok(Object.keys(options).every(key => args[key]),
  'usage: node tools/s2/s2-16-18-device.mjs --serial SERIAL --model MODEL --candidate DIR --output NEW_PRIVATE_DIR');
assert.match(args.serial, /^[A-Za-z0-9_.:-]+$/u);
const candidate = await realpath(args.candidate);
const outputParent = await realpath(dirname(resolve(args.output)));
const out = join(outputParent, basename(resolve(args.output)));
assert.ok(relative(root, out).startsWith('../'), 'raw evidence must stay outside the source checkout');
const inCheckout = await run('git', ['-C', outputParent, 'rev-parse', '--is-inside-work-tree'], { env: { ...process.env, LC_ALL: 'C' } }).then(() => true, error => {
  if (!error.stderr?.includes('not a git repository')) throw error;
  return false;
});
assert.ok(!inCheckout, 'raw evidence must stay outside every Git checkout');

async function adb(cmd, extra = {}) {
  try {
    return (await run(adbPath, ['-s', args.serial, ...cmd], {
      timeout: 45_000, maxBuffer: 3 * 1024 * 1024, ...extra,
    })).stdout;
  } catch (error) {
    error.s127ChildFailure = classifyAdbFailure(error, { reverse: cmd[0] === 'reverse' });
    throw error;
  }
}

async function soleDevice() {
  const devices = (await adb(['devices', '-l'])).trim().split('\n').slice(1).filter(line => line.trim());
  assert.equal(devices.length, 1, 'connect only the selected physical device for this run');
  const [serial, state, usb] = devices[0].trim().split(/\s+/u);
  assert.equal(serial, args.serial);
  assert.equal(state, 'device');
  assert.match(usb ?? '', /^usb:/u, 'a physically connected USB device is required');
}

const shell = async (...args) => (await adb(['shell', '-n', ...args])).trim().replaceAll('\r', '');
const absent = async name => (await shell('dumpsys', 'package', name)) === `Unable to find package: ${name}`;

async function installedSha(name) {
  const path = await shell('pm', 'path', '--user', '0', name);
  assert.match(path, /^package:\/data\/app\/[A-Za-z0-9_+=.,~/-]+\/base\.apk$/u, 'expected one installed base APK');
  const readback = (await shell('sha256sum', path.slice('package:'.length))).split(/\s+/u)[0];
  assert.match(readback, /^[0-9a-f]{64}$/u);
  return readback;
}

const names = ['app-debug.apk', 'app-debug-androidTest.apk'];
const hashes = {};
for (const [index, name] of names.entries()) {
  const apk = join(candidate, name);
  const badging = execFileSync(aapt, ['dump', 'badging', apk], { encoding: 'utf8' });
  assert.equal(badging.match(/^package: name='([^']+)'/mu)?.[1], [app, test][index]);
  hashes[name] = sha(await readFile(apk));
}
const checksums = names.map(name => `${hashes[name]}  ${name}\n`).join('');
assert.equal(await readFile(join(candidate, 'SHA256SUMS'), 'utf8'), checksums, 'candidate APK bytes changed');
const apkSetSha256 = sha(checksums);
await soleDevice();
const [model, api, abi, qemu, bootQemu, user] = await Promise.all([
  ['getprop', 'ro.product.model'], ['getprop', 'ro.build.version.sdk'], ['getprop', 'ro.product.cpu.abi'],
  ['getprop', 'ro.kernel.qemu'], ['getprop', 'ro.boot.qemu'], ['am', 'get-current-user'],
].map(cmd => shell(...cmd)));
assert.equal(model, args.model);
assert.match(api, /^\d+$/u);
assert.equal(abi, 'arm64-v8a');
assert.ok(qemu !== '1' && bootQemu !== '1', 'emulator is not accepted');
assert.equal(user, '0', 'must be using the device owner account');
for (const name of [app, test]) assert.ok(await absent(name), `refusing to alter pre-existing package ${name}`);
const normalApp = 'com.deepstudent.app';
const normalPackageState = async () => await absent(normalApp) ? null : shell('pm', 'path', '--user', '0', normalApp);
const primaryPaths = await normalPackageState();

await mkdir(out, { mode: 0o700 });
const scratch = await mkdtemp(join(tmpdir(), 'dstu-remediation-'));
const startedAt = new Date().toISOString();
const summary = { model, api: Number(api), abi, startedAt, desktopCommit: DESKTOP_COMMIT, apkSetSha256,
  sha256ByFile: hashes, stages: {} };
const owned = new Map();
let tunnel;
let failure;
const cleanupFailures = [];

async function fixtureFile(file, name = file.split('/').at(-1)) {
  assert.match(name, /^[A-Za-z0-9_.-]+$/u);
  const input = await readFile(file);
  // ADB only streams generated inputs into the isolated debug app's private cache.
  await new Promise((success, failure) => {
    const copy = execFile(adbPath, ['-s', args.serial, 'shell', '-T', 'run-as', app, 'sh', '-c', `'cat > ${cache}/${name}'`],
      { timeout: 45_000 }, error => error ? failure(error) : success());
    copy.stdin.on('error', failure);
    copy.stdin.end(input);
  });
  const expected = sha(input);
  assert.equal((await shell('run-as', app, 'sha256sum', `${cache}/${name}`)).split(/\s+/u)[0], expected);
  return expected;
}

function desktopMarker(mode) {
  return execFileSync(process.execPath, ['--disable-warning=ExperimentalWarning', '--experimental-strip-types',
    join(root, 'tools/s2/s2-18-desktop-marker.mjs'), mode, scratch], { encoding: 'utf8', timeout: 120_000 });
}

async function instrument(label, selection, expected, parameters = {}) {
  await soleDevice();
  const forwarded = Object.entries(parameters).flatMap(([key, value]) => {
    assert.match(key, /^[A-Za-z0-9_]+$/u);
    assert.match(value, /^[A-Za-z0-9_./:+-]+$/u);
    return ['-e', key, value];
  });
  let output;
  try {
    output = await adb(['shell', '-n', 'am', 'instrument', '-w', '-r', '--user', '0', '-e', 'class', selection,
      ...forwarded, `${test}/androidx.test.runner.AndroidJUnitRunner`], { timeout: 240_000 });
  } catch (error) {
    await writeFile(join(out, `${label}.log`), `${error.stdout ?? ''}\n${error.stderr ?? ''}`, { mode: 0o600 });
    throw new Error(`${label} instrumentation did not complete`);
  }
  await writeFile(join(out, `${label}.log`), output, { mode: 0o600 });
  const results = parseInstrumentResults(output, selection, expected);
  console.log(JSON.stringify({ event: 's2.device.instrumentation', model, label, passed: expected, skipped: 0 }));
  return results;
}

async function captureTrace(uid, since) {
  assert.match(uid, /^[0-9]+$/u);
  assert.match(since, /^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.000$/u);
  const raw = await shell(`logcat -d --uid=${uid} -b main -v tag -T '${since}' -s DstuBackupRestore DstuBackupCrypto DstuRestoreIdentity DstuOperation '*:S'`);
  const { lines, ...audit } = inspectRemediationTrace(raw);
  await writeFile(join(out, 'trace.log'), `${lines.join('\n')}\n`, { mode: 0o600 });
  if (!args['require-publication-trace']) return audit;
  const { lines: publicationLines, ...publication } = inspectPublicationTrace(raw);
  await writeFile(join(out, 'publication-trace.log'), `${publicationLines.join('\n')}\n`, { mode: 0o600 });
  return { ...audit, publication };
}

try {
  for (const [index, name] of names.entries()) {
    const pkg = [app, test][index];
    assert.ok(await absent(pkg));
    assert.match(await adb(['install', '--no-streaming', '--user', '0', '-t', join(candidate, name)], { timeout: 120_000 }), /\bSuccess\b/u);
    owned.set(pkg, hashes[name]);
    assert.equal(await installedSha(pkg), hashes[name]);
  }
  await shell('run-as', app, 'mkdir', '-p', cache);
  const listed = await shell('pm', 'list', 'packages', '-U', '--user', '0', app);
  const uid = listed.split('\n').find(line => line.startsWith(`package:${app} uid:`))?.split('uid:')[1];
  assert.ok(uid, 'cannot identify the isolated application UID');
  const since = await shell("date '+%m-%d %H:%M:%S.000'");
  summary.stages['S2.16'] = await verifyRestoreRoundtrip(async ({ root, parameters, port }) => {
    const inputs = {};
    for (const name of ['frozen-desktop-vfs.db', 'fixture-blob']) inputs[name] = await fixtureFile(join(root, name));
    summary.s216Inputs = inputs;
    const binding = await bindDisposableReverse(adb, port);
    tunnel = binding.tunnel;
    const deviceParameters = { ...parameters, ENDPOINT: `http://127.0.0.1:${binding.devicePort}/dav/` };
    summary.s216Cases = await instrument('s216', 'com.deepstudent.app.backup.RestoreBaselineDeviceTest', 3,
      Object.fromEntries(Object.entries(deviceParameters).map(([key, value]) => [`s216_${key}`, value])));
    await adb(['reverse', '--remove', tunnel]);
    tunnel = undefined;
  }, 'physical-android');
  console.log(JSON.stringify(summary.stages['S2.16']));

  const orphan = join(scratch, 'desktop-orphan.zip');
  const oracle = await makeOrphanFixture(orphan, 'public-s217-fixture');
  assert.equal(await fixtureFile(orphan), oracle.sha256);
  summary.stages['S2.17'] = { oracle, cases: await instrument('s217', 'com.deepstudent.app.backup.RestoreBlobReferencesDeviceTest', 6) };

  desktopMarker('fixtures');
  const markerInputs = {};
  for (const name of ['desktop-marker-v1.json', 'backup-v2.dsbk']) markerInputs[name] = await fixtureFile(join(scratch, name));
  const markerCases = await instrument('s218',
    'com.deepstudent.app.sync.DesktopLegacyMarkerDeviceTest,com.deepstudent.app.sync.LegacyMarkerWebDavDeviceTest', 10);
  const deviceMarker = await adb(['exec-out', 'run-as', app, 'cat', `${cache}/android-marker-v2.json`]);
  await writeFile(join(scratch, 'android-marker-v2.json'), deviceMarker, { flag: 'wx', mode: 0o600 });
  console.log(desktopMarker('verify').trim());
  summary.stages['S2.18'] = { markerInputs, cases: markerCases, desktopVerifiedMarker: true, outputMarkerSha256: sha(deviceMarker) };
  summary.traceAudit = await captureTrace(uid, since);
} catch (error) {
  failure = error;
} finally {
  if (tunnel) try { await adb(['reverse', '--remove', tunnel]); } catch { cleanupFailures.push('reverse'); }
  for (const [pkg, expected] of [...owned].reverse()) {
    try {
      if (!await absent(pkg)) {
        // Never delete a package whose APK no longer matches this run's install.
        assert.equal(await installedSha(pkg), expected);
        assert.match(await adb(['uninstall', pkg]), /\bSuccess\b/u);
        assert.ok(await absent(pkg));
      }
    } catch { cleanupFailures.push(pkg); }
  }
  try {
    assert.equal(await normalPackageState(), primaryPaths);
    summary.normalAppPackageUnchanged = true;
  } catch { cleanupFailures.push('normal-app-check'); }
  await rm(scratch, { recursive: true, force: true });
  summary.cleanupFailures = cleanupFailures;
  summary.finishedAt = new Date().toISOString();
  summary.passed = !failure && cleanupFailures.length === 0;
  await writeFile(join(out, 'receipt.json'), `${JSON.stringify(summary, null, 2)}\n`, { mode: 0o600 });
}
if (failure) throw failure;
assert.deepEqual(cleanupFailures, [], 'isolated fixture cleanup incomplete');
