import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readlinkSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const git = (...args) => execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
export function includesSourcePath(path) {
  return path !== 'tools/s1/benchmark-readme.md' && !/^(?:docs|logs|\.git)(?:\/|$)/u.test(path);
}
export function sourceFingerprint() {
  const commit = git('rev-parse', 'HEAD').trim();
  // Provenance describes source bytes, not history, handoff prose, or device logs.
  const paths = git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split('\0').filter(path => path && includesSourcePath(path)).sort();
  const digest = createHash('sha256');
  const item = value => { const bytes = Buffer.isBuffer(value) ? value : Buffer.from(String(value)); digest.update(`${bytes.length}:`); digest.update(bytes); };
  item('s1-27-source-v2');
  for (const path of paths) {
    item(path);
    try {
      const full = join(ROOT, path), stat = lstatSync(full);
      if (stat.isSymbolicLink()) { item('symlink'); item(readlinkSync(full)); }
      else if (stat.isFile()) { item(stat.mode & 0o111 ? 'executable' : 'file'); item(readFileSync(full)); }
      else item('non-file');
    } catch (error) { if (error.code === 'ENOENT') item('deleted'); else throw error; }
  }
  return { commit, sha256: digest.digest('hex'), paths: paths.length };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const value = sourceFingerprint();
  console.log(process.argv[2] === '--sha256' ? value.sha256 : JSON.stringify(value, null, 2));
}
