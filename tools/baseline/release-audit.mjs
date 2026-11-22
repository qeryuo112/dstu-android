import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { log, main, readJson, root, sha256 } from './lib.mjs';
import { releaseVersion } from './version.mjs';

const BASELINE = { vfs: 20261115, mistakes: 20261005 };
const TARGET = { vfs: 20261117, mistakes: 20261006 };
const DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const DESKTOP_TREE = '3bee0f058851b9eb113bdc036d9f60433ed29944';
const FSRS_COLUMNS = [
  'fsrs_stability', 'fsrs_difficulty', 'fsrs_phase', 'fsrs_learning_step',
  'fsrs_lapses', 'fsrs_due_at', 'fsrs_last_review_at',
];
const REQUIRED_DELIVERED_TARGETS = [
  { unit: 'S0.21', id: 'portable-anki-templates', database: 'mistakes', version: 20261006 },
  { unit: 'S0.22', id: 'portable-media-progress', database: 'vfs', version: 20261116 },
  { unit: 'S0.24', id: 'plugin-skill-data-contract', database: 'vfs', version: 20261117 },
];

function coordinatedFiles(directory = resolve(root, 'config/schema/coordinated'), prefix = '') {
  if (!existsSync(directory)) return [];
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const relative = prefix === '' ? entry.name : `${prefix}/${entry.name}`;
    const full = resolve(directory, entry.name);
    if (entry.isDirectory()) files.push(...coordinatedFiles(full, relative));
    else if (entry.isFile() && entry.name.endsWith('.sql')) files.push(relative);
  }
  return files.sort();
}

function parseSqlPath(path) {
  const match = /^(vfs|mistakes)\/V(\d{8})__([A-Za-z0-9_]+)\.sql$/.exec(path);
  assert.ok(match, `unexpected coordinated SQL path: ${path}`);
  return { database: match[1], version: Number(match[2]), name: match[3], path };
}

function deliveryOf(entry) {
  const targetOnDisk = entry.targetPath !== null
    && existsSync(resolve(root, 'config/schema/coordinated', entry.targetPath));
  const stalePath = entry.previousReservation?.path ?? null;
  const staleOnDisk = stalePath !== null && stalePath !== entry.targetPath
    && existsSync(resolve(root, 'config/schema/coordinated', stalePath));
  const targetSha256 = targetOnDisk
    ? sha256(readFileSync(resolve(root, 'config/schema/coordinated', entry.targetPath)))
    : null;

  if (entry.schemaContract === 'not-required') {
    assert.equal(entry.targetPath, null, `${entry.unit}/${entry.id}: not-required must not declare a target SQL`);
    assert.equal(targetOnDisk, false, `${entry.unit}/${entry.id}: not-required target exists`);
    assert.equal(staleOnDisk, false, `${entry.unit}/${entry.id}: superseded placeholder exists`);
    return { ...entry, observedStatus: 'not-required', targetOnDisk, staleOnDisk, targetSha256 };
  }

  if (entry.schemaContract === 'delivered') {
    assert.equal(targetOnDisk, true, `${entry.unit}/${entry.id}: delivered target missing`);
    assert.equal(staleOnDisk, false, `${entry.unit}/${entry.id}: delivered target still has a stale reservation`);
    return { ...entry, observedStatus: 'delivered', targetOnDisk, staleOnDisk, targetSha256 };
  }

  assert.equal(entry.schemaContract, 'target-fixed', `${entry.unit}/${entry.id}: unsupported schema contract`);
  if (targetOnDisk) {
    assert.equal(staleOnDisk, false, `${entry.unit}/${entry.id}: target and stale SQL coexist; rebase must not leave both`);
    return { ...entry, observedStatus: 'delivered', targetOnDisk, staleOnDisk, targetSha256 };
  }
  if (staleOnDisk) {
    return { ...entry, observedStatus: 'pending-rebase', targetOnDisk, staleOnDisk, targetSha256 };
  }
  return { ...entry, observedStatus: 'pending-delivery', targetOnDisk, staleOnDisk, targetSha256 };
}

function contractOf(entry) {
  return {
    unit: entry.unit, id: entry.id, database: entry.database,
    sourceContract: entry.sourceContract, schemaContract: entry.schemaContract,
    schemaRequired: entry.schemaRequired, targetVersion: entry.targetVersion,
    targetPath: entry.targetPath, previousReservation: entry.previousReservation,
    reservationDisposition: entry.reservationDisposition, assembly: entry.assembly,
    reason: entry.reason,
  };
}

export async function run(mode = 'verify') {
  assert.equal(mode, 'verify', 'release-audit only has a real verify mode');
  const window = readJson('config/sync-release-window.json');
  const schema = readJson('config/baseline/schema.json');
  const protocol = readJson('config/baseline/protocol.json');
  const registry = readJson('config/sync-registry-contract.json');
  const release = readJson('config/release-contract.json');
  const syncFormats = readJson('config/baseline/sync-formats.json');

  assert.equal(window.unit, 'S0.20');
  assert.equal(window.formatVersion, 2);
  assert.equal(window.capture.state, 'validated-source-contract');
  assert.equal(window.capture.reviewPlansSyncParsed, true);
  assert.equal(window.capture.sourceContractState, 'ready');
  assert.equal(window.capture.reviewPlanParity.syncParsed, true);
  assert.equal(window.capture.reviewPlanParity.sourceMatchesTarget, true);
  assert.deepEqual(window.capture.reviewPlanParity.fsrsColumns, FSRS_COLUMNS);
  assert.equal(window.capture.reviewPlanParity.unknownTarget.valuesRetained, true);
  assert.equal(window.capture.reviewPlanParity.unknownTarget.sourceTargetCanonicalEqual, true);
  assert.equal(window.sources.desktop.commit, DESKTOP_COMMIT);
  assert.equal(window.sources.desktop.tree, DESKTOP_TREE);
  assert.equal(window.sources.desktop.frozen, true);
  assert.equal(window.sources.desktop.releaseClaimed, false);
  assert.equal(window.application.desktopReleaseClaimed, false);
  assert.deepEqual(window.claims, {
    kotlinHostImplemented: false,
    hostInstallationComplete: false,
    desktopReleasePublished: false,
    s0gFinalAssemblyComplete: false,
  });
  assert.equal(window.schema.nativeBaseline.vfs, BASELINE.vfs);
  assert.equal(window.schema.nativeBaseline.mistakes, BASELINE.mistakes);
  assert.equal(window.schema.nativeBaseline.historicalSqlFiles, 135);
  assert.equal(schema.migrations.length, 135);
  assert.equal(schema.databases.find(item => item.id === 'vfs').version, BASELINE.vfs);
  assert.equal(schema.databases.find(item => item.id === 'mistakes').version, BASELINE.mistakes);
  assert.equal(window.schema.incrementTarget.vfs, TARGET.vfs);
  assert.equal(window.schema.incrementTarget.mistakes, TARGET.mistakes);
  assert.equal(window.schema.incrementTarget.separate, true);

  const entries = window.migrationPlan.entries;
  assert.equal(entries.length, 5);
  assert.equal(window.migrationPlan.status, 'target-contract-fixed');
  assert.equal(entries.filter(item => item.sourceContract === 'ready').length, 5);
  assert.equal(entries.every(item => item.assembly === 'S0.G'), true);
  assert.equal(entries.every(item => !('sha256' in item) && !('observedStatus' in item)), true,
    'release-window must not embed directory delivery state');
  assert.equal(entries.filter(item => item.schemaContract === 'delivered').length, 1);
  assert.equal(entries.filter(item => item.schemaContract === 'not-required').length, 2);
  assert.equal(entries.filter(item => item.schemaContract === 'target-fixed').length, 2);
  assert.equal(entries.filter(item => item.reservationDisposition === 'superseded').length, 3);

  const template = entries.find(item => item.id === 'portable-anki-templates');
  assert.equal(template.database, 'mistakes');
  assert.equal(template.targetVersion, 20261006);
  assert.equal(template.schemaContract, 'delivered');
  const attachment = entries.find(item => item.id === 'portable-attachment-roots');
  assert.equal(attachment.schemaContract, 'not-required');
  assert.equal(attachment.targetVersion, null);
  const media = entries.find(item => item.id === 'portable-media-progress');
  assert.equal(media.targetVersion, 20261116);
  assert.equal(media.previousReservation.version, 20261114);
  assert.ok(media.previousReservation.version < BASELINE.vfs);
  assert.equal(media.schemaContract, 'target-fixed');
  const theme = entries.find(item => item.id === 'portable-theme-configuration');
  assert.equal(theme.schemaContract, 'not-required');
  assert.equal(theme.previousReservation.version, BASELINE.vfs);
  const plugin = entries.find(item => item.id === 'plugin-skill-data-contract');
  assert.equal(plugin.targetVersion, 20261117);
  assert.equal(plugin.previousReservation.version, 20261116);
  assert.equal(plugin.schemaContract, 'target-fixed');

  assert.equal(protocol.schemaMatrix.find(item => item.database === 'vfs').schemaVersion, BASELINE.vfs);
  assert.equal(protocol.schemaMatrix.find(item => item.database === 'mistakes').schemaVersion, BASELINE.mistakes);
  assert.equal(registry.unit, 'S0.19');
  const reviewPlanRegistration = registry.registry.find(item => item.database === 'vfs' && item.table === 'review_plans');
  assert.ok(reviewPlanRegistration, 'S0.19 review_plans registration');
  assert.equal(reviewPlanRegistration.category, 'RowSync');
  assert.equal(reviewPlanRegistration.scope.incrementalSync, true);
  assert.equal(reviewPlanRegistration.triggers.allRequiredPresent, true);
  assert.deepEqual(reviewPlanRegistration.schema.foreignKeys.map(fk => [fk.from, fk.table, fk.on_delete]),
    [['exam_id', 'exam_sheets', 'NO ACTION'], ['question_id', 'questions', 'CASCADE']]);
  assert.deepEqual(registry.reviewPlans.columns, FSRS_COLUMNS);
  assert.equal(registry.reviewPlans.sync.wire.parsedEquals, true);
  assert.ok(registry.reviewPlans.sync.wire.bytes > 0);
  assert.equal(registry.reviewPlans.sync.changes, 2);
  assert.equal(registry.reviewPlans.sync.operation, 'Update');
  assert.deepEqual(registry.reviewPlans.sync.source, registry.reviewPlans.sync.target);
  assert.deepEqual(registry.reviewPlans.sync.source.fsrs, {
    stability: 2.30649995803833,
    difficulty: 2.1181039810180664,
    phase: 'learning',
    learning_step: 1,
    lapses: 0,
    due_at: 1794715800000,
    last_review_at: 1794715200000,
  });
  const unknownTarget = registry.reviewPlans.sync.unknownFields;
  assert.deepEqual(unknownTarget.targetRecord, unknownTarget.parsed);
  assert.deepEqual(unknownTarget.targetVault, unknownTarget.seededBeforeSync);
  assert.equal(unknownTarget.valuesRetained, true);
  assert.equal(unknownTarget.sourceCanonicalSha256, unknownTarget.targetCanonicalSha256);
  assert.equal(window.capture.reviewPlanParity.unknownTarget.canonicalSha256, unknownTarget.targetCanonicalSha256);
  assert.deepEqual(window.capture.reviewPlanParity.unknownTarget.targetVault, unknownTarget.targetVault);
  assert.equal(release.version.initial.versionCode, releaseVersion(release.version.initial.version).versionCode);
  assert.equal(release.releasePackage.observed, false);
  assert.equal(syncFormats.unit, 'S0.18');
  assert.equal(syncFormats.formatVersion, 2);
  for (const item of syncFormats.artifacts) {
    assert.equal(item.sha256, sha256(readFileSync(resolve(root, item.file))), `${item.file}: S0.18 hash`);
  }

  assert.equal(window.wire.current, 4);
  assert.equal(JSON.parse(protocol.versionWindow.formatBytes.normalizedText).format_version, 4);
  assert.equal(protocol.versionWindow.formatBytes.sha256,
    sha256(protocol.versionWindow.formatBytes.normalizedText));
  assert.equal(window.wire.minimumReader, 3);
  assert.equal(protocol.versionWindow.minCompatible, 3);
  assert.equal(protocol.versionWindow.v5.registered, false);
  assert.equal(window.wire.v5.registered, false);
  for (const row of protocol.schemaMatrix) {
    const expected = schema.databases.find(database => database.id === row.database);
    assert.ok(expected, `${row.database}: schema baseline`);
    assert.equal(row.schemaVersion, expected.version, `${row.database}: schema version`);
    assert.equal(row.fingerprint, expected.fingerprint, `${row.database}: fingerprint`);
  }
  assert.equal(window.checks.schemaFingerprintsMatchBaseline, true);
  assert.equal(window.checks.formatCurrent, 4);
  assert.equal(window.checks.formatMinimum, 3);
  assert.equal(window.checks.formatNegativeCases, 2);
  assert.equal(window.checks.uploadGateNegativeCases, 3);
  assert.equal(window.checks.leaseAcquireRelease, true);
  assert.equal(window.checks.laterUnequalSchemaDetected, true);
  assert.equal(window.checks.legacyMobileWebdavOnly, true);
  assert.equal(window.checks.nativeAndroidProvidersClaimed, 0);
  assert.equal(window.lease.format_version, 1);
  assert.equal(window.lease.target, 'data_governance');
  assert.equal(window.lease.released, true);
  assert.equal(window.schemaGates.filter(item => !item.accepted).length, 3);
  assert.equal(window.schemaGates.every(item => item.writesDuringGate === 0), true);

  const replay = protocol.scenarios;
  assert.equal(replay.sameSchema.passed, true);
  assert.equal(replay.mismatch.rejection.passed, true);
  assert.equal(replay.mismatch.rejection.publicationWriteCount, 0);
  assert.deepEqual(replay.mismatch.rejection.publicationWrites, []);
  assert.deepEqual(replay.mismatch.rejection.publicationDeletes, []);
  assert.deepEqual(replay.mismatch.rejection.cloudKeysChanged, []);
  assert.equal(replay.mismatch.rejection.pendingUnchanged, true);
  assert.equal(replay.mismatch.rejection.localRowsUnchanged, true);
  assert.equal(replay.mismatch.upgrade.passed, true);
  assert.equal(replay.mismatch.upgrade.originalPendingCarried, true);
  assert.equal(replay.mismatch.upgrade.sameDirectorySameDeviceId, true);
  assert.equal(replay.mismatch.upgrade.dataReinserted, false);
  assert.equal(window.frozenEvidence.mismatch.publicationWriteCount, 0);
  assert.equal(window.frozenEvidence.mismatch.pendingUnchanged, true);
  assert.equal(window.frozenEvidence.mismatch.originalPendingCarried, true);
  assert.equal(window.frozenEvidence.mismatch.rejectionPassed, true);
  assert.equal(window.frozenEvidence.mismatch.upgradePassed, true);

  const allowed = new Set();
  for (const item of entries) {
    if (item.targetPath !== null) allowed.add(item.targetPath);
    if (item.previousReservation?.path) allowed.add(item.previousReservation.path);
  }
  const diskFiles = coordinatedFiles();
  const unexpected = diskFiles.filter(file => !allowed.has(file));
  assert.deepEqual(unexpected, [], 'unexpected coordinated SQL not covered by S0.20 contract');

  const versions = diskFiles.map(parseSqlPath)
    .filter(item => item.version > BASELINE[item.database]);
  assert.equal(new Set(versions.map(item => `${item.database}:${item.version}`)).size, versions.length,
    'duplicate coordinated version for a database');

  const deliveries = entries.map(deliveryOf);
  const counts = status => deliveries.filter(item => item.observedStatus === status).length;
  assert.equal(deliveries.find(item => item.id === 'portable-anki-templates').observedStatus, 'delivered');
  assert.equal(deliveries.find(item => item.id === 'portable-media-progress').observedStatus, 'delivered');
  assert.equal(deliveries.find(item => item.id === 'plugin-skill-data-contract').observedStatus, 'delivered');
  assert.equal(deliveries.find(item => item.id === 'portable-attachment-roots').observedStatus, 'not-required');
  assert.equal(deliveries.find(item => item.id === 'portable-theme-configuration').observedStatus, 'not-required');
  assert.equal(counts('pending-rebase'), 0, 'final S0.20 verify must not accept a pending rebase');
  assert.equal(counts('pending-delivery'), 0, 'final S0.20 verify must not accept a missing delivery');

  const targetFiles = new Set(deliveries.filter(item => item.targetPath !== null).map(item => item.targetPath));
  const staleFiles = diskFiles.filter(file => !targetFiles.has(file));
  const staleReservations = new Set(deliveries.map(item => item.previousReservation?.path).filter(Boolean));
  assert.equal(staleFiles.every(file => staleReservations.has(file)), true,
    'stale coordinated SQL must be a declared previous reservation');
  assert.deepEqual(staleFiles, [], 'final S0.20 verify must not leave stale superseded SQL');

  const deliveredTargets = deliveries
    .filter(item => item.observedStatus === 'delivered')
    .map(item => `${item.database}:${item.targetVersion}`);
  const requiredTargets = REQUIRED_DELIVERED_TARGETS.map(item => `${item.database}:${item.version}`);
  const all3Delivered = requiredTargets.every(target => deliveredTargets.includes(target));
  assert.equal(all3Delivered, true, 'S0.20 final gate requires all three coordinated targets delivered');

  const sourceContractReady = entries.every(item => item.sourceContract === 'ready');
  const fsrsValidated = window.capture.state === 'validated-source-contract'
    && window.capture.reviewPlansSyncParsed === true
    && window.capture.reviewPlanParity.syncParsed === true;
  const deliveryComplete = counts('delivered') === 3 && counts('not-required') === 2
    && counts('pending-rebase') === 0 && counts('pending-delivery') === 0;
  const freezeEligible = sourceContractReady && fsrsValidated && deliveryComplete
    && staleFiles.length === 0 && unexpected.length === 0;
  assert.equal(freezeEligible, true,
    'freezeEligible must derive from the validated source contract and actual delivery status');

  const contracts = entries.map(contractOf);
  const deliveryReport = deliveries.map(item => ({
    unit: item.unit, id: item.id, database: item.database,
    observedStatus: item.observedStatus, targetVersion: item.targetVersion,
    targetPath: item.targetPath, targetOnDisk: item.targetOnDisk,
    staleOnDisk: item.staleOnDisk, targetSha256: item.targetSha256,
  }));

  const report = {
    unit: 'S0.20',
    mode,
    nativeBaseline: BASELINE,
    incrementTarget: TARGET,
    sourceContract: {
      state: window.capture.state,
      ready: sourceContractReady,
      fsrsValidated,
      reviewPlansSyncParsed: window.capture.reviewPlansSyncParsed,
      fsrsColumns: registry.reviewPlans.columns,
      unknownTarget: {
        fields: registry.reviewPlans.sync.unknownFields.seededBeforeSync,
        targetRecord: registry.reviewPlans.sync.unknownFields.targetRecord,
        targetVault: registry.reviewPlans.sync.unknownFields.targetVault,
        valuesRetained: registry.reviewPlans.sync.unknownFields.valuesRetained,
        canonicalSha256: registry.reviewPlans.sync.unknownFields.targetCanonicalSha256,
      },
      contracts,
    },
    delivery: {
      diskFiles,
      staleFiles,
      delivered: counts('delivered'),
      pendingRebase: counts('pending-rebase'),
      pendingDelivery: counts('pending-delivery'),
      notRequired: counts('not-required'),
      supersededReservations: deliveries.filter(item => item.reservationDisposition === 'superseded').length,
      deliveries: deliveryReport,
    },
    freezeEligible,
    finalGate: { requiredTargets, deliveredTargets, all3Delivered },
    claims: window.claims,
    wire: {
      current: protocol.versionWindow.current,
      minimumReader: protocol.versionWindow.minCompatible,
      formatSha256: protocol.versionWindow.formatBytes.sha256,
      v5Registered: protocol.versionWindow.v5.registered,
    },
    schemaFingerprintsMatchBaseline: protocol.schemaMatrix.every(row => {
      const expected = schema.databases.find(database => database.id === row.database);
      return row.schemaVersion === expected.version && row.fingerprint === expected.fingerprint;
    }),
    publicationRejection: {
      publicationWriteCount: replay.mismatch.rejection.publicationWriteCount,
      cloudKeysChanged: replay.mismatch.rejection.cloudKeysChanged.length,
      pendingUnchanged: replay.mismatch.rejection.pendingUnchanged,
      localRowsUnchanged: replay.mismatch.rejection.localRowsUnchanged,
    },
  };
  log('release-audit.delivery', report);
  log('release-audit.accepted', {
    unit: 'S0.20',
    mode,
    freezeEligible,
    sharedDatabases: protocol.schemaMatrix.length,
    schemaWireSeparatedFromAppVersion: true,
    sameDevicePendingUpgradeReplay: replay.mismatch.upgrade.passed,
    inactiveV5: protocol.versionWindow.v5.registered === false,
    nativeBaselineVfs: BASELINE.vfs,
    vfsIncrementTarget: TARGET.vfs,
    mistakesIncrementTarget: TARGET.mistakes,
    delivered: counts('delivered'),
    pendingRebase: counts('pending-rebase'),
    pendingDelivery: counts('pending-delivery'),
    notRequired: counts('not-required'),
    supersededReservations: report.delivery.supersededReservations,
    all3Delivered,
    wireCurrent: report.wire.current,
    wireMinimumReader: report.wire.minimumReader,
    schemaFingerprintsMatchBaseline: report.schemaFingerprintsMatchBaseline,
    publicationWriteCount: report.publicationRejection.publicationWriteCount,
    publicationCloudKeysChanged: report.publicationRejection.cloudKeysChanged,
    reviewPlansSyncParsed: window.capture.reviewPlansSyncParsed,
    kotlinHostImplemented: window.claims.kotlinHostImplemented,
    hostInstallationComplete: window.claims.hostInstallationComplete,
    desktopReleasePublished: window.claims.desktopReleasePublished,
  });
  return report;
}

main(import.meta.url, run);
