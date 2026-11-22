import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { test } from 'node:test';
import { assertPublicPath, exportSource, verifyPublicHistory } from './export.mjs';
const git = (root, ...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
test('public path policy rejects evidence, archives and credentials; retains reviewed source fixtures', () => {
  for (const path of ['logs/old.log', 'screenshots/old.png', 'artifacts/archive.tar.gz', '.git/config', '../escape', 'keys/signing.jks', 'backup.zip', 'app/build/output.txt']) {
    assert.throws(() => assertPublicPath(path));
  }
  for (const path of ['app/src/main/Main.kt', 'docs/evidence/logs-index.json', 'gradle/wrapper/gradle-wrapper.jar', 'fixtures/legacy-mobile/legacy-mobile-standard-standard-v1.zip']) assert.doesNotThrow(() => assertPublicPath(path));
});
test('export imports no private ancestors and keeps a fixed public formatting base on update', t => {
  const base = mkdtempSync(resolve(tmpdir(), 'dstu-public-export-'));
  t.after(() => rmSync(base, { recursive: true, force: true }));
  const source = resolve(base, 'private'); const destination = resolve(base, 'public'); mkdirSync(source);
  git(source, 'init', '-q', '--initial-branch=main'); git(source, 'config', 'user.name', 'Fixture'); git(source, 'config', 'user.email', 'fixture@example.invalid');
  const write = (path, value) => { mkdirSync(resolve(source, path, '..'), { recursive: true }); writeFileSync(resolve(source, path), value); };
  write('app/Main.kt', 'fun value() = 1\n'); write('logs/private.log', 'DO_NOT_PUBLISH'); write('logs/private.png', 'PRIVATE_SCREENSHOT');
  git(source, 'add', '.'); git(source, 'commit', '-qm', 'private baseline'); const old = git(source, 'rev-parse', 'HEAD');
  git(source, 'rm', '-r', '-q', 'logs'); write('config/quality/format-baseline.txt', `${old}\n`); write('LICENSE', 'fixture license\n');
  write('docs/evidence/logs-index.json', JSON.stringify({ archive: { root: '/private', location: '/private/archive', relativeLocation: 'snapshot/evidence.tar.gz' } }));
  write('app/Main.kt', 'fun value() = 2\n'); git(source, 'add', '.'); git(source, 'commit', '-qm', 'private audit');
  const first = exportSource({ source, destination }); assert.equal(first.commits, 2);
  assert.notEqual(git(destination, 'rev-list', '--max-parents=0', 'HEAD'), old);
  assert.notEqual(spawnSync('git', ['-C', destination, 'cat-file', '-e', `${old}^{commit}`], { stdio: 'ignore' }).status, 0);
  assert.equal(git(source, 'show', `${old}:logs/private.log`), 'DO_NOT_PUBLISH');
  const baseline = readFileSync(resolve(destination, 'config/quality/format-baseline.txt'), 'utf8');
  write('app/Main.kt', 'fun value() = 3\n'); git(source, 'add', '.'); git(source, 'commit', '-qm', 'follow up');
  assert.equal(exportSource({ source, destination }).commits, 3);
  assert.equal(readFileSync(resolve(destination, 'config/quality/format-baseline.txt'), 'utf8'), baseline);
  writeFileSync(resolve(destination, 'dirty.txt'), 'user work');
  assert.throws(() => exportSource({ source, destination }), /local changes/u);
  assert.equal(verifyPublicHistory(destination).commits, 3);
});
