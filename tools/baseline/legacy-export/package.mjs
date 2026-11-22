import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { desktop, readJson, root, sha256 } from '../lib.mjs';

export const loadDesktop = file => import(pathToFileURL(resolve(desktop, file)).href);

const CORE = ['vfs', 'llm_usage', 'chat_v2', 'mistakes'];
const ARCHIVE = { vfs: 'vfs.db', llm_usage: 'llm_usage.db', chat_v2: 'chat_v2.db', mistakes: 'mistakes.db', audit: 'databases/audit.db' };
const SQLITE_HEADER = 'SQLite format 3\0';

const KNOWN_SCHEMA_DELTAS = {
  missingEverywhere: ['__governance_schema_fingerprints'],
  extraEverywhere: ['__governance_schema_fingerprints'],
  extra: {
    mistakes: ['pending_memory_candidates'],

  },
  extraIndexPrefixes: { mistakes: ['idx_pending_mem_'] },
};

function header(bytes) {
  return new TextDecoder('ascii').decode(bytes.subarray(0, SQLITE_HEADER.length));
}

export function declaredVersions(manifest) {
  const versions = {};
  for (const id of CORE) {
    const version = manifest.schema_versions?.[id];
    assert.ok(Number.isSafeInteger(version) && version > 0, `manifest.schema_versions.${id} 缺失或不是正版本号`);
    versions[id] = version;
  }
  return versions;
}

export async function readArchive(zipPath) {
  const { ZipReader, memoryZipSource } = await loadDesktop('host/platform/zip/core/index.ts');
  const bytes = readFileSync(zipPath);
  const reader = await ZipReader.open(memoryZipSource(bytes), {});
  const entries = [];
  const data = new Map();
  for (const entry of reader.entries) {
    assert.ok(!data.has(entry.name), `重复 ZIP 条目 ${entry.name}`);
    assert.ok(!entry.name.startsWith('/') && !entry.name.includes('\\') && !/^[A-Za-z]:/.test(entry.name) && entry.name.split('/').every(part => part !== '..' && part !== '.'), `不安全 ZIP 路径 ${entry.name}`);
    const content = await reader.readEntry(entry);
    data.set(entry.name, content);
    entries.push({
      name: entry.name,
      size: entry.size,
      compression: entry.compression,
      crc32: entry.crc32,
      isDirectory: entry.isDirectory,
      sha256: sha256(content),
    });
  }
  return { bytes, entries, data, reader };
}

/** 把 zip 里的库解到临时目录，用桌面 `Connection` 打开。 */
export async function openDatabases(archive, tempRoot) {
  const { Connection } = await loadDesktop('host/platform/storage/connection.ts');
  const opened = new Map();
  try {
    for (const entry of archive.entries) {
      if (!entry.name.endsWith('.db') || entry.isDirectory) continue;
      const path = join(tempRoot, entry.name.replaceAll('/', '_'));
      writeFileSync(path, archive.data.get(entry.name));
      opened.set(entry.name, { path, connection: new Connection(path, { label: entry.name, profile: { foreignKeys: true } }) });
    }
    return opened;
  } catch (error) {
    for (const entry of opened.values()) entry.connection.close();
    throw error;
  }
}

/** 每个库的 `refinery_schema_history` 与 schema 对象集合。 */
export function describeDatabase(connection, id) {
  const hasHistory = connection.pluck(
    "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='refinery_schema_history')",
  ) === 1;
  const history = hasHistory
    ? connection.all('SELECT version, name, checksum FROM refinery_schema_history ORDER BY version')
    : [];
  const objects = connection.all("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name");
  return {
    id,
    version: history.length > 0 ? Number(history[history.length - 1].version) : 0,
    history,
    tables: objects.filter(row => row.type === 'table').map(row => row.name),
    indexes: objects.filter(row => row.type === 'index' && !row.name.startsWith('sqlite_autoindex')).map(row => row.name),
    triggers: objects.filter(row => row.type === 'trigger').map(row => row.name),
    integrity: connection.pluck('PRAGMA integrity_check'),
    foreignKeyViolations: connection.all('PRAGMA foreign_key_check').length,
  };
}

/**
 * 独立指纹：按包 manifest 声明的库版本重放迁移前缀后计算，
 * 不把旧包的版本当成当前桌面全量 head。
 */
export async function computeFingerprints(connections, versions) {
  const { loadSqlMigrations } = await loadDesktop('host/platform/storage/migration/refinery.ts');
  const { MigrationSet } = await loadDesktop('host/platform/storage/migration/definitions.ts');
  const { computeSchemaFingerprint } = await loadDesktop('host/platform/storage/migration/fingerprint.ts');
  const result = {};
  for (const id of CORE) {
    const entry = connections.get(ARCHIVE[id]);
    assert.ok(entry !== undefined, `${id}: 归档中缺少 ${ARCHIVE[id]}`);
    const list = loadSqlMigrations(resolve(desktop, 'resources/app/migrations', id));
    const set = new MigrationSet(id, list.map(migration => ({
      refineryVersion: migration.version, name: migration.name, sql: migration.sql,
      expectedTables: [], expectedColumns: [], expectedIndexes: [], expectedQueries: [], idempotent: false,
    })));
    const version = versions?.[id] ?? entry.connection.pluck('SELECT MAX(version) FROM refinery_schema_history');
    const fingerprint = computeSchemaFingerprint(entry.connection, set, version);
    result[id] = {
      version,
      migrations: list.filter(migration => migration.version <= version).length,
      fingerprint: fingerprint.hash,
      canonicalSchemaSha256: sha256(new TextEncoder().encode(fingerprint.canonicalSchema)),
      scope: {
        tables: [...fingerprint.scope.tables].length,
        indexes: [...fingerprint.scope.indexes].length,
        triggers: [...fingerprint.scope.triggers].length,
      },
    };
  }
  return result;
}

/** S0.08 对拍：history 逐行、version、schema 对象集合、指纹。 */
export function compareWithSchemaBaseline(described, fingerprints, baseline) {
  const failures = [];
  for (const id of CORE) {
    const expected = baseline.databases.find(database => database.id === id);
    assert.ok(expected !== undefined, `S0.08 baseline 缺少 ${id}`);
    const actual = described[id];
    if (actual.version !== expected.version) failures.push(`${id}: version ${actual.version} != S0.08 ${expected.version}`);
    if (actual.history.length !== expected.history.length) {
      failures.push(`${id}: refinery 行数 ${actual.history.length} != S0.08 ${expected.history.length}`);
    }
    const limit = Math.min(actual.history.length, expected.history.length);
    for (let index = 0; index < limit; index += 1) {
      const left = actual.history[index];
      const right = expected.history[index];
      if (Number(left.version) !== Number(right.version) || left.name !== right.name || String(left.checksum) !== String(right.checksum)) {
        failures.push(`${id}: refinery 第 ${index} 行 ${JSON.stringify(left)} != ${JSON.stringify(right)}`);
        break;
      }
    }
    const expectedObjects = expected.schema.objects;
    const expectedTables = expectedObjects.filter(row => row.type === 'table').map(row => row.name);
    const expectedIndexes = expectedObjects.filter(row => row.type === 'index').map(row => row.name);
    const expectedTriggers = expectedObjects.filter(row => row.type === 'trigger').map(row => row.name);
    const missing = KNOWN_SCHEMA_DELTAS.missingEverywhere;
    const extraTables = actual.tables.filter(name => !expectedTables.includes(name)
      && !KNOWN_SCHEMA_DELTAS.extraEverywhere.includes(name)
      && !(KNOWN_SCHEMA_DELTAS.extra[id] ?? []).includes(name));
    const extraIndexes = actual.indexes.filter(name => !expectedIndexes.includes(name)
      && !(KNOWN_SCHEMA_DELTAS.extraIndexPrefixes[id] ?? []).some(prefix => name.startsWith(prefix)));
    const missingTables = expectedTables.filter(name => !actual.tables.includes(name) && !missing.includes(name));
    const missingIndexes = expectedIndexes.filter(name => !actual.indexes.includes(name) && !missing.includes(name));
    const missingTriggers = expectedTriggers.filter(name => !actual.triggers.includes(name) && !missing.includes(name));
    const extraTriggers = actual.triggers.filter(name => !expectedTriggers.includes(name) && !missing.includes(name));
    if (extraTables.length > 0) failures.push(`${id}: 多出表 ${extraTables.join(',')}`);
    if (missingTables.length > 0) failures.push(`${id}: 缺少表 ${missingTables.join(',')}`);
    if (extraIndexes.length > 0) failures.push(`${id}: 多出索引 ${extraIndexes.join(',')}`);
    if (missingIndexes.length > 0) failures.push(`${id}: 缺少索引 ${missingIndexes.join(',')}`);
    if (extraTriggers.length > 0) failures.push(`${id}: 多出触发器 ${extraTriggers.join(',')}`);
    if (missingTriggers.length > 0) failures.push(`${id}: 缺少触发器 ${missingTriggers.join(',')}`);
    if (actual.integrity !== 'ok') failures.push(`${id}: integrity_check=${actual.integrity}`);
    if (actual.foreignKeyViolations !== 0) failures.push(`${id}: foreign_key_check 有 ${actual.foreignKeyViolations} 行`);
    const fingerprint = fingerprints[id];
    if (fingerprint.version !== expected.version) failures.push(`${id}: 指纹版本 ${fingerprint.version} != S0.08 ${expected.version}`);
    if (fingerprint.fingerprint !== expected.fingerprint) failures.push(`${id}: 指纹 ${fingerprint.fingerprint} != S0.08 ${expected.fingerprint}`);
  }
  return failures;
}

/** manifest ↔ 条目一一对应；每个库的头部、512 对齐、大小与 hash。 */
export function compareManifestWithEntries(manifest, archive) {
  const failures = [];
  const byName = new Map(archive.entries.map(entry => [entry.name, entry]));
  if (byName.has('manifest.json') === false) failures.push('缺少 manifest.json');
  const declared = new Map(manifest.files.map(file => [file.path, file]));
  for (const file of manifest.files) {
    const entry = byName.get(file.path);
    if (entry === undefined) { failures.push(`manifest 声明了不存在的条目 ${file.path}`); continue; }
    if (entry.size !== file.size) failures.push(`${file.path}: 条目大小 ${entry.size} != manifest ${file.size}`);
    if (entry.sha256 !== file.sha256) failures.push(`${file.path}: 条目 sha256 != manifest`);
  }
  for (const entry of archive.entries) {
    if (entry.name === 'manifest.json' || entry.isDirectory) continue;
    if (!declared.has(entry.name)) failures.push(`归档包含 manifest 未声明的条目 ${entry.name}`);
  }
  for (const file of manifest.files) {
    if (!file.path.endsWith('.db')) continue;
    const bytes = archive.data.get(file.path);
    if (bytes === undefined) continue;
    if (header(bytes) !== SQLITE_HEADER) failures.push(`${file.path}: 不是 SQLite 3 文件`);
    if (bytes.byteLength < 512 || bytes.byteLength % 512 !== 0) failures.push(`${file.path}: 大小 ${bytes.byteLength} 未按 512 对齐`);
  }
  return failures;
}

/** coverage ledger / key_policy / snapshot_kind 的事实。 */
export function coverageFacts(manifest) {
  const domains = {};
  for (const [id, domain] of Object.entries(manifest.coverage.domains)) {
    domains[id] = { status: domain.status, paths: [...domain.paths], fileCount: domain.file_count, totalSize: domain.total_size };
  }
  return {
    version: manifest.coverage.version,
    snapshotKind: manifest.snapshot_kind,
    keyPolicy: manifest.key_policy,
    requiredComponents: [...manifest.required_components],
    includedComponents: [...manifest.included_components],
    domains,
    complete: Object.entries(domains).filter(([, domain]) => domain.status === 'complete').map(([id]) => id).sort(),
    absent: Object.entries(domains).filter(([, domain]) => domain.status === 'absent').map(([id]) => id).sort(),
    empty: Object.entries(domains).filter(([, domain]) => domain.status === 'empty').map(([id]) => id).sort(),
  };
}

/** 业务行：从导出的库读回，与 fixture 期望逐项比对。 */
export function readRows(connections) {
  const vfs = connections.get(ARCHIVE.vfs).connection;
  const chat = connections.get(ARCHIVE.chat_v2).connection;
  const mistakes = connections.get(ARCHIVE.mistakes).connection;
  const usage = connections.get(ARCHIVE.llm_usage).connection;
  const audit = connections.get(ARCHIVE.audit).connection;
  return {
    notes: vfs.all('SELECT id, resource_id, title, tags FROM notes ORDER BY id'),
    noteFormats: vfs.all('SELECT note_id, content_format, format_version, serializer_version FROM note_document_formats ORDER BY note_id'),
    revisions: vfs.all('SELECT version_id, note_id, source, content_format FROM note_document_revisions ORDER BY seq'),
    resources: vfs.all('SELECT id, hash, type, source_id, source_table, storage_mode, ref_count FROM resources ORDER BY id'),
    resourceData: vfs.all("SELECT id, data FROM resources WHERE type = 'note' ORDER BY id"),
    blobs: vfs.all('SELECT hash, relative_path, size, mime_type, ref_count FROM blobs ORDER BY hash'),
    files: vfs.all('SELECT id, resource_id, blob_hash, sha256, file_name, size, "type" AS file_type, mime_type FROM files ORDER BY id'),
    folders: vfs.all('SELECT id, title FROM folders ORDER BY id'),
    folderItems: vfs.all('SELECT folder_id, item_type, item_id FROM folder_items ORDER BY item_type, item_id'),
    chatSessions: chat.all('SELECT id, mode, title, persist_status, created_at, updated_at FROM chat_v2_sessions ORDER BY id'),
    chatMessages: chat.all('SELECT id, session_id, role, block_ids_json, timestamp, parent_id FROM chat_v2_messages ORDER BY id'),
    chatBlocks: chat.all('SELECT id, message_id, block_type, status, block_index, content FROM chat_v2_blocks ORDER BY id'),
    mistakes: mistakes.all('SELECT id, mistake_type, status, ocr_text FROM mistakes ORDER BY id'),
    mistakeMessages: mistakes.all('SELECT mistake_id, role, content, timestamp FROM chat_messages ORDER BY id'),
    documentTasks: mistakes.all('SELECT id, document_id, status, segment_index FROM document_tasks ORDER BY id'),
    ankiCards: mistakes.all('SELECT id, task_id, front, back, tags_json, source_type, source_id FROM anki_cards ORDER BY id'),
    settings: mistakes.all('SELECT key, value, updated_at FROM settings ORDER BY key'),
    usageLogs: usage.all('SELECT id, provider, model, caller_type, prompt_tokens, completion_tokens, total_tokens, status FROM llm_usage_logs ORDER BY id'),
    usageDaily: usage.all('SELECT date, caller_type, model, provider, request_count, total_tokens FROM llm_usage_daily ORDER BY date'),
    audit: audit.all('SELECT id, operation_type, target, status FROM __audit_log ORDER BY id'),
  };
}

/** 资源引用：blob 文件真的在归档里，且 hash/大小一致；笔记内容 hash 与 resources.hash 一致。 */
export async function checkReferences(rows, archive, seeds) {
  const failures = [];
  for (const blob of rows.blobs) {
    const entry = archive.entries.find(candidate => candidate.name === `assets/vfs_blobs/${blob.relative_path}`);
    if (entry === undefined) { failures.push(`blob ${blob.hash}: 归档缺少 assets/vfs_blobs/${blob.relative_path}`); continue; }
    if (entry.size !== Number(blob.size)) failures.push(`blob ${blob.hash}: 大小 ${entry.size} != blobs.size ${blob.size}`);
    if (entry.sha256 !== blob.hash) failures.push(`blob ${blob.hash}: 文件 sha256 与 blobs.hash 不一致`);
  }
  for (const file of rows.files) {
    if (file.blob_hash !== null && !rows.blobs.some(blob => blob.hash === file.blob_hash)) {
      failures.push(`files.${file.id}: blob_hash ${file.blob_hash} 没有对应 blobs 行`);
    }
    if (file.sha256 !== file.blob_hash) failures.push(`files.${file.id}: sha256 与 blob_hash 不一致（fixture 用同字节）`);
  }
  const notesById = new Map(rows.notes.map(note => [note.id, note]));
  for (const resource of rows.resourceData) {
    const note = [...notesById.values()].find(candidate => candidate.resource_id === resource.id);
    if (note === undefined) continue;
    const expected = sha256(new TextEncoder().encode(`${note.id}:${resource.data}`));
    const row = rows.resources.find(candidate => candidate.id === resource.id);
    if (row === undefined) { failures.push(`资源 ${resource.id}: resources 行缺失`); continue; }
    if (row.hash !== expected) failures.push(`笔记 ${note.id}: resources.hash 不是 sha256(noteId:content)`);
  }
  for (const item of rows.folderItems) {
    if (item.item_type === 'note' && !notesById.has(item.item_id)) failures.push(`folder_items: 笔记 ${item.item_id} 不存在`);
  }
  if (seeds !== undefined) {
    // 逐项对拍公开 fixture 的期望：行数、标题、内容、文件/blob 标识、会话与卡片 id。
    if (rows.notes.length !== seeds.noteIds.length) failures.push(`笔记行数 ${rows.notes.length} != ${seeds.noteIds.length}`);
    for (const id of seeds.noteIds) if (!notesById.has(id)) failures.push(`笔记 ${id} 不在导出库里`);
    const titles = rows.notes.map(note => note.title).sort();
    if (JSON.stringify(titles) !== JSON.stringify([...seeds.noteTitles].sort())) {
      failures.push(`笔记标题 ${JSON.stringify(titles)} != ${JSON.stringify(seeds.noteTitles)}`);
    }
    for (const [index, id] of seeds.noteIds.entries()) {
      const note = notesById.get(id);
      if (note === undefined) continue;
      const content = rows.resourceData.find(row => row.id === note.resource_id)?.data;
      if (content !== seeds.noteContents[index]) failures.push(`笔记 ${id}: 内容与 fixture 不一致`);
    }
    const file = rows.files.find(candidate => candidate.id === seeds.fileId);
    if (file === undefined) failures.push(`files.${seeds.fileId} 不在导出库里`);
    else {
      if (file.sha256 !== seeds.fileSha256) failures.push(`files.${seeds.fileId}: sha256 与 fixture 不一致`);
      if (Number(file.size) !== seeds.fileBytes) failures.push(`files.${seeds.fileId}: size 与 fixture 不一致`);
    }
    const blobRow = rows.blobs.find(candidate => candidate.hash === seeds.blobHash);
    if (blobRow === undefined) failures.push(`blobs.${seeds.blobHash} 不在导出库里`);
    else if (blobRow.relative_path !== seeds.blobRelativePath) failures.push(`blobs.${seeds.blobHash}: relative_path 与 fixture 不一致`);
    if (!rows.chatSessions.some(session => session.id === seeds.sessionId)) failures.push(`会话 ${seeds.sessionId} 不在导出库里`);
    for (const id of seeds.messageIds) if (!rows.chatMessages.some(message => message.id === id)) failures.push(`消息 ${id} 不在导出库里`);
    if (!rows.chatBlocks.some(block => block.id === seeds.blockId)) failures.push(`块 ${seeds.blockId} 不在导出库里`);
    if (!rows.mistakes.some(mistake => mistake.id === seeds.mistakeId)) failures.push(`错题 ${seeds.mistakeId} 不在导出库里`);
    if (!rows.ankiCards.some(card => card.id === seeds.ankiCardId)) failures.push(`卡片 ${seeds.ankiCardId} 不在导出库里`);
    if (!rows.documentTasks.some(task => task.id === seeds.documentTaskId)) failures.push(`任务 ${seeds.documentTaskId} 不在导出库里`);
    if (!rows.audit.some(row => row.id === seeds.auditId)) failures.push(`审计 ${seeds.auditId} 不在导出库里`);
    for (const key of seeds.settingsKeys) if (!rows.settings.some(setting => setting.key === key)) failures.push(`设置 ${key} 不在导出库里`);
    if (!rows.usageLogs.some(row => row.id === seeds.usageId)) failures.push(`用量 ${seeds.usageId} 不在导出库里`);
  }
  return failures;
}

/** 一次性打开/关闭所有库，返回事实包；调用方负责临时目录。 */
export async function inspectPackage(zipPath, { baseline, seeds } = {}) {
  const archive = await readArchive(zipPath);
  const { parseManifest, validateForSlotRestore } = await loadDesktop('host/plugins/backup/src/manifest-model.ts');
  const manifestBytes = archive.data.get('manifest.json');
  assert.ok(manifestBytes !== undefined, '缺少 manifest.json');
  const manifest = parseManifest(JSON.parse(new TextDecoder().decode(manifestBytes)));
  const versions = declaredVersions(manifest);
  const slotRestore = { ok: true, error: null };
  try { validateForSlotRestore(manifest); }
  catch (error) { slotRestore.ok = false; slotRestore.error = error.message; }

  const tempRoot = mkdtempSync(join(tmpdir(), 'dstu-legacy-verify-'));
  let connections = new Map();
  try {
    connections = await openDatabases(archive, tempRoot);
    const described = {};
    for (const id of CORE) described[id] = describeDatabase(connections.get(ARCHIVE[id]).connection, id);
    for (const id of CORE) {
      assert.equal(described[id].version, versions[id], `${id}: 库版本与 manifest.schema_versions 不一致`);
    }
    const fingerprints = await computeFingerprints(connections, versions);
    const rows = readRows(connections);
    const failures = [
      ...compareManifestWithEntries(manifest, archive),
      ...(slotRestore.ok ? [] : [`validateForSlotRestore: ${slotRestore.error}`]),
      ...(baseline === undefined ? [] : compareWithSchemaBaseline(described, fingerprints, baseline)),
      ...(await checkReferences(rows, archive, seeds)),
    ];
    return {
      manifest,
      slotRestore,
      coverage: coverageFacts(manifest),
      entries: archive.entries,
      zip: { bytes: archive.bytes.length, sha256: sha256(archive.bytes) },
      databases: described,
      fingerprints,
      schemaVersions: versions,
      rows,
      failures,
    };
  } finally {
    for (const entry of connections.values()) entry.connection.close();
    rmSync(tempRoot, { recursive: true, force: true });
  }
}

/** 只读 manifest（sidecar 绑定 snapshot 时需要 zip 的 SHA-256）。 */
export async function manifestOf(zipPath) {
  const { parseManifest } = await loadDesktop('host/plugins/backup/src/manifest-model.ts');
  const archive = await readArchive(zipPath);
  return parseManifest(JSON.parse(new TextDecoder().decode(archive.data.get('manifest.json'))));
}

export const schemaBaseline = () => readJson('config/baseline/schema.json');

/**
 * 历史包的 schema 基线：旧 ZIP 在 145bb387 生成，其版本窗口以 0222ccc 的
 * `config/baseline/schema.json` 为准（vfs 20261111）。不能用当前桌面的
 * S0.08 全量 head 去要求旧包。
 */
export const historicalSchemaBaseline = (commit = '0222ccc') => JSON.parse(
  execFileSync('git', ['-C', root, 'show', `${commit}:config/baseline/schema.json`], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  }),
);
