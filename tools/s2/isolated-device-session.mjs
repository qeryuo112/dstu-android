// Physical-device tests may install only the sealed debug app and its instrumented test APK.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { parseArgs, promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';
import { parseInstrumentResults } from './s2-16-18-results.mjs';

const run = promisify(execFile);
const sdk = process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk');
const adbPath = join(sdk, 'platform-tools/adb');
const buildTools = join(sdk, 'build-tools/36.0.0');
export const appId = 'com.deepstudent.app.s2device';
const testId = `${appId}.test`;
const normalApp = 'com.deepstudent.app';
const cache = 'cache/s2-remediation';
const installers = new Set(['com.android.packageinstaller', 'com.vivo.permissionmanager', 'com.iqoo.secure']);
const buttons = new Set(['安装', '确认安装', '继续安装', 'Install', 'Continue installation']);
const deviceModels = new Set(['iPA2475', 'V2232A']);
const controls = /[\r\n\x00]/u;
export const sha256 = data => createHash('sha256').update(data).digest('hex');

export function isolatedArguments(argv) {
  const options = Object.fromEntries(['serial', 'model', 'candidate', 'output'].map(key => [key, { type: 'string' }]));
  const { values } = parseArgs({ args: argv, options });
  assert.ok(Object.keys(options).every(key => values[key]), 'expected --serial SERIAL --model MODEL --candidate DIR --output NEW_PRIVATE_DIR');
  assert.match(values.serial, /^[A-Za-z0-9_.:-]+$/u);
  assert.ok(deviceModels.has(values.model), 'unknown acceptance device model');
  return values;
}

async function checkCandidate(candidate) {
  const root = await realpath(candidate);
  const files = ['app-debug.apk', 'app-debug-androidTest.apk'];
  const entries = [];
  for (const [index, packageId] of [appId, testId].entries()) {
    const name = files[index];
    const path = join(root, name);
    const { stdout } = await run(join(buildTools, 'aapt'), ['dump', 'badging', path]);
    assert.equal(stdout.match(/^package: name='([^']+)'/mu)?.[1], packageId);
    if (index === 0) assert.match(stdout, /^native-code: 'arm64-v8a'\s*$/mu);
    else {
      const { stdout: manifest } = await run(join(buildTools, 'aapt'), ['dump', 'xmltree', path, 'AndroidManifest.xml']);
      assert.ok(manifest.includes(`android:targetPackage(0x01010021)="${appId}"`), 'unexpected instrumentation target');
    }
    const { stdout: signing } = await run(join(buildTools, 'apksigner'), ['verify', '--verbose', '--print-certs', path]);
    assert.match(signing, /^Verified using v2 scheme .*: true$/mu);
    const signature = signing.match(/^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$/mu)?.[1];
    assert.ok(signature);
    await run(join(buildTools, 'zipalign'), ['-c', '-P', '16', '4', path]);
    entries.push({ packageId, name, path, sha256: sha256(await readFile(path)), signature,
      labels: [...stdout.matchAll(/^application-label(?:-[^:]+)?:'([^']+)'$/gmu)].map(match => match[1]) });
  }
  assert.equal(entries[0].signature, entries[1].signature);
  const sums = entries.map(entry => `${entry.sha256}  ${entry.name}\n`).join('');
  assert.equal(await readFile(join(root, 'SHA256SUMS'), 'utf8'), sums);
  const revision = (await readFile(join(root, 'SOURCE_REVISION'), 'utf8')).trim();
  assert.match(revision, /^[0-9a-f]{40}$/u);
  return { entries, revision, apkSetSha256: sha256(sums) };
}

// Only tap a uniquely identified install action in the focused system installer, naming the APK
// whose ADB install is currently pending. Unknown, generic permission/credential or locked screens are not touched.
export function candidateInstallAction(xml, expected, focusPackage) {
  if (!installers.has(focusPackage)) return null;
  const opened = [];
  const nodes = [];
  for (const token of xml.matchAll(/<node\b([^<>]+)>|<\/node>/gu)) {
    if (token[1] === undefined) {
      opened.pop();
      continue;
    }
    const attributes = Object.fromEntries([...token[1].matchAll(/([a-z-]+)="([^"]*)"/gu)]
      .map(([, key, value]) => [key, value]));
    const node = { attributes, parent: opened.at(-1) };
    nodes.push(node);
    if (!token[1].endsWith('/')) opened.push(node);
  }
  const own = nodes.filter(node => node.attributes.package === focusPackage);
  const identity = [expected.packageId, ...expected.labels].filter(name => name && !/[<>&"']/u.test(name));
  const namesCandidate = own.some(({ attributes: node }) => identity.some(name =>
    node.text === name || node['content-desc'] === name ||
    [node.text, node['content-desc']].some(field => field && [`“${name}”`, `「${name}」`, `&quot;${name}&quot;`, `《${name}》`]
      .some(quoted => field.includes(quoted)))));
  if (!namesCandidate) return null;
  const actions = new Map();
  for (const node of own.filter(item => item.attributes.enabled === 'true' && buttons.has(item.attributes.text))) {
    if (node.attributes.clickable === 'true') {
      actions.set(node, node.attributes.text);
      continue;
    }
    // Vivo renders its title as a separate, non-clickable child of the system's positive button.
    // Never use a nearby text match: require the actual dialog button ancestor and APK title.
    if (focusPackage !== 'com.android.packageinstaller' ||
      node.attributes['resource-id'] !== `${focusPackage}:id/vbutton_title` ||
      !own.some(({ attributes }) => attributes['resource-id'] === `${focusPackage}:id/tv_app_name` && identity.includes(attributes.text))) continue;
    for (let parent = node.parent; parent?.attributes.package === focusPackage; parent = parent.parent) {
      const attributes = parent.attributes;
      if (attributes['resource-id'] !== 'android:id/button1') continue;
      if (attributes.enabled === 'true' && attributes.clickable === 'true') actions.set(parent, node.attributes.text);
      break;
    }
  }
  const candidates = [...actions.keys()];
  if (candidates.length !== 1) return null;
  const match = candidates[0].attributes.bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/u);
  if (!match) return null;
  const [l, t, r, b] = match.slice(1).map(Number);
  if (l >= r || t >= b || r > 5000 || b > 5000) return null;
  return { x: Math.floor((l + r) / 2), y: Math.floor((t + b) / 2), button: actions.get(candidates[0]), installer: focusPackage };
}

function deviceFor(serial) {
  const adb = async (args, options = {}) => (await run(adbPath, ['-s', serial, ...args], {
    timeout: 35_000, maxBuffer: 4 * 1024 * 1024, ...options,
  })).stdout;
  const shell = async (...args) => (await adb(['shell', '-n', ...args])).trim().replaceAll('\r', '');
  const absent = async name => (await shell('dumpsys', 'package', name)) === `Unable to find package: ${name}`;
  const installedSha = async name => {
    const value = await shell('pm', 'path', '--user', '0', name);
    assert.match(value, /^package:\/data\/app\/[A-Za-z0-9_+=.,~/-]+\/base\.apk$/u, 'expected one installed base APK');
    const digest = (await shell('sha256sum', value.slice('package:'.length))).split(/\s+/u)[0];
    assert.match(digest, /^[0-9a-f]{64}$/u);
    return digest;
  };
  return { adb, shell, absent, installedSha };
}

async function install(device, entry) {
  const { adb, shell, installedSha } = device;
  const actions = [];
  const state = { pending: true };
  const work = adb(['install', '--no-streaming', '--user', '0', '-t', entry.path], { timeout: 110_000 })
    .then(result => ({ result }), error => ({ error })).finally(() => { state.pending = false; });
  const xmlPath = `/data/local/tmp/dstu-install-${randomUUID()}.xml`;
  const focus = async () => (await shell('dumpsys window | grep mCurrentFocus')).match(/mCurrentFocus=Window\{([a-z0-9]+) u0 ([a-zA-Z0-9_.]+)\/([a-zA-Z0-9_.$]+) /u);
  const deadline = Date.now() + 100_000;
  let inspected = false;
  try {
    while (state.pending && actions.length === 0 && Date.now() < deadline) {
      await Promise.race([delay(1000), work]);
      if (!state.pending) break;
      const window = await focus().catch(() => null);
      const owner = window?.[2];
      if (!installers.has(owner)) continue;
      if ((await shell('pm', 'list', 'packages', '-s', '--user', '0', owner)).trim() !== `package:${owner}`) continue;
      inspected = true;
      const xml = await adb(['shell', '-n', 'uiautomator', 'dump', xmlPath], { timeout: 9_000 })
        .then(() => shell('cat', xmlPath)).catch(() => '');
      const action = candidateInstallAction(xml, entry, owner);
      if (action && state.pending && (await focus().catch(() => null))?.[0] === window[0]) {
        await shell('input', 'tap', String(action.x), String(action.y));
        actions.push({ button: action.button, installer: owner });
      }
    }
  } finally {
    if (inspected) await shell('rm', '-f', xmlPath);
  }
  const { result, error } = await work;
  if (!error && /^Success\s*$/mu.test(result)) {
    assert.equal(await installedSha(entry.packageId), entry.sha256);
    return { ...entryReceipt(entry), actions, mode: 'adb-confirmed' };
  }
  // Some OEM installers finish the same request on-device after the host wait times out.
  const provenance = await shell('dumpsys', 'package', entry.packageId);
  const matching = /initiatingPackageName=com\.android\.shell\b/u.test(provenance) &&
    await installedSha(entry.packageId).catch(() => '') === entry.sha256;
  if (matching) return { ...entryReceipt(entry), actions, mode: 'same-session-package-manager-readback' };
  throw new Error(`Installation did not complete for ${entry.packageId}; no second install attempted`);
}

const entryReceipt = ({ packageId, name, sha256: digest }) => ({ packageId, name, sha256: digest });
async function copyFixture(device, path) {
  const name = path.split('/').at(-1);
  assert.match(name, /^[A-Za-z0-9_.-]+$/u);
  assert.ok(!controls.test(path));
  const bytes = await readFile(path);
  const temporary = `/data/local/tmp/dstu-fixture-${randomUUID()}`;
  try {
    await device.adb(['push', path, temporary]);
    await device.shell(`run-as ${appId} sh -c 'cat ${temporary} > ${cache}/${name}'`);
    const staged = await device.shell('run-as', appId, 'sha256sum', `${cache}/${name}`);
    const hash = sha256(bytes);
    assert.equal(staged.split(/\s+/u)[0], hash, 'device fixture readback mismatch');
    return hash;
  } finally {
    await device.shell('rm', '-f', temporary);
  }
}

export async function withIsolatedDevice(args, verify) {
  const safeParent = await realpath(dirname(resolve(args.output)));
  const inGit = await run('git', ['-C', safeParent, 'rev-parse', '--is-inside-work-tree'])
    .then(() => true, error => {
      if (!String(error.stderr ?? '').includes('not a git repository')) throw error;
      return false;
    });
  assert.ok(!inGit, 'raw device evidence belongs outside any Git checkout');
  const candidate = await checkCandidate(args.candidate);
  const device = deviceFor(args.serial);
  const { adb, shell, absent, installedSha } = device;
  const listed = (await adb(['devices', '-l'])).split('\n').find(line => line.split(/\s+/u)[0] === args.serial);
  assert.ok(listed);
  const [, state, transport] = listed.trim().split(/\s+/u);
  assert.equal(state, 'device');
  assert.match(transport, /^usb:/u);
  const model = await shell('getprop', 'ro.product.model');
  const api = await shell('getprop', 'ro.build.version.sdk');
  const abi = await shell('getprop', 'ro.product.cpu.abi');
  const adbUid = await shell('id', '-u');
  assert.equal(model, args.model);
  assert.equal(abi, 'arm64-v8a');
  assert.equal(await shell('am', 'get-current-user'), '0');
  for (const { packageId } of candidate.entries) assert.ok(await absent(packageId), `refusing to replace pre-existing ${packageId}`);
  const original = async () => (await shell('pm', 'list', 'packages', '-U', '--user', '0', normalApp))
    .split('\n').filter(line => line.startsWith(`package:${normalApp} `)).join('\n');
  const originalState = await original();
  const reverseBefore = await adb(['reverse', '--list']);
  const out = resolve(args.output);
  await mkdir(out, { mode: 0o700 });
  const receipt = { model, api, abi, adbUid, sourceCommit: candidate.revision, apkSetSha256: candidate.apkSetSha256,
    installed: [], cases: [], cleanupFailures: [], startedAt: new Date().toISOString() };
  const attempted = [];
  let stage = 'install';
  try {
    for (const entry of candidate.entries) {
      attempted.push(entry);
      receipt.installed.push(await install(device, entry));
    }
    stage = 'verify';
    await shell('run-as', appId, 'mkdir', '-p', cache);
    const appLine = (await shell('pm', 'list', 'packages', '-U', '--user', '0', appId)).split('\n')
      .find(line => line.startsWith(`package:${appId} uid:`));
    const uid = appLine?.split('uid:')[1];
    assert.match(uid ?? '', /^[0-9]+$/u);
    const since = await shell("date '+%m-%d %H:%M:%S.000'");
    const instrument = async (label, selection, extras = {}) => {
      assert.match(label, /^[A-Za-z0-9_-]+$/u);
      assert.match(selection, /^[A-Za-z0-9_.$]+#[A-Za-z0-9_$]+$/u, 'run one exact case per instrument session');
      const argv = Object.entries(extras).flatMap(([key, value]) => {
        assert.match(key, /^[A-Za-z0-9_]+$/u);
        assert.match(value, /^[A-Za-z0-9_.:/-]+$/u, 'fixtures must be synthetic shell-safe tokens');
        return ['-e', key, value];
      });
      const output = await adb(['shell', '-n', 'am', 'instrument', '--user', '0', '-w', '-r', '-e', 'class', selection,
        ...argv, `${testId}/androidx.test.runner.AndroidJUnitRunner`], { timeout: 240_000 }).catch(async error => {
        await writeFile(join(out, `${label}.log`), `${error.stdout ?? ''}\n${error.stderr ?? ''}`, { flag: 'wx', mode: 0o600 });
        throw new Error(`${label} instrument invocation failed`);
      });
      await writeFile(join(out, `${label}.log`), output, { flag: 'wx', mode: 0o600 });
      const cases = parseInstrumentResults(output, selection, 1);
      receipt.cases.push(...cases);
      return cases;
    };
    receipt.verification = await verify({ ...device, uid, since, out, instrument, copyFixture: path => copyFixture(device, path) });
    for (const entry of candidate.entries) assert.equal(await installedSha(entry.packageId), entry.sha256);
    stage = 'done';
  } finally {
    for (const entry of attempted.reverse()) {
      try {
        if (await absent(entry.packageId)) continue;
        assert.equal(await installedSha(entry.packageId), entry.sha256);
        assert.match(await adb(['uninstall', '--user', '0', entry.packageId]), /^Success\s*$/mu);
        assert.ok(await absent(entry.packageId));
      } catch { receipt.cleanupFailures.push(entry.packageId); }
    }
    try {
      assert.equal(await original(), originalState);
      assert.equal(await adb(['reverse', '--list']), reverseBefore);
      receipt.normalAppAndReverseUnchanged = true;
    } catch { receipt.cleanupFailures.push('normal-app-or-reverse-state'); }
    receipt.passed = stage === 'done' && receipt.cleanupFailures.length === 0;
    receipt.stage = stage;
    receipt.finishedAt = new Date().toISOString();
    await writeFile(join(out, 'receipt.json'), `${JSON.stringify(receipt, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
  }
  assert.deepEqual(receipt.cleanupFailures, [], 'isolated test cleanup incomplete');
  return receipt;
}
