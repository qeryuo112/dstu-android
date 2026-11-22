import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const root = resolve(import.meta.dirname, '../..');
export const baselinePath = resolve(root, 'config/design/tokens.baseline.json');
export const brandPath = resolve(root, 'config/design/brand.baseline.json');
export const desktop = resolve(root, '../dstu-node');
export const frozenCommit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';

export const readJson = path => JSON.parse(readFileSync(path, 'utf8'));

export function scenarioTokens(baseline, mode, palette) {
  const base = baseline.scenarios[`${mode}/default`]?.tokens;
  const variant = baseline.palettesRuntime[`${mode}/${palette}`];
  assert.ok(base && variant, `unknown design scenario ${mode}/${palette}`);
  return { ...base, ...variant.differing, ...variant.accent };
}

export function colorsOf(tokens) {
  return Object.fromEntries(Object.entries(tokens)
    .filter(([, token]) => token.kotlin?.type === 'Color')
    .map(([name, token]) => {
      assert.match(token.kotlin.value, /^0x[0-9A-F]{8}$/);
      return [name, token.kotlin.value];
    }));
}

export function metricsOf(tokens, type) {
  return Object.fromEntries(Object.entries(tokens).flatMap(([name, token]) => {
    const value = type === 'Dp' ? token.kotlin?.dp
      : type === 'TextUnit' ? token.kotlin?.sp : token.kotlin?.value;
    if (token.kotlin?.type !== type || token.res !== 'resolved' || !Number.isFinite(value)) return [];
    return [[name, value]];
  }));
}

export function validateBaseline(baseline) {
  assert.equal(baseline.evidence.frozen.commit, frozenCommit);
  assert.deepEqual(baseline.palettes.modes, ['light', 'dark']);
  assert.equal(baseline.palettes.all.length, 10);
  for (const mode of baseline.palettes.modes) {
    const base = baseline.scenarios[`${mode}/default`].tokens;
    assert.equal(Object.keys(base).length, 812);
    const expectedNames = Object.keys(colorsOf(base)).sort();
    assert.equal(expectedNames.length, 329);
    for (const palette of baseline.palettes.all) {
      const tokens = scenarioTokens(baseline, mode, palette);
      assert.equal(Object.keys(tokens).length, 812, `${mode}/${palette}: token count`);
      assert.deepEqual(Object.keys(colorsOf(tokens)).sort(), expectedNames, `${mode}/${palette}: color names`);
    }
  }
}
