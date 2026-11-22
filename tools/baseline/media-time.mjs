import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { artifact, desktop, log, main, root, sha256 } from './lib.mjs';

const UNIT = 'S0.22';
const CONTRACT = 'config/contracts/media-time.json';
const FIXTURE = 'fixtures/media-time.json';
const RESOURCE = 'public-media-resource';
const DATE = '2026-11-15';
const OBSERVED = {
  studyA: '2026-11-15T08:00:00Z', studyB: '2026-11-15T08:00:01Z',
  resumeA: '2026-11-15T08:01:00Z', resumeB: '2026-11-15T08:02:00Z',
  subtitleA: '2026-11-15T08:03:00Z', subtitleB: '2026-11-15T08:03:01Z',
};
const events = [
  { event_id: 's022-study-a', device_id: 'device-a', kind: 'study', resource_id: RESOURCE,
    logical_date: DATE, seconds: 90, observed_at: OBSERVED.studyA, data: { surface: 'player' } },
  { event_id: 's022-study-b', device_id: 'device-b', kind: 'study', resource_id: RESOURCE,
    logical_date: DATE, seconds: 30, observed_at: OBSERVED.studyB, data: { surface: 'player' } },
  { event_id: 's022-resume-a', device_id: 'device-a', kind: 'resume', resource_id: RESOURCE,
    logical_date: DATE, position_ms: 90000, duration_ms: 100000, observed_at: OBSERVED.resumeA, data: {} },
  { event_id: 's022-resume-b', device_id: 'device-b', kind: 'resume', resource_id: RESOURCE,
    logical_date: DATE, position_ms: 12000, duration_ms: 100000, observed_at: OBSERVED.resumeB, data: {} },
  { event_id: 's022-subtitle-a', device_id: 'device-a', kind: 'subtitle', resource_id: RESOURCE,
    logical_date: DATE, position_ms: 1000, duration_ms: 2500, observed_at: OBSERVED.subtitleA,
    data: { plan_id: 'public-media-plan', version: 2, index: 0, text: 'Second device subtitle' } },
  { event_id: 's022-subtitle-b', device_id: 'device-b', kind: 'subtitle', resource_id: RESOURCE,
    logical_date: DATE, position_ms: 1000, duration_ms: 2500, observed_at: OBSERVED.subtitleB,
    data: { plan_id: 'public-media-plan', version: 2, index: 0, text: 'First device subtitle' } },
];
const SOURCE_FILES = [
  'host/plugins/sync/src/classification-registry.ts',
  'host/platform/storage/migration/definitions/vfs.ts',
  'host/plugins/sync/src/collect-sync-changes.ts',
  'host/plugins/sync/repository/apply-databases.ts',
  'host/plugins/vfs-repos/repository/media-transcripts.ts',
];
const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const plain = value => JSON.parse(JSON.stringify(value, (_key, item) =>
  typeof item === 'bigint' ? { $bigint: item.toString() } : item));

function errorOf(fn) {
  try { fn(); return null; } catch (error) { return error instanceof Error ? error.message : String(error); }
}

async function worker() {
  const media = await import('../../desktop-increment/media-time.mjs');
  const s021 = await import('../../desktop-increment/templates-assets.mjs');
  const hooks = [s021.installTemplateAssetIncrement(), media.installMediaTimeIncrement()];
  const { setLocalRuntime } = await load('host/plugins/sync/src/local-runtime.ts');
  const { nodeLocalRuntime } = await load('host/plugins/sync/src/local-runtime.node.ts');
  setLocalRuntime(nodeLocalRuntime);
  const { createStorageService } = await load('host/platform/storage/service.ts');
  const { Connection } = await load('host/platform/storage/connection.ts');
  const { loadSqlMigrations, Runner } = await load('host/platform/storage/migration/refinery.ts');
  const { collectSyncChanges } = await load('host/plugins/sync/src/collect-sync-changes.ts');
  const { serializeSyncChange, parseSyncChange } = await load('host/plugins/sync/src/change-payload.ts');
  const { applyDownloadedChangesToDatabases } = await load('host/plugins/sync/repository/apply-databases.ts');
  const { rowSyncTables, syncClassificationRegistry } = await load('host/plugins/sync/src/classification.ts');
  const { validateSyncRegistryDrift } = await load('host/plugins/sync/repository/registry-preflight.ts');
  const temp = mkdtempSync(join(tmpdir(), 'dstu-s022-media-'));
  const storages = [];
  const freshReports = {};
  let upgradeReport;
  const logger = { info() {}, debug() {}, warn() {}, error() {} };
  try {
    const migrationDirectory = media.prepareMediaTimeMigrations(join(temp, 'migrations'));
    const preparedVfs = readdirSync(join(migrationDirectory, 'vfs'))
      .filter(file => file.endsWith('.sql')).sort();
    assert.equal(preparedVfs.at(-2), 'V20261115__review_plans_fsrs.sql');
    assert.equal(preparedVfs.at(-1), 'V20261116__portable_media_progress.sql');
    assert.equal(preparedVfs.includes('V20261117__plugin_skill_data_contract.sql'), false);
    assert.equal(readdirSync(join(migrationDirectory, 'mistakes'))
      .includes('V20261006__portable_anki_templates.sql'), true);
    const open = async name => {
      const storage = createStorageService({ baseDir: join(temp, name),
        dataLocalDir: join(temp, `${name}-local`), migrationsDir: migrationDirectory });
      storages.push(storage);
      const fresh = await storage.migrations.runAll();
      assert.equal(fresh.success, true, `${name}: production migration`);
      const freshVfs = fresh.databases.find(row => row.id === 'vfs');
      assert.deepEqual(plain({ fromVersion: freshVfs.fromVersion, toVersion: freshVfs.toVersion,
        appliedCount: freshVfs.appliedCount }),
      { fromVersion: 0, toVersion: 20261116, appliedCount: 74 }, `${name}: fresh vfs head`);
      const restart = await storage.migrations.runAll();
      assert.equal(restart.success, true, `${name}: restart migration`);
      const restartVfs = restart.databases.find(row => row.id === 'vfs');
      assert.deepEqual(plain({ fromVersion: restartVfs.fromVersion, toVersion: restartVfs.toVersion,
        appliedCount: restartVfs.appliedCount }),
      { fromVersion: 20261116, toVersion: 20261116, appliedCount: 0 }, `${name}: restart zero migrations`);
      const reportFields = row => plain({ id: row.id, fromVersion: row.fromVersion,
        toVersion: row.toVersion, appliedCount: row.appliedCount, success: row.success, error: row.error });
      freshReports[name] = { freshVfs: reportFields(freshVfs), restartVfs: reportFields(restartVfs) };
      return storage;
    };
    const source = await open('device-a');
    const target = await open('device-b');
    const baseline = { baseDir: join(temp, 'baseline') };
    for (const database of ['vfs', 'llm_usage', 'chat_v2', 'mistakes']) {
      const directory = resolve(desktop, 'resources/app/migrations', database);
      const migrations = loadSqlMigrations(directory);
      const connection = new Connection(join(temp, 'baseline', 'databases', `${database}.db`),
        { label: `s022-baseline-${database}`, profile: { foreignKeys: true } });
      try {
        const runner = new Runner(migrations, { grouped: false, abortDivergent: false, abortMissing: false,
          log: (level, message) => { if (level === 'error') log('media-time.baseline-migration',
            { database, level, message }); } });
        assert.equal(runner.run(connection).length, migrations.length);
        assert.equal(new Runner(migrations).run(connection).length, 0);
      } finally { connection.close(); }
    }
    const baselineDb = new Connection(join(temp, 'baseline', 'databases', 'vfs.db'),
      { label: 's022-baseline-vfs-read', profile: { foreignKeys: true } });
    const sourceDb = source.database('vfs');
    const targetDb = target.database('vfs');
    assert.equal(baselineDb.pluck('SELECT MAX(version) FROM refinery_schema_history'), 20261115);
    const preparedVfsMigrations = loadSqlMigrations(join(migrationDirectory, 'vfs'));
    assert.deepEqual(preparedVfsMigrations.slice(-2).map(migration => [migration.version, migration.name]),
      [[20261115, 'review_plans_fsrs'], [20261116, 'portable_media_progress']]);
    const upgraded = new Runner(preparedVfsMigrations).run(baselineDb);
    assert.deepEqual(upgraded.map(migration => [migration.version, migration.name]),
      [[20261116, 'portable_media_progress']]);
    assert.deepEqual(plain(baselineDb.get(`SELECT version, name FROM refinery_schema_history
      ORDER BY version DESC LIMIT 1`)),
    { version: 20261116, name: 'portable_media_progress' });
    assert.equal(new Runner(preparedVfsMigrations).run(baselineDb).length, 0);
    upgradeReport = {
      fromVersion: 20261115, toVersion: 20261116,
      applied: upgraded.map(migration => migration.version),
      restartApplied: 0,
      migrationRow: plain(baselineDb.get(`SELECT version, name FROM refinery_schema_history
        WHERE version = 20261116`)),
    };
    assert.equal(sourceDb.pluck('SELECT MAX(version) FROM refinery_schema_history'), 20261116);
    assert.equal(sourceDb.pluck('SELECT name FROM refinery_schema_history WHERE version = 20261116'), 'portable_media_progress');
    const classification = syncClassificationRegistry()
      .find(row => row.table_name === 'portable_media_events');
    assert.deepEqual(plain(classification), {
      database: 'vfs', table_name: 'portable_media_events', primary_key: 'event_id',
      category: 'RowSync', conflict_policy: 'Lww', business_unique_keys: '',
      has_json_blobs: true,
      merge_notes: 'S0.22: immutable media observations deduplicated by event_id; aggregates are recomputed per device and then globally',
    });
    assert.equal(rowSyncTables().filter(row => row.table_name === 'portable_media_events').length, 1);
    assert.equal(syncClassificationRegistry().filter(row => row.table_name === 'custom_anki_templates')[0].category, 'RowSync');
    validateSyncRegistryDrift(source);
    validateSyncRegistryDrift(target);

    assert.equal(media.appendMediaEvent(sourceDb, { ...events[0], observed_at: '2026-11-15T16:00:00+08:00' }).inserted, true);
    assert.equal(media.appendMediaEvent(sourceDb, events[0]).inserted, false);
    assert.equal(media.mediaSummary(sourceDb, RESOURCE, DATE).seconds, 90);
    assert.equal(sourceDb.pluck("SELECT COUNT(*) FROM __change_log WHERE table_name='portable_media_events'"), 1);
    baselineDb.close();
    return await finish({ media, source, target, sourceDb, targetDb, logger, classification,
      freshReports, upgradeReport });
  } finally {
    for (const storage of storages) storage.shutdown();
    setLocalRuntime(null);
    for (const hook of hooks.reverse()) hook.unregister();
    rmSync(temp, { recursive: true, force: true });
  }
}

async function finish(ctx) {
  const { media, source, target, sourceDb, targetDb, logger, classification,
    freshReports, upgradeReport } = ctx;
  const { collectSyncChanges } = await load('host/plugins/sync/src/collect-sync-changes.ts');
  const { serializeSyncChange, parseSyncChange } = await load('host/plugins/sync/src/change-payload.ts');
  const { applyDownloadedChangesToDatabases } = await load('host/plugins/sync/repository/apply-databases.ts');
  const append = event => media.appendMediaEvent(sourceDb, event);
  for (const event of events.slice(1)) append(event);
  const sourceSummary = media.mediaSummary(sourceDb, RESOURCE, DATE);
  assert.equal(sourceSummary.seconds, 120);
  assert.equal(sourceSummary.position_ms, 12000);
  assert.equal(sourceSummary.subtitles.length, 1);
  assert.equal(sourceSummary.subtitles[0].text, 'First device subtitle');
  const collected = await collectSyncChanges(source, null);
  const mediaChanges = collected.filter(change => change.table_name === 'portable_media_events');
  assert.equal(mediaChanges.length, 6);
  const serialized = mediaChanges.map(serializeSyncChange);
  const parsed = serialized.map(parseSyncChange);
  const upload = applyDownloadedChangesToDatabases(target, parsed, 'UseCloud', 'device-b', undefined, logger);
  assert.equal(upload.total_failed, 0);
  const targetSummary = media.mediaSummary(targetDb, RESOURCE, DATE);
  assert.deepEqual(plain(targetSummary), plain(sourceSummary));
  assert.equal(targetSummary.seconds, 120);
  assert.equal(targetSummary.position_ms, 12000);
  assert.deepEqual(targetSummary.subtitles, [{
    plan_id: 'public-media-plan', version: 2, index: 0, text: 'First device subtitle',
    start_ms: 1000, end_ms: 2500, event_id: 's022-subtitle-b',
  }]);
  assert.deepEqual(targetSummary.latest_plan_versions, [{ plan_id: 'public-media-plan', version: 2 }]);
  assert.equal(targetDb.pluck(`SELECT COUNT(*) FROM __change_log
    WHERE table_name='portable_media_events' AND sync_version=0`), 0);
  const local = () => ({
    study: targetDb.get('SELECT * FROM study_time_daily WHERE date=?', [DATE]),
    progress: targetDb.get('SELECT * FROM media_progress WHERE resource_id=?', [RESOURCE]),
    segments: targetDb.pluck('SELECT COUNT(*) FROM media_transcript_segments'),
  });
  const beforeLocal = local();
  assert.equal(applyDownloadedChangesToDatabases(target, [...parsed].reverse(), 'UseCloud', 'device-b', undefined, logger).total_failed, 0);
  assert.equal(applyDownloadedChangesToDatabases(target, parsed, 'UseCloud', 'device-b', undefined, logger).total_failed, 0);
  assert.deepEqual(plain(media.mediaSummary(targetDb, RESOURCE, DATE)), plain(targetSummary));
  assert.deepEqual(plain(local()), plain(beforeLocal));
  // Six unique fixture events: study x2, resume x2, subtitle conflict x2.
  // The +08:00/Z duplicate was the same event id and did not add a row.
  assert.equal(targetDb.pluck('SELECT COUNT(*) FROM portable_media_events'), 6);

  const negatives = [
    ['negative seconds', { ...events[0], event_id: 'n-seconds', seconds: -1 }, /seconds/],
    ['seconds over 86400', { ...events[0], event_id: 'n-over', seconds: 86401 }, /seconds/],
    ['invalid logical_date', { ...events[0], event_id: 'n-date', logical_date: '2026-11-31' }, /logical_date/],
    ['negative position', { ...events[2], event_id: 'n-position', position_ms: -1 }, /position/],
    ['position beyond duration', { ...events[2], event_id: 'n-beyond', position_ms: 100001 }, /resume position exceeds duration/],
    ['inverted cue range', { ...events[4], event_id: 'n-cue', position_ms: 3000 }, /inverted/],
    ['non-numeric plan version', { ...events[4], event_id: 'n-version', data: { ...events[4].data, version: '2' } }, /version/],
    ['missing text', { ...events[4], event_id: 'n-text', data: { plan_id: 'plan', version: 1, index: 0 } }, /missing data.text/],
    ['invalid timestamp', { ...events[0], event_id: 'n-time', observed_at: '2026-11-15' }, /observed_at/],
    ['normalized invalid day', { ...events[0], event_id: 'n-day', observed_at: '2026-02-30T08:00:00Z' }, /observed_at|logical_date/],
  ].map(([name, input, expected]) => {
    const detail = errorOf(() => media.appendMediaEvent(sourceDb, input));
    assert.ok(detail !== null, name);
    assert.match(detail, expected, name);
    return { name, rejected: true, detail };
  });
  const collision = errorOf(() => media.appendMediaEvent(sourceDb, { ...events[1], seconds: 31 }));
  assert.match(collision, /reused with different payload/);
  const triggerUpdate = errorOf(() => sourceDb.run(
    "UPDATE portable_media_events SET seconds=1 WHERE event_id='s022-study-b'"));
  assert.match(triggerUpdate, /immutable/);
  const triggerDelete = errorOf(() => sourceDb.run(
    "DELETE FROM portable_media_events WHERE event_id='s022-study-b'"));
  assert.match(triggerDelete, /immutable/);
  const directInsert = (fields) => sourceDb.run(`INSERT INTO portable_media_events
    (event_id, device_id, kind, resource_id, logical_date, position_ms, duration_ms, seconds,
     observed_at, data, local_version, updated_at, deleted_at)
    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 1, ?9, NULL)`, fields);
  const databaseConstraints = [
    ['remote invalid observed date',
      ['db-time', 'device-a', 'study', RESOURCE, DATE, null, null, 10,
        '2026-02-30T08:00:00Z', '{}'], /CHECK/],
    ['remote invalid logical date',
      ['db-date', 'device-a', 'study', RESOURCE, '2026-02-30', null, null, 10,
        OBSERVED.studyA, '{}'], /CHECK/],
    ['remote resume null position',
      ['db-resume-null', 'device-a', 'resume', RESOURCE, DATE, null, 1000, null,
        OBSERVED.studyA, '{}'], /CHECK/],
    ['remote subtitle version text',
      ['db-subtitle-version', 'device-a', 'subtitle', RESOURCE, DATE, 0, 1000, null,
        OBSERVED.studyA, '{"plan_id":"p","version":"2","index":0,"text":"x"}'], /CHECK/],
  ].map(([name, fields, expected]) => {
    const detail = errorOf(() => directInsert(fields));
    assert.ok(detail !== null, name);
    assert.match(detail, expected, name);
    return { name, rejected: true, detail };
  });

  const beforeRollback = { events: targetDb.pluck('SELECT COUNT(*) FROM portable_media_events'),
    log: targetDb.pluck('SELECT COUNT(*) FROM __change_log'),
    summary: plain(media.mediaSummary(targetDb, RESOURCE, DATE)) };
  targetDb.exec(`CREATE TRIGGER s022_reject_apply BEFORE INSERT ON portable_media_events
    BEGIN SELECT RAISE(ABORT, 's022 injected apply failure'); END;`);
  const rejectedWire = serializeSyncChange({
    ...parsed[2], table_name: 'portable_media_events', record_id: 's022-rollback-candidate',
    operation: 'Insert', changed_at: OBSERVED.resumeB,
    data: { ...parsed[2].data, event_id: 's022-rollback-candidate' },
  });
  const rollback = applyDownloadedChangesToDatabases(target, [parseSyncChange(rejectedWire)],
    'UseCloud', 'device-b', undefined, logger);
  assert.equal(rollback.total_failed, 1);
  assert.deepEqual(rollback.db_errors, []);
  assert.ok(targetDb.pluck(`SELECT error FROM __sync_quarantine
    WHERE table_name='portable_media_events'`).includes('s022 injected apply failure'));
  targetDb.exec('DROP TRIGGER s022_reject_apply');
  assert.deepEqual(plain({ events: targetDb.pluck('SELECT COUNT(*) FROM portable_media_events'),
    log: targetDb.pluck('SELECT COUNT(*) FROM __change_log'),
    summary: media.mediaSummary(targetDb, RESOURCE, DATE) }), beforeRollback);

  const sourceHashes = Object.fromEntries(SOURCE_FILES.map(file =>
    [file, sha256(readFileSync(resolve(desktop, file)))]));
  const migrationRow = sourceDb.get('SELECT version, name FROM refinery_schema_history WHERE version=20261116');
  const contract = {
    formatVersion: 1, unit: UNIT,
    status: { sourceIncrement: 'callable on locked desktop checkout',
      packaging: 'not claimed', distribution: 'not claimed' },
    api: { install: 'installMediaTimeIncrement()', migrations: 'prepareMediaTimeMigrations(parent)',
      append: 'appendMediaEvent(db, event)', summary: 'mediaSummary(db, resource_id, logical_date)',
      normalize: 'normalizeMediaEvent(event)', legacy: 'migrateLegacyMedia(db, device_id)' },
    event: {
      table: 'portable_media_events', primaryKey: 'event_id', classification,
      fields: ['event_id', 'device_id', 'kind', 'resource_id', 'logical_date', 'position_ms',
        'duration_ms', 'seconds', 'observed_at', 'data', 'local_version', 'updated_at', 'deleted_at'],
      kinds: ['study', 'resume', 'subtitle'],
      canonicalTimestamp: 'accepts RFC3339 Z or numeric offset; stores canonical UTC seconds precision; ordering by parsed epoch',
      millisecondDomain: [0, Number.MAX_SAFE_INTEGER],
      requiredByKind: {
        study: { seconds: '0..86400', position_ms: null, duration_ms: null },
        resume: { seconds: null, position_ms: '0..safe max', duration_ms: '0..safe max' },
        subtitle: { seconds: null, position_ms: '0..safe max', duration_ms: '0..safe max',
          data: ['plan_id', 'version', 'index', 'text'] },
      },
      immutable: ['event_id', 'device_id', 'kind', 'resource_id', 'logical_date', 'position_ms',
        'duration_ms', 'seconds', 'observed_at', 'data', 'deleted_at'],
      transportMutable: ['local_version', 'updated_at'],
      deletion: 'rejected by database trigger',
      collision: 'identical payload no-op; different payload rejected by adapter and immutable trigger',
    },
    summary: {
      study: 'dedupe event_id, sum per device, then global; cap 86400',
      resume: 'max(observed epoch, device_id UTF-8, event_id UTF-8); rewinds allowed',
      subtitle: 'winner per resource/plan/version/index by observation epoch/device/id; latest plan version explicit; losing event retained',
      legacy: 'local tables stay non-RowSync; explicit migrateLegacyMedia skips existing stable per-device event IDs',
    },
    migration: { coordinatedFile: 'config/schema/coordinated/vfs/V20261116__portable_media_progress.sql',
      version: 20261116, baselineVersion: 20261115,
      previousReservation: { version: 20261114, path: 'vfs/V20261114__portable_media_progress.sql' },
      sha256: sha256(readFileSync(resolve(root, 'config/schema/coordinated/vfs/V20261116__portable_media_progress.sql'))) },
    evidence: { sourceHashes, classification,
      migrationRow: plain(migrationRow), upgrade: upgradeReport, freshAndRestart: freshReports,
      collectedChanges: mediaChanges.length,
      wireBytes: serialized.reduce((sum, item) => sum + Buffer.byteLength(item), 0),
      offsetNormalization: {
        input: '2026-11-15T16:00:00+08:00',
        stored: sourceDb.pluck("SELECT observed_at FROM portable_media_events WHERE event_id='s022-study-a'"),
        sameEventIdDeduplicated: true,
      },
      targetSummary: plain(targetSummary), replaySummary: plain(media.mediaSummary(targetDb, RESOURCE, DATE)),
      localStateUnchanged: plain(beforeLocal), negatives, collision, triggerUpdate, triggerDelete,
      databaseConstraints,
      rollback: { failed: rollback.total_failed, stateUnchanged: true } },
    checks: { migrationAppended: true, s021AndS022Composed: true, actualCollectWireApply: true,
      reorderedIdempotent: true, replayedIdempotent: true, studySeconds120: true,
      resumeRewindWins: true, subtitleSlotWinner: true, localLegacyUntouched: true,
      collisionsRejected: true, invalidInputsRejected: true, immutableTrigger: true,
      databaseConstraintsRejected: true, rollbackPreservedState: true, sourceStillReadOnly: true,
      upstreamHeadUpgrade: true, freshAndRestart: true },
  };
  const fixture = { formatVersion: 1, unit: UNIT, fixtureClock: OBSERVED,
    resourceId: RESOURCE, logicalDate: DATE, events, expectedSummary: plain(sourceSummary),
    conflictVector: { subtitleSlot: { plan_id: 'public-media-plan', version: 2, index: 0 },
      losingEventId: 's022-subtitle-a', winningEventId: 's022-subtitle-b',
      winnerText: 'First device subtitle' },
    negatives: negatives.map(item => item.name),
    databaseConstraints: databaseConstraints.map(item => item.name),
    legacy: { tables: ['media_progress', 'media_transcript_segments', 'study_time_daily'],
      rowSync: false, localStateUntouched: true } };
  return { contract: plain(contract), fixture: plain(fixture) };
}

export async function run(mode) {
  const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--worker'], {
    cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  const data = JSON.parse(output);
  artifact(CONTRACT, data.contract, mode);
  artifact(FIXTURE, data.fixture, mode);
  log('media-time.accepted', { unit: UNIT, mode, checks: data.contract.checks });
}

if (process.argv[2] === '--worker') {
  worker().then(value => process.stdout.write(JSON.stringify(value))).catch(error => {
    log('media-time.failed', { unit: UNIT, message: error.message, stack: error.stack });
    process.exitCode = 1;
  });
} else {
  main(import.meta.url, run);
}
