import { mkdirSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { assertGit, assertHash, assertSourceRepositories, copyChecked, depot, hash, lock, ndk, out, output, root, run, source, work } from './pdf-build-lib.mjs';

if (!process.argv[2]) throw new Error('Usage: node tools/s1/pdf-build.mjs <module-build-directory>');
const build = resolve(process.argv[2]);
const allowed = join(root, 'engines/pdf/build');
if (build !== allowed && !build.startsWith(`${allowed}/`)) throw new Error('Output must be under engines/pdf/build');
const clang = join(ndk, 'toolchains/llvm/prebuilt/linux-x86_64/bin');
const gn = join(source, 'buildtools/linux64/gn');
const ninja = join(source, 'third_party/ninja/ninja');

function verifyInputs() {
  assertGit(depot, lock.depotTools.commit, lock.depotTools.tree);
  assertHash(join(work, '.gclient'), lock.gclientConfigSha256);
  assertGit(source, lock.pdfium.commit, lock.pdfium.tree);
  assertHash(join(source, 'DEPS'), lock.pdfium.depsSha256);
  assertHash(join(source, 'LICENSE'), lock.pdfium.licenseSha256);
  for (const [path, revision] of Object.entries(lock.dependencies)) assertGit(join(source, path), revision);
  assertGit(join(source, 'third_party/simdutf'), lock.simdutf.commit);
  assertSourceRepositories();
  const tools = [
    [gn, lock.toolHashes.gn], [ninja, lock.toolHashes.ninja],
    [join(source, 'third_party/llvm-build/Release+Asserts/bin/clang'), lock.toolHashes.chromiumClang],
    [join(clang, 'clang'), lock.toolHashes.ndkClang], [join(ndk, 'NOTICE'), lock.toolHashes.ndkNotice],
    [join(clang, '../sysroot/NOTICE'), lock.toolHashes.ndkSysrootNotice],
  ];
  for (const [file, expected] of tools) assertHash(file, expected);
}

function compile() {
  mkdirSync(out, { recursive: true });
  const args = [
    'target_os="android"', 'target_cpu="arm64"', 'is_debug=false', 'symbol_level=0',
    'pdf_is_standalone=true', 'pdf_use_partition_alloc=false', 'pdf_enable_v8=false',
    'pdf_enable_xfa=false', 'pdf_use_skia=false', 'pdf_is_complete_lib=true',
    'is_component_build=false', 'use_custom_libcxx=false', 'use_clang_modules=false',
    'treat_warnings_as_errors=false', 'clang_use_chrome_plugins=false',
    `android_ndk_root="${ndk}"`, 'default_min_sdk_version=26',
  ].join(' ');
  run(gn, ['gen', out, `--root=${source}`, `--args=${args}`], source);
  run(ninja, ['-C', out, 'pdfium', '-j4', '--quiet'], source);
  const commands = output(ninja, ['-C', out, '-t', 'commands', 'pdfium'], source).split('\n');
  const targetCompiles = commands.filter((line) => /(?:clang\+\+|\/clang) .* -c /.test(line) && line.includes('-DANDROID'));
  if (!targetCompiles.length || targetCompiles.some((line) => !line.includes('--target=aarch64-linux-android26'))) {
    throw new Error('PDFium Android compilation was not uniformly targeted at arm64 API 26');
  }
  const dest = join(build, 'generated/pdf-jni/arm64-v8a/libdstu_pdf.so');
  mkdirSync(join(build, 'generated/pdf-jni/arm64-v8a'), { recursive: true });
  run(join(clang, 'aarch64-linux-android26-clang++'), [
    '-std=c++17', '-O2', '-fPIC', '-fvisibility=hidden', '-shared', '-static-libstdc++',
    '-Wl,-z,max-page-size=16384', '-Wl,--no-undefined', '-Wl,--exclude-libs,ALL', `-I${source}`,
    join(root, 'engines/pdf/src/main/cpp/pdf_documents.cpp'),
    join(root, 'engines/pdf/src/main/cpp/pdf_pages.cpp'), join(out, 'obj/libpdfium.a'),
    '-landroid', '-ljnigraphics', '-llog', '-lz', '-lm', '-o', dest,
  ], root);
  const elf = output(join(clang, 'llvm-readelf'), ['-lW', dest]);
  const header = output(join(clang, 'llvm-readelf'), ['-hW', dest]);
  const notes = output(join(clang, 'llvm-readelf'), ['--notes', dest]);
  const exports = output(join(clang, 'llvm-nm'), ['-D', '--defined-only', dest]).split('\n');
  if (exports.length !== 7 || exports.some((line) => !line.includes('Java_com_deepstudent_engines_pdf_PdfNative_'))) {
    throw new Error('Unexpected dynamic exports in PDF JNI library');
  }
  const loads = elf.split('\n').filter((line) => /^\s*LOAD\s/.test(line));
  if (!header.includes('AArch64') || !/NT_ANDROID_TYPE_IDENT\s*\n\s*description data: 1a 00 00 00\b/.test(notes)) {
    throw new Error('Wrong ABI or Android min API note');
  }
  for (const line of loads) {
    const fields = line.trim().split(/\s+/);
    const offset = Number.parseInt(fields[1], 16), address = Number.parseInt(fields[2], 16);
    const align = Number.parseInt(fields.at(-1), 16);
    if (align < 16384 || (address - offset) % 16384 !== 0) throw new Error(`Invalid ELF LOAD: ${line}`);
  }
  if (!loads.length) throw new Error('No ELF LOAD segments');
  console.error(`[pdf-build] source=${lock.pdfium.commit} tree=${lock.pdfium.tree} ndk=${lock.ndk} lockSha256=${hash(join(root, 'config/stages/s1-pdf-source.json'))}`);
  console.error(`[pdf-build] abi=arm64-v8a android-ident-api=26 targetCompiles=${targetCompiles.length} exports=${exports.length} loadAlignment>=16384 sha256=${hash(dest)} file=${dest}`);
}

function stageMaterials() {
  for (const [name, digest] of Object.entries(lock.fixtures)) {
    copyChecked(join(source, 'testing/resources', name), join(build, 'generated/pdf-fixtures', name), digest);
  }
  const desktop = resolve(root, '../dstu-node');
  assertGit(desktop, lock.desktopFixture.desktopCommit, '3bee0f058851b9eb113bdc036d9f60433ed29944');
  copyChecked(join(desktop, lock.desktopFixture.path),
    join(build, 'generated/pdf-fixtures', basename(lock.desktopFixture.path)), lock.desktopFixture.sha256);
  for (const name of lock.licenseFiles) {
    copyChecked(join(source, name), join(build, 'generated/pdf-notices/pdfium', name));
  }
  copyChecked(join(ndk, 'NOTICE'), join(build, 'generated/pdf-notices/pdfium/NDK-NOTICE'), lock.toolHashes.ndkNotice);
  copyChecked(join(clang, '../sysroot/NOTICE'), join(build, 'generated/pdf-notices/pdfium/NDK-SYSROOT-NOTICE'), lock.toolHashes.ndkSysrootNotice);
  copyChecked(join(root, 'config/stages/s1-pdf-source.json'), join(build, 'generated/pdf-notices/pdfium/source.json'));
}

verifyInputs();
compile();
stageMaterials();
