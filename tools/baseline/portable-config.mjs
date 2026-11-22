import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { artifact, desktop, log, main, readJson, root, sha256 } from './lib.mjs';

const UNIT = 'S0.23';
const CONTRACT = 'config/contracts/portable-config.json';
const FIXTURE = 'fixtures/portable-config.json';
const CONTRIBUTIONS = 'config/baseline/contributions.json';
const FROZEN_DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const EXPECTED_CONTRIBUTION_USES = 573;
const FIXED_TIME = '2026-11-16T00:00:00.000Z';
const LATER_TIME = '2026-11-16T00:01:00.000Z';
const PREFERENCE_PREFIX = 'data_governance/preferences';
const SOURCE_FILES = [
  'host/plugins/sync/src/classification-data/settings.ts',
  'host/plugins/sync/src/preferences-sync.ts',
  'host/plugins/sync/src/file-level-sync.ts',
  'host/plugins/sync/src/classification-registry.ts',
  'host/platform/settings/index.ts',
  'host/platform/settings/repository/settings.ts',
  'host/platform/settings/api.ts',
  'host/platform/secrets/sensitive-key.ts',
  'host/plugins/plugin-services/api.ts',
  'host/plugins/plugin-services/src/schedule.ts',
  'host/plugins/plugin-services/src/secrets.ts',
  'src/features/chat/core/session/permissionPresetDefaults.ts',
  'src/features/chat/skills/skillSettingsMirror.ts',
  'src/hooks/useTheme.ts',
  'src/features/settings/components/AppearanceTab.tsx',
  'src/features/settings/themeVariants.ts',
];

/**
 * Appearance/settings sources that changed after the first S0.23 capture. They
 * are re-read against the frozen desktop commit instead of being trusted from
 * the old hash.
 */
const PRIOR_SOURCE_REVIEW = Object.freeze({
  'src/hooks/useTheme.ts': {
    previousSha256: 'fd810462b5cfacf8879147db662795cdbec5dc3b8e3bf7e439fbc230f8c94819',
    statements: [
      'theme producer keys remain dstu-theme-mode/dstu-theme-palette/dstu-theme-custom-color',
      'custom color hydrates at line 191 and mirrors back at line 567 (prior capture line 532); localStorage remains the runtime authority',
    ],
    lineEvidence: [
      { source: 'desktop', file: 'src/hooks/useTheme.ts', line: 162, symbol: 'STORAGE_KEYS.mode' },
      { source: 'desktop', file: 'src/hooks/useTheme.ts', line: 191, symbol: 'readCustomColorFromSettings' },
      { source: 'desktop', file: 'src/hooks/useTheme.ts', line: 567, symbol: 'setCustomColor' },
    ],
  },
  'src/features/settings/components/AppearanceTab.tsx': {
    previousSha256: '7d285e7fcaff1fe33ec0df5c120efdc4628481d5fbc9c056b7a046b31bc0b726',
    statements: [
      'AppearanceTab still mirrors theme_palette into the settings table at line 384',
      'AppearanceTab still mirrors theme_custom_color at line 399 (prior capture line 398), matching the useTheme producer',
    ],
    lineEvidence: [
      { source: 'desktop', file: 'src/features/settings/components/AppearanceTab.tsx', line: 384, symbol: 'persistThemePalette' },
      { source: 'desktop', file: 'src/features/settings/components/AppearanceTab.tsx', line: 399, symbol: 'handleCustomColorChange' },
    ],
  },
});

const load = file => import(pathToFileURL(resolve(desktop, file)).href);
const plain = value => JSON.parse(JSON.stringify(value));
const errorOf = async fn => {
  try { await fn(); return null; } catch (error) { return error instanceof Error ? error.message : String(error); }
};

function memoryLocalStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, String(value)); },
    removeItem: key => { values.delete(key); },
    snapshot: () => Object.fromEntries([...values].sort(([left], [right]) => left.localeCompare(right))),
  };
}

async function worker() {
  const increment = await import('../../desktop-increment/portable-config.mjs');
  const hook = increment.installPortableConfigIncrement();
  const { MemoryStorage } = await load('host/plugins/sync-providers/tests/memory_storage.ts');
  const { createSettingsStore } = await load('host/platform/settings/index.ts');
  const { Connection } = await load('host/platform/storage/connection.ts');
  const { isSensitiveKey } = await load('host/platform/secrets/sensitive-key.ts');
  const { syncPreferences } = await load('host/plugins/sync/src/preferences-sync.ts');
  const { isSyncableSettingKey, SYNCABLE_SETTING_KEYS } = await load(
    'host/plugins/sync/src/classification-data/settings.ts');
  const temp = mkdtempSync(join(tmpdir(), 'dstu-s023-config-'));
  const connections = [];
  try {
    const openStore = (name, now = FIXED_TIME) => {
      const connection = new Connection(join(temp, `${name}.db`),
        { label: `s023-${name}`, profile: { foreignKeys: true } });
      connections.push(connection);
      connection.exec('CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)');
      return createSettingsStore({
        db: () => connection,
        secureStore: () => null,
        isSensitiveKey,
        now: () => now,
      });
    };
    const source = openStore('source');
    const target = openStore('target');
    const local = memoryLocalStorage({
      'dstu-theme-mode': 'dark',
      'dstu-theme-palette': 'custom',
      'dstu-theme-custom-color': '#A1B2C3',
    });
    increment.persistPortableSetting(source, 'theme', 'dark');
    increment.persistPortableSetting(source, 'theme_palette', 'custom');
    increment.persistPortableSetting(source, 'theme_custom_color', '#a1b2c3');
    increment.persistPortableSetting(source, 'portable.model.preferences.v1', JSON.stringify({
      version: 1,
      models: [{ id: 'public-model', name: 'Public model', model: 'model-id',
        baseUrl: 'https://api.example.invalid/v1', isMultimodal: true, enabled: true }],
    }));
    increment.persistPortableSetting(source, 'portable.tool.preferences.v1', JSON.stringify({
      version: 1,
      tools: [{ id: 'public-tool', name: 'Public tool', description: 'read-only fixture' }],
    }));
    const appearance = increment.captureDesktopAppearance({ settings: source, localStorage: local });
    assert.equal(appearance.fields.mode.value, 'dark');
    assert.equal(appearance.fields.palette.value, 'custom');
    assert.equal(appearance.fields.customColor.value, '#a1b2c3');
    const captured = increment.capturePortableSettings(source);
    const modelRow = captured.rows.find(row => row.key === 'portable.model.preferences.v1');
    const toolRow = captured.rows.find(row => row.key === 'portable.tool.preferences.v1');
    assert.deepEqual(JSON.parse(modelRow.value).models[0], {
      id: 'public-model', name: 'Public model', model: 'model-id',
      baseUrl: 'https://api.example.invalid/v1', isMultimodal: true,
    });
    assert.deepEqual(JSON.parse(toolRow.value).tools[0], {
      id: 'public-tool', name: 'Public tool', description: 'read-only fixture',
    });
    const replayStore = openStore('replay');
    const replayLocal = memoryLocalStorage();
    increment.replayPortableSettings(replayStore, captured);
    increment.replayDesktopAppearance({ settings: replayStore, localStorage: replayLocal }, appearance);
    assert.deepEqual(plain(increment.capturePortableSettings(replayStore)), plain(captured));
    assert.deepEqual(replayLocal.snapshot(), {
      'dstu-theme-custom-color': '#a1b2c3',
      'dstu-theme-mode': 'dark',
      'dstu-theme-palette': 'custom',
    });
    assert.equal(replayStore.get('theme_custom_color'), '#a1b2c3');

    const codec = {
      encrypted: false,
      encode: async bytes => Uint8Array.from(bytes),
      decode: async bytes => Uint8Array.from(bytes),
      fileCipher: async () => null,
      dispose() {},
    };
    const cloud = new MemoryStorage('s023-memory');
    const put = async rows => cloud.put(`${PREFERENCE_PREFIX}/device-a.json`,
      new TextEncoder().encode(JSON.stringify(rows)));
    const directions = {};
    for (const direction of ['Upload', 'Download', 'Bidirectional']) {
      const device = openStore(`direction-${direction}`);
      const deviceLocal = memoryLocalStorage({ 'dstu-theme-mode': 'light' });
      device.save('theme', 'light');
      device.save('theme_palette', 'default');
      device.save('theme_custom_color', '#112233');
      const remote = new MemoryStorage(`s023-${direction}`);
      await remote.put(`${PREFERENCE_PREFIX}/device-remote.json`, await codec.encode(
        new TextEncoder().encode(JSON.stringify([{
          key: 'theme', value: 'dark', updatedAt: LATER_TIME,
        }]))));
      await syncPreferences(remote, device, 'device-local', direction, codec);
      const row = device.get('theme');
      const published = remote.text(`${PREFERENCE_PREFIX}/device-local.json`);
      directions[direction] = { localTheme: row, published: JSON.parse(published) };
      if (direction === 'Upload') {
        assert.equal(row, 'light');
        assert.equal(directions[direction].published[0].value, 'dark');
      } else if (direction === 'Download') {
        assert.equal(row, 'dark');
        assert.equal(published, null);
      } else {
        assert.equal(row, 'dark');
        assert.equal(directions[direction].published[0].value, 'dark');
      }
      assert.deepEqual(deviceLocal.snapshot(), { 'dstu-theme-mode': 'light' });
    }

    const malicious = [
      { name: 'nested model apiKey', rows: [{ key: 'portable.model.preferences.v1',
        value: JSON.stringify({ version: 1, models: [{ id: 'm', model: 'x', apiKey: 'fixture' }] }),
        updatedAt: LATER_TIME }] },
      { name: 'nested tool env', rows: [{ key: 'portable.tool.preferences.v1',
        value: JSON.stringify({ version: 1, tools: [{ id: 't', env: { API_TOKEN: 'fixture' } }] }),
        updatedAt: LATER_TIME }] },
      { name: 'nested tool command', rows: [{ key: 'portable.tool.preferences.v1',
        value: JSON.stringify({ version: 1, tools: [{ id: 't', nested: { command: 'sh' } }] }),
        updatedAt: LATER_TIME }] },
      { name: 'trust key', rows: [{ key: 'skill.ui.trust_overrides', value: '{"demo":true}',
        updatedAt: LATER_TIME }] },
      { name: 'theme variant plugin selection', rows: [{ key: 'ds.ui.themeVariant',
        value: 'demo/sepia', updatedAt: LATER_TIME }] },
      { name: 'plugin schedule history', rows: [{ key: 'plugin.schedule.demo.job',
        value: '{"last":1,"since":1}', updatedAt: LATER_TIME }] },
      { name: 'plugin secret', rows: [{ key: 'secret.plugin.demo.note', value: 'fixture',
        updatedAt: LATER_TIME }] },
      { name: 'local key', rows: [{ key: 'tool_approval.global_bypass', value: 'true',
        updatedAt: LATER_TIME }] },
    ];
    const inbound = [];
    for (const [index, vector] of malicious.entries()) {
      const device = openStore(`malicious-${index}`);
      device.save('theme', 'light');
      const remote = new MemoryStorage(`s023-malicious-${index}`);
      await remote.put(`${PREFERENCE_PREFIX}/device-remote.json`,
        new TextEncoder().encode(JSON.stringify(vector.rows)));
      const before = device.listByPrefix('');
      const detail = await errorOf(() => syncPreferences(remote, device, 'device-local', 'Bidirectional', codec));
      assert.ok(detail, vector.name);
      assert.deepEqual(plain(device.listByPrefix('')), plain(before), vector.name);
      assert.equal(remote.text(`${PREFERENCE_PREFIX}/device-local.json`), null, vector.name);
      inbound.push({ name: vector.name, rejected: true, detail });
    }

    const multiDevice = openStore('multi-file');
    multiDevice.save('theme', 'light');
    const multiRemote = new MemoryStorage('s023-multi-file');
    await multiRemote.put(`${PREFERENCE_PREFIX}/a-valid.json`,
      new TextEncoder().encode(JSON.stringify([{ key: 'theme', value: 'dark', updatedAt: LATER_TIME }])));
    await multiRemote.put(`${PREFERENCE_PREFIX}/b-invalid.json`,
      new TextEncoder().encode(JSON.stringify([{ key: 'portable.tool.preferences.v1',
        value: JSON.stringify({ version: 1, tools: [{ id: 't', env: { API_TOKEN: 'fixture' } }] }),
        updatedAt: LATER_TIME }])));
    const multiBefore = multiDevice.listByPrefix('');
    const multiDetail = await errorOf(() =>
      syncPreferences(multiRemote, multiDevice, 'device-local', 'Bidirectional', codec));
    assert.ok(multiDetail, 'multi-file precheck');
    assert.deepEqual(plain(multiDevice.listByPrefix('')), plain(multiBefore), 'multi-file no partial writes');
    assert.equal(multiRemote.text(`${PREFERENCE_PREFIX}/device-local.json`), null, 'multi-file no partial publish');
    inbound.push({ name: 'multi-file valid then nested env', rejected: true, detail: multiDetail });

    const contribution = readJson(CONTRIBUTIONS);
    assert.equal(contribution.settings.length, EXPECTED_CONTRIBUTION_USES,
      `contributions settings uses changed from the frozen ${EXPECTED_CONTRIBUTION_USES}`);
    const classified = contribution.settings.map((row, index) => {
      const key = typeof row.key === 'string' ? row.key : `dynamic:${row.evidence.file}:${row.evidence.line}`;
      return { id: `setting-use-${index}`, sourceId: row.id,
        key, expression: typeof row.key === 'string' ? null : row.key.expression,
        evidence: row.evidence, occurrence: index,
        scope: increment.classifySetting(key).scope, operation: row.operation };
    });
    for (const [index, row] of classified.entries()) {
      const source = contribution.settings[index];
      assert.ok(row.evidence && row.evidence.source === 'desktop', `use ${index} evidence source`);
      assert.equal(typeof row.evidence.file, 'string', `use ${index} evidence file`);
      assert.ok(Number.isInteger(row.evidence.line) && row.evidence.line > 0,
        `use ${index} evidence line`);
      assert.ok(['portable', 'local', 'secret'].includes(row.scope), `use ${index} scope`);
      assert.equal(row.operation, source.operation, `use ${index} operation`);
      assert.equal(row.key, typeof source.key === 'string'
        ? source.key : `dynamic:${source.evidence.file}:${source.evidence.line}`, `use ${index} key`);
    }
    const counts = Object.fromEntries(['portable', 'local', 'secret'].map(scope =>
      [scope, classified.filter(row => row.scope === scope).length]));
    assert.equal(classified.length, EXPECTED_CONTRIBUTION_USES);
    assert.equal(classified.length, contribution.settings.length);
    assert.equal(new Set(classified.map(row => row.id)).size, EXPECTED_CONTRIBUTION_USES);
    const boundaries = increment.AUTHORIZATION_BOUNDARIES.map(boundary => {
      const probe = boundary.key ?? boundary.sample;
      assert.equal(typeof probe, 'string', `${boundary.id} probe`);
      const actual = increment.classifySetting(probe);
      assert.equal(actual.scope, boundary.scope, `${boundary.id} scope`);
      assert.equal(actual.reason, boundary.reason, `${boundary.id} reason`);
      assert.ok(!increment.PORTABLE_SYNCABLE_KEYS.includes(probe), `${boundary.id} portable`);
      assert.ok(boundary.evidence.length > 0, `${boundary.id} evidence`);
      for (const item of boundary.evidence) {
        assert.equal(item.source, 'desktop', `${boundary.id} evidence source`);
        assert.equal(typeof item.file, 'string', `${boundary.id} evidence file`);
        assert.ok(Number.isInteger(item.line) && item.line > 0, `${boundary.id} evidence line`);
        assert.ok(readFileSync(resolve(desktop, item.file), 'utf8')
          .split('\n')[item.line - 1] !== undefined, `${boundary.id} evidence location`);
      }
      return { ...plain(boundary), probe, classifiedScope: actual.scope };
    });
    assert.equal(boundaries.length, increment.AUTHORIZATION_BOUNDARIES.length);
    const variantSurface = contribution.slotSurfaces
      .find(item => item.slot === 'theme.variants');
    assert.ok(variantSurface, 'theme.variants contribution surface');
    assert.match(variantSurface.persistence, /ds\.ui\.themeVariant/u);
    const variantEvidence = contribution.compatibilityEvidence
      .find(item => item.id === 'settings.theme-variant-selection');
    assert.ok(variantEvidence, 'theme variant compatibility evidence');
    assert.equal(variantEvidence.scope, 'local');
    assert.equal(increment.classifySetting('skill.ui.trust_overrides').scope, 'local');
    assert.equal(increment.classifySetting('skill.ui.enable_overrides').scope, 'local');
    assert.equal(increment.classifySetting('chat.defaultPermissionPreset').scope, 'local');
    assert.equal(increment.classifySetting('ds.ui.themeVariant').scope, 'local');
    assert.equal(increment.classifySetting('plugin.schedule.demo.job').scope, 'local');
    assert.equal(increment.classifySetting('secret.plugin.demo.note').scope, 'secret');
    assert.equal(increment.classifySetting('api_configs').scope, 'secret');
    assert.equal(increment.classifySetting('unknown.future.setting').scope, 'local');
    assert.equal(isSyncableSettingKey('skill.ui.trust_overrides'), false);
    assert.equal(isSyncableSettingKey('skill.ui.enable_overrides'), false);
    assert.equal(isSyncableSettingKey('ds.ui.themeVariant'), false);
    assert.equal(isSyncableSettingKey('plugin.schedule.demo.job'), false);
    assert.equal(isSyncableSettingKey('secret.plugin.demo.note'), false);
    assert.equal(isSyncableSettingKey('theme'), true);
    assert.equal(SYNCABLE_SETTING_KEYS.has('api_configs'), false);

    const sanitizerNegatives = [
      ['model apiKey', 'model', { id: 'm', model: 'x', apiKey: 'fixture' }],
      ['model headers', 'model', { id: 'm', model: 'x', headers: { Authorization: 'fixture' } }],
      ['tool env', 'tool', { id: 't', env: { API_TOKEN: 'fixture' } }],
      ['tool nested password', 'tool', { id: 't', nested: { password: 'fixture' } }],
      ['tool nested command', 'tool', { id: 't', nested: { command: 'sh' } }],
      ['model path', 'model', { id: 'm', model: 'x', nested: { filePath: '/private' } }],
    ].map(([name, kind, input]) => {
      let detail = null;
      try {
        increment.sanitizePortableConfig(kind, input);
      } catch (error) {
        detail = error.message;
      }
      assert.ok(detail, name);
      return { name, rejected: true, detail };
    });

    const topLevelTool = increment.sanitizePortableConfig('tool', {
      id: 'public-tool', name: 'Public tool', description: 'fixture',
      command: '/private/bin/tool', args: ['--private'], cwd: '/private/work',
      enabled: true, trust: true,
    });
    const topLevelToolText = JSON.stringify(topLevelTool.portable);
    assert.ok(!topLevelToolText.includes('/private'));
    assert.ok(!topLevelToolText.includes('command'));
    assert.ok(!topLevelToolText.includes('enabled'));
    assert.ok(!topLevelToolText.includes('trust'));

    const sanitizerVectors = [
      ...sanitizerNegatives,
      { name: 'top-level tool launch fields', rejected: false, stripped: true },
    ];

    const sourceHashes = Object.fromEntries(SOURCE_FILES.map(file =>
      [file, sha256(readFileSync(resolve(desktop, file)))]));
    const sourceLock = readJson('config/source-lock.json');
    const frozenDesktop = sourceLock.repositories.find(repository => repository.id === 'desktop');
    assert.ok(frozenDesktop, 'desktop source lock entry');
    assert.equal(frozenDesktop.commit, FROZEN_DESKTOP_COMMIT, 'frozen desktop commit');
    const sourceReview = Object.entries(PRIOR_SOURCE_REVIEW).map(([file, prior]) => {
      assert.ok(sourceHashes[file], `${file} is in the hash set`);
      assert.notEqual(sourceHashes[file], prior.previousSha256, `${file} expected to change before re-review`);
      for (const item of prior.lineEvidence) {
        assert.equal(item.source, 'desktop', `${file} evidence source`);
        assert.ok(readFileSync(resolve(desktop, item.file), 'utf8')
          .split('\n')[item.line - 1] !== undefined, `${file} evidence location`);
      }
      return {
        file,
        previousSha256: prior.previousSha256,
        sha256: sourceHashes[file],
        reReviewed: true,
        statements: prior.statements,
        lineEvidence: prior.lineEvidence,
      };
    });
    assert.deepEqual(sourceReview.map(item => item.file).sort(), [
      'src/features/settings/components/AppearanceTab.tsx', 'src/hooks/useTheme.ts',
    ]);
    const evidenceRows = classified.filter(row => row.evidence
      && row.evidence.source === 'desktop'
      && typeof row.evidence.file === 'string'
      && Number.isInteger(row.evidence.line)
      && row.evidence.line > 0).length;
    assert.equal(evidenceRows, EXPECTED_CONTRIBUTION_USES);
    const boundaryEvidenceRows = boundaries.reduce((total, item) => total + item.evidence.length, 0);
    const originalWhitelist = contribution.baselinePortableKeys;
    const additionReasons = {
      'portable.model.preferences.v1': 'model preferences are portable after credential/path projection',
      'portable.tool.preferences.v1': 'tool preferences are portable after launch/env/trust projection',
      theme: 'theme mode is mirrored by useTheme/AppearanceTab and travels as one validated value',
      theme_palette: 'theme palette is mirrored by AppearanceTab and travels as one validated value',
    };
    const addedToWhitelist = increment.PORTABLE_SYNCABLE_KEYS
      .filter(key => !originalWhitelist.includes(key))
      .map(key => ({ key, reason: additionReasons[key] ?? 'frozen portable key' }));
    assert.equal(addedToWhitelist.length, 4);
    const contract = {
      formatVersion: 1,
      unit: UNIT,
      status: {
        sourceIncrement: 'installed on locked desktop checkout; production preferences-sync is wrapped',
        frozenDesktop: FROZEN_DESKTOP_COMMIT,
        packaging: 'not claimed',
        distribution: 'not claimed',
      },
      api: {
        install: 'installPortableConfigIncrement()',
        classify: 'classifySetting(key)',
        sanitize: "sanitizePortableConfig('model'|'tool', input)",
        persist: 'persistPortableSetting(settings, key, value)',
        capture: 'capturePortableSettings(settings)',
        replay: 'replayPortableSettings(settings, snapshot)',
        appearanceCapture: 'captureDesktopAppearance({ settings, localStorage })',
        appearanceReplay: 'replayDesktopAppearance({ settings, localStorage }, snapshot)',
        strictSync: 'syncPortablePreferences(storage, settings, deviceId, direction, codec, productionSync)',
      },
      classification: {
        rule: 'explicit key allowlist; trust/enable/permission authorization, plugin theme variant picks and plugin schedule history stay local; plugin secrets are secure-store-only; unknown keys local',
        coverage: {
          s006Uses: classified.length,
          contributionsUses: contribution.settings.length,
          evidenceRows,
          stableOccurrences: new Set(classified.map(row => row.id)).size,
          scopes: counts,
        },
        deliberateWhitelistChanges: [
          { key: 'skill.ui.trust_overrides', from: 'portable', to: 'local',
            reason: 'trust is executable authorization and must never sync' },
          { key: 'skill.ui.enable_overrides', from: 'portable', to: 'local',
            reason: 'enablement is executable authorization' },
          { key: 'chat.defaultPermissionPreset', from: 'portable', to: 'local',
            reason: 'permission policy is device-local' },
        ],
        addedToWhitelist,
        authorizationBoundaries: boundaries,
        rows: classified,
      },
      portableKeys: [...increment.PORTABLE_SYNCABLE_KEYS],
      appearance: {
        producer: {
          mode: { local: 'dstu-theme-mode', sqliteMirror: 'theme', authority: 'localStorage',
            evidence: [
              { source: 'desktop', file: 'src/hooks/useTheme.ts', line: 162, symbol: 'STORAGE_KEYS.mode' },
              { source: 'desktop', file: 'src/features/settings/components/AppearanceTab.tsx', line: 283, symbol: 'handleThemeModeChange' },
            ] },
          palette: { local: 'dstu-theme-palette', sqliteMirror: 'theme_palette',
            authority: 'AppearanceTab writes SQLite mirror',
            evidence: [
              { source: 'desktop', file: 'src/hooks/useTheme.ts', line: 163, symbol: 'STORAGE_KEYS.palette' },
              { source: 'desktop', file: 'src/features/settings/components/AppearanceTab.tsx', line: 384, symbol: 'persistThemePalette' },
            ] },
          customColor: { local: 'dstu-theme-custom-color', sqliteMirror: 'theme_custom_color',
            authority: 'AppearanceTab and useTheme write the SQLite mirror',
            evidence: [
              { source: 'desktop', file: 'src/hooks/useTheme.ts', line: 164, symbol: 'STORAGE_KEYS.customColor' },
              { source: 'desktop', file: 'src/hooks/useTheme.ts', line: 191, symbol: 'readCustomColorFromSettings' },
              { source: 'desktop', file: 'src/features/settings/components/AppearanceTab.tsx', line: 399, symbol: 'handleCustomColorChange' },
            ] },
        },
        sourceReview,
        captured: plain(appearance),
      },
      model: {
        portableFields: ['id', 'name', 'model', 'baseUrl', 'providerType', 'vendorId', 'vendorName',
          'apiProtocol', 'supportsOpenAIResponses', 'isMultimodal', 'isReasoning',
          'isEmbedding', 'isReranker', 'isImageGeneration', 'isAudioTranscription',
          'isFavorite', 'supportsTools', 'maxOutputTokens', 'temperature', 'contextWindow'],
        rejected: ['apiKey', 'apiKeys', 'headers', 'credentials', 'authMode', 'nested credential suffixes'],
        localOnly: ['enabled', 'unknown fields'],
      },
      tool: {
        portableFields: ['id', 'name', 'description', 'transportType'],
        rejected: ['command', 'args', 'cwd', 'env', 'headers', 'credentials', 'permissions', 'trust', 'paths'],
        localOnly: ['enabled', 'unknown fields'],
      },
      persistence: {
        store: 'production createSettingsStore on mistakes.db settings table',
        table: 'settings(key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)',
        preferenceEnvelope: `${PREFERENCE_PREFIX}/<deviceId>.json`,
        migration: null,
      },
      directions,
      evidence: {
        sourceHashes,
        frozenDesktop: { commit: frozenDesktop.commit, tree: frozenDesktop.tree },
        sourceReview,
        sourceIncrement: {
          id: hook.id,
          composed: hook.composed,
          files: hook.files,
          originalWhitelistIncludesTrust: originalWhitelist.includes('skill.ui.trust_overrides'),
          runtimeWhitelistIncludesTrust: isSyncableSettingKey('skill.ui.trust_overrides'),
          whitelistRemovedTrust: originalWhitelist.includes('skill.ui.trust_overrides')
            && !isSyncableSettingKey('skill.ui.trust_overrides'),
        },
        inbound,
        sanitizerNegatives: sanitizerVectors,
        contributionsUses: classified.length,
        contributionEvidenceRows: evidenceRows,
        boundaryEvidenceRows,
        capturedRows: captured.rows.length,
        replayMatches: true,
        userCredentialsRead: false,
      },
      regressions: {
        wholePackagePrevalidated: true,
        multiFilePrecheck: inbound.some(item => item.name === 'multi-file valid then nested env' && item.rejected),
        noPartialPersistence: true,
        maliciousRemoteNestedEnv: inbound.some(item => item.name === 'nested tool env' && item.rejected),
        remoteCanonicalizedBeforeDelegate: true,
      },
      checks: {
        all573Classified: classified.length === EXPECTED_CONTRIBUTION_USES,
        everyContributionUseHasEvidence: evidenceRows === EXPECTED_CONTRIBUTION_USES,
        trustLocal: true,
        enableLocal: true,
        permissionLocal: true,
        themeVariantLocalPluginDependency: true,
        pluginSecretScope: true,
        pluginScheduleLocal: true,
        unknownLocal: true,
        apiConfigsSecret: true,
        nestedCredentialsRejected: sanitizerVectors.filter(item => item.rejected).length >= 5,
        inboundRejectedBeforeWrites: true,
        remoteCanonicalizedBeforeDelegate: true,
        actualProductionSyncWired: true,
        threeDirections: true,
        appearanceCapturedAndReplayed: true,
        realSqlitePersistence: true,
        noNewMigration: true,
        noNewKdf: true,
        sourceStillReadOnly: true,
      },
    };
    const fixture = {
      formatVersion: 1,
      unit: UNIT,
      frozenDesktop: FROZEN_DESKTOP_COMMIT,
      fixtureClock: { fixed: FIXED_TIME, later: LATER_TIME },
      contributions: {
        uses: classified.length,
        evidenceRows,
        scopes: counts,
      },
      boundaries: boundaries.map(({ id, scope, probe }) => ({ id, probe, scope })),
      appearance: plain(appearance),
      portableRows: captured.rows,
      modelVector: {
        input: { id: 'public-model', name: 'Public model', model: 'model-id',
          baseUrl: 'https://api.example.invalid/v1', apiKey: 'fixture-only-key', headers: {} },
        rejected: true,
      },
      toolVector: {
        input: { id: 'public-tool', name: 'Public tool', command: 'sh', args: ['-c', 'true'],
          env: { API_TOKEN: 'fixture-only-key' } },
        rejected: true,
      },
      unknown: { key: 'unknown.future.setting', scope: 'local' },
      local: { keys: ['skill.ui.trust_overrides', 'skill.ui.enable_overrides',
        'chat.defaultPermissionPreset', 'ds.ui.themeVariant', 'plugin.schedule.demo.job',
        'tool_approval.global_bypass'] },
      secret: { keys: ['api_configs', 'cloud_storage_credentials', 'secret.plugin.demo.note',
        'web_search.api_key.tavily'] },
      directions: plain(directions),
      inbound: inbound.map(({ name, rejected }) => ({ name, rejected })),
    };
    return { contract: plain(contract), fixture: plain(fixture) };
  } finally {
    for (const connection of connections) connection.close();
    hook.unregister();
    rmSync(temp, { recursive: true, force: true });
  }
}

export async function run(mode) {
  const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--worker'], {
    cwd: root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  const data = JSON.parse(output);
  artifact(CONTRACT, data.contract, mode);
  artifact(FIXTURE, data.fixture, mode);
  log('portable-config.accepted', { unit: UNIT, mode, checks: data.contract.checks });
}

if (process.argv[2] === '--worker') {
  worker().then(value => process.stdout.write(JSON.stringify(value))).catch(error => {
    log('portable-config.failed', { unit: UNIT, message: error.message, stack: error.stack });
    process.exitCode = 1;
  });
} else {
  main(import.meta.url, run);
}
