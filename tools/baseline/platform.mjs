import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { artifact, desktop, git, log, main, operit, readJson, root, sha256, workspace } from './lib.mjs';
import {
  INITIAL_VERSION,
  LEGACY_ANCHOR,
  MAX_MINOR,
  MAX_PATCH,
  MAX_VERSION_CODE,
  MAJOR_STRIDE,
  MINOR_STRIDE,
  OBSERVED_VERSION_CODE,
  OBSERVED_VERSION_NAME,
  isUpgrade,
  releaseVersion,
  verifyRelease,
  versionCode,
} from './version.mjs';

const unit = 'S0.15';
const file = 'config/baseline/platform.json';
const contractFile = 'config/release-contract.json';
const planFile = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const legacyBuildFile = 'mobile/scripts/build-apk.mjs';
const legacyVersionFile = 'scripts/generate-version.mjs';
const certFile = 'resources/app/android/release-signing-cert.sha256';
const tauriFile = 'resources/app/tauri.conf.json';
const mobilePackageFile = 'mobile/package.json';
const capacitorFile = 'mobile/capacitor.config.json';
const runtimeAssetsFile = 'config/runtime-assets.json';
const buildToolsVersion = '36.0.0';
const gradleCache = resolve(workspace, 'build-tools/android-gradle-home/caches/modules-2/files-2.1');
const operitTerminal = resolve(operit, 'terminal');
const repoRoots = { desktop, operit, 'dstu-android': root };
const desktopCommit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const legacyEvidenceCommit = '145bb3879164c58d1cd5ff39daa94a3daa9fe874';
const legacyEvidence = Object.freeze({
  classification: 'legacy-evidence',
  commit: legacyEvidenceCommit,
  upstreamFixedCommit: desktopCommit,
  adoptedForNewBuilds: false,
  note:
    'The historical publication identity is read from this pinned source file. Current upstream mobile paths and later mobile features are not adopted by the Android product.',
});
const publicationIdentity = Object.freeze({
  version: OBSERVED_VERSION_NAME,
  versionCode: OBSERVED_VERSION_CODE,
  certificateSha256: '3acac9413cf2abace04f98e39921822625dbd7ef4b87e3d34bdb1fbd0270e769',
  role: 'historical-publication-identity',
  adoptedForNewBuilds: false,
});
const cases = [];
function step(id, category, fn) {
  try {
    const result = fn();
    cases.push({ id, category, status: 'pass' });
    log('platform.case', { id, category, status: 'pass' });
    return result;
  } catch (error) {
    cases.push({ id, category, status: 'fail', reason: error.message });
    log('platform.case', { id, category, status: 'fail', reason: error.message });
    throw error;
  }
}

const readDesktop = path => readFileSync(resolve(desktop, path), 'utf8');
const readDesktopBytesAt = (commit, path) =>
  execFileSync('git', ['show', `${commit}:${path}`], { cwd: desktop, maxBuffer: 64 * 1024 * 1024 });
const readDesktopAt = (commit, path) => readDesktopBytesAt(commit, path).toString('utf8');
const readOperit = path => readFileSync(resolve(operit, path));
const readRoot = path => readFileSync(resolve(root, path));
const lineOf = (text, needle) => {
  const index = text.split('\n').findIndex(line => line.includes(needle));
  assert.ok(index >= 0, `missing line containing ${needle}`);
  return index + 1;
};
const command = (executable, args, options = {}) => execFileSync(executable, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...options });
const commandWithStderr = (executable, args) => {
  const result = spawnSync(executable, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  return { status: result.status, output: `${result.stdout ?? ''}${result.stderr ?? ''}`.trim() };
};
const tryCommand = (executable, args) => {
  try {
    return { ok: true, output: command(executable, args) };
  } catch (error) {
    return { ok: false, output: `${error.stdout ?? ''}${error.stderr ?? ''}` };
  }
};

function resolveSdk() {
  const environment = process.env.ANDROID_HOME ? 'ANDROID_HOME' : process.env.ANDROID_SDK_ROOT ? 'ANDROID_SDK_ROOT' : 'home-default';
  const candidate = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ?? join(homedir(), 'Android/Sdk');
  assert.ok(isAbsolute(candidate), `SDK path must be absolute: ${candidate}`);
  assert.ok(existsSync(candidate), `Android SDK not found at ${candidate}`);
  return { home: candidate, environment };
}

const sdk = resolveSdk();
const buildToolsDir = join(sdk.home, 'build-tools', buildToolsVersion);
const tools = {
  aapt2: join(buildToolsDir, 'aapt2'),
  apksigner: join(buildToolsDir, 'apksigner'),
  zipalign: join(buildToolsDir, 'zipalign'),
  readelf: '/usr/bin/readelf',
  unzip: '/usr/bin/unzip',
};

function collectTools() {
  for (const path of Object.values(tools)) {
    assert.ok(existsSync(path), `missing tool: ${path}`);
    assert.ok((statSync(path).mode & 0o111) !== 0, `tool is not executable: ${path}`);
  }
  assert.ok(existsSync(join(sdk.home, 'platforms/android-36')), `missing SDK platform 36 at ${sdk.home}`);
  const java = command('/usr/bin/which', ['java']).trim();
  assert.ok(existsSync(java), `java not found: ${java}`);
  const firstLine = value => value.split('\n').map(line => line.trim()).filter(Boolean)[0] ?? 'no output';
  const javaOutput = commandWithStderr(java, ['-version']).output;
  const javaVersion = /version "([^"]+)"/.exec(javaOutput)?.[1] ?? firstLine(javaOutput);
  const zipalignOutput = commandWithStderr(tools.zipalign, ['-h']).output;
  const zipalignUsage = zipalignOutput.split('\n').map(line => line.trim()).find(line => line.startsWith('Usage: zipalign')) ?? firstLine(zipalignOutput);
  return {
    sdk: {
      source: sdk.environment,
      isHomeDefault: sdk.environment === 'home-default',
      platforms: readdirSync(join(sdk.home, 'platforms')).sort(),
      buildTools: readdirSync(join(sdk.home, 'build-tools')).sort(),
      hasPlatform36: true,
    },
    buildTools: {
      version: buildToolsVersion,
      aapt2: { relativePath: `build-tools/${buildToolsVersion}/aapt2`, version: firstLine(commandWithStderr(tools.aapt2, ['version']).output) },
      apksigner: { relativePath: `build-tools/${buildToolsVersion}/apksigner`, version: firstLine(commandWithStderr(tools.apksigner, ['version']).output) },
      zipalign: { relativePath: `build-tools/${buildToolsVersion}/zipalign`, usage: zipalignUsage },
    },
    host: {
      java: { path: java, version: javaVersion },
      node: { path: process.execPath, version: process.version },
      readelf: { path: tools.readelf, version: firstLine(commandWithStderr(tools.readelf, ['--version']).output) },
      unzip: { path: tools.unzip, version: commandWithStderr(tools.unzip, ['-v']).output.split('\n').map(line => line.trim()).find(line => line.startsWith('UnZip')) ?? 'no output' },
    },
  };
}

function parseLegacySources() {
  execFileSync('git', ['cat-file', '-e', `${legacyEvidenceCommit}^{commit}`], { cwd: desktop, stdio: 'pipe' });
  const evidence = {
    ...legacyEvidence,
    file: legacyBuildFile,
  };
  const buildBytes = readDesktopBytesAt(legacyEvidenceCommit, legacyBuildFile);
  const buildText = buildBytes.toString('utf8');
  const buildLines = buildText.split('\n');
  const stringConstant = (name, line) => {
    const match = new RegExp(`${name}\\s*=\\s*(['"])([^'"]*)\\1`).exec(buildLines[line - 1]);
    assert.ok(match, `${legacyBuildFile}:${line}: missing ${name}`);
    return match[2];
  };
  const numberConstant = (name, line) => {
    const match = new RegExp(`${name}\\s*=\\s*([0-9_]+)`).exec(buildLines[line - 1]);
    assert.ok(match, `${legacyBuildFile}:${line}: missing ${name}`);
    return Number(match[1].replaceAll('_', ''));
  };
  const versionBytes = readDesktopBytesAt(desktopCommit, legacyVersionFile);
  const versionText = versionBytes.toString('utf8');
  const versionConstant = declaration => {
    const pattern = new RegExp(`^\\s*(?:export\\s+)?const\\s+${declaration}\\s*=\\s*(['"]?)([^'";]+)\\1\\s*;`, 'm');
    const match = pattern.exec(versionText);
    assert.ok(match, `${legacyVersionFile}: missing ${declaration}`);
    const value = match[2].replaceAll('_', '');
    return { value: /^[0-9]+$/u.test(value) ? Number(value) : value, line: lineOf(versionText, match[0].trim()) };
  };
  const anchor = versionConstant('ANDROID_VERSION_CODE');
  const published = versionConstant('PUBLISHED_ANDROID_VERSION_CODE');
  const baseVersion = versionConstant('ANDROID_VERSION_BASE_APP_VERSION');
  const buildNumberBase = versionConstant('BUILD_NUMBER_BASE');
  const buildNumberCommit = versionConstant('BUILD_NUMBER_BASE_COMMIT');
  const maximum = versionConstant('ANDROID_VERSION_CODE_MAX');
  const majorStride = versionConstant('SEMVER_MAJOR_STRIDE');
  const resolver = lineOf(versionText, 'export function resolveAndroidVersionCode(appVersion)');
  const tauri = JSON.parse(readDesktopAt(desktopCommit, tauriFile));
  const mobilePackage = JSON.parse(readDesktopAt(desktopCommit, mobilePackageFile));
  const capacitor = JSON.parse(readDesktopAt(desktopCommit, capacitorFile));
  const fixedHead = {
    commit: desktopCommit,
    packageFile: mobilePackageFile,
    mobileVersion: mobilePackage.version,
    tauriVersion: tauri.version,
    role: 'source-version-observation',
    adoptedAsNativeIdentity: false,
  };
  const delta = {
    currentMobile: mobilePackage.version,
    generationCommit: legacyEvidenceCommit,
    publication: publicationIdentity.version,
    currentMobileVersionAdopted: false,
    classification: 'upstream-mobile-version-observation',
    note:
      'The fixed desktop commit carries a later upstream mobile version. It is recorded only as an upstream observation; the Android publication identity remains the historical 0.20.0/1114586 package.',
  };
  return {
    evidence,
    fixedHead,
    upstreamMobileDelta: delta,
    buildApk: {
      file: legacyBuildFile,
      sha256: sha256(buildBytes),
      commit: legacyEvidenceCommit,
      classification: legacyEvidence.classification,
      adoptedForNewBuilds: false,
      identity: {
        applicationId: { value: stringConstant('APP_ID', 190), line: 190 },
        appName: { value: stringConstant('APP_NAME', 191), line: 191 },
        versionName: { value: stringConstant('APP_VERSION_NAME', 192), line: 192 },
        versionCode: { value: numberConstant('APP_VERSION_CODE', 194), line: 194 },
      },
      zipalign: { line: lineOf(buildText, "['-P', '16', '-v', '4'"), args: ['-P', '16', '-v', '4'] },
      signatureCheck: { line: lineOf(buildText, 'verifyReleaseSignature(sdk, output, env)'), certificateLine: lineOf(buildText, 'const RELEASE_CERT_FILE') },
      buildToolsVersionLine: lineOf(buildText, "const BUILD_TOOLS_VERSION = '36.0.0'"),
    },
    generateVersion: {
      file: legacyVersionFile,
      sha256: sha256(versionBytes),
      constants: {
        publishedAndroidVersionCode: { value: published.value, line: published.line },
        androidVersionCode: { value: anchor.value, line: anchor.line },
        baseAppVersion: { value: baseVersion.value, line: baseVersion.line },
        buildNumberBase: { value: buildNumberBase.value, line: buildNumberBase.line },
        buildNumberBaseCommit: { value: buildNumberCommit.value, line: buildNumberCommit.line },
        maxVersionCode: { value: maximum.value, line: maximum.line },
        majorStride: { value: majorStride.value, line: majorStride.line },
        minorStride: { value: 100_000, line: null },
      },
      resolverLine: resolver,
    },
    tauri: {
      file: tauriFile,
      commit: desktopCommit,
      identifier: tauri.identifier,
      version: tauri.version,
      androidVersionCode: tauri.bundle.android.versionCode,
      versionCodeLine: lineOf(readDesktopAt(desktopCommit, tauriFile), '"versionCode":'),
    },
    mobile: {
      packageFile: mobilePackageFile,
      commit: desktopCommit,
      version: mobilePackage.version,
      versionRole: 'upstream-observation',
      adoptedForNewBuilds: false,
      capacitorFile,
      capacitorCommit: desktopCommit,
      appId: capacitor.appId,
      appName: capacitor.appName,
    },
  };
}

async function resolveLegacyMapping(legacy) {
  const source = readDesktopBytesAt(desktopCommit, legacyVersionFile);
  const module = await import(pathToFileURL(resolve(desktop, legacyVersionFile)).href);
  const mapped = {};
  for (const version of ['0.9.55', '0.20.0', '0.20.1', '0.21.0', '0.99.99', '1.0.0']) mapped[version] = module.resolveAndroidVersionCode(version);
  const rejected = [];
  for (const version of ['0.9.54', '1.100.0', '1.0.100000', '1.0', '1.0.0-beta']) {
    let accepted = null;
    try {
      accepted = module.resolveAndroidVersionCode(version);
    } catch (error) {
      rejected.push({ version, reason: error.message });
    }
    assert.equal(accepted, null, `legacy generator must reject ${version}`);
  }
  let baselineCommitPresent = true;
  try {
    execFileSync('git', ['cat-file', '-e', '64edcb7735e5d4e6ccad6f13514b9ee0a8867c78^{commit}'], { cwd: desktop, stdio: 'pipe' });
  } catch {
    baselineCommitPresent = false;
  }
  return {
    module: legacyVersionFile,
    source: { commit: desktopCommit, sha256: sha256(source) },
    mapped,
    rejected,
    buildNumberBaseCommitPresent: baselineCommitPresent,
    buildNumberBaseCommit: '64edcb7735e5d4e6ccad6f13514b9ee0a8867c78',
    note: baselineCommitPresent
      ? 'Desktop history contains the build-number baseline commit.'
      : 'Desktop clone is missing the build-number baseline commit, so resolveBuildNumber cannot run here; this unit does not use build numbers for versionCode.',
  };
}

function certificate() {
  const text = readDesktop(certFile).toString('utf8');
  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(line => line && !line.startsWith('#'));
  assert.equal(lines.length, 1, `${certFile} must contain exactly one fingerprint`);
  assert.match(lines[0], /^[0-9a-f]{64}$/u);
  return { value: lines[0].toLowerCase(), line: lineOf(text, lines[0]), sha256: sha256(readDesktop(certFile)), repoPath: `dstu-node/${certFile}`, workspacePath: `dstu-node/${certFile}`, relativeToDesktop: certFile };
}

function enumeratePackages() {
  const found = [];
  const walk = dir => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isSymbolicLink()) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (/\.(apk|aab)$/i.test(entry.name)) found.push(full);
    }
  };
  const artifacts = resolve(workspace, 'artifacts');
  assert.ok(existsSync(artifacts), `missing workspace artifacts directory ${artifacts}`);
  walk(artifacts);
  for (const [repo, dir] of Object.entries(repoRoots)) {
    for (const path of git(dir, 'ls-files', '-z', '--', '*.apk', '*.aab').split('\0').filter(Boolean)) found.push(resolve(dir, path));
  }
  return [...new Set(found)].sort().map(absolute => {
    const location = relative(workspace, absolute).split(sep).join('/');
    assert.ok(!location.startsWith('..'), `package outside workspace: ${location}`);
    assert.ok(!location.includes('node_modules'), `package inside node_modules: ${location}`);
    const entry = Object.entries(repoRoots).find(([, dir]) => absolute.startsWith(`${dir}${sep}`));
    const repo = entry?.[0] ?? null;
    const path = repo === null ? location : relative(entry[1], absolute).split(sep).join('/');
    const tracked = repo !== null && git(entry[1], 'ls-files', '-z', '--', path).split('\0').filter(Boolean).length === 1;
    return { absolute, path, repo, tracked };
  });
}

function badging(apk) {
  const text = command(tools.aapt2, ['dump', 'badging', apk]);
  const field = regex => (regex.exec(text) ?? [])[1] ?? null;
  const nativeCode = field(/^native-code: (.+)$/m);
  return {
    packageName: field(/^package: name='([^']+)'/m),
    versionCode: Number(field(/^package: .*versionCode='([0-9]+)'/m)),
    versionName: field(/^package: .*versionName='([^']+)'/m),
    minSdk: Number(field(/^minSdkVersion:'([0-9]+)'/m)),
    targetSdk: Number(field(/^targetSdkVersion:'([0-9]+)'/m)),
    compileSdk: Number(field(/compileSdkVersion='([0-9]+)'/m)),
    platformBuildVersionCode: Number(field(/platformBuildVersionCode='([0-9]+)'/m)),
    applicationLabel: field(/^application-label:'([^']*)'/m),
    nativeCode: nativeCode === null ? [] : nativeCode.split(/\s+/).map(item => item.replaceAll("'", '')),
    badgingSha256: sha256(text),
  };
}

function manifestFlags(apk) {
  const text = command(tools.aapt2, ['dump', 'xmltree', '--file', 'AndroidManifest.xml', apk]);
  const flag = name => {
    const match = new RegExp(`${name}\\(0x[0-9a-f]+\\)=(true|false)`).exec(text);
    return match ? match[1] === 'true' : null;
  };
  return { extractNativeLibs: flag('extractNativeLibs'), debuggable: flag('debuggable'), allowBackup: flag('allowBackup'), usesCleartextTraffic: flag('usesCleartextTraffic') };
}

function signature(apk) {
  const verify = tryCommand(tools.apksigner, ['verify', apk]);
  const printed = tryCommand(tools.apksigner, ['verify', '--verbose', '--print-certs', apk]).output;
  const schemes = {};
  for (const label of ['v1', 'v2', 'v3', 'v3.1', 'v4']) {
    const match = new RegExp(`Verified using ${label.replace('.', '\\.')} scheme[^:]*: (true|false)`).exec(printed);
    schemes[label] = match ? match[1] === 'true' : null;
  }
  return {
    verified: verify.ok,
    verifyError: verify.ok ? null : verify.output.trim().split('\n')[0],
    schemes,
    signerCount: Number((/Number of signers: ([0-9]+)/.exec(printed) ?? [])[1] ?? 0),
    signerDn: (/Signer #1 certificate DN: (.+)/.exec(printed) ?? [])[1]?.trim() ?? null,
    signerSha256: ((/Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)/.exec(printed) ?? [])[1] ?? '').toLowerCase() || null,
  };
}

function alignment(apk) {
  const result = tryCommand(tools.zipalign, ['-c', '-P', '16', '-v', '4', apk]);
  return {
    command: 'zipalign -c -P 16 -v 4 <apk>',
    passed: result.ok,
    summary: result.output.trim().split('\n').at(-1) ?? null,
    checkedEntries: (result.output.match(/\(OK\)/g) ?? []).length,
  };
}

function zipEntries(apk) {
  const lines = command(tools.unzip, ['-lv', apk]).split('\n').filter(line => /^\s+[0-9]+\s+(?:Defl|Stored|Defl:N|Stored)\s+[0-9]+\s+/.test(line));
  const rows = lines.map(line => {
    const fields = line.trim().split(/\s+/);
    return { method: fields[1], name: fields.at(-1) };
  });
  const natives = rows.filter(row => row.name.startsWith('lib/') && row.name.endsWith('.so'));
  return {
    entries: rows.length,
    nativeEntries: natives.length,
    nativeAbis: [...new Set(natives.map(row => row.name.split('/')[1]))].sort(),
    nativeMethods: natives.reduce((histogram, row) => {
      const method = row.method.startsWith('Defl') ? 'deflated' : 'stored';
      histogram[method] = (histogram[method] ?? 0) + 1;
      return histogram;
    }, {}),
    arm64Entries: natives.filter(row => row.name.startsWith('lib/arm64-v8a/')).map(row => row.name),
  };
}

function parseElfLoadAligns(buffer) {
  assert.equal(buffer.readUInt32BE(0), 0x7f454c46, 'not an ELF file');
  assert.equal(buffer[4], 2, 'expect ELF64');
  assert.equal(buffer[5], 1, 'expect little endian');
  const phoff = Number(buffer.readBigUInt64LE(32));
  const phentsize = buffer.readUInt16LE(54);
  const phnum = buffer.readUInt16LE(56);
  assert.ok(phoff + phentsize * phnum <= buffer.length, 'ELF program headers outside file');
  const loads = [];
  for (let index = 0; index < phnum; index += 1) {
    const offset = phoff + index * phentsize;
    if (buffer.readUInt32LE(offset) === 1) loads.push(Number(buffer.readBigUInt64LE(offset + 48)));
  }
  const aligns = [...new Set(loads)].sort((a, b) => a - b);
  return { loadAligns: aligns, minLoadAlign: aligns[0] ?? null, page16k: (aligns[0] ?? 0) >= 16384 };
}

function inspectPackage(item, expectedCertificate) {
  const apk = item.absolute;
  const info = badging(apk);
  const sig = signature(apk);
  const zip = zipEntries(apk);
  const arm64 = zip.arm64Entries.map(entry => {
    const bytes = execFileSync(tools.unzip, ['-p', apk, entry], { maxBuffer: 64 * 1024 * 1024 });
    return { entry, bytes: bytes.length, ...parseElfLoadAligns(bytes) };
  });
  return {
    path: item.path,
    origin: { repo: item.repo, tracked: item.tracked, source: item.repo === null ? 'workspace artifacts directory' : 'git ls-files of workspace repository' },
    bytes: statSync(apk).size,
    sha256: sha256(readFileSync(apk)),
    badging: info,
    manifestFlags: manifestFlags(apk),
    signature: sig,
    zipAlignment16k: alignment(apk),
    zip,
    arm64LoadAligns: arm64,
    native16k: { checked: arm64.length, pass: arm64.filter(entry => entry.page16k).length, allPass: arm64.every(entry => entry.page16k) },
    releaseCertificateMatch: sig.signerSha256 === expectedCertificate,
    debugSigned: sig.signerDn === 'C=US, O=Android, CN=Android Debug',
  };
}

function blockBody(text, header) {
  const start = text.indexOf(header);
  if (start < 0) return null;
  const open = text.indexOf('{', start + header.length);
  if (open < 0) return null;
  let depth = 0;
  for (let index = open; index < text.length; index += 1) {
    if (text[index] === '{') depth += 1;
    else if (text[index] === '}') {
      depth -= 1;
      if (depth === 0) return text.slice(open + 1, index);
    }
  }
  return null;
}

function collectGradleFacts() {
  const modules = [
    ['app', 'app'],
    ['terminal', 'terminal'],
    ['quickjs', 'quickjs'],
    ['llama', 'llm/llama'],
    ['mnn', 'llm/mnn'],
    ['showerclient', 'showerclient'],
    ['fbx', 'avator/fbx'],
    ['dragonbones', 'avator/dragonbones'],
    ['mmd', 'avator/mmd'],
  ];
  const parsed = modules.map(([id, path]) => {
    const relativePath = `${path}/build.gradle.kts`;
    const text = readOperit(relativePath).toString('utf8');
    const defaultConfig = blockBody(text, 'defaultConfig');
    assert.ok(defaultConfig, `${relativePath}: missing defaultConfig`);
    const number = (block, key) => {
      const match = new RegExp(`${key}\\s*=\\s*([0-9]+)`).exec(block);
      return match ? Number(match[1]) : null;
    };
    const abi = /abiFilters(?:\s*\+?=|\s*\.addAll\()\s*listOf\(([^)]*)\)/.exec(defaultConfig);
    const legacy = /useLegacyPackaging\s*=\s*(true|false)/.exec(text);
    return {
      id,
      path,
      file: relativePath,
      sha256: sha256(readOperit(relativePath)),
      minSdk: number(defaultConfig, 'minSdk'),
      targetSdk: number(defaultConfig, 'targetSdk'),
      compileSdk: number(text, 'compileSdk'),
      abiFilters: abi ? [...abi[1].matchAll(/"([^"]+)"/g)].map(match => match[1]) : [],
      useLegacyPackaging: legacy ? legacy[1] === 'true' : null,
    };
  });
  const catalog = readOperit('gradle/libs.versions.toml').toString('utf8');
  const versionOf = key => (new RegExp(`^${key}\\s*=\\s*"([^"]+)"`, 'm').exec(catalog) ?? [])[1] ?? null;
  return {
    modules: parsed,
    catalog: {
      file: 'gradle/libs.versions.toml',
      sha256: sha256(catalog),
      agp: versionOf('agp'),
      kotlin: versionOf('kotlin'),
      coreKtx: versionOf('coreKtx'),
      composeBom: versionOf('compose-bom'),
      composeUi: versionOf('uiAndroid'),
      activityCompose: versionOf('activity-compose'),
    },
  };
}

function collectAarFacts(gradle) {
  assert.ok(existsSync(gradleCache), `missing local Gradle module cache ${gradleCache}`);
  const aars = [];
  const walk = dir => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isSymbolicLink()) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.aar')) aars.push(full);
    }
  };
  walk(gradleCache);
  const manifests = aars.map(aar => {
    const text = execFileSync(tools.unzip, ['-p', aar, 'AndroidManifest.xml'], { encoding: 'utf8' });
    const match = /minSdkVersion="([0-9]+)"/.exec(text);
    return { coordinate: relative(gradleCache, aar).split(sep).join('/').split('/').slice(0, 3).join(':'), minSdk: match ? Number(match[1]) : null };
  });
  const parsed = manifests.filter(entry => entry.minSdk !== null);
  const maximum = parsed.reduce((value, entry) => Math.max(value, entry.minSdk), 0);
  const requested = [
    ['coreKtx', `androidx.core:core-ktx:${gradle.catalog.coreKtx}`],
    ['composeUi', `androidx.compose.ui:ui:${gradle.catalog.composeUi}`],
    ['composeBom', `androidx.compose:compose-bom:${gradle.catalog.composeBom}`],
    ['activityCompose', `androidx.activity:activity-compose:${gradle.catalog.activityCompose}`],
  ];
  return {
    cache: relative(workspace, gradleCache).split(sep).join('/'),
    aarCount: manifests.length,
    parsedManifestCount: parsed.length,
    maxDeclaredMinSdk: maximum,
    maxDeclaredMinSdkCoordinates: [...new Set(parsed.filter(entry => entry.minSdk === maximum).map(entry => entry.coordinate))].sort(),
    minSdkHistogram: parsed.reduce((histogram, entry) => {
      histogram[String(entry.minSdk)] = (histogram[String(entry.minSdk)] ?? 0) + 1;
      return histogram;
    }, {}),
    requestedArtifacts: requested.map(([id, coordinate]) => ({ id, coordinate, observed: manifests.some(entry => entry.coordinate === coordinate) })),
    unparsedManifests: manifests.filter(entry => entry.minSdk === null).map(entry => entry.coordinate).sort(),
    note: 'Only AAR manifests present in the local Gradle module cache are measured. Requested artifacts absent from the cache are recorded as not observed, not assumed.',
  };
}

function collectWorkspaceState() {
  const markers = ['settings.gradle.kts', 'settings.gradle', 'build.gradle.kts', 'build.gradle', 'gradlew', 'gradle/wrapper/gradle-wrapper.properties'].filter(name => existsSync(resolve(root, name)));
  return {
    dstuAndroid: { gradleProjectMarkers: markers, hasGradleProject: markers.length > 0 },
    heads: {
      desktop: git(desktop, 'rev-parse', 'HEAD'),
      operit: git(operit, 'rev-parse', 'HEAD'),
      terminal: git(operitTerminal, 'rev-parse', 'HEAD'),
    },
    trackedChanges: {
      desktop: git(desktop, 'status', '--porcelain', '--untracked-files=no'),
      operit: git(operit, 'status', '--porcelain', '--untracked-files=no'),
      terminal: git(operitTerminal, 'status', '--porcelain', '--untracked-files=no'),
    },
    note: 'dstu-android HEAD is intentionally not recorded: it changes with each commit of this unit and is not a platform fact. Source repositories are pinned by config/source-lock.json.',
  };
}

function collectExclusions(packages) {
  const grep = (cwd, pattern, paths) => {
    try {
      return command('git', ['grep', '-n', pattern, '--', ...paths], { cwd }).trim().split('\n').filter(Boolean);
    } catch (error) {
      if (error.status === 1) return [];
      throw error;
    }
  };
  const desktopApk = packages.find(entry => entry.path.endsWith('desktop.apk'));
  assert.ok(desktopApk, 'desktop.apk must be enumerated');
  const exampleJars = git(operit, 'ls-files', '-z', '--', 'examples').split('\0').filter(path => path.endsWith('.jar'));
  const dstuJars = [];
  const walk = dir => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isSymbolicLink()) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory() && !['node_modules', '.git', 'logs'].includes(entry.name)) walk(full);
      else if (entry.name.endsWith('.jar')) dstuJars.push(relative(root, full).split(sep).join('/'));
    }
  };
  walk(root);
  return {
    desktopApk: {
      path: desktopApk.path,
      sha256: desktopApk.sha256,
      packageName: desktopApk.badging.packageName,
      versionName: desktopApk.badging.versionName,
      versionCode: desktopApk.badging.versionCode,
      assetReads: grep(operit, 'desktop\\.apk', ['app', 'terminal']),
      packageLaunchReferences: grep(operit, 'com\\.ai\\.assistance\\.operit\\.desktop', ['app/src/main/java']),
      decision: 'not-adopted',
      reason: 'No tracked source opens or installs app/src/main/assets/desktop.apk; the only reference launches the com.ai.assistance.operit.desktop package through Shower. The asset itself has no consumer in the adopted product scope.',
    },
    exampleJars: {
      files: exampleJars.map(path => ({ path, bytes: statSync(resolve(operit, path)).size, sha256: sha256(readOperit(path)) })),
      includedInOperitSettings: /examples/u.test(readOperit('settings.gradle.kts').toString('utf8').split('include(').join('\n')),
      dstuAndroidJars: dstuJars.sort(),
      decision: 'not-shipped',
      reason: 'Operit examples/ is not an included Gradle module and dstu-android contains no jars; the apktool runtime jars are authoring aids, not product payload.',
    },
    subpackKeystores: {
      files: ['app/src/main/assets/jks.jks', 'app/src/main/assets/pkcs12.keystore'].map(path => ({ path, bytes: statSync(resolve(operit, path)).size, bytesRead: false })),
      consumer: 'Operit app/src/main/java/com/ai/assistance/operit/core/subpack/KeyStoreHelper.kt:180',
      decision: 'not-reused',
      reason: 'These are Operit subpack runtime keystores loaded by KeyStoreHelper. They are not release signing material; bytes stay unread and they are excluded from dstu packaging.',
    },
    operitAssets: {
      trackedPackages: packages.filter(entry => entry.origin.repo === 'operit').map(entry => ({ path: entry.path, sha256: entry.sha256 })),
      decision: 'read-only',
      reason: 'Operit assets are inspected without modification; the working tree must stay clean.',
    },
  };
}

const acceptanceChannelIds = {
  hostStatic: ['elf-load-align', 'zip-alignment', 'packaged-native', 'legacy-packaging'],
  debug: ['debug-extracted-files', 'debug-native-dir'],
  release: ['release-execution-profile', 'release-profile-verify'],
  deviceRuntime: ['page-size-device', 'runtime-forkpty', 'runtime-proot'],
};

function acceptanceChannels() {
  const contract = readJson(contractFile);
  const channels = contract.native16k.acceptance;
  assert.deepEqual(Object.keys(channels).sort(), ['debug', 'deviceRuntime', 'hostStatic', 'release']);
  for (const [channel, ids] of Object.entries(acceptanceChannelIds)) {
    const steps = Array.isArray(channels[channel]) ? channels[channel] : channels[channel].commands;
    assert.deepEqual(steps.map(step => step.id), ids, `${channel}: acceptance step ids`);
  }
  const packaged = channels.hostStatic.find(step => step.id === 'packaged-native');
  assert.match(packaged.command, /mktemp -d/u, 'packaged-native must extract to a temporary directory');
  assert.match(packaged.command, /trap /u, 'packaged-native must clean up with trap');
  assert.match(packaged.command, /readelf -lW "\$tmp\//u, 'packaged-native must read the extracted file');
  assert.ok(channels.hostStatic.every(step => step.executedInThisUnit === false && !/adb shell/u.test(step.command)));
  assert.ok(channels.debug.commands.every(step => step.execution === 'adb-debug' && !/echo \$PROOT_LOADER/u.test(step.command)));
  assert.ok(channels.release.commands.every(step => step.execution === 'in-app-diagnostic'));
  assert.deepEqual(channels.release.requiredFields, ['nativeLibraryDir', 'PROOT_LOADER', 'LD_LIBRARY_PATH', 'cwd', 'uid']);
  assert.equal(channels.debug.executedInThisUnit, false);
  assert.equal(channels.release.executedInThisUnit, false);
  assert.equal(channels.deviceRuntime.executedInThisUnit, false);
  return channels;
}

function collectPage16kTasks() {
  const assets = readJson(runtimeAssetsFile);
  assert.equal(assets.page16k.fail.length, 3, 'S0.14 recorded three 16 KiB failures');
  const unitByAsset = { 'operit-shell-exec': 'S6.21', 'aapt2-arm64': 'S6.15' };
  return assets.page16k.fail.map(failure => ({
    assetId: failure.assetId,
    path: failure.path,
    observedMinLoadAlign: failure.minLoadAlign,
    deliveryUnit: unitByAsset[failure.assetId],
    status: 'open',
    rebuild: {
      requirement: 'Rebuild from pinned source with 16 KiB maximum and common page size; do not patch bytes in place.',
      command: '<ndk>/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android26-clang -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384',
      record: ['NDK version', 'full command line', 'output SHA-256', 'PT_LOAD alignment of the rebuilt file'],
    },
    acceptance: acceptanceChannels(),
  }));
}

function validateContract(contract, snapshot) {
  step('contract.identityFreeze', 'contract', () => {
    const legacy = contract.identity.legacy;
    const current = contract.identity.current;
    const upstream = contract.identity.upstreamMobile;
    assert.deepEqual(legacy, {
      applicationId: 'com.deepstudent.app',
      appName: 'Deep Student',
      versionName: '0.20.0',
      versionCode: 1_114_586,
      source: 'dstu-node/mobile/scripts/build-apk.mjs:190-194',
      commit: legacyEvidenceCommit,
      classification: 'legacy-evidence',
      adoptedForNewBuilds: false,
    });
    assert.equal(legacy.versionName, publicationIdentity.version);
    assert.equal(legacy.versionCode, publicationIdentity.versionCode);
    assert.deepEqual(current, {
      versionName: '0.21.0',
      versionCode: 2_100_000,
      status: 'frozen',
    });
    assert.deepEqual(upstream, {
      fixedCommit: desktopCommit,
      mobileVersion: '0.20.1',
      adopted: false,
      classification: 'upstream-mobile-version-observation',
    });
    assert.equal(snapshot.legacy.buildApk.commit, legacy.commit);
    assert.equal(snapshot.legacy.buildApk.classification, legacy.classification);
    assert.equal(snapshot.legacy.buildApk.adoptedForNewBuilds, legacy.adoptedForNewBuilds);
    assert.equal(snapshot.legacy.mobile.version, upstream.mobileVersion);
    assert.equal(snapshot.legacy.fixedHead.commit, upstream.fixedCommit);
    assert.equal(snapshot.version.initial.version, current.versionName);
    assert.equal(snapshot.version.initial.versionCode, current.versionCode);
  });
  step('contract.identity', 'contract', () => {
    assert.equal(contract.unit, unit);
    assert.equal(contract.formatVersion, 1);
    assert.equal(contract.identity.applicationId, 'com.deepstudent.app');
    assert.equal(contract.identity.applicationLabel, 'Deep Student');
    assert.equal(contract.identity.legacy.applicationId, snapshot.legacy.buildApk.identity.applicationId.value);
    assert.equal(contract.identity.legacy.appName, snapshot.legacy.buildApk.identity.appName.value);
    assert.equal(contract.identity.legacy.versionName, OBSERVED_VERSION_NAME);
    assert.equal(contract.identity.legacy.versionCode, OBSERVED_VERSION_CODE);
    assert.equal(contract.identity.tauriIdentifier, snapshot.legacy.tauri.identifier);
    assert.equal(contract.identity.capacitorAppId, snapshot.legacy.mobile.appId);
    assert.equal(contract.identity.packageChange, 'never');
  });
  step('contract.platform', 'contract', () => {
    assert.equal(contract.platform.compileSdk, 36);
    assert.equal(contract.platform.targetSdk, 36);
    assert.equal(contract.platform.minSdk, 26);
    assert.equal(contract.platform.buildTools.version, buildToolsVersion);
    const app = snapshot.platform.gradle.modules.find(module => module.id === 'app');
    assert.equal(contract.platform.minSdk, app.minSdk);
    assert.equal(contract.platform.compileSdk, app.compileSdk);
    assert.ok(contract.platform.minSdk >= snapshot.platform.aarFacts.maxDeclaredMinSdk, 'minSdk covers every measured AAR');
    assert.equal(contract.platform.minSdk, Math.max(snapshot.platform.aarFacts.maxDeclaredMinSdk, app.minSdk));
    assert.ok(contract.platform.minSdkBasis.length >= 3);
    assert.ok(contract.platform.targetSdkBasis.length >= 2);
    assert.equal(contract.platform.unobserved.length, snapshot.platform.aarFacts.requestedArtifacts.filter(item => !item.observed).length);
  });
  step('contract.abi', 'contract', () => {
    assert.deepEqual(contract.abi.shipped, ['arm64-v8a']);
    assert.deepEqual(contract.abi.withheld, ['x86_64']);
    const declared = [...new Set(snapshot.platform.gradle.modules.flatMap(module => module.abiFilters))].sort();
    assert.deepEqual(declared, contract.abi.shipped);
    assert.ok(snapshot.platform.gradle.modules.filter(module => module.useLegacyPackaging === true).length >= 2);
    assert.equal(contract.abi.pageSize.requiredLoadAlign, 16384);
    assert.equal(contract.abi.pageSize.zipAlignFlag, '-P 16');
    assert.equal(contract.abi.pageSize.useLegacyPackaging, true);
    assert.ok(contract.abi.withheldBasis.length >= 2);
  });
  step('contract.version', 'contract', () => {
    assert.equal(contract.version.mapping.file, 'tools/baseline/version.mjs');
    assert.equal(contract.version.mapping.formula, 'major * 10000000 + minor * 100000 + patch');
    assert.equal(contract.version.mapping.majorStride, MAJOR_STRIDE);
    assert.equal(contract.version.mapping.minorStride, MINOR_STRIDE);
    assert.equal(contract.version.mapping.maxMinor, MAX_MINOR);
    assert.equal(contract.version.mapping.maxPatch, MAX_PATCH);
    assert.equal(contract.version.mapping.maxVersionCode, MAX_VERSION_CODE);
    assert.equal(contract.version.mapping.offset, 0);
    assert.equal(contract.version.initial.version, INITIAL_VERSION);
    assert.equal(contract.version.initial.versionCode, snapshot.version.initial.versionCode);
    assert.equal(contract.version.initial.reasonRef, 'initialVersionReason');
    assert.ok(contract.version.initialVersionReason.length > 40);
    assert.equal(contract.version.requiredMatrix.length, 4);
    for (const row of contract.version.requiredMatrix) {
      assert.equal(versionCode(row.version), row.pureCode, `${row.version}: matrix pure code`);
      assert.equal(snapshot.legacy.mapping.mapped[row.version], row.legacyCode, `${row.version}: matrix legacy code`);
      assert.ok(row.pureCode > row.legacyCode, `${row.version}: pure code must exceed legacy`);
      if (row.publishable) assert.equal(releaseVersion(row.version).versionCode, row.pureCode, `${row.version}: publishable`);
      else assert.throws(() => releaseVersion(row.version), /predates the new release line/u, `${row.version}: not publishable`);
    }
    assert.equal(contract.version.legacy.versionGenerator.anchor.version, LEGACY_ANCHOR.version);
    assert.equal(contract.version.legacy.versionGenerator.anchor.versionCode, LEGACY_ANCHOR.versionCode);
    assert.equal(contract.version.legacy.versionGenerator.anchor.buildNumberBase, LEGACY_ANCHOR.buildNumberBase);
    assert.equal(snapshot.legacy.mapping.mapped[OBSERVED_VERSION_NAME], OBSERVED_VERSION_CODE);
    for (const [version, code] of Object.entries(contract.version.legacy.comparison)) {
      assert.equal(snapshot.legacy.mapping.mapped[version], code, `legacy mapping ${version}`);
      assert.ok(versionCode(version) > code, `new mapping must exceed legacy for ${version}`);
    }
    assert.ok(snapshot.version.initial.versionCode > OBSERVED_VERSION_CODE);
    assert.equal(contract.version.buildOrdinalInVersionCode, false);
    assert.equal(contract.version.sameVersionRepublish, 'rejected');
    assert.equal(contract.version.lowerBoundRule.observedPackage.versionCode, OBSERVED_VERSION_CODE);
    assert.equal(contract.version.lowerBoundRule.legacyAnchor.versionCode, LEGACY_ANCHOR.versionCode);
    for (const [version, row] of Object.entries(contract.version.lowerBoundRule.perVersion)) {
      assert.equal(versionCode(version), row.pure, `${version}: lower-bound pure code`);
      assert.equal(snapshot.legacy.mapping.mapped[version], row.legacy, `${version}: lower-bound legacy code`);
      assert.ok(row.pure >= row.legacy, `${version}: pure code must not fall below legacy`);
    }
    assert.equal(contract.version.initial.version, '0.21.0');
    assert.equal(contract.version.initial.versionCode, 2_100_000);
    const upstream = contract.identity.upstreamMobile;
    assert.equal(upstream.adopted, false);
    assert.equal(upstream.mobileVersion, '0.20.1');
  });
  step('contract.signing', 'contract', () => {
    assert.equal(contract.signing.releaseCertificateSha256, snapshot.legacy.certificate.value);
    assert.equal(contract.signing.releaseCertificateSource.file, snapshot.legacy.certificate.repoPath);
    assert.equal(contract.signing.releaseCertificateSource.line, snapshot.legacy.certificate.line);
    assert.deepEqual(contract.signing.requiredSchemes, ['v2', 'v3']);
    assert.equal(contract.signing.rotation, 'forbidden');
    assert.equal(contract.signing.debugCertificateSha256, snapshot.packages.find(entry => entry.path.includes('deep-student-debug.apk')).signature.signerSha256);
    assert.equal(contract.signing.releasePackageObserved, false);
  });
  step('contract.packages', 'contract', () => {
    assert.equal(contract.packages.observed.length, snapshot.packages.length);
    for (const row of contract.packages.observed) {
      const found = snapshot.packages.find(entry => entry.path === row.path);
      assert.ok(found, `contract package ${row.path} not observed`);
      assert.equal(row.repo, found.origin.repo, `${row.path}: repo id`);
      assert.equal(row.packageName, found.badging.packageName);
      assert.equal(row.versionCode, found.badging.versionCode);
      assert.equal(row.versionName, found.badging.versionName);
      assert.equal(row.sha256, found.sha256);
      assert.equal(row.certificateSha256, found.signature.signerSha256);
    }
  });
  step('contract.native16k', 'contract', () => {
    assert.deepEqual(contract.native16k.tasks.map(task => task.assetId).sort(), snapshot.native16k.tasks.map(task => task.assetId).sort());
    assert.equal(contract.native16k.tasks.length, 3);
    assert.ok(contract.native16k.requirements.nativeLibraryDir.includes('nativeLibraryDir'));
    assert.ok(contract.native16k.requirements.legacyPackaging.includes('useLegacyPackaging = true'));
    assert.ok(contract.native16k.requirements.zipAlignment.includes('-P 16'));
    const channels = contract.native16k.acceptance;
    assert.deepEqual(Object.keys(channels).sort(), ['debug', 'deviceRuntime', 'hostStatic', 'release']);
    for (const item of channels.hostStatic) {
      assert.equal(item.runsInThisUnit, false, `${item.id}: host static step must not claim execution`);
      assert.ok(!/readelf -lW -/u.test(item.command), `${item.id}: readelf must not read stdin`);
    }
    const packaged = channels.hostStatic.find(item => item.id === 'packaged-native');
    assert.match(packaged.command, /mktemp -d/u);
    assert.match(packaged.command, /trap /u);
    assert.match(packaged.command, /unzip -o -q <apk>/u);
    assert.match(packaged.command, /readelf -lW "\$tmp\//u);
    assert.equal(channels.debug.executedInThisUnit, false);
    assert.equal(channels.release.executedInThisUnit, false);
    assert.ok(channels.debug.note.includes('run-as'), 'debug channel must scope run-as');
    for (const item of channels.debug.commands) assert.equal(item.execution, 'adb-debug', `${item.id}: debug channel execution`);
    for (const item of channels.release.commands) assert.equal(item.execution, 'in-app-diagnostic', `${item.id}: release channel execution`);
    for (const item of channels.deviceRuntime.commands) assert.equal(item.execution, 'device-runtime', `${item.id}: device channel execution`);
    assert.ok(channels.debug.commands.every(item => !/echo \$PROOT_LOADER/u.test(item.command)), 'debug channel must not read env through run-as');
    assert.deepEqual(channels.release.requiredFields, ['nativeLibraryDir', 'PROOT_LOADER', 'LD_LIBRARY_PATH', 'cwd', 'uid']);
    assert.deepEqual(channels.release.owners, ['S6.01', 'S9.13']);
    assert.ok(channels.release.note.includes('ExecutionProfile'));
    assert.ok(!channels.hostStatic.some(item => /adb shell/u.test(item.command)), 'host static channel must not depend on adb');
    assert.equal(channels.deviceRuntime.executedInThisUnit, false);
    assert.ok(channels.deviceRuntime.commands.some(item => item.id === 'page-size-device'));
    assert.ok(channels.deviceRuntime.commands.some(item => item.id === 'runtime-forkpty'));
  });
  step('contract.exclusions', 'contract', () => {
    assert.equal(contract.exclusions.desktopApk, snapshot.exclusions.desktopApk.decision);
    assert.equal(contract.exclusions.exampleJars, snapshot.exclusions.exampleJars.decision);
    assert.equal(contract.exclusions.subpackKeystores, snapshot.exclusions.subpackKeystores.decision);
    assert.equal(contract.exclusions.operitAssets, snapshot.exclusions.operitAssets.decision);
    assert.equal(snapshot.exclusions.desktopApk.assetReads.length, 0);
    assert.ok(snapshot.exclusions.desktopApk.packageLaunchReferences.length > 0);
    assert.equal(snapshot.exclusions.exampleJars.dstuAndroidJars.length, 0);
    assert.equal(snapshot.exclusions.exampleJars.includedInOperitSettings, false);
    assert.ok(snapshot.exclusions.subpackKeystores.files.every(item => item.bytesRead === false));
  });
  step('contract.releasePackage', 'contract', () => {
    assert.equal(contract.releasePackage.observed, false);
    assert.equal(contract.releasePackage.reason, 'no release-signed APK exists in the workspace');
    assert.deepEqual(contract.releasePackage.debugSigned, snapshot.packages.filter(entry => entry.debugSigned).map(entry => entry.path));
    assert.equal(contract.releasePackage.nextVerification, 'S9.15');
  });
  step('contract.followUps', 'contract', () => {
    const allowed = new Set(['S1.01', 'S6.01', 'S6.15', 'S6.21', 'S9.13', 'S9.15', 'S0.16', 'S0.20']);
    for (const followUp of contract.followUps) assert.ok(allowed.has(followUp.unit), `unknown follow-up unit ${followUp.unit}`);
    assert.ok(contract.followUps.some(followUp => followUp.unit === 'S1.01'));
  });
}

function versionCases(legacyMapping, mobileBuildScriptCode, initialVersion) {
  step('version.accepted', 'version', () => {
    const accepted = { '0.21.0': 2_100_000, '0.21.1': 2_100_001, '0.21.99': 2_100_099, '0.21.99999': 2_199_999, '0.22.0': 2_200_000, '0.99.99': 9_900_099, '1.0.0': 10_000_000, '1.2.3': 10_200_003, '2.0.0': 20_000_000, '210.0.0': MAX_VERSION_CODE };
    for (const [version, expected] of Object.entries(accepted)) assert.equal(versionCode(version), expected, version);
  });
  step('version.rejected', 'version', () => {
    const rejected = ['1.0', '1.0.0-beta', '1.0.0+build', '01.0.0', '1.00.0', '1.0.00', '', '1.100.0', '1.0.100000', '211.0.0', '1.0.0 '];
    for (const version of rejected) assert.throws(() => versionCode(version), undefined, `must reject ${JSON.stringify(version)}`);
    for (const value of [undefined, null, 1, {}, ['1.0.0']]) assert.throws(() => versionCode(value), undefined, `must reject non-string ${JSON.stringify(value)}`);
  });
  step('version.releaseLine', 'version', () => {
    assert.equal(releaseVersion('0.21.0').versionCode, 2_100_000);
    assert.throws(() => releaseVersion('0.20.1'), /predates the new release line/u);
    assert.throws(() => releaseVersion(OBSERVED_VERSION_NAME), /predates the new release line/u);
    assert.equal(releaseVersion('1.0.0').versionCode, 10_000_000);
  });
  step('version.monotonic', 'version', () => {
    const ordered = ['0.21.0', '0.21.1', '0.21.99', '0.22.0', '0.99.99', '1.0.0', '1.2.3', '2.0.0', '210.0.0'];
    for (let index = 1; index < ordered.length; index += 1) {
      assert.ok(versionCode(ordered[index]) > versionCode(ordered[index - 1]), `${ordered[index - 1]} < ${ordered[index]}`);
      assert.equal(isUpgrade(ordered[index - 1], ordered[index]), true);
    }
    assert.equal(isUpgrade('0.21.1', '0.21.0'), false);
    assert.equal(isUpgrade('0.21.0', '0.21.0'), false);
  });
  step('version.legacyUpperBound', 'version', () => {
    for (const [version, code] of Object.entries(legacyMapping.mapped)) {
      assert.ok(versionCode(version) > code, `${version}: pure mapping must exceed legacy ${code}`);
      const legacyOffset = versionCode(version) - code;
      assert.equal(legacyOffset, 885_414, `${version}: pure mapping must sit 885414 above the legacy offset mapping`);
      try {
        const released = releaseVersion(version);
        assert.ok(released.versionCode > code, `${version}: release code must exceed legacy ${code}`);
      } catch (error) {
        assert.match(error.message, /predates the new release line/u, `${version}: only the release line may reject a legacy version`);
      }
    }
    assert.ok(releaseVersion('0.21.0').versionCode > OBSERVED_VERSION_CODE);
    assert.ok(releaseVersion('0.21.0').versionCode > legacyMapping.mapped['0.21.0']);
  });
  step('version.verifyRelease', 'version', () => {
    assert.deepEqual(verifyRelease('0.21.0', 2_100_000), { ok: true, expected: 2_100_000, actual: 2_100_000, reason: null });
    const downgrade = verifyRelease('0.21.0', OBSERVED_VERSION_CODE);
    assert.equal(downgrade.ok, false);
    assert.match(downgrade.reason, /does not match/u);
    for (const bad of [0, -1, 2_100_001, 1.5, Number.NaN, null, '2100000']) assert.equal(verifyRelease('0.21.0', bad).ok, false, `must reject ${String(bad)}`);
    assert.equal(verifyRelease(OBSERVED_VERSION_NAME, OBSERVED_VERSION_CODE).ok, false);
  });
  step('version.lowerBound', 'version', () => {
    const knownCodes = {
      observedPackage: OBSERVED_VERSION_CODE,
      mobileBuildScriptConstant: mobileBuildScriptCode,
      legacyAnchor: LEGACY_ANCHOR.versionCode,
      legacyPublishedAnchor: LEGACY_ANCHOR.publishedVersionCode,
    };
    for (const [name, code] of Object.entries(knownCodes)) {
      assert.ok(initialVersion.versionCode > code, `${name} ${code}: initial release ${initialVersion.versionCode} must be greater`);
    }
    for (const [version, code] of Object.entries(legacyMapping.mapped)) {
      assert.ok(versionCode(version) >= code, `${version}: pure mapping must be >= legacy ${code}`);
    }
    assert.ok(initialVersion.versionCode >= OBSERVED_VERSION_CODE);
    return { ...knownCodes, perVersion: Object.fromEntries(Object.entries(legacyMapping.mapped).map(([version, code]) => [version, { pure: versionCode(version), legacy: code }])) };
  });
  step('version.requiredMatrix', 'version', () => {
    const expected = {
      '0.20.0': { publishable: false, newCode: 2_000_000, legacyCode: 1_114_586 },
      '0.20.1': { publishable: false, newCode: 2_000_001, legacyCode: 1_114_587 },
      '0.21.0': { publishable: true, newCode: 2_100_000, legacyCode: 1_214_586 },
      '1.0.0': { publishable: true, newCode: 10_000_000, legacyCode: 9_114_586 },
    };
    for (const [version, row] of Object.entries(expected)) {
      assert.equal(versionCode(version), row.newCode, `${version}: pure mapping`);
      assert.equal(legacyMapping.mapped[version], row.legacyCode, `${version}: legacy mapping`);
      assert.ok(row.newCode > row.legacyCode, `${version}: new code must exceed legacy`);
      if (row.publishable) {
        assert.equal(releaseVersion(version).versionCode, row.newCode, `${version}: publishable`);
      } else {
        assert.throws(() => releaseVersion(version), /predates the new release line/u, `${version}: legacy version must not publish`);
      }
    }
    assert.ok(2_100_000 > OBSERVED_VERSION_CODE);
    return expected;
  });
  step('version.offsetRule', 'version', () => {
    for (const version of ['0.21.0', '0.21.7', '0.42.13', '1.0.0', '3.14.15']) {
      const [major, minor, patch] = version.split('.').map(Number);
      assert.equal(versionCode(version), major * 10_000_000 + minor * 100_000 + patch, `${version}: offset must be exactly zero`);
      assert.equal(isUpgrade('0.21.0', version) || version === '0.21.0', true, `${version}: must not regress below the release line`);
    }
    assert.equal(versionCode('0.21.1') - versionCode('0.21.0'), 1, 'patch step is one');
    assert.equal(versionCode('0.22.0') - versionCode('0.21.99'), 100_000 - 99, 'minor step crosses patch space');
    assert.equal(versionCode('1.0.0') - versionCode('0.99.99'), 10_000_000 - 9_900_099, 'major step crosses minor space');
  });
}

async function collect() {
  const toolFacts = step('tools.executable', 'environment', collectTools);
  step('environment.workspace', 'environment', () => {
    const state = collectWorkspaceState();
    assert.equal(state.dstuAndroid.hasGradleProject, false, 'S0.15 must not create the Gradle project (S1.01)');
    for (const [repo, changes] of Object.entries(state.trackedChanges)) assert.equal(changes, '', `${repo}: tracked source changes`);
  });
  step('environment.desktopFreeze', 'environment', () => {
    assert.equal(git(desktop, 'rev-parse', 'HEAD'), desktopCommit, 'desktop checkout must stay at the fixed commit');
    assert.equal(git(desktop, 'status', '--porcelain', '--untracked-files=no'), '', 'desktop tracked files must stay clean');
  });
  const legacy = step('legacy.sources', 'legacy', parseLegacySources);
  assert.equal(legacy.buildApk.identity.applicationId.value, 'com.deepstudent.app');
  assert.equal(legacy.buildApk.identity.versionName.value, OBSERVED_VERSION_NAME);
  assert.equal(legacy.buildApk.identity.versionCode.value, OBSERVED_VERSION_CODE);
  assert.equal(legacy.buildApk.zipalign.args.join(' '), '-P 16 -v 4');
  assert.equal(legacy.generateVersion.constants.androidVersionCode.value, LEGACY_ANCHOR.versionCode);
  assert.equal(legacy.generateVersion.constants.publishedAndroidVersionCode.value, LEGACY_ANCHOR.publishedVersionCode);
  assert.equal(legacy.generateVersion.constants.baseAppVersion.value, LEGACY_ANCHOR.version);
  assert.equal(legacy.generateVersion.constants.buildNumberBase.value, LEGACY_ANCHOR.buildNumberBase);
  assert.equal(legacy.tauri.androidVersionCode, LEGACY_ANCHOR.versionCode);
  assert.equal(legacy.tauri.identifier, 'com.deepstudent.app');
  const certificateFacts = step('legacy.certificate', 'legacy', certificate);
  const legacyMapping = await step('legacy.mappingExecution', 'legacy', () => resolveLegacyMapping(legacy));
  assert.equal(legacyMapping.mapped[OBSERVED_VERSION_NAME], OBSERVED_VERSION_CODE);
  assert.equal(legacyMapping.mapped['0.9.55'], LEGACY_ANCHOR.versionCode);
  assert.equal(legacyMapping.rejected.length, 5);
  const initialVersion = step('version.initial', 'version', () => releaseVersion(INITIAL_VERSION));
  step('version.identityFreeze', 'version', () => {
    assert.deepEqual(initialVersion, { version: '0.21.0', versionCode: 2_100_000 });
    assert.equal(legacy.buildApk.identity.versionName.value, publicationIdentity.version);
    assert.equal(legacy.buildApk.identity.versionCode.value, publicationIdentity.versionCode);
    assert.equal(legacy.mobile.version, '0.20.1');
    assert.notEqual(legacy.mobile.version, publicationIdentity.version);
    assert.equal(legacy.mobile.adoptedForNewBuilds, false);
    assert.equal(legacy.fixedHead.commit, desktopCommit);
    assert.equal(legacy.buildApk.commit, legacyEvidenceCommit);
  });
  versionCases(legacyMapping, legacy.buildApk.identity.versionCode.value, initialVersion);

  const packages = step('packages.inspect', 'packages', () => {
    const items = enumeratePackages();
    assert.ok(items.length >= 4, `expected at least four workspace packages, found ${items.length}`);
    const inspected = items.map(item => inspectPackage(item, certificateFacts.value)).sort((a, b) => a.path.localeCompare(b.path));
    for (const entry of inspected) {
      assert.ok(entry.badging.packageName, `${entry.path}: package name`);
      assert.ok(entry.badging.versionCode > 0, `${entry.path}: versionCode`);
      assert.equal(entry.signature.verified, true, `${entry.path}: signature must verify`);
      assert.equal(entry.zipAlignment16k.passed, true, `${entry.path}: zipalign -P 16 must pass`);
      assert.ok(entry.zipAlignment16k.checkedEntries > 0, `${entry.path}: zipalign checked entries`);
      log('platform.package', {
        path: entry.path,
        repo: entry.origin.repo,
        tracked: entry.origin.tracked,
        bytes: entry.bytes,
        packageName: entry.badging.packageName,
        versionCode: entry.badging.versionCode,
        versionName: entry.badging.versionName,
        minSdk: entry.badging.minSdk,
        targetSdk: entry.badging.targetSdk,
        compileSdk: entry.badging.compileSdk,
        nativeCode: entry.badging.nativeCode,
        zipNativeAbis: entry.zip.nativeAbis,
        signerDn: entry.signature.signerDn,
        signerSha256: entry.signature.signerSha256,
        schemes: entry.signature.schemes,
        releaseCertificateMatch: entry.releaseCertificateMatch,
        debugSigned: entry.debugSigned,
        zipAlignment16k: { passed: entry.zipAlignment16k.passed, summary: entry.zipAlignment16k.summary, entries: entry.zipAlignment16k.checkedEntries },
        native16k: entry.native16k,
        extractNativeLibs: entry.manifestFlags.extractNativeLibs,
      });
    }
    assert.equal(inspected.filter(entry => entry.releaseCertificateMatch).length, 0, 'no release-signed package is observed');
    const debug = inspected.filter(entry => entry.debugSigned);
    assert.equal(debug.length, 1, 'exactly one debug-signed dstu package is observed');
    assert.equal(debug[0].badging.packageName, 'com.deepstudent.app');
    assert.equal(debug[0].badging.versionCode, OBSERVED_VERSION_CODE);
    assert.equal(debug[0].badging.versionName, OBSERVED_VERSION_NAME);
    assert.equal(debug[0].badging.minSdk, 24);
    assert.equal(debug[0].badging.targetSdk, 36);
    assert.equal(debug[0].badging.compileSdk, 36);
    assert.equal(debug[0].manifestFlags.debuggable, true);
    assert.equal(debug[0].manifestFlags.extractNativeLibs, false);
    assert.equal(debug[0].zip.nativeEntries, 0);
    for (const entry of inspected.filter(pkg => pkg.origin.repo === 'operit')) {
      assert.ok(entry.zip.nativeAbis.includes('arm64-v8a'), `${entry.path}: arm64 payload`);
      assert.ok(entry.native16k.allPass, `${entry.path}: arm64 payload keeps 16 KiB LOAD alignment`);
    }
    assert.ok(inspected.every(entry => !/^deep-student-release/u.test(entry.path)), 'no release APK filename is present');
    return inspected;
  });

  const gradle = step('platform.gradleFacts', 'platform', () => {
    const facts = collectGradleFacts();
    assert.equal(facts.modules.length, 9);
    for (const module of facts.modules) {
      assert.equal(module.minSdk, 26, `${module.id}: minSdk`);
      assert.equal(module.compileSdk, 36, `${module.id}: compileSdk`);
      if (module.id !== 'showerclient') assert.deepEqual(module.abiFilters, ['arm64-v8a'], `${module.id}: abiFilters`);
    }
    const app = facts.modules.find(module => module.id === 'app');
    assert.equal(app.targetSdk, 34);
    assert.equal(app.useLegacyPackaging, true);
    assert.equal(facts.modules.find(module => module.id === 'terminal').useLegacyPackaging, true);
    assert.equal(facts.catalog.coreKtx, '1.18.0');
    assert.equal(facts.catalog.composeBom, '2026.02.01');
    assert.equal(facts.catalog.composeUi, '1.10.4');
    assert.equal(facts.catalog.activityCompose, '1.8.2');
    assert.equal(facts.catalog.agp, '8.13.2');
    assert.equal(facts.catalog.kotlin, '2.2.21');
    return facts;
  });
  const aarFacts = step('platform.aarManifests', 'platform', () => {
    const facts = collectAarFacts(gradle);
    assert.ok(facts.aarCount >= 40, `expected at least 40 cached AARs, found ${facts.aarCount}`);
    assert.ok(facts.maxDeclaredMinSdk <= 26, `measured AAR minSdk ${facts.maxDeclaredMinSdk} exceeds minSdk 26`);
    assert.equal(facts.parsedManifestCount, facts.aarCount, 'every cached AAR must declare a parseable minSdk');
    assert.equal(facts.requestedArtifacts.filter(item => item.observed).length, 0, 'requested Compose/core artifacts are absent from the local cache');
    return facts;
  });
  const exclusions = step('packages.exclusions', 'exclusions', () => collectExclusions(packages));
  const page16kTasks = step('native16k.tasks', '16k', collectPage16kTasks);
  step('native16k.contract', '16k', () => {
    assert.equal(page16kTasks.length, 3);
    for (const task of page16kTasks) {
      assert.ok(task.deliveryUnit, `${task.assetId}: delivery unit`);
      assert.ok(task.observedMinLoadAlign < 16384, `${task.assetId}: observed failure`);
      const total = Object.values(task.acceptance).reduce((count, channel) => count + (Array.isArray(channel) ? channel.length : channel.commands.length), 0);
      assert.ok(total >= 9, `${task.assetId}: acceptance steps`);
      assert.equal(task.acceptance.hostStatic.length, 4);
      assert.equal(task.acceptance.debug.commands.length, 2);
      assert.equal(task.acceptance.release.commands.length, 2);
      assert.equal(task.acceptance.deviceRuntime.commands.length, 3);
    }
  });

  const contract = readJson(contractFile);
  const snapshot = {
    formatVersion: 1,
    unit,
    delivery: { platformBaseline: file, versionSource: 'tools/baseline/version.mjs', contract: contractFile, next: 'S0.16' },
    policy: {
      packages: 'Packages are enumerated only from the workspace artifacts directory and git-tracked *.apk/*.aab in dstu-node, Operit and dstu-android. No user-disk search and no download.',
      releasePackage: 'A package counts as release-verified only when apksigner reports the published certificate. The observed dstu package is debug-signed, so the release package is not observed.',
      signature: 'Only public certificate data from apksigner --print-certs is read. No keystore, alias or password is opened; no signing, install or data operation is performed.',
      tools: 'Absolute executables from the installed Android SDK build-tools 36.0.0 plus host readelf and unzip; paths and versions are recorded.',
      version: 'The version mapping is a pure function in tools/baseline/version.mjs. Capture and verify both execute it, and the legacy generators are executed read-only for comparison.',
    },
    evidence: {
      plan: {
        file: planFile,
        unitLine: lineOf(readRoot(planFile).toString('utf8'), '| S0.15 | 核实平台与发布身份'),
        releaseSection: lineOf(readRoot(planFile).toString('utf8'), '## 13. 发布与旧链退役'),
        legacyComparisonLine: lineOf(readRoot(planFile).toString('utf8'), 'S0.15并列核对'),
        scheduleLine: lineOf(readRoot(planFile).toString('utf8'), 'Operit终端移植基线为API26、arm64'),
      },
      contract: { file: contractFile, sha256: sha256(readRoot(contractFile)) },
      sourceLock: (() => {
        const lock = readJson('config/source-lock.json');
        const repository = id => lock.repositories.find(item => item.id === id);
        return {
          file: 'config/source-lock.json',
          sha256: sha256(readRoot('config/source-lock.json')),
          desktopCommit: repository('desktop').commit,
          operitCommit: repository('operit').commit,
          operitTree: repository('operit').tree,
          terminalCommit: repository('terminal').commit,
          terminalTree: repository('terminal').tree,
        };
      })(),
      runtimeAssets: { file: runtimeAssetsFile, sha256: sha256(readRoot(runtimeAssetsFile)), page16kFailures: readJson(runtimeAssetsFile).page16k.fail },
    },
    tools: toolFacts,
    legacy: {
      evidence: legacy.evidence,
      fixedHead: legacy.fixedHead,
      upstreamMobileDelta: legacy.upstreamMobileDelta,
      buildApk: legacy.buildApk,
      generateVersion: legacy.generateVersion,
      tauri: legacy.tauri,
      mobile: legacy.mobile,
      certificate: { file: certFile, repoPath: certificateFacts.repoPath, workspacePath: certificateFacts.workspacePath, line: certificateFacts.line, value: certificateFacts.value, sha256: certificateFacts.sha256 },
      mapping: legacyMapping,
    },
    version: {
      source: 'tools/baseline/version.mjs',
      mapping: { formula: 'major * 10000000 + minor * 100000 + patch', majorStride: MAJOR_STRIDE, minorStride: MINOR_STRIDE, maxMinor: MAX_MINOR, maxPatch: MAX_PATCH, maxVersionCode: MAX_VERSION_CODE, offset: 0 },
      initial: { version: initialVersion.version, versionCode: initialVersion.versionCode },
      observedPackage: { version: OBSERVED_VERSION_NAME, versionCode: OBSERVED_VERSION_CODE },
      legacyComparison: legacyMapping.mapped,
      monotonic: ['0.21.0', '0.21.1', '0.21.99', '0.22.0', '0.99.99', '1.0.0', '1.2.3', '2.0.0', '210.0.0'].map(version => ({ version, versionCode: versionCode(version) })),
    },
    platform: {
      gradle,
      aarFacts,
      decision: {
        compileSdk: 36,
        targetSdk: 36,
        minSdk: 26,
        abi: ['arm64-v8a'],
        withheldAbi: ['x86_64'],
        minSdkBasis: [
          'Operit app, terminal, quickjs, llama, mnn, showerclient and avator modules all declare minSdk 26 (measured above).',
          'The desktop APK observed at 0.20.0 declares minSdk 24, but API 24/25 support would need separate source adaptation and device tests (plan section 12.1).',
          'No locally measured AAR declares a minSdk above 26; the highest measured value is 24 (Cordova framework 14.0.1).',
        ],
        targetSdkBasis: [
          'Desktop APK 0.20.0 already targets 36 and is installed on the same users.',
          'Operit targets 34; raising to 36 is recorded because Android 15/16 behavior changes must be tested in S9.',
        ],
      },
      sdk: toolFacts.sdk,
    },
    packages,
    workspace: collectWorkspaceState(),
    exclusions,
    native16k: { tasks: page16kTasks, failureCount: page16kTasks.length },
    checks: null,
  };
  validateContract(contract, snapshot);
  snapshot.checks = { cases: cases.length, pass: cases.filter(entry => entry.status === 'pass').length };
  return snapshot;
}

export async function run(mode) {
  const started = performance.now();
  const snapshot = await collect();
  artifact(file, snapshot, mode);
  log('platform.complete', {
    unit,
    mode,
    cases: snapshot.checks.cases,
    passed: snapshot.checks.pass,
    packages: snapshot.packages.map(entry => ({ path: entry.path, packageName: entry.badging.packageName, versionName: entry.badging.versionName, versionCode: entry.badging.versionCode, certificate: entry.signature.signerSha256 })),
    initialVersion: snapshot.version.initial,
    elapsedMs: Math.round(performance.now() - started),
  });
}

main(import.meta.url, run);
