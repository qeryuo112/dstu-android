// Rebuild the S3.06 wire fixture using only the locked desktop implementation.
// Synthetic inputs and an in-process stub image fetcher only; this script never sends HTTP.
// node --experimental-strip-types tools/providers/s3-06-oracle.mjs --verify|--write
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(fileURLToPath(new URL('../../', import.meta.url)));
const lock = JSON.parse(readFileSync(join(root, 'config/source-lock.json'), 'utf8')).repositories.find(it => it.id === 'desktop');
const desktop = resolve(dirname(root), lock.path);
const git = (...args) => execFileSync('git', ['-C', desktop, ...args], { encoding: 'utf8' });
const [head, tree] = git('rev-parse', 'HEAD', 'HEAD^{tree}').trim().split('\n');
assert.deepEqual([head, tree], [lock.commit, lock.tree]);
git('diff', '--exit-code', 'HEAD', '--', 'host/plugins/llm', 'host/plugins/llm-providers', 'host/platform');
const frozen = relative => import(pathToFileURL(join(desktop, relative)).href);
const llm = 'host/plugins/llm';
const wire = 'host/plugins/llm-providers/src';
const { applyReasoningConfig } = await frozen(`${llm}/src/question_authoring.ts`);
const { applyGenerationParams } = await frozen(`${llm}/src/generation.ts`);
const { resolveQuirks } = await frozen(`${llm}/src/provider_quirks.ts`);
const { computeReasoningStateScope } = await frozen(`${llm}/src/reasoning_state_scope.ts`);
const { requestAdapterIdForConfig } = await frozen(`${llm}/src/protocol.ts`);
const { preparePlainProviderRequest } = await frozen(`${llm}/src/prepared_request.ts`);
const { makeConfig } = await frozen(`${llm}/tests/support.ts`);
const { AnthropicAdapter } = await frozen(`${wire}/providers/anthropic.ts`);
const { setRemoteImageFetcher } = await frozen(`${wire}/media/remote-image.ts`);

const token = 's306-synthetic-key';
const sessionId = 'fixture-s306-session';
const configuredHeaders = { 'x-fixture-provider': 's306', 'content-type': 'text/plain' };
const ephemeral = { type: 'ephemeral' };
const ephemeralTtl = { type: 'ephemeral', ttl: '1h' };
const imageB64 = 'iVBORw0KGgo=';

const standardTool = name => ({ type: 'function', function: {
  name, description: `Synthetic ${name} tool`,
  parameters: { type: 'object', properties: { q: { type: 'string' } } },
} });

const system = { role: 'system', content: 'Fixture instructions. 保持简洁。' };
const user = { role: 'user', content: '总结错题 🧪' };
const plainMessages = () => [structuredClone(system), structuredClone(user)];

const thinkingPart = { type: 'thinking', thinking: '先调用检索工具', signature: 'sig_round_1' };
const redactedPart = { type: 'redacted_thinking', data: 'opaque_payload' };
const replayHistory = () => [
  structuredClone(system),
  structuredClone(user),
  {
    role: 'assistant',
    content: [structuredClone(thinkingPart), structuredClone(redactedPart), { type: 'tool_use', id: 'toolu_1', name: 'lookup_notes', input: { q: '错题' } }, { type: 'text', text: '找到了 3 条。' }],
  },
  { role: 'tool', tool_call_id: 'toolu_1', content: '共 3 道错题' },
  { role: 'user', content: '整理成卡片' },
];

// stripPrivateState direction for an off-target replay: signatures/redacted drop,
// thinking shows as text, and regular content (text/tool_use) stays in place.
const offTargetHistory = () => replayHistory().map((message, index) => index !== 2 ? message : {
  role: 'assistant',
  content: [
    { type: 'text', text: thinkingPart.thinking },
    { type: 'tool_use', id: 'toolu_1', name: 'lookup_notes', input: { q: '错题' } },
    { type: 'text', text: '找到了 3 条。' },
  ],
});

const defaults = {
  isReasoning: true, supportsReasoning: true, supportsTools: true, thinkingEnabled: true,
  reasoningEffort: 'high', thinkingBudget: 512, maxOutputTokens: 2048, maxTokensLimit: 1536,
  temperature: 0.25, topPOverride: 0.75, frequencyPenaltyOverride: 0.125, presencePenaltyOverride: 0.25,
};

const cases = [
  { id: 'manual-thinking-tools', model: 'claude-sonnet-4-5-20250929', baseUrl: 'https://api.anthropic.com/v1',
    messages: replayHistory(), tools: [standardTool('lookup_notes')], stream: null },
  { id: 'adaptive-effort', model: 'claude-opus-4-8', baseUrl: 'https://api.anthropic.com/v1',
    messages: plainMessages(), tools: [standardTool('lookup_notes')] },
  { id: 'adaptive-budget-derived', model: 'claude-fable-5', baseUrl: 'https://api.anthropic.com/v1',
    messages: plainMessages(),
    config: { reasoningEffort: null, thinkingBudget: 20000, includeThoughts: true } },
  { id: 'legacy-sampling', model: 'claude-3-7-sonnet', baseUrl: 'https://api.anthropic.com/v1',
    messages: plainMessages(),
    config: { isReasoning: false, supportsReasoning: false, thinkingEnabled: false, reasoningEffort: null, thinkingBudget: null, topK: 40 } },
  { id: 'structured-output', model: 'claude-sonnet-4-5-20250929', baseUrl: 'https://api.anthropic.com/v1',
    messages: plainMessages(),
    config: { isReasoning: false, supportsReasoning: false, thinkingEnabled: false, reasoningEffort: null, thinkingBudget: null },
    options: { response_format: { type: 'json_schema', json_schema: { name: 'answer', schema: { type: 'object', properties: {} }, strict: true } } } },
  { id: 'cache-breakpoints', model: 'claude-sonnet-5', baseUrl: 'https://api.anthropic.com/v1',
    messages: plainMessages(),
    tools: [
      { type: 'function', cache_control: structuredClone(ephemeral), function: { name: 'tool_one', parameters: { type: 'object' } } },
      { type: 'function', cache_control: structuredClone(ephemeralTtl), function: { name: 'tool_two', parameters: { type: 'object' } } },
      { type: 'function', cache_control: structuredClone(ephemeral), function: { name: 'tool_three', parameters: { type: 'object' } } },
      { type: 'function', cache_control: structuredClone(ephemeral), function: { name: 'tool_four', parameters: { type: 'object' } } },
    ] },
  { id: 'images', model: 'claude-sonnet-4-5-20250929', baseUrl: 'https://api.anthropic.com/v1',
    messages: [{ role: 'user', content: [
      { type: 'text', text: '看这张图' },
      { type: 'image_url', image_url: { url: `data:image/png;base64,${imageB64}` } },
      { type: 'image_url', image_url: { url: 'https://example.com/cat.png' } },
      { type: 'image_url', image_url: { url: 'https://example.com/missing.png' } },
    ] }],
    config: { isReasoning: false, supportsReasoning: false, thinkingEnabled: false, reasoningEffort: null, thinkingBudget: null } },
  { id: 'replay-turn2', model: 'claude-fable-5', baseUrl: 'https://api.anthropic.com/v1',
    messages: replayHistory(), tools: [standardTool('lookup_notes')], stream: null },
  { id: 'offtarget-replay', model: 'claude-sonnet-5', baseUrl: 'https://api.anthropic.com/v1',
    messages: offTargetHistory(), tools: [standardTool('lookup_notes')],
    config: { reasoningEffort: null, thinkingBudget: null } },
  { id: 'branch-bare-base', model: 'claude-haiku-4-5', baseUrl: 'https://relay.fixture',
    messages: plainMessages(),
    config: { isReasoning: false, supportsReasoning: false, thinkingEnabled: false, reasoningEffort: null, thinkingBudget: null } },
];

const startUsage = { input_tokens: 123, output_tokens: 0, cache_read_input_tokens: 900, cache_creation_input_tokens: 50 };
const USAGE_KINDS = { ContentChunk: 'text', ReasoningChunk: 'reasoning', ToolCall: 'tool', ThoughtSignature: 'thought_signature' };
const textFrame = (text, index) => ({ type: 'content_block_delta', index, delta: { type: 'text_delta', text } });
const thinkingFrame = (text, index) => ({ type: 'content_block_delta', index, delta: { type: 'thinking_delta', thinking: text } });
const signatureFrame = (signature, index) => ({ type: 'content_block_delta', index, delta: { type: 'signature_delta', signature } });
const stopFrame = index => ({ type: 'content_block_stop', index });
const startTool = (index, id, name, input = {}) => ({ type: 'content_block_start', index, content_block: { type: 'tool_use', id, name, input } });
const argsFrame = (json, index) => ({ type: 'content_block_delta', index, delta: { type: 'input_json_delta', partial_json: json } });
const deltaFrame = (delta) => ({ type: 'message_delta', delta });

const streams = {
  'complete-thinking-tool': {
    model: 'claude-fable-5', baseUrl: 'https://api.anthropic.com/v1',
    frames: [
      { data: { type: 'message_start', message: { id: 'msg_1', usage: structuredClone(startUsage) } } },
      { data: { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '' } } },
      { data: thinkingFrame('需要工具', 0) },
      { data: signatureFrame('EqQBCg', 0) },
      { data: signatureFrame('XYZ123', 0) },
      { data: stopFrame(0) },
      { data: textFrame('查一下', 1) },
      { data: stopFrame(1) },
      { data: startTool(2, 'toolu_1', 'lookup_notes') },
      { data: argsFrame('{"q"', 2) },
      { data: argsFrame(':"北京"}', 2) },
      { data: stopFrame(2) },
      { data: deltaFrame({ stop_reason: 'end_turn', usage: { output_tokens: 42 } }) },
      { data: { type: 'message_stop' } },
    ],
    androidResult: 'complete', androidText: '查一下', androidStop: 'TOOL_CALL',
    deviation: null,
  },
  refusal: {
    model: 'claude-opus-4-8', baseUrl: 'https://api.anthropic.com/v1',
    frames: [
      { data: { type: 'message_start', message: { id: 'msg_2', usage: { input_tokens: 10, output_tokens: 0 } } } },
      { data: textFrame('无法', 0) },
      { data: deltaFrame({ stop_reason: 'refusal', stop_details: { category: 'cyber' } }) },
      { data: { type: 'message_stop' } },
    ],
    androidResult: 'fail:PROVIDER_ERROR', androidText: null, androidStop: null, androidKeep: 2,
    deviation: 'Android maps refusal/safety stop reasons to a fixed PROVIDER_ERROR end; the desktop emits SafetyBlocked and keeps the stream Done-able.',
  },
  'stream-error': {
    model: 'claude-fable-5', baseUrl: 'https://api.anthropic.com/v1',
    frames: [{ data: { type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } } }],
    androidResult: 'fail:PROVIDER_ERROR', androidText: null, androidStop: null, androidKeep: 0,
    deviation: 'Android ends with PROVIDER_ERROR and never logs or forwards the upstream error detail.',
  },
  'context-window': {
    model: 'claude-fable-5', baseUrl: 'https://api.anthropic.com/v1',
    frames: [
      { data: textFrame('部分', 0) },
      { data: deltaFrame({ stop_reason: 'model_context_window_exceeded' }) },
    ],
    androidResult: 'fail:STREAM_TRUNCATED', androidText: null, androidStop: null, androidKeep: 1,
    deviation: 'Android maps the context-window stop reason to STREAM_TRUNCATED instead of a provider_error block.',
  },
  'truncated-tool': {
    model: 'claude-sonnet-4-5-20250929', baseUrl: 'https://api.anthropic.com/v1',
    frames: [
      { data: textFrame('调用工具', 0) },
      { data: startTool(1, 'toolu_2', 'lookup_notes') },
      { data: argsFrame('{"q":"北', 1) },
      { data: stopFrame(1) },
      { data: deltaFrame({ stop_reason: 'tool_use' }) },
      { data: { type: 'message_stop' } },
    ],
    androidResult: 'fail:PROVIDER_ERROR', androidText: null, androidStop: null, androidKeep: 1,
    deviation: 'The desktop wire fallback completes truncated arguments as an executable {}; the Android port fails instead of arming an unverifiable tool call.',
  },
  'missing-tool-id': {
    model: 'claude-sonnet-4-5-20250929', baseUrl: 'https://api.anthropic.com/v1',
    frames: [
      { data: startTool(0, '', 'lookup_notes', { q: 'x' }) },
      { data: stopFrame(0) },
      { data: deltaFrame({ stop_reason: 'tool_use' }) },
      { data: { type: 'message_stop' } },
    ],
    androidResult: 'fail:PROVIDER_ERROR', androidText: null, androidStop: null, androidKeep: 0,
    deviation: 'A tool_use block without an id never becomes a completed call on Android.',
  },
  'done-marker': {
    model: 'claude-haiku-4-5', baseUrl: 'https://api.anthropic.com/v1',
    frames: [
      { data: textFrame('直接回答', 0) },
      { data: '[DONE]' },
    ],
    androidResult: 'complete', androidText: '直接回答', androidStop: 'STOP',
    deviation: null,
  },
};

const artifact = {
  version: 1,
  desktopRevision: head,
  desktopTree: tree,
  token,
  codexAccount: null,
  configuredHeaders,
  // Controlled image injection mirror of the desktop test stub; never fetched over the network.
  imageCache: { 'https://example.com/cat.png': { media_type: 'image/png', data: 'AQID' } },
  cases: await Promise.all(cases.map(makeRequestCase)),
};
for (const [id, stream] of Object.entries(streams)) artifact.cases.push(await makeStreamCase(id, stream));

const output = join(root, 'app/src/test/resources/s3-06-anthropic-oracle.json');
const serialized = `${JSON.stringify(artifact, null, 2)}\n`;
if (process.argv[2] === '--write') writeFileSync(output, serialized);
else if (process.argv[2] === '--verify') assert.equal(readFileSync(output, 'utf8'), serialized, 'S3.06 frozen oracle drift');
else throw new Error('use --verify or --write');
console.log(`S3.06 frozen desktop ${head}: ${artifact.cases.filter(it => it.request).length} requests, ${artifact.cases.filter(it => it.stream).length} event sequences`);
for (const item of artifact.cases) console.log(item.id, item.adapter, item.request?.url ?? '', item.stream ? item.stream.androidResult : '');

async function makeRequestCase(test) {
  const { id, model, baseUrl, messages, options = {} } = test;
  const input = { ...defaults, ...test.config, model, baseUrl, modelAdapter: 'anthropic', extraBody: options };
  const config = makeConfig({ ...input, providerType: 'anthropic', providerScope: 'anthropic', apiProtocol: 'anthropic_messages',
    authMode: 'x_api_key', enabled: true, apiKey: token, headers: configuredHeaders });
  const adapter = new AnthropicAdapter();
  const body = { model, messages: structuredClone(messages), stream: true };
  applyReasoningConfig(body, config, null);
  const tools = test.tools ?? [];
  if (tools.length) {
    body.tools = structuredClone(tools);
    body.tool_choice = 'auto';
  }
  applyGenerationParams(body, config, resolveQuirks(config));
  Object.assign(body, options);
  setRemoteImageFetcher({ fetchImage: url => url === 'https://example.com/cat.png'
    ? { bytes: new Uint8Array([1, 2, 3]), mimeType: 'image/png' } : undefined });
  let request;
  try {
    const prepared = preparePlainProviderRequest(adapter, config, body, token, sessionId, 'local oracle');
    request = { url: prepared.url, body: prepared.body, headers: Object.fromEntries(prepared.headers.map(([k, v]) => [k.toLowerCase(), v])) };
  } finally {
    setRemoteImageFetcher(null);
  }
  return {
    id, provider: 'anthropic', auth: 'x_api_key', session: sessionId, adapter: requestAdapterIdForConfig(config),
    historyScope: computeReasoningStateScope(config), input, messages, tools: test.tools ?? [],
    sidecarBlocks: sidecarBlocksOf(messages), request,
  };
}

/** Private assistant content blocks the Android sidecar replays on the same target. */
function sidecarBlocksOf(messages) {
  const blocks = {};
  messages.forEach((message, index) => {
    if (message.role !== 'assistant' || !Array.isArray(message.content)) return;
    blocks[String(index)] = message.content;
  });
  return blocks;
}

async function makeStreamCase(id, test) {
  const requestCase = await makeRequestCase({ ...test, id, messages: plainMessages(), tools: [] });
  const adapter = new AnthropicAdapter();
  const frames = test.frames;
  const events = frames.flatMap(({ event, data }) => adapter.parseStream(
    `${event ? `event: ${event}\n` : ''}data: ${typeof data === 'string' ? data : JSON.stringify(data)}`,
  ));
  return {
    id: `stream-${id}`, provider: 'anthropic', auth: 'x_api_key', session: sessionId,
    adapter: requestCase.adapter, historyScope: requestCase.historyScope, input: requestCase.input,
    messages: requestCase.messages, tools: [], request: requestCase.request,
    stream: {
      frames,
      events: events.map(projectEvent),
      replayBlocks: replayFromEvents(events),
      androidResult: test.androidResult,
      androidText: test.androidText,
      androidStop: test.androidStop,
      androidEvents: androidSlice(events, test.androidKeep, test.androidResult === 'complete'),
      deviation: test.deviation,
    },
  };
}

/** Android never sees ThoughtSignature/SafetyBlocked through the sealed API. */
function androidSlice(events, keep, complete) {
  const projected = events
    .filter(event => ['ContentChunk', 'ReasoningChunk', 'ToolCall', 'Usage'].includes(event.type))
    .map(projectEvent);
  const observed = keep === undefined ? projected : projected.slice(0, keep);
  if (complete) {
    const done = events.find(event => event.type === 'Done');
    assert.ok(done, 'successful fixture is missing a frozen terminal event');
    observed.push(projectEvent(done));
  }
  return observed;
}

/** Same-target sidecar capture: reasoning text plus the accumulated block signature. */
function replayFromEvents(events) {
  const thinking = events.filter(event => event.type === 'ReasoningChunk').map(event => event.value).join('');
  const signature = events.filter(event => event.type === 'ThoughtSignature').map(event => event.value).join('');
  if (!thinking && !signature) return [];
  const block = { type: 'thinking', thinking };
  if (signature) block.signature = signature;
  return [block];
}


function projectEvent(event) {
  if (event.type === 'Done') return { kind: 'completed' };
  if (event.type === 'SafetyBlocked') return { kind: 'safety_blocked', value: event.value };
  if (event.type === 'Usage') return { kind: 'usage', value: projectUsage(event.value) };
  assert.ok(USAGE_KINDS[event.type], `unexpected desktop event type ${event.type}`);
  return { kind: USAGE_KINDS[event.type], value: event.type === 'ToolCall' ? event.value : event.value };
}

function projectUsage(usage) {
  const projected = {
    input_tokens: usage.input_tokens,
    output_tokens: usage.output_tokens,
    total_tokens: usage.total_tokens,
  };
  const details = {};
  if (usage.cached_tokens !== null && usage.cached_tokens !== undefined) details.cached_tokens = usage.cached_tokens;
  if (usage.cache_write_tokens !== null && usage.cache_write_tokens !== undefined) details.cache_write_tokens = usage.cache_write_tokens;
  if (Object.keys(details).length) projected.input_tokens_details = details;
  if (usage.reasoning_tokens !== null && usage.reasoning_tokens !== undefined) {
    projected.output_tokens_details = { reasoning_tokens: usage.reasoning_tokens };
  }
  return projected;
}
