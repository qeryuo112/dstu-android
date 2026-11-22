import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { loadTerminalLock } from './terminal-source.mjs';

const root = resolve(import.meta.dirname, '../..');
const cache = resolve(
  process.env.GRADLE_USER_HOME || join(root, '../build-tools/s1-gradle-home'),
  'caches/modules-2/files-2.1',
);

function artifact(group, name, version, suffix) {
  const path = join(cache, group, name, version);
  const files = readdirRecursive(path).filter(file => file.endsWith(suffix));
  if (files.length !== 1) throw new Error(`Expected one cached artifact: ${name}:${version}`);
  return files[0];
}

function readdirRecursive(path) {
  return spawnSync('find', [path, '-type', 'f'], { encoding: 'utf8' })
    .stdout.split('\n')
    .filter(Boolean);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    stdio: options.capture ? 'pipe' : 'inherit',
    encoding: 'utf8',
    timeout: options.timeout ?? 180_000,
  });
  if (result.status !== 0) {
    throw new Error(
      `${command} ${args.join(' ')} failed:\n${result.stdout || ''}\n${result.stderr || result.error || result.signal || result.status}`,
    );
  }
  return result.stdout?.trim() || '';
}

const sourceManifest = join(root, 'build/intermediates/dstu-architecture/scan-manifest.json');
const sourceRules = existsSync(sourceManifest)
  ? JSON.parse(readFileSync(sourceManifest, 'utf8')).sourceRules
  : JSON.parse(readFileSync(join(root, 'config/architecture/architecture.json'), 'utf8')).sourceRules;
const terminalLock = loadTerminalLock(root);
const terminalModules = [
  module(':features:terminal:api', 'feature-api', 'api', 'terminal', ['main', 'test']),
  module(':features:terminal:backend', 'feature-backend', 'backend', 'terminal', ['main', 'test']),
  module(':features:terminal:data', 'feature-data', 'data', 'terminal', ['main', 'androidTest']),
  module(':features:terminal:ui', 'feature-ui', 'ui', 'terminal', ['main', 'androidTest']),
  module(':engines:terminal', 'engine', 'engine', null, ['main', 'test', 'androidTest']),
];
terminalModules.find(entry => entry.path === ':engines:terminal').sources.push({
  dir: join(root, 'engines/terminal/src/operit/kotlin'), sourceSet: 'main',
});

function module(path, role, layer, domain, sourceSets) {
  const relative = path.slice(1).replaceAll(':', '/');
  return {
    path,
    role,
    layer,
    domain,
    sources: sourceSets.map(sourceSet => ({
      dir: join(root, relative, 'src', sourceSet),
      sourceSet,
    })),
  };
}

const temp = mkdtempSync(join(tmpdir(), 'dstu-terminal-arch-'));
try {
  checkSourceBoundaries();
  const manifest = {
    formatVersion: 1,
    root,
    modules: terminalModules,
    sourceRules,
    architectureExceptions: [],
    complexityExceptions: JSON.parse(readFileSync(join(root, 'config/architecture/complexity.json'), 'utf8')).exceptions,
    thresholds: JSON.parse(readFileSync(join(root, 'config/architecture/complexity.json'), 'utf8')).thresholds,
    architectureSourceSets: ['main', 'test', 'androidTest'],
    complexitySourceSets: ['main', 'test', 'androidTest'],
  };
  const manifestPath = join(temp, 'scan-manifest.json');
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));

  const analyzer = join(root, 'build-logic/architecture-analyzer/build/libs/dstu-architecture-analyzer.jar');
  const classpath = [
    analyzer,
    artifact('com.google.code.gson', 'gson', '2.11.0', '.jar'),
    artifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21', '.jar'),
    artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21', '.jar'),
    artifact('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21', '.jar'),
    artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0', '.jar'),
  ].join(':');
  const java = join(process.env.JAVA_HOME || '/usr/lib/jvm/zulu21', 'bin/java');
  for (const mode of ['architecture', 'complexity']) {
    const output = run(
      java,
      ['-cp', classpath, 'com.dstu.architecture.analyzer.AnalyzerMain',
        '--mode', mode, '--manifest', manifestPath, '--report', join(temp, `${mode}.json`)],
      { capture: true },
    );
    console.log(output);
  }
  console.log('[terminal-verify] self-owned terminal sources pass; exact compiled vendor exceptions are checked by the root Gradle gate');
} finally {
  rmSync(temp, { recursive: true, force: true });
}

function checkSourceBoundaries() {
  const engineBuild = readFileSync(join(root, 'engines/terminal/build.gradle.kts'), 'utf8');
  if (engineBuild.includes('features:terminal')) {
    throw new Error('engines/terminal must not depend on features/terminal');
  }
  const uiBuild = readFileSync(join(root, 'features/terminal/ui/build.gradle.kts'), 'utf8');
  if (/(engines:terminal|features:terminal:(backend|data))/.test(uiBuild)) {
    throw new Error('terminal UI must not depend on engine/backend/data');
  }
  const uiSources = filesUnder(join(root, 'features/terminal/ui/src/main/kotlin'));
  for (const file of uiSources) {
    const text = readFileSync(file, 'utf8');
    const forbidden = [
      'com.deepstudent.engines.terminal',
      'com.deepstudent.features.terminal.backend',
      'com.deepstudent.features.terminal.data',
      'System.loadLibrary',
      'android.os.ParcelFileDescriptor',
      'ProcessBuilder',
    ];
    if (forbidden.some(value => text.includes(value)) || /\bkill\s*\(/.test(text)) {
      throw new Error(`Terminal UI boundary violation: ${file}`);
    }
  }
  const engineSources = filesUnder(join(root, 'engines/terminal/src/main/kotlin'));
  for (const file of engineSources) {
    if (readFileSync(file, 'utf8').includes('com.deepstudent.features.terminal')) {
      throw new Error(`Terminal engine boundary violation: ${file}`);
    }
  }
}

function filesUnder(directory) {
  return spawnSync('find', [directory, '-type', 'f', '-name', '*.kt'], { encoding: 'utf8' })
    .stdout.split('\n')
    .filter(Boolean);
}
