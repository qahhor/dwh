import fs from 'node:fs';
import path from 'node:path';
import {
  addAttributesTable,
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
import { CliError, NEW_MODULE_FLOOR, PATHS, platformVersions, resolve } from './layout.mjs';
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
  externalBoundaryTest,
  externalContractTest,
  externalKeys,
  externalModuleDeclaration,
  externalModuleManifest,
  externalPackageInfo,
  externalPom,
  externalTableMigration,
  fieldDeclaration,
  fieldKeys,
  fieldMigration,
  moduleManifest,
  moduleKeys,
  moduleMapRow,
  packageInfo,
  rightsMigration,
  sectionLine,
  starterFields,
  tableMigration,
} from './templates.mjs';

const ICON = /^[a-z][a-z0-9_-]{0,40}$/;

/**
 * `cms module new <code>`: the package with its purpose, the permission area, the module lists, the manifest and the
 * module's name in the catalogs.
 */
export function moduleNew(plan, code, values) {
  if (!code) throw new CliError('Usage: cms module new <code> [--title <ru>] [--title-en <en>] [--title-uz <uz>]');
  if (values.external) {
    return externalModuleNew(plan, code, values);
  }
  const names = moduleNames(code, { area: values.area, tablePrefix: values['table-prefix'] });
  const module = { ...names, title: titles(values, pascal(code)) };

  plan.create(`${module.javaDir}/package-info.java`, packageInfo(module), 'the module and its purpose');
  plan.create(module.manifest, moduleManifest(module), 'module manifest');
  planKeys(plan, moduleKeys(module), { sync: !values['no-sync'] });
  plan.patch(PATHS.permissionAreas, (text) => addPermissionArea(text, module), `area ${module.area} -> ${module.code}`);
  plan.patch(PATHS.moduleBoundaries, (text) => addBoundaryModule(text, module), `module ${module.code}`);
  plan.patch(PATHS.moduleMap, (text) => addModuleMapRow(text, module, moduleMapRow(module)), `row ${module.code}`);
  plan.note(`describe the module in ${module.javaDir}/package-info.java and its row in ${PATHS.moduleMap}`);
  plan.note(`add an entity: cms entity new ${module.code} <entity> --title "<Название>" --title-en "<Title>"`);
  return module;
}

/** `cms module new <code> --external`: a standalone module outside the monorepo (ADR-0033). */
export function externalModuleNew(plan, code, values) {
  if (!/^[a-z][a-z0-9_]*$/.test(code ?? '')) {
    throw new CliError(`Module code: lower-case letters, digits and underscores: ${code}`);
  }
  const title = titles(values, pascal(code));
  const dir = (values.dir ?? `modules/${code}`).replace(/\\/g, '/');
  const pkg = values.package ?? `com.example.${code}`;
  const group = values.group ?? `com.example.${code}`;
  const tablePrefix = values['table-prefix'] ?? snake(code);
  const pkgPath = pkg.split('.').join('/');

  const module = {
    code,
    title,
    dir,
    package: pkg,
    group,
    tablePrefix,
    pascal: pascal(code),
  };

  // A directory outside the repository (absolute, or above its root) is a standalone project: it names the platform's
  // versions itself (ADR-0033, 13). Inside the repository the module takes the root pom as its parent.
  const outside = path.isAbsolute(dir) || path.posix.normalize(dir).startsWith('../');
  const relPom = path.posix.relative(dir, '.').replace(/\\/g, '/') + '/pom.xml';
  const hasParent = !outside && fs.existsSync(path.join(resolve(plan.root, dir), relPom));
  const platform = platformVersions(plan.root);

  plan.create(`${dir}/pom.xml`, externalPom(module, { hasParent, relPom, platform }), 'external module pom.xml');
  plan.create(
    `${dir}/src/main/resources/META-INF/smartupcms/modules/${code}.json`,
    externalModuleManifest(module, platform),
    'module manifest',
  );
  const keys = externalKeys(module, title);
  for (const lang of ['ru', 'uz', 'en']) {
    plan.create(
      `${dir}/src/main/resources/META-INF/smartupcms/modules/${code}/i18n/${lang}.json`,
      JSON.stringify(keys[lang], null, 2) + '\n',
      `${lang} translations`,
    );
  }
  plan.create(
    `${dir}/src/main/resources/db/modules/${code}/V1__${code}_items.sql`,
    externalTableMigration(module),
    'starter migration',
  );
  plan.create(
    `${dir}/src/main/java/${pkgPath}/package-info.java`,
    externalPackageInfo(module),
    'package info',
  );
  plan.create(
    `${dir}/src/main/java/${pkgPath}/${module.pascal}Module.java`,
    externalModuleDeclaration(module),
    'module declaration',
  );
  plan.create(
    `${dir}/src/test/java/${pkgPath}/${module.pascal}ItemsContractTest.java`,
    externalContractTest(module),
    'contract test',
  );
  plan.create(
    `${dir}/src/test/java/${pkgPath}/${module.pascal}ModuleBoundaryTest.java`,
    externalBoundaryTest(module),
    'module boundary test',
  );

  plan.note(`external module created in ${dir}`);
  plan.note(
    hasParent
      ? `build: cd ${dir} && mvn verify (the root pom is its parent)`
      : `build: cd ${dir} && mvn verify (the platform ${platform.appVersion} and its API ${platform.apiVersion} must be in ` +
          'your Maven repository: mvn install -DskipTests in the platform repository)',
  );
  plan.note(`deploy: copy ${dir}/target/${code}-module-*.jar to modules/ directory for Docker`);
  return module;
}

/**
 * `cms entity new <module> <entity>`: the declaration, optional hooks, the table and rights migrations, the catalog
 * keys, the contract test, the coverage floor and the module map entry.
 */
export function entityNew(plan, moduleCode, entityName, values) {
  if (!moduleCode || !entityName) throw new CliError('Usage: cms entity new <module> <entity> [--title <ru>] [--hooks]');
  if (values.icon !== undefined && !ICON.test(values.icon)) {
    throw new CliError(`Icon: a lower-case icon name (box, package): ${values.icon}`);
  }
  const module = loadModule(plan.root, moduleCode, values.icon);
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
  plan.patch(PATHS.schemaOrderTest, (text) => addAttributesTable(text, entity.table), `attributes ${entity.table}`);
  plan.patch(PATHS.coverageFloors, (text) => addCoverageFloor(text, module, NEW_MODULE_FLOOR), `floor ${module.code}`);
  plan.patch(PATHS.moduleMap, (text) => addModuleMapEntity(text, module, entity), `entry ${entity.code}`);

  plan.note(`check the uz/en texts of the new keys in ${PATHS.catalogs}`);
  plan.note(
    `build and test: mvnw -B -pl apps/server -am test -Dtest=${entity.testClass},EntitySchemaContractTest ` +
      '-Dsurefire.failIfNoSpecifiedTests=false',
  );
  plan.note(`API description: mvnw -B -pl apps/server test -Dtest=OpenApiContractTest -Dopenapi.update=true; then npm run api:types in apps/web`);
  plan.note(`open ${entity.route} once the server runs the migration (Compose: docker compose build server, docker compose run --rm migrate, docker compose up -d); the screen is generic`);
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
