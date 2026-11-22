import assert from 'node:assert/strict';
import {
  isJsonFloat, isJsonObject, parseJsonLossless, stringifyJsonLossless,
} from '../../../dstu-node/host/platform/util/lossless-json.ts';

export const MAX_DEPTH = 16;
export const MAX_RECORD_BYTES = 64 * 1024;
export const MAX_OWNER_RECORDS = 2000;
export const MAX_OWNER_BYTES = 5 * 1024 * 1024;

export class PluginDataError extends Error {
  constructor(code, message) {
    super(`[${code}] ${message}`);
    this.name = 'PluginDataError';
    this.code = code;
  }
}

export const fail = (code, message) => {
  throw new PluginDataError(code, message);
};

export function canonicalJson(value) {
  if (typeof value === 'bigint' || isJsonFloat(value)) return stringifyJsonLossless(value);
  if (typeof value === 'number' && !Number.isFinite(value)) fail('INVALID_JSON', 'non-finite JSON number');
  if (value === null || typeof value !== 'object') {
    const encoded = JSON.stringify(value);
    assert.equal(typeof encoded, 'string');
    return encoded;
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (!isJsonObject(value)) fail('INVALID_JSON', 'unsupported JSON value');
  return `{${Object.keys(value).sort().map(key =>
    `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
}

export function checkDepth(value, depth = 0) {
  if (value === null || typeof value !== 'object') return;
  if (depth >= MAX_DEPTH) fail('DEPTH_EXCEEDED', `JSON depth exceeds ${MAX_DEPTH}`);
  const children = Array.isArray(value) ? value : Object.values(value);
  for (const child of children) checkDepth(child, depth + 1);
}

export function bytes(value) {
  return Buffer.byteLength(value, 'utf8');
}

export function parseObject(text, field) {
  let value;
  try {
    value = parseJsonLossless(text);
  } catch {
    fail('INVALID_JSON', `${field} is not valid JSON`);
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail('INVALID_JSON', `${field} must be a JSON object`);
  }
  return value;
}

export function parseJson(text, field) {
  try {
    return parseJsonLossless(text);
  } catch {
    fail('INVALID_JSON', `${field} is not valid JSON`);
  }
}

export function decodeUnknown(text) {
  const unknown = parseObject(text, 'unknown_json');
  for (const [key, value] of Object.entries(unknown)) {
    if (!value || typeof value !== 'object' || Array.isArray(value)
      || !Object.hasOwn(value, 'value') || typeof value.hlc !== 'string'
      || typeof value.dev !== 'string' || !Number.isSafeInteger(value.seq)) {
      fail('INVALID_UNKNOWN', `unknown_json.${key} is not a versioned field envelope`);
    }
  }
  return unknown;
}

export function mergeUnknown(data, unknown) {
  const merged = { ...data };
  for (const [key, envelope] of Object.entries(unknown)) {
    if (!Object.hasOwn(merged, key)) Object.defineProperty(merged, key,
      { value: envelope.value, enumerable: true, configurable: true, writable: true });
  }
  return merged;
}

export { isJsonObject, parseJsonLossless, stringifyJsonLossless };
