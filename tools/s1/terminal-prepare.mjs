import { copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { tmpdir } from 'node:os';
import { loadTerminalLock, verifyTerminalVendor } from './terminal-source.mjs';

const root = resolve(import.meta.dirname, '../..');
const lock = loadTerminalLock(root);
const output = process.argv[2] && resolve(process.argv[2]);
const notices = process.argv[3] && resolve(process.argv[3]);
const buildRoot = resolve(root, 'engines/terminal/build');
const tempRoot = resolve(tmpdir());
const trackedOutput = resolve(root, 'engines/terminal/src/operit/kotlin');
const generateTracked = process.argv.includes('--generate');

function allowed(path) {
  if (!path) return false;
  return path === trackedOutput || path.startsWith(buildRoot + sep) || path.startsWith(join(tempRoot, 'dstu-terminal-'));
}

if (!allowed(output) || !allowed(notices)) {
  throw new Error('Terminal prepare output must be under engines/terminal/build or an isolated dstu-terminal temp directory');
}

verifyTerminalVendor(root, lock);
if (output !== trackedOutput || generateTracked) rmSync(output, { recursive: true, force: true });
rmSync(notices, { recursive: true, force: true });
mkdirSync(output, { recursive: true });
mkdirSync(notices, { recursive: true });

function privacyPatch(text, path) {
  if (!path.endsWith('/AnsiTerminalEmulator.kt')) return text;
  const lines = text.split('\n');
  const removals = lines.filter(line => /\bLog\.[dw]\(/u.test(line)).length;
  if (removals !== lock.patches[0].expectedRemovals) {
    throw new Error(`Unexpected upstream log sites: ${removals}`);
  }
  return lines
    .filter(line => !line.includes('import android.util.Log'))
    .map(line => line.replace(/Log\.[dw]\(.*\)/u, 'Unit'))
    .join('\n');
}

for (const [path, expected] of Object.entries(lock.files)) {
  if (!path.startsWith('src/main/java/')) continue;
  const source = readFileSync(resolve(root, lock.vendorRoot, path), 'utf8');
  const destination = resolve(output, relative('src/main/java', path));
  mkdirSync(dirname(destination), { recursive: true });
  const derived = privacyPatch(source, path);
  if (output === trackedOutput && !generateTracked) {
    if (readFileSync(destination, 'utf8') !== derived) throw new Error(`Terminal derived source differs: ${path}`);
  } else writeFileSync(destination, derived);
}

const actualFiles = readdirSync(output, { recursive: true, withFileTypes: true }).filter(entry => entry.isFile())
  .map(entry => relative(output, join(entry.parentPath, entry.name))).sort();
const expectedFiles = Object.keys(lock.files).filter(path => path.startsWith('src/main/java/'))
  .map(path => relative('src/main/java', path)).sort();
if (JSON.stringify(actualFiles) !== JSON.stringify(expectedFiles)) throw new Error('Terminal derived source file set differs');

const noticeRoot = join(notices, 'licenses/operit-terminal');
mkdirSync(noticeRoot, { recursive: true });
copyFileSync(resolve(root, lock.vendorRoot, 'LICENSE'), join(noticeRoot, 'LICENSE'));
copyFileSync(resolve(root, lock.supplementalLicense.path), join(noticeRoot, 'GPL-3.0.txt'));
writeFileSync(
  join(noticeRoot, 'NOTICE.txt'),
  `Operit Terminal Core ${lock.terminalCommit}\n` +
    `Copyright (C) 2023-2024 Operit AI project team\n` +
    `LGPL-3.0. Upstream: ${lock.repository} at ${lock.terminalCommit}.\n` +
    `Only the minimum PTY and ANSI sources are included; see source.json.\n`,
);
copyFileSync(resolve(root, 'config/stages/s1-terminal-source.json'), join(noticeRoot, 'source.json'));
for (const path of Object.keys(lock.files)) {
  const destination = join(noticeRoot, 'source', path);
  mkdirSync(dirname(destination), { recursive: true });
  copyFileSync(resolve(root, lock.vendorRoot, path), destination);
}
for (const path of [
  'engines/terminal/src/main/cpp/pty_owner.c',
  'tools/s1/terminal-prepare.mjs',
  'tools/s1/terminal-build.mjs',
]) {
  const destination = join(noticeRoot, 'local', path);
  mkdirSync(dirname(destination), { recursive: true });
  copyFileSync(resolve(root, path), destination);
}

console.log(`[terminal-prepare] generated ${Object.keys(lock.files).length} locked sources with privacy patch`);
