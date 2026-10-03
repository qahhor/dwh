// What the CLI reads back from the repository: a module's manifest and names, an entity's declaration.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { addCatalogKeys, areaOwner, namedAreaOf } from './edits.mjs';
import { CliError, LANGUAGES, PATHS, resolve } from './layout.mjs';
import { moduleNames, pascal } from './names.mjs';
import { readText } from './plan.mjs';

/** Titles in the three languages: ru is required as the source, en and uz fall back. */
export function titles(values, fallback) {
  const en = values['title-en'] ?? values['label-en'] ?? fallback;
  const ru = values.title ?? values.label ?? en;
  const uz = values['title-uz'] ?? values['label-uz'] ?? en;
  return { ru, en, uz };
}

/**
 * A module created by `cms module new` (or by hand), read back from where its names live: the permission area from
 * PermissionAreas (ADR-0028), the table prefix from the owner rule of ModuleBoundariesTest, the name from its manifest
 * (ADR-0033, 6.2), and the menu icon from `icon`, else from a declaration of the module, else `box`.
 */
export function loadModule(root, code, icon) {
  const segments = String(code ?? '').split('.');
  const javaDir = `${PATHS.serverJava}/${segments.join('/')}`;
  if (!/^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)?$/.test(String(code ?? '')) || !fs.existsSync(resolve(root, javaDir))) {
    throw new CliError(`No module ${code} at ${javaDir}: run cms module new ${code} first`);
  }
  const quoted = code.replace('.', '\\.');
  const areas = readText(root, PATHS.permissionAreas) ?? '';
  const named = areas ? namedAreaOf(areas, code) : null;
  const area = named ?? code;
  if (!named && areaOwner(areas, area) !== code) {
    throw new CliError(`The module ${code} has no permission area in PermissionAreas (ADR-0028)`);
  }
  const boundaries = readText(root, PATHS.moduleBoundaries) ?? '';
  const owner = new RegExp(`table\\.startsWith\\("([a-z0-9_]+)_"\\)\\) return Optional\\.of\\("${quoted}"\\)`).exec(boundaries);
  const names = moduleNames(code, { area, ...(owner ? { tablePrefix: owner[1] } : {}) });
  const manifestText = readText(root, names.manifest);
  const name = manifestText ? JSON.parse(manifestText).name : null;
  const title = name ?? pascal(code);
  return { ...names, icon: icon ?? declaredIcon(root, javaDir) ?? 'box', title: { ru: title, en: title, uz: title } };
}

/** The menu icon a declaration of the module already uses, so its entities share one. */
function declaredIcon(root, javaDir) {
  for (const file of javaFiles(resolve(root, javaDir))) {
    const menu = /new EntityMenu\(\s*"[^"]*",\s*"([a-z][a-z0-9_-]*)"/.exec(fs.readFileSync(file, 'utf8'));
    if (menu) return menu[1];
  }
  return null;
}

/** Every Java file under a directory. */
function javaFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return javaFiles(full);
    return entry.name.endsWith('.java') ? [full] : [];
  });
}

/**
 * The declaration of an entity by its code: the file that calls `Entity.define` with the code (literally or through
 * a `CODE` constant), its module and its table.
 */
export function findDeclaration(root, code) {
  const base = resolve(root, PATHS.serverJava);
  for (const file of javaFiles(base)) {
    const text = fs.readFileSync(file, 'utf8');
    if (!text.includes('Entity.define(') || !text.includes(`"${code}"`)) continue;
    const literal = text.includes(`Entity.define("${code}"`);
    const constant = new RegExp(`String CODE = "${code.replace('.', '\\.')}";`).test(text) && /Entity\.define\(CODE/.test(text);
    if (!literal && !constant) continue;
    const relative = path.relative(base, file).split(path.sep);
    const serviceAt = relative.lastIndexOf('service');
    const moduleSegments = relative.slice(0, serviceAt > 0 ? serviceAt : relative.length - 1);
    const table = /\.table\(\s*"([a-z_][a-z0-9_]*)"/.exec(text);
    return {
      file: `${PATHS.serverJava}/${relative.join('/')}`,
      module: moduleSegments.join('.'),
      table: table ? table[1] : null,
    };
  }
  throw new CliError(`No entity declaration with the code ${code} under ${PATHS.serverJava}`);
}

/** Plans the keys in every catalog and the Russian fallback of the web after them. */
export function planKeys(plan, keys, { sync = true } = {}) {
  for (const language of LANGUAGES) {
    const entries = Object.fromEntries(Object.entries(keys).map(([key, value]) => [key, value[language]]));
    plan.patch(`${PATHS.catalogs}/${language}.json`, (text) => addCatalogKeys(text, entries), `catalog keys ${language}`);
  }
  const script = resolve(plan.root, PATHS.syncRussian);
  if (!plan.pending.has(`${PATHS.catalogs}/ru.json`)) return;
  if (sync && fs.existsSync(script)) {
    plan.after('npm run i18n:sync-ru (apps/web)', () => {
      const result = spawnSync(process.execPath, [script], { cwd: resolve(plan.root, PATHS.webRoot), encoding: 'utf8' });
      if (result.status !== 0) throw new CliError(`i18n:sync-ru failed: ${result.stderr || result.stdout}`);
    });
  } else {
    plan.note('in apps/web: npm run i18n:sync-ru');
  }
}
