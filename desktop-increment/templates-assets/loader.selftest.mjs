import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { installedIncrementIds, registerIncrement, sourceHash, sourceFile } from '../loader.mjs';

const file = 'host/plugins/sync/src/portable-assets.ts';
const sha256 = sourceHash(file);
const append = marker => text => `${text}\nexport const ${marker} = ${JSON.stringify(marker)};\n`;

const first = registerIncrement({
  unit: 'S0.21',
  id: 'loader-selftest-a',
  files: [{ path: file, sha256, transform: append('__s021LoaderComposeA') }],
});
const second = registerIncrement({
  unit: 'S0.21',
  id: 'loader-selftest-b',
  files: [{ path: file, sha256, transform: append('__s021LoaderComposeB') }],
});

try {
  const module = await import(pathToFileURL(sourceFile(file)).href);
  assert.equal(module.__s021LoaderComposeA, '__s021LoaderComposeA');
  assert.equal(module.__s021LoaderComposeB, '__s021LoaderComposeB');
  assert.deepEqual(installedIncrementIds(), ['loader-selftest-a', 'loader-selftest-b']);
  first.unregister();
  assert.deepEqual(installedIncrementIds(), ['loader-selftest-b']);
  second.unregister();
  assert.deepEqual(installedIncrementIds(), []);
  process.stderr.write(`${JSON.stringify({ event: 'loader.selftest.passed', unit: 'S0.21', composed: 2 })}\n`);
} finally {
  first.unregister();
  second.unregister();
}
