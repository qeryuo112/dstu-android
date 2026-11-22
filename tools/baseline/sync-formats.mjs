import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { artifact, main, root, sha256, log } from './lib.mjs';

export async function run(mode) {
  assert.ok(['capture', 'verify'].includes(mode));
  for (const tool of ['sync-golden', 'crypto-golden', 'file-golden']) {
    const module = await import(`./${tool}.mjs`);
    await module.run(mode);
  }
  const verifyEntries = (entries, id) => {
    assert.ok(entries.length > 0, `${id}: parent sample was not captured`);
    for (const entry of entries) {
      assert.ok(entry.name && entry.size >= 0 && /^[0-9a-f]{64}$/u.test(entry.sha256), `${id}: invalid entry`);
    }
  };
  const fixtureSource = 'fixtures/legacy-mobile/legacy-mobile-standard-standard-v1.zip';
  const fixtureBytes = readFileSync(resolve(root, fixtureSource));
  const fixtureSha256 = sha256(fixtureBytes);
  assert.equal(fixtureSha256, '3bcecfdd5ec02b0d3b8ee110f2649b6edc136bc9bb6d41b2a5673061ec932eba');
  const legacy = await import('./legacy-export/package.mjs');
  const archive = await legacy.readArchive(resolve(root, fixtureSource));
  verifyEntries(archive.entries, 'legacy fixture');
  const artifacts = ['sync-golden', 'crypto-golden', 'file-golden'].map(id => {
    const file = `config/baseline/${id}.json`;
    return { file, sha256: sha256(readFileSync(resolve(root, file))) };
  });
  const source = artifacts.map(item => {
    const value = JSON.parse(readFileSync(resolve(root, item.file), 'utf8'));
    return { file: item.file, format: value.format ?? null, unit: value.unit };
  });
  const binding = {
    desktopCommit: 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87',
    desktopOnly: true,
    legacyFixtureReused: 'config/baseline/file-golden.json#sourceBinding.fixture',
    v5Production: 'inactive: runSyncV5 is not registered; format v4/min_reader v3 is the writable window',
  };
  artifact('config/baseline/sync-formats.json', { formatVersion: 2, format: 'dstu-s0.18-sync-formats/v2',
    unit: 'S0.18', artifacts, source, binding,
    rules: { hashedBytes: 'exact encoded bytes, not reserialized JSON; no Unicode/newline normalization',
      semantic: 'lossless numbers, explicit optional/null behavior, current reader boundaries',
      cryptoRandomness: 'fixed salt/nonces only in public test vectors; production RNG remains unchanged',
      applicationRuntime: 'desktop golden inputs; Android codecs/apply implemented in S1/S2',
      unknownColumns: 'unknown fields retain exact values and original field versions; rehydrate only columns that exist',
      v5: 'v5 parses for diagnostics, is rejected by format negotiation, and has no registered production command' } }, mode);
  log('sync-formats.accepted', { unit: 'S0.18', mode, artifacts: artifacts.length });
}

main(import.meta.url, run);
