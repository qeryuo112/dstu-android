import assert from 'node:assert/strict';
import {
  MAX_OWNER_RECORDS, MAX_RECORD_BYTES, PluginDataError,
  bytes, canonicalJson, checkDepth, decodeUnknown, fail, isJsonObject, mergeUnknown, parseJson,
} from './portable-json.mjs';

const TABLE = 'portable_plugin_records';
const FIELDS = ['owner_id', 'owner_kind', 'namespace', 'record_id', 'package_version',
  'source_hash', 'schema_version', 'min_writer_schema', 'data_json', 'unknown_json',
  'deleted_at', 'device_id', 'local_version', 'updated_at'];
const SAFE_SEGMENT = /^[A-Za-z0-9_.-]{1,128}$/u;
const OWNER = /^(plugin|skill)\.[A-Za-z0-9_.-]{1,121}$/u;
const SOURCE_HASH = /^[0-9a-f]{64}$/u;
const FIXED_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u;

function capability(input) {
  assert.ok(input !== null && typeof input === 'object' && !Array.isArray(input), 'plugin capability');
  const ownerId = input.owner_id;
  assert.equal(typeof ownerId, 'string', 'capability owner_id');
  assert.match(ownerId, OWNER, 'capability owner_id must be plugin.<id> or skill.<id>');
  const ownerKind = ownerId.slice(0, ownerId.indexOf('.'));
  assert.equal(typeof input.package_version, 'string', 'capability package_version');
  assert.ok(input.package_version.length >= 1 && input.package_version.length <= 128,
    'capability package_version length');
  assert.match(input.source_hash, SOURCE_HASH, 'capability source_hash');
  assert.ok(Number.isSafeInteger(input.schema_version) && input.schema_version >= 1,
    'capability schema_version');
  assert.equal(typeof input.device_id, 'string', 'capability device_id');
  assert.ok(input.device_id.length >= 1 && input.device_id.length <= 256,
    'capability device_id length');
  return Object.freeze({
    owner_id: ownerId,
    owner_kind: ownerKind,
    package_version: input.package_version,
    source_hash: input.source_hash,
    schema_version: input.schema_version,
    device_id: input.device_id,
  });
}

function namespace(input) {
  assert.equal(typeof input, 'string', 'namespace');
  if (!SAFE_SEGMENT.test(input) || input.includes('..')) {
    fail('INVALID_NAMESPACE', 'namespace must be 1-128 safe identifier characters without dot-dot');
  }
  return input;
}

function recordId(input) {
  assert.equal(typeof input, 'string', 'record_id');
  if (input.length < 1 || input.length > 512 || input.includes('\0')) {
    fail('INVALID_RECORD_ID', 'record_id must be 1-512 characters without NUL');
  }
  return input;
}

function logicalId(owner, space, key) {
  return JSON.stringify([owner, space, key]);
}

function clock(options) {
  const now = options?.now ?? (() => new Date().toISOString());
  const value = now();
  assert.equal(typeof value, 'string', 'plugin-data clock');
  assert.match(value, FIXED_TIME, 'plugin-data clock must return UTC RFC3339');
  const date = new Date(value);
  assert.equal(Number.isFinite(date.getTime()), true, 'plugin-data clock value');
  assert.equal(date.toISOString(), value.replace(/Z$/u, value.includes('.') ? 'Z' : '.000Z'), 'plugin-data clock calendar fields');
  return value;
}

function assertDatabase(db) {
  assert.ok(db && typeof db.get === 'function' && typeof db.run === 'function',
    'plugin-data store requires a SQL executor');
  assert.ok(typeof db.transaction === 'function' || typeof db.exec === 'function',
    'plugin-data store requires transaction support');
}

function selectRow(db, owner, space, key) {
  return db.get(`SELECT id, ${FIELDS.join(', ')} FROM ${TABLE} WHERE id = ?1`,
    [logicalId(owner.owner_id, space, key)]);
}

function decodeRow(row) {
  if (row === undefined) return null;
  const data = parseJson(row.data_json, 'data_json');
  const unknown = decodeUnknown(row.unknown_json);
  const merged = isJsonObject(data)
    ? mergeUnknown(data, unknown) : data;
  return {
    owner_id: row.owner_id,
    owner_kind: row.owner_kind,
    namespace: row.namespace,
    record_id: row.record_id,
    package_version: row.package_version,
    source_hash: row.source_hash,
    schema_version: Number(row.schema_version),
    min_writer_schema: Number(row.min_writer_schema),
    data: merged,
    unknown,
    unknown_json: row.unknown_json,
    deleted_at: row.deleted_at,
    device_id: row.device_id,
    local_version: Number(row.local_version),
    updated_at: row.updated_at,
  };
}

function encoded(input) {
  checkDepth(input);
  if (input === undefined) fail('INVALID_DATA', 'plugin record payload must be JSON');
  let text;
  try {
    text = canonicalJson(input);
  } catch (error) {
    if (error instanceof PluginDataError) throw error;
    fail('INVALID_JSON', 'plugin record payload is not JSON-serializable');
  }
  if (bytes(text) > MAX_RECORD_BYTES) {
    fail('RECORD_TOO_LARGE', `plugin record exceeds ${MAX_RECORD_BYTES} bytes`);
  }
  return text;
}

function inTransaction(db, fn) {
  if (db.inTransaction === true && typeof db.savepoint === 'function') return db.savepoint(fn);
  if (db.inTransaction === true) {
    db.exec('SAVEPOINT plugin_data_store');
    try {
      const result = fn(db);
      db.exec('RELEASE SAVEPOINT plugin_data_store');
      return result;
    } catch (error) {
      db.exec('ROLLBACK TO SAVEPOINT plugin_data_store');
      db.exec('RELEASE SAVEPOINT plugin_data_store');
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
 * Capability-scoped plugin/skill data. The capability is copied at creation;
 * caller mutations and payload identity fields cannot select another owner.
 */
export function createPluginDataStore(db, capabilityInput, options = {}) {
  assertDatabase(db);
  const owner = capability(capabilityInput);
  const now = options.now ?? (() => new Date().toISOString());
  const sourceAuthority = options.sourceAuthority;
  if (sourceAuthority !== undefined) {
    assert.equal(typeof sourceAuthority, 'function', 'sourceAuthority');
    assert.equal(sourceAuthority(owner.source_hash), true, 'source_hash is not the local source identity');
  }

  const get = (space, key) => {
    const row = selectRow(db, owner, namespace(space), recordId(key));
    const decoded = decodeRow(row);
    return decoded === null || decoded.deleted_at !== null ? null : decoded.data;
  };

  const read = (space, key) => {
    const decoded = decodeRow(selectRow(db, owner, namespace(space), recordId(key)));
    return decoded === null || decoded.deleted_at !== null ? null : decoded;
  };

  const put = (space, key, payload, putOptions = {}) => {
    const ns = namespace(space);
    const id = recordId(key);
    const dataJson = encoded(payload);
    const minWriter = putOptions.minWriterSchema ?? 1;
    assert.ok(Number.isSafeInteger(minWriter) && minWriter >= 1, 'minWriterSchema');
    const timestamp = clock({ now });
    return inTransaction(db, tx => {
      const existing = selectRow(tx, owner, ns, id);
      if (existing !== undefined && Number(existing.min_writer_schema) > owner.schema_version) {
        fail('MIN_WRITER_DOWNGRADE', `writer schema ${owner.schema_version} is below minimum ${existing.min_writer_schema}`);
      }
      const nextMinWriter = Math.max(minWriter, Number(existing?.min_writer_schema ?? 1));
      if (nextMinWriter > owner.schema_version) {
        fail('MIN_WRITER_DOWNGRADE', `requested minimum ${nextMinWriter} exceeds writer schema ${owner.schema_version}`);
      }
      const row = selectRow(tx, owner, ns, id);
      const previousData = row === undefined ? {} : parseJson(row.data_json, 'data_json');
      const previousUnknown = row === undefined ? {} : decodeUnknown(row.unknown_json);
      const incoming = parseJson(dataJson, 'data_json');
      const unknown = { ...previousUnknown };
      if (row !== undefined && Number(row.schema_version) > owner.schema_version
        && isJsonObject(previousData) && isJsonObject(incoming)) {
        for (const [field, value] of Object.entries(previousData)) {
          if (!Object.hasOwn(incoming, field)) {
            if (!Object.hasOwn(unknown, field)) Object.defineProperty(unknown, field, {
              value: { value, hlc: row.updated_at, dev: row.device_id, seq: 0 },
              enumerable: true, configurable: true, writable: true,
            });
          }
        }
      }
      if (isJsonObject(incoming)) for (const [field, value] of Object.entries(incoming)) {
        const opaque = Object.hasOwn(unknown, field) ? unknown[field] : undefined;
        if (opaque !== undefined && canonicalJson(opaque.value) === canonicalJson(value)) continue;
        delete unknown[field];
      }
      const unknownJson = canonicalJson(unknown);
      checkDepth(unknown);
      if (bytes(dataJson) + bytes(unknownJson) > MAX_RECORD_BYTES) {
        fail('RECORD_TOO_LARGE', `plugin record data and unknown fields exceed ${MAX_RECORD_BYTES} bytes`);
      }
      const version = Number(row?.local_version ?? 0) + 1;
      const values = [logicalId(owner.owner_id, ns, id), owner.owner_id, owner.owner_kind,
        ns, id, owner.package_version, owner.source_hash, owner.schema_version,
        nextMinWriter, dataJson, unknownJson, null, owner.device_id, version, timestamp];
      if (row === undefined) {
        tx.run(`INSERT INTO ${TABLE} (id, ${FIELDS.join(', ')})
          VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)`,
        values);
      } else {
        tx.run(`UPDATE ${TABLE} SET owner_id=?2, owner_kind=?3, namespace=?4, record_id=?5,
          package_version=?6, source_hash=?7, schema_version=?8, min_writer_schema=?9,
          data_json=?10, unknown_json=?11, deleted_at=?12, device_id=?13,
          local_version=?14, updated_at=?15 WHERE id=?1`, values);
      }
      return { namespace: ns, record_id: id, local_version: version, updated_at: timestamp };
    });
  };

  const remove = (space, key) => {
    const ns = namespace(space);
    const id = recordId(key);
    const timestamp = clock({ now });
    return inTransaction(db, tx => {
      const row = selectRow(tx, owner, ns, id);
      if (row === undefined || row.deleted_at !== null) return { deleted: false };
      if (Number(row.min_writer_schema) > owner.schema_version) {
        fail('MIN_WRITER_DOWNGRADE', `writer schema ${owner.schema_version} is below minimum ${row.min_writer_schema}`);
      }
      tx.run(`UPDATE ${TABLE} SET deleted_at=?1, package_version=?2, source_hash=?3,
        schema_version=?4, device_id=?5, local_version=local_version+1, updated_at=?1
        WHERE id=?6 AND deleted_at IS NULL`,
      [timestamp, owner.package_version, owner.source_hash, owner.schema_version,
        owner.device_id, row.id]);
      return { deleted: true, updated_at: timestamp };
    });
  };

  return Object.freeze({
    owner: { ...owner },
    put,
    get,
    read,
    remove,
    /** Local-only cleanup, not a sync tombstone. Requires explicit owner policy. */
    purge: (space, key) => {
      if (options.allowLocalPurge !== true) {
        fail('LOCAL_PURGE_DISABLED', 'deleting synced plugin data requires an explicit local policy');
      }
      const ns = namespace(space);
      const id = recordId(key);
      return inTransaction(db, tx => tx.run(`DELETE FROM ${TABLE} WHERE id=?1`,
        [logicalId(owner.owner_id, ns, id)]).changes > 0);
    },
    /** Explicit owner-wide delete: tombstones all live rows and retains data. */
    removeAll: () => {
      const timestamp = clock({ now });
      return inTransaction(db, tx => {
        const rows = tx.all(`SELECT id, min_writer_schema FROM ${TABLE}
          WHERE owner_id=?1 AND deleted_at IS NULL`, [owner.owner_id]);
        for (const row of rows) {
          if (Number(row.min_writer_schema) > owner.schema_version) {
            fail('MIN_WRITER_DOWNGRADE', `writer schema ${owner.schema_version} is below minimum ${row.min_writer_schema}`);
          }
        }
        const changed = tx.run(`UPDATE ${TABLE} SET deleted_at=?1,
          package_version=?2, source_hash=?3, schema_version=?4, device_id=?5,
          local_version=local_version+1, updated_at=?1
          WHERE owner_id=?6 AND deleted_at IS NULL`,
        [timestamp, owner.package_version, owner.source_hash, owner.schema_version,
          owner.device_id, owner.owner_id]).changes;
        return { deleted: changed, updated_at: timestamp };
      });
    },
    quota: () => db.get(`SELECT COUNT(*) AS records,
      COALESCE(SUM(length(CAST(data_json AS BLOB)) + length(CAST(unknown_json AS BLOB))), 0) AS bytes
      FROM ${TABLE} WHERE owner_id=?1 AND deleted_at IS NULL`, [owner.owner_id]),
    limits: Object.freeze({ records: MAX_OWNER_RECORDS, bytes: 5 * 1024 * 1024,
      recordBytes: MAX_RECORD_BYTES, depth: 16 }),
  });
}
