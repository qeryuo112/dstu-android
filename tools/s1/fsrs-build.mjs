import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
const lock = JSON.parse(readFileSync(join(root, 'config/stages/s1-fsrs-source.json'), 'utf8'));
const buildArg = process.argv[2];
if (!buildArg) throw new Error('Usage: node tools/s1/fsrs-build.mjs <module-build-directory>');
const build = resolve(buildArg);
const allowed = join(root, 'engines/fsrs/build');
if (build !== allowed && !build.startsWith(`${allowed}/`)) {
  throw new Error('FSRS build output must be under engines/fsrs/build');
}

const cargoHome = resolve(process.env.DSTU_CARGO_HOME || join(root, lock.cargo.home));
const ndk = resolve(process.env.ANDROID_NDK_HOME || lock.android.ndkPath);
const clangDir = join(ndk, 'toolchains/llvm/prebuilt/linux-x86_64/bin');
const linker = join(clangDir, `aarch64-linux-android${lock.android.androidApi}-clang`);
const readelf = join(clangDir, 'llvm-readelf');
const nm = join(clangDir, 'llvm-nm');
const ar = join(clangDir, 'llvm-ar');
const targetDir = join(build, 'cargo-target');
const jniDir = join(build, 'generated/fsrs-jni', lock.android.abi);
const noticeDir = join(build, 'generated/fsrs-notices');
const crateSource = join(
  cargoHome,
  'registry/src/index.crates.io-1949cf8c6b5b557f',
  `${lock.engine.crate}-${lock.engine.version}`,
);
const crateCache = join(
  cargoHome,
  'registry/cache/index.crates.io-1949cf8c6b5b557f',
  `${lock.engine.crate}-${lock.engine.version}.crate`,
);

function hash(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function treeHash(dir) {
  const digest = createHash('sha256');
  const walk = (current) => readdirSync(current, { withFileTypes: true })
    .flatMap((entry) => {
      const path = join(current, entry.name);
      return entry.isDirectory() ? walk(path) : [path];
    })
    .sort();
  for (const file of walk(dir)) {
    const rel = relative(dir, file).split(sep).join('/');
    digest.update(rel);
    digest.update('\0');
    digest.update(readFileSync(file));
    digest.update('\0');
  }
  return digest.digest('hex');
}

function assertHash(file, expected, label) {
  if (!existsSync(file)) throw new Error(`${label} is missing: ${file}`);
  const actual = hash(file);
  if (actual !== expected) throw new Error(`${label} hash mismatch: expected ${expected}, got ${actual}`);
}

function run(command, args, cwd, env = {}) {
  console.error(`[fsrs-build] ${command} ${args.join(' ')}`);
  const result = spawnSync(command, args, {
    cwd,
    encoding: 'utf8',
    stdio: 'inherit',
    env: { ...process.env, ...env },
  });
  if (result.status !== 0) throw new Error(`${command} failed (${result.status ?? result.error})`);
}

function output(command, args, cwd = root) {
  const result = spawnSync(command, args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: process.env,
  });
  if (result.status !== 0) throw new Error(`${command} failed: ${result.stderr}`);
  return result.stdout;
}

function verifyInputs() {
  run('python3', [join(root, 'tools/s1/fsrs-sources-check.py'), root, cargoHome], root);
  const desktop = resolve(root, '../dstu-node');
  const head = output('git', ['-C', desktop, 'rev-parse', 'HEAD']).trim();
  const tree = output('git', ['-C', desktop, 'rev-parse', 'HEAD^{tree}']).trim();
  const dirty = output('git', ['-C', desktop, 'status', '--porcelain', '--untracked-files=no']).trim();
  if (head !== lock.desktopOracle.commit || tree !== lock.desktopOracle.tree || dirty.length !== 0) {
    throw new Error(`Frozen desktop oracle drifted: ${head} ${tree} dirty=${dirty.length !== 0}`);
  }
  for (const file of lock.desktopOracle.files) {
    assertHash(join(desktop, file.path), file.sha256, `desktop oracle ${file.path}`);
  }
  assertHash(join(root, 'engines/fsrs/Cargo.lock'), lock.cargo.lockSha256, 'Cargo.lock');
  assertHash(join(root, 'engines/fsrs/Cargo.toml'), lock.cargo.manifestSha256, 'Cargo.toml');
  assertHash(join(root, 'engines/fsrs/src/bridge.rs'), lock.cargo.bridgeSha256, 'bridge.rs');
  assertHash(join(root, 'engines/fsrs/src/lib.rs'), lock.cargo.libSourceSha256, 'lib.rs');
  assertHash(crateCache, lock.engine.checksum, 'fsrs crate cache');
  if (!existsSync(crateSource)) throw new Error(`fsrs crate source is missing: ${crateSource}`);
  const actualTree = treeHash(crateSource);
  if (actualTree !== lock.engine.crateSourceSha256) {
    throw new Error(`extracted fsrs source tree mismatch: expected ${lock.engine.crateSourceSha256}, got ${actualTree}`);
  }
  for (const [path, expected] of Object.entries(lock.engine.sourceFiles)) {
    assertHash(join(crateSource, path), expected, `extracted fsrs ${path}`);
  }
  assertHash(linker, lock.android.linkerSha256, 'NDK linker');
  assertHash(readelf, lock.android.readelfSha256, 'NDK readelf');
}

function verifyLockfile() {
  const text = readFileSync(join(root, 'engines/fsrs/Cargo.lock'), 'utf8');
  for (const [name, version] of Object.entries(lock.dependencyPins)) {
    const block = text.match(new RegExp(`\\[\\[package\\]\\]\\nname = "${name}"\\nversion = "${version}"`));
    if (block === null) throw new Error(`Cargo.lock does not pin ${name} ${version}`);
  }
}

function compile() {
  mkdirSync(jniDir, { recursive: true });
  const rustFlags = [
    '-C link-arg=-Wl,-z,max-page-size=16384',
    '-C link-arg=-Wl,--no-undefined',
  ].join(' ');
  run('cargo', ['build', '--release', '--locked', '--offline', '--target', lock.android.target], join(root, 'engines/fsrs'), {
    CARGO_HOME: cargoHome,
    CARGO_TARGET_DIR: targetDir,
    CARGO_NET_OFFLINE: 'true',
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER: linker,
    CC_aarch64_linux_android: linker,
    AR_aarch64_linux_android: ar,
    RUSTFLAGS: rustFlags,
  });
  const source = join(targetDir, lock.android.target, 'release', lock.outputs.library);
  const dest = join(jniDir, lock.outputs.library);
  copyFileSync(source, dest);
  verifyElf(dest);
  stageNotices(dest);
}

function verifyElf(file) {
  const header = output(readelf, ['-hW', file]);
  if (!header.includes('AArch64')) throw new Error('FSRS library is not AArch64');
  const notes = output(readelf, ['--notes', file]);
  if (!/NT_ANDROID_TYPE_IDENT\s*\n\s*description data: 1a 00 00 00\b/.test(notes)) {
    throw new Error('FSRS library does not declare Android API 26');
  }
  const loads = output(readelf, ['-lW', file]).split('\n').filter((line) => /^\s*LOAD\s/.test(line));
  if (loads.length === 0) throw new Error('FSRS library has no LOAD segments');
  for (const line of loads) {
    const fields = line.trim().split(/\s+/);
    const offset = Number.parseInt(fields[1], 16);
    const address = Number.parseInt(fields[2], 16);
    const align = Number.parseInt(fields.at(-1), 16);
    if (align < lock.android.pageSize || (address - offset) % lock.android.pageSize !== 0) {
      throw new Error(`Invalid 16 KiB ELF LOAD: ${line}`);
    }
  }
  const exports = output(nm, ['-D', '--defined-only', file])
    .split('\n')
    .map((line) => line.trim().split(/\s+/).at(-1))
    .filter((name) => name?.startsWith('Java_'))
    .sort();
  const expected = [...lock.outputs.exports].sort();
  if (JSON.stringify(exports) !== JSON.stringify(expected)) {
    throw new Error(`Unexpected JNI exports: ${exports.join(', ')}`);
  }
  console.error(
    `[fsrs-build] abi=${lock.android.abi} api=${lock.android.androidApi} ` +
    `exports=${exports.length} sha256=${hash(file)} file=${file}`,
  );
}

function stageNotices(library) {
  mkdirSync(noticeDir, { recursive: true });
  copyFileSync(join(crateSource, 'LICENSE'), join(noticeDir, 'FSRS-LICENSE'));
  copyFileSync(
    join(cargoHome, 'registry/src/index.crates.io-1949cf8c6b5b557f/jni-0.21.1/LICENSE-MIT'),
    join(noticeDir, 'JNI-LICENSE-MIT'),
  );
  copyFileSync(
    join(cargoHome, 'registry/src/index.crates.io-1949cf8c6b5b557f/jni-0.21.1/LICENSE-APACHE'),
    join(noticeDir, 'JNI-LICENSE-APACHE'),
  );
  const manifest = {
    engine: lock.engine,
    desktopOracle: lock.desktopOracle.commit,
    librarySha256: hash(library),
    cargoLockSha256: lock.cargo.lockSha256,
  };
  const manifestPath = join(noticeDir, 'source.json');
  copyFileSync(join(root, 'config/stages/s1-fsrs-source.json'), manifestPath);
  console.error(`[fsrs-build] notices=${noticeDir} sourceManifest=${manifest.engine.crate}@${manifest.engine.version}`);
}

verifyInputs();
verifyLockfile();
compile();
