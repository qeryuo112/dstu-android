// Acquisition only. Existing engine builders still verify all frozen hashes and compile offline.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, renameSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { root } from './modules.mjs';
const read = name => JSON.parse(readFileSync(resolve(root, `config/stages/s1-${name}-source.json`)));
const run = (command, args, options = {}) => execFileSync(command, args, { cwd: root, stdio: 'inherit', ...options });
const zstd = read('zstd').engine;
const archive = resolve(root, zstd.archive);
mkdirSync(dirname(archive), { recursive: true });
if (!existsSync(archive)) {
  run('curl', ['--fail', '--location', '--retry', '3', '--output', `${archive}.download`,
    `https://codeload.github.com/facebook/zstd/tar.gz/${zstd.commit}`]);
  const digest = createHash('sha256').update(readFileSync(`${archive}.download`)).digest('hex');
  assert.equal(digest, zstd.sha256, 'downloaded Zstd archive digest');
  renameSync(`${archive}.download`, archive);
}
assert.equal(createHash('sha256').update(readFileSync(archive)).digest('hex'), zstd.sha256, 'Zstd archive digest');
const quickjs = read('script').engine;
const source = resolve(root, quickjs.path);
if (!existsSync(resolve(source, '.git'))) {
  mkdirSync(source, { recursive: true });
  run('git', ['init', source]);
  run('git', ['-C', source, 'remote', 'add', 'origin', quickjs.repository]);
  run('git', ['-C', source, 'fetch', '--depth=1', 'origin', quickjs.commit]);
  run('git', ['-C', source, 'checkout', '--detach', 'FETCH_HEAD']);
}
const sha = execFileSync('git', ['-C', source, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
assert.equal(sha, quickjs.commit, 'QuickJS frozen revision');
const fsrs = read('fsrs');
run('cargo', ['fetch', '--locked', '--manifest-path', 'engines/fsrs/Cargo.toml'], {
  env: { ...process.env, CARGO_HOME: resolve(root, fsrs.cargo.home) },
});
