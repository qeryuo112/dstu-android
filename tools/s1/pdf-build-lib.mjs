import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

export const root = resolve(import.meta.dirname, '../..');
export const lock = JSON.parse(readFileSync(join(root, 'config/stages/s1-pdf-source.json')));
export const work = resolve(process.env.DSTU_PDF_WORK || join(root, '../build-tools/s1-pdf-work'));
export const source = join(work, 'pdfium');
export const depot = join(work, 'depot_tools');
export const ndk = resolve(process.env.ANDROID_NDK_HOME || join(process.env.HOME, 'Android/Sdk/ndk/29.0.13846066'));
export const out = join(work, 'out-android');

export function run(cmd, args, cwd = work, extra = {}) {
  console.error(`[pdf-build] ${cmd} ${args.join(' ')}`);
  const result = spawnSync(cmd, args, {
    cwd, encoding: 'utf8', stdio: 'inherit',
    env: { ...process.env, DEPOT_TOOLS_UPDATE: '0', ...extra },
  });
  if (result.status !== 0) throw new Error(`${cmd} failed (${result.status ?? result.error})`);
}

export function output(cmd, args, cwd = work) {
  const result = spawnSync(cmd, args, { cwd, encoding: 'utf8', env: process.env, maxBuffer: 32 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${cmd} failed: ${result.stderr}`);
  return result.stdout.trim();
}

export function hash(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

export function assertHash(file, expected) {
  const actual = hash(file);
  if (actual !== expected) throw new Error(`SHA-256 mismatch: ${file} expected ${expected}, got ${actual}`);
}

export function assertGit(dir, revision, tree) {
  const top = realpathSync(output('git', ['-C', dir, 'rev-parse', '--show-toplevel']));
  if (top !== realpathSync(dir)) throw new Error(`Source directory ${dir} aliases repository ${top}`);
  const actual = output('git', ['-C', dir, 'rev-parse', 'HEAD']);
  if (actual !== revision) throw new Error(`${dir} expected ${revision}, got ${actual}`);
  if (tree && output('git', ['-C', dir, 'rev-parse', 'HEAD^{tree}']) !== tree) {
    throw new Error(`Tree mismatch: ${dir}`);
  }
  if (output('git', ['-C', dir, 'status', '--porcelain', '--untracked-files=no', '--ignore-submodules=none'])) {
    throw new Error(`Tracked source was modified: ${dir}`);
  }
  const flags = output('git', ['-C', dir, 'ls-files', '-v']).split('\n');
  if (flags.some((line) => /^[a-zS]/.test(line))) throw new Error(`Hidden tracked-file flags: ${dir}`);
}

export function assertSourceRepositories() {
  const top = realpathSync(output('git', ['-C', source, 'rev-parse', '--show-toplevel']));
  if (top !== realpathSync(source)) throw new Error(`Source is an alias of ${top}`);
  const found = output('find', [source, '-name', '.git', '-prune', '-print']).split('\n');
  const expected = new Set([realpathSync(source), ...Object.keys(lock.dependencies).map((p) => realpathSync(join(source, p))),
    realpathSync(join(source, 'third_party/simdutf'))]);
  for (const item of found) {
    const dir = realpathSync(dirname(item));
    if (!expected.has(dir)) throw new Error(`Unexpected embedded Git checkout: ${dir}`);
    expected.delete(dir);
  }
  if (expected.size) throw new Error(`Missing source checkouts: ${[...expected].join(', ')}`);
}

export function copyChecked(from, to, expected) {
  if (expected) assertHash(from, expected);
  mkdirSync(dirname(to), { recursive: true });
  copyFileSync(from, to);
}

export function gitClone(dir, url, revision) {
  if (!existsSync(join(dir, '.git'))) {
    run('git', ['-c', 'http.version=HTTP/1.1', 'clone', '--no-checkout', '--filter=blob:none', url, dir]);
  }
  run('git', ['-c', 'http.version=HTTP/1.1', '-C', dir, 'fetch', '--depth=1', 'origin', revision]);
  run('git', ['-c', 'http.version=HTTP/1.1', '-C', dir, 'switch', '--detach', revision]);
}
