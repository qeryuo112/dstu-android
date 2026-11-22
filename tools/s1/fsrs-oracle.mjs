import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
const desktop = resolve(root, '../dstu-node');
const lock = JSON.parse(readFileSync(join(root, 'config/stages/s1-fsrs-source.json'), 'utf8'));
const outDir = join(root, 'fixtures/s1-18');
const verifyOnly = process.argv.includes('--verify');
function saveFixture(name, bytes) {
  const file = join(outDir, name);
  if (verifyOnly) {
    const expected = readFileSync(file, 'utf8');
    if (expected === bytes) return;
    if (name === 'training-vectors.json') {
      const before = JSON.parse(expected), actual = JSON.parse(bytes);
      console.log(JSON.stringify({ event: 'fsrs.oracle.training.comparison', expected: { current: before.current, optimized: before.optimized },
        actual: { current: actual.current, optimized: actual.optimized },
        maxParameterDelta: Math.max(...before.params.map((p, i) => Math.abs(p - actual.params[i]))) }));
      const structural = value => { const { current, optimized, params, ...rest } = value; return JSON.stringify(rest); };
      if (structural(before) !== structural(actual) || before.params.length !== actual.params.length) {
        throw new Error('FSRS training fixture structure differs');
      }
      const numbers = value => [...value.params, value.current.logLoss, value.current.rmseBins,
        value.optimized.logLoss, value.optimized.rmseBins];
      if (numbers(before).some((value, index) => Math.abs(value - numbers(actual)[index]) > 1e-6)) {
        throw new Error('FSRS training numeric difference exceeds 1e-6');
      }
      return;
    }
    throw new Error(`FSRS frozen-desktop fixture differs: ${name}`);
  } else writeFileSync(file, bytes);
}

function hash(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function git(args) {
  const result = spawnSync('git', ['-C', desktop, ...args], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(result.stderr);
  return result.stdout.trim();
}

if (git(['rev-parse', 'HEAD']) !== lock.desktopOracle.commit) throw new Error('desktop HEAD drift');
if (git(['rev-parse', 'HEAD^{tree}']) !== lock.desktopOracle.tree) throw new Error('desktop tree drift');
if (git(['status', '--porcelain', '--untracked-files=no']) !== '') throw new Error('desktop is dirty');
for (const file of lock.desktopOracle.files) {
  if (hash(join(desktop, file.path)) !== file.sha256) throw new Error(`oracle file drift: ${file.path}`);
}

const scheduler = await import(join(desktop, 'host/plugins/review/src/fsrs-scheduler.ts'));
const planFsrs = await import(join(desktop, 'host/plugins/review/src/review-plan-fsrs.ts'));
const native = await import(join(desktop, 'host/platform/native/fsrs/index.ts'));

const SHANGHAI = 8 * 60;
const now = Date.UTC(2026, 10, 15, 4, 0) - SHANGHAI * 60_000;

function context(fuzzFactor = null, overrides = {}) {
  return {
    fsrs: new scheduler.Fsrs6(),
    desiredRetention: 0.9,
    learningSteps: scheduler.DEFAULT_LEARNING_STEPS_MINUTES,
    relearningSteps: scheduler.DEFAULT_RELEARNING_STEPS_MINUTES,
    maximumInterval: scheduler.DEFAULT_MAXIMUM_INTERVAL_DAYS,
    fuzzFactor,
    ...overrides,
  };
}

function answer(value) {
  return {
    phase: value.phase,
    stability: value.memory.stability,
    difficulty: value.memory.difficulty,
    delay: value.delay,
    learningStep: value.learningStep,
    reps: value.reps,
    lapses: value.lapses,
  };
}

function outcome(value, nowMs, config) {
  let dueMs;
  let scheduledDays;
  if (value.delay.kind === 'minutes') {
    dueMs = scheduler.learningDueMs(nowMs, value.delay.minutes, config.dayRolloverHour ?? 4, SHANGHAI);
    scheduledDays = 0;
  } else {
    dueMs = nowMs + value.delay.days * 86_400_000;
    scheduledDays = value.delay.days;
  }
  return { ...answer(value), scheduledDays, dueMs };
}

const schedulerCases = [];
function addCase(id, card, cfg, nowMs = now) {
  const result = scheduler.schedule(card, cfg);
  schedulerCases.push({
    id,
    nowMs,
    card,
    config: {
      desiredRetention: cfg.desiredRetention,
      learningSteps: cfg.learningSteps,
      relearningSteps: cfg.relearningSteps,
      maximumInterval: cfg.maximumInterval,
      fuzzFactor: cfg.fuzzFactor,
    },
    expected: {
      again: outcome(result.again, nowMs, { dayRolloverHour: 4 }),
      hard: outcome(result.hard, nowMs, { dayRolloverHour: 4 }),
      good: outcome(result.good, nowMs, { dayRolloverHour: 4 }),
      easy: outcome(result.easy, nowMs, { dayRolloverHour: 4 }),
    },
  });
}

const newCard = { id: 'card-1', phase: 'new', memory: null, learningStep: 0, daysElapsed: 0, reps: 0, lapses: 0 };
addCase('new-default', newCard, context(scheduler.fuzzFactorFor('card-1', 0)));

const newGood = scheduler.schedule(newCard, context(scheduler.fuzzFactorFor('card-1', 0))).good;
addCase('learning-last-step-good', {
  id: 'card-1', phase: 'learning', memory: newGood.memory, learningStep: newGood.learningStep,
  daysElapsed: 0, reps: newGood.reps, lapses: 0,
}, context(scheduler.fuzzFactorFor('card-1', 1)));

const reviewLast = now - 10 * 86_400_000;
const reviewCard = {
  phase: 'review', memory: { stability: 10, difficulty: 5 }, learningStep: 0,
  daysElapsed: 10, reps: 3, lapses: 0,
};
addCase('review-fuzz-off', { ...reviewCard, lastReviewMs: reviewLast }, context(null));
addCase('review-fuzz-on', { ...reviewCard, id: 'card-1', lastReviewMs: reviewLast }, context(scheduler.fuzzFactorFor('card-1', 3)));

addCase('learning-no-steps', { ...newCard, id: 'card-empty' }, context(scheduler.fuzzFactorFor('card-empty', 0), { learningSteps: [] }));
addCase('review-max-interval', {
  phase: 'review', memory: { stability: 400, difficulty: 5 }, learningStep: 0,
  daysElapsed: 400, reps: 5, lapses: 0, lastReviewMs: now - 400 * 86_400_000,
}, context(null, { maximumInterval: 30 }));

const plans = [
  {
    id: 'rp_fsrs', question_id: 'q1', exam_id: 'e1', ease_factor: 2.5, interval_days: 0, repetitions: 0,
    next_review_date: '2026-11-15', last_review_date: null, status: 'new', total_reviews: 0,
    total_correct: 0, consecutive_failures: 0, is_difficult: false,
    created_at: '2026-11-01T00:00:00+00:00', updated_at: '2026-11-01T00:00:00+00:00',
  },
  {
    id: 'rp_legacy_review', question_id: 'q2', exam_id: 'e1', ease_factor: 2.5, interval_days: 12, repetitions: 3,
    next_review_date: '2026-11-15', last_review_date: '2026-11-03', status: 'reviewing', total_reviews: 3,
    total_correct: 3, consecutive_failures: 0, is_difficult: false,
    created_at: '2026-11-01T00:00:00+00:00', updated_at: '2026-11-01T00:00:00+00:00',
  },
  {
    id: 'rp_legacy_failed', question_id: 'q3', exam_id: 'e1', ease_factor: 1.7, interval_days: 1, repetitions: 0,
    next_review_date: '2026-11-15', last_review_date: '2026-11-14', status: 'learning', total_reviews: 4,
    total_correct: 1, consecutive_failures: 2, is_difficult: true,
    created_at: '2026-11-01T00:00:00+00:00', updated_at: '2026-11-01T00:00:00+00:00',
  },
];
const planCases = plans.map((plan) => {
  const state = planFsrs.resolvePlanFsrsState(plan, null, now);
  const outcomes = planFsrs.schedulePlanAllRatings(plan, null, now).map((value) => ({
    rating: value.rating,
    passed: value.passed,
    intervalDays: value.intervalDays,
    nextReviewDate: value.nextReviewDate,
    intervalMinutes: value.intervalMinutes,
    fsrs: value.fsrs,
    easeFactor: value.easeFactor,
    phase: value.phase,
  }));
  return {
    plan,
    nowMs: now,
    state,
    previews: planFsrs.planRatingPreviews(plan, null, now),
    outcomes,
  };
});

const items = [];
const cardIds = [];
for (let card = 0; card < 32; card += 1) {
  const reviews = [
    [3, 0], [3, 2],
    [card % 6 === 0 ? 1 : card % 4 === 0 ? 4 : 3, 3 + card % 7],
    [card % 5 === 0 ? 2 : card % 3 === 0 ? 1 : 4, 1 + (card * 3) % 11],
    [card % 7 === 0 ? 1 : 3, 2 + (card * 5) % 17],
  ];
  for (let length = 2; length <= reviews.length; length += 1) {
    items.push({ reviews: reviews.slice(0, length).map(([rating, delta_t]) => ({ rating, delta_t })) });
    cardIds.push(10_000 + card);
  }
}
const current = await native.fsrs.evaluate(scheduler.DEFAULT_PARAMETERS, items);
const optimized = await native.fsrs.optimize({
  trainSet: items, cardIds, enableShortTerm: true, numRelearningSteps: null,
});
const optimizedEval = await native.fsrs.evaluate(optimized, items);

mkdirSync(outDir, { recursive: true });
saveFixture('scheduler-vectors.json', `${JSON.stringify({
  oracle: lock.desktopOracle.commit,
  timezoneOffsetMinutes: SHANGHAI,
  nowMs: now,
  cases: schedulerCases,
  planCases,
  fuzzFactors: [
    ['card-1', 3], ['fsrs_卡片_é', 0], ['x\uD800y', -1], ['', 2147483647], ['🃏', 7],
  ].map(([id, reps]) => ({ id, reps, expected: scheduler.fuzzFactorFor(id, reps) })),
  hardDelays: [
    { steps: [10], index: 0, expected: scheduler.hardDelay([10], 0) },
    { steps: [2880], index: 0, expected: scheduler.hardDelay([2880], 0) },
    { steps: [1, 10, 60], index: 2, expected: scheduler.hardDelay([1, 10, 60], 2) },
  ],
}, null, 2)}\n`);
saveFixture('training-vectors.json', `${JSON.stringify({
  oracle: lock.desktopOracle.commit,
  engine: lock.engine,
  items,
  cardIds,
  enableShortTerm: true,
  numRelearningSteps: null,
  current: { logLoss: current.logLoss, rmseBins: current.rmseBins },
  optimized: { logLoss: optimizedEval.logLoss, rmseBins: optimizedEval.rmseBins },
  params: optimized,
}, null, 2)}\n`);
console.info(`[fsrs-oracle] desktop=${lock.desktopOracle.commit} schedulerCases=${schedulerCases.length} planCases=${planCases.length} items=${items.length}`);
