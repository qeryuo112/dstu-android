import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { artifact, main, parse, ts, visit, value, prop, location, sourceFiles, desktop, readDesktop, sha256, log, evidence } from './lib.mjs';

const EXPECTED = Object.freeze({
  sections: 17,
  slotDeclarations: 26,
  slotTypes: 28,
  contributions: 24,
  controls: 156,
  settingUses: 573,
});
const MOBILE_ONLY_SETTING_FILES = new Set([
  'src/features/settings/components/AndroidFeatureFlagsSection.tsx',
]);
const SLOT_OWNERS = {
  'pdf.selectionToolbar': 'dstu.ui.learning-hub',
  'chat.composer.commands': 'dstu.ui.chat',
  'chat.message.actions': 'dstu.ui.chat',
  'review.cardActions': 'dstu.ui.anki',
  'statusbar.items': 'dstu.ui.workbench',
  'desktop.widgets': 'dstu.ui.workbench',
  'theme.variants': 'dstu.ui.settings',
  'editor.extensions': 'dstu.ui.editor',
  'editor.slashMenu': 'dstu.ui.editor',
  'editor.selectionToolbar': 'dstu.ui.editor',
  'editor.blockMenu': 'dstu.ui.editor',
};

function scope(key, syncable, sensitivePatterns) {
  if (key.endsWith('.api_key') || key.endsWith('.apiKey') || sensitivePatterns.some(pattern => key.startsWith(pattern))) return 'secret';
  return syncable.has(key) ? 'portable' : 'local';
}
function sectionFor(file, key) {
  const text = `${file} ${key}`.toLowerCase();
  if (/appearance|theme|font|zoom|sidebar/.test(text)) return 'appearance';
  if (/mcp/.test(text)) return 'mcp';
  if (/voice|speech|tts|asr/.test(text)) return 'voice-input';
  if (/automation|subagent|superagent/.test(text)) return 'automation';
  if (/backup|sync|governance/.test(text)) return 'data-governance';
  if (/memory/.test(text)) return 'memory';
  if (/pdf|ocr|document|media.cache/.test(text)) return 'document-processing';
  if (/shortcut/.test(text)) return 'shortcuts';
  if (/workbench|desktop\./.test(text)) return 'workbench';
  if (/model|vendor|provider|api|web_search/.test(text)) return 'apis';
  return 'general';
}
export function collect() {
  const syncable = new Set([...readDesktop('host/plugins/sync/src/classification-data/settings.ts').matchAll(/'([^']+)'/g)].map(m => m[1]));
  const sensitivePatterns = [];
  visit(parse('host/platform/secrets/sensitive-key.ts'), node => { if (ts.isVariableDeclaration(node) && node.name.getText() === 'PATTERNS') sensitivePatterns.push(...value(node.initializer)); });
  const sectionFile = 'src/ui-plugins/official/settings/index.ts';
  const sf = parse(sectionFile);
  const sections = [];
  visit(sf, node => {
    if (!ts.isObjectLiteralExpression(node) || !prop(node, 'id') || !prop(node, 'titleKey') || !prop(node, 'group')) return;
    const item = value(node);
    sections.push({ ...item, evidence: location(sectionFile, sf, node), android: { route: `settings/${item.id}`, module: 'features/system', deliveryUnit: 'S7.35', visibility: 'release-and-debug; native equivalent for every desktop setting section' }, persistence: 'DataStore local preferences / shared repository portable values / Keystore secret values', states: ['loading', 'ready', 'editing', 'saving', 'saved', 'error'], acceptance: { id: `SETTINGS-SECTION-${item.id}`, normal: 'open from settings navigation/search; persist edit and observe effect after restart', exceptional: 'save failure keeps draft; back preserves or asks about pending changes' } });
  });
  assert.equal(sections.length, EXPECTED.sections);
  sections.push({ id: 'plugin-manager', group: 'integrations', titleKey: 'plugin_manager:title', order: 45, evidence: { source: 'desktop', file: 'src/features/plugin-manager/mount.tsx', line: 55 }, android: { route: 'settings/plugin-manager', module: 'features/system', deliveryUnit: 'S6.35', visibility: 'release-and-debug' }, persistence: 'plugin registry/package data and local trust ledger', states: ['list', 'installing', 'consent', 'enabled', 'disabled', 'error'], acceptance: { id: 'SETTINGS-SECTION-plugin-manager', normal: 'plugin list/configure/consent/update/uninstall', exceptional: 'revocation stops execution and keeps portable data classification' } });
  const allSrc = sourceFiles(desktop, 'src').filter(f => /\.(ts|tsx)$/.test(f) && !/(?:__tests__|\/tests\/|\.test\.|\.spec\.|__golden__|\/demo\/)/.test(f));
  const settingUses = [], uiControls = [], contributions = [], slotDeclarations = [], slotTypes = [];
  const sectionRoots = {
    apis: ['ApisTab', 'VendorDetailPanel'], search: ['ExternalSearchTab'], models: ['ModelsTab'], plugins: ['plugins/PluginsTab'], mcp: ['McpToolsSection'],
    automation: ['AutomationSettingsSection', 'SubagentProfilesSection', 'SuperAgentSettingsSection'], 'data-governance': ['DataGovernanceDashboard'], general: ['GeneralTab'], appearance: ['AppearanceTab'],
    params: ['ParamsTab'], 'voice-input': ['VoiceInputTab'], memory: ['MemoryTab'], workbench: ['WorkbenchTab'], 'document-processing': ['DocumentProcessingTab'], about: ['AboutTab'],
  };
  const sectionsByFile = new Map();
  const importGraph = new Map();
  const resolveImport = (file, specifier) => {
    if (!specifier.startsWith('.') && !specifier.startsWith('@/')) return null;
    const path = specifier.startsWith('@/') ? specifier.replace('@/', 'src/') : `${dirname(file)}/${specifier}`;
    for (const suffix of ['', '.ts', '.tsx', '/index.ts', '/index.tsx']) {
      const absolute = resolve(desktop, path + suffix);
      const relative = absolute.slice(desktop.length + 1);
      if (allSrc.includes(relative) && existsSync(absolute)) return relative;
    }
    return null;
  };
  for (const file of allSrc.filter(file => file.startsWith('src/features/settings/') || file.startsWith('src/components/settings/'))) {
    const dependencies = [];
    visit(parse(file), node => {
      if (ts.isImportDeclaration(node) && ts.isStringLiteralLike(node.moduleSpecifier)) {
        const target = resolveImport(file, node.moduleSpecifier.text);
        if (target !== null && (target.startsWith('src/features/settings/') || target.startsWith('src/components/settings/'))) dependencies.push(target);
      }
    });
    importGraph.set(file, dependencies);
  }
  const associate = (file, section, seen) => {
    if (seen.has(file)) return;
    seen.add(file);
    const owners = sectionsByFile.get(file) ?? new Set(); owners.add(section); sectionsByFile.set(file, owners);
    for (const child of importGraph.get(file) ?? []) associate(child, section, seen);
  };
  for (const [section, roots] of Object.entries(sectionRoots)) for (const name of roots) associate(`src/features/settings/components/${name}.tsx`, section, new Set());
  const sectionAssignment = (file, key) => {
    const owners = [...sectionsByFile.get(file) ?? []].sort();
    return { section: owners.length === 1 ? owners[0] : sectionFor(file, key), ownerSections: owners, assignmentBasis: owners.length === 1 ? 'settings component import ownership' : 'shared component/persistence key family' };
  };
  const constantsByFile = new Map();
  const importsByFile = new Map();
  const resolveConstant = (file, name, seen = new Set()) => {
    const reference = `${file}:${name}`;
    if (seen.has(reference)) return undefined;
    seen.add(reference);
    if (!constantsByFile.has(file)) {
      const constants = new Map(), imports = new Map();
      visit(parse(file), node => {
        if (ts.isVariableDeclaration(node) && node.initializer) {
          const v = value(node.initializer);
          if (typeof v === 'string') constants.set(node.name.getText(), v);
        }
        if (ts.isImportDeclaration(node) && node.importClause?.namedBindings && ts.isNamedImports(node.importClause.namedBindings)) {
          const target = resolveImport(file, node.moduleSpecifier.text);
          if (target !== null) for (const binding of node.importClause.namedBindings.elements) imports.set(binding.name.text, { file: target, name: binding.propertyName?.text ?? binding.name.text });
        }
      });
      constantsByFile.set(file, constants); importsByFile.set(file, imports);
    }
    if (constantsByFile.get(file).has(name)) return constantsByFile.get(file).get(name);
    const imported = importsByFile.get(file).get(name);
    return imported === undefined ? undefined : resolveConstant(imported.file, imported.name, seen);
  };
  const parsed = new Map();
  for (const file of allSrc) {
    const text = readDesktop(file);
    if (!/getSetting|saveSetting|localStorage|SettingRow|SwitchRow|SettingsSlider|\.contribute\(|\.announce\(|SlotMap|cardinality:/.test(text)) continue;
    const source = parse(file); parsed.set(file, source);
  }
  const declaredBy = new Map();
  const ownerOf = new Map(Object.entries(SLOT_OWNERS));
  const slotNameIn = (file, declaration) => {
    const name = declaration.name;
    if (typeof name === 'string') return name;
    if (!name?.expression) return undefined;
    const visited = new Set();
    let current = file;
    let symbol = name.expression;
    while (typeof symbol === 'string' && !visited.has(`${current}:${symbol}`)) {
      visited.add(`${current}:${symbol}`);
      let next = null;
      if (!existsSync(resolve(desktop, current))) return undefined;
      visit(parse(current), node => {
        if (ts.isImportDeclaration(node) && node.importClause?.namedBindings && ts.isNamedImports(node.importClause.namedBindings)) {
          const binding = node.importClause.namedBindings.elements.find(item => item.name.text === symbol);
          if (!binding) return;
          const specifier = node.moduleSpecifier.text;
          const target = specifier.startsWith('@/')
            ? `src/${specifier.slice(2)}.ts`
            : specifier.startsWith('.') ? `${dirname(current)}/${specifier}` : null;
          if (target) next = { file: target, symbol: binding.propertyName?.text ?? binding.name.text };
        }
        if (ts.isVariableDeclaration(node) && node.name.getText() === symbol && node.initializer) {
          const literal = value(node.initializer);
          if (typeof literal === 'string') next = literal;
        }
      });
      if (next === null) return undefined;
      if (typeof next === 'string') return next;
      current = next.file;
      symbol = next.symbol;
    }
    return undefined;
  };
  for (const [file, source] of parsed) {
    visit(source, node => {
      if (ts.isCallExpression(node)) {
        const name = node.expression.getText(source);
        if (/(?:^|\.)(?:getSetting|saveSetting|deleteSetting|readWorkbenchSetting|writeWorkbenchSetting)$|localStorage\.(?:getItem|setItem|removeItem)$/.test(name) && node.arguments.length > 0) {
          const arg = value(node.arguments[0]);
          const key = typeof arg === 'string' ? arg : resolveConstant(file, arg.expression);
          const settingId = key ?? `dynamic:${file}:${location(file, source, node).line}`;
          const assignment = sectionAssignment(file, typeof key === 'string' ? key : node.arguments[0].getText(source));
          const { section } = assignment;
          const category = scope(typeof key === 'string' ? key : node.arguments[0].getText(source), syncable, sensitivePatterns);
          settingUses.push({ id: settingId, key: key ?? { expression: node.arguments[0].getText(source) }, operation: name, arguments: node.arguments.slice(1).map(a => a.getText(source)), evidence: location(file, source, node), ...assignment, scope: category, persistence: category === 'secret' ? 'Keystore-encrypted credential record' : /localStorage/.test(name) ? 'desktop localStorage; Android DataStore' : 'desktop mistakes.db/settings; Android same shared record for portable, DataStore for local', android: { route: `settings/${section}`, module: 'features/system', deliveryUnit: 'S7.35' }, states: ['loading', 'ready', 'dirty', 'saving', 'saved', 'error'], acceptance: { id: `SETTING-${settingId}`, normal: 'write value → observe actual feature effect → restart readback', exceptional: category === 'secret' ? 'credential remains encrypted; pairing transfers only through approved secret channel' : category === 'portable' ? 'desktop/Android setting round-trip' : 'other device retains independent value' } });
        }
        if (/\.contribute$|\.announce$/.test(name) && (file.startsWith('src/ui-plugins/official/') || file.includes('/crepe/') || file.includes('/features/plugin-manager/'))) {
          contributions.push({ id: `${file}:${location(file, source, node).line}`, operation: name, arguments: node.arguments.map(a => value(a)), evidence: location(file, source, node), scope: 'owner/host contribution lifetime', persistence: 'domain data through Repository; UI subscriptions are local', android: { module: file.includes('editor') || file.includes('crepe') ? 'features/knowledge' : 'features/workspace', entry: 'Compose contribution registry/ToolPkg DSL', deliveryUnit: 'S6.34' }, states: ['registered', 'active', 'revoked', 'error'] });
        }
      }
      if ((ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) && /^(?:SettingRow|SwitchRow|SettingsSlider|SettingsGroup|SettingSection)$/.test(node.tagName.getText(source))) {
        const attributes = Object.fromEntries(node.attributes.properties.filter(ts.isJsxAttribute).map(a => [a.name.getText(source), a.initializer === undefined ? true : ts.isJsxExpression(a.initializer) ? a.initializer.expression?.getText(source) ?? null : a.initializer.text]));
        const assignment = sectionAssignment(file, '');
        const { section } = assignment;
        uiControls.push({ id: `${file}:${location(file, source, node).line}`, component: node.tagName.getText(source), attributes, evidence: location(file, source, node), ...assignment, scope: 'owning section; field keys linked through settingUses', persistence: 'settingUses or domain API supplied by section props', android: { module: 'features/system', route: `settings/${section}`, deliveryUnit: 'S7.35' }, states: ['enabled', 'disabled', 'loading', 'dirty', 'saved', 'error'] });
      }
      if (ts.isObjectLiteralExpression(node) && prop(node, 'name') && prop(node, 'cardinality')) slotDeclarations.push({ ...value(node), evidence: location(file, source, node), scope: 'plugin owner lifetime', persistence: 'registry is local; contributed domain data follows its repository', android: { module: 'features/workspace', entry: 'typed Compose registry', deliveryUnit: 'S6.34' }, states: ['declared', 'registered', 'revoked', 'error'] });
      if (ts.isInterfaceDeclaration(node) && node.name.text === 'SlotMap') for (const member of node.members) if (member.name) slotTypes.push({ name: member.name.getText(source).replace(/^['"]|['"]$/g, ''), type: member.type?.getText(source), evidence: location(file, source, member) });
    });
    const plugin = parse(file).statements.find(statement => ts.isExportAssignment(statement));
    if (file.endsWith('/index.ts') && plugin) {
      let pluginId = null;
      visit(plugin, node => {
        if (ts.isPropertyAssignment(node) && node.name.getText() === 'id' && ts.isStringLiteralLike(node.initializer)) pluginId = node.initializer.text;
      });
      if (pluginId) ownerOf.set(file, pluginId);
    }
  }
  for (const slot of slotDeclarations) {
    const file = slot.evidence.file;
    const name = slotNameIn(file, slot);
    if (!name) continue;
    slot.name = name;
    const owner = ownerOf.get(file) ?? SLOT_OWNERS[name] ?? null;
    if (owner) slot.owner = owner;
    if (!declaredBy.has(name)) declaredBy.set(name, { owner, file, line: slot.evidence.line });
  }
  for (const slot of slotDeclarations) {
    if (typeof slot.name !== 'string') continue;
    const declaration = declaredBy.get(slot.name);
    if (declaration) {
      slot.owner ??= declaration.owner;
      slot.declaredAt = { file: declaration.file, line: declaration.line };
    }
  }
  const slotSurfaces = [
    {
      slot: 'pdf.selectionToolbar', consumer: 'src/features/pdf/components/PdfSelectionActions.tsx',
      context: ['text', 'contextBefore', 'contextAfter', 'page?', 'resourceId?', 'documentTitle?', 'kind'],
      persistence: 'none; callback may write notes/cards through host services', platforms: ['desktop', 'mobile'],
      unit: 'S5.10', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/surfaces.ts',
    },
    {
      slot: 'chat.composer.commands', consumer: 'src/features/chat/components/input-bar/InputBarUI.tsx',
      context: ['input', 'sessionId?', 'getInput()', 'setInput()', 'insertText()', 'send(text?)'],
      persistence: 'none; callback may send a normal chat message', platforms: ['desktop', 'mobile'],
      unit: 'S3.27', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/surfaces.ts',
    },
    {
      slot: 'chat.message.actions', consumer: 'src/features/chat/components/message/MessageActions.tsx',
      context: ['messageId', 'role', 'sessionId?', 'content'],
      persistence: 'none; callback may write domain data through host services', platforms: ['desktop', 'mobile'],
      unit: 'S3.27', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/surfaces.ts',
    },
    {
      slot: 'review.cardActions', consumer: 'src/features/flashcards/review/ReviewCardPluginActions.tsx',
      context: ['cardId', 'noteCardId?', 'front', 'back', 'tags', 'flipped'],
      persistence: 'none; callback may write domain data through host services', platforms: ['desktop', 'mobile'],
      unit: 'S7.12', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/surfaces.ts',
    },
    {
      slot: 'statusbar.items', consumer: 'src/features/workbench/components/StatusBarItems.tsx',
      context: ['className'], persistence: 'none', platforms: ['desktop'],
      unit: 'S5.27', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/surfaces.ts',
    },
    {
      slot: 'desktop.widgets', consumer: 'src/features/workbench/components/DesktopPluginWidgets.tsx',
      context: ['title', "size?: 'sm' | 'md' | 'lg'", 'component'], persistence: 'none', platforms: ['desktop'],
      unit: 'S5.27', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/surfaces.ts',
    },
    {
      slot: 'theme.variants', consumer: 'src/features/settings/components/ThemeVariantPicker.tsx',
      context: ['label', 'description?', 'light?', 'dark?'],
      persistence: 'localStorage ds.ui.themeVariant = <plugin id>/<contribution id>; per-device inline CSS variables',
      platforms: ['desktop', 'mobile'], unit: 'S5.01', hostUnit: 'S6.34',
      contract: 'src/ui-kernel/contracts/surfaces.ts',
    },
    {
      slot: 'editor.extensions', consumer: 'src/components/crepe/CrepeEditor.tsx',
      context: ['options', "purpose?: 'editor' | 'diff'"], persistence: 'none',
      platforms: ['desktop', 'mobile'], unit: 'S5.13', hostUnit: 'S6.34',
      contract: 'src/ui-kernel/contracts/editor.ts',
    },
    {
      slot: 'editor.slashMenu', consumer: 'src/components/crepe/CrepeEditor.tsx',
      context: ['menu', 'editor'], persistence: 'none', platforms: ['desktop', 'mobile'],
      unit: 'S5.13', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/editor.ts',
    },
    {
      slot: 'editor.selectionToolbar', consumer: 'src/components/crepe/CrepeEditor.tsx',
      context: ['toolbar', 'editor'], persistence: 'none', platforms: ['desktop', 'mobile'],
      unit: 'S5.10', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/editor.ts',
    },
    {
      slot: 'editor.blockMenu', consumer: 'src/components/crepe/CrepeEditor.tsx',
      context: ['target', 'editor'], persistence: 'none', platforms: ['desktop', 'mobile'],
      unit: 'S5.13', hostUnit: 'S6.34', contract: 'src/ui-kernel/contracts/editor.ts',
    },
  ];
  for (const surface of slotSurfaces) {
    const declaration = declaredBy.get(surface.slot);
    assert.ok(declaration, `${surface.slot}: no declaration`);
    const type = slotTypes.find(entry => entry.name === surface.slot);
    assert.ok(type, `${surface.slot}: no SlotMap type`);
    assert.ok(existsSync(resolve(desktop, surface.consumer)), `${surface.slot}: missing consumer`);
    surface.owner = declaration.owner;
    surface.valueType = type.type;
    surface.declaredAt = { file: declaration.file, line: declaration.line };
    surface.typeAt = type.evidence;
    surface.source = surface.consumer;
    surface.scope = 'community plugin owner lifetime; host surface remains available after a callback failure';
    surface.android = {
      module: 'features/workspace',
      entry: 'typed Compose registry / contextual surface',
      deliveryUnit: surface.unit,
      pluginHostDeliveryUnit: surface.hostUnit,
    };
    surface.states = ['declared', 'registered', 'rendered', 'running', 'failed', 'revoked'];
    surface.acceptance = {
      id: `SLOT-${surface.slot}`,
      normal: 'install owner → render contribution → invoke callback → uninstall and remove entry',
      exceptional: 'throwing callback is isolated and the host surface stays usable',
    };
    surface.evidence = evidence(surface.consumer, 1);
  }
  const compatibilityEvidence = [
    {
      id: 'settings.android-feature-flags',
      file: 'src/features/settings/components/AndroidFeatureFlagsSection.tsx',
      classification: 'not-adopted',
      reason: 'upstream mobile shell-only controls; the dstu Android implementation is native and does not consume the desktop mobile chain',
      persistence: 'Host feature_flags.config through get_feature_flags/update_feature_flag/is_feature_enabled',
      scope: 'local', unit: 'S7.35',
    },
    {
      id: 'settings.plugin-manager-trust',
      file: 'src/features/plugin-manager/PluginCard.tsx',
      classification: 'adopt-desktop-feature',
      reason: 'desktop plugin manager remains the source for list/configure/consent/update/uninstall and local trust ledger',
      persistence: 'plugin registry/package data; trust and enablement remain local', scope: 'local', unit: 'S7.35',
    },
    {
      id: 'settings.theme-variant-selection',
      file: 'src/features/settings/themeVariants.ts',
      classification: 'adopt-desktop-feature',
      reason: 'desktop plugin theme variants are picked locally and reapplied when the owner returns',
      persistence: 'localStorage ds.ui.themeVariant', scope: 'local', unit: 'S5.01',
    },
    {
      id: 'settings.credential-scan',
      file: 'host/plugins/plugin-workshop/src/secrets.ts',
      classification: 'adopt-desktop-feature',
      reason: 'workshop secret settings move values into the secure store and never into the package/export',
      persistence: 'system secure store; secret settings omitted from manifest/config', scope: 'secret', unit: 'S6.36',
    },
  ];
  for (const entry of compatibilityEvidence) {
    assert.ok(existsSync(resolve(desktop, entry.file)), `${entry.id}: missing source`);
    entry.evidence = evidence(entry.file, 1);
  }
  const notAdoptedSettings = settingUses.filter(entry => MOBILE_ONLY_SETTING_FILES.has(entry.evidence.file));
  const notAdoptedControls = uiControls.filter(entry => MOBILE_ONLY_SETTING_FILES.has(entry.evidence.file));
  const desktopSettings = settingUses.filter(entry => !MOBILE_ONLY_SETTING_FILES.has(entry.evidence.file));
  const desktopControls = uiControls.filter(entry => !MOBILE_ONLY_SETTING_FILES.has(entry.evidence.file));
  const notAdopted = {
    classification: 'not-adopted',
    reason: 'upstream mobile-shell-only settings controls; evidence is retained, but the desktop delivery arrays and the S0.23 settings input exclude them',
    files: [...MOBILE_ONLY_SETTING_FILES].sort(),
    settings: notAdoptedSettings,
    controls: notAdoptedControls,
    consumer: 'S0.23 consumes desktopSettings/desktopControls only; dstu settings are implemented natively in S7.35',
  };
  const searchDetails = [];
  const searchFile = 'src/ui-plugins/official/settings/search.ts';
  visit(parse(searchFile), node => { if (ts.isObjectLiteralExpression(node) && prop(node, 'tab')) searchDetails.push(value(node)); });
  const uiSurfaces = [
    { id: 'dock.pinned', source: 'src/features/workbench/components/DockPinnedStore.tsx', scope: 'local', persistence: 'device layout snapshot', states: ['pinned', 'running', 'badge', 'reordered'], android: 'phone task switcher/tablet dock', actions: ['launch', 'activate', 'pin', 'unpin', 'reorder', 'close'], unit: 'S5.27' },
    { id: 'dock.apps-agent', source: 'src/features/workbench/components/Dock.tsx', scope: 'local', persistence: 'AppRegistry + task ledger', states: ['apps-panel', 'agent-control', 'active-tasks'], android: 'all-apps/task-control actions', actions: ['open-all-apps', 'inspect-agent', 'cancel-task'], unit: 'S5.27' },
    { id: 'status.learning', source: 'src/features/workbench/components/StatusBarItems.tsx', scope: 'domain projection + local task state', persistence: 'pomodoro/review/task/automation repositories', states: ['focus', 'break', 'paused', 'due-count', 'task-count', 'automation-running', 'automation-error'], android: 'native top bar/task notification/learning summary', actions: ['open-pomodoro', 'start-due-review', 'open-card-tasks', 'show-automations'], unit: 'S7.34' },
    { id: 'status.shell', source: 'src/features/workbench/components/StatusBar.tsx', scope: 'local', persistence: 'layout/theme + repository summaries', states: ['focused-app', 'window-menu', 'clock', 'rhythm-flyout'], android: 'tablet top bar/phone toolbar', actions: ['commands', 'settings', 'today-review', 'task-switch'], unit: 'S5.27' },
    { id: 'selection.shared', source: 'src/shared/selection/SelectionToolbar.tsx', scope: 'resource selection', persistence: 'selection local; actions persist domain changes', states: ['hidden', 'selected', 'disabled-action', 'running', 'copied'], android: 'Compose contextual toolbar/selection actions', actions: ['copy', 'explain', 'translate', 'save-as-note', 'make-cards', 'add-to-chat', 'add-as-context', 'extra-actions'], unit: 'S5.10' },
  ];
  assert.equal(new Set(slotDeclarations.map(entry => typeof entry.name === 'string' ? entry.name : null).filter(Boolean)).size, EXPECTED.slotDeclarations, 'unique slot declarations');
  assert.equal(new Set(slotTypes.map(entry => entry.name)).size, EXPECTED.slotTypes, 'unique SlotMap entries');
  assert.equal(slotSurfaces.length, 11);
  assert.deepEqual(
    slotSurfaces.map(entry => entry.slot).sort(),
    ['chat.composer.commands', 'chat.message.actions', 'desktop.widgets', 'editor.blockMenu',
      'editor.extensions', 'editor.selectionToolbar', 'editor.slashMenu', 'pdf.selectionToolbar',
      'review.cardActions', 'statusbar.items', 'theme.variants'],
  );
  for (const [slot, owner] of Object.entries(SLOT_OWNERS)) {
    assert.equal(declaredBy.get(slot)?.owner, owner, `${slot}: owner`);
    assert.ok(slotTypes.some(entry => entry.name === slot), `${slot}: type`);
  }
  for (const slot of slotSurfaces) {
    assert.ok(['S3.27', 'S5.01', 'S5.10', 'S5.13', 'S5.27', 'S7.12'].includes(slot.unit), `${slot.slot}: delivery unit`);
    assert.equal(slot.hostUnit, 'S6.34', `${slot.slot}: host delivery unit`);
    assert.equal(slot.android.deliveryUnit, slot.unit, `${slot.slot}: android delivery unit`);
    assert.equal(slot.android.pluginHostDeliveryUnit, 'S6.34', `${slot.slot}: android host delivery unit`);
  }
  assert.equal(notAdoptedSettings.length, 0);
  assert.equal(notAdoptedControls.length, 4);
  assert.equal(desktopControls.length + notAdoptedControls.length, uiControls.length);
  assert.equal(desktopSettings.length + notAdoptedSettings.length, settingUses.length);
  assert.ok(desktopControls.every(entry => !MOBILE_ONLY_SETTING_FILES.has(entry.evidence.file)));
  assert.ok(desktopSettings.every(entry => !MOBILE_ONLY_SETTING_FILES.has(entry.evidence.file)));
  const sourcePaths = new Set(allSrc);
  assert.ok(desktopSettings.every(entry => sourcePaths.has(entry.evidence.file)), 'settings evidence must be a non-test desktop source file');
  assert.ok(desktopControls.every(entry => sourcePaths.has(entry.evidence.file)), 'controls evidence must be a non-test desktop source file');
  assert.ok(desktopSettings.every(entry => ['portable', 'local', 'secret'].includes(entry.scope)));
  assert.ok(desktopSettings.every(entry => entry.android.route === `settings/${entry.section}`));
  assert.ok(desktopSettings.every(entry => entry.acceptance.id === `SETTING-${entry.id}`));
  assert.ok(desktopSettings.filter(entry => typeof entry.key !== 'string').every(entry =>
    entry.id === `dynamic:${entry.evidence.file}:${entry.evidence.line}`
    && typeof entry.key.expression === 'string'
    && entry.key.expression.length > 0));
  log('contributions.coverage', { settingUses: desktopSettings.length, upstreamSettingUses: settingUses.length, slotTypes: slotTypes.length, slotDeclarations: new Set(slotDeclarations.map(entry => typeof entry.name === 'string' ? entry.name : null).filter(Boolean)).size, contributions: contributions.length, controls: desktopControls.length, upstreamControls: uiControls.length, notAdoptedControls: notAdoptedControls.length, surfaces: slotSurfaces.length });
  assert.equal(desktopSettings.length, EXPECTED.settingUses);
  assert.equal(slotTypes.length, EXPECTED.slotTypes);
  assert.equal(contributions.length, EXPECTED.contributions);
  assert.equal(desktopControls.length, EXPECTED.controls);
  return {
    formatVersion: 1,
    unit: 'S0.06',
    deliveryScope: {
      settings: 'desktop settings and preference surfaces only; mobile-only controls are excluded and retained under notAdopted',
      controls: 'desktop settings controls only; mobile-only controls are excluded and retained under notAdopted',
      settingsConsumer: 'S0.23 consumes settings (desktop scope), with dynamic key expressions retained for traceability',
      pluginHost: 'all contributed surfaces registered through the S6.34 Compose contribution host when applicable',
    },
    sections,
    settings: desktopSettings,
    controls: desktopControls,
    notAdopted,
    searchDetails,
    slotDeclarations,
    slotTypes,
    contributions,
    surfaces: uiSurfaces,
    slotSurfaces,
    compatibilityEvidence,
    baselinePortableKeys: [...syncable].sort(),
    classificationRule: 'scope records current desktop preference whitelist; S0.23 freezes additional portable configuration and secret pairing contracts; theme variant pick and mobile-shell feature flags stay local',
    sourceHashes: [...new Set([sectionFile, searchFile, ...parsed.keys(), ...uiSurfaces.map(s => s.source), ...slotSurfaces.flatMap(s => [s.consumer, s.contract]), ...compatibilityEvidence.map(s => s.file), 'src/features/plugin-manager/mount.tsx', 'host/plugins/sync/src/classification-data/settings.ts', 'host/platform/secrets/sensitive-key.ts'])].sort().map(file => ({ file, sha256: sha256(readDesktop(file)) })),
  };
}
export function run(mode) {
  const data = collect();
  artifact('config/baseline/contributions.json', data, mode);
  log('contributions.accepted', {
    sections: data.sections.length,
    settingUses: data.settings.length,
    controls: data.controls.length,
    namedSlotDeclarations: data.slotDeclarations.filter(entry => typeof entry.name === 'string').length,
    slotDeclarationObjects: data.slotDeclarations.length,
    slotTypes: data.slotTypes.length,
    contributions: data.contributions.length,
    notAdoptedControls: data.notAdopted.controls.length,
    surfaces: data.slotSurfaces.length,
  });
}
main(import.meta.url, run);
