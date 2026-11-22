import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { desktop, difference, git, log, readJson, root, sha256 } from './lib.mjs';

export const GATE_CONFIG = 'config/s0-gate.json';
export const FINAL_LOCK = 'config/schema/final-lock.json';
export const GATE_FORMAT = 'dstu-s0-g-stage-gate/v1';
export const LOCK_FORMAT = 'dstu-s0-g-final-lock/v1';
export const UNIT = 'S0.G';

const SQL_FILE_RE = /^V(\d{8})__([A-Za-z0-9_]+)\.sql$/u;
const STALE_VERSIONS = new Set([20261114]);
const QUOTE = value => `"${String(value).replaceAll('"', '""')}"`;

const loadDesktop = file => import(pathToFileURL(resolve(desktop, file)).href);
const byteCompare = (left, right) => left < right ? -1 : left > right ? 1 : 0;
const byFile = (left, right) => byteCompare(left.file, right.file);

function readText(file) {
  return readFileSync(resolve(root, file), 'utf8');
}

function fileInfo(file) {
  const bytes = readFileSync(resolve(root, file));
  return { file, bytes: bytes.length, sha256: sha256(bytes) };
}

function saveJson(file, value) {
  const full = resolve(root, file);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, `${JSON.stringify(value, null, 2)}\n`);
}

function walkRelative(start) {
  const files = [];
  const walk = directory => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const full = resolve(directory, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.isFile()) {
        files.push(relative(root, full).replaceAll('\\', '/'));
      }
    }
  };
  walk(resolve(root, start));
  return files.sort(byteCompare);
}

function globPattern(pattern) {
  let source = '^';
  for (let index = 0; index < pattern.length; index++) {
    const character = pattern[index];
    if (character === '*') {
      if (pattern[index + 1] === '*') {
        source += '.*';
        index++;
        if (pattern[index + 1] === '/') index++;
      } else {
        source += '[^/]*';
      }
    } else if ('\\^$+.()|{}[]'.includes(character)) {
      source += `\\${character}`;
    } else {
      source += character;
    }
  }
  return new RegExp(`${source}$`, 'u');
}

function expandPattern(pattern) {
  if (!/[*?]/u.test(pattern)) return existsSync(resolve(root, pattern)) ? [pattern] : [];
  const expression = globPattern(pattern);
  const candidates = [];
  const walk = (directory, prefix) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const relativePath = prefix === '' ? entry.name : `${prefix}/${entry.name}`;
      const full = resolve(directory, entry.name);
      if (entry.isDirectory()) walk(full, relativePath);
      else if (entry.isFile() && expression.test(relativePath)) candidates.push(relativePath);
    }
  };
  walk(root, '');
  return candidates.sort(byteCompare);
}

function inspectSqlDirectory(directory) {
  const files = [];
  const walk = (current, prefix) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const relativePath = prefix === '' ? entry.name : `${prefix}/${entry.name}`;
      const full = resolve(current, entry.name);
      if (entry.isDirectory()) walk(full, relativePath);
      else if (entry.isFile() && entry.name.endsWith('.sql')) {
        const bytes = readFileSync(full);
        files.push({ relative: relativePath, bytes: bytes.length, sha256: sha256(bytes) });
      }
    }
  };
  walk(directory, '');
  files.sort((left, right) => byteCompare(left.relative, right.relative));
  return {
    count: files.length,
    files,
    setSha256: sha256(files.map(file => `${file.relative}\0${file.sha256}\n`).join('')),
  };
}

function copySqlTree(source, destination) {
  for (const file of walkRelative(source)) {
    if (!file.endsWith('.sql')) continue;
    const target = resolve(destination, relative(resolve(root, source), resolve(root, file)));
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(resolve(root, file), target);
  }
}

function parseSqlFile(file) {
  const match = SQL_FILE_RE.exec(basename(file));
  assert.ok(match, `invalid migration filename: ${file}`);
  return { version: Number(match[1]), name: match[2] };
}

function reportMap(report) {
  return new Map(report.databases.map(database => [database.id, database]));
}

function databaseSummary(database) {
  return {
    id: database.id,
    fromVersion: database.fromVersion,
    toVersion: database.toVersion,
    appliedCount: database.appliedCount,
    success: database.success,
  };
}

function historyRows(connection) {
  return connection.all('SELECT version,name,checksum FROM refinery_schema_history ORDER BY version');
}

function schemaScope(scope) {
  return {
    tables: [...scope.tables],
    indexes: [...scope.indexes],
    triggers: [...scope.triggers],
  };
}

function summarizeScope(scope) {
  const value = schemaScope(scope);
  return {
    tables: value.tables.length,
    indexes: value.indexes.length,
    triggers: value.triggers.length,
    names: value,
    scopeSha256: sha256(JSON.stringify(value)),
  };
}

function governanceRow(connection, database, version) {
  const table = connection.pluck(
    "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='__governance_schema_fingerprints')",
  );
  if (Number(table) !== 1) return { present: false };
  const row = connection.get(
    'SELECT fingerprint, canonical_schema FROM __governance_schema_fingerprints WHERE database_id=? AND schema_version=?',
    [database, version],
  );
  return row === undefined ? { present: false } : {
    present: true,
    fingerprint: row.fingerprint,
    canonicalSchemaSha256: sha256(String(row.canonical_schema ?? '')),
  };
}

function checkDatabaseIntegrity(connection) {
  return {
    integrity: String(connection.pluck('PRAGMA integrity_check')),
    foreignKeyViolations: connection.all('PRAGMA foreign_key_check').length,
  };
}

function loadPolicy() {
  const policy = readJson(GATE_CONFIG);
  assert.equal(policy.formatVersion, 1);
  assert.equal(policy.format, 'dstu-s0-gate-policy/v1');
  assert.equal(policy.unit, UNIT);
  assert.equal(policy.units.length, 25, 'S0 gate maps exactly 25 units');
  const expectedUnits = Array.from({ length: 25 }, (_, index) => `S0.${String(index + 1).padStart(2, '0')}`);
  assert.deepEqual(policy.units.map(unit => unit.unit), expectedUnits);
  assert.equal(new Set(policy.units.map(unit => unit.stage)).size, 25);
  assert.equal(policy.expected.coordinated.length, 3);
  assert.equal(policy.expected.coordinatedMigrations, 3);
  assert.equal(policy.expected.finalMigrations, 138);
  assert.equal(
    Object.values(policy.expected.databaseMigrationCounts).reduce((total, count) => total + count, 0),
    policy.expected.finalMigrations,
  );
  assert.equal(policy.m0.gateToolSignsOff, false);
  for (const unit of policy.units) {
    assert.ok(Array.isArray(policy.unitEvidence?.[unit.unit]) && policy.unitEvidence[unit.unit].length > 0,
      `${unit.unit}: fixed evidenceLogs missing`);
  }
  return policy;
}

function validateEvidenceLog(entry, spec, artifacts) {
  const full = resolve(root, entry.file);
  assert.ok(existsSync(full), `${spec.unit}: evidence log missing ${entry.file}`);
  const bytes = readFileSync(full);
  const text = bytes.toString('utf8');
  const failureEvents = ['baseline.failed', 'verification.failed', 'verification.unit.fail'];
  for (const event of failureEvents) {
    assert.equal(text.includes(`"event":"${event}"`), false,
      `${entry.file}: failure event ${event} must not be accepted`);
  }
  const lines = text.split('\n').filter(line => line.trim() !== '');
  const events = [];
  let ignoredLines = 0;
  for (const line of lines) {
    try {
      events.push(JSON.parse(line));
    } catch {
      // Progress banners and wrapped stack text are not JSON events. They are
      // counted, not treated as success; explicit success and failure events
      // above/below decide acceptance.
      ignoredLines++;
    }
  }
  const failures = events.filter(event =>
    failureEvents.includes(event.event));
  assert.equal(failures.length, 0, `${entry.file}: failure event ${failures[0]?.event ?? ''} must not be accepted`);
  const matches = events.filter(event => {
    if (event.event !== entry.requiredEvent) return false;
    if (entry.unitMatch === true && event.unit !== spec.unit) return false;
    if (entry.artifactMatch === true) {
      if (typeof event.file !== 'string') return false;
      const artifact = artifacts.find(item => item.file === event.file);
      if (artifact === undefined) return false;
      if (event.sha256 !== undefined && artifact.sha256 !== null && event.sha256 !== artifact.sha256) return false;
    }
    return true;
  });
  assert.ok(matches.length > 0,
    `${entry.file}: missing successful event ${entry.requiredEvent}${entry.unitMatch ? ` for ${spec.unit}` : ''}`);
  return {
    file: entry.file,
    bytes: bytes.length,
    sha256: sha256(bytes),
    requiredEvent: entry.requiredEvent,
    unitMatch: entry.unitMatch === true,
    artifactMatch: entry.artifactMatch === true,
    matchedEvents: matches.length,
    ignoredLines,
  };
}

function collectUnits(policy, allowPendingS025) {
  const units = [];
  const missing = [];
  const missingEvidence = [];
  const failureDetails = [];
  for (const spec of policy.units) {
    const semanticOnly = spec.semanticOnly === true;
    const entries = [];
    for (const file of spec.entries) {
      if (!existsSync(resolve(root, file))) missing.push({ unit: spec.unit, kind: 'entry', file });
      else entries.push(fileInfo(file));
    }
    const artifacts = [];
    for (const pattern of spec.artifacts) {
      const files = expandPattern(pattern);
      if (files.length === 0) missing.push({ unit: spec.unit, kind: 'artifact', file: pattern });
      for (const file of files) {
        const info = fileInfo(file);
        artifacts.push(semanticOnly ? { ...info, sha256: null, semanticOnly: true } : info);
      }
    }
    artifacts.sort(byFile);
    entries.sort(byFile);
    const evidenceLogs = [];
    try {
      for (const entry of policy.unitEvidence[spec.unit]) {
        evidenceLogs.push(validateEvidenceLog(entry, spec, artifacts));
      }
    } catch (error) {
      missingEvidence.push(spec.unit);
      failureDetails.push({ unit: spec.unit, message: error instanceof Error ? error.message : String(error) });
    }
    evidenceLogs.sort(byFile);
    const testEvidence = evidenceLogs.length === policy.unitEvidence[spec.unit].length;
    if (!testEvidence && !missingEvidence.includes(spec.unit)) missingEvidence.push(spec.unit);
    const pending = missing.some(item => item.unit === spec.unit) || !testEvidence;
    units.push({
      unit: spec.unit,
      stage: spec.stage,
      entries: entries.map(entry => ({ file: entry.file, sha256: entry.sha256 })),
      artifacts,
      evidenceLogs,
      testEvidence,
      status: pending ? 'pending' : 'complete',
    });
  }
  const blockingMissing = missing.filter(item => !(allowPendingS025 && item.unit === 'S0.25'));
  const blockingEvidence = missingEvidence.filter(unit => !(allowPendingS025 && unit === 'S0.25'));
  const pending = units.filter(unit => unit.status === 'pending').map(unit => unit.unit);
  return {
    units,
    missing,
    missingEvidence,
    failureDetails,
    blockingMissing,
    blockingEvidence,
    pending,
  };
}

function collectDeferred(policy, planContext) {
  assert.ok(planContext !== null, 'deferred follow-up validation requires the execution plan');
  const orderById = new Map();
  for (const unit of planContext.expected.units) orderById.set(unit.id, unit.order);
  for (const exit of planContext.expected.stageExits) orderById.set(exit.id, exit.order);
  const notices = readJson('config/runtime-notices.json');
  const assets = readJson('config/runtime-assets.json');
  const release = readJson('config/release-contract.json');
  const items = [];
  for (const component of notices.components.filter(entry => entry.status === 'unresolved')) {
    const owner = component.followUp?.unit ?? 'unassigned';
    items.push({
      id: `license:${component.id}`,
      kind: 'license',
      owner,
      followUpGate: owner === 'S0.15'
        ? policy.deferredPolicy.desktopApkDispositionGate
        : policy.deferredPolicy.licenseFollowUpGate,
      reason: component.followUp?.reason ?? component.noticeAction,
      notS0Failure: true,
      releaseBlocker: true,
    });
  }
  for (const task of release.native16k.tasks) {
    items.push({
      id: `native16k:${task.assetId}:${task.path}`,
      kind: 'native-16k',
      owner: task.deliveryUnit,
      followUpGate: policy.deferredPolicy.native16kFollowUpGate,
      reason: task.issue,
      notS0Failure: true,
      releaseBlocker: true,
    });
  }
  const unassignedOwners = [];
  const unknownOwners = [];
  const unknownFollowUpGates = [];
  const forwardFollowUpGates = [];
  for (const item of items) {
    item.ownerOrder = orderById.get(item.owner) ?? null;
    item.followUpGateOrder = orderById.get(item.followUpGate) ?? null;
    if (item.owner === 'unassigned' || item.ownerOrder === null) {
      if (item.owner === 'unassigned') unassignedOwners.push(item.id);
      else unknownOwners.push({ id: item.id, owner: item.owner });
    }
    if (item.followUpGateOrder === null) {
      unknownFollowUpGates.push({ id: item.id, followUpGate: item.followUpGate });
    } else if (item.ownerOrder !== null && item.followUpGateOrder <= item.ownerOrder) {
      forwardFollowUpGates.push({
        id: item.id, owner: item.owner, ownerOrder: item.ownerOrder,
        followUpGate: item.followUpGate, followUpGateOrder: item.followUpGateOrder,
      });
    }
  }
  assert.deepEqual(unassignedOwners, [], 'deferred items must not have unassigned owners');
  assert.deepEqual(unknownOwners, [], 'deferred owners must exist in the execution plan');
  assert.deepEqual(unknownFollowUpGates, [], 'deferred follow-up gates must exist in the execution plan');
  assert.deepEqual(forwardFollowUpGates, [], 'deferred follow-up gates must follow their owners');
  items.sort((left, right) => byteCompare(left.id, right.id));
  return {
    summary: {
      license: {
        components: notices.summary.components,
        resolved: notices.summary.resolved,
        unresolved: notices.summary.unresolved,
        notRequired: notices.summary.notRequired,
        unknownLicense: assets.checks.licenseUnknown,
        declaredUnverified: assets.checks.licenseDeclaredUnverified,
        sourceGaps: assets.checks.sourceGaps,
        followUps: assets.checks.followUps,
      },
      native16k: {
        checked: assets.checks.elfChecked,
        passed: assets.checks.elf16kPass,
        failed: assets.checks.elf16kFail,
        tasks: release.native16k.tasks.length,
      },
    },
    verification: {
      items: items.length,
      unassignedOwners: 0,
      unknownOwners: 0,
      unknownFollowUpGates: 0,
      forwardFollowUpGates: 0,
      notS0FailureIsWaiver: false,
      releaseBlocker: true,
    },
    items,
  };
}

function collectM0(policy, status, pending) {
  return {
    owner: policy.m0.owner,
    status,
    complete: false,
    signedOff: false,
    signoffSource: policy.mainReview.receipt,
    gateToolSignsOff: false,
    pending: [...pending],
    conditions: [...policy.m0.conditions],
  };
}

function validateMainReviewReceipt(policy, lock, lockBytes, receipt) {
  assert.equal(receipt.formatVersion, policy.mainReview.formatVersion, 'main review format version');
  assert.equal(receipt.format, policy.mainReview.format, 'main review format');
  assert.equal(receipt.unit, UNIT, 'main review unit');
  assert.ok(['accepted', 'pending'].includes(receipt.decision), `main review decision: ${receipt.decision}`);
  if (receipt.decision !== policy.mainReview.decision) {
    return {
      present: true,
      signed: false,
      m0Complete: false,
      status: receipt.decision,
    };
  }
  assert.equal(receipt.signer, policy.mainReview.signer, 'main review signer');
  assert.match(receipt.signedAt ?? '', /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/u,
    'main review signedAt');
  assert.equal(receipt.finalLock?.file, FINAL_LOCK, 'main review final-lock path');
  assert.equal(receipt.finalLock?.sha256, sha256(lockBytes), 'main review final-lock hash');
  const policyBytes = readFileSync(resolve(root, GATE_CONFIG));
  assert.equal(receipt.policy?.file, GATE_CONFIG, 'main review policy path');
  assert.equal(receipt.policy?.sha256, sha256(policyBytes), 'main review policy hash');
  assert.equal(receipt.assembly?.sqlSetSha256, lock.sql.setSha256, 'main review SQL set');
  for (const database of lock.databases) {
    const claimed = receipt.assembly?.databases?.[database.id];
    assert.ok(claimed, `main review database ${database.id}`);
    assert.equal(claimed.version, database.version, `main review ${database.id} version`);
    assert.equal(claimed.fingerprint, database.fingerprint, `main review ${database.id} fingerprint`);
  }
  assert.equal(receipt.registry?.entries, lock.registry.count, 'main review registry count');
  assert.equal(receipt.registry?.rowSync, lock.registry.rowSync, 'main review RowSync count');
  assert.equal(lock.executionPlan?.freezeReady, true, 'main review S0.25 freeze readiness');
  assert.equal(lock.executionPlan?.unresolvedMembers, 0, 'main review unresolved members');
  assert.equal(lock.executionPlan?.unresolved, 0, 'main review unresolved identities');
  assert.equal(lock.executionPlan?.m0Signed, 24, 'main review M0 signed rows');
  assert.equal(lock.executionPlan?.m0Ready, true, 'main review M0 readiness');
  assert.equal(lock.deferred?.verification?.unassignedOwners, 0, 'main review deferred unassigned owners');
  assert.equal(lock.deferred?.verification?.unknownOwners, 0, 'main review deferred unknown owners');
  assert.equal(lock.deferred?.verification?.unknownFollowUpGates, 0, 'main review deferred unknown gates');
  assert.equal(lock.deferred?.verification?.forwardFollowUpGates, 0, 'main review deferred forward gates');
  assert.equal(lock.deferred?.verification?.notS0FailureIsWaiver, false, 'main review deferred waiver');
  assert.equal(lock.deferred?.verification?.releaseBlocker, true, 'main review deferred release blocker');
  assert.equal(receipt.units?.count, policy.mainReview.requiredUnits, 'main review unit count');
  assert.equal(receipt.units?.allEvidence, true, 'main review all-unit evidence');
  assert.deepEqual(receipt.units?.units, lock.units.map(unit => unit.unit), 'main review unit list');
  for (const unit of lock.units) {
    assert.equal(unit.status, 'complete', `main review ${unit.unit} status`);
    assert.equal(unit.testEvidence, true, `main review ${unit.unit} evidence`);
  }
  assert.equal(receipt.m0?.complete, true, 'main review M0 complete');
  assert.equal(receipt.m0?.signedOff, true, 'main review M0 signed');
  return {
    present: true,
    signed: true,
    m0Complete: true,
    status: 'accepted',
    signedAt: receipt.signedAt,
  };
}

function mainReviewState(policy, lock) {
  const file = policy.mainReview.receipt;
  if (!existsSync(resolve(root, file))) {
    return { present: false, signed: false, m0Complete: false, status: 'not-provided' };
  }
  const receipt = readJson(file);
  const state = validateMainReviewReceipt(policy, lock, readFileSync(resolve(root, FINAL_LOCK)), receipt);
  return { ...state, receiptSha256: sha256(readFileSync(resolve(root, file))) };
}

async function runAssembly() {
  const directory = mkdtempSync(join(tmpdir(), 'dstu-s0-g-result-'));
  const resultFile = join(directory, 'result.json');
  try {
    try {
      execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--worker', resultFile], {
        cwd: root,
        stdio: ['ignore', 'inherit', 'inherit'],
      });
    } catch (error) {
      const payload = existsSync(resultFile) ? JSON.parse(readFileSync(resultFile, 'utf8')) : null;
      throw new Error(`S0.G assembly failed: ${payload?.error ?? error.message}`);
    }
    const payload = JSON.parse(readFileSync(resultFile, 'utf8'));
    if (payload.error) throw new Error(payload.error);
    return payload;
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

async function validateSqlSet(policy, files) {
  assert.equal(files.length, policy.expected.finalMigrations, 'final SQL file count');
  const upstream = files.filter(file => file.origin === 'upstream-history');
  const coordinated = files.filter(file => file.origin === 'coordinated');
  assert.equal(upstream.length, policy.expected.upstreamMigrations, 'upstream SQL count');
  assert.equal(coordinated.length, policy.expected.coordinatedMigrations, 'coordinated SQL count');
  const expectedCoordinated = new Map(policy.expected.coordinated.map(item => [item.file, item]));
  assert.equal(coordinated.length, expectedCoordinated.size, 'coordinated SQL target set');
  for (const file of coordinated) {
    const expected = expectedCoordinated.get(file.file);
    assert.ok(expected, `unexpected coordinated SQL: ${file.file}`);
    assert.equal(file.database, expected.database, `${file.file}: database`);
    assert.equal(file.version, expected.version, `${file.file}: version`);
    assert.equal(file.name, expected.name, `${file.file}: name`);
    assert.equal(file.unit, expected.unit, `${file.file}: unit`);
  }
  const duplicate = new Set();
  const seen = new Set();
  const databaseCounts = Object.fromEntries(
    Object.keys(policy.expected.databaseMigrationCounts).map(database => [database, 0]),
  );
  for (const file of files) {
    const parsed = parseSqlFile(file.file);
    assert.equal(file.version, parsed.version, `${file.file}: filename version`);
    assert.equal(file.name, parsed.name, `${file.file}: filename name`);
    assert.ok(!STALE_VERSIONS.has(file.version), `${file.file}: stale version ${file.version}`);
    const key = `${file.database}:${file.version}`;
    if (seen.has(key)) duplicate.add(key);
    seen.add(key);
    const full = resolve(root, file.file);
    assert.ok(existsSync(full), `${file.file}: missing`);
    const bytes = readFileSync(full);
    assert.equal(file.bytes, bytes.length, `${file.file}: byte count`);
    assert.equal(file.sha256, sha256(bytes), `${file.file}: SHA-256`);
    const checksum = await import(pathToFileURL(resolve(desktop, 'host/platform/storage/migration/refinery.ts')).href)
      .then(module => module.refineryChecksum(file.name, file.version, bytes.toString('utf8')).toString());
    assert.equal(file.refineryChecksum, checksum, `${file.file}: refinery checksum`);
    databaseCounts[file.database] = (databaseCounts[file.database] ?? 0) + 1;
  }
  assert.deepEqual([...duplicate], [], 'duplicate database/version');
  assert.deepEqual(databaseCounts, policy.expected.databaseMigrationCounts, 'database migration counts');
  const order = {};
  for (const database of Object.keys(policy.expected.databaseMigrationCounts)) {
    const versions = files.filter(file => file.database === database).map(file => file.version).sort((left, right) => left - right);
    assert.equal(new Set(versions).size, versions.length, `${database}: duplicate version`);
    order[database] = versions;
  }
  return { order, duplicate: [], stale: [] };
}

function executionPlanContext(api) {
  const sources = api.loadSources();
  const built = api.buildPlan(sources);
  return { sources, ctx: built.ctx, expected: built.plan };
}

function validateExecutionPlan(plan, api, context) {
  api.validatePlan(plan, context.ctx);
  const drift = difference(semanticProjection(plan, api), semanticProjection(context.expected, api));
  if (drift !== null) throw new Error(`execution plan semantic drift at ${drift.path}`);
  const s0Units = plan.units.filter(unit => unit.id.startsWith('S0.'));
  const s0Cards = plan.cards.filter(card => String(card.unit).startsWith('S0.'));
  const unresolvedMembers = plan.coverage?.parameterized?.unresolvedMembers;
  const unresolved = plan.unresolved?.length;
  const m0Rows = plan.m0?.rows ?? [];
  const m0Signed = m0Rows.filter(row => row.status === 'signed').length;
  const issues = [];
  if (unresolvedMembers !== 0) issues.push(`coverage.parameterized.unresolvedMembers=${unresolvedMembers}`);
  if (unresolved !== 0) issues.push(`unresolved.length=${unresolved}`);
  if (m0Rows.length !== 24) issues.push(`m0.rows=${m0Rows.length}`);
  if (m0Signed !== 24) issues.push(`m0.signed=${m0Signed}/24`);
  if (plan.m0?.readyForFinalSignoff !== true) issues.push(`m0.readyForFinalSignoff=${plan.m0?.readyForFinalSignoff ?? 'null'}`);
  if (plan.m0?.status !== 'ready-for-final-signoff') issues.push(`m0.status=${plan.m0?.status ?? 'null'}`);
  if ((plan.m0?.pendingUnits ?? []).length !== 0) issues.push(`m0.pendingUnits=${(plan.m0?.pendingUnits ?? []).join(',')}`);
  const durationProjection = {
    unit: plan.units.map(unit => [unit.id, unit.estimatedWorkDays, unit.actualWorkDays]),
    cards: plan.cards.map(card => [card.id, card.estimatedWorkDays, card.actualWorkDays]),
    stageExits: plan.stageExits.map(exit => [exit.id, exit.estimatedWorkDays, exit.actualWorkDays]),
  };
  const boundSections = {
    units: sha256(JSON.stringify(plan.units)),
    unitDependencies: sha256(JSON.stringify(plan.units.map(unit => ({
      id: unit.id, dependencies: unit.dependencies, declaredDependencies: unit.declaredDependencies,
      inheritedDependencies: unit.inheritedDependencies,
    })))),
    cards: sha256(JSON.stringify(plan.cards)),
    durations: sha256(JSON.stringify(durationProjection)),
    scenarios: sha256(JSON.stringify(plan.scenarios)),
    stageExits: sha256(JSON.stringify(plan.stageExits)),
    milestones: sha256(JSON.stringify(plan.stageExits.map(exit => ({
      id: exit.id, milestone: exit.milestone, projectedCompletionDate: exit.projectedCompletionDate,
      cumulativeDays: exit.cumulativeDays,
    })))),
    m0: sha256(JSON.stringify(plan.m0)),
    coverage: sha256(JSON.stringify(plan.coverage)),
  };
  return {
    schemaVersion: plan.schemaVersion,
    units: plan.units.length,
    stageExits: plan.stageExits.length,
    serialQueue: plan.queue.length,
    cards: plan.cards.length,
    s0Units: s0Units.length,
    s0Cards: s0Cards.length,
    unitOrderSha256: sha256(JSON.stringify(plan.units.map(unit => ({
      id: unit.id, serialIndex: unit.serialIndex, order: unit.order, dependencies: unit.dependencies,
    })))),
    cardIdsSha256: sha256(JSON.stringify(plan.cards.map(card => ({
      id: card.id, unit: card.unit, role: card.role, order: card.order,
    })))),
    freezeReady: issues.length === 0,
    freezeIssues: issues,
    unresolvedMembers,
    unresolved,
    m0Rows: m0Rows.length,
    m0Signed,
    m0Ready: plan.m0?.readyForFinalSignoff ?? false,
    m0Status: plan.m0?.status ?? null,
    boundSections,
    s025: {
      status: plan.units.find(unit => unit.id === 'S0.25')?.status ?? null,
      m0Status: plan.m0?.status ?? null,
      m0Ready: plan.m0?.readyForFinalSignoff ?? null,
      pendingUnits: plan.m0?.pendingUnits ?? [],
      signed: plan.signed,
    },
  };
}

function semanticProjection(plan, api) {
  const value = api.stablePlan(plan);
  delete value.generatedAt;
  delete value.status;
  delete value.unitStatus;
  delete value.signed;
  if (value.plan) delete value.plan.sha256;
  if (value.baseline) delete value.baseline.contractStateNote;
  if (value.policy) delete value.policy.hashPolicy;
  if (value.evidence) {
    delete value.evidence.inputsSha256;
    for (const binding of value.evidence.inputBindings ?? []) {
      delete binding.sha256;
      delete binding.bytes;
      delete binding.observedAt;
    }
  }
  for (const row of value.m0?.rows ?? []) {
    for (const artifact of row.artifacts ?? []) delete artifact.sha256;
  }
  delete value.finalLock;
  delete value.receipt;
  delete value.gate;
  delete value.report;
  return value;
}

async function expectFailure(name, fn) {
  try {
    await fn();
    return { case: name, status: 'failed', reason: 'expected failure did not occur' };
  } catch (error) {
    return { case: name, status: 'passed', reason: error instanceof Error ? error.message : String(error) };
  }
}

async function negativeControls(policy, sql, plan, api, context) {
  const results = [];
  results.push(await expectFailure('drop-migration', () => validateSqlSet(policy,
    sql.files.filter(file => !(file.origin === 'coordinated' && file.version === 20261117)))));
  const tampered = sql.files.map(file => ({ ...file }));
  tampered[0].sha256 = '0'.repeat(64);
  results.push(await expectFailure('tamper-hash', () => validateSqlSet(policy, tampered)));
  const stale = sql.files.map(file => ({ ...file }));
  stale.push({ ...stale[0], version: 20261114, name: 'stale_fixture' });
  results.push(await expectFailure('stale-version', () => validateSqlSet(policy, stale)));
  if (plan === null) {
    results.push({
      case: 'forward-dependency',
      status: 'not-applicable',
      reason: 'S0.25 execution plan not delivered',
    });
  } else {
    const mutated = structuredClone(plan);
    const position = mutated.units.findIndex(item => item.id === 'S0.02');
    assert.ok(position >= 0, 'execution plan S0.02 for negative control');
    mutated.units[position].dependencies = ['S9.19'];
    results.push(await expectFailure('forward-dependency', () => validateExecutionPlan(mutated, api, context)));
  }
  const failed = results.filter(result => result.status === 'failed');
  assert.deepEqual(failed, [], 'negative controls must reject');
  return results.map(result => ({ case: result.case, status: result.status }));
}

function executionPlan() {
  const file = 'config/execution-plan.json';
  return existsSync(resolve(root, file)) ? readJson(file) : null;
}

function executionPlanBinding(policy, plan, api, context) {
  const file = 'config/execution-plan.json';
  if (plan === null) {
    return {
      present: false,
      artifact: file,
      semanticSha256: null,
      units: 0,
      s0Units: 0,
    };
  }
  const summary = validateExecutionPlan(plan, api, context);
  return {
    present: true,
    artifact: file,
    semanticSha256: sha256(JSON.stringify(semanticProjection(plan, api))),
    ...summary,
    artifactHashedByGate: false,
    reason: 'S0.25 and S0.G must not hash each other; the gate binds the plan semantic projection instead of its raw bytes.',
  };
}

function binding(policy, assembly, units, controls, status, executionPlanState, planContext) {
  const pending = status === 'draft' ? units.pending : [];
  return {
    formatVersion: 1,
    format: LOCK_FORMAT,
    unit: UNIT,
    status,
    signedOff: false,
    policy: { file: GATE_CONFIG, sha256: sha256(readFileSync(resolve(root, GATE_CONFIG))) },
    tool: { file: 'tools/baseline/stage-gate.mjs', sha256: sha256(readFileSync(fileURLToPath(import.meta.url))) },
    desktop: assembly.desktop,
    increment: assembly.increment,
    sql: assembly.sql,
    databases: assembly.databases,
    browser: assembly.browser,
    registry: assembly.registry,
    units: units.units,
    executionPlan: executionPlanState,
    mainReview: {
      receipt: policy.mainReview.receipt,
      formatVersion: policy.mainReview.formatVersion,
      format: policy.mainReview.format,
      signer: policy.mainReview.signer,
      decision: policy.mainReview.decision,
      requiredUnits: policy.mainReview.requiredUnits,
      receiptHashedByLock: false,
    },
    deferred: collectDeferred(policy, planContext),
    limitations: policy.limitations,
    negativeControls: controls,
    m0: collectM0(policy, status === 'draft' ? 'draft' : 'validated', pending),
    pending,
  };
}

async function runWorker(resultFile) {
  const policy = loadPolicy();
  const temp = mkdtempSync(join(tmpdir(), 'dstu-s0-g-assembly-'));
  const handles = [];
  try {
    const templates = await import('../../desktop-increment/templates-assets.mjs');
    const media = await import('../../desktop-increment/media-time.mjs');
    const portable = await import('../../desktop-increment/portable-config.mjs');
    const plugin = await import('../../desktop-increment/plugin-data.mjs');
    const loader = await import('../../desktop-increment/loader.mjs');

    handles.push(templates.installTemplateAssetIncrement());
    handles.push(media.installMediaTimeIncrement());
    handles.push(portable.installPortableConfigIncrement());
    handles.push(plugin.installPluginDataIncrement());
    assert.deepEqual(loader.installedIncrementIds(), policy.expected.incrementIds, 'installed increment order');

    const desktopFreeze = (() => {
      const commit = git(desktop, 'rev-parse', 'HEAD');
      const tree = git(desktop, 'rev-parse', 'HEAD^{tree}');
      const dirty = git(desktop, 'status', '--porcelain');
      assert.equal(commit, policy.desktop.commit, 'desktop commit');
      assert.equal(tree, policy.desktop.tree, 'desktop tree');
      assert.equal(dirty, '', 'desktop working tree must stay clean');
      const lock = readJson(policy.desktop.sourceLock);
      const entry = lock.repositories.find(repository => repository.id === 'desktop');
      assert.ok(entry, 'source lock desktop entry');
      assert.equal(entry.commit, commit, 'source lock commit');
      assert.equal(entry.tree, tree, 'source lock tree');
      return {
        repository: policy.desktop.repository,
        commit,
        tree,
        dirty: false,
        sourceLock: {
          file: policy.desktop.sourceLock,
          sha256: sha256(readFileSync(resolve(root, policy.desktop.sourceLock))),
        },
      };
    })();

    const increment = (() => {
      const files = [
        ...walkRelative('desktop-increment').filter(file => file.endsWith('.mjs')),
        ...walkRelative('config/schema/coordinated').filter(file => file.endsWith('.sql')),
      ].map(fileInfo).sort(byFile);
      return {
        files,
        setSha256: sha256(files.map(file => `${file.file}\0${file.sha256}\n`).join('')),
      };
    })();

    const historyRoot = 'config/schema/history';
    const coordinatedRoot = 'config/schema/coordinated';
    const historyFiles = walkRelative(historyRoot).filter(file => file.endsWith('.sql'));
    const coordinatedFiles = walkRelative(coordinatedRoot).filter(file => file.endsWith('.sql'));
    assert.equal(historyFiles.length, policy.expected.upstreamMigrations, 'upstream history snapshot count');
    assert.equal(coordinatedFiles.length, policy.expected.coordinatedMigrations, 'coordinated snapshot count');

    const migrationLock = JSON.parse(readFileSync(resolve(desktop, 'resources/app/migrations/migration-lock.json'), 'utf8'));
    const original = JSON.parse(execFileSync('git', ['show', '0222ccc:config/baseline/schema.json'], {
      cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
    }));
    const projectSchema = readJson('config/baseline/schema.json');
    assert.equal(original.migrations.length, 134, 'original immutable migration set');
    assert.equal(projectSchema.migrations.length, policy.expected.upstreamMigrations, 'S0.08 schema migration count');
    const originalHashes = new Map(original.migrations.map(item => [item.source, item.sha256]));
    const appended = projectSchema.migrations.find(item => item.database === 'vfs' && item.version === 20261115);
    assert.ok(appended, 'upstream V20261115 migration');
    assert.equal(appended.sha256, projectSchema.historyFreeze.appended.sha256, 'S0.08 appended hash');

    const refinery = await loadDesktop('host/platform/storage/migration/refinery.ts');
    const sqlFiles = [];
    for (const file of historyFiles) {
      const relativePath = file.slice(historyRoot.length + 1);
      const parsed = parseSqlFile(relativePath);
      const bytes = readFileSync(resolve(root, file));
      const desktopFile = resolve(desktop, 'resources/app/migrations', relativePath);
      assert.ok(existsSync(desktopFile), `${relativePath}: desktop migration missing`);
      assert.deepEqual(bytes, readFileSync(desktopFile), `${relativePath}: frozen bytes differ from desktop`);
      const lockEntry = migrationLock.entries.find(item => item.path === relativePath);
      assert.ok(lockEntry, `${relativePath}: migration lock entry`);
      assert.equal(lockEntry.version, parsed.version, `${relativePath}: lock version`);
      assert.equal(lockEntry.name, parsed.name, `${relativePath}: lock name`);
      assert.equal(lockEntry.sha256, sha256(bytes), `${relativePath}: lock hash`);
      const originalSource = `resources/app/migrations/${relativePath}`;
      if (originalHashes.has(originalSource)) {
        assert.equal(originalHashes.get(originalSource), sha256(bytes), `${relativePath}: original immutable hash`);
      } else {
        assert.equal(relativePath, appended.snapshot.replace(`${historyRoot}/`, ''), 'only V20261115 may be appended');
        assert.equal(sha256(bytes), appended.sha256, `${relativePath}: appended hash`);
      }
      sqlFiles.push({
        file,
        origin: 'upstream-history',
        unit: 'S0.08',
        database: relativePath.split('/')[0],
        version: parsed.version,
        name: parsed.name,
        bytes: bytes.length,
        sha256: sha256(bytes),
        refineryChecksum: refinery.refineryChecksum(parsed.name, parsed.version, bytes.toString('utf8')).toString(),
      });
    }
    for (const file of coordinatedFiles) {
      const relativePath = file.slice(coordinatedRoot.length + 1);
      const expected = policy.expected.coordinated.find(item => item.file === file);
      assert.ok(expected, `coordinated SQL not registered in policy: ${file}`);
      const parsed = parseSqlFile(relativePath);
      assert.equal(parsed.version, expected.version, `${file}: coordinated version`);
      assert.equal(parsed.name, expected.name, `${file}: coordinated name`);
      const bytes = readFileSync(resolve(root, file));
      sqlFiles.push({
        file,
        origin: 'coordinated',
        unit: expected.unit,
        database: relativePath.split('/')[0],
        version: parsed.version,
        name: parsed.name,
        bytes: bytes.length,
        sha256: sha256(bytes),
        refineryChecksum: refinery.refineryChecksum(parsed.name, parsed.version, bytes.toString('utf8')).toString(),
      });
    }
    sqlFiles.sort((left, right) => byteCompare(left.database, right.database)
      || left.version - right.version
      || byteCompare(left.file, right.file));
    const sqlValidation = await validateSqlSet(policy, sqlFiles);

    const finalDirectory = join(temp, 'final');
    copySqlTree(historyRoot, finalDirectory);
    copySqlTree(coordinatedRoot, finalDirectory);
    const finalPrepared = inspectSqlDirectory(finalDirectory);
    assert.equal(finalPrepared.count, policy.expected.finalMigrations, 'final prepared SQL count');
    const finalByRelative = new Map(finalPrepared.files.map(file => [file.relative, file]));
    for (const file of sqlFiles) {
      const relativePath = file.origin === 'upstream-history'
        ? file.file.slice(historyRoot.length + 1)
        : file.file.slice(coordinatedRoot.length + 1);
      const prepared = finalByRelative.get(relativePath);
      assert.ok(prepared, `${file.file}: final prepared entry`);
      assert.equal(prepared.sha256, file.sha256, `${file.file}: final prepared hash`);
    }

    const preparedDirectories = {
      template: templates.prepareTemplateMigrations(join(temp, 'prepared-template')),
      media: media.prepareMediaTimeMigrations(join(temp, 'prepared-media')),
      plugin: plugin.preparePluginDataMigrations(join(temp, 'prepared-plugin')),
    };
    const prepared = {};
    for (const [name, directory] of Object.entries(preparedDirectories)) {
      const inspected = inspectSqlDirectory(directory);
      for (const file of inspected.files) {
        const final = finalByRelative.get(file.relative);
        assert.ok(final, `${name}: unexpected prepared SQL ${file.relative}`);
        assert.equal(file.sha256, final.sha256, `${name}: prepared SQL drift ${file.relative}`);
      }
      const expectedCounts = { template: 136, media: 137, plugin: 138 };
      assert.equal(inspected.count, expectedCounts[name], `${name}: prepared SQL count`);
      if (name === 'plugin') {
        assert.equal(inspected.files.some(file => file.relative === 'vfs/V20261116__plugin_skill_data_contract.sql'), false,
          'stale V20261116 plugin SQL must not be prepared');
      }
      prepared[name] = {
        count: inspected.count,
        setSha256: inspected.setSha256,
        matchesFinal: true,
      };
    }

    const { MigrationCoordinator } = await loadDesktop('host/platform/storage/migration/coordinator.ts');
    const { Connection } = await loadDesktop('host/platform/storage/connection.ts');
    const { Runner, loadSqlMigrations } = await loadDesktop('host/platform/storage/migration/refinery.ts');
    const { computeSchemaFingerprint } = await loadDesktop('host/platform/storage/migration/fingerprint.ts');
    const migrationLog = (level, message) => {
      if (level === 'error') log('s0-g.migration', { level, message });
    };
    const fresh = new MigrationCoordinator({
      appDataDir: join(temp, 'fresh'),
      migrationsDir: finalDirectory,
      auditDbPath: null,
      log: migrationLog,
    });
    const freshReport = await fresh.runAll();
    assert.equal(freshReport.success, true, 'fresh migration report');
    const freshReportMap = reportMap(freshReport);
    const expectedByDatabase = new Map();
    for (const database of policy.expected.sharedDatabases) {
      expectedByDatabase.set(database, sqlFiles.filter(file => file.database === database).sort((a, b) => a.version - b.version));
    }

    const databases = [];
    for (const database of policy.expected.sharedDatabases) {
      const report = freshReportMap.get(database);
      assert.ok(report, `${database}: fresh report`);
      assert.equal(report.success, true, `${database}: fresh success`);
      assert.equal(report.fromVersion, 0, `${database}: fresh from`);
      assert.equal(report.toVersion, policy.expected.databaseVersions[database], `${database}: fresh to`);
      assert.equal(report.appliedCount, policy.expected.databaseMigrationCounts[database], `${database}: fresh applied`);
      const set = fresh.sets[database];
      assert.ok(set, `${database}: migration set`);
      assert.equal(set.count(), policy.expected.databaseMigrationCounts[database], `${database}: MigrationSet count`);
      assert.equal(set.latestVersion(), policy.expected.databaseVersions[database], `${database}: MigrationSet latest`);
      const expected = expectedByDatabase.get(database);
      assert.equal(set.migrations.length, expected.length, `${database}: definition count`);
      for (const migration of expected) {
        const definition = set.get(migration.version);
        assert.ok(definition, `${database}/${migration.version}: definition`);
        assert.equal(
          definition.sql,
          readFileSync(resolve(root, migration.file), 'utf8'),
          `${database}/${migration.version}: definition SQL`,
        );
      }
      const connection = new Connection(fresh.getDatabasePath(database), {
        label: `s0g-fresh-${database}`,
        profile: { foreignKeys: true },
      });
      try {
        const history = historyRows(connection);
        assert.equal(history.length, expected.length, `${database}: history count`);
        for (const [index, row] of history.entries()) {
          assert.equal(Number(row.version), expected[index].version, `${database}: history version ${index}`);
          assert.equal(row.name, expected[index].name, `${database}: history name ${index}`);
          assert.equal(String(row.checksum), expected[index].refineryChecksum, `${database}: history checksum ${index}`);
        }
        const fingerprint = computeSchemaFingerprint(connection, set, set.latestVersion());
        const managed = summarizeScope(fingerprint.scope);
        const governance = governanceRow(connection, database, set.latestVersion());
        if (governance.present) {
          assert.equal(governance.fingerprint, fingerprint.hash, `${database}: governance fingerprint`);
          assert.equal(governance.canonicalSchemaSha256, sha256(fingerprint.canonicalSchema), `${database}: governance canonical schema`);
        }
        const integrity = checkDatabaseIntegrity(connection);
        assert.equal(integrity.integrity, 'ok', `${database}: fresh integrity`);
        assert.equal(integrity.foreignKeyViolations, 0, `${database}: fresh foreign keys`);
        databases.push({
          id: database,
          version: set.latestVersion(),
          migrationCount: set.count(),
          history,
          historySha256: sha256(JSON.stringify(history)),
          fingerprint: fingerprint.hash,
          canonicalSchemaSha256: sha256(fingerprint.canonicalSchema),
          managedSchema: managed,
          governance,
          fresh: {
            ...databaseSummary(report),
            integrity: integrity.integrity,
            foreignKeyViolations: integrity.foreignKeyViolations,
            restartAppliedCount: null,
          },
        });
      } finally {
        connection.close();
      }
    }

    const browserConnection = new Connection(':memory:', { label: 's0g-browser', profile: { foreignKeys: true } });
    let browser;
    try {
      const browserMigrations = loadSqlMigrations(join(finalDirectory, 'browser'));
      assert.equal(browserMigrations.length, policy.expected.databaseMigrationCounts.browser, 'browser migration count');
      new Runner(browserMigrations).run(browserConnection);
      const integrity = checkDatabaseIntegrity(browserConnection);
      assert.equal(integrity.integrity, 'ok', 'browser integrity');
      assert.equal(integrity.foreignKeyViolations, 0, 'browser foreign keys');
      browser = {
        version: browserMigrations.at(-1)?.version ?? 0,
        migrationCount: browserMigrations.length,
        integrity: integrity.integrity,
        foreignKeyViolations: integrity.foreignKeyViolations,
      };
    } finally {
      browserConnection.close();
    }

    const restart = await fresh.runAll();
    assert.equal(restart.success, true, 'fresh restart report');
    const restartMap = reportMap(restart);
    for (const database of databases) {
      const report = restartMap.get(database.id);
      assert.ok(report, `${database.id}: restart report`);
      assert.equal(report.success, true, `${database.id}: restart success`);
      assert.equal(report.appliedCount, 0, `${database.id}: restart applied`);
      database.fresh.restartAppliedCount = report.appliedCount;
    }
    const restartConnection = new Connection(fresh.getDatabasePath('vfs'), {
      label: 's0g-fresh-restart-vfs',
      profile: { foreignKeys: true },
    });
    try {
      const set = fresh.sets.vfs;
      const fingerprint = computeSchemaFingerprint(restartConnection, set, set.latestVersion());
      assert.equal(fingerprint.hash, databases.find(database => database.id === 'vfs').fingerprint,
        'vfs: fingerprint after restart');
    } finally {
      restartConnection.close();
    }

    const upstreamDirectory = join(temp, 'upstream');
    copySqlTree(historyRoot, upstreamDirectory);
    const upgradeDirectory = join(temp, 'upgrade');
    for (const database of policy.expected.sharedDatabases) {
      const path = join(upgradeDirectory, database === 'vfs' ? 'databases/vfs.db' : `${database}.db`);
      mkdirSync(dirname(path), { recursive: true });
      const connection = new Connection(path, { label: `s0g-old-${database}`, profile: { foreignKeys: true } });
      try {
        new Runner(loadSqlMigrations(join(upstreamDirectory, database))).run(connection);
      } finally {
        connection.close();
      }
    }

    const seedVfs = connection => {
      connection.run(
        `INSERT INTO exam_sheets (id,exam_name,status,temp_id,metadata_json,preview_json,created_at,updated_at)
         VALUES ('exam_s0g_fixture','S0.G FSRS fixture','completed','temp_s0g_fixture','{}','{}',
                 '2026-11-01T00:00:00Z','2026-11-01T00:00:00Z')`,
      );
      connection.run(
        `INSERT INTO questions (id,exam_id,question_label,content,answer,created_at,updated_at)
         VALUES ('q_s0g_fixture','exam_s0g_fixture','1','S0.G question','A',
                 '2026-11-01T00:00:00Z','2026-11-01T00:00:00Z')`,
      );
      connection.run(
        `INSERT INTO review_plans (
           id,question_id,exam_id,ease_factor,interval_days,repetitions,next_review_date,last_review_date,
           status,total_reviews,total_correct,consecutive_failures,is_difficult,created_at,updated_at,
           device_id,local_version,deleted_at,fsrs_stability,fsrs_difficulty,fsrs_phase,fsrs_learning_step,
           fsrs_lapses,fsrs_due_at,fsrs_last_review_at
         ) VALUES (
           'rp_s0g_fixture','q_s0g_fixture','exam_s0g_fixture',2.3,6,3,'2026-11-01','2026-10-26',
           'reviewing',5,4,0,1,'2026-10-20T08:00:00Z','2026-10-26T09:00:00Z',
           's0g-upgrade-device',7,NULL,12.5,4.25,'review',2,3,1893456000000,1790000000000
         )`,
      );
      connection.run(
        `INSERT INTO review_history (
           id,plan_id,question_id,quality,passed,ease_factor_before,ease_factor_after,
           interval_before,interval_after,repetitions_before,repetitions_after,reviewed_at
         ) VALUES (
           'rh_s0g_fixture','rp_s0g_fixture','q_s0g_fixture',4,1,2.3,2.4,6,7,3,4,
           '2026-10-26T09:00:00Z'
         )`,
      );
      connection.run(
        `INSERT INTO review_stats (exam_id,total_plans,reviewing_count,due_today,total_reviews,total_correct,updated_at)
         VALUES ('exam_s0g_fixture',1,1,1,5,4,'2026-10-26T09:00:00Z')`,
      );
    };
    const seedMistakes = connection => {
      connection.run(
        `INSERT INTO custom_anki_templates (
           id,name,description,author,version,preview_front,preview_back,note_type,fields_json,
           generation_prompt,front_template,back_template,css_style,field_extraction_rules_json,
           created_at,updated_at,is_active,is_built_in,preview_data_json,user_modified,user_deleted
         ) VALUES (
           'tpl_s0g_fixture','S0.G portable template','fixture','S0.G','1.0.0','front','back','Basic','[]',
           'prompt','{{Front}}','{{Back}}','.card{}','{}',
           '2026-10-01T00:00:00Z','2026-10-02T00:00:00Z',1,0,'{"front":"preview"}',1,0
         )`,
      );
    };
    const oldVfsPath = join(upgradeDirectory, 'databases/vfs.db');
    const oldMistakesPath = join(upgradeDirectory, 'mistakes.db');
    const seedConnection = new Connection(oldVfsPath, { label: 's0g-seed-vfs', profile: { foreignKeys: true } });
    try {
      seedVfs(seedConnection);
    } finally {
      seedConnection.close();
    }
    const seedMistakesConnection = new Connection(oldMistakesPath, {
      label: 's0g-seed-mistakes',
      profile: { foreignKeys: true },
    });
    try {
      seedMistakes(seedMistakesConnection);
    } finally {
      seedMistakesConnection.close();
    }

    const snapshot = (() => {
      const vfs = new Connection(oldVfsPath, { label: 's0g-before-vfs', profile: { foreignKeys: true } });
      const mistakes = new Connection(oldMistakesPath, { label: 's0g-before-mistakes', profile: { foreignKeys: true } });
      try {
        const vfsRows = {
          exam: vfs.get("SELECT * FROM exam_sheets WHERE id='exam_s0g_fixture'"),
          question: vfs.get("SELECT * FROM questions WHERE id='q_s0g_fixture'"),
          plan: vfs.get("SELECT * FROM review_plans WHERE id='rp_s0g_fixture'"),
          history: vfs.get("SELECT * FROM review_history WHERE id='rh_s0g_fixture'"),
          stats: vfs.get("SELECT * FROM review_stats WHERE exam_id='exam_s0g_fixture'"),
          changeLog: vfs.all('SELECT id,table_name,record_id,operation,sync_version FROM __change_log ORDER BY id'),
        };
        const templateColumns = mistakes.all("PRAGMA table_info('custom_anki_templates')").map(row => row.name);
        const template = mistakes.get(
          `SELECT ${templateColumns.map(name => QUOTE(name)).join(',')} FROM custom_anki_templates WHERE id='tpl_s0g_fixture'`,
        );
        return {
          vfs: { rows: vfsRows, sha256: sha256(JSON.stringify(vfsRows)) },
          mistakes: {
            templateColumns,
            template,
            sha256: sha256(JSON.stringify({ templateColumns, template })),
          },
        };
      } finally {
        vfs.close();
        mistakes.close();
      }
    })();

    const upgrade = new MigrationCoordinator({
      appDataDir: upgradeDirectory,
      migrationsDir: finalDirectory,
      auditDbPath: null,
      log: migrationLog,
    });
    const upgradeReport = await upgrade.runAll();
    assert.equal(upgradeReport.success, true, 'upgrade report');
    const upgradeMap = reportMap(upgradeReport);
    const expectedUpgrade = {
      vfs: { from: 20261115, to: 20261117, applied: 2 },
      mistakes: { from: 20261005, to: 20261006, applied: 1 },
      chat_v2: { from: 20261111, to: 20261111, applied: 0 },
      llm_usage: { from: 20260826, to: 20260826, applied: 0 },
    };
    for (const [database, expected] of Object.entries(expectedUpgrade)) {
      const report = upgradeMap.get(database);
      assert.ok(report, `${database}: upgrade report`);
      assert.equal(report.success, true, `${database}: upgrade success`);
      assert.equal(report.fromVersion, expected.from, `${database}: upgrade from`);
      assert.equal(report.toVersion, expected.to, `${database}: upgrade to`);
      assert.equal(report.appliedCount, expected.applied, `${database}: upgrade applied`);
    }

    const upgradeConnection = new Connection(oldVfsPath, { label: 's0g-upgraded-vfs', profile: { foreignKeys: true } });
    let preserved;
    try {
      const rows = {
        exam: upgradeConnection.get("SELECT * FROM exam_sheets WHERE id='exam_s0g_fixture'"),
        question: upgradeConnection.get("SELECT * FROM questions WHERE id='q_s0g_fixture'"),
        plan: upgradeConnection.get("SELECT * FROM review_plans WHERE id='rp_s0g_fixture'"),
        history: upgradeConnection.get("SELECT * FROM review_history WHERE id='rh_s0g_fixture'"),
        stats: upgradeConnection.get("SELECT * FROM review_stats WHERE exam_id='exam_s0g_fixture'"),
        changeLog: upgradeConnection.all('SELECT id,table_name,record_id,operation,sync_version FROM __change_log ORDER BY id'),
      };
      assert.deepEqual(rows, snapshot.vfs.rows, 'vfs seeded rows and change log must survive upgrade');
      const fsrs = upgradeConnection.get(
        `SELECT fsrs_stability,fsrs_difficulty,fsrs_phase,fsrs_learning_step,fsrs_lapses,
                fsrs_due_at,fsrs_last_review_at,ease_factor,interval_days,repetitions,next_review_date
           FROM review_plans WHERE id='rp_s0g_fixture'`,
      );
      const newTables = ['portable_media_events', 'portable_plugin_records'].map(name => ({
        name,
        exists: Number(upgradeConnection.pluck(
          "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name=?)", [name],
        )) === 1,
        rows: Number(upgradeConnection.pluck(`SELECT COUNT(*) FROM ${QUOTE(name)}`)),
      }));
      for (const table of newTables) {
        assert.equal(table.exists, true, `${table.name}: upgraded table`);
        assert.equal(table.rows, 0, `${table.name}: upgraded table starts empty`);
      }
      const set = upgrade.sets.vfs;
      const history = historyRows(upgradeConnection);
      const expected = expectedByDatabase.get('vfs');
      assert.equal(history.length, expected.length, 'vfs: upgraded history count');
      for (const [index, row] of history.entries()) {
        assert.equal(Number(row.version), expected[index].version, `vfs: upgraded history version ${index}`);
        assert.equal(row.name, expected[index].name, `vfs: upgraded history name ${index}`);
        assert.equal(String(row.checksum), expected[index].refineryChecksum, `vfs: upgraded history checksum ${index}`);
      }
      const fingerprint = computeSchemaFingerprint(upgradeConnection, set, set.latestVersion());
      const data = databases.find(database => database.id === 'vfs');
      assert.equal(fingerprint.hash, data.fingerprint, 'vfs: upgraded fingerprint equals fresh');
      const integrity = checkDatabaseIntegrity(upgradeConnection);
      assert.equal(integrity.integrity, 'ok', 'vfs: upgraded integrity');
      assert.equal(integrity.foreignKeyViolations, 0, 'vfs: upgraded foreign keys');
      assert.equal(sha256(JSON.stringify(rows)), snapshot.vfs.sha256, 'vfs: preserved row hash');
      data.upgrade = {
        fromVersion: upgradeMap.get('vfs').fromVersion,
        toVersion: upgradeMap.get('vfs').toVersion,
        appliedCount: upgradeMap.get('vfs').appliedCount,
        success: upgradeMap.get('vfs').success,
        historySha256: sha256(JSON.stringify(history)),
        fingerprintMatchesFresh: true,
        integrity: integrity.integrity,
        foreignKeyViolations: integrity.foreignKeyViolations,
        restartAppliedCount: null,
      };
      preserved = {
        vfs: {
          tables: ['exam_sheets', 'questions', 'review_plans', 'review_history', 'review_stats'],
          rowSetSha256: snapshot.vfs.sha256,
          equal: true,
          fsrsRow: fsrs,
          newTables,
        },
      };
    } finally {
      upgradeConnection.close();
    }

    const upgradedMistakes = new Connection(oldMistakesPath, {
      label: 's0g-upgraded-mistakes',
      profile: { foreignKeys: true },
    });
    try {
      const template = upgradedMistakes.get(
        `SELECT ${snapshot.mistakes.templateColumns.map(name => QUOTE(name)).join(',')}
           FROM custom_anki_templates WHERE id='tpl_s0g_fixture'`,
      );
      assert.deepEqual(template, snapshot.mistakes.template, 'mistakes: old template columns must survive upgrade');
      const columns = upgradedMistakes.all("PRAGMA table_info('custom_anki_templates')").map(row => row.name);
      const added = columns.filter(name => !snapshot.mistakes.templateColumns.includes(name));
      assert.deepEqual(added, ['device_id', 'local_version', 'deleted_at'], 'mistakes: template sync columns');
      const defaults = upgradedMistakes.get(
        `SELECT device_id,local_version,deleted_at FROM custom_anki_templates WHERE id='tpl_s0g_fixture'`,
      );
      assert.deepEqual(defaults, { device_id: null, local_version: 0, deleted_at: null }, 'mistakes: template defaults');
      const set = upgrade.sets.mistakes;
      const history = historyRows(upgradedMistakes);
      const expected = expectedByDatabase.get('mistakes');
      assert.equal(history.length, expected.length, 'mistakes: upgraded history count');
      for (const [index, row] of history.entries()) {
        assert.equal(Number(row.version), expected[index].version, `mistakes: upgraded history version ${index}`);
        assert.equal(row.name, expected[index].name, `mistakes: upgraded history name ${index}`);
        assert.equal(String(row.checksum), expected[index].refineryChecksum, `mistakes: upgraded history checksum ${index}`);
      }
      const fingerprint = computeSchemaFingerprint(upgradedMistakes, set, set.latestVersion());
      const data = databases.find(database => database.id === 'mistakes');
      assert.equal(fingerprint.hash, data.fingerprint, 'mistakes: upgraded fingerprint equals fresh');
      const integrity = checkDatabaseIntegrity(upgradedMistakes);
      assert.equal(integrity.integrity, 'ok', 'mistakes: upgraded integrity');
      assert.equal(integrity.foreignKeyViolations, 0, 'mistakes: upgraded foreign keys');
      data.upgrade = {
        fromVersion: upgradeMap.get('mistakes').fromVersion,
        toVersion: upgradeMap.get('mistakes').toVersion,
        appliedCount: upgradeMap.get('mistakes').appliedCount,
        success: upgradeMap.get('mistakes').success,
        historySha256: sha256(JSON.stringify(history)),
        fingerprintMatchesFresh: true,
        integrity: integrity.integrity,
        foreignKeyViolations: integrity.foreignKeyViolations,
        restartAppliedCount: null,
      };
      preserved.mistakes = {
        table: 'custom_anki_templates',
        oldColumns: snapshot.mistakes.templateColumns,
        addedColumns: added,
        rowSha256: snapshot.mistakes.sha256,
        equal: true,
        defaults,
      };
    } finally {
      upgradedMistakes.close();
    }

    for (const database of ['chat_v2', 'llm_usage']) {
      const path = join(upgradeDirectory, `${database}.db`);
      const connection = new Connection(path, { label: `s0g-upgraded-${database}`, profile: { foreignKeys: true } });
      try {
        const set = upgrade.sets[database];
        const fingerprint = computeSchemaFingerprint(connection, set, set.latestVersion());
        const data = databases.find(item => item.id === database);
        assert.equal(fingerprint.hash, data.fingerprint, `${database}: upgraded fingerprint equals fresh`);
        const integrity = checkDatabaseIntegrity(connection);
        assert.equal(integrity.integrity, 'ok', `${database}: upgraded integrity`);
        assert.equal(integrity.foreignKeyViolations, 0, `${database}: upgraded foreign keys`);
        const history = historyRows(connection);
        assert.equal(sha256(JSON.stringify(history)), data.historySha256, `${database}: upgraded history unchanged`);
        data.upgrade = {
          fromVersion: upgradeMap.get(database).fromVersion,
          toVersion: upgradeMap.get(database).toVersion,
          appliedCount: upgradeMap.get(database).appliedCount,
          success: upgradeMap.get(database).success,
          historySha256: data.historySha256,
          fingerprintMatchesFresh: true,
          integrity: integrity.integrity,
          foreignKeyViolations: integrity.foreignKeyViolations,
          restartAppliedCount: null,
        };
      } finally {
        connection.close();
      }
    }

    const upgradeRestart = await upgrade.runAll();
    assert.equal(upgradeRestart.success, true, 'upgrade restart report');
    const upgradeRestartMap = reportMap(upgradeRestart);
    for (const database of policy.expected.sharedDatabases) {
      const report = upgradeRestartMap.get(database);
      assert.ok(report, `${database}: upgrade restart report`);
      assert.equal(report.success, true, `${database}: upgrade restart success`);
      assert.equal(report.appliedCount, 0, `${database}: upgrade restart applied`);
      databases.find(item => item.id === database).upgrade.restartAppliedCount = report.appliedCount;
    }
    const reopenedVfs = new Connection(oldVfsPath, { label: 's0g-upgrade-restart-vfs', profile: { foreignKeys: true } });
    try {
      const set = upgrade.sets.vfs;
      const fingerprint = computeSchemaFingerprint(reopenedVfs, set, set.latestVersion());
      assert.equal(fingerprint.hash, databases.find(database => database.id === 'vfs').fingerprint,
        'vfs: upgraded fingerprint after restart');
    } finally {
      reopenedVfs.close();
    }

    const classification = await loadDesktop('host/plugins/sync/src/classification.ts');
    const preflight = await loadDesktop('host/plugins/sync/repository/registry-preflight.ts');
    const baseline = readJson('config/sync-registry-contract.json');
    assert.equal(baseline.unit, 'S0.19');
    assert.equal(baseline.registry.length, 124, 'S0.19 baseline registry count');
    const baselineByKey = new Map(baseline.registry.map(entry => [`${entry.database}.${entry.table}`, entry]));
    const live = classification.syncClassificationRegistry();
    const liveByKey = new Map(live.map(entry => [`${entry.database}.${entry.table_name}`, entry]));
    assert.equal(live.length, policy.expected.registry.entries, 'live registry count');
    assert.equal(live.filter(entry => entry.category === 'RowSync').length,
      policy.expected.registry.rowSync, 'live RowSync count');
    const categoryCounts = {};
    for (const entry of live) categoryCounts[entry.category] = (categoryCounts[entry.category] ?? 0) + 1;
    assert.deepEqual(categoryCounts, policy.expected.registry.categoryCounts, 'live category counts');

    const changedEntries = [];
    for (const entry of baseline.registry) {
      const key = `${entry.database}.${entry.table}`;
      const actual = liveByKey.get(key);
      assert.ok(actual, `${key}: baseline entry missing from live registry`);
      assert.equal(actual.primary_key, entry.primaryKey.declared, `${key}: baseline primary key`);
      if (key === 'mistakes.custom_anki_templates') {
        assert.equal(actual.category, 'RowSync', `${key}: template category`);
        assert.equal(actual.conflict_policy, 'Lww', `${key}: template conflict policy`);
        changedEntries.push({ key, from: entry.category, to: actual.category });
      } else {
        assert.equal(actual.category, entry.category, `${key}: baseline category`);
      }
    }
    const expectedNew = new Map([
      ['vfs.portable_media_events', {
        primary_key: 'event_id', category: 'RowSync', conflict_policy: 'Lww',
      }],
      ['vfs.portable_plugin_records', {
        primary_key: 'id', category: 'RowSync', conflict_policy: 'Lww',
      }],
    ]);
    const newEntries = live.filter(entry => !baseline.registry.some(base =>
      base.database === entry.database && base.table === entry.table_name));
    assert.equal(newEntries.length, expectedNew.size, 'new registry entries');
    for (const entry of newEntries) {
      const key = `${entry.database}.${entry.table_name}`;
      const expected = expectedNew.get(key);
      assert.ok(expected, `${key}: unexpected new registry entry`);
      assert.equal(entry.primary_key, expected.primary_key, `${key}: new primary key`);
      assert.equal(entry.category, expected.category, `${key}: new category`);
      assert.equal(entry.conflict_policy, expected.conflict_policy, `${key}: new conflict policy`);
    }

    const connections = new Map();
    for (const database of policy.expected.sharedDatabases) {
      connections.set(database, new Connection(fresh.getDatabasePath(database), {
        label: `s0g-registry-${database}`,
        profile: { foreignKeys: true },
      }));
    }
    const primaryKey = {
      checked: 0,
      virtualTables: [],
      knownExceptions: [],
      violations: [],
    };
    const triggers = {
      rowSyncChecked: 0,
      exemptions: [],
      violations: [],
    };
    try {
      for (const entry of live) {
        const key = `${entry.database}.${entry.table_name}`;
        const connection = connections.get(entry.database);
        assert.ok(connection, `${key}: database ${entry.database}`);
        const exists = Number(connection.pluck(
          "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name=?)", [entry.table_name],
        )) === 1;
        if (entry.category === 'Deprecated') {
          assert.equal(exists, false, `${key}: deprecated table must be absent`);
          continue;
        }
        assert.equal(exists, true, `${key}: table exists`);
        const columns = connection.all(`PRAGMA table_info(${QUOTE(entry.table_name)})`);
        const baselineEntry = baselineByKey.get(key);
        const virtual = baselineEntry?.primaryKey?.virtual === true;
        if (virtual) {
          primaryKey.virtualTables.push(key);
        } else {
          const columnNames = new Set(columns.map(column => column.name));
          const declared = entry.primary_key.split(',').map(value => value.trim()).filter(Boolean);
          for (const column of declared) assert.ok(columnNames.has(column), `${key}: primary key column ${column}`);
          const sqlite = columns.filter(column => Number(column.pk) > 0)
            .sort((left, right) => Number(left.pk) - Number(right.pk))
            .map(column => column.name);
          const matches = JSON.stringify([...declared].sort(byteCompare)) === JSON.stringify([...sqlite].sort(byteCompare));
          primaryKey.checked++;
          if (!matches) {
            if (key === 'vfs.note_document_revisions') {
              primaryKey.knownExceptions.push({ key, declared, sqlite });
            } else {
              primaryKey.violations.push({ key, declared, sqlite });
            }
          }
        }
        const tableTriggers = connection.all(
          `SELECT name,tbl_name,COALESCE(sql,'') AS sql FROM sqlite_master
            WHERE type='trigger' AND tbl_name=? AND COALESCE(sql,'') LIKE '%__change_log%' ORDER BY name`,
          [entry.table_name],
        );
        if (entry.category === 'RowSync') {
          triggers.rowSyncChecked++;
          const operations = new Map();
          for (const trigger of tableTriggers) {
            const lower = trigger.name.replace(/[A-Z]/gu, character => character.toLowerCase());
            const operation = preflight.triggerEvent(trigger.sql)
              ?? ['insert', 'update', 'delete'].find(event => lower.endsWith(`_${event}`));
            if (operation !== undefined && operation !== null) {
              if (!operations.has(operation)) operations.set(operation, []);
              operations.get(operation).push(trigger.name);
            }
          }
          for (const operation of ['insert', 'update', 'delete']) {
            if (classification.changeLogTriggerExempt(entry.database, entry.table_name, operation)) {
              triggers.exemptions.push({ key, operation });
              continue;
            }
            if (operations.get(operation) === undefined) {
              triggers.violations.push({ key, operation, observed: [...operations.keys()] });
            }
          }
        } else {
          for (const trigger of tableTriggers) triggers.violations.push({ key, stray: trigger.name });
        }
      }
    } finally {
      for (const connection of connections.values()) connection.close();
    }
    assert.deepEqual(primaryKey.violations, [], 'primary key classification violations');
    assert.deepEqual(triggers.violations, [], 'trigger classification violations');

    return {
      desktop: desktopFreeze,
      increment,
      sql: {
        upstreamCount: policy.expected.upstreamMigrations,
        coordinatedCount: policy.expected.coordinatedMigrations,
        total: sqlFiles.length,
        files: sqlFiles,
        setSha256: sha256(sqlFiles.map(file =>
          `${file.database}\0${file.version}\0${file.name}\0${file.sha256}\0${file.refineryChecksum}\n`).join('')),
        finalPrepared: { count: finalPrepared.count, setSha256: finalPrepared.setSha256 },
        prepared,
        order: sqlValidation.order,
        duplicateVersions: [],
        staleVersions: [],
      },
      databases,
      browser,
      registry: {
        count: live.length,
        rowSync: live.filter(entry => entry.category === 'RowSync').length,
        categoryCounts,
        productionOrderSha256: sha256(JSON.stringify(live.map(entry => `${entry.database}.${entry.table_name}`))),
        changedEntries,
        newEntries: newEntries.map(entry => ({
          key: `${entry.database}.${entry.table_name}`,
          primaryKey: entry.primary_key,
          category: entry.category,
          conflictPolicy: entry.conflict_policy,
        })),
        primaryKey,
        triggers,
      },
      preserved,
    };
  } finally {
    for (const handle of handles.reverse()) {
      try { handle.unregister(); } catch {}
    }
    rmSync(temp, { recursive: true, force: true });
  }
}

export async function run(mode, options = {}) {
  assert.ok(['capture', 'verify', 'draft', 'self-test'].includes(mode), 'usage: stage-gate capture|verify|draft|self-test');
  const policy = loadPolicy();
  const allowPendingS025 = mode === 'draft' || mode === 'self-test';
  const units = collectUnits(policy, allowPendingS025);
  const assembly = await runAssembly();
  const plan = executionPlan();
  const planApi = plan === null ? null : await import('./execution-plan.mjs');
  const planContext = planApi === null ? null : executionPlanContext(planApi);
  const executionPlanState = executionPlanBinding(policy, plan, planApi, planContext);
  const controls = await negativeControls(policy, assembly.sql, plan, planApi, planContext);
  if (parsedForwardDependency(controls)) {
    throw new Error('forward-dependency negative control failed');
  }
  const lockStatus = mode === 'draft' ? 'draft' : 'validated';
  const bindingValue = binding(policy, assembly, units, controls, lockStatus, executionPlanState, planContext);

  if (mode === 'self-test') {
    const synthetic = structuredClone(bindingValue);
    synthetic.status = 'validated';
    synthetic.pending = [];
    synthetic.units = synthetic.units.map(unit => ({ ...unit, status: 'complete', testEvidence: true }));
    const syntheticBytes = Buffer.from(`${JSON.stringify(synthetic, null, 2)}\n`);
    const receipt = {
      formatVersion: policy.mainReview.formatVersion,
      format: policy.mainReview.format,
      unit: UNIT,
      decision: 'accepted',
      signer: policy.mainReview.signer,
      signedAt: '2026-11-16T00:00:00+08:00',
      policy: { file: GATE_CONFIG, sha256: sha256(readFileSync(resolve(root, GATE_CONFIG))) },
      finalLock: { file: FINAL_LOCK, sha256: sha256(syntheticBytes) },
      assembly: {
        sqlSetSha256: synthetic.sql.setSha256,
        databases: Object.fromEntries(synthetic.databases.map(database => [
          database.id, { version: database.version, fingerprint: database.fingerprint },
        ])),
      },
      registry: { entries: synthetic.registry.count, rowSync: synthetic.registry.rowSync },
      units: {
        count: policy.mainReview.requiredUnits,
        allEvidence: true,
        units: synthetic.units.map(unit => unit.unit),
      },
      m0: { complete: true, signedOff: true },
    };
    const accepted = validateMainReviewReceipt(policy, synthetic, syntheticBytes, receipt);
    const rejects = fn => {
      try { fn(); return false; } catch { return true; }
    };
    const tamperedReceipt = structuredClone(receipt);
    tamperedReceipt.finalLock.sha256 = '0'.repeat(64);
    const missingEvidence = structuredClone(synthetic);
    missingEvidence.units[0].testEvidence = false;
    const summary = {
      formatVersion: 1,
      unit: UNIT,
      status: 'self-test',
      sql: {
        upstreamCount: assembly.sql.upstreamCount,
        coordinatedCount: assembly.sql.coordinatedCount,
        total: assembly.sql.total,
        setSha256: assembly.sql.setSha256,
        prepared: assembly.sql.prepared,
      },
      databases: assembly.databases.map(database => ({
        id: database.id,
        version: database.version,
        migrationCount: database.migrationCount,
        fingerprint: database.fingerprint,
        managedSchema: {
          tables: database.managedSchema.tables,
          indexes: database.managedSchema.indexes,
          triggers: database.managedSchema.triggers,
          scopeSha256: database.managedSchema.scopeSha256,
        },
        fresh: database.fresh,
        upgrade: database.upgrade,
      })),
      registry: {
        count: assembly.registry.count,
        rowSync: assembly.registry.rowSync,
        categoryCounts: assembly.registry.categoryCounts,
        primaryKey: assembly.registry.primaryKey,
        triggers: assembly.registry.triggers,
      },
      executionPlan: bindingValue.executionPlan,
      deferred: bindingValue.deferred,
      preserved: assembly.preserved,
      browser: assembly.browser,
      negativeControls: controls,
      mainReviewTest: {
        accepted: accepted.signed === true && accepted.m0Complete === true,
        tamperedFinalLockRejected: rejects(() =>
          validateMainReviewReceipt(policy, synthetic, syntheticBytes, tamperedReceipt)),
        missingUnitEvidenceRejected: rejects(() =>
          validateMainReviewReceipt(policy, missingEvidence, syntheticBytes, receipt)),
      },
      unitEvidenceFailures: units.failureDetails,
      pendingUnits: units.pending,
      m0: collectM0(policy, 'blocked', units.pending),
    };
    log('s0-g.self-test', summary);
    return summary;
  }

  if (mode === 'capture' || mode === 'draft') {
    if (mode === 'capture' && plan === null) {
      throw new Error('S0.G capture blocked: config/execution-plan.json is missing');
    }
    if (mode === 'capture' && executionPlanState.freezeReady !== true) {
      throw new Error(`S0.G capture blocked: S0.25 plan is not freeze-ready: ${executionPlanState.freezeIssues.join('; ')}`);
    }
    if (units.blockingMissing.length > 0 || units.blockingEvidence.length > 0) {
      const missing = JSON.stringify({
        missing: units.blockingMissing,
        missingEvidence: units.blockingEvidence,
        evidenceFailures: units.failureDetails,
      });
      throw new Error(`S0.G ${mode} blocked by unresolved unit evidence: ${missing}`);
    }
    if (mode === 'capture' && units.pending.length > 0) {
      throw new Error(`S0.G capture blocked: pending units ${units.pending.join(', ')}`);
    }
    saveJson(FINAL_LOCK, bindingValue);
    log('s0-g.captured', {
      mode,
      status: bindingValue.status,
      sql: bindingValue.sql.total,
      databases: bindingValue.databases.map(database => ({
        id: database.id, version: database.version, migrations: database.migrationCount,
      })),
      registry: { count: bindingValue.registry.count, rowSync: bindingValue.registry.rowSync },
      pending: bindingValue.pending,
      m0: bindingValue.m0,
    });
    return bindingValue;
  }

  const saved = readJson(FINAL_LOCK);
  if (saved.status !== 'validated') {
    throw new Error(
      `S0.G verify blocked: final-lock status is ${saved.status}; pending ${JSON.stringify(saved.pending ?? [])}; run capture after S0.25 is stable`,
    );
  }
  if (saved.executionPlan?.freezeReady !== true) {
    throw new Error(`S0.G verify blocked: S0.25 plan is not freeze-ready: ${(saved.executionPlan?.freezeIssues ?? []).join('; ')}`);
  }
  const drift = difference(saved, bindingValue);
  if (drift !== null) {
    log('s0-g.drift', drift);
    throw new Error(`S0.G final-lock drift at ${drift.path}`);
  }
  const review = mainReviewState(policy, saved);
  const requireSigned = options.requireSigned === true || process.env.S0_G_REQUIRE_SIGNED === '1';
  if (requireSigned && !review.signed) {
    throw new Error(`S0.G signed verification required but main-review receipt is ${review.status}`);
  }
  log(review.signed ? 's0-g.verified.signed' : 's0-g.verified.unsigned', {
    unit: UNIT,
    sql: saved.sql.total,
    databases: saved.databases.map(database => ({
      id: database.id, version: database.version, migrations: database.migrationCount,
    })),
    registry: { count: saved.registry.count, rowSync: saved.registry.rowSync },
    units: saved.units.length,
    deferred: saved.deferred.items.length,
    negativeControls: saved.negativeControls,
    m0: saved.m0,
    mainReview: review,
  });
  return {
    status: 'verified',
    signed: review.signed,
    m0Complete: review.m0Complete,
    mainReview: review,
    lock: saved,
  };
}

function parsedForwardDependency(controls) {
  return controls.some(control => control.case === 'forward-dependency' && control.status === 'failed');
}

const invoked = process.argv[1] !== undefined
  && pathToFileURL(resolve(process.argv[1])).href === import.meta.url;
if (invoked) {
  const mode = process.argv[2];
  if (mode === '--worker') {
    const resultFile = process.argv[3];
    assert.ok(resultFile, 'worker result file');
    runWorker(resultFile).then(value => {
      writeFileSync(resultFile, JSON.stringify(value));
    }).catch(error => {
      const message = error instanceof Error ? error.message : String(error);
      log('s0-g.worker.failed', { message, stack: error instanceof Error ? error.stack : null });
      try { writeFileSync(resultFile, JSON.stringify({ error: message })); } catch {}
      process.exitCode = 1;
    });
  } else {
    run(mode).catch(error => {
      log('s0-g.failed', { mode, message: error.message, stack: error.stack });
      process.exitCode = 1;
    });
  }
}
