import { join } from 'node:path';
import { ensureDirectories, lock, output, root, run, source, verifySource, work } from './script-build-lib.mjs';

verifySource();
const build = join(work, 'host-probe');
ensureDirectories(build);
const common = [
  '-O2', '-fwrapv', '-funsigned-char', '-D_GNU_SOURCE',
  `-DCONFIG_VERSION="${lock.engine.version}"`, '-DNDEBUG', `-I${source}`,
];
const objects = [];
for (const name of ['quickjs.c', 'cutils.c', 'dtoa.c', 'libregexp.c', 'libunicode.c']) {
  const object = join(build, `${name}.o`);
  run('gcc', ['-std=gnu11', ...common, '-c', join(source, name), '-o', object]);
  objects.push(object);
}
const core = join(build, 'quickjs_engine.o');
run('g++', [
  '-std=c++17', ...common, '-Wall', '-Wextra', `-I${join(root, 'engines/quickjs/src/main/cpp')}`,
  '-c', join(root, 'engines/quickjs/src/main/cpp/quickjs_engine.cpp'), '-o', core,
]);
const probe = join(build, 'script-native-probe');
run('g++', [
  '-std=c++17', '-O2', `-I${join(root, 'engines/quickjs/src/main/cpp')}`,
  join(root, 'tools/s1/script-native-probe.cpp'), core, ...objects,
  '-lm', '-ldl', '-lpthread', '-o', probe,
]);
run(probe, [join(root, 'fixtures/script')], root);
console.error(`[script-probe] quickjs=${lock.engine.commit} executable=${probe}`);
