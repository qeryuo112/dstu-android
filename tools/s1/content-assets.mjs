import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const desktop = resolve(root, '../dstu-node');
const modules = join(desktop, 'node_modules');
const lock = JSON.parse(readFileSync(join(root, 'config/stages/s1-content-sources.json')));
const sha = data => createHash('sha256').update(data).digest('hex');
const read = path => readFileSync(join(modules, path));

function sources() {
  const map = JSON.parse(read('mermaid/dist/mermaid.js.map'));
  const names = new Set(['mermaid', 'katex']);
  for (const source of map.sources) {
    if (!source.includes('node_modules/')) continue;
    const parts = source.split('node_modules/').at(-1).split('/');
    names.add(parts.slice(0, parts[0].startsWith('@') ? 2 : 1).join('/'));
  }
  return [...names].sort();
}

function embeddedPackages() {
  const map = JSON.parse(read('mermaid/dist/mermaid.js.map'));
  const packages = new Set();
  for (const source of map.sources) {
    const match = source.match(/node_modules\/\.pnpm\/([^/]+)\/node_modules\/((?:@[^/]+\/)?[^/]+)\//);
    if (match) packages.add(`${match[2]}|${match[1].replace(/_.*/, '').slice(match[1].replace(/_.*/, '').lastIndexOf('@') + 1)}`);
  }
  return [...packages].sort();
}

function licenseFiles() {
  return sources().flatMap(name => {
    const dir = join(modules, name);
    const files = readdirSync(dir).filter(x => /^(licen[sc]e|copying|notice)(\.|$)/i.test(x)).sort();
    if (!files.length) throw Error(`Missing license: ${name}`);
    return files.map(file => `${name}/${file}`);
  });
}

function inputs() {
  const fonts = readdirSync(join(modules, 'katex/dist/fonts')).sort()
    .map(file => `katex/dist/fonts/${file}`);
  return {
    scripts: ['katex/dist/katex.min.js', 'katex/dist/katex.min.css', 'mermaid/dist/mermaid.min.js'],
    fonts,
    licenses: licenseFiles(),
    sourceMap: ['mermaid/dist/mermaid.js.map'],
  };
}

function digest(paths) {
  const hash = createHash('sha256');
  for (const path of paths) hash.update(`${path}\0${sha(read(path))}\n`);
  return hash.digest('hex');
}

function verify(groups) {
  const revision = execFileSync('git', ['-C', desktop, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  if (revision !== lock.desktopRevision) throw Error('Desktop checkout is not at the frozen revision');
  const frozen = JSON.parse(execFileSync('git', ['-C', desktop, 'show', `${lock.desktopRevision}:package-lock.json`]));
  const upstream = JSON.parse(readFileSync(join(root, 'config/source-lock.json')));
  for (const item of lock.packages) {
    const entry = frozen.packages[`node_modules/${item.name}`];
    const source = upstream.packages.find(x => x.id === item.name);
    if (entry?.version !== item.version || entry.integrity !== item.integrity ||
        source?.version !== item.version || source.hash.value !== item.integrity) throw Error(`Package lock mismatch: ${item.name}`);
    const installed = JSON.parse(read(`${item.name}/package.json`));
    if (installed.version !== item.version) throw Error(`Installed version mismatch: ${item.name}`);
    verifyTarball(item, groups);
  }
  const embedded = embeddedPackages();
  if (embedded.length !== lock.embeddedComponents.count || sha(`${embedded.join('\n')}\n`) !== lock.embeddedComponents.sha256) {
    throw Error('Embedded Mermaid component inventory drift');
  }
  for (const [group, paths] of Object.entries(groups)) {
    if (digest(paths) !== lock.groups[group].sha256 || paths.length !== lock.groups[group].count) {
      throw Error(`Content source drift: ${group}`);
    }
  }
}

function tarballFile(integrity) {
  const cache = process.env.npm_config_cache || join(process.env.HOME, '.npm');
  const hex = Buffer.from(integrity.slice('sha512-'.length), 'base64').toString('hex');
  const file = join(cache, '_cacache/content-v2/sha512', hex.slice(0, 2), hex.slice(2, 4), hex.slice(4));
  const tarball = readFileSync(file);
  const actual = `sha512-${createHash('sha512').update(tarball).digest('base64')}`;
  if (actual !== integrity) throw Error(`Tarball integrity mismatch: ${file}`);
  return file;
}

function verifyTarball(item, groups) {
  const file = tarballFile(item.integrity);
  const paths = [...groups.scripts, ...groups.fonts, ...groups.sourceMap, ...groups.licenses]
    .filter(path => path.startsWith(`${item.name}/`));
  for (const path of paths) {
    const entry = `package/${path.slice(item.name.length + 1)}`;
    const source = execFileSync('tar', ['-xOzf', file, entry], { maxBuffer: 32 * 1024 * 1024 });
    if (sha(source) !== sha(read(path))) throw Error(`Installed bytes differ from locked tarball: ${path}`);
  }
}

function overrideLicenses() {
  return lock.embeddedLicenseOverrides.map(item => {
    const file = tarballFile(item.integrity);
    const data = execFileSync('tar', ['-xOzf', file, 'package/LICENSE'], { maxBuffer: 1024 * 1024 });
    if (sha(data) !== item.licenseSha256) throw Error(`License drift: ${item.name}@${item.version}`);
    return [`licenses/embedded/${item.name}@${item.version}/LICENSE`, data];
  });
}

export function inspectContentInputs() {
  return Object.fromEntries(Object.entries(inputs()).map(([name, paths]) => [name, { count: paths.length, sha256: digest(paths) }]));
}

export function copyContentInputs(output) {
  const groups = inputs();
  verify(groups);
  const extraLicenses = overrideLicenses();
  const target = resolve(output, 'content/vendor');
  if (existsSync(target)) rmSync(target, { recursive: true });
  const copies = [
    ...groups.scripts.map(path => [path, path.replace('katex/dist/', '').replace('mermaid/dist/', '')]),
    ...groups.fonts.map(path => [path, path.replace('katex/dist/', '')]),
    ...groups.licenses.map(path => [path, `licenses/${path}`]),
  ];
  for (const [source, destination] of copies) {
    const file = join(target, destination);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, read(source));
  }
  for (const [destination, data] of extraLicenses) {
    const file = join(target, destination);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, data);
  }
  return copies.length + extraLicenses.length;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv[2] === '--inspect') console.log(JSON.stringify(inspectContentInputs(), null, 2));
  else if (process.argv[2]) console.log(`Copied ${copyContentInputs(process.argv[2])} verified content assets`);
  else throw Error('Expected output directory');
}
