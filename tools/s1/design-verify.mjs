import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { baselinePath, desktop, frozenCommit, readJson, scenarioTokens, validateBaseline } from './design-data.mjs';
import { sourceDifferences, sourceHashDifferences } from './design-source.mjs';

function comparable(token) {
  return {
    computed: token?.computed, kind: token?.kind, resolution: token?.res,
    color: token?.color, kotlin: token?.kotlin,
  };
}

export function resolvedDifferences(expected, actual) {
  const differences = [];
  for (const mode of expected.palettes.modes) for (const palette of expected.palettes.all) {
    const before = scenarioTokens(expected, mode, palette);
    const after = scenarioTokens(actual, mode, palette);
    for (const name of new Set([...Object.keys(before), ...Object.keys(after)])) {
      if (JSON.stringify(comparable(before[name])) === JSON.stringify(comparable(after[name]))) continue;
      const src = after[name]?.src ?? before[name]?.src;
      differences.push({ scenario: `${mode}/${palette}`, token: name,
        source: src?.file ? `${src.file}:${src.line ?? '?'}` : null,
        expected: comparable(before[name]), actual: comparable(after[name]) });
    }
  }
  return differences;
}

export function semanticSnapshot(snapshot) {
  const { engine, ...semantic } = snapshot;
  return semantic;
}

export async function verify() {
  const baseline = readJson(baselinePath);
  validateBaseline(baseline);
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: desktop, encoding: 'utf8' }).trim();
  if (head !== frozenCommit) throw new Error(`desktop HEAD is ${head}, expected ${frozenCommit}`);
  const source = sourceDifferences(baseline);
  const hashes = sourceHashDifferences(baseline);
  let actual;
  try {
    const collector = await import('../baseline/design.mjs');
    actual = await collector.collect();
  } catch (error) {
    for (const entry of source) console.error(JSON.stringify({ event: 's1.design.source-drift', ...entry }));
    for (const entry of hashes) console.error(JSON.stringify({ event: 's1.design.source-hash-drift', ...entry }));
    throw new Error(`frozen browser collector failed: ${error.message}`, { cause: error });
  }
  const differences = resolvedDifferences(baseline, actual);
  for (const entry of differences) console.error(JSON.stringify({ event: 's1.design.resolved-drift', ...entry }));
  for (const entry of source) console.error(JSON.stringify({ event: 's1.design.source-drift', ...entry }));
  for (const entry of hashes) console.error(JSON.stringify({ event: 's1.design.source-hash-drift', ...entry }));
  const { difference } = await import('../baseline/lib.mjs');
  const snapshotDrift = difference(semanticSnapshot(baseline), semanticSnapshot(actual));
  if (snapshotDrift) console.error(JSON.stringify({ event: 's1.design.snapshot-drift', ...snapshotDrift }));
  if (differences.length || source.length || hashes.length || snapshotDrift) {
    throw new Error(`design drift: ${differences.length} resolved, ${source.length} declarations, ${hashes.length} files`);
  }
  console.log(JSON.stringify({ event: 's1.design.verified', unit: 'S1.11', scenarios: 20,
    tokensPerScenario: 812, colorsPerScenario: 329, engine: `${actual.engine.name} ${actual.engine.version}` }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  verify().catch(error => { console.error(error.stack); process.exitCode = 1; });
}
