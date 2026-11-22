#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, readFileSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { hash, readJson } from './benchmark-lib.mjs';
import { ROOT, sourceFingerprint } from './benchmark-provenance.mjs';

const usage = 'Usage: benchmark-cost.mjs run --out DIR --label build|test -- COMMAND [ARGS...]\n       benchmark-cost.mjs collect --out DIR --build FILE --test FILE --graph FILE --complexity FILE --detekt FILE';
function parse(args) {
  const mode = args.shift(), options = {};
  while (args.length && args[0] !== '--') {
    const key = args.shift(), value = args.shift();
    if (!key?.startsWith('--') || !value || value.startsWith('--')) throw new Error(usage);
    options[key.slice(2)] = value;
  }
  if (args.shift() === '--') options.command = args;
  return { mode, options };
}
function outDir(path) {
  if (!path) throw new Error('--out is required');
  mkdirSync(resolve(path), { recursive: true, mode: 0o700 });
  const out = realpathSync(path), rel = relative(realpathSync(ROOT), out);
  if (rel === '' || (!rel.startsWith(`..${sep}`) && rel !== '..' && !isAbsolute(rel))) throw new Error('Cost evidence must be outside the repository');
  return out;
}
function save(path, data) { writeFileSync(path, typeof data === 'string' ? data : `${JSON.stringify(data, null, 2)}\n`, { flag: 'wx', mode: 0o600 }); }
function run(options) {
  if (!['build', 'test'].includes(options.label) || !options.command?.length) throw new Error(usage);
  const dir = outDir(options.out), before = sourceFingerprint(), startEpochMs = Date.now(), start = process.hrtime.bigint();
  const child = spawnSync(options.command[0], options.command.slice(1), { cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const elapsedMs = Number(process.hrtime.bigint() - start) / 1e6, endEpochMs = Date.now(), after = sourceFingerprint();
  const log = `COMMAND (JSON argv): ${JSON.stringify(options.command)}\n--- stdout ---\n${child.stdout ?? ''}\n--- stderr ---\n${child.stderr ?? ''}\n`;
  const logPath = `${options.label}.log`; save(resolve(dir, logPath), log);
  const record = { formatVersion: 1, unit: 'S1.27', label: options.label, command: JSON.stringify(options.command), startEpochMs, endEpochMs, elapsedMs,
    exitCode: child.status ?? -1, signal: child.signal ?? null, sourceFingerprintSha256: before.sha256,
    sourceUnchanged: before.sha256 === after.sha256, commit: before.commit, evidence: { path: logPath, sha256: hash(Buffer.from(log)) } };
  save(resolve(dir, `${options.label}.json`), record);
  console.log(JSON.stringify({ label: options.label, elapsedMs, exitCode: record.exitCode, sourceUnchanged: record.sourceUnchanged, evidence: resolve(dir, `${options.label}.json`) }));
  if (record.exitCode !== 0 || !record.sourceUnchanged) process.exitCode = 1;
}
function collect(options) {
  for (const key of ['build', 'test', 'graph', 'complexity', 'detekt']) if (!options[key]) throw new Error(usage);
  const dir = outDir(options.out), current = sourceFingerprint(), commands = {};
  for (const name of ['build', 'test']) {
    const record = readJson(options[name]);
    if (record.label !== name || !record.sourceUnchanged || record.sourceFingerprintSha256 !== current.sha256) throw new Error(`${name}: source fingerprint changed`);
    const source = resolve(dirname(resolve(options[name])), record.evidence.path);
    const bytes = readFileSync(source);
    if (hash(bytes) !== record.evidence.sha256) throw new Error(`${name}: log changed`);
    const path = `cost-${name}.log`; copyFileSync(source, resolve(dir, path), 1);
    commands[name] = { ...record, evidence: { path, sha256: hash(bytes) } };
  }
  const reports = {};
  for (const [key, name] of [['graph', 'architecture-graph'], ['complexity', 'complexity-psi'], ['detekt', 'detekt']]) {
    const file = resolve(options[key]), stat = statSync(file), bytes = readFileSync(file);
    const contemporaneous = Object.values(commands).some(c => stat.mtimeMs >= c.startEpochMs - 1000 && stat.mtimeMs <= c.endEpochMs + 1000);
    const path = `cost-${name}${key === 'detekt' ? '.xml' : '.json'}`;
    copyFileSync(file, resolve(dir, path), 1);
    reports[name] = { generatedAt: new Date(stat.mtimeMs).toISOString(), sourceFingerprintSha256: contemporaneous ? current.sha256 : null,
      contemporaneous, evidence: { path, sha256: hash(bytes) } };
  }
  const cost = { formatVersion: 1, unit: 'S1.27', sourceFingerprintSha256: current.sha256, commands, reports };
  save(resolve(dir, 'cost.json'), cost);
  console.log(JSON.stringify({ output: resolve(dir, 'cost.json'), contemporaneousReports: Object.fromEntries(Object.entries(reports).map(([name, report]) => [name, report.contemporaneous])) }));
}
try {
  const { mode, options } = parse(process.argv.slice(2));
  if (mode === 'run') run(options);
  else if (mode === 'collect') collect(options);
  else throw new Error(usage);
} catch (error) { console.error(`s1.27 cost: ${error.message}`); process.exitCode = 2; }
