import assert from 'node:assert/strict';
import { test } from 'node:test';
import { requireRemoteObjectOperations } from './verify-saf-device.mjs';

const event = (phase, status, state = 'responded') => ({ phase, state, status });

test('S2.07 remote verification requires actual upload, stat, readback and deletion', () => {
  const evidence = [event('PUT', 201), event('HEAD', 200), event('GET', 200), event('DELETE', 204)];
  assert.deepEqual(requireRemoteObjectOperations(evidence), {
    'PUT:201:responded': 1, 'HEAD:200:responded': 1, 'GET:200:responded': 1, 'DELETE:204:responded': 1,
  });
  assert.throws(() => requireRemoteObjectOperations(evidence.filter(({ phase }) => phase !== 'GET')), /GET:200/);
  assert.throws(() => requireRemoteObjectOperations([event('PUT', 201, 'aborted'), ...evidence.slice(1)]), /PUT:201/);
});
