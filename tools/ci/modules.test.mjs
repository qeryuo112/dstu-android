import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { test } from 'node:test';
import { verifyModules, unitTestTasks } from './modules.mjs';

function fixture(t) {
  const root = mkdtempSync(resolve(tmpdir(), 'dstu-modules-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const write = (path, bytes = '') => { mkdirSync(resolve(root, path, '..'), { recursive: true }); writeFileSync(resolve(root, path), bytes); };
  write('settings.gradle.kts', 'include(":core:common")\nif (benchmark) include(":benchmark:startup")\n');
  write('core/common/build.gradle.kts', 'plugins { id("dstu.kotlin.library") }');
  write('core/common/src/test/kotlin/Test.kt');
  write('benchmark/startup/build.gradle.kts');
  return { root, write };
}

test('both normal and optional modules are checked; generated/vendor files are not modules', t => {
  const { root, write } = fixture(t);
  write('core/common/build/generated/build.gradle.kts');
  write('third_party/library/build.gradle.kts');
  assert.deepEqual(verifyModules(root), [':benchmark:startup', ':core:common']);
  assert.deepEqual(unitTestTasks('jvm', root), [':core:common:test']);
  assert.deepEqual(unitTestTasks('android', root), []);
});
test('an unregistered module fails', t => {
  const { root, write } = fixture(t); write('services/new/api/build.gradle.kts');
  assert.throws(() => verifyModules(root), /missing from settings/u);
});
test('stale include fails', t => {
  const { root, write } = fixture(t); write('settings.gradle.kts', 'include(":gone")');
  assert.throws(() => verifyModules(root), /missing from settings|without build scripts/u);
});
test('duplicate include fails', t => {
  const { root, write } = fixture(t);
  write('settings.gradle.kts', 'include(":core:common")\ninclude(":core:common")');
  assert.throws(() => verifyModules(root), /duplicate include/u);
});
test('comments cannot conceal a missing registration', t => {
  const { root, write } = fixture(t);
  write('settings.gradle.kts', 'include(":benchmark:startup")\n// include(":core:common")');
  assert.throws(() => verifyModules(root), /missing from settings/u);
});

test('new test modules cannot silently escape the unit-test shards', t => {
  const { root, write } = fixture(t);
  write('core/common/build.gradle.kts', 'plugins { id("custom.test.plugin") }');
  assert.throws(() => unitTestTasks('jvm', root), /unclassified test module/u);
});
