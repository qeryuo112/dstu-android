import assert from 'node:assert/strict';
import {
  canonicalJson, decodeUnknown, fail, isJsonObject, mergeUnknown, parseJson, stringifyJsonLossless,
} from './portable-json.mjs';

const TABLE = 'portable_plugin_records';
const OWNER = /^(plugin|skill)\.[A-Za-z0-9_.-]{1,121}$/u;
const SAFE_SEGMENT = /^[A-Za-z0-9_.-]{1,128}$/u;

function logicalId(owner, namespace, recordId) {
  return JSON.stringify([owner, namespace, recordId]);
}

function validateIdentity(row) {
  assert.ok(row && typeof row === 'object' && !Array.isArray(row), 'plugin record must be an object');
  assert.match(row.owner_id ?? '', OWNER, 'plugin record owner_id');
  assert.equal(row.owner_kind, row.owner_id.slice(0, row.owner_id.indexOf('.')), 'plugin record owner kind');
  assert.match(row.namespace ?? '', SAFE_SEGMENT, 'plugin record namespace');
  assert.equal(row.namespace.includes('..'), false, 'plugin record namespace');
  assert.equal(typeof row.record_id, 'string', 'plugin record record_id');
  assert.ok(row.record_id.length >= 1 && row.record_id.length <= 512, 'plugin record record_id length');
  // source_hash is package provenance, not output authority. Reject malformed
  // bytes but do not require them to equal the currently installed package.
  assert.match(row.source_hash ?? '', /^[0-9a-f]{64}$/u, 'plugin record source_hash');
  assert.ok(Number.isSafeInteger(row.schema_version) && row.schema_version >= 1,
    'plugin record schema_version');
  assert.ok(Number.isSafeInteger(row.min_writer_schema) && row.min_writer_schema >= 1
    && row.min_writer_schema <= row.schema_version, 'plugin record min_writer_schema');
  assert.equal(typeof row.package_version, 'string', 'plugin record package_version');
  assert.ok(row.package_version.length >= 1 && row.package_version.length <= 128,
    'plugin record package_version length');
  assert.equal(typeof row.device_id, 'string', 'plugin record device_id');
  assert.ok(row.device_id.length >= 1 && row.device_id.length <= 256, 'plugin record device_id length');
  assert.ok(Number.isSafeInteger(row.local_version) && row.local_version >= 1,
    'plugin record local_version');
  assert.equal(typeof row.updated_at, 'string', 'plugin record updated_at');
}

function compatibleLocalSchema(db, row) {
  const local = db.get(`SELECT schema_version FROM ${TABLE} WHERE id=?1`,
    [logicalId(row.owner_id, row.namespace, row.record_id)]);
  return local === undefined || Number(local.schema_version) <= row.schema_version;
}

function inTransaction(db, fn) {
  if (db.inTransaction === true && typeof db.savepoint === 'function') return db.savepoint(fn);
  if (db.inTransaction === true) {
    db.exec('SAVEPOINT plugin_data_apply');
    try {
      const result = fn(db);
      db.exec('RELEASE SAVEPOINT plugin_data_apply');
      return result;
    } catch (error) {
      db.exec('ROLLBACK TO SAVEPOINT plugin_data_apply');
      db.exec('RELEASE SAVEPOINT plugin_data_apply');
      throw error;
    }
  }
  if (typeof db.transaction === 'function') return db.transaction(fn, { behavior: 'immediate' });
  if (typeof db.savepoint === 'function') return db.savepoint(fn);
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = fn(db);
    db.exec('COMMIT');
    return result;
  } catch (error) {
    try { db.exec('ROLLBACK'); } catch { /* preserve the original failure */ }
    throw error;
  }
}

/**
 * Apply one remotely collected portable record. Installation, enablement and
 * trust are deliberately absent: this function only writes scoped data.
 */
export function applyPluginDataRecord(db, row) {
  validateIdentity(row);
  const id = logicalId(row.owner_id, row.namespace, row.record_id);
  assert.equal(row.id ?? id, id, 'plugin record id must equal its canonical owner tuple');
  if (!compatibleLocalSchema(db, row)) {
    fail('SCHEMA_DOWNGRADE', 'remote plugin record is older than the local schema');
  }
  const dataJson = typeof row.data_json === 'string' ? row.data_json
    : stringifyJsonLossless(row.data ?? {});
  const unknownJson = typeof row.unknown_json === 'string'
    ? row.unknown_json : stringifyJsonLossless(row.unknown ?? {});
  parseJson(dataJson, 'data_json');
  decodeUnknown(unknownJson);
  return inTransaction(db, tx => {
    const existing = tx.get(`SELECT * FROM ${TABLE} WHERE id=?1`, [id]);
    if (existing !== undefined && Number(existing.schema_version) > row.schema_version) {
      fail('SCHEMA_DOWNGRADE', 'remote plugin record is older than the local schema');
    }
    const previousUnknown = existing === undefined ? {} : decodeUnknown(existing.unknown_json);
    const incoming = parseJson(dataJson, 'data_json');
    const unknown = { ...previousUnknown, ...decodeUnknown(unknownJson) };
    for (const field of isJsonObject(incoming) ? Object.keys(incoming) : []) {
      const envelope = Object.hasOwn(unknown, field) ? unknown[field] : undefined;
      if (envelope !== undefined
        && canonicalJson(envelope.value) === canonicalJson(incoming[field])) continue;
      delete unknown[field];
    }
    const values = [id, row.owner_id, row.owner_kind, row.namespace, row.record_id,
      row.package_version, row.source_hash, row.schema_version, row.min_writer_schema,
      dataJson, canonicalJson(unknown), row.deleted_at ?? null, row.device_id,
      row.local_version, row.updated_at];
    if (existing === undefined) {
      tx.run(`INSERT INTO ${TABLE} (id, owner_id, owner_kind, namespace, record_id,
        package_version, source_hash, schema_version, min_writer_schema, data_json,
        unknown_json, deleted_at, device_id, local_version, updated_at)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)`, values);
    } else {
      tx.run(`UPDATE ${TABLE} SET owner_id=?2, owner_kind=?3, namespace=?4, record_id=?5,
        package_version=?6, source_hash=?7, schema_version=?8, min_writer_schema=?9,
        data_json=?10, unknown_json=?11, deleted_at=?12, device_id=?13,
        local_version=?14, updated_at=?15 WHERE id=?1`, values);
    }
    return { id, applied: true };
  });
}

export function applyPluginDataBatch(db, rows) {
  assert.ok(Array.isArray(rows), 'plugin data batch');
  return inTransaction(db, tx => rows.map(row => applyPluginDataRecord(tx, row)));
}

export { mergeUnknown };
