// Validate the off-repo runtime captures against the frozen oracle and the S3.08 card set.
// node tools/providers/s3-08-receipts.mjs /absolute/path/to/captures
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const folder = process.argv[2];
assert.ok(folder && isAbsolute(folder), 'supply the absolute capture directory');
const oracleBytes = readFileSync(new URL('../../app/src/test/resources/s3-08-endpoints-oracle.json', import.meta.url));
const oracle = JSON.parse(oracleBytes);
const plan = JSON.parse(readFileSync(new URL('../../config/execution-plan.json', import.meta.url)));
const unit = plan.units.find(it => it.id === 'S3.08');
const required = new Set(unit.cardIds.filter(it => !it.endsWith('/common-setup')));
const digest = createHash('sha256');
const expectedNames = new Set();
const receiptCounts = { request: 0, stream: 0, 'error-cancel': 0 };

const chatCases = oracle.cases.filter(it => it.kind === 'chat');
const credited = new Set();
for (const fixture of chatCases) {
  for (const card of Object.keys(receiptCounts)) {
    const file = `s308-${fixture.id}-${card}.json`;
    assert.ok(!expectedNames.has(file), `duplicate capture ${file}`);
    expectedNames.add(file);
    const bytes = readFileSync(join(folder, file));
    const receipt = JSON.parse(bytes);
    assert.deepEqual([receipt.case, receipt.provider, receipt.card, receipt.verified], [fixture.id, fixture.provider, card, true], file);
    assertRecorded(fixture, card, receipt.details);
    receiptCounts[card] += 1;
    // Several synthetic cases exercise one member card; the first receipt per member credits it.
    const cardRef = `S3.08/${fixture.provider}/${card}`;
    if (!credited.has(cardRef)) {
      assert.ok(required.delete(cardRef), `unplanned receipt ${file}`);
      credited.add(cardRef);
    }
    digest.update(`${file}\0${sha256(bytes)}\n`);
  }
}
// Every member must have contributed request, stream and error-cancel receipts.
assert.deepEqual([...required], [], 'planned provider cards missing');

// Explicit-protocol route and responses evidence, plus the model list canary.
for (const fixture of oracle.cases.filter(it => it.kind === 'route')) {
  assertReceipt(join(folder, `s308-${fixture.id}-route.json`), fixture.id, 'route', fixture.provider, {
    protocol: fixture.protocol, adapter: fixture.adapter, explicitNotDowngraded: true,
  });
  expectedNames.add(`s308-${fixture.id}-route.json`);
  digest.update(`s308-${fixture.id}-route.json\0${sha256(readFileSync(join(folder, `s308-${fixture.id}-route.json`)))}\n`);
}
const responses = oracle.cases.find(it => it.kind === 'responses');
assertReceipt(join(folder, `s308-${responses.id}-responses.json`), responses.id, 'responses', responses.provider, {
  path: new URL(responses.request.url).pathname, explicitProtocol: 'openai_responses', noChatFallback: true,
});
expectedNames.add(`s308-${responses.id}-responses.json`);
digest.update(`s308-${responses.id}-responses.json\0${sha256(readFileSync(join(folder, `s308-${responses.id}-responses.json`)))}\n`);

const canary = JSON.parse(readFileSync(join(folder, 's308-model-list-canary.json')));
assert.deepEqual(
  [canary.case, canary.card, canary.verified, canary.details.models, canary.details.requests, canary.details.sorted, canary.details.backupKeyUsed],
  ['model-list-canary', 'model-list', true, oracle.modelLists.openai.models.length, 2, true, true],
  'model list canary receipt',
);
expectedNames.add('s308-model-list-canary.json');
digest.update('s308-model-list-canary.json\0' + sha256(readFileSync(join(folder, 's308-model-list-canary.json'))) + '\n');

assert.deepEqual(
  readdirSync(folder).filter(name => /^s308-.+\.json$/.test(name)).sort(),
  [...expectedNames].sort(),
  'capture set changed',
);
console.log(JSON.stringify({
  unit: 'S3.08', scope: 'isolated-local-http-replay', verifiedProviderCards: credited.size,
  commonSetupNavigation: 'not-run',
  providerCount: new Set(chatCases.map(it => it.provider)).size,
  receiptCounts, desktop: oracle.desktop, oracleSha256: sha256(oracleBytes), receiptSetSha256: digest.digest('hex'),
}, null, 2));

function sha256(bytes) { return createHash('sha256').update(bytes).digest('hex'); }

function assertReceipt(file, id, card, provider, details) {
  const receipt = JSON.parse(readFileSync(file));
  assert.deepEqual([receipt.case, receipt.provider, receipt.card, receipt.verified], [id, provider, card, true], file);
  assert.deepEqual(receipt.details, details, file);
}

function assertRecorded(fixture, card, recorded) {
  if (card === 'request') {
    assert.deepEqual(recorded, {
      path: new URL(fixture.request.url).pathname, method: 'POST', body: fixture.request.body,
    }, fixture.id);
  } else if (card === 'stream') {
    assert.deepEqual(recorded.events, oracle.stream.events, fixture.id);
    assert.equal(recorded.finish, 'TOOL_CALL', fixture.id);
    assert.equal(recorded.doneClosedSocket, true, fixture.id);
  } else {
    assert.deepEqual(recorded.http, { 400: 'PROVIDER_ERROR', 401: 'AUTHENTICATION', 429: 'RATE_LIMITED' }, fixture.id);
    assert.equal(recorded.sseError, 'PROVIDER_ERROR', fixture.id);
    for (const flag of ['sseErrorSocketClosed', 'cancelAfterFirstChunkSocketClosed', 'credentialsErased']) {
      assert.equal(recorded[flag], true, fixture.id);
    }
    assert.equal(recorded.cancelReceivedCompletion, false, fixture.id);
    assert.deepEqual([recorded.preflightNetworkCalls, recorded.preflightCredentialReads], [0, 0], fixture.id);
  }
}
