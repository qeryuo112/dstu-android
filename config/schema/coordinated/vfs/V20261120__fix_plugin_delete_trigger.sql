-- S2.04: repair only the physical-delete change-log primary key.
DROP TRIGGER trg__change_log_portable_plugin_records_delete;

CREATE TRIGGER IF NOT EXISTS trg__change_log_portable_plugin_records_delete
AFTER DELETE ON portable_plugin_records
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('portable_plugin_records', OLD.id, 'DELETE');
END;
