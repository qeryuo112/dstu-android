import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectApplyTrace } from './s2-30-results.mjs';

const dbs = ['chat_v2', 'mistakes', 'llm_usage'];
const line = (correlation, database, status, stage) => {
  const id = `11111111-1111-1111-1111-11111111111${correlation}`;
  return `I/DstuOperation: event=sync.apply.${stage === 'APPLY' ? 'owner' : 'consume'} taskId=null ` +
    `correlationId=${id} phase=DOWNLOAD status=${status} database=${database} stage=${stage} owner=sync/apply elapsedMs=0`;
};
const replay = (correlation, owners, consume) => [
  ...dbs.map(database => line(correlation, database, owners, 'APPLY')),
  line(correlation, 'vfs', consume, 'CONSUME'),
];
const success = replay(1, 'COMMITTED', 'COMMITTED');
const failed = replay(2, 'COMMITTED', 'FAILED');
const retried = replay(3, 'REPLAYED', 'COMMITTED');

test('apply receipt requires success, failure before consume, and an independently correlated replay', () => {
  const audit = inspectApplyTrace([...success, ...failed, ...retried].join('\n'));
  assert.deepEqual({ runs: audit.runs, events: audit.events, fourDatabaseReplay: audit.fourDatabaseReplay },
    { runs: 3, events: 12, fourDatabaseReplay: true });
  assert.throws(() => inspectApplyTrace([...success, ...retried].join('\n')), /before cursor/iu);
  assert.throws(() => inspectApplyTrace([...success, ...failed, ...retried.slice().reverse()].join('\n')), /receipt replay/iu);
  assert.throws(() => inspectApplyTrace([...success, ...failed, ...retried, `${retried[0]} unsafe=excess`].join('\n')), /unexpected fields/iu);
});
