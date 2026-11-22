import { spawnSync } from 'node:child_process';
import { unitTestTasks, root } from './modules.mjs';

const tasks = unitTestTasks(process.argv[2]);
if (!tasks.length) throw new Error('Empty test shard is not a passing CI check');
console.log(JSON.stringify({ event: 'ci.unit-test.shard', group: process.argv[2], tasks }));
const result = spawnSync('./gradlew', [...tasks, '--stacktrace', '--console=plain'], { cwd: root, stdio: 'inherit' });
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
