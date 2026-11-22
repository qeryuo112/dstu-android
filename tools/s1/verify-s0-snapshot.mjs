import assert from 'node:assert/strict';
import { privateHistoryRepository } from '../publication/frozen-inputs.mjs';
import { execFileSync, spawn } from 'node:child_process';
import { mkdtempSync, rmSync, readdirSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { root, S0_REF, verifyFrozenInputs } from './frozen-inputs.mjs';

const historyRoot = privateHistoryRepository(root);
verifyFrozenInputs();
const temporary = mkdtempSync(resolve(tmpdir(), 'dstu-s1-s0-replay-'));
const snapshot = resolve(temporary, 'dstu-android');
const git = (...args) => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
try {
  git('clone', '--quiet', '--no-hardlinks', '--no-checkout', historyRoot, snapshot);
  git('-C', snapshot, 'checkout', '--quiet', '--detach', S0_REF);
  console.log(JSON.stringify({ event: 's1.s0-snapshot.start', ref: S0_REF,
    mappings: { [root]: snapshot }, policy: 'read-only host and sources; disposable S0 scratch; no capture' }));
  const env = { ...process.env, S0_G_REQUIRE_SIGNED: '1' };
  delete env.ANDROID_HOME;
  delete env.ANDROID_SDK_ROOT;
  const child = spawn('bwrap', [
    '--ro-bind', '/', '/', '--bind', '/tmp', '/tmp', '--dev', '/dev', '--proc', '/proc',
    '--tmpfs', resolve(homedir(), '.config/microsoft-edge'),
    '--bind', snapshot, root, '--chdir', root, '--', process.execPath, 'tools/baseline/verify.mjs',
  ], { cwd: root, env, stdio: 'inherit' });
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  assert.equal(code, 0, 'frozen S0 snapshot failed');
  assert.equal(git('-C', snapshot, 'status', '--porcelain', '--untracked-files=all').trim(), '', 'snapshot left changed inputs or scratch');
  console.log(JSON.stringify({ event: 's1.s0-snapshot.verified', units: 25, gate: 'signed S0.G', ref: S0_REF }));
} finally {
  rmSync(temporary, { recursive: true, force: true });
  assert.ok(!readdirSync(tmpdir()).includes(temporary.split('/').at(-1)), 'snapshot cleanup failed');
}
