import { mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
const cache = resolve(process.env.GRADLE_USER_HOME || join(root, '../build-tools/s1-gradle-home'), 'caches/modules-2/files-2.1');

function artifact(group, name, version, suffix) {
  const path = join(cache, group, name, version);
  const files = readdirSync(path).flatMap(hash =>
    readdirSync(join(path, hash)).filter(file => file.endsWith(suffix)).map(file => join(path, hash, file)),
  );
  if (files.length !== 1) throw new Error(`Expected one cached artifact: ${name}:${version}`);
  return files[0];
}

const jar = (group, name, version) => artifact(group, name, version, '.jar');
const kotlin = (name, version = '2.2.21') => jar('org.jetbrains.kotlin', name, version);
const compiler = [
  kotlin('kotlin-compiler-embeddable'),
  kotlin('kotlin-stdlib'),
  kotlin('kotlin-script-runtime'),
  kotlin('kotlin-reflect', '1.6.10'),
  kotlin('kotlin-daemon-embeddable'),
  jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
  jar('org.jetbrains', 'annotations', '23.0.0'),
];
const aars = [
  ['androidx.compose.runtime', 'runtime-android', '1.10.4'],
  ['androidx.compose.runtime', 'runtime-saveable-android', '1.10.4'],
  ['androidx.compose.foundation', 'foundation-android', '1.10.4'],
  ['androidx.compose.foundation', 'foundation-layout-android', '1.10.4'],
  ['androidx.compose.material', 'material-icons-core-android', '1.7.8'],
  ['androidx.compose.material', 'material-icons-extended-android', '1.7.8'],
  ['androidx.compose.material3', 'material3-android', '1.4.0'],
  ...['ui', 'ui-unit', 'ui-graphics', 'ui-text', 'ui-geometry', 'ui-util'].map(name =>
    ['androidx.compose.ui', `${name}-android`, '1.10.4'],
  ),
];

function sources(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory()
      ? sources(join(directory, entry.name))
      : entry.name.endsWith('.kt') ? [join(directory, entry.name)] : [],
  );
}

const temp = mkdtempSync(join(tmpdir(), 'dstu-terminal-ui-'));
try {
  const extracted = aars.map(([group, name, version], index) => {
    const result = spawnSync('unzip', ['-p', artifact(group, name, version, '.aar'), 'classes.jar'], {
      maxBuffer: 100 * 1024 * 1024,
    });
    if (result.status !== 0) throw new Error(`cannot extract ${name}`);
    const output = join(temp, `${index}.jar`);
    writeFileSync(output, result.stdout);
    return output;
  });
  const designClasses = join(root, 'core/design/build/intermediates/runtime_library_classes_dir/debug/bundleLibRuntimeToDirDebug');
  const libs = [
    ...extracted,
    kotlin('kotlin-stdlib'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
    jar('org.jetbrains', 'annotations', '23.0.0'),
    join(process.env.ANDROID_HOME || '/home/xutao/Android/Sdk', 'platforms/android-36/android.jar'),
    designClasses,
  ];
  const args = [
    '-cp', compiler.join(':'),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect',
    '-Xplugin=' + kotlin('kotlin-compose-compiler-plugin-embeddable'),
    '-classpath', libs.join(':'),
    '-d', join(temp, 'out'),
    ...sources(join(root, 'features/terminal/api/src/main/kotlin')),
    ...sources(join(root, 'features/terminal/ui/src/main/kotlin')),
  ];
  const result = spawnSync(join(process.env.JAVA_HOME || '/usr/lib/jvm/zulu21', 'bin/java'), args, {
    cwd: root,
    stdio: 'inherit',
    timeout: 120000,
  });
  if (result.status !== 0) throw new Error(`Kotlin UI compilation failed: ${result.error || result.signal || result.status}`);
  console.log('[terminal-ui] API and Compose UI compiled with cached Android/Compose dependencies');
} finally {
  rmSync(temp, { recursive: true, force: true });
}
