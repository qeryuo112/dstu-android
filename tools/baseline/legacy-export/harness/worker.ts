import { createSqliteRuntime } from '@dstu/mobile/src/worker/sqlite-runtime.ts';
import { MobileStorageService } from '@dstu/host/platform/storage-mobile/service.ts';
import { MobileBackupService } from '@dstu/host/platform/storage-mobile/backup.ts';
import { createOpfsFiles, mobileRoots } from '@dstu/host/platform/files/opfs.ts';
import { createWebCrypto } from '@dstu/host/platform/crypto/web.ts';
import { mobileLocalRuntime, resetMobileLocalRuntime } from '@dstu/host/platform/files/mobile-local-runtime.ts';
import { setVfsReposLocalRuntime } from '@dstu/host/plugins/vfs-repos/src/local-runtime.ts';
import { ResourceRepo } from '@dstu/host/plugins/vfs-repos/repository/resources.ts';
import { FolderRepo, newFolderItem } from '@dstu/host/plugins/vfs-repos/repository/folders.ts';
import { BlobRepo, computeBlobHash } from '@dstu/host/plugins/vfs-repos/repository/blobs.ts';
import { FileRepo } from '@dstu/host/plugins/vfs-repos/repository/files.ts';
import { IndexStateRepo } from '@dstu/host/plugins/vfs-repos/repository/embedding-repo.ts';
import { IndexUnitRepo } from '@dstu/host/plugins/vfs-repos/repository/index-units.ts';
import { NoteOwnerWritesRepo } from '@dstu/host/plugins/vfs-repos/repository/note-owner-writes.ts';
import { configureNotesRepository, noteRepository } from '@dstu/host/plugins/notes/repository/notes.ts';
import { configureNoteResourceRepos } from '@dstu/host/plugins/notes/repository/resource-deps.ts';
import { configureNotesLogger } from '@dstu/host/plugins/notes/repository/log.ts';
import { createSession } from '@dstu/host/plugins/chat/repository/sessions.ts';
import { createMessage } from '@dstu/host/plugins/chat/repository/messages.ts';
import { createBlock } from '@dstu/host/plugins/chat/repository/blocks.ts';
import { createSettingsStore } from '@dstu/host/platform/settings/index.ts';
import { newUsageRecord } from '@dstu/host/platform/storage/llm-usage-models.ts';
import { insertUsage } from '@dstu/host/platform/storage/llm-usage-repo.ts';
import { saveDocumentTaskWithCardsAtomic } from '@dstu/host/plugins/anki/repository/anki-cards.ts';
import { ensureDefaultFsrsDeckWithConn } from '@dstu/host/plugins/anki/repository/fsrs-decks.ts';
import { sha256HexSync } from '@dstu/host/platform/crypto/sync.ts';
import { FIXTURES } from '@harness/fixtures.mjs';

export const AUDIT_CREATE_TABLE_SQL = `
        CREATE TABLE IF NOT EXISTS __audit_log (
            id TEXT PRIMARY KEY NOT NULL,
            timestamp TEXT NOT NULL,
            operation_type TEXT NOT NULL,
            operation_data TEXT NOT NULL,
            target TEXT NOT NULL,
            status TEXT NOT NULL,
            duration_ms INTEGER,
            details TEXT,
            error_message TEXT
        );

        CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON __audit_log(timestamp);
        CREATE INDEX IF NOT EXISTS idx_audit_log_operation_type ON __audit_log(operation_type);
        CREATE INDEX IF NOT EXISTS idx_audit_log_status ON __audit_log(status);
    `;

const DATA = '/deep-student';
const APP_VERSION = FIXTURES.appVersion;
const ISO = FIXTURES.timestamps.iso;
const ISO_MS = FIXTURES.timestamps.isoMs;

export interface HarnessPayload {
  readonly samples: readonly string[];

  readonly delivery: 'http' | 'opfs';

  readonly capabilities?: SaveCapabilities;
}

export interface SaveCapabilities {
  readonly hasCanShare: boolean;
  readonly canShareFiles: boolean;
  readonly hasShare: boolean;
  readonly hasShowSaveFilePicker: boolean;
  readonly hasAnchorDownload: boolean;
  readonly isSecureContext: boolean;
  readonly userAgent: string;
}

export function saveMethodFor(capabilities: SaveCapabilities | undefined): string {
  if (capabilities === undefined) return 'unknown';
  if (capabilities.hasCanShare && capabilities.canShareFiles && capabilities.hasShare) return 'share';
  if (capabilities.hasShowSaveFilePicker) return 'save-picker';
  return 'download';
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

function fixtureCrypto(real: ReturnType<typeof createWebCrypto>, prefix: string) {
  let counter = 0;
  return {
    ...real,
    randomUUID: () => {
      counter += 1;
      const tail = String(counter).padStart(12, '0');
      return `${prefix}-0000-4000-8000-${tail}`;
    },
  };
}

function makeContext(
  files: unknown,
  crypto: ReturnType<typeof createWebCrypto>,
  calls: unknown[],
  events: unknown[],
  logs: string[],
  capabilities: SaveCapabilities | undefined,
) {
  const logger = (target: string) => {
    const write = (level: string) => (message: string) => {
      if (logs.length < 400) logs.push(`${level} ${target} ${message}`);
    };
    return { error: write('error'), warn: write('warn'), info: write('info'), debug: write('debug'), trace: write('trace') };
  };
  return {
    app: { version: APP_VERSION, dataDir: DATA },
    logger,
    reflect: { get: (name: string) => (name === 'files' ? files : undefined) },
    crypto,
    shell: {
      request: async (op: string, args: unknown) => {
        calls.push({ op, args });
        // 只有 `mobile:save-backup` 有宿主实现（`saveBackupToSystem`）；其余 op 在采集端不存在。
        if (op !== 'mobile:save-backup') throw new Error(`harness 未实现 shell op: ${op}`);
        return { method: saveMethodFor(capabilities) };
      },
    },
    backendEvents: { emit: (channel: string, payload: unknown) => { events.push({ channel, payload }); } },
  };
}

/** 等 OPFS 上真的出现该文件且大小一致（`mobileLocalRuntime` 的落盘是后台异步链）。 */
async function waitForFile(files: { exists(p: string): Promise<boolean>; stat(p: string): Promise<{ size: number }> }, path: string, size: number, label: string): Promise<void> {
  for (let attempt = 0; attempt < 240; attempt += 1) {
    if (await files.exists(path).catch(() => false)) {
      const stat = await files.stat(path).catch(() => null);
      if (stat !== null && stat.size === size) return;
    }
    await sleep(25);
  }
  throw new Error(`OPFS 未在超时内出现 ${label}: ${path} (${size} bytes)`);
}

const progress = (step: string): void => {
  (globalThis as { postMessage?: (value: unknown) => void }).postMessage?.({ progress: `${step} +${Math.round(performance.now())}ms` });
};

const scope = globalThis as {
  postMessage?: (value: unknown) => void;
  onmessage?: ((event: MessageEvent) => void) | null;
};

scope.postMessage?.({ progress: `module-evaluated +${Math.round(performance.now())}ms` });

scope.onmessage = (event: MessageEvent) => {
  void run(event.data as HarnessPayload)
    .then(result => scope.postMessage?.({ ok: true, result }))
    .catch(error => scope.postMessage?.({ ok: false, error: error instanceof Error ? `${error.stack ?? error.message}` : String(error) }));
};

export async function run(payload: HarnessPayload): Promise<Record<string, unknown>> {
  const calls: Array<{ op: string; args: unknown }> = [];
  const events: Array<{ channel: string; payload: unknown }> = [];
  const logs: string[] = [];
  const started = performance.now();
  progress('sqlite-runtime');
  const { runtime, timings } = await createSqliteRuntime();
  const root = await navigator.storage.getDirectory();
  const files = createOpfsFiles({ root, roots: mobileRoots(DATA) });
  const crypto = fixtureCrypto(createWebCrypto(), '5a0e16c0');
  const ctx = makeContext(files, crypto, calls, events, logs, payload.capabilities);
  const storage = new MobileStorageService({
    runtime, baseDir: DATA,
    log: (level, message) => { if (logs.length < 400) logs.push(`${level} storage ${message}`); },
  });
  progress('migrated');
  const report = await storage.migrations.runAll();
  if (!report.success) throw new Error(`migration failed: ${JSON.stringify(report)}`);

  // ---- 生产仓储装配（与 `host/plugins/notes/src/plugin.ts` 的 apply 一致）
  progress('local-runtime');
  resetMobileLocalRuntime();
  const bundle = mobileLocalRuntime(ctx as never, { log: { warn: (message: string) => logs.push(`warn files.mobile ${message}`) } });
  setVfsReposLocalRuntime(bundle.vfsRepos);
  configureNotesLogger({
    error: (message: string) => logs.push(`error notes ${message}`),
    warn: (message: string) => logs.push(`warn notes ${message}`),
    info: (message: string) => logs.push(`info notes ${message}`),
    debug: () => undefined,
  });
  const vfs = storage.database('vfs');
  configureNotesRepository(vfs);
  const resources = new ResourceRepo();
  const folders = new FolderRepo();
  const blobs = new BlobRepo();
  const fileRepo = new FileRepo();
  configureNoteResourceRepos({
    noteOwnerWrites: new NoteOwnerWritesRepo(),
    resources,
    indexStates: new IndexStateRepo(),
    embeddingUnits: new IndexUnitRepo(),
    folders,
  });

  const blobsDir = `${storage.locations.activeDir}/vfs_blobs`;

  progress('seed-folders');
  // ---- 种子 1：文件夹
  folders.createFolder(vfs, {
    id: FIXTURES.folder.id, title: FIXTURES.folder.title, icon: 'folder', isExpanded: true, isFavorite: false,
    sortOrder: 0, createdAt: FIXTURES.timestamps.folderMs, updatedAt: FIXTURES.timestamps.folderMs,
  });

  progress('seed-notes');
  // ---- 种子 2：两条笔记（生产 noteRepository → resources / formats / revisions / note_links）
  const noteOne = noteRepository.createNote(FIXTURES.notes[0], vfs);
  const noteTwo = noteRepository.createNote(FIXTURES.notes[1], vfs);
  folders.addItemToFolder(vfs, newFolderItem(FIXTURES.folder.id, 'note', noteOne.id));
  folders.addItemToFolder(vfs, newFolderItem(FIXTURES.folder.id, 'note', noteTwo.id));

  progress('seed-blob');
  // ---- 种子 3：真实 blob + files 行（BlobRepo 写 OPFS 文件，FileRepo 写元数据）
  const fileBytes = new TextEncoder().encode(FIXTURES.file.text);
  const fileSha = sha256HexSync(fileBytes);
  const blob = blobs.storeBlob(vfs, blobsDir, fileBytes, FIXTURES.file.mimeType, FIXTURES.file.extension);
  const fileRow = fileRepo.createFile(vfs, fileSha, FIXTURES.file.name, fileBytes.byteLength, 'document', FIXTURES.file.mimeType, blob.hash, FIXTURES.file.originalPath);
  folders.addItemToFolder(vfs, newFolderItem(FIXTURES.folder.id, 'file', fileRow.id));
  await waitForFile(files as never, `${blobsDir}/${blob.relativePath}`, fileBytes.byteLength, 'vfs_blobs');

  progress('seed-chat');
  // ---- 种子 4：聊天会话 / 消息 / 块
  const chat = storage.database('chat_v2');
  createSession(chat, {
    id: FIXTURES.chat.sessionId, mode: 'analysis', title: FIXTURES.chat.sessionTitle, persistStatus: 'active',
    titleLocked: false, createdAt: ISO, updatedAt: ISO_MS,
  });
  createMessage(chat, {
    id: FIXTURES.chat.userId, sessionId: FIXTURES.chat.sessionId, role: 'user', blockIds: [],
    timestamp: FIXTURES.timestamps.folderMs,
  });
  createMessage(chat, {
    id: FIXTURES.chat.assistantId, sessionId: FIXTURES.chat.sessionId, role: 'assistant', blockIds: [FIXTURES.chat.blockId],
    timestamp: FIXTURES.timestamps.folderMs + 1000, parentId: FIXTURES.chat.userId,
  });
  createBlock(chat, {
    id: FIXTURES.chat.blockId, messageId: FIXTURES.chat.assistantId, type: 'content', status: 'success',
    blockIndex: 0, content: FIXTURES.chat.answer, startedAt: FIXTURES.timestamps.folderMs + 1000,
    endedAt: FIXTURES.timestamps.folderMs + 1500, firstChunkAt: FIXTURES.timestamps.folderMs + 1100,
  });

  progress('seed-mistakes');
  // ---- 种子 5：错题 + 错题聊天（生产 TS 侧没有写入仓储；SQL 与 `mobile/src/worker/diagnostics-plugin.ts:224` 同形）
  const mistakes = storage.database('mistakes');
  mistakes.run(
    `INSERT INTO mistakes
       (id, created_at, question_images, analysis_images, user_question, ocr_text, ocr_note,
        tags, mistake_type, status, chat_category, updated_at, last_accessed_at)
     VALUES (?1, ?2, '[]', '[]', ?3, ?4, ?5, ?6, ?7, 'completed', 'analysis', ?2, '1970-01-01T00:00:00Z')`,
    [FIXTURES.mistakes.id, ISO, FIXTURES.mistakes.question, FIXTURES.mistakes.ocrText,
      FIXTURES.mistakes.ocrNote, '["fixture"]', FIXTURES.mistakes.type],
  );
  mistakes.run(
    `INSERT INTO chat_messages (mistake_id, role, content, timestamp) VALUES (?1, 'user', ?2, ?3)`,
    [FIXTURES.mistakes.id, FIXTURES.mistakes.message, ISO],
  );

  progress('seed-anki');
  // ---- 种子 6：Anki 任务 + 卡片（生产 `saveDocumentTaskWithCardsAtomic` + 默认 FSRS deck）
  ensureDefaultFsrsDeckWithConn(mistakes, ISO);
  saveDocumentTaskWithCardsAtomic(mistakes, {
    id: FIXTURES.anki.taskId, document_id: FIXTURES.anki.documentId, original_document_name: FIXTURES.file.name,
    segment_index: 0, content_segment: '公开 fixture 段落。', status: 'Completed',
    created_at: ISO, updated_at: ISO, error_message: null, anki_generation_options_json: '{}',
  }, [{
    id: FIXTURES.anki.cardId, task_id: FIXTURES.anki.taskId, front: FIXTURES.anki.front, back: FIXTURES.anki.back,
    text: null, tags: ['fixture'], images: [], is_error_card: false, error_content: null,
    created_at: ISO, updated_at: ISO, extra_fields: {}, template_id: null,
  }]);

  progress('seed-settings');
  // ---- 种子 7：设置（生产 settings 仓储；非敏感键）
  const settings = createSettingsStore({ db: () => mistakes, secureStore: () => null, isSensitiveKey: () => false });
  settings.save(FIXTURES.settings.themeKey, FIXTURES.settings.themeValue);
  settings.save(FIXTURES.settings.folderKey, FIXTURES.folder.id);

  progress('seed-usage');
  // ---- 种子 8：LLM 用量（生产 `newUsageRecord` + `insertUsage`）
  const usage = newUsageRecord(FIXTURES.usage.callerType, FIXTURES.usage.modelId, FIXTURES.usage.promptTokens, FIXTURES.usage.completionTokens)
    .withCallerId(FIXTURES.chat.sessionId).withAdapter('openai-compatible').withDuration(1500).build();
  insertUsage(storage.database('llm_usage'), usage);
  // `llm_usage_daily` 是统计投影表，生产 TS 侧只有读取与 sync apply；按列直接写一行（artifact 标注来源）。
  storage.database('llm_usage').run(
    `INSERT INTO llm_usage_daily
       (date, caller_type, model, provider, request_count, success_count, error_count,
        total_prompt_tokens, total_completion_tokens, total_tokens, total_cost_estimate, created_at, updated_at)
     VALUES (?2, ?3, ?4, ?5, 1, 1, 0, ?6, ?7, ?8, 0, ?1, ?1)`,
    [ISO, FIXTURES.usage.date, FIXTURES.usage.callerType, FIXTURES.usage.modelId, FIXTURES.usage.provider,
      FIXTURES.usage.promptTokens, FIXTURES.usage.completionTokens,
      FIXTURES.usage.promptTokens + FIXTURES.usage.completionTokens],
  );

  progress('seed-audit');
  // ---- 种子 9：审计库（移动端只在文件存在时纳入备份；DDL 见本文件顶部常量）
  const audit = storage.database('audit');
  audit.exec(AUDIT_CREATE_TABLE_SQL);
  audit.run(`INSERT INTO __audit_log
      (id, timestamp, operation_type, operation_data, target, status, duration_ms, details, error_message)
      VALUES (?1, ?2, ?3, ?4, ?5, 'Completed', ?6, ?7, NULL)`,
  [FIXTURES.audit.id, ISO, FIXTURES.audit.operationType,
    JSON.stringify({ type: 'Migration', from_version: 20260130, to_version: 20261111, applied_count: 71 }),
    FIXTURES.audit.target, 812, JSON.stringify({ job_id: 'legacy-export-fixture' })]);

  progress('export');
  // ---- 导出：生产 MobileBackupService，两次真实导出（标准样本 + continuity 样本）
  const backup = new MobileBackupService({ ctx: ctx as never, storage, runtime: runtime as never, files });
  const exported: Array<Record<string, unknown>> = [];
  for (const sample of payload.samples) {
    const id = `legacy-${sample}-fixture`;
    const result = await backup.exportBackup(id, null);
    const bytes = await files.readBytes(result.zipPath);
    let delivered: Record<string, unknown>;
    if (payload.delivery === 'opfs') {
      const target = `${storage.locations.baseDir}/recovery/backups/one-shot-${sample}.zip`;
      await files.writeBytes(target, bytes, { createParents: true });
      delivered = { method: 'opfs', path: target };
    } else {
      const response = await fetch(`/result/zip/${sample}`, { method: 'POST', body: bytes });
      if (!response.ok) throw new Error(`upload ${sample} failed: ${response.status}`);
      delivered = { method: 'http', status: response.status };
    }
    // 生产入口 `startExport(id, outputPath=null, deliver=true)` 会在导出后调用 `deliver()`：
    // 这里同样走 `MobileBackupService.deliver`（→ `mobile:save-backup`）。采集端的 ctx.shell 是
    // **记录型替身**（返回 `{method:'download'}`），所以这个值只证明“Host 侧调用链与返回形状”，
    // 不代表真机结果；真机保存分支由页面里的能力探针 + 源码事实判定（artifact.channelFacts）。
    const deliverMethod = payload.delivery === 'opfs' ? 'opfs' : await backup.deliver(result.zipPath, `${id}.zip`);
    exported.push({
      sample, backupId: id, zipPath: result.zipPath, fileCount: result.fileCount,
      totalSize: result.totalSize, compressedSize: result.compressedSize,
      zipBytes: bytes.byteLength, zipSha256: sha256HexSync(bytes), delivered,
      deliverMethod,
      deliverMethodSource: payload.delivery === 'opfs'
        ? 'one-shot worker（不经过 shell 通道）'
        : '由页面能力快照按 saveBackupToSystem 分支顺序推导（非真机实测）',
      deliverMethodCapabilities: payload.capabilities ?? null,
    });
  }

  progress('read-back');
  // ---- 回读：导出不改动活动槽，库内行与种子一致
  const readBack = {
    notes: vfs.all(`SELECT id, resource_id, title, tags FROM notes ORDER BY id`),
    noteFormats: vfs.all(`SELECT note_id, content_format, format_version, serializer_version FROM note_document_formats ORDER BY note_id`),
    revisions: vfs.all(`SELECT version_id, note_id, source, content_format FROM note_document_revisions ORDER BY seq`),
    resources: vfs.all(`SELECT id, hash, type, source_id, source_table, storage_mode, ref_count FROM resources ORDER BY id`),
    blobs: vfs.all(`SELECT hash, relative_path, size, mime_type, ref_count FROM blobs ORDER BY hash`),
    files: vfs.all(`SELECT id, resource_id, blob_hash, sha256, file_name, size, "type" AS file_type, mime_type FROM files ORDER BY id`),
    folderItems: vfs.all(`SELECT folder_id, item_type, item_id FROM folder_items ORDER BY item_type, item_id`),
    chatSessions: chat.all(`SELECT id, mode, title, persist_status, created_at, updated_at FROM chat_v2_sessions ORDER BY id`),
    chatMessages: chat.all(`SELECT id, session_id, role, block_ids_json, timestamp, parent_id FROM chat_v2_messages ORDER BY id`),
    chatBlocks: chat.all(`SELECT id, message_id, block_type, status, block_index, content FROM chat_v2_blocks ORDER BY id`),
    mistakes: mistakes.all(`SELECT id, mistake_type, status, ocr_text FROM mistakes ORDER BY id`),
    mistakeMessages: mistakes.all(`SELECT mistake_id, role, content, timestamp FROM chat_messages ORDER BY id`),
    documentTasks: mistakes.all(`SELECT id, document_id, status, segment_index FROM document_tasks ORDER BY id`),
    ankiCards: mistakes.all(`SELECT id, task_id, front, back, tags_json, source_type, source_id FROM anki_cards ORDER BY id`),
    settings: mistakes.all(`SELECT key, value, updated_at FROM settings ORDER BY key`),
    usageLogs: storage.database('llm_usage').all(`SELECT id, provider, model, caller_type, prompt_tokens, completion_tokens, total_tokens, status FROM llm_usage_logs ORDER BY id`),
    usageDaily: storage.database('llm_usage').all(`SELECT date, caller_type, model, provider, request_count, total_tokens FROM llm_usage_daily ORDER BY date`),
    audit: audit.all('SELECT id, operation_type, target, status FROM __audit_log ORDER BY id'),
  };
  const schemaHistory: Record<string, unknown> = {};
  const versions: Record<string, number> = {};
  for (const name of ['vfs', 'chat_v2', 'mistakes', 'llm_usage'] as const) {
    schemaHistory[name] = storage.database(name).all('SELECT version, name, checksum FROM refinery_schema_history ORDER BY version');
    versions[name] = storage.migrations.currentVersion(name);
  }

  return {
    environment: {
      sqlite: runtime.sqlite3.capi.sqlite3_libversion(),
      userAgent: navigator.userAgent,
      origin: location.origin,
      secureContext: isSecureContext,
      opfsPoolDirectory: '.deep-student',
      migrationOrder: ['vfs', 'llm_usage', 'chat_v2', 'mistakes'],
      appVersion: APP_VERSION,
      fixtureClock: 'fixed randomUUID sequence; wall clock is real',
    },
    poolTimings: timings,
    elapsedMs: Math.round(performance.now() - started),
    migration: { success: report.success, databases: report.databases },
    versions,
    schemaHistory,
    exported,
    readBack,
    seeds: {
      noteIds: [noteOne.id, noteTwo.id],
      noteTitles: FIXTURES.notes.map(note => note.title),
      noteContents: FIXTURES.notes.map(note => note.content),
      noteHashes: [sha256HexSync(`${noteOne.id}:${FIXTURES.notes[0].content}`), sha256HexSync(`${noteTwo.id}:${FIXTURES.notes[1].content}`)],
      folderId: FIXTURES.folder.id,
      fileSha256: fileSha,
      fileBytes: fileBytes.byteLength,
      fileText: FIXTURES.file.text,
      fileId: fileRow.id,
      blobHash: computeBlobHash(fileBytes),
      blobRelativePath: blob.relativePath,
      sessionId: FIXTURES.chat.sessionId,
      messageIds: [FIXTURES.chat.userId, FIXTURES.chat.assistantId],
      blockId: FIXTURES.chat.blockId,
      mistakeId: FIXTURES.mistakes.id,
      ankiCardId: FIXTURES.anki.cardId,
      documentTaskId: FIXTURES.anki.taskId,
      usageId: usage.id,
      auditId: FIXTURES.audit.id,
      settingsKeys: [FIXTURES.settings.themeKey, FIXTURES.settings.folderKey],
    },
    shellCalls: calls,
    events,
    logs,
  };
}
