import assert from 'node:assert/strict';
import test from 'node:test';
import { instrumentConfig } from './instrument-config.mjs';

test('device harness accepts actual design, engine and layered module paths', () => {
  for (const module of ['app', 'core/design', 'engines/pdf', 'platform/network', 'features/editor/ui', 'services/runtime/data']) {
    const config = instrumentConfig([module, 'com.deepstudent.example.test', '2']);
    assert.equal(config.minimum, 2);
    assert.ok(config.testApk.startsWith(`${module}/build/`));
  }
});

test('device harness rejects traversal, commands and invalid test budgets', () => {
  for (const module of ['../app', 'core/../app', 'app;echo', '/app', 'features/notes', 'engines/pdf/../../app']) {
    assert.throws(() => instrumentConfig([module]));
  }
  for (const minimum of ['0', '-1', 'NaN', '1.5']) assert.throws(() => instrumentConfig(['app', 'com.deepstudent.app.test', minimum]));
  assert.throws(() => instrumentConfig(['app', 'com.other.test']));
  assert.throws(() => instrumentConfig(['app', 'com.deepstudent.app.test', '1', 'Class;command']));
});
