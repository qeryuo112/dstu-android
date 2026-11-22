import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { registerIncrement } from './loader.mjs';
import { desktopRoot } from './loader.mjs';

const REGISTRY = 'host/plugins/sync/src/classification-registry.ts';
const ASSETS = 'host/plugins/sync/src/asset-filenames.ts';
const PORTABLE = 'host/plugins/sync/src/portable-assets.ts';
const ASSET_PATHS = 'host/plugins/sync/src/asset-paths.ts';
const MISTAKES_DEFINITIONS = 'host/platform/storage/migration/definitions/mistakes.ts';

const REGISTRY_SHA256 = '34494175370479849f94e80790ae43a30bc68209699902a6d28936ba6a12287a';
const ASSETS_SHA256 = '7d4af08386fc9e4e1b8fee2b82dfcb12e9bda754ce98b9314e3f3dcd80f63f9f';
const PORTABLE_SHA256 = '414949e6b4d54abe3a3e24d0370d512ac1a4dd30ce0ae428b5f575cfabfe8ddb';
const ASSET_PATHS_SHA256 = '88bd9cc12f2727955d89d3bf164c210ec07814177114180739ef561af003896f';
const MISTAKES_DEFINITIONS_SHA256 = '3671731fd47c10c59348eac17043cc3aa3f60e66c4fee2c294b6e737e3c4b6ed';

const TEMPLATE_MIGRATION = 'mistakes/V20261006__portable_anki_templates.sql';
const TEMPLATE_MIGRATION_SHA256 = '205f7da3f916b65c56b20bdd1472213837dcf359cc25cfe2697a6a3bbf6646d9';
const UPSTREAM_VFS_MIGRATION = 'vfs/V20261115__review_plans_fsrs.sql';
const UPSTREAM_VFS_MIGRATION_SHA256 = '483b242fbf76ded1755644dbb0c6ad9391a91f73af1b32f5d7407a2614faa7d1';

const fileHash = path => createHash('sha256').update(readFileSync(path)).digest('hex');

function replaceOnce(text, from, to, label) {
  const first = text.indexOf(from);
  assert.ok(first >= 0, `S0.21: missing source shape ${label}`);
  assert.equal(text.indexOf(from, first + 1), -1, `S0.21: ambiguous source shape ${label}`);
  return `${text.slice(0, first)}${to}${text.slice(first + from.length)}`;
}

export const TEMPLATE_ASSET_INCREMENT_FILES = Object.freeze([
  { path: REGISTRY, sha256: REGISTRY_SHA256 },
  { path: ASSETS, sha256: ASSETS_SHA256 },
  { path: PORTABLE, sha256: PORTABLE_SHA256 },
  { path: ASSET_PATHS, sha256: ASSET_PATHS_SHA256 },
  { path: MISTAKES_DEFINITIONS, sha256: MISTAKES_DEFINITIONS_SHA256 },
]);

/** Source anchors re-read by the baseline tool so line drift is reviewed, not assumed. */
export const TEMPLATE_ASSET_SOURCE_ANCHORS = Object.freeze([
  {
    path: REGISTRY,
    marker: '  return [...vfsCore, ...vfsNotes, ...chatV2, ...mistakes, ...llmUsage];',
    symbol: 'classification registry return',
  },
  {
    path: ASSETS,
    marker: "  ['active', ['images', 'notes_assets', 'documents', 'subjects', 'textbooks', 'audio', 'videos']],",
    symbol: 'active asset roots',
  },
  {
    path: PORTABLE,
    marker: 'export function isPortableAssetEnabled(key: string, options: PortableAssetOptions = {}): boolean {',
    symbol: 'portable asset gate',
  },
  {
    path: ASSET_PATHS,
    marker: '    let meta;\n    try { meta = fs.statBigSync(rawPath); } catch { continue; }',
    symbol: 'asset scan symlink guard',
  },
  {
    path: ASSET_PATHS,
    marker: '  return join(base, top, rel);\n}',
    symbol: 'asset materialization symlink guard',
  },
  {
    path: MISTAKES_DEFINITIONS,
    marker: 'export const V20260720_FSRS_MASTERY_OUTBOX: MigrationDefSpec = {',
    symbol: 'portable template migration definition',
  },
  {
    path: MISTAKES_DEFINITIONS,
    marker: '  V20261005_FSRS_LEARNING_STEP,\n];',
    symbol: 'portable template migration list',
  },
]);

/** Exact migration inputs copied by prepareTemplateMigrations. */
export const TEMPLATE_ASSET_MIGRATION_INPUTS = Object.freeze({
  upstream: { file: UPSTREAM_VFS_MIGRATION, sha256: UPSTREAM_VFS_MIGRATION_SHA256 },
  template: { file: TEMPLATE_MIGRATION, sha256: TEMPLATE_MIGRATION_SHA256 },
});

export function installTemplateAssetIncrement() {
  return registerIncrement({
    unit: 'S0.21',
    id: 'portable-templates-and-persistent-assets',
    files: [
      {
        path: REGISTRY,
        sha256: REGISTRY_SHA256,
        transform(text) {
          const marker = '  return [...vfsCore, ...vfsNotes, ...chatV2, ...mistakes, ...llmUsage];';
          const replacement = `  // S0.21: only user-created templates become portable. Bundled templates are
  // code-shipped defaults and their names are seeded locally by the Anki plugin.
  return [...vfsCore, ...vfsNotes, ...chatV2, ...mistakes.map(entry => entry.table_name === 'custom_anki_templates'
    ? { ...entry, category: 'RowSync', conflict_policy: 'Lww',
        merge_notes: 'S0.21: portable user templates (is_built_in=0); row-level LWW on the template id, with name as a business key' }
    : entry), ...llmUsage];`;
          return replaceOnce(text, marker, replacement, 'classification registry return');
        },
      },
      {
        path: ASSETS,
        sha256: ASSETS_SHA256,
        transform(text) {
          return replaceOnce(text,
            "  ['active', ['images', 'notes_assets', 'documents', 'subjects', 'textbooks', 'audio', 'videos']],",
            "  ['active', ['images', 'notes_assets', 'documents', 'subjects', 'textbooks', 'audio', 'videos', 'anki_media', 'chat_attachments']],",
            'active asset roots');
        },
      },
      {
        path: PORTABLE,
        sha256: PORTABLE_SHA256,
        transform(text) {
          const marker = "export function isPortableAssetEnabled(key: string, options: PortableAssetOptions = {}): boolean {";
          const replacement = `${marker}
  // S0.21: Anki media and promoted chat attachments are persistent portable assets.
  if (key.startsWith('active/anki_media/') || key.startsWith('active/chat_attachments/')) return true;`;
          return replaceOnce(text, marker, replacement, 'portable asset gate');
        },
      },
      {
        path: ASSET_PATHS,
        sha256: ASSET_PATHS_SHA256,
        transform(text) {
          let output = replaceOnce(text,
            "    let meta;\n    try { meta = fs.statBigSync(rawPath); } catch { continue; }",
            "    // S0.21: reject linked entries before following them into a file or directory.\n    if (fs.lstatSync(rawPath).isSymbolicLink()) continue;\n    let meta;\n    try { meta = fs.statBigSync(rawPath); } catch { continue; }",
            'asset scan symlink guard before stat');
          output = replaceOnce(output,
            "  return join(base, top, rel);\n}",
            "  const target = join(base, top, rel);\n  // S0.21: the final materialization target must not be a symbolic link.\n  return symlink(target) ? null : target;\n}",
            'asset materialization symlink guard');
          return output;
        },
      },
      {
        path: MISTAKES_DEFINITIONS,
        sha256: MISTAKES_DEFINITIONS_SHA256,
        transform(text) {
          let output = replaceOnce(text,
            "export const V20260720_FSRS_MASTERY_OUTBOX: MigrationDefSpec = {",
            `export const V20261006_PORTABLE_ANKI_TEMPLATES: MigrationDefSpec = {
  refineryVersion: 20261006,
  name: 'portable_anki_templates',
  file: 'mistakes/V20261006__portable_anki_templates.sql',
  expectedColumns: [
    ['custom_anki_templates', 'device_id'],
    ['custom_anki_templates', 'local_version'],
    ['custom_anki_templates', 'deleted_at'],
  ],
  expectedIndexes: [
    'idx_custom_anki_templates_local_version',
    'idx_custom_anki_templates_deleted_at',
    'idx_custom_anki_templates_device_id',
    'idx_custom_anki_templates_updated_at',
    'idx_custom_anki_templates_device_version',
    'idx_custom_anki_templates_updated_not_deleted',
  ],
  idempotent: true,
};

export const V20260720_FSRS_MASTERY_OUTBOX: MigrationDefSpec = {`,
            'portable template migration definition');
          output = replaceOnce(output,
            "  V20261005_FSRS_LEARNING_STEP,\n];",
            "  V20261005_FSRS_LEARNING_STEP,\n  V20261006_PORTABLE_ANKI_TEMPLATES,\n];",
            'portable template migration list');
          return output;
        },
      },
    ],
  });
}

export const registerTemplateAssetIncrement = () => installTemplateAssetIncrement();

/**
 * Build a complete migration directory for a production migration coordinator.
 * Historical SQL remains in the locked desktop checkout; the coordinated SQL is
 * copied from this project. The caller owns the returned temporary directory.
 */
export function prepareTemplateMigrations(parent = undefined) {
  const directory = parent === undefined
    ? mkdtempSync(join(resolve(import.meta.dirname, '..'), '.s021-migrations-'))
    : resolve(parent);
  mkdirSync(directory, { recursive: true });
  cpSync(resolve(desktopRoot, 'resources/app/migrations'), directory, { recursive: true, force: true });
  const upstream = join(directory, UPSTREAM_VFS_MIGRATION);
  assert.equal(existsSync(upstream), true, 'S0.21: upstream vfs migration missing');
  assert.equal(fileHash(upstream), UPSTREAM_VFS_MIGRATION_SHA256, 'S0.21: upstream vfs migration drift');
  const template = resolve(import.meta.dirname, '../config/schema/coordinated', TEMPLATE_MIGRATION);
  assert.equal(fileHash(template), TEMPLATE_MIGRATION_SHA256, 'S0.21: coordinated template SQL drift');
  cpSync(template, join(directory, TEMPLATE_MIGRATION));
  assert.equal(fileHash(join(directory, TEMPLATE_MIGRATION)), TEMPLATE_MIGRATION_SHA256,
    'S0.21: prepared template SQL bytes changed');
  return directory;
}
