import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { baselinePath, brandPath, colorsOf, desktop, metricsOf, readJson, root, scenarioTokens, validateBaseline } from './design-data.mjs';
import { iconPaths } from './design-icons.mjs';

const pkg = 'com.deepstudent.design.generated';
const quote = value => JSON.stringify(value).replaceAll('$', '\\$');
const header = `// Generated from frozen S0.11/S0.12 inputs. Do not edit.\npackage ${pkg}\n\n`;
const pascal = value => value.split('-').map(part => part[0].toUpperCase() + part.slice(1)).join('');

function write(path, text) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text);
}

function kotlinMap(functionName, values, type) {
  const lines = Object.entries(values).map(([key, value]) => {
    const literal = type === 'Long' ? `${value}L` : type === 'Float' ? `${Number(value)}f`
      : type === 'Double' ? `${Number(value)}${Number.isInteger(Number(value)) ? '.0' : ''}` : value;
    return `    ${quote(key)} to ${literal},`;
  });
  return `internal fun ${functionName}(): Map<String, ${type}> = mapOf(\n${lines.join('\n')}\n)\n`;
}

function writeChunks(directory, stem, values, type) {
  const entries = Object.entries(values);
  const names = [];
  for (let offset = 0; offset < entries.length; offset += 45) {
    const name = `${stem}${names.length}`;
    names.push(name);
    write(resolve(directory, `${pascal(stem)}${names.length}.kt`), header + kotlinMap(name, Object.fromEntries(entries.slice(offset, offset + 45)), type));
  }
  return names;
}

function colorSources(directory, baseline) {
  const baseNames = {};
  const overrides = {};
  for (const mode of baseline.palettes.modes) {
    const base = colorsOf(scenarioTokens(baseline, mode, 'default'));
    baseNames[mode] = writeChunks(directory, `base${pascal(mode)}`, base, 'Long');
    for (const palette of baseline.palettes.all) {
      const actual = colorsOf(scenarioTokens(baseline, mode, palette));
      const changed = Object.fromEntries(Object.entries(actual).filter(([key, value]) => value !== base[key]));
      const stem = `override${pascal(mode)}${pascal(palette)}`;
      overrides[`${mode}/${palette}`] = writeChunks(directory, stem, changed, 'Long');
    }
  }
  const base = Object.entries(baseNames).map(([mode, names]) =>
    `    ${quote(mode)} -> ${names.map(name => `${name}()`).join(' + ')}`).join('\n');
  const changes = Object.entries(overrides).filter(([, names]) => names.length).map(([id, names]) =>
    `    ${quote(id)} to { ${names.map(name => `${name}()`).join(' + ')} },`).join('\n');
  const source = `${header}internal fun generatedBaseColors(mode: String): Map<String, Long> = when (mode) {\n${base}\n    else -> error("Unknown mode: $mode")\n}\n\n` +
    `private val overrides: Map<String, () -> Map<String, Long>> = mapOf(\n${changes}\n)\n\n` +
    `internal fun generatedOverrides(id: String): Map<String, Long> = overrides[id]?.invoke() ?: emptyMap()\n`;
  write(resolve(directory, 'GeneratedColors.kt'), source);
}

function metricSources(directory, baseline) {
  const branches = [];
  for (const mode of baseline.palettes.modes) for (const type of ['Dp', 'TextUnit', 'Float', 'Int']) {
    const stem = `metric${pascal(mode)}${type}`;
    const values = metricsOf(baseline.scenarios[`${mode}/default`].tokens, type);
    const names = writeChunks(directory, stem, values, type === 'Int' ? 'Int' : type === 'Float' ? 'Double' : 'Float');
    branches.push({ mode, type, names });
  }
  for (const type of ['Dp', 'TextUnit', 'Float', 'Int']) {
    const data = branches.filter(entry => entry.type === type);
    const resultType = type === 'Int' ? 'Int' : type === 'Float' ? 'Double' : 'Float';
    const cases = data.map(({ mode, names }) => `    ${quote(mode)} -> ${names.map(name => `${name}()`).join(' + ')}`).join('\n');
    write(resolve(directory, `GeneratedMetric${type}.kt`), `${header}internal fun generated${type}(mode: String): Map<String, ${resultType}> = when (mode) {\n${cases}\n    else -> error("Unknown mode: $mode")\n}\n`);
  }
}

function brandSources(directory, brand, icons) {
  const source = ['internal object GeneratedBrand {'];
  for (const name of ['mark', 'logo']) {
    const item = brand.brand[name];
    const lines = item.paths.map(path => {
      assert.ok(['fill-foreground', 'fill-background'].includes(path.className));
      return `        ${quote(path.d)} to ${path.className === 'fill-foreground'},`;
    });
    source.push(`    val ${name} = listOf(\n${lines.join('\n')}\n    )`);
  }
  source.push('}');
  write(resolve(directory, 'GeneratedBrand.kt'), header + source.join('\n') + '\n');
  const iconLines = icons.map(icon => `    ${quote(icon.id)} to Pair(${quote(icon.regular)}, ${quote(icon.bold)}),`);
  write(resolve(directory, 'GeneratedIcons.kt'), `${header}internal val generatedIcons = mapOf(\n${iconLines.join('\n')}\n)\n`);
}

function expectedAssets(output, baseline) {
  write(resolve(output, 'androidTestAssets/s1-design-baseline.json'), `${JSON.stringify(baseline)}\n`);
}

export async function generate(output, baseline = readJson(baselinePath), brand = readJson(brandPath)) {
  validateBaseline(baseline);
  const directory = resolve(output, 'kotlin/com/deepstudent/design/generated');
  colorSources(directory, baseline);
  metricSources(directory, baseline);
  brandSources(directory, brand, await iconPaths(brand));
  expectedAssets(output, baseline);
  const provenance = readJson(resolve(root, 'config/stages/s1-design-source.json'));
  for (const entry of provenance.adopted) {
    const text = readFileSync(resolve(desktop, entry.licenseFile));
    assert.equal(createHash('sha256').update(text).digest('hex'), entry.licenseSha256);
    write(resolve(output, 'mainAssets/licenses/design', `${entry.licenseSha256}.txt`), text);
  }
  write(resolve(output, 'mainAssets/licenses/design/source.json'), `${JSON.stringify(provenance, null, 2)}\n`);
  return { output, scenarios: baseline.palettes.all.length * 2, colorsPerScenario: 329 };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const output = process.argv[2];
  assert.ok(output, 'usage: node tools/s1/design-generate.mjs <output-directory>');
  generate(resolve(output)).then(result => console.log(JSON.stringify({ event: 's1.design.generated', ...result })))
    .catch(error => { console.error(error.stack); process.exitCode = 1; });
}
