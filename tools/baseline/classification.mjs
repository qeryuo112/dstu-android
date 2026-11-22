import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { artifact, main, root, desktop, readDesktop, readJson, sha256, log, sourceFiles, assertUnique } from './lib.mjs';

const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const schemaFile = 'config/baseline/schema.json';
const planFile = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const registryFiles = [
  'host/plugins/sync/src/classification.ts',
  'host/plugins/sync/src/classification-registry.ts',
  'host/plugins/sync/src/classification-data/vfs-core.ts',
  'host/plugins/sync/src/classification-data/vfs-notes.ts',
  'host/plugins/sync/src/classification-data/chat-v2.ts',
  'host/plugins/sync/src/classification-data/mistakes.ts',
  'host/plugins/sync/src/classification-data/llm-usage.ts',
  'host/plugins/sync/src/classification-data/settings.ts',
];
const transportFiles = [
  'host/plugins/sync/src/preferences-sync.ts',
  'host/plugins/sync/src/file-level-sync.ts',
  'host/plugins/sync/src/file-tombstone-sync.ts',
  'host/plugins/sync/src/asset-paths.ts',
  'host/plugins/sync/src/asset-sync.ts',
  'host/plugins/sync/src/asset-manifest.ts',
  'host/plugins/sync/src/blob-sync.ts',
  'host/plugins/sync/src/workspace-manifest.ts',
  'host/plugins/sync/src/plugin-sync-sandbox.ts',
  'host/plugins/sync/src/change-log.ts',
  'host/plugins/sync/src/collect-sync-changes.ts',
  'host/plugins/sync/src/device-pairing.ts',
  'host/plugins/sync/src/field-merge.ts',
  'host/plugins/sync/repository/registry-preflight.ts',
  'host/plugins/sync/repository/apply-record.ts',
  'host/plugins/sync/repository/record-data.ts',
  'host/plugins/sync/src/run-sync.ts',
  'host/platform/secrets/sensitive-key.ts',
  'host/platform/settings/api.ts',
  'host/platform/storage/pending-memory-candidates-schema.ts',
  'host/plugins/anki/src/plugin.ts',
  'host/plugins/anki/src/plugin-handlers-media.ts',
  'host/plugins/anki/repository/apkg-export.ts',
  'host/plugins/chat/src/attachment-stage.ts',
  'host/plugins/chat-pipeline/repository/memory-flush.ts',
  'host/plugins/chat/repository/legacy-migration.ts',
  'host/plugins/review/src/fsrs7.ts',
  'host/plugins/vfs-index/repository/chat-lance.ts',
  'host/plugins/vfs-repos/repository/attachments.ts',
  'host/plugins/vfs-index/index.ts',
  'host/plugins/backup/src/migrations.ts',
  'host/plugins/backup/src/operations.ts',
  'host/plugins/backup/src/asset-catalog.ts',
  'host/plugins/backup/src/manifest-model.ts',
  'src/hooks/useTheme.ts',
  'src/features/settings/components/AppearanceTab.tsx',
  'src/features/chat/skills/skillSettingsMirror.ts',
  'src/command-palette/registry/shortcutManager.ts',
  'src/features/pdf/stores/pdfSettingsStore.ts',
  'src/features/chat/core/session/permissionPresetDefaults.ts',
  'src/components/translation/PromptPanel.tsx',
  'src/features/pomodoro/stores/usePomodoroStore.ts',
  'src/features/settings/components/DevicePairingSection.tsx',
];
const evidenceFiles = [...new Set([...registryFiles, ...transportFiles])];
const mobileRuntime = /(?:^|\/)storage-mobile\//;
const reviewQuestionFsrsColumns = [
  'fsrs_stability',
  'fsrs_difficulty',
  'fsrs_phase',
  'fsrs_learning_step',
  'fsrs_lapses',
  'fsrs_due_at',
  'fsrs_last_review_at',
];

const tableUnits = {
  'vfs.resources': 'S1.06', 'vfs.notes': 'S1.06', 'vfs.files': 'S1.06', 'vfs.folders': 'S1.06', 'vfs.folder_items': 'S1.06',
  'vfs.blobs': 'S2.06', 'vfs.note_tags': 'S1.06', 'vfs.note_links': 'S5.24', 'vfs.path_cache': 'S1.06', 'vfs.notes_fts': 'S4.24',
  'vfs.exam_sheets': 'S7.20', 'vfs.translations': 'S7.32', 'vfs.essays': 'S7.23', 'vfs.essay_sessions': 'S7.23',
  'vfs.mindmaps': 'S5.18', 'vfs.mindmap_versions': 'S5.18',
  'vfs.questions': 'S7.18', 'vfs.answer_submissions': 'S7.21', 'vfs.question_bank_stats': 'S7.18', 'vfs.question_history': 'S7.18',
  'vfs.question_sync_conflicts': 'S2.28', 'vfs.question_sync_logs': 'S2.28', 'vfs.questions_fts': 'S4.24',
  'vfs.qbank_papers': 'S7.20', 'vfs.qbank_practice_attempts': 'S7.21', 'vfs.qbank_practice_drafts': 'S7.21', 'vfs.qbank_practice_jobs': 'S7.21',
  'vfs.qbank_sources': 'S7.17', 'vfs.qbank_materials': 'S7.18', 'vfs.qbank_groups': 'S7.18', 'vfs.qbank_group_materials': 'S7.18',
  'vfs.qbank_generation_tasks': 'S7.17', 'vfs.qbank_import_jobs': 'S7.17',
  'vfs.review_plans': 'S7.24', 'vfs.review_stats': 'S7.24', 'vfs.review_history': 'S7.24', 'vfs.mastery_events': 'S7.24', 'vfs.mastery_states': 'S7.24',
  'vfs.insights': 'S7.33', 'vfs.insight_revisions': 'S7.33', 'vfs.insight_evidence': 'S7.33', 'vfs.insight_relations': 'S7.33',
  'vfs.insight_events': 'S7.33', 'vfs.insight_jobs': 'S7.33', 'vfs.insight_fts': 'S4.24',
  'vfs.todo_lists': 'S7.25', 'vfs.todo_items': 'S7.25', 'vfs.pomodoro_records': 'S7.28', 'vfs.automation_todo_deliveries': 'S7.27',
  'vfs.vfs_study_space_profiles': 'S7.01', 'vfs.vfs_space_mounts': 'S7.01', 'vfs.vfs_anchor_edges': 'S5.24',
  'vfs.vfs_index_units': 'S4.25', 'vfs.vfs_index_segments': 'S4.25', 'vfs.vfs_embedding_dims': 'S4.25',
  'vfs.vfs_index_profiles': 'S4.25', 'vfs.vfs_indexing_config': 'S4.27',
  'vfs.media_transcript_segments': 'S7.31', 'vfs.media_progress': 'S7.31', 'vfs.study_time_daily': 'S7.29',
  'vfs.note_learning_relations': 'S5.24', 'vfs.note_document_revisions': 'S5.23', 'vfs.note_document_formats': 'S5.09',
  'vfs.note_transfer_operations': 'S5.21', 'vfs.note_state': 'S5.21', 'vfs.note_history_retention': 'S5.23',
  'vfs.note_review_save_operations': 'S5.21', 'vfs.note_review_save_receipts': 'S5.21',
  'vfs.note_editor_participants': 'S5.22', 'vfs.note_editor_leases': 'S5.22', 'vfs.note_editor_lease_notes': 'S5.22',
  'vfs.note_editor_lease_acks': 'S5.22', 'vfs.note_editor_write_grants': 'S5.22',
  'vfs.memory_config': 'S3.24', 'vfs.memory_audit_log': 'S3.24', 'vfs.memory_write_idempotency': 'S3.24', 'vfs.notes_versions': 'S5.23',
  'chat_v2.chat_v2_sessions': 'S3.01', 'chat_v2.chat_v2_messages': 'S3.01', 'chat_v2.chat_v2_blocks': 'S3.01',
  'chat_v2.chat_v2_attachments': 'S3.13', 'chat_v2.resources': 'S1.06', 'chat_v2.chat_v2_session_mistakes': 'S3.01',
  'chat_v2.chat_v2_session_groups': 'S3.01', 'chat_v2.workspace_index': 'S3.19', 'chat_v2.chat_v2_goals': 'S3.23',
  'chat_v2.connector_operations': 'S3.17', 'chat_v2.completion_outbox': 'S2.18', 'chat_v2.revocation_epochs': 'S2.23',
  'chat_v2.budget_snapshots': 'S3.11', 'chat_v2.skill_usage': 'S3.20', 'chat_v2.skill_candidates': 'S3.20',
  'chat_v2.chat_v2_session_state': 'S3.01', 'chat_v2.chat_v2_todo_lists': 'S3.27', 'chat_v2.chat_v2_session_tags': 'S3.01',
  'chat_v2.chat_v2_compactions': 'S3.24', 'chat_v2.sleep_block': 'S3.22', 'chat_v2.subagent_task': 'S3.22',
  'chat_v2.chat_v2_content_fts': 'S4.24',
  'mistakes.mistakes': 'S7.21', 'mistakes.chat_messages': 'S3.01', 'mistakes.review_analyses': 'S7.23', 'mistakes.review_chat_messages': 'S7.23',
  'mistakes.review_sessions': 'S7.23', 'mistakes.review_session_mistakes': 'S7.23', 'mistakes.document_tasks': 'S7.17',
  'mistakes.anki_cards': 'S7.08', 'mistakes.anki_decks': 'S7.07', 'mistakes.fsrs_card_states': 'S7.12', 'mistakes.fsrs_review_logs': 'S7.12',
  'mistakes.temp_sessions': 'S7.23', 'mistakes.document_control_states': 'S7.17', 'mistakes.search_logs': 'S2.28',
  'mistakes.exam_sheet_sessions': 'S7.17', 'mistakes.migration_progress': 'S2.03', 'mistakes.vectorized_data': 'S4.27',
  'mistakes.settings': 'S7.35', 'mistakes.rag_configurations': 'S4.27', 'mistakes.custom_anki_templates': 'S7.09',
  'mistakes.automation_definitions': 'S3.25', 'mistakes.automation_runs': 'S3.25', 'mistakes.rag_sub_libraries': 'S4.27',
  'llm_usage.llm_usage_logs': 'S3.11', 'llm_usage.llm_usage_daily': 'S3.11',
};

/* Runtime (non-migration) tables cited by the S0.08 inline DDL, plus templates resolved to their literal names. */
const runtimeCurated = {
  __audit_log: { scope: 'local', adoptionUnit: 'S2.18', reason: 'device audit log, one row per governance operation' },
  refinery_schema_history: { scope: 'local', adoptionUnit: 'S2.02', reason: 'refinery migration bookkeeping' },
  __governance_schema_fingerprints: { scope: 'local', adoptionUnit: 'S2.02', reason: 'managed schema fingerprint record, excluded from the fingerprint scope itself' },
  answer_submissions: { scope: 'portable', adoptionUnit: 'S7.21', reason: 'idempotent repair DDL for an already-registered RowSync table' },
  chat_v2_session_groups: { scope: 'portable', adoptionUnit: 'S3.01', reason: 'repair DDL for an already-registered RowSync table' },
  pending_memory_candidates: { scope: 'local', adoptionUnit: 'S3.24', reason: 'runtime repair after migration verification; outside refinery history and the schema fingerprint' },
  completion_outbox: { scope: 'local', adoptionUnit: 'S2.18', reason: 'repair DDL for an already-registered LocalRuntime table' },
  workspace: { scope: 'local', adoptionUnit: 'S3.19', reason: 'per-session workspace database outside the slot business databases' },
  agent: { scope: 'local', adoptionUnit: 'S3.19', reason: 'workspace database membership row' },
  message: { scope: 'local', adoptionUnit: 'S3.19', reason: 'workspace database message row' },
  inbox: { scope: 'local', adoptionUnit: 'S3.19', reason: 'workspace inbox' },
  document: { scope: 'local', adoptionUnit: 'S3.19', reason: 'workspace document store' },
  context: { scope: 'local', adoptionUnit: 'S3.19', reason: 'workspace context store' },
  sleep_block: { scope: 'local', adoptionUnit: 'S3.22', reason: 'workspace sleep state plus the LocalRuntime chat_v2 copy' },
  subagent_task: { scope: 'local', adoptionUnit: 'S3.22', reason: 'workspace task row plus the LocalRuntime chat_v2 copy' },
  col: { scope: 'local', adoptionUnit: 'S4.16', reason: 'temporary Anki collection built in the OS temp dir for APKG export' },
  notes: { scope: 'local', adoptionUnit: 'S4.16', reason: 'temporary Anki collection table (distinct from the registered vfs.notes)' },
  cards: { scope: 'local', adoptionUnit: 'S4.16', reason: 'temporary Anki collection table' },
  revlog: { scope: 'local', adoptionUnit: 'S4.16', reason: 'temporary Anki collection table' },
  graves: { scope: 'local', adoptionUnit: 'S4.16', reason: 'temporary Anki collection table' },
  fsrs7_log_memory: { scope: 'local', adoptionUnit: 'S4.28', reason: 'trial-plugin table created after standard migrations when FSRS-7 trial is enabled' },
  __sync_conflicts: { scope: 'local', adoptionUnit: 'S8.08', reason: 'per-device conflict inbox' },
  __sync_id_aliases: { scope: 'local', adoptionUnit: 'S8.07', reason: 'id remap aliases produced during apply' },
  __sync_quarantine: { scope: 'local', adoptionUnit: 'S8.07', reason: 'quarantined remote payloads' },
  __sync_delete_versions: { scope: 'local', adoptionUnit: 'S8.09', reason: 'delete/tombstone version bookkeeping' },
  __sync_record_history: { scope: 'local', adoptionUnit: 'S8.08', reason: 'apply audit history snapshots' },
  __sync_unknown_fields: { scope: 'local', adoptionUnit: 'S8.01', reason: 'unknown-field vault per record' },
  __sync_row_meta: { scope: 'local', adoptionUnit: 'S8.01', reason: 'row-level sync metadata' },
  __plugin_sync_records: { scope: 'local', adoptionUnit: 'S7.36', reason: 'namespaced plugin records with HLC field versions; helper has no production caller in current desktop TypeScript and is not registered' },
  message_widget_states: { scope: 'local', adoptionUnit: 'S7.36', reason: 'widget state keyed by owner/source hash; same uncalled helper, not registered' },
  __change_log: { scope: 'local', adoptionUnit: 'S2.05', reason: 'capture log the sync engine drains; never itself synced' },
  upload_seq: { scope: 'local', adoptionUnit: 'S2.26', reason: 'per-device upload sequence ledger in sync_state.db' },
  consume_cursor: { scope: 'local', adoptionUnit: 'S8.07', reason: 'download cursor in sync_state.db' },
  legacy_processed_key: { scope: 'local', adoptionUnit: 'S8.07', reason: 'legacy dedupe ledger' },
  tombstone_watermark: { scope: 'local', adoptionUnit: 'S8.09', reason: 'tombstone watermarks per device/scope' },
  tombstone_event_publish: { scope: 'local', adoptionUnit: 'S8.09', reason: 'tombstone publication ledger' },
  instance_binding: { scope: 'local', adoptionUnit: 'S8.17', reason: 'remote instance binding for this device' },
  device_history: { scope: 'local', adoptionUnit: 'S8.17', reason: 'observed peer devices' },
  __sync_v5_runtime_state: { scope: 'local', adoptionUnit: 'S8.06', reason: 'V5 runtime head per repo/device' },
  __sync_v5_runtime_ops: { scope: 'local', adoptionUnit: 'S8.06', reason: 'V5 pending operations' },
  __sync_v5_oversize_quarantine: { scope: 'local', adoptionUnit: 'S8.07', reason: 'isolated oversize V5 changes' },
  __sync_v5_runtime_receipts: { scope: 'local', adoptionUnit: 'S8.06', reason: 'V5 publication receipts' },
  __sync_v5_pending_gc: { scope: 'local', adoptionUnit: 'S8.11', reason: 'deferred object GC list' },
  __sync_v5_change_log_bindings_v2: { scope: 'local', adoptionUnit: 'S8.06', reason: 'V5 change-log bindings' },
  __sync_v5_column_versions: { scope: 'local', adoptionUnit: 'S8.08', reason: 'per-column version registers for field merge' },
  __sync_quarantine_records: { scope: 'local', adoptionUnit: 'S8.07', reason: 'V5 record quarantine' },
  __sync_applied_ops: { scope: 'local', adoptionUnit: 'S8.06', reason: 'V5 applied-operation ledger per repo/device' },
  __sync_applied_ranges: { scope: 'local', adoptionUnit: 'S8.06', reason: 'V5 applied range ledger' },
  __sync_unknown_op_log: { scope: 'local', adoptionUnit: 'S8.01', reason: 'unknown-operation log for forward compatibility' },
  __sync_deferred_orphans: { scope: 'local', adoptionUnit: 'S8.07', reason: 'deferred orphan records awaiting their parent row' },
  rag_sub_libraries: { scope: 'local', adoptionUnit: 'S4.27', reason: 'runtime DDL for a table that also exists in the mistakes migration (BackupOnly)' },
  rag_documents: { scope: 'local', adoptionUnit: 'S4.27', reason: 'Lance store metadata created at startup on mistakes.db' },
  rag_document_chunks: { scope: 'local', adoptionUnit: 'S4.27', reason: 'Lance chunk metadata; rebuildable from resources' },
  chat_v2_compaction_memory_flushes: { scope: 'local', adoptionUnit: 'S3.24', reason: 'memory-flush ledger created on demand' },
  legacy_chat_migration_map: { scope: 'local', adoptionUnit: 'S3.01', reason: 'one-shot legacy chat migration bookkeeping' },
  path_cache: { scope: 'derived', adoptionUnit: 'S1.06', reason: 'repair DDL for the registered DerivedRebuild path cache' },
};
const runtimeTemplateTables = {
  '${SCHEMA_FINGERPRINT_TABLE}': '__governance_schema_fingerprints',
  '${this.tableName}': 'refinery_schema_history',
  '${MEMORY_FLUSH_LEDGER_TABLE}': 'chat_v2_compaction_memory_flushes',
  '${MIGRATION_MAP_TABLE}': 'legacy_chat_migration_map',
};
const runtimeHomes = {
  '__audit_log': 'audit',
  'refinery_schema_history': 'each synced business database',
  '__change_log': 'each synced business database',
  '__governance_schema_fingerprints': 'each synced business database',
  '__sync_conflicts': 'each synced business database',
  '__sync_id_aliases': 'each synced business database',
  '__sync_quarantine': 'each synced business database',
  '__sync_delete_versions': 'each synced business database',
  '__sync_record_history': 'each synced business database',
  '__sync_unknown_fields': 'each synced business database',
  '__sync_row_meta': 'each synced business database',
  '__sync_applied_ops': 'each synced business database',
  '__sync_applied_ranges': 'each synced business database',
  '__sync_unknown_op_log': 'each synced business database',
  '__sync_deferred_orphans': 'each synced business database',
  '__plugin_sync_records': 'each synced business database',
  'message_widget_states': 'each synced business database',
  'answer_submissions': 'vfs',
  'chat_v2_session_groups': 'chat_v2',
  'path_cache': 'vfs',
  'rag_sub_libraries': 'mistakes',
  'rag_documents': 'mistakes',
  'rag_document_chunks': 'mistakes',
  'pending_memory_candidates': 'mistakes',
  'fsrs7_log_memory': 'mistakes',
  'completion_outbox': 'chat_v2',
  'chat_v2_compaction_memory_flushes': 'chat_v2',
  'legacy_chat_migration_map': 'mistakes',
  'workspace': 'workspace',
  'agent': 'workspace',
  'message': 'workspace',
  'inbox': 'workspace',
  'document': 'workspace',
  'context': 'workspace',
  'sleep_block': 'workspace',
  'subagent_task': 'workspace',
  'col': 'apkg-export temp collection',
  'notes': 'apkg-export temp collection',
  'cards': 'apkg-export temp collection',
  'revlog': 'apkg-export temp collection',
  'graves': 'apkg-export temp collection',
  'upload_seq': 'sync_state',
  'consume_cursor': 'sync_state',
  'legacy_processed_key': 'sync_state',
  'tombstone_watermark': 'sync_state',
  'tombstone_event_publish': 'sync_state',
  'instance_binding': 'sync_state',
  'device_history': 'sync_state',
  '__sync_v5_runtime_state': 'sync_state',
  '__sync_v5_runtime_ops': 'sync_state',
  '__sync_v5_oversize_quarantine': 'sync_state',
  '__sync_v5_runtime_receipts': 'sync_state',
  '__sync_v5_pending_gc': 'sync_state',
  '__sync_v5_change_log_bindings_v2': 'sync_state',
  '__sync_v5_column_versions': 'sync_state',
  '__sync_quarantine_records': 'sync_state',
};

function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index; i += 1) if (text.charCodeAt(i) === 10) line += 1;
  return line;
}

function buildIndex() {
  const files = sourceFiles(desktop, 'src', 'host').filter(file => /\.(ts|tsx)$/.test(file)
    && !/(?:__tests__|\/tests\/|\.test\.|\.spec\.|__fixtures__|\/e2e\/|\/demo\/|\/equivalence\/)/.test(file)
    && !mobileRuntime.test(file));
  return { files, texts: new Map(files.map(file => [file, readDesktop(file)])) };
}

function findLiteral(index, literal, limit = 2, restrict = null) {
  const hits = [];
  for (const file of index.files) {
    if (restrict !== null && restrict(file) === false) continue;
    const text = index.texts.get(file);
    let position = text.indexOf(literal);
    while (position >= 0 && hits.length < limit) {
      const line = lineOf(text, position);
      hits.push({ source: 'desktop', file, line, text: text.split('\n')[line - 1].trim().slice(0, 180) });
      position = text.indexOf(literal, position + literal.length);
    }
    if (hits.length >= limit) break;
  }
  return hits;
}

function lineEvidence(file, line) {
  const lines = readDesktop(file).split('\n');
  assert.ok(line >= 1 && line <= lines.length, `${file}:${line} evidence line exists`);
  return { source: 'desktop', file, line, text: lines[line - 1].trim().slice(0, 180) };
}

function schemaObjects(conn) {
  const objects = conn.all("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name");
  return {
    tables: objects.filter(row => row.type === 'table').map(row => ({ name: row.name, ddl: String(row.sql ?? ''), columns: conn.all(`PRAGMA table_info('${row.name.replaceAll("'", "''")}')`), foreignKeys: conn.all(`PRAGMA foreign_key_list('${row.name.replaceAll("'", "''")}')`) })),
    objects,
  };
}

function uniqueGroups(conn, table) {
  const groups = [];
  for (const index of conn.all(`PRAGMA index_list('${table.replaceAll("'", "''")}')`)) {
    if (Number(index.unique) !== 1) continue;
    const columns = conn.all(`PRAGMA index_info('${String(index.name).replaceAll("'", "''")}')`).filter(row => Number(row.cid) >= 0 && row.name !== null).sort((a, b) => Number(a.seqno) - Number(b.seqno)).map(row => String(row.name));
    if (!groups.some(group => group.length === columns.length && group.every((column, position) => column === columns[position]))) groups.push(columns);
  }
  return groups.sort((a, b) => a.join(',').localeCompare(b.join(',')));
}

function classifyScope(category) {
  switch (category) {
    case 'RowSync': return { scope: 'portable', channel: 'row change log + drift checksum', backup: 'database file is part of the desktop backup package' };
    case 'FileSync': return { scope: 'portable', channel: 'file payload', backup: 'database file is part of the desktop backup package' };
    case 'BackupOnly': return { scope: 'local', channel: 'not row-synced; intentionally backup-only', backup: 'included in the full database backup' };
    case 'LocalRuntime': return { scope: 'local', channel: 'not row-synced; device runtime state', backup: 'travels inside the database file, never merged' };
    case 'DerivedRebuild': return { scope: 'derived', channel: 'not synced; rebuilt from source rows', backup: 'rebuilt after restore' };
    case 'Deprecated': return { scope: 'absent', channel: 'not synced; table absent from the current schema', backup: 'not applicable' };
    default: throw new Error(`unknown category ${category}`);
  }
}

function registryEvidence() {
  const map = new Map();
  for (const file of registryFiles.filter(file => file.includes('/classification-data/'))) {
    const text = readDesktop(file);
    let position = text.indexOf('table_name: "');
    while (position >= 0) {
      const start = position + 'table_name: "'.length;
      const end = text.indexOf('"', start);
      const table = text.slice(start, end);
      if (!map.has(table)) map.set(table, lineEvidence(file, lineOf(text, position)));
      position = text.indexOf('table_name: "', end);
    }
  }
  return map;
}

/** Maps Android's frozen byte copy to the repository file that produced it. */
function classificationSourceFile(snapshotFile) {
  const match = /^config\/schema\/history\/(vfs|chat_v2|mistakes|llm_usage)\/V(\d+)__([^/]+)\.sql$/.exec(snapshotFile);
  if (match === null) return snapshotFile;
  return `resources/app/migrations/${match[1]}/V${match[2]}__${match[3]}.sql`;
}

async function liveSchema(registry) {
  const { Connection } = await load('host/platform/storage/connection.ts');
  const { loadSqlMigrations, Runner } = await load('host/platform/storage/migration/refinery.ts');
  const { triggerEvent, validateSyncRegistryDrift } = await load('host/plugins/sync/repository/registry-preflight.ts');
  const { setLocalRuntime } = await load('host/plugins/sync/src/local-runtime.ts');
  const { nodeLocalRuntime } = await load('host/plugins/sync/src/local-runtime.node.ts');
  const { MigrationCoordinator } = await load('host/platform/storage/migration/coordinator.ts');
  const names = [...new Set(registry.map(entry => entry.database))];
  const facts = [];
  const migrationStats = {};
  for (const name of names) {
    const conn = new Connection(':memory:', { label: name, profile: { foreignKeys: true } });
    try {
      const list = loadSqlMigrations(resolve(desktop, 'resources/app/migrations', name));
      const runnerLog = [];
      new Runner(list, { log: (level, message) => { runnerLog.push({ level, message }); if (level === 'warn' || level === 'error') log('classification.desktop', { level, message, database: name }); } }).run(conn);
      migrationStats[name] = { migrations: list.length, logEntries: runnerLog.length, warnings: runnerLog.filter(entry => entry.level === 'warn' || entry.level === 'error').length };
      const snapshot = schemaObjects(conn);
      for (const entry of registry.filter(row => row.database === name)) {
        const table = snapshot.tables.find(item => item.name === entry.table_name);
        if (table === undefined) { facts.push({ key: `${name}.${entry.table_name}`, exists: false }); continue; }
        const sqlitePrimaryKey = table.columns.filter(column => Number(column.pk) > 0).sort((a, b) => Number(a.pk) - Number(b.pk)).map(column => String(column.name)).join(',');
        const triggers = snapshot.objects.filter(object => object.type === 'trigger' && object.tbl_name === entry.table_name && String(object.sql ?? '').includes('__change_log'));
        const operations = {};
        for (const trigger of triggers) {
          const operation = triggerEvent(String(trigger.sql ?? ''));
          if (operation !== null && operation !== undefined) operations[operation] = (operations[operation] ?? 0) + 1;
        }
        facts.push({
          key: `${name}.${entry.table_name}`,
          exists: true,
          columns: table.columns.length,
          columnNames: table.columns.map(column => String(column.name)),
          sqlitePrimaryKey,
          virtualTable: /^CREATE\s+VIRTUAL\s+TABLE/i.test(table.ddl),
          uniqueGroups: uniqueGroups(conn, entry.table_name),
          changeLogTriggerCount: triggers.length,
          changeLogOperations: Object.keys(operations).sort(),
          ddl: table.ddl,
        });
      }
    } finally {
      conn.close();
    }
  }
  const preflight = await productionPreflight(names, { MigrationCoordinator, validateSyncRegistryDrift, setLocalRuntime, nodeLocalRuntime });
  return { facts: new Map(facts.map(fact => [fact.key, fact])), databases: names, preflight, migrationStats };
}

/** Runs the desktop startup guard on real migrated files: cleaner must pass, a dropped trigger must be reported. */
async function productionPreflight(names, { MigrationCoordinator, validateSyncRegistryDrift, setLocalRuntime, nodeLocalRuntime }) {
  const { mkdtempSync, rmSync } = await import('node:fs');
  const { Connection } = await load('host/platform/storage/connection.ts');
  const temp = mkdtempSync(resolve(root, '.baseline-classification-'));
  setLocalRuntime(nodeLocalRuntime);
  try {
    const coordinatorLog = [];
    const coordinator = new MigrationCoordinator({ appDataDir: resolve(temp, 'active'), migrationsDir: resolve(root, 'config/schema/history'), auditDbPath: null, log: (level, message, fields) => { coordinatorLog.push({ level, message, fields }); if (level === 'warn' || level === 'error') log('classification.migration', { level, message, fields }); } });
    const report = await coordinator.runAll();
    assert.equal(report.success, true, 'production coordinator startup for the classification databases');
    const service = {
      pathOf: id => coordinator.getDatabasePath(id),
      openFile: (path, options) => new Connection(path, { label: 'preflight', profile: options?.profile ?? { foreignKeys: true } }),
      close: handle => handle.close(),
    };
    validateSyncRegistryDrift(service);
    log('classification.preflight', { stage: 'clean', result: 'passed', databases: names });
    const conn = new Connection(coordinator.getDatabasePath('vfs'), { label: 'vfs-sabotage', profile: { foreignKeys: true } });
    try { conn.exec('DROP TRIGGER trg__change_log_notes_insert'); } finally { conn.close(); }
    let detected = null;
    try { validateSyncRegistryDrift(service); } catch (error) { detected = error.message; }
    assert.ok(detected !== null && detected.includes('vfs.notes'), 'dropped change-log trigger is detected by the production guard');
    log('classification.preflight', { stage: 'sabotage', result: 'detected', detail: detected.slice(0, 160) });
    const reportSummary = (report.databases ?? []).map(entry => ({ id: entry.id, fromVersion: entry.fromVersion, toVersion: entry.toVersion, appliedCount: entry.appliedCount, success: entry.success }));
    return { databases: names, coordinator: { success: report.success, databases: reportSummary, logEntries: coordinatorLog.length, warnings: coordinatorLog.filter(entry => entry.level === 'warn' || entry.level === 'error').length }, clean: 'passed', sabotage: { dropped: 'vfs trg__change_log_notes_insert', detected } };
  } finally {
    setLocalRuntime(null);
    rmSync(temp, { recursive: true, force: true });
  }
}

function settingsSection(index, api, contributions) {
  const whitelist = [...api.SYNCABLE_SETTING_KEYS];
  const consumers = {
    'skill.ui.enable_overrides': ['src/features/chat/skills/skillSettingsMirror.ts'],
    'skill.ui.trust_overrides': ['src/features/chat/skills/skillSettingsMirror.ts'],
    'skill.ui.favorites': ['src/features/chat/skills/skillSettingsMirror.ts'],
    'skill.ui.bundles': ['src/features/chat/skills/skillSettingsMirror.ts'],
    'shortcuts.custom': ['src/command-palette/registry/shortcutManager.ts'],
    'pdf.settings': ['src/features/pdf/stores/pdfSettingsStore.ts'],
    'chat.defaultPermissionPreset': ['src/features/chat/core/session/permissionPresetDefaults.ts'],
    theme_custom_color: ['src/hooks/useTheme.ts', 'src/features/settings/components/AppearanceTab.tsx'],
    'translation.promptTemplates.v1': ['src/components/translation/PromptPanel.tsx'],
    'pomodoro.settings': ['src/features/pomodoro/stores/usePomodoroStore.ts'],
    'sync.include_skills': ['host/plugins/sync/src/device-pairing.ts', 'host/plugins/sync/src/file-level-sync.ts', 'src/features/settings/components/DevicePairingSection.tsx'],
    'sync.include_plugins': ['host/plugins/sync/src/device-pairing.ts', 'host/plugins/sync/src/file-level-sync.ts', 'src/features/settings/components/DevicePairingSection.tsx'],
  };
  const producer = {
    'skill.ui.enable_overrides': 'invoke("save_setting") from skillSettingsMirror',
    'skill.ui.trust_overrides': 'invoke("save_setting") from skillSettingsMirror',
    'skill.ui.favorites': 'invoke("save_setting") from skillSettingsMirror',
    'skill.ui.bundles': 'invoke("save_setting") from skillSettingsMirror',
    'shortcuts.custom': 'saveSetting',
    'pdf.settings': 'saveSetting',
    'chat.defaultPermissionPreset': 'saveSetting',
    theme_custom_color: 'saveSetting plus a localStorage mirror',
    'translation.promptTemplates.v1': 'saveSetting',
    'pomodoro.settings': 'declared only: the pomodoro UI keeps its state in the pomodoro-storage localStorage entry, no settings-row producer was found',
    'sync.include_skills': 'saveSetting from the device pairing section',
    'sync.include_plugins': 'saveSetting from the device pairing section',
  };
  const entries = whitelist.map(key => {
    assert.ok(consumers[key], `${key}: consumer mapping`);
    const files = consumers[key];
    for (const file of files) assert.ok(index.texts.has(file), `${file}: consumer file is tracked`);
    const references = files.flatMap(file => findLiteral(index, key, 2, candidate => candidate === file).map(hit => ({ file, line: hit.line, text: hit.text })));
    const declaredOnly = references.length === 0;
    if (!declaredOnly) assert.ok(references.length > 0, `${key}: literal reference in its consumer file`);
    return {
      key,
      scope: 'portable',
      sensitive: api.isSensitiveKey(key),
      transport: 'data_governance/preferences/<encodeURIComponent(deviceId)>.json; winner by updatedAt then lexicographic value',
      desktopProducer: producer[key],
      declaredOnly,
      consumers: files.map(file => ({ file, sha256: sha256(readDesktop(file)) })),
      evidence: references.length > 0 ? references : findLiteral(index, key, 1),
      android: { storage: 'DataStore local mirror; shared value through the same preferences channel', deliveryUnit: key.startsWith('skill.ui.') ? 'S3.20' : key.startsWith('sync.') ? 'S8.17' : key === 'theme_custom_color' ? 'S5.01' : key === 'pomodoro.settings' ? 'S7.28' : 'S7.35' },
    };
  });
  assert.equal(new Set(whitelist).size, whitelist.length);
  const portableFromContributions = [...new Set(contributions.settings.filter(setting => setting.scope === 'portable' && typeof setting.key === 'string').map(setting => setting.key))].sort();
  const secretRules = {
    source: 'host/platform/secrets/sensitive-key.ts',
    sha256: sha256(readDesktop('host/platform/secrets/sensitive-key.ts')),
    probes: { apiKeySuffix: api.isSensitiveKey('openai.api_key'), camelSuffix: api.isSensitiveKey('vendor.apiKey'), cloud: api.isSensitiveKey('cloud_storage_credentials'), theme: api.isSensitiveKey('theme_custom_color') },
    patterns: ['internal.oauth.', 'web_search.api_key.', 'web_search.searxng.api_key', 'api_configs', 'mcp.transport.', 'mcp.tools.', 'mcp.servers.', 'siliconflow.api_key', 'cloud_storage', 'apiKey', 'api_key', 'secret', 'password', 'token', 'plugin.ilinkbot.credentials'],
    suffixRules: ['.api_key', '.apiKey'],
    storageRule: 'sensitive keys go through SettingsStore.saveSecret (OS secure store) and legacy plaintext rows are deleted; the settings table never keeps them',
    pairingChannel: { source: 'host/plugins/sync/src/device-pairing.ts', allowlist: ['cloud_storage.config.safe_v1', 'cloud_storage_credentials', 'llm_providers', 'api_configs', 'vendor_configs', 'model_profiles', 'model_assignments', 'siliconflow.api_key', 'cloud_sync.*', 'builtin-*.api_key'], prefs: ['sync.include_skills', 'sync.include_plugins'] },
    android: { keystore: 'S1.23', pairing: 'S8.17', settingsUi: 'S7.35' },
  };
  assert.deepEqual(secretRules.probes, { apiKeySuffix: true, camelSuffix: true, cloud: true, theme: false });
  const localExamples = [
    { key: 'dstu-theme-mode', scope: 'local', reason: 'localStorage-only display mode, not in the syncable whitelist', evidence: findLiteral(index, 'dstu-theme-mode', 1) },
    { key: 'dstu-theme-palette', scope: 'local', reason: 'localStorage-only palette, not in the syncable whitelist', evidence: findLiteral(index, 'dstu-theme-palette', 1) },
    { key: 'attachment.settings', scope: 'local', reason: 'local attachment UI policy, not in the syncable whitelist', evidence: findLiteral(index, 'attachment.settings', 1) },
    { key: 'rag.lance.path', scope: 'local', reason: 'absolute local vector-store path', evidence: findLiteral(index, 'rag.lance.path', 1) },
  ];
  for (const example of localExamples) assert.ok(example.evidence.length > 0, `${example.key}: evidence`);
  return { whitelist: entries, count: entries.length, portableFromContributions, whitelistMissingFromContributions: whitelist.filter(key => !portableFromContributions.includes(key)).sort(), secretRules, localExamples };
}

function assetSection(api, index) {
  const directories = [...api.SYNCED_ASSET_DIRS.entries()].map(([root, tops]) => ({ root, topDirectories: [...tops] }));
  const whitelistSamples = [
    'active/images/中文 图.png', 'active/notes_assets/a.pdf', 'active/documents/b.docx', 'active/subjects/c.png',
    'active/textbooks/d.epub', 'active/audio/e.mp3', 'active/videos/f.mp4',
    'app_data/pdf_ocr_sessions/g.json', 'app_data/skills/deck/SKILL.md', 'app_data/plugins/plugin.a/manifest.json',
    'app_data/dstu-workshop/item/manifest.json',
    'active/vfs_blobs/ab/hash', 'active/attachments/x.pdf', 'active/anki_media/y.png', 'active/databases/vfs.db', 'app_data/plugins/plugins-lock.json',
  ].map(key => ({ key, synced: api.isSyncedAssetKey(key) }));
  const synced = whitelistSamples.filter(sample => sample.synced).map(sample => sample.key);
  assert.equal(synced.length, 12);
  for (const key of ['active/vfs_blobs/ab/hash', 'active/attachments/x.pdf', 'active/anki_media/y.png', 'active/databases/vfs.db']) {
    assert.equal(api.isSyncedAssetKey(key), false, `${key}: outside the asset whitelist`);
  }
  assert.equal(api.isSyncedAssetKey('app_data/plugins/plugins-lock.json'), true, 'the lock file sits inside a whitelisted top directory');
  assert.equal(api.isSyncedAssetKey('app_data/not-a-top/x'), false, 'only the second segment is matched against the whitelist');
  assert.equal(api.isSyncedAssetKey('app_data/plugins'), false, 'a key without a third segment is rejected');
  const portableGates = [
    { key: 'app_data/plugins/plugin.a/main.js', options: {}, expected: true, reason: 'portable plugin source' },
    { key: 'app_data/plugins/plugin.a/native.so', options: {}, expected: false, reason: 'native binaries never leave the device' },
    { key: 'app_data/plugins/plugin.a/user.profile.json', options: {}, expected: false, reason: 'trust profile is device-local' },
    { key: 'app_data/plugins/plugin.a/community-consent.json', options: {}, expected: false, reason: 'consent ledger is device-local' },
    { key: 'app_data/plugins/plugin.a/manifest.json', options: { includePlugins: false }, expected: false, reason: 'include_plugins=false disables the plugin directory' },
    { key: 'app_data/skills/deck/SKILL.md', options: { includeSkills: false }, expected: false, reason: 'include_skills=false disables the skill directory' },
    { key: 'app_data/dstu-workshop/item/manifest.json', options: { mobile: true }, expected: false, reason: 'mobile sync drops workshop assets' },
    { key: 'app_data/dstu-workshop/item/manifest.json', options: {}, expected: true, reason: 'desktop sync keeps workshop assets' },
  ].map(sample => ({ ...sample, actual: api.isPortableAssetEnabled(sample.key, sample.options) }));
  for (const sample of portableGates) assert.equal(sample.actual, sample.expected, `${sample.key}: portable gate`);
  const encoded = api.encodeSegment('a:b?.png');
  assert.equal(api.decodeSegment(encoded), 'a:b?.png');
  const reserved = api.encodeSegment('CON');
  assert.equal(api.decodeSegment(reserved), 'CON');
  const decomposed = '한글'.normalize('NFD');
  const hex = api.encodeSegment(decomposed);
  assert.equal(api.decodeSegment(hex), decomposed);
  assert.notEqual(hex, api.encodeSegment('한글'));
  let oversized = null;
  try { api.encodeSegment('x'.repeat(256)); } catch (error) { oversized = error.name; }
  assert.equal(oversized, 'FilenameCodecError');
  const pluginLock = findLiteral(index, 'plugins-lock.json', 2);
  assert.ok(pluginLock.length > 0);
  return {
    source: 'host/plugins/sync/src/asset-filenames.ts',
    sha256: sha256(readDesktop('host/plugins/sync/src/asset-filenames.ts')),
    directories,
    directoryCounts: Object.fromEntries(directories.map(directory => [directory.root, directory.topDirectories.length])),
    keyRule: 'key = <root>/<top>/<encoded relative path>; both separators required; the top directory must be in the whitelist',
    limits: { segmentUnits: api.MAX_PORTABLE_SEGMENT_UNITS, assetKeyUnits: api.MAX_PORTABLE_ASSET_KEY_UNITS },
    whitelistSamples,
    portableGates: { source: 'host/plugins/sync/src/portable-assets.ts', samples: portableGates, mobilePluginRule: api.supportsMobilePlugin({ platforms: ['mobile'] }) && api.supportsMobilePlugin({ platforms: ['mobile', 'desktop'] }) && !api.supportsMobilePlugin({ native: true, platforms: ['mobile'] }) && !api.supportsMobilePlugin({ desktopOnly: true, platforms: ['mobile'] }) },
    pluginLock: { key: 'app_data/plugins/plugins-lock.json', desktopOnly: true, evidence: pluginLock },
    filenameCodec: { samples: [{ input: 'a:b?.png', encoded, roundTrip: api.decodeSegment(encoded) }, { input: 'CON', encoded: reserved, roundTrip: api.decodeSegment(reserved) }, { input: 'NFD 한글', encoded: hex, roundTrip: api.decodeSegment(hex) }], oversized },
    materialization: {
      source: 'host/plugins/sync/src/asset-paths.ts',
      function: 'assetLocalPath(active, app, key)',
      evidence: [lineEvidence('host/plugins/sync/src/asset-paths.ts', 84), lineEvidence('host/plugins/sync/src/asset-paths.ts', 86)],
      samples: [
        { key: 'active/images/a.png', localPath: api.assetLocalPath('/active', '/app', 'active/images/a.png') },
        { key: 'active/databases/vfs.db', localPath: api.assetLocalPath('/active', '/app', 'active/databases/vfs.db') },
        { key: 'active/vfs_blobs/ab/hash', localPath: api.assetLocalPath('/active', '/app', 'active/vfs_blobs/ab/hash') },
        { key: 'active/attachments/x.pdf', localPath: api.assetLocalPath('/active', '/app', 'active/attachments/x.pdf') },
        { key: 'app_data/skills/deck/SKILL.md', localPath: api.assetLocalPath('/active', '/app', 'app_data/skills/deck/SKILL.md') },
        { key: 'app_data/plugins/plugins-lock.json', localPath: api.assetLocalPath('/active', '/app', 'app_data/plugins/plugins-lock.json') },
        { key: 'active/images/../../etc/passwd', localPath: api.assetLocalPath('/active', '/app', 'active/images/../../etc/passwd') },
      ],
      rule: 'a key materializes only when isSyncedAssetKey accepts its top directory; databases/vfs_blobs/attachments/anki_media/workspaces therefore return null even though they exist on disk',
    },
  };
}

async function preferencesTransport(api) {
  const rows = new Map([
    ['theme_custom_color', { value: '#6366f1', updatedAt: '2026-01-01T00:00:00Z' }],
    ['pdf.settings', { value: '{"zoom":1}', updatedAt: '2026-01-01T00:00:00Z' }],
    ['web_search.api_key.tavily', { value: 'secret-value', updatedAt: '2026-01-01T00:00:00Z' }],
    ['desktop.workbenchMode', { value: 'desktop', updatedAt: '2026-01-01T00:00:00Z' }],
  ]);
  const settings = {
    save: (key, value) => { rows.set(key, { value, updatedAt: '2026-01-02T00:00:00Z' }); },
    get: key => rows.get(key)?.value ?? null,
    listByPrefix: () => [...rows.entries()].map(([key, row]) => ({ key, value: row.value, updatedAt: row.updatedAt })),
  };
  const objects = new Map();
  const storage = {
    listOutcome: async prefix => ({ files: [...objects.keys()].filter(key => key.startsWith(prefix)).map(key => ({ key })), truncated: false }),
    get: async key => objects.get(key) ?? null,
    put: async (key, bytes) => { objects.set(key, bytes); },
  };
  const codec = { encrypted: false, encode: async bytes => bytes, decode: async bytes => bytes };
  await api.syncPreferences(storage, settings, 'device A/1', 'Bidirectional', codec);
  const keys = [...objects.keys()];
  assert.equal(keys.length, 1);
  assert.equal(keys[0], 'data_governance/preferences/device%20A%2F1.json');
  const payload = JSON.parse(new TextDecoder().decode([...objects.values()][0]));
  const uploaded = payload.map(row => row.key).sort();
  assert.deepEqual(uploaded, ['pdf.settings', 'theme_custom_color']);
  assert.equal(payload.find(row => row.key === 'theme_custom_color').value, '#6366f1');
  const directions = [];
  for (const direction of ['Upload', 'Download', 'Bidirectional']) {
    const remoteKey = 'data_governance/preferences/peer.json';
    const remoteRows = [{ key: 'theme_custom_color', value: '#112233', updatedAt: '2026-01-03T00:00:00Z' }];
    const remoteBytes = new TextEncoder().encode(JSON.stringify(remoteRows));
    const objects = new Map([[remoteKey, remoteBytes]]);
    let localValue = '#6366f1';
    const writes = [];
    const local = {
      listByPrefix: () => [{ key: 'theme_custom_color', value: localValue, updatedAt: '2026-01-01T00:00:00Z' }],
      get: () => localValue,
      save: (key, value) => { assert.equal(key, 'theme_custom_color'); localValue = value; },
    };
    await api.syncPreferences({
      listOutcome: async () => ({ files: [{ key: remoteKey }], truncated: false }),
      get: async key => objects.get(key) ?? null,
      put: async (key, bytes) => { writes.push(key); objects.set(key, bytes); },
    }, local, 'device A/1', direction, codec);
    assert.equal(localValue, direction === 'Upload' ? '#6366f1' : '#112233');
    assert.equal(writes.length, direction === 'Download' ? 0 : 1);
    assert.deepEqual(objects.get(remoteKey), remoteBytes);
    const published = writes.length === 0 ? null : JSON.parse(new TextDecoder().decode(objects.get(writes[0])));
    if (published !== null) assert.equal(published[0].value, '#112233');
    directions.push({ direction, localValue, writes, published });
  }
  return { objectKey: keys[0], uploadedKeys: uploaded, excludedKeys: [...rows.keys()].filter(key => !uploaded.includes(key)).sort(), directions, note: 'Bidirectional applies and uploads winners; Upload skips local apply, Download skips remote publication', channel: 'call site: host/plugins/sync/src/file-level-sync.ts:117' };
}

function gapTasks(registryByKey, index) {
  const need = key => {
    const entry = registryByKey.get(key);
    assert.ok(entry, `${key}: registry row for gap task`);
    return entry;
  };
  const first = literal => {
    const hits = findLiteral(index, literal, 1);
    assert.ok(hits.length > 0, `${literal}: desktop evidence`);
    return hits[0];
  };
  const registryEvidenceOf = entry => ({ source: 'desktop', file: entry.evidence.file, line: entry.evidence.line, text: entry.evidence.text });
  const tasks = [
    {
      id: 'templates-portable',
      title: 'Card template two-sided completion contract',
      domain: 'anki-templates',
      baselineReality: {
        registryKey: 'mistakes.custom_anki_templates',
        category: need('mistakes.custom_anki_templates').category,
        conflictPolicy: need('mistakes.custom_anki_templates').conflictPolicy,
        evidence: registryEvidenceOf(need('mistakes.custom_anki_templates')),
        detail: 'Declared BackupOnly/NoConflict, so template rows never travel through row sync even though name is a UNIQUE business key. HTML/CSS live in front_template/back_template/css_style; there are no insert/update/delete change-log triggers because no row is expected to sync.',
      },
      targetDifference: 'Templates become portable rows with an explicit conflict policy, a version counter for concurrent edits, and a render check on both ends; Android renders the same template for the same card group.',
      desktopCaptureUnit: 'S0.21',
      desktopCapture: ['classify custom_anki_templates and its field payloads', 'export row fixture plus rendered front/back bytes', 'define tombstone/rename behaviour for template names'],
      androidAdoptionUnits: ['S7.09', 'S7.11', 'S2.27'],
      assetRoots: [],
      pathRules: ['template HTML/CSS references media by file name; resolution uses the Anki media root recorded in the media task'],
      secretLocalRules: ['template content is non-secret portable data'],
      acceptance: { normal: 'edit a template on desktop → Android renders the same card group after pull; edit on Android → desktop re-renders identically', exceptional: 'concurrent template edits converge on the business key without silently dropping CSS' },
    },
    {
      id: 'attachments-anki-media-bytes',
      title: 'Attachment and Anki media byte completion',
      domain: 'attachments-media',
      baselineReality: {
        registryKey: 'chat_v2.chat_v2_attachments',
        category: need('chat_v2.chat_v2_attachments').category,
        conflictPolicy: need('chat_v2.chat_v2_attachments').conflictPolicy,
        evidence: registryEvidenceOf(need('chat_v2.chat_v2_attachments')),
        detail: 'Attachment rows are RowSync, but content_hash is documented as a dedup hint and is not a UNIQUE key; attachment bytes have no synced asset root. Anki media lives in <active>/anki_media, which is not in SYNCED_ASSET_DIRS and not in the 10-entry desktop backup asset catalog.',
        ankiMedia: {
          path: '<active>/anki_media',
          evidence: [lineEvidence('host/plugins/anki/src/plugin.ts', 153), lineEvidence('host/plugins/anki/src/plugin-handlers-media.ts', 39)],
          inAssetWhitelist: false,
          inBackupCatalog: false,
          backupCatalog: { source: 'host/plugins/backup/src/asset-catalog.ts', evidence: lineEvidence('host/plugins/backup/src/asset-catalog.ts', 3), types: ['images', 'notes_assets', 'documents', 'vfs_blobs', 'subjects', 'workspaces', 'audio', 'videos', 'textbooks', 'pdf_ocr_sessions'] },
        },
        chatStage: { path: '<session temp>/attachments', evidence: lineEvidence('host/plugins/chat/src/attachment-stage.ts', 46), detail: 'staging is a temporary runtime directory; persisted payloads go through VFS resources/blobs' },
        attachmentBytes: {
          resolver: 'resolveVfsAttachment → repo.getContentSource(conn, blobsDir, fileId)',
          evidence: [lineEvidence('host/plugins/chat/src/attachment-stage.ts', 630), lineEvidence('host/plugins/vfs-repos/repository/attachments.ts', 437)],
          storage: ['vfs_blobs content-addressed file through getBlobPath', 'resources.data inline base64 for external-mode rows'],
          detail: 'attachment bytes therefore ride the existing vfs blob channel (sha256-addressed, already synced as file payload) while the chat_v2_attachments row only carries metadata plus content_hash as a dedup hint',
        },
      },
      targetDifference: 'Add portable roots for Anki media and persisted attachment bytes (content-addressed), with reference counting, tombstones and a record→byte linkage both ends can verify.',
      desktopCaptureUnit: 'S0.21',
      desktopCapture: ['extend the asset-root whitelist for anki_media and attachment byte roots', 'capture the media manifest with sha256/size/tombstone', 'record the record→object-key mapping fixture'],
      androidAdoptionUnits: ['S3.13', 'S4.16', 'S7.08', 'S7.11', 'S8.10'],
      assetRoots: ['active/vfs_blobs (existing blob channel)', 'active/anki_media (proposed)', 'active/attachments (proposed)'],
      pathRules: ['filename codec applies to every segment', 'case-collisions are detected with casefoldKey before upload/download', 'content-addressed object keys are shared between records'],
      secretLocalRules: ['media bytes are portable and carry no credentials', 'signed preview_url values stay local'],
      acceptance: { normal: 'desktop attachment and Anki media open on Android after sync; Android-added media opens on desktop', exceptional: 'missing media is reported per reference and a tombstone never deletes a still-referenced object' },
    },
    {
      id: 'media-progress-timing',
      title: 'Media progress, subtitles and study timing completion',
      domain: 'media-progress',
      baselineReality: {
        registryKeys: ['vfs.media_progress', 'vfs.media_transcript_segments', 'vfs.study_time_daily'],
        categories: ['BackupOnly', 'BackupOnly', 'BackupOnly'],
        evidence: [registryEvidenceOf(need('vfs.media_progress')), registryEvidenceOf(need('vfs.media_transcript_segments')), registryEvidenceOf(need('vfs.study_time_daily'))],
        detail: 'All three are BackupOnly/NoConflict: playback resume position keyed by resource_id, ASR/imported transcript segments keyed by (resource_id, idx), and a per-date additive study counter that cannot be row-LWW merged.',
      },
      targetDifference: 'Define a portable resume point (max-position rule), portable subtitles with stable segment identity, and a per-device event/counter merge for study time so two devices converge without double counting.',
      desktopCaptureUnit: 'S0.22',
      desktopCapture: ['capture resume-point and transcript fixtures', 'capture two-device study-time event vectors', 'define idempotent event dedupe keys'],
      androidAdoptionUnits: ['S7.31', 'S4.19', 'S4.20', 'S7.29'],
      assetRoots: [],
      pathRules: ['media is referenced by resource_id; subtitle source is an enum and never a device path'],
      secretLocalRules: ['transcripts may contain user content: portable with the resource, not secret', 'playback cache paths and audio focus state stay local'],
      acceptance: { normal: 'alternating playback on both devices resumes at the agreed point; transcripts open on both ends', exceptional: 'replaying the same event vector twice yields identical aggregate counters' },
    },
    {
      id: 'theme-configuration',
      title: 'Theme and portable configuration completion',
      domain: 'theme-config',
      baselineReality: {
        settingKeys: ['theme_custom_color', 'dstu-theme-mode', 'dstu-theme-palette'],
        portable: ['theme_custom_color'],
        local: ['dstu-theme-mode', 'dstu-theme-palette'],
        evidence: [
          { source: 'desktop', file: 'host/plugins/sync/src/classification-data/settings.ts', line: 4, text: readDesktop('host/plugins/sync/src/classification-data/settings.ts').split('\n')[3].trim() },
          lineEvidence('src/hooks/useTheme.ts', 162),
          lineEvidence('src/hooks/useTheme.ts', 163),
          lineEvidence('src/hooks/useTheme.ts', 180),
        ],
        detail: 'Only the custom accent colour is in the syncable whitelist (also mirrored into the SQLite settings row). Display mode and palette live in localStorage under dstu-theme-* keys and never sync.',
      },
      targetDifference: 'Classify each theme setting explicitly: mode/palette become portable or are documented as per-device by decision, custom colour keeps its portable contract, and Android DstuTheme reads the same values with the same precedence.',
      desktopCaptureUnit: 'S0.23',
      desktopCapture: ['freeze the settingId → scope table for every theme key', 'capture desktop persisted values and the expected cross-device fixture', 'record precedence between localStorage and the SQLite mirror'],
      androidAdoptionUnits: ['S5.01', 'S7.35', 'S0.11'],
      assetRoots: [],
      pathRules: ['theme values are plain JSON strings; no paths'],
      secretLocalRules: ['theme values are non-secret; API keys and pairing material stay on the secret channel', 'system dark-mode state is device-local'],
      acceptance: { normal: 'set palette and custom colour on desktop; Android applies the same resolved values after sync', exceptional: 'a device-local theme key never overwrites the other device value' },
    },
    {
      id: 'plugin-skill-data',
      title: 'Plugin and skill data completion',
      domain: 'plugin-data',
      baselineReality: {
        settingKeys: ['skill.ui.enable_overrides', 'skill.ui.trust_overrides', 'skill.ui.favorites', 'skill.ui.bundles', 'sync.include_skills', 'sync.include_plugins'],
        pluginRecords: { table: '__plugin_sync_records', registered: false, evidence: first('__plugin_sync_records'), detail: 'namespaced plugin records with HLC field versions, 64 KiB record cap, 2000-record / 5 MiB quota; ensurePluginSyncRecordsTable has no production caller in current desktop TypeScript' },
        assets: {
          pluginDirs: 'app_data/plugins/**',
          skillDirs: 'app_data/skills/**',
          trustFilesLocal: ['app_data/plugins/user.profile.json', 'app_data/plugins/*/community-consent.json'],
          evidence: [
            { source: 'desktop', file: 'host/plugins/sync/src/asset-filenames.ts', line: 139, text: readDesktop('host/plugins/sync/src/asset-filenames.ts').split('\n')[138].trim() },
            lineEvidence('host/plugins/sync/src/portable-assets.ts', 3),
            lineEvidence('host/plugins/sync/src/portable-assets.ts', 7),
            lineEvidence('host/plugins/sync/src/file-tombstone-sync.ts', 80),
          ],
        },
        detail: 'Skill UI state is portable through the settings whitelist; plugin/skill content travels as portable assets with binary and trust-file exclusions; the plugin record table is declared but not produced by current desktop code.',
      },
      targetDifference: 'Model content arrival separately from execution trust: plugin data keyspace/owner/package version, unknown-field round-trip, uninstall semantics, and a mobile install/consent gate that is explicitly local.',
      desktopCaptureUnit: 'S0.24',
      desktopCapture: ['owner/package/version/keyspace fixture', 'unknown-field and uninstall samples', 'content-arrival versus execution-authorization evidence'],
      androidAdoptionUnits: ['S7.36', 'S6.35', 'S7.37'],
      assetRoots: ['app_data/plugins', 'app_data/skills', 'app_data/dstu-workshop (desktop only)'],
      pathRules: ['native binaries stay local', 'workshop assets are skipped for mobile', 'plugin directory is pruned to manifests declaring mobile support when options.mobile'],
      secretLocalRules: ['user.profile.json and community-consent.json stay local', 'plugin content arrival never authorizes execution on the receiving device'],
      acceptance: { normal: 'installed plugin/skill content and portable data reach Android; data updates round-trip without unknown-field loss', exceptional: 'uninstall removes content while local trust stays authoritative; arriving content shows an install state before execution' },
    },
  ];
  const units = new Set([...readFileSync(resolve(root, planFile), 'utf8').matchAll(/^\| (S\d\.\d\d) \|/gm)].map(match => match[1]));
  for (const task of tasks) {
    assert.ok(units.has(task.desktopCaptureUnit), `${task.id}: capture unit in plan`);
    for (const unit of task.androidAdoptionUnits) assert.ok(units.has(unit), `${task.id}: plan unit ${unit}`);
  }
  return tasks;
}

function unregisteredTables(schema, registryByKey) {
  const rows = [];
  const kindOf = (database, table) => {
    if (table === 'refinery_schema_history') return 'migration-bookkeeping';
    if (/_fts_(?:config|data|docsize|idx|content)$/.test(table)) return 'fts-shadow';
    if (table.startsWith('__')) return 'sync-engine';
    if (database === 'browser') return 'device-local-business';
    return 'business';
  };
  for (const database of schema.databases) {
    for (const table of database.schema.tables.map(item => item.name).sort()) {
      if (registryByKey.has(`${database.id}.${table}`)) continue;
      const kind = kindOf(database.id, table);
      rows.push({ database: database.id, table, kind, registered: false, scope: 'local', reason: {
        'migration-bookkeeping': 'refinery migration history',
        'fts-shadow': 'FTS5 shadow table rebuilt with its virtual table',
        'sync-engine': 'sync engine bookkeeping (capture log, deletion queues, orphans)',
        'device-local-business': 'device-local browser storage; browser.db is in neither the sync nor the backup database set',
        business: 'present in the migrated schema but absent from the desktop classification registry',
      }[kind] });
    }
  }
  for (const database of schema.localDatabases) {
    for (const table of database.schema.tables.map(item => item.name).sort()) {
      rows.push({ database: database.id, table, kind: 'local-database', registered: false, scope: 'local', reason: `${database.scope}; the desktop registry only covers the four synced business databases` });
    }
  }
  for (const row of rows) assert.equal(row.registered, false);
  return rows;
}

function schemaSetAudit(schema, rows, unregistered) {
  const registered = new Set(rows.map(row => `${row.database}.${row.table}`));
  const classified = new Set(unregistered.map(row => `${row.database}.${row.table}`));
  const schemaTables = schema.databases.flatMap(database => database.schema.tables.map(table => `${database.id}.${table.name}`));
  const localTables = schema.localDatabases.flatMap(database => database.schema.tables.map(table => `${database.id}.${table.name}`));
  const allSchemaTables = [...schemaTables, ...localTables];
  const presentSchemaTables = new Set(allSchemaTables);
  const deprecated = rows.filter(row => row.category === 'Deprecated').map(row => `${row.database}.${row.table}`);
  const missingRegistered = rows.filter(row => row.category !== 'Deprecated' && row.schemaTable.present).map(row => `${row.database}.${row.table}`).filter(key => !presentSchemaTables.has(key));
  const missingClassified = allSchemaTables.filter(key => !registered.has(key) && !classified.has(key));
  const duplicateSchemaTables = allSchemaTables.filter((key, index) => allSchemaTables.indexOf(key) !== index);
  assert.deepEqual(missingRegistered, [], 'registered present tables exist in the schema snapshots');
  assert.deepEqual(missingClassified, [], 'schema tables absent from both the registry and the unregistered set');
  assert.deepEqual(duplicateSchemaTables, [], 'schema table keys are unique across history and local snapshots');
  assert.deepEqual([...registered].filter(key => classified.has(key)), [], 'registry and unregistered sets are disjoint');
  assert.deepEqual(deprecated, ['vfs.notes_versions'], 'only the tombstoned table is registry-only');
  return {
    rule: 'Every present S0.08 schema table is either a registry row or an explicit unregistered row; the only registry-only key is the Deprecated tombstone.',
    schemaTables: schemaTables.length,
    schemaTablesIncludingLocal: allSchemaTables.length,
    localDatabaseTables: localTables.length,
    registered: registered.size,
    unregistered: classified.size,
    accountedFor: new Set([...registered, ...classified]).size,
    registryOnly: deprecated,
    missingFromSchema: [],
    missingClassified,
  };
}

function runtimeTables(schema, registryByKey, index) {
  const byTable = new Map();
  const add = (table, inlineHome, source) => {
    const curated = runtimeCurated[table];
    assert.ok(curated, `${table} (${source}): curated runtime scope`);
    const existing = byTable.get(table) ?? { table, curated, home: runtimeHomes[table] ?? curated.home, sources: new Set(), inlineHomes: new Set() };
    existing.sources.add(source);
    if (inlineHome !== null) existing.inlineHomes.add(inlineHome);
    byTable.set(table, existing);
  };
  for (const entry of schema.inlineDdl) {
    if (/SQL parser\/checker sample literal/.test(entry.purpose)) continue;
    for (const raw of entry.tables) {
      const table = runtimeTemplateTables[raw] ?? raw;
      if (runtimeTemplateTables[raw] === undefined && /[`$.]/.test(table)) continue;
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(table)) continue;
      add(table, entry.database, `${entry.evidence.file}:${entry.evidence.line}`);
    }
  }
  for (const [file, symbol, table] of [
    ['host/plugins/chat-pipeline/repository/memory-flush.ts', 'MEMORY_FLUSH_LEDGER_TABLE', 'chat_v2_compaction_memory_flushes'],
    ['host/plugins/chat/repository/legacy-migration.ts', 'MIGRATION_MAP_TABLE', 'legacy_chat_migration_map'],
    ['host/platform/storage/pending-memory-candidates-schema.ts', 'PENDING_MEMORY_CANDIDATES_TABLE_SQL', 'pending_memory_candidates'],
  ]) {
    const text = readDesktop(file);
    const position = text.indexOf(symbol);
    assert.ok(position >= 0, `${file}: ${symbol} literal`);
    add(table, null, `${file}:${lineOf(text, position)}`);
  }
  const inlineResolved = new Set([...byTable.keys()]);
  const schemaTableKeys = new Set(schema.databases.flatMap(database => database.schema.tables.map(table => `${database.id}.${table.name}`)));
  const localTableKeys = new Set(schema.localDatabases.flatMap(database => database.schema.tables.map(table => `${database.id}.${table.name}`)));
  const excludedInlineDdl = schema.excludedInlineDdl.map(item => item.file).sort();
  const schemaHomes = new Set([...schemaTableKeys, ...localTableKeys].map(key => key.slice(key.indexOf('.') + 1)));
  const missingHomes = [...inlineResolved].filter(key => !schemaHomes.has(key) && !runtimeCurated[key]);
  assert.deepEqual(missingHomes, [], 'runtime DDL tables have an explicit curated home or schema representation');
  const rows = [...byTable.values()].map(item => {
    const registeredIn = [...registryByKey.values()].filter(entry => entry.table === item.table).map(entry => `${entry.database}.${entry.table}`);
    const expectedHome = item.home;
    const realRegistered = registeredIn.filter(key => expectedHome === 'each synced business database' || key.startsWith(`${expectedHome}.`));
    return {
      table: item.table,
      home: expectedHome,
      inlineDdlDatabase: [...item.inlineHomes].sort(),
      sources: [...item.sources].sort(),
      literalEvidence: findLiteral(index, item.table, 1),
      registeredIn: realRegistered,
      sameNameRegisteredIn: registeredIn.filter(key => !realRegistered.includes(key)),
      registered: realRegistered.length > 0,
      scope: item.curated.scope,
      reason: item.curated.reason,
      adoptionUnit: item.curated.adoptionUnit,
      declaredIn: 'inline DDL outside refinery history; never part of the managed schema fingerprint',
    };
  }).sort((a, b) => `${a.home}.${a.table}`.localeCompare(`${b.home}.${b.table}`));
  const portableRuntimeRows = rows.filter(row => row.scope === 'portable');
  assert.deepEqual(portableRuntimeRows.map(row => row.table).sort(), ['answer_submissions', 'chat_v2_session_groups']);
  for (const row of portableRuntimeRows) assert.ok(row.registered, `${row.table}: repair DDL targets an already-registered table`);
  assert.ok(rows.some(row => row.table === 'path_cache' && row.scope === 'derived' && row.registered));
  assert.equal(rows.filter(row => row.registered === false && !row.table.startsWith('__') && row.scope === 'local').length > 0, true, 'unregistered local runtime tables stay visible');
  for (const key of inlineResolved) {
    const homeKnown = rows.some(row => row.table === key) || registryByKey.has(key);
    assert.ok(homeKnown, `${key}: runtime DDL table has an explicit home`);
  }
  assert.deepEqual(excludedInlineDdl, ['host/platform/storage-mobile/audit.ts', 'host/platform/vector/mobile.ts'], 'S0.08 mobile runtime DDL exclusions stay fixed');
  Object.defineProperty(rows, 'mobileExcluded', { value: excludedInlineDdl });
  Object.defineProperty(rows, 'schemaIntersections', { value: {
    runtimeOnly: [...inlineResolved].filter(key => !schemaHomes.has(key)).sort(),
    materializedInSchema: [...inlineResolved].filter(key => schemaHomes.has(key)).sort(),
  } });
  return rows;
}

export async function collect() {
  const schema = readJson(schemaFile);
  assert.equal(schema.unit, 'S0.08');
  const classification = await load('host/plugins/sync/src/classification.ts');
  const settingKeys = await load('host/plugins/sync/src/classification-data/settings.ts');
  const assetsModule = await load('host/plugins/sync/src/asset-filenames.ts');
  const portable = await load('host/plugins/sync/src/portable-assets.ts');
  const secrets = await load('host/platform/secrets/sensitive-key.ts');
  const fieldMerge = await load('host/plugins/sync/src/field-merge.ts');
  const preferences = await load('host/plugins/sync/src/preferences-sync.ts');
  const assetPaths = await load('host/plugins/sync/src/asset-paths.ts');
  const contributions = readJson('config/baseline/contributions.json');
  const index = buildIndex();

  const api = {
    syncClassificationRegistry: classification.syncClassificationRegistry,
    rowSyncTables: classification.rowSyncTables,
    checksumTables: classification.checksumTables,
    isExcludedFromChecksum: classification.isExcludedFromChecksum,
    changeLogTriggerExempt: classification.changeLogTriggerExempt,
    getBusinessUniqueKeys: classification.getBusinessUniqueKeys,
    SYNCABLE_SETTING_KEYS: settingKeys.SYNCABLE_SETTING_KEYS,
    SYNCED_ASSET_DIRS: assetsModule.SYNCED_ASSET_DIRS,
    isSyncedAssetKey: assetsModule.isSyncedAssetKey,
    encodeSegment: assetsModule.encodeSegment,
    decodeSegment: assetsModule.decodeSegment,
    MAX_PORTABLE_SEGMENT_UNITS: assetsModule.MAX_PORTABLE_SEGMENT_UNITS,
    MAX_PORTABLE_ASSET_KEY_UNITS: assetsModule.MAX_PORTABLE_ASSET_KEY_UNITS,
    isPortableAssetEnabled: portable.isPortableAssetEnabled,
    supportsMobilePlugin: portable.supportsMobilePlugin,
    isSensitiveKey: secrets.isSensitiveKey,
    syncPreferences: preferences.syncPreferences,
    assetLocalPath: assetPaths.assetLocalPath,
  };

  const registry = api.syncClassificationRegistry();
  const evidenceMap = registryEvidence();
  const live = await liveSchema(registry);
  const missingFromSchema = registry.filter(entry => !schema.databases.find(database => database.id === entry.database)?.schema.tables.some(table => table.name === entry.table_name)).map(entry => ({ database: entry.database, table: entry.table_name, category: entry.category, note: entry.table_name === 'notes_versions' ? 'dropped by vfs migration V20260214__drop_notes_versions.sql; the registry keeps the Deprecated tombstone entry' : 'absent from the S0.08 schema snapshot' }));
  assert.deepEqual(missingFromSchema.map(item => `${item.database}.${item.table}`), ['vfs.notes_versions']);
  const rows = registry.map((entry, order) => {
    const key = `${entry.database}.${entry.table_name}`;
    const evidence = evidenceMap.get(entry.table_name);
    assert.ok(evidence, `${key}: classification-data evidence`);
    const fact = live.facts.get(key);
    assert.ok(fact, `${key}: live schema fact`);
    const declaredUnique = entry.business_unique_keys === '' ? [] : api.getBusinessUniqueKeys(entry.database, entry.table_name);
    const businessKeyBacked = declaredUnique.length === 0 ? null : fact.uniqueGroups.some(group => group.length === declaredUnique.length && declaredUnique.every(column => group.includes(column)));
    const required = ['insert', 'update', 'delete'].filter(operation => !api.changeLogTriggerExempt(entry.database, entry.table_name, operation));
    const identity = entry.primary_key === '(virtual)'
      ? { kind: 'fts-virtual', declared: entry.primary_key, sqlitePrimaryKey: fact.sqlitePrimaryKey || 'rowid (virtual table)', matches: true }
      : fact.sqlitePrimaryKey === entry.primary_key
        ? { kind: 'primary-key', declared: entry.primary_key, sqlitePrimaryKey: fact.sqlitePrimaryKey, matches: true }
        : { kind: 'declared-identity-column', declared: entry.primary_key, sqlitePrimaryKey: fact.sqlitePrimaryKey, matches: false };
    return {
      order,
      database: entry.database,
      table: entry.table_name,
      primaryKey: entry.primary_key,
      category: entry.category,
      conflictPolicy: entry.conflict_policy,
      businessUniqueKeys: declaredUnique,
      hasJsonBlobs: entry.has_json_blobs,
      mergeNotes: entry.merge_notes,
      scope: { ...classifyScope(entry.category), secret: false },
      schemaTable: {
        present: fact.exists,
        columns: fact.columnNames ?? [],
        columnCount: fact.columns,
        virtualTable: fact.virtualTable,
        identity,
        uniqueGroups: fact.uniqueGroups,
        businessKeyBacked,
        changeLogTriggers: { count: fact.changeLogTriggerCount, operations: fact.changeLogOperations },
        fingerprintManaged: schema.databases.find(database => database.id === entry.database).fingerprintScope.tables.includes(entry.table_name),
      },
      checksum: { included: api.checksumTables(entry.database).some(item => item.table_name === entry.table_name), excludedByRule: api.isExcludedFromChecksum(entry.database, entry.table_name) },
      changeLog: entry.category === 'RowSync' ? { required: required.map(operation => ({ operation, present: fact.changeLogOperations.includes(operation) })), deleteExempt: api.changeLogTriggerExempt(entry.database, entry.table_name, 'delete') } : null,
      evidence,
      sourceSha256: sha256(readDesktop(evidence.file)),
      copySourceSha256: sha256(readDesktop(classificationSourceFile(evidence.file))),
      adoptionUnit: tableUnits[key],
    };
  });
  for (const row of rows) {
    assert.ok(row.adoptionUnit, `${row.database}.${row.table}: adoption unit`);
    assert.equal(row.sourceSha256, row.copySourceSha256, `${row.database}.${row.table}: repository source hash matches frozen copy`);
    if (row.category === 'Deprecated') {
      assert.equal(row.schemaTable.present, false, `${row.database}.${row.table}: deprecated table is absent from the migrated schema`);
      row.schemaTable.absenceReason = missingFromSchema.find(item => item.database === row.database && item.table === row.table)?.note ?? null;
    } else {
      assert.equal(row.schemaTable.present, true, `${row.database}.${row.table}: present in live schema`);
    }
  }
  assertUnique(rows, row => `${row.database}.${row.table}`);

  const categoryCounts = Object.fromEntries(['RowSync', 'FileSync', 'DerivedRebuild', 'LocalRuntime', 'BackupOnly', 'Deprecated'].map(category => [category, rows.filter(row => row.category === category).length]));
  const policyCounts = {};
  for (const row of rows) policyCounts[row.conflictPolicy] = (policyCounts[row.conflictPolicy] ?? 0) + 1;
  const databaseCounts = Object.fromEntries(live.databases.map(database => [database, rows.filter(row => row.database === database).length]));
  assert.equal(rows.length, 124);
  assert.deepEqual(categoryCounts, { RowSync: 58, FileSync: 0, DerivedRebuild: 16, LocalRuntime: 31, BackupOnly: 18, Deprecated: 1 });
  assert.deepEqual(policyCounts, { Lww: 40, FieldMerge: 11, NoConflict: 73 });
  assert.deepEqual(databaseCounts, { vfs: 77, chat_v2: 22, mistakes: 23, llm_usage: 2 });
  assert.equal(rows.filter(row => row.category === 'RowSync').length, api.rowSyncTables().length);
  assert.equal(rows.filter(row => row.checksum.included).length, rows.filter(row => ['RowSync', 'FileSync'].includes(row.category)).length);
  const reviewQuestionTables = ['vfs.review_plans', 'vfs.review_stats', 'vfs.review_history'];
  const reviewQuestionRows = reviewQuestionTables.map(key => {
    const row = rows.find(item => `${item.database}.${item.table}` === key);
    assert.ok(row, `${key}: review question table row`);
    return row;
  });
  assert.deepEqual(reviewQuestionRows.map(row => row.adoptionUnit), reviewQuestionTables.map(() => 'S7.24'), 'review question tables deliver in S7.24');
  const reviewPlanRow = reviewQuestionRows.find(row => row.table === 'review_plans');
  assert.deepEqual(reviewPlanRow.schemaTable.columns.filter(column => reviewQuestionFsrsColumns.includes(column)), reviewQuestionFsrsColumns, 'review_plans exposes all seven FSRS columns in schemaTable.columns');
  const fsrsMigration = schema.migrations.find(migration => migration.database === 'vfs' && migration.version === 20261115);
  assert.ok(fsrsMigration, 'vfs FSRS migration is present in the S0.08 baseline');
  assert.equal(fsrsMigration.name, 'review_plans_fsrs');
  assert.equal(fsrsMigration.snapshot, 'config/schema/history/vfs/V20261115__review_plans_fsrs.sql');
  assert.equal(sha256(readFileSync(resolve(root, fsrsMigration.snapshot))), fsrsMigration.sha256, 'frozen FSRS migration copy hash');
  assert.equal(sha256(readFileSync(resolve(desktop, fsrsMigration.source))), fsrsMigration.sha256, 'repository FSRS migration source hash');

  const identityDifferences = rows.filter(row => row.schemaTable.present && row.schemaTable.identity.matches === false).map(row => ({ database: row.database, table: row.table, declared: row.schemaTable.identity.declared, sqlitePrimaryKey: row.schemaTable.identity.sqlitePrimaryKey, note: 'seq is the SQLite INTEGER PRIMARY KEY; version_id is UNIQUE and is the identity the change log records' }));
  assert.deepEqual(identityDifferences.map(item => `${item.database}.${item.table}`), ['vfs.note_document_revisions']);
  const unbackedBusinessKeys = rows.filter(row => row.businessUniqueKeys.length > 0 && row.schemaTable.businessKeyBacked === false).map(row => ({ database: row.database, table: row.table, businessUniqueKeys: row.businessUniqueKeys, uniqueGroups: row.schemaTable.uniqueGroups }));
  assert.deepEqual(unbackedBusinessKeys.map(item => `${item.database}.${item.table}`), ['vfs.automation_todo_deliveries']);
  const missingTriggers = rows.filter(row => row.changeLog !== null && row.changeLog.required.some(operation => operation.present === false)).map(row => `${row.database}.${row.table}`);
  assert.deepEqual(missingTriggers, []);
  const strayTriggers = [];
  for (const database of live.databases) {
    const rowSyncTables = new Set(rows.filter(row => row.database === database && row.category === 'RowSync').map(row => row.table));
    const snapshot = schema.databases.find(item => item.id === database).schema.objects;
    for (const object of snapshot) {
      if (object.type !== 'trigger' || !String(object.sql ?? '').includes('__change_log')) continue;
      if (!rowSyncTables.has(object.tbl_name)) strayTriggers.push(`${database}.${object.tbl_name}:${object.name}`);
    }
  }
  assert.deepEqual(strayTriggers, []);

  const jsonAudit = rows.map(row => {
    const fact = live.facts.get(`${row.database}.${row.table}`);
    const named = (fact.columnNames ?? []).filter(name => /json/i.test(name)).sort();
    return { key: `${row.database}.${row.table}`, hasJsonBlobs: row.hasJsonBlobs, jsonNamedColumns: named };
  });
  const declaredWithoutNamedJson = jsonAudit.filter(item => item.hasJsonBlobs && item.jsonNamedColumns.length === 0).map(item => item.key);
  const namedJsonWithoutDeclaration = jsonAudit.filter(item => !item.hasJsonBlobs && item.jsonNamedColumns.length > 0).map(item => item.key);
  assert.ok(declaredWithoutNamedJson.includes('vfs.notes'), 'notes.tags holds a JSON array while no column is json-named');
  assert.ok(namedJsonWithoutDeclaration.includes('vfs.answer_submissions'), 'answer_submissions declares has_json_blobs=false but has *_json snapshot columns');
  const jsonDeclaration = {
    rule: 'has_json_blobs is the desktop declaration for lossless JSON handling; it is not a strict column-name predicate',
    declaredWithoutNamedJson,
    namedJsonWithoutDeclaration,
    namedJsonColumns: Object.fromEntries(jsonAudit.filter(item => item.jsonNamedColumns.length > 0).map(item => [item.key, item.jsonNamedColumns])),
  };

  const fieldMergeTables = fieldMerge.fieldMergeTables();
  const fieldMergeAudit = fieldMergeTables.map(table => ({ table, columns: fieldMerge.fieldMergeColumnsForTable(table), registeredIn: rows.filter(row => row.table === table).map(row => `${row.database}.${row.table}`) }));
  for (const item of fieldMergeAudit) assert.ok(item.registeredIn.length > 0, `${item.table}: field-merge table is registered`);
  const policyConsistency = rows.filter(row => row.conflictPolicy === 'FieldMerge').map(row => row.table).filter(table => !fieldMergeTables.includes(table));
  assert.deepEqual(policyConsistency, []);

  const settings = settingsSection(index, api, contributions);
  const asset = assetSection(api, index);
  const transport = await preferencesTransport(api);
  const registryByKey = new Map(rows.map(row => [`${row.database}.${row.table}`, row]));
  const gaps = gapTasks(registryByKey, index);
  const unregistered = unregisteredTables(schema, registryByKey);
  const schemaSets = schemaSetAudit(schema, rows, unregistered);
  const runtime = runtimeTables(schema, registryByKey, index);

  const backup = await load('host/plugins/backup/src/migrations.ts');
  const backupDatabases = [...backup.DATABASE_IDS];
  assert.deepEqual([...backupDatabases].sort(), [...live.databases].sort());

  const unregisteredByKind = {};
  for (const row of unregistered) unregisteredByKind[row.kind] = (unregisteredByKind[row.kind] ?? 0) + 1;
  assert.equal(unregisteredByKind.business ?? 0, 0);
  assert.equal(unregisteredByKind['device-local-business'], 5);

  const checks = {
    registry: {
      total: rows.length,
      uniqueTableKeys: new Set(rows.map(row => `${row.database}.${row.table}`)).size,
      byDatabase: databaseCounts,
      byCategory: categoryCounts,
      byConflictPolicy: policyCounts,
      fileSyncCategory: { registered: categoryCounts.FileSync, note: 'FileSync exists in the desktop type union but no table is registered with it; file bytes travel through the blob/asset/workspace manifests instead of table classification' },
      rowSyncTables: api.rowSyncTables().length,
      checksumTables: Object.fromEntries(live.databases.map(database => [database, api.checksumTables(database).length])),
      deprecatedRegistered: rows.filter(row => row.category === 'Deprecated').map(row => `${row.database}.${row.table}`),
      deprecatedAbsentFromSchema: missingFromSchema,
      identityDifferences,
      unbackedBusinessKeys,
      missingTriggers,
      strayTriggers,
      jsonDeclaration,
      fieldMerge: { tables: fieldMergeAudit, fieldMergeRowsWithoutFieldMergePolicy: policyConsistency },
      liveSchema: live.databases.map(database => ({ database, registered: rows.filter(row => row.database === database).length, migratedTables: schema.databases.find(item => item.id === database).schema.tables.length, migration: live.migrationStats[database] })),
      schemaSets,
      productionPreflight: live.preflight,
    },
    settings: { whitelist: settings.count, portableFromContributions: settings.portableFromContributions.length, whitelistMissingFromContributions: settings.whitelistMissingFromContributions, sensitiveInWhitelist: settings.whitelist.filter(entry => entry.sensitive).map(entry => entry.key), probes: settings.secretRules.probes, preferencesTransport: transport },
    assets: { whitelistRoots: asset.directoryCounts, whitelistedSampleCount: asset.whitelistSamples.filter(sample => sample.synced).length, outsideWhitelistSamples: asset.whitelistSamples.filter(sample => !sample.synced).map(sample => sample.key), protectedDatabaseKeySynced: asset.whitelistSamples.find(sample => sample.key === 'active/databases/vfs.db').synced, portableGateMismatches: asset.portableGates.samples.filter(sample => sample.actual !== sample.expected).length, ankiMediaInWhitelist: asset.directories.some(directory => directory.topDirectories.includes('anki_media')) },
    gaps: { count: gaps.length, units: gaps.map(task => ({ id: task.id, capture: task.desktopCaptureUnit, android: task.androidAdoptionUnits })) },
    unregistered: { total: unregistered.length, byKind: unregisteredByKind, businessInSyncedDatabases: unregisteredByKind.business ?? 0, browserTables: unregistered.filter(row => row.database === 'browser').length, browserNote: 'browser.db is device-local: it is in neither the sync database set nor the desktop backup database set' },
    runtime: {
      total: runtime.length,
      registeredElsewhere: runtime.filter(row => row.registered).map(row => `${row.home}.${row.table}`),
      mobileExcluded: runtime.mobileExcluded,
      schemaIntersections: runtime.schemaIntersections,
    },
  };
  assert.equal(checks.settings.whitelist, 12);
  assert.equal(checks.settings.portableFromContributions, 5);
  assert.deepEqual(checks.settings.sensitiveInWhitelist, []);
  assert.equal(checks.assets.whitelistedSampleCount, 12);
  assert.equal(checks.assets.protectedDatabaseKeySynced, false);
  assert.equal(checks.assets.portableGateMismatches, 0);
  assert.equal(checks.assets.ankiMediaInWhitelist, false);
  assert.equal(checks.unregistered.businessInSyncedDatabases, 0);
  assert.equal(checks.unregistered.browserTables, 5 + 1);

  const planUnits = new Set([...readFileSync(resolve(root, planFile), 'utf8').matchAll(/^\| (S\d\.\d\d) \|/gm)].map(match => match[1]));
  assert.equal(planUnits.size, 302);
  for (const row of rows) assert.ok(planUnits.has(row.adoptionUnit), `${row.database}.${row.table}: ${row.adoptionUnit} exists in the plan`);
  const plan = readFileSync(resolve(root, planFile), 'utf8');
  const planMatrix = ['资源、笔记、文件夹/标签', '聊天', '题库、卡片/牌组', '卡片模板、Anki媒体、聊天附件持久字节', '媒体进度、字幕与学习计时', '主题/外观、模型/工具配置、插件与技能数据', '派生索引和缓存', '系统授权、绝对路径'].map(row => {
    const line = plan.split('\n').findIndex(text => text.startsWith(`| ${row}`)) + 1;
    assert.ok(line > 0, `§8.2 matrix row present: ${row}`);
    return { row, line };
  });
  assert.equal(planMatrix.length, 8);
  const planGapRule = plan.split('\n').findIndex(text => text.includes('S0.09 | 清点同步分类与待补齐域')) + 1;
  assert.equal(planGapRule, 591);
  const planAcceptance = plan.split('\n')[planGapRule - 1];
  for (const keyword of ['模板', '附件/Anki媒体', '媒体进度/计时', '主题配置', '插件data']) assert.ok(planAcceptance.includes(keyword), `plan §S0.09 acceptance names ${keyword}`);

  const secretAuditIndex = evidenceFiles.map(file => ({ file, hits: findLiteral(index, 'secret', 1, candidate => candidate === file).length })).filter(item => item.hits > 0);
  assert.ok(secretAuditIndex.length > 0, 'secret handling appears in the evidence set');

  const checksRegistry = {
    countMatchesSource: rows.length === registry.length,
    everyCategoryKnown: rows.every(row => ['RowSync', 'FileSync', 'DerivedRebuild', 'LocalRuntime', 'BackupOnly', 'Deprecated'].includes(row.category)),
    everyConflictPolicyKnown: rows.every(row => ['Lww', 'FieldMerge', 'CounterMerge', 'SetUnion', 'DeleteWins', 'NoConflict'].includes(row.conflictPolicy)),
    everyRowHasScopeAndUnit: rows.every(row => row.scope.scope && row.adoptionUnit),
    uniqueChecksumSet: rows.filter(row => row.checksum.included).every(row => row.schemaTable.present !== false),
  };
  for (const [name, value] of Object.entries(checksRegistry)) assert.equal(value, true, `checks.registry.${name}`);

  return {
    formatVersion: 1,
    unit: 'S0.09',
    planContract: {
      unit: 'S0.09',
      planLine: 591,
      planSection: '§8.2',
      deliverable: 'portable/local/secret, RowSync/FileSync classification and the补齐 task table',
      acceptance: 'templates, attachments/Anki media, media progress/timing, theme configuration and plugin data each carry a two-sided task',
      artifact: 'config/baseline/classification.json',
      artifactAliases: [{ name: 'sync-classification', file: 'config/baseline/classification.json', note: 'the plan refers to sync-classification semantics; one file carries both so no duplicate artifact is created' }],
      prerequisites: ['S0.05', 'S0.08'],
      nextUnits: ['S0.10', 'S0.21', 'S0.22', 'S0.23', 'S0.24'],
    },
    sources: [
      { file: schemaFile, sha256: sha256(readFileSync(resolve(root, schemaFile))), role: 'S0.08 snapshot: table existence, columns, indexes, triggers, fingerprint scope' },
      { file: 'config/baseline/contributions.json', sha256: sha256(readFileSync(resolve(root, 'config/baseline/contributions.json'))), role: 'S0.06 settings scopes, cross-checked against the portable whitelist' },
      { file: planFile, unitIdsSha256: sha256(JSON.stringify([...planUnits])), role: 'ordered plan unit ids used as adoption targets; matrix content is captured separately' },
      { file: 'resources/app/migrations', role: 'live in-memory migrations for identity, unique-key and trigger verification; bytes are locked by S0.08' },
    ],
    categories: { order: ['RowSync', 'FileSync', 'DerivedRebuild', 'LocalRuntime', 'BackupOnly', 'Deprecated'], conflictPolicies: ['Lww', 'FieldMerge', 'CounterMerge', 'SetUnion', 'DeleteWins', 'NoConflict'], counts: categoryCounts, conflictPolicyCounts: policyCounts },
    registry: rows,
    databaseCoverage: schema.databases.map(database => ({
      database: database.id,
      path: database.path,
      schemaTables: database.schema.tables.length,
      registered: rows.filter(row => row.database === database.id).length,
      inSyncSet: live.databases.includes(database.id),
      inBackupSet: backupDatabases.includes(database.id),
      unregistered: unregistered.filter(row => row.database === database.id).map(row => ({ table: row.table, kind: row.kind })),
    })),
    syncDatabases: {
      names: live.databases,
      evidence: findLiteral(index, "['vfs', 'llm_usage', 'chat_v2', 'mistakes']", 2),
      backupDatabases,
      callChain: { entry: 'host/plugins/sync/src/run-sync.ts:131', changeCollection: 'host/plugins/sync/src/collect-sync-changes.ts:14', preflight: 'host/plugins/sync/repository/registry-preflight.ts:73', applyGuard: 'host/plugins/sync/repository/apply-record.ts:396' },
    },
    planMatrix: { section: '§8.2 sync data and state matrix', rows: planMatrix, gapRule: { line: planGapRule, text: planAcceptance } },
    scopeAudit: { evidenceFilesWithSecretHandling: secretAuditIndex, registryStructuralChecks: checksRegistry },
    scopeMatrix: {
      rules: [
        { scope: 'portable', categories: ['RowSync', 'FileSync'], meaning: 'row/file changes leave the device through the sync repository' },
        { scope: 'local', categories: ['LocalRuntime', 'BackupOnly'], meaning: 'never merged across devices; BackupOnly is intended for full backups only' },
        { scope: 'derived', categories: ['DerivedRebuild'], meaning: 'rebuilt from synced source rows on each device' },
        { scope: 'absent', categories: ['Deprecated'], meaning: 'registry tombstone for a table dropped from the schema' },
        { scope: 'secret', categories: [], meaning: 'settings keys matching the sensitive-key rules leave the settings table and travel only through the pairing/secret channel' },
      ],
      tableScopeCounts: Object.fromEntries(['portable', 'local', 'derived', 'absent'].map(scope => [scope, rows.filter(row => row.scope.scope === scope).length])),
      localDatabases: schema.localDatabases.map(database => ({ database: database.id, path: database.path, tables: database.schema.tables.map(table => table.name), scope: 'local' })),
    },
    unregisteredTables: unregistered,
    runtimeTables: runtime,
    settings,
    assets: asset,
    fieldMerge: { source: 'host/plugins/sync/src/field-merge.ts', tables: fieldMergeAudit },
    gapTasks: gaps,
    checks,
    sourceHashes: evidenceFiles.map(file => ({ file, sha256: sha256(readDesktop(file)) })),
  };
}

function structuralAudit(data) {
  const errors = [];
  const openFindings = [];
  const seen = new Set();
  for (const row of data.registry) {
    const key = `${row.database}.${row.table}`;
    if (seen.has(key)) errors.push(`duplicate registry row ${key}`);
    seen.add(key);
    if (!['RowSync', 'FileSync', 'DerivedRebuild', 'LocalRuntime', 'BackupOnly', 'Deprecated'].includes(row.category)) errors.push(`${key}: unknown category ${row.category}`);
    if (!['Lww', 'FieldMerge', 'CounterMerge', 'SetUnion', 'DeleteWins', 'NoConflict'].includes(row.conflictPolicy)) errors.push(`${key}: unknown conflict policy ${row.conflictPolicy}`);
    if (!row.primaryKey) errors.push(`${key}: missing primary key declaration`);
    if (!row.scope?.scope) errors.push(`${key}: missing scope`);
    if (!row.adoptionUnit) errors.push(`${key}: missing adoption unit`);
    if (!row.evidence?.file || !row.evidence?.line) errors.push(`${key}: missing desktop evidence`);
    if (!row.sourceSha256) errors.push(`${key}: missing source hash`);
    const rowSync = row.category === 'RowSync';
    if (rowSync !== (row.changeLog !== null)) errors.push(`${key}: change-log declaration does not match category`);
    if (rowSync && row.changeLog.required.some(operation => operation.present === false)) errors.push(`${key}: missing change-log trigger`);
    if (row.businessUniqueKeys.length > 0 && row.schemaTable.businessKeyBacked === false) openFindings.push(`${key}: declared business key ${row.businessUniqueKeys.join(',')} is not backed by a unique index`);
    if (row.schemaTable.present && row.schemaTable.identity.matches === false) openFindings.push(`${key}: declared primary key ${row.schemaTable.identity.declared} differs from SQLite ${row.schemaTable.identity.sqlitePrimaryKey}`);
    if (row.category === 'Deprecated' && row.schemaTable.present) errors.push(`${key}: deprecated table still exists`);
    if (row.category !== 'Deprecated' && !row.schemaTable.present) errors.push(`${key}: registered table missing from the migrated schema`);
  }
  for (const row of data.unregisteredTables) if (row.registered) errors.push(`${row.database}.${row.table}: unregistered table claims registration`);
  for (const row of data.runtimeTables) if (!row.adoptionUnit || !row.reason) errors.push(`runtime ${row.table}: missing unit or reason`);
  for (const task of data.gapTasks) {
    if (!task.desktopCaptureUnit || task.androidAdoptionUnits.length === 0) errors.push(`gap ${task.id}: incomplete task`);
    if (!task.baselineReality || !task.targetDifference) errors.push(`gap ${task.id}: missing baseline/target separation`);
  }
  if (data.checks.unregistered.businessInSyncedDatabases !== 0) errors.push('unregistered business tables exist in synced databases');
  if (data.settings.whitelist.some(entry => entry.sensitive)) errors.push('a sensitive setting key is in the portable whitelist');
  if (data.assets.portableGates.samples.some(sample => sample.actual !== sample.expected)) errors.push('asset portable gate mismatch');
  return { errors, openFindings };
}

export async function run(mode) {
  log('classification.start', { mode, registry: registryFiles.length, evidence: evidenceFiles.length });
  const data = await collect();
  for (const database of data.databaseCoverage) log('classification.coverage', { database: database.database, schemaTables: database.schemaTables, registered: database.registered, unregistered: database.unregistered.length, inSyncSet: database.inSyncSet, inBackupSet: database.inBackupSet });
  log('classification.categories', data.categories.counts);
  log('classification.settings', { whitelist: data.settings.count, portableInContributions: data.settings.portableFromContributions.length, missingFromContributions: data.settings.whitelistMissingFromContributions, declaredOnly: data.settings.whitelist.filter(entry => entry.declaredOnly).map(entry => entry.key) });
  log('classification.assets', { roots: data.assets.directoryCounts, portableGateMismatches: data.checks.assets.portableGateMismatches, ankiMediaInWhitelist: data.checks.assets.ankiMediaInWhitelist });
  log('classification.gaps', { tasks: data.gapTasks.map(task => ({ id: task.id, capture: task.desktopCaptureUnit, android: task.androidAdoptionUnits })) });
  log('classification.findings', { openFindings: structuralAudit(data).openFindings, deprecatedAbsent: data.checks.registry.deprecatedAbsentFromSchema, fileSync: data.checks.registry.fileSyncCategory.registered, jsonDeclaration: data.checks.registry.jsonDeclaration.rule });
  const { errors, openFindings } = structuralAudit(data);
  log('classification.audit', { mode, errors, openFindings, tables: data.registry.length, uniqueKeys: data.checks.registry.uniqueTableKeys, note: 'openFindings are truthful baseline facts kept in the artifact, not drift' });
  assert.deepEqual(errors, [], 'structural audit');
  artifact('config/baseline/classification.json', data, mode);
  log('classification.accepted', {
    mode,
    tables: data.registry.length,
    categories: data.categories.counts,
    settings: data.settings.count,
    unregistered: data.unregisteredTables.length,
    runtime: data.runtimeTables.length,
    gaps: data.gapTasks.length,
    openFindings: { count: openFindings.length, identity: data.checks.registry.identityDifferences.length, unbackedBusinessKeys: data.checks.registry.unbackedBusinessKeys.length, missingTriggers: data.checks.registry.missingTriggers.length, unregisteredBusiness: data.checks.unregistered.businessInSyncedDatabases },
  });
}

main(import.meta.url, run);
