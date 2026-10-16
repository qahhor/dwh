// Create-only delivery through the existing CLI templates and Flyway (ADR-0037).
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { moduleNew, planEntityNew } from './commands.mjs';
import { readFormPackage } from './form-contract.mjs';
import { CliError, PATHS, resolve } from './layout.mjs';
import { createsTable, seedsForm } from './migrations.mjs';
import { entityNames, labelKey, moduleNames } from './names.mjs';
import { loadModule, findDeclaration } from './repo.mjs';
import { strictJson } from './strict-json.mjs';
import { safePath } from './safe-path.mjs';
import { FIELD_TYPES, starterFields } from './templates.mjs';

const digest = (text) => crypto.createHash('sha256').update(text, 'utf8').digest('hex');
const titleArgs = (title) => ({ title: title.ru, 'title-uz': title.uz, 'title-en': title.en });

function existingReceipt(plan, file, value) {
  const source = plan.current(file);
  if (source === null) return false;
  const receipt = strictJson(Buffer.from(source), { maxBytes: 65536, maxDepth: 8 });
  if (receipt.formatVersion !== 1 || receipt.kind !== 'simple-form-receipt'
    || receipt.entity !== `${value.declaration.module.code}.${value.declaration.entity.name}`
    || receipt.checksum !== value.checksum || !Array.isArray(receipt.files) || !receipt.files.length) {
    throw new CliError('Create-only schema delivery: existing receipt differs. Required/type/delete and other changes need a reviewed migration.');
  }
  for (const item of receipt.files) {
    if (typeof item.path !== 'string' || path.isAbsolute(item.path) || item.path.split(/[\\/]/).includes('..')
      || !/^[a-f0-9]{64}$/.test(item.sha256 ?? '')) throw new CliError('Invalid delivery receipt');
    const target = safePath(plan.root, item.path);
    if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
      throw new CliError(`Incomplete delivery: generated file missing: ${item.path}; restore the reviewed source, no automatic repair.`);
    }
  }
  plan.note('The same package was already delivered. Manual edits are kept; no source or migration was changed.');
  return true;
}

function moduleFor(plan, data) {
  const names = moduleNames(data.code);
  const manifest = plan.current(names.manifest);
  if (manifest !== null) {
    const parsed = strictJson(Buffer.from(manifest));
    if (parsed.code !== data.code || typeof parsed.version !== 'string' || typeof parsed.minPlatform !== 'string'
      || !Array.isArray(parsed.dependencies) || !parsed.dependencies.some((dependency) => dependency.code === 'iam')
      || Object.keys(parsed).some((key) => !['code', 'version', 'minPlatform', 'dependencies'].includes(key))) {
      throw new CliError('Existing module manifest is not compatible with static form delivery');
    }
    return loadModule(plan.root, data.code);
  }
  if (fs.existsSync(resolve(plan.root, names.javaDir))) throw new CliError('Existing module has no compatible manifest');
  return { ...moduleNew(plan, data.code, { ...titleArgs(data.title), 'no-sync': true, safeDocumentation: true }), icon: 'box' };
}

function fieldsOf(entity, fields) {
  return [...starterFields(entity), ...fields.map((field) => ({
    key: field.id, type: field.type, column: field.id, labelKey: labelKey(entity, field.id),
    label: field.labels, required: field.required, formOnly: !field.listed,
    list: field.listed ? (FIELD_TYPES[field.type].list ?? 'sortable()') : null,
    ...(field.maxLength !== undefined ? { max: field.maxLength, ...(field.required ? { min: 1 } : {}) } : {}),
    ...(field.scale !== undefined ? { scale: field.scale } : {}),
  }))];
}

function planRussian(plan) {
  const relative = 'apps/web/src/app/core/i18n/packaged-russian.ts';
  const catalog = JSON.parse(plan.current(`${PATHS.catalogs}/ru.json`));
  const text = '// Generated from the canonical packaged Russian catalog.\n'
    + '// The server remains authoritative after application initialization.\n'
    + `export const PACKAGED_RUSSIAN: Readonly<Record<string, string>> = Object.freeze(${JSON.stringify(catalog, null, 2)});\n`;
  if (plan.current(relative) === null) plan.create(relative, text, 'Russian fallback');
  else plan.patch(relative, () => text, 'Russian fallback');
}

export function formApply(plan, file, values) {
  if (!file) throw new CliError('Usage: cms form apply <package.json> [--dry-run] [--no-sync]');
  const value = readFormPackage(file);
  const declaration = value.declaration;
  const entity = entityNames(moduleNames(declaration.module.code), declaration.entity.name);
  const receiptPath = `docs/declarations/${entity.code}.json`;
  if (existingReceipt(plan, receiptPath, value)) return;
  let declared = false;
  try { findDeclaration(plan.root, entity.code); declared = true; }
  catch (error) { if (!(error instanceof CliError) || !error.message.startsWith('No entity declaration')) throw error; }
  if (declared || plan.current(entity.entityFile) !== null || createsTable(plan.root, entity.table) || seedsForm(plan.root, entity.form)) {
    throw new CliError(`Create-only schema delivery: existing entity/table ${entity.code} has no matching receipt. Required/type/delete changes need a reviewed migration.`);
  }
  const module = moduleFor(plan, declaration.module);
  if (module.tablePrefix !== declaration.module.code || module.area !== declaration.module.code) {
    throw new CliError('Existing module table prefix is incompatible with the form package');
  }
  planEntityNew(plan, module, declaration.entity.name, {
    ...titleArgs(declaration.entity.title), fields: fieldsOf(entity, declaration.fields),
    administratorsOnly: true, safeDocumentation: true, 'no-sync': true,
  });
  for (const step of plan.pending.values()) {
    if (!step.path.endsWith('.sql')) continue;
    for (const match of step.text.matchAll(/(?:constraint|create(?: unique)? index|create table) ([a-z0-9_]+)/g)) {
      if (match[1].length > 63) throw new CliError(`Invalid generated PostgreSQL identifier: ${match[1]}`);
    }
  }
  if (!values['no-sync']) planRussian(plan);
  const files = [...plan.pending.values()].map((step) => ({ path: step.path, sha256: digest(step.text) }));
  plan.create(receiptPath, JSON.stringify({
    formatVersion: 1, kind: 'simple-form-receipt', entity: entity.code, checksum: value.checksum, files,
  }, null, 2) + '\n', 'delivery receipt');
  plan.note('Preflight is create-only: existing forms and their stored data/dependencies are never altered.');
  plan.note('Review the source, run CI and the schema contract, then deploy through Flyway; this command does not publish.');
}
