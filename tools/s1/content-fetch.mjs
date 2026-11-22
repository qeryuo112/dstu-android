import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const lock = JSON.parse(readFileSync(resolve(root, 'config/stages/s1-content-sources.json')));
for (const item of [...lock.packages, ...lock.embeddedLicenseOverrides]) {
  execFileSync('npm', ['cache', 'add', `${item.name}@${item.version}`], { stdio: 'inherit' });
}
console.log('Cached locked content source archives; content-assets verifies every SRI before use.');
