import { parseArgs } from 'node:util';
import { resolve } from 'node:path';
import {
  DEFAULT_INDEX,
  readIndex,
  repoRoot,
  resolveIndexArchive,
  restoreEntries,
} from './evidence-store.mjs';

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    index: { type: 'string' },
    archive: { type: 'string' },
    'artifact-root': { type: 'string' },
    output: { type: 'string' },
    all: { type: 'boolean' },
    force: { type: 'boolean' },
    help: { type: 'boolean', short: 'h' },
  },
});

if (values.help) {
  console.log('usage: node tools/evidence/restore.mjs [--index FILE] [--archive FILE] [--artifact-root DIR] [--output DIR] [--all] [--force] [logs/path ...]');
  process.exit(0);
}

try {
  if (values.all && positionals.length > 0) throw new Error('--all cannot be combined with explicit paths');
  if (!values.all && positionals.length === 0) throw new Error('provide one or more logs/... paths, or use --all');
  const indexFile = resolve(values.index ?? DEFAULT_INDEX);
  const index = readIndex(indexFile);
  const archivePath = resolveIndexArchive(index, {
    archivePath: values.archive ? resolve(values.archive) : undefined,
    artifactRoot: values['artifact-root'] ? resolve(values['artifact-root']) : undefined,
  });
  const result = restoreEntries({
    root: repoRoot,
    index,
    archivePath,
    paths: values.all ? undefined : positionals,
    outputRoot: values.output ? resolve(values.output) : repoRoot,
    force: values.force === true,
  });
  console.log(JSON.stringify({ event: 'evidence.restored', index: indexFile, archive: archivePath, ...result }, null, 2));
} catch (error) {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
}
