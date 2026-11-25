import assert from 'node:assert/strict';

export function inspectApplyTrace(raw) {
  const knownDb = new Set(['chat_v2', 'mistakes', 'llm_usage', 'vfs']);
  const knownStatus = new Set(['COMMITTED', 'REPLAYED', 'FAILED', 'CANCELLED']);
  const lines = [];
  const runs = new Map();
  for (const line of raw.split(/\r?\n/u)) {
    if (!/\bevent=sync\.apply\.(?:owner|consume) /u.test(line)) continue;
    const words = line.slice(line.indexOf('event=')).trim().split(/\s+/u);
    const fields = Object.fromEntries(words.map(word => word.split('=')));
    const { event, taskId, correlationId, owner, phase, stage, database, status, elapsedMs } = fields;
    assert.equal(words.length, 9, 'unexpected fields in apply event');
    assert.equal(taskId, 'null');
    assert.match(correlationId ?? '', /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/u);
    assert.equal(owner, 'sync/apply');
    assert.equal(phase, 'DOWNLOAD');
    assert.ok(knownDb.has(database) && knownStatus.has(status));
    assert.equal(stage, event.endsWith('.owner') ? 'APPLY' : 'CONSUME');
    if (stage === 'CONSUME') assert.equal(database, 'vfs');
    assert.match(elapsedMs ?? '', /^\d{1,12}$/u);
    lines.push([event, correlationId, owner, phase, stage, database, status, elapsedMs].join(' '));
    const run = runs.get(correlationId) ?? [];
    run.push({ stage, database, status });
    runs.set(correlationId, run);
  }
  const prefix = ['chat_v2', 'mistakes', 'llm_usage'];
  const has = (ownerStatus, consumption) => [...runs.values()].some(run => {
    for (let i = 0; i < run.length - 3; i++) {
      const prefixMatches = prefix.every((database, offset) => run[i + offset].stage === 'APPLY' &&
        run[i + offset].database === database && run[i + offset].status === ownerStatus);
      const cursor = run[i + 3];
      if (prefixMatches && cursor.stage === 'CONSUME' && cursor.status === consumption) return true;
    }
    return false;
  });
  assert.ok(has('COMMITTED', 'COMMITTED'), 'no correlated four-database commit observed');
  assert.ok(has('COMMITTED', 'FAILED'), 'no correlated failure before cursor observed');
  assert.ok(has('REPLAYED', 'COMMITTED'), 'no correlated receipt replay before cursor observed');
  return { lines, runs: runs.size, events: lines.length, fourDatabaseReplay: true };
}
