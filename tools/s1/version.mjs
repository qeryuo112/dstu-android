import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { releaseVersion } from '../baseline/version.mjs';

export function gradleIdentity(version) {
  const release = releaseVersion(version);
  return `${release.version}:${release.versionCode}`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.stdout.write(`${gradleIdentity(process.argv[2])}\n`);
}
