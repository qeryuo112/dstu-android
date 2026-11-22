import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { desktop } from './design-data.mjs';

const requireDesktop = createRequire(resolve(desktop, 'package.json'));
const ts = requireDesktop('typescript');
const esbuild = requireDesktop('esbuild');
const hash = text => createHash('sha256').update(text).digest('hex');

function sidebarPaths(brand) {
  const file = resolve(desktop, brand.icons.source.file);
  const text = readFileSync(file, 'utf8');
  assert.equal(hash(text), brand.icons.source.sha256, 'sidebar icon source changed');
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const found = new Map();
  function visit(node) {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && /^Study\w+Icon$/.test(node.name.text)) {
      const args = node.initializer?.arguments;
      const object = args?.[0];
      if (object && ts.isObjectLiteralExpression(object)) {
        found.set(node.name.text, Object.fromEntries(object.properties.map(property => [
          property.name.getText(source), property.initializer.text,
        ])));
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  return found;
}

async function databasePaths() {
  const source = `import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { Database } from '@phosphor-icons/react/dist/csr/Database';
export const render = weight => renderToStaticMarkup(React.createElement(Database, { weight }));`;
  const built = await esbuild.build({
    absWorkingDir: desktop, stdin: { contents: source, resolveDir: desktop, loader: 'jsx' },
    bundle: true, write: false, format: 'cjs', platform: 'node', target: 'node22', logLevel: 'silent',
  });
  const module = { exports: {} };
  new Function('module', 'exports', 'require', built.outputFiles[0].text)(module, module.exports, requireDesktop);
  return Object.fromEntries(['regular', 'bold'].map(weight => {
    const html = module.exports.render(weight);
    const match = html.match(/<path\b[^>]*\bd="([^"]+)"/);
    assert.ok(match, `Database ${weight} path`);
    return [weight, match[1]];
  }));
}

export async function iconPaths(brand) {
  const found = sidebarPaths(brand);
  found.set('Database', await databasePaths());
  return brand.icons.navigationIcons.map(icon => {
    const paths = found.get(icon.component);
    assert.ok(paths, `missing icon ${icon.component}`);
    for (const weight of ['regular', 'bold']) {
      assert.equal(hash(paths[weight]), icon[weight].pathSha256[0], `${icon.component}/${weight} drift`);
    }
    return { id: icon.navigationId, ...paths };
  });
}
