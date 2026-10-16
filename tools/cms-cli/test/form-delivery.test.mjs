import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { main } from '../lib/main.mjs';
import { PATHS } from '../lib/layout.mjs';
import { Plan } from '../lib/plan.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const labels = { ru: 'Название', uz: 'Nomi', en: 'Name' };
const declaration = () => ({
  formatVersion: 1, kind: 'simple-form', module: { code: 'studioqa', title: labels },
  entity: { name: 'records', title: labels },
  fields: [
    { id: 'note', type: 'text', labels, required: true, listed: true, maxLength: 100 },
    { id: 'amount', type: 'number', labels, required: false, listed: true, scale: 2 },
    { id: 'day', type: 'date', labels, required: false, listed: true },
  ],
});
const sort = (v) => Array.isArray(v) ? v.map(sort) : v && typeof v === 'object'
  ? Object.fromEntries(Object.keys(v).sort().map((key) => [key, sort(v[key])])) : v;
const hash = (v) => crypto.createHash('sha256').update(JSON.stringify(sort(v))).digest('hex');
const pack = (d = declaration()) => ({ formatVersion: 1, kind: 'simple-form-package', declaration: d, checksum: hash(d) });
const generated = `${PATHS.serverJava}/studioqa/service/StudioqaRecordsEntity.java`;
const receipt = 'docs/declarations/studioqa.records.json';
let root;
let input;
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8').replace(/\r\n/g, '\n');
const write = (data) => fs.writeFileSync(input, typeof data === 'string' || Buffer.isBuffer(data) ? data : JSON.stringify(data));
const run = (...args) => main(['form', 'apply', input, '--root', root, '--no-sync', ...args], () => {});
function snapshot() {
  const result = {};
  const visit = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const file = path.join(dir, entry.name);
      if (entry.isDirectory()) visit(file);
      else result[path.relative(root, file)] = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    }
  };
  visit(root);
  return result;
}
beforeEach(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'cms-form-test-'));
  for (const file of [PATHS.serverPom, PATHS.manifest, PATHS.permissionAreas, PATHS.moduleBoundaries,
    PATHS.moduleMap, PATHS.coverageFloors, PATHS.schemaOrderTest,
    ...['ru', 'uz', 'en'].map((lang) => `${PATHS.catalogs}/${lang}.json`),
    `${PATHS.migrations}/V181__example_orders.sql`]) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.copyFileSync(path.join(repo, file), path.join(root, file));
  }
  input = path.join(root, 'package.json');
  write(pack());
});
afterEach(() => fs.rmSync(root, { recursive: true, force: true }));

test('form delivery creates one entity through existing templates with all columns in its initial table', () => {
  assert.equal(run(), 0);
  const java = read(generated);
  assert.match(java, /Entity\.define\(CODE, CODE\)/);
  assert.match(java, /text\("note"/);
  assert.match(java, /\.length\(1, 100\)/);
  assert.match(java, /number\("amount"/);
  assert.match(java, /\.section\([\s\S]*"name", "code", "note", "amount", "day"/);
  const migrations = fs.readdirSync(path.join(root, PATHS.migrations)).filter((name) => name.includes('studioqa_records'));
  assert.equal(migrations.length, 2);
  const table = read(`${PATHS.migrations}/${migrations.find((name) => name.endsWith('_table.sql'))}`);
  assert.match(table, /note text not null/);
  assert.match(table, /amount numeric\(19, 2\)/);
  assert.match(table, /day date/);
  const rights = read(`${PATHS.migrations}/${migrations.find((name) => name.endsWith('_rights.sql'))}`);
  assert.match(rights, /chief_admin/);
  assert.doesNotMatch(rights, /'manager'|'user'|'auditor'/);
  const recorded = JSON.parse(read(receipt));
  assert.equal(recorded.checksum, pack().checksum);
  assert.ok(recorded.files.some((file) => file.path === generated));
  assert.ok(recorded.files.every((file) => /^[a-f0-9]{64}$/.test(file.sha256)));
});

test('dry-run includes the complete plan and writes no file', () => {
  const before = snapshot();
  assert.equal(run('--dry-run'), 0);
  assert.deepEqual(snapshot(), before);
});

test('an exact replay keeps manual edits and creates no migration', () => {
  assert.equal(run(), 0);
  fs.appendFileSync(path.join(root, generated), '\n// Hand edit.\n');
  const before = snapshot();
  assert.equal(run(), 0);
  assert.deepEqual(snapshot(), before);
});

test('a replay with a missing generated file stops without repairing or overwriting', () => {
  assert.equal(run(), 0);
  fs.unlinkSync(path.join(root, generated));
  const before = snapshot();
  assert.throws(() => run(), /missing|incomplete/i);
  assert.deepEqual(snapshot(), before);
});

for (const [change, mutate] of [
  ['required', (d) => { d.fields[1].required = true; }],
  ['type', (d) => { d.fields[2].type = 'datetime'; }],
  ['delete', (d) => { d.fields.pop(); }],
  ['hide', (d) => { d.fields[0].listed = false; }],
]) test(`create-only preflight rejects an existing ${change} change before touching files`, () => {
  assert.equal(run(), 0);
  const d = declaration(); mutate(d); write(pack(d));
  const before = snapshot();
  assert.throws(() => run(), /create.only|schema|existing|receipt/i);
  assert.deepEqual(snapshot(), before);
});

test('existing schema without a receipt cannot be adopted', () => {
  assert.equal(run(), 0);
  fs.unlinkSync(path.join(root, receipt));
  const before = snapshot();
  assert.throws(() => run(), /existing|receipt/i);
  assert.deepEqual(snapshot(), before);
});

test('tampering cannot be accepted by the delivery command', () => {
  const p = pack(); p.declaration.fields[0].required = false; write(p);
  const before = snapshot();
  assert.throws(() => run(), /checksum/i);
  assert.deepEqual(snapshot(), before);
});

for (const [name, mutate] of [
  ['unknown property', (d) => { d.sql = 'drop table'; }],
  ['unknown nested property', (d) => { d.fields[0].hidden = true; }],
  ['wrong type', (d) => { d.fields[0].required = 'true'; }],
  ['missing translation', (d) => { d.entity.title = { ru: 'Имя', en: 'Name' }; }],
  ['untrimmed text', (d) => { d.entity.title = { ...labels, ru: ' Имя' }; }],
  ['line separator', (d) => { d.entity.title = { ...labels, en: 'A\u2028B' }; }],
  ['control', (d) => { d.entity.title = { ...labels, en: 'A\nB' }; }],
  ['surrogate', (d) => { d.entity.title = { ...labels, en: '\ud800' }; }],
  ['reserved ID', (d) => { d.fields[0].id = 'createdat'; }],
  ['Java keyword module', (d) => { d.module.code = 'class'; }],
  ['Java primitive module', (d) => { d.module.code = 'int'; }],
  ['Java boolean literal module', (d) => { d.module.code = 'true'; }],
  ['Java null literal module', (d) => { d.module.code = 'null'; }],
  ['Windows device module con', (d) => { d.module.code = 'con'; }],
  ['Windows device module prn', (d) => { d.module.code = 'prn'; }],
  ['Windows device module aux', (d) => { d.module.code = 'aux'; }],
  ['Windows device module nul', (d) => { d.module.code = 'nul'; }],
  ['Windows device module com1', (d) => { d.module.code = 'com1'; }],
  ['Windows device module com9', (d) => { d.module.code = 'com9'; }],
  ['Windows device module lpt1', (d) => { d.module.code = 'lpt1'; }],
  ['Windows device module lpt9', (d) => { d.module.code = 'lpt9'; }],
  ['SQL reserved column', (d) => { d.fields[0].id = 'select'; }],
  ['SQL user column', (d) => { d.fields[0].id = 'user'; }],
  ['SQL type/function column', (d) => { d.fields[0].id = 'authorization'; }],
  ['PostgreSQL transaction column', (d) => { d.fields[0].id = 'xmin'; }],
  ['PostgreSQL delete transaction column', (d) => { d.fields[0].id = 'xmax'; }],
  ['PostgreSQL create command column', (d) => { d.fields[0].id = 'cmin'; }],
  ['PostgreSQL delete command column', (d) => { d.fields[0].id = 'cmax'; }],
  ['PostgreSQL row location column', (d) => { d.fields[0].id = 'ctid'; }],
  ['PostgreSQL table ID column', (d) => { d.fields[0].id = 'tableoid'; }],
  ['renderer inherited constructor ID', (d) => { d.fields[0].id = 'constructor'; }],
  ['underscore ID', (d) => { d.fields[0].id = 'first_name'; }],
  ['duplicate ID', (d) => { d.fields[1].id = 'note'; }],
  ['unsupported type', (d) => { d.fields[2].type = 'ref'; }],
  ['missing length', (d) => { delete d.fields[0].maxLength; }],
  ['wrong length', (d) => { d.fields[0].maxLength = 0; }],
  ['excess length', (d) => { d.fields[0].maxLength = 4001; }],
  ['wrong scale', (d) => { d.fields[1].scale = 7; }],
  ['irrelevant property', (d) => { d.fields[2].scale = 2; }],
  ['too many fields', (d) => { d.fields = Array.from({ length: 21 }, (_, i) => ({ ...d.fields[2], id: `day${i}` })); }],
  ['path traversal', (d) => { d.module.code = '../escape'; }],
]) test(`the CLI independently rejects ${name}`, () => {
  const d = declaration(); mutate(d); write(pack(d));
  const before = snapshot();
  assert.throws(() => run(), /invalid|unknown|must|expected|duplicate|reserved|limit|unsupported/i);
  assert.deepEqual(snapshot(), before);
});

for (const [name, payload] of [
  ['duplicate keys', () => JSON.stringify(pack()).replace('"formatVersion":1', '"formatVersion":1,"formatVersion":1')],
  ['trailing JSON', () => JSON.stringify(pack()) + '{}'],
  ['invalid UTF8', () => Buffer.from([0xc3, 0x28])],
  ['excess depth', () => '{"a":'.repeat(9) + '0' + '}'.repeat(9)],
  ['excess bytes', () => ' '.repeat(32769)],
]) test(`the input reader rejects ${name} before writes`, () => {
  write(payload()); const before = snapshot();
  assert.throws(() => run(), /invalid|duplicate|trailing|depth|large|limit|UTF/i);
  assert.deepEqual(snapshot(), before);
});

test('Plan atomic mode rejects concurrent changes instead of overwriting them', () => {
  const plan = new Plan(root);
  plan.patch(PATHS.coverageFloors, (s) => s + '\nqa,80,50\n', 'test patch');
  fs.appendFileSync(path.join(root, PATHS.coverageFloors), '\nmanual,80,50\n');
  assert.throws(() => plan.apply({ atomic: true, log: () => {} }), /changed|concurrent/i);
  assert.match(read(PATHS.coverageFloors), /manual,80,50/);
  assert.doesNotMatch(read(PATHS.coverageFloors), /qa,80,50/);
});

test('Plan atomic mode rolls back earlier writes if a later I/O write fails', () => {
  const plan = new Plan(root);
  plan.patch(PATHS.coverageFloors, (s) => s + '\nqa,80,50\n', 'test patch');
  fs.writeFileSync(path.join(root, 'blocking-parent'), 'file instead of directory');
  plan.create('blocking-parent/new.txt', 'new', 'fault');
  const before = snapshot();
  assert.throws(() => plan.apply({ atomic: true, log: () => {} }));
  assert.deepEqual(snapshot(), before);
});


test('the shared server fixture is accepted with its independently specified canonical checksum', () => {
  const file = path.join(repo, 'apps/server/src/test/resources/studio/simple-form.json');
  const d = JSON.parse(fs.readFileSync(file, 'utf8'));
  const expected = fs.readFileSync(file.replace(/\.json$/, '.sha256'), 'utf8').trim();
  write({ formatVersion: 1, kind: 'simple-form-package', declaration: d, checksum: expected });
  assert.equal(run(), 0);
  const java = read(`${PATHS.serverJava}/catalog/service/CatalogLocationsEntity.java`);
  for (const field of ['address', 'details', 'capacity', 'opened', 'meeting', 'closing', 'active', 'email', 'phone', 'website']) {
    assert.ok(java.includes(`"${field}"`));
  }
  assert.match(java, /instant\("meeting"/);
  assert.match(java, /time\("closing"/);
  const sql = fs.readdirSync(path.join(root, PATHS.migrations)).filter((name) => name.includes('catalog_locations_table'));
  assert.equal(sql.length, 1);
  const table = read(`${PATHS.migrations}/${sql[0]}`);
  assert.match(table, /meeting timestamptz/);
  assert.match(table, /closing time/);
  assert.match(table, /details text/);
});

test('a second form reuses the same compatible module without losing catalog entries', () => {
  assert.equal(run(), 0);
  const next = declaration(); next.entity.name = 'another'; write(pack(next));
  assert.equal(run(), 0);
  assert.match(read(PATHS.moduleMap), /studioqa\.records/);
  assert.match(read(PATHS.moduleMap), /studioqa\.another/);
  assert.ok(fs.existsSync(path.join(root, generated)));
});

test('listed false omits the list column but preserves the form field and its stored data column', () => {
  const d = declaration(); d.fields[0].listed = false; write(pack(d));
  assert.equal(run(), 0);
  const java = read(generated);
  const field = java.slice(java.indexOf('.field(text("note"'), java.indexOf('.field(number("amount"'));
  assert.doesNotMatch(field, /\.list\(/);
  assert.match(field, /\.formOnly\(\)/);
  assert.match(java, /"note", "amount", "day"/);
  const sql = fs.readdirSync(path.join(root, PATHS.migrations)).find((name) => name.endsWith('studioqa_records_table.sql'));
  assert.match(read(`${PATHS.migrations}/${sql}`), /note text not null/);
});

test('the default delivery includes the Russian fallback in the same write plan', () => {
  assert.equal(main(['form', 'apply', input, '--root', root], () => {}), 0);
  assert.match(read('apps/web/src/app/core/i18n/packaged-russian.ts'), /studioqa\.records\.col\.note/);
  const recorded = JSON.parse(read(receipt));
  assert.ok(recorded.files.some((file) => file.path.endsWith('packaged-russian.ts')));
});

test('Java Unicode escapes and Javadoc terminators in translated titles never become source syntax', () => {
  const d = declaration();
  d.module.title = { ...labels, en: String.raw`Module \u000a */ @Deprecated` };
  d.entity.title = { ...labels, en: String.raw`Entity \u000a */ @Deprecated` };
  write(pack(d));
  assert.equal(run(), 0);
  assert.doesNotMatch(read(generated), /\\u000a/);
  assert.doesNotMatch(read(`${PATHS.serverJava}/studioqa/package-info.java`), /\\u000a/);
  const en = JSON.parse(read(`${PATHS.catalogs}/en.json`));
  assert.equal(en['nav.studioqa_records'], d.entity.title.en);
});

test('module titles remain literal text in a six-column Markdown map and unchanged in catalogs', () => {
  const d = declaration();
  const title = 'Sales | *Stock* _items_ [link](missing.md) <b> &copy; \\ `code` ~old~';
  d.module.title = { ru: title, uz: title, en: title };
  write(pack(d));
  assert.equal(run(), 0);
  const row = read(PATHS.moduleMap).split('\n').find((line) => line.startsWith('| `studioqa` |'));
  const cells = row.split('|').slice(1, -1).map((cell) => cell.trim());
  assert.equal(cells.length, 6);
  assert.ok(cells[2].startsWith('Sales &#124; &#42;Stock&#42; &#95;items&#95; '
    + '&#91;link&#93;&#40;missing&#46;md&#41; &#60;b&#62; &#38;copy&#59; '
    + '&#92; &#96;code&#96; &#126;old&#126;:'));
  assert.equal(cells[3], '`studioqa_*`');
  assert.equal(cells[4], '`studioqa`');
  assert.match(cells[5], /\/api\/v1\/entities\/studioqa\.records/);
  for (const language of ['ru', 'uz', 'en']) {
    assert.equal(JSON.parse(read(`${PATHS.catalogs}/${language}.json`))['studioqa.module.name'], title);
  }
});

test('a held delivery lock rejects a second writer and preserves the interrupted-process evidence', () => {
  fs.mkdirSync(path.join(root, '.local'));
  fs.writeFileSync(path.join(root, '.local/cms-form-delivery.lock'), 'previous writer');
  const before = snapshot();
  assert.throws(() => run(), /Concurrent delivery|interrupted/);
  assert.deepEqual(snapshot(), before);
});


for (const token of ['1.0', '1e0']) test(`non-integer JSON token ${token} cannot masquerade as formatVersion`, () => {
  write(JSON.stringify(pack()).replace('"formatVersion":1', `"formatVersion":${token}`));
  const before = snapshot();
  assert.throws(() => run(), /integer|invalid/i);
  assert.deepEqual(snapshot(), before);
});

test('Unicode format controls are rejected independently of the backend', () => {
  const d = declaration(); d.entity.title = { ...labels, en: 'A\u200bB' }; write(pack(d));
  assert.throws(() => run(), /invalid|control/i);
});

test('maximum supported names produce PostgreSQL identifiers without truncation', () => {
  const d = declaration(); d.module.code = 'abcdefghijkl'; d.entity.name = 'abcdefghijklmnopqrst';
  d.fields[0].id = 'abcdefghijklmnopqrst'; write(pack(d)); assert.equal(run(), 0);
  for (const file of fs.readdirSync(path.join(root, PATHS.migrations)).filter((name) => name.includes('abcdefghijkl_abcdefghijklmnopqrst'))) {
    const sql = read(`${PATHS.migrations}/${file}`);
    for (const match of sql.matchAll(/(?:constraint|create(?: unique)? index|create table) ([a-z0-9_]+)/g)) {
      assert.ok(match[1].length <= 63, match[1]);
    }
  }
});


test('rollback preserves a concurrent hand edit and keeps the lock when recovery is incomplete', () => {
  const plan = new Plan(root);
  plan.patch(PATHS.coverageFloors, (s) => s + '\nqa,80,50\n', 'first write');
  fs.writeFileSync(path.join(root, 'blocking-parent'), 'blocking file');
  plan.create('blocking-parent/new.txt', 'new', 'later failure');
  assert.throws(() => plan.apply({ atomic: true, log: (message) => {
    if (message.includes('later failure')) fs.appendFileSync(path.join(root, PATHS.coverageFloors), '\nmanual,80,50\n');
  } }), /rollback incomplete/i);
  assert.match(read(PATHS.coverageFloors), /manual,80,50/);
  assert.ok(fs.existsSync(path.join(root, '.local/cms-form-delivery.lock')));
});

test('atomic writes never follow a junction outside the repository root', () => {
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'cms-form-outside-'));
  try {
    const link = path.join(root, 'escape');
    fs.symlinkSync(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
    const plan = new Plan(root);
    plan.create('escape/new.txt', 'outside', 'unsafe');
    assert.throws(() => plan.apply({ atomic: true, log: () => {} }), /outside|escape|repository/i);
    assert.deepEqual(fs.readdirSync(outside), []);
    fs.unlinkSync(link);
  } finally { fs.rmSync(outside, { recursive: true, force: true }); }
});


test('the shared supplementary-Unicode fixture has the same canonical digest in the CLI', () => {
  const file = path.join(repo, 'apps/server/src/test/resources/studio/simple-form-unicode.json');
  const d = JSON.parse(fs.readFileSync(file, 'utf8'));
  const expected = fs.readFileSync(file.replace(/\.json$/, '.sha256'), 'utf8').trim();
  write({ formatVersion: 1, kind: 'simple-form-package', declaration: d, checksum: expected });
  assert.equal(run(), 0);
  assert.equal(JSON.parse(read('docs/declarations/catalog.locations.json')).checksum, expected);
});
