import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { artifact, main, parse, ts, visit, value, prop, location, sourceFiles, desktop, readDesktop, sha256, assertUnique, log } from './lib.mjs';

export const domains = {
  'agent-orchestration': ['features/chat', 'S3.22'], 'agent-skills': ['features/chat', 'S3.20'], 'agent-workspace': ['features/chat', 'S3.19'],
  anki: ['features/study', 'S7.08'], backup: ['core/interchange', 'S8.18'], browser: ['features/workspace', 'S6.37'], chat: ['features/chat', 'S3.01'],
  'chat-pipeline': ['features/chat', 'S3.03'], chatanki: ['features/study', 'S7.08'], ilink: ['features/system', 'S6.36'], integration: ['features/system', 'S6.36'],
  llm: ['features/chat', 'S3.02'], 'llm-providers': ['features/chat', 'S3.04'], mcp: ['features/terminal', 'S6.28'], media: ['features/knowledge', 'S7.31'], memory: ['features/chat', 'S3.24'],
  'mobile-compat': ['features/system', 'S2.28'], notes: ['features/knowledge', 'S5.07'], 'plugin-workshop': ['features/terminal', 'S6.36'], qbank: ['features/study', 'S7.18'],
  'plugin-services': ['features/system', 'S6.35'], review: ['features/study', 'S4.29'], sync: ['core/interchange', 'S8.01'], 'sync-providers': ['core/interchange', 'S8.02'], 'sync-support': ['core/interchange', 'S2.26'],
  translation: ['features/knowledge', 'S7.32'], vfs: ['core/data', 'S2.06'], 'vfs-handlers': ['features/knowledge', 'S7.01'], 'vfs-index': ['features/knowledge', 'S4.24'], 'vfs-repos': ['core/data', 'S2.01'],
};
const lanes = {
  'F1-kernel': ['core/runtime', 'S2.16'], 'F2-shell': ['features/system', 'S2.28'], 'F3-storage': ['core/data', 'S2.01'], 'F4-security': ['core/security', 'S2.13'],
  'L01-llm-providers': domains['llm-providers'], 'L02-llm-manager': domains.llm, 'L04-chat-session': domains.chat,
  'L05-agent-workspace': domains['agent-workspace'], 'L06-agent-orchestration': domains['agent-orchestration'], 'L07-agent-skills-ptc': domains['agent-skills'],
  'L10-notes': domains.notes, 'L12-vfs-index': domains['vfs-index'], 'L13-vfs-api': domains.vfs, 'L14-qbank': domains.qbank, 'L15-review-analytics': domains.review,
  'L16-anki': domains.anki, 'L17-memory-research': domains.memory, 'L18-media-docs': domains.media, 'L19-mcp-browser': domains.mcp,
  'L20-sync': domains.sync, 'L21-backup-governance': domains.backup, 'L23-sync-providers': domains['sync-providers'], 'L24-chatanki': domains.chatanki,
};
const testCase = (id, module, unit, sourceTests) => ({ id, module, deliveryUnit: unit, sourceTests, operations: ['valid input → expected result and repository delta', 'invalid input → typed error without mutation', 'cancel/restart → persisted receipt and resource release'], android: 'Kotlin port executes the captured command/schema; domain tests compare the same input and expected output', crossDevice: 'portable record/asset changes round-trip; local handles/leases remain device-owned' });

/**
 * Desktop adoption follows the real official profile, not a filename. Starting from host/profiles/desktop.json,
 * follow static TypeScript imports/re-exports inside the frozen desktop tree. The mobile profile is never a root.
 * `mobile.ts` and mobile-only standalone entries stay audited, but are adopted only when a desktop root reaches them.
 */
function desktopReachability(files) {
  const profile = JSON.parse(readDesktop('host/profiles/desktop.json'));
  const mobileProfile = JSON.parse(readDesktop('host/profiles/mobile.json'));
  const tracked = new Set(files);
  const roots = profile.plugins
    .map(entry => resolve(desktop, 'host/profiles', entry.module).replaceAll('\\', '/'))
    .map(file => file.slice(desktop.length + 1))
    .filter(file => tracked.has(file));
  assert.ok(roots.length > 0, 'host/profiles/desktop.json must provide at least one tracked host root');
  const mobileRoots = new Set(mobileProfile.plugins
    .map(entry => resolve(desktop, 'host/profiles', entry.module).replaceAll('\\', '/'))
    .map(file => file.slice(desktop.length + 1)));
  const reachable = new Set();
  const queue = [...roots];
  const resolveImport = (file, specifier) => {
    if (!specifier.startsWith('.')) return null;
    const base = resolve(desktop, dirname(file), specifier).replaceAll('\\', '/');
    for (const candidate of [base, base.replace(/\.js$/, '.ts'), `${base}.ts`, `${base}/index.ts`]) {
      const relative = candidate.slice(desktop.length + 1);
      if (tracked.has(relative)) return relative;
    }
    return null;
  };
  while (queue.length > 0) {
    const file = queue.shift();
    if (reachable.has(file)) continue;
    reachable.add(file);
    if (!tracked.has(file)) continue;
    const source = parse(file);
    visit(source, node => {
      if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && ts.isStringLiteralLike(node.moduleSpecifier)) {
        const target = resolveImport(file, node.moduleSpecifier.text);
        if (target !== null) queue.push(target);
      } else if (ts.isCallExpression(node) && node.arguments.length === 1 && ts.isStringLiteralLike(node.arguments[0])
        && (ts.isIdentifier(node.expression) ? ['require', 'import'].includes(node.expression.text) : node.expression.kind === ts.SyntaxKind.ImportKeyword)) {
        const target = resolveImport(file, node.arguments[0].text);
        if (target !== null) queue.push(target);
      } else if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'URL'
        && node.arguments?.[0] !== undefined && ts.isStringLiteralLike(node.arguments[0])) {
        const target = resolveImport(file, node.arguments[0].text);
        if (target !== null) queue.push(target);
      }
    });
  }
  return { roots, mobileRoots, reachable };
}

const adoptionItems = [
  {
    id: 'plugin-services-secrets', area: 'plugin-services', module: 'features/system', deliveryUnit: 'S6.35', relatedUnits: ['S2.13'],
    services: ['pluginSecrets'], files: ['host/plugins/plugin-services/api.ts', 'host/plugins/plugin-services/src/caller.ts', 'host/plugins/plugin-services/src/secrets.ts'],
    behavior: 'get/set/delete secrets isolated by the calling plugin id, under secret.plugin.<pluginId>.<key>; empty set deletes and non-plugin callers are rejected',
    tests: ['host/plugins/plugin-services/tests/plugin-services.test.ts'],
    android: 'store per-owner credential records in Keystore-backed storage; owner isolation and delete semantics survive restart, pairing never copies trust or installation',
  },
  {
    id: 'plugin-services-notify', area: 'plugin-services', module: 'features/system', deliveryUnit: 'S6.35', relatedUnits: ['S7.27', 'S6.34'],
    services: ['pluginNotify'], files: ['host/plugins/plugin-services/api.ts', 'host/plugins/plugin-services/src/notify.ts', 'src/ui-kernel/community.ts'],
    behavior: 'notify(title, body, view?, params?, silent?) goes through the system notification policy and opens the owner plugin view on click; policy suppression returns delivered=false',
    tests: ['host/plugins/plugin-services/tests/plugin-services.test.ts', 'src/ui-kernel/__tests__/notification-action.test.ts'],
    android: 'notification channel plus deep link into the owning plugin view; suppressed/ unavailable results are returned, cancellation and uninstall remove the click target',
  },
  {
    id: 'plugin-services-schedule', area: 'plugin-services', module: 'features/system', deliveryUnit: 'S6.35', relatedUnits: ['S2.22', 'S3.25'],
    services: ['pluginSchedule'], files: ['host/plugins/plugin-services/api.ts', 'host/plugins/plugin-services/src/schedule.ts'],
    behavior: 'daily/weekly/interval/startup jobs, one run at a time, local-time slots, one catch-up after a missed daily or weekly slot, persisted last/since state, owner cancel on unload',
    tests: ['host/plugins/plugin-services/tests/plugin-services.test.ts'],
    android: 'map jobs into the WorkManager-backed task ledger with unique work names; missed-run policy, no overlap, uninstall cancellation and notification/event release stay owner-scoped',
  },
  {
    id: 'plugin-services-study-data', area: 'plugin-services', module: 'features/system', deliveryUnit: 'S6.35', relatedUnits: ['S7.37', 'S7.18', 'S4.29', 'S7.08', 'S5.07'],
    services: ['studyData'], files: ['host/plugins/plugin-services/api.ts', 'host/plugins/plugin-services/src/study-data-types.ts', 'host/plugins/plugin-services/src/study-data.ts'],
    behavior: 'JSON facade over notes, cards, question sets/questions, FSRS review stats/due/enqueue, todos and resource search/readText; writes use the same repositories, indexes and change events as official UI',
    tests: ['host/plugins/plugin-services/tests/study-data.test.ts'],
    android: 'consume official Kotlin repositories and synchronization paths, not plugin-only tables; preserve cursors, optimistic conflicts, list limits and STUDY_DATA_INVALID/NOT_FOUND/UNAVAILABLE errors',
  },
  {
    id: 'plugin-services-study-events', area: 'plugin-services', module: 'features/system', deliveryUnit: 'S6.35', relatedUnits: ['S3.25', 'S4.29', 'S7.18', 'S5.07'],
    services: [], files: ['host/plugins/plugin-services/api.ts', 'host/plugins/plugin-services/src/study-data.ts', 'host/plugins/review/src/commands.ts', 'host/plugins/chat-pipeline/src/execution-runtime.ts', 'host/plugins/anki/src/plugin-handlers-rest.ts'],
    events: ['study/note-saved', 'study/card-reviewed', 'study/answer-finished', 'study/cards-created'],
    behavior: 'study events are emitted after the action completes in a microtask; listener failures are logged without affecting the producer, and subscriptions end with the plugin owner',
    tests: ['host/plugins/plugin-services/tests/plugin-services.test.ts', 'host/plugins/plugin-services/tests/study-data.test.ts'],
    android: 'emit only after repository commit and publish the event payload fields cardId/cardStateId, sessionId/messageId/content/question/durationMs, cardIds/documentId without blocking official writes',
  },
  {
    id: 'plugin-agent-tools', area: 'plugin-tools', module: 'features/chat', deliveryUnit: 'S3.17', relatedUnits: ['S6.35', 'S7.37', 'S3.20'],
    services: [], files: ['host/plugins/agent-skills/src/tools/plugin-tool-executor.ts', 'host/kernel/plugin-agent-tools.ts', 'host/kernel/plugin-agent-tool-context.ts', 'host/plugins/chat-pipeline/src/ambient-tools.ts'],
    tools: ['plugin_<digest>_<name>'],
    behavior: 'manifest agentTools are offered as plugin_<digest>_<name>, resolved through the owner service per call, serial and medium/high sensitivity, plain-JSON arguments/results, declared net hosts only, and withdrawn on disable or uninstall',
    tests: ['host/plugins/agent-skills/tests/plugin-tool-executor.test.ts', 'host/kernel/tests/plugin-agent-tools.test.ts', 'host/plugins/chat-pipeline/tests/ambient-tools.test.ts'],
    android: 'register plugin tools in ToolDispatcher with the same namespaced IDs, capability checks, cancellation, result budget and approval policy; no plugin may impersonate a built-in or MCP tool',
  },
  {
    id: 'plugin-external-data-taint', area: 'plugin-tools', module: 'features/chat', deliveryUnit: 'S3.17', relatedUnits: ['S6.35', 'S7.37'],
    services: [], files: ['host/plugins/agent-skills/src/tools/external-data-taint.ts', 'host/plugins/agent-skills/src/tools/plugin-tool-executor.ts', 'host/plugins/chat-pipeline/src/approval-gate.ts'],
    behavior: 'a successful plugin tool result marks the turn as carrying external data and wraps the model-visible value as data; the next high-sensitivity call is re-approved regardless of a remember or trusted-automation preset',
    tests: ['host/plugins/chat-pipeline/tests/external-data-approval.test.ts', 'host/plugins/agent-skills/tests/plugin-tool-executor.test.ts'],
    android: 'carry the taint bit in the turn/task record and force approval at the shared ToolDispatcher gate; expiry, cancel and restart must not silently clear it before the turn ends',
  },
  {
    id: 'plugin-workshop-lifecycle', area: 'plugin-workshop', module: 'features/terminal', deliveryUnit: 'S6.36', relatedUnits: ['S6.35', 'S7.37'],
    services: ['pluginWorkshop'], files: ['host/plugins/plugin-workshop/api.ts', 'host/plugins/plugin-workshop/index.ts', 'host/plugins/plugin-workshop/src/operations.ts', 'host/plugins/plugin-workshop/src/store.ts'],
    commands: ['plugin:host|call'], remoteMethods: ['developmentSkill', 'bindSession', 'propose', 'list', 'status', 'create', 'cancel', 'validate', 'install', 'revoke', 'restore', 'export', 'reportFault', 'discard', 'redo', 'reduce'],
    behavior: 'proposal/create/validate/install/revoke/restore/export/discard/redo/reduce/fault recovery retain per-version installation state and full-trust workshop consent',
    tests: ['host/plugins/plugin-workshop/tests/usability.test.ts', 'host/plugins/plugin-workshop/tests/lifecycle-state.test.ts', 'host/plugins/plugin-workshop/tests/managed-install.test.ts'],
    android: 'expose the same user-confirmed lifecycle through Compose; desktop full trust is evidence of upstream behavior only and grants no Android APP/LINUX/SHIZUKU/ROOT capability',
  },
  {
    id: 'plugin-workshop-contract-detection', area: 'plugin-workshop', module: 'features/terminal', deliveryUnit: 'S6.36', relatedUnits: ['S6.35', 'S3.17'],
    services: [], files: ['host/plugins/plugin-workshop/src/detect.ts', 'host/plugins/plugin-workshop/src/declared.ts', 'host/plugins/plugin-workshop/src/service-docs.ts', 'host/plugins/plugin-workshop/src/settings.ts'],
    behavior: 'consent cards describe detected studyData reads/writes, study events, pluginSchedule times, pluginSecrets use, network hosts and node modules; card settings keep secret values out of workshop.json',
    tests: ['host/plugins/plugin-workshop/tests/detect.test.ts', 'host/plugins/plugin-workshop/tests/card-consent-settings.test.ts', 'host/plugins/plugin-workshop/tests/service-wiring.test.ts'],
    android: 'derive the same capability disclosure from the installed package and bind it to content digest approval; never persist a secret on the proposal card or export',
  },
  {
    id: 'plugin-settings-data-ui', area: 'plugin-workshop', module: 'features/system', deliveryUnit: 'S6.35', relatedUnits: ['S7.36', 'S0.24'],
    services: [], files: ['src/features/plugin-manager/PluginDataSection.tsx', 'src/features/plugin-manager/PluginDetail.tsx', 'src/features/settings/components/plugins/PluginsTab.tsx'],
    behavior: 'plugin detail exposes ordinary config, secret config, owner data and install/trust as separate surfaces without copying installation state into portable records',
    tests: ['src/features/plugin-manager/__tests__/plugin-data.test.ts'],
    android: 'keep data inspection/clearing separate from installation and trust; clearing creates replayable tombstones while secrets remain owner-scoped device records',
  },
  {
    id: 'review-plan-rating-previews', area: 'ipc-contract', module: 'features/study', deliveryUnit: 'S4.29', relatedUnits: ['S7.24'],
    services: [], files: ['contracts/ipc/L15-review-analytics.json', 'host/plugins/vfs-repos/types/content.ts', 'host/plugins/review/src/review-plan.ts', 'host/plugins/review/src/review-plan-fsrs.ts', 'host/plugins/review/src/review-plan-commands.ts'],
    contracts: ['review_plan_get', 'review_plan_list_by_exam', 'review_plan_get_by_question'], fields: ['rating_previews', 'rating_previews.again', 'rating_previews.hard', 'rating_previews.good', 'rating_previews.easy', 'rating_previews.*.interval_minutes', 'rating_previews.*.next_review_date'],
    behavior: 'ReviewPlan carries optional FSRS four-rating interval previews computed by the same scheduler as flashcards; SM-2 rows are converted lazily on preview/rating',
    tests: ['host/plugins/review/tests/review-plan-fsrs.test.ts', 'src/components/review/__tests__/reviewRatingPreview.test.ts'],
    android: 'parse and preserve rating_previews on ReviewPlan; four rating previews and question-bank plan scheduling use the S4.29 scheduler and match the desktop minutes/date values',
  },
  {
    id: 'media-companion-ipc', area: 'ipc-contract', module: 'features/knowledge', deliveryUnit: 'S7.31', relatedUnits: ['S4.29', 'S3.01'],
    services: [], files: ['contracts/ipc/L18-media-docs.json', 'host/plugins/media/src/register-companion.ts', 'host/plugins/media/src/companion-ask.ts'],
    contracts: ['media_companion_ask', 'media_companion_cancel', 'media_study_cards', 'media_companion_ask_delta'], fields: ['requestId', 'resourceId', 'answer', 'model', 'coverage', 'usedSegments', 'totalSegments', 'finishReason', 'interrupted', 'seq', 'delta', 'MediaStudyCard.id', 'MediaStudyCard.front', 'MediaStudyCard.back', 'MediaStudyCard.seconds', 'MediaStudyCard.createdAt'],
    behavior: 'media companion asks against a resource and streams ordered deltas; cancel is request-scoped; study cards are paged by limit/offset and preserve the source timestamp',
    tests: ['host/plugins/media/tests/companion-ask.test.ts', 'host/plugins/media/tests/companion-commands.test.ts'],
    android: 'keep requestId/resourceId ownership through cancel/restart, order deltas by seq, and persist card seconds without inventing a generated timestamp',
  },
];

const newContractFields = [
  { contract: 'contracts/ipc/L15-review-analytics.json', lane: 'L15-review-analytics', path: '$defs.ReviewPlan.rating_previews', unit: 'S4.29', module: 'features/study', adoptionItem: 'review-plan-rating-previews', kind: 'field' },
  { contract: 'contracts/ipc/L15-review-analytics.json', lane: 'L15-review-analytics', path: '$defs.ReviewPlanRatingPreview', unit: 'S4.29', module: 'features/study', adoptionItem: 'review-plan-rating-previews', kind: 'definition' },
  { contract: 'contracts/ipc/L15-review-analytics.json', lane: 'L15-review-analytics', path: '$defs.ReviewPlanRatingPreviews', unit: 'S4.29', module: 'features/study', adoptionItem: 'review-plan-rating-previews', kind: 'definition' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: 'commands.media_companion_ask', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'command' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: 'commands.media_companion_cancel', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'command' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: 'commands.media_study_cards', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'command' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: 'events.media_companion_ask_delta', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'event' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: '$defs.MediaCompanionAnswer', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'definition' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: '$defs.MediaCompanionAskDelta', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'definition' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: '$defs.MediaCompanionTurn', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'definition' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: '$defs.MediaStudyCard', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'definition' },
  { contract: 'contracts/ipc/L18-media-docs.json', lane: 'L18-media-docs', path: '$defs.MediaStudyCardList', unit: 'S7.31', module: 'features/knowledge', adoptionItem: 'media-companion-ipc', kind: 'definition' },
];

function contractSchema(entry) {
  const definition = JSON.parse(readDesktop(entry.contract));
  const [scope, ...rest] = entry.path.split('.');
  if (scope === '$defs' && rest.length === 1) {
    const schema = definition.$defs?.[rest[0]];
    assert.ok(schema, `missing contract definition ${entry.contract}:${entry.path}`);
    return schema;
  }
  if (scope === '$defs' && rest.length === 2) {
    const parent = definition.$defs?.[rest[0]];
    const schema = parent?.properties?.[rest[1]];
    assert.ok(schema, `missing contract field ${entry.contract}:${entry.path}`);
    return schema;
  }
  if (scope === 'commands' && rest.length === 1) {
    const command = definition.commands.find(item => item.name === rest[0]);
    assert.ok(command, `missing contract command ${entry.contract}:${entry.path}`);
    return command;
  }
  if (scope === 'events' && rest.length === 1) {
    const event = definition.events.find(item => item.name === rest[0]);
    assert.ok(event, `missing contract event ${entry.contract}:${entry.path}`);
    return event;
  }
  assert.fail(`unsupported contract lookup ${entry.contract}:${entry.path}`);
}

export function collect() {
  const files = sourceFiles(desktop, 'host/plugins').filter(f => /\.ts$/.test(f));
  const production = files.filter(f => !/(?:\/(?:tests|__tests__|equivalence)\/|\.test\.|\.spec\.)/.test(f));
  // The graph must span kernel/platform too: plugin imports routinely pass through those host modules.
  const hostFiles = sourceFiles(desktop, 'host').filter(f => /\.ts$/.test(f));
  const { reachable, mobileRoots } = desktopReachability(hostFiles);
  const explicitMobilePath = file => /(?:^|\/)src\/mobile\//.test(file)
    || /(?:^|\/)mobile(?:-[\w-]+)?\.ts$/.test(file)
    || /(?:^|\/)register-mobile-[\w-]+\.ts$/.test(file);
  const sourceRole = file => {
    if (reachable.has(file)) {
      return {
        adoption: 'desktop-adopted', category: 'desktop-production',
        role: /(?:^|\/)portable(?:-[\w-]+)?\.ts$|\/src\/mobile\//.test(file)
          ? 'desktop-reachable portable source; frozen desktop behavior is the adoption baseline'
          : 'desktop-reachable production source; frozen desktop behavior is the adoption baseline',
      };
    }
    if (mobileRoots.has(file) || explicitMobilePath(file)) {
      return {
        adoption: 'legacy-mobile-evidence', category: 'legacy-mobile-evidence',
        role: 'explicit mobile-profile entry or mobile-named path and not desktop-reachable; retained as legacy/mobile evidence',
      };
    }
    return {
      adoption: 'desktop-unwired', category: 'desktop-unwired',
      role: 'not statically reachable from host/profiles/desktop.json; classification pending an explicit desktop entry, not treated as mobile-only or a completion requirement',
    };
  };
  const sourceCategory = file => sourceRole(file).category;
  const plugins = Object.keys(domains).sort().map(id => {
    const owned = production.filter(file => file.startsWith(`host/plugins/${id}/`));
    assert.ok(owned.length > 0, `domain ${id}`);
    const tests = files.filter(f => f.startsWith(`host/plugins/${id}/`) && /(?:\/tests\/|\/__tests__\/|\.test\.|\.spec\.)/.test(f));
    const [module, unit] = domains[id];
    const adopted = owned.filter(file => sourceCategory(file) === 'desktop-production');
    return { id, module, deliveryUnit: unit, sourceFiles: owned.length, sourceTests: tests, desktopEntry: owned.find(f => f === `host/plugins/${id}/index.ts`) ?? null, api: owned.find(f => f === `host/plugins/${id}/api.ts`) ?? null, services: [], commandRegistrations: [], tools: [], backgroundTasks: [], metadata: [], sourceEvidence: { desktopAdopted: adopted.length, legacyMobileEvidence: owned.length - adopted.length }, behaviorCase: testCase(`DOMAIN-${id}`, module, unit, tests) };
  });
  const unknown = new Set(production.map(f => f.split('/')[2]).filter(id => !domains[id]));
  assert.deepEqual([...unknown], [], 'every host/plugins directory needs Kotlin ownership');
  const services = [], registrations = [], tools = [], background = [], sourceIndex = [];
  for (const file of production) {
    const owner = plugins.find(p => p.id === file.split('/')[2]);
    const evidence = sourceRole(file);
    const sf = parse(file);
    const fileSymbols = [];
    visit(sf, node => {
      if (ts.isObjectLiteralExpression(node) && prop(node, 'provides') && prop(node, 'id')) owner.metadata.push({ definition: value(node), evidence: location(file, sf, node) });
      if (ts.isInterfaceDeclaration(node) && /(?:Service|Port|Runtime|Repository|Executor|Worker|Task)/.test(node.name.text)) {
        const item = { id: `${owner.id}/${node.name.text}`, owner: owner.id, name: node.name.text, signatures: node.members.map(m => m.getText(sf)), evidence: location(file, sf, node), module: owner.module, deliveryUnit: owner.deliveryUnit, sourceEvidence: evidence, behaviorCase: `DOMAIN-${owner.id}` };
        services.push(item); owner.services.push(item.id); fileSymbols.push(item.name);
      }
      if (ts.isClassDeclaration(node) && node.name && /(?:ToolExecutor|Executor)$/.test(node.name.text)) {
        const routing = node.members.filter(m => ['name', 'canHandle', 'schemaTools', 'sensitivityLevel', 'concurrencyClass', 'execute'].includes(m.name?.getText())).map(m => ({ name: m.name.getText(), implementation: m.name.getText() === 'execute' ? { sha256: sha256(m.getText(sf)), evidence: location(file, sf, m), parameters: m.parameters.map(p => p.getText(sf)) } : m.getText(sf) }));
        const item = { id: `${owner.id}/${node.name.text}`, owner: owner.id, className: node.name.text, routing, evidence: location(file, sf, node), module: owner.module, deliveryUnit: owner.deliveryUnit, sourceEvidence: evidence, behaviorCase: `DOMAIN-${owner.id}` };
        tools.push(item); owner.tools.push(item.id); fileSymbols.push(item.className);
      }
      if (!ts.isCallExpression(node) && !ts.isNewExpression(node)) return;
      const name = node.expression.getText(sf);
      if (/\.commands\.register$/.test(name)) {
        const arg = node.arguments[0];
        const command = value(arg);
        const item = { id: `${owner.id}:${file}:${location(file, sf, node).line}`, owner: owner.id, name: command, handler: node.arguments[1] === undefined ? null : { sha256: sha256(node.arguments[1].getText(sf)), evidence: location(file, sf, node.arguments[1]), parameters: node.arguments[1].parameters?.map(p => p.getText(sf)) ?? [], sourceLength: node.arguments[1].getText(sf).length }, typeArguments: node.typeArguments?.map(arg => arg.getText(sf)) ?? [], evidence: location(file, sf, node), module: owner.module, deliveryUnit: owner.deliveryUnit, sourceEvidence: evidence, behaviorCase: `DOMAIN-${owner.id}` };
        registrations.push(item); owner.commandRegistrations.push(item.id);
      }
      if (name === 'diagnosticTool' || /registerToolExecutors$/.test(name)) {
        const item = { id: `${owner.id}:tool-registration:${file}:${location(file, sf, node).line}`, owner: owner.id, call: node.getText(sf), evidence: location(file, sf, node), module: owner.module, deliveryUnit: owner.deliveryUnit, sourceEvidence: evidence, behaviorCase: `DOMAIN-${owner.id}` };
        tools.push(item); owner.tools.push(item.id);
      }
      if (/\.provide$/.test(name)) {
        const item = { id: `${owner.id}:provide:${file}:${location(file, sf, node).line}`, owner: owner.id, name: value(node.arguments[0]), implementation: node.arguments[1]?.getText(sf) ?? null, evidence: location(file, sf, node), module: owner.module, deliveryUnit: owner.deliveryUnit, sourceEvidence: evidence, behaviorCase: `DOMAIN-${owner.id}` };
        services.push(item); owner.services.push(item.id);
      }
      if (/(?:^|\.)(?:setInterval|setTimeout|Worker)$|(?:createWorkerPool|schedule\w*|register\w*(?:Sweep|Scheduler|Task)|registerMaintenanceParticipant)$/.test(name)) {
        const item = { id: `${owner.id}:task:${file}:${location(file, sf, node).line}`, owner: owner.id, operation: name, call: node.getText(sf), evidence: location(file, sf, node), module: owner.module, deliveryUnit: owner.deliveryUnit, runtimeModule: 'core/runtime', lifecycle: 'application task with persistent receipt; cancel through TaskSupervisor; notification/lease by task type', sourceEvidence: evidence, behaviorCase: `DOMAIN-${owner.id}` };
        background.push(item); owner.backgroundTasks.push(item.id);
      }
    });
    sourceIndex.push({ file, sha256: sha256(readDesktop(file)), owner: owner.id, module: owner.module, category: sourceCategory(file), ...evidence, symbols: fileSymbols });
  }
  const adoptedSources = sourceIndex.filter(item => item.adoption === 'desktop-adopted');
  const mobileEvidenceSources = sourceIndex.filter(item => item.adoption === 'legacy-mobile-evidence');
  const unwiredSources = sourceIndex.filter(item => item.adoption === 'desktop-unwired');
  const nonAdoptedSources = [...mobileEvidenceSources, ...unwiredSources];
  const ipc = [];
  const contractFiles = sourceFiles(desktop, 'contracts/ipc').filter(f => /^contracts\/ipc\/[^/]+\.json$/.test(f));
  for (const file of contractFiles) {
    const definition = JSON.parse(readDesktop(file));
    if (file.endsWith('/_shared.json')) continue;
    const laneTarget = lanes[definition.lane];
    assert.ok(laneTarget, `unmapped contract lane ${definition.lane}`);
    for (const command of definition.commands) {
      const registered = registrations.filter(r => r.name === command.name);
      const ownerIds = [...new Set(registered.map(r => r.owner))];
      const [module, unit] = ownerIds.length === 1 ? domains[ownerIds[0]] : laneTarget;
      const implementations = registered.map(r => r.evidence);
      const assignmentBasis = ownerIds.length === 1 ? 'actual production command owner' : 'contract lane with explicit command-family refinement';
      let targetModule = module, targetUnit = unit;
      if (ownerIds.length !== 1 && definition.lane === 'L19-mcp-browser') {
        if (command.name.startsWith('browser_')) { targetModule = 'features/workspace'; targetUnit = 'S6.37'; }
        else if (/setting|feature_flag|security_status/.test(command.name)) { targetModule = 'features/system'; targetUnit = 'S7.35'; }
        else if (/search|network_probe/.test(command.name)) { targetModule = 'core/network'; targetUnit = 'S3.08'; }
      }
      ipc.push({ id: `${definition.lane}/${command.name}`, name: command.name, lane: definition.lane, definition: command, source: { file, sha256: sha256(readDesktop(file)) }, implementations, owners: ownerIds, assignmentBasis, module: targetModule, deliveryUnit: targetUnit, behaviorCase: { id: `IPC-${command.name}`, module: targetModule, deliveryUnit: targetUnit, domainCases: plugins.filter(p => ownerIds.includes(p.id) || p.module === targetModule).map(p => p.behaviorCase.id), input: 'definition.args schemas; missing/null/invalid boundaries', expected: 'definition.result or definition.error; exact repository delta and resource references', acceptance: ['normal', 'invalid input', 'cancel/restart', 'cross-device ownership'] } });
    }
  }
  const contractFields = newContractFields.map(entry => ({
    ...entry, adoption: 'desktop-adopted', schema: contractSchema(entry),
    evidence: { source: 'desktop', file: entry.contract, line: 1 },
  }));
  const sourceEvidence = {
    desktopAdopted: adoptedSources.length, legacyMobileEvidence: mobileEvidenceSources.length,
    categories: Object.fromEntries(['desktop-production', 'legacy-mobile-evidence', 'desktop-unwired'].map(category => [category, sourceIndex.filter(item => item.category === category).length])),
    classified: { desktopProduction: adoptedSources.length, legacyMobileEvidence: mobileEvidenceSources.length, desktopUnwired: unwiredSources.length },
  };
  assertUnique(ipc, r => r.id);
  assert.ok(ipc.length > 1000 && registrations.length > 800 && services.length > 100 && tools.length > 50);
  const collectorFile = fileURLToPath(import.meta.url);
  return { formatVersion: 2, unit: 'S0.05', sourceLock: { profile: 'host/profiles/desktop.json', profileSha256: sha256(readDesktop('host/profiles/desktop.json')), collector: 'tools/baseline/domains.mjs', collectorSha256: sha256(readFileSync(collectorFile)), desktopCommit: 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87' }, adoption: { baseline: 'desktop', desktopCommit: 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87', upstreamMobileChain: 'not-adopted', rule: 'desktop reachability starts at host/profiles/desktop.json and follows the entire tracked host TS graph; only desktop-adopted is a requirement, explicit mobile evidence is retained, and desktop-unwired is not called mobile-only', items: adoptionItems.map(item => ({ ...item, sourceEvidence: 'desktop-adopted', behaviorCase: `ADOPTION-${item.id}`, contractFields: contractFields.filter(field => field.adoptionItem === item.id).map(field => field.path) })) }, plugins, services, commands: ipc, commandRegistrations: registrations, tools, backgroundTasks: background, contracts: contractFiles.map(file => ({ file, sha256: sha256(readDesktop(file)), sharedDefinitions: file.endsWith('_shared.json') ? JSON.parse(readDesktop(file)) : null })), contractFields, sourceIndex, sourceEvidence, coverage: { productionFiles: production.length, indexedFiles: sourceIndex.length, pluginDirectories: plugins.length, contractLanes: Object.keys(lanes).length, desktopAdoptedFiles: adoptedSources.length, legacyMobileEvidenceFiles: mobileEvidenceSources.length, desktopUnwiredFiles: unwiredSources.length, adoptionItems: adoptionItems.length, contractFields: contractFields.length }, execution: 'Kotlin UseCase/Repository → RuntimeController/ToolDispatcher; TypeScript and Cordis are source references only' };
}
export function run(mode) {
  const data = collect();
  artifact('config/baseline/domains.json', data, mode);
  log('domains.accepted', { plugins: data.plugins.length, services: data.services.length, commands: data.commands.length, registrations: data.commandRegistrations.length, tools: data.tools.length, tasks: data.backgroundTasks.length, files: data.sourceIndex.length });
}
main(import.meta.url, run);
