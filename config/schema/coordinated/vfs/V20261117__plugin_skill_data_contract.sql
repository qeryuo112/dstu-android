-- S0.24: portable plugin and skill data, separate from installation and trust.
--
-- This is a coordinated migration. It is installed only with desktop/Android
-- consumers that understand portable_plugin_records. Plugin code, package
-- installation, enablement, and trust never live in this table.
--
-- id is the canonical JSON tuple [owner_id, namespace, record_id]. The
-- composite key prevents a crafted remote id from writing under another owner.
-- All quota and identity checks run in SQLite so an apply path cannot bypass
-- the store adapter.

CREATE TABLE IF NOT EXISTS portable_plugin_records (
    id                TEXT PRIMARY KEY NOT NULL,
    owner_id          TEXT NOT NULL,
    owner_kind        TEXT NOT NULL CHECK (owner_kind IN ('plugin', 'skill')),
    namespace         TEXT NOT NULL,
    record_id         TEXT NOT NULL,
    package_version   TEXT NOT NULL,
    source_hash       TEXT NOT NULL,
    schema_version    INTEGER NOT NULL,
    min_writer_schema INTEGER NOT NULL DEFAULT 1,
    data_json         TEXT NOT NULL,
    unknown_json      TEXT NOT NULL DEFAULT '{}',
    deleted_at        TEXT,
    device_id         TEXT NOT NULL,
    local_version     INTEGER NOT NULL DEFAULT 1,
    updated_at        TEXT NOT NULL,

    CHECK (id = json_array(owner_id, namespace, record_id)),
    CHECK (owner_kind IN ('plugin', 'skill')),
    CHECK (length(owner_id) BETWEEN 1 AND 128
           AND owner_id NOT GLOB '*[^A-Za-z0-9_.-]*'),
    CHECK (substr(owner_id, 1, length(owner_kind) + 1) = owner_kind || '.'
           AND length(owner_id) > length(owner_kind) + 1),
    CHECK (length(namespace) BETWEEN 1 AND 128
           AND namespace NOT GLOB '*[^A-Za-z0-9_.-]*'
           AND namespace NOT GLOB '*..*'),
    CHECK (length(record_id) BETWEEN 1 AND 512 AND instr(record_id, char(0)) = 0),
    CHECK (length(package_version) BETWEEN 1 AND 128
           AND instr(package_version, char(0)) = 0),
    CHECK (length(source_hash) = 64 AND source_hash NOT GLOB '*[^0-9a-f]*'),
    CHECK (typeof(schema_version) = 'integer' AND schema_version BETWEEN 1 AND 2147483647),
    CHECK (typeof(min_writer_schema) = 'integer'
           AND min_writer_schema BETWEEN 1 AND 2147483647
           AND min_writer_schema <= schema_version),
    CHECK (typeof(data_json) = 'text'
           AND json_valid(data_json)
           AND length(CAST(data_json AS BLOB))
               + length(CAST(unknown_json AS BLOB)) <= 65536),
    CHECK (typeof(unknown_json) = 'text'
           AND json_valid(unknown_json)
           AND json_type(unknown_json) = 'object'),
    CHECK (deleted_at IS NULL OR (typeof(deleted_at) = 'text' AND deleted_at <> '')),
    CHECK (length(device_id) BETWEEN 1 AND 256
           AND instr(device_id, char(0)) = 0),
    CHECK (typeof(local_version) = 'integer' AND local_version > 0),
    CHECK (typeof(updated_at) = 'text' AND updated_at <> '')
);

CREATE INDEX IF NOT EXISTS idx_portable_plugin_records_owner
    ON portable_plugin_records(owner_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_portable_plugin_records_namespace
    ON portable_plugin_records(owner_id, namespace, deleted_at);
CREATE INDEX IF NOT EXISTS idx_portable_plugin_records_updated_at
    ON portable_plugin_records(updated_at);
CREATE INDEX IF NOT EXISTS idx_portable_plugin_records_device_version
    ON portable_plugin_records(device_id, local_version);

-- Quota checks use committed live rows only. Tombstones retain their last known
-- payload for replay but are excluded from the active owner quota.
CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_quota_insert
BEFORE INSERT ON portable_plugin_records
WHEN NEW.deleted_at IS NULL AND ((
    SELECT COUNT(*) FROM portable_plugin_records
    WHERE owner_id = NEW.owner_id AND deleted_at IS NULL
) + 1 > 2000
OR (
    SELECT COALESCE(SUM(
        length(CAST(data_json AS BLOB)) + length(CAST(unknown_json AS BLOB))
    ), 0) FROM portable_plugin_records
    WHERE owner_id = NEW.owner_id AND deleted_at IS NULL
) + length(CAST(NEW.data_json AS BLOB)) + length(CAST(NEW.unknown_json AS BLOB)) > 5242880)
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records owner quota exceeded');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_quota_update
BEFORE UPDATE ON portable_plugin_records
WHEN NEW.deleted_at IS NULL AND ((
    SELECT COUNT(*) FROM portable_plugin_records
    WHERE owner_id = NEW.owner_id AND deleted_at IS NULL AND id <> OLD.id
) + 1 > 2000
OR (
    SELECT COALESCE(SUM(
        length(CAST(data_json AS BLOB)) + length(CAST(unknown_json AS BLOB))
    ), 0) FROM portable_plugin_records
    WHERE owner_id = NEW.owner_id AND deleted_at IS NULL AND id <> OLD.id
) + length(CAST(NEW.data_json AS BLOB)) + length(CAST(NEW.unknown_json AS BLOB)) > 5242880)
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records owner quota exceeded');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_identity_immutable
BEFORE UPDATE ON portable_plugin_records
WHEN NEW.id IS NOT OLD.id
  OR NEW.owner_id IS NOT OLD.owner_id
  OR NEW.owner_kind IS NOT OLD.owner_kind
  OR NEW.namespace IS NOT OLD.namespace
  OR NEW.record_id IS NOT OLD.record_id
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records logical identity is immutable');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_depth_insert
BEFORE INSERT ON portable_plugin_records
WHEN (WITH RECURSIVE nodes AS (
        SELECT id, parent, type FROM json_tree(NEW.data_json)
      ), depth(id, value) AS (
        SELECT id, 1 FROM nodes WHERE parent IS NULL
        UNION ALL
        SELECT nodes.id, depth.value + 1 FROM nodes JOIN depth ON nodes.parent = depth.id
      )
      SELECT max(value) FROM depth
        JOIN nodes ON nodes.id = depth.id
        WHERE nodes.type IN ('object', 'array')
      ) > 16
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records JSON depth exceeds 16');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_depth_update
BEFORE UPDATE ON portable_plugin_records
WHEN (WITH RECURSIVE nodes AS (
        SELECT id, parent, type FROM json_tree(NEW.data_json)
      ), depth(id, value) AS (
        SELECT id, 1 FROM nodes WHERE parent IS NULL
        UNION ALL
        SELECT nodes.id, depth.value + 1 FROM nodes JOIN depth ON nodes.parent = depth.id
      )
      SELECT max(value) FROM depth
        JOIN nodes ON nodes.id = depth.id
        WHERE nodes.type IN ('object', 'array')
      ) > 16
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records JSON depth exceeds 16');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_unknown_depth_insert
BEFORE INSERT ON portable_plugin_records
WHEN (WITH RECURSIVE nodes AS (
        SELECT id, parent, type FROM json_tree(NEW.unknown_json)
      ), depth(id, value) AS (
        SELECT id, 1 FROM nodes WHERE parent IS NULL
        UNION ALL
        SELECT nodes.id, depth.value + 1 FROM nodes JOIN depth ON nodes.parent = depth.id
      )
      SELECT max(value) FROM depth
        JOIN nodes ON nodes.id = depth.id
        WHERE nodes.type IN ('object', 'array')
      ) > 16
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records unknown JSON depth exceeds 16');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_unknown_depth_update
BEFORE UPDATE ON portable_plugin_records
WHEN (WITH RECURSIVE nodes AS (
        SELECT id, parent, type FROM json_tree(NEW.unknown_json)
      ), depth(id, value) AS (
        SELECT id, 1 FROM nodes WHERE parent IS NULL
        UNION ALL
        SELECT nodes.id, depth.value + 1 FROM nodes JOIN depth ON nodes.parent = depth.id
      )
      SELECT max(value) FROM depth
        JOIN nodes ON nodes.id = depth.id
        WHERE nodes.type IN ('object', 'array')
      ) > 16
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records unknown JSON depth exceeds 16');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_min_writer
BEFORE UPDATE ON portable_plugin_records
WHEN NEW.min_writer_schema < OLD.min_writer_schema
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records min_writer_schema cannot decrease');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_unknown_shape_insert
BEFORE INSERT ON portable_plugin_records
WHEN EXISTS (
    SELECT 1 FROM json_each(NEW.unknown_json) AS field
    WHERE field.type IS NOT 'object'
       OR json_type(field.value, '$.value') IS NULL
       OR json_type(field.value, '$.hlc') IS NOT 'text'
       OR json_type(field.value, '$.dev') IS NOT 'text'
       OR json_type(field.value, '$.seq') IS NOT 'integer'
)
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records unknown fields require version envelopes');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_unknown_shape_update
BEFORE UPDATE ON portable_plugin_records
WHEN EXISTS (
    SELECT 1 FROM json_each(NEW.unknown_json) AS field
    WHERE field.type IS NOT 'object'
       OR json_type(field.value, '$.value') IS NULL
       OR json_type(field.value, '$.hlc') IS NOT 'text'
       OR json_type(field.value, '$.dev') IS NOT 'text'
       OR json_type(field.value, '$.seq') IS NOT 'integer'
)
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records unknown fields require version envelopes');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_plugin_records_schema_write_floor
BEFORE UPDATE ON portable_plugin_records
WHEN NEW.schema_version < OLD.min_writer_schema
BEGIN
    SELECT RAISE(ABORT, 'portable_plugin_records writer schema is below the record minimum');
END;

CREATE TRIGGER IF NOT EXISTS trg__change_log_portable_plugin_records_insert
AFTER INSERT ON portable_plugin_records
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('portable_plugin_records', NEW.id, 'INSERT');
END;

CREATE TRIGGER IF NOT EXISTS trg__change_log_portable_plugin_records_update
AFTER UPDATE ON portable_plugin_records
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('portable_plugin_records', NEW.id, 'UPDATE');
END;

CREATE TRIGGER IF NOT EXISTS trg__change_log_portable_plugin_records_delete
AFTER DELETE ON portable_plugin_records
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('portable_plugin_records', NEW.id, 'DELETE');
END;
