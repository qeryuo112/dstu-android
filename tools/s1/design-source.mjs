import { readFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { desktop, scenarioTokens } from './design-data.mjs';

const requireDesktop = createRequire(resolve(desktop, 'package.json'));
const postcss = requireDesktop('postcss');
const normalize = value => value.replace(/\s+/g, ' ').trim();
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

function declarations(path) {
  const entries = new Map();
  postcss.parse(readFileSync(path, 'utf8'), { from: path }).walkDecls(decl => {
    entries.set(`${decl.source.start.line}:${decl.prop}`, normalize(decl.value));
  });
  return entries;
}

export function sourceDifferences(baseline, desktopRoot = desktop) {
  const cache = new Map();
  const differences = [];
  const seen = new Set();
  for (const mode of baseline.palettes.modes) for (const palette of baseline.palettes.all) {
    for (const [name, token] of Object.entries(scenarioTokens(baseline, mode, palette))) {
      const src = token.src;
      if (!src?.file?.endsWith('.css') || !src.line || src.raw === undefined) continue;
      const id = `${mode}/${palette}:${name}:${src.file}:${src.line}`;
      if (seen.has(id)) continue;
      seen.add(id);
      const path = resolve(desktopRoot, src.file);
      if (!cache.has(path)) cache.set(path, existsSync(path) ? declarations(path) : new Map());
      const actual = cache.get(path).get(`${src.line}:${name}`);
      if (actual !== normalize(src.raw)) differences.push({
        scenario: `${mode}/${palette}`, token: name, source: `${src.file}:${src.line}`,
        expected: normalize(src.raw), actual: actual ?? null,
      });
    }
  }
  return differences;
}

export function sourceHashDifferences(baseline, desktopRoot = desktop) {
  return baseline.importChain.files.flatMap(({ file, sha256 }) => {
    const path = resolve(desktopRoot, file);
    const actual = existsSync(path) ? hash(readFileSync(path)) : null;
    return actual === sha256 ? [] : [{ source: file, expected: sha256, actual }];
  });
}
