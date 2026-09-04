import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const webRoot = process.cwd();
const catalogPath = path.resolve(webRoot, '..', 'server', 'src', 'main', 'resources', 'i18n', 'ru.json');
const outputPath = path.join(webRoot, 'src', 'app', 'core', 'i18n', 'packaged-russian.ts');
const catalog = JSON.parse(await readFile(catalogPath, 'utf8'));

await mkdir(path.dirname(outputPath), { recursive: true });
await writeFile(outputPath,
  `// Generated from the canonical packaged Russian catalog.\n` +
  `// The server remains authoritative after application initialization.\n` +
  `export const PACKAGED_RUSSIAN: Readonly<Record<string, string>> = Object.freeze(${JSON.stringify(catalog, null, 2)});\n`,
  'utf8');

process.stdout.write(`Synchronized ${Object.keys(catalog).length} Russian fallback strings.\n`);
