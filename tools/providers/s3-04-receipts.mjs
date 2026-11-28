// Validate the off-repo runtime captures against the frozen oracle and current card set.
// node tools/providers/s3-04-receipts.mjs /absolute/path/to/captures
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const folder = process.argv[2];
assert.ok(folder && isAbsolute(folder), 'supply the absolute capture directory');
const oracleBytes = readFileSync(new URL('../../app/src/test/resources/s3-04-chat-oracle.json', import.meta.url));
const oracle = JSON.parse(oracleBytes);
const plan = JSON.parse(readFileSync(new URL('../../config/execution-plan.json', import.meta.url)));
const unit = plan.units.find(it => it.id === 'S3.04');
const required = new Set(unit.cardIds.filter(it => !it.endsWith('/common-setup')));
const digest = createHash('sha256');
const expectedNames = new Set();
const receiptCounts = { request: 0, stream: 0, 'error-cancel': 0 };
for (const fixture of oracle.cases) {
  const cards = fixture.stream ? Object.keys(receiptCounts) : ['request'];
  for (const card of cards) {
    const file = `s304-${fixture.id}-${card}.json`;
    assert.ok(!expectedNames.has(file), `duplicate capture ${file}`);
    expectedNames.add(file);
    const bytes = readFileSync(join(folder, file));
    const receipt = JSON.parse(bytes);
    assert.deepEqual([receipt.case, receipt.provider, receipt.card, receipt.verified], [fixture.id, fixture.provider, card, true], file);
    assertRecorded(fixture, card, receipt.details);
    receiptCounts[card] += 1;
    if (fixture.stream) assert.ok(required.delete(`S3.04/${fixture.provider}/${card}`), `unplanned primary receipt ${file}`);
    digest.update(`${file}\0${sha256(bytes)}\n`);
  }
}
assert.deepEqual([...required], [], 'planned provider cards missing');
assert.deepEqual(readdirSync(folder).filter(name => /^s304-.+\.json$/.test(name)).sort(), [...expectedNames].sort(), 'capture set changed');
console.log(JSON.stringify({
  unit: 'S3.04', scope: 'local-http-replay', verifiedCards: unit.cardIds.length, providerCount: oracle.cases.filter(it => it.stream).length,
  receiptCounts, desktop: oracle.desktop, oracleSha256: sha256(oracleBytes), receiptSetSha256: digest.digest('hex'),
}, null, 2));

function sha256(bytes) { return createHash('sha256').update(bytes).digest('hex'); }

function assertRecorded(fixture, card, recorded) {
  if (card === 'request') {
    assert.deepEqual(recorded, {
      path: new URL(fixture.request.url).pathname, method: 'POST', auth: 'bearer_api_key', body: fixture.request.body,
    }, fixture.id);
  } else if (card === 'stream') {
    assert.deepEqual(recorded, { events: fixture.stream.events, finish: 'TOOL_CALL', doneClosedSocket: true }, fixture.id);
  } else {
    assert.deepEqual(recorded, {
      http: { 400: 'PROVIDER_ERROR', 401: 'AUTHENTICATION', 429: 'RATE_LIMITED' },
      sseError: 'PROVIDER_ERROR', sseErrorSocketClosed: true,
      cancelAfterFirstChunkSocketClosed: true, cancelReceivedCompletion: false,
      credentialsErased: true,
      preflightNetworkCalls: 0, preflightCredentialReads: 0,
    }, fixture.id);
  }
}
