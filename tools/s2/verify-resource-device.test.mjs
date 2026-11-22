import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('verify-resource-device.sh', import.meta.url));
const digest = value => createHash('sha256').update(value).digest('hex');
const selector = 'resources 1 com.deepstudent.services.resources.data.AttachmentsAndroidTest\n';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'dstu-resource-runner-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'platform-tools'));
  const apks = ['app-debug.apk', 'app-debug-androidTest.apk', 'resources-debug-androidTest.apk'];
  const [app, appTest, resourcesTest] = apks.map(file => {
    writeFileSync(join(root, file), file);
    return digest(file);
  });
  const sha = digest(`app=${app}\nappTest=${appTest}\nresourcesTest=${resourcesTest}\n`);
  writeFileSync(join(root, 'calls'), '');
  writeFileSync(join(root, 'platform-tools/adb'), `#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$ANDROID_HOME/calls"
resource=com.deepstudent.services.resources.data.test
case "$*" in
  devices)
    printf 'List of devices attached\nSERIAL\tdevice\n'
    if [[ "$FIXTURE_MODE" == multiple ]]; then printf 'OTHER\tunauthorized\n'; fi ;;
  'get-state') echo device ;;
  'shell getprop ro.product.model') echo iPA2475 ;;
  'shell getprop ro.product.cpu.abi') echo arm64-v8a ;;
  'shell getprop ro.build.version.sdk') echo 36 ;;
  'shell am get-current-user') echo 0 ;;
  'shell dumpsys package '*)
    if [[ "$4" == "$resource" && -f "$ANDROID_HOME/installed" ]]; then echo 'Packages: fixture';
    else echo "Unable to find package: $4"; fi ;;
  'shell pm path --user 0 com.deepstudent.app') exit 1 ;;
  "shell pm path --user 0 $resource")
    if [[ -f "$ANDROID_HOME/installed" ]]; then echo 'package:/fixture-resources.apk'; else exit 1; fi ;;
  'shell date +%s%3N,%z') echo '1795190400000,+0800' ;;
  'shell date '*) echo '11-20 23:00:00.000' ;;
  'install '*) touch "$ANDROID_HOME/installed"; echo Success ;;
  'shell sha256sum /fixture-resources.apk') sha256sum "$ANDROID_HOME/resources-debug-androidTest.apk" ;;
  "uninstall --user 0 $resource") rm "$ANDROID_HOME/installed"; echo Success ;;
  'shell am instrument '*)
    if [[ "$FIXTURE_MODE" == skipped ]]; then echo 'INSTRUMENTATION_STATUS_CODE: -3';
    else echo 'INSTRUMENTATION_STATUS_CODE: 0'; fi
    echo 'OK (1 test)'; echo 'INSTRUMENTATION_CODE: -1' ;;
  'logcat '*) ;;
  *) exit 1 ;;
esac
`, { mode: 0o700 });
  return {
    run(mode = 'success', hash = sha, input = selector, env = {}) {
      return spawnSync('bash', [script, 'SERIAL', 'iPA2475', 'S2.06', root, hash], {
        input, encoding: 'utf8', env: { ...process.env, ANDROID_HOME: root, FIXTURE_MODE: mode, ...env }, timeout: 10_000,
      });
    },
    calls: () => readFileSync(join(root, 'calls'), 'utf8'),
  };
}

// Static syntax validation is separate from the fake ADB control flow below.
execFileSync('bash', ['-n', script]);

test('candidate hash mismatch and invalid selector are rejected before contacting ADB', t => {
  const device = fixture(t);
  assert.equal(device.run('success', 'e'.repeat(64)).status, 2);
  assert.equal(device.run('success', undefined, 'app 1 com.deepstudent.services.resources.data.Test\n').status, 2);
  assert.equal(device.calls(), '');
});

test('manual S2.07 picker input cannot be enabled for another unit', t => {
  const device = fixture(t);
  assert.equal(device.run('success', undefined, selector, { DSTU_S207_MANUAL_PICKER: '1' }).status, 2);
  assert.equal(device.calls(), '');
});

test('an unauthorized additional device also blocks installation', t => {
  const device = fixture(t);
  const result = device.run('multiple');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /exactly the selected device/);
  assert.equal(device.calls(), 'devices\n');
});

test('the candidate on-device hash is verified and only the owned test is removed', t => {
  const device = fixture(t);
  const result = device.run();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /executed=1 skipped=0/);
  assert.match(device.calls(), /^uninstall --user 0 com\.deepstudent\.services\.resources\.data\.test$/m);
  assert.doesNotMatch(device.calls(), /^uninstall --user 0 com\.deepstudent\.app/m);
});

test('a skipped instrumentation case cannot satisfy the gate even with a passing-looking summary', t => {
  const device = fixture(t);
  const result = device.run('skipped');
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.doesNotMatch(result.stdout, /executed=1 skipped=0/);
  assert.match(device.calls(), /^uninstall --user 0 com\.deepstudent\.services\.resources\.data\.test$/m);
});
