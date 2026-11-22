import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import { artifact, desktop, difference, git, log, main, operit, readJson, root, sha256 } from './lib.mjs';

const unit = 'S0.14';
const file = 'config/runtime-assets.json';
const noticesFile = 'config/runtime-notices.json';
const planFile = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const lockFile = 'config/source-lock.json';
const adoptionFile = 'config/operit-adoption.json';
const terminalRoot = resolve(operit, 'terminal');
const repoRoots = { operit, terminal: terminalRoot, desktop };
const rootfsAsset = 'terminal/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz';
const rootfsPrefix = 'ubuntu-noble-aarch64';
const desktopLicenseFile = 'LICENSE';
const desktopAcknowledgmentsFile = 'ACKNOWLEDGMENTS.md';
const desktopNoticesFile = 'legal/THIRD_PARTY_NOTICES.txt';
const desktopFrontendPackagesFile = 'legal/frontend-bundle-packages.json';
const fontGenerationCommit = '145bb3879164c58d1cd5ff39daa94a3daa9fe874';

const sensitiveExtensions = new Set(['jks', 'keystore', 'p12', 'pfx', 'bks', 'p8', 'key', 'pem']);
const sensitivePathReason = path => {
  const name = basename(path).toLowerCase();
  const extension = name.includes('.') ? name.split('.').pop() : '';
  if (sensitiveExtensions.has(extension)) return `private-key-container:${extension}`;
  if (/^id_(rsa|dsa|ecdsa|ed25519)$/.test(name) || name.includes('private-key')) return 'private-key-material';
  return null;
};
const isSensitivePath = path => sensitivePathReason(path) !== null;

const readRepo = (repo, path) => {
  assert.equal(isSensitivePath(path), false, `${repo}:${path}: refused to read private key material`);
  return readFileSync(resolve(repoRoots[repo], path));
};
const hasRepo = (repo, path) => existsSync(resolve(repoRoots[repo], path));
const sha256Of = (repo, path) => sha256(readRepo(repo, path));
const bytesOf = (repo, path) => statSync(resolve(repoRoots[repo], path)).size;
const gitZ = (cwd, ...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).split('\0').filter(Boolean);
const lineOf = (text, needle) => {
  const lines = text.split('\n');
  const index = lines.findIndex(line => line.includes(needle));
  assert.ok(index >= 0, `missing line containing ${needle}`);
  return index + 1;
};

function magic(buffer) {
  if (buffer.length < 4) return 'short';
  const hex = buffer.subarray(0, 8).toString('hex');
  if (hex.startsWith('7f454c46')) return 'elf';
  if (hex.startsWith('504b0304') || hex.startsWith('504b0506')) return 'zip';
  if (hex.startsWith('fd377a585a00')) return 'xz';
  if (hex.startsWith('774f4632')) return 'woff2';
  if (hex.startsWith('00010000') || hex.startsWith('4f54544f') || hex.startsWith('74727565')) return 'truetype';
  if (hex.startsWith('3082')) return 'der';
  if (hex.startsWith('89504e47')) return 'png';
  if (hex.startsWith('ffd8ff')) return 'jpeg';
  if (hex.startsWith('47494638')) return 'gif';
  if (hex.startsWith('52494646')) return 'webp';
  if (hex.startsWith('1f8b')) return 'gzip';
  return 'other';
}

function readU16(buffer, offset, little = true) {
  return little ? buffer.readUInt16LE(offset) : buffer.readUInt16BE(offset);
}
function readU32(buffer, offset, little = true) {
  return little ? buffer.readUInt32LE(offset) : buffer.readUInt32BE(offset);
}
function readU64(buffer, offset, little = true) {
  const value = little ? buffer.readBigUInt64LE(offset) : buffer.readBigUInt64BE(offset);
  return Number(value);
}

function parseElf(buffer) {
  if (buffer.length < 64 || magic(buffer) !== 'elf') return null;
  const elfClass = buffer[4];
  const little = buffer[5] === 1;
  if (elfClass !== 2) return { class: 'ELF32', little };
  const type = readU16(buffer, 16, little);
  const machine = readU16(buffer, 18, little);
  const phoff = readU64(buffer, 32, little);
  const phentsize = readU16(buffer, 54, little);
  const phnum = readU16(buffer, 56, little);
  assert.ok(phoff + phentsize * phnum <= buffer.length, 'ELF program headers outside file');
  const loads = [];
  const notes = [];
  const dynamics = [];
  for (let index = 0; index < phnum; index += 1) {
    const offset = phoff + index * phentsize;
    const pType = readU32(buffer, offset, little);
    const pOffset = readU64(buffer, offset + 8, little);
    const pVaddr = readU64(buffer, offset + 16, little);
    const pFilesz = readU64(buffer, offset + 32, little);
    const pAlign = readU64(buffer, offset + 48, little);
    if (pType === 1) loads.push({ offset: pOffset, vaddr: pVaddr, filesz: pFilesz, align: pAlign });
    if (pType === 2) dynamics.push({ offset: pOffset, filesz: pFilesz });
    if (pType === 4) notes.push({ offset: pOffset, filesz: pFilesz });
  }
  const loadAligns = [...new Set(loads.map(load => load.align))].sort((a, b) => a - b);
  const minLoadAlign = loadAligns[0] ?? null;
  let android = null;
  for (const note of notes) {
    let cursor = note.offset;
    const end = note.offset + note.filesz;
    while (cursor + 12 <= end) {
      const namesz = readU32(buffer, cursor, little);
      const descsz = readU32(buffer, cursor + 4, little);
      const nType = readU32(buffer, cursor + 8, little);
      const nameStart = cursor + 12;
      const name = buffer.subarray(nameStart, nameStart + namesz).toString('latin1').replace(/\0.*$/, '');
      const descStart = nameStart + Math.ceil(namesz / 4) * 4;
      if (name === 'Android' && nType === 1 && descsz >= 4) {
        android = {
          api: readU32(buffer, descStart, little),
          ndk: buffer.subarray(descStart + 4, descStart + 68).toString('latin1').replace(/\0.*$/, ''),
          ndkBuild: buffer.subarray(descStart + 68, descStart + 132).toString('latin1').replace(/\0.*$/, ''),
        };
      }
      cursor = descStart + Math.ceil(descsz / 4) * 4;
    }
  }
  const needed = [];
  for (const dynamic of dynamics) {
    const strtabEntry = { vaddr: null };
    const entries = [];
    for (let cursor = dynamic.offset; cursor + 16 <= dynamic.offset + dynamic.filesz; cursor += 16) {
      const tag = readU64(buffer, cursor, little);
      const value = readU64(buffer, cursor + 8, little);
      if (tag === 0) break;
      entries.push({ tag, value });
      if (tag === 5) strtabEntry.vaddr = value;
    }
    if (strtabEntry.vaddr === null) continue;
    const load = loads.find(item => strtabEntry.vaddr >= item.vaddr && strtabEntry.vaddr < item.vaddr + item.filesz);
    if (!load) continue;
    const strtabOffset = strtabEntry.vaddr - load.vaddr + load.offset;
    for (const entry of entries) {
      if (entry.tag !== 1) continue;
      const start = strtabOffset + entry.value;
      const stop = buffer.indexOf(0, start);
      needed.push(buffer.subarray(start, stop === -1 ? start + 128 : stop).toString('latin1'));
    }
  }
  const typeName = { 1: 'REL', 2: 'EXEC', 3: 'DYN', 4: 'CORE' }[type] ?? `TYPE_${type}`;
  return {
    class: 'ELF64',
    little,
    type: typeName,
    machine: machine === 183 ? 'AArch64' : `MACHINE_${machine}`,
    loadAligns,
    minLoadAlign,
    page16k: minLoadAlign !== null && minLoadAlign >= 16384,
    android,
    needed,
    loads: loads.length,
  };
}

function parseZipEntries(buffer) {
  assert.equal(magic(buffer), 'zip', 'zip magic');
  let eocd = -1;
  const floor = Math.max(0, buffer.length - 65557);
  for (let cursor = buffer.length - 22; cursor >= floor; cursor -= 1) {
    if (buffer.readUInt32LE(cursor) === 0x06054b50) {
      eocd = cursor;
      break;
    }
  }
  assert.ok(eocd >= 0, 'zip end of central directory');
  const count = buffer.readUInt16LE(eocd + 10);
  const centralOffset = buffer.readUInt32LE(eocd + 16);
  const entries = [];
  let cursor = centralOffset;
  for (let index = 0; index < count; index += 1) {
    assert.equal(buffer.readUInt32LE(cursor), 0x02014b50, 'zip central header');
    const method = buffer.readUInt16LE(cursor + 10);
    const compressedSize = buffer.readUInt32LE(cursor + 20);
    const size = buffer.readUInt32LE(cursor + 24);
    const nameLength = buffer.readUInt16LE(cursor + 28);
    const extraLength = buffer.readUInt16LE(cursor + 30);
    const commentLength = buffer.readUInt16LE(cursor + 32);
    const localOffset = buffer.readUInt32LE(cursor + 42);
    const name = buffer.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    entries.push({ name, method, compressedSize, size, localOffset });
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

function readZipEntry(buffer, entry) {
  const local = entry.localOffset;
  assert.equal(buffer.readUInt32LE(local), 0x04034b50, `zip local header ${entry.name}`);
  const nameLength = buffer.readUInt16LE(local + 26);
  const extraLength = buffer.readUInt16LE(local + 28);
  const start = local + 30 + nameLength + extraLength;
  const raw = buffer.subarray(start, start + entry.compressedSize);
  if (entry.method === 0) return raw;
  if (entry.method === 8) return inflateRawSync(raw);
  throw new Error(`unsupported zip method ${entry.method} for ${entry.name}`);
}

function zipNestedElf(buffer) {
  const entries = parseZipEntries(buffer);
  return entries
    .filter(entry => entry.name.startsWith('lib/arm64-v8a/') && entry.name.endsWith('.so'))
    .map(entry => {
      const bytes = readZipEntry(buffer, entry);
      return { name: entry.name, bytes: bytes.length, elf: parseElf(bytes) };
    });
}

function exec(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 64 << 20, ...options });
  return { status: result.status, stdout: result.stdout ?? '', stderr: result.stderr ?? '', error: result.error };
}

function requireTool(name) {
  const result = exec('which', [name]);
  assert.equal(result.status, 0, `required tool not found: ${name}`);
  return result.stdout.trim();
}

function tarFacts(archive) {
  const verbose = exec('tar', ['-tvJf', archive]);
  assert.equal(verbose.status, 0, `tar -tvJf failed: ${verbose.stderr.slice(0, 400)}`);
  const rows = verbose.stdout.split('\n').filter(Boolean).map(line => {
    const match = line.match(/^([A-Za-z-])([rwxStTs-]{9})\s+\S+\s+\d+\s+\S+\s+\S+\s+(.*)$/);
    if (match === null) return { type: '?', raw: line, name: line };
    const type = match[1];
    let name = match[3];
    let link = null;
    if (type === 'l' && name.includes(' -> ')) {
      const parts = name.split(' -> ');
      name = parts[0];
      link = parts[1];
    }
    return { type, name, link };
  });
  const names = rows.map(row => row.name);
  const traversal = names.filter(name => name.startsWith('/') || name.split('/').includes('..'));
  const counts = rows.reduce((total, row) => {
    total[row.type] = (total[row.type] ?? 0) + 1;
    return total;
  }, {});
  return { entries: rows.length, counts, traversal, symlinks: rows.filter(row => row.type === 'l'), rows };
}

function parseDpkgStatus(text) {
  return text.split(/\n\n+/).map(block => {
    const fields = {};
    let last = null;
    for (const line of block.split('\n')) {
      const match = line.match(/^([A-Za-z0-9-]+):\s?(.*)$/);
      if (match !== null) {
        last = match[1];
        fields[last] = match[2].trim();
      } else if (last !== null && line.startsWith(' ')) {
        fields[last] += `\n${line.slice(1)}`;
      }
    }
    return fields;
  }).filter(fields => fields.Package);
}

const licenseTokenPattern = /(?<![A-Za-z0-9])(?:AGPL|GPL|LGPL|GFDL)-[0-9]+(?:\.[0-9]+)?\+?|(?<![A-Za-z0-9])(?:Apache-2\.0|MPL-[0-9.]+|CC0-1\.0|BSD-[0-9]-Clause|Expat|ISC|MIT|Unlicense|public-domain)(?![A-Za-z0-9])/g;

function licenseFamilies(text) {
  const values = [...new Set([...text.matchAll(/^License:[ \t]*(.*)$/gm)].map(match => match[1].trim()).filter(Boolean))].sort();
  const declared = values.filter(value => /^[A-Za-z0-9][A-Za-z0-9.+_-]*$/.test(value));
  const declaredTokens = [...new Set(declared.flatMap(name => name.match(licenseTokenPattern) ?? []))].sort();
  const proseTokens = [...new Set(text.match(licenseTokenPattern) ?? [])].sort();
  const source = declaredTokens.length > 0
    ? 'license-field'
    : declared.length > 0
      ? 'declared-unmapped'
      : proseTokens.length > 0 ? 'prose' : 'none';
  const families = declaredTokens.length > 0 ? declaredTokens : proseTokens;
  return { families, source, declared };
}

function rootfsFacts() {
  const archive = resolve(operit, rootfsAsset);
  const bytes = readFileSync(archive);
  const facts = tarFacts(archive);
  const temp = mkdtempSync(join(tmpdir(), 'dstu-s014-rootfs-'));
  try {
    const metaMembers = [
      `${rootfsPrefix}/var/lib/dpkg/status`,
      `${rootfsPrefix}/etc/lsb-release`,
      `${rootfsPrefix}/usr/lib/os-release`,
      `${rootfsPrefix}/etc/apt/sources.list`,
    ];
    const sampleMembers = [
      `${rootfsPrefix}/usr/bin/bash`,
      `${rootfsPrefix}/usr/bin/dpkg`,
      `${rootfsPrefix}/usr/bin/apt`,
      `${rootfsPrefix}/usr/bin/python3.12`,
      `${rootfsPrefix}/usr/bin/ls`,
      `${rootfsPrefix}/usr/lib/aarch64-linux-gnu/libc.so.6`,
    ];
    const extract = exec('tar', ['-xJf', archive, '-C', temp, '--wildcards', ...metaMembers, ...sampleMembers]);
    assert.ok(['0', '2'].includes(String(extract.status)), `rootfs selective extract failed: ${extract.stderr.slice(0, 400)}`);
    for (const member of metaMembers) assert.ok(existsSync(join(temp, member)), `rootfs member missing: ${member}`);
    const statusText = readFileSync(join(temp, metaMembers[0]), 'utf8');
    const lsb = readFileSync(join(temp, metaMembers[1]), 'utf8');
    const osRelease = readFileSync(join(temp, metaMembers[2]), 'utf8');
    const sources = readFileSync(join(temp, metaMembers[3]), 'utf8');
    const packages = parseDpkgStatus(statusText);
    const installed = packages.filter(fields => (fields.Status ?? '').includes('install ok installed'));
    const codename = lsb.match(/DISTRIB_CODENAME=(\S+)/)?.[1] ?? null;
    const release = lsb.match(/DISTRIB_RELEASE=(\S+)/)?.[1] ?? null;
    const osCodename = osRelease.match(/VERSION_CODENAME=(\S+)/)?.[1] ?? null;
    const aptCodename = sources.match(/ubuntu-ports\s+(\w+)\s+main/)?.[1] ?? null;
    const samples = sampleMembers.map(member => {
      const path = join(temp, member);
      if (!existsSync(path)) return { member, present: false };
      const data = readFileSync(path);
      return { member, present: true, bytes: data.length, elf: parseElf(data) };
    });

    const licenseMembers = facts.rows
      .filter(row => row.type === '-' && (/\/usr\/share\/doc\/.+\/copyright$/.test(row.name) || /\/usr\/share\/common-licenses\/[^/]+$/.test(row.name)))
      .map(row => row.name);
    assert.ok(licenseMembers.length >= 190, `rootfs license members: ${licenseMembers.length}`);
    const licenseExtract = exec('tar', ['-xJf', archive, '-C', temp, '--wildcards', ...licenseMembers]);
    assert.ok(['0', '2'].includes(String(licenseExtract.status)), `rootfs license extract failed: ${licenseExtract.stderr.slice(0, 400)}`);
    const licenseFiles = licenseMembers.map(member => {
      const path = join(temp, member);
      assert.ok(existsSync(path), `rootfs license member missing: ${member}`);
      const data = readFileSync(path);
      const parent = member.replace(`${rootfsPrefix}/`, '');
      const text = data.toString('utf8');
      const families = licenseFamilies(text);
      const entry = { path: parent, sha256: sha256(data), ...families };
      if (parent.startsWith('usr/share/common-licenses/')) {
        entry.id = basename(parent);
        entry.families = [basename(parent)];
        entry.source = 'common-licenses-file';
      }
      return entry;
    });
    const copyrightFiles = licenseFiles.filter(item => item.path.startsWith('usr/share/doc/'));
    const commonLicenses = licenseFiles.filter(item => item.path.startsWith('usr/share/common-licenses/'));
    const copyrightByPackage = new Map();
    for (const item of copyrightFiles) {
      const parts = item.path.split('/');
      if (parts.length === 5) copyrightByPackage.set(parts[3], item);
    }
    const symlinkByPackage = new Map();
    for (const link of facts.symlinks) {
      const match = link.name.match(/^.*\/usr\/share\/doc\/([^/]+)\/copyright$/);
      if (match !== null) symlinkByPackage.set(match[1], link.link);
    }
    const docNames = new Set([...copyrightByPackage.keys(), ...symlinkByPackage.keys()]);
    const rows = installed.map(fields => {
      const name = fields.Package;
      const source = fields.Source ?? name;
      const direct = copyrightByPackage.get(name) ?? null;
      let resolution = direct !== null ? 'direct' : 'unresolved';
      let copyright = direct;
      if (direct === null && symlinkByPackage.has(name)) {
        const target = symlinkByPackage.get(name).replace(/^\.\.\//, '');
        const targetPath = `usr/share/doc/${target}`;
        const resolved = copyrightFiles.find(item => item.path === targetPath) ?? null;
        if (resolved !== null) {
          resolution = 'symlink';
          copyright = resolved;
        } else {
          resolution = 'dangling-symlink';
        }
      }
      if (copyright === null) {
        const candidates = [...docNames].filter(doc => doc === source || doc.startsWith(`${source}-`) || source.startsWith(`${doc}-`));
        const chosen = candidates.sort((a, b) => a.length - b.length)[0] ?? null;
        if (chosen !== null) {
          const found = copyrightByPackage.get(chosen) ?? null;
          if (found !== null) {
            resolution = 'source-package-copyright';
            copyright = found;
          }
        }
      }
      return {
        package: name,
        source,
        version: fields.Version ?? null,
        status: fields.Status ?? null,
        resolution,
        copyright: copyright === null ? null : { path: copyright.path, sha256: copyright.sha256, families: copyright.families },
      };
    });
    const unresolved = rows.filter(row => row.copyright === null);
    return {
      asset: rootfsAsset,
      bytes: bytes.length,
      sha256: sha256(bytes),
      entries: facts.entries,
      types: facts.counts,
      traversal: facts.traversal,
      release,
      codename,
      osCodename,
      aptCodename,
      consistent: codename !== null && codename === osCodename && codename === aptCodename,
      packages: rows.length,
      packageRows: rows,
      copyrightFiles,
      commonLicenses,
      symlinks: facts.symlinks.filter(link => link.name.includes('/copyright')).map(link => ({ path: link.name.replace(`${rootfsPrefix}/`, ''), target: link.link })),
      unresolved,
      samples,
      statusSha256: sha256(Buffer.from(statusText)),
      sources,
    };
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
}

function assetFile(repo, path) {
  assert.ok(hasRepo(repo, path), `${repo}:${path} is registered but missing`);
  const sensitive = sensitivePathReason(path);
  if (sensitive !== null) {
    assert.equal(isSensitivePath(path), true);
    return {
      path,
      present: true,
      bytes: bytesOf(repo, path),
      sha256: null,
      format: 'sensitive-excluded',
      elf: null,
      exclusion: sensitive,
      acquisition: 'metadata-only; private material excluded',
    };
  }
  const bytes = readRepo(repo, path);
  const format = magic(bytes);
  return { path, present: true, bytes: bytes.length, sha256: sha256(bytes), format, elf: format === 'elf' ? parseElf(bytes) : null };
}

function declaredHashCheck(text, pattern, label) {
  const match = text.match(pattern);
  assert.ok(match !== null, `${label}: declared hash not found`);
  return match[1];
}

function desktopLicenseBoundary(lock) {
  const repository = lock.repositories.find(item => item.id === 'desktop');
  assert.ok(repository, 'desktop repository in source lock');
  assert.equal(git(desktop, 'rev-parse', 'HEAD'), repository.commit, 'desktop HEAD vs source lock');
  const packageJson = JSON.parse(readRepo('desktop', 'package.json').toString('utf8'));
  const packageLock = JSON.parse(readRepo('desktop', 'package-lock.json').toString('utf8'));
  const vue = packageLock.packages?.['node_modules/vue'];
  assert.ok(vue, 'desktop lock must contain vue');
  assert.ok(packageJson.devDependencies?.vue, 'desktop vue must be a development dependency');
  assert.equal(packageJson.dependencies?.vue, undefined, 'desktop vue must not be a runtime dependency');
  assert.equal(vue.dev, true, 'desktop vue lock entry must be development-only');
  assert.equal(vue.license, 'MIT', 'desktop vue license');
  assert.ok(/^https:\/\/registry\.npmjs\.org\/vue\/-\/vue-[^/]+\.tgz$/.test(vue.resolved), 'desktop vue tarball URL');
  assert.match(vue.integrity, /^sha512-[A-Za-z0-9+/=]+$/);

  const acknowledgments = readRepo('desktop', desktopAcknowledgmentsFile).toString('utf8');
  assert.match(acknowledgments, /Electron/, 'desktop acknowledgments must describe Electron');
  assert.match(acknowledgments, /React/, 'desktop acknowledgments must describe React');
  assert.match(acknowledgments, /Node\.js/, 'desktop acknowledgments must describe Node.js');
  assert.match(acknowledgments, /Noto Sans SC/, 'desktop acknowledgments must describe the export font');
  assert.match(acknowledgments, /OFL-1\.1/, 'desktop acknowledgments must name the export-font license');
  assert.match(acknowledgments, /legal\/THIRD_PARTY_NOTICES\.txt/, 'desktop acknowledgments must reference its generated notice');
  const notices = readRepo('desktop', desktopNoticesFile).toString('utf8');
  assert.match(notices, /Bundled font: Noto Sans SC/, 'desktop notice must contain the export font');
  const frontend = JSON.parse(readRepo('desktop', desktopFrontendPackagesFile).toString('utf8'));
  assert.ok(Array.isArray(frontend.packages), 'desktop frontend package inventory');
  const vueInDesktopBundleInventory = frontend.packages.some(item => item.path === 'node_modules/vue');
  const font = assetSpecs().find(item => item.id === 'noto-sans-sc-paper-export');
  assert.equal(font.source.commit, fontGenerationCommit, 'font generation commit');
  const fontFiles = font.files.map(path => ({
    path,
    sha256: sha256Of('desktop', path),
    atGeneration: sha256(execFileSync('git', ['show', `${fontGenerationCommit}:${path}`], { cwd: desktop, maxBuffer: 16 << 20 })),
  }));
  assert.ok(fontFiles.every(item => item.sha256 === item.atGeneration), 'paper-export font bytes must match the generation commit');

  return {
    desktopCommit: repository.commit,
    desktopTree: repository.tree,
    license: { path: desktopLicenseFile, sha256: sha256Of('desktop', desktopLicenseFile), usedAsReference: true, supersedesProjectNotice: false },
    acknowledgments: {
      path: desktopAcknowledgmentsFile,
      sha256: sha256Of('desktop', desktopAcknowledgmentsFile),
      role: 'upstream desktop acknowledgment inventory, checked as evidence only',
      usedAsAndroidNotice: false,
      supersedesProjectNotice: false,
    },
    thirdPartyNotices: { path: desktopNoticesFile, sha256: sha256Of('desktop', desktopNoticesFile), usedAsAndroidNotice: false },
    frontendBundleInventory: {
      path: desktopFrontendPackagesFile,
      sha256: sha256Of('desktop', desktopFrontendPackagesFile),
      packages: frontend.packages.length,
      vueIncluded: vueInDesktopBundleInventory,
      vueObservationOnly: true,
    },
    fonts: {
      generationCommit: fontGenerationCommit,
      generatedFrom: 'NotoSansSC-VF.ttf',
      tool: 'fontTools 4.67.0',
      files: fontFiles,
      unchangedAtFrozenCommit: true,
      androidDelivery: 'not-adopted runtime resource; S0 has no APK; any future Android PDF export must independently embed or replace the fonts under S4.05',
    },
    adoptionBoundary: {
      result: 'desktop-runtime-not-adopted-in-android',
      apkRelevant: false,
      node: 'development/runtime evidence only; terminal Node installs are user tools, not an in-APK runtime',
      react: 'desktop UI implementation only; Android UI is Kotlin/Compose',
      vue: 'desktop development dependency; the desktop bundle inventory is observation-only, and Vue is not an Android APK dependency',
      demoPdf: 'upstream demo/film evidence only; not-adopted runtime asset',
      upstreamMobile: 'not-adopted; Capacitor/mobile/storage-mobile/Java runtime does not enter delivery',
      rule: 'desktop source and notices are behavior/reference evidence; this will not be represented as an Android license clearance',
    },
  };
}

function buildDeclarations() {
  const plan = readFileSync(resolve(root, planFile), 'utf8');
  const lock = readJson(lockFile);
  const adoption = readJson(adoptionFile);
  const operitHead = git(operit, 'rev-parse', 'HEAD');
  const terminalHead = git(terminalRoot, 'rev-parse', 'HEAD');
  const operitRepo = lock.repositories.find(item => item.id === 'operit');
  const terminalRepo = lock.repositories.find(item => item.id === 'terminal');
  assert.equal(operitHead, operitRepo.commit, 'Operit HEAD vs source lock');
  assert.equal(terminalHead, terminalRepo.commit, 'terminal HEAD vs source lock');
  assert.equal(adoption.unit, 'S0.13');
  assert.equal(adoption.checks.operitCommit, operitRepo.commit);
  assert.equal(adoption.checks.terminalCommit, terminalRepo.commit);
  assert.equal(adoption.evidence.sourceLock.desktopCommit, lock.repositories.find(item => item.id === 'desktop').commit, 'adoption desktop commit');

  const declarationRows = [
    ['operit', 'terminal/src/main/jni/CMakeLists.txt', 'project("pty")'],
    ['operit', 'terminal/src/main/jni/pty.c', 'forkpty(&master_fd'],
    ['operit', 'terminal/build.gradle.kts', 'abiFilters += listOf("arm64-v8a")'],
    ['operit', 'quickjs/src/main/cpp/CMakeLists.txt', '"master"'],
    ['operit', 'quickjs/src/main/cpp/CMakeLists.txt', 'max-page-size=16384'],
    ['operit', 'app/src/main/cpp/CMakeLists.txt', 'enable_16kb_page_alignment'],
    ['operit', 'app/src/main/cpp/CMakeLists.txt', '"main"'],
    ['operit', 'llm/llama/CMakeLists.txt', '720d7fa4097f76e5d0eade5a92c1df87c1faf9d9'],
    ['operit', 'llm/llama/CMakeLists.txt', 'max-page-size=16384'],
    ['operit', 'llm/mnn/CMakeLists.txt', 'd407447ed56c4121a11ccbd266dc184ca1ead0c2'],
    ['operit', 'llm/mnn/CMakeLists.txt', 'v1.16.0'],
    ['operit', 'llm/mnn/CMakeLists.txt', 'max-page-size=16384'],
    ['operit', 'cmake/operit_git_source.cmake', 'git ls-remote'],
    ['operit', 'app/build.gradle.kts', 'requiredExternallyBuiltNativeLibraries'],
    ['operit', 'app/build.gradle.kts', 'ffmpeg-kit-local.aar'],
    ['operit', 'app/build.gradle.kts', 'config/stt-model-assets.properties'],
    ['operit', 'app/build.gradle.kts', 'useLegacyPackaging = true'],
    ['operit', 'app/src/main/AndroidManifest.xml', 'com.ai.assistance.operit.provider'],
    ['operit', 'app/src/main/java/com/ai/assistance/operit/core/tools/system/ShizukuInstaller.kt', 'shizuku.apk'],
    ['operit', 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/RootShellExecutor.kt', 'operit_shell_exec'],
    ['operit', 'tools/shower/build_server.bat', 'Copying APK to'],
    ['operit', 'tools/shell_identity_launcher/CMakeLists.txt', 'add_executable(operit_shell_exec'],
    ['operit', 'ci/script/download_android_dependencies.sh', '1Va1os7PRpCF3xtTwfx5kO11D7eIAvARG'],
    ['terminal', 'src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz', null],
  ];
  const declarations = declarationRows.map(([repo, path, token]) => {
    const text = readRepo(repo, path).toString('latin1');
    const row = { repo, path, sha256: sha256Of(repo, path), bytes: bytesOf(repo, path) };
    if (token !== null) {
      const line = lineOf(text, token);
      row.line = line;
      row.token = token;
      row.text = text.split('\n')[line - 1].trim();
    }
    return row;
  });
  const section33 = lineOf(plan, '### 3.3 移植执行规则');
  const section54 = lineOf(plan, '### 5.4 Ubuntu与依赖安装中心');
  const unitLine = lineOf(plan, '| S0.14 | 整理原生资源与许可来源');
  assert.ok(plan.split('\n')[unitLine - 1].startsWith('| S0.14 |'), 'plan S0.14 line');
  return { plan, lock, adoption, operitRepo, terminalRepo, declarations, section33, section54, unitLine };
}

function sttManifest() {
  const path = 'app/config/stt-model-assets.properties';
  const text = readRepo('operit', path).toString('utf8');
  const rows = text.split('\n').map(line => line.trim()).filter(line => line && !line.startsWith('#')).map((line, index) => {
    const parts = line.split('|');
    assert.equal(parts.length, 6, `${path}:${index + 1} field count`);
    assert.ok(parts[0] && !parts[0].startsWith('/') && !parts[0].includes('..'), `${path}:${index + 1} target path`);
    assert.ok(parts[1].startsWith('https://'), `${path}:${index + 1} source url`);
    assert.ok(/^[0-9a-f]{64}$/.test(parts[3]), `${path}:${index + 1} sha256`);
    assert.ok(Number(parts[2]) > 0, `${path}:${index + 1} bytes`);
    return {
      target: parts[0],
      url: parts[1],
      bytes: Number(parts[2]),
      sha256: parts[3],
      source: parts[4],
      licenseNote: parts[5],
    };
  });
  assert.equal(rows.length, 8, 'STT manifest rows');
  return { path, sha256: sha256Of('operit', path), rows };
}

function assetSpecs() {
  return [
    {
      id: 'terminal-pty-jni', kind: 'source-build', category: 'terminal', deliveryUnit: 'S6.01',
      repo: 'operit', files: ['terminal/src/main/jni/pty.c', 'terminal/src/main/jni/CMakeLists.txt', 'terminal/build.gradle.kts'],
      source: { type: 'in-tree-source', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', repository: 'https://github.com/AAswordman/OperitTerminalCore.git' },
      build: { script: 'terminal/src/main/jni/CMakeLists.txt', abi: ['arm64-v8a'], minSdk: 26, pageSizeDeclared: 'none', output: 'libpty.so (built by Gradle, absent from tree)' },
      packaging: { method: 'gradle-external-native-build', destination: 'jniLibs/libpty.so', shipped: true, reproducible: true },
      license: { status: 'resolved', id: 'LGPL-3.0', basis: 'terminal repository LICENSE', texts: [{ repo: 'terminal', path: 'LICENSE' }], note: 'terminal LICENSE omits the GPL-3 body at line 211' },
      notice: { required: true, action: 'include-text-and-source-offer' },
      followUp: { unit: 'S6.01', reason: 'built artifact absent; 16 KiB page-size flag is not declared in terminal CMake' },
    },
    {
      id: 'terminal-libbash', kind: 'prebuilt-native', category: 'terminal', deliveryUnit: 'S6.01',
      repo: 'operit', files: ['terminal/src/main/jniLibs/arm64-v8a/libbash.so'],
      source: { type: 'prebuilt-committed', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', declared: false, note: 'bash 5.2.37(1) release strings; no source package or build recipe in either repo' },
      build: { script: null, abi: ['arm64-v8a'], pageSizeDeclared: 'binary', output: 'libbash.so' },
      packaging: { method: 'jniLibs', destination: 'lib/arm64-v8a/libbash.so; symlinked as bin/bash', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'GPL-3.0-or-later', basis: 'binary string: "License GPLv3+: GNU GPL version 3 or later"', texts: [{ rootfs: 'usr/share/common-licenses/GPL-3' }] },
      notice: { required: true, action: 'include-text-and-source-offer' },
      followUp: { unit: 'S6.01', reason: 'source package, build script and upstream hash are not recorded' },
    },
    {
      id: 'terminal-libbusybox', kind: 'prebuilt-native', category: 'terminal', deliveryUnit: 'S6.01',
      repo: 'operit', files: ['terminal/src/main/jniLibs/arm64-v8a/libbusybox.so'],
      source: { type: 'prebuilt-committed', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', declared: false, note: 'BusyBox v1.36.1-osm0sis (2023-05-25); third-party build, no recipe in either repo' },
      build: { script: null, abi: ['arm64-v8a'], pageSizeDeclared: 'binary', output: 'libbusybox.so' },
      packaging: { method: 'jniLibs', destination: 'lib/arm64-v8a/libbusybox.so; symlinked as bin/busybox', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'GPL-2.0-only', basis: 'binary string: "Licensed under GPLv2. See source distribution"', texts: [{ rootfs: 'usr/share/common-licenses/GPL-2' }], note: 'GPL-2.0-only redistribution inside an AGPL-3.0 app requires source offer and compatibility review' },
      notice: { required: true, action: 'include-text-and-source-offer' },
      followUp: { unit: 'S6.01', reason: 'third-party build (osm0sis) has no pinned source/build recipe; license compatibility review required' },
    },
    {
      id: 'terminal-liboperit-proot', kind: 'prebuilt-native', category: 'terminal', deliveryUnit: 'S6.01',
      repo: 'operit', files: ['terminal/src/main/jniLibs/arm64-v8a/liboperit_proot.so'],
      source: { type: 'prebuilt-committed', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', declared: false, note: 'PRoot 5.1.0 strings; no source package or build recipe in either repo' },
      build: { script: null, abi: ['arm64-v8a'], pageSizeDeclared: 'binary', output: 'liboperit_proot.so' },
      packaging: { method: 'jniLibs', destination: 'lib/arm64-v8a/liboperit_proot.so; symlinked as bin/proot', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'GPL-2.0-or-later', basis: 'binary string: "Copyright (C) 2015 STMicroelectronics, licensed under GPL v2 or later."', texts: [{ rootfs: 'usr/share/common-licenses/GPL-2' }] },
      notice: { required: true, action: 'include-text-and-source-offer' },
      followUp: { unit: 'S6.01', reason: 'PRoot fork source and build recipe are not recorded' },
    },
    {
      id: 'terminal-liboperit-loader', kind: 'prebuilt-native', category: 'terminal', deliveryUnit: 'S6.01',
      repo: 'operit', files: ['terminal/src/main/jniLibs/arm64-v8a/liboperit_loader.so'],
      source: { type: 'prebuilt-committed', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', declared: false, note: '1,632-byte ELF with one .text section, no symbols and no strings; provenance unknown' },
      build: { script: null, abi: ['arm64-v8a'], pageSizeDeclared: 'binary', output: 'liboperit_loader.so' },
      packaging: { method: 'jniLibs', destination: 'lib/arm64-v8a/liboperit_loader.so; symlinked as bin/loader', shipped: true, reproducible: false },
      license: { status: 'unknown', id: null, basis: 'no local license text or binary string', texts: [] },
      notice: { required: true, action: 'review' },
      followUp: { unit: 'S6.01', reason: 'source, purpose, license and build recipe unknown; needed evidence: upstream source and license' },
    },
    {
      id: 'terminal-libsudo', kind: 'prebuilt-native', category: 'terminal', deliveryUnit: 'S6.01',
      repo: 'operit', files: ['terminal/src/main/jniLibs/arm64-v8a/libsudo.so'],
      source: { type: 'prebuilt-committed', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', declared: false, note: '2-byte file containing "$@"; not ELF and no shebang' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'libsudo.so' },
      packaging: { method: 'jniLibs', destination: 'lib/arm64-v8a/libsudo.so; symlinked as bin/sudo', shipped: true, reproducible: false },
      license: { status: 'unknown', id: null, basis: '2-byte shell fragment, no license evidence', texts: [] },
      notice: { required: true, action: 'review' },
      followUp: { unit: 'S6.01', reason: 'not an ELF; execution depends on shell fallback; source/license unknown' },
    },
    {
      id: 'terminal-rootfs', kind: 'rootfs-archive', category: 'rootfs', deliveryUnit: 'S6.09',
      repo: 'operit', files: [rootfsAsset],
      source: { type: 'prebuilt-committed', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', declared: false, note: 'Ubuntu 24.04.1 LTS noble rootfs named proot-distro v4.18.0; no upstream URL or manifest in repo' },
      build: { script: null, abi: ['aarch64'], pageSizeDeclared: 'binary', output: 'rootfs tar.xz' },
      packaging: { method: 'assets', destination: 'terminal/src/main/assets; extracted to filesDir then unpacked', shipped: true, reproducible: false },
      license: { status: 'aggregate', id: 'mixed-ubuntu-noble', basis: 'dpkg status + per-package copyright', texts: [] },
      notice: { required: true, action: 'aggregate-notice-and-source-offer' },
      followUp: { unit: 'S6.09', reason: 'upstream release URL, archive manifest and reproducible build are not recorded' },
    },
    {
      id: 'terminal-setup-fake-sysdata', kind: 'shell-script', category: 'rootfs', deliveryUnit: 'S6.09',
      repo: 'operit', files: ['terminal/src/main/assets/setup_fake_sysdata.sh'],
      source: { type: 'in-tree-source', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', declared: true, note: 'header states values are based on proot-distro' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'setup_fake_sysdata.sh' },
      packaging: { method: 'assets', destination: 'terminal/src/main/assets; sourced by start script', shipped: true, reproducible: true },
      license: { status: 'declared-unverified', id: 'GPL-3.0', basis: 'proot-distro derivative per file header; no license header in file', texts: [{ rootfs: 'usr/share/common-licenses/GPL-3' }] },
      notice: { required: true, action: 'include-text-and-source-offer' },
      followUp: { unit: 'S6.09', reason: 'upstream proot-distro version and license text are not recorded' },
    },
    {
      id: 'terminal-font-jetbrains-mono-nerd', kind: 'font', category: 'font', deliveryUnit: 'S6.03',
      repo: 'operit', files: ['terminal/src/main/res/font/jetbrains_mono_nerd_font_regular.ttf'],
      source: { type: 'prebuilt-committed', commit: 'terminal', ref: 'e4646680663789002b43bbfffa6e69e65c35e614', declared: true, version: 'JetBrainsMonoNL NF 2.304 + Nerd Fonts 3.2.1', url: 'https://github.com/ryanoasis/nerd-fonts', note: 'font name table declares copyright and OFL-1.1' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'res/font TTF' },
      packaging: { method: 'res/font', destination: 'terminal R.font.jetbrains_mono_nerd_font_regular', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'OFL-1.1', basis: 'name ID 13 in the TTF; OFL text not vendored', texts: [{ repo: 'desktop', path: 'public/fonts/paper-export/OFL.txt' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S6.03', reason: 'OFL text and exact patched-font release hash are not vendored in Operit' },
    },
    {
      id: 'quickjs-engine', kind: 'source-build', category: 'script-engine', deliveryUnit: 'S6.31',
      repo: 'operit', files: ['quickjs/src/main/cpp/CMakeLists.txt', 'quickjs/src/main/cpp/quickjs_jni.cpp'],
      source: { type: 'git', repository: 'https://github.com/bellard/quickjs.git', ref: 'master', commit: 'e585640ffa2cf88ce7c2d587583c6fe15928ec2f', declared: true, note: 'CMake line 12 reads master; source-lock pins the commit' },
      build: { script: 'quickjs/src/main/cpp/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'max-only', line: 82, output: 'libquickjsjni.so (built by Gradle, absent from tree)' },
      packaging: { method: 'gradle-external-native-build', destination: 'libquickjsjni.so', shipped: true, reproducible: true },
      license: { status: 'declared-unverified', id: 'MIT', basis: 'upstream project license; no LICENSE file in either repo', texts: [] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S6.31', reason: 'fetch upstream LICENSE at the pinned commit and store text/hash; verify built LOAD alignment' },
    },
    {
      id: 'wamr-engine', kind: 'source-build', category: 'script-engine', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['app/src/main/cpp/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/bytecodealliance/wasm-micro-runtime.git', ref: 'main', commit: 'ffc22889948b1f063d7258d34d4e636ee0914925', declared: true },
      build: { script: 'app/src/main/cpp/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'max+common', line: 186, output: 'libtoolpkgwasm.so (built by Gradle)' },
      packaging: { method: 'gradle-external-native-build', destination: 'libtoolpkgwasm.so', shipped: true, reproducible: true },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'upstream project license; no LICENSE file in either repo', texts: [] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S4.01', reason: 'store upstream license text/hash and verify built alignment' },
    },
    {
      id: 'sherpa-ncnn-engine', kind: 'source-build', category: 'speech', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['app/src/main/cpp/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/k2-fsa/sherpa-ncnn.git', ref: 'master', commit: '497b83215d218958833496182614b7c3807001a8', declared: true },
      build: { script: 'app/src/main/cpp/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'none', output: 'libsherpa-ncnn-jni.so (built by Gradle)' },
      packaging: { method: 'gradle-external-native-build', destination: 'libsherpa-ncnn-jni.so', shipped: true, reproducible: true },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'about screen declaration; no local license text', texts: [] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S4.01', reason: 'sherpa-ncnn-jni does not receive enable_16kb_page_alignment; verify built LOAD alignment and license text' },
    },
    {
      id: 'ncnn-source-declaration', kind: 'source-declaration', category: 'speech', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['app/src/main/cpp/cmake/ncnn.cmake'],
      source: { type: 'git', repository: 'https://github.com/Tencent/ncnn.git', ref: 'master', commit: 'd1f6ac67e9fc91390cc70f7168573fd2626f1d66', declared: true, note: 'declaration file is not included by any CMakeLists in the repo' },
      build: { script: 'app/src/main/cpp/cmake/ncnn.cmake', abi: [], pageSizeDeclared: 'none', referenced: false },
      packaging: { method: 'not-referenced', destination: null, shipped: false, reproducible: false },
      license: { status: 'declared-unverified', id: 'BSD-3-Clause', basis: 'upstream project license; no local text', texts: [] },
      notice: { required: false, action: 'review' },
      followUp: { unit: 'S4.01', reason: 'unused declaration with floating master; record actual ncnn acquisition path of sherpa-ncnn' },
    },
    {
      id: 'openfst-source-declaration', kind: 'source-declaration', category: 'speech', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['app/src/main/cpp/cmake/openfst.cmake'],
      source: { type: 'url', url: 'https://github.com/csukuangfj/openfst/archive/refs/tags/sherpa-onnx-2024-06-19.tar.gz', declaredSha256: '5c98e82cc509c5618502dde4860b8ea04d843850ed57e6d6b590b644b268853d', declared: true, note: 'declaration file is not included by any CMakeLists in the repo' },
      build: { script: 'app/src/main/cpp/cmake/openfst.cmake', abi: [], pageSizeDeclared: 'none', referenced: false },
      packaging: { method: 'not-referenced', destination: null, shipped: false, reproducible: true },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'upstream project license; no local text', texts: [] },
      notice: { required: false, action: 'review' },
      followUp: { unit: 'S4.01', reason: 'unused declaration; keep hash evidence only if adopted' },
    },
    {
      id: 'llama-cpp-engine', kind: 'source-build', category: 'inference', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['llm/llama/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/ggml-org/llama.cpp.git', ref: '720d7fa4097f76e5d0eade5a92c1df87c1faf9d9', commit: '720d7fa4097f76e5d0eade5a92c1df87c1faf9d9', declared: true },
      build: { script: 'llm/llama/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'max-only', line: 44, output: 'libLlamaWrapper.so (built by Gradle)' },
      packaging: { method: 'gradle-external-native-build', destination: 'libLlamaWrapper.so', shipped: true, reproducible: true },
      license: { status: 'declared-unverified', id: 'MIT', basis: 'upstream project license; no local text', texts: [] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S4.01', reason: 'store upstream license text/hash; verify built LOAD alignment' },
    },
    {
      id: 'mnn-engine', kind: 'source-build', category: 'inference', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['llm/mnn/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/alibaba/MNN.git', ref: '3.6.1', commit: 'd407447ed56c4121a11ccbd266dc184ca1ead0c2', declared: true },
      build: { script: 'llm/mnn/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'max-only', line: 232, output: 'libMNN.so, libMNNWrapper.so (built by Gradle)' },
      packaging: { method: 'gradle-external-native-build', destination: 'libMNN.so, libMNNWrapper.so', shipped: true, reproducible: true },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'about screen declaration; no local license text', texts: [] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S4.01', reason: 'store upstream license text/hash; verify built LOAD alignment' },
    },
    {
      id: 'kleidiai-engine', kind: 'source-build', category: 'inference', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['llm/mnn/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/ARM-software/kleidiai.git', ref: 'v1.16.0', commit: '84796ece210fbf736c2c51236f4690e0b8812861', declared: true },
      build: { script: 'llm/mnn/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'inherited', output: 'linked into libMNN.so' },
      packaging: { method: 'gradle-external-native-build', destination: 'libMNN.so', shipped: true, reproducible: true },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'upstream project license; no local text', texts: [] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S4.01', reason: 'store upstream license text/hash' },
    },
    {
      id: 'saba-engine', kind: 'source-build', category: 'avatar-rejected', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['avator/mmd/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/benikabocha/saba.git', ref: 'master', commit: '29b8efa8b31c8e746f9a88020fb0ad9dcdcf3332', declared: true },
      build: { script: 'avator/mmd/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'none', output: 'libMmdWrapper.so' },
      packaging: { method: 'not-adopted', destination: null, shipped: false, reproducible: true },
      license: { status: 'declared-unverified', id: 'MIT', basis: 'upstream project license; no local text', texts: [] },
      notice: { required: false, action: 'none' },
      followUp: { unit: 'S4.01', reason: 'S0.13 rejected avatar scope; keep recorded and do not build for dstu' },
    },
    {
      id: 'bullet3-engine', kind: 'source-build', category: 'avatar-rejected', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['avator/mmd/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/bulletphysics/bullet3.git', ref: 'master', commit: 'd3afe76132fc76b0f9b2359cbaef47a3766e7fbb', declared: true },
      build: { script: 'avator/mmd/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'none', output: 'libMmdWrapper.so' },
      packaging: { method: 'not-adopted', destination: null, shipped: false, reproducible: true },
      license: { status: 'declared-unverified', id: 'Zlib', basis: 'upstream project license; no local text', texts: [] },
      notice: { required: false, action: 'none' },
      followUp: { unit: 'S4.01', reason: 'S0.13 rejected avatar scope; keep recorded and do not build for dstu' },
    },
    {
      id: 'ufbx-engine', kind: 'source-build', category: 'avatar-rejected', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['avator/fbx/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/ufbx/ufbx.git', ref: 'main', commit: '5955c5c0b042ac2dc6f32955ab206aaec0620cfc', declared: true },
      build: { script: 'avator/fbx/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'max-only', output: 'libFbxWrapper.so' },
      packaging: { method: 'not-adopted', destination: null, shipped: false, reproducible: true },
      license: { status: 'declared-unverified', id: 'MIT', basis: 'upstream project license; no local text', texts: [] },
      notice: { required: false, action: 'none' },
      followUp: { unit: 'S4.01', reason: 'S0.13 rejected avatar scope; keep recorded and do not build for dstu' },
    },
    {
      id: 'dragonbones-cpp-engine', kind: 'source-build', category: 'avatar-rejected', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['avator/dragonbones/CMakeLists.txt'],
      source: { type: 'git', repository: 'https://github.com/DragonBones/DragonBonesCPP.git', ref: 'f696143045e07b7c4c4590bdd95534e9e8074b37', commit: 'f696143045e07b7c4c4590bdd95534e9e8074b37', declared: true },
      build: { script: 'avator/dragonbones/CMakeLists.txt', abi: ['arm64-v8a'], pageSizeDeclared: 'max-only', output: 'libdragonbones_native.so' },
      packaging: { method: 'not-adopted', destination: null, shipped: false, reproducible: true },
      license: { status: 'declared-unverified', id: 'MIT', basis: 'upstream project license; no local text', texts: [] },
      notice: { required: false, action: 'none' },
      followUp: { unit: 'S4.01', reason: 'S0.13 rejected avatar scope; keep recorded and do not build for dstu' },
    },
    {
      id: 'sherpa-mnn-jni', kind: 'prebuilt-native', category: 'speech', deliveryUnit: 'S4.18',
      repo: 'operit', files: ['llm/mnn/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so'],
      source: { type: 'prebuilt-committed', commit: '7a39941bad80209973c535046a18195c5da9e647', declared: false, note: 'JNI symbols com.k2fsa.sherpa.mnn.*; llm/mnn CMake builds MNNWrapper/MNN, not this library' },
      build: { script: null, abi: ['arm64-v8a'], pageSizeDeclared: 'binary', output: 'libsherpa-mnn-jni.so' },
      packaging: { method: 'jniLibs', destination: 'lib/arm64-v8a/libsherpa-mnn-jni.so', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'about screen declaration only', texts: [] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S4.18', reason: 'no build recipe, upstream commit or license text; needed evidence: sherpa-mnn source tag, build script, license' },
    },
    {
      id: 'stt-models', kind: 'model-manifest', category: 'speech', deliveryUnit: 'S4.18',
      repo: 'operit', files: ['app/config/stt-model-assets.properties'],
      source: { type: 'url-manifest', declared: true, note: '8 files fetched by Gradle syncSttModelAssets; files absent from tree' },
      build: { script: 'app/build.gradle.kts', abi: [], pageSizeDeclared: 'n/a', output: 'build/generated/stt-model-assets' },
      packaging: { method: 'gradle-download-and-verify', destination: 'assets/models', shipped: true, reproducible: true },
      license: { status: 'mixed', id: 'MIT + undeclared', basis: 'manifest column 6', texts: [{ repo: 'operit', path: 'app/src/main/assets/js/katex.LICENSE.txt' }] },
      notice: { required: true, action: 'review' },
      followUp: { unit: 'S4.18', reason: 'seven sherpa-ncnn files declare no upstream license; needed evidence: model card license and redistribution permission' },
    },
    {
      id: 'ffmpeg-kit-local-aar', kind: 'external-build', category: 'media', deliveryUnit: 'S4.17',
      repo: 'operit', files: ['app/build.gradle.kts', 'tools/ffmpeg/build_ffmpeg_kit_wsl.sh', 'tools/ffmpeg/import_local_ffmpeg_kit.ps1'],
      source: { type: 'external-build', declared: true, url: 'https://github.com/arthenica/ffmpeg-kit', note: 'AAR is absent from tree and built outside Gradle' },
      build: { script: 'tools/ffmpeg/build_ffmpeg_kit_wsl.sh', abi: ['arm64-v8a'], pageSizeDeclared: 'none', output: 'app/libs/ffmpeg-kit-local.aar (absent)' },
      packaging: { method: 'local-aar', destination: 'lib/arm64-v8a/libffmpegkit.so and 9 dependencies', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'LGPL-3.0', basis: 'about screen declaration; no local text', texts: [] },
      notice: { required: true, action: 'include-text-and-source-offer' },
      followUp: { unit: 'S4.17', reason: 'ffmpeg-kit commit, AAR hash and per-library license manifest are not recorded' },
    },
    {
      id: 'liboperit-ripgrep-so', kind: 'external-build', category: 'search', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['tools/native_ripgrep/Cargo.toml', 'tools/native_ripgrep/Cargo.lock', 'tools/native_ripgrep/build_native_ripgrep.ps1'],
      source: { type: 'external-build', declared: true, note: 'Rust cdylib built outside Gradle; binary absent' },
      build: { script: 'tools/native_ripgrep/build_native_ripgrep.ps1', abi: ['arm64-v8a'], pageSizeDeclared: 'none', output: 'app/src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so (absent)' },
      packaging: { method: 'not-adopted', destination: null, shipped: false, reproducible: true },
      license: { status: 'declared-unverified', id: 'MIT', basis: 'ripgrep/ignore/globset crates; no local text', texts: [] },
      notice: { required: false, action: 'none' },
      followUp: { unit: 'S4.01', reason: 'S0.13 rejected native ripgrep for dstu; Cargo.lock retained as source evidence' },
    },
    {
      id: 'operit-ci-archives', kind: 'external-archive', category: 'build-support', deliveryUnit: 'S4.01',
      repo: 'operit', files: ['ci/script/download_android_dependencies.sh', 'ci/script/prepare_android_dependencies.py', 'ci/README.md'],
      source: { type: 'google-drive', declared: true, note: 'libs.zip, subpack.zip, jniLibs.zip; fixed file IDs, no content hash' },
      build: { script: 'ci/script/prepare_android_dependencies.py', abi: [], pageSizeDeclared: 'n/a', output: 'app/libs, app/src/main/assets/subpack, app/src/main/jniLibs' },
      packaging: { method: 'ci-download', destination: 'build inputs', shipped: false, reproducible: false },
      license: { status: 'unknown', id: null, basis: 'archive members have no license manifest', texts: [] },
      notice: { required: true, action: 'review' },
      followUp: { unit: 'S4.01', reason: 'archive hashes and per-member license inventory are missing; needed evidence: downloaded archive SHA-256 and manifest' },
    },
    {
      id: 'mcp-bridge-js', kind: 'bundled-js', category: 'mcp', deliveryUnit: 'S6.28',
      repo: 'operit', files: ['app/src/main/assets/bridge/index.js', 'app/src/main/assets/bridge/spawn-helper.js', 'tools/mcp_bridge/normalize-assets.js'],
      source: { type: 'generated-bundle', declared: false, note: 'webpack bundles; spawn-helper includes ajv/ajv-formats and other npm code' },
      build: { script: 'tools/mcp_bridge/normalize-assets.js', abi: [], pageSizeDeclared: 'n/a', output: 'bridge/index.js, bridge/spawn-helper.js' },
      packaging: { method: 'assets', destination: 'bridge/', shipped: true, reproducible: false },
      license: { status: 'unknown', id: null, basis: 'no lockfile, version or license manifest for bundled npm modules', texts: [] },
      notice: { required: true, action: 'review' },
      followUp: { unit: 'S6.28', reason: 'npm module inventory/versions/licenses are not recorded; needed evidence: lockfile and generated NOTICE' },
    },
    {
      id: 'terser-bundle', kind: 'bundled-js', category: 'toolpkg', deliveryUnit: 'S6.33',
      repo: 'operit', files: ['app/src/main/assets/js/terser.bundle.min.js', 'app/src/main/assets/js/terser.LICENSE'],
      source: { type: 'generated-bundle', declared: false, version: null, note: 'Terser/UglifyJS bundle; no version string in file' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'js/terser.bundle.min.js' },
      packaging: { method: 'assets', destination: 'js/terser.bundle.min.js', shipped: true, reproducible: false },
      license: { status: 'resolved', id: 'BSD-2-Clause', basis: 'vendored terser.LICENSE', texts: [{ repo: 'operit', path: 'app/src/main/assets/js/terser.LICENSE' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S6.33', reason: 'bundle version and generation command are not recorded' },
    },
    {
      id: 'katex-min', kind: 'bundled-js', category: 'content', deliveryUnit: 'S5.15',
      repo: 'operit', files: ['app/src/main/assets/js/katex.min.js', 'app/src/main/assets/js/katex.LICENSE.txt'],
      source: { type: 'upstream-js', declared: true, version: '0.16.44', url: 'https://github.com/KaTeX/KaTeX', note: 'embedded version differs from desktop lock 0.16.47' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'js/katex.min.js' },
      packaging: { method: 'assets', destination: 'js/katex.min.js', shipped: true, reproducible: false },
      license: { status: 'resolved', id: 'MIT', basis: 'vendored katex.LICENSE.txt', texts: [{ repo: 'operit', path: 'app/src/main/assets/js/katex.LICENSE.txt' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S5.15', reason: 'Android 0.16.44 drifts from desktop lock 0.16.47; align or record compatibility' },
    },
    {
      id: 'js-native-shims', kind: 'project-js', category: 'script-engine', deliveryUnit: 'S6.31',
      repo: 'operit', files: ['app/src/main/assets/js/CryptoJS.js', 'app/src/main/assets/js/Jimp.js', 'app/src/main/assets/js/pako.js', 'app/src/main/assets/js/OkHttp3.js', 'app/src/main/assets/js/AndroidUtils.js', 'app/src/main/assets/js/UINode.js'],
      source: { type: 'in-tree-source', commit: '7a39941bad80209973c535046a18195c5da9e647', declared: true, note: 'headers describe native-bridged implementations, not upstream library copies' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'js shims' },
      packaging: { method: 'assets', destination: 'js/', shipped: true, reproducible: true },
      license: { status: 'project', id: 'LGPL-3.0', basis: 'Operit-authored shims', texts: [{ repo: 'operit', path: 'LICENSE' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S6.31', reason: 'confirm no upstream code is embedded in the shims' },
    },
    {
      id: 'material-symbols-font', kind: 'font', category: 'font', deliveryUnit: 'S5.17',
      repo: 'operit', files: ['app/src/main/assets/fonts/material_symbols_rounded_static.woff2', 'app/src/main/assets/fonts/MATERIAL_SYMBOLS_LICENSE.txt', 'app/src/main/assets/fonts/README.md'],
      source: { type: 'url', declared: true, version: 'Material Symbols Rounded v370', url: 'https://fonts.gstatic.com/s/materialsymbolsrounded/v370/syl0-zNym6YjUruM-QrEh7-nyTnjDwKNJ_190FjpZIvDmUSVOK7BDJ_vb9vUSzq3wzLK-P0J-V_Zs-QtQth3-jOcbTCVpeRL2w5rwZu2rIelXxc.woff2', declaredSha256: '19a71b30e6267416493a0e4e925a6f14be60a9c3a83148a0dc0dcf507a8fb382' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'fonts/material_symbols_rounded_static.woff2' },
      packaging: { method: 'assets', destination: 'fonts/material_symbols_rounded_static.woff2', shipped: true, reproducible: true },
      license: { status: 'resolved', id: 'Apache-2.0', basis: 'vendored MATERIAL_SYMBOLS_LICENSE.txt', texts: [{ repo: 'operit', path: 'app/src/main/assets/fonts/MATERIAL_SYMBOLS_LICENSE.txt' }, { rootfs: 'usr/share/common-licenses/Apache-2.0' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S5.17', reason: 'none; declared and actual hash agree' },
    },
    {
      id: 'noto-sans-sc-paper-export', kind: 'font', category: 'font', deliveryUnit: 'S4.05',
      repo: 'desktop', files: ['public/fonts/paper-export/NotoSansSC-Regular.ttf', 'public/fonts/paper-export/NotoSansSC-Bold.ttf', 'public/fonts/paper-export/OFL.txt', 'public/fonts/paper-export/README.md'],
      source: { type: 'in-tree-source', commit: '145bb3879164c58d1cd5ff39daa94a3daa9fe874', declared: true, note: 'static instances generated from NotoSansSC-VF.ttf with fontTools 4.67.0' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'paper export fonts' },
      packaging: { method: 'desktop-only', destination: null, shipped: false, reproducible: false },
      license: { status: 'resolved', id: 'OFL-1.1', basis: 'vendored OFL.txt', texts: [{ repo: 'desktop', path: 'public/fonts/paper-export/OFL.txt' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S4.05', reason: 'Android PDF export must embed the same fonts or record a replacement' },
    },
    {
      id: 'shower-server-jar', kind: 'prebuilt-apk', category: 'shower', deliveryUnit: 'S6.41',
      repo: 'operit', files: ['showerclient/src/main/assets/shower-server.jar', 'tools/shower/build_server.bat', 'tools/shower/app/build.gradle.kts'],
      source: { type: 'in-tree-source', commit: '7a39941bad80209973c535046a18195c5da9e647', declared: true, version: 'tools/shower versionName 1.0, versionCode 1', note: 'jar is the release APK copied by build_server.bat; no build hash recorded' },
      build: { script: 'tools/shower/build_server.bat', abi: ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], pageSizeDeclared: 'binary', output: 'shower-server.jar (APK)' },
      packaging: { method: 'assets', destination: 'showerclient/src/main/assets/shower-server.jar', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'Apache-2.0 + LGPL-3.0', basis: 'about screen lists scrcpy Apache-2.0; tools/shower has no LICENSE/NOTICE', texts: [{ repo: 'operit', path: 'LICENSE' }], note: 'derivation from scrcpy is claimed in about screen but not documented in tools/shower' },
      notice: { required: true, action: 'review' },
      followUp: { unit: 'S6.41', reason: 'record source commit, APK hash, scrcpy derivation and attribution; add NOTICE' },
    },
    {
      id: 'operit-shell-exec', kind: 'prebuilt-native', category: 'system', deliveryUnit: 'S6.21',
      repo: 'operit', files: ['app/src/main/assets/operit_shell_exec', 'tools/shell_identity_launcher/native-lib.cpp', 'tools/shell_identity_launcher/CMakeLists.txt', 'tools/shell_identity_launcher/build_android.bat'],
      source: { type: 'in-tree-source', commit: '7a39941bad80209973c535046a18195c5da9e647', declared: true, note: 'launcher copied from assets by RootShellExecutor' },
      build: { script: 'tools/shell_identity_launcher/build_android.bat', abi: ['arm64-v8a'], pageSizeDeclared: 'none', output: 'app/src/main/assets/operit_shell_exec' },
      packaging: { method: 'assets', destination: 'assets/operit_shell_exec -> files/bin/operit_shell_exec', shipped: true, reproducible: false },
      license: { status: 'project', id: 'LGPL-3.0', basis: 'Operit-authored launcher', texts: [{ repo: 'operit', path: 'LICENSE' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S6.21', reason: 'prebuilt binary LOAD alignment is 0x1000; rebuild with 16 KiB page-size flags and record hash' },
    },
    {
      id: 'shizuku-apk', kind: 'prebuilt-apk', category: 'system', deliveryUnit: 'S6.18',
      repo: 'operit', files: ['app/src/main/assets/shizuku.apk', 'app/src/main/assets/shizuku_version.txt', 'app/src/main/assets/README.md'],
      source: { type: 'url', declared: true, version: '13.6.0', url: 'https://github.com/RikkaApps/Shizuku/releases', note: 'README instructs downloading the latest release; only version txt pins the bundled copy' },
      build: { script: null, abi: ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], pageSizeDeclared: 'binary', output: 'shizuku.apk' },
      packaging: { method: 'assets', destination: 'assets/shizuku.apk; installed on user action', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'README and about screen; no license text in APK or repo', texts: [] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S6.18', reason: 'record release tag, APK hash, signature and Apache-2.0 text' },
    },
    {
      id: 'accessibility-apk', kind: 'prebuilt-apk', category: 'system', deliveryUnit: 'S6.22',
      repo: 'operit', files: ['app/src/main/assets/accessibility.apk', 'app/src/main/assets/accessibility_version.txt', 'app/src/main/java/com/ai/assistance/operit/core/tools/system/AccessibilityProviderInstaller.kt'],
      source: { type: 'in-tree-build-missing', commit: '7a39941bad80209973c535046a18195c5da9e647', declared: false, version: '1.5', note: 'package com.ai.assistance.operit.provider; no provider module or build recipe in repo' },
      build: { script: null, abi: ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], pageSizeDeclared: 'binary', output: 'accessibility.apk' },
      packaging: { method: 'assets', destination: 'assets/accessibility.apk; installed on user action', shipped: true, reproducible: false },
      license: { status: 'unknown', id: null, basis: 'source and build recipe absent', texts: [] },
      notice: { required: true, action: 'review' },
      followUp: { unit: 'S6.22', reason: 'source module, build recipe and license text are missing' },
    },
    {
      id: 'desktop-apk', kind: 'prebuilt-apk', category: 'system', deliveryUnit: 'S0.15',
      repo: 'operit', files: ['app/src/main/assets/desktop.apk', 'app/src/main/assets/desktop_version.txt'],
      source: { type: 'unknown', declared: false, version: '1.0', note: 'no source consumer found in Operit source' },
      build: { script: null, abi: ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], pageSizeDeclared: 'binary', output: 'desktop.apk' },
      packaging: { method: 'assets', destination: 'assets/desktop.apk (no consumer)', shipped: true, reproducible: false },
      license: { status: 'unknown', id: null, basis: 'provenance and license unknown', texts: [] },
      notice: { required: true, action: 'review' },
      followUp: { unit: 'S0.15', reason: 'disposition decision: remove unused APK or document source/license' },
    },
    {
      id: 'subpack-keystores', kind: 'keystore', category: 'system', deliveryUnit: 'S6.35',
      repo: 'operit', files: ['app/src/main/assets/jks.jks', 'app/src/main/assets/pkcs12.keystore', 'app/src/main/java/com/ai/assistance/operit/core/subpack/KeyStoreHelper.kt'],
      source: { type: 'in-tree-generated', commit: '7a39941bad80209973c535046a18195c5da9e647', declared: false, note: 'KeyStoreHelper validates both with password "android"' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'assets keystores' },
      packaging: { method: 'assets', destination: 'assets/jks.jks, assets/pkcs12.keystore', shipped: true, reproducible: false },
      license: { status: 'project', id: 'LGPL-3.0', basis: 'project-generated keystores', texts: [{ repo: 'operit', path: 'LICENSE' }] },
      notice: { required: false, action: 'none' },
      followUp: { unit: 'S6.35', reason: 'known password "android" and generation procedure must be reviewed before package signing reuse' },
    },
    {
      id: 'aapt2-arm64', kind: 'prebuilt-native', category: 'build-support', deliveryUnit: 'S6.15',
      repo: 'operit', files: ['app/src/main/assets/templates/android/tools/aapt2/aapt2-arm64-v8a', 'app/src/main/assets/templates/flutter/android/tools/aapt2/aapt2-arm64-v8a', 'app/src/main/assets/templates/android/setup_android_env.sh', 'app/src/main/assets/templates/flutter/android/setup_android_env.sh'],
      source: { type: 'prebuilt-committed', commit: '7a39941bad80209973c535046a18195c5da9e647', declared: true, declaredSha256: 'e5b5ff7f0d4f6ecd7fa5d05d77fed3f09f6f1bf80f078b8aada82bc578848561', note: 'both template scripts declare the same expected hash' },
      build: { script: null, abi: ['arm64-v8a'], pageSizeDeclared: 'binary', output: 'aapt2-arm64-v8a' },
      packaging: { method: 'assets', destination: 'templates/**/tools/aapt2/aapt2-arm64-v8a', shipped: true, reproducible: false },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'AOSP build tool; no local license text', texts: [{ rootfs: 'usr/share/common-licenses/Apache-2.0' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S6.15', reason: 'prebuilt LOAD alignment is 0x1000; rebuild from pinned AOSP/NDK and record recipe' },
    },
    {
      id: 'gradle-wrapper-jars', kind: 'build-tool', category: 'build-support', deliveryUnit: 'S1.01',
      repo: 'operit', files: ['gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties', 'tools/shower/gradle/wrapper/gradle-wrapper.jar', 'app/src/main/assets/templates/android/gradle/wrapper/gradle-wrapper.jar'],
      source: { type: 'generated-wrapper', declared: true, version: 'Gradle 8.13', note: 'root properties pin distributionSha256Sum 20f1b117...' },
      build: { script: 'gradle/wrapper/gradle-wrapper.properties', abi: [], pageSizeDeclared: 'n/a', output: 'gradle-wrapper.jar' },
      packaging: { method: 'build-tooling', destination: 'not packaged in APK', shipped: false, reproducible: true },
      license: { status: 'declared-unverified', id: 'Apache-2.0', basis: 'Gradle wrapper manifest; no local license text', texts: [{ rootfs: 'usr/share/common-licenses/Apache-2.0' }] },
      notice: { required: true, action: 'include-text' },
      followUp: { unit: 'S1.01', reason: 'record wrapper provenance and distribution hash in the new build' },
    },
    {
      id: 'examples-apktool-jars', kind: 'example-asset', category: 'examples', deliveryUnit: 'S0.15',
      repo: 'operit', files: ['examples/apktool/resources/apktool/apktool-runtime-android.jar', 'examples/apktool/resources/apktool/jadx-runtime-android.jar', 'examples/apktool/resources/apktool/android-framework.jar', 'examples/apktool/resources/apktool/apk-reverse-helper-runtime-android.jar'],
      source: { type: 'prebuilt-committed', commit: '7a39941bad80209973c535046a18195c5da9e647', declared: false, note: 'examples only; not packaged in the app' },
      build: { script: null, abi: [], pageSizeDeclared: 'n/a', output: 'example jars' },
      packaging: { method: 'not-packaged', destination: null, shipped: false, reproducible: false },
      license: { status: 'unknown', id: null, basis: 'no license manifest for the four jars', texts: [] },
      notice: { required: false, action: 'review' },
      followUp: { unit: 'S0.15', reason: 'examples are not shipped; record disposition and remove if unused' },
    },
  ];
}

function coverageKey(repo, path) {
  if (repo === 'operit' && path.startsWith('terminal/')) return `terminal:${path.slice('terminal/'.length)}`;
  return `${repo}:${path}`;
}

function scanCoverage(specs, groups) {
  const roots = {
    operit: ['app/src/main/assets', 'llm/mnn/src/main/jniLibs', 'showerclient/src/main/assets', 'examples/apktool/resources', 'gradle/wrapper', 'tools/shower/gradle/wrapper'],
    terminal: ['src/main'],
    desktop: ['public/fonts'],
  };
  const covered = new Set();
  for (const spec of specs) for (const path of spec.files) covered.add(coverageKey(spec.repo, path));
  for (const group of groups) for (const path of group.paths) covered.add(coverageKey(group.repo, path));
  const unclassified = [];
  const scanned = [];
  for (const [repo, prefixes] of Object.entries(roots)) {
    const cwd = repoRoots[repo];
    const files = prefixes.length === 0 ? [] : gitZ(cwd, 'ls-files', '-z', '--', ...prefixes);
    for (const path of files) {
      const sensitive = sensitivePathReason(path);
      const key = coverageKey(repo, path);
      if (sensitive !== null) {
        const bytes = statSync(resolve(cwd, path)).size;
        scanned.push({ repo, path, format: 'sensitive-excluded', bytes, excluded: sensitive });
        if (!covered.has(key)) unclassified.push({ repo, path, format: 'sensitive-excluded', bytes, excluded: sensitive });
        continue;
      }
      const buffer = readFileSync(resolve(cwd, path));
      const format = magic(buffer);
      const extension = path.includes('.') ? path.split('.').pop().toLowerCase() : '';
      const binaryish = ['elf', 'zip', 'xz', 'woff2', 'truetype', 'der', 'short'].includes(format)
        || ['js', 'onnx', 'bin', 'param', 'so', 'jar', 'apk', 'aab', 'aar', 'ttf', 'otf', 'woff', 'woff2', 'xz', 'model', 'gguf', 'tflite', 'mp3', 'mp4'].includes(extension);
      if (!binaryish) continue;
      scanned.push({ repo, path, format, bytes: buffer.length });
      if (!covered.has(coverageKey(repo, path))) unclassified.push({ repo, path, format, bytes: buffer.length });
    }
  }
  assert.deepEqual(unclassified, [], `unclassified tracked binary assets: ${JSON.stringify(unclassified.slice(0, 10))}`);
  return { scanned, unclassified, roots };
}

function groupsForCoverage() {
  const list = (repo, prefix) => gitZ(repoRoots[repo], 'ls-files', '-z', '--', prefix).map(path => ({ repo, path }));
  const rows = [
    ['emoji', 'operit', 'app/src/main/assets/emoji', 'Operit bundled emoji reactions; brand/content assets, not runtime engines', 'S0.12'],
    ['model-logos', 'operit', 'app/src/main/assets/model_logos', 'Provider/model logos used to identify vendors; trademark review', 'S0.15'],
    ['toolpkg-packages', 'operit', 'app/src/main/assets/packages', 'Project-authored ToolPkg example packages', 'S6.33'],
    ['templates', 'operit', 'app/src/main/assets/templates', 'In-app Android/Flutter project templates; aapt2 and wrapper jar are listed separately', 'S6.15'],
    ['test-fixtures', 'operit', 'app/src/main/assets/test', 'Bundled media fixtures (jpg/mp3/mp4)', 'S0.12'],
    ['web-assets', 'operit', 'app/src/main/assets/web', 'Bundled HTML template', 'S5.17'],
    ['root-assets', 'operit', 'app/src/main/assets/operit.png', 'App logo raster', 'S0.12'],
    ['root-assets', 'operit', 'app/src/main/assets/logo.svg', 'App logo vector', 'S0.12'],
    ['placeholders', 'operit', 'app/src/main/assets/models/.keep', 'Empty models directory placeholder; real models are downloaded', 'S4.18'],
    ['placeholders', 'operit', 'app/src/main/assets/subpack/.keep', 'Empty subpack directory placeholder', 'S6.35'],
    ['placeholders', 'operit', 'app/src/main/assets/dragonbones/models/.keep', 'Empty avatar model placeholder', 'S0.13'],
    ['placeholders', 'operit', 'app/src/main/assets/pets/.keep', 'Empty pet asset placeholder', 'S0.12'],
  ];
  return rows.map(([id, repo, prefix, note, deliveryUnit]) => ({ id, repo, note, deliveryUnit, paths: list(repo, prefix).map(item => item.path) }));
}

function noticeDocument(notices, snapshot) {
  const lines = [];
  lines.push('dstu-android third-party NOTICE');
  lines.push('');
  lines.push('This file is generated by tools/baseline/assets.mjs (S0.14). Do not edit by hand.');
  lines.push(`Machine-readable inventory: ${file}`);
  lines.push(`Machine-readable notices: ${noticesFile}`);
  lines.push(`Operit commit: ${snapshot.evidence.sourceLock.operitCommit}`);
  lines.push(`terminal commit: ${snapshot.evidence.sourceLock.terminalCommit}`);
  lines.push(`Rootfs archive SHA-256: ${snapshot.rootfs.sha256}`);
  lines.push('');
  lines.push('Desktop evidence boundary (not an Android license clearance):');
  lines.push(`- desktop commit: ${snapshot.evidence.desktopLicense.desktopCommit}`);
  lines.push(`- ACKNOWLEDGMENTS.md: ${snapshot.evidence.desktopLicense.acknowledgments.path} sha256=${snapshot.evidence.desktopLicense.acknowledgments.sha256}; checked as upstream desktop evidence only`);
  lines.push('- Node.js, Electron and React remain desktop implementation/runtime evidence; this project ships a Kotlin/Compose Android app');
  lines.push('- Vue 3.5.40 is a desktop development dependency and is not an Android APK dependency');
  lines.push('- The upstream demo PDF is promo-film evidence only and is not-adopted as a runtime asset');
  lines.push(`- Noto Sans SC stays a non-adopted runtime resource generated at ${snapshot.evidence.desktopLicense.fonts.generationCommit}; S0 has no APK, and any future Android PDF export evidence remains S4.05`);
  lines.push('- This notice does not assert that unresolved licenses are cleared; unresolved entries stay listed below');
  lines.push('');
  lines.push('License text source references:');
  for (const text of notices.licenseTexts) {
    const location = text.rootfs ? `rootfs:${text.rootfs}` : `${text.repo}:${text.path}`;
    lines.push(`- ${text.id} ${location} sha256=${text.sha256}`);
  }
  lines.push('');
  lines.push('Shipped components:');
  for (const component of notices.components.filter(item => item.distribution === 'shipped')) {
    const license = component.license ?? 'UNRESOLVED';
    lines.push(`- ${component.id} | ${license} | ${component.status} | ${component.copyright}`);
    for (const ref of component.textRefs) lines.push(`    text: ${ref.rootfs ? `rootfs:${ref.rootfs}` : `${ref.repo}:${ref.path}`} sha256=${ref.sha256 ?? 'MISSING'}`);
  }
  lines.push('');
  lines.push('Unresolved items that must be closed before release:');
  for (const component of notices.components.filter(item => item.status === 'unresolved')) {
    lines.push(`- ${component.id} | ${component.followUp.unit} | ${component.followUp.reason}`);
  }
  lines.push('');
  return `${lines.join('\n')}`;
}

function buildAssets() {
  const declarations = buildDeclarations();
  const stt = sttManifest();
  const specs = assetSpecs();
  const groups = groupsForCoverage();
  const rootfs = rootfsFacts();
  const adoption = declarations.adoption;
  assert.equal(adoption.rootfs.sha256, rootfs.sha256, 'adoption rootfs hash');
  assert.equal(adoption.rootfs.bytes, rootfs.bytes, 'adoption rootfs bytes');
  assert.equal(adoption.rootfs.entries, rootfs.entries, 'adoption rootfs entries');
  assert.equal(adoption.rootfs.codename, rootfs.codename, 'adoption rootfs codename');
  assert.equal(adoption.rootfs.consistent, rootfs.consistent, 'adoption rootfs consistency');

  const assets = specs.map(spec => {
    const files = spec.files.map(path => assetFile(spec.repo, path));
    const present = files.filter(item => item.present);
    const elfFiles = present.filter(item => item.format === 'elf');
    const nested = present.filter(item => item.format === 'zip' && (item.path.endsWith('.apk') || item.path.endsWith('.jar')))
      .map(item => ({ path: item.path, entries: zipNestedElf(readRepo(spec.repo, item.path)) }))
      .filter(item => item.entries.length > 0);
    const zipFacts = present.filter(item => item.format === 'zip' && (item.path.endsWith('.apk') || item.path.endsWith('.jar')))
      .map(item => ({ path: item.path, entries: parseZipEntries(readRepo(spec.repo, item.path)).length }));
    const hashConsistency = [];
    if (spec.id === 'material-symbols-font') {
      const declared = declaredHashCheck(readRepo('operit', 'app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/part/StatusCardHtmlDocument.kt').toString('utf8'), /SHA-256：([0-9a-f]{64})/, 'material symbols');
      hashConsistency.push({ source: 'StatusCardHtmlDocument.kt', declared, actual: files[0].sha256, match: declared === files[0].sha256 });
    }
    if (spec.id === 'aapt2-arm64') {
      for (const script of ['app/src/main/assets/templates/android/setup_android_env.sh', 'app/src/main/assets/templates/flutter/android/setup_android_env.sh']) {
        const declared = declaredHashCheck(readRepo('operit', script).toString('utf8'), /local expected_sha256="([0-9a-f]{64})"/, script);
        hashConsistency.push({ source: script, declared, actual: files[0].sha256, match: declared === files[0].sha256 });
      }
    }
    const pageDeclared = spec.build.pageSizeDeclared;
    return {
      id: spec.id,
      kind: spec.kind,
      category: spec.category,
      deliveryUnit: spec.deliveryUnit,
      repo: spec.repo,
      files,
      present: present.length,
      missing: files.length - present.length,
      source: spec.source,
      build: spec.build,
      packaging: spec.packaging,
      license: spec.license,
      notice: spec.notice,
      followUp: spec.followUp,
      elf: elfFiles.length === 0 ? null : elfFiles.map(item => ({ path: item.path, ...item.elf })),
      nestedElf: nested,
      zipEntries: zipFacts,
      hashConsistency,
      pageSizeDeclared: pageDeclared,
      page16kFacts: elfFiles.length === 0 && nested.length === 0 ? null : {
        checked: elfFiles.length + nested.reduce((total, item) => total + item.entries.length, 0),
        pass: elfFiles.filter(item => item.elf.page16k).length + nested.reduce((total, item) => total + item.entries.filter(entry => entry.elf.page16k).length, 0),
        fail: elfFiles.filter(item => !item.elf.page16k).length + nested.reduce((total, item) => total + item.entries.filter(entry => !entry.elf.page16k).length, 0),
      },
    };
  });
  const desktopLicense = desktopLicenseBoundary(declarations.lock);

  const coverage = scanCoverage(specs, groups);
  const elfAssets = assets.flatMap(asset => (asset.elf ?? []).map(item => ({ assetId: asset.id, path: item.path, ...item })));
  const nestedAssets = assets.flatMap(asset => asset.nestedElf.flatMap(item => item.entries.map(entry => ({
    assetId: asset.id,
    path: `${item.path}!${entry.name}`,
    bytes: entry.bytes,
    class: entry.elf.class,
    little: entry.elf.little,
    type: entry.elf.type,
    machine: entry.elf.machine,
    loadAligns: entry.elf.loadAligns,
    minLoadAlign: entry.elf.minLoadAlign,
    page16k: entry.elf.page16k,
    android: entry.elf.android,
    needed: entry.elf.needed,
    loads: entry.elf.loads,
  }))));
  const elf16k = [...elfAssets, ...nestedAssets];
  const page16kPass = elf16k.filter(item => item.page16k);
  const page16kFail = elf16k.filter(item => !item.page16k);
  const licenseTexts = buildLicenseTexts(rootfs);
  const notices = buildNotices(assets, rootfs, licenseTexts, desktopLicense);

  const checks = {
    assets: assets.length,
    files: assets.reduce((total, asset) => total + asset.files.length, 0),
    sensitiveExcludedFiles: assets.reduce((total, asset) => total + asset.files.filter(item => item.format === 'sensitive-excluded').length, 0),
    sensitiveExcludedScanned: coverage.scanned.filter(item => item.excluded).length,
    presentFiles: assets.reduce((total, asset) => total + asset.present, 0),
    missingFiles: assets.reduce((total, asset) => total + asset.missing, 0),
    elfChecked: elf16k.length,
    elf16kPass: page16kPass.length,
    elf16kFail: page16kFail.length,
    nestedElfChecked: nestedAssets.length,
    pageDeclaredMaxOnly: assets.filter(asset => asset.pageSizeDeclared === 'max-only').length,
    pageDeclaredMaxAndCommon: assets.filter(asset => asset.pageSizeDeclared === 'max+common').length,
    pageDeclaredNone: assets.filter(asset => asset.pageSizeDeclared === 'none').length,
    scanRoots: coverage.roots,
    scannedBinaryFiles: coverage.scanned.length,
    unclassifiedBinaryFiles: coverage.unclassified.length,
    licenseResolved: assets.filter(asset => ['resolved', 'project'].includes(asset.license.status)).length,
    licenseDeclaredUnverified: assets.filter(asset => asset.license.status === 'declared-unverified').length,
    licenseUnknown: assets.filter(asset => asset.license.status === 'unknown').length,
    licenseAggregate: assets.filter(asset => asset.license.status === 'aggregate').length,
    noticeRequired: assets.filter(asset => asset.notice.required).length,
    noticeResolved: notices.components.filter(component => component.status === 'resolved').length,
    noticeUnresolved: notices.components.filter(component => component.status === 'unresolved').length,
    sourceGaps: assets.filter(asset => asset.source.declared === false || asset.build.script === null && ['prebuilt-native', 'prebuilt-apk', 'prebuilt-committed'].includes(asset.kind)).length,
    followUps: assets.filter(asset => asset.followUp?.reason && !asset.followUp.reason.startsWith('none')).length,
    rootfsPackages: rootfs.packages,
    rootfsCopyrightFiles: rootfs.copyrightFiles.length,
    rootfsUnresolvedCopyright: rootfs.unresolved.length,
    rootfsCommonLicenses: rootfs.commonLicenses.length,
    rootfsCopyrightLicenseFields: rootfs.copyrightFiles.filter(item => item.source === 'license-field').length,
    rootfsCopyrightFamiliesKnown: rootfs.packageRows.filter(row => row.copyright !== null && row.copyright.families.length > 0).length,
    rootfsCopyrightFamiliesNone: rootfs.packageRows.filter(row => row.copyright !== null && row.copyright.families.length === 0).length,
    rootfsSamples: rootfs.samples.length,
    rootfsSample16kPass: rootfs.samples.filter(sample => sample.present && sample.elf?.page16k).length,
    rootfsTraversal: rootfs.traversal.length,
    sttFiles: stt.rows.length,
    hashChecks: assets.reduce((total, asset) => total + asset.hashConsistency.length, 0),
    hashChecksPassed: assets.reduce((total, asset) => total + asset.hashConsistency.filter(item => item.match).length, 0),
  };
  for (const asset of assets) for (const item of asset.hashConsistency) assert.equal(item.match, true, `${asset.id}: declared hash drift vs ${item.source}`);
  assert.equal(checks.unclassifiedBinaryFiles, 0, 'all tracked binary assets classified');
  assert.equal(checks.rootfsTraversal, 0, 'rootfs path traversal');
  assert.equal(rootfs.consistent, true, 'rootfs codename consistency');

  return {
    formatVersion: 1,
    unit,
    delivery: { assets: unit, notices: noticesFile, next: 'S0.15' },
    evidence: {
      plan: { file: planFile, unitLine: declarations.unitLine, sections: { section33: declarations.section33, section54: declarations.section54 } },
      sourceLock: {
        file: lockFile,
        sha256: sha256(readFileSync(resolve(root, lockFile))),
        desktopCommit: declarations.lock.repositories.find(item => item.id === 'desktop').commit,
        desktopTree: declarations.lock.repositories.find(item => item.id === 'desktop').tree,
        operitCommit: declarations.operitRepo.commit,
        operitTree: declarations.operitRepo.tree,
        terminalCommit: declarations.terminalRepo.commit,
        terminalTree: declarations.terminalRepo.tree,
      },
      adoption: {
        file: adoptionFile,
        sha256: sha256(readFileSync(resolve(root, adoptionFile))),
        unit: adoption.unit,
        rootfsSha256: adoption.rootfs.sha256,
        rootfsEntries: adoption.rootfs.entries,
        rootfsCodename: adoption.rootfs.codename,
      },
      declarations: declarations.declarations,
      sttManifest: { path: stt.path, sha256: stt.sha256, rows: stt.rows },
      desktopLicense,
    },
    policy: {
      source: '每项记录实际 hash；预编译来源缺失时登记 declared=false 与所需证据，不填写推测来源或许可证',
      page: '16 KiB 检查基于 ELF PT_LOAD p_align；p_align < 0x4000 记为失败；构建脚本只声明 max-page-size 时记为 max-only',
      rootfs: 'dpkg status、lsb/os-release、apt sources 与 181 个 copyright、14 个 common-licenses 均由 tar 选择性只读解出后计算 SHA-256，并与 6 个样本 ELF 一同放入临时目录，结束后删除；不整包解压',
      sensitive: '路径先于读取分类；jks/keystore/p12/pfx/bks/p8/key/pem 与私钥文件只记录受跟踪路径、stat 大小与 present，不读取字节、不计算哈希',
      notices: '许可文本只引用已存在的原文路径与 SHA-256；缺少文本的许可证标记为 unresolved',
    },
    assets,
    groups,
    page16k: {
      rule: 'PT_LOAD p_align >= 0x4000 passes; nested APK/JAR arm64 libraries are checked from the archive bytes without extraction',
      pass: page16kPass.map(item => ({ assetId: item.assetId, path: item.path, minLoadAlign: item.minLoadAlign })),
      fail: page16kFail.map(item => ({ assetId: item.assetId, path: item.path, minLoadAlign: item.minLoadAlign })),
    },
    coverage: { roots: coverage.roots, scanned: coverage.scanned.length, unclassified: coverage.unclassified },
    rootfs: {
      asset: rootfs.asset,
      bytes: rootfs.bytes,
      sha256: rootfs.sha256,
      entries: rootfs.entries,
      types: rootfs.types,
      traversal: rootfs.traversal,
      release: rootfs.release,
      codename: rootfs.codename,
      osCodename: rootfs.osCodename,
      aptCodename: rootfs.aptCodename,
      consistent: rootfs.consistent,
      packages: rootfs.packages,
      packageRows: rootfs.packageRows,
      unresolved: rootfs.unresolved,
      copyrightFiles: rootfs.copyrightFiles,
      commonLicenses: rootfs.commonLicenses,
      symlinks: rootfs.symlinks,
      samples: rootfs.samples,
    },
    licenseTexts,
    externalArtifacts: [
      { id: 'libpty.so', declaredBy: 'terminal/src/main/jni/CMakeLists.txt', obtained: false, evidence: 'build with Android NDK at the pinned terminal commit; verify PT_LOAD alignment' },
      { id: 'libquickjsjni.so', declaredBy: 'quickjs/src/main/cpp/CMakeLists.txt', obtained: false, evidence: 'build after fetching bellard/quickjs e585640; verify alignment' },
      { id: 'libsherpa-ncnn-jni.so (Gradle build)', declaredBy: 'app/src/main/cpp/CMakeLists.txt', obtained: false, evidence: 'build sherpa-ncnn 497b832; verify alignment (no 16 KiB flag in CMake)' },
      { id: 'libtoolpkgwasm.so', declaredBy: 'app/src/main/cpp/CMakeLists.txt', obtained: false, evidence: 'build WAMR ffc2288; verify alignment' },
      { id: 'libMNN.so, libMNNWrapper.so, libLlamaWrapper.so, libdragonbones_native.so, libFbxWrapper.so, libMmdWrapper.so', declaredBy: 'llm/*/CMakeLists.txt, avator/*/CMakeLists.txt', obtained: false, evidence: 'build each engine; avatar modules are not adopted' },
      { id: 'ffmpeg-kit-local.aar', declaredBy: 'app/build.gradle.kts:155', obtained: false, evidence: 'run tools/ffmpeg/build_ffmpeg_kit_wsl.sh then import_local_ffmpeg_kit.ps1; record AAR SHA-256' },
      { id: 'liboperit_ripgrep.so', declaredBy: 'app/build.gradle.kts:150', obtained: false, evidence: 'not adopted; Cargo.lock is the only reproducible input' },
      { id: 'stt model files (8)', declaredBy: 'app/config/stt-model-assets.properties', obtained: false, evidence: 'Gradle syncSttModelAssets downloads and verifies bytes/SHA-256' },
      { id: 'libs.zip, subpack.zip, jniLibs.zip', declaredBy: 'ci/script/download_android_dependencies.sh', obtained: false, evidence: 'fixed Google Drive IDs without content hashes; obtain and hash before packaging' },
    ],
    checks,
  };
}

function buildLicenseTexts(rootfs) {
  const texts = [
    { id: 'LGPL-3.0', repo: 'operit', path: 'LICENSE', sha256: sha256Of('operit', 'LICENSE'), standard: true, note: 'Operit project license; byte-identical to Ubuntu common-licenses/LGPL-3' },
    { id: 'LGPL-3.0-terminal-variant', repo: 'terminal', path: 'LICENSE', sha256: sha256Of('terminal', 'LICENSE'), standard: false, note: 'terminal submodule license; GPL-3 body omitted at line 211' },
    { id: 'AGPL-3.0', repo: 'desktop', path: 'LICENSE', sha256: sha256Of('desktop', 'LICENSE'), standard: true, note: 'dstu-node project license' },
    { id: 'Apache-2.0', repo: 'operit', path: 'app/src/main/assets/fonts/MATERIAL_SYMBOLS_LICENSE.txt', sha256: sha256Of('operit', 'app/src/main/assets/fonts/MATERIAL_SYMBOLS_LICENSE.txt'), standard: true, note: 'byte-identical to Ubuntu common-licenses/Apache-2.0' },
    { id: 'MIT', repo: 'operit', path: 'app/src/main/assets/js/katex.LICENSE.txt', sha256: sha256Of('operit', 'app/src/main/assets/js/katex.LICENSE.txt'), standard: true, note: 'KaTeX MIT text' },
    { id: 'BSD-2-Clause', repo: 'operit', path: 'app/src/main/assets/js/terser.LICENSE', sha256: sha256Of('operit', 'app/src/main/assets/js/terser.LICENSE'), standard: false, note: 'Terser/UglifyJS BSD text without SPDX header' },
    { id: 'OFL-1.1', repo: 'desktop', path: 'public/fonts/paper-export/OFL.txt', sha256: sha256Of('desktop', 'public/fonts/paper-export/OFL.txt'), standard: true, note: 'Noto Sans SC OFL text; usable as the OFL-1.1 reference text' },
  ];
  for (const item of rootfs.commonLicenses) {
    const name = basename(item.path);
    texts.push({ id: `Ubuntu-common-${name}`, rootfs: item.path, sha256: item.sha256, standard: name !== 'Artistic', note: 'read-only stream from rootfs archive' });
  }
  return texts;
}

function buildNotices(assets, rootfs, licenseTexts, desktopLicense) {
  const components = assets.map(asset => {
    const unresolved = ['unknown', 'declared-unverified', 'mixed', 'aggregate'].includes(asset.license.status);
    const textRefs = asset.license.texts.map(item => {
      if (item.rootfs) {
        const found = rootfs.commonLicenses.find(entry => entry.path === item.rootfs);
        return found ? { rootfs: item.rootfs, sha256: found.sha256 } : { rootfs: item.rootfs, sha256: null, missing: true };
      }
      return { repo: item.repo, path: item.path, sha256: sha256Of(item.repo, item.path) };
    });
    const hasText = textRefs.length > 0 && textRefs.every(ref => ref.sha256 !== null);
    const status = !asset.notice.required ? 'not-required' : unresolved || !hasText ? 'unresolved' : 'resolved';
    return {
      id: asset.id,
      name: asset.id,
      kind: asset.kind,
      deliveryUnit: asset.deliveryUnit,
      distribution: asset.packaging.shipped ? 'shipped' : 'not-shipped',
      license: asset.license.id,
      licenseStatus: asset.license.status,
      copyright: asset.license.basis,
      textRefs,
      noticeAction: asset.notice.action,
      status,
      followUp: asset.followUp,
    };
  });
  components.push({
    id: 'ubuntu-noble-rootfs',
    name: 'Ubuntu 24.04.1 LTS noble rootfs',
    kind: 'rootfs-archive',
    deliveryUnit: 'S6.09',
    distribution: 'shipped',
    license: 'mixed-ubuntu-noble',
    licenseStatus: 'aggregate',
    copyright: `${rootfs.packages} installed packages; ${rootfs.copyrightFiles.length} copyright files; ${rootfs.unresolved.length} unresolved`,
    textRefs: rootfs.commonLicenses.map(item => ({ rootfs: item.path, sha256: item.sha256 })),
    noticeAction: 'aggregate-notice-and-source-offer',
    status: rootfs.unresolved.length === 0 ? 'resolved' : 'unresolved',
    followUp: { unit: 'S6.09', reason: 'per-package source offer and unresolved copyrights' },
  });
  const summary = {
    components: components.length,
    resolved: components.filter(component => component.status === 'resolved').length,
    unresolved: components.filter(component => component.status === 'unresolved').length,
    notRequired: components.filter(component => component.status === 'not-required').length,
    licenseTexts: licenseTexts.length,
  };
  return {
    formatVersion: 1,
    unit,
    generatedFrom: file,
    distributionScopes: ['apk', 'terminal-env', 'desktop-only', 'build-only', 'not-shipped'],
    desktopEvidence: desktopLicense,
    licenseTexts,
    components,
    summary,
    checks: {
      unknownLicenseWithoutReview: components.filter(component => component.licenseStatus === 'unknown' && component.status === 'resolved').length,
      unresolvedMissingText: components.filter(component => component.status === 'unresolved').length,
    },
  };
}

function selfChecks(assets, rootfs, stt, notices) {
  const synthetic = Buffer.alloc(64 + 56);
  synthetic.writeUInt32BE(0x7f454c46, 0);
  synthetic[4] = 2;
  synthetic[5] = 1;
  synthetic.writeUInt16LE(2, 16);
  synthetic.writeUInt16LE(183, 18);
  synthetic.writeUInt32LE(1, 20);
  synthetic.writeBigUInt64LE(64n, 32);
  synthetic.writeUInt16LE(64, 52);
  synthetic.writeUInt16LE(56, 54);
  synthetic.writeUInt16LE(1, 56);
  synthetic.writeUInt32LE(1, 64);
  synthetic.writeUInt32LE(5, 68);
  synthetic.writeBigUInt64LE(0n, 72);
  synthetic.writeBigUInt64LE(0n, 80);
  synthetic.writeBigUInt64LE(0n, 88);
  synthetic.writeBigUInt64LE(0x100n, 96);
  synthetic.writeBigUInt64LE(0x100n, 104);
  synthetic.writeBigUInt64LE(0x1000n, 112);
  const syntheticElf = parseElf(synthetic);
  const rejectsPageAlignment = syntheticElf !== null && syntheticElf.page16k === false;

  const rejectsMissingAsset = (() => {
    try {
      assetFile('operit', 'does/not/exist');
      return false;
    } catch {
      return true;
    }
  })();
  const hash = assets.find(asset => asset.id === 'terminal-libbash').files[0].sha256;
  const rejectsHashDrift = difference({ sha256: hash }, { sha256: '0'.repeat(64) })?.path === '$.sha256';
  const rejectsManifestDrift = (() => {
    try {
      const lines = stt.rows.map(row => `${row.target}|${row.url}|${row.bytes}|${row.sha256}|${row.source}|${row.licenseNote}`);
      lines[0] = lines[0].replace(/\|[0-9a-f]{64}\|/, `|${'0'.repeat(64)}|`);
      const parts = lines[0].split('|');
      assert.equal(parts[3], stt.rows[0].sha256);
      return false;
    } catch {
      return true;
    }
  })();
  const rejectsRootfsCount = (() => {
    try {
      assert.equal(rootfs.packages, rootfs.packages - 1);
      return false;
    } catch {
      return true;
    }
  })();
  const rejectsUnresolvedFabrication = (() => {
    try {
      const unknown = notices.components.find(component => component.licenseStatus === 'unknown');
      assert.ok(unknown, 'unknown license component');
      assert.notEqual(unknown.status, 'resolved');
      assert.throws(() => assert.equal(unknown.status, 'resolved'));
      return true;
    } catch {
      return false;
    }
  })();
  const rejectsSensitiveRead = (() => {
    try {
      readRepo('operit', 'app/src/main/assets/jks.jks');
      return false;
    } catch (error) {
      return /refused to read private key material/.test(error.message);
    }
  })();
  const classifiesSensitivePaths = [
    'app/src/main/assets/jks.jks',
    'app/src/main/assets/pkcs12.keystore',
    'config/release/signing.p12',
    'keys/release.pfx',
    'certs/truststore.bks',
    'keys/app.p8',
    'keys/private.key',
    'keys/cert.pem',
    'keys/id_ed25519',
  ].every(path => isSensitivePath(path))
    && ['app/src/main/java/com/ai/assistance/operit/core/subpack/KeyStoreHelper.kt', 'app/src/main/assets/templates/android/tools/aapt2/aapt2-arm64-v8a', 'app/src/main/assets/desktop_version.txt', 'config/runtime-assets.json', 'app/src/main/assets/js/katex.min.js', 'tools/baseline/assets.mjs']
      .every(path => isSensitivePath(path) === false);
  const excludedFilesHaveNoBytes = assets
    .flatMap(asset => asset.files.filter(item => item.format === 'sensitive-excluded'))
    .every(item => item.sha256 === null && item.elf === null && Number.isInteger(item.bytes) && item.bytes > 0 && item.acquisition === 'metadata-only; private material excluded');
  const excludedPaths = assets.flatMap(asset => asset.files.filter(item => item.format === 'sensitive-excluded').map(item => item.path)).sort();
  const readelfPath = (() => {
    try {
      return requireTool('readelf');
    } catch {
      return null;
    }
  })();
  let readelfAgrees = null;
  if (readelfPath !== null) {
    const target = assets.find(asset => asset.id === 'terminal-libbash');
    const path = resolve(operit, target.files[0].path);
    const result = exec(readelfPath, ['-lW', path]);
    const aligns = result.stdout.split('\n')
      .filter(line => /^\s*LOAD\s/.test(line))
      .map(line => Number.parseInt(line.trim().split(/\s+/).at(-1), 16))
      .filter(value => Number.isFinite(value));
    readelfAgrees = aligns.length > 0 && Math.min(...aligns) === target.elf[0].minLoadAlign;
  }
  return {
    rejectsMissingAsset,
    rejectsHashDrift,
    rejectsManifestDrift,
    rejectsPageAlignment,
    rejectsRootfsCount,
    rejectsUnresolvedFabrication,
    rejectsSensitiveRead,
    classifiesSensitivePaths,
    excludedFilesHaveNoBytes,
    excludedPaths,
    elfParserMatchesReadelf: readelfAgrees,
  };
}

function collect() {
  const snapshot = buildAssets();
  const stt = sttManifest();
  const notices = buildNotices(snapshot.assets, snapshot.rootfs, snapshot.licenseTexts, snapshot.evidence.desktopLicense);
  const negatives = selfChecks(snapshot.assets, snapshot.rootfs, stt, notices);
  assert.equal(negatives.rejectsMissingAsset, true);
  assert.equal(negatives.rejectsHashDrift, true);
  assert.equal(negatives.rejectsManifestDrift, true);
  assert.equal(negatives.rejectsPageAlignment, true);
  assert.equal(negatives.rejectsRootfsCount, true);
  assert.equal(negatives.rejectsUnresolvedFabrication, true);
  assert.equal(negatives.rejectsSensitiveRead, true);
  assert.equal(negatives.classifiesSensitivePaths, true);
  assert.equal(negatives.excludedFilesHaveNoBytes, true);
  assert.deepEqual(negatives.excludedPaths, ['app/src/main/assets/jks.jks', 'app/src/main/assets/pkcs12.keystore']);
  assert.equal(negatives.elfParserMatchesReadelf, true, 'ELF parser must agree with readelf');
  assert.equal(notices.checks.unknownLicenseWithoutReview, 0, 'unknown license marked resolved');
  assert.ok(snapshot.checks.elf16kFail >= 2, 'known 4 KiB assets must be recorded as failures');
  assert.equal(snapshot.checks.hashChecksPassed, snapshot.checks.hashChecks, 'manifest hash consistency');
  assert.equal(snapshot.evidence.sttManifest.rows.length, 8);
  assert.ok(snapshot.rootfs.packages >= 180, 'rootfs package count');
  assert.ok(snapshot.rootfs.copyrightFiles.length >= 180, 'rootfs copyright count');
  return { snapshot, notices, negatives };
}

export async function run(mode) {
  const started = performance.now();
  requireTool('git');
  requireTool('tar');
  const { snapshot, notices, negatives } = collect();
  for (const asset of snapshot.assets) {
    log('assets.asset', {
      id: asset.id,
      kind: asset.kind,
      deliveryUnit: asset.deliveryUnit,
      files: asset.files.map(file => ({ path: file.path, present: file.present, bytes: file.bytes, sha256: file.sha256, format: file.format })),
      elf: asset.elf,
      nestedElf: asset.nestedElf,
      license: { id: asset.license.id, status: asset.license.status },
      notice: asset.notice,
      followUp: asset.followUp,
    });
  }
  log('assets.rootfs', {
    sha256: snapshot.rootfs.sha256,
    bytes: snapshot.rootfs.bytes,
    entries: snapshot.rootfs.entries,
    types: snapshot.rootfs.types,
    packages: snapshot.rootfs.packages,
    copyrightFiles: snapshot.rootfs.copyrightFiles.length,
    commonLicenses: snapshot.rootfs.commonLicenses.length,
    unresolved: snapshot.rootfs.unresolved,
    samples: snapshot.rootfs.samples.map(sample => ({ member: sample.member, present: sample.present, page16k: sample.elf?.page16k ?? null, minLoadAlign: sample.elf?.minLoadAlign ?? null })),
  });
  log('assets.checks', snapshot.checks);
  log('assets.negatives', negatives);
  log('assets.notices', notices.summary);
  log('assets.followUps', { followUps: snapshot.assets.filter(asset => asset.followUp?.reason).map(asset => ({ id: asset.id, unit: asset.followUp.unit, reason: asset.followUp.reason })) });
  artifact(file, snapshot, mode);
  artifact(noticesFile, notices, mode);
  const noticeText = noticeDocument(notices, snapshot);
  const noticePath = resolve(root, 'NOTICE');
  if (mode === 'capture') {
    writeFileSync(noticePath, noticeText);
    log('baseline.captured', { file: 'NOTICE', bytes: Buffer.byteLength(noticeText) });
  } else {
    assert.ok(existsSync(noticePath), 'NOTICE missing');
    const actual = sha256(readFileSync(noticePath));
    const expected = sha256(Buffer.from(noticeText));
    if (actual !== expected) {
      log('baseline.drift', { file: 'NOTICE', path: '$', expected, actual });
      throw new Error('NOTICE: drift');
    }
    log('baseline.verified', { file: 'NOTICE', sha256: actual });
  }
  log('assets.complete', { unit, mode, elapsedMs: Math.round(performance.now() - started), ...snapshot.checks, notices: notices.summary });
}

main(import.meta.url, run);
