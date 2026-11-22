import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { root, workspace, desktop, operit, readJson, git, sha256, artifact, main, log } from './lib.mjs';

export async function run(mode) {
  const inputs = readJson('config/source-inputs.json');
  const repositories = inputs.repositories.map(input => {
    const path = resolve(workspace, input.path);
    const commit = git(path, 'rev-parse', 'HEAD');
    assert.equal(commit, input.commit, `${input.id}: HEAD ${commit} != ${input.commit}`);
    assert.equal(git(path, 'status', '--porcelain', '--untracked-files=no'), '', `${input.id}: tracked source changes`);
    const files = input.anchors.map(file => ({ file, sha256: sha256(readFileSync(resolve(path, file))) }));
    const result = {
      id: input.id, path: input.path, commit, tree: git(path, 'rev-parse', 'HEAD^{tree}'),
      repository: git(path, 'remote', 'get-url', 'origin'),
      contentHash: { algorithm: 'git-sha1-tree', value: git(path, 'rev-parse', 'HEAD^{tree}') },
      acquisition: { method: 'git-checkout', revision: commit, submodules: input.id === 'operit' ? ['terminal'] : [] },
      anchors: files,
    };
    log('source.repository', { id: input.id, commit, tree: result.tree, anchors: files.length });
    return result;
  });
  assert.equal(git(operit, 'rev-parse', 'HEAD:terminal'), inputs.repositories.find(r => r.id === 'terminal').commit);
  const engines = [];
  for (const input of inputs.gitEngines) {
    const url = input.repository.replace('https://github.com/', 'https://api.github.com/repos/').replace(/\.git$/, '') + `/git/commits/${input.commit}`;
    const objectFile = `config/sources/${input.id}.commit.json`;
    let bytes;
    let object;
    let reused = false;
    if (mode === 'capture') {
      try {
        const existing = readFileSync(resolve(root, objectFile));
        const parsed = JSON.parse(existing);
        const declaration = readFileSync(resolve(operit, input.declaration), 'utf8');
        const contractMismatch = declaration.includes(input.commit)
          && input.repository
          && !declaration.includes(input.repository);
        if (parsed.sha === input.commit && /^[a-f0-9]{40}$/.test(parsed.tree?.sha ?? '') && !contractMismatch) {
          bytes = existing;
          object = parsed;
          reused = true;
          log('source.engine.object.reused', { id: input.id, commit: input.commit, tree: object.tree.sha, file: objectFile });
        }
      } catch (_error) {
        // Missing, unreadable or malformed evidence is re-fetched below.
      }
    }
    if (!reused && mode === 'capture') {
      const object = execFileSync('curl', ['--fail', '--silent', '--show-error', '--location', '--max-time', '90', '--proxy', 'http://127.0.0.1:7897', url], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
      mkdirSync(resolve(root, 'config/sources'), { recursive: true });
      writeFileSync(resolve(root, objectFile), object);
      bytes = Buffer.from(object);
      log('source.engine.object.fetched', { id: input.id, commit: input.commit });
    }
    if (bytes === undefined) {
      bytes = readFileSync(resolve(root, objectFile));
      object = JSON.parse(bytes);
    }
    if (object === undefined) object = JSON.parse(bytes);
    assert.equal(object.sha, input.commit, `${input.id}: remote commit object`);
    assert.match(object.tree.sha, /^[a-f0-9]{40}$/);
    const declaration = readFileSync(resolve(operit, input.declaration));
    engines.push({
      ...input, path: `third_party/sources/${input.id}`, hash: { algorithm: 'git-sha1-tree', value: object.tree.sha },
      sourceObject: { path: objectFile, sha256: sha256(bytes) },
      declarationSha256: sha256(declaration),
      acquisition: { method: 'git-checkout', revision: input.commit, archive: input.repository.replace(/\.git$/, '') + `/archive/${input.commit}.tar.gz` },
    });
    log('source.engine', { id: input.id, commit: input.commit, tree: object.tree.sha });
  }
  const lock = JSON.parse(readFileSync(resolve(desktop, 'package-lock.json'), 'utf8'));
  const packages = inputs.desktopEnginePackages.map(name => {
    const item = lock.packages[`node_modules/${name}`];
    assert.ok(item?.version && item.resolved && item.integrity, `${name}: exact package lock required`);
    return { id: name, version: item.version, path: `dstu-node/node_modules/${name}`, hash: { algorithm: 'npm-sri', value: item.integrity }, acquisition: { method: 'npm-locked-tarball', url: item.resolved }, purpose: 'desktop-behavior-reference', androidAdoptionUnit: 'S4.01' };
  });
  artifact('config/source-lock.json', {
    formatVersion: 1, lockedAt: inputs.lockedAt, inputsSha256: sha256(readFileSync(resolve(root, 'config/source-inputs.json'))),
    repositories, engines, packages,
    rules: {
      baselineFreeze: 'The desktop source is frozen at the exact commit in config/source-inputs.json. Do not refresh or advance automatically; any future change requires an explicit new reviewed decision.',
      repositories: 'HEAD, git tree, clean tracked files, byte SHA-256 of anchors',
      engines: 'exact Git commit and tree; archived commit API response SHA-256; floating upstream ref recorded only as evidence',
      packages: 'exact lockfile version, URL and integrity; desktop packages stay in development tools',
      drift: 'fail with artifact JSON path and expected/actual values; capture is not an automatic refresh and must not be used to move a frozen baseline',
      upstreamMobile: 'upstream mobile/Capacitor/storage-mobile/Java runtime is source-diff/legacy evidence only, not product delivery, and must not create feature-completion requirements',
      legacyExport: 'S0.16 keeps the existing data-export contract and validates the target schema with existing legacy samples; do not extend the upstream Android chain',
    },
  }, mode);
}
main(import.meta.url, run);
