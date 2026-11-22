import assert from 'node:assert/strict';
import { artifact, main, parse, ts, visit, value, prop, location, sourceFiles, desktop, readDesktop, sha256, log, assertUnique } from './lib.mjs';

function contextOf(node, sf) {
  const conditions = [];
  let current = node.parent;
  while (current && current !== sf) {
    if (ts.isConditionalExpression(current)) conditions.push(current.condition.getText(sf));
    if (ts.isIfStatement(current)) conditions.push(current.expression.getText(sf));
    current = current.parent;
  }
  return conditions;
}
function targetFor(category) {
  return ({ navigation: 'app', global: 'features/system', settings: 'features/system', chat: 'features/chat', notes: 'features/knowledge', learning: 'features/knowledge', anki: 'features/study', automation: 'features/productivity', dev: 'features/system' })[category];
}
export function collect() {
  const capabilityFile = 'src/command-palette/registry/capabilityRegistry.ts';
  const capabilities = {};
  visit(parse(capabilityFile), node => {
    if (ts.isPropertyAssignment(node) && ts.isStringLiteralLike(node.name) && ts.isStringLiteralLike(node.initializer)) capabilities[node.name.text] = node.initializer.text;
  });
  const commands = [];
  for (const file of sourceFiles(desktop, 'src/command-palette/modules').filter(f => f.endsWith('.commands.ts'))) {
    const sf = parse(file);
    visit(sf, node => {
      if (!ts.isObjectLiteralExpression(node)) return;
      const id = prop(node, 'id'), execute = prop(node, 'execute'), category = prop(node, 'category');
      if (!id || !execute || !category) return;
      const definition = value(node);
      commands.push({
        id: definition.id, kind: 'command', definition, evidence: location(file, sf, node),
        startupParameters: { input: execute.initializer.parameters?.map(p => p.getText(sf)), context: definition.visibleInViews ?? 'current-view', workbenchApps: definition.workbenchApps ?? 'all-apps' },
        conditions: { build: definition.category === 'dev' ? 'debug' : 'release-and-debug', capability: capabilities[definition.id] ?? 'directly-registered', enabled: definition.isEnabled ?? true, ancestors: contextOf(node, sf) },
        android: { module: targetFor(definition.category), entry: `commands/${definition.id}`, deliveryUnit: 'S5.34', return: 'dismiss palette → originating page; navigation command pushes destination' },
        acceptance: { id: `ENTRY-${definition.id}`, normal: `invoke ${definition.id} with the declared context and inspect execute result`, exceptional: 'unavailable context disables entry; cancellation preserves originating draft; back restores caller' },
      });
    });
  }
  assertUnique(commands, row => row.id);
  const menuRows = [];
  const menuFiles = sourceFiles(desktop, 'src/ui-plugins/official/menu').filter(f => /(?:Menus?|Menu)\.(?:ts|tsx)$/.test(f));
  for (const file of menuFiles) {
    const sf = parse(file);
    visit(sf, node => {
      if (ts.isObjectLiteralExpression(node)) {
        const id = prop(node, 'id');
        const action = prop(node, 'run');
        if (!id || !action) return;
        const row = value(node);
        menuRows.push({ kind: 'menu-row', id: `${file}:${location(file, sf, node).line}`, rowId: row.id, definition: row, evidence: location(file, sf, node), startupParameters: 'menu context and action parameters in definition.run', conditions: { ancestors: contextOf(node, sf), visible: row.visible ?? true, disabled: row.disabled ?? false }, android: { entry: 'long-press/action-sheet or toolbar overflow', module: file.includes('notes') || file.includes('finder') || file.includes('tabBar') ? 'features/knowledge' : 'features/workspace', deliveryUnit: 'S5.34', return: 'close menu → current resource/panel' }, acceptance: { id: `MENU-${file.split('/').at(-1)}-${location(file, sf, node).line}`, normal: 'supply menu context, invoke action and compare resource/panel state', exceptional: 'respect disabled/visibility, cancel leaves state unchanged' } });
      }
      if (ts.isCallExpression(node) && node.expression.getText(sf) === 'row' && node.arguments.length === 3 && ts.isObjectLiteralExpression(node.arguments[2])) {
        const row = value(node.arguments[2]);
        menuRows.push({ kind: 'resource-menu-row', id: `notes-tree:${value(node.arguments[0])}`, definition: row, order: value(node.arguments[1]), evidence: location(file, sf, node), startupParameters: 'NotesTreeMenuContext.item/createFolderId/actions', conditions: { visible: row.visible, disabled: row.disabled ?? false }, android: { module: 'features/knowledge', entry: 'knowledge/tree/context-menu', deliveryUnit: 'S7.01', return: 'close menu → resource tree' }, acceptance: { id: `MENU-notes-tree-${value(node.arguments[0])}`, normal: 'execute on note/mindmap/folder with the source context', exceptional: 'enforce canDelete/canRename and preserve selection after dismissal' } });
      }
    });
  }
  const debugPanels = [];
  const debugFile = 'src/debug-panel/panels.ts';
  const debugSf = parse(debugFile);
  visit(debugSf, node => {
    if (!ts.isObjectLiteralExpression(node) || !prop(node, 'id') || !prop(node, 'component')) return;
    const row = value(node);
    debugPanels.push({ ...row, evidence: location(debugFile, debugSf, node), startupParameters: 'current diagnostics context; panel id', conditions: 'debug-build', android: { module: 'features/system', entry: `debug/panels/${row.id}`, deliveryUnit: 'S9.02', return: 'debug panel list → caller' }, acceptance: { id: `DEBUG-${row.id}`, normal: 'open matching native diagnostic and record emitted events', exceptional: 'release build has no route; closing observer releases subscriptions' } });
  });
  const externals = [
    { id: 'file.open-external', direction: 'outbound', source: 'src/utils/systemFileOpener.ts', symbol: 'openFileExternally', parameters: { path: 'file-path' }, condition: 'user action and URI grant', android: 'ACTION_VIEW + FileProvider URI; chooser reports result', return: 'calling page', unit: 'S2.07' },
    { id: 'file.share-external', direction: 'outbound', source: 'src/utils/systemFileOpener.ts', symbol: 'shareFileExternally', parameters: { path: 'file-path', mode: 'share' }, condition: 'user action and URI grant', android: 'ACTION_SEND + temporary read permission + chooser', return: 'calling page', unit: 'S2.07' },
    { id: 'file.reveal', direction: 'outbound', source: 'src/utils/systemFileOpener.ts', symbol: 'revealFileOrOpen', parameters: { path: 'file-path' }, condition: 'selected file', android: 'explicit open-in-other-app action', return: 'calling page', unit: 'S2.07' },
    { id: 'file.receive', direction: 'inbound', source: 'src/utils/systemFileOpener.ts', symbol: 'new Android counterpart to outbound file interoperability', parameters: { action: ['ACTION_VIEW', 'ACTION_SEND', 'ACTION_SEND_MULTIPLE'], uri: 'content URI', mimeType: 'declared MIME', text: 'optional text' }, condition: 'Android exported receiver with user-selected import target', android: 'URI/PFD import → ResourceStore → library/resource route', return: 'import review → resource or Android caller', unit: 'S7.04' },
    { id: 'browser.oauth-callback', direction: 'inbound', source: 'host/plugins/mcp', symbol: 'MCP OAuth state/PKCE callback', parameters: { state: 'opaque session state', code: 'authorization code' }, condition: 'pending local authorization matches state and redirect', android: 'versioned MCP callback route', return: 'MCP configuration → caller', unit: 'S6.29' },
  ].map(row => ({ ...row, acceptance: { id: `EXTERNAL-${row.id}`, normal: `invoke ${row.id} with the declared payload and verify ${row.return}`, exceptional: 'revoke URI grant or cancel chooser/auth; preserve user content and record result' } }));
  const deepLinkSearch = sourceFiles(desktop, 'src', 'electron', 'mobile').filter(f => /\.(ts|tsx|kt|java|xml)$/.test(f) && !/(?:__tests__|\/tests\/|\/equivalence\/)/.test(f));
  const matches = deepLinkSearch.filter(file => /setAsDefaultProtocolClient|appUrlOpen|onOpenUrl|deepLink|deep-link|deeplink/i.test(readDesktop(file)));
  const sourceHashes = [...new Set([...commands.map(r => r.evidence.file), ...menuFiles, debugFile, capabilityFile, 'src/utils/systemFileOpener.ts', 'electron/app-lifecycle.ts', 'electron/shell/protocols.ts'])].sort().map(file => ({ file, sha256: sha256(readDesktop(file)) }));
  assert.ok(menuRows.length > 50 && debugPanels.length > 30 && commands.length > 80);
  return { formatVersion: 1, unit: 'S0.03', commands, menus: menuRows, debugPanels, externalEntries: externals, deepLinks: { externalAppSchemeRegisteredInBaseline: false, scannedFiles: deepLinkSearch.length, matches, evidence: ['electron/app-lifecycle.ts:173 second-instance focuses main window', 'electron/shell/protocols.ts resource-serving schemes are internal transports'], androidContract: 'typed resource/command routes above; public intent filters are implemented in their delivery units' }, sourceHashes };
}
export function run(mode) {
  const data = collect();
  artifact('config/baseline/entries.json', data, mode);
  log('entries.accepted', { commands: data.commands.length, menus: data.menus.length, debugPanels: data.debugPanels.length, externalEntries: data.externalEntries.length });
}
main(import.meta.url, run);
