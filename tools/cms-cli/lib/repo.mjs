// What the CLI reads back from the repository: a module's manifest and names, an entity's declaration.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { addCatalogKeys, areaOwner } from './edits.mjs';
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

/** A module created by `cms module new` (or by hand): names from its manifest, else from PermissionAreas. */
export function loadModule(root, code) {
  const probe = moduleNames(code, code.includes('.') ? { area: code.split('.').at(-1) } : {});
  if (!fs.existsSync(resolve(root, probe.javaDir))) {
    throw new CliError(`No module ${code} at ${probe.javaDir}: run cms module new ${code} first`);
  }
  const manifestText = readText(root, probe.manifest);
  if (manifestText) {
    const manifest = JSON.parse(manifestText);
    return {
      ...moduleNames(code, { area: manifest.area, tablePrefix: manifest.tablePrefix }),
      icon: manifest.icon ?? 'box',
      title: manifest.title ?? { ru: pascal(code), en: pascal(code), uz: pascal(code) },
    };
  }
  const areas = readText(root, PATHS.permissionAreas) ?? '';
  const named = new RegExp(`"([a-z0-9_]+)",\\s*"${code.replace('.', '\\.')}"`).exec(areas);
  const area = named ? named[1] : code;
  if (!named && areaOwner(areas, area) !== code) {
    throw new CliError(`The module ${code} has no permission area in PermissionAreas (ADR-0028)`);
  }
  const title = pascal(code);
  return { ...moduleNames(code, { area }), icon: 'box', title: { ru: title, en: title, uz: title } };
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
