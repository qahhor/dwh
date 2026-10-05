// Names of modules, entities and fields, and what follows from them (ADR-0020 tables, ADR-0028 forms, ADR-0031 keys).
import { CliError, PATHS, ROOT_PACKAGE } from './layout.mjs';

const SEGMENT = /^[a-z][a-z0-9]*$/;
const ENTITY = /^[a-z][a-z0-9_]*$/;
const FIELD = /^[a-z][a-zA-Z0-9]{0,63}$/;

/** `inventory` -> `Inventory`, `task_types` -> `TaskTypes`. */
export function pascal(text) {
  return text
    .split(/[._]/)
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join('');
}

/** `inventory` -> `inventory`, `task_types` -> `taskTypes`. */
export function camel(text) {
  const p = pascal(text);
  return p ? p[0].toLowerCase() + p.slice(1) : '';
}

/** `dueOn` -> `due_on`. */
export function snake(key) {
  return key.replace(/([A-Z])/g, '_$1').toLowerCase();
}

/** `dueOn` -> `Due on`. */
export function humanize(key) {
  const words = snake(key).split('_').join(' ');
  return words[0].toUpperCase() + words.slice(1);
}

/**
 * A module: a business module code of one segment (`inventory`) or a module under a Biruni prefix (`ms.probe`). Its
 * permission area is its code, or a named area for a code of two segments (ADR-0028).
 */
export function moduleNames(code, options = {}) {
  const segments = String(code ?? '').split('.');
  if (segments.length < 1 || segments.length > 2 || !segments.every((s) => SEGMENT.test(s) && s.length <= 30)) {
    throw new CliError(`Module code: one or two lower-case segments of letters and digits (inventory, ms.probe): ${code}`);
  }
  const area = options.area ?? segments.at(-1);
  if (!SEGMENT.test(area)) throw new CliError(`Permission area: lower-case letters and digits: ${area}`);
  // The area is also the module's code in the registry and in its manifest (ModuleManifest.CODE, ADR-0033, 6.2).
  if (area.length < 2 || area.length > 32) throw new CliError(`Permission area: 2 to 32 characters: ${area}`);
  if (segments.length === 1 && area !== code) {
    throw new CliError(`A module of one segment is its own permission area (ADR-0028): ${code}`);
  }
  const tablePrefix = options.tablePrefix ?? segments.join('_');
  if (!/^[a-z][a-z0-9_]{1,30}$/.test(tablePrefix)) throw new CliError(`Table prefix: ${tablePrefix}`);
  return {
    code,
    segments,
    area,
    namedArea: segments.length === 2,
    tablePrefix,
    pascal: pascal(code),
    package: `${ROOT_PACKAGE}.${code}`,
    javaDir: `${PATHS.serverJava}/${segments.join('/')}`,
    testDir: `${PATHS.serverTest}/${segments.join('/')}`,
    manifest: `${PATHS.moduleManifests}/${area}.json`,
  };
}

/** An entity of a module: its code (also its form, ADR-0028), table, classes, keys and route. */
export function entityNames(module, entity) {
  if (!ENTITY.test(String(entity ?? '')) || entity.length > 40) {
    throw new CliError(`Entity name: lower-case letters, digits and underscores (items, task_types): ${entity}`);
  }
  const code = `${module.area}.${entity}`;
  const base = `${module.pascal}${pascal(entity)}`;
  const servicePackage = `${module.package}.service`;
  return {
    name: entity,
    code,
    form: code,
    table: `${module.tablePrefix}_${entity}`,
    alias: 't',
    entityClass: `${base}Entity`,
    hooksClass: `${base}Hooks`,
    testClass: `${base}ContractTest`,
    beanMethod: `${module.segments[0]}${pascal(module.segments.slice(1).join('_'))}${pascal(entity)}Definition`,
    servicePackage,
    entityFile: `${module.javaDir}/service/${base}Entity.java`,
    hooksFile: `${module.javaDir}/service/${base}Hooks.java`,
    testFile: `${module.testDir}/${base}ContractTest.java`,
    navKey: `nav.${module.area}_${entity}`,
    route: `/e/${code}`,
  };
}

/** A field key of a declaration (the rule of EntityField). */
export function fieldKey(key) {
  if (!FIELD.test(String(key ?? ''))) throw new CliError(`Field key: camelCase letters and digits (dueOn): ${key}`);
  if (['id', 'revision', 'createdAt', 'createdBy', 'modifiedAt', 'modifiedBy', 'attributes', 'archived', 'archivedAt'].includes(key)) {
    throw new CliError(`The key ${key} is the record's own`);
  }
  return key;
}

/** The dictionary key of a field's label: `<entity code>.col.<field in snake_case>` (ADR-0031). */
export function labelKey(entity, key) {
  return `${entity.code}.col.${snake(key)}`;
}
