import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

export const MIGRATION_DATABASES = ['vfs', 'chat_v2', 'mistakes', 'llm_usage'];

export function readMigrationSources(desktop) {
  const root = join(desktop, 'resources/app/migrations');
  const files = {};
  for (const database of MIGRATION_DATABASES) {
    const dir = join(root, database);
    for (const name of readdirSync(dir).filter(entry => entry.endsWith('.sql')).sort()) {
      files[join(root, database, name)] = readFileSync(join(dir, name), 'utf8');
    }
  }
  return files;
}

export function sqlGlobPlugin(desktop) {
  const files = readMigrationSources(desktop);
  return {
    name: 'dstu-sql-glob',
    setup(build) {
      build.onLoad({ filter: /sqlite-runtime\.ts$/ }, args => {
        if (!args.path.includes('/mobile/src/worker/')) return null;
        const text = readFileSync(args.path, 'utf8');
        const start = text.indexOf('const SQL_FILES = import.meta.glob');
        if (start < 0) return null;
        const end = text.indexOf('});', start) + 3;
        const replaced = `${text.slice(0, start)}const SQL_FILES: Record<string, string> = ${JSON.stringify(files)};${text.slice(end)}`;
        return { contents: replaced, loader: 'ts', resolveDir: args.resolveDir };
      });
    },
  };
}
