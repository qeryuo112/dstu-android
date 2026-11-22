import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { homedir } from 'node:os';
import { resolve } from 'node:path';

const adb = resolve(process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb');
const packages = [
  'com.deepstudent.features.browser.data.test', 'com.deepstudent.features.content.ui.test',
  'com.deepstudent.features.editor.ui.test', 'com.deepstudent.design.test',
  'com.deepstudent.features.browser.ui.test', 'com.deepstudent.app.test',
  'com.deepstudent.engines.pdf.test', 'com.deepstudent.features.notes.ui.test',
  'com.deepstudent.features.content.data.test',
];
for (const name of packages) {
  const result = execFileSync(adb, ['uninstall', name], { encoding: 'utf8' }).trim();
  assert.equal(result, 'Success', name);
}
const remaining = execFileSync(adb, ['shell', 'pm', 'list', 'packages', 'com.deepstudent'], { encoding: 'utf8' }).trim();
assert.equal(remaining, 'package:com.deepstudent.app');
console.log(JSON.stringify({ event: 's1.device.tests.cleaned', uninstalled: packages, applicationRetained: true }));
