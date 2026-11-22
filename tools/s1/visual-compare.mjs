import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { root } from './frozen-inputs.mjs';

const [name, desktopPath, androidPath] = process.argv.slice(2);
assert.match(name ?? '', /^s1-[a-z0-9-]+$/u);
mkdirSync(resolve(root, 'logs'), { recursive: true });
const output = resolve(root, 'logs', `${name}.png`);
assert.ok(!existsSync(output), 'Visual evidence must not overwrite an existing image');
const requireDesktop = createRequire(resolve(root, '../dstu-node/package.json'));
const sharp = requireDesktop('sharp');
const adb = resolve(process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb');
const bytes = androidPath ? readFileSync(resolve(androidPath))
  : execFileSync(adb, ['exec-out', 'screencap', '-p'], { maxBuffer: 32 * 1024 * 1024 });
const metadata = await sharp(bytes).metadata();
assert.equal(metadata.format, 'png');
assert.ok(metadata.width >= 300 && metadata.height >= 300, 'Unexpected device capture dimensions');
const statistics = await sharp(bytes).removeAlpha().stats();
assert.ok(statistics.channels.some(channel => channel.stdev > 5), 'Device image appears blank');
writeFileSync(output, bytes, { flag: 'wx' });
let comparison = null;
if (desktopPath) {
  const source = resolve(desktopPath);
  const reference = await sharp(source).metadata();
  assert.equal(reference.format, 'png');
  const height = Math.min(1000, metadata.height);
  const left = await sharp(source).resize({ height }).png().toBuffer();
  const right = await sharp(bytes).resize({ height }).png().toBuffer();
  const leftWidth = (await sharp(left).metadata()).width;
  const rightWidth = (await sharp(right).metadata()).width;
  comparison = resolve(root, 'logs', `${name}-comparison.png`);
  assert.ok(!existsSync(comparison), 'Comparison must not overwrite previous evidence');
  const image = await sharp({ create: { width: leftWidth + rightWidth + 16, height,
    channels: 3, background: '#777777' } }).composite([
    { input: left, top: 0, left: 0 }, { input: right, top: 0, left: leftWidth + 16 },
  ]).png().toBuffer();
  writeFileSync(comparison, image, { flag: 'wx' });
  console.log(JSON.stringify({ event: 's1.visual.reference', source,
    sha256: createHash('sha256').update(readFileSync(source)).digest('hex'),
    width: reference.width, height: reference.height }));
}
console.log(JSON.stringify({ time: new Date().toISOString(), event: 's1.visual.capture', output, comparison,
  width: metadata.width, height: metadata.height,
  sha256: createHash('sha256').update(bytes).digest('hex'),
  nonblank: true, pixelStandardDeviation: statistics.channels.map(channel => channel.stdev),
  scope: 'Evidence only; visual equivalence requires main-thread inspection' }));
