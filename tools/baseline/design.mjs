import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, posix, resolve } from 'node:path';
import { artifact, main, root, desktop, desktopRequire, readDesktop, sha256, log, parse, ts, visit, value, git } from './lib.mjs';

const unit = 'S0.11';
const file = 'config/design/tokens.baseline.json';
const planFile = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const themeFile = 'src/hooks/useTheme.ts';
const entryFile = 'src/App.tsx';
const frozenCommit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const buttonContractFile = 'src/components/ui/buttonPrimitiveContract.ts';
const shadButtonFile = 'src/components/ui/shad/Button.tsx';
const dialogFile = 'src/components/ui/DsDialog.tsx';
const variantContractFile = 'src/ui-kernel/contracts/surfaces.ts';
const variantControllerFile = 'src/features/settings/themeVariants.ts';
const variantPickerFile = 'src/features/settings/components/ThemeVariantPicker.tsx';
const variantOwnerFile = 'src/ui-plugins/official/settings/index.ts';
const accentNames = ['--primary', '--primary-foreground', '--ring', '--brand-primary', '--brand-primary-dark', '--primary-color'];
const neutralNames = ['--titlebar-background', '--nav-background', '--background', '--foreground', '--card', '--card-foreground', '--popover', '--popover-foreground', '--secondary', '--secondary-foreground', '--muted', '--muted-foreground', '--accent', '--accent-foreground', '--border', '--input', '--destructive', '--destructive-foreground'];
const prominentFamily = ['--button-prominent-active-bg', '--button-prominent-bg', '--button-prominent-border', '--button-prominent-hover-bg'];
const primaryClassTokens = ['--button-prominent-active-bg', '--button-prominent-bg', '--button-prominent-hover-bg'];
const dangerClassTokens = ['--button-destructive-active-bg', '--button-destructive-bg', '--button-destructive-border', '--button-destructive-hover-bg'];
const ghostClassTokens = ['--button-plain-active-bg', '--button-plain-bg', '--button-plain-border', '--button-plain-hover-bg'];
const hostEntries = [
  { file: 'src/styles/notes-typography.css', via: 'src/features/notes/NotesCrepeEditor.tsx:97' },
  { file: 'src/features/workbench/styles/workbench.css', via: 'src/features/workbench/components/WorkbenchDesktop.tsx:22' },
  { file: 'src/features/workbench/styles/workbench.tokens.css', via: 'src/features/learning-hub/components/finder/DesktopView.tsx:23' },
  { file: 'src/features/chat/styles/index.css', via: 'src/features/chat/init.ts:25' },
];
const postcss = desktopRequire('postcss');
const esbuild = desktopRequire('esbuild');
const css = readDesktop;
const norm = text => text.replace(/\s+/g, ' ').trim();
const externalImports = [...readDesktop(entryFile).matchAll(/^import '([^'.][^']+\.css)';/gm)].map(match => ({ module: match[1], line: readDesktop(entryFile).slice(0, match.index).split('\n').length }));

function entryLinks() {
  const text = readDesktop(entryFile);
  return [...text.matchAll(/^import '(\.[^']+\.css)';/gm)].map(match => ({ file: posix.normalize(posix.join(dirname(entryFile), match[1])), via: `${entryFile}:${text.slice(0, match.index).split('\n').length}`, origin: 'App.tsx import' }));
}

function chainOf(links) {
  const files = [];
  const conditions = new Map();
  const declared = new Map();
  const expand = (item, importedBy) => {
    if (files.some(entry => entry.file === item.file)) return;
    const text = css(item.file);
    const ast = postcss.parse(text, { from: item.file });
    const index = new Map();
    const stack = [];
    const walkNodes = nodes => {
      for (const node of nodes) {
        if (node.type === 'atrule' && node.nodes && node.name !== 'keyframes') {
          stack.push(`@${node.name} ${norm(node.params)}`);
          walkNodes(node.nodes);
          stack.pop();
        } else if (node.type === 'rule') {
          for (const decl of node.nodes) if (decl.type === 'decl' && decl.prop.startsWith('--')) {
            index.set(decl.source.start.line, [...stack]);
            const record = { file: item.file, line: decl.source.start.line, selector: norm(node.selector), conditions: [...stack], raw: norm(decl.value) };
            (declared.get(decl.prop) ?? declared.set(decl.prop, []).get(decl.prop)).push(record);
          }
        }
      }
    };
    walkNodes(ast.nodes);
    conditions.set(item.file, index);
    const imports = [];
    ast.walkAtRules('import', atRule => {
      const target = atRule.params.match(/^['"]([^'"]+)['"]/);
      if (target && target[1].startsWith('.')) imports.push(posix.normalize(posix.join(dirname(item.file), target[1])));
    });
    files.push({ file: item.file, via: item.via, origin: item.origin ?? `@import from ${importedBy}`, sha256: sha256(text), tokenRules: index.size, imports });
    for (const target of imports) expand({ file: target }, item.file);
  };
  for (const link of links) expand(link, 'entry');
  return { files, conditions, declared };
}

function customAlgorithm() {
  const sf = parse(themeFile);
  const wanted = ['hexToHsl', 'clampPercent', 'generateCustomThemeVars'];
  const functions = [];
  const constants = {};
  visit(sf, node => {
    if (ts.isFunctionDeclaration(node) && node.name && wanted.includes(node.name.text)) functions.push({ name: node.name.text, text: node.getText(sf), line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1 });
    if (ts.isVariableDeclaration(node) && node.initializer && ['PRESET_PALETTES', 'ALL_PALETTES', 'DEFAULT_CUSTOM_COLOR', 'PALETTE_PREVIEW_COLORS'].includes(node.name.getText(sf))) constants[node.name.getText(sf)] = { node: node.initializer, sf, line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1 };
  });
  assert.deepEqual(functions.map(fn => fn.name).sort(), [...wanted].sort());
  return { functions, constants };
}

async function loadAlgorithm(functions) {
  const source = `${functions.map(fn => fn.text).join('\n')}\nexport { hexToHsl, clampPercent, generateCustomThemeVars };`;
  const js = (await esbuild.transform(source, { loader: 'ts', format: 'esm', target: 'node22' })).code;
  return import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`);
}

async function loadButtonContract() {
  const text = readDesktop(buttonContractFile);
  const start = text.indexOf('export const PRIMARY_SOLID_TONE');
  const end = text.indexOf('// 移动端', start);
  assert.ok(start >= 0 && end > start, 'button contract tone block');
  const source = `${text.slice(start, end)}\nexport { GHOST_TONE, DANGER_TONE };`;
  const js = (await esbuild.transform(source, { loader: 'ts', format: 'esm', target: 'node22' })).code;
  const tones = await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`);
  return { text, start, end, tones };
}

const lineAt = (text, index) => text.slice(0, index).split('\n').length;

function buttonContract(loaded) {
  const { text, start, end, tones } = loaded;
  const tokenNames = name => [...new Set([...name.matchAll(/--[\w-]+/g)].map(match => match[0]))].sort();
  const hasLine = (file, needle) => {
    const source = readDesktop(file);
    return { file, line: lineAt(source, source.indexOf(needle)), sha256: sha256(source) };
  };
  const primary = tones.PRIMARY_SOLID_TONE;
  const ghost = tones.GHOST_TONE;
  const danger = tones.DANGER_TONE;
  assert.equal(tones.buttonToneClassNames.primary, primary);
  assert.equal(tones.buttonToneClassNames.ghost, ghost);
  assert.equal(tones.buttonToneClassNames.danger, danger);
  assert.equal(tones.buttonToneClassNames.destructive, danger);
  const primaryTokens = tokenNames(primary);
  const dangerTokens = tokenNames(danger);
  const ghostTokens = tokenNames(ghost);
  assert.deepEqual(primaryTokens, primaryClassTokens);
  assert.deepEqual(dangerTokens, dangerClassTokens);
  assert.deepEqual(ghostTokens, ghostClassTokens);
  assert.match(primary, /border-transparent/);
  assert.match(primary, /text-primary-foreground/);
  assert.match(danger, /bg-\[var\(--button-destructive-bg\)\]/);
  assert.match(ghost, /bg-\[var\(--button-plain-bg\)\]/);
  return {
    source: { file: buttonContractFile, sha256: sha256(text), line: lineAt(text, start) },
    variants: tones.buttonToneClassNames,
    tones: {
      primary: { name: 'solid-primary', className: primary, tokens: primaryTokens, evidence: `${buttonContractFile}:${lineAt(text, start)}` },
      danger: { name: 'solid-danger', className: danger, tokens: dangerTokens, evidence: `${buttonContractFile}:${lineAt(text, text.indexOf('const DANGER_TONE'))}` },
      ghost: { name: 'transparent-ghost', className: ghost, tokens: ghostTokens, evidence: `${buttonContractFile}:${lineAt(text, text.indexOf('const GHOST_TONE'))}` },
    },
    variantLines: Object.fromEntries(Object.keys(tones.buttonToneClassNames).map(variant => {
      const index = text.indexOf(`  ${variant}:`);
      return [variant, index >= start && index < end ? lineAt(text, index) : null];
    })),
    semantics: {
      primary: 'solid theme-primary action; foreground --primary-foreground; at most one per view/dialog/card and only for the step that advances the flow',
      danger: 'red destructive action for delete/destructive operations',
      ghost: 'transparent default/outline/secondary/utility/shell/success/warning/ghost variants',
      nav: 'sidebar navigation row, not a main-action button',
      link: 'link-styled button, not a main-action button',
    },
    onePrimaryPerView: {
      rule: 'at most one variant="primary" per view, dialog or card; all other non-danger actions use ghost; destructive actions use danger',
      evidence: [
        hasLine(buttonContractFile, '规则：每个视图'),
        hasLine(dialogFile, 'secondaryVariant = '),
        hasLine(shadButtonFile, '实色主按钮必须显式写'),
      ],
      shadButtonDefault: 'ghost',
      dialogSecondaryDefault: 'ghost',
      primaryOptIn: 'explicit variant="primary"',
      sourceEntryNote: 'this is a source contract of the button primitive; it records the one-primary rule without scanning rendered pages or widening the S0.11 token scope',
    },
  };
}

function themeVariantContract() {
  const surfaces = readDesktop(variantContractFile);
  const controller = readDesktop(variantControllerFile);
  const picker = readDesktop(variantPickerFile);
  const owner = readDesktop(variantOwnerFile);
  const surfacesAst = parse(variantContractFile);
  const interfaceOf = (ast, name) => {
    let found = null;
    visit(ast, node => {
      if ((ts.isInterfaceDeclaration(node) || ts.isTypeAliasDeclaration(node)) && node.name.text === name) found = node;
    });
    assert.ok(found, `missing ${name}`);
    return found;
  };
  const declarationOf = (ast, name) => {
    let found = null;
    visit(ast, node => {
      if (ts.isFunctionDeclaration(node) && node.name?.text === name) found = node;
      if (ts.isVariableDeclaration(node) && node.name.getText(ast) === name) found = node;
    });
    assert.ok(found, `missing ${name}`);
    return found;
  };
  const contract = interfaceOf(surfacesAst, 'ThemeVariantContribution');
  assert.ok(ts.isInterfaceDeclaration(contract));
  const fields = contract.members.map(member => {
    const name = member.name.getText(surfacesAst);
    const type = member.type?.getText(surfacesAst);
    assert.ok(type, `missing type for ${name}`);
    return { name, optional: Boolean(member.questionToken), type };
  });
  assert.deepEqual(fields.map(field => `${field.name}${field.optional ? '?' : ''}`), ['label', 'description?', 'light?', 'dark?']);
  assert.deepEqual(fields.filter(field => /Variables/.test(field.type)).map(field => field.name), ['light', 'dark']);
  const variables = interfaceOf(surfacesAst, 'ThemeVariables');
  assert.ok(ts.isTypeAliasDeclaration(variables));
  assert.match(variables.type.getText(surfacesAst), /Readonly<Record<string, string>>/);
  const controllerAst = parse(variantControllerFile);
  const slotDeclaration = declarationOf(controllerAst, 'THEME_VARIANT_SLOT');
  const storageDeclaration = declarationOf(controllerAst, 'THEME_VARIANT_STORAGE_KEY');
  assert.equal(value(slotDeclaration.initializer), 'theme.variants');
  assert.equal(value(storageDeclaration.initializer), 'ds.ui.themeVariant');
  const pickerAst = parse(variantPickerFile);
  let optionalSlotCall = false;
  visit(pickerAst, node => {
    if (ts.isCallExpression(node) && node.expression.getText(pickerAst) === 'useOptionalSlot' && node.arguments[0]?.getText(pickerAst) === 'THEME_VARIANT_SLOT') optionalSlotCall = true;
  });
  assert.equal(optionalSlotCall, true, 'Appearance picker does not consume the optional theme.variants slot');
  assert.match(controller, /export const THEME_VARIANT_SLOT = 'theme\.variants'/);
  assert.match(controller, /export const THEME_VARIANT_STORAGE_KEY = 'ds\.ui\.themeVariant'/);
  assert.match(controller, /if \(!prop\.startsWith\('--'\) \|\| typeof value !== 'string'\) continue;/);
  assert.match(controller, /root\.style\.setProperty\(prop, value\)/);
  assert.match(controller, /root\.style\.removeProperty\(prop\)/);
  assert.match(owner, /ctx\.ui\.declareSlot\(\{ name: THEME_VARIANT_SLOT, cardinality: 'list'/);
  assert.match(owner, /ctx\.effect\(\(\) => startThemeVariantController\(ctx\.ui\.slots\)\)/);
  const line = (text, needle) => {
    const index = text.indexOf(needle);
    assert.ok(index >= 0, `missing ${needle}`);
    return lineAt(text, index);
  };
  return {
    slot: 'theme.variants',
    contribution: {
      type: 'ThemeVariantContribution',
      fields,
      variables: 'light/dark maps of CSS custom properties; names must start with --',
      persistence: 'localStorage key ds.ui.themeVariant, value <plugin id>/<contribution id>; removal restores prior inline values',
      lifecycle: 'written to document.documentElement by dstu.ui.settings; unload/default removes the contribution values',
    },
    source: {
      contract: { file: variantContractFile, line: line(surfaces, '// ------------------------------------------------------------------ theme.variants'), sha256: sha256(surfaces) },
      controller: { file: variantControllerFile, line: line(controller, 'export function startThemeVariantController'), sha256: sha256(controller) },
      picker: { file: variantPickerFile, line: line(picker, 'export const ThemeVariantPicker'), sha256: sha256(picker) },
      owner: { file: variantOwnerFile, line: line(owner, "ctx.ui.declareSlot({ name: THEME_VARIANT_SLOT"), sha256: sha256(owner) },
    },
    boundary: {
      contributionSource: 'local plugin contribution through the typed theme.variants slot; user picks it under Settings > Appearance',
      localAuthorizedOnly: 'a selected variant applies only while its contributing plugin is installed and its contribution remains in the local slot',
      notStaticPresetTokens: 'contributed CSS variables are runtime plugin overrides; they are not preset/palette rows in this baseline and must not be synthesized into static DstuTheme tokens',
      notSyncTrust: 'the localStorage pick and plugin CSS values are not synchronized settings, credentials or trusted server input; sync must not transport or trust them',
      cssBoundary: 'no arbitrary stylesheet or unparsed CSS text is accepted here; the controller only writes -- custom properties from a typed contribution',
    },
  };
}

const constant = (constants, name) => {
  assert.ok(constants[name], `missing ${name}`);
  return { value: value(constants[name].node), line: constants[name].line };
};

const candidates = [
  { name: 'playwright-chromium', resolve: () => { try { return desktopRequire('playwright').chromium.executablePath(); } catch { return null; } } },
  { name: 'msedge', resolve: () => ['/opt/microsoft/msedge/msedge', '/usr/bin/microsoft-edge', '/usr/bin/microsoft-edge-stable'].find(existsSync) },
  { name: 'google-chrome', resolve: () => ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/opt/google/chrome/chrome'].find(existsSync) },
  { name: 'chromium', resolve: () => ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/snap/bin/chromium'].find(existsSync) },
];

function pickEngine() {
  const { chromium } = desktopRequire('playwright');
  const available = candidates.map(candidate => ({ ...candidate, executable: candidate.resolve() })).filter(candidate => candidate.executable && existsSync(candidate.executable));
  assert.ok(available.length > 0, 'no chromium-based browser engine available');
  return { chromium, ...available[0], available: available.map(candidate => ({ name: candidate.name, executable: candidate.executable })) };
}

function pageCollect({ state, scopes }) {
  const html = document.documentElement;
  const body = document.body;
  html.className = state.dark ? 'dark' : '';
  for (const [name, value] of Object.entries({ 'data-theme': state.dark ? 'dark' : 'light', 'data-boot-theme': state.dark ? 'dark' : 'light', 'data-theme-palette': state.palette })) html.setAttribute(name, value);
  html.style.colorScheme = state.dark ? 'dark' : 'light';
  for (const name of ['data-wb-material', 'data-sidebar-translucent', 'data-macos-vibrancy', 'data-sidebar-visible']) {
    if (state.attributes && state.attributes[name] !== undefined) html.setAttribute(name, state.attributes[name]);
    else html.removeAttribute(name);
  }
  body.className = state.dark ? 'dark-theme' : 'light-theme';
  for (const [name, value] of Object.entries(state.inlineVars ?? {})) html.style.setProperty(name, value);
  const shell = document.querySelector('.notes-crepe-shell');
  shell.style.width = state.notesWidth || '';
  const header = document.querySelector('.notes-document-header');
  asNotes(state.notes).forEach((value, name) => header.setAttribute(name, value));
  function asNotes(notes) { return new Map(Object.entries(notes ?? {})); }

  const probe = document.createElement('div');
  document.body.appendChild(probe);
  const computedCache = new Map();
  const colorCache = new Map();
  const toRgba = value => {
    if (colorCache.has(value)) return colorCache.get(value);
    probe.style.cssText = '';
    probe.style.color = `color-mix(in srgb, ${value} 100%, ${value} 0%)`;
    const computed = getComputedStyle(probe).color;
    const srgb = computed.match(/^color\(srgb ([^)]+)\)$/);
    const rgb = computed.match(/^rgba?\(([^)]+)\)$/);
    let channels = [];
    let alpha = 1;
    if (srgb) {
      const parts = srgb[1].split('/').map(part => part.trim());
      channels = parts[0].split(/\s+/).map(Number);
      if (parts[1] !== undefined) alpha = Number(parts[1]);
    } else if (rgb) {
      const parts = rgb[1].split('/').map(part => part.trim());
      if (parts[0].includes(',')) {
        const list = parts[0].split(',').map(Number);
        channels = list.slice(0, 3).map(value => value / 255);
        if (list.length === 4) alpha = list[3];
      } else {
        channels = parts[0].split(/\s+/).slice(0, 3).map(Number).map(value => value / 255);
        if (parts[1] !== undefined) alpha = Number(parts[1]);
      }
    } else throw new Error(`unparsed color ${value} -> ${computed}`);
    const hex = channels.map(channel => Math.round(Math.min(1, Math.max(0, channel)) * 255).toString(16).padStart(2, '0')).join('').toUpperCase();
    const record = { css: computed, hex: `#${hex}`, argb: `0x${Math.round(alpha * 255).toString(16).padStart(2, '0').toUpperCase()}${hex}`, alpha, srgb: channels.map(channel => Math.round(channel * 1e6) / 1e6) };
    colorCache.set(value, record);
    return record;
  };
  const asWidth = value => {
    probe.style.cssText = 'font-size:16px';
    probe.style.width = value;
    const width = getComputedStyle(probe).width;
    return width === 'auto' || width === '' ? null : Number.parseFloat(width);
  };
  const ratio = () => html.style.getPropertyValue('--chat-thread-ratio') || getComputedStyle(html).getPropertyValue('--chat-thread-ratio').trim();
  const viewportContext = () => ({ viewport: { width: innerWidth, height: innerHeight }, rootFontSizePx: Number.parseFloat(getComputedStyle(html).fontSize), chatThreadRatio: ratio() });
  const pxUnits = { px: 1, pt: 4 / 3, q: 1.08, cm: 96 / 2.54, mm: 96 / 25.4, in: 96 };
  const parseShadow = value => {
    const layers = [];
    let depth = 0;
    let current = '';
    for (const char of value) {
      if (char === '(') depth++;
      if (char === ')') depth--;
      if (char === ',' && depth === 0) { layers.push(current.trim()); current = ''; continue; }
      current += char;
    }
    if (current.trim()) layers.push(current.trim());
    return layers.map(layer => {
      const colorPart = layer.match(/(?:rgba?|hsla?|color|oklab|oklch)\([^()]*(?:\([^()]*\)[^()]*)*\)|#[0-9a-fA-F]{3,8}/)?.[0] ?? null;
      const rest = colorPart ? layer.replace(colorPart, '') : layer;
      const lengths = [...rest.matchAll(/(-?[\d.]+)(px|rem|em)/g)].map(match => ({ px: Math.round(Number(match[1]) * (match[2] === 'px' ? 1 : 16) * 1000) / 1000, unit: match[2] }));
      return { raw: layer, color: colorPart ? toRgba(colorPart) : null, lengths, inset: /\binset\b/.test(layer) };
    });
  };
  const parseFilter = value => [...value.matchAll(/([a-z-]+)\(([^()]*)\)/g)].map(match => ({ name: match[1], args: match[2].split(/\s+/).filter(Boolean).map(arg => { const length = arg.match(/^(-?[\d.]+)(px|rem)$/); return length ? { px: Math.round(Number(length[1]) * (length[2] === 'rem' ? 16 : 1) * 1000) / 1000 } : Number.isNaN(Number(arg)) ? arg : Number(arg); }) }));
  const classify = value => {
    if (value === '') return { kind: 'missing', res: 'unresolved' };
    if (/var\(/.test(value)) return { kind: 'expression', res: 'unresolved', expression: value };
    if (/^-?[\d.]+$/.test(value)) return { kind: 'number', res: 'resolved', number: Number(value) };
    const duration = value.match(/^(-?[\d.]+)(ms|s)$/i);
    if (duration) return { kind: 'duration', res: 'resolved', ms: duration[2].toLowerCase() === 's' ? Number(duration[1]) * 1000 : Number(duration[1]) };
    if (/^(ease|ease-in|ease-out|ease-in-out|linear|step-start|step-end)$/.test(value) || /^(cubic-bezier|steps|linear)\(/.test(value)) return { kind: 'easing', res: 'expression', easing: value };
    if (/^-?[\d.]+ [\d.]+% [\d.]+%$/.test(value)) return { kind: 'hslTriplet', res: 'triplet', hsl: `hsl(${value})`, color: toRgba(`hsl(${value})`) };
    if (CSS.supports('color', value)) return { kind: 'color', res: 'resolved', color: toRgba(value) };
    if (CSS.supports('background-image', value)) return { kind: 'paint', res: 'expression', paint: value };
    if (CSS.supports('box-shadow', value)) return { kind: 'shadow', res: 'resolved', shadow: parseShadow(value) };
    if (CSS.supports('backdrop-filter', value) || CSS.supports('filter', value)) return { kind: 'filter', res: 'resolved', filter: parseFilter(value) };
    if (CSS.supports('transition', value)) return { kind: 'transition', res: 'expression', transition: value };
    if (value.includes(',') && CSS.supports('font-family', value)) return { kind: 'fontFamily', res: 'resolved', stack: value };
    const unitMatch = value.match(/^(-?[\d.]+)(px|rem|em|%|vw|vh|dvh|svh|lvh|vmin|vmax|ch|ex|pt|q|cm|mm|in)$/);
    if (unitMatch) {
      const amount = Number(unitMatch[1]);
      const unit = unitMatch[2];
      if (unit === 'em') return { kind: 'length', res: 'contextual', expression: value, pxAtContext: amount * 16, context: { emBasePx: 16, note: 'em resolves against the consuming element font size' } };
      if (unit === '%' || ['vw', 'vh', 'dvh', 'svh', 'lvh', 'vmin', 'vmax', 'ch', 'ex'].includes(unit)) return { kind: 'length', res: 'contextual', expression: value, pxAtContext: asWidth(value), context: viewportContext() };
      const px = Math.round(amount * (unit === 'rem' ? 16 : pxUnits[unit]) * 1000) / 1000;
      return { kind: 'length', res: 'resolved', px, unit, expression: unit === 'px' && `${amount}px` === value ? undefined : value };
    }
    if (/^(calc|max|min|clamp)\(/.test(value)) {
      const width = asWidth(value);
      if (/%|v[wh]|dvh|svh|lvh|vmin|vmax/.test(value)) return { kind: 'length', res: 'contextual', expression: value, pxAtContext: width, context: viewportContext() };
      if (/(^|[^\w-])em([^\w-]|$)/.test(value)) return { kind: 'length', res: 'contextual', expression: value, pxAtContext: width, context: { emBasePx: 16 } };
      if (width !== null) return { kind: 'length', res: 'resolved', px: width, unit: 'px', expression: value };
    }
    return { kind: 'keyword', res: 'expression', keyword: value };
  };
  const resolve = value => {
    if (computedCache.has(value)) return computedCache.get(value);
    const result = classify(value);
    computedCache.set(value, result);
    return result;
  };
  const names = new Set();
  const collect = node => {
    if (node.style) for (const prop of node.style) if (prop.startsWith('--')) names.add(prop);
    if (node.styleSheet) collect(node.styleSheet);
    if (node.cssRules) for (const rule of node.cssRules) collect(rule);
  };
  for (const sheet of document.styleSheets) collect(sheet);
  const all = [...names].sort();
  const read = element => {
    const style = getComputedStyle(element);
    const values = new Map();
    for (const name of all) values.set(name, style.getPropertyValue(name).trim());
    return values;
  };
  const record = values => Object.fromEntries([...values].map(([name, value]) => [name, { computed: value, value: resolve(value) }]));
  const root = read(html);
  const scopeRecords = {};
  for (const scope of scopes) {
    const element = document.querySelector(scope);
    if (!element) { scopeRecords[scope] = null; continue; }
    const values = read(element);
    const delta = {};
    for (const [name, value] of values) if (root.get(name) !== value) delta[name] = { computed: value, value: resolve(value) };
    scopeRecords[scope] = delta;
  }
  const media = {
    dark: matchMedia('(prefers-color-scheme: dark)').matches,
    contrastMore: matchMedia('(prefers-contrast: more)').matches,
    reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches,
    reducedTransparency: matchMedia('(prefers-reduced-transparency: reduce)').matches,
    coarsePointer: matchMedia('(pointer: coarse)').matches,
    containerInlineSize: document.querySelector('.notes-crepe-shell').getBoundingClientRect().width,
  };
  const result = { names: all, root: record(root), scopes: scopeRecords, computed: Object.fromEntries(root), media, viewport: { width: innerWidth, height: innerHeight, dpr: devicePixelRatio, rootFontSize: getComputedStyle(html).fontSize } };
  probe.remove();
  return result;
}

const markup = () => {
  const links = [...entryLinks(), ...hostEntries].map(entry => `<link rel="stylesheet" href="/${entry.file}">`).join('');
  return `<!doctype html><html data-theme="light" data-theme-palette="default"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">${links}</head><body class="light-theme"><div id="root" data-shell-role="app-shell"><div class="chat-v2"><div class="chat-container"><div class="message assistant"><div class="message-content"><div class="markdown-content"></div></div></div><div class="timeline-panel"></div><div class="streaming-markdown"></div><div class="ui-tooltip-in" data-side="top"></div><div class="chat-scroll-bottom-ring"></div></div><div class="unified-input-docked"><div class="chat-loading-shell__composer-panel"></div></div><div class="chat-empty-composer-layout"><div class="chat-empty-composer-layout__input unified-input-docked"></div></div></div><div class="notes-crepe-shell"><div class="notes-column"><div class="notes-document-header" data-notes-small-text="false" data-notes-full-width="false" data-notes-font="sans"></div><div class="notes-appearance-panel"></div></div></div><div data-wb-workbench-root><div class="wb-menubar"></div><div class="wb-dock-item"></div></div><div class="scroll-fade-shell"></div><div class="font-sidebar-study-ui"></div><div class="os-scrollbar os-theme-deep-student"><div class="os-scrollbar-track"><div class="os-scrollbar-handle"></div></div></div><div class="notes-appearance-panel"><button class="ui-state-colors"></button></div><div data-shell-surface="navigation"></div><div data-shell-surface="workspace"></div></div></body></html>`;
};

const scopes = ['.chat-v2', '.chat-container', '.unified-input-docked', '.chat-scroll-bottom-ring', '.markdown-content', '.streaming-markdown', '.timeline-panel', '.ui-tooltip-in', '.notes-crepe-shell', '.notes-column', '.notes-appearance-panel', '[data-wb-workbench-root]', '[data-shell-surface="navigation"]', '.scroll-fade-shell', '.font-sidebar-study-ui', '.os-theme-deep-student'];

const sheetFiles = new Map();

async function enableCdp(context, page) {
  const cdp = await context.newCDPSession(page);
  cdp.on('CSS.styleSheetAdded', event => sheetFiles.set(event.header.styleSheetId, event.header.sourceURL));
  await cdp.send('DOM.enable');
  await cdp.send('CSS.enable');
  return cdp;
}

async function primeRanges(cdp, expected) {
  for (let attempt = 0; attempt < 40 && sheetFiles.size < expected; attempt++) await new Promise(resolve => setTimeout(resolve, 50));
  for (const styleSheetId of sheetFiles.keys()) await cdp.send('CSS.getStyleSheetText', { styleSheetId });
  return sheetFiles.size;
}

const applied = (raw, computed) => {
  let depth = 0;
  let value = norm(raw);
  for (;;) {
    let changed = false;
    value = value.replace(/var\(\s*(--[\w-]+)\s*(?:,\s*([^()]*(?:\([^()]*\))?[^()]*))?\)/g, (_match, name, fallback) => {
      const found = computed[name];
      if (found !== undefined && found !== '') { changed = true; return found; }
      if (fallback !== undefined) { changed = true; return fallback.trim(); }
      return `var(${name})`;
    });
    if (!changed || ++depth > 16) return norm(value);
  }
};

async function provenance(cdp, selector, conditions) {
  const { root: documentNode } = await cdp.send('DOM.getDocument', { depth: 0 });
  const { nodeId } = await cdp.send('DOM.querySelector', { nodeId: documentNode.nodeId, selector });
  if (!nodeId) return new Map();
  const matched = await cdp.send('CSS.getMatchedStylesForNode', { nodeId });
  const grouped = new Map();
  for (const item of matched.matchedCSSRules) {
    const rule = item.rule;
    const url = sheetFiles.get(rule.styleSheetId);
    const sourceFile = url ? decodeURIComponent(new URL(url).pathname).slice(1) : null;
    for (const property of rule.style.cssProperties) {
      if (!property.name.startsWith('--') || property.disabled || !property.range) continue;
      const entry = { file: sourceFile, line: property.range.startLine + 1, selector: norm(rule.selectorList.text), conditions: (conditions.get(sourceFile)?.get(property.range.startLine + 1)) ?? [], raw: norm(property.value), important: Boolean(property.important) };
      const key = `${property.name}\u0000${entry.file}\u0000${entry.line}\u0000${entry.selector}`;
      if (!grouped.has(key)) grouped.set(key, { name: property.name, ...entry });
    }
  }
  const byName = new Map();
  for (const entry of grouped.values()) (byName.get(entry.name) ?? byName.set(entry.name, []).get(entry.name)).push(entry);
  return byName;
}

function attachProvenance(records, byName, computed, fileHashes) {
  const stats = { tokens: 0, matched: 0, unmatched: 0, inherited: 0, important: 0 };
  for (const [name, record] of Object.entries(records)) {
    stats.tokens++;
    if (record.computed === '') continue;
    const hits = byName.get(name) ?? [];
    let chosen = null;
    const ordered = [...hits].sort((a, b) => Number(a.important) - Number(b.important));
    for (let index = ordered.length - 1; index >= 0; index--) if (applied(ordered[index].raw, computed) === norm(record.computed)) { chosen = ordered[index]; break; }
    if (!chosen && hits.length) chosen = ordered[ordered.length - 1];
    if (!chosen) { stats.unmatched++; continue; }
    record.src = { file: chosen.file, line: chosen.line, selector: chosen.selector, conditions: chosen.conditions, raw: chosen.raw, important: chosen.important, verified: applied(chosen.raw, computed) === norm(record.computed), sha256: fileHashes.get(chosen.file) ?? null };
    stats.matched++;
    if (chosen.important) stats.important++;
  }
  return stats;
}

const groups = {
  palette: name => /^--(primary|ring|brand-primary|brand-primary-dark|primary-color|primary-foreground)$|^--settings-nav-accent-/.test(name),
  base: name => /^--(background|foreground|card|popover|secondary|muted|accent|destructive|border|input$|danger|text-)/.test(name),
  status: name => /^--(success|warning|info|status-)/.test(name),
  surface: name => /^--surface-|^--overlay|^--interactive-|^--shell-(backdrop|panel|float|rim|seam|glow|grid|titlebar|surface|chrome|control|workspace|inspector|toolbar|window)/.test(name),
  sidebar: name => /^--sidebar(-|$)|^--nav-background|^--titlebar-background|^--shell-navigation/.test(name),
  button: name => /^--button-|^--control-height|^--touch-target/.test(name),
  input: name => /^--input-/.test(name),
  dialog: name => /^--dialog-|^--mobile-sheet-|^--notes-(dialog|overlay|popup)/.test(name),
  menu: name => /^--menu-/.test(name),
  composer: name => /^--composer-|^--unified-input/.test(name),
  shape: name => /^--radius|^--wb-radius|^--notes-radius|^--button-radius|^--wb-window-radius|^--chat-block-radius/.test(name),
  size: name => /^--(size-|space-|button-height|button-padding|button-icon|notes-(text|gutter|content|doc-title)|wb-(sidebar|dock|traffic|titlebar|menubar))/.test(name),
  typography: name => /^--(font-|line-height-|letter-spacing-|m-text-|notes-heading|notes-body|notes-inline-code|chat-heading|chat-thinking-font)/.test(name),
  motion: name => /dur|ease|motion|transition|--wb-ease-|--ease-|--acc-|--page-|--modal-|--panel-|--dropdown-|--digit-|--text-swap-|--icon-swap-|--resize-/.test(name),
  workbench: name => /^--wb-/.test(name),
  chat: name => /^--chat-/.test(name),
  notes: name => /^--notes-/.test(name),
  resourceIcon: name => /^--resource-icon/.test(name),
  scrollbar: name => /^--scrollbar-|^--os-|^--scroll-ring/.test(name),
};

const camel = name => name.replace(/^--/, '').replace(/-([a-z0-9])/g, (_match, char) => char.toUpperCase()).replace(/[^a-zA-Z0-9]/g, '');

function kotlinOf(name, record) {
  const value = record.value;
  const base = { name: camel(name) };
  const text = /font-size|text-|letter-spacing|notes-.*-size|chat-heading|chat-thinking/.test(name);
  if (value.kind === 'hslTriplet') return { ...base, type: 'Color', value: value.color.argb, hex: value.color.hex, alpha: value.color.alpha, wrap: 'hsl(var(--token))' };
  switch (value.kind) {
    case 'color': return { ...base, type: 'Color', value: value.color.argb, hex: value.color.hex, alpha: value.color.alpha };
    case 'duration': return { ...base, type: 'Duration', value: `${value.ms}ms` };
    case 'easing': return { ...base, type: 'Easing', value: value.easing };
    case 'fontFamily': return { ...base, type: 'FontFamily', value: value.stack };
    case 'shadow': return { ...base, type: 'List<Shadow>', value: value.shadow };
    case 'filter': return { ...base, type: 'List<FilterEffect>', value: value.filter };
    case 'length':
      if (text) return { ...base, type: 'TextUnit', value: value.res === 'resolved' ? `${value.px}.sp` : `${value.expression} (relative)`, sp: value.px ?? null, em: value.res === 'contextual' ? value.expression : undefined, source: value.res };
      return { ...base, type: 'Dp', value: value.res === 'resolved' ? `${value.px}.dp` : `${value.expression} (contextual)`, dp: value.px ?? value.pxAtContext ?? null, expression: value.expression, source: value.res };
    case 'number':
      if (/weight/.test(name)) return { ...base, type: 'Int', value: value.number };
      if (/scale|line-height|lineHeight|ratio/.test(name)) return { ...base, type: 'Float', value: value.number };
      if (groups.shape(name)) return { ...base, type: 'Dp', value: `${value.number}.dp` };
      return { ...base, type: 'Float', value: value.number };
    default: return { ...base, type: 'String', value: value.expression ?? value.paint ?? value.shadow ?? value.filter ?? value.transition ?? value.keyword ?? value.css };
  }
}

export async function collect() {
  const plan = readFileSync(resolve(root, planFile), 'utf8').split('\n');
  const unitIndex = plan.findIndex(line => line.startsWith('| S0.11 |'));
  const sectionIndex = plan.findIndex(line => line.startsWith('### 7.0 '));
  assert.ok(unitIndex >= 0 && sectionIndex >= 0, 'plan anchors');
  const sectionRows = [];
  for (let index = sectionIndex + 1; index < plan.length && !/^#{2,3} /.test(plan[index]); index++) if (plan[index].startsWith('|')) sectionRows.push({ line: index + 1, row: plan[index].slice(0, 200) });
  assert.ok(sectionRows.length >= 7);

  const algorithm = customAlgorithm();
  const theme = await loadAlgorithm(algorithm.functions);
  const frozen = {
    commit: frozenCommit,
    head: git(desktop, 'rev-parse', 'HEAD'),
    tree: git(desktop, 'rev-parse', 'HEAD^{tree}'),
  };
  assert.equal(frozen.head, frozenCommit, 'desktop HEAD differs from the frozen baseline');
  const button = buttonContract(await loadButtonContract());
  const themeVariants = themeVariantContract();
  const sourceHashes = Object.fromEntries([buttonContractFile, shadButtonFile, dialogFile, 'src/styles/theme-colors.css', variantContractFile, variantControllerFile, variantPickerFile, variantOwnerFile, themeFile].map(file => [file, sha256(readDesktop(file))]));
  const presets = constant(algorithm.constants, 'PRESET_PALETTES').value;
  const allPalettesValue = constant(algorithm.constants, 'ALL_PALETTES');
  const allPalettes = [...presets, 'custom'];
  const defaultCustomColor = constant(algorithm.constants, 'DEFAULT_CUSTOM_COLOR').value;
  const previews = constant(algorithm.constants, 'PALETTE_PREVIEW_COLORS').value;
  assert.equal(presets.length, 9);
  assert.equal(Object.keys(previews).length, 9);
  assert.equal(defaultCustomColor, '#6366f1');
  assert.equal(allPalettesValue.value.includes('custom'), true);

  const links = [...entryLinks(), ...hostEntries.map(entry => ({ file: entry.file, via: entry.via, origin: 'feature host import' }))];
  const { files: chain, conditions, declared } = chainOf(links);
  const fileHashes = new Map(chain.map(item => [item.file, item.sha256]));
  for (const item of chain) assert.ok(existsSync(resolve(desktop, item.file)), item.file);

  const engine = pickEngine();
  const browser = await engine.chromium.launch({ executablePath: engine.executable, args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'] });
  try {
  const browserVersion = browser.version();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', locale: 'zh-CN' });
  const page = await context.newPage();
  const requested = [];
  await page.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.hostname !== 'design.local') return route.abort();
    if (url.pathname === '/baseline.html') return route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: markup() });
    const target = resolve(desktop, decodeURIComponent(url.pathname).slice(1));
    if (!target.startsWith(desktop) || !existsSync(target)) return route.fulfill({ status: 404, body: '' });
    const bytes = readFileSync(target);
    requested.push({ url: url.pathname, file: target.slice(desktop.length + 1), bytes: bytes.length });
    return route.fulfill({ status: 200, contentType: target.endsWith('.css') ? 'text/css; charset=utf-8' : 'application/octet-stream', body: bytes });
  });
  const cdp = await enableCdp(context, page);
  await page.goto('http://design.local/baseline.html', { waitUntil: 'load' });
  const primed = await primeRanges(cdp, chain.length);
  const loadedSheets = [...new Set([...sheetFiles.values()].map(url => decodeURIComponent(new URL(url).pathname).slice(1)))].sort();
  for (const item of chain) assert.ok(requested.some(entry => entry.file === item.file), `not loaded: ${item.file}`);

  const setMedia = async state => {
    await cdp.send('Emulation.setEmulatedMedia', { media: 'screen', features: [
      { name: 'prefers-color-scheme', value: state.dark ? 'dark' : 'light' },
      { name: 'prefers-contrast', value: state.contrast ? 'more' : 'no-preference' },
      { name: 'prefers-reduced-motion', value: state.reducedMotion ? 'reduce' : 'no-preference' },
      { name: 'prefers-reduced-transparency', value: state.reducedTransparency ? 'reduce' : 'no-preference' },
    ] });
    if (state.mobile) {
      await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
      await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    } else {
      await cdp.send('Emulation.clearDeviceMetricsOverride');
      await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: false });
    }
  };

  const totals = { tokens: 0, matched: 0, unmatched: 0, inherited: 0, important: 0 };
  const measure = async state => {
    await setMedia(state);
    const data = await page.evaluate(pageCollect, { state, scopes });
    const rootStats = attachProvenance(data.root, await provenance(cdp, 'html', conditions), data.computed, fileHashes);
    for (const key of Object.keys(totals)) totals[key] += rootStats[key];
    const scopeStats = {};
    for (const scope of scopes) {
      const records = data.scopes[scope];
      if (!records) { scopeStats[scope] = 'missing'; continue; }
      const stats = attachProvenance(records, await provenance(cdp, scope, conditions), data.computed, fileHashes);
      for (const [name, record] of Object.entries(records)) if (!record.src && record.computed !== '' && data.root[name]?.src) { record.src = { ...data.root[name].src, inheritedFrom: 'html' }; stats.inherited++; totals.inherited++; }
      scopeStats[scope] = stats;
    }
    return { data, scopeStats };
  };

  const baseState = { palette: 'default', dark: false, notes: { 'data-notes-small-text': 'false', 'data-notes-full-width': 'false', 'data-notes-font': 'sans' } };
  const baseLight = await measure(baseState);
  const baseDark = await measure({ ...baseState, dark: true });

  const decorate = records => Object.fromEntries(Object.entries(records).map(([name, record]) => [name, { raw: record.src?.raw ?? record.computed, computed: record.computed, ...record.value, src: record.src, kotlin: kotlinOf(name, record) }]));
  const scenarioOf = measured => ({ tokens: decorate(measured.data.root), scopes: Object.fromEntries(scopes.map(scope => [scope, measured.data.scopes[scope] ? decorate(measured.data.scopes[scope]) : null])) });
  const scenarios = {
    'light/default': { state: 'light + data-theme-palette=default + 1440x900 root font 16px', ...scenarioOf(baseLight) },
    'dark/default': { state: 'dark + data-theme-palette=default + 1440x900 root font 16px', ...scenarioOf(baseDark) },
  };
  const buttonRuntime = Object.fromEntries(['light', 'dark'].map(mode => {
    const tokens = scenarios[`${mode}/default`].tokens;
    const prominent = Object.fromEntries(prominentFamily.map(name => [name, {
      raw: tokens[name]?.raw,
      declared: tokens[name]?.src?.raw,
      computed: tokens[name]?.computed,
      hex: tokens[name]?.color?.hex,
      argb: tokens[name]?.kotlin?.value,
      verified: tokens[name]?.src?.verified === true,
      source: tokens[name]?.src ? { file: tokens[name].src.file, line: tokens[name].src.line, selector: tokens[name].src.selector } : null,
    }]));
    return [mode, {
      primary: { raw: tokens['--primary']?.raw, computed: tokens['--primary']?.computed, hex: tokens['--primary']?.color?.hex },
      primaryForeground: { raw: tokens['--primary-foreground']?.raw, computed: tokens['--primary-foreground']?.computed, hex: tokens['--primary-foreground']?.color?.hex },
      prominent,
      prominentIsSolidPrimary: tokens['--button-prominent-bg']?.src?.raw === 'hsl(var(--primary))'
        && tokens['--button-prominent-bg']?.computed === `hsl(${tokens['--primary']?.computed})`
        && tokens['--button-prominent-bg']?.color?.hex === tokens['--primary']?.color?.hex,
      destructiveIsSolidDanger: tokens['--button-destructive-bg']?.src?.raw?.includes('hsl(var(--destructive))') === true
        && tokens['--destructive']?.computed === tokens['--danger']?.computed
        && tokens['--destructive']?.color?.hex === tokens['--danger']?.color?.hex,
      ghostIsTransparent: tokens['--button-plain-bg']?.computed === 'transparent'
        && (tokens['--button-plain-bg']?.color?.alpha ?? -1) === 0
        && (tokens['--button-plain-border']?.color?.alpha ?? -1) === 0,
    }];
  }));
  assert.equal(buttonRuntime.light.prominentIsSolidPrimary, true, 'light prominent background is not the solid primary');
  assert.equal(buttonRuntime.dark.prominentIsSolidPrimary, true, 'dark prominent background is not the solid primary');
  assert.equal(buttonRuntime.light.destructiveIsSolidDanger, true, 'light destructive background is not the solid danger');
  assert.equal(buttonRuntime.dark.destructiveIsSolidDanger, true, 'dark destructive background is not the solid danger');
  assert.equal(buttonRuntime.light.ghostIsTransparent, true, 'light ghost is not transparent');
  assert.equal(buttonRuntime.dark.ghostIsTransparent, true, 'dark ghost is not transparent');
  assert.deepEqual(button.tones.primary.tokens, primaryClassTokens);
  assert.deepEqual(button.variantLines, Object.fromEntries(Object.keys(button.variants).map(name => [name, button.variantLines[name]])));
  assert.ok(Object.values(button.variantLines).every(line => Number.isInteger(line)));
  const ghostVariants = ['default', 'outline', 'secondary', 'ghost', 'utility', 'shell', 'success', 'warning'];
  assert.deepEqual(ghostVariants.filter(name => button.variants[name] !== button.variants.ghost), [], 'non-danger variants are not ghost');
  assert.equal(button.variants.danger, button.variants.destructive);
  const buttonCoverage = Object.fromEntries(Object.entries(button.variants).map(([variant, className]) => [variant, {
    tone: variant === 'primary' ? 'primary' : ['danger', 'destructive'].includes(variant) ? 'danger' : variant === 'nav' ? 'nav' : variant === 'link' ? 'link' : 'ghost',
    className,
    tokens: [...new Set([...className.matchAll(/--[\w-]+/g)].map(match => match[0]))].sort(),
    line: button.variantLines[variant],
  }]));
  const unavailable = Object.fromEntries(Object.entries(buttonCoverage).map(([variant, entry]) => [variant, entry.tokens.filter(name => !scenarios['light/default'].tokens[name])]));
  assert.deepEqual(Object.fromEntries(Object.entries(unavailable).filter(([, names]) => names.length > 0)), {}, 'button variant references a token absent from the default scenario');
  const baseTokens = new Set([...Object.keys(scenarios['light/default'].tokens), ...Object.keys(scenarios['dark/default'].tokens)]);
  const declaredPaletteAccent = {};
  for (const palette of allPalettes.filter(name => name !== 'default')) {
    declaredPaletteAccent[palette] = ['light', 'dark'].map(mode => {
      const selector = mode === 'dark' ? `:root.dark[data-theme-palette='${palette}']` : `:root[data-theme-palette='${palette}']`;
      return { mode, selector, declarations: [...declared.entries()].filter(([, records]) => records.some(record => record.selector === selector)).map(([name]) => name) };
    });
  }
  const palettesRuntime = {};
  const differingNames = new Set();
  for (const palette of allPalettes) {
    for (const dark of [false, true]) {
      const measured = palette === 'default' ? (dark ? baseDark : baseLight) : await measure({ ...baseState, palette, dark });
      const reference = dark ? baseDark.data : baseLight.data;
      const accent = decorate(Object.fromEntries(accentNames.map(name => [name, measured.data.root[name]])));
      const differing = {};
      for (const [name, record] of Object.entries(measured.data.root)) if (record.computed !== reference.root[name].computed) differing[name] = decorate({ [name]: record })[name];
      for (const name of Object.keys(differing)) differingNames.add(name);
      const neutralDiffering = neutralNames.filter(name => differing[name]);
      palettesRuntime[`${dark ? 'dark' : 'light'}/${palette}`] = {
        palette, mode: dark ? 'dark' : 'light', accent, differing,
        accentOverridden: accentNames.filter(name => differing[name]),
        derivedDiffering: Object.keys(differing).filter(name => !accentNames.includes(name)),
        neutralDiffering,
      };
    }
  }

  const scenarioTokenSets = Object.fromEntries(Object.entries(palettesRuntime).map(([id, entry]) => [id, {
    accent: Object.keys(entry.accent),
    accentNamesComplete: accentNames.every(name => Object.hasOwn(entry.accent, name)),
    computed: Object.values(entry.accent).filter(token => token.computed !== '').length,
    originalTokensComplete: Object.keys(entry.accent).length === accentNames.length
      && Object.values(entry.accent).every(token => token.computed !== '')
      && Object.keys(entry.differing).every(name => baseTokens.has(name)),
  }]));
  const incompletePaletteScenarios = Object.entries(scenarioTokenSets).filter(([, entry]) => !entry.originalTokensComplete).map(([id]) => id);
  assert.deepEqual(incompletePaletteScenarios, [], 'a palette scenario loses original static tokens');
  assert.deepEqual(Object.entries(scenarioTokenSets).filter(([, entry]) => !entry.accentNamesComplete).map(([id]) => id), [], 'a palette scenario loses a named accent token');

  const functionLines = Object.fromEntries(algorithm.functions.map(fn => [fn.name, fn.line]));
  const customRuns = [];
  for (const input of [defaultCustomColor, previews['bright-pink'], previews.paper]) {
    const runs = {};
    for (const dark of [false, true]) {
      const expected = theme.generateCustomThemeVars(input, dark);
      const measured = await measure({ ...baseState, palette: 'custom', dark, inlineVars: expected });
      const observed = Object.fromEntries(Object.keys(expected).map(name => [name, measured.data.root[name].computed]));
      const matches = Object.entries(expected).every(([name, value]) => applied(value, measured.data.root[name].computed ? { ...Object.fromEntries(Object.entries(measured.data.root).map(([key, record]) => [key, record.computed])) } : {}) === norm(observed[name]));
      for (const name of Object.keys(expected)) measured.data.root[name].src = { file: themeFile, line: functionLines.generateCustomThemeVars, symbol: 'generateCustomThemeVars', inlineStyle: true, verified: true };
      const hsl = theme.hexToHsl(input);
      const constraints = {
        primarySaturation: [theme.clampPercent(hsl[1], 48, 76), { min: 48, max: 76 }],
        primaryLightness: dark ? [theme.clampPercent(hsl[2] + 18, 66, 74), { min: 66, max: 74, from: 'hsl.l + 18' }] : [theme.clampPercent(hsl[2] - 6, 32, 42), { min: 32, max: 42, from: 'hsl.l - 6' }],
        ringLightness: dark ? [Math.max(60, theme.clampPercent(hsl[2] + 18, 66, 74) - 6), 'max(60, primaryL - 6)'] : [Math.min(theme.clampPercent(hsl[2] - 6, 32, 42) + 6, 50), 'min(primaryL + 6, 50)'],
        ringSaturation: [Math.max(38, theme.clampPercent(hsl[1], 48, 76) - 8), 'max(38, primaryS - 8)'],
        foreground: dark ? 'fixed 220 30% 10%' : 'fixed 0 0% 100%',
      };
      const reference = (dark ? baseDark : baseLight).data.root;
      const derived = Object.keys(measured.data.root).filter(name => measured.data.root[name].computed !== reference[name]?.computed && !accentNames.includes(name));
      runs[dark ? 'dark' : 'light'] = {
        input, hexToHsl: hsl, constraints, algorithm: expected, browserComputed: observed,
        tokens: decorate(Object.fromEntries(Object.keys(expected).map(name => [name, measured.data.root[name]]))),
        matches,
        derivedDiffering: derived,
        neutralDiffering: neutralNames.filter(name => derived.includes(name)),
      };
      await page.evaluate(names => { for (const name of names) document.documentElement.style.removeProperty(name); }, accentNames);
    }
    customRuns.push({ input, source: input === defaultCustomColor ? 'useTheme.ts DEFAULT_CUSTOM_COLOR' : 'useTheme.ts PALETTE_PREVIEW_COLORS', runs });
  }

  const variants = {};
  const variant = async (id, state, note, compareTo = 'light/default') => {
    const measured = await measure(state);
    const reference = compareTo.startsWith('dark') ? baseDark.data : baseLight.data;
    const diffOf = (records, baseRecords) => {
      const diff = {};
      for (const [name, record] of Object.entries(records)) if (!baseRecords || baseRecords[name]?.computed !== record.computed) diff[name] = decorate({ [name]: record })[name];
      return diff;
    };
    const entry = { state, note, comparedTo: compareTo, root: diffOf(measured.data.root, reference.root), scopes: {}, media: measured.data.media };
    for (const scope of scopes) if (measured.data.scopes[scope]) { const diff = diffOf(measured.data.scopes[scope], reference.scopes[scope]); if (Object.keys(diff).length) entry.scopes[scope] = diff; }
    variants[id] = entry;
  };
  const notesState = notes => ({ ...baseState, notes });
  await variant('notes/small-text', notesState({ 'data-notes-small-text': 'true', 'data-notes-full-width': 'false', 'data-notes-font': 'sans' }), '.notes-crepe-shell:has(.notes-document-header[data-notes-small-text=true])');
  await variant('notes/full-width', notesState({ 'data-notes-small-text': 'false', 'data-notes-full-width': 'true', 'data-notes-font': 'sans' }), '.notes-crepe-shell:has(.notes-document-header[data-notes-full-width=true])');
  await variant('notes/font-serif', notesState({ 'data-notes-small-text': 'false', 'data-notes-full-width': 'false', 'data-notes-font': 'serif' }), '.notes-crepe-shell:has(.notes-document-header[data-notes-font=serif])');
  await variant('notes/font-mono', notesState({ 'data-notes-small-text': 'false', 'data-notes-full-width': 'false', 'data-notes-font': 'mono' }), '.notes-crepe-shell:has(.notes-document-header[data-notes-font=mono])');
  await variant('notes/narrow-container', { ...notesState({ 'data-notes-small-text': 'false', 'data-notes-full-width': 'false', 'data-notes-font': 'sans' }), notesWidth: '600px' }, '@container notes-shell (max-width: 640px)');
  await variant('notes/mobile-coarse', { ...notesState({ 'data-notes-small-text': 'false', 'data-notes-full-width': 'false', 'data-notes-font': 'sans' }), mobile: true }, '@media (max-width: 767px), (pointer: coarse) at 390x844');
  await variant('notes/dark-small-text-serif', { ...notesState({ 'data-notes-small-text': 'true', 'data-notes-full-width': 'false', 'data-notes-font': 'serif' }), dark: true }, 'dark notes shell with small text and serif stack', 'dark/default');
  await variant('workbench/material-minimal', { ...baseState, attributes: { 'data-wb-material': 'minimal' } }, ':root[data-wb-material=minimal]');
  await variant('workbench/material-reduced', { ...baseState, attributes: { 'data-wb-material': 'reduced' } }, ':root[data-wb-material=reduced]');
  await variant('workbench/material-minimal-dark', { ...baseState, dark: true, attributes: { 'data-wb-material': 'minimal' } }, ':root.dark[data-wb-material=minimal]', 'dark/default');
  await variant('shell/sidebar-translucent', { ...baseState, attributes: { 'data-sidebar-translucent': 'true', 'data-sidebar-visible': 'true' } }, 'translucent sidebar tokens');
  await variant('shell/sidebar-translucent-dark', { ...baseState, dark: true, attributes: { 'data-sidebar-translucent': 'true', 'data-sidebar-visible': 'true' } }, 'dark translucent sidebar tokens', 'dark/default');
  await variant('shell/macos-vibrancy', { ...baseState, attributes: { 'data-sidebar-translucent': 'true', 'data-sidebar-visible': 'true', 'data-macos-vibrancy': 'true' } }, 'macOS vibrancy tokens');
  await variant('motion/reduced', { ...baseState, reducedMotion: true }, 'prefers-reduced-motion: reduce');
  await variant('transparency/reduced', { ...baseState, reducedTransparency: true }, 'prefers-reduced-transparency: reduce');
  await variant('contrast/more', { ...baseState, contrast: true }, 'prefers-contrast: more');
  await variant('contrast/more-dark', { ...baseState, dark: true, contrast: true }, 'dark prefers-contrast: more', 'dark/default');

  const variantTokenNames = new Set(Object.values(variants).flatMap(entry => [...Object.keys(entry.root), ...Object.values(entry.scopes).flatMap(tokens => Object.keys(tokens))]));
  const phantomVariantTokens = [...variantTokenNames].filter(name => !baseTokens.has(name)).sort();
  assert.deepEqual(phantomVariantTokens, [], 'a display variant introduces a token outside the original computed set');

  const scopedNames = new Set([...declared.entries()].filter(([, records]) => records.some(record => !/^(:root|:where\(\s*:root|html\b)/.test(record.selector))).map(([name]) => name));
  const scopeHomes = name => scopes.filter(scope => scenarios['light/default'].scopes[scope]?.[name] || scenarios['dark/default'].scopes[scope]?.[name]);
  const dependencyOf = name => {
    const decls = declared.get(name) ?? [];
    const deps = new Set();
    for (const decl of decls) for (const match of decl.raw.matchAll(/var\(\s*(--[\w-]+)/g)) deps.add(match[1]);
    return { decls, deps: [...deps] };
  };
  const unresolvedDetail = [];
  const scopedDetail = [];
  for (const [id, scenario] of Object.entries(scenarios)) for (const [name, token] of Object.entries(scenario.tokens)) {
    if (token.res !== 'unresolved') continue;
    const info = dependencyOf(name);
    const missing = info.deps.filter(dep => !declared.has(dep));
    if (token.computed === '' && missing.length === 0 && scopedNames.has(name)) {
      token.res = 'scoped';
      token.declaredOnlyIn = info.decls.map(decl => ({ file: decl.file, line: decl.line, selector: decl.selector, conditions: decl.conditions, raw: decl.raw }));
      token.capturedIn = scopeHomes(name);
      token.kotlin = kotlinOf(name, { value: token });
      scopedDetail.push({ scenario: id, name, declaredOnlyIn: token.declaredOnlyIn, capturedIn: token.capturedIn });
      continue;
    }
    if (token.computed === '' && missing.length === 0 && info.decls.length > 0 && info.decls.every(decl => decl.conditions.length > 0)) {
      token.res = 'conditional';
      token.conditions = [...new Set(info.decls.flatMap(decl => decl.conditions))];
      token.declaredAt = info.decls.map(decl => ({ file: decl.file, line: decl.line, selector: decl.selector, raw: decl.raw }));
      token.kotlin = kotlinOf(name, { value: token });
      scopedDetail.push({ scenario: id, name, conditional: true, conditions: token.conditions, declaredAt: token.declaredAt, capturedIn: [] });
      continue;
    }
    token.missingDependencies = missing;
    unresolvedDetail.push({
      scenario: id, name,
      computed: token.computed,
      reason: missing.length ? `missing dependency ${missing.join(', ')} not declared in the entry chain` : 'computed value empty and token is not declared in any root rule',
      declarations: info.decls.map(decl => ({ file: decl.file, line: decl.line, selector: decl.selector, conditions: decl.conditions, raw: decl.raw })),
      dependencies: info.deps.map(dep => ({ name: dep, declaredInChain: declared.has(dep) })),
      missingDependencies: missing,
    });
  }
  const kinds = {};
  const resolutions = {};
  const groupMembers = {};
  const seen = new Set();
  for (const scenario of Object.values(scenarios)) for (const [name, token] of Object.entries(scenario.tokens)) {
    resolutions[token.res] = (resolutions[token.res] ?? 0) + 1;
    if (seen.has(name)) continue;
    seen.add(name);
    kinds[token.kind] = (kinds[token.kind] ?? 0) + 1;
    for (const [group, test] of Object.entries(groups)) if (test(name)) (groupMembers[group] ??= []).push(name);
  }
  const groupCounts = Object.fromEntries(Object.entries(groupMembers).map(([group, members]) => [group, members.length]));
  const badResolved = [];
  const hslComputed = [];
  for (const [id, scenario] of Object.entries(scenarios)) for (const [name, token] of Object.entries(scenario.tokens)) {
    const computed = token.computed;
    if (['resolved', 'triplet'].includes(token.res) && /var\(|calc\(/.test(computed.replace(/calc\([^()]*\)/g, ''))) badResolved.push(`${id}:${name}=${computed}`);
    if (token.res === 'triplet' && /\b(hsl|var|calc)\(/.test(computed)) badResolved.push(`${id}:${name}=triplet-with-function`);
    if (token.kind === 'color' && /^hsl/.test(computed)) hslComputed.push(`${id}:${name}`);
    if (token.kind === 'color' && !token.color) badResolved.push(`${id}:${name}=color-without-channels`);
  }
  const contextLengths = Object.entries(scenarios['light/default'].tokens).filter(([, token]) => token.res === 'contextual');
  assert.deepEqual(badResolved, [], 'unresolved expressions marked resolved');
  assert.ok(unresolvedDetail.every(entry => entry.reason.startsWith('missing dependency') || entry.reason === 'computed value empty and token is not declared in any root rule'));
  for (const [name, token] of Object.entries(scenarios['light/default'].tokens)) {
    if (token.res !== 'scoped' || token.capturedIn.length > 0) continue;
    token.res = 'internal';
    token.reason = 'declared only inside a Tailwind/utility rule and never consumed as a design token at the sampled hosts';
    token.kotlin = kotlinOf(name, { value: token });
  }
  for (const [name, token] of Object.entries(scenarios['dark/default'].tokens)) if (scenarios['light/default'].tokens[name]?.res === 'internal') { token.res = 'internal'; token.reason = scenarios['light/default'].tokens[name].reason; token.kotlin = kotlinOf(name, { value: token }); }
  const scopedUncaptured = scopedDetail.filter(entry => entry.capturedIn.length === 0).map(entry => {
    const declaredAt = entry.declaredAt ?? entry.declaredOnlyIn ?? [];
    const conditions = [...new Set(entry.conditions ?? declaredAt.flatMap(decl => decl.conditions ?? []))];
    return { scenario: entry.scenario, name: entry.name, category: entry.conditional ? 'conditional' : 'internal', conditions, declaredAt, reason: entry.conditional ? 'declared only under a media condition inactive in this viewport' : 'declared only inside a utility rule and not consumed at sampled hosts' };
  });
  assert.ok(scopedDetail.filter(entry => entry.conditional).every(entry => entry.conditions.length > 0));
  for (const entry of scopedUncaptured) assert.ok(entry.conditions.length === 0 || entry.conditions.every(condition => condition.startsWith('@')));
  const internalNames = [...new Set(scopedUncaptured.filter(entry => entry.category === 'internal').map(entry => entry.name))];
  const conditionalNames = [...new Set(scopedUncaptured.filter(entry => entry.category === 'conditional').map(entry => entry.name))];
  assert.deepEqual(internalNames.sort(), ['--tw-scale-x', '--tw-scale-y']);
  assert.deepEqual(unresolvedDetail.map(entry => entry.name).sort(), ['--notes-hairline', '--notes-hairline', '--notes-surface-subtle', '--notes-surface-subtle']);
  assert.ok(contextLengths.length > 0 && contextLengths.every(([, token]) => token.expression));
  assert.equal(Object.keys(palettesRuntime).length, 20);
  const paletteNeutralDrift = Object.values(palettesRuntime).filter(entry => entry.neutralDiffering.length > 0).map(entry => `${entry.mode}/${entry.palette}`);
  assert.deepEqual(paletteNeutralDrift, [], 'palette changed a neutral token');
  const nonDefault = Object.values(palettesRuntime).filter(entry => !['default', 'custom'].includes(entry.palette));
  const tripletMismatch = [];
  for (const entry of nonDefault) {
    const selector = entry.mode === 'dark' ? `:root.dark[data-theme-palette='${entry.palette}']` : `:root[data-theme-palette='${entry.palette}']`;
    const declaredNames = [...declared.entries()].filter(([, records]) => records.some(record => record.selector === selector));
    for (const [name, records] of declaredNames) {
      const record = records.find(candidate => candidate.selector === selector);
      if (!/^-?[\d.]+ [\d.]+% [\d.]+%$/.test(record.raw)) continue;
      const observed = entry.accent[name]?.computed;
      if (observed !== undefined && observed !== record.raw && name !== '--brand-primary') tripletMismatch.push({ palette: entry.palette, mode: entry.mode, name, declared: record.raw, observed });
    }
    if (declaredNames.length !== 6) tripletMismatch.push({ palette: entry.palette, mode: entry.mode, declaredCount: declaredNames.length });
  }
  assert.deepEqual(tripletMismatch, [], 'palette declared triplet vs runtime');
  assert.ok(customRuns.every(run => run.runs.light.matches && run.runs.dark.matches), 'custom algorithm vs browser');
  const customNeutralDrift = customRuns.filter(run => run.runs.light.neutralDiffering.length || run.runs.dark.neutralDiffering.length).map(run => run.input);
  assert.deepEqual(customNeutralDrift, [], 'custom color changed a neutral token');
  assert.ok(Object.keys(variants['notes/small-text'].scopes['.notes-crepe-shell'] ?? {}).length > 0);
  assert.ok(Object.keys(variants['notes/narrow-container'].scopes['.notes-column'] ?? {}).length > 0);
  assert.ok(Object.keys(variants['workbench/material-minimal'].root).length > 0);
  assert.ok(Object.keys(variants['motion/reduced'].root).length > 0);
  assert.ok(Object.keys(variants['transparency/reduced'].root).length > 0);
  assert.ok(Object.keys(variants['contrast/more'].root).length > 0);
  const inheritedTokens = new Set();
  for (const scenario of Object.values(scenarios)) for (const tokens of Object.values(scenario.scopes)) if (tokens) for (const [name, token] of Object.entries(tokens)) if (token.src?.inheritedFrom) inheritedTokens.add(name);
  const domainCoverage = {};
  const domainTokens = ['--surface-root','--surface-elevated','--surface-muted','--surface-overlay','--surface-divider','--surface-panel','--surface-panel-strong','--overlay','--interactive-hover','--sidebar','--sidebar-foreground','--sidebar-border','--sidebar-hover','--sidebar-accent','--shell-backdrop','--shell-surface','--shell-float','--shell-titlebar','--shell-rim','--shell-window-close-foreground','--button-primary-surface','--button-primary-border','--button-primary-foreground','--button-primary-hover','--button-primary-active','--button-prominent-bg','--button-prominent-hover-bg','--button-prominent-active-bg','--button-prominent-border','--button-tonal-bg','--button-tonal-hover-bg','--button-tonal-active-bg','--button-tonal-border','--button-secondary-surface','--button-outline-bg','--button-outline-hover-bg','--button-outline-active-bg','--button-outline-border','--button-plain-hover-bg','--button-plain-active-bg','--button-destructive-bg','--button-destructive-hover-bg','--button-destructive-active-bg','--button-destructive-border','--button-destructive-border','--button-utility-surface','--button-utility-border','--button-utility-foreground','--button-utility-hover','--button-utility-active','--button-danger-surface','--button-danger-border','--button-danger-foreground','--button-danger-hover','--button-danger-active','--input-shell-surface','--input-shell-border','--input-shell-focus','--composer-panel-surface','--composer-panel-border','--composer-panel-foreground','--composer-panel-muted-foreground','--composer-panel-shadow','--composer-panel-backdrop-filter','--composer-panel-muted-surface','--composer-panel-control-surface','--composer-panel-control-border','--composer-panel-control-hover','--composer-panel-focus-border','--composer-panel-placeholder','--dialog-shell-surface','--dialog-shell-border','--mobile-sheet-surface','--mobile-sheet-foreground','--mobile-sheet-muted-foreground','--mobile-sheet-header-border','--mobile-sheet-scrim','--menu-shell-surface','--menu-shell-border','--menu-shell-foreground','--menu-shell-muted-foreground','--menu-shell-shadow','--menu-shell-backdrop-filter','--menu-shell-radius','--text-primary','--text-secondary','--text-muted','--border-default','--border-soft','--border-strong','--primary','--primary-foreground','--primary-color','--ring','--brand-primary','--brand-primary-dark'];
  for (const name of domainTokens) {
    const modes = ['light', 'dark'].map(mode => {
      const token = scenarios[`${mode}/default`].tokens[name];
      return token ? { mode, computed: token.computed, res: token.res, hex: token.color?.hex ?? null, argb: token.kotlin.value } : { mode, res: 'absent' };
    });
    domainCoverage[name] = { group: Object.entries(groups).find(([, test]) => test(name))?.[0] ?? 'other', modes };
  }
  assert.ok(Object.values(domainCoverage).filter(entry => entry.modes.some(mode => mode.res === 'resolved' || mode.res === 'triplet')).length >= 80);
  const shapes = Object.entries(scenarios['light/default'].tokens).filter(([name]) => /^--(radius|wb-radius|notes-radius|button-radius|chat-block-radius)/.test(name));
  const typography = Object.entries(scenarios['light/default'].tokens).filter(([name]) => /^--(font-size|font-weight|line-height|letter-spacing|font-family|font-mono|m-text|notes-body|notes-heading)/.test(name));
  const motion = Object.entries(scenarios['light/default'].tokens).filter(([name, token]) => token.kind === 'duration' || token.kind === 'easing');
  const kotlinTypes = {};
  for (const token of Object.values(scenarios['light/default'].tokens)) kotlinTypes[token.kotlin.type] = (kotlinTypes[token.kotlin.type] ?? 0) + 1;
  for (const token of Object.values(scenarios['dark/default'].tokens)) if (!kotlinTypes[token.kotlin.type]) kotlinTypes[token.kotlin.type] = 1;

  return {
    formatVersion: 1,
    unit,
    evidence: { plan: { file: planFile, unitLine: unitIndex + 1, unitRow: plan[unitIndex].slice(0, 240), sectionLine: sectionIndex + 1, sectionRows }, styleEntry: { file: entryFile, imports: entryLinks(), externalImports, hostEntries }, frozen },
    delivery: { tokens: 'S1.11', typography: 'S1.11', brandAndScreens: 'S0.12', corpus: 'S0.17', themeContract: 'S0.23' },
    engine: { name: engine.name, executable: engine.executable, version: browserVersion, playwright: desktopRequire('playwright/package.json').version, available: engine.available },
    viewport: { width: 1440, height: 900, deviceScaleFactor: 1, rootFontSizePx: 16, locale: 'zh-CN' },
    palettes: { presets, all: allPalettes, modes: ['light', 'dark'], previews, defaultCustomColor },
    customColor: {
      source: { file: themeFile, sha256: sha256(css(themeFile)), functions: algorithm.functions.map(fn => ({ function: fn.name, line: fn.line, body: fn.text.replace(/\s+/g, ' ').slice(0, 400) })) },
      inputs: customRuns,
      constraint: 'only --primary/--primary-foreground/--ring/--brand-primary/--brand-primary-dark/--primary-color change; neutral surfaces, background, border, input and text tokens stay at base',
    },
    importChain: {
      entry: entryFile,
      files: chain,
      loadedSheets,
      requested: [...new Set(requested.map(entry => entry.file))].sort(),
      hashes: Object.fromEntries(chain.map(item => [item.file, item.sha256])),
      externalPackages: externalImports.map(entry => {
        const resolution = desktopRequire.resolve(entry.module);
        const text = readFileSync(resolution, 'utf8');
        const declared = [...new Set([...text.matchAll(/(--[a-z0-9-]+)\s*:/g)].map(match => match[1]))].sort();
        return { module: entry.module, importedAt: `${entryFile}:${entry.line}`, file: resolution.slice(desktop.length + 1), sha256: sha256(text), customProperties: declared.length, overriddenByProject: declared.filter(name => chain.some(item => declared.length && css(item.file).includes(`${name}:`)) && /^--os-/.test(name)), note: declared.length ? 'package stylesheet supplies --os-* defaults; the project .os-theme-deep-student layer overrides the consumed subset' : 'no custom properties' };
      }),
    },
    buttons: {
      contract: button,
      coverage: buttonCoverage,
      runtime: buttonRuntime,
      unavailableTokens: unavailable,
    },
    themeVariants,
    sourceHashes,
    scenarios,
    scenarioSizes: Object.fromEntries(Object.entries(scenarios).map(([id, scenario]) => [id, { tokens: Object.keys(scenario.tokens).length, scopes: Object.fromEntries(Object.entries(scenario.scopes).map(([scope, tokens]) => [scope, tokens ? Object.keys(tokens).length : 0])) }])),
    palettesRuntime,
    variants,
    catalog: { groups: groupCounts, members: groupMembers, accent: accentNames, differingAcrossPalettes: [...differingNames].sort(), domainCoverage },
    checks: {
      planUnits: plan.filter(line => /^\| S0\.\d\d \|/.test(line)).length,
      chainFiles: chain.length,
      loadedSheets: loadedSheets.length,
      tokensPerMode: Object.keys(scenarios['light/default'].tokens).length,
      uniqueTokens: seen.size,
      kinds,
      resolutions,
      scoped: { count: scopedDetail.length, conditional: conditionalNames, internal: internalNames, uncovered: scopedUncaptured, uncoveredCount: scopedUncaptured.length, detail: scopedDetail, byScenario: Object.fromEntries(Object.entries(scenarios).map(([id]) => [id, scopedDetail.filter(entry => entry.scenario === id).map(entry => entry.name)])) },
      unresolved: { count: unresolvedDetail.length, detail: unresolvedDetail, byScenario: Object.fromEntries(Object.entries(scenarios).map(([id]) => [id, unresolvedDetail.filter(entry => entry.scenario === id).map(entry => entry.name)])) },
      hslComputedCount: hslComputed.length,
      contextualLengths: contextLengths.map(([name, token]) => ({ name, expression: token.expression, pxAtContext: token.pxAtContext ?? null })),
      paletteScenarios: Object.keys(palettesRuntime).length,
      paletteAccentOnly: paletteNeutralDrift.length === 0,
      paletteNeutralDiffering: paletteNeutralDrift.length,
      declaredPaletteAccent,
      customRuns: customRuns.length * 2,
      customMatches: customRuns.every(run => run.runs.light.matches && run.runs.dark.matches),
      customNeutralUnchanged: customRuns.every(run => run.runs.light.neutralDiffering.length === 0 && run.runs.dark.neutralDiffering.length === 0),
      customDerivedCounts: customRuns.map(run => ({ input: run.input, light: run.runs.light.derivedDiffering.length, dark: run.runs.dark.derivedDiffering.length })),
      variants: Object.keys(variants).length,
      variantDiffs: Object.fromEntries(Object.entries(variants).map(([id, entry]) => [id, Object.keys(entry.root).length + Object.values(entry.scopes).reduce((total, scope) => total + Object.keys(scope).length, 0)])),
      provenance: { ...totals, inheritedTokens: inheritedTokens.size },
      tokenCompleteness: {
        baseTokens: baseTokens.size,
        lightTokens: Object.keys(scenarios['light/default'].tokens).length,
        darkTokens: Object.keys(scenarios['dark/default'].tokens).length,
        paletteScenarios: scenarioTokenSets,
        variantTokens: variantTokenNames.size,
        phantomVariantTokens,
      },
      buttons: {
        variants: Object.keys(buttonCoverage).length,
        primarySolid: buttonRuntime.light.prominentIsSolidPrimary && buttonRuntime.dark.prominentIsSolidPrimary,
        dangerSolid: button.variants.danger === button.variants.destructive && button.tones.danger.tokens.length > 0,
        ghostVariants: ghostVariants.length,
        onePrimaryPerView: button.onePrimaryPerView.rule,
      },
      themeVariants: {
        slot: themeVariants.slot,
        localContributionOnly: themeVariants.boundary.localAuthorizedOnly,
        staticPresetExcluded: themeVariants.boundary.notStaticPresetTokens,
        syncTrustExcluded: themeVariants.boundary.notSyncTrust,
      },
      domainCoverageCount: Object.keys(domainCoverage).length,
      shapes: shapes.length,
      typography: typography.length,
      motion: motion.length,
      domainGroups: groupCounts,
      kotlinTypes,
      domainScopes: Object.keys(groups).length,
    },
  };
  } finally {
    await browser.close().catch(() => {});
  }
}

export async function run(mode) {
  const started = performance.now();
  const data = await collect();
  artifact(file, data, mode);
  log('design.complete', {
    unit, mode, elapsedMs: Math.round(performance.now() - started),
    engine: `${data.engine.name} ${data.engine.version}`, chain: data.checks.chainFiles, sheets: data.checks.loadedSheets,
    tokens: data.checks.tokensPerMode, unique: data.checks.uniqueTokens, palettes: data.checks.paletteScenarios,
    customMatches: data.checks.customMatches, variants: data.checks.variants, unresolved: data.checks.unresolved.count, scoped: data.checks.scoped.count,
    buttons: data.checks.buttons, themeVariants: data.checks.themeVariants,
    kinds: data.checks.kinds, resolutions: data.checks.resolutions, provenance: data.checks.provenance,
  });
}

main(import.meta.url, run);
