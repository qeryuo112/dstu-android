import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const workspace = resolve(here, '../..');
export const desktopRoot = resolve(workspace, 'dstu-node');

/** Insertion-ordered descriptors. One loader hook serves every installed delta. */
const installed = new Map();
let activeHook = null;

export function sourceFile(relative) {
  return resolve(desktopRoot, relative);
}

export function sourceUrl(relative) {
  return pathToFileURL(sourceFile(relative)).href;
}

export function sourceHash(relative) {
  return createHash('sha256').update(readFileSync(sourceFile(relative))).digest('hex');
}

export function installedIncrementIds() {
  return [...installed.keys()];
}

/**
 * Install one explicit desktop source delta before its modules are imported.
 *
 * A descriptor is:
 *   { unit, id, files: [{ path, sha256, transform(text) => text }] }
 *
 * The desktop checkout is never written. Every descriptor is bound to the
 * original source hash and the loader refuses a second install for the same id.
 */
export function registerIncrement(descriptor) {
  assert.equal(typeof descriptor?.unit, 'string', 'increment unit');
  assert.match(descriptor.unit, /^S\d\.\d\d$/u, 'increment unit id');
  assert.equal(typeof descriptor.id, 'string', 'increment id');
  assert.ok(Array.isArray(descriptor.files) && descriptor.files.length > 0, 'increment files');

  assert.equal(installed.has(descriptor.id), false, `increment already installed: ${descriptor.id}`);

  const files = [];
  for (const file of descriptor.files) {
    assert.equal(typeof file.path, 'string', `${descriptor.id}: source path`);
    assert.match(file.sha256 ?? '', /^[0-9a-f]{64}$/u, `${descriptor.id}: source hash ${file.path}`);
    assert.equal(typeof file.transform, 'function', `${descriptor.id}: source transform ${file.path}`);
    const url = sourceUrl(file.path);
    assert.equal(sourceHash(file.path), file.sha256, `${descriptor.id}: locked source drift ${file.path}`);
    files.push({ path: file.path, url, sha256: file.sha256, transform: file.transform });
  }

  if (activeHook === null) {
    activeHook = registerHooks({
      load(url, context, nextLoad) {
        const selected = [...installed.values()]
          .flatMap(entry => entry.files.filter(file => file.url === url).map(file => ({ entry, file })));
        if (selected.length === 0) return nextLoad(url, context);

        // Read the locked baseline once, then compose every matching delta in install order.
        let source = readFileSync(sourceFile(selected[0].file.path), 'utf8');
        for (const { entry, file } of selected) {
          assert.equal(sourceHash(file.path), file.sha256, `${entry.id}: locked source drift ${file.path}`);
          const transformed = file.transform(source);
          assert.equal(typeof transformed, 'string', `${entry.id}: transform result ${file.path}`);
          assert.notEqual(transformed, source, `${entry.id}: transform did not change ${file.path}`);
          source = transformed;
        }
        return { format: 'module-typescript', source, shortCircuit: true };
      },
    });
  }
  installed.set(descriptor.id, { unit: descriptor.unit, id: descriptor.id, files });

  let active = true;
  return {
    unit: descriptor.unit,
    id: descriptor.id,
    files: files.map(file => ({
      path: file.path,
      originalSha256: file.sha256,
    })),
    unregister() {
      if (!active) return;
      active = false;
      installed.delete(descriptor.id);
      if (installed.size === 0 && activeHook !== null) {
        activeHook.deregister();
        activeHook = null;
      }
    },
  };
}
