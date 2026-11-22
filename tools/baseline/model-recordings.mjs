import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { artifact, log, main, readJson, root, sha256 } from './lib.mjs';
import { loadDesktopModule } from './desktop-module.mjs';

const UNIT = 'S0.17';
const CORPUS = 'fixtures/model-streams';
const ARTIFACT = 'config/baseline/model-recordings.json';
const COMMIT = 'a1493f92a7cd4399d57046aadc943aeadda5b8e7';
const TEST_FILE_SHA256 = '1f4b71f04632425350b85ef0ac128f7976c58dcef11725d8babd1eeee321d9f1';
const LICENSE_SHA256 = 'd711ed37bb19ae7249af8d093b8fc6ea758fd3554b92ac61500c1ad3cc0e3fb9';
const SPLITS = [
  { id: 'whole', size: null },
  { id: 'bytes-1', size: 1 },
  { id: 'bytes-7', size: 7 },
];
const FIXTURES = [
  {
    id: 'openai-python:test_parse_nothing',
    file: `${CORPUS}/openai-python-v1.109.1/e2aad469b71d1d4894ff833ea147020a9d875eb7ce644a0ff355581690a4cbfd.sse`,
    sha256: 'e2aad469b71d1d4894ff833ea147020a9d875eb7ce644a0ff355581690a4cbfd',
    bytes: 8761,
    upstreamTest: 'test_parse_nothing',
    upstreamFile: '.inline-snapshot/external/e2aad469b71d1d4894ff833ea147020a9d875eb7ce644a0ff355581690a4cbfd.bin',
    contentKind: 'assistant-text',
    expected: {
      text: "I'm unable to provide real-time weather updates. To get the current weather in San Francisco, I recommend checking a reliable weather website or a weather app.",
      reasoning: '',
      toolArguments: '',
      usage: { prompt_tokens: 14, completion_tokens: 30, total_tokens: 44, completion_tokens_details: { reasoning_tokens: 0 } },
      eventTypes: ['ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'Usage', 'Done'],
    },
  },
  {
    id: 'openai-python:test_parse_pydantic_model',
    file: `${CORPUS}/openai-python-v1.109.1/7e5ea4d12e7cc064399b6631415e65923f182256b6e6b752950a3aaa2ad2320a.sse`,
    sha256: '7e5ea4d12e7cc064399b6631415e65923f182256b6e6b752950a3aaa2ad2320a',
    bytes: 4537,
    upstreamTest: 'test_parse_pydantic_model',
    upstreamFile: '.inline-snapshot/external/7e5ea4d12e7cc064399b6631415e65923f182256b6e6b752950a3aaa2ad2320a.bin',
    contentKind: 'json-text',
    expected: {
      text: '{"city":"San Francisco","temperature":61,"units":"f"}',
      reasoning: '',
      toolArguments: '',
      usage: { prompt_tokens: 79, completion_tokens: 14, total_tokens: 93, completion_tokens_details: { reasoning_tokens: 0 } },
      eventTypes: ['ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'ContentChunk', 'Usage', 'Done'],
    },
  },
  {
    id: 'openai-python:test_parse_strict_tools',
    file: `${CORPUS}/openai-python-v1.109.1/a247c49c5fcd492bfb7a02a3306ad615ed8d8f649888ebfddfbc3ee151f44d46.sse`,
    sha256: 'a247c49c5fcd492bfb7a02a3306ad615ed8d8f649888ebfddfbc3ee151f44d46',
    bytes: 4046,
    upstreamTest: 'test_parse_strict_tools',
    upstreamFile: '.inline-snapshot/external/a247c49c5fcd492bfb7a02a3306ad615ed8d8f649888ebfddfbc3ee151f44d46.bin',
    contentKind: 'tool-call',
    expected: {
      text: '',
      reasoning: '',
      toolArguments: '{"city":"San Francisco","state":"CA"}',
      usage: { prompt_tokens: 48, completion_tokens: 19, total_tokens: 67, completion_tokens_details: { reasoning_tokens: 0 } },
      eventTypes: ['ToolCall', 'ToolCall', 'ToolCall', 'ToolCall', 'ToolCall', 'ToolCall', 'ToolCall', 'ToolCall', 'ToolCall', 'ToolCall', 'ToolCall', 'Usage', 'Done'],
    },
  },
];
const PROVENANCE = `${CORPUS}/openai-python-v1.109.1/PROVENANCE.json`;

function eventSummary(events) {
  const text = events.filter(event => event.type === 'ContentChunk').map(event => event.value).join('');
  const reasoning = events.filter(event => event.type === 'ReasoningChunk').map(event => event.value).join('');
  const toolArguments = [];
  const toolNames = [];
  const toolIds = [];
  for (const event of events) {
    if (event.type !== 'ToolCall') continue;
    const fn = event.value?.function;
    if (typeof fn?.name === 'string' && fn.name !== '') toolNames.push(fn.name);
    if (typeof event.value?.id === 'string' && event.value.id !== '') toolIds.push(event.value.id);
    if (typeof fn?.arguments === 'string' && fn.arguments !== '') toolArguments.push(fn.arguments);
  }
  const usage = events.filter(event => event.type === 'Usage').map(event => event.value);
  return {
    eventTypes: events.map(event => event.type),
    events,
    text,
    reasoning,
    toolNames,
    toolIds,
    toolArguments,
    toolArgumentsJoined: toolArguments.join(''),
    usage: usage.length === 0 ? null : usage.length === 1 ? usage[0] : usage,
    doneCount: events.filter(event => event.type === 'Done').length,
    terminalDone: events.at(-1)?.type === 'Done',
    textSha256: sha256(Buffer.from(text)),
    toolArgumentsSha256: sha256(Buffer.from(toolArguments.join(''))),
    toolCallsSha256: sha256(Buffer.from(JSON.stringify(events.filter(event => event.type === 'ToolCall')))),
    eventsSha256: sha256(Buffer.from(JSON.stringify(events))),
  };
}

function splitBytes(bytes, size) {
  if (size === null) return [bytes];
  const chunks = [];
  for (let offset = 0; offset < bytes.length; offset += size) chunks.push(bytes.subarray(offset, offset + size));
  return chunks;
}

function replay(classes, bytes, size) {
  const buffer = new classes.SseEventBuffer();
  const blocks = [];
  for (const chunk of splitBytes(bytes, size)) blocks.push(...buffer.processBytes(chunk));
  blocks.push(...buffer.flush());
  const adapter = new classes.OpenAIAdapter();
  const events = [];
  for (const block of blocks) events.push(...adapter.parseStream(block));
  events.push(...adapter.finishStream());
  return { blocks, events, summary: eventSummary(events) };
}

function verifyFixtureBytes(bytes, fixture) {
  assert.ok(Buffer.isBuffer(bytes), `${fixture.id}: fixture is not bytes`);
  assert.equal(bytes.length, fixture.bytes, `${fixture.id}: byte count drift`);
  assert.equal(sha256(bytes), fixture.sha256, `${fixture.id}: SHA-256 drift`);
}

function verifyFixtureEnvelope(bytes, fixture) {
  assert.ok(Buffer.isBuffer(bytes), `${fixture.id}: fixture is not bytes`);
  assert.ok(bytes.subarray(0, 6).equals(Buffer.from('data: ')), `${fixture.id}: not an upstream SSE data frame`);
  const doneTail = Buffer.from('data: [DONE]\n\n');
  assert.ok(bytes.subarray(-doneTail.length).equals(doneTail), `${fixture.id}: missing canonical upstream DONE tail`);
  const text = bytes.toString('utf8');
  assert.ok(!text.includes('\uFFFD'), `${fixture.id}: invalid UTF-8 in upstream bytes`);
  assert.equal(Buffer.from(text, 'utf8').compare(bytes), 0, `${fixture.id}: UTF-8 round trip changed bytes`);
}

function verifyFixtureIntegrity(bytes, fixture) {
  verifyFixtureBytes(bytes, fixture);
  verifyFixtureEnvelope(bytes, fixture);
}

function expectedComparable(summary) {
  return {
    eventTypes: summary.eventTypes,
    text: summary.text,
    reasoning: summary.reasoning,
    toolArguments: summary.toolArgumentsJoined,
    usage: summary.usage,
    doneCount: summary.doneCount,
    terminalDone: summary.terminalDone,
    textSha256: summary.textSha256,
    toolArgumentsSha256: summary.toolArgumentsSha256,
    eventsSha256: summary.eventsSha256,
  };
}

function fixtureExpectation(fixture) {
  const textHash = sha256(Buffer.from(fixture.expected.text));
  return {
    eventTypes: fixture.expected.eventTypes,
    text: fixture.expected.text,
    reasoning: fixture.expected.reasoning,
    toolArguments: fixture.expected.toolArguments,
    usage: fixture.expected.usage,
    doneCount: 1,
    terminalDone: true,
    textSha256: textHash,
    toolArgumentsSha256: sha256(Buffer.from(fixture.expected.toolArguments)),
  };
}

function assertReplay(fixture, split, result) {
  const expected = fixtureExpectation(fixture);
  const actual = expectedComparable(result.summary);
  for (const [key, value] of Object.entries(expected)) {
    if (key === 'eventsSha256') continue;
    assert.deepEqual(actual[key], value, `${fixture.id}/${split.id}: ${key} mismatch`);
  }
  assert.equal(result.summary.toolArgumentsJoined, fixture.expected.toolArguments, `${fixture.id}/${split.id}: tool arguments mismatch`);
  assert.ok(result.summary.toolNames.every(name => typeof name === 'string' && name.length > 0), `${fixture.id}/${split.id}: empty tool name`);
  assert.equal(result.summary.toolIds.filter(Boolean).length, fixture.contentKind === 'tool-call' ? 1 : 0, `${fixture.id}/${split.id}: tool id count`);
}

function mutate(bytes, mutation) {
  const copy = Buffer.from(bytes);
  if (mutation === 'sse-prefix') {
    copy[0] ^= 0x20;
  } else if (mutation === 'json-byte') {
    const marker = Buffer.from('"choices"');
    const offset = copy.indexOf(marker);
    assert.ok(offset >= 0, 'choices field missing');
    copy[offset + 1] ^= 0x01;
  } else if (mutation === 'done-marker') {
    const marker = Buffer.from('data: [DONE]\n\n');
    const offset = copy.lastIndexOf(marker);
    assert.ok(offset >= 0, 'DONE marker missing');
    copy[offset + 7] = 0x58;
  } else if (mutation === 'truncated-tail') {
    return copy.subarray(0, copy.length - 10);
  } else {
    throw new Error(`unknown mutation ${mutation}`);
  }
  return copy;
}

function expectRejected(mutation, verifier, run) {
  let rejected = false;
  let reason = null;
  try {
    run();
  } catch (error) {
    rejected = true;
    reason = error instanceof Error ? error.message.slice(0, 240) : String(error).slice(0, 240);
  }
  assert.equal(rejected, true, `mutation was accepted: ${mutation}`);
  return { mutation, rejected: true, verifier, reason };
}

function byteCorruptionChecks(fixture, bytes) {
  return [
    ['sse-prefix', 'verifyFixtureEnvelope(header)', verifyFixtureEnvelope],
    ['json-byte', 'verifyFixtureBytes(SHA-256)', verifyFixtureBytes],
    ['done-marker', 'verifyFixtureEnvelope(DONE-tail)', verifyFixtureEnvelope],
    ['truncated-tail', 'verifyFixtureBytes(byte-count)', verifyFixtureBytes],
  ].map(([mutation, verifier, validate]) => {
    const changed = mutate(bytes, mutation);
    const check = expectRejected(mutation, verifier, () => {
      validate(changed, fixture);
    });
    log('model-recordings.corruption', { fixture: fixture.id, ...check });
    return check;
  });
}

function parserBehaviorChecks(classes, fixture, bytes) {
  const doneChanged = replay(classes, mutate(bytes, 'done-marker'), null);
  const truncated = replay(classes, mutate(bytes, 'truncated-tail'), null);
  const changedJson = replay(classes, mutate(bytes, 'json-byte'), null);
  const canonical = replay(classes, bytes, null);
  const result = {
    doneMarkerChanged: {
      terminalDone: doneChanged.summary.terminalDone,
      doneCount: doneChanged.summary.doneCount,
      eventTypesMatchCanonical: doneChanged.summary.eventsSha256 === canonical.summary.eventsSha256,
    },
    tailTruncated: {
      terminalDone: truncated.summary.terminalDone,
      doneCount: truncated.summary.doneCount,
      eventTypesMatchCanonical: truncated.summary.eventsSha256 === canonical.summary.eventsSha256,
    },
    jsonByteChanged: {
      terminalDone: changedJson.summary.terminalDone,
      doneCount: changedJson.summary.doneCount,
      eventTypesMatchCanonical: changedJson.summary.eventsSha256 === canonical.summary.eventsSha256,
    },
  };
  // These are observations of the desktop parser, not fixture-integrity verdicts.
  assert.equal(result.doneMarkerChanged.terminalDone, true);
  assert.equal(result.tailTruncated.terminalDone, true);
  assert.equal(result.jsonByteChanged.terminalDone, true);
  assert.equal(result.jsonByteChanged.eventTypesMatchCanonical, false);
  log('model-recordings.parser-behavior', { fixture: fixture.id, ...result });
  return result;
}

function verifyProvenance() {
  const provenance = readJson(PROVENANCE);
  assert.equal(provenance.commit, COMMIT);
  assert.equal(provenance.tag, 'v1.109.1');
  assert.equal(provenance.testFile, 'tests/lib/chat/test_completions_streaming.py');
  assert.equal(provenance.testFileSha256, TEST_FILE_SHA256);
  assert.equal(provenance.testFileCopy, 'test_completions_streaming.py');
  assert.equal(provenance.testFileCopyBytes, 37830);
  assert.equal(provenance.license.spdx, 'Apache-2.0');
  assert.equal(provenance.license.sha256, LICENSE_SHA256);
  assert.ok(provenance.captureEvidence.includes('auto-generated from the live API'));
  assert.equal(provenance.fixtures.length, FIXTURES.length);
  for (const fixture of FIXTURES) {
    const row = provenance.fixtures.find(candidate => candidate.file === fixture.file.split('/').at(-1));
    assert.ok(row, `${fixture.id}: missing provenance`);
    assert.equal(row.sha256, fixture.sha256);
    assert.equal(row.bytes, fixture.bytes);
    assert.equal(row.upstreamFile, fixture.upstreamFile);
    assert.equal(row.upstreamTest, fixture.upstreamTest);
    assert.equal(row.contentKind, fixture.contentKind);
  }
  const testFile = resolve(root, `${CORPUS}/openai-python-v1.109.1/${provenance.testFileCopy}`);
  const testBytes = readFileSync(testFile);
  assert.equal(testBytes.length, provenance.testFileCopyBytes);
  assert.equal(sha256(testBytes), TEST_FILE_SHA256);
  const testSource = testBytes.toString('utf8');
  const evidenceLine = '# all the snapshots in this file are auto-generated from the live API';
  assert.equal(testSource.split('\n')[provenance.captureEvidenceLine - 1], evidenceLine);
  assert.ok(provenance.captureEvidence.includes('auto-generated from the live API'));
  for (const fixture of FIXTURES) {
    assert.ok(testSource.includes(`def ${fixture.upstreamTest}(`), `${fixture.id}: test name missing from source`);
    const prefix = fixture.sha256.slice(0, 12);
    assert.ok(testSource.includes(`external("${prefix}*.bin")`), `${fixture.id}: snapshot reference missing from source`);
  }
  const licenseFile = resolve(root, `${CORPUS}/openai-python-v1.109.1/LICENSE`);
  assert.equal(sha256(readFileSync(licenseFile)), LICENSE_SHA256);
  log('model-recordings.provenance', {
    repository: provenance.repository,
    tag: provenance.tag,
    commit: provenance.commit,
    testFileSha256: provenance.testFileSha256,
    testFileCopy: provenance.testFileCopy,
    license: provenance.license.spdx,
    licenseSha256: provenance.license.sha256,
    fixtures: provenance.fixtures.length,
  });
  return provenance;
}

function corpusFiles() {
  return readdirSync(resolve(root, CORPUS, 'openai-python-v1.109.1'))
    .filter(name => name.endsWith('.sse'))
    .sort();
}

function verifyCorpusShape() {
  const names = corpusFiles();
  assert.deepEqual(names, FIXTURES.map(fixture => fixture.file.split('/').at(-1)).sort());
  assert.equal(new Set(FIXTURES.map(fixture => fixture.sha256)).size, FIXTURES.length);
}

function buildArtifact(classes, provenance) {
  verifyCorpusShape();
  const recordings = [];
  for (const fixture of FIXTURES) {
    const bytes = readFileSync(resolve(root, fixture.file));
    verifyFixtureIntegrity(bytes, fixture);
    log('model-recordings.fixture', {
      id: fixture.id,
      file: fixture.file,
      bytes: fixture.bytes,
      sha256: fixture.sha256,
      contentKind: fixture.contentKind,
      upstreamTest: fixture.upstreamTest,
      upstreamFile: fixture.upstreamFile,
    });

    const runs = SPLITS.map(split => {
      const result = replay(classes, bytes, split.size);
      assertReplay(fixture, split, result);
      assert.equal(result.blocks.join('\n\n'), bytes.toString('utf8').trimEnd(), `${fixture.id}/${split.id}: event block bytes differ`);
      const row = {
        split: split.id,
        chunkBytes: split.size,
        chunks: splitBytes(bytes, split.size).length,
        events: result.events.length,
        blocks: result.blocks.length,
        blocksSha256: sha256(Buffer.from(JSON.stringify(result.blocks))),
        doneCount: result.summary.doneCount,
        terminalDone: result.summary.terminalDone,
        eventsSha256: result.summary.eventsSha256,
      };
      log('model-recordings.split', {
        fixture: fixture.id,
        ...row,
        text: result.summary.text,
        textSha256: result.summary.textSha256,
        toolArguments: result.summary.toolArgumentsJoined,
        toolArgumentsSha256: result.summary.toolArgumentsSha256,
      });
      return row;
    });
    assert.equal(new Set(runs.map(run => run.eventsSha256)).size, 1, `${fixture.id}: split event sequences differ`);

    const whole = replay(classes, bytes, null);
    const summary = whole.summary;
    recordings.push({
      id: fixture.id,
      source: {
        file: fixture.file,
        sha256: fixture.sha256,
        bytes: fixture.bytes,
        upstreamFile: fixture.upstreamFile,
        upstreamTest: fixture.upstreamTest,
      },
      contentKind: fixture.contentKind,
      expected: {
        eventTypes: fixture.expected.eventTypes,
        text: fixture.expected.text,
        reasoning: fixture.expected.reasoning,
        toolArguments: fixture.expected.toolArguments,
        usage: fixture.expected.usage,
        doneCount: 1,
        terminalDone: true,
        textSha256: sha256(Buffer.from(fixture.expected.text)),
        toolArgumentsSha256: sha256(Buffer.from(fixture.expected.toolArguments)),
      },
      observed: {
        eventTypes: summary.eventTypes,
        text: summary.text,
        reasoning: summary.reasoning,
        toolNames: summary.toolNames,
        toolIds: summary.toolIds,
        toolArguments: summary.toolArguments,
        toolArgumentsJoined: summary.toolArgumentsJoined,
        usage: summary.usage,
        doneCount: summary.doneCount,
        terminalDone: summary.terminalDone,
        textSha256: summary.textSha256,
        toolArgumentsSha256: summary.toolArgumentsSha256,
        toolCallsSha256: summary.toolCallsSha256,
        eventsSha256: summary.eventsSha256,
      },
      splits: runs,
      corruptionRejection: byteCorruptionChecks(fixture, bytes),
      parserBehavior: parserBehaviorChecks(classes, fixture, bytes),
    });
  }

  return {
    formatVersion: 1,
    unit: UNIT,
    format: 'dstu.model-recordings.v1',
    corpus: {
      directory: `${CORPUS}/openai-python-v1.109.1`,
      repository: provenance.repository,
      tag: provenance.tag,
      commit: provenance.commit,
      license: provenance.license,
      testFile: provenance.testFile,
      testFileSha256: provenance.testFileSha256,
      testFileCopy: provenance.testFileCopy,
      testFileCopyBytes: provenance.testFileCopyBytes,
      testFileEvidence: {
        line: provenance.captureEvidenceLine,
        text: '# all the snapshots in this file are auto-generated from the live API',
      },
      archiveSha256: provenance.archive.sha256,
      captureEvidence: provenance.captureEvidence,
      genuineUpstream: true,
      fixtureCount: FIXTURES.length,
      totalBytes: FIXTURES.reduce((total, fixture) => total + fixture.bytes, 0),
    },
    implementation: {
      sse: 'dstu-node/host/platform/util/sse-buffer.ts::SseEventBuffer',
      adapter: 'dstu-node/host/plugins/llm-providers/src/providers/openai-chat.ts::OpenAIAdapter',
      loader: 'tools/baseline/desktop-module.mjs',
      splitBytes: SPLITS.map(split => split.size),
      requiredSplits: ['whole', 'bytes-1', 'bytes-7'],
      assertion: 'byte-identical event block sequence and deep-equal adapter events across every split',
    },
    checks: {
      fixtures: FIXTURES.length,
      splitRuns: FIXTURES.length * SPLITS.length,
      eventEquality: true,
      terminalDone: recordings.every(recording => recording.observed.terminalDone),
      textHashes: recordings.every(recording => recording.observed.textSha256 === recording.expected.textSha256),
      toolArgumentHashes: recordings.every(recording => recording.observed.toolArgumentsSha256 === recording.expected.toolArgumentsSha256),
      corruptionMutations: recordings.reduce((total, recording) => total + recording.corruptionRejection.length, 0),
      corruptionRejected: recordings.every(recording => recording.corruptionRejection.every(check => check.rejected)),
      noCredentials: true,
      paidApiCalls: 0,
    },
    recordings,
    limitations: [
      'Recordings exercise OpenAI Chat Completions text, structured JSON text and strict tool-call SSE; they do not cover reasoning, refusal or logprobs streams.',
      'The corpus was downloaded from public upstream test snapshots; no OpenAI endpoint was contacted by this verifier.',
      'Parser behavior on altered DONE markers or truncated tails is recorded separately from fixture-integrity rejection; finishStream may legitimately emit Done at EOF after a finish_reason.',
      'Passing this harness verifies the desktop TypeScript parser/adapter behavior, not Android runtime or Kotlin parity.',
    ],
  };
}

function verifyArtifact(saved, provenance) {
  assert.equal(saved.formatVersion, 1);
  assert.equal(saved.unit, UNIT);
  assert.equal(saved.format, 'dstu.model-recordings.v1');
  assert.equal(saved.corpus.commit, COMMIT);
  assert.equal(saved.corpus.genuineUpstream, true);
  assert.equal(saved.recordings.length, FIXTURES.length);
  assert.equal(saved.checks.fixtures, FIXTURES.length);
  assert.equal(saved.checks.splitRuns, FIXTURES.length * SPLITS.length);
  assert.equal(saved.checks.eventEquality, true);
  assert.equal(saved.checks.terminalDone, true);
  assert.equal(saved.checks.textHashes, true);
  assert.equal(saved.checks.toolArgumentHashes, true);
  assert.equal(saved.checks.corruptionMutations, FIXTURES.length * 4);
  assert.equal(saved.checks.corruptionRejected, true);
  assert.equal(saved.checks.noCredentials, true);
  assert.equal(saved.checks.paidApiCalls, 0);
  assert.deepEqual(saved.corpus.license, provenance.license);
  for (const recording of saved.recordings) {
    assert.equal(recording.source.sha256, sha256(readFileSync(resolve(root, recording.source.file))));
    assert.equal(recording.splits.length, SPLITS.length);
    assert.equal(new Set(recording.splits.map(run => run.eventsSha256)).size, 1);
    assert.equal(new Set(recording.splits.map(run => run.blocksSha256)).size, 1);
    assert.equal(recording.observed.textSha256, sha256(Buffer.from(recording.observed.text)));
    assert.equal(recording.observed.toolArgumentsSha256, sha256(Buffer.from(recording.observed.toolArgumentsJoined)));
    assert.equal(recording.corruptionRejection.length, 4);
    assert.ok(recording.corruptionRejection.every(check => check.rejected));
    assert.equal(recording.parserBehavior.doneMarkerChanged.terminalDone, true);
    assert.equal(recording.parserBehavior.tailTruncated.terminalDone, true);
  }
}

async function loadClasses() {
  const loaded = await loadDesktopModule(`
import { SseEventBuffer } from './host/platform/util/api.ts';
import { OpenAIAdapter } from './host/plugins/llm-providers/api.ts';
export { SseEventBuffer, OpenAIAdapter };
`);
  const bundleInputsSha256 = sha256(Buffer.from(JSON.stringify(loaded.inputs)));
  log('model-recordings.desktop-module', {
    sse: 'host/platform/util/sse-buffer.ts::SseEventBuffer',
    adapter: 'host/plugins/llm-providers/src/providers/openai-chat.ts::OpenAIAdapter',
    inputs: loaded.inputs.length,
    bundleInputsSha256,
  });
  return loaded.exports;
}

export async function run(mode) {
  const provenance = verifyProvenance();
  const classes = await loadClasses();
  const built = buildArtifact(classes, provenance);
  if (mode === 'capture') {
    artifact(ARTIFACT, built, mode);
  } else {
    const saved = readJson(ARTIFACT);
    verifyArtifact(saved, provenance);
    artifact(ARTIFACT, built, mode);
  }
  log('model-recordings.accepted', {
    unit: UNIT,
    mode,
    fixtures: built.checks.fixtures,
    splitRuns: built.checks.splitRuns,
    eventEquality: built.checks.eventEquality,
    terminalDone: built.checks.terminalDone,
    corruptionMutations: built.checks.corruptionMutations,
    corruptionRejected: built.checks.corruptionRejected,
    paidApiCalls: built.checks.paidApiCalls,
  });
  return built;
}

main(import.meta.url, run);
