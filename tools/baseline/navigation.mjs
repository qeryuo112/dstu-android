import assert from 'node:assert/strict';
import { artifact, main, parse, ts, visit, unwrap, value, location, readDesktop, assertUnique, log } from './lib.mjs';

export const viewTargets = {
  dashboard: ['features/productivity', 'home/dashboard', 'S7.34'],
  settings: ['features/system', 'settings', 'S7.35'],
  'task-dashboard': ['features/study', 'study/card-tasks', 'S7.08'],
  'skills-management': ['features/chat', 'agent/skills', 'S3.20'],
  'data-management': ['features/system', 'data/overview', 'S8.14'],
  'template-management': ['features/study', 'study/templates', 'S7.09'],
  'ui-lab': ['core/design', 'debug/ui-lab', 'S5.03'],
  'learning-hub': ['features/knowledge', 'knowledge/library', 'S7.01'],
  media: ['features/knowledge', 'media/library', 'S7.31'],
  'sandbox-workbench': ['features/workspace', 'workspace', 'S5.26'],
  'pdf-reader': ['features/knowledge', 'knowledge/pdf/{resourceId}', 'S7.06'],
  todo: ['features/productivity', 'productivity/todo', 'S7.25'],
  flashcards: ['features/study', 'study/flashcards', 'S7.12'],
  'crepe-demo': ['core/design', 'debug/editor', 'S5.13'],
  'chat-v2-test': ['features/chat', 'debug/chat', 'S3.29'],
  'llm-playground': ['features/chat', 'debug/models', 'S3.08'],
  'chat-v2': ['features/chat', 'chat/sessions', 'S3.27'],
};

function title(key, language) {
  const [namespace, path] = key.split(':');
  let current = JSON.parse(readDesktop(`src/locales/${language}/${namespace}.json`));
  for (const part of path.split('.')) current = current[part];
  assert.equal(typeof current, 'string', `${language}: ${key}`);
  return current;
}

export function collect() {
  const file = 'src/ui-plugins/official/shell/index.ts';
  const sf = parse(file);
  const arrays = {};
  visit(sf, node => {
    if (ts.isVariableDeclaration(node) && ['views', 'nav'].includes(node.name.getText())) {
      let array = null;
      visit(node.initializer, child => { if (array === null && ts.isArrayLiteralExpression(child)) array = child; });
      arrays[node.name.getText()] = array.elements.map((entry, index) => ({ ...value(unwrap(entry)), order: (index + 1) * 10, evidence: location(file, sf, entry) }));
    }
  });
  assert.equal(arrays.views.length, 17);
  assert.equal(arrays.nav.length, 9);
  assertUnique(arrays.views, item => item.id);
  const views = arrays.views.map(view => {
    const [module, route, deliveryUnit] = viewTargets[view.id];
    return {
      ...view, title: { zh: title(view.titleKey, 'zh-CN'), en: title(view.titleKey, 'en-US') },
      visibility: view.visible === undefined ? 'all-builds' : view.visible.expression.includes('uiLabEnabled') ? 'uiLabEnabled' : 'debug-build',
      hostCondition: ['task-dashboard', 'template-management', 'flashcards'].includes(view.id) ? 'dstu.anki available; unavailable state uses host-status surface' : 'official-shell',
      keepAlive: true, shell: { sidebarKind: 'main', mobileBackToHome: true, ...view.shell },
      android: { module, route, deliveryUnit, phone: 'native-page-in-panel-stack', tablet: 'native-workbench-panel', return: view.id === 'settings' || view.id === 'todo' ? 'section-stack → caller → home' : 'caller → home' },
      acceptance: { id: `NAV-${view.id}`, normal: 'open from declared entry, verify title/order and back path', exceptional: 'recreate Activity and preserve selected route; enforce visibility and host condition' },
    };
  });
  const nav = arrays.nav.map(item => {
    const target = views.find(view => view.id === item.viewId);
    assert.ok(target);
    return { ...item, title: { zh: title(item.labelKey, 'zh-CN'), en: title(item.labelKey, 'en-US') }, visibility: item.visible === undefined ? 'all-builds' : 'uiLabEnabled', androidRoute: target.android.route, androidPlacement: item.viewId === 'settings' ? 'settings-entry' : 'main-navigation', action: item.desktop?.action === 'new-session' ? 'create-session-then-open' : 'activate-view' };
  });
  return { formatVersion: 1, unit: 'S0.02', baseline: 'desktop', views, navigation: nav, rules: { order: 'source array order × 10', debug: 'development-only views require Android debug build', uiLab: 'explicit UI laboratory setting', phone: 'drawer or panel stack with the same feature order', tablet: 'sidebar and workspace panels', desktopOnlyViewImplementation: 'source evidence; Kotlin and Compose own Android routes' } };
}
export function run(mode) {
  const inventory = collect();
  artifact('config/baseline/navigation.json', inventory, mode);
  log('navigation.accepted', { views: inventory.views.length, nav: inventory.navigation.length });
}
main(import.meta.url, run);
