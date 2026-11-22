-- S0.21: make user-created Anki templates portable through the existing row sync.
-- This is a coordinated migration, not a historical desktop migration. It is
-- delivered with the Android/desktop increment and must only be installed after
-- both sides can consume the new sync columns.

ALTER TABLE custom_anki_templates ADD COLUMN device_id TEXT;
ALTER TABLE custom_anki_templates ADD COLUMN local_version INTEGER DEFAULT 0;
ALTER TABLE custom_anki_templates ADD COLUMN deleted_at TEXT;

CREATE INDEX IF NOT EXISTS idx_custom_anki_templates_local_version
    ON custom_anki_templates(local_version);
CREATE INDEX IF NOT EXISTS idx_custom_anki_templates_deleted_at
    ON custom_anki_templates(deleted_at);
CREATE INDEX IF NOT EXISTS idx_custom_anki_templates_device_id
    ON custom_anki_templates(device_id);
CREATE INDEX IF NOT EXISTS idx_custom_anki_templates_updated_at
    ON custom_anki_templates(updated_at);
CREATE INDEX IF NOT EXISTS idx_custom_anki_templates_device_version
    ON custom_anki_templates(device_id, local_version);
CREATE INDEX IF NOT EXISTS idx_custom_anki_templates_updated_not_deleted
    ON custom_anki_templates(updated_at) WHERE deleted_at IS NULL;

CREATE TRIGGER IF NOT EXISTS trg__change_log_custom_anki_templates_insert
AFTER INSERT ON custom_anki_templates
WHEN NEW.is_built_in = 0 OR NEW.user_modified = 1 OR NEW.user_deleted = 1
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('custom_anki_templates', NEW.id, 'INSERT');
END;

CREATE TRIGGER IF NOT EXISTS trg__change_log_custom_anki_templates_update
AFTER UPDATE ON custom_anki_templates
WHEN NEW.is_built_in = 0 OR NEW.user_modified = 1 OR NEW.user_deleted = 1
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('custom_anki_templates', NEW.id, 'UPDATE');
END;

CREATE TRIGGER IF NOT EXISTS trg__change_log_custom_anki_templates_delete
AFTER DELETE ON custom_anki_templates
WHEN OLD.is_built_in = 0 OR OLD.user_modified = 1 OR OLD.user_deleted = 1
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('custom_anki_templates', OLD.id, 'DELETE');
END;
