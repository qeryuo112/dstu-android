// Rebuild the S3.08 custom/general/opencode_go/openrouter/ollama fixtures using only
// the locked desktop implementation. Synthetic inputs, no HTTP is ever sent except
// through injected stub fetches. node --experimental-strip-types tools/providers/s3-08-oracle.mjs --verify|--write
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
const {
  vendorModelsEndpoint, vendorModelHeaders, parseVendorModelsPage, probeU32Field,
  fetchVendorModels, normalizeOllamaRootBaseUrl, PROBE_CONTEXT_WINDOW_KEYS, PROBE_MAX_OUTPUT_KEYS,
} = await frozen(`${llm}/src/vendor_models.ts`);
const openCodeGo = await frozen(`${llm}/src/opencode_go.ts`);
const { preparePlainProviderRequest, APP_VERSION } = await frozen(`${llm}/src/prepared_request.ts`);
const { requestAdapterIdForConfig, effectiveApiProtocolForConfig } = await frozen(`${llm}/src/protocol.ts`);
const { applyReasoningConfig } = await frozen(`${llm}/src/question_authoring.ts`);
const { applyGenerationParams } = await frozen(`${llm}/src/generation.ts`);
const { resolveQuirks } = await frozen(`${llm}/src/provider_quirks.ts`);
const { OpenAIAdapter } = await frozen(`${wire}/providers/openai-chat.ts`);
const { OpenAIResponsesAdapter } = await frozen(`${wire}/providers/openai-responses.ts`);
const { makeConfig } = await frozen(`${llm}/tests/support.ts`);

const token = 's308-synthetic-bearer';
const sessionId = 'fixture session_01:🧪 go';
const appVersion = APP_VERSION;
const messages = [{ role: 'user', content: '协议回放 fixture 🧪' }];
const standardHeaders = { 'X-Fixture-Trace': 's308', 'x-trace-lower': 'case' };

const chatDefaults = {
  isReasoning: false, supportsReasoning: false, thinkingEnabled: false, reasoningEffort: null,
  maxOutputTokens: 1024, maxTokensLimit: 2048, temperature: 0.4, supportsTools: false,
};

const cases = [
  {
    id: 'custom-relay', provider: 'custom', model: 'fixture-relay-model',
    baseUrl: 'https://relay.example.com/v1', headers: standardHeaders,
  },
  {
    id: 'custom-relay-max', provider: 'custom', model: 'fixture-relay-max',
    baseUrl: 'https://relay.example.com/v1', headers: standardHeaders,
    config: { reasoningEffort: 'max', isReasoning: true, supportsReasoning: true, thinkingEnabled: true, temperature: 0.2 },
  },
  {
    id: 'general-reasoning', provider: 'general', model: 'fixture-general-reasoner',
    baseUrl: 'https://gateway.example.com/v1', headers: standardHeaders,
    config: {
      isReasoning: true, supportsReasoning: true, thinkingEnabled: true, thinkingBudget: 2048,
      temperature: 0.25, topPOverride: 0.75, minP: 0.1, topK: 40,
    },
  },
  {
    id: 'opencode-go-session', provider: 'opencode_go', model: 'glm-5.3',
    baseUrl: 'https://opencode.ai/inference/go', headers: { 'X-Fixture-Go': '1' }, session: sessionId,
  },
  {
    id: 'opencode-go-task', provider: 'opencode_go', model: 'deepseek-v4.1-flash',
    baseUrl: 'https://opencode.ai/zen/go/v1', session: null,
  },
  {
    id: 'openrouter-reasoning', provider: 'openrouter', model: 'gpt-6-fixture',
    baseUrl: 'https://openrouter.example.com/api/v1', headers: standardHeaders,
    config: {
      reasoningEffort: 'max', isReasoning: true, supportsReasoning: true, thinkingEnabled: true,
      temperature: 0.2, extraBody: { reasoning: { max_tokens: 512, enabled: true } },
    },
  },
  {
    id: 'openrouter-legacy', provider: 'openrouter', model: 'fixture-router-model',
    baseUrl: 'https://openrouter.example.com/api/v1',
    config: {
      reasoningEffort: 'high', isReasoning: true, supportsReasoning: true, thinkingEnabled: true,
      extraBody: { reasoning: { max_tokens: 512, enabled: true } },
    },
  },
  {
    id: 'openrouter-implicit', provider: 'openrouter', model: 'fixture-router-thinker',
    baseUrl: 'https://openrouter.example.com/api/v1',
    config: { isReasoning: true, supportsReasoning: true, thinkingEnabled: true, thinkingBudget: 16384 },
  },
  {
    id: 'ollama-local', provider: 'ollama', model: 'fixture-local-model',
    baseUrl: 'https://fixture-ollama.invalid:11434/', auth: 'none',
  },
];

const routes = [
  { id: 'opencode-go-anthropic-route', provider: 'opencode_go', model: 'qwen3.8-max', protocol: 'anthropic_messages', baseUrl: 'https://opencode.ai/inference/go/openai/v1' },
  { id: 'opencode-go-google-route', provider: 'opencode_go', model: 'gemini-4-argon', protocol: 'google_generate_content', baseUrl: 'https://opencode.ai/zen/go' },
  { id: 'custom-responses-route', provider: 'custom', model: 'fixture-relay-model', protocol: 'openai_responses', baseUrl: 'https://relay.example.com/v1', responsesEnabled: true },
  { id: 'openrouter-responses-route', provider: 'openrouter', model: 'fixture-router-model', protocol: 'openai_responses', baseUrl: 'https://openrouter.example.com/api/v1', responsesEnabled: true },
];

const responsesCases = [
  {
    id: 'custom-responses', provider: 'custom', model: 'fixture-relay-model',
    baseUrl: 'https://relay.example.com/v1', headers: standardHeaders, responsesEnabled: true,
  },
];

// ---------------------------------------------------------------- chat requests

function chatConfig(test) {
  const auth = test.auth ?? 'bearer_api_key';
  return makeConfig({
    ...chatDefaults, ...test.config, model: test.model, baseUrl: test.baseUrl,
    providerType: test.provider, providerScope: test.provider, authMode: auth,
    apiKey: auth === 'bearer_api_key' ? token : '', headers: test.headers ?? null, enabled: true,
  });
}

function buildChatCase(test) {
  const config = chatConfig(test);
  // The desktop manager rewrites OpenCode Go roots to the protocol base before preparing
  // (merge_vendor_profile + base_url_for_protocol); mirror that with the frozen function.
  if (test.provider === 'opencode_go') {
    config.baseUrl = openCodeGo.baseUrlForProtocol(test.baseUrl, 'openai_chat_completions');
  }
  const body = { model: test.model, messages: structuredClone(messages), stream: true };
  applyReasoningConfig(body, config, null);
  applyGenerationParams(body, config, resolveQuirks(config));
  const prepared = preparePlainProviderRequest(new OpenAIAdapter(), config, body, null, test.session ?? null, 'local oracle');
  return {
    id: test.id, provider: test.provider, kind: 'chat',
    auth: test.auth ?? 'bearer_api_key', session: test.session ?? null,
    input: { model: test.model, baseUrl: test.baseUrl, headers: test.headers ?? null, config: { ...chatDefaults, ...test.config } },
    adapter: requestAdapterIdForConfig(config), protocol: effectiveApiProtocolForConfig(config),
    request: {
      url: prepared.url, opencodeGo: prepared.opencodeGo, body: prepared.body,
      headers: Object.fromEntries(prepared.headers.map(([name, value]) => [name.toLowerCase(), value])),
    },
  };
}

function buildRouteCase(test) {
  const protocol = test.protocol;
  // The desktop manager derives the runtime model adapter from the catalog protocol.
  const config = makeConfig({
    ...chatDefaults, model: test.model, baseUrl: test.baseUrl,
    providerType: test.provider, providerScope: test.provider, authMode: 'bearer_api_key',
    apiKey: token, enabled: true, apiProtocol: protocol,
    supportsOpenaiResponses: test.responsesEnabled === true,
    modelAdapter: openCodeGo.modelAdapterForProtocol(protocol),
  });
  assert.equal(effectiveApiProtocolForConfig(config), protocol, test.id);
  return {
    id: test.id, provider: test.provider, kind: 'route', protocol,
    auth: 'bearer_api_key',
    input: { model: test.model, baseUrl: test.baseUrl, headers: null, config: chatDefaults },
    responsesEnabled: test.responsesEnabled === true,
    adapter: requestAdapterIdForConfig(config),
    goBase: openCodeGo.isOpencodeGoConfig({ vendorId: null, providerType: test.provider, baseUrl: test.baseUrl })
      ? openCodeGo.baseUrlForProtocol(test.baseUrl, protocol) : null,
  };
}

function buildResponsesCase(test) {
  const config = makeConfig({
    ...chatDefaults, model: test.model, baseUrl: test.baseUrl, providerType: test.provider,
    providerScope: test.provider, authMode: 'bearer_api_key', apiKey: token,
    headers: test.headers ?? null, enabled: true, apiProtocol: 'openai_responses',
    supportsOpenaiResponses: test.responsesEnabled === true,
  });
  const body = { model: test.model, messages: structuredClone(messages), stream: true };
  applyReasoningConfig(body, config, null);
  applyGenerationParams(body, config, resolveQuirks(config));
  const prepared = preparePlainProviderRequest(new OpenAIResponsesAdapter(), config, body, null, sessionId, 'local oracle');
  return {
    id: test.id, provider: test.provider, kind: 'responses', protocol: 'openai_responses',
    auth: 'bearer_api_key',
    input: { model: test.model, baseUrl: test.baseUrl, headers: test.headers ?? null, config: chatDefaults },
    responsesEnabled: test.responsesEnabled === true,
    session: sessionId,
    adapter: requestAdapterIdForConfig(config),
    request: {
      url: prepared.url, body: prepared.body,
      headers: Object.fromEntries(prepared.headers.map(([name, value]) => [name.toLowerCase(), value])),
    },
  };
}

// ---------------------------------------------------------------- model catalogs

const listVendor = overrides => ({
  id: 'fixture-vendor', name: 'Fixture vendor', providerType: 'openai', authMode: null,
  apiProtocol: null, supportsOpenaiResponses: null, baseUrl: 'https://api.example.com/v1',
  apiKey: 's308-synthetic-list-key', apiKeys: [], headers: {}, rateLimitPerMinute: null,
  defaultTimeoutMs: null, notes: '', isBuiltin: false, isReadOnly: false, sortOrder: null,
  maxTokensLimit: null, websiteUrl: '', ...overrides,
});

const pageOne = {
  data: [
    { id: 'b-model', display_name: 'Model B', context_length: 8192 },
    { id: 'b-model-tts-1' },
    { id: 'b-model-kolors' },
  ],
  has_more: true,
  last_id: 'b-model',
};
const pageTwo = { data: [{ id: 'a-model', max_output_tokens: 1024, top_provider: { max_completion_tokens: 4096 } }] };

async function buildModelLists() {
  const openaiRequests = [];
  const openaiModels = await fetchVendorModels(listVendor(), {
    fetch: async (url, init) => {
      openaiRequests.push({ url, headers: Object.fromEntries(init.headers), redirect: init.redirect });
      const page = openaiRequests.length === 1 ? pageOne : pageTwo;
      return { status: 200, statusText: 'OK', json: async () => page, text: async () => '' };
    },
  });

  const anthropicModels = await fetchVendorModels(listVendor({ providerType: 'anthropic', headers: { 'X-Fixture-Trace': 'list' } }), {
    fetch: async (url, init) => {
      return {
        status: 200, statusText: 'OK',
        json: async () => ({ data: [{ id: 'claude-fixture', type: 'model', display_name: 'Claude Fixture', max_output_tokens: 64_000 }, { id: 'tool-item', type: 'tool' }] }),
        text: async () => '',
      };
    },
  });

  const geminiModels = await fetchVendorModels(listVendor({ providerType: 'gemini', headers: { Host: 'evil', Connection: 'close' } }), {
    fetch: async (url, init) => ({
      status: 200, statusText: 'OK',
      json: async () => ({
        models: [
          { name: 'models/gemini-fixture-pro', displayName: 'Gemini Fixture Pro', supportedGenerationMethods: ['generateContent'], inputTokenLimit: 1_000_000, outputTokenLimit: 65_536 },
          { name: 'models/embed-1', supportedGenerationMethods: ['embedContent'] },
        ],
        nextPageToken: 'page-2',
      }),
      text: async () => '',
    }),
  });

  const ollamaEndpoint = vendorModelsEndpoint('http://127.0.0.1:11434', 'ollama', null);

  let catalogCalls = 0;
  const goCatalog = await fetchVendorModels(listVendor({ id: openCodeGo.VENDOR_ID, providerType: openCodeGo.PROVIDER_TYPE, baseUrl: openCodeGo.OPENAI_BASE_URL }), {
    fetch: async () => { catalogCalls += 1; throw new Error('network must not be used'); },
  });

  const authFailureDetail = 'x'.repeat(3000);
  const keyFailure = await fetchVendorModels(listVendor({ apiKey: '', apiKeys: [' backup-key '] }), {
    fetch: async (url, init) => {
      openaiRequests.push({ url, headers: Object.fromEntries(init.headers), redirect: init.redirect });
      return { status: 200, statusText: 'OK', json: async () => pageTwo, text: async () => '' };
    },
  });
  let httpFailure = null;
  try {
    await fetchVendorModels(listVendor(), {
      fetch: async () => ({ status: 401, statusText: 'Unauthorized', json: async () => ({}), text: async () => authFailureDetail }),
    });
  } catch (error) {
    httpFailure = { errorType: error.error_type, message: error.message };
  }
  let containerFailure = null;
  try {
    await fetchVendorModels(listVendor(), { fetch: async () => ({ status: 200, statusText: 'OK', json: async () => ({}), text: async () => '' }) });
  } catch (error) {
    containerFailure = { errorType: error.error_type, message: error.message };
  }
  let connectFailure = null;
  try {
    await fetchVendorModels(listVendor(), { fetch: async () => { throw new TypeError('fetch failed'); } });
  } catch (error) {
    connectFailure = { errorType: error.error_type, message: error.message };
  }

  return {
    probeKeys: { context: PROBE_CONTEXT_WINDOW_KEYS, maxOutput: PROBE_MAX_OUTPUT_KEYS },
    probeU32: [
      { input: { context_length: 128_000 }, keys: PROBE_CONTEXT_WINDOW_KEYS, value: probeU32Field({ context_length: 128_000 }, PROBE_CONTEXT_WINDOW_KEYS) },
      { input: { context_length: ' 64000 ' }, keys: PROBE_CONTEXT_WINDOW_KEYS, value: probeU32Field({ context_length: ' 64000 ' }, PROBE_CONTEXT_WINDOW_KEYS) },
      { input: { context_length: 2 ** 40 }, keys: PROBE_CONTEXT_WINDOW_KEYS, value: probeU32Field({ context_length: 2 ** 40 }, PROBE_CONTEXT_WINDOW_KEYS) },
      { input: { top_provider: { max_completion_tokens: 8192 } }, keys: PROBE_MAX_OUTPUT_KEYS, value: probeU32Field({ top_provider: { max_completion_tokens: 8192 } }, PROBE_MAX_OUTPUT_KEYS) },
    ],
    openai: { requests: openaiRequests, models: openaiModels },
    anthropic: { models: anthropicModels },
    gemini: { models: geminiModels },
    ollama: { normalizedRoot: normalizeOllamaRootBaseUrl('http://127.0.0.1:11434'), endpoint: ollamaEndpoint.url.href, kind: ollamaEndpoint.kind },
    openCodeGo: { calls: catalogCalls, models: goCatalog },
    backupKey: { models: keyFailure, request: openaiRequests.at(-1) },
    failures: {
      http401: httpFailure, missingContainer: containerFailure, connect: connectFailure,
      detailLength: Array.from(authFailureDetail).slice(0, 2048).length,
    },
  };
}

const headerMatrix = {
  openai: vendorModelHeaders(listVendor({ headers: { 'X-Trace': '1', Host: 'evil' } }), 'openai'),
  gemini: vendorModelHeaders(listVendor(), 'gemini'),
  anthropicEmptyKey: vendorModelHeaders(listVendor({ apiKey: '' }), 'anthropic'),
  anthropicCustomVersion: vendorModelHeaders(listVendor({ providerType: 'anthropic', headers: { 'anthropic-version': '1999-01-01' } }), 'anthropic'),
};

const endpoints = {
  geminiRoot: vendorModelsEndpoint('https://generativelanguage.googleapis.com', 'gemini', null).url.href,
  geminiVersioned: vendorModelsEndpoint('https://generativelanguage.googleapis.com/v1beta', 'google', null).url.href,
  anthropicMessages: vendorModelsEndpoint('https://api.anthropic.com/v1/messages', 'anthropic', null).url.href,
  openaiChat: vendorModelsEndpoint('https://api.openai.com/v1/chat/completions', 'openai', null).url.href,
  relayResponses: vendorModelsEndpoint('https://gateway.example.com/custom/responses', 'openai', null).url.href,
  explicitProtocolKeepsOpenai: (() => { const { url, kind } = vendorModelsEndpoint('https://api.openai.com/v1', 'gemini', 'openai_responses'); return { url: url.href, kind }; })(),
  ollamaRoot: vendorModelsEndpoint('http://127.0.0.1:11434', 'ollama', null).url.href,
};

const parsedPages = {
  gemini: parseVendorModelsPage({
    models: [
      { name: 'models/gemini-3-pro', displayName: 'Gemini 3 Pro', supportedGenerationMethods: ['generateContent'], inputTokenLimit: 1_000_000, outputTokenLimit: 65_536 },
      { name: 'models/embedding-1', supportedGenerationMethods: ['embedContent'] },
    ],
    nextPageToken: 'next',
  }, 'gemini'),
  openai: parseVendorModelsPage({
    data: [{ id: 'gpt-5.4-mini', display_name: 'GPT-5.4 Mini', context_length: 400_000 }, { id: 'gpt-tts-1' }],
    has_more: true,
    last_id: 'gpt-5.4-mini',
  }, 'openai'),
  anthropic: parseVendorModelsPage({
    data: [
      { id: 'claude-sonnet-5', type: 'model', display_name: 'Claude Sonnet 5', max_output_tokens: 64_000 },
      { id: 'other', type: 'tool' },
    ],
  }, 'anthropic'),
};

// ---------------------------------------------------------------- opencode go

const opencodeGo = {
  vendorId: openCodeGo.VENDOR_ID, providerType: openCodeGo.PROVIDER_TYPE,
  openaiBaseUrl: openCodeGo.OPENAI_BASE_URL, anthropicBaseUrl: openCodeGo.ANTHROPIC_BASE_URL,
  googleBaseUrl: openCodeGo.GOOGLE_BASE_URL, usageUrl: openCodeGo.USAGE_URL,
  sessionHeader: openCodeGo.SESSION_HEADER, taskSessionId: openCodeGo.TASK_SESSION_ID,
  userAgent: openCodeGo.userAgent(appVersion),
  detection: [
    { vendorId: 'builtin-opencode-go', providerType: null, baseUrl: 'https://example.com/v1', value: openCodeGo.isOpencodeGoConfig({ vendorId: 'builtin-opencode-go', providerType: null, baseUrl: 'https://example.com/v1' }) },
    { vendorId: null, providerType: 'opencode_go', baseUrl: 'https://example.com/v1', value: openCodeGo.isOpencodeGoConfig({ vendorId: null, providerType: 'opencode_go', baseUrl: 'https://example.com/v1' }) },
    { vendorId: 'custom-1', providerType: 'openai', baseUrl: 'https://opencode.ai/inference/go/openai/v1/', value: openCodeGo.isOpencodeGoConfig({ vendorId: 'custom-1', providerType: 'openai', baseUrl: 'https://opencode.ai/inference/go/openai/v1/' }) },
    { vendorId: null, providerType: null, baseUrl: 'https://OpenCode.ai/zen/go/v1', value: openCodeGo.isOpencodeGoConfig({ vendorId: null, providerType: null, baseUrl: 'https://OpenCode.ai/zen/go/v1' }) },
    { vendorId: 'builtin-deepseek', providerType: 'deepseek', baseUrl: 'https://api.deepseek.com/v1', value: openCodeGo.isOpencodeGoConfig({ vendorId: 'builtin-deepseek', providerType: 'deepseek', baseUrl: 'https://api.deepseek.com/v1' }) },
    { vendorId: null, providerType: 'openai', baseUrl: 'https://opencode.ai/zen/v1', value: openCodeGo.isOpencodeGoConfig({ vendorId: null, providerType: 'openai', baseUrl: 'https://opencode.ai/zen/v1' }) },
  ],
  sessionSanitized: openCodeGo.sessionHeaderValue(sessionId),
  sessionEmpty: openCodeGo.sessionHeaderValue('  '),
  sessionOverflowLength: openCodeGo.sessionHeaderValue('x'.repeat(500))?.length,
  identitySession: openCodeGo.identityHeaders('sess_01HX-abc', appVersion),
  identityTask: openCodeGo.identityHeaders('   ', appVersion),
  baseUrlForProtocol: {
    anthropic: openCodeGo.baseUrlForProtocol(openCodeGo.OPENAI_BASE_URL, 'anthropic_messages'),
    google: openCodeGo.baseUrlForProtocol(openCodeGo.OPENAI_BASE_URL, 'google_generate_content'),
    responsesBackToOpenai: openCodeGo.baseUrlForProtocol(openCodeGo.ANTHROPIC_BASE_URL, 'openai_responses'),
    nonGoRootPassthrough: openCodeGo.baseUrlForProtocol('https://proxy.example.com/v1', 'anthropic_messages'),
  },
  builtinModels: openCodeGo.BUILTIN_MODELS,
};

// ---------------------------------------------------------------- stream frames

const streamAdapter = new OpenAIAdapter();
const { stringifyProviderToolCall } = await frozen(`${wire}/tool-call-values.ts`);
const streamFrames = [
  { data: { choices: [{ delta: { reasoning_content: '合成思考片段' } }] } },
  { data: { choices: [{ delta: { content: '哈' } }] } },
  { data: { choices: [{ delta: { tool_calls: [{ index: 0, id: 'call_fixture', type: 'function', function: { name: 'fixture_lookup', arguments: '{"q"' } }] } }] } },
  { data: { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: ':"🧪"}' } }] }, finish_reason: 'tool_calls' }] } },
  { data: { choices: [], usage: { prompt_tokens: 12, completion_tokens: 5, total_tokens: 17, prompt_tokens_details: { cached_tokens: 2, cache_write_tokens: 1 }, completion_tokens_details: { reasoning_tokens: 3 } } } },
  { data: '[DONE]' },
];
const streamEvents = streamFrames
  .map(frame => `data: ${typeof frame.data === 'string' ? frame.data : JSON.stringify(frame.data)}`)
  .flatMap(line => streamAdapter.parseStream(line))
  .map(event => {
    assert.ok(['ContentChunk', 'ReasoningChunk', 'ToolCall', 'Usage', 'Done'].includes(event.type), `unexpected stream event ${event.type}`);
    if (event.type === 'ToolCall') return { kind: 'tool', value: stringifyProviderToolCall(event.value) };
    const kinds = { ContentChunk: 'text', ReasoningChunk: 'reasoning', Usage: 'usage' };
    if (event.type === 'Done') return { kind: 'completed' };
    assert.ok(kinds[event.type], `unexpected stream event ${event.type}`);
    return { kind: kinds[event.type], value: event.value };
  });

const artifact = {
  format: 'dstu-s3-08-endpoint-oracle/v1',
  desktop: head, desktopTree: tree, source: 'Synthetic custom/local/relay endpoint inputs replayed with the frozen desktop implementations.',
  token, sessionId, appVersion, messages,
  cases: [...cases.map(buildChatCase), ...routes.map(buildRouteCase), ...responsesCases.map(buildResponsesCase)],
  stream: { frames: streamFrames.map(frame => typeof frame.data === 'string' ? frame.data : JSON.stringify(frame.data)), events: streamEvents },
  opencodeGo, modelLists: await buildModelLists(), headerMatrix, endpoints, parsedPages,
};

const output = join(root, 'app/src/test/resources/s3-08-endpoints-oracle.json');
const serialized = `${JSON.stringify(artifact, null, 2)}\n`;
if (process.argv[2] === '--write') writeFileSync(output, serialized);
else if (process.argv[2] === '--verify') assert.equal(readFileSync(output, 'utf8'), serialized, 'S3.08 frozen oracle drift');
else throw new Error('use --verify or --write');
console.log(`S3.08 frozen desktop ${head}: ${artifact.cases.length} cases, ${artifact.opencodeGo.builtinModels.length} Go catalog models, ${artifact.modelLists.openai.models.length} paged models`);
for (const item of artifact.cases) console.log(item.id, item.adapter, item.request?.url ?? item.baseForProtocol);
