import { parseArgs } from 'node:util';
import { resolve } from 'node:path';
import { createEvidenceArchive, repoRoot } from './evidence-store.mjs';

const { values } = parseArgs({
  allowPositionals: false,
  options: {
    root: { type: 'string' },
    ref: { type: 'string' },
    source: { type: 'string' },
    'artifact-root': { type: 'string' },
    id: { type: 'string' },
    index: { type: 'string' },
    'prune-worktree': { type: 'boolean' },
    help: { type: 'boolean', short: 'h' },
  },
});

if (values.help) {
  console.log('usage: node tools/evidence/archive.mjs [--root DIR] [--ref REV] [--source head|worktree|both] [--artifact-root DIR] [--id NAME] [--index FILE] [--prune-worktree]');
  process.exit(0);
}

try {
  const result = createEvidenceArchive({
    root: values.root ? resolve(values.root) : repoRoot,
    ref: values.ref,
    source: values.source,
    artifactRoot: values['artifact-root'] ? resolve(values['artifact-root']) : undefined,
    archiveId: values.id,
    indexFile: values.index,
    pruneWorktree: values['prune-worktree'] === true,
  });
  console.log(JSON.stringify({ event: 'evidence.archived', ...result }, null, 2));
} catch (error) {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
}
