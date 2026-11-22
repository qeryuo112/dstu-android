// No device is accessed: all collector commands execute this private fake ADB.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const collector = join(root, 'tools/s2/collect-process-exit.mjs');
const pkg = 'com.deepstudent.app';
const since = 1795169320000; // 2026-11-20 18:08:40.000 +0800, Unix milliseconds.
const until = 1795169330000;
const exit = (time, pid, process = pkg, desc = 'private description') => `  ApplicationExitInfo #0:
   timestamp=${time} pid=${pid} realUid=10395 packageUid=10395 definingUid=10395 user=0
   process=${process} reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) status=0
   importance=100 pss=1MB rss=2MB description=${desc} state=empty trace=/secret/trace.txt`;
const heading = `ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)
  package: ${pkg}
    Historical Process Exit for uid=10395`;
const fake = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const p = require('node:path');
let a = process.argv.slice(2);
fs.appendFileSync(p.join(process.env.MOCK_DIR, 'calls'), JSON.stringify(a) + '\n');
if (a[0] === 'devices') {
  if (process.env.DEVICE_FAIL) { process.stderr.write('secret device failure'); process.exit(1); }
  console.log('List of devices attached\nserial-1\tdevice' + (process.env.TWO_DEVICES ? '\nserial-2\tdevice' : ''));
  process.exit();
}
if (a[0] !== '-s' || a[1] !== 'serial-1') process.exit(13);
a = a.slice(2);
if (a[0] === 'shell' && a[1] === 'date') {
  if (process.env.CLOCK_FAIL) process.exit(2);
  console.log((process.env.CLOCK_MS || '1795169330000') + ',' + (process.env.CLOCK_TZ || '+0800'));
} else if (a[0] === 'shell' && a[1] === 'dumpsys' && a[2] === 'activity' && a[3] === 'exit-info') {
  if (process.env.DUMP_DENIED) { process.stderr.write('Permission Denial with secret payload'); process.exit(1); }
  if (process.env.DUMP_FAIL) { process.stderr.write('private error text'); process.exit(1); }
  if (process.env.DUMP_TIMEOUT) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 5000);
  process.stdout.write(process.env.DUMP || 'ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)\n');
} else if (a[0] === 'logcat' && a[2] === 'events') {
  if (process.env.EVENTS_DENIED) { process.stdout.write('Permission denied private payload'); process.exit(); }
  process.stdout.write(process.env.EVENTS || '');
} else if (a[0] === 'logcat' && a[2] === 'system') {
  if (process.env.SYSTEM_FAIL) process.exit(1);
  process.stdout.write(process.env.SYSTEM || '');
} else process.exit(14);
`;

function setup() {
  const dir = mkdtempSync(join(tmpdir(), 's2-exit-fake-'));
  const adb = join(dir, 'adb');
  writeFileSync(adb, fake);
  chmodSync(adb, 0o700);
  const run = (env = {}, extra = [], opts = {}) => {
    const args = ['--adb', adb, '--serial', 'serial-1', '--package', opts.pkg ?? pkg, '--since-ms', String(opts.since ?? since)];
    if (!opts.omitTz) args.push('--since-tz', '+0800');
    args.push(...extra);
    const result = spawnSync(process.execPath, [collector, ...args], { cwd: root, encoding: 'utf8',
      env: { ...process.env, MOCK_DIR: dir, ...env }, timeout: 15000 });
    const rows = result.stdout.trim().split('\n').filter(Boolean).map(s => JSON.parse(s));
    return { ...result, rows };
  };
  const calls = () => readFileSync(join(dir, 'calls'), 'utf8').trim().split('\n').map(JSON.parse);
  return { run, calls, cleanup: () => rmSync(dir, { force: true, recursive: true }) };
}
const byType = (result, type) => result.rows.filter(row => row.type === type);

test('parses real multiline AOSP exit metadata and OEM kill, correlating PID/time only', () => {
  const t = setup();
  try {
    const dump = [heading, exit('2026-11-20 18:08:44.416', 30008, pkg,
      'forceStopPackageByUid 1000pid 18156 SECRET_CONTENT'),
      exit('2026-11-20 18:08:39.999', 30008), exit('2026-11-20 18:08:45.000', 123, pkg + '.test')].join('\n');
    const events = [
      `11-20 18:08:44.193  1344  3108 I am_kill : [0,30008,${pkg},0,stop ${pkg} due to forceStopPackageByUid 1000pid 18156,126652]`,
      `11-20 18:08:39.193  1344  3108 I am_kill : [0,30008,${pkg},0,old SECRET_OLD,126652]`,
      `11-20 18:08:44.193  1344  3108 I am_kill : [0,30008,${pkg}.test,0,other SECRET_OTHER,126652]`,
      `11-20 18:08:51.193  1344  3108 I am_kill : [0,30008,${pkg},0,future SECRET_FUTURE,126652]`,
    ].join('\n');
    const r = t.run({ DUMP: dump, EVENTS: events }, ['--pid', '30008']);
    assert.equal(r.status, 0, r.stderr);
    const info = byType(r, 'exitInfo');
    assert.equal(info.length, 1);
    assert.deepEqual([info[0].timestampMs, info[0].pid, info[0].realUid, info[0].reasonCode,
      info[0].reasonName, info[0].subreasonCode, info[0].subreasonName],
      [1795169324416, 30008, 10395, 10, 'USER_REQUESTED', 21, 'FORCE_STOP']);
    assert.equal(info[0].reportedCallerUid, 1000);
    assert.equal(info[0].reportedCallerPid, 18156);
    assert.equal(info[0].callerAttribution, 'oem_text_unverified');
    const kills = byType(r, 'amKill');
    assert.equal(kills.length, 1);
    assert.deepEqual([kills[0].pid, kills[0].reportedCallerUid, kills[0].reportedCallerPid], [30008, 1000, 18156]);
    assert.equal(kills[0].exitCorrelation, 'same_pid_process_within_10s_not_causation');
    assert.doesNotMatch(r.stdout + r.stderr, /SECRET|private description|\/secret|\.test/);
    const calls = t.calls();
    assert.ok(calls.some(c => c.slice(2).join(' ') === `shell dumpsys activity exit-info ${pkg}`));
    assert.ok(calls.some(c => c.includes('events') && c.includes('1795169320.000') && c.includes('am_kill:I')));
    assert.ok(calls.some(c => c.includes('system') && c.includes('ActivityManager:I')));
    assert.ok(calls.every(c => c[0] === 'devices' || (c[0] === '-s' && c[1] === 'serial-1')));
  } finally { t.cleanup(); }
});

test('epoch logcat works without timezone; local exit records are not attributed', () => {
  const t = setup();
  try {
    const r = t.run({ DUMP: [heading, exit('2026-11-20 18:08:44.416', 30008)].join('\n'),
      EVENTS: `1795169324.193  1344  3108 I am_kill : [0,30008,${pkg},0,low memory,126652]` }, [], { omitTz: true });
    assert.equal(byType(r, 'exitInfo').length, 0);
    assert.equal(byType(r, 'exitInfoSummary')[0].status, 'timezone_unverified');
    assert.equal(byType(r, 'amKill')[0].exitCorrelation, 'unconfirmed');
    assert.equal(byType(r, 'amKill')[0].descriptionClass, 'memory_related');
  } finally { t.cleanup(); }
});

test('timezone changes and invalid/reversed/overlong clock windows never admit stale records', () => {
  for (const env of [{ CLOCK_TZ: '+0700' }, { CLOCK_FAIL: '1' },
    { CLOCK_MS: String(since - 1) }, { CLOCK_MS: String(since + 600001) }]) {
    const t = setup();
    try {
      const r = t.run({ ...env, DUMP: [heading, exit('2026-11-20 18:08:44.416', 30008)].join('\n') });
      assert.equal(byType(r, 'exitInfo').length, 0);
      assert.ok(['timezone_unverified', 'window_unavailable'].includes(byType(r, 'exitInfoSummary')[0].status));
      assert.ok(t.calls().some(c => c.includes('exit-info')), 'still attempt package-scoped dumpsys');
      if (env.CLOCK_FAIL || env.CLOCK_MS) assert.equal(t.calls().some(c => c.includes('logcat')), false);
    } finally { t.cleanup(); }
  }
});

test('explicit no-history, no-match, permission and immediate command failure statuses are safe', () => {
  for (const [env, expected] of [
    [{}, 'no_history'], [{ DUMP: [heading, exit('2026-11-20 18:08:39.999', 30008)].join('\n') }, 'no_match'],
    [{ DUMP_DENIED: '1' }, 'permission_denied'], [{ DUMP_FAIL: '1' }, 'command_failed'],
    [{ DUMP: 'unexpected OEM response SECRET_CONTENT' }, 'unrecognized_format'],
    [{ DUMP: 'No historical process exit information found' }, 'no_history'],
  ]) {
    const t = setup();
    try {
      const r = t.run(env);
      assert.equal(r.status, 0);
      assert.equal(byType(r, 'exitInfoSummary')[0].status, expected);
      assert.equal(byType(r, 'amKillSummary')[0].status, 'no_match');
      assert.doesNotMatch(r.stdout + r.stderr, /SECRET_CONTENT|private error|secret payload/);
    } finally { t.cleanup(); }
  }
});

test('force-stop lines require exact package/time and known PID when requested', () => {
  const t = setup();
  try {
    const lines = [
      `1795169324.100  1344  3108 I ActivityManager: Force stopping ${pkg} forceStopPackageByUid 1000pid 18156 SECRET`,
      `1795169324.200  1344  3108 I ActivityManager: Force stopping ${pkg}.test forceStopPackageByUid 1000pid 18156 OTHER`,
    ].join('\n');
    const loose = t.run({ SYSTEM: lines });
    assert.equal(byType(loose, 'forceStop').length, 1);
    assert.equal(byType(loose, 'forceStop')[0].targetPid, null);
    assert.equal(byType(loose, 'forceStop')[0].reportedCallerPid, 18156);
    assert.equal(byType(loose, 'forceStop')[0].exitCorrelation, 'unconfirmed');
    const strict = t.run({ SYSTEM: lines }, ['--pid', '30008']);
    assert.equal(byType(strict, 'forceStopSummary')[0].status, 'no_match');
    assert.doesNotMatch(loose.stdout, /SECRET|OTHER|\.test/);
  } finally { t.cleanup(); }
});

test('more than one connected device or device-list failure prevents all subsequent commands', () => {
  for (const env of [{ TWO_DEVICES: '1' }, { DEVICE_FAIL: '1' }]) {
    const t = setup();
    try {
      const r = t.run(env);
      assert.equal(r.status, 1);
      assert.equal(t.calls().length, 1);
      assert.match(r.stdout, /single_device_required|device_list_command_failed/);
      assert.doesNotMatch(r.stdout + r.stderr, /secret/);
    } finally { t.cleanup(); }
  }
});

test('a stuck dumpsys is bounded, reported, and does not prevent the other read-only sources', () => {
  const t = setup();
  try {
    const r = t.run({ DUMP_TIMEOUT: '1', EVENTS_DENIED: '1', SYSTEM_FAIL: '1' });
    assert.equal(r.status, 0);
    assert.equal(byType(r, 'exitInfoSummary')[0].status, 'timeout');
    assert.equal(byType(r, 'amKillSummary')[0].status, 'permission_denied');
    assert.equal(byType(r, 'forceStopSummary')[0].status, 'command_failed');
    assert.doesNotMatch(r.stdout + r.stderr, /private payload/);
  } finally { t.cleanup(); }
});

test('runner start and finish force-stops are instrumentation lifecycle, not an OEM-cause inference', () => {
  const t = setup();
  try {
    const dump = [heading, exit('2026-11-20 18:08:44.416', 30008, pkg,
      `stop ${pkg} due to finished inst SECRET_CONTEXT`)].join('\n');
    const events = [
      `1795169324.193  1344  3108 I am_kill : [0,30008,${pkg},0,stop ${pkg} due to finished inst,126652]`,
      `1795169325.193  1344  3108 I am_kill : [0,30009,${pkg},0,stop ${pkg} due to start instr,126652]`,
      `1795169326.193  1344  3108 I am_kill : [0,30010,${pkg},0,stop ${pkg} due to start instrumentation forceStopPackageByUid 1000pid 18156,126652]`,
    ].join('\n');
    const r = t.run({ DUMP: dump, EVENTS: events });
    assert.equal(byType(r, 'exitInfo')[0].descriptionClass, 'instrumentation_lifecycle');
    assert.deepEqual(byType(r, 'amKill').map(x => x.descriptionClass), Array(3).fill('instrumentation_lifecycle'));
    assert.equal(byType(r, 'amKill')[2].callerAttribution, 'oem_text_unverified');
    assert.doesNotMatch(r.stdout + r.stderr, /SECRET_CONTEXT/);
  } finally { t.cleanup(); }
});

test('the supplied OEM threadtime am_kill sample is filtered and parsed literally', () => {
  const t = setup();
  try {
    const target = 'com.deepstudent.platform.database.test';
    const start = Date.parse('2026-11-20T17:57:30.000+08:00');
    const end = Date.parse('2026-11-20T17:57:40.000+08:00');
    const sample = '11-20 17:57:35.193  1344  3108 I am_kill : [0,24843,com.deepstudent.platform.database.test,0,stop com.deepstudent.platform.database.test due to forceStopPackageByUid 1000pid 18156,126652]';
    const r = t.run({ EVENTS: sample, CLOCK_MS: String(end) }, ['--pid', '24843'], { pkg: target, since: start });
    const kill = byType(r, 'amKill');
    assert.equal(kill.length, 1);
    assert.equal(kill[0].timestampMs, Date.parse('2026-11-20T17:57:35.193+08:00'));
    assert.deepEqual([kill[0].pid, kill[0].reportedCallerUid, kill[0].reportedCallerPid], [24843, 1000, 18156]);
    assert.equal(kill[0].exitCorrelation, 'unconfirmed');
  } finally { t.cleanup(); }
});

test('SIGNALED/9 and USER_REQUESTED/FORCE_STOP remain distinct metadata, not generic crash causes', () => {
  const t = setup();
  try {
    const sig = exit('2026-11-20 18:08:44.416', 30008)
      .replace('reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) status=0', 'reason=2 (SIGNALED) subreason=0 (UNKNOWN) status=9');
    const stop = exit('2026-11-20 18:08:45.000', 30009);
    const r = t.run({ DUMP: [heading, sig, stop].join('\n') });
    assert.deepEqual(byType(r, 'exitInfo').map(x => [x.pid, x.reasonName, x.subreasonCode, x.status]),
      [[30008, 'SIGNALED', 0, 9], [30009, 'USER_REQUESTED', 21, 0]]);
    assert.doesNotMatch(r.stdout, /Process crashed/);
  } finally { t.cleanup(); }
});

test('description contents cannot supply missing exit-info metadata', () => {
  const t = setup();
  try {
    const spoof = `${heading}\nApplicationExitInfo #0:\n timestamp=2026-11-20 18:08:44.416 pid=30008\n` +
      ` description=process=${pkg} reason=10 subreason=21 status=0 SECRET trace=/secret`;
    const r = t.run({ DUMP: spoof });
    assert.equal(byType(r, 'exitInfo').length, 0);
    assert.equal(byType(r, 'exitInfoSummary')[0].status, 'unrecognized_format');
    assert.doesNotMatch(r.stdout, /SECRET|\/secret/);
  } finally { t.cleanup(); }
});
