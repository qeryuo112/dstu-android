// Export source snapshots, NEVER copy/fetch/push the private repository's Git object database.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { resolve, relative, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

export const sourceRoot = resolve(import.meta.dirname, '../..');
const stampFile = 'config/publication/export.json';
const author = ['-c', 'user.name=Codex', '-c', 'user.email=codex@localhost'];
function git(root, args, input) {
  return execFileSync('git', ['-C', root, ...args], { input, maxBuffer: 128 * 1024 * 1024 });
}
function text(root, ...args) { return git(root, args).toString('utf8').trim(); }

export function assertPublicPath(path) {
  assert.ok(typeof path === 'string' && path.length > 0 && !path.startsWith('/') && !path.includes('\\') && !path.split('/').includes('..'), `unsafe export path: ${path}`);
  assert.ok(!/(^|\/)(?:logs|artifacts|\.git|\.gradle|\.kotlin|node_modules|build)(\/|$)/u.test(path), `private/generated path: ${path}`);
  assert.ok(!/\.(?:log|png|jpe?g|gif|webp|mp4|apk|aab|jks|keystore|p12|pem|key|tar|gz)$/iu.test(path), `non-source artifact: ${path}`);
  if (/\.zip$/iu.test(path)) assert.ok(/^fixtures\/legacy-mobile\/legacy-mobile-(?:standard-standard|continuity-continuity)-v1\.zip$/u.test(path), `unreviewed archive: ${path}`);
}
function treeFiles(root, ref) {
  return git(root, ['ls-tree', '-rz', ref]).toString('utf8').split('\0').filter(Boolean).map(row => {
    const split = row.indexOf('\t');
    const metadata = row.slice(0, split); const path = row.slice(split + 1);
    assert.ok(/^100(?:644|755) blob /u.test(metadata), `symlinks/submodules are not part of a public snapshot: ${path}`);
    return path;
  });
}
function materialize(source, ref, destination, files) {
  for (const file of files) assertPublicPath(file);
  const archive = git(source, ['archive', '--format=tar', ref, '--', ...files]);
  execFileSync('tar', ['--extract', '--no-same-owner', '--file=-', '--directory', destination], { input: archive });
}
function commit(root, message) {
  git(root, ['add', '--all']);
  git(root, [...author, 'commit', '--quiet', '-m', message]);
  return text(root, 'rev-parse', 'HEAD');
}

export function verifyPublicHistory(root) {
  assert.ok(!existsSync(resolve(root, '.git/objects/info/alternates')), 'object alternates would expose private history');
  const refs = text(root, 'for-each-ref', '--format=%(refname)').split('\n').filter(Boolean);
  assert.ok(refs.every(ref => /^(?:refs\/heads\/|refs\/remotes\/(?:origin|pull)\/|refs\/tags\/)/u.test(ref)), 'unexpected refs in public checkout');
  const commits = text(root, 'rev-list', '--all').split('\n').filter(Boolean);
  assert.ok(commits.length > 0, 'empty public history');
  for (const ref of commits) for (const path of treeFiles(root, ref)) assertPublicPath(path);
  return { commits: commits.length, files: treeFiles(root, 'HEAD').length, head: text(root, 'rev-parse', 'HEAD') };
}

export function exportSource({ source = sourceRoot, destination }) {
  const dest = resolve(destination);
  const rel = relative(source, dest);
  assert.ok(rel.startsWith(`..${sep}`) && relative(dest, source).startsWith(`..${sep}`), 'public checkout must be outside and not contain private checkout');
  assert.equal(text(source, 'status', '--porcelain'), '', 'commit private source edits before export');
  const sourceCommit = text(source, 'rev-parse', 'HEAD');
  const currentFiles = treeFiles(source, sourceCommit);
  currentFiles.forEach(assertPublicPath);
  let baseline;
  if (!existsSync(dest)) {
    mkdirSync(dest, { recursive: true });
    git(dest, ['init', '--quiet', '--initial-branch=main']);
    const privateBaseline = readFileSync(resolve(source, 'config/quality/format-baseline.txt'), 'utf8').trim();
    assert.match(privateBaseline, /^[0-9a-f]{40}$/u);
    // Minimal source-only formatting baseline, not a copy of any private commit/history.
    const baselineFiles = treeFiles(source, privateBaseline).filter(path => /\.(?:kt|kts)$/u.test(path) && !path.startsWith('third_party/'));
    materialize(source, privateBaseline, dest, baselineFiles);
    writeFileSync(resolve(dest, 'LICENSE'), readFileSync(resolve(source, 'LICENSE')));
    writeFileSync(resolve(dest, 'README.md'), '# Source-only formatting baseline\n\nThis root contains Kotlin formatting reference sources, not private Git history, logs or screenshots. The next commit imports the current application.\n');
    baseline = commit(dest, 'chore: establish source-only formatting baseline');
  } else {
    assert.ok(existsSync(resolve(dest, stampFile)), 'destination is not a managed public snapshot checkout');
    assert.equal(text(dest, 'status', '--porcelain'), '', 'public checkout has local changes; refusing to overwrite');
    assert.equal(text(dest, 'branch', '--show-current'), 'main');
    verifyPublicHistory(dest);
    baseline = JSON.parse(readFileSync(resolve(dest, stampFile), 'utf8')).formatBaseline;
    assert.equal(text(dest, 'merge-base', '--is-ancestor', baseline, 'HEAD'), '');
  }
  // Only tracked files of our clean dedicated checkout are replaced. Private source history is untouched.
  for (const file of treeFiles(dest, 'HEAD')) rmSync(resolve(dest, file));
  materialize(source, sourceCommit, dest, currentFiles);
  writeFileSync(resolve(dest, 'config/quality/format-baseline.txt'), `${baseline}\n`);
  for (const file of readdirSync(resolve(dest, 'docs/evidence')).filter(name => /^logs-index.*\.json$/u.test(name))) {
    const path = resolve(dest, 'docs/evidence', file);
    const index = JSON.parse(readFileSync(path, 'utf8'));
    index.archive.root = 'PRIVATE_ARTIFACT_ROOT';
    index.archive.location = `PRIVATE_ARTIFACT_ROOT/${index.archive.relativeLocation}`;
    if (index.archive.manifest) index.archive.manifest.location = `PRIVATE_ARTIFACT_ROOT/${index.archive.manifest.relativeLocation}`;
    writeFileSync(path, `${JSON.stringify(index, null, 2)}\n`);
  }
  mkdirSync(resolve(dest, 'config/publication'), { recursive: true });
  writeFileSync(resolve(dest, stampFile), `${JSON.stringify({ formatVersion: 1, sourceCommit,
    formatBaseline: baseline, excludesPrivateHistory: true, excludesRawEvidence: true }, null, 2)}\n`);
  commit(dest, `chore: publish reviewed source snapshot ${sourceCommit.slice(0, 12)}`);
  return { destination: dest, sourceCommit, ...verifyPublicHistory(dest) };
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  assert.equal(process.argv.length, 3, 'usage: node tools/publication/export.mjs ABSOLUTE_PUBLIC_CHECKOUT');
  assert.ok(process.argv[2].startsWith('/'), 'explicit absolute public checkout path required');
  console.log(JSON.stringify(exportSource({ destination: process.argv[2] }), null, 2));
}
