import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { desktopRoot, installedIncrementIds, registerIncrement } from './loader.mjs';
import { installPortableConfigIncrement } from './portable-config.mjs';
import { createPluginDataStore } from './plugin-data/store.mjs';
import { applyPluginDataBatch, applyPluginDataRecord } from './plugin-data/apply.mjs';
import {
  createLocalPluginPolicy, createLocalPluginServices, uninstallLocalPlugin,
} from './plugin-data/policy.mjs';

export { createPluginDataStore } from './plugin-data/store.mjs';
export { applyPluginDataBatch, applyPluginDataRecord } from './plugin-data/apply.mjs';
export { canonicalJson, PluginDataError } from './plugin-data/portable-json.mjs';
export {
  createLocalPluginPolicy, createLocalPluginServices, uninstallLocalPlugin,
} from './plugin-data/policy.mjs';

const REGISTRY = 'host/plugins/sync/src/classification-registry.ts';
const REGISTRY_SHA256 = '34494175370479849f94e80790ae43a30bc68209699902a6d28936ba6a12287a';
const VFS_DEFINITIONS = 'host/platform/storage/migration/definitions/vfs.ts';
const VFS_DEFINITIONS_SHA256 = '714c6bce5313baf493f2705319d46765ae3de51a1e5ece9c1f89d38a68d62d5e';
const TABLE = 'portable_plugin_records';
const PREVIOUS_PLUGIN_SQL = 'vfs/V20261116__plugin_skill_data_contract.sql';
const PLUGIN_SQL = 'vfs/V20261117__plugin_skill_data_contract.sql';
const PLUGIN_SQL_SHA256 = 'e0c9ba13527bf4ae0d1409dadc890b6af63a4cc86017b8b3b2a051289742ee38';
const MEDIA_SQL = 'vfs/V20261116__portable_media_progress.sql';
const MEDIA_SQL_SHA256 = '1beeb57d996340270b2f8acfe3fe6608a7432fda5c4178aca6034abaad00d116';
const TEMPLATE_SQL = 'mistakes/V20261006__portable_anki_templates.sql';
const TEMPLATE_SQL_SHA256 = '205f7da3f916b65c56b20bdd1472213837dcf359cc25cfe2697a6a3bbf6646d9';

/** Exactly the coordinated SQL copied by preparePluginDataMigrations. */
export const PLUGIN_DATA_MIGRATION_INPUTS = Object.freeze([
  { unit: 'S0.21', file: TEMPLATE_SQL, sha256: TEMPLATE_SQL_SHA256 },
  { unit: 'S0.22', file: MEDIA_SQL, sha256: MEDIA_SQL_SHA256 },
  { unit: 'S0.24', file: PLUGIN_SQL, sha256: PLUGIN_SQL_SHA256 },
]);

const fileHash = path => createHash('sha256').update(readFileSync(path)).digest('hex');

function replaceOnce(text, from, to, label) {
  const first = text.indexOf(from);
  assert.ok(first >= 0, `S0.24: missing source shape ${label}`);
  assert.equal(text.indexOf(from, first + 1), -1, `S0.24: ambiguous source shape ${label}`);
  return `${text.slice(0, first)}${to}${text.slice(first + from.length)}`;
}

export const PLUGIN_DATA_INCREMENT_FILES = Object.freeze([
  { path: REGISTRY, sha256: REGISTRY_SHA256 },
  { path: VFS_DEFINITIONS, sha256: VFS_DEFINITIONS_SHA256 },
]);

export const PLUGIN_DATA_TABLE = TABLE;

export function installPluginDataIncrement() {
  const owned = [];
  if (!installedIncrementIds().includes('portable-theme-model-tool-configuration')) {
    owned.push(installPortableConfigIncrement());
  }
  assert.ok(installedIncrementIds().includes('portable-media-time-events'),
    'S0.24 must compose after the S0.22 media increment');
  const handle = registerIncrement({
    unit: 'S0.24',
    id: 'portable-plugin-skill-data',
    files: [
      {
        path: REGISTRY,
        sha256: REGISTRY_SHA256,
        transform(text) {
          let output = replaceOnce(text,
            'export function syncClassificationRegistry(): TableClassification[] {',
            'function syncClassificationRegistryS024Base(): TableClassification[] {',
            'classification registry base rename');
          output += `
// S0.24 appends after the S0.21/S0.22/S0.23 registry chain.
export function syncClassificationRegistry(): TableClassification[] {
  return [...syncClassificationRegistryS024Base(), {
    database: 'vfs', table_name: '${TABLE}', primary_key: 'id',
    category: 'RowSync', conflict_policy: 'Lww', business_unique_keys: '',
    has_json_blobs: true,
    merge_notes: 'S0.24: portable plugin/skill data by canonical [owner,namespace,recordId] id; row LWW; install and trust stay local',
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
            'export const V20261111_STUDY_SPACE_PROFILES_AND_MOUNTS: MigrationDefSpec = {',
            `export const V20261117_PLUGIN_SKILL_DATA_CONTRACT: MigrationDefSpec = {
  refineryVersion: 20261117,
  name: 'plugin_skill_data_contract',
  file: 'vfs/V20261117__plugin_skill_data_contract.sql',
  expectedTables: ['${TABLE}'],
  expectedColumns: [
    ['${TABLE}', 'owner_id'], ['${TABLE}', 'package_version'],
    ['${TABLE}', 'source_hash'], ['${TABLE}', 'schema_version'],
    ['${TABLE}', 'min_writer_schema'], ['${TABLE}', 'data_json'],
    ['${TABLE}', 'unknown_json'], ['${TABLE}', 'deleted_at'],
    ['${TABLE}', 'device_id'], ['${TABLE}', 'local_version'],
    ['${TABLE}', 'updated_at'],
  ],
  expectedIndexes: ['idx_portable_plugin_records_owner',
    'idx_portable_plugin_records_namespace',
    'idx_portable_plugin_records_updated_at',
    'idx_portable_plugin_records_device_version'],
  idempotent: true,
};

export const V20261111_STUDY_SPACE_PROFILES_AND_MOUNTS: MigrationDefSpec = {`,
            'plugin data migration definition');
          // S0.22 installs first and appends V20261116 to this exact list.
          const listEnd = '  V20261116_PORTABLE_MEDIA_PROGRESS,\n];\n\nexport const VFS_SCHEMA_VERSION';
          output = replaceOnce(output,
            listEnd,
            '  V20261116_PORTABLE_MEDIA_PROGRESS,\n  V20261117_PLUGIN_SKILL_DATA_CONTRACT,\n];\n\nexport const VFS_SCHEMA_VERSION',
            'plugin data migration list append');
          return output;
        },
      },
    ],
  });
  return {
    ...handle,
    composed: ['S0.21', 'S0.22', 'S0.23', 'S0.24'],
    unregister() {
      handle.unregister();
      for (const prior of owned.reverse()) prior.unregister();
    },
  };
}

export const registerPluginDataIncrement = () => installPluginDataIncrement();

export function preparePluginDataMigrations(parent = undefined) {
  const directory = parent === undefined
    ? mkdtempSync(join(resolve(import.meta.dirname, '..'), '.s024-migrations-'))
    : resolve(parent);
  mkdirSync(directory, { recursive: true });
  cpSync(resolve(desktopRoot, 'resources/app/migrations'), directory, { recursive: true, force: true });
  for (const input of PLUGIN_DATA_MIGRATION_INPUTS) {
    const source = resolve(import.meta.dirname, '../config/schema/coordinated', input.file);
    assert.equal(existsSync(source), true, `S0.24: coordinated SQL missing ${input.file}`);
    assert.equal(fileHash(source), input.sha256, `S0.24: coordinated SQL drift ${input.file}`);
    const target = join(directory, input.file);
    mkdirSync(dirname(target), { recursive: true });
    cpSync(source, target);
    assert.equal(fileHash(target), input.sha256, `S0.24: prepared SQL bytes changed ${input.file}`);
  }
  assert.equal(existsSync(join(directory, PREVIOUS_PLUGIN_SQL)), false,
    'S0.24: stale V20261116 plugin SQL must not be prepared');
  return directory;
}

/**
 * Content-ingestion trust marker. Remote bytes never become installed/trusted;
 * installation is a separate callable local policy adapter and is intentionally
 * not stored in portable_plugin_records. This does not claim app lifecycle
 * wiring.
 */
export function inspectPluginDataContent(content, policy) {
  assert.ok(content !== null && typeof content === 'object' && !Array.isArray(content),
    'plugin data content');
  assert.ok(policy !== null && typeof policy === 'object' && !Array.isArray(policy),
    'local trust policy');
  const local = typeof policy.inspect === 'function' ? policy.inspect() : policy;
  const trusted = local.trusted === true;
  const trust = trusted ? local.trust ?? 'full' : 'untrusted';
  return {
    content: {
      owner_id: content.owner_id,
      package_version: content.package_version,
      source_hash: content.source_hash,
      schema_version: content.schema_version,
    },
    install: {
      installed: local.installed === true,
      enabled: local.enabled === true,
      trusted,
      trust,
      fullTrust: trusted && trust === 'full',
      localPolicy: true,
      synced: false,
    },
    executes: false,
  };
}

export function createPluginDataPolicy(ownerId, initial = {}) {
  return createLocalPluginPolicy(ownerId, initial);
}

export function uninstallPluginData(store, policy, options = {}) {
  return uninstallLocalPlugin(store, policy, options);
}
