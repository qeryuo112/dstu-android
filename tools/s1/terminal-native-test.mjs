import { mkdirSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
const build = join(root, 'engines/terminal/build/independent-native-test');
const java = process.env.JAVA_HOME || '/usr/lib/jvm/zulu21';

function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit', timeout: 120000 });
  if (result.status !== 0) throw new Error(`command failed: ${result.error || result.signal || result.status}`);
}

mkdirSync(build, { recursive: true });
const host = join(build, 'terminal-native-host-test');
try {
  run(process.execPath, ['tools/s1/terminal-build.mjs', build]);
  run('cc', [
    '-std=gnu11', '-Wall', '-Wextra', '-Werror',
    `-I${join(java, 'include')}`, `-I${join(java, 'include/linux')}`,
    'tools/s1/terminal-native-host-test.c',
    'engines/terminal/src/main/cpp/pty_owner.c',
    '-o', host,
  ]);
  run(host, []);
  const ptyHost = join(build, 'terminal-pty-host-test');
  run('cc', [
    '-std=gnu11', '-Wall', '-Wextra', '-Werror',
    'tools/s1/terminal-pty-host-test.c',
    '-lutil',
    '-o', ptyHost,
  ]);
  run(ptyHost, []);
} finally {
  rmSync(build, { recursive: true, force: true });
}
