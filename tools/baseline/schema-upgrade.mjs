import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { desktop, log, root, sha256 } from './lib.mjs';

const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const historyDir = resolve(root, 'config/schema/history');
const upstreamCommit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const previousVersion = 20261111;
const currentVersion = 20261115;
const previousVfsFingerprint = 'b807a28417167a8e62fb7378b237e671b640e54dea63e077b8641a2bec84e0a6';
const dueToday = '2026-11-16';
const newColumnNames = [
  'fsrs_stability',
  'fsrs_difficulty',
  'fsrs_phase',
  'fsrs_learning_step',
  'fsrs_lapses',
  'fsrs_due_at',
  'fsrs_last_review_at',
];

function reportSummary(report) {
  return {
    success: report.success,
    databases: report.databases.map(database => ({
      id: database.id,
      fromVersion: database.fromVersion,
      toVersion: database.toVersion,
      appliedCount: database.appliedCount,
      success: database.success,
    })),
  };
}

function historyRows(conn) {
  return conn.all('SELECT version, name, checksum FROM refinery_schema_history ORDER BY version');
}

function seedReviewPlans(conn) {
  const createdAt = '2026-10-20T08:00:00Z';
  conn.run(
    `INSERT INTO exam_sheets (
       id, exam_name, status, temp_id, metadata_json, preview_json, created_at, updated_at
     ) VALUES (?, ?, 'completed', ?, '{}', '{}', ?, ?)`,
    ['exam_fsrs_fixture', 'FSRS migration fixture', 'temp_fsrs_fixture', createdAt, createdAt],
  );
  conn.run(
    `INSERT INTO questions (
       id, exam_id, question_label, content, answer, created_at, updated_at
     ) VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ['q_due_fixture', 'exam_fsrs_fixture', '1', 'Due question', 'A', createdAt, createdAt],
  );
  conn.run(
    `INSERT INTO questions (
       id, exam_id, question_label, content, answer, created_at, updated_at
     ) VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ['q_future_fixture', 'exam_fsrs_fixture', '2', 'Future question', 'B', createdAt, createdAt],
  );
  conn.run(
    `INSERT INTO review_plans (
       id, question_id, exam_id, ease_factor, interval_days, repetitions,
       next_review_date, last_review_date, status, total_reviews, total_correct,
       consecutive_failures, is_difficult, created_at, updated_at,
       device_id, local_version, deleted_at
     ) VALUES (?, ?, ?, 2.3, 6, 3, ?, ?, 'reviewing', 5, 4, 0, 1, ?, ?, ?, 7, NULL)`,
    ['rp_due_fixture', 'q_due_fixture', 'exam_fsrs_fixture', '2026-11-01', '2026-10-26', createdAt, createdAt, 'upgrade-device'],
  );
  conn.run(
    `INSERT INTO review_plans (
       id, question_id, exam_id, ease_factor, interval_days, repetitions,
       next_review_date, last_review_date, status, total_reviews, total_correct,
       consecutive_failures, is_difficult, created_at, updated_at,
       device_id, local_version, deleted_at
     ) VALUES (?, ?, ?, 2.5, 30, 8, ?, ?, 'graduated', 9, 8, 0, 0, ?, ?, ?, 3, NULL)`,
    ['rp_future_fixture', 'q_future_fixture', 'exam_fsrs_fixture', '2099-12-31', '2026-10-01', createdAt, createdAt, 'upgrade-device'],
  );
}

export async function collect(schema) {
  const { Connection } = await load('host/platform/storage/connection.ts');
  const { loadSqlMigrations, Runner, refineryChecksum } = await load('host/platform/storage/migration/refinery.ts');
  const { MigrationSet } = await load('host/platform/storage/migration/definitions.ts');
  const { computeSchemaFingerprint } = await load('host/platform/storage/migration/fingerprint.ts');
  const { MigrationCoordinator } = await load('host/platform/storage/migration/coordinator.ts');
  const moduleLog = (level, message, fields) => log('schema.upgrade.desktop', { level, message, fields });

  const list = loadSqlMigrations(resolve(historyDir, 'vfs'));
  assert.equal(list.length, 73, 'vfs migration chain contains 72 frozen migrations plus upstream V20261115');
  const set = new MigrationSet('vfs', list.map(migration => ({
    refineryVersion: migration.version,
    name: migration.name,
    sql: migration.sql,
    expectedTables: [],
    expectedColumns: [],
    expectedIndexes: [],
    expectedQueries: [],
    idempotent: false,
  })));
  assert.equal(set.latestVersion(), currentVersion);
  const migration = list.find(item => item.version === currentVersion);
  assert.ok(migration);
  assert.equal(migration.name, 'review_plans_fsrs');

  const schemaVfs = schema.databases.find(database => database.id === 'vfs');
  const registered = schema.migrations.find(item => item.version === currentVersion && item.database === 'vfs');
  assert.ok(schemaVfs && registered);
  assert.equal(registered.name, 'review_plans_fsrs');
  assert.equal(registered.sha256, schema.historyFreeze.appended.sha256);
  assert.equal(registered.refineryChecksum, refineryChecksum(migration.name, migration.version, migration.sql).toString());

  const temp = mkdtempSync(resolve(root, '.baseline-schema-upgrade-'));
  const active = resolve(temp, 'active');
  const vfsPath = resolve(active, 'databases', 'vfs.db');
  let beforePlan;
  let beforeColumns;
  let beforeHistory;
  let beforeFingerprint;
  try {
    mkdirSync(resolve(active, 'databases'), { recursive: true });
    const source = new Connection(vfsPath, { label: 'vfs-upgrade-source', profile: { foreignKeys: true } });
    try {
      const applied = new Runner(list, { target: { kind: 'Version', version: previousVersion }, log: moduleLog }).run(source);
      assert.equal(applied.length, 72, 'old fixture starts at the frozen vfs chain through V20261111');
      seedReviewPlans(source);
      beforeColumns = source.all("PRAGMA table_info('review_plans')");
      assert.equal(beforeColumns.filter(column => newColumnNames.includes(column.name)).length, 0);
      const oldColumnNames = beforeColumns.map(column => column.name).join(', ');
      beforePlan = source.get(`SELECT ${oldColumnNames} FROM review_plans WHERE id = ?`, ['rp_due_fixture']);
      assert.ok(beforePlan);
      beforeHistory = historyRows(source);
      assert.equal(beforeHistory.length, 72);
      assert.equal(beforeHistory.at(-1).version, previousVersion);
      beforeFingerprint = computeSchemaFingerprint(source, set, previousVersion);
      assert.equal(beforeFingerprint.hash, previousVfsFingerprint);
      assert.equal(source.pluck('PRAGMA integrity_check'), 'ok');
      assert.deepEqual(source.all('PRAGMA foreign_key_check'), []);
      const due = source.all("SELECT id FROM review_plans WHERE next_review_date <= ? AND status != 'suspended' ORDER BY next_review_date", [dueToday]);
      assert.deepEqual(due.map(row => row.id), ['rp_due_fixture']);
    } finally {
      source.close();
    }

    const coordinator = new MigrationCoordinator({
      appDataDir: active,
      migrationsDir: historyDir,
      auditDbPath: null,
      log: moduleLog,
    });
    const upgrade = await coordinator.runAll();
    assert.equal(upgrade.success, true, 'production coordinator upgrades the old vfs database');
    const vfsUpgrade = upgrade.databases.find(database => database.id === 'vfs');
    assert.deepEqual(
      { fromVersion: vfsUpgrade?.fromVersion, toVersion: vfsUpgrade?.toVersion, appliedCount: vfsUpgrade?.appliedCount, success: vfsUpgrade?.success },
      { fromVersion: previousVersion, toVersion: currentVersion, appliedCount: 1, success: true },
    );
    log('schema.upgrade.production', reportSummary(upgrade));

    const upgraded = new Connection(vfsPath, { label: 'vfs-upgrade-result', profile: { foreignKeys: true } });
    let afterFingerprint;
    let afterHistory;
    let afterPlan;
    let afterColumns;
    let newValues;
    let due;
    try {
      afterColumns = upgraded.all("PRAGMA table_info('review_plans')");
      assert.deepEqual(afterColumns.slice(0, beforeColumns.length), beforeColumns, 'pre-existing SM-2 columns keep cid/type/notnull/default/pk');
      assert.equal(afterColumns.length, beforeColumns.length + 7);
      assert.deepEqual(afterColumns.slice(beforeColumns.length).map(column => column.name), newColumnNames);
      const expectedNewColumns = [
        ['REAL', null, 0],
        ['REAL', null, 0],
        ['TEXT', null, 0],
        ['INTEGER', '0', 0],
        ['INTEGER', '0', 0],
        ['INTEGER', null, 0],
        ['INTEGER', null, 0],
      ];
      assert.deepEqual(afterColumns.slice(beforeColumns.length).map(column => [column.type, column.dflt_value, column.notnull]), expectedNewColumns);

      const oldColumnNames = beforeColumns.map(column => column.name).join(', ');
      afterPlan = upgraded.get(`SELECT ${oldColumnNames} FROM review_plans WHERE id = ?`, ['rp_due_fixture']);
      assert.deepEqual(afterPlan, beforePlan, 'seed review_plans row keeps every SM-2 and sync value');
      newValues = upgraded.get(
        `SELECT fsrs_stability, fsrs_difficulty, fsrs_phase, fsrs_learning_step,
                fsrs_lapses, fsrs_due_at, fsrs_last_review_at
           FROM review_plans WHERE id = ?`,
        ['rp_due_fixture'],
      );
      assert.deepEqual(newValues, {
        fsrs_stability: null,
        fsrs_difficulty: null,
        fsrs_phase: null,
        fsrs_learning_step: 0,
        fsrs_lapses: 0,
        fsrs_due_at: null,
        fsrs_last_review_at: null,
      });
      assert.deepEqual(Object.entries(newValues).filter(([, value]) => value === null).map(([name]) => name), [
        'fsrs_stability',
        'fsrs_difficulty',
        'fsrs_phase',
        'fsrs_due_at',
        'fsrs_last_review_at',
      ]);
      assert.deepEqual(Object.entries(newValues).filter(([, value]) => value === 0).map(([name]) => name), [
        'fsrs_learning_step',
        'fsrs_lapses',
      ]);
      due = upgraded.all("SELECT id, next_review_date FROM review_plans WHERE next_review_date <= ? AND status != 'suspended' ORDER BY next_review_date", [dueToday]);
      assert.deepEqual(due, [{ id: 'rp_due_fixture', next_review_date: '2026-11-01' }]);
      const future = upgraded.all("SELECT id FROM review_plans WHERE next_review_date > ? ORDER BY next_review_date", [dueToday]);
      assert.deepEqual(future.map(row => row.id), ['rp_future_fixture']);

      afterHistory = historyRows(upgraded);
      assert.deepEqual(afterHistory.slice(0, beforeHistory.length), beforeHistory, 'frozen refinery history rows keep their checksums');
      assert.equal(afterHistory.length, 73);
      assert.deepEqual(afterHistory.at(-1), {
        version: currentVersion,
        name: 'review_plans_fsrs',
        checksum: registered.refineryChecksum,
      });
      afterFingerprint = computeSchemaFingerprint(upgraded, set, currentVersion);
      assert.equal(afterFingerprint.hash, schemaVfs.fingerprint);
      const governance = upgraded.get(
        'SELECT fingerprint, canonical_schema FROM __governance_schema_fingerprints WHERE database_id = ? AND schema_version = ?',
        ['vfs', currentVersion],
      );
      assert.equal(governance?.fingerprint, afterFingerprint.hash);
      assert.equal(governance?.canonical_schema, afterFingerprint.canonicalSchema);
      assert.equal(sha256(afterFingerprint.canonicalSchema), afterFingerprint.hash);
      assert.equal(upgraded.pluck('PRAGMA integrity_check'), 'ok');
      assert.deepEqual(upgraded.all('PRAGMA foreign_key_check'), []);
    } finally {
      upgraded.close();
    }

    const restart = await coordinator.runAll();
    assert.equal(restart.success, true, 'production coordinator restarts after upgrade');
    const vfsRestart = restart.databases.find(database => database.id === 'vfs');
    assert.deepEqual(
      { fromVersion: vfsRestart?.fromVersion, toVersion: vfsRestart?.toVersion, appliedCount: vfsRestart?.appliedCount, success: vfsRestart?.success },
      { fromVersion: currentVersion, toVersion: currentVersion, appliedCount: 0, success: true },
    );
    log('schema.upgrade.restart', reportSummary(restart));

    const reopened = new Connection(vfsPath, { label: 'vfs-upgrade-restart', profile: { foreignKeys: true } });
    try {
      const again = computeSchemaFingerprint(reopened, set, currentVersion);
      assert.equal(again.hash, afterFingerprint.hash);
      assert.equal(reopened.pluck('PRAGMA integrity_check'), 'ok');
      assert.deepEqual(reopened.all('PRAGMA foreign_key_check'), []);
    } finally {
      reopened.close();
    }

    return {
      formatVersion: 1,
      unit: 'S0.08',
      upstream: {
        repository: '../dstu-node',
        commit: upstreamCommit,
        migration: registered.snapshot,
        sha256: registered.sha256,
        refineryChecksum: registered.refineryChecksum,
      },
      fixture: {
        database: 'vfs',
        fromVersion: previousVersion,
        toVersion: currentVersion,
        vfsMigrationCount: { before: 72, after: 73 },
        dueToday,
        seededPlans: [
          { id: 'rp_due_fixture', nextReviewDate: '2026-11-01', status: 'reviewing', easeFactor: 2.3, intervalDays: 6, repetitions: 3 },
          { id: 'rp_future_fixture', nextReviewDate: '2099-12-31', status: 'graduated', easeFactor: 2.5, intervalDays: 30, repetitions: 8 },
        ],
      },
      columns: {
        previousCount: beforeColumns.length,
        currentCount: afterColumns.length,
        appended: afterColumns.slice(beforeColumns.length).map(column => ({
          name: column.name,
          type: column.type,
          notNull: column.notnull,
          defaultValue: column.dflt_value,
          primaryKey: column.pk,
        })),
      },
      values: { before: beforePlan, after: afterPlan, appendedDefaults: newValues },
      due: { query: 'next_review_date <= dueToday AND status != suspended', rows: due },
      history: {
        beforeCount: beforeHistory.length,
        afterCount: afterHistory.length,
        last: afterHistory.at(-1),
        frozenPrefixUnchanged: true,
      },
      fingerprints: {
        previousVersion: { schemaVersion: previousVersion, fingerprint: beforeFingerprint.hash },
        currentVersion: { schemaVersion: currentVersion, fingerprint: afterFingerprint.hash, canonicalSchemaSha256: sha256(afterFingerprint.canonicalSchema) },
        matchesSchemaBaseline: afterFingerprint.hash === schemaVfs.fingerprint,
        governanceStored: true,
      },
      production: {
        coordinator: true,
        upgrade: reportSummary(upgrade),
        restart: reportSummary(restart),
      },
      independentFreshEvidence: schema.independentFreshEvidence,
      checks: {
        productionUpgrade: upgrade.success,
        independentFresh: schema.independentFreshEvidence?.observed?.databases?.some(database =>
          database.id === 'vfs'
          && database.fromVersion === 0
          && database.toVersion === currentVersion
          && database.appliedCount === 73
          && database.success === true),
        productionRestart: restart.success,
        oldDatabaseUpgrade: vfsUpgrade.appliedCount === 1,
        oldColumnsPreserved: true,
        oldRowPreserved: true,
        newColumnDefaults: newValues.fsrs_stability === null
          && newValues.fsrs_difficulty === null
          && newValues.fsrs_phase === null
          && newValues.fsrs_learning_step === 0
          && newValues.fsrs_lapses === 0
          && newValues.fsrs_due_at === null
          && newValues.fsrs_last_review_at === null,
        dailyDueQuery: due.length === 1 && due[0].id === 'rp_due_fixture',
        checksumPreserved: true,
        checksumAppended: true,
        fingerprintMatchesBaseline: afterFingerprint.hash === schemaVfs.fingerprint,
        fingerprintStored: true,
        integrityOk: true,
        foreignKeyCheckEmpty: true,
      },
    };
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
}
