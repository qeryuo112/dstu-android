// Compatibility bridge for historical validators whose immutable inputs refer to logs/.
// Restore only missing evidence, never overwrite local evidence, then remove only unchanged restores.
import { existsSync, lstatSync, readFileSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { DEFAULT_INDEX, readIndex, repoRoot, resolveIndexArchive, restoreEntries, sha256, validateIndex, assertSafeOutputPath } from './evidence-store.mjs';

export function replayWithEvidence(callback, { root = repoRoot, index = readIndex(resolve(root, DEFAULT_INDEX)), archivePath = resolveIndexArchive(index) } = {}) {
  validateIndex(index);
  const missing = index.entries.filter(entry => !existsSync(resolve(root, entry.path)));
  try {
    if (missing.length) restoreEntries({ root, index, archivePath, paths: missing.map(entry => entry.path) });
    return callback();
  } finally {
    for (const entry of missing) {
      let file;
      try { file = assertSafeOutputPath(root, entry.path); } catch { continue; }
      if (!existsSync(file)) continue;
      const stat = lstatSync(file);
      if (stat.isFile() && stat.size === entry.size && sha256(readFileSync(file)) === entry.sha256) rmSync(file);
    }
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (process.argv.length < 3) throw new Error('usage: node tools/evidence/replay.mjs SCRIPT [ARGS...]');
  process.exitCode = replayWithEvidence(() => {
    const result = spawnSync(process.execPath, process.argv.slice(2), { cwd: repoRoot, stdio: 'inherit' });
    if (result.error) throw result.error;
    return result.status ?? 1;
  });
}
