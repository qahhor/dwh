// The CLI smoke (plan 10/10, item 6.1): in a temporary copy of the repository it creates a module, an entity with
// hooks and three fields, checks that a re-run changes nothing and that a hand edit is never overwritten, lets
// `cms migration diff` write a migration the declaration needs, and builds the server with the result: Spotless,
// Error Prone, Checkstyle, the architecture and migration tests, the entity contract kit and the schema comparison.
// Then a module outside the monorepo (ADR-0033, 13): `cms module new --external` into a directory outside the copy,
// the platform installed into a Maven repository of the smoke's own (the developer's is read, never written), and the
// generated module built standalone against it, offline outside CI, with its contract kit.
// CI runs it on Linux and Windows (.github/workflows/nightly.yml); scripts/dev/test-cms-cli.ps1 runs it locally.
//
//   node tools/cms-cli/scripts/smoke.mjs [--keep] [--skip-build]
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PATHS, platformVersions, resolve } from '../lib/layout.mjs';
import { runMaven } from '../lib/toolchain.mjs';

const keep = process.argv.includes('--keep');
const skipBuild = process.argv.includes('--skip-build');
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const work = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP ?? os.tmpdir(), 'cms-cli-smoke-'));
// Outside the copy of the repository, so no pom above it makes the module a part of the reactor.
const outside = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP ?? os.tmpdir(), 'cms-cli-external-'));
const started = Date.now();

const TESTS = [
  'ErrorTextsTest',
  'ErrorModelTest',
  'NoSwallowedErrorsTest',
  'ModuleBoundariesTest',
  'ModularArchitectureTest',
  'ModuleMapTest',
  'ChangesNameTheirRevisionTest',
  'ResponseStatusDeclaredTest',
  'CollectionsArePagedTest',
  'CommentLanguageTest',
  'MigrationLintTest',
  'MigrationFileRulesTest',
  'MigrationManifestTest',
  'SchemaOrderTest',
  'EntityActionPermissionContractTest',
  'EntityFieldContractTest',
  'EntityFieldsSingleSourceTest',
  'EntityScopeDeclaredTest',
  'PermissionCodesTest',
  'MdPermissionEntityNamesTest',
  'MdFormCatalogTest',
  'RbacSystemRolesIntegrationTest',
  'A1InstanceRolesTest',
  // The generated entity: its contract kit, every entity has one, the schema has every declared column, and its API
  // description builds (written as the CLI tells the developer to).
  'MsProbeItemsContractTest',
  'EntityContractCoverageTest',
  'EntitySchemaDiffTest',
  'EntitySchemaContractTest',
  // The generated manifest passes the manifest check of the start (ADR-0033, 6.2).
  'ModuleManifestsTest',
  'OpenApiContractTest',
];

function check(condition, message) {
  if (!condition) throw new Error(`cms-cli smoke: ${message}`);
}

function cms(...args) {
  const result = spawnSync(process.execPath, [resolve(work, 'tools/cms-cli/bin/cms.mjs'), ...args, '--root', work], {
    encoding: 'utf8',
  });
  return { status: result.status, out: `${result.stdout}${result.stderr}` };
}

function cmsOk(...args) {
  const result = cms(...args);
  process.stdout.write(result.out);
  check(result.status === 0, `cms ${args.join(' ')} failed`);
  return result.out;
}

function read(relative) {
  return fs.readFileSync(resolve(work, relative), 'utf8');
}

function copyRepository() {
  const files = execFileSync('git', ['-C', repo, 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  })
    .split('\0')
    .filter(Boolean);
  for (const file of files) {
    const source = path.join(repo, file);
    if (!fs.existsSync(source) || !fs.statSync(source).isFile()) continue;
    const target = path.join(work, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(source, target);
  }
  if (process.platform !== 'win32') fs.chmodSync(path.join(work, 'mvnw'), 0o755);
}

function generate() {
  cmsOk('module', 'new', 'ms.probe', '--title', 'Probe\'s "items"', '--title-en', 'Probe items', '--title-uz', 'Sinov');
  cmsOk('entity', 'new', 'ms.probe', 'items', '--title', 'Записи', '--title-en', 'Items', '--title-uz', 'Yozuvlar', '--hooks');
  cmsOk('entity', 'add-field', 'probe.items', 'dueOn', '--type', 'date', '--label', 'Срок', '--label-en', 'Due on');
  cmsOk('entity', 'add-field', 'probe.items', 'amount', '--type', 'number', '--required', '--label', 'Сумма');
  cmsOk('entity', 'add-field', 'probe.items', 'stage', '--type', 'select', '--options', 'draft,ready', '--label', 'Этап');
}

function checkRerunAndEdits() {
  const commands = [
    ['module', 'new', 'ms.probe', '--title', 'Probe\'s "items"', '--title-en', 'Probe items', '--title-uz', 'Sinov'],
    ['entity', 'new', 'ms.probe', 'items', '--title', 'Записи', '--title-en', 'Items', '--title-uz', 'Yozuvlar', '--hooks'],
    ['entity', 'add-field', 'probe.items', 'dueOn', '--type', 'date', '--label', 'Срок', '--label-en', 'Due on'],
  ];
  for (const command of commands) {
    const out = cmsOk(...command);
    check(out.includes('Nothing to change'), `a re-run of cms ${command.slice(0, 2).join(' ')} changes nothing`);
  }
  const declaration = 'apps/server/src/main/java/com/smartup24/cms/instance/ms/probe/service/MsProbeItemsEntity.java';
  const hooks = 'apps/server/src/main/java/com/smartup24/cms/instance/ms/probe/service/MsProbeItemsHooks.java';
  const original = read(hooks);
  const edited = original.replace('strip()', 'trim()');
  fs.writeFileSync(resolve(work, hooks), edited);
  const before = read(declaration);
  const rerun = cmsOk(...commands[1]);
  check(rerun.includes(`kept ${hooks}`) && read(hooks) === edited, 'a hand edit is never overwritten');
  check(read(declaration) === before, 'a re-run keeps the declaration add-field changed');
  fs.writeFileSync(resolve(work, hooks), original);
  const other = cms('entity', 'new', 'ms.probe', 'items', '--title', 'X');
  check(other.status === 0, 'a re-run with other titles keeps what is there');
  const dry = cms('entity', 'add-field', 'probe.items', 'note', '--type', 'textarea', '--dry-run');
  check(dry.status === 0 && dry.out.includes('[dry-run]') && read(declaration) === before, 'a dry run writes nothing');
}

/** The developer's next field: the declaration first, then `cms migration diff --write` gives its migration. */
function checkMigrationDiff() {
  cmsOk('entity', 'add-field', 'probe.items', 'note', '--type', 'textarea', '--max', '2000', '--label', 'Примечание');
  const dir = resolve(work, PATHS.migrations);
  const added = fs.readdirSync(dir).find((name) => name.endsWith('__ms_probe_items_note.sql'));
  check(added, 'add-field writes a migration');
  fs.rmSync(path.join(dir, added));
  const manifest = resolve(work, PATHS.manifest);
  fs.writeFileSync(manifest, fs.readFileSync(manifest, 'utf8').split(/\r?\n/).filter((l) => !l.includes(added)).join('\n'));
  cmsOk('migration', 'diff', '--write', 'ms_probe_items_note_column');
  const written = fs.readdirSync(dir).find((name) => name.endsWith('__ms_probe_items_note_column.sql'));
  check(written && /add column note text/.test(fs.readFileSync(path.join(dir, written), 'utf8')), 'diff writes the column');
}

function checkGenerated() {
  const javaDir = resolve(work, 'apps/server/src/main/java/com/smartup24/cms/instance/ms/probe');
  const service = fs.readdirSync(path.join(javaDir, 'service'));
  check(service.length === 2, `an entity is two server files (declaration and hooks), found ${service.join(', ')}`);
  for (const name of service) {
    const text = fs.readFileSync(path.join(javaDir, 'service', name), 'utf8');
    check(!/@RestController|@Repository|JdbcClient/.test(text), `${name}: no controller and no SQL`);
    check(!/[Ѐ-ӿ]/.test(text), `${name}: no Cyrillic in the generated Java`);
  }
  for (const name of fs.readdirSync(resolve(work, PATHS.migrations)).filter((n) => n.includes('ms_probe_items'))) {
    const comments = read(`${PATHS.migrations}/${name}`)
      .split(/\r?\n/)
      .filter((line) => line.trim().startsWith('--'));
    check(!comments.some((line) => /[Ѐ-ӿ]/.test(line)), `${name}: comments are in English`);
  }
  for (const language of ['ru', 'uz', 'en']) {
    const catalog = JSON.parse(read(`${PATHS.catalogs}/${language}.json`));
    for (const key of ['nav.probe_items', 'probe.items.col.name', 'probe.items.col.due_on', 'probe.items.stage.ready']) {
      check(catalog[key], `${language}.json has ${key}`);
    }
  }
  const manifest = JSON.parse(read(`${PATHS.moduleManifests}/probe.json`));
  check(
    manifest.code === 'probe' && Object.keys(manifest).join() === 'code,name,version,minPlatform,dependencies',
    'the module manifest has the ADR-0033 fields and the registry code',
  );
  check(read(PATHS.coverageFloors).includes('ms.probe,'), 'the module has a coverage floor');
  check(read(PATHS.moduleMap).includes('/api/v1/entities/probe.items'), 'the module map names the entity');
  const audit = spawnSync(process.execPath, ['scripts/localization-audit.mjs'], { cwd: resolve(work, 'apps/web'), encoding: 'utf8' });
  process.stdout.write(`${audit.stdout}${audit.stderr}`);
  check(audit.status === 0, 'the localization audit passes with the new keys');
}

function build() {
  const log = path.join(work, 'smoke-maven.log');
  const args = ['-B', '-ntp', '-pl', 'apps/server', '-am', 'spotless:check', 'test', 'checkstyle:check'];
  // The generated entity joins the exact list of declared entities of EntityActionPermissionContractTest.
  args.push(`-Dtest=${TESTS.join(',')}`, '-Dsurefire.failIfNoSpecifiedTests=false', '-Dopenapi.update=true');
  args.push('-Dcms.smoke.entities=probe.items');
  console.log(`Building the server with the generated module (${TESTS.length} test classes); log ${log}`);
  const status = runMaven(work, args, log);
  if (status !== 0) {
    process.stdout.write(fs.readFileSync(log, 'utf8').split(/\r?\n/).slice(-150).join('\n'));
    if (keep || process.env.CI) {
      fs.copyFileSync(log, path.join(process.env.RUNNER_TEMP ?? os.tmpdir(), 'cms-cli-smoke-maven.log'));
    }
  }
  check(status === 0, `the server with the generated module fails the build (exit ${status})`);
}

/**
 * A module outside the monorepo (ADR-0033, 13): generated standalone, built against the platform the copy installs.
 * The platform goes into a local repository of the smoke's own whose tail is the developer's (maven.repo.local.tail:
 * read, never written); outside CI nothing is downloaded (-o), so what is missing is named by Maven.
 */
function checkExternal() {
  const head = path.join(work, '.m2-smoke');
  const tail = process.env.CMS_SMOKE_M2_TAIL ?? path.join(os.homedir(), '.m2', 'repository');
  const repository = [`-Dmaven.repo.local=${head}`, `-Dmaven.repo.local.tail=${tail}`, ...(process.env.CI ? [] : ['-o'])];
  const installLog = path.join(work, 'smoke-install.log');
  const install = ['-B', '-ntp', '-pl', 'apps/server,libs/platform-testkit', '-am', 'install', '-DskipTests'];
  install.push('-Djacoco.skip=true', '-Dcheckstyle.skip=true', '-Dspotless.check.skip=true', '-Djapicmp.skip=true');
  console.log(`Installing the platform into ${head}; log ${installLog}`);
  const installed = runMaven(work, [...install, ...repository], installLog);
  if (installed !== 0) process.stdout.write(fs.readFileSync(installLog, 'utf8').split(/\r?\n/).slice(-60).join('\n'));
  check(installed === 0, `the platform does not install (exit ${installed})`);

  const dir = path.join(outside, 'stock').replace(/\\/g, '/');
  cmsOk('module', 'new', 'stock', '--external', '--dir', dir, '--package', 'com.acme.stock', '--group', 'com.acme.stock',
    '--title', 'Склад', '--title-en', 'Stock', '--title-uz', 'Ombor');
  const pom = fs.readFileSync(path.join(dir, 'pom.xml'), 'utf8');
  const platform = platformVersions(work);
  check(!pom.includes('<parent>'), 'a module outside the repository has no parent pom');
  check(pom.includes(`<platform-api.version>${platform.apiVersion}</platform-api.version>`), 'the pom names the API version');
  check(pom.includes(`<smartupcms.version>${platform.appVersion}</smartupcms.version>`), 'the pom names the platform');

  const log = path.join(work, 'smoke-external.log');
  console.log(`Building the external module standalone in ${dir}; log ${log}`);
  const status = runMaven(work, ['-B', '-ntp', '-f', path.join(dir, 'pom.xml'), 'verify', ...repository], log);
  const text = fs.readFileSync(log, 'utf8');
  if (status !== 0) process.stdout.write(text.split(/\r?\n/).slice(-80).join('\n'));
  check(status === 0, `the external module does not build standalone (exit ${status})`);
  for (const test of ['StockItemsContractTest', 'StockModuleBoundaryTest']) {
    check(new RegExp(`Tests run: [1-9]\\d*, Failures: 0, Errors: 0, Skipped: 0.* in com\\.acme\\.stock\\.${test}`).test(text),
      `${test} runs and passes`);
  }
}

let failed = false;
try {
  console.log(`Copying the repository to ${work}`);
  copyRepository();
  generate();
  checkRerunAndEdits();
  checkGenerated();
  if (!skipBuild) {
    checkMigrationDiff();
    build();
    checkExternal();
  }
  const minutes = ((Date.now() - started) / 60000).toFixed(1);
  console.log(
    skipBuild
      ? `cms-cli smoke passed in ${minutes} min without the build (--skip-build).`
      : `cms-cli smoke passed in ${minutes} min: the generated module builds and passes ${TESTS.length} test classes;` +
          ' the external module builds standalone and passes its contract kit.',
  );
} catch (error) {
  failed = true;
  console.error(error.message);
} finally {
  if (keep) console.log(`Kept ${work} and ${outside}`);
  else {
    fs.rmSync(work, { recursive: true, force: true, maxRetries: 3 });
    fs.rmSync(outside, { recursive: true, force: true, maxRetries: 3 });
  }
}
process.exitCode = failed ? 1 : 0;
