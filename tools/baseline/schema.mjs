import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { resolve, dirname, basename } from 'node:path';
import { pathToFileURL } from 'node:url';
import { artifact, main, root, desktop, readDesktop, sourceFiles, sha256, log, parse, ts, visit, location, difference, desktopRequire } from './lib.mjs';

const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const historyRoot = 'resources/app/migrations';
const ids = ['vfs', 'chat_v2', 'mistakes', 'llm_usage', 'browser'];
const locations = { vfs: 'databases/vfs.db', chat_v2: 'chat_v2.db', mistakes: 'mistakes.db', llm_usage: 'llm_usage.db', browser: 'browser.db' };
const upstreamCommit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const appendedMigration = 'vfs/V20261115__review_plans_fsrs.sql';
const frozenMigrationCount = 134;
const frozenVfsFingerprint = 'b807a28417167a8e62fb7378b237e671b640e54dea63e077b8641a2bec84e0a6';
const fsrsColumns = [
  ['fsrs_stability', 'REAL', null, 0],
  ['fsrs_difficulty', 'REAL', null, 0],
  ['fsrs_phase', 'TEXT', null, 0],
  ['fsrs_learning_step', 'INTEGER', '0', 0],
  ['fsrs_lapses', 'INTEGER', '0', 0],
  ['fsrs_due_at', 'INTEGER', null, 0],
  ['fsrs_last_review_at', 'INTEGER', null, 0],
];

function schemaObjects(conn) {
  const objects = conn.all("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name");
  const tables = objects.filter(row => row.type === 'table').map(row => ({ name: row.name, columns: conn.all(`PRAGMA table_info('${row.name.replaceAll("'", "''")}')`), foreignKeys: conn.all(`PRAGMA foreign_key_list('${row.name.replaceAll("'", "''")}')`) }));
  return { objects, tables };
}
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
function canonicalFromObjects(conn, scope) {
  let text = '';
  for (const table of scope.tables) {
    text += `table:${table}\n`;
    for (const col of conn.all(`PRAGMA table_info('${table.replaceAll("'", "''")}')`)) text += `col:${col.cid}:${col.name}:${col.type ?? ''}:${col.notnull}:${col.dflt_value ?? ''}:${col.pk}\n`;
    for (const [type, marker, allowed] of [['index', 'idx', scope.indexes], ['trigger', 'trg', scope.triggers]]) {
      for (const item of conn.all("SELECT name, IFNULL(sql,'') AS sql FROM sqlite_master WHERE type=? AND tbl_name=? ORDER BY name", [type, table])) if (allowed.has(item.name)) text += `${marker}:${item.name}:${item.sql}\n`;
    }
  }
  for (const item of scope.indexes) if (!conn.pluck("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='index' AND name=?)", [item])) text += `missing_idx:${item}\n`;
  for (const item of scope.triggers) if (!conn.pluck("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?)", [item])) text += `missing_trg:${item}\n`;
  return text;
}
export async function collect(mode) {
  const { Connection } = await load('host/platform/storage/connection.ts');
  const { loadSqlMigrations, Runner, refineryChecksum, SipHasher13 } = await load('host/platform/storage/migration/refinery.ts');
  const { MigrationSet } = await load('host/platform/storage/migration/definitions.ts');
  const { computeSchemaFingerprint, ensureSchemaFingerprintTable } = await load('host/platform/storage/migration/fingerprint.ts');
  const { MigrationCoordinator } = await load('host/platform/storage/migration/coordinator.ts');
  const { AUDIT_CREATE_TABLE_SQL } = await load('host/platform/storage/migration/audit-log.ts');
  const { SYNC_STATE_SCHEMA } = await load('host/plugins/sync/repository/state.ts');
  const { WORKSPACE_SCHEMA, CURRENT_SCHEMA_VERSION, applyWorkspaceSchema, migrateWorkspaceSchema } = await load('host/plugins/agent-workspace/repository/schema.ts');
  const { databaseLocation } = await load('host/platform/storage/database-layout.ts');
  const { PROFILES } = await load('host/platform/storage/profiles.ts');
  const { ensurePendingMemoryCandidatesSchema } = await load('host/platform/storage/pending-memory-candidates-schema.ts');
  const lock = JSON.parse(readDesktop(`${historyRoot}/migration-lock.json`));
  const sqlFiles = sourceFiles(desktop, historyRoot).filter(f => /\/V\d+__[^/]+\.sql$/.test(f));
  assert.equal(sqlFiles.length, lock.entries.length, 'migration lock covers complete source set');
  assert.equal(sqlFiles.length, frozenMigrationCount + 1, 'S0.08 upstream append adds exactly one migration');
  assert.ok(sqlFiles.some(file => file === `${historyRoot}/${appendedMigration}`), 'S0.08 upstream migration is present');
  const previous = JSON.parse(execFileSync('git', ['show', '0222ccc:config/baseline/schema.json'], {
    cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024,
  }));
  const immutable = new Map(previous.migrations.map(item => [item.source, item.sha256]));
  assert.equal(immutable.size, frozenMigrationCount);
  for (const [file, hash] of immutable) {
    assert.ok(sqlFiles.includes(file), `${file}: frozen migration missing`);
    assert.equal(sha256(readFileSync(resolve(desktop, file))), hash, `${file}: immutable upstream SQL drift`);
    assert.equal(sha256(readFileSync(resolve(root, file.replace(historyRoot, 'config/schema/history')))), hash,
      `${file}: immutable historical snapshot drift`);
  }
  const migrations = sqlFiles.map(file => {
    const relative = file.slice(historyRoot.length + 1);
    const bytes = readFileSync(resolve(desktop, file));
    const saved = `config/schema/history/${relative}`;
    if (mode === 'capture') {
      mkdirSync(dirname(resolve(root, saved)), { recursive: true });
      writeFileSync(resolve(root, saved), bytes);
    }
    assert.deepEqual(readFileSync(resolve(root, saved)), bytes, `${file}: historical SQL bytes`);
    const locked = lock.entries.find(entry => entry.path === relative);
    assert.ok(locked, `${file}: migration lock entry`);
    assert.equal(sha256(bytes), locked.sha256, `${file}: desktop migration-lock SHA-256`);
    const database = relative.split('/')[0];
    const name = basename(file).replace(/^V\d+__/, '').slice(0, -4);
    return { database, version: locked.version, name, source: file, snapshot: saved, bytes: bytes.length, sha256: sha256(bytes), refineryChecksum: refineryChecksum(name, locked.version, bytes.toString('utf8')).toString(), acquisition: 'byte-for-byte copy of locked desktop Git source' };
  });
  const frozenMigrations = migrations.filter(item => `${item.database}/${basename(item.source)}` !== appendedMigration);
  const appended = migrations.find(item => `${item.database}/${basename(item.source)}` === appendedMigration);
  assert.equal(frozenMigrations.length, frozenMigrationCount, 'original S0.08 migration set remains 134 files');
  assert.ok(appended, 'upstream V20261115 migration is registered');
  assert.deepEqual(
    { database: appended.database, version: appended.version, name: appended.name },
    { database: 'vfs', version: 20261115, name: 'review_plans_fsrs' },
    'upstream V20261115 identity',
  );
  const frozenSqlSetSha256 = sha256(frozenMigrations.map(item => `${item.snapshot}\0${item.sha256}\n`).join(''));
  const historyFreeze = {
    upstream: { repository: '../dstu-node', commit: upstreamCommit, migrationLock: `${historyRoot}/migration-lock.json` },
    frozen: {
      count: frozenMigrations.length,
      throughVersion: 20261111,
      sqlSetSha256: frozenSqlSetSha256,
      vfsFingerprint: frozenVfsFingerprint,
      rule: 'original 134 files are immutable; append-only upstream migration follows the frozen set',
    },
    appended: {
      count: 1,
      snapshot: appended.snapshot,
      version: appended.version,
      name: appended.name,
      sha256: appended.sha256,
      refineryChecksum: appended.refineryChecksum,
    },
    totalCount: migrations.length,
  };
  const previousChecksums = JSON.parse(readDesktop('host/platform/storage/migration/__fixtures__/rust-refinery-checksums.json'));
  let checksumComparisons = 0;
  for (const [id, rows] of Object.entries(previousChecksums.databases)) for (const row of rows) {
    const migration = migrations.find(item => item.database === id && item.version === row.version);
    assert.equal(migration.refineryChecksum, row.checksum, `${id}/${row.version}: Rust checksum golden`);
    checksumComparisons++;
  }
  const sipVectors = [[0, 'abac0158050fc4dc'], [1, 'c9f49bf37d57ca93'], [7, 'd3927d989bb11140'], [8, '369095118d299a8e'], [15, 'd320d86d2a519956'], [63, '9d199062b7bbb3a8']];
  for (const [length, expected] of sipVectors) assert.equal(new SipHasher13(0x0706050403020100n, 0x0f0e0d0c0b0a0908n).write(Uint8Array.from({ length }, (_, i) => i)).finish().toString(16), expected);
  const previousFingerprints = JSON.parse(readDesktop('host/platform/storage/migration/__fixtures__/rust-schema-fingerprints.json'));
  const temp = mkdtempSync(resolve(root, '.baseline-schema-'));
  const databases = [];
  const moduleLog = (level, message, fields) => log('schema.desktop', { level, message, fields });
  try {
    for (const id of ids) {
      const list = loadSqlMigrations(resolve(desktop, historyRoot, id));
      const set = new MigrationSet(id, list.map(m => ({ refineryVersion: m.version, name: m.name, sql: m.sql, expectedTables: [], expectedColumns: [], expectedIndexes: [], expectedQueries: [], idempotent: false })));
      const conn = new Connection(':memory:', { label: id, profile: { foreignKeys: true } });
      try {
        const runner = new Runner(list, { log: moduleLog });
        const first = runner.run(conn);
        assert.equal(first.length, list.length);
        assert.equal(runner.run(conn).length, 0, `${id}: second run is idempotent`);
        const version = list.at(-1).version;
        const fp = computeSchemaFingerprint(conn, set, version);
        const independent = canonicalFromObjects(conn, fp.scope);
        assert.equal(independent, fp.canonicalSchema, `${id}: canonical object comparison`);
        assert.equal(sha256(independent), fp.hash);
        const storedHistory = conn.all('SELECT version,name,checksum FROM refinery_schema_history ORDER BY version');
        for (const row of storedHistory) assert.equal(row.checksum, migrations.find(m => m.database === id && m.version === row.version).refineryChecksum);
        const schema = schemaObjects(conn);
        const database = { id, scope: id === 'browser' ? 'device-local browser storage' : 'shared business schema; table scope determined in S0.09', path: locations[id], migrationCount: list.length, version, history: storedHistory, fingerprint: fp.hash, canonicalSchema: fp.canonicalSchema, fingerprintScope: { tables: [...fp.scope.tables], indexes: [...fp.scope.indexes], triggers: [...fp.scope.triggers] }, schema };
        if (id === 'vfs') database.upgradeFrom20261111 = { targetVersion: 20261115, migrationCount: 73, appendedColumns: fsrsColumns.map(([name]) => name), previousFingerprint: frozenVfsFingerprint, previousVersion: 20261111, evidence: 'config/baseline/schema-upgrade.json' };
        databases.push(database);
        log('schema.database', { id, migrations: list.length, version, fingerprint: fp.hash, tables: schema.tables.length });
        if (id === 'vfs') {
          assert.equal(list.length, 73, 'vfs history contains 72 frozen migrations plus upstream V20261115');
          assert.equal(version, 20261115);
          assert.equal(storedHistory.length, 73);
          assert.equal(storedHistory.at(-1).version, 20261115);
          assert.equal(storedHistory.at(-1).name, 'review_plans_fsrs');
          assert.equal(storedHistory.at(-1).checksum, appended.refineryChecksum);
          const reviewPlan = schema.tables.find(table => table.name === 'review_plans');
          assert.ok(reviewPlan, 'review_plans table is present');
          const oldColumns = reviewPlan.columns.filter(column => !fsrsColumns.some(([name]) => name === column.name));
          const newColumns = reviewPlan.columns.filter(column => fsrsColumns.some(([name]) => name === column.name));
          assert.equal(oldColumns.length, reviewPlan.columns.length - 7);
          assert.deepEqual(newColumns.map(column => [
            column.name,
            column.type,
            column.dflt_value,
            column.notnull,
          ]), fsrsColumns);
        }
        if (previousFingerprints.databases[id]) {
          const reference = previousFingerprints.databases[id];
          const prior = new Connection(':memory:', { label: `${id}-rust-golden`, profile: { foreignKeys: true } });
          try {
            new Runner(list, { target: { kind: 'Version', version: reference.schema_version } }).run(prior);
            const actual = computeSchemaFingerprint(prior, set, reference.schema_version);
            assert.equal(actual.hash, reference.fingerprint, `${id}: Rust fingerprint`);
            assert.equal(actual.canonicalSchema, reference.canonical_schema, `${id}: Rust canonical schema bytes`);
          } finally { prior.close(); }
        }
      } finally { conn.close(); }
    }
    const coordinator = new MigrationCoordinator({ appDataDir: resolve(temp, 'active'), migrationsDir: resolve(root, 'config/schema/history'), auditDbPath: null, log: moduleLog });
    const report = await coordinator.runAll();
    assert.equal(report.success, true, 'production coordinator startup');
    const freshVfs = report.databases.find(database => database.id === 'vfs');
    assert.equal(freshVfs?.fromVersion, 0);
    assert.equal(freshVfs?.toVersion, 20261115);
    assert.equal(freshVfs?.appliedCount, 73);
    log('schema.coordinator.fresh', { success: report.success, databases: report.databases.map(database => ({ id: database.id, from: database.fromVersion, to: database.toVersion, applied: database.appliedCount, success: database.success })) });
    const restart = await coordinator.runAll();
    assert.equal(restart.success, true, 'production coordinator restart');
    assert.ok(restart.databases.every(database => database.success && database.appliedCount === 0), 'restart applies zero migrations');
    log('schema.coordinator.restart', { success: restart.success, databases: restart.databases.map(database => ({ id: database.id, from: database.fromVersion, to: database.toVersion, applied: database.appliedCount, success: database.success })) });
    for (const id of ids.filter(id => id !== 'browser')) {
      const conn = new Connection(coordinator.getDatabasePath(id), { label: id, profile: { foreignKeys: true } });
      try {
        const actual = computeSchemaFingerprint(conn, coordinator.sets[id], coordinator.sets[id].latestVersion());
        assert.equal(actual.hash, databases.find(d => d.id === id).fingerprint, `${id}: coordinator and pure migration schema`);
        databases.find(d => d.id === id).startupSchema = schemaObjects(conn);
        assert.equal(conn.pluck('PRAGMA integrity_check'), 'ok');
        assert.deepEqual(conn.all('PRAGMA foreign_key_check'), []);
      } finally { conn.close(); }
    }
    const local = [
      { id: 'audit', path: 'databases/audit.db', mechanism: 'idempotent DDL', sql: AUDIT_CREATE_TABLE_SQL, source: 'host/platform/storage/migration/audit-log.ts', symbol: 'AUDIT_CREATE_TABLE_SQL' },
      { id: 'sync_state', path: '<dataLocal>/deep-student/sync/sync_state.db', mechanism: 'idempotent DDL; upload_seq survives local purge', sql: SYNC_STATE_SCHEMA, source: 'host/plugins/sync/repository/state.ts', symbol: 'SYNC_STATE_SCHEMA' },
      { id: 'workspace', path: '<active>/workspaces/ws_<id>.db', mechanism: `PRAGMA user_version=${CURRENT_SCHEMA_VERSION}; migration v2`, sql: WORKSPACE_SCHEMA, source: 'host/plugins/agent-workspace/repository/schema.ts', symbol: 'WORKSPACE_SCHEMA' },
    ];
    const localSchemas = [];
    for (const item of local) {
      const conn = new Connection(':memory:', { label: item.id, profile: { foreignKeys: true } });
      try {
        conn.exec(item.sql); conn.exec(item.sql);
        if (item.id === 'workspace') {
          applyWorkspaceSchema(conn); migrateWorkspaceSchema(conn);
          assert.equal(conn.pluck('PRAGMA user_version'), CURRENT_SCHEMA_VERSION);
          const prior = new Connection(':memory:', { label: 'workspace-v1', profile: { foreignKeys: true } });
          try {
            prior.exec(WORKSPACE_SCHEMA.replace('initial_task TEXT,', 'task_content TEXT,').replace('started_at TEXT,', '').replace('completed_at TEXT,', '').replace('result_summary TEXT,', ''));
            prior.exec(`PRAGMA user_version=1;
              INSERT INTO workspace(id,creator_session_id,created_at,updated_at) VALUES('w1','s1','2026-11-15T00:00:00Z','2026-11-15T00:00:00Z');
              INSERT INTO agent(session_id,workspace_id,joined_at,last_active_at) VALUES('s1','w1','2026-11-15T00:00:00Z','2026-11-15T00:00:00Z');
              INSERT INTO subagent_task(id,workspace_id,agent_session_id,task_content,created_at) VALUES('t1','w1','s1','原任务','2026-11-15T00:00:00Z');`);
            migrateWorkspaceSchema(prior);
            assert.equal(prior.pluck("SELECT initial_task FROM subagent_task WHERE id='t1'"), '原任务');
            assert.equal(prior.pluck('PRAGMA user_version'), CURRENT_SCHEMA_VERSION);
            assert.ok(prior.all('PRAGMA table_info(subagent_task)').some(row => row.name === 'initial_task'));
            item.upgradeFromV1 = { expectedVersion: CURRENT_SCHEMA_VERSION, migratedTask: { id: 't1', initialTask: '原任务' }, schema: schemaObjects(prior), columnOrderRule: 'historical v1 columns stay in cid order; v2 ALTER appends four fields; fresh v2 uses WORKSPACE_SCHEMA order' };
          } finally { prior.close(); }
        }
        localSchemas.push({ ...item, sqlSha256: sha256(item.sql), scope: 'device-local runtime; logical workspace content follows S0.09 classification', schema: schemaObjects(conn) });
      } finally { conn.close(); }
    }
    const auxiliary = new Connection(':memory:', { label: 'auxiliary', profile: {} });
    try {
      assert.equal(ensurePendingMemoryCandidatesSchema(auxiliary), 'created');
      assert.equal(ensurePendingMemoryCandidatesSchema(auxiliary), 'none');
      ensureSchemaFingerprintTable(auxiliary, moduleLog);
      localSchemas.push({ id: 'embedded-governance', path: 'mistakes.db/pending_memory_candidates and each core/__governance_schema_fingerprints', source: 'host/platform/storage/pending-memory-candidates-schema.ts', mechanism: 'runtime DDL outside historical migration scope; exact cid order', scope: 'per-table scope registered in S0.09', schema: schemaObjects(auxiliary) });
    } finally { auxiliary.close(); }
    const inlineDdl = [];
    const excludedInlineDdl = [];
    for (const file of sourceFiles(desktop, 'host').filter(f => /\.ts$/.test(f) && !/(?:\/tests\/|__tests__|\.test\.|\/e2e\/|\/equivalence\/|\/fixtures\/|\/migration\/definitions\/)/.test(f))) {
      if (!/CREATE\s+(?:VIRTUAL\s+)?TABLE/i.test(readDesktop(file))) continue;
      if (/(?:^|\/)storage-mobile\//.test(file) || /(?:^|\/)mobile\.ts$/.test(file)) {
        excludedInlineDdl.push({ file, reason: 'upstream mobile runtime chain is not adopted; not a desktop schema input' });
        continue;
      }
      const sf = parse(file);
      visit(sf, node => {
        if (!ts.isStringLiteralLike(node) && !ts.isTemplateExpression(node)) return;
        const raw = ts.isStringLiteralLike(node) ? node.text : node.getText(sf);
        if (!/CREATE\s+(?:VIRTUAL\s+)?TABLE/i.test(raw)) return;
        const database = file.includes('/sync/repository/state') || file.includes('/sync-support/repository/state') || file.includes('/sync/src/v5-runtime-bridge') ? 'sync_state'
          : file.includes('migration/audit-log') || file.includes('/backup/repository/audit') ? 'audit'
          : file.includes('/agent-workspace/repository/schema') ? 'workspace'
          : file.includes('pending-memory-candidates') || file.includes('/review/src/fsrs7') ? 'mistakes'
          : file.includes('/vfs') ? 'vfs'
          : file.includes('/chat/') || file.includes('/chat-pipeline/') || file.includes('completion-outbox') ? 'chat_v2'
          : file.includes('/anki/repository/apkg-export') ? 'apkg-export-staging'
          : 'caller-selected-business-database';
        const purpose = file.includes('script-checker') || file.includes('/sql-text') ? 'SQL parser/checker sample literal' : file.includes('/sync/src/plugin-sync-sandbox') ? 'plugin sync sandbox' : 'repository schema initialization';
        inlineDdl.push({ evidence: location(file, sf, node), database, purpose, sqlOrTemplate: raw, sha256: sha256(raw), tables: [...raw.matchAll(/CREATE\s+(?:VIRTUAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w$"`{}.-]+)/gi)].map(m => m[1]), contract: 'owning repository initializes inline schema; classify table and preserve DDL/column order' });
      });
    }
    const drift = difference({ checksum: 'unchanged' }, { checksum: 'changed' });
    assert.equal(drift.path, '$.checksum');
    const probe = migrations[0];
    const sql = readFileSync(resolve(root, probe.snapshot), 'utf8');
    assert.notEqual(refineryChecksum(probe.name, probe.version, `${sql}\n`).toString(), probe.refineryChecksum, 'one-byte SQL edit changes refinery checksum');
    assert.notEqual(sha256(`${sql}\n`), probe.sha256, 'one-byte SQL edit changes source SHA-256');
    const result = {
      formatVersion: 1, unit: 'S0.08', registeredDatabases: ['mistakes', 'vfs', 'chat_v2', 'llm_usage', 'audit', 'browser', 'sync_state'].map(id => ({ id, location: databaseLocation(id), profile: PROFILES[id], management: ids.includes(id) ? 'historical refinery SQL' : 'inline DDL', androidScope: ['audit', 'browser', 'sync_state'].includes(id) ? 'device-local' : 'shared business schema' })), driver: { package: 'better-sqlite3', version: desktopRequire('better-sqlite3/package.json').version, sqlite: (() => { const c = new Connection(':memory:', { label: 'version', profile: {} }); try { return c.pluck('SELECT sqlite_version()'); } finally { c.close(); } })() },
      migrations, databases, localDatabases: localSchemas, inlineDdl,
      historyFreeze,
      comparisons: { rawSqlFiles: migrations.length, frozenSqlFiles: frozenMigrations.length, appendedSqlFiles: 1, rustChecksums: checksumComparisons, rustFingerprints: Object.keys(previousFingerprints.databases).length, freshRun: 'passed; vfs 0 -> 20261115 with 73 migrations', secondRun: 'zero migrations across all coordinated databases', productionCoordinator: 'fresh + restart + integrity + foreign keys + exact managed fingerprint passed', byteMutation: 'SHA-256 and refinery differ' },
      excludedInlineDdl,
      rules: { checksum: 'SipHash-1-3(0,0): UTF8(name) FF + i32LE(version) + UTF8(raw SQL) FF; u64 decimal string', history: 'SQL and refinery history INSERT share a transaction; preserve original SQL bytes', fingerprint: 'UTF-8 sorted scope; cid order; raw index/trigger DDL; SHA-256 lower hex; governance/history excluded from managed scope', sqlite: 'schema canonical and FTS shadow layout compared on pinned driver; Kotlin bundled engine conformance in S1.04/S1.05', updates: 'historical snapshots immutable; new coordinated migrations appended; S0.G freezes final schema after S0.21–S0.24' },
      sourceHashes: ['host/platform/storage/connection.ts', 'host/platform/storage/migration/refinery-core.ts', 'host/platform/storage/migration/fingerprint.ts', 'host/platform/storage/migration/coordinator.ts', 'host/platform/storage/migration/repairs.ts', 'host/platform/storage/database-layout.ts', `${historyRoot}/migration-lock.json`, 'host/platform/storage/migration/__fixtures__/rust-refinery-checksums.json', 'host/platform/storage/migration/__fixtures__/rust-schema-fingerprints.json', ...local.map(item => item.source)].map(file => ({ file, sha256: sha256(readDesktop(file)) })),
    };
    result.independentFreshEvidence = {
      artifact: 'config/baseline/schema.json',
      operation: 'production MigrationCoordinator on an empty active directory',
      expected: { vfsFromVersion: 0, vfsToVersion: 20261115, vfsAppliedCount: 73 },
      observed: reportSummary(report),
    };
    return result;
  } finally { rmSync(temp, { recursive: true }); }
}
export async function run(mode) {
  const data = await collect(mode);
  artifact('config/baseline/schema.json', data, mode);
  const { collect: collectUpgrade } = await import('./schema-upgrade.mjs');
  const upgrade = await collectUpgrade(data);
  artifact('config/baseline/schema-upgrade.json', upgrade, mode);
  const vfs = data.databases.find(database => database.id === 'vfs');
  log('schema.accepted', { migrations: data.migrations.length, frozenSqlFiles: data.comparisons.frozenSqlFiles, appendedSqlFiles: data.comparisons.appendedSqlFiles, databases: data.databases.length, local: data.localDatabases.length, inlineDdl: data.inlineDdl.length, vfsVersion: vfs.version, vfsFingerprint: vfs.fingerprint, upgrade: upgrade.checks, comparisons: data.comparisons });
}
main(import.meta.url, run);
