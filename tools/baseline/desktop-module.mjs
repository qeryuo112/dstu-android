import { resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { desktop, desktopRequire, sha256 } from './lib.mjs';

export async function loadDesktopModule(contents) {
  const esbuild = desktopRequire('esbuild');
  const result = await esbuild.build({
    absWorkingDir: desktop,
    stdin: { contents, resolveDir: desktop, sourcefile: 'android-baseline-entry.ts', loader: 'ts' },
    tsconfig: resolve(desktop, 'tsconfig.json'),
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'node',
    target: 'node22',
    metafile: true,
    logLevel: 'silent',
  });
  const exports = await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
  const inputs = Object.keys(result.metafile.inputs).filter(file => file !== 'android-baseline-entry.ts').sort().map(file => ({ file, sha256: sha256(readFileSync(resolve(desktop, file))) }));
  return { exports, inputs };
}
