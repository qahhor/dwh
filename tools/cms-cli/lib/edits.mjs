// Idempotent edits of the files that list modules and keys: each returns the text unchanged when the entry is there.
import { CliError } from './layout.mjs';

// ---------------------------------------------------------------------------------------------------------------
// Catalogs (apps/server/src/main/resources/i18n/<language>.json): one `"key": "value"` per line, keys appended.

function jsonText(value) {
  return JSON.stringify(String(value));
}

/** Appends the keys the catalog does not have yet; an existing key keeps its translation. */
export function addCatalogKeys(text, entries) {
  const catalog = JSON.parse(text);
  const added = Object.entries(entries)
    .filter(([key]) => !Object.hasOwn(catalog, key))
    .map(([key, value]) => `  ${jsonText(key)}: ${jsonText(value)}`);
  if (!added.length) return text;
  const end = text.lastIndexOf('}');
  const head = text.slice(0, end).trimEnd();
  const comma = head.endsWith('{') ? '' : ',';
  return `${head}${comma}\n${added.join(',\n')}\n${text.slice(end)}`;
}

// ---------------------------------------------------------------------------------------------------------------
// PermissionAreas (ADR-0028): a module of one segment is an area itself, a module under a prefix gets a named area.

export function areaOwner(text, area) {
  const named = new RegExp(`"${area}",\\s*"([a-z0-9.]+)"`).exec(namedAreasBlock(text));
  if (named) return named[1];
  return moduleAreasBlock(text).includes(`"${area}"`) ? area : null;
}

function moduleAreasBlock(text) {
  const start = text.indexOf('MODULE_AREAS = Set.of(');
  if (start < 0) throw new CliError('PermissionAreas.MODULE_AREAS not found');
  return text.slice(start, text.indexOf(');', start));
}

function namedAreasBlock(text) {
  const start = text.indexOf('NAMED_AREAS = Map.of(');
  if (start < 0) throw new CliError('PermissionAreas.NAMED_AREAS not found');
  return text.slice(start, text.indexOf(');', start));
}

export function addPermissionArea(text, module) {
  const owner = areaOwner(text, module.area);
  if (owner === module.code) return text;
  if (owner) throw new CliError(`The permission area ${module.area} belongs to ${owner} (PermissionAreas)`);
  if (module.namedArea) {
    const block = namedAreasBlock(text);
    const pairs = [...block.matchAll(/"([a-z0-9_]+)",\s*"([a-z0-9.]+)"/g)].map((m) => [m[1], m[2]]);
    if (pairs.length >= 10) throw new CliError('NAMED_AREAS holds 10 pairs, the most Map.of takes: use Map.ofEntries');
    pairs.push([module.area, module.code]);
    const body = pairs.map(([area, owning]) => `            "${area}", "${owning}"`).join(',\n');
    return text.replace(block, `NAMED_AREAS = Map.of(\n${body}`);
  }
  const block = moduleAreasBlock(text);
  const areas = [...block.matchAll(/"([a-z0-9_]+)"/g)].map((m) => m[1]);
  areas.push(module.area);
  areas.sort();
  return text.replace(block, `MODULE_AREAS = Set.of(\n${areas.map((a) => `            "${a}"`).join(',\n')}`);
}

// ---------------------------------------------------------------------------------------------------------------
// ModuleBoundariesTest: the module list and the owner of its table prefix.

export function addBoundaryModule(text, module) {
  const start = text.indexOf('MODULES = List.of(');
  if (start < 0) throw new CliError('ModuleBoundariesTest.MODULES not found');
  const block = text.slice(start, text.indexOf(');', start));
  const modules = [...block.matchAll(/"([a-z0-9.]+)"/g)].map((m) => m[1]);
  let result = text;
  if (!modules.includes(module.code)) {
    modules.push(module.code);
    modules.sort();
    result = result.replace(block, `MODULES = List.of(\n${modules.map((m) => `            "${m}"`).join(',\n')}`);
  }
  const owner = `        if (table.startsWith("${module.tablePrefix}_")) return Optional.of("${module.code}");`;
  if (!result.includes(owner)) {
    const anchor = result.indexOf('        for (String module : List.of(');
    if (anchor < 0) throw new CliError('ModuleBoundariesTest.ownerOf not found');
    result = `${result.slice(0, anchor)}${owner}\n${result.slice(anchor)}`;
  }
  return result;
}

/** Adds a table to the tables SchemaOrderTest expects an `attributes` object check on. */
export function addAttributesTable(text, table) {
  const anchor = text.indexOf('void attributesAreObjects()');
  if (anchor < 0) throw new CliError('SchemaOrderTest.attributesAreObjects not found');
  const start = text.indexOf('.containsOnlyKeys(', anchor);
  const end = text.indexOf(')', start + '.containsOnlyKeys('.length);
  if (start < 0 || end < 0) throw new CliError('SchemaOrderTest: the list of attributes tables not found');
  const block = text.slice(start, end);
  const tables = [...block.matchAll(/"([a-z0-9_]+)"/g)].map((m) => m[1]);
  if (tables.includes(table)) return text;
  tables.push(table);
  tables.sort();
  const indent = ' '.repeat(24);
  return `${text.slice(0, start)}.containsOnlyKeys(\n${tables.map((t) => `${indent}"${t}"`).join(',\n')}${text.slice(end)}`;
}

// ---------------------------------------------------------------------------------------------------------------
// The module map (docs/architecture/module-map.md, ModuleMapTest) and the coverage floors.

export function addModuleMapRow(text, module, row) {
  if (text.includes(`| \`${module.code}\` | \`${module.package}\` |`)) return text;
  const anchor = text.indexOf('| `common` |');
  if (anchor < 0) throw new CliError('The module map has no `common` row to insert before');
  return `${text.slice(0, anchor)}${row}\n${text.slice(anchor)}`;
}

/** Adds the entity's runtime path to the entry points of its module's row. */
export function addModuleMapEntity(text, module, entity) {
  const lines = text.split('\n');
  const index = lines.findIndex((line) => line.startsWith(`| \`${module.code}\` | \`${module.package}\` |`));
  if (index < 0) throw new CliError(`The module map has no row for ${module.code}: run cms module new ${module.code}`);
  const path = `/api/v1/entities/${entity.code}`;
  if (lines[index].includes(path)) return text;
  lines[index] = lines[index].replace(/ \|\s*$/, `; \`${path}\` (\`${entity.entityClass}\`) |`);
  return lines.join('\n');
}

export function addCoverageFloor(text, module, floor) {
  if (new RegExp(`^${module.code.replace('.', '\\.')},`, 'm').test(text)) return text;
  return `${text.endsWith('\n') ? text : `${text}\n`}${module.code},${floor.line},${floor.branch}\n`;
}

// ---------------------------------------------------------------------------------------------------------------
// A declaration (Entity.define): a field, its imports, constants and its place in a section.

/** The declaration already has the field. */
export function declaresField(text, key) {
  return new RegExp(`\\.field\\([a-zA-Z]+\\(\\s*"${key}"`).test(text);
}

/** The table and alias the declaration names. */
export function declaredTable(text) {
  const match = /\.table\(\s*"([a-z_][a-z0-9_]*)"\s*,\s*"([a-z_][a-z0-9_]*)"/.exec(text);
  return match ? { table: match[1], alias: match[2] } : null;
}

export function addImports(text, staticImports, imports) {
  const lines = text.split('\n');
  const first = lines.findIndex((line) => line.startsWith('import '));
  if (first < 0) throw new CliError('The declaration has no imports');
  let last = first;
  for (let i = first; i < lines.length; i++) {
    if (lines[i].startsWith('import ')) last = i;
    else if (lines[i].trim() !== '') break;
  }
  const existing = lines.slice(first, last + 1).filter((line) => line.startsWith('import '));
  const statics = new Set(existing.filter((l) => l.startsWith('import static ')));
  const plain = new Set(existing.filter((l) => !l.startsWith('import static ')));
  staticImports.forEach((name) => statics.add(`import static ${name};`));
  imports.forEach((name) => plain.add(`import ${name};`));
  // By the imported name, as palantir orders them: `EntityDefinition` before `EntityDefinition.EntityMenu`.
  const byName = (a, b) => {
    const left = a.replace(/;$/, '');
    const right = b.replace(/;$/, '');
    return left < right ? -1 : left > right ? 1 : 0;
  };
  const block = [...[...statics].sort(byName), ...(statics.size && plain.size ? [''] : []), ...[...plain].sort(byName)];
  return [...lines.slice(0, first), ...block, ...lines.slice(last + 1)].join('\n');
}

/** Adds constants after the entity's CODE constant (or the class's opening brace). */
export function addConstants(text, constants) {
  const missing = constants.filter((constant) => !text.includes(constant.trim().split(' = ')[0]));
  if (!missing.length) return text;
  const code = /\n {4}public static final String CODE = [^\n]*\n/.exec(text);
  const at = code ? code.index + code[0].length : text.indexOf('{\n') + 2;
  return `${text.slice(0, at)}\n${missing.join('\n')}\n${text.slice(at)}`;
}

/** Inserts the field lines before the first `.section(`. */
export function addFieldLines(text, lines) {
  const at = text.search(/\n\s*\.section\(/);
  if (at < 0) throw new CliError('The declaration has no .section(...) to place the field before');
  return `${text.slice(0, at)}\n${lines.join('\n')}${text.slice(at)}`;
}

/** Adds the key to the section, rewritten in the layout palantir gives it. */
export function addToSection(text, section, key, layout) {
  const pattern = new RegExp(`\\n\\s*\\.section\\(\\s*"${section}"\\s*,\\s*"([^"]+)"((?:\\s*,\\s*"[^"]*")*)\\s*\\)`);
  const match = pattern.exec(text);
  if (!match) throw new CliError(`The declaration has no section "${section}"`);
  const keys = [...match[2].matchAll(/"([^"]*)"/g)].map((m) => m[1]);
  if (keys.includes(key)) return text;
  keys.push(key);
  return `${text.slice(0, match.index)}\n${layout(keys, section, match[1])}${text.slice(match.index + match[0].length)}`;
}
