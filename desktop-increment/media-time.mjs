import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { cpSync, mkdirSync, mkdtempSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { desktopRoot, registerIncrement } from './loader.mjs';

const REGISTRY = 'host/plugins/sync/src/classification-registry.ts';
const REGISTRY_SHA256 = '34494175370479849f94e80790ae43a30bc68209699902a6d28936ba6a12287a';
const VFS_DEFINITIONS = 'host/platform/storage/migration/definitions/vfs.ts';
const VFS_DEFINITIONS_SHA256 = '714c6bce5313baf493f2705319d46765ae3de51a1e5ece9c1f89d38a68d62d5e';
const TABLE = 'portable_media_events';
const KINDS = new Set(['study', 'resume', 'subtitle']);
const FIELDS = ['event_id', 'device_id', 'kind', 'resource_id', 'logical_date',
  'position_ms', 'duration_ms', 'seconds', 'observed_at', 'data', 'local_version',
  'updated_at', 'deleted_at'];
const MAX_SAFE_DOMAIN = BigInt(Number.MAX_SAFE_INTEGER);

function replaceOnce(text, from, to, label) {
  const first = text.indexOf(from);
  assert.ok(first >= 0, `S0.22: missing source shape ${label}`);
  assert.equal(text.indexOf(from, first + 1), -1, `S0.22: ambiguous source shape ${label}`);
  return `${text.slice(0, first)}${to}${text.slice(first + from.length)}`;
}

export const MEDIA_TIME_INCREMENT_FILES = Object.freeze([
  { path: REGISTRY, sha256: REGISTRY_SHA256 },
  { path: VFS_DEFINITIONS, sha256: VFS_DEFINITIONS_SHA256 },
]);

export function installMediaTimeIncrement() {
  return registerIncrement({
    unit: 'S0.22',
    id: 'portable-media-time-events',
    files: [
      {
        path: REGISTRY,
        sha256: REGISTRY_SHA256,
        transform(text) {
          let output = replaceOnce(text,
            'export function syncClassificationRegistry(): TableClassification[] {',
            'function syncClassificationRegistryS022Base(): TableClassification[] {',
            'classification registry base rename');
          output += `
// S0.22 appends to the composed S0.21 result rather than replacing its map wrapper.
export function syncClassificationRegistry(): TableClassification[] {
  return [...syncClassificationRegistryS022Base(), {
    database: 'vfs', table_name: '${TABLE}', primary_key: 'event_id',
    category: 'RowSync', conflict_policy: 'Lww', business_unique_keys: '',
    has_json_blobs: true,
    merge_notes: 'S0.22: immutable media observations deduplicated by event_id; aggregates are recomputed per device and then globally',
  }];
}
`;
          return output;
        },
      },
      {
        path: VFS_DEFINITIONS,
        sha256: VFS_DEFINITIONS_SHA256,
        transform(text) {
          let output = replaceOnce(text,
            'export const V20261115_REVIEW_PLANS_FSRS: MigrationDefSpec = {',
            `export const V20261116_PORTABLE_MEDIA_PROGRESS: MigrationDefSpec = {
  refineryVersion: 20261116,
  name: 'portable_media_progress',
  file: 'vfs/V20261116__portable_media_progress.sql',
  expectedTables: ['${TABLE}'],
  expectedIndexes: ['idx_portable_media_events_device', 'idx_portable_media_events_resource',
    'idx_portable_media_events_updated_at', 'idx_portable_media_events_device_version',
    'idx_portable_media_events_updated_not_deleted'],
  idempotent: true,
};

export const V20261115_REVIEW_PLANS_FSRS: MigrationDefSpec = {`,
            'media migration definition');
          output = replaceOnce(output,
            '  V20261115_REVIEW_PLANS_FSRS,\n];',
            '  V20261115_REVIEW_PLANS_FSRS,\n  V20261116_PORTABLE_MEDIA_PROGRESS,\n];',
            'media migration list append');
          return output;
        },
      },
    ],
  });
}

export function prepareMediaTimeMigrations(parent = undefined) {
  const directory = parent === undefined
    ? mkdtempSync(join(resolve(import.meta.dirname, '..'), '.s022-migrations-'))
    : resolve(parent);
  mkdirSync(directory, { recursive: true });
  cpSync(resolve(desktopRoot, 'resources/app/migrations'), directory, { recursive: true, force: true });
  for (const file of ['mistakes/V20261006__portable_anki_templates.sql', 'vfs/V20261116__portable_media_progress.sql']) {
    cpSync(resolve(import.meta.dirname, '../config/schema/coordinated', file), join(directory, file));
  }
  return directory;
}

function integer(event, field, minimum, maximum, required) {
  const value = event[field];
  if (value === undefined || value === null) {
    assert.equal(required, false, `media event ${event.event_id}: ${field} is required`);
    return null;
  }
  assert.ok(typeof value === 'bigint' || (typeof value === 'number' && Number.isSafeInteger(value)),
    `media event ${event.event_id}: ${field} must be an integer`);
  const result = BigInt(value);
  assert.ok(result >= minimum && result <= maximum, `media event ${event.event_id}: ${field} out of range`);
  return result;
}

function integerText(value) {
  return value === null ? null : Number(value);
}

function timestamp(value, field) {
  assert.equal(typeof value, 'string', `media event time: ${field} must be ISO-8601 UTC or offset`);
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?(Z|[+-]\d{2}:\d{2})$/u.exec(value);
  assert.ok(match, `media event time: invalid ${field}`);
  const [, year, month, day, hour, minute, second, fraction = '000', zone] = match;
  validDate(`${year}-${month}-${day}`);
  assert.ok(Number(hour) <= 23 && Number(minute) <= 59 && Number(second) <= 59,
    `media event time: invalid ${field}`);
  let offsetMinutes = 0;
  if (zone !== 'Z') {
    const offsetHour = Number(zone.slice(1, 3));
    const offsetMinute = Number(zone.slice(4, 6));
    assert.ok(offsetHour <= 23 && offsetMinute <= 59, `media event time: invalid offset ${field}`);
    offsetMinutes = (offsetHour * 60 + offsetMinute) * (zone[0] === '-' ? -1 : 1);
  }
  const millis = Date.parse(value);
  assert.ok(Number.isFinite(millis), `media event time: invalid ${field}`);
  const localMillis = Date.UTC(Number(year), Number(month) - 1, Number(day),
    Number(hour), Number(minute), Number(second), Number(fraction));
  assert.equal(localMillis - offsetMinutes * 60_000, millis,
    `media event time: normalized invalid ${field}`);
  return new Date(millis).toISOString().replace(/\.000Z$/u, 'Z');
}

function validDate(value) {
  assert.match(value ?? '', /^\d{4}-\d{2}-\d{2}$/u, 'media event: logical_date must be YYYY-MM-DD');
  const parsed = new Date(`${value}T00:00:00Z`);
  assert.ok(Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value,
    `media event: invalid logical_date ${value}`);
  return value;
}

export function normalizeMediaEvent(event) {
  assert.ok(event && typeof event === 'object' && !Array.isArray(event), 'media event must be an object');
  for (const field of ['event_id', 'device_id', 'kind', 'resource_id', 'observed_at']) {
    assert.equal(typeof event[field], 'string', `media event: ${field} must be a string`);
    assert.ok(event[field].length > 0 && event[field].length <= 512, `media event: invalid ${field}`);
  }
  assert.ok(KINDS.has(event.kind), `media event: unknown kind ${event.kind}`);
  const logicalDate = validDate(event.logical_date);
  const observedAt = timestamp(event.observed_at, 'observed_at');
  // Ordering uses the observation instant; the retained string is the canonical
  // UTC representation so Z and +08:00 renderings deduplicate as one payload.
  const seconds = integer(event, 'seconds', 0n, 86400n, event.kind === 'study');
  const position = integer(event, 'position_ms', 0n, MAX_SAFE_DOMAIN, event.kind !== 'study');
  const duration = integer(event, 'duration_ms', 0n, MAX_SAFE_DOMAIN, event.kind !== 'study');
  if (event.kind === 'study') {
    assert.equal(position, null, 'study event cannot carry position_ms');
    assert.equal(duration, null, 'study event cannot carry duration_ms');
  } else {
    assert.equal(seconds, null, `${event.kind} event cannot carry seconds`);
    if (event.kind === 'resume') {
      assert.ok(duration === 0n || position <= duration, 'resume position exceeds duration');
    } else {
      assert.ok(position <= duration, 'subtitle range is inverted');
      assert.equal(event.seconds, undefined, 'subtitle event cannot carry seconds');
    }
  }
  const data = event.data ?? {};
  assert.ok(data && typeof data === 'object' && !Array.isArray(data), 'media event data must be an object');
  const canonicalData = canonicalJson(data);
  const normalized = {
    event_id: event.event_id,
    device_id: event.device_id,
    kind: event.kind,
    resource_id: event.resource_id,
    logical_date: logicalDate,
    position_ms: position,
    duration_ms: duration,
    seconds,
    observed_at: observedAt,
    data: canonicalData,
  };
  if (event.kind === 'subtitle') {
    for (const field of ['plan_id', 'version', 'index', 'text']) assert.ok(Object.hasOwn(data, field), `subtitle event missing data.${field}`);
    assert.ok(typeof data.plan_id === 'string' && data.plan_id.length > 0, 'subtitle plan_id');
    integer({ event_id: event.event_id, version: data.version }, 'version', 1n, BigInt(Number.MAX_SAFE_INTEGER), true);
    integer({ event_id: event.event_id, index: data.index }, 'index', 0n, BigInt(Number.MAX_SAFE_INTEGER), true);
    assert.equal(typeof data.text, 'string', 'subtitle text');
  }
  return normalized;
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key =>
      `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function samePayload(row, event) {
  if (row === undefined) return false;
  return Object.keys(event).every(field => {
    const expected = event[field];
    const actual = row[field];
    if (field === 'data') {
      try {
        return JSON.stringify(JSON.parse(String(actual))) === expected;
      } catch {
        return false;
      }
    }
    return expected === null || expected === undefined
      ? actual === null || actual === undefined
      : String(actual) === String(expected);
  });
}

/**
 * Durably append one local observation. Reusing an event_id with an identical
 * payload is an idempotent no-op; any different payload is rejected.
 */
export function appendMediaEvent(db, input, options = {}) {
  const event = normalizeMediaEvent(input);
  const observed = Date.parse(event.observed_at);
  const now = options.now ?? event.observed_at;
  const updatedAt = timestamp(now, 'updated_at');
  const existing = db.get(`SELECT ${FIELDS.join(', ')} FROM ${TABLE} WHERE event_id = ?1`, [event.event_id]);
  if (existing !== undefined) {
    assert.ok(samePayload(existing, event), `media event_id reused with different payload: ${event.event_id}`);
    return { inserted: false, deduplicated: true, event_id: event.event_id };
  }
  db.transaction(tx => {
    tx.run(`INSERT INTO ${TABLE}
        (event_id, device_id, kind, resource_id, logical_date, position_ms, duration_ms,
         seconds, observed_at, data, local_version, updated_at, deleted_at)
      VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 1, ?11, NULL)`,
    [event.event_id, event.device_id, event.kind, event.resource_id, event.logical_date,
      integerText(event.position_ms), integerText(event.duration_ms), integerText(event.seconds),
      event.observed_at, event.data, updatedAt]);
  });
  assert.ok(Number.isFinite(observed), 'media event observed_at must be valid');
  return { inserted: true, deduplicated: false, event_id: event.event_id };
}

function readEvents(db, resourceId) {
  return db.all(`SELECT ${FIELDS.map(field => ['position_ms', 'duration_ms', 'seconds']
    .includes(field) ? `typeof(${field}) AS ${field}_type, ${field}` : field).join(', ')} FROM ${TABLE}
    WHERE resource_id = ?1 AND deleted_at IS NULL
    ORDER BY logical_date, observed_at, device_id, event_id`, [resourceId]);
}

function utf8Compare(left, right) {
  return Buffer.compare(Buffer.from(String(left), 'utf8'), Buffer.from(String(right), 'utf8'));
}

export function mediaSummary(db, resourceId, logicalDate) {
  validDate(logicalDate);
  const events = readEvents(db, resourceId).filter(row => row.logical_date === logicalDate);
  const byDevice = new Map();
  for (const row of events) {
    const device = byDevice.get(row.device_id) ?? { seconds: 0n, events: 0 };
    if (row.kind === 'study') device.seconds += BigInt(row.seconds ?? 0);
    device.events += 1;
    byDevice.set(row.device_id, device);
  }
  const seconds = Math.min(86400, Number([...byDevice.values()].reduce((sum, row) => sum + row.seconds, 0n)));
  const resumeWinner = events.filter(row => row.kind === 'resume')
    .sort(compareCandidates).at(-1);
  const subtitleSlots = new Map();
  for (const row of events) {
    if (row.kind !== 'subtitle') continue;
    const cue = { ...row, ...JSON.parse(row.data) };
    const slot = `${row.resource_id}\u001f${cue.plan_id}\u001f${cue.version}\u001f${cue.index}`;
    const previous = subtitleSlots.get(slot);
    if (previous === undefined || compareCandidates(cue, previous) > 0) subtitleSlots.set(slot, cue);
  }
  const subtitles = [...subtitleSlots.values()]
    .sort((left, right) => utf8Compare(left.plan_id, right.plan_id)
      || Number(left.version) - Number(right.version)
      || Number(left.index) - Number(right.index));
  const latestPlanVersions = new Map();
  for (const cue of subtitles) {
    latestPlanVersions.set(cue.plan_id,
      Math.max(latestPlanVersions.get(cue.plan_id) ?? 0, Number(cue.version)));
  }
  return {
    resource_id: resourceId,
    logical_date: logicalDate,
    seconds,
    position: resumeWinner === undefined ? null : Number(resumeWinner.position_ms),
    position_ms: resumeWinner === undefined ? null : Number(resumeWinner.position_ms),
    duration_ms: resumeWinner === undefined || resumeWinner.duration_ms === null
      ? null : Number(resumeWinner.duration_ms),
    resume_event_id: resumeWinner?.event_id ?? null,
    resume_observed_at: resumeWinner?.observed_at ?? null,
    by_device: [...byDevice].map(([deviceId, value]) => ({
      device_id: deviceId, seconds: Number(value.seconds), events: value.events,
    })).sort((left, right) => utf8Compare(left.device_id, right.device_id)),
    subtitles: subtitles.map(row => ({
      plan_id: row.plan_id,
      version: Number(row.version),
      index: Number(row.index),
      text: row.text,
      start_ms: Number(row.position_ms),
      end_ms: Number(row.duration_ms),
      event_id: row.event_id,
    })),
    latest_plan_versions: [...latestPlanVersions]
      .sort((left, right) => utf8Compare(left[0], right[0]))
      .map(([planId, version]) => ({ plan_id: planId, version })),
    event_count: events.length,
  };
}

function compareCandidates(left, right) {
  const time = Date.parse(left.observed_at) - Date.parse(right.observed_at);
  if (time !== 0) return time;
  const device = utf8Compare(left.device_id, right.device_id);
  if (device !== 0) return device;
  return utf8Compare(left.event_id, right.event_id);
}

export function migrateLegacyMedia(db, deviceId) {
  assert.equal(typeof deviceId, 'string');
  let migrated = 0;
  for (const row of db.all('SELECT date, seconds, updated_at FROM study_time_daily ORDER BY date')) {
    const eventId = `legacy-study-${deviceId}-${row.date}`;
    if (db.get('SELECT event_id FROM portable_media_events WHERE event_id = ?1', [eventId]) !== undefined) continue;
    const result = appendMediaEvent(db, {
      event_id: eventId, device_id: deviceId, kind: 'study',
      resource_id: 'legacy-daily-total', logical_date: row.date,
      seconds: Math.min(86400, Math.max(0, Number(row.seconds))),
      observed_at: new Date(Number(row.updated_at)).toISOString(),
      data: { legacy_table: 'study_time_daily' },
    });
    if (result.inserted) migrated += 1;
  }
  for (const row of db.all('SELECT resource_id, last_position_ms, duration_ms, watched_ms, updated_at FROM media_progress ORDER BY resource_id')) {
    const observedAt = new Date(Number(row.updated_at)).toISOString();
    const date = observedAt.slice(0, 10);
    const eventId = `legacy-resume-${deviceId}-${row.resource_id}`;
    if (db.get('SELECT event_id FROM portable_media_events WHERE event_id = ?1', [eventId]) !== undefined) continue;
    const result = appendMediaEvent(db, {
      event_id: eventId,
      device_id: deviceId, kind: 'resume', resource_id: String(row.resource_id), logical_date: date,
      position_ms: Math.max(0, Number(row.last_position_ms)),
      duration_ms: Math.max(0, Number(row.duration_ms ?? 0)),
      observed_at: observedAt, data: { legacy_table: 'media_progress', watched_ms: String(row.watched_ms) },
    });
    if (result.inserted) migrated += 1;
  }
  for (const row of db.all(`SELECT resource_id, idx, start_ms, end_ms, text, plan_version
      FROM media_transcript_segments ORDER BY resource_id, plan_version, idx`)) {
    const eventId = `legacy-subtitle-${deviceId}-${row.resource_id}-${row.plan_version}-${row.idx}`;
    if (db.get('SELECT event_id FROM portable_media_events WHERE event_id = ?1', [eventId]) !== undefined) continue;
    const result = appendMediaEvent(db, {
      event_id: eventId,
      device_id: deviceId, kind: 'subtitle', resource_id: String(row.resource_id),
      logical_date: '1970-01-01', position_ms: Number(row.start_ms),
      duration_ms: Number(row.end_ms),
      observed_at: '1970-01-01T00:00:00.000Z',
      data: { legacy_table: 'media_transcript_segments', plan_id: String(row.resource_id),
        version: Number(row.plan_version), index: Number(row.idx), text: String(row.text) },
    });
    if (result.inserted) migrated += 1;
  }
  return migrated;
}

export const registerMediaTimeIncrement = () => installMediaTimeIncrement();
