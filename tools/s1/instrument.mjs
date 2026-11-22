import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { instrumentConfig } from './instrument-config.mjs';

const { module, target, minimum, testClass, testApk } = instrumentConfig(process.argv.slice(2));
assert.ok(existsSync(resolve(module, 'build.gradle.kts')), 'Instrumentation module does not exist');
const adb = resolve(process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb');
const run = async (args, timeoutMs = 120_000) => {
  const child = spawn(adb, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  const timer = setTimeout(() => child.kill('SIGTERM'), timeoutMs);
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => {
    output += bytes;
    process.stdout.write(bytes);
  });
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  clearTimeout(timer);
  assert.equal(code, 0, `adb failed: ${args.join(' ')}`);
  return output;
};
if (module === 'app') await run(['install', '-r', 'app/build/outputs/apk/debug/app-debug.apk']);
assert.ok(existsSync(testApk), 'Build the instrumentation APK before running device tests');
await run(['install', '-r', testApk]);
const policy = execFileSync(adb, ['shell', 'dumpsys', 'window', 'policy'], { encoding: 'utf8' });
if (/showing=true/u.test(policy)) {
  assert.ok(/secure=false/u.test(policy), 'Unlock the device before running interactive instrumented tests');
  await run(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']);
  await run(['shell', 'wm', 'dismiss-keyguard']);
}
const selection = testClass ? ['-e', 'class', testClass] : [];
const result = await run(['shell', 'am', 'instrument', '-w', '-r', ...selection,
  `${target}/androidx.test.runner.AndroidJUnitRunner`], 240_000);
assert.ok(!/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_CODE: 0/u.test(result), 'Instrumentation failed');
const count = Number(result.match(/OK \((\d+) tests?\)/u)?.[1]);
const skipped = (result.match(/INSTRUMENTATION_STATUS_CODE: -4\b/gu) ?? []).length;
const passed = count - skipped;
assert.ok(passed >= minimum, `Expected >=${minimum} passed tests, got ${passed} (${skipped} skipped)`);
assert.match(result, /INSTRUMENTATION_CODE: -1/u);
console.log(JSON.stringify({ time: new Date().toISOString(), event: 's1.instrumentation.verified', module, target,
  tests: count, passed, skipped, transport: 'SDK adb AndroidJUnitRunner' }));
