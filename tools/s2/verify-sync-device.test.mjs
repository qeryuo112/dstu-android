// Exercises the device shell orchestration with a fake ADB, never with a real device.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const script = join(root, 'tools/s2/verify-sync-device.sh');
const deathScript = join(root, 'tools/s2/verify-migration-death.sh');
const sha = data => createHash('sha256').update(data).digest('hex');
const fakeAdb = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
let args = process.argv.slice(2), dir = process.env.MOCK_ADB_DIR;
fs.appendFileSync(path.join(dir, 'calls'), JSON.stringify(args) + '\n');
const scoped = args[0] === '-s';
if (scoped) { if (args[1] !== 'serial-1') process.exit(90); args = args.slice(2); }
const pkgFile = pkg => path.join(dir, encodeURIComponent(pkg) + '.pkg');
const isInstalled = pkg => fs.existsSync(pkgFile(pkg));
const output = text => process.stdout.write(text + '\n');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const stateFile = path.join(dir, 'death-state.json');
const state = () => fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : { n: 0 };
const dbInfo = name => {
  const lock = JSON.parse(fs.readFileSync('config/schema/final-lock.json'));
  const repair = JSON.parse(fs.readFileSync('config/schema/s2-04-repair.json'));
  const info = lock.databases.find(x => x.id === name);
  if (name === repair.database) {
    info.version = repair.after.version; info.migrationCount = repair.after.migrationCount;
    info.fingerprint = repair.after.fingerprint; info.history.push({ version: repair.migration.version });
  }
  return info;
};
if (args[0] === 'devices') {
  if (scoped) process.exit(91);
  if (process.env.MOCK_COLLECT_DEVICE_FAIL && fs.existsSync(path.join(dir, 'instrument-failed'))) process.exit(41);
  output('List of devices attached\nserial-1\tdevice' + ((process.env.MOCK_TWO_DEVICES ||
    (process.env.MOCK_TWO_AFTER && fs.existsSync(path.join(dir, 'instrument-failed')))) ? '\nserial-2\tdevice' : ''));
} else if (args[0] === 'get-state') {
  output('device');
} else if (args[0] === 'install') {
  const apk = args.at(-1);
  const packages = { 'app-debug.apk': 'com.deepstudent.app', 'app-debug-androidTest.apk': 'com.deepstudent.app.test',
    'data-debug-androidTest.apk': 'com.deepstudent.services.sync.data.test',
    'database-debug-androidTest.apk': 'com.deepstudent.platform.database.test' };
  const pkg = packages[path.basename(apk)];
  if (!pkg) process.exit(11);
  fs.writeFileSync(pkgFile(pkg), apk); output('Success');
} else if (args[0] === 'uninstall') {
  const file = pkgFile(args.at(-1));
  if (fs.existsSync(file)) fs.unlinkSync(file);
  output('Success');
} else if (args[0] === 'exec-out') {
  const cat = args.indexOf('cat');
  if (args.includes('sha256sum')) output(hash(fs.readFileSync(path.join(dir, 'fixture'))) + '  fixture.json');
  else if (cat >= 0) {
    const kind = path.basename(args[cat + 1]);
    if (kind === 'commit' && process.env.MOCK_DEATH_MODE === 'missing') {
      fs.writeFileSync(path.join(dir, 'instrument-failed'), '1'); process.exit(42);
    }
    if (kind === 'verified' && process.env.MOCK_DEATH_MODE === 'receipt-missing') {
      fs.writeFileSync(path.join(dir, 'instrument-failed'), '1'); process.exit(43);
    }
    const file = path.join(dir, kind);
    if (!fs.existsSync(file)) process.exit(44);
    output(fs.readFileSync(file, 'utf8'));
  }
  // mkdir is confined to the mock and needs no data.
} else if (args[0] === 'shell') {
  const a = args.slice(1);
  if (a[0] === 'getprop') output(a[1] === 'ro.product.model' ? 'fake-arm64' : a[1] === 'ro.product.cpu.abi' ? 'arm64-v8a' : '35');
  else if (a[0] === 'am' && a[1] === 'get-current-user') output('0');
  else if (a[0] === 'am' && a[1] === 'instrument') {
    const selection = a[a.indexOf('class') + 1];
    if (selection.includes('MigrationCommitDeathAndroidTest#checkpoint')) {
      const get = key => a[a.indexOf(key) + 1];
      const operation = get('s203Operation'), before = Number(get('s203Before'));
      const kind = get('s203Kind'), db = get('s203Database'), info = dbInfo(db);
      const s = state(); s.n++; fs.writeFileSync(stateFile, JSON.stringify(s));
      if (operation !== 'verify') {
        const pid = process.env.MOCK_DEATH_MODE === 'stale' ? 32000 : 32000 + s.n;
        const count = operation === 'advance' ? before + 1 : before;
        const version = operation === 'advance' ? info.history[before].version : info.version;
        fs.writeFileSync(path.join(dir, 'commit'), [kind, db, count, version, pid, before, 'a'.repeat(64)].join('\t'));
        if (process.env.MOCK_DEATH_MODE === 'unexpected') {
          fs.writeFileSync(path.join(dir, 'instrument-failed'), '1');
          output('INSTRUMENTATION_RESULT: shortMsg=Process crashed unexpectedly.\nSECRET RUNNER CONTENT');
        } else output('INSTRUMENTATION_RESULT: shortMsg=Process crashed.');
      } else {
        const pid = 42000 + s.n;
        fs.writeFileSync(path.join(dir, 'verified'), [db, kind, info.migrationCount, info.version, pid, 'b'.repeat(64),
          info.fingerprint, 'restartApplied=0', 'governanceUnchanged=true'].join('\t'));
        if (process.env.MOCK_DEATH_MODE === 'verify-failed') {
          fs.writeFileSync(path.join(dir, 'instrument-failed'), '1');
          output('INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nSECRET RUNNER CONTENT');
        } else output('INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1');
      }
    } else if (process.env.MOCK_FAIL_METHOD && selection.includes(process.env.MOCK_FAIL_METHOD)) {
      fs.writeFileSync(path.join(dir, 'instrument-failed'), '1');
      if (process.env.MOCK_FAIL_KIND === 'transport') { process.stderr.write('SECRET RUNNER CONTENT'); process.exit(23); }
      if (process.env.MOCK_FAIL_KIND === 'shortmsg') output('INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nSECRET RUNNER CONTENT');
      else if (process.env.MOCK_FAIL_KIND === 'incomplete') output('INSTRUMENTATION_CODE: -1\nSECRET RUNNER CONTENT');
      else output('FAILURES!!!\nINSTRUMENTATION_CODE: -1\nSECRET RUNNER CONTENT');
    } else output('INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1');
  } else if (a[0] === 'dumpsys' && a[1] === 'activity' && a[2] === 'exit-info') {
    if (process.env.MOCK_DUMP_DENIED) { process.stderr.write('Permission Denial: SECRET DUMP'); process.exit(45); }
    if (process.env.MOCK_DUMP_FAIL) process.exit(46);
    output('ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)');
  } else if (a[0] === 'dumpsys') {
    const pkg = a.at(-1);
    if (pkg === 'com.deepstudent.app' && process.env.MOCK_APP_DUMPSYS_FAIL) {
      // Even plausible stdout is not proof of absence when dumpsys failed.
      output('Unable to find package: ' + pkg);
      process.stderr.write('mock dumpsys failure\n'); process.exit(39);
    }
    output(isInstalled(pkg) || (pkg === 'com.deepstudent.app' && process.env.MOCK_APP_HIDDEN)
      ? 'Package [' + pkg + ']' : 'Unable to find package: ' + pkg);
  } else if (a[0] === 'pm' && a[1] === 'path') {
    const pkg = a.at(-1);
    if (pkg === 'com.deepstudent.app' && process.env.MOCK_APP_PM_ERROR === 'transport') {
      process.stderr.write('mock transport failure\n'); process.exit(37);
    }
    if (pkg === 'com.deepstudent.app' && process.env.MOCK_APP_PM_ERROR === 'nonempty') {
      output('mock pm failure'); process.exit(1);
    }
    if (isInstalled(pkg)) output('package:/mock/' + pkg + '.apk');
    else if (process.env.MOCK_PM_ABSENT_EXIT1) process.exit(1);
  } else if (a[0] === 'sha256sum') {
    const pkg = path.basename(a[1], '.apk'); output(hash(fs.readFileSync(fs.readFileSync(pkgFile(pkg), 'utf8'))) + '  ' + a[1]);
  } else if (a[0] === '-T' && a[1].includes('cat >')) {
    fs.writeFileSync(path.join(dir, 'fixture'), fs.readFileSync(0));
  } else if (a[0] === '-T' && a[1].includes('unlink')) {
    const f = path.join(dir, 'fixture'); if (fs.existsSync(f)) fs.unlinkSync(f);
  } else if (a[0] === 'run-as') {
    // mkdir/rmdir use only the private mock directory.
  } else if (a[0] === 'date') {
    if (process.env.MOCK_CLOCK_FAIL) process.exit(47);
    output((scoped ? '1795169330000' : '1795169320000') + ',+0800');
  } else if (a[0].startsWith('date ')) output('11-20 12:00:00.000');
  else if (a[0].startsWith('test ! -d /proc/')) output(process.env.MOCK_PID_STAYS ? 'alive' : 'gone');
  else process.exit(12);
} else if (args[0] !== 'logcat') process.exit(13);
`;

function setup() {
  const dir = mkdtempSync(join(tmpdir(), 'dstu-s205-fake-adb-'));
  const android = join(dir, 'android');
  const apkDir = join(dir, 'apks');
  mkdirSync(join(android, 'platform-tools'), { recursive: true });
  mkdirSync(apkDir);
  const adb = join(android, 'platform-tools/adb');
  writeFileSync(adb, fakeAdb);
  chmodSync(adb, 0o700);
  for (const file of ['app-debug.apk', 'app-debug-androidTest.apk', 'data-debug-androidTest.apk', 'database-debug-androidTest.apk']) {
    writeFileSync(join(apkDir, file), `fixture APK ${file}`);
  }
  const fixture = join(dir, 'oracle.json');
  writeFileSync(fixture, 'fake oracle, not Android evidence');
  const fixtureSha = sha(readFileSync(fixture));
  const env = { ...process.env, ANDROID_HOME: android, DSTU_S2_APK_DIR: apkDir, DSTU_S2_ORACLE_SHA256: fixtureSha,
    MOCK_ADB_DIR: dir };
  delete env.DSTU_S2_CANDIDATE_SHA256;
  const run = extra => spawnSync('bash', [script, 'serial-1', 'fake-arm64', fixture, '--capture-and-changes'],
    { cwd: root, env: { ...env, ...extra }, encoding: 'utf8' });
  const runDeath = extra => spawnSync('bash', [deathScript, 'serial-1', 'fake-arm64', fixture, '--scenario', 'llm_usage'],
    { cwd: root, env: { ...env, ...extra }, encoding: 'utf8' });
  const calls = () => existsSync(join(dir, 'calls')) ? readFileSync(join(dir, 'calls'), 'utf8').trim().split('\n').map(JSON.parse) : [];
  const cleanup = () => rmSync(dir, { recursive: true, force: true });
  return { dir, fixtureSha, run, runDeath, calls, cleanup };
}

test('requires a pinned oracle before touching ADB', () => {
  const t = setup();
  try {
    const result = t.run({ DSTU_S2_ORACLE_SHA256: '' });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /ORACLE_SHA256/u);
    assert.deepEqual(t.calls(), []);
  } finally { t.cleanup(); }
});

test('refuses more than one connected device before installing', () => {
  const t = setup();
  try {
    const result = t.run({ MOCK_TWO_DEVICES: '1' });
    assert.notEqual(result.status, 0);
    assert.equal(t.calls().filter(call => call[0] === 'install').length, 0);
  } finally { t.cleanup(); }
});

test('exit-1 empty pm path for globally absent packages is safe to install and clean up', () => {
  const t = setup();
  try {
    const result = t.run({ MOCK_PM_ABSENT_EXIT1: '1' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const calls = t.calls();
    assert.ok(calls.some(call => call.join(' ') === 'shell dumpsys package com.deepstudent.app'));
    assert.equal(calls.filter(call => call[0] === 'install').length, 4);
    assert.equal(calls.filter(call => call[0] === 'uninstall').length, 4);
    assert.match(result.stdout, /ordinaryApp=temporary_removed/u);
    assert.equal(existsSync(join(t.dir, 'fixture')), false);
  } finally { t.cleanup(); }
});

test('hidden packages and pm or dumpsys failures never authorize installation', () => {
  for (const [label, extra, expectedStatus, expectAppDumpsys] of [
    ['hidden for user 0', { MOCK_PM_ABSENT_EXIT1: '1', MOCK_APP_HIDDEN: '1' }, 1, true],
    ['transport error', { MOCK_APP_PM_ERROR: 'transport' }, 37, false],
    ['exit 1 with stdout', { MOCK_APP_PM_ERROR: 'nonempty' }, 1, false],
    ['failed dumpsys with misleading stdout', { MOCK_PM_ABSENT_EXIT1: '1', MOCK_APP_DUMPSYS_FAIL: '1' }, 1, true],
  ]) {
    const t = setup();
    try {
      const result = t.run(extra);
      assert.equal(result.status, expectedStatus, `${label}: ${result.stdout}${result.stderr}`);
      const calls = t.calls();
      assert.equal(calls.some(call => call.join(' ') === 'shell dumpsys package com.deepstudent.app'), expectAppDumpsys, label);
      assert.equal(calls.filter(call => call[0] === 'install' || call[0] === 'uninstall').length, 0, label);
    } finally { t.cleanup(); }
  }
});

test('hashes four candidates, isolates every case, and removes owned packages', () => {
  const t = setup();
  try {
    const result = t.run({});
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /candidateSha256=[0-9a-f]{64}/u);
    const calls = t.calls();
    assert.equal(calls.filter(call => call[0] === 'install').length, 4);
    const methods = calls.filter(call => call[0] === 'shell' && call[1] === 'am' && call[2] === 'instrument')
      .map(call => call[call.indexOf('class') + 1]);
    assert.equal(methods.length, 27); // Four migration, 16 capture, four app regression and three oracle cases.
    assert.equal(new Set(methods).size, methods.length);
    assert.ok(methods.every(method => method.includes('#')), 'every invocation must select one explicit method');
    assert.deepEqual(methods.filter(method => method.includes('CoordinatedRepairAndroidTest#')).map(method => method.split('#')[1]), [
      'freshAndRestartApplyRepair', 'oldHeadUpgradePreservesRowsAndLogThenDelete',
      'rejectsUntrustedInputsWithoutOpening', 'rejectsOldHeadDriftWithoutChangingData',
    ]);
    const oracle = methods.filter(method => method.includes('DesktopChangeLogOracleDeviceTest#'));
    assert.equal(oracle.length, 3);
    assert.ok(calls.filter(call => call.includes('oracleSha256')).every(call => call.includes(t.fixtureSha)));
    assert.equal(calls.filter(call => call[0] === 'uninstall').length, 4);
    assert.equal(existsSync(join(t.dir, 'fixture')), false);
  } finally { t.cleanup(); }
});

test('a failed final oracle case still cleans all installed test and temporary app packages', () => {
  const t = setup();
  try {
    const result = t.run({ MOCK_FAIL_METHOD: 'repairedDesktopVfsPluginDeletes' });
    assert.notEqual(result.status, 0);
    assert.equal(t.calls().filter(call => call[0] === 'uninstall').length, 4);
    assert.equal(existsSync(join(t.dir, 'fixture')), false);
  } finally { t.cleanup(); }
});

function diagnosticBeforeUninstall(calls, expectedPackage) {
  const dumpIndex = calls.findIndex(call => call.includes('exit-info'));
  const uninstallIndex = calls.findIndex(call => call[0] === 'uninstall');
  assert.ok(dumpIndex >= 0, 'package-scoped exit-info must be attempted');
  assert.equal(calls[dumpIndex].at(-1), expectedPackage);
  assert.ok(uninstallIndex > dumpIndex, 'diagnostics must precede any owned APK uninstall');
  assert.ok(calls.slice(dumpIndex, uninstallIndex).some(call => call.includes('am_kill:I')));
}

test('ordinary success does not collect exit evidence', () => {
  const t = setup();
  try {
    const result = t.run({});
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(t.calls().filter(call => call.includes('exit-info')).length, 0);
    assert.equal(t.calls().filter(call => call.includes('am_kill:I')).length, 0);
  } finally { t.cleanup(); }
});

test('sync failures collect the actual standalone or app target before uninstall', () => {
  for (const [method, expectedPackage, kind] of [
    ['freshAndRestartApplyRepair', 'com.deepstudent.platform.database.test', 'transport'],
    ['frozenRegistryAndAllFourRealSchemasAgree', 'com.deepstudent.services.sync.data.test', 'shortmsg'],
    ['captureDriftStopsTheRealTransferBeforeAnyNetworkOrJournal', 'com.deepstudent.app', 'incomplete'],
    ['repairedDesktopVfsPluginDeletesProduceSameChangesAndWireOrder', 'com.deepstudent.app', 'failure'],
  ]) {
    const t = setup();
    try {
      const r = t.run({ MOCK_FAIL_METHOD: method, MOCK_FAIL_KIND: kind });
      assert.equal(r.status, 1, r.stdout + r.stderr);
      assert.match(r.stdout + r.stderr, /SECRET RUNNER CONTENT/, 'retain original runner transcript');
      assert.match(r.stderr, /"type":"exitInfoSummary","status":"no_history"/);
      diagnosticBeforeUninstall(t.calls(), expectedPackage);
      assert.equal(existsSync(join(t.dir, 'fixture')), false);
      assert.ok(t.calls().some(call => call[0] === 'uninstall'));
    } finally { t.cleanup(); }
  }
});

test('sync diagnostic failure or unavailable clock preserves failure status and cleanup', () => {
  for (const extra of [{ MOCK_DUMP_DENIED: '1' }, { MOCK_DUMP_FAIL: '1' },
    { MOCK_CLOCK_FAIL: '1' }, { MOCK_COLLECT_DEVICE_FAIL: '1' }, { MOCK_TWO_AFTER: '1' }]) {
    const t = setup();
    try {
      const r = t.run({ ...extra, MOCK_FAIL_METHOD: 'freshAndRestartApplyRepair', MOCK_FAIL_KIND: 'transport' });
      assert.equal(r.status, 1, r.stdout + r.stderr);
      assert.ok(t.calls().some(call => call[0] === 'uninstall'));
      assert.match(r.stderr, /"type":"collector"/);
      assert.doesNotMatch(r.stderr, /SECRET DUMP/);
      if (!extra.MOCK_COLLECT_DEVICE_FAIL && !extra.MOCK_TWO_AFTER) {
        const calls = t.calls();
        assert.ok(calls.findIndex(c => c.includes('exit-info')) < calls.findIndex(c => c[0] === 'uninstall'));
      }
    } finally { t.cleanup(); }
  }
});

test('migration planned kills and successful reopen do not collect redundant evidence', () => {
  const t = setup();
  try {
    const r = t.runDeath({});
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /commitDeathMatrix .*scope=llm_usage/);
    assert.ok(t.calls().filter(c => c.includes('s203Operation')).length > 1);
    assert.equal(t.calls().filter(c => c.includes('exit-info')).length, 0);
    assert.equal(t.calls().filter(c => c.includes('am_kill:I')).length, 0);
    assert.equal(t.calls().filter(c => c[0] === 'uninstall').length, 1);
  } finally { t.cleanup(); }
});

test('unexpected hard-death result or stale checkpoint collects before cleanup with original context', () => {
  for (const mode of ['unexpected', 'stale']) {
    const t = setup();
    try {
      const r = t.runDeath({ MOCK_DEATH_MODE: mode });
      assert.equal(r.status, 1, r.stdout + r.stderr);
      if (mode === 'unexpected') assert.match(r.stderr, /SECRET RUNNER CONTENT/);
      else assert.match(r.stderr, /invalid hard-death marker: .*marker=MIGRATION\tllm_usage\t\d+\t\d+\t32000\t/);
      diagnosticBeforeUninstall(t.calls(), 'com.deepstudent.platform.database.test');
      assert.equal(t.calls().filter(c => c[0] === 'uninstall').length, 1);
    } finally { t.cleanup(); }
  }
});

test('missing checkpoint keeps original non-one exit status even when diagnostics cannot start', () => {
  for (const extra of [{}, { MOCK_DUMP_DENIED: '1' }, { MOCK_COLLECT_DEVICE_FAIL: '1' }]) {
    const t = setup();
    try {
      const r = t.runDeath({ ...extra, MOCK_DEATH_MODE: 'missing' });
      assert.equal(r.status, 42, r.stdout + r.stderr);
      assert.match(r.stderr, /checkpoint.*status=42/);
      assert.equal(t.calls().filter(c => c[0] === 'uninstall').length, 1);
      if (!extra.MOCK_COLLECT_DEVICE_FAIL) diagnosticBeforeUninstall(t.calls(), 'com.deepstudent.platform.database.test');
      else assert.match(r.stderr, /device_list_command_failed/);
    } finally { t.cleanup(); }
  }
});

test('failed reopen and missing receipt collect without changing their original statuses', () => {
  for (const [mode, status] of [['verify-failed', 1], ['receipt-missing', 43]]) {
    const t = setup();
    try {
      const r = t.runDeath({ MOCK_DEATH_MODE: mode });
      assert.equal(r.status, status, r.stdout + r.stderr);
      if (mode === 'verify-failed') assert.match(r.stderr, /SECRET RUNNER CONTENT/);
      else assert.match(r.stderr, /receipt missing or unreadable.*status=43/);
      diagnosticBeforeUninstall(t.calls(), 'com.deepstudent.platform.database.test');
      assert.equal(t.calls().filter(c => c[0] === 'uninstall').length, 1);
    } finally { t.cleanup(); }
  }
});

test('a checkpoint PID that has not disappeared also triggers diagnostics', () => {
  const t = setup();
  try {
    const r = t.runDeath({ MOCK_PID_STAYS: '1' });
    assert.equal(r.status, 1, r.stdout + r.stderr);
    assert.match(r.stderr, /checkpoint pid still present/);
    diagnosticBeforeUninstall(t.calls(), 'com.deepstudent.platform.database.test');
  } finally { t.cleanup(); }
});

test('collector executable failure cannot replace a checkpoint read status or prevent uninstall', () => {
  const t = setup();
  try {
    const bin = join(t.dir, 'bin');
    mkdirSync(bin);
    const node = join(bin, 'node');
    // Simulate a missing/broken collector runtime while allowing the fake ADB to run.
    writeFileSync(node, `#!/bin/bash\ncase "$1" in */collect-process-exit.mjs) exit 127;; esac\nexec '${process.execPath}' "$@"\n`);
    chmodSync(node, 0o700);
    const r = t.runDeath({ MOCK_DEATH_MODE: 'missing', PATH: `${bin}:${process.env.PATH}` });
    assert.equal(r.status, 42, r.stdout + r.stderr);
    assert.match(r.stderr, /"type":"collector","status":"failed_or_timeout"/);
    assert.equal(t.calls().filter(c => c[0] === 'uninstall').length, 1);
  } finally { t.cleanup(); }
});
