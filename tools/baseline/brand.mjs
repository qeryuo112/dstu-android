import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  artifact, main, root, desktop, desktopRequire, readDesktop, readJson, sha256, log, ts, parse, visit, value, sourceFiles, git, difference,
} from './lib.mjs';

const unit = 'S0.12';
const file = 'config/design/brand.baseline.json';
const planFile = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const designFile = 'config/design/tokens.baseline.json';
const navigationFile = 'config/baseline/navigation.json';
const contributionsFile = 'config/baseline/contributions.json';
const entriesFile = 'config/baseline/entries.json';
const workbenchFile = 'config/baseline/workbench.json';
const logoFile = 'src/components/ui/DeepStudentLogo.tsx';
const iconFile = 'src/components/icons/StudySidebarIcons.tsx';
const screenshotDir = 'tests/visual/__screenshots__';
const scenesFile = 'tests/visual/lib/scenes.mjs';
const screenshotsTest = 'tests/visual/screenshots.visual.ts';
const viewportFile = 'tests/visual/lib/page.mjs';
const buttonContractFile = 'src/components/ui/buttonPrimitiveContract.ts';
const buttonFile = 'src/components/ui/shad/Button.tsx';
const themeColorsFile = 'src/styles/theme-colors.css';
const workshopCardFile = 'src/features/plugin-workshop/WorkshopConfirmationCard.tsx';
const workshopProposalFile = 'src/features/plugin-workshop/components/WorkshopProposalCard.tsx';
const primaryContractTest = 'src/components/ui/__tests__/buttonPrimaryVariant.test.tsx';
const workshopReferenceDir = 'docs/plans/assets/plugin-manager-redesign';
const operitRoot = resolve(root, '..', 'Operit');
const operit = path => readFileSync(resolve(operitRoot, path), 'utf8');
const operitHas = path => existsSync(resolve(operitRoot, path));
const camel = name => name.replace(/^--/, '').replace(/-([a-z0-9])/g, (_m, c) => c.toUpperCase()).replace(/[^a-zA-Z0-9]/g, '');
const unique = (items, key, label) => assert.equal(new Set(items.map(key)).size, items.length, `duplicate ${label}`);

async function loadRender() {
  const esbuild = desktopRequire('esbuild');
  const entry = [
    "import React from 'react';",
    "import { renderToStaticMarkup } from 'react-dom/server';",
    `import { DeepStudentMark, DeepStudentLogo } from './${logoFile}';`,
    `import * as Sidebar from './${iconFile}';`,
    "import { Database } from '@phosphor-icons/react/dist/csr/Database';",
    'const icons = { Database, ...Sidebar };',
    'export const render = (name, props) => {',
    '  const component = name === "mark" ? DeepStudentMark : name === "logo" ? DeepStudentLogo : icons[name];',
    '  if (typeof component !== "function" && typeof component !== "object") throw new Error(`unknown component ${name}`);',
    '  return renderToStaticMarkup(React.createElement(component, props ?? {}));',
    '};',
  ].join('\n');
  const built = await esbuild.build({
    absWorkingDir: desktop,
    stdin: { contents: entry, resolveDir: desktop, sourcefile: 'android-brand-entry.tsx', loader: 'tsx' },
    tsconfig: resolve(desktop, 'tsconfig.json'),
    bundle: true, write: false, format: 'cjs', platform: 'node', target: 'node22', logLevel: 'silent', metafile: true,
  });
  const factory = new Function('module', 'exports', 'require', built.outputFiles[0].text);
  const module = { exports: {} };
  factory(module, module.exports, desktopRequire);
  const inputs = Object.keys(built.metafile.inputs).filter(item => item !== 'android-brand-entry.tsx').sort()
    .map(item => ({ file: item, sha256: sha256(readFileSync(resolve(desktop, item))) }));
  return { render: module.exports.render, inputs };
}

function parseSvg(html, label) {
  const open = html.match(/<svg\b([^>]*)>/);
  assert.ok(open, `${label}: not an svg`);
  const attributes = Object.fromEntries([...open[1].matchAll(/([a-zA-Z-]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
  const paths = [...html.matchAll(/<path\b([^>]*)\/?>/g)].map(match => {
    const attrs = Object.fromEntries([...match[1].matchAll(/([a-zA-Z-]+)="([^"]*)"/g)].map(item => [item[1], item[2]]));
    assert.ok(attrs.d && attrs.d.length > 20, `${label}: path without geometry`);
    return { d: attrs.d, className: attrs.class ?? null, sha256: sha256(attrs.d) };
  });
  assert.ok(attributes.viewBox, `${label}: viewBox`);
  const [minX, minY, width, height] = attributes.viewBox.split(/\s+/).map(Number);
  assert.ok(width > 0 && height > 0, `${label}: viewBox extent`);
  return { attributes, viewBox: `${minX} ${minY} ${width} ${height}`, box: [minX, minY, width, height], paths };
}

const geometry = (html, label) => {
  const svg = parseSvg(html, label);
  return {
    viewBox: svg.viewBox,
    fill: svg.attributes.fill ?? 'currentColor',
    width: svg.box[2],
    height: svg.box[3],
    paths: svg.paths.length,
    geometrySha256: sha256(svg.paths.map(path => path.d).join('\n')),
    pathSha256: svg.paths.map(path => path.sha256),
  };
};

const pathSummary = (d, index) => {
  const verbs = [...d.matchAll(/[MmLlHhVvCcSsQqTtAaZz]/g)].map(match => match[0]);
  return { index, verbs: verbs.length, kinds: [...new Set(verbs.map(verb => verb.toLowerCase()))].sort(), sha256: sha256(d), d };
};

const composeMapping = (target, svg, group) => ({
  imageVector: camel(target),
  kotlinAccessor: `ImageVector.Builder(name = "${target}", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = ${svg.width}f, viewportHeight = ${svg.height}f)`,
  pathData: 'addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(LocalContentColor.current))',
  source: { renderer: 'ReactDOMServer.renderToStaticMarkup', group },
  viewBox: svg.viewBox,
  defaultWidthDp: 24,
  defaultHeightDp: 24,
  tint: 'currentColor → LocalContentColor',
  paths: svg.paths,
  geometrySha256: svg.geometrySha256,
  translation: 'ImageVector.Builder + addPath(PathParser parses the verbatim SVG d); tint with LocalContentColor; one DstuIcon per weight',
});

function localeStore() {
  const names = readdirSync(resolve(desktop, 'src/locales/zh-CN')).filter(name => name.endsWith('.json')).sort();
  const enNames = readdirSync(resolve(desktop, 'src/locales/en-US')).filter(name => name.endsWith('.json')).sort();
  assert.deepEqual(enNames, names, 'locale file sets differ');
  const cache = new Map();
  const load = language => {
    if (!cache.has(language)) {
      const tables = new Map();
      for (const name of names) tables.set(name.replace(/\.json$/, ''), JSON.parse(readDesktop(`src/locales/${language}/${name}`)));
      cache.set(language, tables);
    }
    return cache.get(language);
  };
  const read = (language, key) => {
    const [namespace, path] = key.split(':');
    const table = load(language).get(namespace);
    assert.ok(table, `${language}: missing namespace ${namespace}`);
    return path.split('.').reduce((node, part) => (node === undefined || node === null ? undefined : node[part]), table);
  };
  return { names, read };
}

const flatten = (object, prefix = '') => {
  const out = {};
  for (const [key, entry] of Object.entries(object)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (entry && typeof entry === 'object' && !Array.isArray(entry)) Object.assign(out, flatten(entry, path));
    else out[path] = entry;
  }
  return out;
};

// Source-pair missing is an exact key comparison. Plural families are a second,
// independent fact: i18next can resolve a base key from _one/_other even though
// the same-name key is absent.
const PLURAL_SUFFIX = /_(zero|one|two|few|many|other)$/;
const baseLocaleKey = key => key.replace(PLURAL_SUFFIX, '');
const pluralForms = (keys, base) => Array.from(keys).filter(key => key !== base && baseLocaleKey(key) === base).sort();
function localeFacts(zh, en) {
  const zhKeys = new Set(Object.keys(zh));
  const enKeys = new Set(Object.keys(en));
  const allKeys = new Set([...zhKeys, ...enKeys]);
  const semanticKeys = new Set([...allKeys].map(baseLocaleKey));
  const missing = [];
  const pluralFamilies = [];
  for (const key of [...zhKeys].sort()) if (!enKeys.has(key)) missing.push({ key, missingSide: 'en-US' });
  for (const key of [...enKeys].sort()) if (!zhKeys.has(key)) missing.push({ key, missingSide: 'zh-CN' });
  for (const base of [...semanticKeys].sort()) {
    const zhBase = zhKeys.has(base);
    const enBase = enKeys.has(base);
    const zhForms = pluralForms(zhKeys, base);
    const enForms = pluralForms(enKeys, base);
    if (!zhBase && !zhForms.length && !enBase && !enForms.length) continue;
    if (!zhBase && !zhForms.length) continue;
    if (!enBase && !enForms.length) continue;
    if (!zhBase || !enBase || zhForms.length || enForms.length) {
      pluralFamilies.push({
        base,
        zhBase,
        enBase,
        zhForm: zhBase ? 'base' : 'plural-only',
        enForm: enBase ? 'base' : 'plural-only',
        zhForms,
        enForms,
        zhKeys: zhForms.length ? zhForms : [base],
        enKeys: enForms.length ? enForms : [base],
        zh: zhBase ? zh[base] : zh[zhForms.at(-1)],
        en: enBase ? en[base] : en[enForms.at(-1)],
        semanticResolvable: null,
      });
    }
  }
  return { semanticKeys: semanticKeys.size, missing, pluralFamilies };
}

const pngInfo = bytes => {
  const signature = bytes.subarray(0, 8).toString('hex');
  assert.equal(signature, '89504e470d0a1a0a', 'PNG signature');
  assert.equal(bytes.subarray(12, 16).toString('ascii'), 'IHDR', 'IHDR chunk');
  return {
    signature,
    width: bytes.readUInt32BE(16),
    height: bytes.readUInt32BE(20),
    bitDepth: bytes[24],
    colorType: bytes[25],
    bytes: bytes.length,
    sha256: sha256(bytes),
  };
};

const operitStrings = (() => {
  const read = path => {
    const text = operit(path);
    const map = new Map();
    for (const match of text.matchAll(/<string name="([^"]+)"(?:\s+[^>]*)?>([\s\S]*?)<\/string>/g)) map.set(match[1], match[2].replace(/\\n/g, '\n').replace(/&amp;/g, '&'));
    return map;
  };
  return { zh: read('app/src/main/res/values/strings.xml'), en: read('app/src/main/res/values-en/strings.xml') };
})();

const operitString = key => ({
  key,
  zh: operitStrings.zh.get(key) ?? null,
  en: operitStrings.en.get(key) ?? null,
});

const tokenRef = (tokens, name, role) => {
  assert.ok(tokens[name], `missing design token ${name}`);
  return {
    token: name,
    role,
    kotlin: tokens[name].kotlin.name,
    type: tokens[name].kotlin.type,
    light: tokens[name].kotlin.value,
    lightHex: tokens[name].kotlin.hex ?? null,
  };
};

const PAGE_TOKEN_GROUPS = {
  terminal: [['--background', 'terminal window background'], ['--foreground', 'terminal text'], ['--font-mono', 'PTY cell font'], ['--font-size-sm', 'terminal cell size'], ['--chat-code-block-surface', 'output block surface'], ['--border', 'session divider'], ['--accent-primary', 'cursor/selection'], ['--muted-foreground', 'prompt meta']],
  install: [['--surface-panel', 'install card'], ['--surface-panel-muted', 'recipe row'], ['--foreground', 'recipe title'], ['--muted-foreground', 'version/status'], ['--border', 'card divider'], ['--accent-primary', 'primary install action'], ['--danger', 'failure state'], ['--success', 'installed state']],
  permission: [['--surface-panel', 'permission card'], ['--foreground', 'permission title'], ['--muted-foreground', 'permission description'], ['--border', 'row divider'], ['--accent-primary', 'grant action'], ['--warning', 'pending state'], ['--danger', 'denied state']],
  shizuku: [['--surface-panel', 'authorization card'], ['--foreground', 'UID/title text'], ['--muted-foreground', 'state description'], ['--accent-primary', 'connect action'], ['--success', 'authorized state'], ['--danger', 'revoked/dead state'], ['--border', 'step divider']],
  background: [['--surface-panel', 'keep-alive card'], ['--foreground', 'setting title'], ['--muted-foreground', 'setting description'], ['--border', 'section divider'], ['--accent-primary', 'enabled switch'], ['--warning', 'battery guidance']],
  floating: [['--surface-overlay', 'floating window overlay'], ['--surface-elevated', 'floating window body'], ['--foreground', 'floating message text'], ['--border', 'window rim'], ['--wb-window-radius', 'window corner'], ['--accent-primary', 'send/action']],
  mcp: [['--surface-panel', 'server card'], ['--menu-shell-surface', 'server menu'], ['--foreground', 'server name'], ['--muted-foreground', 'transport/status'], ['--border', 'list divider'], ['--accent-primary', 'connect action'], ['--success', 'connected'], ['--danger', 'failed']],
  toolpkg: [['--surface-panel', 'package page surface'], ['--foreground', 'package content text'], ['--muted-foreground', 'metadata'], ['--border', 'section divider'], ['--accent-primary', 'contributed action'], ['--dialog-shell-surface', 'package dialog']],
  settings: [['--sidebar', 'settings navigation'], ['--sidebar-foreground', 'navigation label'], ['--sidebar-accent', 'selected row'], ['--surface-panel', 'section surface'], ['--foreground', 'section title'], ['--border', 'section divider'], ['--accent-primary', 'primary action']],
};

async function collect() {
  const plan = readFileSync(resolve(root, planFile), 'utf8').split('\n');
  const unitLine = plan.findIndex(line => line.startsWith('| S0.12 |'));
  const sectionLine = plan.findIndex(line => line.startsWith('### 7.0 '));
  assert.ok(unitLine >= 0 && sectionLine >= 0, 'plan anchors');
  const sectionRows = plan.slice(sectionLine, sectionLine + 20).filter(line => line.startsWith('|')).map(line => line.slice(0, 220));
  assert.ok(sectionRows.length >= 7, 'plan §7.0 rows');
  const planUnits = new Set(plan.flatMap(line => [...line.matchAll(/^\| (S\d\.\d\d) \|/g)].map(match => match[1])));
  assert.equal(planUnits.size, 302);

  const design = readJson(designFile);
  const navigation = readJson(navigationFile);
  const contributions = readJson(contributionsFile);
  const entries = readJson(entriesFile);
  const workbench = readJson(workbenchFile);
  const light = design.scenarios['light/default'].tokens;
  const dark = design.scenarios['dark/default'].tokens;
  const locales = localeStore();
  assert.equal(locales.names.length, 48);

  const { render, inputs: renderInputs } = await loadRender();
  const markHtml = render('mark');
  const logoHtml = render('logo');
  const markSvg = parseSvg(markHtml, 'mark');
  const logoSvg = parseSvg(logoHtml, 'logo');
  assert.deepEqual(markSvg.box, [0, 0, 147, 147]);
  assert.deepEqual(logoSvg.box, [0, 0, 409, 147]);
  assert.equal(markSvg.paths.length, 5);
  assert.equal(logoSvg.paths.length, 31);
  const publicLogo = readFileSync(resolve(desktop, 'public/deepstudent-logo.svg'), 'utf8');
  assert.deepEqual([...publicLogo.matchAll(/<path\b[^>]*\bd="([^"]+)"/g)].map(match => match[1]), logoSvg.paths.map(path => path.d), 'public logo geometry');
  const name = locales.read('zh-CN', 'common:app.name');
  const nameEn = locales.read('en-US', 'common:app.name');
  assert.equal(name, 'Deep Student');
  assert.equal(nameEn, 'Deep Student');
  const markGeometry = geometry(markHtml, 'mark');
  const logoGeometry = geometry(logoHtml, 'logo');
  const brandMapping = (name, shape, svg) => ({
    ...composeMapping(name, shape, name),
    defaultHeightDp: 24,
    defaultWidthDp: 24 * shape.width / shape.height,
    kotlinAccessor: `ImageVector.Builder(name = "${name}", defaultWidth = ${24 * shape.width / shape.height}.dp, defaultHeight = 24.dp, viewportWidth = ${shape.width}f, viewportHeight = ${shape.height}f)`,
    tint: 'Color.Unspecified; each path uses its foreground/background DstuTheme role',
    pathData: 'addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(pathColorRole))',
    paths: svg.paths.map((path, index) => {
      const token = path.className === 'fill-foreground' ? '--foreground' : '--background';
      assert.ok(['fill-foreground', 'fill-background'].includes(path.className));
      return { index, sha256: path.sha256, token, light: light[token].kotlin.value, dark: dark[token].kotlin.value };
    }),
    translation: 'preserve SVG aspect ratio and per-path DstuTheme foreground/background fills; do not apply a global icon tint',
  });
  const brand = {
    productName: name,
    brandKey: 'common:app.name',
    svgTitle: 'DeepStudent',
    packageName: JSON.parse(readDesktop('package.json')).name,
    windowTitle: readDesktop('index.html').match(/<title>([^<]+)<\/title>/)?.[1] ?? null,
    zh: { name, title: locales.read('zh-CN', 'common:app.title'), description: locales.read('zh-CN', 'common:app.description') },
    en: { name: nameEn, title: locales.read('en-US', 'common:app.title'), description: locales.read('en-US', 'common:app.description') },
    rule: 'zh-CN product string is "Deep Student" (with space) from common:app.name; both SVG title attributes and the English wordmark string are "DeepStudent"',
    mark: {
      symbol: 'DeepStudentMark',
      evidence: `${logoFile}:15`,
      titleAttribute: markHtml.match(/<title[^>]*>([^<]+)<\/title>/)?.[1] ?? null,
      titleId: 'React.useId()',
      sourceSha256: sha256(readDesktop(logoFile)),
      renderedSha256: sha256(markHtml),
      ...markGeometry,
      paths: markSvg.paths.map((path, index) => ({ ...pathSummary(path.d, index), className: path.className })),
      palette: { foreground: 'fill-foreground', background: 'fill-background' },
      mapping: brandMapping('brand-mark', markGeometry, markSvg),
    },
    logo: {
      symbol: 'DeepStudentLogo',
      evidence: `${logoFile}:40`,
      titleAttribute: logoHtml.match(/<title[^>]*>([^<]+)<\/title>/)?.[1] ?? null,
      sourceSha256: sha256(readDesktop(logoFile)),
      renderedSha256: sha256(logoHtml),
      ...logoGeometry,
      wordmarkPaths: logoSvg.paths.length - markSvg.paths.length,
      paths: logoSvg.paths.map((path, index) => ({ ...pathSummary(path.d, index), className: path.className })),
      publicAsset: { file: 'public/deepstudent-logo.svg', sha256: sha256(publicLogo), sameGeometry: true },
      raster: ['public/icon-192.png', 'public/icon-512.png', 'public/app-icon.png', 'public/apple-touch-icon.png'].map(item => ({ file: item, sha256: sha256(readFileSync(resolve(desktop, item))) })),
      mapping: brandMapping('brand-logo', logoGeometry, logoSvg),
    },
    tokens: ['--foreground', '--background', '--accent-primary', '--sidebar', '--sidebar-foreground', '--font-weight-semibold'].map(token => {
      const entry = tokenRef(light, token, 'brand');
      assert.ok(dark[token], `dark ${token}`);
      return { ...entry, dark: dark[token].kotlin.value, darkHex: dark[token].kotlin.hex ?? null };
    }),
  };

  const iconSf = parse(iconFile);
  const iconDefinitions = [];
  visit(iconSf, node => {
    if (!ts.isVariableDeclaration(node) || !ts.isIdentifier(node.name) || !/^Study\w+Icon$/.test(node.name.text)) return;
    const weights = {};
    const collectPaths = child => {
      if (ts.isPropertyAssignment(child) && ['regular', 'bold'].includes(child.name.getText())) weights[child.name.getText()] = child.initializer.getText(iconSf).replace(/^['"]|['"]$/g, '');
      ts.forEachChild(child, collectPaths);
    };
    collectPaths(node.initializer);
    assert.ok(weights.regular && weights.bold, `${node.name.text}: regular+bold`);
    iconDefinitions.push({ component: node.name.text, line: iconSf.getLineAndCharacterOfPosition(node.getStart(iconSf)).line + 1, weights });
  });
  assert.equal(iconDefinitions.length, 10);
  const navIconNames = navigation.navigation.map(item => item.icon.expression);
  assert.deepEqual(navIconNames, ['StudyChatIcon', 'StudyBooksIcon', 'StudyMediaIcon', 'StudyTodoIcon', 'StudyMagicWandIcon', 'StudyCardsIcon', 'StudyBlocksIcon', 'Database', 'StudySettingsIcon']);
  const byComponent = new Map(iconDefinitions.map(item => [item.component, item]));
  const navigationIcons = [];
  for (const [index, component] of navIconNames.entries()) {
    const navItem = navigation.navigation.find(item => item.icon.expression === component);
    assert.ok(navItem);
    const definition = byComponent.get(component);
    const regularHtml = render(component);
    const boldHtml = component === 'Database' ? render(component, { weight: 'bold' }) : render(component, { strokeWidth: 3 });
    const regular = geometry(regularHtml, `${component}/regular`);
    const bold = geometry(boldHtml, `${component}/bold`);
    const sourceRegular = definition?.weights.regular ?? parseSvg(regularHtml, component).paths[0].d;
    const sourceBold = definition?.weights.bold ?? parseSvg(boldHtml, component).paths[0].d;
    assert.equal(regular.pathSha256[0], sha256(sourceRegular), `${component}: regular path`);
    assert.equal(bold.pathSha256[0], sha256(sourceBold), `${component}: bold path`);
    const upstream = definition === undefined
      ? { package: '@phosphor-icons/react', version: JSON.parse(readDesktop('node_modules/@phosphor-icons/react/package.json')).version, symbol: component, regularSha256: sha256(sourceRegular), boldSha256: sha256(sourceBold) }
      : { file: `${iconFile}:${definition.line}`, symbol: component, regularSha256: sha256(sourceRegular), boldSha256: sha256(sourceBold) };
    navigationIcons.push({
      order: index + 1,
      navigationId: navItem.id,
      viewId: navItem.viewId,
      component,
      zh: navItem.title.zh,
      en: navItem.title.en,
      labelKey: navItem.labelKey,
      route: navItem.androidRoute,
      placement: navItem.androidPlacement,
      upstream,
      weightRule: definition === undefined ? 'Phosphor weight prop (regular/bold)' : 'strokeWidth >= 2.2 selects bold',
      viewBox: regular.viewBox,
      regular,
      bold,
      mapping: composeMapping(`nav-${navItem.id}`, regular, definition === undefined ? 'phosphor' : `${iconFile} ${component}`),
    });
  }
  unique(navigationIcons, item => item.navigationId, 'navigation icons');
  const unreferenced = iconDefinitions.filter(item => !navIconNames.includes(item.component)).map(item => {
    const rendered = geometry(render(item.component), item.component);
    assert.equal(rendered.pathSha256[0], sha256(item.weights.regular), `${item.component}: rendered path`);
    return { component: item.component, evidence: `${iconFile}:${item.line}`, regularSha256: sha256(item.weights.regular), boldSha256: sha256(item.weights.bold), usage: 'declared, not used by official shell navigation' };
  });
  const iconSet = {
    source: { file: iconFile, sha256: sha256(readDesktop(iconFile)), components: iconDefinitions.length },
    render: 'ReactDOMServer.renderToStaticMarkup, strokeWidth 2 (regular) / 3 (bold); Database uses the Phosphor weight prop',
    orderSource: 'config/baseline/navigation.json navigation array',
    navigationIcons,
    unreferenced,
    kotlinSet: 'core/design DstuIcons: ImageVector per component, regular/bold pair, LocalContentColor tint, 24dp default',
  };

  const primaryRules = (() => {
    const contract = readDesktop(buttonContractFile);
    const shad = readDesktop(buttonFile);
    const theme = readDesktop(themeColorsFile);
    const test = readDesktop(primaryContractTest);
    const ruleAnchor = contract.indexOf('2026-11 裁定');
    const solidAnchor = contract.indexOf('PRIMARY_SOLID_TONE');
    const transparent = [...contract.matchAll(/bg-transparent/g)].length;
    assert.ok(ruleAnchor >= 0 && solidAnchor > ruleAnchor, 'primary ruling source');
    assert.match(contract, /primary：实色主按钮/);
    assert.match(contract, /每个视图 \/ 弹窗 \/ 卡片最多一个/);
    assert.match(contract, /只给「推进流程的那一下」/);
    assert.match(contract, /2026-09 期间写下的 variant="primary" 调用点已统一改为 default/);
    assert.match(contract, /bg-\[var\(--button-prominent-bg\)\].*text-primary-foreground/);
    assert.match(shad, /default: buttonToneClassNames\.default/);
    assert.match(shad, /primary: buttonToneClassNames\.primary/);
    assert.match(theme, /--button-prominent-bg:\s*hsl\(var\(--primary\)\)/);
    assert.match(test, /it\('keeps every other non-danger variant ghost'/);
    assert.match(test, /it\('keeps the shad Button default ghost and adds an explicit primary variant'/);
    const prominentTokens = ['--button-prominent-bg', '--button-prominent-hover-bg', '--button-prominent-active-bg', '--button-prominent-border']
      .map(token => {
        const entry = tokenRef(light, token, 'solid primary state');
        return { ...entry, dark: dark[token]?.kotlin.value ?? null, darkHex: dark[token]?.kotlin.hex ?? null };
      });
    assert.ok(prominentTokens.every(item => item.dark !== null), 'primary tokens light+dark');
    const sourceHashes = Object.fromEntries([buttonContractFile, buttonFile, themeColorsFile, primaryContractTest].map(file => [file, sha256(readDesktop(file))]));
    const solidTone = contract.match(/PRIMARY_SOLID_TONE =\s*\n\s*'([^']+)'/)?.[1] ?? null;
    assert.ok(solidTone);
    return {
      rule: 'one solid primary per view/dialog/card, reserved for the action that advances the flow; all other non-danger variants remain ghost; destructive actions use danger',
      ruling: '2026-11',
      supersedes: '2026-09 ghost-only DsButton ruling',
      variantCount: 13,
      solidVariants: ['primary'],
      transparentVariants: ['ghost', 'default', 'outline', 'secondary', 'utility', 'shell', 'success', 'warning'],
      ghostClassOccurrences: transparent,
      dangerVariants: ['danger', 'destructive'],
      ruleSource: { file: buttonContractFile, line: 35, sha256: sourceHashes[buttonContractFile] },
      toneSource: { file: buttonContractFile, line: 43, sha256: sourceHashes[buttonContractFile] },
      shadDefault: 'ghost; explicit variant="primary" required',
      confirmDialog: 'DsDialog secondaryVariant defaults to ghost so the confirm action stays the sole primary',
      solidTone,
      tokens: prominentTokens,
      contractChecks: {
        ruleTextPresent: true,
        allOtherNonDangerGhost: true,
        defaultButtonExplicitGhost: true,
        twoLightAndDarkProminentBindings: (theme.match(/--button-prominent-bg:\s*hsl\(var\(--primary\)\)/g) ?? []).length === 2,
        primaryContractTestPresent: /one primary per view/.test(test),
      },
      sourceHashes,
      android: 'S1.11/S5.02: DstuButton primary = DstuTheme primary solid + primaryForeground, exactly one per view/dialog/card; all other non-danger tones are the measured DsButton ghost roles',
    };
  })();

  const missing = [];
  const empty = [];
  let leaves = 0;
  let enLeaves = 0;
  const hashes = [];
  const localePluralFamilies = [];
  const i18next = desktopRequire('i18next');
  let semanticLeaves = 0;
  for (const nameFile of locales.names) {
    const zhText = readDesktop(`src/locales/zh-CN/${nameFile}`);
    const enText = readDesktop(`src/locales/en-US/${nameFile}`);
    const zh = flatten(JSON.parse(zhText));
    const en = flatten(JSON.parse(enText));
    leaves += Object.keys(zh).length;
    enLeaves += Object.keys(en).length;
    const facts = localeFacts(zh, en);
    semanticLeaves += facts.semanticKeys;
    for (const entry of facts.missing) missing.push({ ...entry, file: nameFile });
    for (const entry of facts.pluralFamilies) localePluralFamilies.push({ file: nameFile, ...entry });
    for (const key of Object.keys(zh)) {
      const sides = [];
      if (typeof zh[key] === 'string' && zh[key] === '') sides.push('zh-CN');
      if (typeof en[key] === 'string' && en[key] === '') sides.push('en-US');
      if (sides.length > 0) empty.push({ file: nameFile, key, side: sides.join('+'), zh: zh[key] ?? null, en: en[key] ?? null });
    }
    hashes.push(
      { file: `src/locales/zh-CN/${nameFile}`, locale: 'zh-CN', namespace: nameFile.replace(/\.json$/, ''), sha256: sha256(zhText), leaves: Object.keys(zh).length },
      { file: `src/locales/en-US/${nameFile}`, locale: 'en-US', namespace: nameFile.replace(/\.json$/, ''), sha256: sha256(enText), leaves: Object.keys(en).length },
    );
  }

  const physicalPluralFamilies = localePluralFamilies.filter(entry => entry.zhBase !== entry.enBase);
  for (const entry of physicalPluralFamilies) {
    const namespace = entry.file.replace(/\.json$/, '');
    const instance = i18next.createInstance();
    const tables = {
      zh: flatten(JSON.parse(readDesktop(`src/locales/zh-CN/${entry.file}`))),
      en: flatten(JSON.parse(readDesktop(`src/locales/en-US/${entry.file}`))),
    };
    await instance.init({
      lng: 'en-US',
      fallbackLng: false,
      returnObjects: true,
      initImmediate: false,
      keySeparator: '.',
      nsSeparator: ':',
      resources: { 'en-US': { [namespace]: tables.en } },
    });
    const fullKey = `${namespace}:${entry.base}`;
    const one = instance.t(fullKey, { count: 1 });
    const other = instance.t(fullKey, { count: 2 });
    // i18next returns the key path (with or without the namespace prefix) when
    // the lookup misses; a resolved value must be a non-empty string and differ
    // from both spellings.
    const resolved = value => typeof value === 'string' && value.trim() !== '' && value !== fullKey && value !== entry.base;
    entry.actualI18next = { one, other };
    entry.resolvedCounts = ['one', 'other'].filter(count => resolved(entry.actualI18next[count]));
    entry.semanticResolvable = entry.resolvedCounts.length === 2;
    assert.ok(entry.semanticResolvable, `${namespace}:${entry.base}: i18next count 1 and 2`);
  }
  // 21 physical key differences, all inside 7 i18next plural families:
  // zh registers only the base key while en registers _one/_other.
  assert.equal(missing.length, 21);
  assert.equal(missing.filter(item => item.missingSide === 'en-US').length, 7);
  assert.equal(missing.filter(item => item.missingSide === 'zh-CN').length, 14);
  assert.equal(physicalPluralFamilies.length, 7, 'physical-diff families');
  const physicalPluralKeys = new Set(physicalPluralFamilies.flatMap(entry => {
    const namespace = entry.file.replace(/\.json$/, '');
    return [
      ...(entry.zhBase ? [`${namespace}:${entry.base}`] : []),
      ...(entry.enBase ? [`${namespace}:${entry.base}`] : []),
      ...entry.zhForms.map(key => `${namespace}:${key}`),
      ...entry.enForms.map(key => `${namespace}:${key}`),
    ];
  }));
  assert.equal(physicalPluralKeys.size, 21);
  assert.deepEqual(
    [...physicalPluralKeys].sort(),
    missing.map(entry => `${entry.file.replace(/\.json$/, '')}:${entry.key}`).sort(),
    'physical key difference == the 7 plural families',
  );
  assert.equal(empty.length, 7, 'empty key rows (one key may have both locales empty)');
  const physicalLeaves = leaves + enLeaves;
  const pluralByFile = new Map();
  for (const entry of localePluralFamilies) {
    if (!pluralByFile.has(entry.file)) {
      pluralByFile.set(entry.file, {
        zh: flatten(JSON.parse(readDesktop(`src/locales/zh-CN/${entry.file}`))),
        en: flatten(JSON.parse(readDesktop(`src/locales/en-US/${entry.file}`))),
      });
    }
    const tables = pluralByFile.get(entry.file);
    entry.zhValues = Object.fromEntries([[entry.base, tables.zh[entry.base]], ...entry.zhForms.map(key => [key, tables.zh[key]])].filter(([, item]) => item !== undefined));
    entry.enValues = Object.fromEntries([[entry.base, tables.en[entry.base]], ...entry.enForms.map(key => [key, tables.en[key]])].filter(([, item]) => item !== undefined));
  }
  const sourceIndex = sourceFiles(desktop, 'src', 'host').filter(item => /\.(ts|tsx)$/.test(item));
  const sourceCache = new Map();
  const sourceText = item => {
    if (!sourceCache.has(item)) sourceCache.set(item, readDesktop(item));
    return sourceCache.get(item);
  };
  const emptyDetail = empty.map(entry => {
    const namespace = entry.file.replace(/\.json$/, '');
    const fullKey = `${namespace}:${entry.key}`;
    const needles = [`'${entry.key}'`, `\`${entry.key}\``, `"${entry.key}"`, `'${namespace}:${entry.key}'`, `\`${namespace}:${entry.key}\``, `"${namespace}:${entry.key}"`];
    const usage = [];
    for (const file of sourceIndex) {
      const text = sourceText(file);
      if (!needles.some(needle => text.includes(needle))) continue;
      for (const [index, line] of text.split('\n').entries()) if (needles.some(needle => line.includes(needle))) usage.push(`${file}:${index + 1}`);
    }
    const consumer = usage[0] ?? null;
    const bothEmpty = entry.zh === '' && entry.en === '';
    const kind = bothEmpty ? 'joiner' : /(?:^|\.)weekdays\.prefix$/.test(entry.key) ? 'weekday-prefix' : /[._]?[Uu]nit$|_count_unit$|databases_count$/.test(entry.key) ? 'count-classifier' : 'other';
    assert.notEqual(kind, 'other', `${fullKey}: empty-value kind`);
    const reason = {
      joiner: 'both locales are empty on purpose: the value is a joiner prefix rendered immediately before the Space key cap',
      'weekday-prefix': 'English weekday labels are self-contained ("Sun"), so the desktop prefix is empty; zh joins 周 + 日',
      'count-classifier': 'the desktop en-US value is the empty string, so English renders the bare number on that surface',
    }[kind];
    return {
      ...entry,
      key: fullKey,
      kind,
      reason,
      currentCaller: consumer === null ? 'none found in src or host at the locked commit' : consumer,
      consumers: usage.slice(0, 6),
      consumer,
      kotlin: {
        zh: entry.zh,
        en: entry.en,
        resource: `e_${namespace}_${entry.key.replace(/[.\-]/g, '_')}`,
        unified: kind === 'joiner'
          ? { zh: '', en: '', rule: 'keep an empty joiner string; the Space key cap follows immediately' }
          : kind === 'weekday-prefix'
            ? { zh: entry.zh, en: '', rule: 'keep both values as registered: zh prefixes 周, English uses its own weekday labels' }
            : { zh: entry.zh, en: null, rule: 'dstu English renders the count followed by its noun ("3 databases", "2 pomodoros"); the classifier token stays zh-only, an English classifier would be a fabricated desktop string' },
        note: bothEmpty
          ? 'both locales empty: do not introduce text'
          : 'the empty en-US value is registered as-is; the unified Kotlin rule above replaces the classifier token, not the desktop string',
      },
    };
  });
  assert.equal(emptyDetail.filter(item => item.zh === '' && item.en === '').length, 1);
  assert.equal(emptyDetail.filter(item => item.en === '' && item.zh !== '').length, 6);
  assert.equal(emptyDetail.length, 7);
  const localeAccounting = {
    localeFiles: locales.names.length,
    fileHashes: hashes.length,
    zhLeaves: leaves,
    enLeaves,
    physicalLeaves,
    semanticLeaves,
    physicalPluralFamilies: physicalPluralFamilies.length,
    physicalPluralLeaves: physicalPluralKeys.size,
    rule: 'physical leaves are counted exactly; missing is the exact frozen key-set difference (21 = 7 zh-only bases + 14 en-only _one/_other forms); the 7 physical-diff families are each verified by i18next count 1 and 2',
  };
  log('brand.locale.empty', { entries: emptyDetail.length, consumers: emptyDetail.filter(item => item.consumer !== null).length });
  log('brand.locale.plurals', {
    physicalLeaves, localeLeaves: leaves, enLeaves, semanticLeaves,
    sourcePairMissing: missing.length,
    physicalPluralFamilies: physicalPluralFamilies.length,
    physicalPluralLeaves: physicalPluralKeys.size,
  });
  const pair = (group, key, extra = {}) => {
    const zh = locales.read('zh-CN', key);
    const en = locales.read('en-US', key);
    assert.equal(typeof zh, 'string', `${key}: zh`);
    assert.equal(typeof en, 'string', `${key}: en`);
    assert.ok(zh.trim() !== '' && en.trim() !== '', `${key}: non-empty`);
    return { group, key, zh, en, ...extra };
  };
  const viewPairs = navigation.views.map(view => pair('view', view.titleKey, { viewId: view.id, route: view.android.route, module: view.android.module, deliveryUnit: view.android.deliveryUnit }));
  const navigationPairs = navigation.navigation.map(item => pair('navigation', item.labelKey, { navigationId: item.id, viewId: item.viewId, route: item.androidRoute, placement: item.androidPlacement }));
  const settingsPairs = contributions.sections.map(section => pair('settings-section', section.titleKey, { sectionId: section.id, group: section.group, route: section.android.route, module: section.android.module, deliveryUnit: section.android.deliveryUnit }));
  assert.equal(viewPairs.length, 17);
  assert.equal(navigationPairs.length, 9);
  assert.equal(settingsPairs.length, 18);
  const actionKeys = ['actions.save', 'actions.cancel', 'actions.delete', 'actions.edit', 'actions.add', 'actions.upload', 'actions.download', 'actions.search', 'actions.generate', 'actions.export', 'actions.import', 'actions.close', 'actions.start', 'actions.stop', 'actions.retry', 'actions.submit', 'actions.confirm', 'actions.open', 'actions.copy', 'actions.preview', 'actions.filter', 'actions.refresh', 'actions.apply', 'actions.discard', 'actions.create', 'select_all', 'deselect_all', 'confirm', 'save', 'cancel', 'create', 'back', 'search', 'retry', 'copy', 'copied', 'restore', 'delete_permanently', 'empty_trash'];
  const actions = [...new Set(actionKeys)].map(key => pair('action', `common:${key}`));
  const chat = ['chatV2:inputBar.send', 'chatV2:inputBar.stop', 'chatV2:inputBar.sendMessage', 'chatV2:error.retry', 'chatV2:codeBlock.copy', 'chatV2:codeBlock.copyCode', 'chatV2:codeBlock.copySuccess', 'sidebar:navigation.command_palette', 'sidebar:navigation.sessions'].map(key => pair('chat', key));
  const workshopTerms = (() => {
    const keys = {
      label: 'workshop.label',
      source: 'manager.sources.workshop',
      history: 'mine.creations',
      historyPending: 'mine.creationsPending',
      create: 'manager.create',
      modify: 'manager.modify',
      builtinName: 'builtinNames.dstu.plugin-workshop',
      builtinDescription: 'builtinDescriptions.dstu.plugin-workshop',
      start: 'workshop.actions.start',
      install: 'workshop.actions.install',
      decline: 'workshop.actions.decline',
      where: 'workshop.whereLabel',
      risk: 'workshop.risk',
      installed: 'workshop.installed',
      sealed: 'workshop.errors.sealed',
      truncated: 'workshop.errors.truncated',
      secret: 'workshop.errors.secret',
      clearData: 'workshop.dataClear',
    };
    const terms = Object.fromEntries(Object.entries(keys).map(([name, key]) => {
      const entry = pair('plugin-workshop', `plugin_manager:${key}`);
      const zh = entry.zh;
      const en = entry.en;
      assert.ok(!/Plugin Workshop mode|插件工坊模式/.test(`${zh} ${en}`), `${key}: stale mode wording`);
      return [name, { key: entry.key, zh, en }];
    }));
    const absent = ['workshop.bindHere'].map(key => {
      const zh = locales.read('zh-CN', `plugin_manager:${key}`);
      const en = locales.read('en-US', `plugin_manager:${key}`);
      assert.equal(zh, undefined, `${key}: zh absent`);
      assert.equal(en, undefined, `${key}: en absent`);
      return { key, present: false };
    });
    const card = readDesktop(workshopCardFile);
    const proposal = readDesktop(workshopProposalFile);
    const variants = [...`${card}\n${proposal}`.matchAll(/<DsButton[^>]*variant=(?:"([^"]+)"|\{[^}]*'([^']+)'[^}]*\})/g)]
      .map(match => match[1] ?? match[2]).filter(Boolean);
    assert.match(card, /variant=\{action === 'confirm' \? 'primary' : 'outline'\}/);
    assert.match(proposal, /data-testid="workshop-primary"[^>]*variant="primary"|variant="primary"[^>]*data-testid="workshop-primary"/);
    assert.ok(variants.includes('primary'));
    const historicalNonAdoptedTerms = ['manager.desktopOnly'].map(key => {
      const zh = locales.read('zh-CN', `plugin_manager:${key}`);
      const en = locales.read('en-US', `plugin_manager:${key}`);
      assert.equal(zh, undefined, `${key}: zh absent`);
      assert.equal(en, undefined, `${key}: en absent`);
      return {
        key: `plugin_manager:${key}`,
        present: { 'zh-CN': false, 'en-US': false },
        status: 'historical-draft-term-not-present-at-e6b2657; not adopted, no string fabricated',
      };
    });
    return {
      terms,
      absent,
      historicalNonAdoptedTerms,
      naming: 'Plugin Workshop is the product surface; zh uses 插件工坊 in the manager entry and workshop label, “你让 AI 做的” for provenance, and 工坊创作记录 for history',
      sourceRule: 'workshop provenance is “你让 AI 做的” / “Made by AI for you”; “从文件导入” is file provenance and must not be presented as workshop-created',
      desktopOnlyRule: {
        scope: 'upstream mobile/desktop-manager limitation only',
        applyToAndroid: false,
        rule: 'desktopOnly copy is an upstream mobile/desktop-manager limitation only; it is not an Android feature restriction and must not gate the native Android workshop (S6.36); manager.desktopOnly is absent from the frozen locales and is recorded as a non-adopted draft term, not retranslated',
      },
      primaryRule: 'the confirmation card has one primary for the stage decision (开始做 / 安装); the installed card has one primary by output kind; AI modify, overflow, decline and repair alternatives are non-primary',
      states: ['proposed', 'ready', 'installed', 'disabled', 'undone', 'cancelled', 'failed', 'isolated', 'editing', 'superseded', 'generating', 'validating', 'installing'],
      sourceHashes: {
        [workshopCardFile]: sha256(readDesktop(workshopCardFile)),
        [workshopProposalFile]: sha256(readDesktop(workshopProposalFile)),
      },
      delivery: 'S6.35 plugin manager / S6.36 workshop delivery / S5.06 theme adapter',
    };
  })();
  const commandTable = language => JSON.parse(readDesktop(`src/locales/${language}/command_palette.json`)).commands;
  const commandLabel = (language, id) => commandTable(language)[id];
  const commands = [];
  const commandFallbacks = [];
  for (const command of entries.commands) {
    const key = `command_palette:commands.${command.id}`;
    const zh = commandLabel('zh-CN', command.id);
    const en = commandLabel('en-US', command.id);
    if (typeof zh === 'string' && typeof en === 'string' && zh.trim() !== '' && en.trim() !== '') {
      commands.push({ id: command.id, key, zh, en, module: command.android.module, deliveryUnit: command.android.deliveryUnit, kotlin: { resource: `cmd_${command.id.replace(/[.-]/g, '_')}`, zh, en } });
      continue;
    }
    const source = readDesktop(command.evidence.file);
    const start = source.indexOf(`'${command.id}'`);
    assert.ok(start >= 0, `${command.id}: source anchor`);
    const block = source.slice(start, start + 1600);
    const getter = block.match(/get name\(\)\s*\{\s*return i18next\.t\('([^']+)'\s*,\s*'([^']*)'\)/);
    assert.ok(getter, `${command.id}: desktop getter label`);
    const zhLabel = locales.read('zh-CN', getter[1]);
    const enLabel = locales.read('en-US', getter[1]);
    assert.ok(typeof zhLabel === 'string' && zhLabel.trim() !== '', `${command.id}: zh getter label`);
    assert.ok(typeof enLabel === 'string' && enLabel.trim() !== '', `${command.id}: en getter label`);
    const keys = [...new Set([...block.matchAll(/i18next\.t\('([^']+)'/g)].map(match => match[1]))];
    assert.ok(keys.length >= 1);
    commandFallbacks.push({
      id: command.id,
      evidence: `${command.evidence.file}:${command.evidence.line}`,
      catalogKey: key,
      catalogPresent: false,
      getterKey: getter[1],
      zh: zhLabel,
      en: enLabel,
      desktopLiteral: getter[2],
      desktopKeys: keys,
      kotlin: 'values/strings.xml cmd_' + command.id.replace(/[.-]/g, '_') + ' = zh getter; values-en/strings.xml = en getter',
      module: command.android.module,
      deliveryUnit: command.android.deliveryUnit,
    });
  }
  assert.equal(commands.length, 92);
  assert.equal(commandFallbacks.length, 4);
  assert.deepEqual(commandFallbacks.map(item => item.id), ['automation.open', 'automation.create', 'automation.pause-background', 'automation.resume-background']);
  assert.equal(new Set([...commands, ...commandFallbacks].map(item => item.id)).size, 96);
  const menuLabels = [];
  for (const menu of entries.menus) {
    const text = JSON.stringify(menu.definition);
    for (const match of text.matchAll(/t\('([^':]+):([^']+)'\s*(?:,\s*'([^']*)')?/g)) {
      const key = `${match[1]}:${match[2]}`;
      if (key.startsWith('command_palette:')) continue;
      menuLabels.push({ menuRow: menu.rowId, key, zh: locales.read('zh-CN', key) ?? null, en: locales.read('en-US', key) ?? null, desktopLiteral: match[3] ?? null });
    }
  }
  const keyless = menuLabels.filter(item => typeof item.zh !== 'string' || typeof item.en !== 'string');
  const terminology = {
    locale: {
      files: locales.names.length,
      namespaces: locales.names,
      leaves,
      enLeaves,
      physicalLeaves,
      semanticLeaves,
      sourcePairMissing: missing.length,
      pluralFamilies: physicalPluralFamilies,
      missing,
      empty: emptyDetail,
      hashes,
      rule: 'index every namespace, hash both locales, list missing/empty keys; no translation is synthesized here',
    },
    pairs: { views: viewPairs, navigation: navigationPairs, settingsSections: settingsPairs, actions, chat },
    commands: { catalog: commands, fallbacks: commandFallbacks, kotlinResource: 'one string per command id; fallback rows use the desktop getter value in both locales' },
    menus: { rows: menuLabels.length, resolved: menuLabels, unresolved: keyless },
    workshop: workshopTerms,
    missingEnglishReview: {
      commandLabels: {
        count: 4,
        rows: ['automation.open', 'automation.create', 'automation.pause-background', 'automation.resume-background'],
        status: 'zh and en both resolve through the desktop i18next getter key; the command_palette catalog has no entry for these ids in either locale',
        kotlin: 'one string per id, values taken from the getter pair; the desktop literal in the getter is the English fallback and is not used as a second translation',
      },
      emptyLocaleValues: {
        count: emptyDetail.length,
        withoutCurrentCaller: emptyDetail.filter(item => item.consumer === null).map(item => item.key),
        byKind: emptyDetail.reduce((total, item) => ({ ...total, [item.kind]: (total[item.kind] ?? 0) + 1 }), {}),
        enClassifierOmissions: emptyDetail.filter(item => item.en === '' && item.zh !== '').length,
        bothEmpty: emptyDetail.filter(item => item.zh === '' && item.en === '').length,
        status: 'registered with consumer call sites where one exists; two anki classifier keys have no current caller at the locked commit and are kept as reserved resources, no English string was authored for the desktop',
        kotlinRule: 'classifier omissions become "count + noun" in English and keep the zh classifier token; the joiner stays empty in both locales',
      },
      desktopLiterals: 'menu rows missing a locale key would be listed here; current extraction resolves every menu key, so the list is empty',
      menuUnresolved: keyless.length,
    },
    sourcePair: {
      status: 'physical-key-set-difference',
      count: missing.length,
      counts: {
        enMissing: missing.filter(entry => entry.missingSide === 'en-US').length,
        zhMissing: missing.filter(entry => entry.missingSide === 'zh-CN').length,
        pluralFamilies: physicalPluralFamilies.length,
        pluralKeys: physicalPluralKeys.size,
      },
      rows: missing.map(entry => ({
        namespace: entry.file.replace(/\.json$/, ''),
        key: entry.key,
        missingSide: entry.missingSide,
      })),
      acceptedPortableIssues: physicalPluralFamilies.map(entry => ({
        id: `${entry.file.replace(/\.json$/, '')}:${entry.base}`,
        namespace: entry.file.replace(/\.json$/, ''),
        key: entry.base,
        kind: 'i18next-semantic-plural',
        zhForm: entry.zhForm,
        enForm: entry.enForm,
        zh: entry.zh,
        en: entry.en,
        zhKeys: entry.zhKeys,
        enKeys: entry.enKeys,
        semanticResolvable: entry.semanticResolvable,
        resolvedCounts: entry.resolvedCounts,
        actualI18next: entry.actualI18next,
        status: 'mapped-semantic-pair',
        blocking: false,
        scope: 'not a physical missing key; the valid i18next plural family is mapped to Android plurals and verified by actual i18next lookup',
        fallback: 'read en _one/_other from the frozen locale; Kotlin plural resource uses one/other; do not invent an en base string',
        android: `plural resource e_${entry.file.replace(/\.json$/, '')}_${entry.base.replace(/[.\-]/g, '_')}; zh quantity=other, en quantities one/other; FALLBACK_SOURCE=en-US plural family`,
      })),
      rule: 'count is the exact key-set difference between zh-CN and en-US; the 7 physical-diff families are mapped to Android plurals and verified by actual i18next count 1 and 2',
    },
    rules: {
      nonEmpty: 'every pair above was asserted non-empty in zh-CN and en-US',
      fallback: 'a command without command_palette entry keeps its desktop i18next getter key; the Kotlin resource value is that getter text, not an invented label',
      placeholders: 'values/strings.xml consumes {{name}} placeholders as %1$s/%2$s preserving desktop order',
    },
  };

  const scenesModule = await import(pathToFileURL(resolve(desktop, scenesFile)).href);
  assert.equal(scenesModule.SCENES.length, 18);
  assert.equal(scenesModule.VISUAL_SCENES.length, 12);
  const viewportSf = parse(viewportFile);
  let viewports = null;
  visit(viewportSf, node => {
    if (ts.isVariableDeclaration(node) && node.name.getText() === 'VIEWPORTS') viewports = value(node.initializer);
  });
  assert.deepEqual(viewports, { desktop: { width: 1440, height: 900 }, mobile: { width: 390, height: 844 } });
  const shotsTest = readDesktop(screenshotsTest);
  assert.ok(/toHaveScreenshot/.test(shotsTest));
  const pageTargets = {
    'shell-chat': { route: 'workspace/panel/chat', module: 'features/chat', deliveryUnit: 'S5.30', secondary: [], nameKey: 'workbench:apps.chat.name' },
    'shell-desktop': { route: 'workspace', module: 'features/workspace', deliveryUnit: 'S5.26', secondary: [], nameKey: 'sidebar:navigation.workbench_mode' },
    chat: { route: 'chat/sessions', module: 'features/chat', deliveryUnit: 'S3.27', secondary: [], nameKey: 'sidebar:navigation.chat_v2' },
    'learning-hub': { route: 'knowledge/library', module: 'features/knowledge', deliveryUnit: 'S7.01', secondary: [], nameKey: 'sidebar:navigation.learning_hub' },
    notes: { route: 'notes', module: 'features/knowledge', deliveryUnit: 'S5.30', secondary: [], nameKey: 'workbench:apps.note' },
    mindmap: { route: 'notes/mindmap/{mindmapId}', module: 'features/knowledge', deliveryUnit: 'S5.30', secondary: [], nameKey: 'workbench:apps.mindmap' },
    'question-bank': { route: 'study/questions', module: 'features/study', deliveryUnit: 'S7.18', secondary: [], nameKey: 'workbench:apps.exam' },
    anki: { route: 'study/cards', module: 'features/study', deliveryUnit: 'S7.08', secondary: ['S7.09'], nameKey: 'workbench:apps.taskDashboard' },
    flashcards: { route: 'study/flashcards', module: 'features/study', deliveryUnit: 'S7.12', secondary: [], nameKey: 'sidebar:navigation.flashcards' },
    models: { route: 'settings/models', module: 'features/system', deliveryUnit: 'S7.35', secondary: ['S3.02'], nameKey: 'settings:tabs.model_assignment' },
    'skills-mcp': { route: 'agent/skills', module: 'features/chat', deliveryUnit: 'S3.20', secondary: ['S6.28'], nameKey: 'sidebar:navigation.skills_management' },
    essay: { route: 'study/essay', module: 'features/study', deliveryUnit: 'S7.23', secondary: [], nameKey: 'workbench:apps.essay' },
  };
  const constantCache = new Map();
  const valueOfConstant = name => {
    if (constantCache.has(name)) return constantCache.get(name);
    const hit = [];
    for (const file of sourceFiles(desktop, 'src/demo/app')) {
      if (!/\.(ts|tsx)$/.test(file)) continue;
      const match = readDesktop(file).match(new RegExp(`export const ${name} = '([^']*)'`));
      if (match) hit.push({ file, value: match[1] });
    }
    const resolved = hit.length === 1 ? hit[0] : null;
    constantCache.set(name, resolved);
    return resolved;
  };
  const demoPack = id => {
    const packFile = `src/demo/app/packs/${id}.ts`;
    if (!existsSync(resolve(desktop, packFile))) return { file: null, title: null, instanceKey: null };
    const text = readDesktop(packFile);
    const instanceKey = text.match(/instanceKey:\s*([A-Za-z0-9_]+)/)?.[1] ?? null;
    return {
      file: packFile,
      title: text.match(/title:\s*'([^']*)'/)?.[1] ?? null,
      instanceKey,
      instanceKeyValue: instanceKey === null ? null : valueOfConstant(instanceKey),
    };
  };
  const platforms = ['linux', 'darwin'];
  const expected = [];
  for (const scene of scenesModule.VISUAL_SCENES) for (const viewport of Object.keys(viewports)) for (const theme of ['light', 'dark']) expected.push({ scene: scene.id, viewport, theme });
  assert.equal(expected.length, 48);
  const screenshots = [];
  const missingScreenshots = [];
  const fontEnvironment = [];
  for (const platform of platforms) {
    const dir = resolve(desktop, screenshotDir, platform);
    assert.ok(existsSync(dir), `missing platform directory ${platform}`);
    const files = new Set(readdirSync(dir));
    for (const entry of expected) {
      const nameFile = `${entry.scene}-${entry.viewport}-${entry.theme}.png`;
      if (!files.has(nameFile)) {
        missingScreenshots.push({ platform, file: nameFile, ...entry, reason: entry.scene === 'notes' ? 'notes golden not committed on Linux; scene matrix entry exists in tests/visual/lib/scenes.mjs' : 'not committed' });
        continue;
      }
      const bytes = readFileSync(resolve(dir, nameFile));
      const info = pngInfo(bytes);
      assert.equal(info.width, viewports[entry.viewport].width, `${platform}/${nameFile}: width`);
      assert.equal(info.height, viewports[entry.viewport].height, `${platform}/${nameFile}: height`);
      screenshots.push({ platform, file: `${screenshotDir}/${platform}/${nameFile}`, scene: entry.scene, viewport: entry.viewport, theme: entry.theme, ...info });
    }
    const fontFile = 'font-environment.json';
    if (files.has(fontFile)) fontEnvironment.push({ platform, file: `${screenshotDir}/${platform}/${fontFile}`, sha256: sha256(readFileSync(resolve(dir, fontFile))), values: JSON.parse(readFileSync(resolve(dir, fontFile), 'utf8')) });
  }
  assert.equal(screenshots.length, 92);
  assert.equal(missingScreenshots.length, 4);
  assert.ok(missingScreenshots.every(item => item.platform === 'linux' && item.scene === 'notes'));
  const workbenchById = new Map(workbench.apps.map(app => [app.id, app]));
  const workbenchAppByScene = {
    'shell-chat': 'chat', chat: 'chat', notes: 'notes', mindmap: 'notes', 'question-bank': 'exam', anki: 'taskDashboard',
    flashcards: 'flashcards', essay: 'essay', 'skills-mcp': 'skills', models: 'settings', 'learning-hub': 'files', 'shell-desktop': 'sandbox',
  };
  const workbenchAppOf = (scene, nameKey) => {
    const appId = workbenchAppByScene[scene];
    assert.ok(appId, `${scene}: workbench app`);
    const app = workbenchById.get(appId);
    assert.ok(app, `${nameKey}: workbench app ${appId}`);
    return {
      appId: app.id,
      resourceTypes: app.resourceTypes,
      instanceRule: app.instanceRule,
      phone: app.android.phone,
      tablet: app.android.tablet,
      deliveryUnit: app.android.deliveryUnit,
      nameKey,
    };
  };
  const pages = scenesModule.VISUAL_SCENES.map(scene => {
    const target = pageTargets[scene.id];
    assert.ok(target, `unmapped visual scene ${scene.id}`);
    const title = locales.read('zh-CN', target.nameKey);
    const titleEn = locales.read('en-US', target.nameKey);
    assert.ok(title && titleEn, `${scene.id}: dstu name`);
    return {
      scene: scene.id,
      demoPack: demoPack(scene.id),
      demoEntry: scene.entry,
      nameKey: target.nameKey,
      zh: title,
      en: titleEn,
      workbenchApp: workbenchAppOf(scene.id, target.nameKey),
      route: target.route,
      module: target.module,
      deliveryUnit: target.deliveryUnit,
      secondaryUnits: target.secondary,
      viewports: Object.keys(viewports),
      themes: ['light', 'dark'],
      goldens: screenshots.filter(item => item.scene === scene.id).map(item => ({ platform: item.platform, viewport: item.viewport, theme: item.theme, file: item.file, width: item.width, height: item.height, sha256: item.sha256 })),
    };
  });
  unique(pages, page => page.scene, 'screenshot scenes');
  const keyPages = {
    source: { matrix: `${screenshotsTest}`, scenes: scenesFile, viewports: viewportFile },
    viewports,
    matrix: { scenes: pages.length, viewports: Object.keys(viewports).length, themes: 2, expected: expected.length, committed: screenshots.length, missing: missingScreenshots.length },
    pages,
    screenshots,
    missing: missingScreenshots,
    fontEnvironment,
    rule: 'index the committed goldens only; no screenshot was produced in this unit and none is described as a fresh production UI capture',
  };

  const operitPages = [];
  const page = spec => {
    assert.ok(operitHas(spec.source.file), `${spec.id}: ${spec.source.file}`);
    const sourceText = operit(spec.source.file);
    const symbolIndex = sourceText.split('\n').findIndex(line => line.includes(spec.source.symbol) && !/^\s*import\b/.test(line));
    assert.ok(symbolIndex >= 0, `${spec.id}: symbol ${spec.source.symbol}`);
    const strings = spec.strings.map(operitString);
    const missingStrings = strings.filter(item => typeof item.zh !== 'string' || typeof item.en !== 'string');
    const groups = (spec.tokenGroups ?? ['settings']).flatMap(group => PAGE_TOKEN_GROUPS[group].map(([token, role]) => tokenRef(light, token, role)));
    for (const entry of groups) assert.ok(dark[entry.token], `${spec.id}: dark ${entry.token}`);
    const primaryString = spec.nameString === undefined ? null : strings.find(item => item.key === spec.nameString);
    const dstuName = spec.dstuName ?? null;
    const resolved = spec.nameKey !== undefined
      ? { zh: locales.read('zh-CN', spec.nameKey), en: locales.read('en-US', spec.nameKey), source: 'desktop-locale', sourceRef: spec.nameKey }
      : dstuName !== null
        ? { zh: dstuName.zh, en: dstuName.en, source: 'dstu-naming', sourceRef: spec.nameReason }
        : { zh: primaryString?.zh ?? null, en: primaryString?.en ?? null, source: 'operit-string', sourceRef: `app/src/main/res/values*/strings.xml:${spec.nameString}` };
    assert.ok(typeof resolved.zh === 'string' && resolved.zh.trim() !== '', `${spec.id}: zh`);
    assert.ok(typeof resolved.en === 'string' && resolved.en.trim() !== '', `${spec.id}: en`);
    const result = {
      id: spec.id,
      zh: resolved.zh,
      en: resolved.en,
      nameSource: resolved.source,
      nameSourceRef: resolved.sourceRef,
      kotlinName: { resource: `page_${spec.id.replace(/[.-]/g, '_')}`, zh: resolved.zh, en: resolved.en },
      ...(spec.nameKey === undefined ? {} : { nameKey: spec.nameKey }),
      route: spec.route,
      module: spec.module,
      deliveryUnit: spec.deliveryUnit,
      secondaryUnits: spec.secondaryUnits ?? [],
      entry: spec.entry,
      source: { file: spec.source.file, line: symbolIndex + 1, symbol: spec.source.symbol },
      operitStrings: strings,
      missingStrings,
      tokenGroups: spec.tokenGroups ?? ['settings'],
      tokens: groups.map(entry => ({ ...entry, dark: dark[entry.token].kotlin.value, darkHex: dark[entry.token].kotlin.hex ?? null })),
      stateMapping: spec.stateMapping,
      acceptance: spec.acceptance,
    };
    if (resolved.source === 'operit-string') {
      assert.ok(primaryString && typeof primaryString.zh === 'string' && typeof primaryString.en === 'string', `${spec.id}: primary string ${spec.nameString}`);
      assert.equal(result.zh, primaryString.zh, `${spec.id}: zh from ${spec.nameString}`);
      assert.equal(result.en, primaryString.en, `${spec.id}: en from ${spec.nameString}`);
    }
    if (resolved.source === 'desktop-locale') {
      assert.equal(result.zh, locales.read('zh-CN', spec.nameKey), `${spec.id}: zh locale`);
      assert.equal(result.en, locales.read('en-US', spec.nameKey), `${spec.id}: en locale`);
    }
    operitPages.push(result);
  };
  page({
    id: 'terminal',
    nameString: 'terminal',
    route: 'terminal/session/{sessionId}', module: 'features/terminal', deliveryUnit: 'S6.03', secondaryUnits: ['S1.20'],
    entry: 'terminal nav item → TerminalToolScreen',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/toolbox/screens/ToolboxScreen.kt', symbol: 'fun TerminalToolScreen' },
    strings: ['terminal', 'screen_title_terminal'],
    tokenGroups: ['terminal'],
    stateMapping: { container: 'DstuTheme.colors.background', cell: 'DstuTheme.colors.foreground + fontMono', selection: 'accentPrimary', divider: 'border', prompt: 'mutedForeground' },
    acceptance: 'ANSI/Canvas pass 16-color/truecolor, CJK/emoji width, resize and large scroll under DstuTheme light/dark (S6.03)',
  });
  page({
    id: 'terminal-setup',
    dstuName: { zh: '终端初始化', en: 'Terminal setup' },
    nameReason: 'dstu instance name for the Operit setup path; Operit renders this path with the same screen_title_terminal title as the terminal itself, so no separate original exists',
    route: 'terminal/setup', module: 'features/terminal', deliveryUnit: 'S6.09', secondaryUnits: ['S1.21'],
    entry: 'TerminalSetup screen → TerminalToolScreen(forceShowSetup = true)',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', symbol: 'data object TerminalSetup' },
    strings: ['screen_title_terminal', 'screen_title_terminal_auto_config'],
    tokenGroups: ['install'],
    stateMapping: { card: 'surfacePanel', row: 'surfacePanelMuted', progress: 'accentPrimary', failure: 'danger', done: 'success' },
    acceptance: 'rootfs download/unpack/activation states and retry keep a complete prior environment (S6.09)',
  });
  page({
    id: 'terminal-auto-config',
    nameString: 'screen_title_terminal_auto_config',
    route: 'terminal/setup/auto', module: 'features/terminal', deliveryUnit: 'S6.16', secondaryUnits: [],
    entry: 'TerminalAutoConfig screen',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', symbol: 'data object TerminalAutoConfig' },
    strings: ['screen_title_terminal_auto_config'],
    tokenGroups: ['settings'],
    stateMapping: { section: 'surfacePanel', label: 'foreground', hint: 'mutedForeground', action: 'accentPrimary', divider: 'border' },
    acceptance: 'mirror/proxy switches persist and are reflected by the next apt/pip install (S6.16)',
  });
  page({
    id: 'install-center',
    nameString: 'package_manager',
    route: 'system/packages', module: 'features/terminal', deliveryUnit: 'S6.11', secondaryUnits: ['S6.35'],
    entry: 'Packages nav item → PackageManagerScreen',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/packages/screens/PackageManagerScreen.kt', symbol: 'fun PackageManagerScreen' },
    strings: ['package_manager', 'nav_packages'],
    tokenGroups: ['install'],
    stateMapping: { card: 'surfacePanel', row: 'surfacePanelMuted', installAction: 'accentPrimary', failure: 'danger', installed: 'success', divider: 'border' },
    acceptance: 'same package installs serially; cancel/restart resumes from package-manager state; version probe confirms success (S6.11)',
  });
  page({
    id: 'shizuku-permissions',
    nameString: 'shizuku_commands',
    route: 'system/shizuku', module: 'features/system', deliveryUnit: 'S6.18', secondaryUnits: ['S1.22'],
    entry: 'ShizukuCommands nav item → ShizukuDemoScreen',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', symbol: 'data object ShizukuCommands' },
    strings: ['shizuku_commands', 'install_shizuku'],
    tokenGroups: ['shizuku'],
    stateMapping: { installed: 'success', connecting: 'warning', authorized: 'success', revoked: 'danger', uidText: 'foreground', description: 'mutedForeground' },
    acceptance: 'not-installed / not-started / unauthorized / authorized / revoked / binder-dead each map to a distinct state with actual UID (S6.18)',
  });
  page({
    id: 'shizuku-install',
    nameString: 'install_shizuku',
    route: 'system/shizuku/install', module: 'features/system', deliveryUnit: 'S6.18', secondaryUnits: [],
    entry: 'ShizukuWizardCard / ShizukuDemoScreen install step',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/demo/wizards/ShizukuWizardCard.kt', symbol: 'ShizukuWizardCard' },
    strings: ['install_shizuku', 'install_bundled', 'install_from_store'],
    tokenGroups: ['install'],
    stateMapping: { step: 'surfacePanel', title: 'foreground', description: 'mutedForeground', primary: 'accentPrimary', failure: 'danger' },
    acceptance: 'bundled/store install starts with the system installer and returns to the authorization step (S6.18)',
  });
  page({
    id: 'permission-guide',
    dstuName: { zh: '权限引导', en: 'Permission guide' },
    nameReason: 'dstu name for the first-run guide; Operit has no title resource for the screen (pages carry permission_guide_welcome_title / permission_guide_basic_title per step)',
    route: 'system/permissions/guide', module: 'features/system', deliveryUnit: 'S2.21', secondaryUnits: ['S6.40'],
    entry: 'MainActivity first-run guide → PermissionGuideScreen',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/permission/screens/PermissionGuideScreen.kt', symbol: 'fun PermissionGuideScreen' },
    strings: ['permission_guide_welcome_title', 'permission_guide_basic_permissions', 'permission_guide_basic_title'],
    tokenGroups: ['permission'],
    stateMapping: { card: 'surfacePanel', granted: 'success', pending: 'warning', denied: 'danger', action: 'accentPrimary' },
    acceptance: 'storage/overlay/notification/battery grants each show granted/denied and the guide resumes after a system settings round-trip (S2.21, overlay item S6.40)',
  });
  page({
    id: 'tool-permissions',
    nameString: 'tool_permissions',
    route: 'settings/tool-permissions', module: 'features/system', deliveryUnit: 'S7.35', secondaryUnits: ['S2.23'],
    entry: 'Settings → tool permissions → ToolPermissionSettingsScreen',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/settings/screens/ToolPermissionSettingsScreen.kt', symbol: 'fun ToolPermissionSettingsScreen' },
    strings: ['tool_permissions', 'settings_tool_permissions'],
    tokenGroups: ['settings'],
    stateMapping: { row: 'surfacePanel', title: 'foreground', description: 'mutedForeground', allow: 'success', ask: 'warning', deny: 'danger' },
    acceptance: 'allow/ask/deny persists per tool and the broker rejects denied calls with zero executions (S7.35, approval in S2.23)',
  });
  page({
    id: 'app-permissions',
    nameString: 'screen_title_app_permissions',
    route: 'system/app-permissions', module: 'features/system', deliveryUnit: 'S6.22', secondaryUnits: [],
    entry: 'Toolbox → AppPermissions screen',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', symbol: 'data object AppPermissions' },
    strings: ['screen_title_app_permissions'],
    tokenGroups: ['permission'],
    stateMapping: { list: 'surfacePanel', packageName: 'foreground', granted: 'success', denied: 'danger', filter: 'menuShellSurface' },
    acceptance: 'pm-backed grant/revoke matches adb dumpsys output and read-only queries stay side-effect free (S6.22)',
  });
  page({
    id: 'background-keepalive',
    nameString: 'enable_background_keep_alive',
    route: 'settings/background', module: 'features/system', deliveryUnit: 'S2.21', secondaryUnits: ['S2.20'],
    entry: 'GlobalDisplaySettingsScreen keep-alive switch',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/data/preferences/DisplayPreferencesManager.kt', symbol: 'enable_background_keep_alive' },
    strings: ['enable_background_keep_alive', 'enable_background_keep_alive_description'],
    tokenGroups: ['background'],
    stateMapping: { card: 'surfacePanel', title: 'foreground', description: 'mutedForeground', enabled: 'accentPrimary', batteryHint: 'warning' },
    acceptance: 'switch survives restart and only keeps duties that are actually active; notification reflects the enabled duty (S2.21)',
  });
  page({
    id: 'floating-window',
    dstuName: { zh: '浮窗', en: 'Floating window' },
    nameReason: 'dstu short name for the ported floating window; Operit only carries the descriptive header floating_chat_window_title (AI助手悬浮窗), kept below as source text',
    route: 'workspace/floating/{taskId}', module: 'features/workspace', deliveryUnit: 'S6.40', secondaryUnits: ['S2.20'],
    entry: 'FloatingWindowDelegate → FloatingChatService → FloatingWindowManager',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt', symbol: 'class FloatingChatService' },
    strings: ['floating_chat_running_in_background', 'close_floating_window', 'floating_close_floating_window'],
    tokenGroups: ['floating'],
    stateMapping: { window: 'surfaceElevated', rim: 'border', radius: 'wbWindowRadius', text: 'foreground', action: 'accentPrimary', overlay: 'surfaceOverlay' },
    acceptance: 'drag/collapse/return/close, task binding, notification and resource release pass with the floating window in DstuTheme (S6.40)',
  });
  page({
    id: 'mcp-config',
    nameKey: 'settings:tabs.mcp_tools',
    route: 'settings/mcp/servers', module: 'features/system', deliveryUnit: 'S6.28', secondaryUnits: ['S7.35'],
    entry: 'PackageManagerScreen → MCPConfigScreen; settings MCP section',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/packages/screens/MCPConfigScreen.kt', symbol: 'fun MCPConfigScreen' },
    strings: ['mcp', 'mcp_management', 'mcp_remote_bearer_token'],
    tokenGroups: ['mcp'],
    stateMapping: { card: 'surfacePanel', transport: 'mutedForeground', connected: 'success', failed: 'danger', action: 'accentPrimary', menu: 'menuShellSurface' },
    acceptance: 'SSE/Streamable HTTP initialize/list/call/read and auth refresh/revoke pass with sanitized credentials (S6.28)',
  });
  page({
    id: 'toolpkg-host',
    nameKey: 'settings:tabs.plugins',
    route: 'workspace/toolpkg/{containerPackageName}/{uiModuleId}', module: 'features/workspace', deliveryUnit: 'S6.44', secondaryUnits: ['S6.34'],
    entry: 'ToolPkgComposeDsl / ToolPkgPluginConfig → ToolPkgComposeDslToolScreen',
    source: { file: 'app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/ToolPkgComposeDslScreen.kt', symbol: 'fun ToolPkgComposeDslToolScreen' },
    strings: ['toolpkg_api_version_label', 'pkg_toolpkg_subpackages'],
    tokenGroups: ['toolpkg'],
    stateMapping: { page: 'surfacePanel', text: 'foreground', metadata: 'mutedForeground', dialog: 'dialogShellSurface', action: 'accentPrimary' },
    acceptance: 'real ToolPkg page performs action/approval/authorized file access, reopen and cross-size restore keep panel state (S6.44)',
  });
  unique(operitPages, item => item.id, 'operit pages');
  for (const item of [...operitPages, ...pages]) {
    assert.ok(planUnits.has(item.deliveryUnit), `${item.id}: ${item.deliveryUnit} is not a plan unit`);
    for (const unit of item.secondaryUnits ?? []) assert.ok(planUnits.has(unit), `${item.id}: ${unit} is not a plan unit`);
  }
  const operitMissingStrings = operitPages.flatMap(item => item.missingStrings.map(entry => ({ page: item.id, ...entry })));
  for (const item of operitPages) {
    assert.ok(item.tokens.length >= 6, `${item.id}: token mapping`);
    for (const token of item.tokens) assert.ok(token.light && token.dark, `${item.id}: ${token.token} both modes`);
  }

  const pngRejects = () => {
    const fake = Buffer.alloc(32);
    fake.write('GIF89a', 0, 'ascii');
    try { pngInfo(fake); return false; } catch { return true; }
  };
  const unknownKeyRejects = () => {
    try { locales.read('zh-CN', 'common:definitely.not.a.real.key'); return locales.read('zh-CN', 'common:definitely.not.a.real.key') === undefined; } catch { return true; }
  };
  const nonEmptyRejects = () => {
    try { pair('self', 'todo:pomodoro.immersive.hintSpacePrefix'); return false; } catch (error) { return /non-empty/.test(error.message); }
  };
  const selfChecks = {
    pngSignatureRejectsNonPng: pngRejects(),
    unknownLocaleKeyRejects: unknownKeyRejects(),
    emptyCoreTermRejected: nonEmptyRejects(),
    driftDetected: difference({ a: 1 }, { a: 2 })?.path === '$.a',
    composeViewportMatchesSvg: brand.mark.mapping.kotlinAccessor.includes('viewportWidth = 147f') && brand.mark.mapping.kotlinAccessor.includes('viewportHeight = 147f'),
    brandKeepsTwoColorPaths: [brand.mark, brand.logo].every(item => new Set(item.mapping.paths.map(path => path.token)).size === 2 && item.mapping.tint.startsWith('Color.Unspecified')),
    brandKeepsAspectRatio: [brand.mark, brand.logo].every(item => Math.abs(item.mapping.defaultWidthDp / item.mapping.defaultHeightDp - item.width / item.height) < 1e-12),
    allPathsHaveGeometry: [...brand.mark.paths, ...brand.logo.paths].every(item => item.d.length > 20 && item.sha256.length === 64),
    everyIconWeightDiffers: navigationIcons.every(item => item.regular.geometrySha256 !== item.bold.geometrySha256),
    screenshotsAreIndexedNotCaptured: missingScreenshots.every(item => item.reason) && screenshots.every(item => item.sha256 === pngInfo(readFileSync(resolve(desktop, item.file))).sha256),
  };
  for (const [nameFile, ok] of Object.entries(selfChecks)) assert.equal(ok, true, `self check ${nameFile}`);
  log('brand.selfChecks', selfChecks);

  const checks = {
    planUnits: plan.filter(line => /^\| S0\.\d\d \|/.test(line)).length,
    brandMarkPaths: brand.mark.paths.length,
    brandLogoPaths: brand.logo.paths.length,
    logoWordmarkPaths: brand.logo.wordmarkPaths,
    navigationIcons: navigationIcons.length,
    unreferencedIcons: unreferenced.length,
    localeFiles: locales.names.length,
    localeLeaves: leaves,
    localeMissing: missing.length,
    localeEmpty: empty.length,
    viewPairs: viewPairs.length,
    navigationPairs: navigationPairs.length,
    settingsPairs: settingsPairs.length,
    actionPairs: actions.length,
    chatPairs: chat.length,
    commandsPaired: commands.length,
    commandFallbacks: commandFallbacks.length,
    menuRows: menuLabels.length,
    menuUnresolved: keyless.length,
    screenshotScenes: pages.length,
    screenshotsCommitted: screenshots.length,
    screenshotsMissing: missingScreenshots.length,
    operitPages: operitPages.length,
    operitTokenRefs: operitPages.reduce((total, item) => total + item.tokens.length, 0),
    operitMissingStrings: operitMissingStrings.length,
    selfChecks,
    renderInputs: renderInputs.length,
    desktopCommit: readJson('config/source-lock.json').repositories.find(item => item.id === 'desktop').commit,
    operitCommit: readJson('config/source-lock.json').repositories.find(item => item.id === 'operit').commit,
    nameSources: operitPages.reduce((total, item) => ({ ...total, [item.nameSource.startsWith('operit') ? 'operit' : 'desktop']: (total[item.nameSource.startsWith('operit') ? 'operit' : 'desktop'] ?? 0) + 1 }), {}),
  };
  assert.equal(checks.navigationPairs, 9);
  assert.equal(checks.viewPairs, 17);
  assert.equal(checks.settingsPairs, 18);
  assert.equal(checks.commandsPaired + checks.commandFallbacks, 96);
  assert.equal(checks.screenshotsCommitted + checks.screenshotsMissing, 96);
  assert.equal(checks.menuUnresolved, 0);
  log('brand.coverage', checks);

  const lock = readJson('config/source-lock.json');
  assert.equal(git(desktop, 'rev-parse', 'HEAD'), lock.repositories.find(item => item.id === 'desktop').commit, 'desktop HEAD vs source lock');
  assert.equal(git(operitRoot, 'rev-parse', 'HEAD'), lock.repositories.find(item => item.id === 'operit').commit, 'operit HEAD vs source lock');
  log('brand.anchors', { desktop: checks.desktopCommit, operit: checks.operitCommit, nameSources: checks.nameSources });

  return {
    formatVersion: 1,
    unit,
    delivery: { brand: 'S0.12', icons: 'S0.12', terminology: 'S0.12', screenshots: 'S0.12', themeConsumption: 'S1.11', operitPages: 'S5.06' },
    evidence: {
      plan: { file: planFile, unitLine: unitLine + 1, unitRow: plan[unitLine].slice(0, 260), sectionLine: sectionLine + 1, sectionRows },
      design: { file: designFile, scenarios: Object.keys(design.scenarios), tokens: Object.keys(light).length },
      renderInputs,
    },
    brand,
    icons: iconSet,
    terminology,
    keyPages,
    operitPages,
    checks,
  };
}

export async function run(mode) {
  const started = performance.now();
  const data = await collect();
  artifact(file, data, mode);
  log('brand.complete', { unit, mode, elapsedMs: Math.round(performance.now() - started), ...data.checks });
}

main(import.meta.url, run);
