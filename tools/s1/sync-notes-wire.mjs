import assert from 'node:assert/strict';

/** Compare all paths and exact bytes, but never print a path, payload, or credential on failure. */
export function assertSameObjects(before, after) {
  const left = [...before.keys()].sort();
  const right = [...after.keys()].sort();
  assert.ok(left.length === right.length && left.every((key, index) => key === right[index]),
    'remote object inventory changed at the wrong-password boundary');
  for (const key of left) {
    assert.ok(Buffer.from(before.get(key)).equals(Buffer.from(after.get(key))),
      'remote object bytes changed at the wrong-password boundary');
  }
}

/** Do not echo the log or the offending canary: even failing diagnostics must be safe. */
export function assertNoCanaries(value, canaries) {
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value);
  for (const canary of canaries) {
    if (canary && bytes.includes(Buffer.from(canary))) throw new Error('fixture log or remote object exposed protected test data');
  }
  if (/authorization\s*[:=]\s*basic\s+[a-z0-9+/=]+/iu.test(bytes.toString('utf8'))) {
    throw new Error('fixture log exposed an authorization header');
  }
}

export function mutationCount(counts) {
  return Object.entries(counts).filter(([key]) => /^(PUT|DELETE|MKCOL):/u.test(key))
    .reduce((total, [, count]) => total + count, 0);
}
