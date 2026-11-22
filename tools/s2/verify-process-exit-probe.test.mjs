// Negative controls: the deliberately destructive stimulus must never target an ordinary app or an existing test install.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const script = join(import.meta.dirname, 'verify-process-exit-probe.sh');
const testPackage = 'com.deepstudent.platform.database.test';
const fakeAdb = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.CALLS, JSON.stringify(args) + '\n');
if (args[0] === 'devices') {
  console.log('List of devices attached\nserial-1\tdevice' + (process.env.TWO ? '\nserial-2\tdevice' : ''));
} else if (args[1] === 'getprop') {
  console.log(args[2] === 'ro.product.model' ? 'fake-arm64' : '36');
} else if (args[1] === 'am' && args[2] === 'get-current-user') {
  console.log('0');
} else if (args[1] === 'dumpsys' && args[2] === 'package') {
  console.log('Package [' + args[3] + ']');
} else {
  process.exit(42);
}
`;

function verifyRefusal({ pkg, two, expectedCalls }) {
  const dir = mkdtempSync(join(tmpdir(), 'dstu-exit-probe-fake-'));
  try {
    const sdk = join(dir, 'sdk');
    const calls = join(dir, 'calls');
    const apk = join(dir, 'test.apk');
    mkdirSync(join(sdk, 'platform-tools'), { recursive: true });
    mkdirSync(join(sdk, 'build-tools/36.0.0'), { recursive: true });
    writeFileSync(join(sdk, 'platform-tools/adb'), fakeAdb, { mode: 0o700 });
    writeFileSync(join(sdk, 'build-tools/36.0.0/aapt2'), `#!/bin/bash\nprintf "package: name='%s'\\n" "$PKG"\n`, { mode: 0o700 });
    writeFileSync(apk, 'fake APK');
    const result = spawnSync('bash', [script, 'serial-1', 'fake-arm64', apk], {
      env: { ...process.env, ANDROID_HOME: sdk, PKG: pkg, CALLS: calls, TWO: two ? 'true' : '' }, encoding: 'utf8',
    });
    assert.ok(result.status === 1 || result.status === 2, result.stdout + result.stderr);
    const invoked = existsSync(calls) ? readFileSync(calls, 'utf8').trim().split('\n').map(JSON.parse) : [];
    assert.deepEqual(invoked, expectedCalls);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('rejects an ordinary app before touching ADB', () => {
  verifyRefusal({ pkg: 'com.deepstudent.app', expectedCalls: [] });
});

test('rejects multiple devices before any package operation', () => {
  verifyRefusal({ pkg: testPackage, two: true, expectedCalls: [['devices']] });
});

test('will not replace or force-stop a pre-existing test package', () => {
  verifyRefusal({ pkg: testPackage, expectedCalls: [
    ['devices'], ['shell', 'getprop', 'ro.product.model'], ['shell', 'am', 'get-current-user'],
    ['shell', 'getprop', 'ro.build.version.sdk'], ['shell', 'dumpsys', 'package', testPackage],
  ] });
});
