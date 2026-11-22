import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { desktop, root, sha256 } from '../lib.mjs';
import { computeFingerprints, describeDatabase, loadDesktop, readArchive, readRows } from './package.mjs';

const CORE = ['vfs', 'llm_usage', 'chat_v2', 'mistakes'];
const ARCHIVE = { vfs: 'vfs.db', llm_usage: 'llm_usage.db', chat_v2: 'chat_v2.db', mistakes: 'mistakes.db', audit: 'databases/audit.db' };
const SLOT_PATH = { vfs: 'databases/vfs.db', llm_usage: 'llm_usage.db', chat_v2: 'chat_v2.db', mistakes: 'mistakes.db' };
const FSRS_COLUMNS = [
  ['fsrs_stability', 'REAL', null, 0],
  ['fsrs_difficulty', 'REAL', null, 0],
  ['fsrs_phase', 'TEXT', null, 0],
  ['fsrs_learning_step', 'INTEGER', '0', 0],
  ['fsrs_lapses', 'INTEGER', '0', 0],
  ['fsrs_due_at', 'INTEGER', null, 0],
  ['fsrs_last_review_at', 'INTEGER', null, 0],
];
const LEGACY_PROBE_ROWS = [
  {
    id: 'legacy_review_plan_probe_due',
    questionId: 'legacy_review_question_probe_due',
    nextReviewDate: '2020-01-01',
    lastReviewDate: '2019-12-20',
    status: 'reviewing',
    easeFactor: 2.3,
    intervalDays: 6,
    repetitions: 3,
    totalReviews: 5,
    totalCorrect: 4,
    localVersion: 7,
  },
  {
    id: 'legacy_review_plan_probe_future',
    questionId: 'legacy_review_question_probe_future',
    nextReviewDate: '2099-12-31',
    lastReviewDate: '2026-10-01',
    status: 'graduated',
    easeFactor: 2.5,
    intervalDays: 30,
    repetitions: 8,
    totalReviews: 9,
    totalCorrect: 8,
    localVersion: 3,
  },
];

function summary(report) {
  return {
    success: report.success,
    databases: report.databases.map(({ id, fromVersion, toVersion, appliedCount, success }) => ({
      id, fromVersion, toVersion, appliedCount, success,
    })),
  };
}

function tableRows(connection, table) {
  const escaped = table.replaceAll('"', '""');
  const withoutRowid = connection.pluck(
    "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name=?1 AND upper(sql) LIKE '%WITHOUT ROWID%')",
    [table],
  ) === 1;
  return withoutRowid
    ? connection.all(`SELECT * FROM "${escaped}" ORDER BY ${connection.all(`PRAGMA table_info('${table.replaceAll("'", "''")}')`).map(column => `"${column.name.replaceAll('"', '""')}"`).join(', ')}`)
    : connection.all(`SELECT * FROM "${escaped}" ORDER BY rowid`);
}

function tableShape(connection, table) {
  const escaped = table.replaceAll("'", "''");
  const columns = connection.all(`PRAGMA table_info('${escaped}')`).map(column => ({
    name: column.name,
    type: column.type,
    notNull: column.notnull,
    defaultValue: column.dflt_value,
    primaryKey: column.pk,
  }));
  return { table, columns };
}

/** 桌面 schema 在 review_plans 上要求的同步列；旧移动库有同一张表但允许没有预置行。 */
function seedLegacyReviewRows(connection) {
  const resource = connection.pluck("SELECT id FROM resources WHERE type = 'note' ORDER BY id LIMIT 1");
  assert.ok(resource, 'vfs fixture has no resource row for the legacy review probe');
  const columns = new Set(connection.all("PRAGMA table_info('review_plans')").map(column => column.name));
  for (const key of ['exam_id']) {
    assert.ok(columns.has(key), `review_plans missing legacy column ${key}`);
  }
  for (const row of LEGACY_PROBE_ROWS) {
    const examId = `legacy_exam_probe_${row.id}`;
    const hasExamSheets = connection.pluck("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='exam_sheets')") === 1;
    if (!hasExamSheets) throw new Error('vfs fixture has no exam_sheets table');
    const examColumns = new Set(connection.all("PRAGMA table_info('exam_sheets')").map(column => column.name));
    if (examColumns.has('id') && examColumns.has('exam_name')) {
      const values = ['id', 'resource_id', 'exam_name', 'status', 'temp_id', 'metadata_json', 'preview_json', 'created_at', 'updated_at']
        .filter(column => examColumns.has(column));
      const params = values.map(column => column === 'id' ? examId
        : column === 'resource_id' ? resource
          : column === 'exam_name' ? 'Legacy upgrade probe'
            : column === 'status' ? 'completed'
              : column === 'temp_id' ? `temp_${row.id}`
                : column.endsWith('_json') ? '{}'
                  : '2026-10-20T08:00:00Z');
      connection.run(
        `INSERT INTO exam_sheets (${values.map(column => `"${column}"`).join(', ')}) VALUES (${values.map(() => '?').join(', ')})`,
        params,
      );
    }
    const hasQuestions = connection.pluck("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='questions')") === 1;
    if (hasQuestions) {
      try {
        const questionColumns = new Set(connection.all("PRAGMA table_info('questions')").map(column => column.name));
        const values = [
          ['id', row.questionId],
          ['exam_id', examId],
          ['question_label', '1'],
          ['content', 'Legacy upgrade probe'],
          ['answer', 'A'],
          ['created_at', '2026-10-20T08:00:00Z'],
          ['updated_at', '2026-10-20T08:00:00Z'],
        ].filter(([column]) => questionColumns.has(column));
        connection.run(
          `INSERT INTO questions (${values.map(([column]) => `"${column}"`).join(', ')}) VALUES (${values.map(() => '?').join(', ')})`,
          values.map(([, value]) => value),
        );
      } catch {
        // The probe only needs a row in review_plans; a fixture without the questions contract skips this step.
      }
    }
    const reviewColumns = new Set(connection.all("PRAGMA table_info('review_plans')").map(column => column.name));
    const values = [
      ['id', row.id],
      ['question_id', row.questionId],
      ['exam_id', examId],
      ['ease_factor', row.easeFactor],
      ['interval_days', row.intervalDays],
      ['repetitions', row.repetitions],
      ['next_review_date', row.nextReviewDate],
      ['last_review_date', row.lastReviewDate],
      ['status', row.status],
      ['total_reviews', row.totalReviews],
      ['total_correct', row.totalCorrect],
      ['consecutive_failures', 0],
      ['is_difficult', row.id.endsWith('due') ? 1 : 0],
      ['created_at', '2026-10-20T08:00:00Z'],
      ['updated_at', '2026-10-20T08:00:00Z'],
      ['device_id', 'legacy-upgrade-probe'],
      ['local_version', row.localVersion],
      ['deleted_at', null],
    ].filter(([column]) => reviewColumns.has(column));
    connection.run(
      `INSERT INTO review_plans (${values.map(([column]) => `"${column}"`).join(', ')}) VALUES (${values.map(() => '?').join(', ')})`,
      values.map(([, value]) => value),
    );
  }
  assert.equal(connection.pluck('SELECT COUNT(*) FROM review_plans'), LEGACY_PROBE_ROWS.length);
}

/**
 * 复制旧包到临时槽后由生产 MigrationCoordinator 实际迁移，再逐表回读。
 * 不把旧包指纹当新 schema 指纹：源版本由 manifest 决定，目标版本由迁移结果决定。
 */
export async function verifyLegacyUpgrade(archive) {
  const temp = mkdtempSync(resolve(root, '.baseline-legacy-upgrade-'));
  const slot = resolve(temp, 'slot');
  try {
    for (const id of CORE) {
      const relative = SLOT_PATH[id];
      const file = resolve(slot, relative);
      mkdirSync(dirname(file), { recursive: true });
      writeFileSync(file, archive.data.get(ARCHIVE[id]));
    }
    {
      const { Connection } = await loadDesktop('host/platform/storage/connection.ts');
      const probe = new Connection(resolve(slot, SLOT_PATH.vfs), { label: 'vfs-probe-seed', profile: { foreignKeys: true } });
      try {
        // The public fixture has no review row; seed only in the temporary copy so the target
        // migration must preserve real SM-2 values and append FSRS defaults for them.
        probe.exec('PRAGMA foreign_keys = OFF');
        seedLegacyReviewRows(probe);
        assert.deepEqual(probe.all('PRAGMA foreign_key_check'), [], 'legacy review probes introduced foreign-key violations');
      }
      finally { probe.close(); }
    }
    const auditEntry = archive.data.get(ARCHIVE.audit);
    if (auditEntry !== undefined) {
      mkdirSync(resolve(slot, 'databases'), { recursive: true });
      writeFileSync(resolve(slot, ARCHIVE.audit), auditEntry);
    }

    const { Connection } = await loadDesktop('host/platform/storage/connection.ts');
    const auditBefore = auditEntry === undefined ? null : new Connection(resolve(slot, ARCHIVE.audit), { label: 'audit-before', profile: { foreignKeys: true } });
    const beforeConnections = new Map();
    try {
      if (auditBefore !== null) beforeConnections.set(ARCHIVE.audit, { connection: auditBefore });
      for (const id of CORE) {
        beforeConnections.set(ARCHIVE[id], {
          connection: new Connection(resolve(slot, SLOT_PATH[id]), { label: `${id}-before`, profile: { foreignKeys: true } }),
        });
      }
      const beforeRows = readRows(beforeConnections);
      const beforeAudit = auditBefore?.all('SELECT id, operation_type, target, status FROM __audit_log ORDER BY id') ?? null;
      const beforeTables = {};
      for (const id of CORE) {
        const connection = beforeConnections.get(ARCHIVE[id]).connection;
        const history = connection.all('SELECT version, name, checksum FROM refinery_schema_history ORDER BY version');
        const tables = connection.all("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map(row => row.name);
        beforeTables[id] = {
          version: history.length === 0 ? 0 : history.at(-1).version,
          history: history.length,
          tables: Object.fromEntries(tables.filter(name => name !== 'refinery_schema_history').map(name => [name, tableRows(connection, name)])),
          shapes: Object.fromEntries(tables.filter(name => name !== 'refinery_schema_history').map(name => [name, tableShape(connection, name)])),
        };
      }
      for (const entry of beforeConnections.values()) entry.connection.close();
      beforeConnections.clear();

      const { MigrationCoordinator } = await loadDesktop('host/platform/storage/migration/coordinator.ts');
      const coordinator = new MigrationCoordinator({
        appDataDir: slot,
        // 直接使用冻结桌面提交里的 SQL；与 config/schema/history 逐字节一致，但这里以桌面 head 为准。
        migrationsDir: resolve(desktop, 'resources/app/migrations'),
        auditDbPath: null,
        log: () => {},
      });
      const upgrade = await coordinator.runAll();
      assert.equal(upgrade.success, true, `target migration failed: ${upgrade.error}`);
      const restart = await coordinator.runAll();
      assert.equal(restart.success, true, 'target migration restart failed');
      assert.ok(restart.databases.every(database => database.appliedCount === 0), 'second target migration run must apply zero migrations');

      const afterConnections = new Map();
      try {
        const afterAudit = auditEntry === undefined ? null : new Connection(resolve(slot, ARCHIVE.audit), { label: 'audit-after', profile: { foreignKeys: true } });
        if (afterAudit !== null) afterConnections.set(ARCHIVE.audit, { connection: afterAudit });
        for (const id of CORE) {
          afterConnections.set(ARCHIVE[id], {
            connection: new Connection(resolve(slot, SLOT_PATH[id]), { label: `${id}-after`, profile: { foreignKeys: true } }),
          });
        }
        const afterRows = readRows(afterConnections);
        const preserved = {};
        const tableChecks = {};
        for (const id of CORE) {
          const connection = afterConnections.get(ARCHIVE[id]).connection;
          const history = connection.all('SELECT version, name, checksum FROM refinery_schema_history ORDER BY version');
          const tables = connection.all("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map(row => row.name);
          const tableRowsAfter = Object.fromEntries(tables
            .filter(name => name !== 'refinery_schema_history' && name !== '__governance_schema_fingerprints')
            .map(name => [name, tableRows(connection, name)]));
          const compareTables = Object.keys(beforeTables[id].tables).filter(name => !name.startsWith('__governance_'));
          for (const name of compareTables) {
            assert.ok(Object.hasOwn(tableRowsAfter, name), `${id}: target database lost table ${name}`);
            const beforeRows = beforeTables[id].tables[name];
            const afterRows = tableRowsAfter[name];
            if (name === 'review_plans') {
              const legacyColumns = new Set(beforeTables[id].shapes[name].columns.map(column => column.name));
              assert.equal(afterRows.length, beforeRows.length, `${id}.${name}: row count changed during target migration`);
              for (const [index, row] of afterRows.entries()) {
                const legacy = Object.fromEntries([...legacyColumns].map(column => [column, row[column]]));
                assert.deepEqual(legacy, beforeRows[index], `${id}.${name}[${index}]: legacy columns changed during target migration`);
              }
            } else {
              assert.deepEqual(afterRows, beforeRows, `${id}.${name}: business rows changed during target migration`);
            }
          }
          preserved[id] = {
            rowsBefore: Object.fromEntries(compareTables.map(name => [name, beforeTables[id].tables[name].length])),
            rowsAfter: Object.fromEntries(compareTables.map(name => [name, tableRowsAfter[name].length])),
            unchanged: true,
            shapes: Object.fromEntries(tables.filter(name => !name.startsWith('__governance_')).map(name => [name, tableShape(connection, name)])),
          };
          tableChecks[id] = {
            fromVersion: beforeTables[id].version,
            toVersion: history.length === 0 ? 0 : history.at(-1).version,
            historyBefore: beforeTables[id].history,
            historyAfter: history.length,
            tablesBefore: Object.keys(beforeTables[id].tables).length,
            tablesAfter: tables.length,
          };
        }
        const auditAfter = afterAudit?.all('SELECT id, operation_type, target, status FROM __audit_log ORDER BY id') ?? null;
        assert.deepEqual(auditAfter, beforeAudit, 'audit rows changed during target migration');
        for (const id of CORE) {
          assert.deepEqual(afterRows[id], beforeRows[id], `${id}: selected business rows changed during target migration`);
        }

        const vfs = afterConnections.get(ARCHIVE.vfs).connection;
        const fsrs = vfs.all("SELECT name, type, dflt_value, \"notnull\" FROM pragma_table_info('review_plans') WHERE name LIKE 'fsrs\\_%' ESCAPE '\\' ORDER BY cid");
        const fsrsDefaults = vfs.all('SELECT fsrs_stability, fsrs_difficulty, fsrs_phase, fsrs_learning_step, fsrs_lapses, fsrs_due_at, fsrs_last_review_at FROM review_plans');
        const legacyRows = vfs.all('SELECT id, ease_factor, interval_days, repetitions, next_review_date, last_review_date, status FROM review_plans ORDER BY id');
        const reviewPlans = vfs.pluck('SELECT COUNT(*) FROM review_plans');
        const fsrsColumnNames = fsrs.map(column => column.name);
        assert.deepEqual(fsrsColumnNames, FSRS_COLUMNS.map(([name]) => name), 'FSRS columns are missing or out of order after target migration');
        assert.ok(Array.isArray(fsrs) && fsrs.length >= 7, 'FSRS columns missing after target migration');
        for (const row of fsrsDefaults) {
          assert.equal(row.fsrs_learning_step, 0, 'fsrs_learning_step default is not 0');
          assert.equal(row.fsrs_lapses, 0, 'fsrs_lapses default is not 0');
          assert.equal(row.fsrs_stability, null, 'fsrs_stability default is not NULL');
          assert.equal(row.fsrs_difficulty, null, 'fsrs_difficulty default is not NULL');
          assert.equal(row.fsrs_phase, null, 'fsrs_phase default is not NULL');
          assert.equal(row.fsrs_due_at, null);
          assert.equal(row.fsrs_last_review_at, null);
        }
        const rows = afterRows;
        assert.ok(rows.notes.length > 0, 'target migration lost notes');
        assert.ok(rows.blobs.length === 1, 'target migration changed the public blob row count');
        const targetVersions = Object.fromEntries(CORE.map(id => [id, tableChecks[id].toVersion]));
        const targetDatabases = Object.fromEntries(CORE.map(id => [
          id,
          describeDatabase(afterConnections.get(ARCHIVE[id]).connection, id),
        ]));
        const targetFingerprints = await computeFingerprints(afterConnections, targetVersions);
        return {
          mode: 'copy-to-temporary-slot-then-production-coordinator',
          migrationsDir: 'resources/app/migrations（冻结桌面 e6b2657f）',
          slotLayout: SLOT_PATH,
          sourceVersions: Object.fromEntries(CORE.map(id => [id, beforeTables[id].version])),
          targetVersions,
          migration: summary(upgrade),
          restart: summary(restart),
          preserved: {
            businessRows: true,
            rows: Object.fromEntries(Object.entries(preserved).map(([id, value]) => [id, {
              rowsBefore: value.rowsBefore,
              rowsAfter: value.rowsAfter,
              unchanged: value.unchanged,
            }])),
            assets: rows.blobs.map(blob => ({ hash: blob.hash, size: blob.size, relativePath: blob.relative_path })),
            auditRows: beforeAudit?.length ?? 0,
            tableChecks,
          },
          fsrs: {
            columns: fsrs,
            expectedColumns: FSRS_COLUMNS.map(([name, type, defaultValue, notNull]) => ({ name, type, defaultValue, notNull })),
            reviewPlanRows: reviewPlans,
            legacyRows,
            allLegacyRowsDefaulted: true,
          },
          target: {
            databases: targetDatabases,
            fingerprints: targetFingerprints,
          },
        };
      } finally {
        for (const entry of afterConnections.values()) entry.connection.close();
      }
    } finally {
      for (const entry of beforeConnections.values()) entry.connection.close();
    }
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
}
