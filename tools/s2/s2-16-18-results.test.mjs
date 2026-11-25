import assert from 'node:assert/strict';
import { test } from 'node:test';
import { inspectPublicationTrace, inspectRemediationTrace, parseInstrumentResults } from './s2-16-18-results.mjs';

const status = (name, code) => `INSTRUMENTATION_STATUS: class=Example\nINSTRUMENTATION_STATUS: test=${name}\nINSTRUMENTATION_STATUS_CODE: ${code}\n`;
const ok = (count = 1) => `OK (${count} ${count === 1 ? 'test' : 'tests'})\nINSTRUMENTATION_CODE: -1\n`;

test('instrumentation receipts require unique selected successes with no skip or failure', () => {
  assert.deepEqual(parseInstrumentResults(status('one', 1) + status('one', 0) + ok(), 'Example#one', 1), ['Example#one']);
  for (const code of [-1, -2, -3, -4]) assert.throws(() => parseInstrumentResults(status('one', code) + ok(), 'Example', 1));
  assert.throws(() => parseInstrumentResults(status('one', 0).repeat(2) + ok(2), 'Example', 2));
  assert.throws(() => parseInstrumentResults(status('one', 0) + ok(), 'Example#other', 1));
});

const line = (id, event, states) => `I/Test: event=${event} owner=module correlationId=00000000-0000-0000-0000-00000000000${id} elapsedMs=1 ${states}`;
const unit16 = [
  ...['chat_v2', 'llm_usage', 'mistakes', 'vfs'].map(db => line(0, 'backup.sync_baseline', `database=${db} state=rebuilt`)),
  line(0, 'backup.restore', 'phase=ACTIVATE state=SUCCEEDED'), line(0, 'backup.identity', 'phase=writer state=rotated'),
];
const unit17 = [line(1, 'backup.encrypted', 'phase=authenticate state=verified'), line(1, 'backup.encrypted', 'phase=schedule state=succeeded'),
  line(1, 'backup.restore', 'phase=VERIFY_RESOURCES state=SUCCEEDED'), line(1, 'backup.restore', 'phase=ACTIVATE state=SUCCEEDED')];
const unit18 = ['none', 'ENCRYPTION_PASSWORD_INCORRECT', 'INVALID_ENCRYPTION_MARKER', 'ENCRYPTION_CLAIM_CONFLICT',
  'CANCELLED', 'ENCRYPTION_UPGRADE_UNCERTAIN'].flatMap((result, index) => [
  line(index + 2, 'sync.configuration.started', 'phase=pair state=started status=none'),
  line(index + 2, 'sync.configuration.finished', `phase=pair state=finished status=${result}`),
]);

test('trace requires event ordering, matching operation IDs and marker failure outcomes', () => {
  const trace = [...unit16, ...unit17, ...unit18].join('\n');
  assert.equal(inspectRemediationTrace(trace).correlatedOperations, 8);
  assert.throws(() => inspectRemediationTrace([...unit16].reverse().concat(unit17, unit18).join('\n')), /S2\.16/u);
  assert.throws(() => inspectRemediationTrace(trace.replace(unit17[0], unit17[0].replace('00001 elapsed', '00009 elapsed'))), /S2\.17/u);
  assert.throws(() => inspectRemediationTrace(trace.replace('status=CANCELLED', 'status=none')), /S2\.18/u);
  assert.throws(() => inspectRemediationTrace(trace.replace('database=vfs', 'database=vfs url=http://example.invalid')), /sensitive/u);
});

const publicationId = '00000000-0000-0000-0000-000000000099';
const publicationTrace = ['RESERVE', 'READBACK', 'ACKNOWLEDGE'].map((stage, index) =>
  `I/DstuOperation: event=sync.publication taskId=null correlationId=${publicationId} owner=sync ` +
  `stage=${stage} status=ok elapsedMs=${index + 1} category=incremental`).concat(
  `I/DstuOperation: event=sync.transfer.finished taskId=null correlationId=${publicationId} owner=sync ` +
  'stage=COMPLETE status=none phase=COMPLETE elapsedMs=4').join('\n');

test('publication evidence checks actual LogRecord correlation, order, time and privacy', () => {
  assert.equal(inspectPublicationTrace(publicationTrace).publishedOperations, 1);
  assert.equal(inspectPublicationTrace(publicationTrace.replace('stage=RESERVE', 'stage=REPLAY')).checkedEvents, 4);
  assert.throws(() => inspectPublicationTrace(publicationTrace.replace(publicationId, 'null')), /correlation missing/u);
  assert.throws(() => inspectPublicationTrace(publicationTrace.replace('stage=READBACK', 'stage=ACKNOWLEDGE')), /read back/u);
  assert.throws(() => inspectPublicationTrace(publicationTrace.replace('elapsedMs=2', 'elapsedMs=0')), /timings/u);
  assert.throws(() => inspectPublicationTrace(publicationTrace.replace('status=ok', 'status=ok password=secret')), /unexpected fields/u);
  assert.throws(() => inspectPublicationTrace(publicationTrace.replace('phase=COMPLETE', 'phase=FAILED')), /successful transfer/u);
});
