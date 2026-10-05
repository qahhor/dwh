// The CLI's own tests (plan 10/10, item 6.1): node:test, no packages. They run the commands on a small copy of the
// repository's registries in a temporary directory; the full build of the generated code is the smoke's job.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { entityAddField, entityNew, moduleNew } from '../lib/commands.mjs';
import { addCatalogKeys, addImports, addToSection } from '../lib/edits.mjs';
import { PATHS, resolve } from '../lib/layout.mjs';
import { main } from '../lib/main.mjs';
import { createsTable, hasColumn, highestVersion, manifestHash, versionName } from '../lib/migrations.mjs';
import { entityNames, moduleNames } from '../lib/names.mjs';
import { Plan } from '../lib/plan.mjs';
import { diffMigration, sectionLine } from '../lib/templates.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const COPIED = [
  PATHS.serverPom,
  PATHS.manifest,
  PATHS.permissionAreas,
  PATHS.moduleBoundaries,
  PATHS.moduleMap,
  PATHS.coverageFloors,
  PATHS.schemaOrderTest,
  `${PATHS.catalogs}/ru.json`,
  `${PATHS.catalogs}/uz.json`,
  `${PATHS.catalogs}/en.json`,
  `${PATHS.migrations}/V181__example_orders.sql`,
  `${PATHS.migrations}/V195__project_view_author.sql`,
];

let root;
// The highest migration of the copied manifest: the expected numbers follow it, so a new migration in the repository
// does not change what these tests expect.
let base;
const quiet = () => {};
/** The version the n-th migration a test writes takes: V<base + n>. */
const v = (n) => versionName(base + n);

function run(command) {
  const plan = new Plan(root, { eol: '\n' });
  command(plan);
  plan.apply({ log: quiet });
  return plan;
}

function read(relative) {
  return fs.readFileSync(resolve(root, relative), 'utf8').replace(/\r\n/g, '\n');
}

function exists(relative) {
  return fs.existsSync(resolve(root, relative));
}

function snapshot() {
  const files = {};
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else files[full] = fs.readFileSync(full, 'utf8');
    }
  };
  walk(root);
  return files;
}

const MODULE = { title: 'Склад', 'title-en': 'Inventory', 'title-uz': 'Ombor' };
const ENTITY = { title: 'Товары', 'title-en': 'Items', 'title-uz': 'Tovarlar', icon: 'package', hooks: true, 'no-sync': true };

beforeEach(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'cms-cli-test-'));
  for (const file of COPIED) {
    fs.mkdirSync(path.dirname(resolve(root, file)), { recursive: true });
    fs.copyFileSync(resolve(repo, file), resolve(root, file));
  }
  base = Number(highestVersion(root, new Plan(root)));
});

afterEach(() => fs.rmSync(root, { recursive: true, force: true }));

describe('names', () => {
  test('a module of one segment is its own area, a module under a prefix gets a named one', () => {
    const inventory = moduleNames('inventory');
    assert.equal(inventory.area, 'inventory');
    assert.equal(inventory.tablePrefix, 'inventory');
    assert.equal(inventory.javaDir, `${PATHS.serverJava}/inventory`);
    const probe = moduleNames('ms.probe');
    assert.equal(probe.area, 'probe');
    assert.equal(probe.namedArea, true);
    assert.equal(probe.tablePrefix, 'ms_probe');
    const items = entityNames(probe, 'items');
    assert.equal(items.code, 'probe.items');
    assert.equal(items.table, 'ms_probe_items');
    assert.equal(items.entityClass, 'MsProbeItemsEntity');
    assert.equal(items.beanMethod, 'msProbeItemsDefinition');
    assert.equal(items.navKey, 'nav.probe_items');
  });

  test('bad codes are refused', () => {
    assert.throws(() => moduleNames('Inventory'), /Module code/);
    assert.throws(() => moduleNames('a.b.c'), /Module code/);
    assert.throws(() => moduleNames('inventory', { area: 'stock' }), /own permission area/);
    assert.throws(() => entityNames(moduleNames('inventory'), 'Items'), /Entity name/);
  });
});

describe('cms module new', () => {
  test('writes the package, the manifest, the area and the module lists', () => {
    run((plan) => moduleNew(plan, 'inventory', MODULE));
    assert.match(read(`${PATHS.serverJava}/inventory/package-info.java`), /package com\.smartup24\.cms\.instance\.inventory;/);
    // Exactly the fields of ADR-0033, 6.2: ModuleManifests refuses an unknown one at start.
    assert.deepEqual(JSON.parse(read(`${PATHS.moduleManifests}/inventory.json`)), {
      code: 'inventory',
      name: 'Склад',
      version: '${project.version}',
      minPlatform: '${platform-api.version}',
      dependencies: [{ code: 'iam', version: '${project.version}' }],
    });
    assert.equal(PATHS.moduleManifests, 'apps/server/src/main/resources/META-INF/smartupcms/modules');
    assert.match(read(PATHS.permissionAreas), /"inventory",\n\s+"jobs"/);
    assert.match(read(PATHS.moduleBoundaries), /"inventory",\n\s+"jobs"/);
    assert.match(read(PATHS.moduleBoundaries), /table\.startsWith\("inventory_"\)\) return Optional\.of\("inventory"\)/);
    assert.match(read(PATHS.moduleMap), /\| `inventory` \| `com\.smartup24\.cms\.instance\.inventory` \|[^\n]*\n\| `common` \|/);
  });

  test('a module under a prefix takes a named area', () => {
    run((plan) => moduleNew(plan, 'ms.probe', MODULE));
    assert.match(read(PATHS.permissionAreas), /"probe", "ms\.probe"\);/);
    // The manifest is named after the registry code, the area (ModuleManifests: <code>.json).
    assert.equal(JSON.parse(read(`${PATHS.moduleManifests}/probe.json`)).code, 'probe');
  });

  test('an entity reads the module back: named area, own table prefix, manifest name, shared icon', () => {
    run((plan) => moduleNew(plan, 'ms.probe', { ...MODULE, 'table-prefix': 'prb' }));
    run((plan) => entityNew(plan, 'ms.probe', 'items', ENTITY));
    run((plan) => entityNew(plan, 'ms.probe', 'parts', { ...ENTITY, icon: undefined }));
    const parts = read(`${PATHS.serverJava}/ms/probe/service/MsProbePartsEntity.java`);
    assert.match(parts, /\.table\("prb_parts", "t"\)/);
    assert.match(parts, /new EntityMenu\("nav\.probe_parts", "package", "workspace", 100, "probe"\)/);
    const rights = read(`${PATHS.migrations}/${v(2)}__prb_items_rights.sql`);
    assert.match(rights, /insert into md_installed_modules \(code, name, description, icon, route,/);
    assert.match(rights, /\('probe', 'Склад', 'Склад', 'package', '\/e\/probe\.items'/);
  });

  test('an area another module owns is refused and nothing is written', () => {
    const before = snapshot();
    assert.throws(() => run((plan) => moduleNew(plan, 'ms.tasks', { area: 'tasks' })), /belongs to ms\.task/);
    assert.deepEqual(snapshot(), before);
  });

  test('creates an external module with pom, manifest, translations, migration, and tests', () => {
    run((plan) => moduleNew(plan, 'library', { ...MODULE, external: true }));
    assert.ok(exists('modules/library/pom.xml'));
    assert.match(read('modules/library/pom.xml'), /<artifactId>library-module<\/artifactId>/);
    assert.match(read('modules/library/pom.xml'), /<groupId>com\.example\.library<\/groupId>/);

    const manifest = JSON.parse(read('modules/library/src/main/resources/META-INF/smartupcms/modules/library.json'));
    assert.equal(manifest.code, 'library');
    assert.equal(manifest.configuration, 'com.example.library.LibraryModule');
    assert.equal(manifest.migrations, 'db/modules/library');
    assert.equal(manifest.messages, 'META-INF/smartupcms/modules/library/i18n');

    for (const lang of ['ru', 'uz', 'en']) {
      const i18n = JSON.parse(read(`modules/library/src/main/resources/META-INF/smartupcms/modules/library/i18n/${lang}.json`));
      assert.ok(i18n['nav.library_items']);
      assert.ok(i18n['library.items.col.name']);
    }

    assert.match(
      read('modules/library/src/main/resources/db/modules/library/V1__library_items.sql'),
      /create table library_items \(/,
    );
    assert.match(
      read('modules/library/src/main/java/com/example/library/LibraryModule.java'),
      /public class LibraryModule/,
    );
    assert.match(
      read('modules/library/src/test/java/com/example/library/LibraryItemsContractTest.java'),
      /class LibraryItemsContractTest extends EntityContractTestKit/,
    );
    assert.match(
      read('modules/library/src/test/java/com/example/library/LibraryModuleBoundaryTest.java'),
      /class LibraryModuleBoundaryTest/,
    );

    // Re-run produces no changes
    const before = snapshot();
    const plan = run((p) => moduleNew(p, 'library', { ...MODULE, external: true }));
    assert.equal(plan.changes, 0);
    assert.deepEqual(snapshot(), before);
  });

  test('creates an external module in custom directory', () => {
    run((plan) => moduleNew(plan, 'billing', { ...MODULE, external: true, dir: 'custom/billing', package: 'com.acme.billing' }));
    assert.ok(exists('custom/billing/pom.xml'));
    assert.ok(exists('custom/billing/src/main/java/com/acme/billing/BillingModule.java'));
    const manifest = JSON.parse(read('custom/billing/src/main/resources/META-INF/smartupcms/modules/billing.json'));
    assert.equal(manifest.configuration, 'com.acme.billing.BillingModule');
  });
});

describe('cms entity new', () => {
  beforeEach(() => run((plan) => moduleNew(plan, 'inventory', MODULE)));

  test('writes the declaration, hooks, test, two pinned migrations, keys, floor and map entry', () => {
    run((plan) => entityNew(plan, 'inventory', 'items', ENTITY));
    const declaration = read(`${PATHS.serverJava}/inventory/service/InventoryItemsEntity.java`);
    assert.match(declaration, /Entity\.define\(CODE, CODE\)/);
    assert.match(declaration, /\.table\("inventory_items", "t"\)/);
    assert.match(declaration, /\.archivable\(\)/);
    assert.doesNotMatch(declaration, /[Ѐ-ӿ]/);
    assert.match(read(`${PATHS.serverJava}/inventory/service/InventoryItemsHooks.java`), /implements EntityHooks/);
    assert.match(read(`${PATHS.serverTest}/inventory/InventoryItemsContractTest.java`), /extends EntityContractTestKit/);

    const table = read(`${PATHS.migrations}/${v(1)}__inventory_items_table.sql`);
    assert.match(table, /^set lock_timeout = '2s';\nset statement_timeout = '60s';\n/);
    assert.match(table, /revision bigint not null default 1/);
    assert.match(table, /archived_by bigint constraint inventory_items_fk_archived_by references md_users \(id\)/);
    assert.match(table, /inventory_items_code_uq on inventory_items \(code\) where archived_at is null/);
    const rights = read(`${PATHS.migrations}/${v(2)}__inventory_items_rights.sql`);
    assert.match(rights, /\('inventory\.items', 'inventory', 'Товары'\)/);
    assert.match(rights, /'\/e\/inventory\.items'/);

    const manifest = read(PATHS.manifest);
    assert.ok(manifest.includes(`${manifestHash(table)}  db/migration/${v(1)}__inventory_items_table.sql`));
    assert.ok(manifest.includes(`${manifestHash(rights)}  db/migration/${v(2)}__inventory_items_rights.sql`));
    for (const language of ['ru', 'uz', 'en']) {
      const catalog = JSON.parse(read(`${PATHS.catalogs}/${language}.json`));
      assert.ok(catalog['nav.inventory_items'] && catalog['inventory.items.rights.delete'], language);
    }
    assert.equal(JSON.parse(read(`${PATHS.catalogs}/uz.json`))['nav.inventory_items'], 'Tovarlar');
    assert.match(read(PATHS.coverageFloors), /\ninventory,80,50\n$/);
    assert.match(read(PATHS.moduleMap), /`\/api\/v1\/entities\/inventory\.items` \(`InventoryItemsEntity`\) \|/);
  });

  test('a re-run changes nothing, and a hand edit is kept', () => {
    run((plan) => entityNew(plan, 'inventory', 'items', ENTITY));
    const hooks = `${PATHS.serverJava}/inventory/service/InventoryItemsHooks.java`;
    fs.writeFileSync(resolve(root, hooks), `${read(hooks)}// edited\n`);
    const before = snapshot();
    const plan = run((p) => entityNew(p, 'inventory', 'items', ENTITY));
    assert.equal(plan.changes, 0);
    assert.ok(plan.steps.some((step) => step.kind === 'kept' && step.path === hooks));
    assert.deepEqual(snapshot(), before);
  });

  test('a file of another entity at the path stops the command before any write', () => {
    const file = `${PATHS.serverJava}/inventory/service/InventoryItemsEntity.java`;
    fs.mkdirSync(path.dirname(resolve(root, file)), { recursive: true });
    fs.writeFileSync(resolve(root, file), 'class Other { String CODE = "other.thing"; }\n');
    const before = snapshot();
    assert.throws(() => run((plan) => entityNew(plan, 'inventory', 'items', ENTITY)), /Nothing was written/);
    assert.deepEqual(snapshot(), before);
  });

  test('a dry run writes nothing', () => {
    const before = snapshot();
    const plan = new Plan(root, { eol: '\n' });
    entityNew(plan, 'inventory', 'items', ENTITY);
    const lines = [];
    plan.apply({ dryRun: true, log: (line) => lines.push(line) });
    assert.ok(lines.some((line) => line.startsWith('[dry-run] create')));
    assert.deepEqual(snapshot(), before);
  });

  test('an explicit version is used, and one at or below the highest is refused', () => {
    run((plan) => entityNew(plan, 'inventory', 'items', { ...ENTITY, version: v(14) }));
    assert.ok(exists(`${PATHS.migrations}/${v(14)}__inventory_items_table.sql`));
    assert.ok(exists(`${PATHS.migrations}/${v(15)}__inventory_items_rights.sql`));
    assert.throws(() => run((plan) => entityNew(plan, 'inventory', 'parts', { ...ENTITY, version: v(4) })), /not above/);
  });

  test('the next number follows the highest pinned in the manifest, released or not on disk', () => {
    const manifest = resolve(root, PATHS.manifest);
    fs.appendFileSync(manifest, `${'0'.repeat(64)}  db/migration/${v(40)}__released_elsewhere.sql
`);
    run((plan) => entityNew(plan, 'inventory', 'items', ENTITY));
    assert.ok(exists(`${PATHS.migrations}/${v(41)}__inventory_items_table.sql`));
    assert.ok(exists(`${PATHS.migrations}/${v(42)}__inventory_items_rights.sql`));
  });

  test('an unknown module is refused', () => {
    assert.throws(() => run((plan) => entityNew(plan, 'nowhere', 'items', ENTITY)), /cms module new nowhere/);
  });
});

describe('cms entity add-field', () => {
  beforeEach(() => {
    run((plan) => moduleNew(plan, 'inventory', MODULE));
    run((plan) => entityNew(plan, 'inventory', 'items', ENTITY));
  });

  const declarationFile = () => `${PATHS.serverJava}/inventory/service/InventoryItemsEntity.java`;

  test('adds the field, its import, its section key, a column migration and its keys', () => {
    run((plan) => entityAddField(plan, 'inventory.items', 'dueOn', { type: 'date', label: 'Срок', 'no-sync': true }));
    const text = read(declarationFile());
    assert.match(text, /import static com\.smartup24\.cms\.platform\.api\.entity\.field\.EntityFields\.date;/);
    // At most 98 characters: one line, as palantir keeps it.
    assert.match(text, /\n {12}\.field\(date\("dueOn", "inventory\.items\.col\.due_on"\)\.column\("due_on"\)\.list\(sortable\(\)\)\)\n/);
    assert.match(text, /\.section\("main", "entity\.section\.main", "name", "code", "dueOn"\)/);
    assert.match(read(`${PATHS.migrations}/${v(3)}__inventory_items_due_on.sql`), /alter table inventory_items\n {4}add column due_on date;/);
    assert.equal(JSON.parse(read(`${PATHS.catalogs}/ru.json`))['inventory.items.col.due_on'], 'Срок');
    assert.equal(JSON.parse(read(`${PATHS.catalogs}/en.json`))['inventory.items.col.due_on'], 'Due on');
  });

  test('select, money and reference fields get their constants, columns and keys', () => {
    run((plan) => entityAddField(plan, 'inventory.items', 'stage', { type: 'select', options: 'draft,ready', 'no-sync': true }));
    run((plan) => entityAddField(plan, 'inventory.items', 'price', { type: 'money', currencies: 'UZS,USD', 'no-sync': true }));
    run((plan) => entityAddField(plan, 'inventory.items', 'parent', { type: 'ref', target: 'inventory.items', 'no-sync': true }));
    const text = read(declarationFile());
    assert.match(text, /public static final List<String> STAGE_OPTIONS = List\.of\("draft", "ready"\);/);
    assert.match(text, /\nimport java\.util\.List;\nimport java\.util\.Map;\n/);
    assert.match(text, /\.money\("price_amount", "price_currency"\)/);
    assert.match(text, /\.target\("inventory\.items", "name"\)/);
    assert.match(read(`${PATHS.migrations}/${v(3)}__inventory_items_stage.sql`), /check \(stage in \('draft', 'ready'\)\)/);
    assert.match(read(`${PATHS.migrations}/${v(4)}__inventory_items_price.sql`), /price_currency text constraint/);
    const ref = read(`${PATHS.migrations}/${v(5)}__inventory_items_parent.sql`);
    assert.match(ref, /parent_id bigint constraint inventory_items_fk_parent references inventory_items \(id\)/);
    assert.match(ref, /create index inventory_items_parent_id_idx on inventory_items \(parent_id\);/);
    assert.ok(JSON.parse(read(`${PATHS.catalogs}/uz.json`))['inventory.items.stage.ready']);
  });

  test('a required field is required on the form and nullable in the added column', () => {
    run((plan) => entityAddField(plan, 'inventory.items', 'amount', { type: 'number', required: true, 'no-sync': true }));
    assert.match(read(declarationFile()), /\.column\("amount"\)\n\s+\.required\(\)\n\s+\.scale\(2\)/);
    const sql = read(`${PATHS.migrations}/${v(3)}__inventory_items_amount.sql`);
    assert.match(sql, /add column amount numeric\(19, 2\);/);
    assert.match(sql, /-- amount is required on the form/);
  });

  test('a re-run changes nothing', () => {
    const add = (plan) => entityAddField(plan, 'inventory.items', 'dueOn', { type: 'date', 'no-sync': true });
    run(add);
    const before = snapshot();
    assert.equal(run(add).changes, 0);
    assert.deepEqual(snapshot(), before);
  });

  test('bad input is refused', () => {
    assert.throws(() => run((p) => entityAddField(p, 'inventory.items', 'due', { type: 'colour' })), /--type/);
    assert.throws(() => run((p) => entityAddField(p, 'inventory.items', 'Due', { type: 'date' })), /Field key/);
    assert.throws(() => run((p) => entityAddField(p, 'inventory.items', 'id', { type: 'text' })), /record's own/);
    assert.throws(() => run((p) => entityAddField(p, 'inventory.items', 's', { type: 'select' })), /--options/);
    assert.throws(() => run((p) => entityAddField(p, 'inventory.items', 'r', { type: 'ref' })), /--target/);
    assert.throws(() => run((p) => entityAddField(p, 'no.such', 'x', { type: 'text' })), /No entity declaration/);
  });
});

describe('edits and templates', () => {
  test('catalog keys are appended, an existing translation is kept', () => {
    const text = '{\n  "a.b.c": "one"\n}\n';
    assert.equal(addCatalogKeys(text, { 'a.b.c': 'other' }), text);
    assert.equal(addCatalogKeys(text, { 'a.b.d': 'two' }), '{\n  "a.b.c": "one",\n  "a.b.d": "two"\n}\n');
  });

  test('imports are ordered by name, static first', () => {
    const text = 'package p;\n\nimport static a.B.c;\n\nimport x.Y;\n\nclass Z {}\n';
    assert.equal(
      addImports(text, ['a.B.b'], ['x.Y.Inner', 'java.util.List']),
      'package p;\n\nimport static a.B.b;\nimport static a.B.c;\n\nimport java.util.List;\nimport x.Y;\nimport x.Y.Inner;\n\nclass Z {}\n',
    );
  });

  test('a long section is wrapped as palantir wraps it', () => {
    const keys = Array.from({ length: 6 }, (_, i) => `someField${i}`);
    assert.equal(sectionLine(keys.slice(0, 2)), '            .section("main", "entity.section.main", "someField0", "someField1")');
    const many = sectionLine(Array.from({ length: 12 }, (_, i) => `longFieldName${i}`));
    assert.match(many, /\n {20}"longFieldName11"\)$/);
    const text = `x\n${sectionLine(keys)}\ny`;
    assert.match(addToSection(text, 'main', 'extra', sectionLine), /"someField5",\n {20}"extra"\)\ny$/);
  });

  test('the manifest hash ignores line endings, as MigrationManifestTest does', () => {
    assert.equal(manifestHash('a\r\nb\r\n'), manifestHash('a\nb\n'));
  });

  test('the migrations tell which table and column they create', () => {
    assert.equal(createsTable(root, 'ex_orders'), true);
    assert.equal(createsTable(root, 'ex_nothing'), false);
    assert.equal(hasColumn(root, 'ex_orders', 'customer'), true);
    assert.equal(hasColumn(root, 'ex_orders', 'nothing'), false);
  });

  test('a diff migration starts with the timeout header', () => {
    assert.match(diffMigration(['alter table t add column c text;']), /^set lock_timeout = '2s';\nset statement_timeout = '60s';\n--/);
  });
});

describe('command line', () => {
  test('help, unknown commands and unknown options', () => {
    const lines = [];
    assert.equal(main(['--help'], (line) => lines.push(line)), 0);
    assert.match(lines.join('\n'), /cms entity add-field/);
    assert.equal(main(['frobnicate'], quiet), 2);
    assert.throws(() => main(['module', 'new', 'x', '--colour', 'red', '--root', root], quiet), /Unknown option/);
  });

  test('a command runs end to end through main', () => {
    const lines = [];
    assert.equal(main(['module', 'new', 'inventory', '--root', root, '--title', 'Склад'], (l) => lines.push(l)), 0);
    assert.ok(lines.some((line) => line.startsWith('create apps/server/src/main/java/com/smartup24/cms/instance/inventory/package-info.java')));
    assert.equal(main(['module', 'new', 'inventory', '--root', root, '--title', 'Склад'], (l) => lines.push(l)), 0);
    assert.equal(lines.at(-1), 'Nothing to change: the repository already has all of it.');
  });
});
