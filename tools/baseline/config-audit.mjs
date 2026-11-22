import assert from 'node:assert/strict';
import { artifact, main, log, readJson } from './lib.mjs';

export async function run(mode) {
  const api = await import('../../desktop-increment/portable-config.mjs');
  const scope = key => {
    const value = api.classifySetting(key);
    return typeof value === 'string' ? value : value.scope;
  };
  assert.equal(scope('skill.ui.trust_overrides'), 'local');
  assert.equal(scope('skill.ui.enable_overrides'), 'local');
  assert.equal(scope('chat.defaultPermissionPreset'), 'local');
  assert.equal(scope('ds.ui.themeVariant'), 'local');
  assert.equal(scope('plugin.schedule.demo.job'), 'local');
  assert.equal(scope('secret.plugin.demo.note'), 'secret');
  assert.equal(scope('unknown.future.setting'), 'local');
  assert.equal(scope('api_configs'), 'secret');
  const contribution = readJson('config/baseline/contributions.json');
  assert.equal(contribution.settings.length, 573, 'S0.23 consumes all 573 contribution uses');
  const classified = contribution.settings.map(row => {
    const key = typeof row.key === 'string'
      ? row.key : `dynamic:${row.evidence.file}:${row.evidence.line}`;
    assert.ok(row.evidence && row.evidence.source === 'desktop' && row.evidence.file
      && Number.isInteger(row.evidence.line) && row.evidence.line > 0,
    `contribution use ${row.id} has desktop evidence`);
    return { key, scope: scope(key) };
  });
  assert.equal(classified.length, 573);
  assert.ok(classified.every(row => ['portable', 'local', 'secret'].includes(row.scope)));
  const cases = [
    ['model', { id: 'public-model', name: 'Public model', model: 'model-id', apiKey: 'fixture-only-key' }],
    ['model', { id: 'public-model', name: 'Public model', headers: { Authorization: 'Bearer fixture-only-key' } }],
    ['tool', { id: 'public-tool', name: 'Public tool', env: { API_TOKEN: 'fixture-only-key' } }],
    ['tool', { id: 'public-tool', name: 'Public tool', nested: { password: 'fixture-only-key' } }],
    ['model', { id: 'public-model', model: 'public', nested: [{ refresh_token: 'fixture-only-key' }] }],
  ];
  for (const [index, [kind, input]] of cases.entries()) assert.throws(() => api.sanitizePortableConfig(kind, input), `sensitive nested config must reject case ${index}`);
  const sanitized = api.sanitizePortableConfig('tool', { id: 'public-tool', name: 'Public', command: '/local/bin/tool', cwd: '/local/workspace', enabled: true, trust: true });
  const serialized = JSON.stringify(sanitized.portable ?? sanitized);
  assert.ok(!serialized.includes('/local/'));
  assert.ok(!serialized.includes('"enabled"') && !serialized.includes('"trust"'));
  const rows = new Map();
  const settings = { get: key => rows.get(key) ?? null, save: (key, value) => rows.set(key, value) };
  assert.throws(() => api.replayPortableSettings(settings, { version: 1, rows: [
    { key: 'theme', value: 'dark' }, { key: 'api_configs', value: '{"apiKey":"fixture-only"}' },
  ] }));
  assert.equal(rows.size, 0, 'invalid snapshot must fail validation before partial writes');
  for (const key of ['ds.ui.themeVariant', 'plugin.schedule.demo.job', 'secret.plugin.demo.note',
    'skill.ui.trust_overrides', 'skill.ui.enable_overrides']) {
    assert.throws(() => api.replayPortableSettings(settings, {
      version: 1, rows: [{ key: 'theme', value: 'dark' }, { key, value: 'fixture-only' }],
    }), key);
    assert.equal(rows.size, 0, `${key} must not cause partial writes`);
  }
  const counts = Object.fromEntries(['portable', 'local', 'secret'].map(item =>
    [item, classified.filter(row => row.scope === item).length]));
  const result = {
    formatVersion: 1,
    unit: 'S0.23',
    contributionsUses: classified.length,
    everyContributionUseHasEvidence: true,
    scopes: counts,
    localTrustRetained: true,
    localEnableRetained: true,
    localPermissionRetained: true,
    themeVariantLocalPluginDependency: true,
    pluginScheduleLocal: true,
    unknownDefaultsLocal: true,
    secretScope: true,
    pluginSecretScope: true,
    nestedCredentialRejections: cases.length,
    invalidSnapshotNoPartialWrites: true,
    userCredentialsRead: false,
  };
  artifact('config/contracts/config-audit.json', result, mode);
  log('config-audit.accepted', { unit: 'S0.23', mode, ...result });
}

main(import.meta.url, run);
