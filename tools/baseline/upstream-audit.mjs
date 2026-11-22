import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { difference, readJson, root, sha256 } from './lib.mjs';

const INPUT_FILE = 'config/upstream-impact-inputs.json';
const ARTIFACT_FILE = 'config/upstream-impact.json';
const TOOL_FILE = 'tools/baseline/upstream-audit.mjs';
const LOG_FILE = 'logs/s0-01-upstream-audit.log';
const config = readJson(INPUT_FILE);
const S0_UNIT = /^S0\.(?:0[2-9]|1[0-9]|2[0-5])$/;

function now() {
  return new Date().toISOString();
}

function log(event, details = {}, path = LOG_FILE) {
  const line = `${JSON.stringify({ time: now(), event, unit: 'S0.01', ...details })}\n`;
  mkdirSync(dirname(resolve(root, path)), { recursive: true });
  appendFileSync(resolve(root, path), line);
  process.stderr.write(line);
}

function git(cwd, args, encoding = 'utf8') {
  return execFileSync('git', args, { cwd, encoding, maxBuffer: 64 * 1024 * 1024 });
}

function gitText(cwd, args) {
  return git(cwd, args).trim();
}

function exists(path) {
  return typeof path === 'string' && existsSync(path);
}

function normalizeRelative(path) {
  return path.replaceAll('\\', '/');
}

function globToRegExp(pattern) {
  let out = '^';
  for (let i = 0; i < pattern.length; i++) {
    const ch = pattern[i];
    if (ch === '*' && pattern[i + 1] === '*') {
      i++;
      if (pattern[i + 1] === '/') {
        i++;
        out += '(?:.*/)?';
      } else out += '.*';
    } else if (ch === '*') out += '[^/]*';
    else if (ch === '?') out += '[^/]';
    else out += ch.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`${out}$`);
}

function matches(path, patterns) {
  return patterns.some(pattern => globToRegExp(pattern).test(path));
}

function resolveGitSource(candidates, label, commit) {
  const paths = [];
  for (const candidate of candidates.filter(Boolean)) {
    const path = isAbsolute(candidate) ? candidate : resolve(root, candidate);
    if (!existsSync(path) || paths.includes(path)) continue;
    paths.push(path);
  }
  assert.ok(paths.length, `${label}: no Git object source configured`);
  const found = paths.find(path => {
    try {
      return gitText(path, ['cat-file', '-t', commit]) === 'commit';
    } catch {
      return false;
    }
  });
  assert.ok(found, `${label}: commit ${commit} is absent from configured Git object sources`);
  return { path: found, candidates: paths };
}

function assertCommitTree(source, commit, tree, label) {
  assert.equal(gitText(source.path, ['cat-file', '-t', commit]), 'commit', `${label}: not a commit`);
  assert.equal(gitText(source.path, ['rev-parse', `${commit}^{tree}`]), tree, `${label}: tree mismatch for ${commit}`);
}

function assertNewCheckout(state, label) {
  assert.ok(existsSync(state.checkout), `${label}: new checkout missing: ${state.checkout}`);
  assert.equal(gitText(state.checkout, ['cat-file', '-t', state.commit]), 'commit', `${label}: checkout lacks target commit`);
  assert.equal(gitText(state.checkout, ['rev-parse', `${state.commit}^{tree}`]), state.tree, `${label}: target tree mismatch`);
  const head = gitText(state.checkout, ['rev-parse', 'HEAD']);
  if (state.requireCheckoutHead) assert.equal(head, state.commit, `${label}: checkout HEAD mismatch`);
  const dirty = gitText(state.checkout, ['status', '--porcelain=v1', '--untracked-files=no']);
  assert.equal(dirty, '', `${label}: tracked checkout is dirty`);
  return { checkout: state.checkout, head, targetCommitContained: true, targetTree: state.tree, dirty: false };
}

function parseRawDiff(raw) {
  const fields = raw.split('\0');
  if (fields.at(-1) === '') fields.pop();
  const entries = [];
  for (let i = 0; i < fields.length;) {
    const header = fields[i];
    const firstPath = fields[i + 1];
    assert.ok(firstPath !== undefined, `raw diff header without path: ${header}`);
    i += 2;
    const match = /^:(\d{6}) (\d{6}) ([0-9a-f]{40}) ([0-9a-f]{40}) ([A-Z])(\d*)$/.exec(header);
    assert.ok(match, `unexpected raw diff header: ${header}`);
    const [, oldMode, newMode, oldBlob, newBlob, status, scoreText] = match;
    let oldPath = status === 'A' ? null : normalizeRelative(firstPath);
    let newPath = status === 'D' ? null : normalizeRelative(firstPath);
    if (status === 'R' || status === 'C') {
      const secondPath = fields[i];
      assert.ok(secondPath !== undefined, `raw rename without destination: ${firstPath}`);
      i += 1;
      oldPath = normalizeRelative(firstPath);
      newPath = normalizeRelative(secondPath);
    }
    entries.push({
      path: newPath ?? oldPath,
      oldPath,
      newPath,
      status,
      score: scoreText === '' ? null : Number(scoreText),
      oldMode,
      newMode,
      oldObjectType: oldMode === '160000' ? 'commit' : oldMode === '120000' ? 'blob:symlink' : 'blob',
      newObjectType: newMode === '160000' ? 'commit' : newMode === '120000' ? 'blob:symlink' : 'blob',
      oldBlob: oldMode === '000000' ? null : oldBlob,
      newBlob: newMode === '000000' ? null : newBlob,
    });
  }
  return entries.sort((a, b) => a.path.localeCompare(b.path, 'en'));
}

function rawDiff(objectSource, newCheckout, oldCommit, newCommit) {
  const args = ['diff', '--raw', '-z', '--no-abbrev', '--find-renames=50%', oldCommit, newCommit];
  const entries = parseRawDiff(git(objectSource, args));
  const checkoutEntries = parseRawDiff(git(newCheckout, args));
  const expectedPaths = checkoutEntries.map(entry => entry.path);
  const objectPaths = entries.map(entry => entry.path);
  assert.deepEqual(objectPaths, expectedPaths, 'object source and new checkout produced different changed paths');
  return entries;
}

function readBaselineDocument(repository, commit, file) {
  return JSON.parse(git(repository, ['show', `${commit}:${file}`]));
}

function baselineUnit(document, file) {
  return config.baseline.unitOverrides[file] ?? document.unit;
}

function unitForSourceIndexEntry(entry) {
  if (typeof entry?.deliveryUnit === 'string') return entry.deliveryUnit;
  if (typeof entry?.primaryUnit === 'string') return entry.primaryUnit;
  if (typeof entry?.android?.deliveryUnit === 'string') return entry.android.deliveryUnit;
  return null;
}

function eachSourceRow(value, callback) {
  if (Array.isArray(value)) {
    for (const item of value) callback(item);
    return;
  }
  if (!value || typeof value !== 'object') return;
  for (const item of Object.values(value)) {
    if (!item || typeof item !== 'object') continue;
    if (typeof item.file === 'string') callback(item);
    else if (Array.isArray(item)) for (const child of item) if (child && typeof child === 'object') callback(child);
  }
}

function sourceIndex(repository) {
  const files = new Map();
  const later = new Map();
  const record = (path, unit) => {
    if (typeof path !== 'string' || path.length === 0 || path.startsWith('node_modules/')) return;
    if (!files.has(path)) files.set(path, new Set());
    if (unit !== null) files.get(path).add(unit);
  };
  const recordLater = (path, unit) => {
    if (typeof path !== 'string' || !/^S[1-9]\d*\.\d+$/.test(unit)) return;
    if (!later.has(path)) later.set(path, new Set());
    later.get(path).add(unit);
  };
  for (const file of config.baseline.files) {
    const document = readBaselineDocument(repository, config.baseline.commit, file);
    const unit = baselineUnit(document, file);
    assert.match(unit, /^S0\.\d{2}$/, `baseline document has no S0 unit: ${file}`);
    const hashes = document.sourceHashes;
    if (Array.isArray(hashes)) {
      for (const item of hashes) record(typeof item === 'string' ? item : item?.file, unit);
    } else if (hashes && typeof hashes === 'object') {
      for (const path of Object.keys(hashes)) record(path, unit);
    }
    eachSourceRow(document.sourceIndex, item => {
      record(item.file, unit);
      for (const owner of [item.deliveryUnit, item.primaryUnit, item.android?.deliveryUnit]) {
        if (typeof owner === 'string' && /^S[1-9]\d*\.\d+$/.test(owner)) recordLater(item.file, owner);
      }
      if (item.owner && document.plugins) {
        const plugin = document.plugins.find(entry => entry.id === item.owner);
        if (plugin) recordLater(item.file, plugin.deliveryUnit);
      }
    });
    eachSourceRow(document.sourceInputs, item => record(item.file, unit));
    for (const key of ['views', 'navigation', 'commands', 'menus', 'debugPanels', 'externalEntries', 'apps', 'sections', 'controls', 'searchDetails', 'slotDeclarations', 'surfaces', 'adopted', 'rejected', 'modules', 'terminalModules', 'fileAdoptions']) {
      for (const item of document[key] ?? []) {
        const path = item?.evidence?.file ?? item?.sourcePath ?? item?.modulePath ?? item?.sample;
        record(path, unit);
        const owner = unitForSourceIndexEntry(item);
        if (typeof owner === 'string' && /^S[1-9]\d*\.\d+$/.test(owner)) recordLater(path, owner);
      }
    }
  }
  const domains = readBaselineDocument(repository, config.baseline.commit, 'config/baseline/domains.json');
  eachSourceRow(domains.sourceIndex, item => {
    record(item.file, null);
    for (const plugin of domains.plugins ?? []) {
      if (item.owner === plugin.id) recordLater(item.file, plugin.deliveryUnit);
    }
  });
  for (const key of ['services', 'commands', 'tools', 'backgroundTasks']) {
    for (const item of domains[key] ?? []) {
      const plugin = domains.plugins?.find(entry => entry.id === item.plugin || entry.id === item.pluginId || entry.id === item.owner);
      const path = item.file ?? item.evidence?.file;
      if (plugin) recordLater(path, plugin.deliveryUnit);
      if (typeof item.deliveryUnit === 'string') recordLater(path, item.deliveryUnit);
    }
  }
  for (const key of ['adopted', 'rejected']) {
    for (const item of domains[key] ?? []) {
      const path = item.sourcePath ?? item.modulePath;
      if (typeof item.deliveryUnit === 'string') recordLater(path, item.deliveryUnit);
      for (const unit of item.secondaryUnits ?? []) recordLater(path, unit);
    }
  }
  const adoption = readBaselineDocument(repository, config.baseline.commit, 'config/operit-adoption.json');
  for (const item of adoption.modules ?? []) record(item.sample, 'S0.13');
  for (const item of adoption.terminalModules ?? []) record(item.sample, 'S0.13');
  for (const item of adoption.fileAdoptions ?? []) record(item.id, 'S0.13');
  return { files, later };
}

function evidenceClass(path) {
  for (const item of config.evidenceClasses) {
    if (item.patterns.length === 0 || !matches(path, item.patterns)) continue;
    if (item.exclusions && matches(path, item.exclusions)) continue;
    return item.id;
  }
  return 'unclassified';
}

function pathRule(path) {
  let selected = null;
  let selectedLength = -1;
  for (const rule of config.sourceIndex.pathRules) {
    for (const pattern of rule.patterns) {
      if (!globToRegExp(pattern).test(path)) continue;
      if (pattern.length > selectedLength) {
        selected = rule;
        selectedLength = pattern.length;
      }
    }
  }
  return selected;
}

function addUnit(units, unit) {
  assert.match(unit, /^(?:S0\.\d{2}|S[1-9]\d*\.\d+|S1\+|S0\.G)$/, `invalid delivery unit: ${unit}`);
  if (!units.includes(unit)) units.push(unit);
}

function laterUnit(units, unit) {
  if (unit === 'S1+' || /^S[1-9]\d*\.\d+$/.test(unit)) addUnit(units, unit);
}

function impactFor(path, baseline) {
  const evidence = evidenceClass(path);
  const units = [];
  let mapping = 'unmapped';
  let reason = 'No frozen baseline source path or path rule matched. Review before using as delivery evidence.';
  const exact = baseline.files.get(path);
  const review = config.sourceIndex.reviewPlansFsrs;
  if (review.paths.includes(path) || matches(path, review.overridePatterns ?? [])) {
    mapping = 'review-plans-fsrs';
    addUnit(units, review.unit);
    for (const unit of review.relatedUnits) addUnit(units, unit);
    reason = review.reason;
  } else if (exact && exact.size) {
    mapping = 'baseline-source-index';
    for (const unit of [...exact].sort()) addUnit(units, unit);
    reason = `Exact path is recorded by the frozen dstu-android ${config.baseline.commit} source index (${[...exact].sort().join(', ')}).`;
  } else {
    const rule = pathRule(path);
    if (rule) {
      mapping = `path-rule:${rule.id}`;
      addUnit(units, rule.unit);
      for (const unit of rule.relatedUnits ?? []) laterUnit(units, unit);
      reason = rule.reason;
    }
  }
  for (const unit of baseline.later.get(path) ?? []) laterUnit(units, unit);
  return {
    units,
    mapping,
    evidence,
    deliveryEvidence: evidence === 'production' && units.some(unit => S0_UNIT.test(unit)),
    reason,
  };
}

function validateInputs() {
  assert.equal(config.formatVersion, 1);
  assert.equal(config.unit, 'S0.01');
  assert.match(config.old.commit, /^[0-9a-f]{40}$/);
  assert.match(config.new.commit, /^[0-9a-f]{40}$/);
  assert.match(config.old.tree, /^[0-9a-f]{40}$/);
  assert.match(config.new.tree, /^[0-9a-f]{40}$/);
  assert.match(config.baseline.commit, /^[0-9a-f]{40}$/);
  assert.equal(config.old.requireCheckoutHead, false);
  assert.equal(config.diff.maximumFiles, null);
  assert.equal(config.diff.uncoveredMustEqual, 0);
  assert.deepEqual(config.diff.excludedFromCoverage, []);
  assert.equal(config.baseline.files.length, new Set(config.baseline.files).size);
  assert.ok(config.sourceIndex.pathRules.length > 0);
  assert.equal(config.scope.baselineFrozen, true, 'desktop baseline must be frozen');
  assert.equal(config.scope.adoptedDesktopCommit, config.new.commit, 'adopted desktop commit must equal the audited new commit');
  assert.equal(config.scope.upstreamMobileRuntime.adopted, false, 'upstream mobile runtime must not be adopted');
  assert.equal(config.scope.upstreamMobileRuntime.deliveryEvidence, false, 'upstream mobile runtime must not be delivery evidence');
  assert.equal(config.scope.upstreamMobileRuntime.productRequirement, false, 'upstream mobile runtime must not be a product requirement');
}

function buildAudit() {
  validateInputs();
  const envCheckout = process.env[config.new.allowCheckoutOverrideEnv];
  const newCheckout = envCheckout || config.new.checkout;
  const newState = assertNewCheckout({ ...config.new, checkout: newCheckout }, 'new');
  const objectSources = [newCheckout, config.old.objectSource, ...(config.old.fallbackObjectSources ?? [])].filter(Boolean);
  const oldObject = resolveGitSource(objectSources, 'old', config.old.commit);
  const diffSource = resolveGitSource(objectSources, 'diff', config.new.commit);
  assert.equal(gitText(diffSource.path, ['cat-file', '-t', config.old.commit]), 'commit',
    'diff object source must contain both old and new commits');
  const objectSource = diffSource.path;
  assertCommitTree(oldObject, config.old.commit, config.old.tree, 'old');
  assertCommitTree({ path: objectSource }, config.new.commit, config.new.tree, 'new');
  const entries = rawDiff(objectSource, newCheckout, config.old.commit, config.new.commit);
  assert.equal(entries.length, config.diff.expectedFiles, `changed file count ${entries.length} != ${config.diff.expectedFiles}`);
  const counts = entries.reduce((out, entry) => ({ ...out, [entry.status]: (out[entry.status] ?? 0) + 1 }), {});
  assert.deepEqual(counts, config.diff.expectedStatuses, 'changed status counts drifted');
  const baseline = sourceIndex(config.baseline.repository);
  const units = Object.fromEntries(config.sourceIndex.unitOrder.map(unit => [unit, []]));
  const changed = entries.map(entry => {
    const impact = impactFor(entry.path, baseline);
    if (matches(entry.path, config.scope.upstreamMobileRuntime.patterns)) {
      impact.units = ['S0.01'];
      impact.mapping = 'scoped-upstream-mobile-runtime';
      impact.evidence = 'support';
      impact.deliveryEvidence = false;
      impact.productRequirement = false;
      impact.classification = config.scope.upstreamMobileRuntime.classification;
      impact.reason = config.scope.upstreamMobileRuntime.reason;
    }
    for (const unit of impact.units) {
      if (!units[unit]) units[unit] = [];
      units[unit].push(entry.path);
    }
    return { ...entry, impact };
  });
  const uncovered = changed.filter(entry => entry.impact.units.length === 0);
  const duplicates = changed.length - new Set(changed.map(entry => entry.path)).size;
  const delivery = changed.filter(entry => entry.impact.deliveryEvidence);
  const nonDelivery = changed.filter(entry => !entry.impact.deliveryEvidence);
  const indexFiles = changed.filter(entry => entry.impact.mapping === 'baseline-source-index').length;
  const summary = {
    totalChangedFiles: changed.length,
    covered: changed.length - uncovered.length,
    uncovered: uncovered.length,
    duplicatePaths: duplicates,
    productionEvidenceFiles: delivery.length,
    testDemoDocSupportFiles: nonDelivery.length,
    baselineSourceIndexFiles: indexFiles,
    laterConsumerLinks: changed.filter(entry => entry.impact.units.some(unit => /^S[1-9]\d*\.\d+$/.test(unit))).length,
    byStatus: counts,
    byEvidence: Object.fromEntries(config.evidenceClasses.map(item => [item.id, changed.filter(entry => entry.impact.evidence === item.id).length])),
    byUnit: Object.fromEntries(Object.entries(units).filter(([, paths]) => paths.length).map(([unit, paths]) => [unit, paths.length])),
  };
  if (uncovered.length) log('upstream-audit.uncovered', { count: uncovered.length, paths: uncovered.map(entry => entry.path) });
  assert.equal(uncovered.length, 0, 'all changed files must be mapped');
  assert.equal(duplicates, 0, 'duplicate changed paths');
  assert.equal(changed.filter(entry => entry.impact.evidence !== 'production' && entry.impact.deliveryEvidence).length, 0, 'test/demo/doc/support cannot be delivery evidence');
  const document = {
    formatVersion: 1,
    unit: 'S0.01',
    generatedAt: now(),
    status: 'source-audit-only',
    audit: {
      old: {
        commit: config.old.commit,
        tree: config.old.tree,
        objectSource: oldObject.path,
        objectSourceCandidates: oldObject.candidates,
        checkoutHeadRequired: false,
      },
      new: {
        commit: config.new.commit,
        tree: config.new.tree,
        checkout: newState.checkout,
        checkoutHead: newState.head,
        checkoutHeadRequired: false,
        targetCommitContained: true,
        targetTree: newState.targetTree,
        checkoutDirty: false,
      },
      baseline: {
        repository: config.baseline.repository,
        commit: config.baseline.commit,
        files: config.baseline.files,
      },
      diff: {
        command: config.diff.command,
        objectSource,
        renameDetection: '50%',
        complete: true,
        truncated: false,
        excludedFromCoverage: [],
        counts,
      },
      tool: {
        path: TOOL_FILE,
        sha256: sha256(readFileSync(resolve(root, TOOL_FILE))),
      },
      inputs: {
        path: INPUT_FILE,
        sha256: sha256(readFileSync(resolve(root, INPUT_FILE))),
      },
      summary,
      sourceIndex: {
        oldBaselineSourceIndexFiles: baseline.files.size,
        laterConsumerLinks: changed.filter(entry => (baseline.later.get(entry.path)?.size ?? 0) > 0).length,
        mappedChangedFiles: indexFiles,
        unmappedChangedFiles: changed.filter(entry => entry.impact.mapping === 'unmapped').length,
        unitTotals: units,
      },
      uncovered: uncovered.map(entry => entry.path),
      files: changed,
    },
    impactHints: {
      scope: config.scope,
      reviewPlansFsrs: config.sourceIndex.reviewPlansFsrs,
      securityConflicts: config.securityConflicts,
      deliveredUnits: config.s0Status.deliveredUnits,
      openUnits: config.s0Status.openUnits,
    },
  };
  return { document, changed, baseline, summary };
}

function capture() {
  const { document, changed, baseline, summary } = buildAudit();
  writeFileSync(resolve(root, ARTIFACT_FILE), `${JSON.stringify(document, null, 2)}\n`);
  log('upstream-audit.capture.complete', {
    artifact: ARTIFACT_FILE,
    input: INPUT_FILE,
    oldCommit: config.old.commit,
    newCommit: config.new.commit,
    changedFiles: changed.length,
    covered: summary.covered,
    uncovered: summary.uncovered,
    productionEvidenceFiles: summary.productionEvidenceFiles,
    testDemoDocSupportFiles: summary.testDemoDocSupportFiles,
    sourceIndexFiles: baseline.files.size,
    log: LOG_FILE,
  });
  return document;
}

function verify() {
  assert.ok(existsSync(resolve(root, ARTIFACT_FILE)), `${ARTIFACT_FILE}: missing; run capture first`);
  const saved = readJson(ARTIFACT_FILE);
  const { document: expected } = buildAudit();
  assert.equal(saved.formatVersion, 1);
  assert.equal(saved.unit, 'S0.01');
  assert.equal(saved.status, 'source-audit-only');
  assert.equal(saved.audit.tool.sha256, sha256(readFileSync(resolve(root, TOOL_FILE))), 'tool changed without capture');
  assert.equal(saved.audit.inputs.sha256, sha256(readFileSync(resolve(root, INPUT_FILE))), 'inputs changed without capture');
  assert.notEqual(ARTIFACT_FILE, INPUT_FILE, 'artifact and inputs must be separate files');
  assert.equal(saved.audit.files.length, expected.audit.files.length, 'changed file count drift');
  assert.equal(saved.audit.summary.uncovered, 0);
  assert.equal(saved.audit.summary.covered, saved.audit.files.length);
  assert.equal(saved.audit.files.filter(entry => entry.impact.units.length === 0).length, 0);
  assert.equal(saved.audit.files.filter(entry => entry.impact.evidence !== 'production' && entry.impact.deliveryEvidence).length, 0);
  assert.deepEqual(saved.audit.files.map(entry => entry.path), expected.audit.files.map(entry => entry.path), 'changed path list drift');
  const drift = difference(saved.audit.files, expected.audit.files);
  assert.equal(drift, null, `artifact drift at ${drift?.path}`);
  assert.deepEqual(saved.audit.summary, expected.audit.summary, 'summary drift');
  assert.deepEqual(saved.audit.old, expected.audit.old, 'old identity drift');
  assert.deepEqual(saved.audit.new, expected.audit.new, 'new identity drift');
  assert.deepEqual(saved.audit.diff, expected.audit.diff, 'diff identity drift');
  assert.deepEqual(saved.audit.baseline, expected.audit.baseline, 'baseline pin drift');
  assert.deepEqual(saved.impactHints, expected.impactHints, 'impact hints drift');
  assert.deepEqual(saved.impactHints.scope, config.scope, 'scope drift');
  log('upstream-audit.verify.complete', {
    artifact: ARTIFACT_FILE,
    changedFiles: saved.audit.files.length,
    covered: saved.audit.summary.covered,
    uncovered: saved.audit.summary.uncovered,
    productionEvidenceFiles: saved.audit.summary.productionEvidenceFiles,
    testDemoDocSupportFiles: saved.audit.summary.testDemoDocSupportFiles,
  });
  return saved;
}

function reject(name, mutate) {
  const copy = JSON.parse(JSON.stringify(readJson(ARTIFACT_FILE)));
  mutate(copy);
  const expected = buildAudit().document;
  let rejected = false;
  try {
    const files = copy.audit.files;
    assert.equal(copy.audit.summary.uncovered, 0);
    assert.equal(copy.audit.summary.covered, files.length);
    assert.equal(copy.audit.summary.totalChangedFiles, files.length);
    assert.equal(new Set(files.map(entry => entry.path)).size, files.length);
    for (const entry of files) {
      assert.ok(entry.impact.units.length, `uncovered ${entry.path}`);
      assert.ok(entry.oldBlob || entry.status === 'A', `missing old blob ${entry.path}`);
      assert.ok(entry.newBlob || entry.status === 'D', `missing new blob ${entry.path}`);
      assert.ok(['A', 'D', 'M', 'R', 'C'].includes(entry.status), `bad status ${entry.path}`);
      assert.equal(entry.impact.deliveryEvidence, entry.impact.evidence === 'production' && entry.impact.units.some(unit => S0_UNIT.test(unit)));
      if (entry.status === 'R') assert.equal(entry.score, Number(entry.score), 'rename score');
      if (entry.impact.mapping === 'scoped-upstream-mobile-runtime') {
        assert.equal(entry.impact.productRequirement, false, 'upstream mobile must not be a product requirement');
        assert.equal(entry.impact.deliveryEvidence, false, 'upstream mobile must not be delivery evidence');
      }
    }
    assert.equal(copy.audit.old.commit, config.old.commit);
    assert.equal(copy.audit.new.commit, config.new.commit);
    assert.equal(copy.audit.new.tree, config.new.tree);
    assert.equal(copy.audit.baseline.commit, config.baseline.commit);
    assert.deepEqual(copy.audit.files.map(entry => entry.path), expected.audit.files.map(entry => entry.path));
    assert.equal(difference(copy.audit.files, expected.audit.files), null);
    assert.deepEqual(copy.audit.summary, expected.audit.summary);
  } catch {
    rejected = true;
  }
  assert.equal(rejected, true, `negative case was not rejected: ${name}`);
  log('upstream-audit.selftest.negative', { case: name, rejected });
}

function selftest() {
  assert.ok(existsSync(resolve(root, ARTIFACT_FILE)), `${ARTIFACT_FILE}: missing; capture first`);
  const before = readFileSync(resolve(root, ARTIFACT_FILE));
  const document = readJson(ARTIFACT_FILE);
  reject('missing changed file', copy => {
    copy.audit.files.shift();
    copy.audit.summary.totalChangedFiles = copy.audit.files.length;
    copy.audit.summary.covered = copy.audit.files.length;
  });
  reject('extra changed file', copy => {
    copy.audit.files.push({ ...copy.audit.files[0], path: 'synthetic-extra' });
    copy.audit.summary.totalChangedFiles = copy.audit.files.length;
    copy.audit.summary.covered = copy.audit.files.length;
  });
  reject('wrong old blob', copy => { copy.audit.files[0].oldBlob = '0'.repeat(40); });
  reject('wrong new blob', copy => { copy.audit.files[0].newBlob = 'f'.repeat(40); });
  reject('wrong status', copy => { copy.audit.files[0].status = 'X'; });
  reject('wrong old commit', copy => { copy.audit.old.commit = '0'.repeat(40); });
  reject('wrong new tree', copy => { copy.audit.new.tree = '0'.repeat(40); });
  reject('wrong baseline pin', copy => { copy.audit.baseline.commit = '0'.repeat(40); });
  reject('tampered reason', copy => { copy.audit.files[0].impact.reason = 'synthetic'; });
  reject('tampered impact unit', copy => { copy.audit.files[0].impact.units = ['S9.99']; });
  reject('uncovered count not zero', copy => { copy.audit.summary.uncovered = 1; });
  const nonProduction = document.audit.files.find(entry => entry.impact.evidence !== 'production');
  if (nonProduction) reject('test/demo treated as delivery', copy => {
    const item = copy.audit.files.find(entry => entry.path === nonProduction.path);
    item.impact.deliveryEvidence = true;
    item.impact.evidence = 'production';
  });
  assert.deepEqual(readFileSync(resolve(root, ARTIFACT_FILE)), before, 'selftest must not write the artifact');
  log('upstream-audit.selftest.complete', { cases: 11, artifactUnchanged: true, inputUnchanged: true });
}

export { capture, verify, selftest, buildAudit, parseRawDiff };

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const mode = process.argv[2];
  assert.ok(['capture', 'verify', 'selftest'].includes(mode), 'usage: node tools/baseline/upstream-audit.mjs capture|verify|selftest');
  try {
    const run = mode === 'capture' ? capture : mode === 'verify' ? verify : selftest;
    run();
  } catch (error) {
    log('upstream-audit.failed', { mode, message: error.message, stack: error.stack });
    process.exitCode = 1;
  }
}
