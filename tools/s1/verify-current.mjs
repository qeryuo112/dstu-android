import { spawn } from 'node:child_process';
import { root, verifyFrozenInputs } from './frozen-inputs.mjs';

verifyFrozenInputs();
async function run(command, args) {
  const child = spawn(command, args, { cwd: root, stdio: 'inherit', env: process.env });
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  if (code !== 0) throw new Error(`Current engineering verification failed: ${command} ${args.join(' ')} (${code})`);
}

await run(process.execPath, ['--test', 'tools/s1/version.test.mjs', 'tools/s1/progress.test.mjs', 'tools/s1/frozen-inputs.test.mjs', 'tools/s1/instrument-config.test.mjs', 'tools/s1/design.test.mjs', 'tools/s1/content-assets.test.mjs', 'tools/s1/sync-webdav-fixture.test.mjs', 'tools/planning/verify-plan.test.mjs']);
await run(process.execPath, ['--disable-warning=ExperimentalWarning', '--experimental-strip-types', '--test', 'tools/s1/sync-notes-fixture.test.mjs']);
await run(process.execPath, ['--test', 'tools/s1/benchmark.test.mjs', 'tools/s1/benchmark-observation.test.mjs', 'tools/s1/benchmark-sync.test.mjs']);
await run(process.execPath, ['tools/planning/verify-plan.mjs']);
await run(process.execPath, ['tools/s1/progress.mjs']);
await run(process.execPath, ['tools/s1/gradle.mjs', '-p', 'build-logic', 'check', '--no-daemon']);
await run(process.execPath, ['tools/s1/gradle.mjs', ':core:design:verifyDesignTokens', 'verifyArchitecture', 'verifyComplexity', 'test', 'assembleDebug', 'assembleDebugAndroidTest', ':app:writePlatformEvidence', '--no-daemon']);
await run(process.execPath, ['tools/s1/verify-apk.mjs']);
await run(process.execPath, ['tools/s1/test-counts.mjs']);
console.log(JSON.stringify({ time: new Date().toISOString(), event: 's1.current.verified', scope: 'host engineering; device tests run separately; not S1.G signoff' }));
