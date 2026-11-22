import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, relative, resolve } from 'node:path';
import test from 'node:test';
import { baselinePath, brandPath, colorsOf, desktop, metricsOf, readJson, scenarioTokens, validateBaseline } from './design-data.mjs';
import { generate } from './design-generate.mjs';
import { sourceDifferences } from './design-source.mjs';
import { resolvedDifferences, semanticSnapshot } from './design-verify.mjs';

const baseline = readJson(baselinePath);
const brand = readJson(brandPath);

test('browser identity is recorded but semantic tokens stay strict across hosts', () => {
  const actual = structuredClone(baseline);
  actual.engine = { name: 'ci-chromium', executable: '/ci/browser', version: 'test' };
  assert.deepEqual(semanticSnapshot(baseline), semanticSnapshot(actual));
  actual.scenarios['light/default'].tokens['--radius'].computed = '99px';
  assert.notDeepEqual(semanticSnapshot(baseline), semanticSnapshot(actual));
});

function files(dir) {
  return readdirSync(dir, { recursive: true, withFileTypes: true }).filter(entry => entry.isFile())
    .map(entry => resolve(entry.parentPath, entry.name)).sort();
}

test('all 20 scenarios retain 329 browser-resolved colors and resolved metrics', () => {
  validateBaseline(baseline);
  for (const mode of baseline.palettes.modes) for (const palette of baseline.palettes.all) {
    assert.equal(Object.keys(colorsOf(scenarioTokens(baseline, mode, palette))).length, 329);
  }
  const light = baseline.scenarios['light/default'].tokens;
  assert.equal(Object.keys(metricsOf(light, 'Dp')).length, 102);
  assert.equal(Object.keys(metricsOf(light, 'TextUnit')).length, 18);
  assert.equal(Object.keys(metricsOf(light, 'Float')).length, 64);
  assert.equal(Object.keys(metricsOf(light, 'Int')).length, 11);
});

test('generation is byte reproducible and derived colors are scenario-specific', async () => {
  const tmp = mkdtempSync(resolve(tmpdir(), 's1-design-generate-'));
  try {
    const first = resolve(tmp, 'first');
    const second = resolve(tmp, 'second');
    await generate(first, baseline, brand);
    await generate(second, baseline, brand);
    const paths = files(first).map(path => relative(first, path));
    assert.deepEqual(paths, files(second).map(path => relative(second, path)));
    for (const path of paths) assert.deepEqual(readFileSync(resolve(first, path)), readFileSync(resolve(second, path)), path);
    const source = readFileSync(resolve(first, 'kotlin/com/deepstudent/design/generated/GeneratedColors.kt'), 'utf8');
    assert.match(source, /private val overrides: Map<String, \(\) -> Map<String, Long>>/);
    assert.doesNotMatch(source, /"light\/purple" ->/);
    const numbers = files(first).filter(path => /MetricLightFloat\d+\.kt$/.test(path))
      .map(path => readFileSync(path, 'utf8')).join('\n');
    assert.match(numbers, /Map<String, Double>/);
    assert.match(numbers, /"--z-debug" to 2147483647\.0/);
    assert.notEqual(scenarioTokens(baseline, 'light', 'purple')['--button-prominent-bg'].kotlin.value,
      scenarioTokens(baseline, 'light', 'default')['--button-prominent-bg'].kotlin.value);
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});

test('resolved drift reports scenario, token, source and values', () => {
  const actual = structuredClone(baseline);
  actual.palettesRuntime['dark/teal'].differing['--primary'].kotlin.value = '0xFF000000';
  assert.equal(resolvedDifferences(baseline, actual).length, 0, 'accent overrides the duplicate differing entry');
  assert.notDeepEqual(actual.palettesRuntime['dark/teal'].differing, baseline.palettesRuntime['dark/teal'].differing);
  actual.palettesRuntime['dark/teal'].accent['--primary'].kotlin.value = '0xFF000000';
  const changes = resolvedDifferences(baseline, actual);
  assert.equal(changes.length, 1);
  assert.equal(changes[0].scenario, 'dark/teal');
  assert.equal(changes[0].token, '--primary');
  assert.match(changes[0].source, /^src\/styles\/.+:\d+$/);
  assert.notDeepEqual(changes[0].expected, changes[0].actual);
});

test('PostCSS pinpoints a changed declaration in an isolated desktop copy', () => {
  const tmp = mkdtempSync(resolve(tmpdir(), 's1-design-source-'));
  try {
    for (const item of baseline.importChain.files) {
      const destination = resolve(tmp, item.file);
      mkdirSync(dirname(destination), { recursive: true });
      cpSync(resolve(desktop, item.file), destination);
    }
    const path = resolve(tmp, 'src/styles/shadcn-variables.css');
    const old = readFileSync(path, 'utf8');
    assert.match(old, /--radius: 0\.5rem;/);
    writeFileSync(path, old.replace('--radius: 0.5rem;', '--radius: 0.75rem;'));
    const changes = sourceDifferences(baseline, tmp);
    assert.ok(changes.some(entry => entry.token === '--radius' && entry.source.endsWith('shadcn-variables.css:3') && entry.actual === '0.75rem'));
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});

test('browser verification fails with a localized report when frozen CSS changes', { timeout: 120000 }, () => {
  const tmp = mkdtempSync(resolve(tmpdir(), 's1-design-browser-'));
  try {
    const android = resolve(tmp, 'dstu-android');
    const clone = resolve(tmp, 'dstu-node');
    mkdirSync(resolve(android, 'tools'), { recursive: true });
    mkdirSync(resolve(android, 'config/design'), { recursive: true });
    mkdirSync(resolve(android, 'docs/plans'), { recursive: true });
    cpSync(resolve(import.meta.dirname, '../baseline'), resolve(android, 'tools/baseline'), { recursive: true });
    cpSync(import.meta.dirname, resolve(android, 'tools/s1'), { recursive: true, filter: source => source === import.meta.dirname || /design-(data|source|verify)\.mjs$/.test(source) });
    cpSync(baselinePath, resolve(android, 'config/design/tokens.baseline.json'));
    cpSync(resolve(import.meta.dirname, '../../docs/plans/2026-11-15-mobile-full-feature-refactor.md'), resolve(android, 'docs/plans/2026-11-15-mobile-full-feature-refactor.md'));
    execFileSync('git', ['clone', '--quiet', '--shared', '--no-hardlinks', desktop, clone]);
    symlinkSync(resolve(desktop, 'node_modules'), resolve(clone, 'node_modules'), 'dir');
    const css = resolve(clone, 'src/styles/shadcn-variables.css');
    const old = readFileSync(css, 'utf8');
    writeFileSync(css, old.replace('--radius: 0.5rem;', '--radius: 0.75rem;'));
    const result = spawnSync(process.execPath, [resolve(android, 'tools/s1/design-verify.mjs')], { encoding: 'utf8', timeout: 110000, maxBuffer: 16 * 1024 * 1024 });
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /s1\.design\.resolved-drift/);
    assert.match(result.stderr, /"token":"--radius"/);
    assert.match(result.stderr, /shadcn-variables\.css:3/);
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});
