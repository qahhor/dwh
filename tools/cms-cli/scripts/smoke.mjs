// The CLI smoke (plan 10/10, item 6.1): in a temporary copy of the repository it creates a module, an entity with
// hooks and three fields, checks that a re-run changes nothing and that a hand edit is never overwritten, lets
// `cms migration diff` write a migration the declaration needs, and builds the server with the result: Spotless,
// Error Prone, Checkstyle, the architecture and migration tests, the entity contract kit and the schema comparison.
// CI runs it on Linux and Windows (.github/workflows/nightly.yml); scripts/dev/test-cms-cli.ps1 runs it locally.
//
//   node tools/cms-cli/scripts/smoke.mjs [--keep] [--skip-build]
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PATHS, resolve } from '../lib/layout.mjs';
import { runMaven } from '../lib/toolchain.mjs';

const keep = process.argv.includes('--keep');
const skipBuild = process.argv.includes('--skip-build');
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const work = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP ?? os.tmpdir(), 'cms-cli-smoke-'));
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
  args.push(`-Dtest=${TESTS.join(',')}`, '-Dsurefire.failIfNoSpecifiedTests=false', '-Dopenapi.update=true');
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
  }
  const minutes = ((Date.now() - started) / 60000).toFixed(1);
  console.log(
    skipBuild
      ? `cms-cli smoke passed in ${minutes} min without the build (--skip-build).`
      : `cms-cli smoke passed in ${minutes} min: the generated module builds and passes ${TESTS.length} test classes.`,
  );
} catch (error) {
  failed = true;
  console.error(error.message);
} finally {
  if (keep) console.log(`Kept ${work}`);
  else fs.rmSync(work, { recursive: true, force: true, maxRetries: 3 });
}
process.exitCode = failed ? 1 : 0;
