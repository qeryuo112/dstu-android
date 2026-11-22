import assert from 'node:assert/strict';
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { root } from './modules.mjs';
export function countExecutedTests(xml) {
  const executed = [...xml.matchAll(/<testcase\b[^>]*\/>|<testcase\b[^>]*>[\s\S]*?<\/testcase>/gu)]
    .filter(match => !/<skipped\b/u.test(match[0])).length;
  assert.ok(executed > 0, 'no executed instrumentation tests (empty/skipped suite is not success)');
  assert.ok(!/<(?:failure|error)\b/u.test(xml), 'failing instrumentation tests');
  return executed;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  for (const module of ['storage', 'network', 'security']) {
    const directory = resolve(root, `platform/${module}/build/outputs/androidTest-results/connected/debug`);
    function collect(dir) {
      return existsSync(dir) ? readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
        const path = resolve(dir, entry.name);
        return entry.isDirectory() ? collect(path) : entry.name.endsWith('.xml') ? [path] : [];
      }) : [];
    }
    const xml = collect(directory).map(path => readFileSync(path, 'utf8')).join('\n');
    const executed = countExecutedTests(xml);
    console.log(JSON.stringify({ event: 'ci.instrumentation.verified', module, executed }));
  }
}
