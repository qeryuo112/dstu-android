// Rebuild the S3.07 Google/Gemini wire fixture using only the locked desktop implementation.
// This script uses synthetic inputs only; it never sends HTTP and never touches real credentials.
// node --experimental-strip-types tools/providers/s3-07-oracle.mjs --verify|--write
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
git('diff', '--exit-code', 'HEAD', '--', 'host/plugins/llm', 'host/plugins/llm-providers', 'host/platform', 'scripts/provider-protocol-registry.json');
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
const { GeminiAdapter } = await frozen(`${wire}/providers/gemini.ts`);

const key = 's307-synthetic-key';
const configuredHeaders = { 'x-fixture-provider': 's307' };
const fixtureTool = {
  type: 'function',
  function: {
    name: 'fixture_lookup',
    description: 'Read synthetic test inputs',
    parameters: {
      type: 'object',
      properties: { tags: { type: 'array' }, mode: { enum: [1, 'auto', true] } },
    },
  },
};
const system = { role: 'system', content: 'Fixture instructions for the S3.07 oracle.' };
const image = 'data:image/png;base64,iVBORw0KGgo=';
const textHistory = [
  system,
  { role: 'user', content: [{ type: 'text', text: '协议回放 🧪' }, { type: 'image_url', image_url: { url: image } }] },
];
const toolHistory = [
  { role: 'user', content: "What's the weather?" },
  {
    role: 'assistant',
    thought_signature: 'fixture-signature',
    tool_calls: [{ id: 'fc-native-1', type: 'function', function: { name: 'fixture_lookup', arguments: '{"q":"🧪"}' } }],
  },
  { role: 'tool', tool_call_id: 'fc-native-1', content: '{"temp":25}' },
  { role: 'user', content: 'Continue' },
];
const plainHistory = [system, { role: 'user', content: '序列化' }];

const defaults = {
  isReasoning: false, supportsReasoning: false, supportsTools: true, thinkingEnabled: false,
  reasoningEffort: null, thinkingBudget: null, includeThoughts: false,
  maxOutputTokens: 2048, maxTokensLimit: 1536, temperature: 0.25, topPOverride: 0.75,
  frequencyPenaltyOverride: 0.125, presencePenaltyOverride: 0.25, topK: 12,
};
const thinking = { thinkingEnabled: true, isReasoning: true, supportsReasoning: true };

const usage = { promptTokenCount: 10, candidatesTokenCount: 4, totalTokenCount: 14, cachedContentTokenCount: 6, thoughtsTokenCount: 3 };
const frame = value => JSON.stringify(value);
const toolcallFrames = [
  frame({ candidates: [{ content: { role: 'model', parts: [
    { functionCall: { id: 'fc-native-1', name: 'lookup_a', args: { first: 1 } } },
    { functionCall: { id: 'fc-native-2', name: 'lookup_b', args: { second: 2 } } },
  ] } }], usageMetadata: usage }),
  frame({ candidates: [{ content: { role: 'model', parts: [{ thoughtSignature: 'sig-part' }] } }] }),
  frame({ candidates: [{ content: { role: 'model', parts: [{ functionCall: { name: 'lookup_c', args: {} } }] } }] }),
  frame({ candidates: [{ content: { role: 'model', parts: [{ functionCall: { id: 'fc-native-1', name: 'lookup_a', args: { first: 1, retried: true } } }] } }] }),
  frame({ candidates: [{ finishReason: 'STOP' }], usageMetadata: usage }),
];
const doneMarkerFrames = [
  frame({ candidates: [{ content: { role: 'model', parts: [{ text: 'before-done' }] } }] }),
  '[DONE]',
];
const record = {
  basic: [
    frame({ candidates: [{ content: { role: 'model', parts: [{ thought: true, text: '思考 gemini' }] } }] }),
    frame({ candidates: [{ content: { role: 'model', parts: [{ text: '回答' }] } }] }),
    frame({ candidates: [{ content: { role: 'model', parts: [{ text: ' ✅' }] } }], usageMetadata: usage }),
    frame({ candidates: [{ finishReason: 'STOP' }], usageMetadata: usage }),
  ],
  toolcalls: toolcallFrames,
  lateNativeId: [
    frame({ candidates: [{ content: { role: 'model', parts: [{ functionCall: { name: 'lookup_c', args: {} } }] } }] }),
    frame({ candidates: [{ content: { role: 'model', parts: [{ functionCall: { id: 'fc-late', name: 'lookup_c', args: { late: true } } }] } }] }),
    frame({ candidates: [{ finishReason: 'STOP' }], usageMetadata: usage }),
  ],
  deltaParts: [
    frame({ candidates: [{ content: { role: 'model', parts: [{ text: 'main' }] },
      delta: { parts: [{ thought: { value: false }, text: 'not-thought' }, { thought: true, text: 'delta-thought' }] } }] }),
    frame({ candidates: [{ finishReason: 'STOP' }], usageMetadata: usage }),
  ],
  thoughtVariants: [
    frame({ candidates: [{ content: {
      parts: [
        { metadata: { type: 'Thought' }, text: 'meta-thought' },
        { kind: 'Thought', text: 'kind-thought' },
        { type: 'thought', text: 'type-thought' },
      ] },
      thoughts: [{ text: 'candidate-thought' }, { content: 'alias-thought' }, 'bare-thought'] }],
      thoughts: ['payload-thought'], usageMetadata: usage }),
    frame({ candidates: [{ finishReason: 'STOP' }], usageMetadata: usage }),
  ],
  promptBlocked: [
    frame({ candidates: [{ content: { role: 'model', parts: [{ text: 'partial' }] } }] }),
    frame({ promptFeedback: { blockReason: 'SAFETY' } }),
  ],
  finishSafety: [
    frame({ candidates: [{ content: { role: 'model', parts: [{ text: 'partial' }] } }] }),
    frame({ candidates: [{ finishReason: 'SAFETY', safetyRatings: [{ category: 'HARM_CATEGORY_HARASSMENT' }] }], usageMetadata: usage }),
  ],
  doneMarker: doneMarkerFrames,
  finishMaxTokens: [
    frame({ candidates: [{ content: { role: 'model', parts: [{ text: 'partial' }] } }] }),
    frame({ candidates: [{ finishReason: 'MAX_TOKENS' }], usageMetadata: usage }),
  ],
};

const cases = [
  { id: 'google-basic-thinking', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'basic',
    config: { ...thinking, reasoningEffort: 'high' } },
  { id: 'google-2.5-budget', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'basic',
    config: { ...thinking, reasoningEffort: 'medium', thinkingBudget: 512 } },
  { id: 'google-prefix-version', provider: 'google', model: 'v1beta:gemini-2.5-flash', messages: textHistory,
    config: { ...thinking, thinkingBudget: 128, geminiApiVersion: 'v1' } },
  { id: 'google-v1-system-merge', provider: 'google', model: 'gemini-2.2-pro', messages: textHistory,
    config: { geminiApiVersion: 'v1' } },
  { id: 'google-gemini3-levels', provider: 'google', model: 'gemini-3-pro', messages: textHistory, stream: 'toolcalls',
    config: { ...thinking, reasoningEffort: 'xhigh', includeThoughts: true } },
  { id: 'google-gemini3-flash-off', provider: 'google', model: 'gemini-3-flash', messages: textHistory,
    config: { isReasoning: true, supportsReasoning: true }, options: { google_thinking_config: { include_thoughts: true } } },
  { id: 'google-images-tools', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'toolcalls',
    config: { ...thinking, thinkingBudget: 128 } },
  { id: 'google-signature-history', provider: 'google', model: 'gemini-3-pro', messages: toolHistory,
    config: { ...thinking, includeThoughts: true } },
  { id: 'google-response-format', provider: 'google', model: 'gemini-2.2-pro', messages: plainHistory,
    config: {}, options: {
      response_format: { type: 'json_schema', json_schema: { name: 'fixture', schema: { type: 'object' }, strict: true } },
      stop: ['END', 'STOP'],
    } },
  { id: 'gemini-provider', provider: 'gemini', model: 'gemini-2.5-flash', messages: textHistory, stream: 'basic',
    config: { ...thinking, reasoningEffort: 'high' } },
  { id: 'google-stream-late-native-id', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'lateNativeId',
    config: { ...thinking, thinkingBudget: 128 } },
  { id: 'google-stream-delta-parts', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'deltaParts',
    config: { ...thinking, thinkingBudget: 128 } },
  { id: 'google-stream-thought-variants', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'thoughtVariants',
    config: { ...thinking, thinkingBudget: 128 } },
  { id: 'google-stream-done-marker', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'doneMarker',
    config: { ...thinking, thinkingBudget: 128 } },
  { id: 'google-error-prompt-blocked', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'promptBlocked',
    config: { ...thinking, thinkingBudget: 128 } },
  { id: 'google-error-finish-safety', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'finishSafety',
    config: { ...thinking, thinkingBudget: 128 } },
  { id: 'google-error-finish-max-tokens', provider: 'google', model: 'gemini-2.5-flash', messages: textHistory, stream: 'finishMaxTokens',
    config: { ...thinking, thinkingBudget: 128 } },
];

function makeCase(test) {
  const { id, provider, model, messages, options = {} } = test;
  const input = { ...defaults, ...test.config, model, baseUrl: 'https://generativelanguage.googleapis.com',
    modelAdapter: 'google', extraBody: options };
  const config = makeConfig({ ...input, providerType: provider, providerScope: provider,
    apiProtocol: 'google_generate_content', authMode: 'x_api_key', enabled: true, apiKey: key, headers: configuredHeaders });
  const adapter = new GeminiAdapter();
  const buildBody = history => {
    const body = { model, messages: structuredClone(history), stream: true };
    applyReasoningConfig(body, config, null);
    body.tools = [structuredClone(fixtureTool)];
    body.tool_choice = 'auto';
    applyGenerationParams(body, config, resolveQuirks(config));
    Object.assign(body, options);
    return body;
  };
  const prepared = preparePlainProviderRequest(adapter, config, buildBody(messages), null, null, 'local oracle');
  const recorded = { url: prepared.url, body: prepared.body,
    headers: Object.fromEntries(prepared.headers.map(([k, v]) => [k.toLowerCase(), v])) };
  const result = {
    id, provider, model, headers: configuredHeaders, input, messages, tools: [fixtureTool],
    adapter: requestAdapterIdForConfig(config), historyScope: computeReasoningStateScope(config), request: recorded,
  };
  if (messages === toolHistory) {
    // Cross-target variant: a history replayed to a different target loses the signature.
    const stripped = messages.map(message => {
      const { thought_signature: signature, ...other } = message;
      return signature === undefined ? message : other;
    });
    const cross = preparePlainProviderRequest(adapter, config, buildBody(stripped), null, null, 'local oracle');
    result.requestCrossTarget = { url: cross.url, body: cross.body };
  }
  if (test.stream) {
    const events = record[test.stream].flatMap(line => adapter.parseStream(line));
    result.stream = { frames: record[test.stream], events: events.map(projectEvent) };
  }
  return result;
}

function projectEvent(event) {
  const names = { ContentChunk: 'text', ReasoningChunk: 'reasoning', ToolCall: 'tool', ThoughtSignature: 'signature',
    Usage: 'usage', SafetyBlocked: 'blocked', Done: 'completed' };
  assert.ok(names[event.type], `unexpected desktop event type ${event.type}`);
  if (event.type === 'Done') return { kind: 'completed' };
  if (event.type === 'ToolCall') {
    // Synthetic ids are random per run; both sides normalize them to a stable marker.
    const id = /^call-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(event.value.id)
      ? 'call-synthetic' : event.value.id;
    return { kind: 'tool', value: { index: event.value.index, id, type: event.value.type, function: event.value.function } };
  }
  if (event.type === 'Usage') {
    const value = { input_tokens: event.value.prompt_tokens, output_tokens: event.value.completion_tokens,
      total_tokens: event.value.total_tokens };
    if (event.value.cached_tokens !== undefined) value.cached_tokens = event.value.cached_tokens;
    if (event.value.reasoning_tokens !== undefined) value.reasoning_tokens = event.value.reasoning_tokens;
    return { kind: 'usage', value };
  }
  if (event.type === 'SafetyBlocked') return { kind: 'blocked', value: { type: event.value.type, reason: event.value.reason } };
  return { kind: names[event.type], value: event.value };
}

const artifact = { version: 1, desktopRevision: head, desktopTree: tree, token: key, cases: cases.map(makeCase) };
const output = join(root, 'app/src/test/resources/s3-07-gemini-oracle.json');
const serialized = `${JSON.stringify(artifact, null, 2)}\n`;
if (process.argv[2] === '--write') writeFileSync(output, serialized);
else if (process.argv[2] === '--verify') assert.equal(readFileSync(output, 'utf8'), serialized, 'S3.07 frozen oracle drift');
else throw new Error('use --verify or --write');
console.log(`S3.07 frozen desktop ${head}: ${artifact.cases.length} request cases, ` +
  `${artifact.cases.filter(it => it.stream).length} event sequences`);
for (const item of artifact.cases) console.log(item.id, item.adapter, item.request.url.replace(/&key=[^&]+/, '&key=…'),
  item.stream ? item.stream.events.map(it => it.kind).join(',') : '');
