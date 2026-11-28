// Reproduce the S3.04 golden at the provider boundary, not the context compiler.
// No requests are sent. All prompts, replies, tools and credentials are synthetic.
// node --experimental-strip-types tools/providers/s3-04-oracle.mjs --verify|--write
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(fileURLToPath(new URL('../../', import.meta.url)));
const lock = JSON.parse(readFileSync(join(root, 'config/source-lock.json'), 'utf8')).repositories.find(it => it.id === 'desktop');
const desktop = resolve(dirname(root), lock.path);
const [head, tree] = git('rev-parse', 'HEAD', 'HEAD^{tree}').trim().split('\n');
assert.deepEqual([head, tree], [lock.commit, lock.tree], 'Frozen desktop revision changed');
git('diff', '--exit-code', 'HEAD', '--', 'host/plugins/llm', 'host/plugins/llm-providers', 'host/platform', 'scripts/provider-protocol-registry.json');

const llm = 'host/plugins/llm';
const wire = 'host/plugins/llm-providers/src';
const { applyReasoningConfig } = await frozen(`${llm}/src/question_authoring.ts`);
const { applyGenerationParams } = await frozen(`${llm}/src/generation.ts`);
const { resolveQuirks } = await frozen(`${llm}/src/provider_quirks.ts`);
const { buildModel2Messages } = await frozen(`${llm}/src/model2_messages.ts`);
const { requestAdapterIdForConfig } = await frozen(`${llm}/src/protocol.ts`);
const { preparePlainProviderRequest } = await frozen(`${llm}/src/prepared_request.ts`);
const { normalizeToolSchemasForMfjs, shouldApplyMfjsToolSchemaDialect } = await frozen(`${llm}/src/adapters/moonshot.ts`);
const { BUILTIN_VENDORS } = await frozen(`${llm}/src/data/builtin_catalog.ts`);
const { makeConfig } = await frozen(`${llm}/tests/support.ts`);
const { OpenAIAdapter } = await frozen(`${wire}/providers/openai-chat.ts`);
const { stringifyProviderToolCall } = await frozen(`${wire}/tool-call-values.ts`);

const defaults = {
  supportsReasoning: true, isReasoning: true, thinkingEnabled: true, includeThoughts: true,
  reasoningEffort: 'high', thinkingBudget: 512, supportsTools: true,
  maxOutputTokens: 2048, maxTokensLimit: 1536, temperature: 0.25, topPOverride: 0.75,
  minP: 0.125, topK: 12, frequencyPenaltyOverride: 0.125, presencePenaltyOverride: 0.25,
  repetitionPenalty: 1.25, extraBody: { fixture_option: { flag: true }, fixture_string: 'true', fixture_null: null },
};
const messages = [{ role: 'user', content: '协议回放 fixture 🧪' }];
const tools = [
  { type: 'function', function: { name: 'fixture_lookup', description: 'Read a synthetic fixture', parameters: { properties: { q: { type: 'string' } } } } },
  { type: 'function', function: { name: 'fixture_lookup', description: 'Duplicate must be dropped', parameters: {} } },
];
const standardToolName = tools[0].function.name;
const moonshotTools = [structuredClone(tools[0])];
moonshotTools[0].function.parameters = {
  type: 'object', properties: { q: { type: 'string' }, mode: { const: 'auto' } }, required: ['q'],
  oneOf: [{ required: ['mode'] }, { properties: { q: { type: 'string', const: 'backup' } }, required: ['q'] }],
  uniqueItems: true,
};
const history = [
  ...messages,
  { role: 'assistant', content: '', thinking_content: '合成思考片段',
    tool_call: { id: 'fixture-history-call', tool_name: standardToolName, args_json: { q: '历史 🧪' } } },
  { role: 'tool', content: '{"answer":"synthetic"}', tool_result: { call_id: 'fixture-history-call' } },
];
const syntheticKey = 'fixture-only-not-a-real-key';
const primary = [
  { provider: 'siliconflow', model: 'mock/compatible-think', modelAdapter: 'general', reasoningEffort: null, thinkingBudget: 64 },
  { provider: 'qwen', model: 'qwen3.7-plus', modelAdapter: 'general' },
  { provider: 'zhipu', model: 'glm-5.3', modelAdapter: 'zhipu', reasoningEffort: 'medium', thinkingEnabled: false },
  { provider: 'doubao', model: 'doubao-seed-2-1-pro-260628', modelAdapter: 'doubao', reasoningEffort: 'medium' },
  { provider: 'minimax', model: 'MiniMax-M3', modelAdapter: 'minimax', reasoningSplit: true },
  { provider: 'moonshot', model: 'kimi-k3', modelAdapter: 'moonshot', reasoningEffort: 'xhigh' },
  { provider: 'nvidia', model: 'nvidia/nemotron-3-nano-30b-a3b', modelAdapter: 'general', reasoningEffort: 'high', thinkingEnabled: false },
  { provider: 'mimo', model: 'mimo-v2.5-pro', modelAdapter: 'mimo' },
  { provider: 'ernie', model: 'ernie-5.0-thinking-latest', modelAdapter: 'ernie', baseUrl: 'https://qianfan.baidubce.com/v2' },
  { provider: 'mistral', model: 'mistral-small-4', modelAdapter: 'mistral', baseUrl: 'https://api.mistral.ai/v1', reasoningEffort: 'max' },
];
const extra = [
  { provider: 'siliconflow', model: 'Qwen/Qwen3.8-Omni', modelAdapter: 'qwen', id: 'siliconflow-qwen', thinkingEnabled: false },
  { provider: 'siliconflow', model: 'deepseek-ai/DeepSeek-V4-Pro', modelAdapter: 'deepseek', id: 'siliconflow-deepseek', reasoningEffort: 'high' },
  { provider: 'siliconflow', model: 'deepseek-ai/DeepSeek-V3.2', modelAdapter: 'deepseek', id: 'siliconflow-deepseek-v32', reasoningEffort: 'medium', thinkingBudget: null },
  { provider: 'qwen', model: 'qwen3.8-omni', modelAdapter: 'qwen', id: 'qwen-omni', thinkingEnabled: false, isReasoning: false },
  { provider: 'moonshot', model: 'kimi-k2.7-code', modelAdapter: 'moonshot', id: 'moonshot-code', thinkingEnabled: false },
  { provider: 'minimax', model: 'MiniMax-M2.7', modelAdapter: 'minimax', id: 'minimax-legacy', reasoningSplit: false },
  { provider: 'ernie', model: 'deepseek-v4-pro', modelAdapter: 'ernie', id: 'ernie-hosted-deepseek', baseUrl: 'https://qianfan.baidubce.com/v2', reasoningEffort: 'xhigh' },
  { provider: 'mistral', model: 'magistral-medium-latest', modelAdapter: 'mistral', baseUrl: 'https://api.mistral.ai/v1', id: 'mistral-magistral', thinkingEnabled: false },
  { provider: 'qwen', model: 'qwen3.7-plus', modelAdapter: 'qwen', id: 'qwen-tool-history', useHistory: true },
  { provider: 'minimax', model: 'MiniMax-M3', modelAdapter: 'minimax', id: 'minimax-tool-history', reasoningSplit: true, useHistory: true },
  { provider: 'mimo', model: 'mimo-v2.5-pro', modelAdapter: 'mimo', id: 'mimo-tool-history', useHistory: true },
  { provider: 'moonshot', model: 'kimi-k3', modelAdapter: 'moonshot', id: 'moonshot-tool-schema', useMoonshotTools: true },
];
const executionPlan = JSON.parse(readFileSync(join(root, 'config/execution-plan.json'), 'utf8'));
const plan = executionPlan.units.find(unit => unit.id === 'S3.04');
assert.deepEqual(primary.map(it => it.provider), plan.dimensions[0].members.map(it => it.id));

const artifact = {
  format: 'dstu-s3-04-provider-oracle/v1', desktop: lock.commit, desktopTree: lock.tree,
  source: 'Synthetic provider boundary inputs replayed with the frozen desktop request and response implementations.',
  messages, history, tools, moonshotTools, cases: [...primary, ...extra].map(makeCase),
};
// Keep one case per line so that generated updates have focused diffs.
const header = JSON.stringify({ ...artifact, cases: [] }, null, 2).replace('"cases": []', '"cases": [');
const bytes = `${header.slice(0, -2)}\n${artifact.cases.map(it => `    ${JSON.stringify(it)}`).join(',\n')}\n  ]\n}\n`;
const target = join(root, 'app/src/test/resources/s3-04-chat-oracle.json');
if (process.argv[2] === '--write') writeFileSync(target, bytes);
else if (process.argv[2] === '--verify') assert.equal(readFileSync(target, 'utf8'), bytes, 'Regenerate the fixture from the pinned desktop');
else throw new Error('expected --verify or --write');
console.log(`S3.04 desktop oracle: ${head} ${artifact.cases.length} exact requests, ${primary.length} exact SSE event sequences`);
for (const entry of artifact.cases) {
  console.log(`${entry.id}: ${entry.adapter} ${new URL(entry.request.url).pathname}${entry.stream ? ` (${entry.stream.events.length} events)` : ''}`);
}

function git(...args) { return execFileSync('git', ['-C', desktop, ...args], { encoding: 'utf8' }); }
function frozen(relative) { return import(pathToFileURL(join(desktop, relative)).href); }

function makeCase({ provider, id, baseUrl, useHistory = false, useMoonshotTools = false, ...overrides }) {
  const vendor = BUILTIN_VENDORS.find(item => item.providerType === provider);
  const endpoint = baseUrl ?? vendor?.baseUrl;
  assert.ok(endpoint, provider);
  const input = { ...defaults, ...overrides, baseUrl: endpoint };
  const config = makeConfig({
    ...input, providerType: provider, providerScope: provider, apiProtocol: 'openai_chat_completions',
    enabled: true, apiKey: syntheticKey,
  });
  // Same call order as model2_request.ts, with the chat context already compiled.
  // The frozen context compiler's leading system prompt is outside this test boundary.
  const inputMessages = useHistory ? buildModel2Messages(config, history, '', true, []).slice(1) : structuredClone(messages);
  const body = { model: config.model, messages: inputMessages, stream: true };
  const quirks = resolveQuirks(config);
  applyReasoningConfig(body, config, null);
  body.tools = structuredClone(useMoonshotTools ? moonshotTools : tools);
  if (shouldApplyMfjsToolSchemaDialect(config)) normalizeToolSchemasForMfjs(body.tools);
  if (!(quirks.strip_tool_choice_on_tool_result && inputMessages.some(it => it.role === 'tool'))) body.tool_choice = 'auto';
  applyGenerationParams(body, config, quirks);
  const wireAdapter = new OpenAIAdapter();
  const request = preparePlainProviderRequest(wireAdapter, config, body, null, null, 'local oracle');
  assert.deepEqual(request.headers, [['Authorization', `Bearer ${syntheticKey}`], ['Content-Type', 'application/json']]);
  return {
    id: id ?? provider, provider, input, useHistory, useMoonshotTools, adapter: requestAdapterIdForConfig(config),
    request: { url: request.url, body: request.body },
    ...(id === undefined ? { stream: makeStream(wireAdapter, provider) } : {}),
  };
}

function makeStream(adapter, provider) {
  const thought = `思考 ${provider}`;
  const forms = {
    siliconflow: { reasoning_content: thought },
    qwen: { reasoning_content: thought, reasoning: 'do not emit this fallback' },
    zhipu: { reasoning: thought },
    doubao: { reasoning_content: thought },
    minimax: { reasoning_details: [{ type: 'thinking', text: thought }, { type: 'thinking', summary: ' / second' }] },
    moonshot: { reasoning_content: thought },
    nvidia: { reasoning: thought },
    mimo: { reasoning_content: thought },
    ernie: { reasoning_content: thought },
    mistral: { content: [{ type: 'thinking', thinking: [{ text: thought }, ' / part'] }, { type: 'text', text: '哈' }] },
  };
  const frames = [
    { choices: [{ index: 0, delta: forms[provider], finish_reason: null }] },
    ...(provider === 'qwen' ? [{ choices: [{ index: 0, delta: { reasoning_content: '', reasoning: 'not a fallback' } }] }] : []),
    { choices: [{ index: 0, delta: textualPart(provider), finish_reason: null }] },
    { choices: [{ index: 0, delta: { content: ' ✅', tool_calls: [
      { index: 0, id: 'fixture-call', type: 'function', function: { name: standardToolName, arguments: '{"q"' } },
      { index: 1, id: '', function: { arguments: '' } },
    ] }, finish_reason: null }] },
    { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: ':"🧪"}' } }] }, finish_reason: 'tool_calls' }] },
    { choices: [{ index: 0, delta: { content: ' tail' }, finish_reason: null }] },
    { choices: [], usage: {
      prompt_tokens: 7, completion_tokens: 4, total_tokens: 11,
      prompt_tokens_details: { cached_tokens: 2, cache_write_tokens: 1 }, completion_tokens_details: { reasoning_tokens: 3 },
    } },
  ].map(it => JSON.stringify(it)).concat('[DONE]');
  return { frames, events: frames.flatMap(frame => adapter.parseStream(`data: ${frame}`)).map(projectEvent) };
}

function textualPart(provider) {
  // Two identical textual items in separate Mistral chunks are intentional, not duplicates to discard.
  if (provider === 'mistral') return { content: [{ type: 'text', text: '哈' }] };
  return { content: `回答 ${provider}` };
}

function projectEvent(event) {
  switch (event.type) {
    case 'ContentChunk': return { kind: 'text', value: event.value };
    case 'ReasoningChunk': return { kind: 'reasoning', value: event.value };
    case 'ToolCall': return { kind: 'tool', value: stringifyProviderToolCall(event.value) };
    case 'Usage': return { kind: 'usage', value: event.value };
    case 'Done': return { kind: 'completed' };
    default: throw new Error(`Unexpected desktop oracle event: ${event.type}`);
  }
}
