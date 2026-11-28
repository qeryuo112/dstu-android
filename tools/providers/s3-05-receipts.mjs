// Match app-level loopback captures against the oracle and all frozen S3.05 cards.
// node tools/providers/s3-05-receipts.mjs /absolute/path/to/captures
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const folder = process.argv[2];
assert.ok(folder && isAbsolute(folder), 'supply the absolute capture directory');
const oracleBytes = readFileSync(new URL('../../app/src/test/resources/s3-05-responses-oracle.json', import.meta.url));
const oracle = JSON.parse(oracleBytes);
const plan = JSON.parse(readFileSync(new URL('../../config/execution-plan.json', import.meta.url)));
const unit = plan.units.find(it => it.id === 'S3.05');
const needed = new Set(unit.cardIds.filter(it => !it.endsWith('/common-setup')));
const digest = createHash('sha256');
const expectedFiles = [];
const counts = { request: 0, stream: 0, 'error-cancel': 0 };
for (const fixture of oracle.cases) {
  const operations = fixture.stream ? Object.keys(counts) : ['request'];
  for (const operation of operations) {
    const file = `s305-${fixture.id}-${operation}.json`;
    expectedFiles.push(file);
    const bytes = readFileSync(join(folder, file));
    const receipt = JSON.parse(bytes);
    assert.deepEqual([receipt.case, receipt.provider, receipt.operation], [fixture.id, fixture.provider, operation], file);
    assertRecorded(fixture, operation, receipt.value);
    counts[operation]++;
    if (fixture.stream) assert.ok(needed.delete(`S3.05/${fixture.provider}/${operation}`), file);
    digest.update(`${file}\0${sha256(bytes)}\n`);
  }
}
assert.deepEqual([...needed], [], 'missing provider cards');
assert.deepEqual(readdirSync(folder).filter(it => /^s305-.+\.json$/.test(it)).sort(), expectedFiles.sort());
console.log(JSON.stringify({
  unit: 'S3.05', scope: 'local-http-replay', verifiedCards: unit.cardIds.length, providerCount: counts.stream,
  counts, desktopRevision: oracle.desktopRevision, oracleSha256: sha256(oracleBytes), receiptSetSha256: digest.digest('hex'),
}, null, 2));

function sha256(bytes) { return createHash('sha256').update(bytes).digest('hex'); }

function assertRecorded(fixture, operation, actual) {
  const expected = {
    request: { path: new URL(fixture.request.url).pathname, method: 'POST', auth: fixture.auth, body: fixture.request.body },
    stream: { events: fixture.stream?.events, peerClosed: true },
    'error-cancel': { http400: true, http401: true, http403: true, http429: true, inlineError: true, preCancelled: true,
      flagCancelsOpenPeer: true, noFalseCompletion: true },
  }[operation];
  assert.deepEqual(actual, expected, `${fixture.id}/${operation}`);
}
