import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { root } from './frozen-inputs.mjs';

const mode = process.argv[2];
assert.ok(['logs', 'builds'].includes(mode), 'usage: cleanup-round.mjs logs|builds');
if (mode === 'logs') {
  const untracked = execFileSync('git', ['ls-files', '--others', '--exclude-standard', '-z', 'logs'], { cwd: root, encoding: 'utf8' }).split('\0');
  const deleted = untracked.filter(path => /^logs\/s1-(?:11|12|13|14|15|16)-main-[a-z0-9-]+\.(?:log|png|json)$/u.test(path));
  for (const path of deleted) rmSync(resolve(root, path));
  console.log(JSON.stringify({ event: 's1.round.intermediate-logs.removed', count: deleted.length, paths: deleted }));
} else {
  const directories = readdirSync(root, { recursive: true, withFileTypes: true }).filter(entry =>
    entry.isDirectory() && ['build', '.gradle', '.kotlin'].includes(entry.name));
  const candidates = directories.map(entry => resolve(entry.parentPath, entry.name)).sort((a, b) => a.length - b.length);
  const top = candidates.filter((path, index) => !candidates.slice(0, index).some(parent => path.startsWith(`${parent}/`)));
  for (const path of top) {
    assert.ok(path.startsWith(`${root}/`) && !path.includes('/.git/'));
    rmSync(path, { recursive: true, force: true });
  }
  const nativeOutput = resolve(root, '../build-tools/s1-pdf-work/out-android');
  if (existsSync(nativeOutput)) rmSync(nativeOutput, { recursive: true, force: true });
  const scratch = '/tmp/dstu-webkit-sources.jar';
  if (existsSync(scratch)) rmSync(scratch);
  console.log(JSON.stringify({ event: 's1.round.builds.removed', count: top.length, paths: top,
    nativeOutput, policy: 'Fixed external source checkout and shared dependency caches retained; compiler outputs removed.' }));
}
