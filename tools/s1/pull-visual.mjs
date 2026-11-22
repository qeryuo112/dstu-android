import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';

const [target, name, output] = process.argv.slice(2);
assert.match(target ?? '', /^com\.deepstudent\.[a-z.]+$/u);
assert.match(name ?? '', /^s1-[a-z0-9-]+\.png$/u);
assert.match(output ?? '', /^s1-[a-z0-9-]+\.png$/u);
const adb = resolve(process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb');
const bytes = execFileSync(adb, ['exec-out', 'run-as', target, 'cat', `cache/${name}`], { maxBuffer: 32 * 1024 * 1024 });
assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
mkdirSync(resolve('logs'), { recursive: true });
writeFileSync(resolve('logs', output), bytes, { flag: 'wx' });
console.log(JSON.stringify({ event: 's1.visual.exported', target, name, output, bytes: bytes.length,
  sha256: createHash('sha256').update(bytes).digest('hex') }));
