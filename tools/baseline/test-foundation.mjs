import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { root, artifact, readJson, sha256, main, log } from './lib.mjs';
import { run as measurement } from './measurement.mjs';
import { run as modelRecordings } from './model-recordings.mjs';

export async function run(mode) {
  const { run: corpus } = await import('./corpus.mjs');
  await corpus(mode);
  await measurement(mode);
  await modelRecordings(mode);
  const files = ['config/baseline/corpus.json', 'config/baseline/measurement.json', 'config/baseline/model-recordings.json'];
  artifact('config/baseline/test-foundation.json', {
    formatVersion: 1, unit: 'S0.17',
    artifacts: files.map(file => ({ file, sha256: sha256(readFileSync(resolve(root, file))) })),
    measurement: readJson(files[1]).checks,
    androidImplementation: 'S1 onward; this unit supplies source-bound corpora and measurement protocols',
  }, mode);
  log('test-foundation.accepted', { unit: 'S0.17', mode, artifacts: files.length });
}

main(import.meta.url, run);
