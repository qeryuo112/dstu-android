import assert from 'node:assert/strict';
import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { root, workspace, desktop, operit, readJson, sha256, log, main, difference, saveJson } from './lib.mjs';

const INPUTS = 'config/execution-inputs.json';
const OUTPUT = 'config/execution-plan.json';
const PLAN_FILE = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const PLACEHOLDER = /^(tbd|placeholder|unknown|xxx|n\/a)$/iu;
const UNIT_RE = /^S\d\.\d{2}$/u;
const EXIT_RE = /^S\d\.G$/u;

const clone = value => JSON.parse(JSON.stringify(value));
const roundHalf = value => Math.round(value * 2) / 2;
const isoNow = () => new Date().toISOString();
const readDesktopJson = file => JSON.parse(readFileSync(resolve(desktop, file), 'utf8'));
const readOperit = file => readFileSync(resolve(operit, file), 'utf8');
const fileSha = file => sha256(readFileSync(resolve(root, file)));
const hasFile = file => existsSync(resolve(root, file));

function resolveEvidence(evidence) {
  if (!evidence || typeof evidence.file !== 'string') return null;
  if (evidence.source === 'desktop') return resolve(desktop, evidence.file);
  if (evidence.source === 'operit') return resolve(operit, evidence.file);
  if (evidence.source === 'workspace') return resolve(workspace, evidence.file);
  return resolve(root, evidence.file);
}

function evidenceExists(evidence) {
  const file = resolveEvidence(evidence);
  return file !== null && existsSync(file);
}

function evidenceLine(evidence) {
  const file = resolveEvidence(evidence);
  if (!file || !existsSync(file)) return null;
  return readFileSync(file, 'utf8').split(/\r?\n/u).findIndex(line =>
    evidence.pointer ? line.includes(String(evidence.pointer).split('.').at(-1).replace(/^[^=]+=/, '').replace(/\]$/u, '')) : false) + 1;
}

function readPath(file, label = file) {
  const full = resolve(root, file);
  assert.ok(existsSync(full), `${label}: missing file`);
  return readFileSync(full, 'utf8');
}

function parsePlan() {
  const text = readPath(PLAN_FILE);
  const lines = text.split(/\r?\n/u);
  const units = [];
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (!/^\| S\d\.\d{2} \|/u.test(line)) continue;
    const cells = line.split('|').map(cell => cell.trim());
    if (cells.length < 7) continue;
    const [, id, name, prerequisites, deliverable, acceptance] = cells;
    if (!UNIT_RE.test(id)) continue;
    units.push({ id, stage: id.slice(0, 2), name, prerequisites, deliverable, acceptance,
      planLine: index + 1, planPointer: `${PLAN_FILE}:${index + 1}` });
  }
  return { text, units };
}

function parseDependencyToken(token, unitIds, exitIds) {
  const clean = token.replace(/`/gu, '').replace(/\s+/gu, '').replace(/。$/u, '');
  if (!clean || clean === '—' || clean === '-') return [];
  const range = clean.match(/^(S\d\.\d{2})[–-](S\d\.\d{2})$/u);
  if (range) {
    const stage = range[1].split('.')[0];
    assert.equal(range[2].split('.')[0], stage, `cross-stage range ${clean}`);
    const from = Number(range[1].split('.')[1]);
    const to = Number(range[2].split('.')[1]);
    assert.ok(from <= to, `${clean}: reversed range`);
    const result = [];
    for (let value = from; value <= to; value++) {
      const id = `${stage}.${String(value).padStart(2, '0')}`;
      assert.ok(unitIds.has(id), `${clean}: unknown unit ${id}`);
      result.push(id);
    }
    return result;
  }
  if (UNIT_RE.test(clean)) {
    assert.ok(unitIds.has(clean), `unknown dependency unit ${clean}`);
    return [clean];
  }
  if (EXIT_RE.test(clean)) {
    assert.ok(exitIds.has(clean), `unknown stage exit ${clean}`);
    return [clean];
  }
  throw new Error(`unsupported dependency token: ${token}`);
}

function addWorkingDays(startDate, days) {
  assert.ok(ISO_DATE.test(startDate));
  const date = new Date(`${startDate}T00:00:00Z`);
  let left = days;
  while (left > 0) {
    date.setUTCDate(date.getUTCDate() + 1);
    const day = date.getUTCDay();
    if (day !== 0 && day !== 6) left--;
  }
  return date.toISOString().slice(0, 10);
}

function completionDate(startDate, cumulativeDays) {
  const days = Math.max(1, Math.ceil(cumulativeDays));
  return addWorkingDays(startDate, days - 1);
}

function isWeekday(dateText) {
  const day = new Date(`${dateText}T00:00:00Z`).getUTCDay();
  return day !== 0 && day !== 6;
}

function uniqueBy(items, key) {
  const map = new Map();
  for (const item of items) {
    const id = key(item);
    if (!map.has(id)) map.set(id, { ...item, evidenceList: [] });
    if (item.evidence) map.get(id).evidenceList.push(item.evidence);
  }
  return [...map.values()];
}

function memberIdOf(value) {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object') return value.id ?? value.name ?? value.palette ?? JSON.stringify(value);
  return String(value);
}

function ensureResolvedMember(member, label) {
  assert.ok(member && typeof member.id === 'string' && member.id.trim() !== '', `${label}: missing member id`);
  assert.ok(!PLACEHOLDER.test(member.id), `${label}: placeholder member ${member.id}`);
  assert.ok(member.evidence && evidenceExists(member.evidence), `${label}/${member.id}: evidence missing`);
}

function loadSources() {
  const inputs = readJson(INPUTS);
  const plan = parsePlan();
  const navigation = readJson('config/baseline/navigation.json');
  const workbench = readJson('config/baseline/workbench.json');
  const entries = readJson('config/baseline/entries.json');
  const domains = readJson('config/baseline/domains.json');
  const contributions = readJson('config/baseline/contributions.json');
  const content = readJson('config/baseline/content.json');
  const classification = readJson('config/baseline/classification.json');
  const protocol = readJson('config/baseline/protocol.json');
  const design = readJson('config/design/tokens.baseline.json');
  const brand = readJson('config/design/brand.baseline.json');
  const adoption = readJson('config/operit-adoption.json');
  const assets = readJson('config/runtime-assets.json');
  const sourceLock = readJson('config/source-lock.json');
  const portable = readJson('config/contracts/portable-config.json');
  const providerRegistry = readDesktopJson('scripts/provider-protocol-registry.json');

  const setupFile = 'terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt';
  const setupText = readOperit(setupFile);
  const packageItems = new Map();
  setupText.split(/\r?\n/u).forEach((line, index) => {
    for (const match of line.matchAll(/PackageItem\("([^"]+)"/gu)) {
      packageItems.set(match[1], { file: setupFile, line: index + 1 });
    }
  });

  const toolRegistration = 'app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt';
  const autoUi = 'app/src/main/assets/packages/automatic_ui_base.js';
  const toolSchema = new Map();
  for (const [file, regex] of [[toolRegistration, /name = "([^"]+)"/gu], [autoUi, /"name":\s*"([^"]+)"/gu]]) {
    const text = readOperit(file);
    text.split(/\r?\n/u).forEach((line, index) => {
      for (const match of line.matchAll(regex)) {
        if (!toolSchema.has(match[1])) toolSchema.set(match[1], { file, line: index + 1 });
      }
    });
  }

  const officialUi = readdirSync(resolve(desktop, 'src/ui-plugins/official'), { withFileTypes: true })
    .filter(entry => entry.isDirectory() && !entry.name.startsWith('_'))
    .map(entry => {
      const file = `src/ui-plugins/official/${entry.name}/index.ts`;
      const text = readDesktopJson ? readFileSync(resolve(desktop, file), 'utf8') : '';
      const match = text.match(/meta:\s*\{[\s\S]{0,240}?id:\s*'([^']+)'/u);
      return { id: match ? match[1] : `dstu.ui.${entry.name}`, dir: entry.name, evidence: { source: 'desktop', file } };
    })
    .sort((a, b) => a.dir.localeCompare(b.dir));

  const examples = readdirSync(resolve(desktop, 'examples/plugins'), { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => {
      const file = `examples/plugins/${entry.name}/package.json`;
      const pkg = JSON.parse(readFileSync(resolve(desktop, file), 'utf8'));
      return { id: pkg.name, dir: entry.name, evidence: { source: 'desktop', file } };
    })
    .sort((a, b) => a.dir.localeCompare(b.dir));

  return { inputs, plan, navigation, workbench, entries, domains, contributions, content,
    classification, protocol, design, brand, adoption, assets, sourceLock, portable,
    providerRegistry, packageItems, toolSchema, officialUi, examples };
}

function resolveMembers(source, ids, sources) {
  if (source === 'explicit') return ids;
  if (source === 'providers') {
    return ids.map(id => {
      const record = sources.providerRegistry.providers.find(item => item.provider_type === id);
      assert.ok(record, `provider ${id}: not in desktop registry`);
      return { id, kind: 'provider', protocols: record.allowed_protocols,
        defaultProtocol: record.default_protocol, official: record.official === true,
        evidence: { source: 'desktop', file: 'scripts/provider-protocol-registry.json',
          pointer: `providers[provider_type=${id}]` } };
    });
  }
  if (source === 'settings-unique') {
    const groups = new Map();
    for (const setting of sources.contributions.settings) {
      if (!groups.has(setting.id)) groups.set(setting.id, []);
      groups.get(setting.id).push(setting);
    }
    const scopes = new Map();
    for (const row of sources.portable.classification.rows) {
      if (!scopes.has(row.sourceId)) scopes.set(row.sourceId, row.scope);
    }
    return [...groups.entries()].map(([id, uses]) => {
      const first = uses[0];
      return { id, kind: 'setting', scope: scopes.get(id) ?? first.scope,
        usagePoints: uses.length, dynamicKey: id.startsWith('dynamic:'),
        key: typeof first.key === 'string' ? first.key : null,
        keyExpression: typeof first.key === 'object' ? first.key.expression : null,
        evidence: { source: 'root', file: 'config/baseline/contributions.json',
          pointer: `settings[id=${id}]` },
        secondaryEvidence: { source: 'root', file: 'config/contracts/portable-config.json',
          pointer: `classification.rows[sourceId=${id}]` } };
    });
  }
  if (source === 'workbench-apps') {
    return ids.map(id => {
      const app = sources.workbench.apps.find(item => item.id === id);
      assert.ok(app, `workbench app ${id}: not in S0.04 registry`);
      return { id, kind: 'app', module: app.android?.module, resourceTypes: app.resourceTypes,
        deliveryUnit: app.android?.deliveryUnit,
        evidence: { source: 'root', file: 'config/baseline/workbench.json',
          pointer: `apps[id=${id}]` } };
    });
  }
  if (source === 'dynamic-plugin-view') {
    const family = sources.workbench.dynamicFamily;
    assert.ok(family?.prefix, 'dynamic plugin-view family missing');
    return [{ id: `${family.prefix}*`, kind: 'dynamic-view-family',
      owner: family.owner?.source ?? 'plugin views slot',
      evidence: { source: 'root', file: 'config/baseline/workbench.json',
        pointer: 'dynamicFamily' } }];
  }
  if (source === 'plugins') {
    return sources.domains.plugins.map(plugin => ({
      id: plugin.id, kind: 'plugin', module: plugin.module, deliveryUnit: plugin.deliveryUnit,
      services: plugin.services.length, tools: plugin.tools.length,
      backgroundTasks: plugin.backgroundTasks.length,
      evidence: { source: 'root', file: 'config/baseline/domains.json',
        pointer: `plugins[id=${plugin.id}]` },
    }));
  }
  if (source === 'official-ui') {
    return sources.officialUi.map(item => ({
      id: item.id, kind: 'official-ui', module: `src/ui-plugins/official/${item.dir}`,
      evidence: item.evidence,
    }));
  }
  if (source === 'examples') {
    return sources.examples.map(item => ({
      id: item.id, kind: 'example', module: `examples/plugins/${item.dir}`,
      evidence: item.evidence,
    }));
  }
  if (source === 'chat-blocks') {
    const blocks = sources.content.registrations
      .filter(item => item.kind === 'blockRegistry.register')
      .map((item, index) => {
        if (typeof item.id !== 'string') return null;
        return { id: item.id, kind: 'chat-block', blockType: item.id, orderHint: index,
          evidence: { source: 'root', file: 'config/baseline/content.json',
            pointer: `registrations[kind=blockRegistry.register,id=${item.id}]` } };
      })
      .filter(Boolean);
    const generative = sources.content.generativeTypes.map(item => ({
      id: item.type, kind: 'generative-block', blockType: 'generative-ui',
      allowPartialRender: item.allowPartialRender, deliveryUnit: item.deliveryUnit,
      evidence: { source: 'root', file: 'config/baseline/content.json',
        pointer: `generativeTypes[type=${item.type}]` },
    }));
    return [...blocks, ...generative];
  }
  if (source === 'content-languages') {
    const languages = new Map(sources.content.contentLanguages.map(item => [item.language, item]));
    const types = new Map(sources.content.generativeTypes.map(item => [item.type, item]));
    return ids.map(id => {
      if (id === 'molecule-2d' || id === 'molecule-3d') {
        const members = id === 'molecule-2d' ? ['mol', 'molfile', 'sdf'] : ['pdb'];
        for (const language of members) assert.ok(languages.has(language), `${id}: ${language} missing`);
        return { id, kind: 'content-engine', languages: members,
          evidence: { source: 'root', file: 'config/baseline/content.json',
            pointer: `contentLanguages[language=${members[0]}]` } };
      }
      if (id === 'mindmap') {
        assert.ok(types.has('mindmap-embed'), 'mindmap: generative embedding missing');
        return { id, kind: 'content-engine', generativeType: 'mindmap-embed',
          evidence: { source: 'root', file: 'config/baseline/content.json',
            pointer: 'generativeTypes[type=mindmap-embed]' } };
      }
      assert.ok(languages.has(id), `content language ${id}: missing`);
      return { id, kind: 'content-engine', languages: [id],
        evidence: { source: 'root', file: 'config/baseline/content.json',
          pointer: `contentLanguages[language=${id}]` } };
    });
  }
  if (source === 'operit-package-items') {
    return ids.map(id => {
      const item = sources.packageItems.get(id);
      assert.ok(item, `Operit package item ${id}: not found in SetupScreen`);
      return { id, kind: 'recipe', packageId: id,
        evidence: { source: 'operit', file: item.file, pointer: `PackageItem(${id})` } };
    });
  }
  if (source === 'operit-tool-schema') {
    return ids.map(id => {
      const item = sources.toolSchema.get(id);
      assert.ok(item, `Operit tool schema ${id}: not found`);
      return { id, kind: 'operation', toolName: id,
        evidence: { source: 'operit', file: item.file, pointer: `name=${id}` } };
    });
  }
  if (source === 'operit-workflow') {
    const item = sources.adoption.adopted.find(entry => entry.id === 'workflow');
    assert.ok(item, 'Operit workflow adoption item missing');
    return [{ id: 'operit-workflow', kind: 'workflow', adoptionId: item.id,
      deliveryUnit: item.deliveryUnit,
      evidence: { source: 'root', file: 'config/operit-adoption.json',
        pointer: 'adopted[id=workflow]' } }];
  }
  throw new Error(`unknown member source: ${source}`);
}

function configureParameterized(sources) {
  const result = new Map();
  for (const entry of sources.inputs.parameterized) {
    assert.ok(UNIT_RE.test(entry.unit), `parameterized unit ${entry.unit}`);
    assert.ok(!result.has(entry.unit), `duplicate parameterized unit ${entry.unit}`);
    const dimensions = [];
    for (const dimension of entry.dimensions) {
      const prefix = dimension.idPrefix ?? '';
      const members = (dimension.memberSource === 'explicit'
        ? dimension.members ?? []
        : resolveMembers(dimension.memberSource, dimension.ids ?? [], sources))
        .map((member, index) => ({ ...member, id: `${prefix}${member.id}`, baseId: member.id,
          order: index + 1, kind: dimension.kind }));
      const ids = new Set();
      for (const member of members) {
        assert.ok(!ids.has(member.id), `${entry.unit}/${dimension.kind}: duplicate member ${member.id}`);
        ids.add(member.id);
        ensureResolvedMember(member, `${entry.unit}/${dimension.kind}`);
        assert.ok(!PLACEHOLDER.test(member.id), `${entry.unit}: placeholder member ${member.id}`);
      }
      const operations = dimension.operations ?? [];
      const operationSets = dimension.operationSets ?? null;
      if (!operationSets) assert.ok(operations.length > 0, `${entry.unit}: no operations`);
      for (const operation of operations) assert.ok(operation in sources.inputs.workdays.operationDays,
        `${entry.unit}: unknown operation ${operation}`);
      if (operationSets) {
        for (const [id, set] of Object.entries(operationSets)) {
          assert.ok(members.some(member => member.id === id), `${entry.unit}: operation set for missing member ${id}`);
          for (const operation of set) assert.ok(operation in sources.inputs.workdays.operationDays,
            `${entry.unit}/${id}: unknown operation ${operation}`);
        }
      }
      dimensions.push({ kind: dimension.kind, operations, operationSets,
        members, unresolved: dimension.unresolved ? [dimension.unresolved] : [] });
    }
    result.set(entry.unit, {
      dimensions,
      setupDays: sources.inputs.workdays.setupOverrides[entry.unit] ?? sources.inputs.workdays.parameterizedSetupDays,
    });
  }
  return result;
}

function stageTestType(stage) {
  return ({
    S0: 'baseline-artifact-verify', S1: 'vertical-slice-integration', S2: 'data-task-instrumentation',
    S3: 'protocol-chat-integration', S4: 'engine-golden-and-budget', S5: 'compose-visual-and-a11y',
    S6: 'terminal-system-and-security', S7: 'cross-device-business-ui', S8: 'sync-backup-hardening',
    S9: 'release-candidate-signoff',
  })[stage] ?? 'unit-integration';
}

function operationTestType(operation) {
  return ({
    request: 'provider-request-golden', stream: 'provider-stream-golden', 'error-cancel': 'provider-error-cancel',
    'source-lock': 'source-lock-and-license', 'build-origin': 'reproducible-build-hash',
    adapter: 'adapter-conformance', conformance: 'golden-conformance', budget: 'device-budget',
    states: 'state-screenshot-golden', 'input-a11y': 'ime-touch-talkback',
    render: 'render-golden', edit: 'edit-undo', roundtrip: 'serialize-roundtrip',
    host: 'content-host', interaction: 'content-interaction', 'golden-budget': 'visual-source-budget',
    'action-stream': 'typed-action-stream', 'open-close': 'panel-open-close', 'draft-snapshot': 'draft-snapshot-restore',
    'cross-device': 'cross-device-readback', import: 'import-report', export: 'export-artifact',
    'persist-effect': 'restart-effect', 'scope-sync': 'scope-sync-channel',
    'install-probe': 'install-and-probe', 'failure-resume': 'failure-resume', 'remove-reset': 'remove-and-preserve-user-data',
    execute: 'real-effect-and-audit', 'permission-cancel': 'permission-revoke-cancel',
    'api-map': 'api-mapping', 'port-install': 'port-and-install', 'data-ui-lifecycle': 'data-ui-update-revoke',
    'graph-model': 'workflow-graph-golden', 'node-execution': 'node-execution', 'schedule-agent': 'schedule-agent',
    'ui-roundtrip': 'compose-workflow-roundtrip',
  })[operation] ?? 'operation-conformance';
}

function nonParameterizedDays(unit, inputs) {
  let days = inputs.workdays.stageDefaults[unit.stage] ?? 2;
  const text = `${unit.name} ${unit.deliverable} ${unit.acceptance}`;
  if (inputs.workdays.veryComplexKeywords.some(keyword => text.includes(keyword))) days += 1;
  else if (inputs.workdays.complexKeywords.some(keyword => text.includes(keyword))) days += 0.5;
  return roundHalf(Math.min(3, Math.max(1, days)));
}

function buildPlan(sources) {
  const { inputs } = sources;
  assert.equal(sources.plan.units.length, inputs.expected.mainUnits, 'plan main unit count');
  const unitIds = new Set(sources.plan.units.map(unit => unit.id));
  const exitIds = new Set(inputs.stageExits.map(exit => exit.id));
  assert.equal(exitIds.size, inputs.expected.stageExits, 'stage exit count');
  for (const exit of inputs.stageExits) {
    assert.ok(ISO_DATE.test(inputs.schedule.startDate));
    assert.ok(exit.days > 0 && Number.isInteger(exit.days * 2), `${exit.id}: invalid days`);
  }

  const parameterized = configureParameterized(sources);
  const signoffByUnit = new Map(inputs.signoff.rows.map(row => [row.unit, row]));
  const stageLast = new Map();
  sources.plan.units.forEach((unit, index) => {
    unit.serialIndex = index + 1;
    unit.order = index + 1;
    stageLast.set(unit.stage, unit.serialIndex);
  });
  const exitOrder = new Map();
  for (const exit of inputs.stageExits) {
    const last = stageLast.get(exit.stage);
    assert.ok(last, `${exit.id}: stage has no units`);
    exitOrder.set(exit.id, last + 0.5);
  }

  const units = [];
  const cards = [];
  for (const sourceUnit of sources.plan.units) {
    const declared = sourceUnit.prerequisites.trim() === '—'
      ? []
      : sourceUnit.prerequisites.split(/[、,，]/u).flatMap(token => parseDependencyToken(token, unitIds, exitIds));
    const inherited = sources.plan.units
      .slice(0, sourceUnit.serialIndex - 1)
      .filter(unit => unit.stage === sourceUnit.stage)
      .map(unit => unit.id);
    const stageIndex = inputs.expected.stages.indexOf(sourceUnit.stage);
    if (stageIndex > 0) inherited.push(`${inputs.expected.stages[stageIndex - 1]}.G`);
    const dependencies = [...new Set([...declared, ...inherited])];

    const param = parameterized.get(sourceUnit.id) ?? null;
    const unitCards = [];
    const status = signoffByUnit.get(sourceUnit.id)?.status
      ?? (sourceUnit.id === 'S0.25' ? 'in-progress' : 'pending');
    const baseEvidence = { source: 'plan', file: PLAN_FILE, pointer: `line ${sourceUnit.planLine}` };

    if (!param) {
      const days = nonParameterizedDays(sourceUnit, inputs);
      const card = {
        id: `${sourceUnit.id}/implementation`, unit: sourceUnit.id, memberId: null, memberKind: null,
        operation: 'implementation', role: 'implementation',
        estimatedWorkDays: days, actualWorkDays: null,
        executor: inputs.executor, status, testType: stageTestType(sourceUnit.stage),
        evidence: [baseEvidence],
      };
      cards.push(card);
      unitCards.push(card.id);
    } else {
      const setup = {
        id: `${sourceUnit.id}/common-setup`, unit: sourceUnit.id, memberId: null, memberKind: null,
        operation: 'common-setup', role: 'setup',
        estimatedWorkDays: param.setupDays, actualWorkDays: null,
        executor: inputs.executor, status, testType: stageTestType(sourceUnit.stage),
        evidence: [baseEvidence],
      };
      cards.push(setup);
      unitCards.push(setup.id);
      for (const dimension of param.dimensions) {
        for (const member of dimension.members) {
          const operations = dimension.operationSets?.[member.id] ?? dimension.operations;
          for (const operation of operations) {
            const card = {
              id: `${sourceUnit.id}/${member.id}/${operation}`, unit: sourceUnit.id,
              memberId: member.id, memberKind: dimension.kind, operation, role: 'subcard',
              estimatedWorkDays: inputs.workdays.operationDays[operation], actualWorkDays: null,
              executor: inputs.executor, status, testType: operationTestType(operation),
              evidence: [baseEvidence, member.evidence, ...(member.secondaryEvidence ? [member.secondaryEvidence] : [])],
            };
            cards.push(card);
            unitCards.push(card.id);
          }
        }
      }
    }

    const cardDays = unitCards.reduce((total, id) => total + cards.find(card => card.id === id).estimatedWorkDays, 0);
    const dimensions = param ? param.dimensions.map(dimension => ({
      kind: dimension.kind,
      operations: dimension.operations,
      operationSets: dimension.operationSets,
      members: dimension.members,
      unresolved: dimension.unresolved,
    })) : [];
    const unit = {
      id: sourceUnit.id, serialIndex: sourceUnit.serialIndex, order: sourceUnit.serialIndex, stage: sourceUnit.stage,
      name: sourceUnit.name, deliverable: sourceUnit.deliverable, acceptance: sourceUnit.acceptance,
      planPointer: sourceUnit.planPointer,
      parameterized: Boolean(param),
      commonSetupDays: param ? param.setupDays : 0,
      estimatedWorkDays: roundHalf(cardDays), actualWorkDays: null,
      status, declaredDependencies: declared, inheritedDependencies: inherited,
      dependencies, dimensions, cardIds: unitCards,
      executor: inputs.executor,
    };
    units.push(unit);
  }

  const unitById = new Map(units.map(unit => [unit.id, unit]));
  const orderById = new Map([...units.map(unit => [unit.id, unit.order]),
    ...inputs.stageExits.map(exit => [exit.id, exitOrder.get(exit.id)])]);
  for (const unit of units) {
    for (const dependency of unit.dependencies) {
      assert.ok(orderById.has(dependency), `${unit.id}: unknown dependency ${dependency}`);
      assert.ok(orderById.get(dependency) < unit.order, `${unit.id}: forward dependency ${dependency}`);
    }
  }

  const stageExits = inputs.stageExits.map(exit => {
    const stageUnits = units.filter(unit => unit.stage === exit.stage);
    const dependencies = stageUnits.map(unit => unit.id);
    const stageIndex = inputs.expected.stages.indexOf(exit.stage);
    if (stageIndex > 0) dependencies.push(`${inputs.expected.stages[stageIndex - 1]}.G`);
    return {
      id: exit.id, stage: exit.stage, milestone: exit.milestone, order: exitOrder.get(exit.id),
      estimatedWorkDays: exit.days, actualWorkDays: null, status: exit.id === 'S0.G' ? 'pending-signoff' : 'pending',
      dependencies: [...new Set(dependencies)],
    };
  });
  for (const exit of stageExits) {
    for (const dependency of exit.dependencies) {
      assert.ok(orderById.has(dependency), `${exit.id}: unknown dependency ${dependency}`);
      assert.ok(orderById.get(dependency) < exit.order, `${exit.id}: forward dependency ${dependency}`);
    }
  }

  const queue = [...units.map(unit => ({ kind: 'unit', id: unit.id, stage: unit.stage, order: unit.order, days: unit.estimatedWorkDays })),
    ...stageExits.map(exit => ({ kind: 'exit', id: exit.id, stage: exit.stage, order: exit.order, days: exit.estimatedWorkDays }))]
    .sort((a, b) => a.order - b.order);
  const historicalStage = inputs.schedule.historicalStage ?? 'S0';
  let historicalCumulative = 0;
  let remainingCumulative = 0;
  for (const item of queue) {
    if (item.stage === historicalStage) {
      historicalCumulative = roundHalf(historicalCumulative + item.days);
      item.phase = 'completed';
      item.historicalModelCumulativeDays = historicalCumulative;
      item.projectedCompletionDate = item.id === `${historicalStage}.G` ? inputs.schedule.actualCompletedDate : null;
      if (item.kind === 'unit') {
        const unit = unitById.get(item.id);
        unit.schedulePhase = 'completed';
        unit.projectedCompletionDate = null;
        unit.actualCompletedDate = null;
        unit.actualWorkDays = null;
      } else {
        const exit = stageExits.find(entry => entry.id === item.id);
        exit.schedulePhase = 'completed';
        exit.projectedCompletionDate = item.id === `${historicalStage}.G` ? inputs.schedule.actualCompletedDate : null;
        exit.actualCompletedDate = item.id === `${historicalStage}.G` ? inputs.schedule.actualCompletedDate : null;
        exit.actualWorkDays = null;
        if (item.id === `${historicalStage}.G`) {
          exit.dateBasis = 'planned-s0-exit-asOf';
          exit.signoffStatus = 'pending-main-signoff';
        }
        exit.cumulativeDays = historicalCumulative;
      }
    } else {
      remainingCumulative = roundHalf(remainingCumulative + item.days);
      item.phase = 'projected';
      item.remainingCumulativeDays = remainingCumulative;
      item.projectedCompletionDate = completionDate(inputs.schedule.futureStartDate, remainingCumulative);
      if (item.kind === 'unit') {
        const unit = unitById.get(item.id);
        unit.schedulePhase = 'projected';
        unit.projectedCompletionDate = item.projectedCompletionDate;
        unit.actualCompletedDate = null;
        unit.actualWorkDays = null;
      } else {
        const exit = stageExits.find(entry => entry.id === item.id);
        exit.schedulePhase = 'projected';
        exit.projectedCompletionDate = item.projectedCompletionDate;
        exit.actualCompletedDate = null;
        exit.actualWorkDays = null;
        exit.cumulativeDays = remainingCumulative;
      }
    }
  }
  for (const unit of units.filter(unit => unit.schedulePhase === 'projected')) assert.ok(unit.projectedCompletionDate, `${unit.id}: no schedule`);
  const historicalUnits = units.filter(unit => unit.stage === historicalStage);
  const historicalExit = stageExits.find(exit => exit.id === `${historicalStage}.G`);
  const historicalEstimatedWorkDays = roundHalf(historicalUnits.reduce((sum, unit) => sum + unit.estimatedWorkDays, 0) +
    (historicalExit?.estimatedWorkDays ?? 0));
  const totalEstimatedWorkDays = roundHalf(units.reduce((sum, unit) => sum + unit.estimatedWorkDays, 0) +
    stageExits.reduce((sum, exit) => sum + exit.estimatedWorkDays, 0));
  const futureEstimatedWorkDays = roundHalf(totalEstimatedWorkDays - historicalEstimatedWorkDays);
  for (const item of queue) {
    item.cumulativeEstimatedDays = item.phase === 'completed'
      ? item.historicalModelCumulativeDays
      : roundHalf(historicalEstimatedWorkDays + item.remainingCumulativeDays);
    if (item.id === `${historicalStage}.G`) {
      item.dateBasis = 'planned-s0-exit-asOf';
      item.signoffStatus = 'pending-main-signoff';
    }
  }
  const milestones = stageExits.filter(exit => exit.milestone).map(exit => ({
    id: exit.milestone, exit: exit.id,
    projectedCompletionDate: exit.projectedCompletionDate,
    basis: exit.id === `${historicalStage}.G` ? 'planned-s0-exit-asOf' : 'projected-remaining-estimate',
    actualCompletedDate: exit.id === `${historicalStage}.G` ? inputs.schedule.actualCompletedDate : null,
    actualWorkDays: null,
    dateBasis: exit.id === `${historicalStage}.G` ? 'planned-s0-exit-asOf' : 'projected-remaining-estimate',
    signoffStatus: exit.id === `${historicalStage}.G` ? 'pending-main-signoff' : null,
    cumulativeDays: exit.cumulativeDays, estimateOnly: exit.id !== `${historicalStage}.G`,
  }));
  const scheduleSummary = {
    ...inputs.schedule,
    totalEstimatedWorkDays,
    historicalEstimatedWorkDays,
    remainingEstimatedWorkDays: futureEstimatedWorkDays,
    actualCompleted: {
      [`${historicalStage}.G`]: {
        actualCompletedDate: inputs.schedule.actualCompletedDate,
        actualWorkDays: null,
        dateBasis: 'planned-s0-exit-asOf',
        signoffStatus: 'pending-main-signoff',
        finalDateSame: true,
      },
    },
    historicalCutoff: {
      asOfDate: inputs.schedule.actualCompletedDate,
      finalDateSame: true,
      signoffStatus: 'pending-main-signoff',
      actualWorkDays: null,
    },
    projectedFutureStartDate: inputs.schedule.futureStartDate,
    recalibrateAt: inputs.schedule.recalibrateAt,
    estimateSeparation: 'total includes the historical S0 model estimate; remaining excludes S0 and drives future dates',
  };

  const ctx = {
    inputs, sources, units, stageExits, unitById, cards, parameterized,
    expectedCards: new Map(), expectedMembers: new Map(),
  };
  for (const unit of units) {
    const expected = [];
    if (!unit.parameterized) expected.push(`${unit.id}/implementation`);
    else {
      expected.push(`${unit.id}/common-setup`);
      for (const dimension of unit.dimensions) {
        for (const member of dimension.members) {
          for (const operation of dimension.operationSets?.[member.id] ?? dimension.operations) {
            expected.push(`${unit.id}/${member.id}/${operation}`);
          }
        }
      }
    }
    ctx.expectedCards.set(unit.id, expected);
    ctx.expectedMembers.set(unit.id, unit.dimensions.map(dimension => ({
      kind: dimension.kind, operations: dimension.operations, operationSets: dimension.operationSets,
      memberIds: dimension.members.map(member => member.id),
      unresolvedIds: dimension.unresolved.map(item => item.id),
    })));
  }

  const scenarioCounts = new Map();
  const metrics = {
    settingUsagePoints: sources.contributions.settings.length,
    settingScopes: sources.portable.classification.coverage.scopes,
    dynamicSettingKeys: [...new Set(sources.contributions.settings.map(item => item.id))].filter(id => id.startsWith('dynamic:')).length,
    settingsUnique: new Set(sources.contributions.settings.map(item => item.id)).size,
    domainsSourceIndex: sources.domains.sourceIndex.length,
    contentSources: sources.content.contentSources.length,
    contributionSourceHashes: sources.contributions.sourceHashes.length,
    designTokens: sources.design.checks.uniqueTokens,
    brandScreenshots: sources.brand.keyPages.screenshots.length + sources.brand.keyPages.missing.length,
    classificationRegistry: sources.classification.registry.length,
    runtimeAssetFiles: sources.assets.assets.reduce((total, asset) => total + asset.files.length, 0),
  };

  const scenarios = [];
  const cardIds = new Set(cards.map(card => card.id));
  const memberCards = new Map();
  for (const card of cards) {
    if (!card.memberId) continue;
    const key = `${card.unit}\u0000${card.memberKind}\u0000${card.memberId}`;
    if (!memberCards.has(key)) memberCards.set(key, []);
    memberCards.get(key).push(card.id);
  }
  const memberKey = (unit, kind, id) => `${unit}\u0000${kind}\u0000${id}`;
  const addScenario = spec => {
    const unit = unitById.get(spec.unit);
    assert.ok(unit, `${spec.id}: unknown scenario unit ${spec.unit}`);
    const refs = spec.member ? memberCards.get(memberKey(spec.unit, spec.kind, spec.member)) : null;
    if (spec.requireMember) assert.ok(refs?.length, `${spec.id}: missing member cards ${spec.unit}/${spec.kind}/${spec.member}`);
    const cardRefs = spec.cardRefs ?? refs ?? [unit.parameterized ? `${unit.id}/common-setup` : `${unit.id}/implementation`];
    for (const ref of cardRefs) assert.ok(cardIds.has(ref), `${spec.id}: unknown card ${ref}`);
    const item = {
      id: spec.id, category: spec.category, itemId: String(spec.itemId), unit: spec.unit,
      relatedUnits: spec.relatedUnits ?? [], cardRefs, evidence: spec.evidence,
      testType: spec.testType, acceptance: spec.acceptance,
      status: spec.status ?? 'planned',
      metadata: spec.metadata ?? {},
    };
    assert.ok(item.evidence.length > 0 && item.evidence.every(evidenceExists), `${item.id}: evidence missing`);
    scenarios.push(item);
    scenarioCounts.set(item.category, (scenarioCounts.get(item.category) ?? 0) + 1);
  };

  // Navigation views and primary items.
  for (const view of sources.navigation.views) {
    addScenario({ id: `navigation.views:${view.id}`, category: 'navigation.views', itemId: view.id,
      unit: view.android.deliveryUnit, kind: 'app', member: view.id,
      evidence: [{ source: 'root', file: 'config/baseline/navigation.json', pointer: `views[id=${view.id}]` }],
      testType: 'compose-navigation', acceptance: view.acceptance,
      metadata: { title: view.title, visibility: view.visibility, route: view.android.route } });
  }
  for (const item of sources.navigation.navigation) {
    const view = sources.navigation.views.find(entry => entry.id === item.viewId);
    assert.ok(view, `navigation ${item.id}: missing view`);
    addScenario({ id: `navigation.items:${item.id}`, category: 'navigation.items', itemId: item.id,
      unit: view.android.deliveryUnit, kind: 'app', member: item.viewId,
      evidence: [{ source: 'root', file: 'config/baseline/navigation.json', pointer: `navigation[id=${item.id}]` }],
      testType: 'compose-navigation-return', acceptance: `open ${item.id} and return to caller`,
      metadata: { viewId: item.viewId, order: item.order } });
  }
  for (const app of sources.workbench.apps) {
    addScenario({ id: `workbench.apps:${app.id}`, category: 'workbench.apps', itemId: app.id,
      unit: app.android.deliveryUnit, kind: 'app', member: app.id,
      evidence: [{ source: 'root', file: 'config/baseline/workbench.json', pointer: `apps[id=${app.id}]` }],
      testType: 'workbench-host-draft-close', acceptance: app.acceptance.join(' / '),
      metadata: { resourceTypes: app.resourceTypes, instanceRule: app.instanceRule } });
  }
  const dynamic = sources.workbench.dynamicFamily;
  addScenario({ id: `workbench.dynamicFamily:${dynamic.id}`, category: 'workbench.dynamicFamily', itemId: dynamic.id,
    unit: 'S6.43', kind: 'dynamic-view-family', member: `${dynamic.prefix}*`,
    evidence: [{ source: 'root', file: 'config/baseline/workbench.json', pointer: 'dynamicFamily' }],
    testType: 'dynamic-view-register-remove', acceptance: dynamic.acceptance?.join(' / ') ?? 'owner scope, replacement, unregister and snapshot restore' });

  // UI entries.
  for (const item of sources.entries.commands) {
    addScenario({ id: `entries.commands:${item.id}`, category: 'entries.commands', itemId: item.id,
      unit: item.android.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/entries.json', pointer: `commands[id=${item.id}]` }],
      testType: 'command-palette', acceptance: item.acceptance.normal });
  }
  for (const item of sources.entries.menus) {
    addScenario({ id: `entries.menus:${item.id}`, category: 'entries.menus', itemId: item.id,
      unit: item.android.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/entries.json', pointer: `menus[id=${item.id}]` }],
      testType: 'context-menu-action' });
  }
  for (const item of sources.entries.debugPanels) {
    addScenario({ id: `entries.debugPanels:${item.id}`, category: 'entries.debugPanels', itemId: item.id,
      unit: item.android.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/entries.json', pointer: `debugPanels[id=${item.id}]` }],
      testType: 'debug-panel', acceptance: 'panel opens, reports real values, returns to caller' });
  }
  for (const item of sources.entries.externalEntries) {
    addScenario({ id: `entries.externalEntries:${item.id}`, category: 'entries.externalEntries', itemId: item.id,
      unit: item.unit, evidence: [{ source: 'root', file: 'config/baseline/entries.json', pointer: `externalEntries[id=${item.id}]` }],
      testType: 'external-entry-contract' });
  }

  // IPC contracts (unit mapping is structural; fields remain evidence, not separate tasks).
  const ipcUnits = {
    'F1-kernel': 'S1.02', 'F2-shell': 'S5.05', 'F3-storage': 'S2.01', 'F4-security': 'S2.13',
    'L01-llm-providers': 'S3.02', 'L02-llm-manager': 'S3.02', 'L04-chat-session': 'S3.01',
    'L05-agent-workspace': 'S3.19', 'L06-agent-orchestration': 'S3.22', 'L07-agent-skills-ptc': 'S3.20',
    'L10-notes': 'S5.07', 'L12-vfs-index': 'S4.24', 'L13-vfs-api': 'S2.06',
    'L14-qbank': 'S7.18', 'L15-review-analytics': 'S4.29', 'L16-anki': 'S7.08',
    'L17-memory-research': 'S3.24', 'L18-media-docs': 'S4.17', 'L19-mcp-browser': 'S6.28',
    'L20-sync': 'S8.01', 'L21-backup-governance': 'S8.18', 'L23-sync-providers': 'S8.02',
    'L24-chatanki': 'S7.08', '_shared': 'S1.03',
  };
  for (const contract of sources.domains.contracts) {
    const lane = contract.file.split('/').at(-1).replace(/\.json$/u, '');
    assert.ok(ipcUnits[lane], `IPC contract ${contract.file}: no unit mapping`);
    addScenario({ id: `ipc.contracts:${contract.file}`, category: 'ipc.contracts', itemId: contract.file,
      unit: ipcUnits[lane], evidence: [{ source: 'desktop', file: contract.file, pointer: '$' }],
      testType: 'ipc-contract', metadata: { sha256: contract.sha256, lane } });
  }

  // Domain services, commands, tools and background tasks; duplicate source ids merge.
  for (const tool of uniqueBy(sources.domains.tools, item => item.id)) {
    addScenario({ id: `domains.tools:${tool.id}`, category: 'domains.toolsUnique', itemId: tool.id,
      unit: tool.deliveryUnit, evidence: tool.evidenceList.length ? tool.evidenceList : [{ source: 'root', file: 'config/baseline/domains.json', pointer: `tools[id=${tool.id}]` }],
      testType: 'tool-contract', metadata: { sourceCount: tool.evidenceList.length || 1 } });
  }
  for (const service of uniqueBy(sources.domains.services, item => item.id)) {
    addScenario({ id: `domains.services:${service.id}`, category: 'domains.services', itemId: service.id,
      unit: service.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/domains.json', pointer: `services[id=${service.id}]` }],
      testType: 'service-contract', metadata: { sourceCount: service.evidenceList.length } });
  }
  for (const command of sources.domains.commands) {
    addScenario({ id: `domains.commands:${command.id}`, category: 'domains.commands', itemId: command.id,
      unit: command.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/domains.json', pointer: `commands[id=${command.id}]` }],
      testType: 'domain-command' });
  }
  for (const task of uniqueBy(sources.domains.backgroundTasks, item => item.id)) {
    addScenario({ id: `domains.backgroundTasks:${task.id}`, category: 'domains.backgroundTasks', itemId: task.id,
      unit: task.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/domains.json', pointer: `backgroundTasks[id=${task.id}]` }],
      testType: 'background-task', metadata: { sourceCount: task.evidenceList.length } });
  }

  // Settings, controls, sections and contribution surfaces.
  for (const section of sources.contributions.sections) {
    addScenario({ id: `contributions.sections:${section.id}`, category: 'contributions.sections', itemId: section.id,
      unit: section.android.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/contributions.json', pointer: `sections[id=${section.id}]` }],
      testType: 'settings-section' });
  }
  for (const setting of sources.contributions.settings) {
    if (scenarios.some(item => item.id === `contributions.settingsUnique:${setting.id}`)) continue;
    addScenario({ id: `contributions.settingsUnique:${setting.id}`, category: 'contributions.settingsUnique', itemId: setting.id,
      unit: setting.android.deliveryUnit, kind: 'setting', member: setting.id, requireMember: true,
      evidence: [{ source: 'root', file: 'config/baseline/contributions.json', pointer: `settings[id=${setting.id}]` },
        { source: 'root', file: 'config/contracts/portable-config.json', pointer: `classification.rows[sourceId=${setting.id}]` }],
      testType: 'setting-persist-and-scope', metadata: { scope: setting.scope, operation: setting.operation } });
  }
  for (const control of sources.contributions.controls) {
    addScenario({ id: `contributions.controls:${control.id}`, category: 'contributions.controls', itemId: control.id,
      unit: control.android.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/contributions.json', pointer: `controls[id=${control.id}]` }],
      testType: 'settings-control', metadata: { component: control.component } });
  }
  for (const contribution of sources.contributions.contributions) {
    addScenario({ id: `contributions.contributions:${contribution.id}`, category: 'contributions.contributions', itemId: contribution.id,
      unit: contribution.android.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/contributions.json', pointer: `contributions[id=${contribution.id}]` }],
      testType: 'contribution-register-unregister' });
  }
  for (const slot of sources.contributions.slotTypes) {
    addScenario({ id: `contributions.slotTypes:${slot.name}`, category: 'contributions.slotTypes', itemId: slot.name,
      unit: 'S6.34', evidence: [{ source: 'root', file: 'config/baseline/contributions.json', pointer: `slotTypes[name=${slot.name}]` }],
      testType: 'slot-contract', metadata: { type: slot.type } });
  }
  for (const slot of sources.contributions.slotDeclarations) {
    addScenario({ id: `contributions.slotDeclarations:${memberIdOf(slot.name)}`, category: 'contributions.slotDeclarations', itemId: memberIdOf(slot.name),
      unit: slot.android.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/contributions.json', pointer: `slotDeclarations[name=${memberIdOf(slot.name)}]` }],
      testType: 'slot-declaration', metadata: { cardinality: slot.cardinality } });
  }
  for (const surface of sources.contributions.slotSurfaces) {
    addScenario({ id: `contributions.slotSurfaces:${surface.slot}`, category: 'contributions.slotSurfaces', itemId: surface.slot,
      unit: surface.unit, evidence: [{ source: 'root', file: 'config/baseline/contributions.json', pointer: `slotSurfaces[slot=${surface.slot}]` }],
      testType: 'slot-surface-lifecycle', metadata: { hostUnit: surface.hostUnit, owner: surface.owner } });
  }

  // Content contract.
  for (const item of sources.content.dialect) {
    addScenario({ id: `content.dialect:${item.id}`, category: 'content.dialect', itemId: item.id, unit: item.deliveryUnit,
      evidence: [{ source: 'root', file: 'config/baseline/content.json', pointer: `dialect[id=${item.id}]` }], testType: 'markdown-dialect' });
  }
  for (const item of sources.content.markdown) {
    addScenario({ id: `content.markdown:${item.id}`, category: 'content.markdown', itemId: item.id, unit: item.deliveryUnit,
      evidence: [{ source: 'root', file: 'config/baseline/content.json', pointer: `markdown[id=${item.id}]` }], testType: 'markdown-golden' });
  }
  for (const item of sources.content.anki) {
    addScenario({ id: `content.anki:${item.id}`, category: 'content.anki', itemId: item.id, unit: item.deliveryUnit,
      evidence: [{ source: 'root', file: 'config/baseline/content.json', pointer: `anki[id=${item.id}]` }], testType: 'anki-template-golden' });
  }
  sources.content.inlineCards.forEach((item, index) => {
    addScenario({ id: `content.inlineCards:${index + 1}`, category: 'content.inlineCards', itemId: index + 1, unit: 'S5.16',
      evidence: [{ source: 'root', file: 'config/baseline/content.json', pointer: `inlineCards[${index}]` }], testType: 'inline-card-roundtrip' });
  });
  for (const item of sources.content.contentLanguages) {
    addScenario({ id: `content.languages:${item.language}`, category: 'content.languages', itemId: item.language, unit: item.deliveryUnit,
      evidence: [{ source: 'root', file: 'config/baseline/content.json', pointer: `contentLanguages[language=${item.language}]` }], testType: 'rich-content-render' });
  }
  for (const [index, item] of sources.content.registrations.entries()) {
    const key = typeof item.id === 'string' ? item.id : `generative-ui-host-${index}`;
    let unit = 'S5.13';
    let kind = null;
    let member = null;
    if (item.kind === 'blockRegistry.register') {
      unit = 'S5.19'; kind = 'chat-block'; member = typeof item.id === 'string' ? item.id : null;
    } else if (/callout|toggle|wikilink|mention|columns|inline-cards/u.test(key)) unit = 'S5.16';
    else if (/pdf-ref|media-ref/u.test(key)) unit = 'S5.15';
    addScenario({ id: `content.registrations:${key}`, category: 'content.registrations', itemId: key, unit,
      kind, member, evidence: [{ source: 'root', file: 'config/baseline/content.json', pointer: `registrations[${index}]` }],
      testType: item.kind === 'blockRegistry.register' ? 'chat-block-render' : 'editor-contribution' });
  }
  for (const item of sources.content.generativeTypes) {
    addScenario({ id: `content.generativeTypes:${item.type}`, category: 'content.generativeTypes', itemId: item.type,
      unit: item.deliveryUnit, kind: 'chat-block', member: item.type, requireMember: true,
      evidence: [{ source: 'root', file: 'config/baseline/content.json', pointer: `generativeTypes[type=${item.type}]` }],
      testType: 'generative-render-and-action-stream' });
  }
  for (const item of sources.content.generative) {
    addScenario({ id: `content.generativeSamples:${item.id}`, category: 'content.generativeSamples', itemId: item.id,
      unit: item.deliveryUnit, evidence: [{ source: 'root', file: 'config/baseline/content.json', pointer: `generative[id=${item.id}]` }],
      testType: 'generative-schema-vector' });
  }

  // Operit adoption, assets, engines, official UI and examples.
  for (const item of sources.adoption.adopted) {
    addScenario({ id: `operit.adopted:${item.id}`, category: 'operit.adopted', itemId: item.id, unit: item.deliveryUnit,
      evidence: [{ source: 'root', file: 'config/operit-adoption.json', pointer: `adopted[id=${item.id}]` }], testType: 'operit-source-conformance' });
  }
  for (const asset of sources.assets.assets) {
    addScenario({ id: `operit.runtimeAssets:${asset.id}`, category: 'operit.runtimeAssets', itemId: asset.id, unit: asset.deliveryUnit,
      evidence: [{ source: 'root', file: 'config/runtime-assets.json', pointer: `assets[id=${asset.id}]` }], testType: 'runtime-asset-packaging' });
  }
  for (const pkg of sources.sourceLock.packages) {
    addScenario({ id: `operit.sourcePackages:${pkg.id}`, category: 'operit.sourcePackages', itemId: pkg.id, unit: pkg.androidAdoptionUnit,
      evidence: [{ source: 'root', file: 'config/source-lock.json', pointer: `packages[id=${pkg.id}]` }], testType: 'source-lock-and-license' });
  }
  const rejectedEngines = new Set(['saba', 'bullet3', 'ufbx', 'dragonbones-cpp']);
  for (const engine of sources.sourceLock.engines) {
    addScenario({ id: `operit.engines:${engine.id}`, category: 'operit.engines', itemId: engine.id, unit: engine.adoptionUnit,
      evidence: [{ source: 'root', file: 'config/source-lock.json', pointer: `engines[id=${engine.id}]` }],
      testType: rejectedEngines.has(engine.id) ? 'rejected-source-evidence' : 'engine-build-matrix',
      status: rejectedEngines.has(engine.id) ? 'not-adopted' : 'planned' });
  }
  for (const item of sources.officialUi) {
    addScenario({ id: `operit.officialUiPackages:${item.id}`, category: 'operit.officialUiPackages', itemId: item.id,
      unit: 'S7.37', kind: 'official-ui', member: `ui:${item.id}`, requireMember: true,
      evidence: [item.evidence], testType: 'official-ui-contribution' });
  }
  for (const item of sources.examples) {
    addScenario({ id: `operit.examplePackages:${item.id}`, category: 'operit.examplePackages', itemId: item.id,
      unit: 'S7.37', kind: 'example', member: `example:${item.id}`, requireMember: true,
      evidence: [item.evidence], testType: 'example-package-install-run' });
  }

  // Sync registry, gaps, providers and version scenarios.
  for (const row of sources.classification.registry) {
    addScenario({ id: `sync.registry:${row.database}.${row.table}`, category: 'sync.registry', itemId: `${row.database}.${row.table}`,
      unit: row.adoptionUnit, evidence: [{ source: 'root', file: 'config/baseline/classification.json',
        pointer: `registry[database=${row.database},table=${row.table}]` }], testType: 'row-sync-contract',
      metadata: { category: row.category, conflictPolicy: row.conflictPolicy } });
  }
  for (const gap of sources.classification.gapTasks) {
    const unit = gap.androidAdoptionUnits[0];
    addScenario({ id: `sync.gapTasks:${gap.id}`, category: 'sync.gapTasks', itemId: gap.id, unit,
      relatedUnits: gap.androidAdoptionUnits, evidence: [{ source: 'root', file: 'config/baseline/classification.json', pointer: `gapTasks[id=${gap.id}]` }],
      testType: 'cross-device-gap', metadata: { domain: gap.domain, captureUnit: gap.desktopCaptureUnit } });
  }
  const syncProviderUnits = { webdav: 'S8.02', s3: 'S8.03', ftp: 'S8.04' };
  for (const provider of sources.protocol.providers) {
    addScenario({ id: `sync.providers:${provider.provider}`, category: 'sync.providers', itemId: provider.provider,
      unit: syncProviderUnits[provider.provider], evidence: [{ source: 'root', file: 'config/baseline/protocol.json', pointer: `providers[provider=${provider.provider}]` }],
      testType: 'sync-provider-conformance' });
  }
  const schemaUnits = { vfs: 'S2.02', chat_v2: 'S3.01', mistakes: 'S7.18', llm_usage: 'S3.11' };
  for (const schema of sources.protocol.schemaMatrix) {
    addScenario({ id: `sync.schemaMatrix:${schema.database}`, category: 'sync.schemaMatrix', itemId: schema.database,
      unit: schemaUnits[schema.database], evidence: [{ source: 'root', file: 'config/baseline/protocol.json', pointer: `schemaMatrix[database=${schema.database}]` }],
      testType: 'schema-wire-matrix', metadata: { schemaVersion: schema.schemaVersion } });
  }
  for (const scenario of Object.keys(sources.protocol.scenarios)) {
    addScenario({ id: `sync.scenarios:${scenario}`, category: 'sync.scenarios', itemId: scenario, unit: 'S8.16',
      evidence: [{ source: 'root', file: 'config/baseline/protocol.json', pointer: `scenarios.${scenario}` }],
      testType: 'cross-version-sync' });
  }

  // Visual, terminology and component contract items.
  for (const page of sources.brand.keyPages.pages) {
    addScenario({ id: `visual.keyPages:${page.scene}`, category: 'visual.keyPages', itemId: page.scene,
      unit: page.workbenchApp.deliveryUnit, kind: 'app', member: page.workbenchApp.appId,
      evidence: [{ source: 'root', file: 'config/design/brand.baseline.json', pointer: `keyPages.pages[scene=${page.scene}]` }],
      testType: 'page-visual-golden', metadata: { route: page.route, goldens: page.goldens.length } });
  }
  for (const page of sources.brand.operitPages) {
    addScenario({ id: `visual.operitPages:${page.id}`, category: 'visual.operitPages', itemId: page.id, unit: page.deliveryUnit,
      evidence: [{ source: 'root', file: 'config/design/brand.baseline.json', pointer: `operitPages[id=${page.id}]` }],
      testType: 'operit-page-theme-golden' });
  }
  for (const icon of sources.brand.icons.navigationIcons) {
    addScenario({ id: `visual.navigationIcons:${icon.navigationId}`, category: 'visual.navigationIcons', itemId: icon.navigationId, unit: 'S5.04',
      evidence: [{ source: 'root', file: 'config/design/brand.baseline.json', pointer: `icons.navigationIcons[navigationId=${icon.navigationId}]` }],
      testType: 'icon-geometry-golden' });
  }
  for (const palette of sources.design.palettes.all) {
    const id = memberIdOf(palette);
    addScenario({ id: `visual.palettes:${id}`, category: 'visual.palettes', itemId: id, unit: 'S5.01',
      evidence: [{ source: 'root', file: 'config/design/tokens.baseline.json', pointer: `palettes.all[id=${id}]` }], testType: 'palette-token-golden' });
  }
  for (const variant of Object.keys(sources.design.variants)) {
    addScenario({ id: `visual.variants:${variant}`, category: 'visual.variants', itemId: variant, unit: 'S5.01',
      evidence: [{ source: 'root', file: 'config/design/tokens.baseline.json', pointer: `variants.${variant}` }], testType: 'theme-variant-golden' });
  }
  for (const variant of Object.keys(sources.design.buttons.contract.variants)) {
    addScenario({ id: `visual.buttonVariants:${variant}`, category: 'visual.buttonVariants', itemId: variant, unit: 'S5.02',
      kind: 'component', member: 'button', evidence: [{ source: 'root', file: 'config/design/tokens.baseline.json', pointer: `buttons.contract.variants.${variant}` }],
      testType: 'component-state-golden' });
  }
  for (const item of inputs.userAddedPlan) {
    addScenario({ id: `userAddedPlan:${item.id}`, category: 'userAddedPlan', itemId: item.id, unit: item.units[0],
      relatedUnits: item.units, evidence: [{ source: 'plan', file: PLAN_FILE, pointer: item.planPointer }],
      testType: item.testType, acceptance: item.acceptance });
  }

  // Scenario coverage and metrics.
  const expectedScenarioKeys = Object.keys(inputs.expectedCoverage)
    .filter(key => !['contributions.settingUsagePoints', 'contributions.dynamicSettingKeys'].includes(key));
  const coverageCounts = Object.fromEntries([...scenarioCounts.entries()].sort(([a], [b]) => a.localeCompare(b)));
  const coverage = {
    scenarios: coverageCounts,
    totalScenarios: scenarios.length,
    metrics,
    sourceLeaves: {
      domainsSourceIndex: metrics.domainsSourceIndex,
      contentSources: metrics.contentSources,
      contributionSourceHashes: metrics.contributionSourceHashes,
      designTokens: metrics.designTokens,
      brandScreenshots: metrics.brandScreenshots,
      runtimeAssetFiles: metrics.runtimeAssetFiles,
      note: 'Source leaves, paths and symbols are hashed coverage evidence, not separate product tasks.',
    },
    parameterized: {
      parents: units.filter(unit => unit.parameterized).length,
      dimensions: units.reduce((total, unit) => total + unit.dimensions.length, 0),
      resolvedMembers: units.reduce((total, unit) => total + unit.dimensions.reduce((sum, dimension) => sum + dimension.members.length, 0), 0),
      unresolvedMembers: units.reduce((total, unit) => total + unit.dimensions.reduce((sum, dimension) => sum + dimension.unresolved.length, 0), 0),
      setupCards: cards.filter(card => card.role === 'setup').length,
      operationCards: cards.filter(card => card.role === 'subcard').length,
      totalCards: cards.length,
      byKind: cards.filter(card => card.role === 'subcard').reduce((result, card) => {
        result[card.memberKind] = (result[card.memberKind] ?? 0) + 1; return result;
      }, {}),
    },
  };

  // M0 signoff table with observed artifact hashes.
  const freezeHashes = sources.inputs.hashPolicy.frozen === true || process.env.EXECUTION_PLAN_FREEZE_HASHES === '1';
  const hashState = freezeHashes ? 'frozen' : 'provisional-observed';
  const m0Rows = inputs.signoff.rows.map(row => {
    const artifacts = row.artifacts.map(file => {
      assert.ok(hasFile(file), `${row.unit}: missing artifact ${file}`);
      return { file, sha256: fileSha(file), hashState };
    });
    return { ...row, artifacts };
  });
  const m0 = {
    milestone: 'M0', exit: 'S0.G', finalAuthority: inputs.signoff.finalAuthority,
    status: m0Rows.every(row => row.status === 'signed') ? 'ready-for-final-signoff' : 'awaiting-row-signoff',
    readyForFinalSignoff: m0Rows.every(row => row.status === 'signed'),
    pendingUnits: m0Rows.filter(row => row.status !== 'signed').map(row => row.unit),
    rows: m0Rows,
    summary: {
      total: m0Rows.length,
      signed: m0Rows.filter(row => row.status === 'signed').length,
      verifiedPendingSignoff: m0Rows.filter(row => row.status === 'verified-pending-signoff').length,
      stablePendingSignoff: m0Rows.filter(row => row.status === 'stable-pending-signoff').length,
      capturingInProgress: m0Rows.filter(row => row.status === 'capturing-in-progress').length,
      pendingRevalidation: m0Rows.filter(row => row.status === 'pending-revalidation').length,
    },
    note: 'S0.25 is the current unit and is not self-signed. When every S0.01-S0.24 row is signed, this table becomes ready for final S0.G signoff by the main thread.',
  };

  // Input bindings: S0 artifacts and root configuration only.
  const bindingFiles = [...new Set([INPUTS, PLAN_FILE,
    ...inputs.signoff.rows.flatMap(row => row.artifacts),
    'config/baseline/contributions.json', 'config/baseline/classification.json',
    'config/baseline/content.json', 'config/baseline/domains.json',
    'config/baseline/navigation.json', 'config/baseline/workbench.json',
    'config/baseline/entries.json', 'config/baseline/protocol.json',
    'config/design/tokens.baseline.json', 'config/design/brand.baseline.json',
    'config/operit-adoption.json', 'config/runtime-assets.json', 'config/source-lock.json',
    'config/contracts/portable-config.json', 'config/sync-release-window.json',
    'config/sync-registry-contract.json', 'config/baseline/schema.json',
  ])];
  const inputBindings = bindingFiles.map(file => ({
    file, sha256: fileSha(file), bytes: statSync(resolve(root, file)).size, hashState, observedAt: isoNow(),
  }));

  const plan = {
    formatVersion: 1,
    schemaVersion: 'dstu-s0.25-execution-plan/v1',
    unit: 'S0.25',
    status: 'captured-pending-signoff',
    unitStatus: 'implementation-complete-pending-main-review',
    signed: false,
    generatedAt: isoNow(),
    baseline: sources.inputs.baseline,
    plan: { file: PLAN_FILE, sha256: fileSha(PLAN_FILE), mainUnits: units.length, stageExits: stageExits.length },
    policy: {
      schedule: inputs.schedule, executor: inputs.executor,
      hashPolicy: { ...inputs.hashPolicy, currentHashState: hashState },
      dependencyRule: 'declared dependencies + inherited previous unit + previous stage exit; every dependency resolves to an earlier unit/exit',
      workdayRule: 'one execution channel; parameterized parent total = common setup once + ordered member operation cards; actual days null',
      unresolvedRule: 'unresolved identities are recorded and receive no fabricated operation cards',
    },
    schedule: scheduleSummary,
    operationAliases: inputs.operationAliases,
    units, stageExits, queue, cards, scenarios, coverage, m0,
    unresolved: inputs.unresolved,
    evidence: { inputsSha256: fileSha(INPUTS), inputBindings },
    checks: {},
  };

  plan.checks = validatePlan(plan, ctx);
  const selfTests = runSelfTests(plan, ctx);
  plan.checks.selfTests = selfTests;
  validatePlan(plan, ctx);
  return { plan, ctx };
}

function validatePlan(plan, ctx) {
  const { inputs, units, stageExits, cards, expectedCards, expectedMembers, parameterized } = ctx;
  assert.equal(plan.units.length, inputs.expected.mainUnits, 'main unit count');
  assert.equal(plan.stageExits.length, inputs.expected.stageExits, 'stage exit count');
  assert.equal(new Set(plan.units.map(unit => unit.id)).size, plan.units.length, 'duplicate unit id');
  const unitsById = new Map(plan.units.map(unit => [unit.id, unit]));
  for (const [stage, expectedCount] of Object.entries(inputs.expected.stageUnitCounts)) {
    assert.equal(plan.units.filter(unit => unit.stage === stage).length, expectedCount, `${stage} unit count`);
  }
  assert.deepEqual(plan.units.map(unit => unit.serialIndex), Array.from({ length: plan.units.length }, (_, index) => index + 1), 'serial index');
  const orderById = new Map(plan.units.map(unit => [unit.id, unit.order]));
  for (const exit of plan.stageExits) orderById.set(exit.id, exit.order);
  for (const unit of plan.units) {
    for (const dependency of unit.dependencies) {
      assert.ok(orderById.has(dependency), `${unit.id}: unknown dependency ${dependency}`);
      assert.ok(orderById.get(dependency) < unit.order, `${unit.id}: forward dependency ${dependency}`);
    }
  }
  for (const exit of plan.stageExits) {
    for (const dependency of exit.dependencies) {
      assert.ok(orderById.has(dependency), `${exit.id}: unknown dependency ${dependency}`);
      assert.ok(orderById.get(dependency) < exit.order, `${exit.id}: forward dependency ${dependency}`);
    }
  }
  for (let value = 1; value <= 24; value++) {
    assert.ok(plan.units.find(unit => unit.id === 'S0.25').dependencies.includes(`S0.${String(value).padStart(2, '0')}`),
      `S0.25: range dependency S0.${String(value).padStart(2, '0')}`);
  }
  for (const dependency of ['S0.07', 'S0.08', 'S0.09', 'S0.10']) {
    assert.ok(plan.units.find(unit => unit.id === 'S0.18').dependencies.includes(dependency), `S0.18: range dependency ${dependency}`);
  }
  for (const dependency of ['S1.01', 'S1.02']) {
    assert.ok(plan.units.find(unit => unit.id === 'S1.03').dependencies.includes(dependency), `S1.03: inherited dependency ${dependency}`);
  }
  for (const dependency of ['S1.01', 'S1.02', 'S1.03']) {
    assert.ok(plan.units.find(unit => unit.id === 'S1.04').dependencies.includes(dependency), `S1.04: inherited dependency ${dependency}`);
  }
  assert.ok(plan.units.find(unit => unit.id === 'S2.01').dependencies.includes('S1.G'), 'S2.01: previous stage exit');
  assert.ok(plan.units.find(unit => unit.id === 'S8.01').dependencies.includes('S7.G'), 'S8.01: previous stage exit');
  assert.equal(orderById.size, plan.units.length + plan.stageExits.length, 'queue identity');
  const queueOrders = plan.queue.map(item => item.order);
  assert.deepEqual(queueOrders, [...queueOrders].sort((a, b) => a - b), 'queue order');
  assert.equal(plan.queue.length, plan.units.length + plan.stageExits.length, 'queue length');
  const historicalStage = plan.schedule.historicalStage;
  const historicalExitId = `${historicalStage}.G`;
  for (const item of plan.queue) {
    if (item.phase === 'completed') {
      if (item.id === historicalExitId) {
        assert.equal(item.projectedCompletionDate, plan.schedule.actualCompletedDate, `${item.id}: actual date`);
        assert.ok(isWeekday(item.projectedCompletionDate), `${item.id}: weekend date`);
      } else {
        assert.equal(item.projectedCompletionDate, null, `${item.id}: historical unit must not be replayed`);
      }
    } else {
      assert.equal(item.phase, 'projected', `${item.id}: schedule phase`);
      assert.ok(ISO_DATE.test(item.projectedCompletionDate), `${item.id}: date`);
      assert.ok(isWeekday(item.projectedCompletionDate), `${item.id}: weekend date`);
      assert.ok(item.projectedCompletionDate >= plan.schedule.projectedFutureStartDate, `${item.id}: before future start`);
    }
  }
  const queueById = new Map(plan.queue.map(item => [item.id, item]));
  for (const exit of plan.stageExits) {
    assert.equal(exit.projectedCompletionDate, queueById.get(exit.id).projectedCompletionDate, `${exit.id}: schedule date`);
    if (exit.id === historicalExitId) {
      assert.equal(exit.actualCompletedDate, plan.schedule.actualCompletedDate, `${exit.id}: actual completed`);
      assert.equal(exit.actualWorkDays, null, `${exit.id}: actual days`);
    } else {
      assert.ok(isWeekday(exit.projectedCompletionDate), `${exit.id}: weekend date`);
    }
  }
  const projectedQueue = plan.queue.filter(item => item.phase === 'projected');
  for (let index = 1; index < projectedQueue.length; index++) {
    assert.ok(projectedQueue[index].remainingCumulativeDays >= projectedQueue[index - 1].remainingCumulativeDays, 'remaining cumulative days');
    assert.ok(projectedQueue[index].projectedCompletionDate >= projectedQueue[index - 1].projectedCompletionDate, 'monotonic projected dates');
  }
  for (let index = 1; index < plan.queue.length; index++) {
    assert.ok(plan.queue[index].cumulativeEstimatedDays >= plan.queue[index - 1].cumulativeEstimatedDays, 'single cumulative estimate timeline');
  }
  assert.equal(plan.queue.at(-1).cumulativeEstimatedDays, plan.schedule.totalEstimatedWorkDays, 'cumulative estimate total');
  assert.equal(plan.schedule.totalEstimatedWorkDays,
    roundHalf(plan.units.reduce((sum, unit) => sum + unit.estimatedWorkDays, 0) +
      plan.stageExits.reduce((sum, exit) => sum + exit.estimatedWorkDays, 0)), 'total estimate');
  assert.equal(plan.schedule.historicalEstimatedWorkDays,
    roundHalf(plan.units.filter(unit => unit.stage === historicalStage)
      .reduce((sum, unit) => sum + unit.estimatedWorkDays, 0) +
      plan.stageExits.filter(exit => exit.stage === historicalStage).reduce((sum, exit) => sum + exit.estimatedWorkDays, 0)),
    'historical estimate');
  assert.equal(plan.schedule.remainingEstimatedWorkDays,
    roundHalf(plan.schedule.totalEstimatedWorkDays - plan.schedule.historicalEstimatedWorkDays), 'remaining estimate');
  assert.equal(plan.schedule.actualCompleted[historicalExitId].actualCompletedDate, plan.schedule.actualCompletedDate, 'M0 actual date');
  assert.equal(plan.schedule.actualCompleted[historicalExitId].actualWorkDays, null, 'M0 actual days');
  assert.equal(plan.schedule.actualCompleted[historicalExitId].signoffStatus, 'pending-main-signoff', 'M0 main signoff pending');
  assert.equal(plan.schedule.historicalCutoff.signoffStatus, 'pending-main-signoff', 'historical cutoff pending signoff');
  assert.equal(plan.schedule.historicalCutoff.finalDateSame, true, 'historical cutoff final date same');
  const m0Milestone = plan.stageExits.find(exit => exit.milestone === 'M0');
  assert.equal(m0Milestone.projectedCompletionDate, plan.schedule.actualCompletedDate, 'M0 milestone date');
  assert.equal(m0Milestone.signoffStatus, 'pending-main-signoff', 'M0 milestone signoff pending');
  assert.equal(m0Milestone.dateBasis, 'planned-s0-exit-asOf', 'M0 milestone asOf basis');
  for (const exit of plan.stageExits.filter(exit => exit.milestone && exit.milestone !== 'M0')) {
    assert.ok(exit.projectedCompletionDate >= plan.schedule.projectedFutureStartDate, `${exit.milestone}: future start`);
  }
  for (const unit of plan.units) {
    if (unit.stage === historicalStage) {
      assert.equal(unit.schedulePhase, 'completed', `${unit.id}: historical phase`);
      assert.equal(unit.projectedCompletionDate, null, `${unit.id}: historical date not replayed`);
    } else {
      assert.equal(unit.schedulePhase, 'projected', `${unit.id}: projected phase`);
      assert.ok(unit.projectedCompletionDate >= plan.schedule.projectedFutureStartDate, `${unit.id}: projected start`);
    }
  }
  assert.equal(new Set(plan.cards.map(card => card.id)).size, plan.cards.length, 'duplicate card');
  const cardById = new Map(plan.cards.map(card => [card.id, card]));
  for (const unit of plan.units) {
    const expected = expectedCards.get(unit.id);
    assert.deepEqual(unit.cardIds, expected, `${unit.id}: expected cards`);
    const actual = plan.cards.filter(card => card.unit === unit.id).map(card => card.id);
    assert.deepEqual(actual, expected, `${unit.id}: card order`);
    const dimensions = unit.parameterized ? unit.dimensions : [];
    assert.deepEqual(dimensions.map(dimension => ({
      kind: dimension.kind, operations: dimension.operations, operationSets: dimension.operationSets,
      memberIds: dimension.members.map(member => member.id),
      unresolvedIds: dimension.unresolved.map(item => item.id),
    })), expectedMembers.get(unit.id), `${unit.id}: parameter members`);
    if (!unit.parameterized) {
      assert.equal(unit.cardIds.length, 1, `${unit.id}: non-parameterized card`);
      assert.ok(unit.estimatedWorkDays >= 1 && unit.estimatedWorkDays <= 3, `${unit.id}: unit days`);
    } else {
      assert.ok(unit.estimatedWorkDays > 0, `${unit.id}: setup days`);
    }
    const total = unit.cardIds.reduce((sum, id) => sum + cardById.get(id).estimatedWorkDays, 0);
    assert.equal(unit.estimatedWorkDays, roundHalf(total), `${unit.id}: parent sum counted once`);
    assert.equal(unit.actualWorkDays, null, `${unit.id}: actual`);
    for (const id of unit.cardIds) {
      const card = cardById.get(id);
      assert.ok(card.estimatedWorkDays > 0 && Number.isInteger(card.estimatedWorkDays * 2), `${id}: half-day estimate`);
      assert.ok([0.5, 1, 1.5, 2, 2.5, 3].includes(card.estimatedWorkDays), `${id}: unreasonable estimate`);
      assert.equal(card.actualWorkDays, null, `${id}: actual`);
      for (const evidence of card.evidence) assert.ok(evidenceExists(evidence), `${id}: evidence missing`);
    }
  }
  const resolvedMembers = units.reduce((total, unit) => total + unit.dimensions.reduce((sum, dimension) => sum + dimension.members.length, 0), 0);
  assert.equal(plan.cards.filter(card => card.role === 'subcard').length,
    units.reduce((total, unit) => total + unit.dimensions.reduce((sum, dimension) =>
      sum + dimension.members.reduce((count, member) => count + (dimension.operationSets?.[member.id] ?? dimension.operations).length, 0), 0), 0),
  'operation card count');
  assert.equal(plan.coverage.parameterized.resolvedMembers, resolvedMembers, 'resolved members');
  assert.equal(plan.coverage.parameterized.unresolvedMembers,
    units.reduce((total, unit) => total + unit.dimensions.reduce((sum, dimension) => sum + dimension.unresolved.length, 0), 0), 'unresolved members');
  assert.equal(plan.coverage.parameterized.unresolvedMembers, 0, 'no unresolved parameter members');
  assert.equal(plan.unresolved.length, 0, 'no unresolved records');
  assert.deepEqual(plan.operationAliases, inputs.operationAliases, 'operation aliases');
  const browserUnit = plan.units.find(unit => unit.id === 'S6.38');
  assert.ok(browserUnit.cardIds.includes('S6.38/browser_scroll/execute'), 'browser_scroll execute card');
  assert.ok(browserUnit.cardIds.includes('S6.38/browser_scroll/permission-cancel'), 'browser_scroll permission card');
  for (const alias of plan.operationAliases.browser_scroll) {
    assert.ok(browserUnit.dimensions.some(dimension => dimension.members.some(member => member.id === alias)),
      `browser_scroll alias ${alias} must be a resolved browser action`);
  }
  const s737 = plan.units.find(unit => unit.id === 'S7.37');
  assert.deepEqual(s737.dimensions.map(dimension => dimension.kind), ['plugin', 'official-ui', 'example'], 'S7.37 dimensions');
  assert.deepEqual(s737.dimensions.map(dimension => dimension.members.length), [30, 19, 4], 'S7.37 per-package counts');
  assert.ok(s737.dimensions[0].members.every(member => member.id.startsWith('host:')), 'S7.37 host prefix');
  assert.ok(s737.dimensions[1].members.every(member => member.id.startsWith('ui:')), 'S7.37 ui prefix');
  assert.ok(s737.dimensions[2].members.every(member => member.id.startsWith('example:')), 'S7.37 example prefix');

  const scenarioIds = new Set();
  const scenarioUnitIds = new Set(plan.units.map(unit => unit.id));
  for (const scenario of plan.scenarios) {
    assert.ok(!scenarioIds.has(scenario.id), `duplicate scenario ${scenario.id}`);
    scenarioIds.add(scenario.id);
    assert.ok(scenarioUnitIds.has(scenario.unit), `${scenario.id}: unknown unit`);
    for (const unit of scenario.relatedUnits) {
      assert.ok(scenarioUnitIds.has(unit) || plan.stageExits.some(exit => exit.id === unit),
        `${scenario.id}: unknown related unit ${unit}`);
    }
    for (const ref of scenario.cardRefs) assert.ok(cardById.has(ref), `${scenario.id}: unknown card ${ref}`);
    assert.ok(scenario.evidence.length > 0 && scenario.evidence.every(evidenceExists), `${scenario.id}: evidence`);
  }
  const expectedScenarioKeys = Object.keys(inputs.expectedCoverage)
    .filter(key => !['contributions.settingUsagePoints', 'contributions.dynamicSettingKeys'].includes(key));
  for (const key of expectedScenarioKeys) {
    assert.equal(plan.coverage.scenarios[key] ?? 0, inputs.expectedCoverage[key], `coverage ${key}`);
  }
  assert.equal(plan.coverage.metrics.settingUsagePoints, inputs.expectedCoverage['contributions.settingUsagePoints'], 'setting usage points');
  assert.equal(plan.coverage.metrics.dynamicSettingKeys, inputs.expectedCoverage['contributions.dynamicSettingKeys'], 'dynamic settings');
  assert.equal(plan.coverage.totalScenarios, plan.scenarios.length, 'scenario total');

  for (const unresolved of plan.unresolved) {
    assert.ok(unitsById.has(unresolved.unit), `${unresolved.id}: unknown unit`);
    assert.ok(unresolved.reason && unresolved.reason.length > 20, `${unresolved.id}: unresolved reason`);
  }
  assert.equal(plan.m0.rows.length, 24, 'M0 rows');
  assert.deepEqual(plan.m0.rows.map(row => row.unit), Array.from({ length: 24 }, (_, index) => `S0.${String(index + 1).padStart(2, '0')}`), 'M0 unit order');
  for (const row of plan.m0.rows) {
    assert.ok(row.verifyEntry, `${row.unit}: verify entry`);
    assert.ok(row.artifacts.length > 0, `${row.unit}: artifacts`);
    for (const artifact of row.artifacts) {
      assert.ok(hasFile(artifact.file), `${row.unit}: artifact ${artifact.file}`);
      assert.match(artifact.sha256, /^[0-9a-f]{64}$/u);
    }
    assert.ok(['signed', 'verified-pending-signoff', 'stable-pending-signoff', 'capturing-in-progress', 'pending-revalidation'].includes(row.status), `${row.unit}: status`);
  }
  assert.ok(['awaiting-row-signoff', 'ready-for-final-signoff'].includes(plan.m0.status), 'M0 status');
  assert.equal(plan.m0.readyForFinalSignoff, plan.m0.rows.every(row => row.status === 'signed'), 'M0 readiness');
  assert.deepEqual(plan.m0.pendingUnits, plan.m0.rows.filter(row => row.status !== 'signed').map(row => row.unit), 'M0 pending units');
  assert.equal(plan.m0.summary.signed, 24, 'M0 all rows signed');
  assert.equal(plan.m0.status, 'ready-for-final-signoff', 'M0 ready for S0.G final signoff');
  assert.deepEqual(plan.m0.pendingUnits, [], 'M0 no pending rows');
  assert.equal(plan.signed, false, 'S0.25 must not self-sign');
  assert.ok(plan.evidence.inputBindings.length > 0, 'input bindings');
  assert.equal(plan.policy.hashPolicy.frozen, true, 'persistent hash freeze');
  assert.equal(plan.policy.hashPolicy.currentHashState, 'frozen', 'frozen hash state');
  assert.ok(plan.evidence.inputBindings.every(binding => binding.hashState === 'frozen'), 'frozen input bindings');
  return {
    mainUnits: plan.units.length,
    stageExits: plan.stageExits.length,
    serialQueue: plan.queue.length,
    parameterizedParents: plan.coverage.parameterized.parents,
    resolvedMembers,
    unresolvedMembers: plan.coverage.parameterized.unresolvedMembers,
    totalCards: plan.cards.length,
    operationCards: plan.coverage.parameterized.operationCards,
    scenarios: plan.scenarios.length,
    estimatedWorkDays: roundHalf(plan.units.reduce((sum, unit) => sum + unit.estimatedWorkDays, 0) +
      plan.stageExits.reduce((sum, exit) => sum + exit.estimatedWorkDays, 0)),
    milestones: plan.stageExits.filter(exit => exit.milestone).map(exit => ({ id: exit.milestone, exit: exit.id, date: exit.projectedCompletionDate })),
    m0: plan.m0.summary,
  };
}

function runSelfTests(plan, ctx) {
  const cases = [
    ['missing-parameter-member', value => {
      const unit = value.units.find(item => item.id === 'S3.04');
      unit.dimensions[0].members.pop();
      value.cards = value.cards.filter(card => !(card.unit === 'S3.04' && card.memberId === 'mistral'));
      unit.cardIds = unit.cardIds.filter(id => !id.includes('/mistral/'));
    }],
    ['duplicate-parameter-member', value => {
      const unit = value.units.find(item => item.id === 'S3.04');
      unit.dimensions[0].members.push({ ...unit.dimensions[0].members[0] });
    }],
    ['forward-dependency', value => {
      value.units.find(item => item.id === 'S1.02').dependencies.push('S9.19');
    }],
    ['duration-not-half-day', value => { value.cards[0].estimatedWorkDays = 0.25; }],
    ['duration-parent-sum-mismatch', value => { value.units.find(item => item.id === 'S7.35').estimatedWorkDays += 0.5; }],
    ['missing-scenario-coverage', value => { value.scenarios.pop(); }],
    ['unknown-scenario-unit', value => { value.scenarios[0].unit = 'S9.99'; }],
    ['placeholder-member', value => { value.units.find(item => item.id === 'S5.02').dimensions[0].members[0].id = 'TODO'; }],
    ['fabricated-actual-days', value => { value.cards[0].actualWorkDays = 1; }],
    ['missing-stage-exit', value => { value.stageExits.pop(); }],
    ['missing-signoff-row', value => { value.m0.rows.pop(); }],
    ['duplicate-main-unit', value => { value.units.push(clone(value.units[0])); }],
    ['missing-dependency-target', value => { value.units.find(item => item.id === 'S2.01').dependencies.push('S9.99'); }],
    ['weekend-milestone', value => { value.stageExits.find(exit => exit.id === 'S0.G').projectedCompletionDate = '2026-11-21'; }],
  ];
  const results = [];
  for (const [id, mutate] of cases) {
    const value = clone(plan);
    mutate(value);
    let rejected = false;
    let message = null;
    try { validatePlan(value, ctx); } catch (error) { rejected = true; message = error.message; }
    assert.ok(rejected, `self-test ${id}: mutation was accepted`);
    results.push({ id, expected: 'reject', passed: true, message });
    log('execution-plan.selftest', { id, passed: true });
  }
  return { cases: results.length, passed: results.filter(item => item.passed).length, results };
}

function stablePlan(plan) {
  const value = clone(plan);
  delete value.generatedAt;
  for (const binding of value.evidence.inputBindings) delete binding.observedAt;
  if (value.policy.hashPolicy.currentHashState !== 'frozen') {
    delete value.evidence.inputsSha256;
    for (const binding of value.evidence.inputBindings) { delete binding.sha256; delete binding.bytes; }
    for (const row of value.m0.rows) for (const artifact of row.artifacts) delete artifact.sha256;
  }
  return value;
}

export async function run(mode) {
  assert.ok(['capture', 'verify'].includes(mode), 'usage: node tools/baseline/execution-plan.mjs capture|verify');
  const started = performance.now();
  log('execution-plan.start', { mode, input: INPUTS });
  const sources = loadSources();
  const { plan, ctx } = buildPlan(sources);
  const summary = plan.checks;
  const logSummary = {
    ...summary,
    selfTests: { cases: summary.selfTests.cases, passed: summary.selfTests.passed },
  };
  if (mode === 'capture') {
    saveJson(OUTPUT, plan);
    log('execution-plan.captured', { file: OUTPUT, ...logSummary, ms: Math.round(performance.now() - started) });
    return plan;
  }
  assert.ok(existsSync(resolve(root, OUTPUT)), `${OUTPUT}: missing; capture first`);
  const stored = readJson(OUTPUT);
  const drift = difference(stablePlan(stored), stablePlan(plan));
  if (drift !== null) {
    log('execution-plan.drift', drift);
    throw new Error(`${OUTPUT}: semantic drift at ${drift.path}`);
  }
  const current = new Map(plan.evidence.inputBindings.map(binding => [binding.file, binding.sha256]));
  let hashDrift = 0;
  for (const binding of stored.evidence.inputBindings) {
    if (binding.sha256 && current.get(binding.file) !== binding.sha256) {
      hashDrift++;
      log('execution-plan.hash-drift', { file: binding.file, stored: binding.sha256, current: current.get(binding.file),
        state: binding.hashState });
    }
  }
  if (stored.policy.hashPolicy.currentHashState === 'frozen' && hashDrift > 0) {
    throw new Error(`${OUTPUT}: frozen input hash drift (${hashDrift})`);
  }
  log('execution-plan.verified', { file: OUTPUT, ...logSummary, hashDrift,
    hashState: stored.policy.hashPolicy.currentHashState, ms: Math.round(performance.now() - started) });
  return plan;
}

export { buildPlan, validatePlan, stablePlan, loadSources };

main(import.meta.url, run);
