import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const cache = process.env.DSTU_GRADLE_HOME ?? resolve(root, '../build-tools/s1-gradle-home');
if (cache.includes('android-gradle-home')) throw new Error('S0 platform cache is frozen; use the isolated S1 cache');
const sdk = process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk');
const args = [...process.argv.slice(2)];
if (process.env.DSTU_PROXY_HOST) {
  for (const protocol of ['http', 'https']) {
    args.push(`-D${protocol}.proxyHost=${process.env.DSTU_PROXY_HOST}`, `-D${protocol}.proxyPort=${process.env.DSTU_PROXY_PORT ?? '7897'}`);
  }
  args.push('-Dhttp.nonProxyHosts=localhost|127.0.0.1|*.maven.apache.org|repo1.maven.org');
}
if (existsSync('/usr/lib/jvm/zulu17')) args.push('-Porg.gradle.java.installations.paths=/usr/lib/jvm/zulu17');
const child = spawn(resolve(root, 'gradlew'), args, {
  cwd: root, stdio: 'inherit', env: { ...process.env, ANDROID_HOME: sdk, GRADLE_USER_HOME: cache },
});
const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
process.exitCode = code ?? 1;
