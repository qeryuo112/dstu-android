import assert from 'node:assert/strict';
import { sourceUrl, installedIncrementIds, registerIncrement } from './loader.mjs';
import { installTemplateAssetIncrement } from './templates-assets.mjs';
import { installMediaTimeIncrement } from './media-time.mjs';

const SETTINGS_KEYS = 'host/plugins/sync/src/classification-data/settings.ts';
const SETTINGS_KEYS_SHA256 = '4d690c078d90e5dc4c9f3e21da7069d63ea7ada9c3de01aed86afea7d025b3cd';
const PREFERENCES_SYNC = 'host/plugins/sync/src/preferences-sync.ts';
const PREFERENCES_SYNC_SHA256 = '279c2807066d518fac4930bbcf0b5b2e2e6d05ee3a5aec3576f0586187d9d5b5';

export const THEME_MODES = Object.freeze(['light', 'dark', 'auto']);
export const THEME_PALETTES = Object.freeze([
  'default', 'purple', 'green', 'orange', 'pink', 'bright-pink', 'teal', 'muted', 'paper', 'custom',
]);
export const PORTABLE_SYNCABLE_KEYS = Object.freeze([
  'pdf.settings',
  'pomodoro.settings',
  'portable.model.preferences.v1',
  'portable.tool.preferences.v1',
  'shortcuts.custom',
  'skill.ui.bundles',
  'skill.ui.favorites',
  'sync.include_plugins',
  'sync.include_skills',
  'theme',
  'theme_custom_color',
  'theme_palette',
  'translation.promptTemplates.v1',
]);

const PORTABLE = new Set([
  ...PORTABLE_SYNCABLE_KEYS,
  'dstu-theme-custom-color', 'dstu-theme-mode', 'dstu-theme-palette',
]);

export const THEME_VARIANT_KEY = 'ds.ui.themeVariant';
export const PLUGIN_SECRET_PREFIX = 'secret.plugin.';
export const PLUGIN_SCHEDULE_PREFIX = 'plugin.schedule.';

/**
 * Evidence-backed authorization boundaries frozen with S0.23. Exact keys and
 * prefixes here always win over the portable allowlist, so a later allowlist
 * edit cannot accidentally make executable authorization portable.
 */
export const AUTHORIZATION_BOUNDARIES = Object.freeze([
  {
    id: 'skill-trust-authorization',
    scope: 'local',
    key: 'skill.ui.trust_overrides',
    reason: 'trust is executable authorization and remains on the device that granted it',
    evidence: [
      { source: 'desktop', file: 'host/plugins/sync/src/classification-data/settings.ts', line: 3, symbol: 'SYNCABLE_SETTING_KEYS' },
      { source: 'desktop', file: 'src/features/chat/skills/skillSettingsMirror.ts', line: 35, symbol: 'SKILL_UI_SETTING_KEYS.trust' },
    ],
  },
  {
    id: 'skill-enable-authorization',
    scope: 'local',
    key: 'skill.ui.enable_overrides',
    reason: 'skill enablement is executable authorization and remains device-local',
    evidence: [
      { source: 'desktop', file: 'host/plugins/sync/src/classification-data/settings.ts', line: 3, symbol: 'SYNCABLE_SETTING_KEYS' },
      { source: 'desktop', file: 'src/features/chat/skills/skillSettingsMirror.ts', line: 34, symbol: 'SKILL_UI_SETTING_KEYS.enable' },
    ],
  },
  {
    id: 'permission-preset-policy',
    scope: 'local',
    key: 'chat.defaultPermissionPreset',
    reason: 'permission preset changes execution policy and remains device-local',
    evidence: [
      { source: 'desktop', file: 'host/plugins/sync/src/classification-data/settings.ts', line: 4, symbol: 'SYNCABLE_SETTING_KEYS' },
      { source: 'desktop', file: 'src/features/chat/core/session/permissionPresetDefaults.ts', line: 22, symbol: 'DEFAULT_PERMISSION_PRESET_SETTING_KEY' },
    ],
  },
  {
    id: 'plugin-theme-variant-selection',
    scope: 'local',
    key: THEME_VARIANT_KEY,
    reason: 'the pick references <plugin id>/<contribution id>; it is stored per device and only applies while that plugin is installed',
    evidence: [
      { source: 'desktop', file: 'src/features/settings/themeVariants.ts', line: 17, symbol: 'THEME_VARIANT_STORAGE_KEY' },
      { source: 'desktop', file: 'src/features/settings/themeVariants.ts', line: 9, symbol: 'theme.variants owner lifetime' },
    ],
    baselineRef: [
      'contributions.slotSurfaces[theme.variants]',
      'contributions.compatibilityEvidence[settings.theme-variant-selection]',
    ],
  },
  {
    id: 'plugin-schedule-execution-history',
    scope: 'local',
    prefix: PLUGIN_SCHEDULE_PREFIX,
    sample: 'plugin.schedule.demo.job',
    reason: 'plugin schedule state records last/since execution history and is device-local',
    evidence: [
      { source: 'desktop', file: 'host/plugins/plugin-services/src/schedule.ts', line: 242, symbol: 'settingsSchedulerStore' },
    ],
  },
  {
    id: 'plugin-secrets',
    scope: 'secret',
    prefix: PLUGIN_SECRET_PREFIX,
    sample: 'secret.plugin.demo.note',
    reason: 'plugin secrets live in the secure store under secret.plugin.* and never sync as preferences',
    evidence: [
      { source: 'desktop', file: 'host/plugins/plugin-services/api.ts', line: 35, symbol: 'pluginSecretSettingsKey' },
      { source: 'desktop', file: 'host/plugins/plugin-services/src/secrets.ts', line: 14, symbol: 'PluginSecrets' },
      { source: 'desktop', file: 'host/platform/secrets/sensitive-key.ts', line: 4, symbol: 'isSensitiveKey' },
    ],
  },
]);

const LOCAL_OVERRIDES = new Map(
  AUTHORIZATION_BOUNDARIES
    .filter(item => item.scope === 'local' && typeof item.key === 'string')
    .map(item => [item.key, item.reason]),
);
const BOUNDARY_PREFIXES = Object.freeze(
  AUTHORIZATION_BOUNDARIES
    .filter(item => typeof item.prefix === 'string')
    .map(item => ({ prefix: item.prefix, scope: item.scope, reason: item.reason })),
);
const SECRET_EXACT = new Set([
  'api_configs', 'cloud_storage', 'cloud_storage_credentials',
  'siliconflow.api_key', 'siliconflow_api_key',
]);
const SECRET_PREFIXES = Object.freeze([
  // Matches desktop isSensitiveKey(), which treats every `secret*` key as
  // secure-store-only. `secret.plugin.<pluginId>.<key>` is the plugin form.
  'secret',
  'internal.oauth.', 'mcp.servers.', 'mcp.tools.', 'mcp.transport.',
  'plugin.ilinkbot.credentials', 'web_search.api_key.', 'web_search.searxng.api_key',
]);
const CREDENTIAL_KEYS = new Set([
  'accesstoken', 'apikey', 'apikeys', 'auth', 'authorization',
  'bearer', 'clientsecret', 'cookie', 'cookies', 'credentials', 'env',
  'environment', 'envvars', 'headers', 'httpproxy', 'oauth', 'password',
  'privatekey', 'processenv', 'refreshtoken', 'secret', 'token',
]);
const CREDENTIAL_SUFFIXES = Object.freeze([
  'apikey', 'authorization', 'bearer', 'cookie', 'credential', 'credentials',
  'env', 'environment', 'headers', 'httpproxy', 'oauth', 'password', 'privatekey',
  'secret', 'token',
]);
const EXECUTABLE_FIELDS = new Set([
  'args', 'command', 'cwd', 'executable', 'filepath', 'localpath', 'path',
  'permissions', 'trust', 'workingdir', 'absolutepath',
]);
const EXECUTABLE_SUFFIXES = Object.freeze([
  'command', 'cwd', 'filepath', 'localpath', 'path', 'workingdir',
]);
const MODEL_FIELDS = new Set([
  'apiProtocol', 'baseUrl', 'contextWindow', 'id', 'isAudioTranscription',
  'isEmbedding', 'isFavorite', 'isImageGeneration', 'isMultimodal', 'isReasoning',
  'isReranker', 'maxOutputTokens', 'model', 'name', 'providerType',
  'supportsOpenAIResponses', 'supportsTools', 'temperature', 'vendorId', 'vendorName',
]);
const TOOL_FIELDS = new Set(['description', 'id', 'name', 'transportType']);
const THEME_KEYS = new Set(['theme', 'dstu-theme-mode']);
const PALETTE_KEYS = new Set(['theme_palette', 'dstu-theme-palette']);
const COLOR_KEYS = new Set(['theme_custom_color', 'dstu-theme-custom-color']);
const MAX_VALUE_BYTES = 1024 * 1024;
const MAX_MODELS = 64;
const MAX_TOOLS = 256;

export class PortableConfigError extends Error {
  constructor(code, message) {
    super(`[${code}] ${message}`);
    this.name = 'PortableConfigError';
    this.code = code;
  }
}

const fail = (code, message) => { throw new PortableConfigError(code, message); };
const normalizeKey = key => String(key).replaceAll(/[^a-z0-9]/giu, '').toLowerCase();
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);

function isSecretKey(key) {
  return SECRET_EXACT.has(key) || key.endsWith('.api_key') || key.endsWith('.apiKey')
    || SECRET_PREFIXES.some(prefix => key.startsWith(prefix));
}

function forbiddenField(key) {
  const normalized = normalizeKey(key);
  return CREDENTIAL_KEYS.has(normalized)
    || CREDENTIAL_SUFFIXES.some(suffix => normalized.endsWith(suffix));
}

function assertNoExecutableFields(value, path = '$') {
  if (Array.isArray(value)) {
    value.forEach((item, index) => assertNoExecutableFields(item, `${path}[${index}]`));
    return;
  }
  if (!isObject(value)) return;
  for (const [key, child] of Object.entries(value)) {
    const normalized = normalizeKey(key);
    if (EXECUTABLE_FIELDS.has(normalized)
      || EXECUTABLE_SUFFIXES.some(suffix => normalized.endsWith(suffix))) {
      fail('EXECUTABLE_FIELD', `executable or trust field rejected at ${path}.${key}`);
    }
    assertNoExecutableFields(child, `${path}.${key}`);
  }
}

/** Explicit settingId classification. Unknown identifiers are local by default. */
export function classifySetting(key) {
  assert.equal(typeof key, 'string', 'setting id must be a string');
  assert.ok(key.length > 0 && key.length <= 512, 'setting id length');
  if (LOCAL_OVERRIDES.has(key)) {
    return { key, scope: 'local', reason: LOCAL_OVERRIDES.get(key) };
  }
  const boundaryPrefix = BOUNDARY_PREFIXES.find(item => key.startsWith(item.prefix));
  if (boundaryPrefix) {
    return { key, scope: boundaryPrefix.scope, reason: boundaryPrefix.reason };
  }
  if (isSecretKey(key)) return { key, scope: 'secret', reason: 'credential or secure-store key' };
  if (PORTABLE.has(key)) return { key, scope: 'portable', reason: 'vetted portable appearance/config key' };
  return { key, scope: 'local', reason: 'unknown or device-bound setting defaults to local' };
}

function assertNoSecretFields(value, path = '$') {
  if (Array.isArray(value)) {
    value.forEach((item, index) => assertNoSecretFields(item, `${path}[${index}]`));
    return;
  }
  if (!isObject(value)) return;
  for (const [key, child] of Object.entries(value)) {
    if (forbiddenField(key)) {
      fail('NESTED_SECRET', `credential field rejected at ${path}.${key}`);
    }
    assertNoSecretFields(child, `${path}.${key}`);
  }
}

function parseStructured(value, label) {
  if (typeof value === 'string') {
    if (Buffer.byteLength(value) > MAX_VALUE_BYTES) fail('VALUE_TOO_LARGE', `${label} exceeds 1 MiB`);
    try { return JSON.parse(value); }
    catch (error) { fail('INVALID_JSON', `${label} is not valid JSON: ${error.message}`); }
  }
  assertNoSecretFields(value);
  return value;
}

function finiteNumber(value, field, path) {
  if (value === undefined) return undefined;
  if (typeof value !== 'number' || !Number.isFinite(value)) fail('INVALID_VALUE', `${path}.${field} must be finite`);
  return value;
}

function scalar(value, field, path, maximum = 512) {
  if (value === undefined) return undefined;
  if (typeof value === 'string' && value.length <= maximum) return value;
  if (typeof value === 'boolean') return value;
  fail('INVALID_VALUE', `${path}.${field} has invalid type or length`);
}

function portableUrl(value, path) {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || value.length === 0 || value.length > 2048) {
    fail('INVALID_VALUE', `${path}.baseUrl must be a non-empty string`);
  }
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    fail('INVALID_VALUE', `${path}.baseUrl is not a URL`);
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password
    || parsed.search || parsed.hash) {
    fail('INVALID_VALUE', `${path}.baseUrl must be credential-free http(s) without query or fragment`);
  }
  return value;
}

function sanitizeModel(model, path) {
  if (!isObject(model)) fail('INVALID_VALUE', `${path} must be an object`);
  assertNoExecutableFields(model, path);
  const portable = {};
  for (const field of MODEL_FIELDS) {
    const item = field === 'baseUrl' ? portableUrl(model[field], path)
      : ['maxOutputTokens', 'temperature', 'contextWindow'].includes(field)
        ? finiteNumber(model[field], field, path) : scalar(model[field], field, path);
    if (item !== undefined) portable[field] = item;
  }
  if (typeof portable.id !== 'string' || portable.id.length === 0) fail('INVALID_VALUE', `${path}.id is required`);
  if (typeof portable.model !== 'string' || portable.model.length === 0) fail('INVALID_VALUE', `${path}.model is required`);
  return portable;
}

function sanitizeTool(tool, path) {
  if (!isObject(tool)) fail('INVALID_VALUE', `${path} must be an object`);
  // Top-level launch fields are intentionally excluded from the portable
  // projection. If they appear nested under unknown objects, reject them so a
  // future schema cannot smuggle executable configuration through a field.
  for (const [key, value] of Object.entries(tool)) {
    if (!TOOL_FIELDS.has(key) && (isObject(value) || Array.isArray(value))) {
      assertNoExecutableFields(value, `${path}.${key}`);
    }
  }
  const portable = {};
  for (const field of TOOL_FIELDS) {
    const item = scalar(tool[field], field, path);
    if (item !== undefined) portable[field] = item;
  }
  if (typeof portable.id !== 'string' || portable.id.length === 0) fail('INVALID_VALUE', `${path}.id is required`);
  return portable;
}

function collection(input, singular) {
  if (Array.isArray(input)) return input;
  if (isObject(input) && Array.isArray(input[`${singular}s`])) return input[`${singular}s`];
  if (isObject(input)) return [input];
  fail('INVALID_VALUE', `${singular} config must be an object or array`);
}

/**
 * Parse a model/tool input through an allowlist. Credential fields are explicit
 * errors; executable/local fields are reported in excluded buckets and never
 * copied into the portable value.
 */
export function sanitizePortableConfig(kind, input) {
  if (!['model', 'tool'].includes(kind)) fail('UNKNOWN_KIND', `unsupported config kind ${kind}`);
  assertNoSecretFields(input);
  const items = collection(input, kind);
  if (kind === 'model') {
    if (items.length > MAX_MODELS) fail('VALUE_TOO_LARGE', `model count exceeds ${MAX_MODELS}`);
    const models = items.map((item, index) => sanitizeModel(item, `$.models[${index}]`));
    return {
      version: 1,
      kind,
      portable: { models },
      excluded: {
        local: ['enabled', 'authMode', 'extraBody', 'unknown fields'],
        secret: ['apiKey', 'apiKeys', 'headers', 'credentials'],
      },
    };
  }
  if (items.length > MAX_TOOLS) fail('VALUE_TOO_LARGE', `tool count exceeds ${MAX_TOOLS}`);
  const tools = items.map((item, index) => sanitizeTool(item, `$.tools[${index}]`));
  return {
    version: 1,
    kind,
    portable: { tools },
    excluded: {
      local: ['args', 'command', 'cwd', 'enabled', 'framing', 'permissions', 'trust', 'url', 'unknown fields'],
      secret: ['credentials', 'env', 'headers'],
    },
  };
}

function validateGenericJson(value, key) {
  if (typeof value !== 'string') fail('INVALID_VALUE', `${key} must be a string`);
  if (Buffer.byteLength(value) > MAX_VALUE_BYTES) fail('VALUE_TOO_LARGE', `${key} exceeds 1 MiB`);
  if (/^\s*[[{]/u.test(value)) {
    let parsed;
    try { parsed = JSON.parse(value); }
    catch (error) { fail('INVALID_JSON', `${key} is not valid JSON: ${error.message}`); }
    assertNoSecretFields(parsed, `$.${key}`);
    assertNoExecutableFields(parsed, `$.${key}`);
  }
  return value;
}

/** Validate and canonicalize one portable value. Local and secret keys are rejected. */
export function normalizePortableValue(key, value) {
  const classification = classifySetting(key);
  if (classification.scope !== 'portable') {
    fail('NOT_PORTABLE', `${key} is ${classification.scope}: ${classification.reason}`);
  }
  if (THEME_KEYS.has(key)) {
    assert.ok(THEME_MODES.includes(value), `${key} must be light, dark, or auto`);
    return value;
  }
  if (PALETTE_KEYS.has(key)) {
    assert.ok(THEME_PALETTES.includes(value), `${key} is not a known palette`);
    return value;
  }
  if (COLOR_KEYS.has(key)) {
    assert.match(String(value), /^#[0-9a-f]{6}$/iu, `${key} must be #RRGGBB`);
    return String(value).toLowerCase();
  }
  if (key === 'portable.model.preferences.v1') {
    const normalized = sanitizePortableConfig('model', parseStructured(value, key));
    return JSON.stringify({ version: 1, models: normalized.portable.models });
  }
  if (key === 'portable.tool.preferences.v1') {
    const normalized = sanitizePortableConfig('tool', parseStructured(value, key));
    return JSON.stringify({ version: 1, tools: normalized.portable.tools });
  }
  return validateGenericJson(value, key);
}

/** Persist only a schema-validated portable setting through the supplied store. */
export function persistPortableSetting(settings, key, value) {
  assert.equal(typeof settings?.save, 'function', 'settings store with save()');
  const serialized = normalizePortableValue(key, value);
  settings.save(key, serialized);
  return { key, value: serialized, scope: 'portable' };
}

/**
 * Capture only explicit portable keys. Secret/local rows are never enumerated,
 * so this function cannot read credentials from a settings store.
 */
export function capturePortableSettings(settings) {
  assert.equal(typeof settings?.get, 'function', 'settings store with get()');
  const rows = [];
  for (const key of PORTABLE_SYNCABLE_KEYS) {
    const value = settings.get(key);
    if (value === null || value === undefined) continue;
    rows.push({ key, value: normalizePortableValue(key, value), scope: 'portable' });
  }
  return { version: 1, rows };
}

export function replayPortableSettings(settings, snapshot) {
  if (!isObject(snapshot) || snapshot.version !== 1 || !Array.isArray(snapshot.rows)) {
    fail('INVALID_SNAPSHOT', 'portable snapshot must be version 1 with rows');
  }
  const seen = new Set();
  const validated = snapshot.rows.map(row => {
    assert.equal(typeof row?.key, 'string', 'snapshot row key');
    if (seen.has(row.key)) fail('INVALID_SNAPSHOT', `duplicate snapshot key ${row.key}`);
    seen.add(row.key);
    return { key: row.key, value: normalizePortableValue(row.key, row.value) };
  });
  return validated.map(row => {
    settings.save(row.key, row.value);
    return { key: row.key, value: row.value, scope: 'portable' };
  });
}

const APPEARANCE = Object.freeze({
  mode: { sqlite: 'theme', local: 'dstu-theme-mode' },
  palette: { sqlite: 'theme_palette', local: 'dstu-theme-palette' },
  customColor: { sqlite: 'theme_custom_color', local: 'dstu-theme-custom-color' },
});

function appearanceValue(settings, localStorage, field) {
  const source = APPEARANCE[field];
  const localValue = typeof localStorage?.getItem === 'function' ? localStorage.getItem(source.local) : null;
  // useTheme's localStorage mode is authoritative; AppearanceTab mirrors the
  // palette and custom color into SQLite for recovery and sync.
  const sqliteValue = settings.get(source.sqlite);
  const value = field === 'mode' ? (localValue ?? sqliteValue) : (sqliteValue ?? localValue);
  return value === null || value === undefined
    ? null
    : { source: field === 'mode' && localValue !== null ? 'localStorage' : (sqliteValue !== null ? 'settings' : 'localStorage'),
        key: field === 'mode' && localValue !== null ? source.local : (sqliteValue !== null ? source.sqlite : source.local),
        value };
}

/** Capture the actual useTheme/AppearanceTab producer keys without reading secrets. */
export function captureDesktopAppearance({ settings, localStorage }) {
  assert.equal(typeof settings?.get, 'function', 'settings store with get()');
  const fields = {};
  for (const field of Object.keys(APPEARANCE)) fields[field] = appearanceValue(settings, localStorage, field);
  for (const [field, item] of Object.entries(fields)) {
    if (item !== null) normalizePortableValue(APPEARANCE[field].sqlite, item.value);
  }
  return { version: 1, fields };
}

/** Replay appearance into the same SQLite mirror and useTheme localStorage keys. */
export function replayDesktopAppearance({ settings, localStorage }, snapshot) {
  if (!isObject(snapshot) || snapshot.version !== 1 || !isObject(snapshot.fields)) {
    fail('INVALID_SNAPSHOT', 'appearance snapshot must be version 1 with fields');
  }
  const validated = [];
  for (const [field, item] of Object.entries(snapshot.fields)) {
    if (!Object.hasOwn(APPEARANCE, field)) fail('INVALID_SNAPSHOT', `unknown appearance field ${field}`);
    if (item === null) continue;
    const source = APPEARANCE[field];
    const value = normalizePortableValue(source.sqlite, item.value);
    validated.push({ field, source, value });
  }
  const applied = [];
  for (const { field, source, value } of validated) {
    settings.save(source.sqlite, value);
    if (typeof localStorage?.setItem === 'function') localStorage.setItem(source.local, value);
    applied.push({ field, sqlite: source.sqlite, local: source.local, value });
  }
  return { version: 1, applied };
}

async function prepareRemotePreferences(storage, codec) {
  const listing = await storage.listOutcome('data_governance/preferences');
  if (listing.truncated) fail('LIST_TRUNCATED', 'preference listing is truncated');
  const files = listing.files.filter(item => item.key.endsWith('.json'));
  const preparedBytes = new Map();
  for (const file of files) {
    const raw = await storage.get(file.key);
    if (raw === null || raw === undefined) continue;
    let rows;
    try {
      rows = JSON.parse(new TextDecoder().decode(await codec.decode(raw)));
    } catch (error) {
      fail('INVALID_REMOTE', `${file.key} cannot be decoded: ${error.message}`);
    }
    if (!Array.isArray(rows)) fail('INVALID_REMOTE', `${file.key} must contain an array`);
    const canonicalRows = rows.map((row, index) => {
      if (!isObject(row) || Object.keys(row).sort().join(',') !== 'key,updatedAt,value') {
        fail('INVALID_REMOTE', `${file.key}[${index}] must have exactly key, value, updatedAt`);
      }
      if (classifySetting(row.key).scope !== 'portable') {
        fail('NON_PORTABLE_REMOTE', `${file.key}[${index}] contains non-portable key ${row.key}`);
      }
      if (typeof row.value !== 'string' || typeof row.updatedAt !== 'string') {
        fail('INVALID_REMOTE', `${file.key}[${index}] has an invalid value or timestamp`);
      }
      strictTimestamp(row.updatedAt);
      return { key: row.key, value: normalizePortableValue(row.key, row.value), updatedAt: row.updatedAt };
    });
    preparedBytes.set(file.key, await codec.encode(new TextEncoder().encode(JSON.stringify(canonicalRows))));
  }
  return { listing: { ...listing, files: files.map(file => ({ ...file })) }, bytes: preparedBytes };
}

const MAX_TIMESTAMP_CHARS = 40;
const RFC3339_TIMESTAMP = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/u;

function strictTimestamp(value) {
  if (typeof value !== 'string' || value.length > MAX_TIMESTAMP_CHARS) {
    fail('INVALID_TIMESTAMP', 'updatedAt must be a bounded RFC3339 timestamp');
  }
  const match = RFC3339_TIMESTAMP.exec(value);
  if (match === null) fail('INVALID_TIMESTAMP', `updatedAt is not strict RFC3339: ${value}`);
  const [, yearText, monthText, dayText, hourText, minuteText, secondText, , zone] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const days = new Date(Date.UTC(year, month, 0)).getUTCDate();
  if (year < 1000 || month < 1 || month > 12 || day < 1 || day > days
    || hour > 23 || minute > 59 || second > 59) {
    fail('INVALID_TIMESTAMP', `updatedAt has an invalid calendar field: ${value}`);
  }
  if (zone !== 'Z') {
    const offsetHour = Number(zone.slice(1, 3));
    const offsetMinute = Number(zone.slice(4, 6));
    if (offsetHour > 23 || offsetMinute > 59) {
      fail('INVALID_TIMESTAMP', `updatedAt has an invalid offset: ${value}`);
    }
  }
  const millis = Date.parse(value);
  if (!Number.isFinite(millis)) fail('INVALID_TIMESTAMP', `updatedAt cannot be parsed: ${value}`);
  return millis;
}

/**
 * One read-through view for validation and the production delegate. The
 * delegate cannot observe a different remote object between the security check
 * and winner selection/publication.
 */
function cacheCloudReads(storage, prepared) {
  return new Proxy(storage, {
    get(target, property) {
      if (property === 'listOutcome') {
        return async prefix => {
          if (prefix === 'data_governance/preferences') {
            return { truncated: prepared.listing.truncated,
              files: prepared.listing.files.map(file => ({ ...file })) };
          }
          const result = await target.listOutcome(prefix);
          return { truncated: result.truncated, files: result.files.map(file => ({ ...file })) };
        };
      }
      if (property === 'get') {
        return async key => {
          if (prepared.bytes.has(key)) return new Uint8Array(prepared.bytes.get(key));
          return target.get(key);
        };
      }
      const value = Reflect.get(target, property, target);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
}

/** Delegate sees canonical portable rows only; secret/local rows are not exposed. */
function canonicalPortableStore(settings) {
  const rows = new Map();
  for (const key of PORTABLE_SYNCABLE_KEYS) {
    const value = settings.get(key);
    if (value === null || value === undefined) continue;
    const matches = settings.listByPrefix(key).filter(row => row.key === key);
    const updatedAt = matches[0]?.updatedAt ?? new Date(0).toISOString();
    rows.set(key, { key, value: normalizePortableValue(key, value), updatedAt });
  }
  const copy = row => ({ key: row.key, value: row.value, updatedAt: row.updatedAt });
  return {
    get(key) {
      if (classifySetting(key).scope !== 'portable') return null;
      return rows.get(key)?.value ?? null;
    },
    save(key, value) {
      if (classifySetting(key).scope !== 'portable') {
        fail('NOT_PORTABLE', `${key} cannot be written through the portable store`);
      }
      const canonical = normalizePortableValue(key, value);
      settings.save(key, canonical);
      const previous = rows.get(key);
      const matches = settings.listByPrefix(key).filter(row => row.key === key);
      rows.set(key, { key, value: canonical, updatedAt: matches[0]?.updatedAt ?? previous?.updatedAt ?? new Date(0).toISOString() });
    },
    delete(key) {
      rows.delete(key);
      return settings.delete(key);
    },
    listByPrefix(prefix) {
      return [...rows.values()].filter(row => row.key.startsWith(prefix)).map(copy);
    },
    deleteByPrefix(prefix) {
      let deleted = 0;
      for (const key of [...rows.keys()]) {
        if (!key.startsWith(prefix)) continue;
        rows.delete(key);
        if (settings.delete(key)) deleted++;
      }
      return deleted;
    },
    async getSecret(key) {
      return classifySetting(key).scope === 'portable' ? this.get(key) : null;
    },
    async saveSecret(key, value) {
      this.save(key, value);
    },
    async deleteSecret(key) {
      return this.delete(key);
    },
  };
}

/**
 * Fail-closed preference entry point. It validates all local portable values and
 * every remote row before delegating winner selection, local writes, and
 * publication to the production syncPreferences implementation.
 */
export async function syncPortablePreferences(storage, settings, deviceId, direction, codec, productionSync) {
  if (!['Upload', 'Download', 'Bidirectional'].includes(direction)) {
    fail('INVALID_DIRECTION', `unsupported sync direction ${direction}`);
  }
  assert.equal(typeof settings?.get, 'function', 'settings store');
  assert.equal(typeof settings?.save, 'function', 'settings store');
  const canonical = canonicalPortableStore(settings);
  const prepared = await prepareRemotePreferences(storage, codec);
  const cachedStorage = cacheCloudReads(storage, prepared);
  const delegate = productionSync ?? (await import(sourceUrl('host/plugins/sync/src/preferences-sync.ts'))).syncPreferences;
  assert.equal(typeof delegate, 'function', 'production syncPreferences delegate');
  await delegate(cachedStorage, canonical, deviceId, direction, codec);
}

/** Load the production sync entry point and its original implementation separately. */
export async function productionPreferencesSync() {
  const module = await import(sourceUrl(PREFERENCES_SYNC));
  assert.equal(typeof module.syncPreferences, 'function', 'production syncPreferences export');
  assert.equal(typeof module.syncPreferencesBaseline, 'function', 'renamed production implementation');
  return { syncPreferences: module.syncPreferences, baseline: module.syncPreferencesBaseline };
}

function replaceOnce(text, from, to, label) {
  const first = text.indexOf(from);
  assert.ok(first >= 0, `S0.23: missing source shape ${label}`);
  assert.equal(text.indexOf(from, first + 1), -1, `S0.23: ambiguous source shape ${label}`);
  return `${text.slice(0, first)}${to}${text.slice(first + from.length)}`;
}

const DESCRIPTOR = {
  unit: 'S0.23',
  id: 'portable-theme-model-tool-configuration',
  files: [
    {
      path: SETTINGS_KEYS,
      sha256: SETTINGS_KEYS_SHA256,
      transform(text) {
        const start = text.indexOf('export const SYNCABLE_SETTING_KEYS = new Set([');
        const endMarker = '\n]);\n';
        const end = text.indexOf(endMarker, start);
        assert.ok(start >= 0 && end > start, 'S0.23: settings whitelist shape');
        const replacement = `export const SYNCABLE_SETTING_KEYS = new Set([
${PORTABLE_SYNCABLE_KEYS.map(key => `  '${key}',`).join('\n')}
]);`;
        return replaceOnce(text, text.slice(start, end + endMarker.length), `${replacement}\n`, 'settings whitelist');
      },
    },
    {
      path: PREFERENCES_SYNC,
      sha256: PREFERENCES_SYNC_SHA256,
      transform(text) {
        let output = replaceOnce(text,
          'export async function syncPreferences(storage: CloudStorage, settings: SettingsStore, deviceId: string, direction: string, codec: FilePayloadCodec): Promise<void> {',
          'export async function syncPreferencesBaseline(storage: CloudStorage, settings: SettingsStore, deviceId: string, direction: string, codec: FilePayloadCodec): Promise<void> {',
          'preferences baseline rename');
        const adapterUrl = new URL('./portable-config.mjs', import.meta.url).href;
        output += `
import { syncPortablePreferences as syncPortablePreferencesS023 } from ${JSON.stringify(adapterUrl)};
export async function syncPreferences(
  storage: CloudStorage,
  settings: SettingsStore,
  deviceId: string,
  direction: string,
  codec: FilePayloadCodec,
): Promise<void> {
  return syncPortablePreferencesS023(storage, settings, deviceId, direction, codec, syncPreferencesBaseline);
}
`;
        return output;
      },
    },
  ],
};

/**
 * Install S0.23 after composing S0.21 and S0.22 in one loader hook. The source
 * transforms remain hash-bound to the locked desktop checkout.
 */
export function installPortableConfigIncrement() {
  const owned = [];
  if (!installedIncrementIds().includes('portable-templates-and-persistent-assets')) {
    owned.push(installTemplateAssetIncrement());
  }
  if (!installedIncrementIds().includes('portable-media-time-events')) {
    owned.push(installMediaTimeIncrement());
  }
  const handle = registerIncrement(DESCRIPTOR);
  return {
    ...handle,
    composed: ['S0.21', 'S0.22', 'S0.23'],
    whitelist: [...PORTABLE_SYNCABLE_KEYS],
    unregister() {
      handle.unregister();
      for (const prior of owned.reverse()) prior.unregister();
    },
  };
}

export const registerPortableConfigIncrement = () => installPortableConfigIncrement();
