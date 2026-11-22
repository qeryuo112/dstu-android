import { readFrozenPlanningFile } from '../publication/frozen-inputs.mjs';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const CONFIG_FILE = 'config/planning/active-plan.json';
export const ACTIVE_PLAN = 'docs/plans/2026-11-16-native-modular-execution-plan.md';
export const FUNCTIONAL_PLAN = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
export const S0_REF = '36511a1ba27ef2db7c45636162575a2b1c6564e8';
export const S2_PROGRESS = 'config/stages/s2-progress.json';
export const OLD_HANDOFF = 'docs/handoff.md';
export const ARCHIVE_HANDOFF = 'docs/archive/handoff-s0.md';
export const OLD_S1_HANDOFF = 'docs/handoff-s1.md';
export const S1_ARCHIVE_HANDOFF = 'docs/archive/handoff-s1.md';
export const CURRENT_HANDOFF = 'docs/handoff-s2.md';
export const REQUIRED_REFERENCE_IDS = [
  'source-lock',
  'execution-inputs',
  'execution-plan',
  's0-gate',
  'final-lock',
  's0-g-main-review',
  'frozen-plan',
  's0-handoff-archive',
  's1-handoff-archive'
];
export const FROZEN_COUNTS = Object.freeze({
  mainUnits: 302,
  stageExits: 10,
  serialQueue: 312,
  cards: 1774,
  scenarios: 3198
});
export const CURRENT_COUNTS = Object.freeze({
  mainUnits: 305,
  stageExits: 10,
  serialQueue: 315,
  inheritedFrozenCards: 1774,
  inheritedFrozenScenarios: 3198,
  newImplementationCards: 3
});
export const ACTIVE_COUNTS = Object.freeze({ units: 280, stageExits: 9 });

const HASH_RE = /^[0-9a-f]{64}$/u;
const UNIT_RE = /^S([0-9])\.(\d{2})$/u;
const UNIT_RANGE_RE = /^(S([0-9])\.(\d{2}))–(S([0-9])\.(\d{2}))$/u;
const EXIT_RE = /^S([0-9])\.G$/u;
const EXIT_LINE_RE = /^\s*\*\*阶段出口 (S\d+\.G)\*\*/u;
const PLACEHOLDER_RE = /^(?:tbd|todo|placeholder|unknown|n\/a|—|-|无|待定)$/iu;
const RULES_FILE = 'docs/rules/rules.txt';
const UNIT_CELL_RE = /^S\d+\.\d{2}(?:–S\d+\.\d{2})?$/u;
const DEP_TOKEN_RE = /S\d+\.(?:\d{2}|G)(?:–S\d+\.(?:\d{2}|G))?/gu;
const DEP_RANGE_RE = /^(S([0-9])\.(\d{2}))–(S([0-9])\.(\d{2}))$/u;

const fail = message => {
  throw new Error(message);
};

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

export function log(event, details) {
  process.stderr.write(`${JSON.stringify({ time: new Date().toISOString(), event, ...details })}\n`);
}

const normalizePath = file => String(file ?? '').replaceAll('\\', '/').replace(/^\.\//u, '');
const isRulesPath = file => {
  const path = normalizePath(file);
  return path === RULES_FILE || path.endsWith(`/${RULES_FILE}`);
};
const isBlank = value => {
  const text = String(value ?? '').trim();
  return text.length === 0 || PLACEHOLDER_RE.test(text);
};
const asText = value => Buffer.isBuffer(value) ? value.toString('utf8') : String(value ?? '');

function splitRow(line) {
  const text = line.trim();
  if (!text.startsWith('|') || !text.endsWith('|')) fail(`invalid table row: ${text.slice(0, 80)}`);
  return text.slice(1, -1).split('|').map(cell => cell.trim());
}

function isHeader(cells) {
  return cells.length === 4
    && cells[0].includes('单元')
    && cells[1].includes('后端') && cells[1].includes('归属')
    && cells[2].includes('前端') && cells[2].includes('归属')
    && cells[3].includes('验收');
}

function isDependencyHeader(cells) {
  return cells.length === 5
    && cells[0].includes('编号')
    && cells[1].includes('执行单元')
    && cells[2].includes('前置依赖')
    && cells[3].includes('交付物')
    && cells[4].includes('验收条件');
}

const isSeparator = cells => cells.every(cell => /^:?-{3,}:?$/u.test(cell));

function expandUnitCell(cell, line, { allowS0 = false } = {}) {
  const text = String(cell ?? '').trim();
  if (!allowS0 && /^S0(?:\.|$)/u.test(text)) fail(`line ${line}: S0 rows are not allowed`);
  if (!text.includes('–')) {
    if (!UNIT_RE.test(text)) fail(`line ${line}: invalid unit id ${text}`);
    return [text];
  }
  const match = UNIT_RANGE_RE.exec(text);
  if (!match) fail(`line ${line}: invalid range ${text}`);
  const [, , startStage, startNumber, , endStage, endNumber] = match;
  if (startStage !== endStage) fail(`line ${line}: cross-stage range ${text}`);
  if (!allowS0 && startStage === '0') fail(`line ${line}: S0 rows are not allowed`);
  if (Number(startNumber) > Number(endNumber)) fail(`line ${line}: reversed range ${text}`);
  const stage = `S${startStage}`;
  const ids = [];
  for (let value = Number(startNumber); value <= Number(endNumber); value++) {
    ids.push(`${stage}.${String(value).padStart(2, '0')}`);
  }
  return ids;
}

function validateOwner(value, side, line) {
  if (isBlank(value)) fail(`line ${line}: ${side} owner is empty`);
  if (side !== 'frontend' || !value.includes('无页面')) return;
  const reason = value.slice(value.indexOf('无页面') + '无页面'.length)
    .replace(/^[\s（(：:，,；;–-]+/u, '')
    .replace(/[\s）)]+$/u, '');
  if (!reason) fail(`line ${line}: frontend 无页面 requires a reason`);
}

function parseResponsibilityRow(cells, line) {
  const [unit, backend, frontend, acceptance] = cells;
  const ids = expandUnitCell(unit, line);
  validateOwner(backend, 'backend', line);
  validateOwner(frontend, 'frontend', line);
  if (isBlank(acceptance)) fail(`line ${line}: acceptance is empty`);
  return { unit, ids, backend, frontend, acceptance, line };
}

function parseExitLines(lines) {
  const exits = [];
  for (let index = 0; index < lines.length; index++) {
    const exit = EXIT_LINE_RE.exec(lines[index]);
    if (exit) exits.push({ id: exit[1], line: index + 1 });
  }
  return exits;
}

export function parsePlanDocument(text) {
  const lines = asText(text).split(/\r?\n/u);
  const hasHeader = lines.some(line => {
    const value = line.trim();
    return value.startsWith('|')
      && value.includes('单元')
      && value.includes('后端归属')
      && value.includes('前端归属')
      && value.includes('验收');
  });
  const rows = [];
  let active = false;
  let headers = 0;
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    const lineNumber = index + 1;
    if (EXIT_LINE_RE.test(line)) {
      active = false;
      continue;
    }
    if (!line.trim().startsWith('|')) {
      active = false;
      continue;
    }
    const cells = splitRow(line);
    if (isHeader(cells)) {
      active = true;
      headers++;
      continue;
    }
    if (headers === 0 && !hasHeader) {
      if (cells.length !== 4) {
        if (UNIT_CELL_RE.test(cells[0])) fail(`line ${lineNumber}: responsibility row must have 4 columns`);
        continue;
      }
      if (!UNIT_CELL_RE.test(cells[0])) continue;
      rows.push(parseResponsibilityRow(cells, lineNumber));
      continue;
    }
    if (!active || isSeparator(cells)) continue;
    if (cells.length !== 4) fail(`line ${lineNumber}: responsibility row must have 4 columns`);
    rows.push(parseResponsibilityRow(cells, lineNumber));
  }
  if (headers === 0 && rows.length === 0) fail('missing responsibility table header or rows');
  return { rows, units: rows.flatMap(row => row.ids), exits: parseExitLines(lines) };
}

function expandDependencyToken(token, line) {
  const range = DEP_RANGE_RE.exec(token);
  if (range) {
    const [, , startStage, startNumber, , endStage, endNumber] = range;
    if (startStage !== endStage) fail(`line ${line}: cross-stage dependency range ${token}`);
    if (Number(startNumber) > Number(endNumber)) fail(`line ${line}: reversed dependency range ${token}`);
    const stage = `S${startStage}`;
    const ids = [];
    for (let value = Number(startNumber); value <= Number(endNumber); value++) {
      ids.push(`${stage}.${String(value).padStart(2, '0')}`);
    }
    return ids;
  }
  if (!UNIT_RE.test(token) && !EXIT_RE.test(token)) fail(`line ${line}: invalid dependency ${token}`);
  return [token];
}

function parseDependencyCell(cell, line) {
  const text = String(cell ?? '').trim();
  if (text.length === 0 || /^[—–-]$/u.test(text)) return [];
  const tokens = [...text.matchAll(DEP_TOKEN_RE)].map(match => match[0]);
  const residue = text.replace(DEP_TOKEN_RE, '').replace(/[、,，;；\s]/gu, '');
  if (residue.length > 0) fail(`line ${line}: invalid dependency text ${text}`);
  const dependencies = [];
  for (const token of tokens) {
    for (const dependency of expandDependencyToken(token, line)) {
      if (dependencies.includes(dependency)) fail(`line ${line}: duplicate dependency ${dependency}`);
      dependencies.push(dependency);
    }
  }
  return dependencies;
}

export function parseDependencyPlan(text) {
  const lines = asText(text).split(/\r?\n/u);
  const rows = [];
  let active = false;
  let headers = 0;
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    const lineNumber = index + 1;
    if (!line.trim().startsWith('|')) {
      active = false;
      continue;
    }
    const cells = splitRow(line);
    if (isDependencyHeader(cells)) {
      active = true;
      headers++;
      continue;
    }
    if (!active || isSeparator(cells)) continue;
    if (cells.length !== 5) {
      if (UNIT_RE.test(cells[0])) fail(`line ${lineNumber}: dependency row must have 5 columns`);
      continue;
    }
    if (!UNIT_RE.test(cells[0])) continue;
    const ids = expandUnitCell(cells[0], lineNumber, { allowS0: true });
    if (ids.length !== 1) fail(`line ${lineNumber}: dependency row must name one unit`);
    const [id] = ids;
    const [name, dependencyCell, deliverable, acceptance] = cells.slice(1);
    if (isBlank(name) || isBlank(deliverable) || isBlank(acceptance)) {
      fail(`line ${lineNumber}: dependency row ${id} has an empty field`);
    }
    rows.push({
      id,
      name,
      dependencies: parseDependencyCell(dependencyCell, lineNumber),
      deliverable,
      acceptance,
      line: lineNumber
    });
  }
  if (headers === 0 || rows.length === 0) fail('missing dependency table header or rows');
  const byId = new Map();
  let previous = -1;
  for (const row of rows) {
    if (byId.has(row.id)) fail(`duplicate functional unit ${row.id} at line ${row.line}`);
    const order = unitOrder(row.id);
    if (order <= previous) fail(`out-of-order functional unit ${row.id} at line ${row.line}`);
    previous = order;
    byId.set(row.id, row);
  }
  return { rows, units: rows.map(row => row.id), byId, exits: parseExitLines(lines) };
}

function unitOrder(id) {
  const match = UNIT_RE.exec(id);
  if (match) return Number(match[1]) * 100 + Number(match[2]);
  const exit = EXIT_RE.exec(id);
  if (exit) return Number(exit[1]) * 100 + 99;
  fail(`invalid unit id ${id}`);
}

function verifyExitLines(plan, label, expectedCount) {
  const seen = new Set();
  let previous = -1;
  for (const exit of plan.exits) {
    if (seen.has(exit.id)) fail(`${label}: duplicate stage exit ${exit.id} at line ${exit.line}`);
    const order = unitOrder(exit.id);
    if (order <= previous) fail(`${label}: out-of-order stage exit ${exit.id} at line ${exit.line}`);
    previous = order;
    seen.add(exit.id);
  }
  if (plan.exits.length !== expectedCount) fail(`${label}: stage exit count ${plan.exits.length} !== ${expectedCount}`);
}

function verifyDependencyOrder(plan, label) {
  const exitLines = new Map(plan.exits.map(exit => [exit.id, exit.line]));
  const seenUnits = new Set();
  for (const row of plan.rows) {
    for (const dependency of row.dependencies) {
      if (UNIT_RE.test(dependency)) {
        if (!seenUnits.has(dependency)) fail(`${label} ${row.id}: forward or unknown dependency ${dependency}`);
      } else if (EXIT_RE.test(dependency)) {
        const line = exitLines.get(dependency);
        if (line === undefined || line >= row.line) fail(`${label} ${row.id}: forward or unknown dependency ${dependency}`);
      } else {
        fail(`${label} ${row.id}: invalid dependency ${dependency}`);
      }
    }
    seenUnits.add(row.id);
  }
}

function revisionInfo(metadata) {
  const revision = metadata.revision;
  if (!revision || typeof revision.id !== 'string' || revision.id.trim().length === 0) fail('metadata revision id');
  if (!UNIT_RE.test(revision.startsAt ?? '') || !revision.startsAt.startsWith('S2.')) fail('metadata revision startsAt');
  if (typeof revision.startName !== 'string' || revision.startName.trim().length === 0) fail('metadata revision startName');
  if (!Array.isArray(revision.inserted) || revision.inserted.length !== 3) fail('metadata revision inserted units');
  const inserted = new Map();
  for (const entry of revision.inserted) {
    if (!entry || !UNIT_RE.test(entry.id ?? '') || !entry.id.startsWith('S2.')) fail('metadata revision inserted id');
    if (inserted.has(entry.id)) fail(`metadata revision duplicate inserted unit ${entry.id}`);
    if (!Array.isArray(entry.sourceUnits) || entry.sourceUnits.length === 0
      || entry.sourceUnits.some(id => !UNIT_RE.test(id))) fail(`metadata revision ${entry.id} sourceUnits`);
    if (!Array.isArray(entry.dependencies) || entry.dependencies.some(id => !UNIT_RE.test(id) && !EXIT_RE.test(id))) {
      fail(`metadata revision ${entry.id} dependencies`);
    }
    if (!Array.isArray(entry.requiredTerms) || entry.requiredTerms.length === 0
      || entry.requiredTerms.some(term => typeof term !== 'string' || term.trim().length === 0)) {
      fail(`metadata revision ${entry.id} requiredTerms`);
    }
    if (typeof entry.cardRef !== 'string' || !entry.cardRef.startsWith(`${entry.id}/`)) {
      fail(`metadata revision ${entry.id} cardRef`);
    }
    inserted.set(entry.id, entry);
  }
  if (!inserted.has(revision.startsAt)) fail('metadata revision startsAt is not an inserted unit');
  const shift = revision.shift;
  if (!shift || !UNIT_RE.test(shift.from ?? '') || !UNIT_RE.test(shift.to ?? '')) fail('metadata revision shift');
  const from = UNIT_RE.exec(shift.from);
  const to = UNIT_RE.exec(shift.to);
  if (from[1] !== '2' || to[1] !== '2') fail('metadata revision shift stage');
  const fromNumber = Number(from[2]);
  const toNumber = Number(to[2]);
  if (fromNumber > toNumber) fail('metadata revision shift range');
  if (!Number.isInteger(shift.delta) || shift.delta <= 0) fail('metadata revision shift delta');
  const mapOldToNew = id => {
    const match = UNIT_RE.exec(id);
    if (!match || match[1] !== '2') return id;
    const number = Number(match[2]);
    if (number < fromNumber || number > toNumber) return id;
    return `S2.${String(number + shift.delta).padStart(2, '0')}`;
  };
  const unmapNewToOld = id => {
    const match = UNIT_RE.exec(id);
    if (!match || match[1] !== '2') return id;
    const number = Number(match[2]);
    const start = fromNumber + shift.delta;
    const end = toNumber + shift.delta;
    if (number < start || number > end) return id;
    return `S2.${String(number - shift.delta).padStart(2, '0')}`;
  };
  return { ...revision, inserted, mapOldToNew, unmapNewToOld };
}

function compareDependencySets(actual, expected, label) {
  const actualSet = new Set(actual);
  const expectedSet = new Set(expected);
  const missing = [...expectedSet].filter(id => !actualSet.has(id));
  const extra = [...actualSet].filter(id => !expectedSet.has(id));
  if (missing.length > 0 || extra.length > 0) {
    fail(`${label}: dependency mismatch; missing ${missing.join(',') || 'none'}; extra ${extra.join(',') || 'none'}`);
  }
}

function verifyStageCounts(plan, expected, label) {
  if (!expected || typeof expected !== 'object') fail(`${label}: missing stage counts`);
  const counts = {};
  for (const row of plan.rows) {
    const stage = row.id.slice(0, 2);
    counts[stage] = (counts[stage] ?? 0) + 1;
  }
  for (const [stage, count] of Object.entries(expected)) {
    if (counts[stage] !== count) fail(`${label}: ${stage} unit count ${counts[stage] ?? 0} !== ${count}`);
  }
  for (const stage of Object.keys(counts)) {
    if (!(stage in expected)) fail(`${label}: unexpected stage ${stage}`);
  }
}

function verifyFunctionalPlan(oldText, currentText, metadata) {
  const oldPlan = parseDependencyPlan(oldText);
  const currentPlan = parseDependencyPlan(currentText);
  const oldBytes = Buffer.from(asText(oldText));
  if (!HASH_RE.test(metadata.frozenPlan?.sha256 ?? '')) fail('frozen plan sha256');
  if (sha256(oldBytes) !== metadata.frozenPlan.sha256) fail(`frozen-plan: sha256 drift for ${metadata.frozenPlan.file}`);
  if (metadata.frozenPlan?.gitRef !== S0_REF) fail('frozen plan gitRef');
  if (metadata.functionalPlan?.file !== metadata.frozenPlan.file) fail('functional plan path');
  const currentBytes = Buffer.from(asText(currentText));
  if (!HASH_RE.test(metadata.functionalPlan?.sha256 ?? '')) fail('functional plan sha256 missing');
  if (metadata.functionalPlan?.hashStatus !== 'sealed') fail('functional plan hashStatus is not sealed');
  if (sha256(currentBytes) !== metadata.functionalPlan.sha256) fail('functional-plan: sha256 drift');
  verifyExitLines(oldPlan, 'frozen functional plan', FROZEN_COUNTS.stageExits);
  verifyExitLines(currentPlan, 'functional plan', CURRENT_COUNTS.stageExits);
  verifyDependencyOrder(oldPlan, 'frozen functional plan');
  verifyDependencyOrder(currentPlan, 'functional plan');
  if (oldPlan.units.length !== FROZEN_COUNTS.mainUnits) {
    fail(`frozen functional plan units: ${oldPlan.units.length} !== ${FROZEN_COUNTS.mainUnits}`);
  }
  if (currentPlan.units.length !== CURRENT_COUNTS.mainUnits) {
    fail(`functional plan units: ${currentPlan.units.length} !== ${CURRENT_COUNTS.mainUnits}`);
  }
  if (currentPlan.units.length + currentPlan.exits.length !== CURRENT_COUNTS.serialQueue) {
    fail(`functional plan serial queue: ${currentPlan.units.length + currentPlan.exits.length} !== ${CURRENT_COUNTS.serialQueue}`);
  }
  const revision = revisionInfo(metadata);
  const oldIds = new Set(oldPlan.units);
  for (const entry of revision.inserted.values()) {
    for (const sourceUnit of entry.sourceUnits) {
      if (!oldIds.has(sourceUnit)) fail(`revision ${entry.id}: unknown source unit ${sourceUnit}`);
    }
  }
  const expectedIds = new Set(oldPlan.units.map(revision.mapOldToNew));
  for (const entry of revision.inserted.values()) {
    if (expectedIds.has(entry.id)) fail(`revision inserted unit collides with mapped unit ${entry.id}`);
    expectedIds.add(entry.id);
  }
  if (expectedIds.size !== CURRENT_COUNTS.mainUnits) fail(`revision maps to ${expectedIds.size} units`);
  if (revision.inserted.size !== CURRENT_COUNTS.newImplementationCards) fail('revision new implementation card count');
  for (const row of currentPlan.rows) {
    if (!expectedIds.has(row.id)) fail(`unexpected functional unit ${row.id}`);
  }
  const refinements = revision.refinements ?? {};
  for (const [id, change] of Object.entries(refinements)) {
    if (!currentPlan.byId.has(id) || revision.inserted.has(id)) fail(`revision refinement unit ${id}`);
    if (!Array.isArray(change.fields) || change.fields.some(field => !['deliverable', 'acceptance'].includes(field))) {
      fail(`revision refinement ${id}: fields`);
    }
    if (!Array.isArray(change.additionalDependencies) || change.additionalDependencies.some(dependency =>
      (!UNIT_RE.test(dependency) && !EXIT_RE.test(dependency)) || unitOrder(dependency) >= unitOrder(id))) {
      fail(`revision refinement ${id}: dependencies`);
    }
  }
  for (const oldRow of oldPlan.rows) {
    const newId = revision.mapOldToNew(oldRow.id);
    const currentRow = currentPlan.byId.get(newId);
    if (!currentRow) fail(`missing shifted functional unit ${newId} from ${oldRow.id}`);
    for (const field of ['name', 'deliverable', 'acceptance']) {
      if (currentRow[field] !== oldRow[field] && !refinements[newId]?.fields.includes(field)) {
        fail(`functional unit ${newId}: ${field} changed from frozen plan without a registered refinement`);
      }
    }
    compareDependencySets(
      currentRow.dependencies,
      [...oldRow.dependencies.map(revision.mapOldToNew), ...(refinements[newId]?.additionalDependencies ?? [])],
      `functional unit ${newId}`
    );
  }
  for (const entry of revision.inserted.values()) {
    const row = currentPlan.byId.get(entry.id);
    if (!row) fail(`missing inserted functional unit ${entry.id}`);
    const text = `${row.name} ${row.deliverable} ${row.acceptance}`.toLowerCase();
    for (const term of entry.requiredTerms) {
      if (!text.includes(term.toLowerCase())) fail(`functional unit ${entry.id}: missing required term ${term}`);
    }
    compareDependencySets(row.dependencies, entry.dependencies, `functional unit ${entry.id}`);
  }
  verifyStageCounts(currentPlan, metadata.expected?.stageCounts, 'functional plan');
  return { oldPlan, currentPlan, revision };
}

function activeCoverage(currentPlan, metadata) {
  const units = currentPlan.rows.filter(row => !row.id.startsWith('S0.')).map(row => row.id);
  const exits = currentPlan.exits.filter(exit => !exit.id.startsWith('S0.')).map(exit => exit.id);
  if (units.length !== ACTIVE_COUNTS.units) fail(`functional active units: ${units.length} !== ${ACTIVE_COUNTS.units}`);
  if (metadata.expected?.activeUnits !== ACTIVE_COUNTS.units) fail('metadata active unit count');
  if (exits.length !== ACTIVE_COUNTS.stageExits) fail(`functional active exits: ${exits.length} !== ${ACTIVE_COUNTS.stageExits}`);
  if (metadata.expected?.activeStageExits !== ACTIVE_COUNTS.stageExits) fail('metadata active exit count');
  const stages = [...new Set(units.map(id => id.slice(0, 2)))];
  if (stages.join(',') !== metadata.expected?.stages?.join(',')) fail('metadata active stages');
  if (units[0] !== metadata.expected?.firstUnit || units.at(-1) !== metadata.expected?.lastUnit) {
    fail('metadata first/last active unit');
  }
  return { units, exits };
}

function verifyCoverage(parsed, expected, metadata) {
  const expectedSet = new Set(expected.units);
  const seen = new Set();
  let previous = 0;
  for (const row of parsed.rows) {
    for (const id of row.ids) {
      if (!expectedSet.has(id)) fail(`unknown active unit ${id} at line ${row.line}`);
      if (seen.has(id)) fail(`duplicate active unit ${id} at line ${row.line}`);
      const order = unitOrder(id);
      if (order <= previous) fail(`out-of-order active unit ${id} at line ${row.line}`);
      previous = order;
      seen.add(id);
    }
  }
  const missing = expected.units.filter(id => !seen.has(id));
  if (missing.length > 0) fail(`missing active units: ${missing.join(', ')}`);
  if (parsed.units.length !== metadata.expected.activeUnits) fail('active unit row count');
  if (parsed.units[0] !== metadata.expected.firstUnit || parsed.units.at(-1) !== metadata.expected.lastUnit) {
    fail('active plan first/last unit');
  }
}

function verifyExits(exits, expected, metadata) {
  const expectedSet = new Set(expected.exits);
  const seen = new Set();
  let previous = 0;
  for (const exit of exits) {
    if (!expectedSet.has(exit.id)) fail(`unknown stage exit ${exit.id} at line ${exit.line}`);
    if (seen.has(exit.id)) fail(`duplicate stage exit ${exit.id} at line ${exit.line}`);
    const order = unitOrder(exit.id);
    if (order <= previous) fail(`out-of-order stage exit ${exit.id} at line ${exit.line}`);
    previous = order;
    seen.add(exit.id);
  }
  const missing = expected.exits.filter(id => !seen.has(id));
  if (missing.length > 0) fail(`missing stage exits: ${missing.join(', ')}`);
  if (exits.length !== metadata.expected.activeStageExits) fail('active stage exit count');
}

function verifyGatePlacement(parsed) {
  for (const exit of parsed.exits) {
    const stage = exit.id.slice(0, 2);
    const rows = parsed.rows.filter(row => row.ids[0].startsWith(`${stage}.`));
    if (rows.length === 0) fail(`${exit.id}: no stage units`);
    if (exit.line <= rows.at(-1).line) fail(`${exit.id}: exit must follow its stage units`);
    const nextRow = parsed.rows.find(row => unitOrder(row.ids[0]) > unitOrder(rows.at(-1).ids.at(-1)));
    if (nextRow && exit.line >= nextRow.line) fail(`${exit.id}: exit must precede the next stage`);
  }
}

function referenceMap(metadata) {
  if (!Array.isArray(metadata.references)) fail('metadata references');
  const refs = new Map();
  for (const ref of metadata.references) {
    if (!ref || typeof ref.id !== 'string' || typeof ref.file !== 'string') fail('invalid reference');
    if (refs.has(ref.id)) fail(`duplicate reference id ${ref.id}`);
    refs.set(ref.id, ref);
  }
  for (const id of REQUIRED_REFERENCE_IDS) {
    if (!refs.has(id)) fail(`missing reference ${id}`);
  }
  return refs;
}

function readRequired(readFile, file, label) {
  try {
    return readFile(file);
  } catch (error) {
    fail(`${label}: cannot read ${file} (${error.message})`);
  }
}

function readRequiredGit(readGitFile, gitRef, file, label) {
  try {
    return readGitFile(gitRef, file);
  } catch (error) {
    fail(`${label}: cannot read ${gitRef}:${file} (${error.message})`);
  }
}

function verifyReference(ref, readFile, readGitFile) {
  if (!HASH_RE.test(ref.sha256 ?? '')) fail(`reference ${ref.id}: invalid sha256`);
  if (isRulesPath(ref.file)) fail('rules must not be hash-bound');
  const bytes = ref.gitRef
    ? readRequiredGit(readGitFile, ref.gitRef, ref.file, `reference ${ref.id}`)
    : readRequired(readFile, ref.file, `reference ${ref.id}`);
  const actual = sha256(Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes));
  if (actual !== ref.sha256) fail(`reference ${ref.id}: sha256 drift for ${ref.file}`);
}

function verifyFrozenReferences(metadata, readFile, readGitFile) {
  const refs = referenceMap(metadata);
  for (const ref of refs.values()) verifyReference(ref, readFile, readGitFile);
  const links = [
    ['frozenPlan', 'frozen-plan'],
    ['frozenQueue', 'execution-plan'],
    ['signedReceipt', 's0-g-main-review']
  ];
  for (const [field, id] of links) {
    const value = metadata[field];
    const ref = refs.get(id);
    if (value?.file !== ref.file || value?.sha256 !== ref.sha256) fail(`metadata ${field} does not match ${id}`);
    if (value?.gitRef !== ref.gitRef) fail(`metadata ${field} gitRef does not match ${id}`);
  }
  return refs;
}

function verifyFrozenQueueIdentity(queue, oldPlan, revision) {
  const queueUnits = (queue.units ?? []).map(unit => unit.id);
  const queueExits = (queue.stageExits ?? []).map(exit => exit.id);
  if (JSON.stringify(queueUnits) !== JSON.stringify(oldPlan.units)) fail('frozen queue unit ids changed');
  if (JSON.stringify(queueExits) !== JSON.stringify(oldPlan.exits.map(exit => exit.id))) {
    fail('frozen queue stage exit ids changed');
  }
  const frozenCards = new Set((queue.cards ?? []).map(card => card.id));
  const currentCards = new Set();
  for (const entry of revision.inserted.values()) {
    if (frozenCards.has(entry.cardRef)) fail(`revision ${entry.id} reuses frozen card ${entry.cardRef}`);
    if (currentCards.has(entry.cardRef)) fail(`revision duplicate current card ${entry.cardRef}`);
    currentCards.add(entry.cardRef);
  }
}

function verifyQueue(metadata, queue) {
  const expected = metadata.expected?.queue ?? {};
  const queueCounts = {
    mainUnits: queue.checks?.mainUnits,
    stageExits: queue.checks?.stageExits,
    serialQueue: queue.checks?.serialQueue,
    cards: queue.checks?.totalCards,
    scenarios: queue.checks?.scenarios
  };
  for (const [key, value] of Object.entries(FROZEN_COUNTS)) {
    if (expected[key] !== value) fail(`metadata queue ${key}: ${expected[key]} !== ${value}`);
    if (queueCounts[key] !== value) fail(`frozen queue ${key}: ${queueCounts[key]} !== ${value}`);
  }
  const arrays = {
    mainUnits: queue.units?.length,
    stageExits: queue.stageExits?.length,
    serialQueue: queue.queue?.length,
    cards: queue.cards?.length,
    scenarios: queue.scenarios?.length
  };
  for (const [key, value] of Object.entries(FROZEN_COUNTS)) {
    if (arrays[key] !== value) fail(`frozen queue array ${key}: ${arrays[key]} !== ${value}`);
  }
  if (queue.plan?.file !== metadata.frozenPlan.file || queue.plan?.sha256 !== metadata.frozenPlan.sha256) {
    fail('frozen queue plan binding');
  }
  if (queue.plan?.mainUnits !== FROZEN_COUNTS.mainUnits || queue.plan?.stageExits !== FROZEN_COUNTS.stageExits) {
    fail('frozen queue plan counts');
  }
}

function verifyCurrentCounts(metadata, currentPlan) {
  const expected = metadata.expected?.currentQueue ?? {};
  for (const [key, value] of Object.entries(CURRENT_COUNTS)) {
    if (expected[key] !== value) fail(`metadata current queue ${key}: ${expected[key]} !== ${value}`);
  }
  if (metadata.expected?.functionalUnits !== CURRENT_COUNTS.mainUnits) fail('metadata functional unit count');
  if (metadata.expected?.functionalStageExits !== CURRENT_COUNTS.stageExits) fail('metadata functional exit count');
  if (metadata.expected?.functionalSerialQueue !== CURRENT_COUNTS.serialQueue) fail('metadata functional serial queue');
  if (currentPlan.units.length !== CURRENT_COUNTS.mainUnits) fail('current functional unit count');
  if (currentPlan.exits.length !== CURRENT_COUNTS.stageExits) fail('current functional exit count');
  if (currentPlan.units.length + currentPlan.exits.length !== CURRENT_COUNTS.serialQueue) {
    fail('current functional serial queue');
  }
}

function verifyReceipt(metadata, finalLock, review, refs) {
  const plan = finalLock.executionPlan;
  if (!plan?.present) fail('final-lock execution plan missing');
  if (plan.artifact !== metadata.frozenQueue.file) fail('final-lock execution plan artifact');
  const finalCounts = {
    mainUnits: plan.units,
    stageExits: plan.stageExits,
    serialQueue: plan.serialQueue,
    cards: plan.cards
  };
  for (const [key, actual] of Object.entries(finalCounts)) {
    if (actual !== FROZEN_COUNTS[key]) fail(`final-lock ${key}: ${actual} !== ${FROZEN_COUNTS[key]}`);
  }
  if (plan.freezeReady !== true || plan.unresolvedMembers !== 0 || plan.unresolved !== 0) fail('final-lock queue not frozen');
  if (review.decision !== 'accepted') fail('signed receipt is not accepted');
  if (review.units?.count !== 25 || review.units?.allEvidence !== true) fail('signed receipt units');
  if (review.m0?.complete !== true || review.m0?.signedOff !== true) fail('signed receipt m0');
  const lock = refs.get('final-lock');
  const gate = refs.get('s0-gate');
  if (review.finalLock?.file !== lock.file || review.finalLock?.sha256 !== lock.sha256) fail('signed receipt final-lock binding');
  if (review.policy?.file !== gate.file || review.policy?.sha256 !== gate.sha256) fail('signed receipt gate binding');
}

function verifyHandoffs(metadata, refs, readFile, exists) {
  const archives = [
    ['archive', 's0-handoff-archive', ARCHIVE_HANDOFF, OLD_HANDOFF],
    ['s1Archive', 's1-handoff-archive', S1_ARCHIVE_HANDOFF, OLD_S1_HANDOFF]
  ];
  for (const [key, id, file, source] of archives) {
    const archive = metadata.handoffs?.[key];
    const ref = refs.get(id);
    if (archive?.file !== file || archive?.source !== source) fail(`${id}: handoff archive metadata`);
    if (file !== ref.file || archive.sha256 !== ref.sha256) fail(`${id}: handoff archive does not match frozen reference`);
    if (exists(source)) fail(`legacy handoff still exists: ${source}`);
    if (!exists(file)) fail(`handoff archive missing: ${file}`);
    const { length } = Buffer.from(readRequired(readFile, file, 'handoff archive'));
    if (length !== archive.bytes) fail(`${id}: handoff archive bytes: ${length} !== ${archive.bytes}`);
  }
  const current = metadata.handoffs?.current;
  if (current?.file !== CURRENT_HANDOFF) fail('current handoff metadata');
  if (!exists(current.file)) fail(`current handoff missing: ${current.file}`);
  const currentBytes = Buffer.from(readRequired(readFile, current.file, 'current handoff'));
  if (currentBytes.length === 0) fail('current handoff is empty');
}

function verifyProgress(metadata, progress, currentPlan) {
  const expected = metadata.s2Receipt;
  const revision = revisionInfo(metadata);
  if (expected?.file !== S2_PROGRESS) fail('s2 receipt metadata file');
  if (expected?.planRevision !== revision.id) fail('s2 receipt metadata planRevision');
  if (expected?.currentPlan !== CONFIG_FILE) fail('s2 receipt metadata currentPlan');
  if (progress.planRevision !== expected.planRevision) {
    fail(`s2 receipt planRevision: ${progress.planRevision} !== ${expected.planRevision}`);
  }
  if (progress.currentPlan !== expected.currentPlan) fail('s2 receipt currentPlan');
  const units = Array.isArray(progress.units) ? progress.units : [];
  const stageUnits = currentPlan.rows.filter(row => row.id.startsWith('S2.'));
  // Keep the accepted prefix, but do not freeze future progress at the revision's starting unit.
  if (units.length < 15 || units.length > stageUnits.length) fail('s2 receipt accepted unit count');
  for (let index = 0; index < units.length; index++) {
    const id = stageUnits[index].id;
    if (units[index]?.id !== id || units[index]?.status !== 'accepted') fail(`s2 receipt accepted unit changed: ${id}`);
    const repair = revision.inserted.get(id);
    if (repair && units[index].card !== repair.cardRef) fail(`s2 receipt ${id}: requires independent remediation card`);
  }
  if (progress.s2GateAccepted === true && units.length !== stageUnits.length) fail('s2 receipt premature stage gate');
  const nextRow = stageUnits[units.length];
  const next = nextRow?.id ?? (progress.s2GateAccepted ? 'S3.01' : 'S2.G');
  if (progress.next !== next) fail(`s2 receipt next: ${progress.next} !== ${next}`);
  const nextName = nextRow?.name ?? (progress.s2GateAccepted ? currentPlan.byId.get('S3.01').name : 'S2 阶段出口');
  if (progress.nextName !== nextName) fail(`s2 receipt nextName: ${progress.nextName} !== ${nextName}`);
  const accepted = new Set(units.map(unit => unit.id));
  if (!Array.isArray(progress.plannedRemediations) || progress.plannedRemediations.length !== revision.inserted.size) {
    fail('s2 receipt plannedRemediations');
  }
  [...revision.inserted.values()].forEach((entry, index) => {
    const actual = progress.plannedRemediations[index];
    const statuses = accepted.has(entry.id) ? ['accepted'] : ['pending', 'awaiting-device'];
    if (actual?.id !== entry.id || !statuses.includes(actual.status) || JSON.stringify(actual.dependsOn) !== JSON.stringify(entry.dependencies)) {
      fail(`s2 receipt plannedRemediations[${index}]`);
    }
  });
}

export function verifyPlan(input) {
  const readFile = input.readFile ?? (file => readFileSync(resolve(ROOT, file)));
  const readGitFile = input.readGitFile ?? readFrozenPlanningFile;
  const exists = input.exists ?? (file => existsSync(resolve(ROOT, file)));
  const { metadata, documentText, queue, finalLock, review, progress } = input;
  if (metadata.formatVersion !== 1 || metadata.format !== 'dstu-active-plan/v1') fail('metadata format');
  if (metadata.activePlan?.file !== ACTIVE_PLAN) fail('active plan path');
  if (metadata.activePlan?.frozen !== false) fail('active plan must not be frozen');
  if (metadata.frozenPlan?.file !== FUNCTIONAL_PLAN) fail('frozen functional plan path');
  if (metadata.functionalPlan?.file !== FUNCTIONAL_PLAN) fail('live functional plan path');
  const oldPlanText = input.oldPlanText ?? readRequiredGit(
    readGitFile,
    metadata.frozenPlan.gitRef,
    metadata.frozenPlan.file,
    'frozen functional plan'
  );
  const functionalPlanText = input.functionalPlanText ?? readRequired(
    readFile,
    metadata.functionalPlan.file,
    'functional plan'
  );
  const functional = verifyFunctionalPlan(oldPlanText, functionalPlanText, metadata);
  const expected = activeCoverage(functional.currentPlan, metadata);
  const parsed = parsePlanDocument(documentText);
  verifyCoverage(parsed, expected, metadata);
  verifyExits(parsed.exits, expected, metadata);
  verifyGatePlacement(parsed);
  const refs = verifyFrozenReferences(metadata, readFile, readGitFile);
  verifyQueue(metadata, queue);
  verifyFrozenQueueIdentity(queue, functional.oldPlan, functional.revision);
  verifyCurrentCounts(metadata, functional.currentPlan);
  verifyReceipt(metadata, finalLock, review, refs);
  verifyHandoffs(metadata, refs, readFile, exists);
  verifyProgress(metadata, progress, functional.currentPlan);
  return {
    activePlan: metadata.activePlan.file,
    documentSha256: sha256(Buffer.from(asText(documentText))),
    functionalPlanSha256: sha256(Buffer.from(asText(functionalPlanText))),
    activeUnits: parsed.units.length,
    activeStageExits: parsed.exits.length,
    functionalUnits: functional.currentPlan.units.length,
    functionalStageExits: functional.currentPlan.exits.length,
    functionalSerialQueue: functional.currentPlan.units.length + functional.currentPlan.exits.length,
    revision: metadata.revision.id,
    next: progress.next,
    references: refs.size,
    frozenQueue: FROZEN_COUNTS,
    currentQueue: CURRENT_COUNTS,
    scope: 'document-reference-and-coverage-only'
  };
}

export function verifyAuthorizedPlanRevision(input) {
  const { metadata, queue, progress } = input;
  const oldBytes = input.oldBytes;
  const liveBytes = input.liveBytes;
  if (oldBytes == null || liveBytes == null) fail('authorized plan revision bytes');
  const functional = verifyFunctionalPlan(oldBytes, liveBytes, metadata);
  verifyQueue(metadata, queue);
  verifyFrozenQueueIdentity(queue, functional.oldPlan, functional.revision);
  verifyCurrentCounts(metadata, functional.currentPlan);
  verifyProgress(metadata, progress, functional.currentPlan);
  return {
    functionalPlanSha256: sha256(Buffer.from(asText(liveBytes))),
    functionalUnits: functional.currentPlan.units.length,
    functionalStageExits: functional.currentPlan.exits.length,
    functionalSerialQueue: functional.currentPlan.units.length + functional.currentPlan.exits.length,
    revision: metadata.revision.id,
    next: progress.next
  };
}

export function queryCurrentQueue(id, input) {
  if (!UNIT_RE.test(id ?? '')) fail(`invalid query unit ${id}`);
  const metadata = input.metadata;
  const queue = input.queue;
  const oldPlanText = input.oldPlanText;
  const functionalPlanText = input.functionalPlanText;
  if (oldPlanText == null || functionalPlanText == null) fail('query requires oldPlanText and functionalPlanText');
  const functional = verifyFunctionalPlan(oldPlanText, functionalPlanText, metadata);
  verifyQueue(metadata, queue);
  verifyFrozenQueueIdentity(queue, functional.oldPlan, functional.revision);
  const row = functional.currentPlan.byId.get(id);
  if (!row) fail(`unknown current queue unit ${id}`);
  const inserted = functional.revision.inserted.get(id);
  const sourceUnits = inserted ? inserted.sourceUnits : [functional.revision.unmapNewToOld(id)];
  const sourceCardRefs = [];
  const sourceScenarioRefs = [];
  for (const sourceUnit of sourceUnits) {
    const unit = queue.units?.find(candidate => candidate.id === sourceUnit);
    if (!unit) fail(`current queue ${id}: missing source unit ${sourceUnit}`);
    for (const card of unit.cardIds ?? []) if (!sourceCardRefs.includes(card)) sourceCardRefs.push(card);
    for (const scenario of queue.scenarios ?? []) {
      if (scenario.unit === sourceUnit && !sourceScenarioRefs.includes(scenario.id)) sourceScenarioRefs.push(scenario.id);
    }
  }
  const currentCardRefs = inserted ? [inserted.cardRef] : sourceCardRefs.map(card => {
    const prefix = `${sourceUnits[0]}/`;
    if (!card.startsWith(prefix)) fail(`current queue ${id}: unexpected historical card ${card}`);
    return `${id}/${card.slice(prefix.length)}`;
  });
  const previous = functional.currentPlan.rows[functional.currentPlan.rows.indexOf(row) - 1];
  const previousStage = Number(UNIT_RE.exec(id)[1]) - 1;
  const inherited = [...row.dependencies, ...(previous ? [previous.id] : []), ...(previousStage >= 0 ? [`S${previousStage}.G`] : [])];
  return {
    id,
    name: row.name,
    dependencies: [...new Set(inherited)],
    declaredDependencies: row.dependencies,
    currentCardRefs,
    sourceUnits,
    sourceCardRefs,
    sourceScenarioRefs
  };
}

export function loadContext() {
  const cache = new Map();
  const gitCache = new Map();
  const readFile = file => {
    if (!cache.has(file)) cache.set(file, readFileSync(resolve(ROOT, file)));
    return cache.get(file);
  };
  const readGitFile = (gitRef, file) => {
    const key = `${gitRef}:${file}`;
    if (!gitCache.has(key)) {
      gitCache.set(key, readFrozenPlanningFile(gitRef, file));
    }
    return gitCache.get(key);
  };
  const metadata = JSON.parse(readFile(CONFIG_FILE).toString('utf8'));
  const refs = new Map(metadata.references.map(ref => [ref.id, ref]));
  const functionalPlanText = readFile(metadata.functionalPlan.file).toString('utf8');
  const oldPlanText = readGitFile(metadata.frozenPlan.gitRef, metadata.frozenPlan.file).toString('utf8');
  const progress = JSON.parse(readFile(metadata.s2Receipt.file).toString('utf8'));
  return {
    metadata,
    documentText: readFile(metadata.activePlan.file).toString('utf8'),
    functionalPlanText,
    oldPlanText,
    queue: JSON.parse(readFile(metadata.frozenQueue.file).toString('utf8')),
    finalLock: JSON.parse(readFile(refs.get('final-lock').file).toString('utf8')),
    review: JSON.parse(readFile(refs.get('s0-g-main-review').file).toString('utf8')),
    progress,
    readFile,
    readGitFile,
    exists: file => existsSync(resolve(ROOT, file))
  };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    const queryIndex = process.argv.indexOf('--query');
    if (queryIndex !== -1) {
      const id = process.argv[queryIndex + 1];
      if (!id) fail('usage: node tools/planning/verify-plan.mjs --query S2.16');
      const context = loadContext();
      verifyPlan(context);
      process.stdout.write(`${JSON.stringify(queryCurrentQueue(id, context), null, 2)}\n`);
    } else {
      log('planning.verified', verifyPlan(loadContext()));
    }
  } catch (error) {
    log('planning.failed', { message: error.message, stack: error.stack });
    process.exitCode = 1;
  }
}
