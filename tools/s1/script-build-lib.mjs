import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

export const root = resolve(import.meta.dirname, '../..');
export const lock = JSON.parse(readFileSync(join(root, 'config/stages/s1-script-source.json')));
export const work = resolve(process.env.DSTU_SCRIPT_WORK || join(root, '../build-tools/s1-script-work'));
export const source = join(work, 'quickjs');
export const ndk = resolve(
  process.env.ANDROID_NDK_HOME || join(process.env.HOME, 'Android/Sdk/ndk', lock.build.ndk),
);
export const clangDir = join(ndk, 'toolchains/llvm/prebuilt/linux-x86_64/bin');
export const androidOut = join(root, 'engines/quickjs/build/generated/script-jni/arm64-v8a');
export const noticeOut = join(root, 'engines/quickjs/build/generated/script-notices');

export function run(command, args, cwd = root) {
  console.error(`[script-build] ${command} ${args.join(' ')}`);
  const result = spawnSync(command, args, {
    cwd, encoding: 'utf8', stdio: 'inherit', env: process.env,
  });
  if (result.status !== 0) throw new Error(`${command} failed (${result.status ?? result.error})`);
}

export function output(command, args, cwd = root) {
  const result = spawnSync(command, args, {
    cwd, encoding: 'utf8', env: process.env, maxBuffer: 32 * 1024 * 1024,
  });
  if (result.status !== 0) throw new Error(`${command} failed: ${result.stderr}`);
  return result.stdout.trim();
}

export function hash(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

export function assertHash(file, expected) {
  const actual = hash(file);
  if (actual !== expected) {
    throw new Error(`SHA-256 mismatch: ${file} expected ${expected}, got ${actual}`);
  }
}

export function assertGit(dir, revision, tree) {
  const top = realpathSync(output('git', ['-C', dir, 'rev-parse', '--show-toplevel']));
  if (top !== realpathSync(dir)) throw new Error(`Source directory aliases ${top}`);
  const actual = output('git', ['-C', dir, 'rev-parse', 'HEAD']);
  if (actual !== revision) throw new Error(`${dir} expected ${revision}, got ${actual}`);
  if (tree && output('git', ['-C', dir, 'rev-parse', 'HEAD^{tree}']) !== tree) {
    throw new Error(`Tree mismatch: ${dir}`);
  }
  if (output('git', ['-C', dir, 'status', '--porcelain', '--untracked-files=no'])) {
    throw new Error(`Tracked source was modified: ${dir}`);
  }
}

export function verifySource() {
  assertGit(source, lock.engine.commit, lock.engine.tree);
  for (const [name, digest] of Object.entries(lock.engine.files)) {
    assertHash(join(source, name), digest);
  }
  assertHash(join(root, lock.engine.sourceObject.path), lock.engine.sourceObject.sha256);
  const version = readFileSync(join(source, 'VERSION'), 'utf8').trim();
  if (version !== lock.engine.version) throw new Error(`QuickJS version ${version}`);
}

export function ensureDirectories(...directories) {
  for (const directory of directories) mkdirSync(directory, { recursive: true });
}

export function sourceExists() {
  return existsSync(join(source, '.git'));
}
