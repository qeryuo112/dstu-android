import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import {
  artifact, main, parse, ts, visit, value, prop, location, sourceFiles, desktop,
  desktopRequire, readDesktop, sha256, assertUnique, log, unwrap,
} from './lib.mjs';

const targets = {
  chat: ['features/chat', 'sessionId selected within singleton', 'S5.30'],
  'chat-session': ['features/chat', 'sessionId', 'S5.30'],
  textbook: ['features/knowledge', 'resourceId', 'S7.06'], exam: ['features/study', 'resourceId selected within singleton', 'S7.18'],
  translation: ['features/knowledge', 'resourceId selected within singleton', 'S7.32'], essay: ['features/study', 'resourceId selected within singleton', 'S7.23'],
  image: ['features/knowledge', 'resourceId', 'S7.02'], file: ['features/knowledge', 'resourceId', 'S7.02'],
  notes: ['features/knowledge', 'noteId/mindmapId tabs within singleton', 'S5.30'], files: ['features/knowledge', 'folderId selected within singleton', 'S7.01'],
  'file-preview': ['features/knowledge', 'resourceId', 'S7.06'], aiDashboard: ['features/productivity', 'singleton', 'S7.34'],
  todo: ['features/productivity', 'singleton', 'S7.25'], skills: ['features/chat', 'singleton', 'S3.20'], templates: ['features/study', 'singleton', 'S7.09'],
  taskDashboard: ['features/study', 'singleton', 'S7.08'], flashcards: ['features/study', 'singleton', 'S7.12'], media: ['features/knowledge', 'singleton', 'S7.31'],
  settings: ['features/system', 'singleton', 'S7.35'], pomodoro: ['features/productivity', 'singleton', 'S7.28'],
  sandbox: ['features/workspace', 'singleton', 'S6.43'], browser: ['features/workspace', 'browserSessionId within singleton', 'S6.43'],
};

const STATIC_GOLDEN = 'src/features/workbench/apps/__golden__/apps.json';
const PLUGIN_VIEW_DIR = 'src/features/workbench/apps/plugin-views';
const PLUGIN_VIEW_REGISTER = `${PLUGIN_VIEW_DIR}/register.tsx`;
const PLUGIN_VIEW_WINDOW = `${PLUGIN_VIEW_DIR}/PluginViewAppWindow.tsx`;
const WINDOW_BODY = 'src/features/workbench/components/WindowBody.tsx';
const BESIDE_PLACEMENT = 'src/features/workbench/core/besidePlacement.ts';
const WORKBENCH_BUS = 'src/features/workbench/core/workbenchBus.ts';
const TYPES_FILE = 'src/features/workbench/core/types.ts';
const PLUGIN_VIEW_PREFIX = 'plugin-view.';
const STATIC_APP_COUNT = 22;

function sourceLine(file, token) {
  const lines = readDesktop(file).split('\n');
  const index = lines.findIndex(line => line.includes(token));
  assert.ok(index >= 0, `${file}: missing evidence token ${token}`);
  return { source: 'desktop', file, line: index + 1, symbol: token };
}

function sourceHash(file) {
  return { file, sha256: sha256(readDesktop(file)) };
}

async function bundleIsolated(entry, stubs, label) {
  const esbuild = desktopRequire('esbuild');
  const stubContents = new Map(Object.entries(stubs));
  const result = await esbuild.build({
    absWorkingDir: desktop,
    stdin: { contents: entry, resolveDir: desktop, sourcefile: `${label}.tsx`, loader: 'tsx' },
    tsconfig: resolve(desktop, 'tsconfig.json'),
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'node',
    target: 'node22',
    jsx: 'transform',
    metafile: true,
    logLevel: 'silent',
    plugins: [{
      name: 's0-04-isolated-stubs',
      setup(build) {
        build.onResolve({ filter: /.*/ }, args => {
          if (!stubContents.has(args.path)) return null;
          return { path: args.path, namespace: 's0-04-stub', pluginData: { contents: stubContents.get(args.path) } };
        });
        build.onLoad({ filter: /.*/, namespace: 's0-04-stub' }, args => ({
          contents: args.pluginData.contents,
          loader: 'tsx',
        }));
      },
    }],
  });
  const code = result.outputFiles[0].text;
  const exports = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
  const inputs = Object.keys(result.metafile.inputs)
    .filter(file => file !== `${label}.tsx` && !file.includes('s0-04-stub'))
    .sort();
  return { exports, inputs };
}

function describeDefinition(def) {
  if (!def) return null;
  return {
    typeId: def.typeId,
    nameKey: def.nameKey,
    instanceMode: def.instanceMode,
    memoryWeight: def.memoryWeight,
    defaultFrame: def.defaultFrame,
    minSize: def.minSize,
    showInLauncher: def.showInLauncher ?? null,
    render: def.render ? 'lazy-view-host' : null,
    hasOwnDraftHook: Object.hasOwn(def, 'draft'),
    hasCanClose: typeof def.canClose === 'function',
    hasCanSuspend: typeof def.canSuspend === 'function',
    hasPrepareSuspend: typeof def.prepareSuspend === 'function',
  };
}

function collectStaticApps() {
  const all = sourceFiles(desktop, 'src/features/workbench/apps')
    .filter(file => /\.(ts|tsx)$/.test(file) && !/(?:__tests__|\.test\.)/.test(file));
  const constants = new Map();
  for (const file of all) {
    visit(parse(file), node => {
      if (ts.isVariableDeclaration(node) && node.initializer && ts.isStringLiteralLike(unwrap(node.initializer))) {
        constants.set(node.name.getText(), unwrap(node.initializer).text);
      }
    });
  }
  const registrationFiles = all.filter(file => /\/register\.(?:ts|tsx)$/.test(file) && !file.includes('/mindmap/') && !file.includes('/desktop/'));
  const dynamicRegistrationFiles = registrationFiles.filter(file => file.startsWith(`${PLUGIN_VIEW_DIR}/`));
  const staticRegistrationFiles = registrationFiles.filter(file => !file.startsWith(`${PLUGIN_VIEW_DIR}/`));
  assert.deepEqual(dynamicRegistrationFiles, [PLUGIN_VIEW_REGISTER], 'dynamic family must be discovered separately from official static registration files');

  const definitionRows = [];
  for (const file of staticRegistrationFiles) {
    const sf = parse(file);
    visit(sf, node => {
      if (!ts.isObjectLiteralExpression(node) || !prop(node, 'typeId') || !prop(node, 'memoryWeight') || !prop(node, 'defaultFrame')) return;
      const typeIdNode = unwrap(prop(node, 'typeId').initializer);
      const id = ts.isStringLiteralLike(typeIdNode) ? typeIdNode.text : constants.get(typeIdNode.getText());
      assert.ok(id, `static workbench definition has unresolved/dynamic typeId in ${file}`);
      assert.ok(!id.startsWith(PLUGIN_VIEW_PREFIX), `dynamic plugin-view leaked into official static apps: ${id}`);
      assert.ok(targets[id], `unmapped official workbench app ${id}`);
      const definition = value(node);
      definition.typeId = id;
      definition.instanceMode ??= 'multi';
      definition.minSize ??= { w: 360, h: 280 };
      definitionRows.push({
        id,
        definition,
        evidence: location(file, sf, node),
        factory: file.includes('/content/'),
      });
    });
  }
  assertUnique(definitionRows, row => row.id);
  assert.equal(definitionRows.length, STATIC_APP_COUNT, 'official static app count changed');
  const golden = JSON.parse(readDesktop(STATIC_GOLDEN));
  assert.equal(golden.registry.length, STATIC_APP_COUNT, 'official apps golden count changed');
  assert.deepEqual(definitionRows.map(row => row.id).sort(), golden.registry.map(row => row.typeId).sort());
  assert.ok(golden.registry.every(row => !row.typeId.startsWith(PLUGIN_VIEW_PREFIX)), 'official golden must not contain dynamic family ids');
  return { all, registrationFiles, dynamicRegistrationFiles, staticRegistrationFiles, definitionRows, golden };
}

function buildStaticApps(definitionRows, golden) {
  return golden.registry.map((goldenRow, index) => {
    const row = definitionRows.find(candidate => candidate.id === goldenRow.typeId);
    const { id, definition, factory } = row;
    const [module, resourceIdentity, unit] = targets[id];
    const editable = ['notes', 'exam', 'translation', 'essay'].includes(id);
    return {
      ...row,
      order: index,
      resourceTypes: id === 'notes' ? ['note', 'mindmap'] : id === 'file' || id === 'file-preview' ? ['file', 'pdf', 'audio', 'video', 'office', 'epub'] : [id],
      instanceRule: definition.instanceMode === 'single' ? 'one panel per appId; resource switches within panel' : 'deduplicate by appId + instanceKey; distinct resource ids coexist',
      resourceIdentity,
      activation: definition.onActivation ?? (factory ? 'createContentActivationHandler(typeId)' : 'launch-and-focus'),
      draft: editable ? 'resource-scoped draft; dirty state survives panel switch and Activity recreation' : id.startsWith('chat') ? 'session-scoped composer and streaming task independent of panel lifetime' : 'domain state persists through repository; view state remains local',
      close: id === 'exam' ? 'unfinished review confirmation → dirty confirmation → close' : editable ? 'clean closes; dirty offers save/discard/cancel; save failure and cancel keep panel' : id === 'browser' ? 'close native browser surface on panel release' : 'close panel without cancelling domain/background task',
      suspend: factory || id === 'notes' ? 'dirty=false permits frozen; dirty=true stays background; visual pause does not stop domain task' : 'scheduler may freeze view; task/repository remains application-owned',
      snapshot: { version: 1, identity: ['appId', 'instanceKey'], localState: ['route', 'active tab', 'scroll/selection', 'layout', 'focus'], excludes: ['launchPayload', 'lifecycle', 'live handles', 'Job', 'lease'], storage: 'Android runtime.db device-local layout snapshot', desktopReferenceKey: 'desktop.workbenchSnapshot' },
      android: { module, appId: id, phone: `apps/${id}`, tablet: 'workspace/panel/{panelId}', deliveryUnit: unit },
      acceptance: [
        { id: `WB-${id}-open`, input: { appId: id, instanceKey: definition.instanceMode === 'single' ? null : 'fixture-resource-1' }, expected: 'reopen same identity activates existing panel; other allowed identity follows instanceMode' },
        { id: `WB-${id}-lifecycle`, input: ['activate', 'background', 'rotate', 'close/cancel', 'relaunch'], expected: editable ? 'draft retained; cancel close leaves layout and dirty text unchanged' : 'resource and domain task state preserved; transient launch payload is not restored' },
      ],
    };
  });
}

async function replayPluginViewFamily() {
  const built = await bundleIsolated(`
    export {
      PLUGIN_VIEW_APP_PREFIX,
      pluginViewAppTypeId,
      isPluginViewAppTypeId,
      resolvePluginViewAppTypeId,
      pluginViewNameKey,
      mirrorPluginViewApps,
    } from './${PLUGIN_VIEW_REGISTER}';
    export { appRegistry } from './src/features/workbench/core/appRegistry.ts';
    export { SlotStore } from './src/ui-kernel/slots.ts';
  `, {
    react: `
      const React = {
        createElement: (type, props, ...children) => ({ $$typeof: 'stub.element', type, props, children }),
        lazy: (load) => ({ $$typeof: 'stub.lazy', load }),
      };
      export const createElement = React.createElement;
      export const lazy = React.lazy;
      export default React;
    `,
    'react/jsx-runtime': `
      const jsx = (type, props) => ({ $$typeof: 'stub.element', type, props });
      export { jsx, jsx as jsxs };
    `,
    'react/jsx-dev-runtime': `
      const jsx = (type, props) => ({ $$typeof: 'stub.element', type, props });
      export { jsx, jsx as jsxs };
    `,
    '@phosphor-icons/react': `
      export const PuzzlePiece = (props) => ({ $$typeof: 'stub.icon', type: 'PuzzlePiece', props });
    `,
    '@/components/ErrorBoundary': `
      export function ErrorBoundary() { return null; }
      export default ErrorBoundary;
    `,
    '@/ui-kernel/app-kernel': `
      export function whenAppUiKernel() { return () => {}; }
    `,
    './PluginViewAppWindow': `
      export const PluginViewAppWindow = () => null;
      export default PluginViewAppWindow;
    `,
  }, 's0-04-plugin-view-replay');

  const api = built.exports;
  const { appRegistry, SlotStore } = api;
  const slots = new SlotStore();
  slots.declare({ name: 'views', cardinality: 'keyed', description: 'S0.04 production slot declaration' });
  const kernel = { slots };
  const stop = api.mirrorPluginViewApps(kernel);
  const typeId = api.pluginViewAppTypeId('recall');
  const observation = stage => ({ stage, typeId, definition: describeDefinition(appRegistry.get(typeId)), resolved: api.resolvePluginViewAppTypeId('recall') });
  const steps = [observation('start-empty')];

  const recall = { id: 'workshop.plugin-recall/recall', key: 'recall', value: { id: 'recall', titleKey: 'title', component: () => null } };
  const pluginA = 'workshop.plugin-recall';
  const pluginB = 'community.recall-v2';
  let remove = slots.contribute('views', pluginA, recall, 'community');
  steps.push(observation('community-register'));
  const firstDef = appRegistry.get(typeId);
  assert.ok(firstDef, 'community view did not register a workbench app');
  assert.equal(firstDef.nameKey, 'workshop.plugin-recall:title');
  assert.equal(firstDef.instanceMode, 'single');

  const removeBuiltin = slots.contribute('views', 'dstu.official-view', {
    id: 'dstu.official-view/official-view', key: 'official-view',
    value: { id: 'official-view', titleKey: 'title', component: () => null },
  }, 'builtin');
  assert.equal(appRegistry.get(api.pluginViewAppTypeId('official-view')), undefined, 'builtin view leaked into dynamic family');
  steps.push({ stage: 'builtin-ignored', builtinTypeId: api.pluginViewAppTypeId('official-view'), definition: null });

  remove();
  assert.equal(appRegistry.get(typeId), undefined, 'removed slot entry left its app registered');
  steps.push(observation('entry-removed'));
  remove = slots.contribute('views', pluginB, recall, 'community');
  steps.push(observation('owner-change-reentry'));
  assert.notEqual(appRegistry.get(typeId), firstDef, 'owner replacement reused stale definition');
  assert.equal(appRegistry.get(typeId).nameKey, 'community.recall-v2:title');

  const mismatched = { ...appRegistry.get(typeId), typeId };
  assert.equal(appRegistry.unregister(typeId, mismatched), false, 'mismatched unregister removed another definition');
  assert.equal(appRegistry.get(typeId).nameKey, 'community.recall-v2:title');
  steps.push({ stage: 'mismatched-unregister-ignored', typeId, definition: describeDefinition(appRegistry.get(typeId)) });

  remove();
  remove = slots.contribute('views', pluginA, recall, 'community');
  steps.push(observation('reentry'));

  stop();
  assert.equal(appRegistry.get(typeId), undefined, 'stop did not unregister mirrored apps');
  assert.equal(api.resolvePluginViewAppTypeId('recall'), null);
  steps.push(observation('mirror-stopped'));
  remove();
  removeBuiltin();

  const secondKernel = { slots };
  const stopSecond = api.mirrorPluginViewApps(secondKernel);
  const removeAlias = slots.contribute('views', 'community.alias-owner', {
    id: 'community.alias-owner/alias-entry', key: 'alias-key',
    value: { id: 'inner-id', titleKey: 'ns:title', component: () => null },
  }, 'community');
  assert.equal(appRegistry.get(api.pluginViewAppTypeId('alias-key')).nameKey, 'ns:title');
  assert.equal(appRegistry.get(api.pluginViewAppTypeId('inner-id')), undefined, 'slot key was not authoritative over value.id');
  const aliasObservation = {
    stage: 'slot-key-authoritative',
    aliasTypeId: api.pluginViewAppTypeId('alias-key'),
    innerTypeId: api.pluginViewAppTypeId('inner-id'),
    definition: describeDefinition(appRegistry.get(api.pluginViewAppTypeId('alias-key'))),
  };
  removeAlias();
  stopSecond();

  const checkedAssertions = [
    'builtin-not-mirrored',
    'mismatched-unregister-preserves-owner',
    'slot-key-overrides-value-id',
    'remove-unregisters',
    'stop-unregisters-and-unsubscribes',
  ];
  assert.ok(steps.length >= 7);
  return {
    replay: {
      production: [
        'mirrorPluginViewApps(kernel)',
        'pluginViewAppTypeId(viewKey)',
        'resolvePluginViewAppTypeId(viewKey)',
        'appRegistry.register/unregister',
        'SlotStore.declare/contribute/unsubscribe',
      ],
      stubs: {
        boundary: 'Only render-only imports are substituted; registration, reconciliation, lookup, slot storage and app registry are production code.',
        symbols: ['react', 'react/jsx-runtime', 'react/jsx-dev-runtime', '@phosphor-icons/react', '@/components/ErrorBoundary', '@/ui-kernel/app-kernel', './PluginViewAppWindow'],
        notStubbed: ['plugin-views/register.tsx', 'core/appRegistry.ts', 'ui-kernel/slots.ts'],
      },
      inputs: built.inputs,
      steps,
      checkedAssertions,
      aliasObservation,
    },
    sources: [
      sourceLine(PLUGIN_VIEW_REGISTER, "export const PLUGIN_VIEW_APP_PREFIX = 'plugin-view.';"),
      sourceLine(PLUGIN_VIEW_REGISTER, "instanceMode: 'single'"),
      sourceLine(PLUGIN_VIEW_REGISTER, "entry.origin !== 'builtin'"),
      sourceLine(PLUGIN_VIEW_REGISTER, 'appRegistry.unregister(typeId, current.def)'),
      sourceLine(PLUGIN_VIEW_REGISTER, 'appRegistry.unregister(typeId, def)'),
    ],
  };
}

async function replayBesidePlacement() {
  const built = await bundleIsolated(`
    export { workbenchBus } from './${WORKBENCH_BUS}';
    export { appRegistry } from './src/features/workbench/core/appRegistry.ts';
    export { resetWindowStoreForTests, useWindowStore } from './src/features/workbench/core/windowStore.ts';
    export { planBesidePlacement } from './${BESIDE_PLACEMENT}';
  `, {
    './agentRuntime': `
      export const actOnAgentWindow = async () => { throw new Error('stub: agent action'); };
      export const getAgentCapabilities = () => ({ capabilities: [] });
      export const observeAgentWindow = () => ({ windows: [] });
      export const revertAgentUndo = async () => ({ reverted: false });
      export const waitForAgentCondition = async () => ({ satisfied: false, timedOut: true });
    `,
    '../apps/notes/workspaceRegistry': `
      export const activateWorkspaceResource = async () => ({ windowId: null, result: { handled: false } });
      export const requestWorkspaceResource = async () => null;
    `,
    '../apps/content/resourceWorkspaceRegistry': `
      export const requestResourceWorkspace = async () => null;
    `,
    './windowCloseGuard': `
      export const confirmWindowClose = async () => true;
    `,
  }, 's0-04-beside-replay');

  const { appRegistry, workbenchBus, useWindowStore, resetWindowStoreForTests, planBesidePlacement } = built.exports;
  const register = (typeId, instanceMode, minSize) => {
    if (appRegistry.get(typeId)) return;
    appRegistry.register({
      typeId, nameKey: `workbench:test.${typeId}`, icon: null, instanceMode,
      memoryWeight: 1, defaultFrame: { w: 1080, h: 720 }, minSize,
      render: null,
    });
  };
  register('s0-04-chat', 'single', { w: 640, h: 460 });
  register('s0-04-reader', 'multi', { w: 420, h: 320 });
  workbenchBus.setEnabled(true);

  resetWindowStoreForTests({ w: 1920, h: 1000 });
  const anchorId = useWindowStore.getState().openWindow({
    typeId: 's0-04-chat', instanceKey: 'sess_fixture',
    initialFrame: { x: 48, y: 48, w: 1080, h: 720 },
  });
  const readerId = workbenchBus.launch({
    typeId: 's0-04-reader', instanceKey: 'file_paper',
    placement: { mode: 'beside', anchorWindowId: anchorId }, reason: 'api',
  });
  assert.ok(readerId);
  let state = useWindowStore.getState();
  assert.equal(state.windows[anchorId].displayMode, 'tiled-left');
  assert.equal(state.windows[readerId].displayMode, 'tiled-right');
  assert.equal(state.focusStack.at(-1), readerId);
  assert.equal(typeof readerId, 'string');
  assert.notEqual(readerId, anchorId);
  assert.equal(new Set(Object.keys(state.windows)).size, 2);
  const newWindow = {
    id: '<reader-window>',
    anchorId: '<anchor-window>',
    anchorMode: state.windows[anchorId].displayMode,
    anchorRestoreFrame: state.windows[anchorId].restoreFrame,
    resourceMode: state.windows[readerId].displayMode,
    resourceInstanceKey: state.windows[readerId].instanceKey,
    focusMatchesReader: state.focusStack.at(-1) === readerId,
  };

  resetWindowStoreForTests({ w: 1920, h: 1000 });
  const existingAnchorId = useWindowStore.getState().openWindow({
    typeId: 's0-04-chat', instanceKey: 'sess_fixture',
    initialFrame: { x: 48, y: 48, w: 1080, h: 720 },
  });
  const existingReaderId = useWindowStore.getState().openWindow({
    typeId: 's0-04-reader', instanceKey: 'file_paper',
    initialFrame: { x: 300, y: 120, w: 700, h: 600 },
  });
  useWindowStore.getState().focusWindow(existingAnchorId);
  const before = useWindowStore.getState().windows[existingReaderId];
  const anchorBefore = useWindowStore.getState().windows[existingAnchorId];
  const returnedId = workbenchBus.launch({
    typeId: 's0-04-reader', instanceKey: 'file_paper',
    placement: { mode: 'beside', anchorWindowId: existingAnchorId }, reason: 'api',
  });
  state = useWindowStore.getState();
  assert.equal(returnedId, existingReaderId);
  assert.deepEqual(state.windows[existingReaderId].frame, before.frame);
  assert.equal(state.windows[existingReaderId].displayMode, before.displayMode);
  assert.deepEqual(state.windows[existingAnchorId].frame, anchorBefore.frame);
  assert.equal(state.windows[existingAnchorId].displayMode, anchorBefore.displayMode);
  assert.equal(state.focusStack.at(-1), existingReaderId);
  assert.equal(typeof returnedId, 'string');
  assert.notEqual(returnedId, existingAnchorId);
  assert.equal(new Set(Object.keys(state.windows)).size, 2);
  const existingWindow = {
    id: '<reader-window>',
    anchorId: '<anchor-window>',
    sameWindowReused: returnedId === existingReaderId,
    frameUnchanged: true,
    displayModeUnchanged: true,
    anchorFrameUnchanged: true,
    anchorModeUnchanged: true,
    focusMatchesReader: state.focusStack.at(-1) === returnedId,
  };

  const plannerCases = {
    straddlingFloat: planBesidePlacement({
      anchor: { ...state.windows[existingAnchorId], frame: { x: 48, y: 48, w: 1080, h: 720 }, displayMode: 'floating' },
      desktopSize: { w: 1920, h: 1000 }, resourceMinSize: { w: 420, h: 320 }, anchorMinSize: { w: 640, h: 460 },
    }),
    noAnchor: planBesidePlacement({
      anchor: null, desktopSize: { w: 1920, h: 1000 }, resourceMinSize: { w: 420, h: 320 }, anchorMinSize: { w: 640, h: 460 },
    }),
    minimizedAnchor: planBesidePlacement({
      anchor: { ...state.windows[existingAnchorId], minimized: true },
      desktopSize: { w: 1920, h: 1000 }, resourceMinSize: { w: 420, h: 320 }, anchorMinSize: { w: 640, h: 460 },
    }),
  };
  assert.deepEqual(plannerCases.straddlingFloat, { anchorMode: 'tiled-left', resourceMode: 'tiled-right' });
  assert.equal(plannerCases.noAnchor, null);
  assert.equal(plannerCases.minimizedAnchor, null);

  return {
    interfaceSource: sourceLine(TYPES_FILE, 'export interface LaunchPlacement'),
    requestFieldSource: sourceLine(TYPES_FILE, 'placement?: LaunchPlacement;'),
    plannerSource: sourceLine(BESIDE_PLACEMENT, 'export function planBesidePlacement'),
    launcherSource: sourceLine(WORKBENCH_BUS, 'function launchBeside(req: LaunchRequest): string'),
    existingBranchSource: sourceLine(WORKBENCH_BUS, 'if (findExistingLaunchTarget(req.typeId, instanceKey)) return store.openWindow(base);'),
    planner: { pure: true, module: BESIDE_PLACEMENT, cases: plannerCases },
    replay: {
      production: ['workbenchBus.launch', 'planBesidePlacement', 'useWindowStore.openWindow/focusWindow', 'appRegistry.get'],
      stubs: {
        boundary: 'Only unrelated agent, notes/content workspace and close-guard imports are substituted; launch placement, window store, tiling and app registry are production code.',
        symbols: ['./agentRuntime', '../apps/notes/workspaceRegistry', '../apps/content/resourceWorkspaceRegistry', './windowCloseGuard'],
      },
      inputs: built.inputs,
      newWindow,
      existingWindow,
      checkedAssertions: [
        'new-reader-window-is-distinct',
        'existing-reader-is-focused-not-moved',
        'no-anchor-falls-back-to-cascade',
        'minimized-anchor-falls-back-to-cascade',
      ],
    },
  };
}

function collectContracts() {
  const contractFiles = [
    TYPES_FILE,
    WORKBENCH_BUS,
    BESIDE_PLACEMENT,
    'src/features/workbench/core/windowCloseGuard.ts',
    'src/features/workbench/core/snapshot.ts',
    'src/features/workbench/core/scheduler.ts',
    'src/features/workbench/core/snapshotWindowPolicy.ts',
    'src/features/workbench/apps/content/typeMap.ts',
    'src/features/workbench/apps/content/createContentApp.tsx',
    'src/features/workbench/apps/notes/workspaceRegistry.ts',
  ];
  const interfaces = [];
  const sf = parse(TYPES_FILE);
  const names = ['ActivationContext', 'AppDefinition', 'LaunchRequest', 'LaunchPlacement', 'ActivateRequest', 'WorkbenchSnapshotV1', 'WorkbenchWindow'];
  visit(sf, node => {
    if (ts.isInterfaceDeclaration(node) && names.includes(node.name.text)) {
      interfaces.push({ name: node.name.text, source: node.getText(sf), evidence: location(TYPES_FILE, sf, node) });
    }
  });
  assert.deepEqual(interfaces.map(item => item.name).sort(), [...names].sort());
  return { contractFiles, interfaces };
}

async function collect() {
  const staticData = collectStaticApps();
  const pluginFamily = await replayPluginViewFamily();
  const beside = await replayBesidePlacement();
  const contracts = collectContracts();
  const apps = buildStaticApps(staticData.definitionRows, staticData.golden);
  const sourceFilesForHash = [
    ...staticData.registrationFiles,
    PLUGIN_VIEW_WINDOW,
    WINDOW_BODY,
    'src/features/workbench/apps/registerAll.ts',
    STATIC_GOLDEN,
    ...contracts.contractFiles,
  ];

  return {
    formatVersion: 2,
    unit: 'S0.04',
    staticRegistry: {
      owner: 'dstu.ui.workbench official static app registrations',
      count: apps.length,
      golden: STATIC_GOLDEN,
      registrationFiles: staticData.staticRegistrationFiles,
      dynamicRegistrationFile: PLUGIN_VIEW_REGISTER,
      rule: 'Only literal/const-resolved official registrations enter this 22-app set. Dynamic community plugin-view definitions are verified in their own family and are never merged into the official golden.',
    },
    apps,
    dynamicFamily: {
      id: 'plugin-view',
      prefix: PLUGIN_VIEW_PREFIX,
      staticGoldenMembership: false,
      discovery: {
        source: PLUGIN_VIEW_REGISTER,
        windowHost: PLUGIN_VIEW_WINDOW,
        registrationEntry: 'registerAllWorkbenchApps() → registerPluginViewApps() → mirrorPluginViewApps(kernel)',
        slot: { name: 'views', cardinality: 'keyed', ownerField: 'entry.owner', originField: 'entry.origin' },
        memberKey: 'entry.key ?? entry.value.id',
        excludes: 'entry.origin === builtin',
      },
      owner: {
        source: 'contributing community plugin id from the views slot entry',
        panelAuthority: 'appRegistry definition owner is the mirrored family; view-level plugin scope remains entry.owner',
        replacement: 'same viewKey with a different owner/value unregisters the old definition before registering the replacement',
      },
      singleton: {
        instanceMode: 'single',
        scope: 'one window per plugin-view.<viewKey> typeId; distinct community views may coexist',
        instanceKey: null,
      },
      resourceIdentity: {
        family: PLUGIN_VIEW_PREFIX,
        viewKey: 'entry.key ?? entry.value.id',
        typeId: 'plugin-view.<viewKey>',
        owner: 'slot provenance and rendering scope; not an extra panel dimension',
        resourceKey: 'the keyed views slot guarantees one live contribution per viewKey; a viewKey is a community view identity, not an official ResourceType',
      },
      reconciliation: {
        register: 'new community entry → appRegistry.register(plugin-view.<viewKey>)',
        unchanged: 'same entry.value identity and owner → no registry churn',
        update: 'owner/value change for the same viewKey → unregister old then register new',
        remove: 'slot entry removal → appRegistry.unregister(typeId, mirroredDefinition), leaving unrelated definitions untouched',
        stop: 'unsubscribe views and unregister every mirrored definition; later slot changes do not resurrect apps',
      },
      draft: {
        mirroredDefinitionCallbacks: { canClose: false, canSuspend: false, prepareSuspend: false },
        panelState: 'plugin content and plugin storage own view state; the shell owns only window layout',
        close: 'requestClose uses the shell default because the mirrored definition has no app-specific close guard',
      },
      snapshot: {
        version: 1,
        identity: ['typeId', 'instanceKey'],
        persisted: ['plugin-view.<viewKey> typeId', 'title', 'frame', 'restoreFrame', 'displayMode', 'minimized', 'zIndex', 'timestamps'],
        excludes: ['launchPayload', 'lifecycle', 'live handles', 'plugin component state'],
        restore: 'the shell may restore the layout entry by typeId before the plugin provider exists; WindowBody then takes the appRegistry miss branch (data-wb-unknown-app / unknownApp copy) and does not mount PluginViewAppWindow. The missing-view notice belongs to PluginViewAppWindow after a registered app loses its slot entry.',
        missingProvider: {
          condition: 'no live mirrored appDefinition for the restored typeId',
          source: sourceLine(WINDOW_BODY, 'const def = useMemo(() => (win ? appRegistry.get(win.typeId) : undefined), [win?.typeId]);'),
          branch: sourceLine(WINDOW_BODY, 'if (!def) {'),
          renders: 'WindowBody data-wb-unknown-app with workbench:window.unknownApp; PluginViewAppWindow is not mounted',
        },
        missingViewAfterUnmount: {
          condition: 'PluginViewAppWindow is already mounted and the registered app remains, while its views slot entry is revoked',
          source: sourceLine(PLUGIN_VIEW_WINDOW, 'wb-plugin-view-missing'),
          renders: 'PluginViewAppWindow unavailable notice',
        },
        draft: 'not stored by the workbench snapshot; plugin data must use plugin-owned portable/local storage',
      },
      back: {
        panel: 'close returns to the prior shell focus stack; no mirrored app-specific back stack',
        view: 'internal navigation remains the plugin view host responsibility',
      },
      nativeDelivery: [
        { unit: 'S6.34', responsibility: 'dynamic plugin view rendering, owner scope, slot/view/lifecycle removal' },
        { unit: 'S6.43', responsibility: 'formal workbench panel host, layout, focus and close integration' },
        { unit: 'S7.37', responsibility: 'per-package installation/run/update/revoke evidence, including official UI contribution coverage' },
      ],
      acceptance: [
        { id: 'WB-plugin-view-register', expected: 'community views register plugin-view.<viewKey> with single instance mode and plugin name scope' },
        { id: 'WB-plugin-view-builtin-exclusion', expected: 'builtin views never enter the dynamic family' },
        { id: 'WB-plugin-view-update', expected: 'owner/value replacement swaps the definition without changing the typeId mapping' },
        { id: 'WB-plugin-view-remove', expected: 'slot removal unregisters only the mirrored definition' },
        { id: 'WB-plugin-view-reentry', expected: 're-adding the entry restores the same typeId and a fresh definition' },
        { id: 'WB-plugin-view-snapshot', expected: 'layout snapshot keeps typeId; component/draft/payload are not persisted' },
      ],
      replay: pluginFamily.replay,
      sources: pluginFamily.sources,
    },
    defaultDockPinned: staticData.golden.dockDefaultPinned,
    exclusions: [
      { appId: 'mindmap', reason: 'definition exists but registerAll intentionally routes mindmaps through notes', androidHost: 'notes' },
      { appId: 'desktop', reason: 'virtual Agent target; not a registered UI app', androidHost: 'workspace' },
      { appId: 'plugin-view.*', reason: 'dynamic community family; not an official static app and not part of apps.json golden', androidHost: 'workspace/panel/{panelId}' },
    ],
    resourceKey: {
      fields: { domain: 'string', resourceId: 'stable shared id', viewKind: 'appId/content perspective', revision: 'optional domain version' },
      panelIdentity: 'appId + instanceKey; resourceKey remains distinct from panel id',
      shared: 'domain records and resource bytes',
      local: 'layout, draft, view state and running task ownership',
    },
    launchPlacement: {
      field: 'LaunchRequest.placement',
      modes: ['beside'],
      defaultAnchor: 'focused window when anchorWindowId is absent',
      newWindow: 'plan and place the new window beside the source without covering it when feasible; may tile or minimally move the source according to the source layout and available space',
      existingWindow: 'focus the existing identity only; never move the existing window and never change the source window mode',
      dropPointPrecedence: 'dropPoint takes precedence over beside placement',
      sourceHash: sourceHash(BESIDE_PLACEMENT),
      sources: [beside.interfaceSource, beside.requestFieldSource, beside.plannerSource, beside.launcherSource, beside.existingBranchSource],
      planner: beside.planner,
      replay: beside.replay,
      checkedAssertions: beside.replay.checkedAssertions,
    },
    contracts: contracts.interfaces,
    sourceHashes: [...new Set(sourceFilesForHash)].sort().map(sourceHash),
  };
}

export async function run(mode) {
  const data = await collect();
  artifact('config/baseline/workbench.json', data, mode);
  log('workbench.accepted', {
    staticApps: data.apps.length,
    dynamicFamily: data.dynamicFamily.id,
    dynamicSteps: data.dynamicFamily.replay.steps.length,
    dynamicAssertions: data.dynamicFamily.replay.checkedAssertions.length,
    placementAssertions: data.launchPlacement.checkedAssertions.length,
    contracts: data.contracts.length,
    cases: data.apps.length * 2 + data.dynamicFamily.acceptance.length,
  });
}

main(import.meta.url, run);
