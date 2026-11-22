import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import {
  ACTIVE_PLAN,
  ARCHIVE_HANDOFF,
  CURRENT_HANDOFF,
  OLD_HANDOFF,
  OLD_S1_HANDOFF,
  S0_REF,
  S1_ARCHIVE_HANDOFF,
  S2_PROGRESS,
  parseDependencyPlan,
  parsePlanDocument,
  queryCurrentQueue,
  verifyPlan
} from './verify-plan.mjs';
import { EXPECTED_FROZEN_INPUTS, verifyHistoricalInputs } from '../s1/frozen-inputs.mjs';

const STAGES = ['S0', 'S1', 'S2', 'S3', 'S4', 'S5', 'S6', 'S7', 'S8', 'S9'];
const ACTIVE_STAGES = STAGES.slice(1);
const OLD_COUNTS = { S0: 25, S1: 27, S2: 28, S3: 29, S4: 32, S5: 34, S6: 44, S7: 38, S8: 26, S9: 19 };
const NEW_COUNTS = { ...OLD_COUNTS, S2: 31 };
const PATHS = {
  plan: 'docs/plans/2026-11-15-mobile-full-feature-refactor.md',
  queue: 'config/execution-plan.json',
  gate: 'config/s0-gate.json',
  lock: 'config/schema/final-lock.json',
  review: 'config/s0-g-main-review.json',
  source: 'config/source-lock.json',
  inputs: 'config/execution-inputs.json'
};
const REVISION_ID = '2026-11-23-s2-desktop-compatibility';
const REVISION = {
  id: REVISION_ID,
  startsAt: 'S2.16',
  startName: '修复普通恢复四库同步基线',
  inserted: [
    {
      id: 'S2.16',
      sourceUnits: ['S2.15'],
      dependencies: ['S2.15'],
      requiredTerms: ['普通恢复', '同步基线'],
      cardRef: 'S2.16/remediation/implementation'
    },
    {
      id: 'S2.17',
      sourceUnits: ['S2.08', 'S2.14'],
      dependencies: ['S2.16'],
      requiredTerms: ['资源', '引用'],
      cardRef: 'S2.17/remediation/implementation'
    },
    {
      id: 'S2.18',
      sourceUnits: ['S2.13', 'S2.14'],
      dependencies: ['S2.17'],
      requiredTerms: ['加密', 'marker'],
      cardRef: 'S2.18/remediation/implementation'
    }
  ],
  shift: { from: 'S2.16', to: 'S2.28', delta: 3 }
};
const INSERTED_ROWS = {
  'S2.16': {
    name: '修复普通恢复四库同步基线',
    deliverable: '恢复后重建同步基线',
    acceptance: '普通恢复后重建同步基线'
  },
  'S2.17': {
    name: '资源引用兼容',
    deliverable: '按引用检查资源',
    acceptance: '资源引用兼容'
  },
  'S2.18': {
    name: '旧加密marker兼容',
    deliverable: '验证旧加密marker',
    acceptance: '旧加密marker兼容'
  }
};

const unitIds = (stage, counts) => Array.from(
  { length: counts[stage] },
  (_, index) => `${stage}.${String(index + 1).padStart(2, '0')}`
);

const unitOrder = id => {
  const match = /^S(\d)\.(\d{2})$/u.exec(id);
  return Number(match[1]) * 100 + Number(match[2]);
};

const mapOldToNew = id => {
  const match = /^S2\.(\d{2})$/u.exec(id);
  if (!match) return id;
  const number = Number(match[1]);
  if (number < 16 || number > 28) return id;
  return `S2.${String(number + 3).padStart(2, '0')}`;
};

function defaultDependencies(id) {
  const match = /^S(\d)\.(\d{2})$/u.exec(id);
  const stage = Number(match[1]);
  const number = Number(match[2]);
  if (stage === 0 && number === 1) return [];
  if (stage === 0) return [`S0.${String(number - 1).padStart(2, '0')}`];
  if (number === 1) {
    if (stage === 3) return ['S2.27'];
    return [`S${stage - 1}.G`];
  }
  return [`S${stage}.${String(number - 1).padStart(2, '0')}`];
}

function oldRow(id) {
  if (id === 'S2.16') {
    return {
      id,
      name: '完善runtime.db状态与投影',
      dependencies: defaultDependencies(id),
      deliverable: 'tasks/events/approvals/audit/duties迁移',
      acceptance: 'generation/幂等键/游标不串任务，所有查询与状态重启读回'
    };
  }
  return {
    id,
    name: `unit ${id}`,
    dependencies: defaultDependencies(id),
    deliverable: `deliverable ${id}`,
    acceptance: `acceptance ${id}`
  };
}

function formatDependencies(dependencies) {
  return dependencies.length === 0 ? '—' : dependencies.join('、');
}

function makeDependencyDocument(counts, options = {}) {
  const lines = ['# Functional plan', ''];
  const oldRows = STAGES.flatMap(stage => unitIds(stage, OLD_COUNTS).map(oldRow));
  const mappedRows = options.newRevision
    ? oldRows.map(row => ({
      ...row,
      id: mapOldToNew(row.id),
      dependencies: row.dependencies.map(mapOldToNew)
    }))
    : oldRows;
  const byStage = new Map(STAGES.map(stage => [stage, []]));
  for (const row of mappedRows) byStage.get(row.id.slice(0, 2)).push(row);
  if (options.newRevision) {
    for (const entry of REVISION.inserted) {
      byStage.get('S2').push({
        id: entry.id,
        name: INSERTED_ROWS[entry.id].name,
        dependencies: entry.dependencies,
        deliverable: INSERTED_ROWS[entry.id].deliverable,
        acceptance: INSERTED_ROWS[entry.id].acceptance
      });
    }
  }
  for (const stage of STAGES) {
    lines.push(`### ${stage} functional units`);
    lines.push('| 编号 | 执行单元 | 前置依赖 | 交付物 | 验收条件 |');
    lines.push('| --- | --- | --- | --- | --- |');
    const rows = byStage.get(stage).sort((left, right) => unitOrder(left.id) - unitOrder(right.id));
    for (const base of rows) {
      if (options.remove?.has(base.id)) continue;
      const row = { ...base, ...(options.overrides?.[base.id] ?? {}) };
      lines.push(`| ${row.id} | ${row.name} | ${formatDependencies(row.dependencies)} | ${row.deliverable} | ${row.acceptance} |`);
    }
    lines.push('');
    lines.push(`**阶段出口 ${stage}.G**：本阶段全部签收。`);
    lines.push('');
  }
  return `${lines.join('\n')}\n`;
}

function makeActiveDocument(options = {}) {
  const lines = ['# Active plan', ''];
  for (const stage of ACTIVE_STAGES) {
    const number = Number(stage.slice(1));
    const ids = unitIds(stage, NEW_COUNTS);
    if (!options.headerless) {
      lines.push('| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |');
      lines.push('| --- | --- | --- | --- |');
    }
    if (options.rangeStages?.includes(number)) {
      lines.push(`| ${stage}.01–${stage}.${String(NEW_COUNTS[stage]).padStart(2, '0')} | backend-${stage} | frontend-${stage} | ${stage} range acceptance |`);
    } else {
      const ordered = [...ids];
      if (number === 1 && options.swapFirstTwo) [ordered[0], ordered[1]] = [ordered[1], ordered[0]];
      for (const id of ordered) {
        if (options.skip === id) continue;
        lines.push(options.replace?.[id]
          ?? `| ${id} | backend-${stage} | frontend-${stage} | ${id} layered acceptance |`);
        if (number === 1 && id === 'S1.01') lines.push(...(options.extraRows ?? []));
      }
    }
    lines.push('');
    lines.push(`**阶段出口 ${stage}.G**：本阶段全部签收。`);
    lines.push('');
  }
  lines.push(...(options.extraExits ?? []));
  return `${lines.join('\n')}\n`;
}

function makeFixture(options = {}) {
  const files = new Map();
  const put = (file, text) => files.set(file, Buffer.from(text));
  const hash = file => createHash('sha256').update(files.get(file)).digest('hex');
  const oldPlanText = options.oldPlanText ?? makeDependencyDocument(OLD_COUNTS);
  const functionalPlanText = options.functionalPlanText
    ?? makeDependencyDocument(NEW_COUNTS, { newRevision: true, ...(options.functionalOptions ?? {}) });
  const documentText = options.documentText ?? makeActiveDocument(options.activeOptions ?? {});
  const progress = options.progress ?? {
    next: 'S2.16',
    planRevision: REVISION_ID,
    currentPlan: 'config/planning/active-plan.json',
    nextName: '修复普通恢复四库同步基线',
    plannedRemediations: [
      { id: 'S2.16', status: 'pending', dependsOn: ['S2.15'] },
      { id: 'S2.17', status: 'pending', dependsOn: ['S2.16'] },
      { id: 'S2.18', status: 'pending', dependsOn: ['S2.17'] }
    ],
    units: Array.from({ length: 15 }, (_, index) => ({
      id: `S2.${String(index + 1).padStart(2, '0')}`,
      status: 'accepted'
    }))
  };
  const oldUnits = STAGES.flatMap(stage => unitIds(stage, OLD_COUNTS)).map(id => ({
    id,
    cardIds: [`${id}/implementation`]
  }));
  const queue = {
    plan: { file: PATHS.plan, sha256: createHash('sha256').update(oldPlanText).digest('hex'), mainUnits: 302, stageExits: 10 },
    checks: { mainUnits: 302, stageExits: 10, serialQueue: 312, totalCards: 1774, scenarios: 3198 },
    units: oldUnits,
    stageExits: STAGES.map(stage => ({ id: `${stage}.G` })),
    queue: Array(312).fill({}),
    cards: [
      ...oldUnits.map(unit => ({ id: unit.cardIds[0], unit: unit.id })),
      ...Array.from({ length: 1774 - oldUnits.length }, (_, index) => ({ id: `filler-card-${index}`, unit: 'S0.01' }))
    ],
    scenarios: [
      ...oldUnits.map(unit => ({ id: `scenario:${unit.id}`, unit: unit.id })),
      ...Array.from({ length: 3198 - oldUnits.length }, (_, index) => ({ id: `filler-scenario-${index}`, unit: 'S0.01' }))
    ]
  };
  const finalLock = {
    executionPlan: {
      present: true,
      artifact: PATHS.queue,
      units: 302,
      stageExits: 10,
      serialQueue: 312,
      cards: 1774,
      freezeReady: true,
      unresolvedMembers: 0,
      unresolved: 0
    }
  };
  const review = {
    decision: 'accepted',
    units: { count: 25, allEvidence: true },
    m0: { complete: true, signedOff: true },
    finalLock: { file: PATHS.lock, sha256: '' },
    policy: { file: PATHS.gate, sha256: '' }
  };
  [
    PATHS.plan,
    PATHS.queue,
    PATHS.gate,
    PATHS.lock,
    PATHS.review,
    PATHS.source,
    PATHS.inputs,
    ARCHIVE_HANDOFF,
    S1_ARCHIVE_HANDOFF,
    CURRENT_HANDOFF,
    S2_PROGRESS
  ].forEach(file => put(file, `frozen bytes for ${file}`));
  put(PATHS.plan, functionalPlanText);
  put(PATHS.queue, JSON.stringify(queue));
  put(ARCHIVE_HANDOFF, 'archived handoff bytes');
  put(S1_ARCHIVE_HANDOFF, 'archived S1 handoff bytes');
  put(CURRENT_HANDOFF, '# S2 handoff\n');
  put(S2_PROGRESS, JSON.stringify(progress));
  review.finalLock.sha256 = hash(PATHS.lock);
  review.policy.sha256 = hash(PATHS.gate);
  const metadata = {
    formatVersion: 1,
    format: 'dstu-active-plan/v1',
    activePlan: { file: ACTIVE_PLAN, frozen: false },
    frozenPlan: { file: PATHS.plan, gitRef: S0_REF, sha256: createHash('sha256').update(oldPlanText).digest('hex') },
    functionalPlan: {
      file: PATHS.plan,
      sha256: createHash('sha256').update(functionalPlanText).digest('hex'),
      hashStatus: 'sealed'
    },
    frozenQueue: { file: PATHS.queue, sha256: hash(PATHS.queue) },
    signedReceipt: { file: PATHS.review, sha256: hash(PATHS.review) },
    s2Receipt: {
      file: S2_PROGRESS,
      next: 'S2.16',
      planRevision: REVISION_ID,
      currentPlan: 'config/planning/active-plan.json',
      nextName: '修复普通恢复四库同步基线'
    },
    revision: structuredClone(REVISION),
    handoffs: {
      archive: {
        file: ARCHIVE_HANDOFF,
        source: OLD_HANDOFF,
        bytes: files.get(ARCHIVE_HANDOFF).length,
        sha256: hash(ARCHIVE_HANDOFF)
      },
      s1Archive: {
        file: S1_ARCHIVE_HANDOFF,
        source: OLD_S1_HANDOFF,
        bytes: files.get(S1_ARCHIVE_HANDOFF).length,
        sha256: hash(S1_ARCHIVE_HANDOFF)
      },
      current: { file: CURRENT_HANDOFF }
    },
    references: [
      { id: 'source-lock', file: PATHS.source, sha256: hash(PATHS.source) },
      { id: 'execution-inputs', file: PATHS.inputs, sha256: hash(PATHS.inputs) },
      { id: 'execution-plan', file: PATHS.queue, sha256: hash(PATHS.queue) },
      { id: 's0-gate', file: PATHS.gate, sha256: hash(PATHS.gate) },
      { id: 'final-lock', file: PATHS.lock, sha256: hash(PATHS.lock) },
      { id: 's0-g-main-review', file: PATHS.review, sha256: hash(PATHS.review) },
      { id: 'frozen-plan', file: PATHS.plan, gitRef: S0_REF, sha256: createHash('sha256').update(oldPlanText).digest('hex') },
      { id: 's0-handoff-archive', file: ARCHIVE_HANDOFF, sha256: hash(ARCHIVE_HANDOFF) },
      { id: 's1-handoff-archive', file: S1_ARCHIVE_HANDOFF, sha256: hash(S1_ARCHIVE_HANDOFF) }
    ],
    expected: {
      activeUnits: 280,
      activeStageExits: 9,
      functionalUnits: 305,
      functionalStageExits: 10,
      functionalSerialQueue: 315,
      stages: ACTIVE_STAGES,
      firstUnit: 'S1.01',
      lastUnit: 'S9.19',
      stageCounts: NEW_COUNTS,
      queue: { mainUnits: 302, stageExits: 10, serialQueue: 312, cards: 1774, scenarios: 3198 },
      currentQueue: {
        mainUnits: 305,
        stageExits: 10,
        serialQueue: 315,
        inheritedFrozenCards: 1774,
        inheritedFrozenScenarios: 3198,
        newImplementationCards: 3
      }
    }
  };
  return {
    metadata,
    documentText,
    functionalPlanText,
    oldPlanText,
    queue,
    finalLock,
    review,
    progress,
    readFile: file => {
      if (!files.has(file)) throw new Error(`missing ${file}`);
      return files.get(file);
    },
    readGitFile: (gitRef, file) => {
      if (gitRef !== S0_REF || file !== PATHS.plan) throw new Error(`missing ${gitRef}:${file}`);
      return Buffer.from(oldPlanText);
    },
    exists: file => files.has(file),
    files
  };
}

test('accepts 305 functional units, 280 active units and nine ordered exits', () => {
  const result = verifyPlan(makeFixture());
  assert.equal(result.activeUnits, 280);
  assert.equal(result.activeStageExits, 9);
  assert.equal(result.functionalUnits, 305);
  assert.equal(result.functionalStageExits, 10);
  assert.equal(result.functionalSerialQueue, 315);
  assert.equal(result.currentQueue.inheritedFrozenCards, 1774);
  assert.equal(result.currentQueue.inheritedFrozenScenarios, 3198);
  assert.equal(result.currentQueue.newImplementationCards, 3);
  assert.equal(result.references, 9);
  assert.equal(result.next, 'S2.16');
  assert.equal(result.scope, 'document-reference-and-coverage-only');
});

test('accepts an en dash range within one stage', () => {
  assert.equal(verifyPlan(makeFixture({ activeOptions: { rangeStages: [2] } })).activeUnits, 280);
});

test('accepts a responsibility table without a header', () => {
  assert.equal(verifyPlan(makeFixture({ activeOptions: { headerless: true } })).activeUnits, 280);
});

test('rejects a missing active unit', () => {
  assert.throws(() => verifyPlan(makeFixture({ activeOptions: { skip: 'S5.02' } })), /missing active units: S5\.02/u);
});

test('rejects a duplicate active unit', () => {
  const duplicate = '| S1.01 | backend-S9 | frontend-S9 | duplicate acceptance |';
  assert.throws(() => verifyPlan(makeFixture({ activeOptions: { extraRows: [duplicate] } })), /duplicate active unit S1\.01/u);
});

test('rejects an unknown active unit', () => {
  const unknown = '| S5.99 | backend-S5 | frontend-S5 | unknown acceptance |';
  assert.throws(() => verifyPlan(makeFixture({ activeOptions: { replace: { 'S5.02': unknown } } })), /unknown active unit S5\.99/u);
});

test('rejects an S0 responsibility row', () => {
  const row = '| S0.01 | backend-S5 | frontend-S5 | S0 acceptance |';
  assert.throws(() => verifyPlan(makeFixture({ activeOptions: { replace: { 'S5.02': row } } })), /S0 rows are not allowed/u);
});

test('rejects a reversed range', () => {
  const document = '| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |\n| --- | --- | --- | --- |\n| S2.03–S2.01 | backend | frontend | acceptance |\n';
  assert.throws(() => parsePlanDocument(document), /reversed range/u);
});

test('rejects a cross-stage range', () => {
  const document = '| 单元 | 后端归属 | 前端归属 | 分层交付与新增验收 |\n| --- | --- | --- | --- |\n| S1.27–S2.01 | backend | frontend | acceptance |\n';
  assert.throws(() => parsePlanDocument(document), /cross-stage range/u);
});

test('rejects out-of-order active rows', () => {
  assert.throws(() => verifyPlan(makeFixture({ activeOptions: { swapFirstTwo: true } })), /out-of-order active unit S1\.01/u);
});

test('rejects a bad frozen reference hash', () => {
  const fixture = makeFixture();
  fixture.metadata.references.find(ref => ref.id === 'execution-plan').sha256 = '0'.repeat(64);
  assert.throws(() => verifyPlan(fixture), /reference execution-plan: sha256 drift/u);
});

test('rejects a changed frozen queue count', () => {
  const fixture = makeFixture();
  fixture.queue.checks.totalCards = 1773;
  assert.throws(() => verifyPlan(fixture), /frozen queue cards: 1773 !== 1774/u);
});

test('rejects changed frozen queue unit ids', () => {
  const fixture = makeFixture();
  fixture.queue.units[0].id = 'S0.99';
  assert.throws(() => verifyPlan(fixture), /frozen queue unit ids changed/u);
});

test('rejects a new remediation reusing a frozen card id', () => {
  const fixture = makeFixture();
  fixture.metadata.revision.inserted[0].cardRef = 'S2.16/implementation';
  assert.throws(() => verifyPlan(fixture), /reuses frozen card S2\.16\/implementation/u);
});

test('rejects a missing reason after 无页面', () => {
  const row = '| S5.02 | backend-S5 | 无页面 | S5.02 acceptance |';
  assert.throws(() => verifyPlan(makeFixture({ activeOptions: { replace: { 'S5.02': row } } })), /无页面 requires a reason/u);
});

test('accepts a backend-only row with an explicit no-page reason', () => {
  const row = '| S5.02 | backend-S5 | 无页面（纯后端：仅同步合同） | S5.02 acceptance |';
  assert.equal(verifyPlan(makeFixture({ activeOptions: { replace: { 'S5.02': row } } })).activeUnits, 280);
});

test('rejects an empty owner', () => {
  const row = '| S5.02 | backend-S5 |  | S5.02 acceptance |';
  assert.throws(() => verifyPlan(makeFixture({ activeOptions: { replace: { 'S5.02': row } } })), /frontend owner is empty/u);
});

test('rejects a duplicate stage exit', () => {
  assert.throws(
    () => verifyPlan(makeFixture({ activeOptions: { extraExits: ['**阶段出口 S1.G**：重复出口。'] } })),
    /duplicate stage exit S1\.G/u
  );
});

test('rejects hashing rules', () => {
  const fixture = makeFixture();
  fixture.metadata.references.push({ id: 'rules', file: 'docs/rules/rules.txt', sha256: 'a'.repeat(64) });
  assert.throws(() => verifyPlan(fixture), /rules must not be hash-bound/u);
});

test('rejects a legacy handoff that still exists', () => {
  for (const path of [OLD_HANDOFF, OLD_S1_HANDOFF]) {
    const fixture = makeFixture();
    fixture.files.set(path, Buffer.from('stale handoff'));
    assert.throws(() => verifyPlan(fixture), /legacy handoff still exists/u, path);
  }
});

test('rejects a gate before its stage units', () => {
  const fixture = makeFixture();
  const gate = '**阶段出口 S1.G**：本阶段全部签收。';
  fixture.documentText = `${gate}\n${fixture.documentText.replace(gate, '')}`;
  assert.throws(() => verifyPlan(fixture), /S1\.G: exit must follow its stage units/u);
});

test('rejects gates collected after all stage units', () => {
  const fixture = makeFixture();
  const lines = fixture.documentText.split('\n');
  const gates = lines.filter(line => line.startsWith('**阶段出口 '));
  fixture.documentText = [...lines.filter(line => !gates.includes(line)), ...gates].join('\n');
  assert.throws(() => verifyPlan(fixture), /S1\.G: exit must precede the next stage/u);
});

test('rejects a missing gate', () => {
  const fixture = makeFixture();
  fixture.documentText = fixture.documentText.replace('**阶段出口 S4.G**：本阶段全部签收。', '');
  assert.throws(() => verifyPlan(fixture), /missing stage exits: S4\.G/u);
});

test('rejects changed archived handoff bytes', () => {
  for (const [stage, file] of [['s0', ARCHIVE_HANDOFF], ['s1', S1_ARCHIVE_HANDOFF]]) {
    const fixture = makeFixture();
    fixture.files.set(file, Buffer.from('changed archive'));
    assert.throws(() => verifyPlan(fixture), new RegExp(`${stage}-handoff-archive: sha256 drift`, 'u'));
  }
});

test('requires a frozen reference for the archived S1 handoff', () => {
  const fixture = makeFixture();
  fixture.metadata.references = fixture.metadata.references.filter(ref => ref.id !== 's1-handoff-archive');
  assert.throws(() => verifyPlan(fixture), /missing reference s1-handoff-archive/u);
});

test('rejects a changed S1 archive byte count in metadata', () => {
  const fixture = makeFixture();
  fixture.metadata.handoffs.s1Archive.bytes++;
  assert.throws(() => verifyPlan(fixture), /s1-handoff-archive: handoff archive bytes/u);
});

test('requires the current S2 handoff', () => {
  const fixture = makeFixture();
  fixture.files.delete(CURRENT_HANDOFF);
  assert.throws(() => verifyPlan(fixture), /current handoff missing: docs\/handoff-s2\.md/u);
});

test('rejects an unsigned M0 receipt', () => {
  const fixture = makeFixture();
  fixture.review.m0.signedOff = false;
  assert.throws(() => verifyPlan(fixture), /signed receipt m0/u);
});

test('rejects a missing S2.16 baseline repair unit', () => {
  const fixture = makeFixture({ functionalOptions: { remove: new Set(['S2.16']) } });
  assert.throws(() => verifyPlan(fixture), /functional plan S2\.17: forward or unknown dependency S2\.16/u);
});

test('rejects duplicate functional numbering', () => {
  const text = makeDependencyDocument(NEW_COUNTS, { newRevision: true });
  const row = '| S2.16 | duplicate | S2.15 | duplicate | duplicate |';
  const lines = text.split('\n');
  const index = lines.findIndex(line => line.startsWith('| S2.16 |'));
  lines.splice(index + 1, 0, row);
  assert.throws(() => parseDependencyPlan(lines.join('\n')), /duplicate functional unit S2\.16/u);
});

test('rejects a stale cross-stage prerequisite after renumbering', () => {
  const fixture = makeFixture({
    functionalOptions: { overrides: { 'S3.01': { dependencies: ['S2.27'] } } }
  });
  assert.throws(() => verifyPlan(fixture), /functional unit S3\.01: dependency mismatch/u);
});

test('rejects a forward dependency', () => {
  const fixture = makeFixture({
    functionalOptions: { overrides: { 'S1.02': { dependencies: ['S9.19'] } } }
  });
  assert.throws(() => verifyPlan(fixture), /forward or unknown dependency S9\.19/u);
});

test('rejects S2.16 still describing the old runtime unit', () => {
  const fixture = makeFixture({
    functionalOptions: {
      overrides: {
        'S2.16': {
          name: '完善runtime.db状态与投影',
          deliverable: 'tasks/events/approvals/audit/duties迁移',
          acceptance: 'generation/幂等键/游标不串任务，所有查询与状态重启读回'
        }
      }
    }
  });
  assert.throws(() => verifyPlan(fixture), /functional unit S2\.16: missing required term 普通恢复/u);
});

test('rejects next pointing at the old runtime unit', () => {
  const fixture = makeFixture();
  fixture.progress.next = 'S2.19';
  assert.throws(() => verifyPlan(fixture), /s2 receipt next: S2\.19 !== S2\.16/u);
});

test('rejects a receipt revision mismatch', () => {
  const fixture = makeFixture();
  fixture.progress.planRevision = 'old-revision';
  assert.throws(() => verifyPlan(fixture), /s2 receipt planRevision: old-revision !== 2026-11-23-s2-desktop-compatibility/u);
});

test('rejects changing the existing accepted S2 units', () => {
  const fixture = makeFixture();
  fixture.progress.units[0].status = 'pending';
  assert.throws(() => verifyPlan(fixture), /s2 receipt accepted unit changed: S2\.01/u);
});

test('allows descriptive fields on planned remediations', () => {
  const fixture = makeFixture();
  fixture.progress.plannedRemediations[0].name = '修复普通恢复四库同步基线';
  fixture.progress.plannedRemediations[0].repairs = ['S2.15'];
  fixture.progress.plannedRemediations[0].reason = '保留候选证据';
  assert.equal(verifyPlan(fixture).next, 'S2.16');
});

test('host-tested remediation awaits device signoff without advancing next', () => {
  const fixture = makeFixture();
  for (const item of fixture.progress.plannedRemediations) item.status = 'awaiting-device';
  assert.equal(verifyPlan(fixture).next, 'S2.16');
  fixture.progress.plannedRemediations[0].status = 'accepted';
  assert.throws(() => verifyPlan(fixture), /plannedRemediations\[0\]/u);
});

test('rejects a missing current functional-plan hash', () => {
  const fixture = makeFixture();
  fixture.metadata.functionalPlan.sha256 = null;
  fixture.metadata.functionalPlan.hashStatus = 'pending';
  assert.throws(() => verifyPlan(fixture), /functional plan sha256 missing/u);
});

test('rejects a current functional-plan hash that does not match the live bytes', () => {
  const fixture = makeFixture();
  fixture.metadata.functionalPlan.sha256 = '0'.repeat(64);
  assert.throws(() => verifyPlan(fixture), /functional-plan: sha256 drift/u);
});

test('rejects a non-sealed current functional-plan hash', () => {
  const fixture = makeFixture();
  fixture.metadata.functionalPlan.hashStatus = 'pending';
  assert.throws(() => verifyPlan(fixture), /functional plan hashStatus is not sealed/u);
});

test('protects the historical source hash', () => {
  const fixture = makeFixture();
  fixture.oldPlanText = fixture.oldPlanText.replace('unit S1.01', 'changed S1.01');
  assert.throws(() => verifyPlan(fixture), /frozen-plan: sha256 drift/u);
});

test('query maps S2.16 to the S2.15 source card and S2.19 to old S2.16', () => {
  const fixture = makeFixture();
  const inserted = queryCurrentQueue('S2.16', fixture);
  assert.deepEqual(inserted.sourceUnits, ['S2.15']);
  assert.deepEqual(inserted.currentCardRefs, ['S2.16/remediation/implementation']);
  assert.deepEqual(inserted.sourceCardRefs, ['S2.15/implementation']);
  const shifted = queryCurrentQueue('S2.19', fixture);
  assert.deepEqual(shifted.sourceUnits, ['S2.16']);
  assert.deepEqual(shifted.currentCardRefs, ['S2.19/implementation']);
  assert.deepEqual(shifted.sourceCardRefs, ['S2.16/implementation']);
});

test('rejects unauthorized frozen file modification', () => {
  const paths = Array.from({ length: EXPECTED_FROZEN_INPUTS }, (_, index) => `frozen-${index}`);
  const readOld = path => Buffer.from(`old:${path}`);
  assert.throws(() => verifyHistoricalInputs(paths, {
    authorizedPath: 'frozen-0',
    readOld,
    readLive: path => path === 'frozen-1' ? Buffer.from('changed') : readOld(path),
    revisionVerifier: () => {}
  }), /frozen bytes changed: frozen-1/u);
});

test('requires exactly one authorized revision path', () => {
  const paths = Array.from({ length: EXPECTED_FROZEN_INPUTS }, (_, index) => `frozen-${index}`);
  const readOld = path => Buffer.from(`old:${path}`);
  assert.throws(() => verifyHistoricalInputs(paths, {
    authorizedPath: 'missing',
    readOld,
    readLive: readOld,
    revisionVerifier: () => {}
  }), /authorized revision path missing: missing/u);
});


test('accepts explicitly registered future-unit refinements and added prerequisites', () => {
  const fixture = makeFixture({ functionalOptions: { overrides: {
    'S2.29': { dependencies: ['S2.28', 'S2.18'], deliverable: 'database+change_log_id and crypto session', acceptance: 'cross-database receipts and restart' }
  } } });
  fixture.metadata.revision.refinements = { 'S2.29': {
    fields: ['deliverable', 'acceptance'], additionalDependencies: ['S2.18']
  } };
  assert.equal(verifyPlan(fixture).functionalUnits, 305);
});

test('rejects an unregistered rewrite of a preserved future unit', () => {
  const fixture = makeFixture({ functionalOptions: { overrides: {
    'S2.29': { deliverable: 'removed all publication requirements' }
  } } });
  assert.throws(() => verifyPlan(fixture), /without a registered refinement/u);
});

test('rejects registering a forward prerequisite even if it was not used in the plan', () => {
  const fixture = makeFixture();
  fixture.metadata.revision.refinements = { 'S2.29': { fields: [], additionalDependencies: ['S9.01'] } };
  assert.throws(() => verifyPlan(fixture), /revision refinement S2\.29: dependencies/u);
});

function acceptRepairs(fixture, count) {
  const rows = parseDependencyPlan(fixture.functionalPlanText);
  for (const entry of fixture.metadata.revision.inserted.slice(0, count)) {
    fixture.progress.units.push({ id: entry.id, status: 'accepted', card: entry.cardRef });
    fixture.progress.plannedRemediations.find(item => item.id === entry.id).status = 'accepted';
  }
  const next = `S2.${String(16 + count).padStart(2, '0')}`;
  fixture.progress.next = next;
  fixture.progress.nextName = rows.byId.get(next).name;
}

test('allows normal signoff to advance next without rewriting the revision start', () => {
  const fixture = makeFixture();
  acceptRepairs(fixture, 1);
  assert.equal(verifyPlan(fixture).next, 'S2.17');
  assert.equal(fixture.metadata.revision.startsAt, 'S2.16');
});

test('allows runtime S2.19 only after all three independent remediation receipts', () => {
  const fixture = makeFixture();
  acceptRepairs(fixture, 3);
  assert.equal(verifyPlan(fixture).next, 'S2.19');
  assert.equal(queryCurrentQueue('S2.19', fixture).name, '完善runtime.db状态与投影');
});

test('rejects an old accepted card used as new remediation evidence', () => {
  const fixture = makeFixture();
  acceptRepairs(fixture, 1);
  fixture.progress.units.at(-1).card = 'S2.15/implementation';
  assert.throws(() => verifyPlan(fixture), /requires independent remediation card/u);
});

test('does not mark the S2 stage gate accepted before all current units', () => {
  const fixture = makeFixture();
  fixture.progress.s2GateAccepted = true;
  assert.throws(() => verifyPlan(fixture), /premature stage gate/u);
});

test('current queue query preserves parameterized subcards and inherited stage gates', () => {
  const fixture = makeFixture();
  fixture.queue.units.find(unit => unit.id === 'S2.26').cardIds = ['S2.26/common-setup', 'S2.26/sample/upload', 'S2.26/sample/retry'];
  const result = queryCurrentQueue('S2.29', fixture);
  assert.deepEqual(result.currentCardRefs, ['S2.29/common-setup', 'S2.29/sample/upload', 'S2.29/sample/retry']);
  assert.deepEqual(result.sourceCardRefs, ['S2.26/common-setup', 'S2.26/sample/upload', 'S2.26/sample/retry']);
  assert.ok(result.dependencies.includes('S2.28'));
  assert.ok(result.dependencies.includes('S1.G'));
});
