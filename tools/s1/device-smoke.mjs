import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';

const adb = resolve(process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb');
const shell = (...args) => execFileSync(adb, ['shell', ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const activity = 'com.deepstudent.app/.MainActivity';
const policy = shell('dumpsys', 'window', 'policy');
if (/showing=true/u.test(policy)) {
  assert.ok(/secure=false/u.test(policy), 'Device is securely locked; unlock it before visual testing');
  shell('input', 'keyevent', 'KEYCODE_WAKEUP');
  shell('wm', 'dismiss-keyguard');
}
const start = shell('am', 'start', '-W', '-n', activity);
assert.match(start, /Status: ok/u);
const windows = shell('dumpsys', 'activity', 'activities');
assert.ok(/(?:ResumedActivity|topResumedActivity)[^\n]*com\.deepstudent\.app/u.test(windows), 'Application is not resumed; no screenshot was captured');
const output = process.argv[2] ?? 'logs/s1-01-bootstrap-device.png';
assert.match(output, /^logs\/s1-[a-z0-9-]+\.png$/u);
const screenshot = execFileSync(adb, ['exec-out', 'screencap', '-p'], { maxBuffer: 32 * 1024 * 1024 });
assert.equal(screenshot.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
mkdirSync('logs', { recursive: true });
writeFileSync(output, screenshot, { flag: 'wx' });
console.log(JSON.stringify({ time: new Date().toISOString(), event: 's1.device.smoke', owner: 'S1.01',
  api: shell('getprop', 'ro.build.version.sdk').trim(), abi: shell('getprop', 'ro.product.cpu.abi').trim(),
  model: shell('getprop', 'ro.product.model').trim(), pageSize: shell('getconf', 'PAGE_SIZE').trim(),
  activity, resumed: true, screenshot: output, screenshotBytes: screenshot.length, start: start.trim(),
}));
