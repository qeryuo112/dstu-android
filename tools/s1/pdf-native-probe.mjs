import { existsSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { lock, ndk, out, root, run, source, work } from './pdf-build-lib.mjs';

const adb = process.env.ADB || join(process.env.HOME, 'Android/Sdk/platform-tools/adb');
const serial = process.env.ANDROID_SERIAL;
if (!serial) throw new Error('Set ANDROID_SERIAL to the target device');
const adbArgs = ['-s', serial];
const directory = `/data/local/tmp/dstu-s1-pdf-${process.pid}`;
const fixtureDir = join(root, 'engines/pdf/build/generated/pdf-fixtures');
const executable = join(work, `pdf-native-probe-${process.pid}`);
if (!existsSync(join(fixtureDir, 'corrupt-body.pdf'))) throw new Error('Run pdf-build.mjs first');
const compiler = join(ndk, 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++');
run(compiler, [
  '-std=c++17', '-O2', '-fPIE', '-pie', '-static-libstdc++', '-Wl,-z,max-page-size=16384',
  '-Wl,--no-undefined', `-I${source}`, join(root, 'tools/s1/pdf-native-probe.cpp'),
  join(out, 'obj/libpdfium.a'), '-llog', '-lz', '-lm', '-o', executable,
], root);
run(adb, [...adbArgs, 'shell', 'mkdir', '-p', directory]);
try {
  run(adb, [...adbArgs, 'push', executable, `${directory}/pdf-native-probe`]);
  run(adb, [...adbArgs, 'push', `${fixtureDir}/.`, `${directory}/`]);
  run(adb, [...adbArgs, 'shell', `${directory}/pdf-native-probe`, directory]);
} finally {
  try { run(adb, [...adbArgs, 'shell', 'rm', '-rf', directory]); }
  finally { rmSync(executable, { force: true }); }
}
console.error(`[pdf-probe] PDFium ${lock.pdfium.commit}; device ${serial}; test files cleaned`);
