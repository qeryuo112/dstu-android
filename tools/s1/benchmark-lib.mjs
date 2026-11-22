import { createHash } from 'node:crypto';
import { readFileSync, realpathSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { corpusSha256 } from './benchmark-corpus.mjs';
import { readMacroStartup, startupPayload } from './benchmark-startup.mjs';

const shaPattern = /^[a-f0-9]{64}$/u;
const commitPattern = /^[a-f0-9]{40,64}$/u;
export const hash = bytes => createHash('sha256').update(bytes).digest('hex');
export const readJson = path => JSON.parse(readFileSync(path, 'utf8'));
export const finite = value => typeof value === 'number' && Number.isFinite(value);
const present = value => typeof value === 'string' && value.trim().length > 0;
const inRange = (number, range) => finite(number) && number >= range[0] && number <= range[1];

export function nearestRank(values, percentile) {
  if (!Array.isArray(values) || values.length === 0 || !values.every(finite) || !finite(percentile) || percentile <= 0 || percentile > 1) {
    throw new Error('nearestRank requires finite values and 0 < percentile <= 1');
  }
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.ceil(percentile * sorted.length) - 1];
}

function validateSharedContract(contract) {
  if (contract?.formatVersion !== 1 || contract.unit !== 'S1.27' || contract.policy?.percentile !== 'nearest-rank') throw new Error('Invalid S1.27 contract');
  if (contract.policy.warmup !== 3 || contract.policy.iterations !== 30) throw new Error('S0.17 requires 3 warmups and 30 iterations');
  if (contract.corpus?.sha256 !== corpusSha256()) throw new Error('Corpus generator and contract SHA-256 differ');
  if (!Array.isArray(contract.metrics) || new Set(contract.metrics.map(m => m.id)).size !== contract.metrics.length) throw new Error('Invalid metrics');
}

export function validateContract(contract) {
  validateSharedContract(contract);
  if (!Array.isArray(contract.devices) || contract.devices.length !== 3 || new Set(contract.devices.map(d => d.id)).size !== 3) throw new Error('Exactly three distinct device profiles required');
  for (const metric of contract.metrics) for (const device of contract.devices) {
    const b = metric.budgets?.[device.id];
    if (metric.direction !== 'lower' || !finite(b?.p50) || !finite(b.p95) || b.p50 <= 0 || b.p95 < b.p50) throw new Error(`Invalid absolute budget: ${metric.id}/${device.id}`);
  }
  return contract;
}

// Observation needs the frozen metric definitions, not the three device profiles or their budgets.
export function validateObservationContract(contract) {
  validateSharedContract(contract);
  const required = new Map([
    ['cold-start-ttid-ms', 'ms'], ['cold-start-ttfd-ms', 'ms'],
    ['notes-scroll-frame-p95-ms', 'ms'], ['notes-scroll-peak-pss-mib', 'MiB'],
    ['sync-apply-ms', 'ms'], ['terminal-roundtrip-ms', 'ms'],
  ]);
  if (contract.metrics.length !== required.size || contract.metrics.some(m => required.get(m.id) !== m.unit)) throw new Error('Incomplete observation metric definitions');
  for (const id of ['cold-start-ttid-ms', 'cold-start-ttfd-ms']) {
    if (contract.metrics.find(m => m.id === id).method !== 'androidx.benchmark.macro.StartupTimingMetric/COLD') throw new Error('Invalid startup metric method');
  }
  return contract;
}

function add(issues, status, message) { issues.push({ status, message }); }
function statusOf(issues) { return issues.some(i => i.status === 'fail') ? 'fail' : issues.length ? 'missing' : 'pass'; }

// Resolve evidence strictly inside the supplied run directory (including symlinks).
export function evidenceIssue(ref, baseDir, label) {
  if (!ref || !present(ref.path) || !present(ref.sha256)) return { status: 'missing', message: `${label}: evidence reference missing` };
  if (!shaPattern.test(ref.sha256) || isAbsolute(ref.path) || ref.path.split(/[\\/]/u).includes('..')) return { status: 'fail', message: `${label}: invalid evidence reference` };
  try {
    const base = realpathSync(baseDir);
    const file = realpathSync(resolve(base, ref.path));
    const rel = relative(base, file);
    if (!rel || rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) return { status: 'fail', message: `${label}: evidence escapes run directory` };
    if (hash(readFileSync(file)) !== ref.sha256) return { status: 'fail', message: `${label}: evidence SHA-256 mismatch` };
    return null;
  } catch (error) {
    if (error.code === 'ENOENT') return { status: 'missing', message: `${label}: evidence file missing` };
    return { status: 'fail', message: `${label}: cannot read evidence (${error.code ?? 'invalid'})` };
  }
}
function checkEvidence(issues, ref, base, label) { const result = evidenceIssue(ref, base, label); if (result) issues.push(result); }

function assessDevice(run, profile, base, { observation = false } = {}) {
  const issues = [];
  if (run.formatVersion !== 1 || run.unit !== 'S1.27') add(issues, 'fail', 'Unrecognized run format or unit');
  const acceptedKind = run.kind === 'physical-device' || (observation && run.kind === 'physical-device-observation');
  if (!acceptedKind || run.collector !== 's1-27-device-v1') add(issues, 'missing', 'Not a physical-device collector record; synthetic/mock data is not evidence');
  const d = run.device ?? {};
  if (!observation && d.profileId !== profile.id) add(issues, 'fail', 'Device profile ID mismatch');
  if (observation && run.kind === 'physical-device-observation' && (d.profileId !== null || d.ramMiB !== null)) add(issues, 'fail', 'Unclassified observation must not claim a profile or marketed RAM');
  if (!shaPattern.test(d.serialSha256 ?? '') || !shaPattern.test(d.androidIdSha256 ?? '')) add(issues, 'missing', 'Two independent redacted device identifiers required');
  if (!present(d.model) || !present(d.buildFingerprint)) add(issues, 'missing', 'Device model/build fingerprint missing');
  const emulatorText = [d.model, d.buildFingerprint, d.hardware, d.product].filter(present).join(' ');
  if (d.isEmulator === true || d.qemu === '1' || /(?:generic|emulator|sdk_gphone|goldfish|ranchu|vbox|virtualbox)/iu.test(emulatorText)) add(issues, 'fail', 'Emulator detected');
  else if (d.isEmulator !== false || d.qemu !== '0') add(issues, 'missing', 'Physical-device classification is not substantiated');
  if (d.physicalAttested !== true) add(issues, 'missing', 'Operator physical-device attestation missing');
  checkEvidence(issues, d.identityEvidence, base, 'device identity');
  checkEvidence(issues, d.attestationEvidence, base, 'physical attestation');
  if (!evidenceIssue(d.identityEvidence, base, 'device identity')) {
    try {
      const identity = readJson(resolve(base, d.identityEvidence.path));
      for (const field of ['profileId', 'model', 'buildFingerprint', 'serialSha256', 'androidIdSha256']) {
        if (identity[field] !== d[field]) add(issues, 'fail', `Device identity evidence differs: ${field}`);
      }
    } catch { add(issues, 'fail', 'Device identity evidence is not valid JSON'); }
  }
  if (!evidenceIssue(d.attestationEvidence, base, 'physical attestation')) {
    try {
      const attestation = readJson(resolve(base, d.attestationEvidence.path));
      if (attestation.physicalAttested !== true || attestation.profileId !== d.profileId || attestation.serialSha256 !== d.serialSha256 || !present(attestation.operatorAlias)) add(issues, 'fail', 'Physical attestation does not match the device');
    } catch { add(issues, 'fail', 'Physical attestation is not valid JSON'); }
  }
  if (observation) {
    if (!Number.isInteger(d.api) || d.api <= 0 || !finite(d.memTotalMiB) || d.memTotalMiB <= 0 ||
        !finite(d.smallestWidthDp) || d.smallestWidthDp <= 0 || !Number.isInteger(d.pageSize) || d.pageSize <= 0) add(issues, 'missing', 'Observed device properties incomplete');
    if (!present(d.abi)) add(issues, 'missing', 'Device ABI missing');
    else if (d.abi !== 'arm64-v8a') add(issues, 'fail', 'Observed device is not arm64');
  } else {
    if (!inRange(d.api, profile.api) || d.abi !== profile.abi || !inRange(d.ramMiB, profile.ramMiB) ||
        !inRange(d.smallestWidthDp, profile.smallestWidthDp) || d.pageSize !== profile.pageSize) add(issues, 'fail', 'Device properties fall outside profile');
    if (!finite(d.memTotalMiB) || d.memTotalMiB > d.ramMiB || d.memTotalMiB < d.ramMiB * 0.65) add(issues, 'fail', 'Reported physical RAM and observed MemTotal are inconsistent');
    if (!profile.lock) add(issues, 'missing', 'Physical device identity is not yet frozen in the contract');
    else if (profile.lock.serialSha256 !== d.serialSha256 || profile.lock.androidIdSha256 !== d.androidIdSha256 || profile.lock.buildFingerprint !== d.buildFingerprint) add(issues, 'fail', 'Device does not match frozen identity');
  }
  return issues;
}

function assessProvenance(run, contract, base) {
  const issues = [];
  const b = run.build ?? {}, e = run.environment ?? {};
  if (b.appId !== 'com.deepstudent.app.benchmark' || b.testId !== 'com.deepstudent.app.benchmark.test') add(issues, 'fail', 'Evidence is not from the isolated benchmark app and test package');
  if (b.debuggable !== false || b.profileableShell !== true) add(issues, 'fail', 'Benchmark APK must be non-debuggable and shell-profileable');
  if (!commitPattern.test(b.commit ?? '') || !shaPattern.test(b.sourceFingerprintSha256 ?? '') ||
      !shaPattern.test(b.apkSha256 ?? '') || !shaPattern.test(b.installedApkSha256 ?? '')) add(issues, 'missing', 'Commit, source fingerprint or APK identity missing');
  else if (b.apkSha256 !== b.installedApkSha256) add(issues, 'fail', 'Installed APK hash differs from measured APK');
  if (b.sourceUnchanged !== true) add(issues, 'missing', 'Source tree stability during capture not established');
  if (!present(b.variant) || !Number.isInteger(b.versionCode) || b.versionCode < 1 || !present(b.schemaFingerprint) || !present(b.wireVersion)) add(issues, 'missing', 'Build variant, versionCode, schema fingerprint or wire version missing');
  checkEvidence(issues, b.apkEvidence, base, 'installed APK');
  if (!evidenceIssue(b.apkEvidence, base, 'installed APK')) {
    try {
      const apk = readJson(resolve(base, b.apkEvidence.path));
      if (apk.appId !== b.appId || apk.testId !== b.testId || apk.apkSha256 !== b.apkSha256 || apk.installedApkSha256 !== b.installedApkSha256 || apk.versionCode !== b.versionCode || apk.debuggable !== false || apk.profileableShell !== true || !shaPattern.test(apk.testApkSha256 ?? '')) add(issues, 'fail', 'Installed APK evidence does not match the run');
    } catch { add(issues, 'fail', 'Installed APK evidence is not valid JSON'); }
  }
  if (run.corpus?.id !== contract.corpus.id || run.corpus?.sha256 !== contract.corpus.sha256 || run.corpus?.verified !== true) add(issues, 'missing', 'Fixed corpus is not verified');
  checkEvidence(issues, run.corpus?.evidence, base, 'corpus');
  if (!evidenceIssue(run.corpus?.evidence, base, 'corpus')) {
    try {
      const corpus = readJson(resolve(base, run.corpus.evidence.path));
      if (corpus.type !== 'corpus' || corpus.id !== contract.corpus.id || corpus.sha256 !== contract.corpus.sha256 || corpus.count !== contract.corpus.noteCount) add(issues, 'fail', 'Corpus readback evidence does not match the contract');
    } catch { add(issues, 'fail', 'Corpus readback evidence is not valid JSON'); }
  }
  if (!present(e.thermalState) || !present(e.batteryState) || !present(e.startedAt) || !present(e.finishedAt)) add(issues, 'missing', 'Environment or wall-clock attribution missing');
  else if (!Number.isFinite(Date.parse(e.startedAt)) || !Number.isFinite(Date.parse(e.finishedAt)) || Date.parse(e.finishedAt) < Date.parse(e.startedAt)) add(issues, 'fail', 'Invalid collection timestamps');
  return issues;
}

function assessSamples(metric, definition, profile, contract, base, run, { fullSeries = true } = {}) {
  const issues = [];
  if (!metric) return { status: 'missing', issues: [{ status: 'missing', message: 'Metric not collected' }] };
  if (metric.unit !== definition.unit) add(issues, 'fail', 'Metric unit differs from contract');
  let official;
  if (definition.method) {
    if (!metric.method) add(issues, 'missing', 'Official Macrobenchmark COLD method evidence missing');
    else if (metric.method !== definition.method) add(issues, 'fail', 'Non-Macrobenchmark data cannot populate official startup metric');
    const issue = evidenceIssue(metric.sourceEvidence, base, 'official Macrobenchmark result');
    if (issue) issues.push(issue);
    else try {
      official = readMacroStartup(readJson(resolve(base, metric.sourceEvidence.path)), startupPayload(run), contract.policy);
      if (!official.metrics.has(definition.id)) add(issues, 'missing', 'Official result lacks a complete display timing series');
    } catch { add(issues, 'fail', 'Invalid or mismatched official Macrobenchmark result'); }
  }
  const captureIssue = evidenceIssue(metric.evidence, base, 'raw capture');
  if (captureIssue) issues.push(captureIssue);
  else {
    try {
      const capture = readJson(resolve(base, metric.evidence.path));
      if (capture.metric !== definition.id || capture.unit !== metric.unit || (definition.method && capture.method !== metric.method) || JSON.stringify(capture.samples) !== JSON.stringify(metric.samples)) add(issues, 'fail', 'Raw capture differs from reported samples');
    } catch { add(issues, 'fail', 'Raw capture is not valid JSON'); }
  }
  const rawSamples = Array.isArray(metric.samples) ? metric.samples : [];
  if (!Array.isArray(metric.samples)) add(issues, 'missing', 'Samples missing');
  const samples = rawSamples.filter(s => s && typeof s === 'object' && !Array.isArray(s));
  if (samples.length !== rawSamples.length) add(issues, 'fail', 'Invalid sample record');
  const labelOf = sample => (['warmup', 'measurement'].includes(sample.phase) && Number.isInteger(sample.index) && sample.index >= 1 && sample.index <= contract.policy.iterations)
    ? `${sample.phase} #${sample.index}` : 'invalid sample';
  // AndroidX records the count of PGO warmups, not their timings/traces. Do not invent three values.
  const expected = definition.method ? [['measurement', contract.policy.iterations]]
    : [['warmup', contract.policy.warmup], ['measurement', contract.policy.iterations]];
  for (const [phase, count] of expected) {
    const found = samples.filter(s => s.phase === phase);
    if (fullSeries && found.length < count) add(issues, 'missing', `${phase}: expected ${count}, got ${found.length}`);
    if (found.length > count || new Set(found.map(s => s.index)).size !== found.length || found.some(s => !Number.isInteger(s.index) || s.index < 1 || s.index > count)) add(issues, 'fail', `${phase}: duplicate or out-of-range indices`);
  }
  if (samples.some(s => !expected.some(([phase]) => s.phase === phase))) add(issues, 'fail', 'Unknown sample phase');
  const frames = [];
  for (const sample of samples) {
    const sampleLabel = labelOf(sample);
    if (sample.status === 'failed') {
      if (fullSeries) add(issues, 'fail', `${sampleLabel}: scenario failed`);
      continue;
    }
    if (sample.status !== 'ok' || !finite(sample.value) || sample.value < 0) { add(issues, 'fail', `${sampleLabel}: invalid sample`); continue; }
    if (definition.method) {
      checkEvidence(issues, sample.traceEvidence, base, `startup trace ${sampleLabel}`);
      const value = official?.metrics.get(definition.id)?.[sample.index - 1];
      if (value != null && sample.value !== value) add(issues, 'fail', 'Display timing differs from official raw run');
      if (official && sample.traceEvidence && sample.traceEvidence.path !== official.traceFiles[sample.index - 1]) add(issues, 'fail', 'Startup trace differs from official trace index');
      if (definition.id === 'cold-start-ttfd-ms' && sample.fullDisplayObserved !== true) add(issues, 'missing', `Full-display marker missing at ${sampleLabel}`);
    }
    if (definition.id === 'notes-scroll-frame-p95-ms') {
      const values = sample.frameDurationsMs;
      if (!Array.isArray(values) || values.length < definition.minimumFramesPerIteration || !values.every(v => finite(v) && v > 0) || sample.droppedReports !== 0) add(issues, 'fail', `Frame trace incomplete at ${sampleLabel}`);
      else {
        if (Math.abs(nearestRank(values, 0.95) - sample.value) > 0.00001) add(issues, 'fail', `Frame P95 disagrees with raw frames at ${sampleLabel}`);
        if (sample.phase === 'measurement') frames.push(...values);
      }
    }
    if (definition.id === 'notes-scroll-peak-pss-mib') {
      const points = sample.pssSamples;
      if (!Array.isArray(points) || points.length < 2 || points.some(p => !p || typeof p !== 'object' || !finite(p.elapsedMs) || !finite(p.pssMiB) || p.elapsedMs < 0 || p.pssMiB <= 0) ||
          points.some((p, i) => i > 0 && (p.elapsedMs <= points[i - 1].elapsedMs || p.elapsedMs - points[i - 1].elapsedMs > 250))) add(issues, 'fail', `PSS sampling insufficient at ${sampleLabel}`);
      else if (Math.abs(Math.max(...points.map(p => p.pssMiB)) - sample.value) > 0.00001) add(issues, 'fail', `PSS peak disagrees with raw samples at ${sampleLabel}`);
    }
    if (definition.id === 'terminal-roundtrip-ms' && (sample.outputBytes !== contract.terminalCorpus.expectedOutputBytes || sample.markerObserved !== true)) add(issues, 'fail', `PTY corpus/marker not verified at ${sampleLabel}`);
    if (definition.id === 'sync-apply-ms') {
      const proof = sample.verification ?? {};
      if (!contract.syncCorpus.sha256) add(issues, 'missing', 'Desktop sync corpus hash not yet frozen');
      else if (proof.corpusSha256 !== contract.syncCorpus.sha256 || proof.fixtureId !== contract.syncCorpus.id || proof.androidVerified !== true || proof.desktopVerified !== true || proof.desktopMode !== 'production') add(issues, 'fail', `Two-way fixture verification failed at ${sampleLabel}`);
      const label = `sync verification ${sampleLabel}`;
      const proofIssue = evidenceIssue(proof.evidence, base, label);
      if (proofIssue) issues.push(proofIssue);
      else {
        try {
          const recorded = readJson(resolve(base, proof.evidence.path));
          for (const key of ['fixtureId', 'corpusSha256', 'androidVerified', 'desktopVerified', 'desktopMode', 'phase', 'index']) if (recorded[key] !== proof[key]) add(issues, 'fail', `${label}: proof differs for ${key}`);
        } catch { add(issues, 'fail', `${label}: invalid JSON`); }
      }
    }
  }
  const measured = samples.filter(s => s.phase === 'measurement' && s.status === 'ok' && finite(s.value) && s.value >= 0);
  if (!fullSeries && measured.length === 0) add(issues, 'missing', 'No successful measured trial for this functional scenario');
  let summary;
  if (measured.length) {
    const values = measured.map(s => s.value);
    summary = { count: values.length, p50: nearestRank(values, 0.50), p95: nearestRank(values, 0.95), maximum: Math.max(...values), unit: definition.unit };
    if (frames.length) summary.pooledFrames = { count: frames.length, p50: nearestRank(frames, 0.50), p95: nearestRank(frames, 0.95) };
    if (profile && measured.length === contract.policy.iterations) {
      const b = definition.budgets[profile.id];
      if (summary.p50 > b.p50 || summary.p95 > b.p95) add(issues, 'fail', `Absolute budget exceeded: P50 ${summary.p50}/${b.p50}, P95 ${summary.p95}/${b.p95} ${definition.unit}`);
    }
  }
  return { status: statusOf(issues), issues, ...(profile ? { budget: definition.budgets[profile.id] } : {}), summary };
}

function assessCosts(cost, contract, baselineSource) {
  const issues = [];
  if (!cost) return { status: 'missing', issues: [{ status: 'missing', message: 'Build/test cost and module/complexity evidence not supplied' }] };
  const { record, baseDir } = cost;
  if (record.formatVersion !== 1 || record.unit !== 'S1.27') add(issues, 'fail', 'Invalid cost record');
  if (!shaPattern.test(record.sourceFingerprintSha256 ?? '') || !baselineSource) add(issues, 'missing', 'Source fingerprint for cost comparison missing');
  else if (record.sourceFingerprintSha256 !== baselineSource) add(issues, 'fail', 'Cost evidence does not describe the measured source tree');
  for (const name of contract.costEvidence.requiredReports) {
    const report = record.reports?.[name];
    if (!report?.generatedAt || !Number.isFinite(Date.parse(report.generatedAt))) add(issues, 'missing', `${name}: generation time missing`);
    checkEvidence(issues, report?.evidence, baseDir, name);
    if (report && report.sourceFingerprintSha256 !== record.sourceFingerprintSha256) add(issues, 'missing', `${name}: report source provenance not established`);
  }
  for (const name of contract.costEvidence.requiredTimedCommands) {
    const command = record.commands?.[name];
    if (!command) { add(issues, 'missing', `${name}: timed command missing`); continue; }
    if (command.exitCode !== 0) add(issues, 'fail', `${name}: command failed`);
    if (!present(command.command) || !finite(command.elapsedMs) || command.elapsedMs < 0 || command.sourceFingerprintSha256 !== record.sourceFingerprintSha256) add(issues, 'missing', `${name}: timing or source provenance missing`);
    checkEvidence(issues, command.evidence, baseDir, `${name} log`);
  }
  return { status: statusOf(issues), issues };
}

export function evaluate(contract, inputs, cost = null) {
  validateContract(contract);
  const globalIssues = [];
  if (contract.status !== 'frozen') add(globalIssues, 'missing', 'Absolute thresholds are provisional, not calibrated and frozen');
  const byProfile = new Map();
  for (const input of inputs) {
    // Never let an unclassified observation enter the historical acceptance matrix,
    // even if someone adds a profileId or other profile-shaped fields to it.
    if (input.record?.kind === 'physical-device-observation') {
      add(globalIssues, 'fail', 'Observation record excluded from the historical device matrix');
      continue;
    }
    const id = input.record?.device?.profileId;
    if (!contract.devices.some(p => p.id === id)) { add(globalIssues, 'fail', `Unknown profile: ${String(id)}`); continue; }
    if (byProfile.has(id)) add(globalIssues, 'fail', `Multiple records for profile: ${id}`);
    else byProfile.set(id, input);
  }
  const identities = { serialSha256: new Map(), androidIdSha256: new Map() };
  const results = {};
  let referenceBuild = null;
  for (const profile of contract.devices) {
    const input = byProfile.get(profile.id);
    if (!input) { results[profile.id] = { status: 'missing', issues: [{ status: 'missing', message: 'No physical-device run' }], metrics: {} }; continue; }
    const { record: run, baseDir } = input;
    const issues = [...assessDevice(run, profile, baseDir), ...assessProvenance(run, contract, baseDir)];
    for (const field of Object.keys(identities)) {
      const value = run.device?.[field];
      if (!shaPattern.test(value ?? '')) continue;
      if (identities[field].has(value)) add(globalIssues, 'fail', `Same physical identifier (${field}) assigned to ${identities[field].get(value)} and ${profile.id}`);
      else identities[field].set(value, profile.id);
    }
    const b = run.build ?? {};
    if (!referenceBuild) referenceBuild = b;
    else for (const field of ['sourceFingerprintSha256', 'apkSha256', 'variant', 'versionCode', 'schemaFingerprint', 'wireVersion']) {
      if (b[field] !== referenceBuild[field]) add(globalIssues, 'fail', `Build identity differs across device profiles: ${field}`);
    }
    const metrics = Object.fromEntries(contract.metrics.map(def => [def.id, assessSamples(run.metrics?.[def.id], def, profile, contract, baseDir, run)]));
    const status = statusOf([...issues, ...Object.values(metrics).filter(m => m.status !== 'pass').map(m => ({ status: m.status }))]);
    results[profile.id] = { status, issues, metrics };
  }
  const costs = assessCosts(cost, contract, referenceBuild?.sourceFingerprintSha256);
  const combined = [...globalIssues, ...Object.values(results).filter(r => r.status !== 'pass').map(r => ({ status: r.status })), ...(costs.status === 'pass' ? [] : [{ status: costs.status }])];
  return { formatVersion: 1, unit: 'S1.27', status: statusOf(combined), contractStatus: contract.status, globalIssues, devices: results, costs };
}

function assessLaunchSmoke(run, base) {
  const smoke = run.smoke ?? {};
  const issues = [];
  const evidence = evidenceIssue(smoke.evidence, base, 'activity launch smoke');
  if (evidence) issues.push(evidence);
  else {
    try {
      const observed = readJson(resolve(base, smoke.evidence.path));
      if (smoke.kind !== 'activity-launch-smoke' || smoke.acceptanceEvidence !== false ||
          observed.kind !== smoke.kind || observed.acceptanceEvidence !== false || observed.status !== smoke.status ||
          observed.totalTimeMs !== smoke.totalTimeMs) add(issues, 'fail', 'Activity launch smoke differs from raw evidence');
      else if (smoke.status !== 'ok' || !finite(smoke.totalTimeMs) || smoke.totalTimeMs <= 0) {
        add(issues, 'fail', 'Activity launch smoke did not succeed');
      }
    } catch { add(issues, 'fail', 'Activity launch evidence is not valid JSON'); }
  }
  const status = statusOf(issues);
  return { status: status === 'pass' ? 'complete' : status, issues, ...(status === 'pass' ? { totalTimeMs: smoke.totalTimeMs } : {}) };
}

// This is an evidence-completeness report, never a receipt for formal acceptance.
// All output is constructed from fixed labels, statuses and numeric statistics; no
// input record, identity hash, evidence path, or other device string is copied out.
export function observe(contract, inputs, { requireTwo = false, functional = false } = {}) {
  validateObservationContract(contract);
  const issues = [];
  const expected = requireTwo ? 2 : 1;
  if (inputs.length < expected) add(issues, 'missing', `Expected ${expected} physical-device run(s)`);
  if (inputs.length > expected) add(issues, 'fail', `Expected exactly ${expected} physical-device run(s)`);
  const devices = inputs.map(({ record: run, baseDir }, index) => {
    const deviceIssues = [...assessDevice(run, null, baseDir, { observation: true }), ...assessProvenance(run, contract, baseDir)];
    const metrics = Object.fromEntries(contract.metrics.map(def => {
      const result = assessSamples(run.metrics?.[def.id], def, null, contract, baseDir, run);
      return [def.id, { ...result, status: result.status === 'pass' ? 'complete' : result.status }];
    }));
    let functions;
    if (functional) {
      // Repeated performance collection is not needed to establish that a real route works.
      // Keep full-series status in metrics: missing startup timings and failed attempts remain visible.
      functions = { startup: assessLaunchSmoke(run, baseDir) };
      for (const def of contract.metrics.filter(m => !m.method)) {
        const { status, issues, summary } = assessSamples(run.metrics?.[def.id], def, null, contract, baseDir, run, { fullSeries: false });
        functions[def.id] = { status: status === 'pass' ? 'complete' : status, issues, trialsVerified: summary?.count ?? 0 };
      }
    }
    const combined = [...deviceIssues, ...Object.values(functions ?? metrics).filter(m => m.status !== 'complete').map(m => ({ status: m.status }))];
    const status = statusOf(combined);
    return { ordinal: index + 1, status: status === 'pass' ? 'complete' : status, issues: deviceIssues, metrics, ...(functions ? { functions } : {}) };
  });
  let sameApk = null, sameSource = null, distinctDevices = null;
  if (requireTwo && inputs.length === 2) {
    const [a, b] = inputs.map(input => input.record);
    const ad = a.device ?? {}, bd = b.device ?? {}, ab = a.build ?? {}, bb = b.build ?? {};
    const fields = ['serialSha256', 'androidIdSha256'];
    if (fields.every(field => shaPattern.test(ad[field] ?? '') && shaPattern.test(bd[field] ?? ''))) {
      distinctDevices = fields.every(field => ad[field] !== bd[field]);
      if (!distinctDevices) add(issues, 'fail', 'Device identifiers do not establish two distinct physical devices');
    } else add(issues, 'missing', 'Two independent redacted device identifiers required for each run');
    for (const field of ['appId', 'testId', 'variant', 'versionCode', 'schemaFingerprint', 'wireVersion']) {
      if (ab[field] != null && bb[field] != null && ab[field] !== bb[field]) add(issues, 'fail', `Application build identity differs: ${field}`);
    }
    if (shaPattern.test(ab.apkSha256 ?? '') && shaPattern.test(bb.apkSha256 ?? '')) sameApk = ab.apkSha256 === bb.apkSha256;
    if (shaPattern.test(ab.sourceFingerprintSha256 ?? '') && shaPattern.test(bb.sourceFingerprintSha256 ?? '')) sameSource = ab.sourceFingerprintSha256 === bb.sourceFingerprintSha256;
  }
  const status = statusOf([...issues, ...devices.filter(d => d.status !== 'complete').map(d => ({ status: d.status }))]);
  return { formatVersion: 1, unit: 'S1.27', kind: 'physical-device-observation-report', acceptanceEvidence: false,
    status: status === 'pass' ? 'complete' : status, requireTwo, sameApk, sameSource, distinctDevices, scope: functional ? 'functional' : 'full-series',
    // A mismatch is allowed, but the report cannot establish that changes were host-tool-only.
    hostOnlyChangesVerified: false, issues, devices };
}

export function loadRecord(path) { return { record: readJson(path), baseDir: dirname(resolve(path)) }; }
