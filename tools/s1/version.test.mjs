import assert from 'node:assert/strict';
import test from 'node:test';
import { gradleIdentity } from './version.mjs';

test('Gradle calls the frozen single-source mapping, not a second formula', () => {
  assert.equal(gradleIdentity('0.21.0'), '0.21.0:2100000');
  assert.equal(gradleIdentity('1.0.0'), '1.0.0:10000000');
  assert.equal(gradleIdentity('0.99.99999'), '0.99.99999:9999999');
});

for (const version of ['0.20.0', '0.20.1', '0.21.0-debug', '00.21.0', '1.100.0', '1.0.100000', '211.0.0']) {
  test(`reject invalid or legacy release ${version}`, () => assert.throws(() => gradleIdentity(version)));
}
