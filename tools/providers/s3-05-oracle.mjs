// Rebuild the S3.05 wire fixture using only the locked desktop implementation.
// This script uses synthetic inputs and an in-process stub account; it never sends HTTP.
// node --experimental-strip-types tools/providers/s3-05-oracle.mjs --verify|--write
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
const { ensureOfficialDeepseekV4ReasoningPassback } = await frozen(`${llm}/src/passback.ts`);
const { OpenAIResponsesAdapter } = await frozen(`${wire}/providers/openai-responses.ts`);
const { prepareProviderRequest } = await frozen(`${wire}/openai-codex/prepare.ts`);
const { CODEX_RESPONSES_ENDPOINT } = await frozen(`${wire}/openai-codex/protocol.ts`);

const token = 's305-synthetic-bearer';
const sessionId = 'fixture-s305-session';
const codexAccount = 'fixture-codex-account';
const configuredHeaders = { 'x-fixture-provider': 's305', 'content-type': 'text/plain' };
const reasoning = { type: 'reasoning', id: 'rs_fixture', summary: [{ type: 'summary_text', text: '合成思考' }], encrypted_content: 'fixture-only-encrypted-reasoning' };
const fn = { type: 'function_call', id: 'fc_fixture', call_id: 'call_fixture', name: 'fixture_lookup', arguments: '{"q":"🧪"}' };
const searchItem = {
  type: 'web_search_call', id: 'ws_fixture', status: 'completed', action: { type: 'open_page', url: 'https://example.com/fixture' },
  page: { url: 'https://example.com/fixture', title: 'Synthetic citation' }, signature: 'fixture-only-search-signature',
};
const image = { type: 'input_image', image_url: 'data:image/png;base64,iVBORw0KGgo=', detail: 'low' };
const standardTool = { type: 'function', function: {
  name: fn.name, description: 'Read synthetic test inputs',
  parameters: { type: 'object', properties: { q: { type: 'string' } }, additionalProperties: false }, strict: true,
} };
const system = { role: 'system', content: 'Fixture instructions.' };
const user = { role: 'user', content: [{ type: 'text', text: '协议回放 🧪' }, image] };
const history = [system, user, {
  role: 'assistant', content: [{ type: 'output_text', text: '先前答案' }, { type: 'refusal', refusal: 'synthetic refusal' }],
  response_reasoning_item: reasoning, response_web_search_items: [searchItem],
  tool_calls: [{ type: 'function', id: fn.call_id, function: { name: fn.name, arguments: fn.arguments } }],
}, { role: 'tool', tool_call_id: fn.call_id, content: [{ type: 'text', text: '第一行' }, { type: 'text', text: '第二行' }] },
{ role: 'user', content: 'Continue' }];
const plain = [system, user];
const defaults = {
  isReasoning: true, supportsReasoning: true, supportsTools: true, thinkingEnabled: true,
  reasoningEffort: 'high', thinkingBudget: 512, maxOutputTokens: 2048, maxTokensLimit: 1536,
  temperature: 0.25, topPOverride: 0.75, frequencyPenaltyOverride: 0.125, presencePenaltyOverride: 0.25,
};
const deepseekHistory = [system, { role: 'user', content: [{ type: 'input_file', file_id: 'file-synthetic' }, { type: 'text', text: '总结' }] },
  { role: 'system', content: 'Keep this later instruction in history.' }, ...history.slice(2).map(it => {
    if (it.role !== 'assistant') return it;
    const { response_reasoning_item, ...other } = it;
    return { ...other, reasoning_content: 'DeepSeek reasoning passback' };
  })];
const deepseekTools = [{ type: 'deepseek_web_search' }, { type: 'deepseek_read_url' }];
const deepseekOptions = { safety_check: { level: 'high' }, title: 'synthetic title' };
const cases = [
  { id: 'openai', provider: 'openai', model: 'gpt-5.6-fixture', baseUrl: 'https://api.openai.com/v1', messages: history, stream: 'delta' },
  { id: 'openai_codex', provider: 'openai_codex', model: 'gpt-5-codex', baseUrl: 'https://chatgpt.com/backend-api/codex', messages: history, stream: 'terminal' },
  { id: 'deepseek', provider: 'deepseek', model: 'deepseek-flash', modelAdapter: 'deepseek', baseUrl: 'https://api.deepseek.com/v1',
    messages: deepseekHistory, tools: deepseekTools, options: deepseekOptions, stream: 'moderated' },
  { id: 'grok', provider: 'grok', model: 'grok-4.20-reasoning', modelAdapter: 'grok', baseUrl: 'https://api.x.ai/v1', messages: plain, stream: 'web' },
  { id: 'xai', provider: 'xai', model: 'grok-4-fast-non-reasoning', modelAdapter: 'grok', baseUrl: 'https://api.x.ai/v1',
    messages: plain, config: { supportsReasoning: false, isReasoning: false, thinkingEnabled: false, reasoningEffort: null }, stream: 'typed' },
  { id: 'openai-json-schema', provider: 'openai', model: 'gpt-4.1-fixture', baseUrl: 'https://api.openai.com/v1', messages: plain,
    config: { isReasoning: false, supportsReasoning: false, thinkingEnabled: false, reasoningEffort: null }, options: {
      response_format: { type: 'json_schema', json_schema: { name: 'fixture', schema: { type: 'object' }, strict: true } },
      verbosity: 'low', parallel_tool_calls: false,
    } },
  { id: 'openai-cache-alias', provider: 'openai', model: 'gpt-5.6-fixture', baseUrl: 'https://fixture.invalid/v1', messages: plain,
    config: { supportsOpenaiResponses: true } },
  { id: 'codex-background', provider: 'openai_codex', model: 'gpt-5-codex', baseUrl: 'https://chatgpt.com/backend-api/codex', messages: plain,
    session: null, options: { include: ['message.output_text.logprobs'], store: true } },
  { id: 'deepseek-thinking-off', provider: 'deepseek', model: 'deepseek-flash', modelAdapter: 'deepseek', baseUrl: 'https://api.deepseek.com/v1',
    messages: plain, config: { thinkingEnabled: false, enableThinking: false } },
  { id: 'deepseek-v4-passback', provider: 'deepseek', model: 'deepseek-v4-flash', modelAdapter: 'deepseek', baseUrl: 'https://api.deepseek.com/v1',
    messages: deepseekHistory, config: { reasoningEffort: 'medium' } },
  { id: 'xai-no-auth', provider: 'xai', model: 'grok-4-fast-non-reasoning', modelAdapter: 'grok', baseUrl: 'https://api.x.ai/v1',
    messages: plain, auth: 'none', config: { supportsReasoning: false, isReasoning: false, thinkingEnabled: false, reasoningEffort: null } },
];

const usage = { input_tokens: 2147483650, output_tokens: 5, total_tokens: 2147483655,
  input_tokens_details: { cached_tokens: 2 }, output_tokens_details: { reasoning_tokens: 3 } };
const part = text => ({ type: 'message', id: 'msg_fixture', content: [{ type: 'output_text', text }] });
const added = (item, index) => ({ type: 'response.output_item.added', item, output_index: index });
const done = (item, index) => ({ type: 'response.output_item.done', item, output_index: index });
const terminal = (output, type = 'response.completed') => ({ type, response: { status: 'completed', output, usage } });
const delta = text => ({ type: 'response.output_text.delta', delta: text });
const record = {
  delta: [ { type: 'response.reasoning_summary_text.delta', delta: '合成思考' }, done(reasoning, 0), delta('哈'), delta('哈'),
    added({ ...fn, arguments: '' }, 2), { type: 'response.function_call_arguments.delta', item_id: fn.id, output_index: 2, delta: '{"q"' },
    { type: 'response.function_call_arguments.delta', item_id: fn.id, output_index: 2, delta: ':"🧪"}' },
    done({ ...fn, arguments: '' }, 2), terminal([reasoning, part('哈哈'), fn]) ],
  terminal: [ { type: 'response.created', response: { status: 'in_progress' } }, terminal([reasoning, part('Codex 文本'), fn]) ],
  moderated: [delta('UNREVIEWED'), added(fn, 2), done(fn, 2), done(searchItem, 0), terminal([searchItem, {
    ...part('🌱结论。'), content: [{ type: 'output_text', text: '🌱结论。', annotations: [
      { type: 'url_citation', url: 'https://example.com/a_(b)', start_index: 0, end_index: 3 },
      { type: 'url_citation', url: 'javascript:alert(1)', start_index: 0, end_index: 4 },
    ] }],
  }, fn]) ],
  web: [{ type: 'response.web_search_call.in_progress', item_id: 'ws_fixture' },
    { type: 'response.web_search_call.searching', item_id: 'ws_fixture' }, done(searchItem, 0), delta('Grok 文本'), terminal([searchItem, part('Grok 文本')])],
  typed: [ { event: 'response.output_text.delta', data: { delta: 'xAI' } },
    { type: 'response.output_text.done', text: 'xAI must not duplicate' }, terminal([part('xAI')], 'response.done') ],
};

const artifact = {
  version: 1, desktopRevision: head, desktopTree: tree, token, codexAccount,
  cases: await Promise.all(cases.map(makeCase)),
};
const output = join(root, 'app/src/test/resources/s3-05-responses-oracle.json');
const serialized = `${JSON.stringify(artifact, null, 2)}\n`;
if (process.argv[2] === '--write') writeFileSync(output, serialized);
else if (process.argv[2] === '--verify') assert.equal(readFileSync(output, 'utf8'), serialized, 'S3.05 frozen oracle drift');
else throw new Error('use --verify or --write');
console.log(`S3.05 frozen desktop ${head}: ${artifact.cases.length} requests, ${artifact.cases.filter(it => it.stream).length} event sequences`);
for (const item of artifact.cases) console.log(item.id, item.adapter, item.request.url, item.stream?.events.map(it => it.kind).join(',') ?? '');

async function makeCase(test) {
  const { id, provider, model, baseUrl, messages, options = {}, stream } = test;
  const codex = provider === 'openai_codex';
  const auth = test.auth ?? (codex ? 'openai_codex_oauth' : 'bearer_api_key');
  const session = test.session === undefined ? sessionId : test.session;
  const headers = configuredHeaders;
  const input = { ...defaults, ...test.config, model, baseUrl, modelAdapter: test.modelAdapter ?? 'general', extraBody: options };
  const config = makeConfig({ ...input, providerType: provider, providerScope: provider, apiProtocol: 'openai_responses',
    authMode: auth, enabled: true, apiKey: auth === 'bearer_api_key' ? token : '', headers });
  const adapter = new OpenAIResponsesAdapter();
  const body = { model, messages: structuredClone(messages), stream: true };
  applyReasoningConfig(body, config, null);
  const tools = [standardTool, ...(test.tools ?? [])];
  body.tools = structuredClone(tools);
  body.tool_choice = 'auto';
  applyGenerationParams(body, config, resolveQuirks(config));
  // Protocol-specific options model the already-resolved request knobs, not a context compiler.
  Object.assign(body, options);
  ensureOfficialDeepseekV4ReasoningPassback(body, config);
  const request = codex ? await prepareProviderRequest({
    responsesEndpoint: () => CODEX_RESPONSES_ENDPOINT,
    requestAuth: async () => ({ accessToken: () => token, accountId: () => codexAccount, generation: () => 42, isFedramp: () => true }),
  }, adapter, config, body, { sessionId: session }) : preparePlainProviderRequest(adapter, config, body, null, session, 'local oracle');
  const recorded = { url: request.url, body: request.body, headers: Object.fromEntries(request.headers.map(([k, v]) => [k.toLowerCase(), v])) };
  if (codex) delete recorded.headers['user-agent']; // App verifies its own version, not the desktop version.
  const result = { id, provider, auth, session, headers, input, messages, tools, adapter: requestAdapterIdForConfig(config),
    historyScope: computeReasoningStateScope(config), request: recorded };
  if (stream) {
    const frames = record[stream].map(frame => Object.hasOwn(frame, 'event') ? frame : { data: frame });
    const events = frames.flatMap(({ event, data }) => adapter.parseStream(`${event ? `event: ${event}\n` : ''}data: ${JSON.stringify(data)}`));
    result.stream = { frames, events: events.map(projectEvent) };
  }
  return result;
}

function projectEvent(event) {
  const names = { ContentChunk: 'text', ReasoningChunk: 'reasoning', ToolCall: 'tool', Usage: 'usage',
    ResponseReasoningItem: 'reasoning_item', WebSearchCall: 'web_search', Done: 'completed' };
  assert.ok(names[event.type], `unexpected desktop event type ${event.type}`);
  return event.type === 'Done' ? { kind: 'completed' } : { kind: names[event.type], value: event.value };
}
