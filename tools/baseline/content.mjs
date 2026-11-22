import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { artifact, main, root, desktop, desktopRequire, git, readJson, readDesktop, parse, ts, visit, prop, value, location, sourceFiles, sha256, log } from './lib.mjs';
import { loadDesktopModule } from './desktop-module.mjs';

const plain = item => JSON.parse(JSON.stringify(item, (_key, v) => typeof v === 'bigint' ? { decimal: v.toString() } : v));
const contentRoots = ['src/features/chat/plugins', 'src/ui-plugins/official/content', 'src/components/crepe/plugins'];
const contentSources = sourceFiles(desktop, ...contentRoots).filter(f => /\.(ts|tsx)$/.test(f) && !/(?:__tests__|\.test\.)/.test(f));
export async function collect() {
  const input = readJson('config/content-inputs.json');
  const loaded = await loadDesktopModule(`
    export { rs, toRustJson, formatF64, formatF32, formatInt } from './host/platform/serde-compat/index.ts';
    export { parseJsonDocument } from './host/platform/util/json-exact.ts';
    export { validUtf8Prefix, utf8ByteLength } from './host/platform/util/utf8.ts';
    export { sha256HexSync } from './host/platform/crypto/sync.ts';
    export { renderAnkiTemplate } from './src/services/ankiTemplateEngine.ts';
    export { generativeUIIntentSchema } from './src/features/generative-ui/schema.ts';
    export { BUILTIN_GENERATIVE_BLOCK_SCHEMAS } from './src/features/generative-ui/blockSchemas.ts';
    export { exportGenerativeUIJsonSchema } from './src/features/generative-ui/utils/exportGenerativeUIJsonSchema.ts';
    export { parseColumnsTree } from './src/components/crepe/plugins/columns/remark.ts';
    export { promoteBlockquoteToCallout } from './src/components/crepe/plugins/callout/remark.ts';
    export { parseWikiLinkInner } from './src/components/crepe/plugins/wikilink/format.ts';
    export { parseToggleMarker } from './src/components/crepe/plugins/toggle/marker.ts';
    export { remarkTogglePlugin } from './src/components/crepe/plugins/toggle/remark.ts';
    export { remarkWikilinkPlugin } from './src/components/crepe/plugins/wikilink/remark.ts';
    export { compileInlineCardText } from './src/components/crepe/plugins/inlineCards/scanner.ts';
  `);
  const api = loaded.exports;
  const { fromMarkdown } = await import(pathToFileURL(desktopRequire.resolve('mdast-util-from-markdown')).href);
  const { gfmFromMarkdown } = await import(pathToFileURL(desktopRequire.resolve('mdast-util-gfm')).href);
  const { gfm } = await import(pathToFileURL(desktopRequire.resolve('micromark-extension-gfm')).href);
  const { math } = await import(pathToFileURL(desktopRequire.resolve('micromark-extension-math')).href);
  const { mathFromMarkdown } = await import(pathToFileURL(desktopRequire.resolve('mdast-util-math')).href);
  const remarkTransform = async plugin => {
    let factory;
    const context = { wait: async () => {}, get: () => undefined, update: (_key, update) => {
      const result = update([]);
      if (Array.isArray(result) && result.some(row => typeof row?.plugin === 'function')) factory = result.find(row => typeof row?.plugin === 'function').plugin;
      return context;
    } };
    await plugin.plugin(context)();
    assert.equal(typeof factory, 'function');
    return factory();
  };
  const toggleTransform = await remarkTransform(api.remarkTogglePlugin);
  const wikiTransform = await remarkTransform(api.remarkWikilinkPlugin);
  const markdown = input.markdown.map(row => {
    const tree = fromMarkdown(row.text, { extensions: [gfm(), math()], mdastExtensions: [gfmFromMarkdown(), mathFromMarkdown()] });
    if (row.id === 'toggle') toggleTransform(tree);
    if (row.id === 'wiki-mention-reference') wikiTransform(tree);
    if (row.id.startsWith('columns')) api.parseColumnsTree(tree, row.text);
    if (row.id === 'callout') assert.equal(api.promoteBlockquoteToCallout(tree.children[0]), true);
    return { ...row, utf8Hex: Buffer.from(row.text).toString('hex'), sha256: sha256(row.text), expected: plain(tree), comparison: 'AST fields and offsets exact; unchanged source bytes exact; editor semantic comparison ignores position/list spread only', deliveryUnit: 'S5.08' };
  });
  assert.equal(markdown.find(r => r.id === 'columns').expected.children[0].type, 'ds_columns');
  assert.equal(markdown.find(r => r.id === 'columns-escaped').expected.children[0].type, 'paragraph');
  const anki = input.anki.map(row => ({ ...row, expected: api.renderAnkiTemplate(row.template, row.fields, row.options), comparison: 'HTML and structured issue codes exact; user CSS/script source retained', deliveryUnit: 'S5.17' }));
  assert.equal(anki[0].expected.html, '<b><i>问题</i></b>');
  assert.equal(anki.find(r => r.id === 'malformed').expected.ok, false);
  const generative = input.generative.map(row => {
    const result = api.generativeUIIntentSchema.safeParse(row.input);
    return { ...row, expected: result.success ? { ok: true, value: result.data } : { ok: false, issues: result.error.issues.map(e => ({ path: e.path, code: e.code })) }, deliveryUnit: 'S5.19' };
  });
  const numbers = [];
  for (const [kind, items] of [['i64', ['-9223372036854775808', '-9007199254740993', '0', '9223372036854775807']], ['u64', ['0', '9007199254740993', '18446744073709551615']]]) {
    for (const decimal of items) {
      const expected = api.formatInt(kind, BigInt(decimal)); assert.equal(expected, decimal);
      numbers.push({ kind, inputDecimal: decimal, expectedJson: expected, expectedUtf8Hex: Buffer.from(expected).toString('hex') });
    }
  }
  const rejects = [];
  for (const [kind, decimal] of [['i64', '9223372036854775808'], ['i64', '-9223372036854775809'], ['u64', '-1'], ['u64', '18446744073709551616']]) {
    assert.throws(() => api.formatInt(kind, BigInt(decimal)), RangeError);
    rejects.push({ kind, inputDecimal: decimal, expected: 'RangeError' });
  }
  for (const number of [0, -0, 0.1, 1e-6, 1e16, Math.PI, 16777217]) numbers.push({ kind: 'float', input: Object.is(number, -0) ? '-0' : number.toString(), expectedF64: api.formatF64(number), expectedF32: api.formatF32(number) });
  const optionalSchema = api.rs.struct({ id: api.rs.string, nullable: api.rs.option(api.rs.string), omitted: api.rs.field(api.rs.option(api.rs.string), { skipIf: 'none' }) });
  const nullOmit = [
    { id: 'missing', input: { id: 'x' }, expected: '{"id":"x","nullable":null}' },
    { id: 'null', input: { id: 'x', nullable: null, omitted: null }, expected: '{"id":"x","nullable":null}' },
    { id: 'empty', input: { id: 'x', nullable: '', omitted: '' }, expected: '{"id":"x","nullable":"","omitted":""}' },
  ];
  for (const row of nullOmit) assert.equal(api.toRustJson(row.input, optionalSchema), row.expected);
  const exactJson = input.exactJson.map(text => ({ input: text, expected: plain(api.parseJsonDocument(text)), rawSha256: api.sha256HexSync(text) }));
  const utf8 = ['中文👩‍💻', 'e\u0301', 'é', '\ufeff正文', 'line\r\nnext', '\ud800'].map(text => {
    const bytes = Buffer.from(text);
    assert.equal(api.utf8ByteLength(text), bytes.length);
    assert.equal(api.sha256HexSync(text), sha256(bytes));
    const cuts = [];
    for (let i = 0; i <= bytes.length; i++) cuts.push({ length: i, validation: api.validUtf8Prefix(bytes.subarray(0, i)) });
    return { text, hex: bytes.toString('hex'), length: bytes.length, sha256: sha256(bytes), prefixValidation: cuts };
  });
  const invalidUtf8 = ['c080', 'eda080', 'f4908080', 'e4b8', 'ff'].map(hex => ({ hex, expected: api.validUtf8Prefix(Buffer.from(hex, 'hex')) }));
  const registrations = [];
  const registrationSources = {};
  for (const file of contentSources) {
    const sf = parse(file);
    const text = readDesktop(file);
    visit(sf, node => {
      if (ts.isCallExpression(node) && /blockRegistry\.register$|officialEntry$/.test(node.expression.getText(sf))) {
        const line = sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
        const statement = text.split(/\r?\n/).slice(line - 1, line + 4).join('\n');
        registrations.push({ kind: node.expression.getText(sf), id: value(node.arguments[0]), definition: node.arguments.slice(1).map(value), evidence: location(file, sf, node), sourceSha256: sha256(text), statementSha256: sha256(statement) });
        registrationSources[file] = sha256(text);
      }
    });
  }
  assert.equal(registrations.length, 49);
  const validProps = {
    'stat-card': { title: '掌握度', value: '80%' }, alert: { title: '提示' }, list: { items: [{ label: '项目' }] },
    progress: { current: 1, total: 2 }, 'action-bar': { actions: [{ id: 'confirm', label: '确认' }] }, text: { body: '正文' },
    'key-value-grid': { rows: [{ key: '科目', value: '数学' }] }, 'flashcard-preview': { front: '问题', back: '答案' },
    'review-calendar': { days: [{ date: '2026-11-15', dueCount: 2 }] }, 'mistake-analysis': { topic: '代数', errorRate: 20, suggestion: '复习公式' },
    'mindmap-embed': { mindmapId: 'mindmap_fixture_1' }, 'paper-digest': { title: '论文', year: 2026 },
    'research-plan': { title: '研究', steps: [{ label: '检索' }] }, 'research-report': { body: '报告 [论文-1]' }, markdown: { body: '# 笔记' },
    chart: { kind: 'line', categories: ['一'], series: [{ name: '进度', values: [1] }] }, steps: { steps: [{ label: '学习' }] }, table: { columns: [{ key: 'a', label: '列' }], rows: [{ a: '值' }] },
  };
  const schemaSourceFile = 'src/features/generative-ui/schema.ts';
  const schemaDefinitions = {};
  const schemaSf = parse(schemaSourceFile);
  visit(schemaSf, node => { if (ts.isVariableDeclaration(node) && node.initializer && /Schema$/.test(node.name.getText())) schemaDefinitions[node.name.getText()] = { source: node.initializer.getText(schemaSf), evidence: location(schemaSourceFile, schemaSf, node) }; });
  const schemaSymbols = {};
  const blockSf = parse('src/features/generative-ui/blockSchemas.ts');
  visit(blockSf, node => { if (ts.isObjectLiteralExpression(node) && prop(node, 'type') && prop(node, 'propsSchema')) schemaSymbols[value(prop(node, 'type').initializer)] = prop(node, 'propsSchema').initializer.getText(blockSf); });
  const contentLanguages = [];
  const contentFile = 'src/ui-plugins/official/content/entries.ts';
  visit(parse(contentFile), node => { if (ts.isVariableDeclaration(node) && node.name.getText() === 'languageDefinitions') contentLanguages.push(...value(node.initializer).map(([language, definition]) => ({ language, definition, deliveryUnit: 'S5.18' }))); });
  const inlineCards = ['问题 >> 答案', '牛顿第{{一}}定律', '`问题 >> 答案`', '$a >> b$'].map(text => ({ input: text, expected: api.compileInlineCardText(text) }));
  const schemas = api.BUILTIN_GENERATIVE_BLOCK_SCHEMAS.map(row => {
    const valid = row.propsSchema.safeParse(validProps[row.type]);
    assert.equal(valid.success, true, `valid genui props: ${row.type}`);
    const invalid = row.propsSchema.safeParse({});
    assert.equal(invalid.success, false, `invalid genui props: ${row.type}`);
    return { type: row.type, description: row.description, allowPartialRender: row.allowPartialRender ?? false, deliveryUnit: 'S5.19', schemaSymbol: schemaSymbols[row.type], schema: schemaDefinitions[schemaSymbols[row.type]], vectors: [
      { input: validProps[row.type], expected: { ok: true, value: valid.data } },
      { input: {}, expected: { ok: false, issues: invalid.error.issues.map(e => ({ path: e.path, code: e.code })) } },
    ] };
  });
  assert.equal(schemas.length, 18);
  const dialect = [
    ['commonmark-gfm', 'paragraph/headings/blockquote/list/task/table/code/link/image/strikethrough'],
    ['math', 'inline $ / display $$; TeX source retained'],
    ['callout', '> [!type][+-] title; whitelist marker; nested children'],
    ['toggle', '> [!toggle][+-] title; first-line split and marks retained'],
    ['columns', ':::ds-columns{version=1 layout=equal|cornell}; exactly two :::column blocks at root'],
    ['wikilink', '[[target|label]] and [[target]]; logical resource identity'],
    ['mention-references', '@mention; note:// pdfref:// mediaref:// links and source anchors'],
    ['inline-cards', 'editor:inline-cards opt-in; capsule metadata and source identity'],
    ['unknown', 'retain raw source bytes and unknown attrs; edit only known range; unsupported normalization is a typed error'],
  ].map(([id, grammar]) => ({ id, grammar, samples: markdown.filter(row => row.id.includes(id.split('-')[0])).map(row => row.id), deliveryUnit: 'S5.08' }));
  return {
    formatVersion: 2, unit: 'S0.07', revision: 'content-contract-v2', desktopCommit: git(desktop, 'rev-parse', 'HEAD'), desktopTree: git(desktop, 'rev-parse', 'HEAD^{tree}'), inputSha256: sha256(readFileSync(resolve(root, 'config/content-inputs.json'))), dialect, markdown, anki, inlineCards, contentLanguages, schemaDefinitions, generative, generativeTypes: schemas, generativeJsonSchema: api.exportGenerativeUIJsonSchema(), registrations, registrationSources,
    markers: { wiki: ['note_1|标题', 'note_1', '|'].map(text => ({ input: text, expected: api.parseWikiLinkInner(text) })), toggle: ['[!toggle]- 标题', '[!toggle]+ 展开', '[!note] 标题'].map(text => ({ input: text, expected: api.parseToggleMarker(text) })) },
    encoding: { integers: numbers, rejectedIntegers: rejects, nullOmit, exactJson, utf8, invalidUtf8, rules: { numbers: 'i64→Long; u64→ULong/decimal lexeme; arbitrary JSON numbers retain original token; no Double projection', null: 'nullable option emits null; skipIf none omits; empty string is distinct', unknown: 'unknown JSON members and content nodes survive semantic round-trip', text: 'UTF-8 exact bytes; no NFC/NFD normalization; retain CRLF outside edited span', hash: 'SHA-256 over declared UTF-8/binary bytes; lowercase hex; no added BOM/newline', objectOrder: 'typed struct declaration order; map order follows captured schema; ordinary JSON compared semantically; hashed payload compared byte-for-byte' } },
    contentSources: contentSources.map(file => ({ file, sha256: sha256(readDesktop(file)) })),
    bundleInputs: {
      total: loaded.inputs.length,
      source: loaded.inputs.filter(item => !item.file.startsWith('node_modules/')),
      dependencySha256: sha256(JSON.stringify(loaded.inputs.filter(item => item.file.startsWith('node_modules/')))),
    },
  };
}
export async function run(mode) {
  const data = await collect();
  artifact('config/baseline/content.json', data, mode);
  log('content.accepted', { markdown: data.markdown.length, anki: data.anki.length, genui: data.generativeTypes.length, numericVectors: data.encoding.integers.length, invalidIntegers: data.encoding.rejectedIntegers.length, registrations: data.registrations.length });
}
main(import.meta.url, run);
