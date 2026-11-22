import assert from 'node:assert/strict';
import { appendFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { releaseVersion } from '../baseline/version.mjs';

export function releaseIdentity(refType, refName) {
  assert.equal(refType, 'tag', 'release must run on an existing version tag, never a branch');
  assert.match(refName ?? '', /^v\d+\.\d+\.\d+$/u, 'release tag must be vMAJOR.MINOR.PATCH');
  return releaseVersion(refName.slice(1));
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const identity = releaseIdentity(process.env.GITHUB_REF_TYPE, process.env.GITHUB_REF_NAME);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `version=${identity.version}\n`);
  console.log(JSON.stringify(identity));
}
