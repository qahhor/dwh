// The static simple-form delivery contract (ADR-0037); no code or schema mutation is accepted.
import crypto from 'node:crypto';
import fs from 'node:fs';
import { CliError } from './layout.mjs';
import { strictJson } from './strict-json.mjs';

const TYPES = new Set(['text', 'textarea', 'number', 'date', 'datetime', 'time', 'bool', 'email', 'phone', 'url']);
const identifiers = JSON.parse(fs.readFileSync(
  new URL('../../../apps/server/src/main/resources/studio/simple-form-reserved-identifiers.json', import.meta.url), 'utf8'));
const MODULE_RESERVED = new Set([...identifiers.javaModules, ...identifiers.portableModules]);
const RESERVED = new Set([...identifiers.systemFields, ...identifiers.sqlColumns]);
const fail = (where, reason) => { throw new CliError(`Invalid ${where}: ${reason}`); };
function shape(value, keys, where) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(where, 'expected object');
  for (const key of Object.keys(value)) if (!keys.includes(key)) fail(where, `unknown property ${key}`);
}
function localized(value, where) {
  shape(value, ['ru', 'uz', 'en'], where);
  for (const lang of ['ru', 'uz', 'en']) {
    const text = value[lang];
    if (typeof text !== 'string' || text.length < 1 || [...text].length > 80 || text !== text.trim()
      || /[\p{Cc}\p{Cf}\u2028\u2029]/u.test(text) || /[\uD800-\uDFFF]/u.test(text)) {
      fail(`${where}.${lang}`, 'must be trimmed text of 1..80 characters without controls');
    }
  }
}
function integer(value, min, max, where) {
  if (!Number.isSafeInteger(value) || value < min || value > max) fail(where, `must be an integer ${min}..${max}`);
}
function field(value, seen) {
  shape(value, ['id', 'type', 'labels', 'required', 'listed', 'maxLength', 'scale'], 'field');
  if (typeof value.id !== 'string' || !/^[a-z][a-z0-9]{0,19}$/.test(value.id)) fail('field.id', 'unsupported identifier');
  if (RESERVED.has(value.id) || identifiers.fieldFragments.some((fragment) => value.id.includes(fragment))) {
    fail('field.id', 'reserved identifier');
  }
  if (seen.has(value.id)) fail('field.id', 'duplicate identifier');
  seen.add(value.id);
  if (!TYPES.has(value.type)) fail('field.type', 'unsupported type');
  localized(value.labels, `field.${value.id}.labels`);
  for (const key of ['required', 'listed']) if (typeof value[key] !== 'boolean') fail(`field.${key}`, 'expected boolean');
  if (['text', 'textarea'].includes(value.type)) integer(value.maxLength, 1, 4000, 'field.maxLength');
  else if (Object.hasOwn(value, 'maxLength')) fail('field.maxLength', 'unsupported property for this type');
  if (value.type === 'number') integer(value.scale, 0, 6, 'field.scale');
  else if (Object.hasOwn(value, 'scale')) fail('field.scale', 'unsupported property for this type');
}
export function validateForm(value) {
  shape(value, ['formatVersion', 'kind', 'module', 'entity', 'fields'], 'declaration');
  if (value.formatVersion !== 1 || value.kind !== 'simple-form') fail('declaration', 'unsupported format');
  shape(value.module, ['code', 'title'], 'module');
  shape(value.entity, ['name', 'title'], 'entity');
  if (typeof value.module.code !== 'string' || !/^[a-z][a-z0-9]{1,11}$/.test(value.module.code)) fail('module.code', 'unsupported identifier');
  if (MODULE_RESERVED.has(value.module.code)) fail('module.code', 'reserved identifier');
  if (typeof value.entity.name !== 'string' || !/^[a-z][a-z0-9_]{0,19}$/.test(value.entity.name)) fail('entity.name', 'unsupported identifier');
  localized(value.module.title, 'module.title');
  localized(value.entity.title, 'entity.title');
  if (!Array.isArray(value.fields) || value.fields.length > 20) fail('fields', 'expected array with limit 20');
  const seen = new Set();
  value.fields.forEach((item) => field(item, seen));
  return value;
}
export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}
export function checksum(value) {
  return crypto.createHash('sha256').update(canonical(value), 'utf8').digest('hex');
}
export function readFormPackage(file) {
  let bytes;
  let handle;
  try {
    handle = fs.openSync(file, 'r');
    const buffer = Buffer.alloc(65537);
    let length = 0;
    while (length < buffer.length) {
      const count = fs.readSync(handle, buffer, length, buffer.length - length, null);
      if (count === 0) break;
      length += count;
    }
    if (length > 65536) fail('package', 'byte limit 65536');
    bytes = buffer.subarray(0, length);
  } catch (error) {
    if (error instanceof CliError) throw error;
    throw new CliError(`Cannot read form package: ${error.message}`);
  } finally {
    if (handle !== undefined) fs.closeSync(handle);
  }
  const value = strictJson(bytes, { maxBytes: 65536, maxDepth: 10, integersOnly: true });
  shape(value, ['formatVersion', 'kind', 'declaration', 'checksum'], 'package');
  if (value.formatVersion !== 1 || value.kind !== 'simple-form-package') fail('package', 'unsupported format');
  validateForm(value.declaration);
  strictJson(Buffer.from(canonical(value.declaration), 'utf8'));
  if (value.checksum !== checksum(value.declaration)) fail('package', 'checksum mismatch');
  return value;
}
