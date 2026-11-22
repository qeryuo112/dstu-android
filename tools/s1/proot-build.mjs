import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
const build = resolve(process.argv[2] ?? join(root, 'engines/proot/build'));
const allowed = join(root, 'engines/proot/build');
if (build !== allowed && !build.startsWith(allowed + sep)) throw new Error('PRoot output must be in engines/proot/build');
const lock = JSON.parse(readFileSync(join(root, 'config/stages/s1-proot-source.json'), 'utf8'));
const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
for (const [path, expected] of Object.entries({ ...lock.files, ...lock.localFiles })) {
  if (hash(join(root, path)) !== expected) throw new Error(`PRoot source digest mismatch: ${path}`);
}
function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? walk(path) : [path.slice(root.length + 1)];
  });
}
const actual = [...walk(join(root, 'third_party/proot')), ...walk(join(root, 'third_party/talloc'))].sort();
if (JSON.stringify(actual) !== JSON.stringify(Object.keys(lock.files).sort())) throw new Error('PRoot source file set changed');
const ndk = resolve(process.env.ANDROID_NDK_HOME ?? join(process.env.ANDROID_HOME ?? join(process.env.HOME, 'Android/Sdk'), 'ndk/29.0.13846066'));
if (!readFileSync(join(ndk, 'source.properties'), 'utf8').includes(`Pkg.Revision = ${lock.ndk}`)) throw new Error('PRoot NDK version mismatch');
const bin = join(ndk, 'toolchains/llvm/prebuilt/linux-x86_64/bin');
const compiler = join(bin, 'aarch64-linux-android26-clang');
const toolDigests = {
  'aarch64-linux-android26-clang': 'c9ec0ccdc82aede75e6724738b833603707c0990061e05a1484860249f7520d5',
  clang: '8a380f6bb33db6b84d4248684feef33133d1cddf4ee4a3402a9c25beb2e4b0e6',
  'ld.lld': '0ea81780c84cc2588e0df2cd1676ef986e14f51d04ab638c909bb05c380d13dd',
  'llvm-readelf': 'f5065278c146659a872e64e15543834e0fc4dd1dde277ab266a8662ace06b657',
};
for (const [name, expected] of Object.entries(toolDigests)) {
  if (hash(join(bin, name)) !== expected) throw new Error(`PRoot NDK tool digest mismatch: ${name}`);
}

const temp = join(build, 'proot-native-tmp');
rmSync(temp, { recursive: true, force: true });
mkdirSync(temp, { recursive: true });
const env = { ...process.env, LC_ALL: 'C', CPATH: '', C_INCLUDE_PATH: '', CPLUS_INCLUDE_PATH: '', LIBRARY_PATH: '', SOURCE_DATE_EPOCH: '0' };
function run(command, args, cwd = temp, capture = false) {
  const result = spawnSync(command, args, { cwd, env, encoding: 'utf8', stdio: capture ? 'pipe' : 'inherit' });
  if (result.status !== 0) throw new Error(`PRoot build failed: ${command}: ${result.stderr ?? result.error ?? result.status}`);
  return result.stdout ?? '';
}
try {
  const src = join(temp, 'src');
  cpSync(join(root, 'third_party/proot/src'), src, { recursive: true });
  // Target feature checks are compile/link probes, never executed on the host.
  // A fixed version replaces git describe; the vendored source digest is authoritative.
  writeFileSync(join(src, '.check_process_vm.res'), '#define HAVE_PROCESS_VM\n');
  writeFileSync(join(src, '.check_seccomp_filter.res'), '#define HAVE_SECCOMP_FILTER\n');
  writeFileSync(join(src, 'build.h'), `#ifndef BUILD_H\n#define BUILD_H\n#define VERSION "${lock.proot.version}"\n#define HAVE_PROCESS_VM\n#define HAVE_SECCOMP_FILTER\n#endif\n`);
  const talloc = join(temp, 'talloc.o');
  run(compiler, ['-O2', '-fPIC', '-ffile-prefix-map=' + root + '=.', '-D_GNU_SOURCE', '-I' + join(root, 'engines/proot/src/main/cpp'), '-I' + join(root, 'third_party/talloc'), '-c', join(root, 'third_party/talloc/talloc.c'), '-o', talloc]);
  run(join(bin, 'llvm-ar'), ['rcs', join(temp, 'libtalloc.a'), talloc]);
  const makeArgs = ['-j4', 'CC=' + compiler, 'LD=' + compiler, 'STRIP=' + join(bin, 'llvm-strip'), 'OBJCOPY=' + join(bin, 'llvm-objcopy'), 'OBJDUMP=' + join(bin, 'llvm-objdump'), 'HAS_LOADER_32BIT=', 'GIT=false', 'CFLAGS=-O2 -fPIE -fno-ident -Wall -Wextra -ffile-prefix-map=' + root + '=.', 'CPPFLAGS=-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -include ' + join(root, 'engines/proot/src/main/cpp/proot-include.h') + ' -I. -I' + join(root, 'third_party/talloc'), 'LDFLAGS=-L' + temp + ' -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384', 'LOADER_LDFLAGS=-static -nostdlib -Wl,--build-id=none,-Ttext=0x5400000000,--rosegment,-z,noexecstack,-z,max-page-size=16384'];
  run('make', [...makeArgs, 'loader/loader'], src);
  // Generate the exact upstream loader-info.awk result using target symbols;
  // this removes the host gawk strtonum dependency without modifying PRoot.
  const symbols = run(join(bin, 'llvm-nm'), ['--defined-only', join(src, 'loader/loader')], src, true);
  function address(name) {
    const line = symbols.split('\n').find(value => value.trim().split(/\s+/).at(-1) === name);
    if (!line) throw new Error('PRoot loader symbol missing: ' + name);
    return BigInt('0x' + line.trim().split(/\s+/)[0]);
  }
  writeFileSync(join(src, 'loader/loader-info.c'), '#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=' + (address('pokedata_workaround') - address('_start')) + ';\n');
  run('make', makeArgs, src);
  const dest = join(build, 'generated/proot-jni/arm64-v8a');
  mkdirSync(dest, { recursive: true });
  const outputs = {};
  for (const [name, input] of [['libdstu_proot.so', 'proot'], ['libdstu_loader.so', 'loader/loader']]) {
    const output = join(dest, name);
    cpSync(join(src, input), output);
    run(join(bin, 'llvm-strip'), ['--strip-all', output]);
    const loads = run(join(bin, 'llvm-readelf'), ['-lW', output], temp, true).split('\n').filter(line => /^\s*LOAD\s/.test(line));
    if (!loads.length || loads.some(line => Number.parseInt(line.trim().split(/\s+/).at(-1), 16) < 16384)) throw new Error('PRoot LOAD alignment failed');
    outputs[name] = { sha256: hash(output), bytes: readFileSync(output).length };
  }
  const artifacts = { id: `termux-${lock.proot.commit.slice(0, 12)}-talloc-2.4.2`, ...outputs };
  const resources = join(build, 'generated/proot-resources');
  mkdirSync(resources, { recursive: true });
  writeFileSync(join(resources, 'dstu-proot-artifacts.properties'), ['id=' + artifacts.id, ...Object.entries(outputs).flatMap(([name, value]) => [`${name}.sha256=${value.sha256}`, `${name}.bytes=${value.bytes}`])].join('\n') + '\n');
  const notices = join(build, 'generated/proot-notices/proot');
  mkdirSync(notices, { recursive: true });
  cpSync(join(root, 'third_party/proot'), join(notices, 'source'), { recursive: true });
  cpSync(join(root, 'third_party/talloc'), join(notices, 'talloc'), { recursive: true });
  cpSync(join(root, 'engines/proot/src/main/cpp'), join(notices, 'android'), { recursive: true });
  cpSync(join(root, 'tools/s1/proot-build.mjs'), join(notices, 'proot-build.mjs'));
  cpSync(join(root, 'config/stages/s1-proot-source.json'), join(notices, 'source-lock.json'));
  console.log(JSON.stringify({ event: 'proot.source-built', artifacts, commit: lock.proot.commit, abi: lock.abi, minSdk: lock.minSdk, loadAlignment: 16384 }));
} finally {
  rmSync(temp, { recursive: true, force: true });
}
