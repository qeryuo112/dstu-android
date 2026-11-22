import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const brand = JSON.parse(readFileSync(resolve(root, 'config/design/brand.baseline.json'), 'utf8')).brand.mark;
const paths = brand.paths.map(path => {
  const color = path.className === 'fill-foreground' ? '#FF2A2D32' : '#FFFFFFFF';
  return `    <path android:fillColor="${color}" android:pathData="${path.d}" />`;
});
const output = resolve(root, 'app/src/main/res/drawable/dstu_mark.xml');
const xml = [
  '<?xml version="1.0" encoding="utf-8"?>',
  '<!-- Generated from the frozen S0.12 brand mark. Run node tools/s1/generate-brand.mjs. -->',
  '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
  '    android:width="72dp" android:height="72dp" android:viewportWidth="147" android:viewportHeight="147">',
  ...paths,
  '</vector>',
  '',
].join('\n');
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, xml);
console.log(JSON.stringify({ event: 'brand.generated', owner: 'S1.01', geometry: brand.geometrySha256 }));
