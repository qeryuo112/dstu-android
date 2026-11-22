import { execFileSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { readIndex, repoRoot } from './evidence-store.mjs';

const { values } = parseArgs({
  allowPositionals: false,
  options: {
    root: { type: 'string' },
    index: { type: 'string' },
    help: { type: 'boolean', short: 'h' },
  },
});

if (values.help) {
  console.log('usage: node tools/evidence/check-logs.mjs [--root DIR] [--index FILE]');
  process.exit(0);
}

try {
  const root = values.root ? resolve(values.root) : repoRoot;
  const tracked = execFileSync('git', ['ls-files', '-z', '--', 'logs'], { cwd: root })
    .toString('utf8').split('\0').filter(Boolean);
  if (tracked.length > 0) {
    throw new Error(`raw evidence logs are tracked (${tracked.length}): ${tracked.slice(0, 20).join(', ')}`);
  }
  let ignored = false;
  try {
    execFileSync('git', ['check-ignore', '-q', 'logs/.evidence-local-check.log'], { cwd: root, stdio: 'ignore' });
    ignored = true;
  } catch {
    ignored = false;
  }
  if (!ignored) throw new Error('logs/ is not ignored; local evidence writes would become untracked noise');
  const evidenceDir = resolve(root, 'docs/evidence');
  const indexFiles = values.index
    ? [resolve(root, values.index)]
    : readdirSync(evidenceDir).filter(name => /^logs-index.*\.json$/u.test(name)).sort()
      .map(name => resolve(evidenceDir, name));
  if (indexFiles.length === 0) throw new Error('no docs/evidence/logs-index*.json files found');
  const indexes = indexFiles.map(readIndex);
  console.log(JSON.stringify({
    event: 'evidence.logs.checked',
    trackedLogs: 0,
    indexFiles,
    indexedEntries: indexes.reduce((total, index) => total + index.entries.length, 0),
    archives: indexes.map(index => index.archive.relativeLocation),
    localWritesIgnored: true,
  }));
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
}
