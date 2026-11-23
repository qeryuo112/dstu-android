import assert from 'node:assert/strict';

export function parseInstrumentResults(raw, selection, expected) {
  const output = raw.replaceAll('\r', '');
  assert.ok(!/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|^INSTRUMENTATION_STATUS_CODE: -\d+$/mu.test(output) &&
    /^INSTRUMENTATION_CODE: -1$/mu.test(output) && new RegExp(`^OK \\(${expected} tests?\\)$`, 'mu').test(output),
  'instrumentation failed or skipped; inspect the private instrumentation output');
  const results = [];
  let currentClass;
  let method;
  for (const line of output.split('\n')) {
    if (line.startsWith('INSTRUMENTATION_STATUS: class=')) currentClass = line.split('=')[1];
    if (line.startsWith('INSTRUMENTATION_STATUS: test=')) method = line.split('=')[1];
    if (line === 'INSTRUMENTATION_STATUS_CODE: 0') {
      assert.ok(currentClass && method, 'test completion without identity');
      results.push(`${currentClass}#${method}`);
      currentClass = method = undefined;
    }
  }
  assert.equal(new Set(results).size, expected, 'wrong number of unique passed cases');
  assert.equal(results.length, expected, 'wrong number of passed cases');
  const selected = selection.split(',');
  assert.ok(results.every(result => selected.includes(result) || selected.includes(result.split('#')[0])), 'unselected case executed');
  return results.sort();
}

const fields = line => Object.fromEntries(line.trim().split(/\s+/u).filter(word => word.includes('='))
  .map(word => word.split('=')));
const kind = (event, phase, state) => row => row.event === event && row.phase === phase && row.state === state;

export function inspectRemediationTrace(log) {
  const lines = log.split('\n').filter(line => /\bevent=(?:backup\.(?:encrypted|identity|queue|restore|sync_baseline)|sync\.configuration\.(?:started|cancelled|finished)) /u.test(line));
  const groups = new Map();
  for (const line of lines) {
    assert.ok(!/https?:\/\/|public-fixture-password|fixture-webdav|public-s217-fixture/u.test(line), 'trace contains sensitive fixture fields');
    const row = fields(line);
    assert.match(row.correlationId ?? '', /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/u);
    assert.match(row.owner ?? '', /^[a-z0-9./-]+$/iu);
    assert.match(row.elapsedMs ?? '', /^\d+$/u);
    assert.match(row.state ?? '', /^[a-z0-9_]+$/iu);
    const group = groups.get(row.correlationId) ?? [];
    group.push(row);
    groups.set(row.correlationId, group);
  }
  const operations = [...groups.values()];
  const activated = kind('backup.restore', 'ACTIVATE', 'SUCCEEDED');
  const rotated = kind('backup.identity', 'writer', 'rotated');
  const authenticated = kind('backup.encrypted', 'authenticate', 'verified');
  const queued = kind('backup.encrypted', 'schedule', 'succeeded');
  assert.ok(operations.some(group => {
    const active = group.findIndex(activated);
    if (active < 0 || group.findIndex(rotated) <= active) return false;
    const rebuilt = group.slice(0, active).filter(row => row.event === 'backup.sync_baseline' && row.state === 'rebuilt');
    return rebuilt.map(row => row.database).sort().join(',') === 'chat_v2,llm_usage,mistakes,vfs';
  }), 'S2.16 trace must show four baseline resets before activation and writer rotation');
  assert.ok(operations.some(group => {
    const auth = group.findIndex(authenticated), queue = group.findIndex(queued);
    const verified = group.findIndex(kind('backup.restore', 'VERIFY_RESOURCES', 'SUCCEEDED'));
    return auth >= 0 && queue > auth && verified > queue && group.findIndex(activated) > verified;
  }), 'S2.17 trace must link authentication, queue, resource validation and activation');
  const statuses = operations.filter(group => group[0].event === 'sync.configuration.started' && group[0].phase === 'pair')
    .flatMap(group => group.filter(row => row.event === 'sync.configuration.finished' || row.event === 'sync.configuration.cancelled'))
    .map(row => row.status);
  for (const expected of ['none', 'ENCRYPTION_PASSWORD_INCORRECT', 'INVALID_ENCRYPTION_MARKER', 'ENCRYPTION_CLAIM_CONFLICT',
    'CANCELLED', 'ENCRYPTION_UPGRADE_UNCERTAIN']) assert.ok(statuses.includes(expected), `S2.18 missing trace outcome ${expected}`);
  return { lines, correlatedOperations: groups.size, pairingOutcomes: [...new Set(statuses)].sort() };
}
