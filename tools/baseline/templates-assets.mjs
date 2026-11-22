import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { lstat, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { artifact, desktop, git, log, main, readJson, root, sha256 } from './lib.mjs';

const UNIT = 'S0.21';
const CONTRACT = 'config/contracts/templates-assets.json';
const FIXTURE = 'fixtures/templates-assets.json';
const FIXED_CREATED = '2026-11-15T08:00:00.000Z';
const FIXED_UPDATED = '2026-11-15T09:00:00.000Z';
const FIXED_CHANGED = '2026-11-15T10:00:00.000Z';
const FIXED_TOMBSTONE = '2026-11-15T11:00:00.000Z';
const DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const MIGRATION_LOCK = 'resources/app/migrations/migration-lock.json';
const MIGRATION_LOCK_SHA256 = '1d21338ded468243fc0dbe2593f5cd0b3cb1a259930f77be56c625dd32205445';
const UPSTREAM_VFS_VERSION = 20261115;
const UPSTREAM_VFS_FILE = 'vfs/V20261115__review_plans_fsrs.sql';
const UPSTREAM_VFS_SHA256 = '483b242fbf76ded1755644dbb0c6ad9391a91f73af1b32f5d7407a2614faa7d1';
const DATABASES = ['vfs', 'llm_usage', 'chat_v2', 'mistakes'];

const DESKTOP_LOAD = file => import(pathToFileURL(resolve(desktop, file)).href);

function migrationLockHeads(lock) {
  return Object.fromEntries(DATABASES.map(database => {
    const entries = lock.entries.filter(entry => entry.database === database)
      .sort((left, right) => left.version - right.version);
    const latest = entries.at(-1);
    assert.ok(latest, `${database}: migration lock entry`);
    return [database, {
      version: latest.version,
      name: latest.name,
      file: latest.path,
      sha256: latest.sha256,
      count: entries.length,
    }];
  }));
}

function databaseHeads(storage) {
  return Object.fromEntries(DATABASES.map(database => {
    const history = storage.database(database).all('SELECT version FROM refinery_schema_history ORDER BY version');
    return [database, { version: history.at(-1)?.version ?? 0, count: history.length }];
  }));
}

function sourceAnchor(file, marker, symbol) {
  const text = readFileSync(resolve(desktop, file), 'utf8');
  const first = text.indexOf(marker);
  assert.ok(first >= 0, `${file}: missing anchor ${symbol}`);
  assert.equal(text.indexOf(marker, first + 1), -1, `${file}: ambiguous anchor ${symbol}`);
  const line = text.slice(0, first).split('\n').length;
  return { file, line, symbol, sha256: sha256(text), text: text.split('\n')[line - 1].trim() };
}

const TEMPLATE_REQUEST = Object.freeze({
  name: 'S0.21 portable template fixture',
  description: 'Public fixture for portable template capture.',
  author: 'dstu',
  version: '1.0.0',
  preview_front: '<article class="s021-preview">Portable front</article>',
  preview_back: '<article class="s021-preview">Portable back</article>',
  note_type: 'Basic',
  fields: ['Front', 'Back', 'Tags'],
  generation_prompt: 'Return the public template fixture fields.',
  front_template: '<section class="card">{{Front}}<script>globalThis.__s021TemplateExecuted = true</script></section>',
  back_template: '<section class="card">{{Back}}<div data-tags>{{Tags}}</div></section>',
  css_style: '.card { color: #123456; }',
  field_extraction_rules: {
    Front: { field_type: 'Text', is_required: true, description: 'Front' },
    Back: { field_type: 'Text', is_required: true, description: 'Back' },
    Tags: { field_type: 'Array', is_required: false, default_value: '[]', description: 'Tags' },
  },
  is_active: true,
  is_built_in: false,
});

const ASSET_BYTES = Object.freeze({
  ankiAudio: Buffer.from('524946462400000057415645666d74201000000001000100401f0000803e0000020010006461746100000000', 'hex'),
  chatAttachment: Buffer.from('S0.21 persisted chat attachment fixture\n', 'utf8'),
  restorable: Buffer.from('S0.21 tombstone restore fixture\n', 'utf8'),
});

function sqliteObjects(conn) {
  return conn.all("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name");
}

function tableShape(conn, table) {
  const columns = conn.all(`PRAGMA table_info('${table.replaceAll("'", "''")}')`);
  const objects = sqliteObjects(conn).filter(row => row.tbl_name === table);
  return {
    columns: columns.map(column => ({
      name: column.name,
      type: column.type,
      notnull: Number(column.notnull),
      default: column.dflt_value,
      pk: Number(column.pk),
    })),
    indexes: objects.filter(row => row.type === 'index').map(row => ({ name: row.name, sha256: sha256(String(row.sql ?? '')) })),
    triggers: objects.filter(row => row.type === 'trigger').map(row => ({ name: row.name, sha256: sha256(String(row.sql ?? '')) })),
  };
}

function migrationHistory(storage) {
  const conn = storage.database('mistakes');
  return conn.all('SELECT version, name, checksum FROM refinery_schema_history ORDER BY version');
}

function plain(value) {
  return JSON.parse(JSON.stringify(value, (_key, item) => typeof item === 'bigint' ? { $bigint: item.toString() } : item));
}

function templateWire(change) {
  return {
    table_name: change.table_name,
    record_id: change.record_id,
    operation: change.operation,
    data: change.data,
    changed_at: FIXED_CHANGED,
    database_name: change.database_name ?? 'mistakes',
  };
}

function normalizeAssetPath(path, active) {
  return path.startsWith(active) ? `<active>${path.slice(active.length)}` : path;
}

function validationError(name, detail) {
  return { name, detail, rejected: true };
}

async function validateAssetReferences(entries, references, calculateFileHash) {
  const seen = new Set();
  const accepted = [];
  const rejected = [];
  for (const reference of references) {
    if (seen.has(reference.key)) {
      rejected.push(validationError(reference.key, 'duplicate logical reference'));
      continue;
    }
    seen.add(reference.key);
    const asset = entries.get(reference.key);
    if (asset === undefined) {
      rejected.push(validationError(reference.key, 'missing scanned asset'));
      continue;
    }
    const actual = await calculateFileHash(asset.path);
    if (actual !== reference.sha256 || BigInt(asset.size) !== BigInt(reference.size)) {
      rejected.push(validationError(reference.key, `hash or size mismatch: ${actual}/${asset.size}`));
      continue;
    }
    accepted.push({ key: reference.key, sha256: actual, size: Number(asset.size) });
  }
  return { accepted, rejected };
}

async function worker() {
  const increment = await import('../../desktop-increment/templates-assets.mjs');
  const hook = increment.installTemplateAssetIncrement();
  const { setLocalRuntime, nodeLocalRuntime } = {
    ...(await DESKTOP_LOAD('host/plugins/sync/src/local-runtime.ts')),
    ...(await DESKTOP_LOAD('host/plugins/sync/src/local-runtime.node.ts')),
  };
  const { setLocalFs, nodeLocalFs } = {
    ...(await DESKTOP_LOAD('host/plugins/sync-providers/src/local-fs.ts')),
    ...(await DESKTOP_LOAD('host/plugins/sync-providers/src/local-fs.node.ts')),
  };
  const { createStorageService } = await DESKTOP_LOAD('host/platform/storage/service.ts');
  const { Connection } = await DESKTOP_LOAD('host/platform/storage/connection.ts');
  const { loadSqlMigrations, Runner } = await DESKTOP_LOAD('host/platform/storage/migration/refinery.ts');
  const { insertTemplate, getTemplateById, updateTemplate } = await DESKTOP_LOAD('host/plugins/anki/repository/templates.ts');
  const { collectSyncChanges } = await DESKTOP_LOAD('host/plugins/sync/src/collect-sync-changes.ts');
  const { rowSyncTables, syncClassificationRegistry } = await DESKTOP_LOAD('host/plugins/sync/src/classification.ts');
  const { serializeSyncChange, parseSyncChange } = await DESKTOP_LOAD('host/plugins/sync/src/change-payload.ts');
  const { applyDownloadedChangesToDatabases } = await DESKTOP_LOAD('host/plugins/sync/repository/apply-databases.ts');
  const { getRecordData, readUnknownFields } = {
    ...(await DESKTOP_LOAD('host/plugins/sync/repository/record-data.ts')),
    ...(await DESKTOP_LOAD('host/plugins/sync/src/unknown-fields.ts')),
  };
  const { calculateFileHash } = await DESKTOP_LOAD('host/plugins/sync-providers/src/resume.ts');
  const { assetLocalPath, scanAssets } = await DESKTOP_LOAD('host/plugins/sync/src/asset-paths.ts');
  const { isSyncedAssetKey, sanitizeAssetKeySegments } = await DESKTOP_LOAD('host/plugins/sync/src/asset-filenames.ts');
  const { SqliteSyncStateStore } = await DESKTOP_LOAD('host/plugins/sync-support/src/state.ts');
  const { createSyncSupport } = await DESKTOP_LOAD('host/plugins/sync-support/src/service.ts');
  const { adaptTombstoneStorage, plainCodec } = await DESKTOP_LOAD('host/plugins/sync-support/api.ts');
  const { MemoryStorage: TombstoneMemoryStorage } = await DESKTOP_LOAD('host/plugins/sync-support/tests/memory-storage.ts');

  setLocalRuntime(nodeLocalRuntime);
  setLocalFs(nodeLocalFs);

  const temp = mkdtempSync(join(tmpdir(), 'dstu-s021-templates-'));
  const storages = [];
  const logger = {
    info: () => undefined,
    debug: () => undefined,
    warn: message => log('templates-assets.desktop', { level: 'warn', message }),
    error: message => log('templates-assets.desktop', { level: 'error', message }),
  };

  try {
    const migrationsDir = increment.prepareTemplateMigrations(join(temp, 'migrations'));
    const baselineDir = resolve(desktop, 'resources/app/migrations');
    const migrationLockPath = resolve(desktop, MIGRATION_LOCK);
    const migrationLockBytes = readFileSync(migrationLockPath);
    assert.equal(sha256(migrationLockBytes), MIGRATION_LOCK_SHA256, 'migration lock drift');
    assert.equal(git(desktop, 'rev-parse', 'HEAD'), DESKTOP_COMMIT, 'desktop baseline commit drift');
    const lockHeads = migrationLockHeads(JSON.parse(migrationLockBytes.toString('utf8')));
    assert.equal(lockHeads.vfs.version, UPSTREAM_VFS_VERSION, 'upstream vfs head drift');
    assert.equal(lockHeads.vfs.file, UPSTREAM_VFS_FILE, 'upstream vfs migration drift');
    assert.equal(lockHeads.vfs.sha256, UPSTREAM_VFS_SHA256, 'upstream vfs migration hash drift');
    assert.equal(lockHeads.mistakes.version, 20261005, 'baseline mistakes head drift');
    const sourceLock = readJson('config/source-lock.json');
    const desktopLock = sourceLock.repositories.find(entry => entry.id === 'desktop');
    assert.equal(desktopLock.commit, DESKTOP_COMMIT, 'source lock commit drift');
    assert.equal(desktopLock.anchors.find(anchor => anchor.file === MIGRATION_LOCK).sha256,
      MIGRATION_LOCK_SHA256, 'source lock migration anchor drift');
    const preparedInputs = increment.TEMPLATE_ASSET_MIGRATION_INPUTS;
    for (const item of [preparedInputs.upstream, preparedInputs.template]) {
      assert.equal(sha256(readFileSync(resolve(migrationsDir, item.file))), item.sha256,
        `${item.file}: prepared bytes`);
    }
    const openStorage = async (name, directory, migrateWithCoordinator = true) => {
      const storage = createStorageService({
        baseDir: join(temp, name),
        dataLocalDir: join(temp, `${name}-local`),
        migrationsDir: directory,
        auditDbPath: null,
      });
      storages.push(storage);
      if (migrateWithCoordinator) {
        const report = await storage.migrations.runAll();
        assert.equal(report.success, true, `${name}: production migrations`);
      } else {
        // The S0.21 definition hook is installed for the new store. Migrate the
        // baseline directly from the locked SQL so it retains the old 20261005 head.
        for (const database of ['vfs', 'llm_usage', 'chat_v2', 'mistakes']) {
          const migrations = loadSqlMigrations(resolve(directory, database));
          const conn = new Connection(storage.pathOf(database), {
            label: `s021-baseline-${database}`,
            profile: { foreignKeys: true },
          });
          try {
            const applied = new Runner(migrations, {
              grouped: false,
              abortDivergent: false,
              abortMissing: false,
              log: (level, message) => {
                if (level === 'error') log('templates-assets.baseline-migration', { database, level, message });
              },
            }).run(conn);
            assert.equal(applied.length, migrations.length, `${name}/${database}: locked history applies`);
            assert.equal(new Runner(migrations).run(conn).length, 0, `${name}/${database}: replay is idempotent`);
          } finally {
            conn.close();
          }
        }
      }
      return storage;
    };

    const baseline = await openStorage('baseline', baselineDir, false);
    const source = await openStorage('source', migrationsDir);
    const target = await openStorage('target', migrationsDir);
    const unknownTarget = await openStorage('unknown-target', migrationsDir);
    const baselineHeads = databaseHeads(baseline);
    const sourceHeads = databaseHeads(source);
    assert.equal(baselineHeads.vfs.version, UPSTREAM_VFS_VERSION, 'baseline store vfs head');
    assert.equal(baselineHeads.mistakes.version, 20261005, 'baseline store mistakes head');
    assert.equal(sourceHeads.vfs.version, UPSTREAM_VFS_VERSION, 'source store vfs head');
    assert.equal(sourceHeads.mistakes.version, 20261006, 'source store mistakes head');

    const baselineMistakes = baseline.database('mistakes');
    const sourceMistakes = source.database('mistakes');
    const baselineTemplate = insertTemplate(baselineMistakes, 's021-baseline-template',
      { ...TEMPLATE_REQUEST, name: 'S0.21 baseline-only template' }, FIXED_CREATED);
    assert.equal(baselineTemplate, undefined);
    const baselineCollected = await collectSyncChanges(baseline, null);
    const baselineTemplateChanges = baselineCollected.filter(change => change.table_name === 'custom_anki_templates');
    assert.equal(baselineTemplateChanges.length, 0, 'locked baseline cannot capture a template row');

    insertTemplate(sourceMistakes, 's021-template-fixture', TEMPLATE_REQUEST, FIXED_CREATED);
    insertTemplate(sourceMistakes, 's021-builtin-untouched',
      { ...TEMPLATE_REQUEST, name: 'S0.21 untouched built-in fixture', is_built_in: true }, FIXED_CREATED);
    const sourceTemplate = getTemplateById(sourceMistakes, 's021-template-fixture');
    assert.ok(sourceTemplate);
    const collectedInsert = (await collectSyncChanges(source, null))
      .filter(change => change.table_name === 'custom_anki_templates');
    assert.equal(collectedInsert.length, 1, 'S0.21 captures only the user-created template insert');
    assert.equal(collectedInsert[0].operation, 'Insert');
    assert.equal(sourceMistakes.pluck("SELECT COUNT(*) FROM __change_log WHERE table_name = 'custom_anki_templates' AND record_id = 's021-builtin-untouched'"), 0,
      'untouched bundled template is not captured');

    sourceMistakes.run("UPDATE custom_anki_templates SET user_modified = 1 WHERE id = 's021-builtin-untouched'");
    const builtinModifiedChanges = (await collectSyncChanges(source, null))
      .filter(change => change.table_name === 'custom_anki_templates' && change.record_id === 's021-builtin-untouched');
    assert.equal(builtinModifiedChanges.length, 1, 'modified bundled template user state is captured');
    assert.equal(builtinModifiedChanges[0].operation, 'Update');

    const insertChange = templateWire(collectedInsert[0]);
    const insertWire = serializeSyncChange(insertChange);
    const parsedInsert = parseSyncChange(insertWire);

    const update = {
      name: TEMPLATE_REQUEST.name,
      description: 'Updated public template fixture.',
      front_template: '<section class="card updated">{{Front}}</section>',
      back_template: '<section class="card updated">{{Back}}</section>',
      css_style: '.card { color: #654321; }',
      expected_version: '1.0.0',
    };
    assert.equal(updateTemplate(sourceMistakes, 's021-template-fixture', update, '1.0.0', FIXED_UPDATED), true);
    const updatedTemplate = getTemplateById(sourceMistakes, 's021-template-fixture');
    assert.ok(updatedTemplate);
    assert.equal(updatedTemplate.version, '1.0.1');
    const collectedUpdate = (await collectSyncChanges(source, null))
      .filter(change => change.table_name === 'custom_anki_templates'
        && change.operation === 'Update'
        && change.record_id === 's021-template-fixture');
    assert.equal(collectedUpdate.length, 1, 'S0.21 captures the template update');
    const updateChange = templateWire(collectedUpdate[0]);
    const updateWire = serializeSyncChange(updateChange);
    const parsedUpdate = parseSyncChange(updateWire);

    const application = applyDownloadedChangesToDatabases(
      target,
      [parsedInsert, parsedUpdate],
      'UseCloud',
      's021-device-b',
      undefined,
      logger,
    );
    assert.equal(application.total_failed, 0, `template apply failures: ${JSON.stringify(application.db_errors)}`);
    assert.ok(application.total_success >= 2);
    const targetTemplate = getTemplateById(target.database('mistakes'), 's021-template-fixture');
    assert.ok(targetTemplate);
    assert.equal(targetTemplate.front_template, updatedTemplate.front_template);
    assert.equal(targetTemplate.version, '1.0.1');
    assert.equal(targetTemplate.is_built_in, 0);
    const users = target.database('mistakes').get(
      "SELECT user_modified, user_deleted FROM custom_anki_templates WHERE id = 's021-template-fixture'",
    );
    assert.deepEqual({ ...users }, { user_modified: 0, user_deleted: 0 });

    const unknownRow = {
      ...updateChange.data,
      id: 's021-template-unknown',
      name: 'S0.21 unknown-field template',
      future_template_metadata: { source: 'future-desktop', trusted: false },
    };
    const unknownWire = serializeSyncChange({
      ...updateChange,
      record_id: 's021-template-unknown',
      operation: 'Insert',
      data: unknownRow,
    });
    const unknownParsed = parseSyncChange(unknownWire);
    const unknownResult = applyDownloadedChangesToDatabases(
      unknownTarget,
      [unknownParsed],
      'UseCloud',
      's021-device-c',
      undefined,
      logger,
    );
    assert.equal(unknownResult.total_failed, 0);
    const unknownData = getRecordData(unknownTarget.database('mistakes'), 'custom_anki_templates', 's021-template-unknown', 'id');
    assert.deepEqual(unknownData.future_template_metadata, { source: 'future-desktop', trusted: false });
    const unknownVault = readUnknownFields(unknownTarget.database('mistakes'), 'custom_anki_templates', 's021-template-unknown');
    assert.deepEqual(unknownVault.future_template_metadata, { source: 'future-desktop', trusted: false });

    let duplicateName = null;
    try {
      insertTemplate(sourceMistakes, 's021-duplicate-name',
        { ...TEMPLATE_REQUEST, name: TEMPLATE_REQUEST.name }, FIXED_CREATED);
    } catch (error) {
      duplicateName = error instanceof Error ? error.message : String(error);
    }
    assert.ok(duplicateName?.includes('UNIQUE constraint failed'), 'duplicate template name is rejected');

    const active = source.locations.activeDir;
    const assetSpecs = [
      {
        key: sanitizeAssetKeySegments('active', 'anki_media', ['s021-card-audio.wav']),
        bytes: ASSET_BYTES.ankiAudio,
        role: 'card-audio',
        reference: { kind: 'anki-template-media', owner: 's021-template-fixture', field: 'front_template' },
      },
      {
        key: sanitizeAssetKeySegments('active', 'chat_attachments', ['s021-session', 'attachment.txt']),
        bytes: ASSET_BYTES.chatAttachment,
        role: 'chat-attachment',
        reference: { kind: 'chat-v2-attachment', owner: 's021-chat-attachment', field: 'storage_path' },
      },
      {
        key: sanitizeAssetKeySegments('active', 'chat_attachments', ['s021-session', 'restorable.txt']),
        bytes: ASSET_BYTES.restorable,
        role: 'restorable-bytes',
        reference: { kind: 'logical-byte-reference', owner: 's021-restorable', field: 'content_hash' },
      },
    ];
    for (const spec of assetSpecs) {
      assert.equal(isSyncedAssetKey(spec.key), true, `${spec.key}: S0.21 root whitelist`);
      const path = assetLocalPath(active, active, spec.key);
      assert.ok(path, `${spec.key}: safe production path`);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, spec.bytes);
      utimesSync(path, new Date(FIXED_CREATED), new Date(FIXED_CREATED));
      spec.path = path;
      spec.sha256 = await calculateFileHash(path);
      spec.size = spec.bytes.length;
    }
    assert.ok(assetLocalPath(active, active, 'active/databases/vfs.db') === null, 'database path stays outside the asset whitelist');
    const symlinkPath = join(active, 'anki_media', 's021-disclosure-link');
    const { symlinkSync } = await import('node:fs');
    symlinkSync('/etc/passwd', symlinkPath);
    try {
      const [scan] = await scanAssets(active, active);
      assert.equal(scan.has('active/anki_media/s021-disclosure-link'), false, 'symlinked file is not scanned');
      for (const spec of assetSpecs) assert.ok(scan.has(spec.key), `${spec.key}: production scan`);
      const references = assetSpecs.map(spec => ({
        key: spec.key, sha256: spec.sha256, size: spec.size, role: spec.role, ...spec.reference,
      }));
      const referenceValidation = await validateAssetReferences(scan, references, calculateFileHash);
      assert.equal(referenceValidation.rejected.length, 0);
      assert.equal(referenceValidation.accepted.length, 3);
      const duplicateReference = await validateAssetReferences(scan, [...references, references[0]], calculateFileHash);
      const badHash = await validateAssetReferences(scan, [{ ...references[0], sha256: '0'.repeat(64) }], calculateFileHash);
      assert.equal(duplicateReference.rejected.length, 1);
      assert.equal(badHash.rejected.length, 1);

      const restorable = assetSpecs.find(spec => spec.role === 'restorable-bytes');
      const state = new SqliteSyncStateStore(() => target.database('sync_state'));
      const files = {
        mtimeMs: targetPath => stat(targetPath).then(value => value.mtimeMs).catch(() => null),
        lstat: targetPath => lstat(targetPath).then(value => ({ symlink: value.isSymbolicLink(), directory: value.isDirectory() })).catch(() => null),
        readDir: readdir,
        exists: targetPath => stat(targetPath).then(() => true).catch(() => false),
        remove: targetPath => rm(targetPath),
      };
      const support = createSyncSupport({ state, notes: () => undefined, logger, localFiles: files });
      const tombstoneStorage = new TombstoneMemoryStorage();
      // The memory double already implements the legacy snake_case tombstone API.
      await support.tombstones.uploadAssetTombstones(tombstoneStorage, plainCodec, 's021-device-b', {
        entries: { [restorable.key]: { deleted_at: FIXED_TOMBSTONE, device_id: 's021-device-b', size: restorable.size } },
        updated_at: FIXED_TOMBSTONE,
      });
      const downloadedTombstones = await support.tombstones.downloadAssetTombstones(tombstoneStorage, plainCodec);
      assert.equal(downloadedTombstones.entries[restorable.key].deleted_at, FIXED_TOMBSTONE);
      support.tombstones.validateTombstoneTimestamp(downloadedTombstones.entries[restorable.key].deleted_at, restorable.key);
      const [filtered] = support.tombstones.filterAssetTombstonesAfter(downloadedTombstones, 0n);
      assert.equal(Object.hasOwn(filtered.entries, restorable.key), true);
      rmSync(restorable.path);
      assert.equal(await lstat(restorable.path).then(() => true).catch(() => false), false);
      writeFileSync(restorable.path, restorable.bytes);
      utimesSync(restorable.path, new Date(FIXED_UPDATED), new Date(FIXED_UPDATED));
      const restoredHash = await calculateFileHash(restorable.path);
      assert.equal(restoredHash, restorable.sha256);

      const baselineShape = tableShape(baselineMistakes, 'custom_anki_templates');
      const newShape = tableShape(sourceMistakes, 'custom_anki_templates');
      const addedColumns = newShape.columns.filter(column => !baselineShape.columns.some(previous => previous.name === column.name)).map(column => column.name);
      const addedIndexes = newShape.indexes.filter(index => !baselineShape.indexes.some(previous => previous.name === index.name)).map(index => index.name).sort();
      const addedTriggers = newShape.triggers.filter(trigger => !baselineShape.triggers.some(previous => previous.name === trigger.name)).map(trigger => trigger.name).sort();
      assert.deepEqual(addedColumns, ['device_id', 'local_version', 'deleted_at']);
      assert.deepEqual(addedTriggers, [
        'trg__change_log_custom_anki_templates_delete',
        'trg__change_log_custom_anki_templates_insert',
        'trg__change_log_custom_anki_templates_update',
      ]);
      assert.deepEqual(addedIndexes, [
        'idx_custom_anki_templates_deleted_at',
        'idx_custom_anki_templates_device_id',
        'idx_custom_anki_templates_device_version',
        'idx_custom_anki_templates_local_version',
        'idx_custom_anki_templates_updated_at',
        'idx_custom_anki_templates_updated_not_deleted',
      ]);

      const templateClassification = syncClassificationRegistry()
        .find(entry => entry.database === 'mistakes' && entry.table_name === 'custom_anki_templates');
      assert.equal(templateClassification.category, 'RowSync');
      assert.equal(templateClassification.conflict_policy, 'Lww');
      assert.equal(rowSyncTables().some(entry => entry.table_name === 'custom_anki_templates'), true);
      const triggerSql = sourceMistakes.all("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'custom_anki_templates' ORDER BY name");
      assert.equal(triggerSql.length, 3);
      assert.ok(triggerSql.every(trigger => trigger.sql.includes('user_modified = 1') && trigger.sql.includes('user_deleted = 1')));

      const fullTemplateRow = conn => conn.get(`SELECT id, name, description, author, version, preview_front,
          preview_back, note_type, fields_json, generation_prompt, front_template, back_template,
          css_style, field_extraction_rules_json, created_at, updated_at, is_active, is_built_in,
          preview_data_json, user_modified, user_deleted, device_id, local_version, deleted_at
        FROM custom_anki_templates WHERE id = 's021-template-fixture'`);
      const templateExpected = fullTemplateRow(sourceMistakes);
      const targetFullRow = fullTemplateRow(target.database('mistakes'));
      assert.deepEqual(targetFullRow, templateExpected);

      const fixture = {
        formatVersion: 1,
        unit: UNIT,
        fixtureClock: {
          created_at: FIXED_CREATED,
          updated_at: FIXED_UPDATED,
          changed_at: FIXED_CHANGED,
          tombstone: FIXED_TOMBSTONE,
        },
        template: {
          createRequest: TEMPLATE_REQUEST,
          updateRequest: update,
          templateId: 's021-template-fixture',
          expectedRow: templateExpected,
          expectedWire: { insert: insertWire, update: updateWire },
          executionPolicy: 'template HTML/CSS/JS is opaque data; applying a row never executes it',
        },
        assets: assetSpecs.map(spec => ({
          key: spec.key,
          role: spec.role,
          root: `<active>/${spec.key.split('/').slice(1).join('/')}`,
          size: spec.size,
          sha256: spec.sha256,
          bytesHex: spec.bytes.toString('hex'),
          reference: spec.reference,
        })),
        logicalReferences: {
          templateCard: { templateId: 's021-template-fixture', cardIds: ['s021-card-logical'], reference: 'anki_cards.template_id' },
          chatAttachment: { recordId: 's021-chat-attachment', storagePath: assetSpecs[1].key, contentHash: assetSpecs[1].sha256 },
        },
        tombstone: {
          key: assetSpecs[2].key,
          deletedAt: FIXED_TOMBSTONE,
          restoredSha256: assetSpecs[2].sha256,
        },
      };

      const contract = {
        formatVersion: 1,
        unit: UNIT,
        status: {
          sourceIncrement: 'delivered and callable on the locked desktop checkout',
          packaging: 'not completed; no APK or Gradle artifact is claimed',
          distribution: 'not completed',
        },
        sourceDelta: {
          loader: 'desktop-increment/loader.mjs',
          installer: 'desktop-increment/templates-assets.mjs',
          installApi: 'installTemplateAssetIncrement()',
          genericApi: 'registerIncrement({ unit, id, files })',
          returnedApi: ['unit', 'id', 'files[].path', 'files[].originalSha256', 'unregister()'],
          files: hook.files,
          anchors: increment.TEMPLATE_ASSET_SOURCE_ANCHORS.map(({ path, marker, symbol }) =>
            sourceAnchor(path, marker, symbol)),
          migrationPreparation: 'prepareTemplateMigrations(parent) copies locked historical SQL plus coordinated SQL into a caller-owned temporary directory',
          lockedDesktopSource: 'read-only; no original file is rewritten',
        },
        classification: {
          before: { category: 'BackupOnly', conflictPolicy: 'NoConflict' },
          after: templateClassification,
        },
        migrations: {
          coordinatedFile: 'config/schema/coordinated/mistakes/V20261006__portable_anki_templates.sql',
          coordinatedSha256: sha256(readFileSync(resolve(root, 'config/schema/coordinated/mistakes/V20261006__portable_anki_templates.sql'))),
          historicalSource: 'resources/app/migrations/mistakes',
          historicalSourceUnchanged: true,
          upstream: {
            commit: DESKTOP_COMMIT,
            migrationLock: { file: MIGRATION_LOCK, sha256: MIGRATION_LOCK_SHA256 },
            vfs: lockHeads.vfs,
          },
          preparedInputs: {
            upstreamVfs: preparedInputs.upstream,
            template: preparedInputs.template,
          },
          baselineVersion: baseline.database('mistakes').pluck('SELECT MAX(version) FROM refinery_schema_history'),
          newVersion: source.database('mistakes').pluck('SELECT MAX(version) FROM refinery_schema_history'),
          baselineDatabases: baselineHeads,
          newDatabases: sourceHeads,
          baselineHistory: migrationHistory(baseline),
          newHistory: migrationHistory(source),
          baselineShape,
          newShape,
          addedColumns,
          addedIndexes,
          addedTriggers,
          userTemplateOnlyTriggers: false,
          userStateTriggers: 'user-created templates and user-modified/user-deleted bundled templates are captured; untouched bundled templates remain local',
        },
        baselineCapture: {
          migrationVersion: baseline.database('mistakes').pluck('SELECT MAX(version) FROM refinery_schema_history'),
          collectedTemplateChanges: baselineTemplateChanges.length,
          reason: 'the locked schema has no template change-log triggers',
        },
        templateCapture: {
          collectedInsertCount: collectedInsert.length,
          collectedUpdateCount: collectedUpdate.length,
          insert: { normalizedChangedAt: FIXED_CHANGED, changeLogIdOmitted: true, wire: insertWire, parsed: parsedInsert },
          update: { normalizedChangedAt: FIXED_CHANGED, changeLogIdOmitted: true, wire: updateWire, parsed: parsedUpdate },
          expectedRow: templateExpected,
          builtinTemplatePolicy: {
            untouchedBuiltinCaptured: false,
            modifiedBuiltinCaptured: builtinModifiedChanges.length === 1,
            capturePredicate: 'is_built_in = 0 OR user_modified = 1 OR user_deleted = 1',
            application: 'user-state rows travel as data; bundle upgrade logic still decides what local template code to install',
          },
        },
        application: {
          targetDevice: 's021-device-b',
          strategy: 'UseCloud',
          result: {
            total_success: application.total_success,
            total_skipped: application.total_skipped,
            total_incomplete_skipped: application.total_incomplete_skipped,
            total_failed: application.total_failed,
            total_conflicts: application.total_conflicts,
            db_errors: application.db_errors,
          },
          targetRow: {
            id: targetTemplate.id,
            version: targetTemplate.version,
            front_template: targetTemplate.front_template,
            back_template: targetTemplate.back_template,
            css_style: targetTemplate.css_style,
            is_built_in: targetTemplate.is_built_in,
            user_modified: users.user_modified,
            user_deleted: users.user_deleted,
          },
          scriptExecuted: globalThis.__s021TemplateExecuted === true,
          trustRule: 'template content arrives as data and is not executed during insert, collect, apply, or read',
        },
        unknownFields: {
          insertedField: 'future_template_metadata',
          wire: unknownWire,
          preservedInRowRead: unknownData.future_template_metadata,
          preservedInVault: unknownVault.future_template_metadata,
          applyFailures: unknownResult.total_failed,
        },
        negatives: {
          duplicateName: { rejected: duplicateName !== null, reason: duplicateName?.includes('UNIQUE constraint failed') ? 'UNIQUE constraint failed: custom_anki_templates.name' : duplicateName },
          duplicateReference: duplicateReference.rejected,
          hashMismatch: badHash.rejected,
          protectedDatabasePath: { key: 'active/databases/vfs.db', localPath: assetLocalPath(active, active, 'active/databases/vfs.db') },
        },
        assets: {
          roots: ['active/anki_media', 'active/chat_attachments'],
          fixture: fixture.assets,
          scan: [...scan.entries()].map(([key, entry]) => ({
            key,
            sha256: entry.sha256,
            size: Number(entry.size),
            updatedAt: entry.updated_at,
            path: normalizeAssetPath(entry.path, active),
          })),
          references: referenceValidation,
          symlinkFileExcluded: true,
          tombstone: {
            key: assetSpecs[2].key,
            encoding: { bigint: '{$bigint:decimal}' },
            manifestEntry: plain(downloadedTombstones.entries[assetSpecs[2].key]),
            filteredForFreshWatermark: Object.hasOwn(filtered.entries, assetSpecs[2].key),
            restoredSha256: restoredHash,
            bytesSurvivedReplayAndRestore: restoredHash === assetSpecs[2].sha256,
            verificationScope: 'local storage/tombstone/SQL readback only; full transport is parent-owned asset-transport evidence',
          },
        },
        checks: {
          baselineCannotCaptureTemplate: baselineTemplateChanges.length === 0,
          newClassificationExercised: collectedInsert.length === 1 && rowSyncTables().some(entry => entry.table_name === 'custom_anki_templates'),
          insertAndUpdateApplied: application.total_failed === 0 && targetTemplate.version === '1.0.1',
          duplicateNameRejected: duplicateName !== null,
          duplicateReferenceRejected: duplicateReference.rejected.length === 1,
          hashMismatchRejected: badHash.rejected.length === 1,
          unknownFieldsPreserved: true,
          localUserFlagsAreData: users.user_modified === 0 && users.user_deleted === 0,
          templateScriptNotExecuted: globalThis.__s021TemplateExecuted !== true,
          assetBytesKnownAndHashed: assetSpecs.every(spec => sha256(spec.bytes) === spec.sha256),
          tombstoneDeleteRestore: restoredHash === assetSpecs[2].sha256,
          modifiedBuiltinCaptured: builtinModifiedChanges.length === 1,
          untouchedBuiltinNotCaptured: sourceMistakes.pluck("SELECT COUNT(*) FROM __change_log WHERE table_name = 'custom_anki_templates' AND record_id = 's021-builtin-untouched' AND operation = 'INSERT'") === 0,
          sourceLocked: hook.files.every(file => file.originalSha256.length === 64),
          upstreamVfsApplied: baselineHeads.vfs.version === UPSTREAM_VFS_VERSION && sourceHeads.vfs.version === UPSTREAM_VFS_VERSION,
          templateSqlBytesUnchanged: preparedInputs.template.sha256 === sha256(readFileSync(
            resolve(root, 'config/schema/coordinated', preparedInputs.template.file))),
          sourceAnchorsCurrent: increment.TEMPLATE_ASSET_SOURCE_ANCHORS.every(({ path, marker }) =>
            readFileSync(resolve(desktop, path), 'utf8').includes(marker)),
        },
        fixtureFile: FIXTURE,
        notes: {
          chatAttachmentPromotion: 'Parent-owned adapter; this artifact does not claim a production promotion handler.',
          packaging: 'Source-level increment only; packaging and distribution are not completed.',
          timeNormalization: 'change-log clock values are fixed before serialization; no random IDs or temporary paths enter the fixture',
        },
      };
      return { contract, fixture };
    } finally {
      const { unlinkSync } = await import('node:fs');
      try { unlinkSync(symlinkPath); } catch { /* already removed */ }
    }
  } finally {
    for (const storage of storages) storage.shutdown();
    setLocalRuntime(null);
    setLocalFs(null);
    hook.unregister();
    rmSync(temp, { recursive: true, force: true });
  }
}

export async function run(mode) {
  const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--worker'], {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  const data = JSON.parse(output);
  artifact(CONTRACT, data.contract, mode);
  artifact(FIXTURE, data.fixture, mode);
  log('templates-assets.accepted', {
    unit: UNIT,
    mode,
    checks: data.contract.checks,
    migration: {
      baseline: data.contract.migrations.baselineVersion,
      new: data.contract.migrations.newVersion,
      upstreamVfs: data.contract.migrations.upstream.vfs.version,
      coordinatedSha256: data.contract.migrations.coordinatedSha256,
    },
  });
}

if (process.argv[2] === '--worker') {
  worker().then(value => {
    process.stdout.write(JSON.stringify(value, (_key, item) => typeof item === 'bigint' ? { $bigint: item.toString() } : item));
  }).catch(error => {
    log('templates-assets.failed', { unit: UNIT, message: error.message, stack: error.stack });
    process.exitCode = 1;
  });
} else {
  main(import.meta.url, run);
}
