import { spawn } from 'node:child_process';
import { createWriteStream, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const [name, command, ...args] = process.argv.slice(2);
if (!/^s1-[a-z0-9-]+\.log$/u.test(name ?? '') || !command) throw new Error('usage: logged.mjs s1-name.log command [args]');
mkdirSync('logs', { recursive: true });
const sink = createWriteStream(resolve('logs', name), { flags: 'wx' });
await new Promise((done, reject) => { sink.once('open', done); sink.once('error', reject); });
const started = Date.now();
const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => {
  sink.write(bytes);
  process.stdout.write(bytes);
});
const code = await new Promise((done, reject) => {
  child.once('error', reject);
  child.once('close', value => done(value ?? 1));
});
sink.write(`${JSON.stringify({ event: 's1.command.complete', command, args, code, elapsedMs: Date.now() - started, time: new Date().toISOString() })}\n`);
await new Promise((done, reject) => { sink.once('error', reject); sink.end(done); });
process.exitCode = code;
