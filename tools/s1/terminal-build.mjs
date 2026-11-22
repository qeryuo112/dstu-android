import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
if (!process.argv[2]) throw new Error('Usage: node tools/s1/terminal-build.mjs <terminal-build-directory>');
const build = resolve(process.argv[2]);
const allowed = join(root, 'engines/terminal/build');
if (build !== allowed && !build.startsWith(allowed + sep)) {
  throw new Error('Output must be under engines/terminal/build');
}

const ndk = resolve(
  process.env.ANDROID_NDK_HOME ||
    join(process.env.ANDROID_HOME || join(process.env.HOME, 'Android/Sdk'), 'ndk/29.0.13846066'),
);
const bin = join(ndk, 'toolchains/llvm/prebuilt/linux-x86_64/bin');
const tools = {
  'aarch64-linux-android26-clang': 'c9ec0ccdc82aede75e6724738b833603707c0990061e05a1484860249f7520d5',
  clang: '8a380f6bb33db6b84d4248684feef33133d1cddf4ee4a3402a9c25beb2e4b0e6',
  'ld.lld': '0ea81780c84cc2588e0df2cd1676ef986e14f51d04ab638c909bb05c380d13dd',
  'llvm-readelf': 'f5065278c146659a872e64e15543834e0fc4dd1dde277ab266a8662ace06b657',
  'llvm-nm': '9cb5e7d38d53329046f0e65db5dbb717ae8241a7dcdcc980e601801497af72e7',
};
const companion = 'Java_com_ai_assistance_operit_terminal_Pty_00024Companion_';
const exports = [
  ...['closeRaw', 'createSubprocess', 'setCloseOnExec', 'setNonBlocking', 'signalSession',
    'terminateSession', 'waitFor', 'waitForNoHang'].map(name => companion + name),
  'Java_com_ai_assistance_operit_terminal_Pty_setPtyWindowSize',
].sort();
const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
for (const [name, expected] of Object.entries(tools)) {
  if (hash(join(bin, name)) !== expected) throw new Error(`Terminal NDK tool digest mismatch: ${name}`);
}
if (!/^Pkg\.Revision\s*=\s*29\.0\.13846066-beta3\s*$/m.test(readFileSync(join(ndk, 'source.properties'), 'utf8'))) {
  throw new Error('Terminal requires Android NDK 29.0.13846066-beta3');
}

const temp = join(build, 'terminal-native-tmp');
mkdirSync(temp, { recursive: true });
const env = {
  ...process.env,
  TMPDIR: temp,
  CPATH: '',
  C_INCLUDE_PATH: '',
  CPLUS_INCLUDE_PATH: '',
  LIBRARY_PATH: '',
};

function run(command, args, capture = false) {
  const result = spawnSync(command, args, {
    cwd: root,
    env,
    encoding: 'utf8',
    stdio: capture ? 'pipe' : 'inherit',
  });
  if (result.status !== 0) {
    throw new Error(`${command} failed: ${result.stderr || result.error || result.signal || result.status}`);
  }
  return result.stdout?.trim() || '';
}

try {
  run(process.execPath, [join(root, 'tools/s1/terminal-source.mjs')]);
  const versionScript = join(temp, 'exports.map');
  writeFileSync(versionScript, `{\n  global:\n${exports.map(name => `    ${name};`).join('\n')}\n  local: *;\n};\n`);
  const dest = join(build, 'generated/terminal-jni/arm64-v8a/libpty.so');
  mkdirSync(dirname(dest), { recursive: true });
  const candidate = join(temp, 'libpty.so');
  const vendorObject = join(temp, 'operit-pty.o');
  run(join(bin, 'aarch64-linux-android26-clang'), [
    '-std=gnu11', '-O2', '-fPIC', '-fvisibility=hidden',
    '-DJava_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess=operit_create_subprocess',
    '-c', join(root, 'third_party/operit/terminal/src/main/jni/pty.c'), '-o', vendorObject,
  ]);
  run(join(bin, 'aarch64-linux-android26-clang'), [
    '-std=gnu11', '-O2', '-fPIC', '-fvisibility=hidden', '-shared',
    '-Wl,-z,max-page-size=16384', '-Wl,--no-undefined', '-Wl,--wrap=forkpty',
    `-Wl,--version-script=${versionScript}`,
    vendorObject,
    join(root, 'engines/terminal/src/main/cpp/pty_owner.c'),
    '-llog',
    '-o', candidate,
  ]);
  const header = run(join(bin, 'llvm-readelf'), ['-hW', candidate], true);
  const notes = run(join(bin, 'llvm-readelf'), ['--notes', candidate], true);
  const loads = run(join(bin, 'llvm-readelf'), ['-lW', candidate], true)
    .split('\n')
    .filter(line => /^\s*LOAD\s/.test(line));
  const actualExports = run(join(bin, 'llvm-nm'), ['-D', '--defined-only', candidate], true)
    .split('\n')
    .filter(Boolean)
    .map(line => line.trim().split(/\s+/).at(-1))
    .sort();
  if (!/Machine:\s+AArch64\b/.test(header) || !/Type:\s+DYN\b/.test(header) ||
      !/NT_ANDROID_TYPE_IDENT\s*\n\s*description data: 1a 00 00 00\b/.test(notes)) {
    throw new Error('Invalid terminal ELF ABI, type or Android API note');
  }
  if (!loads.length || loads.some(line => {
    const fields = line.trim().split(/\s+/);
    const offset = Number.parseInt(fields[1], 16);
    const address = Number.parseInt(fields[2], 16);
    const alignment = Number.parseInt(fields.at(-1), 16);
    return !Number.isSafeInteger(offset) || !Number.isSafeInteger(address) ||
      !Number.isSafeInteger(alignment) || alignment < 16384 || (address - offset) % 16384 !== 0;
  })) {
    throw new Error('Invalid terminal ELF LOAD alignment');
  }
  if (JSON.stringify(actualExports) !== JSON.stringify(exports)) {
    throw new Error(`Unexpected terminal JNI exports: ${actualExports.join(', ')}`);
  }
  renameSync(candidate, dest);
  console.error(`[terminal-build] ndk=29.0.13846066-beta3 abi=arm64-v8a api=26 loadAlignment>=16384 exports=${exports.length}`);
  console.error(`[terminal-build] sha256=${hash(dest)} file=${dest}`);
} finally {
  rmSync(temp, { recursive: true, force: true });
}
