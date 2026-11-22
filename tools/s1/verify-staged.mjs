import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { root } from './frozen-inputs.mjs';

const workspace = mkdtempSync(resolve(tmpdir(), 'dstu-s1-staged-'));
const temporary = resolve(workspace, 'dstu-android');
const args = process.argv.slice(2);
const exportApks = args.includes('--export-apks');
if (exportApks) args.splice(args.indexOf('--export-apks'), 1);
const currentVerification = args.includes('--verify-current');
if (currentVerification) args.splice(args.indexOf('--verify-current'), 1);
assert.ok(currentVerification || args.length > 0, 'Provide Gradle tasks or --verify-current for the isolated staged candidate');
const indexTree = execFileSync('git', ['write-tree'], { cwd: root, encoding: 'utf8' }).trim();
try {
  mkdirSync(temporary);
  const androidHome = resolve(workspace, 'android-home');
  mkdirSync(androidHome);
  const debugKey = resolve(homedir(), '.android/debug.keystore');
  if (existsSync(debugKey)) copyFileSync(debugKey, resolve(androidHome, 'debug.keystore'));
  execFileSync('git', ['checkout-index', '--all', `--prefix=${temporary}/`], { cwd: root });
  const cache = process.env.DSTU_GRADLE_HOME ?? resolve(root, '../build-tools/s1-gradle-home');
  const pdfOutput = resolve(process.env.DSTU_PDF_WORK ?? resolve(root, '../build-tools/s1-pdf-work'), 'out-android');
  const nativeOutput = existsSync(pdfOutput) ? ['--bind', pdfOutput, pdfOutput] : [];
  const cargoHome = resolve(root, '../build-tools/s1-cargo-home');
  const cargoCache = existsSync(resolve(temporary, 'engines/fsrs/Cargo.toml')) && existsSync(cargoHome)
    ? ['--bind', cargoHome, cargoHome] : [];
  const scriptWork = resolve(root, '../build-tools/s1-script-work');
  const scriptCache = existsSync(resolve(temporary, 'engines/quickjs/build.gradle.kts')) && existsSync(scriptWork)
    ? ['--bind', scriptWork, scriptWork] : [];
  const zstdWork = resolve(process.env.DSTU_ZSTD_WORK ?? resolve(root, '../build-tools/s1-zstd-work'));
  const zstdCache = existsSync(resolve(temporary, 'engines/zstd/build.gradle.kts')) && existsSync(zstdWork)
    ? ['--bind', zstdWork, zstdWork] : [];
  const history = currentVerification ? ['--ro-bind', resolve(root, '.git'), resolve(root, '.git')] : [];
  const verificationCommand = currentVerification
    ? [process.execPath, resolve(root, 'tools/s1/verify-current.mjs')]
    : [process.execPath, resolve(root, 'tools/s1/gradle.mjs'), ...args];
  const child = spawn('bwrap', [
    '--ro-bind', '/', '/', '--bind', '/tmp', '/tmp', '--dev', '/dev', '--proc', '/proc',
    '--bind', cache, cache, '--bind', temporary, root,
    ...history,
    ...nativeOutput,
    ...cargoCache,
    ...scriptCache,
    ...zstdCache,
    '--bind', androidHome, resolve(homedir(), '.android'),
    '--tmpfs', resolve(homedir(), '.local/share/kotlin/daemon'),
    '--tmpfs', resolve(homedir(), '.config/microsoft-edge'), '--chdir', root,
    '--', ...verificationCommand,
  ], {
    cwd: root, stdio: 'inherit', env: process.env,
  });
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  assert.equal(code, 0, 'staged candidate failed clean build');
  assert.equal(execFileSync('git', ['write-tree'], { cwd: root, encoding: 'utf8' }).trim(), indexTree, 'Git index changed during candidate verification');
  if (exportApks) {
    for (const entry of readdirSync(temporary, { recursive: true, withFileTypes: true })) {
      if (!entry.isFile()) continue;
      const source = resolve(entry.parentPath, entry.name);
      const relative = source.slice(temporary.length + 1);
      const apk = entry.name.endsWith('.apk') && relative.includes('/build/outputs/apk/');
      const hostProbe = relative === 'engines/zstd/build/host/crosscheck.zst';
      if (!apk && !hostProbe) continue;
      const destination = resolve(root, relative);
      mkdirSync(resolve(destination, '..'), { recursive: true });
      copyFileSync(source, destination);
      console.log(JSON.stringify({ event: apk ? 's1.staged-candidate.apk-exported' : 's1.staged-candidate.host-probe-exported', path: relative, indexTree }));
    }
  }
  console.log(JSON.stringify({ time: new Date().toISOString(), event: 's1.staged-candidate.verified', tasks: args,
    indexTree, clean: true }));
} finally {
  rmSync(workspace, { recursive: true, force: true });
}
