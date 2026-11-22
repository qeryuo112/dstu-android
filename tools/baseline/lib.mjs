import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const workspace = dirname(root);
export const desktop = resolve(workspace, 'dstu-node');
export const operit = resolve(workspace, 'Operit');
export const desktopRequire = createRequire(resolve(desktop, 'package.json'));
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export const readJson = file => JSON.parse(readFileSync(resolve(root, file), 'utf8'));
export const readDesktop = file => readFileSync(resolve(desktop, file), 'utf8');
export const git = (cwd, ...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
export const sourceFiles = (cwd, ...paths) => git(cwd, 'ls-files', '-z', '--', ...paths).split('\0').filter(Boolean).sort();
export const evidence = (file, line = 1, symbol = null) => ({ source: 'desktop', file, line, symbol });

export function log(event, details) {
  process.stderr.write(`${JSON.stringify({ time: new Date().toISOString(), event, ...details })}\n`);
}

export function saveJson(file, value) {
  const full = resolve(root, file);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, `${JSON.stringify(value, null, 2)}\n`);
}

export function difference(expected, actual, path = '$') {
  if (Object.is(expected, actual)) return null;
  if (typeof expected !== typeof actual || expected === null || actual === null) return { path, expected, actual };
  if (typeof actual !== 'object') return { path, expected, actual };
  if (Array.isArray(expected) !== Array.isArray(actual)) return { path, expected, actual };
  const keys = new Set([...Object.keys(expected), ...Object.keys(actual)]);
  for (const key of keys) {
    const found = difference(expected[key], actual[key], `${path}.${key}`);
    if (found !== null) return found;
  }
  return null;
}

export function artifact(file, value, mode) {
  if (mode === 'capture') {
    saveJson(file, value);
    log('baseline.captured', { file, bytes: Buffer.byteLength(JSON.stringify(value)) });
  } else {
    assert.equal(mode, 'verify');
    const drift = difference(readJson(file), value);
    if (drift !== null) {
      log('baseline.drift', { file, ...drift });
      throw new Error(`${file}: drift at ${drift.path}`);
    }
    log('baseline.verified', { file });
  }
}

export function main(moduleUrl, run) {
  if (process.argv[1] !== undefined && pathToFileURL(resolve(process.argv[1])).href === moduleUrl) {
    const mode = process.argv[2];
    assert.ok(['capture', 'verify'].includes(mode), 'usage: node <tool> capture|verify');
    Promise.resolve().then(() => run(mode)).catch(error => {
      log('baseline.failed', { tool: relative(root, fileURLToPath(moduleUrl)), message: error.message, stack: error.stack });
      process.exitCode = 1;
    });
  }
}

export const ts = desktopRequire('typescript');
export function parse(file) {
  return ts.createSourceFile(file, readDesktop(file), ts.ScriptTarget.Latest, true, file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
}
export function visit(node, fn) {
  fn(node);
  ts.forEachChild(node, child => visit(child, fn));
}
export function unwrap(node) {
  while (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isSatisfiesExpression(node)) node = node.expression;
  return node;
}
export function prop(node, key) {
  node = unwrap(node);
  assert.ok(ts.isObjectLiteralExpression(node), `expected object: ${node.getText().slice(0, 100)}`);
  return node.properties.find(p => p.name?.getText().replace(/^['"]|['"]$/g, '') === key);
}
export function value(node) {
  node = unwrap(node);
  if (ts.isStringLiteralLike(node)) return node.text;
  if (ts.isNumericLiteral(node)) return Number(node.text);
  if (node.kind === ts.SyntaxKind.TrueKeyword) return true;
  if (node.kind === ts.SyntaxKind.FalseKeyword) return false;
  if (node.kind === ts.SyntaxKind.NullKeyword) return null;
  if (ts.isArrayLiteralExpression(node)) return node.elements.map(value);
  if (ts.isObjectLiteralExpression(node)) return Object.fromEntries(node.properties.filter(ts.isPropertyAssignment).map(p => [p.name.getText().replace(/^['"]|['"]$/g, ''), value(p.initializer)]));
  return { expression: node.getText() };
}
export const location = (file, sf, node) => evidence(file, sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1);
export function assertUnique(items, key) {
  assert.equal(new Set(items.map(key)).size, items.length, 'duplicate identifiers');
}
