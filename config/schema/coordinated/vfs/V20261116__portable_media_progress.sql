-- S0.22: portable media evidence.
--
-- Playback progress, subtitles, and study time are append-only observations.
-- Legacy media_progress / media_transcript_segments / study_time_daily remain
-- local runtime tables; their values are copied into local events by the adapter
-- and are never merged as row-LWW aggregates.

CREATE TABLE IF NOT EXISTS portable_media_events (
    event_id      TEXT PRIMARY KEY NOT NULL,
    device_id     TEXT NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('study', 'resume', 'subtitle')),
    resource_id   TEXT NOT NULL,
    logical_date  TEXT NOT NULL,
    position_ms   INTEGER,
    duration_ms   INTEGER,
    seconds       INTEGER,
    observed_at   TEXT NOT NULL,
    data          TEXT NOT NULL DEFAULT '{}',
    local_version INTEGER NOT NULL DEFAULT 0,
    updated_at    TEXT NOT NULL,
    deleted_at    TEXT,
    CHECK (event_id <> '' AND device_id <> '' AND kind <> ''
           AND resource_id <> '' AND observed_at <> ''),
    CHECK (typeof(observed_at) = 'text'
           AND (observed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z'
             OR observed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'
             OR observed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9][+-][0-9][0-9]:[0-9][0-9]'
             OR observed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][+-][0-9][0-9]:[0-9][0-9]')
           AND substr(observed_at, 12, 2) BETWEEN '00' AND '23'
           AND substr(observed_at, 15, 2) BETWEEN '00' AND '59'
           AND substr(observed_at, 18, 2) BETWEEN '00' AND '59'
           AND date(substr(observed_at, 1, 10)) = substr(observed_at, 1, 10)
           AND (substr(observed_at, -1) = 'Z'
                OR (substr(observed_at, -5, 2) BETWEEN '00' AND '23'
                    AND substr(observed_at, -2, 2) BETWEEN '00' AND '59'))),
    CHECK (logical_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
           AND date(logical_date) = logical_date),
    CHECK ((kind = 'study' AND typeof(seconds) = 'integer'
            AND seconds IS NOT NULL AND seconds BETWEEN 0 AND 86400
            AND position_ms IS NULL AND duration_ms IS NULL)
        OR (kind = 'resume' AND seconds IS NULL
            AND typeof(position_ms) = 'integer' AND position_ms IS NOT NULL
            AND typeof(duration_ms) = 'integer' AND duration_ms IS NOT NULL
            AND position_ms BETWEEN 0 AND 9007199254740991
            AND duration_ms BETWEEN 0 AND 9007199254740991
            AND (duration_ms = 0 OR position_ms <= duration_ms))
        OR (kind = 'subtitle' AND seconds IS NULL
            AND typeof(position_ms) = 'integer' AND position_ms IS NOT NULL
            AND typeof(duration_ms) = 'integer' AND duration_ms IS NOT NULL
            AND position_ms BETWEEN 0 AND 9007199254740991
            AND duration_ms BETWEEN position_ms AND 9007199254740991)),
    CHECK (typeof(data) = 'text' AND typeof(local_version) = 'integer'
           AND typeof(updated_at) = 'text' AND local_version > 0),
    CHECK (json_valid(data) AND json_type(data) = 'object'),
    CHECK (kind <> 'subtitle'
           OR (json_type(data, '$.plan_id') = 'text'
               AND json_type(data, '$.version') = 'integer'
               AND json_type(data, '$.index') = 'integer'
               AND json_type(data, '$.text') = 'text'))
);

CREATE INDEX IF NOT EXISTS idx_portable_media_events_device
    ON portable_media_events(device_id, logical_date);
CREATE INDEX IF NOT EXISTS idx_portable_media_events_resource
    ON portable_media_events(resource_id, logical_date, kind, observed_at);
CREATE INDEX IF NOT EXISTS idx_portable_media_events_updated_at
    ON portable_media_events(updated_at);
CREATE INDEX IF NOT EXISTS idx_portable_media_events_device_version
    ON portable_media_events(device_id, local_version);
CREATE INDEX IF NOT EXISTS idx_portable_media_events_updated_not_deleted
    ON portable_media_events(updated_at) WHERE deleted_at IS NULL;

CREATE TRIGGER IF NOT EXISTS trg__change_log_portable_media_events_insert
AFTER INSERT ON portable_media_events
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('portable_media_events', NEW.event_id, 'INSERT');
END;

CREATE TRIGGER IF NOT EXISTS trg__change_log_portable_media_events_update
AFTER UPDATE ON portable_media_events
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('portable_media_events', NEW.event_id, 'UPDATE');
END;

CREATE TRIGGER IF NOT EXISTS trg__change_log_portable_media_events_delete
AFTER DELETE ON portable_media_events
BEGIN
    INSERT INTO __change_log (table_name, record_id, operation)
    VALUES ('portable_media_events', OLD.event_id, 'DELETE');
END;

-- Events are immutable at the database boundary. Transport metadata
-- (local_version/updated_at) and apply-mode echo suppression may move; every
-- evidence field must be identical.
CREATE TRIGGER IF NOT EXISTS trg__portable_media_events_immutable
BEFORE UPDATE ON portable_media_events
WHEN NEW.event_id IS NOT OLD.event_id
  OR NEW.device_id IS NOT OLD.device_id
  OR NEW.kind IS NOT OLD.kind
  OR NEW.resource_id IS NOT OLD.resource_id
  OR NEW.logical_date IS NOT OLD.logical_date
  OR NEW.position_ms IS NOT OLD.position_ms
  OR NEW.duration_ms IS NOT OLD.duration_ms
  OR NEW.seconds IS NOT OLD.seconds
  OR NEW.observed_at IS NOT OLD.observed_at
  OR NEW.data IS NOT OLD.data
  OR NEW.deleted_at IS NOT OLD.deleted_at
BEGIN
    SELECT RAISE(ABORT, 'portable_media_events are immutable');
END;

CREATE TRIGGER IF NOT EXISTS trg__portable_media_events_no_delete
BEFORE DELETE ON portable_media_events
BEGIN
    SELECT RAISE(ABORT, 'portable_media_events are immutable');
END;
