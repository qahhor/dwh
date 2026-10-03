// Flyway migrations: the next free number, the pin in the manifest (MigrationManifestTest) and what the existing
// files already create (ADR-0020; plan 10/10, item 0.5).
import crypto from 'node:crypto';
import fs from 'node:fs';
import { CliError, PATHS, resolve } from './layout.mjs';
import { normalize } from './plan.mjs';

const VERSION = /^V0*(\d+)__([a-z0-9_]+)\.sql$/;

/** Every migration file name with its text, `\n` line endings. */
export function migrationFiles(root) {
  const dir = resolve(root, PATHS.migrations);
  return fs
    .readdirSync(dir)
    .filter((name) => VERSION.test(name))
    .map((name) => ({ name, text: normalize(fs.readFileSync(`${dir}/${name}`, 'utf8')) }));
}

/** The highest version on disk or in the manifest: a released number is never taken again. */
export function highestVersion(root, plan) {
  let highest = 0;
  for (const { name } of migrationFiles(root)) highest = Math.max(highest, Number(VERSION.exec(name)[1]));
  const manifest = plan.current(PATHS.manifest) ?? '';
  for (const match of manifest.matchAll(/db\/migration\/V0*(\d+)__/g)) highest = Math.max(highest, Number(match[1]));
  for (const step of plan.steps) {
    const match = step.kind === 'write' && /\/V0*(\d+)__[a-z0-9_]+\.sql$/.exec(step.path);
    if (match) highest = Math.max(highest, Number(match[1]));
  }
  return highest;
}

/** `V196`, three digits at least (MigrationFileRulesTest). */
export function versionName(number) {
  return `V${String(number).padStart(3, '0')}`;
}

/** The SHA-256 MigrationManifestTest pins: of the text with `\n` line endings. */
export function manifestHash(text) {
  return crypto.createHash('sha256').update(normalize(text), 'utf8').digest('hex');
}

/**
 * Plans a new migration under the next free number (or `version`), pinned in the manifest. Returns its version.
 */
export function planMigration(plan, name, text, version) {
  if (!/^[a-z0-9_]+$/.test(name)) throw new CliError(`Migration name: lower-case letters, digits and _: ${name}`);
  const highest = highestVersion(plan.root, plan);
  let number = highest + 1;
  if (version) {
    const match = /^V?0*(\d+)$/.exec(version);
    if (!match) throw new CliError(`Migration version: V<number>: ${version}`);
    number = Number(match[1]);
    if (number <= highest) {
      throw new CliError(`${versionName(number)} is not above the highest migration ${versionName(highest)}`);
    }
  }
  const file = `${versionName(number)}__${name}.sql`;
  plan.create(`${PATHS.migrations}/${file}`, text, 'migration');
  plan.patch(
    PATHS.manifest,
    (manifest) => {
      const line = `${manifestHash(text)}  db/migration/${file}`;
      if (manifest.includes(`db/migration/${file}`)) return manifest;
      return `${manifest.endsWith('\n') || manifest === '' ? manifest : `${manifest}\n`}${line}\n`;
    },
    `pin ${file}`,
  );
  return versionName(number);
}

/** The statements of the migrations, comments dropped, lower case, one string per statement. */
function statements(root) {
  return migrationFiles(root).flatMap(({ text }) =>
    text
      .replace(/--[^\n]*/g, '')
      .toLowerCase()
      .split(';')
      .map((statement) => statement.replace(/\s+/g, ' ').trim()),
  );
}

/** Whether a migration creates the table. */
export function createsTable(root, table) {
  const pattern = new RegExp(`^create table (if not exists )?${table} \\(`);
  return statements(root).some((statement) => pattern.test(statement));
}

/** Whether a migration gives the table the column, in its create statement or an added column. */
export function hasColumn(root, table, column) {
  const create = new RegExp(`^create table (if not exists )?${table} \\(`);
  const alter = new RegExp(`^alter table (if exists )?(only )?${table} `);
  const added = new RegExp(`add column (if not exists )?${column} `);
  const declared = new RegExp(`[(,] ?${column} [a-z]`);
  return statements(root).some(
    (statement) => (create.test(statement) && declared.test(statement)) || (alter.test(statement) && added.test(statement)),
  );
}

/** Whether a migration seeds the right of the form. */
export function seedsForm(root, form) {
  return statements(root).some(
    (statement) => statement.startsWith('insert into md_forms') && statement.includes(`('${form}',`),
  );
}
