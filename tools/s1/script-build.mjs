import { copyFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import {
  androidOut, assertHash, clangDir, ensureDirectories, lock, ndk, noticeOut,
  output, root, run, source, verifySource, work,
} from './script-build-lib.mjs';

verifySource();
const obj = join(work, 'android-obj');
ensureDirectories(obj, androidOut, noticeOut);
const cCompiler = join(clangDir, 'aarch64-linux-android26-clang');
const cxxCompiler = join(clangDir, 'aarch64-linux-android26-clang++');
const common = [
  '-O2', '-fPIC', '-fvisibility=hidden', '-fwrapv', '-funsigned-char',
  '-D_GNU_SOURCE', `-DCONFIG_VERSION="${lock.engine.version}"`, '-DNDEBUG',
  `-I${source}`, `-I${join(root, 'engines/quickjs/src/main/cpp')}`,
];
const cSources = ['quickjs.c', 'cutils.c', 'dtoa.c', 'libregexp.c', 'libunicode.c'];
const objects = [];
for (const name of cSources) {
  const object = join(obj, `${name}.o`);
  run(cCompiler, [
    '-std=gnu11', ...common, '-Wno-sign-compare', '-Wno-missing-field-initializers',
    '-Wno-unused-parameter', '-Wno-array-bounds', '-Wno-format-truncation',
    '-c', join(source, name), '-o', object,
  ]);
  objects.push(object);
}
for (const name of ['quickjs_engine.cpp', 'quickjs_jni.cpp']) {
  const object = join(obj, `${name}.o`);
  run(cxxCompiler, [
    '-std=c++17', ...common, '-Wall', '-Wextra', '-Wno-unused-parameter',
    '-c', join(root, 'engines/quickjs/src/main/cpp', name), '-o', object,
  ]);
  objects.push(object);
}
const library = join(androidOut, lock.build.library);
run(cxxCompiler, [
  '-shared', '-static-libstdc++', '-Wl,-soname,libdstu_quickjs.so',
  '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
  '-Wl,--no-undefined', '-Wl,--exclude-libs,ALL',
  ...objects, '-landroid', '-llog', '-lm', '-ldl', '-o', library,
]);

const elf = output(join(clangDir, 'llvm-readelf'), ['-lW', library]);
const header = output(join(clangDir, 'llvm-readelf'), ['-hW', library]);
const notes = output(join(clangDir, 'llvm-readelf'), ['--notes', library]);
const exports = output(join(clangDir, 'llvm-nm'), ['-D', '--defined-only', library])
  .split('\n').filter(Boolean);
if (!header.includes('AArch64') || !/NT_ANDROID_TYPE_IDENT\s*\n\s*description data: 1a 00 00 00\b/.test(notes)) {
  throw new Error('wrong ABI or Android API note');
}
if (exports.length !== 5 || exports.some(line => !line.includes('Java_com_deepstudent_engines_quickjs_QuickJsNative_'))) {
  throw new Error(`unexpected dynamic exports: ${exports.join(', ')}`);
}
const loads = elf.split('\n').filter(line => /^\s*LOAD\s/.test(line));
if (!loads.length) throw new Error('missing ELF LOAD segments');
for (const line of loads) {
  const fields = line.trim().split(/\s+/);
  const offset = Number.parseInt(fields[1], 16);
  const address = Number.parseInt(fields[2], 16);
  const align = Number.parseInt(fields.at(-1), 16);
  if (align < lock.build.pageAlignment || (address - offset) % lock.build.pageAlignment !== 0) {
    throw new Error(`invalid 16KiB LOAD alignment: ${line}`);
  }
}
ensureDirectories(join(noticeOut, 'quickjs'));
copyFileSync(join(source, 'LICENSE'), join(noticeOut, 'quickjs/LICENSE'));
copyFileSync(join(root, 'config/stages/s1-script-source.json'), join(noticeOut, 'quickjs/source.json'));
assertHash(join(noticeOut, 'quickjs/LICENSE'), lock.engine.files.LICENSE);
console.error(
  `[script-build] engine=${lock.engine.commit} abi=${lock.build.abi} api=${lock.build.minSdk} ` +
  `loadAlignment>=${lock.build.pageAlignment} exports=${exports.length} ` +
  `sha256=${output('sha256sum', [library]).split(/\s+/)[0]} file=${library}`,
);
