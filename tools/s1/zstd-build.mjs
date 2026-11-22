import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync, copyFileSync, writeFileSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
const lockPath = join(root, 'config/stages/s1-zstd-source.json');
const lock = JSON.parse(readFileSync(lockPath));
const archive = resolve(root, process.env.DSTU_ZSTD_ARCHIVE || lock.engine.archive);
const work = resolve(process.env.DSTU_ZSTD_WORK || join(root, '../build-tools/s1-zstd-work'));
const source = join(work, `zstd-${lock.engine.commit}`);
const mode = process.argv[2] || 'android';
const ndk = resolve(process.env.ANDROID_NDK_HOME || join(process.env.HOME, 'Android/Sdk/ndk', lock.build.ndk));
const bin = join(ndk, 'toolchains/llvm/prebuilt/linux-x86_64/bin');
const out = mode === 'host' ? join(root, 'engines/zstd/build/host') : join(root, 'engines/zstd/build/generated/zstd-jni/arm64-v8a');
const notices = join(root, 'engines/zstd/build/generated/zstd-notices/zstd');
const hash = file => createHash('sha256').update(readFileSync(file)).digest('hex');
function run(command, args, capture = false) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', stdio: capture ? 'pipe' : 'inherit', maxBuffer: 32 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${command} failed: ${result.stderr || result.error || result.status}`);
  return result.stdout?.trim() || '';
}
if (!['host', 'android'].includes(mode)) throw new Error('unsupported build mode');
if (hash(archive) !== lock.engine.sha256) throw new Error('zstd archive hash mismatch');
mkdirSync(work, { recursive: true });
const verified = join(root, 'engines/zstd/build', `${mode}-verified-source`);
rmSync(join(out, 'verified-source'), { recursive: true, force: true });
rmSync(verified, { recursive: true, force: true });
mkdirSync(verified, { recursive: true });
run('tar', ['-xzf', archive, '--strip-components=1', '-C', verified]);
for (const [file, expected] of Object.entries(lock.engine.files)) {
  if (hash(join(verified, file)) !== expected) throw new Error(`zstd archived source hash mismatch: ${file}`);
  if (existsSync(source) && hash(join(source, file)) !== expected) throw new Error(`zstd cached source hash mismatch: ${file}`);
}
const sourceDirs = ['common', 'compress', 'decompress'];
const sources = Object.keys(lock.engine.files).filter(file => sourceDirs.some(dir => file.startsWith(`lib/${dir}/`)) && file.endsWith('.c'));
if (!sources.length) throw new Error('missing source files');
mkdirSync(out, { recursive: true });
const objDir = join(work, `${mode}-obj`);
mkdirSync(objDir, { recursive: true });
const compiler = mode === 'host' ? 'cc' : join(bin, 'aarch64-linux-android26-clang');
const flags = ['-O2', '-fPIC', '-fvisibility=hidden', '-DNDEBUG', '-DZSTD_DISABLE_ASM', '-DZSTD_MULTITHREAD=0',
  `-ffile-prefix-map=${root}=.`, `-I${join(verified, 'lib')}`];
if (mode === 'host') {
  const javaHome = process.env.JAVA_HOME || dirname(dirname(run('readlink', ['-f', run('which', ['javac'], true)], true)));
  flags.push(`-I${join(javaHome, 'include')}`, `-I${join(javaHome, 'include/linux')}`);
}
const objects = [];
for (const file of [...sources.map(file => join(verified, file)), join(root, 'engines/zstd/src/main/cpp/zstd_jni.c')]) {
  const object = join(objDir, `${objects.length}.o`);
  run(compiler, [...flags, '-c', file, '-o', object]);
  objects.push(object);
}
const library = join(out, 'libdstu_zstd.so');
const versionScript = join(work, 'exports.map');
writeFileSync(versionScript, '{ global: Java_com_deepstudent_engines_zstd_ZstdNative_*; local: *; };\n');
const linker = ['-shared', '-Wl,--no-undefined', '-Wl,--exclude-libs,ALL', `-Wl,--version-script=${versionScript}`];
if (mode === 'android') linker.push('-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384');
run(compiler, [...linker, ...objects, '-o', library]);
const nm = mode === 'host' ? 'nm' : join(bin, 'llvm-nm');
const exports = run(nm, ['-D', '--defined-only', library], true).split('\n').filter(Boolean);
if (exports.length !== 2 || exports.some(line => !line.includes('Java_com_deepstudent_engines_zstd_ZstdNative_'))) throw new Error('unexpected exports');
if (mode === 'android') {
  const readelf = join(bin, 'llvm-readelf');
  const header = run(readelf, ['-hW', library], true);
  const notes = run(readelf, ['--notes', library], true);
  if (!header.includes('AArch64') || !/NT_ANDROID_TYPE_IDENT\s*\n\s*description data: 1a 00 00 00\b/.test(notes)) throw new Error('incorrect ABI/API');
  const loads = run(readelf, ['-lW', library], true).split('\n').filter(line => /^\s*LOAD\s/.test(line));
  if (!loads.length || loads.some(line => { const f = line.trim().split(/\s+/); return parseInt(f.at(-1), 16) < 16384 || (parseInt(f[2], 16) - parseInt(f[1], 16)) % 16384; })) throw new Error('invalid LOAD alignment');
  mkdirSync(notices, { recursive: true });
  copyFileSync(join(verified, 'LICENSE'), join(notices, 'LICENSE'));
  copyFileSync(lockPath, join(notices, 'source.json'));
  copyFileSync(join(root, 'tools/s1/zstd-build.mjs'), join(notices, 'zstd-build.mjs'));
  copyFileSync(join(root, 'engines/zstd/src/main/cpp/zstd_jni.c'), join(notices, 'zstd_jni.c'));
  writeFileSync(join(notices, 'build.json'), JSON.stringify({ commit: lock.engine.commit, archiveSha256: lock.engine.sha256, ndk: lock.build.ndk, abi: lock.build.abi, minSdk: lock.build.minSdk, pageAlignment: lock.build.pageAlignment, librarySha256: hash(library) }, null, 2));
}
console.error(`[zstd-build] mode=${mode} commit=${lock.engine.commit} sources=${sources.length} sha256=${hash(library)}`);
