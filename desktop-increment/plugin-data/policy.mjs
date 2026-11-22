import assert from 'node:assert/strict';
import { canonicalJson, fail } from './portable-json.mjs';

const OWNER = /^(plugin|skill)\.[A-Za-z0-9_.-]{1,121}$/u;
const SECRET_KEY = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/u;
const JOB_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/u;
const TRUST = new Set(['untrusted', 'restricted', 'full']);

function localTrust(initial = {}) {
  const trust = initial.trust ?? (initial.trusted === true ? 'full' : 'untrusted');
  assert.ok(TRUST.has(trust), 'local plugin trust');
  const trusted = initial.trusted === true;
  return {
    installed: initial.installed === true,
    enabled: initial.enabled === true,
    trusted,
    trust: trusted ? trust : 'untrusted',
    fullTrust: trusted && trust === 'full',
  };
}

function scheduleState(input) {
  assert.ok(input !== null && typeof input === 'object' && !Array.isArray(input),
    'plugin schedule state');
  assert.ok(Number.isFinite(input.since), 'plugin schedule since');
  assert.ok(input.last === null || Number.isFinite(input.last), 'plugin schedule last');
  return Object.freeze({ since: input.since, last: input.last });
}

/**
 * New plugin-services state that must never travel inside
 * portable_plugin_records: per-plugin secrets and schedule execution history.
 * Values stay in this local adapter (secure/local settings on a real device);
 * inspect() exposes only key names, never secret values.
 */
export function createLocalPluginServices(ownerId, initial = {}) {
  assert.match(ownerId, OWNER, 'local plugin services owner_id');
  let state = Object.freeze({
    owner_id: ownerId,
    ...localTrust(initial),
    local: true,
    synced: false,
    portableData: false,
  });
  const secrets = new Map();
  const schedules = new Map();
  if (initial.secrets !== undefined) {
    assert.ok(initial.secrets !== null && typeof initial.secrets === 'object'
      && !Array.isArray(initial.secrets), 'initial plugin secrets');
    for (const [key, value] of Object.entries(initial.secrets)) {
      assert.match(key, SECRET_KEY, 'plugin secret key');
      assert.equal(typeof value, 'string', 'plugin secret value');
      secrets.set(key, value);
    }
  }
  if (initial.schedules !== undefined) {
    assert.ok(initial.schedules !== null && typeof initial.schedules === 'object'
      && !Array.isArray(initial.schedules), 'initial plugin schedules');
    for (const [id, record] of Object.entries(initial.schedules)) {
      assert.match(id, JOB_ID, 'plugin schedule id');
      schedules.set(id, scheduleState(record));
    }
  }

  return Object.freeze({
    owner_id: ownerId,
    getSecret(key) {
      assert.match(key, SECRET_KEY, 'plugin secret key');
      return secrets.has(key) ? secrets.get(key) : null;
    },
    setSecret(key, value) {
      assert.match(key, SECRET_KEY, 'plugin secret key');
      assert.equal(typeof value, 'string', 'plugin secret value');
      if (value === '') {
        secrets.delete(key);
      } else {
        secrets.set(key, value);
      }
      return { key, stored: value !== '', local: true, portable: false };
    },
    deleteSecret(key) {
      assert.match(key, SECRET_KEY, 'plugin secret key');
      return secrets.delete(key);
    },
    readSchedule(id) {
      assert.match(id, JOB_ID, 'plugin schedule id');
      return schedules.get(id) ?? null;
    },
    writeSchedule(id, record) {
      assert.match(id, JOB_ID, 'plugin schedule id');
      const value = scheduleState(record);
      schedules.set(id, value);
      return { id, record: { ...value }, local: true, portable: false };
    },
    deleteSchedule(id) {
      assert.match(id, JOB_ID, 'plugin schedule id');
      return schedules.delete(id);
    },
    setLocalTrust(next = {}) {
      const trust = next.trust ?? (next.trusted === true ? 'full'
        : next.trusted === false ? 'untrusted' : state.trust);
      assert.ok(TRUST.has(trust), 'local plugin trust');
      state = Object.freeze({
        owner_id: ownerId,
        ...localTrust({
          installed: next.installed ?? state.installed,
          enabled: next.enabled ?? state.enabled,
          trusted: next.trusted ?? state.trusted,
          trust,
        }),
        local: true,
        synced: false,
        portableData: false,
      });
      return { ...state };
    },
    inspect() {
      return {
        ...state,
        secretKeys: [...secrets.keys()].sort(),
        scheduleKeys: [...schedules.keys()].sort(),
        schedules: [...schedules].map(([id, record]) => ({ id, ...record }))
          .sort((left, right) => left.id.localeCompare(right.id)),
      };
    },
    /** JSON shape for local persistence; intentionally excludes secret values. */
    localSnapshot() {
      return {
        version: 1,
        owner_id: ownerId,
        state: { ...state },
        secretKeys: [...secrets.keys()].sort(),
        schedules: canonicalJson([...schedules].map(([id, record]) => ({ id, ...record }))
          .sort((left, right) => left.id.localeCompare(right.id))),
      };
    },
  });
}

/**
 * Callable local-only install/trust separation adapter. This object is
 * intentionally not a table and is never serialized into
 * portable_plugin_records. Application lifecycle wiring is not claimed here.
 */
export function createLocalPluginPolicy(ownerId, initial = {}) {
  assert.match(ownerId, OWNER, 'local policy owner_id');
  let state = Object.freeze({
    owner_id: ownerId,
    installed: initial.installed === true,
    enabled: initial.enabled === true,
    trusted: initial.trusted === true,
    trust: initial.trust ?? (initial.trusted === true ? 'full' : 'untrusted'),
    local: true,
    synced: false,
  });
  return Object.freeze({
    owner_id: ownerId,
    inspect: () => ({ ...state }),
    setLocalInstall(next = {}) {
      state = Object.freeze({
        ...state,
        installed: next.installed ?? state.installed,
        enabled: next.enabled ?? state.enabled,
        trusted: next.trusted ?? state.trusted,
        trust: next.trust ?? state.trust,
        local: true,
        synced: false,
      });
      return { ...state };
    },
    uninstall({ authorized, deleteData = false } = {}) {
      if (authorized !== true) fail('LOCAL_AUTH_REQUIRED', 'uninstall requires local authorization');
      state = Object.freeze({
        owner_id: ownerId,
        installed: false,
        enabled: false,
        trusted: false,
        trust: 'untrusted',
        local: true,
        synced: false,
      });
      return { installState: { ...state }, deleteData: deleteData === true };
    },
  });
}

export function uninstallLocalPlugin(store, policy, options = {}) {
  assert.ok(store && typeof store.removeAll === 'function', 'plugin data store');
  assert.ok(policy && typeof policy.uninstall === 'function', 'local plugin policy');
  assert.equal(store.owner.owner_id, policy.owner_id, 'store and policy owner must match');
  const outcome = policy.uninstall(options);
  const data = outcome.deleteData ? store.removeAll() : null;
  return {
    installState: outcome.installState,
    retainedSyncedData: !outcome.deleteData,
    dataTombstones: data,
    executes: false,
  };
}
