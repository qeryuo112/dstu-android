import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readFile, realpath } from 'node:fs/promises';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bindDisposableReverse, classifyAdbFailure } from '../s1/benchmark-device-lib.mjs';
import { startSafObjectFixture } from './saf-object-fixture.mjs';

/** The pipe has to hit a host object service; a local byte stream cannot satisfy this gate. */
export function requireRemoteObjectOperations(events) {
  const requests = {};
  for (const { phase, state, status } of events) {
    const key = `${phase}:${status ?? 'none'}:${state}`;
    requests[key] = (requests[key] ?? 0) + 1;
  }
  for (const key of ['PUT:201:responded', 'HEAD:200:responded', 'GET:200:responded', 'DELETE:204:responded']) {
    assert.ok(requests[key] > 0, `real HTTP fixture did not observe ${key}`);
  }
  return requests;
}

async function sha(path) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest('hex');
}

async function pinCandidate(directory, expectedSetSha, expectedCasesSha) {
  const apkPaths = ['app-debug.apk', 'app-debug-androidTest.apk', 'resources-debug-androidTest.apk'];
  const [app, appTest, resourcesTest] = await Promise.all(apkPaths.map(name => sha(resolve(directory, name))));
  const hashes = `app=${app}\nappTest=${appTest}\nresourcesTest=${resourcesTest}\n`;
  assert.equal(createHash('sha256').update(hashes).digest('hex'), expectedSetSha, 'candidate digest mismatch');
  const cases = await readFile(resolve(directory, 'cases.tsv'));
  assert.equal(createHash('sha256').update(cases).digest('hex'), expectedCasesSha, 'case list digest mismatch');
  assert.ok(cases.length > 0 && cases.at(-1) === 10, 'case list must be non-empty and end in a newline');
  return cases;
}

function adbRunner(adb, serial) {
  return args => {
    try {
      return execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 12_000,
        stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch (failure) {
      throw Object.assign(new Error('ADB fixture operation failed'), {
        s127ChildFailure: classifyAdbFailure(failure, { reverse: args[0] === 'reverse' }),
      });
    }
  };
}

async function runResourceCases(options) {
  const runner = fileURLToPath(new URL('verify-resource-device.sh', import.meta.url));
  const child = spawn('bash', [runner, options.serial, options.model, 'S2.07', options.directory, options.digest], {
    stdio: ['pipe', 'inherit', 'inherit'],
    env: { ...process.env, DSTU_S207_DEVICE_PORT: String(options.port) },
  });
  child.stdin.on('error', error => { if (error.code !== 'EPIPE') child.kill('SIGTERM'); });
  child.stdin.end(options.cases);
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  assert.equal(code, 0, 'device runner failed');
}

async function main(args) {
  const [serial, model, artifactPath, digest, casesSha] = args;
  assert.ok(args.length === 5 && /^[a-zA-Z0-9._:-]+$/u.test(serial) && ['iPA2475', 'V2232A'].includes(model) &&
    [digest, casesSha].every(value => /^[0-9a-f]{64}$/u.test(value ?? '')),
  'usage: node tools/s2/verify-saf-device.mjs SERIAL MODEL CANDIDATE_DIRECTORY APK_SET_SHA256 CASES_SHA256');
  const directory = await realpath(artifactPath);
  const cases = await pinCandidate(directory, digest, casesSha);
  const adb = resolve(process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb');
  const device = adbRunner(adb, serial);
  const lines = device(['devices']).split(/\r?\n/u).slice(1).map(line => line.trim()).filter(Boolean);
  assert.deepEqual(lines.map(line => line.replace(/\s+/gu, ' ')), [`${serial} device`], 'exactly the selected device must be connected');
  assert.equal(device(['shell', 'getprop', 'ro.product.model']), model, 'device model mismatch');
  assert.equal(device(['shell', 'getprop', 'ro.product.cpu.abi']), 'arm64-v8a', 'device ABI mismatch');
  const events = [];
  const fixture = await startSafObjectFixture({ log: event => events.push(event) });
  let binding;
  try {
    binding = await bindDisposableReverse(device, fixture.port);
    console.log(JSON.stringify({ event: 's2.07.device-fixture.ready', model, candidateSha256: digest, casesSha256: casesSha }));
    await runResourceCases({ serial, model, directory, digest, cases, port: binding.devicePort });
    const requests = requireRemoteObjectOperations(events);
    console.log(JSON.stringify({ event: 's2.07.device-fixture.verified', requests, model, candidateSha256: digest }));
  } finally {
    try { if (binding) device(['reverse', '--remove', binding.tunnel]); }
    finally { await fixture.close(); }
  }
  console.log(JSON.stringify({ event: 's2.07.device-fixture.cleaned', model }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main(process.argv.slice(2));
}
