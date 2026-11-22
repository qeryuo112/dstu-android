import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

export function loadTerminalLock(projectRoot = root) {
  return JSON.parse(readFileSync(resolve(projectRoot, 'config/stages/s1-terminal-source.json'), 'utf8'));
}

function filesUnder(directory, prefix = '') {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = prefix ? `${prefix}/${entry.name}` : entry.name;
    return entry.isDirectory() ? filesUnder(resolve(directory, entry.name), path) : [path];
  });
}

export function verifyTerminalVendor(projectRoot = root, lock = loadTerminalLock(projectRoot)) {
  const vendor = resolve(projectRoot, lock.vendorRoot);
  const recorded = [...Object.keys(lock.files), 'GPL-3.0.txt'].sort();
  const actual = filesUnder(vendor).sort();
  if (JSON.stringify(actual) !== JSON.stringify(recorded)) {
    throw new Error(`Terminal vendor file set mismatch: ${actual.join(', ')}`);
  }
  for (const [path, expected] of Object.entries(lock.files)) {
    const digest = createHash('sha256').update(readFileSync(resolve(vendor, path))).digest('hex');
    if (digest !== expected) throw new Error(`Terminal vendor digest mismatch: ${path}`);
  }
  for (const [path, expected] of Object.entries(lock.localFiles ?? {})) {
    const digest = createHash('sha256').update(readFileSync(resolve(projectRoot, path))).digest('hex');
    if (digest !== expected) throw new Error(`Terminal local source digest mismatch: ${path}`);
  }
  const supplemental = resolve(projectRoot, lock.supplementalLicense.path);
  const digest = createHash('sha256').update(readFileSync(supplemental)).digest('hex');
  if (digest !== lock.supplementalLicense.sha256) {
    throw new Error('Terminal supplemental license digest mismatch');
  }
  const upstream = lock.upstreamRoot && resolve(projectRoot, lock.upstreamRoot);
  let upstreamVerified = false;
  if (upstream && existsSync(upstream)) {
    verifyUpstream(upstream, lock, vendor);
    upstreamVerified = true;
  }
  return {
    files: Object.keys(lock.files).length,
    localFiles: Object.keys(lock.localFiles ?? {}).length,
    vendor: relative(projectRoot, vendor),
    upstreamVerified,
  };
}

function git(directory, args) {
  const result = spawnSync('git', ['-C', directory, ...args], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`git ${args.join(' ')} failed in ${directory}`);
  return result.stdout.trim();
}

function verifyUpstream(upstream, lock, vendor) {
  if (git(upstream, ['rev-parse', 'HEAD']) !== lock.terminalCommit) {
    throw new Error('Frozen Operit terminal commit changed');
  }
  if (git(upstream, ['rev-parse', 'HEAD^{tree}']) !== lock.terminalTree) {
    throw new Error('Frozen Operit terminal tree changed');
  }
  for (const path of Object.keys(lock.files)) {
    const source = resolve(upstream, path);
    if (!existsSync(source)) throw new Error(`Frozen upstream file missing: ${path}`);
    if (!readFileSync(source).equals(readFileSync(resolve(vendor, path)))) {
      throw new Error(`Vendor differs from frozen upstream bytes: ${path}`);
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = verifyTerminalVendor();
  console.log(
    `[terminal-source] verified ${result.files} locked files and ${result.localFiles} local hashes under ${result.vendor}` +
      (result.upstreamVerified ? ' and frozen upstream commit/tree/bytes' : ''),
  );
}
