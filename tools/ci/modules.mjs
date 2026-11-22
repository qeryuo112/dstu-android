import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve, relative, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

export const root = resolve(import.meta.dirname, '../..');
export const moduleRoots = ['app', 'core', 'services', 'features', 'platform', 'engines', 'benchmark'];
const ignored = new Set(['build', '.gradle', '.kotlin', 'node_modules', 'third_party']);

export function discoverModules(base = root) {
  const modules = [];
  function walk(directory) {
    if (!existsSync(directory)) return;
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = resolve(directory, entry.name);
      if (entry.isDirectory() && !ignored.has(entry.name)) walk(file);
      if (entry.isFile() && ['build.gradle.kts', 'build.gradle'].includes(entry.name)) {
        modules.push(`:${relative(base, directory).split(sep).join(':')}`);
      }
    }
  }
  for (const directory of moduleRoots) walk(resolve(base, directory));
  return modules.sort();
}

export function verifyModules(base = root) {
  const settings = readFileSync(resolve(base, 'settings.gradle.kts'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//gu, '').replace(/^\s*\/\/.*$/gmu, '');
  const declarations = [...settings.matchAll(/\binclude\s*\(\s*"(:[\w:-]+)"\s*\)/gu)].map(match => match[1]);
  assert.ok(declarations.length > 0, 'no explicit module declarations found');
  assert.equal(new Set(declarations).size, declarations.length, 'duplicate include declaration');
  const actual = discoverModules(base);
  assert.equal(new Set(actual).size, actual.length, 'module has more than one build script');
  const missing = actual.filter(module => !declarations.includes(module));
  const stale = declarations.filter(module => !actual.includes(module));
  assert.deepEqual(missing, [], `modules missing from settings: ${missing.join(', ')}`);
  assert.deepEqual(stale, [], `includes without build scripts: ${stale.join(', ')}`);
  return actual;
}

export function unitTestTasks(group, base = root) {
  assert.ok(['jvm', 'android'].includes(group), 'unit-test group must be jvm or android');
  return verifyModules(base).flatMap(module => {
    const directory = resolve(base, module.slice(1).replaceAll(':', '/'));
    if (!existsSync(resolve(directory, 'src/test'))) return [];
    const script = readFileSync(resolve(directory, 'build.gradle.kts'), 'utf8');
    const isJvm = script.includes('id("dstu.kotlin.library")');
    const isAndroid = /id\("dstu.android.(?:application|library)"\)/u.test(script);
    assert.ok(isJvm !== isAndroid, `unclassified test module: ${module}; register its CI shard explicitly`);
    if (group === 'jvm' && isJvm) return [`${module}:test`];
    if (group === 'android' && isAndroid) return [`${module}:testDebugUnitTest`];
    return [];
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const modules = verifyModules();
  console.log(JSON.stringify({ event: 'ci.modules.verified', count: modules.length,
    optional: ':benchmark:startup (enabled by -Pdstu.benchmarkApp=true)' }));
}
