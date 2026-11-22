import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  artifact, desktop, log, main, root, sha256,
} from './lib.mjs';

const FORMAT = 'dstu-s0.19-sync-registry-contract/v1';
const UNIT = 'S0.19';
const DATABASES = ['vfs', 'llm_usage', 'chat_v2', 'mistakes'];
const ROW_OPS = ['insert', 'update', 'delete'];
const FTS_SHADOW_SUFFIXES = [
  '_content', '_docsize', '_config', '_idx', '_segdir', '_segments', '_stat', '_data',
];
const FTS_REBUILD_SOURCE = 'resources/app/migrations/vfs/V20260724__notes_fts.sql';
const MIGRATIONS_DIR = 'config/schema/history';
const REVIEW_PLAN_FSRS_COLUMNS = [
  'fsrs_stability',
  'fsrs_difficulty',
  'fsrs_phase',
  'fsrs_learning_step',
  'fsrs_lapses',
  'fsrs_due_at',
  'fsrs_last_review_at',
];
const loadDesktop = file => import(pathToFileURL(resolve(desktop, file)).href);
const quote = value => `"${String(value).replaceAll('"', '""')}"`;
const bytes = value => new TextEncoder().encode(value);
const json = value => JSON.stringify(value);
const wire = value => typeof value === 'bigint' ? { $bigint: value.toString() }
  : Array.isArray(value) ? value.map(wire)
    : value !== null && typeof value === 'object'
      ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, wire(item)]))
      : value;

function sourceHash(file, base = root, origin = 'android') {
  const content = readFileSync(resolve(base, file));
  return { file, origin, bytes: content.length, sha256: sha256(content) };
}

function normalizeReport(report) {
  const strip = value => {
    if (Array.isArray(value)) return value.map(strip);
    if (value === null || typeof value !== 'object') return value;
    return Object.fromEntries(Object.entries(value)
      .filter(([key]) => !['durationMs', 'totalDurationMs'].includes(key))
      .map(([key, item]) => [key, strip(item)]));
  };
  return strip(wire(report));
}

function withFixedClock(clockMs, run) {
  const originalNow = Date.now;
  const originalPerformanceNow = performance.now;
  const originalTimeOrigin = performance.timeOrigin;
  Date.now = () => clockMs;
  performance.now = () => 0;
  Object.defineProperty(performance, 'timeOrigin', { configurable: true, value: clockMs });
  try {
    return run();
  } finally {
    Date.now = originalNow;
    performance.now = originalPerformanceNow;
    Object.defineProperty(performance, 'timeOrigin', { configurable: true, value: originalTimeOrigin });
    assert.equal(Date.now, originalNow, 'fixed clock did not restore Date.now');
    assert.equal(performance.now, originalPerformanceNow, 'fixed clock did not restore performance.now');
    assert.equal(performance.timeOrigin, originalTimeOrigin, 'fixed clock did not restore performance.timeOrigin');
  }
}

function tableInfoColumns(conn, table) {
  return conn.all(`PRAGMA table_info(${quote(table)})`).map(row => ({
    cid: Number(row.cid),
    name: String(row.name),
    type: String(row.type ?? ''),
    notnull: Number(row.notnull),
    default: row.dflt_value === null || row.dflt_value === undefined ? null : String(row.dflt_value),
    pk: Number(row.pk),
  }));
}

function tableXinfoColumns(conn, table) {
  return conn.all(`PRAGMA table_xinfo(${quote(table)})`).map(row => ({
    cid: Number(row.cid),
    name: String(row.name),
    type: String(row.type ?? ''),
    notnull: Number(row.notnull),
    default: row.dflt_value === null || row.dflt_value === undefined ? null : String(row.dflt_value),
    pk: Number(row.pk),
    hidden: Number(row.hidden ?? 0),
  }));
}

function indexes(conn, table) {
  return conn.all(`PRAGMA index_list(${quote(table)})`).map(row => {
    const sql = conn.get("SELECT sql FROM sqlite_master WHERE type='index' AND name=?1", [row.name])?.sql ?? '';
    return {
      name: String(row.name),
      unique: Number(row.unique),
      origin: String(row.origin),
      partial: Number(row.partial),
      columns: conn.all(`PRAGMA index_info(${quote(row.name)})`)
        .sort((left, right) => Number(left.seqno) - Number(right.seqno))
        .map(item => item.name === null ? { expression: true } : String(item.name)),
      sqlSha256: sql === '' ? null : sha256(bytes(String(sql))),
    };
  });
}

function tableMetadata(conn, table) {
  const master = conn.get(
    "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type='table' AND name=?1",
    [table],
  );
  const tableInfo = tableInfoColumns(conn, table);
  const tableColumns = tableXinfoColumns(conn, table);
  const tableIndexes = indexes(conn, table);
  const uniqueSets = [];
  for (const index of tableIndexes.filter(item => item.unique === 1)) {
    if (!index.columns.every(column => typeof column === 'string')) continue;
    if (uniqueSets.some(set => json(set.columns) === json(index.columns))) continue;
    uniqueSets.push({
      columns: index.columns,
      index: index.name,
      origin: index.origin,
      partial: index.partial,
    });
  }
  const ddl = String(master?.sql ?? '');
  return {
    exists: master !== undefined,
    ddlSha256: ddl === '' ? null : sha256(bytes(ddl)),
    columns: tableInfo,
    xinfoColumns: tableColumns,
    hiddenColumns: tableColumns.filter(column => column.hidden !== 0),
    sqlitePrimaryKey: tableInfo
      .filter(column => column.pk > 0)
      .sort((left, right) => left.pk - right.pk)
      .map(column => column.name),
    indexes: tableIndexes,
    uniqueSets,
    foreignKeys: wire(conn.all(`PRAGMA foreign_key_list(${quote(table)})`)),
  };
}

function allTables(conn) {
  return conn.all(
    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
  ).map(row => String(row.name));
}

function triggerRows(conn) {
  return conn.all(
    "SELECT name,tbl_name,COALESCE(sql,'') AS sql FROM sqlite_master WHERE type='trigger' ORDER BY name",
  ).map(row => ({
    name: String(row.name),
    table: String(row.tbl_name),
    sql: String(row.sql),
  }));
}

function frozenTable(schema, database, table) {
  const db = schema.databases.find(item => item.id === database);
  return {
    database: db ?? null,
    table: db?.schema.tables.find(item => item.name === table) ?? null,
    triggers: db?.schema.objects.filter(object => object.type === 'trigger' && object.tbl_name === table) ?? [],
  };
}

function visibleColumns(items) {
  return items.filter(column => Number(column.hidden ?? 0) === 0).map(column => ({
    cid: Number(column.cid),
    name: String(column.name),
    type: String(column.type ?? ''),
    notnull: Number(column.notnull),
    dflt_value: column.default === undefined ? column.dflt_value ?? null
      : column.default === null ? null : String(column.default),
    pk: Number(column.pk),
  }));
}

function canonicalClassification(entry, api) {
  return {
    database: entry.database,
    table: entry.table_name,
    primaryKey: entry.primary_key,
    category: entry.category,
    conflictPolicy: entry.conflict_policy,
    businessUniqueKeys: api.getBusinessUniqueKeys(entry.database, entry.table_name),
    hasJsonBlobs: entry.has_json_blobs,
    mergeNotes: entry.merge_notes,
  };
}

function normalizedClassification(entry) {
  return {
    database: entry.database,
    table: entry.table,
    primaryKey: entry.primaryKey,
    category: entry.category,
    conflictPolicy: entry.conflictPolicy,
    businessUniqueKeys: entry.businessUniqueKeys,
    hasJsonBlobs: entry.hasJsonBlobs,
    mergeNotes: entry.mergeNotes,
  };
}

function differences(left, right, path = '$') {
  if (Object.is(left, right)) return [];
  if (typeof left !== typeof right || left === null || right === null) return [{ path, left, right }];
  if (typeof left !== 'object') return [{ path, left, right }];
  if (Array.isArray(left) !== Array.isArray(right)) return [{ path, left, right }];
  const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
  return [...keys].flatMap(key => differences(left[key], right[key], `${path}.${key}`));
}

function schemaComparison(liveMetadata, frozen) {
  if (frozen.table === null) {
    return {
      present: false,
      tablesMatch: liveMetadata.exists === false,
      columnsMatch: liveMetadata.exists === false,
      visibleCidMatch: liveMetadata.exists === false,
      triggersMatch: liveMetadata.exists === false && frozen.triggers.length === 0,
    };
  }
  const actualVisible = visibleColumns(liveMetadata.columns);
  const expectedVisible = visibleColumns(frozen.table.columns);
  return {
    present: liveMetadata.exists,
    databaseFingerprint: frozen.database.fingerprint,
    tablesMatch: liveMetadata.exists,
    columnsMatch: json(actualVisible) === json(expectedVisible),
    visibleCidMatch: json(actualVisible.map(column => column.cid))
      === json(expectedVisible.map(column => column.cid)),
    actualColumnCount: actualVisible.length,
    baselineColumnCount: expectedVisible.length,
    hiddenColumns: liveMetadata.hiddenColumns
      .map(column => ({ cid: column.cid, name: column.name, hidden: column.hidden })),
    triggersMatch: false,
  };
}

function triggerContract(api, entry, rows, category, shadow) {
  const exemptions = ROW_OPS.filter(operation => api.changeLogTriggerExempt(entry.database, entry.table_name, operation));
  const requiredOps = entry.category === 'RowSync' ? ROW_OPS.filter(operation => !exemptions.includes(operation)) : [];
  const triggerList = rows.map(row => {
    const event = api.triggerEvent(row.sql);
    return {
      name: row.name,
      event,
      capturesChangeLog: row.sql.includes('__change_log'),
      category,
      local: category === 'LocalRuntime',
      derived: category === 'DerivedRebuild',
      shadow,
      sha256: sha256(bytes(row.sql)),
    };
  });
  const capturedOps = [...new Set(triggerList
    .filter(row => row.capturesChangeLog && row.event !== null)
    .map(row => row.event))].sort();
  const missingOps = requiredOps.filter(operation => !capturedOps.includes(operation));
  return {
    requiredOps,
    exemptOps: exemptions,
    rows: triggerList,
    capturedOps,
    missingOps,
    allRequiredPresent: missingOps.length === 0,
    strayCapture: entry.category !== 'RowSync' && capturedOps.length > 0,
  };
}

function shadowsFor(databaseTables, base) {
  return FTS_SHADOW_SUFFIXES.map(suffix => `${base}${suffix}`)
    .filter(name => databaseTables.has(name));
}

function registryEntry(api, classification, entry, metadata, triggers, shadowTables) {
  const live = metadata.get(entry.table_name) ?? {
    exists: false,
    ddlSha256: null,
    columns: [],
    xinfoColumns: [],
    hiddenColumns: [],
    sqlitePrimaryKey: [],
    indexes: [],
    uniqueSets: [],
    foreignKeys: [],
  };
  const virtual = entry.primary_key === '(virtual)';
  const productionPk = live.exists && !virtual
    ? api.primaryKeyColumns(api.connection, entry.table_name)
    : [];
  const logicalPk = virtual ? [] : entry.primary_key.split(',').map(part => part.trim()).filter(Boolean);
  const businessUniqueKeys = api.getBusinessUniqueKeys(entry.database, entry.table_name);
  const matchingUnique = live.exists && businessUniqueKeys.length > 0
    ? live.uniqueSets.filter(set => json(set.columns) === json(businessUniqueKeys))
    : [];
  const category = entry.category;
  const local = category === 'LocalRuntime';
  const derived = category === 'DerivedRebuild';
  const shadow = shadowTables.has(entry.table_name);
  const tableTriggers = triggerContract(
    api,
    entry,
    triggers.filter(row => row.table === entry.table_name),
    category,
    shadow,
  );
  const logicalMatchesSqlite = virtual
    ? live.sqlitePrimaryKey.length === 0
    : json(live.sqlitePrimaryKey) === json(logicalPk);
  const logicalMatchesProduction = virtual
    ? productionPk.length === 0
    : json(productionPk) === json(logicalPk);
  const checksumIncluded = !api.isExcludedFromChecksum(entry.database, entry.table_name)
    && api.checksumTables(entry.database).some(row => row.table_name === entry.table_name);
  const anomaly = {
    missingSchema: !live.exists && category !== 'Deprecated',
    logicalVsSqliteMismatch: live.exists && category !== 'Deprecated' && !logicalMatchesSqlite,
    logicalVsProductionMismatch: live.exists && category !== 'Deprecated' && !logicalMatchesProduction,
    uniqueDeclaredWithoutSqliteMatch: live.exists && businessUniqueKeys.length > 0 && matchingUnique.length === 0,
    missingRequiredTrigger: tableTriggers.missingOps.length > 0,
    strayCapture: tableTriggers.strayCapture,
  };
  const baselineEntry = classification.registry.find(row => row.database === entry.database && row.table === entry.table_name);
  return {
    order: entry.order,
    database: entry.database,
    table: entry.table_name,
    category,
    conflictPolicy: entry.conflict_policy,
    businessUniqueKeys,
    businessUniqueKeysExpression: entry.business_unique_keys,
    hasJsonBlobs: entry.has_json_blobs,
    mergeNotes: entry.merge_notes,
    adoptionUnit: baselineEntry?.adoptionUnit ?? null,
    primaryKey: {
      declared: entry.primary_key,
      logical: logicalPk,
      production: productionPk,
      sqlite: live.sqlitePrimaryKey,
      virtual,
      logicalMatchesSqlite,
      logicalMatchesProduction,
    },
    logicalPk: {
      declared: entry.primary_key,
      columns: logicalPk,
      virtual,
    },
    sqlitePk: {
      columns: live.sqlitePrimaryKey,
      productionColumns: productionPk,
      exists: live.exists,
      matchesDeclared: logicalMatchesSqlite,
    },
    uniqueness: {
      declared: businessUniqueKeys,
      sqliteSets: live.uniqueSets,
      matchingIndexes: matchingUnique.map(set => set.index),
      matchesDeclaredUnique: businessUniqueKeys.length === 0 ? null : matchingUnique.length > 0,
    },
    scope: {
      portable: ['RowSync', 'FileSync'].includes(category),
      incrementalSync: ['RowSync', 'FileSync'].includes(category),
      rowTriggerCapture: category === 'RowSync',
      fileManifestCapture: category === 'FileSync',
      localRuntime: local,
      derived,
      backupOnly: category === 'BackupOnly',
      deprecated: category === 'Deprecated',
      wholeDatabaseBackup: live.exists,
      backupNote: 'The coordinated database file is backed up as a whole; LocalRuntime tables are not claimed to be excluded from that database backup.',
    },
    checksum: {
      included: checksumIncluded,
      excludedByProductionRule: !checksumIncluded,
    },
    capture: local
      ? 'No incremental row capture; local-runtime data is still included when the whole database file is backed up.'
      : category === 'BackupOnly'
        ? 'No incremental row capture; restored from the whole database-file backup.'
        : derived
          ? 'No incremental row capture; rebuilt from synced source rows or file payloads.'
          : category === 'Deprecated'
            ? 'No live schema and no capture.'
            : category === 'FileSync'
              ? 'File payloads use file manifests; this table has no row trigger contract.'
              : 'Insert, update and delete are captured through __change_log triggers; production exemptions are explicit.',
    schema: {
      present: live.exists,
      virtual,
      shadow,
      ftsShadowTables: virtual ? shadowsFor(metadata, entry.table_name) : [],
      ddlSha256: live.ddlSha256,
      columns: live.columns,
      xinfoColumns: live.xinfoColumns,
      hiddenColumns: live.hiddenColumns,
      generatedColumns: live.xinfoColumns
        .filter(column => Number(column.hidden) === 2 || Number(column.hidden) === 3)
        .map(column => ({ cid: column.cid, name: column.name, hidden: column.hidden })),
      indexes: live.indexes,
      uniqueSets: live.uniqueSets,
      foreignKeys: live.foreignKeys,
    },
    triggers: tableTriggers,
    anomaly,
  };
}

function createCrudHarness(api, connection) {
  const term = 'registrycontractprobe';
  const stableRecord = (table, id) => table === 'notes' ? '<note>' : id === api.crudResourceId ? '<resource>' : '<auxiliary>';
  const noteFor = () => connection.get(
    "SELECT id,resource_id,title,deleted_at FROM notes WHERE title LIKE 'Registry contract note%' ORDER BY created_at LIMIT 1",
  );
  const counts = () => {
    const row = connection.get(`
      SELECT
        (SELECT COUNT(*) FROM notes) AS notes,
        (SELECT COUNT(*) FROM resources) AS resources,
        (SELECT COUNT(*) FROM __change_log) AS changeLog,
        (SELECT COUNT(*) FROM notes_fts) AS ftsRows
    `);
    const note = noteFor();
    const directTermMatches = note === undefined ? 0 : Number(connection.pluck(
      'SELECT COUNT(*) FROM notes_fts WHERE rowid=(SELECT rowid FROM notes WHERE id=?1) AND notes_fts MATCH ?2',
      [note.id, term],
    ) ?? 0);
    return {
      notes: Number(row.notes),
      resources: Number(row.resources),
      changeLog: Number(row.changeLog),
      ftsRows: Number(row.ftsRows),
      directTermMatches,
      productionSearchHits: api.noteRepository.searchNotesWithSnippets(term, 20, connection).length,
      notePresent: note !== undefined,
      noteDeleted: note === undefined ? null : note.deleted_at !== null,
    };
  };
  const step = (name, expected, run) => {
    const before = Number(connection.pluck('SELECT COALESCE(MAX(id),0) FROM __change_log'));
    run();
    const rows = connection.all(
      'SELECT id,table_name,record_id,operation,sync_version FROM __change_log WHERE id>?1 ORDER BY id',
      [before],
    );
    const target = rows
      .filter(row => ['notes', 'resources'].includes(String(row.table_name)))
      .map(row => ({
        table: String(row.table_name),
        record: stableRecord(String(row.table_name), String(row.record_id)),
        operation: String(row.operation),
        syncVersion: wire(row.sync_version),
      }));
    for (const item of expected) {
      assert.ok(target.some(row => row.table === item.table && row.operation === item.operation),
        `${name}: missing ${item.table}.${item.operation} capture`);
    }
    const capturedTables = [...new Set(rows.map(row => String(row.table_name)))].sort();
    const uncategorized = capturedTables.filter(table => api.classification.get(table) === undefined);
    assert.deepEqual(uncategorized, [], `${name}: unregistered table wrote a change-log row`);
    assert.ok(capturedTables.every(table => api.classification.get(table).category === 'RowSync'),
      `${name}: captured table is not RowSync`);
    const snapshot = counts();
    log('registry-contract.crud.step', {
      unit: UNIT,
      step: name,
      target,
      ancillaryTables: capturedTables.filter(table => !['notes', 'resources'].includes(table)),
      newChangeLogRows: rows.length,
      counts: snapshot,
    });
    return {
      name,
      target,
      ancillaryTables: capturedTables.filter(table => !['notes', 'resources'].includes(table)),
      newChangeLogRows: rows.length,
      counts: snapshot,
    };
  };

  const baseline = counts();
  assert.equal(baseline.notes, 0);
  assert.equal(baseline.resources, 0);
  const changes = [];
  let note;
  changes.push(step('insert-note-and-resource', [
    { table: 'resources', operation: 'INSERT' },
    { table: 'notes', operation: 'INSERT' },
  ], () => {
    note = api.noteRepository.createNote({
      title: 'Registry contract note',
      content: `${term} original body`,
      tags: ['registry', 'fixture'],
    }, connection);
    api.crudResourceId = String(connection.pluck('SELECT resource_id FROM notes WHERE id=?1', [note.id]));
  }));
  changes.push(step('update-resource-data', [{ table: 'resources', operation: 'UPDATE' }], () => {
    assert.equal(api.resourceRepository.updateResourceData(
      connection,
      api.crudResourceId,
      `${term} revised body`,
    ), true);
  }));
  changes.push(step('update-note-metadata', [{ table: 'notes', operation: 'UPDATE' }], () => {
    api.noteRepository.updateNote(note.id, { title: 'Registry contract note updated' }, connection);
  }));

  const rebuild = (() => {
    connection.exec("INSERT INTO notes_fts(notes_fts) VALUES('delete-all');");
    const cleared = counts();
    connection.exec(api.ftsRebuildSql);
    const rebuilt = counts();
    assert.equal(cleared.directTermMatches, 0, 'FTS clear must remove direct MATCH rows');
    assert.equal(cleared.productionSearchHits > 0, true, 'production search should exercise its fallback after clear');
    assert.ok(rebuilt.directTermMatches > 0, 'FTS rebuild must restore direct MATCH rows');
    log('registry-contract.fts.rebuild', {
      unit: UNIT,
      method: `execute ${FTS_REBUILD_SOURCE}`,
      cleared,
      rebuilt,
    });
    return {
      method: `execute ${FTS_REBUILD_SOURCE} after delete-all`,
      cleared,
      rebuilt,
      fallbackObserved: cleared.productionSearchHits > 0 && cleared.directTermMatches === 0,
    };
  })();

  changes.push(step('soft-delete-note', [{ table: 'notes', operation: 'UPDATE' }], () => {
    api.noteRepository.deleteNote(note.id, connection);
    assert.equal(counts().directTermMatches, 0, 'soft delete must remove the note from direct FTS MATCH');
  }));
  changes.push(step('restore-note', [{ table: 'notes', operation: 'UPDATE' }], () => {
    api.noteRepository.restoreNote(note.id, connection);
    assert.ok(counts().directTermMatches > 0, 'restore must reinsert the note into direct FTS MATCH');
  }));
  changes.push(step('hard-delete-note-and-resource', [
    { table: 'notes', operation: 'DELETE' },
    { table: 'resources', operation: 'DELETE' },
  ], () => {
    api.noteRepository.purgeNote(note.id, connection);
    assert.equal(counts().notePresent, false, 'hard delete must remove the note');
  }));

  const final = counts();
  assert.equal(final.ftsRows, 0);
  assert.equal(final.directTermMatches, 0);
  assert.equal(final.notePresent, false);
  const capturedTables = connection.all('SELECT DISTINCT table_name FROM __change_log ORDER BY table_name')
    .map(row => String(row.table_name))
    .map(table => ({ table, category: api.classification.get(table)?.category ?? null }));
  assert.ok(capturedTables.every(row => row.category === 'RowSync'));
  return {
    fixture: 'fresh production vfs migrations plus production noteRepository and ResourceRepo',
    term,
    baseline,
    steps: changes,
    ftsRebuild: rebuild,
    final,
    capturedTables,
    pendingTargets: api.getPendingChanges(connection).entries
      .filter(change => ['notes', 'resources'].includes(change.table_name))
      .map(change => ({
        table: change.table_name,
        record: stableRecord(change.table_name, change.record_id),
        operation: change.operation,
      })),
  };
}

function configureReviewPlanFixture(api, connection, time) {
  connection.run(
    `INSERT INTO resources (id,hash,type,storage_mode,ref_count,created_at,updated_at)
     VALUES (?1,?2,'exam_sheet','inline',1,?3,?3)`,
    ['resource_s0_19', 'hash_s0_19', time],
  );
  connection.run(
    `INSERT INTO exam_sheets (id,resource_id,exam_name,status,temp_id,metadata_json,preview_json,created_at,updated_at)
     VALUES (?1,?2,?3,'completed',?4,'{}','{"pages":[]}',?5,?5)`,
    ['exam_s0_19', 'resource_s0_19', 'S0.19 migration fixture', 'tmp_s0_19', time],
  );
  connection.run(
    `INSERT INTO questions (id,exam_id,content,tags,created_at,updated_at)
     VALUES (?1,?2,?3,'[]',?4,?4)`,
    ['question_s0_19', 'exam_s0_19', 'FSRS repository round-trip vector', time],
  );
  const fixed = new Date(2026, 10, 15, 12, 0, 0, 0).getTime();
  const repo = new api.ReviewPlanRepo();
  const service = withFixedClock(fixed, () => {
    const service = new api.ReviewPlanService(connection, {
      questions: {
        getQuestion: (conn, id) => conn.get(
          'SELECT exam_id,tags FROM questions WHERE id=?1 AND deleted_at IS NULL',
          [id],
        ) ?? null,
        listQuestions: (conn, examId, _filters, page, pageSize) => {
          const total = Number(conn.pluck(
            'SELECT COUNT(*) FROM questions WHERE exam_id=?1 AND deleted_at IS NULL',
            [examId],
          ) ?? 0);
          const offset = (page - 1) * pageSize;
          const ids = conn.all(
            'SELECT id FROM questions WHERE exam_id=?1 AND deleted_at IS NULL ORDER BY created_at,id LIMIT ?2 OFFSET ?3',
            [examId, pageSize, offset],
          ).map(row => String(row.id));
          return { questions: ids.map(id => ({ id })), total, page, page_size: pageSize,
            has_more: offset + pageSize < total };
        },
      },
      reviewPlans: repo,
    });
    const plan = service.createReviewPlan('question_s0_19', 'exam_s0_19');
    service.processReview(plan.id, 4, null, 30);
    const answerTime = String(connection.pluck(
      'SELECT reviewed_at FROM review_history WHERE plan_id=?1 ORDER BY reviewed_at DESC LIMIT 1',
      [plan.id],
    ));
    return { service, clockMs: fixed, planId: plan.id, answerTime };
  });
  return { repo, ...service, seedTime: time };
}

function seedReviewPlanParents(targetStorage, time) {
  const connection = targetStorage.database('vfs');
  connection.run(
    `INSERT INTO resources (id,hash,type,storage_mode,ref_count,created_at,updated_at)
     VALUES (?1,?2,'exam_sheet','inline',1,?3,?3)`,
    ['resource_s0_19', 'hash_s0_19', time],
  );
  connection.run(
    `INSERT INTO exam_sheets (id,resource_id,exam_name,status,temp_id,metadata_json,preview_json,created_at,updated_at)
     VALUES (?1,?2,?3,'completed',?4,'{}','{"pages":[]}',?5,?5)`,
    ['exam_s0_19', 'resource_s0_19', 'S0.19 migration fixture', 'tmp_s0_19', time],
  );
  connection.run(
    `INSERT INTO questions (id,exam_id,content,tags,created_at,updated_at)
     VALUES (?1,?2,?3,'[]',?4,?4)`,
    ['question_s0_19', 'exam_s0_19', 'FSRS repository round-trip vector', time],
  );
}

function testPreflight(storage, connectionFactory, originalTriggerSqls) {
  const execute = sql => {
    const conn = connectionFactory();
    try { conn.exec(sql); }
    finally { conn.close(); }
  };
  const validate = () => {
    try {
      apiValidateSyncRegistryDrift(storage);
      return { rejected: false, message: null };
    } catch (error) {
      return { rejected: true, message: String(error.message) };
    }
  };
  const healthy = validate();
  assert.equal(healthy.rejected, false, `healthy production preflight failed: ${healthy.message}`);
  const negatives = [];
  const negative = (id, mutation, restoration, expected) => {
    execute(mutation);
    let rejected;
    try {
      rejected = validate();
      assert.equal(rejected.rejected, true, `${id}: mutation was not rejected`);
      assert.ok(rejected.message.includes(expected), `${id}: unexpected rejection: ${rejected.message}`);
    } finally {
      execute(restoration);
    }
    const restored = validate();
    assert.equal(restored.rejected, false, `${id}: restored trigger did not validate: ${restored.message}`);
    log('registry-contract.preflight.negative', {
      unit: UNIT,
      id,
      rejected,
      restoredAccepted: true,
    });
    negatives.push({ id, mutation, rejection: rejected, restoredAccepted: true, expected });
  };

  negative(
    'missing-update-trigger',
    'DROP TRIGGER trg__change_log_notes_update;',
    `${originalTriggerSqls['trg__change_log_notes_update']};`,
    'vfs.notes 缺少 __change_log update 触发器',
  );
  negative(
    'missing-insert-trigger',
    'DROP TRIGGER trg__change_log_notes_insert;',
    `${originalTriggerSqls['trg__change_log_notes_insert']};`,
    'vfs.notes 缺少 __change_log insert 触发器',
  );
  negative(
    'stray-local-trigger',
    `CREATE TRIGGER trg__change_log_review_history_insert AFTER INSERT ON review_history BEGIN
       INSERT INTO __change_log(table_name,record_id,operation) VALUES('review_history',NEW.id,'INSERT');
     END;`,
    'DROP TRIGGER trg__change_log_review_history_insert;',
    'vfs.review_history 存在 __change_log 触发器但 registry 不是 RowSync',
  );
  assert.equal(validate().rejected, false, 'final production preflight must be healthy');
  return { healthy: { rejected: false }, negatives, finalAccepted: true };
}

function testRemoteFailClosed(api, storage) {
  const connection = api.openConnection(storage, 'vfs', 's0-19-remote');
  let before;
  try {
    before = Number(connection.pluck('SELECT COUNT(*) FROM review_history'));
  } finally {
    connection.close();
  }
  const unregistered = {
    database_name: 'vfs',
    table_name: 'review_history',
    record_id: 'remote-registry-probe',
    operation: 'Insert',
    data: { id: 'remote-registry-probe', updated_at: '2026-11-15T00:00:00Z' },
    changed_at: '2026-11-15T00:00:00Z',
    source_device_id: 'remote-probe',
    source_seq: 1,
  };
  const result = api.applyToDatabases(storage, [unregistered], 'UseCloud', 's0-19-local');
  const afterConnection = api.openConnection(storage, 'vfs', 's0-19-remote-after');
  let after;
  let quarantine;
  try {
    after = Number(afterConnection.pluck('SELECT COUNT(*) FROM review_history'));
    quarantine = Number(afterConnection.pluck(
      "SELECT COUNT(*) FROM __sync_quarantine WHERE table_name='review_history' AND record_id='remote-registry-probe'",
    ));
  } finally {
    afterConnection.close();
  }
  assert.equal(result.total_success, 0);
  assert.equal(result.total_failed, 1);
  assert.equal(after, before);
  assert.ok(quarantine >= 1);
  log('registry-contract.remote.unregistered', {
    unit: UNIT,
    table: 'vfs.review_history',
    success: result.total_success,
    failed: result.total_failed,
    rowCountBefore: before,
    rowCountAfter: after,
    quarantined: quarantine,
  });

  let unknownDatabase;
  try {
    api.applyToDatabases(storage, [{
      database_name: 'future_db',
      table_name: 'future_rows',
      record_id: 'future-1',
      operation: 'Insert',
      data: { id: 'future-1', updated_at: '2026-11-15T00:00:00Z' },
      changed_at: '2026-11-15T00:00:00Z',
    }], 'UseCloud', 's0-19-local');
    unknownDatabase = { rejected: false, message: null };
  } catch (error) {
    unknownDatabase = { rejected: true, message: String(error.message) };
  }
  assert.equal(unknownDatabase.rejected, true);
  assert.ok(unknownDatabase.message.includes("未知数据库名称 'future_db'"));
  log('registry-contract.remote.unknown-database', { unit: UNIT, ...unknownDatabase });
  return {
    unregisteredTable: {
      table: 'vfs.review_history',
      success: result.total_success,
      failed: result.total_failed,
      rowCountBefore: before,
      rowCountAfter: after,
      quarantined: quarantine,
    },
    unknownDatabase: { database: 'future_db', table: 'future_rows', ...unknownDatabase },
  };
}

function compositePkVectors(api) {
  const vectors = [
    { id: 'single-column', table: 'notes', columns: ['id'], recordId: 'note_single', expected: ['note_single'] },
    { id: 'composite-json-object', table: 'chat_v2_session_mistakes', columns: ['session_id', 'mistake_id'], recordId: '{"session_id":"session-1","mistake_id":"mistake-1"}', expected: ['session-1', 'mistake-1'] },
    { id: 'composite-json-array', table: 'chat_v2_session_mistakes', columns: ['session_id', 'mistake_id'], recordId: '["session-2","mistake-2"]', expected: ['session-2', 'mistake-2'] },
    { id: 'composite-colon', table: 'review_session_mistakes', columns: ['session_id', 'mistake_id'], recordId: 'session-3:mistake-3', expected: ['session-3', 'mistake-3'] },
    { id: 'three-column-json', table: 'note_state', columns: ['note_id', 'state_type', 'state_key'], recordId: '{"note_id":"note-1","state_type":"ui","state_key":"sidebar"}', expected: ['note-1', 'ui', 'sidebar'] },
    { id: 'three-column-colon', table: 'note_state', columns: ['note_id', 'state_type', 'state_key'], recordId: 'note-2:ui:zoom', expected: ['note-2', 'ui', 'zoom'] },
    { id: 'lossless-large-integer', table: 'mis-cellaneous', columns: ['left', 'right'], recordId: '{"left":9007199254740993,"right":"x"}', expected: ['9007199254740993', 'x'] },
    { id: 'llm-usage-daily-json', table: 'llm_usage_daily', columns: ['date', 'caller_type', 'model', 'provider'], recordId: '{"date":"2026-11-15","caller_type":"chat","model":"m1","provider":"provider_x"}', expected: ['2026-11-15', 'chat', 'm1', 'provider_x'] },
    { id: 'llm-usage-daily-underscore', table: 'llm_usage_daily', columns: ['date', 'caller_type', 'model', 'provider'], recordId: '2026-11-15_chat_m1_provider_x', expected: ['2026-11-15', 'chat', 'm1', 'provider_x'] },
    { id: 'missing-object-field', table: 'chat_v2_session_mistakes', columns: ['session_id', 'mistake_id'], recordId: '{"session_id":"session-4"}', expectedError: '复合主键 record_id 缺少字段' },
    { id: 'wrong-arity', table: 'review_session_mistakes', columns: ['session_id', 'mistake_id'], recordId: 'single-value', expectedError: '复合主键 record_id 无法解析' },
    { id: 'invalid-llm-usage-daily', table: 'llm_usage_daily', columns: ['date', 'caller_type', 'model', 'provider'], recordId: 'invalid', expectedError: '复合主键 record_id 无法解析' },
  ];
  return vectors.map(vector => {
    let actual = null;
    let error = null;
    try {
      actual = api.parseRecordKeyValues(vector.table, vector.recordId, vector.columns);
    } catch (caught) {
      error = String(caught.message);
    }
    if (vector.expected !== undefined) assert.deepEqual(actual, vector.expected, `${vector.id}: production parser result`);
    else {
      assert.equal(actual, null, `${vector.id}: parser should reject`);
      assert.ok(error?.includes(vector.expectedError), `${vector.id}: unexpected error ${error}`);
    }
    log('registry-contract.pk-vector', {
      unit: UNIT,
      id: vector.id,
      table: vector.table,
      columns: vector.columns,
      recordId: vector.recordId,
      result: actual,
      error,
    });
    return {
      id: vector.id,
      table: vector.table,
      columns: vector.columns,
      recordId: vector.recordId,
      expected: vector.expected ?? { error: vector.expectedError },
      actual,
      error,
      passed: true,
    };
  });
}

function reviewPlanVector(connection, planId) {
  const stable = value => value === null ? null : (
    typeof value === 'number' && Number.isFinite(value) ? value : value
  );
  const fsrs = row => Object.fromEntries(REVIEW_PLAN_FSRS_COLUMNS.map(column => [column, stable(row[column])]));
  const plan = connection.get(
    "SELECT id,question_id,exam_id,status,total_reviews,total_correct,repetitions,interval_days,next_review_date,last_review_date,updated_at,fsrs_stability,fsrs_difficulty,fsrs_phase,fsrs_learning_step,fsrs_lapses,fsrs_due_at,fsrs_last_review_at FROM review_plans WHERE id=?1",
    [planId],
  );
  assert.ok(plan, 'review plan fixture is missing');
  const history = connection.get(
    'SELECT quality,passed,interval_after,repetitions_after FROM review_history WHERE plan_id=?1 ORDER BY reviewed_at DESC LIMIT 1',
    [planId],
  );
  assert.ok(history, 'review history fixture is missing');
  const fsrsColumns = tableInfoColumns(connection, 'review_plans').map(column => column.name)
    .filter(name => name.startsWith('fsrs_'));
  assert.deepEqual(fsrsColumns, REVIEW_PLAN_FSRS_COLUMNS);
  for (const column of REVIEW_PLAN_FSRS_COLUMNS) {
    assert.equal(plan[column] === undefined, false, `${column}: missing from real review_plans row`);
  }
  return {
    planId: '<review-plan>',
    questionId: plan.question_id,
    examId: plan.exam_id,
    status: plan.status,
    totalReviews: Number(plan.total_reviews),
    totalCorrect: Number(plan.total_correct),
    repetitions: Number(plan.repetitions),
    intervalDays: Number(plan.interval_days),
    nextReviewDate: plan.next_review_date,
    lastReviewDate: plan.last_review_date,
    updatedAt: '<rfc3339>',
    updatedAtKind: 'runtime clock; validated and normalized',
    fsrs: fsrs(plan),
    history: {
      quality: Number(history.quality),
      passed: Number(history.passed) !== 0,
      intervalAfter: Number(history.interval_after),
      repetitionsAfter: Number(history.repetitions_after),
      reviewedAt: '<rfc3339>',
    },
  };
}

function reviewPlanSyncVector(api, sourceStorage, targetStorage, planId) {
  const sourcePlan = api.reviewPlans.getPlan(sourceStorage.database('vfs'), planId);
  assert.ok(sourcePlan, 'source review plan missing');
  const sourceState = api.reviewPlans.getFsrsStates(sourceStorage.database('vfs'), [sourcePlan.id]).get(sourcePlan.id);
  assert.ok(sourceState, 'source FSRS state missing');
  const sourceUnknown = api.readUnknownFields(sourceStorage.database('vfs'), 'review_plans', sourcePlan.id);
  assert.equal(api.stringifyJsonLossless(sourceUnknown.future_rating), '9007199254740993',
    'source unknown bigint was not seeded before sync');
  assert.equal(sourceUnknown.future_note, null, 'source unknown null was not seeded before sync');
  const pending = api.getPendingChanges(sourceStorage.database('vfs'));
  const planChanges = pending.entries.filter(entry => entry.table_name === 'review_plans');
  assert.ok(planChanges.length > 0, 'review_plans must produce change-log rows');
  const collected = api.enrichChangesWithData(sourceStorage.database('vfs'), planChanges);
  const change = collected.at(-1);
  assert.ok(change, 'review_plans enriched change missing');
  assert.ok(['Insert', 'Update'].includes(change.operation), 'review_plans enriched change operation');
  assert.equal(change.record_id, sourcePlan.id);
  assert.equal(api.stringifyJsonLossless(change.data.future_rating), '9007199254740993',
    'collected change lost the unknown bigint');
  assert.equal(change.data.future_note, null, 'collected change lost the unknown null');
  const collectedText = api.stringifyJsonLossless(change.data);
  for (const column of REVIEW_PLAN_FSRS_COLUMNS) {
    const expected = column === 'fsrs_stability' ? sourceState.stability
      : column === 'fsrs_difficulty' ? sourceState.difficulty
        : column === 'fsrs_phase' ? sourceState.phase
          : column === 'fsrs_learning_step' ? sourceState.learning_step
            : column === 'fsrs_lapses' ? sourceState.lapses
              : column === 'fsrs_due_at' ? sourceState.due_at : sourceState.last_review_at;
    assert.equal(api.stringifyJsonLossless(change.data[column]), api.stringifyJsonLossless(expected),
      `${column}: collected value drift`);
  }
  const wire = api.serializeSyncChange({ ...change, data: api.parseJsonLossless(collectedText), database_name: 'vfs' });
  const parsed = api.parseSyncChange(wire);
  assert.equal(api.serializeSyncChange(parsed), wire);
  assert.equal(parsed.table_name, change.table_name);
  assert.equal(parsed.record_id, change.record_id);
  assert.equal(parsed.operation, change.operation);
  assert.equal(parsed.database_name, 'vfs');
  for (const column of REVIEW_PLAN_FSRS_COLUMNS) {
    assert.equal(parsed.data[column] === undefined, false, `${column}: missing from parsed wire data`);
    assert.equal(api.stringifyJsonLossless(parsed.data[column]),
      api.stringifyJsonLossless(change.data[column]), `${column}: wire value drift`);
  }
  assert.equal(api.stringifyJsonLossless(parsed.data.future_rating), '9007199254740993',
    'parsed wire change lost the unknown bigint');
  assert.equal(parsed.data.future_note, null, 'parsed wire change lost the unknown null');
  const result = api.applyToDatabases(targetStorage, [parsed], 'UseCloud', 's0-19-target');
  const targetQuarantine = targetStorage.database('vfs').get(
    'SELECT error FROM __sync_quarantine WHERE table_name=?1 AND record_id=?2 ORDER BY id DESC LIMIT 1',
    [parsed.table_name, parsed.record_id],
  );
  assert.deepEqual({ success: result.total_success, failed: result.total_failed, skipped: result.total_skipped },
    { success: 1, failed: 0, skipped: 0 }, `target apply failed: ${targetQuarantine?.error ?? 'unknown'}`);
  const targetPlan = api.reviewPlans.getPlan(targetStorage.database('vfs'), sourcePlan.id);
  assert.ok(targetPlan, 'target review plan missing after apply');
  const targetState = api.reviewPlans.getFsrsStates(targetStorage.database('vfs'), [targetPlan.id]).get(targetPlan.id);
  assert.ok(targetState, 'target FSRS state missing after apply');
  const targetConnection = targetStorage.database('vfs');
  const targetRecord = api.getRecordData(targetConnection, 'review_plans', targetPlan.id, 'id');
  const targetUnknown = api.readUnknownFields(targetConnection, 'review_plans', targetPlan.id);
  assert.ok(targetRecord && typeof targetRecord === 'object' && !Array.isArray(targetRecord));
  assert.equal(api.stringifyJsonLossless(targetUnknown.future_rating), '9007199254740993',
    'target vault lost the unknown bigint');
  assert.equal(targetUnknown.future_note, null, 'target vault lost the unknown null');
  assert.equal(api.stringifyJsonLossless(targetRecord.future_rating), '9007199254740993',
    'target getRecordData lost the unknown bigint');
  assert.equal(targetRecord.future_note, null, 'target getRecordData lost the unknown null');
  const expectedUnknown = api.stringifyJsonLossless(api.canonicalizeSyncValueForCompare({
    future_rating: 9007199254740993n,
    future_note: null,
  }));
  const selectedSourceUnknown = { future_rating: sourceUnknown.future_rating, future_note: sourceUnknown.future_note };
  const selectedTargetUnknown = { future_rating: targetUnknown.future_rating, future_note: targetUnknown.future_note };
  const sourceUnknownCanonical = api.stringifyJsonLossless(api.canonicalizeSyncValueForCompare(selectedSourceUnknown));
  const targetUnknownCanonical = api.stringifyJsonLossless(api.canonicalizeSyncValueForCompare(selectedTargetUnknown));
  const valuesRetained = sourceUnknownCanonical === expectedUnknown && targetUnknownCanonical === expectedUnknown;
  assert.equal(valuesRetained, true, 'unknown field values were not retained through sync');
  assert.deepEqual({
    stats: targetPlan.total_reviews,
    correct: targetPlan.total_correct,
    repetitions: targetPlan.repetitions,
    status: targetPlan.status,
    fsrs: targetState,
  }, {
    stats: sourcePlan.total_reviews,
    correct: sourcePlan.total_correct,
    repetitions: sourcePlan.repetitions,
    status: sourcePlan.status,
    fsrs: sourceState,
  });
  log('registry-contract.review-plan.sync', {
    unit: UNIT,
    planId: sourcePlan.id,
    changes: collected.length,
    wireBytes: wire.length,
    target: {
      totalReviews: targetPlan.total_reviews,
      totalCorrect: targetPlan.total_correct,
      repetitions: targetPlan.repetitions,
      intervalDays: targetPlan.interval_days,
      status: targetPlan.status,
      fsrsPhase: targetState.phase,
      dueAtPresent: targetState.due_at !== null,
      unknownFields: Object.keys(targetUnknown).sort(),
    },
  });
  return {
    collection: 'production getPendingChanges plus enrichChangesWithData on the real vfs review_plans row',
    apply: 'production applyDownloadedChangesToDatabases(UseCloud) in a separate target storage',
    readback: 'production ReviewPlanRepo getPlan plus getFsrsStates on target vfs',
    changes: collected.length,
    operation: change.operation,
    wire: {
      bytes: wire.length,
      parsedEquals: true,
      unknownFieldsIgnoredByChangeParser: true,
    },
    source: {
      totalReviews: sourcePlan.total_reviews,
      totalCorrect: sourcePlan.total_correct,
      repetitions: sourcePlan.repetitions,
      intervalDays: sourcePlan.interval_days,
      status: sourcePlan.status,
      fsrs: sourceState,
    },
    target: {
      totalReviews: targetPlan.total_reviews,
      totalCorrect: targetPlan.total_correct,
      repetitions: targetPlan.repetitions,
      intervalDays: targetPlan.interval_days,
      status: targetPlan.status,
      fsrs: targetState,
    },
    unknownFields: {
      seededBeforeSync: Object.keys(sourceUnknown).sort(),
      collected: Object.keys(change.data).filter(key => ['future_rating', 'future_note'].includes(key)).sort(),
      parsed: Object.keys(parsed.data).filter(key => ['future_rating', 'future_note'].includes(key)).sort(),
      targetVault: Object.keys(targetUnknown).sort(),
      targetRecord: ['future_note', 'future_rating'].filter(key => targetRecord[key] !== undefined).sort(),
      sourceCanonicalSha256: sha256(bytes(sourceUnknownCanonical)),
      targetCanonicalSha256: sha256(bytes(targetUnknownCanonical)),
      valuesRetained,
    },
  };
}

function unknownFieldsHashVector(api, storage, planId) {
  const connection = storage.database('vfs');
  api.vaultUnknownFieldsWithVersion(connection, 'review_plans', planId, {
    future_rating: { value: 9007199254740993n, hlc: '000000000000100-00001', dev: 's0-19-peer' },
    future_note: { value: null, hlc: '000000000000100-00001', dev: 's0-19-peer' },
  });
  const roundTrip = api.readUnknownFieldsForRoundTrip(connection, 'review_plans', planId);
  assert.equal(Object.hasOwn(roundTrip.values, 'future_rating'), true);
  assert.equal(Object.hasOwn(roundTrip.values, 'future_note'), true);
  assert.equal(roundTrip.values.future_rating, 9007199254740993n);
  assert.equal(roundTrip.values.future_note, null);
  const stored = connection.get(
    'SELECT fields_json FROM __sync_unknown_fields WHERE table_name=?1 AND record_id=?2',
    ['review_plans', planId],
  );
  assert.ok(stored);
  assert.equal(api.stringifyJsonLossless(roundTrip.values.future_rating), '9007199254740993');
  assert.equal(roundTrip.values.future_note, null);
  const canonical = api.canonicalizeSyncValueForCompare(roundTrip.values);
  const canonicalText = api.stringifyJsonLossless(canonical);
  const fieldsHash = sha256(bytes(canonicalText));
  const storedFieldsSha256 = sha256(bytes(String(stored.fields_json)));
  log('registry-contract.unknown-fields', {
    unit: UNIT,
    table: 'review_plans',
    recordId: planId,
    fields: Object.keys(roundTrip.values).sort(),
    fieldsSha256: fieldsHash,
    biginteger: api.stringifyJsonLossless(roundTrip.values.future_rating),
    nullPreserved: roundTrip.values.future_note === null,
  });
  return {
    table: 'review_plans',
    recordId: '<review-plan>',
    fields: Object.keys(roundTrip.values).sort(),
    values: {
      future_rating: { kind: 'bigint', text: api.stringifyJsonLossless(roundTrip.values.future_rating) },
      future_note: { kind: 'null', text: api.stringifyJsonLossless(roundTrip.values.future_note) },
    },
    canonicalText,
    canonicalSha256: fieldsHash,
    storedFieldsSha256,
  };
}

function probeContractChange(api, storage, id, buildChange, clockMs, planId) {
  const change = buildChange();
  let result = null;
  let error = null;
  try {
    result = withFixedClock(clockMs, () =>
      api.applyToDatabases(storage, [change], 'UseCloud', 's0-19-negative'));
  } catch (caught) {
    error = String(caught.message);
  }
  const rejected = error !== null || (result.total_success === 0 && result.total_failed === 1);
  const connection = storage.database('vfs');
  const row = connection.get('SELECT id FROM review_plans WHERE id=?1', [planId]);
  assert.ok(row, `${id}: base row disappeared`);
  const quarantine = connection.get(
    'SELECT error,payload_json FROM __sync_quarantine WHERE table_name=?1 AND record_id=?2 ORDER BY id DESC LIMIT 1',
    [change.table_name, change.record_id],
  );
  const normalizePlanId = value => typeof value === 'string' ? value.replaceAll(planId, '<review-plan>') : value;
  const resultMessage = error ?? result?.db_errors?.map(([db, text]) => `${db}: ${text}`).join('; ') ?? null;
  const quarantineError = normalizePlanId(quarantine?.error ?? null);
  const knownColumns = new Set(api.tableColumnNames(connection, 'review_plans'));
  const unknownKeys = change.data === null ? [] : Object.keys(change.data).filter(key => !knownColumns.has(key));
  const vaultKeys = Object.keys(api.readUnknownFields(connection, 'review_plans', planId)).sort();
  log('registry-contract.probe', {
    unit: UNIT, id, rejected, message: error ?? result?.db_errors, unknownKeys, vaultKeys,
    quarantineError: quarantine?.error ?? null,
  });
  return {
    id,
    rejected,
    path: error === null ? 'apply result' : 'apply throw',
    message: normalizePlanId(resultMessage),
    success: result?.total_success ?? null,
    failed: result?.total_failed ?? null,
    skipped: result?.total_skipped ?? null,
    quarantineError,
  };
}

function registryDriftProbes(api, storage, clockMs, planId) {
  const connection = storage.database('vfs');
  const before = api.getRecordData(connection, 'review_plans', planId, 'id');
  assert.ok(before && typeof before === 'object' && !Array.isArray(before));
  const knownColumns = new Set(api.tableColumnNames(connection, 'review_plans'));
  const knownEntry = Object.fromEntries(Object.entries(before).filter(([key]) => knownColumns.has(key)));
  const beforeVault = api.readUnknownFields(connection, 'review_plans', planId);
  const changedAt = withFixedClock(clockMs, () => new Date().toISOString());
  const baseChange = entry => ({
    table_name: 'review_plans',
    record_id: planId,
    operation: 'Update',
    data: entry,
    changed_at: changedAt,
    database_name: 'vfs',
    source_device_id: 's0-19-negative',
    source_seq: 1,
  });
  const probe = (id, mutate) => probeContractChange(api, storage, id, () => {
    const change = baseChange({ ...knownEntry });
    mutate(change);
    return change;
  }, clockMs, planId);
  const results = [
    probe('unknown-column', change => {
      change.data.fsrs_future_rating = 4;
    }),
    probe('primary-key-drift', change => { change.record_id = 'rp_s0_19_missing'; }),
    probe('clock-future-updated-at', change => {
      change.data.updated_at = '2099-01-01T00:00:00Z';
    }),
  ];
  const futureColumn = connection.get(
    'SELECT fields_json FROM __sync_unknown_fields WHERE table_name=?1 AND record_id=?2',
    ['review_plans', planId],
  );
  const futureFields = futureColumn === undefined
    ? {}
    : api.parseJsonLossless(String(futureColumn.fields_json));
  const afterVault = api.readUnknownFields(connection, 'review_plans', planId);
  const unknownColumnResult = results.find(row => row.id === 'unknown-column');
  const clockResult = results.find(row => row.id === 'clock-future-updated-at');
  const clockRow = connection.get(
    'SELECT updated_at FROM review_plans WHERE id=?1',
    [planId],
  );
  results.push({
    id: 'observed-behavior',
    columnPreserved: Object.hasOwn(futureFields, 'fsrs_future_rating')
      && api.stringifyJsonLossless(futureFields.fsrs_future_rating) === '4',
    unknownColumnRejected: unknownColumnResult.rejected,
    unknownColumnQuarantineError: unknownColumnResult.quarantineError,
    unknownColumnVaultBefore: Object.keys(beforeVault).sort(),
    unknownColumnVaultAfter: Object.keys(afterVault).sort(),
    unknownColumnStored: Object.hasOwn(futureFields, 'fsrs_future_rating'),
    clockRejected: clockResult.rejected,
    clockRowPreserved: clockRow?.updated_at !== '2099-01-01 00:00:00',
    semantics: 'recorded as observed; no production behavior is forced to match a test expectation',
  });
  return results;
}

function reviewPlanContract(api, connection, sourceStorage, targetStorage, clockMs, answerTime, planId) {
  const vector = reviewPlanVector(connection, planId);
  const probes = registryDriftProbes(api, sourceStorage, clockMs, planId);
  const unknownFields = unknownFieldsHashVector(api, sourceStorage, planId);
  const sync = reviewPlanSyncVector(api, sourceStorage, targetStorage, planId);
  return {
    migration: 'V20261115__review_plans_fsrs.sql',
    schemaVersion: Number(connection.pluck('SELECT MAX(version) FROM refinery_schema_history')),
    vfsMigrationCount: Number(connection.pluck('SELECT COUNT(*) FROM refinery_schema_history')),
    columns: REVIEW_PLAN_FSRS_COLUMNS,
    fixtures: {
      seeded: ['resources', 'exam_sheets', 'questions', 'review_plans'],
      clock: 'Date.now and performance are fixed only for the synchronous production review scoring and probe changed_at values; target apply uses the real clock and the artifact normalizes runtime timestamps after validating them',
      reviewCreatedAt: answerTime,
    },
    vector,
    probes,
    unknownFields,
    sync,
  };
}

let apiValidateSyncRegistryDrift;

async function build() {
  const started = performance.now();
  const [
    classificationModule,
    storageModule,
    preflightModule,
    connectionModule,
    changeLogModule,
    notesModule,
    noteResourceModule,
    resourceModule,
    ownerWritesModule,
    embeddingModule,
    indexUnitsModule,
    foldersModule,
    recordDataModule,
    changePayloadModule,
    reviewPlanModule,
    reviewPlanServiceModule,
    losslessJsonModule,
    unknownFieldsModule,
    questionModule,
    sqliteValuesModule,
    applyDatabasesModule,
    localRuntimeModule,
    nodeLocalRuntimeModule,
  ] = await Promise.all([
    loadDesktop('host/plugins/sync/src/classification.ts'),
    loadDesktop('host/platform/storage/service.ts'),
    loadDesktop('host/plugins/sync/repository/registry-preflight.ts'),
    loadDesktop('host/platform/storage/connection.ts'),
    loadDesktop('host/plugins/sync/repository/change-log.ts'),
    loadDesktop('host/plugins/notes/repository/notes.ts'),
    loadDesktop('host/plugins/notes/repository/resource-deps.ts'),
    loadDesktop('host/plugins/vfs-repos/repository/resources.ts'),
    loadDesktop('host/plugins/vfs-repos/repository/note-owner-writes.ts'),
    loadDesktop('host/plugins/vfs-repos/repository/embedding-repo.ts'),
    loadDesktop('host/plugins/vfs-repos/repository/index-units.ts'),
    loadDesktop('host/plugins/vfs-repos/repository/folders.ts'),
    loadDesktop('host/plugins/sync/repository/record-data.ts'),
    loadDesktop('host/plugins/sync/src/change-payload.ts'),
    loadDesktop('host/plugins/vfs-repos/repository/review-plans.ts'),
    loadDesktop('host/plugins/review/src/review-plan.ts'),
    loadDesktop('host/platform/util/lossless-json.ts'),
    loadDesktop('host/plugins/sync/repository/unknown-fields.ts'),
    loadDesktop('host/plugins/vfs-repos/repository/questions.ts'),
    loadDesktop('host/plugins/sync/repository/sqlite-values.ts'),
    loadDesktop('host/plugins/sync/repository/apply-databases.ts'),
    loadDesktop('host/plugins/sync/src/local-runtime.ts'),
    loadDesktop('host/plugins/sync/src/local-runtime.node.ts'),
  ]);
  apiValidateSyncRegistryDrift = preflightModule.validateSyncRegistryDrift;
  const classificationBaseline = JSON.parse(readFileSync(resolve(root, 'config/baseline/classification.json'), 'utf8'));
  const schema = JSON.parse(readFileSync(resolve(root, 'config/baseline/schema.json'), 'utf8'));
  assert.equal(classificationBaseline.unit, 'S0.09');
  assert.equal(schema.unit, 'S0.08');
  assert.equal(classificationBaseline.registry.length, 124);

  const live = classificationModule.syncClassificationRegistry();
  assert.equal(live.length, 124, 'live classification registry count');
  const baselineByKey = new Map(classificationBaseline.registry.map(entry => [`${entry.database}.${entry.table}`, entry]));
  const liveKeys = live.map(entry => `${entry.database}.${entry.table_name}`);
  assert.equal(new Set(liveKeys).size, 124, 'duplicate live registry key');
  const classificationDivergences = [];
  for (const entry of live) {
    const key = `${entry.database}.${entry.table_name}`;
    const expected = baselineByKey.get(key);
    assert.ok(expected, `${key}: missing from S0.09 classification`);
    const found = differences(canonicalClassification(entry, classificationModule), normalizedClassification(expected));
    if (found.length > 0) classificationDivergences.push({ key, differences: found });
  }
  assert.deepEqual(classificationDivergences, []);
  log('registry-contract.classification.matched', {
    unit: UNIT,
    live: live.length,
    frozen: classificationBaseline.registry.length,
    divergences: classificationDivergences.length,
  });

  const temp = mkdtempSync(join(tmpdir(), 's0-19-registry-'));
  const storage = storageModule.createStorageService({
    baseDir: temp,
    dataLocalDir: temp,
    migrationsDir: resolve(root, MIGRATIONS_DIR),
  });
  const api = {
    ...classificationModule,
    ...preflightModule,
    ...sqliteValuesModule,
    ...recordDataModule,
    ...changePayloadModule,
    ...reviewPlanModule,
    ...reviewPlanServiceModule,
    ...losslessJsonModule,
    ...unknownFieldsModule,
    ...questionModule,
  };
  try {
    localRuntimeModule.setLocalRuntime(nodeLocalRuntimeModule.nodeLocalRuntime);
    const first = await storage.migrations.runAll();
    assert.equal(first.success, true, 'fresh production migration');
    const replay = await storage.migrations.runAll();
    assert.equal(replay.success, true, 'production migration replay');
    log('registry-contract.storage.migrated', {
      unit: UNIT,
      success: first.success,
      replaySuccess: replay.success,
      databases: first.databases.map(row => ({
        id: row.id,
        fromVersion: row.fromVersion,
        toVersion: row.toVersion,
        appliedCount: row.appliedCount,
      })),
    });

    const metadata = {};
    const triggerCatalog = {};
    const databaseTables = {};
    const integrity = {};
    const connectionFactory = (database, label = `${database}-registry`) => new connectionModule.Connection(
      storage.pathOf(database),
      { label, profile: { foreignKeys: true } },
    );
    for (const database of DATABASES) {
      const conn = connectionFactory(database);
      try {
        const names = allTables(conn);
        databaseTables[database] = new Set(names);
        metadata[database] = new Map(names.map(name => [name, tableMetadata(conn, name)]));
        triggerCatalog[database] = triggerRows(conn);
        integrity[database] = {
          integrityCheck: conn.pluck('PRAGMA integrity_check'),
          foreignKeyViolations: conn.all('PRAGMA foreign_key_check').length,
          tableCount: names.length,
          triggerCount: triggerCatalog[database].length,
        };
      } finally {
        conn.close();
      }
    }

    const shadowTables = new Set();
    for (const database of DATABASES) {
      for (const table of databaseTables[database]) {
        for (const suffix of FTS_SHADOW_SUFFIXES) {
          if (table.endsWith(suffix)) {
            const base = table.slice(0, -suffix.length);
            if (databaseTables[database].has(base)) shadowTables.add(table);
          }
        }
      }
    }

    const frozenDivergences = [];
    for (const entry of live) {
      const frozen = frozenTable(schema, entry.database, entry.table_name);
      const liveTable = metadata[entry.database].get(entry.table_name) ?? { exists: false, columns: [] };
      const comparison = schemaComparison(liveTable, frozen);
      comparison.triggersMatch = json(
        triggerCatalog[entry.database]
          .filter(row => row.table === entry.table_name)
          .map(row => ({ name: row.name, sql: row.sql }))
          .sort((left, right) => left.name.localeCompare(right.name)),
      ) === json(frozen.triggers
        .map(row => ({ name: row.name, sql: String(row.sql ?? '') }))
        .sort((left, right) => left.name.localeCompare(right.name)));
      if (!comparison.tablesMatch || !comparison.columnsMatch || !comparison.triggersMatch) {
        frozenDivergences.push({ key: `${entry.database}.${entry.table_name}`, comparison });
      }
    }
    assert.deepEqual(frozenDivergences, [], 'live table_info differs from S0.08 baseline');
    log('registry-contract.schema.matched', {
      unit: UNIT,
      entries: live.length,
      divergences: frozenDivergences.length,
      tableInfo: 'fresh PRAGMA table_info compared directly with S0.08 baseline cid/type/default/pk',
      tableXinfo: 'separate PRAGMA table_xinfo hidden/generated evidence; not compared to S0.08 cid',
      databases: DATABASES.map(database => ({ database, ...integrity[database] })),
    });

    const classificationLookup = new Map(live.map(entry => [entry.table_name, entry]));
    const connection = storage.database('vfs');
    const resourceRepository = new resourceModule.ResourceRepo();
    const targetTemp = mkdtempSync(join(tmpdir(), 's0-19-target-'));
    const targetStorage = storageModule.createStorageService({
      baseDir: targetTemp,
      dataLocalDir: targetTemp,
      migrationsDir: resolve(root, MIGRATIONS_DIR),
    });
    let crud;
    let preflight;
    let remote;
    let reviewPlans;
    let reviewClockMs;
    let reviewPlanId;
    try {
      noteResourceModule.configureNoteResourceRepos({
        noteOwnerWrites: new ownerWritesModule.NoteOwnerWritesRepo(),
        resources: resourceRepository,
        indexStates: new embeddingModule.IndexStateRepo(),
        embeddingUnits: new indexUnitsModule.IndexUnitRepo(),
        folders: new foldersModule.FolderRepo(),
      });
      api.noteRepository = notesModule.noteRepository;
      api.resourceRepository = resourceRepository;
      api.reviewPlans = new reviewPlanModule.ReviewPlanRepo();
      api.getPendingChanges = changeLogModule.getPendingChanges;
      api.classification = classificationLookup;
      api.ftsRebuildSql = readFileSync(resolve(desktop, FTS_REBUILD_SOURCE), 'utf8');
      crud = createCrudHarness(api, connection);
      const reviewFixture = configureReviewPlanFixture(api, connection, '2026-11-15T00:00:00Z');
      reviewClockMs = reviewFixture.clockMs;
      api.reviewAnswerTime = reviewFixture.answerTime;
      reviewPlanId = reviewFixture.planId;
      const triggerSqls = Object.fromEntries(
        triggerCatalog.vfs
          .filter(row => ['trg__change_log_notes_insert', 'trg__change_log_notes_update'].includes(row.name))
          .map(row => [row.name, row.sql.replace(/^CREATE TRIGGER (?:IF NOT EXISTS )?/i, 'CREATE TRIGGER ')]),
      );
      preflight = testPreflight(storage, () => connectionFactory('vfs', 's0-19-preflight'), triggerSqls);
      api.applyToDatabases = applyDatabasesModule.applyDownloadedChangesToDatabases;
      api.openConnection = (service, database, label) => new connectionModule.Connection(
        service.pathOf(database),
        { label, profile: { foreignKeys: true } },
      );
      remote = testRemoteFailClosed(api, storage);
      const targetMigration = await targetStorage.migrations.runAll();
      assert.equal(targetMigration.success, true, 'target storage fresh migration');
      assert.equal(targetMigration.databases.find(row => row.id === 'vfs').toVersion, 20261115);
      seedReviewPlanParents(targetStorage, '2026-11-15T00:00:00Z');
      reviewPlans = reviewPlanContract(api, connection, storage, targetStorage, reviewClockMs,
        api.reviewAnswerTime, reviewPlanId);
    } finally {
      noteResourceModule.configureNoteResourceRepos(null);
      targetStorage.shutdown();
      rmSync(targetTemp, { recursive: true, force: true });
    }

    const registry = live.map(entry => {
      const entryConnection = connectionFactory(entry.database, `${entry.database}-${entry.table_name}`);
      try {
        api.connection = entryConnection;
        return registryEntry(
          api,
          classificationBaseline,
          entry,
          metadata[entry.database],
          triggerCatalog[entry.database],
          shadowTables,
        );
      } finally {
        api.connection = null;
        entryConnection.close();
      }
    });
    assert.equal(registry.length, 124);
    assert.equal(new Set(registry.map(row => `${row.database}.${row.table}`)).size, 124);
    assert.ok(registry.every(row => row.category === 'Deprecated' ? !row.schema.present : row.schema.present));
    assert.ok(registry.every(row => row.triggers.allRequiredPresent));
    assert.ok(registry
      .filter(row => ['LocalRuntime', 'BackupOnly', 'DerivedRebuild'].includes(row.category))
      .every(row => !row.triggers.strayCapture && !row.triggers.capturedOps.length));
    const anomalies = registry.filter(row => Object.values(row.anomaly).some(Boolean)).map(row => ({
      key: `${row.database}.${row.table}`,
      anomaly: row.anomaly,
    }));
    assert.deepEqual(anomalies, [
      {
        key: 'vfs.automation_todo_deliveries',
        anomaly: {
          missingSchema: false,
          logicalVsSqliteMismatch: false,
          logicalVsProductionMismatch: false,
          uniqueDeclaredWithoutSqliteMatch: true,
          missingRequiredTrigger: false,
          strayCapture: false,
        },
      },
      {
        key: 'vfs.note_document_revisions',
        anomaly: {
          missingSchema: false,
          logicalVsSqliteMismatch: true,
          logicalVsProductionMismatch: false,
          uniqueDeclaredWithoutSqliteMatch: false,
          missingRequiredTrigger: false,
          strayCapture: false,
        },
      },
    ]);
    const categoryCounts = Object.fromEntries(
      [...new Set(registry.map(row => row.category))].sort()
        .map(category => [category, registry.filter(row => row.category === category).length]),
    );
    log('registry-contract.registry.classified', {
      unit: UNIT,
      entries: registry.length,
      categoryCounts,
      anomalies,
      shadowTables: shadowTables.size,
    });
    const pkParsing = compositePkVectors(api);

    const sources = [
      sourceHash('host/plugins/sync/src/classification.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/src/classification-registry.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/src/classification-data/vfs-core.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/src/classification-data/vfs-notes.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/src/classification-data/chat-v2.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/src/classification-data/mistakes.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/src/classification-data/llm-usage.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/repository/registry-preflight.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/repository/change-log.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/repository/sqlite-values.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/repository/apply-record.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/repository/apply-batch.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/repository/apply-databases.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/src/local-runtime.ts', desktop, 'desktop'),
      sourceHash('host/plugins/sync/src/local-runtime.node.ts', desktop, 'desktop'),
      sourceHash('host/platform/storage/service.ts', desktop, 'desktop'),
      sourceHash('host/platform/storage/connection.ts', desktop, 'desktop'),
      sourceHash('host/plugins/notes/repository/notes.ts', desktop, 'desktop'),
      sourceHash('host/plugins/notes/repository/resource-deps.ts', desktop, 'desktop'),
      sourceHash('host/plugins/vfs-repos/repository/resources.ts', desktop, 'desktop'),
      sourceHash('host/plugins/vfs-repos/repository/note-owner-writes.ts', desktop, 'desktop'),
      sourceHash('host/plugins/vfs-repos/repository/embedding-repo.ts', desktop, 'desktop'),
      sourceHash('host/plugins/vfs-repos/repository/index-units.ts', desktop, 'desktop'),
      sourceHash('host/plugins/vfs-repos/repository/folders.ts', desktop, 'desktop'),
      sourceHash(FTS_REBUILD_SOURCE, desktop, 'desktop'),
      sourceHash('resources/app/migrations/vfs/V20260131__add_change_log.sql', desktop, 'desktop'),
      sourceHash('config/baseline/schema.json', root, 'android'),
      sourceHash('config/baseline/classification.json', root, 'android'),
    ];
    log('registry-contract.sources.hashed', {
      unit: UNIT,
      sources: sources.length,
      combinedSha256: sha256(sources.map(source => `${source.origin}:${source.file}:${source.sha256}`).join('\n')),
    });

    const result = {
      formatVersion: 1,
      format: FORMAT,
      unit: UNIT,
      generatedAt: new Date().toISOString(),
      scope: {
        databases: DATABASES,
        registryEntries: registry.length,
        categoryCounts,
        source: 'live syncClassificationRegistry joined with fresh production refinery migrations and S0.08 schema',
        platformNeutral: 'No temp paths, device identity, capture timestamps, or host-specific paths are recorded.',
        excluded: [
          'file/tombstone manifests and DSBK encryption (S0.18)',
          'S0.20 schema release windows',
          'Android Kotlin registry implementation',
        ],
      },
      producer: {
        storageService: 'createStorageService({ migrationsDir: config/schema/history })',
        migrations: 'fresh runAll plus second idempotent runAll',
        introspection: 'production Connection PRAGMA table_xinfo/index_list/index_info/foreign_key_list',
        classification: 'production syncClassificationRegistry/getBusinessUniqueKeys/isExcludedFromChecksum/checksumTables',
        preflight: 'production validateSyncRegistryDrift with production node local runtime',
        apply: 'production applyDownloadedChangesToDatabases',
        crud: 'production noteRepository plus ResourceRepo/NoteOwnerWritesRepo/FolderRepo',
        fts: `execute ${FTS_REBUILD_SOURCE} after delete-all`,
        pkParsing: 'production parseRecordKeyValues',
        reviewPlans: 'production ReviewPlanRepo/ReviewPlanService, getPendingChanges/enrichChangesWithData, serializeSyncChange/parseSyncChange, applyDownloadedChangesToDatabases',
      },
      migrations: {
        replayIdempotent: true,
        firstRun: normalizeReport(first),
        secondRun: normalizeReport(replay),
        databases: DATABASES.map(database => ({
          database,
          version: Number(storage.database(database).pluck('SELECT MAX(version) FROM refinery_schema_history')),
          schemaFingerprint: schema.databases.find(db => db.id === database).fingerprint,
          tables: databaseTables[database].size,
          triggers: triggerCatalog[database].length,
          integrity: integrity[database],
        })),
      },
      classification: {
        baseline: 'config/baseline/classification.json',
        liveEntries: live.length,
        comparedFields: ['database', 'table', 'primaryKey', 'category', 'conflictPolicy', 'businessUniqueKeys', 'hasJsonBlobs', 'mergeNotes'],
        divergences: classificationDivergences,
        categoryCounts,
      },
      schemaBaseline: {
        file: 'config/baseline/schema.json',
        unit: schema.unit,
        divergences: frozenDivergences,
        compared: [
          'PRAGMA table_info visible columns with cid/type/default/pk',
          'trigger names and exact SQL text',
        ],
        introspection: {
          tableInfo: 'PRAGMA table_info returns visible columns and reindexes cid without generated hidden columns; compared directly with the S0.08 baseline.',
          tableXinfo: 'PRAGMA table_xinfo returns visible and hidden/generated columns with physical CIDs; recorded separately per registry table.',
          deliberateDistinction: 'xinfo hidden CIDs are not schema drift and are not compared to S0.08 table_info CIDs.',
        },
      },
      registry,
      anomalies,
      preflight,
      remote,
      crud,
      reviewPlans,
      ftsShadows: [...shadowTables].sort().map(table => ({
        table,
        database: DATABASES.find(database => databaseTables[database].has(table)),
        classification: 'FTS5 shadow table; rebuilt with its virtual table and not a registry row',
      })),
      pkParsing,
      sources,
      rules: {
        registry: 'Exact live syncClassificationRegistry order and values; S0.09 values are compared only after mapping to canonical fields.',
        schema: 'Fresh production migrations, not a copied schema fixture. table_info is compared directly with S0.08 for visible cid/type/default/pk; table_xinfo separately records hidden/generated columns and physical CIDs. SQLite intentionally reindexes table_info cid without hidden columns.',
        primaryKey: 'logical declaration, production primaryKeyColumns, and raw SQLite primary key are recorded separately; known differences are facts.',
        unique: 'Business unique-key text is split by production getBusinessUniqueKeys and matched against SQLite unique index column sets.',
        triggers: 'Trigger lookup is database-qualified. Hashes cover exact sqlite_master SQL; event parsing uses production triggerEvent.',
        local: 'LocalRuntime and BackupOnly have no incremental row capture. This does not claim they are excluded from the whole database-file backup.',
        derived: 'DerivedRebuild tables have no row capture and are rebuilt from synced sources; FTS shadow tables are cataloged separately.',
        preflight: 'Each negative starts from a valid production fixture and restores the exact original trigger before the next negative.',
        remote: 'Unregistered RowSync targets are quarantined and not written; unknown database names fail before acknowledgement.',
        fts: 'Rebuild is the exact production notes_fts migration after delete-all. Direct MATCH is tested separately from the production LIKE fallback.',
        reviewPlans: 'A real vfs review_plans row is scored through ReviewPlanService/ReviewPlanRepo, collected through getPendingChanges/enrichChangesWithData, serialized through the production change codec, applied through applyDownloadedChangesToDatabases into a separate fresh vfs, and read back through ReviewPlanRepo. Unknown fields and FSRS columns stay in the row; the sync parser deliberately ignores outer unknown keys.',
        unknownFields: 'The real __sync_unknown_fields vault is written for review_plans; bigint and null round-trip after an unrelated row update, and the canonical hash covers only stable field values.',
        probes: 'Review-plan drift probes record observed production behavior instead of assuming every case rejects: unknown columns are vaulted and accepted, primary-key drift is business-key aliased, and a future updated_at is quarantined as clock drift.',
      },
      replay: { artifactSha256: null },
    };
    const hashInput = JSON.parse(json({
      ...result,
      generatedAt: null,
      captureMs: null,
      replay: { artifactSha256: null },
    }));
    result.replay.artifactSha256 = sha256(json(hashInput));
    result.captureMs = Math.round(performance.now() - started);
    return result;
  } finally {
    noteResourceModule.configureNoteResourceRepos(null);
    localRuntimeModule.setLocalRuntime(null);
    storage.shutdown();
    rmSync(temp, { recursive: true, force: true });
  }
}

function verify(data) {
  assert.equal(data.format, FORMAT);
  assert.equal(data.unit, UNIT);
  const hashInput = JSON.parse(json({
    ...data,
    generatedAt: null,
    captureMs: null,
    replay: { artifactSha256: null },
  }));
  assert.equal(data.replay.artifactSha256, sha256(json(hashInput)), 'artifact self-hash drift');
  assert.equal(data.registry.length, 124);
  assert.equal(new Set(data.registry.map(row => `${row.database}.${row.table}`)).size, 124);
  assert.equal(data.registry.filter(row => row.category === 'RowSync').length, 58);
  assert.equal(data.registry.filter(row => row.category === 'LocalRuntime').length, 31);
  assert.equal(data.registry.filter(row => row.category === 'DerivedRebuild').length, 16);
  assert.equal(data.registry.filter(row => row.category === 'BackupOnly').length, 18);
  assert.equal(data.registry.filter(row => row.category === 'Deprecated').length, 1);
  assert.deepEqual(data.classification.divergences, []);
  assert.deepEqual(data.schemaBaseline.divergences, []);
  assert.equal(data.schemaBaseline.introspection.tableInfo.includes('reindexes cid'), true);
  assert.equal(data.schemaBaseline.introspection.tableXinfo.includes('hidden/generated'), true);
  assert.ok(data.registry.every(row => row.category === 'Deprecated' ? !row.schema.present : row.schema.present));
  assert.ok(data.registry.every(row => row.triggers.allRequiredPresent));
  assert.ok(data.registry
    .filter(row => ['LocalRuntime', 'BackupOnly', 'DerivedRebuild'].includes(row.category))
    .every(row => !row.triggers.strayCapture && !row.triggers.capturedOps.length));
  assert.ok(data.registry.every(row => row.schema.columns.every(column => Number.isInteger(column.cid))));
  assert.ok(data.registry.every(row => row.schema.xinfoColumns.every(column => Number.isInteger(column.cid))));
  assert.ok(data.registry.every(row => row.schema.xinfoColumns.length
    === row.schema.columns.length + row.schema.hiddenColumns.length));
  const llmUsageLogs = data.registry.find(row => row.database === 'llm_usage' && row.table === 'llm_usage_logs');
  assert.equal(llmUsageLogs.schema.columns.length, 28);
  assert.equal(llmUsageLogs.schema.xinfoColumns.length, 30);
  assert.deepEqual(llmUsageLogs.schema.generatedColumns, [
    { cid: 21, name: 'date_key', hidden: 3 },
    { cid: 22, name: 'hour_key', hidden: 3 },
  ]);
  assert.equal(data.anomalies.length, 2);
  assert.ok(data.preflight.negatives.every(row => row.rejection.rejected && row.restoredAccepted));
  assert.equal(data.preflight.finalAccepted, true);
  assert.equal(data.remote.unregisteredTable.success, 0);
  assert.equal(data.remote.unregisteredTable.failed, 1);
  assert.equal(data.remote.unregisteredTable.rowCountAfter, data.remote.unregisteredTable.rowCountBefore);
  assert.equal(data.remote.unknownDatabase.rejected, true);
  assert.equal(data.crud.baseline.notes, 0);
  assert.equal(data.crud.baseline.resources, 0);
  assert.equal(data.crud.ftsRebuild.cleared.directTermMatches, 0);
  assert.equal(data.crud.ftsRebuild.cleared.productionSearchHits > 0, true);
  assert.ok(data.crud.ftsRebuild.rebuilt.directTermMatches > 0);
  assert.equal(data.crud.final.directTermMatches, 0);
  assert.equal(data.crud.final.ftsRows, 0);
  assert.ok(data.crud.capturedTables.every(row => row.category === 'RowSync'));
  assert.deepEqual(data.reviewPlans.columns, REVIEW_PLAN_FSRS_COLUMNS);
  assert.equal(data.reviewPlans.schemaVersion, 20261115);
  assert.equal(data.reviewPlans.vfsMigrationCount, 73);
  assert.equal(data.reviewPlans.vector.fsrs.fsrs_phase, 'learning');
  assert.ok(data.reviewPlans.vector.fsrs.fsrs_stability > 0);
  assert.ok(data.reviewPlans.vector.fsrs.fsrs_difficulty > 0);
  assert.ok(data.reviewPlans.vector.fsrs.fsrs_due_at !== null);
  assert.ok(data.reviewPlans.vector.fsrs.fsrs_last_review_at !== null);
  assert.equal(data.reviewPlans.vector.history.quality, 4);
  assert.equal(data.reviewPlans.vector.history.passed, true);
  assert.deepEqual(data.reviewPlans.sync.source, data.reviewPlans.sync.target);
  assert.equal(data.reviewPlans.sync.wire.parsedEquals, true);
  assert.ok(data.reviewPlans.unknownFields.fields.includes('future_rating'));
  assert.ok(data.reviewPlans.unknownFields.fields.includes('future_note'));
  assert.equal(data.reviewPlans.unknownFields.values.future_rating.text, '9007199254740993');
  assert.equal(data.reviewPlans.unknownFields.values.future_note.kind, 'null');
  assert.ok(data.reviewPlans.unknownFields.canonicalSha256.length === 64);
  assert.deepEqual(data.reviewPlans.probes.map(row => row.id), [
    'unknown-column',
    'primary-key-drift',
    'clock-future-updated-at',
    'observed-behavior',
  ]);
  assert.ok(data.reviewPlans.probes
    .filter(row => row.id !== 'observed-behavior')
    .every(row => typeof row.rejected === 'boolean'));
  const observed = data.reviewPlans.probes.find(row => row.id === 'observed-behavior');
  assert.equal(observed.unknownColumnRejected, false);
  assert.equal(observed.columnPreserved, true);
  assert.equal(observed.clockRejected, true);
  assert.ok(data.reviewPlans.sync.unknownFields.targetVault.includes('future_rating'));
  assert.ok(data.reviewPlans.sync.unknownFields.targetVault.includes('future_note'));
  assert.ok(data.reviewPlans.sync.unknownFields.targetRecord.includes('future_rating'));
  assert.ok(data.reviewPlans.sync.unknownFields.targetRecord.includes('future_note'));
  assert.equal(data.reviewPlans.sync.unknownFields.valuesRetained, true);
  assert.equal(data.reviewPlans.sync.unknownFields.targetCanonicalSha256,
    data.reviewPlans.sync.unknownFields.sourceCanonicalSha256);
  assert.equal(data.pkParsing.length, 12);
  assert.ok(data.pkParsing.every(row => row.passed));
  for (const source of data.sources) {
    const base = source.origin === 'desktop' ? desktop : root;
    assert.equal(sha256(readFileSync(resolve(base, source.file))), source.sha256, `${source.file}: source hash drift`);
  }
}

function replayComparable(data) {
  return JSON.parse(json({
    ...data,
    generatedAt: null,
    captureMs: null,
    replay: { artifactSha256: null },
  }));
}

export async function run(mode) {
  if (mode === 'capture') {
    const data = await build();
    artifact('config/sync-registry-contract.json', data, mode);
    log('sync-registry-contract.accepted', {
      unit: UNIT,
      mode,
      entries: data.registry.length,
      categoryCounts: data.scope.categoryCounts,
      anomalies: data.anomalies.length,
      preflightNegatives: data.preflight.negatives.length,
      remoteFailClosed: true,
      crudSteps: data.crud.steps.length,
      ftsRebuilt: true,
      reviewPlanSync: true,
      reviewPlanProbes: data.reviewPlans.probes.length,
      pkVectors: data.pkParsing.length,
      sources: data.sources.length,
      artifactSha256: data.replay.artifactSha256,
    });
    return data;
  }
  assert.equal(mode, 'verify');
  const saved = JSON.parse(readFileSync(resolve(root, 'config/sync-registry-contract.json'), 'utf8'));
  verify(saved);
  const fresh = await build();
  assert.deepEqual(replayComparable(saved), replayComparable(fresh), 'registry contract replay drift');
  artifact('config/sync-registry-contract.json', saved, mode);
  log('sync-registry-contract.verified', {
    unit: UNIT,
    mode,
    entries: saved.registry.length,
    categoryCounts: saved.scope.categoryCounts,
    preflightNegatives: saved.preflight.negatives.length,
    crudSteps: saved.crud.steps.length,
    reviewPlanProbes: saved.reviewPlans.probes.length,
    pkVectors: saved.pkParsing.length,
    artifactSha256: saved.replay.artifactSha256,
  });
  return saved;
}

main(import.meta.url, run);
