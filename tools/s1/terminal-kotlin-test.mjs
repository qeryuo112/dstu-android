import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
const cache = resolve(process.env.GRADLE_USER_HOME || join(root, '../build-tools/s1-gradle-home'), 'caches/modules-2/files-2.1');

function jar(group, artifact, version) {
  const path = join(cache, group, artifact, version);
  const files = readdirSync(path).flatMap(hash =>
    readdirSync(join(path, hash)).filter(file => file.endsWith('.jar')).map(file => join(path, hash, file)),
  );
  if (files.length !== 1) throw new Error(`Expected one cached JAR: ${artifact}:${version}`);
  return files[0];
}

const kotlin = (name, version = '2.2.21') => jar('org.jetbrains.kotlin', name, version);
const libs = [
  kotlin('kotlin-stdlib'),
  jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
  jar('com.ibm.icu', 'icu4j', '76.1'),
  jar('org.jetbrains', 'annotations', '23.0.0'),
  jar('junit', 'junit', '4.13.2'),
  jar('org.hamcrest', 'hamcrest-core', '1.3'),
];
const compiler = [
  kotlin('kotlin-compiler-embeddable'),
  kotlin('kotlin-stdlib'),
  kotlin('kotlin-script-runtime'),
  kotlin('kotlin-reflect', '1.6.10'),
  kotlin('kotlin-daemon-embeddable'),
  jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
  jar('org.jetbrains', 'annotations', '23.0.0'),
];

function sources(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory()
      ? sources(join(directory, entry.name))
      : entry.name.endsWith('.kt') ? [join(directory, entry.name)] : [],
  );
}

const temp = mkdtempSync(join(tmpdir(), 'dstu-terminal-kotlin-'));
function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit', timeout: 120000 });
  if (result.status !== 0) throw new Error(`command failed: ${result.error || result.signal || result.status}`);
}

try {
  const generated = join(temp, 'generated');
  const notices = join(temp, 'notices');
  const main = join(temp, 'main');
  const test = join(temp, 'test');
  const android = process.env.ANDROID_HOME || '/home/xutao/Android/Sdk';
  const androidJar = join(android, 'platforms/android-36/android.jar');
  run(process.execPath, ['tools/s1/terminal-prepare.mjs', generated, notices]);
  const java = join(process.env.JAVA_HOME || '/usr/lib/jvm/zulu21', 'bin/java');
  run(java, [
    '-cp', compiler.join(':'),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect',
    '-classpath', [...libs, androidJar].join(':'),
    '-d', main,
    ...sources(generated),
    ...sources(join(root, 'features/terminal/api/src/main/kotlin')),
    ...sources(join(root, 'features/terminal/backend/src/main/kotlin')),
    ...sources(join(root, 'features/terminal/data/src/main/kotlin')),
    ...sources(join(root, 'engines/terminal/src/main/kotlin')),
  ]);
  run(java, [
    '-cp', compiler.join(':'),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect',
    '-Xfriend-paths=' + main,
    '-classpath', [main, ...libs, androidJar].join(':'),
    '-d', test,
    ...sources(join(root, 'testing/fixtures/s1-terminal/android-stubs')),
    ...sources(join(root, 'features/terminal/api/src/test/kotlin')),
    ...sources(join(root, 'features/terminal/backend/src/test/kotlin')),
    ...sources(join(root, 'engines/terminal/src/test/kotlin')),
  ]);
  run(java, [
    '-cp', [test, main, ...libs].join(':'),
    'org.junit.runner.JUnitCore',
    'com.deepstudent.features.terminal.api.TerminalContractsTest',
    'com.deepstudent.features.terminal.backend.TerminalSessionServiceTest',
    'com.deepstudent.engines.terminal.OperitTerminalScreenTest',
  ]);
} finally {
  rmSync(temp, { recursive: true, force: true });
}
