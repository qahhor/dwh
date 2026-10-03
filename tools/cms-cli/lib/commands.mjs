// The generating commands: cms module new, cms entity new, cms entity add-field (plan 10/10, item 6.1).
import {
  addBoundaryModule,
  addConstants,
  addCoverageFloor,
  addFieldLines,
  addImports,
  addModuleMapEntity,
  addModuleMapRow,
  addPermissionArea,
  addToSection,
  declaresField,
} from './edits.mjs';
import { list } from './args.mjs';
import { CliError, NEW_MODULE_FLOOR, PATHS } from './layout.mjs';
import { createsTable, hasColumn, planMigration, seedsForm } from './migrations.mjs';
import { entityNames, fieldKey, humanize, labelKey, moduleNames, pascal, snake } from './names.mjs';
import { findDeclaration, loadModule, planKeys, titles } from './repo.mjs';
import {
  FIELD_TYPES,
  columnOf,
  contractTest,
  entityDeclaration,
  entityHooks,
  entityKeys,
  fieldDeclaration,
  fieldKeys,
  fieldMigration,
  moduleManifest,
  moduleMapRow,
  packageInfo,
  rightsMigration,
  sectionLine,
  starterFields,
  tableMigration,
} from './templates.mjs';

const ICON = /^[a-z][a-z0-9_-]{0,40}$/;

/** `cms module new <code>`: the package with its purpose, the permission area, the module lists and the manifest. */
export function moduleNew(plan, code, values) {
  if (!code) throw new CliError('Usage: cms module new <code> [--title <ru>] [--title-en <en>] [--title-uz <uz>]');
  const names = moduleNames(code, { area: values.area, tablePrefix: values['table-prefix'] });
  const icon = values.icon ?? 'box';
  if (!ICON.test(icon)) throw new CliError(`Icon: a lower-case icon name (box, package): ${icon}`);
  const module = { ...names, icon, title: titles(values, pascal(code)), description: values.description };

  plan.create(`${module.javaDir}/package-info.java`, packageInfo(module), 'the module and its purpose');
  plan.create(module.manifest, moduleManifest(module), 'module manifest');
  plan.patch(PATHS.permissionAreas, (text) => addPermissionArea(text, module), `area ${module.area} -> ${module.code}`);
  plan.patch(PATHS.moduleBoundaries, (text) => addBoundaryModule(text, module), `module ${module.code}`);
  plan.patch(PATHS.moduleMap, (text) => addModuleMapRow(text, module, moduleMapRow(module)), `row ${module.code}`);
  plan.note(`describe the module in ${module.javaDir}/package-info.java and its row in ${PATHS.moduleMap}`);
  plan.note(`add an entity: cms entity new ${module.code} <entity> --title "<Название>" --title-en "<Title>"`);
  return module;
}

/**
 * `cms entity new <module> <entity>`: the declaration, optional hooks, the table and rights migrations, the catalog
 * keys, the contract test, the coverage floor and the module map entry.
 */
export function entityNew(plan, moduleCode, entityName, values) {
  if (!moduleCode || !entityName) throw new CliError('Usage: cms entity new <module> <entity> [--title <ru>] [--hooks]');
  const module = loadModule(plan.root, moduleCode);
  const entity = entityNames(module, entityName);
  const title = titles(values, humanize(entity.name));

  const ofEntity = (text) => text.includes(`"${entity.code}"`) || text.includes(`${entity.entityClass}.CODE`);
  plan.create(entity.entityFile, entityDeclaration(module, entity, { title }), `the declaration of ${entity.code}`, ofEntity);
  if (values.hooks) plan.create(entity.hooksFile, entityHooks(entity), `the hooks of ${entity.code}`, ofEntity);
  plan.create(entity.testFile, contractTest(module, entity), `the contract test of ${entity.code}`, ofEntity);

  if (createsTable(plan.root, entity.table)) {
    plan.note(`a migration already creates ${entity.table}: no new table migration`);
  } else {
    const version = planMigration(plan, `${entity.table}_table`, tableMigration(entity, starterFields(entity)), values.version);
    if (!seedsForm(plan.root, entity.form)) {
      planMigration(plan, `${entity.table}_rights`, rightsMigration(module, entity, version, title));
    }
  }
  planKeys(plan, entityKeys(entity, title), { sync: !values['no-sync'] });
  plan.patch(PATHS.coverageFloors, (text) => addCoverageFloor(text, module, NEW_MODULE_FLOOR), `floor ${module.code}`);
  plan.patch(PATHS.moduleMap, (text) => addModuleMapEntity(text, module, entity), `entry ${entity.code}`);

  plan.note(`check the uz/en texts of the new keys in ${PATHS.catalogs}`);
  plan.note(
    `build and test: mvnw -B -pl apps/server -am test -Dtest=${entity.testClass},EntitySchemaDiffTest ` +
      '-Dsurefire.failIfNoSpecifiedTests=false',
  );
  plan.note(`API description: mvnw -B -pl apps/server test -Dtest=OpenApiContractTest -Dopenapi.update=true; then npm run api:types in apps/web`);
  plan.note(`open ${entity.route} after the migration (scripts/dev/run-local or the Compose stack); the screen is generic`);
  plan.note(`after the first mvn verify set the floor of ${module.code} in ${PATHS.coverageFloors} to the measured value`);
  return entity;
}

/** The field a `cms entity add-field` command describes, checked against the type's needs. */
export function describeField(plan, entity, key, values) {
  fieldKey(key);
  const type = values.type;
  if (!FIELD_TYPES[type]) {
    throw new CliError(`--type: one of ${Object.keys(FIELD_TYPES).join(', ')}`);
  }
  const field = {
    key,
    type,
    column: values.column ?? columnOf(type, key),
    required: Boolean(values.required),
    labelKey: labelKey(entity, key),
    label: titles(values, humanize(key)),
    list: values['no-list'] ? null : FIELD_TYPES[type].list,
  };
  if (!/^[a-z_][a-z0-9_]*$/.test(field.column)) throw new CliError(`Column: ${field.column}`);
  if (['text', 'textarea', 'markdown'].includes(type)) {
    field.max = values.max ? Number(values.max) : FIELD_TYPES[type].max;
    if (!Number.isInteger(field.max) || field.max < 1) throw new CliError(`--max: a positive whole number: ${values.max}`);
    if (field.required) field.min = 1;
  }
  if (type === 'number') {
    field.scale = values.scale === undefined ? 2 : Number(values.scale);
    if (!Number.isInteger(field.scale) || field.scale < 0 || field.scale > 6) throw new CliError('--scale: 0..6');
  }
  if (type === 'select') {
    field.options = list(values.options);
    if (!field.options.length || !field.options.every((o) => /^[a-z][a-z0-9_]*$/.test(o))) {
      throw new CliError('--options: lower-case values separated by commas (new,active,closed)');
    }
  }
  if (type === 'money') {
    field.currencies = list(values.currencies ?? 'UZS');
    if (!field.currencies.every((c) => /^[A-Z]{3}$/.test(c))) throw new CliError('--currencies: ISO 4217 codes (UZS,USD)');
  }
  if (type === 'ref') {
    if (!values.target) throw new CliError('--target: the code of the entity the reference names (inventory.items)');
    field.target = values.target;
    field.targetLabel = values['target-label'] ?? 'name';
    field.targetTable = findDeclaration(plan.root, values.target).table;
    if (field.required) plan.note(`the kit cannot make up a reference: give ${key} a value in fixture(...) of the test`);
  }
  return field;
}

/** `cms entity add-field <entity> <field> --type <type>`: the declaration, a column migration and the keys. */
export function entityAddField(plan, code, key, values) {
  if (!code || !key || !values.type) {
    throw new CliError('Usage: cms entity add-field <entity code> <field> --type <type> [--required] [--label <ru>]');
  }
  const declaration = findDeclaration(plan.root, code);
  if (!declaration.table) throw new CliError(`${declaration.file} declares no table`);
  const module = loadModule(plan.root, declaration.module);
  const entity = { ...entityNames(module, code.split('.').at(-1)), code, table: declaration.table };
  const field = describeField(plan, entity, key, values);
  const declared = fieldDeclaration(entity, field);
  const section = values.section ?? 'main';

  plan.patch(
    declaration.file,
    (text) => {
      if (declaresField(text, key)) return text;
      let result = addImports(text, declared.staticImports, declared.imports);
      result = addConstants(result, declared.constants);
      result = addFieldLines(result, declared.lines);
      return addToSection(result, section, key, (keys, name, label) => sectionLine(keys, name, label));
    },
    `field ${key}`,
  );
  const columns = field.type === 'money' ? [`${field.column}_amount`] : [field.column];
  if (columns.every((column) => hasColumn(plan.root, entity.table, column))) {
    plan.note(`a migration already gives ${entity.table} the column ${columns[0]}: no new migration`);
  } else {
    planMigration(plan, `${entity.table}_${snake(key)}`, fieldMigration(entity, field), values.version);
  }
  planKeys(plan, fieldKeys(entity, field), { sync: !values['no-sync'] });
  plan.note(`check the texts of ${field.labelKey} in ${PATHS.catalogs}`);
  plan.note('cms migration diff confirms the schema has every declared column');
  return field;
}
