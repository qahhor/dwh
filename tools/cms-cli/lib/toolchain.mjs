// The commands that need the Java toolchain: cms migration diff (through EntitySchemaDiffTest, which runs
// EntitySchemaCheck) and cms doctor.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { CliError, PATHS, SCHEMA_DIFF_TEST, resolve } from './layout.mjs';
import { planMigration } from './migrations.mjs';
import { diffMigration } from './templates.mjs';

const WINDOWS = process.platform === 'win32';

/** Runs the Maven wrapper of the repository; output goes to the log file. */
export function runMaven(root, args, logFile) {
  const out = fs.openSync(logFile, 'w');
  try {
    const result = WINDOWS
      ? spawnSync('cmd.exe', ['/d', '/s', '/c', `"${[resolve(root, 'mvnw.cmd'), ...args].map(quoteWindows).join(' ')}"`], {
          cwd: root,
          stdio: ['ignore', out, out],
          windowsVerbatimArguments: true,
        })
      : spawnSync('sh', [resolve(root, PATHS.mavenWrapper), ...args], { cwd: root, stdio: ['ignore', out, out] });
    if (result.error) throw new CliError(`Maven did not start: ${result.error.message}`);
    return result.status;
  } finally {
    fs.closeSync(out);
  }
}

/** An argument of a cmd.exe line: quoted when it has a space or a character cmd would read. */
function quoteWindows(arg) {
  return /[\s"&|<>^()]/.test(arg) ? `"${arg.replace(/"/g, '""')}"` : arg;
}

function tail(file, lines = 40) {
  return fs.readFileSync(file, 'utf8').split(/\r?\n/).slice(-lines).join('\n');
}

/**
 * `cms migration diff`: the Java test EntitySchemaDiffTest starts the application's declarations on an embedded
 * PostgreSQL with every migration applied and writes what the schema lacks as DDL. Printed, or written as a new
 * migration with `--write <name>`.
 */
export function migrationDiff(plan, values, log = console.log) {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'cms-diff-'));
  const out = path.join(work, 'diff.sql');
  const logFile = path.join(work, 'maven.log');
  const args = [
    '-B',
    '-ntp',
    '-pl',
    'apps/server',
    ...(values['no-libs'] ? [] : ['-am']),
    'test',
    `-Dtest=${SCHEMA_DIFF_TEST}`,
    '-Dsurefire.failIfNoSpecifiedTests=false',
    '-Djacoco.skip=true',
    `-Dcms.schema.diff.out=${out}`,
  ];
  log(`Comparing the entity declarations with the migrated schema (${SCHEMA_DIFF_TEST}, embedded PostgreSQL)...`);
  const status = runMaven(plan.root, args, logFile);
  if (status !== 0 || !fs.existsSync(out)) {
    throw new CliError(`The comparison failed (Maven exit ${status}); the log is ${logFile}:\n${tail(logFile)}`);
  }
  const ddl = fs.readFileSync(out, 'utf8').trim();
  const problemsFile = `${out}.problems`;
  const problems = fs.existsSync(problemsFile) ? fs.readFileSync(problemsFile, 'utf8').trim() : '';
  fs.rmSync(work, { recursive: true, force: true });
  if (problems) {
    // EntitySchemaCheck, the check the start runs (ADR-0033, 7): what the DDL below does not fix is fixed by hand.
    log('Differences EntitySchemaCheck reports (the start refuses on any of them):');
    log(problems.split(/\r?\n/).map((line) => `  - ${line}`).join('\n'));
    log('');
  }
  if (!ddl) {
    log(problems
      ? 'No missing table or column to write: fix the differences above by hand.'
      : 'No difference: the schema matches every declaration.');
    return [];
  }
  const statements = ddl.split(/\n\n+/);
  if (values.write) {
    const version = planMigration(plan, values.write, diffMigration(statements), values.version);
    plan.note(`review ${version}__${values.write}.sql: a required field's column is added nullable`);
  } else {
    log(statements.join('\n\n'));
    log('');
    log('Write it as a migration: cms migration diff --write <name>');
  }
  return statements;
}

function version(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', shell: false });
  if (result.error || result.status !== 0) return null;
  return `${result.stdout}${result.stderr}`.trim();
}

/** `cms doctor`: what the workstation has of what the repository needs. */
export function doctor(root, log = console.log) {
  const checks = [];
  const add = (level, what, detail) => checks.push({ level, what, detail });

  const node = Number(process.versions.node.split('.')[0]);
  const wanted = fs.existsSync(path.join(root, '.node-version'))
    ? fs.readFileSync(path.join(root, '.node-version'), 'utf8').trim()
    : '22';
  add(node >= 22 ? 'ok' : 'fail', 'Node.js', `${process.versions.node} (the repository pins ${wanted})`);

  const javaHome = process.env.JAVA_HOME;
  const java = javaHome ? path.join(javaHome, 'bin', WINDOWS ? 'java.exe' : 'java') : 'java';
  const javaVersion = version(java, ['-version']);
  const major = javaVersion && /version "(\d+)/.exec(javaVersion);
  if (!javaVersion) add('fail', 'Java', 'not found: install JDK 25 and set JAVA_HOME');
  else add(major && Number(major[1]) >= 25 ? 'ok' : 'fail', 'Java', `${javaVersion.split('\n')[0]}${javaHome ? '' : ' (JAVA_HOME unset)'}`);

  const wrapper = resolve(root, WINDOWS ? `${PATHS.mavenWrapper}.cmd` : PATHS.mavenWrapper);
  add(fs.existsSync(wrapper) ? 'ok' : 'fail', 'Maven wrapper', path.relative(root, wrapper));
  const git = version('git', ['--version']);
  add(git ? 'ok' : 'warn', 'git', git ?? 'not found');
  const modules = resolve(root, `${PATHS.webRoot}/node_modules`);
  add(fs.existsSync(modules) ? 'ok' : 'warn', 'web packages', fs.existsSync(modules) ? 'apps/web/node_modules' : 'run npm ci in apps/web for the web checks');
  const docker = version('docker', ['--version']);
  add(docker ? 'ok' : 'warn', 'Docker', docker ?? 'not found: the Compose stack and E2E need it; the server tests do not');
  for (const file of [PATHS.manifest, PATHS.permissionAreas, PATHS.moduleBoundaries, PATHS.moduleMap, PATHS.coverageFloors]) {
    add(fs.existsSync(resolve(root, file)) ? 'ok' : 'fail', 'repository', file);
  }
  for (const check of checks) log(`${check.level.toUpperCase().padEnd(4)}  ${check.what.padEnd(14)} ${check.detail}`);
  const failed = checks.filter((check) => check.level === 'fail').length;
  log(failed ? `${failed} check(s) failed.` : 'The environment is ready.');
  return failed === 0;
}
