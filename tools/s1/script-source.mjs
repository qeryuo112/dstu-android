import { existsSync } from 'node:fs';
import { join } from 'node:path';
import {
  assertGit, ensureDirectories, lock, output, run, source, verifySource, work,
} from './script-build-lib.mjs';

ensureDirectories(work);
if (!existsSync(join(source, '.git'))) {
  run('git', [
    '-c', 'http.version=HTTP/1.1', 'clone', '--no-checkout', '--filter=blob:none',
    lock.engine.repository, source,
  ]);
}
const current = output('git', ['-C', source, 'rev-parse', 'HEAD']);
if (current !== lock.engine.commit) {
  run('git', [
    '-c', 'http.version=HTTP/1.1', '-C', source, 'fetch', '--depth=1',
    'origin', lock.engine.commit,
  ]);
  run('git', ['-C', source, 'switch', '--detach', lock.engine.commit]);
}
assertGit(source, lock.engine.commit, lock.engine.tree);
verifySource();
console.error(`[script-source] quickjs=${lock.engine.commit} tree=${lock.engine.tree} path=${source}`);
