import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import { frozenInputScopes, verifyArchivedFrozenEvidence } from './frozen-inputs.mjs';

test('user-owned live rules are not pinned as S0 product input', () => {
  assert.ok(!frozenInputScopes.includes('docs/rules/rules.txt'));
  for (const scope of ['config', 'fixtures', 'desktop-increment', 'tools/baseline', 'NOTICE',
    'docs/plans/2026-11-15-mobile-full-feature-refactor.md']) assert.ok(frozenInputScopes.includes(scope));
  assert.equal(frozenInputScopes.length, 6);
});

test('archived evidence stays hash-bound to the historical S0 bytes', () => {
  const bytes = Buffer.from('frozen log');
  const entries = [{ path: 'logs/frozen.log', size: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex') }];
  assert.equal(verifyArchivedFrozenEvidence(['logs/frozen.log'], entries, () => bytes, 1), 1);
  assert.throws(() => verifyArchivedFrozenEvidence(['logs/frozen.log'], [], () => bytes, 1), /missing archived/u);
  assert.throws(() => verifyArchivedFrozenEvidence(['logs/frozen.log'], entries, () => Buffer.from('tampered'), 1), /bytes changed/u);
  assert.ok(!frozenInputScopes.includes('logs'));
});
