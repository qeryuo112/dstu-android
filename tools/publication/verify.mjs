import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { assertPublicPath, sourceRoot, verifyPublicHistory } from './export.mjs';
const paths = execFileSync('git', ['ls-files', '-z'], { cwd: sourceRoot }).toString('utf8').split('\0').filter(Boolean);
paths.forEach(assertPublicPath);
const isPublic = existsSync(resolve(sourceRoot, 'config/publication/export.json'));
const history = isPublic ? verifyPublicHistory(sourceRoot) : null;
console.log(JSON.stringify({ event: 'publication.boundary.verified', sourceFiles: paths.length,
  checkout: isPublic ? 'public-source-snapshot' : 'private-development-history-not-for-push', history }));
