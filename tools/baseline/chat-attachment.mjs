import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readdirSync, mkdirSync, symlinkSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { desktop, root, artifact, main, sha256, log } from './lib.mjs';
import { promoteChatAttachment, resolveChatAttachment } from '../../desktop-increment/chat-attachment.mjs';

const DATABASES = ['vfs', 'llm_usage', 'chat_v2', 'mistakes'];
const UPSTREAM_VFS = 'config/schema/history/vfs/V20261115__review_plans_fsrs.sql';
const UPSTREAM_VFS_SHA256 = '483b242fbf76ded1755644dbb0c6ad9391a91f73af1b32f5d7407a2614faa7d1';

function databaseHeads(storage) {
  return Object.fromEntries(DATABASES.map(database => {
    const history = storage.database(database).all('SELECT version FROM refinery_schema_history ORDER BY version');
    return [database, { version: history.at(-1)?.version ?? 0, count: history.length }];
  }));
}

export async function run(mode) {
  const { createStorageService } = await import(pathToFileURL(resolve(desktop, 'host/platform/storage/service.ts')).href);
  const temp = mkdtempSync(join(tmpdir(), 'dstu-chat-promotion-'));
  const storage = createStorageService({ baseDir: temp, dataLocalDir: join(temp, 'local'), migrationsDir: resolve(root, 'config/schema/history') });
  try {
    await storage.migrations.runAll();
    const heads = databaseHeads(storage);
    assert.equal(heads.vfs.version, 20261115, 'upstream vfs baseline');
    assert.equal(heads.chat_v2.version, 20261111, 'chat_v2 baseline');
    assert.equal(sha256(readFileSync(resolve(root, UPSTREAM_VFS))), UPSTREAM_VFS_SHA256,
      'upstream vfs snapshot bytes');
    const db = storage.database('chat_v2');
    db.exec(`INSERT INTO chat_v2_sessions(id,mode,title,created_at,updated_at) VALUES('session','general_chat','public fixture','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');
      INSERT INTO chat_v2_messages(id,session_id,role,timestamp) VALUES('message','session','user',1767225600000);
      INSERT INTO chat_v2_attachments(id,message_id,name,type,mime_type,size,created_at) VALUES('attachment','message','public.txt','document','text/plain',6,'2026-01-01T00:00:00Z');`);
    const activeDir = dirname(storage.pathOf('mistakes'));
    const data = Buffer.from('public');
    const input = { db, activeDir, attachmentId: 'attachment', data, expectedSha256: sha256(data), updatedAt: '2026-01-01T00:00:01Z' };
    const receipt = promoteChatAttachment(input);
    const reused = promoteChatAttachment(input);
    assert.equal(reused.reused, true);
    assert.deepEqual(resolveChatAttachment({ activeDir, storagePath: receipt.storagePath, expectedSha256: receipt.sha256 }), data);
    assert.throws(() => promoteChatAttachment({ ...input, expectedSha256: '0'.repeat(64) }), /hash mismatch/);
    assert.throws(() => promoteChatAttachment({ ...input, data: Buffer.from('other'), expectedSha256: sha256('other') }), /size mismatch/);
    assert.throws(() => resolveChatAttachment({ activeDir, storagePath: 'active/chat_attachments/../secret', expectedSha256: receipt.sha256 }), /storage path/);
    db.exec(`CREATE TRIGGER injected_attachment_failure BEFORE UPDATE ON chat_v2_attachments BEGIN SELECT RAISE(ABORT,'injected promotion failure'); END;`);
    const newData = Buffer.from('second');
    assert.throws(() => promoteChatAttachment({ ...input, data: newData, expectedSha256: sha256(newData) }), /injected promotion failure/);
    db.exec('DROP TRIGGER injected_attachment_failure');
    assert.deepEqual(readdirSync(join(activeDir, 'chat_attachments', 'attachment')), [receipt.sha256]);
    const outside = join(temp, 'outside'); mkdirSync(outside);
    symlinkSync(outside, join(activeDir, 'chat_attachments', 'linked'));
    db.exec(`INSERT INTO chat_v2_attachments(id,message_id,name,type,mime_type,size,created_at) VALUES('linked','message','public.txt','document','text/plain',6,'2026-01-01T00:00:00Z');`);
    assert.throws(() => promoteChatAttachment({ ...input, attachmentId: 'linked' }), /linked/);
    const row = db.get('SELECT storage_path,content_hash,status FROM chat_v2_attachments WHERE id=?', ['attachment']);
    assert.equal(row.storage_path, receipt.storagePath);
    const result = { formatVersion: 1, unit: 'S0.21', receipt, metadata: row,
      checks: { persisted: true, hashMatched: true, reuse: true, badHashRejected: true, badSizeRejected: true,
        traversalRejected: true, failedMetadataLeavesOriginalBytes: true, linkedDirectoryRejected: true, executesContent: false,
        upstreamVfsApplied: true },
      migrations: {
        heads,
        upstreamVfs: { file: UPSTREAM_VFS, sha256: UPSTREAM_VFS_SHA256 },
        rule: 'chat attachment regression migrates frozen upstream history snapshots only',
      },
      scope: 'callable desktop persistence adapter over production chat table; UI send-flow wiring belongs to S3.13' };
    artifact('config/contracts/chat-attachment.json', result, mode);
    log('chat-attachment.accepted', { unit: 'S0.21', mode, ...result.checks });
  } finally { storage.shutdown(); rmSync(temp, { recursive: true, force: true }); }
}

main(import.meta.url, run);
