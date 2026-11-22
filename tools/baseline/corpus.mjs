import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  artifact, desktop, desktopRequire, git, log, main, parse, root, sha256, ts, visit,
} from './lib.mjs';
import { loadDesktopModule } from './desktop-module.mjs';

const FORMAT = 'dstu-s0.17-corpus/v1';
const UNIT = 'S0.17';
const MARKDOWN_SAMPLE_LIMIT = 150;
const MARKDOWN_MIN = 100;
const SOURCE_ROOTS = [
  'src/components/crepe',
  'src/features/notes',
  'host/plugins/notes/tests',
];
const EDITOR_SOURCE_PATTERN = /(?:\.test|\.spec)\.(?:ts|tsx)$/;
const JSON_SAFE = (_key, value) => typeof value === 'bigint' ? value.toString() : value;
const clone = value => JSON.parse(JSON.stringify(value, JSON_SAFE));
const jsonBytes = value => Buffer.from(JSON.stringify(value, JSON_SAFE), 'utf8');
const lineOf = (sf, node) => sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
const gitAt = (cwd, ...args) => git(cwd, ...args);

function bounded(text, limit = 600) {
  return text.length <= limit ? text : `${text.slice(0, limit)}…`;
}

function walkObject(value, fn, path = '$') {
  fn(value, path);
  if (Array.isArray(value)) value.forEach((item, index) => walkObject(item, fn, `${path}[${index}]`));
  else if (value !== null && typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) walkObject(item, fn, `${path}.${key}`);
  }
}

function stableNodes(tree) {
  const rows = [];
  const walk = (node, depth, parentType) => {
    if (node === null || typeof node !== 'object') return;
    const row = {
      type: node.type ?? null,
      depth,
      parentType,
      valueSha256: typeof node.value === 'string' ? sha256(node.value) : null,
      valueLength: typeof node.value === 'string' ? Buffer.byteLength(node.value) : null,
      start: node.position?.start?.offset ?? null,
      end: node.position?.end?.offset ?? null,
    };
    for (const key of ['calloutType', 'calloutTitle', 'calloutCollapsed', 'open', 'title', 'layout', 'target', 'label', 'checked']) {
      if (node[key] !== undefined) row[key] = node[key];
    }
    rows.push(row);
    for (const child of node.children ?? []) walk(child, depth + 1, node.type ?? null);
  };
  walk(tree, 0, null);
  return rows;
}

function substantiveMarkdown(text) {
  if (typeof text !== 'string' || text.length < 3 || text.length > 20_000) return false;
  if (text.includes('\u0000')) return false;
  if (/^\s*(?:import|export|const|let|var|function|return|expect|describe|it|await|throw|new |type |interface )\b/m.test(text)) return false;
  if (/^(?:SELECT|INSERT|UPDATE|DELETE|CREATE|PRAGMA|ALTER|DROP)\b/i.test(text)) return false;
  if (text.trim().length < 2) return false;
  if (/^(?:```|~~~)(?:[A-Za-z0-9_+-]*)$/.test(text.trim())) return false;
  if (/^:::[A-Za-z0-9_{}= .-]+$/.test(text.trim()) && !text.includes('\n')) return false;
  const signals = [
    /^#{1,6} /m, /^>\s/m, /^[-*+] /m, /^\d+\. /m, /^\|.*\|/m, /^```/m,
    /^:::ds-columns/m, /\[\[[^\]]+\]\]/, /> \[![a-z]+\]/i, /\*\*[^*]+\*\*/,
    /!\[[^\]]*\]\(/, /\[[^\]]+\]\([^)]+\)/, /<!-- ds:block-id=/, /\$\$[\s\S]*\$\$/,
    /<br\s*\/>/,
  ];
  return signals.some(regex => regex.test(text));
}

function sourceFilesBounded() {
  return SOURCE_ROOTS.flatMap(scope => gitAt(desktop, 'ls-files', '-z', '--', scope).split('\0'))
    .filter(Boolean)
    .filter(file => EDITOR_SOURCE_PATTERN.test(file))
    .sort();
}

function extractMarkdownSamples() {
  const samples = new Map();
  const files = sourceFilesBounded();
  const sourceHashes = new Map();
  let stringLiterals = 0;
  let candidateLiterals = 0;
  for (const file of files) {
    const absolute = resolve(desktop, file);
    const bytes = readFileSync(absolute);
    const fileHash = sha256(bytes);
    sourceHashes.set(file, fileHash);
    const sf = parse(file);
    const collect = node => {
      if (!ts.isStringLiteral(node) && !ts.isNoSubstitutionTemplateLiteral(node)) return;
      stringLiterals += 1;
      if (!substantiveMarkdown(node.text)) return;
      candidateLiterals += 1;
      const inputSha256 = sha256(Buffer.from(node.text, 'utf8'));
      let entry = samples.get(inputSha256);
      if (entry === undefined) {
        entry = { text: node.text, references: [] };
        samples.set(inputSha256, entry);
      }
      entry.references.push({
        file,
        line: lineOf(sf, node),
        sourceSha256: fileHash,
        expression: node.getText(sf).slice(0, 120),
      });
    };
    visit(sf, collect);
  }
  const ordered = [...samples.entries()]
    .sort(([hashA, a], [hashB, b]) => {
      const refA = a.references[0];
      const refB = b.references[0];
      return refA.file.localeCompare(refB.file) || refA.line - refB.line || hashA.localeCompare(hashB);
    });
  return { files, stringLiterals, candidateLiterals, sourceHashes, samples: ordered };
}

function selectMarkdownSamples(extracted, limit) {
  const all = extracted.samples.map(([hash, entry], index) => ({
    hash,
    entry,
    index,
    features: featureTags(entry.text),
  }));
  const selected = [];
  const seen = new Set();
  const take = row => {
    if (seen.has(row.hash) || selected.length >= limit) return;
    seen.add(row.hash);
    selected.push(row);
  };
  const featureOrder = ['columns', 'toggle', 'callout', 'block-id', 'wikilink', 'inline-card',
    'front-matter', 'crlf', 'math-block', 'math-inline', 'table', 'code-fence', 'task-list',
    'ordered-list', 'list', 'heading', 'blockquote', 'image', 'internal-link', 'html', 'unicode'];
  for (const feature of featureOrder) {
    for (const row of all.filter(candidate => candidate.features.includes(feature))) take(row);
    if (selected.length >= limit) break;
  }
  const sourceFiles = [...new Set(all.map(row => row.entry.references[0].file))];
  for (const file of sourceFiles) {
    for (const row of all.filter(candidate => candidate.entry.references[0].file === file)) take(row);
    if (selected.length >= limit) break;
  }
  for (const row of all) take(row);
  return selected.slice(0, limit);
}

function featureTags(text) {
  const tags = [];
  const add = (tag, regex) => { if (regex.test(text)) tags.push(tag); };
  add('heading', /^#{1,6} /m);
  add('blockquote', /^>\s/m);
  add('list', /^[-*+] /m);
  add('ordered-list', /^\d+\. /m);
  add('task-list', /^\s*[-*+] \[[ xX]\]/m);
  add('table', /^\|.*\|/m);
  add('code-fence', /^```/m);
  add('math-inline', /(?:^|[^$])\$(?!\$)[^$\n]+\$(?!\$)/m);
  add('math-block', /\$\$[\s\S]*\$\$/);
  add('callout', /> \[!(?:note|tip|warning|danger|info)\]/i);
  add('toggle', /> \[!toggle\][+-]?/i);
  add('columns', /^:::ds-columns\{/m);
  add('wikilink', /\[\[[^\]]+\]\]/);
  add('internal-link', /\]\((?:note|pdfref|mediaref|asset):\/\//);
  add('image', /!\[[^\]]*\]\(/);
  add('html', /<!--|<br\s*\/>/);
  add('block-id', /<!-- ds:block-id=/);
  add('inline-card', />>|\{\{[^}]+\}\}/);
  add('front-matter', /^---\r?\n/);
  add('crlf', /\r\n/);
  add('unicode', /[^\x00-\x7f]/);
  return [...new Set(tags)];
}

async function remarkTransform(plugin) {
  let factory;
  const context = {
    wait: async () => {},
    get: () => undefined,
    update: (_key, update) => {
      const result = update([]);
      if (Array.isArray(result) && result.some(row => typeof row?.plugin === 'function')) {
        factory = result.find(row => typeof row?.plugin === 'function').plugin;
      }
      return context;
    },
  };
  await plugin.plugin(context)();
  assert.equal(typeof factory, 'function', 'remark plugin did not register a transformer');
  return factory();
}

function markdownPipeline(api, fromMarkdown, gfmFromMarkdown, gfm, mathFromMarkdown, math, toggleTransform, wikiTransform) {
  const promoteCallouts = node => {
    if (!Array.isArray(node.children) || node.children.length === 0) return;
    for (const child of node.children) {
      if (child.type === 'blockquote') api.promoteBlockquoteToCallout(child);
      promoteCallouts(child);
    }
  };
  return text => {
    const tree = fromMarkdown(text, {
      extensions: [gfm(), math()],
      mdastExtensions: [gfmFromMarkdown(), mathFromMarkdown()],
    });
    promoteCallouts(tree);
    toggleTransform(tree);
    wikiTransform(tree);
    api.parseColumnsTree(tree, text);
    return tree;
  };
}

function normalizeTree(tree) {
  const copy = clone(tree);
  const walk = node => {
    if (node === null || typeof node !== 'object') return;
    delete node.position;
    if ((node.type === 'list' || node.type === 'listItem')) delete node.spread;
    for (const child of node.children ?? []) walk(child);
  };
  walk(copy);
  return copy;
}

function sourceReference(file, line, expression, sourceHashes, kind = 'source-literal') {
  const sourceSha256 = sourceHashes.get(file);
  assert.ok(sourceSha256, `missing source hash for ${file}`);
  return { kind, file, line, sourceSha256, expression };
}

function astCount(tree, type) {
  let count = 0;
  const walk = node => {
    if (node === null || typeof node !== 'object') return;
    if (node.type === type) count += 1;
    for (const child of node.children ?? []) walk(child);
  };
  walk(tree);
  return count;
}

function captureNegativeCases(api, pipeline, sourceHashes) {
  const columnsFile = 'src/components/crepe/plugins/columns/__tests__/columns.test.ts';
  const noteColumnsFile = 'host/plugins/notes/tests/note-columns.test.ts';
  const identityFile = 'src/components/crepe/plugins/blockIdentity/__tests__/roundtrip.test.ts';
  const calloutFile = 'src/components/crepe/plugins/callout/__tests__/callout.test.ts';
  const sourceColumns = (left = 'Left', right = 'Right', layout = 'equal') =>
    `${api.columnsOpen(layout)}\n\n:::column\n\n${left}\n\n:::end-column\n\n:::column\n\n${right}\n\n:::end-column\n\n:::end-ds-columns\n`;
  const base = sourceColumns();
  const derived = [
    ['unknown-version', base.replace('version=1', 'version=2'), 102],
    ['third-column', base.replace(':::end-ds-columns', ':::column\n\nThird\n\n:::end-column\n\n:::end-ds-columns'), 103],
    ['nested', sourceColumns(sourceColumns('Nested Left', 'Nested Right'), 'Outer right'), 104],
    ['missing-end', base.replace(':::end-ds-columns', ''), 105],
    ['escaped-literal', base.replace(':::ds-columns', '\\:::ds-columns'), 106],
    ['quoted', base.split('\n').map(line => `> ${line}`).join('\n'), 107],
    ['code-fence', '```\n' + base + '\n```', 108],
  ].map(([id, text, line]) => {
    const ast = normalizeTree(pipeline(text));
    return {
      id: `neg-columns-${id}`,
      category: `columns-${id}`,
      text,
      input: { bytes: Buffer.byteLength(text), sha256: sha256(Buffer.from(text, 'utf8')), utf8Hex: Buffer.from(text, 'utf8').toString('hex') },
      provenance: {
        kind: 'derived-test-expression',
        primary: sourceReference(columnsFile, line, `it.each: source().replace(...) for ${id}`, sourceHashes, 'derived-test-expression'),
        references: [sourceReference(columnsFile, 47, 'function source(left = Left, right = Right, layout = equal)', sourceHashes)],
        derivation: id === 'nested'
          ? 'source(source(Nested Left, Nested Right), Outer right)'
          : id === 'quoted'
            ? 'source().split(\\n).map(line => `> ${line}`).join(\\n)'
            : id === 'code-fence'
              ? '```\\n + source() + \\n```'
              : `source().replace(...) for ${id}`,
      },
      features: featureTags(text),
      expected: { ast, astSha256: sha256(jsonBytes(ast)), dsColumns: astCount(ast, 'ds_columns'), contains: ['Left', 'Right'] },
      sourceTestExpectation: 'does not upgrade to ds-columns and retains content',
    };
  });

  const unknownSyntax = [
    ['unsupported-code-metadata', 'one\n\n> nested\n\n```js unsupported-metadata\nx\n```', 71],
    ['unknown-html', 'one\n\n> nested\n\n<div>unknown</div>', 71],
  ].map(([id, text, line]) => {
    const ast = normalizeTree(pipeline(text));
    return {
      id: `neg-unknown-${id}`,
      category: `unknown-${id}`,
      text,
      input: { bytes: Buffer.byteLength(text), sha256: sha256(Buffer.from(text, 'utf8')), utf8Hex: Buffer.from(text, 'utf8').toString('hex') },
      provenance: {
        kind: 'source-literal',
        primary: sourceReference(identityFile, line, `rejects unknown full-document suffix: ${id}`, sourceHashes),
        references: [],
      },
      features: featureTags(text),
      expected: { ast, astSha256: sha256(jsonBytes(ast)), nodeTypes: [...new Set(stableNodes(ast).map(row => row.type))] },
      sourceTestExpectation: 'normalizeMarkdown throws before mutation',
    };
  });

  const markerText = '[!todo] later';
  const markerExpected = api.parseCalloutMarker(markerText);
  assert.equal(markerExpected, null, 'unknown callout marker unexpectedly accepted');
  const markers = [{
    id: 'neg-callout-unknown-type',
    category: 'callout-unknown-type',
    input: markerText,
    inputSha256: sha256(Buffer.from(markerText, 'utf8')),
    provenance: {
      kind: 'source-literal',
      primary: sourceReference(calloutFile, 82, "parseCalloutMarker('[!todo] later')", sourceHashes),
    },
    expected: { accepted: false, result: markerExpected },
    sourceTestExpectation: 'rejects unknown types',
  }];

  return { cases: [...derived, ...unknownSyntax], markers };
}

async function captureMarkdown(api) {
  const { fromMarkdown } = await import(pathToFileURL(desktopRequire.resolve('mdast-util-from-markdown')).href);
  const { gfmFromMarkdown } = await import(pathToFileURL(desktopRequire.resolve('mdast-util-gfm')).href);
  const { gfm } = await import(pathToFileURL(desktopRequire.resolve('micromark-extension-gfm')).href);
  const { math } = await import(pathToFileURL(desktopRequire.resolve('micromark-extension-math')).href);
  const { mathFromMarkdown } = await import(pathToFileURL(desktopRequire.resolve('mdast-util-math')).href);
  const toggleTransform = await remarkTransform(api.remarkTogglePlugin);
  const wikiTransform = await remarkTransform(api.remarkWikilinkPlugin);
  const pipeline = markdownPipeline(api, fromMarkdown, gfmFromMarkdown, gfm, mathFromMarkdown, math, toggleTransform, wikiTransform);

  const extracted = extractMarkdownSamples();
  assert.ok(extracted.samples.length >= MARKDOWN_MIN,
    `editor corpus has only ${extracted.samples.length} distinct substantive samples; expected >= ${MARKDOWN_MIN}`);
  const selected = selectMarkdownSamples(extracted, MARKDOWN_SAMPLE_LIMIT);
  const negative = captureNegativeCases(api, pipeline, extracted.sourceHashes);
  const rows = selected.map(({ hash: inputSha256, entry }, index) => {
    const firstTree = pipeline(entry.text);
    const secondTree = pipeline(entry.text);
    const firstCanonical = normalizeTree(firstTree);
    const secondCanonical = normalizeTree(secondTree);
    assert.deepEqual(firstCanonical, secondCanonical, `nondeterministic AST replay for sample ${index}`);
    assert.equal(sha256(jsonBytes(firstCanonical)), sha256(jsonBytes(secondCanonical)));
    const bytes = Buffer.from(entry.text, 'utf8');
    return {
      id: `md-${String(index + 1).padStart(3, '0')}-${inputSha256.slice(0, 12)}`,
      text: entry.text,
      input: {
        bytes: bytes.length,
        sha256: inputSha256,
        utf8Hex: bytes.toString('hex'),
      },
      provenance: {
        kind: 'source-literal',
        primary: entry.references[0],
        references: entry.references,
      },
      features: featureTags(entry.text),
      parser: {
        module: 'mdast-util-from-markdown',
        extensions: ['gfm', 'math', 'remark-ds-columns', 'remark-toggle', 'remark-wikilink'],
        transformOrder: ['callout', 'toggle', 'wikilink', 'columns'],
        comparison: 'normalized AST: production callout promotion plus toggle/wikilink/columns transforms; positions and list spread removed; extension attributes retained',
      },
      expected: {
        ast: firstCanonical,
        stableNodes: stableNodes(firstCanonical),
        astSha256: sha256(jsonBytes(firstCanonical)),
      },
      replay: {
        passes: 2,
        astSha256: [sha256(jsonBytes(firstCanonical)), sha256(jsonBytes(secondCanonical))],
        equal: true,
      },
    };
  });

  const featureCounts = {};
  for (const row of rows) for (const feature of row.features) featureCounts[feature] = (featureCounts[feature] ?? 0) + 1;
  const astTypeCounts = {};
  for (const row of rows) {
    for (const node of row.expected.stableNodes) astTypeCounts[node.type] = (astTypeCounts[node.type] ?? 0) + 1;
  }
  const required = ['heading', 'blockquote', 'list', 'table', 'code-fence', 'callout', 'toggle', 'columns',
    'wikilink', 'unicode', 'crlf', 'block-id', 'inline-card', 'front-matter', 'math-block'];
  for (const feature of required) assert.ok(featureCounts[feature] > 0, `missing required Markdown feature: ${feature}`);
  assert.ok((astTypeCounts.callout ?? 0) > 0, 'production callout promotion produced no callout AST nodes');
  return {
    sourceFiles: extracted.files.length,
    sourceStringLiterals: extracted.stringLiterals,
    candidateLiterals: extracted.candidateLiterals,
    extractedDistinct: extracted.samples.length,
    selectedDistinct: rows.length,
    duplicateTextsCollapsed: extracted.candidateLiterals - extracted.samples.length,
    selection: {
      policy: 'feature-balanced then source-file round-robin, stable source-order fallback',
      limit: MARKDOWN_SAMPLE_LIMIT,
      selectedDistinct: rows.length,
      omittedDistinct: extracted.samples.length - rows.length,
    },
    normalization: {
      transformOrder: ['callout', 'toggle', 'wikilink', 'columns'],
      callout: 'recursive production promoteBlockquoteToCallout on every blockquote',
      positions: 'removed recursively before AST hashing',
      listSpread: 'removed recursively for list and listItem nodes',
      extensionAttributes: 'retained (callout, toggle, columns, wikilink and unknown data)',
      astHashInput: 'JSON of the normalized AST',
    },
    negativeCases: negative.cases,
    negativeMarkers: negative.markers,
    sourceHashes: [...extracted.sourceHashes].map(([file, hash]) => ({ file, sha256: hash })),
    featureCounts,
    astTypeCounts,
    requiredFeatures: required,
    samples: rows,
  };
}

function captureSchemas() {
  const dirs = ['browser', 'chat_v2', 'llm_usage', 'mistakes', 'vfs'];
  const files = gitAt(root, 'ls-files', '-z', '--', 'config/schema/history').split('\0').filter(Boolean).sort();
  const migrations = files.map(file => {
    const bytes = readFileSync(resolve(root, file));
    const text = bytes.toString('utf8');
    const sql = text.replace(/\r\n/g, '\n');
    const name = file.split('/').pop();
    return {
      database: file.split('/')[3],
      file,
      version: Number(/^V(\d+)__/.exec(name)?.[1] ?? NaN),
      bytes: bytes.length,
      sha256: sha256(bytes),
      sqlSha256: sha256(Buffer.from(sql, 'utf8')),
      statements: (sql.match(/;\s*(?:\n|$)/g) ?? []).length,
      tables: [...new Set([...sql.matchAll(/CREATE\s+(?:VIRTUAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["`[]?([A-Za-z0-9_]+)/gi)].map(match => match[1]))].sort(),
    };
  });
  assert.ok(migrations.length > 100, `schema history unexpectedly small: ${migrations.length}`);
  assert.deepEqual([...new Set(migrations.map(row => row.database))].sort(), dirs);
  assert.ok(migrations.every(row => Number.isInteger(row.version) && row.version > 0));
  assert.equal(new Set(migrations.map(row => row.file)).size, migrations.length);
  const databases = dirs.map(database => {
    const rows = migrations.filter(row => row.database === database);
    return {
      database,
      migrations: rows.length,
      minVersion: Math.min(...rows.map(row => row.version)),
      maxVersion: Math.max(...rows.map(row => row.version)),
      bytes: rows.reduce((total, row) => total + row.bytes, 0),
      sha256: sha256(Buffer.concat(rows.map(row => readFileSync(resolve(root, row.file))))),
    };
  });
  return {
    format: 'refinery-sql-history-index',
    source: 'config/schema/history (S0.08 frozen byte copies)',
    databases,
    migrations,
  };
}

function captureWire() {
  const files = [
    'fixtures/differential/vectors.json',
    'fixtures/differential/boundary-vectors.json',
    'fixtures/differential/crypto-vectors.json',
  ];
  const sources = files.map(file => {
    const bytes = readFileSync(resolve(desktop, file));
    const rows = JSON.parse(bytes.toString('utf8'));
    assert.ok(Array.isArray(rows) && rows.length > 0);
    const ids = rows.map(row => row.id);
    assert.equal(new Set(ids).size, ids.length, `duplicate vector id in ${file}`);
    const lanes = [...new Set(rows.map(row => row.lane))].sort();
    const functions = [...new Set(rows.map(row => row.fn))].sort();
    return { file, sha256: sha256(bytes), bytes: bytes.length, rows: rows.length, lanes, functions, ids };
  });
  const all = sources.flatMap(source => source.ids);
  assert.equal(new Set(all).size, all.length, 'wire vector ids collide across sources');
  const required = [
    ['fixtures/differential/vectors.json', ['tokens.estimate', 'text.sanitize_unicode']],
    ['fixtures/differential/boundary-vectors.json', ['fsrs.schedule']],
    ['fixtures/differential/crypto-vectors.json', ['crypto.decryptBackup', 'crypto.checkVerifier']],
  ];
  for (const [file, functions] of required) {
    const source = sources.find(row => row.file === file);
    for (const fn of functions) assert.ok(source.functions.includes(fn), `${file} lacks ${fn}`);
  }
  return {
    provenance: 'desktop committed differential vector inputs; expected Rust outputs are not embedded in these files',
    comparisonRule: 'ids/functions/lane presence; input bytes hashed; semantic output comparison deferred to S0.18',
    sources,
    totalVectors: sources.reduce((total, row) => total + row.rows, 0),
  };
}

function sseEvents(text) {
  const chunks = [];
  let offset = 0;
  for (const raw of text.split(/(?<=\n\n)/)) {
    if (raw.length === 0) continue;
    chunks.push({ text: raw, start: offset, bytes: Buffer.byteLength(raw) });
    offset += raw.length;
  }
  return chunks;
}

function parseEvent(block) {
  let event = null;
  const data = [];
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  return { event, data: data.join('\n') };
}

function decodeGzip(file) {
  const bytes = execFileSync('gzip', ['-dc', resolve(desktop, file)], { maxBuffer: 64 * 1024 * 1024 });
  return Buffer.from(bytes);
}

function captureSse() {
  const sources = [
    {
      file: 'host/e2e/streaming/wave0/streaming.scenarios.provider.json',
      provenance: 'synthetic',
      captureKind: 'scripted-local-service-fixture',
      origin: 'desktop committed provider sidecar; literal responses served by the local WAVE0 scripted service',
    },
    {
      file: 'host/e2e/streaming/wave0/__fixtures__/ai-grading-scripted.provider.json',
      provenance: 'synthetic',
      captureKind: 'scripted-local-service-fixture',
      origin: 'desktop committed qbank provider sidecar; byte-identical snapshot of an external fixture, no paid endpoint contact',
    },
  ];
  const recordings = [];
  for (const source of sources) {
    const bytes = readFileSync(resolve(desktop, source.file));
    const parsed = JSON.parse(bytes.toString('utf8'));
    for (const [scriptId, responses] of Object.entries(parsed)) {
      if (scriptId === 'note') continue;
      assert.ok(Array.isArray(responses) && responses.length > 0, `${source.file}: ${scriptId} has no responses`);
      responses.forEach((response, responseIndex) => {
        assert.ok(Number.isInteger(response.status), `${scriptId}: bad status`);
        assert.ok(Array.isArray(response.chunks), `${scriptId}: chunks missing`);
        const raw = response.chunks.join('');
        const blocks = sseEvents(raw);
        const events = blocks.map(block => parseEvent(block.text));
        const complete = raw.endsWith('\n\n');
        recordings.push({
          id: `${source.file.includes('ai-grading') ? 'qbank' : 'wave0'}:${scriptId}:${responseIndex}`,
          provenance: source.provenance,
          captureKind: source.captureKind,
          origin: source.origin,
          source: { file: source.file, sha256: sha256(bytes) },
          status: response.status,
          headers: response.headers ?? {},
          responseIndex,
          bytes: Buffer.byteLength(raw),
          rawSha256: sha256(Buffer.from(raw, 'utf8')),
          complete,
          events,
          chunks: blocks,
        });
      });
    }
  }
  const g2Files = gitAt(desktop, 'ls-files', '--', 'fixtures/differential/g2').split('\n')
    .filter(file => file.endsWith('.json.xz'));
  const g2 = g2Files.map(file => {
    const bytes = readFileSync(resolve(desktop, file));
    const decoded = execFileSync('xz', ['-dc', resolve(desktop, file)], { maxBuffer: 64 * 1024 * 1024 });
    return {
      file,
      bytes: bytes.length,
      sha256: sha256(bytes),
      decodedSha256: sha256(decoded),
      decodedBytes: decoded.length,
      provenance: 'live-capture',
      captureKind: 'real-client-run-against-scripted-local-service',
      genuineUpstream: false,
    };
  });
  assert.ok(g2.some(row => row.file.includes('chat.stream.success.answer/B.json.xz')));
  const genuineUpstream = [];
  return {
    classification: {
      genuineUpstreamRecordings: genuineUpstream,
      genuineUpstreamCount: genuineUpstream.length,
      liveClientCaptures: g2.filter(row => row.provenance === 'live-capture').length,
      syntheticResponses: recordings.length,
      note: 'The repository contains real client captures, but their provider side was the committed scripted local WAVE0 service. No committed genuine upstream model recording was found; no endpoint was contacted.',
    },
    recordings,
    liveClientCaptures: g2,
  };
}

function captureDocuments() {
  const files = [
    ['host/plugins/media/equivalence/fixtures/tiny.pdf', 'pdf'],
    ['host/plugins/media/equivalence/fixtures/corrupt-body.pdf', 'pdf'],
    ['host/plugins/media/equivalence/fixtures/encrypted-marker.pdf', 'pdf'],
    ['host/e2e/data-compat/__fixtures__/a-produced/historical-zip/portable.zip.gz', 'zip-gzip'],
    ['host/e2e/data-compat/__fixtures__/a-produced/historical-zip/sealed.zip.gz', 'zip-gzip'],
  ];
  return files.map(([file, kind]) => {
    const absolute = resolve(desktop, file);
    const bytes = readFileSync(absolute);
    const row = { file, kind, bytes: bytes.length, sha256: sha256(bytes) };
    if (file.endsWith('.pdf')) {
      assert.equal(bytes.subarray(0, 5).toString('latin1'), '%PDF-');
      row.header = bytes.subarray(0, 8).toString('latin1');
      row.encryptedMarker = bytes.includes(Buffer.from('/Encrypt'));
      row.eof = bytes.includes(Buffer.from('%%EOF'));
    } else {
      const decoded = decodeGzip(file);
      assert.equal(decoded.subarray(0, 2).toString('latin1'), 'PK');
      row.decoded = { bytes: decoded.length, sha256: sha256(decoded), magic: decoded.subarray(0, 4).toString('hex') };
    }
    return row;
  });
}

function captureEncryption() {
  const vectorsFile = 'fixtures/differential/crypto-vectors.json';
  const vectorsBytes = readFileSync(resolve(desktop, vectorsFile));
  const vectors = JSON.parse(vectorsBytes.toString('utf8'));
  assert.equal(vectors.length, 10);
  const plaintextObjects = [
    ['plaintext', 'PK\u0003\u0004 plaintext repo object'],
    ['unicode', '中文🙂 repo object'],
    ['empty', ''],
  ].map(([id, text]) => ({
    id,
    text,
    bytes: Buffer.byteLength(text),
    sha256: sha256(Buffer.from(text, 'utf8')),
    encrypted: false,
  }));
  const encryptedObjects = vectors
    .filter(row => row.fn === 'crypto.decryptBackup')
    .map(row => ({
      id: row.id,
      passwordKind: row.args.password.includes('wrong') ? 'wrong' : 'valid',
      inputHexBytes: Buffer.from(row.args.hex, 'hex').length,
      inputSha256: sha256(Buffer.from(row.args.hex, 'hex')),
      magic: Buffer.from(row.args.hex, 'hex').subarray(0, 4).toString('latin1'),
      encrypted: true,
    }));
  const repositoryScenariosFile = 'host/plugins/sync/equivalence/sync-cloud.json';
  const repositoryScenariosBytes = readFileSync(resolve(desktop, repositoryScenariosFile));
  const scenarios = JSON.parse(repositoryScenariosBytes.toString('utf8')).scenarios
    .filter(row => row.id.startsWith('sync.cloud.repo_check.'));
  assert.ok(scenarios.length >= 7);
  const repositoryChecks = scenarios.map(row => ({
    id: row.id,
    steps: row.steps.length,
    capture: row.capture,
    commands: [...new Set(row.steps.map(step => step.cmd))],
    provenance: 'desktop committed equivalence scenario',
  }));
  return {
    provenance: 'desktop committed crypto/repository fixtures; no key, password file, user profile or remote endpoint was read',
    plaintextObjects,
    encryptedObjects,
    vectors: { file: vectorsFile, sha256: sha256(vectorsBytes), count: vectors.length },
    repositoryChecks: {
      file: repositoryScenariosFile,
      sha256: sha256(repositoryScenariosBytes),
      scenarios: repositoryChecks,
    },
  };
}

function captureSources() {
  const repos = [
    { id: 'desktop', path: desktop },
    { id: 'operit', path: resolve(dirname(root), 'Operit') },
  ];
  return repos.map(repo => {
    const commit = gitAt(repo.path, 'rev-parse', 'HEAD');
    const tree = gitAt(repo.path, 'rev-parse', 'HEAD^{tree}');
    const status = gitAt(repo.path, 'status', '--porcelain');
    return {
      id: repo.id,
      path: relative(dirname(root), repo.path),
      commit,
      tree,
      worktreeClean: status.length === 0,
      worktreeEntries: status.length === 0 ? 0 : status.split('\n').length,
    };
  });
}

function verifyMarkdown(saved) {
  assert.ok(Array.isArray(saved.samples), 'markdown samples missing');
  assert.ok(Array.isArray(saved.sourceHashes), 'markdown source hashes missing');
  assert.ok(saved.samples.length >= MARKDOWN_MIN);
  assert.ok(saved.samples.length <= MARKDOWN_SAMPLE_LIMIT);
  const ids = saved.samples.map(row => row.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const row of saved.samples) {
    assert.equal(typeof row.text, 'string', `${row.id}: text missing`);
    const bytes = Buffer.from(row.text, 'utf8');
    assert.equal(bytes.length, row.input.bytes, `${row.id}: byte length drift`);
    assert.equal(sha256(bytes), row.input.sha256, `${row.id}: input hash drift`);
    assert.equal(bytes.toString('hex'), row.input.utf8Hex, `${row.id}: UTF-8 hex drift`);
    assert.equal(row.expected.astSha256, sha256(jsonBytes(row.expected.ast)), `${row.id}: AST hash drift`);
    assert.deepEqual(row.replay.astSha256, [row.expected.astSha256, row.expected.astSha256]);
    for (const reference of row.provenance.references) {
      const current = sha256(readFileSync(resolve(desktop, reference.file)));
      assert.equal(current, reference.sourceSha256, `${reference.file}: source hash drift`);
    }
  }
  for (const row of saved.sourceHashes) {
    assert.equal(sha256(readFileSync(resolve(desktop, row.file))), row.sha256, `${row.file}: source hash drift`);
  }
  assert.ok(Array.isArray(saved.negativeCases) && saved.negativeCases.length >= 9, 'negative editor cases missing');
  const categories = new Set(saved.negativeCases.map(row => row.category));
  for (const category of ['columns-unknown-version', 'columns-third-column', 'columns-nested', 'columns-missing-end',
    'columns-escaped-literal', 'columns-quoted', 'columns-code-fence', 'unknown-unsupported-code-metadata',
    'unknown-unknown-html']) {
    assert.ok(categories.has(category), `missing negative case: ${category}`);
  }
  for (const row of saved.negativeCases) {
    const bytes = Buffer.from(row.text, 'utf8');
    assert.equal(bytes.length, row.input.bytes, `${row.id}: byte length drift`);
    assert.equal(sha256(bytes), row.input.sha256, `${row.id}: input hash drift`);
    assert.equal(bytes.toString('hex'), row.input.utf8Hex, `${row.id}: UTF-8 hex drift`);
    assert.equal(row.expected.astSha256, sha256(jsonBytes(row.expected.ast)), `${row.id}: negative AST hash drift`);
    assert.equal(sha256(readFileSync(resolve(desktop, row.provenance.primary.file))),
      row.provenance.primary.sourceSha256, `${row.id}: negative source hash drift`);
    if (row.category.startsWith('columns-')) {
      assert.equal(row.expected.dsColumns, 0, `${row.id}: malformed columns upgraded`);
      for (const text of row.expected.contains) assert.ok(row.text.includes(text), `${row.id}: lost ${text}`);
    }
  }
  assert.ok(Array.isArray(saved.negativeMarkers) && saved.negativeMarkers.length === 1);
  const marker = saved.negativeMarkers[0];
  assert.equal(marker.expected.accepted, false);
  assert.equal(marker.expected.result, null);
  assert.equal(sha256(Buffer.from(marker.input, 'utf8')), marker.inputSha256);
  assert.equal(sha256(readFileSync(resolve(desktop, marker.provenance.primary.file))),
    marker.provenance.primary.sourceSha256);
}

function tamperChecks(markdown) {
  const checks = [];
  const run = (id, mutate) => {
    const copy = clone(markdown);
    mutate(copy);
    let rejected = false;
    let reason = null;
    try {
      verifyMarkdown(copy);
    } catch (error) {
      rejected = true;
      reason = error instanceof Error ? error.message.slice(0, 240) : String(error).slice(0, 240);
    }
    checks.push({ id, rejected, expected: 'rejected', verifier: 'verifyMarkdown', reason });
    assert.equal(rejected, true, `tamper check did not reject: ${id}`);
  };
  run('markdown-input-byte-hash', row => { row.samples[0].input.sha256 = '0'.repeat(64); });
  run('markdown-text-bytes', row => { row.samples[0].text += '\nTampered'; });
  run('markdown-ast-hash', row => { row.samples[1].expected.astSha256 = '0'.repeat(64); });
  run('markdown-source-hash', row => { row.sourceHashes[0].sha256 = '0'.repeat(64); });
  run('markdown-negative-columns-upgraded', row => { row.negativeCases[0].expected.dsColumns = 1; });
  run('markdown-negative-marker-accepted', row => { row.negativeMarkers[0].expected.accepted = true; });
  return checks;
}

function artifactHashInput(value) {
  const copy = clone(value);
  delete copy.generatedAt;
  delete copy.captureMs;
  if (copy.replay !== undefined) delete copy.replay.artifactSha256;
  return copy;
}

function artifactSha256(value) {
  return sha256(jsonBytes(artifactHashInput(value)));
}

function verifyArtifactSelfHash(saved) {
  assert.equal(saved.replay.artifactSha256, artifactSha256(saved), 'artifact self-hash drift');
}

function replayComparable(value) {
  const copy = clone(value);
  delete copy.generatedAt;
  delete copy.captureMs;
  delete copy.replay.artifactSha256;
  return copy;
}

async function build() {
  const started = performance.now();
  const loaded = await loadDesktopModule(`
    export { columnsOpen } from './src/components/crepe/plugins/columns/format.ts';
    export { parseColumnsTree } from './src/components/crepe/plugins/columns/remark.ts';
    export { parseCalloutMarker } from './src/components/crepe/plugins/callout/types.ts';
    export { promoteBlockquoteToCallout } from './src/components/crepe/plugins/callout/remark.ts';
    export { remarkTogglePlugin } from './src/components/crepe/plugins/toggle/remark.ts';
    export { remarkWikilinkPlugin } from './src/components/crepe/plugins/wikilink/remark.ts';
  `);
  log('corpus.desktop-module', { inputs: loaded.inputs.length, bundleInputsSha256: sha256(JSON.stringify(loaded.inputs)) });
  const markdown = await captureMarkdown(loaded.exports);
  const schemas = captureSchemas();
  const wire = captureWire();
  const sse = captureSse();
  const documents = captureDocuments();
  const encryption = captureEncryption();
  const sources = captureSources();
  const tamper = tamperChecks(markdown);
  const sampleIds = markdown.samples.map(row => row.id);
  assert.equal(new Set(sampleIds).size, sampleIds.length, 'duplicate Markdown sample ids');
  const result = {
    formatVersion: 1,
    format: FORMAT,
    unit: UNIT,
    generatedAt: new Date().toISOString(),
    scope: {
      included: ['editor-markdown-corpus', 'schema-history-index', 'wire-vector-index', 'sse-recordings', 'document-fixtures', 'encryption-repository-fixtures'],
      excluded: ['device-matrix', 'measurement-plan', 'S0.18 golden comparator', 'Kotlin implementation'],
    },
    environment: {
      node: process.version,
      desktop,
      sourceRepositories: sources,
      desktopDependencies: {
        loader: 'tools/baseline/desktop-module.mjs',
        esbuild: desktopRequire('esbuild/package.json').version,
        typescript: ts.version,
        mdastUtilFromMarkdown: JSON.parse(readFileSync(
          resolve(desktop, 'node_modules/mdast-util-from-markdown/package.json'), 'utf8',
        )).version,
      },
      noInstalls: true,
      noProfilesOrSecrets: true,
    },
    markdown,
    schemas,
    wire,
    sse,
    documents,
    encryption,
    tamper,
    replay: {
      markdown: { passes: 2, deterministic: markdown.samples.every(row => row.replay.equal) },
      sourceHashes: markdown.sourceHashes.every(row => row.sha256.length === 64),
      artifactSha256: null,
    },
    limitations: [
      'No committed genuine upstream model recording was found; SSE entries are scripted local-service responses or real client captures against that scripted service.',
      'Schema, wire, PDF, and repository fixtures are indexed/hashed here; S0.18 owns semantic golden comparison and merge/compression vectors.',
      'Device matrix and measurement methodology are intentionally excluded and handled by the parent.',
      'Markdown extraction is bounded to tracked editor/notes test sources; it is not a whole-repository documentation corpus.',
    ],
  };
  result.replay.artifactSha256 = artifactSha256(result);
  const artifactTamper = (() => {
    const copy = clone(result);
    copy.replay.artifactSha256 = '0'.repeat(64);
    let rejected = false;
    let reason = null;
    try {
      verifyArtifactSelfHash(copy);
    } catch (error) {
      rejected = true;
      reason = error instanceof Error ? error.message.slice(0, 240) : String(error).slice(0, 240);
    }
    assert.equal(rejected, true, 'artifact self-hash tamper was not rejected');
    return { id: 'artifact-self-hash', rejected, expected: 'rejected', verifier: 'verifyArtifactSelfHash', reason };
  })();
  result.tamper.push(artifactTamper);
  result.replay.artifactSha256 = artifactSha256(result);
  verifyArtifactSelfHash(result);
  result.captureMs = Math.round(performance.now() - started);
  return result;
}

function verifyHashes(saved) {
  for (const source of saved.schemas.migrations) {
    assert.equal(sha256(readFileSync(resolve(root, source.file))), source.sha256, `${source.file}: schema hash drift`);
  }
  for (const source of saved.wire.sources) {
    assert.equal(sha256(readFileSync(resolve(desktop, source.file))), source.sha256, `${source.file}: wire hash drift`);
  }
  for (const recording of saved.sse.recordings) {
    assert.equal(sha256(readFileSync(resolve(desktop, recording.source.file))), recording.source.sha256);
  }
  for (const row of saved.sse.liveClientCaptures) {
    const bytes = readFileSync(resolve(desktop, row.file));
    assert.equal(sha256(bytes), row.sha256);
    const decoded = execFileSync('xz', ['-dc', resolve(desktop, row.file)], { maxBuffer: 64 * 1024 * 1024 });
    assert.equal(sha256(decoded), row.decodedSha256);
  }
  for (const row of saved.documents) {
    assert.equal(sha256(readFileSync(resolve(desktop, row.file))), row.sha256);
  }
  assert.equal(sha256(readFileSync(resolve(desktop, saved.encryption.vectors.file))), saved.encryption.vectors.sha256);
  assert.equal(sha256(readFileSync(resolve(desktop, saved.encryption.repositoryChecks.file))), saved.encryption.repositoryChecks.sha256);
  for (const row of saved.tamper) assert.equal(row.rejected, true);
}

export async function run(mode) {
  if (mode === 'capture') {
    const data = await build();
    artifact('config/baseline/corpus.json', data, mode);
    log('corpus.accepted', {
      unit: UNIT,
      markdownSamples: data.markdown.samples.length,
      sourceFiles: data.markdown.sourceFiles,
      sourceLiterals: data.markdown.sourceLiterals,
      schemaMigrations: data.schemas.migrations.length,
      wireVectors: data.wire.totalVectors,
      sseSynthetic: data.sse.classification.syntheticResponses,
      liveClientCaptures: data.sse.classification.liveClientCaptures,
      genuineUpstream: data.sse.classification.genuineUpstreamCount,
      tamperChecks: data.tamper.length,
    });
    return data;
  }
  assert.equal(mode, 'verify');
  const saved = JSON.parse(readFileSync(resolve(root, 'config/baseline/corpus.json'), 'utf8'));
  assert.equal(saved.format, FORMAT);
  assert.equal(saved.unit, UNIT);
  verifyArtifactSelfHash(saved);
  verifyMarkdown(saved.markdown);
  verifyHashes(saved);
  const replayed = await build();
  assert.deepEqual(replayComparable(saved), replayComparable(replayed), 'deterministic replay drift');
  artifact('config/baseline/corpus.json', saved, mode);
  log('corpus.verified', {
    unit: UNIT,
    markdownSamples: saved.markdown.samples.length,
    schemaMigrations: saved.schemas.migrations.length,
    wireVectors: saved.wire.totalVectors,
    sseSynthetic: saved.sse.classification.syntheticResponses,
    liveClientCaptures: saved.sse.classification.liveClientCaptures,
    genuineUpstream: saved.sse.classification.genuineUpstreamCount,
    tamperChecks: saved.tamper.length,
  });
  return saved;
}

main(import.meta.url, run);
