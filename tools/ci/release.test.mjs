import assert from 'node:assert/strict';
import { test } from 'node:test';
import { releaseIdentity } from './release.mjs';
test('release versions reuse the production versionCode mapping', () => {
  assert.deepEqual(releaseIdentity('tag', 'v0.21.0'), { version: '0.21.0', versionCode: 2100000 });
});
test('branch, prerelease, legacy and shell-like tag input are rejected', () => {
  for (const [type, name] of [['branch', 'main'], ['tag', 'v0.21.0-rc1'], ['tag', 'v0.20.0'], ['tag', 'v0.21.0\nrun=x']]) {
    assert.throws(() => releaseIdentity(type, name));
  }
});
