import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { root } from './frozen-inputs.mjs';
import { releaseVersion, verifyRelease } from '../baseline/version.mjs';

const apk = resolve(process.argv[2] ?? 'app/build/outputs/apk/debug/app-debug.apk');
const sdk = process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk');
const tools = resolve(sdk, 'build-tools/36.0.0');
const run = (command, args) => execFileSync(command, args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
const contract = JSON.parse(readFileSync(resolve(root, 'config/release-contract.json'), 'utf8'));
const badging = run(resolve(tools, 'aapt2'), ['dump', 'badging', apk]);
const attributes = line => Object.fromEntries([...line.matchAll(/([a-zA-Z][a-zA-Z0-9]*)='([^']*)'/gu)].map(m => [m[1], m[2]]));
const identity = attributes(badging.split('\n').find(line => line.startsWith('package:')) ?? '');
assert.equal(identity.name, contract.identity.applicationId);
const expectedRelease = process.argv[3] ? releaseVersion(process.argv[3]) : {
  version: contract.identity.current.versionName, versionCode: contract.identity.current.versionCode,
};
assert.equal(identity.versionName, expectedRelease.version);
assert.equal(Number(identity.versionCode), expectedRelease.versionCode);
if (process.argv[3]) assert.ok(!badging.includes('application-debuggable'), 'release must not be debuggable');
assert.ok(verifyRelease(identity.versionName, Number(identity.versionCode)).ok, 'single-source version mapping');
const sdkValue = name => Number(badging.match(new RegExp(`^${name}:'(\\d+)'`, 'mu'))?.[1]);
assert.equal(sdkValue('minSdkVersion'), contract.platform.minSdk);
assert.equal(sdkValue('targetSdkVersion'), contract.platform.targetSdk);
assert.equal(Number(identity.compileSdkVersion), contract.platform.compileSdk);
const entries = run('unzip', ['-Z1', apk]).trim().split('\n');
const abis = [...new Set(entries.filter(path => /^lib\/[^/]+\/[^/]+\.so$/u.test(path)).map(path => path.split('/')[1]))].sort();
for (const abi of abis) assert.ok(contract.abi.shipped.includes(abi), `unexpected native ABI ${abi}`);
const forbidden = /(?:^|\/)(?:desktop\.apk|accessibility\.apk|shizuku\.apk|jks\.jks|pkcs12\.keystore|capacitor[^/]*|cordova[^/]*)$/iu;
assert.deepEqual(entries.filter(path => forbidden.test(path)), [], 'legacy assets must not be bundled');
const signature = run(resolve(tools, 'apksigner'), ['verify', '--verbose', '--print-certs', apk]);
assert.match(signature, /Verified using v2 scheme .*: true/u);
const certificate = signature.match(/Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)/u)?.[1].toLowerCase();
assert.match(certificate ?? '', /^[a-f0-9]{64}$/u);
assert.ok(!signature.includes('DOES NOT VERIFY'));
const alignment = run(resolve(tools, 'zipalign'), ['-c', '-P', '16', '-v', '4', apk]);
assert.match(alignment, /Verification successful/u);
const temporary = mkdtempSync(resolve(tmpdir(), 'dstu-s1-apk-'));
const native = [];
try {
  for (const entry of entries.filter(path => /^lib\/[^/]+\/[^/]+\.so$/u.test(path))) {
    run('unzip', ['-q', apk, entry, '-d', temporary]);
    const elf = run('readelf', ['-lW', resolve(temporary, entry)]);
    const aligns = elf.split('\n').filter(line => line.trim().startsWith('LOAD '))
      .map(line => Number(line.trim().split(/\s+/u).at(-1)));
    assert.ok(aligns.length > 0 && aligns.every(value => value >= 16_384), `ELF 16KiB LOAD alignment: ${entry}`);
    native.push({ entry, loadAlignments: aligns });
  }
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
console.log(JSON.stringify({
  time: new Date().toISOString(), event: 's1.apk.verified', owner: 'S1.01', apk,
  sha256: createHash('sha256').update(readFileSync(apk)).digest('hex'), identity,
  minSdk: sdkValue('minSdkVersion'), targetSdk: sdkValue('targetSdkVersion'), abis, native,
  nativeAbsent: abis.length === 0, certificate, signingScope: 'debug-development; not release signoff',
  alignment: '16KiB zip alignment passed', legacyAssets: 'absent',
}));
