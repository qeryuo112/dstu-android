import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startWebDavFixture } from './sync-webdav-fixture.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const desktop = resolve(root, '../dstu-node');
const commit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const sources = ['host/plugins/sync-providers/src/webdav.ts', 'host/plugins/sync/src/remote-format.ts'];
assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: desktop, encoding: 'utf8' }).trim(), commit);
for (const file of sources) assert.deepEqual(readFileSync(resolve(desktop, file)), execFileSync('git', ['show', `${commit}:${file}`], { cwd: desktop }));
const { WebDavStorage } = await import(new URL('../../../dstu-node/host/plugins/sync-providers/src/webdav.ts', import.meta.url));
const { parseFormatDescriptor } = await import(new URL('../../../dstu-node/host/plugins/sync/src/remote-format.ts', import.meta.url));
const adb = resolve(process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb');
const apk = resolve(root, 'services/sync/data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk');
assert.ok(existsSync(apk), 'build sync data AndroidTest APK first');
const devices = execFileSync(adb, ['devices'], { encoding: 'utf8' }).split('\n').filter(line => /\tdevice$/u.test(line));
assert.equal(devices.length, 1, 'exactly one authorized Android device is required');
const run = async args => {
  const child = spawn(adb, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { output += bytes; process.stdout.write(bytes); });
  const timer = setTimeout(() => child.kill('SIGTERM'), 180_000);
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  clearTimeout(timer);
  assert.equal(code, 0, 'adb conformance command failed');
  return output;
};
const fixture = await startWebDavFixture();
const provider = storage('shared-provider');
function storage(path) {
  return new WebDavStorage({ endpoint: fixture.endpoint, username: fixture.username, password: fixture.password }, path,
    { rateLimitEnv: '0', requestWindowKey: `device-fixture-${fixture.port}-${path}` });
}
try {
  await provider.put('from-desktop/中文 %.bin', Buffer.from('desktop-public\r\n中文😀\u0000'));
  await run(['reverse', `tcp:${fixture.port}`, `tcp:${fixture.port}`]);
  await run(['install', '-r', apk]);
  const result = await run(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
    'com.deepstudent.services.sync.data.WebDavDesktopConformanceTest', '-e', 'webdavEndpoint', fixture.endpoint,
    'com.deepstudent.services.sync.data.test/androidx.test.runner.AndroidJUnitRunner']);
  assert.ok(!/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=/u.test(result));
  assert.match(result, /OK \(1 test\)/u);
  assert.match(result, /INSTRUMENTATION_CODE: -1/u);
  const androidBytes = Buffer.from('android-public\r\n中文😀\u0000');
  assert.deepEqual(Buffer.from(await provider.get('from-android/中文 %.bin')), androidBytes);
  const list = await provider.listOutcome('from-android');
  assert.equal(list.truncated, false);
  assert.ok(list.files.some(file => file.key === 'from-android/中文 %.bin'));
  const created = storage('created-on-android');
  const formatBytes = await created.get('data_governance/format.json');
  const descriptor = parseFormatDescriptor(Buffer.from(formatBytes).toString('utf8'));
  assert.equal(descriptor.format_version, 4);
  assert.equal(descriptor.min_reader_version, 3);
  const instance = JSON.parse(Buffer.from(await created.get('data_governance/instance.json')).toString('utf8'));
  assert.match(instance.instance_id, /^[a-f0-9-]{36}$/u);
  await provider.delete('from-android/中文 %.bin');
  assert.equal(await provider.get('from-android/中文 %.bin'), null);
  console.log(JSON.stringify({ event: 's1.sync.provider.device-desktop-verified', desktopCommit: commit, tests: 1,
    operations: ['put', 'get', 'list', 'delete', 'create', 'join', 'keystore-reopen', 'secret-scan'],
    androidObjectSha256: createHash('sha256').update(androidBytes).digest('hex'), requests: fixture.counts() }));
} finally {
  execFileSync(adb, ['reverse', '--remove', `tcp:${fixture.port}`], { stdio: 'inherit' });
  await fixture.close();
}
