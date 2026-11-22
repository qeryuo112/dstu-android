import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { assertHash, lock, root, source } from './pdf-build-lib.mjs';

const require = createRequire(join(root, '../dstu-node/package.json'));
const { init } = require('@embedpdf/pdfium');
const wasmPath = require.resolve('@embedpdf/pdfium/pdfium.wasm');
const module = await init({ wasmBinary: readFileSync(wasmPath) });
module.PDFiumExt_Init();

function extract(name) {
  const path = join(source, 'testing/resources', name);
  assertHash(path, lock.fixtures[name]);
  const bytes = readFileSync(path);
  const pointer = module.pdfium.wasmExports.malloc(bytes.length);
  if (!pointer) throw new Error('WASM allocation failed');
  let document = 0;
  let page = 0;
  let text = 0;
  try {
    module.pdfium.HEAPU8.set(bytes, pointer);
    document = module.FPDF_LoadMemDocument64(pointer, bytes.length, '');
    if (!document) throw new Error(`WASM open failed: ${name}`);
    page = module.FPDF_LoadPage(document, 0);
    text = module.FPDFText_LoadPage(page);
    const count = module.FPDFText_CountChars(text);
    let value = '';
    for (let i = 0; i < count; ++i) {
      const code = module.FPDFText_GetUnicode(text, i);
      value += String.fromCodePoint(code > 0 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff) ? code : 0xfffd);
    }
    const target = Array.from(value).indexOf('我');
    let box = null;
    if (target >= 0) {
      const buffer = module.pdfium.wasmExports.malloc(32);
      try {
        if (!module.FPDFText_GetCharBox(text, target, buffer, buffer + 8, buffer + 16, buffer + 24)) {
          throw new Error('WASM character box failed');
        }
        box = [0, 8, 16, 24].map((offset) => module.pdfium.getValue(buffer + offset, 'double'));
      } finally { module.pdfium.wasmExports.free(buffer); }
    }
    return { pages: module.FPDF_GetPageCount(document), text: value, boxLeftRightBottomTop: box };
  } finally {
    if (text) module.FPDFText_ClosePage(text);
    if (page) module.FPDF_ClosePage(page);
    if (document) module.FPDF_CloseDocument(document);
    module.pdfium.wasmExports.free(pointer);
  }
}

for (const name of ['bug_420508260.pdf', 'rotated_text_90.pdf', 'rotated_image.pdf']) {
  console.log(JSON.stringify({ fixture: name, ...extract(name) }));
}
