import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { relative, resolve } from 'node:path';
import { root } from './frozen-inputs.mjs';

const requireDesktop = createRequire(resolve(root, '../dstu-node/package.json'));
const { DOMParser } = requireDesktop('@xmldom/xmldom');
const rows = [];
for (const entry of readdirSync(root, { recursive: true, withFileTypes: true })) {
  if (!entry.isFile() || !entry.name.startsWith('TEST-') || !entry.name.endsWith('.xml')) continue;
  const path = resolve(entry.parentPath, entry.name);
  const name = relative(root, path);
  if (name.startsWith('build-logic/') || !/\/build\/test-results\/(?:test|testDebugUnitTest)\//u.test(name)) continue;
  const document = new DOMParser().parseFromString(readFileSync(path, 'utf8'), 'text/xml');
  const suite = document.documentElement;
  assert.equal(suite.tagName, 'testsuite', name);
  const count = field => Number(suite.getAttribute(field) || 0);
  assert.equal(count('failures') + count('errors'), 0, name);
  rows.push({ module: name.split('/build/')[0], suite: suite.getAttribute('name'),
    tests: count('tests'), skipped: count('skipped'), passed: count('tests') - count('skipped') });
}
assert.ok(rows.length > 0);
const modules = Object.fromEntries([...new Set(rows.map(row => row.module))].sort().map(module => [module,
  rows.filter(row => row.module === module).reduce((sum, row) => sum + row.passed, 0)]));
console.log(JSON.stringify({ time: new Date().toISOString(), event: 's1.jvm.unique-tests',
  scope: 'test and debugUnitTest only; release duplicates excluded',
  tests: rows.reduce((sum, row) => sum + row.tests, 0),
  passed: rows.reduce((sum, row) => sum + row.passed, 0), modules }));
