import { parseArgs } from 'node:util';
import { resolve } from 'node:path';
import {
  DEFAULT_INDEX,
  readIndex,
  resolveIndexArchive,
  verifyArchive,
} from './evidence-store.mjs';

const { values } = parseArgs({
  allowPositionals: false,
  options: {
    index: { type: 'string' },
    archive: { type: 'string' },
    'artifact-root': { type: 'string' },
    help: { type: 'boolean', short: 'h' },
  },
});

if (values.help) {
  console.log('usage: node tools/evidence/verify.mjs [--index FILE] [--archive FILE] [--artifact-root DIR]');
  process.exit(0);
}

try {
  const indexFile = resolve(values.index ?? DEFAULT_INDEX);
  const index = readIndex(indexFile);
  const archivePath = resolveIndexArchive(index, {
    archivePath: values.archive ? resolve(values.archive) : undefined,
    artifactRoot: values['artifact-root'] ? resolve(values['artifact-root']) : undefined,
  });
  const report = verifyArchive({
    archivePath,
    archiveSha256: index.archive.checksum.value,
    archiveSize: index.archive.size,
    entries: index.entries,
  });
  console.log(JSON.stringify({ event: 'evidence.archive.verified', index: indexFile, ...report }, null, 2));
} catch (error) {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
}
