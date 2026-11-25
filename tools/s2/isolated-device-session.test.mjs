import assert from 'node:assert/strict';
import test from 'node:test';
import { appId, candidateInstallAction, isolatedArguments } from './isolated-device-session.mjs';

const pkg = 'com.android.packageinstaller';
const expected = { packageId: appId, labels: ['Deep Student'] };
const installButton = `<node package="${pkg}" text="继续安装" enabled="true" clickable="true" bounds="[10,20][90,100]"/>`;
const name = `<node package="${pkg}" text="是否要安装「Deep Student」？"/>`;
const xml = (content) => `<hierarchy>${content}</hierarchy>`;

test('confirms only a unique install action in the focused installer naming the pending candidate', () => {
  const action = candidateInstallAction(xml(name + installButton), expected, pkg);
  assert.deepEqual(action, { x: 50, y: 60, button: '继续安装', installer: pkg });
  assert.equal(candidateInstallAction(xml(name + installButton), expected, 'com.example.notinstaller'), null);
  assert.equal(candidateInstallAction(xml(installButton), expected, pkg), null);
  assert.equal(candidateInstallAction(xml(name + installButton + installButton), expected, pkg), null);
  assert.equal(candidateInstallAction(xml(name + installButton.replace('继续安装', '允许')), expected, pkg), null);
  assert.equal(candidateInstallAction(xml(name + installButton.replace('enabled="true"', 'enabled="false"')), expected, pkg), null);
  assert.equal(candidateInstallAction(xml(name + installButton.replace('packageinstaller', 'notinstaller')), expected, pkg), null);
});

test('requires explicit validated device and isolated candidate arguments', () => {
  const args = ['--serial', 'selected-device', '--model', 'V2232A', '--candidate', '/candidate', '--output', '/result'];
  assert.equal(isolatedArguments(args).serial, 'selected-device');
  for (const value of ['unknown', 'emulator']) {
    assert.throws(() => isolatedArguments(args.map(arg => arg === 'V2232A' ? value : arg)));
  }
  assert.throws(() => isolatedArguments(args.slice(2)));
});


test('vivo nested label requires the observed positive-button ancestor and exact APK metadata', () => {
  const name = `<node package="${pkg}" resource-id="${pkg}:id/tv_app_name" text="Deep Student"/>`;
  const label = `<node package="${pkg}" resource-id="${pkg}:id/vbutton_title" text="继续安装" enabled="true" clickable="false"/>`;
  const open = `<node package="${pkg}" resource-id="android:id/button1" enabled="true" clickable="true" bounds="[144,2178][936,2316]">`;
  const inner = `<node package="${pkg}" text="" enabled="true" clickable="true">${label}</node>`;
  const key = xml(`${name}${open}${inner}</node>`);
  assert.deepEqual(candidateInstallAction(key, expected, pkg), { x: 540, y: 2247, button: '继续安装', installer: pkg });
  assert.equal(candidateInstallAction(xml(`${name}${open}</node>${inner}`), expected, pkg), null);
  assert.equal(candidateInstallAction(key.replace('android:id/button1', 'android:id/button2'), expected, pkg), null);
  assert.equal(candidateInstallAction(key.replace('tv_app_name', 'arbitrary_text'), expected, pkg), null);
  assert.equal(candidateInstallAction(key.replace('Deep Student', 'Another applicant'), expected, pkg), null);
});
