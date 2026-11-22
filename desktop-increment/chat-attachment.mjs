import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { lstatSync, mkdirSync, openSync, writeSync, fsyncSync, closeSync, renameSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const digest = value => createHash('sha256').update(value).digest('hex');
const validId = value => assert.match(value, /^[A-Za-z0-9_-]{1,128}$/u, 'invalid attachment identity');
function directory(path) {
  mkdirSync(path, { recursive: true });
  assert.equal(lstatSync(path).isSymbolicLink(), false, 'linked attachment directory rejected');
}

export function promoteChatAttachment({ db, activeDir, attachmentId, data, expectedSha256, updatedAt }) {
  validId(attachmentId);
  assert.ok(data instanceof Uint8Array && data.byteLength <= 256 * 1024 * 1024, 'attachment byte budget');
  assert.match(expectedSha256, /^[0-9a-f]{64}$/u);
  assert.equal(digest(data), expectedSha256, 'attachment hash mismatch');
  assert.ok(Number.isFinite(Date.parse(updatedAt)), 'attachment update time');
  const row = db.get('SELECT id,size,deleted_at FROM chat_v2_attachments WHERE id=?', [attachmentId]);
  assert.ok(row && row.deleted_at === null, 'attachment row missing or deleted');
  assert.equal(Number(row.size), data.byteLength, 'attachment size mismatch');
  directory(activeDir);
  const root = join(activeDir, 'chat_attachments'); directory(root);
  const owner = join(root, attachmentId); directory(owner);
  const target = join(owner, expectedSha256);
  const storagePath = `active/chat_attachments/${attachmentId}/${expectedSha256}`;
  const reused = existsSync(target);
  if (reused) {
    assert.equal(lstatSync(target).isSymbolicLink(), false, 'linked attachment target rejected');
    assert.equal(digest(readFileSync(target)), expectedSha256, 'persistent attachment object corrupt');
  } else {
    const temporary = join(owner, `.pending-${randomUUID()}`);
    let fd;
    try {
      fd = openSync(temporary, 'wx', 0o600);
      let offset = 0;
      while (offset < data.byteLength) {
        const written = writeSync(fd, data, offset, Math.min(65536, data.byteLength - offset));
        assert.ok(written > 0, 'attachment write made no progress'); offset += written;
      }
      fsyncSync(fd); closeSync(fd); fd = undefined;
      renameSync(temporary, target);
    } catch (error) {
      if (fd !== undefined) closeSync(fd);
      rmSync(temporary, { force: true });
      throw error;
    }
  }
  try {
    db.transaction(tx => {
      const changed = tx.run(`UPDATE chat_v2_attachments SET storage_path=?,content_hash=?,status='ready',error=NULL,updated_at=?
        WHERE id=? AND deleted_at IS NULL AND size=?`, [storagePath, expectedSha256, updatedAt, attachmentId, data.byteLength]);
      assert.equal(changed.changes, 1, 'attachment metadata changed during promotion');
    });
  } catch (error) {
    if (!reused) rmSync(target, { force: true });
    throw error;
  }
  return { attachmentId, storagePath, sha256: expectedSha256, size: data.byteLength, reused, executable: false };
}

export function resolveChatAttachment({ activeDir, storagePath, expectedSha256 }) {
  const match = /^active\/chat_attachments\/([A-Za-z0-9_-]{1,128})\/([0-9a-f]{64})$/u.exec(storagePath);
  assert.ok(match && match[2] === expectedSha256, 'untrusted attachment storage path');
  for (const path of [activeDir, join(activeDir, 'chat_attachments'), join(activeDir, 'chat_attachments', match[1])]) {
    assert.equal(lstatSync(path).isSymbolicLink(), false, 'linked attachment parent rejected');
  }
  const target = join(activeDir, 'chat_attachments', match[1], match[2]);
  assert.equal(lstatSync(target).isSymbolicLink(), false, 'linked attachment target rejected');
  const data = readFileSync(target);
  assert.equal(digest(data), expectedSha256, 'attachment byte hash mismatch');
  return data;
}
