import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { test } from 'node:test';
import { root } from './modules.mjs';
const read = path => readFileSync(resolve(root, path), 'utf8');

test('normal CI has separate required checks, bounded triggers and real device matrix', () => {
  const ci = read('.github/workflows/android.yml');
  assert.match(ci, /branches: \[main, 'release\/\*\*'\]/u);
  assert.doesNotMatch(ci, /pull_request_target|continue-on-error: true/u);
  for (const job of ['contracts', 'static', 'unit', 'device', 'required']) assert.match(ci, new RegExp(`^  ${job}:`, 'mu'));
  assert.match(ci, /api: \[26, 36\]/u);
  assert.match(ci, /needs: \[contracts, static, unit, device\]/u);
  assert.match(ci, /value.result!=="success"/u);
  const device = read('tools/ci/device-tests.sh');
  for (const module of ['storage', 'network', 'security']) assert.ok(device.includes(`:platform:${module}:connectedDebugAndroidTest`));
  assert.match(device, /check-device-results.mjs/u);
});
test('release depends on CI, requires protected secrets and never publishes automatically', () => {
  const release = read('.github/workflows/android-release.yml');
  assert.match(release, /needs: \[validate, checks\]/u);
  assert.match(release, /environment: android-release/u);
  assert.match(release, /assembleRelease :app:bundleRelease/u);
  assert.match(release, /apksigner.*verify/u);
  assert.match(release, /--verify-tag --draft/u);
  assert.match(release, /trap 'rm -f "\$DSTU_RELEASE_KEYSTORE"' EXIT/u);
  assert.match(release, /if-no-files-found: error/u);
});
test('format ratchet is an immutable pre-change commit, not the branch currently being tested', () => {
  assert.match(read('config/quality/format-baseline.txt').trim(), /^[0-9a-f]{40}$/u);
  assert.match(read('build.gradle.kts'), /config\/quality\/format-baseline.txt/u);
  assert.doesNotMatch(read('build.gradle.kts'), /ratchetFrom\("(?:HEAD|origin\/main)"\)/u);
});
