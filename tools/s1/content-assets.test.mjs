import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { copyContentInputs, inspectContentInputs } from './content-assets.mjs';

test('copies the locked libraries, fonts and bundled dependency licenses', () => {
  const dir = mkdtempSync(join(tmpdir(), 's115-content-'));
  try {
    assert.equal(copyContentInputs(dir), 124);
    assert.match(readFileSync(join(dir, 'content/vendor/licenses/mermaid/LICENSE'), 'utf8'), /MIT License/);
    assert.ok(readFileSync(join(dir, 'content/vendor/mermaid.min.js')).length > 1_000_000);
    assert.match(readFileSync(join(dir, 'content/vendor/licenses/embedded/d3-array@2.12.1/LICENSE'), 'utf8'), /Redistribution and use/);
    assert.equal(inspectContentInputs().fonts.count, 60);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
