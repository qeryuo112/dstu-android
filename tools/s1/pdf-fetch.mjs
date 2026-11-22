import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { assertGit, assertHash, depot, gitClone, lock, run, source, work } from './pdf-build-lib.mjs';

mkdirSync(work, { recursive: true });
if (!existsSync(join(depot, '.git'))) gitClone(depot, lock.depotTools.url, lock.depotTools.commit);
assertGit(depot, lock.depotTools.commit, lock.depotTools.tree);
if (!existsSync(join(source, '.git'))) gitClone(source, lock.pdfium.url, lock.pdfium.commit);
assertGit(source, lock.pdfium.commit, lock.pdfium.tree);
assertHash(join(source, 'DEPS'), lock.pdfium.depsSha256);
const env = {
  PATH: `${depot}:${process.env.PATH}`,
  GIT_CONFIG_COUNT: '1', GIT_CONFIG_KEY_0: 'http.version', GIT_CONFIG_VALUE_0: 'HTTP/1.1',
};
if (!existsSync(join(work, '.gclient'))) {
  run(join(depot, 'gclient'), [
    'config', '--unmanaged', '--name', 'pdfium', '--custom-var', 'checkout_configuration=minimal',
    '--custom-var', 'checkout_android_native_support=False', lock.pdfium.url,
  ], work, env);
}
assertHash(join(work, '.gclient'), lock.gclientConfigSha256);
run(join(depot, 'gclient'), [
  'sync', '--no-history', '--shallow', '--nohooks', '--deps=android', '-j', '4',
  '-r', `pdfium@${lock.pdfium.commit}`,
], work, env);
const simdutf = join(source, 'third_party/simdutf');
if (!existsSync(join(simdutf, '.git'))) gitClone(simdutf, lock.simdutf.url, lock.simdutf.commit);
assertGit(simdutf, lock.simdutf.commit);
console.error('[pdf-build] Source acquisition complete. Run pdf-build.mjs to verify and compile.');
