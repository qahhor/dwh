// Renames translation keys everywhere they live (plan 10/10, item 4.5; ADR-0031).
//
//   node scripts/i18n-rename-keys.mjs           apply the mapping to catalogs, web, server and e2e sources
//   node scripts/i18n-rename-keys.mjs --check   fail if an old key of the mapping is still used anywhere
//   node scripts/i18n-rename-keys.mjs --sql     print the old/new pairs as a SQL array for a migration
//   --map <file>                                another mapping file (default: scripts/i18n-key-renames.json)
//
// The mapping is an object { "old.key": "new.key" }. It is also the input of the migration that
// renames administrators' overrides (V156): released migrations are frozen, so a later rename gets
// its own mapping file and its own migration.
import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const webRoot = process.cwd();
const repoRoot = path.resolve(webRoot, '..', '..');
const catalogRoot = path.join(repoRoot, 'apps', 'server', 'src', 'main', 'resources', 'i18n');
const LANGUAGES = ['ru', 'uz', 'en'];

/** Source trees that reference keys, with the file types to rewrite. */
const SOURCE_TREES = [
  ['apps/web/src', /\.(ts|html)$/],
  ['apps/server/src/main/java', /\.java$/],
  ['apps/server/src/test/java', /\.java$/],
  ['e2e', /\.(ts|mjs)$/],
  ['scripts', /\.(ps1|mjs|sh)$/],
];
/**
 * Never rewritten: released migrations are frozen, and the mapping and the tests of a rename
 * migration name the old keys on purpose.
 */
const UNTOUCHED = [
  /\/db\/migration\//,
  /\/node_modules\//,
  /\/i18n-key-renames[^/]*\.json$/,
  /\/instance\/db\/[A-Za-z0-9]+MigrationTest\.java$/,
];

function option(name) {
  const index = process.argv.indexOf(name);
  return index > 0 ? process.argv[index + 1] : undefined;
}

export async function loadMapping(file = path.join(webRoot, 'scripts', 'i18n-key-renames.json')) {
  const mapping = JSON.parse(await readFile(file, 'utf8'));
  const targets = new Set();
  for (const [from, to] of Object.entries(mapping)) {
    if (from === to) throw new Error(`${from}: renamed to itself`);
    if (targets.has(to)) throw new Error(`${to}: target of two keys`);
    if (to in mapping) throw new Error(`${to}: renamed again (chains are not supported)`);
    targets.add(to);
  }
  return mapping;
}

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * One regular expression for all old keys, longest first, so `x.kursiv` never eats the head of
 * `x.kursiv_ctrl_i` or `x.kursiv.64bef33`. Both the plain spelling and the regex-escaped one
 * (`iam\.users`, as written in PowerShell patterns) match.
 */
function keyPattern(keys) {
  const alternatives = [...keys]
    .sort((left, right) => right.length - left.length)
    .flatMap((key) => [escapeRegExp(key), escapeRegExp(key.replaceAll('.', '\\.'))]);
  return new RegExp(`(?<![A-Za-z0-9_.\\\\])(?:${alternatives.join('|')})(?![A-Za-z0-9_]|\\.[A-Za-z0-9])`, 'g');
}

async function filesUnder(directory, pattern) {
  const result = [];
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') return result;
    throw error;
  }
  for (const entry of entries) {
    const absolute = path.join(directory, entry.name);
    const relative = absolute.split(path.sep).join('/');
    if (UNTOUCHED.some((rule) => rule.test(relative + (entry.isDirectory() ? '/' : '')))) continue;
    if (entry.isDirectory()) result.push(...(await filesUnder(absolute, pattern)));
    else if (pattern.test(entry.name)) result.push(absolute);
  }
  return result;
}

async function sourceFiles() {
  const files = [];
  for (const [tree, pattern] of SOURCE_TREES) files.push(...(await filesUnder(path.join(repoRoot, tree), pattern)));
  return files;
}

/** Keeps a file's line endings: the working tree may hold CRLF (core.autocrlf). */
async function writeLike(file, original, text) {
  const crlf = original.includes('\r\n');
  await writeFile(file, crlf ? text.replace(/\r?\n/g, '\r\n') : text, 'utf8');
}

async function renameInCatalogs(mapping) {
  let renamed = 0;
  for (const language of LANGUAGES) {
    const file = path.join(catalogRoot, `${language}.json`);
    const original = await readFile(file, 'utf8');
    const catalog = JSON.parse(original);
    const result = {};
    for (const [key, value] of Object.entries(catalog)) {
      const target = mapping[key] ?? key;
      if (target !== key) renamed++;
      if (target in result) throw new Error(`${language}: ${key} -> ${target} collides with an existing key`);
      result[target] = value;
    }
    await writeLike(file, original, `${JSON.stringify(result, null, 2)}\n`);
  }
  return renamed;
}

async function renameInSources(mapping) {
  const pattern = keyPattern(Object.keys(mapping));
  const changed = [];
  for (const file of await sourceFiles()) {
    const original = await readFile(file, 'utf8');
    const text = original.replace(pattern, (match) => {
      const plain = match.replaceAll('\\.', '.');
      const target = mapping[plain];
      return match.includes('\\.') ? target.replaceAll('.', '\\.') : target;
    });
    if (text !== original) {
      await writeLike(file, original, text);
      changed.push(path.relative(repoRoot, file).split(path.sep).join('/'));
    }
  }
  return changed;
}

/** Old keys still present in catalogs or sources; the audit fails on any. */
export async function leftovers(mapping) {
  const pattern = keyPattern(Object.keys(mapping));
  const found = [];
  for (const language of LANGUAGES) {
    const catalog = JSON.parse(await readFile(path.join(catalogRoot, `${language}.json`), 'utf8'));
    for (const key of Object.keys(catalog)) if (key in mapping) found.push(`${language}.json: ${key}`);
  }
  for (const file of await sourceFiles()) {
    const text = await readFile(file, 'utf8');
    for (const match of text.matchAll(pattern))
      found.push(`${path.relative(repoRoot, file).split(path.sep).join('/')}: ${match[0]}`);
  }
  return found;
}

function sqlArray(mapping) {
  const quote = (text) => `'${text.replaceAll("'", "''")}'`;
  return Object.entries(mapping)
    .map(([from, to]) => `        [${quote(from)}, ${quote(to)}]`)
    .join(',\n');
}

async function main() {
  const mapping = await loadMapping(option('--map') && path.resolve(option('--map')));
  if (process.argv.includes('--sql')) {
    process.stdout.write(`${sqlArray(mapping)}\n`);
    return;
  }
  if (process.argv.includes('--check')) {
    const found = await leftovers(mapping);
    if (found.length) {
      process.stderr.write(`Renamed translation keys still in use:\n${found.join('\n')}\n`);
      process.exit(1);
    }
    process.stdout.write(`No renamed key is left (${Object.keys(mapping).length} renames).\n`);
    return;
  }
  const renamed = await renameInCatalogs(mapping);
  const changed = await renameInSources(mapping);
  process.stdout.write(
    `Renamed ${renamed} catalog entries and rewrote ${changed.length} source files:\n${changed.join('\n')}\n` +
      'Run npm run i18n:sync-ru and npm run i18n:audit next.\n',
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
