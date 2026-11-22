import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { readJson, log } from './lib.mjs';

const input = readJson('config/measurement-inputs.json');
const adb = input.inventory.adb;
const devices = execFileSync(adb, ['devices', '-l'], { encoding: 'utf8' }).split('\n')
  .filter(line => /\sdevice\s/.test(line));
assert.equal(devices.length, 1, 'exactly one authorized device is required; never select an arbitrary device');
const transport = devices[0].match(/transport_id:(\d+)/)[1];
const shell = (...args) => execFileSync(adb, ['-t', transport, 'shell', ...args], { encoding: 'utf8', timeout: 10000 }).trim();
const memory = shell('cat', '/proc/meminfo').match(/^MemTotal:\s+(\d+) kB/m);
const pixels = shell('wm', 'size').match(/Physical size: (\d+)x(\d+)/);
const density = Number(shell('wm', 'density').match(/Physical density: (\d+)/)[1]);
const hasPackage = name => spawnSync(adb, ['-t', transport, 'shell', 'pm', 'path', name], { encoding: 'utf8', timeout: 10000 }).stdout.startsWith('package:');
const result = {
  model: shell('getprop', 'ro.product.model'), api: Number(shell('getprop', 'ro.build.version.sdk')),
  abi: shell('getprop', 'ro.product.cpu.abilist'), pageSize: Number(shell('getconf', 'PAGE_SIZE')),
  memoryTotalKiB: Number(memory[1]), physicalPixels: pixels.slice(1).map(Number), densityDpi: density,
  buildFingerprint: shell('getprop', 'ro.build.fingerprint'), adbUid: Number(shell('id', '-u')),
  shizukuPackagePresent: hasPackage('moe.shizuku.privileged.api'), dstuPackagePresent: hasPackage('com.deepstudent.app'),
};
result.smallestWidthDp = Math.min(...result.physicalPixels) * 160 / density;
const observed = input.inventory.devices.find(device => device.model === result.model);
assert.ok(observed, 'device model must be explicitly registered');
for (const [key, value] of Object.entries(result)) assert.deepEqual(value, observed[key], `device observation drift: ${key}`);
log('device.probe.accepted', { unit: 'S0.17', deviceId: observed.id, ...result,
  scope: 'read-only properties; no install, permission grant, application benchmark or private data read' });
