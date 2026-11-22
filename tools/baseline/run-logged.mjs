import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createWriteStream, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { root, log } from './lib.mjs';

const [tool, mode, logName] = process.argv.slice(2);
assert.match(tool ?? '', /^[a-z][a-z0-9-]*$/);
assert.ok(['capture', 'verify'].includes(mode));
assert.match(logName ?? '', /^s0-[a-z0-9-]+\.log$/);
mkdirSync(resolve(root, 'logs'), { recursive: true });
const sink = createWriteStream(resolve(root, 'logs', logName));
const child = spawn(process.execPath, [resolve(root, `tools/baseline/${tool}.mjs`), mode], {
  cwd: root, stdio: ['ignore', 'pipe', 'pipe'],
});
for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => sink.write(bytes));
const code = await new Promise((done, reject) => {
  child.once('error', reject);
  child.once('close', code => done(code ?? 1));
});
await new Promise((done, reject) => {
  sink.once('error', reject);
  sink.end(done);
});
log('logged-run.complete', { tool, mode, code, file: `logs/${logName}` });
process.exitCode = code;
