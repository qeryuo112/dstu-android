import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const awk = fileURLToPath(new URL('./s2-14-restore-trace.awk', import.meta.url));
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const event = (kind, n, phase, state, code = 'none') =>
  `event=backup.${kind} owner=app.backup correlationId=${id(n)} phase=${phase} state=${state} elapsedMs=1 code=${code}`;
const chain = (n, activationId = n) => [
  event('encrypted', n, 'authenticate', 'verified'),
  event('queue', n, 'queue', 'QUEUED'),
  event('restore', activationId, 'ACTIVATE', 'SUCCEEDED'),
  event('queue', n, 'startup', 'APPLIED'),
];
const report = (fourth, extra = []) => [
  ...[1, 2, 3].flatMap(n => chain(n)), ...fourth,
  event('encrypted', 8, 'prepare', 'failed', 'AUTHENTICATION_FAILED'),
  event('encrypted', 9, 'write', 'succeeded'), ...extra,
];
const run = rows => spawnSync('awk', ['-f', awk], { input: `${rows.join('\n')}\n`, encoding: 'utf8' });

test('counts only fully correlated encrypted restores and requires a rejected password and export', () => {
  const result = run(report(chain(4)));
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /encryptedTrace=verified .*correlatedActivations=4/u);
});

test('unrelated or out-of-order activation cannot satisfy the encrypted restore gate', () => {
  assert.equal(run(report(chain(4, 7))).status, 1);
  const [auth, queue, activate, applied] = chain(4);
  assert.equal(run(report([auth, activate, queue, applied])).status, 1);
});

test('a failed authentication must never have a queued restore under the same operation', () => {
  assert.equal(run(report(chain(4), [event('queue', 8, 'queue', 'QUEUED')])).status, 1);
});
